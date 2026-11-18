package org.instagene.app.gui.tool.map

data class CircularLabelMetrics(val height: Int, val ascent: Int, val descent: Int)

/** Logical-space callout geometry, independent of painting and Swing state. */
object CircularLabelLayout {
    fun baselines(
        count: Int,
        metrics: CircularLabelMetrics,
        viewportTop: Int,
        viewportHeight: Int,
        canvasHeight: Int,
        zoomPadding: Int,
    ): List<Int> {
        if (count == 0) return emptyList()
        val preferredSpacing = metrics.height + 15 + zoomPadding
        val minimumSpacing = metrics.height + 14
        val canFitInViewport = count <= 1 || viewportHeight >= minimumSpacing * (count - 1) + metrics.height + 16
        val topLimit = metrics.ascent + 11
        val bottomLimit = (canvasHeight - metrics.descent - 11).coerceAtLeast(topLimit)
        val minBaseline = if (canFitInViewport) {
            (viewportTop + topLimit).coerceIn(topLimit, bottomLimit)
        } else topLimit
        val maxBaseline = if (canFitInViewport) {
            (viewportTop + viewportHeight - metrics.descent - 13).coerceIn(minBaseline, bottomLimit)
        } else bottomLimit
        val availableSpacing = if (count == 1) preferredSpacing else
            ((maxBaseline - minBaseline).toDouble() / (count - 1)).toInt().coerceAtLeast(1)
        // Prefer breathing room, compact to the minimum legible gap when needed.
        // A crowded column that cannot fit in the viewport uses the whole canvas.
        val spacing = if (count == 1) preferredSpacing else
            minOf(preferredSpacing, availableSpacing).coerceAtLeast(minimumSpacing)
        val totalSpacing = spacing * (count - 1)
        val firstBaseline = if (totalSpacing <= maxBaseline - minBaseline) {
            (minBaseline + maxBaseline - totalSpacing) / 2
        } else minBaseline
        return (0 until count).map { firstBaseline + it * spacing }
    }

    fun requiredCanvasHeight(count: Int, metrics: CircularLabelMetrics, zoomPadding: Int): Int {
        if (count <= 1) return 380
        val lastBaseline = metrics.ascent + 11 + (count - 1) * (metrics.height + 15 + zoomPadding)
        return maxOf(380, lastBaseline + metrics.descent + 16)
    }
}
