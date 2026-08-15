package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.edit.EditHistoryPanel
import org.instagene.app.gui.edit.EditRecorder
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.app.gui.project.ProjectTreePanel
import org.instagene.app.gui.tool.AnalysisPanel
import org.instagene.app.gui.tool.DigestPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.app.gui.tool.LibraryPanel
import org.instagene.app.gui.tool.PrimersPanel
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.project.SeqProject
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ContextMenuTest {

    @Test
    fun toolTablesExposeContextMenusWithTooltips() = onEdt {
        val prefs = Prefs().apply {
            update { it.copy(digestCuttersOnly = false) }
        }
        val doc = SeqDocument(
            Seq(
                name = "menu-test",
                bases = "GAATTCGGATCCGAATTCACGTACGTACGTACGT",
                features = listOf(Feature("geneA", "gene", 1, 7)),
            )
        )
        val digest = DigestPanel(doc, { _: Seq -> }, { _, _ -> }, prefs)
        val panels = listOf(
            LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }.apply {
                addItem(SavedItem(SavedKind.FRAGMENT, "insert", "ACGT"))
            },
            FeaturesPanel(doc, prefs) { _, _ -> },
            PrimersPanel(doc, prefs),
            digest,
            AnalysisPanel(doc, {}, { _, _ -> }),
            EditHistoryPanel(EditRecorder().apply {
                val root = Files.createTempDirectory("history-menu").toFile()
                SeqProject.create(root).save()
                setProject(SeqProject.open(root), created = false)
            }),
        )

        val tables = panels.flatMap { descendants(it, JTable::class.java) }
        assertTrue(tables.isNotEmpty(), "expected GUI tables to inspect")
        tables.forEach { table ->
            popupClick(table, if (table.rowCount > 0) 0 else -1)
            val popup = table.componentPopupMenu
            assertNotNull(popup, "table '${table.columnName(0)}' should expose a context menu")
            assertMenuTooltips(popup)
        }
        digest.dispose()
    }

    @Test
    fun tableRightClickSelectsTheClickedRowBeforeShowingMenu() = onEdt {
        val prefs = Prefs()
        val doc = SeqDocument(Seq(bases = "ACGTACGT"))
        val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
        panel.addItem(SavedItem(SavedKind.FRAGMENT, "first", "AAAA"))
        panel.addItem(SavedItem(SavedKind.FRAGMENT, "second", "CCCC"))
        val table = panel.libraryTable

        table.clearSelection()
        popupClick(table, 1)

        assertEquals(1, table.selectedRow)
        assertMenuTooltips(table.componentPopupMenu)
        assertTrue(
            table.componentPopupMenu.components.filterIsInstance<JMenuItem>().any { it.text == "Open as sequence" },
            "library context menu should include row actions",
        )
    }

    @Test
    fun projectTreeContextMenuItemsHaveTooltips() = onEdt {
        val root = Files.createTempDirectory("project-menu").toFile()
        val file = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        SeqProject.create(root).save()
        val panel = ProjectTreePanel({}, {}, {}, { emptyList() })
        panel.setProject(SeqProject.open(root))
        panel.reveal(file)

        popupClick(panel.tree)

        assertMenuTooltips(panel.tree.componentPopupMenu)
    }

    private fun JTable.columnName(column: Int): String =
        if (column in 0 until columnCount) getColumnName(column) else javaClass.simpleName

    private fun popupClick(table: JTable, row: Int) {
        table.setSize(600, (table.rowCount.coerceAtLeast(1) + 1) * table.rowHeight)
        val y = if (row >= 0) row * table.rowHeight + table.rowHeight / 2 else table.rowHeight / 2
        val event = MouseEvent(
            table,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            0,
            1,
            y,
            1,
            true,
            MouseEvent.BUTTON3,
        )
        table.mouseListeners
            .filter {
                it.javaClass.name.contains("ContextMenus") ||
                    it.javaClass.name.contains("NcbiAnalysisPanel")
            }
            .forEach { it.mouseReleased(event) }
    }

    private fun popupClick(tree: JTree) {
        tree.setSize(600, 400)
        tree.doLayout()
        val row = tree.selectionRows?.firstOrNull() ?: 0
        val bounds = tree.getRowBounds(row)
        val event = MouseEvent(
            tree,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            0,
            bounds.x + 2,
            bounds.y + bounds.height / 2,
            1,
            true,
            MouseEvent.BUTTON3,
        )
        tree.dispatchEvent(event)
    }

    private fun assertMenuTooltips(popup: JPopupMenu?) {
        assertNotNull(popup, "context menu should exist")
        val items = popup.components.filterIsInstance<JMenuItem>()
        assertTrue(items.isNotEmpty(), "context menu should contain at least one item")
        items.forEach { item ->
            assertTrue(
                item.toolTipText?.isNotBlank() == true,
                "menu item '${item.text}' should have a tooltip",
            )
        }
    }

    private fun <T : Component> descendants(root: Component, type: Class<T>): List<T> {
        val found = ArrayList<T>()
        fun visit(component: Component) {
            if (type.isInstance(component)) found += type.cast(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return found
    }

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
}
