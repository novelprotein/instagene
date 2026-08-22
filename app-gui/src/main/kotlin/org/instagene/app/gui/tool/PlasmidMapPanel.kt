package org.instagene.app.gui.tool

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.theme.Palette
import org.instagene.core.Feature
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.core.Strand
import org.instagene.core.Topology
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class MapPreset(val width: Int, val height: Int) {
    PRESENTATION(1600, 1200),
    PAPER(1200, 900),
    NOTEBOOK(900, 700),
}

data class MapExportOptions(
    val preset: MapPreset = MapPreset.PAPER,
    val title: String? = null,
    val showFeatureLabels: Boolean = true,
    val showRestrictionSites: Boolean = true,
)

/**
 * The plasmid map: a circular (or linear) diagram of features and restriction
 * sites. Clicking a feature selects it in the editor; dragging selects the
 * section between the press and release points. The header's "Circular" toggle
 * always reflects the sequence's actual topology.
 */
class PlasmidMapPanel(initial: SeqDocument) : JPanel(BorderLayout(0, 4)) {

    /** The displayed document, rebound when the active tab changes. */
    private var doc = initial
    private var docListener: SeqDocument.Listener? = null

    /** Called when the user clicks a feature or cut site, so the editor can follow. */
    var onSelect: ((Int, Int) -> Unit)? = null

    /** Exposed for tests: reflects the sequence's actual topology. */
    val circularCheckbox: JCheckBox = JCheckBox("Circular").apply {
        toolTipText = "Toggle between circular (plasmid) and linear topology"
    }
    val showFeatureLabels: JCheckBox = JCheckBox("Feature labels", true)
    val showRestrictionSites: JCheckBox = JCheckBox("Restriction sites", true)

    private data class CircularLabel(
        val text: String,
        val angle: Double,
        val ring: Int,
        val arcLength: Double,
    )

    private data class LinearLabel(
        val text: String,
        val anchorX: Int,
        val lane: Int,
        val colorIndex: Int,
    )

