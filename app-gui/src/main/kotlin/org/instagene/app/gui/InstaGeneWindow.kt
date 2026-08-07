package org.instagene.app.gui

import org.instagene.core.Seq
import java.awt.BorderLayout
import javax.swing.JFrame

/**
 * Main application window for the InstaGene sequence editor.
 *
 * The editor UI itself lives in [InstaGeneContent]; this class only wraps it in
 * a `JFrame` (menus, toolbar, sequence editor, and tool panels).
 */
class InstaGeneWindow(openPath: String? = null) : JFrame("InstaGene - Sequence Editor") {

    val content: InstaGeneContent

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1400, 800)
        setLocationRelativeTo(null)
        isResizable = true

        content = InstaGeneContent(openPath, this)
        jMenuBar = content.menuBar
        contentPane.add(content, BorderLayout.CENTER)
    }

    /** Convenience for opening a fragment (or any [Seq]) directly in its own window. */
    constructor(initial: Seq) : this(null) {
        content.doc.loadSequence(initial)
        title = "InstaGene - ${initial.name}"
    }
}
