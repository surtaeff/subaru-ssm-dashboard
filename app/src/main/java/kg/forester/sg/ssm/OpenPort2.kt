package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Tactrix OpenPort 2.0 and its Chinese clones (USB 0403:CC4B/CC4C/CC4D, CDC-ACM).
 *
 * The adapter speaks a line-oriented ASCII protocol: "ato…" opens a channel, "att…" transmits,
 * "atf…" installs a receive filter, every command ends with a sequence number that the reply
 * ("aro <seq>" / "are <err> <seq>") echoes back. Received bus frames arrive in the same stream as
 * binary records: 'a' 'r' <protocol digit> <len> <RxStatus> <timestamp:4> <data…> on CAN, without the timestamp
 * on the K-line.
 * Wire format from the reverse-engineered vendor DLL, see github.com/roffe/libj2534_openport2.
 */
class OpenPort2(private val port: UsbSerialPort) {
    private var io: SerialInputOutputManager? = null
    private val parseLock = Any()
    private var buf = ByteArray(8192)
    private var len = 0
    private val replies = LinkedBlockingQueue<String>()
    private var seq = 0
    /** K-line record headers written to the log so far this session (their layout is learned from users' logs). */
    private var rawLogged = 0
    @Volatile var ioError: Exception? = null
        private set

    /** Bus frame callback: J2534 protocol id (3 = ISO9141, 5 = CAN), RxStatus, data. */
    @Volatile var onFrame: ((Int, Int, ByteArray) -> Unit)? = null

    var firmware = ""
        private set

