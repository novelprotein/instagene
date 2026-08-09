package org.instagene.core.io

import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.Reader
import java.io.StringReader

/** FASTA reading and writing. */
object Fasta {

    const val LINE_WIDTH = 60

    /** Parses every record in [text]. Bare sequence text with no header is accepted. */
    fun parseAll(text: String, defaultName: String = "sequence"): List<Seq> =
        parseAllFrom(StringReader(text), defaultName)

    /**
     * Parses every record from [reader], line by line, so large files are never
     * buffered whole. Each line is cleaned and upper-cased in a single pass into
     * the shared builder, keeping peak memory near one copy of the sequence.
     *
     * When [stopAfterFirstRecord] is true the reader is left exactly after the
     * first record's last line, so a multi-contig genome file only ever buffers
     * one contig instead of every record in the file.
     */
    fun parseAllFrom(
        reader: Reader,
        defaultName: String = "sequence",
        capacityHint: Int = 1 shl 16,
        stopAfterFirstRecord: Boolean = false,
    ): List<Seq> {
        val out = ArrayList<Seq>()
        var name: String? = null
        var description = ""
        val bases = StringBuilder(capacityHint)
        var stoppedEarly = false

        fun flush() {
            if (name == null && bases.isEmpty()) return
            out += build(name ?: defaultName, description, bases.toString())
            bases.setLength(0)
            description = ""
        }

        reader.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
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
                    for (c in line) {
                        if (c.isWhitespace() || c.isDigit()) continue
                        bases.append(c.uppercaseChar())
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
            ?: throw IllegalArgumentException("No sequence found in FASTA input")

    private fun build(name: String, description: String, bases: String): Seq {
        val kind = detectKind(bases)
        // "circular" anywhere in the description is the de-facto convention for plasmid FASTA.
        val topology =
            if (description.contains("circular", ignoreCase = true)) Topology.CIRCULAR else Topology.LINEAR
        return Seq(name, bases, kind, topology, emptyList(), description)
    }

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

    fun writeAll(seqs: List<Seq>, lineWidth: Int = LINE_WIDTH): String =
        seqs.joinToString("") { write(it, lineWidth) }
}
