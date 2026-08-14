package org.instagene.core

data class FeatureDefinition(
    val name: String,
    val pattern: String,
    val type: String = "misc_feature",
    val strand: Strand = Strand.FORWARD,
    val color: String? = null,
    val uppercaseOnly: Boolean = false,
)

/** Pattern-backed automatic annotation, including IUPAC and variable-length wildcards. */
object FeatureLibrary {
    fun annotate(seq: Seq, definitions: Collection<FeatureDefinition>, includeExisting: Boolean = true): Seq {
        val additions = definitions.flatMap { definition ->
            find(seq, definition).map { (start, end) ->
                Feature(definition.name, definition.type, start, end, definition.strand, color = definition.color)
            }
        }
        val features = if (includeExisting) seq.features + additions else additions
        return seq.copy(features = features.distinctBy { listOf(it.name, it.type, it.start, it.end, it.strand) }.sortedBy { it.start })
    }

    fun find(seq: Seq, definition: FeatureDefinition): List<Pair<Int, Int>> {
        if (definition.pattern.isBlank()) return emptyList()
        val regex = Regex(patternRegex(definition.pattern), setOf(RegexOption.IGNORE_CASE))
        val out = ArrayList<Pair<Int, Int>>()
        val source = if (definition.strand == Strand.FORWARD) seq.bases else seq.reverseComplement().bases
        regex.findAll(source).forEach { match ->
            val start = if (definition.strand == Strand.FORWARD) match.range.first else seq.length - match.range.last - 1
            val end = if (definition.strand == Strand.FORWARD) match.range.last + 1 else seq.length - match.range.first
            if (!definition.uppercaseOnly || source.substring(match.range).all { it.isUpperCase() }) out += start to end
        }
        return out
    }

    private fun patternRegex(pattern: String): String = buildString {
        pattern.forEach { code ->
            when (code.uppercaseChar()) {
                '#', '+' -> append("[ACGT]*")
                else -> {
                    val bases = Alphabet.expansion(code)
                    if (bases == null) append(Regex.escape(code.toString()))
                    else append('[').append(bases).append(']')
                }
            }
        }
    }
}
