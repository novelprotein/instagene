package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Topology
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

    private val LOCATION_RANGE = Regex("""(\d+)\s*\.\.\s*[><]?(\d+)""")
    private val SINGLE_POSITION = Regex("""^\s*[><]?(\d+)\s*$""")

    /** True when [text] opens with a LOCUS line, the GenBank signature. */
    fun looksLikeGenBank(text: String): Boolean =
        text.lineSequence().take(5).any { it.startsWith("LOCUS") }

    // ------------------------------------------------------------------ reading

    /** Parses one GenBank record from [text]. */
    fun parse(text: String, defaultName: String = "sequence"): Seq =
        parseFrom(StringReader(text), defaultName)

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
        val metadata = LinkedHashMap<String, String>()
        val bases = StringBuilder()

        var section = ""
        var pendingLocation: String? = null
        var pendingType = ""
        val qualifiers = LinkedHashMap<String, MutableList<String>>()
        var lastQualifier: String? = null
        var pendingMetadata: String? = null

        fun flushFeature() {
            val loc = pendingLocation ?: return
            pendingLocation = null
            val strand = if (loc.contains("complement")) Strand.REVERSE else Strand.FORWARD
            val label = qualifiers["label"]?.firstOrNull() ?: qualifiers["gene"]?.firstOrNull()
                ?: qualifiers["product"]?.firstOrNull() ?: qualifiers["note"]?.firstOrNull() ?: pendingType
            for ((start, end) in parseLocations(loc)) {
                features += Feature(
                    name = label,
                    type = pendingType,
                    start = start,
                    end = end,
                    strand = strand,
                    notes = qualifiers["note"]?.joinToString("\n").orEmpty(),
                    qualifiers = qualifiers.mapValues { (_, values) -> values.toList() },
                )
            }
            qualifiers.clear()
            lastQualifier = null
        }

        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trimEnd()
                if (line.isBlank()) continue
                when {
                    line.startsWith("LOCUS") -> {
                        section = "LOCUS"
                        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (parts.size > 1) name = parts[1]
                        if (line.contains("circular", ignoreCase = true)) topology = Topology.CIRCULAR
                        if (Regex("\\bRNA\\b").containsMatchIn(line)) kind = SeqKind.RNA
                        pendingMetadata = null
                    }

                    line.startsWith("DEFINITION") -> {
                        section = "DEFINITION"
                        description = line.removePrefix("DEFINITION").trim()
                        pendingMetadata = null
                    }

                    line.startsWith("FEATURES") -> {
                        section = "FEATURES"
                        pendingMetadata = null
                    }

                    line.startsWith("ORIGIN") -> {
                        flushFeature()
                        section = "ORIGIN"
                        pendingMetadata = null
                    }

                    line.startsWith("//") -> {
                        flushFeature()
                        section = ""
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
                                metadata[key] = line.drop(12).trim()
                                section = "HEADER"
                            } else {
                                section = ""
                            }
                        }
                    }

                    line.firstOrNull()?.isWhitespace() == false -> {
                        val key = line.take(12).trim()
                        if (key.isNotEmpty()) {
                            pendingMetadata = key
                            metadata[key] = line.drop(12).trim()
                            section = "HEADER"
                        }
                    }

                    section == "HEADER" && pendingMetadata != null -> {
                        // ORGANISM is the one standard header subfield which is
                        // indented even though it starts a new value, not a
                        // continuation of SOURCE.
                        if (line.startsWith("  ORGANISM")) {
                            val key = "ORGANISM"
                            pendingMetadata = key
                            metadata[key] = line.removePrefix("  ORGANISM").trim()
                        } else {
                            val key = pendingMetadata
                            metadata[key] = metadata[key].orEmpty() + " " + line.trim()
                        }
                    }
                }
            }
        }

        val seqBases = bases.toString().uppercase()
        if (kind == SeqKind.DNA) kind = Fasta.detectKind(seqBases)
        return Seq(
            name = name,
            bases = seqBases,
            kind = kind,
            topology = topology,
            // Features beyond the sequence end are clipped rather than dropped, so a
            // record whose sequence got truncated still keeps its annotations.
            features = features.mapNotNull { f ->
                val end = minOf(f.end, seqBases.length)
                if (end > f.start) f.copy(end = end) else null
            }.sortedBy { it.start },
            description = description,
            metadata = metadata,
        )
    }

    /** Turns a GenBank location string into 0-based half-open spans. */
    private fun parseLocations(location: String): List<Pair<Int, Int>> {
        val ranges = LOCATION_RANGE.findAll(location)
            .map { it.groupValues[1].toInt() - 1 to it.groupValues[2].toInt() }
            .toList()
        if (ranges.isNotEmpty()) return ranges
        val single = SINGLE_POSITION.find(location.replace(Regex("[a-z()]"), ""))
        return single?.let { listOf(it.groupValues[1].toInt() - 1 to it.groupValues[1].toInt()) }
            ?: emptyList()
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
        val metadata = seq.metadata.filterKeys { it !in setOf("LOCUS", "DEFINITION", "FEATURES", "ORIGIN") }
        appendHeaderField("ACCESSION", metadata["ACCESSION"] ?: ".")
        metadata.filterKeys { it != "ACCESSION" }.forEach { (key, value) -> appendHeaderField(key, value) }
        if ("SOURCE" !in metadata) append("SOURCE      InstaGene\n")
        if ("ORGANISM" !in metadata) append("  ORGANISM  synthetic construct\n")
        append("FEATURES             Location/Qualifiers\n")
        for (f in seq.features.sortedBy { it.start }) {
            val range = f.locationSegments.joinToString(",") { "${it.start + 1}..${it.end}" }
            val joined = if (f.locationSegments.size > 1) "join($range)" else range
            val location = if (f.strand == Strand.REVERSE) "complement($joined)" else joined
            append("     %-16s%s\n".format(f.type.take(15), location))
            val qualifiers = if (f.qualifiers.isEmpty()) {
                buildMap {
                    put("label", listOf(f.name))
                    if (f.notes.isNotBlank()) put("note", listOf(f.notes))
                }
            } else f.qualifiers
            qualifiers.forEach { (key, values) ->
                if (values.isEmpty()) append("                     /$key\n")
                values.forEach { value -> appendQualifier(key, value) }
            }
        }
        append("ORIGIN\n")
        append(origin(seq.bases))
        append("//\n")
    }

    /** Writes a header value on its own line, avoiding malformed embedded newlines. */
    private fun StringBuilder.appendHeaderField(key: String, value: String) {
        append(key.take(12).padEnd(12)).append(' ').append(value.replace('\n', ' ')).append('\n')
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
}
