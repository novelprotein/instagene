package org.instagene.core

/** Explicit grants required before a recipe may invoke a non-local dependency. */
data class WorkflowReplayAuthorization(
    val allowExternalTools: Boolean = false,
    val allowOnlineSources: Boolean = false,
)

/** Outcome of replaying a portable workflow recipe. */
enum class WorkflowReplayStatus {
    SUCCEEDED,
    INPUT_MISMATCH,
    EXTERNAL_OPT_IN_REQUIRED,
    ONLINE_OPT_IN_REQUIRED,
    UNSUPPORTED_OPERATION,
    OUTPUT_IDENTITY_MISMATCH,
    FAILED,
}

/** A replay result always carries a reviewable status instead of returning a partial construct silently. */
data class WorkflowReplayResult(
    val recipe: WorkflowRecipe,
    val operation: RecipeOperation,
    val status: WorkflowReplayStatus,
    val product: Seq? = null,
    val messages: List<String> = emptyList(),
) {
    val succeeded: Boolean get() = status == WorkflowReplayStatus.SUCCEEDED
}

/**
 * Replays the deterministic molecular-biology workflows represented by a
 * [WorkflowRecipe]. Inputs are resolved by content identity, never by a file
 * path or display name, so a recipe cannot accidentally run against a changed
 * construct. The output identity is verified before a result is accepted.
 */
object WorkflowReplays {

    fun replay(
        recipe: WorkflowRecipe,
        suppliedInputs: List<Seq>,
        authorization: WorkflowReplayAuthorization = WorkflowReplayAuthorization(),
    ): WorkflowReplayResult {
        val operation = WorkflowRecipes.operation(recipe)
        val resolved = resolveInputs(recipe, suppliedInputs)
        if (resolved.errors.isNotEmpty()) {
            return WorkflowReplayResult(recipe, operation, WorkflowReplayStatus.INPUT_MISMATCH, messages = resolved.errors)
        }
        if (recipe.onlineSources.isNotEmpty() && !authorization.allowOnlineSources) {
            return WorkflowReplayResult(
                recipe,
                operation,
                WorkflowReplayStatus.ONLINE_OPT_IN_REQUIRED,
                messages = listOf(
                    "This recipe records online sources (${recipe.onlineSources.keys.joinToString()}). " +
                        "Replay again with explicit online approval.",
                ),
            )
        }
        if (requiresExternalTools(recipe, operation) && !authorization.allowExternalTools) {
            val tools = buildList {
                addAll(recipe.externalTools.keys)
                if (operation is RecipeOperation.PcrRestrictionCloning && operation.primerBackend == PrimerDesignBackend.PRIMER3) add("primer3")
            }.distinct()
            return WorkflowReplayResult(
                recipe,
                operation,
                WorkflowReplayStatus.EXTERNAL_OPT_IN_REQUIRED,
                messages = listOf("This recipe requires external tool(s): ${tools.joinToString()}. Replay again with explicit external-tool approval."),
            )
        }
        if (operation is RecipeOperation.Generic) {
            return WorkflowReplayResult(
                recipe,
                operation,
                WorkflowReplayStatus.UNSUPPORTED_OPERATION,
                messages = listOf("'${operation.originalOperation}' has no deterministic replay implementation in this InstaGene version."),
            )
        }

        val product = runCatching { execute(operation, resolved.inputs) }.getOrElse { error ->
            return WorkflowReplayResult(
                recipe,
                operation,
                WorkflowReplayStatus.FAILED,
                messages = listOf(error.message ?: error.javaClass.simpleName),
            )
        }
        val actualIdentity = SequenceIdentity.cdseguid(product)
        if (actualIdentity != recipe.outputCdseguid) {
            return WorkflowReplayResult(
                recipe,
                operation,
                WorkflowReplayStatus.OUTPUT_IDENTITY_MISMATCH,
                product,
                listOf(
                    "Replay completed but produced $actualIdentity; recipe requires ${recipe.outputCdseguid}. " +
                        "No output should be accepted until the changed inputs or parameters are reviewed.",
                ),
            )
        }
        return WorkflowReplayResult(
            recipe,
            operation,
            WorkflowReplayStatus.SUCCEEDED,
            product,
            listOf("Replayed ${operation.operationType.name.lowercase().replace('_', ' ')} with identity-matched inputs."),
        )
    }

    private fun requiresExternalTools(recipe: WorkflowRecipe, operation: RecipeOperation): Boolean =
        recipe.externalTools.isNotEmpty() ||
            (operation is RecipeOperation.PcrRestrictionCloning && operation.primerBackend == PrimerDesignBackend.PRIMER3)

