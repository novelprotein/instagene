package org.instagene.app.gui

import org.instagene.app.gui.tool.WorkflowLibraryPanel
import org.instagene.core.*
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.*
import kotlin.test.*

class WorkflowLibraryPanelTest {
    @Test
    fun libraryEntryPointsAreAvailableWithoutDocuments() = onEdt {
        val content = InstaGeneContent()
        assertTrue(content.commandPaletteCommands().any { it.id == "workflow.library" })
        val projectMenu = (0 until content.menuBar.menuCount).map { content.menuBar.getMenu(it) }.single { it.text == "Project" }
        assertTrue(projectMenu.isEnabled)
        assertTrue(projectMenu.menuComponents.filterIsInstance<JMenuItem>().single { it.text == "Workflow Library…" }.isEnabled)
    }

    private fun onEdt(block: () -> Unit) {
        var error: Throwable? = null
        SwingUtilities.invokeAndWait { try { block() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }

    private fun components(root: Component): List<Component> = listOf(root) +
        if (root is Container) root.components.flatMap(::components) else emptyList()

    @Test
    fun corruptLibraryDisablesEditingAndReportsTheFailure() = onEdt {
        val file = Files.createTempDirectory("workflow-editor").resolve("library.json").toFile()
        file.writeText("damaged")
        val errors = mutableListOf<String>()
        val panel = WorkflowLibraryPanel(WorkflowLibraryStore(file), showError = { _, message -> errors += message })
        assertTrue(errors.single().contains("Could not load"))
        assertFalse(components(panel).filterIsInstance<JButton>().single { it.text == "New Protocol" }.isEnabled)
        panel.createEntry(WorkflowEntryKind.PROTOCOL)
        assertEquals("damaged", file.readText())
    }

    @Test
    fun createSaveDuplicateFilterAndDeleteWithoutASequence() = onEdt {
        val file = Files.createTempDirectory("workflow-editor").resolve("library.json").toFile()
        val errors = mutableListOf<String>()
        var answer = 2
        val panel = WorkflowLibraryPanel(WorkflowLibraryStore(file), { answer }, { true }, { _, message -> errors += message })
        fun button(label: String) = components(panel).filterIsInstance<JButton>().single { it.text == label }
        val title = components(panel).filterIsInstance<JTextField>().single { it.name == "workflowTitle" }
        val body = components(panel).filterIsInstance<JTextArea>().single { it.name == "workflowInstructions" }
        panel.createEntry(WorkflowEntryKind.PROCEDURE)
        assertFalse(panel.saveEntry())
        assertTrue(errors.single().contains("title"))
        title.text = "Routine"
        body.text = "Instructions\nαβ"
        assertFalse(panel.canLeave())
        answer = 0
        assertTrue(panel.canLeave())
        assertEquals("Instructions\nαβ", WorkflowLibraryStore(file).load().entries.single().instructions)
        button("Duplicate").doClick()
        answer = 2
        assertFalse(panel.canLeave())
        button("Save").doClick()
        val saved = WorkflowLibraryStore(file).load().entries
        assertEquals(2, saved.size)
        assertEquals(2, saved.map { it.id }.distinct().size)
        button("Delete").doClick()
        assertEquals(1, WorkflowLibraryStore(file).load().entries.size)
        panel.createEntry(WorkflowEntryKind.PROTOCOL)
        title.text = "Empty protocol draft"
        assertTrue(panel.saveEntry())
        val filter = components(panel).filterIsInstance<JComboBox<*>>().single()
        filter.selectedItem = "Procedure"
        val entries = components(panel).filterIsInstance<JList<*>>().single { it.model.size > 0 && it.model.getElementAt(0) is WorkflowLibraryEntry }
        assertEquals(1, entries.model.size)
        val search = components(panel).filterIsInstance<JTextField>().single { it.name == "workflowSearch" }
        search.text = "no match"
        assertEquals(0, entries.model.size)
        search.text = "αβ"
        assertEquals(1, entries.model.size)
    }

    @Test
    fun stepReorderingAndFailedSavePreserveTheDraft() = onEdt {
        val file = Files.createTempDirectory("workflow-editor").resolve("library.json").toFile()
        val store = WorkflowLibraryStore(file)
        store.load()
        store.save(WorkflowLibrary(entries = listOf(WorkflowLibraryEntry(kind = WorkflowEntryKind.PROTOCOL,
            title = "Protocol", steps = listOf(WorkflowLibraryStep("One", "A"), WorkflowLibraryStep("Two", "B"))))))
        val errors = mutableListOf<String>()
        var answer = 2
        val panel = WorkflowLibraryPanel(store, { answer }, { true }, { _, message -> errors += message })
        val lists = components(panel).filterIsInstance<JList<*>>()
        lists.single { it.model.size == 1 }.selectedIndex = 0
        val steps = lists.single { it.model.size == 2 }
        steps.selectedIndex = 1
        components(panel).filterIsInstance<JButton>().single { it.text == "Move up" }.doClick()
        assertTrue(panel.saveEntry())
        assertEquals(listOf("Two", "One"), WorkflowLibraryStore(file).load().entries.single().steps.map { it.title })
        val body = components(panel).filterIsInstance<JTextArea>().single { it.name == "workflowInstructions" }
        body.text = "Keep these notes"
        file.writeText("external edit")
        assertFalse(panel.saveEntry())
        assertEquals("Keep these notes", body.text)
        assertEquals("external edit", file.readText())
        assertTrue(errors.single().contains("changed on disk"))
        assertFalse(panel.canLeave())
        answer = 1
        assertTrue(panel.canLeave())
    }
}
