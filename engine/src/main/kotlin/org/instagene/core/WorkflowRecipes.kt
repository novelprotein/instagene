package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** Immutable identity of a recipe input, independent of its current file path. */
@Serializable
data class RecipeInput(
    val name: String,
    val cdseguid: String,
    /** Input topology at capture time; absent in legacy recipes. */
    val topology: Topology? = null,
)

/** Stable kinds used by the schema-v2 operation payload. */
@Serializable
enum class RecipeOperationType {
    RESTRICTION_CLONING,
    OVERLAP_ASSEMBLY,
    GOLDEN_GATE,
    GATEWAY,
    TERMINAL_CLONING,
    PCR_RESTRICTION_CLONING,
    HOMOLOGY_RECOMBINATION,
    GENERIC,
}

/**
 * Typed payload for a reproducible operation. The legacy [WorkflowRecipe.operation]
 * string is retained for schema-v1 compatibility and human-readable reports;
 * new replay code consumes this model instead of parsing display labels.
 */
@Serializable
sealed class RecipeOperation {
    abstract val operationType: RecipeOperationType
    abstract val deterministic: Boolean

    @Serializable
    @SerialName("restriction_cloning")
    data class RestrictionCloning(
        val enzymeNames: List<String>,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.RESTRICTION_CLONING
        override val deterministic = true
    }

    @Serializable
    @SerialName("overlap_assembly")
    data class OverlapAssembly(
        val method: CloningMethod,
        val minimumOverlap: Int,
        val circular: Boolean,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.OVERLAP_ASSEMBLY
        override val deterministic = true
    }

    @Serializable
    @SerialName("golden_gate")
    data class GoldenGate(
        val overhangs: List<String>,
        val circular: Boolean,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.GOLDEN_GATE
        override val deterministic = true
    }

    @Serializable
    @SerialName("gateway")
    data class Gateway(
        val leftSite: String,
        val rightSite: String,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.GATEWAY
        override val deterministic = true
    }

    @Serializable
    @SerialName("terminal_cloning")
    data class TerminalCloning(
        val method: CloningMethod,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.TERMINAL_CLONING
        override val deterministic = true
    }

    @Serializable
    @SerialName("pcr_restriction_cloning")
    data class PcrRestrictionCloning(
        val enzymeNames: List<String>,
        /** One-based inclusive source target stored exactly as researchers see it. */
        val insertTarget: String,
        val leftClamp: String,
        val rightClamp: String,
        val primerBackend: PrimerDesignBackend = PrimerDesignBackend.BUILTIN,
        val forwardPrimer: String? = null,
        val reversePrimer: String? = null,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.PCR_RESTRICTION_CLONING
        override val deterministic = true
    }

    @Serializable
    @SerialName("homology_recombination")
    data class HomologyRecombination(
        val armLength: Int,
        /** Zero-based position in the stable, coordinate-sorted candidate list. */
        val candidateIndex: Int = 0,
        val productName: String,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.HOMOLOGY_RECOMBINATION
        override val deterministic = true
    }

    /** Typed envelope for existing or third-party operations not yet replayable by the engine. */
    @Serializable
    @SerialName("generic")
    data class Generic(
        val originalOperation: String,
        override val deterministic: Boolean = true,
    ) : RecipeOperation() {
        override val operationType = RecipeOperationType.GENERIC
    }
}

/**
 * Portable, versioned record of a workflow invocation.
 *
 * Recipes intentionally store names, identities, parameters, and explicit
 * provenance—not full input sequences—so they are small, reviewable, and can
 * safely be attached to an ELN or committed with a project. Re-running one
 * requires the matching input sequences to be supplied by the caller.
 */
@Serializable
data class WorkflowRecipe(
    val schemaVersion: Int = WorkflowRecipes.SCHEMA_VERSION,
    val operation: String,
    /** Schema-v2 typed representation. Missing in schema-v1 files and populated during decode migration. */
    val operationSpec: RecipeOperation? = null,
    val inputs: List<RecipeInput>,
    val parameters: Map<String, String> = emptyMap(),
    val procedure: List<ProcedureRecord> = emptyList(),
    val outputName: String,
    val outputCdseguid: String,
    val externalTools: Map<String, String> = emptyMap(),
    /** Explicit network-derived inputs that must be approved before replay. */
    val onlineSources: Map<String, String> = emptyMap(),
)

