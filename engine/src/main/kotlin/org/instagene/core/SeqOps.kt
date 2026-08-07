package org.instagene.core

import kotlin.math.log10
import kotlin.math.roundToInt

/** An open reading frame located by [SeqOps.findOrfs]. */
data class Orf(
    val start: Int,
    val end: Int,
    val strand: Strand,
    val frame: Int,
    val protein: String,
) {
    val lengthNt: Int get() = end - start
    val lengthAa: Int get() = protein.length
}

/** Analysis and conversion operations over [Seq]. All are pure. */
object SeqOps {

    // ---------------------------------------------------------------- conversion

    /** DNA -> RNA (T becomes U). Idempotent on RNA. */
    fun transcribe(seq: Seq): Seq = seq.copy(
        bases = seq.bases.map { if (it == 'T') 'U' else if (it == 't') 'u' else it }.joinToString(""),
        kind = SeqKind.RNA,
    )

    /** RNA -> DNA (U becomes T). Idempotent on DNA. */
    fun backTranscribe(seq: Seq): Seq = seq.copy(
        bases = seq.bases.map { if (it == 'U') 'T' else if (it == 'u') 't' else it }.joinToString(""),
        kind = SeqKind.DNA,
    )

    /**
     * Translates [seq] in reading [frame] (0, 1 or 2).
     * A trailing partial codon is ignored; stop codons appear as `*`.
     */
    fun translate(
        seq: Seq,
        frame: Int = 0,
        table: CodonTable = CodonTable.STANDARD,
        stopAtFirstStop: Boolean = false,
    ): Seq {
        require(frame in 0..2) { "Frame must be 0, 1 or 2 (got $frame)" }
        val protein = translateBases(seq.bases, frame, table, stopAtFirstStop)
        return Seq(
            name = "${seq.name}_frame${frame + 1}_protein",
            bases = protein,
            kind = SeqKind.PROTEIN,
            topology = Topology.LINEAR,
            description = "Translation of ${seq.name} (frame ${frame + 1}, ${table.displayName})",
        )
    }

