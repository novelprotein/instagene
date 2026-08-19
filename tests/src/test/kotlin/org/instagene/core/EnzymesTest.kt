package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzymesTest {

    @Test
    fun ecoRiProperties() {
        val eco = Enzymes.require("EcoRI")
        assertEquals("GAATTC", eco.site)
        assertEquals(1, eco.topCut)
        assertEquals(5, eco.bottomCut)
        assertEquals(EndType.FIVE_PRIME_OVERHANG, eco.endType)
        assertEquals("G^AATTC", eco.notation())
        assertTrue(eco.isPalindromic)
        assertEquals(4, eco.overhangLength)
    }

    @Test
    fun overhangPolarity() {
        assertEquals(EndType.THREE_PRIME_OVERHANG, Enzymes.require("KpnI").endType)
        assertEquals(EndType.THREE_PRIME_OVERHANG, Enzymes.require("PstI").endType)
        assertEquals(EndType.BLUNT, Enzymes.require("EcoRV").endType)
        assertEquals(EndType.BLUNT, Enzymes.require("SmaI").endType)
        assertTrue(Enzymes.require("KpnI").overhangLength < 0)
        assertEquals(0, Enzymes.require("SmaI").overhangLength)
    }

    @Test
    fun xmaIAndSmaIShareSiteButDifferCuts() {
        val sma = Enzymes.require("SmaI")
        val xma = Enzymes.require("XmaI")
        assertEquals(sma.site, xma.site)
        assertEquals(3, sma.topCut)
        assertEquals(1, xma.topCut)
    }

    @Test
    fun catalogIsSortedAndUnique() {
        val names = Enzymes.ALL.map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
        assertEquals(names.size, names.toSet().size)
        assertTrue(Enzymes.ALL.isNotEmpty())
    }

    @Test
    fun everyBuiltinHasAnIndividualShortDescriptionAndSource() {
        val keys = Enzymes.ALL.map { it.name.lowercase() }.toSet()
        assertEquals(keys, Enzymes.BUILTIN_DESCRIPTIONS.keys)
        assertEquals(keys, Enzymes.BUILTIN_INFO.keys)
        assertEquals(Enzymes.ALL.size, Enzymes.BUILTIN_INFO.values.map { it.sourceUrl }.toSet().size)
        for (enzyme in Enzymes.ALL) {
            val key = enzyme.name.lowercase()
            val description = Enzymes.BUILTIN_DESCRIPTIONS.getValue(key)
            val info = Enzymes.BUILTIN_INFO.getValue(key)
            assertTrue(info.sourceUrl.isNotBlank())
            assertTrue(!info.sourceUrl.contains("alphabetized-list-of-recognition-specificities"))
            assertTrue(info.sourceUrl.contains("/enz/") || info.sourceUrl.contains("/products/"))
            assertTrue(description.length in 20..120)
            assertTrue(!description.contains(enzyme.site))
        }
    }

    @Test
    fun individuallyResearchedFunctionalNotesCoverSpecialMethylationCases() {
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("dpni").contains("template plasmid after PCR"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("bcli").contains("dam methylation"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("clai").contains("CpG methylation"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("eagi").contains("CpG methylation"))
    }

    @Test
    fun simpleDescriptionsCallOutPracticalCloningCases() {
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("haeiii").contains("frequent-cutting"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("noti").contains("rare-cutting"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("bamhi").contains("BglII"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("ecori").contains("MfeI"))
        assertTrue(Enzymes.BUILTIN_DESCRIPTIONS.getValue("ndei").contains("start codon"))
    }

    @Test
    fun findAndRequireAreCaseInsensitive() {
        assertEquals("BamHI", Enzymes.find(" bamhi ")!!.name)
        assertNull(Enzymes.find("NotAnEnzyme"))
        assertFailsWith<IllegalArgumentException> { Enzymes.require("Nope") }
    }

    @Test
    fun parseListSplitsOnCommonSeparators() {
        val list = Enzymes.parseList("EcoRI, BamHI; HindIII  XbaI")
        assertEquals(listOf("EcoRI", "BamHI", "HindIII", "XbaI"), list.map { it.name })
        assertTrue(Enzymes.parseList(" , ; ").isEmpty())
        assertFailsWith<IllegalArgumentException> { Enzymes.parseList("EcoRI,Fake") }
    }

    @Test
    fun poolMergesBuiltinsWithCustomAndDeduplicatesCaseInsensitively() {
        val custom = listOf(
            Enzyme("MegaI", "GATCGA", 1, 5),
            Enzyme("ecori", "GAATTC", 1, 5), // duplicate of a built-in, case-insensitively
        )
        val pool = Enzymes.pool(custom)
        assertEquals(Enzymes.ALL.size + 1, pool.size) // MegaI added, ecori merged away
        assertTrue(pool.any { it.name == "MegaI" })
        assertEquals(1, pool.count { it.name.equals("EcoRI", ignoreCase = true) })
        // Built-ins win the deduplication.
        assertEquals("EcoRI", pool.first { it.name.equals("EcoRI", ignoreCase = true) }.name)
    }

    @Test
    fun poolWithNoCustomReturnsBuiltins() {
        assertEquals(Enzymes.ALL, Enzymes.pool(emptyList()))
    }

    @Test
    fun enzymesForRestrictsToNamedSetOrReturnsWholePool() {
        val pool = Enzymes.pool()
        val subset = Enzymes.enzymesFor(pool, listOf("EcoRI", "bamhi"))
        // Order follows the pool (alphabetical), not the requested order.
        assertEquals(listOf("BamHI", "EcoRI"), subset.map { it.name })
        // An empty enabled list means everything is active.
        assertEquals(pool, Enzymes.enzymesFor(pool, emptyList()))
        // Unknown names are ignored silently.
        assertTrue(Enzymes.enzymesFor(pool, listOf("Ghost")).isEmpty())
    }

    @Test
    fun validateNewAcceptsNovelEnzymesAndRejectsBadInput() {
        assertNull(Enzymes.validateNew("MegaI", "GATCGA", 1, 5))
        assertTrue(Enzymes.validateNew("", "GATCGA", 1, 5) != null)
        assertTrue(Enzymes.validateNew("With Space", "GATCGA", 1, 5) != null)
        assertTrue(Enzymes.validateNew("MegaI", "GATC", 1, 5) != null) // non-IUPAC character
        assertTrue(Enzymes.validateNew("MegaI", "GATCGA", 8, 5) != null) // cut beyond site length
    }
}
