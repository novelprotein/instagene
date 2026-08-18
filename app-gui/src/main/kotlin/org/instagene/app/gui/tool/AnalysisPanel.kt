package org.instagene.app.gui.tool

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.*
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/** Persistent GUI workspace for sequence analysis workflows. */
class AnalysisPanel(
    initial: SeqDocument,
    onOpenSequence: (Seq) -> Unit,
    onReveal: (Int, Int) -> Unit,
    ncbiClient: NcbiClient = NcbiClient(),
    ncbiPollIntervalMillis: Long = 2_000L,
) : JPanel(BorderLayout()) {
    private var doc = initial
    private var listener: SeqDocument.Listener? = null
    private val tabs = JTabbedPane()
    private val panels = listOf<BoundAnalysisPanel>(
        SearchAnalysisPanel(onReveal),
        AlignmentAnalysisPanel(),
        EnzymeAnalysisPanel(),
        AssemblyAnalysisPanel(onOpenSequence),
        PcrAnalysisPanel(onOpenSequence),
        TranslationAnalysisPanel(onOpenSequence),
        GelAnalysisPanel(),
        CalculatorAnalysisPanel(),
        NcbiAnalysisPanel(onOpenSequence, ncbiClient, ncbiPollIntervalMillis),
        ChromatogramAnalysisPanel(),
        CrisprDesignAnalysisPanel(),
        SangerAlignmentAnalysisPanel(),
        PrimerThermodynamicsAnalysisPanel(),
        PlasmidDatabaseAnalysisPanel(onOpenSequence),
        SiteDomesticationAnalysisPanel(),
        GraphAnalysisPanel(),
    )
    private val tabNames = listOf(
        "Search", "Alignment", "Enzymes", "Assembly", "PCR / Mutagenesis",
        "Translation / Structure", "Virtual Gel", "Calculators", "NCBI / BLAST",
        "Chromatogram", "CRISPR / gRNA", "Sanger Alignment", "Primer Thermo",
        "Plasmid DB", "Site Domestication", "Statistics / Graphs",
    )

    init {
        panels.zip(tabNames).forEach { (panel, name) -> tabs.addTab(name, panel) }
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
        }
        refreshChildren()
    }

    private var docListenerAttached = false

    private fun refreshChildren() {
        docListenerAttached = true
        panels.forEach { it.bindDocument(doc) }
    }

    fun selectTool(name: String) {
        for (i in 0 until tabs.tabCount) if (tabs.getTitleAt(i).equals(name, ignoreCase = true)) tabs.selectedIndex = i
    }

    /** Visible tool names, exposed for headless GUI smoke tests. */
    fun toolNames(): List<String> = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }

    fun selectedTool(): String = tabs.getTitleAt(tabs.selectedIndex)
}

internal abstract class BoundAnalysisPanel : JPanel(BorderLayout()) {
    internal lateinit var doc: SeqDocument
    fun bindDocument(value: SeqDocument) {
        doc = value
        refreshDocument()
    }
    protected open fun refreshDocument() {}
    protected fun row(vararg components: java.awt.Component): JPanel =
        org.instagene.app.gui.row(*components)
    protected fun copyRowToClipboard(model: DefaultTableModel, row: Int?) {
        if (row != null) {
            val sb = StringBuilder()
            for (c in 0 until model.columnCount) { if (c > 0) sb.append("\t"); sb.append(model.getValueAt(row, c)) }
            ContextMenus.copyToClipboard(sb.toString())
        }
    }
    protected fun output(): JTextArea = org.instagene.app.gui.monospacedTextArea()

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
        table.installRowContextMenu { row -> searchPopup(row) }
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

    private fun searchPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item(
            "Reveal Match",
            "Select this search match in the sequence viewer.",
            hasRow,
        ) {
            if (row != null) onReveal((model.getValueAt(row, 0) as Int) - 1, model.getValueAt(row, 1) as Int)
        })
        add(ContextMenus.item(
            "Copy Match",
            "Copy this matched sequence text to the clipboard.",
            hasRow,
        ) {
            if (row != null) ContextMenus.copyToClipboard(model.getValueAt(row, 4).toString())
        })
    }
}

private class AlignmentAnalysisPanel : BoundAnalysisPanel() {
    private val algorithm = JComboBox(MultipleAlignmentAlgorithm.entries.toTypedArray())
    private val queryNames = JTextField(30)
    private val mismatch = JTextField("0.1", 5)
    private val gap = JTextField("1.5", 5)
    private val extension = JTextField("0.5", 5)
    private val text = output()
    private val run = JButton("Align")
    private var queryFiles: List<File> = emptyList()
    private var worker: SwingWorker<MultipleAlignmentResult, Unit>? = null

    init {
        val choose = JButton("Choose query files...")
        choose.addActionListener {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                queryFiles = chooser.selectedFiles.toList()
                queryNames.text = queryFiles.joinToString(", ") { it.name }
            }
        }
        run.toolTipText = "Run the selected aligner; click again to cancel a running alignment."
        run.addActionListener { if (worker == null) execute() else cancel() }
        algorithm.renderer = DefaultListCellRenderer().apply { horizontalAlignment = SwingConstants.LEFT }
        add(row(choose, queryNames, JLabel("Algorithm"), algorithm, JLabel("Mismatch"), mismatch, JLabel("Gap"), gap, JLabel("Extension"), extension, run), BorderLayout.NORTH)
        add(JScrollPane(text), BorderLayout.CENTER)
    }

    private fun execute() {
        if (queryFiles.isEmpty()) return
        run.text = "Cancel alignment"
        text.text = "Aligning…"
        val task = object : SwingWorker<MultipleAlignmentResult, Unit>() {
            override fun doInBackground(): MultipleAlignmentResult {
            val queries = queryFiles.map { SeqIO.read(it) }
            val selected = algorithm.selectedItem as MultipleAlignmentAlgorithm
            return if (selected == MultipleAlignmentAlgorithm.BUILTIN) {
                val result = Alignment.align(doc.seq, queries, AlignmentParameters(
                    mismatchPenalty = -mismatch.text.toDouble(), gapPenalty = -gap.text.toDouble(), gapExtensionPenalty = -extension.text.toDouble(),
                ))
                MultipleAlignmentResult(
                    selected,
                    listOf(doc.seq.copy(bases = result.reference.sequence)) + result.queries.mapIndexed { index, row -> queries[index].copy(bases = row.sequence) },
                )
            } else MultipleAlignment.align(listOf(doc.seq) + queries, selected) { isCancelled }
            }

            override fun done() {
                if (worker !== this) return
                worker = null
                run.text = "Align"
                if (isCancelled) {
                    text.text = "Alignment cancelled."
                    return
                }
                runCatching { get() }.onSuccess { result ->
                    text.text = buildString {
                        append("Algorithm: ${result.algorithm}\n\n")
                        result.sequences.forEach { sequence ->
                            append(">${sequence.name}\n")
                            append(sequence.bases.chunked(80).joinToString("\n")).append("\n\n")
                        }
                    }
                }.onFailure { text.text = it.message ?: "Alignment failed" }
            }
        }
        worker = task
        task.execute()
    }

    private fun cancel() {
        worker?.cancel(true)
        worker = null
        run.text = "Align"
        text.text = "Alignment cancelled."
    }
}

