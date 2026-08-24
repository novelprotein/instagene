@file:Suppress("DuplicatedCode")

package org.instagene.core

/**
 * A single enzyme cut, in forward-strand coordinates.
 *
 * [topCut] is the phosphodiester bond broken on the top strand (the fragment
 * boundary), [bottomCut] the corresponding bond on the bottom strand.
 */
data class CutSite(
    val enzyme: Enzyme,
    val recognitionStart: Int,
    val topCut: Int,
    val bottomCut: Int,
    val strand: Strand,
) {
    val recognitionEnd: Int get() = recognitionStart + enzyme.siteLength
}

/** The single-stranded end left behind by a cut (or a native end of a linear molecule). */
data class StickyEnd(val type: EndType, val overhang: String, val enzyme: String? = null) {

    val isBlunt: Boolean get() = type == EndType.BLUNT

    /** Two ends anneal when they leave the same overhang of the same polarity. */
    fun isCompatibleWith(other: StickyEnd): Boolean =
        type == other.type && overhang.equals(other.overhang, ignoreCase = true)

    override fun toString(): String = when {
        isBlunt -> "blunt"
        else -> "${type.label} ${overhang.uppercase()}${enzyme?.let { " ($it)" } ?: ""}"
    }

    companion object {
        val BLUNT = StickyEnd(EndType.BLUNT, "")
    }
}

/**
 * A double-stranded fragment. [bases] is the top strand running from this
 * fragment's left top-strand cut to its right top-strand cut, so concatenating
 * consecutive fragments reproduces the parent molecule exactly.
 */
data class Fragment(
    val bases: String,
    val leftEnd: StickyEnd,
    val rightEnd: StickyEnd,
    val sourceName: String = "",
    val start: Int = 0,
    val features: List<Feature> = emptyList(),
) {
    val length: Int get() = bases.length

    val end: Int get() = start + length

    /**
     * Converts this fragment to a linear DNA [Seq] with its features. The default
     * name is derived from [sourceName] and the fragment coordinates.
     */
    fun toSeq(name: String = "${sourceName}_${start + 1}-$end"): Seq =
        Seq(name, bases, SeqKind.DNA, Topology.LINEAR, features)

    override fun toString(): String =
        "$length bp [${leftEnd}] .. [${rightEnd}]"
}

/** Restriction mapping and digestion. */
object Digest {

    /** All cut sites of [enzymes] in [seq], sorted by top-strand cut position. */
    fun cutSites(seq: Seq, enzymes: Collection<Enzyme>): List<CutSite> {
        if (enzymes.size <= 4) {
            // Small pools: sequential is faster than coroutine dispatch overhead.
            val sites = ArrayList<CutSite>()
            for (enzyme in enzymes) sites += cutSites(seq, enzyme)
            return sites.sortedWith(compareBy({ it.topCut }, { it.enzyme.name }))
        }
        val sites = Parallel.map(enzymes.toList()) { cutSites(seq, it) }.flatten()
        return sites.sortedWith(compareBy({ it.topCut }, { it.enzyme.name }))
    }

    /** All cut sites of [enzyme] in [seq], sorted by top-strand cut position. */
    fun cutSites(seq: Seq, enzyme: Enzyme): List<CutSite> {
        val out = ArrayList<CutSite>()
        scanSites(seq, enzyme) { i, forward, topCut, bottomCut ->
            out += if (seq.isCircular) {
                CutSite(
                    enzyme = enzyme,
                    recognitionStart = i,
                    topCut = normalize(topCut, seq.length),
                    bottomCut = normalize(bottomCut, seq.length),
                    strand = if (forward) Strand.FORWARD else Strand.REVERSE,
                )
            } else {
                CutSite(
                    enzyme = enzyme,
                    recognitionStart = i,
                    topCut = topCut,
                    bottomCut = bottomCut,
                    strand = if (forward) Strand.FORWARD else Strand.REVERSE,
                )
            }
        }
        return out.sortedBy { it.topCut }
    }

    /**
     * The number of times [enzyme] cuts [seq], without building any [CutSite]
     * objects, so whole catalogs of enzymes can be scanned cheaply.
     */
    fun countSites(
        seq: Seq,
        enzyme: Enzyme,
        /** Checked in bounded intervals, making large catalog scans cancellable without affecting small scans. */
        cancellationRequested: () -> Boolean = { false },
        /** Receives scanned and total candidate positions at bounded intervals. */
        progress: ((scanned: Int, total: Int) -> Unit)? = null,
    ): Int {
        var count = 0
        scanSites(seq, enzyme, cancellationRequested, progress) { _, _, _, _ -> count++ }
        return count
    }

