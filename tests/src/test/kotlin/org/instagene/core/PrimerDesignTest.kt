package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimerDesignTest {

    private val template = Seq(name = "template", bases = "ACGT".repeat(80))

    @Test
    fun primer3RequestCarriesTargetAndExcludedRegions() {
        val request = PrimerDesign.primer3Input(
            template, 10, 120,
            PrimerDesignParameters(excludedRegions = listOf(30..39, 80..84)),
        )
        assertTrue("SEQUENCE_INCLUDED_REGION=10,110" in request)
        assertTrue("SEQUENCE_EXCLUDED_REGION=30,10 80,5" in request)
        assertTrue(request.endsWith("=\n"))
    }

    @Test
    fun primer3OutputUsesForwardAndReverseCoordinates() {
        val candidates = PrimerDesign.parsePrimer3Output(
            """
            PRIMER_PAIR_0_PENALTY=1.25
            PRIMER_LEFT_0=12,20
            PRIMER_LEFT_0_SEQUENCE=ACGTACGTACGTACGTACGT
            PRIMER_RIGHT_0=150,21
            PRIMER_RIGHT_0_SEQUENCE=CGTACGTACGTACGTACGTAC
            =
            """.trimIndent(),
            template,
        )
        assertEquals(2, candidates.size)
        assertEquals(12, candidates.first { it.primer.name == "primer3_F" }.start)
        val reverse = candidates.first { it.primer.name == "primer3_R" }
        assertEquals(130, reverse.start)
        assertEquals(151, reverse.end)
        assertEquals(1.25, reverse.score)
    }

    @Test
    fun builtInCandidatesDoNotOverlapExcludedRegions() {
        val candidates = PrimerDesign.candidates(template, 0, template.length, PrimerDesignParameters(excludedRegions = listOf(0..40)))
        assertFalse(candidates.any { it.start <= 40 })
    }

    @Test
    fun qualityContextIsMergedIntoBuiltInAndPrimer3Exclusions() {
        val quality = PrimerQualityContext(
            templateLength = template.length,
            minimumPhred = 20,
            evidence = listOf(
                QualityEvidence(
                    QualitySourceProvenance(QualityEvidenceKind.FASTA_QUAL, "template.qual"),
                    listOf(ReferencePhredObservation(2, 5)),
                ),
            ),
            manualExcludedRegions = listOf(ManualQualityExclusion(60..64)),
        )
        val parameters = PrimerDesignParameters(excludedRegions = listOf(30..39), qualityContext = quality)

        val request = PrimerDesign.primer3Input(template, 0, template.length, parameters)
        val candidates = PrimerDesign.candidates(template, 0, template.length, parameters)

        assertTrue("SEQUENCE_EXCLUDED_REGION=2,1 30,10 60,5" in request)
        assertTrue(candidates.none { it.start <= 2 && it.end > 2 })
        assertTrue(candidates.none { it.start <= 64 && it.end > 60 })
    }

    @Test
    fun sequencingModeScansTheSelectedWindowAndHonorsDirection() {
        val parameters = PrimerDesignParameters(
            minLength = 8,
            maxLength = 8,
            minTm = 0.0,
            maxTm = 100.0,
            minGc = 0.0,
            maxGc = 100.0,
            maxSelfComplementarity = 100,
            mode = PrimerDesignMode.SEQUENCING,
            sequencingDirection = SequencingPrimerDirection.FORWARD,
        )

        val candidates = PrimerDesign.candidates(template, 20, 50, parameters)
        val input = PrimerDesign.primer3Input(template, 20, 50, parameters)

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.primer.name.startsWith("candidate_SEQ_F_") })
        assertTrue(candidates.all { it.start in 20..42 && it.end <= 50 })
        assertTrue("PRIMER_TASK=pick_sequencing_primers" in input)
        assertTrue("PRIMER_PICK_RIGHT_PRIMER=0" in input)
    }

    @Test
    fun primer3FailureFallsBackWithQualityAndRequestProvenance() {
        val quality = PrimerQualityContext(
            template.length,
            evidence = listOf(
                QualityEvidence(
                    QualitySourceProvenance(QualityEvidenceKind.FASTA_QUAL, "template.qual"),
                    listOf(ReferencePhredObservation(2, 5)),
                ),
            ),
        )
        val tool = ExternalTools.CATALOG.first { it.id == "primer3" }

        val result = PrimerDesign.design(
            template,
            0,
            template.length,
            PrimerDesignParameters(qualityContext = quality),
            PrimerDesignBackend.PRIMER3,
            primer3Runner = { request -> ToolResult(tool, "primer3_core", 127, "", "not installed: $request") },
        )

        assertEquals(PrimerDesignBackend.BUILTIN, result.backend)
        assertTrue(result.warnings.any { it.contains("Primer3 unavailable") })
        assertEquals(listOf(2..2), result.effectiveExcludedRegions)
        assertTrue(result.primer3Input.orEmpty().contains("SEQUENCE_EXCLUDED_REGION=2,1"))
    }
}