private class EnzymeAnalysisPanel : BoundAnalysisPanel() {
    private val names = JTextField("EcoRI,BamHI", 24)
    private val enzymeSet = JComboBox((listOf("Custom") + EnzymeSetCatalog.PREDEFINED.map { it.name }).toTypedArray())
    private val dam = JCheckBox("Dam methylated")
    private val dcm = JCheckBox("Dcm methylated")
    private val output = output()

    init {
        enzymeSet.addActionListener {
            val selected = enzymeSet.selectedIndex - 1
            if (selected >= 0) names.text = EnzymeSetCatalog.PREDEFINED[selected].enzymeNames.joinToString(",")
        }
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
        val applyState = JButton("Apply methylation state").apply {
            toolTipText = "Persist Dam/Dcm methylation on the current molecule for later restriction checks."
            addActionListener {
                doc.mutate("update methylation state") { seq ->
                    seq.copy(molecule = seq.molecule.copy(damMethylated = dam.isSelected, dcmMethylated = dcm.isSelected))
                }
            }
        }
        add(row(JLabel("Set"), enzymeSet, JLabel("Enzymes"), names, dam, dcm, applyState), BorderLayout.NORTH)
        add(row(report, unique, methylation, diagnostic, silent, recognition), BorderLayout.SOUTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    override fun refreshDocument() {
        dam.isSelected = doc.seq.molecule.damMethylated
        dcm.isSelected = doc.seq.molecule.dcmMethylated
    }

    private fun execute(action: (List<Enzyme>) -> String) {
        runCatching { action(Enzymes.parseList(names.text)) }.onSuccess { output.text = it.ifBlank { "No results." } }.onFailure { output.text = it.message ?: "Analysis failed" }
    }
}

private class AssemblyAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val mode = JComboBox(arrayOf(
        "Restriction cloning", "Gateway cloning", "Gibson assembly", "NEBuilder HiFi", "In-Fusion cloning",
        "TA cloning", "GC cloning", "TA TOPO", "Directional TOPO", "Blunt TOPO", "Golden Gate", "Homology recombination",
    ))
    private val parts = JTextField(36)
    private val enzymes = JTextField("EcoRI", 12)
    private val overhangs = JTextField("A,G,A", 12)
    private val arm = JSpinner(SpinnerNumberModel(15, 1, 1000, 1))
    private val gatewaySites = JTextField("GGGGACAAGTTTGTACAAAAAAGCAGGCT,GGGGACCACTTTGTACAAGAAAGCTGGGT", 28)
    private val productName = JTextField("assembly_product", 18)
    private val circular = JCheckBox("Circular product", true)
    private val output = output()
    private var product: Seq? = null

    init {
        mode.selectedIndex = 2
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
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Enzymes"), enzymes, JLabel("Overhangs"), overhangs, JLabel("Homology arm"), arm))
            add(row(JLabel("Gateway left,right"), gatewaySites, JLabel("Name"), productName, circular, run, open, save))
        }, BorderLayout.SOUTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        val files = parts.text.split(',').map(String::trim).filter(String::isNotEmpty)
        runCatching {
            val loaded = files.map { SeqIO.read(File(it)) }
            val orderedParts = if (doc.seq.bases.isBlank()) loaded else listOf(doc.seq) + loaded
            fun firstInsert(): Seq = loaded.firstOrNull() ?: error("Choose at least one insert sequence")
            when (mode.selectedIndex) {
                0 -> CloningWorkflows.restriction(doc.seq, firstInsert(), Enzymes.parseList(enzymes.text), productName.text)
                1 -> gatewaySites.text.split(',').map(String::trim).let { sites ->
                    require(sites.size == 2) { "Enter left and right Gateway recombination sites" }
                    CloningWorkflows.gateway(doc.seq, firstInsert(), sites[0], sites[1], productName.text)
                }
                2 -> CloningWorkflows.overlapAssembly(CloningMethod.GIBSON, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt())
                3 -> CloningWorkflows.overlapAssembly(CloningMethod.NEBUILDER_HIFI, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt())
                4 -> CloningWorkflows.overlapAssembly(CloningMethod.IN_FUSION, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt())
                5 -> CloningWorkflows.terminalClone(CloningMethod.TA, doc.seq, firstInsert(), productName.text)
                6 -> CloningWorkflows.terminalClone(CloningMethod.GC, doc.seq, firstInsert(), productName.text)
                7 -> CloningWorkflows.terminalClone(CloningMethod.TOPO_TA, doc.seq, firstInsert(), productName.text)
                8 -> CloningWorkflows.terminalClone(CloningMethod.TOPO_DIRECTIONAL, doc.seq, firstInsert(), productName.text)
                9 -> CloningWorkflows.terminalClone(CloningMethod.TOPO_BLUNT, doc.seq, firstInsert(), productName.text)
                10 -> CloningWorkflows.goldenGate(
                    orderedParts,
                    overhangs.text.split(',').map(String::trim),
                    productName.text,
                    circular.isSelected,
                )
                else -> {
                    val donor = firstInsert()
                    val candidates = Recombination.candidates(doc.seq, donor, (arm.value as Number).toInt())
                    require(candidates.isNotEmpty()) { "No matching homology-arm candidate found" }
                    val raw = Recombination.recombine(doc.seq, donor, candidates.first(), productName.text).product
                    MolecularWorkflowResult(
                        CloningMethod.GATEWAY,
                        raw.withProcedure(ProcedureRecord("HOMOLOGY_RECOMBINATION", "Recombined ${donor.name} into ${doc.seq.name}", listOf(doc.seq.name, donor.name), timestamp = System.currentTimeMillis())),
                        listOf(ProtocolStep("Homology recombination", "Used ${(arm.value as Number).toInt()} bp arms")),
                    )
                }
            }
        }.onSuccess { result ->
            product = result.product
            output.text = buildString {
                append("Product: ${result.product.name}\nLength: ${result.product.length}\nTopology: ${result.product.topology}\n")
                result.diagnostics.forEach { append("${it.severity}: ${it.message}\n") }
                append('\n')
                result.steps.forEachIndexed { index, step -> append("${index + 1}. ${step.title}: ${step.detail}\n") }
                append("\n${result.product.bases.chunked(80).joinToString("\n")}")
                if (mode.selectedIndex == 10) {
                    val overhangList = overhangs.text.split(',').map(String::trim).filter(String::isNotEmpty)
                    if (overhangList.isNotEmpty()) {
                        val fidelity = GoldenGateFidelity.score(overhangList)
                        append("\n\n--- Golden Gate Fidelity Report ---\n")
                        append("Set fidelity: ${"%.4f".format(fidelity.setFidelity * 100)}%\n")
                        append("Weakest overhang: ${fidelity.weakestOverhang ?: "none (all >= 99%)"}\n")
                        fidelity.perOverhangFidelity.forEach { (oh, fi) ->
                            append("  $oh: ${"%.4f".format(fi * 100)}%\n")
                        }
                        if (fidelity.warnings.isNotEmpty()) {
                            append("\nWarnings:\n")
                            fidelity.warnings.forEach { append("  ⚠ $it\n") }
                        }
                    }
                }
            }
        }.onFailure { product = null; output.text = it.message ?: "Assembly failed" }
    }
}

