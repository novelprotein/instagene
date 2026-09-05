package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.ChromatogramReader
import org.instagene.core.ExampleMetadataInference
import org.instagene.core.MoleculeProperties
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strandedness
import org.instagene.core.Topology
import java.io.File
import java.io.IOException

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
            ) || seq.recordMetadata != org.instagene.core.SequenceRecordMetadata() ||
            seq.metadata.keys.any { it.startsWith("IG_") || it in setOf("ACCESSION", "SOURCE", "ORGANISM", "COMMENT", "REFERENCE", "DBLINK") }
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
     * Delivers records one at a time. Flat-file records are delimited before
     * parsing, so callers can process large multi-record files without a list
     * of sequences accumulating in memory.
     */
    fun forEachRecord(file: File, consumer: (Seq) -> Unit): Int {
        try {
            val firstLine = firstNonBlankLine(file)
            return when {
                isGenBankFile(file) || firstLine.startsWith("LOCUS") ->
                    file.bufferedReader().use { GenBank.forEachRecord(it, file.nameWithoutExtension, consumer) }
                firstLine.startsWith("ID   ") -> streamDelimitedRecords(file, consumer) { Embl.parse(it, file.nameWithoutExtension) }
                firstLine.startsWith("##gff-version") -> {
                    consumer(parse(file.readText(), file.nameWithoutExtension))
                    1
                }
                else -> {
                    file.bufferedReader().use {
                        Fasta.forEachRecord(it, file.nameWithoutExtension, capacityHintFor(file), consumer = consumer)
                    }
                }
            }
        } catch (e: SeqIOException) {
            throw e
        } catch (e: IOException) {
            throw SeqIOException("Cannot read ${file.name}: ${e.message ?: "I/O error"}", cause = e)
        }
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
                buildList { forEachRecord(file, ::add) }
            } else if (firstLine.startsWith("##gff-version")) {
                listOf(parse(file.readText(), file.nameWithoutExtension))
            } else if (firstLine.startsWith("ID   ")) {
                buildList { forEachRecord(file, ::add) }
            } else {
                buildList { forEachRecord(file, ::add) }
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

    private fun streamDelimitedRecords(
        file: File,
        consumer: (Seq) -> Unit,
        parser: (String) -> Seq,
    ): Int {
        var count = 0
        val current = StringBuilder()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                current.append(line).append('\n')
                if (line.trimStart().startsWith("//")) {
                    consumer(parser(current.toString()))
                    count++
                    current.setLength(0)
                }
            }
        }
        if (current.isNotBlank()) throw SeqIOException("${file.name} is missing the record terminator '//'")
        return count
    }

    private fun isGenBankFile(file: File): Boolean = file.extension.lowercase() in SeqFormat.GENBANK.extensions

    private fun String.normalizedOpeningLine(): String =
        trimStart().removePrefix("\uFEFF").trimStart()

    /** Complete source records bundled for offline GUI and CLI use. */
    object Samples {
        const val SOURCE_METADATA_KEY = "IG_SAMPLE_SOURCE"
        private const val PBR322_RESOURCE = "/org/instagene/core/samples/pBR322_J01749.1.gb"
        private const val PUC19_REFERENCE_RESOURCE = "/org/instagene/core/samples/pUC19_M77789.2.gb"
        private const val GFP_REFERENCE_RESOURCE = "/org/instagene/core/samples/GFP_L29345.1.gb"
        private const val PGFPUV_REFERENCE_RESOURCE = "/org/instagene/core/samples/pGFPuv_U62636.1.gb"

        const val PBR322_NCBI_SOURCE =
            "Complete source record bundled from NCBI GenBank accession J01749.1 (Cloning vector pBR322, complete sequence); the full sequence, feature table, authors, and publication references are retained from the source record."
        const val PUC19_NCBI_REFERENCE_SOURCE =
            "Complete source record bundled from NCBI GenBank accession M77789.2 (Cloning vector pUC19, complete sequence); the full sequence, feature table, authors, and publication references are retained from the source record."
        const val GFP_AEQUOREA_NCBI_REFERENCE_SOURCE =
            "Complete source record bundled from NCBI GenBank accession L29345.1 (Aequorea victoria green-fluorescent protein mRNA, complete cds); the full sequence, feature table, authors, and publication references are retained from the source record."
        const val PGFPUV_NCBI_REFERENCE_SOURCE =
            "Complete source record bundled from NCBI GenBank accession U62636.1 (Cloning vector pGFPuv, complete sequence); the full sequence, feature table, authors, and submission references are retained from the source record."

        val PBR322_NCBI: Seq = sourceRecord(
            resource = PBR322_RESOURCE,
            name = "pBR322_NCBI",
            accession = "J01749.1",
            sourceUrl = "https://www.ncbi.nlm.nih.gov/nuccore/J01749.1",
            sourceStatement = PBR322_NCBI_SOURCE,
            checksum = "02cc9962c0600186d4e1e4055b0de44f2fb0c6a0512e52cf9e5f788f21c180ee",
        )

        /** Complete offline pUC19 source record from NCBI GenBank M77789.2. */
        val PUC19_NCBI_REFERENCE: Seq = sourceRecord(
            resource = PUC19_REFERENCE_RESOURCE,
            name = "pUC19_NCBI_reference",
            accession = "M77789.2",
            sourceUrl = "https://www.ncbi.nlm.nih.gov/nuccore/M77789.2",
            sourceStatement = PUC19_NCBI_REFERENCE_SOURCE,
            checksum = "b2651308eedffa54dfb3a1b6307cacdda959e7164a799bc813c883117a1b40d5",
        )

        /** Complete offline Aequorea victoria GFP source record from NCBI GenBank L29345.1. */
        val GFP_AEQUOREA_NCBI_REFERENCE: Seq = sourceRecord(
            resource = GFP_REFERENCE_RESOURCE,
            name = "GFP_Aequorea_NCBI_reference",
            accession = "L29345.1",
            sourceUrl = "https://www.ncbi.nlm.nih.gov/nuccore/L29345.1",
            sourceStatement = GFP_AEQUOREA_NCBI_REFERENCE_SOURCE,
            checksum = "7521e075ea6e1832e4972d3ef457e8b79ff984cc0ade4189549b166bf60317c5",
        )

        /** Complete offline pGFPuv source record from NCBI GenBank U62636.1. */
        val PGFPUV_NCBI_REFERENCE: Seq = sourceRecord(
            resource = PGFPUV_REFERENCE_RESOURCE,
            name = "pGFPuv_NCBI_reference",
            accession = "U62636.1",
            sourceUrl = "https://www.ncbi.nlm.nih.gov/nuccore/U62636.1",
            sourceStatement = PGFPUV_NCBI_REFERENCE_SOURCE,
            checksum = "ea180ed97a540172ff82a6d32b9ba36b3da6d9de79baebc9a7709a104c38c9ac",
        )

        val ALL: List<Seq> = listOf(
            PBR322_NCBI,
            PUC19_NCBI_REFERENCE,
            GFP_AEQUOREA_NCBI_REFERENCE,
            PGFPUV_NCBI_REFERENCE,
        )

        fun sourceFor(name: String): String? =
            ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.metadata
                ?.get(SOURCE_METADATA_KEY)

        private fun sourceRecord(
            resource: String,
            name: String,
            accession: String,
            sourceUrl: String,
            sourceStatement: String,
            checksum: String,
        ): Seq {
            val record = parseResource(resource, name)
            return ExampleMetadataInference.apply(record.copy(
                name = name,
                metadata = record.metadata + mapOf(
                    SOURCE_METADATA_KEY to sourceStatement,
                    "ONLINE_ACCESSION" to accession,
                    "ONLINE_SOURCE" to "NCBI GenBank",
                    "ONLINE_URL" to sourceUrl,
                    "SOURCE_RETRIEVED" to "2026-09-01",
                    "SOURCE_SHA256" to checksum,
                    "ANNOTATION_SOURCE" to "Complete GenBank feature table from NCBI accession $accession",
                ),
                recordMetadata = record.recordMetadata.copy(
                    createdAt = record.recordMetadata.createdAt ?: record.recordMetadata.modifiedAt,
                    references = record.recordMetadata.references.map { reference ->
                        reference.copy(sourceUrl = reference.sourceUrl ?: sourceUrl)
                    },
                ).withResolvedAuthor(),
            ))
        }

        private fun parsePbr322Resource(): Seq {
            return parseResource(PBR322_RESOURCE, "pBR322_J01749.1.gb")
        }

        private fun parseResource(resource: String, defaultName: String): Seq {
            val text = SeqIO::class.java.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Missing bundled sample resource: $resource")
            return parse(text, defaultName)
        }
    }
}

/** Convenience: `seq.toFasta()` reads better than `Fasta.write(seq)` at call sites. */
fun Seq.toFasta(): String = Fasta.write(this)

/** Convenience: `seq.toGenBank()` reads better than `GenBank.write(seq)` at call sites. */
fun Seq.toGenBank(): String = GenBank.write(this)

/** True when this sequence holds nucleotides rather than amino acids. */
fun Seq.isNucleotide(): Boolean = kind != SeqKind.PROTEIN
