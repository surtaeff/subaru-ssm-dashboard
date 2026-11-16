package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bus speeds as the app keeps them: kbit/s in an Int ([kg.forester.sg.data.Repo.Settings.canKbit]), where 83 stands
 * for 83.333 (Mercedes CAN-B) and 33 for 33.333 (GM single-wire GMLAN).
 */
object CanBitrates {
    /** What the auto-detection tries, most common first. */
    val PROBE = listOf(500, 250, 125, 1000, 100, 83, 50, 33)

    /** The chips of the speed picker, ascending. */
    val CHOICES = listOf(33, 50, 83, 100, 125, 250, 500, 1000)

    fun bps(kbit: Int): Int = when (kbit) { 83 -> 83_333; 33 -> 33_333; else -> kbit * 1000 }

    fun label(kbit: Int): String = when (kbit) {
        83 -> "83,3 кбит"
        33 -> "33,3 кбит"
        1000 -> "1 Мбит"
        else -> if (kbit >= 1000 && kbit % 1000 == 0) "${kbit / 1000} Мбит" else "$kbit кбит"
    }

    /**
     * The slcan command for [kbit]: Lawicel's "S0"–"S8" for the standard speeds, else "s" with the SJA1000 bit timing
     * registers BTR0/BTR1 (16 MHz clock, 16 time quanta, sample point 87.5 %): what a Lawicel CANUSB and its clones
     * take for 83.3 and 33.3. A CANable's own firmware has no "s": it answers with an error, and that speed is
     * reported as not supported by the adapter. Null: no way to set it.
     */
    fun slcanCommand(kbit: Int): String? = when (kbit) {
        10 -> "S0"; 20 -> "S1"; 50 -> "S2"; 100 -> "S3"; 125 -> "S4"; 250 -> "S5"; 500 -> "S6"; 1000 -> "S8"
        // BRP = 16 MHz / (2 × 16 × bit rate) − 1, SJW 2: 83.333 → BRP 5, 33.333 → BRP 14; BTR1 0x1C: TSEG1 13, TSEG2 2
        83 -> "s451C"
        33 -> "s4E1C"
        else -> null
    }
}

/** The bytes of an slcan adapter: what the probe and [SlcanPort] need of a serial port (and a test of a fake bus). */
interface SlcanIo {
    fun write(cmd: String)
    /** Bytes that came within [timeoutMs] into [buf]; 0 when none. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
}

class SlcanUsbIo(private val port: UsbSerialPort) : SlcanIo {
    override fun write(cmd: String) { port.write((cmd + "\r").toByteArray(Charsets.US_ASCII), 500) }
    override fun read(buf: ByteArray, timeoutMs: Int): Int = runCatching { port.read(buf, timeoutMs) }.getOrDefault(0).coerceAtLeast(0)

    /** The line as [SlcanPort.open] sets it. */
    fun setUp() {
        runCatching { port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        runCatching { port.dtr = true; port.rts = true }
    }
}

/**
 * Commands with their answers, and the bus opened so that the adapter never drives it: no ACK, no error frames, no
 * frames. That is what makes it safe to try a speed on a car's bus: a controller in normal mode at the wrong speed
 * floods the bus with error frames (warning lights, a gateway logging faults).
 */
object SlcanListen {
    enum class Reply { OK, ERROR, NONE }

    /** Sends [cmd] and waits up to [timeoutMs] for its CR (OK) or BEL (error); frames coming meanwhile go to [frames]. */
    fun command(io: SlcanIo, cmd: String, timeoutMs: Int = 300, frames: ((Slcan.Frame) -> Unit)? = null): Reply {
        io.write(cmd)
        val buf = ByteArray(512)
        val line = StringBuilder()
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val n = io.read(buf, 50)
            for (i in 0 until n) {
                when (val c = (buf[i].toInt() and 0xFF).toChar()) {
                    '\u0007' -> return Reply.ERROR
                    '\r', '\n' -> {
                        if (line.isEmpty() || line.toString() == "z" || line.toString() == "Z") return Reply.OK
                        Slcan.parse(line.toString())?.let { frames?.invoke(it) }
                        line.setLength(0)
                    }
                    else -> if (line.length < 160) line.append(c) else line.setLength(0)
                }
            }
        }
        return Reply.NONE
    }

    /** The CANable's own firmware (normaldotcom canable-fw): "M1" is its silent mode, it has no Lawicel "L". */
    fun canableFw(vid: Int, pid: Int) = vid == 0xAD50 && pid == 0x60C4

    /**
     * Opens the (closed, speed set) bus listen-only. A CANable ([canableFw]): "M1" then "O", unless it refuses M1.
     * Anything else: Lawicel's "L", which either opens it listen-only or does nothing; never "O". Returns false when
     * the adapter said no: then the bus stays closed.
     */
    fun open(io: SlcanIo, canableFw: Boolean, frames: ((Slcan.Frame) -> Unit)? = null): Boolean {
        if (canableFw) {
            if (command(io, "M1") == Reply.ERROR) return false
            return command(io, "O", frames = frames) != Reply.ERROR
        }
        return command(io, "L", frames = frames) != Reply.ERROR
    }
}

/**
 * Finds the speed of a bus by listening at each common one in turn, listen-only (see [SlcanListen]): the speed at
 * which whole frames come and the adapter reports no errors. Nothing is ever sent onto the bus.
 */
