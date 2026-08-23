package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultipleAlignmentViewTest {

    private val result = MultipleAlignmentResult(
        MultipleAlignmentAlgorithm.BUILTIN,
        listOf(
            Seq(name = "reference", bases = "AC-GT"),
            Seq(name = "sample-a", bases = "AT-GT"),
            Seq(name = "sample-b", bases = "AGAGT"),
        ),
    )

    @Test
    fun consensusConservationAndReferenceCoordinatesAreDeterministic() {
        val view = result.view()
        assertEquals("ANAGT", view.consensus)
        assertEquals(1.0, view.conservation[0])
        assertEquals(1.0 / 3.0, view.conservation[1])
        assertEquals(1.0, view.conservation[2])
        assertEquals(listOf(1, 2, null, 3, 4), view.referencePositions)
        assertNull(view.referencePositions[2])
    }

    @Test
    fun alignedFastaRetainsGapsAndNames() {
        val fasta = result.toFasta(3)
        assertTrue(fasta.contains(">reference\nAC-\nGT\n"))
        assertTrue(fasta.contains(">sample-b\nAGA\nGT\n"))
    }
}
