package org.instagene.core

data class SecondaryStructureResult(
    val sequence: String,
    val dotBracket: String,
    val pairedBases: Int,
    val estimatedDeltaG: Double?,
    val algorithm: String,
)

/** Nussinov folding with a ViennaRNA adapter when RNAfold is installed. */
object SecondaryStructure {
    fun predict(seq: Seq, preferVienna: Boolean = true): SecondaryStructureResult {
        require(seq.kind != SeqKind.PROTEIN) { "Secondary structure requires DNA or RNA" }
        require(seq.length <= 5_000) { "Interactive secondary-structure prediction is limited to 5,000 bases" }
        val tool = ExternalTools.CATALOG.firstOrNull { it.id == "rnafold" }
        if (seq.kind == SeqKind.RNA && preferVienna && tool != null && ExternalTools.isAvailable(tool)) {
            val result = ExternalTools.run(tool, seq.copy(kind = SeqKind.RNA, bases = seq.bases.replace('T', 'U')))
            if (result.succeeded) parseVienna(result.stdout, seq.bases)?.let { return it }
        }
        require(seq.length <= 300) {
            "Built-in secondary-structure prediction is limited to 300 bases; install ViennaRNA RNAfold for longer sequences."
        }
        return nussinov(seq.bases.uppercase(), seq.kind == SeqKind.RNA)
    }

    private fun nussinov(sequence: String, rna: Boolean): SecondaryStructureResult {
        val n = sequence.length
        if (n == 0) return SecondaryStructureResult("", "", 0, null, "Nussinov pairing heuristic")
        val score = Array(n) { IntArray(n) }
        for (span in 4 until n) {
            for (i in 0 until n - span) {
                val j = i + span
                var best = maxOf(score[i + 1][j], score[i][j - 1])
                if (pairs(sequence[i], sequence[j], rna)) best = maxOf(best, score.getOrZero(i + 1, j - 1) + 1)
                for (k in i + 1 until j) best = maxOf(best, score.getOrZero(i, k) + score.getOrZero(k + 1, j))
                score[i][j] = best
            }
        }
        val structure = CharArray(n) { '.' }
        fun trace(i: Int, j: Int) {
            if (i >= j || i !in 0 until n || j !in 0 until n) return
            when {
                score[i][j] == score.getOrZero(i + 1, j) -> trace(i + 1, j)
                score[i][j] == score.getOrZero(i, j - 1) -> trace(i, j - 1)
                pairs(sequence[i], sequence[j], rna) && score[i][j] == score.getOrZero(i + 1, j - 1) + 1 -> {
                    structure[i] = '('; structure[j] = ')'; trace(i + 1, j - 1)
                }
                else -> for (k in i + 1 until j) {
                    if (score[i][j] == score.getOrZero(i, k) + score.getOrZero(k + 1, j)) {
                        trace(i, k); trace(k + 1, j); break
                    }
                }
            }
        }
        trace(0, n - 1)
        val paired = structure.count { it == '(' }
        return SecondaryStructureResult(sequence, structure.concatToString(), paired, null, "Nussinov ${if (rna) "RNA" else "DNA"} pairing heuristic (no energy model)")
    }

    /** Parses an RNAfold structure/energy line; absent or malformed energy remains unavailable. */
    fun parseVienna(output: String, original: String): SecondaryStructureResult? {
        val pattern = Regex("""^([.()]+)\s+\(\s*(-?\d+(?:\.\d+)?)\s*\)\s*$""")
        val match = output.lineSequence().mapNotNull { pattern.matchEntire(it.trim()) }
            .firstOrNull { it.groupValues[1].length == original.length } ?: return null
        val structure = match.groupValues[1]
        val energy = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return SecondaryStructureResult(original, structure, structure.count { it == '(' }, energy, "ViennaRNA RNAfold")
    }

    private fun pairs(a: Char, b: Char, rna: Boolean): Boolean = when (a) {
        'A' -> b == if (rna) 'U' else 'T'
        'U' -> rna && (b == 'A' || b == 'G')
        'T' -> !rna && b == 'A'
        'G' -> b == 'C' || (rna && b == 'U')
        'C' -> b == 'G'
        else -> false
    }
    private fun Array<IntArray>.getOrZero(i: Int, j: Int): Int = if (i in indices && j in this[i].indices && i <= j) this[i][j] else 0
}
