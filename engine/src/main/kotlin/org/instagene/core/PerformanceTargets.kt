package org.instagene.core

/**
 * Version-controlled desktop responsiveness targets.  They are deliberately
 * expressed as workload classes and user-visible behavior, not universal
 * timing promises: hardware, file systems, and JVM configuration vary.
 */
data class PerformanceTarget(
    val id: String,
    val workload: String,
    val target: String,
    val verification: String,
)

/** Shared targets used by documentation, regression tests, and opt-in benchmarks. */
object PerformanceTargets {
    const val PLASMID_BASES = 10_000
    const val CONSTRUCT_BASES = 100_000
    const val PROGRESSIVE_GENOME_BASES = 1_000_000
    const val VIEWPORT_RENDER_BUDGET_MILLIS = 250L
    const val PLASMID_OPEN_BUDGET_MILLIS = 2_000L

    val ALL: List<PerformanceTarget> = listOf(
        PerformanceTarget(
            id = "plasmid-open",
            workload = "$PLASMID_BASES bp plasmid",
            target = "Open and first viewport render within ${PLASMID_OPEN_BUDGET_MILLIS / 1000} seconds on a typical desktop.",
            verification = "Focused SeqIO and SequenceView regression tests plus the CLI benchmark.",
        ),
        PerformanceTarget(
            id = "construct-interaction",
            workload = "$CONSTRUCT_BASES bp construct",
            target = "Keep editing and scrolling responsive by painting only visible sequence rows.",
            verification = "Viewport virtualization regression test.",
        ),
        PerformanceTarget(
            id = "genome-progress",
            workload = "${PROGRESSIVE_GENOME_BASES / 1_000_000}+ Mb genome or crowded enzyme catalog",
            target = "Run parsing and scans off the event thread with visible progress and cancellation.",
            verification = "Background file-open and cancellable restriction-scan tests.",
        ),
    )
}
