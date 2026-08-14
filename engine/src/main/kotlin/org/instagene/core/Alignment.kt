package org.instagene.core

data class AlignmentParameters(
    val matchScore: Double = 1.0,
    val mismatchPenalty: Double = -0.1,
    val gapPenalty: Double = -1.5,
    val gapExtensionPenalty: Double = -0.5,
    val lineWidth: Int = 60,
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

    fun nextDiscrepancy(after: Int = -1, queryIndex: Int = 0): Int? =
        discrepancyPositions(queryIndex).firstOrNull { it > after }
}

/** Deterministic Needleman-Wunsch alignment with affine gap penalties. */
object Alignment {
    fun align(reference: Seq, queries: List<Seq>, parameters: AlignmentParameters = AlignmentParameters()): AlignmentResult {
        require(queries.isNotEmpty()) { "At least one query sequence is required" }
        val aligned = queries.map { alignPair(reference, it, parameters) }
        val refLength = aligned.maxOf { it.reference.length }
        val ref = aligned.firstOrNull()?.let { pair ->
            AlignedSequence(reference.name, pair.reference, Strand.FORWARD, pair.score, pair.matches, pair.mismatches, pair.gaps)
        } ?: AlignedSequence(reference.name, reference.bases, Strand.FORWARD, 0.0, 0, 0, 0)
        val normalizedRef = if (ref.sequence.length == refLength) ref else ref.copy(sequence = pad(ref.sequence, refLength))
        // Re-run metadata mapping without relying on the reference path from another query.
        val rows = queries.mapIndexed { index, query ->
            val pair = aligned[index]
            AlignedSequence(query.name, pad(pair.query, normalizedRef.sequence.length), pair.direction, pair.score, pair.matches, pair.mismatches, pair.gaps)
        }
        return AlignmentResult(normalizedRef, rows, parameters)
    }

    private data class PairResult(
        val reference: String,
        val query: String,
        val direction: Strand = Strand.FORWARD,
        val score: Double,
        val matches: Int,
        val mismatches: Int,
        val gaps: Int,
    )

    private fun alignPair(reference: Seq, query: Seq, p: AlignmentParameters): PairResult {
        val a = reference.bases.uppercase()
        val b = query.bases.uppercase()
        val rows = a.length + 1
        val cols = b.length + 1
        val score = Array(rows) { DoubleArray(cols) }
        val trace = Array(rows) { CharArray(cols) }
        for (i in 1 until rows) {
            score[i][0] = p.gapPenalty + (i - 1) * p.gapExtensionPenalty
            trace[i][0] = 'U'
        }
        for (j in 1 until cols) {
            score[0][j] = p.gapPenalty + (j - 1) * p.gapExtensionPenalty
            trace[0][j] = 'L'
        }
        for (i in 1 until rows) for (j in 1 until cols) {
            val diagonal = score[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) p.matchScore else p.mismatchPenalty
            val up = score[i - 1][j] + if (trace[i - 1][j] == 'U') p.gapExtensionPenalty else p.gapPenalty
            val left = score[i][j - 1] + if (trace[i][j - 1] == 'L') p.gapExtensionPenalty else p.gapPenalty
            val best = maxOf(diagonal, up, left)
            score[i][j] = best
            trace[i][j] = when (best) {
                diagonal -> 'D'
                up -> 'U'
                else -> 'L'
            }
        }
        val ra = StringBuilder()
        val qb = StringBuilder()
        var i = a.length
        var j = b.length
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && trace[i][j] == 'D' -> { ra.append(a[--i]); qb.append(b[--j]) }
                i > 0 && (j == 0 || trace[i][j] == 'U') -> { ra.append(a[--i]); qb.append('-') }
                else -> { ra.append('-'); qb.append(b[--j]) }
            }
        }
        val r = ra.reverse().toString()
        val q = qb.reverse().toString()
        val matches = r.indices.count { r[it] == q[it] && r[it] != '-' }
        val gaps = r.indices.count { r[it] == '-' || q[it] == '-' }
        return PairResult(
            reference = r,
            query = q,
            score = score[a.length][b.length],
            matches = matches,
            mismatches = r.length - matches - gaps,
            gaps = gaps,
        )
    }

    private fun pad(value: String, length: Int): String = value.padEnd(length, '-')
}
