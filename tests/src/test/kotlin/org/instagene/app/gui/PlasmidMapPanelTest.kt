package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.Topology
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Section selection on the plasmid map: dragging across the map selects the
 * range between the press and release points, and a plain click still selects
 * a feature (or a single base). Events are dispatched to the painted canvas,
 * exactly as a real mouse would deliver them.
 */
class PlasmidMapPanelTest {

    /** 400 bp circular sequence, so one full turn is exactly 1 degree per base. */
    private val circularLength = 400

    private val circular = Seq(bases = "ACGT".repeat(100), topology = Topology.CIRCULAR)

    /**
     * Lays the map out, paints it (the click/drag handlers depend on the
     * centre and ring geometry computed during painting), and returns the
     * inner Map canvas.
     */
    private fun paintableMap(content: InstaGeneContent): JPanel {
        val map = content.plasmidMapPanel
        map.setSize(400, 400)
        map.doLayout()
        map.paint(BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB).graphics)
        // BorderLayout children: header panel first, map canvas second.
        return map.getComponent(1) as JPanel
    }

    /**
     * Screen point for [position] on the 400 bp circular map: the same centre
     * and the same integer division `paintComponent` uses, at a 150 px radius
     * which always lands inside the ring.
     */
    private fun point(canvas: JPanel, position: Int): Pair<Int, Int> {
        val cx = canvas.width / 2
        val cy = canvas.height / 2
        val angle = PI / 2 - position.toDouble() / circularLength * 2 * PI
        return (cx + (cos(angle) * 150).roundToInt()) to (cy - (sin(angle) * 150).roundToInt())
    }

    private fun press(canvas: JPanel, p: Pair<Int, Int>) {
        canvas.dispatchEvent(
            MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, p.first, p.second, 1, false, MouseEvent.BUTTON1)
        )
    }

    private fun dragged(canvas: JPanel, p: Pair<Int, Int>) {
        canvas.dispatchEvent(
            MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, p.first, p.second, 0, false, MouseEvent.BUTTON1)
        )
    }

    private fun release(canvas: JPanel, p: Pair<Int, Int>) {
        canvas.dispatchEvent(
            MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, InputEvent.BUTTON1_DOWN_MASK, p.first, p.second, 1, false, MouseEvent.BUTTON1)
        )
    }

    @Test
    fun dragOnCircularMapSelectsTheSectionBetweenPressAndRelease() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            content.doc.loadSequence(circular)
            val canvas = paintableMap(content)

            press(canvas, point(canvas, 0))
            dragged(canvas, point(canvas, 50))
            dragged(canvas, point(canvas, 100))
            release(canvas, point(canvas, 100))

            assertTrue(content.doc.hasSelection, "expected a range selection after the drag")
            assertEquals(0, content.doc.selectionStart)
            assertEquals(100, content.doc.selectionEnd)
        }
    }

    @Test
    fun dragMovesBetweenTwoPositionsRegardlessOfDirection() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            content.doc.loadSequence(circular)
            val canvas = paintableMap(content)

            press(canvas, point(canvas, 300))
            dragged(canvas, point(canvas, 250))
            release(canvas, point(canvas, 250))

            assertEquals(250, content.doc.selectionStart)
            assertEquals(300, content.doc.selectionEnd)
        }
    }

    @Test
    fun dragOnLinearMapSelectsAStraightRange() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            content.doc.loadSequence(Seq(bases = "ACGT".repeat(50)))
            val canvas = paintableMap(content)

            // One quarter along the linear backbone: x = 40 + 0.25 * (width - 80).
            val span = canvas.width - 80
            val x = 40 + span / 4
            press(canvas, 40 to 150)
            dragged(canvas, x to 150)
            release(canvas, x to 150)

            assertTrue(content.doc.selectionEnd > content.doc.selectionStart)
            assertEquals(0, content.doc.selectionStart)
            assertEquals(50, content.doc.selectionEnd)
        }
    }

    @Test
    fun clickOnFeatureSelectsTheWholeFeature() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            content.doc.loadSequence(circular)
            content.featuresPanel.addFeatureManually("ori", "rep_origin", 51, 100)
            val canvas = paintableMap(content)

            press(canvas, point(canvas, 75))
            release(canvas, point(canvas, 75))

            assertEquals(50, content.doc.selectionStart)
            assertEquals(100, content.doc.selectionEnd)
        }
    }

    @Test
    fun clickOnBareBackboneSelectsASingleBase() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            content.doc.loadSequence(circular)
            val canvas = paintableMap(content)

            press(canvas, point(canvas, 200))
            release(canvas, point(canvas, 200))

            assertFalse(content.doc.selectionEnd > content.doc.selectionStart + 1)
            assertEquals(200, content.doc.selectionStart)
        }
    }
}