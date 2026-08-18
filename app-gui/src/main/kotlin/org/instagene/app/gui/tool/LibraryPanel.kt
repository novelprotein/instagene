package org.instagene.app.gui.tool

import org.instagene.app.gui.TableLabels
import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedFeatureMetadata
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.Alphabet
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Topology
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPopupMenu
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Reusable primers, restriction fragments, and annotated features. Library
 * entries can be created here or saved from the corresponding analysis panel.
 */
class LibraryPanel(
    private val prefs: Prefs,
    initial: SeqDocument,
    private val sequenceView: SequenceView,
    private val onOpenSeq: (Seq) -> Unit,
) : JPanel(BorderLayout(0, 6)) {

    /** The document used as the insertion and source-jump context. */
    private var doc = initial

    private val libraryModel = LibraryTableModel()
    val libraryTable = JTable(libraryModel)
    private val summary = JLabel(" ")
    private val addItemButton = JButton("Add Item…")
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
        libraryTable.installRowContextMenu { row -> libraryPopup(row) }

        addItemButton.addActionListener { showAddItemDialog() }
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

    /** Binds this panel to another document. */
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc === doc) return
        doc = newDoc
        sequenceView.bindDocument(newDoc)
        updateActionState()
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        add(JLabel("Saved primers, fragments, and features"), BorderLayout.WEST)
        add(addItemButton, BorderLayout.EAST)
    }

    private fun buildActions(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(insertButton)
            add(copyButton)
            add(openButton)
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(jumpButton)
            add(editElementButton)
            add(deleteButton)
        })
        add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
            add(summary, BorderLayout.WEST)
        })
    }

    /** Called after the action-button state changes (row selection or library edits), for menu sync. */
    var onStateChanged: (() -> Unit)? = null

    private fun updateActionState() {
        val item = prefs.value.library.getOrNull(libraryTable.selectedRow)
        val hasRow = item != null
        insertButton.isEnabled = hasRow && doc.seq.kind != SeqKind.PROTEIN
        copyButton.isEnabled = hasRow
        openButton.isEnabled = hasRow
        jumpButton.isEnabled = item?.context?.sourceName?.isNotBlank() == true
        editElementButton.isEnabled = hasRow
        deleteButton.isEnabled = hasRow
        summary.text = if (libraryModel.rowCount == 0) {
            "Add an item here, or save one from the Primers, Enzyme, or Features tab."
        } else {
            "${libraryModel.rowCount} saved item(s)."
        }
        onStateChanged?.invoke()
    }

    /** Exposed for tests and menus: whether "Insert at caret" can act on the selected row. */
    fun isInsertEnabled(): Boolean = insertButton.isEnabled

    /** Exposed for tests and menus: whether "Copy" can act on the selected row. */
    fun isCopyEnabled(): Boolean = copyButton.isEnabled

    /** Exposed for tests and menus: whether "Open as sequence" can act on the selected row. */
    fun isOpenEnabled(): Boolean = openButton.isEnabled

    /** Exposed for tests and menus: whether "Jump to source" can act on the selected row. */
    fun isJumpToSourceEnabled(): Boolean = jumpButton.isEnabled

    /** Exposed for tests and menus: whether "Edit Element..." can act on the selected row. */
    fun isEditElementEnabled(): Boolean = editElementButton.isEnabled

    /** Exposed for tests and menus: whether "Delete" can act on the selected row. */
    fun isDeleteEnabled(): Boolean = deleteButton.isEnabled

    private fun libraryPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val item = row?.let { prefs.value.library.getOrNull(it) }
        val hasRow = item != null
        add(ContextMenus.item(
            "Add Item…",
            "Create a reusable primer, fragment, or feature library item.",
        ) { showAddItemDialog() })
        addSeparator()
        add(ContextMenus.item(
            "Insert at caret",
            "Insert this item's sequence into the active nucleotide document.",
            hasRow && doc.seq.kind != SeqKind.PROTEIN,
        ) { insertSelected(row ?: -1) })
        add(ContextMenus.item(
            "Copy sequence",
            "Copy this item's sequence bases to the clipboard.",
            hasRow,
        ) { copySelected(row ?: -1) })
        add(ContextMenus.item(
            "Open as sequence",
            "Open this saved item as a new sequence tab.",
            hasRow,
        ) { openSelected(row ?: -1) })
        add(ContextMenus.item(
            "Jump to source",
            "Reveal the source region if the originating sequence is active.",
            item?.context?.sourceName?.isNotBlank() == true,
        ) { jumpToSource(row ?: -1) })
        addSeparator()
        add(ContextMenus.item(
            "Edit Element…",
            "Edit this library item's visible fields.",
            hasRow,
        ) { editSelected(row ?: -1) })
        add(ContextMenus.item(
            "Delete",
            "Remove this item from the library.",
            hasRow,
        ) { deleteSelected(row ?: -1) })
    }

    /**
     * Inserts the saved bases at the caret, replacing any selection. Features
     * add their annotation in the same undoable document mutation.
     */
    fun insertSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        if (doc.seq.kind == SeqKind.PROTEIN) return
        val bases = normalizeNucleotides(item.bases, doc.seq.kind)
        if (bases.isEmpty()) return

        val start = doc.selectionStart
        val end = doc.selectionEnd
        if (item.kind != SavedKind.FEATURE) {
            doc.mutate("insert library item") { seq ->
                if (end > start) seq.replaceRange(start, end, bases) else seq.insertAt(start, bases)
            }
            doc.moveCaret(start + bases.length)
            return
        }

        val metadata = item.feature ?: SavedFeatureMetadata()
        val annotation = Feature(
            name = item.name,
            type = metadata.type,
            start = start,
            end = start + bases.length,
            strand = metadata.strand,
            notes = item.description,
            qualifiers = metadata.qualifiers,
        )
        doc.mutate("insert library feature") { seq ->
            val inserted = if (end > start) {
                seq.replaceRange(start, end, bases)
            } else {
                seq.insertAt(start, bases)
            }
            inserted.withFeature(annotation)
        }
        doc.moveCaret(start + bases.length)
    }

    /** Adds a prebuilt item to the library (used by other save entry points and tests). */
    fun addItem(item: SavedItem) {
        prefs.update { it.copy(library = it.library + item) }
    }

    /**
     * Validates and adds a context-free item created in this panel. Whitespace
     * and sequence numbering are ignored, and T/U is normalized to [sequenceKind].
     * Returns a user-facing validation error, or null after saving.
     */
    fun addLibraryItem(
        kind: SavedKind,
        name: String,
        sequenceKind: SeqKind,
        bases: String,
        description: String = "",
        featureType: String = "misc_feature",
        strand: Strand = Strand.FORWARD,
    ): String? {
        if (sequenceKind == SeqKind.PROTEIN) return "Library items must use a DNA or RNA molecule."
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Library item name cannot be empty."
        val normalized = validateAndNormalizeBases(bases, sequenceKind) ?: return sequenceError(bases)
        val feature = if (kind == SavedKind.FEATURE) {
            SavedFeatureMetadata(
                type = featureType.trim().ifBlank { "misc_feature" },
                strand = strand,
                qualifiers = emptyMap(),
            )
        } else {
            null
        }
        val item = SavedItem(
            kind = kind,
            name = trimmedName,
            bases = normalized,
            context = SavedContext(),
            description = description,
            sequenceKind = sequenceKind,
            feature = feature,
        )
        prefs.update { it.copy(library = it.library + item) }
        val newRow = prefs.value.library.lastIndex
        if (newRow >= 0) {
            libraryTable.setRowSelectionInterval(newRow, newRow)
            libraryTable.scrollRectToVisible(libraryTable.getCellRect(newRow, 0, true))
        }
        summary.text = "Added ${item.name} to Library."
        return null
    }

    /**
     * Replaces every user-editable field while retaining the item's kind and
     * source context. Existing feature qualifiers are also retained.
     */
    fun updateLibraryElement(
        row: Int,
        name: String,
        bases: String,
        description: String,
        sequenceKind: SeqKind? = null,
        featureType: String? = null,
        strand: Strand? = null,
    ): String? {
        val previous = prefs.value.library.getOrNull(row) ?: return "Choose a library item to edit."
        val selectedKind = sequenceKind ?: previous.sequenceKind
        if (selectedKind == SeqKind.PROTEIN) return "Library items must use a DNA or RNA molecule."
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Library item name cannot be empty."
        val normalized = validateAndNormalizeBases(bases, selectedKind) ?: return sequenceError(bases)
        val metadata = if (previous.kind == SavedKind.FEATURE) {
            val current = previous.feature ?: SavedFeatureMetadata()
            current.copy(
                type = featureType?.trim()?.ifBlank { "misc_feature" } ?: current.type,
                strand = strand ?: current.strand,
            )
        } else {
            null
        }
        prefs.update { current ->
            current.copy(library = current.library.mapIndexed { index, item ->
                if (index == row) {
                    item.copy(
                        name = trimmedName,
                        bases = normalized,
                        description = description,
                        sequenceKind = selectedKind,
                        feature = metadata,
                    )
                } else {
                    item
                }
            })
        }
        if (row in prefs.value.library.indices) libraryTable.setRowSelectionInterval(row, row)
        summary.text = "Updated $trimmedName."
        return null
    }

    private fun validateAndNormalizeBases(raw: String, kind: SeqKind): String? {
        val cleaned = Alphabet.clean(raw).uppercase()
        if (cleaned.isEmpty()) return null
        if (cleaned.any { !Alphabet.isNucleotide(it) || it == '-' }) return null
        return normalizeNucleotides(cleaned, kind)
    }

    private fun sequenceError(raw: String): String {
        val cleaned = Alphabet.clean(raw).uppercase()
        if (cleaned.isEmpty()) return "Library sequence cannot be empty."
        val invalid = cleaned.filter { !Alphabet.isNucleotide(it) || it == '-' }.toSet()
        return "Library sequence contains invalid nucleotide character(s): ${invalid.sorted().joinToString(" ")}"
    }

    private fun normalizeNucleotides(bases: String, kind: SeqKind): String = when (kind) {
        SeqKind.RNA -> bases.uppercase().replace('T', 'U')
        SeqKind.DNA -> bases.uppercase().replace('U', 'T')
        SeqKind.PROTEIN -> bases.uppercase()
    }

    /** Acts on the currently selected library row; used by the menu bar. */
    fun insertSelectedRow() = insertSelected(libraryTable.selectedRow)

    /** Acts on the currently selected library row; used by the menu bar. */
    fun copySelectedRow() = copySelected(libraryTable.selectedRow)

    /** Acts on the currently selected library row; used by the menu bar. */
    fun openSelectedRow() = openSelected(libraryTable.selectedRow)

    /** Acts on the currently selected library row; used by the menu bar. */
    fun jumpToSourceRow() = jumpToSource(libraryTable.selectedRow)

    /** Acts on the currently selected library row; used by the menu bar. */
    fun editSelectedRow() = editSelected(libraryTable.selectedRow)

    /** Acts on the currently selected library row; used by the menu bar. */
    fun deleteSelectedRow() = deleteSelected(libraryTable.selectedRow)

    fun copySelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        ContextMenus.copyToClipboard(item.bases)
    }

    private fun openSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        val metadata = item.feature
        val features = if (item.kind == SavedKind.FEATURE) {
            listOf(
                Feature(
                    name = item.name,
                    type = metadata?.type ?: "misc_feature",
                    start = 0,
                    end = item.bases.length,
                    strand = metadata?.strand ?: Strand.FORWARD,
                    notes = item.description,
                    qualifiers = metadata?.qualifiers ?: emptyMap(),
                )
            )
        } else {
            emptyList()
        }
        onOpenSeq(
            Seq(
                name = item.name,
                bases = normalizeNucleotides(item.bases, item.sequenceKind),
                kind = item.sequenceKind,
                topology = Topology.LINEAR,
                features = features,
                description = item.description,
            )
        )
    }

    fun showAddItemDialog() {
        val kindField = JComboBox(SavedKind.entries.toTypedArray())
        val moleculeField = JComboBox(arrayOf(SeqKind.DNA, SeqKind.RNA)).apply {
            selectedItem = doc.seq.kind.takeIf { it != SeqKind.PROTEIN } ?: SeqKind.DNA
        }
        val nameField = JTextField(24)
        val basesField = JTextArea(5, 40).apply { lineWrap = true; wrapStyleWord = true }
        val descriptionField = JTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
        val featureTypeLabel = JLabel("Feature type")
        val featureTypeField = JTextField("misc_feature", 20)
        val strandLabel = JLabel("Strand")
        val strandField = JComboBox(Strand.entries.toTypedArray())
        val details = JPanel(GridLayout(0, 2, 6, 6)).apply {
            add(JLabel("Kind"))
            add(kindField)
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Molecule"))
            add(moleculeField)
            add(featureTypeLabel)
            add(featureTypeField)
            add(strandLabel)
            add(strandField)
        }
        val form = JPanel(BorderLayout(0, 8)).apply {
            add(details, BorderLayout.NORTH)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("Sequence"))
                add(JScrollPane(basesField))
                add(Box.createVerticalStrut(6))
                add(JLabel("Description"))
                add(JScrollPane(descriptionField))
            }, BorderLayout.CENTER)
        }
        fun updateFeatureControls() {
            val visible = kindField.selectedItem == SavedKind.FEATURE
            featureTypeLabel.isVisible = visible
            featureTypeField.isVisible = visible
            strandLabel.isVisible = visible
            strandField.isVisible = visible
            form.revalidate()
            SwingUtilities.getWindowAncestor(form)?.pack()
        }
        kindField.addActionListener { updateFeatureControls() }
        updateFeatureControls()

        while (true) {
            val result = JOptionPane.showConfirmDialog(
                null,
                form,
                "Add Library Item",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            )
            if (result != JOptionPane.OK_OPTION) return
            val error = addLibraryItem(
                kind = kindField.selectedItem as SavedKind,
                name = nameField.text,
                sequenceKind = moleculeField.selectedItem as SeqKind,
                bases = basesField.text,
                description = descriptionField.text,
                featureType = featureTypeField.text,
                strand = strandField.selectedItem as Strand,
            )
            if (error == null) return
            JOptionPane.showMessageDialog(null, error, "Add Library Item", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Opens the visible editor for the selected library item. */
    fun editSelected(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        val nameField = JTextField(item.name, 24)
        val moleculeField = JComboBox(arrayOf(SeqKind.DNA, SeqKind.RNA)).apply {
            selectedItem = item.sequenceKind.takeIf { it != SeqKind.PROTEIN } ?: SeqKind.DNA
        }
        val basesField = JTextArea(item.bases, 4, 40).apply { lineWrap = true; wrapStyleWord = true }
        val descriptionField = JTextArea(item.description, 6, 40).apply { lineWrap = true; wrapStyleWord = true }
        val metadata = item.feature ?: SavedFeatureMetadata()
        val featureTypeField = item.takeIf { it.kind == SavedKind.FEATURE }
            ?.let { JTextField(metadata.type, 20) }
        val strandField = item.takeIf { it.kind == SavedKind.FEATURE }
            ?.let { JComboBox(Strand.entries.toTypedArray()).apply { selectedItem = metadata.strand } }
        val details = JPanel(GridLayout(0, 2, 6, 6)).apply {
            add(JLabel("Kind"))
            add(JLabel(displayKind(item.kind)))
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Molecule"))
            add(moleculeField)
            if (item.kind == SavedKind.FEATURE) {
                add(JLabel("Feature type"))
                add(featureTypeField)
                add(JLabel("Strand"))
                add(strandField)
            }
        }
        val result = JOptionPane.showConfirmDialog(
            null,
            JPanel(BorderLayout(0, 8)).apply {
                add(details, BorderLayout.NORTH)
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
        updateLibraryElement(
            row = row,
            name = nameField.text,
            bases = basesField.text,
            description = descriptionField.text,
            sequenceKind = moleculeField.selectedItem as SeqKind,
            featureType = featureTypeField?.text,
            strand = strandField?.selectedItem as? Strand,
        )?.let { error ->
            JOptionPane.showMessageDialog(null, error, "Edit Library Item", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Selects and reveals the source region when the originating sequence is still open. */
    fun jumpToSource(row: Int) {
        val item = prefs.value.library.getOrNull(row) ?: return
        val context = item.context
        if (context.sourceName.isBlank()) return
        val source = doc.seq.name
        val linearRange = context.start in 0..doc.seq.length && context.end in context.start..doc.seq.length
        val wrappingRange = doc.seq.isCircular && context.start in 0 until doc.seq.length &&
            context.end > doc.seq.length && context.end - context.start <= doc.seq.length
        if (context.sourceName == source && (linearRange || wrappingRange)) {
            if (wrappingRange) sequenceView.revealRange(0, doc.seq.length)
            else sequenceView.revealRange(context.start, context.end)
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

    /** Removes exactly the item at [row], including when duplicate values exist. */
    fun deleteSelected(row: Int) {
        if (row !in prefs.value.library.indices) return
        prefs.update { current -> current.copy(library = current.library.filterIndexed { index, _ -> index != row }) }
    }

    private inner class LibraryTableModel : AbstractTableModel() {
        private val columns = arrayOf(
            TableLabels.KIND,
            TableLabels.NAME,
            TableLabels.LENGTH,
            TableLabels.SOURCE,
            TableLabels.DESCRIPTION,
        )

        override fun getRowCount(): Int = prefs.value.library.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = prefs.value.library[rowIndex]
            val context = item.context
            return when (columnIndex) {
                0 -> displayKind(item.kind)
                1 -> item.name
                2 -> TableLabels.length(item.length, item.sequenceKind)
                3 -> if (context.sourceName.isBlank()) "—" else "${context.sourceName} ${context.start + 1}..${context.end}"
                else -> item.description
            }
        }
    }

    private fun displayKind(kind: SavedKind): String =
        kind.name.lowercase().replaceFirstChar { it.uppercase() }

}
