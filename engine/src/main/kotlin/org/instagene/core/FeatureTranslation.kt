package org.instagene.core

/** Severity used by coordinate-linked CDS and reading-frame checks. */
enum class TranslationValidationSeverity { INFO, WARNING, ERROR }

/** A precise, reviewable translation or frame issue. Coordinates are zero-based sequence positions. */
data class TranslationValidationIssue(
    val severity: TranslationValidationSeverity,
    val code: String,
    val message: String,
    val coordinates: List<Int> = emptyList(),
)

/** One translated codon and the exact source positions that produced it, in biological 5'→3' order. */
data class TranslatedCodon(
    val aminoAcidIndex: Int,
    val aminoAcidNumber: Int,
    val codon: String,
    val aminoAcid: Char,
    /** Three zero-based positions, descending for reverse-strand features. */
    val sourcePositions: List<Int>,
) {
    fun displayCoordinates(): String = sourcePositions.joinToString(",") { (it + 1).toString() }
}

/**
 * Translation of an annotated feature with its frame and every amino acid
 * linked back to the nucleotide coordinates that generated it.
 */
data class FeatureTranslationResult(
    val feature: Feature,
    val tableId: Int,
    val tableName: String,
    /** Feature bases in biological 5'→3' orientation, before `codon_start` adjustment. */
    val orientedBases: String,
    val translatedBases: String,
    val protein: String,
    val codons: List<TranslatedCodon>,
    val skippedLeadingPositions: List<Int>,
    val trailingPositions: List<Int>,
    val issues: List<TranslationValidationIssue> = emptyList(),
) {
    val isInFrame: Boolean get() = trailingPositions.isEmpty()
    val hasErrors: Boolean get() = issues.any { it.severity == TranslationValidationSeverity.ERROR }
    val hasWarnings: Boolean get() = issues.any { it.severity == TranslationValidationSeverity.WARNING }
    @Suppress("unused")
    val validationStatus: TranslationValidationSeverity
        get() = when {
            hasErrors -> TranslationValidationSeverity.ERROR
            hasWarnings -> TranslationValidationSeverity.WARNING
            else -> TranslationValidationSeverity.INFO
        }
}

/** Translation and validation of arbitrary annotated features, especially CDS features. */
object FeatureTranslations {