    private fun execute(operation: RecipeOperation, inputs: List<Seq>): Seq = when (operation) {
        is RecipeOperation.RestrictionCloning -> {
            requireTwoInputs(inputs, operation)
            CloningWorkflows.restriction(inputs[0], inputs[1], enzymes(operation.enzymeNames), operation.productName).product
        }
        is RecipeOperation.OverlapAssembly -> {
            require(inputs.size >= 2) { "${operation.operationType} requires at least two inputs" }
            CloningWorkflows.overlapAssembly(
                operation.method,
                inputs,
                operation.productName,
                operation.circular,
                operation.minimumOverlap,
            ).product
        }
        is RecipeOperation.GoldenGate -> {
            require(inputs.isNotEmpty()) { "${operation.operationType} requires at least one input" }
            CloningWorkflows.goldenGate(inputs, operation.overhangs, operation.productName, operation.circular).product
        }
        is RecipeOperation.Gateway -> {
            requireTwoInputs(inputs, operation)
            CloningWorkflows.gateway(inputs[0], inputs[1], operation.leftSite, operation.rightSite, operation.productName).product
        }
        is RecipeOperation.TerminalCloning -> {
            requireTwoInputs(inputs, operation)
            CloningWorkflows.terminalClone(operation.method, inputs[0], inputs[1], operation.productName).product
        }
        is RecipeOperation.PcrRestrictionCloning -> {
            requireTwoInputs(inputs, operation)
            val (start, end) = parseOneBasedInclusiveRange(operation.insertTarget, inputs[1].length)
            PcrCloningWorkflows.designAndClone(
                PcrCloningRequest(
                    backbone = inputs[0],
                    insertTemplate = inputs[1],
                    insertStart = start,
                    insertEnd = end,
                    enzymes = enzymes(operation.enzymeNames),
                    productName = operation.productName,
                    primerBackend = operation.primerBackend,
                    leftClamp = operation.leftClamp,
                    rightClamp = operation.rightClamp,
                ),
            ).product
        }
        is RecipeOperation.HomologyRecombination -> {
            requireTwoInputs(inputs, operation)
            CloningWorkflows.homologyRecombination(
                inputs[0],
                inputs[1],
                operation.armLength,
                operation.candidateIndex,
                operation.productName,
            ).product
        }
        is RecipeOperation.Generic -> error("Generic recipes cannot be replayed")
    }

    private fun requireTwoInputs(inputs: List<Seq>, operation: RecipeOperation) {
        require(inputs.size == 2) { "${operation.operationType} requires exactly 2 inputs, found ${inputs.size}" }
    }

    private fun enzymes(names: List<String>): List<Enzyme> {
        require(names.isNotEmpty()) { "Recipe does not record any restriction enzymes" }
        return names.map(Enzymes::require)
    }

    /** Converts a researcher-facing `1..N` target into the engine's half-open zero-based range. */
    private fun parseOneBasedInclusiveRange(value: String, length: Int): Pair<Int, Int> {
        val match = Regex("^\\s*(\\d+)\\s*\\.\\.\\s*(\\d+)\\s*$").matchEntire(value)
            ?: throw IllegalArgumentException("PCR recipe target '$value' is not a one-based inclusive range such as 1..240")
        val start = match.groupValues[1].toInt() - 1
        val end = match.groupValues[2].toInt()
        require(start in 0 until length && end in (start + 1)..length) {
            "PCR recipe target '$value' is outside the supplied insert template (1..$length)"
        }
        return start to end
    }

    private data class ResolvedInputs(val inputs: List<Seq>, val errors: List<String>)

    /** Matches every expected identity once, preserving the recipe's semantic input order. */
    private fun resolveInputs(recipe: WorkflowRecipe, supplied: List<Seq>): ResolvedInputs {
        if (recipe.inputs.size != supplied.size) {
            return ResolvedInputs(
                emptyList(),
                listOf("Recipe expects ${recipe.inputs.size} input(s), but ${supplied.size} were supplied."),
            )
        }
        val remaining = supplied.toMutableList()
        val resolved = mutableListOf<Seq>()
        val errors = mutableListOf<String>()
        recipe.inputs.forEach { expected ->
            val index = remaining.indexOfFirst { SequenceIdentity.cdseguid(it) == expected.cdseguid }
            if (index < 0) {
                errors += "Missing identity-matched input '${expected.name}' (${expected.cdseguid})."
            } else {
                val found = remaining.removeAt(index)
                resolved += expected.topology?.let(found::withTopology) ?: found
            }
        }
        return ResolvedInputs(resolved, errors)
    }
}
