package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** Shared report formatting used by both CLI and web front-ends. */
object Reports {

    /** A stable, machine-readable summary of a completed researcher workflow. */
    @Serializable
    data class WorkflowReport(
        val operation: String,
        val productName: String,
        val productLength: Int,
        val productTopology: String,
        val productIdentity: String,
        val inputs: List<String> = emptyList(),
        val parameters: Map<String, String> = emptyMap(),
        val steps: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    /** A stable verification summary suitable for a lab notebook or CI output. */
    @Serializable
    data class VerificationReport(
        val referenceName: String,
        val referenceLength: Int,
        val referenceIdentity: String,
        val totalReads: Int,
        val averageIdentity: Double,
        val reads: List<ReadVerification> = emptyList(),
        val uncoveredPositions: List<Int> = emptyList(),
    )

    @Serializable
    data class ReadVerification(
        val name: String,
        val identity: Double,
        val alignedLength: Int,
        val mismatchCount: Int,
        val confidence: String,
        val mismatches: List<String> = emptyList(),
    )

    /** Stable machine-readable equivalent of [seqSummary]. */
    @Serializable
    data class SequenceSummary(
        val name: String,
        val length: Int,
        val kind: String,
        val topology: String,
        val identity: String,
        val sourceSha256: String? = null,
        val gcPercent: Double,
        val meltingTemperatureC: Double,
        val molecularWeightKda: Double,
        val baseCounts: Map<String, Int>,
        val featureCount: Int,
        val enzymesCuttingOnce: List<String>,
    )

    private val reportJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

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

    fun sequenceSummary(seq: Seq): SequenceSummary {
        val counts = SeqOps.baseCounts(seq.bases)
        return SequenceSummary(
            name = seq.name,
            length = seq.length,
            kind = seq.kind.name,
            topology = seq.topology.name,
            identity = SequenceIdentity.cdseguid(seq),
            sourceSha256 = seq.metadata["SOURCE_SHA256"],
            gcPercent = round1(SeqOps.gcContent(seq)),
            meltingTemperatureC = round1(SeqOps.meltingTemp(seq.bases)),
            molecularWeightKda = round1(SeqOps.molecularWeightDaltons(seq) / 1000.0),
            baseCounts = counts.mapKeys { it.key.toString() }.toSortedMap(),
            featureCount = seq.features.size,
            enzymesCuttingOnce = Digest.enzymesCutting(seq).map { it.name },
        )
    }

    fun sequenceSummaryJson(seq: Seq): String = reportJson.encodeToString(sequenceSummary(seq))

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

    /** Builds a workflow report from the product and its explicit operation details. */
    fun workflowReport(
        operation: String,
        product: Seq,
        inputs: List<Seq> = emptyList(),
        parameters: Map<String, String> = emptyMap(),
        steps: List<String> = product.provenance.map { it.summary },
        warnings: List<String> = product.provenance.flatMap { it.warnings },
    ): WorkflowReport = WorkflowReport(
        operation = operation,
        productName = product.name,
        productLength = product.length,
        productTopology = product.topology.name,
        productIdentity = SequenceIdentity.cdseguid(product),
        inputs = inputs.map { "${it.name} (${SequenceIdentity.cdseguid(it)})" },
        parameters = parameters.toSortedMap(),
        steps = steps,
        warnings = warnings,
    )

    /** Converts the cloning workflow's review data into the shared report format. */
    fun workflowReport(
        result: MolecularWorkflowResult,
        inputs: List<Seq> = emptyList(),
        parameters: Map<String, String> = emptyMap(),
    ): WorkflowReport = workflowReport(
        operation = result.method.name,
        product = result.product,
        inputs = inputs,
        parameters = parameters,
        steps = result.steps.map { "${it.title}: ${it.detail}" },
        warnings = result.diagnostics
            .filter { it.severity != DiagnosticSeverity.INFO }
            .map { it.message },
    )

    /** Builds a verification report from the existing alignment result. */
    fun verificationReport(reference: Seq, result: SangerAlignmentResult): VerificationReport {
        val covered = result.reads.flatMap { read ->
            read.referenceStart until (read.referenceStart + read.alignedLength)
        }.toSet()
        val reads = result.reads.map { read ->
            ReadVerification(
                name = read.readName,
                identity = round1(read.identity),
                alignedLength = read.alignedLength,
                mismatchCount = read.mismatches.size,
                confidence = read.confidence().name,
                mismatches = read.mismatches.map { "${it.refPos + 1}: ${it.refBase} -> ${it.readBase}" },
            )
        }
        return VerificationReport(
            referenceName = reference.name,
            referenceLength = reference.length,
            referenceIdentity = SequenceIdentity.cdseguid(reference),
            totalReads = result.summary.totalReads,
            averageIdentity = round1(result.summary.averageIdentity),
            reads = reads,
            uncoveredPositions = (0 until reference.length).filterNot { it in covered }.map { it + 1 },
        )
    }

    fun workflowJson(report: WorkflowReport): String = reportJson.encodeToString(report)

    fun verificationJson(report: VerificationReport): String = reportJson.encodeToString(report)

    fun workflowMarkdown(report: WorkflowReport): String = buildString {
        appendLine("# ${report.operation}")
        appendLine()
        appendLine("- Product: **${report.productName}**")
        appendLine("- Length: **${report.productLength} bp**")
        appendLine("- Topology: **${report.productTopology}**")
        appendLine("- Sequence identity: `${report.productIdentity}`")
        if (report.inputs.isNotEmpty()) {
            appendLine()
            appendLine("## Inputs")
            report.inputs.forEach { appendLine("- $it") }
        }
        if (report.parameters.isNotEmpty()) {
            appendLine()
            appendLine("## Parameters")
            report.parameters.forEach { (key, value) -> appendLine("- $key: $value") }
        }
        if (report.steps.isNotEmpty()) {
            appendLine()
            appendLine("## Steps")
            report.steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        }
        if (report.warnings.isNotEmpty()) {
            appendLine()
            appendLine("## Warnings")
            report.warnings.forEach { appendLine("- ⚠ $it") }
        }
    }

    fun verificationMarkdown(report: VerificationReport): String = buildString {
        appendLine("# Sanger verification: ${report.referenceName}")
        appendLine()
        appendLine("- Reference length: **${report.referenceLength} bp**")
        appendLine("- Reference identity: `${report.referenceIdentity}`")
        appendLine("- Reads: **${report.totalReads}**")
        appendLine("- Average identity: **${round1(report.averageIdentity * 100)}%**")
        appendLine()
        appendLine("## Reads")
        appendLine("| Read | Identity | Aligned bases | Mismatches | Confidence |")
        appendLine("|---|---:|---:|---:|---|")
        report.reads.forEach { read ->
            appendLine("| ${read.name} | ${round1(read.identity * 100)}% | ${read.alignedLength} | ${read.mismatchCount} | ${read.confidence} |")
        }
        if (report.uncoveredPositions.isNotEmpty()) {
            appendLine()
            appendLine("## Uncovered positions")
            appendLine(report.uncoveredPositions.joinToString(", "))
        }
    }
}
