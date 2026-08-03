package org.instagene.app.gui

import org.instagene.core.SeqOps
import org.instagene.core.Strand
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import javax.swing.JPanel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The plasmid map: a circular (or linear) diagram of features and restriction
 * sites. Clicking a feature selects it in the editor.
 */
class PlasmidMapPanel(private val doc: SeqDocument) : JPanel() {

    /** Called when the user clicks a feature or cut site, so the editor can follow. */
    var onSelect: ((Int, Int) -> Unit)? = null

    private val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 15)
    private val subtitleFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    private val labelFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)

    private var centerX = 0
    private var centerY = 0
    private var radius = 0

    init {
        background = Palette.BACKGROUND
        isOpaque = true
        preferredSize = Dimension(380, 380)
        doc.addListener { _, _ -> repaint() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = handleClick(e)
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val seq = doc.seq
        if (seq.length == 0) {
            g2.color = Palette.MUTED
            g2.font = subtitleFont
            g2.drawString("Nothing to map yet.", 16, 28)
            return
        }

        centerX = width / 2
        centerY = height / 2
        radius = (min(width, height) / 2 - 74).coerceAtLeast(40)

        if (seq.isCircular) paintCircular(g2) else paintLinear(g2)
    }

    // ------------------------------------------------------------- circular

    private fun paintCircular(g2: Graphics2D) {
        val seq = doc.seq

        g2.color = Palette.GRID
        g2.stroke = BasicStroke(9f)
        g2.draw(
            Ellipse2D.Double(
                (centerX - radius).toDouble(), (centerY - radius).toDouble(),
                (radius * 2).toDouble(), (radius * 2).toDouble()
            )
        )

        // Decade ticks around the backbone.
        val tickStep = tickStep(seq.length)
        g2.stroke = BasicStroke(1f)
        g2.font = subtitleFont
        var tick = 0
        while (tick < seq.length) {
            val a = angleOf(tick)
            g2.color = Palette.GUTTER
            g2.drawLine(
                pointX(a, radius - 8), pointY(a, radius - 8),
                pointX(a, radius + 8), pointY(a, radius + 8),
            )
            drawCentered(g2, "${tick / 1000}k".takeIf { tick >= 1000 } ?: "$tick", a, radius - 20)
            tick += tickStep
        }

        // Feature arcs, one ring per lane so overlaps stay readable.
        seq.features.forEachIndexed { index, f ->
            val ring = radius - 22 - (index % 3) * 15
            val color = Palette.featureColor(index)
            val startAngle = 90.0 - f.start * 360.0 / seq.length
            val extent = -(f.length * 360.0 / seq.length)
            g2.color = color
            g2.stroke = BasicStroke(11f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            g2.draw(
                Arc2D.Double(
                    (centerX - ring).toDouble(), (centerY - ring).toDouble(),
                    (ring * 2).toDouble(), (ring * 2).toDouble(),
                    startAngle, extent, Arc2D.OPEN,
                )
            )
            // Arrowhead showing the direction of transcription.
            val headAt = if (f.strand == Strand.FORWARD) f.end else f.start
            drawArrowHead(g2, angleOf(headAt), ring, f.strand)

            g2.font = labelFont
            g2.color = Palette.TEXT
            drawCentered(g2, f.name, angleOf((f.start + f.end) / 2), ring - 22)
        }

        // Restriction sites outside the backbone.
        val sites = doc.cutSites.sortedBy { it.topCut }
        g2.font = labelFont
        for (site in sites) {
            val a = angleOf(site.topCut)
            g2.color = Palette.CUT_MARK
            g2.stroke = BasicStroke(1.5f)
            g2.drawLine(
                pointX(a, radius + 4), pointY(a, radius + 4),
                pointX(a, radius + 16), pointY(a, radius + 16),
            )
            drawRadialLabel(g2, "${site.enzyme.name} ${site.topCut + 1}", a, radius + 20)
        }

        // Centre caption.
        g2.color = Palette.TEXT
        g2.font = titleFont
        drawStringCentered(g2, seq.name, centerX, centerY - 6)
        g2.font = subtitleFont
        g2.color = Palette.MUTED
        drawStringCentered(g2, "${seq.length} bp circular", centerX, centerY + 12)
        drawStringCentered(g2, "GC ${"%.1f".format(SeqOps.gcContent(seq))}%", centerX, centerY + 28)
    }

    // --------------------------------------------------------------- linear

    private fun paintLinear(g2: Graphics2D) {
        val seq = doc.seq
        val left = 40
        val right = width - 40
        val span = (right - left).coerceAtLeast(1)
        val axisY = height / 2

        g2.color = Palette.GRID
        g2.stroke = BasicStroke(8f)
        g2.drawLine(left, axisY, right, axisY)

        g2.stroke = BasicStroke(1f)
        g2.font = subtitleFont
        val tickStep = tickStep(seq.length)
        var tick = 0
        while (tick <= seq.length) {
            val x = left + (tick.toDouble() / seq.length * span).roundToInt()
            g2.color = Palette.GUTTER
            g2.drawLine(x, axisY + 6, x, axisY + 12)
            drawStringCentered(g2, "$tick", x, axisY + 26)
            tick += tickStep
        }

        seq.features.forEachIndexed { index, f ->
            val x1 = left + (f.start.toDouble() / seq.length * span).roundToInt()
            val x2 = left + (f.end.toDouble() / seq.length * span).roundToInt()
            val y = axisY - 22 - (index % 3) * 16
            val color = Palette.featureColor(index)
            g2.color = Palette.translucent(color, 0x99)
            g2.fillRoundRect(x1, y, maxOf(4, x2 - x1), 12, 6, 6)
            g2.color = color
            g2.drawRoundRect(x1, y, maxOf(4, x2 - x1), 12, 6, 6)
            g2.color = Palette.TEXT
            g2.font = labelFont
            g2.drawString(f.name, x1, y - 2)
        }

        g2.font = labelFont
        for (site in doc.cutSites) {
            val x = left + (site.topCut.toDouble() / seq.length * span).roundToInt()
            g2.color = Palette.CUT_MARK
            g2.drawLine(x, axisY - 8, x, axisY + 8)
            g2.drawString(site.enzyme.name, x + 2, axisY + 44)
        }

        g2.color = Palette.TEXT
        g2.font = titleFont
        drawStringCentered(g2, seq.name, width / 2, 26)
        g2.font = subtitleFont
        g2.color = Palette.MUTED
        drawStringCentered(g2, "${seq.length} bp linear", width / 2, 44)
    }

    // ---------------------------------------------------------------- helpers

    private fun tickStep(length: Int): Int {
        val rough = length / 8.0
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(rough.coerceAtLeast(1.0))))
        val step = (Math.ceil(rough / magnitude) * magnitude).toInt()
        return step.coerceAtLeast(1)
    }

    /** Radians for a position, measured clockwise from twelve o'clock. */
    private fun angleOf(position: Int): Double =
        PI / 2 - position.toDouble() / doc.seq.length * 2 * PI

    private fun pointX(angle: Double, r: Int) = centerX + (cos(angle) * r).roundToInt()
    private fun pointY(angle: Double, r: Int) = centerY - (sin(angle) * r).roundToInt()

    private fun drawArrowHead(g2: Graphics2D, angle: Double, ring: Int, strand: Strand) {
        val tipOffset = if (strand == Strand.FORWARD) -0.035 else 0.035
        val tip = angle + tipOffset
        val xs = intArrayOf(pointX(tip, ring), pointX(angle, ring - 9), pointX(angle, ring + 9))
        val ys = intArrayOf(pointY(tip, ring), pointY(angle, ring - 9), pointY(angle, ring + 9))
        g2.fillPolygon(xs, ys, 3)
    }

    private fun drawCentered(g2: Graphics2D, text: String, angle: Double, r: Int) {
        drawStringCentered(g2, text, pointX(angle, r), pointY(angle, r) + 4)
    }

    /** Labels outside the circle are pushed left or right so they never overlap it. */
    private fun drawRadialLabel(g2: Graphics2D, text: String, angle: Double, r: Int) {
        val x = pointX(angle, r)
        val y = pointY(angle, r) + 4
        val fm = g2.fontMetrics
        val onLeft = cos(angle) < 0
        g2.drawString(text, if (onLeft) x - fm.stringWidth(text) else x, y)
    }

    private fun drawStringCentered(g2: Graphics2D, text: String, x: Int, y: Int) {
        g2.drawString(text, x - g2.fontMetrics.stringWidth(text) / 2, y)
    }

    private fun handleClick(e: MouseEvent) {
        val seq = doc.seq
        if (seq.length == 0) return
        val position = if (seq.isCircular) {
            val dx = (e.x - centerX).toDouble()
            val dy = (centerY - e.y).toDouble()
            var angle = PI / 2 - atan2(dy, dx)
            if (angle < 0) angle += 2 * PI
            (angle / (2 * PI) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
        } else {
            val left = 40
            val span = (width - 80).coerceAtLeast(1)
            (((e.x - left).toDouble() / span) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
        }
        val feature = seq.features.firstOrNull { position in it.start until it.end }
        if (feature != null) {
            onSelect?.invoke(feature.start, feature.end)
        } else {
            onSelect?.invoke(position, position + 1)
        }
    }
}
