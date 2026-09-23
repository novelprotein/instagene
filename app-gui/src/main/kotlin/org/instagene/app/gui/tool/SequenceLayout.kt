package org.instagene.app.gui.tool

import java.awt.Rectangle

enum class SequenceGraphics(val label: String) {
    DETAILED("Detailed"), SIMPLIFIED("Simplified");
    override fun toString() = label
}

/** Cached row and annotation geometry, shared by painting, hit testing and navigation. */
internal class SequenceLayout(
    val length: Int,
    val columns: Int,
    val cellWidth: Int,
    val textHeight: Int,
    val gutter: Int,
    val tracks: Int,
    val laneHeight: Int,
    val detailed: Boolean,
    objects: List<SequenceObject>,
) {
    data class Bar(val item: SequenceObject, val start: Int, val end: Int, val lane: Int)
    data class Row(val index: Int, val top: Int, val marks: Int, val height: Int, val bars: List<Bar>, val sites: List<SequenceObject.Site>)
    val rows: List<Row>
    val height: Int
    init {
        val count = maxOf(1, (length + columns - 1) / columns)
        val bars = Array(count) { mutableListOf<Bar>() }
        val sites = Array(count) { mutableListOf<SequenceObject.Site>() }
        objects.forEach { item ->
            if (item is SequenceObject.Site) {
                val row = (item.site.topCut.coerceIn(0, length) / columns).coerceAtMost(count - 1)
                sites[row] += item
            } else {
                val spans = if (item is SequenceObject.Annotation) item.feature.locationSegments.map { it.start to it.end }
                    else listOf(item.start to item.end)
                spans.forEach { (start, end) ->
                    if (end > start && start < length) {
                        for (r in (start / columns).coerceAtLeast(0)..((end - 1) / columns).coerceAtMost(count - 1)) {
                            bars[r] += Bar(item, maxOf(start, r * columns), minOf(end, (r + 1) * columns), 0)
                        }
                    }
                }
            }
        }
        var y = 10
        rows = (0 until count).map { r ->
            val ends = mutableListOf<Int>()
            val packed = bars[r].sortedBy { it.start }.map { bar ->
                val lane = ends.indexOfFirst { it <= bar.start }.let { if (it < 0) ends.size else it }
                if (lane == ends.size) ends += bar.end else ends[lane] = bar.end
                bar.copy(lane = lane)
            }
            val marks = if (sites[r].isEmpty()) 0 else if (detailed) laneHeight * 2 + 12 else 12
            val h = marks + tracks * textHeight + ends.size * laneHeight + 14
            Row(r, y, marks, h, packed, sites[r]).also { y += h }
        }
        height = y + 10
    }
    fun rowAt(y: Int): Row {
        var lo = 0
        var hi = rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rows[mid].top <= y) lo = mid else hi = mid - 1
        }
        return rows[lo]
    }
    fun rowFor(position: Int) = rows[(position.coerceIn(0, length) / columns).coerceAtMost(rows.lastIndex)]
    fun x(column: Int) = 10 + gutter + column * cellWidth
    fun indexAt(x: Int, y: Int): Int {
        val row = rowAt(y)
        val col = ((x - this.x(0) + cellWidth / 2) / cellWidth).coerceIn(0, columns)
        return (row.index * columns + col).coerceAtMost(length)
    }
    fun bounds(row: Row, bar: Bar): Rectangle = Rectangle(
        x(bar.start - row.index * columns), row.top + row.marks + tracks * textHeight + bar.lane * laneHeight,
        maxOf(3, (bar.end - bar.start) * cellWidth), laneHeight - 2,
    )
}
