@file:Suppress("DuplicatedCode")

package org.instagene.core

/** Methylation assumptions used by restriction-site calculations. */
data class MethylationProfile(
    /** Kept non-null for compatibility with the original two-flag API. */
    val dam: Boolean = false,
    val dcm: Boolean = false,
    val cpg: Boolean? = null,
    /** Whether the legacy [dam] value is known rather than an unknown placeholder. */
    val damKnown: Boolean = true,
    /** Whether the legacy [dcm] value is known rather than an unknown placeholder. */
    val dcmKnown: Boolean = true,
) {
    val hasUnknown: Boolean get() = !damKnown || !dcmKnown || cpg == null

    companion object {
        fun from(molecule: MoleculeProperties): MethylationProfile = MethylationProfile(
            dam = molecule.damMethylated,
            dcm = molecule.dcmMethylated,
            cpg = molecule.cpgState.toBooleanOrNull(),
            damKnown = molecule.damState != MethylationState.UNKNOWN,
            dcmKnown = molecule.dcmState != MethylationState.UNKNOWN,
        )

        fun unknown(): MethylationProfile = MethylationProfile(
            dam = false,
            dcm = false,
            cpg = null,
            damKnown = false,
            dcmKnown = false,
        )
    }
}

private fun MethylationState.toBooleanOrNull(): Boolean? = when (this) {
    MethylationState.METHYLATED -> true
    MethylationState.UNMETHYLATED -> false
    MethylationState.UNKNOWN -> null
}

/** A centralized, deliberately conservative restriction methylation rule table. */
object MethylationRules {
    private val damBlocked = setOf("bcli", "clai")
    private val dcmBlocked = emptySet<String>()
    private val cpgBlocked = setOf("accii", "bsshii", "bstui", "clai", "eagi", "hpaii", "smai", "sacii")
    private val methylatedSubstrateCutters = setOf("dpni")

    /** True when this site is blocked by the supplied methylation profile. */
    fun isBlocked(seq: Seq, site: CutSite, profile: MethylationProfile): Boolean {
        val key = site.enzyme.name.lowercase()
        val recognition = seq.sub(site.recognitionStart, site.recognitionStart + site.enzyme.siteLength).uppercase()
        if (key in methylatedSubstrateCutters && recognition.contains("GATC")) return profile.damKnown && !profile.dam
        if (profile.damKnown && profile.dam && key in damBlocked) return true
        if (profile.dcmKnown && profile.dcm && key in dcmBlocked && Regex("CC[ACT]GG").containsMatchIn(recognition)) return true
        if (profile.cpg == true && key in cpgBlocked && recognition.contains("CG")) return true
        return false
    }

    /** Human-readable explanation for a result that remains uncertain. */
    fun uncertainty(profile: MethylationProfile): String? = if (profile.hasUnknown) {
        "Methylation state unknown for ${listOfNotNull(
            if (!profile.damKnown) "Dam" else null,
            if (!profile.dcmKnown) "Dcm" else null,
            if (profile.cpg == null) "CpG" else null,
        ).distinct().joinToString(", ")}."
    } else null
}
data class RestrictionReport(val enzyme: Enzyme, val count: Int, val positions: List<Int>)

enum class CpGContext { ISLAND, SHORE, OPEN_SEA }

data class CpGCatalogEntry(val position: Int, val context: CpGContext)

data class CpGComparisonReport(
    val pairLabel: String,
    val enzyme1: String,
    val enzyme2: String,
    val totalSites: Int,
    val methylBlockedSites: Int,
)

data class MutationCandidate(
    val enzyme: Enzyme,
    val position: Int,
    val strand: Strand,
    val original: String,
    val mutated: String,
    val mismatches: Int,
    val aminoAcidSequence: String? = null,
)

