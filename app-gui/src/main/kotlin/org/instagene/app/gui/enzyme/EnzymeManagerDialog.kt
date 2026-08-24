package org.instagene.app.gui.enzyme

import org.instagene.app.gui.TableLabels
import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.Enzyme
import org.instagene.core.LabLibraryFiles
import org.instagene.core.LibraryImportMode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JFileChooser
import javax.swing.JPopupMenu
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

/**
 * Modal dialog for managing the enzyme catalog: choosing the active enzymes
 * and adding or removing custom enzymes. Edits are made against an
 * [EnzymeManagerModel] and persist only when the user selects Done.
 */
class EnzymeManagerDialog(
    private val prefs: Prefs,
    private val owner: Frame? = null,
    initialName: String? = null,
) : JDialog(owner, "Manage Enzymes", true) {

    private val model = EnzymeManagerModel(prefs)
    private val tableModel = EnzymeTableModel(model)
    private val enzymeTable = JTable(tableModel)
    private val nameField = JTextField(12)
    private val siteField = JTextField(10)
    private val topCutSpinner = JSpinner(SpinnerNumberModel(1, 0, 40, 1))
    private val bottomCutSpinner = JSpinner(SpinnerNumberModel(1, 0, 40, 1))
    private val validationLabel = JLabel(" ")

    init {
        enzymeTable.rowHeight = 20
        enzymeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        enzymeTable.columnModel.getColumn(0).apply {
            minWidth = 44
            maxWidth = 44
            preferredWidth = 44
        }
        enzymeTable.columnModel.getColumn(4).maxWidth = 70
        model.addListener { tableModel.fireTableDataChanged() }
        enzymeTable.installRowContextMenu { row -> enzymeManagerPopup(row) }

        contentPane.layout = BorderLayout(0, 6)
        contentPane.add(buildForm(), BorderLayout.NORTH)
        contentPane.add(JScrollPane(enzymeTable), BorderLayout.CENTER)
        contentPane.add(buildButtons(), BorderLayout.SOUTH)

        if (initialName != null) nameField.text = initialName
        pack()
        setLocationRelativeTo(owner)
        minimumSize = Dimension(620, 380)
    }

    private fun buildForm(): JPanel = JPanel().apply {
        border = BorderFactory.createTitledBorder("Add novel enzyme")
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Site"))
            add(siteField)
            add(JLabel("Top cut"))
            add(topCutSpinner)
            add(JLabel("Bottom cut"))
            add(bottomCutSpinner)
            add(JButton("Add").apply {
                addActionListener { addFromForm() }
            })
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(validationLabel)
        })
    }

    private fun buildButtons(): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
        add(JButton("Import set…").apply {
            toolTipText = "Import a versioned, self-contained enzyme-set JSON file into this working catalog."
            addActionListener { importSet() }
        })
        add(JButton("Export active set…").apply {
            toolTipText = "Export the currently enabled enzymes as a versioned JSON lab set."
            addActionListener { exportSet() }
        })
        add(JButton("Remove selected").apply {
            addActionListener { removeSelected() }
        })
        add(JButton("Done").apply {
            addActionListener {
                this@EnzymeManagerDialog.model.commit()
                dispose()
            }
        })
    }

    private fun importSet() {
        val chooser = JFileChooser().apply { dialogTitle = "Import enzyme set" }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching { LabLibraryFiles.readEnzymeSet(chooser.selectedFile) }.onSuccess { imported ->
            val choice = JOptionPane.showOptionDialog(
                this,
                "Import '${imported.name}' (${imported.enzymes.size} enzyme(s)).\nMerge keeps the active set; replace changes only which enzymes are active.",
                "Import enzyme set",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                arrayOf("Merge active set", "Replace active set", "Cancel"),
                "Merge active set",
            )
            val mode = when (choice) {
                0 -> LibraryImportMode.MERGE
                1 -> LibraryImportMode.REPLACE
                else -> return@onSuccess
            }
            val error = model.importSet(imported, mode)
            if (error == null) {
                validationLabel.foreground = Color(0x2E, 0x7D, 0x32)
                validationLabel.text = "Imported ${imported.enzymes.size} enzyme(s) from ${imported.name}."
            } else {
                validationLabel.foreground = Color(0xC0, 0x39, 0x2B)
                validationLabel.text = error
            }
        }.onFailure { error ->
            JOptionPane.showMessageDialog(this, error.message ?: "Unable to import enzyme set.", "Import enzyme set", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun exportSet() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export active enzyme set"
            selectedFile = File("enzyme-set${LabLibraryFiles.ENZYME_SET_SUFFIX}")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val destination = chooser.selectedFile
        val name = destination.name.removeSuffix(LabLibraryFiles.ENZYME_SET_SUFFIX).removeSuffix(".json")
        runCatching { LabLibraryFiles.write(destination, model.exportSet(name)) }.onSuccess {
            validationLabel.foreground = Color(0x2E, 0x7D, 0x32)
            validationLabel.text = "Exported ${model.activeEnzymes().size} active enzyme(s) to ${destination.name}."
        }.onFailure { error ->
            JOptionPane.showMessageDialog(this, error.message ?: "Unable to export enzyme set.", "Export enzyme set", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun addFromForm() {
        val error = model.addEnzyme(
            name = nameField.text,
            site = siteField.text,
            topCut = (topCutSpinner.value as Number).toInt(),
            bottomCut = (bottomCutSpinner.value as Number).toInt(),
        )
        if (error == null) {
            validationLabel.text = " "
            nameField.text = ""
            siteField.text = ""
            topCutSpinner.value = 1
            bottomCutSpinner.value = 1
        } else {
            validationLabel.foreground = Color(0xC0, 0x39, 0x2B)
            validationLabel.text = error
        }
    }

    private fun removeSelected() {
        val row = enzymeTable.selectedRow
        if (row !in 0 until tableModel.rowCount) return
        val enzyme = tableModel.enzymeAt(row)
        if (!model.isCustom(enzyme)) {
            JOptionPane.showMessageDialog(
                this,
                "'${enzyme.name}' is a built-in enzyme and cannot be removed.",
                "Cannot Remove",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        model.removeEnzyme(enzyme)
    }

    private fun enzymeManagerPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val enzyme = row?.takeIf { it in 0 until tableModel.rowCount }?.let { tableModel.enzymeAt(it) }
        val hasEnzyme = enzyme != null
        add(ContextMenus.item(
            if (enzyme != null && model.isEnabled(enzyme)) "Disable Enzyme" else "Enable Enzyme",
            "Toggle whether this enzyme is included in the active working set.",
            hasEnzyme,
        ) {
            if (enzyme != null) model.setEnabled(enzyme, !model.isEnabled(enzyme))
        })
        add(ContextMenus.item(
            "Edit Element…",
            "Edit this enzyme's name, recognition site, cut positions, enabled state, and description.",
            hasEnzyme,
        ) { if (row != null) editSelected(row) })
        add(ContextMenus.item(
            "Remove selected",
            "Remove this enzyme when it is a custom enzyme.",
            enzyme?.let { model.isCustom(it) } == true,
        ) { removeSelected() })
        addSeparator()
        add(ContextMenus.item(
            "Copy enzyme details",
            "Copy this enzyme row as tab-separated text.",
            hasEnzyme,
        ) {
            if (row != null && row in 0 until tableModel.rowCount) {
                ContextMenus.copyToClipboard((0 until tableModel.columnCount).joinToString("\t") { column ->
                    tableModel.getValueAt(row, column).toString()
                })
            }
        })
    }

    private fun editSelected(row: Int) {
        val enzyme = tableModel.enzymeAt(row)
        EnzymeElementDialog(prefs, enzyme, owner).isVisible = true
        val selected = model.pool.indexOfFirst { it.name.equals(enzyme.name, ignoreCase = true) }
        if (selected >= 0) enzymeTable.selectionModel.setSelectionInterval(selected, selected)
    }

    private class EnzymeTableModel(private val model: EnzymeManagerModel) : AbstractTableModel() {

        private val columns = arrayOf(
            TableLabels.USE,
            TableLabels.ENZYME,
            TableLabels.RECOGNITION_SITE,
            TableLabels.CUT_COUNT,
            TableLabels.ORIGIN,
        )

        fun enzymeAt(row: Int): Enzyme = model.pool[row]

        override fun getRowCount(): Int = model.pool.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val enzyme = enzymeAt(rowIndex)
            return when (columnIndex) {
                0 -> model.isEnabled(enzyme)
                1 -> enzyme.name
                2 -> enzyme.site
                3 -> "${enzyme.topCut}/${enzyme.bottomCut}"
                else -> if (model.isCustom(enzyme)) "custom" else "built-in"
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            model.setEnabled(enzymeAt(rowIndex), value == true)
        }
    }
}
