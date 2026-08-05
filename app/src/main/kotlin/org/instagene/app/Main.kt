package org.instagene.app

import org.instagene.app.cli.Cli
import org.instagene.app.gui.InstaGeneWindow
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Single entry point for both front-ends.
 *
 * No arguments (or `gui`) opens the editor; anything else is handled by the CLI,
 * so the same binary works in a shell pipeline and on the desktop.
 */
fun main(argv: Array<String>) {
    val args = argv.toList()
    val wantsGui = args.isEmpty() || args.first().equals("gui", ignoreCase = true)

    if (!wantsGui) {
        exitProcess(Cli.run(args))
    }

    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("No display available, so the GUI cannot start. Command-line usage:\n")
        println(Cli.usage())
        exitProcess(2)
    }

    val openPath = args.drop(1).firstOrNull { !it.startsWith("-") }
    SwingUtilities.invokeLater {
        setupTheme()
        InstaGeneWindow(openPath).isVisible = true
    }
}

private fun setupTheme() {
    try {
        // Use FlatDarkLaf (modern dark theme inspired by IntelliJ IDEA)
        UIManager.setLookAndFeel(FlatDarkLaf())
    } catch (e: Exception) {
        try {
            // Fallback to light theme
            UIManager.setLookAndFeel(FlatLightLaf())
        } catch (e2: Exception) {
            System.err.println("Warning: Could not load FlatLaf theme: ${e.message}")
        }
    }
}