    /**
     * Walks [seq] once per strand, calling [body] for every recognition site.
     * The topCut/bottomCut passed to [body] are plain (un-normalized) offsets
     * for linear molecules; circular callers must normalize them against the
     * sequence length, which is why [body] receives them alongside the site
     * start position.
     */
    private inline fun scanSites(
        seq: Seq,
        enzyme: Enzyme,
        crossinline cancellationRequested: () -> Boolean = { false },
        noinline progress: ((scanned: Int, total: Int) -> Unit)? = null,
        body: (i: Int, forward: Boolean, topCut: Int, bottomCut: Int) -> Unit
    ) {
        val len = seq.length
        if (len == 0) return
        val bases = seq.bases
        val site = enzyme.site.uppercase()
        val siteLen = site.length
        // A linear molecule can only be cut where the whole site fits; a circular
        // one is scanned all the way round the origin.
        val limit = if (seq.isCircular) len else len - siteLen + 1
        if (limit <= 0) return
        // Check frequently enough for a crowded genome to react promptly, but
        // not at every base: this is a tight, allocation-free hot path.
        val checkEvery = minOf(16_384, maxOf(1, limit / 100))
        var nextCheck = 0
        var lastReported = -1
        // Bitmaps let the scan check every site position with a single array read,
        // avoiding per-char map lookups, uppercasing and string searches. The
        // palindromic flag and the reverse-complement are evaluated once up front
        // rather than per candidate position.
        val palindromic = enzyme.isPalindromic
        val siteBitmaps = site.map { symbolBitmap(it) }
        val rcBitmaps = if (palindromic) siteBitmaps else Alphabet.reverseComplement(site).map { symbolBitmap(it) }
        // Only positions whose first base can begin a match are examined at all.
        val firstBitmap = siteBitmaps[0]
        val rcBitmap = rcBitmaps[0]

        if (seq.isCircular) {
            for (i in 0 until len) {
                if (i >= nextCheck) {
                    if (cancellationRequested()) throw java.util.concurrent.CancellationException("Restriction-site scan cancelled")
                    progress?.invoke(i.coerceAtMost(limit), limit)
                    lastReported = i.coerceAtMost(limit)
                    // Avoid wrapping around when an unusually large input is
                    // close to Int.MAX_VALUE. The final progress update below
                    // still reports the exact endpoint in that case.
                    nextCheck = if (i > limit - checkEvery) Int.MAX_VALUE else i + checkEvery
                }
                val base = bases[i]
                if (!bitmapsHit(firstBitmap, base)) continue
                if (matchesAtCircular(bases, len, i, siteBitmaps)) {
                    body(i, true, i + enzyme.topCut, i + enzyme.bottomCut)
                } else if (!palindromic && bitmapsHit(rcBitmap, base) && matchesAtCircular(bases, len, i, rcBitmaps)) {
                    body(i, false, i + siteLen - enzyme.bottomCut, i + siteLen - enzyme.topCut)
                }
            }
        } else {
            for (i in 0 until limit) {
                if (i >= nextCheck) {
                    if (cancellationRequested()) throw java.util.concurrent.CancellationException("Restriction-site scan cancelled")
                    progress?.invoke(i.coerceAtMost(limit), limit)
                    lastReported = i.coerceAtMost(limit)
                    nextCheck = if (i > limit - checkEvery) Int.MAX_VALUE else i + checkEvery
                }
                val base = bases[i]
                if (!bitmapsHit(firstBitmap, base)) continue
                if (matchesAt(bases, i, siteBitmaps)) {
                    body(i, true, i + enzyme.topCut, i + enzyme.bottomCut)
                } else if (!palindromic && bitmapsHit(rcBitmap, base) && matchesAt(bases, i, rcBitmaps)) {
                    body(i, false, i + siteLen - enzyme.bottomCut, i + siteLen - enzyme.topCut)
                }
            }
        }
        if (lastReported != limit) {
            if (cancellationRequested()) throw java.util.concurrent.CancellationException("Restriction-site scan cancelled")
            progress?.invoke(limit, limit)
        }
    }

    /** True when [base] is an ASCII base whose first-symbol bitmap bit is set. */
    private fun bitmapsHit(bitmap: BooleanArray, base: Char): Boolean {
        val code = base.code
        return code < 256 && bitmap[code]
    }

    private fun matchesAt(bases: String, start: Int, bitmaps: List<BooleanArray>): Boolean {
        for (j in bitmaps.indices) {
            if (!bitmaps[j][bases[start + j].code]) return false
        }
        return true
    }

    private fun matchesAtCircular(bases: String, len: Int, start: Int, bitmaps: List<BooleanArray>): Boolean {
        // The common case never wraps around the origin, so index straight into
        // the string; only the tail that crosses the end of the sequence needs a
        // (cheap, division-free) wrap. A site longer than the sequence can wrap
        // more than once; that degenerate case falls back to modular indexing.
        if (start + bitmaps.size <= len) return matchesAt(bases, start, bitmaps)
        if (bitmaps.size <= len) {
            val straight = len - start
            for (j in 0 until straight) {
                if (!bitmaps[j][bases[start + j].code]) return false
            }
            for (j in straight until bitmaps.size) {
                if (!bitmaps[j][bases[j - straight].code]) return false
            }
            return true
        }
        for (j in bitmaps.indices) {
            if (!bitmaps[j][bases[Math.floorMod(start + j, len)].code]) return false
        }
        return true
    }

