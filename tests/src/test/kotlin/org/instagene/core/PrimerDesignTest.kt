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
}
