package kg.forester.sg.ssm

/**
 * GVRET, the binary protocol of ESP32RET / M2RET / GVRET boards that SavvyCAN talks to (USB serial at 1 Mbaud, or
 * Wi-Fi TCP port 23 with mDNS "_telnet._tcp"). Byte layouts from collin80/ESP32RET (MIT): src/gvret_comm.cpp (what the
 * board takes), src/commbuffer.cpp sendFrameToBuffer (the frames it sends), src/wifi_manager.cpp (port and mDNS),
 * src/ESP32RET.cpp (Serial.begin(1000000)).
 *
 * The board starts in a text console; 0xE7 switches it to binary. Every binary message starts with 0xF1 and a command:
 *
 * - 0 frame, board to host: F1 00, time stamp µs (4 LE), id (4 LE, bit 31 = extended), length | bus << 4, data, 0.
 *   Host to board: F1 00, id (4 LE, bit 31 = extended), bus, length, data, a trailing byte (the board sends the frame
 *   only once that byte came).
 * - 20 CAN FD frame, board to host: F1 14, time stamp (4 LE), id (4 LE), length (0–64), bus, data, 0.
 *   Host to board: F1 14, id (4 LE), bus, length | BRS << 7, data, a trailing byte.
 * - 1 time sync: F1 01 → F1 01 + µs (4 LE). 7 device info: F1 07 → F1 07 + build (2 LE), 0x20, 0, 0, 0.
 *   12 number of buses: F1 0C → F1 0C + n. 6 bus parameters: F1 06 → F1 06 + per bus (two): enabled | listen-only << 4,
 *   speed (4 LE). 9 keep-alive: F1 09 → F1 09 DE AD. 22 FD settings: F1 16 → F1 16 + n + per bus flags, FD rate (4 LE).
 * - 5 set up the buses: F1 05, bus 0 word (4 LE), bus 1 word (4 LE): speed in the low 20 bits, 0x80000000 = the next two
 *   bits count, 0x40000000 = enabled, 0x20000000 = listen only; 0 switches the bus off. SavvyCAN adds a trailing 0.
 *
 * The board's trailing byte after a frame is 0 (no checksum is worked out): it is not waited for, the next message
 * starts at the next F1.
 */
object Gvret {
    const val CMD = 0xF1
    const val BINARY = 0xE7

    const val FRAME = 0
    const val TIME_SYNC = 1
    const val DIG_INPUTS = 2
    const val ANA_INPUTS = 3
    const val SETUP_CANBUS = 5
    const val CANBUS_PARAMS = 6
    const val DEV_INFO = 7
    const val KEEPALIVE = 9
    const val NUM_BUSES = 12
    const val EXT_BUSES = 13
    const val FD_FRAME = 20
    const val GET_FD = 22

    /** The board's answers to the commands this app sends. */
    class Info {
        @Volatile var buses: Int? = null
        @Volatile var build: Int? = null
        /** Per bus: speed in bit/s, null when the bus is off; filled by [CANBUS_PARAMS]. */
        @Volatile var speeds: List<Int?> = emptyList()
        @Volatile var listenOnly: List<Boolean> = emptyList()
        /** Per bus: can do CAN FD at all. */
        @Volatile var fdCapable: List<Boolean> = emptyList()
        @Volatile var keepAlive = 0
        @Volatile var timeUs: Long? = null
        val answered: Boolean get() = buses != null || build != null || keepAlive > 0 || speeds.isNotEmpty()
    }

    /** The board's byte stream to frames and answers; messages split across reads in any way. */
    class Decoder {
        val info = Info()
        var frames = 0
            private set
        /** Bytes outside a message: the text console before binary mode, noise. */
        var skipped = 0
            private set
        /** Messages dropped half-way (an impossible length, an unknown command). */
        var dropped = 0
            private set

        private var state = 0 // 0 idle, 1 command, 2 collecting
        private var cmd = 0
        private val msg = ByteArray(96)
        private var have = 0
        private var need = 0

