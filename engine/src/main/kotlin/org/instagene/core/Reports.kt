package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
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
        val lowQualityBases: Int = 0,
        val trimmedBases: Int = 0,
        val insertions: Int = 0,
        val deletions: Int = 0,
        val mismatches: List<String> = emptyList(),
    )

    /** One one-based inclusive coordinate range in a portable report. */
    @Serializable
    data class CoordinateRange(
        val start: Int,
        val end: Int,
    )

    @Serializable
    data class PrimerQualitySource(
        val kind: String,
        val label: String,
        val sourceId: String,
    )

    @Serializable
    data class PrimerQualityReport(
        val minimumPhred: Int,
        val observedPositionCount: Int,
        val lowQualityRegions: List<CoordinateRange>,
        val uncoveredRegions: List<CoordinateRange>,
        val manualExcludedRegions: List<CoordinateRange>,
        val excludeUncoveredPositions: Boolean,
        val sources: List<PrimerQualitySource>,
    )

    @Serializable
    data class PrimerCandidateReport(
        val name: String,
        val sequence: String,
        val start: Int,
        val end: Int,
        val tm: Double,
        val gcPercent: Double,
        val score: Double,
        val selfComplementarity: Int,
    )

    /** Reproducible report for either PCR-pair or sequencing-primer selection. */
    @Serializable
    data class PrimerDesignReport(
        val templateName: String,
        val templateLength: Int,
        val templateIdentity: String,
        val targetStart: Int,
        val targetEnd: Int,
        val mode: String,
        val backend: String,
        val parameters: Map<String, String>,
        val effectiveExcludedRegions: List<CoordinateRange>,
        val quality: PrimerQualityReport? = null,
        val candidates: List<PrimerCandidateReport>,
        val warnings: List<String> = emptyList(),
        val primer3Command: String? = null,
        val primer3Input: String? = null,
    )

    /** One full PCR primer retained in a construct report, including its 5' cloning tail. */
    @Serializable
    data class PcrCloningPrimerReport(
        val name: String,
        val hybridizingSequence: String,
        val extension: String,
        val fullSequence: String,
    )

    /** Portable validation evidence for a PCR-amplified restriction clone. */
    @Serializable
    data class PcrCloningValidationReport(
        val passed: Boolean,
        val targetMatchesAmplicon: Boolean,
        val restrictionSitesAreUniqueInAmplicon: Boolean,
        val productContainsTarget: Boolean,
        val insertWasFlipped: Boolean,
        val templateTarget: CoordinateRange,
        val pcrTarget: CoordinateRange,
        val productInsert: CoordinateRange,
        val diagnostics: List<String> = emptyList(),
    )

    /** Machine-readable outcome of identity-checked recipe replay. */
    @Serializable
    data class WorkflowReplayReport(
        val status: String,
        val succeeded: Boolean,
        val operationType: String,
        val expectedOutputIdentity: String,
        val productName: String? = null,
        val productIdentity: String? = null,
        val messages: List<String> = emptyList(),
    )

    /** Complete, JSON-safe construct record for the PCR-cloning wizard and headless callers. */
    @Serializable
    data class PcrCloningReport(
        val workflow: WorkflowReport,
        val insertTemplateName: String,
        val insertTemplateIdentity: String,
        val enzymes: List<String>,
        val forwardPrimer: PcrCloningPrimerReport,
        val reversePrimer: PcrCloningPrimerReport,
        val ampliconName: String,
        val ampliconLength: Int,
        val ampliconIdentity: String,
        val validation: PcrCloningValidationReport,
        val recipe: WorkflowRecipe,
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
        parameters = result.parameters + parameters,
        steps = result.steps.map { "${it.title}: ${it.detail}" },
        warnings = result.diagnostics
            .filter { it.severity != DiagnosticSeverity.INFO }
            .map { it.message },
    )

    /** Builds a verification report from the existing alignment result. */
    fun verificationReport(reference: Seq, result: SangerAlignmentResult): VerificationReport {
        val covered = result.reads.flatMap { read ->
            read.referenceStart until (read.referenceStart + read.referenceLength)
        }.toSet()
        val reads = result.reads.map { read ->
            ReadVerification(
                name = read.readName,
                identity = round1(read.identity),
                alignedLength = read.alignedLength,
                mismatchCount = read.mismatches.size,
                confidence = read.confidence().name,
                lowQualityBases = read.lowQualityBases,
                trimmedBases = read.trimmedBases,
                insertions = read.insertionCount,
                deletions = read.deletionCount,
                mismatches = read.mismatches.map { "${it.refPos + 1}: ${it.refBase} -> ${it.readBase} (${it.kind})" },
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

    /** Captures primer-selection evidence, effective exclusions, and Primer3 input for an ELN-ready record. */
    fun primerDesignReport(
        template: Seq,
        start: Int,
        end: Int,
        parameters: PrimerDesignParameters,
        result: PrimerDesignResult,
    ): PrimerDesignReport {
        require(start in 0..template.length && end in start..template.length) { "Invalid primer-design report target" }
        fun range(region: IntRange): CoordinateRange = CoordinateRange(region.first + 1, region.last + 1)
        val quality = result.qualitySummary?.let { summary ->
            PrimerQualityReport(
                minimumPhred = summary.minimumPhred,
                observedPositionCount = summary.observedPositions.size,
                lowQualityRegions = summary.lowQualityRegions.map(::range),
                uncoveredRegions = summary.uncoveredRegions.map(::range),
                manualExcludedRegions = summary.manualExcludedRegions.map { range(it.range) },
                excludeUncoveredPositions = summary.excludeUncoveredPositions,
                sources = summary.sources.map { source ->
                    PrimerQualitySource(source.kind.name, source.label, source.sourceId)
                },
            )
        }
        return PrimerDesignReport(
            templateName = template.name,
            templateLength = template.length,
            templateIdentity = SequenceIdentity.cdseguid(template),
            targetStart = start + 1,
            targetEnd = end,
            mode = parameters.mode.name,
            backend = result.backend.name,
            parameters = mapOf(
                "minLength" to parameters.minLength.toString(),
                "maxLength" to parameters.maxLength.toString(),
                "targetTm" to parameters.targetTm.toString(),
                "minTm" to parameters.minTm.toString(),
                "maxTm" to parameters.maxTm.toString(),
                "minGc" to parameters.minGc.toString(),
                "maxGc" to parameters.maxGc.toString(),
                "maxSelfComplementarity" to parameters.maxSelfComplementarity.toString(),
                "sequencingDirection" to parameters.sequencingDirection.name,
            ).toSortedMap(),
            effectiveExcludedRegions = result.effectiveExcludedRegions.map(::range),
            quality = quality,
            candidates = result.candidates.map { candidate ->
                PrimerCandidateReport(
                    name = candidate.primer.name,
                    sequence = candidate.primer.bases,
                    start = candidate.start + 1,
                    end = candidate.end,
                    tm = round1(candidate.primer.tm),
                    gcPercent = round1(candidate.primer.gc),
                    score = round1(candidate.score),
                    selfComplementarity = candidate.selfComplementarity,
                )
            },
            warnings = result.warnings,
            primer3Command = result.command,
            primer3Input = result.primer3Input,
        )
    }

    /** Turns [PcrCloningResult] into one normalized, portable construct report. */
    fun pcrCloningReport(result: PcrCloningResult): PcrCloningReport {
        fun primer(primer: PcrPrimer) = PcrCloningPrimerReport(
            name = primer.name,
            hybridizingSequence = primer.hybridizingSequence,
            extension = primer.extension,
            fullSequence = primer.extension + primer.hybridizingSequence,
        )
        fun range(coordinates: WorkflowCoordinates) = CoordinateRange(coordinates.start + 1, coordinates.end)
        val parameters = PcrCloningWorkflows.recipeParameters(
            result.request,
            enzymes = result.request.enzymes,
            forward = result.forwardPrimer,
            reverse = result.reversePrimer,
            coordinates = result.validation.coordinates,
            design = result.primerDesign,
        )
        val validation = result.validation
        return PcrCloningReport(
            workflow = workflowReport(
                result.cloning,
                inputs = listOf(result.request.backbone, result.request.insertTemplate),
                parameters = parameters,
            ),
            insertTemplateName = result.request.insertTemplate.name,
            insertTemplateIdentity = SequenceIdentity.cdseguid(result.request.insertTemplate),
            enzymes = result.request.enzymes.map { it.name },
            forwardPrimer = primer(result.forwardPrimer),
            reversePrimer = primer(result.reversePrimer),
            ampliconName = result.amplification.product.name,
            ampliconLength = result.amplification.product.length,
            ampliconIdentity = SequenceIdentity.cdseguid(result.amplification.product),
            validation = PcrCloningValidationReport(
                passed = validation.passed,
                targetMatchesAmplicon = validation.targetMatchesAmplicon,
                restrictionSitesAreUniqueInAmplicon = validation.restrictionSitesAreUniqueInAmplicon,
                productContainsTarget = validation.productContainsTarget,
                insertWasFlipped = validation.insertWasFlipped,
                templateTarget = range(validation.coordinates.templateTarget),
                pcrTarget = range(validation.coordinates.pcrTarget),
                productInsert = range(validation.coordinates.productInsert),
                diagnostics = validation.diagnostics.map { "${it.severity}: ${it.message}" },
            ),
            recipe = result.recipe,
        )
    }

    fun workflowJson(report: WorkflowReport): String = reportJson.encodeToString(report)

    /** Standalone, printable HTML form of a workflow record. */
    fun workflowHtml(report: WorkflowReport): String = htmlDocument(report.operation, workflowMarkdown(report))

    /** A dependency-free PDF suitable for attaching the workflow record to an ELN. */
    fun workflowPdf(report: WorkflowReport): ByteArray = textPdf(workflowMarkdown(report))

    fun primerDesignJson(report: PrimerDesignReport): String = reportJson.encodeToString(report)

    fun pcrCloningJson(report: PcrCloningReport): String = reportJson.encodeToString(report)

    fun pcrCloningHtml(report: PcrCloningReport): String = htmlDocument(
        "PCR restriction cloning: ${report.workflow.productName}",
        pcrCloningMarkdown(report),
    )

    fun pcrCloningPdf(report: PcrCloningReport): ByteArray = textPdf(pcrCloningMarkdown(report))

    fun workflowReplayReport(result: WorkflowReplayResult): WorkflowReplayReport = WorkflowReplayReport(
        status = result.status.name,
        succeeded = result.succeeded,
        operationType = result.operation.operationType.name,
        expectedOutputIdentity = result.recipe.outputCdseguid,
        productName = result.product?.name,
        productIdentity = result.product?.let(SequenceIdentity::cdseguid),
        messages = result.messages,
    )

    fun workflowReplayJson(result: WorkflowReplayResult): String = reportJson.encodeToString(workflowReplayReport(result))

    /** Captures the same operation as a portable, machine-readable recipe. */
    fun workflowRecipe(
        operation: String,
        product: Seq,
        inputs: List<Seq> = emptyList(),
        parameters: Map<String, String> = emptyMap(),
        externalTools: Map<String, String> = emptyMap(),
        onlineSources: Map<String, String> = emptyMap(),
    ): WorkflowRecipe = WorkflowRecipes.capture(operation, product, inputs, parameters, externalTools, onlineSources)

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
        appendLine("| Read | Identity | Aligned bases | Mismatches | Indels | Confidence |")
        appendLine("|---|---:|---:|---:|---:|---|")
        report.reads.forEach { read ->
            appendLine("| ${read.name} | ${round1(read.identity * 100)}% | ${read.alignedLength} | ${read.mismatchCount} | ${read.insertions + read.deletions} | ${read.confidence} |")
        }
        if (report.uncoveredPositions.isNotEmpty()) {
            appendLine()
            appendLine("## Uncovered positions")
            appendLine(report.uncoveredPositions.joinToString(", "))
        }
    }

    fun primerDesignMarkdown(report: PrimerDesignReport): String = buildString {
        appendLine("# Primer design: ${report.templateName}")
        appendLine()
        appendLine("- Template: **${report.templateLength} bp** (`${report.templateIdentity}`)")
        appendLine("- Target: **${report.targetStart}..${report.targetEnd}**")
        appendLine("- Mode: **${report.mode.lowercase()}**")
        appendLine("- Backend used: **${report.backend}**")
        appendLine()
        appendLine("## Parameters")
        report.parameters.forEach { (key, value) -> appendLine("- $key: $value") }
        if (report.effectiveExcludedRegions.isNotEmpty()) {
            appendLine()
            appendLine("## Effective excluded regions")
            appendLine(report.effectiveExcludedRegions.joinToString(", ") { "${it.start}-${it.end}" })
        }
        report.quality?.let { quality ->
            appendLine()
            appendLine("## Quality constraints")
            appendLine("- Minimum Phred: **${quality.minimumPhred}**")
            appendLine("- Observed template positions: **${quality.observedPositionCount}**")
            appendLine("- Exclude uncovered positions: **${quality.excludeUncoveredPositions}**")
            if (quality.lowQualityRegions.isNotEmpty()) {
                appendLine("- Low-quality regions: ${quality.lowQualityRegions.joinToString(", ") { "${it.start}-${it.end}" }}")
            }
            if (quality.uncoveredRegions.isNotEmpty()) {
                appendLine("- Uncovered regions: ${quality.uncoveredRegions.joinToString(", ") { "${it.start}-${it.end}" }}")
            }
            if (quality.manualExcludedRegions.isNotEmpty()) {
                appendLine("- Manual exclusions: ${quality.manualExcludedRegions.joinToString(", ") { "${it.start}-${it.end}" }}")
            }
            if (quality.sources.isNotEmpty()) {
                appendLine("- Sources: ${quality.sources.joinToString { "${it.kind}: ${it.label}" }}")
            }
        }
        appendLine()
        appendLine("## Candidates")
        appendLine("| Name | Position | Sequence | Tm | GC | Score | Self-complementarity |")
        appendLine("|---|---:|---|---:|---:|---:|---:|")
        report.candidates.forEach { candidate ->
            appendLine("| ${candidate.name} | ${candidate.start}..${candidate.end} | ${candidate.sequence} | ${candidate.tm} | ${candidate.gcPercent}% | ${candidate.score} | ${candidate.selfComplementarity} |")
        }
        if (report.primer3Input != null || report.primer3Command != null) {
            appendLine()
            appendLine("## Primer3 provenance")
            report.primer3Command?.let { appendLine("- Command: `$it`") }
            report.primer3Input?.let {
                appendLine()
                appendLine("~~~")
                append(it)
                if (!it.endsWith('\n')) appendLine()
                appendLine("~~~")
            }
        }
        if (report.warnings.isNotEmpty()) {
            appendLine()
            appendLine("## Warnings")
            report.warnings.forEach { appendLine("- ⚠ $it") }
        }
    }

    fun pcrCloningMarkdown(report: PcrCloningReport): String = buildString {
        appendLine("# PCR restriction cloning: ${report.workflow.productName}")
        appendLine()
        appendLine("- Product: **${report.workflow.productLength} bp**, ${report.workflow.productTopology.lowercase()}")
        appendLine("- Product identity: `${report.workflow.productIdentity}`")
        appendLine("- Insert template: **${report.insertTemplateName}** (`${report.insertTemplateIdentity}`)")
        appendLine("- Enzymes: **${report.enzymes.joinToString(", ")}**")
        appendLine()
        appendLine("## PCR primers")
        appendLine("| Primer | 5' extension | Hybridizing sequence | Full sequence |")
        appendLine("|---|---|---|---|")
        listOf(report.forwardPrimer, report.reversePrimer).forEach { primer ->
            appendLine("| ${primer.name} | ${primer.extension.ifBlank { "—" }} | ${primer.hybridizingSequence} | ${primer.fullSequence} |")
        }
        appendLine()
        appendLine("## Coordinate validation")
        appendLine("- Template target: **${report.validation.templateTarget.start}..${report.validation.templateTarget.end}**")
        appendLine("- Amplicon target: **${report.validation.pcrTarget.start}..${report.validation.pcrTarget.end}**")
        appendLine("- Product insert: **${report.validation.productInsert.start}..${report.validation.productInsert.end}**")
        appendLine("- Target matches amplicon: **${report.validation.targetMatchesAmplicon}**")
        appendLine("- Restriction sites validated: **${report.validation.restrictionSitesAreUniqueInAmplicon}**")
        appendLine("- Product contains target: **${report.validation.productContainsTarget}**")
        appendLine("- Insert flipped: **${report.validation.insertWasFlipped}**")
        if (report.workflow.parameters.isNotEmpty()) {
            appendLine()
            appendLine("## Normalized parameters")
            report.workflow.parameters.forEach { (key, value) -> appendLine("- $key: `$value`") }
        }
        if (report.validation.diagnostics.isNotEmpty() || report.workflow.warnings.isNotEmpty()) {
            appendLine()
            appendLine("## Diagnostics")
            (report.validation.diagnostics + report.workflow.warnings).distinct().forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## Reproducibility recipe")
        appendLine("- Operation: `${report.recipe.operation}`")
        appendLine("- Schema version: ${report.recipe.schemaVersion}")
        appendLine("- Inputs: ${report.recipe.inputs.joinToString { "${it.name} (${it.cdseguid})" }}")
    }

    private fun htmlDocument(title: String, body: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${htmlEscape(title)}</title>
          <style>
            body { color: #18212f; background: #fff; font: 15px/1.45 system-ui, sans-serif; margin: 2rem auto; max-width: 75rem; padding: 0 1.5rem; }
            pre { white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace; }
          </style>
        </head>
        <body><pre>${htmlEscape(body)}</pre></body>
        </html>
    """.trimIndent() + "\n"

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * Small, intentionally dependency-free PDF writer. Reports are plain text
     * by design, which keeps PDF export deterministic and portable without a
     * heavyweight rendering stack. Each page uses the standard Helvetica font.
     */
    private fun textPdf(text: String): ByteArray {
        val lines = text.lineSequence()
            .flatMap { wrapPdfLine(it).asSequence() }
            .toList()
            .ifEmpty { listOf("") }
        val pageLines = lines.chunked(58)
        data class PdfPage(val pageObject: Int, val contentObject: Int, val content: ByteArray)
        var nextObject = 4
        val pages = pageLines.map { page ->
            val pageObject = nextObject++
            val contentObject = nextObject++
            PdfPage(pageObject, contentObject, pdfPageContent(page).toByteArray(StandardCharsets.ISO_8859_1))
        }
        val objects = linkedMapOf<Int, ByteArray>()
        objects[1] = "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(StandardCharsets.ISO_8859_1)
        objects[2] = "<< /Type /Pages /Kids [${pages.joinToString(" ") { "${it.pageObject} 0 R" }}] /Count ${pages.size} >>"
            .toByteArray(StandardCharsets.ISO_8859_1)
        objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray(StandardCharsets.ISO_8859_1)
        pages.forEach { page ->
            objects[page.pageObject] = (
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents ${page.contentObject} 0 R >>"
                ).toByteArray(StandardCharsets.ISO_8859_1)
            val streamHeader = "<< /Length ${page.content.size} >>\nstream\n".toByteArray(StandardCharsets.ISO_8859_1)
            val streamFooter = "\nendstream".toByteArray(StandardCharsets.ISO_8859_1)
            objects[page.contentObject] = streamHeader + page.content + streamFooter
        }

        val output = ByteArrayOutputStream()
        output.write("%PDF-1.4\n% InstaGene report\n".toByteArray(StandardCharsets.ISO_8859_1))
        val offsets = ArrayList<Int>(objects.size)
        objects.toSortedMap().forEach { (number, value) ->
            offsets += output.size()
            output.write("$number 0 obj\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(value)
            output.write("\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        val xrefOffset = output.size()
        output.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray(StandardCharsets.ISO_8859_1))
        offsets.forEach { offset ->
            output.write("%010d 00000 n \n".format(offset).toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write(
            "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        return output.toByteArray()
    }

    private fun pdfPageContent(lines: List<String>): String = buildString {
        append("BT\n/F1 9 Tf\n50 756 Td\n12 TL\n")
        lines.forEach { line -> append('(').append(pdfEscape(line)).append(") Tj\nT*\n") }
        append("ET\n")
    }

    private fun wrapPdfLine(value: String, width: Int = 92): List<String> {
        val sanitized = value.map { character -> if (character.code in 32..126) character else '?' }.joinToString("")
        if (sanitized.length <= width) return listOf(sanitized)
        return sanitized.chunked(width)
    }

    private fun pdfEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")
}
