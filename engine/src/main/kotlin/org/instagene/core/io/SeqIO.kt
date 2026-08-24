package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.ChromatogramRecord
import org.instagene.core.ChromatogramReader
import org.instagene.core.ChromatogramTrace
import org.instagene.core.Feature
import org.instagene.core.MoleculeProperties
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strandedness
import org.instagene.core.Topology
import java.io.File
import java.io.IOException
import kotlin.math.abs

/** The sequence file formats InstaGene reads and writes. */
enum class SeqFormat(val displayName: String, val extensions: List<String>) {
    FASTA("FASTA", listOf("fa", "fasta", "fna", "fas", "seq", "txt")),
    GENBANK("GenBank", listOf("gb", "gbk", "genbank", "ape")),
    GFF3("GFF3", listOf("gff", "gff3")),
    EMBL("EMBL / ENA", listOf("embl", "ena")),
    SWISS_PROT("Swiss-Prot", listOf("swiss", "sprot", "dat")),
    ALIGNMENT_FASTA("FASTA alignment", listOf("afa", "msa")),
    ALIGNMENT_CLUSTAL("Clustal alignment", listOf("aln", "clustal")),
    ALIGNMENT_STOCKHOLM("Stockholm alignment", listOf("sto", "stockholm")),
    ALIGNMENT_PHYLIP("PHYLIP alignment", listOf("phy", "phylip", "ph")),
    ;

    val isAlignment: Boolean
        get() = this in setOf(ALIGNMENT_FASTA, ALIGNMENT_CLUSTAL, ALIGNMENT_STOCKHOLM, ALIGNMENT_PHYLIP)

    fun alignmentFormat(): AlignmentFormat = when (this) {
        ALIGNMENT_FASTA -> AlignmentFormat.FASTA
        ALIGNMENT_CLUSTAL -> AlignmentFormat.CLUSTAL
        ALIGNMENT_STOCKHOLM -> AlignmentFormat.STOCKHOLM
        ALIGNMENT_PHYLIP -> AlignmentFormat.PHYLIP
        else -> error("$displayName is not an alignment format")
    }
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
        if (
            seq.isCircular || seq.features.isNotEmpty() || seq.primers.isNotEmpty() || seq.provenance.isNotEmpty() ||
            seq.molecule != MoleculeProperties(
                strandedness = if (seq.kind == SeqKind.PROTEIN) Strandedness.SINGLE else Strandedness.DOUBLE,
            )
        ) SeqFormat.GENBANK else SeqFormat.FASTA

    /** Sniffs the format of [text] from its opening lines. */
    fun detectFormat(text: String): SeqFormat {
        when (AlignmentIO.detectFormat(text)) {
            AlignmentFormat.CLUSTAL -> return SeqFormat.ALIGNMENT_CLUSTAL
            AlignmentFormat.STOCKHOLM -> return SeqFormat.ALIGNMENT_STOCKHOLM
            AlignmentFormat.PHYLIP -> return SeqFormat.ALIGNMENT_PHYLIP
            else -> Unit
        }
        return when {
            GenBank.looksLikeGenBank(text) -> SeqFormat.GENBANK
            Gff3.looksLikeGff3(text) -> SeqFormat.GFF3
            Embl.looksLike(text) && Regex("\\bAA\\.", RegexOption.IGNORE_CASE).containsMatchIn(text.lineSequence().firstOrNull().orEmpty()) -> SeqFormat.SWISS_PROT
            Embl.looksLike(text) -> SeqFormat.EMBL
            else -> SeqFormat.FASTA
        }
    }

    /** Parses [text] in whichever format it appears to be; bare bases are accepted too. */
    fun parse(text: String, defaultName: String = "sequence"): Seq = when {
        AlignmentIO.detectFormat(text) in setOf(AlignmentFormat.CLUSTAL, AlignmentFormat.STOCKHOLM, AlignmentFormat.PHYLIP) ->
            AlignmentIO.parse(text, defaultName).first()
        GenBank.looksLikeGenBank(text) -> GenBank.parse(text, defaultName)
        Gff3.looksLikeGff3(text) -> Gff3.parse(text, defaultName)
        Embl.looksLike(text) -> Embl.parse(text, defaultName)
        text.contains('>') -> Fasta.parse(text, defaultName)
        else -> rawSequence(text, defaultName)
    }

