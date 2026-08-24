package org.instagene.app.gui

import org.instagene.app.gui.prefs.Prefs
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPaletteTest {

    @Test
    fun filterMatchesLabelsKeywordsAndCompactSubsequences() {
        val commands = listOf(
            CommandPaletteCommand("file.open", "Open files…", keywords = listOf("import")) {},
            CommandPaletteCommand("analysis.sanger", "Open Sanger Alignment", keywords = listOf("trace chromatogram")) {},
            CommandPaletteCommand("project.search", "Search project…", keywords = listOf("find")) {},
        )

        assertEquals(listOf("analysis.sanger"), CommandPalette.filter(commands, "trace").map { it.id })
        assertTrue("analysis.sanger" in CommandPalette.filter(commands, "osa").map { it.id })
        assertEquals(listOf("project.search"), CommandPalette.filter(commands, "project find").map { it.id })
    }

    @Test
    fun contentPublishesFileProjectToolAndWorkflowCommands() {
        var content: InstaGeneContent? = null
        SwingUtilities.invokeAndWait { content = InstaGeneContent(prefs = Prefs()) }

        val commands = content!!.commandPaletteCommands()
        assertTrue(commands.any { it.id == "file.open" })
        assertTrue(commands.any { it.id == "project.open" })
        assertTrue(commands.any { it.label == "Open Sanger Alignment" })
        assertTrue(commands.any { it.id == "workflow.assembly" })
        assertTrue(commands.any { it.id == "workflow.replay" })
    }
}
