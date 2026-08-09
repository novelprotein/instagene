package org.instagene.app.gui

import com.formdev.flatlaf.FlatDarculaLaf
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.intellijthemes.FlatArcIJTheme
import com.formdev.flatlaf.intellijthemes.FlatCyanLightIJTheme
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme
import com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkHardIJTheme
import com.formdev.flatlaf.intellijthemes.FlatMonokaiProIJTheme
import com.formdev.flatlaf.intellijthemes.FlatNordIJTheme
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme
import com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme
import com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme
import com.formdev.flatlaf.intellijthemes.FlatSpacegrayIJTheme
import com.formdev.flatlaf.intellijthemes.FlatXcodeDarkIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatAtomOneDarkIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatAtomOneLightIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkIJTheme
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubIJTheme
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * Registry of selectable FlatLaf themes and the runtime switching logic.
 *
 * A theme is identified by a stable id (defaulting to the LAF class simple
 * name), which is what gets persisted in prefs. Switching calls
 * `UIManager.setLookAndFeel` followed by `FlatLaf.updateUI()` so every open
 * window is re-skinned in place.
 */
object ThemeManager {

    const val DEFAULT_THEME = "FlatDraculaIJTheme"

    data class Theme(
        val id: String,
        val displayName: String,
        val dark: Boolean,
        val create: () -> LookAndFeel,
    )

    val themes: List<Theme> = listOf(
        Theme("FlatLightLaf", "Light (FlatLaf)", dark = false, create = ::FlatLightLaf),
        Theme("FlatIntelliJLaf", "IntelliJ Light", dark = false, create = ::FlatIntelliJLaf),
        Theme("FlatMacLightLaf", "macOS Light", dark = false, create = ::FlatMacLightLaf),
        Theme("FlatArcIJTheme", "Arc", dark = false, create = ::FlatArcIJTheme),
        Theme("FlatCyanLightIJTheme", "Cyan Light", dark = false, create = ::FlatCyanLightIJTheme),
        Theme("FlatGitHubIJTheme", "GitHub", dark = false, create = ::FlatGitHubIJTheme),
        Theme("FlatAtomOneLightIJTheme", "Atom One Light", dark = false, create = ::FlatAtomOneLightIJTheme),
        Theme("FlatSolarizedLightIJTheme", "Solarized Light", dark = false, create = ::FlatSolarizedLightIJTheme),
        Theme("FlatDarculaLaf", "Darcula", dark = true, create = ::FlatDarculaLaf),
        Theme("FlatDarkLaf", "Dark (FlatLaf)", dark = true, create = ::FlatDarkLaf),
        Theme("FlatMacDarkLaf", "macOS Dark", dark = true, create = ::FlatMacDarkLaf),
        Theme("FlatDraculaIJTheme", "Dracula", dark = true, create = ::FlatDraculaIJTheme),
        Theme("FlatOneDarkIJTheme", "One Dark", dark = true, create = ::FlatOneDarkIJTheme),
        Theme("FlatNordIJTheme", "Nord", dark = true, create = ::FlatNordIJTheme),
        Theme("FlatMonokaiProIJTheme", "Monokai Pro", dark = true, create = ::FlatMonokaiProIJTheme),
        Theme("FlatGruvboxDarkHardIJTheme", "Gruvbox Dark", dark = true, create = ::FlatGruvboxDarkHardIJTheme),
        Theme("FlatSolarizedDarkIJTheme", "Solarized Dark", dark = true, create = ::FlatSolarizedDarkIJTheme),
        Theme("FlatGitHubDarkIJTheme", "GitHub Dark", dark = true, create = ::FlatGitHubDarkIJTheme),
        Theme("FlatSpacegrayIJTheme", "Space Gray", dark = true, create = ::FlatSpacegrayIJTheme),
        Theme("FlatXcodeDarkIJTheme", "Xcode Dark", dark = true, create = ::FlatXcodeDarkIJTheme),
        Theme("FlatAtomOneDarkIJTheme", "Atom One Dark", dark = true, create = ::FlatAtomOneDarkIJTheme),
    )

    private var applied: String? = null

    /** The id of the last successfully applied theme. */
    fun current(): String = applied ?: DEFAULT_THEME

    fun theme(id: String): Theme? = themes.firstOrNull { it.id == id }

    fun displayName(id: String): String = theme(id)?.displayName ?: id

    fun isDark(id: String): Boolean = theme(id)?.dark == true

    /**
     * Installs [id] as the active theme and re-skins every open window.
     * Returns false (leaving the current theme in place) if [id] is unknown
     * or the look-and-feel cannot be installed.
     */
    fun apply(id: String): Boolean {
        val target = theme(id) ?: return false
        val installed = runCatching { UIManager.setLookAndFeel(target.create()) }
        if (installed.isFailure) {
            System.err.println("instagene: failed to install theme '${target.displayName}': ${installed.exceptionOrNull()?.message}")
            return false
        }
        applied = id
        runCatching { FlatLaf.updateUI() }
            .onFailure { System.err.println("instagene: theme '${target.displayName}' installed but UI refresh failed: ${it.message}") }
        return true
    }
}
