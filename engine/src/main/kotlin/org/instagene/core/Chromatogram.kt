package org.instagene.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Raw signal data used to inspect a called base in an ABI/SCF trace. */
data class ChromatogramTrace(
    val peakPositions: List<Int> = emptyList(),
    val channels: Map<Char, List<Int>> = emptyMap(),
) {
    fun hasSignal(): Boolean = peakPositions.isNotEmpty() && channels.values.any { it.isNotEmpty() }
}

data class ChromatogramRecord(
    val name: String,
    val bases: String,
    val qualities: List<Int> = emptyList(),
    val source: String = "",
    val trace: ChromatogramTrace? = null,
) {
    fun toSeq(): Seq = Seq(name, bases, SeqKind.DNA, metadata = mapOf("CHROMATOGRAM" to source))
    fun toSangerRead(): SangerRead = SangerRead(name, bases, qualities)
}

/** Reader for called bases, confidence values, and raw signal channels in ABI/AB1 and SCF chromatograms. */
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
        val order = entries.firstOrNull { it.tag == "FWO_" }
            ?.let { readData(bytes, it).decodeToString().uppercase().filter { base -> base in Alphabet.DNA_BASES } }
            ?.takeIf { it.length == 4 }
            ?: "GATC"
        val channels = order.mapIndexedNotNull { channelIndex, base ->
            entries.firstOrNull { it.tag == "DATA" && it.number == 9 + channelIndex }
                ?.let { base to readUnsignedShorts(readData(bytes, it)) }
        }.toMap()
        val peaks = entries.firstOrNull { it.tag == "PLOC" }?.let { readUnsignedShorts(readData(bytes, it)) }.orEmpty()
        return ChromatogramRecord(
            name.substringBeforeLast('.'), bases, qualities, name,
            ChromatogramTrace(peaks.take(bases.length), channels).takeIf { it.hasSignal() },
        )
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
        val peakPositions = ArrayList<Int>(baseCount)
        repeat(baseCount) { index ->
            val at = baseOffset + index * 12
            val called = bytes[at + 8].toInt().and(0xff).toChar().uppercaseChar()
            if (called in Alphabet.DNA_BASES) {
                bases.append(called)
                peakPositions += uint(bytes, at)
                qualities += maxOf(bytes[at + 4].toInt() and 0xff, bytes[at + 5].toInt() and 0xff,
                    bytes[at + 6].toInt() and 0xff, bytes[at + 7].toInt() and 0xff)
            }
        }
        require(bases.isNotEmpty()) { "SCF chromatogram does not contain called bases" }
        return ChromatogramRecord(
            name.substringBeforeLast('.'), bases.toString(), qualities, name,
            readScfTrace(bytes, peakPositions).takeIf { it.hasSignal() },
        )
    }

    private data class Entry(
        val tag: String,
        val number: Int,
        val elementSize: Int,
        val elementCount: Int,
        val dataSize: Int,
        val dataOffset: Int,
        val inlineData: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Entry

            if (number != other.number) return false
            if (elementSize != other.elementSize) return false
            if (elementCount != other.elementCount) return false
            if (dataSize != other.dataSize) return false
            if (dataOffset != other.dataOffset) return false
            if (tag != other.tag) return false
            if (!inlineData.contentEquals(other.inlineData)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = number
            result = 31 * result + elementSize
            result = 31 * result + elementCount
            result = 31 * result + dataSize
            result = 31 * result + dataOffset
            result = 31 * result + tag.hashCode()
            result = 31 * result + inlineData.contentHashCode()
            return result
        }
    }

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

    private fun readScfTrace(bytes: ByteArray, peakPositions: List<Int>): ChromatogramTrace {
        val sampleCount = uint(bytes, 4)
        val sampleOffset = uint(bytes, 8)
        val sampleSize = uint(bytes, 40)
        if (sampleCount <= 0 || sampleOffset < 0 || sampleSize !in 1..2 || sampleOffset + sampleCount * sampleSize * 4L > bytes.size) {
            return ChromatogramTrace()
        }
        val version = bytes.copyOfRange(36, 40).decodeToString()
        val samples = if (version.startsWith("3")) {
            "ACGT".mapIndexed { channel, base ->
                val start = sampleOffset + channel * sampleCount * sampleSize
                base to undoScfDelta(readSamples(bytes, start, sampleCount, sampleSize))
            }.toMap()
        } else {
            val channels = "ACGT".associateWith { ArrayList<Int>(sampleCount) }
            repeat(sampleCount) { index ->
                "ACGT".forEachIndexed { channel, base ->
                    val at = sampleOffset + (index * 4 + channel) * sampleSize
                    channels.getValue(base) += readSample(bytes, at, sampleSize)
                }
            }
            channels
        }
        return ChromatogramTrace(peakPositions, samples)
    }

    private fun readSamples(bytes: ByteArray, offset: Int, count: Int, sampleSize: Int): List<Int> =
        List(count) { index -> readSample(bytes, offset + index * sampleSize, sampleSize) }

    private fun readSample(bytes: ByteArray, offset: Int, sampleSize: Int): Int = when (sampleSize) {
        1 -> bytes[offset].toInt() and 0xff
        2 -> ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        else -> 0
    }

    /** SCF v3 stores each signal channel as a second-order delta stream. */
    private fun undoScfDelta(values: List<Int>): List<Int> {
        val restored = values.toMutableList()
        for (index in 1 until restored.size) restored[index] = (restored[index] + restored[index - 1]) and 0xffff
        for (index in 1 until restored.size) restored[index] = (restored[index] + restored[index - 1]) and 0xffff
        return restored
    }

    private fun readUnsignedShorts(bytes: ByteArray): List<Int> =
        bytes.asList().chunked(2).filter { it.size == 2 }.map { pair ->
            ((pair[0].toInt() and 0xff) shl 8) or (pair[1].toInt() and 0xff)
        }

    private fun int(bytes: ByteArray, offset: Int): Int = if (offset + 4 <= bytes.size) ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int else 0
    private fun short(bytes: ByteArray, offset: Int): Int = if (offset + 2 <= bytes.size) ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() else 0
    private fun uint(bytes: ByteArray, offset: Int): Int = int(bytes, offset).coerceAtLeast(0)
}
