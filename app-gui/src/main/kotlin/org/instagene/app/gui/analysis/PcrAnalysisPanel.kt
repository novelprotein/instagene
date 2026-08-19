package org.instagene.app.gui.analysis

import org.instagene.core.PcrPrimer
import org.instagene.core.PcrWorkflows
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.io.File
import javax.swing.*

internal class PcrAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
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
        val choose = JButton("Choose second product\u2026").apply {
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
