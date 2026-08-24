package org.instagene.core.io

import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Topology
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression coverage for every openly documented native interchange family. */
class InteroperabilityRoundTripTest {

    private val annotatedDna = Seq(
        name = "pInterop",
        bases = "ATGCGTACGTAGCTAGCTAA",
        topology = Topology.CIRCULAR,
        description = "synthetic interoperability fixture",
        metadata = mapOf("ACCESSION" to "IG000001", "ORGANISM" to "synthetic construct", "COMMENT" to "local-only"),
        features = listOf(
            Feature("promoter", "promoter", 0, 6, Strand.FORWARD, "drives expression", mapOf("note" to listOf("drives expression"))),
            Feature("marker", "CDS", 6, 18, Strand.REVERSE, "synthetic marker"),
        ),
    )

    @Test
    fun genbankAndApeRoundTripAnnotatedCircularConstructs() {
        val root = createTempDirectory("instagene-interop-genbank").toFile()
        try {
            listOf("gb", "ape").forEach { extension ->
                val file = File(root, "pInterop.$extension")
                SeqIO.write(file, annotatedDna)
                val decoded = SeqIO.read(file)
                assertEquals(SeqFormat.GENBANK, SeqIO.formatOf(file))
                assertEquals(annotatedDna.bases, decoded.bases)
                assertEquals(Topology.CIRCULAR, decoded.topology)
                assertEquals(annotatedDna.features.map { it.name to it.type to it.strand }, decoded.features.map { it.name to it.type to it.strand })
                assertEquals("IG000001", decoded.metadata["ACCESSION"])
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emblAndSwissProtPreserveTheirSupportedSequenceSemantics() {
        val root = createTempDirectory("instagene-interop-embl").toFile()
        try {
            val embl = File(root, "construct.embl")
            SeqIO.write(embl, annotatedDna, SeqFormat.EMBL)
            val emblDecoded = SeqIO.read(embl)
            assertEquals(annotatedDna.bases, emblDecoded.bases)
            assertEquals(Topology.CIRCULAR, emblDecoded.topology)
            assertEquals(annotatedDna.features.map(Feature::name), emblDecoded.features.map(Feature::name))
            assertEquals("IG000001", emblDecoded.metadata["ACCESSION"])

            val protein = Seq("protein_fixture", "MTEYKLVVVG", SeqKind.PROTEIN, description = "synthetic protein")
            val swiss = File(root, "protein.sprot")
            SeqIO.write(swiss, protein, SeqFormat.SWISS_PROT)
            val swissDecoded = SeqIO.read(swiss)
            assertEquals(SeqKind.PROTEIN, swissDecoded.kind)
            assertEquals(protein.bases, swissDecoded.bases)
            assertEquals("protein_fixture", swissDecoded.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gff3RoundTripPreservesSequenceAndVisibleFeatureCoordinates() {
        val root = createTempDirectory("instagene-interop-gff").toFile()
        try {
            val file = File(root, "construct.gff3")
            SeqIO.write(file, annotatedDna, SeqFormat.GFF3)
            val decoded = SeqIO.read(file)

            assertEquals(annotatedDna.bases, decoded.bases)
            assertEquals(
                annotatedDna.features.map { listOf(it.name, it.type, it.start, it.end, it.strand) },
                decoded.features.map { listOf(it.name, it.type, it.start, it.end, it.strand) },
            )
            assertTrue(SeqIO.write(decoded, SeqFormat.GFF3).contains("##gff-version 3"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun alignmentRowsRoundTripThroughEveryNativeAlignmentExtension() {
        val rows = listOf(Seq("reference", "AC-GTT"), Seq("sample", "ATAGTT"))
        val root = createTempDirectory("instagene-interop-alignment").toFile()
        try {
            listOf("afa", "aln", "sto", "phy").forEach { extension ->
                val file = File(root, "rows.$extension")
                file.writeText(SeqIO.writeAll(rows, SeqIO.formatOf(file)))
                assertEquals(rows.map(Seq::bases), SeqIO.readAll(file).map(Seq::bases), "round trip .$extension")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
