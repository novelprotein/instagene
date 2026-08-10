package org.instagene.app.gui

import java.io.File

/**
 * The open documents of one window, plus the one that is active.
 *
 * Every view that must follow the current sequence (the tool panels, the
 * document tab strip, the status bar and the window title) listens to this hub:
 * activating a tab fires [Reason.ACTIVE_CHANGED] and the listeners re-bind to
 * the new document, so a single set of panels serves all tabs.
 */
class DocumentHub {

    /** What changed: the open set itself, or just which document is active. */
    enum class Reason { DOCS_CHANGED, ACTIVE_CHANGED }

    /** Receives a callback whenever the open set or the active tab changes. */
    fun interface Listener {
        fun documentsChanged(hub: DocumentHub, reason: Reason)
    }

    private val documents = ArrayList<SeqDocument>()
    private val listeners = ArrayList<Listener>()

    /** The currently active document, or null when none is open. */
    var active: SeqDocument? = null
        private set

    /** A snapshot of the open documents in tab order. */
    val openDocuments: List<SeqDocument> get() = documents.toList()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    private fun notify(reason: Reason) {
        listeners.toList().forEach { it.documentsChanged(this, reason) }
    }

    /** The tab-strip index of [doc], or -1 when it is not open. */
    fun indexOf(doc: SeqDocument): Int = documents.indexOf(doc)

    /** True when [doc] is open. */
    fun contains(doc: SeqDocument): Boolean = doc in documents

    /** The open document backed by [file], or null. */
    fun documentFor(file: File): SeqDocument? {
        val target = file.canonicalFile
        return documents.firstOrNull { it.file?.canonicalFile == target }
    }

    /** Adds [doc] (if new) and makes it active. */
    fun add(doc: SeqDocument): SeqDocument {
        if (doc !in documents) {
            documents += doc
            notify(Reason.DOCS_CHANGED)
        }
        activate(doc)
        return doc
    }

    /** Makes [doc] active; it must already be open. */
    fun activate(doc: SeqDocument) {
        if (doc in documents && doc !== active) {
            active = doc
            notify(Reason.ACTIVE_CHANGED)
        }
    }

    /**
     * Removes [doc]; when it was active, the last remaining document becomes
     * active instead. Returns false when [doc] was not open.
     */
    fun remove(doc: SeqDocument): Boolean {
        if (doc !in documents) return false
        documents.remove(doc)
        if (doc === active) {
            active = documents.lastOrNull()
            notify(Reason.ACTIVE_CHANGED)
        }
        notify(Reason.DOCS_CHANGED)
        return true
    }
}
