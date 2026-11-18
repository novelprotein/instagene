package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.theme.ThemeManager
import org.instagene.app.gui.tool.map.CircularMapLayout
import org.instagene.app.gui.tool.map.MapExportOptions
import org.instagene.app.gui.tool.map.PlasmidMapPanel
import org.instagene.core.enzyme.Enzymes
import org.instagene.core.sequence.Feature
import org.instagene.core.sequence.Seq
import org.instagene.core.sequence.Topology
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.test.*

class CircularMapTextLayoutTest {
    private fun metrics(size: Int = 14) = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().let { g ->
        try { g.getFontMetrics(Font(Font.SANS_SERIF, Font.PLAIN, size)) } finally { g.dispose() }
    }

    private fun features(count: Int) = (0 until count).map { index ->
        val feature = Feature("feature-$index-with-a-very-long-description-that-must-be-shortened", start = index * 300, end = index * 300 + 40)
        CircularMapLayout.FeatureInput(feature, feature.name, 0)
    }

    private fun assertClear(plan: CircularMapLayout.Plan, width: Int, height: Int) {
        plan.texts.forEachIndexed { index, first ->
            assertTrue(Rectangle(0, 0, width, height).contains(first.bounds), "Clipped ${first.kind}: ${first.bounds}")
            plan.texts.drop(index + 1).forEach { second ->
                assertFalse(first.bounds.intersects(second.bounds), "${first.kind} '${first.text}' ${first.bounds} overlaps ${second.kind} '${second.text}' ${second.bounds}")
            }
        }
    }

    @Test fun coordinatesAreOutsideTheBackboneAndTheOriginIsRetained() {
        val fm = metrics()
        val plan = CircularMapLayout.create(900, 900, 600, 10_000, emptyList(), emptyList(), "Plasmid",
            listOf("10000 bp dna circular", "GC 50.0%"), true, false, fm, fm, fm)
        val coordinates = plan.texts.filter { it.kind == CircularMapLayout.Kind.ORIGIN || it.kind == CircularMapLayout.Kind.COORDINATE }
        assertTrue(coordinates.size > 3)
        assertEquals(1, coordinates.count { it.kind == CircularMapLayout.Kind.ORIGIN })
        coordinates.forEach { item ->
            val x = plan.center.x.coerceIn(item.bounds.x, item.bounds.x + item.bounds.width)
            val y = plan.center.y.coerceIn(item.bounds.y, item.bounds.y + item.bounds.height)
            assertTrue(hypot((x - plan.center.x).toDouble(), (y - plan.center.y).toDouble()) > plan.radius)
        }
        assertClear(plan, 900, 900)
    }

    @Test fun denseFeaturesSitesCaptionsAndKeyHaveDisjointMeasuredBounds() {
        for (size in listOf(14, 24)) {
            val fm = metrics(size)
            val inputs = features(30)
            val sites = (0 until 16).map { CircularMapLayout.SiteInput(it + 1, "restriction-enzyme-$it-near-the-origin") }
            val plan = CircularMapLayout.create(800, 3200, 600, 10_000, inputs, sites,
                "A very long title that must remain separate from the labels and legend",
                listOf("10000 bp dna circular", "GC 50.0%", "30 features · 16 sites"), true, true, fm, fm, fm)
            assertClear(plan, 800, 3200)
            assertEquals(30, plan.texts.count { it.kind == CircularMapLayout.Kind.FEATURE })
            assertEquals(30, plan.texts.count { it.kind == CircularMapLayout.Kind.KEY })
            assertEquals(16, plan.texts.count { it.kind == CircularMapLayout.Kind.SITE })
            val key = assertNotNull(plan.keyBounds)
            plan.texts.forEach { item ->
                if (item.kind == CircularMapLayout.Kind.KEY) assertTrue(key.contains(item.bounds))
                else assertFalse(key.intersects(item.bounds))
            }
            assertTrue(plan.texts.any { it.text.endsWith("...") })
            plan.texts.filter { it.kind == CircularMapLayout.Kind.FEATURE }.forEach { item ->
                assertEquals(item.feature!!.name, item.fullText)
                assertTrue(item.text.length > 3, "Use the free canvas rows before reducing a feature name to dots")
            }
        }
    }

    @Test fun truncationFitsItsBudgetAndPreservesFullCodePoints() {
        val fm = metrics()
        for (name in listOf("feature with a long name", "feature-🧬-repeat-🧬-repeat")) {
            for (width in listOf(fm.stringWidth("...") + 1, 50, 100)) {
                val fitted = CircularMapLayout.fitText(fm, name, width)
                assertTrue(fm.stringWidth(fitted) <= width)
                assertTrue(fitted.endsWith("...") || fitted == name)
                assertFalse(fitted.dropLast(3).lastOrNull()?.isHighSurrogate() ?: false)
            }
        }
    }

