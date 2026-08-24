package org.instagene.core.project

import java.io.File

/** A cheap on-disk revision marker used to decide whether a project document changed externally. */
data class ProjectFileRevision(
    val size: Long,
    val modifiedMillis: Long,
)

/** The safe action to take for one project-backed editor tab after an external change. */
enum class ProjectReloadDisposition {
    /** The file still agrees with the last observed revision. */
    UNCHANGED,
    /** A clean in-memory document may be replaced with the current disk content. */
    RELOAD_FROM_DISK,
    /** Keep unsaved in-memory edits and ask the researcher to resolve the changed-file conflict. */
    PRESERVE_LOCAL_CONFLICT,
    /** The backing file disappeared; retain the clean tab rather than closing it implicitly. */
    MISSING_ON_DISK,
    /** The backing file disappeared while local edits exist; retain those edits. */
    PRESERVE_MISSING_LOCAL,
}

data class ProjectReloadDecision(
    val disposition: ProjectReloadDisposition,
    val currentRevision: ProjectFileRevision?,
)

/**
 * Small, UI-independent policy for project reloads.
 *
 * A project may live in Git, Dropbox, OneDrive, or another synchronizer. A
 * reload must therefore never silently discard a dirty editor buffer. This
 * policy makes that conservative decision explicit while leaving parsing and
 * presentation to a front end.
 */
object ProjectReload {

    /** Captures the revision of an existing regular file, or null when it is absent/unreadable. */
    fun snapshot(file: File): ProjectFileRevision? = runCatching {
        file.takeIf { it.isFile }?.let { ProjectFileRevision(it.length(), it.lastModified()) }
    }.getOrNull()

    /** Chooses a non-destructive action from the prior revision, current file state, and editor dirty bit. */
    fun decide(
        previousRevision: ProjectFileRevision?,
        currentRevision: ProjectFileRevision?,
        dirty: Boolean,
    ): ProjectReloadDecision {
        val disposition = when {
            currentRevision == null && dirty -> ProjectReloadDisposition.PRESERVE_MISSING_LOCAL
            currentRevision == null -> ProjectReloadDisposition.MISSING_ON_DISK
            previousRevision == null || previousRevision == currentRevision -> ProjectReloadDisposition.UNCHANGED
            dirty -> ProjectReloadDisposition.PRESERVE_LOCAL_CONFLICT
            else -> ProjectReloadDisposition.RELOAD_FROM_DISK
        }
        return ProjectReloadDecision(disposition, currentRevision)
    }
}
