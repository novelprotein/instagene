package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.project.ProjectLayout
import org.instagene.core.project.SeqProject
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            assertEquals(1, content.docTabs.tabCount)
            assertTrue(content.closeTab(content.activeDocument, force = true))
            assertEquals(0, content.docTabs.tabCount, "closing the last tab must not open a fresh document")
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
    fun openingTheSameFileReusesItsTab() {
        val file = Files.createTempFile("seq", ".fasta").toFile()
        file.writeText(">seq\nACGTACGT\n")
        file.deleteOnExit()

        val content = onEdt { InstaGeneContent() }
        onEdt { content.openFileInTab(file) }
        assertTrue(awaitEdt { content.activeDocument.file == file }, "file was never opened")

        val tab = content.activeDocument
        onEdt { content.openFileInTab(file) }
        assertEquals(2, content.docTabs.tabCount, "duplicate tab created for an open file")
        assertSame(tab, content.activeDocument)
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
        assertTrue(awaitEdt { content.docTabs.tabCount == 3 }, "project documents were not loaded")

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
        assertTrue(awaitEdt { content.activeDocument.file == a })

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
        assertTrue(awaitEdt { reopened.activeDocument.file == a })
        assertEquals("AAAA", reopened.activeDocument.seq.bases)
        assertFalse(reopened.activeDocument.isDirty, "unsaved edits must not be silently persisted")
    }
}
