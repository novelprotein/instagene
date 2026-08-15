package org.instagene.core.project

import org.instagene.core.io.FileSniffer
import org.instagene.core.io.FileType
import org.instagene.core.io.SeqIO
import java.io.File

enum class ProjectSearchField { NAME, SEQUENCE, FEATURE, PRIMER, METADATA }

data class ProjectSearchHit(
    val file: File,
    val field: ProjectSearchField,
    val summary: String,
    val position: Int? = null,
)

/** Content-aware search over sequence files in a project folder. */
object ProjectSearch {
    fun search(root: File, query: String, fields: Set<ProjectSearchField> = ProjectSearchField.entries.toSet()): List<ProjectSearchHit> {
        require(root.isDirectory) { "Project root is not a directory" }
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<ProjectSearchHit>()
        root.walkTopDown().filter { it.isFile && !it.toPath().startsWith(File(root, ".instagene").toPath()) }.forEach { file ->
            if (ProjectSearchField.NAME in fields && file.name.contains(needle, true)) {
                hits += ProjectSearchHit(file, ProjectSearchField.NAME, file.name)
            }
            if (FileSniffer.typeOf(file) != FileType.SEQUENCE) return@forEach
            val seq = runCatching { SeqIO.read(file) }.getOrNull() ?: return@forEach
            if (ProjectSearchField.SEQUENCE in fields) {
                val at = seq.bases.indexOf(needle, ignoreCase = true)
                if (at >= 0) hits += ProjectSearchHit(file, ProjectSearchField.SEQUENCE, "${at + 1}..${at + needle.length}", at)
            }
            if (ProjectSearchField.FEATURE in fields) seq.features.filter { it.name.contains(needle, true) || it.type.contains(needle, true) || it.notes.contains(needle, true) }
                .forEach { hits += ProjectSearchHit(file, ProjectSearchField.FEATURE, "${it.name} (${it.displayRange()})", it.start) }
            if (ProjectSearchField.PRIMER in fields) seq.primers.filter { it.name.contains(needle, true) || it.fullSequence.contains(needle, true) }
                .forEach { hits += ProjectSearchHit(file, ProjectSearchField.PRIMER, "${it.name} (${it.bindingStart + 1}..${it.bindingEnd})", it.bindingStart) }
            if (ProjectSearchField.METADATA in fields && (seq.description.contains(needle, true) || seq.metadata.any { it.key.contains(needle, true) || it.value.contains(needle, true) })) {
                hits += ProjectSearchHit(file, ProjectSearchField.METADATA, seq.description.ifBlank { "Metadata match" })
            }
        }
        return hits.distinctBy { Triple(it.file.absolutePath, it.field, it.summary) }
    }
}
