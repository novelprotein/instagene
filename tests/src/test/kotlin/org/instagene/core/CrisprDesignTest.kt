package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertTrue

class CrisprDesignTest {

    @Test
    fun findsPamSites() {
        val seq = Seq(name = "target", bases = "ATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGNGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq)
        assertTrue(result.guides.isNotEmpty(), "Should find at least one PAM site")
    }

    @Test
    fun scoresGuides() {
        val seq = Seq(name = "target", bases = "ATCGATCGATCGATCGATCGATCGATCGATCGATCGNGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq)
        for (guide in result.guides) {
            assertTrue(guide.onTargetScore in 0.0..1.0, "On-target score should be 0-1")
            assertTrue(guide.offTargetScore in 0.0..1.0, "Off-target score should be 0-1")
            assertTrue(guide.gcContent in 0.0..1.0, "GC content should be 0-1")
        }
    }

    @Test
    fun respectsMaxGuides() {
        val seq = Seq(name = "target", bases = "A".repeat(500) + "NGG", kind = SeqKind.DNA)
        val result = CrisprDesign.design(seq, maxGuides = 5)
        assertTrue(result.guides.size <= 5)
    }
}
