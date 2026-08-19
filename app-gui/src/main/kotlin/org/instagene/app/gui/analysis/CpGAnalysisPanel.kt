package org.instagene.app.gui.analysis

import org.instagene.core.CpGContext
import org.instagene.core.EnzymeAnalysis
import org.instagene.core.SequenceStatistics
import java.awt.BorderLayout
import javax.swing.*

internal class CpGAnalysisPanel : BoundAnalysisPanel() {
    private val output = output()

    init {
        val catalog = JButton("CpG Catalog")
        catalog.toolTipText = "Scan the current sequence for CpG dinucleotides and classify by context."
        catalog.addActionListener { executeCpgCatalog() }
        val comparison = JButton("Methylation Comparison")
        comparison.toolTipText = "Compare methylation-sensitive isoschizomer pairs (HpaII/MspI, SmaI/XmaI, AccII/BstUI)."
        comparison.addActionListener { executeComparison() }
        val islands = JButton("CpG Islands")
        islands.toolTipText = "Detect CpG islands (Gardiner-Garden & Frommer 1987 criteria)."
        islands.addActionListener { executeCpgIslands() }
        add(row(catalog, comparison, islands), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun executeCpgCatalog() {
        runCatching {
            val catalog = EnzymeAnalysis.cpgCatalog(doc.seq)
            if (catalog.isEmpty()) {
                output.text = "No CpG dinucleotides found."
                return@runCatching
            }
            val island = catalog.count { it.context == CpGContext.ISLAND }
            val shore = catalog.count { it.context == CpGContext.SHORE }
            val open = catalog.count { it.context == CpGContext.OPEN_SEA }
            output.text = buildString {
                appendLine("CpG Dinucleotide Catalog (${catalog.size} total)")
                appendLine("=".repeat(44))
                appendLine("Island:    $island")
                appendLine("Shore:     $shore")
                appendLine("Open sea:  $open")
                appendLine()
                for (entry in catalog) {
                    appendLine("pos ${entry.position}\t${entry.context}")
                }
            }
        }.onFailure { output.text = it.message ?: "CpG catalog failed" }
    }

    private fun executeComparison() {
        runCatching {
            val reports = EnzymeAnalysis.methylationSensitiveComparison(doc.seq)
            if (reports.isEmpty()) {
                output.text = "No isoschizomer pairs found."
                return@runCatching
            }
            output.text = buildString {
                appendLine("Methylation-Sensitive Isoschizomer Comparison")
                appendLine("=".repeat(44))
                for (r in reports) {
                    appendLine()
                    appendLine("${r.pairLabel}:")
                    appendLine("  Total sites:      ${r.totalSites}")
                    appendLine("  CpG-overlapping:  ${r.methylBlockedSites}")
                    appendLine("  Methyl-blocked:   ${r.methylBlockedSites} of ${r.totalSites}")
                }
            }
        }.onFailure { output.text = it.message ?: "Comparison failed" }
    }

    private fun executeCpgIslands() {
        runCatching {
            val islands = SequenceStatistics.cpgIslands(doc.seq)
            if (islands.isEmpty()) {
                output.text = "No CpG islands detected (GC\u226550%, OE\u22650.6, length\u2265200bp)."
                return@runCatching
            }
            output.text = buildString {
                appendLine("CpG Islands (${islands.size} found)")
                appendLine("=".repeat(44))
                for ((i, island) in islands.withIndex()) {
                    appendLine()
                    appendLine("Island ${i + 1}: ${island.start}-${island.end} (${island.length} bp)")
                    appendLine("  GC content:  ${"%.1f".format(island.gcContent)}%")
                    appendLine("  Observed CpG: ${island.observedCpG}")
                    appendLine("  Expected CpG: ${"%.1f".format(island.expectedCpG)}")
                    appendLine("  O/E ratio:   ${"%.2f".format(island.oeRatio)}")
                }
            }
        }.onFailure { output.text = it.message ?: "CpG island detection failed" }
    }
}
