package kg.forester.sg.service

import kotlin.math.abs
import kotlin.math.sqrt

/** An interval of an acceleration run in km/h; [from] = 0 is from a standstill, anything else on the move. */
data class Interval(val from: Int, val to: Int) {
    val key get() = "$from-$to"

    companion object {
        fun parse(key: String): Interval? {
            val p = key.split('-')
            if (p.size != 2) return null
            val a = p[0].trim().toIntOrNull() ?: return null
            val b = p[1].trim().toIntOrNull() ?: return null
            return if (a >= 0 && b > a && b <= 350) Interval(a, b) else null
        }

        /** "0-100,100-150" → the intervals, standing ones first, each set once. */
        fun list(s: String): List<Interval> = s.split(',').mapNotNull { parse(it) }.distinct().sortedWith(compareBy({ it.from != 0 }, { it.from }, { it.to }))
    }
}

/** The speed a run is counted on: [t] seconds from the start of the trace, [v] km/h; [source] says where it came from. */
class SpeedSeries(val t: DoubleArray, val v: DoubleArray, val source: String, val gps: Boolean) {
    val size get() = t.size

    /** Samples a second over the series. */
    val hz: Double get() = if (size >= 2) (size - 1) / (t[size - 1] - t[0]).coerceAtLeast(0.001) else 0.0

    /**
     * How close the times are: a crossing falls between two samples, and at 150–200 km/h the speed grows ~1 km/h in
     * 0.1 s. GPS from 10 Hz or the ECU's fast poll (~12 a second) keep it near ±0.1 s; GPS at 4–5 Hz or the plain ECU
     * polling (~4 a second, 100–200 off by ~0.25 s) about ±0.3 s.
     */
    val precisionSec: Double get() = if (hz >= (if (gps) 9.0 else 8.0)) 0.1 else 0.3

    /** «GPS 10 Гц, точность около ±0,1 с». */
    fun precisionText(): String =
        (if (gps) "GPS %.0f Гц".format(hz) else "ЭБУ ~%.0f Гц".format(hz)) + ", точность около ±%s с".format(if (precisionSec < 0.2) "0,1" else "0,3")

    /** The speed at [time], straight between the samples. */
    fun at(time: Double): Double {
        if (size == 0) return 0.0
        if (time <= t[0]) return v[0]
        for (k in 1 until size) if (t[k] >= time) {
            val dt = t[k] - t[k - 1]
            return if (dt <= 0) v[k] else v[k - 1] + (v[k] - v[k - 1]) * (time - t[k - 1]) / dt
        }
        return v[size - 1]
    }
}

/**
 * One timed interval: [sec] long, from [from] to [to] (s on the trace); [trap] is the speed at a distance mark, [dist]
 * the metres a braking took.
 */
data class Hit(val key: String, val sec: Double, val from: Double, val to: Double, val trap: Double? = null, val dist: Double? = null)

/** A gear change: from the rpm peak to the trough after it (what the G drop lasts), at [kmh]. */
data class Shift(val from: Double, val to: Double, val kmh: Double, val rpmFrom: Int, val rpmTo: Int) {
    val sec get() = to - from
}

/** A point of the acceleration graph. */
data class GPt(val t: Double, val kmh: Double, val g: Double)

/** A run counted: the speed used, the moment of the start from a standstill (null: rolling), the intervals, the shifts. */
class Measured(val speed: SpeedSeries, val launch: Double?, val hits: List<Hit>, val shifts: List<Shift>) {
    /**
     * The results as kept with a run: interval → seconds, the trap speed at 201/402 m as "201v"/"402v", the braking
     * distance as "100-0d".
     */
    fun results(): Map<String, Float> = LinkedHashMap<String, Float>().also { m ->
        for (h in hits) {
            m[h.key] = h.sec.toFloat()
            h.trap?.let { m[h.key.replace("m", "v")] = it.toFloat() }
            h.dist?.let { m[h.key + PerfCalc.DIST] = it.toFloat() }
        }
    }

    /** A braking from [PerfCalc.BRAKE_FROM] to a stop, nothing else: no launch, no interval timed. */
    val braking: Boolean get() = launch == null && hits.isNotEmpty() && hits.all { it.key == PerfCalc.BRAKE }

