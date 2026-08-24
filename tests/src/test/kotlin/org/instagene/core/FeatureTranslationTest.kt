package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTranslationTest {

    @Test
    fun forwardCdsLinksEveryCodonToItsExactSequenceCoordinates() {
        val sequence = Seq(
            "construct",
            "CCCCATGAAATAAGGGG",
            features = listOf(Feature("gfp_start", "CDS", 4, 13, Strand.FORWARD)),
        )

        val result = FeatureTranslations.translate(sequence, sequence.features.single())

        assertEquals("MK*", result.protein)
        assertTrue(result.isInFrame)
        assertFalse(result.hasErrors)
        assertEquals(listOf(4, 5, 6), result.codons.first().sourcePositions)
        assertEquals(listOf(10, 11, 12), result.codons.last().sourcePositions)
        assertEquals("5,6,7", result.codons.first().displayCoordinates())
        assertTrue(FeatureTranslations.summary(result).contains("MK*"))
    }

    @Test
    fun reverseCdsPreservesBiologicalCodonOrderAndCoordinates() {
        val sequence = Seq(
            "reverse_construct",
            "CCCTTATTTCATGGG",
            features = listOf(Feature("reverse_gene", "CDS", 3, 12, Strand.REVERSE)),
        )

        val result = FeatureTranslations.translate(sequence, sequence.features.single())

        assertEquals("MK*", result.protein)
        assertEquals(listOf(11, 10, 9), result.codons.first().sourcePositions)
        assertEquals("12,11,10", result.codons.first().displayCoordinates())
        assertFalse(result.hasErrors)
    }

    @Test
    fun codonStartAndDeclaredTranslationAreValidatedAgainstCoordinates() {
        val matching = Feature(
            "offset_cds",
            "CDS",
            0,
            10,
            translationStartOffset = 1,
            qualifiers = mapOf("translation" to listOf("MK")),
        )
        val mismatch = matching.copy(name = "mismatch", qualifiers = mapOf("translation" to listOf("MA")))
        val sequence = Seq("offset", "AATGAAATAA", features = listOf(matching, mismatch))

        val matched = FeatureTranslations.translate(sequence, matching)
        val failed = FeatureTranslations.translate(sequence, mismatch)

        assertEquals("MK*", matched.protein)
        assertEquals(listOf(0), matched.skippedLeadingPositions)
        assertTrue(matched.issues.none { it.code == "DECLARED_TRANSLATION_MISMATCH" })
        assertTrue(failed.issues.any { it.code == "DECLARED_TRANSLATION_MISMATCH" && it.severity == TranslationValidationSeverity.ERROR })
    }

    @Test
    fun partialFramesAndInternalStopsAreExplicitlyReported() {
        val partial = Feature("partial", "CDS", 0, 10)
        val internalStop = Feature("internal_stop", "CDS", 0, 12)
        val partialResult = FeatureTranslations.translate(Seq("partial", "ATGAAATAAA", features = listOf(partial)), partial)
        val stopResult = FeatureTranslations.translate(Seq("stop", "ATGTAAATGTAA", features = listOf(internalStop)), internalStop)

        assertFalse(partialResult.isInFrame)
        assertTrue(partialResult.issues.any { it.code == "PARTIAL_TERMINAL_CODON" })
        assertTrue(stopResult.issues.any { it.code == "INTERNAL_STOP_CODON" && it.severity == TranslationValidationSeverity.ERROR })
    }
}
