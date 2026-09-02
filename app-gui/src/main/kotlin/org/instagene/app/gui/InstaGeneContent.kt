@file:Suppress("DuplicatedCode")

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
import org.instagene.app.gui.file.FileOpenBatch
import org.instagene.app.gui.file.FileOpenFailure
import org.instagene.app.gui.file.FileOpenService
import org.instagene.app.gui.file.FileTypes
import org.instagene.app.gui.file.OpenedFile
import org.instagene.app.gui.menu.FileMenu
import org.instagene.app.gui.menu.HelpMenu
import org.instagene.app.gui.menu.ToolsMenu
import org.instagene.app.gui.menu.ViewMenu
import org.instagene.app.gui.menu.confirmDiscardChanges
import org.instagene.app.gui.menu.menuShortcut
import org.instagene.app.gui.menu.menuShortcutWithShift
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
import org.instagene.app.gui.theme.ThemeRefreshable
import org.instagene.core.ElnAdapters
import org.instagene.core.ElnArtifactRole
import org.instagene.core.ElnAttachment
import org.instagene.core.ElnBundleRequest
import org.instagene.core.ElnCopy
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Version
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import org.instagene.core.project.ProjectSearch
import org.instagene.core.project.ProjectLayout
import org.instagene.core.project.ProjectFileRevision
import org.instagene.core.project.ProjectReload
import org.instagene.core.project.ProjectReloadDisposition
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
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.JTextField
import javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT as FocusedAncestorInputMap
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.JTable
import javax.swing.JOptionPane
import javax.swing.WindowConstants
import javax.swing.table.DefaultTableModel
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.filechooser.FileNameExtensionFilter
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
) : JPanel(BorderLayout()), ThemeRefreshable {

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

    /** Latest unified file-open batch, exposed for headless UI regression tests. */
    var lastFileOpenBatch: FileOpenBatch? = null
        private set

    /** One completed item in the background file-open worker. */
    private data class FileOpenProgress(
        val completed: Int,
        val total: Int,
        val opened: OpenedFile? = null,
        val failure: FileOpenFailure? = null,
    )

    private data class OpenProgressDialog(
        val dialog: JDialog,
        val progress: JProgressBar,
    )

    /** Per-document menus; created lazily the first time a document is activated. */
    private val menus = HashMap<Doc, MenuSet>()

    /** The currently open project, or null when the window is not attached to a project. */
    private var project: SeqProject? = null

    /** True while [openProjectAt] is restoring documents, so intermediate states are not persisted. */
    private var loadingProject = false

    /** The last file each document was saved to, to catch save-as in [onDocChanged]. */
    private val recordedFile = HashMap<Doc, File?>()

    /** Last observed on-disk revision for project-backed tabs. Kept separate from a document's dirty baseline. */
    private val projectRevisions = HashMap<Doc, ProjectFileRevision?>()

    /** Coalesces watcher notifications while a reload worker is already comparing files. */
    private var projectReloadPending = false

    /** Human-readable outcome of the most recent external or explicit project reload. */
    private var lastProjectReloadStatus = "No project reload has run."

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
        infoPanel = InfoPanel(initial, { openFile() }, ncbiClient)
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
            onOpenExample = ::openBundledExample,
        )
        projectTreePanel = ProjectTreePanel(
            onOpenFile = { file -> openFileInTab(file) },
            onOpenInFolder = { dir -> if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir) },
            onOpenWithSystem = { file -> if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file) },
            openFiles = { hub.openDocuments.mapNotNull { it.file } },
            onExternalProjectChange = { scheduleProjectReload() },
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
        installCommandPaletteShortcut()

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
            openFiles(requestedPaths.map(::File))
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
        featuresPanel.dispose()
        sequenceView.dispose()
        analysisPanel.detachedWindows.toList().forEach { it.dispose() }
    }

    /** Reattaches look-and-feel-owned pieces that Swing replaces during a theme switch. */
    override fun refreshTheme() {
        installTreeDividerListener()
        revalidate()
        repaint()
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

    /** Opens a complete bundled source record without downloading or creating files. */
    private fun openBundledExample(example: WelcomeExample) {
        SeqIO.Samples.ALL.firstOrNull { it.name.equals(example.sampleName, ignoreCase = true) }
            ?.let(::openSequence)
    }

    /** Opens one or more researcher files or an InstaGene project folder. */
    fun openFile() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isMultiSelectionEnabled = true
            isAcceptAllFileFilterUsed = false
            fileFilter = FileTypes.supportedOpenFileFilter()
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            openFiles((chooser.selectedFiles.toList() + listOfNotNull(chooser.selectedFile)).distinct())
        }
    }

    /**
     * Opens [file] through the same background route as the chooser. Existing
     * document tabs are activated immediately rather than parsed again.
     */
    fun openFileInTab(file: File) {
        openFiles(listOf(file))
    }

    /**
     * Routes a native file-manager drop. A homogeneous ABI/SCF drop onto an
     * active nucleotide document means "verify this reference"; every other
     * drop retains the ordinary, typed file-open behavior.
     */
    fun handleDroppedFiles(files: List<File>) {
        val selected = files.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val reference = activeDoc as? SeqDocument
        val traceDrop = selected.isNotEmpty() && selected.all { FileTypes.classify(it) == FileType.CHROMATOGRAM }
        if (traceDrop && reference != null && reference.seq.kind != SeqKind.PROTEIN && reference.seq.length > 0) {
            alignDroppedReads(reference, selected)
        } else {
            openFiles(selected)
        }
    }

    /** Parses dropped trace files in the background, then opens Sanger verification on the explicit reference tab. */
    private fun alignDroppedReads(reference: SeqDocument, files: List<File>) {
        var worker: SwingWorker<FileOpenBatch, FileOpenProgress>? = null
        val progressDialog = createOpenProgressDialog(files.size) { worker?.cancel(true) }
        statusBar.setMessage("Loading ${files.size} dropped ABI/SCF read(s) for ${reference.seq.name}…")
        worker = object : SwingWorker<FileOpenBatch, FileOpenProgress>() {
            @Volatile private var completedBatch: FileOpenBatch? = null

            override fun doInBackground(): FileOpenBatch {
                var completed = 0
                return FileOpenService.loadAll(
                    files,
                    cancellationRequested = { isCancelled },
                    onResult = { opened ->
                        completed++
                        publish(FileOpenProgress(completed, files.size, opened = opened))
                    },
                    onFailure = { failure ->
                        completed++
                        publish(FileOpenProgress(completed, files.size, failure = failure))
                    },
                ).also { completedBatch = it }
            }

            override fun process(items: MutableList<FileOpenProgress>) {
                items.lastOrNull()?.let { update ->
                    progressDialog?.progress?.value = update.completed
                    statusBar.setMessage("Loading dropped reads: ${update.completed}/${update.total}")
                }
            }

            override fun done() {
                val batch = if (isCancelled) {
                    completedBatch ?: FileOpenBatch(emptyList(), emptyList(), cancelled = true)
                } else runCatching { get() }.getOrElse { error ->
                    FileOpenBatch(emptyList(), listOf(FileOpenFailure(files.first(), error.message ?: "Unable to load dropped read.")))
                }
                lastFileOpenBatch = batch
                progressDialog?.dialog?.dispose()
                val reads = batch.opened.filterIsInstance<OpenedFile.Chromatogram>()
                if (reads.isEmpty()) {
                    statusBar.setMessage("No dropped ABI/SCF reads could be loaded for ${reference.seq.name}.")
                    if (batch.failures.isNotEmpty()) showOpenFailures(batch.failures)
                    return
                }
                if (!hub.contains(reference)) {
                    statusBar.setMessage("Dropped reads were loaded, but reference '${reference.seq.name}' was closed before verification started.")
                    return
                }
                // The drop target—not whichever tab happened to become active while
                // parsing—is the reference. Re-activate it before binding Sanger UI.
                hub.activate(reference)
                analysisPanel.showSangerVerification(reads.map { it.record }, reads.map { it.file })
                toolTabs.selectedIndex = toolTabs.indexOfTab("Analysis")
                statusBar.setMessage(
                    "Aligned ${reads.size} dropped read(s) to ${reference.seq.name}" +
                        if (batch.cancelled) " (drop loading was cancelled after available reads)." else ".",
                )
                if (batch.failures.isNotEmpty()) showOpenFailures(batch.failures)
            }
        }
        progressDialog?.dialog?.isVisible = true
        worker.execute()
    }

    /**
     * Parses every selected path in a background worker, applies successful
     * results on the EDT, and reports failures together after the batch. This
     * makes opening a folder-derived list reliable even when one input is bad.
     */
    fun openFiles(files: List<File>) {
        val selected = files
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        if (selected.isEmpty()) return

        val toLoad = selected.filter { file ->
            hub.documentFor(file)?.let { hub.activate(it) } == null
        }
        if (toLoad.isEmpty()) {
            lastFileOpenBatch = FileOpenBatch(emptyList(), emptyList())
            return
        }

        var worker: SwingWorker<FileOpenBatch, FileOpenProgress>? = null
        val progressDialog = createOpenProgressDialog(toLoad.size) { worker?.cancel(true) }
        worker = object : SwingWorker<FileOpenBatch, FileOpenProgress>() {
            @Volatile private var completedBatch: FileOpenBatch? = null

            override fun doInBackground(): FileOpenBatch {
                var completed = 0
                return FileOpenService.loadAll(
                    toLoad,
                    cancellationRequested = { isCancelled },
                    onResult = { opened ->
                        completed++
                        publish(FileOpenProgress(completed, toLoad.size, opened = opened))
                    },
                    onFailure = { failure ->
                        completed++
                        publish(FileOpenProgress(completed, toLoad.size, failure = failure))
                    },
                ).also { completedBatch = it }
            }

            override fun process(items: MutableList<FileOpenProgress>) {
                items.forEach { update ->
                    update.opened?.let(::applyOpenedFile)
                    progressDialog?.progress?.value = update.completed
                    statusBar.setMessage("Opening files: ${update.completed}/${update.total}")
                }
            }

            override fun done() {
                val batch = if (isCancelled) {
                    completedBatch ?: FileOpenBatch(emptyList(), emptyList(), cancelled = true)
                } else runCatching { get() }.getOrElse { error ->
                    FileOpenBatch(emptyList(), listOf(FileOpenFailure(toLoad.first(), error.message ?: "File opening failed.")))
                }
                lastFileOpenBatch = batch
                progressDialog?.dialog?.dispose()
                when {
                    batch.cancelled -> statusBar.setMessage("File opening cancelled after ${batch.completed}/${toLoad.size} item(s).")
                    batch.failures.isNotEmpty() -> {
                        statusBar.setMessage("Opened ${batch.opened.size} file(s); ${batch.failures.size} failed.")
                        showOpenFailures(batch.failures)
                    }
                    else -> statusBar.setMessage("Opened ${batch.opened.size} file(s).")
                }
            }
        }
        progressDialog?.dialog?.isVisible = true
        worker.execute()
    }

    /** Applies an already parsed result on the EDT. */
    private fun applyOpenedFile(opened: OpenedFile) {
        when (opened) {
            is OpenedFile.Sequence -> {
                if (hub.documentFor(opened.file) == null) {
                    noteRecent(openSequence(opened.sequence, opened.file), opened.file)
                }
            }
            is OpenedFile.Text -> {
                if (hub.documentFor(opened.file) == null) openText(opened.text, opened.file)
            }
            is OpenedFile.Project -> openProjectAt(opened.file)
            is OpenedFile.System -> Openers.SystemAppOpener.open(this, opened.file)
            is OpenedFile.Chromatogram -> {
                ensureSequenceWorkspace(opened.record.toSeq())
                analysisPanel.showChromatogram(opened.record, opened.file)
            }
            is OpenedFile.Alignment -> {
                ensureSequenceWorkspace(opened.sequences.first())
                analysisPanel.showAlignment(opened.sequences, opened.file)
            }
        }
    }

    /** Makes analysis results visible when opening a trace or alignment from an empty welcome screen. */
    private fun ensureSequenceWorkspace(sequence: Seq) {
        if (activeDoc !is SeqDocument) openSequence(sequence)
    }

    private fun createOpenProgressDialog(total: Int, onCancel: () -> Unit): OpenProgressDialog? {
        if (owner == null || GraphicsEnvironment.isHeadless()) return null
        val progress = JProgressBar(0, total).apply {
            isStringPainted = true
            string = "0 / $total"
        }
        progress.addChangeListener { progress.string = "${progress.value} / $total" }
        val cancel = JButton("Cancel").apply { addActionListener { onCancel() } }
        val dialog = JDialog(owner, "Opening files", false).apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(JLabel("Opening $total file${if (total == 1) "" else "s"}…"), BorderLayout.NORTH)
                add(progress, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(cancel) }, BorderLayout.SOUTH)
            }
            pack()
            setLocationRelativeTo(owner)
        }
        return OpenProgressDialog(dialog, progress)
    }

    private fun showOpenFailures(failures: List<FileOpenFailure>) {
        if (GraphicsEnvironment.isHeadless()) return
        val message = buildString {
            append("Some selected files could not be opened:\n\n")
            failures.take(12).forEach { failure -> append("• ${failure.file.name}: ${failure.message}\n") }
            if (failures.size > 12) append("… and ${failures.size - 12} more.\n")
        }
        JOptionPane.showMessageDialog(owner, message, "File open results", JOptionPane.WARNING_MESSAGE)
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
        projectRevisions.remove(doc)
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
        projectRevisions.clear()
        projectReloadPending = false
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
        projectRevisions.clear()
        projectReloadPending = false
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

    /** A stable EDT snapshot of one project-backed tab before a reload worker examines the filesystem. */
    private data class ProjectReloadCandidate(
        val document: Doc,
        val file: File,
        val previousRevision: ProjectFileRevision?,
        val dirty: Boolean,
    )

    /** Parsed replacement content applied only after a final dirty-state recheck on the EDT. */
    private sealed class ReloadedProjectDocument(val document: Doc, val file: File) {
        class Sequence(document: SeqDocument, val sequence: Seq, file: File) : ReloadedProjectDocument(document, file)
        class Text(document: TextDocument, val text: String, file: File) : ReloadedProjectDocument(document, file)
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
     * Explicitly compares the project against disk, including a manifest edited
     * by Git or a sync client. Clean tabs refresh; dirty buffers are retained
     * and reported as conflicts rather than being overwritten.
     */
    fun reloadProjectFromDisk() = scheduleProjectReload(includeManifest = true)

    /** Last conflict-tolerant reload outcome, exposed for headless regression tests and status integrations. */
    fun projectReloadStatus(): String = lastProjectReloadStatus

    /** Schedules one non-blocking comparison of open project files against their stored revisions. */
    private fun scheduleProjectReload(includeManifest: Boolean = false) {
        val p = project ?: return
        if (projectReloadPending) return
        projectReloadPending = true
        val candidates = hub.openDocuments.mapNotNull { document ->
            val file = document.file ?: return@mapNotNull null
            if (p.relativePath(file) == null) return@mapNotNull null
            ProjectReloadCandidate(document, file, projectRevisions[document] ?: ProjectReload.snapshot(file), document.isDirty)
        }
        val alreadyOpenPaths = hub.openDocuments.mapNotNull { it.file }.map { it.canonicalFile.path }.toSet()
        Thread {
            val decisions = candidates.associateWith { candidate ->
                ProjectReload.decide(candidate.previousRevision, ProjectReload.snapshot(candidate.file), candidate.dirty)
            }
            val replacements = decisions.filterValues { it.disposition == ProjectReloadDisposition.RELOAD_FROM_DISK }
                .keys.mapNotNull { candidate ->
                    runCatching {
                        when (val document = candidate.document) {
                            is SeqDocument -> ReloadedProjectDocument.Sequence(document, SeqIO.read(candidate.file), candidate.file)
                            is TextDocument -> ReloadedProjectDocument.Text(document, candidate.file.readText(), candidate.file)
                            else -> null
                        }
                    }.getOrNull()
                }
            val manifestEntries = if (includeManifest) SeqProject.open(p.root).manifest.openDocs else emptyList()
            val newlyDeclared = manifestEntries.mapNotNull { p.resolvePath(it)?.takeIf(File::isFile) }
                .filter { it.canonicalFile.path !in alreadyOpenPaths }
                .mapNotNull { file ->
                    runCatching {
                        when (FileTypes.classify(file)) {
                            FileType.SEQUENCE -> SeqIO.read(file).let { Opened.Sequence(it, file) }
                            FileType.TEXT -> file.readText().let { Opened.Text(it, file) }
                            else -> null
                        }
                    }.getOrNull()
                }
            SwingUtilities.invokeLater {
                if (project !== p) return@invokeLater
                projectReloadPending = false
                var refreshed = 0
                var preserved = 0
                var missing = 0
                for ((candidate, decision) in decisions) {
                    projectRevisions[candidate.document] = decision.currentRevision
                    when (decision.disposition) {
                        ProjectReloadDisposition.PRESERVE_LOCAL_CONFLICT,
                        ProjectReloadDisposition.PRESERVE_MISSING_LOCAL -> preserved++
                        ProjectReloadDisposition.MISSING_ON_DISK -> missing++
                        else -> Unit
                    }
                }
                for (replacement in replacements) {
                    if (!hub.contains(replacement.document)) continue
                    // A user edit after the background snapshot wins over disk.
                    if (replacement.document.isDirty) {
                        preserved++
                        continue
                    }
                    when (replacement) {
                        is ReloadedProjectDocument.Sequence -> (replacement.document as SeqDocument).reset(replacement.sequence, replacement.file)
                        is ReloadedProjectDocument.Text -> (replacement.document as TextDocument).reset(replacement.text, replacement.file)
                    }
                    projectRevisions[replacement.document] = ProjectReload.snapshot(replacement.file)
                    refreshed++
                }
                if (newlyDeclared.isNotEmpty()) {
                    loadingProject = true
                    try {
                        newlyDeclared.forEach { opened ->
                            if (hub.documentFor(opened.file) == null) {
                                when (opened) {
                                    is Opened.Sequence -> openSequence(opened.seq, opened.file)
                                    is Opened.Text -> openText(opened.text, opened.file)
                                }
                            }
                        }
                    } finally {
                        loadingProject = false
                    }
                }
                val parts = buildList {
                    if (refreshed > 0) add("reloaded $refreshed clean file${if (refreshed == 1) "" else "s"}")
                    if (newlyDeclared.isNotEmpty()) add("opened ${newlyDeclared.size} manifest file${if (newlyDeclared.size == 1) "" else "s"}")
                    if (preserved > 0) add("preserved $preserved local change${if (preserved == 1) "" else "s"}")
                    if (missing > 0) add("kept $missing missing-file tab${if (missing == 1) "" else "s"}")
                }
                lastProjectReloadStatus = if (parts.isEmpty()) "Project reload: no external changes." else "Project reload: ${parts.joinToString("; ")}."
                statusBar.setMessage(lastProjectReloadStatus)
                projectTreePanel.refresh()
                persistProject()
            }
        }.apply { isDaemon = true; name = "ProjectReload-${p.root.name}" }.start()
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
        projectRevisions.clear()
        projectReloadPending = false
        projectTreePanel.setProject(null)
        editRecorder.setProject(null, created = false)
        rebuildMenuBar()
        updateWorkingState()
    }

    // -------------------------------------------------------- ELN / LIMS handoff

    /** Returns the Markdown summary used by the generic ELN handoff for the active sequence. */
    fun activeElnSummary(): String? = (activeDoc as? SeqDocument)?.let { ElnCopy.sequenceSummaryMarkdown(it.seq) }

    /**
     * Writes a complete, vendor-neutral ELN/LIMS bundle for the active sequence.
     *
     * The method is deliberately synchronous so callers such as scripts and
     * headless tests can verify the resulting file. The desktop menu prepares
     * its request on the event thread and invokes the write on a worker.
     */
    fun exportActiveSequenceElnBundle(destination: File) =
        ElnAdapters.GENERIC_ZIP.export(
            destination,
            activeElnBundleRequest()
                ?: throw IllegalStateException("Open a sequence document before exporting an ELN bundle."),
        )

    /** Builds a local-first exchange request, adding the active plasmid-map SVG when it can be rendered. */
    private fun activeElnBundleRequest(): ElnBundleRequest? {
        val document = activeDoc as? SeqDocument ?: return null
        val sequence = document.seq
        val attachments = if (sequence.length == 0) emptyList() else listOf(renderElnMapAttachment(document))
        val provenance = buildMap {
            document.file?.let { source ->
                val relative = project?.relativePath(source)
                put(if (relative != null) "projectFile" else "sourceFile", relative ?: source.name)
            }
        }
        return ElnBundleRequest(
            title = sequence.name.ifBlank { "InstaGene sequence" },
            sequence = sequence,
            attachments = attachments,
            provenance = provenance,
        )
    }

    /** Renders a map only to a temporary file, then embeds its bytes in the portable bundle. */
    private fun renderElnMapAttachment(document: SeqDocument): ElnAttachment {
        val temporary = Files.createTempFile("instagene-eln-map-", ".svg")
        try {
            plasmidMapPanel.bindDocument(document)
            plasmidMapPanel.exportSvg(temporary.toFile())
            return ElnAttachment(
                path = "maps/${elnFileStem(document.seq.name)}-map.svg",
                bytes = Files.readAllBytes(temporary),
                mediaType = "image/svg+xml",
                role = ElnArtifactRole.MAP_SVG,
                description = "Plasmid or sequence map exported by InstaGene",
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyElnSummary() {
        val summary = activeElnSummary() ?: return
        runCatching { ContextMenus.copyToClipboard(summary) }
            .onSuccess { statusBar.setMessage("Copied ELN sequence summary.") }
            .onFailure { reportElnExportFailure("Copy ELN summary", it) }
    }

    private fun exportElnSummary() {
        val document = activeDoc as? SeqDocument ?: return
        val destination = chooseElnSaveFile("Export ELN Summary", "${elnFileStem(document.seq.name)}-summary.md", "md") ?: return
        writeElnFile("ELN summary", destination) { destination.writeText(ElnCopy.sequenceSummaryMarkdown(document.seq)) }
    }

    private fun exportElnPrimerCsv() {
        val document = activeDoc as? SeqDocument ?: return
        val destination = chooseElnSaveFile("Export ELN Primer CSV", "${elnFileStem(document.seq.name)}-primers.csv", "csv") ?: return
        writeElnFile("ELN primer CSV", destination) { destination.writeText(ElnCopy.primerCsv(document.seq)) }
    }

    private fun exportElnMapSvg() {
        val document = activeDoc as? SeqDocument ?: return
        if (document.seq.length == 0) {
            statusBar.setMessage("Add sequence bases before exporting a map.")
            return
        }
        val destination = chooseElnSaveFile("Export ELN Map SVG", "${elnFileStem(document.seq.name)}-map.svg", "svg") ?: return
        writeElnFile("ELN map SVG", destination) {
            plasmidMapPanel.bindDocument(document)
            plasmidMapPanel.exportSvg(destination)
        }
    }

    private fun exportElnSequenceAttachment() {
        val document = activeDoc as? SeqDocument ?: return
        val fasta = FileNameExtensionFilter("FASTA sequence", "fasta", "fa", "fna")
        val genBank = FileNameExtensionFilter("GenBank sequence", "gb", "gbk", "genbank")
        val chooser = JFileChooser().apply {
            dialogTitle = "Export ELN Sequence Attachment"
            addChoosableFileFilter(fasta)
            addChoosableFileFilter(genBank)
            fileFilter = genBank
            selectedFile = File("${elnFileStem(document.seq.name)}.gb")
        }
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return
        val format = if (chooser.fileFilter == fasta) SeqFormat.FASTA else SeqFormat.GENBANK
        val extension = if (format == SeqFormat.FASTA) "fasta" else "gb"
        val destination = ensureElnExtension(chooser.selectedFile, extension)
        if (!confirmElnOverwrite(destination)) return
        writeElnFile("ELN sequence attachment", destination) {
            destination.writeText(SeqIO.write(document.seq, format))
        }
    }

    /** Prepares the graphical map on the EDT, then writes the ZIP without freezing the desktop. */
    private fun exportGenericElnBundle() {
        val document = activeDoc as? SeqDocument ?: return
        val destination = chooseElnSaveFile("Export Generic ELN/LIMS Bundle", "${elnFileStem(document.seq.name)}-eln.zip", "zip") ?: return
        val request = runCatching { activeElnBundleRequest() }
            .getOrElse { error ->
                reportElnExportFailure("Prepare ELN bundle", error)
                return
            } ?: return
        statusBar.setMessage("Exporting generic ELN/LIMS bundle…")
        Thread {
            runCatching { ElnAdapters.GENERIC_ZIP.export(destination, request) }
                .onSuccess { manifest ->
                    SwingUtilities.invokeLater {
                        statusBar.setMessage("Exported generic ELN/LIMS bundle with ${manifest.artifacts.size} attachment(s).")
                    }
                }
                .onFailure { error -> SwingUtilities.invokeLater { reportElnExportFailure("Export ELN bundle", error) } }
        }.apply { isDaemon = true; name = "ElnBundleExport-${document.seq.name}" }.start()
    }

    private fun chooseElnSaveFile(title: String, suggestedName: String, extension: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileFilter = FileNameExtensionFilter("${extension.uppercase()} file", extension)
            selectedFile = File(suggestedName)
        }
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        val destination = ensureElnExtension(chooser.selectedFile, extension)
        return destination.takeIf(::confirmElnOverwrite)
    }

    private fun ensureElnExtension(file: File, extension: String): File =
        if (file.name.contains('.')) file else File(file.parentFile, "${file.name}.$extension")

    private fun confirmElnOverwrite(file: File): Boolean =
        !file.exists() || JOptionPane.showConfirmDialog(owner, "${file.name} already exists. Overwrite it?", "Overwrite export", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION

    private fun writeElnFile(label: String, destination: File, write: () -> Unit) {
        runCatching(write)
            .onSuccess { statusBar.setMessage("Exported $label: ${destination.name}") }
            .onFailure { reportElnExportFailure("Export $label", it) }
    }

    private fun reportElnExportFailure(action: String, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        statusBar.setMessage("$action failed: $message")
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(owner, "$action failed:\n$message", "ELN export", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun elnFileStem(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifBlank { "sequence" }

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
        project?.let { p ->
            doc.file?.takeIf { p.relativePath(it) != null }?.let { file ->
                projectRevisions[doc] = ProjectReload.snapshot(file)
            }
        }
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
        project?.let { p ->
            if (!doc.isDirty) {
                doc.file?.takeIf { p.relativePath(it) != null }?.let { file ->
                    projectRevisions[doc] = ProjectReload.snapshot(file)
                }
            }
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
            menuBar.add(set.tools.create().apply { isEnabled = sequence })
            menuBar.add(createCommandMenu())
            menuBar.add(set.tools.createActions().apply { isEnabled = sequence })
            menuBar.add(HelpMenu().create())
        }
        menuBar.revalidate()
        menuBar.repaint()
    }

    /**
     * The menu bar with no document open: the full set of top-level options
     * (File, Edit, View, Project, Tools, Command, Actions), with the sequence-only menus shown disabled.
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
            add(menuItem("Save As...", KeyEvent.VK_A, menuShortcutWithShift(KeyEvent.VK_S)) { activeFileMenu().saveFileAs() })
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
        menuBar.add(emptyStateMenuSet.tools.create().apply { isEnabled = false })
        menuBar.add(createCommandMenu())
        menuBar.add(JMenu("Actions").apply { isEnabled = false })
        menuBar.add(HelpMenu().create())
    }

    /** A compact keyboard-first route to documents, projects, tools, and workflows. */
    private fun createCommandMenu(): JMenu = JMenu("Command").apply {
        mnemonic = KeyEvent.VK_C
        add(menuItem("Command Palette...", KeyEvent.VK_P, commandPaletteShortcut()) { showCommandPalette() })
    }

    private fun commandPaletteShortcut(): KeyStroke {
        val shortcut = menuShortcut(KeyEvent.VK_P)
        return KeyStroke.getKeyStroke(KeyEvent.VK_P, shortcut.modifiers or InputEvent.SHIFT_DOWN_MASK)
    }

    private fun installCommandPaletteShortcut() {
        getInputMap(FocusedAncestorInputMap)
            .put(commandPaletteShortcut(), "show-command-palette")
        actionMap.put("show-command-palette", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = showCommandPalette()
        })
    }

    private fun showCommandPalette() = CommandPalette.show(this, commandPaletteCommands())

    /** Commands are rebuilt on opening so recents and project-specific actions stay current. */
    fun commandPaletteCommands(): List<CommandPaletteCommand> = buildList {
        add(CommandPaletteCommand("file.new", "New sequence", "Create an empty sequence document", listOf("file document")) { newDocument() })
        add(CommandPaletteCommand("file.new-text", "New text file", "Create a plain-text document", listOf("file document note")) { openText() })
        add(CommandPaletteCommand("file.open", "Open files…", "Open sequence, trace, alignment, text, or project files", listOf("file import")) { openFile() })
        prefs.value.recentFiles.map(::File).filter(File::exists).forEach { file ->
            add(CommandPaletteCommand("file.recent.${file.absolutePath}", "Open recent: ${file.name}", file.absolutePath, listOf("recent file")) {
                openFileInTab(file)
            })
        }
        add(CommandPaletteCommand("project.new", "New project…", "Create a project folder", listOf("project")) { newProject() })
        add(CommandPaletteCommand("project.open", "Open project…", "Open an existing InstaGene project", listOf("project")) { openProject() })
        if (project != null) {
            add(CommandPaletteCommand("project.close", "Close project", "Detach the current project", listOf("project")) { closeProject() })
            add(CommandPaletteCommand("project.reload", "Reload project from disk", "Refresh clean files and preserve unsaved local edits", listOf("project reload sync")) { reloadProjectFromDisk() })
            add(CommandPaletteCommand("project.search", "Search project…", "Search sequence files and annotations", listOf("project find")) { showProjectSearch(true) })
            add(CommandPaletteCommand("project.collections", "Project collections…", "Manage project collections", listOf("project")) { showProjectCollections() })
        }
        if (activeDoc is SeqDocument) {
            add(CommandPaletteCommand("eln.copy-summary", "Copy ELN sequence summary", "Copy a Markdown sequence and provenance summary", listOf("eln lims lab notebook copy")) { copyElnSummary() })
            add(CommandPaletteCommand("eln.export-bundle", "Export generic ELN/LIMS bundle…", "Write a local vendor-neutral ZIP with sequence, map, primers, and hashes", listOf("eln lims lab notebook export handoff")) { exportGenericElnBundle() })
        }
        prefs.value.recentProjects.map(::File).filter(File::exists).forEach { root ->
            add(CommandPaletteCommand("project.recent.${root.absolutePath}", "Open recent project: ${root.name}", root.absolutePath, listOf("recent project")) {
                openProjectAt(root)
            })
        }

        listOf("Info", "Map", "Sequence", "Features", "Primers", "Library", "Analysis", "History", "Enzyme").forEach { name ->
            add(CommandPaletteCommand("panel.${name.lowercase()}", "Show $name panel", "Open the $name workspace", listOf("panel tab")) {
                ensureSequenceWorkspace(Seq(""))
                toolTabs.selectedIndex = toolTabs.indexOfTab(name)
            })
        }
        analysisPanel.toolNames().forEach { name ->
            add(CommandPaletteCommand("analysis.${name.lowercase()}", "Open $name", "Analysis tool", listOf("analysis tool workflow")) {
                ensureSequenceWorkspace(Seq(""))
                analysisPanel.selectTool(name)
                toolTabs.selectedIndex = toolTabs.indexOfTab("Analysis")
            })
        }
        add(CommandPaletteCommand("workflow.pcr", "Start PCR / mutagenesis", "Open PCR product and cloning workflow", listOf("pcr cloning")) {
            ensureSequenceWorkspace(Seq(""))
            analysisPanel.selectTool("PCR / Mutagenesis")
            toolTabs.selectedIndex = toolTabs.indexOfTab("Analysis")
        })
        add(CommandPaletteCommand("workflow.assembly", "Start plasmid assembly", "Open assembly workflow", listOf("cloning plasmid gibson golden gate")) {
            ensureSequenceWorkspace(Seq(""))
            analysisPanel.selectTool("Assembly")
            toolTabs.selectedIndex = toolTabs.indexOfTab("Analysis")
        })
        add(CommandPaletteCommand("workflow.replay", "Replay workflow recipe…", "Reproduce an identity-matched local workflow", listOf("recipe reproducibility cloning replay")) {
            analysisPanel.showRecipeReplayDialog(owner)
        })
        add(CommandPaletteCommand("settings.preferences", "Preferences…", "Application preferences", listOf("settings theme")) {
            SettingsDialog.showPreferences(owner, prefs)
        })
    }

    private fun createProjectMenu(): JMenu = JMenu("Project").apply {
        mnemonic = KeyEvent.VK_P
        val hasProject = project != null
        add(menuItem("New Project...") { newProject() })
        add(menuItem("Open Project...", KeyEvent.VK_P, shiftShortcut(KeyEvent.VK_P)) { openProject() })
        add(menuItem("Close Project") { closeProject() }.apply { isEnabled = hasProject })
        add(menuItem("Reload Project from Disk") { reloadProjectFromDisk() }.apply { isEnabled = hasProject })
        addSeparator()
        add(JMenu("ELN / Lab Notebook").apply {
            isEnabled = activeDoc is SeqDocument
            add(menuItem("Copy ELN Summary") { copyElnSummary() })
            add(menuItem("Export ELN Summary...") { exportElnSummary() })
            add(menuItem("Export Sequence Attachment...") { exportElnSequenceAttachment() })
            add(menuItem("Export Primer CSV...") { exportElnPrimerCsv() })
            add(menuItem("Export Map SVG...") { exportElnMapSvg() })
            addSeparator()
            add(menuItem("Export Generic ELN/LIMS Bundle...") { exportGenericElnBundle() })
        })
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
        return menuShortcutWithShift(keyCode)
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
            addTab("Features", featuresPanel)
            addTab("Primers", primersPanel)
            addTab("Library", libraryPanel)
            addTab("Analysis", analysisPanel)
            addTab("History", editHistoryPanel)
            addTab("Enzyme", digestPanel)
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
