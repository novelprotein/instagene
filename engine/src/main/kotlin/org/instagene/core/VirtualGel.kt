package org.instagene.core

data class Ladder(val name: String, val bandsBp: List<Int>) {
    init { require(bandsBp.all { it > 0 }) { "ladder bands must be positive" } }
}

enum class GelBuffer { TAE, TBE, SB }

data class GelSettings(
    val agarosePercent: Double = 1.0,
    val runMinutes: Int = 45,
    val voltage: Int = 100,
    val buffer: GelBuffer = GelBuffer.TAE,
) {
    init {
        require(agarosePercent in 0.3..5.0) { "agarose percentage must be between 0.3 and 5.0" }
        require(runMinutes > 0) { "run time must be positive" }
        require(voltage > 0) { "voltage must be positive" }
    }
}

sealed interface GelLane {
    val name: String
    data class Dna(
        override val name: String,
        val sequence: Seq,
        val enzymes: List<Enzyme>,
        val completionPercent: Int = 100,
    ) : GelLane
    data class SizeStandard(override val name: String, val ladder: Ladder) : GelLane
    data class PcrProduct(override val name: String, val product: Seq, val massNg: Double = 50.0) : GelLane
}

data class GelBand(val sizeBp: Int, val relativeIntensity: Double = 1.0, val fragment: Fragment? = null)
data class GelLaneResult(val name: String, val bands: List<GelBand>)
data class VirtualGelResult(val lanes: List<GelLaneResult>, val settings: GelSettings = GelSettings()) {
    fun migration(sizeBp: Int): Double {
        val base = 1.0 - kotlin.math.log10(sizeBp.coerceAtLeast(1).toDouble()) / 8.0
        val gelFactor = 1.0 / kotlin.math.sqrt(settings.agarosePercent)
        val runFactor = (settings.runMinutes / 45.0) * (settings.voltage / 100.0)
        return (base * gelFactor * runFactor).coerceIn(0.0, 1.0)
    }
}

/** Deterministic virtual agarose gel simulation over the digest engine. */
object VirtualGel {
    val LADDERS = listOf(
        Ladder("1 kb ladder", listOf(10_000, 8_000, 6_000, 5_000, 4_000, 3_000, 2_000, 1_500, 1_000, 500)),
        Ladder("100 bp ladder", (100..1_500 step 100).toList().reversed()),
        Ladder("Low molecular weight", listOf(1_000, 700, 500, 400, 300, 200, 100, 75, 50, 25)),
    )

    fun run(lanes: List<GelLane>, settings: GelSettings = GelSettings()): VirtualGelResult = VirtualGelResult(lanes.map { lane ->
        when (lane) {
            is GelLane.SizeStandard -> GelLaneResult(lane.name, lane.ladder.bandsBp.sortedDescending().map { GelBand(it) })
            is GelLane.PcrProduct -> {
                require(lane.product.length > 0) { "PCR product cannot be empty" }
                GelLaneResult(lane.name, listOf(GelBand(lane.product.length, lane.massNg.coerceAtLeast(0.0) / 50.0)))
            }
            is GelLane.Dna -> {
                require(lane.completionPercent in 0..100) { "digest completion must be between 0 and 100" }
                val sites = Digest.cutSites(lane.sequence, lane.enzymes)
                    .filterIndexed { index, _ -> lane.completionPercent == 100 || (index * 37 + lane.sequence.length) % 100 < lane.completionPercent }
                val fragments = Digest.digestSites(lane.sequence, sites)
                val grouped = fragments.groupBy { it.length }
                GelLaneResult(lane.name, grouped.entries.sortedByDescending { it.key }.map { (size, same) ->
                    GelBand(size, same.size.toDouble(), same.first())
                })
            }
        }
    }, settings)
}
