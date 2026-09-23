package org.instagene.app.gui

import org.instagene.app.gui.dialog.FontPreferencesPanel
import org.instagene.app.gui.prefs.PrefsStore
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.Seq
import org.instagene.app.gui.prefs.UserPrefs
import org.instagene.app.gui.theme.ThemeManager
import java.nio.file.Files
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.*

class FontPreferencesTest {
    @Test
    fun changingFontsPreservesEveryThemesColors() = SwingUtilities.invokeAndWait {
        val keys = listOf("Panel.background", "Label.foreground", "TextField.background", "TextField.foreground", "Component.accentColor")
        try {
            for (theme in ThemeManager.themes) {
                assertTrue(ThemeManager.apply(theme.id))
                val colors = keys.map { javax.swing.UIManager.getColor(it) }
                for (size in listOf(20, 24, 0)) {
                    assertTrue(ThemeManager.apply(theme.id, if (size == 0) null else "Serif", size))
                    assertEquals(colors, keys.map { javax.swing.UIManager.getColor(it) }, "${theme.id}, font size $size")
                    assertEquals(ThemeManager.current(), theme.id)
                }
            }
        } finally {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        }
    }

    @Test
    fun savedFontsLoadAtStartupAndRepopulatePreferences() = SwingUtilities.invokeAndWait {
        val dir = Files.createTempDirectory("font-startup").toFile()
        try {
            val file = dir.resolve("prefs.json")
            val prefs = Prefs(PrefsStore(file))
            prefs.update { it.copy(interfaceFontFamily = "Serif", interfaceFontSize = 22) }
            val loaded = Prefs(PrefsStore(file))
            Class.forName("org.instagene.app.gui.GuiMainKt")
                .getDeclaredMethod("applySavedTheme", Prefs::class.java)
                .apply { isAccessible = true }.invoke(null, loaded)
            assertEquals("Serif", JLabel().font.family)
            assertEquals(22, JLabel().font.size)
            val panel = FontPreferencesPanel(loaded.value)
            assertEquals("Serif", panel.selectedFamily)
            assertEquals(22, panel.selectedSize)
            val view = SequenceView(SeqDocument(Seq("ACGT".repeat(100))))
            try {
                assertEquals(22, view.fontSize())
            } finally {
                view.dispose()
            }
        } finally {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
            dir.deleteRecursively()
        }
    }

    @Test
    fun sequenceFontRefreshChangesRenderingAndZoomKeepsTheChosenSize() = SwingUtilities.invokeAndWait {
        ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        val view = SequenceView(SeqDocument(Seq("ACGT".repeat(100))))
        fun render(): IntArray {
            val image = java.awt.image.BufferedImage(900, 500, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { view.paint(graphics) } finally { graphics.dispose() }
            return image.getRGB(0, 0, 900, 500, null, 0, 900)
        }
        try {
            view.setSize(900, 500)
            val before = render()
            val height = view.preferredSize.height
            ThemeManager.apply(ThemeManager.DEFAULT_THEME, "Serif", 22)
            view.refreshTheme()
            assertEquals(22, view.fontSize())
            assertFalse(before.contentEquals(render()))
            assertTrue(view.preferredSize.height > height)
            view.setFontSize(24)
            val fontField = SequenceView::class.java.getDeclaredField("baseFont").apply { isAccessible = true }
            assertEquals("Monospaced", (fontField.get(view) as java.awt.Font).family)
            view.refreshTheme()
            assertEquals(24, view.fontSize(), "An unchanged preference must preserve zoom")
            view.resetZoom()
            assertEquals(22, view.fontSize())
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
            view.refreshTheme()
            assertEquals(14, view.fontSize())
        } finally {
            view.dispose()
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        }
    }

    @Test
    fun fontPersistsAndOlderPreferencesUseThemeDefaults() {
        val file = Files.createTempDirectory("font-prefs").resolve("prefs.json").toFile()
        file.writeText("{}")
        assertNull(PrefsStore(file).load().interfaceFontFamily)
        assertEquals(0, PrefsStore(file).load().interfaceFontSize)
        val prefs = UserPrefs(interfaceFontFamily = "Serif", interfaceFontSize = 20)
        PrefsStore(file).save(prefs)
        assertEquals(prefs, PrefsStore(file).load())
    }

    @Test
    fun fontAppliesToControlsAndCanReturnToThemeDefaults() = SwingUtilities.invokeAndWait {
        try {
            ThemeManager.apply("FlatLightLaf")
            val defaultFont = JLabel().font
            assertTrue(ThemeManager.apply("FlatLightLaf", "Serif", 20))
            assertEquals("Serif", JLabel().font.family)
            assertEquals(20, JLabel().font.size)
            assertTrue(ThemeManager.apply("FlatDarculaLaf", "Serif", 20))
            assertEquals(20, JLabel().font.size)
            ThemeManager.apply("FlatLightLaf")
            assertEquals(defaultFont, JLabel().font)
            ThemeManager.apply("FlatLightLaf", "missing-font-family")
            assertEquals(defaultFont, JLabel().font)
        } finally {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        }
    }

    @Test
    fun previewChangesAreStagedUntilPreferencesAreAccepted() = SwingUtilities.invokeAndWait {
        val current = JLabel().font
        val panel = FontPreferencesPanel(UserPrefs())
        val family = panel.components.filterIsInstance<JComboBox<*>>().single { it.name == "interfaceFontFamily" }
        val size = panel.components.filterIsInstance<JComboBox<*>>().single { it.name == "interfaceFontSize" }
        family.selectedItem = "Serif"
        size.selectedItem = "22 pt"
        assertEquals("Serif", panel.selectedFamily)
        assertEquals(22, panel.selectedSize)
        val preview = panel.components.filterIsInstance<JLabel>().single { it.name == "interfaceFontPreview" }
        assertEquals("Serif", preview.font.family)
        assertEquals(22, preview.font.size)
        assertEquals(current, JLabel().font)
        family.selectedIndex = 0
        size.selectedIndex = 0
        assertNull(panel.selectedFamily)
        assertEquals(0, panel.selectedSize)
    }
}
