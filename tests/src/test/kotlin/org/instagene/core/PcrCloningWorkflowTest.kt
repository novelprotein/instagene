package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcrCloningWorkflowTest {

    private val backbone = TestSequenceFixtures.restrictionBackbone.copy(topology = Topology.CIRCULAR)
    private val insertTemplate = TestSequenceFixtures.insertTemplate
    private val enzymes = listOf(Enzymes.require("EcoRI"), Enzymes.require("HindIII"))

    @Test
    fun pcrRestrictionCloningDesignsPrimersValidatesCoordinatesAndCapturesRecipe() {
        val result = PcrCloningWorkflows.designAndClone(
            PcrCloningRequest(
                backbone = backbone,
                insertTemplate = insertTemplate,
                enzymes = enzymes,
                productName = "pGFP_pcr",
            ),
        )

        assertEquals(Topology.CIRCULAR, result.product.topology)
        assertTrue(result.validation.passed)
        assertTrue(result.validation.targetMatchesAmplicon)
        assertTrue(result.validation.restrictionSitesAreUniqueInAmplicon)
        assertTrue(result.validation.productContainsTarget)
        assertEquals(
            insertTemplate.bases,
            result.amplification.product.sub(
                result.validation.coordinates.pcrTarget.start,
                result.validation.coordinates.pcrTarget.end,
            ),
        )
        assertEquals(0, result.validation.coordinates.templateTarget.start)
        assertEquals(insertTemplate.length, result.validation.coordinates.templateTarget.end)
        assertTrue(result.product.features.any { it.name == result.amplification.product.name && it.notes.startsWith("Inserted with") })
        assertEquals(listOf("EcoRI", "HindIII"), result.recipe.parameters.getValue("enzymeNames").split(','))
        assertEquals(2, result.recipe.inputs.size)
        assertTrue(result.product.provenance.any { it.operation == PcrMode.STANDARD.name })
        assertTrue(result.product.provenance.any { it.operation == CloningMethod.RESTRICTION.name })
    }

    @Test
    fun pcrCloningReportCarriesPrimersCoordinatesAndPortableRecipe() {
        val result = PcrCloningWorkflows.designAndClone(
            PcrCloningRequest(backbone, insertTemplate, enzymes = enzymes, productName = "pGFP_report"),
        )

        val report = Reports.pcrCloningReport(result)
        val markdown = Reports.pcrCloningMarkdown(report)
        val json = Reports.pcrCloningJson(report)
        val html = Reports.pcrCloningHtml(report)
        val pdf = Reports.pcrCloningPdf(report).toString(Charsets.ISO_8859_1)

        assertEquals("PCR_RESTRICTION_CLONING", report.recipe.operation)
        assertEquals(result.forwardPrimer.extension + result.forwardPrimer.hybridizingSequence, report.forwardPrimer.fullSequence)
        assertEquals(result.validation.coordinates.productInsert.start + 1, report.validation.productInsert.start)
        assertContains(markdown, "Coordinate validation")
        assertContains(markdown, "Reproducibility recipe")
        assertContains(json, "forwardPrimer")
        assertContains(json, "PCR_RESTRICTION_CLONING")
        assertContains(html, "<!doctype html>")
        assertTrue(pdf.startsWith("%PDF-1.4"))
    }

    @Test
    fun pcrCloningRejectsInternalSelectedRestrictionSite() {
        val template = Seq("insert_with_ecori", "ATGCGTGAATTC" + "ACGT".repeat(30))
        val error = assertFailsWith<IllegalArgumentException> {
            PcrCloningWorkflows.designAndClone(
                PcrCloningRequest(
                    backbone = backbone,
                    insertTemplate = template,
                    enzymes = enzymes,
                ),
            )
        }

        assertContains(error.message.orEmpty(), "internal EcoRI")
    }

    @Test
    fun oneEnzymeCloningIsMarkedAsNonDirectional() {
        val result = PcrCloningWorkflows.designAndClone(
            PcrCloningRequest(
                backbone = backbone,
                insertTemplate = insertTemplate,
                enzymes = listOf(Enzymes.require("EcoRI")),
                productName = "pGFP_single_enzyme",
            ),
        )

        assertTrue(result.validation.passed)
        assertTrue(result.validation.diagnostics.any { it.message.contains("not directionally constrained") })
    }
}
