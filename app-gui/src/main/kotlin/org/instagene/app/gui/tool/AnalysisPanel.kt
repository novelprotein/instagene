package org.instagene.app.gui.tool

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.*
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.FlowLayout
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
    private val search = SearchAnalysisPanel(onReveal)
    private val alignment = AlignmentAnalysisPanel()
    private val enzymes = EnzymeAnalysisPanel()
    private val assembly = AssemblyAnalysisPanel(onOpenSequence)
    private val pcr = PcrAnalysisPanel(onOpenSequence)
    private val translation = TranslationAnalysisPanel(onOpenSequence)
    private val gel = GelAnalysisPanel()
    private val calculators = CalculatorAnalysisPanel()
    private val ncbi = NcbiAnalysisPanel(onOpenSequence, ncbiClient, ncbiPollIntervalMillis)
    private val chromatogram = ChromatogramAnalysisPanel()

    init {
        tabs.addTab("Search", search)
        tabs.addTab("Alignment", alignment)
        tabs.addTab("Enzymes", enzymes)
        tabs.addTab("Assembly", assembly)
        tabs.addTab("PCR / Mutagenesis", pcr)
        tabs.addTab("Translation / Structure", translation)
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
        pcr.bindDocument(doc)
        translation.bindDocument(doc)
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
                else -> MolecularCalculators.masterMix(MolecularCalculators.parseRecipe(recipe.text).map { MasterMixComponent(it.first, it.second) }, 1).let { it.components.joinToString("\n") { c -> "${c.name}: ${c.volumeUl} µl" } }
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
            output.text = if (querySource.selectedIndex == 0 && typed.isBlank()) {
                "Enter a GenBank accession, or run Search NCBI and select a result."
            } else if (querySource.selectedIndex == 0) {
                "'$typed' is not a GenBank accession. Run Search NCBI and select a result."
            } else if (querySource.selectedIndex == 1 && !doc.hasSelection) {
                "Select bases in the Sequence view, run Search NCBI, then select a result to fetch GenBank."
            } else {
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
