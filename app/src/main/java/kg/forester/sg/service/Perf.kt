package kg.forester.sg.service

import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.max

/**
 * What a run was driven in: intake air and barometer (for the weather correction), coolant, IAM, and what the
 * driver marked it with for the series — the fuel or the mod ([label]: "98", "присадка") and a free [note].
 */
data class RunCond(
    val iatC: Int? = null, val baroKpa: Float? = null, val ectC: Int? = null, val iam: Float? = null,
    val label: String = "", val note: String = "",
)

/**
 * One acceleration run: results by name ("0-100" → seconds, "402m" → seconds, "402v" → km/h at the line). [log] is
 * the run's own CSV in [Perf.dir] (null: none kept); [demo] runs are shown but never saved. [trace] is the file of
 * its raw samples (see [RunTrace]) to count it again on other intervals; [flags] by result, see [PerfCalc.Flag].
 */
data class PerfRun(
    val timeMs: Long, val results: Map<String, Float>, val maxSpeed: Float, val peakHp: Float? = null,
    val log: String? = null, val demo: Boolean = false,
    val trace: String? = null,
    /** From a standstill; otherwise a rolling run (the pedal floored on the move). */
    val standing: Boolean = true,
    val flags: Map<String, List<String>> = emptyMap(),
    val cond: RunCond = RunCond(),
    val peakG: Float? = null,
    /** Where the speed came from: "GPS 10 Гц", "ЭБУ ×1.034 по GPS замера"… */
    val source: String = "",
    /** The road's grade over the run, %, from the GPS altitude. */
    val slope: Float? = null,
    /** The [PerfCalc.ALGO] it was counted by. */
    val algo: Int = PerfCalc.ALGO,
) {
    /** The result counts in comparisons: timed with the pedal down, on the flat, without gaps. */
    fun clean(key: String) = flags[key].isNullOrEmpty()

    /** A braking test (see [PerfCalc.braking]), not an acceleration. */
    val braking get() = PerfCalc.BRAKE in results
}

/**
 * One point of the power curve: engine rpm, power at the wheels, and that power over the engine speed: the torque at
 * the crankshaft less the losses of the transmission (not the torque at the wheels).
 */
data class DynoPoint(val rpm: Int, val hp: Float, val nm: Float)

/** A point of a GPS track. */
data class GeoPt(val lat: Double, val lon: Double)

/** One lap: its time, when it ended, its log as for [PerfRun]. */
data class Lap(val sec: Float, val timeMs: Long, val log: String? = null, val demo: Boolean = false)

data class LapState(
    val lineSet: Boolean = false,
    val running: Boolean = false,
    val currentSec: Float = 0f,
    val laps: List<Lap> = emptyList(),
    /** The lap being driven, from the line on. */
    val track: List<GeoPt> = emptyList(),
) {
    val best get() = laps.minOfOrNull { it.sec }
}

data class PerfState(
    /** Standing still with the engine running: the next pull-away is timed. */
    val armed: Boolean = false,
    val running: Boolean = false,
    /** The run in progress started on the move. */
    val rolling: Boolean = false,
    val elapsedSec: Float = 0f,
    val distanceM: Float = 0f,
    val speedKmh: Float = 0f,
    /** Results of the run in progress. */
    val live: Map<String, Float> = emptyMap(),
    val runs: List<PerfRun> = emptyList(),
    val dyno: List<DynoPoint> = emptyList(),
    val lap: LapState = LapState(),
    /** The run being timed, or the last one, as driven. */
    val track: List<GeoPt> = emptyList(),
    /** The intervals runs are timed on (see [Perf.setIntervals]). */
    val intervals: List<Interval> = emptyList(),
    val spool: SpoolState = SpoolState(),
)