    fun open() {
        runCatching { port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        runCatching { port.dtr = true }
        runCatching { port.rts = true }
        // Blocking reads with a timeout, not the async ones (readTimeout 0): the adapter ends a reply of
        // exactly 64 bytes with a zero-length packet, and on an empty async read the library sends a
        // USB GET_STATUS that some head units fail ("USB get_status request failed").
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = feed(data)
            override fun onRunError(e: Exception) {
                ioError = e
            }
        }).also { it.readTimeout = 200; it.start() }
        // Drop a half-typed command from a previous session, then make sure it is an OpenPort.
        port.write("\r\n".toByteArray(), 500)
        Thread.sleep(80)
        replies.clear()
        firmware = command("ati").substringAfter(':', "").trim()
        command("ata") // close channels left open by another program
        // the battery on OBD pin 16 as the adapter sees it: without it the K-line and CAN stay dead (no echo either)
        runCatching { command("atr 16").trim().split(' ').getOrNull(2)?.toIntOrNull() }.getOrNull()?.let { mv ->
            vbatt = mv
            SsmTrace.log("OpenPort: OBD pin 16 at %.1f V".format(mv / 1000f))
        }
    }

    /** Millivolts on OBD pin 16 read at [open], or -1. */
    var vbatt = -1
        private set

    fun close() {
        runCatching { command("ata", timeoutMs = 600) }
        runCatching { io?.stop() }
        runCatching { port.close() }
    }

    /** Sends "<cmd> <seq>\r\n" (+ raw payload) and returns the matching reply line; throws on "are". */
    @Synchronized
    fun command(cmd: String, payload: ByteArray? = null, timeoutMs: Long = 1500): String {
        ioError?.let { throw IOException(it) }
        seq = if (seq >= 0xFFFF) 1 else seq + 1
        val my = seq
        val line = "$cmd $my\r\n".toByteArray()
        val out = if (payload == null) line else line + payload
        SsmTrace.log(">> $cmd $my" + (payload?.let { " + " + it.toHex(" ") } ?: ""))
        port.write(out, 1000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IOException("OpenPort: нет ответа на «$cmd»")
            val r = replies.poll(left, TimeUnit.MILLISECONDS) ?: continue
            SsmTrace.log("<< $r")
            if (r.startsWith("ari")) {
                if (cmd == "ati") return r else continue
            }
            val parts = r.substring(3).trim().split(' ').filter { it.isNotEmpty() }
            if (parts.lastOrNull()?.toIntOrNull() != my) continue // stale reply of an earlier command
            if (r[2] == 'e') throw OpenPortError(parts.firstOrNull()?.toIntOrNull() ?: -1, cmd)
            return r
        }
    }

    // ---- stream parser (runs on the USB reader thread) ----

    internal fun feed(data: ByteArray) {
        synchronized(parseLock) {
            if (len + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + data.size))
            data.copyInto(buf, len)
            len += data.size
            parse()
        }
    }

    private fun parse() {
        var pos = 0
        while (true) {
            var i = pos
            while (i + 1 < len && !(buf[i] == 'a'.code.toByte() && buf[i + 1] == 'r'.code.toByte())) i++
            if (i + 1 >= len) { pos = if (len > 0 && buf[len - 1] == 'a'.code.toByte()) len - 1 else len; break }
            if (i + 2 >= len) { pos = i; break }
            val c = buf[i + 2].toInt().toChar()
            if (c in '1'..'6') {
                if (i + 3 >= len) { pos = i; break }
                val n = buf[i + 3].toInt() and 0xFF
                if (i + 4 + n > len) { pos = i; break }
                if (n >= 1) {
                    val status = buf[i + 4].toInt() and 0xFF
                    // K-line records carry no timestamp: <RxStatus> <data…> (firmware 1.17, a GD's ROM ID came back
                    // without the first 4 bytes of every record when they were taken for one). A start/end
                    // indication with a timestamp alone (n = 5) carries no data.
                    val kline = c - '0' == ISO9141
                    if (kline && rawLogged < 12) { rawLogged++; SsmTrace.log("op2 rx record proto 3 status %02X len %d".format(status, n)) }
                    val payload = when {
                        kline && n == 5 && status and 0xC0 != 0 -> null
                        kline -> buf.copyOfRange(i + 5, i + 4 + n)
                        n >= 5 -> buf.copyOfRange(i + 9, i + 4 + n)
                        else -> null
                    }
                    if (payload != null) runCatching { onFrame?.invoke(c - '0', status, payload) }
                }
                pos = i + 4 + n
            } else {
                var nl = i + 2
                while (nl < len && buf[nl] != '\n'.code.toByte()) nl++
                if (nl >= len) { pos = i; break }
                replies.offer(String(buf, i, nl - i, Charsets.US_ASCII).trimEnd('\r'))
                pos = nl + 1
            }
        }
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, len)
            len -= pos
        }
    }

    companion object {
        const val VID = 0x0403
        val PIDS = intArrayOf(0xCC4B, 0xCC4C, 0xCC4D)
        const val ISO9141 = 3
        const val CAN = 5
        /** J2534 ERR_TIMEOUT: on a CAN transmit, nobody on the bus acknowledged the frame. */
        const val ERR_TIMEOUT = 9

        fun matches(vid: Int, pid: Int) = vid == VID && pid in PIDS
    }
}

/** An "are <code>" reply: the adapter refused [cmd] with a J2534 error code. */
class OpenPortError(val code: Int, cmd: String) : IOException("OpenPort: ошибка $code на «$cmd»")

/** SSM over the OpenPort's K-line (ISO9141 channel at 4800 baud), set up like RomRaider does. */
class Op2KLineTransport(private val op2: OpenPort2) : KLineTransport() {
    override val name = "OpenPort 2.0, K-Line"

    fun open() {
        op2.onFrame = { proto, _, data -> if (proto == OpenPort2.ISO9141 && data.isNotEmpty()) onBytes(data) }
        op2.command("ato${OpenPort2.ISO9141} 512 4800 0") // 0x200 = ISO9141_NO_CHECKSUM, we add the SSM checksum
        // P1_MAX, P3_MIN, P4_MIN, LOOPBACK, PARITY (none), DATA_BITS (8)
        listOf(7 to 1, 10 to 1, 12 to 0, 3 to 1, 22 to 0, 32 to 0).forEach { (p, v) ->
            runCatching { op2.command("ats${OpenPort2.ISO9141} $p $v") }
        }
        op2.command("atf${OpenPort2.ISO9141} 1 1 1", byteArrayOf(0, 0)) // pass everything
    }

