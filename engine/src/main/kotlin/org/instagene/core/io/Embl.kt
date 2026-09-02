package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Feature
import org.instagene.core.FeatureLocationMetadata
import org.instagene.core.MoleculeProperties
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.RecordHeaderField
import org.instagene.core.SequenceRecordMetadata
import org.instagene.core.SequenceReference
import org.instagene.core.SequenceOrigin
import org.instagene.core.MethylationSource
import org.instagene.core.Strand
import org.instagene.core.Topology

/** EMBL/ENA and Swiss-Prot flat-file support for sequences, descriptions, and feature annotations. */
object Embl {
    fun looksLike(text: String): Boolean {
        val lines = text.lineSequence().take(12).toList()
        return lines.any { it.startsWith("ID   ") } && lines.any { it.startsWith("SQ   ") || it.startsWith("FH   ") || it.startsWith("DE   ") }
    }

    fun parse(text: String, defaultName: String = "sequence"): Seq {
        var name = defaultName
        var description = ""
        var kind = SeqKind.DNA
        var topology = Topology.LINEAR
        val metadata = linkedMapOf<String, String>()
        val headerFields = arrayListOf<RecordHeaderField>()
        val comments = arrayListOf<String>()
        val taxonomy = arrayListOf<String>()
        val databaseReferences = arrayListOf<String>()
        val references = arrayListOf<SequenceReference>()
        var currentReference: SequenceReference? = null
        val features = arrayListOf<Feature>()
        val bases = StringBuilder()
        var inSequence = false
        var currentType: String? = null
        var currentLocation = ""
        val qualifiers = linkedMapOf<String, MutableList<String>>()

        fun flushFeature() {
            val type = currentType ?: return
            // source is a metadata feature spanning the whole sequence; skip it.
            if (type.equals("source", true)) { currentType = null; currentLocation = ""; qualifiers.clear(); return }
            val parsed = GenBankLocations.parse(currentLocation)
            val locationSegments = parsed.segments
            if (locationSegments.isNotEmpty()) {
                val label = qualifiers["label"]?.firstOrNull()
                    ?: qualifiers["gene"]?.firstOrNull()
                    ?: qualifiers["product"]?.firstOrNull()
                    ?: type
                val strand = if (locationSegments.all { it.strand == Strand.REVERSE }) Strand.REVERSE else Strand.FORWARD
                locationSegments.forEachIndexed { index, segment ->
                    features += Feature(
                        name = label,
                        type = type,
                        start = segment.start,
                        end = segment.end,
                        strand = strand,
                        notes = qualifiers["note"]?.joinToString("\n").orEmpty(),
                        qualifiers = qualifiers.mapValues { it.value.toList() },
                        locationMetadata = FeatureLocationMetadata(
                            expression = currentLocation,
                            node = parsed.node,
                            segmentIndex = index,
                            segmentCount = locationSegments.size,
                        ),
                    )
                }
            }
            currentType = null
            currentLocation = ""
            qualifiers.clear()
        }

        for (line in text.lineSequence()) {
            when {
                line.startsWith("ID   ") -> {
                    val body = line.drop(5)
                    name = body.substringBefore(';').trim().ifBlank { defaultName }
                    if (body.contains("circular", true)) topology = Topology.CIRCULAR
                    if (Regex("\\bAA\\.", RegexOption.IGNORE_CASE).containsMatchIn(body)) kind = SeqKind.PROTEIN
                    else if (Regex("\\bRNA\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) kind = SeqKind.RNA
                }
                line.startsWith("DE   ") -> description = listOf(description, line.drop(5).trim()).filter(String::isNotBlank).joinToString(" ")
                line.startsWith("AC   ") -> metadata["ACCESSION"] = line.drop(5).trim().trimEnd(';')
                line.startsWith("OS   ") -> {
                    metadata["ORGANISM"] = line.drop(5).trim()
                    headerFields += RecordHeaderField("ORGANISM", metadata.getValue("ORGANISM"))
                }
                line.startsWith("OC   ") -> taxonomy += line.drop(5).trim()
                line.startsWith("DR   ") -> {
                    val reference = line.drop(5).trim().trimEnd(';')
                    databaseReferences += reference
                    headerFields += RecordHeaderField("DBLINK", reference)
                }
                line.startsWith("CC   ") -> {
                    val comment = line.drop(5).trim()
                    val key = comment.substringBefore('=', "")
                    if (key.startsWith("IG_") && comment.contains('=')) {
                        val value = comment.substringAfter('=').trim()
                        if (key == "IG_REFURL" && currentReference != null) {
                            currentReference = currentReference.copy(sourceUrl = value)
                        } else {
                            metadata[key] = value
                            headerFields += RecordHeaderField(key, value)
                        }
                    } else {
                        comments += comment
                        metadata["COMMENT"] = sequenceOf(metadata["COMMENT"], comment).filterNotNull().filter(String::isNotBlank).joinToString(" ")
                        headerFields += RecordHeaderField("COMMENT", comment)
                    }
                }
                line.startsWith("RN   ") -> {
                    currentReference?.let { references += it }
                    currentReference = SequenceReference(reference = line.drop(5).trim())
                }
                line.startsWith("RA   ") -> currentReference = (currentReference ?: SequenceReference()).copy(
                    authors = line.drop(5).trim().trimEnd(';'),
                )
                line.startsWith("RT   ") -> currentReference = (currentReference ?: SequenceReference()).copy(
                    title = line.drop(5).trim().trim(';', '"'),
                )
                line.startsWith("RL   ") -> currentReference = (currentReference ?: SequenceReference()).copy(
                    journal = line.drop(5).trim(),
                )
                line.startsWith("RX   ") -> {
                    val rx = line.drop(5).trim()
                    currentReference = (currentReference ?: SequenceReference()).copy(
                        pubMed = rx.substringAfter("PUBMED;", "").trim().trimEnd(';', '.').ifBlank { null },
                    )
                }
                line.startsWith("FT   ") -> {
                    val body = line.drop(5)
                    val trimmed = body.trim()
                    if (trimmed.startsWith('/')) {
                        val key = trimmed.drop(1).substringBefore('=')
                        val value = trimmed.substringAfter('=', "").trim().trim('"')
                        qualifiers.getOrPut(key) { arrayListOf() } += value
                    } else if (body.take(15).trim().isNotEmpty()) {
                        flushFeature()
                        currentType = body.take(15).trim()
                        currentLocation = body.drop(15).trim()
                    } else if (currentType != null) currentLocation += trimmed
                }
                line.startsWith("SQ   ") -> { flushFeature(); inSequence = true }
                line.startsWith("//") -> { flushFeature(); break }
                inSequence -> bases.append(Alphabet.clean(line.filterNot(Char::isDigit)))
            }
        }
        val sequence = bases.toString().uppercase()
        require(sequence.isNotEmpty()) { "EMBL/Swiss-Prot record contains no sequence" }
        if (kind == SeqKind.DNA) kind = Fasta.detectKind(sequence)
        currentReference?.let { references += it }
        val recordMetadata = SequenceRecordMetadata(
            headerFields = headerFields,
            comments = comments,
            references = references,
            freeformReferences = headerFields.filter { it.key == "IG_FREE_REF" }.map { it.value },
            author = metadata["IG_AUTHOR"],
            nucleicAcidCategory = metadata["IG_NACAT"],
            labHostType = metadata["IG_HOSTTYPE"],
            hostStrain = metadata["IG_STRAIN"],
            origin = metadata["IG_ORIGIN"]?.let { runCatching { SequenceOrigin.valueOf(it) }.getOrNull() }
                ?: SequenceOrigin.UNKNOWN,
            originLocked = metadata["IG_ORLOCK"]?.toBooleanStrictOrNull() ?: false,
            createdAt = metadata["IG_CREATED"]?.toLongOrNull(),
            modifiedAt = metadata["IG_MODIFIED"]?.toLongOrNull(),
            source = metadata["IG_SOURCE"],
            organism = metadata["ORGANISM"],
            taxonomy = taxonomy,
            databaseReferences = databaseReferences,
        ).withResolvedAuthor()
        val molecule = MoleculeProperties(
            strandedness = metadata["IG_STRANDS"]?.let { runCatching { org.instagene.core.Strandedness.valueOf(it) }.getOrNull() }
                ?: if (kind == SeqKind.PROTEIN) org.instagene.core.Strandedness.SINGLE else org.instagene.core.Strandedness.DOUBLE,
            damMethylated = metadata["IG_DAM"]?.toBooleanStrictOrNull() ?: false,
            dcmMethylated = metadata["IG_DCM"]?.toBooleanStrictOrNull() ?: false,
            cpgMethylated = metadata["IG_CPG"]?.toBooleanStrictOrNull() ?: false,
            methylationSource = metadata["IG_METHYL_SRC"]?.let {
                runCatching { MethylationSource.valueOf(it) }.getOrNull()
            } ?: MethylationSource.UNKNOWN,
            damStateOverride = metadata["IG_DAM"]?.takeIf { it.equals("unknown", true) }
                ?.let { org.instagene.core.MethylationState.UNKNOWN },
            dcmStateOverride = metadata["IG_DCM"]?.takeIf { it.equals("unknown", true) }
                ?.let { org.instagene.core.MethylationState.UNKNOWN },
            cpgStateOverride = metadata["IG_CPG"]?.takeIf { it.equals("unknown", true) }
                ?.let { org.instagene.core.MethylationState.UNKNOWN },
            fivePrimePhosphorylated = metadata["IG_5P"]?.toBooleanStrictOrNull() ?: true,
            threePrimePhosphorylated = metadata["IG_3P"]?.toBooleanStrictOrNull() ?: false,
            )
        return Seq(
            name = name,
            bases = sequence,
            kind = kind,
            topology = topology,
            features = features.filter { it.start < sequence.length }.map {
                val end = minOf(it.end, sequence.length)
                it.copy(end = end, locationMetadata = if (end == it.end) it.locationMetadata else null)
            },
            description = description,
            metadata = metadata,
            molecule = molecule,
            recordMetadata = recordMetadata,
        )
    }

    fun write(seq: Seq): String = buildString {
        val unit = if (seq.kind == SeqKind.PROTEIN) "AA" else "BP"
        val molecule = when (seq.kind) { SeqKind.DNA -> "DNA"; SeqKind.RNA -> "RNA"; SeqKind.PROTEIN -> "PROTEIN" }
        append("ID   ${seq.name}; SV 1; ${seq.topology.name.lowercase()}; $molecule; STD; UNC; ${seq.length} $unit.\n")
        append("DE   ${seq.description.ifBlank { seq.name }}\n")
        seq.metadata["ACCESSION"]?.let { append("AC   $it;\n") }
        (seq.recordMetadata.organism ?: seq.metadata["ORGANISM"])?.let { append("OS   $it\n") }
        seq.recordMetadata.taxonomy.forEach { append("OC   ${it.replace('\n', ' ')}\n") }
        val comments = seq.recordMetadata.comments.ifEmpty { seq.metadata["COMMENT"]?.let(::listOf).orEmpty() }
        comments.forEach { append("CC   ${it.replace('\n', ' ')}\n") }
        fun cc(key: String, value: String?) {
            if (!value.isNullOrBlank()) append("CC   $key=${value.replace('\n', ' ')}\n")
        }
        cc("IG_AUTHOR", seq.recordMetadata.author)
        cc("IG_NACAT", seq.recordMetadata.nucleicAcidCategory)
        cc("IG_HOSTTYPE", seq.recordMetadata.labHostType)
        cc("IG_STRAIN", seq.recordMetadata.hostStrain)
        cc("IG_SOURCE", seq.recordMetadata.source)
        cc("IG_STRANDS", seq.molecule.strandedness.name)
        cc("IG_ORIGIN", seq.recordMetadata.origin.name)
        cc("IG_ORLOCK", seq.recordMetadata.originLocked.toString())
        cc("IG_CREATED", seq.recordMetadata.createdAt?.toString())
        cc("IG_MODIFIED", seq.recordMetadata.modifiedAt?.toString())
        cc("IG_DAM", if (seq.molecule.damState == org.instagene.core.MethylationState.UNKNOWN) "unknown" else seq.molecule.damMethylated.toString())
        cc("IG_DCM", if (seq.molecule.dcmState == org.instagene.core.MethylationState.UNKNOWN) "unknown" else seq.molecule.dcmMethylated.toString())
        cc("IG_CPG", if (seq.molecule.cpgState == org.instagene.core.MethylationState.UNKNOWN) "unknown" else seq.molecule.cpgMethylated.toString())
        cc("IG_METHYL_SRC", seq.molecule.methylationSource.name)
        cc("IG_5P", seq.molecule.fivePrimePhosphorylated.toString())
        cc("IG_3P", seq.molecule.threePrimePhosphorylated.toString())
        seq.recordMetadata.freeformReferences.forEach { cc("IG_FREE_REF", it) }
        seq.recordMetadata.references.forEach { reference ->
            append("RN   ${reference.reference}\n")
            if (reference.authors.isNotBlank()) append("RA   ${reference.authors};\n")
            if (reference.title.isNotBlank()) append("RT   \"${reference.title.replace('"', '\'')}\";\n")
            if (reference.journal.isNotBlank()) append("RL   ${reference.journal}\n")
            reference.pubMed?.let { append("RX   PUBMED; $it.\n") }
            reference.sourceUrl?.let { cc("IG_REFURL", it) }
        }
        append("FH   Key             Location/Qualifiers\n")
        append("FH\n")
        val writtenCompoundLocations = HashSet<String>()
        for (feature in seq.features) {
            val structured = feature.locationMetadata
            val compoundKey = structured?.takeIf { it.segmentCount > 1 }?.let { "${feature.type}\u0000${feature.name}\u0000${it.expression}" }
            if (compoundKey != null && !writtenCompoundLocations.add(compoundKey)) continue
            val location = structured?.node?.let(GenBankLocations::format) ?: run {
                val span = feature.locationSegments.joinToString(",") { "${it.start + 1}..${it.end}" }
                val joined = if (feature.locationSegments.size > 1) "join($span)" else span
                if (feature.strand == Strand.REVERSE) "complement($joined)" else joined
            }
            val name = feature.name
            val type = feature.type
            val notes = feature.notes
            append("FT   ${type.take(15).padEnd(15)}$location\n")
            append("FT                   /label=\"${name.replace('"', '\'')}\"\n")
            if (notes.isNotBlank()) append("FT                   /note=\"${notes.replace('\n', ' ').replace('"', '\'')}\"\n")
        }
        append("SQ   Sequence ${seq.length} $unit;\n")
        seq.bases.lowercase().chunked(60).forEachIndexed { index, line ->
            append("     ${line.chunked(10).joinToString(" ").padEnd(66)} ${(index * 60 + line.length)}\n")
        }
        append("//\n")
    }
}