    /** Where the timed part of the run ends. */
    val end: Double? get() = hits.maxOfOrNull { it.to }
    val start: Double? get() = launch ?: hits.minOfOrNull { it.from }
}

data class Stats(val n: Int, val mean: Double, val sd: Double, val min: Double, val max: Double)

/**
 * Two series compared: B − A in seconds, the half-width of its 95 % interval, and whether the difference is more
 * than the scatter explains (null: too few runs to say).
 */
data class Verdict(val diff: Double, val margin: Double?, val significant: Boolean?)

data class SpoolPt(val rpm: Int, val kpa: Float)

/** The spool of one pull: rpm where boost went through 0, through 1 bar, and up to the target. */
data class SpoolResult(val rpm0: Int?, val rpm1: Int?, val rpmTarget: Int?, val peakKpa: Float)

/**
 * The counting behind «Замеры», all on real sample times (the ECU's fast poll, GPS at 1–10 Hz), crossings
 * interpolated between samples, never by counting samples.
 */
object PerfCalc {
    /**
     * The version of the counting kept with each run: kept runs counted by an older one are counted again from their
     * samples. 2: distances timed as on a drag strip (roll-out, 66 ft trap), the line crossed at a steady acceleration.
     */
    const val ALGO = 2

    /** Distances from a standstill: 60 ft, 1/8 and 1/4 mile. */
    val DISTANCES = listOf(18.288 to "18m", 201.168 to "201m", 402.336 to "402m")

    /**
     * Roll-out: a drag strip's clock starts when the front tyre leaves the staging beam, about one foot after the car
     * moves: 0.2–0.4 s less than from the first movement. Dragy times its distances (60 ft, 1/8, 1/4 mile) so, from one
     * foot to the mark plus one foot, and so are the distances here. Its 0–60 / 0–100 run from the first movement
     * (0.8 km/h), as "0-100" here; "0-100r" is the same from one foot on, as US magazines time 0–60 mph.
     */
    const val ROLLOUT_M = 0.3048
    const val ROLLOUT = "r"

    /** Trap speed: the average over the last 66 ft before the line, as a drag strip's timing beams take it. */
    const val TRAP_M = 20.1168

    /**
     * Braking from 100 km/h to a stop (Dragy and Multitronics time it too): the time and the distance ("100-0d"). A
     * stop counts as a test with 0.5 g or more on average (under 5.7 s); an ordinary stop takes twice as long.
     */
    const val BRAKE_FROM = 100
    const val BRAKE = "100-0"
    const val DIST = "d"
    const val BRAKE_MAX_SEC = 5.7

    /** A standing interval timed with the roll-out ("0-100r"). */
    fun isRollout(key: String) = key.startsWith("0-") && key.endsWith(ROLLOUT)

    const val WOT_PEDAL = 95f
    // a cable throttle's sensor rarely reads the full 100 % with the pedal down
    const val WOT_THROTTLE = 90f

    // ---- the speed

    /**
     * GPS speed (Doppler, not the wheels: no wheelspin, no tyre size) when it comes often enough to time on (an
     * external receiver, 4 Hz and up); otherwise the ECU speed, ~12 samples a second, scaled to the GPS of this very
     * run when there is enough of it, else by the correction the app has learned.
     */
    fun speed(tr: RunTrace): SpeedSeries {
        val good = tr.gps.filter { it.acc <= 20f }
        val hz = if (good.size >= 2) (good.size - 1) / ((good.last().t - good.first().t) / 1000.0).coerceAtLeast(0.001) else 0.0
        val acc = good.map { it.acc }.sorted().getOrNull(good.size / 2) ?: 99f
        if ((good.size >= 8 && hz >= 4.0 && acc <= 10f) || (tr.ecu.size < 5 && good.size >= 5)) {
            return SpeedSeries(DoubleArray(good.size) { good[it].t / 1000.0 }, DoubleArray(good.size) { good[it].kmh.toDouble() },
                "GPS %.0f Гц".format(hz), true)
        }
        val fit = fitK(tr)
        val k = (fit ?: tr.k).toDouble()
        val e = tr.ecu
        val ehz = if (e.size >= 2) (e.size - 1) / ((e.last().t - e.first().t) / 1000.0).coerceAtLeast(0.001) else 0.0
        return SpeedSeries(DoubleArray(e.size) { e[it].t / 1000.0 }, DoubleArray(e.size) { e[it].kmh * k },
            "ЭБУ ~%.0f Гц ×%.3f".format(ehz, k) + if (fit != null) " по GPS замера" else "", false)
    }

