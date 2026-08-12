package org.instagene.app.gui

import org.instagene.app.gui.document.TextDocument
import org.instagene.app.gui.document.TextEditorView
import org.instagene.app.gui.edit.EditMenu
import org.instagene.app.gui.edit.SequenceEditActions
import org.instagene.app.gui.edit.TextEditActions
import org.instagene.app.gui.enzyme.EnzymeManagerModel
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import org.instagene.core.io.SeqIO
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.app.gui.tool.DigestPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.app.gui.menu.FileMenu
import org.instagene.app.gui.tool.InfoPanel
import org.instagene.app.gui.tool.LibraryPanel
import org.instagene.app.gui.tool.PlasmidMapPanel
import org.instagene.app.gui.tool.PrimersPanel
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.SequenceView
import org.instagene.app.gui.menu.ToolsMenu
import org.instagene.app.gui.menu.ViewMenu
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

    /** Pumps the EDT until the digest panel's cut-count scan has landed. */
    private fun awaitDigestCounts(panel: DigestPanel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            SwingUtilities.invokeAndWait { ready = panel.computedCutCounts() != null }
            if (ready) return
            Thread.sleep(10)
        }
        fail("digest cut counts never became available")
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

            val status = StatusBar(doc, view)
            assertNotNull(status)

            val fileMenu: JMenu = FileMenu(null, doc).create()
            assertEquals("File", fileMenu.text)
            assertTrue(fileMenu.itemCount > 0)

            val editMenu = EditMenu(null, doc, SequenceEditActions(view, doc)).create()
            assertEquals("Edit", editMenu.text)

            val viewMenu = ViewMenu(doc, view).create()
            assertEquals("View", viewMenu.text)

            val toolsMenu = ToolsMenu(doc, digest).create()
            assertEquals("Tools", toolsMenu.text)

            // The actions live in the menus now (no toolbar); clicking an item
            // still drives the model.
            val undoItem = menuItem(editMenu, "Undo")
            assertFalse(undoItem.isEnabled)
            menuItem(editMenu, "Select All").doClick()
            assertTrue(doc.hasSelection)
            assertEquals(doc.seq.length, doc.selectionEnd)
            doc.mutate("insert 1 base") { it.insertAt(0, "A") }
            assertTrue(undoItem.isEnabled)
            assertEquals("Undo insert 1 base", undoItem.text)
            assertTrue(menuItem(editMenu, "Copy").isEnabled)
        }
    }

    @Test
    fun textEditMenuDrivesTheTextEditor() {
        onEdt {
            val doc = TextDocument("hello")
            val view = TextEditorView(doc)
            val actions = TextEditActions(view)
            val editMenu = EditMenu(null, doc, actions).create()

            val undoItem = menuItem(editMenu, "Undo")
            assertFalse(undoItem.isEnabled)
            menuItem(editMenu, "Select All").doClick()
            assertEquals("hello", view.area.selectedText)
            menuItem(editMenu, "Delete").doClick()
            assertEquals("", doc.text)
            assertTrue(doc.isDirty)
            assertTrue(undoItem.isEnabled)
            assertEquals("Undo edit", undoItem.text)
            undoItem.doClick()
            assertEquals("hello", doc.text)
            assertEquals("hello", view.area.text)
            assertFalse(undoItem.isEnabled)

            assertTrue(actions.findNext("ell"))
            assertEquals(1, view.area.selectionStart)
            assertEquals(4, view.area.selectionEnd)
            assertFalse(actions.findNext("nope"))
        }
    }

    @Test
    fun sequenceFindNextSearchesForwardAndReverseComplement() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGT"))
            val view = SequenceView(doc)
            val actions = SequenceEditActions(view, doc)

            doc.moveCaret(2)
            assertTrue(actions.findNext("ACG"))
            assertEquals(4, doc.selectionStart)
            assertEquals(7, doc.selectionEnd)

            doc.moveCaret(0)
            assertTrue(actions.findNext("CGT"))
            assertEquals(1, doc.selectionStart)
            assertEquals(4, doc.selectionEnd)
            assertFalse(actions.findNext("TTT"))
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
            // Lay out and paint the entire editor off-screen.
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
    fun digestPanelDisposesItsBackgroundWorkers() {
        onEdt {
            val panel = DigestPanel(SeqDocument(Seq(bases = "ACGT")), { _: Seq -> }, { _, _ -> })
            assertFalse(panel.isDisposed())
            panel.dispose()
            assertTrue(panel.isDisposed())
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
    fun digestPanelSortsCuttersToTheTop() {
        // Construct on the EDT; the cut-count scan runs on background threads, so
        // poll for it from the test thread before asserting.
        val panel = onEdt {
            val doc = SeqDocument(Seq(bases = "ACGAATTCGGATCCGAATTCACGT"))
            val prefs = Prefs().apply { update { it.copy(digestCuttersOnly = false) } }
            DigestPanel(doc, {}, { _, _ -> }, prefs)
        }
        awaitDigestCounts(panel)
        onEdt {
            // EcoRI cuts twice, BamHI once, NotI never; with cuttersOnly off the
            // whole catalog is shown but the cutters must come first.
            val counts = panel.computedCutCounts()
            assertEquals(2, counts!![org.instagene.core.Enzymes.require("EcoRI")])
            assertEquals(1, counts[org.instagene.core.Enzymes.require("BamHI")])
            assertEquals(0, counts[org.instagene.core.Enzymes.require("NotI")])

            val shown = panel.displayedEnzymes()
            assertTrue(shown.contains(org.instagene.core.Enzymes.require("NotI")))
            assertEquals("EcoRI", shown[0].name)
            assertEquals("BamHI", shown[1].name)
            val ordered = shown.map { counts[it] ?: 0 }
            assertEquals(ordered.sortedDescending(), ordered, "cut counts must be non-increasing down the table")
        }
    }

    @Test
    fun digestPanelCuttersOnlyKeepsOnlyCutters() {
        val panel = onEdt {
            DigestPanel(SeqDocument(Seq(bases = "ACGAATTCGGATCCGAATTCACGT")), {}, { _, _ -> })
        }
        awaitDigestCounts(panel)
        onEdt {
            val shown = panel.displayedEnzymes()
            assertTrue(shown.isNotEmpty())
            assertTrue(shown.all { (panel.computedCutCounts()!![it] ?: 0) > 0 })
            assertEquals("EcoRI", shown.first().name)
        }
    }

    @Test
    fun digestPanelListsIndividualMatchesForSelectedEnzyme() {
        val panel = onEdt {
            DigestPanel(SeqDocument(Seq(bases = "ACGAATTCGGATCCGAATTCACGT")), {}, { _, _ -> })
        }
        awaitDigestCounts(panel)
        onEdt {
            panel.selectEnzymeInTable(org.instagene.core.Enzymes.require("EcoRI"))
            val eco = panel.displayedMatches()
            assertEquals(2, eco.size)
            assertEquals(2, eco[0].recognitionStart)
            assertEquals(14, eco[1].recognitionStart)
            assertEquals(org.instagene.core.Strand.FORWARD, eco[0].strand)

            panel.selectEnzymeInTable(org.instagene.core.Enzymes.require("BamHI"))
            val bam = panel.displayedMatches()
            assertEquals(1, bam.size)
            assertEquals(8, bam[0].recognitionStart)
        }
    }

    @Test
    fun digestPanelRevealsIndividualMatch() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "NNNGAATTCNNN"))
            val prefs = Prefs().apply { update { it.copy(digestCuttersOnly = false) } }
            var revealed: Pair<Int, Int>? = null
            val panel = DigestPanel(doc, {}, { s, e -> revealed = s to e }, prefs)

            panel.selectEnzymeInTable(org.instagene.core.Enzymes.require("EcoRI"))
            assertEquals(1, panel.displayedMatches().size)
            assertEquals(3, panel.displayedMatches()[0].recognitionStart)
            panel.revealMatch(0)
            assertEquals(3 to 9, revealed)
        }
    }

    @Test
    fun plasmidMapPaintsCircularAndLinearProtein() {
        onEdt {
            val circular = SeqDocument(
                Seq(
                    bases = "GAATTCCGTACGAATTCGGTAC",
                    topology = Topology.CIRCULAR,
                    features = listOf(
                        Feature("ampR", start = 2, end = 8),
                        Feature("ori", start = 6, end = 12),
                        Feature("gfp", start = 10, end = 18),
                    ),
                )
            )
            val map = PlasmidMapPanel(circular)
            assertTrue(map.circularCheckbox.isSelected)
            paintComponent(map, 500, 500)

            val protein = SeqDocument(
                Seq(
                    bases = "MEEKLPFG",
                    kind = SeqKind.PROTEIN,
                    features = listOf(Feature("sig", start = 0, end = 3)),
                )
            )
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
            assertEquals(
                listOf("Info", "Map", "Sequence", "Enzyme", "Features", "Primers", "Library", "History"),
                titles,
            )

            val sequenceIndex = titles.indexOf("Sequence")
            val sequenceTab = content.toolTabs.getComponentAt(sequenceIndex)
            val view = (sequenceTab as JScrollPane).viewport.view
            assertSame(content.sequenceView, view)
        }
    }

    @Test
    fun featuresPanelListsAddsAndDeletes() {
        onEdt {
            val doc = SeqDocument(
                Seq(
                    bases = "ACGTACGTACGT",
                    features = listOf(Feature("cds", start = 0, end = 6)),
                )
            )
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

    @Test
    fun infoPanelOffersOpenButtonUntilAFileIsLoaded() {
        onEdt {
            var opened = 0
            val doc = SeqDocument(Seq(bases = "ACGT"))
            val panel = InfoPanel(doc) { opened++ }

            // No file yet: the Open File button is the File-row affordance.
            assertTrue(panel.openFileButton.isVisible)
            assertFalse(panel.fileLabel.isVisible)
            panel.openFileButton.doClick()
            assertEquals(1, opened)

            // Once a file is present, the path shows and the button disappears.
            doc.file = File("/tmp/example.fasta")
            assertFalse(panel.openFileButton.isVisible)
            assertTrue(panel.fileLabel.isVisible)
            assertEquals("/tmp/example.fasta", panel.fileLabel.text)
        }
    }

    @Test
    fun primersPanelClearsStaleResultOnSequenceChange() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT"))
            val panel = PrimersPanel(doc)
            panel.designAmplicon(0, 24)
            assertNotNull(panel.lastPrimers())

            // Editing the sequence invalidates the designed pair.
            doc.mutate("edit") { it.insertAt(0, "G") }
            assertNull(panel.lastPrimers())
        }
    }

    @Test
    fun primersPanelAutoDesignsForWholeSequenceOnBind() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT"))
            val panel = PrimersPanel(doc)
            val primers = panel.lastPrimers()
            assertNotNull(primers)
            assertEquals("1" to "24", panel.rangeFields())
            assertNotEquals(primers.first.name, primers.second.name)
            assertTrue(primers.first.bases.isNotEmpty())
        }
    }

    @Test
    fun primersPanelAutoDesignsForSelection() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGT".repeat(12)))
            doc.select(4, 40)
            val panel = PrimersPanel(doc)
            assertNotNull(panel.lastPrimers())
            assertEquals("5" to "40", panel.rangeFields())
        }
    }

    @Test
    fun primersPanelDoesNotAutoDesignHugeSequence() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGT".repeat(6000)))
            val panel = PrimersPanel(doc)
            assertNull(panel.lastPrimers())
            assertEquals("1" to "24000", panel.rangeFields())
            assertTrue(panel.summaryText().contains("too large", ignoreCase = true))
        }
    }

    @Test
    fun primersPanelKeepsManuallyTypedRangeAcrossSelectionMoves() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT"))
            val panel = PrimersPanel(doc)

            panel.typeRangeForTest(1, 10)
            doc.select(15, 20)
            // A manual range represents user intent and must survive selection changes.
            assertEquals("1" to "10", panel.rangeFields())
        }
    }

    @Test
    fun primersPanelSavesPairToLibrary() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT", name = "tgt"))
            val panel = PrimersPanel(doc, prefs)
            panel.designAmplicon(0, 24)
            assertNotNull(panel.lastPrimers())

            panel.savePrimers()
            val library = prefs.value.library
            assertEquals(2, library.size)
            assertTrue(library.all { it.kind == SavedKind.PRIMER })
            assertEquals("tgt", library[0].context.sourceName)
            assertEquals(0, library[0].context.start)
            assertEquals(24, library[0].context.end)
            assertNotNull(library[0].context.tm)
        }
    }

    @Test
    fun primerDescriptionsAreDisplayedAndSavedToLibrary() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT", name = "tgt"))
            val panel = PrimersPanel(doc, prefs)
            panel.designAmplicon(0, 24)

            assertTrue(panel.updatePrimerDescription(0, "Forward screening primer"))
            assertTrue(panel.updatePrimerDescription(1, "Reverse screening primer"))
            assertEquals("Forward screening primer", panel.primerDescription(0))
            assertEquals("Reverse screening primer", panel.primerDescription(1))

            panel.savePrimers()
            assertEquals("Forward screening primer", prefs.value.library[0].description)
            assertEquals("Reverse screening primer", prefs.value.library[1].description)
        }
    }

    @Test
    fun primerElementEditRecalculatesMetricsAndFeedsLibraryAndFeatureActions() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT", name = "tgt"))
            val panel = PrimersPanel(doc, prefs)
            panel.designAmplicon(0, 24)

            assertEquals(null, panel.updatePrimerElement(0, "custom_forward", "ACGTACGT", "Hand-tuned primer"))
            val primers = panel.lastPrimers()!!
            assertEquals("custom_forward", primers.first.name)
            assertEquals("ACGTACGT", primers.first.bases)
            assertEquals(8, primers.first.bases.length)
            assertEquals(50.0, primers.first.gc)
            assertTrue(panel.updatePrimerElement(0, "bad", "ACGT!", "") != null)
            assertEquals("ACGTACGT", panel.lastPrimers()!!.first.bases)

            panel.savePrimers()
            assertEquals("custom_forward", prefs.value.library[0].name)
            assertEquals("Hand-tuned primer", prefs.value.library[0].description)
            assertTrue(panel.addPrimersToFeatures())
            assertTrue(doc.seq.features.any { it.name == "custom_forward" && it.length == 8 })
        }
    }

    @Test
    fun enzymeDescriptionsAreVisibleAndPersistedInPreferences() {
        onEdt {
            val prefs = Prefs().also { it.update { current -> current.copy(digestCuttersOnly = false) } }
            val panel = DigestPanel(SeqDocument(Seq(bases = "ACGTACGT")), { _: Seq -> }, { _, _ -> }, prefs)
            val row = panel.displayedEnzymes().indexOfFirst { it.name == "EcoRI" }
            assertTrue(row >= 0)
            assertTrue(panel.enzymeDescription(row).contains("MfeI"))

            assertTrue(panel.updateEnzymeDescription(row, "Standard sticky-end cloning enzyme"))
            assertEquals("Standard sticky-end cloning enzyme", panel.enzymeDescription(row))
            assertEquals("Standard sticky-end cloning enzyme", prefs.value.enzymeDescriptions["ecori"])
        }
    }

    @Test
    fun digestPanelKeepsSelectedEnzymeAcrossUnrelatedPreferenceRefreshes() {
        onEdt {
            val prefs = Prefs().also { it.update { current -> current.copy(digestCuttersOnly = false) } }
            val panel = DigestPanel(SeqDocument(Seq(bases = "GAATTCGGATCC")), { _: Seq -> }, { _, _ -> }, prefs)
            val ecoRi = panel.displayedEnzymes().first { it.name == "EcoRI" }
            panel.selectEnzymeInTable(ecoRi)
            assertEquals(ecoRi, panel.selectedEnzymeInTable())

            // Tool-tab selection is stored in prefs and must not clear the enzyme row.
            prefs.update { current -> current.copy(activeTab = 2) }

            assertEquals(ecoRi, panel.selectedEnzymeInTable())
            assertTrue(panel.displayedMatches().isNotEmpty())
        }
    }

    @Test
    fun digestPanelKeepsSelectedMatchAcrossUnrelatedPreferenceRefreshes() {
        onEdt {
            val prefs = Prefs().also { it.update { current -> current.copy(digestCuttersOnly = false) } }
            val panel = DigestPanel(SeqDocument(Seq(bases = "GAATTCGAATTC")), { _: Seq -> }, { _, _ -> }, prefs)
            panel.selectEnzymeInTable(panel.displayedEnzymes().first { it.name == "EcoRI" })
            val secondMatch = panel.displayedMatches()[1]
            panel.selectMatchInTable(secondMatch)
            assertEquals(secondMatch, panel.selectedMatchInTable())

            // Tool-tab selection is persisted through prefs and rebuilds both tables.
            prefs.update { current -> current.copy(activeTab = 2) }

            assertEquals(secondMatch, panel.selectedMatchInTable())
        }
    }

    @Test
    fun editingSelectedEnzymeRefreshesDigestMappingWithReplacementDefinition() {
        onEdt {
            val prefs = Prefs().also { it.update { current -> current.copy(digestCuttersOnly = false) } }
            val doc = SeqDocument(Seq(bases = "GAATTC"))
            val panel = DigestPanel(doc, { _: Seq -> }, { _, _ -> }, prefs)
            val ecoRi = panel.displayedEnzymes().first { it.name == "EcoRI" }
            panel.selectEnzymes(listOf(ecoRi))
            assertEquals(listOf("EcoRI"), doc.mappedEnzymes.map { it.name })

            val model = EnzymeManagerModel(prefs)
            assertNull(model.editEnzyme(ecoRi, "EditedRI", "CCGG", 0, 2, true, "Changed site"))
            model.commit()

            assertEquals(listOf("EditedRI"), doc.mappedEnzymes.map { it.name })
            assertEquals("CCGG", doc.mappedEnzymes.single().site)
            assertTrue(doc.cutSites.isEmpty(), "the replacement site should no longer cut GAATTC")
        }
    }

    @Test
    fun primersPanelAddsDesignedPrimersToFeatures() {
        onEdt {
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGTACGTACGT", name = "tgt"))
            val panel = PrimersPanel(doc)
            panel.designAmplicon(0, 24)
            val pair = panel.lastPrimers()
            assertNotNull(pair)

            assertTrue(panel.addPrimersToFeatures())
            var primers = doc.seq.features.filter { it.type == "primer_bind" }
            assertEquals(2, primers.size)

            val fwd = primers.first { it.name == pair.first.name }
            val rev = primers.first { it.name == pair.second.name }
            // Forward primer at the amplicon start; reverse primer at its end.
            assertEquals(0, fwd.start)
            assertEquals(pair.first.bases.length, fwd.end)
            assertEquals(24 - pair.second.bases.length, rev.start)
            assertEquals(24, rev.end)

            // Re-designing and adding again must not duplicate the annotations.
            panel.designAmplicon(0, 24)
            assertTrue(panel.addPrimersToFeatures())
            primers = doc.seq.features.filter { it.type == "primer_bind" }
            assertEquals(2, primers.size)

            // The annotation is undoable.
            doc.undo()
            assertEquals(0, doc.seq.features.size)
        }
    }

    @Test
    fun libraryPanelInsertsAndJumpsToSource() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGTACGTACGTACGT", name = "src"))
            val view = SequenceView(doc)
            val panel = LibraryPanel(prefs, doc, view) { _ -> }
            panel.addItem(
                org.instagene.app.gui.prefs.SavedItem(
                    kind = SavedKind.FRAGMENT,
                    name = "frag",
                    bases = "TTTT",
                    context = org.instagene.app.gui.prefs.SavedContext("src", start = 2, end = 6),
                )
            )
            assertEquals(1, panel.libraryTable.rowCount)

            doc.select(0, 2)
            panel.insertSelected(0)
            assertEquals("TTTTGTACGTACGTACGT", doc.seq.bases)

            doc.select(0, 0)
            panel.jumpToSource(0)
            assertEquals(2, doc.selectionStart)
            assertEquals(6, doc.selectionEnd)
        }
    }

    @Test
    fun libraryPanelShowsAndEditsSavedItemDescriptions() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGTACGT", name = "src"))
            val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
            panel.addItem(
                org.instagene.app.gui.prefs.SavedItem(
                    kind = SavedKind.PRIMER,
                    name = "screening_primer",
                    bases = "ACGT",
                    context = org.instagene.app.gui.prefs.SavedContext("src", 1, 5),
                    description = "Original screening primer",
                )
            )

            assertEquals("Description", panel.libraryTable.columnModel.getColumn(4).headerValue)
            assertEquals("Original screening primer", panel.libraryTable.model.getValueAt(0, 4))
            assertNull(panel.updateLibraryElement(0, "renamed_primer", "ac gt", "Verification primer"))

            val updated = prefs.value.library.single()
            assertEquals("renamed_primer", updated.name)
            assertEquals("ACGT", updated.bases)
            assertEquals("Verification primer", updated.description)
            assertEquals(org.instagene.app.gui.prefs.SavedContext("src", 1, 5), updated.context)
            assertEquals("Verification primer", panel.libraryTable.model.getValueAt(0, 4))

            assertNotNull(panel.updateLibraryElement(0, "renamed_primer", "AC?T", "Bad edit"))
            assertEquals("Verification primer", prefs.value.library.single().description)
        }
    }

    @Test
    fun libraryPanelDeleteRemovesItem() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGT"))
            val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
            panel.addItem(
                org.instagene.app.gui.prefs.SavedItem(
                    kind = SavedKind.FRAGMENT,
                    name = "frag",
                    bases = "AAAA",
                    context = org.instagene.app.gui.prefs.SavedContext("src", 0, 4),
                )
            )
            assertEquals(1, panel.libraryTable.rowCount)
            panel.deleteSelected(0)
            assertEquals(0, prefs.value.library.size)
        }
    }

    @Test
    fun libraryPanelDeleteRemovesOnlyTheSelectedDuplicate() {
        onEdt {
            val prefs = Prefs()
            val doc = SeqDocument(Seq(bases = "ACGT"))
            val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
            val duplicate = org.instagene.app.gui.prefs.SavedItem(SavedKind.FRAGMENT, "frag", "AAAA")
            panel.addItem(duplicate)
            panel.addItem(duplicate)
            panel.deleteSelected(0)
            assertEquals(1, prefs.value.library.size)
            assertEquals(duplicate, prefs.value.library.single())
        }
    }

    private fun menuItem(menu: JMenu, text: String): JMenuItem =
        menu.menuComponents.filterIsInstance<JMenuItem>().first { it.text == text }
}
