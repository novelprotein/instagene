package org.instagene.app.gui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import org.instagene.core.prefs.PrefsStore
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.UIManager
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
        setupTheme()
        val prefs = Prefs(PrefsStore())
        InstaGeneWindow(openPath, prefs).isVisible = true
    }
}

private fun setupTheme() {
    try {
        // Use FlatDarkLaf (modern dark theme inspired by IntelliJ IDEA)
        UIManager.setLookAndFeel(FlatDarkLaf())
    } catch (e: Exception) {
        // Fallback to light theme
        try {
            UIManager.setLookAndFeel(FlatLightLaf())
        } catch (_: Exception) {
            System.err.println("Warning: Could not load FlatLaf theme: ${e.message}")
        }
    }
}
