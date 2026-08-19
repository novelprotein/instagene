package org.instagene.core

import kotlin.math.exp

enum class ScoringMode { RULESET3_FULL, RULESET3_SIMPLE }

data class GuideRNA(
    val sequence: String,
    val pamPosition: Int,
    val onTargetScore: Double,
    val offTargetScore: Double,
    val gcContent: Double,
    val scoringMode: ScoringMode = ScoringMode.RULESET3_SIMPLE,
)

data class CrisprDesignResult(val guides: List<GuideRNA>)

object CrisprDesign {

    private const val GUIDE_LEN = 20

    // Full Ruleset 3 position-specific nucleotide weights (Doench 2016, Table S3)
    // Rows: positions 1-20 (1=PAM-proximal, 20=distal), Columns: A,C,G,T
    private val FULL_WEIGHTS = arrayOf(
        doubleArrayOf( 0.037, -0.329,  0.198, -0.069),  // pos 1
        doubleArrayOf(-0.102, -0.275,  0.075,  0.043),  // pos 2
        doubleArrayOf(-0.047, -0.149,  0.054, -0.017),  // pos 3
        doubleArrayOf(-0.032,  0.064, -0.037,  0.028),  // pos 4
        doubleArrayOf(-0.033,  0.044,  0.027, -0.060),  // pos 5
        doubleArrayOf(-0.031, -0.015,  0.051, -0.014),  // pos 6
        doubleArrayOf( 0.049, -0.088,  0.039, -0.012),  // pos 7
        doubleArrayOf(-0.033,  0.098, -0.064, -0.010),  // pos 8
        doubleArrayOf( 0.015,  0.056, -0.038, -0.034),  // pos 9
        doubleArrayOf( 0.012,  0.032, -0.043, -0.009),  // pos 10
        doubleArrayOf( 0.048, -0.052, -0.018,  0.025),  // pos 11
        doubleArrayOf( 0.031,  0.028, -0.059, -0.003),  // pos 12
        doubleArrayOf(-0.012,  0.042,  0.028, -0.058),  // pos 13
        doubleArrayOf( 0.033, -0.028,  0.017, -0.016),  // pos 14
        doubleArrayOf(-0.012,  0.015, -0.040,  0.037),  // pos 15
        doubleArrayOf(-0.028, -0.013,  0.057, -0.018),  // pos 16
        doubleArrayOf( 0.043, -0.039, -0.022,  0.016),  // pos 17
        doubleArrayOf( 0.014,  0.028, -0.015, -0.026),  // pos 18
        doubleArrayOf(-0.016, -0.025,  0.040, -0.002),  // pos 19
        doubleArrayOf(-0.003,  0.029, -0.017, -0.010),  // pos 20
    )

    // Simplified: top-impact positions only (1-3 proximal, 16-20 distal)
    private val SIMPLE_WEIGHTS = arrayOf(
        doubleArrayOf( 0.037, -0.329,  0.198, -0.069),  // pos 1
        doubleArrayOf(-0.102, -0.275,  0.075,  0.043),  // pos 2
        doubleArrayOf(-0.047, -0.149,  0.054, -0.017),  // pos 3
        doubleArrayOf(-0.028, -0.013,  0.057, -0.018),  // pos 16
        doubleArrayOf( 0.043, -0.039, -0.022,  0.016),  // pos 17
        doubleArrayOf( 0.014,  0.028, -0.015, -0.026),  // pos 18
        doubleArrayOf(-0.016, -0.025,  0.040, -0.002),  // pos 19
        doubleArrayOf(-0.003,  0.029, -0.017, -0.010),  // pos 20
    )

    private val NUCLEOTIDE_INDEX = mapOf('A' to 0, 'C' to 1, 'G' to 2, 'T' to 3)
    private const val FULL_INTERCEPT = 0.588
    private const val SIMPLE_INTERCEPT = 0.442

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun positionWeights(seq: String, weights: Array<DoubleArray>, intercept: Double): Double {
        var sum = intercept
        for (i in weights.indices) {
            val nucIdx = NUCLEOTIDE_INDEX[seq[i]] ?: continue
            sum += weights[i][nucIdx]
        }
        return sum
    }

    private fun gcPenalty(gc: Double): Double {
        val deviation = gc - 0.5
        return -4.0 * deviation * deviation
    }

    private fun polyTPenalty(seq: String): Double {
        var maxRun = 0; var run = 0
        for (c in seq) {
            if (c == 'T') { run++; if (run > maxRun) maxRun = run }
            else run = 0
        }
        return if (maxRun >= 4) -0.3 * (maxRun - 3) else 0.0
    }

    private fun onTargetScore(seq: String, mode: ScoringMode): Double {
        val (weights, intercept) = when (mode) {
            ScoringMode.RULESET3_FULL -> FULL_WEIGHTS to FULL_INTERCEPT
            ScoringMode.RULESET3_SIMPLE -> SIMPLE_WEIGHTS to SIMPLE_INTERCEPT
        }
        val baseScore = positionWeights(seq, weights, intercept)
        val gc = seq.count { it == 'G' || it == 'C' } / GUIDE_LEN.toDouble()
        val gcAdj = gcPenalty(gc) * 0.15
        val ttPenalty = polyTPenalty(seq)
        return sigmoid(baseScore + gcAdj + ttPenalty).coerceIn(0.01, 0.99)
    }

    private fun offTargetScore(seq: String, target: String): Double {
        val seed = seq.takeLast(12)
        val targetSeed = target.takeLast(12)
        var mismatches = 0
        var penaltySum = 0.0
        for (i in seed.indices) {
            if (seed[i] != targetSeed[i]) {
                mismatches++
                val positionWeight = 1.0 + i * 0.15
                penaltySum += positionWeight
            }
        }
        return when (mismatches) {
            0 -> 0.0
            1 -> sigmoid(-1.5 + penaltySum * 0.3)
            else -> sigmoid(-2.0 + penaltySum * 0.2).coerceAtMost(0.5)
        }
    }

    fun design(target: Seq, maxGuides: Int = 10, scoringMode: ScoringMode = ScoringMode.RULESET3_SIMPLE): CrisprDesignResult {
        val seq = target.bases.uppercase().replace('U', 'T')
        val guides = mutableListOf<GuideRNA>()
        for (i in GUIDE_LEN..seq.length - 3) {
            if (seq[i] == 'N' && seq[i + 1] == 'G' && seq[i + 2] == 'G') {
                val start = i - GUIDE_LEN
                val grna = seq.substring(start, i)
                if (grna.all { it in "ACGT" }) {
                    val gc = grna.count { it == 'G' || it == 'C' } / GUIDE_LEN.toDouble()
                    val onTarget = onTargetScore(grna, scoringMode)
                    val offTarget = offTargetScore(grna, seq)
                    guides.add(GuideRNA(grna, i, onTarget, offTarget, gc, scoringMode))
                }
            }
        }
        val sorted = guides.sortedByDescending { it.onTargetScore }.take(maxGuides)
        return CrisprDesignResult(sorted)
    }
}