    /** Parses every record in [text], in whichever format it appears to be. */
    fun parseAll(text: String, defaultName: String = "sequence"): List<Seq> = when {
        AlignmentIO.detectFormat(text) in setOf(AlignmentFormat.CLUSTAL, AlignmentFormat.STOCKHOLM, AlignmentFormat.PHYLIP) ->
            AlignmentIO.parse(text, defaultName)
        GenBank.looksLikeGenBank(text) -> splitGenBankRecords(text).map { GenBank.parse(it, defaultName) }
        Gff3.looksLikeGff3(text) -> listOf(Gff3.parse(text, defaultName))
        Embl.looksLike(text) -> splitFlatFileRecords(text).map { Embl.parse(it, defaultName) }
        text.contains('>') -> Fasta.parseAll(text, defaultName)
        else -> listOf(rawSequence(text, defaultName))
    }

    /**
     * Reads a file without ever buffering the whole thing in memory. The format
     * is found from the first non-blank line: GenBank streams through its text
     * parser, while FASTA reads the first record line by line and stops there,
     * so only one contig from a multi-contig genome is held in memory.
     */
    fun read(file: File): Seq {
        try {
            SequenceFormatCatalog.forFile(file)?.takeIf { it.support == FormatSupport.CONVERTER }?.let {
                return ExternalSequenceFormats.read(file, it)
            }
            val magic = file.inputStream().use { it.readNBytes(4) }
            if (ChromatogramReader.looksLikeAbi(magic)) return ChromatogramReader.readAbi(file).toSeq()
            if (ChromatogramReader.looksLikeScf(magic)) return ChromatogramReader.readScf(file).toSeq()
            if (formatOf(file).isAlignment) {
                return readAll(file).firstOrNull() ?: throw SeqIOException("No sequence found in ${file.name}")
            }
            val firstLine = firstNonBlankLine(file)
            return if (isGenBankFile(file) || firstLine.startsWith("LOCUS")) {
                file.bufferedReader().use { GenBank.parseFrom(it, file.nameWithoutExtension) }
            } else if (firstLine.startsWith("##gff-version") || firstLine.startsWith("ID   ")) {
                parse(file.readText(), file.nameWithoutExtension)
            } else {
                val hint = firstFastaRecordCapacityHint(file)
                file.bufferedReader().use {
                    Fasta.parseAllFrom(it, file.nameWithoutExtension, hint, stopAfterFirstRecord = true)
                }.firstOrNull() ?: throw SeqIOException("No sequence found in ${file.name}")
            }
        } catch (e: SeqIOException) {
            throw e
        } catch (e: IOException) {
            throw SeqIOException("Cannot read ${file.name}: ${e.message ?: "I/O error"}", cause = e)
        }
    }

    /**
     * Reads every record from [file], streaming FASTA records individually and
     * splitting GenBank records at their terminators.
     */
    fun readAll(file: File): List<Seq> {
        try {
            SequenceFormatCatalog.forFile(file)?.takeIf { it.support == FormatSupport.CONVERTER }?.let {
                return listOf(ExternalSequenceFormats.read(file, it))
            }
            val magic = file.inputStream().use { it.readNBytes(4) }
            if (ChromatogramReader.looksLikeAbi(magic)) return listOf(ChromatogramReader.readAbi(file).toSeq())
            if (ChromatogramReader.looksLikeScf(magic)) return listOf(ChromatogramReader.readScf(file).toSeq())
            if (formatOf(file).isAlignment) return AlignmentIO.parse(file.readText(), file.nameWithoutExtension)
            val firstLine = firstNonBlankLine(file)
            return if (isGenBankFile(file) || firstLine.startsWith("LOCUS")) {
                readGenBankRecords(file)
            } else if (firstLine.startsWith("##gff-version")) {
                listOf(parse(file.readText(), file.nameWithoutExtension))
            } else if (firstLine.startsWith("ID   ")) {
                splitFlatFileRecords(file.readText()).map { Embl.parse(it, file.nameWithoutExtension) }
            } else {
                file.bufferedReader().use { Fasta.parseAllFrom(it, file.nameWithoutExtension, capacityHintFor(file)) }
            }
        } catch (e: SeqIOException) {
            throw e
        } catch (e: IOException) {
            throw SeqIOException("Cannot read ${file.name}: ${e.message ?: "I/O error"}", cause = e)
        }
    }

    /**
     * Streams the FASTA once to count the bases in the first record, so the
     * second pass can preallocate the exact capacity. Peak memory stays near one
     * record, and a multi-GB, multi-contig file does not over-allocate for its first contig.
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
                if (line.isNotBlank()) return line.normalizedOpeningLine()
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
        SeqFormat.GFF3 -> Gff3.write(seq)
        SeqFormat.EMBL, SeqFormat.SWISS_PROT -> Embl.write(seq)
        SeqFormat.ALIGNMENT_FASTA, SeqFormat.ALIGNMENT_CLUSTAL, SeqFormat.ALIGNMENT_STOCKHOLM, SeqFormat.ALIGNMENT_PHYLIP ->
            AlignmentIO.write(listOf(seq), format.alignmentFormat())
    }

    /** Serializes every aligned row in the selected interchange format. */
    fun writeAll(sequences: List<Seq>, format: SeqFormat): String =
        if (format.isAlignment) AlignmentIO.write(sequences, format.alignmentFormat()) else Fasta.writeAll(sequences)

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

