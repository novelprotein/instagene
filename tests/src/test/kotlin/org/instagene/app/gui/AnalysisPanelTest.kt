package org.instagene.app.gui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.AnalysisPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.core.Seq
import org.instagene.core.Strand
import org.instagene.core.NcbiClient
import java.awt.Component
import java.awt.Container
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
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
            listOf("Search", "Alignment", "Enzymes", "Assembly", "Virtual Gel", "Calculators", "NCBI / BLAST", "Chromatogram"),
            panel.toolNames(),
        )
        panel.selectTool("Virtual Gel")
        assertEquals("Virtual Gel", panel.selectedTool())
    }

    @Test
    fun analysisWorkspaceRebindsWithoutReconstruction() = onEdt {
        val first = SeqDocument(Seq("first", "ACGT"))
        val panel = AnalysisPanel(first, {}, { _, _ -> })
        val second = SeqDocument(Seq("second", "TGCA"))
        panel.bindDocument(second)
        panel.selectTool("Search")
        assertTrue(panel.selectedTool() == "Search")
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
                val controls = searchButton.parent
                descendants(controls, JTextField::class.java).single().text = "J01636.1"
                searchButton.doClick()
            }
            awaitCondition { nucleotideTable(panel).rowCount == 1 }
            onEdt {
                val table = nucleotideTable(panel)
                assertEquals("J01636.1", table.getValueAt(0, 0))
                assertEquals("Example nucleotide record", table.getValueAt(0, 1))
                table.setRowSelectionInterval(0, 0)
                descendants(panel, JButton::class.java).single { it.text == "Fetch selected GenBank" }.doClick()
            }
            awaitCondition { opened != null }
            assertEquals("ACGTACGT", opened?.bases)

            onEdt { descendants(panel, JButton::class.java).single { it.text == "Run BLAST" }.doClick() }
            awaitCondition { blastTable(panel).rowCount == 1 }
            onEdt {
                val table = blastTable(panel)
                assertEquals("J01636.1", table.getValueAt(0, 1))
                assertEquals(100.0, table.getValueAt(0, 2))
            }
        }
    }

    private fun nucleotideTable(panel: AnalysisPanel): JTable = descendants(panel, JTable::class.java).single {
        it.getColumnName(0) == "Accession"
    }

    private fun blastTable(panel: AnalysisPanel): JTable = descendants(panel, JTable::class.java).single {
        it.getColumnName(0) == "Query"
    }

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

    private fun <T : Component> descendants(root: Component, type: Class<T>): List<T> {
        val found = ArrayList<T>()
        fun visit(component: Component) {
            if (type.isInstance(component)) found += type.cast(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return found
    }

    private fun withApiServer(block: (String) -> Unit) {
        val statusCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("/esearch.fcgi") ->
                    """{"esearchresult":{"count":"1","idlist":["J01636.1"]}}"""
                path.endsWith("/esummary.fcgi") ->
                    """{"result":{"uids":["J01636.1"],"J01636.1":{"accessionversion":"J01636.1","caption":"J01636","title":"Example nucleotide record","organism":"Escherichia coli","slen":8,"moltype":"genomic"}}}"""
                path.endsWith("/efetch.fcgi") -> genBank
                path.endsWith("/Blast.cgi") && exchange.requestMethod == "POST" -> "RID = RID123\nRTOE = 1\n"
                path.endsWith("/Blast.cgi") && query.contains("FORMAT_OBJECT=SearchInfo") ->
                    if (statusCalls.getAndIncrement() == 0) "Status=WAITING\n" else "Status=READY\n"
                path.endsWith("/Blast.cgi") ->
                    "# BLASTN\nquery\tJ01636.1\t100.0\t8\t0\t0\t1\t8\t1\t8\t1e-10\t16.5\n"
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
