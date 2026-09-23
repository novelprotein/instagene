package org.instagene.app.gui

import org.instagene.app.gui.theme.Palette
import org.instagene.app.gui.theme.ThemeManager
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {

    /** Runs [block] on the EDT and rethrows any failure where the test thread can see it. */
    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun baseColorKnownAndUnknown() {
        assertEquals(Palette.baseColor('A'), Color(0x2E, 0x8B, 0x57))
        assertEquals(Palette.baseColor('a'), Color(0x2E, 0x8B, 0x57))
        assertEquals(Palette.baseColor('C'), Color(0x1E, 0x6F, 0xBA))
        assertEquals(Palette.baseColor('G'), Color(0xC8, 0x7A, 0x0E))
        assertEquals(Palette.baseColor('T'), Color(0xC0, 0x39, 0x2B))
        assertEquals(Palette.baseColor('U'), Color(0xB0, 0x3A, 0x8B))
        assertEquals(Palette.baseColor('N'), Color(0x8A, 0x8A, 0x8A))
        assertEquals(Palette.baseColor('X'), Palette.MUTED)
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

    @Test
    fun colorsFollowTheActiveThemeInsteadOfAHardcodedSplit() {
        try {
            onEdt {
                assertTrue(ThemeManager.apply("FlatOneDarkIJTheme"))
                assertEquals(UIManager.getColor("Label.foreground"), Palette.TEXT)
                assertEquals(UIManager.getColor("Table.gridColor"), Palette.GRID)
                assertEquals(UIManager.getColor("Panel.background"), Palette.BACKGROUND)
                assertEquals(UIManager.getColor("Component.accentColor"), Palette.ACCENT)
                assertEquals(UIManager.getColor("Component.borderColor"), Palette.MAP_LABEL_BORDER)

                assertTrue(ThemeManager.apply("FlatSolarizedLightIJTheme"))
                assertEquals(UIManager.getColor("Label.foreground"), Palette.TEXT)
                assertEquals(UIManager.getColor("Table.gridColor"), Palette.GRID)
                assertEquals(UIManager.getColor("Panel.background"), Palette.BACKGROUND)
            }
        } finally {
            onEdt { ThemeManager.apply(ThemeManager.DEFAULT_THEME) }
        }
    }
}
