package org.instagene.app.gui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The open-file dialog must offer sequence files only; everything else stays
 * a project fallback (text notes, images, PDFs) reachable via the project tree. */
class FileTypeTest {

    @Test
    fun openFileFilterAcceptsOnlySequenceExtensions() {
        val filter = FileTypes.sequenceFileFilter()
        assertEquals("Sequence files", filter.description)

        for (ext in listOf("fasta", "fa", "fna", "fas", "gb", "gbk", "genbank", "gp", "ape", "seq")) {
            assertTrue(filter.accept(File("seq.$ext")), "$ext must be openable from the dialog")
        }
        assertTrue(filter.accept(File("SEQ.FASTA")), "the filter must be case-insensitive")

        for (ext in listOf("txt", "md", "notes", "log", "png", "jpg", "pdf", "docx")) {
            assertFalse(filter.accept(File("notes.$ext")), "$ext must not be offered in the dialog")
        }
    }
}
