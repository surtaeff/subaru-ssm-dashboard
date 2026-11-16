package kg.forester.sg.ssm

import java.util.zip.CRC32

/**
 * The RealDash CAN protocol: what DIY CAN boards (an Arduino with an MCP2515, an ESP32) and many CAN-to-Bluetooth
 * bridges send to RealDash. Layout from janimm/RealDash-extras, RealDash-CAN/realdash-can-protocol.md (public domain):
 *
 * - «44» frame: 44 33 22 11, id (4 bytes, little endian), 8 data bytes (shorter frames padded with zeroes). No length,
 *   no extended flag, no bus, no time stamp.
 * - «66» frame: 66 33 22, size code 0x11–0x1F (8, 12 … 64 bytes), id (4 LE), data, CRC32 (4 LE) over all bytes before
 *   it. For CAN FD.
 * - «55» text frame: 55 33 22 11, id (4 LE), UTF-8 text up to a 0 byte.
 * - «67» config frames, app to device: 67 33 22 02 15 speed CRC32 (bus speed), 67 33 22 01 mode CRC32 (normal, loopback,
 *   silent, loopback silent).
 * - App to device, «SET VALUE 44»: the 44 frame plus a 1-byte sum of the 16 bytes before it (17 bytes); «SET VALUE 66»
 *   the same as the 66 frame.
 *
 * The CRC is the usual CRC-32 (reflected, 0xEDB88320, start and final XOR 0xFFFFFFFF), as java.util.zip.CRC32 makes it:
 * the spec's table is that one.
 */
object RealDashCan {
    /** What a stream gave besides frames. */
    class Stats {
        var frames = 0
        /** 66 / 67 frames dropped for a wrong CRC32. */
        var crcErrors = 0
        /** Bytes skipped to find the next frame (a stream joined midway, line noise, an unknown frame). */
        var skipped = 0
        var texts = 0
        var configs = 0
    }

    /**
     * Turns the byte stream into frames, frame bytes split across reads in any way. Anything that is not a known frame
     * is skipped a byte at a time until the next tag: that is how RealDash itself catches up with a stream.
     */
    class Decoder(private val onText: ((id: Int, text: String) -> Unit)? = null) {
        val stats = Stats()
        private var buf = ByteArray(512)
        private var len = 0

        fun feed(src: ByteArray, off: Int = 0, n: Int = src.size, out: MutableList<CanFrame>) {
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            System.arraycopy(src, off, buf, len, n)
            len += n
            var at = 0
            while (at < len) {
                val used = frameAt(at, out)
                when {
                    used > 0 -> at += used
                    used == 0 -> break // a frame begun, the rest still to come
                    else -> { at++; stats.skipped++ }
                }
            }
            if (at > 0) { System.arraycopy(buf, at, buf, 0, len - at); len -= at }
            // never hold on to more than one frame's worth of junk
            if (len > 1024) { stats.skipped += len; len = 0 }
        }