        fun feed(src: ByteArray, off: Int = 0, n: Int = src.size, out: MutableList<CanFrame>) {
            for (i in off until off + n) byte(src[i].toInt() and 0xFF, out)
        }

        private fun byte(c: Int, out: MutableList<CanFrame>) {
            when (state) {
                0 -> if (c == CMD) state = 1 else skipped++
                1 -> {
                    cmd = c
                    have = 0
                    need = when (c) {
                        FRAME -> 9 // time stamp, id, length | bus: the data length follows from the last
                        FD_FRAME -> 10 // time stamp, id, length, bus
                        TIME_SYNC -> 4
                        DIG_INPUTS -> 2
                        ANA_INPUTS -> 15
                        CANBUS_PARAMS -> 10
                        DEV_INFO -> 6
                        KEEPALIVE -> 2
                        NUM_BUSES -> 1
                        EXT_BUSES -> 15
                        GET_FD -> 1
                        else -> -1
                    }
                    if (need < 0) { dropped++; state = if (c == CMD) 1 else 0 } else state = 2
                }
                else -> {
                    msg[have++] = c.toByte()
                    if (have == 9 && cmd == FRAME) {
                        val len = c and 0x0F
                        if (len > 8) { drop(); return }
                        need = 9 + len
                    }
                    if (have == 9 && cmd == FD_FRAME) {
                        if (c > 64) { drop(); return }
                        need = 10 + c
                    }
                    if (have == 1 && cmd == GET_FD) need = 1 + (c.coerceAtMost(8)) * 5
                    if (have >= need) { done(out); state = 0 }
                }
            }
        }

        private fun drop() { dropped++; state = 0 }

        private fun u8(i: Int) = msg[i].toInt() and 0xFF
        private fun le32(i: Int): Long = (u8(i).toLong()) or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

        private fun done(out: MutableList<CanFrame>) {
            when (cmd) {
                FRAME, FD_FRAME -> {
                    val raw = le32(4)
                    val ext = raw and 0x80000000L != 0L
                    val id = (raw and 0x1FFFFFFFL).toInt()
                    val fd = cmd == FD_FRAME
                    val len = if (fd) u8(8) else u8(8) and 0x0F
                    val bus = if (fd) u8(9) else u8(8) shr 4
                    val at = if (fd) 10 else 9
                    out += CanFrame(id, ext, msg.copyOfRange(at, at + len), bus = bus, fd = fd, timeUs = le32(0))
                    frames++
                }
                TIME_SYNC -> info.timeUs = le32(0)
                DEV_INFO -> info.build = u8(0) or (u8(1) shl 8)
                // a K-line cable echoes the question back: "F1 0C F1…" would read as 241 buses
                NUM_BUSES -> if (u8(0) in 1..8) info.buses = u8(0)
                KEEPALIVE -> if (u8(0) == 0xDE && u8(1) == 0xAD) info.keepAlive++
                CANBUS_PARAMS -> {
                    info.speeds = (0 until 2).map { b -> if (u8(b * 5) and 0x0F != 0) le32(b * 5 + 1).toInt() else null }
                    info.listenOnly = (0 until 2).map { b -> u8(b * 5) shr 4 != 0 }
                }
                GET_FD -> info.fdCapable = (0 until u8(0).coerceAtMost(8)).map { b -> u8(1 + b * 5) and 1 != 0 }
            }
        }
    }

    /** Into binary mode, then what the board is: number of buses, build, bus parameters, FD settings. */
    fun hello(): ByteArray = byteArrayOf(
        BINARY.toByte(), BINARY.toByte(),
        CMD.toByte(), NUM_BUSES.toByte(), CMD.toByte(), DEV_INFO.toByte(), CMD.toByte(), CANBUS_PARAMS.toByte(),
        CMD.toByte(), GET_FD.toByte(),
    )

    fun keepAlive(): ByteArray = byteArrayOf(CMD.toByte(), KEEPALIVE.toByte())

