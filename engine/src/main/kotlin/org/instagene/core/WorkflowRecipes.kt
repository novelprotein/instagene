package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Immutable identity of a recipe input, independent of its current file path. */
@Serializable
data class RecipeInput(
    val name: String,
    val cdseguid: String,
)

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
    val schemaVersion: Int = 1,
    val operation: String,
    val inputs: List<RecipeInput>,
    val parameters: Map<String, String> = emptyMap(),
    val procedure: List<ProcedureRecord> = emptyList(),
    val outputName: String,
    val outputCdseguid: String,
    val externalTools: Map<String, String> = emptyMap(),
)

/** Serialization and construction helpers for reproducible local workflows. */
object WorkflowRecipes {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun capture(
        operation: String,
        product: Seq,
        inputs: List<Seq>,
        parameters: Map<String, String> = emptyMap(),
        externalTools: Map<String, String> = emptyMap(),
    ): WorkflowRecipe = WorkflowRecipe(
        operation = operation,
        inputs = inputs.map { RecipeInput(it.name, SequenceIdentity.cdseguid(it)) },
        parameters = parameters.toSortedMap(),
        procedure = product.provenance,
        outputName = product.name,
        outputCdseguid = SequenceIdentity.cdseguid(product),
        externalTools = externalTools.toSortedMap(),
    )

    fun encode(recipe: WorkflowRecipe): String = json.encodeToString(recipe)

    fun decode(text: String): WorkflowRecipe = json.decodeFromString(text)

    /** Returns mismatches in caller-supplied inputs before a recipe is run. */
    fun validateInputs(recipe: WorkflowRecipe, inputs: List<Seq>): List<String> {
        val actual = inputs.groupBy { SequenceIdentity.cdseguid(it) }
        return recipe.inputs.mapNotNull { expected ->
            if (actual[expected.cdseguid].isNullOrEmpty()) {
                "Missing input '${expected.name}' (${expected.cdseguid})"
            } else null
        }
    }
}
