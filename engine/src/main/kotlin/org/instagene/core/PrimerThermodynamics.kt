package org.instagene.core

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

enum class StructureAssessment { NO_RISK, LOW_RISK, MEDIUM_RISK, HIGH_RISK }

data class ThermodynamicResult(val deltaG: Double, val tm: Double)

data class DimerResult(val deltaG: Double, val length: Int)

data class StructureReport(val assessment: StructureAssessment, val details: String)

object PrimerThermodynamics {

    // SantaLucia 1998 unified nearest-neighbor parameters (PNAS 95:1460-1465)
    // ΔH in kcal/mol, ΔS in cal/(mol·K)
    private val NN_DH = mapOf(
        "AA" to -7.9, "TT" to -7.9,
        "AT" to -7.2, "TA" to -7.2,
        "CA" to -8.5, "TG" to -8.5,
        "GT" to -8.4, "AC" to -8.4,
        "CT" to -7.8, "AG" to -7.8,
        "GA" to -8.2, "TC" to -8.2,
        "CG" to -10.6, "GC" to -9.8,
        "GG" to -8.0, "CC" to -8.0,
    )

    private val NN_DS = mapOf(
        "AA" to -22.2, "TT" to -22.2,
        "AT" to -20.4, "TA" to -21.3,
        "CA" to -22.7, "TG" to -22.7,
        "GT" to -22.4, "AC" to -22.4,
        "CT" to -21.0, "AG" to -21.0,
        "GA" to -22.2, "TC" to -22.2,
        "CG" to -27.2, "GC" to -24.4,
        "GG" to -19.9, "CC" to -19.9,
    )

    private const val R_CAL = 1.987
    private const val HIGH_RISK_DG = -6.0
    private const val MEDIUM_RISK_DG = -3.0
    private const val LOW_RISK_DG = -1.0

    private fun nnParams(seq: String): Pair<Double, Double> {
        val upper = seq.uppercase().replace('U', 'T')
        require(upper.isNotEmpty() && upper.all { it in "ACGT" }) {
            "Primer sequence must contain only A, C, G, T, or U"
        }
        var dh = 0.0
        var ds = 0.0
        for (i in 0 until upper.length - 1) {
            val pair = upper.substring(i, i + 2)
            dh += NN_DH[pair] ?: -8.0
            ds += NN_DS[pair] ?: -22.0
        }
        return Pair(dh, ds)
    }

    private fun deltaG37(dh: Double, ds: Double): Double {
        return dh - 310.15 * ds / 1000.0
    }

    private fun duplexThermodynamics(seq: String, selfComplementary: Boolean = false): Pair<Double, Double> {
        val (nnDh, nnDs) = nnParams(seq)
        // SantaLucia's initiation terms (SantaLucia, 1998), including the
        // symmetry penalty for self-complementary duplexes.
        val dh = nnDh + 0.2
        val ds = nnDs - 5.7 - if (selfComplementary) 2.8 else 0.0
        return dh to ds
    }

    private fun duplexDeltaG(seq: String): Double {
        val (dh, ds) = duplexThermodynamics(seq)
        return deltaG37(dh, ds)
    }

    /**
     * Nearest-neighbor Tm at 1M NaCl (SantaLucia 1998).
     * For non-self-complementary: Tm = ΔH*1000 / (ΔS + R*ln(CT/4)) - 273.15
     */
    private fun calcTm1M(dh: Double, ds: Double, conc: Double = 250e-9): Double {
        if (ds + R_CAL * ln(conc / 4.0) >= 0.0) return 120.0
        val tm = dh * 1000.0 / (ds + R_CAL * ln(conc / 4.0)) - 273.15
        return tm.coerceIn(0.0, 120.0)
    }

    /**
     * Owczarzy 2008 monovalent salt correction (Biochemistry 47:5336-5353).
     * Adjusts Tm from 1M NaCl to actual [Na⁺].
     */
    private fun correctMonovalent(tm1M: Double, naConc: Double, gcFraction: Double): Double {
        if (naConc <= 0.0 || naConc >= 1.0) return tm1M
        val logNa = log10(naConc)
        val tm1K = tm1M + 273.15
        val invTm = 1.0 / tm1K + (4.29 * gcFraction - 3.95) * 1e-5 * logNa + 9.40e-6 * logNa * logNa
        return (1.0 / invTm - 273.15).coerceIn(0.0, 120.0)
    }

