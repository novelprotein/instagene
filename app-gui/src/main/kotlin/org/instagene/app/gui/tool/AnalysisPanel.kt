package org.instagene.app.gui.tool

import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.AdvancedSearch
import org.instagene.core.Alignment
import org.instagene.core.AlignmentParameters
import org.instagene.core.Assembly
import org.instagene.core.AssemblyWorkflows
import org.instagene.core.ChromatogramReader
import org.instagene.core.Enzyme
import org.instagene.core.EnzymeAnalysis
import org.instagene.core.Enzymes
import org.instagene.core.GelLane
import org.instagene.core.Ladder
import org.instagene.core.MolecularCalculators
import org.instagene.core.MethylationProfile
import org.instagene.core.NcbiClient
import org.instagene.core.Recombination
import org.instagene.core.SearchMode
import org.instagene.core.SearchRequest
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.SequenceIdentity
import org.instagene.core.VirtualGel
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.JOptionPane
import javax.swing.SpinnerNumberModel
import javax.swing.SwingWorker
import javax.swing.table.DefaultTableModel

/** Persistent GUI workspace for the analysis APIs added for ApE parity. */
class AnalysisPanel(
    initial: SeqDocument,
    private val onOpenSequence: (Seq) -> Unit,
    private val onReveal: (Int, Int) -> Unit,
) : JPanel(BorderLayout()) {
    private var doc = initial
    private var listener: SeqDocument.Listener? = null
    private val tabs = JTabbedPane()
    private val search = SearchAnalysisPanel(onReveal)
    private val alignment = AlignmentAnalysisPanel()
    private val enzymes = EnzymeAnalysisPanel()
    private val assembly = AssemblyAnalysisPanel(onOpenSequence)
    private val gel = GelAnalysisPanel()
    private val calculators = CalculatorAnalysisPanel()
    private val ncbi = NcbiAnalysisPanel(onOpenSequence)
    private val chromatogram = ChromatogramAnalysisPanel()

    init {
        tabs.addTab("Search", search)
        tabs.addTab("Alignment", alignment)
        tabs.addTab("Enzymes", enzymes)
        tabs.addTab("Assembly", assembly)
        tabs.addTab("Virtual Gel", gel)
        tabs.addTab("Calculators", calculators)
        tabs.addTab("NCBI / BLAST", ncbi)
        tabs.addTab("Chromatogram", chromatogram)
        add(tabs, BorderLayout.CENTER)
        bindDocument(initial)
    }

    fun bindDocument(newDoc: SeqDocument) {
        val changed = newDoc !== doc
        if (changed) {
            listener?.let { doc.removeListener(it) }
            doc = newDoc
        }
        if (listener == null) {
            listener = SeqDocument.Listener { _, reason ->
                if (reason == SeqDocument.Reason.SEQUENCE || reason == SeqDocument.Reason.SELECTION) refreshChildren()
            }
        }
        if (changed || !docListenerAttached) {
            doc.addListener(listener!!)
        } else if (docListenerAttached.not()) {
            doc.addListener(listener!!)
        }
        refreshChildren()
    }

    private var docListenerAttached = false

    private fun refreshChildren() {
        docListenerAttached = true
        search.bindDocument(doc)
        alignment.bindDocument(doc)
        enzymes.bindDocument(doc)
        assembly.bindDocument(doc)
        gel.bindDocument(doc)
        calculators.bindDocument(doc)
        ncbi.bindDocument(doc)
        chromatogram.bindDocument(doc)
    }

    fun selectTool(name: String) {
        for (i in 0 until tabs.tabCount) if (tabs.getTitleAt(i).equals(name, ignoreCase = true)) tabs.selectedIndex = i
    }

    /** Visible tool names, exposed for headless GUI smoke tests. */
    fun toolNames(): List<String> = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }

    fun selectedTool(): String = tabs.getTitleAt(tabs.selectedIndex)
}

