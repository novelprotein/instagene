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

    /** Caret position, in `0..length`. */
    var caret: Int = 0
        private set

    /** The other end of the selection; equal to [caret] when nothing is selected. */
    var anchor: Int = 0
        private set

    val selectionStart: Int get() = minOf(caret, anchor)
    val selectionEnd: Int get() = maxOf(caret, anchor)
    val hasSelection: Boolean get() = caret != anchor
    val selectionLength: Int get() = selectionEnd - selectionStart
    val selectedBases: String get() = seq.sub(selectionStart, selectionEnd)

    var mappedEnzymes: List<Enzyme> = emptyList()
        private set

    var cutSites: List<CutSite> = emptyList()
        private set

    val title: String get() = (if (isDirty) "*" else "") + seq.name

    private val listeners = ArrayList<Listener>()
    private val undoStack = ArrayDeque<Pair<String, Seq>>()
    private val redoStack = ArrayDeque<Pair<String, Seq>>()
    private val historyLimit = 100

    fun addListener(listener: Listener) {
        listeners += listener
    }

    private fun notify(reason: Reason) {
        listeners.toList().forEach { it.documentChanged(this, reason) }
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
        isDirty = true
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    /** Replaces the sequence outright (file load, format change) and clears history. */
    fun reset(next: Seq, newFile: File? = file, dirty: Boolean = false) {
        undoStack.clear()
        redoStack.clear()
        seq = next
        file = newFile
        isDirty = dirty
        caret = 0
        anchor = 0
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    fun markSaved(savedTo: File) {
        file = savedTo
        isDirty = false
        notify(Reason.SEQUENCE)
    }

    val undoLabel: String? get() = undoStack.lastOrNull()?.first
    val redoLabel: String? get() = redoStack.lastOrNull()?.first

    fun undo() {
        val (label, previous) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(label to seq)
        seq = previous
        isDirty = true
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
    }

    fun redo() {
        val (label, next) = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(label to seq)
        seq = next
        isDirty = true
        clampSelection()
        refreshCutSites()
        notify(Reason.SEQUENCE)
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

    private fun refreshCutSites() {
        cutSites = if (mappedEnzymes.isEmpty()) {
            emptyList()
        } else {
            org.instagene.core.Digest.cutSites(seq, mappedEnzymes)
        }
    }
}