object CanBaudProbe {
    class Result(val kbit: Int, val supported: Boolean, val frames: Int, val ids: Int, val repeated: Int, val errors: Int) {
        /** Frames of the same ids again and again, and no error: a bus at this speed (garbage at a wrong one rarely repeats). */
        val valid: Boolean get() = supported && errors == 0 && frames >= 3 && repeated >= 1
    }

    sealed class Outcome {
        class Found(val kbit: Int, val results: List<Result>) : Outcome()
        class Silent(val results: List<Result>) : Outcome()
        /** The adapter refused to listen only: nothing was tried, so as not to disturb the bus. */
        object NoListenOnly : Outcome()
        object Cancelled : Outcome()
    }

    sealed class State {
        object Idle : State()
        /** Asked for: waits for the CANable session to hand over its port. */
        object Waiting : State()
        class Trying(val kbit: Int, val done: List<Result>) : State()
        class Finished(val outcome: Outcome, val atMs: Long) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    /** The session lets go of the port and runs [runOn] instead of reading. */
    val requested: Boolean get() = state.value is State.Waiting

    fun request() { state.value = State.Waiting }
    fun cancel() { if (state.value is State.Waiting) state.value = State.Idle else cancelled = true }
    @Volatile private var cancelled = false

    /** The best speed of [results], or null when none looks like a bus. */
    fun pick(results: List<Result>): Result? =
        results.filter { it.valid }.maxWithOrNull(compareBy<Result>({ it.repeated }, { it.frames }))

    /**
     * Tries [rates] on [io] for [listenMs] each (sooner when the bus is plainly there) and leaves the bus closed.
     * [canableFw]: how to open listen-only, see [SlcanListen.open].
     */
    fun run(
        io: SlcanIo, canableFw: Boolean, rates: List<Int> = CanBitrates.PROBE, listenMs: Long = 1200,
        cancel: () -> Boolean = { false }, progress: (Int, List<Result>) -> Unit = { _, _ -> },
    ): Outcome {
        val results = ArrayList<Result>()
        val buf = ByteArray(4096)
        try {
            for (kbit in rates) {
                if (cancel()) return Outcome.Cancelled
                progress(kbit, results.toList())
                io.write("C")
                drain(io, buf)
                val cmd = CanBitrates.slcanCommand(kbit)
                if (cmd == null || SlcanListen.command(io, cmd) == SlcanListen.Reply.ERROR) {
                    results += Result(kbit, false, 0, 0, 0, 0); continue
                }
                val counts = HashMap<Long, Int>()
                var frames = 0
                var errors = 0
                fun got(f: Slcan.Frame) { frames++; val k = (f.id.toLong() shl 1) or (if (f.ext) 1L else 0L); counts[k] = (counts[k] ?: 0) + 1 }
                if (!SlcanListen.open(io, canableFw, ::got)) {
                    // the first speed tells: an adapter that won't listen only is not tried any further
                    if (results.none { it.supported }) return Outcome.NoListenOnly
                    results += Result(kbit, false, 0, 0, 0, 0); continue
                }
                val line = StringBuilder()
                val until = System.currentTimeMillis() + listenMs
                while (System.currentTimeMillis() < until && !cancel()) {
                    val n = io.read(buf, 50)
                    for (i in 0 until n) {
                        when (val c = (buf[i].toInt() and 0xFF).toChar()) {
                            '\r', '\n' -> { Slcan.parse(line.toString())?.let(::got); line.setLength(0) }
                            '\u0007' -> { errors++; line.setLength(0) }
                            else -> if (line.length < 160) line.append(c) else line.setLength(0)
                        }
                    }
                    // a busy bus is plain within a moment: on to the next speed only when it might be better
                    if (frames >= 60 && counts.values.count { it >= 3 } >= 2 && errors == 0) break
                }
                results += Result(kbit, true, frames, counts.size, counts.values.count { it >= 2 }, errors)
                // a clear winner: the rest need not be tried
                val r = results.last()
                if (r.valid && r.frames >= 60 && r.repeated >= 2) break
            }
        } finally {
            runCatching { io.write("C") }
            if (canableFw) runCatching { io.write("M0") } // back to the firmware's default for a normal session
        }
        val best = pick(results)
        return if (best != null) Outcome.Found(best.kbit, results) else Outcome.Silent(results)
    }

    private fun drain(io: SlcanIo, buf: ByteArray) { repeat(5) { if (io.read(buf, 40) <= 0) return } }

    /**
     * The CANable session's turn: probe on its open [port], save the speed found, report. Called on the service's
     * thread with the bus closed.
     */
    fun runOn(port: UsbSerialPort, onSaved: (Int) -> Unit) {
        cancelled = false
        val io = SlcanUsbIo(port).also { it.setUp() }
        val dev = port.driver.device
        val out = run(io, SlcanListen.canableFw(dev.vendorId, dev.productId), cancel = { cancelled },
            progress = { k, done -> state.value = State.Trying(k, done) })
        if (out is Outcome.Found) onSaved(out.kbit)
        state.value = State.Finished(out, System.currentTimeMillis())
        SsmTrace.log("CAN bit rate probe: " + when (out) {
            is Outcome.Found -> "${out.kbit} kbit"
            is Outcome.Silent -> "nothing heard"
            Outcome.NoListenOnly -> "adapter refused listen-only"
            Outcome.Cancelled -> "cancelled"
        })
    }
}
