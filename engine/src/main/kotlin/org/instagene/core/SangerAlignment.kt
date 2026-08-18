package org.instagene.core

data class AlignmentMismatch(val refPos: Int, val readPos: Int, val refBase: Char, val readBase: Char)

data class AlignedRead(
    val readName: String,
    val identity: Double,
    val mismatches: List<AlignmentMismatch>,
    val alignedLength: Int,
)

data class AlignmentSummary(val totalReads: Int, val averageIdentity: Double)

data class SangerAlignmentResult(val reads: List<AlignedRead>, val summary: AlignmentSummary)

object SangerAlignment {

    fun align(reference: Seq, reads: List<Seq>): SangerAlignmentResult {
        val ref = reference.bases.uppercase()
        val aligned = reads.map { read -> alignOne(ref, read) }
        val avgIdentity = if (aligned.isNotEmpty()) aligned.map { it.identity }.average() else 0.0
        return SangerAlignmentResult(aligned, AlignmentSummary(aligned.size, avgIdentity))
    }

    private fun alignOne(ref: String, read: Seq): AlignedRead {
        val seq = read.bases.uppercase()
        var bestScore = Int.MIN_VALUE
        var bestRefStart = 0
        var bestReadStart = 0
        var bestLen = 0
        val window = ref.length + seq.length
        for (rs in 0..ref.length) {
            for (ss in 0..seq.length) {
                var score = 0
                var len = 0
                while (rs + len < ref.length && ss + len < seq.length) {
                    if (ref[rs + len] == seq[ss + len]) score++ else score--
                    len++
                }
                if (score > bestScore) {
                    bestScore = score
                    bestRefStart = rs
                    bestReadStart = ss
                    bestLen = len
                }
            }
        }
        val mismatches = mutableListOf<AlignmentMismatch>()
        var matches = 0
        for (k in 0 until bestLen) {
            val rb = ref[bestRefStart + k]
            val qb = seq[bestReadStart + k]
            if (rb == qb) matches++ else mismatches.add(AlignmentMismatch(bestRefStart + k, bestReadStart + k, rb, qb))
        }
        val identity = if (bestLen > 0) matches.toDouble() / bestLen else 0.0
        return AlignedRead(read.name, identity, mismatches, bestLen)
    }
}