private abstract class BoundAnalysisPanel : JPanel(BorderLayout()) {
    protected lateinit var doc: SeqDocument
    fun bindDocument(value: SeqDocument) {
        doc = value
        refreshDocument()
    }
    protected open fun refreshDocument() {}
    protected fun row(vararg components: java.awt.Component): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
        components.forEach { add(it) }
    }
    protected fun output(): JTextArea = JTextArea(12, 80).apply {
        isEditable = false
        lineWrap = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }
    protected fun chooseSequence(): Seq? {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null
        return runCatching { SeqIO.read(chooser.selectedFile) }.getOrElse {
            JOptionPane.showMessageDialog(this, it.message ?: "Unable to read sequence", "Open sequence", JOptionPane.ERROR_MESSAGE)
            null
        }
    }
}

private class SearchAnalysisPanel(private val onReveal: (Int, Int) -> Unit) : BoundAnalysisPanel() {
    private val pattern = JTextField(18)
    private val mode = JComboBox(arrayOf("DNA / degenerate", "Literal", "Amino acid"))
    private val bothStrands = JCheckBox("Both strands", true)
    private val caseSensitive = JCheckBox("Case-sensitive")
    private val mismatches = JSpinner(SpinnerNumberModel(0, 0, 3, 1))
    private val threePrime = JSpinner(SpinnerNumberModel(0, 0, 100, 1))
    private val model = DefaultTableModel(arrayOf("Start", "End", "Strand", "Mismatches", "Match", "Frame"), 0)
    private val table = JTable(model)
    private val status = JLabel("Enter a pattern to search.")

    init {
        val run = JButton("Search")
        run.addActionListener { execute() }
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Pattern"), pattern, JLabel("Mode"), mode, bothStrands, caseSensitive))
            add(row(JLabel("Max mismatches"), mismatches, JLabel("3' exact bases"), threePrime, run))
        }
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) revealSelected()
            }
        })
        add(controls, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun execute() {
        if (pattern.text.isBlank()) return
        val searchMode = when (mode.selectedIndex) {
            1 -> SearchMode.LITERAL
            2 -> SearchMode.AMINO_ACID
            else -> SearchMode.DNA_DEGENERATE
        }
        runCatching {
            AdvancedSearch.find(doc.seq, SearchRequest(
                pattern.text.trim(), searchMode, bothStrands.isSelected, caseSensitive.isSelected,
                (mismatches.value as Number).toInt(), (threePrime.value as Number).toInt(),
            ))
        }.onSuccess { hits ->
            model.rowCount = 0
            hits.forEach { model.addRow(arrayOf<Any?>(it.start + 1, it.end, it.strand.symbol, it.mismatches, it.matched, it.frame ?: "")) }
            status.text = "${hits.size} match(es) in ${doc.seq.name}. Double-click a row to reveal it."
        }.onFailure { status.text = it.message ?: "Search failed" }
    }

    private fun revealSelected() {
        val row = table.selectedRow
        if (row >= 0) onReveal((model.getValueAt(row, 0) as Int) - 1, model.getValueAt(row, 1) as Int)
    }
}

private class AlignmentAnalysisPanel : BoundAnalysisPanel() {
    private val queryNames = JTextField(30)
    private val mismatch = JTextField("0.1", 5)
    private val gap = JTextField("1.5", 5)
    private val extension = JTextField("0.5", 5)
    private val text = output()
    private var queryFiles: List<File> = emptyList()

    init {
        val choose = JButton("Choose query files...")
        choose.addActionListener {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                queryFiles = chooser.selectedFiles.toList()
                queryNames.text = queryFiles.joinToString(", ") { it.name }
            }
        }
        val run = JButton("Align")
        run.addActionListener { execute() }
        add(row(choose, queryNames, JLabel("Mismatch"), mismatch, JLabel("Gap"), gap, JLabel("Extension"), extension, run), BorderLayout.NORTH)
        add(JScrollPane(text), BorderLayout.CENTER)
    }

    private fun execute() {
        if (queryFiles.isEmpty()) return
        runCatching {
            Alignment.align(doc.seq, queryFiles.map { SeqIO.read(it) }, AlignmentParameters(
                mismatchPenalty = -mismatch.text.toDouble(), gapPenalty = -gap.text.toDouble(), gapExtensionPenalty = -extension.text.toDouble(),
            ))
        }.onSuccess { result ->
            text.text = buildString {
                append("Reference: ${result.reference.name}\n\n")
                result.queries.forEach { query ->
                    append("${query.name}: score=${"%.2f".format(query.score)} matches=${query.matches} mismatches=${query.mismatches} gaps=${query.gaps}\n")
                    append(query.sequence).append("\n\n")
                }
            }
        }.onFailure { text.text = it.message ?: "Alignment failed" }
    }
}

