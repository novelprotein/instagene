package org.instagene.core

/** Search interpretation used by the sequence editor and CLI. */
enum class SearchMode { DNA_DEGENERATE, AMINO_ACID, LITERAL }

data class SearchRequest(
    val pattern: String,
    val mode: SearchMode = SearchMode.DNA_DEGENERATE,
    val bothStrands: Boolean = true,
    val caseSensitive: Boolean = false,
    val maxMismatches: Int = 0,
    val threePrimeExact: Int = 0,
)

data class SequenceMatch(
    val start: Int,
    val end: Int,
    val strand: Strand,
    val mismatches: Int = 0,
    val matched: String,
    val frame: Int? = null,
) {
    val length: Int get() = end - start
}

/** ApE-style sequence search, including degenerate bases, mismatches and translations. */
object AdvancedSearch {
    fun find(seq: Seq, request: SearchRequest): List<SequenceMatch> {
        require(request.maxMismatches >= 0) { "maxMismatches must not be negative" }
        require(request.threePrimeExact >= 0) { "threePrimeExact must not be negative" }
        if (request.pattern.isBlank()) return emptyList()
        return when (request.mode) {
            SearchMode.AMINO_ACID -> findAminoAcids(seq, request)
            SearchMode.DNA_DEGENERATE, SearchMode.LITERAL -> findNucleotides(seq, request)
        }
    }

    private fun findNucleotides(seq: Seq, request: SearchRequest): List<SequenceMatch> {
        val pattern = request.pattern.trim()
        val strands = if (request.bothStrands && seq.kind != SeqKind.PROTEIN) {
            listOf(Strand.FORWARD, Strand.REVERSE)
        } else listOf(Strand.FORWARD)
        fun searchStrand(strand: Strand): List<SequenceMatch> {
            val oriented = if (strand == Strand.FORWARD) pattern else
                Alphabet.reverseComplement(pattern, seq.kind)
            val maxStart = if (seq.isCircular) seq.length - 1 else seq.length - oriented.length
            if (maxStart < 0) return emptyList()
            // Bitmap gate: precompute which bases the first pattern character can match.
            val firstChar = oriented[0].uppercaseChar()
            val firstAllowed = BooleanArray(256).also { arr ->
                if (request.mode == SearchMode.LITERAL || request.caseSensitive) {
                    arr[firstChar.code] = true
                } else {
                    val expansion = Alphabet.expansion(firstChar)
                    if (expansion != null) {
                        for (c in expansion) arr[c.uppercaseChar().code] = true
                    } else {
                        arr[firstChar.code] = true
                    }
                }
            }
            val out = ArrayList<SequenceMatch>()
            for (start in 0..maxStart) {
                val firstBase = seq.bases[start].uppercaseChar().code
                if (firstBase !in firstAllowed.indices || !firstAllowed[firstBase]) continue
                val target = seq.sub(start, start + oriented.length)
                if (target.length != oriented.length) continue
                val mismatch = mismatchCount(target, oriented, request)
                if (mismatch <= request.maxMismatches && exactThreePrime(target, oriented, request)) {
                    out += SequenceMatch(start, start + oriented.length, strand, mismatch, target)
                }
            }
            return out
        }
        val results = if (strands.size > 1) {
            Parallel.map(strands) { searchStrand(it) }.flatten()
        } else {
            searchStrand(strands.first())
        }
        return results.distinctBy { Triple(it.start, it.end, it.strand) }
            .sortedWith(compareBy({ it.start }, { it.strand.sign }))
    }

    private fun findAminoAcids(seq: Seq, request: SearchRequest): List<SequenceMatch> {
        val peptide = request.pattern.uppercase()
        val strands = if (request.bothStrands) listOf(Strand.FORWARD, Strand.REVERSE) else listOf(Strand.FORWARD)
        fun searchFrame(strand: Strand, frame: Int): List<SequenceMatch> {
            val oriented = if (strand == Strand.FORWARD) seq else seq.reverseComplement()
            val protein = SeqOps.translateBases(oriented.bases, frame)
            val out = ArrayList<SequenceMatch>()
            var from = 0
            while (true) {
                val hit = protein.indexOf(peptide, from)
                if (hit < 0) break
                val ntStart = frame + hit * 3
                val ntEnd = ntStart + peptide.length * 3
                val forwardStart = if (strand == Strand.FORWARD) ntStart else seq.length - ntEnd
                val forwardEnd = if (strand == Strand.FORWARD) ntEnd else seq.length - ntStart
                out += SequenceMatch(forwardStart, forwardEnd, strand, 0, peptide, frame)
                from = hit + 1
            }
            return out
        }
        val tasks = strands.flatMap { strand -> (0..2).map { frame -> strand to frame } }
        val results = if (tasks.size > 2) {
            Parallel.map(tasks) { (strand, frame) -> searchFrame(strand, frame) }.flatten()
        } else {
            tasks.flatMap { (strand, frame) -> searchFrame(strand, frame) }
        }
        return results.sortedWith(compareBy({ it.start }, { it.strand.sign }, { it.frame ?: 0 }))
    }

    private fun mismatchCount(target: String, pattern: String, request: SearchRequest): Int =
        target.indices.count { !matches(pattern[it], target[it], request) }

    private fun exactThreePrime(target: String, pattern: String, request: SearchRequest): Boolean {
        if (request.threePrimeExact == 0) return true
        val count = request.threePrimeExact.coerceAtMost(pattern.length)
        val start = pattern.length - count
        return (start until pattern.length).all { matches(pattern[it], target[it], request, literal = true) }
    }

    private fun matches(pattern: Char, target: Char, request: SearchRequest, literal: Boolean = false): Boolean {
        if (request.caseSensitive && pattern != target) return false
        if (!request.caseSensitive && pattern.uppercaseChar() == target.uppercaseChar()) return true
        if (request.mode == SearchMode.LITERAL || literal) return false
        return Alphabet.matches(pattern.uppercaseChar(), target.uppercaseChar())
    }
}
