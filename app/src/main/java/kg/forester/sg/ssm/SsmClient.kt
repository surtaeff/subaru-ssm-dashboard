package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

data class EcuInit(
    val ssmId: String,
    val romId: String,
    val capabilities: ByteArray,
) {
    fun supports(ecuByteIndex: Int, ecuBit: Int): Boolean {
        val i = ecuByteIndex - 8
        if (i < 0 || i >= capabilities.size) return false
        return (capabilities[i].toInt() shr ecuBit) and 1 == 1
    }
}

/**
 * Carries one SSM command to a control unit and returns its answer (starting with the response code,
 * e.g. E8 for A8). The SSM commands are the same on K-line and CAN, only the framing differs.
 */
interface SsmTransport {
    /** Shown in the status line, e.g. "KKL, K-Line". */
    val name: String
    /** CAN units answer the init with 0xAA/0xEA instead of 0xBF/0xFF. */
    val isCan: Boolean
    /**
     * Another tester (a laptop on a second cable on the same K-line) is talking to the ECU: we must not, or the
     * requests collide. Meanwhile [sniffed] gives what the ECU answered it.
     */
    val passive: Boolean get() = false
    /** The latest values the ECU sent the other tester for any of [addrs], at most [maxAgeMs] old: (addresses, bytes). */
    fun sniffed(addrs: IntArray, maxAgeMs: Long): Pair<IntArray, ByteArray>? = null
    /** Addresses per read request (an ELM327 fits only one into its single frame). */
    val maxReadAddrs: Int get() = 64
    /**
     * States of the cable's control lines worth trying when the ECU stays silent: KKL cables with a chip
     * inside (VAG K+CAN Commander and the like) only pass the K-line through with DTR/RTS set their way.
     */
    val lineModes: Int get() = 1
    fun setLineMode(i: Int) {}
    fun exchange(dest: Int, body: ByteArray): ByteArray?
    fun close()
}

/**
 * K-line SSM2 framing: 80 <dest> F0 <len> <body> <checksum>, the answer comes back as 80 F0 <dest> …
 *
 * Every byte on the line is also read as a stream of frames, so a second tester on the same K-line (a laptop with its
 * own cable, for diagnostics) shows up: its requests are not ours. Then we fall silent ([passive]) and keep the
 * values the ECU sends it ([sniffed]); when it has been quiet for a few seconds we ask again ourselves.
 */
abstract class KLineTransport : SsmTransport {
    private val inbox = java.io.ByteArrayOutputStream()
    private val lock = Object()
    @Volatile protected var ioError: Exception? = null
    override val isCan = false

    protected abstract fun writeRaw(packet: ByteArray)

    // ---- the line as a whole
    private val stream = java.io.ByteArrayOutputStream()
    /** Our request on its way: its echo is ours, any other request is someone else's. */
    @Volatile private var sending: ByteArray? = null
    @Volatile private var lastForeignMs = 0L
    /** What the other tester asked last, per unit: the addresses whose values come back in that order. */
    private val pending = HashMap<Int, IntArray>()
    /** ECU memory as the other tester's answers showed it: address → (value, when). */
    private val seen = java.util.concurrent.ConcurrentHashMap<Int, Pair<Byte, Long>>()
    /** The ECU's init answer to the other tester (its ROM ID), if it asked. */
    @Volatile var sniffedInit: ByteArray? = null
        private set
    /** The ECU answering one request over and over by itself (see [startStream]): each answer's body goes here. */
    @Volatile private var streamSink: ((ByteArray) -> Unit)? = null
    @Volatile private var lastRxMs = 0L

    /** The ECU is sending its answers by itself; any other request stops that (see [exchange]). */
    val streaming: Boolean get() = streamSink != null

    override val passive: Boolean get() = System.currentTimeMillis() - lastForeignMs < QUIET_MS

    override fun sniffed(addrs: IntArray, maxAgeMs: Long): Pair<IntArray, ByteArray>? {
        val now = System.currentTimeMillis()
        val got = addrs.filter { a -> seen[a]?.let { now - it.second <= maxAgeMs } == true }
        if (got.isEmpty()) return null
        return got.toIntArray() to ByteArray(got.size) { seen.getValue(got[it]).first }
    }

    /** Bytes received from the line (with or without the echo of our own request). */
    protected fun onBytes(data: ByteArray) {
        synchronized(lock) {
            lastRxMs = System.currentTimeMillis()
            // while the ECU streams nobody waits in [exchange]: the answers would only pile up
            if (streamSink == null) inbox.write(data)
            stream.write(data)
            scan()
        }
    }