private class PcrAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val mode = JComboBox(arrayOf("Standard PCR", "Inverse PCR", "Overlap extension PCR", "Primer-directed mutagenesis", "Anneal oligos"))
    private val forward = JTextField(24)
    private val reverse = JTextField(24)
    private val forwardExtension = JTextField(10)
    private val reverseExtension = JTextField(10)
    private val replacement = JTextField(16)
    private val secondFile = JTextField(28)
    private val productName = JTextField("pcr_product", 16)
    private val output = output()
    private var product: Seq? = null

    init {
        val choose = JButton("Choose second product…").apply {
            toolTipText = "Choose the second amplicon for overlap-extension PCR."
            addActionListener {
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(this@PcrAnalysisPanel) == JFileChooser.APPROVE_OPTION) secondFile.text = chooser.selectedFile.absolutePath
            }
        }
        val run = JButton("Simulate").apply {
            toolTipText = "Simulate the selected PCR, mutagenesis, or oligo-annealing workflow."
            addActionListener { execute() }
        }
        val open = JButton("Open product").apply {
            toolTipText = "Open the simulated product in a new InstaGene sequence tab."
            addActionListener { product?.let(onOpenSequence) }
        }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Workflow"), mode, JLabel("Forward / target"), forward, JLabel("Reverse"), reverse))
            add(row(JLabel("5' extensions"), forwardExtension, reverseExtension, JLabel("Replacement"), replacement))
            add(row(choose, secondFile, JLabel("Product name"), productName, run, open))
        }, BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    override fun refreshDocument() {
        if (doc.seq.kind == SeqKind.PROTEIN || doc.seq.length < 8 || forward.text.isNotBlank() || reverse.text.isNotBlank()) return
        val length = minOf(20, doc.seq.length / 2)
        forward.text = doc.seq.bases.take(length)
        reverse.text = doc.seq.bases.takeLast(length).let { Seq("primer", it).reverseComplement().bases }
        productName.text = "${doc.seq.name}_product"
    }

    private fun execute() {
        runCatching {
            when (mode.selectedIndex) {
                0, 1 -> PcrWorkflows.amplify(
                    doc.seq,
                    PcrPrimer("forward", forward.text, forwardExtension.text),
                    PcrPrimer("reverse", reverse.text, reverseExtension.text),
                    productName.text,
                    inverse = mode.selectedIndex == 1,
                ).product
                2 -> {
                    val second = File(secondFile.text).takeIf(File::isFile)?.let(SeqIO::read)
                        ?: error("Choose the second PCR product")
                    PcrWorkflows.overlapExtension(doc.seq, second, name = productName.text).product
                }
                3 -> PcrWorkflows.mutagenize(doc.seq, forward.text, replacement.text, productName.text).product
                else -> PcrWorkflows.anneal(forward.text, reverse.text, productName.text)
            }
        }.onSuccess {
            product = it
            output.text = buildString {
                append("${it.name}: ${it.length} bp, ${it.topology.name.lowercase()}\n")
                it.provenance.lastOrNull()?.let { record -> append("${record.operation}: ${record.summary}\n") }
                if (it.primers.isNotEmpty()) append("Primers: ${it.primers.joinToString { primer -> primer.name }}\n")
                append("\n${it.bases.chunked(80).joinToString("\n")}")
            }
        }.onFailure {
            product = null
            output.text = it.message ?: "PCR simulation failed"
        }
    }
}

private class TranslationAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val operation = JComboBox(arrayOf("Find ORFs", "Make protein", "Reverse translate", "Optimize codons", "GC profile", "Secondary structure"))
    private val frame = JSpinner(SpinnerNumberModel(1, 1, 3, 1))
    private val profile = JComboBox(CodonDesign.PROFILES.map { it.name }.toTypedArray())
    private val window = JSpinner(SpinnerNumberModel(100, 10, 10_000, 10))
    private val output = output()
    private var product: Seq? = null

    init {
        val run = JButton("Analyze").apply {
            toolTipText = "Run the selected translation, codon, GC, or structure analysis."
            addActionListener { execute() }
        }
        val open = JButton("Open product").apply {
            toolTipText = "Open a translated or codon-designed product as a new sequence."
            addActionListener { product?.let(onOpenSequence) }
        }
        add(row(JLabel("Operation"), operation, JLabel("Frame"), frame, JLabel("Codon profile"), profile, JLabel("Window"), window, run, open), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        product = null
        val selectedProfile = CodonDesign.PROFILES[profile.selectedIndex]
        runCatching {
            when (operation.selectedIndex) {
                0 -> SeqOps.findOrfs(doc.seq).joinToString("\n") {
                    "${it.start + 1}..${it.end}\t${it.strand.symbol}\tframe ${it.frame + 1}\t${it.lengthAa} aa"
                }.ifBlank { "No ORFs found." }
                1 -> CodonDesign.makeProtein(doc.seq, (frame.value as Number).toInt() - 1).also { product = it }
                    .let { "${it.name}: ${it.length} aa\n\n${it.bases.chunked(80).joinToString("\n")}" }
                2 -> CodonDesign.reverseTranslate(doc.seq, selectedProfile).also { product = it }
                    .let { "${it.name}: ${it.length} bp\n\n${it.bases.chunked(80).joinToString("\n")}" }
                3 -> CodonDesign.optimize(doc.seq, selectedProfile, (frame.value as Number).toInt() - 1).also { product = it }
                    .let { "${it.name}: ${it.length} bp\n\n${it.bases.chunked(80).joinToString("\n")}" }
                4 -> SequenceProfiles.gcWindows(doc.seq, (window.value as Number).toInt()).joinToString("\n") {
                    "${it.start + 1}..${it.end}\t${"%.2f".format(it.gcPercent)}% GC"
                }
                else -> SecondaryStructure.predict(doc.seq).let {
                    "${it.algorithm}: ${it.pairedBases} base pair(s), estimated ΔG ${"%.1f".format(it.estimatedDeltaG)} kcal/mol\n\n${it.sequence}\n${it.dotBracket}"
                }
            }
        }.onSuccess { output.text = it }.onFailure { output.text = it.message ?: "Analysis failed" }
    }
}

