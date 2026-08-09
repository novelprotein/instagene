package org.instagene.core.io

import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        assertEquals(2, seq.features.count { it.name == "split" || it.notes.contains("split") || it.name == "split" })
        // join produces two features sharing a label/note
        assertTrue(seq.features.size >= 4)
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
