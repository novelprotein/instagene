package org.instagene.core

/** A donor placement bounded by matching left and right homology arms. */
data class RecombinationCandidate(
    val targetLeft: Int,
    val targetRight: Int,
    val armLength: Int,
    val score: Int,
)

data class RecombinationResult(val product: Seq, val candidate: RecombinationCandidate)

/** Homology-arm based recombination planning and replacement. */
object Recombination {
    fun candidates(target: Seq, donor: Seq, armLength: Int = 20): List<RecombinationCandidate> {
        require(target.kind != SeqKind.PROTEIN && donor.kind != SeqKind.PROTEIN) { "Recombination requires nucleotide sequences" }
        require(armLength > 0 && donor.length >= armLength * 2) { "Donor must contain two homology arms of the requested length" }
        val left = donor.bases.substring(0, armLength)
        val right = donor.bases.substring(donor.length - armLength)
        val leftHits = exactHits(target.bases, left)
        val rightHits = exactHits(target.bases, right)
        return leftHits.flatMap { leftStart ->
            rightHits.filter { rightStart -> rightStart >= leftStart }
                .map { rightStart ->
                    RecombinationCandidate(leftStart, rightStart, armLength, armLength * 2)
                }
        }.distinctBy { it.targetLeft to it.targetRight }.sortedWith(compareBy({ it.targetLeft }, { it.targetRight }))
    }

    fun recombine(target: Seq, donor: Seq, candidate: RecombinationCandidate, name: String = "${target.name}_recombined"): RecombinationResult {
        require(candidate.targetLeft >= 0 && candidate.targetRight >= candidate.targetLeft) { "Invalid recombination candidate" }
        require(candidate.targetRight + candidate.armLength <= target.length) { "Candidate extends beyond target" }
        require(donor.length >= candidate.armLength * 2) { "Donor is shorter than its homology arms" }
        val productBases = target.bases.substring(0, candidate.targetLeft) + donor.bases +
            target.bases.substring(candidate.targetRight + candidate.armLength)
        val product = Seq(name, productBases, target.kind, Topology.LINEAR, description = "Homology recombination of ${target.name} and ${donor.name}")
        return RecombinationResult(product, candidate)
    }

    private fun exactHits(source: String, pattern: String): List<Int> = buildList {
        var from = 0
        while (from <= source.length - pattern.length) {
            val hit = source.indexOf(pattern, from, ignoreCase = true)
            if (hit < 0) break
            add(hit)
            from = hit + 1
        }
    }
}
