package org.instagene.app.gui

import org.instagene.core.project.EditEntry
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * The Edit History tool tab: a read-only log of every change recorded for the
 * current project, newest first. It is shared across documents like the other
 * tool tabs but bound to the project's [EditRecorder] rather than to the active
 * document, so it always shows the whole project.
 */
class EditHistoryPanel(private val recorder: EditRecorder) : JPanel(BorderLayout()) {

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val CARD_TABLE = "table"
        private const val CARD_HINT = "hint"
    }

    private val model = object : AbstractTableModel() {
        val rows = ArrayList<EditEntry>()
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = arrayOf("Time", "Document", "Change")[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val entry = rows[rowIndex]
            return when (columnIndex) {
                0 -> TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()))
                1 -> entry.doc ?: ""
                2 -> if (entry.detail == null) entry.label else "${entry.label} — ${entry.detail}"
                else -> null
            }
        }
    }

    /** The read-only history table; public so tests can inspect its rows. */
    val table = JTable(model).apply {
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 130
        columnModel.getColumn(1).preferredWidth = 180
        columnModel.getColumn(2).preferredWidth = 360
        tableHeader.reorderingAllowed = false
        rowHeight = rowHeight.coerceAtLeast(20)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }

    private val hint = JLabel("No project edits recorded yet.", JLabel.CENTER).apply {
        font = font.deriveFont(Font.ITALIC)
        foreground = java.awt.Color.GRAY
    }

    private val cards = JPanel(CardLayout()).apply {
        add(JScrollPane(table), CARD_TABLE)
        add(hint, CARD_HINT)
    }

    init {
        add(cards, BorderLayout.CENTER)
        recorder.addListener { refresh() }
        refresh()
    }

    /** Re-reads the recorder's entries into the table, newest first. */
    fun refresh() {
        model.rows.clear()
        model.rows += recorder.entries.asReversed()
        model.fireTableDataChanged()
        (cards.layout as CardLayout).show(cards, if (recorder.entries.isEmpty()) CARD_HINT else CARD_TABLE)
    }
}
