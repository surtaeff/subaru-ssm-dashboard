package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/**
 * SSM1 — the pre-1999 Subaru diagnostics protocol (16-bit JECS ECUs on an L-series/XT, SVX, early Legacy Turbo,
 * EJ18/EJ20G/EJ22 SF/GC/GF, …). 1953 baud 8N1 on a K-line, where SSM2 is 4800. Not framed: one request byte on
 * the wire picks an address, then the ECU streams that address's byte over and over until stopped. Addresses
 * are swapped one at a time.
 *
 * ELM327 adapters cannot do 1953 baud, OpenPort 2.0 would need its own baud override: this client is for a plain
 * FTDI/CH340/CP210x KKL cable and OpenPort 1.3 only.
 *
 * Sources: FreeSSM (GPL, https://github.com/Comer352L/FreeSSM) src/SSMP1*.cpp. All timings and the GET_ID
 * acceptance rules are from that reference — their heuristics around stray bytes and buffer-overflow sync come
 * from testing on real cars, no reason to reinvent them.
 */
class SsmP1Client(private val port: UsbSerialPort) {

    private val inbox = java.io.ByteArrayOutputStream()
    private val lock = Object()
    @Volatile private var io: SerialInputOutputManager? = null
    @Volatile private var ioError: Exception? = null

