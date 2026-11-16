package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * SLCAN (the Lawicel text protocol): what a CANable, a CANtact and most cheap USB-CAN sticks speak over their USB
 * serial. The app listens (a sports ECU, Link, Haltech, ECUMaster…, broadcasts its dash stream by itself); a frame goes
 * out only through [CanTx], switched off by default.
 * Lines end with CR: "S6" sets 500 kbit, "O" opens the bus, "C" closes it; frames come as "t" + 3 hex id + length +
 * data ("T" + 8 hex id for an extended one), sometimes with a 4-hex time stamp after the data. A CANable 2.0 with CAN FD
 * sends FD frames as "d"/"D" (and "b"/"B" with the bit rate switch), the length a DLC code 0–F (canable2-fw, slcan.c).
 */
object Slcan {
    class Frame(id: Int, ext: Boolean, data: ByteArray, fd: Boolean = false, brs: Boolean = false) :
        CanFrame(id, ext, data, fd = fd, brs = brs)

    /** CANable (slcan firmware), the CANtact it came from, and anything whose USB name says it speaks slcan. */
    fun matches(vid: Int, pid: Int, name: String?): Boolean =
        (vid == 0xAD50 && pid == 0x60C4) || (vid == 0x16D0 && pid == 0x117E) ||
            (name != null && listOf("canable", "cantact", "slcan").any { name.contains(it, ignoreCase = true) })

    /** candleLight firmware (gs_usb): a CANable too, but not a serial port; Android can't open it without a driver of its own. */
    fun isCandleLight(vid: Int, pid: Int) = vid == 0x1D50 && pid == 0x606F

    /** Bus speeds offered in the settings, kbit/s: a comfort bus runs at 50–125, a dash or a sensor bus at 250–1000. */
    val SPEEDS = listOf(50, 100, 125, 250, 500, 1000)

    /** The "S" command's digit for [kbit]. */
    fun speedCode(kbit: Int): Char = when (kbit) {
        10 -> '0'; 20 -> '1'; 50 -> '2'; 100 -> '3'; 125 -> '4'; 250 -> '5'; 750 -> '7'; 1000 -> '8'
        else -> '6'
    }

    /** One line without its CR; null for anything but a data frame (OK, error, a remote request). */
    fun parse(line: String): Frame? {
        if (line.isEmpty()) return null
        val c = line[0]
        val ext = when (c) { 't', 'd', 'b' -> false; 'T', 'D', 'B' -> true; else -> return null }
        val fd = c in "dDbB"
        val idLen = if (ext) 8 else 3
        if (line.length < 1 + idLen + 1) return null
        val id = line.substring(1, 1 + idLen).toIntOrNull(16) ?: return null
        val dlc = line[1 + idLen].digitToIntOrNull(16) ?: return null
        if (!fd && dlc > 8) return null
        val len = CanFrame.dlcToLen(dlc, fd)
        val at = 2 + idLen
        if (line.length < at + len * 2) return null
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = (line.substring(at + i * 2, at + i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        return Frame(id, ext, data, fd, brs = c == 'b' || c == 'B')
    }

    /** The line that sends [f] ("t…"/"T…" for a classic frame, "d…"/"D…"/"b…"/"B…" for CAN FD), without its CR. */
    fun format(f: CanFrame): String {
        val sb = StringBuilder()
        sb.append(if (f.fd) (if (f.brs) 'b' else 'd') else 't')
        if (f.ext) sb.setCharAt(0, sb[0].uppercaseChar())
        sb.append(if (f.ext) "%08X".format(f.id) else "%03X".format(f.id))
        val len = if (f.fd) CanFrame.fdLen(f.data.size) else f.data.size
        sb.append("%X".format(CanFrame.lenToDlc(len)))
        for (i in 0 until len) sb.append("%02X".format(f.data.getOrElse(i) { 0 }.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * An answer to "V" that only an slcan adapter gives: the Lawicel "V1013", or a CANable 2.0's git version and
     * remote. A KKL cable echoes "V" back, an ELM327 answers "?" and its prompt.
     */
    fun versionReply(reply: String): Boolean = reply.split('\r', '\n').map { it.trim() }.any { l ->
        Regex("^[Vv][0-9A-Fa-f]{4}").containsMatchIn(l) || listOf("canable", "slcan", "cantact", "github").any { l.contains(it, true) }
    } && '>' !in reply
}

/** A CANable on its USB serial: [open] the bus at a speed, then [read] what comes. */
class SlcanPort(private val port: UsbSerialPort) : CanSource {
    private val buf = ByteArray(4096)
    private val line = StringBuilder()
    /** Error answers (BEL) since open: the adapter refused a command or the bus is off. */
    override var errors = 0
        private set

    private fun send(cmd: String) = port.write((cmd + "\r").toByteArray(Charsets.US_ASCII), 500)

    fun open(kbit: Int) {
        runCatching { port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        runCatching { port.dtr = true; port.rts = true }
        // a bus left open by another app (or by us before a crash) takes no speed: close it first
        send("C")
        Thread.sleep(50)
        drain()
        send("S${Slcan.speedCode(kbit)}")
        Thread.sleep(30)
        send("O")
        Thread.sleep(30)
        errors = 0
    }

    private fun drain() { repeat(5) { if (port.read(buf, 50) <= 0) return } }

    override val canTransmit get() = true

    override fun transmit(f: CanFrame, permit: CanTx.Permit): Boolean {
        if (!permit.covers(f)) return false
        if (f.data.size > (if (f.fd) CanFrame.MAX_FD else CanFrame.MAX_CLASSIC)) return false
        return runCatching { send(Slcan.format(f)) }.isSuccess
    }

    override fun read(timeoutMs: Int): List<CanFrame> {
        val n = port.read(buf, timeoutMs)
        if (n <= 0) return emptyList()
        val out = ArrayList<CanFrame>()
        for (i in 0 until n) {
            when (val c = (buf[i].toInt() and 0xFF).toChar()) {
                '\r', '\n' -> { Slcan.parse(line.toString())?.let(out::add); line.setLength(0) }
                '\u0007' -> { errors++; line.setLength(0) }
                // a CAN FD line is up to 1 + 8 + 1 + 128 characters
                else -> if (line.length < 160) line.append(c) else line.setLength(0)
            }
        }
        return out
    }

    override fun close() { runCatching { send("C") } }
}