/** Serialization and construction helpers for reproducible local workflows. */
object WorkflowRecipes {
    const val SCHEMA_VERSION = 2

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "operationKind"
    }

    fun capture(
        operation: String,
        product: Seq,
        inputs: List<Seq>,
        parameters: Map<String, String> = emptyMap(),
        externalTools: Map<String, String> = emptyMap(),
        onlineSources: Map<String, String> = emptyMap(),
    ): WorkflowRecipe = WorkflowRecipe(
        operation = operation,
        operationSpec = typedOperation(operation, parameters, product.name),
        inputs = inputs.map { RecipeInput(it.name, SequenceIdentity.cdseguid(it), it.topology) },
        parameters = parameters.toSortedMap(),
        procedure = product.provenance,
        outputName = product.name,
        outputCdseguid = SequenceIdentity.cdseguid(product),
        externalTools = externalTools.toSortedMap(),
        onlineSources = onlineSources.toSortedMap(),
    )

    fun encode(recipe: WorkflowRecipe): String = json.encodeToString(normalize(recipe))

    /** Decodes schema-v1 recipes and upgrades them in memory to the typed v2 form. */
    fun decode(text: String): WorkflowRecipe = normalize(json.decodeFromString<WorkflowRecipe>(text))

    /** Returns mismatches in caller-supplied inputs before a recipe is run. */
    fun validateInputs(recipe: WorkflowRecipe, inputs: List<Seq>): List<String> {
        val actual = inputs.groupBy { SequenceIdentity.cdseguid(it) }
        return recipe.inputs.mapNotNull { expected ->
            if (actual[expected.cdseguid].isNullOrEmpty()) {
                "Missing input '${expected.name}' (${expected.cdseguid})"
            } else null
        }
    }

    /** Returns the typed operation, synthesizing one when a caller holds an unmigrated in-memory recipe. */
    fun operation(recipe: WorkflowRecipe): RecipeOperation = recipe.operationSpec
        ?: typedOperation(recipe.operation, recipe.parameters, recipe.outputName)

    private fun normalize(recipe: WorkflowRecipe): WorkflowRecipe {
        require(recipe.schemaVersion in 1..SCHEMA_VERSION) {
            "Recipe schema version ${recipe.schemaVersion} is newer than supported version $SCHEMA_VERSION"
        }
        val normalizedParameters = recipe.parameters.toSortedMap()
        val normalizedTools = recipe.externalTools.toSortedMap()
        val normalizedSources = recipe.onlineSources.toSortedMap()
        val typed = recipe.operationSpec ?: typedOperation(recipe.operation, normalizedParameters, recipe.outputName)
        return recipe.copy(
            schemaVersion = SCHEMA_VERSION,
            operationSpec = typed,
            parameters = normalizedParameters,
            externalTools = normalizedTools,
            onlineSources = normalizedSources,
        )
    }

    /** Converts legacy operation labels plus their normalized parameters into the v2 operation model. */
    private fun typedOperation(operation: String, rawParameters: Map<String, String>, outputName: String): RecipeOperation {
        val parameters = rawParameters.mapKeys { it.key.lowercase() }
        fun value(key: String, fallback: String = ""): String = parameters[key.lowercase()] ?: fallback
        fun names(key: String): List<String> = value(key).split(',').map(String::trim).filter(String::isNotEmpty)
        fun bool(key: String, default: Boolean): Boolean = value(key, default.toString()).equals("true", ignoreCase = true)
        fun integer(key: String, default: Int): Int = value(key, default.toString()).toIntOrNull() ?: default
        return when (val normalized = operation.trim().uppercase().replace(' ', '_').replace('-', '_')) {
            "RESTRICTION", "RESTRICTION_CLONING" -> RecipeOperation.RestrictionCloning(
                enzymeNames = names("enzymeNames"),
                productName = value("productName", outputName),
            )
            "GIBSON", "NEBUILDER_HIFI", "IN_FUSION" -> RecipeOperation.OverlapAssembly(
                method = CloningMethod.valueOf(normalized),
                minimumOverlap = integer("minimumOverlap", integer("minOverlap", if (normalized == "IN_FUSION") 15 else 20)),
                circular = bool("circular", true),
                productName = value("productName", outputName),
            )
            "GOLDEN_GATE" -> RecipeOperation.GoldenGate(
                overhangs = names("overhangs"),
                circular = bool("circular", true),
                productName = value("productName", outputName),
            )
            "GATEWAY" -> RecipeOperation.Gateway(
                leftSite = value("leftSite"),
                rightSite = value("rightSite"),
                productName = value("productName", outputName),
            )
            "TA", "GC", "TOPO_TA", "TOPO_DIRECTIONAL", "TOPO_BLUNT" -> RecipeOperation.TerminalCloning(
                method = CloningMethod.valueOf(normalized),
                productName = value("productName", outputName),
            )
            "PCR_RESTRICTION_CLONING" -> RecipeOperation.PcrRestrictionCloning(
                enzymeNames = names("enzymeNames"),
                insertTarget = value("insertTarget"),
                leftClamp = value("leftClamp"),
                rightClamp = value("rightClamp"),
                primerBackend = runCatching { PrimerDesignBackend.valueOf(value("primerBackend", "BUILTIN").uppercase()) }
                    .getOrDefault(PrimerDesignBackend.BUILTIN),
                forwardPrimer = value("forwardPrimer").ifBlank { null },
                reversePrimer = value("reversePrimer").ifBlank { null },
                productName = value("productName", outputName),
            )
            "HOMOLOGY_RECOMBINATION" -> RecipeOperation.HomologyRecombination(
                armLength = integer("armLength", integer("arm", 20)),
                candidateIndex = integer("candidateIndex", 0),
                productName = value("productName", outputName),
            )
            else -> RecipeOperation.Generic(operation, deterministic = true)
        }
    }
}
