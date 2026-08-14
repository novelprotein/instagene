package org.instagene.core

data class Ladder(val name: String, val bandsBp: List<Int>) {
    init { require(bandsBp.all { it > 0 }) { "ladder bands must be positive" } }
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
}

data class GelBand(val sizeBp: Int, val relativeIntensity: Double = 1.0, val fragment: Fragment? = null)
data class GelLaneResult(val name: String, val bands: List<GelBand>)
data class VirtualGelResult(val lanes: List<GelLaneResult>) {
    fun migration(sizeBp: Int): Double = (1.0 - (kotlin.math.log10(sizeBp.coerceAtLeast(1).toDouble()) / 8.0)).coerceIn(0.0, 1.0)
}

/** Deterministic virtual agarose gel simulation over the digest engine. */
object VirtualGel {
    fun run(lanes: List<GelLane>): VirtualGelResult = VirtualGelResult(lanes.map { lane ->
        when (lane) {
            is GelLane.SizeStandard -> GelLaneResult(lane.name, lane.ladder.bandsBp.sortedDescending().map { GelBand(it) })
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
    })
}
