package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.theme.Palette
import org.instagene.app.gui.theme.ThemeManager
import org.instagene.app.gui.tool.sequence.SequenceGraphics
import org.instagene.app.gui.tool.sequence.SequenceView
import org.instagene.core.sequence.Feature
import org.instagene.core.sequence.Seq
import org.instagene.core.sequence.SeqKind
import java.awt.Color
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.JScrollPane
import javax.swing.UIManager
import kotlin.math.pow
import kotlin.test.*

class SequenceEditorRenderingTest {
    private class FocusManager : DefaultKeyboardFocusManager() {
        fun focus(component: Component?) { setGlobalFocusOwner(component) }
    }
    private fun font(view: SequenceView) = SequenceView::class.java.getDeclaredField("baseFont")
        .apply { isAccessible = true }.get(view) as Font

    private fun caretBounds(view: SequenceView): Rectangle {
        val position = SequenceView::class.java.getDeclaredMethod("caretPosition").apply { isAccessible = true }.invoke(view)
        return position.javaClass.getMethod("getCaretBounds").invoke(position) as Rectangle
    }

    private fun trailing(view: SequenceView) = caretBounds(view).x == view.xCoordinate(view.indexAt(view.xCoordinate(240), 10))

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(rgb: Int): Double {
            val color = Color(rgb)
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return linear(color.red) * 0.2126 + linear(color.green) * 0.7152 + linear(color.blue) * 0.0722
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun key(view: SequenceView, code: Int, shift: Boolean = false) {
        val event = KeyEvent(view, KeyEvent.KEY_PRESSED, 0,
            if (shift) InputEvent.SHIFT_DOWN_MASK else 0, code, KeyEvent.CHAR_UNDEFINED)
        view.keyListeners.forEach { it.keyPressed(event) }
    }

    private fun mouse(view: SequenceView, id: Int, x: Int, y: Int, shift: Boolean = false) {
        val event = MouseEvent(view, id, 0,
            InputEvent.BUTTON1_DOWN_MASK or (if (shift) InputEvent.SHIFT_DOWN_MASK else 0),
            x, y, 1, false, MouseEvent.BUTTON1)
        when (id) {
            MouseEvent.MOUSE_PRESSED -> view.mouseListeners.forEach { it.mousePressed(event) }
            MouseEvent.MOUSE_DRAGGED -> view.mouseMotionListeners.forEach { it.mouseDragged(event) }
            MouseEvent.MOUSE_RELEASED -> view.mouseListeners.forEach { it.mouseReleased(event) }
        }
    }

    private fun render(view: SequenceView, scale: Double = 1.0): BufferedImage {
        val image = BufferedImage((view.width * scale).toInt(), (view.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { g.scale(scale, scale); view.paint(g) } finally { g.dispose() }
        return image
    }

    @Test fun plainClicksPaintTheCaretAtTheTrailingEdgeAndInsertThere() = SwingUtilities.invokeAndWait {
        val previousFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focus = FocusManager()
        KeyboardFocusManager.setCurrentKeyboardFocusManager(focus)
        val blink = SequenceView::class.java.getDeclaredField("caretVisible").apply { isAccessible = true }
        try {
            for (theme in listOf("FlatLightLaf", "FlatDarkLaf")) for (mode in SequenceGraphics.entries) for (size in listOf(14, 24)) {
                assertTrue(ThemeManager.apply(theme))
                val doc = SeqDocument(Seq(bases = "A".repeat(1000)))
                val view = SequenceView(doc)
                try {
                    view.graphicsMode = mode
                    view.setFontSize(size)
                    val pane = JScrollPane(view)
                    pane.setSize(900, 90)
                    pane.doLayout()
                    pane.viewport.doLayout()
                    focus.focus(view)
                    assertTrue(view.isFocusOwner, "The rendering test must exercise the focused caret path")
                    val columns = view.indexAt(view.xCoordinate(240), 10)
                    val cell = view.xCoordinate(1) - view.xCoordinate(0)
                    for (length in listOf(columns, columns + 7)) {
                        doc.mutate("fixture") { it.copy(bases = "A".repeat(length)) }
                        view.setSize(pane.viewport.extentSize.width, view.preferredSize.height)
                        for (clickX in listOf(view.xCoordinate(columns) - 1, view.xCoordinate(columns) + 5)) {
                            blink.setBoolean(view, false)
                            mouse(view, MouseEvent.MOUSE_PRESSED, clickX, 11)
                            assertEquals(columns, doc.caret)
                            val bounds = caretBounds(view)
                            assertEquals(view.xCoordinate(columns), bounds.x)
                            assertEquals(10, bounds.y)
                            assertTrue(bounds.x + bounds.width <= view.width, "The caret must fit inside the viewport")
                            assertTrue(blink.getBoolean(view), "A click must restart the caret's visible blink phase")
                            SequenceView::class.java.getDeclaredMethod("scrollCaretIntoView")
                                .apply { isAccessible = true }.invoke(view)
                            assertEquals(0, pane.viewport.viewPosition.y, "Trailing-edge clicks must not scroll to the next row")
                            for (scale in listOf(1.0, 1.25, 1.5)) {
                                blink.setBoolean(view, false)
                                val off = render(view, scale)
                                blink.setBoolean(view, true)
                                val on = render(view, scale)
                                val left = kotlin.math.ceil(bounds.x * scale).toInt()
                                val right = kotlin.math.floor((bounds.x + bounds.width) * scale).toInt()
                                val top = kotlin.math.ceil(bounds.y * scale).toInt() + 1
                                val bottom = kotlin.math.floor((bounds.y + bounds.height) * scale).toInt() - 1
                                // Glyphs can overhang their advance: measure contrast against the blank
                                // row background, rather than against a letter covered by the caret.
                                val rowBackground = off.getRGB(left, top)
                                for (x in left until right) for (y in top until bottom) {
                                    assertNotEquals(off.getRGB(x, y), on.getRGB(x, y), "Missing caret pixel at $x,$y, scale=$scale")
                                    assertTrue(contrast(on.getRGB(x, y), rowBackground) >= 4.5,
                                        "Caret contrast at $x,$y mode=$mode size=$size scale=$scale: on=${Color(on.getRGB(x, y))}, background=${Color(rowBackground)}, bounds=$bounds")
                                }
                                if (mode == SequenceGraphics.DETAILED && size == 24 && length > columns && scale == 1.25 && clickX == view.xCoordinate(columns) - 1) {
                                    val previews = Path.of("build", "reports", "sequence-editor")
                                    Files.createDirectories(previews)
                                    ImageIO.write(on, "png", previews.resolve("focused-trailing-caret-$theme.png").toFile())
                                }
                            }
                        }
                        mouse(view, MouseEvent.MOUSE_PRESSED, view.xCoordinate(columns) - cell * 3 / 4, 11)
                        assertEquals(columns - 1, doc.caret)
                        assertEquals(view.xCoordinate(columns - 1), caretBounds(view).x)
                        mouse(view, MouseEvent.MOUSE_PRESSED, view.xCoordinate(columns) - 1, 11)
                        view.insertBases("C")
                        assertEquals("A".repeat(columns) + "C" + "A".repeat(length - columns), doc.seq.bases)
                        if (length > columns) {
                            val lastLength = doc.seq.length
                            doc.moveCaret(lastLength)
                            val finalBounds = caretBounds(view)
                            mouse(view, MouseEvent.MOUSE_PRESSED, finalBounds.x - 1, finalBounds.y + 1)
                            assertEquals(lastLength, doc.caret)
                            assertEquals(finalBounds, caretBounds(view), "The partial final row must allow a trailing caret too")
                        }
                    }
                } finally { focus.focus(null); view.dispose() }
            }
        } finally {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager)
            ThemeManager.apply(ThemeManager.DEFAULT_THEME)
        }
    }

    @Test fun biologicalColorsRemainUnchangedForEverySequenceKind() = SwingUtilities.invokeAndWait {
        for ((kind, bases) in listOf(SeqKind.DNA to "ACGTN", SeqKind.RNA to "ACGUN", SeqKind.PROTEIN to "MWKDE")) {
            val doc = SeqDocument(Seq(bases = bases, kind = kind))
            val view = SequenceView(doc)
            try {
                view.setSize(900, 200)
                view.setFontSize(24)
                view.previewRange = 0 to bases.length
                val cell = view.xCoordinate(1) - view.xCoordinate(0)
                for (selected in listOf(false, true)) {
                    if (selected) doc.selectAll()
                    val image = render(view)
                    bases.forEachIndexed { index, base ->
                        val pixels = image.getRGB(view.xCoordinate(index), 10, cell, view.getFontMetrics(font(view)).height, null, 0, cell)
                        assertTrue(pixels.any { it == Palette.charColor(base, kind).rgb }, "$kind $base selected=$selected")
                    }
                }
            } finally { view.dispose() }
        }
    }

    @Test fun neutralTextFallsBackWhenThemeColorsDoNotContrast() = SwingUtilities.invokeAndWait {
        try {
            for (theme in listOf("FlatLightLaf", "FlatDarkLaf")) {
                assertTrue(ThemeManager.apply(theme))
                // Deliberately supply unreadable theme text to exercise the fallback, including the gutter.
                UIManager.put("Label.foreground", Palette.BACKGROUND)
                UIManager.put("Label.disabledForeground", Palette.BACKGROUND)
                val doc = SeqDocument(Seq(bases = "ATGTAA", features = listOf(Feature("Neutral label", "CDS", 0, 6, color = "#FFFFFF"))))
                val view = SequenceView(doc)
                try {
                    view.setSize(900, 300)
                    view.setFontSize(24)
                    view.showTranslation = true
                    view.previewRange = 0 to 6
                    view.selectFeature(doc.seq.features.single())
                    doc.selectAll()
                    val image = render(view)
                    val cell = view.xCoordinate(1) - view.xCoordinate(0)
                    val height = view.getFontMetrics(font(view)).height
                    for (track in 1..2) {
                        val x = view.xCoordinate(if (track == 2) 1 else 0)
                        val pixels = image.getRGB(x, 10 + track * height, cell, height, null, 0, cell)
                        val background = pixels.first()
                        val foreground = listOf(Color.WHITE.rgb, Color.BLACK.rgb).maxBy { contrast(it, background) }
                        assertTrue(pixels.any { it == foreground }, "$theme track $track must use contrasting text")
                        assertTrue(contrast(foreground, background) >= 4.5)
                    }
                    val gutter = image.getRGB(10, 10, view.xCoordinate(0) - 20, height, null, 0, view.xCoordinate(0) - 20)
                    val expected = listOf(Color.WHITE.rgb, Color.BLACK.rgb).maxBy { contrast(it, Palette.BACKGROUND.rgb) }
                    assertTrue(gutter.any { it == expected }, "$theme position numbers must contrast")
                    val label = assertNotNull(view.featureLabelHitCenterForTest("Neutral label"))
                    val labelPixels = image.getRGB(label.first - 20, label.second - 8, 40, 16, null, 0, 40)
                    assertTrue(labelPixels.any { it == Color.BLACK.rgb }, "White-filled annotation labels must use dark text")
                } finally { view.dispose() }
            }
        } finally { ThemeManager.apply(ThemeManager.DEFAULT_THEME) }
    }

    @Test fun mouseAndKeyboardKeepWrappedEndpointsOnTheIntendedRow() = SwingUtilities.invokeAndWait {
        for (mode in SequenceGraphics.entries) {
            val doc = SeqDocument(Seq(bases = "ACGT".repeat(200), features = listOf(Feature("gene", "CDS", 0, 20))))
            val view = SequenceView(doc)
            try {
                view.graphicsMode = mode
                view.setSize(900, 600)
                render(view)
                val columns = view.indexAt(view.xCoordinate(240), 10)
                val x0 = view.xCoordinate(0)
                val end = view.xCoordinate(columns)
                val cell = view.xCoordinate(1) - x0
                val nextY = (11..600).first { view.indexAt(x0, it) == columns }

                mouse(view, MouseEvent.MOUSE_PRESSED, end - cell * 3 / 4, 11)
                assertEquals(columns - 1, doc.caret)
                mouse(view, MouseEvent.MOUSE_DRAGGED, end - 1, 11)
                assertEquals(1, doc.selectedBases.length)
                assertTrue(trailing(view))
                mouse(view, MouseEvent.MOUSE_DRAGGED, end + 20, 11)
                assertEquals(columns, doc.caret)
                assertTrue(trailing(view))
                mouse(view, MouseEvent.MOUSE_DRAGGED, x0 + cell, nextY)
                assertEquals(columns + 1, doc.caret)
                assertFalse(trailing(view))
                mouse(view, MouseEvent.MOUSE_DRAGGED, end - cell, 11)
                assertEquals(columns - 1, doc.caret)
                mouse(view, MouseEvent.MOUSE_RELEASED, end - cell, 11)

                mouse(view, MouseEvent.MOUSE_PRESSED, x0 + cell, nextY)
                mouse(view, MouseEvent.MOUSE_DRAGGED, end - 1, 11)
                assertEquals(columns to columns + 1, doc.selectionStart to doc.selectionEnd)
                assertTrue(trailing(view), "Reverse dragging must preserve the first row's endpoint")
                mouse(view, MouseEvent.MOUSE_RELEASED, end - 1, 11)
                doc.moveCaret(columns - 1)
                mouse(view, MouseEvent.MOUSE_PRESSED, end - 1, 11, shift = true)
                assertEquals(1, doc.selectedBases.length)
                assertTrue(trailing(view))

                doc.moveCaret(0)
                key(view, KeyEvent.VK_END, shift = true)
                assertEquals(columns, doc.selectionEnd)
                assertEquals(columns, doc.selectedBases.length)
                assertTrue(trailing(view))
                key(view, KeyEvent.VK_END)
                assertEquals(columns, doc.caret, "Repeated End must stay on this row")
                if (mode == SequenceGraphics.DETAILED) {
                    val expected = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                    val g = expected.createGraphics()
                    try {
                        g.color = Palette.BACKGROUND; g.fillRect(0, 0, 1, 1)
                        g.color = Palette.EDITOR_ACTIVE_ROW; g.fillRect(0, 0, 1, 1)
                    } finally { g.dispose() }
                    assertEquals(expected.getRGB(0, 0), render(view).getRGB(x0 + 1, 11),
                        "The active-row highlight must stay on the row where End was pressed")
                }
                key(view, KeyEvent.VK_HOME)
                assertEquals(0, doc.caret)
                key(view, KeyEvent.VK_END)
                key(view, KeyEvent.VK_RIGHT)
                assertEquals(columns + 1, doc.caret)
                key(view, KeyEvent.VK_LEFT)
                assertEquals(columns, doc.caret)
                assertFalse(trailing(view), "Left from the next row stays on that row's start")
                key(view, KeyEvent.VK_LEFT)
                assertEquals(columns - 1, doc.caret)
                key(view, KeyEvent.VK_END)
                key(view, KeyEvent.VK_DOWN)
                assertEquals(columns * 2, doc.caret)
                assertTrue(trailing(view))
                key(view, KeyEvent.VK_UP)
                assertEquals(columns, doc.caret)
                key(view, KeyEvent.VK_HOME, shift = true)
                assertEquals(columns, doc.selectedBases.length)

                doc.moveCaret(columns)
                assertFalse(trailing(view), "External document selection resets visual affinity")
                key(view, KeyEvent.VK_END)
                view.setSize(600, 600)
                assertFalse(trailing(view), "Reflow must discard affinity for the previous column count")
            } finally { view.dispose() }
        }
    }

    @Test fun finalAndEmptyRowsClampSelectionAndNavigation() = SwingUtilities.invokeAndWait {
        for (length in listOf(0, 1, 1000)) {
            val doc = SeqDocument(Seq(bases = "A".repeat(length)))
            val view = SequenceView(doc)
            try {
                view.setSize(900, 600)
                val columns = view.indexAt(view.xCoordinate(240), 10)
                doc.moveCaret(length)
                key(view, KeyEvent.VK_END, shift = true)
                assertEquals(length, doc.caret)
                key(view, KeyEvent.VK_HOME)
                val start = if (columns == 0 || length == 0) 0 else (length - 1) / columns * columns
                assertEquals(start, doc.caret)
                key(view, KeyEvent.VK_END, shift = true)
                assertEquals(length - start, doc.selectedBases.length)
                assertEquals(length, view.indexAt(view.xCoordinate(240), view.preferredSize.height))
                render(view)
            } finally { view.dispose() }
        }
        val doc = SeqDocument(Seq(bases = "A".repeat(1000)))
        val view = SequenceView(doc)
        try {
            view.setSize(900, 600)
            val columns = view.indexAt(view.xCoordinate(240), 10)
            doc.mutate("exact row") { Seq(bases = "A".repeat(columns)) }
            doc.moveCaret(0)
            key(view, KeyEvent.VK_END, shift = true)
            assertEquals(columns, doc.selectedBases.length)
            key(view, KeyEvent.VK_HOME)
            assertEquals(0, doc.caret)
        } finally { view.dispose() }
    }

    @Test fun sequenceColorsAndNeutralContrastSurviveHighlightsAndScaling() = SwingUtilities.invokeAndWait {
        try {
            for (theme in listOf("FlatLightLaf", "FlatDarkLaf")) {
                assertTrue(ThemeManager.apply(theme))
                val doc = SeqDocument(Seq(bases = "ATGTAA".repeat(40)))
                val view = SequenceView(doc)
                try {
                    view.setSize(900, 500)
                    view.setFontSize(24)
                    view.showTranslation = true
                    for (mode in SequenceGraphics.entries) {
                        view.graphicsMode = mode
                        for (selected in listOf(false, true)) {
                            view.previewRange = if (selected) 0 to 6 else null
                            if (selected) doc.select(0, 6) else doc.moveCaret(0)
                            for (scale in listOf(1.0, 1.25, 1.5)) {
                                val image = render(view, scale)
                                val fm = view.getFontMetrics(font(view))
                                val cell = view.xCoordinate(1) - view.xCoordinate(0)
                                for (track in 0..2) {
                                    val x = view.xCoordinate(if (track == 2) 1 else 0)
                                    val top = 10 + track * fm.height
                                    val pixels = image.getRGB((x * scale).toInt(), (top * scale).toInt(),
                                        (cell * scale).toInt(), (fm.height * scale).toInt(), null, 0, (cell * scale).toInt())
                                    if (track == 0) {
                                        assertTrue(pixels.any { it == Palette.charColor('A', doc.seq.kind).rgb },
                                            "$theme: primary colors, selected=$selected, scale=$scale")
                                    } else {
                                        val background = pixels.first()
                                        val preferred = Palette.TEXT.rgb
                                        val foreground = if (contrast(preferred, background) >= 4.5) preferred else
                                            listOf(Color.WHITE.rgb, Color.BLACK.rgb).maxBy { contrast(it, background) }
                                        assertTrue(pixels.any { it == foreground }, "$theme: neutral track $track, selected=$selected, scale=$scale")
                                        assertTrue(contrast(foreground, background) >= 4.5)
                                    }
                                    assertTrue(pixels.any { it != Palette.TEXT.rgb && it != pixels.first() }, "Glyph edges should be antialiased")
                                }
                                // A blank corner in the start-codon cell proves selection paints over codon shading.
                                val expected = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                                val g = expected.createGraphics()
                                try {
                                    g.color = Palette.BACKGROUND; g.fillRect(0, 0, 1, 1)
                                    if (mode == SequenceGraphics.DETAILED) { g.color = Palette.EDITOR_ACTIVE_ROW; g.fillRect(0, 0, 1, 1) }
                                    if (selected) { g.color = Palette.translucent(Palette.ACCENT, 24); g.fillRect(0, 0, 1, 1) }
                                    g.color = Palette.START_CODON; g.fillRect(0, 0, 1, 1)
                                    if (selected) { g.color = Palette.SELECTION; g.fillRect(0, 0, 1, 1) }
                                } finally { g.dispose() }
                                assertEquals(expected.getRGB(0, 0), image.getRGB(((view.xCoordinate(0) + 1) * scale).toInt(), ((10 + fm.height * 2 + 1) * scale).toInt()))
                            }
                        }
                    }
                    view.graphicsMode = SequenceGraphics.DETAILED
                    val previews = Path.of("build", "reports", "sequence-editor")
                    Files.createDirectories(previews)
                    for (scale in listOf(1.0, 1.25, 1.5)) {
                        ImageIO.write(render(view, scale), "png", previews.resolve("$theme-$scale.png").toFile())
                    }
                } finally { view.dispose() }
            }
        } finally { ThemeManager.apply(ThemeManager.DEFAULT_THEME) }
    }

    @Test fun proportionalOverridesCannotChangeSequenceSpacing() = SwingUtilities.invokeAndWait {
        val saved = UIManager.getFont("InstaGene.sequenceFont")
        UIManager.put("InstaGene.sequenceFont", Font(Font.SERIF, Font.BOLD, 19))
        val view = SequenceView(SeqDocument(Seq(bases = "ACGT")))
        try {
            assertEquals(Font.MONOSPACED, font(view).family)
            assertEquals(19, view.fontSize())
            for (size in listOf(14, 22)) {
                UIManager.put("InstaGene.sequenceFont", Font(Font.SERIF, Font.BOLD, size))
                view.refreshTheme()
                assertEquals(Font.MONOSPACED, font(view).family)
                assertEquals(size, view.fontSize())
                val fm = view.getFontMetrics(font(view))
                assertEquals(1, "ACGTURYSWKMBDHVNX*-IL".map { fm.charWidth(it) }.distinct().size)
                view.setFontSize(26)
                view.refreshTheme()
                assertEquals(26, view.fontSize())
                view.resetZoom()
                assertEquals(size, view.fontSize())
            }
        } finally { UIManager.put("InstaGene.sequenceFont", saved); view.dispose() }
    }
}
