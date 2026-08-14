package org.instagene.core

data class PrimerDesignParameters(
    val minLength: Int = 18,
    val maxLength: Int = 30,
    val targetTm: Double = 60.0,
    val minTm: Double = 50.0,
    val maxTm: Double = 70.0,
    val minGc: Double = 30.0,
    val maxGc: Double = 70.0,
    val maxHomopolymer: Int = 5,
    val maxSelfComplementarity: Int = 8,
)

data class PrimerCandidate(
    val primer: SeqOps.Primer,
    val start: Int,
    val end: Int,
    val score: Double,
    val selfComplementarity: Int,
)

/** Exhaustive, explainable primer candidate search for PCR and assembly tools. */
object PrimerDesign {
    fun candidates(seq: Seq, start: Int, end: Int, parameters: PrimerDesignParameters = PrimerDesignParameters()): List<PrimerCandidate> {
        require(start in 0 until seq.length && end in (start + 1)..seq.length) { "Invalid primer target" }
        val output = ArrayList<PrimerCandidate>()
        for (length in parameters.minLength..parameters.maxLength) {
            if (start + length <= end) output += candidate(seq.sub(start, start + length), start, start + length, parameters, "F")
            if (end - length >= start) {
                val reverse = seq.sub(end - length, end).let { Seq(name = "primer", bases = it).reverseComplement().bases }
                output += candidate(reverse, end - length, end, parameters, "R")
            }
        }
        return output.filter {
            it.primer.tm in parameters.minTm..parameters.maxTm &&
                it.primer.gc in parameters.minGc..parameters.maxGc &&
                it.selfComplementarity <= parameters.maxSelfComplementarity
        }.sortedBy { it.score }
    }

    private fun candidate(bases: String, start: Int, end: Int, p: PrimerDesignParameters, suffix: String): PrimerCandidate {
        val primer = SeqOps.Primer("candidate_$suffix", bases.uppercase(), SeqOps.meltingTemp(bases), SeqOps.gcContent(bases))
        val homopolymer = Regex("(.)\\1{${p.maxHomopolymer},}", RegexOption.IGNORE_CASE).containsMatchIn(bases)
        val self = selfComplementarity(bases)
        val score = kotlin.math.abs(primer.tm - p.targetTm) + kotlin.math.abs(primer.gc - 50.0) / 10.0 + if (homopolymer) 100.0 else 0.0
        return PrimerCandidate(primer, start, end, score, self)
    }

    private fun selfComplementarity(bases: String): Int {
        val rc = Seq(name = "primer", bases = bases).reverseComplement().bases
        var best = 0
        for (offset in -bases.length..bases.length) {
            var run = 0
            for (i in bases.indices) {
                val j = i + offset
                if (j in rc.indices && bases[i] == rc[j]) { run++; best = maxOf(best, run) } else run = 0
            }
        }
        return best
    }
}
