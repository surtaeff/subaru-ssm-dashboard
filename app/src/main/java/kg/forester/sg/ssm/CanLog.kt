package kg.forester.sg.ssm

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.Writer

/**
 * CAN logs on disk, in the two formats the hobby tools read: SavvyCAN's own GVRET CSV and Linux candump's log
 * (`candump -L`). A recording is written as GVRET CSV; the share sheet can hand it over as candump too. A log made
 * elsewhere (SavvyCAN, candump, our own) is read back and replayed through the monitor and the own channels (see
 * [CanReplay]): the sniffer and the channels can be worked out at home, with no car.
 *
 * Frames are sized by their data: a CAN FD frame (up to 64 bytes) is written and read the same way.
 */
class CanLogFrame(
    /** Microseconds; absolute (epoch) or from the log's start, whatever the file had. */
    timeUs: Long,
    id: Int,
    ext: Boolean,
    data: ByteArray,
    bus: Int = 0,
    /** Sent by the adapter (SavvyCAN's "Tx"); the app sends only through the transmit gate, a log from elsewhere may have them. */
    val tx: Boolean = false,
    /** A CAN FD frame (candump's "##"); a frame over 8 bytes is FD whatever this says. */
    fd: Boolean = data.size > 8,
) : CanFrame(id, ext, data, bus, fd, timeUs = timeUs) {
    override fun equals(other: Any?) = other is CanLogFrame && other.timeUs == timeUs && other.id == id && other.ext == ext &&
        other.data.contentEquals(data) && other.bus == bus && other.tx == tx && other.fd == fd
    override fun hashCode() = (timeUs * 31 + id).toInt() * 31 + data.contentHashCode()
    override fun toString() = "CanLogFrame($timeUs, %X, ext=$ext, ${data.joinToString("") { "%02X".format(it) }})".format(id)
}

enum class CanLogFormat(val title: String, val ext: String, val mime: String) {
    GVRET_CSV("CSV (SavvyCAN)", "csv", "text/csv"),
    CANDUMP("candump", "log", "text/plain"),
}

private fun hex2(b: Byte) = HEX[(b.toInt() shr 4) and 0xF].toString() + HEX[b.toInt() and 0xF]
private const val HEX = "0123456789ABCDEF"

