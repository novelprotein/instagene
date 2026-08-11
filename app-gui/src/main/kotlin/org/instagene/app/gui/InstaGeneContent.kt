package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.Version
import org.instagene.core.io.SeqIO
import org.instagene.core.project.ProjectLayout
import org.instagene.core.project.SeqProject
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.KeyStroke
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

    /** The parent window, used for dialogs and the system-app opener; null in headless contexts. */
    val parentWindow: JFrame? get() = owner

    /** One tab per open document. */
    val docTabs = JTabbedPane()

    /** The tool panels (Info/Map/Sequence/Enzyme/Features/Primers/Library/History), shared across documents. */
    val toolTabs = JTabbedPane()

    /**
     * The document on the selected tab, when it is a sequence; creates a fresh
     * empty sequence document when there is none. Sequence-only callers (the
     * shared tool panels) use this; code that must also handle text documents
     * reads [activeDoc] instead.
     */
    val activeDocument: SeqDocument get() = activeDoc as? SeqDocument ?: newDocument()

    /** The document on the selected tab, whatever its kind. */
    val activeDoc: Doc? get() = hub.active

    /** The active document (alias kept for callers of the single-document API). */
    val doc: SeqDocument get() = activeDocument

    val sequenceView: SequenceView
    val textEditorView: TextEditorView
    val digestPanel: DigestPanel
    val plasmidMapPanel: PlasmidMapPanel
    val featuresPanel: FeaturesPanel
    val primersPanel: PrimersPanel
    val infoPanel: InfoPanel
    val libraryPanel: LibraryPanel
    val statusBar: StatusBar
    val menuBar = JMenuBar()

    /** Records and persists the current project's edit history (`.instagene/history.json`). */
    val editRecorder = EditRecorder()

    /** The Edit History tool tab, bound to [editRecorder]. */
    val editHistoryPanel: EditHistoryPanel

    /** Swaps between the shared tool tabs and the text editor as the active tab changes. */
    val editorHost = JPanel(CardLayout())
    private val cardTools = "tools"
    private val cardText = "text"

    /** The project file tree and the split that separates it from the editor. */
    val projectSplit: JSplitPane
    val projectTreePanel: ProjectTreePanel
    private val treeScroll: JScrollPane

    /** The file browser: [fileBrowserHeader] with the toggleable tree section below it. */
    val fileBrowserPanel: JPanel

    /** The empty state: shown whenever no documents are open. */
    val welcomePanel: WelcomePanel

    /** The card that holds the working (editor) view and the welcome view. */
    private val rightHost = JPanel(CardLayout())
    private val cardWorking = "working"
    private val cardWelcome = "welcome"

    /** The toggle that expands/collapses the file browser, living in its header strip.
     * Textless: ">" while collapsed (restore) and "-" while expanded (minimize). */
    val fileBrowserToggle = JToggleButton().apply {
        icon = ToggleGlyph(EXPAND)
        selectedIcon = ToggleGlyph(MINIMIZE)
        isRolloverEnabled = true
        toolTipText = "Minimize or restore the file browser"
        addActionListener { setFileBrowserVisible(isSelected) }
    }

    /** The header strip of the file browser; its toggle alone remains when the browser is minimized. */
    val fileBrowserHeader = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        add(fileBrowserToggle)
    }

    /** Whether the left-hand file browser is currently expanded.
     * The browser starts minimized because no project is open on launch; it is
     * expanded by the user through the header toggle or the View menu. */
    var fileBrowserVisible: Boolean = false
        private set

    /** The "Show File Browser" toggle of the empty-state View menu, kept in sync with the browser. */
    private val emptyViewBrowserItem = JCheckBoxMenuItem("Show File Browser", false).apply {
        accelerator = menuShortcut(KeyEvent.VK_B)
        addActionListener { setFileBrowserVisible(isSelected) }
    }

    /** Whether the tree section of the file browser is shown (false when minimized). */
    val fileBrowserTreeVisible: Boolean
        get() = treeScroll.isVisible

    /** The tree width to restore when the browser is re-expanded. */
    private var lastTreeWidth = 180

    /** The split divider's width while the browser is expanded; restored when it is re-expanded. */
    private var expandedDividerSize = -1

    /** True once closing the last tab requested the window to close (tests observe this). */
    var exitPending: Boolean = false
        private set

    private val hub = DocumentHub<Doc>()

    /** Per-document menus; created lazily the first time a document is activated. */
    private val menus = HashMap<Doc, MenuSet>()

    /** The currently open project, or null when the window is not attached to a project. */
    private var project: SeqProject? = null

    /** True while [openProjectAt] is restoring documents, so intermediate states are not persisted. */
    private var loadingProject = false

    /** The last file each document was saved to, to catch save-as in [onDocChanged]. */
    private val recordedFile = HashMap<Doc, File?>()

    /** True while [syncDocTabs] is rebuilding the tab strip; its own tab events must be ignored. */
    private var inSync = false

    /** The title label of each tab, so dirty markers can be updated without rebuilding. */
    private val tabLabels = HashMap<Doc, JLabel>()

    private class MenuSet(
        val file: FileMenu,
        val edit: EditMenu,
        val view: ViewMenu,
        val tools: ToolsMenu,
    )

    /** A shared throwaway empty sequence document backing the Edit/View/Tools menus while no sequence is open. */
    private val placeholderSeq by lazy { SeqDocument(Seq("")) }

    /** A menu set bound to a throwaway empty sequence document, used only while nothing is open. */
    private val emptyStateMenuSet by lazy { menuSetFor(placeholderSeq) }

    init {
        val initialFile = if (openPath != null && File(openPath).exists()) File(openPath) else null
        val initialSeq = initialFile?.let { runCatching { SeqIO.read(it) }.getOrNull() } ?: Seq("")
        val initial = SeqDocument(initialSeq, initialFile)

        sequenceView = SequenceView(initial)
        textEditorView = TextEditorView(TextDocument())
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
        editHistoryPanel = EditHistoryPanel(editRecorder)

        docTabs.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        docTabs.addChangeListener { onDocTabSelected() }

        editorHost.add(buildToolTabs(), cardTools)
        editorHost.add(textEditorView, cardText)
        welcomePanel = WelcomePanel(
            prefs,
            onOpenFile = { openFile() },
            onOpenProject = { openProject() },
            onNewDocument = { newDocument() },
            onOpenRecentFile = { openFileInTab(it) },
            onOpenRecentProject = { openProjectAt(it) },
        )
        projectTreePanel = ProjectTreePanel(
            onOpenFile = { file -> openFileInTab(file) },
            onOpenInFolder = { dir -> if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir) },
            onOpenWithSystem = { file -> if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file) },
            openFiles = { hub.openDocuments.mapNotNull { it.file } },
        )
        treeScroll = JScrollPane(projectTreePanel)
        fileBrowserPanel = JPanel(BorderLayout()).apply {
            add(fileBrowserHeader, BorderLayout.NORTH)
            add(treeScroll, BorderLayout.CENTER)
        }
        rightHost.add(buildWorkingPanel(), cardWorking)
        rightHost.add(welcomePanel, cardWelcome)
        projectSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            fileBrowserPanel,
            rightHost,
        ).apply {
            isContinuousLayout = true
            resizeWeight = 0.15
            dividerLocation = 180
        }
        add(projectSplit, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)

        toolTabs.addChangeListener { prefs.update { it.copy(activeTab = toolTabs.selectedIndex) } }
        toolTabs.selectedIndex = prefs.value.activeTab.coerceIn(0, toolTabs.tabCount - 1)

        applyFileBrowserVisible(fileBrowserVisible)
        updateWorkingState()

        // Registered last so the first document's change events (and everything
        // else that fired during construction) see fully-initialized fields.
        hub.addListener { _, reason -> onHubChanged(reason) }
        // With no file to open the window starts in the welcome state: nothing
        // is open until the user opens a file, a project or starts fresh.
        if (initialFile != null) addDocument(initial)

        onActiveDocumentChanged()
        rebuildMenuBar()
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

    /** Opens [text] in a new text-editor tab and activates it. */
    fun openText(text: String = "", file: File? = null): TextDocument {
        val doc = TextDocument(text, file)
        addDocument(doc)
        return doc
    }

    /** Opens a file picker restricted to sequence files; the chosen file lands in a new tab.
     * Non-sequence files (text, images, PDFs) are only reachable in the context of a
     * project, e.g. through the project tree, so a project never needs another editor. */
    fun openFile() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileTypes.sequenceFileFilter()
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            openFileInTab(chooser.selectedFile)
        }
    }

    /**
     * Opens [file] in a new tab (or hands it to the system app when it is not
     * editable in-app), dispatching on its type. If it is already open the
     * existing tab is activated instead, so re-opening a file never duplicates it.
     */
    fun openFileInTab(file: File) {
        hub.documentFor(file)?.let { hub.activate(it) } ?: Openers.forFile(file).open(this, file)
    }

    /** Records [file] as a recent file for the menus of [doc]. */
    fun noteRecent(doc: Doc, file: File) {
        menus[doc]?.file?.addRecent(file)
    }

    /** Records [root] as a recent project (most recent first, capped at 10). */
    private fun noteRecentProject(root: File) {
        val path = root.absolutePath
        prefs.update {
            it.copy(recentProjects = (listOf(path) + it.recentProjects.filter { p -> p != path }).take(10))
        }
    }

    /**
     * Closes the tab of [doc], prompting for unsaved changes unless [force].
     * Closing the last tab exits the program.
     */
    fun closeTab(doc: Doc, force: Boolean = false): Boolean {
        if (!hub.contains(doc)) return false
        if (!force && !confirmDiscardChanges(owner, doc)) return false
        editRecorder.recordDocumentClosed(doc)
        editRecorder.unbind(doc)
        hub.remove(doc)
        menus.remove(doc)
        recordedFile.remove(doc)
        tabLabels.remove(doc)
        if (hub.openDocuments.isEmpty()) {
            persistProject()
            exitPending = true
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
        noteRecentProject(root)
        project = SeqProject.create(root).also { it.save() }
        projectTreePanel.setProject(project)
        editRecorder.setProject(project, created = true)
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
        noteRecentProject(root)
        project = opened
        projectTreePanel.setProject(opened)
        editRecorder.setProject(opened, created = false)
        // The saved tree split only applies while the browser is actually
        // shown; while collapsed it stays a button-wide strip with no blank pane.
        if (fileBrowserVisible) projectSplit.setDividerLocation(opened.manifest.layout.treeSplitRatio)
        val files = opened.openDocuments()
        if (files.isEmpty()) {
            toolTabs.selectedIndex = opened.manifest.layout.activeToolTab.coerceIn(0, toolTabs.tabCount - 1)
            persistProject()
            return
        }
        loadingProject = true
        Thread {
            val loaded = files.mapNotNull { f ->
                when (FileTypes.classify(f)) {
                    FileType.SEQUENCE -> runCatching { SeqIO.read(f) }.getOrNull()?.let { Opened.Sequence(it, f) }
                    FileType.TEXT -> runCatching { f.readText() }.getOrNull()?.let { Opened.Text(it, f) }
                    // Images, PDFs and unknown binaries stay on disk; the tree
                    // panel still lists them for "open in folder".
                    else -> null
                }
            }
            SwingUtilities.invokeLater {
                loaded.forEach { item ->
                    if (hub.documentFor(item.file) == null) {
                        when (item) {
                            is Opened.Sequence -> openSequence(item.seq, item.file)
                            is Opened.Text -> openText(item.text, item.file)
                        }
                    }
                }
                opened.activeDocument()?.let { active -> hub.documentFor(active)?.let { hub.activate(it) } }
                toolTabs.selectedIndex = opened.manifest.layout.activeToolTab.coerceIn(0, toolTabs.tabCount - 1)
                loadingProject = false
                persistProject()
            }
        }.apply { isDaemon = false; name = "ProjectLoader" }.start()
    }

    /** A document recovered from disk during [openProjectAt], already classified. */
    private sealed class Opened(val file: File) {
        class Sequence(val seq: Seq, file: File) : Opened(file)
        class Text(val text: String, file: File) : Opened(file)
    }

    /**
     * Persists the project manifest (open set, active tab, layout). Only
     * file-backed documents inside the project are recorded.
     */
    fun persistProject() {
        val p = project ?: return
        p.setOpenDocuments(hub.openDocuments.mapNotNull { it.file })
        val active = activeDoc?.file
        p.setActive(if (active != null && p.relativePath(active) != null) active else null)
        p.setLayout(ProjectLayout(activeToolTab = toolTabs.selectedIndex, treeSplitRatio = splitRatio()))
        p.save()
        editRecorder.flush()
    }

    /** The current tree split as a 0..1 ratio, defaulting to the persisted value off-screen. */
    private fun splitRatio(): Double {
        if (!fileBrowserVisible) return project?.manifest?.layout?.treeSplitRatio ?: 0.25
        val w = projectSplit.width
        return if (w > 0) projectSplit.dividerLocation.toDouble() / w
        else project?.manifest?.layout?.treeSplitRatio ?: 0.25
    }

    // ---------------------------------------------------------------- internals

    private fun addDocument(doc: Doc): Doc {
        doc.addDocListener { onDocChanged(it) }
        recordedFile[doc] = doc.file
        hub.add(doc)
        editRecorder.bind(doc)
        // Restored project documents are not re-logged as opens: they are already part of the history.
        if (!loadingProject) editRecorder.recordDocumentOpened(doc)
        return doc
    }

    private fun onDocChanged(doc: Doc) {
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
        if (reason == DocumentHub.Reason.DOCS_CHANGED) projectTreePanel.refresh()
        updateWorkingState()
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
    private fun tabComponentFor(doc: Doc): JComponent {
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

    private fun tabTitle(doc: Doc): String {
        val name = doc.displayName
        return if (doc.isDirty) "$name *" else name
    }

    private fun updateTabText(doc: Doc) {
        tabLabels[doc]?.text = tabTitle(doc)
    }

    /** The text currently shown on [doc]'s tab, e.g. "name *" when it is dirty. */
    fun tabLabelText(doc: Doc): String = tabLabels[doc]?.text ?: tabTitle(doc)

    /** Re-points every shared panel and the menus at the newly active document. */
    private fun onActiveDocumentChanged() {
        val active = hub.active ?: return
        if (active is SeqDocument) {
            sequenceView.bindDocument(active)
            digestPanel.bindDocument(active)
            plasmidMapPanel.bindDocument(active)
            featuresPanel.bindDocument(active)
            primersPanel.bindDocument(active)
            infoPanel.bindDocument(active)
            libraryPanel.bindDocument(active)
            statusBar.bindDocument(active)
            (editorHost.layout as CardLayout).show(editorHost, cardTools)
        } else {
            textEditorView.bindDocument(active as? TextDocument ?: return)
            (editorHost.layout as CardLayout).show(editorHost, cardText)
        }
        rebuildMenuBar()
        updateTitle()
    }

    private fun updateTitle() {
        val active = hub.active
        if (active == null) {
            owner?.title = "InstaGene ${Version.VERSION} - Sequence Editor"
            return
        }
        val filename = active.file?.name ?: "Untitled"
        val dirty = if (active.isDirty) "*" else ""
        owner?.title = "InstaGene ${Version.VERSION} - $filename$dirty"
    }

    private fun activeMenuSet(): MenuSet = activeDoc?.let { menuSetFor(it) } ?: emptyStateMenuSet

    /** The File menu of the currently active document (used by the openers). */
    fun activeFileMenu(): FileMenu = activeMenuSet().file

    private fun menuSetFor(doc: Doc): MenuSet = menus.getOrPut(doc) {
        val menusDoc = doc as? SeqDocument ?: placeholderSeq
        MenuSet(
            file = FileMenu(
                frame = owner,
                doc = doc,
                prefs = prefs,
                onNewDocument = { newDocument() },
                onNewTextDocument = { openText() },
                onOpenDocument = { openFile() },
                onOpenRecent = { openFileInTab(it) },
                onNewProject = { newProject() },
                onOpenProject = { openProject() },
                onCloseTab = { closeTab(activeDoc ?: newDocument()) },
                onExit = { if (confirmCloseAll(owner)) { persistProject(); owner?.dispose() } },
                onTitleChanged = { updateTitle() },
            ),
            edit = EditMenu(
                owner,
                doc,
                if (doc is SeqDocument) SequenceEditActions(sequenceView, doc) else TextEditActions(textEditorView),
                prefs,
            ),
            view = ViewMenu(
                menusDoc,
                sequenceView,
                prefs,
                isFileBrowserVisible = { fileBrowserVisible },
                onFileBrowserVisible = { visible -> setFileBrowserVisible(visible) },
            ),
            tools = ToolsMenu(menusDoc, digestPanel, prefs),
        )
    }

    private fun rebuildMenuBar() {
        menuBar.removeAll()
        val active = hub.active
        if (active == null) {
            buildEmptyMenuBar()
        } else {
            val set = menuSetFor(active)
            val sequence = active is SeqDocument
            menuBar.add(set.file.create())
            menuBar.add(set.edit.create())
            menuBar.add(set.view.create().apply { isEnabled = sequence })
            menuBar.add(set.tools.create().apply { isEnabled = sequence })
        }
        menuBar.revalidate()
        menuBar.repaint()
    }

    /**
     * The menu bar with no document open: the full set of top-level options
     * (File, Edit, View, Tools), with the sequence-only menus shown disabled.
     */
    private fun buildEmptyMenuBar() {
        menuBar.add(JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add(menuItem("New", KeyEvent.VK_N, menuShortcut(KeyEvent.VK_N)) { newDocument() })
            add(menuItem("New Text File", KeyEvent.VK_T, shiftShortcut(KeyEvent.VK_T)) { openText() })
            add(menuItem("Open...", KeyEvent.VK_O, menuShortcut(KeyEvent.VK_O)) { openFile() })
            addSeparator()
            add(menuItem("New Project...") { newProject() })
            add(menuItem("Open Project...", KeyEvent.VK_P, shiftShortcut(KeyEvent.VK_P)) { openProject() })
            addSeparator()
            add(menuItem("Exit") { if (confirmCloseAll(owner)) { persistProject(); owner?.dispose() } })
        })
        menuBar.add(emptyStateMenuSet.edit.create().apply { isEnabled = false })
        menuBar.add(JMenu("View").apply {
            mnemonic = KeyEvent.VK_V
            emptyViewBrowserItem.isSelected = fileBrowserVisible
            add(emptyViewBrowserItem)
        })
        menuBar.add(emptyStateMenuSet.tools.create().apply { isEnabled = false })
    }

    private fun menuItem(label: String, mnemonic: Int? = null, accelerator: KeyStroke? = null, action: () -> Unit): JMenuItem =
        JMenuItem(label, mnemonic ?: 0).apply {
            if (accelerator != null) this.accelerator = accelerator
            addActionListener { action() }
        }

    /** The menu accelerator for [keyCode] plus the shift modifier. */
    private fun shiftShortcut(keyCode: Int): KeyStroke {
        val base = menuShortcut(keyCode)
        return KeyStroke.getKeyStroke(keyCode, base.modifiers or InputEvent.SHIFT_DOWN_MASK)
    }

    private fun buildToolTabs(): JTabbedPane {
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
            addTab("History", editHistoryPanel)
        }
        return toolTabs
    }

    /** The tab strip and editor of the working view. */
    private fun buildWorkingPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            add(docTabs, BorderLayout.NORTH)
            add(editorHost, BorderLayout.CENTER)
        }
    }

    /** Shows the welcome (empty) or working (editor) card depending on the open documents. */
    private fun updateWorkingState() {
        val empty = hub.openDocuments.isEmpty()
        (rightHost.layout as CardLayout).show(rightHost, if (empty) cardWelcome else cardWorking)
        updateTitle()
    }

    /** Shows or hides the left-hand file browser, persisting the choice. */
    fun setFileBrowserVisible(visible: Boolean) {
        if (fileBrowserVisible == visible) return
        fileBrowserVisible = visible
        prefs.update { it.copy(fileBrowserVisible = visible) }
        applyFileBrowserVisible(visible)
        syncFileBrowserItems()
    }

    /** Aligns every "Show File Browser" menu toggle with the browser's actual state. */
    private fun syncFileBrowserItems() {
        emptyViewBrowserItem.isSelected = fileBrowserVisible
        menus.values.forEach { it.view.syncFileBrowser() }
    }

    /**
     * Applies the browser state to the split and keeps the header toggle in
     * sync. While expanded the browser occupies [lastTreeWidth] pixels on the
     * left. While minimized it collapses to the button-wide header with no
     * margins and no divider, and the split's resize weight drops to zero so
     * resizing the window never re-opens a blank pane; the header toggle
     * restores it.
     */
    private fun applyFileBrowserVisible(visible: Boolean) {
        if (!visible && projectSplit.width > 0) lastTreeWidth = projectSplit.dividerLocation
        treeScroll.isVisible = visible
        fileBrowserHeader.layout = FlowLayout(FlowLayout.LEFT, if (visible) 4 else 0, if (visible) 4 else 0)
        if (visible) {
            if (expandedDividerSize < 0) expandedDividerSize = projectSplit.dividerSize.coerceAtLeast(1)
            projectSplit.leftComponent = fileBrowserPanel
            projectSplit.dividerSize = expandedDividerSize
            projectSplit.resizeWeight = 0.15
            projectSplit.dividerLocation = lastTreeWidth
        } else {
            expandedDividerSize = projectSplit.dividerSize.coerceAtLeast(1)
            projectSplit.leftComponent = fileBrowserPanel
            projectSplit.dividerSize = 0
            projectSplit.resizeWeight = 0.0
            projectSplit.dividerLocation = fileBrowserHeader.preferredSize.width
        }
        fileBrowserToggle.isSelected = visible
        revalidate()
        repaint()
    }
}

/** The toggle glyphs: [EXPAND] draws ">", [MINIMIZE] draws "-". */
private const val EXPAND = 0
private const val MINIMIZE = 1

/**
 * A small textless glyph drawn with the component's foreground color, used as
 * the minimize/restore icon of the file browser toggle: ">" while collapsed
 * (restore) and "-" while expanded (minimize).
 */
private class ToggleGlyph(private val kind: Int) : Icon {
    override fun getIconWidth() = 12
    override fun getIconHeight() = 12

    override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = c.foreground
            g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val cx = x + getIconWidth() / 2
            val cy = y + getIconHeight() / 2
            when (kind) {
                EXPAND -> {
                    g2.drawLine(cx - 2, cy - 4, cx + 2, cy)
                    g2.drawLine(cx - 2, cy + 4, cx + 2, cy)
                }
                MINIMIZE -> g2.drawLine(cx - 4, cy, cx + 4, cy)
            }
        } finally {
            g2.dispose()
        }
    }
}
