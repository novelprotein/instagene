package org.instagene.core.io

import java.io.IOException

/**
 * Thrown when a sequence file or pasted sequence cannot be read or parsed.
 *
 * Unlike the raw [IllegalArgumentException]/[NumberFormatException]/[OutOfMemoryError]
 * family, this is the single, predictable exception the IO layer raises: malformed
 * records, alphabet violations, empty or binary files, truncated GenBank records
 * and unreadable targets all surface as [SeqIOException], so front-ends can catch
 * one type and render the message (with [line] and [cause] when known).
 */
class SeqIOException(
    message: String,
    /** The 1-based line of the input the error refers to, when it is known. */
    val line: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
