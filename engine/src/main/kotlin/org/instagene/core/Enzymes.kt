package org.instagene.core

import kotlinx.serialization.Serializable

/**
 * A type II restriction enzyme.
 *
 * [topCut] and [bottomCut] are offsets from the start of the recognition [site]
 * on the top and bottom strands respectively. EcoRI (`G^AATTC`) therefore has
 * `topCut = 1` and `bottomCut = 5`, giving a 4-base 5' overhang.
 */
@Serializable
data class Enzyme(
    val name: String,
    val site: String,
    val topCut: Int,
    val bottomCut: Int,
    val supplier: String = "",
) {
    /** Length of the recognition [site]. */
    val siteLength: Int get() = site.length

    /** Positive for a 5' overhang, negative for a 3' overhang, zero for blunt. */
    val overhangLength: Int get() = bottomCut - topCut

    /** The end the enzyme leaves: blunt, 5' or 3' overhang, from the relative cut positions. */
    val endType: EndType
        get() = when {
            overhangLength > 0 -> EndType.FIVE_PRIME_OVERHANG
            overhangLength < 0 -> EndType.THREE_PRIME_OVERHANG
            else -> EndType.BLUNT
        }

    /** True when the recognition [site] reads identically on the reverse strand, i.e. equals its reverse complement. */
    val isPalindromic: Boolean
        get() = site == Alphabet.reverseComplement(site)

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

/** Curated, non-UI provenance for a bundled enzyme description. */
data class BuiltInEnzymeInfo(val description: String, val sourceUrl: String)

/** The commonly stocked cloning enzymes, enough to cover a typical MCS. */
object Enzymes {

    /** The built-in catalog of commonly stocked cloning enzymes, deduplicated by name. */
    val ALL: List<Enzyme> = listOf(
        Enzyme("AatII", "GACGTC", 5, 1),
        Enzyme("AccII", "CGCG", 2, 2),
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
        Enzyme("BstUI", "CGCG", 2, 2),
        Enzyme("ClaI", "ATCGAT", 2, 4),
        Enzyme("DpnI", "GATC", 2, 2),
        Enzyme("DraI", "TTTAAA", 3, 3),
        Enzyme("EagI", "CGGCCG", 1, 5),
        Enzyme("EcoRI", "GAATTC", 1, 5),
        Enzyme("EcoRV", "GATATC", 3, 3),
        Enzyme("HaeIII", "GGCC", 2, 2),
        Enzyme("HinDIII", "AAGCTT", 1, 5),
        Enzyme("HpaII", "CCGG", 1, 3),
        Enzyme("HpaI", "GTTAAC", 3, 3),
        Enzyme("KpnI", "GGTACC", 5, 1),
        Enzyme("MfeI", "CAATTG", 1, 5),
        Enzyme("MluI", "ACGCGT", 1, 5),
        Enzyme("MspI", "CCGG", 1, 3),
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

    /** Individually researched, compact descriptions and their source records. */
    val BUILTIN_INFO: Map<String, BuiltInEnzymeInfo> = mapOf(
        "aatii" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/AatII.html"),
        "accii" to BuiltInEnzymeInfo("A blunt-end enzyme whose recognition site contains CpG; blocked by CpG methylation.", "https://rebase.neb.com/rebase/enz/AccII.html"),
        "acci" to BuiltInEnzymeInfo("A flexible cloning enzyme. CpG methylation can prevent cutting.", "https://www.neb.com/en-us/products/r0161-acci"),
        "aflii" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/AflII.html"),
        "agei" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/AgeI.html"),
        "apai" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/ApaI.html"),
        "asei" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/AseI.html"),
        "avrii" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with SpeI, XbaI, and NheI.", "https://rebase.neb.com/rebase/enz/AvrII.html"),
        "bamhi" to BuiltInEnzymeInfo("A common cloning enzyme with ends compatible with BglII and BclI.", "https://rebase.neb.com/rebase/enz/BamHI.html"),
        "bcli" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with BamHI. dam methylation prevents cutting.", "https://www.neb.com/en/products/r0160-bcli"),
        "bglii" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with BamHI and BclI.", "https://rebase.neb.com/rebase/enz/BglII.html"),
        "bsrgi" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/BsrGI.html"),
        "bsshii" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/BssHII.html"),
        "bstui" to BuiltInEnzymeInfo("A blunt-end enzyme whose recognition site contains CpG; isoschizomer of AccII.", "https://rebase.neb.com/rebase/enz/BstUI.html"),
        "clai" to BuiltInEnzymeInfo("A cloning enzyme. dam or CpG methylation can prevent cutting.", "https://www.neb.com/en/products/r0197-clai"),
        "dpni" to BuiltInEnzymeInfo("Cuts methylated DNA; useful for removing template plasmid after PCR.", "https://www.neb.com/products/r0176-dpni"),
        "drai" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/DraI.html"),
        "eagi" to BuiltInEnzymeInfo("A cloning enzyme. CpG methylation can prevent cutting.", "https://www.neb.com/en/products/r0505-eagi"),
        "ecori" to BuiltInEnzymeInfo("A common cloning enzyme with ends compatible with MfeI.", "https://rebase.neb.com/rebase/enz/EcoRI.html"),
        "ecorv" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/EcoRV.html"),
        "haeiii" to BuiltInEnzymeInfo("A frequent-cutting, blunt-end enzyme for making small DNA fragments.", "https://rebase.neb.com/rebase/enz/HaeIII.html"),
        "hindiii" to BuiltInEnzymeInfo("A common DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/HindIII.html"),
        "hpai" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/HpaI.html"),
        "hpaii" to BuiltInEnzymeInfo("A methylation-sensitive enzyme; blocked by 5-methylcytosine at CpG. Isoschizomer of MspI.", "https://rebase.neb.com/rebase/enz/HpaII.html"),
        "kpni" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/KpnI.html"),
        "mfei" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with EcoRI.", "https://rebase.neb.com/rebase/enz/MfeI.html"),
        "mlui" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/MluI.html"),
        "mspi" to BuiltInEnzymeInfo("A methylation-insensitive isoschizomer of HpaII; cuts regardless of CpG methylation.", "https://rebase.neb.com/rebase/enz/MspI.html"),
        "naei" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/NaeI.html"),
        "ncoi" to BuiltInEnzymeInfo("Useful for cloning coding sequences because its site includes a start codon.", "https://rebase.neb.com/rebase/enz/NcoI.html"),
        "ndei" to BuiltInEnzymeInfo("Useful for cloning coding sequences because its site includes a start codon.", "https://rebase.neb.com/rebase/enz/NdeI.html"),
        "nhei" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with SpeI, XbaI, and AvrII.", "https://rebase.neb.com/rebase/enz/NheI.html"),
        "noti" to BuiltInEnzymeInfo("A rare-cutting enzyme for cloning large DNA fragments.", "https://rebase.neb.com/rebase/enz/NotI.html"),
        "nrui" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/NruI.html"),
        "paci" to BuiltInEnzymeInfo("A rare-cutting enzyme for cloning large DNA fragments.", "https://rebase.neb.com/rebase/enz/PacI.html"),
        "pmei" to BuiltInEnzymeInfo("A rare-cutting, blunt-end enzyme for cloning large DNA fragments.", "https://rebase.neb.com/rebase/enz/PmeI.html"),
        "psti" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/PstI.html"),
        "pvui" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/PvuI.html"),
        "pvuii" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/PvuII.html"),
        "saci" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/SacI.html"),
        "sacii" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/SacII.html"),
        "sali" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with XhoI.", "https://rebase.neb.com/rebase/enz/SalI.html"),
        "sbfi" to BuiltInEnzymeInfo("A rare-cutting enzyme for cloning large DNA fragments.", "https://rebase.neb.com/rebase/enz/SbfI.html"),
        "scai" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/ScaI.html"),
        "smai" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/SmaI.html"),
        "snabi" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/SnaBI.html"),
        "spei" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with XbaI, NheI, and AvrII.", "https://rebase.neb.com/rebase/enz/SpeI.html"),
        "sphi" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/SphI.html"),
        "sspi" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/SspI.html"),
        "stui" to BuiltInEnzymeInfo("A blunt-end restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/StuI.html"),
        "xbai" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with SpeI, NheI, and AvrII.", "https://rebase.neb.com/rebase/enz/XbaI.html"),
        "xhoi" to BuiltInEnzymeInfo("A cloning enzyme with ends compatible with SalI.", "https://rebase.neb.com/rebase/enz/XhoI.html"),
        "xmai" to BuiltInEnzymeInfo("A standard DNA restriction enzyme for cloning.", "https://rebase.neb.com/rebase/enz/XmaI.html"),
    )

    /** Convenience view used by the desktop description resolver. */
    val BUILTIN_DESCRIPTIONS: Map<String, String> = BUILTIN_INFO.mapValues { it.value.description }

    /** A compact explanation suitable for a table cell or enzyme editor. */
    fun simpleDescription(enzyme: Enzyme, functionalNote: String? = null): String {
        val ends = when (enzyme.endType) {
            EndType.BLUNT -> "blunt ends"
            EndType.FIVE_PRIME_OVERHANG -> "5' sticky ends"
            EndType.THREE_PRIME_OVERHANG -> "3' sticky ends"
        }
        return buildString {
            append("${enzyme.name} is a DNA restriction enzyme that recognizes ${enzyme.site} and creates $ends.")
            if (functionalNote != null) append(' ').append(functionalNote)
        }
    }

    private val byName = ALL.associateBy { it.name.lowercase() }

    /**
     * The working catalog: built-in [ALL] merged with the [custom] novel enzymes,
     * deduplicated by case-insensitive name (built-ins win).
     */
    fun pool(custom: Collection<Enzyme> = emptyList()): List<Enzyme> {
        if (custom.isEmpty()) return ALL
        val seen = HashSet<String>()
        val merged = ArrayList<Enzyme>(ALL.size + custom.size)
        for (enzyme in ALL + custom) {
            if (seen.add(enzyme.name.lowercase())) merged += enzyme
        }
        return merged
    }

    /**
     * The active working set: [pool] restricted to the enzymes named in
     * [enabled]. An empty [enabled] list means the whole pool is active.
     */
    fun enzymesFor(pool: List<Enzyme>, enabled: Collection<String>): List<Enzyme> {
        val wanted = enabled.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return pool
        return pool.filter { it.name.lowercase() in wanted }
    }

    /** The IUPAC symbols accepted in a recognition site (DNA codes only, no gap). */
    private val SITE_CODES = Alphabet.NUCLEOTIDES.filter { it != '-' }

    /**
     * Validates a novel enzyme definition, returning an error message, or null
     * when [name]/[site]/[topCut]/[bottomCut] form a usable restriction enzyme.
     */
    fun validateNew(name: String, site: String, topCut: Int, bottomCut: Int): String? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Enzyme name cannot be empty."
        if (trimmedName.any { it.isWhitespace() }) return "Enzyme name cannot contain spaces."
        val trimmedSite = site.trim().uppercase()
        if (trimmedSite.isEmpty()) return "Recognition site cannot be empty."
        val bad = trimmedSite.filterNot { it in SITE_CODES }
        if (bad.isNotEmpty()) {
            return "Recognition site contains invalid IUPAC character(s): ${bad.toSet().sorted().joinToString(" ")}"
        }
        if (topCut !in 0..trimmedSite.length || bottomCut !in 0..trimmedSite.length) {
            return "Cut positions must be between 0 and ${trimmedSite.length} (the site length)."
        }
        return null
    }

    /** The enzyme named [name], from [custom] or the built-in catalog (case-insensitive), or null when unknown. */
    fun find(name: String, custom: Collection<Enzyme> = emptyList()): Enzyme? {
        val key = name.trim().lowercase()
        return byName[key]
            ?: custom.firstOrNull { it.name.trim().lowercase() == key }
    }

    /** [find], or throw an [IllegalArgumentException] listing the available enzymes. */
    fun require(name: String, custom: Collection<Enzyme> = emptyList()): Enzyme =
        find(name, custom) ?: throw IllegalArgumentException(
            "Unknown enzyme '$name'. Try one of: ${pool(custom).joinToString(", ") { it.name }}"
        )

    /** Parses a comma- or space-separated enzyme list from CLI input. */
    fun parseList(spec: String, custom: Collection<Enzyme> = emptyList()): List<Enzyme> =
        spec.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { require(it, custom) }
}
