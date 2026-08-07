package org.instagene.core

/**
 * A type II restriction enzyme.
 *
 * [topCut] and [bottomCut] are offsets from the start of the recognition [site]
 * on the top and bottom strands respectively. EcoRI (`G^AATTC`) therefore has
 * `topCut = 1` and `bottomCut = 5`, giving a 4-base 5' overhang.
 */
data class Enzyme(
    val name: String,
    val site: String,
    val topCut: Int,
    val bottomCut: Int,
    val supplier: String = "",
) {
    val siteLength: Int get() = site.length

    /** Positive for a 5' overhang, negative for a 3' overhang, zero for blunt. */
    val overhangLength: Int get() = bottomCut - topCut

    val endType: EndType
        get() = when {
            overhangLength > 0 -> EndType.FIVE_PRIME_OVERHANG
            overhangLength < 0 -> EndType.THREE_PRIME_OVERHANG
            else -> EndType.BLUNT
        }

    val isPalindromic: Boolean
        get() = site == site.reversed().map { Alphabet.complement(it, SeqKind.DNA) }.joinToString("")

    /** Human-readable cut notation, e.g. `G^AATTC`. */
    fun notation(): String = buildString {
        append(site.substring(0, topCut.coerceIn(0, site.length)))
        append('^')
        append(site.substring(topCut.coerceIn(0, site.length)))
    }

    override fun toString(): String = "$name (${notation()}, ${endType.label})"
}

enum class EndType(val label: String) {
    BLUNT("blunt"),
    FIVE_PRIME_OVERHANG("5' overhang"),
    THREE_PRIME_OVERHANG("3' overhang"),
}

/** The commonly stocked cloning enzymes, enough to cover a typical MCS. */
object Enzymes {

    val ALL: List<Enzyme> = listOf(
        Enzyme("AatII", "GACGTC", 5, 1),
        Enzyme("AccI", "GTMKAC", 2, 4),
        Enzyme("AflII", "CTTAAG", 1, 5),
        Enzyme("AgeI", "ACCGGT", 1, 5),
        Enzyme("ApaI", "GGGCCC", 5, 1),
        Enzyme("AseI", "ATTAAT", 2, 4),
        Enzyme("AvrII", "CCTAGG", 1, 5),
        Enzyme("BamHI", "GGATCC", 1, 5),
        Enzyme("BclI", "TGATCA", 1, 5),
        Enzyme("BglII", "AGATCT", 1, 5),
        Enzyme("BsrGI", "TGTACA", 1, 5),
        Enzyme("BssHII", "GCGCGC", 1, 5),
        Enzyme("ClaI", "ATCGAT", 2, 4),
        Enzyme("DpnI", "GATC", 2, 2),
        Enzyme("DraI", "TTTAAA", 3, 3),
        Enzyme("EagI", "CGGCCG", 1, 5),
        Enzyme("EcoRI", "GAATTC", 1, 5),
        Enzyme("EcoRV", "GATATC", 3, 3),
        Enzyme("HaeIII", "GGCC", 2, 2),
        Enzyme("HinDIII", "AAGCTT", 1, 5),
        Enzyme("HpaI", "GTTAAC", 3, 3),
        Enzyme("KpnI", "GGTACC", 5, 1),
        Enzyme("MfeI", "CAATTG", 1, 5),
        Enzyme("MluI", "ACGCGT", 1, 5),
        Enzyme("NaeI", "GCCGGC", 3, 3),
        Enzyme("NcoI", "CCATGG", 1, 5),
        Enzyme("NdeI", "CATATG", 2, 4),
        Enzyme("NheI", "GCTAGC", 1, 5),
        Enzyme("NotI", "GCGGCCGC", 2, 6),
        Enzyme("NruI", "TCGCGA", 3, 3),
        Enzyme("PacI", "TTAATTAA", 5, 3),
        Enzyme("PmeI", "GTTTAAAC", 4, 4),
        Enzyme("PstI", "CTGCAG", 5, 1),
        Enzyme("PvuI", "CGATCG", 4, 2),
        Enzyme("PvuII", "CAGCTG", 3, 3),
        Enzyme("SacI", "GAGCTC", 5, 1),
        Enzyme("SacII", "CCGCGG", 4, 2),
        Enzyme("SalI", "GTCGAC", 1, 5),
        Enzyme("SbfI", "CCTGCAGG", 6, 2),
        Enzyme("ScaI", "AGTACT", 3, 3),
        Enzyme("SmaI", "CCCGGG", 3, 3),
        Enzyme("SnaBI", "TACGTA", 3, 3),
        Enzyme("SpeI", "ACTAGT", 1, 5),
        Enzyme("SphI", "GCATGC", 5, 1),
        Enzyme("SspI", "AATATT", 3, 3),
        Enzyme("StuI", "AGGCCT", 3, 3),
        Enzyme("XbaI", "TCTAGA", 1, 5),
        Enzyme("XhoI", "CTCGAG", 1, 5),
        Enzyme("XmaI", "CCCGGG", 1, 5),
    ).sortedBy { it.name.lowercase() }

    private val byName = ALL.associateBy { it.name.lowercase() }

    /** Alternate spellings that resolve to a canonical entry, e.g. `HindIII` for `HinDIII`. */
    private val aliases = mapOf(
        "hindiii" to "hindiii",
    )

    fun find(name: String): Enzyme? {
        val key = name.trim().lowercase()
        return byName[key] ?: aliases[key]?.let { byName[it] }
    }

    fun require(name: String): Enzyme =
        find(name) ?: throw IllegalArgumentException(
            "Unknown enzyme '$name'. Try one of: ${ALL.joinToString(", ") { it.name }}"
        )

    /** Parses a comma- or space-separated enzyme list from CLI input. */
    fun parseList(spec: String): List<Enzyme> =
        spec.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { require(it) }
}
