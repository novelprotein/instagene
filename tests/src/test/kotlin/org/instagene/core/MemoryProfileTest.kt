package org.instagene.core

import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opt-in retained-heap observations for the file classes that commonly stress
 * a research desktop. This is intentionally diagnostic rather than a brittle
 * cross-machine memory limit; run it with:
 *
 * `./gradlew :tests:test --tests '*MemoryProfileTest' -Dinstagene.memoryProfile=true -Dinstagene.heap=2g`
 */
class MemoryProfileTest {

    @Test
    fun profilesLargeFastaGenBankAndAbiScfTraceBatches() {
        assumeTrue(
            System.getProperty("instagene.memoryProfile") == "true",
            "memory profile skipped (set -Dinstagene.memoryProfile=true to run)",
        )
        val bases = System.getProperty("instagene.memoryProfileBases")?.toIntOrNull()
            ?.coerceIn(100_000, 25_000_000)
            ?: 5_000_000
        val traceCount = System.getProperty("instagene.memoryProfileTraceCount")?.toIntOrNull()
            ?.coerceIn(1, 500)
            ?: 64
        val traceBases = 8_000
        val root = Files.createTempDirectory("instagene-memory-profile").toFile()
        try {
            val sequence = syntheticBases(bases)
            val fasta = File(root, "large.fasta").apply { writeFasta(this, sequence) }
            val genBank = File(root, "large.gb").apply {
                writeText(SeqIO.write(Seq(name = "memory-profile", bases = sequence), SeqFormat.GENBANK))
            }

            val fastaRead = profile("FASTA read ($bases bp)") { SeqIO.read(fasta) }
            val genBankRead = profile("GenBank read ($bases bp)") { SeqIO.read(genBank) }
            assertEquals(bases, fastaRead.length)
            assertEquals(bases, genBankRead.length)

            val scfFiles = (0 until traceCount).map { index ->
                File(root, "read-$index.scf").apply { writeBytes(minimalScf(traceBases)) }
            }
            val abiFiles = (0 until traceCount).map { index ->
                File(root, "read-$index.ab1").apply { writeBytes(minimalAbi(traceBases)) }
            }
            val scfReads = profile("SCF batch ($traceCount × $traceBases called bases)") {
                scfFiles.map(SeqIO::read)
            }
            val abiReads = profile("ABI batch ($traceCount × $traceBases called bases)") {
                abiFiles.map(SeqIO::read)
            }
            assertEquals(traceCount, scfReads.size)
            assertEquals(traceCount, abiReads.size)
            assertEquals(traceBases, scfReads.first().length)
            assertEquals(traceBases, abiReads.first().length)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun <T> profile(label: String, block: () -> T): T {
        val before = usedHeap()
        val result = block()
        val after = usedHeap()
        val deltaMiB = (after - before) / (1024.0 * 1024.0)
        println("[memory] $label: retained heap ${"%.1f".format(deltaMiB)} MiB (now ${"%.1f".format(after / (1024.0 * 1024.0))} MiB)")
        return result
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun syntheticBases(length: Int): String = buildString(length) {
        repeat(length) { append("ACGT"[it and 3]) }
    }

    private fun writeFasta(file: File, bases: String) {
        file.bufferedWriter().use { out ->
            out.appendLine(">memory-profile")
            bases.chunked(60).forEach(out::appendLine)
        }
    }

    /** Minimal SCF v2 data with calls, quality scores, and four short trace channels. */
    private fun minimalScf(baseCount: Int): ByteArray {
        val sampleCount = 4
        val sampleOffset = 128
        val baseOffset = sampleOffset + sampleCount * 4
        val bytes = ByteArray(baseOffset + baseCount * 12)
        ".scf".encodeToByteArray().copyInto(bytes)
        fun putInt(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        putInt(4, sampleCount)
        putInt(8, sampleOffset)
        putInt(12, baseCount)
        putInt(16, baseOffset)
        putInt(40, 1)
        "2.00".encodeToByteArray().copyInto(bytes, 36)
        for (channel in 0 until 4) bytes[sampleOffset + channel * sampleCount] = ((channel + 1) * 20).toByte()
        repeat(baseCount) { index ->
            val offset = baseOffset + index * 12
            putInt(offset, index + 1)
            bytes[offset + 4] = 40
            bytes[offset + 8] = "ACGT"[index and 3].code.toByte()
        }
        return bytes
    }

    /** Minimal ABI directory containing called bases (PBAS) and confidence values (PCON). */
    private fun minimalAbi(baseCount: Int): ByteArray {
        val root = 128
        val entries = root + 28
        val basesOffset = entries + 56
        val qualityOffset = basesOffset + baseCount
        val bytes = ByteArray(qualityOffset + baseCount)
        "ABIF".encodeToByteArray().copyInto(bytes)
        fun putInt(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        fun tag(offset: Int, value: String) = value.encodeToByteArray().copyInto(bytes, offset)
        fun entry(offset: Int, name: String, number: Int, dataOffset: Int) {
            tag(offset, name)
            putInt(offset + 4, number)
            bytes[offset + 10] = 1 // element size
            putInt(offset + 12, baseCount)
            putInt(offset + 16, baseCount)
            putInt(offset + 20, dataOffset)
        }
        putInt(26, root)
        putInt(root + 12, 2)
        putInt(root + 16, 56)
        putInt(root + 20, entries)
        entry(entries, "PBAS", 2, basesOffset)
        entry(entries + 28, "PCON", 2, qualityOffset)
        repeat(baseCount) { index ->
            bytes[basesOffset + index] = "ACGT"[index and 3].code.toByte()
            bytes[qualityOffset + index] = 40
        }
        return bytes
    }
}
