package org.instagene.core

enum class AlignmentMode { GLOBAL, LOCAL }

data class AlignmentParameters(
    val matchScore: Double = 1.0,
    val mismatchPenalty: Double = -0.1,
    val gapPenalty: Double = -1.5,
    val gapExtensionPenalty: Double = -0.5,
    val lineWidth: Int = 60,
    val scoring: AlignmentScoring = AlignmentScoring.CUSTOM,
    val mode: AlignmentMode = AlignmentMode.GLOBAL,
)

data class AlignedSequence(
    val name: String,
    val sequence: String,
    val direction: Strand,
    val score: Double,
    val matches: Int,
    val mismatches: Int,
    val gaps: Int,
) {
    val length: Int get() = sequence.length
}

data class AlignmentResult(
    val reference: AlignedSequence,
    val queries: List<AlignedSequence>,
    val parameters: AlignmentParameters,
) {
    fun discrepancyPositions(queryIndex: Int = 0): List<Int> {
        val query = queries.getOrNull(queryIndex) ?: return emptyList()
        return reference.sequence.indices.filter { i ->
            reference.sequence[i] != query.sequence[i] || reference.sequence[i] == '-' || query.sequence[i] == '-'
        }
    }
}

/** Deterministic pairwise alignment with true three-state affine-gap dynamic programming. */
object Alignment {
    private const val MATCH_STATE: Byte = 1
    private const val QUERY_GAP_STATE: Byte = 2
    private const val REFERENCE_GAP_STATE: Byte = 3

    fun align(reference: Seq, queries: List<Seq>, parameters: AlignmentParameters = AlignmentParameters()): AlignmentResult {
        require(queries.isNotEmpty()) { "At least one query sequence is required" }
        queries.forEach { validate(reference, it, parameters) }
        val aligned = if (queries.size <= 2) {
            queries.map { alignPair(reference, it, parameters) }
        } else {
            Parallel.map(queries) { alignPair(reference, it, parameters) }
        }
        val refLength = aligned.maxOf { it.reference.length }
        val first = aligned.firstOrNull()
        val normalizedRef = first?.let { pair ->
            AlignedSequence(reference.name, pad(pair.reference, refLength), Strand.FORWARD, pair.score, pair.matches, pair.mismatches, pair.gaps)
        } ?: AlignedSequence(reference.name, reference.bases, Strand.FORWARD, 0.0, 0, 0, 0)
        val rows = queries.mapIndexed { index, query ->
            val pair = aligned[index]
            AlignedSequence(
                query.name,
                pad(pair.query, normalizedRef.sequence.length),
                pair.direction,
                pair.score,
                pair.matches,
                pair.mismatches,
                pair.gaps,
            )
        }
        return AlignmentResult(normalizedRef, rows, parameters)
    }

    internal data class PairResult(
        val reference: String,
        val query: String,
        val direction: Strand = Strand.FORWARD,
        val score: Double,
        val matches: Int,
        val mismatches: Int,
        val gaps: Int,
    )

