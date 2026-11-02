package kg.forester.sg.service

import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuParams
import kg.forester.sg.ssm.EcuSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One raw sample of a pull: the speed as the ECU gave it, before the correction learned from GPS. */
data class DynoSample(val tMs: Long, val rpm: Int, val kmh: Float, val gas: Float?, val boostKpa: Float?)

/**
 * A pull kept for comparing: what it was driven in, its samples in their own file (see [DynoStore]). [speedK] is the
 * speedometer correction then, [rpmPerKmh] tells the gear, [algoVersion] the [DynoCalc.ALGO] it was saved with.
 */
data class DynoRun(val startMs: Long, val massKg: Float, val speedK: Float, val gear: Int?, val rpmPerKmh: Float,
    val label: String?, val iatC: Int?, val baroKpa: Float?, val coolantC: Int?, val iam: Float?, val algoVersion: Int)

/** A round step for [n] divisions of [range]: 1, 2, 2.5, 5 or 10 × 10^n. */
fun niceStep(range: Float, n: Int): Float {
    if (range <= 0f || n <= 0) return 1f
    val raw = range / n
    val mag = 10f.pow(floor(log10(raw)))
    val f = raw / mag
    return when {
        f <= 1f -> 1f
        f <= 2f -> 2f
        f <= 2.5f -> 2.5f
        f <= 5f -> 5f
        else -> 10f
    } * mag
}

/** The counting of a pull, all of it from the raw samples: a run of any age is counted again by the current code. */
object DynoCalc {
    /** Raised when the counting changes; the curves are never stored, only counted from the samples. */
    const val ALGO = 1
    /** A run needs this many cells to be a curve. */
    const val MIN_CELLS = 4
    private const val MIN_CELL_SAMPLES = 3
    private const val SPLIT_RPM_DROP = 200
    /** rpm/v further than this from the pull's own is another gear or a slipping clutch. */
    private const val RATIO_TOL = 0.08f
    /** Whole km/h under this are too coarse to tell an 8 % change of rpm/v. */
    private const val RATIO_MIN_KMH = 20f
    private const val CDA = 0.85 // Cd × frontal area, m² (Forester/Impreza class)
    private const val PLATEAU = 0.97f
    private const val MIN_SLOPE = 3f
    private const val MAX_SLOPE = 10f

    class Raw(val rpm: Int, val hp: Float, val nm: Float)

    /** The two vertical scales: [lines] divisions of [hpStep] on the left and of [nmStep] on the right, on the same lines. */
    data class Axes(val hpStep: Float, val nmStep: Float, val lines: Int)

    /**
     * The grade to count with, from the GPS altitude over a pull: its noise is as large as the effect of a gentle road,
     * so under 3 % the road counts as flat, and past 10 % it is an error of the altitude, no road.
     */
    fun usableSlope(s: Float?): Float? = s?.takeIf { abs(it) >= MIN_SLOPE && abs(it) <= MAX_SLOPE }

    private fun omega(rpm: Int) = 2 * PI * rpm / 60.0

    /** The gear's factor: v = k·ω, the regression through zero of the speed in m/s on the crank's rad/s over the run. */
    fun gearK(samples: List<DynoSample>, speedK: Float): Double {
        var vw = 0.0; var ww = 0.0
        for (s in samples) { val w = omega(s.rpm); vw += s.kmh * speedK / 3.6 * w; ww += w * w }
        return if (ww > 0) vw / ww else 0.0
    }

    fun rpmPerKmh(samples: List<DynoSample>, speedK: Float): Float {
        val kmh = samples.sumOf { (it.kmh * speedK).toDouble() }
        return if (kmh > 0) (samples.sumOf { it.rpm.toDouble() } / kmh).toFloat() else 0f
    }

    fun hz(samples: List<DynoSample>): Float {
        if (samples.size < 2) return 0f
        val ms = samples.last().tMs - samples.first().tMs
        return if (ms > 0) (samples.size - 1) * 1000f / ms else 0f
    }

    /** Half the smoothing window: ±150 ms where the samples are dense enough for it, else ±300 ms. */
    fun windowMs(samples: List<DynoSample>): Long = if (hz(samples) >= 20f) 150L else 300L