private class GelAnalysisPanel : BoundAnalysisPanel() {
    private val enzymes = JTextField("EcoRI", 18)
    private val completion = JSpinner(SpinnerNumberModel(100, 0, 100, 5))
    private val ladder = JComboBox(VirtualGel.LADDERS.map { it.name }.toTypedArray())
    private val agarose = JSpinner(SpinnerNumberModel(1.0, 0.3, 5.0, 0.1))
    private val minutes = JSpinner(SpinnerNumberModel(45, 1, 600, 5))
    private val voltage = JSpinner(SpinnerNumberModel(100, 1, 500, 5))
    private val buffer = JComboBox(GelBuffer.entries.toTypedArray())
    private val asPcr = JCheckBox("PCR product lane")
    private val output = output()

    init {
        val run = JButton("Run gel")
        run.addActionListener { execute() }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Enzymes"), enzymes, JLabel("Completion %"), completion, JLabel("Ladder"), ladder, asPcr, run))
            add(row(JLabel("Agarose %"), agarose, JLabel("Minutes"), minutes, JLabel("Voltage"), voltage, JLabel("Buffer"), buffer))
        }, BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        runCatching {
            val standard = VirtualGel.LADDERS[ladder.selectedIndex]
            val sample = if (asPcr.isSelected) {
                GelLane.PcrProduct(doc.seq.name, doc.seq)
            } else {
                GelLane.Dna(doc.seq.name, doc.seq, Enzymes.parseList(enzymes.text), (completion.value as Number).toInt())
            }
            VirtualGel.run(
                listOf(GelLane.SizeStandard(standard.name, standard), sample),
                GelSettings(
                    (agarose.value as Number).toDouble(),
                    (minutes.value as Number).toInt(),
                    (voltage.value as Number).toInt(),
                    buffer.selectedItem as GelBuffer,
                ),
            )
        }.onSuccess { result ->
            output.text = result.lanes.joinToString("\n\n") { lane ->
                "${lane.name}\n" + lane.bands.joinToString("\n") { "  ${it.sizeBp} bp\tintensity=${"%.2f".format(it.relativeIntensity)}\tmigration=${"%.3f".format(result.migration(it.sizeBp))}" }
            }
        }.onFailure { output.text = it.message ?: "Gel simulation failed" }
    }
}

private class CalculatorAnalysisPanel : BoundAnalysisPanel() {
    private val operation = JComboBox(arrayOf("Dilution", "Molecular weight", "nM from mass", "Mass from nM", "Master mix", "Extinction coefficient", "Absorbance at 1%"))
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
                4 -> MolecularCalculators.masterMix(MolecularCalculators.parseRecipe(recipe.text).map { MasterMixComponent(it.first, it.second) }, 1).let { it.components.joinToString("\n") { c -> "${c.name}: ${c.volumeUl} µl" } }
                5 -> {
                    val ec = MolecularCalculators.extinctionCoefficient(doc.seq)
                    val mw = MolecularCalculators.molecularWeight(doc.seq)
                    "Extinction coefficient (ε₂₈₀): ${"%.1f".format(ec)} M⁻¹cm⁻¹\nMolecular weight: ${"%.1f".format(mw)} Da"
                }
                6 -> {
                    val abs = MolecularCalculators.absorbanceAt1Percent(doc.seq)
                    "A(1%, 280nm): ${"%.4f".format(abs)}\nAbsorbance of a 1 mg/mL solution in a 1 cm cuvette at 280 nm."
                }
                else -> "Select an operation."
            }
        }.onSuccess { output.text = it }.onFailure { output.text = it.message ?: "Calculation failed" }
    }
}