    /** Positions (ASCII, upper and lower case) whose base matches [symbol]. */
    private fun symbolBitmap(symbol: Char): BooleanArray {
        val bitmap = BooleanArray(256)
        val expansion = Alphabet.expansion(symbol) ?: return bitmap
        for (c in expansion) {
            bitmap[c.code] = true
            bitmap[c.lowercaseChar().code] = true
        }
        return bitmap
    }

    private fun normalize(pos: Int, len: Int): Int = Math.floorMod(pos, len)

    /**
     * Digests [seq] with [enzymes].
     *
     * A circular molecule cut *n* times yields *n* fragments; a linear one yields
     * *n + 1*. Cutting nothing returns the molecule unchanged as a single fragment.
     */
    fun digest(seq: Seq, enzymes: Collection<Enzyme>): List<Fragment> {
        val sites = cutSites(seq, enzymes)
            .distinctBy { it.topCut }
            .sortedBy { it.topCut }

        return digestAtSites(seq, sites)
    }

    /** Digests using an explicit subset of mapped sites, useful for partial-digest simulation. */
    fun digestSites(seq: Seq, sites: Collection<CutSite>): List<Fragment> =
        digestAtSites(seq, sites.distinctBy { it.topCut }.sortedBy { it.topCut })

    private fun digestAtSites(seq: Seq, sites: List<CutSite>): List<Fragment> {

        if (sites.isEmpty()) {
            return listOf(
                Fragment(seq.bases, StickyEnd.BLUNT, StickyEnd.BLUNT, seq.name, 0, seq.features)
            )
        }

        return if (seq.isCircular) digestCircular(seq, sites) else digestLinear(seq, sites)
    }

    private fun digestLinear(seq: Seq, sites: List<CutSite>): List<Fragment> {
        val boundaries = listOf(0) + sites.map { it.topCut } + listOf(seq.length)
        val out = ArrayList<Fragment>()
        for (i in 0 until boundaries.size - 1) {
            val from = boundaries[i]
            val to = boundaries[i + 1]
            if (to <= from) continue
            val leftEnd = if (i == 0) StickyEnd.BLUNT else endFor(seq, sites[i - 1])
            val rightEnd = if (i == boundaries.size - 2) StickyEnd.BLUNT else endFor(seq, sites[i])
            out += Fragment(seq.bases.substring(from, to), leftEnd, rightEnd, seq.name, from, featuresIn(seq, from, to))
        }
        return out
    }

    private fun digestCircular(seq: Seq, sites: List<CutSite>): List<Fragment> {
        val out = ArrayList<Fragment>()
        for (i in sites.indices) {
            val from = sites[i].topCut
            val next = sites[(i + 1) % sites.size]
            // The last fragment wraps the origin back to the first cut.
            val span = if (i == sites.size - 1) seq.length - from + next.topCut else next.topCut - from
            if (span <= 0) continue
            out += Fragment(
                bases = seq.sub(from, from + span),
                leftEnd = endFor(seq, sites[i]),
                rightEnd = endFor(seq, next),
                sourceName = seq.name,
                start = from,
                features = featuresIn(seq, from, from + span),
            )
        }
        return out
    }

    /** The end produced at [site]: the overhang is the span between the two cuts. */
    fun stickyEnd(seq: Seq, site: CutSite): StickyEnd = endFor(seq, site)

    private fun endFor(seq: Seq, site: CutSite): StickyEnd {
        val len = site.enzyme.overhangLength
        if (len == 0) return StickyEnd(EndType.BLUNT, "", site.enzyme.name)
        val lo = minOf(site.topCut, site.bottomCut)
        val overhang = seq.sub(lo, lo + kotlin.math.abs(len))
        return StickyEnd(site.enzyme.endType, overhang.uppercase(), site.enzyme.name)
    }

    private fun featuresIn(seq: Seq, from: Int, to: Int): List<Feature> =
        seq.features.mapNotNull { f ->
            val s = f.start - from
            val e = f.end - from
            if (e <= 0 || s >= to - from) null
            else f.copy(start = s.coerceAtLeast(0), end = e.coerceAtMost(to - from))
        }

    /** Enzymes that cut [seq] exactly [times] — the usual way to find a unique site. */
    fun enzymesCutting(seq: Seq, times: Int = 1, pool: List<Enzyme> = Enzymes.ALL): List<Enzyme> {
        if (pool.size <= 4) return pool.filter { countSites(seq, it) == times }
        return Parallel.filter(pool) { countSites(seq, it) == times }
    }

    /** Per-enzyme cut counts over [pool], for the digest summary column. */
    fun cutCounts(seq: Seq, pool: List<Enzyme> = Enzymes.ALL): Map<Enzyme, Int> {
        if (pool.size <= 4) return pool.associateWith { countSites(seq, it) }
        val counts = Parallel.map(pool) { it to countSites(seq, it) }
        return counts.toMap()
    }
}
