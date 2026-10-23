package kg.forester.sg.service

import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Made-up but plausible ECU/TCU data for the demo mode (Настройки → «Демо-данные»): a 140-second loop of
 * idle, a flat-out run to ~120 km/h (0–100 in about 10 s, so «Замеры» shows it), a sharp lift, cruise, a hard stop (~0.95 g, so «Замеры» shows the braking 100–0) and a minute at a red light (long
 * enough for «Мониторинг → Проверка» to take its idle sample, with the values of a healthy warm EJ).
 * Nothing is written to trips.
 */
object Demo {
    private const val CYCLE = 140.0
    private val gearRatio = floatArrayOf(2.785f, 1.545f, 1.000f, 0.694f) // 4EAT
    private const val FINAL = 4.111f
    private const val TYRE_M = 2.0f

    private fun speedAt(t: Double): Double = when {
        t < 8 -> 0.0
        t < 26 -> 125 * (1 - Math.exp(-(t - 8) / 6))
        t < 60 -> 118 + 3 * sin((t - 26) / 5)
        t < 63.5 -> 118 * (1 - (t - 60) / 3.5)
        else -> 0.0
    }

    fun ecu(nowMs: Long): Pair<EcuSnapshot, TcuSnapshot> {
        val t = (nowMs / 1000.0) % CYCLE
        val v = speedAt(t)
        val accel = (speedAt(t + 0.5) - v) * 2 // km/h per s
        val load = when {
            t in 8.0..26.0 -> 0.95 // flat out, then a sharp lift at 26 s (backfire on the screen)
            accel > 3 -> min(1.0, 0.45 + accel / 12)
            v > 1 && accel > -1 -> 0.28
            v > 1 -> 0.02
            else -> 0.12
        }
        val gear = when {
            v < 1 -> 1
            v < 22 -> 1
            v < 45 -> 2
            v < 100 -> 3
            else -> 4
        }
        val wheelRpm = v / 3.6 / TYRE_M * 60
        val lockUp = gear >= 3 && accel < 4
        val turbine = wheelRpm * gearRatio[gear - 1] * FINAL
        val rpm = max(780.0, if (v < 1) 780.0 else turbine * (if (lockUp) 1.0 else 1.08))
        val wobble = sin(nowMs / 700.0)
        val idling = v < 1
        // each stop a little different, and a small air leak growing over the stops (so «Проверка» has a history
        // worth looking at): the MAF reads a bit less, the ECU learns a bit more fuel
        val stop = ((nowMs / 1000.0) / CYCLE).toLong()
        val leak = (stop % 8) / 7.0
        val jitter = sin(stop * 1.7)
        val boostKpa = if (load > 0.7) (load - 0.7) / 0.3 * 85 else -60 + load * 70
        val maf = 2.5 + rpm / 1000 * (3 + load * 28)
        // warms from 55° to 85° and back over an hour, smoothly (a sawtooth would look like a faulty sensor)
        val atf = (70 - 15 * cos(nowMs / 60000.0 / 60 * 2 * Math.PI)).toInt()
        val transfer = (25 + load * 45 + if (v < 15 && accel > 3) 20 else 0).coerceAtMost(100.0)
        val ecu = EcuSnapshot(
            timeMs = nowMs,
            coolantC = 88 + (wobble * 2).toInt(),
            rpm = if (idling) (715 + jitter * 25 + leak * 40 + wobble * 12).toInt() else rpm.toInt(),
            speedKmh = v.toInt(),
            timingDeg = if (idling) (13 + wobble * 0.5).toFloat() else (12 + 18 * (1 - load) + wobble).toFloat(),
            intakeC = 32,
            mafGs = if (idling) (2.9 - leak * 0.5 + jitter * 0.1 + wobble * 0.1).toFloat() else maf.toFloat(),
            throttlePct = if (idling) 1.5f else (load * 85 + 2).toFloat(),
            batteryV = (14.1 + wobble * 0.1).toFloat(),
            injPwMs = if (idling) (2.05 + leak * 0.3 + jitter * 0.05).toFloat() else (1.8 + load * 9).toFloat(),
            knockDeg = if (load > 0.9 && ((nowMs / 1000) % 17 == 0L)) -1.4f else 0f,
            atmKpa = 99f,
            afCorrPct = (wobble * 3).toFloat(),
            afLearnPct = if (idling) (1.5 + leak * 8).toFloat() else 2.3f,
            lambda = if (load > 0.8) 0.82f else (1 + wobble * 0.02).toFloat(),
            wastegatePct = if (load > 0.7) 45f else 0f,
            pedalPct = if (idling) 0f else (load * 100).toFloat(),
            mapKpa = if (idling) (33 + wobble).toFloat() else (99 + boostKpa).toFloat(),
            avcsDeg = if (idling) 0.5f else (load * 25).toFloat(),
            altDutyPct = 38f,
            throttleMotorPct = (load * 30).toFloat(),
        )
        val tcu = TcuSnapshot(
            timeMs = nowMs,
            atfC = atf,
            gear = gear,
            turbineRpm = turbine.toInt(),
            linePressurePct = (30 + load * 60).toFloat(),
            lockUpPct = if (lockUp) 95f else 0f,
            transferPct = transfer.toFloat(),
            frontWheelKmh = (v + if (v < 15 && accel > 3) 3 else 0).toInt(),
            rearWheelKmh = v.toInt(),
            range = if (v < 1 && t < 4) "P/N" else "D",
        )
        return ecu to tcu
    }

