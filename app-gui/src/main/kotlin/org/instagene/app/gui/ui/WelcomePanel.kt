package org.instagene.app.gui.ui

import org.instagene.app.gui.file.Prefs
import org.instagene.app.gui.theme.Palette
import org.instagene.core.Version
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.Insets
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The empty state shown when no documents are open: a short welcome heading,
 * buttons to open a file, open a project, or start a document, plus shortcuts
 * to recent files and projects. The working panels
 * (tabs and tool panels) are hidden while this is visible.
 */
class WelcomePanel(
    private val prefs: Prefs,
    onOpenFile: () -> Unit,
    onOpenProject: () -> Unit,
    onNewDocument: () -> Unit,
    private val onOpenRecentFile: (File) -> Unit = {},
    private val onOpenRecentProject: (File) -> Unit = {},
) : JPanel(BorderLayout()) {

    /** "Open File..." button, exposed for tests. */
    val openFileButton = JButton("Open File...")

    /** "Open Project..." button, exposed for tests. */
    val openProjectButton = JButton("Open Project...")

    /** "New Document" button, exposed for tests. */
    val newDocumentButton = JButton("New Document")

    /** How many entries each recent section shows at most. */
    private val recentLimit = 6

    private val recentFilesLabel = JLabel("Recent Files")
    private val recentProjectsLabel = JLabel("Recent Projects")
    private val recentFilesBox = JPanel()
    private val recentProjectsBox = JPanel()

    /** The recent files currently shown, most recent first, missing entries skipped. */
    var recentFiles: List<File> = emptyList()
        private set

    /** The recent projects currently shown, most recent first, missing entries skipped. */
    var recentProjects: List<File> = emptyList()
        private set

    init {
        openFileButton.addActionListener { onOpenFile() }
        openProjectButton.addActionListener { onOpenProject() }
        newDocumentButton.addActionListener { onNewDocument() }

        recentFilesBox.layout = BoxLayout(recentFilesBox, BoxLayout.Y_AXIS)
        recentFilesBox.isOpaque = false
        recentProjectsBox.layout = BoxLayout(recentProjectsBox, BoxLayout.Y_AXIS)
        recentProjectsBox.isOpaque = false

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
            add(Box.createVerticalStrut(24))
            add(recentFilesLabel.also { it.alignmentX = CENTER_ALIGNMENT })
            add(recentFilesBox.also { it.alignmentX = CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(12))
            add(recentProjectsLabel.also { it.alignmentX = CENTER_ALIGNMENT })
            add(recentProjectsBox.also { it.alignmentX = CENTER_ALIGNMENT })
            add(Box.createVerticalGlue())
        }
        add(column, BorderLayout.CENTER)

        prefs.addListener { rebuildRecents() }
        rebuildRecents()
    }

    /** The "open recent file" buttons currently shown, most recent first. */
    fun recentFileButtons(): List<JButton> = recentFilesBox.components.filterIsInstance<JButton>()

    /** The "open recent project" buttons currently shown, most recent first. */
    fun recentProjectButtons(): List<JButton> = recentProjectsBox.components.filterIsInstance<JButton>()

    private fun rebuildRecents() {
        recentFiles = existing(prefs.value.recentFiles)
        recentProjects = existing(prefs.value.recentProjects)
        rebuild(recentFilesLabel, recentFilesBox, recentFiles, onOpenRecentFile)
        rebuild(recentProjectsLabel, recentProjectsBox, recentProjects, onOpenRecentProject)
    }

    private fun existing(paths: List<String>): List<File> =
        paths.mapNotNull { File(it).takeIf { f -> f.exists() } }.take(recentLimit)

    private fun rebuild(label: JLabel, box: JPanel, files: List<File>, onClick: (File) -> Unit) {
        label.isVisible = files.isNotEmpty()
        box.removeAll()
        files.forEach { file ->
            box.add(linkButton(file.name, file.absolutePath) { onClick(file) })
        }
        box.revalidate()
        box.repaint()
    }

    /** A clickable entry styled as a hyperlink (accent color, hand cursor) rather than a full button. */
    private fun linkButton(text: String, tip: String, onClick: () -> Unit): JButton =
        JButton(text).apply {
            alignmentX = CENTER_ALIGNMENT
            toolTipText = tip
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            margin = Insets(2, 2, 2, 2)
            foreground = Palette.ACCENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        }
}
