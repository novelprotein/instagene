package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.io.File
import javax.swing.AbstractButton
import javax.swing.JFrame
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JToolBar

/**
 * The entire editor UI, built as a plain [JPanel] so it can be constructed and
 * exercised without a display (headless tests). [InstaGeneWindow] wraps this
 * panel into a `JFrame`; `owner` is that window, used as the parent for file
 * dialogs and title updates, and may be null in headless contexts.
 */
class InstaGeneContent(
    openPath: String? = null,
    owner: JFrame? = null,
) : JPanel(BorderLayout()) {

    val doc: SeqDocument
    val sequenceView: SequenceView
    val digestPanel: DigestPanel
    val plasmidMapPanel: PlasmidMapPanel
    val menuBar: JMenuBar
    val statusBar: StatusBar
    private lateinit var openButton: AbstractButton

    init {
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

        menuBar = createMenuBar(owner)
        statusBar = StatusBar(sequenceView)
        add(createToolbar(owner), BorderLayout.NORTH)
        add(createMainContent(), BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        // Listen for file changes to update open button visibility
        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) {
                updateOpenButtonVisibility()
            }
        }
        updateOpenButtonVisibility()
    }

    private fun updateOpenButtonVisibility() {
        openButton.isVisible = doc.file == null
    }

    private fun createMenuBar(owner: JFrame?): JMenuBar {
        return JMenuBar().apply {
            add(FileMenu(owner, doc).create())
            add(EditMenu(owner, doc, sequenceView).create())
            add(ViewMenu(sequenceView).create())
            add(ToolsMenu(doc, digestPanel).create())
        }
    }

    private fun createToolbar(owner: JFrame?): JToolBar {
        return JToolBar().apply {
            isFloatable = false
            openButton = ToolbarActions.createFileOpenButton(owner, doc)
            add(openButton)
            add(ToolbarActions.createFileSaveButton(owner, doc))
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
}