private class EnzymeAnalysisPanel : BoundAnalysisPanel() {
    private val names = JTextField("EcoRI,BamHI", 24)
    private val dam = JCheckBox("Dam methylated")
    private val dcm = JCheckBox("Dcm methylated")
    private val output = output()

    init {
        val report = JButton("Restriction report")
        report.addActionListener { execute { enzymes -> EnzymeAnalysis.reports(doc.seq, enzymes).joinToString("\n") { "${it.enzyme.name}\t${it.count}\t${it.positions.joinToString(",")}" } } }
        val unique = JButton("Unique / absent")
        unique.addActionListener { execute { enzymes -> "Unique: ${EnzymeAnalysis.unique(doc.seq, enzymes).joinToString { it.name }}\nAbsent: ${EnzymeAnalysis.absent(doc.seq, enzymes).joinToString { it.name }}" } }
        val methylation = JButton("Methylation-filtered sites")
        methylation.addActionListener { execute { enzymes -> EnzymeAnalysis.cutSites(doc.seq, enzymes, MethylationProfile(dam.isSelected, dcm.isSelected)).joinToString("\n") { "${it.enzyme.name}\t${it.recognitionStart + 1}" } } }
        val diagnostic = JButton("Diagnostic sites")
        diagnostic.addActionListener { execute { enzymes -> EnzymeAnalysis.diagnosticSites(doc.seq, 0 until doc.seq.length, enzymes).joinToString("\n") { "${it.enzyme.name}\t${it.position + 1}\t${it.original} -> ${it.mutated}" } } }
        val silent = JButton("Silent sites")
        silent.addActionListener { execute { enzymes -> EnzymeAnalysis.silentSites(doc.seq, 0 until doc.seq.length, enzymes).joinToString("\n") { "${it.enzyme.name}\t${it.position + 1}\t${it.original} -> ${it.mutated}" } } }
        val recognition = JButton("Recognition preview")
        recognition.addActionListener { execute { enzymes -> enzymes.joinToString("\n") { "${it.name}\tforward=${EnzymeAnalysis.insertRecognitionSite(it)}\treverse=${EnzymeAnalysis.insertRecognitionSite(it, true)}" } } }
        add(row(JLabel("Enzymes"), names, dam, dcm), BorderLayout.NORTH)
        add(row(report, unique, methylation, diagnostic, silent, recognition), BorderLayout.SOUTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute(action: (List<Enzyme>) -> String) {
        runCatching { action(Enzymes.parseList(names.text)) }.onSuccess { output.text = it.ifBlank { "No results." } }.onFailure { output.text = it.message ?: "Analysis failed" }
    }
}

private class AssemblyAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val mode = JComboBox(arrayOf("Gibson", "Golden Gate", "Restriction ligation", "Homology recombination"))
    private val parts = JTextField(36)
    private val enzymes = JTextField("EcoRI", 12)
    private val overhangs = JTextField("A,G,A", 12)
    private val arm = JSpinner(SpinnerNumberModel(20, 1, 1000, 1))
    private val name = JTextField("assembly_product", 18)
    private val circular = JCheckBox("Circular product", true)
    private val output = output()
    private var product: Seq? = null

    init {
        val choose = JButton("Choose parts...")
        choose.addActionListener {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) parts.text = chooser.selectedFiles.joinToString(",") { it.absolutePath }
        }
        val run = JButton("Preview")
        run.addActionListener { execute() }
        val open = JButton("Open product")
        open.addActionListener { product?.let(onOpenSequence) }
        val save = JButton("Save product")
        save.addActionListener {
            val result = product ?: return@addActionListener
            val chooser = JFileChooser().apply { dialogTitle = "Save assembly product" }
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                runCatching { chooser.selectedFile.writeText(SeqIO.write(result, SeqIO.formatOf(chooser.selectedFile))) }
                    .onFailure { JOptionPane.showMessageDialog(this, it.message ?: "Unable to save product", "Assembly", JOptionPane.ERROR_MESSAGE) }
            }
        }
        add(row(JLabel("Workflow"), mode, choose, parts), BorderLayout.NORTH)
        add(row(JLabel("Enzymes"), enzymes, JLabel("Overhangs"), overhangs, JLabel("Homology arm"), arm, JLabel("Name"), name, circular, run, open, save), BorderLayout.SOUTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        val files = parts.text.split(',').map(String::trim).filter(String::isNotEmpty)
        runCatching {
            when (mode.selectedIndex) {
                0 -> Assembly.gibson(files.map { SeqIO.read(File(it)) }, name = name.text, circular = circular.isSelected).product
                1 -> AssemblyWorkflows.goldenGate(files.map { SeqIO.read(File(it)) }, overhangs.text.split(',').map(String::trim), name.text, circular.isSelected).product
                2 -> {
                    val cuts = Enzymes.parseList(enzymes.text)
                    val fragments = files.map { SeqIO.read(File(it)) }.map { seq ->
                        val digest = org.instagene.core.Digest.digest(seq, cuts)
                        require(digest.size == 1) { "Each part must yield exactly one digest fragment" }
                        org.instagene.core.TreatedFragment(digest.single())
                    }
                    AssemblyWorkflows.restrictionLigation(fragments, name.text, circular.isSelected).product
                }
                else -> {
                    require(files.isNotEmpty()) { "Choose a donor file" }
                    val donor = SeqIO.read(File(files.first()))
                    val candidates = Recombination.candidates(doc.seq, donor, (arm.value as Number).toInt())
                    require(candidates.isNotEmpty()) { "No matching homology-arm candidate found" }
                    Recombination.recombine(doc.seq, donor, candidates.first(), name.text).product
                }
            }
        }.onSuccess { result ->
            product = result
            output.text = "Product: ${result.name}\nLength: ${result.length}\nTopology: ${result.topology}\n\n${result.bases.chunked(80).joinToString("\n")}"
        }.onFailure { product = null; output.text = it.message ?: "Assembly failed" }
    }
}

