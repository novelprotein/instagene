package org.instagene.app.gui.tool

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.Feature
import org.instagene.core.Alphabet
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * PCR primer design for the selected amplicon. From/To follow the editor
 * selection and target Tm is adjustable; the engine's [SeqOps.designPrimers]
 * picks the best forward/reverse pair. The target Tm and a "Save primers"
 * library action are backed by [prefs].
 */
class PrimersPanel(
    initial: SeqDocument,
    private val prefs: Prefs = Prefs(),
) : JPanel(BorderLayout(0, 6)) {

    /** The displayed document, rebound when the active tab changes. */
    private var doc = initial
    private var docListener: SeqDocument.Listener? = null

    private val fromField = JTextField(8)
    private val toField = JTextField(8)
    private val tmSpinner = JSpinner(SpinnerNumberModel(prefs.value.primerDefaultTm.coerceIn(40.0, 75.0), 40.0, 75.0, 0.5))
    private val designButton = JButton("Design primers")
    private val copyButton = JButton("Copy as FASTA")
    private val saveButton = JButton("Save primers to library")
    private val editElementButton = JButton("Edit Element...")
    private val summary = JLabel(" ")
    private val resultsModel = PrimerTableModel()
    private val resultsTable = JTable(resultsModel)

    private var result: Pair<SeqOps.Primer, SeqOps.Primer>? = null
    private var descriptions: List<String> = listOf("", "")

    /** Set after the user edits From or To, preventing selection changes from overwriting the range. */
    private var rangeEdited = false

    companion object {
        /** Amplicons longer than this are not auto-designed; the user picks a region instead. */
        private const val AUTO_DESIGN_MAX_AMPLICON = 20_000
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        resultsTable.rowHeight = 20
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        resultsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) refreshEditElementActionState()
        }

        add(buildControls(), BorderLayout.NORTH)
        add(JScrollPane(resultsTable), BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        designButton.addActionListener {
            if (design()) promptToAddPrimersToFeatures()
        }
        copyButton.addActionListener { copyAsFasta() }
        saveButton.addActionListener { savePrimers() }

        tmSpinner.addChangeListener {
            prefs.update { it.copy(primerDefaultTm = (tmSpinner.value as Number).toDouble()) }
        }

        fromField.document.addDocumentListener(editListener())
        toField.document.addDocumentListener(editListener())

        docListener = SeqDocument.Listener { _, reason -> handleDocChanged(reason) }
        doc.addListener(docListener!!)
        autoPopulateAndDesign()
    }

    /**
     * Binds this panel to another document. The previous amplicon and any
     * manually entered range are reset because they describe the previous sequence.
     */
    fun bindDocument(newDoc: SeqDocument) {
        val switched = newDoc !== doc
        if (switched) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            if (docListener != null) doc.addListener(docListener!!)
        }
        if (docListener == null) {
            docListener = SeqDocument.Listener { _, reason -> handleDocChanged(reason) }
            doc.addListener(docListener!!)
        }
        if (switched) {
            result = null
            descriptions = listOf("", "")
            rangeEdited = false
            suppressEditTracking = true
            try {
                fromField.text = ""
                toField.text = ""
            } finally {
                suppressEditTracking = false
            }
        }
        autoPopulateAndDesign()
    }

    private fun editListener() = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = markEdited()
        override fun removeUpdate(e: DocumentEvent) = markEdited()
        override fun changedUpdate(e: DocumentEvent) = markEdited()
    }

    private fun markEdited() {
        if (suppressEditTracking) return
        rangeEdited = true
    }

    /** Set during programmatic field writes so they are not taken for manual edits. */
    private var suppressEditTracking = false

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
            add(saveButton)
            add(editElementButton.apply {
                addActionListener { editPrimerElement(resultsTable.selectedRow) }
            })
            add(Box.createHorizontalStrut(4))
        })
    }

    private fun fillFromSelection() {
        if (rangeEdited) return
        if (doc.hasSelection && doc.selectionEnd > doc.selectionStart) {
            suppressEditTracking = true
            try {
                fromField.text = (doc.selectionStart + 1).toString()
                toField.text = doc.selectionEnd.toString()
            } finally {
                suppressEditTracking = false
            }
        }
    }

    /**
     * Fills From/To from the current selection, or the whole sequence when there
     * is none, then auto-designs primers so the tab is immediately useful.
     * Manually entered ranges are preserved; amplicons larger
     * than [AUTO_DESIGN_MAX_AMPLICON] are left for the user to scope manually.
     */
    private fun autoPopulateAndDesign() {
        if (rangeEdited) return
        fillFromSelection()
        if (fromField.text.isEmpty() && toField.text.isEmpty() && doc.seq.length > 0) {
            suppressEditTracking = true
            try {
                fromField.text = "1"
                toField.text = doc.seq.length.toString()
            } finally {
                suppressEditTracking = false
            }
        }
        refresh()
        val from = fromField.text.toIntOrNull()
        val to = toField.text.toIntOrNull()
        if (from == null || to == null || from >= to) return
        if (to - from > AUTO_DESIGN_MAX_AMPLICON) {
            summary.text = "Sequence too large for automatic primer design — select a region " +
                    "(max $AUTO_DESIGN_MAX_AMPLICON bp) or enter From/To."
            return
        }
        design()
    }

    private fun handleDocChanged(reason: SeqDocument.Reason) {
        when (reason) {
            SeqDocument.Reason.SEQUENCE -> {
                // The amplicon may have moved or changed; stale primers are misleading.
                result = null
                descriptions = listOf("", "")
                autoPopulateAndDesign()
            }
            SeqDocument.Reason.SELECTION -> autoPopulateAndDesign()
            else -> {}
        }
    }

    /** Keeps the controls in sync with the document and validates the current From/To range again. */
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
        refreshEditElementActionState()
    }

    private fun setInteractive(enabled: Boolean) {
        fromField.isEnabled = enabled
        toField.isEnabled = enabled
        tmSpinner.isEnabled = enabled
        designButton.isEnabled = enabled
        copyButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        resultsTable.isEnabled = enabled
        refreshEditElementActionState()
    }

    /** Exposed for tests: whether primer design is available for the sample type. */
    fun isDesignEnabled(): Boolean = designButton.isEnabled || tmSpinner.isEnabled

    private fun toRange(): Pair<Int, Int> {
        val f0 = (fromField.text.toIntOrNull() ?: 1) - 1
        val t0 = toField.text.toIntOrNull() ?: 0
        return f0 to t0
    }

    /** Designs primers for the displayed range and shows them in the results table. */
    fun design(): Boolean {
        if (doc.seq.kind == SeqKind.PROTEIN) return false
        val (from, to) = toRange()
        if (from !in 0..doc.seq.length || to !in from..doc.seq.length || from == to) return false
        val tm = (tmSpinner.value as Number).toDouble()
        result = SeqOps.designPrimers(doc.seq, from, to, targetTm = tm)
        descriptions = listOf("", "")
        resultsModel.fireTableDataChanged()
        refresh()
        return true
    }

    /** Programmatic design over `[start, end)` (0-based), used by tests. */
    fun designAmplicon(start: Int, end: Int, tm: Double = 60.0) {
        fromField.text = (start + 1).toString()
        toField.text = end.toString()
        tmSpinner.value = tm
        design()
    }

    /**
     * Asks the user whether to annotate the designed primers on the sequence.
     * Shown after a manual "Design primers" click, when a pair was just found;
     * auto-designed pairs (which fire on every selection change) never prompt.
     */
    private fun promptToAddPrimersToFeatures() {
        val pair = result ?: return
        val (from, to) = toRange()
        val message = buildString {
            appendLine("Found primers for amplicon ${from + 1}..$to:")
            appendLine()
            appendLine("${pair.first.name}  ${pair.first.bases}")
            appendLine("${pair.second.name}  ${pair.second.bases}")
            appendLine()
            append("Add them to the sequence's features list?")
        }
        val choice = JOptionPane.showConfirmDialog(
            null,
            message,
            "Add primers to features",
            JOptionPane.YES_NO_OPTION,
        )
        if (choice == JOptionPane.YES_OPTION) addPrimersToFeatures()
    }

    /**
     * Annotates the last designed primer pair on the sequence as `primer_bind`
     * features (the forward primer at the amplicon start, the reverse at its
     * end). The change is undoable; returns false when there is nothing to add.
     */
    fun addPrimersToFeatures(): Boolean {
        val pair = result ?: return false
        if (doc.seq.kind == SeqKind.PROTEIN) return false
        val (from, to) = toRange()
        val fwd = Feature(pair.first.name, "primer_bind", from, from + pair.first.bases.length)
        val rev = Feature(pair.second.name, "primer_bind", to - pair.second.bases.length, to)
        val existing = doc.seq.features.map { it.name.lowercase() }.toSet()
        doc.mutate("add primers to features") {
            var next = it
            if (fwd.name.lowercase() !in existing) next = next.withFeature(fwd)
            if (rev.name.lowercase() !in existing) next = next.withFeature(rev)
            next
        }
        return true
    }

    private fun copyAsFasta() {
        val pair = result ?: return
        val fasta = listOf(pair.first, pair.second).joinToString("\n") { ">${it.name}\n${it.bases}" }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(fasta), null)
    }

    /** Stores the last designed pair in the library, tagged with the amplicon context. */
    fun savePrimers() {
        val pair = result ?: return
        val (from, to) = toRange()
        val tm = (tmSpinner.value as Number).toDouble()
        val context = SavedContext(
            sourceName = doc.seq.name,
            start = from,
            end = to,
            tm = tm,
        )
        val items = listOf(
            SavedItem(SavedKind.PRIMER, pair.first.name, pair.first.bases, context, descriptions[0]),
            SavedItem(SavedKind.PRIMER, pair.second.name, pair.second.bases, context, descriptions[1]),
        )
        prefs.update { it.copy(library = it.library + items) }
    }

    /** Exposed for tests: the last designed pair, or null. */
    fun lastPrimers(): Pair<SeqOps.Primer, SeqOps.Primer>? = result

    /** Exposed for tests: sets From/To as if typed by the user (no primer design). */
    fun typeRangeForTest(from: Int, to: Int) {
        fromField.text = from.toString()
        toField.text = to.toString()
    }

    /** Exposed for tests: the current From/To field text. */
    fun rangeFields(): Pair<String, String> = fromField.text to toField.text

    /** Exposed for tests: the current summary/hint text. */
    fun summaryText(): String = summary.text

    /** Exposed for tests and the GUI: the description for a designed-primer row. */
    fun primerDescription(row: Int): String = descriptions.getOrNull(row).orEmpty()

    /** Updates the in-panel description for a designed primer. It persists when saved to the Library. */
    fun updatePrimerDescription(row: Int, description: String): Boolean {
        val primer = primerAt(row) ?: return false
        return updatePrimerElement(row, primer.name, primer.bases, description) == null
    }

    /**
     * Updates every user-editable field of a designed primer. Length, Tm, and
     * GC% are recalculated from the replacement sequence. Returns an error
     * without changing the current result when the input is invalid.
     */
    fun updatePrimerElement(row: Int, name: String, bases: String, description: String): String? {
        if (primerAt(row) == null) return "Choose a primer to edit."
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Primer name cannot be empty."
        val cleanedBases = Alphabet.clean(bases).uppercase()
        if (cleanedBases.isEmpty()) return "Primer sequence cannot be empty."
        val invalid = cleanedBases.filter { !Alphabet.isNucleotide(it) || it == '-' }.toSet()
        if (invalid.isNotEmpty()) return "Primer sequence contains invalid nucleotide character(s): ${invalid.sorted().joinToString(" ")}"
        val updated = SeqOps.Primer(
            trimmedName,
            cleanedBases,
            SeqOps.meltingTemp(cleanedBases),
            SeqOps.gcContent(cleanedBases),
        )
        result = if (row == 0) result!!.copy(first = updated) else result!!.copy(second = updated)
        descriptions = descriptions.mapIndexed { index, current -> if (index == row) description else current }
        resultsModel.fireTableRowsUpdated(row, row)
        return null
    }

    private fun primerAt(row: Int): SeqOps.Primer? = when (row) {
        0 -> result?.first
        1 -> result?.second
        else -> null
    }

    private fun refreshEditElementActionState() {
        editElementButton.isEnabled = resultsTable.isEnabled && result != null && resultsTable.selectedRow in 0..1
    }

    /** Opens the visible GUI editor for every editable field of the selected primer. */
    private fun editPrimerElement(row: Int) {
        val primer = primerAt(row) ?: return
        val nameField = JTextField(primer.name, 24)
        val basesField = JTextArea(primer.bases, 4, 40).apply { lineWrap = true; wrapStyleWord = true }
        val descriptionField = JTextArea(descriptions[row], 6, 40).apply { lineWrap = true; wrapStyleWord = true }
        val ok = JOptionPane.showConfirmDialog(
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
            "Edit Primer",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (ok != JOptionPane.OK_OPTION) return
        updatePrimerElement(row, nameField.text, basesField.text, descriptionField.text)?.let { error ->
            JOptionPane.showMessageDialog(null, error, "Edit Primer", JOptionPane.ERROR_MESSAGE)
        }
    }

    private inner class PrimerTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Sequence", "Length", "Tm", "GC%", "Description")

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
                4 -> "%.1f".format(primer.gc)
                else -> descriptions[rowIndex]
            }
        }
    }
}
