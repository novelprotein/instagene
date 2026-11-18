package org.instagene.app.gui.tool.map

import org.instagene.core.sequence.Feature
import java.awt.FontMetrics
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.*

/** Measured logical-space text shared by the canvas and editable exports. */
object CircularMapLayout {
    enum class Kind { ORIGIN, COORDINATE, SITE, TITLE, METADATA, FEATURE, KEY }
    data class FeatureInput(val feature: Feature, val name: String, val lane: Int)
    data class SiteInput(val position: Int, val name: String)
    data class Text(
        val kind: Kind, val text: String, val fullText: String, val x: Int, val baseline: Int,
        val bounds: Rectangle, val metrics: FontMetrics, val feature: Feature? = null,
        val position: Int? = null, val anchor: Point? = null,
    )
    data class Plan(
        val center: Point, val radius: Int, val mapHeight: Int, val keyBounds: Rectangle?, val texts: List<Text>,
    )

    private const val MARGIN = 12
    private const val GAP = 8
    const val TOO_SMALL = "Map text does not fit. Choose a larger export size or show fewer labels."

    fun fitText(metrics: FontMetrics, text: String, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        require(metrics.stringWidth("...") <= width) { TOO_SMALL }
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + "...") > width) {
            end = text.offsetByCodePoints(end, -1)
        }
        return text.substring(0, end) + "..."
    }

    private fun columns(width: Int) = ((width - MARGIN * 2) / 160).coerceIn(1, 3)
    fun keyHeight(count: Int, width: Int, metrics: FontMetrics): Int =
        if (count == 0) 0 else ((count + columns(width) - 1) / columns(width)) * (metrics.height + GAP) + MARGIN * 2

    fun create(
        width: Int, height: Int, frameHeight: Int, length: Int,
        features: List<FeatureInput>, sites: List<SiteInput>, title: String?, metadata: List<String>,
        labels: Boolean, key: Boolean, font: FontMetrics, titleFont: FontMetrics, detailFont: FontMetrics,
        laneSpacing: Int = 15, viewport: Rectangle = Rectangle(0, 0, width, height), crowded: Boolean = features.size > 6,
        compactCaption: Boolean = false,
    ): Plan {
        val footer = keyHeight(if (key) features.size else 0, width, font)
        val mapHeight = height - footer
        val center = Point(width / 2, mapHeight / 2)
        val referencePadding = maxOf(detailFont.stringWidth(formatTick(length)) + 34, detailFont.height + 42)
        val radius = minOf(width, frameHeight, mapHeight) / 2 - referencePadding
        require(radius >= 16) { TOO_SMALL }
        val canvas = Rectangle(4, 4, width - 8, mapHeight - 8)
        val placed = ArrayList<Text>()
        val occupied = ArrayList<Rectangle>()
        fun point(position: Int, ring: Int): Point {
            val angle = PI / 2 - position.toDouble() / length.coerceAtLeast(1) * PI * 2
            return Point(center.x + (cos(angle) * ring).roundToInt(), center.y - (sin(angle) * ring).roundToInt())
        }
        fun text(kind: Kind, value: String, full: String, x: Int, y: Int, fm: FontMetrics = font,
                 feature: Feature? = null, position: Int? = null, anchor: Point? = null): Text =
            Text(kind, value, full, x, y, Rectangle(x - 3, y - fm.ascent - 2, fm.stringWidth(value) + 6, fm.height + 4), fm, feature, position, anchor)
        fun free(item: Text): Boolean = canvas.contains(item.bounds) && occupied.none { it.intersects(item.bounds) }
        fun add(item: Text) {
            placed += item
            occupied += Rectangle(item.bounds).apply { grow(GAP, GAP) }
        }
        fun outside(bounds: Rectangle): Boolean {
            val nearestX = center.x.coerceIn(bounds.x, bounds.x + bounds.width)
            val nearestY = center.y.coerceIn(bounds.y, bounds.y + bounds.height)
            return hypot((nearestX - center.x).toDouble(), (nearestY - center.y).toDouble()) >= radius + 9
        }
        fun radial(kind: Kind, value: String, position: Int, fm: FontMetrics): Text {
            val angle = PI / 2 - position.toDouble() / length.coerceAtLeast(1) * PI * 2
            val anchor = point(position, radius + if (kind == Kind.ORIGIN) 30 else 20)
            val x = when { cos(angle) > 0.25 -> anchor.x + 4; cos(angle) < -0.25 -> anchor.x - fm.stringWidth(value) - 4; else -> anchor.x - fm.stringWidth(value) / 2 }
            val y = when { sin(angle) > 0.25 -> anchor.y - fm.descent - 4; sin(angle) < -0.25 -> anchor.y + fm.ascent + 4; else -> anchor.y + (fm.ascent - fm.descent) / 2 }
            return text(kind, value, value, x, y, fm, position = position)
        }

        // The caption is a reserved block, not merely text painted under callouts.
        val captionWidth = minOf(width - 32, maxOf(radius + radius / 2, 140),
            if (compactCaption) 80 else width)
        val captionLines = ArrayList<Pair<String, FontMetrics>>()
        title?.let { captionLines += fitText(titleFont, it, captionWidth) to titleFont }
        metadata.forEach { line ->
            var current = ""
            for (word in line.split(' ')) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && detailFont.stringWidth(candidate) > captionWidth) {
                    captionLines += current to detailFont
                    current = word
                } else current = candidate
            }
            if (current.isNotEmpty()) captionLines += fitText(detailFont, current, captionWidth) to detailFont
        }
        var captionY = center.y - captionLines.sumOf { it.second.height + 6 } / 2
        captionLines.forEachIndexed { index, (value, fm) ->
            val kind = if (title != null && index == 0) Kind.TITLE else Kind.METADATA
            val item = text(kind, value, if (kind == Kind.TITLE) title!! else value,
                center.x - fm.stringWidth(value) / 2, captionY + fm.ascent, fm)
            require(canvas.contains(item.bounds)) { TOO_SMALL }
            // Caption lines belong to one block; their padded exclusion zones can touch.
            add(item)
            captionY += fm.height + 6
        }

        if (length > 0) {
            val origin = radial(Kind.ORIGIN, "1", 0, detailFont)
            require(free(origin) && outside(origin.bounds)) { TOO_SMALL }
            add(origin)
        }
        val columnWidth = minOf(width / 2 - MARGIN * 2, viewport.width / 2 - MARGIN * 2)
            .coerceAtLeast(font.stringWidth("...") + 6)
        val step = font.height + GAP * 2 + 4
        fun placeCallout(kind: Kind, full: String, left: Boolean, preferredY: Int, anchor: Point,
                         feature: Feature? = null, position: Int? = null, exterior: Boolean = false): Text {
            val rail = if (exterior) Rectangle(0, 0, width, mapHeight) else viewport
            val availableWidth = if (exterior) width / 2 - MARGIN * 2 else columnWidth
            val minimum = font.stringWidth("...")
            val widths = listOf(availableWidth - 6, (availableWidth - 6 + minimum) / 2, minimum).distinct()
            val rows = (MARGIN + font.ascent..mapHeight - MARGIN - font.descent).step(step)
                .sortedBy { abs(it - preferredY) }
            // At fit/export, move to a free row before shortening further. In a
            // zoomed viewport, keep nearby labels visible by shortening first.
            val candidates = if (!exterior && compactCaption) rows.flatMap { y -> widths.map { it to y } }
                else widths.flatMap { budget -> rows.map { budget to it } }
            for ((budget, y) in candidates) {
                val value = fitText(font, full, budget)
                val desiredX = if (left) rail.x + MARGIN else rail.x + rail.width - MARGIN - font.stringWidth(value)
                val x = desiredX.coerceIn(MARGIN, (width - MARGIN - font.stringWidth(value)).coerceAtLeast(MARGIN))
                val item = text(kind, value, full, x, y, feature = feature, position = position, anchor = anchor)
                if (free(item) && (!exterior || outside(item.bounds))) return item
            }
            throw IllegalArgumentException(TOO_SMALL)
        }

        // Biological site labels have priority over optional coordinate labels.
        sites.sortedBy { point(it.position, radius).y }.forEach { site ->
            val anchor = point(site.position, radius + 8)
            val direct = radial(Kind.SITE, fitText(font, site.name, width / 2 - MARGIN * 2 - 6), site.position, font)
                .copy(fullText = site.name, anchor = anchor)
            val item = if (free(direct) && outside(direct.bounds)) direct else
                placeCallout(Kind.SITE, site.name, anchor.x < center.x, anchor.y, anchor, position = site.position, exterior = true)
            add(item)
        }
        var tick = tickStep(length)
        while (tick < length) {
            val item = radial(Kind.COORDINATE, formatTick(tick), tick, detailFont)
            if (free(item) && outside(item.bounds)) add(item)
            tick += tickStep(length)
        }

        if (labels) {
            val callouts = ArrayList<Pair<FeatureInput, Point>>()
            features.forEach { input ->
                val midpoint = (input.feature.start + input.feature.end) / 2
                val ring = radius - 24 - input.lane * laneSpacing
                val anchor = point(midpoint, ring)
                val inlinePoint = point(midpoint, ring - 20)
                val item = text(Kind.FEATURE, input.name, input.name, inlinePoint.x - font.stringWidth(input.name) / 2,
                    inlinePoint.y + font.ascent / 2, feature = input.feature)
                val arcLength = ring * input.feature.length.toDouble() / length.coerceAtLeast(1) * PI * 2
                if (!crowded && arcLength > font.stringWidth(input.name) + 10 && free(item)) add(item)
                else callouts += input to anchor
            }
            callouts.groupBy { it.second.x < center.x }.forEach { (left, side) ->
                val sorted = side.sortedBy { it.second.y }
                val preferred = if (viewport.width < width || viewport.y > 0) {
                    sorted.indices.map { viewport.y + font.ascent + MARGIN + it * step }
                } else CircularLabelLayout.baselines(sorted.size, CircularLabelMetrics(font.height, font.ascent, font.descent),
                    viewport.y, minOf(viewport.height, mapHeight), mapHeight, 0)
                sorted.forEachIndexed { index, (input, anchor) ->
                    add(placeCallout(Kind.FEATURE, input.name, left, preferred[index], anchor, feature = input.feature))
                }
            }
        }

        val keyBounds = if (footer > 0) Rectangle(0, mapHeight, width, footer) else null
        if (keyBounds != null) {
            val columns = columns(width)
            val cellWidth = (width - MARGIN * 2) / columns
            features.forEachIndexed { index, input ->
                val x = MARGIN + index % columns * cellWidth + 16
                val y = mapHeight + MARGIN + font.ascent + index / columns * (font.height + GAP)
                val item = text(Kind.KEY, fitText(font, input.name, cellWidth - 28), input.name, x, y, feature = input.feature)
                require(keyBounds.contains(item.bounds)) { TOO_SMALL }
                placed += item
            }
        }
        return Plan(center, radius, mapHeight, keyBounds, placed)
    }

    fun formatTick(tick: Int): String = if (tick >= 1000 && tick % 1000 == 0) "${tick / 1000} kb" else tick.toString()
    fun tickStep(length: Int): Int {
        val rough = length / 8.0
        val magnitude = 10.0.pow(floor(log10(rough.coerceAtLeast(1.0))))
        return (ceil(rough / magnitude) * magnitude).toInt().coerceAtLeast(1)
    }
}
