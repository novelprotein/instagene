package org.instagene.app.gui

import java.io.File

/**
 * The open documents of one window, plus the one that is active.
 *
 * Every view that must follow the current document (the tool panels, the
 * document tab strip, the status bar and the window title) listens to this hub:
 * activating a tab fires [Reason.ACTIVE_CHANGED] and the listeners re-bind to
 * the new document, so a single set of panels serves all tabs.
 */
class DocumentHub<T : Doc> {

    /** What changed: the open set itself, or just which document is active. */
    enum class Reason { DOCS_CHANGED, ACTIVE_CHANGED }

    /** Receives a callback whenever the open set or the active tab changes. */
    fun interface Listener {
        fun documentsChanged(hub: DocumentHub<*>, reason: Reason)
    }

    private val documents = ArrayList<T>()
    private val listeners = ArrayList<Listener>()

    /** The currently active document, or null when none is open. */
    var active: T? = null
        private set

    /** A snapshot of the open documents in tab order. */
    val openDocuments: List<T> get() = documents.toList()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    private fun notify(reason: Reason) {
        listeners.toList().forEach { it.documentsChanged(this, reason) }
    }

    /** The tab-strip index of [doc], or -1 when it is not open. */
    fun indexOf(doc: T): Int = documents.indexOf(doc)

    /** True when [doc] is open. */
    fun contains(doc: T): Boolean = doc in documents

    /** The open document backed by [file], or null. */
    fun documentFor(file: File): T? {
        val target = file.canonicalFile
        return documents.firstOrNull { it.file?.canonicalFile == target }
    }

    /** Adds [doc] (if new) and makes it active. */
    fun add(doc: T): T {
        if (doc !in documents) {
            documents += doc
            notify(Reason.DOCS_CHANGED)
        }
        activate(doc)
        return doc
    }

    /** Makes [doc] active; it must already be open. */
    fun activate(doc: T) {
        if (doc in documents && doc !== active) {
            active = doc
            notify(Reason.ACTIVE_CHANGED)
        }
    }

    /**
     * Removes [doc]; when it was active, the last remaining document becomes
     * active instead. Returns false when [doc] was not open.
     */
    fun remove(doc: T): Boolean {
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