/** Higher-level restriction analysis described by ApE's enzyme workflows. */
object EnzymeAnalysis {
    fun reports(
        seq: Seq,
        enzymes: Collection<Enzyme>,
        selection: IntRange? = null,
        profile: MethylationProfile = MethylationProfile.from(seq.molecule),
    ): List<RestrictionReport> {
        val enzymeList = enzymes.toList()
        val results = if (enzymeList.size <= 4) {
            enzymeList.map { enzyme ->
                val sites = Digest.cutSites(seq, enzyme, profile).filter { site -> selection == null || site.recognitionStart in selection }
                RestrictionReport(enzyme, sites.size, sites.map { it.recognitionStart + 1 })
            }
        } else {
            Parallel.map(enzymeList) { enzyme ->
                val sites = Digest.cutSites(seq, enzyme, profile).filter { site -> selection == null || site.recognitionStart in selection }
                RestrictionReport(enzyme, sites.size, sites.map { it.recognitionStart + 1 })
            }
        }
        return results.sortedBy { it.enzyme.name.lowercase() }
    }

    fun unique(
        seq: Seq,
        enzymes: Collection<Enzyme> = Enzymes.ALL,
        profile: MethylationProfile = MethylationProfile.from(seq.molecule),
    ): List<Enzyme> {
        if (enzymes is List && enzymes.size <= 4) return enzymes.filter { Digest.countSites(seq, it, profile) == 1 }
        return Parallel.filter(enzymes.toList()) { Digest.countSites(seq, it, profile) == 1 }
    }

    fun absent(
        seq: Seq,
        enzymes: Collection<Enzyme> = Enzymes.ALL,
        profile: MethylationProfile = MethylationProfile.from(seq.molecule),
    ): List<Enzyme> {
        if (enzymes is List && enzymes.size <= 4) return enzymes.filter { Digest.countSites(seq, it, profile) == 0 }
        return Parallel.filter(enzymes.toList()) { Digest.countSites(seq, it, profile) == 0 }
    }

    /** Filters sites through the same methylation rules used by the digest panel. */
    fun cutSites(seq: Seq, enzymes: Collection<Enzyme>, profile: MethylationProfile): List<CutSite> =
        Digest.cutSites(seq, enzymes, profile)

    fun insertRecognitionSite(enzyme: Enzyme, reverse: Boolean = false): String {
        val concrete = enzyme.site.map { Alphabet.expansion(it)?.firstOrNull() ?: 'N' }.joinToString("")
        return if (reverse) Alphabet.reverseComplement(concrete) else concrete
    }

    fun diagnosticSites(
        seq: Seq,
        region: IntRange,
        enzymes: Collection<Enzyme>,
        maxMismatches: Int = 1,
    ): List<MutationCandidate> {
        require(maxMismatches in 1..3) { "maxMismatches must be between 1 and 3" }
        return candidateSites(seq, region, enzymes, maxMismatches, requireSilent = false)
    }

    fun silentSites(seq: Seq, region: IntRange, enzymes: Collection<Enzyme>): List<MutationCandidate> =
        candidateSites(seq, region, enzymes, maxMismatches = 1, requireSilent = true)

    private fun candidateSites(
        seq: Seq,
        region: IntRange,
        enzymes: Collection<Enzyme>,
        maxMismatches: Int,
        requireSilent: Boolean,
    ): List<MutationCandidate> {
        require(seq.kind != SeqKind.PROTEIN) { "Restriction-site analysis requires a nucleotide sequence" }
        val start = region.first.coerceAtLeast(0)
        val end = (region.last + 1).coerceAtMost(seq.length)
        if (end <= start) return emptyList()
        val originalProtein = if (requireSilent) SeqOps.translateBases(seq.bases.substring(start, end)) else null
        val candidates = ArrayList<MutationCandidate>()
        for (enzyme in enzymes) {
            // Pre-compute cut sites once per enzyme instead of rescanning inside
            // the position loop — turns O(region × seqLength) into O(region + seqLength).
            val existingSites = Digest.cutSites(seq, listOf(enzyme)).map { it.recognitionStart }.toHashSet()
            for (strand in listOf(Strand.FORWARD, Strand.REVERSE)) {
                val site = if (strand == Strand.FORWARD) enzyme.site else insertRecognitionSite(enzyme, reverse = true)
                for (position in start..(end - site.length).coerceAtLeast(start)) {
                    val current = seq.bases.substring(position, position + site.length).uppercase()
                    val mismatch = site.indices.count { !Alphabet.matches(site[it], current[it]) }
                    if (mismatch !in 1..maxMismatches) continue
                    val mutated = buildString {
                        current.forEachIndexed { index, base -> append(Alphabet.expansion(site[index])?.firstOrNull() ?: base) }
                    }
                    if (mutated == current || position in existingSites) continue
                    val changed = seq.bases.substring(0, position) + mutated + seq.bases.substring(position + site.length)
                    if (requireSilent && SeqOps.translateBases(changed.substring(start, end)) != originalProtein) continue
                    candidates += MutationCandidate(enzyme, position, strand, current, mutated, mismatch, originalProtein)
                }
            }
        }
        return candidates.distinctBy { Triple(it.enzyme.name, it.position, it.strand) }
            .sortedWith(compareBy({ it.position }, { it.enzyme.name }))
    }

