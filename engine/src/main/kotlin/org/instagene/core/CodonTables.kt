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
        // NCBI table 2: Mold, Protozoan, Coelenterate, and Mycoplasma/Spiroplasma
        // Differences from standard: TGA = Trp, AGR = Ser
        private const val AA_MOLD =
            "FFLLSSSSYY**CCWWLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSSSVVVVAAAADDEEGGGG"
        // NCBI table 3: Yeast (Saccharomyces cerevisiae)
        // Differences from standard: CTN = Thr (not Leu), TGA = Trp
        private const val AA_YEAST =
            "FFLLSSSSYY**CCWWTTTTPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"
        // NCBI table 5: Invertebrate (Drosophila, C. elegans, etc.)
        // Differences from standard: AGA/S = Ser, TGA = Trp
        private const val AA_INVERTEBRATE =
            "FFLLSSSSYY**CCWWLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSSSVVVVAAAADDEEGGGG"
        // NCBI table 9: Euplotid Nuclear (ciliated protozoa)
        // Differences from standard: TGA = Cys
        private const val AA_EUPLOTID =
            "FFLLSSSSYY**CCCWLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"
        // NCBI table 10: bacterial/plasmid (same as 11 but only ATG start)
        // NCBI table 12: Spiroplasma and Entomoplasma
        // Differences from standard: TGA = Trp
        private const val AA_SPIROPLASMA =
            "FFLLSSSSYY**CCWWLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"

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

        /** Table 2: Mold, Protozoan, Coelenterate, and Mycoplasma/Spiroplasma. */
        val MOLD = CodonTable(
            id = 2,
            displayName = "2 - Mold / Protozoan / Mycoplasma",
            codons = tableOf(AA_MOLD),
            startCodons = setOf("ATG", "TTG", "CTG", "ATT", "ATC", "ATA", "GTG"),
        )

        /** Table 3: Yeast (Saccharomyces cerevisiae). CTN = Thr, TGA = Trp. */
        val YEAST = CodonTable(
            id = 3,
            displayName = "3 - Yeast (S. cerevisiae)",
            codons = tableOf(AA_YEAST),
            startCodons = setOf("ATG"),
        )

        /** Table 5: Invertebrate (Drosophila, C. elegans, etc.). AGA/S = Ser, TGA = Trp. */
        val INVERTEBRATE = CodonTable(
            id = 5,
            displayName = "5 - Invertebrate",
            codons = tableOf(AA_INVERTEBRATE),
            startCodons = setOf("ATG", "TTG", "CTG", "ATT", "ATC", "ATA", "GTG"),
        )

        /** Table 9: Euplotid Nuclear (ciliated protozoa). */
        val EUPLOTID = CodonTable(
            id = 9,
            displayName = "9 - Euplotid Nuclear",
            codons = tableOf(AA_EUPLOTID),
            startCodons = setOf("ATG"),
        )

        /** Table 11: same amino acids, many more permitted start codons. */
        val BACTERIAL = CodonTable(
            id = 11,
            displayName = "11 - Bacterial / Plasmid",
            codons = tableOf(AA_STANDARD),
            startCodons = setOf("ATG", "GTG", "TTG", "ATT", "ATC", "ATA", "CTG"),
        )

        /** Table 12: Spiroplasma and Entomoplasma. */
        val SPIROPLASMA = CodonTable(
            id = 12,
            displayName = "12 - Spiroplasma / Entomoplasma",
            codons = tableOf(AA_SPIROPLASMA),
            startCodons = setOf("ATG", "TTG", "CTG", "ATT", "ATC", "ATA", "GTG"),
        )

        /** The bundled tables. */
        val ALL = listOf(STANDARD, MOLD, YEAST, INVERTEBRATE, EUPLOTID, BACTERIAL, SPIROPLASMA)

        /** The table with the NCBI [id], throwing [IllegalArgumentException] when it is not bundled. */
        fun byId(id: Int): CodonTable =
            ALL.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown genetic code table $id (available: ${ALL.map { it.id }})")
    }
}
