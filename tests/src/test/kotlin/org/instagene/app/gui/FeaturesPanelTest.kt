package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.Strand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeaturesPanelTest {

    private fun panelWithSeq(): Pair<SeqDocument, FeaturesPanel> {
        val doc = SeqDocument(Seq(bases = "ACGTACGTACGT"))
        return doc to FeaturesPanel(doc) { _, _ -> }
    }

    @Test
    fun addButtonDisabledWithoutSelection() {
        val (doc, panel) = panelWithSeq()
        doc.moveCaret(3)
        assertFalse(panel.isAddEnabled())
    }

    @Test
    fun addButtonEnabledAfterDragSelection() {
        val (doc, panel) = panelWithSeq()
        doc.moveCaret(2)
        doc.moveCaret(6, extendSelection = true)
        assertTrue(panel.isAddEnabled())
        assertEquals(2, doc.selectionStart)
        assertEquals(6, doc.selectionEnd)
    }

    @Test
    fun addButtonDisabledAgainAfterCollapse() {
        val (doc, panel) = panelWithSeq()
        doc.moveCaret(2)
        doc.moveCaret(6, extendSelection = true)
        assertTrue(panel.isAddEnabled())
        doc.moveCaret(6)
        assertFalse(panel.isAddEnabled())
    }

    @Test
    fun addFeatureCreatesUndoableFeature() {
        val (doc, panel) = panelWithSeq()
        doc.moveCaret(2)
        doc.moveCaret(6, extendSelection = true)
        panel.addFeature("probe", "primer_bind")
        val feature = doc.seq.features.single()
        assertEquals("probe", feature.name)
        assertEquals(2, feature.start)
        assertEquals(6, feature.end)
        assertEquals("primer_bind", feature.type)
        doc.undo()
        assertTrue(doc.seq.features.isEmpty())
    }

    // ------------------------------------------------------------ manual add

    @Test
    fun manualAddButtonEnabledWithoutSelection() {
        val (doc, panel) = panelWithSeq()
        doc.moveCaret(3)
        assertFalse(panel.isAddEnabled())
        assertTrue(panel.isManualAddEnabled())
    }

    @Test
    fun manualAddButtonDisabledOnEmptySequence() {
        val doc = SeqDocument(Seq(bases = ""))
        val panel = FeaturesPanel(doc) { _, _ -> }
        assertFalse(panel.isManualAddEnabled())
    }

    @Test
    fun addFeatureManuallyCreatesUndoableFeature() {
        val (doc, panel) = panelWithSeq()
        assertTrue(panel.addFeatureManually("prom", "promoter", 1, 4))
        val feature = doc.seq.features.single()
        assertEquals("prom", feature.name)
        assertEquals("promoter", feature.type)
        assertEquals(0, feature.start)
        assertEquals(4, feature.end)
        doc.undo()
        assertTrue(doc.seq.features.isEmpty())
    }

    @Test
    fun addFeatureManuallyHonorsStrandAndNotes() {
        val (doc, panel) = panelWithSeq()
        assertTrue(panel.addFeatureManually("rev", "CDS", 5, 9, Strand.REVERSE, "beta-lactamase"))
        val feature = doc.seq.features.single()
        assertEquals(Strand.REVERSE, feature.strand)
        assertEquals(4, feature.start)
        assertEquals(9, feature.end)
        assertEquals("beta-lactamase", feature.notes)
    }

    @Test
    fun addFeatureManuallyRejectsInvalidCoordinates() {
        val (doc, panel) = panelWithSeq()
        assertFalse(panel.addFeatureManually("zero", "misc_feature", 0, 4))
        assertFalse(panel.addFeatureManually("inverted", "misc_feature", 5, 4))
        assertFalse(panel.addFeatureManually("pastEnd", "misc_feature", 1, doc.seq.length + 1))
        assertTrue(doc.seq.features.isEmpty())
    }
}
