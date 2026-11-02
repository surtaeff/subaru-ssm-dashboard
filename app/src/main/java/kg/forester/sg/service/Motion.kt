package kg.forester.sg.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The peak of a value per short slot over the last seconds, with no allocation per sample: the sensor runs at ~50 Hz.
 * Written from the sensor's thread, read from the ECU's.
 */
class ShockHold {
    private val slot = LongArray(SLOTS) { -1L }
    private val peak = FloatArray(SLOTS)

    @Synchronized
    fun sample(tMs: Long, g: Float) {
        val n = tMs / SLOT_MS
        val i = (n % SLOTS).toInt()
        if (slot[i] != n) { slot[i] = n; peak[i] = g } else if (g > peak[i]) peak[i] = g
    }

    /** The highest in the slots touching [fromMs]..[toMs], null when none has a sample. */
    @Synchronized
    fun peak(fromMs: Long, toMs: Long): Float? {
        var best = -1f
        for (i in 0 until SLOTS) if (slot[i] in (fromMs / SLOT_MS)..(toMs / SLOT_MS) && peak[i] > best) best = peak[i]
        return best.takeIf { it >= 0f }
    }

    companion object {
        const val SLOT_MS = 100L
        const val SLOTS = 40
    }
}

/** G-forces and tilt for the G-meter and the inclinometer. */
data class MotionState(
    /** Sideways (+ = to the right) and along the car (+ = speeding up), in g. */
    val latG: Float? = null,
    val lonG: Float? = null,
    /** Where the numbers come from: "датчик магнитолы", "VDC", "GPS и скорость". */
    val source: String = "",
    /** Nose up (+) / down and roll to the right (+) / left, degrees; needs the head unit's sensor. */
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    /** Road grade from GPS height over the last ~150 m, %. */
    val gradePct: Float? = null,
    val hasSensor: Boolean = false,
    val calibrated: Boolean = false,
    val maxLat: Float = 0f,
    val maxAccel: Float = 0f,
    val maxBrake: Float = 0f,
    /** The largest sideways G each way, for the G-force card. */
    val maxLeft: Float = 0f,
    val maxRight: Float = 0f,
)

/**
 * The head unit's accelerometer when it has one (many FYT units do not): zeroed on level ground with the car at
 * rest ([calibrate]), it gives pitch, roll and both G axes. Without it: lateral G from VDC's sensor (SH and
 * later, via RomRaider P172) or from speed × GPS turn rate, longitudinal G from the ECU speed.
 */
object Motion : SensorEventListener {
    private var sm: SensorManager? = null
    private val tracker = GravityTracker()
    private var zero: FloatArray? = null
    /** The car stood still at the last sample (for the moving-off rewind of [GravityTracker]). */
    private var wasStill = false
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var lastGps: GpsFix? = null
    private var gpsLat: Float? = null
    private val climb = ArrayDeque<Pair<Double, Double>>() // (distance m, altitude m)
    private var distM = 0.0
    private var lastSnapMs = 0L

