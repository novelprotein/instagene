package org.instagene.core

import org.instagene.core.io.SeqIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeqOpsTest {

    @Test
    fun transcribeAndBackTranscribe() {
        val dna = Seq(bases = "AaTtGgCc", kind = SeqKind.DNA)
        val rna = SeqOps.transcribe(dna)
        assertEquals(SeqKind.RNA, rna.kind)
        assertEquals("AaUuGgCc", rna.bases)
        val back = SeqOps.backTranscribe(rna)
        assertEquals(SeqKind.DNA, back.kind)
        assertEquals("AaTtGgCc", back.bases)
        assertEquals(dna.bases, SeqOps.backTranscribe(dna).bases)
    }

    @Test
    fun translateFramesAndStops() {
        val seq = Seq(bases = "ATGGCCTAATAG") // M A * *
        assertEquals("MA**", SeqOps.translateBases(seq.bases))
        assertEquals("MA", SeqOps.translateBases(seq.bases, stopAtFirstStop = true))
        assertEquals("WP", SeqOps.translateBases(seq.bases, frame = 1).take(2)) // frame 1: TGG CCT AAT -> W P N
        assertFailsWith<IllegalArgumentException> { SeqOps.translate(seq, frame = 3) }
        // Trailing partial codon ignored
        assertEquals("M", SeqOps.translateBases("ATGAA"))
    }

    @Test
    fun gcContentAndBaseCounts() {
        assertEquals(0.0, SeqOps.gcContent(""))
        assertEquals(50.0, SeqOps.gcContent("ATGC"))
        assertEquals(100.0, SeqOps.gcContent("SS"))
        assertEquals(0.0, SeqOps.gcContent("NN"))
        assertEquals(SeqOps.baseCounts("aAt"), mapOf('A' to 2, 'T' to 1))
    }

    @Test
    fun meltingTempWallaceAndSalt() {
        assertEquals(0.0, SeqOps.meltingTemp(""))
        // Wallace: 2*AT + 4*GC for short oligos
        assertEquals(30.0, SeqOps.meltingTemp("AAAAACCCCC")) // 5 A + 5 C = 2*5 + 4*5 = 30
        val long = "ACGTACGTACGTAC" // 14 nt -> salt formula
        val tmDefault = SeqOps.meltingTemp(long)
        val tmHighSalt = SeqOps.meltingTemp(long, saltMolar = 0.5)
        assertTrue(tmHighSalt > tmDefault)
    }

    @Test
    fun molecularWeightEmptyIsZero() {
        assertEquals(0.0, SeqOps.molecularWeightDaltons(Seq(bases = "")))
        val dna = SeqOps.molecularWeightDaltons(Seq(bases = "AT", kind = SeqKind.DNA))
        val rna = SeqOps.molecularWeightDaltons(Seq(bases = "AU", kind = SeqKind.RNA))
        assertTrue(dna > 0)
        assertTrue(rna > 0)
        assertTrue(dna != rna)
    }

    @Test
    fun findOrfsClassicAndMinLength() {
        // ATG + AAA + TAA = M K *
        val seq = Seq(bases = "AAATGAAATAA")
        val orfs = SeqOps.findOrfs(seq, minAminoAcids = 1)
        assertTrue(orfs.any { it.protein.startsWith("MK") })
        assertTrue(SeqOps.findOrfs(seq, minAminoAcids = 50).isEmpty())
    }

    @Test
    fun findExactIupacAndBothStrands() {
        val seq = Seq(bases = "NNNGAATTCNNNCTTAAGNNN")
        val fwd = SeqOps.find(seq, "GAATTC")
        assertEquals(listOf(3 to Strand.FORWARD), fwd)

        val iupac = SeqOps.find(seq, "GAATTY")
        assertEquals(1, iupac.size)

        // Non-palindromic pattern: reverse-strand hits are reported at their forward coordinates.
        val nonPalindromic = Seq(bases = "XXCATGCXXGCATGXX")
        val both = SeqOps.find(nonPalindromic, "CATGC", bothStrands = true)
        assertTrue(both.any { it.second == Strand.FORWARD })
        assertTrue(both.any { it.second == Strand.REVERSE })

        // A palindromic pattern is its own reverse complement; every forward hit is
        // the same physical site, so it must not be double-reported as REVERSE.
        val palindrome = SeqOps.find(seq, "GAATTC", bothStrands = true)
        assertTrue(palindrome.none { it.second == Strand.REVERSE })

        assertTrue(SeqOps.find(seq, "").isEmpty())
        assertTrue(SeqOps.find(Seq(bases = ""), "A").isEmpty())
    }

    @Test
    fun findCircularWrap() {
        val seq = Seq(bases = "TTCAAAG", topology = Topology.CIRCULAR) // wraps: AAG + TTC = AAGTTC?
        // Site GAATTC wrapping: last bases + first. Use EcoRI spanning origin: ...G | AATTC...
        val wrap = Seq(bases = "AATTCXXXG", topology = Topology.CIRCULAR)
        val hits = SeqOps.find(wrap, "GAATTC")
        assertEquals(1, hits.size)
        assertEquals(8, hits.single().first) // recognition starts at G
    }

    @Test
    fun designPrimers() {
        val seq = SeqIO.Samples.GFP_CDS
        val (fwd, rev) = SeqOps.designPrimers(seq, 0, 100, targetTm = 60.0)
        assertTrue(fwd.bases.length in 18..30)
        assertTrue(rev.bases.length in 18..30)
        assertTrue(fwd.name.endsWith("_F"))
        assertTrue(rev.name.endsWith("_R"))
        assertFailsWith<IllegalArgumentException> { SeqOps.designPrimers(seq, 10, 10) }
    }

    @Test
    fun designPrimersReversePrimerNeverReachesUpstreamOfAmpliconStart() {
        // A short amplicon whose start is not at 0: the reverse primer must anneal
        // inside `[start, end)`, never extend before `start`.
        val seq = Seq(name = "short", bases = "CGTACCCCCCCCCCCCCCCCCCGTAC")
        val (_, rev) = SeqOps.designPrimers(seq, 3, 12, targetTm = 60.0)
        assertTrue(rev.bases.length in 9..12)
        // rev.bases is the reverse-complement of bases `start..start+len`, so its
        // forward-strand coordinates must stay >= start.
        val revLen = rev.bases.length
        assertTrue(revLen <= 12 - 3)
    }

    @Test
    fun codonUsageCountsByFrame() {
        val usage = SeqOps.codonUsage("ATGATG", frame = 0)
        assertEquals(2, usage["ATG"])
    }
}
