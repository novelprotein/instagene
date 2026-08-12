package org.instagene.core.project

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A multi-document project: a plain folder on disk that InstaGene manages.
 *
 * A project is identified by the presence of `<root>/.instagene/project.json`,
 * the manifest that records the open documents, the active tab and the editor
 * layout (see [ProjectManifest]). Everything else in the folder is ordinary
 * files (FASTA, GenBank, text, images, ...) that the front-ends can open.
 *
 * The manifest paths are always forward-slash and relative to [root], so a
 * project folder can be moved on disk without breaking it. Paths are resolved
 * through [resolvePath], which refuses to escape [root].
 */
class SeqProject private constructor(
    val root: File,
) {

    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** The manifest of this project; mutate it through the helpers below, then call [save]. */
    var manifest: ProjectManifest = loadManifest()
        private set

    private fun loadManifest(): ProjectManifest {
        val file = manifestFile(root)
        if (!file.isFile) return ProjectManifest()
        return runCatching { json.decodeFromString<ProjectManifest>(file.readText()) }
            .getOrElse { ProjectManifest() }
    }

    // -------------------------------------------------------------- accessors

    /** The resolved, existing files named in [ProjectManifest.openDocs], in tab order. */
    fun openDocuments(): List<File> =
        manifest.openDocs.mapNotNull { rel -> resolvePath(rel)?.takeIf { it.isFile } }

    /** The resolved active document, or null when there is none or it is missing. */
    fun activeDocument(): File? =
        manifest.activeDoc?.let { rel -> resolvePath(rel)?.takeIf { it.isFile } }

    // -------------------------------------------------------------- mutation

    /** Opens [file] (which must live under [root]) in tab order, without duplicating it. */
    fun addDocument(file: File) {
        val rel = requireRelative(file)
        if (rel in manifest.openDocs) return
        manifest = manifest.copy(openDocs = manifest.openDocs + rel)
    }

    /**
     * Replaces the open set with [files] in the given order; files outside the
     * project are ignored. The active document is kept when it is still open,
     * otherwise cleared.
     */
    fun setOpenDocuments(files: List<File>) {
        val rels = files.mapNotNull { relativePath(it) }.distinct()
        manifest = manifest.copy(openDocs = rels)
        if (manifest.activeDoc !in rels) {
            manifest = manifest.copy(activeDoc = null)
        }
    }

    /** Removes [file] from the open set; does not touch the file on disk. */
    fun removeDocument(file: File) {
        val rel = requireRelative(file)
        manifest = manifest.copy(openDocs = manifest.openDocs.filter { it != rel })
        if (manifest.activeDoc == rel) {
            manifest = manifest.copy(activeDoc = null)
        }
    }

    /** Makes [file] (or null for none) the active document, adding it if needed. */
    fun setActive(file: File?) {
        val rel = if (file == null) null else requireRelative(file)
        if (rel != null && rel !in manifest.openDocs) {
            manifest = manifest.copy(openDocs = manifest.openDocs + rel)
        }
        manifest = manifest.copy(activeDoc = rel)
    }

    /** Replaces the persisted layout block. */
    fun setLayout(layout: ProjectLayout) {
        manifest = manifest.copy(layout = layout)
    }

    // ---------------------------------------------------------------- paths

    /** The manifest file for [root], whether or not it exists yet. */
    fun manifestFile(): File = manifestFile(root)

    /**
     * Resolves [rel] against [root]. Returns null when [rel] escapes the
     * project (absolute or containing `..`), preventing a hostile manifest from
     * pointing to files outside the folder.
     */
    fun resolvePath(rel: String): File? {
        if (rel.isBlank()) return null
        val candidate = File(root, rel).normalize().canonicalFile
        val base = root.canonicalFile
        val path = candidate.path
        return if (path == base.path || path.startsWith(base.path + File.separator)) candidate else null
    }

    /** The forward-slash path of [file] relative to [root], or null when it is outside the project. */
    fun relativePath(file: File): String? {
        val base = root.canonicalFile
        val abs = file.canonicalFile
        val path = abs.path
        return if (path == base.path || path.startsWith(base.path + File.separator)) {
            base.toPath().relativize(abs.toPath()).toString().replace(File.separatorChar, '/')
        } else {
            null
        }
    }

    /** [relativePath] with a hard error, for files that must live in the project. */
    private fun requireRelative(file: File): String {
        val rel = relativePath(file) ?: throw IllegalArgumentException("'$file' is outside project '${root.path}'")
        return rel
    }

    // ------------------------------------------------------------- persistence

    /** Writes the manifest atomically (temp file + move), creating `.instagene/` as needed. */
    fun save() {
        atomicWrite(manifestFile(root), json.encodeToString(manifest))
    }

    /** The edit-history file for this project, whether or not it exists yet. */
    fun historyFile(): File = historyFile(root)

    /** Loads the persisted edit history, falling back to an empty one when it is missing or corrupt. */
    fun loadHistory(): EditHistory {
        val file = historyFile(root)
        if (!file.isFile) return EditHistory()
        return runCatching { json.decodeFromString<EditHistory>(file.readText()) }
            .getOrElse { EditHistory() }
    }

    /** Writes [history] atomically, creating `.instagene/` as needed. */
    fun saveHistory(history: EditHistory) {
        atomicWrite(historyFile(root), json.encodeToString(history))
    }

    /** Writes [text] to [file] via a temp file + atomic move, creating the parent directory as needed. */
    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MANIFEST_DIR = ".instagene"
        const val MANIFEST_NAME = "project.json"
        const val HISTORY_NAME = "history.json"

        /** The manifest file for [root], whether or not it exists yet. */
        fun manifestFile(root: File): File = File(File(root, MANIFEST_DIR), MANIFEST_NAME)

        /** The edit-history file for [root], whether or not it exists yet. */
        fun historyFile(root: File): File = File(File(root, MANIFEST_DIR), HISTORY_NAME)

        /** True when [dir] holds a `.instagene/project.json`. */
        fun isProjectRoot(dir: File): Boolean = manifestFile(dir).isFile

        /**
         * Opens the project rooted at [root]. When [root] is already a project
         * its manifest is loaded; a missing or corrupt manifest falls back to a
         * fresh, empty one. Nothing is written until [SeqProject.save] is called.
         */
        fun open(root: File): SeqProject = SeqProject(root)

        /** Creates an in-memory project for [root]; files are written only when [SeqProject.save] is called. */
        fun create(root: File): SeqProject = SeqProject(root)
    }
}