    // The fake GPS goes round a closed ~1.4 km circuit in the centre of Bishkek, once per cycle, as far along it as
    // the demo speed has driven, and stops at the same red light every time: the lap timer and the maps of runs and
    // laps can be tried in the demo (a car heading east for ever never comes back to a start/finish line).
    private const val LAT0 = 42.8746
    private const val LON0 = 74.6122

    /** Metres driven since the start of the cycle, every 0.1 s. */
    private val driven: DoubleArray by lazy {
        val n = (CYCLE * 10).toInt()
        DoubleArray(n + 1).also { a -> for (i in 1..n) a[i] = a[i - 1] + speedAt((i - 0.5) / 10) / 3.6 * 0.1 }
    }

    /** The circuit as x east, y north in metres, every ~1 m of its length (closed: the last point is the first). */
    private val circuit: List<Pair<Double, Double>> by lazy {
        // a lumpy oval: a long straight-ish side, a hairpin, a couple of sweepers
        val raw = (0..720).map { k ->
            val a = 2 * Math.PI * k / 720
            val r = 1 + 0.28 * sin(2 * a) + 0.12 * cos(3 * a)
            r * 1.4 * cos(a) to r * sin(a)
        }
        val len = raw.zipWithNext { p, q -> Math.hypot(q.first - p.first, q.second - p.second) }.sum()
        val k = driven.last() / len
        raw.map { (x, y) -> (x - raw[0].first) * k to (y - raw[0].second) * k }
    }
    private val along: DoubleArray by lazy {
        DoubleArray(circuit.size).also { a ->
            for (i in 1 until circuit.size) a[i] = a[i - 1] + Math.hypot(circuit[i].first - circuit[i - 1].first, circuit[i].second - circuit[i - 1].second)
        }
    }

    /** Index on [circuit] of the point the car is at now. */
    private fun spot(nowMs: Long): Int {
        val t = (nowMs / 1000.0) % CYCLE
        val d = driven[(t * 10).toInt().coerceIn(0, driven.size - 1)]
        var lo = 0; var hi = along.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (along[m] < d) lo = m + 1 else hi = m }
        return lo
    }

    /** Course for the fake GPS: along the circuit. */
    fun bearing(nowMs: Long): Float {
        val i = spot(nowMs).coerceAtMost(circuit.size - 2)
        val (x0, y0) = circuit[i]; val (x1, y1) = circuit[i + 1]
        return ((Math.toDegrees(Math.atan2(x1 - x0, y1 - y0)) + 360) % 360).toFloat()
    }

    @Suppress("UNUSED_PARAMETER")
    fun position(nowMs: Long, speedKmh: Float, bearing: Float?): Pair<Double, Double> {
        val (x, y) = circuit[spot(nowMs)]
        return (LAT0 + y / 111_320.0) to (LON0 + x / (111_320.0 * cos(Math.toRadians(LAT0))))
    }

    /** Instant consumption for the demo, same formula as the real one. */
    fun litersPerHour(e: EcuSnapshot) = FuelCalc.litersPerHour(e, 1.0)

}
