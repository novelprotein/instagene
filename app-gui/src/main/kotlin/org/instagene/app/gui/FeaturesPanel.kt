package org.instagene.app.gui

import org.instagene.core.Feature
import org.instagene.core.Strand
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Annotated regions on the current sequence: browse, jump to, add from the
 * editor selection, add at explicit coordinates, and delete. Feature edits go
 * through `doc.mutate`, so they are undoable.
 */
class FeaturesPanel(
    initial: SeqDocument,
    private val onReveal: (Int, Int) -> Unit,
) : JPanel(BorderLayout(0, 6)) {

    /** The document currently displayed; re-pointed when the active tab changes. */
    private var doc = initial
    private var docListener: SeqDocument.Listener? = null

    private val featuresModel = FeatureTableModel()
    private val featureTable = JTable(featuresModel)
    private val addButton = JButton("Add Feature from Selection...")
    private val manualAddButton = JButton("Add Feature Manually...")
    private val deleteButton = JButton("Delete")
    private val summary = JLabel(" ")

    private val rowSelectionListener = javax.swing.event.ListSelectionListener {
        if (!it.valueIsAdjusting) revealSelectedFeature()
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        featureTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        featureTable.rowHeight = 20
        featureTable.selectionModel.addListSelectionListener(rowSelectionListener)

        add(buildButtons(), BorderLayout.NORTH)
        add(JScrollPane(featureTable), BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        bindDocument(doc)
        refresh()
    }

    /** The change handler: the feature list only changes when the sequence does. */
    private fun listenerFor() = SeqDocument.Listener { _, reason ->
        when (reason) {
            // The feature list only changes when the sequence does; rebuilding
            // the table on every selection event would drop the user's row
            // selection (the click-driven "reveal" sets a document selection,
            // which otherwise unselects the row just clicked).
            SeqDocument.Reason.SEQUENCE -> refresh()
            SeqDocument.Reason.SELECTION -> refreshSelectionState()
            else -> {}
        }
    }

    /**
     * Re-points this panel at another document (used when the active tab
     * changes) and rebuilds the feature table from it.
     */
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc !== doc) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            if (docListener != null) doc.addListener(docListener!!)
        }
        if (docListener == null) {
            docListener = listenerFor()
            doc.addListener(docListener!!)
        }
        refresh()
    }

    private fun buildButtons(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(addButton.apply {
            addActionListener { addFeatureDialog() }
        })
        add(manualAddButton.apply {
            addActionListener { manualAddDialog() }
        })
        add(deleteButton.apply {
            addActionListener { deleteSelectedFeature() }
        })
    }

    /** Rebuilds the table from the current features, reselecting the row at the
     * same position so a feature being deleted hands selection to the next row. */
    fun refresh() {
        val previousRow = featureTable.selectedRow
        featuresModel.fireTableDataChanged()
        val target = when {
            doc.seq.features.isEmpty() -> -1
            previousRow in doc.seq.features.indices -> previousRow
            else -> minOf(previousRow, doc.seq.features.size - 1).coerceAtLeast(0)
        }
        if (target >= 0) {
            // Swap the listener out so reselecting does not fire another reveal.
            featureTable.selectionModel.removeListSelectionListener(rowSelectionListener)
            featureTable.selectionModel.setSelectionInterval(target, target)
            featureTable.selectionModel.addListSelectionListener(rowSelectionListener)
        }
        refreshSelectionState()
    }

    /** Updates the action buttons and summary from the current state (no table rebuild). */
    private fun refreshSelectionState() {
        addButton.isEnabled = doc.hasSelection && doc.selectionEnd > doc.selectionStart
        manualAddButton.isEnabled = doc.seq.length > 0
        deleteButton.isEnabled = featureTable.selectedRow in doc.seq.features.indices
        val features = doc.seq.features
        summary.text = if (features.isEmpty()) {
            "No features. Select a region and use \"Add Feature from Selection...\", or type coordinates with \"Add Feature Manually...\"."
        } else {
            "${features.size} feature(s), ${features.sumOf { it.length }} bp annotated"
        }
    }

    /** Exposed for tests: whether "Add Feature from Selection..." is currently enabled. */
    fun isAddEnabled(): Boolean = addButton.isEnabled

    /** Exposed for tests: whether "Add Feature Manually..." is currently enabled. */
    fun isManualAddEnabled(): Boolean = manualAddButton.isEnabled

    /** Exposed for tests: the currently selected feature row, -1 when none. */
    fun selectedFeatureRow(): Int = featureTable.selectedRow

    /** Exposed for tests: selects the row at [row] as a mouse click would. */
    fun selectFeatureRow(row: Int) {
        featureTable.selectionModel.setSelectionInterval(row, row)
    }

    /** Exposed for tests: whether the Delete button is currently enabled. */
    fun isDeleteEnabled(): Boolean = deleteButton.isEnabled

    /** Adds a feature over the current editor selection (undoable). */
    fun addFeature(name: String, type: String = "misc_feature", notes: String = "") {
        if (!doc.hasSelection || doc.selectionEnd <= doc.selectionStart) return
        val feature = Feature(
            name = name.ifBlank { "feature" },
            type = type.ifBlank { "misc_feature" },
            start = doc.selectionStart,
            end = doc.selectionEnd,
            notes = notes,
        )
        doc.mutate("add feature") { it.withFeature(feature) }
    }

    /**
     * Adds a feature at explicit coordinates: [start] and [end] are the
     * 1-based inclusive positions biologists type in. No selection is needed.
     * Returns false (and changes nothing) when the coordinates are invalid.
     */
    fun addFeatureManually(
        name: String,
        type: String = "misc_feature",
        start: Int,
        end: Int,
        strand: Strand = Strand.FORWARD,
        notes: String = "",
    ): Boolean {
        val length = doc.seq.length
        if (start !in 1..length || end !in start..length) return false
        val feature = Feature(
            name = name.ifBlank { "feature" },
            type = type.ifBlank { "misc_feature" },
            start = start - 1,
            end = end,
            strand = strand,
            notes = notes,
        )
        doc.mutate("add feature") { it.withFeature(feature) }
        return true
    }

    private fun addFeatureDialog() {
        if (!doc.hasSelection || doc.selectionEnd <= doc.selectionStart) return
        val nameField = JTextField(20)
        val typeField = JComboBox(TYPES.toTypedArray())
        val notesField = JTextField(20)
        val form = JPanel(GridLayout(3, 2, 6, 6)).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Type"))
            add(typeField)
            add(JLabel("Notes"))
            add(notesField)
        }
        val start = doc.selectionStart + 1
        val end = doc.selectionEnd
        val ok = JOptionPane.showConfirmDialog(
            null,
            JPanel(BorderLayout(0, 6)).apply {
                add(JLabel("Feature on ${doc.seq.name}: $start..$end"), BorderLayout.NORTH)
                add(form, BorderLayout.CENTER)
            },
            "Add Feature",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (ok == JOptionPane.OK_OPTION) {
            addFeature(nameField.text, typeField.selectedItem as String, notesField.text)
        }
    }

    private fun manualAddDialog() {
        if (doc.seq.length == 0) return
        val nameField = JTextField(20)
        val typeField = JComboBox(TYPES.toTypedArray())
        val startField = JTextField(6)
        val endField = JTextField(6)
        val strandField = JComboBox(arrayOf("+", "-"))
        val notesField = JTextField(20)
        val form = JPanel(GridLayout(6, 2, 6, 6)).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Type"))
            add(typeField)
            add(JLabel("Start (1-based)"))
            add(startField)
            add(JLabel("End (1-based)"))
            add(endField)
            add(JLabel("Strand"))
            add(strandField)
            add(JLabel("Notes"))
            add(notesField)
        }
        val ok = JOptionPane.showConfirmDialog(
            null,
            JPanel(BorderLayout(0, 6)).apply {
                add(JLabel("Feature on ${doc.seq.name}: 1..${doc.seq.length}"), BorderLayout.NORTH)
                add(form, BorderLayout.CENTER)
            },
            "Add Feature Manually",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (ok != JOptionPane.OK_OPTION) return
        val start = startField.text.trim().toIntOrNull()
        val end = endField.text.trim().toIntOrNull()
        val strand = if (strandField.selectedItem == "-") Strand.REVERSE else Strand.FORWARD
        if (start == null || end == null ||
            !addFeatureManually(nameField.text, typeField.selectedItem as String, start, end, strand, notesField.text)
        ) {
            JOptionPane.showMessageDialog(
                null,
                "Invalid coordinates — Start must be 1-based and End must be >= Start and <= ${doc.seq.length}.",
                "Add Feature Manually",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun deleteSelectedFeature() {
        deleteFeature(featureTable.selectedRow)
    }

    /** Removes the feature at [row] (undoable). */
    fun deleteFeature(row: Int) {
        val feature = doc.seq.features.getOrNull(row) ?: return
        doc.mutate("remove feature") { it.withoutFeature(feature) }
    }

    /** Reveals the feature at [row] in the editor. */
    fun revealFeature(row: Int) {
        val feature = doc.seq.features.getOrNull(row) ?: return
        onReveal(feature.start, feature.end)
    }

    private fun revealSelectedFeature() {
        revealFeature(featureTable.selectedRow)
    }

    private inner class FeatureTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Type", "Start", "End", "Strand", "Length")

        override fun getRowCount(): Int = doc.seq.features.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val f = doc.seq.features[rowIndex]
            return when (columnIndex) {
                0 -> f.name
                1 -> f.type
                2 -> f.start + 1
                3 -> f.end
                4 -> f.strand.symbol
                else -> f.length
            }
        }
    }

    companion object {
        private val TYPES = listOf(
            "misc_feature", "CDS", "gene", "promoter", "terminator", "primer_bind",
            "regulatory", "exon", "intron", "sig_peptide", "polyA_signal",
        )
    }
}
