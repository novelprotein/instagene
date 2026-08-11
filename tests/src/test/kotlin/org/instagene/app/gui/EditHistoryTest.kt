package org.instagene.app.gui

import org.instagene.core.project.EditKind
import org.instagene.core.project.SeqProject
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Edit History feature end to end: the recorder coalesces text typing runs,
 * sequence edits/undo/redo/saves are logged, everything persists write-through
 * to `.instagene/history.json`, and the History tab renders it newest first.
 */
class EditHistoryTest {

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
    fun textTypingRunsCoalesceIntoOneEntryAndPersist() {
        val root = Files.createTempDirectory("hist-proj").toFile()
        root.deleteOnExit()
        var now = 1_000L
        val recorder = EditRecorder(now = { now })
        recorder.setProject(SeqProject.open(root), created = false)

        val text = TextDocument()
        recorder.bind(text)
        text.setText("a")
        text.setText("ab")
        text.setText("abc")

        // A typing run merges into a single entry whose detail tracks the length.
        assertEquals(1, recorder.entries.count { it.kind == EditKind.EDIT })
        assertEquals("3 chars", recorder.entries.last { it.kind == EditKind.EDIT }.detail)

        // Once the coalesce window has passed, the next keystroke opens a new entry.
        now = 10_000L
        text.setText("abcd")
        assertEquals(2, recorder.entries.count { it.kind == EditKind.EDIT })

        recorder.flush()
        assertEquals(recorder.entries, SeqProject.open(root).loadHistory().entries)
    }

    @Test
    fun sequenceEditsUndoRedoAndSavesAreLoggedAndTheTabShowsThem() {
        val root = Files.createTempDirectory("hist-proj").toFile()
        val seq = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        root.deleteOnExit()
        seq.deleteOnExit()
        SeqProject.create(root).apply {
            addDocument(seq)
            setActive(seq)
            save()
        }

        val content = onEdt { InstaGeneContent() }
        content.openProjectAt(root)
        assertTrue(awaitEdt { content.activeDoc?.file == seq }, "project document was not loaded")

        onEdt {
            val doc = content.activeDocument
            doc.mutate("replace 2 bases") { it.replaceRange(0, 2, "") }
            doc.undo()
            doc.redo()
            doc.markSaved(seq)

            val entries = content.editRecorder.entries
            val kinds = entries.map { it.kind }
            assertTrue(kinds.contains(EditKind.PROJECT), "project open must be logged")
            assertTrue(kinds.contains(EditKind.EDIT), "edits must be logged")
            assertTrue(kinds.contains(EditKind.UNDO), "undo must be logged")
            assertTrue(kinds.contains(EditKind.REDO), "redo must be logged")
            assertTrue(kinds.contains(EditKind.SAVE), "save must be logged")

            // Write-through: what is in memory is what is on disk.
            assertEquals(entries, SeqProject.open(root).loadHistory().entries)

            // The tab renders the same log, newest first, with a document column
            // and the change summary (label + detail) joined into the Change cell.
            val model = content.editHistoryPanel.table.model
            assertEquals(entries.size, model.rowCount)
            assertEquals("Saved — a.fasta", model.getValueAt(0, 2))
            assertEquals("Redo replace 2 bases — 4 -> 2 bp", model.getValueAt(1, 2))
            assertEquals("Undo replace 2 bases — 2 -> 4 bp", model.getValueAt(2, 2))
            assertEquals("replace 2 bases — 4 -> 2 bp", model.getValueAt(3, 2))
            assertEquals("a.fasta", model.getValueAt(3, 1))
            assertEquals("Project opened — ${root.name}", model.getValueAt(4, 2))

            // Closing the tab logs it.
            content.closeTab(doc, force = true)
            assertEquals(EditKind.CLOSE, content.editRecorder.entries.last().kind)
        }
        root.deleteRecursively()
    }
}
