package org.instagene.app.gui

import org.instagene.core.project.EditKind
import java.io.File

/**
 * One open document, whatever its kind: a sequence ([SeqDocument]) or a plain
 * text/notes file ([TextDocument]). The tab strip, the window title and the
 * project manifest only need this shared surface; kind-specific views cast to
 * the concrete subtype.
 */
interface Doc {

    /** The file this document is saved under, or null when it is not yet on disk. */
    var file: File?

    /** True when the in-memory content differs from the last save/load. */
    val isDirty: Boolean

    /** The name shown on the document tab and in the window title. */
    val displayName: String

    /** Registers [listener] to be notified of every change. */
    fun addDocListener(listener: Listener)

    /** Unregisters [listener]; a no-op when it was never registered. */
    fun removeDocListener(listener: Listener)

    /**
     * Registers [listener] to be notified of every applied change (edit, undo,
     * redo, save) together with what changed. Content edits already carry a
     * short label; undo and redo report the label of the entry they
     * reverted/re-applied. Saves leave the label null and are identified by
     * their kind.
     */
    fun addEditListener(listener: EditListener)

    /** Unregisters [listener]; a no-op when it was never registered. */
    fun removeEditListener(listener: EditListener)

    /** Records that the document was saved to [savedTo]: the dirty flag clears and the undo baseline moves up. */
    fun markSaved(savedTo: File)

    /** Receives a callback whenever the document changes. */
    fun interface Listener {
        fun docChanged(doc: Doc)
    }

    /** Receives a callback with the change kind, its label and a short detail whenever the document's content changes. */
    fun interface EditListener {
        fun docEdited(doc: Doc, kind: EditKind, label: String?, detail: String?)
    }
}
