package org.instagene.app.gui

import org.instagene.core.project.EditEntry
import org.instagene.core.project.EditHistory
import org.instagene.core.project.EditKind
import org.instagene.core.project.SeqProject

/**
 * Records the edit history of the current project and persists it write-through
 * to `<project>/.instagene/history.json`.
 *
 * Content edits are captured from every open document through [Doc.EditListener]
 * (see [bind]): each applied change becomes one [EditEntry]. Because the plain
 * text editor pushes one edit per keystroke, consecutive same-document typing
 * runs are coalesced into a single entry that is only written out once the run
 * is closed by the next distinct event or by [flush]; every other entry is
 * persisted immediately, so sequence edits survive a crash.
 *
 * The recorder is project-scoped: nothing is recorded until [setProject]
 * attaches a project, and switching projects replaces the in-memory log with
 * the new project's persisted history.
 */
class EditRecorder(
    /** Consecutive text edits closer together than this are merged into one entry. */
    private val coalesceWindowMs: Long = 2_000,
    /** The maximum number of entries kept, oldest dropped first. */
    private val historyLimit: Int = 1_000,
    /** The clock, injectable for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Receives a callback whenever the recorded history changes. */
    fun interface Listener {
        fun historyChanged()
    }

    private val listeners = ArrayList<Listener>()
    private val subscribed = HashSet<Doc>()

    private var project: SeqProject? = null

    /** The recorded entries, oldest first; a new project resets this from disk. */
    var entries: List<EditEntry> = emptyList()
        private set

    private val editListener = Doc.EditListener { doc, kind, label, detail ->
        onDocEdited(doc, kind, label, detail)
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.historyChanged() }
    }

    /** Attaches the recorder to project [p], loading its history and recording a PROJECT event. */
    fun setProject(p: SeqProject?, created: Boolean) {
        project = p
        entries = p?.loadHistory()?.entries?.takeLast(historyLimit) ?: emptyList()
        if (p != null) {
            append(EditEntry(now(), EditKind.PROJECT, label = if (created) "Project created" else "Project opened", detail = p.root.name))
        } else {
            notifyChanged()
        }
    }

    /** Starts recording edits for [doc]; a no-op when it is already subscribed. */
    fun bind(doc: Doc) {
        if (subscribed.add(doc)) doc.addEditListener(editListener)
    }

    /** Stops recording edits for [doc]; a no-op when it was never subscribed. */
    fun unbind(doc: Doc) {
        if (subscribed.remove(doc)) doc.removeEditListener(editListener)
    }

    /** Records a document being opened (with a file) or created (without) in a tab. */
    fun recordDocumentOpened(doc: Doc) {
        if (project == null) return
        val kind = if (doc.file == null) EditKind.NEW else EditKind.OPEN
        val label = if (doc.file == null) "New document" else "Opened"
        append(EditEntry(now(), kind, documentLabel(doc), label, null))
    }

    /** Records a document tab being closed. */
    fun recordDocumentClosed(doc: Doc) {
        if (project == null) return
        append(EditEntry(now(), EditKind.CLOSE, documentLabel(doc), "Closed", null))
    }

    /** Persists any pending (coalesced) entries; called alongside the project manifest save. */
    fun flush() {
        val p = project ?: return
        p.saveHistory(EditHistory(entries = entries))
    }

    // ------------------------------------------------------------- capture

    private fun onDocEdited(doc: Doc, kind: EditKind, label: String?, detail: String?) {
        if (project == null) return
        when (kind) {
            EditKind.SAVE, EditKind.SAVE_AS ->
                append(EditEntry(now(), kind, documentLabel(doc), if (kind == EditKind.SAVE) "Saved" else "Saved as", doc.file?.name))
            EditKind.EDIT -> recordEdit(doc, label ?: "edit", detail)
            EditKind.UNDO, EditKind.REDO -> {
                val prefix = if (kind == EditKind.UNDO) "Undo " else "Redo "
                append(EditEntry(now(), kind, documentLabel(doc), prefix + (label ?: "edit"), detail))
            }
            else -> {}
        }
    }

    /** Records one content edit, coalescing a text typing run into a single entry. */
    private fun recordEdit(doc: Doc, label: String, detail: String?) {
        val docLabel = documentLabel(doc)
        val time = now()
        if (doc is TextDocument && label == "edit") {
            val last = entries.lastOrNull()
            if (last != null &&
                last.kind == EditKind.EDIT &&
                last.doc == docLabel &&
                last.label == "edit" &&
                time - last.timestamp < coalesceWindowMs
            ) {
                entries = entries.dropLast(1) + last.copy(timestamp = time, detail = "${doc.text.length} chars")
                return // coalesced: held in memory until the run closes with an append or flush
            }
            append(EditEntry(time, EditKind.EDIT, docLabel, "edit", "${doc.text.length} chars"))
            return
        }
        append(EditEntry(time, EditKind.EDIT, docLabel, label, detail))
    }

    /** Appends [entry], trims to [historyLimit] and persists write-through. */
    private fun append(entry: EditEntry) {
        val next = entries + entry
        entries = if (next.size > historyLimit) next.takeLast(historyLimit) else next
        flush()
        notifyChanged()
    }

    /** How [doc] appears in the log: its project-relative path when inside the project, else its display name. */
    private fun documentLabel(doc: Doc): String {
        val file = doc.file ?: return doc.displayName
        return project?.relativePath(file) ?: doc.displayName
    }
}
