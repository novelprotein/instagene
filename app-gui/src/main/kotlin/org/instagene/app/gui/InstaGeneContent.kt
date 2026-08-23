package org.instagene.app.gui

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.document.Doc
import org.instagene.app.gui.document.DocumentHub
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.document.TextDocument
import org.instagene.app.gui.document.TextEditorView
import org.instagene.app.gui.dialog.SettingsDialog
import org.instagene.app.gui.edit.EditHistoryPanel
import org.instagene.app.gui.edit.EditMenu
import org.instagene.app.gui.edit.EditRecorder
import org.instagene.app.gui.edit.SequenceEditActions
import org.instagene.app.gui.edit.TextEditActions
import org.instagene.app.gui.file.FileType
import org.instagene.app.gui.file.FileTypes
import org.instagene.app.gui.menu.FileMenu
import org.instagene.app.gui.menu.HelpMenu
import org.instagene.app.gui.menu.ToolsMenu
import org.instagene.app.gui.menu.ViewMenu
import org.instagene.app.gui.menu.confirmDiscardChanges
import org.instagene.app.gui.menu.menuShortcut
import org.instagene.app.gui.project.BatchOperation
import org.instagene.app.gui.project.ProjectDialogs
import org.instagene.app.gui.project.ProjectTreePanel
import org.instagene.app.gui.tool.DigestPanel
import org.instagene.app.gui.tool.AnalysisPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.app.gui.tool.InfoPanel
import org.instagene.app.gui.tool.LibraryPanel
import org.instagene.app.gui.tool.PlasmidMapPanel
import org.instagene.app.gui.tool.PrimersPanel
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import org.instagene.core.Version
import org.instagene.core.io.SeqIO
import org.instagene.core.project.ProjectSearch
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.JTable
import javax.swing.JOptionPane
import javax.swing.table.DefaultTableModel
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import kotlin.math.roundToInt

/**
 * The entire editor UI, built as a plain [JPanel] so it can be constructed and
 * exercised without a display (headless tests). [InstaGeneWindow] wraps this
 * panel into a `JFrame`; `owner` is that window, used as the parent for file
 * dialogs and title updates, and may be null in headless contexts.
 *
 * Documents open as tabs on [docTabs] (one tab per open document). The tool
 * panels on [toolTabs] below are a single shared set that is rebound to the
 * active document whenever the active tab changes, so switching tabs never
 * rebuilds the panels themselves.
 */
