package org.instagene.core.prefs

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads and saves [UserPrefs] as JSON, writing atomically (temp file + move) so
 * a crash mid-save never leaves a corrupt prefs file behind.
 *
 * The file is only created when [save] is called; [load] of a missing or
 * unreadable file returns the defaults. The path is overridable for tests.
 */
class PrefsStore(
    val file: File = File(AppDirs.configDir(), "prefs.json"),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {

    private var cached: UserPrefs? = null

    /** The persisted prefs, lazily loaded once; corrupt files fall back to defaults. */
    fun load(): UserPrefs {
        cached?.let { return it }
        val loaded = if (file.isFile) {
            runCatching { json.decodeFromString<UserPrefs>(file.readText()) }.getOrElse { UserPrefs() }
        } else {
            UserPrefs()
        }
        cached = loaded
        return loaded
    }

    /** Persists [prefs] (and caches it), creating the config directory if needed. */
    fun save(prefs: UserPrefs) {
        cached = prefs
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(json.encodeToString(prefs))
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
