package org.instagene.app.gui

import org.instagene.app.gui.tool.MapExportOptions
import org.instagene.app.gui.tool.MapPreset
import org.instagene.app.gui.tool.PlasmidMapPanel
import org.instagene.app.gui.tool.FeatureLabelChoice
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.theme.Palette
import org.instagene.core.Feature
import org.instagene.core.Enzymes
import org.instagene.core.Seq
import org.instagene.core.Topology
import org.instagene.core.io.SeqIO
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.abs
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
        val canvas = map.canvasForTest()
        canvas.setSize(canvas.preferredSize)
        canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
        return canvas
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

    private fun click(canvas: JPanel, p: Pair<Int, Int>) {
        press(canvas, p)
        release(canvas, p)
    }

    private fun renderMap(seq: Seq, width: Int, height: Int): BufferedImage {
        val map = PlasmidMapPanel(SeqDocument(seq))
        map.setSize(width, height)
        map.doLayout()
        val canvas = map.canvasForTest()
        canvas.setSize(canvas.preferredSize)
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { canvas.paint(graphics) } finally { graphics.dispose() }
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

    private fun paintCanvas(canvas: JPanel): BufferedImage {
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            canvas.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun labelPixels(image: BufferedImage, bounds: Rectangle, inset: Int = 1): List<Int> =
        (bounds.y + inset until bounds.y + bounds.height - inset).flatMap { y ->
            (bounds.x + inset until bounds.x + bounds.width - inset).map { x -> image.getRGB(x, y) }
        }

    private fun opaqueRgb(color: Color): Int = Color(color.red, color.green, color.blue).rgb

    private fun containsColorNear(pixels: List<Int>, expected: Color, tolerance: Int = 12): Boolean =
        pixels.any { pixel ->
            val actual = Color(pixel, true)
            abs(actual.red - expected.red) <= tolerance &&
                abs(actual.green - expected.green) <= tolerance &&
                abs(actual.blue - expected.blue) <= tolerance
        }

    private fun viewportDistanceTo(map: PlasmidMapPanel, point: Pair<Int, Int>): Int {
        val position = map.viewportPositionForTest()
        val extent = map.viewportExtentForTest()
        val centerX = position.x + extent.width / 2
        val centerY = position.y + extent.height / 2
        return abs(point.first - centerX) + abs(point.second - centerY)
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
    fun clickOnRealPlasmidFeatureLabelSelectsTheWholeFeature() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val seq = SeqIO.Samples.PBR322_NCBI
            val feature = seq.features.first { it.visible && it.name == "ROP protein" }
            content.doc.loadSequence(seq)
            val map = content.plasmidMapPanel
            map.setSize(800, 700)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(800, 700)
            canvas.paint(BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB).graphics)
            val hit = map.featureLabelHitCenterForTest(feature.name)

            assertTrue(hit != null, "expected a painted label hit target for ${feature.name}")
            click(canvas, hit)

            assertEquals(feature.start, content.doc.selectionStart)
            assertEquals(feature.end, content.doc.selectionEnd)
        }
    }

    @Test
    fun clickingALabelAtDefaultZoomDoesNotMoveTheViewport() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val seq = SeqIO.Samples.PBR322_NCBI
            val feature = seq.features.first { it.visible && it.name == "ROP protein" }
            content.doc.loadSequence(seq)
            val map = content.plasmidMapPanel
            map.setSize(800, 700)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(800, 700)
            canvas.paint(BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB).graphics)
            val before = map.viewportPositionForTest()
            val hit = map.featureLabelHitCenterForTest(feature.name)

            assertTrue(hit != null, "expected a painted label hit target for ${feature.name}")
            click(canvas, hit)

            assertEquals(before, map.viewportPositionForTest())
            assertEquals(feature.start, content.doc.selectionStart)
            assertEquals(feature.end, content.doc.selectionEnd)
        }
    }

    @Test
    fun clickOnRealPlasmidFeatureArcSelectsTheWholeFeature() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val seq = SeqIO.Samples.PBR322_NCBI
            val feature = seq.features.first { it.visible && it.name.isNotBlank() && it.length > 100 }
            content.doc.loadSequence(seq)
            val map = content.plasmidMapPanel
            map.setSize(800, 700)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(800, 700)
            canvas.paint(BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB).graphics)
            val hit = map.featureArcHitCenterForTest(feature.name)

            assertTrue(hit != null, "expected a painted arc hit target for ${feature.name}")
            click(canvas, hit)

            assertEquals(feature.start, content.doc.selectionStart)
            assertEquals(feature.end, content.doc.selectionEnd)
        }
    }

    @Test
    fun realPlasmidFeatureLabelsUseNonOverlappingPackedCallouts() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(SeqIO.Samples.PBR322_NCBI))
            map.setSize(800, 700)
            map.doLayout()
            map.canvasForTest().setSize(800, 700)
            map.canvasForTest().paint(BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB).graphics)
            val bounds = map.featureLabelBoundsForTest()

            assertTrue(bounds.size > 6, "expected multiple packed labels for the real plasmid")
            bounds.forEachIndexed { index, first ->
                bounds.drop(index + 1).forEach { second ->
                    assertFalse(first.intersects(second), "feature labels overlap: $first and $second")
                }
            }
        }
    }

    @Test
    fun clickOnCrowdedCircularCalloutSelectsTheWholeFeature() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val features = (0 until 18).map { index ->
                Feature("feature-$index", start = index * 15, end = index * 15 + 9)
            }
            content.doc.loadSequence(circular.copy(features = features))
            val map = content.plasmidMapPanel
            map.setSize(340, 300)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            assertTrue(canvas.height > 380, "crowded circular callouts should increase the canvas height")
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val target = features.last()
            val hit = map.featureLabelHitCenterForTest(target.name)

            assertTrue(hit != null, "expected a painted callout hit target for ${target.name}")
            click(canvas, hit)

            assertEquals(target.start, content.doc.selectionStart)
            assertEquals(target.end, content.doc.selectionEnd)
        }
    }

    @Test
    fun clickingFeatureLabelKeepsItsBoxAndTextColorsAfterRepaint() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val clicked = Feature("clicked-color-feature", start = 0, end = 80)
            val unaffected = Feature("unaffected-color-feature", start = 200, end = 280)
            content.doc.loadSequence(circular.copy(features = listOf(clicked, unaffected)))
            val map = content.plasmidMapPanel
            map.setSize(340, 300)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)

            val before = paintCanvas(canvas)
            val clickedBounds = map.featureLabelBoundsForTest(clicked.name)
                ?: error("expected an initial label for ${clicked.name}")
            val unaffectedBounds = map.featureLabelBoundsForTest(unaffected.name)
                ?: error("expected an initial label for ${unaffected.name}")
            val clickedPixels = labelPixels(before, clickedBounds, inset = 4)
            val unaffectedPixels = labelPixels(before, unaffectedBounds)
            assertTrue(clickedPixels.contains(opaqueRgb(Palette.MAP_LABEL_BACKGROUND)), "expected the label background color")
            assertTrue(containsColorNear(clickedPixels, Palette.TEXT), "expected the label text color")

            val hit = map.featureLabelHitCenterForTest(clicked.name)
                ?: error("expected a clickable label for ${clicked.name}")
            click(canvas, hit)

            val after = paintCanvas(canvas)
            val afterClickedBounds = map.featureLabelBoundsForTest(clicked.name)
                ?: error("expected the clicked label after the first click")
            val afterUnaffectedBounds = map.featureLabelBoundsForTest(unaffected.name)
                ?: error("expected the unaffected label after the first click")
            assertEquals(clickedBounds, afterClickedBounds)
            assertEquals(unaffectedBounds, afterUnaffectedBounds)
            assertEquals(
                clickedPixels,
                labelPixels(after, afterClickedBounds, inset = 4),
                "clicked label text and background colors changed after the first click",
            )
            assertEquals(
                unaffectedPixels,
                labelPixels(after, afterUnaffectedBounds),
                "unaffected label colors changed after the first click",
            )
            assertEquals(clicked.start, content.doc.selectionStart)
            assertEquals(clicked.end, content.doc.selectionEnd)

            click(canvas, hit)
            val afterSecondClick = paintCanvas(canvas)
            val secondClickedBounds = map.featureLabelBoundsForTest(clicked.name)
                ?: error("expected the clicked label after the second click")
            val secondUnaffectedBounds = map.featureLabelBoundsForTest(unaffected.name)
                ?: error("expected the unaffected label after the second click")
            assertEquals(clickedPixels, labelPixels(afterSecondClick, secondClickedBounds, inset = 4))
            assertEquals(unaffectedPixels, labelPixels(afterSecondClick, secondUnaffectedBounds))
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

    @Test
    fun mixedLinearLabelsDoNotOverlapAcrossFeatureLanesAndCallouts() {
        SwingUtilities.invokeAndWait {
            val features = listOf(
                Feature("wide-alpha", start = 0, end = 300),
                Feature("wide-beta", start = 100, end = 400),
                Feature("wide-gamma", start = 200, end = 500),
            ) + (0 until 3).flatMap { index ->
                listOf(
                    Feature("narrow-left-$index", start = 250 + index * 10, end = 251 + index * 10),
                    Feature("narrow-right-$index", start = 750 + index * 10, end = 751 + index * 10),
                )
            }
            val map = PlasmidMapPanel(
                SeqDocument(
                    Seq(
                        bases = "ACGT".repeat(250),
                        topology = Topology.LINEAR,
                        features = features,
                    )
                )
            )
            map.mapFontSize.value = 14
            map.setSize(360, 240)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            paintCanvas(canvas)

            val bounds = features.map { feature ->
                feature.name to (map.featureLabelBoundsForTest(feature.name) ?: error("missing ${feature.name}"))
            }
            assertEquals(features.size, bounds.size)
            val wideLabelYs = features.take(3).map { feature -> bounds.first { it.first == feature.name }.second.y }.toSet()
            assertEquals(3, wideLabelYs.size, "overlapping feature labels should follow separate vertical lanes")
            val leftCalloutBounds = features.filter { it.name.startsWith("narrow-left") }
                .map { feature -> bounds.first { it.first == feature.name }.second }
            val rightCalloutBounds = features.filter { it.name.startsWith("narrow-right") }
                .map { feature -> bounds.first { it.first == feature.name }.second }
            assertEquals(1, leftCalloutBounds.map { it.x }.toSet().size, "left callouts should share one aligned rail")
            assertEquals(1, rightCalloutBounds.map { it.maxX }.toSet().size, "right callouts should share one aligned rail")
            assertTrue(leftCalloutBounds.zipWithNext().all { (first, second) -> first.y < second.y })
            assertTrue(rightCalloutBounds.zipWithNext().all { (first, second) -> first.y < second.y })
            leftCalloutBounds.forEach { leftCallout ->
                rightCalloutBounds.forEach { rightCallout ->
                    assertTrue(leftCallout.maxX < rightCallout.x, "callout rails should keep a center gap")
                }
            }
            bounds.forEachIndexed { index, (firstName, first) ->
                assertTrue(first.x >= 0 && first.y >= 0, "label $index starts outside the canvas: $first")
                assertTrue(
                    first.maxX <= canvas.width && first.maxY <= canvas.height,
                    "label $index exceeds ${canvas.width}x${canvas.height}: $first",
                )
                bounds.drop(index + 1).forEach { (secondName, second) ->
                    assertFalse(
                        first.intersects(second),
                        "linear feature labels overlap: $firstName $first and $secondName $second",
                    )
                }
            }
        }
    }

    @Test
    fun sameLaneLinearLabelsUseASecondaryRowWhenTheyCannotFitSideBySide() {
        SwingUtilities.invokeAndWait {
            val features = listOf(
                Feature("same-lane-feature-with-a-long-label-alpha", start = 300, end = 301),
                Feature("same-lane-feature-with-a-long-label-beta", start = 305, end = 306),
            )
            val map = PlasmidMapPanel(
                SeqDocument(
                    Seq(
                        bases = "ACGT".repeat(250),
                        topology = Topology.LINEAR,
                        features = features,
                    )
                )
            )
            map.mapFontSize.value = 14
            map.setSize(360, 240)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            paintCanvas(canvas)

            val first = map.featureLabelBoundsForTest(features[0].name) ?: error("missing first label")
            val second = map.featureLabelBoundsForTest(features[1].name) ?: error("missing second label")
            assertFalse(first.intersects(second), "same-lane labels should not overlap")
            assertTrue(first.y != second.y, "same-lane labels should use a secondary row when needed")
        }
    }

    @Test
    fun denseLinearCalloutRailExpandsTheCanvasInsteadOfClippingLabels() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 14).map { index ->
                Feature("dense-left-feature-$index", start = 10 + index * 10, end = 11 + index * 10)
            }
            val map = PlasmidMapPanel(
                SeqDocument(
                    Seq(
                        bases = "ACGT".repeat(250),
                        topology = Topology.LINEAR,
                        features = features,
                    )
                )
            )
            map.mapFontSize.value = 14
            map.setSize(360, 240)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            paintCanvas(canvas)

            val bounds = features.map { feature ->
                map.featureLabelBoundsForTest(feature.name) ?: error("missing ${feature.name}")
            }
            assertTrue(canvas.height > 380, "dense callouts should increase the canvas height")
            assertTrue(bounds.zipWithNext().all { (first, second) -> first.y < second.y })
            bounds.forEach { label ->
                assertTrue(label.y >= 0 && label.maxY <= canvas.height, "label $label was clipped")
            }
        }
    }

    @Test
    fun svgExportHonorsPresetTitleAndLabelOptions() {
        SwingUtilities.invokeAndWait {
            val file = File.createTempFile("instagene-map-", ".svg")
            file.deleteOnExit()
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = listOf(Feature("feature", start = 10, end = 20)))))
            map.exportSvg(file, MapExportOptions(MapPreset.NOTEBOOK, "Paper map", showFeatureLabels = false))
            val svg = file.readText()
            assertTrue(svg.contains("Paper map"))
            assertFalse(svg.contains(">feature</text>"))
            assertTrue(svg.contains("width=\"900\""))
            assertTrue(svg.contains("stroke-linecap=\"round\""))
            assertTrue(svg.contains("<rect"))
        }
    }

    @Test
    fun mapDisplayControlsSeparateMapTextAndMetadataSettings() {
        SwingUtilities.invokeAndWait {
            val sequence = circular.copy(features = listOf(
                Feature("coding", "CDS", 10, 80),
                Feature("promoter", "promoter", 100, 140),
            ))
            val map = PlasmidMapPanel(SeqDocument(sequence))
            assertTrue(map.showFeatureKey.isSelected)
            assertTrue(map.showMetadata.isSelected)
            assertEquals(14, (map.mapFontSize.value as Number).toInt())
            assertTrue(map.featureLabelMode.itemCount >= 4)
            assertTrue((0 until map.featureLabelMode.itemCount).map { (map.featureLabelMode.getItemAt(it) as FeatureLabelChoice).displayName }
                .containsAll(listOf("All features", "Visible features", "CDS", "promoter")))

            val controlFontBefore = map.showFeatureKey.font.size
            map.showFeatureKey.doClick()
            assertFalse(map.showFeatureKey.isSelected)
            assertTrue(map.showMetadata.isSelected)
            map.showMetadata.doClick()
            assertFalse(map.showMetadata.isSelected)
            map.mapFontSize.value = 18

            assertEquals(18, (map.mapFontSize.value as Number).toInt())
            assertEquals(controlFontBefore, map.showFeatureKey.font.size)
        }
    }

    @Test
    fun exportsHonorCustomDimensionsTransparencyAndKeyOptions() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = listOf(Feature("feature", start = 10, end = 20)))))
            val png = File.createTempFile("instagene-map-options-", ".png").also { it.deleteOnExit() }
            map.exportPng(
                png,
                MapExportOptions(
                    width = 640,
                    height = 480,
                    showTitle = false,
                    showFeatureKey = false,
                    showMetadata = false,
                    transparentBackground = true,
                    fontSize = 18,
                ),
            )
            val image = ImageIO.read(png)
            assertEquals(640, image.width)
            assertEquals(480, image.height)
            assertEquals(0, image.getRGB(0, 0).ushr(24))

            val svg = File.createTempFile("instagene-map-options-", ".svg").also { it.deleteOnExit() }
            map.exportSvg(
                svg,
                MapExportOptions(width = 640, height = 480, showTitle = false, showFeatureKey = false, showMetadata = false, fontSize = 18),
            )
            val text = svg.readText()
            assertTrue(text.contains("width=\"640\""))
            assertTrue(text.contains("height=\"480\""))
            assertFalse(text.contains("font-size=\"24\""))
            assertFalse(text.contains("features ·"))
        }
    }

    @Test
    fun svgExportKeepsEveryCrowdedFeatureLabelAsAnEditableCallout() {
        SwingUtilities.invokeAndWait {
            val file = File.createTempFile("instagene-crowded-map-", ".svg")
            file.deleteOnExit()
            val features = (0 until 18).map { index ->
                Feature("feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.exportSvg(file, MapExportOptions(MapPreset.NOTEBOOK, featureLaneSpacing = 16))
            val svg = file.readText()
            features.forEach { feature ->
                assertEquals(1, Regex(">${feature.name}</text>").findAll(svg).count(), "missing or duplicate label for ${feature.name}")
            }
        }
    }

    @Test
    fun mapZoomClampsAndExpandsTheScrollableCanvas() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(circular.copy(topology = Topology.LINEAR)))
            map.setSize(320, 320)
            map.doLayout()
            assertEquals(100, map.zoomPercent)
            map.setZoomPercent(275)
            assertEquals(300, map.zoomPercent)
            assertEquals("300%", map.zoomLabelTextForTest())
            assertEquals(
                0,
                map.zoomControlsForTest().components.count { it is javax.swing.JComboBox<*> },
                "zoom controls should not contain a percentage dropdown",
            )
            assertTrue(map.canvasForTest().preferredSize.width > map.viewportExtentForTest().width)
            map.setZoomPercent(999)
            assertEquals(600, map.zoomPercent)
            map.setZoomPercent(1)
            assertEquals(50, map.zoomPercent)
            map.setZoomPercent(100)
            assertEquals(100, map.zoomPercent)
        }
    }

    @Test
    fun zoomUpdatesViewportGeometryAndKeepsTheMapCenterAnchored() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(circular.copy(topology = Topology.LINEAR)))
            map.setSize(320, 320)
            map.doLayout()
            val before = map.viewportPositionForTest()
            val extent = map.viewportExtentForTest()
            assertTrue(extent.width > 0)
            val beforeCenterX = before.x + extent.width / 2.0
            val beforeCenterY = before.y + extent.height / 2.0

            map.setZoomPercent(600)
            val canvas = map.canvasForTest()
            assertTrue(canvas.width > extent.width)
            assertTrue(canvas.height > extent.height)
            val after = map.viewportPositionForTest()
            assertTrue(after.x > before.x || after.y > before.y, "zoom should move the viewport into the enlarged canvas")
            val afterExtent = map.viewportExtentForTest()
            assertTrue(abs((after.x + afterExtent.width / 2.0) / 6.0 - beforeCenterX) < 2.0)
            assertTrue(abs((after.y + afterExtent.height / 2.0) / 6.0 - beforeCenterY) < 2.0)

            map.setZoomPercent(100)
            val reset = map.viewportPositionForTest()
            assertEquals(0, reset.x)
            assertEquals(0, reset.y)
        }
    }

    @Test
    fun zoomedCircularFeatureHitUsesLogicalCoordinates() {
        SwingUtilities.invokeAndWait {
            val content = InstaGeneContent()
            val feature = Feature("zoomed", start = 50, end = 100)
            content.doc.loadSequence(circular.copy(features = listOf(feature)))
            val map = content.plasmidMapPanel
            map.setZoomPercent(200)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val hit = map.featureArcHitCenterForTest(feature.name)
            assertTrue(hit != null)
            click(canvas, hit)
            assertEquals(feature.start, content.doc.selectionStart)
            assertEquals(feature.end, content.doc.selectionEnd)
        }
    }

    @Test
    fun zoomedCircularLabelsReflowWithoutOverlapping() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 18).map { index ->
                Feature("zoomed-feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.setSize(340, 300)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)

            val bounds = map.featureLabelBoundsForTest()
            assertEquals(features.size, bounds.size)
            bounds.forEachIndexed { index, first ->
                assertTrue(first.x >= 0 && first.y >= 0)
                assertTrue(first.maxX <= canvas.width && first.maxY <= canvas.height, "$first outside ${canvas.width}x${canvas.height}")
                bounds.drop(index + 1).forEach { second ->
                    assertFalse(first.intersects(second), "zoomed feature labels overlap: $first and $second")
                }
            }
        }
    }

    @Test
    fun zoomedCircularLabelsMoveWithTheVisibleViewport() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 18).map { index ->
                Feature("feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.setSize(340, 300)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val before = map.featureLabelBoundsForTest("feature-0")

            assertTrue(before != null, "expected an initial label for feature-0")
            map.setViewportPositionForTest(360, 0)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val after = map.featureLabelBoundsForTest("feature-0")

            assertTrue(after != null, "expected a scrolled label for feature-0")
            assertTrue(after.x > before.x, "expected feature-0 label to move with the viewport")
        }
    }

    @Test
    fun clickingZoomedCircularLabelMovesViewportTowardTheFeatureArc() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 18).map { index ->
                Feature("focus-feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.setSize(340, 300)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            map.setViewportPositionForTest(360, 0)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val target = features.mapNotNull { feature ->
                val labelHit = map.featureLabelHitCenterForTest(feature.name)
                val arcHit = map.featureArcHitCenterForTest(feature.name)
                if (labelHit != null && arcHit != null) Triple(feature, labelHit, arcHit) else null
            }.maxByOrNull { (_, _, arcHit) -> viewportDistanceTo(map, arcHit) }

            assertTrue(target != null, "expected a clickable zoomed feature label")
            val beforeDistance = viewportDistanceTo(map, target.third)
            click(canvas, target.second)
            repeat(40) { map.advanceViewportAnimationForTest() }
            val afterDistance = viewportDistanceTo(map, target.third)

            assertTrue(afterDistance < beforeDistance, "expected label click to move the viewport toward the feature arc")
        }
    }

    @Test
    fun zoomedLinearLabelsStayInsideTheHorizontalViewport() {
        SwingUtilities.invokeAndWait {
            val seq = Seq(
                bases = "ACGT".repeat(300),
                topology = Topology.LINEAR,
                features = listOf(
                    Feature("early", start = 20, end = 25),
                    Feature("late", start = 920, end = 926),
                ),
            )
            val map = PlasmidMapPanel(SeqDocument(seq))
            map.setSize(320, 260)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            map.setViewportPositionForTest(520, 0)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val viewport = map.viewportPositionForTest()
            val extent = map.viewportExtentForTest()
            val late = map.featureLabelBoundsForTest("late")

            assertTrue(late != null, "expected a label for the late feature")
            assertTrue(late.x >= viewport.x)
            assertTrue(late.maxX <= viewport.x + extent.width)
        }
    }

    @Test
    fun clickingZoomedLinearLabelMovesViewportTowardTheFeatureSpan() {
        SwingUtilities.invokeAndWait {
            val lateFeature = Feature("late", start = 920, end = 926)
            val seq = Seq(
                bases = "ACGT".repeat(300),
                topology = Topology.LINEAR,
                features = listOf(
                    Feature("early", start = 20, end = 25),
                    lateFeature,
                ),
            )
            val content = InstaGeneContent()
            content.doc.loadSequence(seq)
            val map = content.plasmidMapPanel
            map.setSize(320, 260)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            map.setViewportPositionForTest(0, 0)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val before = map.viewportPositionForTest()
            val hit = map.featureLabelHitCenterForTest(lateFeature.name)

            assertTrue(hit != null, "expected a clickable label for the late feature")
            click(canvas, hit)
            repeat(40) { map.advanceViewportAnimationForTest() }

            assertTrue(map.viewportPositionForTest().x > before.x, "expected label click to scroll toward the late feature")
            assertEquals(lateFeature.start, content.doc.selectionStart)
            assertEquals(lateFeature.end, content.doc.selectionEnd)
        }
    }

    @Test
    fun mouseWheelScrollsTheZoomedMapViewport() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(Seq(bases = "ACGT".repeat(300), topology = Topology.LINEAR)))
            map.setSize(320, 260)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            val before = map.viewportPositionForTest()
            val event = MouseWheelEvent(
                canvas,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                120,
                120,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1,
                2,
            )
            canvas.dispatchEvent(event)
            repeat(40) { map.advanceViewportAnimationForTest() }
            assertTrue(map.viewportPositionForTest().y > before.y, "expected wheel input to scroll down")
        }
    }

    @Test
    fun restrictionSiteToggleInvalidatesAndHidesTheMapLayer() {
        SwingUtilities.invokeAndWait {
            val doc = SeqDocument(SeqIO.Samples.PUC19_MCS)
            doc.addEnzyme(Enzymes.require("EcoRI"))
            val map = PlasmidMapPanel(doc)
            map.setSize(500, 500)
            map.doLayout()
            map.ensureStaticMapImageSizeForTest()
            val firstRender = map.staticMapRenderCountForTest()
            map.showRestrictionSites.doClick()
            map.ensureStaticMapImageSizeForTest()
            assertTrue(map.staticMapRenderCountForTest() > firstRender)
            assertFalse(map.showRestrictionSites.isSelected)
        }
    }

    @Test
    fun labelsFadedOutAtTheViewportEdgeDoNotExposeHitTargets() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 18).map { index ->
                Feature("edge-feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.setSize(340, 300)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)
            val faded = map.featureLabelAlphasForTest().entries.firstOrNull { it.value < 0.18f }

            assertTrue(faded != null, "expected at least one label to fade near or outside the viewport")
            assertTrue(map.featureLabelHitCenterForTest(faded.key) == null, "faded-out labels should not be clickable")
        }
    }

    @Test
    fun plasmidFeatureLabelStripeUsesTheFeatureColor() {
        SwingUtilities.invokeAndWait {
            val custom = Feature("custom-color", start = 10, end = 18, color = "#123456")
            val features = listOf(custom) + (1 until 18).map { index ->
                Feature("stripe-feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.setSize(340, 300)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            canvas.paint(BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics)

            assertEquals(Color.decode("#123456"), map.featureLabelStripeColorsForTest().getValue(custom.name))
        }
    }

    @Test
    fun viewportAnimationReusesTheStaticMapLayer() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 80).map { index ->
                Feature("cached-feature-$index", start = index * 12, end = index * 12 + 8)
            }
            val map = PlasmidMapPanel(SeqDocument(Seq(bases = "ACGT".repeat(300), topology = Topology.LINEAR, features = features)))
            map.setSize(320, 260)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            map.ensureStaticMapImageSizeForTest()
            val renderCount = map.staticMapRenderCountForTest()

            val event = MouseWheelEvent(
                canvas,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                120,
                120,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1,
                3,
            )
            canvas.dispatchEvent(event)
            repeat(8) {
                map.advanceViewportAnimationForTest()
                map.ensureStaticMapImageSizeForTest()
            }

            assertEquals(renderCount, map.staticMapRenderCountForTest(), "viewport animation should reuse the cached static map")
        }
    }

    @Test
    fun themeRefreshInvalidatesTheStaticMapLayer() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(Seq(bases = "ACGT".repeat(300), topology = Topology.CIRCULAR)))
            map.setSize(320, 260)
            map.doLayout()
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            val graphics = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).graphics
            try {
                canvas.paint(graphics)
                assertEquals(1, map.staticMapRenderCountForTest())

                canvas.paint(graphics)
                assertEquals(1, map.staticMapRenderCountForTest())

                map.refreshTheme()
                canvas.paint(graphics)
                assertEquals(2, map.staticMapRenderCountForTest())
            } finally {
                graphics.dispose()
            }
        }
    }

    @Test
    fun zoomedStaticMapCacheUsesPhysicalCanvasPixels() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(Seq(bases = "ACGT".repeat(300), topology = Topology.CIRCULAR)))
            map.setSize(320, 260)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            val cacheSize = map.ensureStaticMapImageSizeForTest()

            assertEquals(canvas.width, cacheSize.width)
            assertEquals(canvas.height, cacheSize.height)
            assertTrue(cacheSize.width > 320, "zoomed static cache should not be limited to logical map width")
        }
    }

    @Test
    fun clippedViewportRepaintKeepsFeatureLabelHitTargets() {
        SwingUtilities.invokeAndWait {
            val features = (0 until 18).map { index ->
                Feature("clip-feature-$index", start = index * 15, end = index * 15 + 9)
            }
            val map = PlasmidMapPanel(SeqDocument(circular.copy(features = features)))
            map.setSize(340, 300)
            map.doLayout()
            map.setZoomPercent(300)
            val canvas = map.canvasForTest()
            canvas.setSize(canvas.preferredSize)
            val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
            canvas.paint(image.graphics)
            val targetName = map.featureLabelAlphasForTest().entries.firstOrNull { it.value >= 0.18f }?.key
                ?: error("expected at least one visible label")
            val target = map.featureLabelHitCenterForTest(targetName)

            assertTrue(target != null, "expected a feature label hit target before clipped repaint")
            val graphics = image.createGraphics()
            try {
                graphics.clip = Rectangle(0, 0, 12, 12)
                canvas.paint(graphics)
            } finally {
                graphics.dispose()
            }

            assertTrue(map.featureLabelHitCenterForTest(targetName) != null, "clipped repaint should not erase label hit targets")
        }
    }

    @Test
    fun pngExportIsIndependentFromInteractiveZoomAndRestoresTheViewportMode() {
        SwingUtilities.invokeAndWait {
            val map = PlasmidMapPanel(SeqDocument(circular))
            map.setZoomPercent(600)
            val output = File.createTempFile("instagene-map-", ".png")
            try {
                map.exportPng(output, 220, 180)
                assertEquals(600, map.zoomPercent)
                assertEquals(220, ImageIO.read(output).width)
                assertEquals(180, ImageIO.read(output).height)
            } finally {
                output.delete()
            }
        }
    }
}
