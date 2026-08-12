package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.project.ProjectLayout
import org.instagene.core.project.SeqProject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Multi-document editing: tabs open, switch, mark dirty and close, with the
 * shared tool panels rebinding to whichever document is active.
 */
class DocumentTabsTest {

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

    /** Pumps the EDT until [condition] holds or the timeout elapses. */
    private fun awaitEdt(timeoutMs: Long = 60_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (SwingUtilities.isEventDispatchThread()) {
                if (condition()) return true
                Thread.sleep(10)
            } else {
                SwingUtilities.invokeAndWait { }
                if (condition()) return true
                Thread.sleep(10)
            }
        }
        return condition()
    }

    @Test
    fun tabsOpenSwitchMarkDirtyAndClose() {
        onEdt {
            val content = InstaGeneContent()
            val first = content.activeDocument
            assertEquals(1, content.docTabs.tabCount)
            assertEquals("Untitled", content.tabLabelText(first))

            // Opening a sequence adds a tab and makes it active.
            val second = content.openSequence(Seq(bases = "ACGT", name = "tab2"))
            assertEquals(2, content.docTabs.tabCount)
            assertSame(second, content.activeDocument)
            assertEquals("tab2", content.tabLabelText(second))

            // The shared panels follow the active document.
            assertEquals("4 bp", content.infoPanel.lengthLabel.text)

            // Switching tabs re-binds the panels to the other document.
            content.docTabs.selectedIndex = 0
            assertSame(first, content.activeDocument)
            assertEquals("0 bp", content.infoPanel.lengthLabel.text)
            content.docTabs.selectedIndex = 1
            assertSame(second, content.activeDocument)
            assertEquals("4 bp", content.infoPanel.lengthLabel.text)

            // Unsaved edits mark the tab title.
            second.mutate("edit") { it.insertAt(0, "G") }
            assertEquals("tab2 *", content.tabLabelText(second))

            // Closing removes the tab; the remaining one stays active.
            assertTrue(content.closeTab(second, force = true))
            assertEquals(1, content.docTabs.tabCount)
            assertSame(first, content.activeDocument)
        }
    }

    @Test
    fun closingTheLastTabExitsTheProgram() {
        onEdt {
            val content = InstaGeneContent()
            // The window starts in the welcome state with nothing open; opening
            // a fresh document moves it to the working view.
            assertEquals(0, content.docTabs.tabCount, "welcome state must start with no tabs")
            val doc = content.newDocument()
            assertEquals(1, content.docTabs.tabCount)
            assertTrue(content.closeTab(doc, force = true))
            assertEquals(0, content.docTabs.tabCount, "closing the last tab must not open a fresh document")
            assertTrue(content.exitPending, "closing the last tab must request the window to close")
        }
    }

    @Test
    fun tabCloseButtonClosesTheTab() {
        onEdt {
            val content = InstaGeneContent()
            val first = content.activeDocument
            content.openSequence(Seq(bases = "ACGT", name = "tab2"))
            assertEquals(2, content.docTabs.tabCount)

            val close = (content.docTabs.getTabComponentAt(1) as JPanel).getComponent(1) as JButton
            close.doClick()

            assertEquals(1, content.docTabs.tabCount, "close button did not remove the tab")
            assertSame(first, content.activeDocument)
        }
    }

    @Test
    fun documentTabsDoNotReserveSpaceBeforeToolTabs() {
        onEdt {
            val content = InstaGeneContent()
            content.newDocument()
            content.setSize(1000, 700)
            content.doLayout()
            content.docTabs.doLayout()

            val tabBounds = content.docTabs.ui.getTabBounds(content.docTabs, 0)
            assertTrue(
                content.docTabs.height <= tabBounds.y + tabBounds.height + 1,
                "document tabs must only occupy their tab strip, not an empty content margin",
            )
        }
    }

    @Test
    fun openingTheSameFileReusesItsTab() {
        val file = Files.createTempFile("seq", ".fasta").toFile()
        file.writeText(">seq\nACGTACGT\n")
        file.deleteOnExit()

        val content = onEdt { InstaGeneContent() }
        onEdt { content.openFileInTab(file) }
        assertTrue(awaitEdt { content.activeDoc?.file == file }, "file was never opened")

        val tab = content.activeDoc
        onEdt { content.openFileInTab(file) }
        assertEquals(1, content.docTabs.tabCount, "re-opening an open file must reuse its tab, not create another")
        assertSame(tab, content.activeDoc)
    }

    @Test
    fun openingAProjectRestoresItsDocumentsActiveTabAndLayout() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        val b = File(root, "b.gb").apply { writeText(">b\nCCCC\n") }
        root.deleteOnExit()
        a.deleteOnExit()
        b.deleteOnExit()

        SeqProject.create(root).apply {
            addDocument(a)
            addDocument(b)
            setActive(b)
            setLayout(ProjectLayout(activeToolTab = 3, treeSplitRatio = 0.4))
            save()
        }

        val content = onEdt { InstaGeneContent() }
        content.openProjectAt(root)
        assertTrue(awaitEdt { content.docTabs.tabCount == 2 }, "project documents were not loaded")

        val active = content.activeDocument
        assertEquals("b.gb", active.file?.name)
        assertEquals(3, content.toolTabs.selectedIndex, "project layout tab was not restored")
    }

    @Test
    fun projectManifestPersistsTabSetAndDirtyStateSurvivesInTabs() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        root.deleteOnExit()
        a.deleteOnExit()

        SeqProject.create(root).apply {
            addDocument(a)
            setActive(a)
            save()
        }

        val content = onEdt { InstaGeneContent() }
        content.openProjectAt(root)
        assertTrue(awaitEdt { content.activeDoc?.file == a })

        // Editing marks the active tab dirty and the title keeps the marker
        // across a save-less tab switch.
        val doc = content.activeDocument
        onEdt { doc.mutate("edit") { it.insertAt(0, "G") } }
        assertTrue(content.tabLabelText(doc).contains("*"))
        assertSame(doc, content.activeDocument)
        assertTrue(doc.isDirty)

        // Reopening the project restores the same document from disk; unsaved
        // edits are never silently persisted.
        val reopened = onEdt { InstaGeneContent() }
        reopened.openProjectAt(root)
        assertTrue(awaitEdt { reopened.activeDoc?.file == a })
        assertEquals("AAAA", reopened.activeDocument.seq.bases)
        assertFalse(reopened.activeDocument.isDirty, "unsaved edits must not be silently persisted")
    }

    @Test
    fun welcomeStateShowsOpenOptionsAndLeavesWhenADocumentOpens() {
        onEdt {
            val content = InstaGeneContent()
            assertEquals(0, content.docTabs.tabCount, "no file to open means the welcome state")
            assertNull(content.activeDoc)

            // The empty-state menu bar still shows the full set of top-level
            // options; the sequence-only ones are merely disabled.
            val menus = (0 until content.menuBar.menuCount).map { content.menuBar.getMenu(it)!!.text }
            assertEquals(listOf("File", "Edit", "View", "Tools"), menus, "all top-level menus must be present")
            val edit = content.menuBar.getMenu(1)!!
            val tools = content.menuBar.getMenu(3)!!
            assertFalse(edit.isEnabled, "Edit must be disabled with no document open")
            assertFalse(tools.isEnabled, "Tools must be disabled with no document open")

            // "New Document" moves the window into the working view.
            content.welcomePanel.newDocumentButton.doClick()
            assertEquals(1, content.docTabs.tabCount)
            assertNotNull(content.activeDoc, "New Document must open a fresh document")
        }
    }

    @Test
    fun fileBrowserToggleMinimizesAndRestoresTheTree() {
        onEdt {
            val content = InstaGeneContent()
            // No project is open, so the browser starts minimized: it collapses
            // to a button-wide strip with no margins or divider, and the split
            // never widens on resize (resizeWeight 0), so no blank pane appears.
            assertFalse(content.fileBrowserVisible)
            assertFalse(content.fileBrowserTreeVisible)
            assertSame(content.fileBrowserPanel, content.projectSplit.leftComponent)
            assertEquals(0, content.projectSplit.dividerSize, "a minimized browser must hide the divider")
            assertEquals(0.0, content.projectSplit.resizeWeight, "a minimized browser must not take resize space")
            assertEquals(
                content.fileBrowserHeader.preferredSize.width,
                content.projectSplit.dividerLocation,
                "a minimized browser must collapse to the toggle width",
            )
            assertEquals(0, (content.fileBrowserHeader.layout as FlowLayout).hgap, "no horizontal margin when minimized")
            assertTrue(content.fileBrowserToggle.isShowing || content.fileBrowserToggle.isVisible, "the restore toggle must stay")

            content.fileBrowserToggle.doClick()
            assertTrue(content.fileBrowserVisible)
            assertTrue(content.fileBrowserTreeVisible)
            assertSame(content.fileBrowserHeader, content.fileBrowserToggle.parent)
            assertTrue(content.fileBrowserToggle.isShowing || content.fileBrowserToggle.isVisible)
            assertTrue(content.projectSplit.dividerSize > 0, "expanding must bring the divider back")
            assertEquals(0.15, content.projectSplit.resizeWeight)
            assertEquals(180, content.projectSplit.dividerLocation, "expanding must restore the saved tree width")
            assertEquals(4, (content.fileBrowserHeader.layout as FlowLayout).hgap)

            content.fileBrowserToggle.doClick()
            assertFalse(content.fileBrowserVisible)
            assertFalse(content.fileBrowserTreeVisible)
            assertEquals(0, content.projectSplit.dividerSize, "minimizing must hide the divider again")
            assertEquals(0.0, content.projectSplit.resizeWeight, "minimizing must drop the resize weight again")
            assertEquals(0, (content.fileBrowserHeader.layout as FlowLayout).hgap, "margins must stay gone")
        }
    }

    @Test
    fun viewMenuBrowserToggleTracksTheActualBrowserState() {
        onEdt {
            val content = InstaGeneContent()
            val browserItem = showFileBrowserItem(content)

            // Empty state: the browser starts minimized and the View toggle agrees.
            assertFalse(content.fileBrowserVisible)
            assertFalse(browserItem.isSelected)

            // Minimizing via the header toggle flips the View toggle too.
            content.fileBrowserToggle.doClick()
            assertTrue(content.fileBrowserVisible)
            assertTrue(browserItem.isSelected)

            // Unchecking the View toggle minimizes the browser again.
            browserItem.doClick()
            assertFalse(content.fileBrowserVisible)
            assertFalse(content.fileBrowserToggle.isSelected)
        }
    }

    @Test
    fun viewMenuBrowserToggleTracksBrowserStateWithADocumentOpen() {
        onEdt {
            val content = InstaGeneContent()
            content.newDocument()
            val browserItem = showFileBrowserItem(content)

            assertFalse(content.fileBrowserVisible)
            assertFalse(browserItem.isSelected)

            content.fileBrowserToggle.doClick()
            assertTrue(browserItem.isSelected)

            browserItem.doClick()
            assertFalse(content.fileBrowserVisible)
            assertFalse(content.fileBrowserToggle.isSelected)
        }
    }

    private fun showFileBrowserItem(content: InstaGeneContent): JCheckBoxMenuItem {
        val viewMenu = content.menuBar.getMenu(2) ?: fail("View menu missing")
        return viewMenu.menuComponents.filterIsInstance<JCheckBoxMenuItem>()
            .first { it.text == "Show File Browser" }
    }

    @Test
    fun browserToggleLivesInItsHeaderAndTheToolbarIsGone() {
        onEdt {
            val content = InstaGeneContent()

            // The File Browser toggle moved into the toggleable header section
            // at the top of the file browser side panel.
            assertEquals(
                listOf(content.fileBrowserToggle),
                content.fileBrowserHeader.components.toList(),
                "the header must hold exactly the File Browser toggle",
            )
            assertSame(content.fileBrowserHeader, content.fileBrowserToggle.parent)
            assertSame(content.fileBrowserPanel, content.fileBrowserHeader.parent)

            // The top toolbar is gone: the working view's top strip is the tab
            // strip itself, with no New/Open buttons of its own.
            val working = content.docTabs.parent
            val layout = working?.layout as? BorderLayout
            assertEquals(content.docTabs, layout?.getLayoutComponent(BorderLayout.NORTH), "tabs must be the working view's top")
            assertFalse(
                working.components.any { it is javax.swing.AbstractButton },
                "the working view must have no toolbar buttons",
            )
            assertFalse("New" in collectButtonTexts(content), "the New button must be removed")
        }
    }

    /** Every button label found in [container], recursively. */
    private fun collectButtonTexts(container: java.awt.Container): List<String> {
        val texts = ArrayList<String>()
        for (c in container.components) {
            if (c is javax.swing.AbstractButton) texts += c.text
            if (c is java.awt.Container) texts += collectButtonTexts(c)
        }
        return texts
    }

    @Test
    fun viewMenuTogglesTheFileBrowser() {
        onEdt {
            val content = InstaGeneContent()
            val doc = content.newDocument()
            val menu = ViewMenu(
                doc,
                content.sequenceView,
                Prefs(),
                isFileBrowserVisible = { content.fileBrowserVisible },
                onFileBrowserVisible = { content.setFileBrowserVisible(it) },
            ).create()
            val item = (0 until menu.menuComponentCount)
                .map { menu.getMenuComponent(it) }
                .filterIsInstance<JCheckBoxMenuItem>()
                .firstOrNull { it.text == "Show File Browser" }
            assertNotNull(item, "View menu must contain a Show File Browser item")

            // Selecting the item via the menu fires its action listener with the
            // checkbox's new state; drive the listener directly rather than
            // relying on doClick()'s press/release selection semantics.
            item.isSelected = false
            val listener = item.actionListeners.single()
            listener.actionPerformed(java.awt.event.ActionEvent(item, java.awt.event.ActionEvent.ACTION_PERFORMED, ""))
            assertFalse(content.fileBrowserVisible, "deselecting the View menu item must hide the browser")
        }
    }
}