private fun parseHexBytes(s: String): ByteArray? {
    if (s.length % 2 != 0) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(s[i * 2], 16)
        val lo = Character.digit(s[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * SavvyCAN's native CSV ("GVRET" in its save dialog):
 * `Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8`, then `1234567,000003EC,false,Rx,0,8,01,02,…`.
 * The time stamp is in microseconds, the id 8 hex digits. Older SavvyCAN files have no Dir column: the reader goes by
 * the header. A frame longer than 8 bytes simply has more data columns (SavvyCAN reads LEN of them).
 */
object GvretCsv {
    const val HEADER = "Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8"

    fun line(f: CanLogFrame): String {
        val sb = StringBuilder(48 + f.data.size * 3)
        sb.append(f.timeUs).append(',')
        sb.append("%08X".format(f.id)).append(',')
        sb.append(if (f.ext) "true" else "false").append(',')
        sb.append(if (f.tx) "Tx" else "Rx").append(',')
        sb.append(f.bus).append(',')
        sb.append(f.data.size).append(',')
        for (b in f.data) sb.append(hex2(b)).append(',')
        return sb.toString()
    }

    fun write(w: Writer, frames: Iterable<CanLogFrame>) {
        w.write(HEADER); w.write("\n")
        for (f in frames) { w.write(line(f)); w.write("\n") }
    }

    /** Where each field is, from the header line; null when it isn't SavvyCAN's CSV. */
    class Columns(val time: Int, val id: Int, val ext: Int, val dir: Int, val bus: Int, val len: Int, val data: Int)

    fun columns(header: String): Columns? {
        val h = header.split(',').map { it.trim().lowercase() }
        val time = h.indexOf("time stamp").takeIf { it >= 0 } ?: return null
        val id = h.indexOf("id").takeIf { it >= 0 } ?: return null
        val len = h.indexOf("len").takeIf { it >= 0 } ?: return null
        val d1 = h.indexOf("d1").takeIf { it >= 0 } ?: (len + 1)
        return Columns(time, id, h.indexOf("extended"), h.indexOf("dir"), h.indexOf("bus"), len, d1)
    }

    /** One data line; null for anything else (the header, an empty or broken line). */
    fun parse(line: String, c: Columns = DEFAULT): CanLogFrame? {
        val t = line.split(',')
        if (t.size <= c.len) return null
        val time = t[c.time].trim().toLongOrNull() ?: return null
        val idText = t[c.id].trim().removePrefix("0x").removePrefix("0X")
        val id = idText.toLongOrNull(16)?.takeIf { it in 0..0x1FFFFFFF }?.toInt() ?: return null
        val ext = if (c.ext >= 0) t.getOrNull(c.ext)?.trim()?.equals("true", true) == true else id > 0x7FF
        val tx = c.dir >= 0 && t.getOrNull(c.dir)?.trim()?.equals("tx", true) == true
        val bus = if (c.bus >= 0) t.getOrNull(c.bus)?.trim()?.toIntOrNull() ?: 0 else 0
        val n = t[c.len].trim().toIntOrNull()?.takeIf { it in 0..64 } ?: return null
        if (t.size < c.data + n) return null
        val data = ByteArray(n)
        for (i in 0 until n) data[i] = (t[c.data + i].trim().toIntOrNull(16)?.takeIf { it in 0..255 } ?: return null).toByte()
        return CanLogFrame(time, id, ext, data, bus, tx)
    }

    val DEFAULT = columns(HEADER)!!
}

/**
 * Linux candump's log (`candump -L`, what `canplayer` and SavvyCAN read): `(1436509052.249713) can0 123#DEADBEEF`.
 * 3 hex digits for an 11-bit id, 8 for a 29-bit one; `123##1…` is a CAN FD frame (the digit after ## its flags),
 * `123#R` a remote request (skipped on reading). The reader also takes candump's screen output,
 * `  can0  123   [4]  DE AD BE EF`, which people paste as often.
 */
object Candump {
    fun line(f: CanLogFrame, iface: String = "can${f.bus}"): String {
        val sec = Math.floorDiv(f.timeUs, 1_000_000L)
        val us = Math.floorMod(f.timeUs, 1_000_000L)
        val sb = StringBuilder(40 + f.data.size * 2)
        sb.append('(').append(sec).append('.').append(us.toString().padStart(6, '0')).append(") ")
        sb.append(iface).append(' ')
        sb.append(if (f.ext) "%08X".format(f.id) else "%03X".format(f.id))
        // flags: 0 (no bit rate switch, no error state); the app can't know the BRS of a frame it was handed
        if (f.fd) sb.append("##0") else sb.append('#')
        for (b in f.data) sb.append(hex2(b))
        return sb.toString()
    }

    fun write(w: Writer, frames: Iterable<CanLogFrame>) {
        for (f in frames) { w.write(line(f)); w.write("\n") }
    }

    private val SCREEN = Regex("^\\s*(?:\\(([0-9.]+)\\)\\s+)?(\\w+)\\s+([0-9A-Fa-f]{1,8})\\s+\\[(\\d{1,2})]\\s*((?:[0-9A-Fa-f]{2}\\s*)*)$")

    fun parse(line: String): CanLogFrame? {
        val s = line.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("(")) {
            val close = s.indexOf(')')
            if (close > 0) {
                val rest = s.substring(close + 1).trim().split(Regex("\\s+"))
                if (rest.size >= 2 && rest[1].contains('#')) return logLine(s.substring(1, close), rest[0], rest[1])
            }
        }
        val m = SCREEN.matchEntire(s) ?: return null
        val idText = m.groupValues[3]
        val id = idText.toLongOrNull(16)?.toInt() ?: return null
        val n = m.groupValues[4].toInt()
        val data = parseHexBytes(m.groupValues[5].replace(Regex("\\s+"), "")) ?: return null
        if (data.size != n) return null
        val time = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(::timeOf) ?: 0L
        return CanLogFrame(time, id, idText.length > 3, data, busOf(m.groupValues[2]))
    }

    private fun timeOf(t: String): Long? {
        val dot = t.indexOf('.')
        if (dot < 0) return t.toLongOrNull()?.times(1_000_000L)
        val sec = t.substring(0, dot).toLongOrNull() ?: return null
        val frac = t.substring(dot + 1).take(6).padEnd(6, '0').toLongOrNull() ?: return null
        return sec * 1_000_000L + frac
    }

    /** "can1" → 1, "vcan0" → 0, "slcan2" → 2; anything else bus 0. */
    private fun busOf(iface: String) = iface.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun logLine(time: String, iface: String, frame: String): CanLogFrame? {
        val t = timeOf(time) ?: return null
        val hash = frame.indexOf('#')
        val idText = frame.substring(0, hash)
        if (idText.isEmpty() || idText.length > 8) return null
        val id = idText.toLongOrNull(16)?.takeIf { it in 0..0x1FFFFFFF }?.toInt() ?: return null
        val ext = idText.length > 3
        var body = frame.substring(hash + 1)
        val fd = body.startsWith("#")
        if (fd) body = body.drop(2) // ## and the flags digit
        else if (body.startsWith("R")) return null // remote request: no data, nothing to show
        val data = parseHexBytes(body.replace(".", "")) ?: return null
        return CanLogFrame(t, id, ext, data, busOf(iface), fd = fd || data.size > 8)
    }
}

object CanLogs {
    /** Which format the first lines of a file are in; null when neither. */
    fun detect(firstLines: List<String>): CanLogFormat? {
        for (l in firstLines) {
            val s = l.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            if (GvretCsv.columns(s) != null) return CanLogFormat.GVRET_CSV
            if (Candump.parse(s) != null) return CanLogFormat.CANDUMP
            if (GvretCsv.parse(s) != null) return CanLogFormat.GVRET_CSV
        }
        return null
    }

    /**
     * Every frame of [r], lazily (a log of an hour on a busy bus is millions of lines; nothing is held). Broken lines
     * are skipped. [format] null: found from the first lines.
     */
    fun read(r: BufferedReader, format: CanLogFormat? = null): Sequence<CanLogFrame> = sequence {
        val head = ArrayList<String>()
        r.mark(64 * 1024)
        repeat(10) { r.readLine()?.let(head::add) }
        r.reset()
        val fmt = format ?: detect(head) ?: return@sequence
        var cols = GvretCsv.DEFAULT
        while (true) {
            val l = r.readLine() ?: break
            when (fmt) {
                CanLogFormat.GVRET_CSV -> {
                    val f = GvretCsv.parse(l, cols)
                    if (f != null) yield(f) else GvretCsv.columns(l)?.let { cols = it }
                }
                CanLogFormat.CANDUMP -> Candump.parse(l)?.let { yield(it) }
            }
        }
    }

    fun read(f: File, format: CanLogFormat? = null): Sequence<CanLogFrame> = sequence {
        f.bufferedReader().use { yieldAll(read(it, format)) }
    }

    /** [src] (any of the two) written again as [to]. */
    fun convert(src: File, to: CanLogFormat, dst: File) {
        dst.bufferedWriter().use { w ->
            when (to) {
                CanLogFormat.GVRET_CSV -> GvretCsv.write(w, read(src).asIterable())
                CanLogFormat.CANDUMP -> Candump.write(w, read(src).asIterable())
            }
        }
    }

    /** What a log holds: frames, ids, how long it runs. One pass over the file. */
    class Summary(val frames: Int, val ids: Int, val durationMs: Long, val format: CanLogFormat?)

    fun summary(f: File): Summary {
        val fmt = runCatching { f.bufferedReader().use { r -> detect(List(10) { r.readLine() ?: "" }) } }.getOrNull()
        var n = 0
        val ids = HashSet<Long>()
        var first = Long.MIN_VALUE
        var last = 0L
        runCatching {
            for (fr in read(f, fmt)) {
                n++
                ids += (fr.id.toLong() shl 1) or (if (fr.ext) 1L else 0L)
                if (first == Long.MIN_VALUE) first = fr.timeUs
                last = fr.timeUs
            }
        }
        return Summary(n, ids.size, if (n > 0) (last - first) / 1000 else 0, fmt)
    }
}

/**
 * Writes what comes off the bus to a GVRET CSV, from any thread. The frames reach it through [CanBus.dispatch] (a
 * [CanBus.listeners] entry), so a recording takes every adapter (a CANable, RealDash CAN, GVRET, the SensorBox) and the
 * OpenPort's listening alike, with their bus numbers and CAN FD; a replayed log is not recorded again.
 */
class CanLogRecorder(val file: File) {
    private val w: BufferedWriter = file.bufferedWriter().also { it.write(GvretCsv.HEADER); it.write("\n") }
    @Volatile var frames = 0
        private set
    val startMs = System.currentTimeMillis()
    private var flushedMs = startMs
    @Volatile private var closed = false

    fun add(id: Int, ext: Boolean, data: ByteArray, nowMs: Long) = add(CanFrame(id, ext, data), nowMs)

    @Synchronized
    fun add(f: CanFrame, nowMs: Long) {
        if (closed) return
        runCatching {
            w.write(GvretCsv.line(CanLogFrame(nowMs * 1000L, f.id, f.ext, f.data, f.bus, fd = f.fd)))
            w.write("\n")
            frames++
            if (nowMs - flushedMs > 2000) { w.flush(); flushedMs = nowMs }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { w.flush(); w.close() }
    }
}

/** The one recording of the bus going on, if any: every frame [CanBus.dispatch] hands out, a replay left out. */
object CanRecording {
    val recorder = kotlinx.coroutines.flow.MutableStateFlow<CanLogRecorder?>(null)
    private val tap = CanBus.Listener { f, _, now -> if (!CanReplay.active) recorder.value?.add(f, now) }

    /** Where the recordings go: «can-logs» next to the app's other logs. */
    fun dir(ctx: android.content.Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "can-logs").apply { mkdirs() }

    fun start(dir: File, now: Long = System.currentTimeMillis()): CanLogRecorder {
        stop()
        val name = "can-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now)) + ".csv"
        return CanLogRecorder(File(dir.apply { mkdirs() }, name)).also {
            recorder.value = it
            if (tap !in CanBus.listeners) CanBus.listeners += tap
        }
    }

    fun stop(): File? {
        val r = recorder.value ?: return null
        recorder.value = null
        CanBus.listeners -= tap
        r.close()
        return r.file
    }

    /** The recordings and imported logs, newest first. */
    fun files(dir: File): List<File> = dir.listFiles { f -> f.isFile && (f.name.endsWith(".csv") || f.name.endsWith(".log")) }
        .orEmpty().sortedByDescending { it.lastModified() }
}
