package org.instagene.core

import kotlinx.serialization.Serializable

@Serializable
data class FeatureDefinition(
    val name: String,
    val pattern: String,
    val type: String = "misc_feature",
    val strand: Strand = Strand.FORWARD,
    val color: String? = null,
    val uppercaseOnly: Boolean = false,
    /** When true, regions matching this pattern are excluded from annotation by other definitions. */
    val exclude: Boolean = false,
)

/** Information about a single match found by the library, used for preview. */
data class MatchInfo(
    val name: String,
    val start: Int,
    val end: Int,
    val strand: Strand,
    val matchedSequence: String,
    val definition: FeatureDefinition,
)

/** A bounded, definition-level progress update from a feature-library scan. */
data class FeatureScanProgress(
    val completedDefinitions: Int,
    val totalDefinitions: Int,
    /** Matches found so far, including exclusion rules so callers can explain their result. */
    val matches: Int,
)

/** Pattern-backed automatic annotation, including IUPAC, variable-length wildcards, exclusion, and bidirectional search. */
object FeatureLibrary {

    /** Built-in feature presets organized by category. */
    val BUILTIN_PRESETS: Map<String, List<FeatureDefinition>> = linkedMapOf(
        "Common Promoters" to listOf(
            FeatureDefinition("T7 promoter", "TATAGATATAACTAGATA", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("T3 promoter", "ATTAACCCTCACTAAAGGGA", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("SP6 promoter", "ATTTAGGTGACACTATAGAA", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("lac promoter", "TGTGTGGAATTGTGAGCGG", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("araBAD promoter", "CTGTCGACTGGTACCCGTAT", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("tac promoter", "GATAACAATTTCACACAAGC", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("trc promoter", "GTTGACAATTAATCATCGAA", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("CMV promoter", "CGTTACATAACTTACGGTAAATGCCC", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("EF1a promoter", "GACGTAAACGGCCACAAGTTCAGCG", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("adh1 promoter", "GGTACCTTTAAATGTCAGTATTAAATTTGATCGATAATCAAT", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("ompA promoter", "ATAATTTTTAATAAGAAGAAATATTTAATTTAAT", "promoter", Strand.FORWARD, "#1E88E5"),
            FeatureDefinition("bla promoter", "GCGCAATTATTTTGCCAAGCTG", "promoter", Strand.FORWARD, "#1E88E5"),
        ),
        "Operators / Repressors" to listOf(
            FeatureDefinition("lac operator", "TGTGTGGAATTGTGAGCGGATAACAATT", "regulatory", Strand.FORWARD, "#2E7D32"),
            FeatureDefinition("lac operator (tight)", "AATTGTGAGCGGATAACAATTT", "regulatory", Strand.FORWARD, "#2E7D32"),
            FeatureDefinition("tet operator", "TCCCTATCAGTGATAGAGA", "regulatory", Strand.FORWARD, "#2E7D32"),
            FeatureDefinition("araC operator (araO1)", "TGACACCTTGATTTGATCGATCTTTTCAG", "regulatory", Strand.FORWARD, "#2E7D32"),
            FeatureDefinition("lambda pL/pR operator (cI)", "TACTCCCAATCGATAGAATA", "regulatory", Strand.FORWARD, "#2E7D32"),
            FeatureDefinition("trp operator", "GTTAGCTTAACTTACTG", "regulatory", Strand.FORWARD, "#2E7D32"),
        ),
        "RBS / Ribosome Binding" to listOf(
            FeatureDefinition("Shine-Dalgarno (strong)", "AGGAGGT", "regulatory", Strand.FORWARD, "#43A047"),
            FeatureDefinition("Shine-Dalgarno (weak)", "GGAGG", "regulatory", Strand.FORWARD, "#43A047"),
            FeatureDefinition("Strong RBS (Elowitz)", "AAGAAG", "regulatory", Strand.FORWARD, "#43A047"),
            FeatureDefinition("T7 RBS", "GAAAGAAG", "regulatory", Strand.FORWARD, "#43A047"),
            FeatureDefinition("Consensus RBS", "AGGAGG", "regulatory", Strand.FORWARD, "#43A047"),
            FeatureDefinition("lac RBS", "AAGGAGATATCATATG", "regulatory", Strand.FORWARD, "#43A047"),
        ),
        "Common Tags" to listOf(
            FeatureDefinition("His6 tag", "CATCATCATCATCAT", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("His6 tag (codon opt)", "CACCACCACCACCACCAC", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("FLAG tag", "GACTACAAGGATGACGACGATAAG", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("HA tag", "TATCCTTATGATGTTCCTGATTATGCT", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("Strep-tag II", "TGGAGCCACCCGCAGTTCGAAAAA", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("V5 tag", "GGTAAGCCTATCCCTAACCCTCTCCTCGTCTCTCCT", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("Myc tag", "GAACAAAAACTTATTTCTGAAGAAGATCTG", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("GST tag", "ATGTCCCCTATACTAGGTTATTGGAAA", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("MBP tag", "ATGAAATCTAATTTAAAGATTGAAGAAG", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("SUMO tag", "ATGAGCGATAAAGGAGAAGAACTG", "CDS", Strand.FORWARD, "#F4511E"),
            FeatureDefinition("Fc tag", "GGAGTGCATCCGCAAACTG", "CDS", Strand.FORWARD, "#F4511E"),
        ),
        "Fluorescent Proteins" to listOf(
            FeatureDefinition("GFP (EGFP)", "ATGAGTAAAGGAGAAGAACTTTTCACTGGAGTTGTCCCAATTCTTGTTGAATTAGATGGTGATGTTAATGGGCACAAATTTTCTGTCAGTGGAGAGGGTGAAGGTGATGCAACATACGG", "CDS", Strand.FORWARD, "#00C853"),
            FeatureDefinition("mCherry", "ATGGTGAGCAAGGGCGAGGAGGAT", "CDS", Strand.FORWARD, "#D50000"),
            FeatureDefinition("mRuby", "ATGGTGCGCTCGAAAGACGACGG", "CDS", Strand.FORWARD, "#C51162"),
            FeatureDefinition("YFP (EYFP)", "ATGAGTAAAGGAGAAGAACTTTTCACTGGAGTTGTCCCA", "CDS", Strand.FORWARD, "#FFD600"),
            FeatureDefinition("CFP", "ATGAGTAAAGGAGAAGAACTTTTCACTGGAGTTGTCCCA", "CDS", Strand.FORWARD, "#00B0FF"),
            FeatureDefinition("DsRed", "ATGGCGAGTAGCGAAGACGTTATCAAAG", "CDS", Strand.FORWARD, "#B71C1C"),
            FeatureDefinition("RFP (mRFP1)", "ATGGCGAGTAGCGAAGACGTTATCAAAGAGTTCATGCG", "CDS", Strand.FORWARD, "#E91E63"),
            FeatureDefinition("TagBFP", "ATGAGTAAAGGAGAAGAACTTTTCACTGG", "CDS", Strand.FORWARD, "#2962FF"),
            FeatureDefinition("iRFP", "ATGGCGAGCAGCAGCGACAGC", "CDS", Strand.FORWARD, "#4A148C"),
            FeatureDefinition("GFP start", "ATG", "CDS", Strand.FORWARD, "#00C853"),
        ),
        "Terminators" to listOf(
            FeatureDefinition("rrnB T1 terminator", "CGCTTCAGTGGAAAAGAAAAACCACCAC", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("rrnB T1 terminator (short)", "CTGTCAGTGCAAT", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("T7 terminator", "GCAAAAAAACCCCTCAAGACCCGTTTAGAGGCCCCAAGGGGTTATGCTAG", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("rrnC terminator", "GCGCCAGCCTTGCGACGG", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("rrnA terminator", "CGGGCCTCTTCGCTATTACGCCAG", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("lacIq terminator", "CGGATCCCCCTGCAGCCCAAGCT", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("t0 terminator", "TTTTTTTTCATACCTAGTT", "terminator", Strand.FORWARD, "#8E24AA"),
            FeatureDefinition("lpp terminator", "CCGATCGTTATCACGGTTAAG", "terminator", Strand.FORWARD, "#8E24AA"),
        ),
        "Antibiotic Resistance" to listOf(
            FeatureDefinition("AmpR (bla) start", "ATGAGTATTCAACATTTTCGTGTCGCC", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("KanR (neo) start", "ATGATTGAACAAGATGATTATCGAAAGG", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("CamR (cat) start", "ATGGAGAAAAAAATCACTGGATATACC", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("SpecR (aadA) start", "ATGGCGAGCGGCGCGACCGAAGGGG", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("HygR (hph) start", "ATGAAAAAGCCTGAACTCACCGCGAC", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("BleoR (zeo) start", "ATGACCGAGTACAAGCCCACGGTG", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("TetR start", "ATGCTAGATTATTCCCCAAC", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("Chloramphenicol CAT core", "GATATACCACCGTTGATATATCCCAATGGCAT", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("Beta-lactamase core", "AGTATTCAACATTTTCGTGTCGCCCTTATTCC", "CDS", Strand.FORWARD, "#FFB300"),
            FeatureDefinition("Kanamycin phosphotransferase core", "GAACAAGATGATTATCGAAAGGATGGCG", "CDS", Strand.FORWARD, "#FFB300"),
        ),
        "Origins of Replication" to listOf(
            FeatureDefinition("ColE1 origin", "AATGAAACAGCTCAAAACCCAGCTATTGACCCTAGTGATTTTCTTTGGGTATGCGAAACGCCCCGCGGATCATTTAAATCCCTGACCTTTTGATTTTG", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("pUC origin", "CGCCCGCCCTGACTAGCGGCTCCTTCGCTTTCTTCCCTTCCTTTCTCGCCACGTTCGCCGGCTTTCCCCGTCAAGCTCTAAATCGGGGGCTCCCTTTAGGGTTCCGATTTAGTGCTTTACGGCACCTCGACCCCAAAAAACTTGATTTTTTTATGGTGGGTCGGAAGCGGTCCGATTGACCGGCATG", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("f1 origin", "GTTTTTGTCATTGTTTATCACC", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("p15A origin", "CCCTCATGATGTTAACTTTGTTCAAGCATTTAATGTTCAATAATGCTTATCAATGATACCGCGGAAAAACGCGTTAAGATCGATTTTATCAAACTTATCATGACGTCAAAAGATAA", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("SC101 origin", "CAGCCTGCGTCGCCTTGTCGATTCAATCGAGGATTTCAGCGCATCGCCCAGTGCGCCATCAGCGGCTCAGTTCCGGCAGCCGCGCGTCATCCGGATCAGCAGCAG", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("2u origin (yeast)", "TTAATCATCGATTTTTTCTATTCACTATCGGTAATTATACCCATACTG", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("ARS (yeast)", "TTTTTGTTTAAACGATCTTTTAATTTTTTAGATTTTT", "rep_origin", Strand.FORWARD, "#5C6BC0"),
            FeatureDefinition("ColE1 origin (core)", "AATGAAACAGCTCAAAACCCAGCTATTG", "rep_origin", Strand.FORWARD, "#5C6BC0"),
        ),
        "Recombination / Integration Sites" to listOf(
            FeatureDefinition("loxP site", "ATAACTTCGTATAATGTATGCTATACGAAGTTAT", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("loxP site (reverse)", "ATAACTTCGTATAATGTATGCTATACGAAGTTAT", "misc_feature", Strand.REVERSE, "#FF6D00"),
            FeatureDefinition("FRT site", "GAAGTTCCTATTCCTAGGCCGACCCGGACAGGAT", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("attB1", "AAAACTAGTGGATCAAACTAGTGGCCCA", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("attB2", "AACTAGTGGATCAAACTAGTGGCCCA", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("attP1", "AAACCACTAGTGATCAAACTAGTGGCCCA", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("attP2", "AACTAGTGATCAAACTAGTGGCCCA", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("attL1", "AAACCACTAGTGGATCAAACTAGTGGCCCA", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("attR1", "AACTAGTGGATCAAACTAGTGGCCCA", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("Tn5 ME (left)", "GCTTGCGCGAGATCGCGAAACTCG", "misc_feature", Strand.FORWARD, "#FF6D00"),
            FeatureDefinition("Tn5 ME (right)", "GCTTGCGCGAGATCGCGAAACTCG", "misc_feature", Strand.REVERSE, "#FF6D00"),
        ),
        "MCS / Polylinker Sites" to listOf(
            FeatureDefinition("MCS (pUC/EcoRI-BamHI)", "GAATTCGGATCC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("MCS (pET/EcoRI-HindIII)", "GAATTCGGATCCCATGGCATATGAAGCTT", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("MCS (pGEX/EcoRI-SalI)", "GAATTCGGATCCCGGGTCGAC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("EcoRI site", "GAATTC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("BamHI site", "GGATCC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("HindIII site", "AAGCTT", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("XhoI site", "CTCGAG", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("NotI site", "GCGGCCGC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("XbaI site", "TCTAGA", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("SacI site", "GAGCTC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("KpnI site", "GGTACC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("SpeI site", "ACTAGT", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("NdeI site", "CATATG", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("SmaI site", "CCCGGG", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("SacII site", "CCGCGG", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("PstI site", "CTGCAG", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("NcoI site", "CCATGG", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("NheI site", "GCTAGC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("SalI site", "GTCGAC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("SphI site", "GCATGC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("ApaI site", "GGGCCC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("BglII site", "AGATCT", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("ClaI site", "ATCGAT", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("EcoRV site", "GATATC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("HpaI site", "GTTAAC", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("MluI site", "ACGCGT", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("BstBI site", "TTCGAA", "misc_feature", Strand.FORWARD, "#78909C"),
            FeatureDefinition("FspI site", "TGCGCA", "misc_feature", Strand.FORWARD, "#78909C"),
        ),
        "Sequencing / Expression Signals" to listOf(
            FeatureDefinition("T7 gene 10 tag", "ATGAGCTATCAAAGAAGAAAGG", "CDS", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("Kozak sequence (mammalian)", "GCCGCCACC", "regulatory", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("Kozak sequence (consensus)", "GCC(A/G)CCATGG", "regulatory", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("Poly(A) signal (SV40)", "AATAAA", "regulatory", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("Poly(A) signal (bovine)", "AATAAA", "regulatory", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("IRES (EMCV)", "GCCGCCAGTCATTAGAACATCG", "regulatory", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("2A self-cleaving (P2A)", "GGAAGCGGAGCTACTAACTTCAGCCTGCTGAAGCAGGCTGGAGACGTGGAGGAGAACCCT", "misc_feature", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("T2A self-cleaving", "GAACTTTCTCCTTGACGTTAGAATCAAGTTTTGTGGCGGTGACACTGAGTTTCCTGTCC", "misc_feature", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("T7 promoter + RBS", "TATAGATACTAACTAGATAAGGAGGT", "promoter", Strand.FORWARD, "#00ACC1"),
            FeatureDefinition("T7 terminator (short)", "GCAAAAAAACCCCTCAAGACCCGTTTAGAGGCCCC", "terminator", Strand.FORWARD, "#00ACC1"),
        ),
    )

    /**
     * Find all matches for a definition, optionally searching both strands.
     * When [searchBothStrands] is true, the definition's strand field is ignored and
     * matches on both forward and reverse-complement are returned with the correct strand.
     */
    fun annotate(
        seq: Seq,
        definitions: Collection<FeatureDefinition>,
        includeExisting: Boolean = true,
        searchBothStrands: Boolean = false,
    ): Seq {
        val matchInfo = previewMatches(seq, definitions, searchBothStrands)
        return annotateMatches(seq, matchInfo, includeExisting)
    }

    /**
     * Applies annotations while reporting progress and checking cancellation
     * between definitions. This deliberately keeps the normal [annotate]
     * fast/parallel for small desktop inputs while giving front ends a safe
     * path for crowded genomes and large lab libraries.
     */
    fun annotateCancellable(
        seq: Seq,
        definitions: Collection<FeatureDefinition>,
        includeExisting: Boolean = true,
        searchBothStrands: Boolean = false,
        cancellationRequested: () -> Boolean = { false },
        progress: (FeatureScanProgress) -> Unit = {},
    ): Seq = annotateMatches(
        seq,
        previewMatchesCancellable(seq, definitions, searchBothStrands, cancellationRequested, progress),
        includeExisting,
    )

    private fun annotateMatches(seq: Seq, matchInfo: List<MatchInfo>, includeExisting: Boolean): Seq {
        val exclusions = matchInfo.filter { it.definition.exclude }
        val additions = matchInfo.filter { !it.definition.exclude }.filter { m ->
            exclusions.none { e -> rangesOverlap(m.start, m.end, e.start, e.end) }
        }.map { m ->
            Feature(m.name, m.definition.type, m.start, m.end, m.strand, color = m.definition.color)
        }
        val features = if (includeExisting) seq.features + additions else additions
        return seq.copy(features = features.distinctBy { listOf(it.name, it.type, it.start, it.end, it.strand) }.sortedBy { it.start })
    }

    /** Preview matches without mutating the sequence. Used by the GUI dialog. */
    fun previewMatches(
        seq: Seq,
        definitions: Collection<FeatureDefinition>,
        searchBothStrands: Boolean = false,
    ): List<MatchInfo> {
        val defs = definitions.toList()
        if (defs.size <= 8) {
            return defs.flatMap { definition ->
                if (searchBothStrands) {
                    findOnStrand(seq, definition, Strand.FORWARD) + findOnStrand(seq, definition, Strand.REVERSE)
                } else {
                    findOnStrand(seq, definition, definition.strand)
                }
            }
        }
        return Parallel.flatMap(defs) { definition ->
            matchesForDefinition(seq, definition, searchBothStrands)
        }
    }

    /**
     * Sequential, cancellable counterpart to [previewMatches]. Progress is
     * deliberately reported once per definition, rather than for every base or
     * match, so the hot regex path stays allocation-light and the UI has a
     * useful, stable unit of work to display.
     */
    fun previewMatchesCancellable(
        seq: Seq,
        definitions: Collection<FeatureDefinition>,
        searchBothStrands: Boolean = false,
        cancellationRequested: () -> Boolean = { false },
        progress: (FeatureScanProgress) -> Unit = {},
    ): List<MatchInfo> {
        val defs = definitions.toList()
        val out = ArrayList<MatchInfo>()
        progress(FeatureScanProgress(0, defs.size, 0))
        for ((index, definition) in defs.withIndex()) {
            checkCancelled(cancellationRequested)
            out += matchesForDefinition(seq, definition, searchBothStrands, cancellationRequested)
            checkCancelled(cancellationRequested)
            progress(FeatureScanProgress(index + 1, defs.size, out.size))
        }
        return out
    }

    private fun matchesForDefinition(
        seq: Seq,
        definition: FeatureDefinition,
        searchBothStrands: Boolean,
        cancellationRequested: () -> Boolean = { false },
    ): List<MatchInfo> = if (searchBothStrands) {
        findOnStrand(seq, definition, Strand.FORWARD, cancellationRequested) +
                findOnStrand(seq, definition, Strand.REVERSE, cancellationRequested)
    } else {
        findOnStrand(seq, definition, definition.strand, cancellationRequested)
    }

    fun find(seq: Seq, definition: FeatureDefinition): List<Pair<Int, Int>> =
        findOnStrand(seq, definition, definition.strand).map { it.start to it.end }

    private fun findOnStrand(
        seq: Seq,
        definition: FeatureDefinition,
        strand: Strand,
        cancellationRequested: () -> Boolean = { false },
    ): List<MatchInfo> {
        checkCancelled(cancellationRequested)
        if (definition.pattern.isBlank()) return emptyList()
        val regex = Regex(patternRegex(definition.pattern), setOf(RegexOption.IGNORE_CASE))
        val out = ArrayList<MatchInfo>()
        val source = if (strand == Strand.FORWARD) seq.bases else seq.reverseComplement().bases
        regex.findAll(source).forEach { match ->
            checkCancelled(cancellationRequested)
            val start = if (strand == Strand.FORWARD) match.range.first else seq.length - match.range.last - 1
            val end = if (strand == Strand.FORWARD) match.range.last + 1 else seq.length - match.range.first
            val matchedSeq = source.substring(match.range)
            if (!definition.uppercaseOnly || matchedSeq.all { it.isUpperCase() }) {
                out += MatchInfo(definition.name, start, end, strand, matchedSeq, definition)
            }
        }
        return out
    }

    private fun checkCancelled(cancellationRequested: () -> Boolean) {
        if (cancellationRequested()) {
            throw java.util.concurrent.CancellationException("Feature-library scan cancelled")
        }
    }

    fun patternRegex(pattern: String): String {
        val clean = if (pattern.startsWith("!")) pattern.substring(1) else pattern
        return buildString {
            var i = 0
            while (i < clean.length) {
                when (val c = clean[i]) {
                    '#', '+' -> append("[ACGT]*")
                    '{' -> {
                        val close = clean.indexOf('}', i)
                        if (close > i) {
                            val spec = clean.substring(i + 1, close)
                            val parts = spec.split(',')
                            val (min, max) = when (parts.size) {
                                1 -> parts[0].trim().toIntOrNull()?.let { it to it } ?: (0 to Int.MAX_VALUE)
                                2 -> {
                                    val a = parts[0].trim().toIntOrNull() ?: 0
                                    val b = parts[1].trim().toIntOrNull() ?: Int.MAX_VALUE
                                    a to b
                                }
                                else -> 0 to Int.MAX_VALUE
                            }
                            append("[ACGT]{")
                            append(min)
                            if (min != max) {
                                append(",")
                                if (max < Int.MAX_VALUE) append(max)
                            }
                            append("}")
                            i = close
                        } else {
                            append(Regex.escape(c.toString()))
                        }
                    }
                    else -> {
                        val bases = Alphabet.expansion(c)
                        if (bases == null) append(Regex.escape(c.toString()))
                        else append('[').append(bases).append(']')
                    }
                }
                i++
            }
        }
    }

    private fun rangesOverlap(s1: Int, e1: Int, s2: Int, e2: Int): Boolean = s1 < e2 && s2 < e1
}
