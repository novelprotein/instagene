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
