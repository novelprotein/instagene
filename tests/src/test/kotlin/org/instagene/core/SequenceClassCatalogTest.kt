package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SequenceClassCatalogTest {

    @Test
    fun requestedBiologicalAndSubmissionClassesArePresent() {
        val labels = SequenceClassCatalog.labels.toSet()
        assertTrue(
            labels.containsAll(
                setOf(
                    "Primate",
                    "Rodent",
                    "Other mammalian",
                    "Other vertebrate",
                    "Invertebrate",
                    "Plant",
                    "Fungal",
                    "Algal",
                    "Bacterial",
                    "Viral",
                    "Phage",
                    "Expressed sequence tag",
                    "Patent sequence",
                    "Sequence-tagged site",
                    "Genome survey sequence",
                    "High-throughput genomic sequence",
                    "High-throughput cDNA sequence",
                    "Environmental sampling sequence",
                    "Contig or assembly record",
                ),
            ),
        )
    }

    @Test
    fun catalogAddsUsefulSequenceAndArchiveClassesWithoutInventingCodes() {
        assertTrue(SequenceClassCatalog.labels.containsAll(setOf(
            "Archaeal",
            "Genomic DNA",
            "mRNA or transcript",
            "cDNA",
            "Plasmid or vector",
            "Whole-genome shotgun sequence",
            "Transcriptome shotgun assembly",
            "Targeted locus study",
            "Third-party annotation or assembly",
        )))
        assertEquals("PRI", SequenceClassCatalog.option("Primate")?.ncbiCode)
        assertEquals("BCT", SequenceClassCatalog.option("Bacterial")?.ncbiCode)
        assertEquals("EST", SequenceClassCatalog.option("Expressed sequence tag")?.ncbiCode)
        assertEquals("CON", SequenceClassCatalog.option("Contig or assembly record")?.ncbiCode)
        assertEquals(null, SequenceClassCatalog.option("Plant")?.ncbiCode)
        assertEquals(null, SequenceClassCatalog.option("Fungal")?.ncbiCode)
        assertEquals(null, SequenceClassCatalog.option("Algal")?.ncbiCode)
        assertEquals(null, SequenceClassCatalog.option("Archaeal")?.ncbiCode)
    }

    @Test
    fun everyCatalogOptionHasAUniqueThreeLetterSequenceCode() {
        val codes = SequenceClassCatalog.options.map { it.sequenceCode }
        assertTrue(codes.all { it.matches(Regex("[A-Z]{3}")) })
        assertEquals(codes.size, codes.distinct().size)
        assertTrue(SequenceClassCatalog.options.all { it.uiLabel.isNotBlank() })
        assertTrue(SequenceClassCatalog.options.all { it.displayLabel == "${it.uiLabel} (${it.sequenceCode})" })
        assertEquals("PLT", SequenceClassCatalog.option("Plant")?.sequenceCode)
        assertEquals(SequenceClassCodeAuthority.INSTAGENE, SequenceClassCatalog.option("Plant")?.codeAuthority)
        assertEquals(SequenceClassCodeAuthority.NCBI, SequenceClassCatalog.option("Bacterial")?.codeAuthority)
    }

    @Test
    fun dropdownContainsOnlyOptionsAsStorableValuesAndHasUniqueLabels() {
        assertEquals(SequenceClassCatalog.options.size, SequenceClassCatalog.labels.distinct().size)
        assertTrue(SequenceClassCatalog.dropdownItems.first().isEmpty())
        assertTrue(SequenceClassCatalog.groupLabels.all { it in SequenceClassCatalog.dropdownItems })
        assertTrue(SequenceClassCatalog.options.all { it.displayLabel in SequenceClassCatalog.dropdownItems })
        assertFalse(SequenceClassCatalog.groupLabels.any { it in SequenceClassCatalog.labels })
        assertNotNull(SequenceClassCatalog.option("Bacterial"))
        assertTrue(SequenceClassCatalog.isGroupLabel("Biological source"))
        assertFalse(SequenceClassCatalog.isGroupLabel("Bacterial"))
    }
}
