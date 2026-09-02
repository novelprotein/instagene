package org.instagene.core

import kotlinx.serialization.json.*
import org.instagene.core.io.Fasta
import org.instagene.core.io.GenBank
import org.instagene.core.io.SeqIO
import org.instagene.core.io.SeqIOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration

data class NcbiHit(
    val accession: String,
    val title: String,
    val organism: String = "",
    val length: Int = 0,
    val moleculeType: String = "",
)

/** Publication metadata resolved from a PubMed record. */
data class NcbiPublication(
    val pubMed: String,
    val title: String = "",
    val authors: String = "",
    val journal: String = "",
    val sourceUrl: String = NcbiClient.pubMedUrl(pubMed),
)

data class NcbiSearchResult(
    val hits: List<NcbiHit>,
    val rawXml: String,
    val totalCount: Int = hits.size,
    /** One provenance record for each NCBI response used to build this result. */
    val provenance: List<OnlineFetchProvenance> = emptyList(),
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

/**
 * Explicit, time-limited NCBI integration.
 *
 * Search and GenBank retrieval may use an [OnlineCache] only when callers
 * select a non-default [onlineCacheMode].  BLAST submissions and polling stay
 * network-only because they are stateful remote jobs rather than immutable
 * retrievals.
 */
class NcbiClient(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val baseUrl: String = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils",
    private val blastBaseUrl: String = "https://blast.ncbi.nlm.nih.gov/Blast.cgi",
    private val onlineCache: OnlineCache? = null,
    private val onlineCacheMode: OnlineCacheMode = OnlineCacheMode.NETWORK_ONLY,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        require(onlineCache != null || onlineCacheMode == OnlineCacheMode.NETWORK_ONLY) {
            "An online cache directory is required for ${onlineCacheMode.name.lowercase().replace('_', '-')} mode"
        }
    }

    /** Returns an equivalent client with an explicitly selected retrieval cache policy. */
    fun withOnlineCache(cache: OnlineCache?, mode: OnlineCacheMode): NcbiClient = NcbiClient(
        http = http,
        baseUrl = baseUrl,
        blastBaseUrl = blastBaseUrl,
        onlineCache = cache,
        onlineCacheMode = mode,
        clock = clock,
    )

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
        val searchResponse = getFetched(
            "$baseUrl/esearch.fcgi?db=nuccore&retmode=json&retmax=$maxHits&idtype=acc&term=${encode(term)}",
            cacheable = true,
        )
        val searchText = searchResponse.body
        val search = json.parseToJsonElement(searchText).jsonObject["esearchresult"]?.jsonObject
            ?: error("NCBI search response did not contain esearchresult")
        val ids = search["idlist"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val totalCount = search["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ids.size
        if (ids.isEmpty()) return NcbiSearchResult(
            hits = emptyList(),
            rawXml = searchText,
            totalCount = totalCount,
            provenance = listOf(searchResponse.provenance),
        )

        val summaryResponse = getFetched(
            "$baseUrl/esummary.fcgi?db=nuccore&retmode=json&id=${ids.joinToString(",") { encode(it) }}",
            cacheable = true,
        )
        val summaryText = summaryResponse.body
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
        return NcbiSearchResult(hits, summaryText, totalCount, listOf(searchResponse.provenance, summaryResponse.provenance))
    }

    fun fetchGenBank(accession: String): Seq {
        require(accession.matches(RID_VALIDATE)) { "Invalid NCBI accession" }
        val genBankResponse = getFetched(
            "$baseUrl/efetch.fcgi?db=nuccore&rettype=gb&retmode=text&id=${encode(accession)}",
            cacheable = true,
        )
        val text = genBankResponse.body
        if (!GenBank.looksLikeGenBank(text)) {
            throw SeqIOException("NCBI fetch for $accession did not return GenBank text")
        }
        val annotated = SeqIO.parse(text, accession)
        if (annotated.bases.isNotBlank()) return withFetchProvenance(annotated, accession, listOf(genBankResponse.provenance))
        if ("CONTIG" !in annotated.metadata && "WGS" !in annotated.metadata) {
            throw SeqIOException("NCBI fetch for $accession did not include sequence bases")
        }

        val fastaResponse = getFetched(
            "$baseUrl/efetch.fcgi?db=nuccore&rettype=fasta&retmode=text&id=${encode(accession)}",
            cacheable = true,
        )
        val fastaText = fastaResponse.body
        if (!fastaText.trimStart().startsWith(">")) {
            throw SeqIOException("NCBI record $accession has no retrievable sequence bases")
        }
        val fasta = Fasta.parse(fastaText, accession)
        if (fasta.bases.isBlank()) {
            throw SeqIOException("NCBI record $accession has no retrievable sequence bases")
        }
        return withFetchProvenance(annotated.copy(
            bases = fasta.bases,
            kind = fasta.kind,
            features = clipFeatures(annotated.features, fasta.length),
        ), accession, listOf(genBankResponse.provenance, fastaResponse.provenance))
    }

    /** Resolves bibliographic fields for a PubMed identifier through ESummary. */
    fun fetchPublication(pubMed: String): NcbiPublication {
        val id = pubMed.trim()
        require(id.matches(Regex("\\d+"))) { "Invalid PubMed identifier" }
        val response = getFetched(
            "$baseUrl/esummary.fcgi?db=pubmed&retmode=json&id=${encode(id)}",
            cacheable = true,
        )
        val result = json.parseToJsonElement(response.body).jsonObject["result"]?.jsonObject
            ?: error("NCBI publication response did not contain result")
        val record = result[id] as? JsonObject ?: error("PubMed record $id was not found")
        val authors = record["authors"]?.jsonArray.orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.joinToString(", ")
        return NcbiPublication(
            pubMed = id,
            title = record.string("title").orEmpty(),
            authors = authors,
            journal = record.string("fulljournalname") ?: record.string("source").orEmpty(),
        )
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
        val rid = RID_PATTERN.find(response)?.groupValues?.get(1)
            ?: error("NCBI BLAST response did not contain a request ID")
        val estimated = RTOE_PATTERN.find(response)?.groupValues?.get(1)?.toIntOrNull()
        return BlastSubmission(rid, estimated)
    }

    fun blastStatus(rid: String): BlastStatusResult {
        validateRid(rid)
        val response = get("$blastBaseUrl?CMD=Get&RID=${encode(rid)}&FORMAT_OBJECT=SearchInfo")
        val status = STATUS_PATTERN.find(response)?.groupValues?.get(1)?.uppercase()
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
        require(rid.matches(RID_VALIDATE)) { "Invalid BLAST request ID" }
    }

    private fun clipFeatures(features: List<Feature>, length: Int): List<Feature> =
        features.mapNotNull { feature ->
            val clippedEnd = minOf(feature.end, length)
            if (clippedEnd > feature.start) feature.copy(end = clippedEnd) else null
        }.sortedBy { it.start }

    private fun withFetchProvenance(seq: Seq, accession: String, responses: List<OnlineFetchProvenance>): Seq {
        val first = responses.first()
        val origins = responses.joinToString(",") { it.origin.name.lowercase().replace('_', '-') }
        val fallbackReasons = responses.mapNotNull { it.fallbackReason?.takeIf(String::isNotBlank) }
        val metadata = linkedMapOf<String, String>().apply {
            putAll(first.metadata())
            put("ONLINE_ACCESSION", accession)
            put("ONLINE_REQUESTS", responses.joinToString("\n") { it.request })
            put("ONLINE_REQUEST_KEYS", responses.joinToString(",") { it.requestKey })
            put("ONLINE_RESPONSE_SHA256S", responses.joinToString(",") { it.responseSha256 })
            put("ONLINE_ORIGINS", origins)
            if (fallbackReasons.isNotEmpty()) put("ONLINE_FALLBACK_REASONS", fallbackReasons.joinToString(" | "))
        }
        val usedCache = responses.any { it.origin != OnlineFetchOrigin.NETWORK }
        return seq.copy(
            metadata = seq.metadata + metadata,
            provenance = seq.provenance + ProcedureRecord(
                operation = "ncbi-fetch",
                summary = "Retrieved $accession from NCBI (${if (usedCache) "cached response used" else "network response"})",
                inputs = responses.map { it.request },
                warnings = fallbackReasons,
                timestamp = first.fetchedAt.toEpochMilli(),
            ),
        )
    }

    private fun get(url: String): String = getFetched(url, cacheable = false).body

    private fun getFetched(url: String, cacheable: Boolean): OnlineFetch {
        val network = { send(HttpRequest.newBuilder(URI.create(url)).GET()) }
        if (!cacheable || onlineCache == null) {
            val body = network()
            return OnlineFetch(body, OnlineFetchProvenance.network("NCBI", url, body, clock))
        }
        return onlineCache.fetch("NCBI", url, onlineCacheMode, network)
    }

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
        if (response.statusCode() !in 200..299) throw java.io.IOException("NCBI returned HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val RID_PATTERN = Regex("(?im)^\\s*RID\\s*=\\s*(\\S+)")
        private val RTOE_PATTERN = Regex("(?im)^\\s*RTOE\\s*=\\s*(\\d+)")
        private val STATUS_PATTERN = Regex("(?im)^\\s*Status\\s*=\\s*(\\S+)")
        private val RID_VALIDATE = Regex("[A-Za-z0-9_.-]+")

        /** Canonical browser URL for a PubMed identifier. */
        fun pubMedUrl(id: String): String = "https://pubmed.ncbi.nlm.nih.gov/${id.trim()}/"

        /** Canonical browser URL for an NCBI nucleotide accession. */
        fun nuccoreUrl(accession: String): String = "https://www.ncbi.nlm.nih.gov/nuccore/${accession.trim()}"

        /** Extracts a PubMed ID from a PubMed URL, PMID label, or bare numeric ID. */
        fun extractPubMedId(raw: String): String? {
            val value = raw.trim()
            Regex("(?i)pubmed\\.ncbi\\.nlm\\.nih\\.gov/(\\d+)").find(value)?.let { return it.groupValues[1] }
            Regex("(?i)(?:www\\.)?ncbi\\.nlm\\.nih\\.gov/(?:pubmed|entrez/pubmed)/(\\d+)").find(value)?.let { return it.groupValues[1] }
            Regex("(?i)^pmid\\s*[:#]?\\s*(\\d+)$").find(value)?.let { return it.groupValues[1] }
            return value.takeIf { it.matches(Regex("\\d{4,9}")) }
        }

        /** Returns a canonical NCBI URL when the input identifies an NCBI record. */
        fun canonicalReferenceUrl(raw: String): String {
            val value = raw.trim()
            extractPubMedId(value)?.let { return pubMedUrl(it) }
            if (value.matches(Regex("(?i)[A-Z]{1,5}_?\\d+(?:\\.\\d+)?"))) return nuccoreUrl(value)
            return value
        }
    }
}
