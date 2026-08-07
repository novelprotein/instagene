package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SequenceTest {

    @Test
    fun featureRejectsInvalidRanges() {
        assertFailsWith<IllegalArgumentException> { Feature("bad", start = -1, end = 2) }
        assertFailsWith<IllegalArgumentException> { Feature("bad", start = 5, end = 3) }
    }

    @Test
    fun featureDisplayRangeIsOneBasedInclusive() {
        val f = Feature("cds", start = 0, end = 3)
        assertEquals(3, f.length)
        assertEquals("1..3", f.displayRange())
    }

    @Test
    fun strandFlipped() {
        assertEquals(Strand.REVERSE, Strand.FORWARD.flipped())
        assertEquals(Strand.FORWARD, Strand.REVERSE.flipped())
    }

    @Test
    fun linearSubAndBaseAt() {
        val seq = Seq(bases = "ACGTAC")
        assertEquals('G', seq.baseAt(2))
        assertEquals("CGTA", seq.sub(1, 5))
        assertEquals("", seq.sub(3, 3))
        assertFailsWith<IllegalArgumentException> { seq.sub(4, 2) }
    }

    @Test
    fun circularWrapsBaseAtAndSub() {
        val seq = Seq(bases = "ACGT", topology = Topology.CIRCULAR)
        assertEquals('T', seq.baseAt(-1))
        assertEquals('A', seq.baseAt(4))
        assertEquals("TACG", seq.sub(3, 7))
        assertEquals("", Seq(bases = "", topology = Topology.CIRCULAR).sub(0, 0))
    }

    @Test
    fun insertAtShiftsAndExtendsFeatures() {
        val feat = Feature("x", start = 2, end = 5)
        val seq = Seq(bases = "AAAAAA", features = listOf(feat))
        val upstream = seq.insertAt(2, "TT")
        assertEquals("AATTAAAA", upstream.bases)
        assertEquals(Feature("x", start = 4, end = 7), upstream.features.single())

        val inside = seq.insertAt(3, "GG")
        assertEquals("AAAGGAAA", inside.bases)
        assertEquals(Feature("x", start = 2, end = 7), inside.features.single())
    }

    @Test
    fun deleteRangeClipsAndDropsFeatures() {
        val features = listOf(
            Feature("up", start = 0, end = 2),
            Feature("mid", start = 2, end = 5),
            Feature("down", start = 5, end = 8),
            Feature("span", start = 1, end = 6),
        )
        val seq = Seq(bases = "ABCDEFGH", features = features)
        val deleted = seq.deleteRange(2, 5)
        assertEquals("ABFGH", deleted.bases)
        assertEquals(listOf("up", "down", "span"), deleted.features.map { it.name })
        assertEquals(Feature("down", start = 2, end = 5), deleted.features.first { it.name == "down" })
        assertEquals(Feature("span", start = 1, end = 3), deleted.features.first { it.name == "span" })
        assertEquals(seq.bases, seq.deleteRange(3, 3).bases)
    }

    @Test
    fun replaceRangeComposesDeleteAndInsert() {
        val seq = Seq(bases = "AAAAAA", features = listOf(Feature("x", start = 1, end = 4)))
        val replaced = seq.replaceRange(1, 4, "TT")
        assertEquals("ATTAA", replaced.bases)
        assertTrue(replaced.features.isEmpty() || replaced.features.all { it.end <= replaced.length })
    }

    @Test
    fun subSeqClipsFeaturesAndForcesLinear() {
        val seq = Seq(
            bases = "ACGTACGT",
            topology = Topology.CIRCULAR,
            features = listOf(Feature("f", start = 2, end = 6)),
        )
        val sub = seq.subSeq(1, 5)
        assertEquals("CGTA", sub.bases)
        assertEquals(Topology.LINEAR, sub.topology)
        assertEquals(Feature("f", start = 1, end = 4), sub.features.single())
    }

    @Test
    fun rotateOriginDropsStraddlers() {
        val seq = Seq(
            bases = "ABCDEF",
            topology = Topology.CIRCULAR,
            features = listOf(
                Feature("keep", start = 3, end = 5),
                Feature("straddle", start = 4, end = 6),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            Seq(bases = "ACGT", topology = Topology.LINEAR).rotateOrigin(1)
        }
        assertEquals(seq, seq.rotateOrigin(0))
        val rotated = seq.rotateOrigin(3)
        assertEquals("DEFABC", rotated.bases)
        assertEquals(listOf("keep"), rotated.features.map { it.name })
        assertEquals(Feature("keep", start = 0, end = 2), rotated.features.single())
    }

    @Test
    fun reverseComplementMirrorsFeatures() {
        val seq = Seq(
            bases = "GAATTC",
            features = listOf(Feature("eco", start = 0, end = 6, strand = Strand.FORWARD)),
        )
        val rc = seq.reverseComplement("rc")
        assertEquals("rc", rc.name)
        assertEquals("GAATTC", rc.bases) // EcoRI site is palindromic
        assertEquals(Strand.REVERSE, rc.features.single().strand)
        assertEquals(0, rc.features.single().start)
        assertEquals(6, rc.features.single().end)
    }

    @Test
    fun complementWithoutReverse() {
        assertEquals("TGCA", Seq(bases = "ACGT").complement().bases)
    }

    @Test
    fun plusShiftsFeaturesAndRejectsCircular() {
        val a = Seq(bases = "AA", features = listOf(Feature("a", start = 0, end = 2)))
        val b = Seq(bases = "TT", features = listOf(Feature("b", start = 0, end = 2)))
        val joined = a + b
        assertEquals("AATT", joined.bases)
        assertEquals(Feature("b", start = 2, end = 4), joined.features.first { it.name == "b" })
        assertFailsWith<IllegalArgumentException> {
            Seq(bases = "AA", topology = Topology.CIRCULAR) + b
        }
    }

    @Test
    fun withFeatureKeepsSortedByStart() {
        val seq = Seq(bases = "AAAAAA")
            .withFeature(Feature("late", start = 4, end = 5))
            .withFeature(Feature("early", start = 1, end = 2))
        assertEquals(listOf("early", "late"), seq.features.map { it.name })
    }
}
