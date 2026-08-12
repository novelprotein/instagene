package org.instagene.app.gui.doc

import org.instagene.core.project.EditKind
import java.io.File

/**
 * One open plain-text notes document. Edits go through [mutate] so they are
 * undoable, mirroring [org.instagene.app.gui.ui.SeqDocument]'s history stack.
 * The dirty flag indicates whether the text differs from the last saved or loaded state.
 */
class TextDocument(initial: String = "", override var file: File? = null) : Doc {

    var text: String = initial
        private set

    override var isDirty: Boolean = false
        private set

    /** The saved or loaded text against which [isDirty] is calculated. */
    private var savedText: String = initial

    /** The name shown on the document tab and in the window title. */
    override val displayName: String get() = file?.name ?: "Untitled"

    private val listeners = ArrayList<Doc.Listener>()
    private val editListeners = ArrayList<Doc.EditListener>()
    private val undoStack = ArrayDeque<Pair<String, String>>()
    private val redoStack = ArrayDeque<Pair<String, String>>()
    private val historyLimit = 100

    override fun addDocListener(listener: Doc.Listener) {
        listeners += listener
    }

    override fun removeDocListener(listener: Doc.Listener) {
        listeners.remove(listener)
    }

    override fun addEditListener(listener: Doc.EditListener) {
        editListeners += listener
    }

    override fun removeEditListener(listener: Doc.EditListener) {
        editListeners.remove(listener)
    }

    private fun fireChanged() {
        listeners.toList().forEach { it.docChanged(this) }
    }

    private fun fireEdit(kind: EditKind, label: String?) {
        editListeners.toList().forEach { it.docEdited(this, kind, label, null) }
    }

    /** Applies [transform], recording an undo entry labelled [label]. */
    fun mutate(label: String, transform: (String) -> String) {
        val next = transform(text)
        if (next == text) return
        undoStack.addLast(label to text)
        while (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
        text = next
        refreshDirty()
        fireChanged()
        fireEdit(EditKind.EDIT, label)
    }

    /** Replaces the whole buffer, recording an undo entry labelled [label]. */
    fun setText(next: String, label: String = "edit") {
        mutate(label) { next }
    }

    /** Replaces the buffer outright (file load) and clears history. */
    fun reset(next: String, newFile: File? = file, dirty: Boolean = false) {
        undoStack.clear()
        redoStack.clear()
        text = next
        savedText = next
        file = newFile
        isDirty = dirty
        fireChanged()
    }

    override fun markSaved(savedTo: File) {
        val savedAs = file != null && file != savedTo
        file = savedTo
        savedText = text
        isDirty = false
        fireChanged()
        fireEdit(if (savedAs) EditKind.SAVE_AS else EditKind.SAVE, null)
    }

    /** Reverts the most recent [mutate], restoring the previous buffer. */
    fun undo() {
        val (label, previous) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(label to text)
        text = previous
        refreshDirty()
        fireChanged()
        fireEdit(EditKind.UNDO, label)
    }

    /** Re-applies the change most recently reverted by [undo]. */
    fun redo() {
        val (label, next) = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(label to text)
        text = next
        refreshDirty()
        fireChanged()
        fireEdit(EditKind.REDO, label)
    }

    /** True when an [undo] would do something. */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /** True when a [redo] would do something. */
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** The label of the entry the next [undo] reverts, or null when there is none. */
    fun undoLabel(): String? = undoStack.lastOrNull()?.first

    /** The label of the entry the next [redo] re-applies, or null when there is none. */
    fun redoLabel(): String? = redoStack.lastOrNull()?.first

    /** Dirty means "differs from the last saved or loaded state", not "edited at all". */
    private fun refreshDirty() {
        isDirty = text != savedText
    }
}
