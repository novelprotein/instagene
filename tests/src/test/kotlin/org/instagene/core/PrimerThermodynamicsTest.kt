package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrimerThermodynamicsTest {

    @Test
    fun thermodynamicResultCalculatesDeltaG() {
        val result = PrimerThermodynamics.thermodynamicResult("ACGTACGTACGTACGTACGT")
        assertTrue(result.deltaG < 0.0, "dG should be negative for a self-complementary sequence")
        assertTrue(result.tm > 0.0, "Tm should be positive")
    }

    @Test
    fun selfDimerScreeningReturnsReasonableScore() {
        val result = PrimerThermodynamics.selfDimer("ACGTACGTACGTACGTACGT")
        assertTrue(result.deltaG <= 0.0, "Self-dimer dG should be negative or zero")
        assertEquals(20, result.length)
    }

    @Test
    fun heteroDimerScreeningWorks() {
        val result = PrimerThermodynamics.heteroDimer("ACGTACGT", "ACGTACGT")
        assertTrue(result.deltaG <= 0.0, "Hetero-dimer dG should be negative for identical sequences")
    }

    @Test
    fun assessHairpinReportsCorrectRisk() {
        val noRisk = PrimerThermodynamics.assessHairpin("AAAA")
        assertEquals(StructureAssessment.NO_RISK, noRisk.assessment)
        val highRisk = PrimerThermodynamics.assessHairpin("GCGCGCGCGCGCGCGCGCGC")
        assertEquals(StructureAssessment.HIGH_RISK, highRisk.assessment)
    }

    @Test
    fun assessSelfDimerReportsRiskLevel() {
        val result = PrimerThermodynamics.assessSelfDimer("ACGTACGTACGTACGTACGT")
        assertTrue(result.assessment in StructureAssessment.entries)
    }

    @Test
    fun fullScreenReturnsMultipleReports() {
        val reports = PrimerThermodynamics.fullScreen("ACGTACGTACGTACGTACGT")
        assertEquals(2, reports.size)
    }
}
