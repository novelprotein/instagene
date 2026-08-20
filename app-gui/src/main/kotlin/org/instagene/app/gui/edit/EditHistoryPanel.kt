package org.instagene.app.gui.edit

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.project.EditEntry
import org.instagene.core.project.EditKind
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

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

        private val KIND_COLORS = mapOf(
            EditKind.EDIT to Color(0x33, 0x77, 0xCC, 0x18),
            EditKind.UNDO to Color(0xE0, 0x8A, 0x2E, 0x18),
            EditKind.REDO to Color(0x3F, 0xA9, 0x6B, 0x18),
            EditKind.NEW to Color(0x2E, 0x8B, 0x57, 0x18),
            EditKind.OPEN to Color(0x99, 0x9C, 0xA0, 0x18),
            EditKind.CLOSE to Color(0xC0, 0x39, 0x2B, 0x10),
            EditKind.SAVE to Color(0x3F, 0xA9, 0x6B, 0x10),
            EditKind.SAVE_AS to Color(0x3F, 0xA9, 0x6B, 0x18),
            EditKind.PROJECT to Color(0x8A, 0x8F, 0x3C, 0x18),
        )
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

    private val filterField = JTextField().apply {
        toolTipText = "Filter history by text"
        columns = 20
    }

    /** The read-only history table; public so tests can inspect its rows. */
    val table = JTable(model).apply {
        val tableModel = this@EditHistoryPanel.model
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 130
        columnModel.getColumn(1).preferredWidth = 180
        columnModel.getColumn(2).preferredWidth = 360
        tableHeader.reorderingAllowed = false
        rowHeight = rowHeight.coerceAtLeast(20)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        installRowContextMenu { row -> historyPopup(row) }
        setDefaultRenderer(Any::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected) {
                    val modelRow = table.convertRowIndexToModel(row)
                    val kind = tableModel.rows.getOrNull(modelRow)?.kind
                    val bg = KIND_COLORS[kind]
                    c.background = bg ?: table.background
                }
                return c
            }
        })
    }

    private val sorter = TableRowSorter(model)

    private val hint = JLabel("No project edits recorded yet.", JLabel.CENTER).apply {
        font = font.deriveFont(Font.ITALIC)
        foreground = Color.GRAY
    }

    private val cards = JPanel(CardLayout()).apply {
        add(JScrollPane(table), CARD_TABLE)
        add(hint, CARD_HINT)
    }

    init {
        table.rowSorter = sorter
        val topBar = JPanel(BorderLayout(4, 0)).apply {
            add(JLabel("Filter:"), BorderLayout.WEST)
            add(filterField, BorderLayout.CENTER)
            border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
        }
        add(topBar, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        filterField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
        recorder.addListener { refresh() }
        refresh()
    }

    private fun applyFilter() {
        val text = filterField.text.trim()
        if (text.isEmpty()) {
            sorter.rowFilter = null
        } else {
            sorter.rowFilter = RowFilter.regexFilter("(?i)$text")
        }
    }

    /** Refreshes the table from the recorder's entries, newest first. */
    fun refresh() {
        model.rows.clear()
        model.rows += recorder.entries.asReversed()
        model.fireTableDataChanged()
        (cards.layout as CardLayout).show(cards, if (recorder.entries.isEmpty()) CARD_HINT else CARD_TABLE)
        applyFilter()
    }

    private fun historyPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row in model.rows.indices
        add(ContextMenus.item(
            "Copy Row",
            "Copy the selected edit history entry as tab-separated text.",
            hasRow,
        ) {
            if (row != null && row in model.rows.indices) {
                ContextMenus.copyToClipboard((0 until model.columnCount).joinToString("\t") { column ->
                    model.getValueAt(row, column).toString()
                })
            }
        })
    }
}
