package org.instagene.core

/**
 * NCBI genetic codes. Only the two that matter for everyday cloning are bundled:
 * the standard code and the bacterial/plasmid code (which differs in its start codons).
 */
class CodonTable(
    val id: Int,
    val displayName: String,
    private val codons: Map<String, Char>,
    val startCodons: Set<String>,
) {
    /** Translates a single codon; unknown or degenerate codons become 'X'. */
    fun translate(codon: String): Char =
        codons[codon.uppercase().replace('U', 'T')] ?: 'X'

    /** True when [codon] translates to the stop symbol ('*'). */
    fun isStop(codon: String): Boolean = translate(codon) == '*'

    /** True when [codon] (T or U) is one of this table's permitted start codons. */
    fun isStart(codon: String): Boolean =
        codon.uppercase().replace('U', 'T') in startCodons

    companion object {
        // Amino acids in the canonical NCBI ordering of TTT, TTC, TTA, ... GGG.
        private const val AA_STANDARD =
            "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"

        private fun codonOrder(): List<String> {
            val bases = "TCAG"
            val out = ArrayList<String>(64)
            for (a in bases) for (b in bases) for (c in bases) out += "$a$b$c"
            return out
        }

        private fun tableOf(aminoAcids: String): Map<String, Char> =
            codonOrder().mapIndexed { i, codon -> codon to aminoAcids[i] }.toMap()

        /** Table 1: the canonical genetic code. */
        val STANDARD = CodonTable(
            id = 1,
            displayName = "1 - Standard",
            codons = tableOf(AA_STANDARD),
            startCodons = setOf("ATG"),
        )

        /** Table 11: same amino acids, many more permitted start codons. */
        val BACTERIAL = CodonTable(
            id = 11,
            displayName = "11 - Bacterial / Plasmid",
            codons = tableOf(AA_STANDARD),
            startCodons = setOf("ATG", "GTG", "TTG", "ATT", "ATC", "ATA", "CTG"),
        )

        /** The bundled tables: standard, then bacterial/plasmid. */
        val ALL = listOf(STANDARD, BACTERIAL)

        /** The table with the NCBI [id], throwing [IllegalArgumentException] when it is not bundled. */
        fun byId(id: Int): CodonTable =
            ALL.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown genetic code table $id (available: ${ALL.map { it.id }})")
    }
}
