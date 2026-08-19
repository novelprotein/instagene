package org.instagene.core

import kotlin.math.ln
import kotlin.math.roundToInt

/** A single data point for a time-series / position-series chart. */
data class XY(val x: Double, val y: Double)

/** A named bar in a bar chart. */
data class Bar(val label: String, val value: Double)

/** Codon usage entry with count and relative frequency. */
data class CodonUsageEntry(val codon: String, val count: Int, val frequency: Double)

/** Repeat match with position and length. */
data class RepeatMatch(val start: Int, val length: Int, val unit: String)

/** A detected CpG island with statistics. */
data class CpGIsland(
    val start: Int,
    val end: Int,
    val length: Int,
    val gcContent: Double,
    val observedCpG: Int,
    val expectedCpG: Double,
    val oeRatio: Double,
)

/** Comprehensive statistics for a single sequence. */
data class SequenceStats(
    val length: Int,
    val gcContent: Double,
    val nucleotideComposition: Map<Char, Int>,
    val longestHomopolymer: Pair<Char, Int>,
    val gcSkew: Double,
    val atSkew: Double,
    val ambigCount: Int,
    val dinucleotideCounts: Map<String, Int>,
    val trinucleotideCounts: Map<String, Int>,
    val complexityScore: Double,
    val shannonEntropy: Double,
    val simpsonDiversity: Double,
)

/** Pure statistical computations over sequences. All functions are stateless. */
object SequenceStatistics {

    /** Full statistics for a DNA/RNA sequence — single fused pass over the string. */
    fun computeStats(seq: Seq): SequenceStats {
        val bases = seq.bases
        val length = bases.length
        if (length == 0) return SequenceStats(
            0, 0.0, emptyMap(), 'N' to 0, 0.0, 0.0, 0,
            emptyMap(), emptyMap(), 0.0, 0.0, 0.0,
        )

        // Single pass: base counts, homopolymer, GC content, skew, ambiguity
        val baseCounts = IntArray(128)
        var maxChar = '\u0000'
        var maxRun = 0
        var curChar = '\u0000'
        var curRun = 0
        var gCount = 0
        var cCount = 0
        var aCount = 0
        var tCount = 0

        for (i in 0 until length) {
            val ch = bases[i].code
            if (ch in 0 until 128) baseCounts[ch]++

            val uc = bases[i].uppercaseChar()
            if (uc == curChar) {
                curRun++
            } else {
                curChar = uc
                curRun = 1
            }
            if (curRun > maxRun) { maxRun = curRun; maxChar = curChar }

            when (uc) {
                'G' -> gCount++
                'C' -> cCount++
                'A' -> aCount++
                'T', 'U' -> tCount++
            }
        }

        val gc = (gCount + cCount) * 100.0 / length
        val gcSkew = if (gCount + cCount == 0) 0.0 else (gCount - cCount).toDouble() / (gCount + cCount)
        val atSkew = if (aCount + tCount == 0) 0.0 else (aCount - tCount).toDouble() / (aCount + tCount)
        val ambig = length - (aCount + tCount + gCount + cCount)

        // Build sorted map from IntArray
        val counts = sortedMapOf<Char, Int>()
        for (code in baseCounts.indices) {
            if (baseCounts[code] > 0) counts[code.toChar()] = baseCounts[code]
        }

        // Dinucleotides — avoid substring allocation, use (char, char) packed as int
        val dinucMap = IntArray(16 * 16) // ACGT*4 -> index
        val dinucValues = IntArray(16 * 16)
        val dinucKeys = ArrayList<String>(16)
        val dinucIdx = IntArray(128) { -1 }
        val bases4 = charArrayOf('A', 'C', 'G', 'T', 'U')
        for (i in bases4.indices) dinucIdx[bases4[i].code] = i
        var dinucUnique = 0

        if (length >= 2) {
            for (i in 0 until length - 1) {
                val b1 = dinucIdx[bases[i].uppercaseChar().code]
                val b2 = dinucIdx[bases[i + 1].uppercaseChar().code]
                if (b1 >= 0 && b2 >= 0) {
                    val key = b1 * 5 + b2
                    if (dinucValues[key] == 0 && dinucUnique < 25) {
                        dinucKeys.add("${bases[i].uppercaseChar()}${bases[i + 1].uppercaseChar()}")
                        dinucMap[key] = dinucUnique++
                    }
                    dinucValues[key]++
                }
            }
        }
        val dinuc = LinkedHashMap<String, Int>()
        for (i in 0 until dinucUnique) {
            // find the key for this slot
            for (j in 0 until 25) {
                if (dinucMap[j] == i) {
                    val b1 = bases4[j / 5]
                    val b2 = bases4[j % 5]
                    dinuc["$b1$b2"] = dinucValues[j]
                    break
                }
            }
        }

        // Trinucleotides — packed int key to avoid String allocation per position
        val triValues = IntArray(125) // 5^3 = 125 possible
        val triKeys = ArrayList<String>(64)
        val triMap = IntArray(125) { -1 }
        var triUnique = 0
        if (length >= 3) {
            for (i in 0 until length - 2) {
                val b1 = dinucIdx[bases[i].uppercaseChar().code]
                val b2 = dinucIdx[bases[i + 1].uppercaseChar().code]
                val b3 = dinucIdx[bases[i + 2].uppercaseChar().code]
                if (b1 >= 0 && b2 >= 0 && b3 >= 0) {
                    val key = b1 * 25 + b2 * 5 + b3
                    if (triValues[key] == 0) {
                        triKeys.add("${bases[i].uppercaseChar()}${bases[i + 1].uppercaseChar()}${bases[i + 2].uppercaseChar()}")
                        triMap[key] = triUnique++
                    }
                    triValues[key]++
                }
            }
        }
        val trinuc = LinkedHashMap<String, Int>()
        for (i in 0 until triUnique) {
            for (j in 0 until 125) {
                if (triMap[j] == i) {
                    val b1 = bases4[j / 25]; val b2 = bases4[(j / 5) % 5]; val b3 = bases4[j % 5]
                    trinuc["$b1$b2$b3"] = triValues[j]
                    break
                }
            }
        }

        // Complexity
        val dinucPossible = minOf(16, maxOf(1, length - 1))
        val trinucPossible = minOf(64, maxOf(1, length - 2))
        val complexity = ((dinuc.size.toDouble() / dinucPossible + trinuc.size.toDouble() / trinucPossible) / 2.0 * 100.0).roundToInt() / 100.0

        // Shannon entropy + Simpson diversity — reuse baseCounts from first pass (zero extra passes)
        val total = length.toDouble()
        var entropy = 0.0
        var sumNiNj = 0L
        for (c in baseCounts) {
            if (c > 0) {
                val p = c / total
                entropy -= p * ln(p) / ln(2.0)
            }
            if (c > 1) sumNiNj += c.toLong() * (c - 1)
        }
        val n = length.toLong()
        val diversity = if (n < 2) 0.0 else 1.0 - sumNiNj.toDouble() / (n * (n - 1))

        return SequenceStats(
            length = length,
            gcContent = gc,
            nucleotideComposition = counts,
            longestHomopolymer = maxChar to maxRun,
            gcSkew = gcSkew,
            atSkew = atSkew,
            ambigCount = ambig,
            dinucleotideCounts = dinuc,
            trinucleotideCounts = trinuc,
            complexityScore = complexity,
            shannonEntropy = entropy,
            simpsonDiversity = diversity,
        )
    }

