package org.instagene.core

import org.instagene.core.io.Gff3
import org.instagene.core.io.FileSniffer
import org.instagene.core.io.FileType
import org.instagene.core.io.SeqIO
import org.instagene.core.io.SeqFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreWorkflowTest {
    @Test
    fun advancedSearchSupportsMismatchesAndThreePrimeConstraint() {
        val seq = Seq(bases = "AAGGATCCGATT")
        val relaxed = AdvancedSearch.find(seq, SearchRequest("GGATCT", maxMismatches = 1, bothStrands = false))
        assertEquals(1, relaxed.size)
        val strict = AdvancedSearch.find(seq, SearchRequest("GGATCT", maxMismatches = 1, threePrimeExact = 3, bothStrands = false))
        assertEquals(0, strict.size)
    }

    @Test
    fun alignmentReportsMismatchAndGap() {
        val result = Alignment.align(Seq(name = "ref", bases = "ACGT"), listOf(Seq(name = "read", bases = "AGT")))
        assertEquals("ACGT", result.reference.sequence)
        assertEquals(1, result.queries.single().gaps)
        assertTrue(result.discrepancyPositions().isNotEmpty())
    }

    @Test
    fun gff3RoundTripKeepsAnnotationAndSequence() {
        val original = Seq(
            name = "construct",
            bases = "ACGTACGT",
            features = listOf(Feature("promoter", "promoter", 1, 5, color = "#112233")),
        )
        val parsed = SeqIO.parse(Gff3.write(original))
        assertEquals(original.bases, parsed.bases)
        assertEquals("promoter", parsed.features.single().name)
        assertEquals(SeqFormat.GFF3, SeqIO.detectFormat(Gff3.write(original)))
    }

    @Test
    fun virtualGelGroupsEqualSizedFragments() {
        val seq = Seq(name = "circular", bases = "GAATTCGAATTC", topology = Topology.CIRCULAR)
        val gel = VirtualGel.run(listOf(GelLane.Dna("EcoRI", seq, listOf(Enzymes.require("EcoRI")))))
        assertEquals(1, gel.lanes.single().bands.size)
        assertEquals(2.0, gel.lanes.single().bands.single().relativeIntensity)
    }

    @Test
    fun reactionAndDilutionCalculationsAreConsistent() {
        val dilution = MolecularCalculators.dilution(100.0, 10.0, 100.0)
        assertEquals(10.0, dilution.stockVolumeUl)
        assertEquals(90.0, dilution.diluentVolumeUl)
        val mix = MolecularCalculators.masterMix(listOf(MasterMixComponent("buffer", 2.0)), 5, 0.1)
        assertEquals(11.0, mix.totalVolumeUl)
    }

    @Test
    fun featureLibraryAnnotatesIupacPatterns() {
        val seq = Seq(name = "x", bases = "AAAAGGATCCAAAA")
        val annotated = FeatureLibrary.annotate(seq, listOf(FeatureDefinition("tag", "GGATCN", "misc_feature")))
        assertEquals(1, annotated.features.size)
        assertEquals(4, annotated.features.single().start)
    }

    @Test
    fun identityAndBlastUrlAreStableAndExplicit() {
        val seq = Seq(name = "x", bases = "ACGT")
        assertTrue(SequenceIdentity.cdseguid(seq).startsWith("cdseguid-"))
        assertTrue(NcbiClient().blastUrl(seq).toString().contains("PROGRAM=blastn"))
    }

    @Test
    fun scfChromatogramsExposeCalledBasesAndQuality() {
        val bytes = ByteArray(128 + 24)
        ".scf".encodeToByteArray().copyInto(bytes)
        fun putInt(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        putInt(12, 2)
        putInt(16, 128)
        bytes[128 + 4] = 10
        bytes[128 + 8] = 'A'.code.toByte()
        bytes[140 + 7] = 80
        bytes[140 + 8] = 'T'.code.toByte()
        assertEquals("AT", ChromatogramReader.readScf(bytes, "read.scf").bases)
        assertEquals(FileType.CHROMATOGRAM, FileSniffer.typeOf(bytes))
    }

    @Test
    fun recombinationReplacesTheTargetIntervalUsingMatchingArms() {
        val target = Seq("target", "AAAACCCCGGGGTTTT")
        val donor = Seq("donor", "CCCCAAAA" + "GGGG")
        val candidates = Recombination.candidates(target, donor, armLength = 4)
        assertTrue(candidates.isNotEmpty())
        val product = Recombination.recombine(target, donor, candidates.single(), "edited").product
        assertEquals("AAAACCCCAAAAGGGGTTTT", product.bases)
    }

    @Test
    fun goldenGateRequiresCompatibleJunctions() {
        val parts = listOf(Seq(name = "a", bases = "AAAA"), Seq(name = "b", bases = "CCCC"))
        val product = AssemblyWorkflows.goldenGate(parts, listOf("A", "B", "A"), circular = true)
        assertEquals("AAAACCCC", product.product.bases)
        assertTrue(product.product.isCircular)
    }
}