    /** The raw ECU speed at [ms] on the trace, null outside it. */
    private fun ecuAt(e: List<EcuPt>, ms: Double): Double? {
        if (e.isEmpty() || ms < e.first().t || ms > e.last().t) return null
        for (k in 1 until e.size) if (e[k].t >= ms) {
            val dt = (e[k].t - e[k - 1].t).toDouble()
            return if (dt <= 0) e[k].kmh.toDouble() else e[k - 1].kmh + (e[k].kmh - e[k - 1].kmh) * (ms - e[k - 1].t) / dt
        }
        return e.last().kmh.toDouble()
    }

    /**
     * The ECU speed's factor to the GPS of this run (the ECU reads a few km/h low; other tyres more). The fix
     * arrives late, so the lag is fitted too: the one that leaves the least scatter. Null with under 5 good fixes.
     */
    fun fitK(tr: RunTrace): Float? {
        val g = tr.gps.filter { it.acc <= 15f && it.kmh >= 30f }
        if (g.size < 5 || tr.ecu.size < 5) return null
        var best = Double.MAX_VALUE; var bestK = 0.0
        // GPS fixes are stamped when they were taken (the Doppler speed is still a little behind), ECU samples when they came
        for (lag in -300..1500 step 50) {
            var ge = 0.0; var ee = 0.0; var n = 0
            val pairs = g.mapNotNull { p -> ecuAt(tr.ecu, (p.t - lag).toDouble())?.takeIf { it >= 25 }?.let { p.kmh.toDouble() to it } }
            for ((a, b) in pairs) { ge += a * b; ee += b * b; n++ }
            if (n < 5) continue
            val k = ge / ee
            val res = pairs.sumOf { (a, b) -> (a - k * b) * (a - k * b) } / n
            if (res < best) { best = res; bestK = k }
        }
        return bestK.takeIf { best < Double.MAX_VALUE && it in 0.85..1.2 }?.toFloat()
    }

    // ---- times

    /** When the speed went from below [level] to it between samples k−1 and k. */
    private fun cross(s: SpeedSeries, k: Int, level: Double): Double {
        val dv = s.v[k] - s.v[k - 1]
        return if (dv <= 0) s.t[k] else s.t[k - 1] + (level - s.v[k - 1]) / dv * (s.t[k] - s.t[k - 1])
    }

    /** The first time after [after] the speed goes up through [level]. */
    fun upCross(s: SpeedSeries, level: Double, after: Double = Double.NEGATIVE_INFINITY): Double? {
        for (k in 1 until s.size) if (s.t[k] > after && s.v[k - 1] < level && s.v[k] >= level) return cross(s, k, level)
        return null
    }

    /**
     * The moment of the start from a standstill: the samples right after moving off (up to 20 km/h, a second) on a
     * straight line back to zero, so a speed sensor that shows nothing below 3–4 km/h does not start the clock late.
     * Kept within 0.8 s before the first sample moving. Null if the trace does not start standing.
     */
    fun launch(s: SpeedSeries): Double? {
        var i = -1
        var moved = false
        for (k in 0 until s.size) {
            if (s.v[k] < 0.5) i = k
            else if (s.v[k] >= 5 && i >= 0) { moved = true; break }
        }
        if (!moved) return null
        val j = i + 1
        val lin = cross(s, j, 0.5)
        val pts = (j until s.size).takeWhile { s.v[it] <= 20 && s.t[it] - s.t[j] <= 1.0 }
        var t0: Double? = null
        if (pts.size >= 2) {
            val n = pts.size
            val mt = pts.sumOf { s.t[it] } / n; val mv = pts.sumOf { s.v[it] } / n
            val sxx = pts.sumOf { (s.t[it] - mt) * (s.t[it] - mt) }
            val a = if (sxx > 1e-9) pts.sumOf { (s.t[it] - mt) * (s.v[it] - mv) } / sxx else 0.0
            if (a > 1.0) t0 = mt - mv / a
        }
        // the samples still at zero may already be moving (a dead sensor below a few km/h): up to 0.8 s back is allowed
        return (t0 ?: lin).coerceIn(minOf(s.t[i], s.t[j] - 0.8), s.t[j])
    }

