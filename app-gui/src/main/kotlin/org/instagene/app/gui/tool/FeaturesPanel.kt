@file:Suppress("DuplicatedCode")

package org.instagene.app.gui.tool

import org.instagene.app.gui.TableLabels
import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.installRowContextMenu
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedFeatureMetadata
import org.instagene.app.gui.prefs.SavedFeatureDefinition
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.core.Feature
import org.instagene.core.FeatureDefinition
import org.instagene.core.FeatureLibrary
import org.instagene.core.FeatureLibraryFile
import org.instagene.core.FeatureTranslationResult
import org.instagene.core.FeatureTranslations
import org.instagene.core.LabLibraryFiles
import org.instagene.core.LibraryImportMode
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionListener
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPopupMenu
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingWorker
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel
import java.io.File

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
    private val autoAnnotateButton = JButton("Auto-annotate...")
    private val importFeatureLibraryButton = JButton("Import feature library…")
    private val exportFeatureLibraryButton = JButton("Export feature library…")
    private val editElementButton = JButton("Edit Element...")
    private val saveFeatureButton = JButton("Save feature to library")
    private val validateFrameButton = JButton("Validate reading frame")
    private val deleteButton = JButton("Delete")
    private val summary = JLabel(" ")

    /** The active large-library annotation job, if any. Its result is ignored when the document changes. */
    private var autoAnnotationWorker: SwingWorker<org.instagene.core.Seq, org.instagene.core.FeatureScanProgress>? = null

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
        featureTable.installRowContextMenu { row -> featurePopup(row) }

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
            autoAnnotationWorker?.cancel(true)
            autoAnnotationWorker = null
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            docListener?.let { doc.addListener(it) }
        }
        if (docListener == null) {
            val listener = listenerFor()
            docListener = listener
            doc.addListener(listener)
        }
        refresh()
    }

    /** Cancels background annotation before the owning editor is disposed. */
    fun dispose() {
        autoAnnotationWorker?.cancel(true)
        autoAnnotationWorker = null
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
            add(autoAnnotateButton.apply {
                addActionListener { autoAnnotateDialog() }
            })
            add(importFeatureLibraryButton.apply {
                toolTipText = "Import a versioned, reviewable feature-library JSON file."
                addActionListener { importFeatureLibraryDialog() }
            })
            add(exportFeatureLibraryButton.apply {
                toolTipText = "Export the saved feature-library rules as a versioned JSON file."
                addActionListener { exportFeatureLibraryDialog() }
            })
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(editElementButton.apply {
                addActionListener { editFeatureElement(featureTable.selectedRow) }
            })
            add(saveFeatureButton.apply {
                addActionListener { saveSelectedFeature() }
            })
            add(validateFrameButton.apply {
                toolTipText = "Translate the selected feature from its exact coordinates and validate its reading frame."
                addActionListener { showFeatureTranslation(featureTable.selectedRow) }
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
        autoAnnotateButton.isEnabled = doc.seq.kind != SeqKind.PROTEIN && doc.seq.length > 0 && autoAnnotationWorker == null
        deleteButton.isEnabled = featureTable.selectedRow in doc.seq.features.indices
        editElementButton.isEnabled = deleteButton.isEnabled
        saveFeatureButton.isEnabled = savableFeature(featureTable.selectedRow) != null
        validateFrameButton.isEnabled = deleteButton.isEnabled && doc.seq.kind != SeqKind.PROTEIN
        val features = doc.seq.features
        summary.text = if (features.isEmpty()) {
            "No features. Select a region and use \"Add Feature from Selection...\", or type coordinates with \"Add Feature Manually...\"."
        } else {
            "${features.size} feature(s), ${features.sumOf { it.length }} bp annotated"
        }
    }

    private fun featurePopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row in doc.seq.features.indices
        val canAddSelection = doc.hasSelection && doc.selectionEnd > doc.selectionStart
        add(ContextMenus.item(
            "Add Feature from Selection…",
            "Annotate the currently selected bases as a new feature.",
            canAddSelection,
        ) { addFeatureDialog() })
        add(ContextMenus.item(
            "Add Feature Manually…",
            "Create a feature by typing 1-based start and end coordinates.",
            doc.seq.length > 0,
        ) { manualAddDialog() })
        add(ContextMenus.item(
            "Auto-annotate…",
            "Find feature-library patterns in the current nucleotide sequence.",
            doc.seq.kind != SeqKind.PROTEIN && doc.seq.length > 0,
        ) { autoAnnotateDialog() })
        addSeparator()
        add(ContextMenus.item(
            "Reveal Feature",
            "Select this feature's bases in the sequence viewer.",
            hasRow,
        ) { revealFeature(row ?: -1) })
        add(ContextMenus.item(
            "Edit Element…",
            "Edit this feature's name, type, coordinates, strand, color, visibility, order, and description.",
            hasRow,
        ) { editFeatureElement(row ?: -1) })
        add(ContextMenus.item(
            "Save Feature to Library",
            "Save this feature and its source bases to the reusable library.",
            savableFeature(row ?: -1) != null,
        ) { saveFeature(row ?: -1) })
        add(ContextMenus.item(
            "Validate Reading Frame",
            "Translate this feature from its annotated coordinates and inspect linked codon positions.",
            hasRow && doc.seq.kind != SeqKind.PROTEIN,
        ) { showFeatureTranslation(row ?: -1) })
        add(ContextMenus.item(
            "Delete",
            "Remove this feature from the current sequence.",
            hasRow,
        ) { deleteFeature(row ?: -1) })
    }

    /** Exposed for tests: whether "Add Feature from Selection..." is currently enabled. */
    fun isAddEnabled(): Boolean = addButton.isEnabled

    /** Exposed for tests: whether "Add Feature Manually..." is currently enabled. */
    fun isManualAddEnabled(): Boolean = manualAddButton.isEnabled

    /** Whether "Auto-annotate..." is available for the current document. */
    fun isAutoAnnotateEnabled(): Boolean = autoAnnotateButton.isEnabled

    /** Current persisted feature rules in the engine's portable representation. */
    fun featureLibraryDefinitions(): List<FeatureDefinition> = prefs.value.featureLibrary.map { it.toDefinition() }

    /** Builds a portable feature-library file without writing it, useful for tests and headless callers. */
    fun exportFeatureLibrary(name: String = "Feature library", description: String = ""): FeatureLibraryFile =
        LabLibraryFiles.featureLibrary(name, featureLibraryDefinitions(), description)

    /** Imports a previously decoded feature library using an explicit non-destructive or replace policy. */
    fun importFeatureLibrary(file: FeatureLibraryFile, mode: LibraryImportMode = LibraryImportMode.MERGE): Int {
        val merged = LabLibraryFiles.mergeDefinitions(featureLibraryDefinitions(), file.definitions, mode)
        prefs.update { current -> current.copy(featureLibrary = merged.map { it.toSaved() }) }
        return merged.size
    }

    private fun importFeatureLibraryDialog() {
        val chooser = JFileChooser().apply { dialogTitle = "Import feature library" }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching { LabLibraryFiles.readFeatureLibrary(chooser.selectedFile) }.onSuccess { file ->
            val mode = chooseFeatureImportMode(file) ?: return@onSuccess
            val count = importFeatureLibrary(file, mode)
            summary.text = "${if (mode == LibraryImportMode.MERGE) "Merged" else "Replaced"} feature library with $count rule(s) from ${file.name}."
        }.onFailure { error ->
            JOptionPane.showMessageDialog(this, error.message ?: "Unable to import the feature library.", "Import feature library", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun exportFeatureLibraryDialog() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export feature library"
            selectedFile = File("feature-library${LabLibraryFiles.FEATURE_LIBRARY_SUFFIX}")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val destination = chooser.selectedFile
        val displayName = destination.name.removeSuffix(LabLibraryFiles.FEATURE_LIBRARY_SUFFIX).removeSuffix(".json")
        runCatching { LabLibraryFiles.write(destination, exportFeatureLibrary(displayName)) }
            .onSuccess { summary.text = "Exported ${featureLibraryDefinitions().size} feature rule(s) to ${destination.name}." }
            .onFailure { error ->
                JOptionPane.showMessageDialog(this, error.message ?: "Unable to export the feature library.", "Export feature library", JOptionPane.ERROR_MESSAGE)
            }
    }

    private fun chooseFeatureImportMode(file: FeatureLibraryFile): LibraryImportMode? {
        val choice = JOptionPane.showOptionDialog(
            this,
            "Import '${file.name}' (${file.definitions.size} rule(s)).\nMerge retains existing rules; replace clears them first.",
            "Import feature library",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf("Merge", "Replace", "Cancel"),
            "Merge",
        )
        return when (choice) {
            0 -> LibraryImportMode.MERGE
            1 -> LibraryImportMode.REPLACE
            else -> null
        }
    }

    /** Whether "Edit Element..." can act on the currently selected feature row. */
    fun isEditElementEnabled(): Boolean = editElementButton.isEnabled

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

    /** Coordinate-linked translation validation for one annotated feature, or null for an invalid row. */
    fun validateFeatureTranslation(row: Int): FeatureTranslationResult? = doc.seq.features.getOrNull(row)
        ?.takeIf { doc.seq.kind != SeqKind.PROTEIN }
        ?.let { FeatureTranslations.translate(doc.seq, it) }

    /** Exposed for tests: the current summary or confirmation text. */
    fun summaryText(): String = summary.text

    private fun showFeatureTranslation(row: Int) {
        val result = validateFeatureTranslation(row) ?: return
        val details = JTextArea(FeatureTranslations.summary(result), 20, 76).apply {
            isEditable = false
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        }
        JOptionPane.showMessageDialog(
            this,
            JScrollPane(details),
            "Reading-frame validation: ${result.feature.name}",
            if (result.hasErrors) JOptionPane.ERROR_MESSAGE else JOptionPane.INFORMATION_MESSAGE,
        )
    }

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
        color: String? = null,
        visible: Boolean = true,
        displayOrder: Int = 0,
        geneticCodeId: Int = 1,
        translationNumberingStart: Int = 1,
        translationStartOffset: Int = 0,
        ribosomalSlippage: Int = 0,
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
            color = color?.trim()?.ifBlank { null },
            visible = visible,
            displayOrder = displayOrder,
            geneticCodeId = geneticCodeId,
            translationNumberingStart = translationNumberingStart,
            translationStartOffset = translationStartOffset,
            ribosomalSlippage = ribosomalSlippage,
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

    /** Adds a feature over the specified coordinates (undoable). */
    fun addFeature(name: String, type: String = "misc_feature", notes: String = "", start: Int = doc.selectionStart, end: Int = doc.selectionEnd) {
        if (start >= end) return
        val feature = Feature(
            name = name.ifBlank { "feature" },
            type = type.ifBlank { "misc_feature" },
            start = start,
            end = end,
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
        color: String? = null,
        visible: Boolean = true,
        displayOrder: Int = 0,
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
            color = color?.trim()?.ifBlank { null },
            visible = visible,
            displayOrder = displayOrder,
        )
        doc.mutate("add feature") { it.withFeature(feature) }
        return true
    }

    fun addFeatureDialog() {
        if (!doc.hasSelection || doc.selectionEnd <= doc.selectionStart) return
        val capturedStart = doc.selectionStart
        val capturedEnd = doc.selectionEnd
        val nameField = JTextField(20)
        val typeField = JComboBox(TYPES.toTypedArray()).apply { isEditable = true }
        val notesField = JTextField(20)
        val form = JPanel(GridLayout(3, 2, 6, 6)).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Type"))
            add(typeField)
            add(JLabel("Description"))
            add(notesField)
        }
        val start = capturedStart + 1
        val ok = JOptionPane.showConfirmDialog(
            null,
            JPanel(BorderLayout(0, 6)).apply {
                add(JLabel("Feature on ${doc.seq.name}: $start..$capturedEnd"), BorderLayout.NORTH)
                add(form, BorderLayout.CENTER)
            },
            "Add Feature",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (ok == JOptionPane.OK_OPTION) {
            addFeature(nameField.text, typeField.selectedItem as String, notesField.text, capturedStart, capturedEnd)
        }
    }

    fun manualAddDialog() {
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

    /** Applies simple pattern-backed feature definitions to the current sequence. */
    fun autoAnnotateDialog() {
        if (doc.seq.kind == SeqKind.PROTEIN || doc.seq.length == 0) return

        val presetNames = arrayOf("<None>") + FeatureLibrary.BUILTIN_PRESETS.keys.toTypedArray()
        val presetCombo = JComboBox(presetNames)
        val strandCombo = JComboBox(arrayOf("Forward strand", "Reverse strand", "Both strands"))
        val matchCountLabel = JLabel(" ")
        matchCountLabel.toolTipText = "Preview shows how many matches each pattern finds."
        val previewButton = JButton("Preview matches")
        previewButton.toolTipText = "Count matches for each pattern without applying changes."
        var previewWorker: SwingWorker<List<org.instagene.core.MatchInfo>, org.instagene.core.FeatureScanProgress>? = null

        val patterns = JTextArea(
            prefs.value.featureLibrary.takeIf { it.isNotEmpty() }?.joinToString("\n") { saved ->
                "${saved.name}|${saved.type}|${if (saved.exclude) "!" else ""}${saved.pattern}"
            }
                ?: "promoter|promoter|TATAAA",
            10,
            50,
        ).apply { lineWrap = true; wrapStyleWord = false }

        val helpText = JTextArea(
            buildString {
                appendLine("Pattern syntax:")
                appendLine("  ACGT  — literal IUPAC sequence")
                appendLine("  R, Y, S, W, K, M, B, D, H, V, N — degenerate bases")
                appendLine("  # or + — zero or more nucleotides (wildcard)")
                appendLine("  {n}    — exactly n nucleotides")
                appendLine("  {n,m}  — between n and m nucleotides")
                appendLine("  !pattern — exclude regions matching this pattern")
                appendLine()
                appendLine("Format: name|type|pattern")
                appendLine("  name   — feature name (e.g. His6 tag)")
                appendLine("  type   — SO term (CDS, promoter, misc_feature, etc.)")
                appendLine("  pattern — IUPAC pattern with wildcards")
                appendLine()
                appendLine("Examples:")
                appendLine("  My plasmid|rep_origin|{100,500}")
                appendLine("  Polylinker|MCS|GAATTCGGATCC")
                appendLine("  Exclude poly-A|misc_feature|!AAAAAAAAAA")
            },
            18,
            50,
        ).apply { isEditable = false; lineWrap = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }

        val presetListener = ActionListener {
            val selected = presetCombo.selectedItem?.toString() ?: "<None>"
            if (selected != "<None>") {
                val presets = FeatureLibrary.BUILTIN_PRESETS[selected] ?: emptyList()
                val presetText = presets.joinToString("\n") { definition ->
                    "${definition.name}|${definition.type}|${if (definition.exclude) "!" else ""}${definition.pattern}"
                }
                patterns.text = presetText
            }
        }
        presetCombo.addActionListener(presetListener)

        val previewListener = ActionListener {
            previewWorker?.let { running ->
                running.cancel(true)
                previewButton.isEnabled = false
                matchCountLabel.text = "Cancelling feature scan…"
                return@ActionListener
            }
            val defs = parseAutoAnnotateDefinitions(patterns.text)
            if (defs.isEmpty()) {
                matchCountLabel.text = "No valid definitions."
                return@ActionListener
            }
            val strandIdx = strandCombo.selectedIndex
            val searchBoth = strandIdx == 2
            val source = doc.seq
            val worker = object : SwingWorker<List<org.instagene.core.MatchInfo>, org.instagene.core.FeatureScanProgress>() {
                override fun doInBackground(): List<org.instagene.core.MatchInfo> =
                    FeatureLibrary.previewMatchesCancellable(
                        source,
                        defs,
                        searchBothStrands = searchBoth,
                        cancellationRequested = { isCancelled || Thread.currentThread().isInterrupted },
                        progress = { publish(it) },
                    )

                override fun process(chunks: MutableList<org.instagene.core.FeatureScanProgress>) {
                    if (previewWorker !== this || chunks.isEmpty()) return
                    val update = chunks.last()
                    matchCountLabel.text = "Scanning feature patterns: ${update.completedDefinitions}/${update.totalDefinitions} (${update.matches} match(es))…"
                }

                override fun done() {
                    if (previewWorker !== this) return
                    previewWorker = null
                    previewButton.text = "Preview matches"
                    previewButton.isEnabled = true
                    if (isCancelled) {
                        matchCountLabel.text = "Feature scan cancelled."
                        return
                    }
                    val matches = try {
                        get()
                    } catch (_: CancellationException) {
                        matchCountLabel.text = "Feature scan cancelled."
                        return
                    } catch (error: ExecutionException) {
                        matchCountLabel.text = "Feature scan failed: ${error.cause?.message ?: "unknown error"}"
                        return
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        matchCountLabel.text = "Feature scan interrupted."
                        return
                    }
                    if (doc.seq !== source) {
                        matchCountLabel.text = "Feature scan ignored because the sequence changed."
                        return
                    }
                    val byName = matches.filter { !it.definition.exclude }.groupBy { it.name }
                    val excluded = matches.count { it.definition.exclude }
                    val total = matches.count { !it.definition.exclude }
                    matchCountLabel.text = "<html>${defs.size} pattern(s): <b>${total}</b> match(es)${if (excluded > 0) ", $excluded excluded" else ""}</html>"
                    matchCountLabel.toolTipText = byName.entries.joinToString("\n") { (name, list) -> "$name: ${list.size} match(es)" }
                }
            }
            previewWorker = worker
            previewButton.text = "Cancel preview"
            matchCountLabel.text = "Scanning feature patterns: 0/${defs.size}…"
            worker.execute()
        }
        previewButton.addActionListener(previewListener)

        val strandPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("Search:")); add(strandCombo); add(previewButton); add(matchCountLabel)
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(JLabel("Preset:")); add(presetCombo)
            })
            add(strandPanel)
            add(JLabel("One definition per line: name|type|pattern"))
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            add(JLabel("Pattern Reference"), BorderLayout.NORTH)
            add(JScrollPane(helpText), BorderLayout.CENTER)
        }

        val splitPane = javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(patterns),
            rightPanel,
        ).apply { dividerLocation = 400; isOneTouchExpandable = true }

        val panel = JPanel(BorderLayout(0, 6)).apply {
            add(topPanel, BorderLayout.NORTH)
            add(splitPane, BorderLayout.CENTER)
        }

        val ok = JOptionPane.showConfirmDialog(
            null, panel, "Auto-annotate from Feature Library", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        previewWorker?.let { worker ->
            previewWorker = null
            worker.cancel(true)
        }
        if (ok != JOptionPane.OK_OPTION) return
        val definitions = parseAutoAnnotateDefinitions(patterns.text)
        if (definitions.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No valid feature definitions were entered.", "Auto-annotate", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val strandIdx = strandCombo.selectedIndex
        val searchBoth = strandIdx == 2
        annotateAsync(doc.seq, definitions, searchBoth)
    }

    /** Runs the potentially expensive annotation pass outside the event thread. */
    private fun annotateAsync(
        source: org.instagene.core.Seq,
        definitions: List<FeatureDefinition>,
        searchBoth: Boolean,
    ) {
        autoAnnotationWorker?.cancel(true)
        val worker = object : SwingWorker<org.instagene.core.Seq, org.instagene.core.FeatureScanProgress>() {
            override fun doInBackground(): org.instagene.core.Seq = FeatureLibrary.annotateCancellable(
                source,
                definitions,
                searchBothStrands = searchBoth,
                cancellationRequested = { isCancelled || Thread.currentThread().isInterrupted },
                progress = { publish(it) },
            )

            override fun process(chunks: MutableList<org.instagene.core.FeatureScanProgress>) {
                if (autoAnnotationWorker !== this || chunks.isEmpty()) return
                val update = chunks.last()
                summary.text = "Auto-annotating: ${update.completedDefinitions}/${update.totalDefinitions} pattern(s), ${update.matches} match(es)…"
            }

            override fun done() {
                if (autoAnnotationWorker !== this) return
                autoAnnotationWorker = null
                val annotated = try {
                    get()
                } catch (_: CancellationException) {
                    summary.text = "Auto-annotation cancelled."
                    refreshSelectionState()
                    return
                } catch (error: ExecutionException) {
                    summary.text = "Auto-annotation failed: ${error.cause?.message ?: "unknown error"}"
                    refreshSelectionState()
                    return
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    summary.text = "Auto-annotation interrupted."
                    refreshSelectionState()
                    return
                }
                if (doc.seq !== source) {
                    summary.text = "Auto-annotation ignored because the sequence changed."
                    refreshSelectionState()
                    return
                }
                val added = annotated.features.size - source.features.size
                prefs.update { current ->
                    current.copy(featureLibrary = LabLibraryFiles.mergeDefinitions(
                        current.featureLibrary.map { it.toDefinition() },
                        definitions,
                        LibraryImportMode.MERGE,
                    ).map { it.toSaved() })
                }
                doc.mutate("auto-annotate features") { annotated }
                summary.text = "Auto-annotated $added feature(s) from ${definitions.size} definition(s)."
                refreshSelectionState()
            }
        }
        autoAnnotationWorker = worker
        autoAnnotateButton.isEnabled = false
        summary.text = "Auto-annotating: 0/${definitions.size} pattern(s)…"
        worker.execute()
    }

    private fun parseAutoAnnotateDefinitions(text: String): List<FeatureDefinition> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val fields = line.split('|', limit = 3).map(String::trim)
            fun definition(name: String, pattern: String, type: String = "misc_feature"): FeatureDefinition? {
                if (name.isBlank() || pattern.isBlank()) return null
                return FeatureDefinition(name, pattern.removePrefix("!"), type.ifBlank { "misc_feature" }, exclude = pattern.startsWith("!"))
            }
            when (fields.size) {
                3 -> definition(fields[0], fields[2], fields[1])
                2 -> definition(fields[0], fields[1])
                else -> null
            }
        }
        .toList()

    private fun SavedFeatureDefinition.toDefinition(): FeatureDefinition = FeatureDefinition(
        name = name,
        pattern = pattern.removePrefix("!"),
        type = type,
        strand = strand,
        color = color,
        uppercaseOnly = uppercaseOnly,
        exclude = exclude || pattern.startsWith("!"),
    )

    private fun FeatureDefinition.toSaved(): SavedFeatureDefinition = SavedFeatureDefinition(
        name = name,
        pattern = pattern.removePrefix("!"),
        type = type,
        strand = strand,
        color = color,
        uppercaseOnly = uppercaseOnly,
        exclude = exclude,
    )

    /** Deletes the feature currently selected in the table (undoable). */
    fun deleteSelectedFeature() {
        deleteFeature(featureTable.selectedRow)
    }

    /** Opens the visible GUI editor for the feature currently selected in the table. */
    fun editSelectedFeatureElement() {
        editFeatureElement(featureTable.selectedRow)
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
        val colorField = JTextField(feature.color.orEmpty(), 10)
        val visibleField = JCheckBox("Visible", feature.visible)
        val orderField = JSpinner(SpinnerNumberModel(feature.displayOrder, -1000, 1000, 1))
        val geneticCodeField = JSpinner(SpinnerNumberModel(feature.geneticCodeId, 1, 33, 1))
        val numberingField = JSpinner(SpinnerNumberModel(feature.translationNumberingStart, -1_000_000, 1_000_000, 1))
        val translationOffsetField = JSpinner(SpinnerNumberModel(feature.translationStartOffset, 0, 2, 1))
        val slippageField = JSpinner(SpinnerNumberModel(feature.ribosomalSlippage, -2, 2, 1))
        val descriptionField = JTextArea(feature.notes, 6, 40).apply { lineWrap = true; wrapStyleWord = true }
        val form = JPanel(GridLayout(12, 2, 6, 6)).apply {
            add(JLabel("Name")); add(nameField)
            add(JLabel("Type")); add(typeField)
            add(JLabel("Start (1-based)")); add(startField)
            add(JLabel("End (1-based)")); add(endField)
            add(JLabel("Strand")); add(strandField)
            add(JLabel("Color (#RRGGBB)")); add(colorField)
            add(JLabel("Display order")); add(orderField)
            add(JLabel("Visibility")); add(visibleField)
            add(JLabel("Genetic code table")); add(geneticCodeField)
            add(JLabel("Translation numbering start")); add(numberingField)
            add(JLabel("Translation start offset")); add(translationOffsetField)
            add(JLabel("Ribosomal slippage")); add(slippageField)
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
                colorField.text, visibleField.isSelected, (orderField.value as Number).toInt(),
                (geneticCodeField.value as Number).toInt(),
                (numberingField.value as Number).toInt(),
                (translationOffsetField.value as Number).toInt(),
                (slippageField.value as Number).toInt(),
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
