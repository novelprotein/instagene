package org.instagene.core

data class MethylationProfile(val dam: Boolean = false, val dcm: Boolean = false)
data class RestrictionReport(val enzyme: Enzyme, val count: Int, val positions: List<Int>)
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
    fun reports(seq: Seq, enzymes: Collection<Enzyme>, selection: IntRange? = null): List<RestrictionReport> = enzymes.map { enzyme ->
        val sites = Digest.cutSites(seq, enzyme).filter { site -> selection == null || site.recognitionStart in selection }
        RestrictionReport(enzyme, sites.size, sites.map { it.recognitionStart + 1 })
    }.sortedBy { it.enzyme.name.lowercase() }

    fun unique(seq: Seq, enzymes: Collection<Enzyme> = Enzymes.ALL): List<Enzyme> = enzymes.filter { Digest.countSites(seq, it) == 1 }
    fun absent(seq: Seq, enzymes: Collection<Enzyme> = Enzymes.ALL): List<Enzyme> = enzymes.filter { Digest.countSites(seq, it) == 0 }

    /** Filters sites blocked by common Dam/Dcm methylation motifs. */
    fun cutSites(seq: Seq, enzymes: Collection<Enzyme>, profile: MethylationProfile): List<CutSite> =
        Digest.cutSites(seq, enzymes).filterNot { site ->
            val context = seq.sub(site.recognitionStart - 4, site.recognitionStart + site.enzyme.siteLength + 4).uppercase()
            (profile.dam && context.contains("GATC")) || (profile.dcm && Regex("CC[ACT]GG").containsMatchIn(context))
        }

    fun insertRecognitionSite(enzyme: Enzyme, reverse: Boolean = false): String {
        val concrete = enzyme.site.map { Alphabet.expansion(it)?.firstOrNull() ?: 'N' }.joinToString("")
        return if (reverse) concrete.reversed().map { Alphabet.complement(it, SeqKind.DNA) }.joinToString("") else concrete
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
            for (strand in listOf(Strand.FORWARD, Strand.REVERSE)) {
                val site = if (strand == Strand.FORWARD) enzyme.site else insertRecognitionSite(enzyme, reverse = true)
                for (position in start..(end - site.length).coerceAtLeast(start)) {
                    val current = seq.bases.substring(position, position + site.length).uppercase()
                    val mismatch = site.indices.count { !Alphabet.matches(site[it], current[it]) }
                    if (mismatch !in 1..maxMismatches) continue
                    val mutated = buildString {
                        current.forEachIndexed { index, base -> append(Alphabet.expansion(site[index])?.firstOrNull() ?: base) }
                    }
                    if (mutated == current || Digest.cutSites(seq, listOf(enzyme)).any { it.recognitionStart == position }) continue
                    val changed = seq.bases.substring(0, position) + mutated + seq.bases.substring(position + site.length)
                    if (requireSilent && SeqOps.translateBases(changed.substring(start, end)) != originalProtein) continue
                    candidates += MutationCandidate(enzyme, position, strand, current, mutated, mismatch, originalProtein)
                }
            }
        }
        return candidates.distinctBy { Triple(it.enzyme.name, it.position, it.strand) }
            .sortedWith(compareBy({ it.position }, { it.enzyme.name }))
    }
}