private class NcbiAnalysisPanel(
    private val onOpenSequence: (Seq) -> Unit,
    private val client: NcbiClient,
    pollIntervalMillis: Long,
) : BoundAnalysisPanel() {
    private val pollIntervalMillis = pollIntervalMillis.coerceAtLeast(0L)
    private val term = JTextField(24).apply {
        name = "ncbiSharedQuery"
        toolTipText = "Enter an NCBI search term, nucleotide sequence, or GenBank accession."
    }
    private val querySource = JComboBox(arrayOf("Typed term", "Selected bases", "Whole sequence"))
    private val nucleotideModel = tableModel("Accession", "Title", "Organism", "Length", "Molecule type")
    private val nucleotideTable = JTable(nucleotideModel)
    private val blastModel = tableModel(
        "Accession", "% identity", "Alignment length", "Mismatches", "Gaps", "E-value", "Bit score",
    )
    private val blastTable = JTable(blastModel)
    private val resultTabs = JTabbedPane()
    private val output = output()
    private val queryStatus = JLabel().apply { name = "ncbiQueryStatus" }
    private val searchButton = JButton("Search NCBI")
    private val fetchButton = JButton("Fetch GenBank")
    private val blastButton = JButton("Run BLAST")
    private val nucleotidePopup = JPopupMenu()
    private val fetchNucleotideMenuItem = JMenuItem("Fetch GenBank")
    private val copyNucleotideAccessionMenuItem = JMenuItem("Copy accession")
    private val blastPopup = JPopupMenu()
    private val openBlastHitMenuItem = JMenuItem("Open BLAST Hit")
    private val copyBlastAccessionMenuItem = JMenuItem("Copy accession")
    private var hits = emptyList<NcbiHit>()
    private var blastHits = emptyList<BlastHit>()
    private var searchWorker: SwingWorker<*, *>? = null
    private var fetchWorker: SwingWorker<*, *>? = null
    private var blastWorker: SwingWorker<*, *>? = null
    private var searchGeneration = 0
    private var blastGeneration = 0
    private var searchRunning = false
    private var blastRunning = false
    private var lastQueryState: Pair<Int, String?>? = null

    init {
        searchButton.addActionListener { search() }
        fetchButton.addActionListener { fetch() }
        blastButton.addActionListener { if (blastRunning) cancelBlast() else runBlast() }
        fetchNucleotideMenuItem.toolTipText = "Fetch the selected nucleotide record and open it as a sequence tab."
        fetchNucleotideMenuItem.addActionListener { fetch() }
        copyNucleotideAccessionMenuItem.toolTipText = "Copy the selected nucleotide accession to the clipboard."
        copyNucleotideAccessionMenuItem.addActionListener { copySelectedNucleotideAccession() }
        nucleotidePopup.add(fetchNucleotideMenuItem)
        nucleotidePopup.add(copyNucleotideAccessionMenuItem)
        openBlastHitMenuItem.toolTipText = "Fetch the selected BLAST hit accession and open it as a sequence tab."
        openBlastHitMenuItem.addActionListener { openSelectedBlastHit() }
        copyBlastAccessionMenuItem.toolTipText = "Copy the selected BLAST hit accession to the clipboard."
        copyBlastAccessionMenuItem.addActionListener { copySelectedBlastAccession() }
        querySource.addActionListener {
            queryChanged()
        }
        term.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = queryChanged()
            override fun removeUpdate(e: DocumentEvent) = queryChanged()
            override fun changedUpdate(e: DocumentEvent) = queryChanged()
        })
        fetchButton.toolTipText = "Fetch a typed GenBank accession or the selected nucleotide search result."
        openBlastHitMenuItem.isEnabled = false
        copyBlastAccessionMenuItem.isEnabled = false
        blastPopup.add(openBlastHitMenuItem)
        blastPopup.add(copyBlastAccessionMenuItem)
        nucleotideTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        nucleotideTable.selectionModel.addListSelectionListener {
            updateFetchButton()
        }
        nucleotideTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) fetch()
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowNucleotidePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowNucleotidePopup(e)
            }
        })
        blastTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        blastTable.selectionModel.addListSelectionListener {
            updateOpenBlastHitAction()
        }
        blastTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedBlastHit()
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowBlastPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowBlastPopup(e)
            }
        })
        resultTabs.addTab("Nucleotide records", JScrollPane(nucleotideTable))
        resultTabs.addTab("BLAST results", JScrollPane(blastTable))
        add(ncbiControls(), BorderLayout.NORTH)
        add(resultTabs, BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 80) }, BorderLayout.SOUTH)
    }

    private fun ncbiControls(): JPanel =
        row(JLabel("Query source"), querySource, term, queryStatus, searchButton, fetchButton, blastButton)

    override fun refreshDocument() {
        val state = currentQueryState()
        if (lastQueryState != null && state != lastQueryState) clearNucleotideResults()
        lastQueryState = state
        refreshQueryControls()
    }

    private fun refreshQueryControls() {
        updateSearchControls()
        updateFetchButton()
        updateBlastButtons()
    }

    private fun queryChanged() {
        clearNucleotideResults()
        lastQueryState = currentQueryState()
        refreshQueryControls()
    }

    private fun clearNucleotideResults() {
        searchGeneration++
        searchWorker?.cancel(true)
        searchWorker = null
        searchRunning = false
        hits = emptyList()
        nucleotideTable.clearSelection()
        nucleotideModel.rowCount = 0
    }

    private fun currentQueryState(): Pair<Int, String?> = querySource.selectedIndex to nucleotideQuery()

    private fun search() {
        val query = nucleotideQuery() ?: run {
            showMissingQueryGuidance()
            return
        }
        searchGeneration++
        val generation = searchGeneration
        searchWorker?.cancel(true)
        searchRunning = true
        updateSearchControls()
        hits = emptyList()
        nucleotideTable.clearSelection()
        nucleotideModel.rowCount = 0
        updateFetchButton()
        resultTabs.selectedIndex = 0
        output.text = "Searching NCBI..."
        searchWorker = object : SwingWorker<NcbiSearchResult, Unit>() {
            override fun doInBackground() = client.searchNucleotide(query)

            override fun done() {
                if (generation != searchGeneration || isCancelled) return
                runCatching { get() }.onSuccess { result ->
                    hits = result.hits
                    nucleotideModel.rowCount = 0
                    hits.forEach {
                        nucleotideModel.addRow(arrayOf<Any?>(it.accession, it.title, it.organism, it.length, it.moleculeType))
                    }
                    resultTabs.selectedIndex = 0
                    if (hits.isNotEmpty() && nucleotideModel.rowCount > 0) nucleotideTable.setRowSelectionInterval(0, 0)
                    updateFetchButton()
                    output.text = "${hits.size} result(s) shown${if (result.totalCount > hits.size) " of ${result.totalCount}" else ""}."
                }.onFailure { output.text = it.message ?: "NCBI search failed" }
                searchRunning = false
                updateSearchControls()
            }
        }.also { it.execute() }
    }

    private fun fetch() {
        if (fetchWorker != null) return
        val typed = term.text.trim()
        val typedAccession = typed.takeIf { querySource.selectedIndex == 0 && NCBI_ACCESSION_TOKEN.matches(it) }
        val accession = typedAccession ?: selectedNucleotideHit()?.accession
        if (accession == null) {
            output.text = when (querySource.selectedIndex) {
                0 -> if (typed.isBlank()) {
                    "Enter a GenBank accession, or run Search NCBI and select a result."
                } else {
                    "'$typed' is not a GenBank accession. Run Search NCBI and select a result."
                }
                1 -> if (!doc.hasSelection) {
                    "Select bases in the Sequence view, run Search NCBI, then select a result to fetch GenBank."
                } else {
                    "Run Search NCBI with the selected query source, then select a result to fetch GenBank."
                }
                else ->
                    "Run Search NCBI with the selected query source, then select a result to fetch GenBank."
            }
            if (querySource.selectedIndex == 0) term.requestFocusInWindow()
            return
        }
        fetchAccession(accession)
    }

    private fun fetchAccession(accession: String) {
        if (fetchWorker != null) {
            output.text = "A GenBank fetch is already running."
            return
        }
        output.text = "Fetching $accession..."
        val worker = object : SwingWorker<Seq, Unit>() {
            override fun doInBackground() = client.fetchGenBank(accession)

            override fun done() {
                if (fetchWorker !== this) return
                fetchWorker = null
                runCatching { get() }
                    .onSuccess {
                        onOpenSequence(it)
                        output.text = "Opened $accession in a new sequence tab."
                    }
                    .onFailure { output.text = it.message ?: "NCBI fetch failed" }
                updateFetchButton()
                updateOpenBlastHitAction()
            }
        }
        fetchWorker = worker
        updateFetchButton()
        worker.execute()
    }

    private fun runBlast() {
        val input = blastInput() ?: run {
            showMissingQueryGuidance()
            return
        }
        blastGeneration++
        val generation = blastGeneration
        blastWorker?.cancel(true)
        blastRunning = true
        updateBlastButtons()
        resultTabs.selectedIndex = 1
        blastHits = emptyList()
        blastModel.rowCount = 0
        blastTable.clearSelection()
        updateOpenBlastHitAction()
        output.text = "Submitting BLASTN to NCBI..."
        blastWorker = object : SwingWorker<BlastSearchResult, String>() {
            override fun doInBackground(): BlastSearchResult {
                val submission = client.submitBlastN(input.seq, input.selection)
                publish("BLAST request ${submission.rid} submitted; waiting for NCBI...")
                var status = client.blastStatus(submission.rid)
                var delayMillis = pollIntervalMillis.coerceAtMost(MAX_BLAST_POLL_INTERVAL_MILLIS)
                while (status.status == BlastStatus.WAITING) {
                    check(!isCancelled) { "BLAST request cancelled" }
                    publish("BLAST request ${submission.rid} is still running...")
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    delayMillis = (delayMillis * 2).coerceAtMost(MAX_BLAST_POLL_INTERVAL_MILLIS)
                    status = client.blastStatus(submission.rid)
                }
                if (status.status != BlastStatus.READY) error(status.message.ifBlank { "NCBI BLAST failed" })
                return client.fetchBlastResults(submission.rid)
            }

            override fun process(chunks: MutableList<String>) {
                if (chunks.isNotEmpty()) output.text = chunks.last()
            }

            override fun done() {
                if (generation != blastGeneration || isCancelled) return
                runCatching { get() }.onSuccess { result ->
                    blastHits = result.hits
                    blastModel.rowCount = 0
                    blastHits.forEach {
                        val accession = accessionFromBlastSubject(it.subjectId) ?: it.subjectId
                        blastModel.addRow(arrayOf<Any?>(
                            accession, it.percentIdentity, it.alignmentLength, it.mismatches,
                            it.gapOpenings, it.eValue, it.bitScore,
                        ))
                    }
                    if (blastHits.isNotEmpty()) blastTable.setRowSelectionInterval(0, 0)
                    updateOpenBlastHitAction()
                    output.text = if (result.hits.isEmpty()) "BLAST completed with no hits." else "${result.hits.size} BLAST hit(s) shown."
                }.onFailure { output.text = it.message ?: "NCBI BLAST failed" }
                blastRunning = false
                updateBlastButtons()
            }
        }.also { it.execute() }
    }

    private fun cancelBlast() {
        if (!blastRunning) return
        blastGeneration++
        blastWorker?.cancel(true)
        blastRunning = false
        output.text = "BLAST cancelled."
        updateBlastButtons()
    }

    private fun maybeShowNucleotidePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = nucleotideTable.rowAtPoint(e.point)
        if (row >= 0) {
            nucleotideTable.selectionModel.setSelectionInterval(row, row)
        } else {
            nucleotideTable.clearSelection()
        }
        updateFetchButton()
        nucleotideTable.componentPopupMenu = nucleotidePopup
        if (nucleotideTable.isShowing) nucleotidePopup.show(nucleotideTable, e.x, e.y)
    }

    private fun maybeShowBlastPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = blastTable.rowAtPoint(e.point)
        if (row >= 0) {
            blastTable.selectionModel.setSelectionInterval(row, row)
        } else {
            blastTable.clearSelection()
        }
        updateOpenBlastHitAction()
        blastTable.componentPopupMenu = blastPopup
        if (blastTable.isShowing) blastPopup.show(blastTable, e.x, e.y)
    }

    private fun openSelectedBlastHit() {
        val hit = selectedBlastHit() ?: return
        val accession = accessionFromBlastSubject(hit.subjectId)
        if (accession == null) {
            output.text = "Cannot open BLAST hit '${hit.subjectId}': no GenBank accession was found."
            return
        }
        fetchAccession(accession)
    }

    private fun copySelectedNucleotideAccession() {
        val hit = selectedNucleotideHit() ?: return
        ContextMenus.copyToClipboard(hit.accession)
    }

    private fun copySelectedBlastAccession() {
        val hit = selectedBlastHit() ?: return
        val accession = accessionFromBlastSubject(hit.subjectId) ?: hit.subjectId
        ContextMenus.copyToClipboard(accession)
    }

    private fun selectedNucleotideHit(): NcbiHit? {
        if (hits.isEmpty()) return null
        val selected = nucleotideTable.selectedRow
        val row = if (selected >= 0) nucleotideTable.convertRowIndexToModel(selected) else return null
        return hits.getOrNull(row)
    }

    private fun selectedBlastHit(): BlastHit? {
        if (blastHits.isEmpty()) return null
        val selected = blastTable.selectedRow
        val row = if (selected >= 0) blastTable.convertRowIndexToModel(selected) else return null
        return blastHits.getOrNull(row)
    }

    private fun updateFetchButton() {
        val idle = fetchWorker == null
        fetchButton.isEnabled = idle
        val rowEnabled = idle && selectedNucleotideHit() != null
        fetchNucleotideMenuItem.isEnabled = rowEnabled
        copyNucleotideAccessionMenuItem.isEnabled = rowEnabled
    }

    private fun updateOpenBlastHitAction() {
        openBlastHitMenuItem.isEnabled = selectedBlastHit()?.let { accessionFromBlastSubject(it.subjectId) != null } == true
        copyBlastAccessionMenuItem.isEnabled = selectedBlastHit() != null
    }

    private fun updateSearchControls() {
        val typed = querySource.selectedIndex == 0
        term.isEnabled = typed
        term.isVisible = typed
        queryStatus.isVisible = !typed
        queryStatus.text = when (querySource.selectedIndex) {
            1 -> when {
                doc.seq.kind == SeqKind.PROTEIN -> "Nucleotide sequence required"
                doc.hasSelection -> "${doc.selectionEnd - doc.selectionStart} bases selected"
                else -> "No bases selected"
            }
            2 -> when {
                doc.seq.kind == SeqKind.PROTEIN -> "Nucleotide sequence required"
                doc.seq.bases.isBlank() -> "No sequence available"
                else -> "${doc.seq.length} bases in sequence"
            }
            else -> ""
        }
        searchButton.isEnabled = !searchRunning
    }

    private fun updateBlastButtons() {
        blastButton.text = if (blastRunning) "Cancel BLAST" else "Run BLAST"
        blastButton.isEnabled = blastRunning || querySource.selectedIndex == 1 || blastInput() != null
    }

    private fun showMissingQueryGuidance() {
        output.text = when (querySource.selectedIndex) {
            0 -> "Enter an NCBI search term or nucleotide sequence, then try again."
            1 -> if (doc.seq.kind == SeqKind.PROTEIN) {
                "Selected-bases searches require a nucleotide sequence."
            } else {
                "Select bases in the Sequence view, then try again."
            }
            else -> if (doc.seq.kind == SeqKind.PROTEIN) {
                "Whole-sequence searches require a nucleotide sequence."
            } else {
                "The current sequence is empty."
            }
        }
    }

    private fun nucleotideQuery(): String? = when (querySource.selectedIndex) {
        0 -> term.text.trim()
        1 -> if (doc.seq.kind != SeqKind.PROTEIN && doc.hasSelection) doc.selectedBases else ""
        else -> if (doc.seq.kind != SeqKind.PROTEIN) doc.seq.bases else ""
    }.takeIf { it.isNotBlank() }

    private fun blastInput(): BlastInput? {
        return when (querySource.selectedIndex) {
            0 -> typedBlastSequence()?.let { BlastInput(it, null) } ?: documentBlastInput()
            1 -> if (doc.seq.kind != SeqKind.PROTEIN && doc.hasSelection) {
                BlastInput(doc.seq, doc.selectionStart until doc.selectionEnd)
            } else {
                null
            }
            else -> if (doc.seq.kind != SeqKind.PROTEIN && doc.seq.bases.isNotBlank()) BlastInput(doc.seq, null) else null
        }
    }

    private fun documentBlastInput(): BlastInput? {
        if (doc.seq.kind == SeqKind.PROTEIN || doc.seq.bases.isBlank()) return null
        val selection = if (doc.hasSelection) doc.selectionStart until doc.selectionEnd else null
        return BlastInput(doc.seq, selection)
    }

    private fun typedBlastSequence(): Seq? {
        val bases = Alphabet.clean(term.text).uppercase()
        if (bases.isBlank() || Alphabet.invalidCharacters(bases).isNotEmpty()) return null
        return Seq(name = "typed_query", bases = bases, kind = SeqKind.DNA)
    }

    private fun accessionFromBlastSubject(subject: String): String? {
        val trimmed = subject.trim()
        if (NCBI_ACCESSION_TOKEN.matches(trimmed)) return trimmed
        return trimmed.split('|')
            .map { it.trim() }
            .firstOrNull { NCBI_ACCESSION_TOKEN.matches(it) }
    }

    companion object {
        private const val MAX_BLAST_POLL_INTERVAL_MILLIS = 15_000L
        private val NCBI_ACCESSION_TOKEN = Regex("[A-Za-z]{1,4}_[A-Za-z0-9]+(?:\\.\\d+)?|[A-Za-z]+\\d+(?:\\.\\d+)?")
    }

    private data class BlastInput(val seq: Seq, val selection: IntRange?)

    private fun tableModel(vararg columns: String): DefaultTableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
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
            when {
                ChromatogramReader.looksLikeAbi(bytes) -> ChromatogramReader.readAbi(bytes, file.name)
                ChromatogramReader.looksLikeScf(bytes) -> ChromatogramReader.readScf(bytes, file.name)
                else -> error("Unrecognized chromatogram format in '${file.name}'. Expected ABI (.ab1) or SCF (.scf).")
            }
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

