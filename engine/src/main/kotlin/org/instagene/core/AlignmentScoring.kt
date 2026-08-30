package org.instagene.core

/** Residue-scoring presets used by pairwise and multiple alignment. */
enum class AlignmentScoring {
    CUSTOM,
    AUTO,
    NUCLEOTIDE,
    BLOSUM62,
    PAM250,
}

internal object AlignmentScores {
    private const val BLOSUM_ALPHABET = "ARNDCQEGHILKMFPSTWYV"
    private val blosum62 = matrix(
        """
        4 -1 -2 -2 0 -1 -1 0 -2 -1 -1 -1 -1 -2 -1 1 0 -3 -2 0
        -1 5 0 -2 -3 1 0 -2 0 -3 -2 2 -1 -3 -2 -1 -1 -3 -2 -3
        -2 0 6 1 -3 0 0 0 1 -3 -3 0 -2 -3 -2 1 0 -4 -2 -3
        -2 -2 1 6 -3 0 2 -1 -1 -3 -4 -1 -3 -3 -1 0 -1 -4 -3 -3
        0 -3 -3 -3 9 -3 -4 -3 -3 -1 -1 -3 -1 -3 -3 -1 -1 -1 -2 -1
        -1 1 0 0 -3 5 2 -2 0 -3 -2 1 0 -3 -1 0 -1 -2 -1 -2
        -1 0 0 2 -4 2 5 -2 0 -3 -3 1 -2 -3 -1 0 -1 -3 -2 -2
        0 -2 0 -1 -3 -2 -2 6 -2 -4 -3 -2 -3 -3 -2 0 -2 -4 -3 -3
        -2 0 1 -1 -3 0 0 -2 8 -3 -3 -1 -2 -1 -2 -1 -1 -2 2 -3
        -1 -3 -3 -3 -1 -3 -3 -4 -3 4 2 -3 1 0 -3 -2 -1 -3 -1 3
        -1 -2 -3 -4 -1 -2 -3 -3 -3 2 4 -2 2 0 -3 -2 -1 -2 -1 1
        -1 2 0 -1 -3 1 1 -2 -1 -3 -2 5 -1 -3 -1 0 -1 -3 -2 -2
        -1 -1 -2 -3 -1 0 -2 -3 -2 1 2 -1 5 0 -2 -1 -1 -1 -1 1
        -2 -3 -3 -3 -3 -3 -3 -3 -1 0 0 -3 0 6 -4 -2 -2 1 3 -1
        -1 -2 -2 -1 -3 -1 -1 -2 -2 -3 -3 -1 -2 -4 7 -1 -1 -4 -3 -2
        1 -1 1 0 -1 0 0 0 -1 -2 -2 0 -1 -2 -1 4 1 -3 -2 -2
        0 -1 0 -1 -1 -1 -1 -2 -1 -1 -1 -1 -1 -2 -1 1 5 -2 -2 0
        -3 -3 -4 -4 -2 -2 -3 -4 -2 -3 -2 -3 -1 1 -4 -3 -2 11 2 -3
        -2 -2 -2 -3 -2 -1 -2 -3 2 -1 -1 -2 -1 3 -3 -2 -2 2 7 -1
        0 -3 -3 -3 -1 -2 -2 -3 -3 3 1 -2 1 -1 -2 -2 0 -3 -1 4
        """.trimIndent(),
    )

    /** A conservative PAM-like preset for protein alignments. */
    private val pam250 = BLOSUM_ALPHABET.associateWith { row ->
        BLOSUM_ALPHABET.associateWith { column ->
            when (row) {
                column -> 3.0
                in "STNQ" if column in "STNQ" -> 1.0
                in "AILMV" if column in "AILMV" -> 1.0
                in "DE" if column in "DE" -> 2.0
                in "KR" if column in "KR" -> 2.0
                in "FWY" if column in "FWY" -> 2.0
                else -> -2.0
            }
        }
    }

    private val nucleotideSets = mapOf(
        'A' to setOf('A'), 'C' to setOf('C'), 'G' to setOf('G'), 'T' to setOf('T'), 'U' to setOf('T'),
        'R' to setOf('A', 'G'), 'Y' to setOf('C', 'T'), 'S' to setOf('G', 'C'), 'W' to setOf('A', 'T'),
        'K' to setOf('G', 'T'), 'M' to setOf('A', 'C'), 'B' to setOf('C', 'G', 'T'),
        'D' to setOf('A', 'G', 'T'), 'H' to setOf('A', 'C', 'T'), 'V' to setOf('A', 'C', 'G'),
        'N' to setOf('A', 'C', 'G', 'T'),
    )

    fun score(
        kind: SeqKind,
        left: Char,
        right: Char,
        preset: AlignmentScoring,
        customMatch: Double = 1.0,
        customMismatch: Double = -0.1,
    ): Double {
        if (left == '-' || right == '-') return 0.0
        val resolved = when (preset) {
            AlignmentScoring.CUSTOM -> null
            AlignmentScoring.AUTO -> if (kind == SeqKind.PROTEIN) AlignmentScoring.BLOSUM62 else AlignmentScoring.NUCLEOTIDE
            else -> preset
        }
        return when (resolved) {
            null -> if (left == right) customMatch else customMismatch
            AlignmentScoring.NUCLEOTIDE -> {
                val l = nucleotideSets[left.uppercaseChar()]
                val r = nucleotideSets[right.uppercaseChar()]
                if (l != null && r != null && l.intersect(r).isNotEmpty()) 1.0 else -1.0
            }
            AlignmentScoring.BLOSUM62 -> matrixScore(blosum62, left, right)
            AlignmentScoring.PAM250 -> pam250[left.uppercaseChar()]?.get(right.uppercaseChar()) ?: -2.0
            AlignmentScoring.AUTO, AlignmentScoring.CUSTOM -> error("Unresolved alignment scoring preset")
        }
    }

    fun compatible(reference: Seq, query: Seq): Boolean = when {
        reference.kind == SeqKind.PROTEIN || query.kind == SeqKind.PROTEIN ->
            reference.kind == SeqKind.PROTEIN && query.kind == SeqKind.PROTEIN
        else -> true
    }

    private fun matrix(values: String): Array<DoubleArray> {
        val numbers = values.split(Regex("\\s+")).map(String::toDouble)
        require(numbers.size == BLOSUM_ALPHABET.length * BLOSUM_ALPHABET.length)
        return Array(BLOSUM_ALPHABET.length) { row ->
            DoubleArray(BLOSUM_ALPHABET.length) { column -> numbers[row * BLOSUM_ALPHABET.length + column] }
        }
    }

    private fun matrixScore(matrix: Array<DoubleArray>, left: Char, right: Char): Double {
        val i = BLOSUM_ALPHABET.indexOf(left.uppercaseChar())
        val j = BLOSUM_ALPHABET.indexOf(right.uppercaseChar())
        return if (i >= 0 && j >= 0) matrix[i][j] else if (left.uppercaseChar() == right.uppercaseChar()) 1.0 else -2.0
    }
}