/**
 * Acceleration timer like a GPS performance meter, on the ECU speed (~12 samples a second with the fast poll) or on
 * GPS when it comes often enough, crossings interpolated on the real sample times: from a standstill (0–60, 0–100…,
 * 60 ft, 201 m and 402 m with the speed at the line) and rolling (60–100, 100–150, 100–200… or any set by the driver),
 * the rolling ones timed when the pedal is floored on the move. Each run keeps its raw samples, so its times are
 * counted again when the intervals change, with flags for what spoils a run (the pedal not held, a slope, gaps).
 * While the pedal is floored the power at the wheels is estimated from mass × acceleration plus air and rolling
 * drag ("dyno"). And a lap timer on a GPS start/finish point. Each run and lap keeps its own log (see [PerfLog]) for
 * the charts and the map.
 */
object Perf {
    private const val KEEP = 150
    private var lastMs = 0L
    private var lastV = 0f
    private var stillSince = 0L
    private var startMs = 0L
    private var standing = true
    private var dist = 0.0
    private var maxV = 0f
    private var maxGas: Float? = null
    private var liftSince = 0L
    private var liveMs = 0L
    private var cond = RunCond()
    private val live = LinkedHashMap<String, Float>()
    private val pull = ArrayList<Triple<Long, Float, Int>>() // floored: time, speed m/s, rpm
    private var lapStart = 0L
    private var lapAway = false
    private var lapClosest = Double.MAX_VALUE
    private var lapClosestMs = 0L
    private var brakeHighMs = 0L
    private var brakeStopMs = 0L
    private var brakeFromMs = 0L

    init {
        Repo.perf.value = Repo.perf.value.copy(
            runs = load(), lap = LapState(lineSet = Repo.settings.lapLine.isNotEmpty(), laps = loadLaps()),
            intervals = Interval.list(Repo.settings.perfIntervals), spool = SpoolState(tests = Spool.load()),
        )
        // runs counted by an older version of the counting: again from their samples, off the thread that woke us
        if (Repo.perf.value.runs.any { it.algo < PerfCalc.ALGO && it.trace != null }) Thread { recompute() }.start()
    }

    /** Where the logs of runs and laps are: the demo's apart, so they never mix with the car's. */
    fun dir(demo: Boolean): java.io.File = if (demo) Repo.perfDemoFiles else Repo.perfFiles

    /** The log of a run or lap, if it was kept and is still there (it can be deleted from the log viewer). */
    fun logFile(name: String?, demo: Boolean): java.io.File? = name?.let { java.io.File(dir(demo), it) }?.takeIf { it.exists() }

    private fun dropLog(name: String?, demo: Boolean) { name?.let { java.io.File(dir(demo), it).delete() } }

    private fun traceFile(name: String, demo: Boolean) = java.io.File(java.io.File(dir(demo), "trace"), name)

    /** The raw samples of [run], null for a run from before they were kept. Reads a file: not on the main thread. */
    fun trace(run: PerfRun): RunTrace? = run.trace?.let { n -> runCatching { RunTrace.decode(traceFile(n, run.demo).readText()) }.getOrNull() }

    /** [list] with the point of [gps] added, if it moved 3 m or more from the last one. */
    private fun withPoint(list: List<GeoPt>, gps: GpsFix?): List<GeoPt> {
        val la = gps?.lat ?: return list
        val lo = gps.lon ?: return list
        val last = list.lastOrNull()
        if (last != null && Motion.metres(last.lat, last.lon, la, lo) < 3.0) return list
        return list + GeoPt(la, lo)
    }

    @Synchronized
    fun process(e: EcuSnapshot, gps: GpsFix?, now: Long) {
        PerfLog.record(e, gps, now)
        TraceBuffer.record(e, gps, Repo.tcu.value?.takeIf { now - it.timeMs < 1500 }?.gear, now)
        Spool.process(e, now)
        // the ECU speed with the correction learned from GPS (other tyre sizes)
        val v = (e.speedKmh?.let { it * Repo.settings.speedK.toFloat() } ?: gps?.speedKmh) ?: return
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f)
        val running = (e.rpm ?: 0) > 400
        if (dt <= 0f || dt > 2f) { lastMs = now; lastV = v; return }
        // when the speed went through [level] between the last sample and this one (in Long: a Float of epoch ms is ±1 min off)
        fun cross(level: Float): Long = now - (((v - level) / (v - lastV)).coerceIn(0f, 1f) * dt * 1000f).toLong()
        val gas = e.pedalPct ?: e.throttlePct
        val floored = gas != null && gas >= (if (e.pedalPct != null) PerfCalc.WOT_PEDAL else PerfCalc.WOT_THROTTLE)