class InstaGeneContent(
    openPath: String? = null,
    openPaths: List<String> = emptyList(),
    private val owner: JFrame? = null,
    private val prefs: Prefs = Prefs(),
    private val ncbiClient: NcbiClient = NcbiClient(),
    private val onRequestClose: () -> Unit = {},
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
    val analysisPanel: AnalysisPanel
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
    private val rightHost = JPanel(CardLayout()).apply {
        // The browser owns its selected width; the editor side absorbs window
        // shrinkage instead of forcing Swing to steal space from the browser.
        minimumSize = Dimension(0, 0)
    }
    private val cardWorking = "working"
    private val cardWelcome = "welcome"

    /**
     * Expands or collapses the file browser. Its icon shows ">" while collapsed
     * and "-" while expanded.
     */
    val fileBrowserToggle = JToggleButton().apply {
        icon = ToggleGlyph(EXPAND)
        selectedIcon = ToggleGlyph(MINIMIZE)
        isRolloverEnabled = true
        toolTipText = "Minimize or restore the file browser"
        addActionListener { setFileBrowserVisible(isSelected) }
    }

    val projectSearchField = JTextField(14).apply {
        toolTipText = "Search project file names, sequences, features, primers, descriptions, and metadata."
        addActionListener { showProjectSearch() }
    }

    private val projectSearchButton = JButton("Search").apply {
        toolTipText = "Search all supported sequence files in the current project."
        addActionListener { showProjectSearch() }
    }

    /** Keeps the search controls on one responsive row beside the browser toggle. */
    private val projectSearchControls = JPanel(BorderLayout(4, 0)).apply {
        minimumSize = Dimension(0, projectSearchButton.preferredSize.height)
        add(projectSearchField, BorderLayout.CENTER)
        add(projectSearchButton, BorderLayout.EAST)
    }

    /** The header strip of the file browser; its toggle alone remains when the browser is minimized. */
    val fileBrowserHeader = JPanel(BorderLayout(4, 0)).apply {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        add(fileBrowserToggle, BorderLayout.WEST)
        add(projectSearchControls, BorderLayout.CENTER)
    }

    /**
     * Whether the left-hand file browser is currently expanded.
     * The browser starts minimized because no project is open on launch; it is
     * expanded by the user through the header toggle or the View menu.
     */
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

    /** A persisted project ratio waiting to be converted to pixels once the split has a real width. */
    private var pendingTreeRatio: Double? = null

    /** Prevents programmatic collapse/restore moves from replacing [lastTreeWidth]. */
    private var applyingFileBrowserLayout = false

    /** Records a new fixed width only after an actual divider drag finishes. */
    private val treeDividerMouseListener = object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
            rememberExpandedTreeWidth()
        }
    }

    /** The current look-and-feel's divider, which is replaced when the theme changes. */
    private var trackedTreeDivider: BasicSplitPaneDivider? = null

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

    /** The "Open Recent" submenu for the empty-state File menu. */
    private val emptyRecentMenu = JMenu("Open Recent").apply {
        val paths = prefs.value.recentFiles.filter { File(it).exists() }
        if (paths.isEmpty()) {
            add(JMenuItem("(none)").apply { isEnabled = false })
        } else {
            for (path in paths) {
                add(JMenuItem(File(path).name).apply {
                    toolTipText = path
                    addActionListener { openFileInTab(File(path)) }
                })
            }
        }
    }

    init {
        val requestedPaths = buildList {
            openPath?.let(::add)
            addAll(openPaths)
        }.distinct()
        val initial = SeqDocument(Seq(""))

        sequenceView = SequenceView(initial)
        textEditorView = TextEditorView(TextDocument())
        digestPanel = DigestPanel(
            initial,
            { seq ->
                openSequence(seq)
                toolTabs.selectedIndex = toolTabs.indexOfTab("Sequence")
            },
            { start, end -> sequenceView.revealRange(start, end) },
            prefs,
        )
        analysisPanel = AnalysisPanel(
            initial,
            { seq ->
                openSequence(seq)
                toolTabs.selectedIndex = toolTabs.indexOfTab("Sequence")
            },
            { start, end -> sequenceView.revealRange(start, end) },
            ncbiClient,
            prefs = prefs,
        )
        plasmidMapPanel = PlasmidMapPanel(initial).apply {
            onSelect = { start, end -> sequenceView.revealRange(start, end) }
        }
        featuresPanel = FeaturesPanel(initial, prefs) { start, end -> sequenceView.revealRange(start, end) }
        primersPanel = PrimersPanel(initial, prefs)
        infoPanel = InfoPanel(initial) { openFile() }
        libraryPanel = LibraryPanel(prefs, initial, sequenceView) { seq ->
            openSequence(seq)
            toolTabs.selectedIndex = toolTabs.indexOfTab("Sequence")
        }
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
            resizeWeight = 0.0
            dividerLocation = 180
        }
        installTreeDividerListener()
        projectSplit.addPropertyChangeListener("UI") { installTreeDividerListener() }
        projectSplit.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (fileBrowserVisible) applyExpandedTreeWidth()
            }
        })
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
        if (requestedPaths.isEmpty()) {
            // With no file to open the window starts in the welcome state.
        } else {
            requestedPaths.forEach { path -> openFileInTab(File(path)) }
        }

        onActiveDocumentChanged()
        rebuildMenuBar()
    }

    /** Releases resources held by shared panels when the containing window closes. */
    fun dispose() {
        projectTreePanel.stopWatching()
        trackedTreeDivider?.removeMouseListener(treeDividerMouseListener)
        trackedTreeDivider = null
        digestPanel.dispose()
        analysisPanel.detachedWindows.toList().forEach { it.dispose() }
    }

    private fun showProjectSearch(promptIfBlank: Boolean = false) {
        val root = project?.root ?: projectTreePanel.projectRoot()
        var query = projectSearchField.text.trim()
        if (query.isEmpty() && promptIfBlank) {
            query = JOptionPane.showInputDialog(owner, "Search project:", "Project Search", JOptionPane.QUESTION_MESSAGE)
                ?.trim()
                .orEmpty()
            if (query.isNotEmpty()) projectSearchField.text = query
        }
        if (root == null || query.isEmpty()) return
        val hits = runCatching { ProjectSearch.search(root, query) }.getOrElse {
            JOptionPane.showMessageDialog(owner, it.message ?: "Project search failed", "Project Search", JOptionPane.ERROR_MESSAGE)
            return
        }
        val model = object : DefaultTableModel(arrayOf("File", "Matched", "Result"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        hits.forEach { model.addRow(arrayOf(root.toPath().relativize(it.file.toPath()).toString(), it.field.name.lowercase(), it.summary)) }
        val table = JTable(model).apply {
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
            toolTipText = "Double-click a result to open its sequence file."
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) hits.getOrNull(selectedRow)?.let { openFileInTab(it.file) }
                }
            })
        }
        JOptionPane.showMessageDialog(
            owner,
            JScrollPane(table).apply { preferredSize = Dimension(720, 320) },
            "Project Search — ${hits.size} result(s)",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun showProjectCollections() {
        val p = project ?: return
        ProjectDialogs.showCollections(owner, p, projectTreePanel.selectedProjectFile()?.takeIf(File::isFile)) { file ->
            openFileInTab(file)
        }
        projectTreePanel.refresh()
    }

    private fun showBatchOperation(operation: BatchOperation) {
        val p = project ?: return
        ProjectDialogs.showBatch(owner, p, operation, selectedProjectFiles()) {
            projectTreePanel.refresh()
        }
    }

    private fun selectedProjectFiles(): List<File> {
        val p = project ?: return emptyList()
        val selected = projectTreePanel.selectedProjectFile()
        return when {
            selected?.isFile == true -> listOf(selected)
            selected?.isDirectory == true -> selected.walkTopDown()
                .filter { it.isFile && p.relativePath(it) != null && FileTypes.classify(it) == FileType.SEQUENCE }
                .toList()
            else -> activeDoc?.file?.takeIf { p.relativePath(it) != null }?.let(::listOf).orEmpty()
        }
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

    /** Fetches an NCBI accession in the background and opens it as a new tab. */
    private fun fetchNcbiAccession(input: String) {
        statusBar.setMessage("Fetching NCBI record: $input...")
        Thread {
            try {
                val seq = ncbiClient.fetchGenBank(input.trim())
                SwingUtilities.invokeLater {
                    openSequence(seq)
                    statusBar.setMessage("Opened $input")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        owner,
                        "Error fetching from NCBI:\n${e.message ?: "Unknown error"}",
                        "NCBI Fetch Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                    statusBar.setMessage("Ready")
                }
            }
        }.apply { isDaemon = true; name = "NCBIFetch-$input" }.start()
    }

    /** Opens [text] in a new text-editor tab and activates it. */
    fun openText(text: String = "", file: File? = null): TextDocument {
        val doc = TextDocument(text, file)
        addDocument(doc)
        return doc
    }

    /**
     * Opens a file picker restricted to sequence files and places the selected
     * file in a new tab. Other file types are opened through the project tree.
     */
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
     * existing tab is activated instead, preventing duplicate tabs for the same file.
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
        // When the last tab closes the welcome screen is shown automatically
        // by updateWorkingState() via onHubChanged(DOCS_CHANGED).
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
        rebuildMenuBar()
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
        rebuildMenuBar()
        // Convert the project's saved ratio to a fixed pixel width once the
        // split has been laid out. Until then, retain the ratio for persistence.
        pendingTreeRatio = opened.manifest.layout.treeSplitRatio.coerceIn(0.0, 1.0)
        if (fileBrowserVisible) applyExpandedTreeWidth()
        val files = opened.openDocuments()
        if (files.isEmpty()) {
            toolTabs.selectedIndex = opened.manifest.layout.activeToolTab.coerceIn(0, toolTabs.tabCount - 1)
            persistProject()
            updateWorkingState()
            rebuildMenuBar()
            projectTreePanel.startWatching()
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
                rebuildMenuBar()
                projectTreePanel.startWatching()
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

    /**
     * Closes the current project: persists its state, closes all open tabs
     * (prompting for unsaved changes), detaches the project tree and edit
     * recorder, and shows the welcome screen.
     */
    fun closeProject() {
        if (project == null) return
        if (!confirmCloseAll(owner)) return
        // Close all tabs first (force since we already confirmed).
        while (hub.openDocuments.isNotEmpty()) {
            closeTab(hub.openDocuments.last(), force = true)
        }
        persistProject()
        project = null
        projectTreePanel.setProject(null)
        editRecorder.setProject(null, created = false)
        rebuildMenuBar()
        updateWorkingState()
    }

    /** The fixed tree width represented as the 0..1 ratio used by the project manifest. */
    private fun splitRatio(): Double {
        pendingTreeRatio?.let { return it }
        val w = projectSplit.width
        val treeWidth = if (fileBrowserVisible) projectSplit.dividerLocation else lastTreeWidth
        return if (w > 0) (treeWidth.toDouble() / w).coerceIn(0.0, 1.0)
        else project?.manifest?.layout?.treeSplitRatio ?: DEFAULT_TREE_RATIO
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
                // Document tabs only provide the project-tab strip; the shared
                // editor below owns all document content. A normal empty panel
                // has a non-zero preferred height, which leaves a visible gap
                // before the tool tabs.
                docTabs.addTab("", zeroSizeTabContent())
                docTabs.setTabComponentAt(docTabs.tabCount - 1, tabComponentFor(doc))
            }
            val index = if (active != null) hub.indexOf(active) else 0
            if (index in 0 until docTabs.tabCount) docTabs.selectedIndex = index
        } finally {
            inSync = false
        }
    }

    /** A tab content placeholder that cannot reserve vertical space below the tab strip. */
    private fun zeroSizeTabContent(): JPanel = object : JPanel() {
        override fun getPreferredSize(): Dimension = Dimension(0, 0)
        override fun getMinimumSize(): Dimension = Dimension(0, 0)
        override fun getMaximumSize(): Dimension = Dimension(0, 0)
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

    /** Binds every shared panel and menu to the newly active document. */
    private fun onActiveDocumentChanged() {
        val active = hub.active ?: return
        if (active is SeqDocument) {
            sequenceView.bindDocument(active)
            digestPanel.bindDocument(active)
            analysisPanel.bindDocument(active)
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
                onExit = onRequestClose,
                onTitleChanged = { updateTitle() },
            ),
            edit = EditMenu(
                owner,
                doc,
                if (doc is SeqDocument) SequenceEditActions(sequenceView, doc) else TextEditActions(textEditorView),
                prefs,
                featuresPanel = featuresPanel,
                sequenceView = sequenceView,
                onEditProperties = {
                    toolTabs.selectedIndex = toolTabs.indexOfTab("Info")
                    infoPanel.nameField.requestFocusInWindow()
                },
            ),
            view = ViewMenu(
                menusDoc,
                sequenceView,
                isFileBrowserVisible = { fileBrowserVisible },
                onFileBrowserVisible = { visible -> setFileBrowserVisible(visible) },
                onSelectToolTab = { name -> toolTabs.selectedIndex = toolTabs.indexOfTab(name) },
            ),
            tools = ToolsMenu(
                menusDoc,
                digestPanel,
                prefs,
                featuresPanel = featuresPanel,
                primersPanel = primersPanel,
                libraryPanel = libraryPanel,
                onAnalysis = { name ->
                    analysisPanel.selectTool(name)
                    toolTabs.selectedIndex = toolTabs.indexOfTab("Analysis")
                },
                onFetchNcbi = { input -> fetchNcbiAccession(input) },
            ),
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
            menuBar.add(createProjectMenu())
            menuBar.add(set.tools.createActions().apply { isEnabled = sequence })
            menuBar.add(set.tools.create().apply { isEnabled = sequence })
            menuBar.add(HelpMenu().create())
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
            add(emptyRecentMenu)
            addSeparator()
            add(menuItem("Close Tab", KeyEvent.VK_W, menuShortcut(KeyEvent.VK_W)) { closeTab(activeDoc ?: newDocument()) })
            addSeparator()
            add(menuItem("Save", KeyEvent.VK_S, menuShortcut(KeyEvent.VK_S)) { activeFileMenu().saveFile() })
            add(menuItem("Save As...", KeyEvent.VK_A, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)) { activeFileMenu().saveFileAs() })
            addSeparator()
            add(menuItem("Preferences...") { SettingsDialog.showPreferences(null, prefs) })
            add(menuItem("Settings...") { SettingsDialog.showSystemSettings(null) })
            addSeparator()
            add(menuItem("Exit", action = onRequestClose))
        })
        menuBar.add(emptyStateMenuSet.edit.create().apply { isEnabled = false })
        menuBar.add(JMenu("View").apply {
            mnemonic = KeyEvent.VK_V
            emptyViewBrowserItem.isSelected = fileBrowserVisible
            add(emptyViewBrowserItem)
        })
        menuBar.add(createProjectMenu())
        menuBar.add(JMenu("Actions").apply { isEnabled = false })
        menuBar.add(emptyStateMenuSet.tools.create().apply { isEnabled = false })
        menuBar.add(HelpMenu().create())
    }

    private fun createProjectMenu(): JMenu = JMenu("Project").apply {
        mnemonic = KeyEvent.VK_P
        val hasProject = project != null
        add(menuItem("New Project...") { newProject() })
        add(menuItem("Open Project...", KeyEvent.VK_P, shiftShortcut(KeyEvent.VK_P)) { openProject() })
        add(menuItem("Close Project") { closeProject() }.apply { isEnabled = hasProject })
        addSeparator()
        add(menuItem("Search Project...") { showProjectSearch(promptIfBlank = true) }.apply { isEnabled = hasProject })
        add(menuItem("Collections...") { showProjectCollections() }.apply { isEnabled = hasProject })
        addSeparator()
        add(menuItem("Batch Convert...") { showBatchOperation(BatchOperation.CONVERT) }.apply { isEnabled = hasProject })
        add(menuItem("Batch Annotate...") { showBatchOperation(BatchOperation.ANNOTATE) }.apply { isEnabled = hasProject })
        add(menuItem("Batch Update Properties...") { showBatchOperation(BatchOperation.PROPERTIES) }.apply { isEnabled = hasProject })
        addSeparator()
        add(JMenu("Recent Projects").apply {
            val paths = prefs.value.recentProjects.filter { File(it).exists() }
            if (paths.isEmpty()) {
                add(JMenuItem("(none)").apply { isEnabled = false })
            } else {
                for (path in paths) {
                    add(JMenuItem(File(path).name).apply {
                        toolTipText = path
                        addActionListener { openProjectAt(File(path)) }
                    })
                }
            }
        })
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
            addTab("Analysis", analysisPanel)
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
     * sync. While expanded the browser keeps the pixel width selected by the
     * user through ordinary resizing and fullscreen transitions. While
     * minimized it collapses to the button-wide header with no margins or
     * divider and a zero resize weight.
     */
    private fun applyFileBrowserVisible(visible: Boolean) {
        treeScroll.isVisible = visible
        projectSearchField.isVisible = visible
        projectSearchButton.isVisible = visible
        projectSearchControls.isVisible = visible
        fileBrowserHeader.border = BorderFactory.createEmptyBorder(
            if (visible) 4 else 0,
            if (visible) 4 else 0,
            if (visible) 4 else 0,
            if (visible) 4 else 0,
        )
        applyingFileBrowserLayout = true
        try {
            if (visible) {
                if (expandedDividerSize < 0) expandedDividerSize = projectSplit.dividerSize.coerceAtLeast(1)
                projectSplit.leftComponent = fileBrowserPanel
                projectSplit.dividerSize = expandedDividerSize
                applyExpandedTreeWidth()
            } else {
                expandedDividerSize = projectSplit.dividerSize.coerceAtLeast(1)
                projectSplit.leftComponent = fileBrowserPanel
                projectSplit.dividerSize = 0
                projectSplit.resizeWeight = 0.0
                projectSplit.dividerLocation = fileBrowserHeader.preferredSize.width
            }
        } finally {
            applyingFileBrowserLayout = false
        }
        fileBrowserToggle.isSelected = visible
        revalidate()
        repaint()
    }

    /** Tracks the divider supplied by the active look and feel. */
    private fun installTreeDividerListener() {
        val divider = (projectSplit.ui as? BasicSplitPaneUI)?.divider
        if (trackedTreeDivider === divider) return
        trackedTreeDivider?.removeMouseListener(treeDividerMouseListener)
        trackedTreeDivider = divider
        divider?.addMouseListener(treeDividerMouseListener)
    }

    /** Remembers a divider position selected by the user as a fixed pixel width. */
    private fun rememberExpandedTreeWidth() {
        if (!fileBrowserVisible || applyingFileBrowserLayout) return
        lastTreeWidth = projectSplit.dividerLocation
        pendingTreeRatio = null
    }

    /** Restores the expanded browser at its fixed width, converting a saved ratio once if needed. */
    private fun applyExpandedTreeWidth() {
        val width = projectSplit.width
        if (width > 0) {
            pendingTreeRatio?.let { ratio ->
                lastTreeWidth = (ratio * width).roundToInt()
                pendingTreeRatio = null
            }
        }
        val wasApplying = applyingFileBrowserLayout
        applyingFileBrowserLayout = true
        try {
            if (projectSplit.resizeWeight != 0.0) projectSplit.resizeWeight = 0.0
            if (projectSplit.dividerLocation != lastTreeWidth) projectSplit.dividerLocation = lastTreeWidth
        } finally {
            applyingFileBrowserLayout = wasApplying
        }
    }
}

/** The toggle glyphs: [EXPAND] draws ">", [MINIMIZE] draws "-". */
private const val EXPAND = 0
private const val MINIMIZE = 1
private const val DEFAULT_TREE_RATIO = 0.15

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
