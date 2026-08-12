package org.instagene.app.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.instagene.core.CodonTable
import org.instagene.core.Digest
import org.instagene.core.Enzymes
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.core.io.SeqIO
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The HTML5 front-end: a small HTTP server (JDK `HttpServer`, no extra
 * dependencies) that serves the web UI and exposes the engine over JSON.
 */
object WebServer {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    private class RequestTooLarge : IllegalArgumentException("Request body exceeds 4 MiB")

    /** `/api/open` request: open a bundled [sample] or parse pasted [text]. */
    @Serializable
    data class OpenRequest(val sample: String? = null, val text: String? = null)

    /** `/api/op` request: run operation [op] over [seq], with optional [args]. */
    @Serializable
    data class OpRequest(val op: String, val seq: Seq, val args: Map<String, String> = emptyMap())

    /** `/api/op` response: the transformed [seq], a [text] result, or an [error]. */
    @Serializable
    data class OpResult(val op: String, val seq: Seq? = null, val text: String? = null, val error: String? = null)

    @Volatile
    private var running: HttpServer? = null

    private var executor: ExecutorService? = null

    /**
     * Starts the server on the loopback interface ([port] of 0 binds an
     * ephemeral port, used by tests). See [start] with an explicit host.
     */
    fun start(port: Int = 8080): HttpServer = start("127.0.0.1", port)

