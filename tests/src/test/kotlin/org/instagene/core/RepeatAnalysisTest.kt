package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepeatAnalysisTest {

    @Test
    fun findsDirectAndInvertedRepeatsWithOneBasedExportCoordinates() {
        val direct = RepeatAnalysis.findRepeats(
            Seq("direct", "AAAACCGGTTTCCGGAAAA"),
            minimumLength = 4,
            includeInverted = false,
        )
        assertTrue(direct.directRepeats.any { it.sequence == "CCGG" && it.firstStart == 4 && it.secondStart == 11 })
        assertContains(RepeatAnalysis.repeatsTsv(direct), "DIRECT\t5\t8\t12\t15\t4\tCCGG")

        val inverted = RepeatAnalysis.findRepeats(
            Seq("inverted", "AAAAATGCAAAAGCATTTT"),
            minimumLength = 4,
            includeDirect = false,
        )
        assertTrue(inverted.invertedRepeats.any { it.sequence.contains("ATGC") && it.length >= 4 })
        assertContains(RepeatAnalysis.repeatsJson(inverted), "INVERTED")
    }

    @Test
    fun dotPlotIncludesDirectAndInvertedPointsAndBoundedSvgExport() {
        val horizontal = Seq("horizontal", "ATGCATGC")
        val vertical = Seq("vertical", "ATGCGCAT")
        val result = RepeatAnalysis.dotPlot(horizontal, vertical, wordSize = 4, includeInverted = true, maxPoints = 100)

        assertTrue(result.points.any { it.orientation == RepeatOrientation.DIRECT })
        assertTrue(result.points.any { it.orientation == RepeatOrientation.INVERTED })
        assertContains(RepeatAnalysis.dotPlotTsv(result), "orientation")
        assertContains(RepeatAnalysis.dotPlotJson(result), "horizontal")
        val svg = RepeatAnalysis.dotPlotSvg(result, 320, 320)
        assertTrue(svg.startsWith("<?xml"))
        assertContains(svg, "#1565c0")
        assertContains(svg, "#ad1457")

        val capped = RepeatAnalysis.dotPlot(Seq("repeat", "A".repeat(30)), wordSize = 2, maxPoints = 1)
        assertEquals(1, capped.points.size)
        assertTrue(capped.truncated)
    }
}
