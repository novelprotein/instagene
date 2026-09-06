package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.CrisprDesign
import org.instagene.core.GuideRNA
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class CrisprDesignAnalysisPanel : BoundAnalysisPanel() {
    private val model = DefaultTableModel(arrayOf("Guide coordinates", "Strand", "Guide (20bp)", "PAM", "GC%", "Warnings"), 0)
    private val table = JTable(model)
    private val output = output()
    private var lastGuides = emptyList<GuideRNA>()

    init {
        val run = JButton("Find guides")
        run.toolTipText = "Scan the current sequence for CRISPR guide RNA targets."
        run.addActionListener { execute() }
        add(row(JLabel("PAM"), JLabel("NGG (SpCas9, both strands)"), run), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 60) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> crisprPopup(row) }
    }

    private fun execute() {
        runCatching {
            val result = CrisprDesign.design(doc.seq)
            lastGuides = result.guides
            model.rowCount = 0
            lastGuides.forEach { g ->
                model.addRow(arrayOf<Any?>(
                    "${g.start + 1}..${g.end}", g.strand.symbol, g.sequence, g.pam,
                    "%.1f%%".format(g.gcContent * 100), g.warnings.joinToString("; "),
                ))
            }
            output.text = buildString {
                append(if (lastGuides.isEmpty()) "No concrete NGG guide RNAs found." else "${lastGuides.size} guide(s) found.")
                if (result.warnings.isNotEmpty()) append(" ${result.warnings.joinToString(" ")}")
            }
        }.onFailure { output.text = it.message ?: "CRISPR design failed" }
    }

    private fun crisprPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy guide sequence", "Copy the guide RNA sequence to the clipboard.", hasRow) {
            if (row != null) ContextMenus.copyToClipboard(model.getValueAt(row, 2).toString())
        })
    }
}
