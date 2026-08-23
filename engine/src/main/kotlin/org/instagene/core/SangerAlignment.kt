package org.instagene.core

data class AlignmentMismatch(
    val refPos: Int,
    val readPos: Int,
    val refBase: Char,
    val readBase: Char,
    val kind: MismatchKind = MismatchKind.SUBSTITUTION,
)

enum class MismatchKind { SUBSTITUTION, LOW_QUALITY, INSERTION, DELETION }

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
    /** Offset into the source chromatogram after quality trimming. */
    val sourceOffset: Int = 0,
) {
    fun trimmed(minQuality: Int): SangerRead {
        if (qualities.size != bases.length || bases.isEmpty()) return this
        val start = qualities.indexOfFirst { it >= minQuality }
        if (start < 0) return copy(bases = "", qualities = emptyList())
        val end = qualities.indexOfLast { it >= minQuality } + 1
        return copy(bases = bases.substring(start, end), qualities = qualities.subList(start, end), sourceOffset = sourceOffset + start)
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
    /** Number of reference bases consumed by the local alignment (excludes read insertions). */
    val referenceLength: Int = alignedLength,
) {
    val insertionCount: Int get() = mismatches.count { it.kind == MismatchKind.INSERTION }
    val deletionCount: Int get() = mismatches.count { it.kind == MismatchKind.DELETION }
    fun confidence(minIdentity: Double = minIdentityThreshold, minAlignedLength: Int = minAlignedLengthThreshold): ReadConfidence = when {
        alignedLength < minAlignedLength -> ReadConfidence.LOW
        identity >= minIdentity -> ReadConfidence.HIGH
        else -> ReadConfidence.REVIEW
    }
}

data class AlignmentSummary(
    val totalReads: Int,
    val averageIdentity: Double,
    val uncoveredReferenceBases: Int = 0,
)

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
        val covered = aligned.flatMap { it.referenceStart until (it.referenceStart + it.referenceLength) }.toSet()
        return SangerAlignmentResult(aligned, AlignmentSummary(aligned.size, avgIdentity, (0 until ref.length).count { it !in covered }))
    }

    fun alignChromatograms(reference: Seq, reads: List<ChromatogramRecord>, options: SangerOptions = SangerOptions()): SangerAlignmentResult =
        align(reference, reads.map { SangerRead(it.name, it.bases, it.qualities) }, options)

    private fun alignOne(ref: String, read: SangerRead, options: SangerOptions, trimmedBases: Int): AlignedRead {
        val seq = read.bases.uppercase()
        if (ref.isEmpty() || seq.isEmpty()) return AlignedRead(
            read.name, 0.0, emptyList(), 0,
            lowQualityBases = read.qualities.count { it < options.minQuality }, trimmedBases = trimmedBases,
            minIdentityThreshold = options.minIdentity, minAlignedLengthThreshold = options.minAlignedLength,
        )
        val width = seq.length + 1
        val directions = ByteArray((ref.length + 1) * width)
        var previous = IntArray(width)
        var bestScore = 0
        var bestRefEnd = 0
        var bestReadEnd = 0
        for (refIndex in 1..ref.length) {
            val current = IntArray(width)
            for (readIndex in 1..seq.length) {
                val diagonal = previous[readIndex - 1] + if (ref[refIndex - 1] == seq[readIndex - 1]) 2 else -1
                val deletion = previous[readIndex] - 2
                val insertion = current[readIndex - 1] - 2
                val score = maxOf(0, diagonal, deletion, insertion)
                current[readIndex] = score
                directions[refIndex * width + readIndex] = when (score) {
                    0 -> 0
                    diagonal -> 1
                    deletion -> 2
                    else -> 3
                }
                if (score > bestScore) {
                    bestScore = score
                    bestRefEnd = refIndex
                    bestReadEnd = readIndex
                }
            }
            previous = current
        }
        val mismatches = mutableListOf<AlignmentMismatch>()
        var matches = 0
        var columns = 0
        var referenceLength = 0
        var refIndex = bestRefEnd
        var readIndex = bestReadEnd
        while (refIndex > 0 && readIndex > 0) {
            when (directions[refIndex * width + readIndex].toInt()) {
                0 -> break
                1 -> {
                    val refBase = ref[refIndex - 1]
                    val readBase = seq[readIndex - 1]
                    columns++
                    referenceLength++
                    if (refBase == readBase) matches++ else {
                        val quality = read.qualities.getOrNull(readIndex - 1)
                        mismatches += AlignmentMismatch(
                            refIndex - 1, read.sourceOffset + readIndex - 1, refBase, readBase,
                            if (quality != null && quality < options.minQuality) MismatchKind.LOW_QUALITY else MismatchKind.SUBSTITUTION,
                        )
                    }
                    refIndex--
                    readIndex--
                }
                2 -> {
                    columns++
                    referenceLength++
                    mismatches += AlignmentMismatch(refIndex - 1, read.sourceOffset + readIndex, ref[refIndex - 1], '-', MismatchKind.DELETION)
                    refIndex--
                }
                3 -> {
                    columns++
                    mismatches += AlignmentMismatch(refIndex, read.sourceOffset + readIndex - 1, '-', seq[readIndex - 1], MismatchKind.INSERTION)
                    readIndex--
                }
            }
        }
        mismatches.reverse()
        val identity = if (columns > 0) matches.toDouble() / columns else 0.0
        val lowQuality = read.qualities.count { it < options.minQuality }
        return AlignedRead(
            read.name, identity, mismatches, columns, refIndex, read.sourceOffset + readIndex,
            lowQuality, trimmedBases, options.minIdentity, options.minAlignedLength, referenceLength,
        )
    }
}