    // ---------------------------------------------------------------- sliding windows

    /** GC content in sliding windows using running counts — no substring allocation. */
    fun gcContentProfile(seq: Seq, windowSize: Int = 100, step: Int = 50): List<XY> {
        require(seq.kind != SeqKind.PROTEIN) { "GC profile requires DNA or RNA" }
        val bases = seq.bases
        val len = bases.length
        if (len == 0) return emptyList()
        val result = ArrayList<XY>()
        var pos = 0
        // Initial window count
        var gcCount = 0
        for (i in 0 until minOf(windowSize, len)) {
            val uc = bases[i].uppercaseChar()
            if (uc == 'G' || uc == 'C' || uc == 'S') gcCount++
        }
        result += XY(windowSize / 2.0, gcCount * 100.0 / minOf(windowSize, len))
        pos += step
        // Slide window
        while (pos + windowSize <= len) {
            // Remove element leaving window, add element entering window
            val leaving = bases[pos - 1].uppercaseChar()
            val entering = bases[pos + windowSize - 1].uppercaseChar()
            if (leaving == 'G' || leaving == 'C' || leaving == 'S') gcCount--
            if (entering == 'G' || entering == 'C' || entering == 'S') gcCount++
            result += XY(pos + windowSize / 2.0, gcCount * 100.0 / windowSize)
            pos += step
        }
        return result
    }

