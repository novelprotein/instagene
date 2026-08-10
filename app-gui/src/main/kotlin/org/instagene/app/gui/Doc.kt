package org.instagene.app.gui

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

    /** Records that the document was saved to [savedTo]: the dirty flag clears and the undo baseline moves up. */
    fun markSaved(savedTo: File)

    /** Receives a callback whenever the document changes. */
    fun interface Listener {
        fun docChanged(doc: Doc)
    }
}
