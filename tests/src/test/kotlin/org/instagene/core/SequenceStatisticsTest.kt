package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertTrue

class SequenceStatisticsTest {

    @Test
    fun cpgIslandsFindsKnownIsland() {
        val gcRich = "CG".repeat(150)
        val bg = "AT".repeat(500)
        val seq = Seq(name = "island_test", bases = bg + gcRich + bg, kind = SeqKind.DNA)
        val islands = SequenceStatistics.cpgIslands(seq)
        assertTrue(islands.isNotEmpty(), "Should detect CpG island in GC-rich region")
        val found = islands.first()
        assertTrue(found.gcContent >= 50.0, "Island GC should be ≥50%")
        assertTrue(found.oeRatio >= 0.6, "Island OE ratio should be ≥0.6")
        assertTrue(found.length >= 200, "Island length should be ≥200bp")
    }

    @Test
    fun cpgIslandsRejectsShortRegion() {
        val shortIsland = "CG".repeat(50)
        val bg = "AT".repeat(1000)
        val seq = Seq(name = "short_test", bases = bg + shortIsland + bg, kind = SeqKind.DNA)
        val islands = SequenceStatistics.cpgIslands(seq)
        assertTrue(islands.isEmpty(), "100bp island should be rejected (< 200bp minimum)")
    }

    @Test
    fun cpgDensityProfileReturnsData() {
        val seq = Seq(name = "density_test", bases = "ATCGCGATCGCGATCGCG" + "AT".repeat(100), kind = SeqKind.DNA)
        val profile = SequenceStatistics.cpgDensityProfile(seq, windowSize = 20, step = 10)
        assertTrue(profile.isNotEmpty(), "CpG density profile should have data points")
        assertTrue(profile.all { it.y >= 0.0 }, "OE ratios should be non-negative")
    }

    @Test
    fun cpgIslandsEmptyForLowGc() {
        val seq = Seq(name = "at_test", bases = "AT".repeat(500), kind = SeqKind.DNA)
        val islands = SequenceStatistics.cpgIslands(seq)
        assertTrue(islands.isEmpty(), "AT-rich sequence should have no CpG islands")
    }

    @Test
    fun cpgDensityProfileEmptyForShortSeq() {
        val seq = Seq(name = "short", bases = "CG", kind = SeqKind.DNA)
        val profile = SequenceStatistics.cpgDensityProfile(seq, windowSize = 100)
        assertTrue(profile.isEmpty(), "Short sequence should return empty profile")
    }
}
