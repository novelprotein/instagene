package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrisprDesignTest {
    @Test
    fun findsConcreteNgGOnBothStrands() {
        val sequence = "A".repeat(20) + "AGG" + "CCA" + "C".repeat(20)
        val guides = CrisprDesign.design(Seq(name = "target", bases = sequence)).guides

        assertEquals(2, guides.size)
        assertTrue(guides.any { it.strand == Strand.FORWARD && it.sequence == "A".repeat(20) })
        assertTrue(guides.any { it.strand == Strand.REVERSE && it.pam == "CCA" })
        assertTrue(guides.all { it.pam[1] == 'G' && it.pam[2] == 'G' || it.pam[0] == 'C' && it.pam[1] == 'C' })
    }

    @Test
    fun ignoresAmbiguousPamAndReportsWarning() {
        val result = CrisprDesign.design(Seq(name = "target", bases = "A".repeat(20) + "NGG"))
        assertTrue(result.guides.isEmpty())
        assertTrue(result.warnings.any { it.contains("Ambiguous") })
    }

    @Test
    fun rejectsAmbiguousProtospacers() {
        val sequence = "A".repeat(19) + "N" + "AGG"
        val result = CrisprDesign.design(Seq(name = "target", bases = sequence))
        assertTrue(result.guides.isEmpty())
    }

    @Test
    fun circularGuideWrapsOriginAndRetainsTraversalCoordinates() {
        val bases = "C".repeat(30).toCharArray()
        "AGG".forEachIndexed { index, base -> bases[2 + index] = base }
        val result = CrisprDesign.design(
            Seq(name = "circle", bases = String(bases), topology = Topology.CIRCULAR),
            maxGuides = 100,
        )
        val guide = result.guides.first { it.strand == Strand.FORWARD }

        assertEquals(Strand.FORWARD, guide.strand)
        assertEquals(listOf(12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 0, 1), guide.coordinates)
        assertEquals(0, guide.start)
        assertEquals(30, guide.end)
        assertTrue(guide.warnings.any { it.contains("origin") })
    }

    @Test
    fun resultsAreOrderedByGenomicCoordinatesAndExposeGc() {
        val sequence = "G".repeat(20) + "AGG" + "A".repeat(20) + "AGG"
        val guides = CrisprDesign.design(Seq(name = "target", bases = sequence), maxGuides = 10).guides
        assertFalse(guides.isEmpty())
        assertEquals(guides, guides.sortedWith(compareBy<GuideRNA> { it.start }.thenBy { it.end }.thenBy { it.strand.sign }))
        assertEquals(1.0, guides.first().gcContent)
    }
}