    // --------------------------------------------------------- CpG methylation

    private val METHYL_SENSITIVE_PAIRS = listOf(
        Triple("HpaII/MspI", "HpaII", "MspI"),
        Triple("SmaI/XmaI", "SmaI", "XmaI"),
        Triple("AccII/BstUI", "AccII", "BstUI"),
    )

    private val ISOSCHIZOMER_SITES = mapOf(
        "HpaII" to "CCGG", "MspI" to "CCGG",
        "SmaI" to "CCCGGG", "XmaI" to "CCCGGG",
        "AccII" to "CGCG", "BstUI" to "CGCG",
    )

    private val CpG_PATTERN = Regex("CG")

    /**
     * Classifies a CpG dinucleotide position by its local sequence context.
     * Uses a 500bp window centered on the CpG to compute GC% and OE ratio,
     * then classifies into ISLAND (GC≥50%, OE≥0.6), SHORE (within 2kb of
     * an island), or OPEN_SEA.
     */
    fun cpgCatalog(seq: Seq): List<CpGCatalogEntry> {
        val bases = seq.bases.uppercase()
        val len = bases.length
        val cpgPositions = ArrayList<Int>()
        var i = 0
        while (i < len - 1) {
            if (bases[i] == 'C' && bases[i + 1] == 'G') {
                cpgPositions.add(i)
                i += 2
            } else {
                i++
            }
        }
        if (cpgPositions.isEmpty()) return emptyList()

        val islandWindows = BooleanArray(len / 20 + 1)
        val windowSize = 100
        val step = 20
        var pos = 0
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
            val oe = if (expected > 0) cg / expected else 0.0
            if (gcPct >= 50.0 && oe >= 0.6) {
                islandWindows[pos / step] = true
            }
            pos += step
        }

        return cpgPositions.map { pos ->
            val context = when {
                pos / step in islandWindows.indices && islandWindows[pos / step] -> CpGContext.ISLAND
                isNearIsland(pos, islandWindows) -> CpGContext.SHORE
                else -> CpGContext.OPEN_SEA
            }
            CpGCatalogEntry(pos + 1, context)
        }
    }

    private fun isNearIsland(pos: Int, islandWindows: BooleanArray): Boolean {
        val shoreRange = 2000 / 20
        val center = pos / 20
        for (d in -shoreRange..shoreRange) {
            val idx = center + d
            if (idx in islandWindows.indices && islandWindows[idx]) return true
        }
        return false
    }

    /**
     * Compares cutting behavior of methylation-sensitive isoschizomer pairs.
     * For each pair, counts how many recognition sites overlap a CpG dinucleotide
     * and would therefore be blocked by CpG methylation.
     */
    fun methylationSensitiveComparison(seq: Seq): List<CpGComparisonReport> {
        val bases = seq.bases.uppercase()
        val results = ArrayList<CpGComparisonReport>()
        for ((label, e1Name, e2Name) in METHYL_SENSITIVE_PAIRS) {
            val siteSeq = ISOSCHIZOMER_SITES[e1Name] ?: continue
            val e1 = Enzymes.find(e1Name) ?: continue
            val sites = Digest.cutSites(seq, listOf(e1), MethylationProfile(dam = false, dcm = false, cpg = false))
            var blocked = 0
            for ((_, start) in sites) {
                if (start + siteSeq.length <= bases.length) {
                    val region = bases.substring(start, start + siteSeq.length)
                    if (CpG_PATTERN.containsMatchIn(region)) {
                        blocked++
                    }
                }
            }
            results += CpGComparisonReport(label, e1Name, e2Name, sites.size, blocked)
        }
        return results
    }
}
