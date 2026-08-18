package org.instagene.core

data class GuideRNA(
    val sequence: String,
    val pamPosition: Int,
    val onTargetScore: Double,
    val offTargetScore: Double,
    val gcContent: Double,
)

data class CrisprDesignResult(val guides: List<GuideRNA>)

object CrisprDesign {

    fun design(target: Seq, maxGuides: Int = 10): CrisprDesignResult {
        val seq = target.bases.uppercase().replace('U', 'T')
        val guides = mutableListOf<GuideRNA>()
        for (i in 20..seq.length - 3) {
            if (seq[i] == 'N' && seq[i + 1] == 'G' && seq[i + 2] == 'G') {
                val start = i - 20
                if (start >= 0) {
                    val grna = seq.substring(start, i)
                    if (grna.all { it in "ACGT" }) {
                        val gc = grna.count { it == 'G' || it == 'C' } / 20.0
                        val gcPenalty = 1.0 - 4.0 * (gc - 0.5) * (gc - 0.5)
                        val seqHash = grna.fold(0L) { acc, c -> acc * 31 + c.code }
                        val onTarget = (0.5 + gcPenalty * 0.4 + (seqHash % 100) / 1000.0).coerceIn(0.0, 1.0)
                        val offTarget = (0.5 + gcPenalty * 0.3 + ((seqHash / 7) % 100) / 1000.0).coerceIn(0.0, 1.0)
                        guides.add(GuideRNA(grna, i, onTarget, offTarget, gc))
                    }
                }
            }
        }
        val sorted = guides.sortedByDescending { it.onTargetScore }.take(maxGuides)
        return CrisprDesignResult(sorted)
    }
}
