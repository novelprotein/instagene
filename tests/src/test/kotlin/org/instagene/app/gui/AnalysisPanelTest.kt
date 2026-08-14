package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.AnalysisPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.core.Seq
import org.instagene.core.Strand
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisPanelTest {
    @Test
    fun analysisWorkspaceExposesEveryAddedWorkflow() = onEdt {
        val panel = AnalysisPanel(SeqDocument(Seq("sample", "GAATTCATGGCCTAAGCTT")), {}, { _, _ -> })
        assertEquals(
            listOf("Search", "Alignment", "Enzymes", "Assembly", "Virtual Gel", "Calculators", "NCBI / BLAST", "Chromatogram"),
            panel.toolNames(),
        )
        panel.selectTool("Virtual Gel")
        assertEquals("Virtual Gel", panel.selectedTool())
    }

    @Test
    fun analysisWorkspaceRebindsWithoutReconstruction() = onEdt {
        val first = SeqDocument(Seq("first", "ACGT"))
        val panel = AnalysisPanel(first, {}, { _, _ -> })
        val second = SeqDocument(Seq("second", "TGCA"))
        panel.bindDocument(second)
        panel.selectTool("Search")
        assertTrue(panel.selectedTool() == "Search")
    }

    @Test
    fun featureEditorPersistsDisplayMetadata() = onEdt {
        val document = SeqDocument(Seq("annotated", "ACGTACGT", features = listOf(org.instagene.core.Feature("old", start = 0, end = 4))))
        val features = FeaturesPanel(document) { _, _ -> }
        assertEquals(null, features.updateFeatureElement(0, "promoter", "promoter", 1, 4, Strand.FORWARD, "note", "#123456", false, 7))
        val updated = document.seq.features.single()
        assertEquals("#123456", updated.color)
        assertEquals(false, updated.visible)
        assertEquals(7, updated.displayOrder)
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try { result = block() } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
