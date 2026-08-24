package org.instagene.core

import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnlineCacheTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun networkOnlyDoesNotCreateAResponseFile() = withCache { cache ->
        val result = cache.fetch("fixture", "https://example.test/record", OnlineCacheMode.NETWORK_ONLY) { "fresh" }

        assertEquals("fresh", result.body)
        assertEquals(OnlineFetchOrigin.NETWORK, result.provenance.origin)
        assertFalse(cache.entryFile("fixture", "https://example.test/record").exists())
    }

    @Test
    fun preferCacheWritesAndReusesAHashVerifiedResponse() = withCache { cache ->
        var calls = 0
        val first = cache.fetch("fixture", "record-1", OnlineCacheMode.PREFER_CACHE) {
            calls++
            "first response"
        }
        val second = cache.fetch("fixture", "record-1", OnlineCacheMode.PREFER_CACHE) {
            calls++
            "unexpected"
        }

        assertEquals(1, calls)
        assertEquals(OnlineFetchOrigin.NETWORK, first.provenance.origin)
        assertEquals(OnlineFetchOrigin.CACHE, second.provenance.origin)
        assertEquals("first response", second.body)
        assertEquals("2026-08-23T12:00:00Z", second.provenance.fetchedAt.toString())
        assertEquals(OnlineCache.responseSha256("first response"), second.provenance.responseSha256)
        assertEquals("cache", second.provenance.metadata()["ONLINE_ORIGIN"])
        assertTrue(cache.entryFile("fixture", "record-1").isFile)
    }

    @Test
    fun cacheOnlyExplainsAMissingExplicitRequest() = withCache { cache ->
        val error = assertFailsWith<OnlineCacheMissException> {
            cache.fetch("fixture", "missing", OnlineCacheMode.CACHE_ONLY) { error("network must not run") }
        }

        assertTrue(error.message.orEmpty().contains("No verified cached response"))
    }

    @Test
    fun networkThenCacheUsesVerifiedFallbackAndRecordsTheFailure() = withCache { cache ->
        cache.fetch("fixture", "record-2", OnlineCacheMode.PREFER_CACHE) { "saved response" }

        val result = cache.fetch("fixture", "record-2", OnlineCacheMode.NETWORK_THEN_CACHE) {
            throw IOException("offline for test")
        }

        assertEquals("saved response", result.body)
        assertEquals(OnlineFetchOrigin.CACHE_FALLBACK, result.provenance.origin)
        assertTrue(result.provenance.fallbackReason.orEmpty().contains("offline for test"))
    }

    @Test
    fun damagedPayloadIsRejectedRatherThanReturned() = withCache { cache ->
        val source = "fixture"
        val request = "record-3"
        cache.entryFile(source, request).apply {
            parentFile.mkdirs()
            writeText("{ definitely not JSON")
        }

        val error = assertFailsWith<OnlineCacheCorruptionException> {
            cache.fetch(source, request, OnlineCacheMode.CACHE_ONLY) { error("network must not run") }
        }

        assertTrue(error.message.orEmpty().contains("not valid JSON"))
    }

    private fun withCache(block: (OnlineCache) -> Unit) {
        val directory = Files.createTempDirectory("instagene-online-cache-").toFile()
        try {
            block(OnlineCache(directory, clock))
        } finally {
            directory.deleteRecursively()
        }
    }
}
