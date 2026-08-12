package org.instagene.app.gui

import org.instagene.core.Alphabet
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import org.instagene.app.gui.prefs.SavedItem
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel

/**
 * The Library: reusable primers and fragments saved from the primer designer,
 * the digest panel or the Edit menu. Each entry remembers where it came from,
 * so it can be inserted at the caret, copied, opened as a sequence or traced
 * back to its source region.
 */
class LibraryPanel(
    private val prefs: Prefs,
    initial: SeqDocument,
    private val sequenceView: SequenceView,
    private val onOpenSeq: (Seq) -> Unit,
) : JPanel(BorderLayout(0, 6)) {

    /** The document used as the "source" context; re-pointed when the active tab changes. */
    private var doc = initial

    private val libraryModel = LibraryTableModel()
    val libraryTable = JTable(libraryModel)
    private val summary = JLabel(" ")
    private val insertButton = JButton("Insert at caret")
    private val copyButton = JButton("Copy")
    private val openButton = JButton("Open as sequence")
    private val jumpButton = JButton("Jump to source")
    private val editElementButton = JButton("Edit Element…")
    private val deleteButton = JButton("Delete")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        libraryTable.rowHeight = 20
        libraryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        libraryTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) updateActionState()
        }

        insertButton.addActionListener { insertSelected(libraryTable.selectedRow) }
        copyButton.addActionListener { copySelected(libraryTable.selectedRow) }
        openButton.addActionListener { openSelected(libraryTable.selectedRow) }
        jumpButton.addActionListener { jumpToSource(libraryTable.selectedRow) }
        editElementButton.addActionListener { editSelected(libraryTable.selectedRow) }
        deleteButton.addActionListener { deleteSelected(libraryTable.selectedRow) }

        prefs.addListener {
            libraryModel.fireTableDataChanged()
            updateActionState()
        }

        add(buildHeader(), BorderLayout.NORTH)
        add(JScrollPane(libraryTable), BorderLayout.CENTER)
        add(buildActions(), BorderLayout.SOUTH)
        updateActionState()
    }

    /** Re-points this panel at another document (used when the active tab changes). */
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc === doc) return
        doc = newDoc
    }

    private fun buildHeader(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(JLabel("Saved primers and fragments"))
    }

    private fun buildActions(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(insertButton)
        add(copyButton)
        add(openButton)
        add(jumpButton)
        add(editElementButton)
        add(deleteButton)
    }

    private fun updateActionState() {
        val hasRow = libraryTable.selectedRow in 0 until libraryModel.rowCount
        insertButton.isEnabled = hasRow
        copyButton.isEnabled = hasRow
        openButton.isEnabled = hasRow
        jumpButton.isEnabled = hasRow
        editElementButton.isEnabled = hasRow
        deleteButton.isEnabled = hasRow
        summary.text = if (libraryModel.rowCount == 0) {
            "Save primers or fragments from the Primers and Enzyme tabs, or Edit > Save Selection to Library."
        } else {
            "${libraryModel.rowCount} saved item(s)."
        }
    }

    /** Inserts the saved bases at the caret, replacing any selection. */
    fun insertSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        sequenceView.insertBases(item.bases)
    }

    /** Adds [item] to the library (used by the save entry points and tests). */
    fun addItem(item: SavedItem) {
        prefs.update { it.copy(library = it.library + item) }
    }

    /**
     * Replaces every user-editable field of a library item while retaining its
     * kind and source context. Returns a validation error without saving it.
     */
    fun updateLibraryElement(row: Int, name: String, bases: String, description: String): String? {
        if (prefs.value.library.getOrNull(row) == null) return "Choose a library item to edit."
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Library item name cannot be empty."
        val cleanedBases = Alphabet.clean(bases).uppercase()
        if (cleanedBases.isEmpty()) return "Library sequence cannot be empty."
        val invalid = cleanedBases.filter { !Alphabet.isNucleotide(it) || it == '-' }.toSet()
        if (invalid.isNotEmpty()) {
            return "Library sequence contains invalid nucleotide character(s): ${invalid.sorted().joinToString(" ")}"
        }
        prefs.update { current ->
            current.copy(library = current.library.mapIndexed { index, item ->
                if (index == row) item.copy(name = trimmedName, bases = cleanedBases, description = description) else item
            })
        }
        return null
    }

    private fun copySelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(item.bases), null)
    }

    private fun openSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        onOpenSeq(Seq(item.name, item.bases, SeqKind.DNA, Topology.LINEAR))
    }

    /** Opens the visible editor for the selected saved primer or fragment. */
    private fun editSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        val nameField = JTextField(item.name, 24)
        val basesField = JTextArea(item.bases, 4, 40).apply { lineWrap = true; wrapStyleWord = true }
        val descriptionField = JTextArea(item.description, 6, 40).apply { lineWrap = true; wrapStyleWord = true }
        val result = JOptionPane.showConfirmDialog(
            null,
            JPanel(BorderLayout(0, 8)).apply {
                add(JPanel(BorderLayout(6, 0)).apply {
                    add(JLabel("Name"), BorderLayout.WEST)
                    add(nameField, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JLabel("Sequence"))
                    add(JScrollPane(basesField))
                    add(Box.createVerticalStrut(6))
                    add(JLabel("Description"))
                    add(JScrollPane(descriptionField))
                }, BorderLayout.CENTER)
            },
            "Edit Library Item",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return
        updateLibraryElement(row, nameField.text, basesField.text, descriptionField.text)?.let { error ->
            JOptionPane.showMessageDialog(null, error, "Edit Library Item", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Selects and reveals the source region when the originating sequence is still open. */
    fun jumpToSource(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        val context = item.context
        val source = doc.seq.name
        val inBounds = context.start in 0..doc.seq.length && context.end in context.start..doc.seq.length
        if (context.sourceName.isNotEmpty() && context.sourceName == source && inBounds) {
            sequenceView.revealRange(context.start, context.end)
        } else {
            JOptionPane.showMessageDialog(
                null,
                "Saved from '${context.sourceName}' (${context.start + 1}..${context.end}). " +
                    "Open that sequence and try again.",
                "Jump to Source",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }

    /** Removes the item at [row] from the library. */
    fun deleteSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        prefs.update { it.copy(library = it.library - item) }
    }

    private inner class LibraryTableModel : AbstractTableModel() {
        private val columns = arrayOf("Kind", "Name", "Length", "Source", "Description")

        override fun getRowCount(): Int = prefs.value.library.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = prefs.value.library[rowIndex]
            val context = item.context
            return when (columnIndex) {
                0 -> item.kind.name.lowercase()
                1 -> item.name
                2 -> "${item.length} bp"
                3 -> "${context.sourceName} ${context.start + 1}..${context.end}"
                else -> item.description
            }
        }
    }
}
