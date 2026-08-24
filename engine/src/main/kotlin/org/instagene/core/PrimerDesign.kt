package org.instagene.core

data class PrimerDesignParameters(
    val minLength: Int = 18,
    val maxLength: Int = 30,
    val targetTm: Double = 60.0,
    val minTm: Double = 50.0,
    val maxTm: Double = 70.0,
    val minGc: Double = 30.0,
    val maxGc: Double = 70.0,
    val maxHomopolymer: Int = 5,
    val maxSelfComplementarity: Int = 8,
    /** Zero-based, inclusive template regions that may not overlap a primer. */
    val excludedRegions: List<IntRange> = emptyList(),
    /** PCR returns primers at the selected amplicon ends; sequencing scans its selected window. */
    val mode: PrimerDesignMode = PrimerDesignMode.PCR,
    val sequencingDirection: SequencingPrimerDirection = SequencingPrimerDirection.BOTH,
    /** Optional ABI/SCF, FASTA-QUAL, and manual evidence applied in addition to [excludedRegions]. */
    val qualityContext: PrimerQualityContext? = null,
) {
    /** Stable merged exclusions passed to both the bundled designer and Primer3. */
    fun effectiveExcludedRegions(templateLength: Int): List<IntRange> = QualityRegions.merge(
        excludedRegions.mapNotNull { region ->
            val first = region.first.coerceAtLeast(0)
            val last = region.last.coerceAtMost(templateLength - 1)
            (first..last).takeUnless(IntRange::isEmpty)
        } + qualityContext?.effectiveExcludedRegions().orEmpty(),
    )
}

/** The primer-selection workflow, recorded with results and Primer3 input. */
enum class PrimerDesignMode { PCR, SEQUENCING }

/** Which primer orientations are offered by [PrimerDesignMode.SEQUENCING]. */
enum class SequencingPrimerDirection { FORWARD, REVERSE, BOTH }

/** Selects the explainable bundled search or an installed Primer3 binary. */
enum class PrimerDesignBackend { BUILTIN, PRIMER3 }

/** Candidates plus provenance, so results remain auditable when a tool falls back. */
data class PrimerDesignResult(
    val candidates: List<PrimerCandidate>,
    val backend: PrimerDesignBackend,
    val warnings: List<String> = emptyList(),
    val command: String? = null,
    /** Merged regular, manual, low-quality, and optionally uncovered regions. */
    val effectiveExcludedRegions: List<IntRange> = emptyList(),
    val qualitySummary: PrimerQualitySummary? = null,
    /** Exact Boulder-IO sent to Primer3, retained even when it falls back. */
    val primer3Input: String? = null,
)

data class PrimerCandidate(
    val primer: SeqOps.Primer,
    val start: Int,
    val end: Int,
    val score: Double,
    val selfComplementarity: Int,
)

/** Exhaustive, explainable primer candidate search for PCR and assembly tools. */
object PrimerDesign {
    /**
     * Designs candidates with the requested backend. Primer3 is optional: an
     * unavailable or failing executable returns the deterministic built-in
     * candidates and records why, instead of making normal installations fail.
     */
    fun design(
        seq: Seq,
        start: Int,
        end: Int,
        parameters: PrimerDesignParameters = PrimerDesignParameters(),
        backend: PrimerDesignBackend = PrimerDesignBackend.BUILTIN,
        cancellationRequested: () -> Boolean = { false },
        /** Injectable only for deterministic integrations/tests; normal callers use [ExternalTools]. */
        primer3Runner: ((String) -> ToolResult)? = null,
    ): PrimerDesignResult {
        if (backend == PrimerDesignBackend.BUILTIN) return resultFor(seq, candidates(seq, start, end, parameters), backend, parameters)
        val tool = ExternalTools.CATALOG.first { it.id == "primer3" }
        val request = primer3Input(seq, start, end, parameters)
        val execution = primer3Runner?.invoke(request)
            ?: ExternalTools.runText(tool, request, cancellationRequested = cancellationRequested)
        if (execution.succeeded) {
            return runCatching { parsePrimer3Output(execution.stdout, seq, parameters) }
                .map { candidates -> resultFor(seq, candidates, PrimerDesignBackend.PRIMER3, parameters, command = execution.command, primer3Input = request) }
                .getOrElse { error -> fallback(seq, start, end, parameters, "Primer3 output could not be read: ${error.message}", execution.command, request) }
        }
        return fallback(
            seq, start, end, parameters,
            execution.stderr.ifBlank { "Primer3 failed (exit ${execution.exitCode})" }, execution.command, request,
        )
    }

