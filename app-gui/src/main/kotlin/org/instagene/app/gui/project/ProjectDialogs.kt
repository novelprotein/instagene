package org.instagene.app.gui.project

import org.instagene.app.gui.file.FileType
import org.instagene.app.gui.file.FileTypes
import org.instagene.app.gui.row
import org.instagene.core.FeatureDefinition
import org.instagene.core.MoleculeProperties
import org.instagene.core.Strandedness
import org.instagene.core.Topology
import org.instagene.core.io.SeqFormat
import org.instagene.core.project.BatchOperations
import org.instagene.core.project.BatchResult
import org.instagene.core.project.CollectionDocument
import org.instagene.core.project.CollectionStore
import org.instagene.core.project.SeqProject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.JSpinner
import javax.swing.SwingWorker
import javax.swing.table.DefaultTableModel

object ProjectDialogs {
    fun showCollections(frame: JFrame?, project: SeqProject, initialFile: File?, onOpenFile: (File) -> Unit) {
        val panel = ProjectCollectionsPanel(project, initialFile, onOpenFile)
        JOptionPane.showMessageDialog(frame, panel.wrapped(), "Project Collections", JOptionPane.PLAIN_MESSAGE)
    }

    fun showBatch(
        frame: JFrame?,
        project: SeqProject,
        operation: BatchOperation,
        initialFiles: List<File>,
        onComplete: () -> Unit,
    ) {
        val panel = BatchOperationPanel(project, operation, initialFiles, onComplete)
        JOptionPane.showMessageDialog(frame, panel.wrapped(), operation.displayName, JOptionPane.PLAIN_MESSAGE)
    }

    private fun JPanel.wrapped(): JScrollPane =
        JScrollPane(this).apply { preferredSize = Dimension(820, 460) }
}

enum class BatchOperation(val displayName: String) {
    CONVERT("Batch Convert"),
    ANNOTATE("Batch Annotate"),
    PROPERTIES("Batch Update Properties"),
}

class ProjectCollectionsPanel(
    private val project: SeqProject,
    initialFile: File? = null,
    private val onOpenFile: (File) -> Unit = {},
) : JPanel(BorderLayout(8, 8)) {
    private val store = CollectionStore(project)
    private var document: CollectionDocument = store.load()
    private val model = object : DefaultTableModel(arrayOf("Collection", "Area", "File"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    val table = JTable(model)
    val collectionField = JTextField("Main", 14)
    val areaField = JTextField("Sequences", 14)
    val fileField = JTextField(32)
    val statusLabel = JLabel(" ")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        initialFile?.takeIf(File::isFile)?.let {
            fileField.text = project.relativePath(it) ?: it.absolutePath
        }

        val browse = JButton("Choose File...").apply {
            toolTipText = "Choose a sequence file inside this project."
            addActionListener { chooseFile() }
        }
        val add = JButton("Add").apply {
            toolTipText = "Add the selected project file to the named collection area."
            addActionListener { addCurrentFile() }
        }
        val open = JButton("Open").apply {
            toolTipText = "Open the selected collection file."
            addActionListener { openSelected() }
        }
        val remove = JButton("Remove").apply {
            toolTipText = "Remove the selected file from this collection. The file on disk is not deleted."
            addActionListener { removeSelected() }
        }
        val save = JButton("Save").apply {
            toolTipText = "Save project collections to .instagene/collections.json."
            addActionListener { saveCollections() }
        }

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Collection"), collectionField, JLabel("Area"), areaField))
            add(row(JLabel("File"), fileField, browse, add, open, remove, save))
        }, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        refreshTable()
    }

    fun rowCount(): Int = model.rowCount

    fun addProjectFile(collectionName: String, areaName: String, file: File): String? {
        return runCatching {
            require(file.isFile) { "Choose an existing project file." }
            require(project.relativePath(file) != null) { "File is outside the project." }
            document = store.addFile(
                document,
                collectionName.trim().ifBlank { "Main" },
                areaName.trim().ifBlank { "Sequences" },
                file,
            )
            refreshTable()
            statusLabel.text = "Collection updated. Click Save to persist it."
            null
        }.getOrElse { it.message ?: "Unable to add file." }
    }

    fun saveCollections() {
        store.save(document)
        statusLabel.text = "Saved project collections."
    }

    private fun chooseFile() {
        val chooser = JFileChooser(project.root).apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileField.text = project.relativePath(chooser.selectedFile) ?: chooser.selectedFile.absolutePath
        }
    }

    private fun addCurrentFile() {
        val file = resolveProjectFile(fileField.text)
        val error = if (file == null) "Choose a file inside this project." else addProjectFile(collectionField.text, areaField.text, file)
        if (error != null) JOptionPane.showMessageDialog(this, error, "Project Collections", JOptionPane.ERROR_MESSAGE)
    }

    private fun openSelected() {
        selectedFile()?.let(onOpenFile)
    }

    private fun removeSelected() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow = table.convertRowIndexToModel(row)
        val collection = model.getValueAt(modelRow, 0).toString()
        val area = model.getValueAt(modelRow, 1).toString()
        val file = resolveProjectFile(model.getValueAt(modelRow, 2).toString()) ?: return
        document = store.removeFile(document, collection, area, file)
        refreshTable()
        statusLabel.text = "Collection updated. Click Save to persist it."
    }

    private fun selectedFile(): File? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        val modelRow = table.convertRowIndexToModel(row)
        return resolveProjectFile(model.getValueAt(modelRow, 2).toString())
    }

    private fun refreshTable() {
        model.rowCount = 0
        document.collections.forEach { collection ->
            collection.areas.forEach { area ->
                area.files.forEach { file ->
                    model.addRow(arrayOf(collection.name, area.name, file))
                }
            }
        }
        statusLabel.text = if (model.rowCount == 0) "No project collections yet." else "${model.rowCount} collection item(s)."
    }

    private fun resolveProjectFile(text: String): File? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val file = if (File(raw).isAbsolute) File(raw) else project.resolvePath(raw)
        return file?.takeIf { project.relativePath(it) != null }
    }
}

