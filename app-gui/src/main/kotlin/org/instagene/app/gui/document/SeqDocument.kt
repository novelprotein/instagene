package org.instagene.app.gui.document

import org.instagene.core.CutSite
import org.instagene.core.Digest
import org.instagene.core.Enzyme
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.project.EditKind
import java.io.File

/**
 * One open sequence, plus everything the views need to stay in step: the
 * selection, the enzymes currently mapped, an undo history and a dirty flag.
 */
class SeqDocument(initial: Seq, file: File? = null) : Doc {

    /** Receives a callback with the change [Reason] whenever the document changes. */
    fun interface Listener {
        fun documentChanged(doc: SeqDocument, reason: Reason)
    }

    /** What changed in the document: the sequence, the selection, or the mapped enzymes. */
    enum class Reason { SEQUENCE, SELECTION, ENZYMES }

    var seq: Seq = initial
        private set

    override var file: File? = file
        set(value) {
            field = value
            notify(Reason.SEQUENCE)
        }

    override var isDirty: Boolean = false
        private set

    /** The name shown on the document tab and in the window title. */
    override val displayName: String get() = file?.name ?: seq.name.ifBlank { "Untitled" }

    /** The saved or loaded sequence against which [isDirty] is calculated. */
    private var savedSeq: Seq = initial

    /** Caret position, in `0..length`. */
    var caret: Int = 0
        private set

    /** The other end of the selection; equal to [caret] when nothing is selected. */
    var anchor: Int = 0
        private set

    /** The lower of [caret] and [anchor]. */
    val selectionStart: Int get() = minOf(caret, anchor)

    /** The upper of [caret] and [anchor]. */
    val selectionEnd: Int get() = maxOf(caret, anchor)

    /** True when [caret] and [anchor] differ, i.e. something is selected. */
    val hasSelection: Boolean get() = caret != anchor

    /** The bases spanned by the current selection. */
    val selectedBases: String get() = seq.sub(selectionStart, selectionEnd)

    /** The enzymes currently mapped for the cut-site view. */
    var mappedEnzymes: List<Enzyme> = emptyList()
        private set

    /** Cut sites of [mappedEnzymes] in the current sequence, refreshed on every change. */
    var cutSites: List<CutSite> = emptyList()
        private set

    /** Bases changed by the most recent edit/undo/redo, for optional history coloring. */
    var recentChangeRange: IntRange? = null
        private set

    private val listeners = ArrayList<Listener>()
    private val docListeners = ArrayList<Doc.Listener>()
    private val editListeners = ArrayList<Doc.EditListener>()
    private val undoStack = ArrayDeque<Pair<String, Seq>>()
    private val redoStack = ArrayDeque<Pair<String, Seq>>()
    private val historyLimit = 100
    private var notificationsEnabled = true

    /** Registers [listener] to be notified of every document change. */
    fun addListener(listener: Listener) {
        listeners += listener
    }

    /** Unregisters [listener]; a no-op when it was never registered. */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    override fun addDocListener(listener: Doc.Listener) {
        docListeners += listener
    }

    override fun removeDocListener(listener: Doc.Listener) {
        docListeners.remove(listener)
    }

    override fun addEditListener(listener: Doc.EditListener) {
        editListeners += listener
    }

    override fun removeEditListener(listener: Doc.EditListener) {
        editListeners.remove(listener)
    }

    private fun fireEdit(kind: EditKind, label: String?, detail: String?) {
        editListeners.toList().forEach { it.docEdited(this, kind, label, detail) }
    }

    private fun notify(reason: Reason) {
        if (notificationsEnabled) {
            listeners.toList().forEach { it.documentChanged(this, reason) }
            docListeners.toList().forEach { it.docChanged(this) }
        }
    }

    /** Suppresses listener notifications while multiple updates are applied. */
    fun beginBatchUpdate() {
        notificationsEnabled = false
    }

    /** Re-enables notifications and notifies listeners of the final state. */
    fun endBatchUpdate(reason: Reason = Reason.SEQUENCE) {
        notificationsEnabled = true
        notify(reason)
    }

    // --------------------------------------------------------------- mutation

