package org.instagene.core.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.instagene.core.FeatureDefinition
import org.instagene.core.FeatureLibrary
import org.instagene.core.MoleculeProperties
import org.instagene.core.Seq
import org.instagene.core.Topology
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import java.io.File

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

    fun load(): CollectionDocument = if (!file.isFile) CollectionDocument() else
        runCatching { json.decodeFromString<CollectionDocument>(file.readText()) }.getOrDefault(CollectionDocument())

    fun save(document: CollectionDocument) {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(document))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
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

/** Safe batch transformations over explicit files; source files are never overwritten. */
object BatchOperations {
    fun convert(files: List<File>, outputDirectory: File, format: SeqFormat): BatchResult = process(files) { file ->
        val seq = SeqIO.read(file)
        outputDirectory.mkdirs()
        val extension = format.extensions.first()
        SeqIO.write(File(outputDirectory, "${file.nameWithoutExtension}.$extension"), seq, format)
    }

    fun annotate(files: List<File>, outputDirectory: File, definitions: List<FeatureDefinition>): BatchResult = process(files) { file ->
        val seq = FeatureLibrary.annotate(SeqIO.read(file), definitions)
        outputDirectory.mkdirs()
        SeqIO.write(File(outputDirectory, "${file.nameWithoutExtension}.gb"), seq, SeqFormat.GENBANK)
    }

    fun transformProperties(
        files: List<File>,
        outputDirectory: File,
        topology: Topology? = null,
        molecule: MoleculeProperties? = null,
    ): BatchResult = process(files) { file ->
        val original = SeqIO.read(file)
        val seq: Seq = original.copy(topology = topology ?: original.topology, molecule = molecule ?: original.molecule)
        outputDirectory.mkdirs()
        SeqIO.write(File(outputDirectory, "${file.nameWithoutExtension}.gb"), seq, SeqFormat.GENBANK)
    }

    private fun process(files: List<File>, action: (File) -> Unit): BatchResult {
        val failed = linkedMapOf<File, String>()
        var processed = 0
        files.distinctBy { it.absoluteFile.normalize().path }.forEach { file ->
            runCatching { action(file) }
                .onSuccess { processed++ }
                .onFailure { failed[file] = it.message ?: it::class.simpleName.orEmpty() }
        }
        return BatchResult(processed, failed)
    }
}
