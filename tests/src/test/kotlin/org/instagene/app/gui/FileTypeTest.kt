package org.instagene.app.gui

import org.instagene.app.gui.file.FileTypes
import org.instagene.core.io.NativeFileAssociations
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The open-file dialog offers only sequence files. Text notes, images, and PDFs
 * remain accessible through the project tree.
 */
class FileTypeTest {

    @Test
    fun openFileFilterAcceptsOnlySequenceExtensions() {
        val filter = FileTypes.sequenceFileFilter()
        assertEquals("Sequence files", filter.description)

        for (ext in NativeFileAssociations.extensions) {
            assertTrue(filter.accept(File("seq.$ext")), "$ext must be openable from the dialog")
        }
        assertTrue(filter.accept(File("SEQ.FASTA")), "the filter must be case-insensitive")

        for (ext in listOf("txt", "md", "notes", "log", "png", "jpg", "pdf", "docx")) {
            assertFalse(filter.accept(File("notes.$ext")), "$ext must not be offered in the dialog")
        }
    }
}
