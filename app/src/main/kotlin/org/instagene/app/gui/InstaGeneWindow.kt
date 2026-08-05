package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.JFrame
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Main application window for the InstaGene sequence editor.
 * Combines a sequence viewer with restriction enzyme digestion and plasmid mapping tools.
 */
class InstaGeneWindow(openPath: String? = null) : JFrame("InstaGene - Sequence Editor") {

    private val doc: SeqDocument
    private val sequenceView: SequenceView
    private val digestPanel: DigestPanel
    private val plasmidMapPanel: PlasmidMapPanel

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1400, 800)
        setLocationRelativeTo(null)
        isResizable = true

        // Initialize document and views
        val initialSeq = if (openPath != null && File(openPath).exists()) {
            runCatching { SeqIO.read(File(openPath)) }.getOrNull() ?: Seq("")
        } else {
            Seq("")
        }
        doc = SeqDocument(initialSeq, if (openPath != null) File(openPath) else null)

        sequenceView = SequenceView(doc)
        digestPanel = DigestPanel(doc, { /* onExtractFragment */ }, { start, end -> sequenceView.revealRange(start, end) })
        plasmidMapPanel = PlasmidMapPanel(doc).apply {
            onSelect = { start, end -> sequenceView.revealRange(start, end) }
        }

        // Build UI
        jMenuBar = createMenuBar()
        add(createToolbar(), BorderLayout.NORTH)
        add(createMainContent(), BorderLayout.CENTER)
        add(createStatusBar(), BorderLayout.SOUTH)
    }

    private fun createMenuBar(): JMenuBar {
        return JMenuBar().apply {
            add(FileMenu(this@InstaGeneWindow, doc).create())
            add(EditMenu(this@InstaGeneWindow, doc, sequenceView).create())
            add(ViewMenu(sequenceView).create())
            add(ToolsMenu(doc, digestPanel).create())
        }
    }

    private fun createToolbar(): JToolBar {
        return JToolBar().apply {
            isFloatable = false
            add(ToolbarActions.createFileOpenButton(this@InstaGeneWindow, doc))
            add(ToolbarActions.createFileSaveButton(this@InstaGeneWindow, doc))
            addSeparator()
            add(ToolbarActions.createUndoButton(doc))
            add(ToolbarActions.createRedoButton(doc))
            addSeparator()
            add(ToolbarActions.createSelectAllButton(doc))
            add(ToolbarActions.createCopyButton(sequenceView))
            add(ToolbarActions.createPasteButton(sequenceView))
            addSeparator()
            add(ToolbarActions.createFontSizeControls(sequenceView))
        }
    }

    private fun createMainContent(): JPanel {
        val mainPanel = JPanel(BorderLayout())

        // Top: Sequence editor
        val editorScroll = JScrollPane(sequenceView)
        editorScroll.horizontalScrollBar.unitIncrement = 10
        editorScroll.verticalScrollBar.unitIncrement = 17

        // Bottom: Tool panels in tabs
        val toolTabs = JTabbedPane().apply {
            addTab("Digestion", digestPanel)
            addTab("Plasmid Map", plasmidMapPanel)
        }

        // Split pane between editor and tools
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScroll, toolTabs).apply {
            dividerLocation = 600
            isOneTouchExpandable = true
        }

        mainPanel.add(split, BorderLayout.CENTER)
        return mainPanel
    }

    private fun createStatusBar(): StatusBar {
        return StatusBar(sequenceView)
    }
}
