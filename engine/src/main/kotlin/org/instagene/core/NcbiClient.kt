package org.instagene.core

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class NcbiHit(
    val accession: String,
    val title: String,
    val organism: String = "",
    val length: Int = 0,
    val moleculeType: String = "",
)

data class NcbiSearchResult(
    val hits: List<NcbiHit>,
    val rawXml: String,
    val totalCount: Int = hits.size,
)

data class BlastSubmission(val rid: String, val estimatedSeconds: Int? = null)

enum class BlastStatus { WAITING, READY, FAILED, UNKNOWN }

data class BlastStatusResult(val status: BlastStatus, val message: String = "")

data class BlastHit(
    val queryId: String,
    val subjectId: String,
    val percentIdentity: Double,
    val alignmentLength: Int,
    val mismatches: Int,
    val gapOpenings: Int,
    val queryStart: Int,
    val queryEnd: Int,
    val subjectStart: Int,
    val subjectEnd: Int,
    val eValue: Double,
    val bitScore: Double,
)

data class BlastSearchResult(val rid: String, val hits: List<BlastHit>, val rawReport: String = "")

/** Network-only NCBI integration. All requests are explicit and time-limited. */
class NcbiClient(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val baseUrl: String = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils",
    private val blastBaseUrl: String = "https://blast.ncbi.nlm.nih.gov/Blast.cgi",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun blastUrl(seq: Seq, program: String = "blastn", expect: Double = 100.0, selection: IntRange? = null): URI {
        val supported = setOf("blastn", "blastp", "blastx", "tblastn", "tblastx")
        require(program in supported) { "Unsupported BLAST program '$program'" }
        val subject = selection?.let { seq.sub(it.first, it.last + 1) } ?: seq.bases
        val encoded = URLEncoder.encode(subject, StandardCharsets.UTF_8)
        return URI.create("https://blast.ncbi.nlm.nih.gov/Blast.cgi?PROGRAM=$program&EXPECT=$expect&QUERY=$encoded")
    }

    /** Searches NCBI's nuccore database and returns accession-level summaries. */
    fun searchNucleotide(term: String, maxHits: Int = 20): NcbiSearchResult {
        require(term.isNotBlank()) { "NCBI search term cannot be blank" }
        require(maxHits in 1..10_000) { "maxHits must be between 1 and 10000" }
        val searchText = get(
            "$baseUrl/esearch.fcgi?db=nuccore&retmode=json&retmax=$maxHits&idtype=acc&term=${encode(term)}"
        )
        val search = json.parseToJsonElement(searchText).jsonObject["esearchresult"]?.jsonObject
            ?: error("NCBI search response did not contain esearchresult")
        val ids = search["idlist"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val totalCount = search["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ids.size
        if (ids.isEmpty()) return NcbiSearchResult(emptyList(), searchText, totalCount)

        val summaryText = get(
            "$baseUrl/esummary.fcgi?db=nuccore&retmode=json&id=${ids.joinToString(",") { encode(it) }}"
        )
        val result = json.parseToJsonElement(summaryText).jsonObject["result"]?.jsonObject
            ?: error("NCBI summary response did not contain result")
        val summaries = result.entries.mapNotNull { (key, value) ->
            val record = value as? JsonObject ?: return@mapNotNull null
            key to record
        }
        val hits = ids.mapNotNull { id ->
            val record = summaries.firstOrNull { (key, summary) ->
                key == id ||
                    summary.string("accessionversion") == id ||
                    summary.string("caption") == id ||
                    summary.string("uid") == id
            }?.second ?: return@mapNotNull null
            NcbiHit(
                accession = record.string("accessionversion") ?: record.string("caption") ?: id,
                title = record.string("title").orEmpty(),
                organism = record.string("organism").orEmpty(),
                length = record.int("slen"),
                moleculeType = record.string("moltype").orEmpty(),
            )
        }
        return NcbiSearchResult(hits, summaryText, totalCount)
    }

    fun fetchGenBank(accession: String): Seq {
        require(accession.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid NCBI accession" }
        val text = get("$baseUrl/efetch.fcgi?db=nuccore&rettype=gb&retmode=text&id=${encode(accession)}")
        return org.instagene.core.io.SeqIO.parse(text, accession)
    }

    /** Submits a nucleotide BLAST search against an NCBI nucleotide database. */
    fun submitBlastN(
        seq: Seq,
        selection: IntRange? = null,
        database: String = "core_nt",
        hitListSize: Int = 100,
    ): BlastSubmission {
        require(seq.kind != SeqKind.PROTEIN) { "BLASTN requires a nucleotide sequence" }
        require(hitListSize in 1..500) { "hitListSize must be between 1 and 500" }
        val subjectBases = selection?.let { seq.sub(it.first, it.last + 1) } ?: seq.bases
        require(subjectBases.isNotBlank()) { "BLASTN query cannot be empty" }
        val fasta = ">${seq.name.ifBlank { "InstaGene_query" }}\n" + subjectBases.chunked(80).joinToString("\n")
        val response = post(
            blastBaseUrl,
            mapOf(
                "CMD" to "Put",
                "PROGRAM" to "blastn",
                "DATABASE" to database,
                "QUERY" to fasta,
                "HITLIST_SIZE" to hitListSize.toString(),
                "TOOL" to "InstaGene",
            ),
        )
        val rid = Regex("(?im)^\\s*RID\\s*=\\s*(\\S+)").find(response)?.groupValues?.get(1)
            ?: error("NCBI BLAST response did not contain a request ID")
        val estimated = Regex("(?im)^\\s*RTOE\\s*=\\s*(\\d+)").find(response)?.groupValues?.get(1)?.toIntOrNull()
        return BlastSubmission(rid, estimated)
    }

    fun blastStatus(rid: String): BlastStatusResult {
        validateRid(rid)
        val response = get("$blastBaseUrl?CMD=Get&RID=${encode(rid)}&FORMAT_OBJECT=SearchInfo")
        val status = Regex("(?im)^\\s*Status\\s*=\\s*(\\S+)").find(response)?.groupValues?.get(1)?.uppercase()
        return when (status) {
            "WAITING" -> BlastStatusResult(BlastStatus.WAITING, response.trim())
            "READY" -> BlastStatusResult(BlastStatus.READY, response.trim())
            "FAILED", "UNKNOWN" -> BlastStatusResult(BlastStatus.FAILED, response.trim())
            else -> BlastStatusResult(BlastStatus.UNKNOWN, response.trim())
        }
    }

    /** Retrieves the standard 12-column BLAST tabular report. */
    fun fetchBlastResults(rid: String): BlastSearchResult {
        validateRid(rid)
        val report = get(
            "$blastBaseUrl?CMD=Get&RID=${encode(rid)}&FORMAT_TYPE=Text&ALIGNMENT_VIEW=Tabular"
        )
        val hits = report.lineSequence().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t')
            if (fields.size < 12) return@mapNotNull null
            runCatching {
                BlastHit(
                    queryId = fields[0],
                    subjectId = fields[1],
                    percentIdentity = fields[2].toDouble(),
                    alignmentLength = fields[3].toInt(),
                    mismatches = fields[4].toInt(),
                    gapOpenings = fields[5].toInt(),
                    queryStart = fields[6].toInt(),
                    queryEnd = fields[7].toInt(),
                    subjectStart = fields[8].toInt(),
                    subjectEnd = fields[9].toInt(),
                    eValue = fields[10].toDouble(),
                    bitScore = fields[11].toDouble(),
                )
            }.getOrNull()
        }.toList()
        return BlastSearchResult(rid, hits, report)
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int = string(name)?.toIntOrNull() ?: 0

    private fun validateRid(rid: String) {
        require(rid.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid BLAST request ID" }
    }

    private fun get(url: String): String = send(
        HttpRequest.newBuilder(URI.create(url)).GET()
    )

    private fun post(url: String, parameters: Map<String, String>): String {
        val body = parameters.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        )
    }

    private fun send(builder: HttpRequest.Builder): String {
        val request = builder
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "InstaGene/1.0")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("NCBI returned HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
