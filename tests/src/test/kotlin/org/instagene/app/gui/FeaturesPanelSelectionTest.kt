package org.instagene.app.gui

import org.instagene.core.Feature
import org.instagene.core.Seq
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Features tab row selection must survive the reveal round trip: clicking
 * a row reveals it in the editor, which moves the document selection, which
 * must not clear the just-made table selection, or the Delete button can never
 * be enabled.
 */
class FeaturesPanelSelectionTest {

    private fun panelWithFeatures(): Pair<SeqDocument, FeaturesPanel> {
        val doc = SeqDocument(Seq(
            bases = "ACGTACGTACGT",
            features = listOf(
                Feature("f0", "misc_feature", 0, 2),
                Feature("f1", "misc_feature", 4, 6),
                Feature("f2", "misc_feature", 8, 10),
            ),
        ))
        return doc to FeaturesPanel(doc) { _, _ -> }
    }

    @Test
    fun selectedRowSurvivesRevealRoundTripAndEnablesDelete() {
        SwingUtilities.invokeAndWait {
            val (doc, panel) = panelWithFeatures()
            doc.select(2, 6)

            panel.selectFeatureRow(1)
            assertTrue(panel.isDeleteEnabled(), "delete must be enabled right after the click")

            // refresh() is what the document listener runs when the reveal
            // moves the editor selection: it must keep the row selected.
            panel.refresh()
            assertEquals(1, panel.selectedFeatureRow())
            assertTrue(panel.isDeleteEnabled())

            panel.revealFeature(panel.selectedFeatureRow())
            assertEquals(1, panel.selectedFeatureRow())
            assertTrue(panel.isDeleteEnabled())
        }
    }

    @Test
    fun deleteUsesSelectedRowAndAdvancesToNext() {
        SwingUtilities.invokeAndWait {
            val (doc, panel) = panelWithFeatures()
            panel.selectFeatureRow(1)

            panel.deleteFeature(panel.selectedFeatureRow())
            assertEquals(listOf("f0", "f2"), doc.seq.features.map { it.name })
            // The rebuild reselects the row now holding the next feature.
            assertEquals(1, panel.selectedFeatureRow())
            assertEquals("f2", doc.seq.features[panel.selectedFeatureRow()].name)
            assertTrue(panel.isDeleteEnabled())
        }
    }

    @Test
    fun deleteOfLastRowLeavesNothingSelected() {
        SwingUtilities.invokeAndWait {
            val (doc, panel) = panelWithFeatures()
            panel.refresh()
            panel.selectFeatureRow(2)
            panel.deleteFeature(panel.selectedFeatureRow())
            assertEquals(2, doc.seq.features.size)
            panel.deleteFeature(panel.selectedFeatureRow())
            assertEquals(1, doc.seq.features.size)
            panel.deleteFeature(panel.selectedFeatureRow())
            assertTrue(doc.seq.features.isEmpty())
            assertEquals(-1, panel.selectedFeatureRow())
            assertFalse(panel.isDeleteEnabled())
        }
    }
}