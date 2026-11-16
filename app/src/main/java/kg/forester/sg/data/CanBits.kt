package kg.forester.sg.data

/**
 * Bit fields of a CAN frame the way a DBC lays them out. Bits are numbered byte by byte, bit 0 the lowest of byte 0,
 * bit 8 the lowest of byte 1 and so on.
 *
 * - Intel (@1, little endian): [start] is the lowest bit of the value, the value goes up from there.
 * - Motorola (@0, big endian): [start] is the highest bit of the value; going down it leaves a byte at its bit 0 and
 *   goes on at bit 7 of the next byte (the "sawtooth" of Vector's editor).
 */
object CanBits {
    private fun bit(d: ByteArray, pos: Int): Long = ((d[pos ushr 3].toInt() ushr (pos and 7)) and 1).toLong()

    /** The bit positions of the field from its highest to its lowest bit, or null when one lies outside a frame of [size] bytes. */
    fun positions(start: Int, len: Int, motorola: Boolean, size: Int): IntArray? {
        if (len !in 1..64 || start < 0) return null
        val out = IntArray(len)
        if (motorola) {
            var p = start
            for (k in 0 until len) {
                if (p < 0 || p >= size * 8) return null
                out[k] = p
                p = if (p and 7 == 0) p + 15 else p - 1
            }
        } else {
            for (k in 0 until len) {
                val p = start + len - 1 - k
                if (p >= size * 8) return null
                out[k] = p
            }
        }
        return out
    }

    /** The field's raw number (two's complement when [signed]), or null when the frame is too short for it. */
    fun get(d: ByteArray, start: Int, len: Int, motorola: Boolean, signed: Boolean): Long? {
        val pos = positions(start, len, motorola, d.size) ?: return null
        var u = 0L
        for (p in pos) u = (u shl 1) or bit(d, p)
        if (signed && len < 64 && (u ushr (len - 1)) and 1L == 1L) u -= 1L shl len
        return u
    }

    /** The lowest and highest byte the field touches (for the byte model's fallback and the B0… of RealDash formulas). */
    fun byteSpan(start: Int, len: Int, motorola: Boolean): IntRange? {
        val pos = positions(start, len, motorola, 64) ?: return null
        return (pos.minOf { it } ushr 3)..(pos.maxOf { it } ushr 3)
    }

    /**
     * Bit [j] of a value of [len] bytes at [offset] (j = 0 its lowest bit), as a frame bit position: low byte first
     * when not [bigEndian], high byte first when it is.
     */
    fun valueBit(offset: Int, len: Int, bigEndian: Boolean, j: Int): Int =
        if (bigEndian) (offset + len - 1 - j / 8) * 8 + j % 8 else offset * 8 + j
}
