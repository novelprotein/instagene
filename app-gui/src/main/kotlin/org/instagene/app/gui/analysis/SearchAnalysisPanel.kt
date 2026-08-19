package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.AdvancedSearch
import org.instagene.core.SearchMode
import org.instagene.core.SearchRequest
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class SearchAnalysisPanel(private val onReveal: (Int, Int) -> Unit) : BoundAnalysisPanel() {
    private val pattern = JTextField(18)
    private val mode = JComboBox(arrayOf("DNA / degenerate", "Literal", "Amino acid"))
    private val bothStrands = JCheckBox("Both strands", true)
    private val caseSensitive = JCheckBox("Case-sensitive")
    private val mismatches = JSpinner(SpinnerNumberModel(0, 0, 3, 1))
    private val threePrime = JSpinner(SpinnerNumberModel(0, 0, 100, 1))
    private val model = DefaultTableModel(arrayOf("Start", "End", "Strand", "Mismatches", "Match", "Frame"), 0)
    private val table = JTable(model)
    private val status = JLabel("Enter a pattern to search.")

    init {
        val run = JButton("Search")
        run.addActionListener { execute() }
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Pattern"), pattern, JLabel("Mode"), mode, bothStrands, caseSensitive))
            add(row(JLabel("Max mismatches"), mismatches, JLabel("3' exact bases"), threePrime, run))
        }
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.installRowContextMenu { row -> searchPopup(row) }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) revealSelected()
            }
        })
        add(controls, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun execute() {
        if (pattern.text.isBlank()) return
        val searchMode = when (mode.selectedIndex) {
            1 -> SearchMode.LITERAL
            2 -> SearchMode.AMINO_ACID
            else -> SearchMode.DNA_DEGENERATE
        }
        runCatching {
            AdvancedSearch.find(doc.seq, SearchRequest(
                pattern.text.trim(), searchMode, bothStrands.isSelected, caseSensitive.isSelected,
                (mismatches.value as Number).toInt(), (threePrime.value as Number).toInt(),
            ))
        }.onSuccess { hits ->
            model.rowCount = 0
            hits.forEach { model.addRow(arrayOf<Any?>(it.start + 1, it.end, it.strand.symbol, it.mismatches, it.matched, it.frame ?: "")) }
            status.text = "${hits.size} match(es) in ${doc.seq.name}. Double-click a row to reveal it."
        }.onFailure { status.text = it.message ?: "Search failed" }
    }

    private fun revealSelected() {
        val row = table.selectedRow
        if (row >= 0) onReveal((model.getValueAt(row, 0) as Int) - 1, model.getValueAt(row, 1) as Int)
    }

    private fun searchPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item(
            "Reveal Match",
            "Select this search match in the sequence viewer.",
            hasRow,
        ) {
            if (row != null) onReveal((model.getValueAt(row, 0) as Int) - 1, model.getValueAt(row, 1) as Int)
        })
        add(ContextMenus.item(
            "Copy Match",
            "Copy this matched sequence text to the clipboard.",
            hasRow,
        ) {
            if (row != null) ContextMenus.copyToClipboard(model.getValueAt(row, 4).toString())
        })
    }
}
