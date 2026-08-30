package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.Reader
import java.io.StringReader

/** FASTA reading and writing. */
object Fasta {

    /** Default line width for wrapped FASTA output. */
    const val LINE_WIDTH = 60

    /** Every character accepted by either alphabet, used to locate errors within a line. */
    private val ANY_ALPHABET = (Alphabet.NUCLEOTIDES + Alphabet.AMINO_ACIDS).toSet()

    /** Parses every record in [text]. Bare sequence text with no header is accepted. */
    fun parseAll(text: String, defaultName: String = "sequence"): List<Seq> =
        parseAllFrom(StringReader(text), defaultName)

    /** Delivers FASTA records as they are completed, without retaining prior records. */
    fun forEachRecord(
        reader: Reader,
        defaultName: String = "sequence",
        capacityHint: Int = 1 shl 16,
        validateAlphabet: Boolean = true,
        consumer: (Seq) -> Unit,
    ): Int {
        var name: String? = null
        var description = ""
        val bases = StringBuilder(capacityHint)
        var firstBasesLine = 0
        val invalidLines = HashMap<Char, Int>()
        var count = 0

        fun flush() {
            if (name == null && bases.isEmpty()) return
            consumer(build(name ?: defaultName, description, bases.toString(), validateAlphabet, firstBasesLine, invalidLines))
            count++
            name = null
            description = ""
            bases.setLength(0)
            firstBasesLine = 0
            invalidLines.clear()
        }

        reader.buffered().use { lines ->
            var lineNumber = 0
            while (true) {
                val raw = lines.readLine() ?: break
                lineNumber++
                var line = raw.trim()
                if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.removePrefix("\uFEFF").trim()
                if (line.isEmpty()) continue
                if (line.startsWith(">") || line.startsWith(";")) {
                    flush()
                    val header = line.substring(1).trim()
                    name = header.substringBefore(' ').ifEmpty { defaultName }
                    description = header.substringAfter(' ', "").trim()
                } else {
                    if (firstBasesLine == 0) firstBasesLine = lineNumber
                    for (c in line) {
                        if (c.isWhitespace() || c.isDigit()) continue
                        val upper = c.uppercaseChar()
                        if (validateAlphabet && upper !in ANY_ALPHABET) invalidLines.putIfAbsent(upper, lineNumber)
                        bases.append(upper)
                    }
                }
            }
        }
        flush()
        return count
    }

    /**
     * Parses every record from [reader], line by line, so large files are never
     * buffered in its entirety. Each line is cleaned, converted to uppercase, and validated in a single
     * pass into the shared builder, keeping peak memory near one copy of the
     * sequence. Lines are counted 1-based so parse failures point at the line.
     *
     * When [stopAfterFirstRecord] is true the reader is left exactly after the
     * first record's last line, so a multi-contig genome file only ever buffers
     * one contig instead of every record in the file.
     *
     * When [validateAlphabet] is true, a record containing a character rejected by
     * the detected alphabet fails with a [SeqIOException] that names the
     * offending characters and the line where they appeared.
     */
    fun parseAllFrom(
        reader: Reader,
        defaultName: String = "sequence",
        capacityHint: Int = 1 shl 16,
        stopAfterFirstRecord: Boolean = false,
        validateAlphabet: Boolean = true,
    ): List<Seq> {
        val out = ArrayList<Seq>()
        var name: String? = null
        var description = ""
        val bases = StringBuilder(capacityHint)
        var stoppedEarly = false

        // Bookkeeping for error messages: the first line that carried bases and
        // the earliest line each out-of-alphabet character was seen on.
        var firstBasesLine = 0
        val invalidLines = HashMap<Char, Int>()

        fun flush() {
            if (name == null && bases.isEmpty()) return
            out += build(
                name ?: defaultName, description, bases.toString(), validateAlphabet,
                firstBasesLine, invalidLines,
            )
            bases.setLength(0)
            description = ""
            firstBasesLine = 0
            invalidLines.clear()
        }

        reader.useLines { lines ->
            var lineNumber = 0
            for (rawLine in lines) {
                lineNumber++
                var line = rawLine.trim()
                if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.removePrefix("\uFEFF").trim()
                if (line.isEmpty()) continue
                if (line.startsWith(">") || line.startsWith(";")) {
                    if (stopAfterFirstRecord && (name != null || bases.isNotEmpty())) {
                        flush()
                        stoppedEarly = true
                        return@useLines
                    }
                    flush()
                    val header = line.substring(1).trim()
                    name = header.substringBefore(' ').ifEmpty { defaultName }
                    description = header.substringAfter(' ', "").trim()
                } else {
                    if (firstBasesLine == 0) firstBasesLine = lineNumber
                    for (c in line) {
                        if (c.isWhitespace() || c.isDigit()) continue
                        val upper = c.uppercaseChar()
                        if (validateAlphabet && upper !in ANY_ALPHABET) {
                            invalidLines.putIfAbsent(upper, lineNumber)
                        }
                        bases.append(upper)
                    }
                }
            }
        }
        if (!stoppedEarly) flush()
        return out
    }

