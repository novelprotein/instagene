package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenGateFidelityTest {

    @Test
    fun screensStandardSetWithoutFabricatingPercentages() {
        val score = GoldenGateFidelity.score(
            listOf("GGAG", "TGAC", "TCCC", "TACT", "CCAT", "AATG", "AGCC", "TTCG", "GCTT", "GGTA", "CGCT"),
        )
        assertNull(score.setFidelity)
        assertTrue(!score.quantitativeFidelityAvailable)
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
    fun doesNotReportUnsupportedPerOverhangScores() {
        val score = GoldenGateFidelity.score(listOf("GGAG", "TGAC", "TCCC", "AATG"))
        assertTrue(score.perOverhangFidelity.isEmpty())
        assertNull(score.weakestFidelity)
    }
}