    /** The sensor runs only with the G-meter and tilt feature on; call again when the feature changes. */
    fun start(ctx: Context) {
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (!Repo.settings.feature("motion")) {
            stop()
            if (!Repo.motion.value.hasSensor) Repo.motion.value = Repo.motion.value.copy(hasSensor = true)
            return
        }
        if (sm != null) return
        sm = m
        m.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        zero = Repo.settings.tiltZero.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 }?.toFloatArray()
        tracker.start(zero)
        Repo.motion.value = Repo.motion.value.copy(hasSensor = true, calibrated = zero != null)
    }

    fun stop() {
        sm?.unregisterListener(this)
        sm = null
    }

    override fun onSensorChanged(ev: SensorEvent) {
        tracker.sample(ev.values, ev.timestamp)
        // the raw reading along "down", gravity taken out: the smoothing above would flatten a jolt
        val g = tracker.gravity ?: return
        val len = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (len < 1f) return
        val v = ev.values
        val along = ((v[0] - g[0]) * g[0] + (v[1] - g[1]) * g[1] + (v[2] - g[2]) * g[2]) / len
        shocks.sample(System.currentTimeMillis(), abs(along) / 9.81f)
    }

    private val shocks = ShockHold()

    /** The strongest vertical shock between these moments, g; null with no sensor running. */
    fun shockG(fromMs: Long, toMs: Long): Float? = if (sm == null) null else shocks.peak(fromMs, toMs)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Car at rest on level ground: remember which way "down" is for this mounting. */
    fun calibrate() {
        val g = tracker.reading ?: return
        zero = g.copyOf()
        tracker.start(g)
        Repo.settings.tiltZero = g.joinToString(",")
        Repo.motion.value = Repo.motion.value.copy(calibrated = true)
    }

    fun resetMax() {
        Repo.motion.value = Repo.motion.value.copy(maxLat = 0f, maxAccel = 0f, maxBrake = 0f, maxLeft = 0f, maxRight = 0f)
    }

    /** The car's axes in the sensor's frame from the calibration: up, forward (out of the screen's back), right. */
    private fun axes(): Triple<FloatArray, FloatArray, FloatArray>? {
        val z = zero ?: return null
        val up = norm(z)
        // the screen faces the driver, so "forward" is the device's -Z, made level
        val f0 = floatArrayOf(0f, 0f, -1f)
        val d = dot(f0, up)
        val fwd = norm(FloatArray(3) { f0[it] - d * up[it] })
        val right = cross(fwd, up)
        return Triple(up, fwd, right)
    }

    fun process(e: EcuSnapshot, gps: GpsFix?, now: Long) {
        val st = Repo.motion.value
        // longitudinal from the ECU speed: slope of the last ~0.8 s
        e.speedKmh?.let { v ->
            speeds.addLast(now to v / 3.6f)
            while (speeds.isNotEmpty() && now - speeds.first().first > 800) speeds.removeFirst()
        }
        val lonEcu = slope(speeds)?.let { it / 9.81f }
        // lateral from GPS: speed × turn rate
        if (gps != null && gps !== lastGps) {
            val prev = lastGps
            lastGps = gps
            if (prev?.bearing != null && gps.bearing != null && gps.speedKmh > 10) {
                val dt = (gps.timeMs - prev.timeMs) / 1000f
                if (dt in 0.3f..3f) {
                    var db = gps.bearing - prev.bearing
                    if (db > 180) db -= 360f
                    if (db < -180) db += 360f
                    gpsLat = (gps.speedKmh / 3.6f) * Math.toRadians((db / dt).toDouble()).toFloat() / 9.81f
                }
            } else if (gps.speedKmh < 5) gpsLat = 0f
            // grade: height change over distance
            if (prev != null && prev.lat != null && gps.lat != null && prev.lon != null && gps.lon != null) {
                distM += metres(prev.lat, prev.lon, gps.lat, gps.lon)
                climb.addLast(distM to gps.altitudeM)
                while (climb.size > 2 && distM - climb.first().first > 150) climb.removeFirst()
            }
        }
        val grade = if (climb.size >= 2 && climb.last().first - climb.first().first > 60)
            ((climb.last().second - climb.first().second) / (climb.last().first - climb.first().first) * 100).toFloat()
                .takeIf { abs(it) < 40 } else null
        // gravity follows the sensor only standing or at a steady speed, by the ECU's speed or else the GPS's; the
        // first second of moving off (the ECU shows 0 below 3–4 km/h) is taken back out of it
        val kmh = e.speedKmh?.toFloat() ?: gps?.takeIf { now - it.timeMs < 3000 }?.speedKmh
        val still = kmh != null && kmh < 1f
        if (wasStill && kmh != null && !still) tracker.rewind(GravityTracker.MOVE_OFF_SEC)
        wasStill = still
        tracker.tauSec = GravityTracker.tau(kmh, lonEcu, gpsLat.takeIf { gps != null })
        // VDC lateral G (m/s²) when the transmission/VDC unit gives it
        val vdc = Repo.rrValues.value.let { rr -> rr["P172"]?.toFloat()?.let { it / 9.81f } }

        var lat: Float?
        var lon: Float?
        var pitch: Float? = null
        var roll: Float? = null
        var source: String
        val ax = axes()
        val gNow = tracker.gravity
        if (ax != null && gNow != null) {
            val (up, fwd, right) = ax
            val gu = dot(gNow, up); val gf = dot(gNow, fwd); val gr = dot(gNow, right)
            pitch = Math.toDegrees(atan2(gf.toDouble(), gu.toDouble())).toFloat() // nose up: "up" leans forward
            roll = Math.toDegrees(atan2(-gr.toDouble(), gu.toDouble())).toFloat() // right side down: "up" leans left
            val l = tracker.linear
            lat = dot(l, right) / 9.81f
            lon = dot(l, fwd) / 9.81f
            source = "датчик магнитолы"
        } else {
            lat = vdc ?: gpsLat
            lon = lonEcu
            source = if (vdc != null) "VDC и скорость" else "GPS и скорость"
        }
        if (lat != null && abs(lat) > 2.5f) lat = null
        if (lon != null && abs(lon) > 2.5f) lon = null
        lastSnapMs = now
        Repo.motion.value = st.copy(
            latG = lat, lonG = lon, source = source, pitchDeg = pitch, rollDeg = roll, gradePct = grade,
            maxLat = maxOf(st.maxLat, abs(lat ?: 0f)),
            maxAccel = maxOf(st.maxAccel, lon ?: 0f),
            maxBrake = maxOf(st.maxBrake, -(lon ?: 0f)),
            maxLeft = maxOf(st.maxLeft, -(lat ?: 0f)),
            maxRight = maxOf(st.maxRight, lat ?: 0f),
        )
    }

    private fun slope(p: ArrayDeque<Pair<Long, Float>>): Float? {
        if (p.size < 3) return null
        val t0 = p.first().first
        val n = p.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((t, v) in p) { val x = (t - t0) / 1000.0; sx += x; sy += v; sxx += x * x; sxy += x * v }
        val den = n * sxx - sx * sx
        return if (abs(den) < 1e-6) null else ((n * sxy - sx * sy) / den).toFloat()
    }

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = 111_320.0
        val dx = (lon2 - lon1) * k * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * k
        return sqrt(dx * dx + dy * dy)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: FloatArray): FloatArray { val l = sqrt(dot(a, a)).takeIf { it > 1e-6f } ?: 1f; return FloatArray(3) { a[it] / l } }
}

