package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceTargetsTest {
    @Test
    fun targetsCoverPlasmidsConstructsAndProgressiveGenomeWorkloads() {
        assertEquals(listOf("plasmid-open", "construct-interaction", "genome-progress"), PerformanceTargets.ALL.map { it.id })
        assertEquals(10_000, PerformanceTargets.PLASMID_BASES)
        assertEquals(100_000, PerformanceTargets.CONSTRUCT_BASES)
        assertTrue(PerformanceTargets.PLASMID_OPEN_BUDGET_MILLIS > 0)
        assertTrue(PerformanceTargets.VIEWPORT_RENDER_BUDGET_MILLIS > 0)
    }
}
