package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DigestTest {

    @Test
    fun ecoRiCutOnKnownSite() {
        val seq = Seq(bases = "NNNGAATTCNNN")
        val eco = Enzymes.require("EcoRI")
        val sites = Digest.cutSites(seq, eco)
        assertEquals(1, sites.size)
        val site = sites.single()
        assertEquals(3, site.recognitionStart)
        assertEquals(4, site.topCut) // 3 + 1
        assertEquals(8, site.bottomCut) // 3 + 5
        assertEquals(Strand.FORWARD, site.strand)
        assertEquals("AATT", Digest.stickyEnd(seq, site).overhang)
        assertEquals(EndType.FIVE_PRIME_OVERHANG, Digest.stickyEnd(seq, site).type)
        val frags = Digest.digest(seq, listOf(eco))
        val overhangs = frags.flatMap { listOf(it.leftEnd.overhang, it.rightEnd.overhang) }.filter { it.isNotEmpty() }
        assertTrue(overhangs.any { it.equals("AATT", ignoreCase = true) })
    }

    @Test
    fun emptyOrShortSequenceYieldsNoCuts() {
        val eco = Enzymes.require("EcoRI")
        assertTrue(Digest.cutSites(Seq(bases = ""), eco).isEmpty())
        assertTrue(Digest.cutSites(Seq(bases = "GAATT"), eco).isEmpty())
    }

    @Test
    fun circularSiteSpanningOrigin() {
        val seq = Seq(bases = "AATTCXXXG", topology = Topology.CIRCULAR)
        val sites = Digest.cutSites(seq, Enzymes.require("EcoRI"))
        assertEquals(1, sites.size)
        assertEquals(8, sites.single().recognitionStart)
    }

    @Test
    fun linearDoubleDigestFragmentCount() {
        val seq = TestSequenceFixtures.restrictionBackbone
        val enzymes = listOf(Enzymes.require("EcoRI"), Enzymes.require("HindIII"))
        val sites = Digest.cutSites(seq, enzymes)
        val frags = Digest.digest(seq, enzymes)
        assertEquals(sites.size + 1, frags.size)
        assertEquals(seq.bases, frags.joinToString("") { it.bases })
    }

    @Test
    fun circularSingleCutOneFragment() {
        val seq = Seq(bases = "NNNGAATTCNNN", topology = Topology.CIRCULAR)
        val frags = Digest.digest(seq, listOf(Enzymes.require("EcoRI")))
        assertEquals(1, frags.size)
        assertEquals(seq.length, frags.single().length)
    }

    @Test
    fun circularDoubleDigestFragmentsReassemble() {
        val seq = Seq(bases = "GAATTCCGGATCCGGAATTCG", topology = Topology.CIRCULAR)
        val enzymes = listOf(Enzymes.require("EcoRI"), Enzymes.require("BamHI"))
        val frags = Digest.digest(seq, enzymes)
        assertEquals(3, frags.size)
        assertEquals(seq.length, frags.sumOf { it.length })
        val reassembled = frags.joinToString("") { it.bases }
        assertTrue((reassembled + reassembled).contains(seq.bases))
    }

    @Test
    fun noEnzymeYieldsSingleBluntFragment() {
        val feat = Feature("marker", start = 1, end = 4)
        val seq = Seq(bases = "ACGTAC", features = listOf(feat))
        val frags = Digest.digest(seq, emptyList())
        assertEquals(1, frags.size)
        assertTrue(frags.single().leftEnd.isBlunt)
        assertTrue(frags.single().rightEnd.isBlunt)
        assertEquals(seq.bases, frags.single().bases)
        assertEquals(1, frags.single().features.size)
    }

    @Test
    fun stickyEndCompatibility() {
        val a = StickyEnd(EndType.FIVE_PRIME_OVERHANG, "AATT", "EcoRI")
        val b = StickyEnd(EndType.FIVE_PRIME_OVERHANG, "aatt", "EcoRI")
        val c = StickyEnd(EndType.FIVE_PRIME_OVERHANG, "GATC", "BamHI")
        assertTrue(a.isCompatibleWith(b))
        assertFalse(a.isCompatibleWith(c))
        assertTrue(StickyEnd.BLUNT.isCompatibleWith(StickyEnd.BLUNT))
    }

    @Test
    fun enzymesCuttingAndCutCountsOnMcs() {
        val seq = TestSequenceFixtures.restrictionBackbone
        val unique = Digest.enzymesCutting(seq, times = 1)
        assertTrue(unique.any { it.name == "EcoRI" })
        assertTrue(unique.any { it.name == "BamHI" })
        val counts = Digest.cutCounts(seq, listOf(Enzymes.require("EcoRI"), Enzymes.require("NotI")))
        assertEquals(1, counts[Enzymes.require("EcoRI")])
        assertEquals(0, counts[Enzymes.require("NotI")])
    }

    @Test
    fun countSitesReportsBoundedProgressAndHonorsCancellation() {
        val seq = Seq(bases = "A".repeat(100_000))
        val progress = ArrayList<Pair<Int, Int>>()
        var checks = 0

        assertFailsWith<java.util.concurrent.CancellationException> {
            Digest.countSites(
                seq,
                Enzymes.require("EcoRI"),
                cancellationRequested = { checks >= 2 },
                progress = { scanned, total ->
                    progress += scanned to total
                    checks++
                },
            )
        }

        assertTrue(progress.isNotEmpty())
        assertEquals(seq.length - Enzymes.require("EcoRI").siteLength + 1, progress.first().second)
        assertTrue(progress.zipWithNext().all { (before, after) -> before.first < after.first })
    }

    @Test
    fun countSitesReportsTheFinalPositionWhenItCompletes() {
        val seq = Seq(bases = "A".repeat(100_003))
        val progress = ArrayList<Pair<Int, Int>>()

        assertEquals(0, Digest.countSites(seq, Enzymes.require("EcoRI"), progress = { scanned, total -> progress += scanned to total }))

        val total = seq.length - Enzymes.require("EcoRI").siteLength + 1
        assertEquals(total to total, progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> before.first < after.first })
    }

    @Test
    fun fragmentToSeqIsLinear() {
        val f = Fragment("ACGT", StickyEnd.BLUNT, StickyEnd.BLUNT, sourceName = "x", start = 10)
        val seq = f.toSeq()
        assertEquals(Topology.LINEAR, seq.topology)
        assertEquals("ACGT", seq.bases)
        assertTrue(seq.name.contains("11"))
    }
}
