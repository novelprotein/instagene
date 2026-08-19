package org.instagene.app.gui.analysis

import org.instagene.core.Enzymes
import org.instagene.core.GelBuffer
import org.instagene.core.GelLane
import org.instagene.core.GelSettings
import org.instagene.core.VirtualGel
import java.awt.BorderLayout
import javax.swing.*

internal class GelAnalysisPanel : BoundAnalysisPanel() {
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
