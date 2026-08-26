package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SequenceAnnotationEditTest {
    @Test
    fun insertionRemapsDiscontinuousFeatureSegments() {
        val feature = Feature(
            "joined", start = 2, end = 10,
            segments = listOf(FeatureSegment(2, 5), FeatureSegment(7, 10)),
        )
        val edited = Seq(bases = "AAAAAAAAAAAA", features = listOf(feature)).insertAt(8, "TT")
        val result = edited.features.single()
        assertEquals(2, result.start)
        assertEquals(12, result.end)
        assertEquals(listOf(FeatureSegment(2, 5), FeatureSegment(7, 12)), result.segments)
    }

    @Test
    fun deletionClipsAndShiftsDiscontinuousFeatureSegments() {
        val feature = Feature(
            "joined", start = 2, end = 12,
            segments = listOf(FeatureSegment(2, 6), FeatureSegment(8, 12)),
        )
        val edited = Seq(bases = "AAAAAAAAAAAA", features = listOf(feature)).deleteRange(4, 10)
        val result = edited.features.single()
        assertEquals(2, result.start)
        assertEquals(6, result.end)
        assertEquals(listOf(FeatureSegment(2, 4), FeatureSegment(4, 6)), result.segments)
        assertTrue(edited.bases.length == 6)
    }
}
