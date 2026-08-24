package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceTargetsTest {
    @Test
    fun targetsCoverPlasmidsConstructsAndProgressiveGenomeWorkloads() {
        assertEquals(listOf("plasmid-open", "construct-interaction", "genome-progress"), PerformanceTargets.ALL.map { it.id })
        assertEquals(10_000, PerformanceTargets.PLASMID_BASES)
        assertEquals(100_000, PerformanceTargets.CONSTRUCT_BASES)
        assertEquals(2_000L, PerformanceTargets.PLASMID_OPEN_BUDGET_MILLIS)
        assertEquals(250L, PerformanceTargets.VIEWPORT_RENDER_BUDGET_MILLIS)
    }
}
