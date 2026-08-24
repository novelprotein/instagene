package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * The explicit policy for a persisted online response cache.  [NETWORK_ONLY]
 * is deliberately the default: opening a remote service must never start
 * retaining requests or results without the caller choosing a cache mode.
 */
enum class OnlineCacheMode {
    /** Perform the request now and neither read nor write a cache entry. */
    NETWORK_ONLY,

    /** Reuse a matching verified entry when present; otherwise request and save it. */
    PREFER_CACHE,

    /** Request and save a fresh response; use a verified entry only after a network failure. */
    NETWORK_THEN_CACHE,

    /** Never contact the network; require a matching verified entry. */
    CACHE_ONLY;

    companion object {
        fun parse(value: String): OnlineCacheMode = entries.firstOrNull {
            it.name.equals(value.trim().replace('-', '_'), ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Unknown cache mode '$value' (use network-only, prefer-cache, network-then-cache, or cache-only)"
        )
    }
}

/** A caller requested an offline cache entry which is not available. */
class OnlineCacheMissException(message: String) : IOException(message)

/** A cache entry could not be trusted because its manifest or payload is invalid. */
class OnlineCacheCorruptionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A network-first request failed and no verified cached response was available as a fallback. */
class OnlineFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Where the response supplied to a workflow came from. */
enum class OnlineFetchOrigin { NETWORK, CACHE, CACHE_FALLBACK }

/**
 * Provenance for one explicit remote response.  [request] is intentionally
 * retained so exports and project bundles can show exactly what was retrieved;
 * callers should avoid putting secrets in a request string.
 */
data class OnlineFetchProvenance(
    val source: String,
    val request: String,
    val requestKey: String,
    val fetchedAt: Instant,
    val responseSha256: String,
    val origin: OnlineFetchOrigin,
    val fallbackReason: String? = null,
) {
    /** A metadata representation suitable for a [Seq] record or report. */
    fun metadata(prefix: String = "ONLINE"): Map<String, String> = buildMap {
        put("${prefix}_SOURCE", source)
        put("${prefix}_REQUEST", request)
        put("${prefix}_REQUEST_KEY", requestKey)
        put("${prefix}_FETCHED_AT", fetchedAt.toString())
        put("${prefix}_RESPONSE_SHA256", responseSha256)
        put("${prefix}_ORIGIN", origin.name.lowercase().replace('_', '-'))
        fallbackReason?.takeIf { it.isNotBlank() }?.let { put("${prefix}_FALLBACK_REASON", it) }
    }

    companion object {
        /** Constructs network provenance when no persisted cache was selected. */
        fun network(source: String, request: String, body: String, clock: Clock = Clock.systemUTC()): OnlineFetchProvenance =
            OnlineFetchProvenance(
                source = source,
                request = request,
                requestKey = OnlineCache.requestKey(source, request),
                fetchedAt = clock.instant(),
                responseSha256 = OnlineCache.responseSha256(body),
                origin = OnlineFetchOrigin.NETWORK,
            )
    }
}

/** The content and provenance returned by [OnlineCache.fetch]. */
data class OnlineFetch(val body: String, val provenance: OnlineFetchProvenance)

@Serializable
private data class OnlineCacheEntry(
    val schemaVersion: Int = SCHEMA_VERSION,
    val source: String,
    val request: String,
    val requestKey: String,
    val fetchedAtEpochMillis: Long,
    val responseSha256: String,
    val body: String,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * A small, file-backed cache for deliberately requested online results.
 *
 * Each response is stored as a versioned JSON manifest with a SHA-256 digest
 * of the payload.  Writes are atomic, and reads reject a damaged or mismatched
 * entry instead of silently using it.  The caller supplies the network action
 * so this class remains independent of any particular service or HTTP client.
 */
class OnlineCache(
    val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val lock = Any()

    /**
     * Returns a response under [mode].  [network] is invoked only when that
     * policy permits it; [OnlineCacheMode.CACHE_ONLY] is consequently safe for offline use.
     */
    fun fetch(
        source: String,
        request: String,
        mode: OnlineCacheMode,
        network: () -> String,
    ): OnlineFetch {
        require(source.isNotBlank()) { "Online cache source cannot be blank" }
        require(request.isNotBlank()) { "Online cache request cannot be blank" }
        val key = requestKey(source, request)
        return when (mode) {
            OnlineCacheMode.NETWORK_ONLY -> fromNetwork(source, request, key, persist = false, network)
            OnlineCacheMode.PREFER_CACHE -> read(source, request, key)
                ?: fromNetwork(source, request, key, persist = true, network)
            OnlineCacheMode.CACHE_ONLY -> read(source, request, key)
                ?: throw OnlineCacheMissException(
                    "No verified cached response for $source request $key. Choose a network cache mode to retrieve it."
                )
            OnlineCacheMode.NETWORK_THEN_CACHE -> try {
                fromNetwork(source, request, key, persist = true, network)
            } catch (error: Exception) {
                val cached = read(source, request, key)
                cached?.copy(
                    provenance = cached.provenance.copy(
                        origin = OnlineFetchOrigin.CACHE_FALLBACK,
                        fallbackReason = error.message ?: error::class.simpleName.orEmpty(),
                    )
                ) ?: throw OnlineFetchException(
                    "Online request to $source failed and no verified cached response is available: " +
                        (error.message ?: error::class.simpleName.orEmpty()),
                    error,
                )
            }
        }
    }

    /** The deterministic on-disk manifest path for one source/request pair. */
    fun entryFile(source: String, request: String): File = File(directory, "${requestKey(source, request)}.json")

    private fun fromNetwork(
        source: String,
        request: String,
        key: String,
        persist: Boolean,
        network: () -> String,
    ): OnlineFetch {
        val body = network()
        val fetchedAt = clock.instant()
        val hash = responseSha256(body)
        if (persist) {
            write(
                OnlineCacheEntry(
                    source = source,
                    request = request,
                    requestKey = key,
                    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
                    responseSha256 = hash,
                    body = body,
                )
            )
        }
        return OnlineFetch(
            body,
            OnlineFetchProvenance(
                source = source,
                request = request,
                requestKey = key,
                fetchedAt = fetchedAt,
                responseSha256 = hash,
                origin = OnlineFetchOrigin.NETWORK,
            )
        )
    }

    private fun read(source: String, request: String, key: String): OnlineFetch? = synchronized(lock) {
        val file = File(directory, "$key.json")
        if (!file.isFile) return@synchronized null
        val entry = try {
            json.decodeFromString<OnlineCacheEntry>(file.readText())
        } catch (error: Exception) {
            throw OnlineCacheCorruptionException("Cached response $key is not valid JSON", error)
        }
        if (entry.schemaVersion != OnlineCacheEntry.SCHEMA_VERSION) {
            throw OnlineCacheCorruptionException(
                "Cached response $key uses unsupported schema ${entry.schemaVersion}"
            )
        }
        if (entry.source != source || entry.request != request || entry.requestKey != key) {
            throw OnlineCacheCorruptionException("Cached response $key does not match its requested source")
        }
        if (responseSha256(entry.body) != entry.responseSha256) {
            throw OnlineCacheCorruptionException("Cached response $key failed its SHA-256 integrity check")
        }
        OnlineFetch(
            entry.body,
            OnlineFetchProvenance(
                source = entry.source,
                request = entry.request,
                requestKey = entry.requestKey,
                fetchedAt = Instant.ofEpochMilli(entry.fetchedAtEpochMillis),
                responseSha256 = entry.responseSha256,
                origin = OnlineFetchOrigin.CACHE,
            )
        )
    }

    private fun write(entry: OnlineCacheEntry) = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create online cache directory ${directory.path}")
        }
        val destination = File(directory, "${entry.requestKey}.json")
        val temporary = File(directory, ".${entry.requestKey}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json.encodeToString(entry))
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        /** Stable request key suitable for filenames and provenance references. */
        fun requestKey(source: String, request: String): String = digest("$source\n$request")

        /** Lowercase SHA-256 of an online response body. */
        fun responseSha256(body: String): String = digest(body)

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