    @Test fun zoomedCalloutsStayVisibleAndAvoidTheCaptionWhilePanning() {
        val fm = metrics()
        val inputs = features(18)
        val metadata = listOf("10000 bp dna circular", "GC 50.0%", "18 features · 0 sites")
        val initial = CircularMapLayout.create(800, 1800, 600, 10_000, inputs, emptyList(), "Map", metadata,
            true, true, fm, fm, fm)
        for (viewport in listOf(Rectangle(290, initial.center.y - 80, 220, 160), Rectangle(100, 0, 220, 160))) {
            val plan = CircularMapLayout.create(800, 1800, 600, 10_000, inputs, emptyList(), "Map", metadata,
                true, true, fm, fm, fm, viewport = viewport, compactCaption = true)
            assertClear(plan, 800, 1800)
            assertTrue(plan.texts.any { it.kind == CircularMapLayout.Kind.FEATURE && viewport.intersects(it.bounds) })
        }
    }

    @Test fun insufficientExportSpaceProducesActionableGuidance() {
        val fm = metrics(24)
        val error = assertFailsWith<IllegalArgumentException> {
            CircularMapLayout.create(180, 120, 120, 10_000, features(30), emptyList(), "Map", emptyList(), true, true, fm, fm, fm)
        }
        assertEquals(CircularMapLayout.TOO_SMALL, error.message)
    }

    @Test fun failedExportsLeaveTheInteractiveMapIntact() = SwingUtilities.invokeAndWait {
        val output = Files.createTempDirectory("small-circular-export")
        try {
            val map = PlasmidMapPanel(SeqDocument(Seq(bases = "ACGT".repeat(2500), topology = Topology.CIRCULAR,
                features = features(30).map { it.feature })))
            map.setSize(700, 600)
            map.doLayout()
            map.setZoomPercent(300)
            val beforeSize = java.awt.Dimension(map.canvasForTest().size)
            val beforePosition = Point(map.viewportPositionForTest())
            val options = MapExportOptions(width = 180, height = 120, fontSize = 24, showFeatureKey = true)
            val png = output.resolve("small.png").toFile()
            val svg = output.resolve("small.svg").toFile()
            assertEquals(CircularMapLayout.TOO_SMALL, assertFailsWith<IllegalArgumentException> { map.exportPng(png, options) }.message)
            assertEquals(CircularMapLayout.TOO_SMALL, assertFailsWith<IllegalArgumentException> { map.exportSvg(svg, options) }.message)
            assertFalse(png.exists())
            assertFalse(svg.exists())
            assertEquals(300, map.zoomPercent)
            assertEquals(beforeSize, map.canvasForTest().size)
            assertEquals(beforePosition, map.viewportPositionForTest())
        } finally { Files.deleteIfExists(output) }
    }

    @Test fun canvasKeyHoverSelectionAndExportsUseTheMeasuredLayout() = SwingUtilities.invokeAndWait {
        val directory = Files.createTempDirectory("circular-text-layout")
        try {
            for (theme in listOf("FlatLightLaf", "FlatDarkLaf")) {
                ThemeManager.apply(theme)
                val inputs = features(18)
                val doc = SeqDocument(Seq(name = "Circular map with long feature names", bases = "GAATTC" + "ACGT".repeat(2498),
                    topology = Topology.CIRCULAR, features = inputs.map { it.feature }))
                doc.addEnzyme(Enzymes.require("EcoRI"))
                val map = PlasmidMapPanel(doc)
                map.onSelect = { start, end -> doc.select(start, end) }
                map.setSize(700, 600)
                map.doLayout()
                val canvas = map.canvasForTest()
                canvas.size = canvas.preferredSize
                val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try { canvas.paint(g) } finally { g.dispose() }
                val plan = assertNotNull(map.circularLayoutForTest())
                val keyBounds = assertNotNull(plan.keyBounds)
                assertClear(plan, keyBounds.width, plan.mapHeight + keyBounds.height)
                val key = plan.texts.first { it.kind == CircularMapLayout.Kind.KEY }
                val keyFeature = assertNotNull(key.feature)
                val scale = map.backboneDisplayDiameterForTest() / (plan.radius * 2)
                val hit = ((key.bounds.centerX * scale).toInt() to (key.bounds.centerY * scale).toInt())
                val event = MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0, hit.first, hit.second, 0, false)
                assertEquals(key.fullText, canvas.getToolTipText(event))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, hit.first, hit.second, 1, false, MouseEvent.BUTTON1))
                canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, hit.first, hit.second, 1, false, MouseEvent.BUTTON1))
                assertEquals(keyFeature.start to keyFeature.end, doc.selectionStart to doc.selectionEnd)
                val previews = Path.of("build", "reports", "circular-map-layout")
                Files.createDirectories(previews)
                ImageIO.write(image, "png", previews.resolve("$theme.png").toFile())
                val options = MapExportOptions(width = 1200, height = 1400, showFeatureKey = true, showMetadata = true, fontSize = 14)
                val svg = directory.resolve("$theme.svg").toFile()
                val png = directory.resolve("$theme.png").toFile()
                map.exportSvg(svg, options)
                map.exportPng(png, options)
                val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg)
                assertEquals("1200", xml.documentElement.getAttribute("width"))
                assertEquals("1400", xml.documentElement.getAttribute("height"))
                assertTrue(xml.getElementsByTagName("text").length >= inputs.size * 2)
                inputs.forEach { assertTrue(svg.readText().contains(it.name)) }
                assertEquals(1200, ImageIO.read(png).width)
                map.showFeatureKey.doClick()
                val graphics = image.createGraphics()
                try { canvas.paint(graphics) } finally { graphics.dispose() }
                assertNull(map.circularLayoutForTest()?.keyBounds)
            }
        } finally {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
