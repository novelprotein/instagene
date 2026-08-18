package org.instagene.core

enum class StructureAssessment { NO_RISK, LOW_RISK, MEDIUM_RISK, HIGH_RISK }

data class ThermodynamicResult(val deltaG: Double, val tm: Double)

data class DimerResult(val deltaG: Double, val length: Int)

data class StructureReport(val assessment: StructureAssessment, val details: String)

object PrimerThermodynamics {

    private val NN_DG = mapOf(
        "AA" to -1.0, "TT" to -1.0,
        "AT" to -0.88, "TA" to -0.58,
        "CA" to -1.45, "TG" to -1.45,
        "GT" to -1.44, "AC" to -1.44,
        "CT" to -1.28, "AG" to -1.28,
        "GA" to -1.3, "TC" to -1.3,
        "CG" to -2.17, "GC" to -2.24,
        "GG" to -1.84, "CC" to -1.84,
    )

    private const val R = 1.987
    private const val CONC = 250e-9
    private const val DH_SCALE = 1000.0

    private fun nearestNeighborDg(seq: String): Double {
        val upper = seq.uppercase().replace('U', 'T')
        var dg = 0.0
        for (i in 0 until upper.length - 1) {
            val pair = upper.substring(i, i + 2)
            dg += NN_DG[pair] ?: -1.5
        }
        dg += 0.1 // initiation
        return dg
    }

    private fun calcTm(dg: Double, len: Int): Double {
        val dh = dg * DH_SCALE
        val ds = dh / 373.15
        val tm = dh / (ds + R * Math.log(CONC * len)) - 273.15
        return tm.coerceIn(0.0, 120.0)
    }

    fun thermodynamicResult(seq: String): ThermodynamicResult {
        val dg = nearestNeighborDg(seq)
        val tm = calcTm(dg, seq.length)
        return ThermodynamicResult(dg, tm)
    }

    fun selfDimer(seq: String): DimerResult {
        val upper = seq.uppercase().replace('U', 'T')
        var bestDg = 0.0
        var bestLen = 0
        val rc = Alphabet.reverseComplement(upper)
        for (offset in 0 until upper.length) {
            val overlap = upper.length - offset
            var matchLen = 0
            for (j in 0 until overlap) {
                val pair = "${upper[j]}${rc[j + offset]}"
                if (pair in NN_DG) matchLen++ else break
            }
            if (matchLen > 0) {
                var dimerDg = 0.0
                for (j in 0 until matchLen) {
                    val pair = "${upper[j]}${rc[j + offset]}"
                    dimerDg += NN_DG[pair] ?: -1.5
                }
                if (dimerDg < bestDg) {
                    bestDg = dimerDg
                    bestLen = matchLen
                }
            }
        }
        return DimerResult(bestDg, bestLen)
    }

    fun heteroDimer(seq1: String, seq2: String): DimerResult {
        val s1 = seq1.uppercase().replace('U', 'T')
        val s2 = seq2.uppercase().replace('U', 'T')
        val rc2 = Alphabet.reverseComplement(s2)
        var bestDg = 0.0
        var bestLen = 0
        for (offset in -(s2.length - 1) until s1.length) {
            var matchLen = 0
            var dimerDg = 0.0
            for (j in s1.indices) {
                val k = j - offset
                if (k in rc2.indices) {
                    val pair = "${s1[j]}${rc2[k]}"
                    if (pair in NN_DG) {
                        dimerDg += NN_DG[pair] ?: -1.5
                        matchLen++
                    } else break
                }
            }
            if (matchLen > 0 && dimerDg < bestDg) {
                bestDg = dimerDg
                bestLen = matchLen
            }
        }
        return DimerResult(bestDg, bestLen)
    }

    fun assessHairpin(seq: String): StructureReport {
        val upper = seq.uppercase().replace('U', 'T')
        var bestStemDg = 0.0
        var bestStemLen = 0
        for (i in 0 until upper.length - 5) {
            for (j in i + 5 until upper.length) {
                var stemLen = 0
                var stemDg = 0.0
                var a = i
                var b = j
                while (a < b) {
                    val pair = "${upper[a]}${Alphabet.complement(upper[b], SeqKind.DNA)}"
                    val rcPair = "${upper[b]}${Alphabet.complement(upper[a], SeqKind.DNA)}"
                    val dg = NN_DG[pair] ?: NN_DG[rcPair] ?: break
                    stemDg += dg
                    stemLen++
                    a++
                    b--
                }
                if (stemLen >= 3 && stemDg < bestStemDg) {
                    bestStemDg = stemDg
                    bestStemLen = stemLen
                }
            }
        }
        val assessment = when {
            bestStemDg < -6.0 -> StructureAssessment.HIGH_RISK
            bestStemDg < -3.0 -> StructureAssessment.MEDIUM_RISK
            bestStemDg < -1.0 -> StructureAssessment.LOW_RISK
            else -> StructureAssessment.NO_RISK
        }
        return StructureReport(assessment, "Hairpin stem ${bestStemLen}bp dG=${"%.2f".format(bestStemDg)} kcal/mol")
    }

    fun assessSelfDimer(seq: String): StructureReport {
        val result = selfDimer(seq)
        val assessment = when {
            result.deltaG < -6.0 -> StructureAssessment.HIGH_RISK
            result.deltaG < -3.0 -> StructureAssessment.MEDIUM_RISK
            result.deltaG < -1.0 -> StructureAssessment.LOW_RISK
            else -> StructureAssessment.NO_RISK
        }
        return StructureReport(assessment, "Self-dimer dG=${"%.2f".format(result.deltaG)} kcal/mol")
    }

    fun fullScreen(seq: String): List<StructureReport> = listOf(assessHairpin(seq), assessSelfDimer(seq))
}
