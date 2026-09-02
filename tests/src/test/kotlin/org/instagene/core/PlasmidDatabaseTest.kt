package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlasmidDatabaseTest {

    @Test
    fun searchFindsPUC19() {
        val result = PlasmidDatabase.search("pUC19")
        assertEquals(1, result.results.size)
        assertEquals("pUC19", result.results[0].name)
    }

    @Test
    fun searchByMarker() {
        val result = PlasmidDatabase.search("KanR")
        assertTrue(result.results.isNotEmpty(), "Should find plasmids with KanR marker")
    }

    @Test
    fun getByNameReturnsRecord() {
        val record = PlasmidDatabase.getByName("pcDNA3.1")
        assertNotNull(record)
        assertEquals("pcDNA3.1", record.name)
        assertEquals("Mammalian", record.organism)
    }

    @Test
    fun getByNameReturnsNullForUnknown() {
        assertNull(PlasmidDatabase.getByName("NonExistent"))
    }

    @Test
    fun allReturnsBuiltInSet() {
        assertTrue(PlasmidDatabase.all().size >= 8, "Should have at least 8 built-in plasmids")
    }

    @Test
    fun pbr322DatabaseRecordUsesBundledNcbiSequence() {
        val sequence = PlasmidDatabase.sequenceFor("pBR322")
        assertNotNull(sequence)
        assertEquals("pBR322_NCBI", sequence.name)
        assertEquals(4361, sequence.length)
        assertTrue(sequence.features.isNotEmpty())
        assertEquals("J01749.1", sequence.metadata["ONLINE_ACCESSION"])
    }

    @Test
    fun puc19DatabaseRecordUsesCompleteBundledReferenceSequence() {
        val sequence = PlasmidDatabase.sequenceFor("pUC19")
        assertNotNull(sequence)
        assertEquals("pUC19_NCBI_reference", sequence.name)
        assertEquals(2686, sequence.length)
        assertEquals("M77789.2", sequence.metadata["ONLINE_ACCESSION"])
        assertTrue(sequence.recordMetadata.references.isNotEmpty())
    }
}
