package org.instagene.core.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.instagene.core.feature.FeatureDefinition
import org.instagene.core.feature.FeatureLibrary
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import org.instagene.core.sequence.MoleculeProperties
import org.instagene.core.sequence.Seq
import org.instagene.core.sequence.Topology
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
data class CollectionArea(
    val name: String,
    val files: List<String> = emptyList(),
)

@Serializable
data class SequenceCollection(
    val name: String,
    val areas: List<CollectionArea> = listOf(CollectionArea("Sequences")),
    val mainArea: String = areas.firstOrNull()?.name ?: "Sequences",
)

@Serializable
data class CollectionDocument(
    val schemaVersion: Int = 1,
    val collections: List<SequenceCollection> = emptyList(),
)

/** Project-local collections with named areas and portable relative file references. */
class CollectionStore(private val project: SeqProject) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(File(project.root, SeqProject.MANIFEST_DIR), "collections.json")
    private var loaded = false
    private var revision: String? = null

    fun load(): CollectionDocument {
        loaded = false
        val text = if (file.exists()) file.readText() else null
        val document = text?.let { json.decodeFromString<CollectionDocument>(it) } ?: CollectionDocument()
        require(document.schemaVersion == 1) { "Unsupported collections version: ${document.schemaVersion}" }
        revision = text
        loaded = true
        return document
    }

    fun save(document: CollectionDocument) {
        check(loaded) { "Collections must load successfully before they can be saved." }
        require(document.schemaVersion == 1) { "Unsupported collections version: ${document.schemaVersion}" }
        val current = if (file.exists()) file.readText() else null
        check(current == revision) { "Collections changed on disk. Reopen the collections dialog before saving." }
        val text = json.encodeToString(document)
        Files.createDirectories(file.parentFile.toPath())
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".collections-", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            revision = text
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun addFile(document: CollectionDocument, collectionName: String, areaName: String, sequenceFile: File): CollectionDocument {
        val relative = project.relativePath(sequenceFile) ?: throw IllegalArgumentException("File is outside the project")
        val collections = document.collections.toMutableList()
        val collectionIndex = collections.indexOfFirst { it.name.equals(collectionName, true) }
        val collection = collections.getOrNull(collectionIndex) ?: SequenceCollection(collectionName)
        val areas = collection.areas.toMutableList()
        val areaIndex = areas.indexOfFirst { it.name.equals(areaName, true) }
        val area = areas.getOrNull(areaIndex) ?: CollectionArea(areaName)
        val updatedArea = area.copy(files = (area.files + relative).distinct())
        if (areaIndex >= 0) areas[areaIndex] = updatedArea else areas += updatedArea
        val updatedCollection = collection.copy(areas = areas)
        if (collectionIndex >= 0) collections[collectionIndex] = updatedCollection else collections += updatedCollection
        return document.copy(collections = collections)
    }

    fun removeFile(document: CollectionDocument, collectionName: String, areaName: String, sequenceFile: File): CollectionDocument {
        val relative = project.relativePath(sequenceFile) ?: throw IllegalArgumentException("File is outside the project")
        val collections = document.collections.map { collection ->
            if (!collection.name.equals(collectionName, true)) return@map collection
            collection.copy(areas = collection.areas.map { area ->
                if (!area.name.equals(areaName, true)) area else area.copy(files = area.files.filterNot { it == relative })
            })
        }
        return document.copy(collections = collections)
    }
}

data class BatchResult(val processed: Int, val failed: Map<File, String>)

/** Batch transformations that reject conflicting or existing outputs before writing any files. */
object BatchOperations {
    fun convert(files: List<File>, outputDirectory: File, format: SeqFormat): BatchResult =
        process(files, outputDirectory, format.extensions.first(), format) { file ->
            SeqIO.read(file)
        }

    fun annotate(files: List<File>, outputDirectory: File, definitions: List<FeatureDefinition>): BatchResult =
        process(files, outputDirectory, "gb", SeqFormat.GENBANK) { file ->
            FeatureLibrary.annotate(SeqIO.read(file), definitions)
        }

    fun transformProperties(
        files: List<File>,
        outputDirectory: File,
        topology: Topology? = null,
        molecule: MoleculeProperties? = null,
    ): BatchResult = process(files, outputDirectory, "gb", SeqFormat.GENBANK) { file ->
        val original = SeqIO.read(file)
        original.copy(topology = topology ?: original.topology, molecule = molecule ?: original.molecule)
    }

    private fun process(
        files: List<File>,
        outputDirectory: File,
        extension: String,
        format: SeqFormat,
        transform: (File) -> Seq,
    ): BatchResult {
        val inputs = files.distinctBy { it.canonicalFile }
        val destinations = inputs.associateWith { File(outputDirectory, "${it.nameWithoutExtension}.$extension").canonicalFile }
        val duplicateDestinations = destinations.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val sourcePaths = inputs.map { it.canonicalFile }.toSet()
        val failed = linkedMapOf<File, String>()
        for ((source, destination) in destinations) {
            val error = when {
                destination in sourcePaths -> "Output would overwrite a source file: ${destination.path}"
                destination in duplicateDestinations -> "Multiple inputs have the same output path: ${destination.path}"
                destination.exists() -> "Output already exists: ${destination.path}"
                else -> null
            }
            if (error != null) failed[source] = error
        }
        // Validate the entire set before creating any output, including outputs that
        // would replace another input that has not yet been processed.
        if (failed.isNotEmpty()) {
            inputs.forEach { failed.putIfAbsent(it, "Batch aborted because another output path conflicts.") }
            return BatchResult(0, failed)
        }
        var processed = 0
        destinations.forEach { (file, destination) ->
            runCatching {
                val text = SeqIO.write(transform(file), format)
                Files.createDirectories(destination.parentFile.toPath())
                // CREATE_NEW also protects an output created after preflight.
                Files.writeString(destination.toPath(), text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            }
                .onSuccess { processed++ }
                .onFailure { failed[file] = it.message ?: it::class.simpleName.orEmpty() }
        }
        return BatchResult(processed, failed)
    }
}
