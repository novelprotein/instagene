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
            val rows = progressiveProfileAlignment(sequences, cancellationRequested)
            return MultipleAlignmentResult(
                algorithm,
                sequences.mapIndexed { index, sequence -> sequence.copy(bases = rows[index]) },
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

    private fun progressiveProfileAlignment(sequences: List<Seq>, cancellationRequested: () -> Boolean): List<String> {
        val parameters = AlignmentParameters(scoring = AlignmentScoring.AUTO)
        sequences.drop(1).forEach { sequence ->
            require(AlignmentScores.compatible(sequences.first(), sequence)) {
                "Cannot align nucleotide and protein sequences"
            }
        }
        var profile = sequences.take(1).map { it.bases.uppercase() }
        for (sequence in sequences.drop(1)) {
            if (cancellationRequested()) throw java.util.concurrent.CancellationException("Alignment cancelled")
            profile = alignProfile(profile, sequence.bases.uppercase(), sequence.kind, parameters)
        }
        return profile
    }

    /** Aligns one new sequence to every row in the current profile. */
    private fun alignProfile(
        profile: List<String>,
        query: String,
        kind: SeqKind,
        parameters: AlignmentParameters,
    ): List<String> {
        val profileWidth = profile.first().length
        require(profile.all { it.length == profileWidth }) { "Profile rows have different lengths" }
        val rows = profileWidth + 1
        val cols = query.length + 1
        val match = Array(rows) { DoubleArray(cols) { Double.NEGATIVE_INFINITY } }
        val profileGap = Array(rows) { DoubleArray(cols) { Double.NEGATIVE_INFINITY } }
        val queryGap = Array(rows) { DoubleArray(cols) { Double.NEGATIVE_INFINITY } }
        val matchTrace = Array(rows) { ByteArray(cols) }
        val profileGapTrace = Array(rows) { ByteArray(cols) }
        val queryGapTrace = Array(rows) { ByteArray(cols) }
        match[0][0] = 0.0
        for (i in 1 until rows) {
            queryGap[i][0] = parameters.gapPenalty + (i - 1) * parameters.gapExtensionPenalty
            queryGapTrace[i][0] = if (i == 1) 0 else QUERY_GAP
        }
        for (j in 1 until cols) {
            profileGap[0][j] = parameters.gapPenalty + (j - 1) * parameters.gapExtensionPenalty
            profileGapTrace[0][j] = if (j == 1) 0 else PROFILE_GAP
        }
        for (i in 1 until rows) for (j in 1 until cols) {
            val diagonal = choose(
                Candidate(match[i - 1][j - 1], MATCH),
                Candidate(queryGap[i - 1][j - 1], QUERY_GAP),
                Candidate(profileGap[i - 1][j - 1], PROFILE_GAP),
            )
            val columnScore = profileColumnScore(profile, i - 1, query[j - 1], kind, parameters)
            match[i][j] = diagonal.value + columnScore
            matchTrace[i][j] = diagonal.state
            val x = choose(
                Candidate(queryGap[i - 1][j] + parameters.gapExtensionPenalty, QUERY_GAP),
                Candidate(match[i - 1][j] + parameters.gapPenalty, MATCH),
            )
            queryGap[i][j] = x.value
            queryGapTrace[i][j] = x.state
            val y = choose(
                Candidate(profileGap[i][j - 1] + parameters.gapExtensionPenalty, PROFILE_GAP),
                Candidate(match[i][j - 1] + parameters.gapPenalty, MATCH),
            )
            profileGap[i][j] = y.value
            profileGapTrace[i][j] = y.state
        }
        val end = choose(
            Candidate(match[profileWidth][query.length], MATCH),
            Candidate(queryGap[profileWidth][query.length], QUERY_GAP),
            Candidate(profileGap[profileWidth][query.length], PROFILE_GAP),
        )
        val operations = ArrayList<Byte>()
        var i = profileWidth
        var j = query.length
        var state = end.state
        while (i > 0 || j > 0) {
            when (state) {
                MATCH -> {
                    operations += MATCH
                    state = matchTrace[i][j]
                    i--
                    j--
                }
                QUERY_GAP -> {
                    operations += QUERY_GAP
                    state = queryGapTrace[i][j]
                    i--
                }
                PROFILE_GAP -> {
                    operations += PROFILE_GAP
                    state = profileGapTrace[i][j]
                    j--
                }
                else -> error("Profile alignment ended before consuming both rows")
            }
        }
        operations.reverse()
        val alignedProfile = profile.map { StringBuilder() }
        val alignedQuery = StringBuilder()
        var profileColumn = 0
        var queryColumn = 0
        for (operation in operations) {
            when (operation) {
                MATCH -> {
                    alignedProfile.forEachIndexed { index, row -> row.append(profile[index][profileColumn]) }
                    alignedQuery.append(query[queryColumn++])
                    profileColumn++
                }
                QUERY_GAP -> {
                    alignedProfile.forEachIndexed { index, row -> row.append(profile[index][profileColumn]) }
                    alignedQuery.append('-')
                    profileColumn++
                }
                PROFILE_GAP -> {
                    alignedProfile.forEach { it.append('-') }
                    alignedQuery.append(query[queryColumn++])
                }
            }
        }
        return alignedProfile.map { it.toString() } + alignedQuery.toString()
    }

    private fun profileColumnScore(
        profile: List<String>,
        column: Int,
        residue: Char,
        kind: SeqKind,
        parameters: AlignmentParameters,
    ): Double {
        val residues = profile.map { it[column] }.filter { it != '-' }
        if (residues.isEmpty()) return 0.0
        return residues.map {
            AlignmentScores.score(kind, it, residue, parameters.scoring, parameters.matchScore, parameters.mismatchPenalty)
        }.average()
    }

    private data class Candidate(val value: Double, val state: Byte)

    private fun choose(vararg candidates: Candidate): Candidate {
        var best = candidates.first()
        for (candidate in candidates.drop(1)) if (candidate.value > best.value) best = candidate
        return best
    }

    private const val MATCH: Byte = 1
    private const val QUERY_GAP: Byte = 2
    private const val PROFILE_GAP: Byte = 3
}
