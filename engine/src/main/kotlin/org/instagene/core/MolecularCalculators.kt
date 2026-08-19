package org.instagene.core

data class ReactionVolume(val name: String, val volumeUl: Double, val massNg: Double, val nanomoles: Double)
data class DilutionResult(val stockVolumeUl: Double, val diluentVolumeUl: Double, val finalVolumeUl: Double)
data class MasterMixComponent(val name: String, val perReactionUl: Double)
data class MasterMixResult(val components: List<ReactionVolume>, val totalVolumeUl: Double)

/** Unit-safe calculations used by cloning and reaction-planning workflows. */
object MolecularCalculators {
    fun molecularWeight(seq: Seq): Double = SeqOps.molecularWeightDaltons(seq)

    /** Converts mass/concentration units to molarity in nanomolar. */
    fun nanomolar(massNg: Double, molecularWeightDa: Double, volumeUl: Double): Double {
        require(molecularWeightDa > 0 && volumeUl > 0) { "molecular weight and volume must be positive" }
        return massNg * 1_000_000.0 / (molecularWeightDa * volumeUl)
    }

    fun massNg(nanomolar: Double, molecularWeightDa: Double, volumeUl: Double): Double {
        require(nanomolar >= 0 && molecularWeightDa > 0 && volumeUl > 0) { "invalid molarity inputs" }
        return nanomolar * molecularWeightDa * volumeUl / 1_000_000.0
    }

    fun dilution(stock: Double, final: Double, finalVolumeUl: Double): DilutionResult {
        require(stock > 0 && final >= 0 && final <= stock && finalVolumeUl > 0) { "invalid dilution inputs" }
        val stockVolume = final * finalVolumeUl / stock
        return DilutionResult(stockVolume, finalVolumeUl - stockVolume, finalVolumeUl)
    }

    fun masterMix(
        components: List<MasterMixComponent>,
        reactions: Int,
        overheadFraction: Double = 0.0,
    ): MasterMixResult {
        require(reactions > 0 && overheadFraction >= 0) { "invalid master mix inputs" }
        val multiplier = reactions * (1.0 + overheadFraction)
        val result = components.map {
            ReactionVolume(it.name, it.perReactionUl * multiplier, 0.0, 0.0)
        }
        return MasterMixResult(result, result.sumOf { it.volumeUl })
    }

    fun parseRecipe(recipe: String): List<Pair<String, Double>> = recipe
        .split(',', ';', '\n')
        .mapNotNull { token ->
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val match = Regex("^(.+?)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(?:mM|M)?$", RegexOption.IGNORE_CASE).find(trimmed)
                ?: return@mapNotNull null
            match.groupValues[1].trim() to match.groupValues[2].toDouble()
        }

    /**
     * Molar extinction coefficient at 280 nm (ε₂₈₀) for a protein sequence.
     *
     * Uses the Gill & von Hippel (1990) method: counts Trp, Tyr, and Cys
     * residues plus disulfide bridges. If [disulfideBonds] is -1 (default),
     * all Cys residues are assumed to be in disulfide bonds.
     *
     * Reference: Gill SC, von Hippel PH. (1990) Anal Biochem. 182:319-326.
     */
    fun extinctionCoefficient(
        protein: Seq,
        disulfideBonds: Int = -1,
    ): Double {
        require(protein.kind == SeqKind.PROTEIN) { "Extinction coefficient requires a protein sequence" }
        var trp = 0
        var tyr = 0
        var cys = 0
        for (c in protein.bases.uppercase()) when (c) {
            'W' -> trp++
            'Y' -> tyr++
            'C' -> cys++
        }
        // Gill & von Hippel (1990) coefficients
        val base = trp * 5500.0 + tyr * 1490.0 + cys * 125.0
        // Disulfide bond correction: -2.0 * disulfideBonds * 125.0
        val ds = if (disulfideBonds >= 0) disulfideBonds else cys / 2
        return base - 2.0 * ds * 125.0
    }

    /**
     * Absorbance at 280 nm for a 1 mg/mL solution in a 1 cm cuvette.
     *
     * A(1%, 280) = ε₂₈₀ * 10 / MW(Da)
     */
    fun absorbanceAt1Percent(protein: Seq, disulfideBonds: Int = -1): Double {
        val ec = extinctionCoefficient(protein, disulfideBonds)
        val mw = molecularWeight(protein)
        return if (mw > 0) ec * 10.0 / mw else 0.0
    }
}