    /** Applies [transform], recording an undo entry labelled [label]. */
    fun mutate(label: String, transform: (Seq) -> Seq) {
        val before = seq
        val next = transform(seq)
        if (next == seq) return
        undoStack.addLast(label to seq)
        while (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
        seq = next
        recentChangeRange = changedBaseRange(before, next)
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
        fireEdit(EditKind.EDIT, label, changeDetail(before, next))
    }

    /** Replaces the sequence outright (file load, format change) and clears history. */
    fun reset(next: Seq, newFile: File? = file, dirty: Boolean = false) {
        undoStack.clear()
        redoStack.clear()
        seq = next
        recentChangeRange = null
        savedSeq = next
        file = newFile
        isDirty = dirty
        caret = 0
        anchor = 0
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    /** Records a save to [savedTo], clearing the dirty flag and updating the saved-state baseline. */
    override fun markSaved(savedTo: File) {
        val savedAs = file != null && file != savedTo
        file = savedTo
        savedSeq = seq
        isDirty = false
        fireEdit(if (savedAs) EditKind.SAVE_AS else EditKind.SAVE, null, null)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun undoLabel(): String? = undoStack.lastOrNull()?.first
    fun redoLabel(): String? = redoStack.lastOrNull()?.first

    /** Reverts the most recent [mutate], restoring the previous sequence. */
    fun undo() {
        val (label, previous) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(label to seq)
        val before = seq
        seq = previous
        recentChangeRange = changedBaseRange(before, previous)
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
        fireEdit(EditKind.UNDO, label, changeDetail(before, previous))
    }

    /** Re-applies the change most recently reverted by [undo]. */
    fun redo() {
        val (label, next) = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(label to seq)
        val before = seq
        seq = next
        recentChangeRange = changedBaseRange(before, next)
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
        fireEdit(EditKind.REDO, label, changeDetail(before, next))
    }

    /** A short summary of what changed between [before] and [after], for the edit history. */
    private fun changeDetail(before: Seq, after: Seq): String? {
        val unit = if (after.kind == SeqKind.PROTEIN) "aa" else "bp"
        return when {
            before.length != after.length -> "${before.length} -> ${after.length} $unit"
            before.name != after.name -> "${before.name} -> ${after.name}"
            before.topology != after.topology -> "${before.topology.name.lowercase()} -> ${after.topology.name.lowercase()}"
            before.features != after.features -> "features ${before.features.size} -> ${after.features.size}"
            else -> null
        }
    }

    private fun changedBaseRange(before: Seq, after: Seq): IntRange? {
        if (before.bases == after.bases) return null
        val common = minOf(before.length, after.length)
        var prefix = 0
        while (prefix < common && before.bases[prefix] == after.bases[prefix]) prefix++
        var suffix = 0
        while (
            suffix < common - prefix &&
            before.bases[before.length - 1 - suffix] == after.bases[after.length - 1 - suffix]
        ) suffix++
        if (after.length == 0) return null
        val endExclusive = (after.length - suffix).coerceAtLeast(prefix + 1).coerceAtMost(after.length)
        val start = prefix.coerceAtMost(after.length - 1)
        return start until endExclusive
    }

    /** Dirty means "differs from the last saved or loaded state", not "edited at all". */
    private fun refreshDirty() {
        isDirty = seq != savedSeq
    }

    // -------------------------------------------------------------- selection

    /**
     * Moves the caret to [position], clamped to the sequence. When [extendSelection]
     * is true, only the caret end of the selection moves.
     */
    fun moveCaret(position: Int, extendSelection: Boolean = false) {
        caret = position.coerceIn(0, seq.length)
        if (!extendSelection) anchor = caret
        notify(Reason.SELECTION)
    }

    /** Selects `[start, end)`, both ends clamped to the sequence. */
    fun select(start: Int, end: Int) {
        anchor = start.coerceIn(0, seq.length)
        caret = end.coerceIn(0, seq.length)
        notify(Reason.SELECTION)
    }

    /** Selects the whole sequence. */
    fun selectAll() = select(0, seq.length)

    private fun clampSelection() {
        caret = caret.coerceIn(0, seq.length)
        anchor = anchor.coerceIn(0, seq.length)
    }

    // ---------------------------------------------------------------- enzymes

    /** Replaces the mapped set with [enzymes] and rescans cut sites synchronously. */
    fun setMappedEnzymes(enzymes: List<Enzyme>) {
        mappedEnzymes = enzymes
        refreshCutSites()
        notify(Reason.ENZYMES)
    }

    /**
     * Maps [enzymes] and applies a caller-computed set of [sites] directly,
     * skipping the synchronous whole-sequence rescan. Lets the digest panel
     * compute cut sites off the event thread for very large sequences.
     */
    fun applyMappedEnzymes(enzymes: List<Enzyme>, sites: List<CutSite>) {
        mappedEnzymes = enzymes
        cutSites = sites
        notify(Reason.ENZYMES)
    }

    /** Adds [enzyme] to the mapped set. */
    fun addEnzyme(enzyme: Enzyme) {
        setMappedEnzymes(mappedEnzymes + enzyme)
    }

    /** Unmaps every enzyme. */
    fun clearEnzymes() {
        setMappedEnzymes(emptyList())
    }

    /** Loads a sequence while suppressing intermediate listener notifications. */
    fun loadSequence(newSeq: Seq, newFile: File? = null) {
        beginBatchUpdate()
        reset(newSeq, newFile, false)
        endBatchUpdate()
    }

    private fun refreshCutSites() {
        cutSites = if (mappedEnzymes.isEmpty()) {
            emptyList()
        } else {
            Digest.cutSites(seq, mappedEnzymes)
        }
    }
}