        /** A frame at [at]: its length when taken (or skipped whole), 0 when it needs more bytes, -1 when there is none. */
        private fun frameAt(at: Int, out: MutableList<CanFrame>): Int {
            val have = len - at
            fun b(i: Int) = buf[at + i].toInt() and 0xFF
            // the tag first, as far as it has come: a partial tag waits for the rest
            val tag = when (b(0)) {
                0x44 -> TAG_44; 0x55 -> TAG_55; 0x66 -> TAG_66; 0x67 -> TAG_67; else -> return -1
            }
            for (i in 1 until minOf(tag.size, have)) if (b(i) != tag[i]) return -1
            if (have < tag.size) return 0
            when (b(0)) {
                0x44 -> {
                    if (have < 16) return 0
                    val (id, ext) = id(at + 4)
                    out += CanFrame(id, ext, buf.copyOfRange(at + 8, at + 16))
                    stats.frames++
                    return 16
                }
                0x66 -> {
                    if (have < 4) return 0
                    val size = sizeOf(b(3)) ?: return -1
                    val total = 8 + size + 4
                    if (have < total) return 0
                    if (crc(buf, at, total - 4) != le32(at + total - 4)) { stats.crcErrors++; return -1 }
                    val (id, ext) = id(at + 4)
                    out += CanFrame(id, ext, buf.copyOfRange(at + 8, at + 8 + size), fd = size > 8)
                    stats.frames++
                    return total
                }
                0x55 -> {
                    if (have < 9) return 0
                    // the text runs to its 0 byte; one that never ends is noise
                    val end = (at + 8 until len).firstOrNull { buf[it].toInt() == 0 }
                        ?: return if (have > 8 + MAX_TEXT) -1 else 0
                    if (end - at - 8 > MAX_TEXT) return -1
                    stats.texts++
                    onText?.invoke(id(at + 4).first, String(buf, at + 8, end - at - 8, Charsets.UTF_8))
                    return end - at + 1
                }
                else -> { // 0x67: a config frame (our own, echoed back by a loopback): checked and dropped
                    if (have < 4) return 0
                    val total = when (b(3)) { 0x01 -> 9; 0x02 -> 10; else -> return -1 }
                    if (have < total) return 0
                    if (crc(buf, at, total - 4) != le32(at + total - 4)) { stats.crcErrors++; return -1 }
                    stats.configs++
                    return total
                }
            }
        }

        private fun le32(i: Int): Long =
            (buf[i].toLong() and 0xFF) or ((buf[i + 1].toLong() and 0xFF) shl 8) or
                ((buf[i + 2].toLong() and 0xFF) shl 16) or ((buf[i + 3].toLong() and 0xFF) shl 24)

        /**
         * The protocol has no extended-id flag: an id above 0x7FF is an extended one. A device that marks it the
         * SocketCAN way (bit 31) is understood too.
         */
        private fun id(i: Int): Pair<Int, Boolean> {
            val raw = le32(i)
            val flagged = raw and 0x80000000L != 0L
            val id = (raw and 0x1FFFFFFFL).toInt()
            return id to (flagged || id > CanFrame.MAX_STD_ID)
        }
    }

    private val TAG_44 = intArrayOf(0x44, 0x33, 0x22, 0x11)
    private val TAG_55 = intArrayOf(0x55, 0x33, 0x22, 0x11)
    private val TAG_66 = intArrayOf(0x66, 0x33, 0x22)
    private val TAG_67 = intArrayOf(0x67, 0x33, 0x22)
    private const val MAX_TEXT = 256

    /** Payload bytes for a 66 frame's size code (0x11 = 8 … 0x1F = 64), null for any other byte. */
    fun sizeOf(code: Int): Int? = if (code in 0x11..0x1F) 8 + (code - 0x11) * 4 else null

    /** The size code for [len] bytes, rounded up to the next size the 66 frame has. */
    fun sizeCode(len: Int): Int = 0x11 + ((len.coerceIn(8, 64) - 8 + 3) / 4)

    fun crc(b: ByteArray, off: Int, n: Int): Long = CRC32().apply { update(b, off, n) }.value

    private fun putLe32(b: ByteArray, at: Int, v: Long) { for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte() }

    private fun idWord(f: CanFrame): Long = f.id.toLong() and 0x1FFFFFFFL

    /** A 44 frame (device to RealDash), for tests and the demo. */
    fun frame44(id: Int, data: ByteArray): ByteArray {
        val b = ByteArray(16)
        TAG_44.forEachIndexed { i, v -> b[i] = v.toByte() }
        putLe32(b, 4, id.toLong())
        data.copyInto(b, 8, 0, minOf(8, data.size))
        return b
    }

