package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology

/** FASTA reading and writing. */
object Fasta {

    const val LINE_WIDTH = 60

    /** Parses every record in [text]. Bare sequence text with no header is accepted. */
    fun parseAll(text: String, defaultName: String = "sequence"): List<Seq> {
        val out = ArrayList<Seq>()
        var name: String? = null
        var description = ""
        val bases = StringBuilder()

        fun flush() {
            if (name == null && bases.isEmpty()) return
            out += build(name ?: defaultName, description, bases.toString())
            bases.setLength(0)
            description = ""
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(">") || line.startsWith(";")) {
                flush()
                val header = line.substring(1).trim()
                name = header.substringBefore(' ').ifEmpty { defaultName }
                description = header.substringAfter(' ', "").trim()
            } else {
                bases.append(Alphabet.clean(line))
            }
        }
        flush()
        return out
    }

    /** Parses the first record, which is what the single-sequence views need. */
    fun parse(text: String, defaultName: String = "sequence"): Seq =
        parseAll(text, defaultName).firstOrNull()
            ?: throw IllegalArgumentException("No sequence found in FASTA input")

    private fun build(name: String, description: String, bases: String): Seq {
        val upper = bases.uppercase()
        val kind = detectKind(upper)
        // "circular" anywhere in the description is the de-facto convention for plasmid FASTA.
        val topology =
            if (description.contains("circular", ignoreCase = true)) Topology.CIRCULAR else Topology.LINEAR
        return Seq(name, upper, kind, topology, emptyList(), description)
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
            "${seq.length} bp",
            seq.topology.name.lowercase(),
        )
        append(' ').append(extras.joinToString(" | "))
        append('\n')
        seq.bases.chunked(lineWidth).forEach { append(it).append('\n') }
    }

    fun writeAll(seqs: List<Seq>, lineWidth: Int = LINE_WIDTH): String =
        seqs.joinToString("") { write(it, lineWidth) }
}