class BatchOperationPanel(
    private val project: SeqProject,
    private val operation: BatchOperation,
    initialFiles: List<File> = emptyList(),
    private val onComplete: () -> Unit = {},
) : JPanel(BorderLayout(8, 8)) {
    val filesArea = JTextArea(6, 72)
    val outputField = JTextField(File(project.root, "batch-output").absolutePath, 42)
    val statusLabel = JLabel(" ")
    private val formatBox = JComboBox(SeqFormat.entries.toTypedArray())
    private val definitionsArea = JTextArea("tag:GGATCN:misc_feature", 4, 40)
    private val topologyBox = JComboBox(arrayOf("Keep topology", "Linear", "Circular"))
    private val strandednessBox = JComboBox(Strandedness.entries.toTypedArray())
    private val dam = JCheckBox("Dam")
    private val dcm = JCheckBox("Dcm")
    private val cpg = JCheckBox("CpG")
    private val fivePrime = JCheckBox("5' phosphorylated", true)
    private val threePrime = JCheckBox("3' phosphorylated")
    private val workerCount = JSpinner(SpinnerNumberModel(1, 1, 1, 1))
    private val runButton = JButton("Run")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        filesArea.text = initialFiles.filter(File::isFile)
            .mapNotNull { project.relativePath(it) ?: it.absolutePath }
            .joinToString("\n")
        runButton.toolTipText = "Run this batch operation. Source files are never overwritten."
        runButton.addActionListener { runBatchAsync() }

        val chooseFiles = JButton("Choose Files...").apply {
            toolTipText = "Choose one or more sequence files inside this project."
            addActionListener { chooseFiles() }
        }
        val useAll = JButton("Use All Sequences").apply {
            toolTipText = "Use every sequence file currently listed in the project tree."
            addActionListener { useAllSequenceFiles() }
        }
        val chooseOutput = JButton("Output...").apply {
            toolTipText = "Choose the output folder for generated files."
            addActionListener { chooseOutputDirectory() }
        }

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(chooseFiles, useAll, JLabel("Output"), outputField, chooseOutput, runButton))
            add(JScrollPane(filesArea))
            add(operationPanel())
        }, BorderLayout.NORTH)
        add(statusLabel, BorderLayout.SOUTH)
        refreshStatus()
    }

    fun selectedFiles(): List<File> = filesArea.text
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { resolveProjectFile(it)?.takeIf(File::isFile) }
        .distinctBy { it.canonicalFile.path }

    fun runBatch(): BatchResult {
        val files = selectedFiles()
        require(files.isNotEmpty()) { "Choose at least one project sequence file." }
        val output = File(outputField.text.trim().ifBlank { File(project.root, "batch-output").absolutePath })
        return when (operation) {
            BatchOperation.CONVERT -> BatchOperations.convert(files, output, formatBox.selectedItem as SeqFormat)
            BatchOperation.ANNOTATE -> BatchOperations.annotate(files, output, parseDefinitions())
            BatchOperation.PROPERTIES -> BatchOperations.transformProperties(files, output, selectedTopology(), selectedMolecule())
        }
    }

    private fun operationPanel(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
        when (operation) {
            BatchOperation.CONVERT -> {
                add(JLabel("Format"))
                add(formatBox)
            }
            BatchOperation.ANNOTATE -> {
                add(JLabel("Feature rules"))
                add(JScrollPane(definitionsArea).apply { preferredSize = Dimension(420, 90) })
            }
            BatchOperation.PROPERTIES -> {
                add(JLabel("Topology"))
                add(topologyBox)
                add(JLabel("Strandedness"))
                add(strandednessBox)
                add(dam)
                add(dcm)
                add(cpg)
                add(fivePrime)
                add(threePrime)
            }
        }
        workerCount.isVisible = false
    }

    private fun chooseFiles() {
        val chooser = JFileChooser(project.root).apply {
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            filesArea.text = chooser.selectedFiles
                .filter { project.relativePath(it) != null }
                .joinToString("\n") { project.relativePath(it) ?: it.absolutePath }
            refreshStatus()
        }
    }

    private fun chooseOutputDirectory() {
        val chooser = JFileChooser(project.root).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputField.text = chooser.selectedFile.absolutePath
        }
    }

    private fun useAllSequenceFiles() {
        filesArea.text = project.root.walkTopDown()
            .filter { it.isFile && !it.toPath().startsWith(File(project.root, SeqProject.MANIFEST_DIR).toPath()) }
            .filter { FileTypes.classify(it) == FileType.SEQUENCE }
            .joinToString("\n") { project.relativePath(it) ?: it.absolutePath }
        refreshStatus()
    }

    private fun runBatchAsync() {
        runButton.isEnabled = false
        statusLabel.text = "Running ${operation.displayName.lowercase()}..."
        object : SwingWorker<BatchResult, Unit>() {
            override fun doInBackground(): BatchResult = runBatch()

            override fun done() {
                runButton.isEnabled = true
                runCatching { get() }.onSuccess { result ->
                    statusLabel.text = "Processed ${result.processed}; failed ${result.failed.size}."
                    onComplete()
                }.onFailure {
                    statusLabel.text = it.message ?: "Batch operation failed."
                }
            }
        }.execute()
    }

    private fun parseDefinitions(): List<FeatureDefinition> {
        val definitions = definitionsArea.text.lineSequence()
            .flatMap { it.split(',').asSequence() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { text ->
                val parts = text.split(':', limit = 4).map(String::trim)
                require(parts.size >= 2) { "Feature rules use name:pattern[:type]." }
                FeatureDefinition(parts[0], parts[1], parts.getOrElse(2) { "misc_feature" }.ifBlank { "misc_feature" })
            }
            .toList()
        require(definitions.isNotEmpty()) { "Enter at least one feature rule." }
        return definitions
    }

    private fun selectedTopology(): Topology? = when (topologyBox.selectedIndex) {
        1 -> Topology.LINEAR
        2 -> Topology.CIRCULAR
        else -> null
    }

    private fun selectedMolecule(): MoleculeProperties = MoleculeProperties(
        strandedness = strandednessBox.selectedItem as Strandedness,
        damMethylated = dam.isSelected,
        dcmMethylated = dcm.isSelected,
        cpgMethylated = cpg.isSelected,
        methylationSource = org.instagene.core.MethylationSource.MANUAL,
        fivePrimePhosphorylated = fivePrime.isSelected,
        threePrimePhosphorylated = threePrime.isSelected,
    )

    private fun resolveProjectFile(text: String): File? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val file = if (File(raw).isAbsolute) File(raw) else project.resolvePath(raw)
        return file?.takeIf { project.relativePath(it) != null }
    }

    private fun refreshStatus() {
        statusLabel.text = "${selectedFiles().size} selected file(s)."
    }
}
