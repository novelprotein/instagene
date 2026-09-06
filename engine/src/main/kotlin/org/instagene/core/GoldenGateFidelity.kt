package org.instagene.core

/** Qualitative Golden Gate overhang screening; experimental fidelity is not estimated. */
data class FidelityScore(
    val setFidelity: Double? = null,
    val perOverhangFidelity: Map<String, Double?> = emptyMap(),
    val weakestOverhang: String?,
    val weakestFidelity: Double? = null,
    val warnings: List<String>,
    val quantitativeFidelityAvailable: Boolean = false,
)

object GoldenGateFidelity {

    /**
     * Screens a Golden Gate assembly overhang set for design hazards.
     *
     * Experimental ligation percentages are deliberately not estimated here:
     * fidelity depends on enzyme, temperature, concentration, substrate context,
     * and the particular overhang pair.
     *
     * @param overhangs the set of 4-base overhangs used in the assembly
     * @return fidelity score with per-overhang breakdown and warnings
     */
    fun score(overhangs: List<String>): FidelityScore {
        val normalized = overhangs.map { it.uppercase().trim() }
        val warnings = ArrayList<String>()

        // Validate overhangs
        for (oh in normalized) {
            if (oh.length != 4) warnings.add("Overhang '$oh' is not 4 bases")
            if (!oh.all { it in "ACGT" }) warnings.add("Overhang '$oh' contains non-DNA characters")
        }

        // Check for palindromes
        for (oh in normalized) {
            val rc = Alphabet.reverseComplement(oh)
            if (oh == rc) warnings.add("Palindromic overhang '$oh' can ligate in either orientation")
        }

        // Check for duplicates
        val dups = normalized.groupBy { it }.filter { it.value.size > 1 }
        for ((oh, _) in dups) warnings.add("Duplicate overhang '$oh' — fragments may ligate incorrectly")

        for (left in normalized) {
            for (right in normalized) {
                if (left != right && left == Alphabet.reverseComplement(right)) {
                    warnings.add("Overhang '$left' is complementary to '$right' and may permit an unintended ligation")
                }
            }
        }

        return FidelityScore(
            weakestOverhang = null,
            warnings = warnings,
            quantitativeFidelityAvailable = false,
        )
    }

    /**
     * Reports the standard published overhang sets.
     */
    fun standardSets(): Map<String, List<String>> = mapOf(
        "Plant Standard (11 overhangs)" to listOf("GGAG", "TGAC", "TCCC", "TACT", "CCAT", "AATG", "AGCC", "TTCG", "GCTT", "GGTA", "CGCT"),
        "CIDAR MoClo (8 overhangs)" to listOf("GGAG", "TACT", "AATG", "AGGT", "GCTT", "CGCT", "TGCC", "ACTA"),
        "Minimal (4 overhangs)" to listOf("GGAG", "TACT", "AATG", "GCTT"),
    )
}