    /** GC skew using running counts — no substring allocation. */
    fun gcSkewProfile(seq: Seq, windowSize: Int = 100, step: Int = 50): List<XY> {
        require(seq.kind != SeqKind.PROTEIN) { "GC skew requires DNA or RNA" }
        val bases = seq.bases
        val len = bases.length
        if (len == 0) return emptyList()
        val result = ArrayList<XY>()
        var pos = 0
        var g = 0; var c = 0
        for (i in 0 until minOf(windowSize, len)) {
            val uc = bases[i].uppercaseChar()
            if (uc == 'G') g++ else if (uc == 'C') c++
        }
        result += XY(windowSize / 2.0, if (g + c == 0) 0.0 else (g - c).toDouble() / (g + c))
        pos += step
        while (pos + windowSize <= len) {
            val leaving = bases[pos - 1].uppercaseChar()
            val entering = bases[pos + windowSize - 1].uppercaseChar()
            if (leaving == 'G') g-- else if (leaving == 'C') c--
            if (entering == 'G') g++ else if (entering == 'C') c++
            result += XY(pos + windowSize / 2.0, if (g + c == 0) 0.0 else (g - c).toDouble() / (g + c))
            pos += step
        }
        return result
    }

    /** Cumulative GC skew in sliding windows (not per-base — avoids O(n) memory). */
    @Suppress("DuplicatedCode")
    fun cumulativeGcSkew(seq: Seq, windowSize: Int = 100, step: Int = 50): List<XY> {
        require(seq.kind != SeqKind.PROTEIN) { "GC skew requires DNA or RNA" }
        val bases = seq.bases
        val len = bases.length
        if (len == 0) return emptyList()
        val result = ArrayList<XY>()
        var g = 0; var c = 0
        for (i in 0 until minOf(windowSize, len)) {
            val uc = bases[i].uppercaseChar()
            if (uc == 'G') g++ else if (uc == 'C') c++
        }
        var winStart = 0
        result += XY(windowSize / 2.0, if (g + c == 0) 0.0 else (g - c).toDouble() / (g + c))
        winStart += step
        while (winStart + windowSize <= len) {
            val leaving = bases[winStart - 1].uppercaseChar()
            val entering = bases[winStart + windowSize - 1].uppercaseChar()
            if (leaving == 'G') g-- else if (leaving == 'C') c--
            if (entering == 'G') g++ else if (entering == 'C') c++
            result += XY(winStart + windowSize / 2.0, if (g + c == 0) 0.0 else (g - c).toDouble() / (g + c))
            winStart += step
        }
        return result
    }

    // ---------------------------------------------------------------- composition

    /** Nucleotide composition as bars. */
    fun nucleotideComposition(seq: Seq): List<Bar> {
        val counts = SeqOps.baseCounts(seq.bases)
        val total = seq.length.toDouble()
        return listOf('A', 'T', 'U', 'G', 'C', 'N', 'R', 'Y', 'S', 'W', 'K', 'M', 'B', 'D', 'H', 'V')
            .filter { counts.containsKey(it) }
            .map { Bar("$it", (counts[it]!! / total * 100.0).roundToInt() / 100.0) }
    }

    /** Codon usage sorted by frequency. */
    fun codonUsage(seq: Seq): List<CodonUsageEntry> {
        require(seq.kind != SeqKind.PROTEIN) { "Codon usage requires DNA or RNA" }
        val counts = SeqOps.codonUsage(seq.bases)
        val total = counts.values.sum().toDouble()
        return counts.entries
            .sortedByDescending { it.value }
            .map { CodonUsageEntry(it.key, it.value, (it.value / total * 10000.0).roundToInt() / 100.0) }
    }

    /** Amino acid composition for a protein sequence. */
    fun aminoAcidComposition(seq: Seq): List<Bar> {
        require(seq.kind == SeqKind.PROTEIN) { "Amino acid composition requires protein" }
        val counts = seq.bases.uppercase().groupingBy { it }.eachCount()
        val total = seq.length.toDouble()
        return counts.entries
            .sortedByDescending { it.value }
            .map { Bar("${it.key}", (it.value / total * 100.0).roundToInt() / 100.0) }
    }

    // ---------------------------------------------------------------- dinucleotides

