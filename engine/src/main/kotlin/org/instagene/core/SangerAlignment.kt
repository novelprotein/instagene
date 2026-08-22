package org.instagene.core

data class AlignmentMismatch(
    val refPos: Int,
    val readPos: Int,
    val refBase: Char,
    val readBase: Char,
    val kind: MismatchKind = MismatchKind.SUBSTITUTION,
)

enum class MismatchKind { SUBSTITUTION, LOW_QUALITY }

data class SangerOptions(
    val minQuality: Int = 20,
    val trimQuality: Int = 20,
    val minIdentity: Double = 0.90,
    val minAlignedLength: Int = 20,
)

data class SangerRead(
    val name: String,
    val bases: String,
    val qualities: List<Int> = emptyList(),
) {
    fun trimmed(minQuality: Int): SangerRead {
        if (qualities.size != bases.length || bases.isEmpty()) return this
        val start = qualities.indexOfFirst { it >= minQuality }
        if (start < 0) return copy(bases = "", qualities = emptyList())
        val end = qualities.indexOfLast { it >= minQuality } + 1
        return copy(bases = bases.substring(start, end), qualities = qualities.subList(start, end))
    }
}

enum class ReadConfidence { HIGH, REVIEW, LOW }

data class AlignedRead(
    val readName: String,
    val identity: Double,
    val mismatches: List<AlignmentMismatch>,
    val alignedLength: Int,
    val referenceStart: Int = 0,
    val readStart: Int = 0,
    val lowQualityBases: Int = 0,
    val trimmedBases: Int = 0,
    val minIdentityThreshold: Double = 0.90,
    val minAlignedLengthThreshold: Int = 20,
) {
    fun confidence(minIdentity: Double = minIdentityThreshold, minAlignedLength: Int = minAlignedLengthThreshold): ReadConfidence = when {
        alignedLength < minAlignedLength -> ReadConfidence.LOW
        identity >= minIdentity -> ReadConfidence.HIGH
        else -> ReadConfidence.REVIEW
    }
}

data class AlignmentSummary(val totalReads: Int, val averageIdentity: Double)

data class SangerAlignmentResult(val reads: List<AlignedRead>, val summary: AlignmentSummary)

object SangerAlignment {

    fun align(reference: Seq, reads: List<Seq>): SangerAlignmentResult {
        return align(reference, reads.map { SangerRead(it.name, it.bases) }, SangerOptions())
    }

    fun align(reference: Seq, reads: List<SangerRead>, options: SangerOptions = SangerOptions()): SangerAlignmentResult {
        val ref = reference.bases.uppercase()
        val aligned = if (reads.size <= 4) {
            reads.map { read ->
                val trimmed = read.trimmed(options.trimQuality)
                alignOne(ref, trimmed, options, read.bases.length - trimmed.bases.length)
            }
        } else {
            Parallel.map(reads) { read ->
                val trimmed = read.trimmed(options.trimQuality)
                alignOne(ref, trimmed, options, read.bases.length - trimmed.bases.length)
            }
        }
        val avgIdentity = if (aligned.isNotEmpty()) aligned.map { it.identity }.average() else 0.0
        return SangerAlignmentResult(aligned, AlignmentSummary(aligned.size, avgIdentity))
    }

    fun alignChromatograms(reference: Seq, reads: List<ChromatogramRecord>, options: SangerOptions = SangerOptions()): SangerAlignmentResult =
        align(reference, reads.map { SangerRead(it.name, it.bases, it.qualities) }, options)

    private fun alignOne(ref: String, read: SangerRead, options: SangerOptions, trimmedBases: Int): AlignedRead {
        val seq = read.bases.uppercase()
        var bestScore = Int.MIN_VALUE
        var bestRefStart = 0
        var bestReadStart = 0
        var bestLen = 0
        for (rs in 0..ref.length) {
            for (ss in 0..seq.length) {
                var score = 0
                var len = 0
                val maxLen = minOf(ref.length - rs, seq.length - ss)
                while (len < maxLen) {
                    if (ref[rs + len] == seq[ss + len]) score++ else score--
                    len++
                    // Prune: even if every remaining base matches, we can't beat bestScore.
                    if (score + (maxLen - len) <= bestScore) break
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
            if (rb == qb) matches++ else {
                val quality = read.qualities.getOrNull(bestReadStart + k)
                mismatches.add(AlignmentMismatch(
                    bestRefStart + k, bestReadStart + k, rb, qb,
                    if (quality != null && quality < options.minQuality) MismatchKind.LOW_QUALITY else MismatchKind.SUBSTITUTION,
                ))
            }
        }
        val identity = if (bestLen > 0) matches.toDouble() / bestLen else 0.0
        val lowQuality = read.qualities.count { it < options.minQuality }
        return AlignedRead(
            read.name, identity, mismatches, bestLen, bestRefStart, bestReadStart,
            lowQuality, trimmedBases, options.minIdentity, options.minAlignedLength,
        )
    }
}
