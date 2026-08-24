package org.instagene.app.gui

import org.instagene.app.gui.file.FileType
import org.instagene.app.gui.file.FileTypes
import org.instagene.core.io.NativeFileAssociations
import org.instagene.core.project.SeqProject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The unified open dialog routes every file family that InstaGene understands. */
class FileTypeTest {

    @Test
    fun sequenceOnlyFilterStillAcceptsNativeSequenceExtensions() {
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

    @Test
    fun unifiedOpenFilterIncludesResearchInputsAndProjectFoldersAreClassified() {
        val filter = FileTypes.supportedOpenFileFilter()
        assertEquals("Supported research files", filter.description)
        for (ext in NativeFileAssociations.extensions + listOf("ab1", "abi", "scf", "md", "notes", "png", "pdf")) {
            assertTrue(filter.accept(File("input.$ext")), "$ext must be offered by the unified dialog")
        }

        val root = Files.createTempDirectory("instagene-project").toFile()
        root.deleteOnExit()
        SeqProject.create(root).save()
        assertEquals(FileType.PROJECT, FileTypes.classify(root))
        assertTrue(filter.accept(root), "JFileChooser must keep project folders visible")
    }

    @Test
    fun alignmentExtensionsAreRoutedSeparatelyFromSingleSequences() {
        assertEquals(FileType.ALIGNMENT, FileTypes.classify(File("rows.afa")))
        assertEquals(FileType.ALIGNMENT, FileTypes.classify(File("rows.aln")))
        assertEquals(FileType.ALIGNMENT, FileTypes.classify(File("rows.sto")))
        assertEquals(FileType.ALIGNMENT, FileTypes.classify(File("rows.phy")))
        assertEquals(FileType.SEQUENCE, FileTypes.classify(File("vector.gb")))
    }
}
