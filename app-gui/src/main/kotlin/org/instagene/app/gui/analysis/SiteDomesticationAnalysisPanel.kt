package org.instagene.app.gui.analysis

import org.instagene.core.SiteDomestication
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextField

internal class SiteDomesticationAnalysisPanel : BoundAnalysisPanel() {
    private val enzymeField = JTextField("BsaI,BbsI,BsmBI", 24)
    private val output = output()

    init {
        val findSites = JButton("Find internal sites")
        findSites.toolTipText = "Find internal recognition sites for Golden Gate Type IIS enzymes."
        findSites.addActionListener { findSites() }
        val suggest = JButton("Suggest enzyme")
        suggest.toolTipText = "Suggest which Golden Gate enzyme has the most internal sites to domesticate."
        suggest.addActionListener { suggestEnzyme() }
        val domesticate = JButton("Domesticate")
        domesticate.toolTipText = "Silently mutate all internal recognition sites for the specified enzymes."
        domesticate.addActionListener { domesticate() }
        add(row(JLabel("Enzymes"), enzymeField, findSites, suggest, domesticate), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun parseEnzymes(): List<org.instagene.core.Enzyme> {
        return enzymeField.text.split(',').map(String::trim).filter(String::isNotEmpty).mapNotNull { name ->
            SiteDomestication.GOLDEN_GATE_ENZYMES.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }
    }

    private fun findSites() {
        val enzymes = parseEnzymes()
        if (enzymes.isEmpty()) { output.text = "Enter valid Golden Gate enzyme names (e.g. BsaI, BbsI, BsmBI)."; return }
        runCatching {
            val sites = SiteDomestication.findInternalSites(doc.seq, enzymes)
            output.text = if (sites.isEmpty()) {
                "No internal ${enzymes.joinToString { it.name }} sites found in ${doc.seq.name}."
            } else {
                "Found ${sites.size} internal site(s) in ${doc.seq.name}:\n\n" +
                    sites.joinToString("\n") { "${it.enzyme.name} at position ${it.position + 1}" }
            }
        }.onFailure { output.text = it.message ?: "Site search failed" }
    }

    private fun suggestEnzyme() {
        runCatching {
            val (enzyme, count) = SiteDomestication.suggestEnzyme(doc.seq)
            output.text = "Suggested enzyme: ${enzyme.name} ($count internal site(s))\n" +
                "Recognition site: ${enzyme.site} (top cut at offset ${enzyme.topCut})"
        }.onFailure { output.text = it.message ?: "Enzyme suggestion failed" }
    }

    private fun domesticate() {
        val enzymes = parseEnzymes()
        if (enzymes.isEmpty()) { output.text = "Enter valid Golden Gate enzyme names."; return }
        runCatching {
            val result = SiteDomestication.domesticate(doc.seq, enzymes)
            output.text = buildString {
                appendLine("Domestication complete for ${doc.seq.name}")
                appendLine("Enzymes: ${enzymes.joinToString { it.name }}")
                appendLine("Mutations applied: ${result.mutationsApplied}")
                appendLine("Domesticated sequence length: ${result.domesticated.length} bp")
                appendLine()
                appendLine("Apply the domesticated sequence? (sequence preview omitted for brevity)")
            }
            doc.mutate("domesticate sites") { result.domesticated }
        }.onFailure { output.text = it.message ?: "Domestication failed" }
    }
}
