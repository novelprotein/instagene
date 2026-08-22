package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SangerAlignmentTest {

    @Test
    fun alignsPerfectMatch() {
        val ref = Seq(name = "ref", bases = "ATCGATCGATCGATCGATCG", kind = SeqKind.DNA)
        val read = Seq(name = "read1", bases = "ATCGATCGATCGATCGATCG", kind = SeqKind.DNA)
        val result = SangerAlignment.align(ref, listOf(read))
        assertEquals(1, result.reads.size)
        assertEquals(1.0, result.reads[0].identity, 0.01, "Perfect match should have 100% identity")
    }

    @Test
    fun detectsMismatches() {
        val ref = Seq(name = "ref", bases = "ATCGATCGATCG", kind = SeqKind.DNA)
        val read = Seq(name = "read1", bases = "ATCGATCAATCG", kind = SeqKind.DNA)
        val result = SangerAlignment.align(ref, listOf(read))
        assertTrue(result.reads[0].mismatches.isNotEmpty(), "Should detect the mismatch")
        assertTrue(result.reads[0].identity < 1.0, "Identity should be less than 100%")
    }

    @Test
    fun summaryCalculatesAverages() {
        val ref = Seq(name = "ref", bases = "ATCGATCG", kind = SeqKind.DNA)
        val read1 = Seq(name = "r1", bases = "ATCGATCG", kind = SeqKind.DNA)
        val read2 = Seq(name = "r2", bases = "ATCGATCA", kind = SeqKind.DNA)
        val result = SangerAlignment.align(ref, listOf(read1, read2))
        assertEquals(2, result.summary.totalReads)
        assertTrue(result.summary.averageIdentity > 0.9, "Average identity should be >90%")
    }

    @Test
    fun qualityAwareAlignmentTrimsLowQualityEndsAndClassifiesLowQualityMismatch() {
        val ref = Seq(name = "ref", bases = "ACGTACGT", kind = SeqKind.DNA)
        val read = SangerRead("trace", "ACGTTC", listOf(40, 40, 40, 40, 8, 40))
        val result = SangerAlignment.align(ref, listOf(read), SangerOptions(minQuality = 20, trimQuality = 20))

        val aligned = result.reads.single()
        assertEquals(1, aligned.mismatches.size)
        assertEquals(MismatchKind.LOW_QUALITY, aligned.mismatches.single().kind)
        assertEquals(0, aligned.readStart)
    }
}
