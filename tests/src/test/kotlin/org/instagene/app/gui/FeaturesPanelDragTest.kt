package org.instagene.app.gui

import org.instagene.core.Seq
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeaturesPanelDragTest {

    @Test
    fun dragInEditorEnablesAddFeatureButton() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val doc = content.doc
            doc.loadSequence(Seq(bases = "ACGTACGTACGTACGTACGT"))
            val view = content.sequenceView
            view.setSize(900, 400)
            view.doLayout()
            // Coordinate lookup calculates basesPerLine from the actual font if needed.
            val firstX = view.xCoordinate(0)
            val fifthX = view.xCoordinate(5)

            view.dispatchEvent(
                MouseEvent(view, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, firstX, 10, 1, false)
            )
            for (step in 1..4) {
                val x = firstX + (fifthX - firstX) * step / 4
                view.dispatchEvent(
                    MouseEvent(view, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, x, 10, 0, false)
                )
            }
            view.dispatchEvent(
                MouseEvent(view, MouseEvent.MOUSE_RELEASED, 0, 0, fifthX, 10, 1, false)
            )

            assertTrue(doc.hasSelection, "selection expected after drag")
            assertEquals(0, doc.selectionStart)
            assertEquals(5, doc.selectionEnd)
            assertTrue(content.featuresPanel.isAddEnabled(), "Add Feature button should be enabled after the drag")
        }
    }

    @Test
    fun addFeatureIsUndoableThroughFullContent() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            content.doc.loadSequence(Seq(bases = "ACGTACGTACGTACGTACGT"))
            content.doc.select(2, 6)
            assertTrue(content.featuresPanel.isAddEnabled())
            content.featuresPanel.addFeature("rep", "regulatory")
            assertEquals("rep", content.doc.seq.features.single().name)
            content.doc.undo()
            assertFalse(content.doc.seq.features.isNotEmpty())
        }
    }
}
