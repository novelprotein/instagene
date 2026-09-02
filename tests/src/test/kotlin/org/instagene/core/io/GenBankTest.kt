package org.instagene.core.io

import org.instagene.core.Feature
import org.instagene.core.LocationBoundary
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenBankTest {

    private val minimalGb = """
        LOCUS       mini                     12 bp    DNA     circular
        DEFINITION  tiny test record
        FEATURES             Location/Qualifiers
             CDS             1..6
                             /gene="alpha"
             misc_feature    complement(7..12)
                             /label="tail"
             misc_feature    join(1..3,10..12)
                             /note="split"
        ORIGIN
                1 atgcatgcat gc
        //
    """.trimIndent()

    @Test
    fun looksLikeGenBank() {
        assertTrue(GenBank.looksLikeGenBank(minimalGb))
        assertFalse(GenBank.looksLikeGenBank(">fasta\nACGT"))
        assertFalse(GenBank.looksLikeGenBank("ACGTACGT"))
    }

    @Test
    fun parseLocusFeaturesAndTopology() {
        val seq = GenBank.parse(minimalGb)
        assertEquals("mini", seq.name)
        assertEquals(12, seq.length)
        assertEquals(Topology.CIRCULAR, seq.topology)
        assertEquals(SeqKind.DNA, seq.kind)
        assertEquals("ATGCATGCATGC", seq.bases)

        val alpha = seq.features.first { it.name == "alpha" }
        assertEquals(0, alpha.start)
        assertEquals(6, alpha.end)

        val tail = seq.features.first { it.name == "tail" }
        assertEquals(Strand.REVERSE, tail.strand)
        assertEquals(6, tail.start)
        assertEquals(12, tail.end)

        assertEquals(2, seq.features.count { it.name == "split" || it.notes.contains("split") })
        // join produces two features sharing a label/note
        assertTrue(seq.features.size >= 4)
    }

    @Test
    fun parseAcceptsBomAndLeadingWhitespaceBeforeLocus() {
        val gb = "\uFEFF  $minimalGb"

        val seq = GenBank.parse(gb)

        assertEquals("mini", seq.name)
        assertEquals("ATGCATGCATGC", seq.bases)
    }

    @Test
    fun parseAcceptsFuzzyLocationBounds() {
        val gb = """
            LOCUS       fuzzy                    6 bp    DNA     linear
            FEATURES             Location/Qualifiers
                 CDS             <1..>6
                                 /gene="fuzzy"
            ORIGIN
                     1 atgcat
            //
        """.trimIndent()

        val feature = GenBank.parse(gb).features.single()

        assertEquals(0, feature.start)
        assertEquals(6, feature.end)
        assertEquals("fuzzy", feature.name)
    }

    @Test
    fun parseContigRecordWithoutOriginPreservesFeatures() {
        val gb = """
            LOCUS       contig                  12 bp    DNA     linear   CON
            DEFINITION  assembled scaffold.
            FEATURES             Location/Qualifiers
                 source          1..12
                                 /organism="synthetic construct"
                 CDS             2..8
                                 /gene="kept"
            CONTIG      join(ABC123.1:1..12)
            //
        """.trimIndent()

        val seq = GenBank.parse(gb)

        assertEquals("contig", seq.name)
        assertEquals("", seq.bases)
        assertEquals("join(ABC123.1:1..12)", seq.metadata["CONTIG"])
        assertEquals(listOf("kept"), seq.features.map { it.name })
        assertEquals(1, seq.features.single { it.name == "kept" }.start)
        assertEquals(8, seq.features.single { it.name == "kept" }.end)
    }

    @Test
    fun parseFailsClearlyForTruncatedGenBank() {
        val gb = """
            LOCUS       truncated                4 bp    DNA     linear
            ORIGIN
                    1 atgc
        """.trimIndent()

        val error = assertFailsWith<SeqIOException> { GenBank.parse(gb) }

        assertTrue(error.message.orEmpty().contains("terminator"))
    }

    @Test
    fun writeRoundTripPreservesBasesAndFeatures() {
        val original = Seq(
            name = "round",
            bases = "ATGCATGCATGC",
            kind = SeqKind.DNA,
            topology = Topology.CIRCULAR,
            features = listOf(
                Feature("prom", "promoter", 0, 4, Strand.FORWARD, "note1"),
                Feature("cds", "CDS", 4, 12, Strand.REVERSE),
            ),
            description = "round trip",
        )
        val text = GenBank.write(original)
        assertTrue(text.contains("LOCUS"))
        assertTrue(text.trim().endsWith("//") || text.contains("//"))
        val parsed = GenBank.parse(text)
        assertEquals(original.bases, parsed.bases)
        assertEquals(Topology.CIRCULAR, parsed.topology)
        assertTrue(parsed.features.any { it.name == "prom" })
        assertTrue(parsed.features.any { it.name == "cds" && it.strand == Strand.REVERSE })
    }

    @Test
    fun roundTripPreservesFeatureQualifiersAndRecordMetadata() {
        val original = Seq(
            name = "annotated",
            bases = "ATGCATGC",
            features = listOf(
                Feature("gene", "CDS", 0, 8, qualifiers = mapOf("gene" to listOf("gene"), "note" to listOf("first", "second"))),
            ),
            metadata = mapOf("ACCESSION" to "ABC123", "COMMENT" to "kept"),
        )
        val parsed = GenBank.parse(GenBank.write(original))
        assertEquals("ABC123", parsed.metadata["ACCESSION"])
        assertEquals("kept", parsed.metadata["COMMENT"])
        assertEquals(listOf("first", "second"), parsed.features.single().qualifiers["note"])
    }

    @Test
    fun roundTripKeepsSourceAndOrganismAsSeparateMetadataFields() {
        val original = Seq(
            name = "metadata",
            bases = "ACGT",
            metadata = mapOf("SOURCE" to "human", "ORGANISM" to "Homo sapiens"),
        )

        val parsed = GenBank.parse(GenBank.write(original))

        assertEquals("human", parsed.metadata["SOURCE"])
        assertEquals("Homo sapiens", parsed.metadata["ORGANISM"])
    }

    @Test
    fun nestedLocationsKeepOperatorsFuzzyBoundsAndSegmentStrands() {
        val gb = """
            LOCUS       nested                    12 bp    DNA     linear
            FEATURES             Location/Qualifiers
                 CDS             complement(join(<1..3,8^9,>10..12))
                                 /gene="nested"
            ORIGIN
                     1 atgcatgcatgc
            //
        """.trimIndent()

        val parsed = GenBank.parse(gb)
        assertEquals(3, parsed.features.size)
        assertTrue(parsed.features.all { it.strand == Strand.REVERSE })
        assertEquals(
            LocationBoundary.LESS_THAN,
            parsed.features.first { it.start == 0 }.locationMetadata?.node?.children?.first()?.segment?.startBoundary,
        )
        val exported = GenBank.write(parsed)
        assertTrue(exported.contains("complement(join(<1..3,8^9,>10..12))"))
    }

    @Test
    fun repeatedHeaderFieldsBecomeStructuredRecordMetadata() {
        val gb = """
            LOCUS       refs                       4 bp    DNA     linear
            DEFINITION  referenced sequence
            ACCESSION   REF001
            SOURCE      synthetic construct
              ORGANISM  Example organism
                        Bacteria; Example.
            COMMENT     first comment
                        continued comment
            REFERENCE   1
              AUTHORS   Doe J.
              TITLE     A useful title
              JOURNAL   Journal of Tests 1:1-2 (2026)
              PUBMED    12345
            DBLINK      BioProject: PRJ001; BioSample: SAM001
            FEATURES             Location/Qualifiers
            ORIGIN
                     1 acgt
            //
        """.trimIndent()

        val metadata = GenBank.parse(gb).recordMetadata
        assertEquals("synthetic construct", metadata.source)
        assertEquals("Example organism", metadata.organism)
        assertEquals(listOf("Bacteria; Example."), metadata.taxonomy)
        assertEquals(listOf("first comment continued comment"), metadata.comments)
        assertEquals("Doe J.", metadata.references.single().authors)
        assertEquals("12345", metadata.references.single().pubMed)
        assertEquals(listOf("BioProject: PRJ001", "BioSample: SAM001"), metadata.databaseReferences)
    }

    @Test
    fun locusDivisionAndDateArePreservedWhenPresent() {
        val gb = """
            LOCUS       source                   4 bp    DNA     linear   CON 02-FEB-2026
            DEFINITION  referenced sequence
            ORIGIN
                     1 acgt
            //
        """.trimIndent()

        val parsed = GenBank.parse(gb)
        assertEquals("CON", parsed.recordMetadata.locusDivision)
        assertTrue(parsed.recordMetadata.modifiedAt != null, parsed.recordMetadata.toString())
        val exportedLocus = GenBank.write(parsed).lineSequence().first()
        assertTrue(exportedLocus.contains("CON 02-FEB-2026"), exportedLocus)
    }

    @Test
    fun newRecordsDoNotReceiveSourceOrSyntheticDefaults() {
        val text = GenBank.write(Seq(name = "new-record", bases = "ACGT"))
        val locus = text.lineSequence().first()

        assertFalse(text.contains("InstaGene"))
        assertFalse(text.contains("synthetic construct"))
        assertFalse(locus.contains(" SYN "))
        assertFalse(locus.contains("01-JAN-1980"))

        val parsed = GenBank.parse(text)
        assertEquals(null, parsed.recordMetadata.source)
        assertEquals(null, parsed.recordMetadata.organism)
        assertEquals(null, parsed.recordMetadata.locusDivision)
        assertEquals(null, parsed.recordMetadata.modifiedAt)
    }

    @Test
    fun featuresBeyondLengthAreClipped() {
        val gb = """
            LOCUS       short                     6 bp    DNA     linear
            FEATURES             Location/Qualifiers
                 CDS             1..6
                                 /gene="ok"
                 CDS             5..20
                                 /gene="overflow"
            ORIGIN
                     1 atgcat
            //
        """.trimIndent()
        val seq = GenBank.parse(gb)
        val ok = seq.features.first { it.name == "ok" }
        assertEquals(0, ok.start)
        assertEquals(6, ok.end)
        val overflow = seq.features.first { it.name == "overflow" }
        assertEquals(4, overflow.start)
        assertEquals(6, overflow.end)
    }

    @Test
    fun featureStartingBeyondLengthIsDropped() {
        val gb = """
            LOCUS       short                     6 bp    DNA     linear
            FEATURES             Location/Qualifiers
                 CDS             8..12
                                 /gene="gone"
            ORIGIN
                     1 atgcat
            //
        """.trimIndent()
        assertEquals(emptyList(), GenBank.parse(gb).features)
    }
}
