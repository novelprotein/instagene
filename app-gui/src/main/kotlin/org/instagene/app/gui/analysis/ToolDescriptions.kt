package org.instagene.app.gui.analysis

internal object ToolDescriptions {
    private val descriptions = mapOf(
        "Search" to "Pattern search with mismatches and degenerate bases",
        "Alignment" to "Multiple sequence alignment with several algorithms",
        "Enzymes" to "Restriction enzyme analysis, methylation, and diagnostic sites",
        "CpG Methylation" to "CpG dinucleotide catalog, isoschizomer comparison, and island detection",
        "Assembly" to "Sequence-construction simulation: restriction, overlap assembly, cassette exchange, Golden Gate",
        "PCR / Mutagenesis" to "PCR, inverse PCR, overlap extension, and mutagenesis",
        "Translation / Structure" to "ORFs, translation, codon optimization, and secondary structure",
        "Virtual Gel" to "Simulate agarose gel electrophoresis with configurable parameters",
        "Calculators" to "Dilution, molecular weight, concentration, and master mix calculators",
        "NCBI / BLAST" to "Search NCBI nucleotide database and run BLASTN",
        "Chromatogram" to "Read ABI and SCF chromatogram files",
        "CRISPR / gRNA" to "Screen SpCas9 NGG sites on both strands; no activity or off-target prediction",
        "Sanger Alignment" to "Align Sanger sequencing reads to a reference",
        "Primer Thermo" to "Analyze primer Tm, \u0394G, hairpins, and self-dimers",
        "Plasmid DB" to "Browse the built-in plasmid database",
        "Site Domestication" to "Screen internal Golden Gate sites and preview coding-context mutations",
        "Statistics / Graphs" to "GC content, codon usage, ORF density, and more",
    )

    operator fun get(toolName: String): String = descriptions[toolName].orEmpty()
}
