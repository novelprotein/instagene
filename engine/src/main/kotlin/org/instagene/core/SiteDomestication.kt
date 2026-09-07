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
        Enzyme("BsaI", "GGTCTC", 7, 11),
        Enzyme("BbsI", "GAAGAC", 8, 12),
        Enzyme("BsmBI", "CGTCTC", 7, 11),
        Enzyme("BpiI", "GAAGAC", 8, 12),
        Enzyme("AarI", "CACCTGC", 11, 15),
        Enzyme("Esp3I", "CGTCTC", 7, 11),
        Enzyme("BfuAI", "ACCTGC", 10, 14),
        Enzyme("BbsI-HF", "GAAGAC", 8, 12),
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

        require(translations.values.none { it.hasErrors }) { "Resolve CDS translation errors before domestication." }
        // Each accepted edit strictly removes selected sites and introduces none: this terminates.
        while (true) {
            val beforeSites = enzymes.flatMap { enzyme ->
                findPositionsBothStrands(bases, enzyme.site.uppercase()).map { enzyme.name to it }
            }.toSet()
            val changed = enzymes.any { enzyme ->
                findPositionsBothStrands(bases, enzyme.site.uppercase()).any { position ->
                    trySilentMutate(bases, position, enzyme.site.uppercase(), codingFeatures, seq, translations,
                        enzymes, beforeSites)
                }
            }
            if (!changed) break
            mutations++
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
                i++
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
        enzymes: List<Enzyme>,
        beforeSites: Set<Pair<String, Int>>,
    ): Boolean {
        for (feature in codingFeatures.filter { it.type.equals("CDS", true) }) {
            val table = CodonTable.byId(feature.geneticCodeId)
            // Reuse translation's biological coordinates, including reverse and joined CDSs.
            val current = FeatureTranslations.translate(originalSeq.copy(bases = bases.concatToString()), feature)
            for (codon in current.codons) {
                val positions = codon.sourcePositions
                if (positions.none { it in sitePos until sitePos + site.length }) continue
                if (codon.aminoAcid == '*' || codon.aminoAcid == 'X') continue
                for (syn in synonymousCodons(table, codon.aminoAcid)) {
                    if (syn == codon.codon) continue
                    val saved = positions.map { bases[it] }
                    positions.forEachIndexed { index, position ->
                        bases[position] = if (feature.strand == Strand.REVERSE)
                            Alphabet.complement(syn[index], SeqKind.DNA) else syn[index]
                    }
                    val afterSites = enzymes.flatMap { enzyme ->
                        findPositionsBothStrands(bases, enzyme.site.uppercase()).map { enzyme.name to it }
                    }.toSet()
                    val candidate = originalSeq.copy(bases = bases.concatToString())
                    val safe = afterSites.size < beforeSites.size && beforeSites.containsAll(afterSites) &&
                        sitePos !in findPositionsBothStrands(bases, site) &&
                        translations.all { (cds, before) ->
                            FeatureTranslations.translate(candidate, cds).protein == before.protein
                        }
                    if (safe) return true
                    positions.forEachIndexed { index, position -> bases[position] = saved[index] }
                }
            }
        }
        return false
    }

    private val SYNONYMOUS_CODONS: Map<CodonTable, Map<Char, List<String>>> = CodonTable.ALL.associateWith { table ->
        val bases = "TCAG"
        val codons = Array(64) { "${bases[it / 16]}${bases[(it / 4) % 4]}${bases[it % 4]}" }
        buildMap {
            for (codon in codons) {
                val aa = table.translate(codon)
                if (aa != '*' && aa != 'X') getOrPut(aa) { mutableListOf() }.let { (it as MutableList).add(codon) }
            }
        }
    }

    private fun synonymousCodons(table: CodonTable, aminoAcid: Char): List<String> =
        SYNONYMOUS_CODONS[table]?.get(aminoAcid).orEmpty()
}