    /**
     * Power at the wheels at each sample: (m·a·δ + air + rolling + grade) · v. The ECU's speed comes in whole km/h, too
     * coarse for an acceleration; rpm has 0.25 steps, so a = k·dω/dt and v = k·ω ([gearK]). [slopePct] is the road's
     * grade, + uphill: a downhill's help comes off, a hill's load goes on (without it, it counts as flat).
     */
    fun raw(samples: List<DynoSample>, massKg: Float, speedK: Float, slopePct: Float? = null): List<Raw> {
        val k = gearK(samples, speedK)
        if (k <= 0.0) return emptyList()
        val half = windowMs(samples)
        val m = massKg.toDouble()
        val grade = m * 9.81 * (slopePct ?: 0f) / 100.0
        val out = ArrayList<Raw>(samples.size)
        var from = 0
        var to = 0
        for (i in samples.indices) {
            val t = samples[i].tMs
            while (samples[from].tMs < t - half) from++
            if (to < i) to = i
            while (to + 1 < samples.size && samples[to + 1].tMs <= t + half) to++
            if (to - from + 1 < 3) continue
            // dω/dt: the slope of a straight line through the window
            val t0 = samples[from].tMs
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (q in from..to) {
                val x = (samples[q].tMs - t0) / 1000.0; val y = omega(samples[q].rpm)
                sx += x; sy += y; sxx += x * x; sxy += x * y
            }
            val n = to - from + 1
            val den = n * sxx - sx * sx
            if (den < 1e-9) continue
            val a = k * (n * sxy - sx * sy) / den
            if (a < 0.1) continue
            val rpm = samples[i].rpm
            val w = omega(rpm)
            if (w <= 0.0) continue
            val v = k * w
            val force = m * a * PerfCalc.rotatingMass(rpm, v) + 0.5 * 1.2 * CDA * v * v + 0.015 * m * 9.81 + grade
            val watts = force * v
            out += Raw(rpm, (watts / 735.5).toFloat(), (watts / w).toFloat())
        }
        return out
    }

    /**
     * The samples binned by rpm. Cells of 100 rpm where they get 3 samples on average, else of 250; a cell under 3
     * samples is dropped, so no lone sample makes a peak. A cell stands at its centre: runs with cells of different
     * width then compare without a false shift of half a cell.
     */
    fun cells(raw: List<Raw>): List<DynoPoint> {
        if (raw.isEmpty()) return emptyList()
        val perCell = raw.size.toFloat() / raw.groupBy { it.rpm / 100 }.size
        val step = if (perCell >= MIN_CELL_SAMPLES) 100 else 250
        return raw.groupBy { it.rpm / step * step }.entries.sortedBy { it.key }.filter { it.value.size >= MIN_CELL_SAMPLES }.map { (r, l) ->
            DynoPoint(r + step / 2, l.map { it.hp }.average().toFloat(), l.map { it.nm }.average().toFloat())
        }
    }

    fun curve(samples: List<DynoSample>, massKg: Float, speedK: Float, slopePct: Float? = null): List<DynoPoint> =
        cells(raw(samples, massKg, speedK, slopePct))

    /** A pull cut at each shift: the revs falling by more than 200, or rpm/v leaving its value by more than 8 %. */
    fun split(samples: List<DynoSample>): List<List<DynoSample>> {
        val parts = ArrayList<List<DynoSample>>()
        var cur = ArrayList<DynoSample>()
        var peak = 0
        var rpms = 0.0; var kmhs = 0.0
        for (s in samples) {
            if (cur.isNotEmpty()) {
                val ratio = if (s.kmh >= RATIO_MIN_KMH && kmhs > 0) (s.rpm / s.kmh) / (rpms / kmhs).toFloat() else 1f
                if (s.rpm < peak - SPLIT_RPM_DROP || abs(ratio - 1f) > RATIO_TOL) {
                    parts += cur
                    cur = ArrayList()
                    peak = 0; rpms = 0.0; kmhs = 0.0
                }
            }
            cur += s
            peak = maxOf(peak, s.rpm)
            if (s.kmh >= RATIO_MIN_KMH) { rpms += s.rpm; kmhs += s.kmh }
        }
        if (cur.isNotEmpty()) parts += cur
        return parts
    }

