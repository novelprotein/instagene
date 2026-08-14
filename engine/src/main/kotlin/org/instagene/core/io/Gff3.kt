package org.instagene.core.io

import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.Strand

/** Minimal, standards-shaped GFF3 annotation import/export for an existing sequence. */
object Gff3 {
    fun looksLikeGff3(text: String): Boolean = text.lineSequence().take(3).any { it == "##gff-version 3" || it.contains("\t") && it.split('\t').size >= 8 }

    fun parseAnnotations(text: String, sequence: Seq): Seq = sequence.copy(
        features = sequence.features + text.lineSequence().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t')
            if (fields.size < 8) return@mapNotNull null
            val start = fields[3].toIntOrNull()?.minus(1) ?: return@mapNotNull null
            val end = fields[4].toIntOrNull() ?: return@mapNotNull null
            val strand = if (fields[6] == "-") Strand.REVERSE else Strand.FORWARD
            val qualifiers = parseAttributes(fields.getOrNull(8).orEmpty())
            val name = qualifiers["Name"] ?: qualifiers["gene"] ?: qualifiers["ID"] ?: fields[2]
            Feature(name, fields[2], start, end, strand, qualifiers = qualifiers.mapValues { listOf(it.value) })
        }.toList().sortedBy { it.start }
    )

    fun parse(text: String, defaultName: String = "sequence"): Seq {
        val fasta = text.substringAfter("##FASTA", "")
        val sequence = if (fasta.isBlank()) Seq(name = defaultName) else Fasta.parse(fasta, defaultName)
        return parseAnnotations(text.substringBefore("##FASTA"), sequence)
    }

    fun write(seq: Seq): String = buildString {
        append("##gff-version 3\n")
        append("##sequence-region ${seq.name} 1 ${seq.length}\n")
        for (feature in seq.features.filter { it.visible }.sortedBy { it.start }) {
            for ((part, segment) in feature.locationSegments.withIndex()) {
                val attributes = LinkedHashMap<String, String>()
                attributes["ID"] = if (part == 0) feature.name else "${feature.name}_part${part + 1}"
                if (feature.name.isNotBlank()) attributes["Name"] = feature.name
                feature.qualifiers.forEach { (key, values) -> if (key !in attributes && values.isNotEmpty()) attributes[key] = values.first() }
                append(seq.name).append('\t')
                    .append("InstaGene\t").append(feature.type).append('\t')
                    .append(segment.start + 1).append('\t').append(segment.end).append("\t.\t")
                    .append(feature.strand.symbol).append("\t.\t")
                    .append(attributes.entries.joinToString(";") { "${it.key}=${escape(it.value)}" }).append('\n')
            }
        }
        append("##FASTA\n>").append(seq.name).append('\n')
        seq.bases.chunked(60).forEach { append(it).append('\n') }
    }

    private fun parseAttributes(raw: String): Map<String, String> = raw.split(';')
        .mapNotNull { item ->
            val index = item.indexOf('=')
            if (index <= 0) null else item.substring(0, index).trim() to unescape(item.substring(index + 1).trim())
        }.toMap()

    private fun escape(value: String): String = value.replace("%", "%25").replace(";", "%3B").replace("=", "%3D")
    private fun unescape(value: String): String = value.replace("%3B", ";", true).replace("%3D", "=", true).replace("%25", "%", true)
}