    /** Dinucleotide relative frequencies. */
    fun dinucleotideFrequencies(seq: Seq): List<Bar> {
        val bases = seq.bases
        val len = bases.length
        if (len < 2) return emptyList()
        // Packed int key — avoid String allocation per position
        val bases5 = charArrayOf('A', 'C', 'G', 'T', 'U')
        val idx = IntArray(128) { -1 }
        for (i in bases5.indices) idx[bases5[i].code] = i
        val values = IntArray(25)
        val map = IntArray(25) { -1 }
        val keys = ArrayList<String>(25)
        var unique = 0
        for (i in 0 until len - 1) {
            val b1 = idx[bases[i].uppercaseChar().code]
            val b2 = idx[bases[i + 1].uppercaseChar().code]
            if (b1 >= 0 && b2 >= 0) {
                val k = b1 * 5 + b2
                if (values[k] == 0) {
                    keys.add("${bases[i].uppercaseChar()}${bases[i + 1].uppercaseChar()}")
                    map[k] = unique++
                }
                values[k]++
            }
        }
        val total = values.sum().toDouble()
        return (0 until unique).map { slot ->
            for (j in 0 until 25) { if (map[j] == slot) return@map Bar(keys[slot], values[j] / total * 100.0) }
            Bar(keys[slot], 0.0)
        }.sortedByDescending { it.value }
    }

    // ---------------------------------------------------------------- profiles

    /** Melting temperature profile along a DNA sequence using sliding window. */
    @Suppress("DuplicatedCode")
    fun meltingTempProfile(seq: Seq, windowSize: Int = 20, step: Int = 10): List<XY> {
        require(seq.kind != SeqKind.PROTEIN) { "Melting temp requires DNA or RNA" }
        val bases = seq.bases
        val len = bases.length
        if (len == 0) return emptyList()
        val result = ArrayList<XY>()
        var pos = 0
        // Initial window: count AT and GC
        var at = 0; var gc = 0
        for (i in 0 until minOf(windowSize, len)) {
            when (bases[i].uppercaseChar()) {
                'A', 'T', 'U' -> at++
                'C', 'G' -> gc++
            }
        }
        val n = at + gc
        val tm = if (n < 14) 2.0 * at + 4.0 * gc else 81.5 + 16.6 * 0.05 + 41.0 * gc / n - 600.0 / n
        result += XY(windowSize / 2.0, tm)
        pos += step
        while (pos + windowSize <= len) {
            val leaving = bases[pos - 1].uppercaseChar()
            val entering = bases[pos + windowSize - 1].uppercaseChar()
            when (leaving) { 'A', 'T', 'U' -> at--; 'C', 'G' -> gc-- }
            when (entering) { 'A', 'T', 'U' -> at++; 'C', 'G' -> gc++ }
            val nWin = at + gc
            val tmWin = if (nWin < 14) 2.0 * at + 4.0 * gc else 81.5 + 16.6 * 0.05 + 41.0 * gc / nWin - 600.0 / nWin
            result += XY(pos + windowSize / 2.0, tmWin)
            pos += step
        }
        return result
    }

    /** Open reading frame density in windows. */
    fun orfDensity(seq: Seq, windowSize: Int = 200, step: Int = 100): List<XY> {
        require(seq.kind == SeqKind.DNA) { "ORF density requires DNA" }
        if (seq.length < windowSize) return emptyList()
        val orfs = SeqOps.findOrfs(seq, minAminoAcids = 20)
        val orfRanges = orfs.map { it.start until it.end }
        val result = ArrayList<XY>()
        var pos = 0
        while (pos + windowSize <= seq.length) {
            val window = pos until pos + windowSize
            val coverage = orfRanges.sumOf { range ->
                val overlapStart = maxOf(range.first, window.first)
                val overlapEnd = minOf(range.last, window.last)
                if (overlapStart < overlapEnd) overlapEnd - overlapStart else 0
            }
            result += XY(pos + windowSize / 2.0, coverage.toDouble() / windowSize * 100.0)
            pos += step
        }
        return result
    }

    // ---------------------------------------------------------------- repeats

    /** Find tandem repeats of length 1–10 — char-level comparison, no substring allocation. */
    fun tandemRepeats(seq: Seq, minLength: Int = 1, maxLength: Int = 10, minRepeats: Int = 3): List<RepeatMatch> {
        val bases = seq.bases
        val len = bases.length
        val result = ArrayList<RepeatMatch>()
        for (unitLen in minLength..maxLength) {
            var i = 0
            while (i + unitLen <= len) {
                var count = 1
                var j = i + unitLen
                // Compare without creating substrings
                outer@ while (j + unitLen <= len) {
                    for (k in 0 until unitLen) {
                        if (bases[i + k].uppercaseChar() != bases[j + k].uppercaseChar()) break@outer
                    }
                    count++
                    j += unitLen
                }
                if (count >= minRepeats) {
                    result += RepeatMatch(i, count * unitLen, bases.substring(i, i + unitLen).uppercase())
                }
                i = if (count > 1) j else i + 1
            }
        }
        return result.sortedBy { it.start }
    }

