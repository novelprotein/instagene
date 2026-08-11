package org.instagene.core.project

import org.instagene.core.Seq
import kotlinx.serialization.Serializable

/** What kind of event an [EditEntry] records. */
@Serializable
enum class EditKind {
    /** A content edit applied to a document (sequence or text). */
    EDIT,
    /** An edit reverted through Undo (never persisted; tells the recorder to drop the task). */
    UNDO,
    /** An edit re-applied through Redo (never persisted; tells the recorder to re-add the task). */
    REDO,
    /** A new, unsaved document created in a tab. */
    NEW,
    /** A file opened into a tab. */
    OPEN,
    /** A tab closed. */
    CLOSE,
    /** A document saved to the file it was already associated with. */
    SAVE,
    /** A document saved under a new path (Save As). */
    SAVE_AS,
    /** A project-level event: the project was created or opened. */
    PROJECT,
}

/**
 * One event in an edit history.
 *
 * [doc] is the document the event concerns, as the project-relative path when
 * the document lives under the project root and as the file/display name
 * otherwise (project-level events leave it null). [label] is a short
 * human-readable summary of the change ("rename", "replace 12 bases") and
 * [detail] an optional secondary line ("4361 -> 4349 bp", "pMini.gb").
 *
 * Content edits carry a [snapshotSeq] (sequence documents) or [snapshotText]
 * (text documents): the full document state right after the edit, kept only
 * while it is small enough to make "revert to this snapshot" possible.
 */
@Serializable
data class EditEntry(
    /** Epoch milliseconds when the change was applied. */
    val timestamp: Long,
    val kind: EditKind,
    val doc: String? = null,
    val label: String = "",
    val detail: String? = null,
    /** The sequence right after this edit, when it was small enough to keep; null otherwise. */
    val snapshotSeq: Seq? = null,
    /** The text right after this edit, when it was small enough to keep; null otherwise. */
    val snapshotText: String? = null,
) {
    /** True when [snapshotSeq] or [snapshotText] captures a state a document can be reverted to. */
    val hasSnapshot: Boolean get() = snapshotSeq != null || snapshotText != null
}

/**
 * An edit history, stored as JSON either at `<project>/.instagene/history.json`
 * (project mode) or in a sidecar file next to the file being edited
 * (single-file mode). [entries] is kept in oldest-first order, capped by
 * whoever appends; [EditKind] values and field defaults keep older files
 * loadable.
 */
@Serializable
data class EditHistory(
    /** Bumped when the on-disk schema changes; unknown versions still load. */
    val schemaVersion: Int = 1,
    val entries: List<EditEntry> = emptyList(),
)
