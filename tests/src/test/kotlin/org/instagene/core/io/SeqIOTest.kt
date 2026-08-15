package org.instagene.core.io

import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun annotatedCircularGenBankFileRoundTripPreservesEverything() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val original = Seq(
                name = "pMini",
                bases = "ATGCATGCATGCATGC",
                kind = SeqKind.DNA,
                topology = Topology.CIRCULAR,
                features = listOf(
                    Feature("oris", "rep_origin", 0, 4, org.instagene.core.Strand.FORWARD, ""),
                    Feature("ampR", "CDS", 4, 16, org.instagene.core.Strand.REVERSE, "beta-lactamase"),
                    Feature("MCS", "misc_feature", 12, 16),
                ),
                description = "mini plasmid with a map",
            )
            val file = File(dir, "pMini.gb")
            SeqIO.write(file, original)
            val reloaded = SeqIO.read(file)
            assertEquals(original.name, reloaded.name)
            assertEquals(original.description, reloaded.description)
            assertEquals(original.bases, reloaded.bases)
            assertEquals(Topology.CIRCULAR, reloaded.topology)
            assertEquals(original.features.map { it.copy(qualifiers = mapOf("label" to listOf(it.name)).let { qualifiers ->
                if (it.notes.isBlank()) qualifiers else qualifiers + ("note" to listOf(it.notes))
            }) }, reloaded.features)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preferredSaveFormatKeepsAnnotatedAndCircularDocumentsInGenBank() {
        assertEquals(SeqFormat.FASTA, SeqIO.preferredSaveFormat(Seq("plain", "ACGT")))
        assertEquals(SeqFormat.GENBANK, SeqIO.preferredSaveFormat(Seq("plasmid", "ACGT", topology = Topology.CIRCULAR)))
        assertEquals(
            SeqFormat.GENBANK,
            SeqIO.preferredSaveFormat(Seq("anno", "ACGTACGT", features = listOf(Feature("f", "misc_feature", 0, 4))))
        )
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
    fun readReturnsOnlyTheFirstRecordOfAMultiContigGenome() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val genome = File(dir, "genome.fna")
            genome.writeText(
                (1..3).joinToString("") { i ->
                    ">chr$i synthetic contig $i\n" + "ACGT".repeat(250) + "\n"
                }
            )
            val seq = SeqIO.read(genome)
            assertEquals("chr1", seq.name)
            assertEquals(1000, seq.length)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readStreamsGenBankFromFile() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val gb = File(dir, "genome.gb")
            gb.writeText(
                """
                LOCUS       chr1                1000 bp    DNA     linear
                DEFINITION  synthetic chromosome 1
                FEATURES             Location/Qualifiers
                     source          1..1000
                                     /mol_type="genomic DNA"
                ORIGIN
                    1 gattaca
                //
                """.trimIndent()
            )
            val seq = SeqIO.read(gb)
            assertEquals("chr1", seq.name)
            assertEquals(7, seq.length)
            assertEquals("GATTACA", seq.bases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readGenBankFileWithBom() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val gb = File(dir, "bom.gbk")
            gb.writeText(
                "\uFEFF" + """
                LOCUS       bom                      4 bp    DNA     linear
                ORIGIN
                        1 atgc
                //
                """.trimIndent()
            )

            val seq = SeqIO.read(gb)

            assertEquals("bom", seq.name)
            assertEquals("ATGC", seq.bases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun genBankExtensionDoesNotFallBackToFastaForMalformedContent() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val gb = File(dir, "bad.gb")
            gb.writeText("not a genbank record\nACGT\n")

            val error = assertFailsWith<SeqIOException> { SeqIO.read(gb) }

            assertTrue(error.message.orEmpty().contains("LOCUS"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readAllParsesMultipleGenBankRecords() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val file = File(dir, "records.gb")
            file.writeText(
                """
                LOCUS       first                    4 bp    DNA     linear
                ORIGIN
                        1 atgc
                //
                LOCUS       second                   4 bp    DNA     linear
                ORIGIN
                        1 gcat
                //
                """.trimIndent()
            )
            assertEquals(listOf("first", "second"), SeqIO.readAll(file).map { it.name })
        } finally {
            dir.deleteRecursively()
        }
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
