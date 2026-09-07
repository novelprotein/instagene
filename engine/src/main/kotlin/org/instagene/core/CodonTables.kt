package org.instagene.core

/**
 * Bundled NCBI genetic codes, verified against the 2024-09-23 reference tables.
 * https://www.ncbi.nlm.nih.gov/Taxonomy/Utils/wprintgc.cgi
 */
class CodonTable(
    val id: Int,
    val displayName: String,
    codons: Map<String, Char>,
    val startCodons: Set<String>,
) {
    /** Packed lookup: 64-entry CharArray indexed by (b1*16 + b2*4 + b3) where T=0,C=1,A=2,G=3. */
    private val translateTable: CharArray = CharArray(64) { 'X' }

    /** Set of packed start-codon indices for O(1) isStart checks. */
    private val startIndices: IntArray

    init {
        for ((codon, aa) in codons) {
            translateTable[codonIndex(codon)] = aa
        }
        startIndices = startCodons.map { codonIndex(it) }.toIntArray().also { it.sort() }
    }

    /** Translates a single codon; unknown or degenerate codons become 'X'. */
    fun translate(codon: String): Char {
        if (codon.length != 3) return 'X'
        val i = try { codonIndex(codon) } catch (_: IllegalArgumentException) { return 'X' }
        return translateTable[i]
    }

    /** True when [codon] translates to the stop symbol ('*'). */
    fun isStop(codon: String): Boolean = translate(codon) == '*'

    /** True when [codon] (T or U) is one of this table's permitted start codons. */
    fun isStart(codon: String): Boolean {
        if (codon.length != 3) return false
        val i = try { codonIndex(codon) } catch (_: IllegalArgumentException) { return false }
        return startIndices.contains(i)
    }

    companion object {
        private const val BASES = "TCAG"

        /** Encodes a 3-char codon into a 0–63 index. U is treated as T. */
        private fun codonIndex(codon: String): Int {
            val b0 = BASES.indexOf(codon[0].uppercaseChar().let { if (it == 'U') 'T' else it })
            val b1 = BASES.indexOf(codon[1].uppercaseChar().let { if (it == 'U') 'T' else it })
            val b2 = BASES.indexOf(codon[2].uppercaseChar().let { if (it == 'U') 'T' else it })
            if (b0 < 0 || b1 < 0 || b2 < 0) throw IllegalArgumentException("Non-IUPAC base in codon: $codon")
            return b0 * 16 + b1 * 4 + b2
        }
        // Amino acids in the canonical NCBI ordering of TTT, TTC, TTA, ... GGG.
        private const val AA_STANDARD =
            "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"
        private fun tableWithChanges(
            changes: Map<String, Char>,
        ): Map<String, Char> = tableOf(AA_STANDARD) + changes

        // NCBI table 2: Vertebrate mitochondrial.
        private val AA_VERTEBRATE_MITOCHONDRIAL = tableWithChanges(
            mapOf("ATA" to 'M', "TGA" to 'W', "AGA" to '*', "AGG" to '*'),
        )

        // NCBI table 4: Mold, Protozoan, Coelenterate, and Mycoplasma/Spiroplasma.
        // Difference from standard: TGA = Trp.
        private val AA_MOLD = tableWithChanges(
            mapOf("TGA" to 'W'),
        )
        // NCBI table 3: Yeast (Saccharomyces cerevisiae)
        // Differences from standard: CTN = Thr (not Leu), TGA = Trp
        private val AA_YEAST = tableWithChanges(
            mapOf(
                "ATA" to 'M',
                "TGA" to 'W',
                "CTT" to 'T',
                "CTC" to 'T',
                "CTA" to 'T',
                "CTG" to 'T',
            ),
        )
        // NCBI table 5: Invertebrate mitochondrial.
        private val AA_INVERTEBRATE = tableWithChanges(
            mapOf("ATA" to 'M', "TGA" to 'W', "AGA" to 'S', "AGG" to 'S'),
        )
        // NCBI table 9: Echinoderm and flatworm mitochondrial.
        private val AA_ECHINODERM = tableWithChanges(
            mapOf("AAA" to 'N', "TGA" to 'W', "AGA" to 'S', "AGG" to 'S'),
        )
        // NCBI table 10: Euplotid nuclear (ciliated protozoa).
        // Difference from standard: TGA = Cys.
        private val AA_EUPLOTID = tableWithChanges(mapOf("TGA" to 'C'))
        // NCBI table 12: Alternative yeast nuclear.
        // Difference from standard: CTG = Ser.
        private val AA_ALTERNATIVE_YEAST = tableWithChanges(mapOf("CTG" to 'S'))

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
            startCodons = setOf("TTG", "CTG", "ATG"),
        )

        /** Table 2: Vertebrate mitochondrial. */
        val VERTEBRATE_MITOCHONDRIAL = CodonTable(
            id = 2,
            displayName = "2 - Vertebrate Mitochondrial",
            codons = AA_VERTEBRATE_MITOCHONDRIAL,
            startCodons = setOf("ATA", "ATC", "ATT", "ATG", "GTG"),
        )

        /** Table 4: Mold, Protozoan, Coelenterate, and Mycoplasma/Spiroplasma. */
        val MOLD = CodonTable(
            id = 4,
            displayName = "4 - Mold / Protozoan / Mycoplasma",
            codons = AA_MOLD,
            startCodons = setOf("TTA", "TTG", "CTG", "ATT", "ATC", "ATA", "ATG", "GTG"),
        )

        /** Table 3: Yeast (Saccharomyces cerevisiae). CTN = Thr, TGA = Trp. */
        val YEAST = CodonTable(
            id = 3,
            displayName = "3 - Yeast (S. cerevisiae)",
            codons = AA_YEAST,
            startCodons = setOf("ATA", "ATG", "GTG"),
        )

        /** Table 5: Invertebrate mitochondrial. AGA/AGG = Ser, TGA = Trp. */
        val INVERTEBRATE = CodonTable(
            id = 5,
            displayName = "5 - Invertebrate Mitochondrial",
            codons = AA_INVERTEBRATE,
            startCodons = setOf("TTG", "ATT", "ATC", "ATA", "ATG", "GTG"),
        )

        /** Table 9: Echinoderm and flatworm mitochondrial. */
        val ECHINODERM = CodonTable(
            id = 9,
            displayName = "9 - Echinoderm / Flatworm Mitochondrial",
            codons = AA_ECHINODERM,
            startCodons = setOf("ATG", "GTG"),
        )

        /** Table 10: Euplotid nuclear (ciliated protozoa). */
        val EUPLOTID = CodonTable(
            id = 10,
            displayName = "10 - Euplotid Nuclear",
            codons = AA_EUPLOTID,
            startCodons = setOf("ATG"),
        )

        /** Table 11: Bacterial, archaeal, and plant plastid. */
        val BACTERIAL = CodonTable(
            id = 11,
            displayName = "11 - Bacterial / Archaeal / Plant Plastid",
            codons = tableOf(AA_STANDARD),
            startCodons = setOf("TTG", "CTG", "ATT", "ATC", "ATA", "ATG", "GTG"),
        )

        /** Table 12: Alternative yeast nuclear (CUG = Ser). */
        val ALTERNATIVE_YEAST = CodonTable(
            id = 12,
            displayName = "12 - Alternative Yeast Nuclear",
            codons = AA_ALTERNATIVE_YEAST,
            startCodons = setOf("CTG", "ATG"),
        )

        /** Compatibility alias for the former name of NCBI table 4. */
        @Deprecated("Use MOLD for NCBI table 4.")
        val SPIROPLASMA: CodonTable = MOLD

        /** The bundled tables. */
        val ALL = listOf(
            STANDARD,
            VERTEBRATE_MITOCHONDRIAL,
            YEAST,
            MOLD,
            INVERTEBRATE,
            ECHINODERM,
            EUPLOTID,
            BACTERIAL,
            ALTERNATIVE_YEAST,
        )

        /** The table with the NCBI [id], throwing [IllegalArgumentException] when it is not bundled. */
        fun byId(id: Int): CodonTable =
            ALL.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown genetic code table $id (available: ${ALL.map { it.id }})")
    }
}
