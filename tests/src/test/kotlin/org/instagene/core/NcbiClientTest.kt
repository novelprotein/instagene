package org.instagene.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.instagene.core.io.SeqIOException
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun mapsNcbiSummaryRecordsKeyedByNumericUid() {
        withServer(numericSummary = true) { base ->
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
        }
    }

    @Test
    fun fetchGenBankFailsClearlyWhenNcbiReturnsNonGenBankText() {
        withServer(efetchBody = "Error: failed to retrieve sequence") { base ->
            val client = NcbiClient(
                http = HttpClient.newHttpClient(),
                baseUrl = "$base/entrez/eutils",
                blastBaseUrl = "$base/Blast.cgi",
            )

            val error = assertFailsWith<SeqIOException> { client.fetchGenBank("J01636.1") }

            assertEquals("NCBI fetch for J01636.1 did not return GenBank text", error.message)
        }
    }

    @Test
    fun fetchGenBankUsesFastaBasesForContigRecordsAndKeepsFeatures() {
        withServer(
            efetchBody = contigGenBank,
            efetchFastaBody = ">NZ_CONTIG.1 assembled scaffold\nAACCGGTTAACC\n",
        ) { base ->
            val client = NcbiClient(
                http = HttpClient.newHttpClient(),
                baseUrl = "$base/entrez/eutils",
                blastBaseUrl = "$base/Blast.cgi",
            )

            val fetched = client.fetchGenBank("NZ_CONTIG.1")

            assertEquals("NZ_CONTIG.1", fetched.name)
            assertEquals("AACCGGTTAACC", fetched.bases)
            assertEquals("join(ABC123.1:1..12)", fetched.metadata["CONTIG"])
            assertEquals("kept", fetched.features.single { it.type == "CDS" }.name)
            assertEquals(1, fetched.features.single { it.type == "CDS" }.start)
            assertEquals(8, fetched.features.single { it.type == "CDS" }.end)
        }
    }

    @Test
    fun fetchGenBankFailsClearlyWhenSequenceReferenceRecordHasNoRetrievableFastaBases() {
        withServer(efetchBody = wgsMasterGenBank, efetchFastaBody = "\n") { base ->
            val client = NcbiClient(
                http = HttpClient.newHttpClient(),
                baseUrl = "$base/entrez/eutils",
                blastBaseUrl = "$base/Blast.cgi",
            )

            val error = assertFailsWith<SeqIOException> { client.fetchGenBank("NZ_CONTIG.1") }

            assertEquals("NCBI record NZ_CONTIG.1 has no retrievable sequence bases", error.message)
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

    private fun withServer(
        numericSummary: Boolean = false,
        efetchBody: String? = null,
        efetchFastaBody: String? = null,
        block: (String) -> Unit,
    ) {
        val statusCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("/esearch.fcgi") ->
                    """{"header":{"type":"esearch"},"esearchresult":{"count":"1","idlist":["J01636.1"]}}"""
                path.endsWith("/esummary.fcgi") ->
                    if (numericSummary) {
                        """{"result":{"uids":["12345"],"12345":{"uid":"12345","accessionversion":"J01636.1","caption":"J01636","title":"Example nucleotide record","organism":"Escherichia coli","slen":8,"moltype":"genomic"}}}"""
                    } else {
                        """{"result":{"uids":["J01636.1"],"J01636.1":{"accessionversion":"J01636.1","caption":"J01636","title":"Example nucleotide record","organism":"Escherichia coli","slen":8,"moltype":"genomic"}}}"""
                    }
                path.endsWith("/efetch.fcgi") ->
                    if (query.contains("rettype=fasta")) efetchFastaBody.orEmpty() else efetchBody ?: genBank
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

    private val contigGenBank = """
        LOCUS       NZ_CONTIG.1              12 bp    DNA     linear   CON 01-JAN-2024
        DEFINITION  Assembled scaffold.
        ACCESSION   NZ_CONTIG
        VERSION     NZ_CONTIG.1
        FEATURES             Location/Qualifiers
             source          1..12
                             /organism="synthetic construct"
             CDS             2..8
                             /gene="kept"
        CONTIG      join(ABC123.1:1..12)
        //
    """.trimIndent()

    private val wgsMasterGenBank = """
        LOCUS       NZ_CONTIG.1              12 rc    DNA     linear   BCT 01-JAN-2024
        DEFINITION  Whole genome shotgun sequencing project.
        ACCESSION   NZ_CONTIG
        VERSION     NZ_CONTIG.1
        FEATURES             Location/Qualifiers
             source          1..12
                             /organism="synthetic construct"
        WGS         ABC123000001-ABC123000012
        //
    """.trimIndent()
}
