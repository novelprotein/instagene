package org.instagene.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ChromatogramRecord(val name: String, val bases: String, val qualities: List<Int> = emptyList(), val source: String = "") {
    fun toSeq(): Seq = Seq(name, bases, SeqKind.DNA, metadata = mapOf("CHROMATOGRAM" to source))
}

/** Reader for the called-base and quality channels of ABI/AB1 chromatograms. */
object ChromatogramReader {
    fun looksLikeAbi(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).decodeToString() == "ABIF"
    fun looksLikeScf(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).decodeToString() == ".scf"

    fun readAbi(file: File): ChromatogramRecord = readAbi(file.readBytes(), file.name)

    fun readScf(file: File): ChromatogramRecord = readScf(file.readBytes(), file.name)

    fun readAbi(bytes: ByteArray, name: String = "chromatogram"): ChromatogramRecord {
        require(looksLikeAbi(bytes)) { "Not an ABI chromatogram" }
        val entries = directory(bytes)
        val bases = entries.firstNotNullOfOrNull { entry ->
            if (entry.tag == "PBAS") readData(bytes, entry).decodeToString().takeWhile { it.code >= 32 }.filter { it in Alphabet.DNA_BASES }
            else null
        }.orEmpty()
        require(bases.isNotEmpty()) { "ABI chromatogram does not contain called bases" }
        val qualities = entries.firstNotNullOfOrNull { entry ->
            if (entry.tag == "PCON") readData(bytes, entry).map { it.toInt() and 0xff } else null
        }.orEmpty()
        return ChromatogramRecord(name.substringBeforeLast('.'), bases, qualities, name)
    }

    /** Reads the called bases and per-base confidence values from an SCF v3 file. */
    fun readScf(bytes: ByteArray, name: String = "chromatogram"): ChromatogramRecord {
        require(looksLikeScf(bytes)) { "Not an SCF chromatogram" }
        require(bytes.size >= 128) { "SCF chromatogram header is truncated" }
        val baseCount = uint(bytes, 12)
        val baseOffset = uint(bytes, 16)
        require(baseCount > 0 && baseOffset >= 0 && baseOffset + baseCount * 12L <= bytes.size) {
            "SCF chromatogram has an invalid base table"
        }
        val bases = StringBuilder(baseCount)
        val qualities = ArrayList<Int>(baseCount)
        repeat(baseCount) { index ->
            val at = baseOffset + index * 12
            val called = bytes[at + 8].toInt().and(0xff).toChar().uppercaseChar()
            if (called in Alphabet.DNA_BASES) {
                bases.append(called)
                qualities += maxOf(bytes[at + 4].toInt() and 0xff, bytes[at + 5].toInt() and 0xff,
                    bytes[at + 6].toInt() and 0xff, bytes[at + 7].toInt() and 0xff)
            }
        }
        require(bases.isNotEmpty()) { "SCF chromatogram does not contain called bases" }
        return ChromatogramRecord(name.substringBeforeLast('.'), bases.toString(), qualities, name)
    }

    private data class Entry(
        val tag: String,
        val number: Int,
        val elementSize: Int,
        val elementCount: Int,
        val dataSize: Int,
        val dataOffset: Int,
        val inlineData: ByteArray,
    )

    private fun directory(bytes: ByteArray): List<Entry> {
        val root = int(bytes, 26)
        if (root <= 0 || root + 28 > bytes.size) return emptyList()
        val count = int(bytes, root + 12)
        val rootDataSize = int(bytes, root + 16)
        val offset = if (rootDataSize <= 4) root + 20 else int(bytes, root + 20)
        if (count <= 0 || offset < 0 || offset > bytes.size) return emptyList()
        return (0 until count).mapNotNull { index ->
            val at = offset + index * 28
            if (at + 28 > bytes.size) return@mapNotNull null
            val dataSize = int(bytes, at + 16)
            Entry(
                bytes.copyOfRange(at, at + 4).decodeToString(),
                int(bytes, at + 4),
                short(bytes, at + 10),
                int(bytes, at + 12),
                dataSize,
                int(bytes, at + 20),
                if (dataSize <= 4) bytes.copyOfRange(at + 20, at + 24) else byteArrayOf(),
            )
        }
    }

    private fun readData(bytes: ByteArray, entry: Entry): ByteArray {
        if (entry.dataSize <= 4) return entry.inlineData.copyOf(entry.dataSize.coerceAtLeast(0))
        val offset = entry.dataOffset
        if (offset !in 0..bytes.size) return byteArrayOf()
        val size = (entry.elementSize * entry.elementCount).coerceAtMost(bytes.size - offset).coerceAtLeast(0)
        return bytes.copyOfRange(offset.coerceIn(0, bytes.size), (offset + size).coerceIn(0, bytes.size))
    }

    private fun int(bytes: ByteArray, offset: Int): Int = if (offset + 4 <= bytes.size) ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int else 0
    private fun short(bytes: ByteArray, offset: Int): Int = if (offset + 2 <= bytes.size) ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() else 0
    private fun uint(bytes: ByteArray, offset: Int): Int = int(bytes, offset).coerceAtLeast(0)
}