    fun open() {
        try {
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            throw IOException("SSM1: кабель не берёт скорость 1953 бод", e)
        }
        runCatching { port.dtr = true }
        runCatching { port.rts = true }
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) { synchronized(lock) { inbox.write(data) } }
            override fun onRunError(e: Exception) { ioError = e }
        }).also { it.readTimeout = 0; it.start() }
    }

    fun close() {
        runCatching { io?.stop() }
        io = null
    }

    private fun clear() { synchronized(lock) { inbox.reset() } }

    private fun snapshot(): ByteArray = synchronized(lock) { inbox.toByteArray() }

    private fun send(bytes: ByteArray) {
        ioError?.let { throw IOException(it) }
        port.write(bytes, 1000)
    }

    /** Sends GET_ID (0x00) + address + extralen. The address is the standard SSM1 ID query location, 0x0000 for ECU. */
    private fun sendGetId(addr: Int, extralen: Int) =
        send(byteArrayOf(CMD_GET_ID.toByte(), ((addr shr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(), extralen.toByte()))

    private fun sendStopTalking() =
        send(byteArrayOf(CMD_STOP_READING.toByte(), 0, 0, 0))

    /** Sends READ_ENGINE/… (3-byte command for AirCon/4WS, 4-byte for the others, padded to 4 with 0x00). */
    private fun sendReadAddress(cmd: Int, addr: Int) {
        val three = cmd == CMD_READ_AIRCON || cmd == CMD_READ_4WS
        send(if (three) byteArrayOf(cmd.toByte(), ((addr shr 8) and 0xFF).toByte(), (addr and 0xFF).toByte())
             else byteArrayOf(cmd.toByte(), ((addr shr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(), 0))
    }

    /**
     * The unit's 3-byte ROM id, or null. Two flavours, as FreeSSM's getID takes them:
     * - `7x yy zz` (the 1987–96 units): the id streams over and over, a repeat confirms it;
     * - `Ax 01 zz` / `Ax 10 zz` (the 1997–98 JECS units: A30113 on the GC8 STi and Impreza GT, A30117 on the Forester):
     *   the id comes once, followed by data, so two queries that give the same three bytes confirm it.
     * The query is the one the Select Monitor sends (00 46 48 49, after a read to start the stream; confirmed on a
     * GC8 by P1kachu/ssm1-gc8), then FreeSSM's (00 00 00 00) for a unit that ignores that one.
     */
    fun getId(): ByteArray? {
        for ((addr, extra) in listOf(0x4648 to 0x49, 0x0000 to 0x00)) {
            val a = queryId(addr, extra) ?: continue
            if (a[0].toInt() and 0xF0 == 0x70) return a // already confirmed by its repeat
            val b = queryId(addr, extra) ?: continue
            if (a.contentEquals(b)) return a
        }
        return null
    }

    private fun queryId(addr: Int, extra: Int): ByteArray? {
        runCatching { sendStopTalking() }
        Thread.sleep(T_IC_WAIT_MS)
        sendReadAddress(CMD_READ_ENGINE, 0x0000)
        Thread.sleep(T_IC_WAIT_MS)
        clear()
        sendGetId(addr, extra)
        val deadline = System.currentTimeMillis() + T_ID_TOTAL_MS
        var found: ByteArray? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            val r = extractId(snapshot()) ?: continue
            if (r.second) return r.first
            found = r.first
        }
        return found
    }


    /**
     * Reads one memory byte at [addr] by switching the ECU's stream to that address and taking the first stable
     * 3-byte block `<hb> <lb> <data>` seen at least twice in a row. Returns null on a switch timeout.
     *
     * [cmd] is one of CMD_READ_ENGINE / CMD_READ_TRANSMISSION / ...; the engine ECU is the usual one.
     * This changes the ECU's streaming address, so to read many values call it in a round-robin over them.
     */
    fun readByte(addr: Int, cmd: Int = CMD_READ_ENGINE, timeoutMs: Long = T_READ_TIMEOUT_MS): Int? {
        sendReadAddress(cmd, addr)
        Thread.sleep(T_IC_WAIT_MS)
        clear()
        val hb = ((addr shr 8) and 0xFF).toByte()
        val lb = (addr and 0xFF).toByte()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
            val b = snapshot()
            if (b.size < 6) continue
            // find `hb lb ?` and confirm the same `hb lb ?` follows: if both `?` match, that byte is the value
            for (k in 0..b.size - 6) {
                if (b[k] == hb && b[k + 1] == lb && b[k + 3] == hb && b[k + 4] == lb && b[k + 2] == b[k + 5]) {
                    return b[k + 2].toInt() and 0xFF
                }
            }
        }
        return null
    }

    /** Sends STOP_READING (0x12). The ECU stops streaming after the current message. */
    fun stop() { runCatching { sendStopTalking() } }

    companion object {
        /**
         * FreeSSM's acceptance, from the front of [buf] (at most MAX_BYTES_DROPPED stray bytes skipped): a `7x` head
         * whose three bytes repeat (true: confirmed), or an `Ax 01|10` head (false: one sighting, the caller asks again).
         */
        internal fun extractId(buf: ByteArray): Pair<ByteArray, Boolean>? {
            for (i in 0 until minOf(buf.size, MAX_BYTES_DROPPED + 1)) {
                val h = buf[i].toInt() and 0xF0
                if (h == 0x70 && buf.size - i >= 6) {
                    val id = buf.copyOfRange(i, i + 3)
                    for (j in i + 3..buf.size - 3) if (buf[j] == id[0] && buf[j + 1] == id[1] && buf[j + 2] == id[2]) return id to true
                } else if (h == 0xA0 && buf.size - i >= 3 && (buf[i + 1].toInt() == 0x01 || buf[i + 1].toInt() == 0x10)) {
                    return buf.copyOfRange(i, i + 3) to false
                }
            }
            return null
        }

        const val BAUD = 1953

        // Command bytes (FreeSSM src/SSMP1base.h)
        const val CMD_GET_ID = 0x00
        const val CMD_STOP_READING = 0x12
        const val CMD_READ_ENGINE = 0x78
        const val CMD_READ_TRANSMISSION = 0x45
        const val CMD_READ_CRUISECONTROL = 0xCC
        const val CMD_READ_AIRCON = 0x89
        const val CMD_READ_AIRCON2 = 0xAC
        const val CMD_READ_4WS = 0x92
        const val CMD_READ_ABS = 0xAB
        const val CMD_READ_AIRSUSP = 0x66
        const val CMD_READ_POWERSTEERING = 0x95
        const val CMD_WRITE = 0xAA

        // Timings (FreeSSM src/SSMP1base.h and procedures)
        const val T_IC_WAIT_MS = 40L
        const val T_ID_RECSTART_MAX_MS = 300L
        /** Total time for GET_ID: FreeSSM uses (T_ID_RECSTART_MAX + 1000*3*8/1953)*2 ≈ 630 ms. */
        const val T_ID_TOTAL_MS = 700L
        /** Max stray bytes to drop while waiting for the ID head byte. */
        const val MAX_BYTES_DROPPED = 15
        /** A single memory byte: 1953 baud, 3 bytes per block ≈ 65/s, so 400 ms catches several. */
        const val T_READ_TIMEOUT_MS = 500L
    }
}
