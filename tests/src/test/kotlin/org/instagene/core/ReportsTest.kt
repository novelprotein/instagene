package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportsTest {
    @Test
    fun workflowReportsContainStableProductIdentityAndSortedParameters() {
        val product = Seq("product", "ACGT", topology = Topology.CIRCULAR)
        val report = Reports.workflowReport(
            operation = "Golden Gate",
            product = product,
            inputs = listOf(Seq("part", "AC")),
            parameters = mapOf("overhangs" to "A,G,A", "circular" to "true"),
        )

        assertEquals(SequenceIdentity.cdseguid(product), report.productIdentity)
        assertEquals(listOf("circular", "overhangs"), report.parameters.keys.toList())
        assertContains(Reports.workflowMarkdown(report), "Sequence identity")
        assertContains(Reports.workflowJson(report), "Golden Gate")
        assertContains(Reports.workflowHtml(report), "<!doctype html>")
        val pdf = Reports.workflowPdf(report).toString(Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.endsWith("%%EOF\n"))
    }

    @Test
    fun verificationReportsExposeCoverageAndMismatchDetails() {
        val reference = Seq("reference", "ACGTACGT")
        val read = Seq("read", "ACGT")
        val result = SangerAlignment.align(reference, listOf(read))
        val report = Reports.verificationReport(reference, result)

        assertEquals(1, report.totalReads)
        assertEquals(4, report.uncoveredPositions.size)
        assertContains(Reports.verificationMarkdown(report), "Uncovered positions")
        assertContains(Reports.verificationJson(report), "referenceIdentity")
    }

    @Test
    fun primerDesignReportsCarryQualityConstraintsAndPrimer3Provenance() {
        val template = Seq("template", "ACGT".repeat(40))
        val quality = PrimerQualityContext(
            templateLength = template.length,
            evidence = listOf(
                QualityEvidence(
                    QualitySourceProvenance(QualityEvidenceKind.FASTA_QUAL, "template.qual", "quality/template.qual"),
                    listOf(ReferencePhredObservation(10, 5)),
                ),
            ),
            manualExcludedRegions = listOf(ManualQualityExclusion(30..34)),
        )
        val parameters = PrimerDesignParameters(qualityContext = quality)
        val result = PrimerDesign.design(template, 0, template.length, parameters)
            .copy(primer3Input = "SEQUENCE_ID=template\n=")

        val report = Reports.primerDesignReport(template, 0, template.length, parameters, result)

        assertEquals(20, report.quality?.minimumPhred)
        assertContains(Reports.primerDesignJson(report), "quality/template.qual")
        assertContains(Reports.primerDesignMarkdown(report), "Primer3 provenance")
        assertContains(Reports.primerDesignMarkdown(report), "Quality constraints")
    }

    @Test
    fun cloningReportsMergeEngineNormalizedParametersBeforeExport() {
        val backbone = TestSequenceFixtures.restrictionBackbone.copy(topology = Topology.CIRCULAR)
        val insert = TestSequenceFixtures.restrictionInsert
        val result = CloningWorkflows.restriction(
            backbone,
            insert,
            listOf(Enzymes.require("EcoRI"), Enzymes.require("HindIII")),
            "pGFP",
        )

        val report = Reports.workflowReport(result, listOf(backbone, insert))

        assertEquals("EcoRI,HindIII", report.parameters["enzymeNames"])
        assertEquals("pGFP", report.parameters["productName"])
        assertContains(Reports.workflowMarkdown(report), "enzymeNames")
    }
}
