package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowRecipesTest {

    @Test
    fun capturedRecipeRoundTripsWithSortedParametersAndProvenance() {
        val input = Seq(name = "vector", bases = "ACGTACGT")
        val product = Seq(name = "product", bases = "ACGTACGTGG").withProcedure(
            ProcedureRecord("GIBSON", "Joined vector and insert", listOf("vector"), timestamp = 42),
        )
        val recipe = WorkflowRecipes.capture(
            "Gibson", product, listOf(input),
            parameters = linkedMapOf("overlap" to "20", "circular" to "true"),
            externalTools = mapOf("primer3_core" to "2.6.1"),
        )
        val decoded = WorkflowRecipes.decode(WorkflowRecipes.encode(recipe))
        assertEquals(recipe, decoded)
        assertEquals(WorkflowRecipes.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(
            RecipeOperation.OverlapAssembly(
                method = CloningMethod.GIBSON,
                minimumOverlap = 20,
                circular = true,
                productName = "product",
            ),
            decoded.operationSpec,
        )
        assertEquals(listOf("circular", "overlap"), decoded.parameters.keys.toList())
        assertEquals("GIBSON", decoded.procedure.single().operation)
    }

    @Test
    fun schemaV1RecipeMigratesToTypedOperationWithoutChangingItsIdentity() {
        val legacy = """
            {
              "schemaVersion": 1,
              "operation": "RESTRICTION",
              "inputs": [{"name": "vector", "cdseguid": "abc"}],
              "parameters": {"productName": "clone", "enzymeNames": "HindIII,EcoRI"},
              "outputName": "clone",
              "outputCdseguid": "out"
            }
        """.trimIndent()

        val decoded = WorkflowRecipes.decode(legacy)

        assertEquals(WorkflowRecipes.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(
            RecipeOperation.RestrictionCloning(listOf("HindIII", "EcoRI"), "clone"),
            decoded.operationSpec,
        )
        assertTrue(WorkflowRecipes.encode(decoded).contains("\"operationKind\": \"restriction_cloning\""))
    }

    @Test
    fun recipeEncodingIsStableAndRejectsFutureSchemas() {
        val recipe = WorkflowRecipes.capture(
            operation = "Golden Gate",
            product = Seq(name = "assembly", bases = "ACGT"),
            inputs = emptyList(),
            parameters = linkedMapOf("productName" to "assembly", "overhangs" to "AGCT,TCGA", "circular" to "false"),
        )

        val first = WorkflowRecipes.encode(recipe)
        assertEquals(first, WorkflowRecipes.encode(WorkflowRecipes.decode(first)))
        assertFailsWith<IllegalArgumentException> {
            WorkflowRecipes.decode(first.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"))
        }
    }

    @Test
    fun inputValidationUsesSequenceIdentityNotDisplayName() {
        val input = Seq(name = "vector", bases = "ACGTACGT")
        val recipe = WorkflowRecipes.capture("test", Seq(name = "out", bases = "TTTT"), listOf(input))
        assertTrue(WorkflowRecipes.validateInputs(recipe, listOf(input.copy(name = "renamed"))).isEmpty())
        val errors = WorkflowRecipes.validateInputs(recipe, listOf(Seq(name = "vector", bases = "AAAA")))
        assertFalse(errors.isEmpty())
        assertTrue(errors.single().contains("Missing input"))
    }
}
