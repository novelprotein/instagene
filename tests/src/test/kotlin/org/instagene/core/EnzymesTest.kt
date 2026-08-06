package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun findAndRequireAreCaseInsensitive() {
        assertEquals("BamHI", Enzymes.find(" bamhi ")!!.name)
        assertNull(Enzymes.find("NotAnEnzyme"))
        assertFailsWith<IllegalArgumentException> { Enzymes.require("Nope") }
    }

    @Test
    fun parseListSplitsOnCommonSeparators() {
        val list = Enzymes.parseList("EcoRI, BamHI; HinDIII  XbaI")
        assertEquals(listOf("EcoRI", "BamHI", "HinDIII", "XbaI"), list.map { it.name })
        assertTrue(Enzymes.parseList(" , ; ").isEmpty())
        assertFailsWith<IllegalArgumentException> { Enzymes.parseList("EcoRI,Fake") }
    }
}