private class GelAnalysisPanel : BoundAnalysisPanel() {
    private val enzymes = JTextField("EcoRI", 18)
    private val completion = JSpinner(SpinnerNumberModel(100, 0, 100, 5))
    private val ladder = JTextField("10000,5000,2000,1000,500", 24)
    private val output = output()

    init {
        val run = JButton("Run gel")
        run.addActionListener { execute() }
        add(row(JLabel("Enzymes"), enzymes, JLabel("Completion %"), completion, JLabel("Ladder bp"), ladder, run), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        runCatching {
            val lanes = listOf(
                GelLane.SizeStandard("Ladder", Ladder("Custom", ladder.text.split(',').mapNotNull(String::trim).mapNotNull(String::toIntOrNull))),
                GelLane.Dna(doc.seq.name, doc.seq, Enzymes.parseList(enzymes.text), (completion.value as Number).toInt()),
            )
            VirtualGel.run(lanes)
        }.onSuccess { result ->
            output.text = result.lanes.joinToString("\n\n") { lane ->
                "${lane.name}\n" + lane.bands.joinToString("\n") { "  ${it.sizeBp} bp\tintensity=${"%.2f".format(it.relativeIntensity)}\tmigration=${"%.3f".format(result.migration(it.sizeBp))}" }
            }
        }.onFailure { output.text = it.message ?: "Gel simulation failed" }
    }
}

private class CalculatorAnalysisPanel : BoundAnalysisPanel() {
    private val operation = JComboBox(arrayOf("Dilution", "Molecular weight", "nM from mass", "Mass from nM", "Master mix"))
    private val a = JTextField("100", 8)
    private val b = JTextField("10", 8)
    private val c = JTextField("100", 8)
    private val recipe = JTextField("Buffer=2,Water=5", 28)
    private val output = output()

