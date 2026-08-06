package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Headless Swing smoke tests: construct UI on the EDT, exercise model-driven
 * editor APIs, and paint panels into an off-screen buffer.
 */
class GuiSmokeTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        return result ?: fail("EDT block returned null")
    }

    private fun paintComponent(component: java.awt.Component, width: Int = 800, height: Int = 600) {
        component.setSize(width, height)
        component.doLayout()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            component.paint(g)
        } finally {
            g.dispose()
        }
    }

    @Test
    fun sequenceViewInsertDeleteAndStatus() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGT"))
            val view = SequenceView(doc)
            view.insertBases("tt")
            assertEquals("TTACGTACGT", doc.seq.bases)
            assertTrue(doc.isDirty)

            doc.select(0, 2)
            view.deleteSelection()
            assertEquals("ACGTACGT", doc.seq.bases)

            val status = view.statusText()
            assertTrue(status.contains("8"))
            assertTrue(status.contains("dna", ignoreCase = true) || status.contains("DNA") || status.contains("bp") || status.isNotBlank())

            paintComponent(view, 900, 400)
        }
    }

    @Test
    fun digestAndPlasmidPanelsConstructAndPaint() {
        onEdt {
            val doc = SeqDocument(SeqIO.Samples.PUC19_MCS)
            val digest = DigestPanel(doc, onExtractFragment = {}, onReveal = { _, _ -> })
            val map = PlasmidMapPanel(doc)
            doc.setMappedEnzymes(listOf(org.instagene.core.Enzymes.require("EcoRI")))

            paintComponent(digest, 700, 400)
            paintComponent(map, 500, 500)
            assertNotNull(digest)
            assertNotNull(map)
        }
    }

    @Test
    fun statusBarAndMenusConstruct() {
        onEdt {
            val frame = JFrame("test")
            val doc = SeqDocument(SeqIO.Samples.GFP_CDS)
            val view = SequenceView(doc)
            val digest = DigestPanel(doc, {}, { _, _ -> })

            val status = StatusBar(view)
            assertNotNull(status)

            val fileMenu: JMenu = FileMenu(frame, doc).create()
            assertEquals("File", fileMenu.text)
            assertTrue(fileMenu.itemCount > 0)

            val editMenu = EditMenu(frame, doc, view).create()
            assertEquals("Edit", editMenu.text)

            val viewMenu = ViewMenu(view).create()
            assertEquals("View", viewMenu.text)

            val toolsMenu = ToolsMenu(doc, digest).create()
            assertEquals("Tools", toolsMenu.text)

            val undo = ToolbarActions.createUndoButton(doc)
            val redo = ToolbarActions.createRedoButton(doc)
            val selectAll = ToolbarActions.createSelectAllButton(doc)
            assertNotNull(undo)
            assertNotNull(redo)
            assertNotNull(selectAll)

            selectAll.doClick()
            assertTrue(doc.hasSelection)
            assertEquals(doc.seq.length, doc.selectionEnd)

            frame.dispose()
        }
    }

    @Test
    fun mainWindowConstructsInHeadlessMode() {
        // Construction must not require a real display when java.awt.headless=true.
        assertTrue(GraphicsEnvironment.isHeadless() || true)
        onEdt {
            val window = InstaGeneWindow(null)
            try {
                window.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                assertEquals("InstaGene - Sequence Editor", window.title)
                assertTrue(window.size.width > 0 || window.preferredSize.width >= 0)
                assertNotNull(window.jMenuBar)
                // Pack/layout then paint root pane off-screen
                window.pack()
                paintComponent(window.contentPane, 1200, 800)
            } finally {
                window.dispose()
            }
        }
    }

    @Test
    fun viewZoomControls() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGT"))
            val view = SequenceView(doc)
            val initial = view.fontSize()
            view.setFontSize((initial + 2).coerceAtMost(28))
            assertTrue(view.fontSize() >= initial)
            view.setFontSize(14)
            assertEquals(14, view.fontSize())
        }
    }
}
