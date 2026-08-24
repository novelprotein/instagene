package org.instagene.app.gui

import org.instagene.app.gui.file.FileOpenService
import org.instagene.app.gui.file.OpenedFile
import org.instagene.core.project.SeqProject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileOpenServiceTest {

    @Test
    fun loadsSequencesAndAlignedFastaThroughDistinctRoutes() {
        val root = Files.createTempDirectory("instagene-open").toFile().apply { deleteOnExit() }
        val sequence = root.resolve("vector.fasta").apply { writeText(">vector\nACGTACGT\n") }
        val alignment = root.resolve("reads.afa").apply {
            writeText(">reference\nACGT-\n>read\nACGTA\n")
        }

        val loadedSequence = assertIs<OpenedFile.Sequence>(FileOpenService.load(sequence))
        assertEquals("vector", loadedSequence.sequence.name)
        assertEquals("ACGTACGT", loadedSequence.sequence.bases)
        assertEquals(sequence.absolutePath, loadedSequence.sequence.metadata["SOURCE_FILE"])
        assertTrue(loadedSequence.sequence.metadata["SOURCE_SHA256"].orEmpty().matches(Regex("[0-9a-f]{64}")))

        val loadedAlignment = assertIs<OpenedFile.Alignment>(FileOpenService.load(alignment))
        assertEquals(listOf("reference", "read"), loadedAlignment.sequences.map { it.name })
        assertEquals(listOf("ACGT-", "ACGTA"), loadedAlignment.sequences.map { it.bases })
    }

    @Test
    fun batchContinuesPastUnsupportedFilesAndDeduplicatesPaths() {
        val root = Files.createTempDirectory("instagene-batch").toFile().apply { deleteOnExit() }
        val sequence = root.resolve("good.fa").apply { writeText(">good\nACGT\n") }
        val unsupported = root.resolve("bad.binary").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

        val batch = FileOpenService.loadAll(listOf(sequence, unsupported, sequence))

        assertEquals(1, batch.opened.size)
        assertEquals(1, batch.failures.size)
        assertEquals(sequence, batch.opened.single().file)
        assertTrue(batch.failures.single().message.contains("cannot open", ignoreCase = true))
    }

    @Test
    fun batchReportsMissingFilesAndPreservesCompletedItemsWhenCancelled() {
        val root = Files.createTempDirectory("instagene-cancel").toFile().apply { deleteOnExit() }
        val first = root.resolve("first.fa").apply { writeText(">first\nACGT\n") }
        val second = root.resolve("second.fa").apply { writeText(">second\nTGCA\n") }
        val missing = root.resolve("missing.fa")
        var cancel = false

        val cancelled = FileOpenService.loadAll(
            listOf(first, second),
            cancellationRequested = { cancel },
            onResult = { cancel = true },
        )
        val missingBatch = FileOpenService.loadAll(listOf(missing))

        assertTrue(cancelled.cancelled)
        assertEquals(listOf(first), cancelled.opened.map { it.file })
        assertEquals(1, missingBatch.failures.size)
        assertTrue(missingBatch.failures.single().message.contains("no longer exists"))
    }

    @Test
    fun loadsAnInstaGeneProjectFolder() {
        val root = Files.createTempDirectory("instagene-project").toFile().apply { deleteOnExit() }
        SeqProject.create(root).save()

        assertIs<OpenedFile.Project>(FileOpenService.load(root))
    }

    @Test
    fun rejectsUnequalAlignmentRowsWithActionableGuidance() {
        val file = Files.createTempFile("instagene-unaligned", ".msa").toFile().apply {
            deleteOnExit()
            writeText(">one\nACGT\n>two\nACGTA\n")
        }

        val error = runCatching { FileOpenService.load(file) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("different lengths"))
        assertTrue(error?.message.orEmpty().contains("run Alignment"))
    }

    @Test
    fun snapGeneFilesReceiveAnExplicitConversionPathInsteadOfAProprietaryParserClaim() {
        val file = Files.createTempFile("instagene-snapgene", ".dna").toFile().apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0, 1, 2))
        }

        val message = FileOpenService.unsupportedMessage(file)

        assertTrue(message.contains("SnapGene .dna import is intentionally deferred"))
        assertTrue(message.contains("Export the record as GenBank"))
    }
}
