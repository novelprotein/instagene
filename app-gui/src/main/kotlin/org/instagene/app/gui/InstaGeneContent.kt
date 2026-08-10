package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.Version
import org.instagene.core.io.SeqIO
import org.instagene.core.project.ProjectLayout
import org.instagene.core.project.SeqProject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * The entire editor UI, built as a plain [JPanel] so it can be constructed and
 * exercised without a display (headless tests). [InstaGeneWindow] wraps this
 * panel into a `JFrame`; `owner` is that window, used as the parent for file
 * dialogs and title updates, and may be null in headless contexts.
 *
 * Documents open as tabs on [docTabs] (one tab per open sequence). The tool
 * panels on [toolTabs] below are a single shared set that is rebound to the
 * active document whenever the active tab changes, so switching tabs never
 * rebuilds the panels themselves.
 */
class InstaGeneContent(
    openPath: String? = null,
    private val owner: JFrame? = null,
    private val prefs: Prefs = Prefs(),
) : JPanel(BorderLayout()) {

    /** One tab per open document. */
    val docTabs = JTabbedPane()

    /** The tool panels (Info/Map/Sequence/Enzyme/Features/Primers/Library), shared across documents. */
    val toolTabs = JTabbedPane()

    /** The document on the selected tab; non-null because the last tab cannot be closed. */
    val activeDocument: SeqDocument get() = hub.active ?: newDocument()

    /** The active document (alias kept for callers of the single-document API). */
    val doc: SeqDocument get() = activeDocument

    val sequenceView: SequenceView
    val digestPanel: DigestPanel
    val plasmidMapPanel: PlasmidMapPanel
    val featuresPanel: FeaturesPanel
    val primersPanel: PrimersPanel
    val infoPanel: InfoPanel
    val libraryPanel: LibraryPanel
    val statusBar: StatusBar
    val menuBar = JMenuBar()

    private val hub = DocumentHub()

    /** Per-document menus; created lazily the first time a document is activated. */
    private val menus = HashMap<SeqDocument, MenuSet>()

    /** The currently open project, or null when the window is not attached to a project. */
    private var project: SeqProject? = null

    /** True while [openProjectAt] is restoring documents, so intermediate states are not persisted. */
    private var loadingProject = false

    /** The last file each document was saved to, to catch save-as in [onDocChanged]. */
    private val recordedFile = HashMap<SeqDocument, File?>()

    /** True while [syncDocTabs] is rebuilding the tab strip; its own tab events must be ignored. */
    private var inSync = false

    /** The title label of each tab, so dirty markers can be updated without rebuilding. */
    private val tabLabels = HashMap<SeqDocument, JLabel>()

    private class MenuSet(
        val file: FileMenu,
        val edit: EditMenu,
        val view: ViewMenu,
        val tools: ToolsMenu,
    )

    init {
        val initialFile = if (openPath != null && File(openPath).exists()) File(openPath) else null
        val initialSeq = initialFile?.let { runCatching { SeqIO.read(it) }.getOrNull() } ?: Seq("")
        val initial = SeqDocument(initialSeq, initialFile)

        sequenceView = SequenceView(initial)
        digestPanel = DigestPanel(
            initial,
            { seq -> openSequence(seq) },
            { start, end -> sequenceView.revealRange(start, end) },
            prefs,
        )
        plasmidMapPanel = PlasmidMapPanel(initial).apply {
            onSelect = { start, end -> sequenceView.revealRange(start, end) }
        }
        featuresPanel = FeaturesPanel(initial) { start, end -> sequenceView.revealRange(start, end) }
        primersPanel = PrimersPanel(initial, prefs)
        infoPanel = InfoPanel(initial) { openFile() }
        libraryPanel = LibraryPanel(prefs, initial, sequenceView) { seq -> openSequence(seq) }
        statusBar = StatusBar(initial, sequenceView)

        hub.addListener { _, reason -> onHubChanged(reason) }
        addDocument(initial)

        docTabs.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        docTabs.addChangeListener { onDocTabSelected() }

        add(JPanel(BorderLayout()).apply {
            add(docTabs, BorderLayout.NORTH)
            add(toolTabs, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        buildToolTabs()
        toolTabs.addChangeListener { prefs.update { it.copy(activeTab = toolTabs.selectedIndex) } }
        toolTabs.selectedIndex = prefs.value.activeTab.coerceIn(0, toolTabs.tabCount - 1)

        onActiveDocumentChanged()
    }

    // ------------------------------------------------------------- documents

    /** Opens a fresh empty sequence in a new tab. */
    fun newDocument(): SeqDocument = openSequence(Seq(""))

    /** Opens [seq] in a new tab and activates it. */
    fun openSequence(seq: Seq, file: File? = null): SeqDocument {
        val doc = SeqDocument(seq, file)
        addDocument(doc)
        return doc
    }

    /** Opens a file picker; the chosen sequence lands in a new tab. */
    fun openFile() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            setFileFilter(
                javax.swing.filechooser.FileNameExtensionFilter("Sequence Files", "fasta", "fa", "fna", "gb", "gbk", "gp", "txt"),
            )
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            openFileInTab(chooser.selectedFile)
        }
    }

    /**
     * Opens [file] in a new tab without a chooser. If it is already open the
     * existing tab is activated instead, so re-opening a file never duplicates it.
     */
    fun openFileInTab(file: File) {
        hub.documentFor(file)?.let { hub.activate(it) } ?: activeMenuSet().file.readAsync(file) { seq ->
            val doc = openSequence(seq, file)
            menus.getValue(doc).file.addRecent(file)
        }
    }

    /**
     * Closes the tab of [doc], prompting for unsaved changes unless [force].
     * Closing the last tab exits the program instead of keeping a fresh one.
     */
    fun closeTab(doc: SeqDocument, force: Boolean = false): Boolean {
        if (!hub.contains(doc)) return false
        if (!force && !confirmDiscardChanges(owner, doc)) return false
        hub.remove(doc)
        menus.remove(doc)
        recordedFile.remove(doc)
        tabLabels.remove(doc)
        if (hub.openDocuments.isEmpty()) {
            persistProject()
            owner?.dispose()
        }
        return true
    }

    /** Prompts once per dirty document; returns true when every tab may be closed. */
    fun confirmCloseAll(frame: JFrame?): Boolean {
        for (d in hub.openDocuments) {
            if (!confirmDiscardChanges(frame, d)) return false
        }
        return true
    }

    // --------------------------------------------------------------- projects

    /** Creates a new project: picks a folder, marks it as a project and records the current documents. */
    fun newProject() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
        val root = chooser.selectedFile
        project = SeqProject.create(root).also { it.save() }
        persistProject()
        updateTitle()
    }

    /** Opens a project directory, restoring its open documents, active tab and layout. */
    fun openProject() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
        openProjectAt(chooser.selectedFile)
    }

    /** Opens the project at [root], loading its documents off the EDT in tab order. */
    fun openProjectAt(root: File) {
        val opened = SeqProject.open(root)
        project = opened
        val files = opened.openDocuments()
        if (files.isEmpty()) {
            toolTabs.selectedIndex = opened.manifest.layout.activeToolTab.coerceIn(0, toolTabs.tabCount - 1)
            persistProject()
            return
        }
        loadingProject = true
        Thread {
            val loaded = files.mapNotNull { f -> runCatching { SeqIO.read(f) }.getOrNull()?.let { it to f } }
            SwingUtilities.invokeLater {
                loaded.forEach { (seq, f) ->
                    if (hub.documentFor(f) == null) openSequence(seq, f)
                }
                opened.activeDocument()?.let { active -> hub.documentFor(active)?.let { hub.activate(it) } }
                toolTabs.selectedIndex = opened.manifest.layout.activeToolTab.coerceIn(0, toolTabs.tabCount - 1)
                loadingProject = false
                persistProject()
            }
        }.apply { isDaemon = false; name = "ProjectLoader" }.start()
    }

    /**
     * Persists the project manifest (open set, active tab, layout). Only
     * file-backed documents inside the project are recorded.
     */
    fun persistProject() {
        val p = project ?: return
        p.setOpenDocuments(hub.openDocuments.mapNotNull { it.file })
        val active = activeDocument.file
        p.setActive(if (active != null && p.relativePath(active) != null) active else null)
        p.setLayout(ProjectLayout(activeToolTab = toolTabs.selectedIndex, treeSplitRatio = p.manifest.layout.treeSplitRatio))
        p.save()
    }

    // ---------------------------------------------------------------- internals

    private fun addDocument(doc: SeqDocument): SeqDocument {
        doc.addListener { _, _ -> onDocChanged(doc) }
        recordedFile[doc] = doc.file
        hub.add(doc)
        return doc
    }

    private fun onDocChanged(doc: SeqDocument) {
        updateTabText(doc)
        if (doc === hub.active) updateTitle()
        // A save-as changes the document's file without touching the tab set;
        // re-record it so the project manifest follows.
        if (project != null && recordedFile[doc] != doc.file) {
            recordedFile[doc] = doc.file
            persistProject()
        }
    }

    private fun onDocTabSelected() {
        if (inSync) return
        val index = docTabs.selectedIndex
        if (index in hub.openDocuments.indices) hub.activate(hub.openDocuments[index])
    }

    private fun onHubChanged(reason: DocumentHub.Reason) {
        syncDocTabs()
        if (project != null && !loadingProject) persistProject()
        if (reason == DocumentHub.Reason.ACTIVE_CHANGED) onActiveDocumentChanged()
    }

    /** Rebuilds the document tab strip from the hub, preserving the active tab. */
    private fun syncDocTabs() {
        if (inSync) return
        inSync = true
        try {
            val active = hub.active
            docTabs.removeAll()
            tabLabels.clear()
            hub.openDocuments.forEach { doc ->
                docTabs.addTab("", JPanel())
                docTabs.setTabComponentAt(docTabs.tabCount - 1, tabComponentFor(doc))
            }
            val index = if (active != null) hub.indexOf(active) else 0
            if (index in 0 until docTabs.tabCount) docTabs.selectedIndex = index
        } finally {
            inSync = false
        }
    }

    /** The label-and-close-button component shown on [doc]'s tab. */
    private fun tabComponentFor(doc: SeqDocument): JComponent {
        val label = JLabel(tabTitle(doc)).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 6)
        }
        tabLabels[doc] = label
        val close = JButton("\u00d7").apply {
            isFocusable = false
            isContentAreaFilled = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            margin = Insets(0, 0, 0, 0)
            preferredSize = Dimension(16, 16)
            toolTipText = "Close tab"
            addActionListener { closeTab(doc) }
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(label)
            add(close)
        }
    }

    private fun tabTitle(doc: SeqDocument): String {
        val name = doc.file?.name ?: doc.seq.name.ifBlank { "Untitled" }
        return if (doc.isDirty) "$name *" else name
    }

    private fun updateTabText(doc: SeqDocument) {
        tabLabels[doc]?.text = tabTitle(doc)
    }

    /** The text currently shown on [doc]'s tab, e.g. "name *" when it is dirty. */
    fun tabLabelText(doc: SeqDocument): String = tabLabels[doc]?.text ?: tabTitle(doc)

    /** Re-points every shared panel and the menus at the newly active document. */
    private fun onActiveDocumentChanged() {
        val active = hub.active ?: return
        sequenceView.bindDocument(active)
        digestPanel.bindDocument(active)
        plasmidMapPanel.bindDocument(active)
        featuresPanel.bindDocument(active)
        primersPanel.bindDocument(active)
        infoPanel.bindDocument(active)
        libraryPanel.bindDocument(active)
        statusBar.bindDocument(active)
        rebuildMenuBar()
        updateTitle()
    }

    private fun updateTitle() {
        val active = hub.active ?: return
        val filename = active.file?.name ?: "Untitled"
        val dirty = if (active.isDirty) "*" else ""
        owner?.title = "InstaGene ${Version.VERSION} - $filename$dirty"
    }

    private fun activeMenuSet(): MenuSet = menuSetFor(activeDocument)

    private fun menuSetFor(doc: SeqDocument): MenuSet = menus.getOrPut(doc) {
        MenuSet(
            file = FileMenu(
                frame = owner,
                doc = doc,
                prefs = prefs,
                onNewDocument = { newDocument() },
                onOpenDocument = { openFile() },
                onOpenRecent = { openFileInTab(it) },
                onNewProject = { newProject() },
                onOpenProject = { openProject() },
                onCloseTab = { closeTab(activeDocument) },
                onExit = { if (confirmCloseAll(owner)) { persistProject(); owner?.dispose() } },
                onTitleChanged = { updateTitle() },
            ),
            edit = EditMenu(owner, doc, sequenceView, prefs),
            view = ViewMenu(doc, sequenceView, prefs),
            tools = ToolsMenu(doc, digestPanel, prefs),
        )
    }

    private fun rebuildMenuBar() {
        val set = activeMenuSet()
        menuBar.removeAll()
        menuBar.add(set.file.create())
        menuBar.add(set.edit.create())
        menuBar.add(set.view.create())
        menuBar.add(set.tools.create())
        menuBar.revalidate()
        menuBar.repaint()
    }

    private fun buildToolTabs() {
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
    }
}
