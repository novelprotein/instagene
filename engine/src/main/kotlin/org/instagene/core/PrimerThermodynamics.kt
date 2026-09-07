package org.instagene.core

import kotlin.math.ln
import kotlin.math.sqrt

enum class StructureAssessment { NO_RISK, LOW_RISK, MEDIUM_RISK, HIGH_RISK }

data class ThermodynamicResult(val deltaG: Double, val tm: Double)

data class DimerResult(val deltaG: Double, val length: Int)

data class StructureReport(val assessment: StructureAssessment, val details: String)

object PrimerThermodynamics {

    // Allawi & SantaLucia 1997 DNA/DNA parameters (Biochemistry 36:10581-10594).
    // Matches Biopython DNA_NN3, including terminal initiation and symmetry.
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
        val terminalAt = listOf(seq.first(), seq.last()).count { it == 'A' || it == 'T' }
        val dh = nnDh + terminalAt * 2.3 + (2 - terminalAt) * 0.1
        val ds = nnDs + terminalAt * 4.1 - (2 - terminalAt) * 2.8 - (if (selfComplementary) 1.4 else 0.0)
        return dh to ds
    }

    private fun duplexDeltaG(seq: String): Double {
        val (dh, ds) = duplexThermodynamics(seq)
        return deltaG37(dh, ds)
    }

    /** Owczarzy 2004/2008 reciprocal-temperature salt correction, concentrations in M.
     * https://doi.org/10.1021/bi702363u; validated against Biopython 1.86 saltcorr=7.
     */
    private fun saltCorrection(na: Double, magnesium: Double, dntp: Double, gc: Double, length: Int): Double {
        val binding = 3e4
        val balance = binding * (dntp - magnesium) + 1.0
        val mg = if (dntp == 0.0) magnesium else
            (-balance + sqrt(balance * balance + 4.0 * binding * magnesium)) / (2.0 * binding)
        val logNa = ln(na)
        val ratio = sqrt(mg.coerceAtLeast(0.0)) / na
        if (ratio < 0.22) {
            return (4.29 * gc - 3.95) * 1e-5 * logNa + 9.40e-6 * logNa * logNa
        }
        var a = 3.92
        var d = 1.42
        var g = 8.31
        if (ratio < 6.0) {
            a *= 0.843 - 0.352 * sqrt(na) * logNa
            d *= 1.279 - 4.03e-3 * logNa - 8.03e-3 * logNa * logNa
            g *= 0.486 - 0.258 * logNa + 5.25e-3 * logNa * logNa * logNa
        }
        val logMg = ln(mg)
        return (a - 0.911 * logMg + gc * (6.26 + d * logMg) +
            (-48.2 + 52.5 * logMg + g * logMg * logMg) / (2.0 * (length - 1))) * 1e-5
    }

    /** Perfect DNA/DNA duplex model. U is interpreted as T, not as an RNA model.
     * [strandConcentration] is total strand concentration in M: CT/4 for equimolar
     * distinct complementary strands, CT for a self-complementary strand.
     * deltaG is the standard duplex free energy at 37 C, 1 M Na; Tm is salt corrected.
     */
    fun thermodynamicResult(
        seq: String,
        naConc: Double = 0.05,
        mgConc: Double = 0.0,
        dntpConc: Double = 0.0,
        strandConcentration: Double = 250e-9,
    ): ThermodynamicResult {
        val upper = seq.uppercase().replace('U', 'T')
        require(upper.length >= 2) { "Primer sequence must contain at least two bases" }
        require(upper.all { it in "ACGT" }) { "Primer sequence must contain only A, C, G, T, or U" }
        require(naConc.isFinite() && naConc > 0.0) { "Monovalent salt concentration must be positive and finite" }
        require(mgConc.isFinite() && mgConc >= 0.0) { "Magnesium concentration must be finite and non-negative" }
        require(dntpConc.isFinite() && dntpConc >= 0.0) { "dNTP concentration must be finite and non-negative" }
        require(strandConcentration.isFinite() && strandConcentration > 0.0) { "Total strand concentration must be positive and finite" }
        val selfComplementary = Alphabet.reverseComplement(upper) == upper
        val (dh, ds) = duplexThermodynamics(upper, selfComplementary)
        val concentration = strandConcentration / if (selfComplementary) 1.0 else 4.0
        val denominator = ds + R_CAL * ln(concentration)
        require(denominator < 0.0) { "Thermodynamic denominator is invalid for this sequence and concentration" }
        val tmKelvin = dh * 1000.0 / denominator
        val gc = upper.count { it == 'G' || it == 'C' }.toDouble() / upper.length
        val tm = 1.0 / (1.0 / tmKelvin + saltCorrection(naConc, mgConc, dntpConc, gc, upper.length)) - 273.15
        return ThermodynamicResult(deltaG37(dh, ds), tm)
    }

    fun selfDimer(seq: String): DimerResult = heteroDimer(seq, seq)

    /** Contiguous complementary-stem screen, not a full dimer free-energy model. */
    fun heteroDimer(seq1: String, seq2: String): DimerResult {
        val first = seq1.uppercase().replace('U', 'T')
        val second = Alphabet.reverseComplement(seq2.uppercase().replace('U', 'T'))
        var bestDg = 0.0
        var bestLen = 0
        for (offset in -(second.length - 1) until first.length) {
            var length = 0
            for (i in maxOf(0, offset) until minOf(first.length, second.length + offset)) {
                if (first[i] == second[i - offset] && first[i] in "ACGT") length++ else length = 0
                if (length > 1) {
                    val dg = duplexDeltaG(first.substring(i - length + 1, i + 1))
                    if (dg < bestDg) { bestDg = dg; bestLen = length }
                }
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
                while (b - a > 3) {
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
        return StructureReport(assessment, "Heuristic complementary-stem screen (loop energy excluded): ${bestStemLen}bp, estimated dG=${"%.2f".format(bestStemDg)} kcal/mol")
    }

    fun assessSelfDimer(seq: String): StructureReport {
        val result = selfDimer(seq)
        val assessment = assessDgRisk(result.deltaG)
        return StructureReport(assessment, "Heuristic complementary-stem energy (not full dimer ΔG)=${"%.2f".format(result.deltaG)} kcal/mol")
    }

    fun fullScreen(seq: String): List<StructureReport> = listOf(assessHairpin(seq), assessSelfDimer(seq))

    private fun assessDgRisk(dg: Double) = when {
        dg < HIGH_RISK_DG -> StructureAssessment.HIGH_RISK
        dg < MEDIUM_RISK_DG -> StructureAssessment.MEDIUM_RISK
        dg < LOW_RISK_DG -> StructureAssessment.LOW_RISK
        else -> StructureAssessment.NO_RISK
    }
}
