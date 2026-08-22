package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
}
