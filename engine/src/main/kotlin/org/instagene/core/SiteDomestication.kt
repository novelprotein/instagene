package org.instagene.core

data class InternalSite(val enzyme: Enzyme, val position: Int)

data class DomesticateResult(val domesticated: Seq, val mutationsApplied: Int)

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
            for (cutSite in Digest.cutSites(seq, listOf(enzyme))) {
                sites.add(InternalSite(enzyme, cutSite.recognitionStart))
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

    fun domesticate(seq: Seq, enzymes: List<Enzyme>): DomesticateResult {
        val bases = seq.bases.uppercase().toCharArray()
        var mutations = 0

        for (enzyme in enzymes) {
            var changed = true
            while (changed) {
                changed = false
                val sites = findPositions(bases, enzyme.site.uppercase())
                if (sites.isEmpty()) break
                for (sitePos in sites) {
                    if (trySilentMutate(bases, sitePos, enzyme.site)) {
                        mutations++
                        changed = true
                        break
                    }
                }
            }
        }

        return DomesticateResult(
            domesticated = seq.copy(bases = bases.joinToString("")),
            mutationsApplied = mutations,
        )
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

    private fun matchesSite(bases: CharArray, pos: Int, site: String): Boolean {
        for (j in site.indices) {
            if (bases[pos + j] != site[j]) return false
        }
        return true
    }

    private fun trySilentMutate(bases: CharArray, sitePos: Int, site: String): Boolean {
        val siteLen = site.length
        val codonStart = (sitePos / 3) * 3
        val codonEnd = ((sitePos + siteLen - 1) / 3 + 1) * 3
        val codonPositions = (codonStart until codonEnd step 3).toList()

        for (codonPos in codonPositions) {
            if (codonPos + 3 > bases.size) continue
            val originalCodon = String(bases, codonPos, 3)
            val originalAA = CodonTable.STANDARD.translate(originalCodon)
            if (originalAA == '*' || originalAA == 'X') continue

            val synonymous = synonymousCodons(originalAA)
            for (syn in synonymous) {
                if (syn == originalCodon) continue
                val saved = CharArray(3) { bases[codonPos + it] }
                for (k in 0..2) bases[codonPos + k] = syn[k]
                val siteStillPresent = (0..bases.size - siteLen).any { matchesSite(bases, it, site.uppercase()) }
                if (!siteStillPresent) return true
                for (k in 0..2) bases[codonPos + k] = saved[k]
            }
        }
        return false
    }

    private fun synonymousCodons(aa: Char): List<String> {
        val bases = "TCAG"
        val codons = mutableListOf<String>()
        for (a in bases) for (b in bases) for (c in bases) {
            val codon = "$a$b$c"
            if (CodonTable.STANDARD.translate(codon) == aa) codons.add(codon)
        }
        return codons
    }
}