/**
 * Gravity in the sensor's frame, kept apart from the car's own acceleration. A plain low-pass filter (the Android
 * SensorEvent example: alpha 0.8 at ~50 Hz) takes any acceleration held for a second or two for gravity and washes it
 * out (the 0.95/0.05 one here before: a steady 0.6 g pull read ~8 % of itself after a second). Here gravity starts at
 * the calibrated "down" (or the first reading) and follows the sensor only while the car is known to stand or to
 * keep its speed ([tauSec], set from the ECU or GPS speed), and slowly: a slope or a parked tilt is taken in, a pull,
 * a stop or a long bend never is.
 */
class GravityTracker {
    /** Gravity, m/s² in the sensor's frame; null before the first reading. */
    @Volatile var gravity: FloatArray? = null
        private set
    /** The reading with the sensor's noise smoothed (gravity and the car's acceleration together). */
    @Volatile private var acc: FloatArray? = null
    private var lastNs = 0L
    /** Gravity over the last couple of seconds, to take back what moving off fed into it. */
    private val past = ArrayDeque<Pair<Long, FloatArray>>()

    /** Seconds gravity follows the sensor over; 0 = held as it is (speeding up, braking, cornering). */
    @Volatile var tauSec = NO_SPEED_SEC

    /** Start from [g] (the calibrated "down"), or from the next reading when null. */
    fun start(g: FloatArray?) {
        gravity = g?.copyOf()
        synchronized(past) { past.clear() }
    }

    /** One reading [v] (m/s²) at [tNs] (the sensor's clock, ns). */
    fun sample(v: FloatArray, tNs: Long) {
        val dt = if (lastNs == 0L) 0f else ((tNs - lastNs) / 1e9f).coerceIn(0f, 0.5f)
        lastNs = tNs
        val a = acc?.let { p -> FloatArray(3) { p[it] + (v[it] - p[it]) * (dt / (NOISE_SEC + dt)) } } ?: v.copyOf()
        acc = a
        val g = gravity
        val tau = tauSec
        val next = when {
            g == null -> a.copyOf()
            tau > 0f -> FloatArray(3) { g[it] + (a[it] - g[it]) * (dt / (tau + dt)) }
            else -> g
        }
        gravity = next
        synchronized(past) {
            if (past.isEmpty() || tNs - past.last().first >= 100_000_000L) past.addLast(tNs to next)
            while (past.size > 2 && tNs - past.first().first > 3_000_000_000L) past.removeFirst()
        }
    }

    /** Gravity back to what it was [sec] ago: the car turned out to be moving already. */
    fun rewind(sec: Float) {
        val t = lastNs - (sec * 1e9f).toLong()
        synchronized(past) { past.lastOrNull { it.first <= t } ?: past.firstOrNull() }?.let { gravity = it.second.copyOf() }
    }

    /** The car's own acceleration, m/s² in the sensor's frame. */
    val linear: FloatArray
        get() {
            val a = acc ?: return FloatArray(3)
            val g = gravity ?: return FloatArray(3)
            return FloatArray(3) { a[it] - g[it] }
        }

    /** The smoothed reading, gravity included: "down" with the car at rest. */
    val reading: FloatArray? get() = acc?.copyOf()

    companion object {
        /** The sensor's noise smoothed over this, s. */
        const val NOISE_SEC = 0.1f
        /** Gravity follows the sensor over this standing, s: a parked slope is taken in within a few seconds. */
        const val STILL_SEC = 2f
        /** At a steady speed on a straight (GPS), s: a hill is taken in, an overtake is not. */
        const val STEADY_SEC = 10f
        /** At a steady speed with no GPS to say it is a straight, s: a long bend must not wash out. */
        const val STEADY_BLIND_SEC = 20f
        /** With no speed at all (no ECU, no GPS), s: slow enough to leave a pull or a stop alone. */
        const val NO_SPEED_SEC = 30f
        /** What is taken back when moving off: the ECU shows 0 for the first fraction of a second of moving. */
        const val MOVE_OFF_SEC = 1f

        /**
         * How fast gravity may follow the sensor, s (0 = not at all): by the speed [kmh] (null = none known), the
         * acceleration along the car from the speed [lonG], and the GPS's sideways G [latG] (null = no GPS).
         */
        fun tau(kmh: Float?, lonG: Float?, latG: Float?): Float = when {
            kmh == null -> NO_SPEED_SEC
            kmh < 1f -> STILL_SEC
            lonG == null || abs(lonG) >= 0.04f -> 0f
            latG == null -> STEADY_BLIND_SEC
            abs(latG) < 0.05f -> STEADY_SEC
            else -> 0f
        }
    }
}
