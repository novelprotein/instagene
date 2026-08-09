package org.instagene.app.gui

import org.instagene.app.gui.prefs.PrefsStore
import org.instagene.app.gui.prefs.UserPrefs
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeManagerTest {

    @Test
    fun registryListsUniqueThemesWithLightAndDarkOptions() {
        val ids = ThemeManager.themes.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "theme ids must be unique")
        assertTrue(ids.contains(ThemeManager.DEFAULT_THEME))
        assertTrue(ThemeManager.themes.any { it.dark })
        assertTrue(ThemeManager.themes.any { !it.dark })
    }

    @Test
    fun applyingAThemeInstallsItAndTracksTheCurrentOne() {
        try {
            assertTrue(ThemeManager.apply("FlatLightLaf"))
            assertEquals("FlatLightLaf", ThemeManager.current())
            assertFalse(ThemeManager.isDark("FlatLightLaf"))

            assertTrue(ThemeManager.apply("FlatDarculaLaf"))
            assertEquals("FlatDarculaLaf", ThemeManager.current())
            assertTrue(ThemeManager.isDark("FlatDarculaLaf"))
        } finally {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        }
    }

    @Test
    fun unknownThemeIsRejected() {
        assertFalse(ThemeManager.apply("NoSuchTheme"))
    }

    @Test
    fun themeIsPersistedInPrefs() {
        assertEquals("FlatDraculaIJTheme", UserPrefs().theme)
        val prefs = UserPrefs(theme = "FlatOneDarkIJTheme")
        assertEquals("FlatOneDarkIJTheme", prefs.theme)
        assertEquals("One Dark", ThemeManager.displayName(prefs.theme))
    }

    @Test
    fun themeSurvivesPrefsRoundTripAndIsRestoredOnLaunch() {
        val dir = Files.createTempDirectory("instagene-theme").toFile()
        dir.deleteOnExit()
        val file = File(dir, "prefs.json")

        // Selecting a theme via the View menu persists it to disk.
        onEdt {
            val prefs = Prefs(PrefsStore(file))
            val doc = SeqDocument(org.instagene.core.Seq(bases = "ACGT"))
            val view = SequenceView(doc)
            val themes = ViewMenu(doc, view, prefs).create()
            val themeMenu = themes.menuComponents.filterIsInstance<javax.swing.JMenu>().first { it.text == "Theme" }
            val oneDark = themeMenu.menuComponents.filterIsInstance<javax.swing.JRadioButtonMenuItem>()
                .first { it.text == "One Dark" }
            oneDark.doClick()
            assertEquals("FlatOneDarkIJTheme", prefs.value.theme)
        }

        // A fresh launch reads the theme back from disk and applies it.
        val prefs = Prefs(PrefsStore(file))
        assertEquals("FlatOneDarkIJTheme", prefs.value.theme)
        assertTrue(ThemeManager.apply(prefs.value.theme))
        assertEquals("FlatOneDarkIJTheme", ThemeManager.current())
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) return block()
        SwingUtilities.invokeAndWait(block)
    }
}