    /** «SET VALUE 44»: the 44 frame with a 1-byte sum of the 16 bytes before it (overflow dropped). */
    fun setValue44(f: CanFrame): ByteArray {
        val b = frame44(idWord(f).toInt(), f.data).copyOf(17)
        b[16] = (b.take(16).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return b
    }

    /** A 66 frame («SET VALUE 66» too): [data] padded to the next size the frame has, CRC32 at the end. */
    fun frame66(id: Int, data: ByteArray): ByteArray {
        val code = sizeCode(data.size)
        val size = sizeOf(code)!!
        val b = ByteArray(8 + size + 4)
        TAG_66.forEachIndexed { i, v -> b[i] = v.toByte() }
        b[3] = code.toByte()
        putLe32(b, 4, id.toLong() and 0x1FFFFFFFL)
        data.copyInto(b, 8, 0, minOf(size, data.size))
        putLe32(b, 8 + size, crc(b, 0, 8 + size))
        return b
    }

    /**
     * The speed byte of the «67» speed frame for [kbit], null for a speed it has no code for. The spec lists 20, 50,
     * 100, 125, 500, 800 and 1000 kbit; 0x05 is missing from its table and taken as 250 kbit (the gap between 125 and
     * 500): an assumption, not in the spec.
     */
    fun speedByte(kbit: Int): Int? = when (kbit) {
        20 -> 0x01; 50 -> 0x02; 100 -> 0x03; 125 -> 0x04; 250 -> 0x05; 500 -> 0x06; 800 -> 0x07; 1000 -> 0x08; else -> null
    }

    const val MODE_NORMAL = 0x11
    const val MODE_LOOPBACK = 0x12
    const val MODE_SILENT = 0x13
    const val MODE_LOOPBACK_SILENT = 0x14

    fun configSpeed(kbit: Int): ByteArray? {
        val s = speedByte(kbit) ?: return null
        val b = byteArrayOf(0x67, 0x33, 0x22, 0x02, 0x15, s.toByte(), 0, 0, 0, 0)
        putLe32(b, 6, crc(b, 0, 6))
        return b
    }

    fun configMode(mode: Int): ByteArray {
        val b = byteArrayOf(0x67, 0x33, 0x22, 0x01, mode.toByte(), 0, 0, 0, 0)
        putLe32(b, 5, crc(b, 0, 5))
        return b
    }

    /** What a device sending «44» frames on its own looks like: at least one good frame in [bytes]. */
    fun looksLike(bytes: ByteArray, n: Int = bytes.size): Boolean {
        val out = ArrayList<CanFrame>()
        Decoder().feed(bytes, 0, n, out)
        return out.isNotEmpty()
    }
}

/**
 * A RealDash CAN device on any [link]. It streams by itself; on open it is told the bus speed and mode with «67»
 * frames when [config] (a sketch that knows none ignores them). [silent]: the device is asked not to take part in the
 * bus at all (no ACK): the car's own buses; a sensor bus of two nodes needs [silent] false.
 */
class RealDashSource(
    private val link: ByteLink,
    private val kbit: Int,
    private val config: Boolean,
    private val silent: Boolean,
) : CanSource {
    private val decoder = RealDashCan.Decoder { id, text -> SsmTrace.log("RealDash text %X: %s".format(id, text)) }
    private val buf = ByteArray(4096)

    override val title get() = "RealDash CAN"
    override val errors: Int get() = decoder.stats.crcErrors
    override val canTransmit get() = true

    fun open() {
        if (!config) return
        RealDashCan.configSpeed(kbit)?.let { link.write(it) }
        link.write(RealDashCan.configMode(if (silent) RealDashCan.MODE_SILENT else RealDashCan.MODE_NORMAL))
    }

    override fun read(timeoutMs: Int): List<CanFrame> {
        val n = link.read(buf, timeoutMs)
        if (n <= 0) return emptyList()
        val out = ArrayList<CanFrame>()
        decoder.feed(buf, 0, n, out)
        return out
    }

    override fun transmit(f: CanFrame, permit: CanTx.Permit): Boolean {
        if (!permit.covers(f)) return false
        val bytes = if (!f.fd && f.data.size <= 8) RealDashCan.setValue44(f) else RealDashCan.frame66(f.id, f.data)
        return runCatching { link.write(bytes) }.isSuccess
    }

    override fun close() = link.close()
}