    /** Whole frames out of [stream]: a request of another tester marks the line busy, the ECU's answer to it is kept. */
    private fun scan() {
        val b = stream.toByteArray()
        var i = 0
        while (true) {
            while (i < b.size && b[i] != 0x80.toByte()) i++
            if (b.size - i < 4) break
            val total = 5 + (b[i + 3].toInt() and 0xFF)
            if (b.size - i < total) break
            if (checksum(b.copyOfRange(i, i + total), total - 1) != b[i + total - 1]) { i++; continue }
            frame(b.copyOfRange(i, i + total))
            i += total
        }
        stream.reset()
        // keep at most one frame's worth of an unfinished tail
        if (i < b.size) stream.write(b, i, minOf(b.size - i, 300))
    }

    private fun frame(f: ByteArray) {
        val dst = f[1].toInt() and 0xFF
        val src = f[2].toInt() and 0xFF
        val body = f.copyOfRange(4, f.size - 1)
        if (body.isEmpty()) return
        val now = System.currentTimeMillis()
        if (src == 0xF0 && (dst == SsmClient.ECU || dst == SsmClient.TCU)) {
            val mine = sending
            if (mine != null && mine.contentEquals(f)) { sending = null; return } // our own echo
            lastForeignMs = now
            when (body[0].toInt() and 0xFF) {
                // A8 00 aaa aaa …: single addresses
                0xA8 -> pending[dst] = IntArray((body.size - 2) / 3) { k ->
                    ((body[2 + k * 3].toInt() and 0xFF) shl 16) or ((body[3 + k * 3].toInt() and 0xFF) shl 8) or (body[4 + k * 3].toInt() and 0xFF)
                }
                // A0 00 aaa nn: a block of nn + 1 bytes
                0xA0 -> if (body.size >= 6) {
                    val a = ((body[2].toInt() and 0xFF) shl 16) or ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
                    pending[dst] = IntArray((body[5].toInt() and 0xFF) + 1) { a + it }
                }
                else -> pending.remove(dst)
            }
            if (lastForeignMs - lastLoggedMs > 30_000) { lastLoggedMs = lastForeignMs; SsmTrace.log("another tester on the K-line: listening", f) }
            return
        }
        if (dst == 0xF0 && src == SsmClient.ECU) {
            when (body[0].toInt() and 0xFF) {
                0xFF -> if (now - lastForeignMs < QUIET_MS) sniffedInit = body
                0xE8, 0xE0 -> {
                    if (now - lastForeignMs >= QUIET_MS) { streamSink?.invoke(body); return }
                    // an answer to the other tester, not to us
                    val asked = pending.remove(SsmClient.ECU) ?: return
                    for (k in asked.indices) if (1 + k < body.size) seen[asked[k]] = body[1 + k] to now
                }
            }
        }
    }

    private var lastLoggedMs = 0L

    private fun packet(dest: Int, body: ByteArray): ByteArray {
        val packet = ByteArray(body.size + 5)
        packet[0] = 0x80.toByte()
        packet[1] = dest.toByte()
        packet[2] = 0xF0.toByte()
        packet[3] = body.size.toByte()
        body.copyInto(packet, 4)
        packet[packet.size - 1] = checksum(packet, packet.size - 1)
        return packet
    }

    /**
     * Sends a read request with the continuous flag (A8 01, RomRaider's «fast poll»): the ECU then answers it again
     * and again without waiting to be asked, about three times as often as request and answer in turn. Each answer's
     * body (E8 …) goes to [sink] until [stopStream] or any other request.
     */
    @Synchronized
    fun startStream(dest: Int, body: ByteArray, sink: (ByteArray) -> Unit): Boolean {
        if (passive) return false
        ioError?.let { throw IOException(it) }
        val packet = packet(dest, body)
        synchronized(lock) { inbox.reset() }
        SsmTrace.log("TX stream", packet)
        streamSink = sink
        sending = packet
        writeRaw(packet)
        return true
    }

