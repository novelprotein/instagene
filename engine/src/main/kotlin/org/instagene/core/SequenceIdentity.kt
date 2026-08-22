package org.instagene.core

import java.security.MessageDigest
import java.io.File

/** Stable, content-addressed identifiers compatible with the sequence identity workflow in ApE. */
object SequenceIdentity {
    /** Returns a lowercase SHA-256 digest of the biological sequence and alphabet. */
    fun sha256(seq: Seq): String = digest("${seq.kind.name}\n${seq.bases.uppercase()}")

    /** Returns the compact cdseguid-like identifier used in exported records. */
    fun cdseguid(seq: Seq): String = "cdseguid-" + sha256(seq).take(20)

    fun verify(seq: Seq): Boolean = seq.uniqueIdentifier?.let { it == cdseguid(seq) } ?: false

    /** SHA-256 of the source bytes, used to make imported records traceable. */
    fun sourceSha256(file: File): String = digest(file.readBytes())

    /** Adds source-file provenance without changing the biological sequence. */
    fun withSourceFile(seq: Seq, file: File): Seq = seq.copy(
        metadata = seq.metadata + mapOf(
            "SOURCE_FILE" to file.absoluteFile.normalize().path,
            "SOURCE_SHA256" to sourceSha256(file),
        ),
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
