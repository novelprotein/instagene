package org.instagene.app.gui.project

import org.instagene.core.project.SeqProject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

import org.instagene.app.gui.ContextMenus

/**
 * The left-hand file tree of a project: every file under the project root
 * (with `.instagene/` hidden), labelled by its path relative to the project.
 * Double-clicking opens a file, and right-clicking provides "open in folder"
 * and "open with system app" actions. Files already open in a tab are bold.
 * With no project attached, the tree shows a hint instead.
 */
class ProjectTreePanel(
    private val onOpenFile: (File) -> Unit,
    private val onOpenInFolder: (File) -> Unit,
    private val onOpenWithSystem: (File) -> Unit,
    private val openFiles: () -> List<File>,
    private val onExternalProjectChange: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private var project: SeqProject? = null
    private val root = DefaultMutableTreeNode("No project open")
    private val model = DefaultTreeModel(root)
    private val fileWatcher = ProjectFileWatcher {
        refresh()
        onExternalProjectChange()
    }

    /** The underlying tree; public so callers (and tests) can inspect selection and the model. */
    val tree = JTree(model)

    init {
        tree.isRootVisible = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = OpenFileRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) openSelected()
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
        })
        // InstaGeneContent owns the single scroll pane around this panel.  A
        // second one here creates a double border in some FlatLaf themes.
        add(tree, BorderLayout.CENTER)
        refresh()
    }

    /** Attaches the tree to [p] (or detaches it when null) and rebuilds it. */
    fun setProject(p: SeqProject?) {
        stopWatching()
        project = p
        refresh()
    }

    /** Starts watching the project root for file-system changes. */
    fun startWatching() {
        val rootDir = project?.root ?: return
        fileWatcher.start(rootDir)
    }

    /** Stops watching for file-system changes. */
    fun stopWatching() {
        fileWatcher.stop()
    }

    /**
     * Refreshes the project folder and open-file set from the current state,
     * keeping the expanded folders and selection so loading files does not
     * collapse the tree back to its root.
     */
    fun refresh() {
        val p = project
        val expanded = expandedFiles()
        val selected = selectedProjectFile()
        root.removeAllChildren()
        if (p == null) {
            root.userObject = "No project open"
        } else {
            root.userObject = p.root.name
            addChildren(root, p.root)
        }
        model.reload()
        if (root.childCount > 0) {
            tree.expandPath(treePath(root))
            expanded.forEach { file ->
                nodeFor(file)?.let { tree.expandPath(treePath(it)) }
            }
            selected?.let { file ->
                nodeFor(file)?.let { node ->
                    tree.expandPath(treePath(node))
                    tree.selectionPath = treePath(node)
                }
            }
        }
    }

    /** Files (directories and leaves) whose tree node is currently expanded. */
    private fun expandedFiles(): Set<File> {
        val expanded = HashSet<File>()
        fun visit(node: DefaultMutableTreeNode) {
            val file = node.userObject as? File
            if (file != null && tree.isExpanded(treePath(node))) expanded += file
            node.children().toList().forEach { child ->
                if (child is DefaultMutableTreeNode) visit(child)
            }
        }
        visit(root)
        return expanded
    }

    /** The file of the currently selected tree node, or null. */
    fun selectedProjectFile(): File? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        return node?.userObject as? File
    }

    /** The tree node for [file], for tests and callers that want to pre-select a file. */
    fun nodeFor(file: File): DefaultMutableTreeNode? {
        if (project == null) return null
        val target = file.canonicalFile
        return depthFirst(root).firstOrNull { (it.userObject as? File)?.canonicalFile == target }
    }

    /** Selects the node of [file], expanding the path as needed. */
    fun reveal(file: File) {
        val node = nodeFor(file) ?: return
        val path = treePath(node)
        tree.expandPath(path)
        tree.selectionModel.selectionPath = path
    }

    /** Opens the file of the currently selected tree node (what a double-click does). */
    fun openSelected() {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val file = node.userObject as? File ?: return
        if (file.isDirectory) {
            tree.expandPath(tree.selectionPath)
        } else {
            onOpenFile(file)
        }
    }

    private fun treePath(node: DefaultMutableTreeNode): TreePath {
        val path = ArrayList<DefaultMutableTreeNode>()
        var n: DefaultMutableTreeNode? = node
        while (n != null) {
            path.add(0, n)
            n = n.parent as? DefaultMutableTreeNode
        }
        return TreePath(path.toTypedArray())
    }

    /** All file nodes (no directories) currently listed in the tree, depth-first. */
    fun files(): List<File> =
        depthFirst(root).mapNotNull { it.userObject as? File }.filter { it.isFile }.toList()

    /** True when the tree has no project attached. */
    fun isEmpty(): Boolean = project == null

    /** Current project root for content-aware search, or null outside project mode. */
    fun projectRoot(): File? = project?.root

    /**
     * How [file] appears in the tree: its path relative to the project root
     * (`a.fasta`, `sub/b.gb`), never an absolute path. Root-level files show
     * just their name; the project folder itself labels the root node.
     */
    fun labelFor(file: File): String {
        val p = project ?: return file.name
        return runCatching { p.root.toPath().relativize(file.toPath()).toString() }
            .getOrElse { file.name }
    }

    private fun addChildren(parent: DefaultMutableTreeNode, dir: File) {
        val entries = (dir.listFiles() ?: emptyArray())
            .filterNot { it.name == SeqProject.MANIFEST_DIR }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        for (file in entries) {
            val node = DefaultMutableTreeNode(file)
            parent.add(node)
            // Do not follow directory links: they can escape the project or
            // form a cycle and make a recursive refresh never terminate.
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) addChildren(node, file)
        }
    }

    private fun depthFirst(node: DefaultMutableTreeNode): Sequence<DefaultMutableTreeNode> =
        sequence {
            yield(node)
            node.children().toList().forEach { child ->
                if (child is DefaultMutableTreeNode) yieldAll(depthFirst(child))
            }
        }

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val node = tree.getClosestPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val file = node.userObject as? File ?: return
        tree.selectionPath = treePath(node)
        val popup = JPopupMenu()
        if (!file.isDirectory) {
            popup.add(ContextMenus.item(
                "Open",
                "Open this project file in InstaGene.",
            ) { onOpenFile(file) })
            popup.add(ContextMenus.item(
                "Open with System App",
                "Open this file with the operating system's default application.",
            ) { onOpenWithSystem(file) })
        }
        popup.add(ContextMenus.item(
            "Open in Folder",
            "Reveal this item in its containing folder.",
        ) { onOpenInFolder(if (file.isDirectory) file else file.parentFile!!) })
        tree.componentPopupMenu = popup
        if (tree.isShowing) popup.show(tree, e.x, e.y)
    }

    /** Relative labels for project files; bold type for files already open in a tab. */
    private inner class OpenFileRenderer : DefaultTreeCellRenderer() {

        private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            val c = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode
            val file = node?.userObject as? File
            if (file != null) {
                text = labelFor(file)
                if (openFiles().any { it.canonicalFile == file.canonicalFile }) {
                    c.font = c.font.deriveFont(Font.BOLD)
                }
                // Tooltip: always present so truncated names are recoverable on hover.
                if (file.isFile) {
                    val sizeKb = file.length() / 1024.0
                    val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024) else "%.1f KB".format(sizeKb)
                    val lastMod = Files.getLastModifiedTime(file.toPath())
                    val timeStr = runCatching {
                        timeFmt.format(Instant.ofEpochMilli(lastMod.toMillis()).atZone(ZoneId.systemDefault()))
                    }.getOrDefault("unknown")
                    toolTipText = "<html><b>${file.name}</b><br>$sizeStr · Modified $timeStr</html>"
                } else {
                    toolTipText = "<html><b>${file.name}</b></html>"
                }
            } else {
                // Root node (userObject is the project name string): add tooltip
                // so the full project name is visible when truncated by a narrow column.
                val name = node?.userObject as? String
                if (name != null && name != "No project open") {
                    val rootDir = project?.root
                    val path = rootDir?.absolutePath ?: ""
                    toolTipText = "<html><b>$name</b><br><font size=2>$path</font></html>"
                }
            }
            return c
        }
    }
}
