package kg.forester.sg.ssm

import java.util.BitSet

/**
 * The sniffer's arithmetic, apart from the screens (and tested): which bits changed, how busy the bus is, a run of
 * bits picked in the bit viewer as a DBC signal (start bit, length, byte order) and its value, and the «нажмите кнопку
 * сейчас» capture. Bits are counted as DBC files count them: bit n is bit n % 8 of byte n / 8, bit 0 the lowest.
 */
object CanSniff {
    /**
     * Marks in [bitMs] (byte × 8 + bit) the bits that differ between [old] and [new] with [now]; a byte [old] didn't
     * have counts as changed. Returns whether anything changed.
     */
    fun markChanges(old: ByteArray, new: ByteArray, bitMs: LongArray, now: Long): Boolean {
        var any = false
        for (i in new.indices) {
            val x = if (i < old.size) (old[i].toInt() xor new[i].toInt()) and 0xFF else 0xFF
            if (x == 0) continue
            any = true
            for (b in 0 until 8) if ((x shr b) and 1 == 1 && i * 8 + b < bitMs.size) bitMs[i * 8 + b] = now
        }
        return any || old.size != new.size
    }

    /** How strongly a change made at [changedMs] still shows: 1 just now, fading to 0 over [fadeMs] (SavvyCAN's fade). */
    fun fade(changedMs: Long, now: Long, fadeMs: Long = 2000): Float =
        if (changedMs <= 0L) 0f else (1f - (now - changedMs).toFloat() / fadeMs).coerceIn(0f, 1f)

    /**
     * Bits on the wire for one data frame with [len] data bytes: the fixed fields (47 bits for an 11-bit id, 67 for a
     * 29-bit one, with the 3 between frames) plus the data, plus the stuff bits, taken at half their worst case. An
     * estimate, as CAN analysers show it. A CAN FD frame is counted as if all of it went at the nominal rate (its data
     * phase is faster), so its share comes out too high.
     */
    fun frameBits(len: Int, ext: Boolean): Int {
        val fixed = if (ext) 67 else 47
        val stuffable = (if (ext) 54 else 34) + 8 * len
        return fixed + 8 * len + (stuffable - 1) / 4 / 2
    }

    /** The share of the bus taken, 0–1 (more when the rates were counted high), from (frames/s, data bytes, 29-bit) by id. */
    fun busLoad(rates: List<Triple<Float, Int, Boolean>>, kbit: Int): Float {
        if (kbit <= 0) return 0f
        val bps = CanBitrates.bps(kbit).toDouble()
        val used = rates.sumOf { (hz, len, ext) -> hz.toDouble() * frameBits(len, ext) }
        return (used / bps).toFloat()
    }

    fun bit(d: ByteArray, n: Int): Int = if (n < 0 || n / 8 >= d.size) 0 else (d[n / 8].toInt() shr (n % 8)) and 1

    /**
     * A run of bits as a DBC signal: [start] is the lowest bit for Intel (little-endian, "@1"), the highest (most
     * significant) for Motorola (big-endian, "@0"), counted the DBC way. [length] bits.
     */
    data class BitSel(val start: Int, val length: Int, val bigEndian: Boolean) {
        /** The bits it takes (byte × 8 + bit), most significant first. */
        fun bits(): List<Int> {
            val out = ArrayList<Int>(length)
            if (bigEndian) {
                var p = start
                repeat(length) { out += p; p = if (p % 8 == 0) p + 15 else p - 1 }
            } else for (i in length - 1 downTo 0) out += start + i
            return out
        }

        /** Its unsigned value in [d]; null when the frame is too short for it. */
        fun raw(d: ByteArray): Long? {
            if (length !in 1..64) return null
            var v = 0L
            for (p in bits()) { if (p / 8 >= d.size || p < 0) return null; v = (v shl 1) or bit(d, p).toLong() }
            return v
        }

        /** As a signed number (two's complement over [length] bits). */
        fun signed(d: ByteArray): Long? = raw(d)?.let { u -> if (length < 64 && (u shr (length - 1)) and 1L == 1L) u - (1L shl length) else u }

        /** Whole bytes: (first byte, bytes) the byte model of [kg.forester.sg.data.CanChannel] can hold; null otherwise. */
        fun wholeBytes(): Pair<Int, Int>? {
            if (length % 8 != 0 || length / 8 !in setOf(1, 2, 4)) return null
            val n = length / 8
            return if (bigEndian) { if (start % 8 == 7) start / 8 to n else null }
            else { if (start % 8 == 0) start / 8 to n else null }
        }
    }

    /**
     * The run of bits between two taps in the bit viewer, both ends in: Intel bits go up through the bytes (byte 0 bit
     * 7, then byte 1 bit 0), Motorola ones down the way they are drawn (byte 0 bit 0, then byte 1 bit 7).
     */
    fun select(a: Int, b: Int, bigEndian: Boolean): BitSel {
        if (!bigEndian) { val lo = minOf(a, b); return BitSel(lo, maxOf(a, b) - lo + 1, false) }
        // the drawn order: byte by byte, bit 7 first
        fun drawn(n: Int) = n / 8 * 8 + (7 - n % 8)
        val first = if (drawn(a) <= drawn(b)) a else b
        return BitSel(first, kotlin.math.abs(drawn(a) - drawn(b)) + 1, true)
    }

    // ---------------------------------------------------------------- «нажмите кнопку сейчас»