        // standing start
        if (v < 0.5f) {
            if (stillSince == 0L) stillSince = now
            if (startMs != 0L) finish(now)
        } else stillSince = 0L
        val armed = running && stillSince != 0L && now - stillSince > 1000
        // braking: from 100 km/h or more to a stop within ten seconds, counted 1.5 s after the stop (GPS comes late)
        if (v >= PerfCalc.BRAKE_FROM) { brakeHighMs = now; brakeStopMs = 0L }
        else if (v < 0.5f && brakeHighMs != 0L) {
            if (now - brakeHighMs < 10_000) { brakeFromMs = brakeHighMs; brakeStopMs = now }
            brakeHighMs = 0L
        }
        if (brakeStopMs != 0L && now - brakeStopMs >= 1500) { brakeStopMs = 0L; braked(brakeFromMs, now, e) }
        if (startMs == 0L && lastV < 0.5f && v >= 0.5f && running) begin(cross(0.5f), true, e, gps)
        // rolling: the pedal floored on the move
        if (startMs == 0L && floored && v >= 10f) begin(now, false, e, gps)
        if (startMs != 0L) {
            Repo.perf.value.let { st -> withPoint(st.track, gps).let { if (it !== st.track) Repo.perf.value = st.copy(track = it) } }
            if (standing) dist += (lastV + v) / 2 / 3.6 * dt
            maxV = max(maxV, v)
            if (gas != null) maxGas = max(maxGas ?: 0f, gas)
            liftSince = if (gas == null || gas >= 40f) 0L else if (liftSince == 0L) now else liftSince
            val t = (now - startMs) / 1000f
            if (now - liveMs >= 250) { liveMs = now; liveResults(now) }
            // the run is over when the driver lifts (the pedal up for 1.5 s: a manual's shifts are shorter, or the speed
            // falls), or after a minute
            val lifted = if (gas != null) (liftSince != 0L && now - liftSince > 1500) || v < maxV - 8f else v < maxV - 4f
            if (lifted || t > 60f) finish(now)
        }
        // dyno: pedal (or throttle) floored
        val dynoOn = ((e.pedalPct ?: e.throttlePct) ?: 0f) > 85f && v > 10f
        if (dynoOn && e.rpm != null) pull += Triple(now, v / 3.6f, e.rpm)
        // a pull of two seconds or more (by the time: the fast poll gives ~12 samples a second, a Bluetooth adapter 3–5)
        else if (pull.size >= 5 && pull.last().first - pull.first().first >= 2000) { dyno(); pull.clear() } else pull.clear()

