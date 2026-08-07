package org.instagene.app.gui

import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

/**
 * PCR primer design for the selected amplicon. From/To follow the editor
 * selection and target Tm is adjustable; the engine's [SeqOps.designPrimers]
 * picks the best forward/reverse pair.
 */
class PrimersPanel(private val doc: SeqDocument) : JPanel(BorderLayout(0, 6)) {

    private val fromField = JTextField(8)
    private val toField = JTextField(8)
    private val tmSpinner = JSpinner(SpinnerNumberModel(60.0, 40.0, 75.0, 0.5))
    private val designButton = JButton("Design primers")
    private val copyButton = JButton("Copy as FASTA")
    private val summary = JLabel(" ")
    private val resultsModel = PrimerTableModel()
    private val resultsTable = JTable(resultsModel)

    private var result: Pair<SeqOps.Primer, SeqOps.Primer>? = null

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        resultsTable.rowHeight = 20
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        add(buildControls(), BorderLayout.NORTH)
        add(JScrollPane(resultsTable), BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        designButton.addActionListener { design() }
        copyButton.addActionListener { copyAsFasta() }

        doc.addListener { _, reason ->
            when (reason) {
                SeqDocument.Reason.SEQUENCE -> refresh()
                SeqDocument.Reason.SELECTION -> {
                    fillFromSelection()
                    refresh()
                }
                else -> {}
            }
        }
        fillFromSelection()
        refresh()
    }

    private fun buildControls(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Amplicon From"))
            add(fromField)
            add(JLabel("To"))
            add(toField)
            add(JLabel("Target Tm"))
            add(tmSpinner)
            add(designButton)
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(copyButton)
            add(Box.createHorizontalStrut(4))
        })
    }

    private fun fillFromSelection() {
        if (doc.hasSelection && doc.selectionEnd > doc.selectionStart) {
            fromField.text = (doc.selectionStart + 1).toString()
            toField.text = doc.selectionEnd.toString()
        }
    }

    fun refresh() {
        val nucleotide = doc.seq.kind != SeqKind.PROTEIN
        setInteractive(nucleotide)
        if (!nucleotide) {
            result = null
            resultsModel.fireTableDataChanged()
            summary.text = "Primer design applies to nucleotide sequences."
            return
        }
        val from = fromField.text.toIntOrNull()
        val to = toField.text.toIntOrNull()
        designButton.isEnabled = from != null && to != null && from < to &&
            from >= 1 && to <= doc.seq.length
        if (result != null) {
            val (fwd, rev) = result!!
            val (f0, t0) = toRange()
            summary.text = "Amplicon $f0..$t0 (${t0 - f0} bp): $fwd   $rev"
        } else {
            summary.text = "Set From/To (or select a region) and pick a target Tm, then Design."
        }
    }

    private fun setInteractive(enabled: Boolean) {
        fromField.isEnabled = enabled
        toField.isEnabled = enabled
        tmSpinner.isEnabled = enabled
        designButton.isEnabled = enabled
        copyButton.isEnabled = enabled
        resultsTable.isEnabled = enabled
    }

    /** Exposed for tests: whether primer design is available for the sample type. */
    fun isDesignEnabled(): Boolean = designButton.isEnabled || tmSpinner.isEnabled

    private fun toRange(): Pair<Int, Int> {
        val f0 = (fromField.text.toIntOrNull() ?: 1) - 1
        val t0 = toField.text.toIntOrNull() ?: 0
        return f0 to t0
    }

    fun design() {
        if (doc.seq.kind == SeqKind.PROTEIN) return
        val (from, to) = toRange()
        if (from !in 0..doc.seq.length || to !in from..doc.seq.length || from == to) return
        val tm = (tmSpinner.value as Number).toDouble()
        result = SeqOps.designPrimers(doc.seq, from, to, targetTm = tm)
        resultsModel.fireTableDataChanged()
        refresh()
    }

    /** Programmatic design over `[start, end)` (0-based), used by tests. */
    fun designAmplicon(start: Int, end: Int, tm: Double = 60.0) {
        fromField.text = (start + 1).toString()
        toField.text = end.toString()
        tmSpinner.value = tm
        design()
    }

    private fun copyAsFasta() {
        val pair = result ?: return
        val fasta = listOf(pair.first, pair.second).joinToString("\n") { ">${it.name}\n${it.bases}" }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(fasta), null)
    }

    /** Exposed for tests: the last designed pair, or null. */
    fun lastPrimers(): Pair<SeqOps.Primer, SeqOps.Primer>? = result

    private inner class PrimerTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Sequence", "Length", "Tm", "GC%")

        override fun getRowCount(): Int = if (result == null) 0 else 2
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val primer = if (rowIndex == 0) result!!.first else result!!.second
            return when (columnIndex) {
                0 -> primer.name
                1 -> primer.bases
                2 -> primer.bases.length
                3 -> "%.1f".format(primer.tm)
                else -> "%.1f".format(primer.gc)
            }
        }
    }
}
