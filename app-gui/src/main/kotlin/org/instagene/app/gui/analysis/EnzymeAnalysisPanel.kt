package org.instagene.app.gui.analysis

import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.EnzymeAnalysis
import org.instagene.core.EnzymeSetCatalog
import org.instagene.core.Enzymes
import org.instagene.core.MethylationProfile
import org.instagene.core.MethylationSource
import org.instagene.core.MethylationState
import java.awt.BorderLayout
import javax.swing.*

internal class EnzymeAnalysisPanel : BoundAnalysisPanel() {
    private val names = JTextField("EcoRI,BamHI", 24)
    private val enzymeSet = JComboBox((listOf("Custom") + EnzymeSetCatalog.PREDEFINED.map { it.name }).toTypedArray())
    private val dam = JCheckBox("Dam methylated")
    private val dcm = JCheckBox("Dcm methylated")
    private val scopeLabel = JLabel(" ")
    private val output = output()
    private var observedDoc: SeqDocument? = null
    private var selectionListener: SeqDocument.Listener? = null

    init {
        names.toolTipText = "Enter enzyme names or recognition sites separated by commas, for example EcoRI, GAATTC."
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
                    val cpg = when (seq.molecule.cpgState) {
                        MethylationState.METHYLATED -> true
                        MethylationState.UNMETHYLATED -> false
                        MethylationState.UNKNOWN -> null
                    }
                    seq.copy(
                        molecule = seq.molecule.withMethylation(
                            dam = dam.isSelected,
                            dcm = dcm.isSelected,
                            cpg = cpg,
                            source = MethylationSource.MANUAL,
                        ),
                    )
                }
            }
        }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Enzyme set"), enzymeSet, JLabel("Enzyme names or sites"), names, dam, dcm, applyState))
            add(row(scopeLabel))
        }, BorderLayout.NORTH)
        add(row(report, unique, methylation, diagnostic, silent, recognition), BorderLayout.SOUTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    override fun refreshDocument() {
        dam.isSelected = doc.seq.molecule.damMethylated == true
        dcm.isSelected = doc.seq.molecule.dcmMethylated == true
        updateScopeLabel()
    }

    override fun bindDocument(value: SeqDocument) {
        selectionListener?.let { listener -> observedDoc?.removeListener(listener) }
        super.bindDocument(value)
        val listener = SeqDocument.Listener { _, reason ->
            if (reason == SeqDocument.Reason.SELECTION || reason == SeqDocument.Reason.SEQUENCE) {
                updateScopeLabel()
            }
        }
        observedDoc = value
        selectionListener = listener
        value.addListener(listener)
        updateScopeLabel()
    }

    internal fun scopeTextForTest(): String = scopeLabel.text

    private fun updateScopeLabel() {
        val unit = if (doc.seq.kind == org.instagene.core.SeqKind.PROTEIN) "aa" else "bp"
        scopeLabel.text = buildString {
            append("Analysis target: whole sequence (${doc.seq.length} $unit)")
            if (doc.hasSelection) {
                append("  |  selected range ${doc.selectionStart + 1}–${doc.selectionEnd}")
                append(" (${doc.selectionEnd - doc.selectionStart} $unit; context only)")
            }
        }
    }

    private fun execute(action: (List<org.instagene.core.Enzyme>) -> String) {
        runCatching { action(Enzymes.parseList(names.text)) }.onSuccess {
            output.text = "${scopeLabel.text}\n\n${it.ifBlank { "No results." }}"
        }.onFailure { output.text = it.message ?: "Analysis failed" }
    }
}
