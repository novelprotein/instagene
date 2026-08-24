package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.PlasmidDatabase
import org.instagene.core.PlasmidRecord
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class PlasmidDatabaseAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val searchField = JTextField(24)
    private val model = DefaultTableModel(arrayOf("Name", "Size (bp)", "Organism", "Markers", "Description"), 0)
    private val table = JTable(model)
    private val output = output()
    private var results = emptyList<PlasmidRecord>()

    init {
        val search = JButton("Search")
        search.toolTipText = "Search the built-in plasmid database by name, marker, organism, or keyword."
        search.addActionListener { executeSearch() }
        val browseAll = JButton("Browse all")
        browseAll.toolTipText = "Show all plasmids in the built-in database."
        browseAll.addActionListener { browseAll() }
        val open = JButton("Open plasmid")
        open.toolTipText = "Open the selected plasmid as a new sequence tab."
        open.addActionListener { openSelected() }
        add(row(JLabel("Search"), searchField, search, browseAll, open), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 40) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> plasmidDbPopup(row) }
        browseAll()
    }

    private fun executeSearch() {
        val query = searchField.text.trim()
        if (query.isBlank()) { browseAll(); return }
        results = PlasmidDatabase.search(query).results
        refreshTable()
    }

    private fun browseAll() {
        results = PlasmidDatabase.all()
        searchField.text = ""
        refreshTable()
    }

    private fun refreshTable() {
        model.rowCount = 0
        results.forEach { r ->
            model.addRow(arrayOf<Any?>(r.name, r.sizeBp, r.organism, r.markers.joinToString(", "), r.description))
        }
        output.text = "${results.size} plasmid(s) shown."
    }

    private fun openSelected() {
        val row = table.selectedRow
        if (row < 0) { output.text = "Select a plasmid to open."; return }
        val record = results[row]
        val sequence = PlasmidDatabase.sequenceFor(record) ?: run {
            val bases = "ATCG".repeat(record.sizeBp / 4 + 1).take(record.sizeBp)
            Seq(record.name, bases, SeqKind.DNA)
        }
        onOpenSequence(sequence)
        output.text = "Opened ${record.name} (${record.sizeBp} bp) as a new sequence tab."
    }

    private fun plasmidDbPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy row", "Copy plasmid info to clipboard.", hasRow) {
            copyRowToClipboard(model, row)
        })
        add(ContextMenus.item("Open as sequence", "Open the selected plasmid as a new tab.", hasRow) {
            if (row != null) { table.setRowSelectionInterval(row, row); openSelected() }
        })
    }
}
