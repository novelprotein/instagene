package org.instagene.app.gui.analysis

import org.instagene.core.*
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.io.AlignmentFormat
import org.instagene.core.io.AlignmentIO
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

internal class AlignmentAnalysisPanel(private val prefs: Prefs) : BoundAnalysisPanel() {
    private val savedSettings = prefs.value.analysisDefaults
    private val algorithm = JComboBox(MultipleAlignmentAlgorithm.entries.toTypedArray()).apply {
        selectedItem = MultipleAlignmentAlgorithm.entries.firstOrNull { it.name == savedSettings.alignmentAlgorithm }
            ?: MultipleAlignmentAlgorithm.BUILTIN
    }
    private val queryNames = JTextField(30)
    private val mismatch = JTextField(savedSettings.alignmentMismatchPenalty.toString(), 5)
    private val gap = JTextField(savedSettings.alignmentGapPenalty.toString(), 5)
    private val extension = JTextField(savedSettings.alignmentGapExtensionPenalty.toString(), 5)
    private val text = output()
    private val run = JButton("Align")
    private val export = JButton("Export alignment…").apply {
        isEnabled = false
        toolTipText = "Export aligned FASTA, Clustal, Stockholm, PHYLIP, SVG, or PNG."
    }
    private var queryFiles: List<File> = emptyList()
    private var worker: SwingWorker<MultipleAlignmentResult, Unit>? = null
    private var displayedResult: MultipleAlignmentResult? = null

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
        export.addActionListener { exportAlignment() }
        algorithm.renderer = DefaultListCellRenderer().apply { horizontalAlignment = SwingConstants.LEFT }
        add(row(choose, queryNames, JLabel("Algorithm"), algorithm, JLabel("Mismatch"), mismatch, JLabel("Gap"), gap, JLabel("Extension"), extension, run, export), BorderLayout.NORTH)
        add(JScrollPane(text), BorderLayout.CENTER)
    }

    private fun execute() {
        if (queryFiles.isEmpty()) return
        val selected = algorithm.selectedItem as MultipleAlignmentAlgorithm
        val parameters = runCatching {
            AlignmentParameters(
                mismatchPenalty = -mismatch.text.toDouble(),
                gapPenalty = -gap.text.toDouble(),
                gapExtensionPenalty = -extension.text.toDouble(),
            )
        }.getOrElse { error ->
            text.text = "Alignment settings are invalid: ${error.message ?: "enter numeric penalties"}"
            return
        }
        prefs.update { current ->
            current.copy(
                analysisDefaults = current.analysisDefaults.copy(
                    alignmentAlgorithm = selected.name,
                    alignmentMismatchPenalty = -parameters.mismatchPenalty,
                    alignmentGapPenalty = -parameters.gapPenalty,
                    alignmentGapExtensionPenalty = -parameters.gapExtensionPenalty,
                ),
            )
        }
        run.text = "Cancel alignment"
        text.text = "Aligning\u2026"
        val task = object : SwingWorker<MultipleAlignmentResult, Unit>() {
            override fun doInBackground(): MultipleAlignmentResult {
            val queries = queryFiles.map { SeqIO.read(it) }
            return if (selected == MultipleAlignmentAlgorithm.BUILTIN) {
                val result = Alignment.align(doc.seq, queries, parameters)
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
                    displayedResult = result
                    export.isEnabled = true
                    text.text = render(result)
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

    private fun exportAlignment() {
        val result = displayedResult ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = "Export alignment"
            addChoosableFileFilter(FileNameExtensionFilter("Alignment interchange (FASTA, Clustal, Stockholm, PHYLIP)", "fasta", "fa", "afa", "msa", "aln", "clustal", "sto", "stockholm", "phy", "phylip", "ph"))
            addChoosableFileFilter(FileNameExtensionFilter("Alignment image (SVG, PNG)", "svg", "png"))
            selectedFile = File("${doc.seq.name}_alignment.aln")
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val selected = chooser.selectedFile
            val target = if (selected.extension.isBlank()) {
                File(selected.parentFile ?: File("."), "${selected.name}.fasta")
            } else {
                selected
            }
            runCatching {
                when (target.extension.lowercase()) {
                    "fa", "fasta", "afa", "msa" -> target.writeText(AlignmentIO.write(result.sequences, AlignmentFormat.FASTA))
                    "aln", "clustal" -> target.writeText(AlignmentIO.write(result.sequences, AlignmentFormat.CLUSTAL))
                    "sto", "stockholm" -> target.writeText(AlignmentIO.write(result.sequences, AlignmentFormat.STOCKHOLM))
                    "phy", "phylip", "ph" -> target.writeText(AlignmentIO.write(result.sequences, AlignmentFormat.PHYLIP))
                    "svg" -> target.writeText(AlignmentImages.svg(result))
                    "png" -> target.writeBytes(AlignmentImages.png(result))
                    else -> error("Choose a FASTA, Clustal (.aln), Stockholm (.sto), PHYLIP (.phy), SVG, or PNG filename")
                }
            }.onSuccess {
                JOptionPane.showMessageDialog(this, "Exported alignment to ${target.name}", "Export alignment", JOptionPane.INFORMATION_MESSAGE)
            }.onFailure {
                JOptionPane.showMessageDialog(this, it.message ?: "Could not export alignment", "Export alignment", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    /** Displays an already aligned multi-record file routed through File → Open. */
    internal fun showImportedAlignment(sequences: List<Seq>, sourceFile: File? = null) {
        require(sequences.size >= 2) { "An alignment needs at least two rows." }
        require(sequences.map { it.bases.length }.distinct().size == 1) {
            "Imported alignment rows must have equal lengths."
        }
        val result = MultipleAlignmentResult(
            algorithm = MultipleAlignmentAlgorithm.BUILTIN,
            sequences = sequences,
            warnings = listOf("Opened aligned rows from ${sourceFile?.name ?: "the bundled example"}; no aligner was run."),
        )
        displayedResult = result
        export.isEnabled = true
        text.text = render(result)
    }

    /** Fixed-width blocks make gaps, consensus calls, and weak columns easy to scan. */
    private fun render(result: MultipleAlignmentResult): String {
        val view = result.view()
        val labelWidth = maxOf(9, result.sequences.maxOf { it.name.length })
        fun row(label: String, bases: String): String = label.padEnd(labelWidth) + "  " + bases
        fun conservationGlyph(value: Double): Char = when {
            value >= 0.999 -> '*'
            value >= 0.80 -> '+'
            value >= 0.60 -> ':'
            value > 0.0 -> '.'
            else -> ' '
        }
        return buildString {
            append("Algorithm: ${result.algorithm}\n")
            if (result.command != null) append("Command: ${result.command}\n")
            result.warnings.forEach { append("Warning: $it\n") }
            append("Consensus uses N/X for ties; conservation: * identical, + >=80%, : >=60%.\n\n")
            for (offset in view.consensus.indices step 80) {
                val until = minOf(offset + 80, view.consensus.length)
                val coordinates = view.referencePositions.subList(offset, until).joinToString("") { position ->
                    when {
                        position == null -> ' '
                        position % 10 == 0 -> '|'
                        else -> ' '
                    }.toString()
                }
                append(row("reference", coordinates)).append('\n')
                append(row("consensus", view.consensus.substring(offset, until))).append('\n')
                append(row("conservation", view.conservation.subList(offset, until).joinToString("") { conservationGlyph(it).toString() })).append('\n')
                result.sequences.forEach { sequence -> append(row(sequence.name, sequence.bases.substring(offset, until))).append('\n') }
                append('\n')
            }
        }
    }
}
