package org.instagene.core

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class NcbiHit(val accession: String, val title: String)
data class NcbiSearchResult(val hits: List<NcbiHit>, val rawXml: String)

/** Optional, network-only NCBI integration. All requests are explicit and time-limited. */
class NcbiClient(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val baseUrl: String = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils",
) {
    fun blastUrl(seq: Seq, program: String = "blastn", expect: Double = 100.0, selection: IntRange? = null): URI {
        val supported = setOf("blastn", "blastp", "blastx", "tblastn", "tblastx")
        require(program in supported) { "Unsupported BLAST program '$program'" }
        val subject = selection?.let { seq.sub(it.first, it.last + 1) } ?: seq.bases
        val encoded = URLEncoder.encode(subject, StandardCharsets.UTF_8)
        return URI.create("https://blast.ncbi.nlm.nih.gov/Blast.cgi?PROGRAM=$program&EXPECT=$expect&QUERY=$encoded")
    }

    fun searchNucleotide(term: String, maxHits: Int = 20): NcbiSearchResult {
        require(term.isNotBlank()) { "NCBI search term cannot be blank" }
        require(maxHits in 1..10_000) { "maxHits must be between 1 and 10000" }
        val xml = get("$baseUrl/esearch.fcgi?db=nuccore&retmode=json&retmax=$maxHits&term=${encode(term)}")
        val ids = Regex("\"IdList\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.let { Regex("\"(\\d+)\"").findAll(it).map { match -> match.groupValues[1] }.toList() }
            ?: emptyList()
        if (ids.isEmpty()) return NcbiSearchResult(emptyList(), xml)
        val summary = get("$baseUrl/esummary.fcgi?db=nuccore&retmode=json&id=${ids.joinToString(",")}")
        val hits = Regex("\"uid\":\"?(\\d+)\"?.*?\"caption\":\"(.*?)\".*?\"title\":\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(summary).map { NcbiHit(it.groupValues[2], it.groupValues[3]) }.toList()
        return NcbiSearchResult(hits, summary)
    }

    fun fetchGenBank(accession: String): Seq {
        require(accession.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid NCBI accession" }
        val text = get("$baseUrl/efetch.fcgi?db=nuccore&rettype=gb&retmode=text&id=${encode(accession)}")
        return org.instagene.core.io.SeqIO.parse(text, accession)
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "InstaGene/1.0").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("NCBI returned HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
