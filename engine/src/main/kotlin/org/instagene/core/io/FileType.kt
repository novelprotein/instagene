package org.instagene.core.io

import org.instagene.core.ChromatogramReader
import java.io.File

/**
 * The broad kind of a file, determined before parsing so images, PDFs, and
 * database dumps are not misidentified as sequences.
 */
enum class FileType(val displayName: String) {
    /** A FASTA, GenBank or bare-bases file that the parsers understand. */
    SEQUENCE("sequence file"),

    /** Printable, human-readable text that is not a sequence. */
    TEXT("text file"),

    /** A raster image (PNG, JPEG, GIF, TIFF, BMP, ...). */
    IMAGE("image"),

    /** A PDF document. */
    PDF("PDF"),

    /** A Sanger sequencing chromatogram (ABI/AB1 or SCF). */
    CHROMATOGRAM("chromatogram"),

    /** Anything else, in practice binary data or an unknown format. */
    OTHER("file"),
}

/** Sniffs the broad [FileType] of a file or pasted bytes without parsing it. */
object FileSniffer {

    private const val BOM = '\uFEFF'

    /** First bytes examined; enough for every magic number we recognise. */
    private const val PEEK_BYTES = 64

    private const val PEEK_CHARS = 4096

    private fun isPng(b: ByteArray): Boolean = b.size >= 8 &&
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun isJpeg(b: ByteArray): Boolean = b.size >= 3 &&
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isGif(b: ByteArray): Boolean = b.size >= 6 &&
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    private fun isTiff(b: ByteArray): Boolean = b.size >= 4 &&
        (b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 0x2A.toByte() && b[3] == 0x00.toByte() ||
            b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() && b[2] == 0x00.toByte() && b[3] == 0x2A.toByte())

    private fun isBmp(b: ByteArray): Boolean = b.size >= 2 &&
        b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()

    private fun isPdf(b: ByteArray): Boolean = b.size >= 5 &&
        b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[4] == '-'.code.toByte()

    private fun isImageMagic(b: ByteArray): Boolean =
        isPng(b) || isJpeg(b) || isGif(b) || isTiff(b) || isBmp(b)

    /** The broad type of [file], from its content (and name) rather than its parser. */
    fun typeOf(file: File): FileType {
        if (!file.isFile || file.length() == 0L) return FileType.TEXT
        return file.inputStream().use { input ->
            val bytes = input.readNBytes(PEEK_BYTES)
            typeOf(bytes, file.name)
        }
    }

    /** The broad type of the first [bytes] of a file named [fileName] (may be null). */
    fun typeOf(bytes: ByteArray, fileName: String? = null): FileType = when {
        ChromatogramReader.looksLikeAbi(bytes) || ChromatogramReader.looksLikeScf(bytes) -> FileType.CHROMATOGRAM
        isImageMagic(bytes) -> FileType.IMAGE
        isPdf(bytes) -> FileType.PDF
        isBinary(bytes) -> FileType.OTHER
        else -> typeOf(bytes.toString(Charsets.UTF_8))
    }

    /** The broad type of pasted or file text. */
    fun typeOf(text: String): FileType {
        val head = text.removePrefix(BOM.toString()).take(PEEK_CHARS)
        if (GenBank.looksLikeGenBank(head)) return FileType.SEQUENCE
        if (Gff3.looksLikeGff3(head)) return FileType.SEQUENCE
        if (head.contains('>')) return FileType.SEQUENCE
        val cleaned = head.filterNot { it.isWhitespace() || it.isDigit() }
        if (cleaned.isNotEmpty() && cleaned.none { !it.isLetter() }) return FileType.SEQUENCE
        return if (head.none { it.isISOControl() && it != '\n' && it != '\t' }) FileType.TEXT else FileType.OTHER
    }

    /** True when the bytes look like a sequence the parsers can read. */
    fun isSequence(bytes: ByteArray, fileName: String? = null): Boolean =
        typeOf(bytes, fileName) == FileType.SEQUENCE

    /** True when the file looks like a sequence the parsers can read. */
    fun isSequence(file: File): Boolean = typeOf(file) == FileType.SEQUENCE

    /** NUL bytes or heavy control characters mark a file as binary rather than text. */
    private fun isBinary(bytes: ByteArray): Boolean {
        var control = 0
        for (i in bytes.indices) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) return true
            if (c < 0x09 || c in 0x0E..0x1F || c == 0x7F) control++
        }
        return bytes.isNotEmpty() && control * 2 >= bytes.size
    }
}