    /**
     * Stops the ECU's stream: any request does it. Then waits for the line to go quiet, so an answer still on its way
     * is not taken for the next request's. The request may collide with an answer on the single wire, so it is sent
     * again while the ECU keeps talking.
     */
    @Synchronized
    fun stopStream() {
        if (streamSink == null) return
        streamSink = null
        // another tester's request has already stopped it, and ours would garble his
        if (passive) return
        ioError?.let { throw IOException(it) }
        val stop = packet(SsmClient.ECU, byteArrayOf(0xA8.toByte(), 0x00))
        repeat(3) {
            sending = stop
            writeRaw(stop)
            val deadline = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(15)
                if (System.currentTimeMillis() - lastRxMs > 120) {
                    synchronized(lock) { inbox.reset(); stream.reset() }
                    SsmTrace.log("stream stopped")
                    return
                }
            }
        }
        synchronized(lock) { inbox.reset() }
        SsmTrace.log("stream: the ECU keeps sending")
    }

    @Synchronized
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        // someone else has the line: asking now would garble both
        if (passive) return null
        // the ECU streams (the trouble codes screen, the transmission asking in between): stop it first
        stopStream()
        val packet = packet(dest, body)

        ioError?.let { throw IOException(it) }
        synchronized(lock) { inbox.reset() }
        SsmTrace.log("TX", packet)
        sending = packet
        writeRaw(packet)

        // 2 s like RomRaider and FreeSSM: older (16-bit) ECUs can be slow with the first answer
        val deadline = System.currentTimeMillis() + 2000
        var all = ByteArray(0)
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
            all = synchronized(lock) { inbox.toByteArray() }
            val resp = stripEcho(all, packet) ?: continue
            if (resp.size >= 4) {
                val start = indexOfHeader(resp, dest)
                if (start < 0) continue
                val len = resp[start + 3].toInt() and 0xFF
                val total = 4 + len + 1
                if (resp.size - start >= total) {
                    val frame = resp.copyOfRange(start, start + total)
                    SsmTrace.log("RX", all)
                    if (checksum(frame, total - 1) != frame[total - 1]) {
                        SsmTrace.log("bad checksum")
                        return null
                    }
                    return frame.copyOfRange(4, 4 + len)
                }
            }
        }
        SsmTrace.log("timeout, got ${all.size} bytes: ${all.toHex(" ")}")
        if (all.size >= packet.size && all.copyOf(packet.size).contentEquals(packet)) heardEcho = true
        return null
    }

    /**
     * Our own request came back off the line since the last [heardEcho] = false: the cable sits on a K-line with 12 V on
     * it (the ignition on). A head unit's own USB-serial chip, or the line with the ignition off, gives nothing back.
     */
    @Volatile var heardEcho = false

    private fun stripEcho(all: ByteArray, packet: ByteArray): ByteArray? {
        if (all.size < packet.size) return null
        for (i in packet.indices) if (all[i] != packet[i]) return all // no echo (cable without loopback)
        return all.copyOfRange(packet.size, all.size)
    }

    private fun indexOfHeader(b: ByteArray, dest: Int): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 0x80.toByte() && b[i + 1] == 0xF0.toByte() && b[i + 2] == dest.toByte()) return i
        }
        return -1
    }

    companion object {
        /** The other tester quiet this long: the line is ours again. */
        const val QUIET_MS = 3000L

        fun checksum(b: ByteArray, len: Int): Byte {
            var s = 0
            for (i in 0 until len) s += b[i].toInt() and 0xFF
            return s.toByte()
        }
    }
}

/** Tactrix OpenPort 1.3 (M, S and U versions): an FTDI K-line cable with Tactrix product ids. */
object OpenPort13 {
    const val VID = 0x0403
    val PIDS = intArrayOf(0xCC48, 0xCC49, 0xCC4A)

    fun matches(vid: Int, pid: Int) = vid == VID && pid in PIDS
}

/**
 * Plain USB KKL cable (CH340/FTDI/PL2303/CP210x) or OpenPort 1.3: the K-line is the serial port itself, 4800 8N1.
 * DTR and RTS start on (RTS off for the OpenPort 1.3, as RomRaider does); the other combinations are tried
 * while the ECU stays silent, both off being what Windows programs leave them at.
 */
class KklTransport(private val port: UsbSerialPort, private val openPort13: Boolean = false) : KLineTransport() {
    private val modes = if (openPort13) listOf(true to false, true to true, false to false, false to true)
    else listOf(true to true, false to false, true to false, false to true)
    private var mode = 0
    override val name: String
        get() = (if (openPort13) "OpenPort 1.3, K-Line" else "KKL, K-Line") +
            if (mode == 0) "" else modes[mode].let { (d, r) -> ", DTR ${if (d) 1 else 0} RTS ${if (r) 1 else 0}" }
    override val lineModes get() = modes.size
    private var io: SerialInputOutputManager? = null

