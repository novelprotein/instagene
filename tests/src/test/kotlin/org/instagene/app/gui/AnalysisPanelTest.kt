package org.instagene.app.gui

import com.sun.net.httpserver.HttpServer
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.AnalysisDefaults
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.PrefsStore
import org.instagene.app.gui.prefs.UserPrefs
import org.instagene.app.gui.tool.AnalysisPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import org.instagene.core.Strand
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisPanelTest {
    @Test
    fun analysisWorkspaceExposesEveryAddedWorkflow() = onEdt {
        val panel = AnalysisPanel(SeqDocument(Seq("sample", "GAATTCATGGCCTAAGCTT")), {}, { _, _ -> })
        assertEquals(
            listOf(
                "Search", "Alignment", "Repeats / Dot Plot", "Enzymes", "CpG Methylation", "Assembly", "PCR / Mutagenesis", "Translation / Structure",
                "Virtual Gel", "Calculators", "NCBI / BLAST", "Chromatogram",
                "CRISPR / gRNA", "Sanger Alignment", "Primer Thermo", "Plasmid DB", "Site Domestication",
                "Statistics / Graphs",
            ),
            panel.toolNames(),
        )
        panel.selectTool("Virtual Gel")
        assertEquals("Virtual Gel", panel.selectedTool())
        val tabGroups = descendants(panel, JTabbedPane::class.java)
        assertTrue(
            tabGroups.any { tabs ->
                (0 until tabs.tabCount).map(tabs::getTitleAt) ==
                    listOf("Search & Find", "Sequence Analysis", "Cloning & Design", "PCR & Sequencing", "Utilities")
            },
            "analysis navigation must be represented by standard grouped tabs",
        )
    }

    @Test
    fun assemblyWorkspaceExposesThePcrCloningWizard() = onEdt {
        val panel = AnalysisPanel(
            SeqDocument(Seq("circular_backbone", "GAATTCACGTACGTAAGCTT", topology = org.instagene.core.Topology.CIRCULAR)),
            {},
            { _, _ -> },
        )

        panel.selectTool("Assembly")

        val wizard = descendants(panel, JButton::class.java).firstOrNull { it.text == "PCR-cloning wizard…" }
        assertTrue(wizard != null, "Assembly tools must expose the guided PCR-cloning workflow")
        assertTrue(wizard.toolTipText.orEmpty().contains("validate restriction cloning", ignoreCase = true))
        val replay = descendants(panel, JButton::class.java).firstOrNull { it.text == "Replay recipe…" }
        assertTrue(replay != null, "Assembly tools must expose identity-checked recipe replay")
    }

    @Test
    fun translationWorkspaceExposesCdsFrameValidation() = onEdt {
        val panel = AnalysisPanel(
            SeqDocument(Seq("cds", "ATGAAATAA", features = listOf(org.instagene.core.Feature("gene", "CDS", 0, 9)))),
            {},
            { _, _ -> },
        )

        panel.selectTool("Translation / Structure")

        val operation = descendants(panel, JComboBox::class.java).firstOrNull { combo ->
            (0 until combo.itemCount).any { combo.getItemAt(it) == "Validate CDS features" }
        }
        assertTrue(operation != null, "Translation tools must offer coordinate-linked CDS validation")
    }

    @Test
    fun repeatWorkspaceExposesDotPlotAndRepeatExports() = onEdt {
        val panel = AnalysisPanel(SeqDocument(Seq("repeat", "AAAAATGCAAAAGCATTTT")), {}, { _, _ -> })

        panel.selectTool("Repeats / Dot Plot")

        val labels = descendants(panel, JButton::class.java).map { it.text }
        assertTrue("Analyze" in labels)
        assertTrue("Export plot" in labels)
        assertTrue("Export repeats" in labels)
    }

    @Test
    fun alignmentWorkspaceOffersInterchangeAndImageExport() = onEdt {
        val panel = AnalysisPanel(SeqDocument(Seq("alignment", "ACGTACGT")), {}, { _, _ -> })

        panel.selectTool("Alignment")

        val export = descendants(panel, JButton::class.java).firstOrNull { it.text == "Export alignment…" }
        assertTrue(export != null, "Alignment tools must offer a researcher-readable export action")
        assertTrue(export.toolTipText.orEmpty().contains("Stockholm"))
        assertTrue(export.toolTipText.orEmpty().contains("PNG"))
    }

    @Test
    fun analysisWorkspaceRebindsWithoutReconstruction() = onEdt {
        val first = SeqDocument(Seq("first", "ACGT"))
        val panel = AnalysisPanel(first, {}, { _, _ -> })
        val second = SeqDocument(Seq("second", "TGCA"))
        panel.bindDocument(second)
        panel.selectTool("Search")
        assertEquals("Search", panel.selectedTool())
    }

    @Test
    fun analysisWorkspaceRestoresAndPersistsTheLastSelectedTool() = onEdt {
        val prefsFile = Files.createTempDirectory("instagene-analysis-defaults").resolve("prefs.json").toFile()
        PrefsStore(prefsFile).save(UserPrefs(analysisDefaults = AnalysisDefaults(lastTool = "Repeats / Dot Plot", repeatWordSize = 17)))
        val prefs = Prefs(PrefsStore(prefsFile))
        val panel = AnalysisPanel(SeqDocument(Seq("sample", "ACGTACGT")), {}, { _, _ -> }, prefs = prefs)

        assertEquals("Repeats / Dot Plot", panel.selectedTool())
        panel.selectTool("Alignment")
        assertEquals("Alignment", prefs.value.analysisDefaults.lastTool)
    }

    @Test
    fun featureEditorPersistsDisplayMetadata() = onEdt {
        val document = SeqDocument(Seq("annotated", "ACGTACGT", features = listOf(org.instagene.core.Feature("old", start = 0, end = 4))))
        val features = FeaturesPanel(document) { _, _ -> }
        assertEquals(null, features.updateFeatureElement(0, "promoter", "promoter", 1, 4, Strand.FORWARD, "note", "#123456", false, 7))
        val updated = document.seq.features.single()
        assertEquals("#123456", updated.color)
        assertEquals(false, updated.visible)
        assertEquals(7, updated.displayOrder)
    }

    @Test
    fun ncbiSearchFetchAndBlastResultsStayInsideTheAnalysisPanel() {
        withApiServer { base ->
            var opened: Seq? = null
            val panel = onEdt {
                AnalysisPanel(
                    SeqDocument(Seq("query", "ACGTACGT")),
                    { opened = it },
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }

            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            onEdt {
                ncbiQueryField(panel).text = "J01636.1"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                val table = nucleotideTable(panel)
                assertEquals("J01636.1", table.getValueAt(0, 0))
                assertEquals("Example nucleotide record", table.getValueAt(0, 1))
                assertTrue(table.selectedRow == 0)
                val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }
                assertTrue(fetchButton.isEnabled)
                fetchButton.doClick()
            }
            awaitCondition { opened != null }
            assertEquals("ACGTACGT", opened?.bases)

            onEdt {
                descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }.doClick()
            }
            awaitCondition { blastTable(panel).rowCount == 1 }
            onEdt {
                val table = blastTable(panel)
                assertEquals(
                    listOf("Accession", "% identity", "Alignment length", "Mismatches", "Gaps", "E-value", "Bit score"),
                    table.headers(),
                )
                assertEquals("J01636.1", table.getValueAt(0, 0))
                assertEquals(100.0, table.getValueAt(0, 1))
                assertEquals(8, table.getValueAt(0, 2))
                assertEquals(0, table.getValueAt(0, 3))
                assertEquals(0, table.getValueAt(0, 4))
                assertEquals(1.0E-10, table.getValueAt(0, 5))
                assertEquals(16.5, table.getValueAt(0, 6))
            }
        }
    }

    @Test
    fun ncbiSearchResultDoubleClickOpensGenBankSequence() {
        withApiServer { base ->
            var opened: Seq? = null
            val panel = onEdt {
                AnalysisPanel(
                    SeqDocument(Seq("query", "ACGTACGT")),
                    { opened = it },
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }

            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            onEdt {
                ncbiQueryField(panel).text = "J01636.1"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                val table = nucleotideTable(panel)
                table.setRowSelectionInterval(0, 0)
                doubleClick(table)
            }
            awaitCondition { opened != null }
            assertEquals("ACGTACGT", opened?.bases)
        }
    }

    @Test
    fun ncbiFetchRequiresCurrentSelectionWhenTypedQueryIsNotAnAccession() {
        val fetchedId = AtomicReference<String>()
        withApiServer(onFetch = fetchedId::set) { base ->
            val panel = ncbiPanel(base)
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                ncbiQueryField(panel).text = "J01636.1"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                ncbiQueryField(panel).text = "record search"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                nucleotideTable(panel).clearSelection()
                assertTrue(fetchButton.isEnabled)
                fetchButton.doClick()
            }
            awaitCondition {
                descendants(panel, javax.swing.JTextArea::class.java)
                    .any { it.text.contains("Run Search NCBI and select a result") }
            }
            assertEquals(null, fetchedId.get())
        }
    }

    @Test
    fun ncbiFetchCanUseTypedAccessionWithoutSearchResults() {
        val fetchedId = AtomicReference<String>()
        val searches = ArrayList<String>()
        withApiServer(onFetch = fetchedId::set, onSearch = searches::add) { base ->
            var opened: Seq? = null
            val panel = onEdt {
                AnalysisPanel(
                    SeqDocument(Seq("query", "ACGTACGT")),
                    { opened = it },
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                assertTrue(fetchButton.isEnabled)
                ncbiQueryField(panel).text = "J01636.1"
                fetchButton.doClick()
            }
            awaitCondition { opened != null }

            assertEquals("J01636.1", fetchedId.get())
            assertEquals("ACGTACGT", opened?.bases)
            assertEquals(emptyList(), searches)
        }
    }

    @Test
    fun fullWindowFetchGenBankOpensSequenceTabFromSharedQueryField() {
        val fetchedId = AtomicReference<String>()
        withApiServer(onFetch = fetchedId::set) { base ->
            val content = onEdt {
                InstaGeneContent(
                    ncbiClient = NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                ).also {
                    it.toolTabs.selectedIndex = it.toolTabs.indexOfTab("Analysis")
                    it.analysisPanel.selectTool("NCBI / BLAST")
                }
            }
            val fetchButton = descendants(content.analysisPanel, JButton::class.java)
                .single { it.text == "Fetch GenBank" }

            onEdt {
                assertEquals(0, content.docTabs.tabCount)
                ncbiQueryField(content.analysisPanel).text = "J01636.1"
                assertTrue(fetchButton.isEnabled)
                fetchButton.doClick()
                assertEquals(false, fetchButton.isEnabled)
            }
            awaitCondition { content.docTabs.tabCount == 1 }

            onEdt {
                assertEquals("J01636.1", fetchedId.get())
                assertEquals("ACGTACGT", (content.activeDoc as SeqDocument).seq.bases)
                assertTrue(fetchButton.isEnabled)
            }
        }
    }

    @Test
    fun failedGenBankFetchRestoresButtonAndShowsError() {
        withApiServer(fetchBody = "not a GenBank record") { base ->
            val panel = ncbiPanel(base)
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                ncbiQueryField(panel).text = "J01636.1"
                fetchButton.doClick()
                assertEquals(false, fetchButton.isEnabled)
            }
            awaitCondition {
                fetchButton.isEnabled && descendants(panel, javax.swing.JTextArea::class.java)
                    .any { it.text.contains("did not return GenBank text") }
            }
        }
    }

    @Test
    fun ncbiFetchIsAlwaysActionableAndExplainsMissingAccession() {
        withApiServer { base ->
            val panel = ncbiPanel(base)
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                ncbiQueryField(panel).text = "lacZ operon"
                assertTrue(searchButton.isEnabled)
                assertTrue(fetchButton.isEnabled)
                fetchButton.doClick()
            }
            awaitCondition {
                descendants(panel, javax.swing.JTextArea::class.java)
                    .any { it.text.contains("Run Search NCBI and select a result") }
            }
        }
    }

    @Test
    fun ncbiFetchTracksClickedAndRightClickedRows() {
        val fetched = ArrayList<String>()
        withApiServer(searchIds = listOf("J01636.1", "ALT123.1"), onFetch = fetched::add) { base ->
            val panel = ncbiPanel(base)
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                ncbiQueryField(panel).text = "two records"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 2 }
            onEdt {
                val table = nucleotideTable(panel)
                table.setRowSelectionInterval(1, 1)
                assertTrue(fetchButton.isEnabled)
                fetchButton.doClick()
            }
            awaitCondition { fetched.size == 1 && fetchButton.isEnabled }
            onEdt {
                val table = nucleotideTable(panel)
                popupClick(table, 0)
                val fetchItem = table.componentPopupMenu.components.filterIsInstance<JMenuItem>().single { it.text == "Fetch GenBank" }
                assertTrue(fetchItem.isEnabled)
                fetchItem.doClick()
            }
            awaitCondition { fetched.size == 2 }

            assertEquals(listOf("ALT123.1", "J01636.1"), fetched)
        }
    }

    @Test
    fun ncbiFetchRemainsAvailableForZeroSearchResults() {
        withApiServer(searchIds = emptyList()) { base ->
            val panel = ncbiPanel(base)
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                ncbiQueryField(panel).text = "no hits"
                searchButton.doClick()
            }
            awaitCondition { descendants(panel, javax.swing.JTextArea::class.java).any { it.text.contains("0 result") } }
            onEdt {
                assertEquals(0, nucleotideTable(panel).rowCount)
                assertTrue(fetchButton.isEnabled)
            }
        }
    }

    @Test
    fun ncbiBlastHitOpensPipeFormattedSubjectAccession() {
        val fetchedId = AtomicReference<String>()
        withApiServer(blastSubjectId = "gi|49175990|ref|NC_000913.3|", onFetch = fetchedId::set) { base ->
            var opened: Seq? = null
            val panel = onEdt {
                AnalysisPanel(
                    SeqDocument(Seq("query", "ACGTACGT")),
                    { opened = it },
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }

            onEdt {
                descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }.doClick()
            }
            awaitCondition { blastTable(panel).rowCount == 1 }
            onEdt {
                val table = blastTable(panel)
                assertEquals("NC_000913.3", table.getValueAt(0, 0))
                assertTrue(descendants(panel, JButton::class.java).none { it.text == "Open BLAST Hit" })
                table.clearSelection()
                popupClick(table, 0)
                assertEquals(0, table.selectedRow)
                val openItem = table.componentPopupMenu.components.filterIsInstance<JMenuItem>().single { it.text == "Open BLAST Hit" }
                assertTrue(openItem.isEnabled)
                openItem.doClick()
            }
            awaitCondition { opened != null }
            assertEquals("NC_000913.3", fetchedId.get())
            assertEquals("ACGTACGT", opened?.bases)
        }
    }

    @Test
    fun ncbiBlastCanBeCancelledWhilePolling() {
        withApiServer(blastStaysWaiting = true) { base ->
            val panel = onEdt {
                AnalysisPanel(
                    SeqDocument(Seq("query", "ACGTACGT")),
                    {},
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 100,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val blastButton = descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }


            onEdt {
                assertTrue(blastButton.isEnabled)
                blastButton.doClick()
                assertEquals("Cancel BLAST", blastButton.text)
                assertTrue(blastButton.isEnabled)
                blastButton.doClick()
            }
            awaitCondition { blastButton.text == "Run BLAST" && blastButton.isEnabled }
            assertEquals(0, blastTable(panel).rowCount)
        }
    }

    @Test
    fun ncbiNucleotideSearchCanUseTypedSelectedOrWholeSequenceInput() {
        val searches = ArrayList<String>()
        withApiServer(onSearch = searches::add) { base ->
            val doc = SeqDocument(Seq("query", "AAAACCCCGGGG"))
            val panel = onEdt {
                doc.select(4, 8)
                AnalysisPanel(
                    doc,
                    {},
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val source = comboBox(panel, "Typed term", "Selected bases", "Whole sequence")

            onEdt {
                ncbiQueryField(panel).text = "J01636.1"
                searchButton.doClick()
            }
            awaitCondition { searches.size == 1 }

            onEdt {
                source.selectItem("Selected bases")
                searchButton.doClick()
            }
            awaitCondition { searches.size == 2 }

            onEdt {
                source.selectItem("Whole sequence")
                searchButton.doClick()
            }
            awaitCondition { searches.size == 3 }

            assertEquals(listOf("J01636.1", "CCCC", "AAAACCCCGGGG"), searches)
        }
    }

    @Test
    fun genBankFetchUsesSelectedAndWholeSequenceSearchResults() {
        val searches = ArrayList<String>()
        val fetched = ArrayList<String>()
        withApiServer(onSearch = searches::add, onFetch = fetched::add) { base ->
            val doc = SeqDocument(Seq("query", "AAAACCCCGGGG"))
            val panel = onEdt {
                doc.select(4, 8)
                AnalysisPanel(
                    doc,
                    {},
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val source = comboBox(panel, "Typed term", "Selected bases", "Whole sequence")
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                source.selectItem("Selected bases")
                searchButton.doClick()
            }
            awaitCondition { searches.size == 1 && nucleotideTable(panel).rowCount == 1 }
            onEdt { fetchButton.doClick() }
            awaitCondition { fetched.size == 1 && fetchButton.isEnabled }

            onEdt {
                source.selectItem("Whole sequence")
                assertEquals(0, nucleotideTable(panel).rowCount)
                searchButton.doClick()
            }
            awaitCondition { searches.size == 2 && nucleotideTable(panel).rowCount == 1 }
            onEdt { fetchButton.doClick() }
            awaitCondition { fetched.size == 2 && fetchButton.isEnabled }

            assertEquals(listOf("CCCC", "AAAACCCCGGGG"), searches)
            assertEquals(listOf("J01636.1", "J01636.1"), fetched)
        }
    }

    @Test
    fun changingSharedQueryOrSourceClearsStaleNucleotideResults() {
        val fetched = ArrayList<String>()
        withApiServer(onFetch = fetched::add) { base ->
            val panel = ncbiPanel(base)
            val source = comboBox(panel, "Typed term", "Selected bases", "Whole sequence")
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }

            onEdt {
                ncbiQueryField(panel).text = "record search"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                ncbiQueryField(panel).text = "different search"
                assertEquals(0, nucleotideTable(panel).rowCount)
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                source.selectItem("Whole sequence")
                assertEquals(0, nucleotideTable(panel).rowCount)
                fetchButton.doClick()
            }
            awaitCondition {
                descendants(panel, javax.swing.JTextArea::class.java)
                    .any { it.text.contains("then select a result to fetch GenBank") }
            }
            assertEquals(emptyList(), fetched)
        }
    }

    @Test
    fun ncbiBlastCanUseSelectionOrWholeSequenceInput() {
        val blastQueries = ArrayList<String>()
        withApiServer(onBlastQuery = blastQueries::add) { base ->
            val doc = SeqDocument(Seq("query", "AAAACCCCGGGG"))
            val panel = onEdt {
                doc.select(4, 8)
                AnalysisPanel(
                    doc,
                    {},
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val runButton = descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }
            val source = comboBox(panel, "Typed term", "Selected bases", "Whole sequence")

            onEdt {
                assertTrue(runButton.isEnabled)
                runButton.doClick()
            }
            awaitCondition { blastQueries.size == 1 }
            awaitCondition { runButton.text == "Run BLAST" && runButton.isEnabled }

            onEdt {
                source.selectItem("Whole sequence")
                runButton.doClick()
            }
            awaitCondition { blastQueries.size == 2 }
            awaitCondition { runButton.text == "Run BLAST" && runButton.isEnabled }

            onEdt {
                source.selectItem("Selected bases")
                runButton.doClick()
            }
            awaitCondition { blastQueries.size == 3 }

            assertEquals(listOf("CCCC", "AAAACCCCGGGG", "CCCC"), blastQueries)
        }
    }

    @Test
    fun ncbiBlastCanUseTypedNucleotideInput() {
        val blastQueries = ArrayList<String>()
        withApiServer(onBlastQuery = blastQueries::add) { base ->
            val panel = onEdt {
                AnalysisPanel(
                    SeqDocument(Seq("query", "AAAACCCCGGGG")),
                    {},
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val runButton = descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }
            val term = ncbiQueryField(panel)

            onEdt {
                assertTrue(runButton.isEnabled)
                term.text = "not-an-accession"
                assertTrue(runButton.isEnabled)
                runButton.doClick()
            }
            awaitCondition { blastQueries.size == 1 }
            awaitCondition { runButton.text == "Run BLAST" && runButton.isEnabled }

            onEdt {
                term.text = "AACCGGTT"
                assertTrue(runButton.isEnabled)
                runButton.doClick()
            }
            awaitCondition { blastQueries.size == 2 }

            assertEquals(listOf("AAAACCCCGGGG", "AACCGGTT"), blastQueries)
        }
    }

    @Test
    fun selectedBaseSearchControlsStayActionableAndExplainMissingSelection() {
        val searches = ArrayList<String>()
        val blastQueries = ArrayList<String>()
        withApiServer(onSearch = searches::add, onBlastQuery = blastQueries::add) { base ->
            val doc = SeqDocument(Seq("query", "AAAACCCCGGGG"))
            val panel = onEdt {
                AnalysisPanel(
                    doc,
                    {},
                    { _, _ -> },
                    NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                    ncbiPollIntervalMillis = 0,
                ).also { it.selectTool("NCBI / BLAST") }
            }
            val searchButton = descendants(panel, JButton::class.java).single { it.text == "Search NCBI" }
            val runButton = descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }
            val source = comboBox(panel, "Typed term", "Selected bases", "Whole sequence")

            onEdt {
                source.selectItem("Selected bases")
                assertTrue(searchButton.isEnabled)
                assertTrue(runButton.isEnabled)
                assertEquals("No bases selected", ncbiQueryStatus(panel).text)

                searchButton.doClick()
                assertTrue(descendants(panel, javax.swing.JTextArea::class.java)
                    .any { it.text.contains("Select bases in the Sequence view") })
                runButton.doClick()
                assertTrue(descendants(panel, javax.swing.JTextArea::class.java)
                    .any { it.text.contains("Select bases in the Sequence view") })
                assertEquals(emptyList(), searches)
                assertEquals(emptyList(), blastQueries)

                doc.select(0, 4)
                assertTrue(searchButton.isEnabled)
                assertTrue(runButton.isEnabled)
                assertEquals("4 bases selected", ncbiQueryStatus(panel).text)
            }
        }
    }

    @Test
    fun sequenceTabSelectionReachesSelectedBasesNcbiSearch() {
        val searches = ArrayList<String>()
        withApiServer(onSearch = searches::add) { base ->
            val content = onEdt {
                InstaGeneContent(
                    ncbiClient = NcbiClient(
                        http = HttpClient.newHttpClient(),
                        baseUrl = "$base/entrez/eutils",
                        blastBaseUrl = "$base/Blast.cgi",
                    ),
                ).also {
                    it.openSequence(Seq("query", "AAAACCCCGGGG"))
                    it.toolTabs.selectedIndex = it.toolTabs.indexOfTab("Sequence")
                    it.sequenceView.revealRange(4, 8)
                    it.toolTabs.selectedIndex = it.toolTabs.indexOfTab("Analysis")
                    it.analysisPanel.selectTool("NCBI / BLAST")
                }
            }
            val source = comboBox(content.analysisPanel, "Typed term", "Selected bases", "Whole sequence")
            val searchButton = descendants(content.analysisPanel, JButton::class.java)
                .single { it.text == "Search NCBI" }

            onEdt {
                source.selectItem("Selected bases")
                assertEquals("4 bases selected", ncbiQueryStatus(content.analysisPanel).text)
                assertTrue(searchButton.isEnabled)
                searchButton.doClick()
            }
            awaitCondition { searches.size == 1 }
            assertEquals(listOf("CCCC"), searches)
        }
    }

    @Test
    fun ncbiSearchGenBankAndBlastShareOneQueryRow() = onEdt {
        val panel = AnalysisPanel(SeqDocument(Seq("query", "ACGTACGT")), {}, { _, _ -> })
            .also { it.selectTool("NCBI / BLAST") }
        panel.setSize(640, 420)
        panel.doLayout()

        val source = comboBox(panel, "Typed term", "Selected bases", "Whole sequence")
        val runButton = descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }
        val fetchButton = descendants(panel, JButton::class.java).single { it.text == "Fetch GenBank" }
        val queryButtons = runButton.parent.components.filterIsInstance<JButton>().map { it.text }

        assertEquals("Typed term", source.selectedItem)
        assertTrue(runButton.isVisible)
        assertTrue(runButton.isEnabled)
        assertTrue(fetchButton.isVisible)
        assertTrue(fetchButton.isEnabled)
        assertEquals(listOf("Search NCBI", "Fetch GenBank", "Run BLAST"), queryButtons)
        assertEquals("ncbiSharedQuery", ncbiQueryField(panel).name)
        assertEquals(runButton.parent, fetchButton.parent)
        assertTrue(descendants(panel, JButton::class.java).none { it.text == "Cancel BLAST" || it.text == "Open BLAST Hit" })
    }

    @Test
    fun ncbiCacheModeIsExplicitAndPersistsTheResearcherChoice() = onEdt {
        val prefs = Prefs()
        val panel = AnalysisPanel(SeqDocument(Seq("query", "ACGTACGT")), {}, { _, _ -> }, prefs = prefs)
            .also { it.selectTool("NCBI / BLAST") }

        val cache = descendants(panel, JComboBox::class.java).single { it.name == "ncbiCacheMode" }
        assertEquals("Network only", cache.selectedItem.toString())

        cache.selectedIndex = (0 until cache.itemCount).first { cache.getItemAt(it).toString() == "Cache only (offline)" }
        assertEquals("CACHE_ONLY", prefs.value.onlineCacheMode)

        val reopened = AnalysisPanel(SeqDocument(Seq("query", "ACGTACGT")), {}, { _, _ -> }, prefs = prefs)
            .also { it.selectTool("NCBI / BLAST") }
        val reopenedCache = descendants(reopened, JComboBox::class.java).single { it.name == "ncbiCacheMode" }
        assertEquals("Cache only (offline)", reopenedCache.selectedItem.toString())
    }

    private fun nucleotideTable(panel: AnalysisPanel): JTable = descendants(panel, JTable::class.java).single {
        it.getColumnName(0) == "Accession" && it.columnCount == 5
    }

    private fun blastTable(panel: AnalysisPanel): JTable = descendants(panel, JTable::class.java).single {
        it.getColumnName(0) == "Accession" && it.columnCount == 7
    }

    private fun JTable.headers(): List<String> = (0 until columnCount).map { getColumnName(it) }

    private fun ncbiPanel(base: String): AnalysisPanel = onEdt {
        AnalysisPanel(
            SeqDocument(Seq("query", "ACGTACGT")),
            {},
            { _, _ -> },
            NcbiClient(
                http = HttpClient.newHttpClient(),
                baseUrl = "$base/entrez/eutils",
                blastBaseUrl = "$base/Blast.cgi",
            ),
            ncbiPollIntervalMillis = 0,
        ).also { it.selectTool("NCBI / BLAST") }
    }

    private fun JComboBox<*>.selectItem(label: String) {
        selectedIndex = (0 until itemCount).first { getItemAt(it) == label }
    }

    private fun comboBox(panel: AnalysisPanel, vararg labels: String): JComboBox<*> =
        descendants(panel, JComboBox::class.java).single { combo ->
            labels.all { label -> (0 until combo.itemCount).any { combo.getItemAt(it) == label } }
        }

    private fun ncbiQueryField(panel: AnalysisPanel): JTextField =
        descendants(panel, JTextField::class.java).single { it.name == "ncbiSharedQuery" }

    private fun ncbiQueryStatus(panel: AnalysisPanel): javax.swing.JLabel =
        descendants(panel, javax.swing.JLabel::class.java).single { it.name == "ncbiQueryStatus" }

    private fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            SwingUtilities.invokeAndWait { ready = condition() }
            if (ready) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for GUI state")
    }

    private fun doubleClick(table: JTable) {
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                2,
                false,
                MouseEvent.BUTTON1,
            )
        )
    }

    private fun popupClick(table: JTable, row: Int) {
        val y = row * table.rowHeight + table.rowHeight / 2
        val event = MouseEvent(
            table,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            0,
            1,
            y,
            1,
            true,
            MouseEvent.BUTTON3,
        )
        table.mouseListeners
            .filter { it.javaClass.name.contains("NcbiAnalysisPanel") }
            .forEach { it.mouseReleased(event) }
    }

    private fun <T : Component> descendants(root: Component, type: Class<T>): List<T> {
        val found = ArrayList<T>()
        fun visit(component: Component) {
            if (type.isInstance(component)) found += type.cast(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return found
    }

    private fun withApiServer(
        blastStaysWaiting: Boolean = false,
        blastSubjectId: String = "J01636.1",
        searchIds: List<String> = listOf("J01636.1"),
        fetchBody: String? = null,
        onSearch: (String) -> Unit = {},
        onBlastQuery: (String) -> Unit = {},
        onFetch: (String) -> Unit = {},
        block: (String) -> Unit,
    ) {
        val statusCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("/esearch.fcgi") -> {
                    onSearch(queryParameter(query, "term"))
                    """{"esearchresult":{"count":"${searchIds.size}","idlist":[${searchIds.joinToString(",") { "\"$it\"" }}]}}"""
                }
                path.endsWith("/esummary.fcgi") -> {
                    val records = searchIds.joinToString(",") { id ->
                        val title = if (id == "J01636.1") "Example nucleotide record" else "Example nucleotide record $id"
                        """"$id":{"uid":"$id","accessionversion":"$id","caption":"${id.substringBefore('.')}",`title`:"$title","organism":"Escherichia coli","slen":8,"moltype":"genomic"}"""
                            .replace('`', '"')
                    }
                    """{"result":{"uids":[${searchIds.joinToString(",") { "\"$it\"" }}]${if (records.isBlank()) "" else ",$records"}}}"""
                }
                path.endsWith("/efetch.fcgi") -> {
                    onFetch(queryParameter(query, "id"))
                    fetchBody ?: genBank
                }
                path.endsWith("/Blast.cgi") && exchange.requestMethod == "POST" -> {
                    val form = exchange.requestBody.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
                    onBlastQuery(queryParameter(form, "QUERY").lineSequence().filterNot { it.startsWith(">") }.joinToString(""))
                    "RID = RID123\nRTOE = 1\n"
                }
                path.endsWith("/Blast.cgi") && query.contains("FORMAT_OBJECT=SearchInfo") ->
                    if (blastStaysWaiting || statusCalls.getAndIncrement() == 0) "Status=WAITING\n" else "Status=READY\n"
                path.endsWith("/Blast.cgi") ->
                    "# BLASTN\nquery\t$blastSubjectId\t100.0\t8\t0\t0\t1\t8\t1\t8\t1e-10\t16.5\n"
                else -> error("Unexpected request: ${exchange.requestURI}")
            }
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun queryParameter(query: String, name: String): String =
        query.split('&')
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            .orEmpty()

    private val genBank = """
        LOCUS       J01636.1                 8 bp    DNA     linear   PLN 01-JAN-2024
        DEFINITION  Example nucleotide record.
        ACCESSION   J01636
        VERSION     J01636.1
        ORIGIN
                1 acgtacgt
        //
    """.trimIndent()

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try { result = block() } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
