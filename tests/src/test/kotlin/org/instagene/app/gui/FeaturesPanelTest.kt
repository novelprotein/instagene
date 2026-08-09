package org.instagene.app.gui

import org.instagene.core.Seq
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
}
