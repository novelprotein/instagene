package org.instagene.core

data class InternalSite(val enzyme: Enzyme, val position: Int)

data class DomesticateResult(
    val domesticated: Seq,
    val mutationsApplied: Int,
    val unresolvedSites: List<InternalSite> = emptyList(),
    /** A reviewable sequence containing the mutations found so far. */
    val mutationPreview: Seq = domesticated,
)

object SiteDomestication {

    val GOLDEN_GATE_ENZYMES: List<Enzyme> = listOf(
        Enzyme("BsaI", "GGTCTC", 1, 5),
        Enzyme("BbsI", "GAAGAC", 2, 6),
        Enzyme("BsmBI", "CGTCTC", 1, 5),
        Enzyme("BpiI", "GAAGAC", 2, 6),
        Enzyme("AarI", "CACCTGC", 1, 5),
        Enzyme("Esp3I", "CGTCTC", 1, 5),
        Enzyme("BfuAI", "ACCTGC", 1, 5),
        Enzyme("BbsI-HF", "GAAGAC", 2, 6),
    )

    fun findInternalSites(seq: Seq, enzymes: List<Enzyme> = GOLDEN_GATE_ENZYMES): List<InternalSite> {
        val sites = mutableListOf<InternalSite>()
        for (enzyme in enzymes) {
            for ((_, recognitionStart) in Digest.cutSites(seq, listOf(enzyme))) {
                sites.add(InternalSite(enzyme, recognitionStart))
            }
        }
        return sites.sortedBy { it.position }
    }

    fun suggestEnzyme(seq: Seq): Pair<Enzyme, Int> {
        var bestEnzyme = GOLDEN_GATE_ENZYMES.first()
        var bestCount = 0
        for (enzyme in GOLDEN_GATE_ENZYMES) {
            val count = Digest.countSites(seq, enzyme)
            if (count > bestCount) {
                bestCount = count
                bestEnzyme = enzyme
            }
        }
        return bestEnzyme to bestCount
    }

    /**
     * Removes recognition sites by synonymous substitutions in explicitly
     * supplied CDS annotations.  An unannotated sequence is never silently
     * treated as coding.
     *
     * Circular records are rejected because a site or CDS crossing the origin
     * needs a compound annotation to describe its reading frame unambiguously.
     */
    fun domesticate(
        seq: Seq,
        enzymes: List<Enzyme>,
        codingFeatures: List<Feature> = emptyList(),
    ): DomesticateResult {
        require(!seq.isCircular) {
            "Domestication of circular sequences is unsupported; linearize or split origin-spanning features first."
        }
        val bases = seq.bases.uppercase().toCharArray()
        var mutations = 0
        val translations = codingFeatures.associateWith { FeatureTranslations.translate(seq, it) }

        for (enzyme in enzymes) {
            val site = enzyme.site.uppercase()
            var changed = true
            while (changed) {
                changed = false
                val sites = findPositionsBothStrands(bases, site)
                if (sites.isEmpty()) break
                for (sitePos in sites) {
                    if (trySilentMutate(bases, sitePos, site, codingFeatures, seq, translations)) {
                        mutations++
                        changed = true
                        break
                    }
                }
            }
        }

        val preview = seq.copy(bases = bases.joinToString(""))
        val remaining = enzymes.flatMap { enzyme ->
            findPositionsBothStrands(bases, enzyme.site.uppercase()).map { InternalSite(enzyme, it) }
        }.distinctBy { it.enzyme.name to it.position }
        val finalTranslations = codingFeatures.associateWith { FeatureTranslations.translate(preview, it) }
        require(translations.all { (feature, before) ->
            finalTranslations[feature]?.protein == before.protein
        }) { "A proposed domestication would alter an annotated CDS translation." }
        return DomesticateResult(preview, mutations, remaining.distinctBy { it.enzyme.name to it.position }, preview)
    }

