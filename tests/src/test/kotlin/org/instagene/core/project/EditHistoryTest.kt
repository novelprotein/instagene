package org.instagene.core.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine edit-history model: load/save round-trips and fallbacks when the
 * history file is missing or corrupt.
 */
class EditHistoryTest {

    private fun tempRoot(): File {
        val dir = File.createTempFile("instagene-history", "").let {
            it.delete()
            File(it.absolutePath).apply { mkdirs() }
        }
        return dir
    }

    @Test
    fun missingHistoryFileYieldsAnEmptyHistory() {
        val root = tempRoot()
        val project = SeqProject.open(root)
        assertTrue(project.loadHistory().entries.isEmpty())
        assertEquals(File(root, ".instagene/history.json"), project.historyFile())
        root.deleteRecursively()
    }

    @Test
    fun saveAndReloadRoundTripsEntries() {
        val root = tempRoot()
        val history = EditHistory(
            entries = listOf(
                EditEntry(timestamp = 1, kind = EditKind.PROJECT, label = "Project created", detail = root.name),
                EditEntry(timestamp = 2, kind = EditKind.EDIT, doc = "pMini.gb", label = "replace 4 bases", detail = "10 -> 6 bp"),
                EditEntry(timestamp = 3, kind = EditKind.UNDO, doc = "pMini.gb", label = "Undo make circular"),
                EditEntry(timestamp = 4, kind = EditKind.SAVE_AS, doc = "pMini.gb", label = "Saved as", detail = "pMini.gb"),
            ),
        )
        SeqProject.open(root).saveHistory(history)

        assertTrue(File(root, ".instagene/history.json").isFile)
        assertEquals(history, SeqProject.open(root).loadHistory())
        root.deleteRecursively()
    }

    @Test
    fun corruptHistoryFallsBackToAnEmptyHistory() {
        val root = tempRoot()
        val file = File(File(root, ".instagene"), SeqProject.HISTORY_NAME).apply {
            parentFile.mkdirs()
            writeText("this is not json")
        }
        assertTrue(file.isFile)
        assertTrue(SeqProject.open(root).loadHistory().entries.isEmpty())
        root.deleteRecursively()
    }
}
