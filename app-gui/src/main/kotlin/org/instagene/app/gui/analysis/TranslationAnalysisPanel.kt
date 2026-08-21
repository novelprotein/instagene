package org.instagene.app.gui.analysis

import org.instagene.core.*
import java.awt.BorderLayout
import javax.swing.*

internal class TranslationAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val operation = JComboBox(arrayOf("Find ORFs", "Make protein", "Reverse translate", "Optimize codons", "GC profile", "Secondary structure"))
    private val frame = JSpinner(SpinnerNumberModel(1, 1, 3, 1))
    private val profile = JComboBox(CodonDesign.PROFILES.map { it.name }.toTypedArray())
    private val window = JSpinner(SpinnerNumberModel(100, 10, 10_000, 10))
    private val output = output()
    private var product: Seq? = null
    private var worker: SwingWorker<Pair<String, Seq?>, Unit>? = null

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
        val seq = doc.seq
        val opIndex = operation.selectedIndex
        val frameValue = (frame.value as Number).toInt() - 1
        val windowValue = (window.value as Number).toInt()
        output.text = "Analyzing\u2026"
        worker?.cancel(true)
        worker = object : SwingWorker<Pair<String, Seq?>, Unit>() {
            override fun doInBackground(): Pair<String, Seq?> {
                return when (opIndex) {
                    0 -> SeqOps.findOrfs(seq).joinToString("\n") {
                        "${it.start + 1}..${it.end}\t${it.strand.symbol}\tframe ${it.frame + 1}\t${it.lengthAa} aa"
                    }.ifBlank { "No ORFs found." } to null
                    1 -> CodonDesign.makeProtein(seq, frameValue).let {
                        "${it.name}: ${it.length} aa\n\n${it.bases.chunked(80).joinToString("\n")}" to it
                    }
                    2 -> CodonDesign.reverseTranslate(seq, selectedProfile).let {
                        "${it.name}: ${it.length} bp\n\n${it.bases.chunked(80).joinToString("\n")}" to it
                    }
                    3 -> CodonDesign.optimize(seq, selectedProfile, frameValue).let {
                        "${it.name}: ${it.length} bp\n\n${it.bases.chunked(80).joinToString("\n")}" to it
                    }
                    4 -> SequenceProfiles.gcWindows(seq, windowValue).joinToString("\n") {
                        "${it.start + 1}..${it.end}\t${"%.2f".format(it.gcPercent)}% GC"
                    } to null
                    else -> SecondaryStructure.predict(seq).let {
                        ("${it.algorithm}: ${it.pairedBases} base pair(s), estimated \u0394G ${"%.1f".format(it.estimatedDeltaG)} kcal/mol\n\n${it.sequence}\n${it.dotBracket}") to null
                    }
                }
            }

            override fun done() {
                if (worker !== this) return
                worker = null
                runCatching { get() }.onSuccess { (text, seq) ->
                    product = seq
                    output.text = text
                }.onFailure { output.text = it.message ?: "Analysis failed" }
            }
        }.also { it.execute() }
    }
}
