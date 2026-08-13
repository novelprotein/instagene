package org.instagene.app.gui.tool

import org.instagene.app.gui.TableLabels
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedFeatureMetadata
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.core.Feature
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel

/**
 * Annotated regions on the current sequence: browse, jump to, add from the
 * editor selection, add at explicit coordinates, and delete. Feature edits go
 * through `doc.mutate`, so they are undoable.
 */
class FeaturesPanel(
    initial: SeqDocument,
    private val prefs: Prefs = Prefs(),
    private val onReveal: (Int, Int) -> Unit,
) : JPanel(BorderLayout(0, 6)) {

    /** The displayed document, rebound when the active tab changes. */
    private var doc = initial
    private var docListener: SeqDocument.Listener? = null

    private val featuresModel = FeatureTableModel()
    private val featureTable = JTable(featuresModel)
    private val addButton = JButton("Add Feature from Selection...")
    private val manualAddButton = JButton("Add Feature Manually...")
    private val editElementButton = JButton("Edit Element...")
    private val saveFeatureButton = JButton("Save feature to library")
    private val deleteButton = JButton("Delete")
    private val summary = JLabel(" ")

    private val rowSelectionListener = ListSelectionListener {
        if (!it.valueIsAdjusting) {
            revealSelectedFeature()
            refreshSelectionState()
        }
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
     * Binds this panel to another document and rebuilds the feature table.
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

    private fun buildButtons(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(addButton.apply {
                addActionListener { addFeatureDialog() }
            })
            add(manualAddButton.apply {
                addActionListener { manualAddDialog() }
            })
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(editElementButton.apply {
                addActionListener { editFeatureElement(featureTable.selectedRow) }
            })
            add(saveFeatureButton.apply {
                addActionListener { saveSelectedFeature() }
            })
            add(deleteButton.apply {
                addActionListener { deleteSelectedFeature() }
            })
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
        editElementButton.isEnabled = deleteButton.isEnabled
        saveFeatureButton.isEnabled = savableFeature(featureTable.selectedRow) != null
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
        if (row in doc.seq.features.indices) {
            featureTable.selectionModel.setSelectionInterval(row, row)
        } else {
            featureTable.clearSelection()
        }
        refreshSelectionState()
    }

    /** Exposed for tests: whether the Delete button is currently enabled. */
    fun isDeleteEnabled(): Boolean = deleteButton.isEnabled

    /** Exposed for tests: whether the selected feature can be saved to the Library. */
    fun isSaveFeatureEnabled(): Boolean = saveFeatureButton.isEnabled

    /** Exposed for tests: the current summary or confirmation text. */
    fun summaryText(): String = summary.text

    /** Exposed for tests: the description associated with the feature at [row]. */
    fun featureDescription(row: Int): String = doc.seq.features.getOrNull(row)?.notes.orEmpty()

    /** Updates a feature description as an undoable sequence edit. */
    fun updateFeatureDescription(row: Int, description: String): Boolean {
        val feature = doc.seq.features.getOrNull(row) ?: return false
        return updateFeatureElement(
            row, feature.name, feature.type, feature.start + 1, feature.end, feature.strand, description,
        ) == null
    }

    /**
     * Saves the feature at [row] with its exact source bases and annotation metadata.
     * Invalid, empty, out-of-range, and protein features are left unchanged.
     */
    fun saveFeature(row: Int): Boolean {
        val feature = savableFeature(row) ?: return false
        val bases = doc.seq.sub(feature.start, feature.end)
        if (bases.isEmpty()) return false
        val item = SavedItem(
            kind = SavedKind.FEATURE,
            name = feature.name,
            bases = bases,
            context = SavedContext(
                sourceName = doc.seq.name,
                start = feature.start,
                end = feature.end,
            ),
            description = feature.notes,
            sequenceKind = doc.seq.kind,
            feature = SavedFeatureMetadata(
                type = feature.type,
                strand = feature.strand,
                qualifiers = feature.qualifiers,
            ),
        )
        prefs.update { it.copy(library = it.library + item) }
        summary.text = "Saved ${item.name} to Library."
        return true
    }

    /** Saves the feature currently selected in the table to the Library. */
    fun saveSelectedFeature(): Boolean = saveFeature(featureTable.selectedRow)

    private fun savableFeature(row: Int): Feature? {
        if (doc.seq.kind == SeqKind.PROTEIN) return null
        val feature = doc.seq.features.getOrNull(row) ?: return null
        return feature.takeIf {
            it.start >= 0 && it.end > it.start && it.end <= doc.seq.length
        }
    }

    /**
     * Updates every user-editable feature field. Coordinates are the 1-based,
     * inclusive values displayed by the GUI; returns an error without changing
     * the sequence when the replacement is invalid.
     */
    fun updateFeatureElement(
        row: Int,
        name: String,
        type: String,
        start: Int,
        end: Int,
        strand: Strand,
        description: String,
    ): String? {
        val previous = doc.seq.features.getOrNull(row) ?: return "Choose a feature to edit."
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Feature name cannot be empty."
        if (start !in 1..doc.seq.length || end !in start..doc.seq.length) {
            return "Start must be 1-based and End must be >= Start and <= ${doc.seq.length}."
        }
        val edited = previous.copy(
            name = trimmedName,
            type = type.trim().ifBlank { "misc_feature" },
            start = start - 1,
            end = end,
            strand = strand,
            notes = description,
        )
        doc.mutate("edit feature") { seq ->
            seq.copy(features = seq.features.mapIndexed { index, feature ->
                if (index == row) edited else feature
            }.sortedBy { it.start })
        }
        val editedRow = doc.seq.features.indexOf(edited)
        if (editedRow >= 0) selectFeatureRow(editedRow)
        return null
    }

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
            add(JLabel("Description"))
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
            add(JLabel("Description"))
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

    /** Opens the visible GUI editor for every editable field of the selected feature. */
    private fun editFeatureElement(row: Int) {
        val feature = doc.seq.features.getOrNull(row) ?: return
        val nameField = JTextField(feature.name, 20)
        val typeField = JComboBox(TYPES.toTypedArray()).apply {
            isEditable = true
            selectedItem = feature.type
        }
        val startField = JTextField((feature.start + 1).toString(), 6)
        val endField = JTextField(feature.end.toString(), 6)
        val strandField = JComboBox(arrayOf("+", "-")).apply { selectedItem = feature.strand.symbol }
        val descriptionField = JTextArea(feature.notes, 6, 40).apply { lineWrap = true; wrapStyleWord = true }
        val form = JPanel(GridLayout(5, 2, 6, 6)).apply {
            add(JLabel("Name")); add(nameField)
            add(JLabel("Type")); add(typeField)
            add(JLabel("Start (1-based)")); add(startField)
            add(JLabel("End (1-based)")); add(endField)
            add(JLabel("Strand")); add(strandField)
        }
        val ok = JOptionPane.showConfirmDialog(
            null,
            JPanel(BorderLayout(0, 8)).apply {
                add(form, BorderLayout.NORTH)
                add(JPanel(BorderLayout(0, 4)).apply {
                    add(JLabel("Description"), BorderLayout.NORTH)
                    add(JScrollPane(descriptionField), BorderLayout.CENTER)
                }, BorderLayout.CENTER)
            },
            "Edit Feature",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (ok != JOptionPane.OK_OPTION) return
        val start = startField.text.trim().toIntOrNull()
        val end = endField.text.trim().toIntOrNull()
        val strand = if (strandField.selectedItem == "-") Strand.REVERSE else Strand.FORWARD
        val error = if (start == null || end == null) {
            "Start and End must be whole numbers."
        } else {
            updateFeatureElement(
                row, nameField.text, typeField.selectedItem?.toString().orEmpty(), start, end, strand, descriptionField.text,
            )
        }
        if (error != null) {
            JOptionPane.showMessageDialog(null, error, "Edit Feature", JOptionPane.ERROR_MESSAGE)
        }
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
        private val columns = arrayOf(
            TableLabels.NAME,
            TableLabels.TYPE,
            TableLabels.START,
            TableLabels.END,
            TableLabels.STRAND,
            TableLabels.LENGTH,
            TableLabels.DESCRIPTION,
        )

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
                5 -> TableLabels.length(f.length, doc.seq.kind)
                else -> f.notes
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