private class CrisprDesignAnalysisPanel : BoundAnalysisPanel() {
    private val pamType = JComboBox(arrayOf("NGG (SpCas9)", "NNAGAAW (SaCas9)", "TTTV (CjCas9)"))
    private val minScore = JSpinner(SpinnerNumberModel(0.5, 0.0, 1.0, 0.05))
    private val model = DefaultTableModel(arrayOf("Position", "Strand", "Guide (20bp)", "GC%", "On-target", "Off-target"), 0)
    private val table = JTable(model)
    private val output = output()
    private var lastGuides = emptyList<GuideRNA>()

    init {
        val run = JButton("Find guides")
        run.toolTipText = "Scan the current sequence for CRISPR guide RNA targets."
        run.addActionListener { execute() }
        add(row(JLabel("PAM type"), pamType, JLabel("Min score"), minScore, run), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 60) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> crisprPopup(row) }
    }

    private fun execute() {
        runCatching {
            val result = CrisprDesign.design(doc.seq)
            lastGuides = result.guides.filter { it.onTargetScore >= (minScore.value as Number).toDouble() }
            model.rowCount = 0
            lastGuides.forEach { g ->
                model.addRow(arrayOf<Any?>(
                    "${g.pamPosition - 19}..${g.pamPosition}", "+", g.sequence,
                    "%.1f%%".format(g.gcContent * 100),
                    "%.3f".format(g.onTargetScore), "%.3f".format(g.offTargetScore),
                ))
            }
            output.text = if (lastGuides.isEmpty()) "No guide RNAs found above the minimum score threshold."
            else "${lastGuides.size} guide(s) found. Double-click a row to open the guide sequence."
        }.onFailure { output.text = it.message ?: "CRISPR design failed" }
    }

    private fun crisprPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy guide sequence", "Copy the guide RNA sequence to the clipboard.", hasRow) {
            if (row != null) ContextMenus.copyToClipboard(model.getValueAt(row, 2).toString())
        })
    }
}

