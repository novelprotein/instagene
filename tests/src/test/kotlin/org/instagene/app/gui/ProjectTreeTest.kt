package org.instagene.app.gui

import org.instagene.core.project.SeqProject
import java.io.File
import java.nio.file.Files
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The project file tree: it lists the project's files, hides the manifest
 * directory, reveals/opens files, and clears when the project is detached.
 */
class ProjectTreeTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        return result ?: fail("EDT block returned null")
    }

    private fun awaitEdt(timeoutMs: Long = 60_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait { }
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun projectTree(content: InstaGeneContent): ProjectTreePanel = content.projectTreePanel

    @Test
    fun projectTreeUsesOnlyTheBrowserScrollPane() {
        onEdt {
            val content = InstaGeneContent()
            var component: java.awt.Component? = content.projectTreePanel.tree
            var scrollPaneCount = 0
            while (component != null) {
                if (component is JScrollPane) scrollPaneCount++
                component = component.parent
            }
            assertEquals(1, scrollPaneCount, "the file browser must not nest scroll-pane borders")
        }
    }

    /** The [javax.swing.tree.TreePath] from the tree root down to [node]. */
    private fun pathTo(node: DefaultMutableTreeNode): javax.swing.tree.TreePath {
        val path = ArrayList<DefaultMutableTreeNode>()
        var n: DefaultMutableTreeNode? = node
        while (n != null) {
            path.add(0, n)
            n = n.parent as? DefaultMutableTreeNode
        }
        return javax.swing.tree.TreePath(path.toTypedArray())
    }

    @Test
    fun treeListsProjectFilesRecursivelyAndHidesManifest() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        File(root, "notes.txt").apply { writeText("hi") }
        val sub = File(root, "sub").apply { mkdirs() }
        val b = File(sub, "b.gb").apply { writeText(">b\nCCCC\n") }
        root.deleteOnExit()
        a.deleteOnExit()
        b.deleteOnExit()

        onEdt {
            val content = InstaGeneContent()
            assertTrue(projectTree(content).isEmpty())
            content.openProjectAt(root)
            val files = projectTree(content).files().map { it.name }
            assertEquals(
                listOf("b.gb", "a.fasta", "notes.txt"),
                files,
                "expects every project file (no .instagene), sub/ recursed, dirs before files",
            )
        }
    }

    @Test
    fun revealingAndOpeningANodeOpensTheFileInATab() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n" ) }
        SeqProject.create(root).apply { save() }
        root.deleteOnExit()
        a.deleteOnExit()

        val content = onEdt { InstaGeneContent() }
        content.openProjectAt(root)

        onEdt {
            val panel = projectTree(content)
            val node = panel.nodeFor(a)
            assertEquals(a, (node as DefaultMutableTreeNode).userObject)

            panel.reveal(a)
            assertEquals(
                a,
                (panel.tree.selectionPath!!.lastPathComponent as DefaultMutableTreeNode).userObject,
                "reveal did not select the file node",
            )

            panel.openSelected()
        }
        assertTrue(awaitEdt { content.activeDoc?.file == a }, "opening the tree node did not open a tab")
    }

    @Test
    fun treeFollowsOpenTabsWithoutDuplicating() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        SeqProject.create(root).apply { save() }
        root.deleteOnExit()
        a.deleteOnExit()

        val content = onEdt { InstaGeneContent() }
        content.openProjectAt(root)
        onEdt {
            projectTree(content).reveal(a)
            projectTree(content).openSelected()
        }
        assertTrue(awaitEdt { content.activeDoc?.file == a })

        val tabsBefore = content.docTabs.tabCount
        onEdt {
            projectTree(content).reveal(a)
            projectTree(content).openSelected()
        }
        assertEquals(tabsBefore, content.docTabs.tabCount, "re-opening from the tree must reuse the existing tab")
    }

    @Test
    fun treeKeepsExpandedFoldersAndSelectionWhenFilesOpen() {
        val root = Files.createTempDirectory("proj").toFile()
        val sub = File(root, "sub").apply { mkdirs() }
        val a = File(sub, "a.fasta").apply { writeText(">a\nAAAA\n") }
        SeqProject.create(root).apply { save() }
        root.deleteOnExit()
        sub.deleteOnExit()
        a.deleteOnExit()

        val content = onEdt { InstaGeneContent() }
        content.openProjectAt(root)
        assertTrue(awaitEdt { projectTree(content).nodeFor(a) != null }, "project tree did not list the file")

        // Expanding a folder, selecting a file, then opening it triggers a tree
        // refresh (a document was loaded); both must survive.
        onEdt {
            projectTree(content).reveal(a)
            projectTree(content).openSelected()
        }
        assertTrue(awaitEdt { content.activeDoc?.file == a }, "opening the tree node did not open a tab")

        onEdt {
            val panel = projectTree(content)
            val subNode = panel.nodeFor(sub)
            assertNotNull(subNode, "sub/ must still be listed")
            assertTrue(panel.tree.isExpanded(pathTo(subNode)), "expanded folders must stay expanded after loading a file")
            val selected = panel.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
            assertEquals(a, selected?.userObject, "tree selection must survive loading a file")
        }
    }

    @Test
    fun treeLabelsAreRelativeToTheProject() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        val sub = File(root, "sub").apply { mkdirs() }
        val b = File(sub, "b.gb").apply { writeText(">b\nCCCC\n") }
        SeqProject.create(root).apply { save() }
        root.deleteOnExit()
        a.deleteOnExit()
        b.deleteOnExit()

        onEdt {
            val content = InstaGeneContent()
            content.openProjectAt(root)
            val panel = content.projectTreePanel
            assertEquals("a.fasta", panel.labelFor(a), "root-level files show only their name")
            assertEquals("sub/b.gb", panel.labelFor(b), "nested files show their project-relative path")
            assertFalse(panel.labelFor(b).startsWith("/"), "labels must never be absolute paths")

            // The renderer actually paints those relative labels.
            val renderer = panel.tree.cellRenderer as DefaultTreeCellRenderer
            val node = panel.nodeFor(b)!!
            val cell = renderer.getTreeCellRendererComponent(panel.tree, node, false, false, false, 0, false)
            assertEquals("sub/b.gb", (cell as javax.swing.JLabel).text)
        }
    }
}