    /**
     * The torque's plateau: the longest stretch of rpm at 97 % of its peak or more. It has to be contiguous: a hole of
     * more than 2 cells in the curve (a full read in the pull, cells dropped for too few samples) ends the stretch, as
     * nothing is known of the torque there.
     */
    fun plateau(curve: List<DynoPoint>): IntRange? {
        val top = (curve.maxOfOrNull { it.nm } ?: return null) * PLATEAU
        val cell = curve.zipWithNext { a, b -> b.rpm - a.rpm }.minOrNull() ?: 0
        var best: IntRange? = null
        var start: Int? = null
        var prev: Int? = null
        for (c in curve) {
            if (prev != null && c.rpm - prev > cell * 3) start = null
            prev = c.rpm
            if (c.nm >= top) {
                if (start == null) start = c.rpm
                if (best == null || c.rpm - start > best.last - best.first) best = start..c.rpm
            } else start = null
        }
        return best
    }

    /** Where the power starts to fall: the first rpm above its peak under 97 % of it; null when the pull ended first. */
    fun dropAfter(curve: List<DynoPoint>): Int? {
        val peak = curve.withIndex().maxByOrNull { it.value.hp } ?: return null
        return curve.drop(peak.index + 1).firstOrNull { it.hp < peak.value.hp * PLATEAU }?.rpm
    }

    /** The rpm as the words give them: the cells stand at their centres, and ..375 says no more than ..400. */
    private fun round50(x: Int) = (x + 25) / 50 * 50

    private fun shift(d: Int) = round50(abs(d)).let { if (d > 0) "+$it" else if (d < 0) "-$it" else "0" }

    /** What moved against [ref], in words: the plateau of the torque, then where the power falls. */
    fun compareLines(cur: List<DynoPoint>, ref: List<DynoPoint>?): List<String> {
        val out = ArrayList<String>(2)
        val p = plateau(cur)
        val rp = ref?.let { plateau(it) }
        if (p != null) {
            val text = "Плато момента ${round50(p.first)}–${round50(p.last)}"
            out += if (rp == null) text
                else "$text, было ${round50(rp.first)}–${round50(rp.last)} (${shift((p.first + p.last) / 2 - (rp.first + rp.last) / 2)})"
        }
        val d = dropAfter(cur)
        val rd = ref?.let { dropAfter(it) }
        out += when {
            d != null && rd != null -> "Мощность падает после ${round50(d)}, было ${round50(rd)} (${shift(d - rd)})"
            d != null && ref != null -> "Мощность падает после ${round50(d)}, в прошлом заезде спада не видно"
            d != null -> "Мощность падает после ${round50(d)}"
            rd != null -> "Мощность не упала до конца заезда, было ${round50(rd)}"
            else -> "Мощность не упала до конца заезда"
        }
        return out
    }

    /** Two runs that aren't in one gear: rpm/v differs by more than 8 %. */
    fun otherGear(a: DynoRun, b: DynoRun): Boolean =
        b.rpmPerKmh <= 0f || abs(a.rpmPerKmh / b.rpmPerKmh - 1f) > RATIO_TOL

    /** The run before [cur] in the same gear, from [runs] newest first. */
    fun previousSameGear(runs: List<DynoRun>, cur: DynoRun): DynoRun? =
        runs.firstOrNull { it.startMs < cur.startMs && !otherGear(cur, it) }

    /** The power of [cur] against [ref] in %, cell by cell over the rpm both have, [ref] taken between its cells. */
    fun diffPct(cur: List<DynoPoint>, ref: List<DynoPoint>): List<Pair<Int, Float>> {
        if (ref.isEmpty()) return emptyList()
        return cur.mapNotNull { c ->
            if (c.rpm < ref.first().rpm || c.rpm > ref.last().rpm) return@mapNotNull null
            val i = ref.indexOfFirst { it.rpm >= c.rpm }
            val b = ref[i]
            val hp = if (b.rpm == c.rpm || i == 0) b.hp else {
                val a = ref[i - 1]
                a.hp + (b.hp - a.hp) * (c.rpm - a.rpm) / (b.rpm - a.rpm)
            }
            if (hp <= 0f) null else c.rpm to (c.hp / hp - 1f) * 100f
        }
    }

    /** Each curve to its own peak: 100 % at the top of the power and of the torque. */
    fun percentOfPeak(curve: List<DynoPoint>): List<DynoPoint> {
        val hp = curve.maxOfOrNull { it.hp }?.takeIf { it > 0f } ?: return curve
        val nm = curve.maxOfOrNull { it.nm }?.takeIf { it > 0f } ?: return curve
        return curve.map { DynoPoint(it.rpm, it.hp / hp * 100f, it.nm / nm * 100f) }
    }

