package org.instagene.core

import org.instagene.core.io.FastaQualRecord

/** The origin of Phred evidence used to constrain primer selection. */
enum class QualityEvidenceKind { CHROMATOGRAM, FASTA_QUAL, MANUAL }

/**
 * Identifies evidence without embedding a laboratory file's contents in a
 * primer-design result. [sourceId] may be a file path, record id, or read id.
 */
data class QualitySourceProvenance(
    val kind: QualityEvidenceKind,
    val label: String,
    val sourceId: String = label,
) {
    init {
        require(label.isNotBlank()) { "Quality evidence label cannot be blank" }
        require(sourceId.isNotBlank()) { "Quality evidence source id cannot be blank" }
    }
}

/** A Phred score mapped onto one zero-based reference position. */
data class ReferencePhredObservation(
    val referencePosition: Int,
    val phred: Int,
) {
    init {
        require(referencePosition >= 0) { "Quality observation is before reference position 0" }
        require(phred >= 0) { "Phred scores must be non-negative" }
    }
}

/** Per-source quality observations and their provenance. */
data class QualityEvidence(
    val source: QualitySourceProvenance,
    val observations: List<ReferencePhredObservation>,
)

/** A user-declared inclusive zero-based exclusion with a human-readable reason. */
data class ManualQualityExclusion(
    val range: IntRange,
    val reason: String = "Manually marked low-quality region",
) {
    init {
        require(!range.isEmpty()) { "Manual quality exclusion cannot be empty" }
        require(range.first >= 0) { "Manual quality exclusion starts before position 0" }
        require(reason.isNotBlank()) { "Manual quality exclusion reason cannot be blank" }
    }
}

/** A compact, report-ready view of the decisions made by [PrimerQualityContext]. */
data class PrimerQualitySummary(
    val minimumPhred: Int,
    val observedPositions: Set<Int>,
    val lowQualityPositions: Set<Int>,
    val uncoveredPositions: Set<Int>,
    val lowQualityRegions: List<IntRange>,
    val uncoveredRegions: List<IntRange>,
    val manualExcludedRegions: List<ManualQualityExclusion>,
    val effectiveExcludedRegions: List<IntRange>,
    val excludeUncoveredPositions: Boolean,
    val sources: List<QualitySourceProvenance>,
)

/** Coordinate helpers shared by GUI, CLI, reports, and the primer engine. */
object QualityRegions {
    /** Merges touching inclusive ranges into stable, sorted ranges. */
    fun merge(ranges: Iterable<IntRange>): List<IntRange> {
        val sorted = ranges.filterNot(IntRange::isEmpty).sortedBy(IntRange::first)
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf<IntRange>()
        var start = sorted.first().first
        var end = sorted.first().last
        sorted.drop(1).forEach { next ->
            if (next.first <= end + 1) {
                end = maxOf(end, next.last)
            } else {
                merged += start..end
                start = next.first
                end = next.last
            }
        }
        merged += start..end
        return merged
    }

    /** Converts individual reference coordinates to merged inclusive ranges. */
    fun fromPositions(positions: Iterable<Int>): List<IntRange> = merge(positions.distinct().sorted().map { it..it })

    /** Parses researcher-facing one-based ranges such as `1-20, 43, 80..90`. */
    fun parseOneBased(specification: String, templateLength: Int, reason: String = "Manually marked low-quality region"): List<ManualQualityExclusion> {
        if (specification.isBlank()) return emptyList()
        require(templateLength > 0) { "Cannot apply quality exclusions to an empty template" }
        return specification.split(',').map { raw ->
            val token = raw.trim()
            val match = Regex("^(\\d+)(?:\\s*(?:-|\\.\\.)\\s*(\\d+))?$").matchEntire(token)
                ?: throw IllegalArgumentException("Invalid quality region '$token'; use one-based forms like 1-20 or 45.")
            val first = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid quality region '$token'.")
            val last = match.groupValues[2].ifBlank { match.groupValues[1] }.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid quality region '$token'.")
            require(first >= 1 && last >= first && last <= templateLength) {
                "Quality region '$token' is outside 1..$templateLength."
            }
            ManualQualityExclusion((first - 1)..(last - 1), reason)
        }
    }

    fun oneBased(ranges: Iterable<IntRange>): String = merge(ranges).joinToString(", ") { "${it.first + 1}-${it.last + 1}" }
}

/**
 * Quality evidence associated with a specific reference template.
 *
 * Multiple observations at one position are combined by their *minimum* Phred
 * score: a weak trace cannot be hidden by a stronger one. Uncovered positions
 * remain a separate category and are only excluded when explicitly requested.
 */