    /**
     * A rolling interval: from the last time the speed went up through [Interval.from] to it going up through
     * [Interval.to]. The trace must come from below the lower bound (as with a GPS meter).
     */
    fun rolling(s: SpeedSeries, iv: Interval): Hit? {
        var from: Double? = null
        for (k in 1 until s.size) {
            if (s.v[k - 1] < iv.from && s.v[k] >= iv.from) from = cross(s, k, iv.from.toDouble())
            val f = from
            if (f != null && s.v[k - 1] < iv.to && s.v[k] >= iv.to) {
                val to = cross(s, k, iv.to.toDouble())
                return Hit(iv.key, to - f, f, to)
            }
        }
        return null
    }

    /**
     * When the distance from [launch] (speed integrated) reaches [m], and the speed there. Within the last step the
     * acceleration is taken as steady (the distance grows as v₀t + at²/2, as Dragy finishes a distance), not the speed:
     * with a few samples a second a steady speed puts the line late.
     */
    fun distance(s: SpeedSeries, launch: Double, m: Double): Pair<Double, Double>? {
        var d = 0.0; var pt = launch; var pv = 0.0
        for (k in 0 until s.size) {
            if (s.t[k] <= launch) continue
            val dt = s.t[k] - pt
            val seg = (pv + s.v[k]) / 2 / 3.6 * dt
            if (seg > 0 && d + seg >= m) {
                val v0 = pv / 3.6
                val a = (s.v[k] - pv) / 3.6 / dt
                val rem = m - d
                val x = if (abs(a) < 1e-9) rem / v0 else (-v0 + sqrt((v0 * v0 + 2 * a * rem).coerceAtLeast(0.0))) / a
                val tx = x.coerceIn(0.0, dt)
                return (pt + tx) to (v0 + a * tx) * 3.6
            }
            d += seg; pt = s.t[k]; pv = s.v[k]
        }
        return null
    }

    /**
     * A distance as a drag strip times it: from one foot of roll-out to [m] plus that foot, and the trap speed over
     * the last 66 ft ([TRAP_M]) before the line; 60 ft has no trap. Null when the trace ends before the line.
     */
    fun dragDistance(s: SpeedSeries, launch: Double, m: Double, key: String): Hit? {
        val from = distance(s, launch, ROLLOUT_M)?.first ?: return null
        val (to, _) = distance(s, launch, m + ROLLOUT_M) ?: return null
        val trap = if (m > 2 * TRAP_M) distance(s, launch, m + ROLLOUT_M - TRAP_M)?.first?.let { t0 ->
            (to - t0).takeIf { it > 0 }?.let { TRAP_M / it * 3.6 }
        } else null
        return Hit(key, to - from, from, to, trap)
    }

    fun measure(tr: RunTrace, intervals: List<Interval>): Measured {
        val s = speed(tr)
        if (s.size < 3) return Measured(s, null, emptyList(), emptyList())
        val launch = launch(s)
        val rollout = launch?.let { distance(s, it, ROLLOUT_M)?.first }
        val hits = ArrayList<Hit>()
        for (iv in intervals) {
            if (iv.from == 0) {
                if (launch != null) upCross(s, iv.to.toDouble(), launch)?.let { t ->
                    hits += Hit(iv.key, t - launch, launch, t)
                    if (rollout != null && rollout < t) hits += Hit(iv.key + ROLLOUT, t - rollout, rollout, t)
                }
            } else rolling(s, iv)?.let { hits += it }
        }
        if (launch != null) for ((m, key) in DISTANCES) dragDistance(s, launch, m, key)?.let { hits += it }
        braking(s)?.let { hits += it }
        // the rpm falling while braking is the gearbox shifting down, not a shift of a run
        val brakingOnly = launch == null && hits.isNotEmpty() && hits.all { it.key == BRAKE }
        return Measured(s, launch, hits, if (brakingOnly) emptyList() else shifts(tr, s))
    }

