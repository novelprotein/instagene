package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How an imported lab library is combined with the user's existing library. */
enum class LibraryImportMode { MERGE, REPLACE }

/**
 * Versioned, plain JSON interchange file for a lab's pattern-backed feature
 * definitions. It intentionally stores definitions rather than opaque UI state
 * so a file remains reviewable and useful from the headless engine.
 */
@Serializable
data class FeatureLibraryFile(
    val schemaVersion: Int = LabLibraryFiles.SCHEMA_VERSION,
    val name: String = "Feature library",
    val description: String = "",
    val definitions: List<FeatureDefinition> = emptyList(),
)

/**
 * Versioned, self-contained enzyme-set interchange file. Full enzyme
 * definitions make a set portable even when it includes a local enzyme that
 * is not in the built-in catalog.
 */
@Serializable
data class EnzymeSetFile(
    val schemaVersion: Int = LabLibraryFiles.SCHEMA_VERSION,
    val name: String = "Enzyme set",
    val description: String = "",
    val enzymes: List<Enzyme> = emptyList(),
)

/** Read, validate, merge, and atomically write researcher-owned lab-library files. */
object LabLibraryFiles {
    const val SCHEMA_VERSION = 1
    const val FEATURE_LIBRARY_SUFFIX = ".instagene-features.json"
    const val ENZYME_SET_SUFFIX = ".instagene-enzymes.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun featureLibrary(
        name: String,
        definitions: Collection<FeatureDefinition>,
        description: String = "",
    ): FeatureLibraryFile = FeatureLibraryFile(
        name = name.ifBlank { "Feature library" },
        description = description,
        definitions = normalizeDefinitions(definitions),
    )

    fun enzymeSet(
        name: String,
        enzymes: Collection<Enzyme>,
        description: String = "",
    ): EnzymeSetFile = EnzymeSetFile(
        name = name.ifBlank { "Enzyme set" },
        description = description,
        enzymes = normalizeEnzymes(enzymes),
    )

    fun encode(file: FeatureLibraryFile): String = json.encodeToString(validate(file))

    fun encode(file: EnzymeSetFile): String = json.encodeToString(validate(file))

    fun decodeFeatureLibrary(text: String): FeatureLibraryFile = validate(json.decodeFromString<FeatureLibraryFile>(text))

    fun decodeEnzymeSet(text: String): EnzymeSetFile = validate(json.decodeFromString<EnzymeSetFile>(text))

    fun write(file: File, library: FeatureLibraryFile) = writeAtomically(file, encode(library))

    fun write(file: File, set: EnzymeSetFile) = writeAtomically(file, encode(set))

    fun readFeatureLibrary(file: File): FeatureLibraryFile = decodeFeatureLibrary(file.readText())

    fun readEnzymeSet(file: File): EnzymeSetFile = decodeEnzymeSet(file.readText())

    fun mergeDefinitions(
        existing: Collection<FeatureDefinition>,
        imported: Collection<FeatureDefinition>,
        mode: LibraryImportMode,
    ): List<FeatureDefinition> = when (mode) {
        LibraryImportMode.REPLACE -> normalizeDefinitions(imported)
        LibraryImportMode.MERGE -> normalizeDefinitions(existing + imported)
    }

    fun mergeEnzymes(
        existing: Collection<Enzyme>,
        imported: Collection<Enzyme>,
        mode: LibraryImportMode,
    ): List<Enzyme> = when (mode) {
        LibraryImportMode.REPLACE -> normalizeEnzymes(imported)
        LibraryImportMode.MERGE -> normalizeEnzymes(existing + imported)
    }

    private fun validate(file: FeatureLibraryFile): FeatureLibraryFile {
        validateSchema(file.schemaVersion, "Feature library")
        require(file.name.isNotBlank()) { "Feature library name must not be blank" }
        val definitions = normalizeDefinitions(file.definitions)
        definitions.forEach { definition ->
            require(definition.name.isNotBlank()) { "Feature definition name must not be blank" }
            require(definition.pattern.isNotBlank()) { "Feature '${definition.name}' has a blank pattern" }
        }
        return file.copy(definitions = definitions)
    }

    private fun validate(file: EnzymeSetFile): EnzymeSetFile {
        validateSchema(file.schemaVersion, "Enzyme set")
        require(file.name.isNotBlank()) { "Enzyme set name must not be blank" }
        val enzymes = normalizeEnzymes(file.enzymes)
        enzymes.forEach { enzyme ->
            Enzymes.validateNew(enzyme.name, enzyme.site, enzyme.topCut, enzyme.bottomCut)?.let { error ->
                throw IllegalArgumentException("Invalid enzyme '${enzyme.name}': $error")
            }
        }
        return file.copy(enzymes = enzymes)
    }

    private fun validateSchema(version: Int, type: String) {
        require(version > 0) { "$type schema version must be positive" }
        require(version <= SCHEMA_VERSION) {
            "$type schema version $version is newer than the supported version $SCHEMA_VERSION"
        }
    }

    /** Name and pattern identify a feature rule while still allowing same-name variants. */
    private fun normalizeDefinitions(definitions: Collection<FeatureDefinition>): List<FeatureDefinition> = definitions
        .map { definition ->
            definition.copy(
                name = definition.name.trim(),
                pattern = definition.pattern.trim().uppercase(),
                type = definition.type.trim().ifBlank { "misc_feature" },
            )
        }
        .distinctBy { listOf(it.name.lowercase(), it.pattern.uppercase(), it.type.lowercase(), it.strand, it.exclude) }
        .sortedWith(compareBy<FeatureDefinition> { it.name.lowercase() }.thenBy { it.pattern })

    /** Enzyme names are case-insensitive keys in all built-in and GUI catalogs. */
    private fun normalizeEnzymes(enzymes: Collection<Enzyme>): List<Enzyme> {
        val seen = HashSet<String>()
        return enzymes.map { enzyme ->
            enzyme.copy(name = enzyme.name.trim(), site = enzyme.site.trim().uppercase())
        }.onEach { enzyme ->
            require(seen.add(enzyme.name.lowercase())) { "Enzyme set contains duplicate enzyme '${enzyme.name}'" }
        }.sortedBy { it.name.lowercase() }
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), ".${file.name}.tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