    fun candidates(seq: Seq, start: Int, end: Int, parameters: PrimerDesignParameters = PrimerDesignParameters()): List<PrimerCandidate> {
        require(start in 0 until seq.length && end in (start + 1)..seq.length) { "Invalid primer target" }
        val output = when (parameters.mode) {
            PrimerDesignMode.PCR -> pcrCandidates(seq, start, end, parameters)
            PrimerDesignMode.SEQUENCING -> sequencingCandidates(seq, start, end, parameters)
        }
        val excluded = parameters.effectiveExcludedRegions(seq.length)
        return output.filter {
            it.primer.tm in parameters.minTm..parameters.maxTm &&
                it.primer.gc in parameters.minGc..parameters.maxGc &&
                it.selfComplementarity <= parameters.maxSelfComplementarity &&
                !overlapsExcludedRegion(it, excluded)
        }.sortedBy { it.score }
    }

    private fun pcrCandidates(seq: Seq, start: Int, end: Int, parameters: PrimerDesignParameters): List<PrimerCandidate> = buildList {
        for (length in parameters.minLength..parameters.maxLength) {
            if (start + length <= end) add(candidate(seq.sub(start, start + length), start, start + length, parameters, "F"))
            if (end - length >= start) {
                val reverse = seq.sub(end - length, end).let { Seq(name = "primer", bases = it).reverseComplement().bases }
                add(candidate(reverse, end - length, end, parameters, "R"))
            }
        }
    }

    /**
     * Sequencing mode treats the selected range as a primer-binding search
     * window rather than an amplicon. It returns individual forward/reverse
     * candidates so a researcher can choose a primer beside the region to read.
     */
    private fun sequencingCandidates(seq: Seq, start: Int, end: Int, parameters: PrimerDesignParameters): List<PrimerCandidate> = buildList {
        for (length in parameters.minLength..parameters.maxLength) {
            if (end - start < length) continue
            for (primerStart in start..(end - length)) {
                val primerEnd = primerStart + length
                if (parameters.sequencingDirection in setOf(SequencingPrimerDirection.FORWARD, SequencingPrimerDirection.BOTH)) {
                    add(candidate(seq.sub(primerStart, primerEnd), primerStart, primerEnd, parameters, "SEQ_F_${primerStart + 1}"))
                }
                if (parameters.sequencingDirection in setOf(SequencingPrimerDirection.REVERSE, SequencingPrimerDirection.BOTH)) {
                    val reverse = seq.sub(primerStart, primerEnd).let { Seq(name = "primer", bases = it).reverseComplement().bases }
                    add(candidate(reverse, primerStart, primerEnd, parameters, "SEQ_R_${primerStart + 1}"))
                }
            }
        }
    }

    /** Primer3 Boulder-IO request, kept public for reproducible CLI/report provenance. */
    fun primer3Input(seq: Seq, start: Int, end: Int, parameters: PrimerDesignParameters): String = buildString {
        appendLine("SEQUENCE_ID=${seq.name.ifBlank { "instagene" }}")
        appendLine("SEQUENCE_TEMPLATE=${seq.bases.uppercase()}")
        appendLine("SEQUENCE_INCLUDED_REGION=$start,${end - start}")
        val excluded = parameters.effectiveExcludedRegions(seq.length)
        if (excluded.isNotEmpty()) {
            val regions = excluded.joinToString(" ") { "${it.first},${it.last - it.first + 1}" }
            appendLine("SEQUENCE_EXCLUDED_REGION=$regions")
        }
        appendLine("PRIMER_TASK=${if (parameters.mode == PrimerDesignMode.PCR) "generic" else "pick_sequencing_primers"}")
        val direction = if (parameters.mode == PrimerDesignMode.PCR) SequencingPrimerDirection.BOTH else parameters.sequencingDirection
        appendLine("PRIMER_PICK_LEFT_PRIMER=${if (direction != SequencingPrimerDirection.REVERSE) 1 else 0}")
        appendLine("PRIMER_PICK_RIGHT_PRIMER=${if (direction != SequencingPrimerDirection.FORWARD) 1 else 0}")
        appendLine("PRIMER_NUM_RETURN=10")
        appendLine("PRIMER_MIN_SIZE=${parameters.minLength}")
        appendLine("PRIMER_OPT_SIZE=${((parameters.minLength + parameters.maxLength) / 2)}")
        appendLine("PRIMER_MAX_SIZE=${parameters.maxLength}")
        appendLine("PRIMER_MIN_TM=${parameters.minTm}")
        appendLine("PRIMER_OPT_TM=${parameters.targetTm}")
        appendLine("PRIMER_MAX_TM=${parameters.maxTm}")
        appendLine("PRIMER_MIN_GC=${parameters.minGc}")
        appendLine("PRIMER_MAX_GC=${parameters.maxGc}")
        appendLine("=")
    }

