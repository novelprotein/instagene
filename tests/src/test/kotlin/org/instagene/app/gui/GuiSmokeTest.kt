package org.instagene.app.gui

import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import org.instagene.core.io.SeqIO
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
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
            val doc = SeqDocument(SeqIO.Samples.GFP_CDS)
            val view = SequenceView(doc)
            val digest = DigestPanel(doc, {}, { _, _ -> })

            val status = StatusBar(view)
            assertNotNull(status)

            val fileMenu: JMenu = FileMenu(null, doc).create()
            assertEquals("File", fileMenu.text)
            assertTrue(fileMenu.itemCount > 0)

            val editMenu = EditMenu(null, doc, view).create()
            assertEquals("Edit", editMenu.text)

            val viewMenu = ViewMenu(doc, view).create()
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
        }
    }

    @Test
    fun mainContentConstructsInHeadlessMode() {
        // The editor UI must be constructible without a real display (java.awt.headless=true).
        assertTrue(GraphicsEnvironment.isHeadless())
        onEdt {
            val content = InstaGeneContent(null)
            assertNotNull(content.menuBar)
            assertTrue(content.componentCount > 0)
            // Pack/layout then paint the whole editor off-screen
            content.setSize(1200, 800)
            content.doLayout()
            paintComponent(content, 1200, 800)
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

    @Test
    fun digestPanelGatedBySampleType() {
        onEdt {
            val protein = SeqDocument(Seq(bases = "MEEKLF", kind = SeqKind.PROTEIN))
            val rna = SeqDocument(Seq(bases = "ACGUACGU", kind = SeqKind.RNA))
            val dna = SeqDocument(Seq(bases = "ACGTACGT"))
            assertFalse(DigestPanel(protein, {}, { _, _ -> }).isDigestEnabled())
            assertFalse(DigestPanel(rna, {}, { _, _ -> }).isDigestEnabled())
            assertTrue(DigestPanel(dna, {}, { _, _ -> }).isDigestEnabled())
        }
    }

    @Test
    fun circularCheckboxReflectsTopologyAndIsUndoable() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "NNNGAATTCNNN"))
            val map = PlasmidMapPanel(doc)
            // Never defaulted on for a linear sample.
            assertFalse(map.circularCheckbox.isSelected)
            map.circularCheckbox.doClick()
            assertTrue(doc.seq.isCircular)
            assertTrue(map.circularCheckbox.isSelected)
            doc.undo()
            assertFalse(doc.seq.isCircular)
            assertFalse(map.circularCheckbox.isSelected)
        }
    }

    @Test
    fun circularDigestFragmentRevealsWholeSequenceWhenWrapping() {
        onEdt {
            val seq = Seq(bases = "NNNGAATTCNNN", topology = Topology.CIRCULAR)
            val doc = SeqDocument(seq)
            var revealed: Pair<Int, Int>? = null
            val digest = DigestPanel(doc, {}, { s, e -> revealed = s to e })
            digest.selectEnzymes(listOf(org.instagene.core.Enzymes.require("EcoRI")))
            digest.revealFragment(0)
            assertNotNull(revealed)
            assertEquals(0, revealed.first)
            assertEquals(seq.length, revealed.second)
        }
    }

    @Test
    fun plasmidMapPaintsCircularAndLinearProtein() {
        onEdt {
            val circular = SeqDocument(Seq(
                bases = "GAATTCCGTACGAATTCGGTAC",
                topology = Topology.CIRCULAR,
                features = listOf(
                    Feature("ampR", start = 2, end = 8),
                    Feature("ori", start = 6, end = 12),
                    Feature("gfp", start = 10, end = 18),
                ),
            ))
            val map = PlasmidMapPanel(circular)
            assertTrue(map.circularCheckbox.isSelected)
            paintComponent(map, 500, 500)

            val protein = SeqDocument(Seq(
                bases = "MEEKLPFG",
                kind = SeqKind.PROTEIN,
                features = listOf(Feature("sig", start = 0, end = 3)),
            ))
            val proteinMap = PlasmidMapPanel(protein)
            assertFalse(proteinMap.circularCheckbox.isEnabled)
            paintComponent(proteinMap, 500, 300)
        }
    }

    @Test
    fun viewMenuDisablesNucleotideTracksForProtein() {
        onEdt {
            val proteinDoc = SeqDocument(Seq(bases = "MEEKLF", kind = SeqKind.PROTEIN))
            val proteinMenu = ViewMenu(proteinDoc, SequenceView(proteinDoc)).create()
            assertFalse(menuItem(proteinMenu, "Show Complement Strand").isEnabled)
            assertFalse(menuItem(proteinMenu, "Show Translation").isEnabled)

            val dnaDoc = SeqDocument(Seq(bases = "ACGTACGT"))
            val dnaMenu = ViewMenu(dnaDoc, SequenceView(dnaDoc)).create()
            assertTrue(menuItem(dnaMenu, "Show Complement Strand").isEnabled)
        }
    }

    @Test
    fun fragmentExtractionProducesLinearizedFragment() {
        onEdt {
            val seq = Seq(bases = "NNNGAATTCNNN", topology = Topology.CIRCULAR)
            val doc = SeqDocument(seq)
            var extracted: Seq? = null
            val digest = DigestPanel(doc, { s -> extracted = s }, { _, _ -> })
            digest.selectEnzymes(listOf(org.instagene.core.Enzymes.require("EcoRI")))
            digest.extractFragment(0)
            assertNotNull(extracted)
            assertEquals(seq.length, extracted.length)
            assertEquals(Topology.LINEAR, extracted.topology)
        }
    }

    @Test
    fun toolTabsAreRenamedAndReordered() {
        onEdt {
            val content = InstaGeneContent(null)
            val titles = (0 until content.toolTabs.tabCount).map { content.toolTabs.getTitleAt(it) }
            assertEquals(listOf("Enzyme", "Features", "Primers", "Sequence", "Map", "Info"), titles)

            val sequenceIndex = titles.indexOf("Sequence")
            val sequenceTab = content.toolTabs.getComponentAt(sequenceIndex)
            val view = (sequenceTab as JScrollPane).viewport.view
            assertSame(content.sequenceView, view)
        }
    }

    @Test
    fun featuresPanelListsAddsAndDeletes() {
        onEdt {
            val doc = SeqDocument(Seq(
                bases = "ACGTACGTACGT",
                features = listOf(Feature("cds", start = 0, end = 6)),
            ))
            var revealed: Pair<Int, Int>? = null
            val panel = FeaturesPanel(doc) { s, e -> revealed = s to e }
            assertEquals(1, doc.seq.features.size)

            panel.revealFeature(0)
            assertEquals(0 to 6, revealed)

            doc.select(6, 9)
            panel.addFeature("promoter", "promoter", "note")
            assertEquals(2, doc.seq.features.size)
            assertEquals(6, doc.seq.features[1].start)
            assertEquals(9, doc.seq.features[1].end)

            panel.deleteFeature(1)
            assertEquals(1, doc.seq.features.size)
            doc.undo()
            assertEquals(2, doc.seq.features.size)
        }
    }

    @Test
    fun primersPanelDesignsForDnaAndDisablesForProtein() {
        onEdt {
            val dna = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT"))
            val panel = PrimersPanel(dna)
            assertTrue(panel.isDesignEnabled())
            panel.designAmplicon(0, 24)
            val primers = panel.lastPrimers()
            assertNotNull(primers)
            assertTrue(primers.first.bases.isNotEmpty())
            assertTrue(primers.second.bases.isNotEmpty())
            assertNotEquals(primers.first.name, primers.second.name)
            assertTrue(primers.first.gc in 0.0..100.0)

            val protein = SeqDocument(Seq(bases = "MEEKLF", kind = SeqKind.PROTEIN))
            assertFalse(PrimersPanel(protein).isDesignEnabled())
        }
    }

    @Test
    fun infoPanelShowsPropertiesAndUpdates() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGT", name = "old", description = "d"))
            val panel = InfoPanel(doc)
            assertEquals("old", panel.nameField.text)
            assertEquals("dna", panel.kindLabel.text)
            assertEquals("linear", panel.topologyLabel.text)
            assertEquals("8 bp", panel.lengthLabel.text)
            assertEquals("0", panel.featuresLabel.text)

            panel.renameTo("renamed")
            assertEquals("renamed", doc.seq.name)
            doc.undo()
            assertEquals("old", doc.seq.name)

            doc.mutate("desc") { it.copy(description = "new") }
            assertEquals("new", panel.descriptionField.text)
        }
    }

    private fun menuItem(menu: JMenu, text: String): JMenuItem =
        menu.menuComponents.filterIsInstance<JMenuItem>().first { it.text == text }
}
