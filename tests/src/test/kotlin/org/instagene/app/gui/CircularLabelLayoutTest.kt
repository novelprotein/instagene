package org.instagene.app.gui

import org.instagene.app.gui.tool.map.CircularLabelLayout
import org.instagene.app.gui.tool.map.CircularLabelMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircularLabelLayoutTest {
    private val metrics = CircularLabelMetrics(18, 14, 4)

    @Test
    fun calloutsCenterInTheViewportAndFollowScrolling() {
        assertEquals(listOf(71, 104, 137), CircularLabelLayout.baselines(3, metrics, 0, 200, 500, 0))
        assertEquals(listOf(171, 204, 237), CircularLabelLayout.baselines(3, metrics, 100, 200, 500, 0))
        assertEquals(emptyList(), CircularLabelLayout.baselines(0, metrics, 0, 200, 500, 0))
    }

    @Test
    fun crowdedCalloutsUseTheCanvasWhenTheViewportIsTooShort() {
        assertEquals(listOf(162, 195, 228), CircularLabelLayout.baselines(3, metrics, 100, 60, 380, 0))
    }

    @Test
    fun canvasSizingKeepsLargeFontCalloutsInsideTheCanvasWithoutOverlap() {
        val large = CircularLabelMetrics(48, 36, 12)
        val height = CircularLabelLayout.requiredCanvasHeight(20, large, 4)
        val baselines = CircularLabelLayout.baselines(20, large, 0, 100, height, 4)
        assertEquals(20, baselines.size)
        assertTrue(baselines.first() - large.ascent - 2 >= 0)
        assertTrue(baselines.last() + large.descent + 2 <= height)
        baselines.zipWithNext { previous, next -> assertTrue(next - previous >= large.height + 14) }
        assertEquals(380, CircularLabelLayout.requiredCanvasHeight(1, metrics, 0))
    }
}
