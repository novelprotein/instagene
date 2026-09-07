package org.instagene.core

import kotlin.test.*

class DomesticationRegressionTest {
    @Test fun reverseStrandEditsPreserveProteinAndRemoveSites() {
        val sequence = Seq(bases = Alphabet.reverseComplement("ATGTGTGGTCTCTGTTGTTGTTAA"))
        val feature = Feature("reverse", "CDS", 0, sequence.length, strand = Strand.REVERSE)
        val result = SiteDomestication.domesticate(sequence, listOf(SiteDomestication.GOLDEN_GATE_ENZYMES.first()), listOf(feature))
        assertTrue(result.mutationsApplied > 0)
        assertTrue(result.unresolvedSites.isEmpty())
        assertEquals(FeatureTranslations.translate(sequence, feature).protein,
            FeatureTranslations.translate(result.domesticated, feature).protein)
    }

    @Test fun searchesLaterCodonsWhenFirstCodonHasNoSynonym() {
        val sequence = Seq(bases = "ATGTGGCTGTAA")
        val feature = Feature("cds", "CDS", 0, sequence.length)
        val result = SiteDomestication.domesticate(sequence, listOf(Enzyme("fixture", "TGGCTG", 0, 0)), listOf(feature))
        assertTrue(result.unresolvedSites.isEmpty())
        assertEquals("MWL*", FeatureTranslations.translate(result.domesticated, feature).protein)
    }

    @Test fun usesFeatureGeneticCodeForSynonymousCandidates() {
        val sequence = Seq(bases = "ATGAAATAA")
        val feature = Feature("mitochondrial", "CDS", 0, sequence.length, geneticCodeId = 9)
        val result = SiteDomestication.domesticate(sequence, listOf(Enzyme("fixture", "AAA", 0, 0)), listOf(feature))
        assertTrue(result.unresolvedSites.isEmpty())
        assertEquals("MN*", FeatureTranslations.translate(result.domesticated, feature).protein)
    }

    @Test fun everyCandidatePreservesOverlappingCodingFeatures() {
        val sequence = Seq(bases = "ATGGGTCTCTAA")
        val features = listOf(Feature("primary", "CDS", 0, sequence.length), Feature("overlap", "CDS", 1, 10))
        val result = SiteDomestication.domesticate(sequence, listOf(SiteDomestication.GOLDEN_GATE_ENZYMES.first()), features)
        features.forEach { feature ->
            assertEquals(FeatureTranslations.translate(sequence, feature).protein,
                FeatureTranslations.translate(result.domesticated, feature).protein)
        }
    }
}
