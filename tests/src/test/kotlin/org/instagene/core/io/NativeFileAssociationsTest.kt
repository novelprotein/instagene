package org.instagene.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFileAssociationsTest {

    @Test
    fun manifestIsPresentAndUnique() {
        val associations = NativeFileAssociations.all
        assertTrue(associations.isNotEmpty())
        assertEquals(associations.size, associations.map { it.extension }.toSet().size)
        assertTrue(associations.all { it.extension == it.extension.lowercase() })
        assertTrue(associations.all { it.mimeType.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun onlyDirectlyReadableFormatsAreAssociated() {
        assertFalse(NativeFileAssociations.extensions.contains("txt"))
        assertFalse(NativeFileAssociations.extensions.contains("ab1"))
        assertFalse(NativeFileAssociations.extensions.contains("geneious"))
        assertTrue(NativeFileAssociations.extensions.containsAll(setOf("fasta", "gb", "gff3", "embl", "msa")))
    }
}
