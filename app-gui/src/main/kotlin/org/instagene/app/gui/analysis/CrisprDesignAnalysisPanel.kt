package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.CrisprDesign
import org.instagene.core.GuideRNA
import org.instagene.core.ScoringMode
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class CrisprDesignAnalysisPanel : BoundAnalysisPanel() {
    private val pamType = JComboBox(arrayOf("NGG (SpCas9)", "NNAGAAW (SaCas9)", "TTTV (CjCas9)"))
    private val scoringMode = JComboBox(arrayOf("Simplified Ruleset 3", "Full Ruleset 3"))
    private val minScore = JSpinner(SpinnerNumberModel(0.5, 0.0, 1.0, 0.05))
    private val model = DefaultTableModel(arrayOf("Position", "Strand", "Guide (20bp)", "GC%", "On-target", "Off-target"), 0)
    private val table = JTable(model)
    private val output = output()
    private var lastGuides = emptyList<GuideRNA>()

    init {
        val run = JButton("Find guides")
        run.toolTipText = "Scan the current sequence for CRISPR guide RNA targets."
        run.addActionListener { execute() }
        add(row(JLabel("PAM type"), pamType, JLabel("Scoring"), scoringMode, JLabel("Min score"), minScore, run), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 60) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> crisprPopup(row) }
    }

    private fun selectedScoringMode(): ScoringMode =
        if (scoringMode.selectedIndex == 0) ScoringMode.RULESET3_SIMPLE else ScoringMode.RULESET3_FULL

    private fun execute() {
        runCatching {
            val mode = selectedScoringMode()
            val result = CrisprDesign.design(doc.seq, scoringMode = mode)
            lastGuides = result.guides.filter { it.onTargetScore >= (minScore.value as Number).toDouble() }
            model.rowCount = 0
            lastGuides.forEach { g ->
                model.addRow(arrayOf<Any?>(
                    "${g.pamPosition - 19}..${g.pamPosition}", "+", g.sequence,
                    "%.1f%%".format(g.gcContent * 100),
                    "%.3f".format(g.onTargetScore), "%.3f".format(g.offTargetScore),
                ))
            }
            output.text = if (lastGuides.isEmpty()) "No guide RNAs found above the minimum score threshold."
            else "${lastGuides.size} guide(s) found. Double-click a row to open the guide sequence."
        }.onFailure { output.text = it.message ?: "CRISPR design failed" }
    }

    private fun crisprPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy guide sequence", "Copy the guide RNA sequence to the clipboard.", hasRow) {
            if (row != null) ContextMenus.copyToClipboard(model.getValueAt(row, 2).toString())
        })
    }
}
