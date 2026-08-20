package org.instagene.core

import kotlin.math.roundToInt

/** Shared report formatting used by both CLI and web front-ends. */
object Reports {

    /** Round to one decimal place. */
    fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    /** Sequence summary: name, length, GC%, composition, enzymes. */
    fun seqSummary(seq: Seq): String = buildString {
        appendLine("Name: ${seq.name}")
        appendLine("Length: ${seq.length} bp")
        appendLine("Type: ${seq.kind}")
        appendLine("Topology: ${seq.topology}")
        appendLine("GC content: ${round1(SeqOps.gcContent(seq))}%")
        appendLine("Melting temp: ${round1(SeqOps.meltingTemp(seq.bases))} C")
        appendLine("Molecular weight: ${round1(SeqOps.molecularWeightDaltons(seq) / 1000.0)} kDa")
        val counts = SeqOps.baseCounts(seq.bases)
        appendLine("Base counts: A=${counts.getOrDefault('A', 0)} T=${counts.getOrDefault('T', 0)} G=${counts.getOrDefault('G', 0)} C=${counts.getOrDefault('C', 0)}")
        if (seq.features.isNotEmpty()) {
            appendLine("Features (${seq.features.size}):")
            for (f in seq.features) appendLine("  ${f.type}: ${f.name} (${f.start + 1}..${f.end}, ${f.strand})")
        }
        val cutting = Digest.enzymesCutting(seq)
        if (cutting.isNotEmpty()) {
            appendLine("Enzymes cutting once: ${cutting.joinToString { it.name }}")
        }
    }

    /** Restriction digest report: cut-site table and fragment table. */
    fun digestReport(seq: Seq, enzymes: List<Enzyme>): String = buildString {
        val allSites = Digest.cutSites(seq, enzymes)
        val fragments = Digest.digestSites(seq, allSites)
        appendLine("== Cut Sites ==")
        appendLine(String.format("%-12s %-8s %-10s %-10s", "Enzyme", "Pos", "Top", "Bottom"))
        for (site in allSites) {
            appendLine(String.format("%-12s %-8d %-10s %-10s", site.enzyme.name, site.topCut + 1, site.topCut + 1, site.bottomCut + 1))
        }
        appendLine()
        appendLine("== Fragments ==")
        appendLine(String.format("%-4s %-8s %-8s %-12s %-12s", "#", "Len", "Start", "Left End", "Right End"))
        fragments.forEachIndexed { i, f ->
            appendLine(String.format("%-4d %-8d %-8d %-12s %-12s", i + 1, f.length, f.start + 1, f.leftEnd, f.rightEnd))
        }
    }

    /** ORF report. */
    fun orfReport(seq: Seq, table: CodonTable = CodonTable.STANDARD, minAa: Int = 30, bothStrands: Boolean = true): String = buildString {
        val orfs = SeqOps.findOrfs(seq, minAa, table, bothStrands)
        appendLine(String.format("%-10s %-10s %-7s %-6s %s", "Start", "End", "Strand", "AA", "Protein"))
        for (orf in orfs) {
            appendLine(String.format("%-10d %-10d %-7s %-6d %s", orf.start + 1, orf.end + 1, orf.strand, orf.lengthAa, orf.protein.take(40)))
        }
        appendLine("Found ${orfs.size} ORFs")
    }

    /** Search report. */
    fun searchReport(
        seq: Seq,
        pattern: String,
        mode: SearchMode = SearchMode.DNA_DEGENERATE,
        bothStrands: Boolean = true,
        maxMismatches: Int = 0,
    ): String = buildString {
        val request = SearchRequest(pattern, mode, bothStrands, maxMismatches = maxMismatches)
        val hits = AdvancedSearch.find(seq, request)
        for (hit in hits) {
            appendLine("${hit.start + 1}\t${hit.end}\t${hit.strand}\t${hit.mismatches}\t${hit.matched}")
        }
        appendLine("Found ${hits.size} matches")
    }
}
