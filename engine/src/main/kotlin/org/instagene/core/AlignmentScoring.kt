package org.instagene.core

/** Residue-scoring presets used by pairwise and multiple alignment. */
enum class AlignmentScoring { CUSTOM, AUTO, NUCLEOTIDE, BLOSUM62, PAM250 }

object AlignmentScores {
    private const val ALPHABET = "ARNDCQEGHILKMFPSTWYV"
    private val blosum62 = matrix(
        """
        4 -1 -2 -2 0 -1 -1 0 -2 -1 -1 -1 -1 -2 -1 1 0 -3 -2 0
        -1 5 0 -2 -3 1 0 -2 0 -3 -2 2 -1 -3 -2 -1 -1 -3 -2 -3
        -2 0 6 1 -3 0 0 0 1 -3 -3 0 -2 -3 -2 1 0 -4 -2 -3
        -2 -2 1 6 -3 0 2 -1 -1 -3 -4 -1 -3 -3 -1 0 -1 -4 -3 -3
        0 -3 -3 -3 9 -3 -4 -3 -3 -1 -1 -3 -1 -2 -3 -1 -1 -2 -2 -1
        -1 1 0 0 -3 5 2 -2 0 -3 -2 1 0 -3 -1 0 -1 -2 -1 -2
        -1 0 0 2 -4 2 5 -2 0 -3 -3 1 -2 -3 -1 0 -1 -3 -2 -2
        0 -2 0 -1 -3 -2 -2 6 -2 -4 -4 -2 -3 -3 -2 0 -2 -2 -3 -3
        -2 0 1 -1 -3 0 0 -2 8 -3 -3 -1 -2 -1 -2 -1 -2 -2 2 -3
        -1 -3 -3 -3 -1 -3 -3 -4 -3 4 2 -3 1 0 -3 -2 -1 -3 -1 3
        -1 -2 -3 -4 -1 -2 -3 -4 -3 2 4 -2 2 0 -3 -2 -1 -2 -1 1
        -1 2 0 -1 -3 1 1 -2 -1 -3 -2 5 -1 -3 -1 0 -1 -3 -2 -2
        -1 -1 -2 -3 -1 0 -2 -3 -2 1 2 -1 5 0 -2 -1 -1 -1 -1 1
        -2 -3 -3 -3 -2 -3 -3 -3 -1 0 0 -3 0 6 -4 -2 -2 1 3 -1
        -1 -2 -2 -1 -3 -1 -1 -2 -2 -3 -3 -1 -2 -4 7 -1 -1 -4 -3 -2
        1 -1 1 0 -1 0 0 0 -1 -2 -2 0 -1 -2 -1 4 1 -3 -2 -2
        0 -1 0 -1 -1 -1 -1 -2 -2 -1 -1 -1 -1 -2 -1 1 5 -2 -2 0
        -3 -3 -4 -4 -2 -2 -3 -2 -2 -3 -2 -3 -1 1 -4 -3 -2 11 2 -3
        -2 -2 -2 -3 -2 -1 -2 -3 2 -1 -1 -2 -1 3 -3 -2 -2 2 7 -1
        0 -3 -3 -3 -1 -2 -2 -3 -3 3 1 -2 1 -1 -2 -2 0 -3 -1 4
        """.trimIndent(),
    )

