package org.instagene.app.web

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebServerTest {

    private val client = HttpClient.newHttpClient()

    private fun get(url: String): String =
        client.send(
            HttpRequest.newBuilder(URI(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    private fun post(url: String, body: String): String =
        client.send(
            HttpRequest.newBuilder(URI(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    private fun statusOf(request: HttpRequest): Int =
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()

    @Test
    fun rejectsBadRequestsAndWrongMethods() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"

            // Malformed JSON on both POST endpoints is a 400, not a 500.
            assertEquals(400, statusOf(HttpRequest.newBuilder(URI("$base/api/open"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{not json")).build()))
            assertEquals(400, statusOf(HttpRequest.newBuilder(URI("$base/api/op"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{not json")).build()))

            // GET is not allowed where POST is required (and vice versa).
            assertEquals(405, statusOf(HttpRequest.newBuilder(URI("$base/api/open")).GET().build()))
            assertEquals(405, statusOf(HttpRequest.newBuilder(URI("$base/api/op")).GET().build()))
            assertEquals(405, statusOf(HttpRequest.newBuilder(URI("$base/api/samples"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun rejectsOversizedRequestBodies() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"
            val request = HttpRequest.newBuilder(URI("$base/api/open"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4 * 1024 * 1024 + 1)))
                .build()
            assertEquals(413, statusOf(request))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun openIgnoresArbitraryPathAndNeverReadsFiles() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"
            // The old `path` option (arbitrary file read) is gone: the request is
            // accepted but falls back to the sample, and no file is returned.
            val resp = post("$base/api/open", """{"path":"/etc/hostname","sample":"PUC19_MCS"}""")
            assertTrue(resp.contains("\"bases\""))
            assertTrue(!resp.contains("localhost") || resp.contains("\"name\":\"pUC19_MCS\""))

            // Missing sample (no sample, no text) still resolves to the default sample.
            val empty = post("$base/api/open", """{}""")
            assertTrue(empty.contains("\"bases\""))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun bindsLoopbackOnly() {
        val server = WebServer.start(0)
        try {
            val address = server.address.address
            assertTrue(address.isLoopbackAddress, "Expected loopback bind, got ${address.hostAddress}")
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun servesIndexAndSamples() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"
            val index = get("$base/")
            assertTrue(index.contains("InstaGene"))
            assertTrue(index.contains("app.js"))

            val samples = get("$base/api/samples")
            assertTrue(samples.contains("GFP_CDS"))
            assertTrue(samples.contains("pUC19_MCS", ignoreCase = true))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun openAndTransformRoundTrip() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"
            val opened = post("$base/api/open", """{"sample":"PUC19_MCS"}""")
            assertTrue(opened.contains("\"bases\""))
            assertTrue(opened.contains("GAATTC"))

            val rc = post("$base/api/op", """{"op":"revcomp","seq":$opened}""")
            assertTrue(rc.contains("AAGCTT"))

            val translated = post("$base/api/op", """{"op":"translate","seq":$opened}""")
            assertTrue(translated.contains("protein"))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun analysesReturnText() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"
            val opened = post("$base/api/open", """{"sample":"GFP_CDS"}""")

            val digest = post("$base/api/op", """{"op":"digest","seq":$opened,"args":{"enzymes":"EcoRI,HinDIII"}}""")
            assertTrue(digest.contains("Fragments"))
            assertTrue(digest.contains("GAATTC") || digest.contains("EcoRI"))

            val info = post("$base/api/op", """{"op":"info","seq":$opened}""")
            assertTrue(info.contains("GC content") || info.contains("Length"))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun unknownOpReportsError() {
        val server = WebServer.start(0)
        try {
            val base = "http://localhost:${server.address.port}"
            val opened = post("$base/api/open", """{"sample":"PUC19_MCS"}""")
            val bad = post("$base/api/op", """{"op":"nope","seq":$opened}""")
            assertTrue(bad.contains("\"error\""))
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun secondStartIsRejectedUntilStopped() {
        WebServer.start(0)
        try {
            val second = runCatching { WebServer.start(0) }
            assertTrue(second.isFailure)
            assertTrue(second.exceptionOrNull()!!.message!!.contains("already running"))
        } finally {
            WebServer.stop()
        }
        // After a stop the server can start again.
        try {
            WebServer.start(0)
        } finally {
            WebServer.stop()
        }
    }

    @Test
    fun canBindAllInterfacesForSharing() {
        val server = WebServer.start("0.0.0.0", 0)
        try {
            val base = "http://localhost:${server.address.port}"
            assertEquals(200, statusOf(HttpRequest.newBuilder(URI("$base/api/samples")).GET().build()))
        } finally {
            WebServer.stop()
        }
    }
}
