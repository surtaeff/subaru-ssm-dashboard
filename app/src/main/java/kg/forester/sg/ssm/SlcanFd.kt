package kg.forester.sg.ssm

/**
 * CAN FD frames in slcan, as the CANable 2.0 FD firmwares send them: "d"/"D" (11/29-bit id, no bit rate switch),
 * "b"/"B" (with it), then the id, one hex digit of DLC (0–8 bytes as is, 9–F = 12, 16, 20, 24, 32, 48, 64) and the
 * data, sometimes with a time stamp after it.
 */
object SlcanFd {
    /** Data bytes of each DLC code. */
    val SIZES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64)

    fun dlc(size: Int): Int = SIZES.indexOfFirst { it >= size }.takeIf { it >= 0 } ?: 15

    fun parse(line: String): Slcan.Frame? {
        if (line.isEmpty()) return null
        val ext = when (line[0]) { 'd', 'b' -> false; 'D', 'B' -> true; else -> return null }
        val idLen = if (ext) 8 else 3
        if (line.length < 2 + idLen) return null
        val id = line.substring(1, 1 + idLen).toIntOrNull(16) ?: return null
        val len = SIZES[line[1 + idLen].digitToIntOrNull(16) ?: return null]
        val at = 2 + idLen
        if (line.length < at + len * 2) return null
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = (line.substring(at + i * 2, at + i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        return Slcan.Frame(id, ext, data)
    }
}
