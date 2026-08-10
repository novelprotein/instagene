package org.instagene.app.gui

import org.instagene.core.Version
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The empty state shown when no documents are open: a short welcome heading and
 * one-tap buttons to open a file, open a project or start a fresh document.
 * The working panels (tabs and tool panels) are hidden while this is visible.
 */
class WelcomePanel(
    onOpenFile: () -> Unit,
    onOpenProject: () -> Unit,
    onNewDocument: () -> Unit,
) : JPanel(BorderLayout()) {

    /** "Open File..." button, exposed for tests. */
    val openFileButton = JButton("Open File...")

    /** "Open Project..." button, exposed for tests. */
    val openProjectButton = JButton("Open Project...")

    /** "New Document" button, exposed for tests. */
    val newDocumentButton = JButton("New Document")

    init {
        openFileButton.addActionListener { onOpenFile() }
        openProjectButton.addActionListener { onOpenProject() }
        newDocumentButton.addActionListener { onNewDocument() }

        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(Box.createVerticalGlue())
            add(JLabel("InstaGene ${Version.VERSION}").apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, 26)
                alignmentX = CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(6))
            add(JLabel("Sequence Editor").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
                alignmentX = CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(28))
            add(JPanel().apply {
                isOpaque = false
                alignmentX = CENTER_ALIGNMENT
                add(openFileButton)
                add(openProjectButton)
                add(newDocumentButton)
            })
            add(Box.createVerticalGlue())
        }
        add(column, BorderLayout.CENTER)
    }
}