    /** Splits in-memory GenBank text for [parseAll]; file reads use [readGenBankRecords]. */
    private fun splitGenBankRecords(text: String): List<String> {
        val records = ArrayList<String>()
        val current = StringBuilder()
        for (line in text.lineSequence()) {
            current.append(line).append('\n')
            if (line.normalizedOpeningLine().startsWith("//")) {
                records += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) records += current.toString()
        return records
    }

    private fun splitFlatFileRecords(text: String): List<String> {
        val records = ArrayList<String>()
        val current = StringBuilder()
        for (line in text.lineSequence()) {
            current.append(line).append('\n')
            if (line.trimStart().startsWith("//")) {
                records += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) records += current.toString()
        return records
    }

    /** Buffers and parses one GenBank record at a time, never the whole file. */
    private fun readGenBankRecords(file: File): List<Seq> {
        val records = ArrayList<Seq>()
        val current = StringBuilder()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                current.append(line).append('\n')
                if (line.normalizedOpeningLine().startsWith("//")) {
                    records += GenBank.parse(current.toString(), file.nameWithoutExtension)
                    current.setLength(0)
                }
            }
        }
        if (current.isNotBlank()) records += GenBank.parse(current.toString(), file.nameWithoutExtension)
        return records
    }

    private fun isGenBankFile(file: File): Boolean = file.extension.lowercase() in SeqFormat.GENBANK.extensions

    private fun String.normalizedOpeningLine(): String =
        trimStart().removePrefix("\uFEFF").trimStart()

    /** Bundled example constructs, so the GUI and CLI have something to open immediately. */
    object Samples {
        const val SOURCE_METADATA_KEY = "IG_SAMPLE_SOURCE"
        const val LICENSE_METADATA_KEY = "IG_SAMPLE_LICENSE"
        private const val PBR322_RESOURCE = "/org/instagene/core/samples/pBR322_J01749.1.gb"

        private const val SAMPLE_LICENSE = "MIT; synthetic InstaGene example data"
        private const val PUC19_MCS_SOURCE =
            "Synthetic teaching fragment manually authored for InstaGene; represents the pUC19 multiple cloning site region only, not a downloaded full pUC19 record."
        private const val GFP_CDS_SOURCE =
            "Synthetic GFP-like teaching open reading frame authored for InstaGene examples; not copied from an external database record."
        private const val PLASMID_DEMO_SOURCE =
            "Synthetic circular construct authored for InstaGene tutorials from the bundled pUC19_MCS teaching fragment plus artificial filler and annotations."
        const val ALIGNMENT_DEMO_SOURCE =
            "Synthetic three-sequence alignment authored for InstaGene examples, including one gap and one substitution for viewer testing."
        const val CHROMATOGRAM_DEMO_SOURCE =
            "Generated synthetic chromatogram trace authored for InstaGene examples; contains no lab, patient, proprietary, or downloaded trace data."
        const val PBR322_NCBI_SOURCE =
            "Real plasmid example BLAST-verified against NCBI GenBank accession J01749.1 (Cloning vector pBR322, complete sequence); features are from the selected GenBank record; primary complete-sequence reference PubMed 383387."

        val PUC19_MCS: Seq = parse(
            """
            >pUC19_MCS_region polylinker of pUC19 (lacZalpha), linear
            GAATTCGAGCTCGGTACCCGGGGATCCTCTAGAGTCGACCTGCAGGCATGCAAGCTT
            """.trimIndent()
        ).copy(name = "pUC19_MCS", description = PUC19_MCS_SOURCE).withSampleSource(PUC19_MCS_SOURCE)

