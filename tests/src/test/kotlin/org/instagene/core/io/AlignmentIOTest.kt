package org.instagene.core.io

import org.instagene.core.Seq
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlignmentIOTest {

    private val rows = listOf(
        Seq("reference", "AC-GT"),
        Seq("sample", "ATAGT"),
    )

    @Test
    fun allInterchangeFormatsRoundTripNamesGapsAndColumns() {
        AlignmentFormat.entries.forEach { format ->
            val encoded = AlignmentIO.write(rows, format)
            assertEquals(format, AlignmentIO.detectFormat(encoded), "format detection for $format")
            val decoded = AlignmentIO.parse(encoded)
            assertEquals(rows.map(Seq::name), decoded.map(Seq::name), "names for $format")
            assertEquals(rows.map(Seq::bases), decoded.map(Seq::bases), "bases for $format")
        }
    }

    @Test
    fun parsersAcceptCommonBlockedAndInterleavedFixtures() {
        val clustal = """
            CLUSTAL W multiple sequence alignment

            reference  AC-
            sample     ATA
                       ** 

            reference  GT
            sample     GT
                       **
        """.trimIndent()
        val stockholm = """
            # STOCKHOLM 1.0
            #=GF ID example
            reference AC-
            sample ATA

            reference GT
            sample GT
            //
        """.trimIndent()
        val phylip = """
            2 5
            reference AC-
            sample ATA

            GT
            GT
        """.trimIndent()

        assertEquals(rows.map(Seq::bases), AlignmentIO.parse(clustal).map(Seq::bases))
        assertEquals(rows.map(Seq::bases), AlignmentIO.parse(stockholm).map(Seq::bases))
        assertEquals(rows.map(Seq::bases), AlignmentIO.parse(phylip).map(Seq::bases))
    }

    @Test
    fun seqIoReadsEveryAlignmentExtensionAndWritesSelectedFormat() {
        val root = createTempDirectory("instagene-alignment-io").toFile()
        try {
            val cases = listOf(
                AlignmentFormat.FASTA to "afa",
                AlignmentFormat.CLUSTAL to "aln",
                AlignmentFormat.STOCKHOLM to "sto",
                AlignmentFormat.PHYLIP to "phy",
            )
            cases.forEach { (format, extension) ->
                val file = File(root, "rows.$extension").apply { writeText(AlignmentIO.write(rows, format)) }
                assertEquals(rows.map(Seq::bases), SeqIO.readAll(file).map(Seq::bases), "read $extension")
            }
            assertTrue(SeqIO.writeAll(rows, SeqFormat.ALIGNMENT_STOCKHOLM).startsWith("# STOCKHOLM"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unequalAlignmentRowsAreRejectedInsteadOfSilentlyImported() {
        val error = assertFailsWith<SeqIOException> {
            AlignmentIO.parse(">short\nACG\n>long\nACGT\n", format = AlignmentFormat.FASTA)
        }
        assertTrue(error.message.orEmpty().contains("different lengths"))
    }
}