    internal fun alignPair(reference: Seq, query: Seq, p: AlignmentParameters): PairResult {
        validate(reference, query, p)
        val a = reference.bases.uppercase()
        val b = query.bases.uppercase()
        val rows = a.length + 1
        val cols = b.length + 1
        val match = Array(rows) { DoubleArray(cols) { Double.NEGATIVE_INFINITY } }
        val queryGap = Array(rows) { DoubleArray(cols) { Double.NEGATIVE_INFINITY } }
        val referenceGap = Array(rows) { DoubleArray(cols) { Double.NEGATIVE_INFINITY } }
        val matchTrace = Array(rows) { ByteArray(cols) }
        val queryGapTrace = Array(rows) { ByteArray(cols) }
        val referenceGapTrace = Array(rows) { ByteArray(cols) }

        match[0][0] = 0.0
        if (p.mode == AlignmentMode.LOCAL) {
            for (i in 1 until rows) {
                match[i][0] = 0.0
                queryGap[i][0] = 0.0
            }
            for (j in 1 until cols) {
                match[0][j] = 0.0
                referenceGap[0][j] = 0.0
            }
        } else {
            for (i in 1 until rows) {
                queryGap[i][0] = p.gapPenalty + (i - 1) * p.gapExtensionPenalty
                queryGapTrace[i][0] = if (i == 1) 0 else QUERY_GAP_STATE
            }
            for (j in 1 until cols) {
                referenceGap[0][j] = p.gapPenalty + (j - 1) * p.gapExtensionPenalty
                referenceGapTrace[0][j] = if (j == 1) 0 else REFERENCE_GAP_STATE
            }
        }

        var bestLocal = StateScore(0.0, 0, 0, 0)
        for (i in 1 until rows) for (j in 1 until cols) {
            val residue = AlignmentScores.score(
                reference.kind,
                a[i - 1],
                b[j - 1],
                p.scoring,
                p.matchScore,
                p.mismatchPenalty,
            )
            val diagonal = choose(
                localPrevious(match[i - 1][j - 1], MATCH_STATE, p.mode),
                localPrevious(queryGap[i - 1][j - 1], QUERY_GAP_STATE, p.mode),
                localPrevious(referenceGap[i - 1][j - 1], REFERENCE_GAP_STATE, p.mode),
            )
            val mCandidate = diagonal.value + residue
            val m = if (p.mode == AlignmentMode.LOCAL) {
                choose(StateScore(0.0, 0), StateScore(mCandidate, diagonal.state))
            } else StateScore(mCandidate, diagonal.state)
            match[i][j] = m.value
            matchTrace[i][j] = m.state

            val x = if (p.mode == AlignmentMode.LOCAL) {
                choose(
                    StateScore(0.0, 0),
                    localPrevious(queryGap[i - 1][j] + p.gapExtensionPenalty, QUERY_GAP_STATE, p.mode),
                    localPrevious(match[i - 1][j] + p.gapPenalty, MATCH_STATE, p.mode),
                )
            } else {
                choose(
                    StateScore(queryGap[i - 1][j] + p.gapExtensionPenalty, QUERY_GAP_STATE),
                    StateScore(match[i - 1][j] + p.gapPenalty, MATCH_STATE),
                )
            }
            queryGap[i][j] = x.value
            queryGapTrace[i][j] = x.state

            val y = if (p.mode == AlignmentMode.LOCAL) {
                choose(
                    StateScore(0.0, 0),
                    localPrevious(referenceGap[i][j - 1] + p.gapExtensionPenalty, REFERENCE_GAP_STATE, p.mode),
                    localPrevious(match[i][j - 1] + p.gapPenalty, MATCH_STATE, p.mode),
                )
            } else {
                choose(
                    StateScore(referenceGap[i][j - 1] + p.gapExtensionPenalty, REFERENCE_GAP_STATE),
                    StateScore(match[i][j - 1] + p.gapPenalty, MATCH_STATE),
                )
            }
            referenceGap[i][j] = y.value
            referenceGapTrace[i][j] = y.state

            if (p.mode == AlignmentMode.LOCAL) {
                val cell = choose(
                    StateScore(match[i][j], MATCH_STATE),
                    StateScore(queryGap[i][j], QUERY_GAP_STATE),
                    StateScore(referenceGap[i][j], REFERENCE_GAP_STATE),
                )
                if (cell.value > bestLocal.value) bestLocal = StateScore(cell.value, cell.state, i, j)
            }
        }

        val end = if (p.mode == AlignmentMode.LOCAL) {
            bestLocal
        } else {
            val cell = choose(
                StateScore(match[a.length][b.length], MATCH_STATE),
                StateScore(queryGap[a.length][b.length], QUERY_GAP_STATE),
                StateScore(referenceGap[a.length][b.length], REFERENCE_GAP_STATE),
            )
            StateScore(cell.value, cell.state, a.length, b.length)
        }
        val alignedReference = StringBuilder()
        val alignedQuery = StringBuilder()
        var i = end.row
        var j = end.column
        var state = end.state
        while (state != 0.toByte() && (i > 0 || j > 0)) {
            when (state) {
                MATCH_STATE -> {
                    val previous = matchTrace[i][j]
                    alignedReference.append(a[--i])
                    alignedQuery.append(b[--j])
                    state = previous
                }
                QUERY_GAP_STATE -> {
                    val previous = queryGapTrace[i][j]
                    alignedReference.append(a[--i])
                    alignedQuery.append('-')
                    state = previous
                }
                REFERENCE_GAP_STATE -> {
                    val previous = referenceGapTrace[i][j]
                    alignedReference.append('-')
                    alignedQuery.append(b[--j])
                    state = previous
                }
                else -> break
            }
        }
        val r = alignedReference.reverse().toString()
        val q = alignedQuery.reverse().toString()
        val matches = r.indices.count { r[it] == q[it] && r[it] != '-' }
        val gaps = r.indices.count { r[it] == '-' || q[it] == '-' }
        return PairResult(
            reference = r,
            query = q,
            score = end.value,
            matches = matches,
            mismatches = r.length - matches - gaps,
            gaps = gaps,
        )
    }

    private data class StateScore(
        val value: Double,
        val state: Byte,
        val row: Int = 0,
        val column: Int = 0,
    )

    /** Keeps ties stable: the first candidate wins, giving diagonal preference. */
    private fun choose(vararg candidates: StateScore): StateScore {
        var best = candidates.first()
        for (candidate in candidates.drop(1)) if (candidate.value > best.value) best = candidate
        return best
    }

    private fun localPrevious(value: Double, state: Byte, mode: AlignmentMode): StateScore =
        StateScore(value, if (mode == AlignmentMode.LOCAL && value <= 0.0) 0 else state)

    private fun validate(reference: Seq, query: Seq, p: AlignmentParameters) {
        require(AlignmentScores.compatible(reference, query)) { "Cannot align nucleotide and protein sequences" }
        when (p.scoring) {
            AlignmentScoring.BLOSUM62, AlignmentScoring.PAM250 ->
                require(reference.kind == SeqKind.PROTEIN) { "${p.scoring} scoring requires protein sequences" }
            AlignmentScoring.NUCLEOTIDE ->
                require(reference.kind != SeqKind.PROTEIN) { "Nucleotide scoring requires DNA or RNA sequences" }
            else -> Unit
        }
    }

    private fun pad(value: String, length: Int): String = value.padEnd(length, '-')
}
