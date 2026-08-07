package org.instagene.app.gui

import org.instagene.core.Feature
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
 * editor selection, and delete. Feature edits go through `doc.mutate`, so they
 * are undoable.
 */
class FeaturesPanel(
    private val doc: SeqDocument,
    private val onReveal: (Int, Int) -> Unit,
) : JPanel(BorderLayout(0, 6)) {

    private val featuresModel = FeatureTableModel()
    private val featureTable = JTable(featuresModel)
    private val addButton = JButton("Add Feature from Selection...")
    private val deleteButton = JButton("Delete")
    private val summary = JLabel(" ")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        featureTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        featureTable.rowHeight = 20
        featureTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) revealSelectedFeature()
        }

        add(buildButtons(), BorderLayout.NORTH)
        add(JScrollPane(featureTable), BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        doc.addListener { _, reason ->
            when (reason) {
                SeqDocument.Reason.SEQUENCE, SeqDocument.Reason.SELECTION -> refresh()
                else -> {}
            }
        }
        refresh()
    }

    private fun buildButtons(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(addButton.apply {
            addActionListener { addFeatureDialog() }
        })
        add(deleteButton.apply {
            addActionListener { deleteSelectedFeature() }
        })
    }

    fun refresh() {
        featuresModel.fireTableDataChanged()
        addButton.isEnabled = doc.hasSelection && doc.selectionEnd > doc.selectionStart
        deleteButton.isEnabled = featureTable.selectedRow in doc.seq.features.indices
        val features = doc.seq.features
        summary.text = if (features.isEmpty()) {
            "No features. Select a region and use \"Add Feature from Selection...\"."
        } else {
            "${features.size} feature(s), ${features.sumOf { it.length }} bp annotated"
        }
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
