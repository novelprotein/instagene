package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.*
import org.instagene.core.*
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*
import org.junit.jupiter.api.Test

class SequenceWorkflowTest {
    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    @Test fun objectNavigationPreservesBaseSelectionAndReturnsToSource() = edt {
        val feature = Feature("gene", "CDS", 4, 28)
        val doc = SeqDocument(Seq(bases = "ACGT".repeat(30), features = listOf(feature)))
        val controller = SequenceInteraction(doc)
        doc.select(40, 44)
        var tab = "Features"
        var revealed: Pair<Int, Int>? = null
        controller.onNavigate = { tab = it }
        controller.onReveal = { s, e -> revealed = s to e }
        controller.select(SequenceObject.Annotation(feature))
        assertEquals("Features", tab)
        controller.show(SequenceObject.Annotation(feature))
        assertEquals("Sequence", tab)
        assertEquals(4 to 28, revealed)
        assertEquals(40 to 44, doc.selectionStart to doc.selectionEnd)
        controller.back()
        assertEquals("Features", tab)
        controller.selectBases()
        assertEquals(4 to 28, doc.selectionStart to doc.selectionEnd)
        controller.dispose()
    }

    @Test fun selectedObjectsAreScopedToDocumentsAndRemovedWithAnnotations() = edt {
        val feature = Feature("gene", "CDS", 0, 8)
        val first = SeqDocument(Seq(bases = "ACGTACGT", features = listOf(feature)))
        val second = SeqDocument(Seq(bases = "AAAAAAAA"))
        val controller = SequenceInteraction(first)
        controller.select(SequenceObject.Annotation(feature))
        controller.bindDocument(second)
        assertNull(controller.selected)
        controller.bindDocument(first)
        assertEquals(SequenceObject.Annotation(feature), controller.selected)
        first.mutate("remove") { it.withoutFeature(feature) }
        assertNull(controller.selected)
        controller.dispose()
    }

    @Test fun primerDesignIsExplicitPreviewIsTransientAndApplyIsUndoable() = edt {
        val doc = SeqDocument(Seq(bases = "ACGT".repeat(30)))
        val panel = PrimersPanel(doc)
        var preview: List<PrimerAnnotation> = emptyList()
        panel.onPreview = { primers, _ -> preview = primers }
        assertNull(panel.lastPrimers())
        doc.select(8, 48)
        assertNull(panel.lastPrimers())
        panel.setTarget(8, 48)
        assertTrue(panel.design())
        val pair = panel.lastPrimers()
        panel.preview()
        assertEquals(2, preview.size)
        assertEquals(Strand.REVERSE, preview[1].strand)
        assertFalse(doc.isDirty)
        doc.select(60, 80)
        assertEquals(pair, panel.lastPrimers())
        assertEquals("9" to "48", panel.rangeFields())
        assertTrue(panel.addPrimersToFeatures())
        assertEquals(2, doc.seq.primers.size)
        assertEquals(Strand.REVERSE, doc.seq.features.last().strand)
        assertFalse(panel.addPrimersToFeatures())
        assertTrue(preview.isEmpty())
        assertTrue(doc.undo())
        assertTrue(doc.seq.primers.isEmpty())
        assertTrue(doc.seq.features.isEmpty())
        panel.dispose()
    }

    @Test fun primerResultsSurviveMetadataEditsAndDocumentSwitchesButNotBaseEdits() = edt {
        val first = SeqDocument(Seq(bases = "ACGT".repeat(30)))
        val second = SeqDocument(Seq(bases = "TGCA".repeat(30)))
        val panel = PrimersPanel(first)
        panel.designAmplicon(4, 60)
        val pair = panel.lastPrimers()
        first.mutate("annotate") { it.withFeature(Feature("gene", "gene", 0, 4)) }
        assertEquals(pair, panel.lastPrimers())
        panel.bindDocument(second)
        assertNull(panel.lastPrimers())
        panel.bindDocument(first)
        assertEquals(pair, panel.lastPrimers())
        assertEquals("5" to "60", panel.rangeFields())
        first.mutate("insert") { it.insertAt(0, "T") }
        assertNull(panel.lastPrimers())
        assertFalse(panel.areResultActionsEnabled())
        panel.dispose()
    }

    @Test fun graphicsModesKeepCoordinatesAndLimitPaintToViewport() = edt {
        val feature = Feature("Long reverse feature", "CDS", 4, 80, Strand.REVERSE)
        val doc = SeqDocument(Seq(bases = "ACGT".repeat(25_000), features = listOf(feature)))
        val view = SequenceView(doc)
        try {
            view.setSize(900, 500)
            val image = BufferedImage(900, 500, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.clipRect(0, 0, 900, 500)
                view.paint(g)
                assertTrue(view.lastViewportPaintedRowCount() < 20)
                assertNotNull(view.featureHitCenterForTest(feature.name))
                val x = view.xCoordinate(4)
                assertEquals(4, view.indexAt(x, 10))
                view.graphicsMode = SequenceGraphics.SIMPLIFIED
                view.paint(g)
                assertEquals(4, view.indexAt(view.xCoordinate(4), 10))
                assertEquals(doc.seq.features.single(), feature)
            } finally { g.dispose() }
        } finally { view.dispose() }
    }
}