    override fun setLineMode(i: Int) {
        mode = i.mod(modes.size)
        val (d, r) = modes[mode]
        runCatching { port.dtr = d }
        runCatching { port.rts = r }
        SsmTrace.log("lines: DTR ${if (d) 1 else 0} RTS ${if (r) 1 else 0}")
    }

    fun open() {
        port.setParameters(4800, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        setLineMode(0)
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = onBytes(data)
            override fun onRunError(e: Exception) {
                ioError = e
            }
        }).also { it.readTimeout = 0; it.start() }
    }

    override fun writeRaw(packet: ByteArray) = port.write(packet, 1000)

    /** Stops reading; the port itself is closed by whoever opened it. */
    override fun close() {
        runCatching { io?.stop() }
    }
}

class SsmClient(val transport: SsmTransport) {

    fun init(dest: Int = ECU): EcuInit? {
        val (cmd, ok) = if (transport.isCan) 0xAA to 0xEA else 0xBF to 0xFF
        // the engine unit gets one more try on K-line: just woken up, it may let the first request pass unanswered
        // (not the transmission: its retries every minute would freeze the gauges twice as long)
        val data = transport.exchange(dest, byteArrayOf(cmd.toByte()))
            ?: (if (transport.isCan || dest != ECU) null else transport.exchange(dest, byteArrayOf(cmd.toByte())))
            ?: return null
        if (data.size < 9 || data[0] != ok.toByte()) return null
        return EcuInit(
            ssmId = data.copyOfRange(1, 4).toHex(),
            romId = data.copyOfRange(4, 9).toHex(),
            capabilities = data.copyOfRange(9, data.size),
        )
    }

    fun read(addresses: IntArray, dest: Int = ECU): ByteArray? {
        val step = transport.maxReadAddrs.coerceAtLeast(1)
        if (addresses.size <= step) return readChunk(addresses, dest)
        val out = java.io.ByteArrayOutputStream(addresses.size)
        for (i in addresses.indices step step) {
            out.write(readChunk(addresses.copyOfRange(i, minOf(i + step, addresses.size)), dest) ?: return null)
        }
        return out.toByteArray()
    }

    private fun readChunk(addresses: IntArray, dest: Int): ByteArray? {
        val data = transport.exchange(dest, readBody(addresses, 0x00)) ?: return null
        if (data.isEmpty() || data[0] != 0xE8.toByte() || data.size < 1 + addresses.size) return null
        return data.copyOfRange(1, 1 + addresses.size)
    }

    /** A8 <mode> aaa aaa …: mode 0 is one answer, 1 the continuous one. */
    private fun readBody(addresses: IntArray, mode: Int): ByteArray {
        val body = ByteArray(2 + addresses.size * 3)
        body[0] = 0xA8.toByte()
        body[1] = mode.toByte()
        addresses.forEachIndexed { i, a ->
            body[2 + i * 3] = (a shr 16).toByte()
            body[3 + i * 3] = (a shr 8).toByte()
            body[4 + i * 3] = a.toByte()
        }
        return body
    }

    /**
     * K-line only: the engine unit answers [addresses] over and over by itself (see [KLineTransport.startStream]),
     * [onValues] gets the bytes of each answer in their order. False when the cable can't or the list is too long
     * for one request.
     */
    fun startStream(addresses: IntArray, onValues: (ByteArray) -> Unit): Boolean {
        val kt = transport as? KLineTransport ?: return false
        if (addresses.isEmpty() || addresses.size > kt.maxReadAddrs) return false
        return kt.startStream(ECU, readBody(addresses, 0x01)) { b ->
            if (b.size >= 1 + addresses.size && b[0] == 0xE8.toByte()) onValues(b.copyOfRange(1, 1 + addresses.size))
        }
    }

    fun stopStream() = (transport as? KLineTransport)?.stopStream()

    /** The stream still runs: no other request has stopped it. */
    val streaming: Boolean get() = (transport as? KLineTransport)?.streaming == true

    fun write(address: Int, value: Int, dest: Int = ECU): Int? {
        val body = byteArrayOf(
            0xB8.toByte(),
            (address shr 16).toByte(), (address shr 8).toByte(), address.toByte(),
            value.toByte(),
        )
        val data = transport.exchange(dest, body) ?: return null
        if (data.size < 2 || data[0] != 0xF8.toByte()) return null
        return data[1].toInt() and 0xFF
    }

    fun close() = transport.close()

    companion object {
        const val ECU = 0x10
        const val TCU = 0x18
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
