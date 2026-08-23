package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(listOf("circular", "overlap"), decoded.parameters.keys.toList())
        assertEquals("GIBSON", decoded.procedure.single().operation)
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
