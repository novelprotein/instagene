package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlignmentAlgorithmTest {
    @Test
    fun globalAlignmentUsesOneAffinePenaltyForAWholeGapRun() {
        val result = Alignment.align(
            Seq(name = "reference", bases = "ACGTACGT"),
            listOf(Seq(name = "query", bases = "ACGT")),
            AlignmentParameters(matchScore = 2.0, mismatchPenalty = -4.0, gapPenalty = -3.0, gapExtensionPenalty = -1.0),
        )

        assertEquals("ACGTACGT", result.reference.sequence)
        assertEquals("ACGT", result.queries.single().sequence.replace("-", ""))
        assertEquals(4, result.queries.single().sequence.count { it == '-' })
        assertEquals(2.0, result.queries.single().score)
        assertEquals(4, result.queries.single().gaps)
    }

    @Test
    fun localAlignmentReturnsTheBestInternalSegment() {
        val result = Alignment.align(
            Seq(name = "reference", bases = "TTACGTAA"),
            listOf(Seq(name = "query", bases = "GGACGTCC")),
            AlignmentParameters(matchScore = 2.0, mismatchPenalty = -3.0, gapPenalty = -4.0, mode = AlignmentMode.LOCAL),
        )

        assertEquals("ACGT", result.reference.sequence)
        assertEquals("ACGT", result.queries.single().sequence)
        assertEquals(8.0, result.queries.single().score)
    }

    @Test
    fun proteinPresetUsesEngineOwnedSubstitutionScores() {
        val result = Alignment.align(
            Seq(name = "reference", bases = "AC", kind = SeqKind.PROTEIN),
            listOf(Seq(name = "query", bases = "AC", kind = SeqKind.PROTEIN)),
            AlignmentParameters(scoring = AlignmentScoring.BLOSUM62),
        )

        assertEquals(13.0, result.queries.single().score)
    }

    @Test
    fun builtinMultipleAlignmentProgressivelyKeepsEveryInputRow() {
        val input = listOf(
            Seq(name = "one", bases = "ACGT"),
            Seq(name = "two", bases = "AGT"),
            Seq(name = "three", bases = "ACCT"),
        )

        val first = MultipleAlignment.align(input).sequences
        val second = MultipleAlignment.align(input).sequences
        assertEquals(first, second)
        assertEquals(1, first.map { it.bases.length }.toSet().size)
        assertEquals(input.map { it.bases }, first.map { it.bases.replace("-", "") })
        assertTrue(first.any { '-' in it.bases })
    }
}
