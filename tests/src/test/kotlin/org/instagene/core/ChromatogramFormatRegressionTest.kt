package org.instagene.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Fixtures follow the published ABIF and Staden layouts, with distinct header fields and multiple bases. */
class ChromatogramFormatRegressionTest {
    @Test fun readsScfVersionsAndSampleWidths() {
        for (version in listOf(2, 3)) for (width in listOf(1, 2)) {
            val baseOffset = 128 + 3 * 4 * width
            val buffer = ByteBuffer.allocate(baseOffset + 36).order(ByteOrder.BIG_ENDIAN)
            buffer.put(".scf".encodeToByteArray())
            buffer.putInt(4, 3) // samples
            buffer.putInt(8, 128)
            buffer.putInt(12, 3) // bases
            buffer.putInt(16, 1) // left clip, deliberately not the base offset
            buffer.putInt(20, 2) // right clip
            buffer.putInt(24, baseOffset)
            "$version.00".encodeToByteArray().forEachIndexed { i, b -> buffer.put(36 + i, b) }
            buffer.putInt(40, width)
            val signal = if (width == 1) listOf(10, 20, 35) else listOf(1000, 2000, 3500)
            val encoded = if (width == 1) listOf(10, 0, 5) else listOf(1000, 0, 500)
            for (channel in 0..3) for (i in 0..2) {
                val offset = 128 + (if (version == 3) channel * 3 + i else i * 4 + channel) * width
                val value = if (version == 3) encoded[i] else signal[i]
                if (width == 1) buffer.put(offset, value.toByte()) else buffer.putShort(offset, value.toShort())
            }
            for (i in 0..2) {
                if (version == 3) {
                    buffer.putInt(baseOffset + i * 4, i)
                    buffer.put(baseOffset + 12 + i * 3 + i, (30 + i).toByte())
                    buffer.put(baseOffset + 24 + i, "ACG"[i].code.toByte())
                } else {
                    buffer.putInt(baseOffset + i * 12, i)
                    buffer.put(baseOffset + i * 12 + 4 + i, (30 + i).toByte())
                    buffer.put(baseOffset + i * 12 + 8, "ACG"[i].code.toByte())
                }
            }
            val record = ChromatogramReader.readScf(buffer.array())
            assertEquals("ACG", record.bases, "SCF $version / $width")
            assertEquals(listOf(30, 31, 32), record.qualities)
            assertEquals(listOf(0, 1, 2), record.trace?.peakPositions)
            "ACGT".forEach { assertEquals(signal, record.trace?.channels?.get(it)) }
        }
    }

    @Test fun readsAbifRootDirectoryAndInlineAndExternalNumericData() {
        for (width in listOf(1, 2, 4)) {
            val buffer = ByteBuffer.allocate(272).order(ByteOrder.BIG_ENDIAN)
            buffer.put("ABIF".encodeToByteArray())
            buffer.putShort(4, 101)
            fun entry(at: Int, tag: String, number: Int, size: Int, count: Int, data: ByteArray, offset: Int) {
                tag.encodeToByteArray().forEachIndexed { i, b -> buffer.put(at + i, b) }
                buffer.putInt(at + 4, number)
                buffer.putShort(at + 8, if (size == 1) 2 else 4)
                buffer.putShort(at + 10, size.toShort())
                buffer.putInt(at + 12, count)
                buffer.putInt(at + 16, data.size)
                if (data.size <= 4) data.forEachIndexed { i, b -> buffer.put(at + 20 + i, b) }
                else {
                    buffer.putInt(at + 20, offset)
                    data.forEachIndexed { i, b -> buffer.put(offset + i, b) }
                }
            }
            // The root directory entry is embedded at byte 6; its array starts at 128.
            buffer.putInt(18, 4)
            buffer.putInt(22, 4 * 28)
            buffer.putInt(26, 128)
            entry(128, "PBAS", 2, 1, 4, "ACGT".encodeToByteArray(), 0)
            entry(156, "PCON", 2, 1, 4, byteArrayOf(30, 31, 32, 33), 0)
            entry(184, "PLOC", 2, 2, 4, byteArrayOf(0, 0, 0, 1, 0, 2, 0, 3), 240)
            val samples = ByteBuffer.allocate(width * 4).order(ByteOrder.BIG_ENDIAN)
            for (value in listOf(10, 20, 30, 200)) when (width) {
                1 -> samples.put(value.toByte())
                2 -> samples.putShort(value.toShort())
                4 -> samples.putInt(value)
            }
            entry(212, "DATA", 9, width, 4, samples.array(), 248)
            val record = ChromatogramReader.readAbi(buffer.array())
            assertEquals("ACGT", record.bases)
            assertEquals(listOf(30, 31, 32, 33), record.qualities)
            assertEquals(listOf(0, 1, 2, 3), record.trace?.peakPositions)
            assertEquals(listOf(10, 20, 30, 200), record.trace?.channels?.get('G'))
        }
    }
}