    /** The bus word of [SETUP_CANBUS]: [bps] (≤ 1 Mbit), enabled, [listenOnly]; 0 switches the bus off. */
    fun busWord(bps: Int, enabled: Boolean, listenOnly: Boolean): Long =
        if (!enabled) 0L else 0x80000000L or 0x40000000L or (if (listenOnly) 0x20000000L else 0L) or (bps.toLong().coerceIn(0, 1_000_000) and 0xFFFFF)

    /** F1 05, bus 0 and bus 1 words, a trailing 0 as SavvyCAN sends it. */
    fun setupBuses(bus0: Long, bus1: Long): ByteArray {
        val b = ByteArray(11)
        b[0] = CMD.toByte(); b[1] = SETUP_CANBUS.toByte()
        for (i in 0 until 4) { b[2 + i] = (bus0 shr (8 * i)).toByte(); b[6 + i] = (bus1 shr (8 * i)).toByte() }
        return b
    }

    /** [f] to the board: command 0 for a classic frame (up to 8 bytes), 20 for CAN FD; the trailing byte is 0. */
    fun frame(f: CanFrame): ByteArray {
        val fd = f.fd || f.data.size > 8
        val len = if (fd) CanFrame.fdLen(f.data.size) else f.data.size
        val b = ByteArray(8 + len + 1)
        b[0] = CMD.toByte(); b[1] = (if (fd) FD_FRAME else FRAME).toByte()
        val id = (f.id.toLong() and 0x1FFFFFFFL) or (if (f.ext) 0x80000000L else 0L)
        for (i in 0 until 4) b[2 + i] = (id shr (8 * i)).toByte()
        b[6] = (f.bus and 3).toByte()
        b[7] = (len or if (fd && f.brs) 0x80 else 0).toByte()
        f.data.copyInto(b, 8, 0, minOf(len, f.data.size))
        return b
    }
}

/**
 * A GVRET board on any [link]: put into binary mode, its buses set to [kbit] (each one listen-only unless
 * [listenOnly] says otherwise), then it streams. [buses] is what the board says it has (1 until it answers).
 */
class GvretSource(
    private val link: ByteLink,
    private val kbit: Int,
    private val listenOnly: (bus: Int) -> Boolean,
) : CanSource {
    private val decoder = Gvret.Decoder()
    private val buf = ByteArray(8192)
    private var pending = ArrayList<CanFrame>()
    private var lastKeepAlive = 0L

    val info: Gvret.Info get() = decoder.info
    override val title get() = "GVRET"
    override val errors: Int get() = decoder.dropped
    override val buses: Int get() = decoder.info.buses?.coerceIn(1, 4) ?: 1
    override val canTransmit get() = true

    /** Binary mode, the questions, the bus speeds; true when the board answered within [waitMs]. */
    fun open(waitMs: Int = 1500): Boolean {
        link.write(Gvret.hello())
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until && decoder.info.buses == null) {
            val n = link.read(buf, 50)
            if (n > 0) decoder.feed(buf, 0, n, pending)
        }
        val bps = kbit * 1000
        link.write(Gvret.setupBuses(Gvret.busWord(bps, true, listenOnly(0)),
            if ((decoder.info.buses ?: 2) > 1) Gvret.busWord(bps, true, listenOnly(1)) else 0L))
        lastKeepAlive = System.currentTimeMillis()
        return decoder.info.answered
    }

    override fun read(timeoutMs: Int): List<CanFrame> {
        val now = System.currentTimeMillis()
        // SavvyCAN asks every few seconds; a board that dropped the link answers nothing, and the session sees it
        if (now - lastKeepAlive > 3000) { lastKeepAlive = now; runCatching { link.write(Gvret.keepAlive()) } }
        val out = pending.takeIf { it.isNotEmpty() }?.also { pending = ArrayList() } ?: ArrayList()
        val n = link.read(buf, if (out.isEmpty()) timeoutMs else 1)
        if (n > 0) decoder.feed(buf, 0, n, out)
        return out
    }

    override fun transmit(f: CanFrame, permit: CanTx.Permit): Boolean {
        if (!permit.covers(f)) return false
        return runCatching { link.write(Gvret.frame(f)) }.isSuccess
    }

    override fun close() = link.close()
}
