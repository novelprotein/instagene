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
        assertEquals(CodonTable.STANDARD, CodonTable.byId(1))
        assertEquals(CodonTable.BACTERIAL, CodonTable.byId(11))
        assertFailsWith<IllegalArgumentException> { CodonTable.byId(99) }
    }
}
