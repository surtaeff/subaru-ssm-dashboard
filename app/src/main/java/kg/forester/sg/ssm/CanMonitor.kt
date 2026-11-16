package kg.forester.sg.ssm

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * «Что идёт по CAN»: every id heard (from a CANable or the OpenPort listening), its last bytes and how often it
 * comes. To find where a stream keeps a value (an EGT a tuner added to the Link's stream) without a laptop.
 * A compound stream (one id, byte 0 = frame number) gets a row per frame.
 */
object CanMonitor {
    class Row(
        val id: Int, val ext: Boolean, val mux: Int?, val data: ByteArray, val count: Int, val hz: Float, val lastMs: Long,
        /** When each bit (byte × 8 + bit, as the DBC counts them) last changed, ms; 0 = never. Sized by the data. */
        val bitMs: LongArray = LongArray(0),
        /** When any byte last changed, ms; 0 = never since the id came. */
        val changedMs: Long = 0L,
    ) {
        val idText: String get() = (if (ext) "%08X" else "%03X").format(id) + (mux?.let { " #$it" } ?: "")
        val bytesText: String get() = data.joinToString(" ") { "%02X".format(it) }
        /** The row's key in [CanMonitor] (id, 11/29 bit, frame number). */
        val key: Long get() = key(id, ext, mux)
        /** When a bit of byte [i] last changed (0 = never). */
        fun byteMs(i: Int): Long { var m = 0L; for (b in i * 8 until minOf(i * 8 + 8, bitMs.size)) if (bitMs[b] > m) m = bitMs[b]; return m }
    }

    private class Acc(val id: Int, val ext: Boolean, val mux: Int?) {
        var data = ByteArray(0)
        var count = 0
        var lastMs = 0L
        var hz = 0f
        var winStart = 0L
        var winCount = 0
        var bitMs = LongArray(0)
        var changedMs = 0L
    }

    /** Whoever else wants every frame (the log recorder, the «нажмите кнопку» capture): called on the reading thread. */
    fun interface Tap { fun frame(id: Int, ext: Boolean, mux: Int?, data: ByteArray, now: Long) }
    val taps = java.util.concurrent.CopyOnWriteArrayList<Tap>()

    private val map = ConcurrentHashMap<Long, Acc>()
    val rows = MutableStateFlow<List<Row>>(emptyList())
    private var publishedMs = 0L

    fun key(id: Int, ext: Boolean, mux: Int?) = (id.toLong() shl 9) or (if (ext) 256L else 0L) or (mux?.toLong() ?: 255L)

    fun feed(id: Int, ext: Boolean, data: ByteArray, now: Long = System.currentTimeMillis(), mux: Int? = null) {
        val k = key(id, ext, mux)
        val a = map[k] ?: if (map.size >= 400) return else Acc(id, ext, mux).also { map[k] = it }
        if (a.count > 0) CanSniff.markChanges(a.data, data, a.bitMs.let { if (it.size < data.size * 8) it.copyOf(data.size * 8).also { n -> a.bitMs = n } else it }, now)
            .let { if (it) a.changedMs = now }
        a.data = data.copyOf()
        a.count++
        for (t in taps) t.frame(id, ext, mux, a.data, now)
        a.lastMs = now
        if (a.winStart == 0L) a.winStart = now
        a.winCount++
        if (now - a.winStart >= 1000) { a.hz = a.winCount * 1000f / (now - a.winStart); a.winStart = now; a.winCount = 0 }
        if (now - publishedMs >= 250) publish(now)
    }

    fun publish(now: Long = System.currentTimeMillis()) {
        publishedMs = now
        rows.value = map.values.filter { now - it.lastMs < 5000 }
            .sortedWith(compareBy<Acc>({ it.ext }, { it.id }, { it.mux ?: -1 }))
            .map { Row(it.id, it.ext, it.mux, it.data, it.count, it.hz, it.lastMs, it.bitMs.copyOf(), it.changedMs) }
    }

    /** About how busy the bus is, 0–1, at [kbit] (see [CanSniff.busLoad]); from the rates of the last second. */
    fun busLoad(kbit: Int, now: Long = System.currentTimeMillis()): Float =
        CanSniff.busLoad(map.values.filter { now - it.lastMs < 2000 }.map { Triple(it.hz, it.data.size, it.ext) }, kbit)

    /** Different ids heard in the last 5 s. */
    fun idsSeen(now: Long = System.currentTimeMillis()): Int = map.values.filter { now - it.lastMs < 5000 }.map { it.id }.distinct().size

    fun clear() { map.clear(); rows.value = emptyList() }
}
