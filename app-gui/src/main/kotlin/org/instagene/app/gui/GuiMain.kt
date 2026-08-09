package org.instagene.app.gui

import org.instagene.app.gui.prefs.PrefsStore
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Standalone entry point for the desktop front-end (`./gradlew :app-gui:runGui`). */
fun main(argv: Array<String>) {
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("No display available, so the desktop GUI cannot start.")
        System.err.println("Use the CLI front-end (`:app-cli:runCli`) or the web front-end (`:app-web:runWeb`) instead.")
        exitProcess(2)
    }
    launch(argv.firstOrNull { !it.startsWith("-") })
}

private val launched = AtomicBoolean(false)

/**
 * Opens the editor window on the Swing event thread.
 *
 * This is the single launch path for the desktop platform; calling it twice in
 * one JVM (e.g. from a double-click plus an IDE launch) is ignored.
 */
fun launch(openPath: String?) {
    if (!launched.compareAndSet(false, true)) return
    SwingUtilities.invokeLater {
        val prefs = Prefs(PrefsStore())
        applySavedTheme(prefs)
        InstaGeneWindow(openPath, prefs).isVisible = true
    }
}

private fun applySavedTheme(prefs: Prefs) {
    val saved = prefs.value.theme
    if (!ThemeManager.apply(saved)) {
        // Corrupt or outdated theme id: fall back to the default and repair prefs.
        ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        prefs.update { it.copy(theme = ThemeManager.DEFAULT_THEME) }
    }
}