    // ---------------------------------------------------------------- CpG islands

    /**
     * Detects CpG islands using Gardiner-Garden & Frommer (1987) criteria:
     * - Window GC content ≥ [minGc]%
     * - Observed/Expected CpG ratio ≥ [minOeRatio]
     * - Merged island length ≥ [minLength] bp
     */
    fun cpgIslands(
        seq: Seq,
        windowSize: Int = 100,
        step: Int = 20,
        minLength: Int = 200,
        minGc: Double = 50.0,
        minOeRatio: Double = 0.6,
    ): List<CpGIsland> {
        require(seq.kind != SeqKind.PROTEIN) { "CpG island detection requires DNA or RNA" }
        val bases = seq.bases.uppercase()
        val len = bases.length
        if (len < windowSize) return emptyList()

        // Score each window
        val qualifying = BooleanArray(len / step + 1)
        var pos = 0
        var idx = 0
        while (pos + windowSize <= len) {
            var gc = 0; var cg = 0; var cCount = 0; var gCount = 0
            for (j in pos until pos + windowSize) {
                when (bases[j]) {
                    'G' -> { gCount++; gc++ }
                    'C' -> { cCount++; gc++ }
                }
            }
            for (j in pos until pos + windowSize - 1) {
                if (bases[j] == 'C' && bases[j + 1] == 'G') cg++
            }
            val gcPct = gc * 100.0 / windowSize
            val expected = cCount.toDouble() * gCount / windowSize
            val oe = if (expected > 0.0) cg / expected else 0.0
            if (gcPct >= minGc && oe >= minOeRatio) qualifying[idx] = true
            pos += step
            idx++
        }

        // Merge overlapping qualifying windows
        val islands = ArrayList<CpGIsland>()
        var mergeStart = -1
        var mergeEnd = -1
        for (i in qualifying.indices) {
            if (qualifying[i]) {
                val winStart = i * step
                val winEnd = winStart + windowSize
                if (mergeStart < 0) {
                    mergeStart = winStart
                    mergeEnd = winEnd
                } else {
                    mergeEnd = winEnd
                }
            } else if (mergeStart >= 0) {
                addIslandIfLargeEnough(bases, mergeStart, mergeEnd, minLength, islands)
                mergeStart = -1
                mergeEnd = -1
            }
        }
        if (mergeStart >= 0) {
            addIslandIfLargeEnough(bases, mergeStart, mergeEnd, minLength, islands)
        }
        return islands
    }

    private fun addIslandIfLargeEnough(
        bases: String, start: Int, end: Int, minLength: Int, out: MutableList<CpGIsland>,
    ) {
        val length = end - start
        if (length < minLength) return
        var gc = 0; var cg = 0; var cCount = 0; var gCount = 0
        for (j in start until end) {
            when (bases[j]) {
                'G' -> { gCount++; gc++ }
                'C' -> { cCount++; gc++ }
            }
        }
        for (j in start until end - 1) {
            if (bases[j] == 'C' && bases[j + 1] == 'G') cg++
        }
        val gcPct = gc * 100.0 / length
        val expected = cCount.toDouble() * gCount / length
        val oe = if (expected > 0.0) cg / expected else 0.0
        out += CpGIsland(start + 1, end, length, gcPct, cg, expected, oe)
    }

    /** Per-window observed/expected CpG ratio as an XY series for charting. */
    fun cpgDensityProfile(seq: Seq, windowSize: Int = 100, step: Int = 20): List<XY> {
        require(seq.kind != SeqKind.PROTEIN) { "CpG density requires DNA or RNA" }
        val bases = seq.bases.uppercase()
        val len = bases.length
        if (len < windowSize) return emptyList()
        val result = ArrayList<XY>()
        var pos = 0
        while (pos + windowSize <= len) {
            var cg = 0; var cCount = 0; var gCount = 0
            for (j in pos until pos + windowSize) {
                when (bases[j]) {
                    'G' -> gCount++
                    'C' -> cCount++
                }
            }
            for (j in pos until pos + windowSize - 1) {
                if (bases[j] == 'C' && bases[j + 1] == 'G') cg++
            }
            val expected = cCount.toDouble() * gCount / windowSize
            val oe = if (expected > 0.0) cg / expected else 0.0
            result += XY(pos + windowSize / 2.0, oe)
            pos += step
        }
        return result
    }
}