    private fun nextStep(step: Float): Float {
        val mag = 10f.pow(floor(log10(step)))
        val f = step / mag
        return when {
            f <= 1.001f -> 2f
            f <= 2.001f -> 2.5f
            f <= 2.501f -> 5f
            f <= 5.001f -> 10f
            else -> 20f
        } * mag
    }

    /** The scales of both: the lines by the power, the torque's step fitting its peak on as many lines. */
    fun axes(hpPeak: Float, nmPeak: Float): Axes {
        val hp = hpPeak.coerceAtLeast(1f)
        val nm = nmPeak.coerceAtLeast(1f)
        val hpStep = niceStep(hp, 5)
        val lines = ceil(hp * 1.05f / hpStep).toInt().coerceAtLeast(1)
        var nmStep = niceStep(nm, lines)
        while (lines * nmStep < nm * 1.05f) nmStep = nextStep(nmStep)
        return Axes(hpStep, nmStep, lines)
    }

    /** The vertical lines every 500 rpm, around [from]..[to], at least one division. */
    fun rpmTicks(from: Int, to: Int): List<Int> {
        val a = from / 500 * 500
        val b = maxOf(a + 500, (to + 499) / 500 * 500)
        return (a..b step 500).toList()
    }
}

/**
 * Follows the pedal: each pull gets its samples and, when it ends, is cut at the shifts and counted. A new pull clears
 * [curve] from its first sample, so one too short to count leaves nothing of the last curve behind ([rejected]). A pull
 * belongs to the demo or to the car from its start: the other one's samples never finish it, it is thrown away. A gap
 * in the samples of over 2 s (the link lost) ends it: nothing is measured across that.
 */
class DynoTracker {
    /** A part of a pull between shifts that makes a curve. */
    class Done(val samples: List<DynoSample>, val curve: List<DynoPoint>, val demo: Boolean)

    private val pull = ArrayList<DynoSample>()
    private var pullDemo = false
    var curve: List<DynoPoint> = emptyList(); private set
    var pulling = false; private set
    var rejected = false; private set
    /** Counts the pulls started: one ending and the next starting can come with the same sample. */
    var started = 0; private set

    /** The next sample of a pull, or null when the pedal is not down; what a pull gave when it ends. */
    fun feed(s: DynoSample?, massKg: Float, speedK: Float, demo: Boolean = false): List<Done> {
        if (pulling && demo != pullDemo) { pull.clear(); pulling = false; rejected = false }
        if (s == null) return if (pulling) finish(massKg, speedK) else emptyList()
        val ended = if (pulling && s.tMs - pull.last().tMs > MAX_GAP_MS) finish(massKg, speedK) else emptyList()
        if (!pulling) { pulling = true; pullDemo = demo; rejected = false; curve = emptyList(); pull.clear(); started++ }
        // the clock set back (by GPS time) would make a window go wrong
        if (pull.isEmpty() || s.tMs > pull.last().tMs) pull += s
        return if (pull.size >= MAX_SAMPLES) ended + finish(massKg, speedK) else ended
    }

    private fun finish(massKg: Float, speedK: Float): List<Done> {
        val list = pull.toList()
        pull.clear()
        pulling = false
        // two seconds or more, by the time: the rate depends on the cable and the list
        val done = if (list.size >= 5 && list.last().tMs - list.first().tMs >= MIN_MS) {
            DynoCalc.split(list).map { Done(it, DynoCalc.curve(it, massKg, speedK), pullDemo) }.filter { it.curve.size >= DynoCalc.MIN_CELLS }
        } else emptyList()
        curve = done.maxByOrNull { it.samples.size }?.curve ?: emptyList()
        rejected = done.isEmpty()
        return done
    }

    private companion object {
        const val MIN_MS = 2000L
        const val MAX_GAP_MS = 2000L
        /** A pedal held for minutes (a stuck sensor) must not grow without end. */
        const val MAX_SAMPLES = 6000
    }
}

/**
 * The fast poll of the dyno. With the dyno on the screen and the pedal floored the ECU is asked only for the rpm, speed,
 * gas and manifold pressure (and the ROM's own FBKC and direct pressure, where the watched list has them proven to
 * answer), and the transmission is left out. Everything else stays what the last full read gave ([merge]), so nothing on
 * the screen disappears; but a merged frame's old values with a new time must not count as seen again, so those frames
 * don't go to the rules ([route]). No full read in the first [FIRST_FULL_MS] of a pull, as each one leaves a hole in the
 * measured rpm; then one every [FULL_EVERY_MS]. After [MAX_SHORT_MS] the full list comes back until the pedal is lifted.
 * Any other time nothing changes.
 */
