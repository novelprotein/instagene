package org.instagene.core.project

import kotlinx.serialization.Serializable

/**
 * The per-project editor layout that survives a restart: which tool tab was
 * selected and where the project-tree split sits.
 *
 * Field-level defaults make every value optional, so an empty or partial
 * layout block deserializes cleanly.
 */
@Serializable
data class ProjectLayout(
    /** The selected tool tab in the editor area (Info/Map/Sequence/...). */
    val activeToolTab: Int = 0,
    /** The fraction of the window width taken by the project tree (0..1). */
    val treeSplitRatio: Double = 0.25,
)

/**
 * Everything a project remembers between launches, stored as JSON at
 * `<project>/.instagene/project.json`.
 *
 * [openDocs] holds the open documents as forward-slash paths relative to the
 * project root, in tab order. [activeDoc] is the path of the selected tab (may
 * be null when no document is active). Only file-backed documents are recorded.
 * Untitled documents cannot be reopened, so the user is prompted to save them on close.
 */
@Serializable
data class ProjectManifest(
    /** Bumped when the on-disk schema changes; unknown versions still load. */
    val schemaVersion: Int = 1,
    val openDocs: List<String> = emptyList(),
    val activeDoc: String? = null,
    val layout: ProjectLayout = ProjectLayout(),
)
