package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.File

/** The sequence file formats InstaGene reads and writes. */
enum class SeqFormat(val displayName: String, val extensions: List<String>) {
    FASTA("FASTA", listOf("fa", "fasta", "fna", "fas", "seq", "txt")),
    GENBANK("GenBank", listOf("gb", "gbk", "genbank", "ape")),
}

/** Format-sniffing front door for reading and writing sequence files. */
object SeqIO {

    /** The format for [file], from its extension; unknown extensions fall back to FASTA. */
    fun formatOf(file: File): SeqFormat {
        val ext = file.extension.lowercase()
        return SeqFormat.entries.firstOrNull { ext in it.extensions } ?: SeqFormat.FASTA
    }

    /**
     * The format that stores [seq] without losing anything: GenBank when there
     * are features or a circular topology (a plasmid map), FASTA otherwise.
     */
    fun preferredSaveFormat(seq: Seq): SeqFormat =
        if (seq.isCircular || seq.features.isNotEmpty()) SeqFormat.GENBANK else SeqFormat.FASTA

    /** Sniffs the format of [text] from its opening lines. */
    fun detectFormat(text: String): SeqFormat =
        if (GenBank.looksLikeGenBank(text)) SeqFormat.GENBANK else SeqFormat.FASTA

    /** Parses [text] in whichever format it appears to be; bare bases are accepted too. */
    fun parse(text: String, defaultName: String = "sequence"): Seq = when {
        GenBank.looksLikeGenBank(text) -> GenBank.parse(text, defaultName)
        text.contains('>') -> Fasta.parse(text, defaultName)
        else -> rawSequence(text, defaultName)
    }

    /** Parses every record in [text], in whichever format it appears to be. */
    fun parseAll(text: String, defaultName: String = "sequence"): List<Seq> = when {
        GenBank.looksLikeGenBank(text) -> splitGenBankRecords(text).map { GenBank.parse(it, defaultName) }
        text.contains('>') -> Fasta.parseAll(text, defaultName)
        else -> listOf(rawSequence(text, defaultName))
    }

    /**
     * Reads a file without ever buffering the whole thing in memory. The format
     * is found from the first non-blank line: GenBank streams through its text
     * parser, while FASTA reads the first record line by line and stops there,
     * so a multi-contig genome file never materialises more than one contig.
     */
    fun read(file: File): Seq {
        val firstLine = firstNonBlankLine(file)
        return if (firstLine.startsWith("LOCUS")) {
            file.bufferedReader().use { GenBank.parseFrom(it, file.nameWithoutExtension) }
        } else {
            val hint = firstFastaRecordCapacityHint(file)
            file.bufferedReader().use {
                Fasta.parseAllFrom(it, file.nameWithoutExtension, hint, stopAfterFirstRecord = true)
            }.firstOrNull() ?: throw IllegalArgumentException("No sequence found in ${file.name}")
        }
    }

    /** Reads every record from [file]: FASTA records stream one at a time, GenBank records split at record terminators. */
    fun readAll(file: File): List<Seq> {
        val firstLine = firstNonBlankLine(file)
        return if (firstLine.startsWith("LOCUS")) {
            splitGenBankRecords(file.readText()).map { GenBank.parse(it, file.nameWithoutExtension) }
        } else {
            file.bufferedReader().use { Fasta.parseAllFrom(it, file.nameWithoutExtension, capacityHintFor(file)) }
        }
    }

    /**
     * Streams the FASTA once to count the bases in the first record, so the
     * second pass can preallocate exactly: peak memory stays at one record and
     * a multi-GB multi-contig file never over-allocates for contig one.
     */
    private fun firstFastaRecordCapacityHint(file: File): Int {
        var count = 0L
        var started = false
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith(">") || trimmed.startsWith(";")) {
                    if (started) break
                    started = true
                    continue
                }
                started = true
                for (c in trimmed) {
                    if (c.isWhitespace() || c.isDigit()) continue
                    count++
                }
            }
        }
        return count.coerceIn(64, Int.MAX_VALUE.toLong()).toInt()
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
        file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceIn(64, Int.MAX_VALUE)

    /** Serializes [seq] in [format]. */
    fun write(seq: Seq, format: SeqFormat): String = when (format) {
        SeqFormat.FASTA -> Fasta.write(seq)
        SeqFormat.GENBANK -> GenBank.write(seq)
    }

    /** Writes [seq] to [file] in [format], defaulting to the format its extension names. */
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

/** Convenience: `seq.toGenBank()` reads better than `GenBank.write(seq)` at call sites. */
fun Seq.toGenBank(): String = GenBank.write(this)

/** True when this sequence holds nucleotides rather than amino acids. */
fun Seq.isNucleotide(): Boolean = kind != SeqKind.PROTEIN