    override fun writeRaw(packet: ByteArray) {
        op2.command("att${OpenPort2.ISO9141} ${packet.size} 0 1000000", packet, 2500)
    }

    override fun close() {
        op2.onFrame = null
        runCatching { op2.command("atc${OpenPort2.ISO9141}") }
    }
}

/**
 * SSM over CAN (Forester SH and later): 500 kbit/s, ECU 7E0/7E8, TCU 7E1/7E9, the SSM command
 * goes in ISO 15765 (ISO-TP) frames without the K-line header and checksum. The adapter's raw CAN
 * channel is used and ISO-TP is done here, so no flow-control filter setup is needed.
 */
class Op2CanTransport(private val op2: OpenPort2) : SsmTransport {
    override val name = "OpenPort 2.0, CAN"
    override val isCan = true
    private val rx = LinkedBlockingQueue<ByteArray>()

    /** [listen]: everything on the bus comes in, the broadcast frames go to [CanBroadcast]. */
    fun open(listen: Boolean = false) {
        op2.onFrame = { proto, _, data ->
            if (proto == OpenPort2.CAN && data.size >= 5) {
                val id = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                    ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                if (id in 0x7E0..0x7EF || id in extraRx) rx.offer(data) else if (listen) CanBroadcast.feed(id, data.copyOfRange(4, data.size))
            }
        }
        op2.command("ato${OpenPort2.CAN} 0 500000 0")
        if (listen) {
            // mask 0: every frame on the bus
            op2.command("atf${OpenPort2.CAN} 1 4 4", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        } else {
            // mask 0x7F0 / pattern 0x7E0: only the diagnostic answers 7E0…7EF
            op2.command("atf${OpenPort2.CAN} 1 4 4", byteArrayOf(0, 0, 0x07, 0xF0.toByte(), 0, 0, 0x07, 0xE0.toByte()))
        }
    }

    /** Answer ids beyond 7E0…7EF asked for by [exchangeId], each with a receive filter of its own. */
    private val extraRx = java.util.concurrent.CopyOnWriteArraySet<Int>()

    @Synchronized
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        val txId = if (dest == SsmClient.TCU) 0x7E1 else 0x7E0
        return exchangeId(txId, txId + 8, body)
    }

    /** [body] to any unit on 11-bit CAN ([txId], answering from [rxId]): the manufacturer PIDs of OBDb. */
    @Synchronized
    fun exchangeId(txId: Int, rxId: Int, body: ByteArray): ByteArray? {
        if (rxId !in 0x7E0..0x7EF && rxId !in extraRx) {
            runCatching {
                op2.command("atf${OpenPort2.CAN} 1 4 4", byteArrayOf(0, 0, 0x07, 0xFF.toByte(), 0, 0, (rxId shr 8).toByte(), rxId.toByte()))
            }.onFailure { SsmTrace.log("CAN filter %03X: $it".format(rxId)); return null }
            extraRx += rxId
        }
        rx.clear()
        if (!sendIsoTp(txId, rxId, body)) return null
        val deadline = System.currentTimeMillis() + 1500
        while (true) {
            val resp = receiveIsoTp(txId, rxId, deadline) ?: run { SsmTrace.log("CAN timeout"); return null }
            // 7F xx 78 = "busy, answer follows"; any other 7F is a refusal
            if (resp.size >= 3 && resp[0] == 0x7F.toByte() && resp[2] == 0x78.toByte()) continue
            if (resp.isNotEmpty() && resp[0] == 0x7F.toByte()) return null
            return resp
        }
    }

