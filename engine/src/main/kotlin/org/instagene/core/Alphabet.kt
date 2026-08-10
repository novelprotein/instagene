package org.instagene.core

/**
 * IUPAC nucleotide alphabet: complements, degenerate-code expansion and matching.
 */
object Alphabet {

    /** Unambiguous DNA bases. */
    const val DNA_BASES = "ACGT"

    /** Unambiguous RNA bases. */
    const val RNA_BASES = "ACGU"

    /** Every character accepted in a nucleotide sequence, including gaps. */
    const val NUCLEOTIDES = "ACGTURYSWKMBDHVN-"

    /** Standard amino acids plus the common ambiguities B/Z/J, X and the stop codon. */
    const val AMINO_ACIDS = "ACDEFGHIKLMNPQRSTVWYBZX*-"

    private val DNA_COMPLEMENT = mapOf(
        'A' to 'T', 'C' to 'G', 'G' to 'C', 'T' to 'A', 'U' to 'A',
        'R' to 'Y', 'Y' to 'R', 'S' to 'S', 'W' to 'W', 'K' to 'M', 'M' to 'K',
        'B' to 'V', 'V' to 'B', 'D' to 'H', 'H' to 'D', 'N' to 'N', '-' to '-',
    )

    private val RNA_COMPLEMENT = DNA_COMPLEMENT + mapOf('A' to 'U', 'T' to 'A', 'U' to 'A')

    /** Which concrete bases each IUPAC code stands for. */
    private val EXPANSION = mapOf(
        'A' to "A", 'C' to "C", 'G' to "G", 'T' to "T", 'U' to "T",
        'R' to "AG", 'Y' to "CT", 'S' to "CG", 'W' to "AT", 'K' to "GT", 'M' to "AC",
        'B' to "CGT", 'D' to "AGT", 'H' to "ACT", 'V' to "ACG", 'N' to "ACGT",
    )

    /** True when [c] is an accepted nucleotide character (any IUPAC code or a gap), case-insensitively. */
    fun isNucleotide(c: Char): Boolean = NUCLEOTIDES.indexOf(c.uppercaseChar()) >= 0

    /** True when [c] is an accepted amino-acid character (including B/Z/J, X and the stop symbol). */
    fun isAminoAcid(c: Char): Boolean = AMINO_ACIDS.indexOf(c.uppercaseChar()) >= 0

    /** The complementary base of [c] under [kind], preserving case; unknown characters become 'N'. */
    fun complement(c: Char, kind: SeqKind): Char {
        val upper = c.uppercaseChar()
        val table = if (kind == SeqKind.RNA) RNA_COMPLEMENT else DNA_COMPLEMENT
        val comp = table[upper] ?: 'N'
        return if (c.isLowerCase()) comp.lowercaseChar() else comp
    }

    /**
     * True when [code] (a possibly degenerate IUPAC symbol, e.g. from an enzyme
     * recognition site) can stand for the concrete base [base].
     */
    fun matches(code: Char, base: Char): Boolean {
        val codeSet = EXPANSION[code.uppercaseChar()] ?: return false
        val baseChar = base.uppercaseChar().let { if (it == 'U') 'T' else it }
        // A degenerate base in the subject matches if its own expansion overlaps.
        // An 'N' in the subject is unknown: it never confirms a specific code.
        val baseSet = if (baseChar == 'N') "" else EXPANSION[baseChar] ?: return false
        return baseSet.any { it in codeSet }
    }

    /** The concrete bases [symbol] stands for (uppercase), or null when unknown. */
    fun expansion(symbol: Char): String? = EXPANSION[symbol.uppercaseChar()]

    /** Strips whitespace, digits and FASTA-style noise, leaving sequence characters. */
    fun clean(raw: String): String = raw.filter { !it.isWhitespace() && !it.isDigit() }

    /** Returns the offending characters in [seq], or an empty set when it is valid. */
    fun invalidCharacters(seq: String): Set<Char> =
        seq.filterNot { isNucleotide(it) }.toSet()

    /**
     * Returns the characters in [seq] that [kind]'s alphabet rejects, or an
     * empty set when the whole sequence is valid: nucleotides are checked
     * against [NUCLEOTIDES], amino acids against [AMINO_ACIDS].
     */
    fun invalidCharacters(seq: String, kind: SeqKind): Set<Char> {
        val allowed = if (kind == SeqKind.PROTEIN) AMINO_ACIDS else NUCLEOTIDES
        return seq.filterNot { allowed.indexOf(it.uppercaseChar()) >= 0 }.toSet()
    }
}
