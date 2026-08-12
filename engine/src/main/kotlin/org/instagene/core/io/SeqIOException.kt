package org.instagene.core.io

import java.io.IOException

/**
 * Thrown when a sequence file or pasted sequence cannot be read or parsed.
 *
 * Parsing and I/O code uses this type for malformed records, alphabet violations,
 * empty input, truncated GenBank records, and unreadable files. Front ends can catch
 * one exception type and display its message, [line], and [cause].
 */
class SeqIOException(
    message: String,
    /** The 1-based line of the input the error refers to, when it is known. */
    val line: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
