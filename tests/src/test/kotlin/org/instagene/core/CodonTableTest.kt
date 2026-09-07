package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodonTableTest {

    @Test
    fun standardTableHasSixtyFourCodons() {
        assertEquals('F', CodonTable.STANDARD.translate("TTT"))
        assertEquals('F', CodonTable.STANDARD.translate("TTC"))
        assertEquals('M', CodonTable.STANDARD.translate("ATG"))
        assertEquals('M', CodonTable.STANDARD.translate("aug"))
        assertEquals('*', CodonTable.STANDARD.translate("TAA"))
        assertEquals('*', CodonTable.STANDARD.translate("TAG"))
        assertEquals('*', CodonTable.STANDARD.translate("TGA"))
        assertEquals('X', CodonTable.STANDARD.translate("NNN"))
        assertEquals('X', CodonTable.STANDARD.translate("ATH"))
        assertEquals('X', CodonTable.STANDARD.translate("ATGG"))
        assertEquals('X', CodonTable.STANDARD.translate(""))
    }

    @Test
    fun startAndStopHelpers() {
        assertTrue(CodonTable.STANDARD.isStart("ATG"))
        assertFalse(CodonTable.STANDARD.isStart("GTG"))
        assertTrue(CodonTable.BACTERIAL.isStart("GTG"))
        assertTrue(CodonTable.BACTERIAL.isStart("TTG"))
        assertTrue(CodonTable.BACTERIAL.isStart("CTG"))
        assertFalse(CodonTable.STANDARD.isStart("ATGG"))
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
    fun mitochondrialTablesUseNcbIAssignmentsAndStarts() {
        assertEquals('M', CodonTable.VERTEBRATE_MITOCHONDRIAL.translate("ATA"))
        assertEquals('*', CodonTable.VERTEBRATE_MITOCHONDRIAL.translate("AGA"))
        assertTrue(CodonTable.VERTEBRATE_MITOCHONDRIAL.isStart("AUA"))
        assertTrue(CodonTable.YEAST.isStart("GTG"))
        assertEquals('R', CodonTable.MOLD.translate("AGA"))
        assertTrue(CodonTable.MOLD.isStart("ATC"))
        assertTrue(CodonTable.INVERTEBRATE.isStart("ATC"))
        assertTrue(CodonTable.ALTERNATIVE_YEAST.isStart("CTG"))
        assertEquals('C', CodonTable.EUPLOTID.translate("TGA"))
        assertEquals('S', CodonTable.ALTERNATIVE_YEAST.translate("CTG"))
    }

    @Test
    fun invertebrateTableTranslatesAGAToSerine() {
        assertEquals('S', CodonTable.INVERTEBRATE.translate("AGA"))
        assertEquals('S', CodonTable.INVERTEBRATE.translate("AGG"))
    }

    @Test
    fun codonTablesUseUniqueNcbIIds() {
        assertEquals(9, CodonTable.ALL.size)
        assertEquals(9, CodonTable.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun correctedYeastAndEchinodermAssignmentsMatchNcbi() {
        assertEquals('M', CodonTable.YEAST.translate("ATA"))
        assertEquals('T', CodonTable.YEAST.translate("CTG"))
        assertEquals('N', CodonTable.ECHINODERM.translate("AAA"))
        assertEquals(setOf("ATG", "GTG"), CodonTable.ECHINODERM.startCodons)
    }

    @Test
    fun byIdFindsAllBundledTables() {
        assertEquals(CodonTable.byId(1), CodonTable.STANDARD)
        assertEquals(CodonTable.byId(2), CodonTable.VERTEBRATE_MITOCHONDRIAL)
        assertEquals(CodonTable.byId(3), CodonTable.YEAST)
        assertEquals(CodonTable.byId(4), CodonTable.MOLD)
        assertEquals(CodonTable.byId(5), CodonTable.INVERTEBRATE)
        assertEquals(CodonTable.byId(9), CodonTable.ECHINODERM)
        assertEquals(CodonTable.byId(10), CodonTable.EUPLOTID)
        assertEquals(CodonTable.byId(11), CodonTable.BACTERIAL)
        assertEquals(CodonTable.byId(12), CodonTable.ALTERNATIVE_YEAST)
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
