package org.instagene.app.gui

import org.instagene.core.Alphabet
import java.io.File
import javax.swing.filechooser.FileNameExtensionFilter

/** The kind of file a project node can be, decided by [FileTypes.classify]. */
enum class FileType {
    /** A FASTA/GenBank/bare-bases molecule, opened as a sequence tab. */
    SEQUENCE,

    /** A plain-text / notes file, opened in the in-app text editor. */
    TEXT,

    /** A raster/vector image, opened with the system app. */
    IMAGE,

    /** A PDF, opened with the system app. */
    PDF,

    /** Binary or unrecognized content, opened with the system app as a last resort. */
    UNKNOWN,
}

/**
 * Classifies a file by extension first, then by content so that `.txt` files
 * holding bare DNA still open as sequences while prose opens as text.
 */
object FileTypes {

    /** Extensions that make a file eligible for the open-file dialog. */
    val sequenceExtensions = setOf("fasta", "fa", "fna", "fas", "gb", "gbk", "genbank", "gp", "ape", "seq")
    private val textExtensions = setOf("md", "markdown", "notes", "log")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "svg", "bmp", "webp", "tif", "tiff")
    private val pdfExtensions = setOf("pdf")

    /** The file filter for the open-file dialog: only sequence files are shown. */
    fun sequenceFileFilter(): FileNameExtensionFilter =
        FileNameExtensionFilter("Sequence files", *sequenceExtensions.toTypedArray())

    /** The dominant file-type of [file]. Only reads enough of the file to sniff it. */
    fun classify(file: File): FileType {
        val ext = file.extension.lowercase()
        when {
            ext in sequenceExtensions -> return FileType.SEQUENCE
            ext in textExtensions -> return FileType.TEXT
            ext in imageExtensions -> return FileType.IMAGE
            ext in pdfExtensions -> return FileType.PDF
        }
        // `.txt` (and anything else unknown) is decided by content: DNA stays a
        // sequence, prose and notes become text, binary goes to the system app.
        return classifyContent(peek(file))
    }

    /** Classifies an already-read text sample; used by the tests and by the tree panel. */
    fun classifyContent(sample: String): FileType {
        val text = sample.takeIf { !it.contains('\u0000') } ?: return FileType.UNKNOWN
        val trimmed = text.trimStart()
        val looksFasta = trimmed.startsWith(">")
        val looksGenbank = trimmed.startsWith("LOCUS")
        if (looksFasta || looksGenbank) return FileType.SEQUENCE
        val letters = trimmed.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { Alphabet.isNucleotide(it) } && letters.length >= 40) {
            return FileType.SEQUENCE
        }
        return if (trimmed.isNotEmpty()) FileType.TEXT else FileType.UNKNOWN
    }

    /** The first 8 KiB of [file], or an empty string when it cannot be read. */
    private fun peek(file: File): String =
        runCatching { file.inputStream().use { it.readNBytes(8 * 1024).decodeToString() } }.getOrDefault("")
}
