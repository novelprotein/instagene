package org.instagene.core

import org.instagene.core.io.FastaQualRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimerQualityContextTest {

    @Test
    fun combinesTraceEvidenceConservativelyAndKeepsUncoveredSeparate() {
        val trace = QualityEvidence(
            QualitySourceProvenance(QualityEvidenceKind.CHROMATOGRAM, "trace-a"),
            listOf(
                ReferencePhredObservation(1, 40),
                ReferencePhredObservation(2, 33),
                ReferencePhredObservation(4, 8),
            ),
        )
        val sidecar = QualityEvidence(
            QualitySourceProvenance(QualityEvidenceKind.FASTA_QUAL, "read.qual"),
            listOf(ReferencePhredObservation(2, 12)),
        )
        val context = PrimerQualityContext(
            templateLength = 8,
            minimumPhred = 20,
            evidence = listOf(trace, sidecar),
            manualExcludedRegions = listOf(ManualQualityExclusion(6..6, "reviewed manually")),
        )

        assertEquals(12, context.phredAt(2), "the lowest observation must win")
        assertEquals(setOf(2, 4), context.lowQualityPositions)
        assertEquals(setOf(0, 3, 5, 6, 7), context.uncoveredPositions)
        assertEquals(listOf(2..2, 4..4, 6..6), context.effectiveExcludedRegions())
        assertFalse(context.summary().excludeUncoveredPositions)
        assertTrue(context.summary().sources.any { it.kind == QualityEvidenceKind.MANUAL })
    }

    @Test
    fun sidecarEvidenceMapsSequentiallyAndManualParserUsesOneBasedCoordinates() {
        val evidence = PrimerQualityContext.evidenceFromFastaQual(
            FastaQualRecord("read", listOf(40, 7, 35)),
            templateLength = 10,
            offset = 3,
            sourceId = "reads/read.qual",
        )
        val manual = QualityRegions.parseOneBased("1-2, 8", templateLength = 10)
        val context = PrimerQualityContext(10, evidence = listOf(evidence), manualExcludedRegions = manual)

        assertEquals(listOf(3, 4, 5), evidence.observations.map { it.referencePosition })
        assertEquals("reads/read.qual", evidence.source.sourceId)
        assertEquals(listOf(0..1, 4..4, 7..7), context.effectiveExcludedRegions())
        assertEquals("1-2, 5-5, 8-8", QualityRegions.oneBased(context.effectiveExcludedRegions()))
    }

    @Test
    fun alignmentEvidencePreservesReadProvenanceAndReferenceCoordinates() {
        val reference = Seq("reference", "ACGTACGT")
        val result = SangerAlignment.align(
            reference,
            listOf(SangerRead("trace-read", "ACGT", listOf(40, 30, 20, 10))),
            SangerOptions(trimQuality = 0),
        )

        val evidence = PrimerQualityContext.evidenceFromSangerAlignment(result).single()

        assertEquals(QualityEvidenceKind.CHROMATOGRAM, evidence.source.kind)
        assertEquals("trace-read", evidence.source.label)
        assertEquals(listOf(0, 1, 2, 3), evidence.observations.map { it.referencePosition })
        assertEquals(listOf(40, 30, 20, 10), evidence.observations.map { it.phred })
    }
}