    /**
     * Owczarzy 2008 divalent cation correction (Biochemistry 47:5336-5353).
     * Accounts for Mg²⁺ chelation by dNTPs.
     *
     * @param tmNa Tm after monovalent correction
     * @param mgConc free Mg²⁺ concentration (M)
     * @param dntpConc total dNTP concentration (M)
     * @param naConc monovalent cation concentration (M)
     * @param gcFraction GC content of the oligo (0.0-1.0)
     */
    private fun correctDivalent(
        tmNa: Double,
        mgConc: Double,
        dntpConc: Double,
        naConc: Double,
        gcFraction: Double,
    ): Double {
        if (mgConc <= 0.0) return tmNa
        val freeMg = (mgConc - dntpConc).coerceAtLeast(0.0)
        if (freeMg == 0.0) return tmNa
        // The validated monovalent-equivalent approximation used for mixed
        // Na+/Mg2+ buffers (Owczarzy et al., 2008).  It is preferable to
        // claiming precision from the published full mixed-salt polynomial
        // without the required activity and concentration regime metadata.
        val effectiveNa = naConc + 3.3 * sqrt(freeMg)
        return correctMonovalent(tmNa, effectiveNa, gcFraction)
    }

    /**
     * Calculate melting temperature with salt correction.
     *
     * Uses SantaLucia 1998 nearest-neighbor parameters with optional
     * Owczarzy 2008 salt corrections for monovalent and divalent cations.
     */
    fun thermodynamicResult(
        seq: String,
        naConc: Double = 0.05,
        mgConc: Double = 0.0,
        dntpConc: Double = 0.0,
    ): ThermodynamicResult {
        val upper = seq.uppercase().replace('U', 'T')
        require(upper.length >= 2) { "Primer sequence must contain at least two bases" }
        require(naConc.isFinite() && naConc > 0.0) { "Monovalent salt concentration must be positive and finite" }
        require(mgConc.isFinite() && mgConc >= 0.0) { "Magnesium concentration must be finite and non-negative" }
        require(dntpConc.isFinite() && dntpConc >= 0.0) { "dNTP concentration must be finite and non-negative" }
        val (dh, ds) = duplexThermodynamics(upper, Alphabet.reverseComplement(upper) == upper)
        val dg = deltaG37(dh, ds)
        val gcCount = upper.count { it == 'G' || it == 'C' }
        val gcFrac = gcCount.toDouble() / upper.length

        var tm = calcTm1M(dh, ds)
        if (naConc != 1.0) {
            tm = correctMonovalent(tm, naConc, gcFrac)
        }
        if (mgConc > 0.0) {
            tm = correctDivalent(tm, mgConc, dntpConc, naConc, gcFrac)
        }

        return ThermodynamicResult(dg, tm)
    }

    fun selfDimer(seq: String): DimerResult {
        val upper = seq.uppercase().replace('U', 'T')
        var bestDg = 0.0
        var bestLen = 0
        val rc = Alphabet.reverseComplement(upper)
        for (offset in upper.indices) {
            val overlap = upper.length - offset
            var matchLen = 0
            var sumDg = 0.0
            for (j in 0 until overlap) {
                if (upper[j] == rc[j + offset]) {
                    matchLen++
                } else break
            }
            if (matchLen > 1) sumDg = duplexDeltaG(upper.substring(0, matchLen))
            if (matchLen > 0 && sumDg < bestDg) {
                bestDg = sumDg
                bestLen = matchLen
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
            var sumDg = 0.0
            for (j in s1.indices) {
                val k = j - offset
                if (k in rc2.indices) {
                    if (s1[j] == rc2[k]) matchLen++ else break
                }
                if (matchLen > 1) {
                    val start = (j - matchLen + 1).coerceAtLeast(0)
                    sumDg = duplexDeltaG(s1.substring(start, j + 1))
                }
            }
            if (matchLen > 0 && sumDg < bestDg) {
                bestDg = sumDg
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
                    if (upper[a] == Alphabet.complement(upper[b], SeqKind.DNA)) {
                        stemLen++
                        a++
                        b--
                    } else break
                }
                if (stemLen >= 3) {
                    val stem = upper.substring(i, i + stemLen)
                    stemDg = duplexDeltaG(stem)
                }
                if (stemLen >= 3 && stemDg < bestStemDg) {
                    bestStemDg = stemDg
                    bestStemLen = stemLen
                }
            }
        }
        val assessment = assessDgRisk(bestStemDg)
        return StructureReport(assessment, "Complementary-stem screen: ${bestStemLen}bp, estimated dG=${"%.2f".format(bestStemDg)} kcal/mol")
    }

    fun assessSelfDimer(seq: String): StructureReport {
        val result = selfDimer(seq)
        val assessment = assessDgRisk(result.deltaG)
        return StructureReport(assessment, "Self-dimer dG=${"%.2f".format(result.deltaG)} kcal/mol")
    }

    fun fullScreen(seq: String): List<StructureReport> = listOf(assessHairpin(seq), assessSelfDimer(seq))

    private fun assessDgRisk(dg: Double) = when {
        dg < HIGH_RISK_DG -> StructureAssessment.HIGH_RISK
        dg < MEDIUM_RISK_DG -> StructureAssessment.MEDIUM_RISK
        dg < LOW_RISK_DG -> StructureAssessment.LOW_RISK
        else -> StructureAssessment.NO_RISK
    }
}
