package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrisprDesignTest {

    @Test
    fun findsPamSites() {
        val seq = Seq(name = "target", bases = "ATCG".repeat(100) + "NGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq)
        assertTrue(result.guides.isNotEmpty(), "Should find at least one PAM site")
    }

    @Test
    fun scoresGuidesSimple() {
        val seq = Seq(name = "target", bases = "ATCG".repeat(100) + "NGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq, scoringMode = ScoringMode.RULESET3_SIMPLE)
        for (guide in result.guides) {
            assertTrue(guide.onTargetScore in 0.0..1.0, "On-target score should be 0-1")
            assertTrue(guide.offTargetScore in 0.0..1.0, "Off-target score should be 0-1")
            assertTrue(guide.gcContent in 0.0..1.0, "GC content should be 0-1")
            assertEquals(ScoringMode.RULESET3_SIMPLE, guide.scoringMode)
        }
    }

    @Test
    fun scoresGuidesFull() {
        val seq = Seq(name = "target", bases = "ATCG".repeat(100) + "NGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq, scoringMode = ScoringMode.RULESET3_FULL)
        for (guide in result.guides) {
            assertTrue(guide.onTargetScore in 0.0..1.0, "On-target score should be 0-1")
            assertTrue(guide.offTargetScore in 0.0..1.0, "Off-target score should be 0-1")
            assertEquals(ScoringMode.RULESET3_FULL, guide.scoringMode)
        }
    }

    @Test
    fun fullAndSimpleRankSimilarly() {
        val seq = Seq(name = "target", bases = "ATCG".repeat(100) + "NGG", kind = SeqKind.DNA)
        val full = CrisprDesign.design(seq, scoringMode = ScoringMode.RULESET3_FULL)
        val simple = CrisprDesign.design(seq, scoringMode = ScoringMode.RULESET3_SIMPLE)
        val fullTop = full.guides.take(3).map { it.sequence }.toSet()
        val simpleTop = simple.guides.take(3).map { it.sequence }.toSet()
        val overlap = fullTop.intersect(simpleTop).size
        assertTrue(overlap >= 1, "Top guides should largely overlap between modes")
    }

    @Test
    fun respectsMaxGuides() {
        val seq = Seq(name = "target", bases = "A".repeat(500) + "NGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq, maxGuides = 5)
        assertTrue(result.guides.size <= 5)
    }

    @Test
    fun defaultModeIsSimple() {
        val seq = Seq(name = "target", bases = "ATCG".repeat(100) + "NGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq)
        for (guide in result.guides) {
            assertEquals(ScoringMode.RULESET3_SIMPLE, guide.scoringMode)
        }
    }
}
