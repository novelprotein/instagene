package org.instagene.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NcbiClientTest {
    @Test
    fun parsesCurrentNcbiSearchSummaryAndFetchesGenBank() {
        withServer { base ->
            val client = NcbiClient(
                http = HttpClient.newHttpClient(),
                baseUrl = "$base/entrez/eutils",
                blastBaseUrl = "$base/Blast.cgi",
            )

            val result = client.searchNucleotide("J01636.1")
            assertEquals(1, result.totalCount)
            assertEquals("J01636.1", result.hits.single().accession)
            assertEquals("Example nucleotide record", result.hits.single().title)
            assertEquals("Escherichia coli", result.hits.single().organism)
            assertEquals(8, result.hits.single().length)
            assertEquals("genomic", result.hits.single().moleculeType)

            val fetched = client.fetchGenBank("J01636.1")
            assertEquals("J01636.1", fetched.name)
            assertEquals("ACGTACGT", fetched.bases)
        }
    }

    @Test
    fun submitsPollsAndParsesBlastTabularResults() {
        withServer { base ->
            val client = NcbiClient(
                http = HttpClient.newHttpClient(),
                baseUrl = "$base/entrez/eutils",
                blastBaseUrl = "$base/Blast.cgi",
            )
            val submission = client.submitBlastN(Seq(name = "query", bases = "ACGTACGT"))
            assertEquals("RID123", submission.rid)
            assertEquals(2, submission.estimatedSeconds)

            assertEquals(BlastStatus.WAITING, client.blastStatus(submission.rid).status)
            assertEquals(BlastStatus.READY, client.blastStatus(submission.rid).status)
            val result = client.fetchBlastResults(submission.rid)
            val hit = result.hits.single()
            assertEquals("query", hit.queryId)
            assertEquals("J01636.1", hit.subjectId)
            assertEquals(100.0, hit.percentIdentity)
            assertEquals(8, hit.alignmentLength)
            assertEquals(1e-10, hit.eValue)
            assertEquals(16.5, hit.bitScore)
        }
    }

    private fun withServer(block: (String) -> Unit) {
        val statusCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("/esearch.fcgi") ->
                    """{"header":{"type":"esearch"},"esearchresult":{"count":"1","idlist":["J01636.1"]}}"""
                path.endsWith("/esummary.fcgi") ->
                    """{"result":{"uids":["J01636.1"],"J01636.1":{"accessionversion":"J01636.1","caption":"J01636","title":"Example nucleotide record","organism":"Escherichia coli","slen":8,"moltype":"genomic"}}}"""
                path.endsWith("/efetch.fcgi") -> genBank
                path.endsWith("/Blast.cgi") && exchange.requestMethod == "POST" -> "RID = RID123\nRTOE = 2\n"
                path.endsWith("/Blast.cgi") && query.contains("FORMAT_OBJECT=SearchInfo") ->
                    if (statusCalls.getAndIncrement() == 0) "Status=WAITING\n" else "Status=READY\n"
                path.endsWith("/Blast.cgi") ->
                    "# BLASTN 2.0\nquery\tJ01636.1\t100.0\t8\t0\t0\t1\t8\t1\t8\t1e-10\t16.5\n"
                else -> error("Unexpected request: ${exchange.requestURI}")
            }
            respond(exchange, body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
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
}
