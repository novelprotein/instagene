package org.instagene.app.web

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
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
}
