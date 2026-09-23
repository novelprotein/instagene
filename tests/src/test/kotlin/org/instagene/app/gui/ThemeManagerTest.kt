package org.instagene.app.gui

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.PrefsStore
import org.instagene.app.gui.prefs.UserPrefs
import org.instagene.app.gui.theme.ThemeManager
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
        assertEquals("FlatDarculaLaf", UserPrefs().theme)
        assertEquals("FlatDarculaLaf", ThemeManager.DEFAULT_THEME)
        val prefs = UserPrefs(theme = "FlatOneDarkIJTheme")
        assertEquals("FlatOneDarkIJTheme", prefs.theme)
        assertEquals("One Dark", ThemeManager.displayName(prefs.theme))
    }

    @Test
    fun themeSurvivesPrefsRoundTripAndIsRestoredOnLaunch() {
        val dir = Files.createTempDirectory("instagene-theme").toFile()
        dir.deleteOnExit()
        val file = File(dir, "prefs.json")

        // Selecting a theme in Preferences persists it to disk.
        onEdt {
            val prefs = Prefs(PrefsStore(file))
            prefs.update { it.copy(theme = "FlatOneDarkIJTheme") }
            assertEquals("FlatOneDarkIJTheme", prefs.value.theme)
        }

        // A fresh launch reads the theme back from disk and applies it.
        val prefs = Prefs(PrefsStore(file))
        assertEquals("FlatOneDarkIJTheme", prefs.value.theme)
        assertTrue(ThemeManager.apply(prefs.value.theme))
        assertEquals("FlatOneDarkIJTheme", ThemeManager.current())
    }

    @Test
    fun changingAnExistingThemeSurvivesTheActualStartupPath() {
        val dir = Files.createTempDirectory("instagene-theme-restart").toFile()
        val file = File(dir, "prefs.json")
        val startup = Class.forName("org.instagene.app.gui.GuiMainKt")
            .getDeclaredMethod("applySavedTheme", Prefs::class.java)
            .apply { isAccessible = true }
        try {
            onEdt {
                val prefs = Prefs(PrefsStore(file))
                prefs.update { it.copy(theme = "FlatLightLaf") }
                for (theme in listOf("FlatOneDarkIJTheme", "FlatDraculaIJTheme", "FlatDarculaLaf")) {
                    prefs.update { it.copy(theme = theme) }
                    prefs.save()
                    val restarted = Prefs(PrefsStore(file))
                    assertEquals(theme, restarted.value.theme, "saved theme")
                    startup.invoke(null, restarted)
                    assertEquals(ThemeManager.current(), theme, "restored theme")
                    assertEquals(theme, PrefsStore(file).load().theme, "startup must preserve the choice")
                }
            }
        } finally {
            onEdt { ThemeManager.apply(ThemeManager.DEFAULT_THEME) }
            dir.deleteRecursively()
        }
    }

    @Test
    fun themeBelongsToPreferencesRatherThanTheViewMenu() {
        val dir = Files.createTempDirectory("instagene-welcome-theme").toFile()
        dir.deleteOnExit()
        val file = File(dir, "prefs.json")

        try {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
            onEdt {
                val prefs = Prefs(PrefsStore(file))
                val content = InstaGeneContent(prefs = prefs)
                val menus = (0 until content.menuBar.menuCount).mapNotNull(content.menuBar::getMenu)
                val viewMenu = menus.first { it.text == "View" }
                assertFalse(viewMenu.menuComponents.any { it is javax.swing.JMenu && it.text == "Theme" })
                val fileMenu = menus.first { it.text == "File" }
                assertTrue(fileMenu.menuComponents.filterIsInstance<javax.swing.JMenuItem>().any { it.text == "Preferences..." })
                content.dispose()
            }
        } finally {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        }
    }

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
}
