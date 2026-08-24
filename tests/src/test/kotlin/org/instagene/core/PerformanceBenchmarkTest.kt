package org.instagene.core

import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Opt-in micro-benchmark for the digest hot paths.
 *
 * Skipped unless the `instagene.perf` system property is set, so it never runs
 * in CI or the normal `./gradlew build` gate:
 *
 *     ./gradlew :tests:test --tests '*PerformanceBenchmarkTest' -Dinstagene.perf=true
 *
 * It times the restriction-site scans over a synthetic genome and prints the
 * numbers, then asserts generous upper bounds so a serious regression (an
 * accidental O(n²) scan, an allocation storm, or EDT-stalling code) fails the
 * run instead of going unnoticed.
 */
class PerformanceBenchmarkTest {
    private companion object {
        const val GENOME_LENGTH = 20_000_000
        const val MEAN_REPEATS = 3
    }

    private fun out(value: Any? = "") {
        print(value)
        print('\n')
    }

    @Test
    fun digestHotPathsOverSyntheticGenome() {
        assumeTrue(
            System.getProperty("instagene.perf") == "true",
            "performance benchmark skipped (set -Dinstagene.perf=true to run)",
        )

        val genome = Seq(bases = randomDna())
        val pool = Enzymes.ALL
        val eco = Enzymes.require("EcoRI")

        out("== Digest performance over a $GENOME_LENGTH bp synthetic genome ==")
        out("Enzyme catalog: ${pool.size} enzymes")

        // Per-enzyme site scan (cutSites builds the site list).
        val single = meanNanos { Digest.cutSites(genome, eco) }
        out("cutSites(genome, EcoRI):           ${single / 1_000_000.0} ms / run")
        assertTrue(single < 2_000_000_000L, "cutSites took ${single / 1_000_000.0} ms, expected < 2 s")

        // Allocation-free count over the whole catalog (what the Digest panel's
        // Cuts column computes on a background thread).
        val counts = meanNanos { Digest.cutCounts(genome, pool) }
        out("cutCounts(genome, ${pool.size} enzymes): ${counts / 1_000_000.0} ms / run")
        assertTrue(counts < 30_000_000_000L, "cutCounts took ${counts / 1_000_000.0} ms, expected < 30 s")

        val countSites = meanNanos { pool.sumOf { Digest.countSites(genome, it) } }
        out("countSites per enzyme (sum):       ${countSites / 1_000_000.0} ms / run")
        assertTrue(countSites < 30_000_000_000L, "countSites took ${countSites / 1_000_000.0} ms, expected < 30 s")
    }

    private fun meanNanos(block: () -> Unit): Long {
        var total = 0L
        repeat(MEAN_REPEATS) {
            total += measureNanoTime { block() }
        }
        return total / MEAN_REPEATS
    }

    private fun randomDna(): String {
        val rng = Random(42)
        val sb = StringBuilder(GENOME_LENGTH)
        repeat(GENOME_LENGTH) { sb.append("ACGT"[rng.nextInt(4)]) }
        return sb.toString()
    }
}
