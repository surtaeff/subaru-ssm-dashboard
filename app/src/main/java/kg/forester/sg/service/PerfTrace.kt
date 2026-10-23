package kg.forester.sg.service

import kg.forester.sg.data.GpsFix
import kg.forester.sg.ssm.EcuSnapshot
import java.util.Locale

/**
 * One ECU sample of a run: [t] ms from the start of the trace, the ECU speed as read (km/h; the speedometer
 * correction is [RunTrace.k]), engine speed, boost (gauge kPa), pedal and throttle plate (%), the automatic's gear.
 */
data class EcuPt(
    val t: Int, val kmh: Float, val rpm: Int? = null, val boost: Float? = null,
    val pedal: Float? = null, val thr: Float? = null, val gear: Int? = null,
) {
    /** The driver's foot: the pedal where there is one (drive by wire), else the throttle plate. */
    val gas get() = pedal ?: thr
}

/**
 * One GPS fix of a run: [t] as for [EcuPt] (when it was taken), Doppler speed km/h, accuracy m, altitude m, satellites in
 * the fix and the mean C/N0 of the 4 strongest (dB-Hz).
 */
data class GpsPt(val t: Int, val kmh: Float, val acc: Float, val alt: Float? = null, val sats: Int? = null, val cn0: Float? = null)

/**
 * The raw samples of a run, kept with it so its times can be counted again on other intervals (a GPS meter keeps
 * only its results). [startMs] is the wall time of t = 0, [k] the speedometer correction learned from GPS then.
 */
class RunTrace(val startMs: Long, val k: Float, val ecu: List<EcuPt>, val gps: List<GpsPt>) {
    /** Seconds the trace covers. */
    val span: Double get() {
        val a = minOf(ecu.firstOrNull()?.t ?: Int.MAX_VALUE, gps.firstOrNull()?.t ?: Int.MAX_VALUE)
        val b = maxOf(ecu.lastOrNull()?.t ?: 0, gps.lastOrNull()?.t ?: 0)
        return if (a == Int.MAX_VALUE) 0.0 else (b - a) / 1000.0
    }

    /** A line per sample, "-" where a value is missing: ~30 bytes a sample, ~10 KB for a 30-second run. */
    fun encode(): String = buildString {
        fun f(v: Float?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
        append("trace 1 $startMs ${String.format(Locale.US, "%.4f", k)}\n")
        for (p in ecu) append("e ${p.t} ${f(p.kmh)} ${p.rpm ?: "-"} ${f(p.boost)} ${f(p.pedal)} ${f(p.thr)} ${p.gear ?: "-"}\n")
        for (p in gps) append("g ${p.t} ${f(p.kmh)} ${f(p.acc)} ${f(p.alt)} ${p.sats ?: "-"} ${f(p.cn0)}\n")
    }

    companion object {
        fun decode(text: String): RunTrace? = runCatching {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            val head = lines.next().split(' ')
            if (head[0] != "trace") return null
            val ecu = ArrayList<EcuPt>(); val gps = ArrayList<GpsPt>()
            for (line in lines) {
                val c = line.split(' ')
                fun fl(i: Int) = c.getOrNull(i)?.toFloatOrNull()
                fun int(i: Int) = c.getOrNull(i)?.toIntOrNull()
                when (c[0]) {
                    "e" -> ecu += EcuPt(c[1].toInt(), c[2].toFloat(), int(3), fl(4), fl(5), fl(6), int(7))
                    "g" -> gps += GpsPt(c[1].toInt(), c[2].toFloat(), c[3].toFloat(), fl(4), int(5), fl(6))
                }
            }
            RunTrace(head[2].toLong(), head[3].toFloat(), ecu, gps)
        }.getOrNull()
    }
}

/**
 * The last couple of minutes of samples for [RunTrace]: every ECU sample with its own time (the fast poll gives
 * ~12 a second, a Bluetooth adapter fewer) and every new GPS fix, so a run is cut out of it when it is over.
 */
object TraceBuffer {
    private class E(val ms: Long, val p: EcuPt)
    private class G(val ms: Long, val p: GpsPt)

    private val ecu = ArrayDeque<E>()
    private val gps = ArrayDeque<G>()
    private var lastFix = 0L

    @Synchronized
    fun record(e: EcuSnapshot, fix: GpsFix?, gear: Int?, now: Long) {
        val kmh = e.speedKmh
        if (kmh != null) ecu.addLast(E(now, EcuPt(0, kmh.toFloat(), e.rpm, e.gaugeKpa, e.pedalPct, e.throttlePct, gear)))
        if (fix != null) fix(fix)
        while (ecu.isNotEmpty() && now - ecu.first().ms > 150_000L) ecu.removeFirst()
        while (gps.isNotEmpty() && now - gps.first().ms > 150_000L) gps.removeFirst()
    }

    /**
     * A GPS fix, straight from the receiver (every one, also those the screens skip) or with an ECU sample: a newer one
     * only, so the same fix coming both ways is kept once. The network's position (no speed) is left out.
     */
    @Synchronized
    fun fix(fix: GpsFix) {
        if (fix.timeMs <= lastFix || fix.accuracyM >= 50f) return
        lastFix = fix.timeMs
        gps.addLast(G(fix.timeMs, GpsPt(0, fix.speedKmh, fix.accuracyM, fix.altitudeM.toFloat(), fix.satellites.takeIf { it > 0 }, fix.cn0)))
    }

    /** The samples from [fromMs] to [toMs], the time counted from [fromMs]; null with too little to count. */
    @Synchronized
    fun cut(fromMs: Long, toMs: Long, k: Float): RunTrace? {
        val e = ecu.filter { it.ms in fromMs..toMs }.map { it.p.copy(t = (it.ms - fromMs).toInt()) }
        val g = gps.filter { it.ms in fromMs..toMs }.map { it.p.copy(t = (it.ms - fromMs).toInt()) }
        if (e.size < 5 && g.size < 5) return null
        return RunTrace(fromMs, k, e, g)
    }

    @Synchronized
    fun clear() { ecu.clear(); gps.clear(); lastFix = 0L }
}