    /**
     * False when the frame went unacknowledged: no CAN on this OBD socket (K-line cars such as the
     * GD/SG), the same as an ECU that stays silent, so "Авто" goes on with the K-line and OBD-II.
     */
    private fun sendFrame(id: Int, data: ByteArray): Boolean {
        val f = ByteArray(12)
        f[2] = (id shr 8).toByte(); f[3] = id.toByte()
        data.copyInto(f, 4, 0, minOf(8, data.size))
        SsmTrace.log("CAN TX %03X".format(id), f.copyOfRange(4, 12))
        try {
            op2.command("att${OpenPort2.CAN} 12 0 100000", f, 1500)
        } catch (e: OpenPortError) {
            if (e.code != OpenPort2.ERR_TIMEOUT) throw e
            SsmTrace.log("CAN: кадр никто не подтвердил, на разъёме нет CAN")
            return false
        }
        return true
    }

    private fun nextFrame(id: Int, deadline: Long): ByteArray? {
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val f = rx.poll(left, TimeUnit.MILLISECONDS) ?: return null
            val fid = ((f[0].toInt() and 0xFF) shl 24) or ((f[1].toInt() and 0xFF) shl 16) or
                ((f[2].toInt() and 0xFF) shl 8) or (f[3].toInt() and 0xFF)
            if (fid != id) continue
            val d = f.copyOfRange(4, f.size)
            SsmTrace.log("CAN RX %03X".format(id), d)
            return d
        }
    }

    private fun sendIsoTp(txId: Int, rxId: Int, body: ByteArray): Boolean {
        if (body.size <= 7) return sendFrame(txId, byteArrayOf(body.size.toByte()) + body)
        if (!sendFrame(txId, byteArrayOf((0x10 or (body.size shr 8)).toByte(), body.size.toByte()) + body.copyOfRange(0, 6))) return false
        var pos = 6
        var sn = 1
        while (pos < body.size) {
            // flow control from the unit: 30 = go on, 31 = wait, 32 = overflow
            var fc: ByteArray
            do {
                fc = nextFrame(rxId, System.currentTimeMillis() + 1000) ?: return false
            } while ((fc[0].toInt() and 0xF0) != 0x30 || (fc[0].toInt() and 0x0F) == 1)
            if ((fc[0].toInt() and 0x0F) != 0) return false
            val blockSize = fc[1].toInt() and 0xFF
            val stMin = (fc[2].toInt() and 0xFF).let { if (it <= 0x7F) it.toLong() else 1L }
            var sent = 0
            while (pos < body.size && (blockSize == 0 || sent < blockSize)) {
                val end = minOf(pos + 7, body.size)
                if (!sendFrame(txId, byteArrayOf((0x20 or (sn and 0x0F)).toByte()) + body.copyOfRange(pos, end))) return false
                pos = end; sn++; sent++
                if (stMin > 0) Thread.sleep(stMin)
            }
        }
        return true
    }

    private fun receiveIsoTp(txId: Int, rxId: Int, deadline: Long): ByteArray? {
        val first = nextFrame(rxId, deadline) ?: return null
        when (first[0].toInt() and 0xF0) {
            0x00 -> {
                val n = first[0].toInt() and 0x0F
                return first.copyOfRange(1, minOf(1 + n, first.size))
            }
            0x10 -> {
                val n = ((first[0].toInt() and 0x0F) shl 8) or (first[1].toInt() and 0xFF)
                val out = java.io.ByteArrayOutputStream(n)
                out.write(first, 2, first.size - 2)
                if (!sendFrame(txId, byteArrayOf(0x30, 0, 0))) return null // send everything, no pauses
                while (out.size() < n) {
                    val cf = nextFrame(rxId, deadline) ?: return null
                    if ((cf[0].toInt() and 0xF0) != 0x20) continue
                    out.write(cf, 1, cf.size - 1)
                }
                return out.toByteArray().copyOf(n)
            }
            else -> return null
        }
    }

    override fun close() {
        op2.onFrame = null
        runCatching { op2.command("atc${OpenPort2.CAN}") }
    }
}