class DynoPoll {
    /** The last full snapshot. */
    var full: EcuSnapshot? = null; private set
    private var fullMs = 0L
    private var on = false
    private var sinceMs = 0L
    private var spent = false

    fun wanted(tab: Boolean, live: EcuSnapshot, now: Long): Boolean {
        val gas = live.pedalPct ?: live.throttlePct
        if (!tab || gas == null || live.speedKmh == null || gas < GAS_OFF) { on = false; spent = false; return false }
        if (!on && !spent && gas > GAS_ON && live.speedKmh > MIN_KMH) { on = true; sinceMs = now }
        if (on && now - sinceMs >= MAX_SHORT_MS) { on = false; spent = true }
        return on
    }

    fun noteFull(s: EcuSnapshot, now: Long) { full = s; fullMs = now }

    /** A full read now, in a pull: there being nothing to merge into, or past its first seconds and the last read. */
    fun fullDue(now: Long) = full == null || (now - sinceMs >= FIRST_FULL_MS && now - fullMs >= FULL_EVERY_MS)

    /** Who gets a frame, besides the live values, the trip and the timer, which get them all. */
    class Route(val rules: Boolean, val scales: Boolean, val knock: Boolean)

    companion object {
        const val FULL_EVERY_MS = 2000L
        const val FIRST_FULL_MS = 6000L
        const val MAX_SHORT_MS = 30_000L
        /** As the pull itself counts it (see [Perf]); lower on the way down, so a wobble doesn't restart the stream. */
        private const val GAS_ON = 85f
        private const val GAS_OFF = 75f
        private const val MIN_KMH = 10

        /**
         * A merged frame reaches neither the rules (a sustained rule would count an old value as held, a pull's peak and
         * spool point would be made of them) nor the learned scales. The knock events take it only with the FBKC read in
         * that very frame ([knockFresh]).
         */
        fun route(merged: Boolean, knockFresh: Boolean) = Route(!merged, !merged, !merged || knockFresh)

        /**
         * The short list out of what this ECU answers, with [extras] (the ROM's own FBKC and pressure) if one request can
         * carry them: null where it can't work without rpm, speed and gas, or where a cable asks one address at a time
         * (ELM327, too slow for this at any list).
         */
        fun shortList(addrs: IntArray, skip: Set<Int>, maxReadAddrs: Int, extras: IntArray = IntArray(0)): IntArray? {
            if (maxReadAddrs < 5) return null
            val has = { a: Int -> a in addrs && a !in skip }
            if (!has(EcuParams.RPM_H) || !has(EcuParams.RPM_L) || !has(EcuParams.SPEED)) return null
            if (!has(EcuParams.THROTTLE) && !has(EcuParams.PEDAL)) return null
            val base = intArrayOf(EcuParams.RPM_H, EcuParams.RPM_L, EcuParams.SPEED, EcuParams.THROTTLE, EcuParams.PEDAL, EcuParams.MAP).filter(has)
            val more = extras.filter { it !in base }.distinct()
            return (if (base.size + more.size <= maxReadAddrs) base + more else base).toIntArray()
        }

        /** The short frame [short] over the last full snapshot [full]. */
        fun merge(full: EcuSnapshot, short: EcuSnapshot): EcuSnapshot = full.copy(
            timeMs = short.timeMs,
            rpm = short.rpm ?: full.rpm,
            speedKmh = short.speedKmh ?: full.speedKmh,
            throttlePct = short.throttlePct ?: full.throttlePct,
            pedalPct = short.pedalPct ?: full.pedalPct,
            mapKpa = short.mapKpa ?: full.mapKpa,
        )
    }
}

/**
 * The pulls kept, 100 at most, apart from the acceleration runs («Очистить список» there leaves them). The samples:
 * `Perf.dir(demo)/dyno/<startMs>.txt`, a header `dyno 1 <startMs> <massKg> <speedK>`, a line
 * `s <tMs> <rpm> <kmh> <gas> <boostKpa>` per sample and a last `g <slope %>` when a grade was counted with. The rest
 * of [DynoRun] is in `Repo.settings.perfDyno`. A file that can't be read, or from a format not known here, is left out
 * of the list. The demo's are shown but never saved: their list is in memory, their files in the cache.
 */