    fun translateBases(
        bases: String,
        frame: Int = 0,
        table: CodonTable = CodonTable.STANDARD,
        stopAtFirstStop: Boolean = false,
    ): String {
        val sb = StringBuilder()
        var i = frame
        while (i + 3 <= bases.length) {
            val aa = table.translate(bases.substring(i, i + 3))
            if (aa == '*' && stopAtFirstStop) break
            sb.append(aa)
            i += 3
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- statistics

    /** GC content as a percentage; degenerate S counts as GC, N does not. */
    fun gcContent(bases: String): Double {
        if (bases.isEmpty()) return 0.0
        val gc = bases.count { it.uppercaseChar() in "GCS" }
        return gc * 100.0 / bases.length
    }

    fun gcContent(seq: Seq): Double = gcContent(seq.bases)

    fun baseCounts(bases: String): Map<Char, Int> =
        bases.uppercase().groupingBy { it }.eachCount().toSortedMap()

    /**
     * Melting temperature in degrees C.
     *
     * Uses the Wallace rule below 14 nt and the salt-adjusted GC formula above it —
     * both are the rules of thumb primer design actually uses at the bench.
     */
    fun meltingTemp(bases: String, saltMolar: Double = 0.05): Double {
        val clean = bases.uppercase().filter { it in "ACGT" }
        val n = clean.length
        if (n == 0) return 0.0
        val at = clean.count { it == 'A' || it == 'T' }
        val gc = n - at
        return if (n < 14) {
            2.0 * at + 4.0 * gc
        } else {
            81.5 + 16.6 * log10(saltMolar) + 41.0 * gc / n - 600.0 / n
        }
    }

    fun molecularWeightDaltons(seq: Seq): Double {
        val weights = when (seq.kind) {
            SeqKind.RNA -> mapOf('A' to 329.2, 'U' to 306.2, 'C' to 305.2, 'G' to 345.2)
            else -> mapOf('A' to 313.2, 'T' to 304.2, 'C' to 289.2, 'G' to 329.2)
        }
        val sum = seq.bases.uppercase().sumOf { weights[it] ?: 0.0 }
        // Linear single strands carry a free 5' phosphate correction.
        return if (sum == 0.0) 0.0 else sum - 61.96
    }

    fun codonUsage(bases: String, frame: Int = 0): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        var i = frame
        while (i + 3 <= bases.length) {
            val codon = bases.substring(i, i + 3).uppercase()
            counts[codon] = (counts[codon] ?: 0) + 1
            i += 3
        }
        return counts
    }

    // ---------------------------------------------------------------- ORFs

    /**
     * Finds open reading frames on both strands.
     *
     * Coordinates are always reported against the forward strand of [seq].
     * For circular sequences the search runs over a doubled sequence so ORFs
     * spanning the origin are found once each.
     */
    fun findOrfs(
        seq: Seq,
        minAminoAcids: Int = 30,
        table: CodonTable = CodonTable.STANDARD,
        bothStrands: Boolean = true,
    ): List<Orf> {
        val dna = backTranscribe(seq)
        val out = ArrayList<Orf>()
        out += scanStrand(dna, Strand.FORWARD, minAminoAcids, table)
        if (bothStrands) out += scanStrand(dna, Strand.REVERSE, minAminoAcids, table)
        return out.sortedWith(compareByDescending<Orf> { it.lengthNt }.thenBy { it.start })
    }

    private fun scanStrand(seq: Seq, strand: Strand, minAa: Int, table: CodonTable): List<Orf> {
        val len = seq.length
        if (len < 6) return emptyList()
        val working = if (strand == Strand.FORWARD) seq.bases else seq.reverseComplement().bases
        // Circular sequences are scanned over one extra copy so origin-spanning ORFs are visible.
        val search = if (seq.isCircular) working + working else working
        val limit = if (seq.isCircular) len else search.length

        val orfs = ArrayList<Orf>()
        for (frame in 0..2) {
            var i = frame
            while (i + 3 <= search.length && i < limit) {
                val codon = search.substring(i, i + 3).uppercase()
                if (table.isStart(codon)) {
                    var j = i
                    var protein: String? = null
                    while (j + 3 <= search.length) {
                        val c = search.substring(j, j + 3).uppercase()
                        if (table.isStop(c)) {
                            protein = translateBases(search.substring(i, j), 0, table)
                            j += 3
                            break
                        }
                        j += 3
                    }
                    if (protein != null && protein.length >= minAa) {
                        orfs += toForwardCoords(i, j, len, strand, frame, protein, seq.isCircular)
                        i = j
                        continue
                    }
                }
                i += 3
            }
        }
        return orfs
    }

    private fun toForwardCoords(
        start: Int,
        end: Int,
        len: Int,
        strand: Strand,
        frame: Int,
        protein: String,
        circular: Boolean,
    ): Orf {
        // Reverse-strand hits are reported against forward coordinates.
        val forwardStart = if (strand == Strand.FORWARD) start else len - end
        val normStart = if (circular) Math.floorMod(forwardStart, len) else forwardStart.coerceAtLeast(0)
        return Orf(normStart, normStart + (end - start), strand, frame, protein)
    }

    // ---------------------------------------------------------------- searching

    /**
     * All positions where [pattern] (IUPAC-aware) occurs. When [bothStrands] is
     * set, reverse-strand hits are reported at their forward-strand start.
     */
    fun find(seq: Seq, pattern: String, bothStrands: Boolean = false): List<Pair<Int, Strand>> {
        val hits = ArrayList<Pair<Int, Strand>>()
        val p = pattern.uppercase()
        if (p.isEmpty() || seq.length == 0) return hits
        val scanLimit = if (seq.isCircular) seq.length else seq.length - p.length + 1
        for (i in 0 until scanLimit.coerceAtLeast(0)) {
            val window = seq.sub(i, i + p.length)
            if (window.length < p.length) continue
            if (p.indices.all { Alphabet.matches(p[it], window[it]) }) hits += i to Strand.FORWARD
        }
        if (bothStrands) {
            val rcPattern = p.reversed().map { Alphabet.complement(it, SeqKind.DNA) }.joinToString("")
            for (i in 0 until scanLimit.coerceAtLeast(0)) {
                val window = seq.sub(i, i + p.length)
                if (window.length < p.length) continue
                if (p.indices.all { Alphabet.matches(rcPattern[it], window[it]) }) {
                    hits += i to Strand.REVERSE
                }
            }
        }
        return hits.sortedBy { it.first }
    }

    // ---------------------------------------------------------------- primers

    data class Primer(val name: String, val bases: String, val tm: Double, val gc: Double) {
        override fun toString(): String =
            "$name  ${bases}  (${bases.length} nt, Tm ${(tm * 10).roundToInt() / 10.0} C, GC ${(gc * 10).roundToInt() / 10.0}%)"
    }

    /**
     * Picks a forward/reverse primer pair amplifying `[start, end)`, choosing the
     * length in [lengthRange] whose Tm is closest to [targetTm].
     */
    fun designPrimers(
        seq: Seq,
        start: Int,
        end: Int,
        targetTm: Double = 60.0,
        lengthRange: IntRange = 18..30,
    ): Pair<Primer, Primer> {
        require(end > start) { "Amplicon end must follow its start" }
        val fwd = bestPrimer("${seq.name}_F", seq.sub(start, end.coerceAtMost(start + lengthRange.last)), targetTm, lengthRange)
        val revTemplate = seq.subSeq(maxOf(0, end - lengthRange.last), end).reverseComplement()
        val rev = bestPrimer("${seq.name}_R", revTemplate.bases, targetTm, lengthRange)
        return fwd to rev
    }

    private fun bestPrimer(name: String, template: String, targetTm: Double, lengths: IntRange): Primer {
        val candidates = lengths.filter { it <= template.length }
        val len = candidates.minByOrNull { kotlin.math.abs(meltingTemp(template.take(it)) - targetTm) }
            ?: template.length
        val bases = template.take(len)
        return Primer(name, bases.uppercase(), meltingTemp(bases), gcContent(bases))
    }
}
