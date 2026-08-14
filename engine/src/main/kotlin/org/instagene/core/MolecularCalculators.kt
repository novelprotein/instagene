package org.instagene.core

data class DnaReactionFragment(val name: String, val sizeBp: Int, val concentrationNgPerUl: Double, val molarRatio: Double = 1.0)
data class ReactionVolume(val name: String, val volumeUl: Double, val massNg: Double, val nanomoles: Double)
data class ReactionSetup(
    val fragments: List<ReactionVolume>,
    val waterUl: Double,
    val totalVolumeUl: Double,
    val intermediateDnaVolumeUl: Double,
)
data class DilutionResult(val stockVolumeUl: Double, val diluentVolumeUl: Double, val finalVolumeUl: Double)
data class MasterMixComponent(val name: String, val perReactionUl: Double)
data class MasterMixResult(val components: List<ReactionVolume>, val totalVolumeUl: Double)

/** Unit-safe calculations used by cloning and reaction-planning workflows. */
object MolecularCalculators {
    fun molecularWeight(seq: Seq): Double = SeqOps.molecularWeightDaltons(seq)

    fun molarMassForNucleicAcid(size: Int, doubleStranded: Boolean = true, rna: Boolean = false): Double {
        require(size >= 0) { "size must not be negative" }
        val perBase = when {
            rna -> 340.0
            doubleStranded -> 660.0
            else -> 330.0
        }
        return size * perBase
    }

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

    fun molarRatioReaction(
        fragments: List<DnaReactionFragment>,
        reactionVolumeUl: Double,
        dnaAmountNanomolar: Double,
        minimumPipetteVolumeUl: Double = 0.0,
    ): ReactionSetup {
        require(fragments.isNotEmpty()) { "at least one fragment is required" }
        require(reactionVolumeUl > 0 && dnaAmountNanomolar > 0) { "reaction volume and DNA amount must be positive" }
        val targetMoles = fragments.sumOf { it.molarRatio }.let { total -> dnaAmountNanomolar / total }
        val volumes = fragments.map { fragment ->
            val molarity = targetMoles * fragment.molarRatio
            val mw = molarMassForNucleicAcid(fragment.sizeBp)
            val volume = massNg(molarity, mw, 1.0) / fragment.concentrationNgPerUl
            ReactionVolume(fragment.name, maxOf(volume, minimumPipetteVolumeUl), volume * fragment.concentrationNgPerUl, molarity)
        }
        val dnaVolume = volumes.sumOf { it.volumeUl }
        return ReactionSetup(volumes, (reactionVolumeUl - dnaVolume).coerceAtLeast(0.0), reactionVolumeUl, dnaVolume)
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
}