data class PrimerQualityContext(
    val templateLength: Int,
    val minimumPhred: Int = 20,
    val evidence: List<QualityEvidence> = emptyList(),
    val manualExcludedRegions: List<ManualQualityExclusion> = emptyList(),
    val excludeUncoveredPositions: Boolean = false,
) {
    init {
        require(templateLength >= 0) { "Template length cannot be negative" }
        require(minimumPhred >= 0) { "Minimum Phred score cannot be negative" }
        evidence.flatMap(QualityEvidence::observations).forEach { observation ->
            require(observation.referencePosition < templateLength) {
                "Quality observation at ${observation.referencePosition + 1} is outside the $templateLength-base template"
            }
        }
        manualExcludedRegions.forEach { exclusion ->
            require(exclusion.range.last < templateLength) {
                "Manual exclusion ${exclusion.range.first + 1}-${exclusion.range.last + 1} is outside the $templateLength-base template"
            }
        }
    }

    private val scoresByPosition: Map<Int, List<Int>> by lazy {
        evidence.flatMap(QualityEvidence::observations)
            .groupBy(ReferencePhredObservation::referencePosition)
            .mapValues { (_, observations) -> observations.map(ReferencePhredObservation::phred) }
    }

    /** The conservative (lowest observed) Phred score at [position], or null if uncovered. */
    fun phredAt(position: Int): Int? = scoresByPosition[position]?.minOrNull()

    val observedPositions: Set<Int> by lazy { scoresByPosition.keys.toSortedSet() }
    val lowQualityPositions: Set<Int> by lazy {
        observedPositions.filterTo(sortedSetOf()) { position -> phredAt(position)!! < minimumPhred }
    }
    val uncoveredPositions: Set<Int> by lazy {
        (0 until templateLength).filterTo(sortedSetOf()) { it !in observedPositions }
    }
    val lowQualityRegions: List<IntRange> by lazy { QualityRegions.fromPositions(lowQualityPositions) }
    val uncoveredRegions: List<IntRange> by lazy { QualityRegions.fromPositions(uncoveredPositions) }

    /** All quality-derived regions that must not overlap a primer. */
    fun effectiveExcludedRegions(): List<IntRange> = QualityRegions.merge(
        manualExcludedRegions.map(ManualQualityExclusion::range) +
            lowQualityRegions +
            if (excludeUncoveredPositions) uncoveredRegions else emptyList(),
    )

    fun summary(): PrimerQualitySummary = PrimerQualitySummary(
        minimumPhred = minimumPhred,
        observedPositions = observedPositions,
        lowQualityPositions = lowQualityPositions,
        uncoveredPositions = uncoveredPositions,
        lowQualityRegions = lowQualityRegions,
        uncoveredRegions = uncoveredRegions,
        manualExcludedRegions = manualExcludedRegions,
        effectiveExcludedRegions = effectiveExcludedRegions(),
        excludeUncoveredPositions = excludeUncoveredPositions,
        sources = sources,
    )

    /** Each source is retained in result provenance, including manual annotations. */
    val sources: List<QualitySourceProvenance>
        get() = buildList {
            addAll(evidence.map(QualityEvidence::source))
            if (manualExcludedRegions.isNotEmpty()) {
                add(QualitySourceProvenance(QualityEvidenceKind.MANUAL, "Manual quality exclusions"))
            }
        }.distinct()

    fun withEvidence(additional: Iterable<QualityEvidence>): PrimerQualityContext = copy(evidence = evidence + additional)

    fun withManualExclusions(additional: Iterable<ManualQualityExclusion>): PrimerQualityContext =
        copy(manualExcludedRegions = manualExcludedRegions + additional)

    companion object {
        /** Evidence from aligned ABI/SCF reads, preserving each read as a source. */
        fun evidenceFromSangerAlignment(result: SangerAlignmentResult): List<QualityEvidence> = result.reads.mapNotNull { read ->
            read.qualityObservations.takeIf { it.isNotEmpty() }?.let { observations ->
                QualityEvidence(
                    QualitySourceProvenance(QualityEvidenceKind.CHROMATOGRAM, read.readName),
                    observations.map { ReferencePhredObservation(it.referencePosition, it.phred) },
                )
            }
        }

        /** Evidence from a sidecar whose scores correspond sequentially to [offset] in the reference. */
        fun evidenceFromFastaQual(
            record: FastaQualRecord,
            templateLength: Int,
            offset: Int = 0,
            sourceId: String = record.name,
        ): QualityEvidence {
            require(offset >= 0 && offset + record.scores.size <= templateLength) {
                "FASTA-QUAL record '${record.name}' has ${record.scores.size} scores and does not fit at reference position ${offset + 1} in a $templateLength-base template."
            }
            return QualityEvidence(
                QualitySourceProvenance(QualityEvidenceKind.FASTA_QUAL, record.name, sourceId),
                record.scores.mapIndexed { index, score -> ReferencePhredObservation(offset + index, score) },
            )
        }

        fun fromSangerAlignment(
            templateLength: Int,
            result: SangerAlignmentResult,
            minimumPhred: Int = 20,
            manualExcludedRegions: List<ManualQualityExclusion> = emptyList(),
            excludeUncoveredPositions: Boolean = false,
        ): PrimerQualityContext = PrimerQualityContext(
            templateLength = templateLength,
            minimumPhred = minimumPhred,
            evidence = evidenceFromSangerAlignment(result),
            manualExcludedRegions = manualExcludedRegions,
            excludeUncoveredPositions = excludeUncoveredPositions,
        )
    }
}
