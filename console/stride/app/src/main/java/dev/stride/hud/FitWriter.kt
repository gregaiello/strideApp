package dev.stride.hud

import java.io.ByteArrayOutputStream

/** FIT base type ids (the byte a definition message carries per field). */
object FitBase {
    const val ENUM = 0x00
    const val UINT8 = 0x02
    const val SINT16 = 0x83
    const val UINT16 = 0x84
    const val SINT32 = 0x85
    const val UINT32 = 0x86
    const val UINT32Z = 0x8C
}

data class FitField(val num: Int, val size: Int, val base: Int)

/**
 * The byte layer of a .FIT file: header, definition and data messages, CRC.
 *
 * Written from the public FIT protocol description, the same way Ftms.kt is
 * written from the Bluetooth spec, rather than by pulling in the FIT SDK: the
 * SDK's licence is not one an upstream PR should drag along, and the app has
 * kept to a single runtime dependency on purpose. An activity file needs a
 * handful of message types and no reading, so the encoder is small.
 *
 * Little-endian only (architecture byte 0). Readers handle either; there is
 * no reason to write both.
 */
class FitWriter {

    companion object {
        /** Seconds between the unix epoch and FIT's, 1989-12-31T00:00:00Z. */
        const val FIT_EPOCH_S = 631065600L
        private const val PROTOCOL = 0x20
        private const val PROFILE = 2132

        private val TABLE = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
        )

        /** FIT's nibble-table CRC, which is CRC-16/ARC ("123456789" → 0xBB3D). */
        fun crc(bytes: ByteArray, from: Int = 0, to: Int = bytes.size, seed: Int = 0): Int {
            var crc = seed
            for (i in from until to) {
                val b = bytes[i].toInt() and 0xFF
                var tmp = TABLE[crc and 0xF]
                crc = (crc shr 4) and 0x0FFF
                crc = crc xor tmp xor TABLE[b and 0xF]
                tmp = TABLE[crc and 0xF]
                crc = (crc shr 4) and 0x0FFF
                crc = crc xor tmp xor TABLE[(b shr 4) and 0xF]
            }
            return crc
        }

        fun fitTime(unixMs: Long): Long = unixMs / 1000 - FIT_EPOCH_S

        fun semicircles(deg: Double): Long = Math.round(deg * 2147483648.0 / 180.0)

        private fun invalid(base: Int): Long = when (base) {
            FitBase.ENUM, FitBase.UINT8 -> 0xFF
            FitBase.UINT16 -> 0xFFFF
            FitBase.SINT16 -> 0x7FFF
            FitBase.UINT32 -> 0xFFFFFFFFL
            FitBase.SINT32 -> 0x7FFFFFFF
            FitBase.UINT32Z -> 0
            else -> throw IllegalArgumentException("base type $base")
        }
    }

    private val body = ByteArrayOutputStream()
    private val defs = HashMap<Int, List<FitField>>()

    fun define(local: Int, global: Int, fields: List<FitField>) {
        require(local in 0..15)
        defs[local] = fields
        body.write(0x40 or local)
        body.write(0)          // reserved
        body.write(0)          // architecture: little-endian
        le(global.toLong(), 2)
        body.write(fields.size)
        for (f in fields) { body.write(f.num); body.write(f.size); body.write(f.base) }
    }

    /** @param values one per defined field, in order; null writes the field's "invalid". */
    fun data(local: Int, vararg values: Long?) {
        val fields = defs[local] ?: throw IllegalStateException("local type $local not defined")
        check(values.size == fields.size) { "local $local has ${fields.size} fields, got ${values.size}" }
        body.write(local)
        fields.forEachIndexed { i, f -> le(values[i] ?: invalid(f.base), f.size) }
    }

    fun bytes(): ByteArray {
        val data = body.toByteArray()
        val out = ByteArrayOutputStream(14 + data.size + 2)
        val header = ByteArray(14)
        header[0] = 14
        header[1] = PROTOCOL.toByte()
        header[2] = (PROFILE and 0xFF).toByte()
        header[3] = (PROFILE shr 8).toByte()
        for (i in 0 until 4) header[4 + i] = (data.size ushr (8 * i)).toByte()
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        val hc = crc(header, 0, 12)
        header[12] = (hc and 0xFF).toByte()
        header[13] = (hc shr 8).toByte()
        out.write(header)
        out.write(data)
        val all = out.toByteArray()
        val fc = crc(all)
        return all + byteArrayOf((fc and 0xFF).toByte(), (fc shr 8).toByte())
    }

    private fun le(v: Long, size: Int) {
        for (i in 0 until size) body.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
}
