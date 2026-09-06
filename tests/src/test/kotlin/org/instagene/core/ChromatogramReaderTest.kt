package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChromatogramReaderTest {
    @Test
    fun readsScfV3DeltaEncodedEightBitChannels() {
        val bytes = ByteArray(128 + 8 + 12)
        ".scf".encodeToByteArray().copyInto(bytes)
        fun putInt(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        putInt(4, 2)
        putInt(8, 128)
        putInt(12, 1)
        putInt(16, 136)
        "3.00".encodeToByteArray().copyInto(bytes, 36)
        putInt(40, 1)
        // Each channel has two second-order deltas: [10, 0] restores [10, 20].
        for (channel in 0 until 4) {
            bytes[128 + channel * 2] = 10
            bytes[129 + channel * 2] = 0
        }
        putInt(136, 7)
        bytes[140] = 50
        bytes[144] = 'A'.code.toByte()

        val record = ChromatogramReader.readScf(bytes, "fixture.scf")
        assertEquals("A", record.bases)
        assertEquals(listOf(10, 20), record.trace?.channels?.get('A'))
    }

    @Test
    fun rejectsTruncatedAbiDirectoryInsteadOfReadingPastInput() {
        val bytes = ByteArray(32)
        "ABIF".encodeToByteArray().copyInto(bytes)
        bytes[26] = 0
        bytes[27] = 0
        bytes[28] = 0
        bytes[29] = 120

        assertFails { ChromatogramReader.readAbi(bytes) }
    }
}
