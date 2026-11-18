package org.instagene.core.project

import org.instagene.core.feature.FeatureDefinition
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import org.instagene.core.sequence.Seq
import org.instagene.core.sequence.Topology
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BatchOperationsTest {
    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("instagene-batch-test").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    private fun sequence(file: File, bases: String = "ACGTACGT"): File {
        file.parentFile.mkdirs()
        SeqIO.write(file, Seq(file.nameWithoutExtension, bases))
        return file
    }

    @Test
    fun allOperationsRejectTheirOwnSourceAsOutput() = withDirectory { root ->
        val source = sequence(File(root, "source.gb"))
        val original = source.readText()
        val results = listOf(
            BatchOperations.convert(listOf(source), root, SeqFormat.GENBANK),
            BatchOperations.annotate(listOf(source), root, listOf(FeatureDefinition("tag", "ACGT", "misc_feature"))),
            BatchOperations.transformProperties(listOf(source), root, topology = Topology.CIRCULAR),
        )
        results.forEach {
            assertEquals(0, it.processed)
            assertContains(it.failed.getValue(source), "source file")
        }
        assertEquals(original, source.readText())
    }

    @Test
    fun duplicateDestinationsAbortBeforeAnyOutputIsWritten() = withDirectory { root ->
        val a = sequence(File(root, "a/shared.fasta"))
        val b = sequence(File(root, "b/shared.fasta"), "TTTT")
        val safe = sequence(File(root, "safe.fasta"))
        val output = File(root, "output")
        val result = BatchOperations.convert(listOf(safe, a, b), output, SeqFormat.GENBANK)
        assertEquals(0, result.processed)
        assertEquals(3, result.failed.size)
        assertContains(result.failed.getValue(a), "same output path")
        assertFalse(output.exists())
        assertEquals("TTTT", SeqIO.read(b).bases)
    }

    @Test
    fun anOutputCannotReplaceAnotherInputInTheBatch() = withDirectory { root ->
        val fasta = sequence(File(root, "sample.fasta"))
        val genbank = sequence(File(root, "sample.gb"), "TTTT")
        val original = genbank.readText()
        val result = BatchOperations.convert(listOf(fasta, genbank), root, SeqFormat.GENBANK)
        assertEquals(0, result.processed)
        assertContains(result.failed.getValue(fasta), "source file")
        assertEquals(original, genbank.readText())
    }

    @Test
    fun existingOutputsArePreserved() = withDirectory { root ->
        val source = sequence(File(root, "source.fasta"))
        val output = File(root, "output").apply { mkdirs() }
        val saved = File(output, "source.gb").apply { writeText("previous result") }
        val result = BatchOperations.convert(listOf(source), output, SeqFormat.GENBANK)
        assertEquals(0, result.processed)
        assertContains(result.failed.getValue(source), "already exists")
        assertEquals("previous result", saved.readText())
    }

    @Test
    fun uniqueInputsConvertSuccessfullyAndAliasesAreDeduplicated() = withDirectory { root ->
        val source = sequence(File(root, "source.fasta"))
        val output = File(root, "output")
        val result = BatchOperations.convert(listOf(source, File(root, "./source.fasta")), output, SeqFormat.GENBANK)
        assertEquals(1, result.processed)
        assertTrue(result.failed.isEmpty())
        assertEquals(SeqIO.read(source).bases, SeqIO.read(File(output, "source.gb")).bases)
        assertTrue(source.isFile)
    }
}
