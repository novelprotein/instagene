package org.instagene.core.io

import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeqIOTest {

    @Test
    fun formatOfByExtension() {
        assertEquals(SeqFormat.GENBANK, SeqIO.formatOf(File("x.gb")))
        assertEquals(SeqFormat.GENBANK, SeqIO.formatOf(File("x.gbk")))
        assertEquals(SeqFormat.FASTA, SeqIO.formatOf(File("x.fa")))
        assertEquals(SeqFormat.FASTA, SeqIO.formatOf(File("x.unknown")))
    }

    @Test
    fun detectAndParseFormats() {
        assertEquals(SeqFormat.GENBANK, SeqIO.detectFormat("LOCUS       x\nORIGIN\n//"))
        assertEquals(SeqFormat.FASTA, SeqIO.detectFormat(">x\nACGT"))
        assertEquals("ACGT", SeqIO.parse("acgt").bases)
        assertEquals("named", SeqIO.parse(">named\nACGT").name)
    }

    @Test
    fun parseAllMultiFasta() {
        val all = SeqIO.parseAll(">a\nAA\n>b\nTT")
        assertEquals(2, all.size)
    }

    @Test
    fun rawSequence() {
        val seq = SeqIO.rawSequence("a c g t 12")
        assertEquals("ACGT", seq.bases)
        assertEquals(Topology.LINEAR, seq.topology)
    }

    @Test
    fun fileRoundTrip() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val fa = File(dir, "s.fasta")
            val gb = File(dir, "s.gb")
            val seq = Seq("s", "ATGCATGC", SeqKind.DNA, Topology.LINEAR)
            SeqIO.write(fa, seq)
            SeqIO.write(gb, seq, SeqFormat.GENBANK)
            assertEquals(seq.bases, SeqIO.read(fa).bases)
            assertEquals(seq.bases, SeqIO.read(gb).bases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun samplesAreNonEmptyAndNamed() {
        assertTrue(SeqIO.Samples.PUC19_MCS.length > 20)
        assertEquals("pUC19_MCS", SeqIO.Samples.PUC19_MCS.name)
        assertTrue(SeqIO.Samples.GFP_CDS.length > 100)
        assertEquals("GFP_CDS", SeqIO.Samples.GFP_CDS.name)
        assertEquals(2, SeqIO.Samples.ALL.size)
        assertTrue(SeqIO.Samples.PUC19_MCS.bases.startsWith("GAATTC"))
        assertTrue(SeqIO.Samples.PUC19_MCS.bases.endsWith("AAGCTT"))
    }

    @Test
    fun extensionHelpers() {
        val seq = Seq("x", "ACGT")
        assertTrue(seq.toFasta().startsWith(">x"))
        assertTrue(seq.toGenBank().contains("LOCUS"))
        assertTrue(seq.isNucleotide())
        assertTrue(!Seq("p", "MEEK", SeqKind.PROTEIN).isNucleotide())
    }
}
