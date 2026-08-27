@file:Suppress("DuplicatedCode")

package org.instagene.app.gui.tool

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.theme.Palette
import org.instagene.core.Feature
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.core.Strand
import org.instagene.core.Topology
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
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
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.Timer
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
    @Suppress("unused")
    PRESENTATION(1600, 1200),
    PAPER(1200, 900),
    NOTEBOOK(900, 700),
}

data class MapExportOptions(
    val preset: MapPreset = MapPreset.PAPER,
    val title: String? = null,
    val showFeatureLabels: Boolean = true,
    val showRestrictionSites: Boolean = true,
    val featureLaneSpacing: Int = 12,
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
    val featureLabelMode = JComboBox(FeatureLabelMode.entries.toTypedArray()).apply {
        toolTipText = "Choose which annotations receive labels"
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(
                list, (value as? FeatureLabelMode)?.displayName ?: value, index, isSelected, cellHasFocus,
            )
        }
    }

    private data class CircularLabel(
        val text: String,
        val angle: Double,
        val ring: Int,
        val arcLength: Double,
        val colorIndex: Int,
        val feature: Feature,
    )

    private data class LinearLabel(
        val text: String,
        val anchorX: Int,
        val lane: Int,
        val colorIndex: Int,
        val feature: Feature,
    )

    private data class PlacedLinearLabel(
        val label: LinearLabel,
        val x: Int,
        val row: Int,
    )

    private data class LabelHitRegion(
        val bounds: Rectangle,
        val start: Int,
        val end: Int,
        val feature: Feature? = null,
    )

    private data class ArcHitRegion(
        val feature: Feature,
        val ring: Int,
    )

    private data class FeatureLabelKey(
        val text: String,
        val type: String,
        val start: Int,
        val end: Int,
        val strand: Strand,
    )

    private data class LabelVisualState(
        var x: Double,
        var baseline: Double,
        var alpha: Float,
        var targetX: Int,
        var targetBaseline: Int,
        var targetAlpha: Float,
        var lastSeen: Int,
    )

    private data class StaticMapCacheKey(
        val logicalWidth: Int,
        val logicalHeight: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val zoomPercent: Int,
        val featureLabelMode: FeatureLabelMode,
        val showRestrictionSites: Boolean,
        val cutSiteIdentity: Int,
        val cutSiteCount: Int,
    )

    private var baseCanvasWidth = 380
    private var baseCanvasHeight = 380
    private val mapCanvas = MapCanvas()
    private var renderingExport = false
    private val mapScrollPane = JScrollPane(mapCanvas).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        viewport.scrollMode = JViewport.BLIT_SCROLL_MODE
    }
    private val animationTimer = Timer(16) { mapCanvas.advanceAnimationFrame() }.apply {
        isRepeats = true
    }
    private var viewportAnimationTarget: java.awt.Point? = null
    private var exportFeatureLaneSpacing: Int? = null
    private var zoomFactor = 1.0
    private val zoomValues = intArrayOf(50, 75, 100, 125, 150, 200, 300, 400, 600)
    private val zoomLabel = JLabel("100%").apply {
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        preferredSize = Dimension(48, preferredSize.height)
        toolTipText = "Current map zoom"
    }
    private val zoomControlsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
    }

    /** Interactive map zoom preset in percent, constrained to 50%..600%. */
    val zoomPercent: Int get() = (zoomFactor * 100.0).roundToInt()

    init {
        background = Palette.BACKGROUND
        isOpaque = true
        border = BorderFactory.createEmptyBorder(6, 8, 8, 8)

        mapCanvas.onSelect = { start, end -> onSelect?.invoke(start, end) }
        mapScrollPane.viewport.addChangeListener { mapCanvas.viewportChanged() }

        circularCheckbox.addActionListener {
            val target = if (circularCheckbox.isSelected) Topology.CIRCULAR else Topology.LINEAR
            doc.mutate(if (target == Topology.CIRCULAR) "make circular" else "make linear") {
                it.withTopology(target)
            }
        }
        val initialListener = SeqDocument.Listener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) mapCanvas.clearLabelVisuals()
            if (reason == SeqDocument.Reason.SEQUENCE || reason == SeqDocument.Reason.ENZYMES) mapCanvas.invalidateStaticMapCache()
            mapCanvas.repaint()
            if (reason == SeqDocument.Reason.SEQUENCE) syncTopologyControl()
        }
        docListener = initialListener
        doc.addListener(initialListener)
        syncTopologyControl()

        val featureControls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(circularCheckbox)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(showFeatureLabels)
                add(featureLabelMode)
                add(showRestrictionSites)
            })
        }
        zoomControlsPanel.add(JLabel("Zoom"))
        zoomControlsPanel.add(JButton("−").apply {
            toolTipText = "Zoom out"
            addActionListener { setZoomPercent(neighborZoom(-1)) }
        })
        zoomControlsPanel.add(zoomLabel)
        zoomControlsPanel.add(JButton("+").apply {
            toolTipText = "Zoom in"
            addActionListener { setZoomPercent(neighborZoom(1)) }
        })
        zoomControlsPanel.add(JButton("Reset").apply {
            toolTipText = "Reset map zoom"
            addActionListener { setZoomPercent(100) }
        })
        val exportControls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(JButton("Export PNG").apply {
                addActionListener {
                    val chooser = JFileChooser().apply { dialogTitle = "Export map as PNG" }
                    if (chooser.showSaveDialog(this@PlasmidMapPanel) == JFileChooser.APPROVE_OPTION) exportPng(chooser.selectedFile)
                }
            })
            add(JButton("Export SVG").apply {
                addActionListener {
                    val chooser = JFileChooser().apply { dialogTitle = "Export map as SVG" }
                    if (chooser.showSaveDialog(this@PlasmidMapPanel) == JFileChooser.APPROVE_OPTION) exportSvg(chooser.selectedFile)
                }
            })
        }
        add(JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(featureControls, BorderLayout.WEST)
            add(zoomControlsPanel, BorderLayout.CENTER)
            add(exportControls, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        showFeatureLabels.addActionListener { mapCanvas.clearLabelVisuals(); mapCanvas.repaint() }
        featureLabelMode.addActionListener { mapCanvas.clearLabelVisuals(); mapCanvas.invalidateStaticMapCache(); mapCanvas.repaint() }
        showRestrictionSites.addActionListener { mapCanvas.invalidateStaticMapCache(); mapCanvas.repaint() }
        add(mapScrollPane, BorderLayout.CENTER)
    }

    override fun doLayout() {
        super.doLayout()
        layoutMapViewport()
    }

    /** Sets interactive map zoom and preserves the same logical map center. */
    fun setZoomPercent(value: Int) {
        viewportAnimationTarget = null
        animationTimer.stop()
        val old = zoomFactor
        val targetPercent = zoomValues.minWithOrNull(
            compareBy<Int> { abs(it - value.coerceIn(zoomValues.first(), zoomValues.last())) }
                .thenByDescending { it },
        ) ?: 100
        val target = targetPercent / 100.0
        val viewport = mapScrollPane.viewport
        val oldExtent = viewport.extentSize
        val oldPosition = viewport.viewPosition
        val oldOffsetX = canvasOffsetX(mapCanvas.width, old)
        val oldOffsetY = canvasOffsetY(mapCanvas.height, old)
        val logicalCenterX = (oldPosition.x + oldExtent.width / 2.0 - oldOffsetX) / old
        val logicalCenterY = (oldPosition.y + oldExtent.height / 2.0 - oldOffsetY) / old
        zoomFactor = target
        zoomLabel.text = "$targetPercent%"
        layoutMapViewport()
        mapCanvas.invalidateStaticMapCache()
        val newExtent = viewport.extentSize
        val newOffsetX = canvasOffsetX(mapCanvas.width, target)
        val newOffsetY = canvasOffsetY(mapCanvas.height, target)
        val point = java.awt.Point(
            (logicalCenterX * target + newOffsetX - newExtent.width / 2.0).roundToInt(),
            (logicalCenterY * target + newOffsetY - newExtent.height / 2.0).roundToInt(),
        )
        val maxX = (mapCanvas.width - newExtent.width).coerceAtLeast(0)
        val maxY = (mapCanvas.height - newExtent.height).coerceAtLeast(0)
        viewport.viewPosition = java.awt.Point(point.x.coerceIn(0, maxX), point.y.coerceIn(0, maxY))
        mapCanvas.repaint()
    }

    private fun layoutMapViewport() {
        mapScrollPane.revalidate()
        mapScrollPane.doLayout()
        mapScrollPane.viewport.doLayout()
        updateCanvasBounds()
        if (zoomFactor == 1.0) {
            // A fitted 100% map can change the viewport extent when a stale
            // scrollbar disappears, so perform one final fit with the new extent.
            mapScrollPane.doLayout()
            mapScrollPane.viewport.doLayout()
            updateCanvasBounds()
        }
    }

    private fun updateCanvasBounds() {
        val extent = mapScrollPane.viewport.extentSize
        if (zoomFactor == 1.0) {
            baseCanvasWidth = maxOf(380, extent.width)
            baseCanvasHeight = maxOf(380, extent.height)
        }
        val preferred = mapCanvas.preferredSize
        mapCanvas.setSize(maxOf(extent.width, preferred.width), maxOf(extent.height, preferred.height))
        mapScrollPane.viewport.revalidate()
    }

    private fun canvasOffsetX(physicalWidth: Int, scale: Double): Int =
        ((physicalWidth - (baseCanvasWidth * scale).roundToInt()).coerceAtLeast(0) / 2.0).roundToInt()

    private fun canvasOffsetY(physicalHeight: Int, scale: Double): Int =
        ((physicalHeight - (baseCanvasHeight * scale).roundToInt()).coerceAtLeast(0) / 2.0).roundToInt()

    private fun neighborZoom(direction: Int): Int {
        val current = zoomPercent
        return if (direction > 0) {
            zoomValues.firstOrNull { it > current } ?: zoomValues.last()
        } else {
            zoomValues.lastOrNull { it < current } ?: zoomValues.first()
        }
    }

    /** Exposed for headless GUI tests and callers that need the painted canvas. */
    fun canvasForTest(): JPanel = mapCanvas

    /** Current viewport position in zoomed canvas pixels. */
    fun viewportPositionForTest(): java.awt.Point = java.awt.Point(mapScrollPane.viewport.viewPosition)

    /** Current viewport extent in pixels. */
    fun viewportExtentForTest(): Dimension = Dimension(mapScrollPane.viewport.extentSize)

    /** Current user-facing zoom text. */
    fun zoomLabelTextForTest(): String = zoomLabel.text

    /** Advances viewport motion once for deterministic GUI regression tests. */
    fun advanceViewportAnimationForTest() = mapCanvas.advanceViewportAnimation()

    /** Exposed for GUI regression tests: the zoom controls contain buttons and a read-only indicator. */
    fun zoomControlsForTest(): JPanel = zoomControlsPanel

    /** Exposed for GUI regression tests: static map cache rebuild count. */
    fun staticMapRenderCountForTest(): Int = mapCanvas.staticMapRenderCountForTest()

    /** Exposed for GUI regression tests: warms and returns the static map cache size in screen pixels. */
    fun ensureStaticMapImageSizeForTest(): Dimension = mapCanvas.ensureStaticMapImageSizeForTest()

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
            docListener?.let { doc.addListener(it) }
        }
        if (docListener == null) {
            val listener = SeqDocument.Listener { _, reason ->
                mapCanvas.clearLabelVisuals()
                if (reason == SeqDocument.Reason.SEQUENCE || reason == SeqDocument.Reason.ENZYMES) mapCanvas.invalidateStaticMapCache()
                mapCanvas.repaint()
                if (reason == SeqDocument.Reason.SEQUENCE) syncTopologyControl()
            }
            docListener = listener
            doc.addListener(listener)
        }
        syncTopologyControl()
        mapCanvas.clearLabelVisuals()
        mapCanvas.invalidateStaticMapCache()
        mapCanvas.repaint()
    }

    /** Renders the current map to a PNG without requiring a visible window. */
    fun exportPng(file: File, width: Int = 1200, height: Int = 900) {
        require(width > 0 && height > 0) { "Map dimensions must be positive" }
        val previousZoom = zoomFactor
        val previousSize = Dimension(mapCanvas.size)
        val previousBaseWidth = baseCanvasWidth
        val previousBaseHeight = baseCanvasHeight
        try {
            // Export coordinates are independent from the interactive viewport.
            zoomFactor = 1.0
            baseCanvasWidth = width
            baseCanvasHeight = height
            mapCanvas.setSize(width, height)
            mapCanvas.doLayout()
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                renderingExport = true
                mapCanvas.paint(graphics)
            } finally {
                renderingExport = false
                graphics.dispose()
            }
            ImageIO.write(image, "png", file)
        } finally {
            zoomFactor = previousZoom
            baseCanvasWidth = previousBaseWidth
            baseCanvasHeight = previousBaseHeight
            mapCanvas.setSize(previousSize)
            mapCanvas.revalidate()
            updateCanvasBounds()
            mapCanvas.repaint()
        }
    }

    /** Renders a PNG using a named researcher-facing preset. */
    @Suppress("unused")
    fun exportPng(file: File, options: MapExportOptions) {
        require(options.featureLaneSpacing > 0) { "Feature lane spacing must be positive" }
        val previousZoom = zoomFactor
        val previousLabels = showFeatureLabels.isSelected
        val previousSites = showRestrictionSites.isSelected
        val previousLaneSpacing = exportFeatureLaneSpacing
        try {
            showFeatureLabels.isSelected = options.showFeatureLabels
            showRestrictionSites.isSelected = options.showRestrictionSites
            exportFeatureLaneSpacing = options.featureLaneSpacing
            zoomFactor = 1.0
            exportPng(file, options.preset.width, options.preset.height)
        } finally {
            showFeatureLabels.isSelected = previousLabels
            showRestrictionSites.isSelected = previousSites
            exportFeatureLaneSpacing = previousLaneSpacing
            zoomFactor = previousZoom
        }
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
        require(options.featureLaneSpacing > 0) { "Feature lane spacing must be positive" }
        val seq = doc.seq
        val cx = width / 2
        val cy = height / 2
        val radius = min(width, height) / 3
        val features = seq.features.filter { FeatureLabelOptions.include(it, featureLabelMode.selectedItem as? FeatureLabelMode ?: FeatureLabelMode.ALL) }
        val laneOf = HashMap<Feature, Int>()
        packLanes(features, laneOf)
        val featureSvg = features.mapIndexed { index, feature ->
            val start = feature.start.toDouble() / seq.length * 360.0 - 90.0
            val end = feature.end.toDouble() / seq.length * 360.0 - 90.0
            val color = svgColor(feature, index)
            val lane = laneOf[feature] ?: 0
            val path = svgArc(cx, cy, radius + lane * options.featureLaneSpacing, start, end)
            """
              <path d="$path" fill="none" stroke="#ffffff" stroke-width="14" stroke-linecap="round"/>
              <path d="$path" fill="none" stroke="$color" stroke-width="10" stroke-linecap="round"/>
            """.trimIndent()
        }.joinToString("\n")
        val labelSvg = if (options.showFeatureLabels) svgFeatureLabels(features, laneOf, cx, cy, radius, width, height, options.featureLaneSpacing) else ""
        val siteSvg = if (options.showRestrictionSites) doc.cutSites.joinToString("\n") { (enzyme, recognitionStart) ->
            val angle = Math.toRadians(recognitionStart.toDouble() / seq.length * 360.0 - 90.0)
            val x = cx + cos(angle) * (radius + 22)
            val y = cy + sin(angle) * (radius + 22)
            val label = escapeSvg(enzyme.name)
            val labelWidth = label.length * 6 + 8
            "<circle cx=\"$x\" cy=\"$y\" r=\"3\" fill=\"#8a4baf\"/><rect x=\"${x + 5}\" y=\"${y - 11}\" width=\"$labelWidth\" height=\"14\" rx=\"4\" fill=\"#ffffff\" stroke=\"#c6cfd9\"/><text x=\"${x + 9}\" y=\"$y\" font-size=\"10\">$label</text>"
        } else ""
        val title = options.title?.takeIf { it.isNotBlank() } ?: "${seq.name} (${seq.length} bp)"
        file.writeText("""
            <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
              <rect width="100%" height="100%" fill="#ffffff"/>
              <circle cx="$cx" cy="$cy" r="$radius" fill="none" stroke="#d8dee6" stroke-width="10"/>
              <circle cx="$cx" cy="$cy" r="$radius" fill="none" stroke="#f7f9fb" stroke-width="2"/>
              <text x="$cx" y="${cy + 5}" text-anchor="middle" font-size="16">${escapeSvg(title)}</text>
              $featureSvg
              $labelSvg
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

    /**
     * Places exported feature callouts in independent left/right columns. This
     * mirrors the map canvas's callout behavior while keeping SVG text editable
     * and prevents crowded constructs from producing overlapping labels.
     */
    private fun svgFeatureLabels(
        features: List<Feature>,
        laneOf: Map<Feature, Int>,
        cx: Int,
        cy: Int,
        radius: Int,
        width: Int,
        height: Int,
        laneSpacing: Int,
    ): String {
        data class Callout(val feature: Feature, val lane: Int, val angle: Double, val anchorX: Double, val anchorY: Double)
        val callouts = features.map { feature ->
            val lane = laneOf[feature] ?: 0
            val angle = Math.toRadians((feature.start + feature.end) / 2.0 / doc.seq.length * 360.0 - 90.0)
            val ring = radius + lane * laneSpacing
            Callout(feature, lane, angle, cx + cos(angle) * ring, cy + sin(angle) * ring)
        }
        return callouts.groupBy { cos(it.angle) < 0 }.flatMap { (left, side) ->
            val sorted = side.sortedBy { it.anchorY }
            val minY = 20.0
            val maxY = (height - 14).toDouble()
            val spacing = 15.0
            val requested = sorted.map { it.anchorY.coerceIn(minY, maxY) }.toMutableList()
            for (index in 1 until requested.size) requested[index] = maxOf(requested[index], requested[index - 1] + spacing)
            if ((requested.lastOrNull() ?: minY) > maxY) {
                sorted.indices.forEach { index -> requested[index] = minY + (maxY - minY) * (index + 1) / (sorted.size + 1) }
            }
            sorted.indices.map { index ->
                val callout = sorted[index]
                val text = escapeSvg(callout.feature.name)
                val x = if (left) 12 else width - 12
                val anchor = if (left) "end" else "start"
                "<path d=\"M ${callout.anchorX} ${callout.anchorY} L $x ${requested[index] - 4}\" fill=\"none\" stroke=\"#69727d\" stroke-width=\"1\"/><text x=\"$x\" y=\"${requested[index]}\" text-anchor=\"$anchor\" font-size=\"12\">$text</text>"
            }
        }.joinToString("\n")
    }

    private fun svgColor(feature: Feature, index: Int): String = feature.color ?: "#%06x".format(featureColor(feature, index).rgb and 0xffffff)

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

    /** Exposed for GUI regression tests: center of the latest painted feature label target. */
    fun featureLabelHitCenterForTest(name: String): Pair<Int, Int>? =
        mapCanvas.featureLabelHitCenterForTest(name)

    /** Exposed for GUI regression tests: center of the latest painted feature arc target. */
    fun featureArcHitCenterForTest(name: String): Pair<Int, Int>? =
        mapCanvas.featureArcHitCenterForTest(name)

    /** Exposed for GUI regression tests: latest painted feature label boxes. */
    fun featureLabelBoundsForTest(): List<Rectangle> =
        mapCanvas.featureLabelBoundsForTest()

    /** Exposed for GUI regression tests: latest painted feature label box for [name]. */
    fun featureLabelBoundsForTest(name: String): Rectangle? =
        mapCanvas.featureLabelBoundsForTest(name)

    /** Exposed for GUI regression tests: latest painted feature-label opacity by feature name. */
    fun featureLabelAlphasForTest(): Map<String, Float> =
        mapCanvas.featureLabelAlphasForTest()

    /** Exposed for GUI regression tests: moves the scroll viewport in canvas coordinates. */
    fun setViewportPositionForTest(x: Int, y: Int) {
        val extent = mapScrollPane.viewport.extentSize
        val maxX = (mapCanvas.width - extent.width).coerceAtLeast(0)
        val maxY = (mapCanvas.height - extent.height).coerceAtLeast(0)
        mapScrollPane.viewport.viewPosition = java.awt.Point(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
        mapCanvas.viewportChanged()
    }

    private inner class MapCanvas : JPanel() {

        var onSelect: ((Int, Int) -> Unit)? = null

        private val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 15)
        private val subtitleFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        private val labelFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)

        private var centerX = 0
        private var centerY = 0
        private var backboneRadius = 0
        private var linearAxisY = 0
        private val ringOf = HashMap<Feature, Int>()
        private var ringCount = 0
        private val laneOf = HashMap<Feature, Int>()
        private var laneCount = 0
        private var layeredSequence: Any? = null
        private var gcSequence: Any? = null
        private var cachedGcPercent = 0.0
        private var visibleFeaturesSequence: Any? = null
        private var visibleFeaturesMode: FeatureLabelMode? = null
        private var cachedVisibleFeatures: List<Feature> = emptyList()
        private val labelHitRegions = ArrayList<LabelHitRegion>()
        private val arcHitRegions = ArrayList<ArcHitRegion>()
        private val featureLabelHitRegions = LinkedHashMap<String, Rectangle>()
        private val featureLabelVisualBounds = LinkedHashMap<String, Rectangle>()
        private val featureLabelAlphas = LinkedHashMap<String, Float>()
        private val labelVisuals = LinkedHashMap<FeatureLabelKey, LabelVisualState>()
        private var labelPaintGeneration = 0
        private var staticMapImage: BufferedImage? = null
        private var staticMapCacheKey: StaticMapCacheKey? = null
        private var staticMapSequence: Any? = null
        private var staticMapCutSites: Any? = null
        private val staticLabelHitRegions = ArrayList<LabelHitRegion>()
        private val staticArcHitRegions = ArrayList<ArcHitRegion>()
        private var staticMapRenderCount = 0
        private var renderingStaticLayer = false
        private var renderingDynamicOverlay = false

        private var pressPosition: Int? = null
        private var dragged = false
        private var applyingViewportAnimation = false
        private var lastAnimationNanos = System.nanoTime()

        private val logicalWidth: Int get() = baseCanvasWidth.coerceAtLeast(1)
        private val logicalHeight: Int get() = baseCanvasHeight.coerceAtLeast(1)

        init {
            isOpaque = true
            background = Palette.BACKGROUND
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
                        handleClick(e)
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
            addMouseWheelListener { e ->
                if (zoomFactor <= 1.0) return@addMouseWheelListener
                val viewport = mapScrollPane.viewport
                val extent = viewport.extentSize
                val step = (if (e.isShiftDown) extent.width else extent.height).coerceAtLeast(32) / 3
                val distance = e.wheelRotation * step
                val current = viewport.viewPosition
                val target = if (e.isShiftDown) {
                    java.awt.Point(current.x + distance, current.y)
                } else {
                    java.awt.Point(current.x, current.y + distance)
                }
                animateViewportTo(target)
                e.consume()
            }
        }

        override fun updateUI() {
            super.updateUI()
            background = Palette.BACKGROUND
        }

        override fun getPreferredSize(): Dimension = Dimension(
            (baseCanvasWidth * zoomFactor).roundToInt().coerceAtLeast(1),
            (baseCanvasHeight * zoomFactor).roundToInt().coerceAtLeast(1),
        )

        fun viewportChanged() {
            if (!applyingViewportAnimation && viewportAnimationTarget != null) {
                viewportAnimationTarget = null
                animationTimer.stop()
            }
            if (shouldAnimateLabels() && labelVisuals.isNotEmpty()) animationTimer.start()
            repaintVisibleMap()
        }

        private fun animateViewportTo(requested: java.awt.Point) {
            val viewport = mapScrollPane.viewport
            val extent = viewport.extentSize
            val maxX = (mapCanvas.width - extent.width).coerceAtLeast(0)
            val maxY = (mapCanvas.height - extent.height).coerceAtLeast(0)
            viewportAnimationTarget = java.awt.Point(
                requested.x.coerceIn(0, maxX),
                requested.y.coerceIn(0, maxY),
            )
            lastAnimationNanos = System.nanoTime()
            animationTimer.start()
        }

        fun advanceAnimationFrame() {
            val now = System.nanoTime()
            val elapsedSeconds = ((now - lastAnimationNanos) / 1_000_000_000.0).coerceIn(0.001, 0.05)
            lastAnimationNanos = now
            val viewportMoving = advanceViewportAnimation(elapsedSeconds)
            val labelsMoving = advanceLabelAnimation(elapsedSeconds)
            if (viewportMoving || labelsMoving) repaintVisibleMap() else animationTimer.stop()
        }

        private fun repaintVisibleMap() {
            val view = mapScrollPane.viewport.viewRect
            if (view.width <= 0 || view.height <= 0) {
                repaint()
                return
            }
            val margin = 128
            repaint(
                (view.x - margin).coerceAtLeast(0),
                (view.y - margin).coerceAtLeast(0),
                (view.width + margin * 2).coerceAtMost(width),
                (view.height + margin * 2).coerceAtMost(height),
            )
        }

        fun advanceViewportAnimation() {
            lastAnimationNanos = System.nanoTime()
            advanceViewportAnimation(0.016)
        }

        private fun advanceViewportAnimation(elapsedSeconds: Double): Boolean {
            val target = viewportAnimationTarget ?: run {
                return false
            }
            val viewport = mapScrollPane.viewport
            val current = viewport.viewPosition
            val factor = (1.0 - kotlin.math.exp(-elapsedSeconds / 0.12)).coerceIn(0.0, 1.0)
            val nextX = current.x + ((target.x - current.x) * factor).roundToInt()
            val nextY = current.y + ((target.y - current.y) * factor).roundToInt()
            val next = java.awt.Point(nextX, nextY)
            applyingViewportAnimation = true
            viewport.viewPosition = next
            applyingViewportAnimation = false
            if (next == target) {
                viewportAnimationTarget = null
                return false
            }
            return true
        }

        fun clearLabelVisuals() {
            animationTimer.stop()
            labelVisuals.clear()
            featureLabelVisualBounds.clear()
            featureLabelAlphas.clear()
        }

        fun invalidateStaticMapCache() {
            staticMapImage = null
            staticMapCacheKey = null
            staticMapSequence = null
            staticMapCutSites = null
            staticLabelHitRegions.clear()
            staticArcHitRegions.clear()
        }

        fun staticMapRenderCountForTest(): Int = staticMapRenderCount

        fun ensureStaticMapImageSizeForTest(): Dimension {
            val image = staticMapImage()
            return Dimension(image.width, image.height)
        }

        fun advanceLabelAnimation() {
            if (!advanceLabelAnimation(0.016)) animationTimer.stop()
        }

        private fun advanceLabelAnimation(elapsedSeconds: Double): Boolean {
            if (labelVisuals.isEmpty()) {
                return false
            }
            var moving = false
            val factor = (1.0 - kotlin.math.exp(-elapsedSeconds / 0.10)).toFloat().coerceIn(0f, 1f)
            for (visual in labelVisuals.values) {
                val nextX = visual.x + (visual.targetX - visual.x) * factor
                val nextBaseline = visual.baseline + (visual.targetBaseline - visual.baseline) * factor
                val nextAlpha = visual.alpha + (visual.targetAlpha - visual.alpha) * factor
                moving = moving ||
                    abs(nextX - visual.targetX) > 0.5 ||
                    abs(nextBaseline - visual.targetBaseline) > 0.5 ||
                    abs(nextAlpha - visual.targetAlpha) > 0.02f
                visual.x = if (abs(nextX - visual.targetX) <= 0.5) visual.targetX.toDouble() else nextX
                visual.baseline = if (abs(nextBaseline - visual.targetBaseline) <= 0.5) visual.targetBaseline.toDouble() else nextBaseline
                visual.alpha = if (abs(nextAlpha - visual.targetAlpha) <= 0.02f) visual.targetAlpha else nextAlpha
            }
            return moving
        }

        private fun shouldAnimateLabels(): Boolean =
            isShowing && !renderingExport && !GraphicsEnvironment.isHeadless()

        private fun labelViewport(): Rectangle {
            if (renderingExport || zoomFactor == 1.0) return Rectangle(0, 0, logicalWidth, logicalHeight)
            val view = mapScrollPane.viewport.viewRect
            if (view.width <= 0 || view.height <= 0) return Rectangle(0, 0, logicalWidth, logicalHeight)
            val offsetX = canvasOffsetX(width, zoomFactor)
            val offsetY = canvasOffsetY(height, zoomFactor)
            val left = floor((view.x - offsetX) / zoomFactor).toInt().coerceIn(0, logicalWidth - 1)
            val top = floor((view.y - offsetY) / zoomFactor).toInt().coerceIn(0, logicalHeight - 1)
            val right = ceil((view.x + view.width - offsetX) / zoomFactor).toInt().coerceIn(left + 1, logicalWidth)
            val bottom = ceil((view.y + view.height - offsetY) / zoomFactor).toInt().coerceIn(top + 1, logicalHeight)
            return Rectangle(left, top, right - left, bottom - top)
        }

        private fun edgeFadeAlpha(bounds: Rectangle, viewport: Rectangle): Float {
            if (renderingExport || zoomFactor == 1.0) return 1f
            if (!bounds.intersects(viewport)) return 0f
            val fadeBand = (64.0 / zoomFactor).roundToInt().coerceIn(12, 96)
            val distance = minOf(
                bounds.x + bounds.width - viewport.x,
                viewport.x + viewport.width - bounds.x,
                bounds.y + bounds.height - viewport.y,
                viewport.y + viewport.height - bounds.y,
            ).coerceAtLeast(0)
            return (0.22 + 0.78 * (distance.toDouble() / fadeBand).coerceIn(0.0, 1.0)).toFloat()
        }

        private fun pruneLabelVisuals() {
            labelVisuals.entries.removeIf { it.value.lastSeen != labelPaintGeneration && it.value.alpha <= 0.02f }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val seq = doc.seq
            if (seq.length == 0) {
                clearLabelVisuals()
                clearInteractiveHitRegions()
                g2.color = Palette.MUTED
                g2.font = subtitleFont
                g2.drawString("Nothing to map yet.", 16, 28)
                return
            }

            centerX = logicalWidth / 2
            centerY = logicalHeight / 2

            if (renderingExport) {
                applyMapTransform(g2)
                clearInteractiveHitRegions()
                labelPaintGeneration++
                if (seq.isCircular) paintCircular(g2) else paintLinear(g2)
            } else {
                val staticMap = staticMapImage()
                restoreStaticHitRegions()
                featureLabelHitRegions.clear()
                featureLabelVisualBounds.clear()
                featureLabelAlphas.clear()
                labelPaintGeneration++
                g2.drawImage(staticMap, 0, 0, null)
                val overlay = g2.create() as Graphics2D
                applyMapTransform(overlay)
                renderingDynamicOverlay = true
                try {
                    if (seq.isCircular) paintCircular(overlay) else paintLinear(overlay)
                } finally {
                    renderingDynamicOverlay = false
                    overlay.dispose()
                }
            }
            pruneLabelVisuals()
        }

        private fun applyMapTransform(g2: Graphics2D) {
            val offsetX = canvasOffsetX(width, zoomFactor)
            val offsetY = canvasOffsetY(height, zoomFactor)
            g2.translate(offsetX / zoomFactor, offsetY / zoomFactor)
            g2.scale(zoomFactor, zoomFactor)
        }

        private fun clearInteractiveHitRegions() {
            labelHitRegions.clear()
            arcHitRegions.clear()
            featureLabelHitRegions.clear()
            featureLabelVisualBounds.clear()
            featureLabelAlphas.clear()
        }

        private fun restoreStaticHitRegions() {
            labelHitRegions.clear()
            labelHitRegions += staticLabelHitRegions
            arcHitRegions.clear()
            arcHitRegions += staticArcHitRegions
        }

        private fun staticMapImage(): BufferedImage {
            val key = currentStaticMapCacheKey()
            val cached = staticMapImage
            if (cached != null && staticMapCacheKey == key && staticMapSequence === doc.seq && staticMapCutSites === doc.cutSites) {
                return cached
            }

            val image = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                applyMapTransform(graphics)
                clearInteractiveHitRegions()
                centerX = logicalWidth / 2
                centerY = logicalHeight / 2
                renderingStaticLayer = true
                if (doc.seq.isCircular) paintCircular(graphics) else paintLinear(graphics)
            } finally {
                renderingStaticLayer = false
                graphics.dispose()
            }
            staticLabelHitRegions.clear()
            staticLabelHitRegions += labelHitRegions.map { it.copy(bounds = Rectangle(it.bounds)) }
            staticArcHitRegions.clear()
            staticArcHitRegions += arcHitRegions
            staticMapImage = image
            staticMapCacheKey = key
            staticMapSequence = doc.seq
            staticMapCutSites = doc.cutSites
            staticMapRenderCount++
            return image
        }

        private fun currentStaticMapCacheKey(): StaticMapCacheKey {
            val mode = featureLabelMode.selectedItem as? FeatureLabelMode ?: FeatureLabelMode.ALL
            return StaticMapCacheKey(
                logicalWidth,
                logicalHeight,
                width,
                height,
                zoomPercent,
                mode,
                showRestrictionSites.isSelected,
                System.identityHashCode(doc.cutSites),
                doc.cutSites.size,
            )
        }

        // ------------------------------------------------------------- circular

        /** Greedy interval packing so overlapping features get their own ring. */
        private fun assignLayers() {
            if (layeredSequence === doc.seq) return
            ringOf.clear()
            laneOf.clear()
            ringCount = packLanes(doc.seq.features, ringOf)
            laneCount = packLanes(doc.seq.features, laneOf)
            layeredSequence = doc.seq
        }

        private fun visibleFeatures(): List<Feature> {
            val mode = featureLabelMode.selectedItem as? FeatureLabelMode ?: FeatureLabelMode.ALL
            if (visibleFeaturesSequence !== doc.seq || visibleFeaturesMode != mode) {
                cachedVisibleFeatures = doc.seq.features.filter { FeatureLabelOptions.include(it, mode) }
                visibleFeaturesSequence = doc.seq
                visibleFeaturesMode = mode
            }
            return cachedVisibleFeatures
        }


        private fun paintCircular(g2: Graphics2D) {
            val seq = doc.seq
            val gcPct = if (gcSequence === seq) {
                cachedGcPercent
            } else {
                SeqOps.gcContent(seq).also {
                    gcSequence = seq
                    cachedGcPercent = it
                }
            }
            assignLayers()

            val available = min(logicalWidth, logicalHeight) / 2
            val ringBand = ringCount * 15 + 24
            backboneRadius = (available - 26).coerceAtLeast(ringBand + 30).coerceAtMost(available - 18)
            val r = backboneRadius

            if (!renderingDynamicOverlay) {
                // Backbone.
                g2.color = Palette.MAP_BACKBONE
                g2.stroke = BasicStroke(10f)
                g2.draw(
                    Ellipse2D.Double(
                        (centerX - r).toDouble(), (centerY - r).toDouble(),
                        (r * 2).toDouble(), (r * 2).toDouble()
                    )
                )
                g2.color = Palette.MAP_BACKBONE_HIGHLIGHT
                g2.stroke = BasicStroke(2f)
                g2.draw(
                    Ellipse2D.Double(
                        (centerX - r).toDouble(), (centerY - r).toDouble(),
                        (r * 2).toDouble(), (r * 2).toDouble()
                    )
                )

                // Origin marker (base 1) at twelve o'clock.
                g2.color = Palette.CUT_MARK
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(pointX(PI / 2, r - 2), pointY(PI / 2, r - 2), pointX(PI / 2, r - 16), pointY(PI / 2, r - 16))
                g2.fill(Ellipse2D.Double((pointX(PI / 2, r) - 3).toDouble(), (pointY(PI / 2, r) - 3).toDouble(), 6.0, 6.0))
                g2.font = labelFont
                val originX = pointX(PI / 2, r - 25)
                val originY = pointY(PI / 2, r - 25) + 4
                val originBounds = textBounds(g2.fontMetrics, "1", originX - g2.fontMetrics.stringWidth("1") / 2, originY)
                drawLabelBox(g2, "1", originBounds.x + 3, originY, originBounds, Palette.CUT_MARK)
            }

            // Current selection, highlighted as an arc just inside the backbone.
            if (!renderingStaticLayer && doc.hasSelection) {
                val s = doc.selectionStart
                val e = doc.selectionEnd
                if (e > s) {
                    g2.color = Palette.translucent(Palette.ACCENT, 0x88)
                    g2.stroke = BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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

            if (!renderingDynamicOverlay) {
                // Decade ticks around the backbone (base 1 is the origin marker instead of 0).
                val tickStep = tickStep(seq.length)
                g2.stroke = BasicStroke(1f)
                g2.font = subtitleFont
                var tick = tickStep
                while (tick < seq.length) {
                    val a = angleOf(tick)
                    g2.color = Palette.MAP_GUIDE
                    g2.drawLine(
                        pointX(a, r - 8), pointY(a, r - 8),
                        pointX(a, r + 8), pointY(a, r + 8),
                    )
                    g2.color = Palette.MUTED
                    drawCentered(g2, formatTick(tick), a, r - 20)
                    tick += tickStep
                }
            }

            // Feature arcs, one packed ring per lane so overlaps stay readable.
            g2.font = labelFont
            val fm = g2.fontMetrics
            val labels = ArrayList<CircularLabel>(seq.features.size)
            visibleFeatures().forEachIndexed { index, f ->
                val ring = r - 24 - (ringOf[f] ?: 0) * (exportFeatureLaneSpacing ?: 15)
                val color = featureColor(f, index)
                val startAngle = 90.0 - f.start * 360.0 / seq.length
                val extent = -(f.length * 360.0 / seq.length)
                val arc = Arc2D.Double(
                    (centerX - ring).toDouble(), (centerY - ring).toDouble(),
                    (ring * 2).toDouble(), (ring * 2).toDouble(),
                    startAngle, extent, Arc2D.OPEN,
                )
                if (!renderingDynamicOverlay) {
                    g2.color = Palette.FEATURE_OUTLINE
                    g2.stroke = BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(arc)
                    g2.color = color
                    g2.stroke = BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(arc)
                    arcHitRegions += ArcHitRegion(f, ring)
                    // Arrowhead showing the direction of transcription.
                    val headAt = if (f.strand == Strand.FORWARD) f.end else f.start
                    drawArrowHead(g2, angleOf(headAt), ring, f.strand)
                }

                val arcLength = ring * abs(extent) * PI / 180.0
                labels += CircularLabel(
                    featureLabel(f),
                    angleOf((f.start + f.end) / 2),
                    ring,
                    arcLength,
                    index,
                    f,
                )
            }

            // Restriction sites outside the backbone.
            if (!renderingDynamicOverlay) {
                // Restriction sites outside the backbone.
                val sites = if (showRestrictionSites.isSelected) doc.cutSites else emptyList()
                g2.font = labelFont
                for ((enzyme, _, topCut) in sites) {
                    val a = angleOf(topCut)
                    g2.color = Palette.CUT_MARK
                    g2.stroke = BasicStroke(1.5f)
                    g2.drawLine(
                        pointX(a, r + 4), pointY(a, r + 4),
                        pointX(a, r + 16), pointY(a, r + 16),
                    )
                    val labelBounds = drawRadialLabel(g2, "${enzyme.name} ${topCut + 1}", a, r + 20)
                    addPositionHit(labelBounds, topCut)
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
            }

            // Draw labels last so restriction marks and the backbone cannot obscure them.
            if (!renderingStaticLayer && showFeatureLabels.isSelected) drawCircularFeatureLabels(g2, labels, fm, gcPct)

            if (!renderingDynamicOverlay && seq.features.count { it.visible } <= 12) paintFeatureLegend(g2, seq)
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
            val viewport = labelViewport()
            // At higher zoom levels, give labels that fit their feature arc a
            // chance to return inline instead of forcing every label into a
            // distant column. Narrow features still use packed callouts.
            val crowded = labels.size > 6 && zoomPercent < 200

            for (label in labels) {
                val baselineX = pointX(label.angle, label.ring - 20) - fm.stringWidth(label.text) / 2
                val baselineY = pointY(label.angle, label.ring - 20) + fm.ascent / 2
                val bounds = textBounds(fm, label.text, baselineX, baselineY)
                val fitsArc = label.arcLength > fm.stringWidth(label.text) + 10
                if (!crowded && fitsArc && paddedBoundsInsideCanvas(bounds) && occupied.none(bounds::intersects)) {
                    drawFeatureLabel(g2, label.feature, label.text, baselineX, baselineY, bounds, null, edgeFadeAlpha(bounds, viewport))
                    occupied += bounds
                } else {
                    callouts += label
                }
            }

            val margin = 8
            val visibleLeft = viewport.x + margin
            val visibleRight = viewport.x + viewport.width - margin
            val columnWidth = minOf(logicalWidth / 2 - margin * 3, viewport.width / 2 - margin * 2).coerceAtLeast(24)
            val sides = callouts.groupBy { cos(it.angle) < 0 }
            for ((onLeft, sideLabels) in sides) {
                val sorted = sideLabels.sortedBy { pointY(it.angle, it.ring) }
                val baselines = packedCircularCalloutBaselines(fm, sorted, viewport)
                for ((index, label) in sorted.withIndex()) {
                    val text = fitText(fm, label.text, columnWidth)
                    val textWidth = fm.stringWidth(text)
                    val x = if (onLeft) {
                        visibleLeft.coerceIn(margin, (logicalWidth - margin - textWidth).coerceAtLeast(margin))
                    } else {
                        (visibleRight - textWidth).coerceIn(margin, (logicalWidth - margin - textWidth).coerceAtLeast(margin))
                    }
                    val baseline = baselines[index]
                    val bounds = textBounds(fm, text, x, baseline)
                    val angle = label.angle
                    val ring = label.ring
                    val anchorX = pointX(angle, ring)
                    val anchorY = pointY(angle, ring)
                    val labelEdgeX = if (onLeft) bounds.x + bounds.width else bounds.x

                    drawFeatureLabel(
                        g2,
                        label.feature,
                        text,
                        x,
                        baseline,
                        bounds,
                        Palette.featureColor(label.colorIndex),
                        edgeFadeAlpha(bounds, viewport),
                        anchorX,
                        anchorY,
                        labelEdgeX,
                        bounds.y + bounds.height / 2,
                    )
                    occupied += bounds
                }
            }
        }

        private fun packedCircularCalloutBaselines(
            fm: java.awt.FontMetrics,
            labels: List<CircularLabel>,
            viewport: Rectangle,
        ): List<Int> {
            if (labels.isEmpty()) return emptyList()
            val preferredSpacing = fm.height + 15 + zoomLabelPadding()
            val minimumSpacing = textBounds(fm, "Ag", 0, 0).height + 6
            val canFitInViewport = labels.size <= 1 || viewport.height >= minimumSpacing * (labels.size - 1) + fm.height + 16
            val topLimit = fm.ascent + 11
            val bottomLimit = (logicalHeight - fm.descent - 11).coerceAtLeast(topLimit)
            val minBaseline = if (canFitInViewport) {
                (viewport.y + topLimit).coerceIn(topLimit, bottomLimit)
            } else {
                topLimit
            }
            val maxBaseline = if (canFitInViewport) {
                (viewport.y + viewport.height - fm.descent - 13)
                    .coerceIn(minBaseline, bottomLimit.coerceAtLeast(minBaseline))
            } else {
                bottomLimit.coerceAtLeast(minBaseline)
            }
            val availableSpacing = if (labels.size == 1) {
                preferredSpacing
            } else {
                ((maxBaseline - minBaseline).toDouble() / (labels.size - 1)).toInt().coerceAtLeast(1)
            }
            // At high zoom the enlarged canvas has enough room to keep all
            // callouts visible; at the default fit, preserve the original
            // readable spacing even when a crowded record needs extra height.
            val spacing = if (zoomPercent >= 200) {
                maxOf(minimumSpacing, minOf(preferredSpacing, availableSpacing))
            } else {
                maxOf(preferredSpacing, minimumSpacing)
            }
            val totalSpacing = spacing * (labels.size - 1)
            val firstBaseline = if (totalSpacing <= maxBaseline - minBaseline) {
                (minBaseline + maxBaseline - totalSpacing) / 2
            } else {
                minBaseline
            }
            return labels.indices.map { firstBaseline + it * spacing }
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
            val right = logicalWidth - 40
            val span = (right - left).coerceAtLeast(1)
            val laneH = 16
            g2.font = labelFont
            val fm = g2.fontMetrics
            val viewport = labelViewport()
            val visibleLabelLeft = maxOf(left, viewport.x + 8).coerceAtMost((right - 24).coerceAtLeast(left))
            val visibleLabelRight = minOf(right, viewport.x + viewport.width - 8)
            val labelLeft = if (visibleLabelRight - visibleLabelLeft >= 24) visibleLabelLeft else left
            val labelRight = if (visibleLabelRight - visibleLabelLeft >= 24) visibleLabelRight else right
            val calloutLabels = visibleFeatures().mapIndexedNotNull { index, feature ->
                val x1 = left + (feature.start.toDouble() / seq.length * span).roundToInt()
                val x2 = left + (feature.end.toDouble() / seq.length * span).roundToInt()
                val featureWidth = maxOf(4, x2 - x1)
                val text = featureLabel(feature)
                if (featureWidth > fm.stringWidth(text) + 8) {
                    null
                } else {
                    LinearLabel(text, x1 + featureWidth / 2, laneOf[feature] ?: 0, index, feature)
                }
            }
            val placedLabels = placeLinearLabels(fm, calloutLabels, labelLeft, labelRight)
            val labelRowHeight = fm.height + 5 + zoomLabelPadding()
            val calloutBottom = placedLabels.maxOfOrNull { 62 + it.row * labelRowHeight + fm.descent } ?: 48
            val featureStack = 24 + (laneCount - 1).coerceAtLeast(0) * laneH
            val desiredAxisY = maxOf(logicalHeight / 2 + laneCount * laneH / 2, calloutBottom + featureStack + 10)
            val axisY = desiredAxisY.coerceAtMost((logicalHeight - 60).coerceAtLeast(70))
            linearAxisY = axisY

            if (!renderingDynamicOverlay) {
                g2.color = Palette.MAP_BACKBONE
                g2.stroke = BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(left, axisY, right, axisY)
                g2.color = Palette.MAP_BACKBONE_HIGHLIGHT
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(left, axisY, right, axisY)
            }

            // Current selection, highlighted as a band just above the backbone.
            if (!renderingStaticLayer && doc.hasSelection) {
                val s = doc.selectionStart
                val e = doc.selectionEnd
                if (e > s) {
                    val x1 = left + (s.toDouble() / seq.length * span).roundToInt()
                    val x2 = left + (e.toDouble() / seq.length * span).roundToInt()
                    g2.color = Palette.translucent(Palette.ACCENT, 0x88)
                    g2.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(x1, axisY - 8, maxOf(x1 + 2, x2), axisY - 8)
                }
            }

            if (!renderingDynamicOverlay) {
                g2.stroke = BasicStroke(1f)
                g2.font = subtitleFont
                val tickStep = tickStep(seq.length)
                var tick = 0
                while (tick <= seq.length) {
                    val x = left + (tick.toDouble() / seq.length * span).roundToInt()
                    g2.color = Palette.MAP_GUIDE
                    g2.drawLine(x, axisY + 6, x, axisY + 12)
                    g2.color = Palette.MUTED
                    drawStringCentered(g2, formatTick(tick), x, axisY + 26)
                    tick += tickStep
                }
            }

            visibleFeatures().forEachIndexed { index, f ->
                val lane = laneOf[f] ?: 0
                val x1 = left + (f.start.toDouble() / seq.length * span).roundToInt()
                val x2 = left + (f.end.toDouble() / seq.length * span).roundToInt()
                val y = axisY - 24 - lane * laneH
                val w = maxOf(4, x2 - x1)
                val color = featureColor(f, index)
                if (!renderingDynamicOverlay) {
                    g2.color = Palette.FEATURE_OUTLINE
                    g2.fillRoundRect(x1 - 1, y - 1, w + 2, 14, 7, 7)
                    g2.color = Palette.translucent(color, 0xA8)
                    g2.fillRoundRect(x1, y, w, 12, 6, 6)
                    g2.color = color
                    g2.drawRoundRect(x1, y, w, 12, 6, 6)
                    drawLinearArrowHead(g2, x1, y, w, f.strand)
                }
                val text = featureLabel(f)
                if (!renderingStaticLayer && w > fm.stringWidth(text) + 8) {
                    val labelBaseline = y - 2
                    val bounds = textBounds(fm, text, x1, labelBaseline)
                    drawFeatureLabel(g2, f, text, x1, labelBaseline, bounds, null, edgeFadeAlpha(bounds, viewport), animate = false)
                }
            }

            if (!renderingStaticLayer && showFeatureLabels.isSelected) drawLinearFeatureLabels(g2, placedLabels, axisY, laneH, labelRowHeight, viewport)

            if (!renderingDynamicOverlay && showRestrictionSites.isSelected && doc.cutSites.isNotEmpty()) {
                g2.font = labelFont
                val fm = g2.fontMetrics
                val laneOccupied = mutableListOf(mutableListOf<IntRange>())
                for ((enzyme, _, topCut) in doc.cutSites) {
                    val x = left + (topCut.toDouble() / seq.length * span).roundToInt()
                    g2.color = Palette.CUT_MARK
                    g2.drawLine(x, axisY - 8, x, axisY + 8)
                    val label = enzyme.name
                    val labelWidth = fm.stringWidth(label)
                    val interval = (x - 2)..(x + labelWidth + 2)
                    var lane = 0
                    while (lane < laneOccupied.size && laneOccupied[lane].any { rangesOverlap(it, interval) }) lane++
                    if (lane >= laneOccupied.size) laneOccupied.add(mutableListOf())
                    laneOccupied[lane].add(interval)
                    val baseline = axisY + 44 + lane * (fm.height + 2)
                    val bounds = textBounds(fm, label, x + 2, baseline)
                    drawLabelBox(g2, label, x + 2, baseline, bounds, Palette.CUT_MARK)
                    addPositionHit(bounds, topCut)
                }
            }

            if (!renderingDynamicOverlay) {
                g2.color = Palette.TEXT
                g2.font = titleFont
                drawStringCentered(g2, seq.name, logicalWidth / 2, 26)
                g2.font = subtitleFont
                g2.color = Palette.MUTED
                drawStringCentered(g2, "${seq.length} bp ${seq.kind.name.lowercase()} linear", logicalWidth / 2, 44)

                paintFeatureLegend(g2, seq)
            }
        }

        // ---------------------------------------------------------------- helpers

        private fun paintFeatureLegend(g2: Graphics2D, seq: org.instagene.core.Seq) {
            val features = visibleFeatures()
            if (features.isEmpty()) return
            g2.font = labelFont
            val fm = g2.fontMetrics
            val legendX = 8
            var legendY = logicalHeight - 8
            for (f in features.reversed()) {
                val color = featureColor(f, seq.features.indexOf(f))
                val label = featureLabel(f)
                val boxSize = 8
                legendY -= fm.height + 2
                g2.color = Palette.FEATURE_OUTLINE
                g2.fillRoundRect(legendX - 1, legendY - boxSize + 1, boxSize + 2, boxSize + 2, 3, 3)
                g2.color = color
                g2.fillRoundRect(legendX, legendY - boxSize + 2, boxSize, boxSize, 3, 3)
                g2.color = Palette.TEXT
                g2.drawString(label, legendX + boxSize + 4, legendY)
                addFeatureHit(f, Rectangle(legendX - 2, legendY - fm.ascent - 2, boxSize + 6 + fm.stringWidth(label), fm.height + 4))
            }
        }

        private fun featureLabel(feature: Feature): String = FeatureLabelOptions.text(feature)

        /** Packs narrow-feature callouts into rows whose text bounds do not overlap. */
        @Suppress("SameParameterValue")
        private fun placeLinearLabels(
            fm: java.awt.FontMetrics,
            labels: List<LinearLabel>,
            left: Int,
            right: Int,
        ): List<PlacedLinearLabel> {
            val rows = ArrayList<MutableList<IntRange>>()
            val placed = ArrayList<PlacedLinearLabel>(labels.size)
            val maxWidth = (right - left).coerceAtLeast(16)
            val padding = 3 + zoomLabelPadding()
            for (original in labels.sortedBy { it.anchorX }) {
                val text = fitText(fm, original.text, maxWidth)
                val label = original.copy(text = text)
                val textWidth = fm.stringWidth(text)
                val x = (label.anchorX - textWidth / 2).coerceIn(left, (right - textWidth).coerceAtLeast(left))
                val interval = (x - padding)..(x + textWidth + padding)
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

        @Suppress("SameParameterValue")
        private fun drawLinearFeatureLabels(
            g2: Graphics2D,
            labels: List<PlacedLinearLabel>,
            axisY: Int,
            laneHeight: Int,
            rowHeight: Int,
            viewport: Rectangle,
        ) {
            g2.font = labelFont
            val fm = g2.fontMetrics
            val minBaseline = if (zoomFactor > 1.0 && !renderingExport) {
                (viewport.y + fm.ascent + 8).coerceAtLeast(fm.ascent + 8)
            } else {
                62
            }
            val maxBaseline = if (zoomFactor > 1.0 && !renderingExport) {
                (viewport.y + viewport.height - fm.descent - 8).coerceAtLeast(minBaseline)
            } else {
                (logicalHeight - fm.descent - 8).coerceAtLeast(minBaseline)
            }
            val firstBaseline = minBaseline.coerceAtMost(maxBaseline)
            for ((label, x, row) in labels) {
                val baseline = firstBaseline + row * rowHeight
                val bounds = textBounds(fm, label.text, x, baseline)
                val featureY = axisY - 18 - label.lane * laneHeight
                drawFeatureLabel(
                    g2,
                    label.feature,
                    label.text,
                    x,
                    baseline,
                    bounds,
                    Palette.featureColor(label.colorIndex),
                    edgeFadeAlpha(bounds, viewport),
                    label.anchorX,
                    bounds.y + bounds.height,
                    label.anchorX,
                    featureY,
                )
            }
        }

        private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
            first.first <= second.last && second.first <= first.last

        /** Adds a small amount of displayed-space breathing room at high zoom. */
        private fun zoomLabelPadding(): Int =
            ((zoomFactor - 1.0) * 2.0).roundToInt().coerceIn(0, 12)

        private fun fitText(fm: java.awt.FontMetrics, text: String, maxWidth: Int): String {
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "…"
            var end = text.length
            while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) end--
            return if (end == 0) ellipsis else text.substring(0, end) + ellipsis
        }

        private fun textBounds(
            fm: java.awt.FontMetrics,
            text: String,
            x: Int,
            baseline: Int,
        ): Rectangle = Rectangle(x - 3, baseline - fm.ascent - 2, fm.stringWidth(text) + 6, fm.height + 4)

        private fun paddedBoundsInsideCanvas(bounds: Rectangle): Boolean =
            bounds.x >= 3 && bounds.y >= 3 && bounds.x + bounds.width + 3 <= logicalWidth && bounds.y + bounds.height + 3 <= logicalHeight

        private fun drawFeatureLabel(
            g2: Graphics2D,
            feature: Feature,
            text: String,
            targetX: Int,
            targetBaseline: Int,
            targetBounds: Rectangle,
            stripe: Color?,
            alpha: Float,
            leaderStartX: Int? = null,
            leaderStartY: Int? = null,
            leaderEndX: Int? = null,
            leaderEndY: Int? = null,
            animate: Boolean = true,
        ) {
            val fm = g2.getFontMetrics(labelFont)
            val key = featureLabelKey(feature, text)
            val targetAlpha = alpha.coerceIn(0f, 1f)
            val animateNow = animate && shouldAnimateLabels()
            val visual = labelVisuals.getOrPut(key) {
                LabelVisualState(
                    targetX.toDouble(),
                    targetBaseline.toDouble(),
                    if (animateNow) 0f else targetAlpha,
                    targetX,
                    targetBaseline,
                    targetAlpha,
                    labelPaintGeneration,
                )
            }
            visual.targetX = targetX
            visual.targetBaseline = targetBaseline
            visual.targetAlpha = targetAlpha
            visual.lastSeen = labelPaintGeneration
            if (!animateNow) {
                visual.x = targetX.toDouble()
                visual.baseline = targetBaseline.toDouble()
                visual.alpha = targetAlpha
            } else if (abs(visual.x - targetX) > 0.5 || abs(visual.baseline - targetBaseline) > 0.5 || abs(visual.alpha - targetAlpha) > 0.02f) {
                animationTimer.start()
            }

            val drawX = visual.x.roundToInt()
            val drawBaseline = visual.baseline.roundToInt()
            val drawBounds = textBounds(fm, text, drawX, drawBaseline)
            val drawAlpha = visual.alpha.coerceIn(0f, 1f)
            addFeatureVisualBounds(feature, drawBounds)
            addFeatureAlpha(feature, drawAlpha)
            if (drawAlpha <= 0.02f) return

            if (leaderStartX != null && leaderStartY != null && leaderEndX != null && leaderEndY != null) {
                val currentEndX = when {
                    leaderEndX <= targetBounds.x -> drawBounds.x
                    leaderEndX >= targetBounds.x + targetBounds.width -> drawBounds.x + drawBounds.width
                    else -> drawBounds.x + drawBounds.width / 2
                }
                val currentEndY = when {
                    leaderEndY <= targetBounds.y -> drawBounds.y
                    leaderEndY >= targetBounds.y + targetBounds.height -> drawBounds.y + drawBounds.height
                    else -> drawBounds.y + drawBounds.height / 2
                }
                withAlpha(g2, drawAlpha) {
                    g2.color = Palette.MAP_GUIDE
                    g2.stroke = BasicStroke(1f)
                    g2.drawLine(leaderStartX, leaderStartY, currentEndX, currentEndY)
                }
            }
            drawLabelBox(g2, text, drawX, drawBaseline, drawBounds, stripe, drawAlpha)
            if (drawAlpha >= 0.18f) addFeatureHit(feature, drawBounds)
        }

        private fun featureLabelKey(feature: Feature, text: String): FeatureLabelKey =
            FeatureLabelKey(text, feature.type, feature.start, feature.end, feature.strand)

        private fun addFeatureAlpha(feature: Feature, alpha: Float) {
            featureLabelAlphas.putIfAbsent(featureLabel(feature), alpha)
            if (feature.name.isNotBlank()) featureLabelAlphas.putIfAbsent(feature.name, alpha)
        }

        private fun addFeatureVisualBounds(feature: Feature, bounds: Rectangle) {
            val screenBounds = screenRect(Rectangle(bounds.x - 3, bounds.y - 3, bounds.width + 6, bounds.height + 6))
            featureLabelVisualBounds.putIfAbsent(featureLabel(feature), screenBounds)
            if (feature.name.isNotBlank()) featureLabelVisualBounds.putIfAbsent(feature.name, screenBounds)
        }

        private fun drawLabelBox(
            g2: Graphics2D,
            text: String,
            x: Int,
            baseline: Int,
            bounds: Rectangle,
            stripe: Color? = null,
            alpha: Float = 1f,
        ) {
            withAlpha(g2, alpha) {
                g2.color = Palette.MAP_LABEL_BACKGROUND
                g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5)
                if (stripe != null) {
                    g2.color = stripe
                    g2.fillRoundRect(bounds.x, bounds.y, 4, bounds.height, 5, 5)
                }
                g2.color = Palette.MAP_LABEL_BORDER
                g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5)
                g2.color = Palette.TEXT
                g2.font = labelFont
                g2.drawString(text, x, baseline)
            }
        }

        private inline fun withAlpha(g2: Graphics2D, alpha: Float, draw: () -> Unit) {
            val previous = g2.composite
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
            try {
                draw()
            } finally {
                g2.composite = previous
            }
        }

        private fun formatTick(tick: Int): String =
            if (tick >= 1000 && tick % 1000 == 0) "${tick / 1000} kb" else tick.toString()

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

        private fun drawLinearArrowHead(g2: Graphics2D, x: Int, y: Int, width: Int, strand: Strand) {
            if (width < 8) return
            if (strand == Strand.FORWARD) {
                g2.fillPolygon(
                    intArrayOf(x + width, x + width, x + width + 5),
                    intArrayOf(y - 1, y + 13, y + 6),
                    3,
                )
            } else if (strand == Strand.REVERSE) {
                g2.fillPolygon(
                    intArrayOf(x, x, x - 5),
                    intArrayOf(y - 1, y + 13, y + 6),
                    3,
                )
            }
        }

        private fun drawCentered(g2: Graphics2D, text: String, angle: Double, radius: Int) {
            drawStringCentered(g2, text, pointX(angle, radius), pointY(angle, radius) + 4)
        }

        /** Labels outside the circle are pushed left or right so they never overlap it. */
        private fun drawRadialLabel(g2: Graphics2D, text: String, angle: Double, radius: Int): Rectangle {
            val x = pointX(angle, radius)
            val y = pointY(angle, radius) + 4
            val fm = g2.fontMetrics
            val onLeft = cos(angle) < 0
            val textX = if (onLeft) x - fm.stringWidth(text) else x
            val bounds = textBounds(fm, text, textX, y)
            drawLabelBox(g2, text, textX, y, bounds, Palette.CUT_MARK)
            return bounds
        }

        private fun addFeatureHit(feature: Feature, bounds: Rectangle) {
            val padded = screenRect(Rectangle(bounds.x - 3, bounds.y - 3, bounds.width + 6, bounds.height + 6))
            labelHitRegions += LabelHitRegion(padded, feature.start, feature.end, feature)
            featureLabelHitRegions.putIfAbsent(featureLabel(feature), padded)
            if (feature.name.isNotBlank()) featureLabelHitRegions.putIfAbsent(feature.name, padded)
        }

        private fun addPositionHit(bounds: Rectangle, position: Int) {
            val padded = screenRect(Rectangle(bounds.x - 3, bounds.y - 3, bounds.width + 6, bounds.height + 6))
            labelHitRegions += LabelHitRegion(padded, position, position + 1)
        }

        fun featureLabelHitCenterForTest(name: String): Pair<Int, Int>? {
            val bounds = featureLabelHitRegions[name] ?: return null
            return bounds.x + bounds.width / 2 to bounds.y + bounds.height / 2
        }

        fun featureArcHitCenterForTest(name: String): Pair<Int, Int>? {
            val hit = arcHitRegions.firstOrNull { featureLabel(it.feature) == name || it.feature.name == name } ?: return null
            val midpoint = hit.feature.start + hit.feature.length / 2
            val angle = angleOf(midpoint % doc.seq.length)
            return (canvasOffsetX(width, zoomFactor) + pointX(angle, hit.ring) * zoomFactor).roundToInt() to
                (canvasOffsetY(height, zoomFactor) + pointY(angle, hit.ring) * zoomFactor).roundToInt()
        }

        fun featureLabelBoundsForTest(): List<Rectangle> =
            featureLabelVisualBounds.values.map { Rectangle(it) }

        fun featureLabelBoundsForTest(name: String): Rectangle? =
            featureLabelVisualBounds[name]?.let { Rectangle(it) }

        fun featureLabelAlphasForTest(): Map<String, Float> =
            featureLabelAlphas.toMap()

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
            val px = (e.x - canvasOffsetX(width, zoomFactor)) / zoomFactor
            val py = (e.y - canvasOffsetY(height, zoomFactor)) / zoomFactor
            if (seq.isCircular) {
                val dx = px - centerX
                val dy = centerY - py
                val dist = sqrt(dx * dx + dy * dy)
                val outer = backboneRadius + 18.0
                val inner = (backboneRadius - 24 - ringCount * 15 - 8).coerceAtLeast(0)
                if (dist > outer || dist < inner) return null
                var angle = PI / 2 - atan2(dy, dx)
                if (angle < 0) angle += 2 * PI
                return (angle / (2 * PI) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
            }
            val left = 40
            val span = (logicalWidth - 80).coerceAtLeast(1)
            return (((px - left) / span) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
        }

        /** Maps a click to a feature or base position; clicks outside the map do nothing. */
        private fun handleClick(e: MouseEvent) {
            val seq = doc.seq
            if (seq.length == 0) return
            labelHitRegions
                .filter { it.bounds.contains(e.x, e.y) }
                .minByOrNull { it.bounds.width * it.bounds.height }
                ?.let {
                onSelect?.invoke(it.start, it.end.coerceAtMost(seq.length))
                it.feature?.let(::focusFeatureInViewport)
                return
            }
            featureArcAt(e.x, e.y)?.let {
                onSelect?.invoke(it.start, it.end)
                return
            }
            val position = positionAt(e) ?: return
            val feature = seq.features.firstOrNull { position in it.start until it.end }
            if (feature != null) {
                onSelect?.invoke(feature.start, feature.end)
            } else {
                onSelect?.invoke(position, position + 1)
            }
        }

        private fun focusFeatureInViewport(feature: Feature) {
            if (zoomFactor <= 1.0) return
            val target = if (doc.seq.isCircular) circularFeatureScreenPoint(feature) else linearFeatureScreenPoint(feature)
            scrollViewportTo(target.first, target.second)
        }

        private fun circularFeatureScreenPoint(feature: Feature): Pair<Int, Int> {
            val ring = arcHitRegions.firstOrNull { it.feature == feature }?.ring
                ?: (backboneRadius - 24 - (ringOf[feature] ?: 0) * (exportFeatureLaneSpacing ?: 15))
            val angle = angleOf((feature.start + feature.length / 2).coerceIn(0, doc.seq.length - 1))
            return logicalToScreen(pointX(angle, ring), pointY(angle, ring))
        }

        private fun linearFeatureScreenPoint(feature: Feature): Pair<Int, Int> {
            val left = 40
            val span = (logicalWidth - 80).coerceAtLeast(1)
            val midpoint = (feature.start + feature.length / 2).coerceIn(0, doc.seq.length - 1)
            val x = left + (midpoint.toDouble() / doc.seq.length * span).roundToInt()
            val y = linearAxisY - 18 - (laneOf[feature] ?: 0) * 16
            return logicalToScreen(x, y)
        }

        private fun scrollViewportTo(screenX: Int, screenY: Int) {
            val viewport = mapScrollPane.viewport
            val extent = viewport.extentSize
            if (extent.width <= 0 || extent.height <= 0) return
            val maxX = (mapCanvas.width - extent.width).coerceAtLeast(0)
            val maxY = (mapCanvas.height - extent.height).coerceAtLeast(0)
            animateViewportTo(java.awt.Point(
                (screenX - extent.width / 2).coerceIn(0, maxX),
                (screenY - extent.height / 2).coerceIn(0, maxY),
            ))
        }

        private fun featureArcAt(x: Int, y: Int): Feature? {
            val seq = doc.seq
            if (!seq.isCircular || arcHitRegions.isEmpty()) return null
            val logicalX = (x - canvasOffsetX(width, zoomFactor)) / zoomFactor
            val logicalY = (y - canvasOffsetY(height, zoomFactor)) / zoomFactor
            val dx = logicalX - centerX
            val dy = centerY - logicalY
            val dist = sqrt(dx * dx + dy * dy)
            var angle = PI / 2 - atan2(dy, dx)
            if (angle < 0) angle += 2 * PI
            val position = (angle / (2 * PI) * seq.length).roundToInt().coerceIn(0, seq.length - 1)
            return arcHitRegions
                .filter { abs(dist - it.ring) <= 10.0 && containsFeaturePosition(it.feature, position) }
                .minByOrNull { abs(dist - it.ring) }
                ?.feature
        }

        private fun containsFeaturePosition(feature: Feature, position: Int): Boolean =
            if (feature.start <= feature.end) {
                position in feature.start until feature.end
            } else {
                position >= feature.start || position < feature.end
            }

        private fun screenRect(bounds: Rectangle): Rectangle = Rectangle(
            (canvasOffsetX(width, zoomFactor) + bounds.x * zoomFactor).roundToInt(),
            (canvasOffsetY(height, zoomFactor) + bounds.y * zoomFactor).roundToInt(),
            (bounds.width * zoomFactor).roundToInt().coerceAtLeast(1),
            (bounds.height * zoomFactor).roundToInt().coerceAtLeast(1),
        )

        private fun logicalToScreen(x: Int, y: Int): Pair<Int, Int> =
            (canvasOffsetX(width, zoomFactor) + x * zoomFactor).roundToInt() to
                (canvasOffsetY(height, zoomFactor) + y * zoomFactor).roundToInt()
    }
}