object DynoStore {
    private const val KEEP = 100

    /** A file's content. */
    class Decoded(val startMs: Long, val massKg: Float, val speedK: Float, val slopePct: Float?, val samples: List<DynoSample>)

    /** A run counted from its file, for the screen. */
    class View(val curve: List<DynoPoint>, val slopePct: Float?, val hz: Float)

    private var carRuns: List<DynoRun>? = null
    private var demoRuns: List<DynoRun> = emptyList()
    private val views = HashMap<Long, View>()

    private fun dir(demo: Boolean) = File(Perf.dir(demo), "dyno")
    private fun file(dir: File, startMs: Long) = File(dir, "$startMs.txt")
    private fun isDemo(r: DynoRun) = demoRuns.any { it.startMs == r.startMs }

    /** Keeps [run] first with its [samples]; [slopePct], the road's grade if one was counted with, goes with them. */
    @Synchronized
    fun save(run: DynoRun, samples: List<DynoSample>, slopePct: Float? = null, demo: Boolean) {
        val d = dir(demo)
        write(d, run, samples, slopePct)
        store(demo, prune(d, listOf(run) + list(demo).filter { it.startMs != run.startMs }))
    }

    /** Newest first. Reads the files the first time: not on the main thread. */
    @Synchronized
    fun list(demo: Boolean): List<DynoRun> =
        if (demo) demoRuns else carRuns ?: scan(dir(false), Repo.settings.perfDyno).also { carRuns = it }

    /** The samples of [r], empty when its file is gone. */
    @Synchronized
    fun samples(r: DynoRun): List<DynoSample> = read(r)?.samples ?: emptyList()

    /** The curve of [r] with its grade, counted from the samples once. Reads a file: not on the main thread. */
    @Synchronized
    fun view(r: DynoRun): View = views.getOrPut(r.startMs) {
        val f = read(r)
        if (f == null) View(emptyList(), null, 0f)
        else View(DynoCalc.curve(f.samples, r.massKg, r.speedK, f.slopePct), f.slopePct, DynoCalc.hz(f.samples))
    }

    @Synchronized
    fun delete(r: DynoRun) {
        val demo = isDemo(r)
        file(dir(demo), r.startMs).delete()
        views.remove(r.startMs)
        store(demo, list(demo).filter { it.startMs != r.startMs })
    }

    @Synchronized
    fun setLabel(r: DynoRun, label: String?) {
        val demo = isDemo(r)
        store(demo, list(demo).map { if (it.startMs == r.startMs) it.copy(label = label) else it })
    }

    /** Three made-up pulls, so the demo shows the comparison before its own first one. */
    @Synchronized
    fun seedDemo(now: Long) {
        if (demoRuns.isNotEmpty()) return
        listOf(Triple(now - 86_400_000L, 0, 24.5), Triple(now - 7_200_000L, 0, 35.0), Triple(now - 3_600_000L, 250, 35.0)).forEachIndexed { i, (t, shift, ratio) ->
            val s = Demo.dynoPull(t, shift, ratio)
            save(DynoRun(t, 1550f, 1f, if (ratio > 30) 3 else 4, DynoCalc.rpmPerKmh(s, 1f), listOf(null, "95", "98")[i], 25, 99f, 90, 1f, DynoCalc.ALGO), s, demo = true)
        }
    }

    private fun store(demo: Boolean, runs: List<DynoRun>) {
        if (demo) demoRuns = runs
        else { carRuns = runs; Repo.settings.perfDyno = metaJson(runs) }
    }

    private fun read(r: DynoRun): Decoded? =
        runCatching { decode(file(dir(isDemo(r)), r.startMs).readText()) }.getOrNull()

    // --- the files, free of the app's state

