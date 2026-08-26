package org.instagene.core.io

import org.instagene.core.Alphabet
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
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
            val reverse = currentLocation.contains("complement", true)
            val positions = Regex("(\\d+)\\.\\.(\\d+)").find(currentLocation)
                ?: Regex("(\\d+)").find(currentLocation)
            if (positions != null) {
                val start = positions.groupValues[1].toInt() - 1
                val end = (positions.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)?.toInt()) ?: (start + 1)
                val label = qualifiers["label"]?.firstOrNull()
                    ?: qualifiers["gene"]?.firstOrNull()
                    ?: qualifiers["product"]?.firstOrNull()
                    ?: type
                features += Feature(
                    label,
                    type,
                    start,
                    end,
                    if (reverse) Strand.REVERSE else Strand.FORWARD,
                    qualifiers["note"]?.joinToString("\n").orEmpty(),
                    qualifiers.mapValues { it.value.toList() },
                )
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
                line.startsWith("OS   ") -> metadata["ORGANISM"] = line.drop(5).trim()
                line.startsWith("CC   ") -> metadata["COMMENT"] = sequenceOf(metadata["COMMENT"], line.drop(5).trim()).filterNotNull().filter(String::isNotBlank).joinToString(" ")
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
        return Seq(name, sequence, kind, topology, features.filter { it.start < sequence.length }.map { it.copy(end = minOf(it.end, sequence.length)) }, description, metadata)
    }

    fun write(seq: Seq): String = buildString {
        val unit = if (seq.kind == SeqKind.PROTEIN) "AA" else "BP"
        val molecule = when (seq.kind) { SeqKind.DNA -> "DNA"; SeqKind.RNA -> "RNA"; SeqKind.PROTEIN -> "PROTEIN" }
        append("ID   ${seq.name}; SV 1; ${seq.topology.name.lowercase()}; $molecule; STD; UNC; ${seq.length} $unit.\n")
        append("DE   ${seq.description.ifBlank { seq.name }}\n")
        seq.metadata["ACCESSION"]?.let { append("AC   $it;\n") }
        seq.metadata["ORGANISM"]?.let { append("OS   $it\n") }
        seq.metadata["COMMENT"]?.let { append("CC   ${it.replace('\n', ' ')}\n") }
        append("FH   Key             Location/Qualifiers\n")
        append("FH\n")
        for ((name, type, start, end, strand, notes) in seq.features) {
            val span = "${start + 1}..$end"
            val location = if (strand == Strand.REVERSE) "complement($span)" else span
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