    private fun findPositions(bases: CharArray, site: String): List<Int> {
        val positions = mutableListOf<Int>()
        var i = 0
        while (i <= bases.size - site.length) {
            if (matchesSite(bases, i, site)) {
                positions.add(i)
                i += site.length
            } else {
                i++
            }
        }
        return positions
    }

    private fun findPositionsBothStrands(bases: CharArray, site: String): List<Int> {
        val rc = Alphabet.reverseComplement(site)
        return (findPositions(bases, site) + if (rc == site) emptyList() else findPositions(bases, rc))
            .distinct().sorted()
    }

    private fun matchesSite(bases: CharArray, pos: Int, site: String): Boolean {
        for (j in site.indices) {
            if (bases[pos + j] != site[j]) return false
        }
        return true
    }

    private fun trySilentMutate(
        bases: CharArray,
        sitePos: Int,
        site: String,
        codingFeatures: List<Feature>,
        originalSeq: Seq,
        translations: Map<Feature, FeatureTranslationResult>,
    ): Boolean {
        val siteEnd = sitePos + site.length
        val feature = codingFeatures.firstOrNull { f ->
            f.type.equals("CDS", true) && f.strand == Strand.FORWARD &&
                f.locationSegments.size == 1 && sitePos >= f.start && siteEnd <= f.end
        } ?: codingFeatures.firstOrNull { f ->
            f.type.equals("CDS", true) && f.strand == Strand.REVERSE &&
                f.locationSegments.size == 1 && sitePos >= f.start && siteEnd <= f.end
        } ?: return false
        val codonIndex = if (feature.strand == Strand.FORWARD) {
            (sitePos - feature.start - feature.translationStartOffset) / 3
        } else {
            (feature.end - siteEnd - feature.translationStartOffset) / 3
        }
        if (codonIndex < 0) return false
        val codonPositions = if (feature.strand == Strand.FORWARD) {
            val start = feature.start + feature.translationStartOffset + codonIndex * 3
            listOf(start, start + 1, start + 2)
        } else {
            val start = feature.end - feature.translationStartOffset - (codonIndex + 1) * 3
            listOf(start + 2, start + 1, start)
        }
        if (codonPositions.any { it !in bases.indices }) return false
        val originalCodon = codonPositions.joinToString("") { bases[it].toString() }
            .let { if (feature.strand == Strand.REVERSE) Alphabet.reverseComplement(it) else it }
        val originalAA = CodonTable.byId(feature.geneticCodeId).translate(originalCodon)
        if (originalAA == '*' || originalAA == 'X') return false
        val synonymous = SYNONYMOUS_CODONS[originalAA] ?: return false
        for (syn in synonymous) {
            if (syn == originalCodon) continue
            val saved = codonPositions.map { bases[it] }
            val replacement = if (feature.strand == Strand.REVERSE) Alphabet.reverseComplement(syn) else syn
            codonPositions.forEachIndexed { index, position -> bases[position] = replacement[index] }
            val candidate = originalSeq.copy(bases = bases.concatToString())
            val translated = FeatureTranslations.translate(candidate, feature)
            val localSites = findPositionsBothStrands(bases, site)
            if (translated.protein == translations[feature]?.protein && sitePos !in localSites) return true
            codonPositions.forEachIndexed { index, position -> bases[position] = saved[index] }
        }
        return false
    }

    /** Precomputed map of amino acid to its synonymous codons (zero-allocation lookup). */
    private val SYNONYMOUS_CODONS: Map<Char, List<String>> = buildMap {
        val bases = "TCAG"
        val codons = Array(64) { "${bases[it / 16]}${bases[(it / 4) % 4]}${bases[it % 4]}" }
        for (codon in codons) {
            val aa = CodonTable.STANDARD.translate(codon)
            if (aa != '*' && aa != 'X') {
                getOrPut(aa) { mutableListOf() }.let { (it as MutableList).add(codon) }
            }
        }
    }
}
