package org.instagene.core.io

import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.StringReader
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

    @Test
    fun stopAfterFirstRecordReturnsOnlyTheFirstRecord() {
        val text = ">one first\nACGT\n>two\nTTAA\n>three\nCCGG"
        val first = Fasta.parseAllFrom(StringReader(text), stopAfterFirstRecord = true)
        assertEquals(1, first.size)
        assertEquals("one", first[0].name)
        assertEquals("first", first[0].description)
        assertEquals("ACGT", first[0].bases)
    }

    @Test
    fun stopAfterFirstRecordHandlesHeaderlessAndSemicolonFirstLines() {
        // A headerless first record ends at the next header.
        val bare = Fasta.parseAllFrom(StringReader("ACGT\n>two\nTTAA"), stopAfterFirstRecord = true)
        assertEquals(1, bare.size)
        assertEquals("ACGT", bare[0].bases)
        // A leading ; header still counts as the first record, as in parseAll.
        val semi = Fasta.parseAllFrom(StringReader(";plasmid circular\nATGC\n>two\nTTAA"), stopAfterFirstRecord = true)
        assertEquals(1, semi.size)
        assertEquals("plasmid", semi[0].name)
        assertEquals(Topology.CIRCULAR, semi[0].topology)
    }

    @Test
    fun stopAfterFirstRecordWithNoSecondRecordReadsTheWholeFile() {
        val first = Fasta.parseAllFrom(StringReader(">only\nAACC"), stopAfterFirstRecord = true)
        assertEquals(1, first.size)
        assertEquals("AACC", first[0].bases)
    }

    @Test
    fun writeUsesAaUnitForProteins() {
        val seq = Seq("prot", "MKT", SeqKind.PROTEIN, Topology.LINEAR)
        assertTrue(Fasta.write(seq).startsWith(">prot 3 aa | linear"))
    }

    @Test
    fun writeOmitsBlankDescriptionFromHeader() {
        val seq = Seq("x", "ACGT", SeqKind.DNA, Topology.LINEAR, description = "")
        val text = Fasta.write(seq)
        assertTrue(text.startsWith(">x 4 bp | linear"))
        assertEquals("x", Fasta.parse(text).name)
    }
}