    fun encode(run: DynoRun, samples: List<DynoSample>, slopePct: Float?): String = buildString {
        fun f(v: Float?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
        append(String.format(Locale.US, "dyno 1 %d %.1f %.4f\n", run.startMs, run.massKg, run.speedK))
        for (s in samples) append("s ${s.tMs} ${s.rpm} ${f(s.kmh)} ${f(s.gas)} ${f(s.boostKpa)}\n")
        if (slopePct != null) append(String.format(Locale.US, "g %.2f\n", slopePct))
    }

    fun write(dir: File, run: DynoRun, samples: List<DynoSample>, slopePct: Float?) {
        dir.mkdirs()
        // a whole file in one step, as far as the flash storage keeps its promise on a power cut: one cut off at the end
        // of a line would read as a shorter pull
        val tmp = File(dir, "${run.startMs}.tmp")
        java.io.FileOutputStream(tmp).use { o ->
            o.write(encode(run, samples, slopePct).toByteArray())
            o.fd.sync()
        }
        if (!tmp.renameTo(file(dir, run.startMs))) {
            tmp.delete()
            throw java.io.IOException("dyno: can't rename ${tmp.name}")
        }
    }

    /**
     * Null for what isn't this format: another header, a bad line, no samples. A file cut off at the end of a line can't
     * be told from a shorter pull; [write] goes through a temp file and a rename to make that unlikely.
     */
    fun decode(text: String): Decoded? {
        fun num(s: String) = s.toFloatOrNull()?.takeIf { it.isFinite() }
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val h = lines.firstOrNull()?.split(' ') ?: return null
        if (h.size != 5 || h[0] != "dyno" || h[1] != "1") return null
        val start = h[2].toLongOrNull() ?: return null
        val mass = num(h[3])?.takeIf { it > 0f } ?: return null
        val k = num(h[4])?.takeIf { it > 0f } ?: return null
        var slope: Float? = null
        val out = ArrayList<DynoSample>(lines.size)
        for (l in lines.drop(1)) {
            val p = l.split(' ')
            when {
                p[0] == "s" && p.size == 6 -> {
                    val t = p[1].toLongOrNull() ?: return null
                    if (out.isNotEmpty() && t <= out.last().tMs) return null
                    val rpm = p[2].toIntOrNull() ?: return null
                    val kmh = num(p[3]) ?: return null
                    val gas = if (p[4] == "-") null else num(p[4]) ?: return null
                    val boost = if (p[5] == "-") null else num(p[5]) ?: return null
                    out += DynoSample(t, rpm, kmh, gas, boost)
                }
                p[0] == "g" && p.size == 2 -> slope = num(p[1]) ?: return null
                else -> return null
            }
        }
        return if (out.isEmpty()) null else Decoded(start, mass, k, slope, out)
    }

    fun metaJson(runs: List<DynoRun>): String {
        val arr = JSONArray()
        runs.forEach { r ->
            arr.put(JSONObject().put("t", r.startMs).put("m", r.massKg.toDouble()).put("k", r.speedK.toDouble()).put("g", r.gear)
                .put("rk", r.rpmPerKmh.toDouble()).put("lb", r.label).put("iat", r.iatC).put("baro", r.baroKpa?.toDouble())
                .put("ect", r.coolantC).put("iam", r.iam?.toDouble()).put("av", r.algoVersion))
        }
        return arr.toString()
    }

    private fun parseMeta(meta: String): Map<Long, DynoRun> = runCatching {
        val arr = JSONArray(meta)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            fun fl(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k).toFloat() else null
            DynoRun(o.getLong("t"), fl("m") ?: 0f, fl("k") ?: 1f, int("g"), fl("rk") ?: 0f, if (o.isNull("lb")) null else o.getString("lb"),
                int("iat"), fl("baro"), int("ect"), fl("iam"), o.optInt("av", 1))
        }.associateBy { it.startMs }
    }.getOrDefault(emptyMap())

    /** The runs whose files read well, newest first; the metadata of each if it is there, else what its file says. */
    fun scan(dir: File, meta: String): List<DynoRun> {
        val known = parseMeta(meta)
        // what a crash left in the middle of a write
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.name.endsWith(".txt") } ?: return emptyList()
        return files.mapNotNull { f ->
            val d = runCatching { decode(f.readText()) }.getOrNull() ?: return@mapNotNull null
            if (f.name != "${d.startMs}.txt") return@mapNotNull null
            known[d.startMs] ?: DynoRun(d.startMs, d.massKg, d.speedK, null, DynoCalc.rpmPerKmh(d.samples, d.speedK), null, null, null, null, null, DynoCalc.ALGO)
        }.sortedByDescending { it.startMs }
    }

    /** The newest [KEEP] of [runs]; the others' files go. */
    fun prune(dir: File, runs: List<DynoRun>): List<DynoRun> {
        val sorted = runs.sortedByDescending { it.startMs }
        sorted.drop(KEEP).forEach { file(dir, it.startMs).delete(); views.remove(it.startMs) }
        return sorted.take(KEEP)
    }
}
