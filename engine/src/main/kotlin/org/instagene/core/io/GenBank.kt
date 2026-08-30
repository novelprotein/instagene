package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Feature
import org.instagene.core.FeatureLocationNode
import org.instagene.core.MoleculeProperties
import org.instagene.core.PrimerAnnotation
import org.instagene.core.ProcedureRecord
import org.instagene.core.RecordHeaderField
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Strandedness
import org.instagene.core.Topology
import org.instagene.core.FeatureLocationMetadata
import org.instagene.core.SequenceRecordMetadata
import org.instagene.core.SequenceReference
import java.io.Reader
import java.io.StringReader

/**
 * A pragmatic GenBank flat-file reader/writer.
 *
 * It covers what a cloning tool needs — LOCUS, DEFINITION, the FEATURES table
 * (including `complement(...)` and `join(...)`) and ORIGIN — rather than the
 * whole NCBI specification.
 */
object GenBank {

    /** True when [text] opens with a LOCUS line, the GenBank signature. */
    fun looksLikeGenBank(text: String): Boolean =
        text.lineSequence().take(5).any { it.normalizedKeywordLine().startsWith("LOCUS") }

    // ------------------------------------------------------------------ reading

    /** Parses one GenBank record from [text]. */
    fun parse(text: String, defaultName: String = "sequence"): Seq =
        parseFrom(StringReader(text), defaultName)