    /** PAM250, Dayhoff et al., in the order ARNDCQEGHILKMFPSTWYV. */
    private val pam250 = matrix(
        """
        2 -2 0 0 -2 0 0 1 -1 -1 -2 -1 -1 -3 1 1 1 -6 -3 0
        -2 6 0 -1 -4 1 -1 -3 2 -2 -3 3 0 -4 0 0 -1 2 -4 -2
        0 0 2 2 -4 1 1 0 2 -2 -3 1 -2 -3 0 1 0 -4 -2 -2
        0 -1 2 4 -5 2 3 1 1 -2 -4 0 -3 -6 -1 0 0 -7 -4 -2
        -2 -4 -4 -5 12 -5 -5 -3 -3 -2 -6 -5 -5 -4 -3 0 -2 -8 0 -2
        0 1 1 2 -5 4 2 -1 3 -2 -2 1 -1 -5 0 -1 -1 -5 -4 -2
        0 -1 1 3 -5 2 4 0 1 -2 -3 0 -2 -5 -1 0 0 -7 -4 -2
        1 -3 0 1 -3 -1 0 5 -2 -3 -4 -2 -3 -5 0 1 0 -7 -5 -1
        -1 2 2 1 -3 3 1 -2 6 -2 -2 0 -2 -2 0 -1 -1 -3 0 -2
        -1 -2 -2 -2 -2 -2 -2 -3 -2 5 2 -2 2 1 -2 -1 0 -5 -1 4
        -2 -3 -3 -4 -6 -2 -3 -4 -2 2 6 -3 4 2 -3 -3 -2 -2 -1 2
        -1 3 1 0 -5 1 0 -2 0 -2 -3 5 0 -5 -1 0 0 -3 -4 -2
        -1 0 -2 -3 -5 -1 -2 -3 -2 2 4 0 6 0 -2 -2 -1 -4 -2 2
        -3 -4 -3 -6 -4 -5 -5 -5 -2 1 2 -5 0 9 -5 -3 -3 0 7 -1
        1 0 0 -1 -3 0 -1 0 0 -2 -3 -1 -2 -5 6 1 0 -6 -5 -1
        1 0 1 0 0 -1 0 1 -1 -1 -3 0 -2 -3 1 2 1 -2 -3 -1
        1 -1 0 0 -2 -1 0 0 -1 0 -2 0 -1 -3 0 1 3 -5 -3 0
        -6 2 -4 -7 -8 -5 -7 -7 -3 -5 -2 -3 -4 0 -6 -2 -5 17 0 -6
        -3 -4 -2 -4 0 -4 -4 -5 0 -1 -1 -4 -2 7 -5 -3 -3 0 10 -2
        0 -2 -2 -2 -2 -2 -2 -1 -2 4 2 -2 2 -1 -1 -1 0 -6 -2 4
        """.trimIndent(),
    )

    private val nucleotideSets = mapOf(
        'A' to setOf('A'), 'C' to setOf('C'), 'G' to setOf('G'), 'T' to setOf('T'), 'U' to setOf('T'),
        'R' to setOf('A', 'G'), 'Y' to setOf('C', 'T'), 'S' to setOf('G', 'C'), 'W' to setOf('A', 'T'),
        'K' to setOf('G', 'T'), 'M' to setOf('A', 'C'), 'B' to setOf('C', 'G', 'T'),
        'D' to setOf('A', 'G', 'T'), 'H' to setOf('A', 'C', 'T'), 'V' to setOf('A', 'C', 'G'),
        'N' to setOf('A', 'C', 'G', 'T'),
    )

    fun score(kind: SeqKind, left: Char, right: Char, preset: AlignmentScoring, customMatch: Double = 1.0, customMismatch: Double = -0.1): Double {
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
            AlignmentScoring.PAM250 -> matrixScore(pam250, left, right)
            AlignmentScoring.AUTO, AlignmentScoring.CUSTOM -> error("Unresolved alignment scoring preset")
        }
    }

    fun compatible(reference: Seq, query: Seq): Boolean =
        if (reference.kind == SeqKind.PROTEIN || query.kind == SeqKind.PROTEIN) {
            reference.kind == SeqKind.PROTEIN && query.kind == SeqKind.PROTEIN
        } else true

    private fun matrix(values: String): Array<DoubleArray> {
        val numbers = values.trim().split(Regex("\\s+")).map(String::toDouble)
        require(numbers.size == ALPHABET.length * ALPHABET.length)
        return Array(ALPHABET.length) { row ->
            DoubleArray(ALPHABET.length) { column -> numbers[row * ALPHABET.length + column] }
        }
    }

    private fun matrixScore(matrix: Array<DoubleArray>, left: Char, right: Char): Double {
        val i = ALPHABET.indexOf(left.uppercaseChar())
        val j = ALPHABET.indexOf(right.uppercaseChar())
        return if (i >= 0 && j >= 0) matrix[i][j] else if (left.uppercaseChar() == right.uppercaseChar()) 1.0 else -2.0
    }
}
