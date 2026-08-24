package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnzymeAnalysisTest {

    @Test
    fun cpgCatalogFindsSites() {
        val seq = Seq(name = "test", bases = "A".repeat(50) + "CG" + "A".repeat(30) + "CG" + "A".repeat(50), kind = SeqKind.DNA)
        val catalog = EnzymeAnalysis.cpgCatalog(seq)
        assertEquals(2, catalog.size)
        assertEquals(51, catalog[0].position)
        assertEquals(83, catalog[1].position)
    }

    @Test
    fun cpgCatalogClassifiesIslandContext() {
        val gcRich = "CGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCG"
        val bg = "A".repeat(200)
        val seq = Seq(name = "island_test", bases = bg + gcRich + bg, kind = SeqKind.DNA)
        val catalog = EnzymeAnalysis.cpgCatalog(seq)
        val islandEntries = catalog.filter { it.context == CpGContext.ISLAND }
        assertTrue(islandEntries.isNotEmpty(), "Should find CpG entries in island context")
    }

    @Test
    fun cpgCatalogClassifiesOpenSeaContext() {
        val atRich = "ATATATATATATATATATATATCGATATATATATATATATATATATATATATAT"
        val seq = Seq(name = "opensea_test", bases = "A".repeat(500) + atRich + "A".repeat(500), kind = SeqKind.DNA)
        val catalog = EnzymeAnalysis.cpgCatalog(seq)
        val openSea = catalog.filter { it.context == CpGContext.OPEN_SEA }
        assertTrue(openSea.isNotEmpty(), "Should find CpG entries in open sea context")
    }

    @Test
    fun methylationSensitiveComparisonReportsHpaIIMspI() {
        val seq = Seq(name = "ccgg_test", bases = "A".repeat(10) + "CCGG" + "A".repeat(10) + "CCGG" + "A".repeat(10), kind = SeqKind.DNA)
        val reports = EnzymeAnalysis.methylationSensitiveComparison(seq)
        val hpa = reports.firstOrNull { it.pairLabel == "HpaII/MspI" }
        assertTrue(hpa != null, "Should report HpaII/MspI pair")
        assertTrue(hpa.totalSites >= 2, "Should find at least 2 CCGG sites")
        assertTrue(hpa.methylBlockedSites >= 2, "All CCGG sites overlap CpG")
    }

    @Test
    fun methylationSensitiveComparisonEmptySeq() {
        val seq = Seq(name = "empty", bases = "A".repeat(20), kind = SeqKind.DNA)
        val reports = EnzymeAnalysis.methylationSensitiveComparison(seq)
        for ((_, _, _, totalSites) in reports) {
            assertEquals(0, totalSites, "No sites in poly-A sequence")
        }
    }
}
