package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenGateFidelityTest {

    @Test
    fun scoresStandardSet() {
        val score = GoldenGateFidelity.score(
            listOf("GGAG", "TGAC", "TCCC", "TACT", "CCAT", "AATG", "AGCC", "TTCG", "GCTT", "GGTA", "CGCT"),
        )
        assertTrue(score.setFidelity > 0.99, "Plant standard set should have >99% fidelity")
        assertNull(score.weakestOverhang)
    }

    @Test
    fun detectsDuplicateOverhangs() {
        val score = GoldenGateFidelity.score(listOf("GGAG", "GGAG", "TACT", "AATG"))
        assertTrue(score.warnings.any { it.contains("Duplicate") })
    }

    @Test
    fun detectsPalindromes() {
        val score = GoldenGateFidelity.score(listOf("AATT", "GGAG", "TACT", "AATG"))
        assertTrue(score.warnings.any { it.contains("Palindromic") })
    }

    @Test
    fun standardSetsAreAvailable() {
        val sets = GoldenGateFidelity.standardSets()
        assertTrue(sets.containsKey("Plant Standard (11 overhangs)"))
        assertTrue(sets.containsKey("CIDAR MoClo (8 overhangs)"))
    }

    @Test
    fun reportsPerOverhangScores() {
        val score = GoldenGateFidelity.score(listOf("GGAG", "TGAC", "TCCC", "AATG"))
        assertEquals(4, score.perOverhangFidelity.size)
        for ((_, fi) in score.perOverhangFidelity) {
            assertTrue(fi > 0.0, "Per-overhang fidelity should be positive")
        }
    }
}
