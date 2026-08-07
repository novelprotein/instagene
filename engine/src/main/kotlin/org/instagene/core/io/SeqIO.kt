package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.File

enum class SeqFormat(val displayName: String, val extensions: List<String>) {
    FASTA("FASTA", listOf("fa", "fasta", "fna", "fas", "seq", "txt")),
    GENBANK("GenBank", listOf("gb", "gbk", "genbank", "ape")),
}

/** Format-sniffing front door for reading and writing sequence files. */
object SeqIO {

    fun formatOf(file: File): SeqFormat {
        val ext = file.extension.lowercase()
        return SeqFormat.entries.firstOrNull { ext in it.extensions } ?: SeqFormat.FASTA
    }

    fun detectFormat(text: String): SeqFormat =
        if (GenBank.looksLikeGenBank(text)) SeqFormat.GENBANK else SeqFormat.FASTA

    /** Parses [text] in whichever format it appears to be; bare bases are accepted too. */
    fun parse(text: String, defaultName: String = "sequence"): Seq = when {
        GenBank.looksLikeGenBank(text) -> GenBank.parse(text, defaultName)
        text.contains('>') -> Fasta.parse(text, defaultName)
        else -> rawSequence(text, defaultName)
    }

    fun parseAll(text: String, defaultName: String = "sequence"): List<Seq> = when {
        GenBank.looksLikeGenBank(text) -> splitGenBankRecords(text).map { GenBank.parse(it, defaultName) }
        text.contains('>') -> Fasta.parseAll(text, defaultName)
        else -> listOf(rawSequence(text, defaultName))
    }

    /**
     * Reads a file without buffering the whole thing in memory. The format is
     * found from the first non-blank line: GenBank stays on its text parser,
     * while FASTA loops bare sequence files line by line via stream.
     */
    fun read(file: File): Seq {
        val firstLine = firstNonBlankLine(file)
        return if (firstLine.startsWith("LOCUS")) {
            GenBank.parse(file.readText(), file.nameWithoutExtension)
        } else {
            file.bufferedReader().use { Fasta.parseAllFrom(it, file.nameWithoutExtension, capacityHintFor(file)) }
                .firstOrNull() ?: throw IllegalArgumentException("No sequence found in ${file.name}")
        }
    }

    fun readAll(file: File): List<Seq> {
        val firstLine = firstNonBlankLine(file)
        return if (firstLine.startsWith("LOCUS")) {
            splitGenBankRecords(file.readText()).map { GenBank.parse(it, file.nameWithoutExtension) }
        } else {
            file.bufferedReader().use { Fasta.parseAllFrom(it, file.nameWithoutExtension, capacityHintFor(file)) }
        }
    }

    /** The first line of [file], or an empty string when the file is blank. */
    private fun firstNonBlankLine(file: File): String {
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) return line
            }
        }
        return ""
    }

    private fun capacityHintFor(file: File): Int =
        file.length().toInt().coerceIn(64, Int.MAX_VALUE)

    fun write(seq: Seq, format: SeqFormat): String = when (format) {
        SeqFormat.FASTA -> Fasta.write(seq)
        SeqFormat.GENBANK -> GenBank.write(seq)
    }

    fun write(file: File, seq: Seq, format: SeqFormat = formatOf(file)) {
        file.writeText(write(seq, format))
    }

    /** Bare pasted bases, the most common thing to arrive on the clipboard. */
    fun rawSequence(text: String, name: String = "sequence"): Seq {
        val bases = Alphabet.clean(text).uppercase()
        val kind = Fasta.detectKind(bases)
        return Seq(name, bases, kind, Topology.LINEAR)
    }

    private fun splitGenBankRecords(text: String): List<String> {
        val records = ArrayList<String>()
        val current = StringBuilder()
        for (line in text.lineSequence()) {
            current.append(line).append('\n')
            if (line.startsWith("//")) {
                records += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) records += current.toString()
        return records
    }

    /** Bundled example constructs, so the GUI and CLI have something to open immediately. */
    object Samples {
        val PUC19_MCS: Seq = parse(
            """
            >pUC19_MCS_region polylinker of pUC19 (lacZalpha), linear
            GAATTCGAGCTCGGTACCCGGGGATCCTCTAGAGTCGACCTGCAGGCATGCAAGCTT
            """.trimIndent()
        ).copy(name = "pUC19_MCS")

        val GFP_CDS: Seq = parse(
            """
            >GFP_CDS synthetic GFP-like open reading frame flanked by EcoRI and HinDIII
            GAATTCATGAGTAAAGGAGAAGAACTTTTCACTGGAGTTGTCCCAATTCTTGTTGAATTAGATGGTGATG
            TTAATGGGCACAAATTTTCTGTCAGTGGAGAGGGTGAAGGTGATGCAACATACGGAAAACTTACCCTTAA
            ATTTATTTGCACTACTGGAAAACTACCTGTTCCATGGCCAACACTTGTCACTACTTTCTCTTATGGTGTT
            CAATGCTTTTCAAGATACCCAGATCATATGAAACAGCATGACTTTTTCAAGAGTGCCATGCCCGAAGGTT
            ATGTACAGGAAAGAACTATATTTTTCAAAGATGACGGGAACTACAAGACACGTGCTGAAGTCAAGTTTGA
            AGGTGATACCCTTGTTAATAGAATCGAGTTAAAAGGTATTGATTTTAAAGAAGATGGAAACATTCTTGGA
            CACAAATTGGAATACAACTATAACTCACACAATGTATACATCATGGCAGACAAACAAAAGAATGGAATCA
            AAGTTAACTTCAAAATTAGACACAACATTGAAGATGGAAGCGTTCAACTAGCAGACCATTATCAACAAAA
            TACTCCAATTGGCGATGGCCCTGTCCTTTTACCAGACAACCATTACCTGTCCACACAATCTGCCCTTTCG
            AAAGATCCCAACGAAAAGAGAGACCACATGGTCCTTCTTGAGTTTGTAACAGCTGCTGGGATTACACATG
            GCATGGATGAACTATACAAATAAAAGCTT
            """.trimIndent()
        ).copy(name = "GFP_CDS")

        val ALL: List<Seq> = listOf(PUC19_MCS, GFP_CDS)
    }
}

/** Convenience: `seq.toFasta()` reads better than `Fasta.write(seq)` at call sites. */
fun Seq.toFasta(): String = Fasta.write(this)

fun Seq.toGenBank(): String = GenBank.write(this)

/** True when this sequence holds nucleotides rather than amino acids. */
fun Seq.isNucleotide(): Boolean = kind != SeqKind.PROTEIN
