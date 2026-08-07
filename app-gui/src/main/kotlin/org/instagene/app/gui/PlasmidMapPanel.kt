package org.instagene.app.gui

import org.instagene.core.Feature
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.core.Strand
import org.instagene.core.Topology
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JPanel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The plasmid map: a circular (or linear) diagram of features and restriction
 * sites. Clicking a feature selects it in the editor. The header offers a
 * "Circular" toggle, which is only ever checked when the sequence actually is
 * circular (i.e. a plasmid), never on by default.
 */
class PlasmidMapPanel(private val doc: SeqDocument) : JPanel(BorderLayout(0, 4)) {

    /** Called when the user clicks a feature or cut site, so the editor can follow. */
    var onSelect: ((Int, Int) -> Unit)? = null

    /** Exposed for tests: reflects the sequence's actual topology, never defaults on. */
    val circularCheckbox: JCheckBox = JCheckBox("Circular").apply {
        toolTipText = "Toggle between circular (plasmid) and linear topology"
    }

    private val mapCanvas = MapCanvas()

    init {
        background = Palette.BACKGROUND
        isOpaque = true
        border = BorderFactory.createEmptyBorder(6, 8, 8, 8)

        mapCanvas.onSelect = { start, end -> onSelect?.invoke(start, end) }

        circularCheckbox.addActionListener {
            val target = if (circularCheckbox.isSelected) Topology.CIRCULAR else Topology.LINEAR
            doc.mutate(if (target == Topology.CIRCULAR) "make circular" else "make linear") {
                it.withTopology(target)
            }
        }
        doc.addListener { _, reason ->
            mapCanvas.repaint()
            if (reason == SeqDocument.Reason.SEQUENCE) syncTopologyControl()
        }
        syncTopologyControl()

        add(JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(circularCheckbox, BorderLayout.WEST)
        }, BorderLayout.NORTH)
        add(mapCanvas, BorderLayout.CENTER)
    }

    /** The checkbox mirrors `seq.isCircular`; it is never pre-checked for a linear sample. */
    private fun syncTopologyControl() {
        circularCheckbox.isSelected = doc.seq.isCircular
        circularCheckbox.isEnabled = doc.seq.kind != SeqKind.PROTEIN
    }

    private inner class MapCanvas : JPanel() {

        var onSelect: ((Int, Int) -> Unit)? = null

        private val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 15)
        private val subtitleFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        private val labelFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)

        private var centerX = 0
        private var centerY = 0
        private var backboneRadius = 0
        private val ringOf = HashMap<Feature, Int>()
        private var ringCount = 0
        private val laneOf = HashMap<Feature, Int>()
        private var laneCount = 0

        init {
            isOpaque = true
            background = Palette.BACKGROUND
            preferredSize = Dimension(380, 380)
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

            if (seq.isCircular) paintCircular(g2) else paintLinear(g2)
        }

        // ------------------------------------------------------------- circular

        /** Greedy interval packing so overlapping features get their own ring. */
        private fun assignRings() {
            ringOf.clear()
            val ends = ArrayList<Int>()
            for (f in doc.seq.features.sortedBy { it.start }) {
                var lane = ends.indexOfFirst { it <= f.start }
                if (lane < 0) {
                    lane = ends.size
                    ends += f.end
                } else {
                    ends[lane] = f.end
                }
                ringOf[f] = lane
            }
            ringCount = ends.size
        }

        private fun paintCircular(g2: Graphics2D) {
            val seq = doc.seq
            assignRings()

            val available = min(width, height) / 2
            val ringBand = ringCount * 15 + 24
            backboneRadius = (available - 26).coerceAtLeast(ringBand + 30).coerceAtMost(available - 18)
            val r = backboneRadius

            // Origin marker (base 1) at twelve o'clock.
            g2.color = Palette.CUT_MARK
            g2.stroke = BasicStroke(2f)
            g2.drawLine(pointX(PI / 2, r - 2), pointY(PI / 2, r - 2), pointX(PI / 2, r - 12), pointY(PI / 2, r - 12))
            g2.font = labelFont
            g2.color = Palette.TEXT
            drawStringCentered(g2, "1", pointX(PI / 2, r - 20), pointY(PI / 2, r - 20) + 4)

            // Backbone.
            g2.color = Palette.GRID
            g2.stroke = BasicStroke(9f)
            g2.draw(
                Ellipse2D.Double(
                    (centerX - r).toDouble(), (centerY - r).toDouble(),
                    (r * 2).toDouble(), (r * 2).toDouble()
                )
            )

            // Current selection, highlighted as an arc just inside the backbone.
            if (doc.hasSelection) {
                val s = doc.selectionStart
                val e = doc.selectionEnd
                if (e > s) {
                    g2.color = Palette.translucent(Palette.SELECTION, 0x66)
                    g2.stroke = BasicStroke(7f)
                    g2.draw(
                        Arc2D.Double(
                            (centerX - r + 6).toDouble(), (centerY - r + 6).toDouble(),
                            ((r - 6) * 2).toDouble(), ((r - 6) * 2).toDouble(),
                            90.0 - s * 360.0 / seq.length,
                            -(e - s) * 360.0 / seq.length,
                            Arc2D.OPEN,
                        )
                    )
                }
            }

            // Decade ticks around the backbone (base 1 is the origin marker instead of 0).
            val tickStep = tickStep(seq.length)
            g2.stroke = BasicStroke(1f)
            g2.font = subtitleFont
            var tick = tickStep
            while (tick < seq.length) {
                val a = angleOf(tick)
                g2.color = Palette.GUTTER
                g2.drawLine(
                    pointX(a, r - 8), pointY(a, r - 8),
                    pointX(a, r + 8), pointY(a, r + 8),
                )
                drawCentered(g2, "${tick / 1000}k".takeIf { tick >= 1000 } ?: "$tick", a, r - 20)
                tick += tickStep
            }

            // Feature arcs, one packed ring per lane so overlaps stay readable.
            val fm = g2.fontMetrics
            seq.features.forEachIndexed { index, f ->
                val ring = r - 24 - (ringOf[f] ?: 0) * 15
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

                // Name label only when the arc is long enough to fit it.
                g2.font = labelFont
                val arcLength = ring * abs(extent) * PI / 180.0
                if (arcLength > fm.stringWidth(f.name) + 10) {
                    g2.color = Palette.TEXT
                    drawCentered(g2, f.name, angleOf((f.start + f.end) / 2), ring - 20)
                }
            }

            // Restriction sites outside the backbone.
            val sites = doc.cutSites.sortedBy { it.topCut }
            g2.font = labelFont
            for (site in sites) {
                val a = angleOf(site.topCut)
                g2.color = Palette.CUT_MARK
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(
                    pointX(a, r + 4), pointY(a, r + 4),
                    pointX(a, r + 16), pointY(a, r + 16),
                )
                drawRadialLabel(g2, "${site.enzyme.name} ${site.topCut + 1}", a, r + 20)
            }

            // Centre caption.
            g2.color = Palette.TEXT
            g2.font = titleFont
            drawStringCentered(g2, seq.name, centerX, centerY - 6)
            g2.font = subtitleFont
            g2.color = Palette.MUTED
            drawStringCentered(
                g2, "${seq.length} bp ${seq.kind.name.lowercase()} circular", centerX, centerY + 12
            )
            drawStringCentered(g2, "GC ${"%.1f".format(SeqOps.gcContent(seq))}%", centerX, centerY + 28)
        }

        // --------------------------------------------------------------- linear

        /** Greedy interval packing for the linear view's feature lanes. */
        private fun assignLanes() {
            laneOf.clear()
            val ends = ArrayList<Int>()
            for (f in doc.seq.features.sortedBy { it.start }) {
                var lane = ends.indexOfFirst { it <= f.start }
                if (lane < 0) {
                    lane = ends.size
                    ends += f.end
                } else {
                    ends[lane] = f.end
                }
                laneOf[f] = lane
            }
            laneCount = ends.size
        }

        private fun paintLinear(g2: Graphics2D) {
            val seq = doc.seq
            assignLanes()

            val left = 40
            val right = width - 40
            val span = (right - left).coerceAtLeast(1)
            val laneH = 16
            val axisY = (height / 2 + laneCount * laneH / 2).coerceAtMost(height - 60)

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

            g2.font = labelFont
            val fm = g2.fontMetrics
            seq.features.forEachIndexed { index, f ->
                val lane = laneOf[f] ?: 0
                val x1 = left + (f.start.toDouble() / seq.length * span).roundToInt()
                val x2 = left + (f.end.toDouble() / seq.length * span).roundToInt()
                val y = axisY - 24 - lane * laneH
                val w = maxOf(4, x2 - x1)
                val color = Palette.featureColor(index)
                g2.color = Palette.translucent(color, 0x99)
                g2.fillRoundRect(x1, y, w, 12, 6, 6)
                g2.color = color
                g2.drawRoundRect(x1, y, w, 12, 6, 6)
                if (w > fm.stringWidth(f.name) + 8) {
                    g2.color = Palette.TEXT
                    g2.drawString(f.name, x1, y - 2)
                }
            }

            if (doc.cutSites.isNotEmpty()) {
                g2.font = labelFont
                for (site in doc.cutSites) {
                    val x = left + (site.topCut.toDouble() / seq.length * span).roundToInt()
                    g2.color = Palette.CUT_MARK
                    g2.drawLine(x, axisY - 8, x, axisY + 8)
                    g2.drawString(site.enzyme.name, x + 2, axisY + 44)
                }
            }

            g2.color = Palette.TEXT
            g2.font = titleFont
            drawStringCentered(g2, seq.name, width / 2, 26)
            g2.font = subtitleFont
            g2.color = Palette.MUTED
            drawStringCentered(g2, "${seq.length} bp ${seq.kind.name.lowercase()} linear", width / 2, 44)
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

        private fun pointX(angle: Double, radius: Int) = centerX + (cos(angle) * radius).roundToInt()
        private fun pointY(angle: Double, radius: Int) = centerY - (sin(angle) * radius).roundToInt()

        private fun drawArrowHead(g2: Graphics2D, angle: Double, ring: Int, strand: Strand) {
            val tipOffset = if (strand == Strand.FORWARD) -0.035 else 0.035
            val tip = angle + tipOffset
            val xs = intArrayOf(pointX(tip, ring), pointX(angle, ring - 9), pointX(angle, ring + 9))
            val ys = intArrayOf(pointY(tip, ring), pointY(angle, ring - 9), pointY(angle, ring + 9))
            g2.fillPolygon(xs, ys, 3)
        }

        private fun drawCentered(g2: Graphics2D, text: String, angle: Double, radius: Int) {
            drawStringCentered(g2, text, pointX(angle, radius), pointY(angle, radius) + 4)
        }

        /** Labels outside the circle are pushed left or right so they never overlap it. */
        private fun drawRadialLabel(g2: Graphics2D, text: String, angle: Double, radius: Int) {
            val x = pointX(angle, radius)
            val y = pointY(angle, radius) + 4
            val fm = g2.fontMetrics
            val onLeft = cos(angle) < 0
            g2.drawString(text, if (onLeft) x - fm.stringWidth(text) else x, y)
        }

        private fun drawStringCentered(g2: Graphics2D, text: String, x: Int, y: Int) {
            g2.drawString(text, x - g2.fontMetrics.stringWidth(text) / 2, y)
        }

        /** Maps a click to a feature or base position; clicks outside the map do nothing. */
        private fun handleClick(e: MouseEvent) {
            val seq = doc.seq
            if (seq.length == 0) return
            val position = if (seq.isCircular) {
                val dx = (e.x - centerX).toDouble()
                val dy = (centerY - e.y).toDouble()
                val dist = sqrt(dx * dx + dy * dy)
                val outer = backboneRadius + 18.0
                val inner = (backboneRadius - 24 - ringCount * 15 - 8).coerceAtLeast(0)
                if (dist > outer || dist < inner) return
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
}
