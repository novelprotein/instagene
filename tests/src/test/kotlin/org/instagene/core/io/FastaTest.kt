package org.instagene.core.io

import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastaTest {

    @Test
    fun parseMultiRecordAndBareSequence() {
        val text = """
            >one first record
            ACGT
            >two
            TTAA
        """.trimIndent()
        val all = Fasta.parseAll(text)
        assertEquals(2, all.size)
        assertEquals("one", all[0].name)
        assertEquals("first record", all[0].description)
        assertEquals("ACGT", all[0].bases)
        assertEquals("two", all[1].name)

        val bare = Fasta.parse("acgt acgt")
        assertEquals("ACGTACGT", bare.bases)
        assertEquals("sequence", bare.name)
    }

    @Test
    fun semicolonHeaderAndCircularDescription() {
        val seq = Fasta.parse(";plasmid circular molecule\nATGC")
        assertEquals("plasmid", seq.name)
        assertEquals(Topology.CIRCULAR, seq.topology)
    }

    @Test
    fun detectKind() {
        assertEquals(SeqKind.DNA, Fasta.detectKind(""))
        assertEquals(SeqKind.DNA, Fasta.detectKind("ACGT"))
        assertEquals(SeqKind.RNA, Fasta.detectKind("ACGU"))
        assertEquals(SeqKind.PROTEIN, Fasta.detectKind("MEEKLF"))
    }

    @Test
    fun emptyInput() {
        assertFailsWith<IllegalArgumentException> { Fasta.parse("") }
        assertTrue(Fasta.parseAll("").isEmpty())
    }

    @Test
    fun writeRoundTrip() {
        val seq = Seq("demo", "A".repeat(65), SeqKind.DNA, Topology.CIRCULAR, description = "note")
        val fasta = Fasta.write(seq)
        assertTrue(fasta.startsWith(">demo"))
        assertTrue(fasta.contains("circular"))
        val lines = fasta.trim().lines()
        assertEquals(Fasta.LINE_WIDTH, lines[1].length)
        val parsed = Fasta.parse(fasta)
        assertEquals(seq.bases, parsed.bases)
        assertEquals(seq.name, parsed.name)
    }

    @Test
    fun writeAllConcatenates() {
        val out = Fasta.writeAll(listOf(Seq("a", "AA"), Seq("b", "TT")))
        assertEquals(2, Fasta.parseAll(out).size)
    }
}
