package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFrame
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane

/**
 * The entire editor UI, built as a plain [JPanel] so it can be constructed and
 * exercised without a display (headless tests). [InstaGeneWindow] wraps this
 * panel into a `JFrame`; `owner` is that window, used as the parent for file
 * dialogs and title updates, and may be null in headless contexts.
 */
class InstaGeneContent(
    openPath: String? = null,
    owner: JFrame? = null,
    private val prefs: Prefs = Prefs(),
) : JPanel(BorderLayout()) {

    val doc: SeqDocument
    val sequenceView: SequenceView
    val digestPanel: DigestPanel
    val plasmidMapPanel: PlasmidMapPanel
    val featuresPanel: FeaturesPanel
    val primersPanel: PrimersPanel
    val infoPanel: InfoPanel
    val libraryPanel: LibraryPanel
    val toolTabs = JTabbedPane()
    val menuBar: JMenuBar
    val statusBar: StatusBar

    init {
        val initialSeq = if (openPath != null && File(openPath).exists()) {
            runCatching { SeqIO.read(File(openPath)) }.getOrNull() ?: Seq("")
        } else {
            Seq("")
        }
        doc = SeqDocument(initialSeq, if (openPath != null) File(openPath) else null)

        sequenceView = SequenceView(doc)
        digestPanel = DigestPanel(doc, { seq -> openFragmentWindow(seq) }, { start, end -> sequenceView.revealRange(start, end) }, prefs)
        plasmidMapPanel = PlasmidMapPanel(doc).apply {
            onSelect = { start, end -> sequenceView.revealRange(start, end) }
        }
        featuresPanel = FeaturesPanel(doc) { start, end -> sequenceView.revealRange(start, end) }
        primersPanel = PrimersPanel(doc, prefs)
        infoPanel = InfoPanel(doc) { FileMenu(owner, doc, prefs).openFile() }
        libraryPanel = LibraryPanel(prefs, doc, sequenceView) { seq -> openFragmentWindow(seq) }

        menuBar = createMenuBar(owner)
        statusBar = StatusBar(doc, sequenceView)
        add(createMainContent(), BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        toolTabs.selectedIndex = prefs.value.activeTab.coerceIn(0, toolTabs.tabCount - 1)
        toolTabs.addChangeListener { prefs.update { it.copy(activeTab = toolTabs.selectedIndex) } }
    }

    /** Opens the extracted fragment as a fresh editor window. */
    private fun openFragmentWindow(fragment: Seq) {
        if (GraphicsEnvironment.isHeadless()) {
            // No display in headless contexts (tests/CI): open it in the current document.
            doc.replaceSequence(fragment)
            return
        }
        val window = InstaGeneWindow(fragment, prefs)
        window.isVisible = true
    }

    private fun createMenuBar(owner: JFrame?): JMenuBar {
        return JMenuBar().apply {
            add(FileMenu(owner, doc, prefs).create())
            add(EditMenu(owner, doc, sequenceView, prefs).create())
            add(ViewMenu(doc, sequenceView).create())
            add(ToolsMenu(doc, digestPanel, prefs).create())
        }
    }

    private fun createMainContent(): JPanel {
        // The main sequence editor lives on the Sequence tab, so it is only
        // visible while that tab is selected.
        val editorScroll = JScrollPane(sequenceView).apply {
            horizontalScrollBar.unitIncrement = 10
            verticalScrollBar.unitIncrement = 17
        }

        toolTabs.apply {
            addTab("Info", infoPanel)
            addTab("Map", plasmidMapPanel)
            addTab("Sequence", editorScroll)
            addTab("Enzyme", digestPanel)
            addTab("Features", featuresPanel)
            addTab("Primers", primersPanel)
            addTab("Library", libraryPanel)
        }

        return JPanel(BorderLayout()).apply {
            add(toolTabs, BorderLayout.CENTER)
        }
    }
}
