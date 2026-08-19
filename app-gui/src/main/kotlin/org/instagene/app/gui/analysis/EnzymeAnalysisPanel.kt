package org.instagene.app.gui.analysis

import org.instagene.core.EnzymeAnalysis
import org.instagene.core.EnzymeSetCatalog
import org.instagene.core.Enzymes
import org.instagene.core.MethylationProfile
import java.awt.BorderLayout
import javax.swing.*

internal class EnzymeAnalysisPanel : BoundAnalysisPanel() {
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

    private fun execute(action: (List<org.instagene.core.Enzyme>) -> String) {
        runCatching { action(Enzymes.parseList(names.text)) }.onSuccess { output.text = it.ifBlank { "No results." } }.onFailure { output.text = it.message ?: "Analysis failed" }
    }
}
