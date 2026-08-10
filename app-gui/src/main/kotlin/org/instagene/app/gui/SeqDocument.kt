package org.instagene.app.gui

import org.instagene.core.CutSite
import org.instagene.core.Enzyme
import org.instagene.core.Seq
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

    /** The sequence as of the last save/load: the baseline `isDirty` is compared against. */
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

    private val listeners = ArrayList<Listener>()
    private val docListeners = ArrayList<Doc.Listener>()
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

    private fun notify(reason: Reason) {
        if (notificationsEnabled) {
            listeners.toList().forEach { it.documentChanged(this, reason) }
            docListeners.toList().forEach { it.docChanged(this) }
        }
    }

    /** Batch multiple updates without triggering listener notifications. Call endBatchUpdate() when done. */
    fun beginBatchUpdate() {
        notificationsEnabled = false
    }

    /** Re-enable notifications and notify listeners of the final state. */
    fun endBatchUpdate(reason: Reason = Reason.SEQUENCE) {
        notificationsEnabled = true
        notify(reason)
    }

    // --------------------------------------------------------------- mutation

    /** Applies [transform], recording an undo entry labelled [label]. */
    fun mutate(label: String, transform: (Seq) -> Seq) {
        val next = transform(seq)
        if (next == seq) return
        undoStack.addLast(label to seq)
        while (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
        seq = next
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    /** Replaces the sequence outright (file load, format change) and clears history. */
    fun reset(next: Seq, newFile: File? = file, dirty: Boolean = false) {
        undoStack.clear()
        redoStack.clear()
        seq = next
        savedSeq = next
        file = newFile
        isDirty = dirty
        caret = 0
        anchor = 0
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    /** Records that the document was saved to [savedTo]: the dirty flag clears and the undo baseline moves up. */
    override fun markSaved(savedTo: File) {
        file = savedTo
        savedSeq = seq
        isDirty = false
        notify(Reason.SEQUENCE)
    }

    //val undoLabel: String? get() = undoStack.lastOrNull()?.first
    //val redoLabel: String? get() = redoStack.lastOrNull()?.first

    /** Reverts the most recent [mutate], restoring the previous sequence. */
    fun undo() {
        val (label, previous) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(label to seq)
        seq = previous
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    /** Re-applies the change most recently reverted by [undo]. */
    fun redo() {
        val (label, next) = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(label to seq)
        seq = next
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    /** Dirty means "differs from the last saved or loaded state", not "edited at all". */
    private fun refreshDirty() {
        isDirty = seq != savedSeq
    }

    // -------------------------------------------------------------- selection

    /** Moves the caret to [position] (clamped to the sequence); with [extendSelection] only the caret end of the selection moves. */
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

    /** Load a sequence while suppressing listener notifications for better performance. */
    fun loadSequence(newSeq: Seq, newFile: File? = null) {
        beginBatchUpdate()
        reset(newSeq, newFile, false)
        endBatchUpdate()
    }

    private fun refreshCutSites() {
        cutSites = if (mappedEnzymes.isEmpty()) {
            emptyList()
        } else {
            org.instagene.core.Digest.cutSites(seq, mappedEnzymes)
        }
    }
}