    /**
     * Starts the server bound to [host]; a port of 0 binds an ephemeral port
     * (used by tests).
     *
     * The default host is the loopback interface, so the server is reachable
     * only from this machine — use `0.0.0.0` (e.g. `--share`) to serve the
     * whole network. The web front-end opens only through this explicit call,
     * and at most once per JVM — a second call while a server is running is
     * rejected.
     */
    fun start(host: String, port: Int): HttpServer {
        synchronized(this) {
            running?.let {
                throw IllegalStateException("Web front-end already running on port ${it.address.port}")
            }
            val pool = Executors.newFixedThreadPool(4)
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(host), port), 0)
            server.executor = pool
            server.createContext("/api/", ::handleApi)
            server.createContext("/", ::handleStatic)
            server.start()
            running = server
            executor = pool
            return server
        }
    }

    /** Stops the running server (no-op when none is running). */
    fun stop() {
        synchronized(this) {
            running?.stop(0)
            running = null
            executor?.shutdownNow()
            executor = null
        }
    }

    private fun handleApi(exchange: HttpExchange) {
        try {
            when (exchange.requestURI.path) {
                "/api/samples" -> if (exchange.requestMethod == "GET") {
                    respondJson(exchange, 200, json.encodeToString(SeqIO.Samples.ALL.map { it.name }))
                } else {
                    methodNotAllowed(exchange)
                }

                "/api/open" -> if (exchange.requestMethod == "POST") {
                    handleOpen(exchange)
                } else {
                    methodNotAllowed(exchange)
                }

                "/api/op" -> if (exchange.requestMethod == "POST") {
                    handleOp(exchange)
                } else {
                    methodNotAllowed(exchange)
                }

                else -> respondJson(exchange, 404, """{"error":"Not found"}""")
            }
        } catch (e: RequestTooLarge) {
            respondJson(exchange, 413, json.encodeToString(OpResult("", error = e.message)))
        } catch (e: Exception) {
            respondJson(exchange, 500, json.encodeToString(OpResult("", error = "Server error: ${e.message}")))
        }
    }

    private fun methodNotAllowed(exchange: HttpExchange) {
        exchange.responseHeaders.add("Allow", "POST")
        respondJson(exchange, 405, """{"error":"Method not allowed"}""")
    }

    private fun handleOpen(exchange: HttpExchange) {
        val req = try {
            json.decodeFromString<OpenRequest>(readBody(exchange))
        } catch (e: RequestTooLarge) {
            throw e
        } catch (e: Exception) {
            respondJson(exchange, 400, """{"error":"Bad request: ${e.message}"}""")
            return
        }
        // Only bundled samples and pasted text are accepted: the server must not
        // hand out arbitrary files from this machine.
        val seq = when {
            !req.text.isNullOrBlank() -> SeqIO.parse(req.text, "pasted")
            else -> SeqIO.Samples.ALL.firstOrNull { it.name.equals(req.sample, ignoreCase = true) }
                ?: SeqIO.Samples.PUC19_MCS
        }
        respondJson(exchange, 200, json.encodeToString(seq))
    }

    private fun handleOp(exchange: HttpExchange) {
        val req = try {
            json.decodeFromString<OpRequest>(readBody(exchange))
        } catch (e: RequestTooLarge) {
            throw e
        } catch (e: Exception) {
            respondJson(exchange, 400, """{"error":"Bad request: ${e.message}"}""")
            return
        }
        val result = try {
            runOp(req)
        } catch (e: Exception) {
            OpResult(req.op, error = e.message ?: "Operation failed")
        }
        respondJson(exchange, 200, json.encodeToString(result))
    }

    private fun runOp(req: OpRequest): OpResult {
        val seq = req.seq
        val a = req.args
        return when (req.op) {
            "revcomp" -> seqResult("revcomp", seq.reverseComplement())
            "complement" -> seqResult("complement", seq.complement())
            "transcribe" -> seqResult("transcribe", SeqOps.transcribe(seq))
            "backtranscribe" -> seqResult("backtranscribe", SeqOps.backTranscribe(seq))
            "translate" -> seqResult(
                "translate",
                SeqOps.translate(
                    seq,
                    (a["frame"]?.toIntOrNull() ?: 1) - 1,
                    CodonTable.byId(a["table"]?.toIntOrNull() ?: 1),
                    a["stop"] == "true",
                ),
            )

            "extract" -> {
                val from = a["from"]?.toIntOrNull() ?: 1
                val to = a["to"]?.toIntOrNull() ?: seq.length
                var piece = seq.subSeq(from - 1, to)
                if (a["revcomp"] == "true") piece = piece.reverseComplement()
                seqResult("extract", piece)
            }

            "rotate" -> {
                val origin = a["origin"]?.toIntOrNull() ?: 1
                seqResult("rotate", seq.rotateOrigin(origin - 1))
            }

            "info" -> textResult("info", infoText(seq))
            "gc" -> textResult("gc", "${round1(SeqOps.gcContent(seq))} %")
            "tm" -> textResult("tm", "${round1(SeqOps.meltingTemp(seq.bases))} C")
            "orf" -> textResult("orf", orfText(seq, a))
            "find" -> textResult("find", findText(seq, a))
            "digest" -> textResult("digest", digestText(seq, a))
            else -> throw IllegalArgumentException("Unknown operation '${req.op}'")
        }
    }

    private fun seqResult(op: String, seq: Seq) = OpResult(op, seq = seq)

    private fun textResult(op: String, text: String) = OpResult(op, text = text)

    private fun infoText(seq: Seq): String = buildString {
        append("Name        ").append(seq.name).append('\n')
        if (seq.description.isNotBlank()) append("Description ").append(seq.description).append('\n')
        append("Type        ").append(seq.kind.name.lowercase()).append('\n')
        append("Topology    ").append(seq.topology.name.lowercase()).append('\n')
        append("Length      ").append(seq.length).append(if (seq.kind == SeqKind.PROTEIN) " aa" else " bp").append('\n')
        append("GC content  ").append(round1(SeqOps.gcContent(seq))).append(" %").append('\n')
        append("Tm          ").append(round1(SeqOps.meltingTemp(seq.bases))).append(" C").append('\n')
        val counts = SeqOps.baseCounts(seq.bases)
        append("Composition ").append(counts.entries.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
        if (seq.features.isNotEmpty()) {
            append("Features\n")
            seq.features.forEach {
                append("  %-20s %-14s %s %s".format(it.name.take(20), it.type, it.displayRange(), it.strand.symbol))
                    .append('\n')
            }
        }
        val cutters = Digest.enzymesCutting(seq, 1)
        if (cutters.isNotEmpty()) {
            append("Unique cutters (").append(cutters.size).append("): ")
                .append(cutters.joinToString(", ") { it.name }).append('\n')
        }
    }

    private fun orfText(seq: Seq, a: Map<String, String>): String {
        val table = CodonTable.byId(a["table"]?.toIntOrNull() ?: 1)
        val minAa = a["min-aa"]?.toIntOrNull() ?: 30
        val orfs = SeqOps.findOrfs(seq, minAa, table, a["forward-only"] != "true")
        if (orfs.isEmpty()) return "No ORFs of at least $minAa aa found."
        return buildString {
            append("%-10s %-10s %-7s %-6s %s".format("start", "end", "strand", "aa", "protein")).append('\n')
            orfs.forEach {
                append("%-10d %-10d %-7s %-6d %s".format(it.start + 1, it.end, it.strand.symbol, it.lengthAa, it.protein.take(60)))
                    .append('\n')
            }
            append("${orfs.size} ORF(s).")
        }
    }

    private fun findText(seq: Seq, a: Map<String, String>): String {
        val pattern = a["pattern"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing 'pattern' argument")
        val hits = SeqOps.find(seq, pattern, a["forward-only"] != "true")
        if (hits.isEmpty()) return "No hits for $pattern in ${seq.name}."
        return buildString {
            hits.forEach { (pos, strand) -> append(pos + 1).append('\t').append(strand.symbol).append('\t').append(pattern).append('\n') }
            append("${hits.size} hit(s) for $pattern in ${seq.name}")
        }
    }

    private fun digestText(seq: Seq, a: Map<String, String>): String {
        val enzymes = a["enzymes"]?.takeIf { it.isNotBlank() }?.let { Enzymes.parseList(it) } ?: Enzymes.ALL
        val sites = Digest.cutSites(seq, enzymes)
        val fragments = Digest.digest(seq, enzymes)
        return buildString {
            append("Cut sites in ${seq.name} (${seq.length} bp, ${seq.topology.name.lowercase()}):").append('\n')
            if (sites.isEmpty()) {
                append("  none").append('\n')
            } else {
                sites.forEach {
                    append("  %-10s at %-8d site %-10s %s".format(it.enzyme.name, it.topCut + 1, it.enzyme.notation(), it.enzyme.endType.label))
                        .append('\n')
                }
            }
            append('\n')
            append("Fragments (${fragments.size}):").append('\n')
            append("  %-6s %-10s %-10s %-22s %s".format("#", "length", "start", "left end", "right end")).append('\n')
            fragments.forEachIndexed { i, f ->
                append("  %-6d %-10d %-10d %-22s %s".format(i + 1, f.length, f.start + 1, f.leftEnd, f.rightEnd)).append('\n')
            }
        }
    }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    // --------------------------------------------------------------- HTTP plumbing

    private fun handleStatic(exchange: HttpExchange) {
        // Reject traversal: only serve files directly under the bundled /web tree.
        val requested = exchange.requestURI.path.removePrefix("/")
        if (requested.any { it == '/' }) {
            respond(exchange, 404, "Not found", "text/plain")
            return
        }
        val path = requested.ifEmpty { "index.html" }
        val resource = WebServer::class.java.getResource("/web/$path")
        if (resource == null) {
            respond(exchange, 404, "Not found", "text/plain")
            return
        }
        val type = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            else -> "application/octet-stream"
        }
        respond(exchange, 200, resource.readText(), type)
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: String) =
        respond(exchange, status, body, "application/json")

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "$contentType; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readBody(exchange: HttpExchange): String {
        if (exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let { it > MAX_REQUEST_BYTES } == true) {
            throw RequestTooLarge()
        }
        exchange.requestBody.reader(Charsets.UTF_8).use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(8 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) return out.toString()
                if (out.length + read > MAX_REQUEST_BYTES) throw RequestTooLarge()
                out.append(buffer, 0, read)
            }
        }
    }
}