    private data class PlacedLinearLabel(
        val label: LinearLabel,
        val x: Int,
        val row: Int,
    )

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
        docListener = SeqDocument.Listener { _, reason ->
            mapCanvas.repaint()
            if (reason == SeqDocument.Reason.SEQUENCE) syncTopologyControl()
        }
        doc.addListener(docListener!!)
        syncTopologyControl()

        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(circularCheckbox, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(showFeatureLabels)
                add(showRestrictionSites)
            }, BorderLayout.CENTER)
            add(JButton("Export PNG").apply {
                addActionListener {
                    val chooser = JFileChooser().apply { dialogTitle = "Export map as PNG" }
                    if (chooser.showSaveDialog(this@PlasmidMapPanel) == JFileChooser.APPROVE_OPTION) exportPng(chooser.selectedFile)
                }
            }, BorderLayout.CENTER)
            add(JButton("Export SVG").apply {
                addActionListener {
                    val chooser = JFileChooser().apply { dialogTitle = "Export map as SVG" }
                    if (chooser.showSaveDialog(this@PlasmidMapPanel) == JFileChooser.APPROVE_OPTION) exportSvg(chooser.selectedFile)
                }
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        showFeatureLabels.addActionListener { mapCanvas.repaint() }
        showRestrictionSites.addActionListener { mapCanvas.repaint() }
        add(mapCanvas, BorderLayout.CENTER)
    }

    private fun featureColor(feature: Feature, index: Int): Color = feature.color?.let {
        runCatching { Color.decode(it) }.getOrNull()
    } ?: Palette.featureColor(index)

    /**
     * Binds this panel to another document and keeps the topology control in sync.
     */
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc !== doc) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            if (docListener != null) doc.addListener(docListener!!)
        }
        if (docListener == null) {
            docListener = SeqDocument.Listener { _, reason ->
                mapCanvas.repaint()
                if (reason == SeqDocument.Reason.SEQUENCE) syncTopologyControl()
            }
            doc.addListener(docListener!!)
        }
        syncTopologyControl()
        mapCanvas.repaint()
    }

    /** Renders the current map to a PNG without requiring a visible window. */
    fun exportPng(file: File, width: Int = 1200, height: Int = 900) {
        require(width > 0 && height > 0) { "Map dimensions must be positive" }
        mapCanvas.setSize(width, height)
        mapCanvas.doLayout()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { mapCanvas.paint(graphics) } finally { graphics.dispose() }
        ImageIO.write(image, "png", file)
    }

    /** Renders a PNG using a named researcher-facing preset. */
    fun exportPng(file: File, options: MapExportOptions) {
        exportPng(file, options.preset.width, options.preset.height)
    }

    /** Exports a lightweight, editable SVG representation of the current map. */
    fun exportSvg(file: File, width: Int = 1200, height: Int = 900) {
        exportSvg(file, MapExportOptions(), width, height)
    }

    /** Exports an SVG with reproducible title and visibility settings. */
    fun exportSvg(file: File, options: MapExportOptions) {
        exportSvg(file, options, options.preset.width, options.preset.height)
    }

    private fun exportSvg(file: File, options: MapExportOptions, width: Int, height: Int) {
        val seq = doc.seq
        val cx = width / 2
        val cy = height / 2
        val radius = min(width, height) / 3
        val featureSvg = seq.features.filter { it.visible }.mapIndexed { index, feature ->
            val start = feature.start.toDouble() / seq.length * 360.0 - 90.0
            val end = feature.end.toDouble() / seq.length * 360.0 - 90.0
            val color = feature.color ?: "#4c8bf5"
            val y = cy - radius - 18 - (index % 8) * 14
            val label = if (options.showFeatureLabels) "<text x=\"${cx + radius + 18}\" y=\"$y\" font-size=\"12\">${escapeSvg(feature.name)}</text>" else ""
            "<path d=\"${svgArc(cx, cy, radius + (index % 4) * 12, start, end)}\" fill=\"none\" stroke=\"$color\" stroke-width=\"10\"/>$label"
        }.joinToString("\n")
        val siteSvg = if (options.showRestrictionSites) doc.cutSites.map { site ->
            val angle = Math.toRadians(site.recognitionStart.toDouble() / seq.length * 360.0 - 90.0)
            val x = cx + cos(angle) * (radius + 22)
            val y = cy + sin(angle) * (radius + 22)
            "<circle cx=\"$x\" cy=\"$y\" r=\"3\" fill=\"#8a4baf\"/><text x=\"${x + 5}\" y=\"$y\" font-size=\"10\">${escapeSvg(site.enzyme.name)}</text>"
        }.joinToString("\n") else ""
        val title = options.title?.takeIf { it.isNotBlank() } ?: "${seq.name} (${seq.length} bp)"
        file.writeText("""
            <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
              <rect width="100%" height="100%" fill="#ffffff"/>
              <circle cx="$cx" cy="$cy" r="$radius" fill="none" stroke="#9aa3ad" stroke-width="8"/>
              <text x="$cx" y="${cy + 5}" text-anchor="middle" font-size="16">${escapeSvg(title)}</text>
              $featureSvg
              $siteSvg
            </svg>
        """.trimIndent())
    }

    private fun svgArc(cx: Int, cy: Int, radius: Int, start: Double, end: Double): String {
        val s = Math.toRadians(start)
        val e = Math.toRadians(end)
        val x1 = cx + cos(s) * radius
        val y1 = cy + sin(s) * radius
        val x2 = cx + cos(e) * radius
        val y2 = cy + sin(e) * radius
        val large = if (abs(end - start) > 180) 1 else 0
        return "M $x1 $y1 A $radius $radius 0 $large 1 $x2 $y2"
    }

    private fun escapeSvg(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Refreshes the background after a look-and-feel change. */
    override fun updateUI() {
        super.updateUI()
        background = Palette.BACKGROUND
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

        private var pressPosition: Int? = null
        private var dragged = false

        init {
            isOpaque = true
            background = Palette.BACKGROUND
            preferredSize = Dimension(380, 380)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        pressPosition = positionAt(e)
                        dragged = false
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (dragged) {
                        // The selection was already updated during the drag.
                        pressPosition = null
                        dragged = false
                    } else {
                        if (pressPosition != null) handleClick(e)
                        pressPosition = null
                    }
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val current = positionAt(e) ?: return
                    val start = pressPosition ?: return
                    if (abs(current - start) < 2) return
                    dragged = true
                    val seq = doc.seq
                    if (seq.isCircular && start != current) {
                        val shortStart: Int
                        val shortEnd: Int
                        if (start < current) {
                            val shortArc = current - start
                            val longArc = seq.length - shortArc
                            if (shortArc <= longArc) { shortStart = start; shortEnd = current }
                            else { shortStart = current; shortEnd = start }
                        } else {
                            val shortArc = start - current
                            val longArc = seq.length - shortArc
                            if (shortArc <= longArc) { shortStart = current; shortEnd = start }
                            else { shortStart = start; shortEnd = current }
                        }
                        onSelect?.invoke(shortStart, shortEnd)
                    } else {
                        onSelect?.invoke(minOf(start, current), maxOf(start, current))
                    }
                }
            })
        }

        override fun updateUI() {
            super.updateUI()
            background = Palette.BACKGROUND
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
        private fun assignLayers() {
            ringCount = packLanes(doc.seq.features, ringOf)
            laneCount = packLanes(doc.seq.features, laneOf)
        }


        private fun paintCircular(g2: Graphics2D) {
            val seq = doc.seq
            val gcPct = SeqOps.gcContent(seq)
            assignLayers()

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
            g2.font = labelFont
            val fm = g2.fontMetrics
            val labels = ArrayList<CircularLabel>(seq.features.size)
            seq.features.filter { it.visible }.forEachIndexed { index, f ->
                val ring = r - 24 - (ringOf[f] ?: 0) * 15
                val color = featureColor(f, index)
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

                val arcLength = ring * abs(extent) * PI / 180.0
                labels += CircularLabel(
                    featureLabel(f),
                    angleOf((f.start + f.end) / 2),
                    ring,
                    arcLength,
                )
            }

            // Restriction sites outside the backbone.
            val sites = if (showRestrictionSites.isSelected) doc.cutSites else emptyList()
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
            drawStringCentered(g2, "GC ${"%.1f".format(gcPct)}%", centerX, centerY + 28)

            // Draw labels last so restriction marks and the backbone cannot obscure them.
            if (showFeatureLabels.isSelected) drawCircularFeatureLabels(g2, labels, fm, gcPct)

            paintFeatureLegend(g2, seq)
        }

        /**
         * Draws every feature name. Labels that fit their arc remain inline;
         * the rest are placed in two callout columns inside the map.
         */
        private fun drawCircularFeatureLabels(
            g2: Graphics2D,
            labels: List<CircularLabel>,
            fm: java.awt.FontMetrics,
            gcPct: Double = SeqOps.gcContent(doc.seq),
        ) {
            val occupied = mutableListOf(circularCaptionBounds(g2, gcPct))
            val callouts = ArrayList<CircularLabel>()

            for (label in labels) {
                val baselineX = pointX(label.angle, label.ring - 20) - fm.stringWidth(label.text) / 2
                val baselineY = pointY(label.angle, label.ring - 20) + fm.ascent / 2
                val bounds = textBounds(fm, label.text, baselineX, baselineY)
                val fitsArc = label.arcLength > fm.stringWidth(label.text) + 10
                if (fitsArc && boundsInsideCanvas(bounds) && occupied.none(bounds::intersects)) {
                    drawLabelBox(g2, label.text, baselineX, baselineY, bounds)
                    occupied += bounds
                } else {
                    callouts += label
                }
            }

            val margin = 8
            val centreGap = 28
            val columnWidth = (width / 2 - centreGap - margin).coerceAtLeast(24)
            val sides = callouts.groupBy { cos(it.angle) < 0 }
            for ((onLeft, sideLabels) in sides) {
                val x = if (onLeft) margin else centerX + centreGap
                for (label in sideLabels.sortedBy { pointY(it.angle, it.ring) }) {
                    val text = fitText(fm, label.text, columnWidth)
                    val desiredBaseline = pointY(label.angle, label.ring) + fm.ascent / 2
                    val baseline = findFreeBaseline(fm, text, x, desiredBaseline, occupied)
                    val bounds = textBounds(fm, text, x, baseline)
                    val anchorX = pointX(label.angle, label.ring)
                    val anchorY = pointY(label.angle, label.ring)
                    val labelEdgeX = if (onLeft) bounds.x + bounds.width else bounds.x

                    g2.color = Palette.MUTED
                    g2.stroke = BasicStroke(1f)
                    g2.drawLine(anchorX, anchorY, labelEdgeX, bounds.y + bounds.height / 2)
                    drawLabelBox(g2, text, x, baseline, bounds)
                    occupied += bounds
                }
            }
        }

        private fun circularCaptionBounds(g2: Graphics2D, gcPct: Double = SeqOps.gcContent(doc.seq)): Rectangle {
            val seq = doc.seq
            g2.font = titleFont
            val titleWidth = g2.fontMetrics.stringWidth(seq.name)
            g2.font = subtitleFont
            val detailWidth = maxOf(
                g2.fontMetrics.stringWidth("${seq.length} bp ${seq.kind.name.lowercase()} circular"),
                g2.fontMetrics.stringWidth("GC ${"%.1f".format(gcPct)}%"),
            )
            g2.font = labelFont
            val captionWidth = maxOf(titleWidth, detailWidth) + 12
            return Rectangle(centerX - captionWidth / 2, centerY - 24, captionWidth, 58)
        }

        // --------------------------------------------------------------- linear



        private fun paintLinear(g2: Graphics2D) {
            val seq = doc.seq
            assignLayers()

            val left = 40
            val right = width - 40
            val span = (right - left).coerceAtLeast(1)
            val laneH = 16
            g2.font = labelFont
            val fm = g2.fontMetrics
            val calloutLabels = seq.features.filter { it.visible }.mapIndexedNotNull { index, feature ->
                val x1 = left + (feature.start.toDouble() / seq.length * span).roundToInt()
                val x2 = left + (feature.end.toDouble() / seq.length * span).roundToInt()
                val featureWidth = maxOf(4, x2 - x1)
                val text = featureLabel(feature)
                if (featureWidth > fm.stringWidth(text) + 8) {
                    null
                } else {
                    LinearLabel(text, x1 + featureWidth / 2, laneOf[feature] ?: 0, index)
                }
            }
            val placedLabels = placeLinearLabels(fm, calloutLabels, left, right)
            val labelRowHeight = fm.height + 5
            val calloutBottom = placedLabels.maxOfOrNull { 62 + it.row * labelRowHeight + fm.descent } ?: 48
            val featureStack = 24 + (laneCount - 1).coerceAtLeast(0) * laneH
            val desiredAxisY = maxOf(height / 2 + laneCount * laneH / 2, calloutBottom + featureStack + 10)
            val axisY = desiredAxisY.coerceAtMost((height - 60).coerceAtLeast(70))

            g2.color = Palette.GRID
            g2.stroke = BasicStroke(8f)
            g2.drawLine(left, axisY, right, axisY)

            // Current selection, highlighted as a band just above the backbone.
            if (doc.hasSelection) {
                val s = doc.selectionStart
                val e = doc.selectionEnd
                if (e > s) {
                    val x1 = left + (s.toDouble() / seq.length * span).roundToInt()
                    val x2 = left + (e.toDouble() / seq.length * span).roundToInt()
                    g2.color = Palette.translucent(Palette.SELECTION, 0x66)
                    g2.stroke = BasicStroke(5f)
                    g2.drawLine(x1, axisY - 8, maxOf(x1 + 2, x2), axisY - 8)
                }
            }

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

            seq.features.filter { it.visible }.forEachIndexed { index, f ->
                val lane = laneOf[f] ?: 0
                val x1 = left + (f.start.toDouble() / seq.length * span).roundToInt()
                val x2 = left + (f.end.toDouble() / seq.length * span).roundToInt()
                val y = axisY - 24 - lane * laneH
                val w = maxOf(4, x2 - x1)
                val color = featureColor(f, index)
                g2.color = Palette.translucent(color, 0x99)
                g2.fillRoundRect(x1, y, w, 12, 6, 6)
                g2.color = color
                g2.drawRoundRect(x1, y, w, 12, 6, 6)
                val text = featureLabel(f)
                if (w > fm.stringWidth(text) + 8) {
                    g2.color = Palette.TEXT
                    g2.drawString(text, x1, y - 2)
                }
            }

            if (showFeatureLabels.isSelected) drawLinearFeatureLabels(g2, placedLabels, axisY, laneH, labelRowHeight)

            if (showRestrictionSites.isSelected && doc.cutSites.isNotEmpty()) {
                g2.font = labelFont
                val fm = g2.fontMetrics
                val occupiedX = mutableListOf<IntRange>()
                var lastLane = 0
                val laneOccupied = mutableListOf(mutableListOf<IntRange>())
                for (site in doc.cutSites) {
                    val x = left + (site.topCut.toDouble() / seq.length * span).roundToInt()
                    g2.color = Palette.CUT_MARK
                    g2.drawLine(x, axisY - 8, x, axisY + 8)
                    val label = site.enzyme.name
                    val labelWidth = fm.stringWidth(label)
                    val interval = (x - 2)..(x + labelWidth + 2)
                    var lane = 0
                    while (lane < laneOccupied.size && laneOccupied[lane].any { rangesOverlap(it, interval) }) lane++
                    if (lane >= laneOccupied.size) laneOccupied.add(mutableListOf())
                    laneOccupied[lane].add(interval)
                    g2.drawString(label, x + 2, axisY + 44 + lane * (fm.height + 2))
                }
            }

            g2.color = Palette.TEXT
            g2.font = titleFont
            drawStringCentered(g2, seq.name, width / 2, 26)
            g2.font = subtitleFont
            g2.color = Palette.MUTED
            drawStringCentered(g2, "${seq.length} bp ${seq.kind.name.lowercase()} linear", width / 2, 44)

            paintFeatureLegend(g2, seq)
        }

        // ---------------------------------------------------------------- helpers

        private fun paintFeatureLegend(g2: Graphics2D, seq: org.instagene.core.Seq) {
            val features = seq.features.filter { it.visible }
            if (features.isEmpty()) return
            g2.font = labelFont
            val fm = g2.fontMetrics
            val legendX = 8
            var legendY = height - 8
            for (f in features.reversed()) {
                val color = featureColor(f, seq.features.indexOf(f))
                val label = featureLabel(f)
                val boxSize = 8
                legendY -= fm.height + 2
                g2.color = color
                g2.fillRect(legendX, legendY - boxSize + 2, boxSize, boxSize)
                g2.color = Palette.TEXT
                g2.drawString(label, legendX + boxSize + 4, legendY)
            }
        }

        private fun featureLabel(feature: Feature): String =
            feature.name.trim().ifEmpty { feature.type.trim().ifEmpty { "feature" } }

        /** Packs narrow-feature callouts into rows whose text bounds do not overlap. */
        private fun placeLinearLabels(
            fm: java.awt.FontMetrics,
            labels: List<LinearLabel>,
            left: Int,
            right: Int,
        ): List<PlacedLinearLabel> {
            val rows = ArrayList<MutableList<IntRange>>()
            val placed = ArrayList<PlacedLinearLabel>(labels.size)
            val maxWidth = (right - left).coerceAtLeast(16)
            for (original in labels.sortedBy { it.anchorX }) {
                val text = fitText(fm, original.text, maxWidth)
                val label = original.copy(text = text)
                val textWidth = fm.stringWidth(text)
                val x = (label.anchorX - textWidth / 2).coerceIn(left, (right - textWidth).coerceAtLeast(left))
                val interval = (x - 3)..(x + textWidth + 3)
                var row = rows.indexOfFirst { occupied -> occupied.none { rangesOverlap(it, interval) } }
                if (row < 0) {
                    row = rows.size
                    rows.add(mutableListOf())
                }
                rows[row] += interval
                placed += PlacedLinearLabel(label, x, row)
            }
            return placed
        }

        private fun drawLinearFeatureLabels(
            g2: Graphics2D,
            labels: List<PlacedLinearLabel>,
            axisY: Int,
            laneHeight: Int,
            rowHeight: Int,
        ) {
            g2.font = labelFont
            val fm = g2.fontMetrics
            for (placed in labels) {
                val label = placed.label
                val baseline = 62 + placed.row * rowHeight
                val bounds = textBounds(fm, label.text, placed.x, baseline)
                val featureY = axisY - 18 - label.lane * laneHeight
                g2.color = Palette.featureColor(label.colorIndex)
                g2.stroke = BasicStroke(1f)
                g2.drawLine(label.anchorX, bounds.y + bounds.height, label.anchorX, featureY)
                drawLabelBox(g2, label.text, placed.x, baseline, bounds)
            }
        }

        private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
            first.first <= second.last && second.first <= first.last

        private fun fitText(fm: java.awt.FontMetrics, text: String, maxWidth: Int): String {
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "…"
            var end = text.length
            while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) end--
            return if (end == 0) ellipsis else text.substring(0, end) + ellipsis
        }

        private fun findFreeBaseline(
            fm: java.awt.FontMetrics,
            text: String,
            x: Int,
            desired: Int,
            occupied: List<Rectangle>,
        ): Int {
            val minimum = fm.ascent + 5
            val maximum = (height - fm.descent - 5).coerceAtLeast(minimum)
            val initial = desired.coerceIn(minimum, maximum)
            val step = fm.height + 5
            val limit = height / step + 2
            for (distance in 0..limit) {
                val candidates = if (distance == 0) intArrayOf(initial) else intArrayOf(initial + distance * step, initial - distance * step)
                for (baseline in candidates) {
                    if (baseline !in minimum..maximum) continue
                    val bounds = textBounds(fm, text, x, baseline)
                    if (occupied.none(bounds::intersects)) return baseline
                }
            }
            return initial
        }

        private fun textBounds(
            fm: java.awt.FontMetrics,
            text: String,
            x: Int,
            baseline: Int,
        ): Rectangle = Rectangle(x - 3, baseline - fm.ascent - 2, fm.stringWidth(text) + 6, fm.height + 4)

        private fun boundsInsideCanvas(bounds: Rectangle): Boolean =
            bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= width && bounds.y + bounds.height <= height

        private fun drawLabelBox(g2: Graphics2D, text: String, x: Int, baseline: Int, bounds: Rectangle) {
            g2.color = Palette.translucent(Palette.BACKGROUND, 0xE8)
            g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5)
            g2.color = Palette.TEXT
            g2.font = labelFont
            g2.drawString(text, x, baseline)
        }

        private fun tickStep(length: Int): Int {
            val rough = length / 8.0
            val magnitude = 10.0.pow(floor(log10(rough.coerceAtLeast(1.0))))
            val step = (ceil(rough / magnitude) * magnitude).toInt()
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

        /**
         * Sequence position under the pointer, or null when the sequence is
         * empty or the point lies outside the map (the ring on circular maps).
         */
        private fun positionAt(e: MouseEvent): Int? {
            val seq = doc.seq
            if (seq.length == 0) return null
            if (seq.isCircular) {
                val dx = (e.x - centerX).toDouble()
                val dy = (centerY - e.y).toDouble()
                val dist = sqrt(dx * dx + dy * dy)
                val outer = backboneRadius + 18.0
                val inner = (backboneRadius - 24 - ringCount * 15 - 8).coerceAtLeast(0)
                if (dist > outer || dist < inner) return null
                var angle = PI / 2 - atan2(dy, dx)
                if (angle < 0) angle += 2 * PI
                return (angle / (2 * PI) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
            }
            val left = 40
            val span = (width - 80).coerceAtLeast(1)
            return (((e.x - left).toDouble() / span) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
        }

        /** Maps a click to a feature or base position; clicks outside the map do nothing. */
        private fun handleClick(e: MouseEvent) {
            val seq = doc.seq
            if (seq.length == 0) return
            val position = positionAt(e) ?: return
            val feature = seq.features.firstOrNull { position in it.start until it.end }
            if (feature != null) {
                onSelect?.invoke(feature.start, feature.end)
            } else {
                onSelect?.invoke(position, position + 1)
            }
        }
    }
}
