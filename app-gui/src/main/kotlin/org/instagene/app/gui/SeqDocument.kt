package org.instagene.app.gui

import org.instagene.core.CutSite
import org.instagene.core.Enzyme
import org.instagene.core.Seq
import java.io.File

/**
 * One open sequence, plus everything the views need to stay in step: the
 * selection, the enzymes currently mapped, an undo history and a dirty flag.
 */
class SeqDocument(initial: Seq, file: File? = null) {

    fun interface Listener {
        fun documentChanged(doc: SeqDocument, reason: Reason)
    }

    enum class Reason { SEQUENCE, SELECTION, ENZYMES }

    var seq: Seq = initial
        private set

    var file: File? = file
        set(value) {
            field = value
            notify(Reason.SEQUENCE)
        }

    var isDirty: Boolean = false
        private set

    /** The sequence as of the last save/load: the baseline `isDirty` is compared against. */
    private var savedSeq: Seq = initial

    /** Caret position, in `0..length`. */
    var caret: Int = 0
        private set

    /** The other end of the selection; equal to [caret] when nothing is selected. */
    var anchor: Int = 0
        private set

    val selectionStart: Int get() = minOf(caret, anchor)
    val selectionEnd: Int get() = maxOf(caret, anchor)
    val hasSelection: Boolean get() = caret != anchor
    val selectedBases: String get() = seq.sub(selectionStart, selectionEnd)

    var mappedEnzymes: List<Enzyme> = emptyList()
        private set

    var cutSites: List<CutSite> = emptyList()
        private set

    private val listeners = ArrayList<Listener>()
    private val undoStack = ArrayDeque<Pair<String, Seq>>()
    private val redoStack = ArrayDeque<Pair<String, Seq>>()
    private val historyLimit = 100
    private var notificationsEnabled = true

    fun addListener(listener: Listener) {
        listeners += listener
    }

    private fun notify(reason: Reason) {
        if (notificationsEnabled) {
            listeners.toList().forEach { it.documentChanged(this, reason) }
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

    fun markSaved(savedTo: File) {
        file = savedTo
        savedSeq = seq
        isDirty = false
        notify(Reason.SEQUENCE)
    }

    //val undoLabel: String? get() = undoStack.lastOrNull()?.first
    //val redoLabel: String? get() = redoStack.lastOrNull()?.first

    fun undo() {
        val (label, previous) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(label to seq)
        seq = previous
        refreshDirty()
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

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

    fun moveCaret(position: Int, extendSelection: Boolean = false) {
        caret = position.coerceIn(0, seq.length)
        if (!extendSelection) anchor = caret
        notify(Reason.SELECTION)
    }

    fun select(start: Int, end: Int) {
        anchor = start.coerceIn(0, seq.length)
        caret = end.coerceIn(0, seq.length)
        notify(Reason.SELECTION)
    }

    fun selectAll() = select(0, seq.length)

    private fun clampSelection() {
        caret = caret.coerceIn(0, seq.length)
        anchor = anchor.coerceIn(0, seq.length)
    }

    // ---------------------------------------------------------------- enzymes

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

    fun addEnzyme(enzyme: Enzyme) {
        setMappedEnzymes(mappedEnzymes + enzyme)
    }

    fun clearEnzymes() {
        setMappedEnzymes(emptyList())
    }

    fun replaceSequence(seq: Seq) {
        reset(seq, file)
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