        laps(gps, now)
        lastMs = now
        lastV = v
        Repo.perf.value = Repo.perf.value.copy(
            armed = armed && startMs == 0L, running = startMs != 0L, rolling = startMs != 0L && !standing,
            elapsedSec = if (startMs != 0L) (now - startMs) / 1000f else Repo.perf.value.elapsedSec,
            distanceM = dist.toFloat(), speedKmh = v, live = LinkedHashMap(live),
        )
    }

    private fun begin(at: Long, fromStill: Boolean, e: EcuSnapshot, gps: GpsFix?) {
        startMs = at; standing = fromStill
        dist = 0.0; maxV = 0f; maxGas = null; liftSince = 0L; liveMs = 0L; live.clear()
        val s = Repo.settings
        cond = RunCond(e.intakeC, e.atmKpa, e.coolantC, Repo.guard.value.iam, s.perfLabel.trim(), s.perfNote.trim())
        Repo.perf.value = Repo.perf.value.copy(track = withPoint(emptyList(), gps), elapsedSec = 0f)
    }

    /** The run so far counted as it will be when it is over, a few times a second. */
    private fun liveResults(now: Long) {
        val tr = TraceBuffer.cut(startMs - 2000, now, Repo.settings.speedK.toFloat()) ?: return
        val m = PerfCalc.measure(tr, Repo.perf.value.intervals)
        live.clear(); live.putAll(m.results())
    }

    private fun finish(now: Long) {
        val from = startMs
        startMs = 0L
        // a pull-away in traffic is no run: the pedal has to have been down
        if (maxGas != null && maxGas!! < 80f) return
        // two seconds before the start: the standing still, or the speed coming up to a rolling interval
        val tr = TraceBuffer.cut(from - 2000, now, Repo.settings.speedK.toFloat()) ?: return
        val m = PerfCalc.measure(tr, Repo.perf.value.intervals)
        if (m.hits.isEmpty()) return
        // demo runs are shown and can be looked at, but never saved, like trips
        val demo = Repo.settings.demo
        val name = "$now.txt"
        val trace = runCatching { traceFile(name, demo).also { it.parentFile?.mkdirs() }.writeText(tr.encode()); name }.getOrNull()
        val hits = m.hits.filter { it.key.startsWith("0-") || it.key.endsWith("m") }
        val log = PerfLog.save(dir(demo), if (standing) "Разгон" else "Разгон ${m.hits.maxBy { it.to }.key}", from - 2000, now)
        val run = describe(tr, m, PerfRun(now, emptyMap(), maxV, if (standing) Repo.perf.value.dyno.maxOfOrNull { it.hp } else null, log, demo,
            trace, standing, cond = cond))
        keep(run)
        live.clear(); live.putAll(run.results)
        if (demo) return
        val said = if (standing) hits.firstOrNull { it.key == "0-100" } else m.hits.maxByOrNull { it.to }
        said?.let { h ->
            val what = if (h.key == "0-100") "Разгон до сотни" else "Разгон ${h.key.replace("-", "–")}"
            Alerts.raise("run", "$what: %.2f секунды".format(h.sec), Alerts.Level.INFO, 0L)
        }
    }

    /**
     * A hard stop from [PerfCalc.BRAKE_FROM] km/h (last there at [fromMs]): kept as a run of its own, timed and with the
     * distance, if it was braking (see [PerfCalc.braking]) and not just a stop.
     */
    private fun braked(fromMs: Long, now: Long, e: EcuSnapshot) {
        val tr = TraceBuffer.cut(fromMs - 2000, now, Repo.settings.speedK.toFloat()) ?: return
        val m = PerfCalc.measure(tr, emptyList())
        val h = m.hits.firstOrNull { it.key == PerfCalc.BRAKE } ?: return
        val demo = Repo.settings.demo
        val name = "$now.txt"
        val trace = runCatching { traceFile(name, demo).also { it.parentFile?.mkdirs() }.writeText(tr.encode()); name }.getOrNull()
        val log = PerfLog.save(dir(demo), "Торможение", fromMs - 2000, now)
        val s = Repo.settings
        val c = RunCond(e.intakeC, e.atmKpa, e.coolantC, Repo.guard.value.iam, s.perfLabel.trim(), s.perfNote.trim())
        keep(describe(tr, m, PerfRun(now, emptyMap(), m.speed.v.maxOrNull()?.toFloat() ?: 0f, null, log, demo, trace, false, cond = c)))
        if (!demo) Alerts.raise("run", "Торможение со ста: %.2f секунды, %.0f метров".format(h.sec, h.dist ?: 0.0), Alerts.Level.INFO, 0L)
    }

    /** [base] with the results, flags, peak G, speed source and grade of the counted run [m]. */
    private fun describe(tr: RunTrace, m: Measured, base: PerfRun): PerfRun {
        val flags = m.hits.associate { it.key to PerfCalc.flags(tr, m, it) }.filterValues { it.isNotEmpty() }
        val a = m.start; val b = m.end
        // a braking is too short for the GPS altitude alone: the road coming up to it too, as its flag takes it
        val slope = if (a == null || b == null) null
            else if (m.braking) PerfCalc.slope(tr, m.speed, a - 5, b, minM = 100.0) else PerfCalc.slope(tr, m.speed, a, b)
        return base.copy(results = m.results(), flags = flags, peakG = PerfCalc.peakG(m)?.toFloat(), source = m.speed.source,
            slope = slope?.toFloat(), standing = m.launch != null, algo = PerfCalc.ALGO)
    }

    /** Puts [run] first; the oldest past [KEEP] go, with their logs. */
    @Synchronized
    private fun keep(run: PerfRun) {
        val all = listOf(run) + Repo.perf.value.runs
        all.drop(KEEP).forEach(::drop)
        val runs = all.take(KEEP)
        Repo.perf.value = Repo.perf.value.copy(runs = runs)
        save(runs)
    }

    private fun drop(r: PerfRun) {
        dropLog(r.log, r.demo)
        r.trace?.let { traceFile(it, r.demo).delete() }
    }

    // --- intervals and runs, from the screen

    /** New intervals: saved, and every kept run counted again on them. Reads files: not on the main thread. */
    fun setIntervals(list: List<Interval>) {
        Repo.settings.perfIntervals = list.joinToString(",") { it.key }
        synchronized(this) { Repo.perf.value = Repo.perf.value.copy(intervals = Interval.list(Repo.settings.perfIntervals)) }
        recompute()
    }

    /** Counts the kept runs again on the current intervals (the results of runs without samples stay as they were). */
    fun recompute() {
        val iv = Repo.perf.value.intervals
        val fresh = HashMap<Long, PerfRun>()
        for (r in Repo.perf.value.runs) {
            val tr = trace(r) ?: continue
            fresh[r.timeMs] = describe(tr, PerfCalc.measure(tr, iv), r)
        }
        synchronized(this) {
            val runs = Repo.perf.value.runs.map { fresh[it.timeMs] ?: it }
            Repo.perf.value = Repo.perf.value.copy(runs = runs)
            save(runs)
        }
    }

    /** The fuel/mod label and the note of a run already kept (a run marked wrong is moved to the right series). */
    @Synchronized
    fun setRunInfo(timeMs: Long, label: String, note: String) {
        val runs = Repo.perf.value.runs.map { if (it.timeMs == timeMs) it.copy(cond = it.cond.copy(label = label.trim(), note = note.trim())) else it }
        Repo.perf.value = Repo.perf.value.copy(runs = runs)
        save(runs)
    }

    @Synchronized
    fun deleteRun(timeMs: Long) {
        val (gone, runs) = Repo.perf.value.runs.partition { it.timeMs == timeMs }
        gone.forEach(::drop)
        Repo.perf.value = Repo.perf.value.copy(runs = runs)
        save(runs)
    }

    /**
     * Power at the wheels over the pull: (m·a·δ + air + rolling) · v, smoothed over ~0.6 s, binned by 250 rpm; δ is the
     * spinning wheels' and gearbox's share ([PerfCalc.rotatingMass], by the gear from rpm and speed).
     */
    private fun dyno() {
        val m = Repo.settings.massKg.toDouble()
        val cda = 0.85 // Cd × frontal area, m² (Forester/Impreza class)
        val bins = HashMap<Int, MutableList<Pair<Double, Double>>>()
        for (i in pull.indices) {
            val (t, v, rpm) = pull[i]
            val a = pull.indices.filter { kotlin.math.abs(pull[it].first - t) <= 300 }.let { w ->
                if (w.size < 3) null else {
                    val t0 = pull[w.first()].first
                    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                    for (k in w) { val x = (pull[k].first - t0) / 1000.0; val y = pull[k].second.toDouble(); sx += x; sy += y; sxx += x * x; sxy += x * y }
                    val n = w.size
                    val den = n * sxx - sx * sx
                    if (den < 1e-6) null else (n * sxy - sx * sy) / den
                }
            } ?: continue
            if (a < 0.1) continue
            val force = m * a * PerfCalc.rotatingMass(rpm, v.toDouble()) + 0.5 * 1.2 * cda * v * v + 0.015 * m * 9.81
            val watts = force * v
            val hp = watts / 735.5
            val nm = watts / (2 * PI * rpm / 60.0)
            bins.getOrPut((rpm / 250) * 250) { ArrayList() } += hp to nm
        }
        val curve = bins.entries.sortedBy { it.key }.filter { it.value.size >= 1 }.map { (r, l) ->
            DynoPoint(r, l.map { it.first }.average().toFloat(), l.map { it.second }.average().toFloat())
        }
        if (curve.size >= 4) Repo.perf.value = Repo.perf.value.copy(dyno = curve)
    }

    // --- lap timer

    /** A new start/finish line is a new track: the laps of the old one go, with their logs. */
    fun setLapLine(gps: GpsFix?) {
        val lat = gps?.lat ?: return
        val lon = gps.lon ?: return
        Repo.perf.value.lap.laps.forEach { dropLog(it.log, it.demo) }
        Repo.settings.lapLine = "$lat,$lon"
        Repo.settings.perfLaps = "[]"
        lapStart = 0L; lapAway = false
        Repo.perf.value = Repo.perf.value.copy(lap = LapState(lineSet = true))
    }

    fun clearLaps() {
        Repo.perf.value.lap.laps.forEach { dropLog(it.log, it.demo) }
        Repo.settings.lapLine = ""
        Repo.settings.perfLaps = "[]"
        lapStart = 0L
        Repo.perf.value = Repo.perf.value.copy(lap = LapState())
    }

    private fun laps(gps: GpsFix?, now: Long) {
        val line = Repo.settings.lapLine.split(',').mapNotNull { it.toDoubleOrNull() }
        if (line.size != 2 || gps?.lat == null || gps.lon == null) return
        val d = Motion.metres(line[0], line[1], gps.lat, gps.lon)
        val lap = Repo.perf.value.lap
        if (d > 60) {
            // away from the line: the next approach is a pass
            if (lapClosestMs != 0L && lapClosest < 25) {
                val passMs = lapClosestMs
                var laps = lap.laps
                if (lapStart != 0L) {
                    val sec = (passMs - lapStart) / 1000f
                    val best = lap.best
                    val demo = Repo.settings.demo
                    val log = PerfLog.save(dir(demo), "Круг ${laps.size + 1}", lapStart, passMs)
                    // a long day at the track: the oldest laps go, with their logs
                    val all = laps + Lap(sec, passMs, log, demo)
                    all.dropLast(100).forEach { dropLog(it.log, it.demo) }
                    laps = all.takeLast(100)
                    saveLaps(laps)
                    if (!demo) Alerts.raise("lap", "Круг %d:%05.2f".format((sec / 60).toInt(), sec % 60) +
                        if (best != null && sec < best) ", лучший!" else "", Alerts.Level.INFO, 0L)
                }
                lapStart = passMs
                // the pass is only known 60 m past the line: the new lap's path starts back at the line, with the
                // points driven since (the tail of the old path, from its point nearest to the line)
                val prev = lap.track
                var from = prev.size
                var near = Double.MAX_VALUE
                for (i in prev.indices.reversed()) {
                    val m = Motion.metres(line[0], line[1], prev[i].lat, prev[i].lon)
                    if (m > 120) break
                    if (m < near) { near = m; from = i }
                }
                Repo.perf.value = Repo.perf.value.copy(lap = lap.copy(laps = laps, running = true, track = withPoint(prev.subList(from, prev.size), gps)))
            }
            lapAway = true
            lapClosest = Double.MAX_VALUE; lapClosestMs = 0L
        } else if (lapAway && d < lapClosest) {
            lapClosest = d; lapClosestMs = gps.timeMs
        }
        // the path since the last pass (or, before the first one, since the line was set: the way to it)
        val l = Repo.perf.value.lap
        Repo.perf.value = Repo.perf.value.copy(lap = l.copy(
            currentSec = if (lapStart != 0L) (now - lapStart) / 1000f else l.currentSec, lineSet = true, track = withPoint(l.track, gps),
        ))
    }

    // --- storage

    private fun save(runs: List<PerfRun>) {
        val arr = JSONArray()
        runs.filter { !it.demo }.forEach { r ->
            val res = JSONObject(); r.results.forEach { (k, v) -> res.put(k, v.toDouble()) }
            val fl = JSONObject(); r.flags.forEach { (k, v) -> fl.put(k, v.joinToString(",")) }
            val c = r.cond
            val cond = JSONObject().put("iat", c.iatC).put("baro", c.baroKpa?.toDouble()).put("ect", c.ectC).put("iam", c.iam?.toDouble())
                .put("lb", c.label).put("nt", c.note)
            arr.put(JSONObject().put("t", r.timeMs).put("r", res).put("v", r.maxSpeed.toDouble()).put("hp", r.peakHp?.toDouble()).put("log", r.log)
                .put("tr", r.trace).put("st", r.standing).put("f", fl).put("c", cond).put("g", r.peakG?.toDouble()).put("src", r.source)
                .put("sl", r.slope?.toDouble()).put("av", r.algo))
        }
        Repo.settings.perfRuns = arr.toString()
    }

    private fun load(): List<PerfRun> = runCatching {
        val arr = JSONArray(Repo.settings.perfRuns)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val r = o.getJSONObject("r")
            fun JSONObject.fl(k: String) = optDouble(k).takeIf { !it.isNaN() }?.toFloat()
            fun JSONObject.int(k: String) = if (has(k) && !isNull(k)) getInt(k) else null
            val f = o.optJSONObject("f")
            val c = o.optJSONObject("c")
            PerfRun(o.getLong("t"), r.keys().asSequence().associateWith { r.getDouble(it).toFloat() }, o.optDouble("v", 0.0).toFloat(),
                o.fl("hp"), o.optString("log").ifEmpty { null },
                trace = o.optString("tr").ifEmpty { null }, standing = o.optBoolean("st", true),
                flags = f?.keys()?.asSequence()?.associateWith { f.getString(it).split(',').filter(String::isNotEmpty) } ?: emptyMap(),
                cond = c?.let { RunCond(it.int("iat"), it.fl("baro"), it.int("ect"), it.fl("iam"), it.optString("lb"), it.optString("nt")) } ?: RunCond(),
                peakG = o.fl("g"), source = o.optString("src"), slope = o.fl("sl"), algo = o.optInt("av", 1))
        }
    }.getOrDefault(emptyList())

    private fun saveLaps(laps: List<Lap>) {
        val arr = JSONArray()
        laps.filter { !it.demo }.forEach { arr.put(JSONObject().put("s", it.sec.toDouble()).put("t", it.timeMs).put("log", it.log)) }
        Repo.settings.perfLaps = arr.toString()
    }

    private fun loadLaps(): List<Lap> = runCatching {
        val arr = JSONArray(Repo.settings.perfLaps)
        List(arr.length()) { i -> arr.getJSONObject(i).let { Lap(it.getDouble("s").toFloat(), it.getLong("t"), it.optString("log").ifEmpty { null }) } }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clearRuns() {
        Repo.perf.value.runs.forEach(::drop)
        Repo.settings.perfRuns = "[]"
        Repo.perf.value = Repo.perf.value.copy(runs = emptyList(), dyno = emptyList())
    }
}
