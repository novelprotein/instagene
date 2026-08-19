package org.instagene.app.gui.analysis

import org.instagene.core.*
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.io.File
import javax.swing.*

internal class AlignmentAnalysisPanel : BoundAnalysisPanel() {
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
        text.text = "Aligning\u2026"
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
