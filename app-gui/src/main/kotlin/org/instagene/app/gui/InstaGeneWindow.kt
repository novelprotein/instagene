package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.Version
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

/**
 * Main application window for the InstaGene sequence editor.
 *
 * The editor UI itself lives in [InstaGeneContent]; this class only wraps it in
 * a `JFrame` (menus, toolbar, sequence editor, and tool panels). Closing the
 * window prompts for unsaved changes before it goes away. Window geometry is
 * remembered in [prefs] and restored on the next launch.
 */
class InstaGeneWindow(
    openPath: String? = null,
    private val prefs: Prefs = Prefs(),
) : JFrame("InstaGene ${Version.VERSION} - Sequence Editor") {

    val content: InstaGeneContent

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                rememberGeometry()
                if (confirmDiscardChanges(this@InstaGeneWindow, content.doc)) dispose()
            }
        })

        val saved = prefs.value
        setSize(saved.windowWidth, saved.windowHeight)
        val savedX = saved.windowX
        val savedY = saved.windowY
        if (savedX != null && savedY != null) {
            setLocation(savedX, savedY)
        } else {
            setLocationRelativeTo(null)
        }
        if (saved.windowMaximized) extendedState = MAXIMIZED_BOTH
        isResizable = true

        content = InstaGeneContent(openPath, this, prefs)
        jMenuBar = content.menuBar
        contentPane.add(content, BorderLayout.CENTER)
    }

    /** Convenience for opening a fragment (or any [Seq]) directly in its own window. */
    constructor(initial: Seq, prefs: Prefs = Prefs()) : this(null, prefs) {
        content.doc.loadSequence(initial)
        title = "InstaGene ${Version.VERSION} - ${initial.name}"
    }

    /** Persists the current bounds so the next launch opens in the same place. */
    private fun rememberGeometry() {
        val maximized = extendedState and MAXIMIZED_BOTH != 0
        prefs.update {
            it.copy(
                windowX = if (maximized) it.windowX else x,
                windowY = if (maximized) it.windowY else y,
                windowWidth = width,
                windowHeight = height,
                windowMaximized = maximized,
            )
        }
        prefs.save()
    }
}