    /**
     * A braking to a stop: from the last time the speed fell through [from] (the braking starts above it, as a GPS
     * meter's 100–0) to the speed falling through 0.8 km/h, both between samples, and the distance driven between
     * them. Null without a fall through [from] that ends standing, or slower than [BRAKE_MAX_SEC].
     */
    fun braking(s: SpeedSeries, from: Int = BRAKE_FROM): Hit? {
        val top = from.toDouble()
        val stop = 0.8
        var down: Double? = null
        for (k in 1 until s.size) {
            if (s.v[k] > top) { down = null; continue }
            if (s.v[k - 1] > top) down = s.t[k - 1] + (s.v[k - 1] - top) / (s.v[k - 1] - s.v[k]) * (s.t[k] - s.t[k - 1])
            val d0 = down ?: continue
            if (s.v[k - 1] > stop && s.v[k] <= stop) {
                val end = s.t[k - 1] + (s.v[k - 1] - stop) / (s.v[k - 1] - s.v[k]) * (s.t[k] - s.t[k - 1])
                if (end - d0 > BRAKE_MAX_SEC || end <= d0) return null
                // the distance: the speed straight between the samples, from the fall through [from] to the stop
                var dist = 0.0
                var pt = d0; var pv = top
                for (q in 1..k) {
                    if (s.t[q] <= d0) continue
                    val t = minOf(s.t[q], end); val v = if (s.t[q] <= end) s.v[q] else stop
                    dist += (pv + v) / 2 / 3.6 * (t - pt)
                    pt = t; pv = v
                }
                return Hit(BRAKE, end - d0, d0, end, dist = dist)
            }
        }
        return null
    }

    // ---- the acceleration graph

