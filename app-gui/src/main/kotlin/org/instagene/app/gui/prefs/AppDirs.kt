package org.instagene.app.gui.prefs

import java.io.File

/**
 * Cross-platform location of the InstaGene config directory (`prefs.json`).
 *
 * The inputs are injectable so the resolution rules can be tested on any host:
 *   - macOS    -> `~/Library/Application Support/instagene`
 *   - Windows  -> `%APPDATA%\instagene` (fallback `~/.instagene`)
 *   - Linux    -> `$XDG_CONFIG_HOME/instagene` (fallback `~/.config/instagene`)
 */
object AppDirs {

    fun configDir(
        osName: String = System.getProperty("os.name", "").lowercase(),
        env: (String) -> String? = { System.getenv(it) },
        userHome: String = System.getProperty("user.home", "."),
    ): File {
        val home = File(userHome)
        return when {
            osName.contains("mac") -> File(home, "Library/Application Support/instagene")
            osName.contains("win") -> {
                val appData = env("APPDATA")
                if (!appData.isNullOrBlank()) File(File(appData), "instagene")
                else File(home, ".instagene")
            }
            else -> {
                val xdg = env("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(File(xdg), "instagene")
                else File(File(home, ".config"), "instagene")
            }
        }
    }
}