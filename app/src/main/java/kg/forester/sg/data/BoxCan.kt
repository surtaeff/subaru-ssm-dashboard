package kg.forester.sg.data

import kg.forester.sg.ssm.CanSource
import kg.forester.sg.ssm.Slcan
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * CAN on the SensorBox (an ESP32-S3 with a TJA1051T/3 or SN65HVD230 transceiver, firmware 6+): the box takes the place
 * of a separate CANable. The box says «can=twai» in its I line; the app sends the bus settings («G …» lines, see
 * hardware/sensorbox/README.md) and gets the frames back as slcan text lines between its D lines.
 *
 * Listen-only is the default and the only mode for a car's own bus: the controller sends no ACK and no error frame and
 * the transceiver's S pin holds its transmitter off. Normal mode (ACKs) only for the driver's own sensor bus, where
 * a sensor would otherwise go error-passive for want of an acknowledgement.
 */
data class BoxCanCfg(
    val on: Boolean = false,
    val kbit: Int = 500,
    /** The car's bus: listen only (no ACK, transceiver silent). False only for the driver's own sensor bus. */
    val listenOnly: Boolean = true,
    /** The board's pins (output pins of the ESP32-S3) for the transceiver's TXD, RXD and S; [s] empty when not wired. */
    val tx: String = "39",
    val rx: String = "40",
    val s: String = "41",
    /** Acceptance filters in the box's controller: none (every frame), one 29-bit, or one or two 11-bit. */
    val filters: List<BoxCanFilter> = emptyList(),
    val extFilter: Boolean = false,
    /** The transceiver module, for the wiring only: "tja1051" (TJA1051T/3) or "sn65" (SN65HVD230, no S pin). */
    val chip: String = "tja1051",
) {
    fun pins(): List<String> = listOf(tx, rx, s).filter { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject().put("on", on).put("kbit", kbit).put("listen", listenOnly).put("tx", tx).put("rx", rx).put("s", s)
        .put("fx", extFilter).put("chip", chip).put("f", JSONArray().also { a -> filters.forEach { a.put(JSONObject().put("id", it.id).put("mask", it.mask)) } })

    companion object {
        fun fromJson(o: JSONObject?): BoxCanCfg {
            if (o == null) return BoxCanCfg()
            val f = o.optJSONArray("f")
            return BoxCanCfg(
                o.optBoolean("on"), o.optInt("kbit", 500).takeIf { it in BoxCan.SPEEDS } ?: 500, o.optBoolean("listen", true),
                o.optString("tx", "39"), o.optString("rx", "40"), o.optString("s", "41"),
                if (f == null) emptyList() else List(f.length()) { f.getJSONObject(it).let { x -> BoxCanFilter(x.optInt("id"), x.optInt("mask")) } },
                o.optBoolean("fx"), o.optString("chip", "tja1051"),
            )
        }
    }
}

/** A frame passes when (its id AND [mask]) equals ([id] AND [mask]): mask bits 1 must match, as in SocketCAN. */
data class BoxCanFilter(val id: Int, val mask: Int)

/** What the box says about its bus in each D line: «can=l,1234,0,0,0,0». */
data class BoxCanStatus(
    /** 'l' listening, 'n' normal, 'b' bus off, 'r' recovering, 's' stopped. */
    val state: Char,
    val frames: Long,
    /** Lines the box left out because the USB could not take them (the app reads too slowly). */
    val dropped: Long,
    /** Frames the controller lost (its queue or FIFO full). */
    val missed: Long,
    val rxErrors: Int,
    val txErrors: Int,
    /** Frames a second, from the last two D lines. */
    val perSecond: Float = 0f,
) {
    val title: String get() = when (state) {
        'l' -> "слушает шину"
        'n' -> "на шине (обычный режим)"
        'b' -> "шина отключена (bus off): проводка или скорость"
        'r' -> "восстанавливается после bus off"
        else -> "остановлена"
    }
}

object BoxCan {
    /** kbit/s the box's firmware offers (62 is 62.5, 83 is 83.3, 33 is 33.3). */
    val SPEEDS = listOf(33, 50, 62, 83, 100, 125, 250, 500, 800, 1000)

    fun speedTitle(k: Int) = when (k) { 33 -> "33,3"; 62 -> "62,5"; 83 -> "83,3"; 1000 -> "1 Мбит"; else -> "$k" } + if (k < 1000) " кбит" else ""

    /** The pins the box takes for the transceiver when nothing else is on them: next to each other on DevKitC-1. */
    val DEFAULT_PINS = listOf("39", "40", "41")

    val cfg = MutableStateFlow(BoxCanCfg())
    val status = MutableStateFlow<BoxCanStatus?>(null)

    fun load(o: JSONObject?) { cfg.value = BoxCanCfg.fromJson(o) }

    /** The box on the cable can do CAN (an ESP32-S3 with firmware 6+). */
    fun supported(info: BoxInfo? = SensorBox.info.value) = info != null && info.via.startsWith("USB") && info.fw >= 6 && info.can.isNotEmpty()

    /** The lines that set the box's bus up as [c] says: the filter first, the bus opened with it. */
    fun commands(c: BoxCanCfg): List<String> =
        if (!c.on || c.tx.isEmpty() || c.rx.isEmpty()) listOf("G C")
        else listOf(filterCommand(c), "G O ${c.kbit} ${if (c.listenOnly) "L" else "N"} ${c.tx} ${c.rx} ${c.s.ifEmpty { "-" }}")

    fun filterCommand(c: BoxCanCfg): String {
        val l = if (c.extFilter) c.filters.take(1) else c.filters.take(2)
        if (l.isEmpty()) return "G F"
        val lim = if (c.extFilter) 0x1FFFFFFF else 0x7FF
        return "G F" + (if (c.extFilter) " X" else "") + l.joinToString("") { " %X %X".format(it.id and lim, it.mask and lim) }
    }

    /**
     * [c] with transceiver pins out of [free] (the output pins nobody else holds): its own when they are free, else
     * [DEFAULT_PINS], else the first free ones; S left out (empty) when only two are free.
     */
    fun pickPins(c: BoxCanCfg, free: List<String>): BoxCanCfg {
        val want = if (c.chip == "sn65") 2 else 3
        val own = listOf(c.tx, c.rx, c.s).take(want)
        if (own.all { it in free } && own.toSet().size == want) return c
        val pick = (if (DEFAULT_PINS.take(want).all { it in free }) DEFAULT_PINS.take(want) else free.take(want))
        return c.copy(tx = pick.getOrElse(0) { "" }, rx = pick.getOrElse(1) { "" }, s = if (want == 3) pick.getOrElse(2) { "" } else "")
    }

    /** «l,1234,0,0,5,0» from the D line's can= field; [prev] gives the rate. */
    fun parseStatus(v: String, prev: BoxCanStatus? = null, dtMs: Long = 0): BoxCanStatus? {
        val p = v.split(',')
        if (p.size < 6 || p[0].length != 1) return null
        val frames = p[1].toLongOrNull() ?: return null
        val rate = if (prev != null && dtMs > 0 && frames >= prev.frames) (frames - prev.frames) * 1000f / dtMs else 0f
        return BoxCanStatus(p[0][0], frames, p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: 0, p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0, rate)
    }

    private var baseFrames = -1L
    private var baseMs = 0L
    private var rate = 0f

    /** The D line's can= field (empty when the bus is closed) at the box's own [ms]: the rate over a second or more. */
    fun onStatus(v: String, ms: Long) {
        if (v.isEmpty()) { status.value = null; baseFrames = -1; return }
        val s = parseStatus(v) ?: return
        if (baseFrames < 0 || s.frames < baseFrames || ms < baseMs) { baseFrames = s.frames; baseMs = ms; rate = 0f }
        else if (ms - baseMs >= 1000) { rate = (s.frames - baseFrames) * 1000f / (ms - baseMs); baseFrames = s.frames; baseMs = ms }
        status.value = s.copy(perSecond = rate)
    }

    /** A box line that is a CAN frame ('t', 'T'); null for anything else, a remote frame among them. */
    fun parseLine(line: String): Slcan.Frame? =
        if (line.isNotEmpty() && (line[0] == 't' || line[0] == 'T')) Slcan.parse(line) else null

    /** [hex] as a filter's id or mask, null when it is not hex or does not fit [ext]'s id length. */
    fun hexId(hex: String, ext: Boolean): Int? = hex.trim().removePrefix("0x").removePrefix("0X").takeIf { it.isNotEmpty() && it.length <= 8 }
        ?.toLongOrNull(16)?.takeIf { it <= if (ext) 0x1FFFFFFFL else 0x7FFL }?.toInt()
}

/**
 * Where a frame off the box goes. At merge with feature/can-transport this is the seam: its common frame source
 * (RealDash/GVRET receivers) should take [BoxCanBus] as one more source, or register a [CanFrameSink] in [BoxCanBus.tap].
 */
fun interface CanFrameSink {
    fun onFrame(id: Int, ext: Boolean, data: ByteArray, timeMs: Long)
}

/**
 * The box's frames on their way to the same consumers a CANable feeds. With the adapter set to «CANable» and no
 * CANable on USB, the ECU loop runs its CAN session on a [Source] (Link streams, «Что идёт по CAN», own CAN values),
 * so the box replaces the CANable. Otherwise (a KKL cable or an OpenPort runs the ECU) every frame goes straight to
 * the monitor and the own CAN values, the way OpenPort's listening does.
 */
object BoxCanBus {
    private val queue = ArrayBlockingQueue<Slcan.Frame>(8192)
    /** A CAN session reads the box through a [Source] now. */
    @Volatile var attached = false
        private set
    /** Frames the app itself could not keep up with (queue full). */
    @Volatile var overflow = 0L
        private set
    /** One more consumer of every frame (see [CanFrameSink]). */
    @Volatile var tap: CanFrameSink? = null

    /** The box on USB can give CAN frames and the driver switched its CAN on. */
    fun available(): Boolean = BoxCan.supported() && BoxCan.cfg.value.on

    fun onFrame(f: Slcan.Frame, now: Long = System.currentTimeMillis()) {
        tap?.onFrame(f.id, f.ext, f.data, now)
        if (attached) { if (!queue.offer(f)) overflow++; return }
        direct(f, now)
    }

    private fun direct(f: Slcan.Frame, now: Long) {
        // the Subaru broadcast table keeps what a standard frame carries, and feeds the monitor and own values itself
        if (!f.ext && f.id !in 0x7E0..0x7EF) kg.forester.sg.ssm.CanBroadcast.feed(f.id, f.data, now)
        else {
            kg.forester.sg.ssm.CanMonitor.feed(f.id, f.ext, f.data, now)
            CanChannels.feed(f.id, f.ext, f.data, now)
        }
    }

    /** The box's frames as a CAN session's source, in place of a CANable. */
    class Source : CanSource {
        init { queue.clear(); attached = true }

        override fun read(timeoutMs: Int): List<Slcan.Frame> {
            val first = queue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return emptyList()
            val out = ArrayList<Slcan.Frame>(64)
            out += first
            queue.drainTo(out, 2048)
            return out
        }

        /** The controller's receive errors: a wrong speed shows here. */
        override val errors: Int get() = BoxCan.status.value?.rxErrors ?: 0

        override fun close() { attached = false; queue.clear() }
    }
}
