package org.instagene.app.gui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain-text/notes document model: edits, dirty tracking, save and undo. */
class TextDocumentTest {

    @Test
    fun editsMarkDirtyAndNotify() {
        val doc = TextDocument("hello")
        var changes = 0
        doc.addDocListener { changes++ }
        assertFalse(doc.isDirty)
        doc.setText("hello world")
        assertTrue(doc.isDirty)
        assertTrue(changes > 0)
        assertEquals("hello world", doc.text)
    }

    @Test
    fun saveClearsDirtyAndMovesBaseline() {
        val tmp = File.createTempFile("instagene-text", ".txt").apply { deleteOnExit() }
        val doc = TextDocument("hello", tmp)
        doc.setText("hello world")
        assertTrue(doc.isDirty)
        doc.markSaved(tmp)
        assertFalse(doc.isDirty, "saving must clear the dirty flag")
        assertEquals(tmp, doc.file)
    }

    @Test
    fun undoRedoAreLabelledAndRoundTrip() {
        val doc = TextDocument("start")
        doc.setText("middle", label = "insert")
        doc.setText("end", label = "replace")
        assertEquals("replace", doc.undoLabel())
        assertEquals("end", doc.text)
        doc.undo()
        assertEquals("middle", doc.text)
        assertEquals("insert", doc.undoLabel())
        assertEquals("replace", doc.redoLabel())
        doc.undo()
        assertEquals("start", doc.text)
        assertFalse(doc.canUndo())
        assertNull(doc.undoLabel())
        doc.redo()
        assertEquals("middle", doc.text)
        doc.redo()
        assertEquals("end", doc.text)
    }

    @Test
    fun sameTextEditIsIgnored() {
        val doc = TextDocument("same")
        var changes = 0
        doc.addDocListener { changes++ }
        doc.setText("same")
        assertEquals(0, changes)
        assertFalse(doc.canUndo())
    }

    @Test
    fun displayNameFallsBackToUntitled() {
        assertEquals("Untitled", TextDocument().displayName)
        assertEquals("notes.txt", TextDocument(file = File("notes.txt")).displayName)
    }
}
