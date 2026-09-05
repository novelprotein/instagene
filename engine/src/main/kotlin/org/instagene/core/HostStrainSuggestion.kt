package org.instagene.core

data class HostStrainSuggestionInput(
    val sequenceKind: SeqKind = SeqKind.DNA,
    val hostType: String? = null,
    val hostStrain: String? = null,
    val organism: String? = null,
    val taxonomy: List<String> = emptyList(),
    val description: String = "",
)

data class HostStrainSuggestion(
    val strain: String,
    val hostType: String,
    val organism: String,
    val score: Int,
    val rationale: String,
)

/**
 * Ranks practical laboratory hosts from record metadata. This is a
 * decision-support heuristic, not a claim that a host will express a gene.
 */
object HostStrainSuggestionEngine {
    private data class Candidate(
        val strain: String,
        val hostType: String,
        val organism: String,
        val aliases: Set<String>,
        val baseScore: Int,
        val rationale: String,
    )

    private val candidates = listOf(
        Candidate("DH5α", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "enterobacteriaceae", "bacteria", "bacterial"), 80, "high-copy cloning and plasmid propagation"),
        Candidate("TOP10", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 76, "routine cloning and plasmid propagation"),
        Candidate("BL21(DE3)", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 72, "recombinant protein expression"),
        Candidate("Stbl3", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 68, "propagation of unstable or repetitive constructs"),
        Candidate("JM109", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 66, "routine cloning and blue-white screening"),
        Candidate("XL1-Blue", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 64, "high-yield plasmid propagation"),
        Candidate("NEB 5-alpha", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 63, "general-purpose cloning"),
        Candidate("Rosetta(DE3)", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 62, "expression of codon-biased recombinant proteins"),
        Candidate("SCS110", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 58, "dam/dcm-deficient plasmid preparation"),
        Candidate("GM2163", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 56, "methylation-sensitive cloning"),
        Candidate("ER1821", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 54, "methylation-sensitive cloning and library work"),
        Candidate("JM110", "E. coli", "Escherichia coli", setOf("escherichia coli", "e. coli", "bacteria", "bacterial"), 52, "dam/dcm-deficient cloning"),
        Candidate("BY4741", "Yeast", "Saccharomyces cerevisiae", setOf("saccharomyces cerevisiae", "saccharomyces", "fungi", "fungal", "yeast"), 78, "standard haploid yeast genetics"),
        Candidate("W303", "Yeast", "Saccharomyces cerevisiae", setOf("saccharomyces cerevisiae", "saccharomyces", "fungi", "fungal", "yeast"), 72, "yeast genetics and phenotyping"),
        Candidate("INVSc1", "Yeast", "Saccharomyces cerevisiae", setOf("saccharomyces cerevisiae", "saccharomyces", "fungi", "fungal", "yeast"), 66, "routine yeast transformation"),
        Candidate("BY4742", "Yeast", "Saccharomyces cerevisiae", setOf("saccharomyces cerevisiae", "saccharomyces", "fungi", "fungal", "yeast"), 64, "standard haploid yeast genetics"),
        Candidate("CEN.PK2-1C", "Yeast", "Saccharomyces cerevisiae", setOf("saccharomyces cerevisiae", "saccharomyces", "fungi", "fungal", "yeast"), 60, "metabolic engineering and fermentation"),
        Candidate("HEK293T", "Mammalian", "Homo sapiens", setOf("homo sapiens", "mammalia", "mammalian"), 76, "transient mammalian expression"),
        Candidate("CHO", "Mammalian", "Cricetulus griseus", setOf("mammalia", "mammalian", "cricetulus griseus"), 70, "stable mammalian protein production"),
        Candidate("HeLa", "Mammalian", "Homo sapiens", setOf("homo sapiens", "mammalia", "mammalian"), 64, "human cell-line transfection studies"),
        Candidate("CHO-K1", "Mammalian", "Cricetulus griseus", setOf("cricetulus griseus", "mammalia", "mammalian"), 66, "mammalian expression and cell-line development"),
        Candidate("Vero", "Mammalian", "Chlorocebus sabaeus", setOf("chlorocebus sabaeus", "mammalia", "mammalian"), 60, "mammalian virology and propagation"),
        Candidate("Sf9", "Insect", "Spodoptera frugiperda", setOf("spodoptera frugiperda", "insecta", "insect"), 74, "baculovirus-based insect expression"),
        Candidate("Sf21", "Insect", "Spodoptera frugiperda", setOf("spodoptera frugiperda", "insecta", "insect"), 68, "baculovirus-based insect expression"),
        Candidate("High Five", "Insect", "Trichoplusia ni", setOf("trichoplusia ni", "insecta", "insect"), 66, "high-level insect protein expression"),
        Candidate("Nicotiana benthamiana", "Plant", "Nicotiana benthamiana", setOf("nicotiana benthamiana", "plantae", "plant"), 70, "transient agroinfiltration expression"),
        Candidate("Arabidopsis Col-0", "Plant", "Arabidopsis thaliana", setOf("arabidopsis thaliana", "plantae", "plant"), 62, "plant genetics and transformation"),
    )

    fun suggest(input: HostStrainSuggestionInput, limit: Int = 5): List<HostStrainSuggestion> {
        require(limit in 1..candidates.size) { "limit must be between 1 and ${candidates.size}" }
        val evidence = buildList {
            input.hostType?.let(::add)
            input.hostStrain?.let(::add)
            input.organism?.let(::add)
            addAll(input.taxonomy)
            add(input.description)
        }.joinToString(" ").lowercase()
        val explicitHost = input.hostStrain?.trim()?.lowercase().orEmpty()
        return candidates.map { candidate ->
            val aliasMatch = candidate.aliases.count { evidence.contains(it) }
            val typeMatch = input.hostType?.trim()?.equals(candidate.hostType, ignoreCase = true) == true
            val currentMatch = explicitHost == candidate.strain.lowercase()
            val kindAdjustment = if (input.sequenceKind == SeqKind.PROTEIN && candidate.hostType in setOf("E. coli", "Mammalian", "Insect")) 3 else 0
            val score = candidate.baseScore + aliasMatch * 18 + if (typeMatch) 12 else 0 + if (currentMatch) 8 else 0 + kindAdjustment
            val rationale = buildList {
                add(candidate.rationale)
                if (aliasMatch > 0) add("matches the supplied organism/taxonomy")
                if (typeMatch) add("matches the selected host type")
            }.joinToString("; ")
            HostStrainSuggestion(candidate.strain, candidate.hostType, candidate.organism, score, rationale)
        }.sortedWith(compareByDescending<HostStrainSuggestion> { it.score }.thenBy { it.strain }).take(limit)
    }
}