        val GFP_CDS: Seq = parse(
            """
            >GFP_CDS synthetic GFP-like open reading frame flanked by EcoRI and HindIII
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
        ).copy(name = "GFP_CDS", description = GFP_CDS_SOURCE).withSampleSource(GFP_CDS_SOURCE)

        val PBR322_NCBI: Seq = parsePbr322Resource().let { record ->
            record.copy(
                name = "pBR322_NCBI",
                metadata = record.metadata + mapOf(
                    SOURCE_METADATA_KEY to PBR322_NCBI_SOURCE,
                    "ONLINE_ACCESSION" to "J01749.1",
                    "ONLINE_SOURCE" to "NCBI GenBank",
                    "ONLINE_URL" to "https://www.ncbi.nlm.nih.gov/nuccore/J01749.1",
                    "BLAST_VERIFICATION" to "NCBI BLASTN selected accession J01749.1 as the matching pBR322 record",
                    "BLAST_SELECTED_ACCESSION" to "J01749.1",
                    "ANNOTATION_SOURCE" to "GenBank feature table from NCBI accession J01749.1",
                    "PUBMED" to "383387",
                ),
            )
        }

        /** A compact annotated circular construct suitable for map and cloning walkthroughs. */
        val PLASMID_DEMO: Seq = Seq(
            name = "pInstaGene_demo",
            bases = PUC19_MCS.bases + "ATGC".repeat(60),
            topology = Topology.CIRCULAR,
            description = PLASMID_DEMO_SOURCE,
            features = listOf(
                Feature("lac promoter", "promoter", 0, 20, color = "#1E88E5"),
                Feature("MCS", "misc_feature", 20, PUC19_MCS.length, color = "#F9A825"),
                Feature("demo insert", "CDS", PUC19_MCS.length, PUC19_MCS.length + 120, color = "#43A047"),
                Feature("origin", "rep_origin", PUC19_MCS.length + 120, PUC19_MCS.length + 200, color = "#8E24AA"),
            ),
        ).withSampleSource(PLASMID_DEMO_SOURCE)

        /** A ready-to-view aligned FASTA example, including a substitution and a gap. */
        val ALIGNMENT_DEMO: List<Seq> = listOf(
            Seq("alignment_reference", "ATGCGTACGTA-CGTTAGCA", description = ALIGNMENT_DEMO_SOURCE)
                .withSampleSource(ALIGNMENT_DEMO_SOURCE),
            Seq("alignment_read_1", "ATGCGTACGTAACGTTAGCA", description = ALIGNMENT_DEMO_SOURCE)
                .withSampleSource(ALIGNMENT_DEMO_SOURCE),
            Seq("alignment_read_2", "ATGCGTTCGTA-CGTTAGCA", description = ALIGNMENT_DEMO_SOURCE)
                .withSampleSource(ALIGNMENT_DEMO_SOURCE),
        )

        /** Synthetic trace data so the chroma/quality UI can be explored without a lab file. */
        val CHROMATOGRAM_DEMO: ChromatogramRecord = syntheticChromatogram()

        val ALL: List<Seq> = listOf(PUC19_MCS, GFP_CDS, PLASMID_DEMO, PBR322_NCBI)

        private fun syntheticChromatogram(): ChromatogramRecord {
            val bases = "ACGTACGTACGT"
            val peaks = bases.indices.map { 20 + it * 24 }
            val channels = "ACGT".associateWith { channel ->
                List(peaks.last() + 24) { sample ->
                    peaks.mapIndexed { index, peak ->
                        val distance = abs(sample - peak)
                        val height = if (bases[index] == channel) 850 else 110
                        (height - distance * 45).coerceAtLeast(0)
                    }.maxOrNull() ?: 0
                }
            }
            return ChromatogramRecord(
                name = "synthetic_chromatogram",
                bases = bases,
                qualities = listOf(42, 41, 39, 40, 38, 18, 35, 40, 42, 41, 39, 42),
                source = CHROMATOGRAM_DEMO_SOURCE,
                trace = ChromatogramTrace(peaks, channels),
            )
        }

        fun sourceFor(name: String): String? =
            (ALL + ALIGNMENT_DEMO).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.metadata
                ?.get(SOURCE_METADATA_KEY)
                ?: if (name.equals(CHROMATOGRAM_DEMO.name, ignoreCase = true)) CHROMATOGRAM_DEMO.source else null

        private fun Seq.withSampleSource(source: String): Seq = copy(
            metadata = metadata + mapOf(
                SOURCE_METADATA_KEY to source,
                LICENSE_METADATA_KEY to SAMPLE_LICENSE,
            )
        )

        private fun parsePbr322Resource(): Seq {
            val text = SeqIO::class.java.getResourceAsStream(PBR322_RESOURCE)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Missing bundled sample resource: $PBR322_RESOURCE")
            return parse(text, "pBR322_J01749.1.gb")
        }
    }
}

/** Convenience: `seq.toFasta()` reads better than `Fasta.write(seq)` at call sites. */
fun Seq.toFasta(): String = Fasta.write(this)

/** Convenience: `seq.toGenBank()` reads better than `GenBank.write(seq)` at call sites. */
fun Seq.toGenBank(): String = GenBank.write(this)

/** True when this sequence holds nucleotides rather than amino acids. */
fun Seq.isNucleotide(): Boolean = kind != SeqKind.PROTEIN