    /**
     * Gear changes from the engine speed: a peak above 2000 rpm, a fall of 300 rpm and 12 % or more within 1.5 s, and
     * the rpm climbing again after it (a lift at the end of a run keeps falling: not a shift).
     */
    fun shifts(tr: RunTrace, s: SpeedSeries): List<Shift> {
        val p = tr.ecu.filter { it.rpm != null }
        val out = ArrayList<Shift>()
        var i = 1
        while (i < p.size - 1) {
            val r = p[i].rpm!!
            if (r >= 2000 && r >= p[i - 1].rpm!! && r >= p[i + 1].rpm!!) {
                var low = i; var rose = false
                var j = i + 1
                while (j < p.size && p[j].t - p[i].t <= 1500) {
                    if (p[j].rpm!! < p[low].rpm!!) low = j
                    else if (p[j].rpm!! > p[low].rpm!! + 60) { rose = true; break }
                    j++
                }
                val drop = r - p[low].rpm!!
                val kmh = s.at(p[i].t / 1000.0)
                if (rose && drop >= 300 && drop >= r * 0.12 && kmh > 8 && s.at(p[low].t / 1000.0) > kmh - 10) {
                    out += Shift(p[i].t / 1000.0, p[low].t / 1000.0, kmh, r, p[low].rpm!!)
                    i = low
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * Longitudinal G along the run: the slope of the speed over a short window (a straight line through the samples
     * within ±0.3 s, wider for sparse samples), so the ECU's whole km/h do not turn into noise.
     */
    fun gCurve(s: SpeedSeries, from: Double, to: Double): List<GPt> {
        if (s.size < 3) return emptyList()
        val dts = (1 until s.size).map { s.t[it] - s.t[it - 1] }.sorted()
        val w = maxOf(0.3, dts[dts.size / 2] * 1.5)
        val out = ArrayList<GPt>()
        var lo = 0; var hi = 0
        for (k in 0 until s.size) {
            if (s.t[k] < from || s.t[k] > to) continue
            while (s.t[lo] < s.t[k] - w) lo++
            while (hi < s.size - 1 && s.t[hi + 1] <= s.t[k] + w) hi++
            val n = hi - lo + 1
            if (n < 3) continue
            var mt = 0.0; var mv = 0.0
            for (q in lo..hi) { mt += s.t[q]; mv += s.v[q] }
            mt /= n; mv /= n
            var sxy = 0.0; var sxx = 0.0
            for (q in lo..hi) { sxy += (s.t[q] - mt) * (s.v[q] - mv); sxx += (s.t[q] - mt) * (s.t[q] - mt) }
            if (sxx < 1e-9) continue
            out += GPt(s.t[k], s.v[k], sxy / sxx / 3.6 / 9.80665)
        }
        return out
    }

    /** The highest G of a run; of a braking the deepest (below zero). */
    fun peakG(m: Measured): Double? {
        val a = m.start ?: return null
        val b = m.end ?: return null
        val g = gCurve(m.speed, a, b)
        return if (m.braking) g.minOfOrNull { it.g } else g.maxOfOrNull { it.g }
    }

    // ---- is the run any good

    /** Flags of a result: the run is timed, but should not count in a comparison. */
    object Flag {
        const val WOT = "wot"      // the gas was not held down
        const val LIMP = "limp"    // pedal down, throttle plate shut: limp mode
        const val ZERO = "zero"    // the clock ran with the car standing (a GPS meter's well-known glitch)
        const val GAP = "gap"      // samples missing
        const val SLOPE = "slope"  // not on the flat
        const val GNSS = "gnss"    // timed on GPS with a weak sky: few satellites or a faint signal
    }

    /**
     * Dragy's test of the sky over a run timed on GPS: on average 8 satellites or more with the 4 strongest at 40 dB-Hz,
     * or 10 and more at 35. True: good, false: weak, null: the receiver tells neither.
     */
    fun skyOk(pts: List<GpsPt>): Boolean? {
        val sats = pts.mapNotNull { it.sats }.takeIf { it.isNotEmpty() }?.average() ?: return null
        val cn0 = pts.mapNotNull { it.cn0 }.takeIf { it.isNotEmpty() }?.average() ?: return sats >= 8
        return (sats >= 8 && cn0 >= 40) || (sats >= 10 && cn0 >= 35)
    }

    /** Seconds of [tr]'s ECU samples within [from]..[to] (shifts left out) for which [bad] holds. */
    private fun badTime(tr: RunTrace, from: Double, to: Double, shifts: List<Shift>, bad: (EcuPt) -> Boolean): Double {
        var sum = 0.0
        val e = tr.ecu
        for (k in 0 until e.size - 1) {
            val t = e[k].t / 1000.0
            if (t < from || t > to) continue
            if (shifts.any { t >= it.from - 0.15 && t <= it.to + 0.25 }) continue
            if (bad(e[k])) sum += (e[k + 1].t - e[k].t) / 1000.0
        }
        return sum
    }

    /** The road's grade over [from]..[to] in %, from the GPS altitude against the distance driven; null under [minM] or 4 fixes. */
    fun slope(tr: RunTrace, s: SpeedSeries, from: Double, to: Double, minM: Double = 150.0): Double? {
        val pts = tr.gps.filter { it.alt != null && it.acc <= 20f && it.t / 1000.0 in (from - 0.5)..(to + 0.5) }
        if (pts.size < 4) return null
        // the distance along at each fix, by the speed
        val xs = ArrayList<Double>(); var d = 0.0; var pt = pts.first().t / 1000.0
        for (p in pts) {
            val t = p.t / 1000.0
            val steps = 8
            for (q in 0 until steps) {
                val a = pt + (t - pt) * q / steps; val b = pt + (t - pt) * (q + 1) / steps
                d += (s.at(a) + s.at(b)) / 2 / 3.6 * (b - a)
            }
            pt = t
            xs += d
        }
        if (d < minM) return null
        val n = pts.size
        val mx = xs.sum() / n; val my = pts.sumOf { it.alt!!.toDouble() } / n
        var sxy = 0.0; var sxx = 0.0
        for (q in 0 until n) { sxy += (xs[q] - mx) * (pts[q].alt!! - my); sxx += (xs[q] - mx) * (xs[q] - mx) }
        return if (sxx < 1e-6) null else sxy / sxx * 100
    }

    fun flags(tr: RunTrace, m: Measured, h: Hit): List<String> {
        val out = ArrayList<String>()
        if (h.key == BRAKE) {
            // no gas to hold; a gap or a grade (uphill stops it shorter) spoils it the same
            val s = m.speed
            var gap = 0.0
            for (k in 1 until s.size) if (s.t[k] > h.from && s.t[k - 1] < h.to) gap = maxOf(gap, s.t[k] - s.t[k - 1])
            if (gap > 0.6) out += Flag.GAP
            // 40 m of braking is too short for the GPS altitude: the grade of the road coming up to it as well
            slope(tr, s, h.from - 5, h.to, minM = 100.0)?.let { if (abs(it) > 1.0) out += Flag.SLOPE }
            return out
        }
        val pedal = tr.ecu.any { it.pedal != null }
        val limit = if (pedal) WOT_PEDAL else WOT_THROTTLE
        // a quarter of a second to get the foot down; the lifts of a manual's shifts are left out
        if (tr.ecu.any { it.gas != null } && badTime(tr, h.from + 0.25, h.to, m.shifts) { (it.gas ?: 100f) < limit } > 0.25) out += Flag.WOT
        if (pedal && badTime(tr, h.from + 0.25, h.to, m.shifts) { (it.pedal ?: 0f) >= WOT_PEDAL && (it.thr ?: 100f) < 60f } > 0.3) out += Flag.LIMP
        val s = m.speed
        // a standing interval or distance (with the roll-out it starts a foot on): the stops counted from the launch
        if (m.launch != null && (h.key.startsWith("0-") || DISTANCES.any { it.second == h.key })) {
            var still = 0.0
            val first = (0 until s.size).firstOrNull { s.t[it] > m.launch && s.v[it] >= 0.5 }
            if (first != null) for (k in first until s.size - 1) if (s.t[k] < h.to && s.v[k] < 0.5) still += s.t[k + 1] - s.t[k]
            if (still > 0.3) out += Flag.ZERO
        }
        var gap = 0.0
        for (k in 1 until s.size) if (s.t[k] > h.from && s.t[k - 1] < h.to) gap = maxOf(gap, s.t[k] - s.t[k - 1])
        if (gap > 0.6) out += Flag.GAP
        slope(tr, s, h.from, h.to)?.let { if (abs(it) > 1.0) out += Flag.SLOPE }
        if (s.gps && skyOk(tr.gps.filter { it.t / 1000.0 in h.from..h.to }) == false) out += Flag.GNSS
        return out
    }

    // ---- series

    fun stats(xs: List<Double>): Stats? {
        if (xs.isEmpty()) return null
        val mean = xs.average()
        val sd = if (xs.size > 1) sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)) else 0.0
        return Stats(xs.size, mean, sd, xs.min(), xs.max())
    }

    // Student's t, two-sided 95 %, by degrees of freedom
    private val T95 = doubleArrayOf(12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145,
        2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042)

    fun t95(df: Double): Double = when {
        df < 1 -> T95[0]
        df <= 30 -> { val i = df.toInt(); if (i >= 30) T95[29] else T95[i - 1] + (T95[i] - T95[i - 1]) * (df - i) }
        df <= 60 -> 2.042 + (2.000 - 2.042) * (df - 30) / 30
        df <= 120 -> 2.000 + (1.980 - 2.000) * (df - 60) / 60
        else -> 1.96
    }

    /**
     * B against A (Welch's t-test, the series may differ in size and scatter). The scatter of a series is taken as
     * at least 0.03 s: two runs a hundredth apart prove nothing.
     */
    fun compare(a: List<Double>, b: List<Double>): Verdict? {
        val sa = stats(a) ?: return null
        val sb = stats(b) ?: return null
        val diff = sb.mean - sa.mean
        if (sa.n < 2 || sb.n < 2) return Verdict(diff, null, null)
        val va = maxOf(sa.sd, 0.03).let { it * it } / sa.n
        val vb = maxOf(sb.sd, 0.03).let { it * it } / sb.n
        val se = sqrt(va + vb)
        val df = (va + vb) * (va + vb) / (va * va / (sa.n - 1) + vb * vb / (sb.n - 1))
        val margin = t95(df) * se
        return Verdict(diff, margin, abs(diff) > margin)
    }

    // ---- weather

    /**
     * SAE J1349: how much more the engine would make at 25 °C and 99 kPa of dry air (>1 = the air was worse). The
     * standard takes the dry air's pressure (barometer less the water vapour's); the car has no humidity sensor, so
     * the whole barometer stands in for it. On a hot humid day (vapour 2–4 kPa) that reads 1–3 % of power short, the
     * same for every run in that weather; a fixed guess of the vapour would only shift all the figures, so none is
     * made, and the screens say «без учёта влажности».
     */
    fun sae(iatC: Double, baroKpa: Double): Double = 1.18 * (99.0 / baroKpa) * sqrt((iatC + 273.15) / 298.0) - 0.18

    /**
     * The mass factor of the spinning parts at [rpm] and [mps] (m/s): what speeding up the wheels, the driveline and the
     * engine adds to the car's own mass, 1.04 + 0.0025 · ξ² by the overall ratio ξ (engine turns per wheel turn, from
     * the speeds on a ~2 m tyre), the passenger car estimate of Wong's Theory of Ground Vehicles: ~1.2 in 2nd, ~1.13
     * in 3rd, ~1.07 in 5th. Without it the power on the lower gears came out 5–20 % short. Capped at 1.8 (1st gear).
     */
    fun rotatingMass(rpm: Int, mps: Double, tyreM: Double = 2.0): Double {
        if (mps < 1.0 || rpm <= 0) return 1.04
        val ratio = rpm / 60.0 / (mps / tyreM)
        return (1.04 + 0.0025 * ratio * ratio).coerceAtMost(1.8)
    }

    /** DIN 70020: the same to 20 °C and 101.3 kPa. */
    fun din(iatC: Double, baroKpa: Double): Double = (101.3 / baroKpa) * sqrt((iatC + 273.15) / 293.0)

    /**
     * The time as it would be in standard air: the time to gain a speed goes as 1 / power. Null outside where the
     * correction means anything (a cold-soaked or heat-soaked sensor, no barometer).
     */
    fun corrected(sec: Double, iatC: Double?, baroKpa: Double?): Double? {
        if (iatC == null || baroKpa == null || iatC !in -35.0..60.0 || baroKpa !in 60.0..110.0) return null
        return sec / sae(iatC, baroKpa)
    }

    // ---- spool

    /** Engine speed where the boost first goes up through [kpa] on a pull (rpm rising), straight between samples. */
    private fun rpmAt(p: List<SpoolPt>, kpa: Float): Int? {
        for (k in 1 until p.size) if (p[k - 1].kpa < kpa && p[k].kpa >= kpa) {
            val f = (kpa - p[k - 1].kpa) / (p[k].kpa - p[k - 1].kpa)
            return (p[k - 1].rpm + f * (p[k].rpm - p[k - 1].rpm)).toInt()
        }
        return null
    }

    /** A pull's spool: through 0 kPa, through 100 kPa, and to within 5 kPa of [targetKpa]. */
    fun spool(p: List<SpoolPt>, targetKpa: Float?): SpoolResult = SpoolResult(
        rpmAt(p, 0f), rpmAt(p, 100f), targetKpa?.takeIf { it > 10f }?.let { rpmAt(p, it - 5f) }, p.maxOfOrNull { it.kpa } ?: 0f,
    )

    /**
     * How far the spool moved: the median of the last three tests against the median of the first ones (up to
     * five) — a leak or a sticking wastegate pushes it up by hundreds of rpm. Null under four tests.
     */
    fun spoolDrift(oldestFirst: List<Int>): Int? {
        if (oldestFirst.size < 4) return null
        fun median(l: List<Int>) = l.sorted().let { it[it.size / 2] }
        val base = oldestFirst.take(minOf(5, oldestFirst.size - 3))
        return median(oldestFirst.takeLast(3)) - median(base)
    }
}
