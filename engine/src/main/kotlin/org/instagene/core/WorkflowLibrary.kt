package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
enum class WorkflowEntryKind {
    PROTOCOL, PROCEDURE;
    override fun toString(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}

@Serializable
data class WorkflowLibraryStep(val title: String = "", val instructions: String = "")

@Serializable
data class WorkflowLibraryEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: WorkflowEntryKind,
    val title: String = "",
    val instructions: String = "",
    val steps: List<WorkflowLibraryStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Serializable
data class WorkflowLibrary(val schemaVersion: Int = 1, val entries: List<WorkflowLibraryEntry> = emptyList())

/** Explicit loading and optimistic revision checks protect unreadable or externally edited files. */
class WorkflowLibraryStore(private val file: File) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private var loaded = false
    private var revision: String? = null

    fun load(): WorkflowLibrary {
        loaded = false
        val text = if (file.exists()) file.readText(Charsets.UTF_8) else null
        val library = text?.let { json.decodeFromString<WorkflowLibrary>(it) } ?: WorkflowLibrary()
        validate(library)
        revision = text
        loaded = true
        return library
    }

    fun save(library: WorkflowLibrary) {
        check(loaded) { "The workflow library must load successfully before it can be saved." }
        validate(library)
        val current = if (file.exists()) file.readText(Charsets.UTF_8) else null
        check(current == revision) { "The workflow library changed on disk. Copy your unsaved text, then reopen the library." }
        val text = json.encodeToString(WorkflowLibrary.serializer(), library)
        val parent = file.absoluteFile.parentFile.toPath()
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".workflow-library-", ".tmp")
        try {
            Files.writeString(temporary, text, Charsets.UTF_8)
            // If atomic replacement is unsupported, fail without damaging the saved library.
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            revision = text
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validate(library: WorkflowLibrary) {
        require(library.schemaVersion == 1) { "Unsupported workflow library version: ${library.schemaVersion}" }
        require(library.entries.map { it.id }.distinct().size == library.entries.size) { "Duplicate workflow IDs." }
        require(library.entries.all { it.id.isNotBlank() && it.title.isNotBlank() }) { "Each entry needs an ID and title." }
        require(library.entries.all { it.kind == WorkflowEntryKind.PROTOCOL || it.steps.isEmpty() }) {
            "Only protocols can contain steps."
        }
    }
}