private class SangerAlignmentAnalysisPanel : BoundAnalysisPanel() {
    private val queryFiles = JTextField(30)
    private val model = DefaultTableModel(arrayOf("Read name", "Identity", "Mismatches", "Aligned length"), 0)
    private val table = JTable(model)
    private val output = output()
    private var lastResult: SangerAlignmentResult? = null

    init {
        val choose = JButton("Choose trace files...")
        choose.toolTipText = "Select ABI/SCF chromatogram files to align against the current sequence."
        choose.addActionListener {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                queryFiles.text = chooser.selectedFiles.joinToString(", ") { it.name }
                queryFiles.toolTipText = chooser.selectedFiles.joinToString("\n") { it.absolutePath }
            }
        }
        val run = JButton("Align reads")
        run.toolTipText = "Align the selected chromatogram reads to the current reference sequence."
        run.addActionListener { execute() }
        add(row(choose, queryFiles, run), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 60) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> sangerPopup(row) }
    }

    private fun execute() {
        val paths = queryFiles.text.split(',').map(String::trim).filter(String::isNotEmpty)
        if (paths.isEmpty()) { output.text = "Choose one or more chromatogram files."; return }
        runCatching {
            val reads = paths.map { path ->
                val file = File(path)
                val bytes = file.readBytes()
                val record = when {
                    ChromatogramReader.looksLikeAbi(bytes) -> ChromatogramReader.readAbi(bytes, file.name)
                    ChromatogramReader.looksLikeScf(bytes) -> ChromatogramReader.readScf(bytes, file.name)
                    else -> error("Unrecognized format: ${file.name}")
                }
                Seq(record.name, record.bases, SeqKind.DNA)
            }
            val result = SangerAlignment.align(doc.seq, reads)
            lastResult = result
            model.rowCount = 0
            result.reads.forEach { r ->
                model.addRow(arrayOf<Any?>(
                    r.readName, "%.2f%%".format(r.identity * 100), r.mismatches.size, r.alignedLength,
                ))
            }
            output.text = buildString {
                append("Aligned ${result.summary.totalReads} read(s)\n")
                append("Average identity: ${"%.2f".format(result.summary.averageIdentity * 100)}%\n")
                val allMismatches = result.reads.flatMap { it.mismatches }
                if (allMismatches.isNotEmpty()) {
                    append("\nMismatch details:\n")
                    allMismatches.groupBy { it.refPos }.forEach { (pos, mm) ->
                        append("  Position ${pos + 1}: ${mm.first().refBase} -> ${mm.first().readBase} (${mm.size} read(s))\n")
                    }
                }
            }
        }.onFailure { output.text = it.message ?: "Sanger alignment failed" }
    }

    private fun sangerPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy row data", "Copy the alignment result row to the clipboard.", hasRow) {
            copyRowToClipboard(model, row)
        })
    }
}

private class PrimerThermodynamicsAnalysisPanel : BoundAnalysisPanel() {
    private val forward = JTextField(24)
    private val reverse = JTextField(24)
    private val output = output()

