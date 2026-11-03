package dev.stride.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitWriterTest {

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u(b, i) or (u(b, i + 1) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16)

    @Test fun crcCheckValue() {
        assertEquals(0xBB3D, FitWriter.crc("123456789".toByteArray()))
    }

    @Test fun headerLayout() {
        val b = FitWriter().bytes()
        assertEquals(16, b.size)
        assertEquals(14, u(b, 0))
        assertEquals(0x20, u(b, 1))
        assertEquals(0L, le32(b, 4))
        assertEquals(".FIT", String(b, 8, 4, Charsets.US_ASCII))
        assertEquals(FitWriter.crc(b, 0, 12), le16(b, 12))
    }

    @Test fun fileCrcCoversEverything() {
        val w = FitWriter()
        w.define(0, 20, listOf(FitField(253, 4, FitBase.UINT32)))
        w.data(0, 42L)
        val b = w.bytes()
        assertEquals(FitWriter.crc(b, 0, b.size - 2), le16(b, b.size - 2))
        assertEquals(0, FitWriter.crc(b))
        assertEquals((b.size - 16).toLong(), le32(b, 4))
    }

    @Test fun definitionThenData() {
        val w = FitWriter()
        w.define(0, 20, listOf(FitField(253, 4, FitBase.UINT32), FitField(3, 1, FitBase.UINT8)))
        w.data(0, 7L, null)
        val b = w.bytes()
        val region = b.copyOfRange(14, b.size - 2).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x40, 0, 0, 20, 0, 2, 253, 4, 0x86, 3, 1, 0x02) + listOf(0x00, 7, 0, 0, 0, 0xFF), region)
    }

    @Test fun invalidPerBaseType() {
        val w = FitWriter()
        w.define(1, 20, listOf(
            FitField(0, 1, FitBase.ENUM), FitField(1, 1, FitBase.UINT8), FitField(2, 2, FitBase.SINT16),
            FitField(3, 2, FitBase.UINT16), FitField(4, 4, FitBase.SINT32), FitField(5, 4, FitBase.UINT32),
            FitField(6, 4, FitBase.UINT32Z),
        ))
        w.data(1, null, null, null, null, null, null, null)
        val b = w.bytes()
        val data = b.copyOfRange(b.size - 2 - 19, b.size - 2)
        val expected = byteArrayOf(
            0x01,
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0x7F,
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0,
        )
        assertArrayEquals(expected, data)
    }

    @Test fun epochAndSemicircles() {
        assertEquals(0L, FitWriter.fitTime(631065600_000L))
        assertEquals(Math.round(-11.64 * 2147483648.0 / 180.0), FitWriter.semicircles(-11.64))
        assertTrue(FitWriter.semicircles(166.95) > 0)
    }

    @Test(expected = IllegalStateException::class)
    fun dataForUndefinedLocalThrows() {
        FitWriter().data(3, 1L)
    }
}
