package org.instagene.app.gui.analysis

import org.instagene.core.PrimerThermodynamics
import java.awt.BorderLayout
import javax.swing.*

internal class PrimerThermodynamicsAnalysisPanel : BoundAnalysisPanel() {
    private val forward = JTextField(24)
    private val reverse = JTextField(24)
    private val output = output()

    init {
        val run = JButton("Check primers")
        run.toolTipText = "Analyze primer thermodynamics: Tm, \u0394G, self-dimers, hairpins."
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
        appendLine("\u0394G: ${"%.2f".format(thermo.deltaG)} kcal/mol")
        appendLine("Tm: ${"%.1f".format(thermo.tm)} \u00b0C")
        appendLine("Hairpin: ${hairpin.assessment} \u2014 ${hairpin.details}")
        appendLine("Self-dimer: ${selfDimer.assessment} \u2014 ${selfDimer.details}")
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
                    appendLine("\u0394G: ${"%.2f".format(hetero.deltaG)} kcal/mol")
                    appendLine("Length: ${hetero.length} bp")
                    appendLine("\u0394Tm: ${"%.1f".format(kotlin.math.abs(tmFwd - tmRev))} \u00b0C")
                }
            }
        }.onFailure { output.text = it.message ?: "Thermodynamic analysis failed" }
    }
}
