package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable

/**
 * SLCAN (the Lawicel text protocol): what a CANable, a CANtact and most cheap USB-CAN sticks speak over their USB
 * serial. The app only listens: a sports ECU (Link, Haltech, ECUMaster…) broadcasts its dash stream by itself.
 * Lines end with CR: "S6" sets 500 kbit, "O" opens the bus, "C" closes it; frames come as "t" + 3 hex id + length +
 * data ("T" + 8 hex id for an extended one), sometimes with a 4-hex time stamp after the data.
 */
object Slcan {
    class Frame(val id: Int, val ext: Boolean, val data: ByteArray)

    /** CANable (slcan firmware), the CANtact it came from, and anything whose USB name says it speaks slcan. */
    fun matches(vid: Int, pid: Int, name: String?): Boolean =
        (vid == 0xAD50 && pid == 0x60C4) || (vid == 0x16D0 && pid == 0x117E) ||
            (name != null && listOf("canable", "cantact", "slcan").any { name.contains(it, ignoreCase = true) })

    /** candleLight firmware (gs_usb): a CANable too, but not a serial port; Android can't open it without a driver of its own. */
    fun isCandleLight(vid: Int, pid: Int) = vid == 0x1D50 && pid == 0x606F

    val SPEEDS = listOf(125, 250, 500, 1000)

    /** The "S" command's digit for [kbit]. */
    fun speedCode(kbit: Int): Char = when (kbit) {
        10 -> '0'; 20 -> '1'; 50 -> '2'; 100 -> '3'; 125 -> '4'; 250 -> '5'; 750 -> '7'; 1000 -> '8'
        else -> '6'
    }

    /** One line without its CR; null for anything but a data frame (OK, error, a remote request). */
    fun parse(line: String): Frame? {
        if (line.isEmpty()) return null
        val ext = when (line[0]) { 't' -> false; 'T' -> true; else -> return null }
        val idLen = if (ext) 8 else 3
        if (line.length < 1 + idLen + 1) return null
        val id = line.substring(1, 1 + idLen).toIntOrNull(16) ?: return null
        val len = line[1 + idLen].digitToIntOrNull() ?: return null
        if (len > 8) return null
        val at = 2 + idLen
        if (line.length < at + len * 2) return null
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = (line.substring(at + i * 2, at + i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        return Frame(id, ext, data)
    }
}

/** Where the CAN session's frames come from: a CANable, or the Link demo. */
interface CanSource : Closeable {
    /** Frames that came within [timeoutMs]; empty when the bus is quiet. */
    fun read(timeoutMs: Int): List<Slcan.Frame>
    /** Error answers since the bus was opened. */
    val errors: Int
}

/** A CANable on its USB serial: [open] the bus at a speed, then [read] what comes. */
class SlcanPort(private val port: UsbSerialPort) : CanSource {
    private val buf = ByteArray(4096)
    private val line = StringBuilder()
    /** Error answers (BEL) since open: the adapter refused a command or the bus is off. */
    override var errors = 0
        private set

    private fun send(cmd: String) = port.write((cmd + "\r").toByteArray(Charsets.US_ASCII), 500)

    /**
     * [listenOnly]: the adapter never drives the bus (no ACK, no error frames), for a car's own bus; see [SlcanListen].
     * Returns false when the adapter refused that: the bus then stays closed.
     */
    fun open(kbit: Int, listenOnly: Boolean = false): Boolean {
        runCatching { port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        runCatching { port.dtr = true; port.rts = true }
        // a bus left open by another app (or by us before a crash) takes no speed: close it first
        send("C")
        Thread.sleep(50)
        drain()
        // 83.3 and 33.3 kbit go as Lawicel's "s" with bit timing (see CanBitrates)
        send(CanBitrates.slcanCommand(kbit) ?: "S${Slcan.speedCode(kbit)}")
        Thread.sleep(30)
        val dev = runCatching { port.driver.device }.getOrNull()
        val canableFw = dev != null && SlcanListen.canableFw(dev.vendorId, dev.productId)
        if (listenOnly) {
            drain()
            val ok = SlcanListen.open(SlcanUsbIo(port), canableFw)
            errors = 0
            return ok
        }
        if (canableFw) { send("M0"); Thread.sleep(20) } // a listen-only run (or the speed probe) may have left it silent
        send("O")
        Thread.sleep(30)
        errors = 0
        return true
    }

    private fun drain() { repeat(5) { if (port.read(buf, 50) <= 0) return } }

    override fun read(timeoutMs: Int): List<Slcan.Frame> {
        val n = port.read(buf, timeoutMs)
        if (n <= 0) return emptyList()
        val out = ArrayList<Slcan.Frame>()
        for (i in 0 until n) {
            when (val c = (buf[i].toInt() and 0xFF).toChar()) {
                '\r', '\n' -> { Slcan.parse(line.toString())?.let(out::add); line.setLength(0) }
                '\u0007' -> { errors++; line.setLength(0) }
                else -> if (line.length < 64) line.append(c) else line.setLength(0)
            }
        }
        return out
    }

    override fun close() { runCatching { send("C") } }
}
