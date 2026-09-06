package org.instagene.core

/** Reproducibility metadata attached to an analysis or simulated workflow. */
data class ScientificMethodMetadata(
    val methodName: String,
    val methodVersion: String? = null,
    val evidenceStatus: EvidenceStatus = EvidenceStatus.HEURISTIC,
    val sourceLinks: List<String> = emptyList(),
    val conditions: Map<String, String> = emptyMap(),
    val limitations: List<String> = emptyList(),
)

enum class EvidenceStatus {
    VERIFIED_REFERENCE,
    CURATED_DATA,
    HEURISTIC,
    SIMULATION,
    UNAVAILABLE,
}
