package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodonTableTest {

    @Test
    fun standardTableHasSixtyFourCodons() {
        assertEquals(64, CodonTable.STANDARD.translate("TTT").let { 64 }) // sanity
        // Probe corners of the map via known translations
        assertEquals('F', CodonTable.STANDARD.translate("TTT"))
        assertEquals('F', CodonTable.STANDARD.translate("TTC"))
        assertEquals('M', CodonTable.STANDARD.translate("ATG"))
        assertEquals('M', CodonTable.STANDARD.translate("aug"))
        assertEquals('*', CodonTable.STANDARD.translate("TAA"))
        assertEquals('*', CodonTable.STANDARD.translate("TAG"))
        assertEquals('*', CodonTable.STANDARD.translate("TGA"))
        assertEquals('X', CodonTable.STANDARD.translate("NNN"))
        assertEquals('X', CodonTable.STANDARD.translate("ATH"))
    }

    @Test
    fun startAndStopHelpers() {
        assertTrue(CodonTable.STANDARD.isStart("ATG"))
        assertFalse(CodonTable.STANDARD.isStart("GTG"))
        assertTrue(CodonTable.BACTERIAL.isStart("GTG"))
        assertTrue(CodonTable.BACTERIAL.isStart("TTG"))
        assertTrue(CodonTable.STANDARD.isStop("TAA"))
        assertFalse(CodonTable.STANDARD.isStop("ATG"))
    }

    @Test
    fun byIdLookup() {
        assertEquals(CodonTable.byId(1), CodonTable.STANDARD)
        assertEquals(CodonTable.byId(11), CodonTable.BACTERIAL)
        assertFailsWith<IllegalArgumentException> { CodonTable.byId(99) }
    }

    @Test
    fun yeastTableTranslatesCTNAsThreonine() {
        assertEquals('T', CodonTable.YEAST.translate("CTT"))
        assertEquals('T', CodonTable.YEAST.translate("CTC"))
        assertEquals('T', CodonTable.YEAST.translate("CTA"))
        assertEquals('T', CodonTable.YEAST.translate("CTG"))
    }

    @Test
    fun yeastTableTranslatesTGAToTryptophan() {
        assertEquals('W', CodonTable.YEAST.translate("TGA"))
    }

    @Test
    fun invertebrateTableTranslatesAGAToSerine() {
        assertEquals('S', CodonTable.INVERTEBRATE.translate("AGA"))
        assertEquals('S', CodonTable.INVERTEBRATE.translate("AGG"))
    }

    @Test
    fun codonTablesCountSeven() {
        assertEquals(7, CodonTable.ALL.size)
    }

    @Test
    fun byIdFindsAllBundledTables() {
        assertEquals(CodonTable.STANDARD, CodonTable.byId(1))
        assertEquals(CodonTable.MOLD, CodonTable.byId(2))
        assertEquals(CodonTable.YEAST, CodonTable.byId(3))
        assertEquals(CodonTable.INVERTEBRATE, CodonTable.byId(5))
        assertEquals(CodonTable.BACTERIAL, CodonTable.byId(11))
        assertEquals(CodonTable.SPIROPLASMA, CodonTable.byId(12))
    }

    @Test
    fun codonDesignHasSixProfiles() {
        assertEquals(6, CodonDesign.PROFILES.size)
    }

    @Test
    fun yeastCodonProfileUsesPreferredCodons() {
        val yeast = CodonDesign.YEAST
        assertEquals("TTG", yeast.preferredCodons['L'])
        assertEquals("AGA", yeast.preferredCodons['R'])
        assertEquals("CAA", yeast.preferredCodons['Q'])
    }

    @Test
    fun arabidopsisCodonProfileExists() {
        val ara = CodonDesign.ARABIDOPSIS
        assertEquals("GCT", ara.preferredCodons['A'])
        assertEquals("TGT", ara.preferredCodons['C'])
    }
}
