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

    @Test
    fun digestHotPathsOverSyntheticGenome() {
        assumeTrue(
            System.getProperty("instagene.perf") == "true",
            "performance benchmark skipped (set -Dinstagene.perf=true to run)",
        )

        val length = 20_000_000
        val genome = Seq(bases = randomDna(length))
        val pool = Enzymes.ALL
        val eco = Enzymes.require("EcoRI")

        println("== Digest performance over a $length bp synthetic genome ==")
        println("Enzyme catalog: ${pool.size} enzymes")

        // Per-enzyme site scan (cutSites builds the site list).
        val single = meanNanos(3) { Digest.cutSites(genome, eco) }
        println("cutSites(genome, EcoRI):           ${single / 1_000_000.0} ms / run")
        assertTrue(single < 2_000_000_000L, "cutSites took ${single / 1_000_000.0} ms, expected < 2 s")

        // Allocation-free count over the whole catalog (what the Digest panel's
        // Cuts column computes on a background thread).
        val counts = meanNanos(3) { Digest.cutCounts(genome, pool) }
        println("cutCounts(genome, ${pool.size} enzymes): ${counts / 1_000_000.0} ms / run")
        assertTrue(counts < 30_000_000_000L, "cutCounts took ${counts / 1_000_000.0} ms, expected < 30 s")

        val countSites = meanNanos(3) { pool.sumOf { Digest.countSites(genome, it) } }
        println("countSites per enzyme (sum):       ${countSites / 1_000_000.0} ms / run")
        assertTrue(countSites < 30_000_000_000L, "countSites took ${countSites / 1_000_000.0} ms, expected < 30 s")
    }

    private fun meanNanos(repeats: Int, block: () -> Unit): Long {
        var total = 0L
        repeat(repeats) {
            total += measureNanoTime { block() }
        }
        return total / repeats
    }

    private fun randomDna(length: Int): String {
        val rng = Random(42)
        val sb = StringBuilder(length)
        repeat(length) { sb.append("ACGT"[rng.nextInt(4)]) }
        return sb.toString()
    }
}
