package org.instagene.app.gui

import org.instagene.core.Digest
import org.instagene.core.Enzyme
import org.instagene.core.Enzymes
import org.instagene.core.Fragment
import org.instagene.core.Seq
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Restriction mapping: tick enzymes to map them onto the sequence, then read
 * off the fragments a digest would produce.
 */
class DigestPanel(
    private val doc: SeqDocument,
    private val onExtractFragment: (Seq) -> Unit,
    private val onReveal: (Int, Int) -> Unit,
) : JPanel(BorderLayout(0, 6)) {

    private val checked = LinkedHashSet<Enzyme>()
    private val enzymeModel = EnzymeTableModel()
    private val fragmentModel = FragmentTableModel()
    private val enzymeTable = JTable(enzymeModel)
    private val fragmentTable = JTable(fragmentModel)
    private val filterField = JTextField()
    private val cuttersOnly = JCheckBox("Only enzymes that cut", true)
    private val uniqueOnly = JCheckBox("Only unique cutters", false)
    private val summary = JLabel(" ")

    private var visibleEnzymes: List<Enzyme> = emptyList()
    private var fragments: List<Fragment> = emptyList()

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        enzymeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        enzymeTable.rowHeight = 20
        enzymeTable.columnModel.getColumn(0).maxWidth = 30
        enzymeTable.columnModel.getColumn(3).maxWidth = 50
        enzymeTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) revealFirstSiteOfSelectedEnzyme()
        }

        fragmentTable.rowHeight = 20
        fragmentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fragmentTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) revealSelectedFragment()
        }

        add(buildTop(), BorderLayout.NORTH)

        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(enzymeTable),
            JPanel(BorderLayout(0, 4)).apply {
                add(JLabel("Fragments").apply { border = BorderFactory.createEmptyBorder(4, 2, 2, 2) }, BorderLayout.NORTH)
                add(JScrollPane(fragmentTable), BorderLayout.CENTER)
                add(buildFragmentButtons(), BorderLayout.SOUTH)
            },
        ).apply {
            resizeWeight = 0.55
            border = null
        }
        add(split, BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        cuttersOnly.addActionListener { refresh() }
        uniqueOnly.addActionListener { refresh() }

        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) refresh()
        }
        refresh()
    }

    private fun buildTop(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(BorderLayout(6, 0)).apply {
            add(JLabel("Filter"), BorderLayout.WEST)
            add(filterField, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(cuttersOnly)
            add(uniqueOnly)
            add(JButton("Clear").apply {
                addActionListener {
                    checked.clear()
                    applySelection()
                }
            })
        })
    }

    private fun buildFragmentButtons(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(JButton("Open fragment as new sequence").apply {
            addActionListener {
                val row = fragmentTable.selectedRow
                if (row in fragments.indices) {
                    val f = fragments[row]
                    onExtractFragment(f.toSeq("${doc.seq.name}_frag${row + 1}"))
                }
            }
        })
        add(Box.createHorizontalStrut(4))
    }

    /** Recomputes cut counts for the current sequence and repopulates the tables. */
    fun refresh() {
        val seq = doc.seq
        val counts = Digest.cutCounts(seq)
        val needle = filterField.text.trim().lowercase()
        visibleEnzymes = Enzymes.ALL.filter { enzyme ->
            val n = counts[enzyme] ?: 0
            (needle.isEmpty() || enzyme.name.lowercase().contains(needle) ||
                enzyme.site.lowercase().contains(needle)) &&
                (!cuttersOnly.isSelected || n > 0) &&
                (!uniqueOnly.isSelected || n == 1)
        }
        enzymeModel.counts = counts
        enzymeModel.fireTableDataChanged()
        applySelection()
    }

    private fun applySelection() {
        val active = checked.toList()
        doc.setMappedEnzymes(active)
        fragments = if (active.isEmpty()) emptyList() else Digest.digest(doc.seq, active)
        fragmentModel.fireTableDataChanged()
        enzymeModel.fireTableDataChanged()
        summary.text = when {
            active.isEmpty() -> "Tick enzymes to map their sites."
            else -> "${active.joinToString(", ") { it.name }}  ->  ${doc.cutSites.size} site(s), " +
                "${fragments.size} fragment(s)"
        }
    }

    private fun revealFirstSiteOfSelectedEnzyme() {
        val row = enzymeTable.selectedRow
        if (row !in visibleEnzymes.indices) return
        val enzyme = visibleEnzymes[row]
        val site = Digest.cutSites(doc.seq, enzyme).firstOrNull() ?: return
        onReveal(site.recognitionStart, site.recognitionStart + enzyme.siteLength)
    }

    private fun revealSelectedFragment() {
        val row = fragmentTable.selectedRow
        if (row !in fragments.indices) return
        val f = fragments[row]
        onReveal(f.start, (f.start + f.length).coerceAtMost(doc.seq.length))
    }

    // ------------------------------------------------------------ table models

    private inner class EnzymeTableModel : AbstractTableModel() {
        var counts: Map<Enzyme, Int> = emptyMap()

        private val columns = arrayOf("", "Enzyme", "Site", "Cuts")

        override fun getRowCount(): Int = visibleEnzymes.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val enzyme = visibleEnzymes[rowIndex]
            return when (columnIndex) {
                0 -> enzyme in checked
                1 -> enzyme.name
                2 -> enzyme.notation()
                else -> (counts[enzyme] ?: 0).toString()
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            val enzyme = visibleEnzymes[rowIndex]
            if (value == true) checked += enzyme else checked -= enzyme
            applySelection()
        }
    }

    private inner class FragmentTableModel : AbstractTableModel() {
        private val columns = arrayOf("#", "Length", "Start", "Left end", "Right end")

        override fun getRowCount(): Int = fragments.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val f = fragments[rowIndex]
            return when (columnIndex) {
                0 -> rowIndex + 1
                1 -> "${f.length} bp"
                2 -> f.start + 1
                3 -> f.leftEnd.toString()
                else -> f.rightEnd.toString()
            }
        }
    }
}
