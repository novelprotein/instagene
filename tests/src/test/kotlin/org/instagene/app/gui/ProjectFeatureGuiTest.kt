package org.instagene.app.gui

import org.instagene.app.gui.dialog.SettingsDialog
import org.instagene.app.gui.project.BatchOperation
import org.instagene.app.gui.project.BatchOperationPanel
import org.instagene.app.gui.project.ProjectCollectionsPanel
import org.instagene.core.ExternalTools
import org.instagene.core.Seq
import org.instagene.core.io.SequenceFormatCatalog
import org.instagene.core.io.SeqIO
import org.instagene.core.project.CollectionStore
import org.instagene.core.project.SeqProject
import java.awt.Component
import java.awt.Container
import java.io.File
import java.nio.file.Files
import javax.swing.JMenu
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ProjectFeatureGuiTest {
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

    private fun <T : Component> descendants(root: Component, type: Class<T>): List<T> {
        val found = ArrayList<T>()
        fun visit(component: Component) {
            if (type.isInstance(component)) found += type.cast(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return found
    }

    @Test
    fun projectMenuStaysAvailableWhenAProjectHasNoOpenDocuments() {
        val root = Files.createTempDirectory("instagene-empty-project").toFile()
        SeqProject.create(root).save()
        val content = onEdt { InstaGeneContent() }

        onEdt { content.openProjectAt(root) }

        val menus = onEdt { (0 until content.menuBar.menuCount).map { content.menuBar.getMenu(it)!!.text } }
        assertEquals(listOf("Command", "File", "Edit", "View", "Project", "Actions", "Tools", "Help"), menus)
        val projectMenu = onEdt { content.menuBar.getMenu(4)!! }
        assertTrue(projectMenu.isEnabled, "Project menu must be enabled for project-only features without document tabs.")
        assertFalse(onEdt { content.menuBar.getMenu(5)!!.isEnabled }, "Sequence Actions stay disabled without a sequence tab.")
        assertEquals(
            listOf("New Project...", "Open Project...", "Close Project", "Reload Project from Disk", "ELN / Lab Notebook", "Search Project...", "Collections...", "Batch Convert...", "Batch Annotate...", "Batch Update Properties...", "Recent Projects"),
            menuItemTexts(projectMenu),
        )
        assertFalse(
            onEdt { projectMenu.menuComponents.filterIsInstance<JMenu>().single { it.text == "ELN / Lab Notebook" }.isEnabled },
            "ELN actions require an active sequence document.",
        )
    }

    @Test
    fun projectCollectionsAndBatchPanelsAreBackedByTheCurrentProject() {
        val root = Files.createTempDirectory("instagene-project-gui").toFile()
        val sequenceFile = File(root, "plasmid.gb")
        SeqIO.write(sequenceFile, Seq("plasmid", "ACGTACGT"))
        val project = SeqProject.create(root).also { it.save() }

        val collections = onEdt { ProjectCollectionsPanel(project, sequenceFile) }
        var addError: String? = null
        onEdt {
            addError = collections.addProjectFile("Main", "Plasmids", sequenceFile)
        }
        assertEquals(null, addError)
        onEdt { collections.saveCollections() }
        assertEquals(1, onEdt { collections.rowCount() })
        assertEquals(
            "plasmid.gb",
            CollectionStore(project).load().collections.single().areas.first { it.name == "Plasmids" }.files.single(),
        )

        val output = Files.createTempDirectory("instagene-batch-output").toFile()
        val batch = onEdt {
            BatchOperationPanel(project, BatchOperation.CONVERT, listOf(sequenceFile)).apply {
                outputField.text = output.absolutePath
            }
        }
        assertEquals(listOf(sequenceFile.canonicalFile), onEdt { batch.selectedFiles().map { it.canonicalFile } })
        val result = onEdt { batch.runBatch() }
        assertEquals(1, result.processed)
        assertTrue(output.listFiles()?.singleOrNull()?.isFile == true)
    }

    @Test
    fun settingsExposeExternalToolsAndConverterBackedFormats() {
        val panel = onEdt { SettingsDialog.externalToolsPanel() }
        val tabs = descendants(panel, JTabbedPane::class.java).single()
        assertEquals(listOf("Tools", "Converters"), (0 until tabs.tabCount).map { tabs.getTitleAt(it) })

        val tables = descendants(panel, JTable::class.java)
        val toolTable = tables.firstOrNull { it.rowCount >= ExternalTools.CATALOG.size }
            ?: fail("External tool catalog must be visible.")
        assertEquals(listOf("Tool", "Status", "Version / path", "Action"), (0 until toolTable.columnCount).map(toolTable::getColumnName))
        assertTrue(
            tables.any { table ->
                (0 until table.rowCount).any { row ->
                    (0 until table.columnCount).any { col ->
                        table.getValueAt(row, col).toString().startsWith("INSTAGENE_CONVERTER_")
                    }
                }
            },
            "Converter-backed import formats must show their environment variable names.",
        )
        assertTrue(SequenceFormatCatalog.FORMATS.any { it.displayName == "Vector NTI" })
    }

    private fun menuItemTexts(menu: JMenu): List<String> =
        (0 until menu.itemCount).mapNotNull { menu.getItem(it)?.text }
}