    /** Parses the first record, which is what the single-sequence views need. */
    fun parse(text: String, defaultName: String = "sequence"): Seq =
        parseAll(text, defaultName).firstOrNull()
            ?: throw SeqIOException("No sequence found in FASTA input")

    private fun build(
        name: String,
        description: String,
        bases: String,
        validateAlphabet: Boolean,
        firstBasesLine: Int,
        invalidLines: Map<Char, Int>,
    ): Seq {
        val kind = detectKind(bases)
        if (validateAlphabet) {
            val invalid = Alphabet.invalidCharacters(bases, kind)
            if (invalid.isNotEmpty()) {
                val line = invalid.firstNotNullOfOrNull { invalidLines[it] } ?: firstBasesLine
                throw SeqIOException(
                    "Invalid ${kind.name.lowercase()} character(s) " +
                        "${invalid.sorted().joinToString { "'$it'" }} in FASTA input" +
                        if (line > 0) " on line $line" else "",
                    line = line.takeIf { it > 0 },
                )
            }
        }
        // "circular" anywhere in the description is the de facto convention for plasmid FASTA.
        val topology =
            if (description.contains("circular", ignoreCase = true)) Topology.CIRCULAR else Topology.LINEAR
        return Seq(name, bases, kind, topology, emptyList(), description)
    }

    /** Best-effort kind guess for [bases]: protein when mostly non-nucleotide, RNA when uracil-like, DNA otherwise. */
    fun detectKind(bases: String): SeqKind {
        if (bases.isEmpty()) return SeqKind.DNA
        val nucleotideLike = bases.count { it.uppercaseChar() in "ACGTUN-" }
        if (nucleotideLike < bases.length * 0.9) return SeqKind.PROTEIN
        return if (bases.any { it.uppercaseChar() == 'U' } && bases.none { it.uppercaseChar() == 'T' }) {
            SeqKind.RNA
        } else {
            SeqKind.DNA
        }
    }

    /** Serializes [seq] as FASTA: wrapped at [lineWidth], with length and topology appended to the header. */
    fun write(seq: Seq, lineWidth: Int = LINE_WIDTH): String = buildString {
        append('>').append(seq.name)
        val extras = listOfNotNull(
            seq.description.takeIf { it.isNotBlank() },
            "${seq.length} ${if (seq.kind == SeqKind.PROTEIN) "aa" else "bp"}",
            seq.topology.name.lowercase(),
        )
        if (extras.isNotEmpty()) append(' ').append(extras.joinToString(" | "))
        append('\n')
        seq.bases.chunked(lineWidth).forEach { append(it).append('\n') }
    }

    /** Serializes [seqs] into one multi-record FASTA. */
    fun writeAll(seqs: List<Seq>, lineWidth: Int = LINE_WIDTH): String =
        seqs.joinToString("") { write(it, lineWidth) }
}
