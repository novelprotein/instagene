package org.instagene.core

import org.instagene.core.io.Fasta

enum class MultipleAlignmentAlgorithm(val toolId: String?) {
    BUILTIN(null),
    CLUSTAL_OMEGA("clustalo"),
    MAFFT("mafft"),
    MUSCLE("muscle"),
    T_COFFEE("tcoffee"),
}

data class MultipleAlignmentResult(
    val algorithm: MultipleAlignmentAlgorithm,
    val sequences: List<Seq>,
    val command: String? = null,
    val warnings: List<String> = emptyList(),
)

/** Presentation-independent consensus and conservation information for an alignment. */
data class AlignmentView(
    val consensus: String,
    /** Fraction (0.0–1.0) of non-gap residues supporting the consensus at each column. */
    val conservation: List<Double>,
    /** One-based reference coordinate at each column, or null for a reference gap. */
    val referencePositions: List<Int?>,
)

/**
 * Computes a conservative DNA/protein consensus. A tied nucleotide/protein
 * call is represented as `N`/`X`, making ambiguity visible instead of silently
 * choosing one of the input rows.
 */
fun MultipleAlignmentResult.view(): AlignmentView {
    require(sequences.isNotEmpty()) { "Alignment contains no sequences" }
    val width = sequences.maxOf { it.bases.length }
    require(sequences.all { it.bases.length == width }) { "Alignment rows have different lengths" }
    val protein = sequences.first().kind == SeqKind.PROTEIN
    val consensus = StringBuilder(width)
    val conservation = ArrayList<Double>(width)
    val positions = ArrayList<Int?>(width)
    var referencePosition = 0
    for (column in 0 until width) {
        val residues = sequences.map { it.bases[column] }.filter { it != '-' && !it.isWhitespace() }
        val counts = residues.groupingBy { it.uppercaseChar() }.eachCount()
        val best = counts.maxByOrNull { it.value }
        val tied = best != null && counts.count { it.value == best.value } > 1
        consensus.append(
            when {
                best == null -> '-'
                tied -> if (protein) 'X' else 'N'
                else -> best.key
            },
        )
        conservation += if (residues.isEmpty()) 0.0 else best!!.value.toDouble() / residues.size
        val referenceBase = sequences.first().bases[column]
        positions += if (referenceBase == '-') null else ++referencePosition
    }
    return AlignmentView(consensus.toString(), conservation, positions)
}

/** Emits valid aligned FASTA while retaining row names and gap characters. */
fun MultipleAlignmentResult.toFasta(lineWidth: Int = 80): String {
    require(lineWidth > 0) { "lineWidth must be positive" }
    return buildString {
        sequences.forEach { sequence ->
            append('>').append(sequence.name).append('\n')
            sequence.bases.chunked(lineWidth).forEach { append(it).append('\n') }
        }
    }
}

/** One interface over the bundled reference aligner and optional trusted alignment tools. */
object MultipleAlignment {
    fun align(
        sequences: List<Seq>,
        algorithm: MultipleAlignmentAlgorithm = MultipleAlignmentAlgorithm.BUILTIN,
        cancellationRequested: () -> Boolean = { false },
    ): MultipleAlignmentResult {
        require(sequences.size >= 2) { "Alignment requires at least two sequences" }
        if (algorithm == MultipleAlignmentAlgorithm.BUILTIN) {
            val aligned = Alignment.align(sequences.first(), sequences.drop(1))
            return MultipleAlignmentResult(
                algorithm,
                listOf(
                    sequences.first().copy(bases = aligned.reference.sequence),
                    *aligned.queries.mapIndexed { index, row -> sequences[index + 1].copy(bases = row.sequence) }.toTypedArray(),
                ),
            )
        }
        val tool = ExternalTools.CATALOG.first { it.id == algorithm.toolId }
        val input = sequences.first().copy(name = "alignment_input")
        val result = ExternalTools.run(
            tool,
            input,
            inputFasta = Fasta.writeAll(sequences),
            cancellationRequested = cancellationRequested,
        )
        require(result.succeeded) { result.stderr.ifBlank { "${tool.displayName} failed" } }
        val output = result.payload()
        val parsed = Fasta.parseAll(output)
        require(parsed.size == sequences.size) { "${tool.displayName} returned ${parsed.size} aligned sequence(s); expected ${sequences.size}" }
        return MultipleAlignmentResult(algorithm, parsed, result.command)
    }
}