    /** Parses each `//`-terminated record without retaining the complete input. */
    fun forEachRecord(reader: Reader, defaultName: String = "sequence", consumer: (Seq) -> Unit): Int {
        val records = reader.buffered()
        val current = StringBuilder()
        var count = 0
        while (true) {
            val line = records.readLine() ?: break
            current.append(line).append('\n')
            if (line.trim() == "//") {
                consumer(parse(current.toString(), defaultName))
                count++
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) throw SeqIOException("GenBank input is missing the record terminator '//'")
        return count
    }

    /**
     * Parses one GenBank record from [reader], line by line, so a large genome
     * flat file is never buffered in its entirety. The record's sequence is
     * accumulated in a single builder, while the LOCUS and FEATURES data remain small.
     */
    fun parseFrom(reader: Reader, defaultName: String = "sequence"): Seq {
        var name = defaultName
        var description = ""
        var topology = Topology.LINEAR
        var kind = SeqKind.DNA
        val features = ArrayList<Feature>()
        val primers = ArrayList<PrimerAnnotation>()
        val metadata = LinkedHashMap<String, String>()
        val headerFields = ArrayList<RecordHeaderField>()
        val taxonomy = ArrayList<String>()
        val bases = StringBuilder()

        var section = ""
        var pendingLocation: String? = null
        var pendingType = ""
        val qualifiers = LinkedHashMap<String, MutableList<String>>()
        var lastQualifier: String? = null
        var pendingMetadata: String? = null
        var sawLocus = false
        var sawOrigin = false
        var sawContig = false
        var sawTerminator = false

        fun setHeader(key: String, value: String) {
            metadata[key] = value
            headerFields += RecordHeaderField(key, value)
        }

        fun appendHeaderContinuation(key: String, value: String) {
            metadata[key] = metadata[key].orEmpty() + " " + value
            val last = headerFields.indexOfLast { it.key == key }
            if (last >= 0) headerFields[last] = headerFields[last].copy(value = metadata[key].orEmpty())
            else headerFields += RecordHeaderField(key, metadata[key].orEmpty())
        }

        fun flushFeature() {
            val loc = pendingLocation ?: return
            pendingLocation = null
            // source is a metadata feature spanning the whole sequence; skip it.
            if (pendingType.equals("source", ignoreCase = true)) { qualifiers.clear(); lastQualifier = null; return }
            val parsed = GenBankLocations.parse(loc)
            val segments = parsed.segments
            if (segments.isEmpty()) {
                qualifiers.clear()
                lastQualifier = null
                return
            }
            val strand = if (segments.all { it.strand == Strand.REVERSE }) Strand.REVERSE else Strand.FORWARD
            val label = qualifiers["label"]?.firstOrNull() ?: qualifiers["gene"]?.firstOrNull()
                ?: qualifiers["product"]?.firstOrNull() ?: qualifiers["note"]?.firstOrNull() ?: pendingType
            val preserveStructuredLocation = segments.size > 1 ||
                loc.contains('^') || loc.contains(':') || loc.contains('<') || loc.contains('>') ||
                loc.contains("order(", ignoreCase = true) || loc.contains("bond(", ignoreCase = true)
            val locationMetadata = FeatureLocationMetadata(
                expression = loc,
                node = parsed.node,
                segmentCount = segments.size,
            ).takeIf { preserveStructuredLocation }
            for ((index, segment) in segments.withIndex()) {
                val featureLocation = locationMetadata?.copy(segmentIndex = index)
                if (pendingType.equals("primer_bind", true)) {
                    val bound = qualifiers["sequence"]?.firstOrNull().orEmpty()
                    primers += PrimerAnnotation(
                        name = label,
                        bases = bound,
                        bindingStart = segment.start,
                        bindingEnd = segment.end,
                        strand = strand,
                        extension = qualifiers["extension"]?.firstOrNull().orEmpty(),
                        description = qualifiers["note"]?.joinToString("\n").orEmpty(),
                    )
                } else {
                    features += Feature(
                        name = label,
                        type = pendingType,
                        start = segment.start,
                        end = segment.end,
                        strand = strand,
                        notes = qualifiers["note"]?.joinToString("\n").orEmpty(),
                        qualifiers = qualifiers.mapValues { (_, values) -> values.toList() },
                        locationMetadata = featureLocation,
                        geneticCodeId = qualifiers["transl_table"]?.firstOrNull()?.toIntOrNull() ?: 1,
                        translationStartOffset = ((qualifiers["codon_start"]?.firstOrNull()?.toIntOrNull() ?: 1) - 1).coerceIn(0, 2),
                        translationNumberingStart = qualifiers["numbering_start"]?.firstOrNull()?.toIntOrNull() ?: 1,
                        ribosomalSlippage = qualifiers["ribosomal_slippage"]?.firstOrNull()?.toIntOrNull() ?: 0,
                    )
                }
            }
            qualifiers.clear()
            lastQualifier = null
        }

        reader.buffered().use { lines ->
            while (true) {
                val raw = lines.readLine() ?: break
                val line = raw.trimEnd()
                if (line.isBlank()) continue
                val keywordLine = line.normalizedKeywordLine()
                when {
                    keywordLine.startsWith("LOCUS") -> {
                        section = "LOCUS"
                        sawLocus = true
                        sawTerminator = false
                        val parts = keywordLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (parts.size > 1) name = parts[1]
                        if (keywordLine.contains("circular", ignoreCase = true)) topology = Topology.CIRCULAR
                        if (Regex("\\bRNA\\b").containsMatchIn(keywordLine)) kind = SeqKind.RNA
                        pendingMetadata = null
                    }

                    keywordLine.startsWith("DEFINITION") -> {
                        section = "DEFINITION"
                        description = keywordLine.removePrefix("DEFINITION").trim()
                        setHeader("DEFINITION", description)
                        pendingMetadata = null
                    }

                    keywordLine.startsWith("FEATURES") -> {
                        section = "FEATURES"
                        pendingMetadata = null
                    }

                    keywordLine.startsWith("ORIGIN") -> {
                        flushFeature()
                        section = "ORIGIN"
                        sawOrigin = true
                        pendingMetadata = null
                    }

                    keywordLine.startsWith("//") -> {
                        flushFeature()
                        sawTerminator = true
                        break
                    }

                    keywordLine == line && line.firstOrNull()?.isWhitespace() == false -> {
                        flushFeature()
                        val key = keywordLine.take(12).trim()
                        if (key.isNotEmpty()) {
                            if (key == "CONTIG" || key == "WGS") sawContig = true
                            pendingMetadata = key
                            setHeader(key, keywordLine.drop(12).trim())
                            section = "HEADER"
                        }
                    }

                    section == "ORIGIN" -> bases.append(Alphabet.clean(line))

                    section == "FEATURES" -> {
                        val trimmed = line.trim()
                        val isQualifier = trimmed.startsWith("/")
                        val isNewFeature = line.length > 21 && line[5] != ' ' && !isQualifier
                        when {
                            isNewFeature -> {
                                flushFeature()
                                pendingType = line.substring(5, 21).trim()
                                pendingLocation = line.substring(21).trim()
                                lastQualifier = null
                            }

                            isQualifier -> {
                                val body = trimmed.removePrefix("/")
                                val key = body.substringBefore('=')
                                val value = body.substringAfter('=', "").trim().trim('"')
                                qualifiers.getOrPut(key) { ArrayList() } += value
                                lastQualifier = key
                            }

                            lastQualifier != null -> {
                                // GenBank permits quoted qualifier values to wrap
                                // onto indented continuation lines.
                                val values = qualifiers.getValue(lastQualifier!!)
                                values[values.lastIndex] += trimmed.trim('"')
                            }

                            qualifiers.isEmpty() -> {
                                // Continuation of a location that wrapped onto the next line.
                                val current = pendingLocation
                                if (current != null) pendingLocation = current + trimmed
                            }
                        }
                    }

                    section == "DEFINITION" -> {
                        // Only space-indented lines continue the definition; the
                        // next column-0 keyword (ACCESSION, SOURCE, ...) ends it.
                        if (line.startsWith(" ")) {
                            description += " " + line.trim()
                        } else {
                            val key = line.take(12).trim()
                            if (key.isNotEmpty()) {
                                pendingMetadata = key
                                setHeader(key, line.drop(12).trim())
                                section = "HEADER"
                            } else {
                                section = ""
                            }
                        }
                    }

                    section == "HEADER" && pendingMetadata != null -> {
                        // ORGANISM is the one standard header subfield which is
                        // indented even though it starts a new value, not a
                        // continuation of SOURCE.
                        val headerText = line.trimStart()
                        val subfield = headerText.takeWhile { it.isLetterOrDigit() || it == '_' }
                        if (subfield in setOf("ORGANISM", "AUTHORS", "TITLE", "JOURNAL", "PUBMED", "MEDLINE")) {
                            pendingMetadata = subfield
                            setHeader(subfield, headerText.removePrefix(subfield).trim())
                        } else {
                            val key = pendingMetadata
                            if (key == "ORGANISM") taxonomy += line.trim()
                            else appendHeaderContinuation(key, line.trim())
                        }
                    }
                }
            }
        }

        if (!sawLocus) throw SeqIOException("GenBank input does not start with a LOCUS record")
        if (!sawOrigin && !sawContig) throw SeqIOException("GenBank input is missing an ORIGIN section")
        if (!sawTerminator) throw SeqIOException("GenBank input is missing the record terminator '//'")
        val seqBases = bases.toString().uppercase()
        if (seqBases.isEmpty() && !sawContig) throw SeqIOException("GenBank input contains no sequence bases")
        if (kind == SeqKind.DNA) kind = Fasta.detectKind(seqBases)
        val parsedFeatures = if (seqBases.isEmpty()) {
            features
        } else {
            // Features beyond the sequence end are clipped rather than dropped, so a
            // record whose sequence got truncated still keeps its annotations.
            features.mapNotNull { f ->
                val end = minOf(f.end, seqBases.length)
                if (end > f.start) f.copy(end = end, locationMetadata = if (end == f.end) f.locationMetadata else null) else null
            }
        }
        val molecule = MoleculeProperties(
            strandedness = metadata["IG_STRANDS"]?.let { runCatching { Strandedness.valueOf(it) }.getOrNull() }
                ?: if (kind == SeqKind.PROTEIN) Strandedness.SINGLE else Strandedness.DOUBLE,
            damMethylated = metadata["IG_DAM"].toBoolean(),
            dcmMethylated = metadata["IG_DCM"].toBoolean(),
            cpgMethylated = metadata["IG_CPG"].toBoolean(),
            fivePrimePhosphorylated = metadata["IG_5P"]?.toBooleanStrictOrNull() ?: true,
            threePrimePhosphorylated = metadata["IG_3P"].toBoolean(),
        )
        val provenance = metadata["IG_HISTORY"].orEmpty().split(" || ").filter(String::isNotBlank).map { encoded ->
            val fields = encoded.split('|', limit = 3)
            ProcedureRecord(fields.first(), fields.getOrElse(1) { "" }, timestamp = fields.getOrElse(2) { "0" }.toLongOrNull() ?: 0L)
        }
        val references = parseReferences(headerFields)
        val recordMetadata = SequenceRecordMetadata(
            headerFields = headerFields.filterNot { it.key == "LOCUS" },
            comments = headerFields.filter { it.key == "COMMENT" }.map { it.value },
            references = references,
            source = metadata["SOURCE"],
            organism = metadata["ORGANISM"],
            taxonomy = taxonomy,
            databaseReferences = headerFields.filter { it.key == "DBLINK" }.flatMap { it.value.split(';').map(String::trim) }.filter(String::isNotBlank),
        )
        return Seq(
            name = name,
            bases = seqBases,
            kind = kind,
            topology = topology,
            features = parsedFeatures.sortedBy { it.start },
            description = description,
            metadata = metadata,
            primers = primers.sortedBy { it.bindingStart },
            molecule = molecule,
            provenance = provenance,
            recordMetadata = recordMetadata,
        )
    }

    private fun parseReferences(fields: List<RecordHeaderField>): List<SequenceReference> {
        val references = ArrayList<SequenceReference>()
        var current: SequenceReference? = null
        for (field in fields) {
            when (field.key) {
                "REFERENCE" -> {
                    current?.let { references += it }
                    current = SequenceReference(reference = field.value)
                }
                "AUTHORS" -> current = (current ?: SequenceReference()).copy(authors = field.value)
                "TITLE" -> current = (current ?: SequenceReference()).copy(title = field.value)
                "JOURNAL" -> current = (current ?: SequenceReference()).copy(journal = field.value)
                "PUBMED" -> current = (current ?: SequenceReference()).copy(pubMed = field.value)
                "MEDLINE" -> current = (current ?: SequenceReference()).copy(medLine = field.value)
            }
        }
        current?.let { references += it }
        return references
    }

    // ------------------------------------------------------------------ writing

    /** Serializes [seq] as a GenBank flat file, with its features and topology. */
    fun write(seq: Seq): String = buildString {
        val molecule = if (seq.kind == SeqKind.RNA) "RNA" else "DNA"
        val shape = if (seq.isCircular) "circular" else "linear  "
        append(
            "LOCUS       %-16s%9d bp    %-6s  %-9s SYN %s\n".format(
                seq.name.take(16), seq.length, molecule, shape, "01-JAN-1980"
            )
        )
        append("DEFINITION  ${seq.description.ifBlank { seq.name }}\n")
        val stateMetadata = mapOf(
            "IG_STRANDS" to seq.molecule.strandedness.name,
            "IG_DAM" to seq.molecule.damMethylated.toString(),
            "IG_DCM" to seq.molecule.dcmMethylated.toString(),
            "IG_CPG" to seq.molecule.cpgMethylated.toString(),
            "IG_5P" to seq.molecule.fivePrimePhosphorylated.toString(),
            "IG_3P" to seq.molecule.threePrimePhosphorylated.toString(),
        ) + if (seq.provenance.isEmpty()) emptyMap() else mapOf(
            "IG_HISTORY" to seq.provenance.joinToString(" || ") {
                "${it.operation.replace('|', '/') }|${it.summary.replace('|', '/')}|${it.timestamp}"
            }
        )
        val metadata = seq.metadata.filterKeys { it !in setOf("LOCUS", "DEFINITION", "FEATURES", "ORIGIN") } + stateMetadata
        val structuredFields = seq.recordMetadata.headerFields.filterNot {
            it.key in setOf("LOCUS", "DEFINITION", "FEATURES", "ORIGIN") || it.key in stateMetadata
        }
        val accession = structuredFields.firstOrNull { it.key == "ACCESSION" }?.value
            ?: metadata["ACCESSION"]
            ?: "."
        appendHeaderField("ACCESSION", accession)
        if (structuredFields.isNotEmpty()) {
            structuredFields.filter { it.key != "ACCESSION" && it.key != "ORGANISM" }
                .forEach { appendHeaderField(it.key, it.value) }
            structuredFields.firstOrNull { it.key == "ORGANISM" }?.let {
                appendOrganism(it.value, seq.recordMetadata.taxonomy)
            } ?: metadata["ORGANISM"]?.let {
                appendOrganism(it, seq.recordMetadata.taxonomy)
            }
            if (structuredFields.none { it.key == "COMMENT" }) {
                seq.recordMetadata.comments.forEach { appendHeaderField("COMMENT", it) }
            }
            if (structuredFields.none { it.key == "REFERENCE" }) {
                seq.recordMetadata.references.forEach { reference ->
                    appendHeaderField("REFERENCE", reference.reference)
                    if (reference.authors.isNotBlank()) appendHeaderField("AUTHORS", reference.authors)
                    if (reference.title.isNotBlank()) appendHeaderField("TITLE", reference.title)
                    if (reference.journal.isNotBlank()) appendHeaderField("JOURNAL", reference.journal)
                    reference.pubMed?.let { appendHeaderField("PUBMED", it) }
                    reference.medLine?.let { appendHeaderField("MEDLINE", it) }
                }
            }
        } else {
            metadata.filterKeys { it != "ACCESSION" && it != "ORGANISM" }.forEach { (key, value) -> appendHeaderField(key, value) }
            metadata["ORGANISM"]?.let { appendOrganism(it, seq.recordMetadata.taxonomy) }
            if (seq.recordMetadata.comments.isNotEmpty() && "COMMENT" !in metadata) {
                seq.recordMetadata.comments.forEach { appendHeaderField("COMMENT", it) }
            }
            if (seq.recordMetadata.references.isNotEmpty() && "REFERENCE" !in metadata) {
                seq.recordMetadata.references.forEach { reference ->
                    appendHeaderField("REFERENCE", reference.reference)
                    if (reference.authors.isNotBlank()) appendHeaderField("AUTHORS", reference.authors)
                    if (reference.title.isNotBlank()) appendHeaderField("TITLE", reference.title)
                    if (reference.journal.isNotBlank()) appendHeaderField("JOURNAL", reference.journal)
                    reference.pubMed?.let { appendHeaderField("PUBMED", it) }
                    reference.medLine?.let { appendHeaderField("MEDLINE", it) }
                }
            }
        }
        val hasSource = structuredFields.any { it.key == "SOURCE" } || "SOURCE" in metadata
        val hasOrganism = structuredFields.any { it.key == "ORGANISM" } || "ORGANISM" in metadata
        if (!hasSource) appendHeaderField("SOURCE", seq.recordMetadata.source ?: "InstaGene")
        if (!hasOrganism) appendOrganism(seq.recordMetadata.organism ?: "synthetic construct", seq.recordMetadata.taxonomy)
        if (structuredFields.none { it.key == "DBLINK" } && seq.recordMetadata.databaseReferences.isNotEmpty()) {
            seq.recordMetadata.databaseReferences.forEach { appendHeaderField("DBLINK", it) }
        }
        append("FEATURES             Location/Qualifiers\n")
        val persistedPrimerNames = seq.primers.map { it.name.lowercase() }.toSet()
        val featureRows = seq.features.sortedBy { it.start }.filterNot {
            it.type.equals("primer_bind", true) && it.name.lowercase() in persistedPrimerNames
        }
        val writtenCompoundLocations = HashSet<String>()
        for (feature in featureRows) {
            val compound = feature.locationMetadata?.takeIf { it.segmentCount > 1 }
            val compoundKey = compound?.let { "${feature.type}\u0000${feature.name}\u0000${it.expression}" }
            if (compoundKey != null) {
                val group = featureRows.filter { candidate ->
                    val candidateMetadata = candidate.locationMetadata
                    candidate.type == feature.type && candidate.name == feature.name &&
                        candidateMetadata?.expression == compound.expression &&
                        candidateMetadata.segmentCount == compound.segmentCount
                }
                if (group.size >= compound.segmentCount && group.mapNotNull { it.locationMetadata?.segmentIndex }.toSet().size == compound.segmentCount) {
                    if (!writtenCompoundLocations.add(compoundKey)) continue
                    appendFeature(group.minBy { it.locationMetadata?.segmentIndex ?: Int.MAX_VALUE }, compound.node?.let(GenBankLocations::format))
                    continue
                }
            }
            appendFeature(feature, null)
        }
        for ((name, bases, bindingStart, bindingEnd, strand, extension, description) in seq.primers.sortedBy { it.bindingStart }) {
            val range = "${bindingStart + 1}..$bindingEnd"
            val location = if (strand == Strand.REVERSE) "complement($range)" else range
            append("     %-16s%s\n".format("primer_bind", location))
            appendQualifier("label", name)
            appendQualifier("sequence", bases)
            if (extension.isNotBlank()) appendQualifier("extension", extension)
            if (description.isNotBlank()) appendQualifier("note", description)
        }
        append("ORIGIN\n")
        append(origin(seq.bases))
        append("//\n")

    }

    /** Writes a header value on its own line, avoiding malformed embedded newlines. */
    private fun StringBuilder.appendHeaderField(key: String, value: String) {
        if (key == "ORGANISM") {
            append("  ORGANISM  ").append(value.replace('\n', ' ')).append('\n')
        } else {
            append(key.take(12).padEnd(12)).append(' ').append(value.replace('\n', ' ')).append('\n')
        }
    }

    private fun StringBuilder.appendOrganism(value: String, taxonomy: List<String>) {
        appendHeaderField("ORGANISM", value)
        taxonomy.forEach { append("            ").append(it.replace('\n', ' ')).append('\n') }
    }

    private fun legacyLocation(feature: Feature): String {
        val children = feature.locationSegments.map { segment -> FeatureLocationNode(segment = segment) }
        val node = if (children.size == 1) children.single() else FeatureLocationNode(
            operator = org.instagene.core.FeatureLocationOperator.JOIN,
            children = children,
        )
        val location = GenBankLocations.format(node)
        return if (feature.strand == Strand.REVERSE) "complement($location)" else location
    }

    private fun StringBuilder.appendFeature(f: Feature, structuredLocation: String? = null) {
        val location = structuredLocation ?: legacyLocation(f)
        append("     %-16s%s\n".format(f.type.take(15), location))
        val defaults = buildMap {
            put("label", listOf(f.name))
            if (f.notes.isNotBlank()) put("note", listOf(f.notes))
            if (f.geneticCodeId != 1) put("transl_table", listOf(f.geneticCodeId.toString()))
            if (f.translationStartOffset != 0) put("codon_start", listOf((f.translationStartOffset + 1).toString()))
            if (f.translationNumberingStart != 1) put("numbering_start", listOf(f.translationNumberingStart.toString()))
            if (f.ribosomalSlippage != 0) put("ribosomal_slippage", listOf(f.ribosomalSlippage.toString()))
        }
        val qualifiers = if (f.qualifiers.isEmpty()) defaults else defaults + f.qualifiers
        qualifiers.forEach { (key, values) ->
            if (values.isEmpty()) append("                     /$key\n")
            values.forEach { value -> appendQualifier(key, value) }
        }
    }

    /** Writes a quoted qualifier with embedded quotes escaped for a GenBank flat file. */
    private fun StringBuilder.appendQualifier(key: String, value: String) {
        append("                     /").append(key).append("=\"")
            .append(value.replace("\"", "\"\"").replace('\n', ' ')).append("\"\n")
    }

    /** The classic 60-per-line, 10-per-block ORIGIN body. */
    private fun origin(bases: String): String = buildString {
        var i = 0
        while (i < bases.length) {
            val line = bases.substring(i, minOf(i + 60, bases.length))
            append("%9d ".format(i + 1))
            append(line.lowercase().chunked(10).joinToString(" "))
            append('\n')
            i += 60
        }
    }

    private fun String.normalizedKeywordLine(): String =
        trimStart().removePrefix("\uFEFF").trimStart()
}
