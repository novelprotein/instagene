package org.instagene.app.gui

import org.instagene.core.Enzymes
import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeqDocumentTest {

    @Test
    fun selectionAndCaret() {
        val doc = SeqDocument(Seq(bases = "ACGTACGT"))
        assertEquals(0, doc.caret)
        assertFalse(doc.hasSelection)

        doc.select(2, 5)
        assertTrue(doc.hasSelection)
        assertEquals(2, doc.selectionStart)
        assertEquals(5, doc.selectionEnd)
        assertEquals("GTA", doc.selectedBases)

        doc.moveCaret(7)
        assertFalse(doc.hasSelection)
        assertEquals(7, doc.caret)

        doc.moveCaret(1, extendSelection = true)
        assertTrue(doc.hasSelection)

        doc.selectAll()
        assertEquals(doc.seq.length, doc.selectionEnd)

        doc.select(-10, 999)
        assertEquals(0, doc.selectionStart)
        assertEquals(8, doc.selectionEnd)
    }

    @Test
    fun mutateUndoRedoAndDirty() {
        val doc = SeqDocument(Seq(bases = "AAAA"))
        assertFalse(doc.isDirty)

        doc.mutate("insert") { it.insertAt(0, "TT") }
        assertEquals("TTAAAA", doc.seq.bases)
        assertTrue(doc.isDirty)

        doc.undo()
        assertEquals("AAAA", doc.seq.bases)

        doc.redo()
        assertEquals("TTAAAA", doc.seq.bases)

        doc.mutate("again") { it.insertAt(0, "G") }
        doc.undo()
        // redo stack cleared by new mutate after undo of "again"... wait:
        // after mutate again, redo was cleared. undo restores TTAAAA.
        assertEquals("TTAAAA", doc.seq.bases)
        doc.redo()
        assertEquals("GTTAAAA", doc.seq.bases)
    }

    @Test
    fun identityMutateIsNoOp() {
        val doc = SeqDocument(Seq(bases = "ACGT"))
        val reasons = mutableListOf<SeqDocument.Reason>()
        doc.addListener { _, reason -> reasons += reason }
        doc.mutate("noop") { it }
        assertFalse(doc.isDirty)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun dirtyTracksSavedStateAcrossUndoRedo() {
        val doc = SeqDocument(Seq(bases = "AAAA"))
        assertFalse(doc.isDirty)

        doc.mutate("insert") { it.insertAt(0, "TT") }
        assertTrue(doc.isDirty)

        // Undoing back to the saved state clears the dirty flag.
        doc.undo()
        assertFalse(doc.isDirty)
        doc.redo()
        assertTrue(doc.isDirty)

        // Marking saved after a change resets the baseline.
        doc.markSaved(File("/tmp/saved.fa"))
        assertFalse(doc.isDirty)
        doc.undo()
        assertTrue(doc.isDirty)
        doc.redo()
        assertFalse(doc.isDirty)
    }

    @Test
    fun resetClearsHistory() {
        val doc = SeqDocument(Seq(bases = "AAAA"))
        doc.mutate("x") { it.insertAt(0, "T") }
        doc.reset(Seq(bases = "GG"), dirty = false)
        assertEquals("GG", doc.seq.bases)
        assertFalse(doc.isDirty)
        doc.undo()
        assertEquals("GG", doc.seq.bases)
    }

    @Test
    fun markSavedAndFileNotify() {
        val doc = SeqDocument(Seq(bases = "ACGT"))
        doc.mutate("x") { it.insertAt(0, "T") }
        assertTrue(doc.isDirty)
        val f = File("/tmp/instagene-test.fa")
        doc.markSaved(f)
        assertFalse(doc.isDirty)
        assertEquals(f, doc.file)
    }

    @Test
    fun enzymesRefreshCutSites() {
        val doc = SeqDocument(SeqIO.Samples.PUC19_MCS)
        assertTrue(doc.cutSites.isEmpty())
        doc.addEnzyme(Enzymes.require("EcoRI"))
        assertEquals(1, doc.cutSites.size)
        doc.clearEnzymes()
        assertTrue(doc.cutSites.isEmpty())
    }

    @Test
    fun batchUpdateSuppressesNotifications() {
        val doc = SeqDocument(Seq(bases = "ACGT"))
        val reasons = mutableListOf<SeqDocument.Reason>()
        doc.addListener { _, reason -> reasons += reason }
        doc.beginBatchUpdate()
        doc.select(0, 2)
        doc.mutate("x") { it.insertAt(0, "T") }
        assertTrue(reasons.isEmpty())
        doc.endBatchUpdate(SeqDocument.Reason.SEQUENCE)
        assertEquals(listOf(SeqDocument.Reason.SEQUENCE), reasons)
    }

    @Test
    fun shorteningSequenceClampsSelection() {
        val doc = SeqDocument(Seq(bases = "ACGTACGT"))
        doc.select(4, 8)
        doc.mutate("trim") { it.deleteRange(2, 8) }
        assertTrue(doc.selectionStart <= doc.seq.length)
        assertTrue(doc.selectionEnd <= doc.seq.length)
    }

    @Test
    fun loadSequenceUsesBatch() {
        val doc = SeqDocument(Seq(bases = "AAAA"))
        val reasons = mutableListOf<SeqDocument.Reason>()
        doc.addListener { _, reason -> reasons += reason }
        doc.loadSequence(Seq(bases = "TTTT"), File("x.fa"))
        assertEquals("TTTT", doc.seq.bases)
        // One notification at end of batch
        assertEquals(1, reasons.size)
    }
}
