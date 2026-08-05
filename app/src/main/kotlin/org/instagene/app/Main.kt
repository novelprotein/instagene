package org.instagene.app

import org.instagene.app.cli.Cli
import org.instagene.app.gui.InstaGeneWindow
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
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        InstaGeneWindow(openPath).isVisible = true
    }
}
