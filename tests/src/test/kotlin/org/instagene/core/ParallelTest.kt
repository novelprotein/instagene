package org.instagene.core

import org.instagene.core.support.Parallel
import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelTest {
    @Test
    fun indexedMapPreservesIndicesAcrossChunksWithRepeatedValues() {
        val count = Parallel.cores * 3 + 5
        val items = List(count) { "repeated" }
        assertEquals((0 until count).toList(), Parallel.mapIndexed(items) { index, _ -> index })
    }

    @Test
    fun indexedMapHandlesEmptyAndSmallLists() {
        assertEquals(emptyList(), Parallel.mapIndexed(emptyList<String>()) { index, _ -> index })
        assertEquals(listOf(0), Parallel.mapIndexed(listOf("a")) { index, _ -> index })
        assertEquals(listOf(0, 1), Parallel.mapIndexed(listOf("a", "a")) { index, _ -> index })
    }
}