    init {
        val run = JButton("Calculate")
        run.addActionListener { execute() }
        add(row(JLabel("Operation"), operation, JLabel("A"), a, JLabel("B"), b, JLabel("C"), c, JLabel("Recipe"), recipe, run), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        runCatching {
            when (operation.selectedIndex) {
                0 -> MolecularCalculators.dilution(a.text.toDouble(), b.text.toDouble(), c.text.toDouble()).let { "Stock: ${it.stockVolumeUl} µl\nDiluent: ${it.diluentVolumeUl} µl\nFinal: ${it.finalVolumeUl} µl" }
                1 -> "Molecular weight: ${MolecularCalculators.molecularWeight(doc.seq)} Da"
                2 -> "Concentration: ${MolecularCalculators.nanomolar(a.text.toDouble(), b.text.toDouble(), c.text.toDouble())} nM"
                3 -> "Mass: ${MolecularCalculators.massNg(a.text.toDouble(), b.text.toDouble(), c.text.toDouble())} ng"
                else -> MolecularCalculators.masterMix(MolecularCalculators.parseRecipe(recipe.text).map { org.instagene.core.MasterMixComponent(it.first, it.second) }, 1).let { it.components.joinToString("\n") { c -> "${c.name}: ${c.volumeUl} µl" } }
            }
        }.onSuccess { output.text = it }.onFailure { output.text = it.message ?: "Calculation failed" }
    }
}

private class NcbiAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val term = JTextField(30)
    private val model = DefaultTableModel(arrayOf("Accession", "Title"), 0)
    private val table = JTable(model)
    private val output = output()
    private var hits = emptyList<org.instagene.core.NcbiHit>()

    init {
        val search = JButton("Search NCBI")
        search.addActionListener { search() }
        val fetch = JButton("Fetch selected GenBank")
        fetch.addActionListener { fetch() }
        val blast = JButton("Open BLAST")
        blast.addActionListener { openBlast() }
        add(row(JLabel("Nucleotide search"), term, search, fetch, blast), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 80) }, BorderLayout.SOUTH)
    }

    private fun search() {
        val query = term.text.trim()
        if (query.isEmpty()) return
        output.text = "Searching NCBI..."
        object : SwingWorker<org.instagene.core.NcbiSearchResult, Unit>() {
            override fun doInBackground() = NcbiClient().searchNucleotide(query)
            override fun done() {
                runCatching { get() }.onSuccess { result ->
                    hits = result.hits
                    model.rowCount = 0
                    hits.forEach { model.addRow(arrayOf<Any?>(it.accession, it.title)) }
                    output.text = "${hits.size} result(s)."
                }.onFailure { output.text = it.message ?: "NCBI search failed" }
            }
        }.execute()
    }

    private fun fetch() {
        val row = table.selectedRow
        if (row !in hits.indices) return
        output.text = "Fetching ${hits[row].accession}..."
        object : SwingWorker<Seq, Unit>() {
            override fun doInBackground() = NcbiClient().fetchGenBank(hits[row].accession)
            override fun done() {
                runCatching { onOpenSequence(get()) }.onFailure { output.text = it.message ?: "NCBI fetch failed" }
            }
        }.execute()
    }

    private fun openBlast() {
        runCatching {
            val uri = NcbiClient().blastUrl(doc.seq, selection = if (doc.hasSelection) doc.selectionStart until doc.selectionEnd else null)
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
            uri
        }.onSuccess { output.text = "Opened $it" }.onFailure { output.text = it.message ?: "Unable to open BLAST" }
    }
}

private class ChromatogramAnalysisPanel : BoundAnalysisPanel() {
    private val fileField = JTextField(36)
    private val output = output()

    init {
        val choose = JButton("Open chromatogram...")
        choose.addActionListener { open() }
        add(row(choose, fileField), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun open() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        fileField.text = file.absolutePath
        runCatching {
            val bytes = file.readBytes()
            if (ChromatogramReader.looksLikeAbi(bytes)) ChromatogramReader.readAbi(bytes, file.name)
            else ChromatogramReader.readScf(bytes, file.name)
        }.onSuccess { record ->
            output.text = buildString {
                append("${record.name}: ${record.bases.length} called bases\n\n")
                record.bases.forEachIndexed { index, base ->
                    val quality = record.qualities.getOrNull(index) ?: 0
                    append("%6d  %c  quality=%3d  %s\n".format(index + 1, base, quality, "#".repeat((quality / 5).coerceAtMost(20))))
                }
            }
        }.onFailure { output.text = it.message ?: "Unable to read chromatogram" }
    }
}
