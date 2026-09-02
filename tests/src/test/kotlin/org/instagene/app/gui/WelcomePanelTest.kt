package org.instagene.app.gui

import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.project.SeqProject
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The welcome panel lists recent files and projects, skips missing entries,
 * and opens an entry when it is clicked.
 */
class WelcomePanelTest {

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

    @Test
    fun recentFilesAndProjectsAreListedAndMissingEntriesAreSkipped() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        val b = File(root, "b.fasta").apply { writeText(">b\nCCCC\n") }
        val gone = File(root, "gone.fasta")
        root.deleteOnExit()
        a.deleteOnExit()
        b.deleteOnExit()

        val prefs = Prefs()
        prefs.update {
            it.copy(
                recentFiles = listOf(b.absolutePath, gone.absolutePath, a.absolutePath),
                recentProjects = listOf(root.absolutePath),
            )
        }

        onEdt {
            val content = InstaGeneContent(prefs = prefs)
            assertEquals(0, content.docTabs.tabCount, "recent entries must not auto-open")
            assertEquals(listOf(b, a), content.welcomePanel.recentFiles, "missing entries must be skipped")
            assertEquals(listOf(root), content.welcomePanel.recentProjects)
            assertEquals(listOf(b.name, a.name), content.welcomePanel.recentFileButtons().map { it.text })
            assertEquals(listOf(root.name), content.welcomePanel.recentProjectButtons().map { it.text })
        }
    }

    @Test
    fun clickingARecentFileOpensItAndARecentProjectOpensItsDocuments() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        val b = File(root, "b.fasta").apply { writeText(">b\nCCCC\n") }
        SeqProject.create(root).apply {
            addDocument(a)
            setActive(a)
            save()
        }
        root.deleteOnExit()
        a.deleteOnExit()
        b.deleteOnExit()

        val prefs = Prefs()
        prefs.update {
            it.copy(recentFiles = listOf(b.absolutePath), recentProjects = listOf(root.absolutePath))
        }

        val content = onEdt { InstaGeneContent(prefs = prefs) }
        onEdt { content.welcomePanel.recentFileButtons().single().doClick() }
        assertTrue(awaitEdt { content.activeDoc?.file == b }, "clicking a recent file must open it")

        val projectContent = onEdt { InstaGeneContent(prefs = prefs) }
        onEdt { projectContent.welcomePanel.recentProjectButtons().single().doClick() }
        assertTrue(awaitEdt { projectContent.activeDoc?.file == a }, "clicking a recent project must open its documents")
    }

    @Test
    fun openingAProjectRecordsItAsRecent() {
        val root = Files.createTempDirectory("proj").toFile()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        SeqProject.create(root).apply {
            addDocument(a)
            setActive(a)
            save()
        }
        root.deleteOnExit()
        a.deleteOnExit()

        val prefs = Prefs()
        val content = onEdt { InstaGeneContent(prefs = prefs) }
        content.openProjectAt(root)
        assertTrue(awaitEdt { content.activeDoc?.file == a }, "opening the project did not load its file")
        assertEquals(listOf(root.absolutePath), prefs.value.recentProjects, "opening a project must record it as recent")
    }

    @Test
    fun bundledExamplesAreVisibleAndInvokeTheirTypedCallbacks() {
        val opened = mutableListOf<WelcomeExample>()
        val panel = onEdt {
            WelcomePanel(
                Prefs(),
                onOpenFile = {},
                onOpenProject = {},
                onNewDocument = {},
                onOpenExample = { opened += it },
            )
        }

        assertEquals(WelcomeExample.entries.map { it.label }, panel.exampleButtons().map { it.text })
        assertTrue(panel.exampleButtons().all { it.toolTipText.contains("complete source record") })
        listOf("J01749.1", "M77789.2", "U62636.1", "L29345.1").forEach { accession ->
            assertTrue(panel.exampleButtons().any { it.toolTipText.contains(accession) })
        }
        onEdt { panel.exampleButtons().forEach { it.doClick() } }
        assertEquals(WelcomeExample.entries.toList(), opened)
    }

}