    /** Parses the stable, line-oriented subset of Primer3 Boulder-IO output. */
    fun parsePrimer3Output(output: String, seq: Seq, parameters: PrimerDesignParameters = PrimerDesignParameters()): List<PrimerCandidate> {
        val fields = output.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0].trim() to it[1].trim() }
        fields["PRIMER_ERROR"]?.takeIf(String::isNotBlank)?.let { error("Primer3: $it") }
        val pairs = Regex("PRIMER_(LEFT|RIGHT)_(\\d+)(?:_SEQUENCE)?").findAll(fields.keys.joinToString("\n"))
            .map { it.groupValues[2].toInt() }.toSet().sorted()
        val excluded = parameters.effectiveExcludedRegions(seq.length)
        return pairs.flatMap { index ->
            listOfNotNull(
                fields["PRIMER_LEFT_${index}_SEQUENCE"]?.let { bases -> primer3Candidate("F", bases, fields["PRIMER_LEFT_$index"], fields["PRIMER_PAIR_${index}_PENALTY"], parameters) },
                fields["PRIMER_RIGHT_${index}_SEQUENCE"]?.let { bases -> primer3Candidate("R", bases, fields["PRIMER_RIGHT_$index"], fields["PRIMER_PAIR_${index}_PENALTY"], parameters) },
            )
        }.filter { it.start >= 0 && it.end <= seq.length && !overlapsExcludedRegion(it, excluded) }.sortedBy { it.score }
    }

    private fun primer3Candidate(kind: String, bases: String, position: String?, penalty: String?, p: PrimerDesignParameters): PrimerCandidate? {
        val numbers = position?.split(',')?.mapNotNull(String::toIntOrNull) ?: return null
        if (numbers.size != 2) return null
        val (coordinate, length) = numbers
        val start = if (kind == "R") coordinate - length + 1 else coordinate
        val end = start + length
        val primer = SeqOps.Primer("primer3_$kind", bases, SeqOps.meltingTemp(bases), SeqOps.gcContent(bases))
        return PrimerCandidate(primer, start, end, penalty?.toDoubleOrNull() ?: kotlin.math.abs(primer.tm - p.targetTm), selfComplementarity(bases))
    }

    private fun fallback(
        seq: Seq,
        start: Int,
        end: Int,
        parameters: PrimerDesignParameters,
        reason: String,
        command: String?,
        primer3Input: String,
    ): PrimerDesignResult = resultFor(
        seq = seq,
        candidates = candidates(seq, start, end, parameters),
        backend = PrimerDesignBackend.BUILTIN,
        parameters = parameters,
        warnings = listOf("Primer3 unavailable; used built-in design: $reason"),
        command = command,
        primer3Input = primer3Input,
    )

    private fun resultFor(
        seq: Seq,
        candidates: List<PrimerCandidate>,
        backend: PrimerDesignBackend,
        parameters: PrimerDesignParameters,
        warnings: List<String> = emptyList(),
        command: String? = null,
        primer3Input: String? = null,
    ): PrimerDesignResult {
        val quality = parameters.qualityContext?.summary()
        val qualityWarnings = when {
            quality == null -> emptyList()
            quality.uncoveredPositions.isNotEmpty() && !quality.excludeUncoveredPositions -> listOf(
                "Quality evidence leaves ${quality.uncoveredPositions.size} template position(s) uncovered; they were kept distinct and not excluded."
            )
            else -> emptyList()
        }
        return PrimerDesignResult(
            candidates = candidates,
            backend = backend,
            warnings = warnings + qualityWarnings,
            command = command,
            effectiveExcludedRegions = parameters.effectiveExcludedRegions(seq.length),
            qualitySummary = quality,
            primer3Input = primer3Input,
        )
    }

    /** Candidate bounds are half-open, whereas exclusions are inclusive. */
    private fun overlapsExcludedRegion(candidate: PrimerCandidate, excluded: List<IntRange>): Boolean =
        excluded.any { region -> candidate.start <= region.last && region.first < candidate.end }

    private fun candidate(bases: String, start: Int, end: Int, p: PrimerDesignParameters, suffix: String): PrimerCandidate {
        val primer = SeqOps.Primer("candidate_$suffix", bases.uppercase(), SeqOps.meltingTemp(bases), SeqOps.gcContent(bases))
        val homopolymer = Regex("(.)\\1{${p.maxHomopolymer},}", RegexOption.IGNORE_CASE).containsMatchIn(bases)
        val self = selfComplementarity(bases)
        val score = kotlin.math.abs(primer.tm - p.targetTm) + kotlin.math.abs(primer.gc - 50.0) / 10.0 + if (homopolymer) 100.0 else 0.0
        return PrimerCandidate(primer, start, end, score, self)
    }

    private fun selfComplementarity(bases: String): Int {
        val rc = Seq(name = "primer", bases = bases).reverseComplement().bases
        var best = 0
        for (offset in -bases.length..bases.length) {
            var run = 0
            for (i in bases.indices) {
                val j = i + offset
                if (j in rc.indices && bases[i] == rc[j]) { run++; best = maxOf(best, run) } else run = 0
            }
        }
        return best
    }
}