    init {
        val run = JButton("Check primers")
        run.toolTipText = "Analyze primer thermodynamics: Tm, ΔG, self-dimers, hairpins."
        run.addActionListener { execute() }
        add(row(JLabel("Forward"), forward, JLabel("Reverse"), reverse, run), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun StringBuilder.appendPrimerThermo(label: String, seq: String) {
        val thermo = PrimerThermodynamics.thermodynamicResult(seq)
        val hairpin = PrimerThermodynamics.assessHairpin(seq)
        val selfDimer = PrimerThermodynamics.assessSelfDimer(seq)
        appendLine("=== $label Primer ===")
        appendLine("Sequence: $seq")
        appendLine("Length: ${seq.length} bp")
        appendLine("ΔG: ${"%.2f".format(thermo.deltaG)} kcal/mol")
        appendLine("Tm: ${"%.1f".format(thermo.tm)} °C")
        appendLine("Hairpin: ${hairpin.assessment} — ${hairpin.details}")
        appendLine("Self-dimer: ${selfDimer.assessment} — ${selfDimer.details}")
        appendLine()
    }

    private fun execute() {
        val fwd = forward.text.trim()
        val rev = reverse.text.trim()
        if (fwd.isBlank() && rev.isBlank()) { output.text = "Enter one or both primer sequences."; return }
        runCatching {
            output.text = buildString {
                if (fwd.isNotBlank()) appendPrimerThermo("Forward", fwd)
                if (rev.isNotBlank()) appendPrimerThermo("Reverse", rev)
                if (fwd.isNotBlank() && rev.isNotBlank()) {
                    val hetero = PrimerThermodynamics.heteroDimer(fwd, rev)
                    val tmFwd = PrimerThermodynamics.thermodynamicResult(fwd).tm
                    val tmRev = PrimerThermodynamics.thermodynamicResult(rev).tm
                    appendLine("=== Hetero-dimer ===")
                    appendLine("ΔG: ${"%.2f".format(hetero.deltaG)} kcal/mol")
                    appendLine("Length: ${hetero.length} bp")
                    appendLine("ΔTm: ${"%.1f".format(kotlin.math.abs(tmFwd - tmRev))} °C")
                }
            }
        }.onFailure { output.text = it.message ?: "Thermodynamic analysis failed" }
    }
}

private class PlasmidDatabaseAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val searchField = JTextField(24)
    private val model = DefaultTableModel(arrayOf("Name", "Size (bp)", "Organism", "Markers", "Description"), 0)
    private val table = JTable(model)
    private val output = output()
    private var results = emptyList<PlasmidRecord>()

    init {
        val search = JButton("Search")
        search.toolTipText = "Search the built-in plasmid database by name, marker, organism, or keyword."
        search.addActionListener { executeSearch() }
        val browseAll = JButton("Browse all")
        browseAll.toolTipText = "Show all plasmids in the built-in database."
        browseAll.addActionListener { browseAll() }
        val open = JButton("Open plasmid")
        open.toolTipText = "Open the selected plasmid as a new sequence tab."
        open.addActionListener { openSelected() }
        add(row(JLabel("Search"), searchField, search, browseAll, open), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 40) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> plasmidDbPopup(row) }
        browseAll()
    }

    private fun executeSearch() {
        val query = searchField.text.trim()
        if (query.isBlank()) { browseAll(); return }
        results = PlasmidDatabase.search(query).results
        refreshTable()
    }

    private fun browseAll() {
        results = PlasmidDatabase.all()
        searchField.text = ""
        refreshTable()
    }

    private fun refreshTable() {
        model.rowCount = 0
        results.forEach { r ->
            model.addRow(arrayOf<Any?>(r.name, r.sizeBp, r.organism, r.markers.joinToString(", "), r.description))
        }
        output.text = "${results.size} plasmid(s) shown."
    }

    private fun openSelected() {
        val row = table.selectedRow
        if (row < 0) { output.text = "Select a plasmid to open."; return }
        val record = results[row]
        val bases = "ATCG".repeat(record.sizeBp / 4 + 1).take(record.sizeBp)
        onOpenSequence(Seq(record.name, bases, SeqKind.DNA))
        output.text = "Opened ${record.name} (${record.sizeBp} bp) as a new sequence tab."
    }

    private fun plasmidDbPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy row", "Copy plasmid info to clipboard.", hasRow) {
            copyRowToClipboard(model, row)
        })
        add(ContextMenus.item("Open as sequence", "Open the selected plasmid as a new tab.", hasRow) {
            if (row != null) { table.setRowSelectionInterval(row, row); openSelected() }
        })
    }
}

private class SiteDomesticationAnalysisPanel : BoundAnalysisPanel() {
    private val enzymeField = JTextField("BsaI,BbsI,BsmBI", 24)
    private val output = output()

    init {
        val findSites = JButton("Find internal sites")
        findSites.toolTipText = "Find internal recognition sites for Golden Gate Type IIS enzymes."
        findSites.addActionListener { findSites() }
        val suggest = JButton("Suggest enzyme")
        suggest.toolTipText = "Suggest which Golden Gate enzyme has the most internal sites to domesticate."
        suggest.addActionListener { suggestEnzyme() }
        val domesticate = JButton("Domesticate")
        domesticate.toolTipText = "Silently mutate all internal recognition sites for the specified enzymes."
        domesticate.addActionListener { domesticate() }
        add(row(JLabel("Enzymes"), enzymeField, findSites, suggest, domesticate), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun parseEnzymes(): List<Enzyme> {
        return enzymeField.text.split(',').map(String::trim).filter(String::isNotEmpty).mapNotNull { name ->
            SiteDomestication.GOLDEN_GATE_ENZYMES.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }
    }

    private fun findSites() {
        val enzymes = parseEnzymes()
        if (enzymes.isEmpty()) { output.text = "Enter valid Golden Gate enzyme names (e.g. BsaI, BbsI, BsmBI)."; return }
        runCatching {
            val sites = SiteDomestication.findInternalSites(doc.seq, enzymes)
            output.text = if (sites.isEmpty()) {
                "No internal ${enzymes.joinToString { it.name }} sites found in ${doc.seq.name}."
            } else {
                "Found ${sites.size} internal site(s) in ${doc.seq.name}:\n\n" +
                    sites.joinToString("\n") { "${it.enzyme.name} at position ${it.position + 1}" }
            }
        }.onFailure { output.text = it.message ?: "Site search failed" }
    }

    private fun suggestEnzyme() {
        runCatching {
            val (enzyme, count) = SiteDomestication.suggestEnzyme(doc.seq)
            output.text = "Suggested enzyme: ${enzyme.name} ($count internal site(s))\n" +
                "Recognition site: ${enzyme.site} (top cut at offset ${enzyme.topCut})"
        }.onFailure { output.text = it.message ?: "Enzyme suggestion failed" }
    }

    private fun domesticate() {
        val enzymes = parseEnzymes()
        if (enzymes.isEmpty()) { output.text = "Enter valid Golden Gate enzyme names."; return }
        runCatching {
            val result = SiteDomestication.domesticate(doc.seq, enzymes)
            output.text = buildString {
                appendLine("Domestication complete for ${doc.seq.name}")
                appendLine("Enzymes: ${enzymes.joinToString { it.name }}")
                appendLine("Mutations applied: ${result.mutationsApplied}")
                appendLine("Domesticated sequence length: ${result.domesticated.length} bp")
                appendLine()
                appendLine("Apply the domesticated sequence? (sequence preview omitted for brevity)")
            }
            doc.mutate("domesticate sites") { result.domesticated }
        }.onFailure { output.text = it.message ?: "Domestication failed" }
    }
}
