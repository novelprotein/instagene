package org.instagene.app.gui

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.theme.ThemeManager
import org.instagene.app.gui.prefs.PrefsStore
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Standalone entry point for the desktop front-end (`./gradlew :app-gui:runGui`). */
fun main(argv: Array<String>) {
    configurePlatformConventions()
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("No display available, so the desktop GUI cannot start.")
        System.err.println("Use the CLI front-end (`:app-cli:runCli`) or the web front-end (`:app-web:runWeb`) instead.")
        exitProcess(2)
    }
    launch(argv.filterNot { it.startsWith("-") })
}

/** Configure Apple-specific Swing integration before the AWT toolkit starts. */
private fun configurePlatformConventions(osName: String = System.getProperty("os.name", "")) {
    when {
        osName.contains("mac", ignoreCase = true) -> {
            System.setProperty("apple.laf.useScreenMenuBar", "true")
            System.setProperty("apple.awt.application.name", "InstaGene")
            System.setProperty("com.apple.mrj.application.apple.menu.about.name", "InstaGene")
        }
        osName.contains("win", ignoreCase = true) -> {
            // Opt in before AWT starts so Java follows the monitor DPI when a
            // researcher moves the app between mixed-scale Windows displays.
            System.setProperty("sun.java2d.dpiaware", "true")
        }
    }
}

private val launched = AtomicBoolean(false)

/**
 * Opens the editor window on the Swing event thread.
 *
 * This is the single launch path for the desktop platform; calling it twice in
 * one JVM (e.g. from a double-click plus an IDE launch) is ignored.
 */
fun launch(openPaths: List<String>) {
    if (!launched.compareAndSet(false, true)) return
    SwingUtilities.invokeLater {
        val prefs = Prefs(PrefsStore())
        applySavedTheme(prefs)
        InstaGeneWindow(
            openPaths = openPaths,
            prefs = prefs,
            onProcessExit = { exitProcess(0) },
        ).isVisible = true
    }
}

private fun applySavedTheme(prefs: Prefs) {
    val previous = prefs.value.theme
    val saved = ThemeManager.migrateLegacyDefault(previous)
    if (saved != previous) prefs.update { it.copy(theme = saved) }
    if (!ThemeManager.apply(saved)) {
        // Corrupt or outdated theme id: fall back to the default and repair prefs.
        ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        prefs.update { it.copy(theme = ThemeManager.DEFAULT_THEME) }
    }
}