    /**
     * SavvyCAN's «show only what changes while I press the button»: for [baselineMs] the driver touches nothing and
     * every bit that moves by itself (counters, engine speed, checksums) is noted as noise; then for [actionMs] they
     * press the button, and what changed then but never in the baseline is the button. An id first heard in the action
     * window is reported whole.
     */
    class ChangeCapture(val startMs: Long, val baselineMs: Long, val actionMs: Long) {
        enum class Phase { BASELINE, ACTION, DONE }

        private class Seen(var data: ByteArray, val noise: BitSet = BitSet(), val moved: BitSet = BitSet(), var fresh: Boolean = false)

        private val seen = HashMap<Long, Seen>()

        fun phase(now: Long): Phase = when {
            now < startMs + baselineMs -> Phase.BASELINE
            now < startMs + baselineMs + actionMs -> Phase.ACTION
            else -> Phase.DONE
        }

        /** Seconds left in the current phase. */
        fun left(now: Long): Int = when (phase(now)) {
            Phase.BASELINE -> ((startMs + baselineMs - now + 999) / 1000).toInt()
            Phase.ACTION -> ((startMs + baselineMs + actionMs - now + 999) / 1000).toInt()
            Phase.DONE -> 0
        }

        @Synchronized
        fun feed(key: Long, data: ByteArray, now: Long) {
            val ph = phase(now)
            if (ph == Phase.DONE) return
            val s = seen[key]
            if (s == null) { seen[key] = Seen(data.copyOf(), fresh = ph == Phase.ACTION); return }
            val into = if (ph == Phase.BASELINE) s.noise else s.moved
            for (i in 0 until maxOf(s.data.size, data.size)) {
                val o = if (i < s.data.size) s.data[i].toInt() and 0xFF else -1
                val n = if (i < data.size) data[i].toInt() and 0xFF else -1
                if (o == n) continue
                val x = if (o < 0 || n < 0) 0xFF else o xor n
                for (b in 0 until 8) if ((x shr b) and 1 == 1) into.set(i * 8 + b)
            }
            s.data = data.copyOf()
        }

        /** One id that answered the button: its bits (byte × 8 + bit) and whether it only came with the button. */
        class Hit(val key: Long, val bits: List<Int>, val newId: Boolean, val data: ByteArray)

        @Synchronized
        fun result(): List<Hit> = seen.mapNotNull { (k, s) ->
            if (s.fresh) return@mapNotNull Hit(k, (0 until s.data.size * 8).toList(), true, s.data.copyOf())
            val b = (s.moved.clone() as BitSet).apply { andNot(s.noise) }
            if (b.isEmpty) null else Hit(k, generateSequence(b.nextSetBit(0).takeIf { it >= 0 }) { i -> b.nextSetBit(i + 1).takeIf { it >= 0 } }.toList(), false, s.data.copyOf())
        }.sortedWith(compareBy({ it.newId }, { it.bits.size }, { it.key }))
    }
}

/**
 * The one capture going on (or the last one done), fed by [CanBus.dispatch] (a [CanBus.listeners] entry): every
 * adapter, the OpenPort listening and a replayed log alike. The screens read [state].
 */
object CanCapture {
    val state = kotlinx.coroutines.flow.MutableStateFlow<CanSniff.ChangeCapture?>(null)
    private val tap = CanBus.Listener { f, mux, now -> state.value?.feed(CanMonitor.key(f.id, f.ext, mux, f.bus), f.data, now) }

    fun start(baselineMs: Long, actionMs: Long, now: Long = System.currentTimeMillis()) {
        state.value = CanSniff.ChangeCapture(now, baselineMs, actionMs)
        if (tap !in CanBus.listeners) CanBus.listeners += tap
    }

    fun stop() { state.value = null; CanBus.listeners -= tap }

    /** (id, 29-bit, frame number) back from a monitor key. */
    fun unkey(k: Long): Triple<Int, Boolean, Int?> = CanMonitor.unkey(k)
}

/**
 * A run of bits picked in the bit viewer as one of the driver's own CAN channels, the model the DBC and RealDash
 * imports make: whole bytes (1, 2 or 4 in a row, anywhere in a frame of up to 64) in the byte model, any other run as a
 * bit-level signal (start bit + length, Intel or Motorola, DBC numbering: see [kg.forester.sg.data.CanBits]). The
 * channel form opens with it.
 */
object CanBitChannel {
    class Request(val canId: Int, val ext: Boolean, val mux: Int?, val sel: CanSniff.BitSel)

    fun channel(r: Request, id: String): kg.forester.sg.data.CanChannel {
        val s = r.sel
        val whole = s.wholeBytes()?.takeIf { it.first + it.second <= kg.forester.sg.data.CanChannel.MAX_BYTE + 1 }
        if (whole != null) return kg.forester.sg.data.CanChannel(id, "", r.canId, r.ext, r.mux ?: -1, whole.first, whole.second,
            bigEndian = s.bigEndian, signed = false, formula = "x", units = "", decimals = 0, role = "")
        // the bytes it touches stay the byte model's rough fallback, as the DBC import leaves them
        val span = kg.forester.sg.data.CanBits.byteSpan(s.start, s.length, s.bigEndian) ?: (s.start / 8)..(s.start / 8)
        val len = when (span.last - span.first + 1) { 1 -> 1; 2 -> 2; else -> 4 }
        return kg.forester.sg.data.CanChannel(id, "", r.canId, r.ext, r.mux ?: -1, span.first.coerceIn(0, kg.forester.sg.data.CanChannel.MAX_BYTE), len,
            bigEndian = s.bigEndian, signed = false, formula = "x", units = "", decimals = 0, role = "",
            startBit = s.start, bitLen = s.length)
    }
}
