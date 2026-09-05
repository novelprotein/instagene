package org.instagene.core

/** Result of applying a conservative, curated host/strain rule. */
data class HostMethylationInference(
    val profile: MethylationProfile,
    val matchedHost: String? = null,
)

/**
 * Small, auditable host table used by the Info panel's "Infer from host" action.
 * Unknown hosts intentionally remain unknown rather than assuming a wild-type
 * methylation pattern.
 */
object HostMethylationInferenceRules {
    val hostTypeSuggestions: List<String> = listOf(
        "Bacterial", "Mammalian", "Insect", "Yeast", "Cell-free", "Other",
    )

    /**
     * Compatibility view for callers that still use the old property name.
     * The Info panel now presents the grouped [SequenceClassCatalog].
     */
    val nucleicAcidCategorySuggestions: List<String>
        get() = SequenceClassCatalog.labels

    private val damDcmPositive = setOf(
        "dh5alpha", "dh5a", "top10", "xl1blue", "jm109", "bl21", "bl21de3", "stbl3",
        "neb5alpha", "rosetta", "rosettade3",
    )
    private val damDcmNegative = setOf(
        "jm110", "scs110", "gm2163", "er1821",
    )

    fun infer(hostType: String?, strain: String?): HostMethylationInference {
        val normalizedType = hostType.orEmpty().normalizeHostText()
        val normalizedStrain = strain.orEmpty().normalizeHostText()
        val typeKey = normalizedType.compactHostText()
        val strainKey = normalizedStrain.compactHostText()
        val knownBacterialStrain = strainKey in damDcmPositive || strainKey in damDcmNegative
        if (!knownBacterialStrain && typeKey != "bacterial" && typeKey != "ecoli" && typeKey != "escherichiacoli") {
            return HostMethylationInference(MethylationProfile.unknown(), null)
        }
        if (normalizedStrain.contains("dam-") || normalizedStrain.contains("dam negative")) {
            return HostMethylationInference(MethylationProfile(dam = false, dcm = false, cpg = null), strain)
        }
        if (normalizedStrain.contains("dcm-") || normalizedStrain.contains("dcm negative")) {
            return HostMethylationInference(MethylationProfile(dam = false, dcm = false, cpg = null), strain)
        }
        return when {
            strainKey in damDcmPositive -> HostMethylationInference(
                MethylationProfile(dam = true, dcm = true, cpg = null), strain,
            )
            strainKey in damDcmNegative -> HostMethylationInference(
                MethylationProfile(dam = false, dcm = false, cpg = null), strain,
            )
            else -> HostMethylationInference(MethylationProfile.unknown(), null)
        }
    }

    private fun String.normalizeHostText(): String = lowercase()
        .replace('α', 'a')
        .replace('−', '-')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.compactHostText(): String = replace(Regex("[^a-z0-9]"), "")
}
