package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SiteDomesticationTest {

    @Test
    fun findsInternalSites() {
        val codingSeq = Seq(
            name = "gene",
            bases = "ATGGCTGGTCTCATGGCTGGTCTCATGGCTA",
            kind = SeqKind.DNA,
        )
        val sites = SiteDomestication.findInternalSites(codingSeq)
        assertTrue(sites.isNotEmpty(), "Should find BsaI sites")
    }

    @Test
    fun reportsNoSitesWhenAbsent() {
        val cleanSeq = Seq(name = "clean", bases = "ATGATGATGATGATG", kind = SeqKind.DNA)
        val sites = SiteDomestication.findInternalSites(cleanSeq)
        assertEquals(0, sites.size)
    }

    @Test
    fun suggestsBestEnzyme() {
        val seq = Seq(name = "gene", bases = "ATGGCTGGTCTCATGGCTGGTCTCATGGCTA", kind = SeqKind.DNA)
        val (enzyme, count) = SiteDomestication.suggestEnzyme(seq)
        assertNotNull(enzyme)
        assertTrue(count >= 0, "Site count should be non-negative")
    }

    @Test
    fun appliesSilentMutations() {
        val seq = Seq(
            name = "gene",
            bases = "ATG" + "TGT" + "GGTCTC" + "TGT" + "TGT" + "TGT" + "TAA",
            kind = SeqKind.DNA,
        )
        val result = SiteDomestication.domesticate(seq, listOf(Enzyme("BsaI", "GGTCTC", 1, 5)))
        val sitesAfter = Digest.countSites(result.domesticated, Enzyme("BsaI", "GGTCTC", 1, 5))
        assertEquals(0, sitesAfter, "All BsaI sites should be removed")
    }

    @Test
    fun goldenGateEnzymeListIsNotEmpty() {
        assertTrue(SiteDomestication.GOLDEN_GATE_ENZYMES.isNotEmpty())
    }
}
