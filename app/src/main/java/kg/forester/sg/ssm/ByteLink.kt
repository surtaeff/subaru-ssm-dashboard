package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A byte pipe to a CAN adapter, whatever carries it: a USB serial port, a Bluetooth socket, a TCP connection, a BLE
 * UART service. The RealDash CAN and GVRET readers work on this, so each protocol runs on every link.
 */
interface ByteLink : Closeable {
    /** Up to [buf].size bytes within [timeoutMs]; 0 when nothing came. Throws [IOException] once the link is gone. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun write(b: ByteArray)
    /** A serial port's speed; nothing on a link that has none. */
    fun setBaud(baud: Int) {}
    /** What the link is, for the trace: "USB 1A86:7523", "Bluetooth OBDLink", "Wi-Fi 192.168.4.1:23". */
    val label: String

    /** Reads and drops what is waiting (an old answer, the text a GVRET prints at start). */
    fun drain(ms: Int = 100) {
        val b = ByteArray(1024)
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) if (read(b, 20) <= 0) break
    }
}

/** A USB serial port (the port is opened and closed by whoever owns the USB device). */
class UsbByteLink(private val port: UsbSerialPort, override val label: String) : ByteLink {
    override fun read(buf: ByteArray, timeoutMs: Int): Int = port.read(buf, timeoutMs.coerceAtLeast(1)).coerceAtLeast(0)
    override fun write(b: ByteArray) = port.write(b, 500)
    override fun setBaud(baud: Int) {
        runCatching { port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        // CDC-ACM boards (and the ESP32's bridge) send nothing until the host raises DTR; on an ESP32 RTS is its reset
        runCatching { port.dtr = true }
    }
    override fun close() {}
}

/**
 * A pair of streams (a Bluetooth SPP socket, a TCP socket): a reader thread takes what comes in chunks, so [read] can
 * wait with a timeout on streams that have none. [closeable] is closed with the link.
 */
class StreamByteLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable?,
    override val label: String,
) : ByteLink {
    private val chunks = ArrayBlockingQueue<ByteArray>(4096)
    private var pending: ByteArray? = null
    private var pendingAt = 0
    @Volatile private var error: IOException? = null
    @Volatile private var closed = false

    private val reader = Thread({
        val b = ByteArray(4096)
        try {
            while (!closed) {
                val n = input.read(b)
                if (n < 0) throw IOException("closed by the adapter")
                // a reader too slow for the adapter loses the oldest chunk, never blocks the socket
                if (n > 0 && !chunks.offer(b.copyOf(n))) { chunks.poll(); chunks.offer(b.copyOf(n)) }
            }
        } catch (e: IOException) {
            if (!closed) error = e
        }
    }, "can link reader").also { it.isDaemon = true; it.start() }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var p = pending
        if (p == null) {
            error?.let { if (chunks.isEmpty()) throw it }
            p = chunks.poll(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: run {
                error?.let { throw it }
                return 0
            }
            pending = p; pendingAt = 0
        }
        val n = minOf(buf.size, p.size - pendingAt)
        System.arraycopy(p, pendingAt, buf, 0, n)
        pendingAt += n
        if (pendingAt >= p.size) pending = null
        return n
    }

    override fun write(b: ByteArray) {
        error?.let { throw it }
        output.write(b)
        output.flush()
    }

    override fun close() {
        closed = true
        runCatching { closeable?.close() }
        runCatching { input.close() }
        runCatching { output.close() }
        reader.interrupt()
    }
}
