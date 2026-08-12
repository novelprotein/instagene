package org.instagene.app.gui

import org.instagene.app.gui.ui.InstaGeneContent
import org.instagene.app.gui.ui.PlasmidMapPanel
import org.instagene.app.gui.ui.SeqDocument
import org.instagene.core.Feature
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
     * Returns the screen point for [position] on the 400 bp circular map, using
     * the same centre and integer division as `paintComponent`. A 150 px radius
     * places the point inside the ring.
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

    private fun renderMap(seq: Seq, width: Int, height: Int): BufferedImage {
        val map = PlasmidMapPanel(SeqDocument(seq))
        map.setSize(width, height)
        map.doLayout()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            map.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun imagesDiffer(first: BufferedImage, second: BufferedImage): Boolean {
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) return true
            }
        }
        return false
    }

    /** Renaming only one feature must change pixels if that feature has a visible label. */
    private fun assertEveryFeatureIsLabelled(seq: Seq, width: Int, height: Int) {
        val original = renderMap(seq, width, height)
        seq.features.indices.forEach { renamedIndex ->
            val renamed = seq.copy(
                features = seq.features.mapIndexed { index, feature ->
                    if (index == renamedIndex) feature.copy(name = "renamed-$renamedIndex") else feature
                }
            )
            assertTrue(
                imagesDiffer(original, renderMap(renamed, width, height)),
                "feature $renamedIndex did not have a visible label",
            )
        }
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

    @Test
    fun everyCircularFeatureHasAVisibleLabelOnACompactMap() {
        SwingUtilities.invokeAndWait {
            val features = listOf(
                Feature("alpha", start = 10, end = 12),
                Feature("beta", start = 70, end = 72),
                Feature("", type = "rep_origin", start = 120, end = 122),
                Feature("delta", start = 195, end = 198),
                Feature("epsilon", start = 250, end = 253),
                Feature("zeta", start = 251, end = 254),
            )
            assertEveryFeatureIsLabelled(circular.copy(features = features), 340, 300)
        }
    }

    @Test
    fun everyLinearFeatureHasAVisibleLabelOnACompactMap() {
        SwingUtilities.invokeAndWait {
            val features = listOf(
                Feature("alpha", start = 10, end = 12),
                Feature("beta", start = 70, end = 72),
                Feature("", type = "promoter", start = 120, end = 122),
                Feature("delta", start = 195, end = 198),
                Feature("epsilon", start = 250, end = 253),
                Feature("zeta", start = 251, end = 254),
            )
            assertEveryFeatureIsLabelled(circular.copy(topology = Topology.LINEAR, features = features), 360, 240)
        }
    }
}
