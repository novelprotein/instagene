package org.instagene.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FastaQualTest {

    @Test
    fun parsesNamedWhitespaceSeparatedPhredRecords() {
        val records = FastaQual.parse(
            "\uFEFF>trace-a first read\n" +
                "40 38 12\n" +
                "  30\n" +
                ">trace-b\n" +
                "5 6 7\n",
        )

        assertEquals(listOf("trace-a", "trace-b"), records.map { it.name })
        assertEquals(listOf(40, 38, 12, 30), records.first().scores)
        assertEquals("first read", records.first().description)
    }

    @Test
    fun rejectsNegativeAndNonNumericScores() {
        assertFailsWith<SeqIOException> { FastaQual.parse(">read\n40 -1\n") }
        assertFailsWith<SeqIOException> { FastaQual.parse(">read\n40 high\n") }
    }
}
