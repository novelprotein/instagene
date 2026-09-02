package org.instagene.core

/** The authority for a three-letter sequence-class code. */
enum class SequenceClassCodeAuthority {
    NCBI,
    INSTAGENE,
}

/** A researcher-facing sequence classification and its three-letter code. */
data class SequenceClassOption(
    val label: String,
    val sequenceCode: String,
    val codeAuthority: SequenceClassCodeAuthority = SequenceClassCodeAuthority.INSTAGENE,
) {
    /** Text shown in the sequence-class selector. */
    val uiLabel: String
        get() = label

    /** Text shown for this option in the dropdown, including its code. */
    val displayLabel: String
        get() = "$uiLabel ($sequenceCode)"

    /** The code is exposed as an NCBI code only when NCBI documents it as such. */
    val ncbiCode: String?
        get() = sequenceCode.takeIf { codeAuthority == SequenceClassCodeAuthority.NCBI }
}

/** A visually grouped set of sequence-class options. */
data class SequenceClassGroup(
    val label: String,
    val options: List<SequenceClassOption>,
)

/**
 * The explicit sequence classes offered by the Info panel.
 *
 * Every option has a three-letter code. NCBI divisions and submission classes
 * use their documented codes; more granular application labels use an
 * InstaGene-defined code and are not presented as NCBI divisions.
 */
object SequenceClassCatalog {
    val groups: List<SequenceClassGroup> = listOf(
        SequenceClassGroup(
            label = "Biological source",
            options = listOf(
                SequenceClassOption("Primate", "PRI", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Rodent", "ROD", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Other mammalian", "MAM", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Other vertebrate", "VRT", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Invertebrate", "INV", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Plant", "PLT"),
                SequenceClassOption("Fungal", "FNG"),
                SequenceClassOption("Algal", "ALG"),
                SequenceClassOption("Bacterial", "BCT", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Archaeal", "ARC"),
                SequenceClassOption("Viral", "VRL", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Phage", "PHG", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Synthetic or chimeric", "SYN", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Unknown or unannotated", "UNA", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Other biological source", "OBS"),
            ),
        ),
        SequenceClassGroup(
            label = "Sequence content",
            options = listOf(
                SequenceClassOption("Genomic DNA", "GDN"),
                SequenceClassOption("Chromosomal sequence", "CHR"),
                SequenceClassOption("Organelle sequence", "ORG"),
                SequenceClassOption("mRNA or transcript", "MRT"),
                SequenceClassOption("cDNA", "CDN"),
                SequenceClassOption("Ribosomal RNA", "RNR"),
                SequenceClassOption("Transfer RNA", "TNR"),
                SequenceClassOption("Other non-coding RNA", "NCR"),
                SequenceClassOption("Structural RNA", "SRN"),
                SequenceClassOption("Plasmid or vector", "PLV"),
                SequenceClassOption("Amplicon or PCR product", "PCR"),
                SequenceClassOption("Protein sequence", "PRT"),
                SequenceClassOption("Other sequence content", "OSC"),
            ),
        ),
        SequenceClassGroup(
            label = "Archive or submission class",
            options = listOf(
                SequenceClassOption("Expressed sequence tag", "EST", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Patent sequence", "PAT", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Sequence-tagged site", "STS", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Genome survey sequence", "GSS", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("High-throughput genomic sequence", "HTG", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("High-throughput cDNA sequence", "HTC", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Environmental sampling sequence", "ENV", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Whole-genome shotgun sequence", "WGS", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Transcriptome shotgun assembly", "TSA", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Targeted locus study", "TLS", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Third-party annotation or assembly", "TPA", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Contig or assembly record", "CON", SequenceClassCodeAuthority.NCBI),
                SequenceClassOption("Other record class", "ORC"),
            ),
        ),
    )

    val options: List<SequenceClassOption> = groups.flatMap { it.options }
    val labels: List<String> = options.map { it.label }
    val groupLabels: Set<String> = groups.map { it.label }.toSet()
    val dropdownItems: List<String> = buildList {
        add("")
        groups.forEach { group ->
            add(group.label)
            addAll(group.options.map { it.displayLabel })
        }
    }

    fun option(label: String?): SequenceClassOption? = options.firstOrNull { it.label == label || it.displayLabel == label }

    fun isGroupLabel(value: String?): Boolean = value != null && value in groupLabels
}