    /**
     * Extracts [feature] from [sequence], honors strand, joined segments, the
     * genetic code and `codon_start`, then validates coding boundaries and any
     * declared GenBank `/translation` qualifier.
     */
    fun translate(sequence: Seq, feature: Feature): FeatureTranslationResult {
        val issues = mutableListOf<TranslationValidationIssue>()
        if (sequence.kind == SeqKind.PROTEIN) {
            return emptyResult(feature, "Protein sequences cannot be translated as nucleotide features.")
        }
        val segments = feature.locationSegments
        val invalid = segments.firstOrNull { it.start !in 0..sequence.length || it.end !in 0..sequence.length || it.end < it.start }
        if (invalid != null) {
            return emptyResult(feature, "Feature '${feature.name}' has coordinates outside '${sequence.name}'.")
        }
        val forwardPositions = segments.flatMap { segment -> (segment.start until segment.end).toList() }
        if (forwardPositions.isEmpty()) return emptyResult(feature, "Feature '${feature.name}' has no nucleotide bases to translate.")
        val forwardBases = forwardPositions.joinToString("") { index -> sequence.bases[index].toString() }
        val biologicalPositions = if (feature.strand == Strand.FORWARD) forwardPositions else forwardPositions.asReversed()
        val oriented = if (feature.strand == Strand.FORWARD) forwardBases.uppercase()
        else Alphabet.reverseComplement(forwardBases).uppercase()

        val table = runCatching { CodonTable.byId(feature.geneticCodeId) }.getOrElse { error ->
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.ERROR,
                "UNKNOWN_GENETIC_CODE",
                error.message ?: "Unknown genetic code table ${feature.geneticCodeId}.",
            )
            CodonTable.STANDARD
        }
        val offset = feature.translationStartOffset
        if (offset !in 0..2 || offset >= oriented.length) {
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.ERROR,
                "INVALID_CODON_START",
                "codon_start offset $offset leaves no complete reading frame for feature '${feature.name}'.",
                biologicalPositions,
            )
            return result(feature, table, oriented, "", emptyList(), biologicalPositions, emptyList(), issues)
        }
        val translatedBases = oriented.drop(offset)
        val translatedPositions = biologicalPositions.drop(offset)
        val trailingLength = translatedBases.length % 3
        val codingLength = translatedBases.length - trailingLength
        val codons = ArrayList<TranslatedCodon>(codingLength / 3)
        for (index in 0 until codingLength step 3) {
            val codon = translatedBases.substring(index, index + 3)
            val aminoAcidIndex = index / 3
            codons += TranslatedCodon(
                aminoAcidIndex = aminoAcidIndex,
                aminoAcidNumber = feature.translationNumberingStart + aminoAcidIndex,
                codon = codon,
                aminoAcid = table.translate(codon),
                sourcePositions = translatedPositions.subList(index, index + 3),
            )
        }
        val trailing = translatedPositions.takeLast(trailingLength)
        val skipped = biologicalPositions.take(offset)
        val protein = codons.joinToString("") { it.aminoAcid.toString() }

        if (codons.isEmpty()) {
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.ERROR,
                "NO_COMPLETE_CODON",
                "Feature '${feature.name}' has no complete codon after codon_start adjustment.",
                translatedPositions,
            )
        }
        if (trailing.isNotEmpty()) {
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.WARNING,
                "PARTIAL_TERMINAL_CODON",
                "${trailing.size} trailing base(s) do not form a complete codon.",
                trailing,
            )
        }
        if (feature.type.equals("CDS", ignoreCase = true) && codons.isNotEmpty()) {
            val first = codons.first()
            val last = codons.last()
            if (!table.isStart(first.codon)) {
                issues += TranslationValidationIssue(
                    TranslationValidationSeverity.WARNING,
                    "MISSING_START_CODON",
                    "CDS does not start with a ${table.displayName} start codon (${first.codon} at ${first.displayCoordinates()}).",
                    first.sourcePositions,
                )
            }
            if (last.aminoAcid != '*') {
                issues += TranslationValidationIssue(
                    TranslationValidationSeverity.WARNING,
                    "MISSING_TERMINAL_STOP",
                    "CDS does not end with a stop codon (${last.codon} at ${last.displayCoordinates()}).",
                    last.sourcePositions,
                )
            }
            codons.dropLast(1).filter { it.aminoAcid == '*' }.forEach { stop ->
                issues += TranslationValidationIssue(
                    TranslationValidationSeverity.ERROR,
                    "INTERNAL_STOP_CODON",
                    "Internal stop codon ${stop.codon} at ${stop.displayCoordinates()} (amino acid ${stop.aminoAcidNumber}).",
                    stop.sourcePositions,
                )
            }
        }
        codons.filter { it.aminoAcid == 'X' }.forEach { unknown ->
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.WARNING,
                "AMBIGUOUS_CODON",
                "Ambiguous codon ${unknown.codon} at ${unknown.displayCoordinates()} translates to X.",
                unknown.sourcePositions,
            )
        }
        if (feature.ribosomalSlippage != 0) {
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.WARNING,
                "PROGRAMMED_SLIPPAGE",
                "Feature declares a ${if (feature.ribosomalSlippage > 0) "+" else ""}${feature.ribosomalSlippage} programmed ribosomal slippage; exact shift position is not encoded in the feature model.",
            )
        }
        validateDeclaredTranslation(feature, protein, issues)
        return result(feature, table, oriented, translatedBases, codons, skipped, trailing, issues)
    }

    /** Validates every CDS feature in a sequence in coordinate order. */
    fun validateCodingFeatures(sequence: Seq): List<FeatureTranslationResult> = sequence.features
        .filter { it.type.equals("CDS", ignoreCase = true) }
        .sortedBy { it.start }
        .map { translate(sequence, it) }

    /** Compact, copyable review text used by GUI, CLI, reports, and notebooks. */
    fun summary(result: FeatureTranslationResult, includeCodons: Boolean = true): String = buildString {
        appendLine("${result.feature.name} (${result.feature.type}, ${result.feature.displayRange()}, ${result.feature.strand.symbol})")
        appendLine("Genetic code: ${result.tableName}; codon_start offset: ${result.feature.translationStartOffset}")
        appendLine("Frame: ${if (result.isInFrame) "complete" else "partial"}; protein: ${result.protein.length} aa")
        appendLine("Protein: ${result.protein.ifBlank { "(none)" }}")
        if (result.skippedLeadingPositions.isNotEmpty()) {
            appendLine("Skipped leading positions: ${result.skippedLeadingPositions.joinToString(",") { (it + 1).toString() }}")
        }
        if (includeCodons && result.codons.isNotEmpty()) {
            appendLine("Codons (AA number\tAA\tcodon\tsequence coordinates):")
            result.codons.forEach { codon ->
                appendLine("${codon.aminoAcidNumber}\t${codon.aminoAcid}\t${codon.codon}\t${codon.displayCoordinates()}")
            }
        }
        if (result.issues.isNotEmpty()) {
            appendLine("Validation:")
            result.issues.forEach { issue ->
                val coordinates = issue.coordinates.takeIf { it.isNotEmpty() }?.joinToString(",") { (it + 1).toString() }
                appendLine("${issue.severity}: ${issue.code}: ${issue.message}${coordinates?.let { " [$it]" } ?: ""}")
            }
        } else {
            appendLine("Validation: no frame or translation issues found.")
        }
    }

    private fun validateDeclaredTranslation(
        feature: Feature,
        protein: String,
        issues: MutableList<TranslationValidationIssue>,
    ) {
        val declared = feature.qualifiers["translation"]
            ?.joinToString("")
            ?.filterNot(Char::isWhitespace)
            ?.removeSuffix("*")
            ?.uppercase()
            ?: return
        val observed = protein.removeSuffix("*").uppercase()
        if (declared != observed) {
            issues += TranslationValidationIssue(
                TranslationValidationSeverity.ERROR,
                "DECLARED_TRANSLATION_MISMATCH",
                "The declared /translation (${declared.length} aa) does not match coordinate-derived translation (${observed.length} aa).",
            )
        }
    }

    private fun emptyResult(feature: Feature, message: String): FeatureTranslationResult = FeatureTranslationResult(
        feature = feature,
        tableId = feature.geneticCodeId,
        tableName = runCatching { CodonTable.byId(feature.geneticCodeId).displayName }.getOrElse { "Unknown genetic code" },
        orientedBases = "",
        translatedBases = "",
        protein = "",
        codons = emptyList(),
        skippedLeadingPositions = emptyList(),
        trailingPositions = emptyList(),
        issues = listOf(TranslationValidationIssue(TranslationValidationSeverity.ERROR, "INVALID_FEATURE_COORDINATES", message)),
    )

    private fun result(
        feature: Feature,
        table: CodonTable,
        oriented: String,
        translated: String,
        codons: List<TranslatedCodon>,
        skipped: List<Int>,
        trailing: List<Int>,
        issues: List<TranslationValidationIssue>,
    ): FeatureTranslationResult = FeatureTranslationResult(
        feature = feature,
        tableId = table.id,
        tableName = table.displayName,
        orientedBases = oriented,
        translatedBases = translated,
        protein = codons.joinToString("") { it.aminoAcid.toString() },
        codons = codons,
        skippedLeadingPositions = skipped,
        trailingPositions = trailing,
        issues = issues,
    )
}
