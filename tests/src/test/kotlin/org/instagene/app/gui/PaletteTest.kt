package org.instagene.app.gui

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteTest {

    @Test
    fun baseColorKnownAndUnknown() {
        assertEquals(Color(0x2E, 0x8B, 0x57), Palette.baseColor('A'))
        assertEquals(Color(0x2E, 0x8B, 0x57), Palette.baseColor('a'))
        assertEquals(Color(0x1E, 0x6F, 0xBA), Palette.baseColor('C'))
        assertEquals(Color(0xC8, 0x7A, 0x0E), Palette.baseColor('G'))
        assertEquals(Color(0xC0, 0x39, 0x2B), Palette.baseColor('T'))
        assertEquals(Color(0xB0, 0x3A, 0x8B), Palette.baseColor('U'))
        assertEquals(Color(0x8A, 0x8A, 0x8A), Palette.baseColor('N'))
        assertEquals(Palette.MUTED, Palette.baseColor('X'))
    }

    @Test
    fun featureColorWraps() {
        assertEquals(Palette.featureColor(0), Palette.featureColor(8))
        assertEquals(Palette.featureColor(1), Palette.featureColor(9))
        assertEquals(Palette.featureColor(7), Palette.featureColor(-1))
    }

    @Test
    fun translucentPreservesRgb() {
        val c = Color(10, 20, 30)
        val t = Palette.translucent(c, 128)
        assertEquals(10, t.red)
        assertEquals(20, t.green)
        assertEquals(30, t.blue)
        assertEquals(128, t.alpha)
    }
}
