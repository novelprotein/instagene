package org.instagene.core

/** Reproducibility metadata attached to an analysis or simulated workflow. */
data class ScientificMethodMetadata(
    val methodName: String,
    val methodVersion: String? = null,
    val evidenceStatus: EvidenceStatus = EvidenceStatus.HEURISTIC,
    val sourceLinks: List<String> = emptyList(),
    val conditions: Map<String, String> = emptyMap(),
    val limitations: List<String> = emptyList(),
) {
    fun summary(): String = buildString {
        appendLine("Method: $methodName${methodVersion?.let { " ($it)" }.orEmpty()}")
        appendLine("Evidence: $evidenceStatus")
        conditions.forEach { (key, value) -> appendLine("$key: $value") }
        sourceLinks.forEach { appendLine("Source: $it") }
        limitations.forEach { appendLine("Limitation: $it") }
    }.trimEnd()
}

enum class EvidenceStatus {
    VERIFIED_REFERENCE,
    CURATED_DATA,
    HEURISTIC,
    SIMULATION,
    UNAVAILABLE,
}
