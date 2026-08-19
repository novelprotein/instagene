package org.instagene.app.gui.analysis

import org.instagene.core.MasterMixComponent
import org.instagene.core.MolecularCalculators
import java.awt.BorderLayout
import javax.swing.*

internal class CalculatorAnalysisPanel : BoundAnalysisPanel() {
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
                0 -> MolecularCalculators.dilution(a.text.toDouble(), b.text.toDouble(), c.text.toDouble()).let { "Stock: ${it.stockVolumeUl} \u00b5l\nDiluent: ${it.diluentVolumeUl} \u00b5l\nFinal: ${it.finalVolumeUl} \u00b5l" }
                1 -> "Molecular weight: ${MolecularCalculators.molecularWeight(doc.seq)} Da"
                2 -> "Concentration: ${MolecularCalculators.nanomolar(a.text.toDouble(), b.text.toDouble(), c.text.toDouble())} nM"
                3 -> "Mass: ${MolecularCalculators.massNg(a.text.toDouble(), b.text.toDouble(), c.text.toDouble())} ng"
                4 -> MolecularCalculators.masterMix(MolecularCalculators.parseRecipe(recipe.text).map { MasterMixComponent(it.first, it.second) }, 1).let { it.components.joinToString("\n") { c -> "${c.name}: ${c.volumeUl} \u00b5l" } }
                5 -> {
                    val ec = MolecularCalculators.extinctionCoefficient(doc.seq)
                    val mw = MolecularCalculators.molecularWeight(doc.seq)
                    "Extinction coefficient (\u03b5\u2082\u2088\u2080): ${"%.1f".format(ec)} M\u207b\u00b9cm\u207b\u00b9\nMolecular weight: ${"%.1f".format(mw)} Da"
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
