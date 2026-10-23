package kg.forester.sg

import kg.forester.sg.service.EcuPt
import kg.forester.sg.service.GpsPt
import kg.forester.sg.service.Interval
import kg.forester.sg.service.PerfCalc
import kg.forester.sg.service.PerfCalc.Flag
import kg.forester.sg.service.RunTrace
import kg.forester.sg.service.SpoolPt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.sqrt

class PerfCalcTest {
    /** ECU samples every ~83 ms (the fast poll) with ±20 ms of jitter, over [sec] seconds. */
    private fun trace(
        sec: Double, kmh: (Double) -> Double, dtMs: Int = 83, k: Float = 1f,
        pedal: (Double) -> Float? = { 100f }, rpm: (Double) -> Int? = { null }, thr: (Double) -> Float? = { null },
        gps: List<GpsPt> = emptyList(), skip: (Double) -> Boolean = { false },
    ): RunTrace {
        val e = ArrayList<EcuPt>()
        var i = 0
        while (true) {
            val ms = i * dtMs + (if (i % 3 == 0) 20 else if (i % 3 == 1) -15 else 0).coerceAtLeast(-i * dtMs)
            val t = ms / 1000.0
            if (t > sec) break
            if (!skip(t)) e += EcuPt(ms, kmh(t).toFloat(), rpm(t), null, pedal(t), thr(t))
            i++
        }
        return RunTrace(0L, k, e, gps)
    }

    // 4 m/s² from a standstill at t = 2 s
    private fun standing(t: Double) = if (t < 2) 0.0 else 4.0 * (t - 2) * 3.6

    @Test
    fun standingIntervalInterpolatesTheCrossings() {
        val m = PerfCalc.measure(trace(12.0, ::standing), Interval.list("0-60,0-100"))
        assertEquals(2.0, m.launch!!, 0.02)
        assertEquals(60 / 3.6 / 4, m.results()["0-60"]!!.toDouble(), 0.02)
        assertEquals(100 / 3.6 / 4, m.results()["0-100"]!!.toDouble(), 0.02)
    }

    @Test
    fun rolloutVariantStartsOneFootLater() {
        // a foot at 4 m/s² takes sqrt(2 × 0.3048 / 4) = 0.39 s: the Dragy / drag strip time is that much shorter
        val r = PerfCalc.measure(trace(12.0, ::standing), Interval.list("0-60,0-100")).results()
        val ro = sqrt(2 * PerfCalc.ROLLOUT_M / 4)
        assertEquals(100 / 3.6 / 4 - ro, r["0-100r"]!!.toDouble(), 0.03)
        assertEquals(60 / 3.6 / 4 - ro, r["0-60r"]!!.toDouble(), 0.03)
        assertTrue(PerfCalc.isRollout("0-100r"))
        assertFalse(PerfCalc.isRollout("100-200"))
        assertNull(Interval.parse("0-100r"))
        // rolling intervals have no roll-out
        val rolling = PerfCalc.measure(trace(12.0, { t -> 40 + 14.4 * t }), Interval.list("60-100")).results()
        assertTrue(rolling.keys.none { PerfCalc.isRollout(it) })
    }

    @Test
    fun rotatingMassByTheGear() {
        // 3rd of a 5MT (overall 6.0) at 72 km/h on a 2 m tyre: 20 m/s, 3600 rpm
        assertEquals(1.04 + 0.0025 * 36, PerfCalc.rotatingMass(3600, 20.0), 1e-9)
        // 5th (3.33) at 120 km/h
        assertEquals(1.04 + 0.0025 * 3.33 * 3.33, PerfCalc.rotatingMass((3.33 * 60 * 33.333 / 2).toInt(), 33.333), 0.002)
        // 1st at a crawl is capped; no speed, no ratio
        assertEquals(1.8, PerfCalc.rotatingMass(6000, 3.0), 1e-9)
        assertEquals(1.04, PerfCalc.rotatingMass(3000, 0.5), 1e-9)
    }

    @Test
    fun speedSensorDeadBelowFourKmhDoesNotStartTheClockLate() {
        // whole km/h, nothing under 4 km/h: the start is found back on the line of the first samples
        val m = PerfCalc.measure(trace(12.0, { t -> standing(t).let { if (it < 4) 0.0 else floor(it) } }), Interval.list("0-100"))
        assertEquals(2.0, m.launch!!, 0.08)
        assertEquals(6.944, m.results()["0-100"]!!.toDouble(), 0.1)
    }

    @Test
    fun distancesAndTrapSpeed() {
        val m = PerfCalc.measure(trace(20.0, ::standing), emptyList())
        val r = m.results()
        // as on a drag strip: from one foot of roll-out to the line plus that foot; the trap over the last 66 ft
        fun at(x: Double) = sqrt(2 * x / 4)
        val ft = PerfCalc.ROLLOUT_M
        assertEquals(at(402.336 + ft) - at(ft), r["402m"]!!.toDouble(), 0.02)
        assertEquals(PerfCalc.TRAP_M / (at(402.336 + ft) - at(402.336 + ft - PerfCalc.TRAP_M)) * 3.6, r["402v"]!!.toDouble(), 0.3)
        assertEquals(at(18.288 + ft) - at(ft), r["18m"]!!.toDouble(), 0.02)
        assertNull(r["18v"])
    }

    @Test
    fun distanceLineAtSteadyAccelerationWithSparseSamples() {
        // GPS at 4 Hz: the line is crossed between samples a quarter of a second apart
        val r = PerfCalc.measure(trace(20.0, ::standing, dtMs = 250), emptyList()).results()
        val ft = PerfCalc.ROLLOUT_M
        assertEquals(sqrt(2 * (201.168 + ft) / 4) - sqrt(2 * ft / 4), r["201m"]!!.toDouble(), 0.01)
    }

    @Test
    fun rollingIntervalNeedsToComeFromBelow() {
        // from 80 km/h at 2 m/s²
        val tr = trace(20.0, { 80 + 2.0 * it * 3.6 })
        val m = PerfCalc.measure(tr, Interval.list("100-150,60-100,0-100"))
        assertNull(m.launch)
        assertEquals(50 / 3.6 / 2, m.results()["100-150"]!!.toDouble(), 0.02)
        assertFalse("60-100" in m.results()) // started above 60
        assertFalse("0-100" in m.results())
    }

    @Test
    fun recountOnOtherIntervalsFromTheSavedTrace() {
        val tr = trace(30.0, { t -> if (t < 2) 0.0 else minOf(220.0, 3.0 * (t - 2) * 3.6) })
        val back = RunTrace.decode(tr.encode())!!
        assertEquals(tr.ecu.size, back.ecu.size)
        val first = PerfCalc.measure(back, Interval.list("0-100")).results()
        val again = PerfCalc.measure(back, Interval.list("0-100,100-200,80-120,50-150")).results()
        assertEquals(first["0-100"]!!, again["0-100"]!!, 0.001f)
        assertEquals(100 / 3.6 / 3, again["100-200"]!!.toDouble(), 0.03)
        assertEquals(40 / 3.6 / 3, again["80-120"]!!.toDouble(), 0.03)
        assertEquals(100 / 3.6 / 3, again["50-150"]!!.toDouble(), 0.03)
    }

    @Test
    fun intervalsParse() {
        assertEquals(listOf(Interval(0, 100), Interval(60, 100), Interval(100, 150)), Interval.list("100-150, 0-100,60-100,x,150-100,0-100"))
    }

    @Test
    fun gpsUsedWhenDenseElseEcuScaledToIt() {
        // the ECU reads 4 % low; a 10 Hz receiver is taken as it is
        val dense = (0..150).map { GpsPt(it * 100, (80 + 2.0 * it / 10 * 3.6).toFloat(), 3f) }
        val a = PerfCalc.measure(trace(15.0, { (80 + 2.0 * it * 3.6) / 1.04 }, gps = dense), Interval.list("100-150"))
        assertTrue(a.speed.gps)
        assertEquals(6.944, a.results()["100-150"]!!.toDouble(), 0.02)
        // 1 Hz, arriving 400 ms late: the ECU speed, scaled by the factor fitted with the lag
        val sparse = (0..15).map { s -> GpsPt(s * 1000 + 400, (80 + 2.0 * s * 3.6).toFloat(), 5f) }
        val tr = trace(15.0, { (80 + 2.0 * it * 3.6) / 1.04 }, gps = sparse)
        assertEquals(1.04f, PerfCalc.fitK(tr)!!, 0.005f)
        val b = PerfCalc.measure(tr, Interval.list("100-150"))
        assertFalse(b.speed.gps)
        assertEquals(6.944, b.results()["100-150"]!!.toDouble(), 0.05)
    }

    @Test
    fun shiftsFoundAndLiftIsNot() {
        // rpm climbing in a gear, a 0.6 s shift at 5 s, then a lift at the end
        val rpm = { t: Double -> when { t < 5 -> 3000 + 600 * t; t < 5.6 -> 6000 - 4000 * (t - 5); t < 9 -> 3600 + 400 * (t - 5.6); else -> 4960 - 1500 * (t - 9) }.toInt() }
        val tr = trace(11.0, { 60 + 10 * it }, rpm = rpm)
        val m = PerfCalc.measure(tr, emptyList())
        assertEquals(1, m.shifts.size)
        assertEquals(0.6, m.shifts[0].sec, 0.12)
        assertEquals(5.0, m.shifts[0].from, 0.1)
    }

    @Test
    fun gCurveAndPeak() {
        val m = PerfCalc.measure(trace(12.0, { t -> standing(t).let { floor(it) } }), Interval.list("0-100"))
        val g = PerfCalc.gCurve(m.speed, 2.5, 8.0)
        assertTrue(g.isNotEmpty())
        g.forEach { assertEquals(4 / 9.80665, it.g, 0.08) }
        assertEquals(4 / 9.80665, PerfCalc.peakG(m)!!, 0.1)
    }

    @Test
    fun wotFlagButNotForShiftLifts() {
        val rpm = { t: Double -> when { t < 5 -> 2000 + 700 * t; t < 5.6 -> 5500 - 3000 * (t - 5); else -> 3700 + 500 * (t - 5.6) }.toInt() }
        // a manual: the pedal up during the shift only
        val ok = trace(12.0, { 80 + 2.0 * it * 3.6 }, rpm = rpm, pedal = { t -> if (t in 5.0..5.6) 0f else 100f })
        val m1 = PerfCalc.measure(ok, Interval.list("100-150"))
        assertTrue(PerfCalc.flags(ok, m1, m1.hits.first()).isEmpty())
        // the pedal at 80 % for a second in the middle
        val part = trace(12.0, { 80 + 2.0 * it * 3.6 }, pedal = { t -> if (t in 6.0..7.0) 80f else 100f })
        val m2 = PerfCalc.measure(part, Interval.list("100-150"))
        assertTrue(Flag.WOT in PerfCalc.flags(part, m2, m2.hits.first()))
        // limp mode: pedal down, throttle plate at 30 %
        val limp = trace(12.0, { 80 + 2.0 * it * 3.6 }, thr = { 30f })
        val m3 = PerfCalc.measure(limp, Interval.list("100-150"))
        assertTrue(Flag.LIMP in PerfCalc.flags(limp, m3, m3.hits.first()))
    }

    @Test
    fun zeroGapAndSlopeFlags() {
        // the speed back to zero for half a second after the start
        val stuck = trace(12.0, { t -> if (t in 2.5..3.0) 0.0 else standing(t) })
        val m1 = PerfCalc.measure(stuck, Interval.list("0-100"))
        assertTrue(Flag.ZERO in PerfCalc.flags(stuck, m1, m1.hits.first { it.key == "0-100" }))
        // a second without samples
        val gap = trace(12.0, ::standing, skip = { it in 4.0..5.0 })
        val m2 = PerfCalc.measure(gap, Interval.list("0-100"))
        assertTrue(Flag.GAP in PerfCalc.flags(gap, m2, m2.hits.first { it.key == "0-100" }))
        // downhill 2 %: the GPS altitude falls 2 m per 100 m driven
        val alt = (0..20).map { s -> val d = 22.22 * s + 0.5 * 2.0 * s * s; GpsPt(s * 1000, 0f, 5f, (300 - d * 0.02).toFloat()) }
        val hill = trace(20.0, { 80 + 2.0 * it * 3.6 }, gps = alt)
        val m3 = PerfCalc.measure(hill, Interval.list("100-150"))
        assertTrue(Flag.SLOPE in PerfCalc.flags(hill, m3, m3.hits.first()))
        assertEquals(-2.0, PerfCalc.slope(hill, m3.speed, m3.hits.first().from, m3.hits.first().to)!!, 0.3)
        val flat = trace(20.0, { 80 + 2.0 * it * 3.6 }, gps = alt.map { it.copy(alt = 300f) })
        val m4 = PerfCalc.measure(flat, Interval.list("100-150"))
        assertTrue(PerfCalc.flags(flat, m4, m4.hits.first()).isEmpty())
    }

    @Test
    fun statsAndAbVerdict() {
        val s = PerfCalc.stats(listOf(7.0, 7.2, 7.4))!!
        assertEquals(7.2, s.mean, 1e-9)
        assertEquals(0.2, s.sd, 1e-9)
        // clearly apart
        val apart = PerfCalc.compare(listOf(7.80, 7.85, 7.82, 7.78), listOf(7.55, 7.60, 7.58, 7.52))!!
        assertEquals(-0.25, apart.diff, 0.001)
        assertEquals(true, apart.significant)
        // within the scatter
        val same = PerfCalc.compare(listOf(7.6, 7.9, 7.7), listOf(7.65, 7.85, 7.55))!!
        assertEquals(false, same.significant)
        assertTrue(same.margin!! > kotlin.math.abs(same.diff))
        // one run says nothing
        assertNull(PerfCalc.compare(listOf(7.6), listOf(7.5, 7.4))!!.significant)
        assertEquals(12.706, PerfCalc.t95(1.0), 1e-9)
        assertEquals(2.228, PerfCalc.t95(10.0), 1e-9)
    }

    @Test
    fun saeCorrection() {
        assertEquals(1.0, PerfCalc.sae(24.85, 99.0), 1e-9)
        assertEquals(1.0, PerfCalc.din(19.85, 101.3), 1e-9)
        // hot and high: the engine made less, the corrected time is shorter
        val cf = PerfCalc.sae(35.0, 92.0)
        assertTrue(cf > 1.05)
        assertEquals(8.0 / cf, PerfCalc.corrected(8.0, 35.0, 92.0)!!, 1e-9)
        assertNull(PerfCalc.corrected(8.0, null, 99.0))
        assertNull(PerfCalc.corrected(8.0, 90.0, 99.0))
    }

    @Test
    fun spoolPointsAndDrift() {
        val pts = (0..30).map { val r = 1800 + it * 100; SpoolPt(r, (-40 + (r - 1800) * 0.08).toFloat()) }
        val r = PerfCalc.spool(pts, 120f)
        assertEquals(2300.0, r.rpm0!!.toDouble(), 2.0)
        assertEquals(3550.0, r.rpm1!!.toDouble(), 2.0)
        assertEquals(3737.0, r.rpmTarget!!.toDouble(), 3.0)
        assertNull(PerfCalc.spoolDrift(listOf(3000, 3050, 3020)))
        assertEquals(-30, PerfCalc.spoolDrift(listOf(3000, 3050, 3020, 3010, 3030))!!)
        assertEquals(400, PerfCalc.spoolDrift(listOf(3000, 3020, 2990, 3400, 3420, 3380))!!)
    }

    /** ±0.1 s only with GPS from 10 Hz or the ECU's fast poll; GPS 5 Hz or the plain ECU polling ±0.3 s. */
    @Test fun precisionBySampleRate() {
        fun series(hz: Double, gps: Boolean) = kg.forester.sg.service.SpeedSeries(
            DoubleArray(41) { it / hz }, DoubleArray(41) { 100.0 + it }, "", gps)
        assertEquals(0.1, series(10.0, true).precisionSec, 1e-9)
        assertEquals(0.3, series(5.0, true).precisionSec, 1e-9)
        assertEquals(0.1, series(12.0, false).precisionSec, 1e-9)
        assertEquals(0.3, series(4.0, false).precisionSec, 1e-9)
        assertEquals(4.0, series(4.0, false).hz, 1e-6)
        assertTrue(series(4.0, false).precisionText().startsWith("ЭБУ ~4 Гц"))
        assertTrue(series(4.0, false).precisionText().endsWith("±0,3 с"))
    }

    @Test
    fun brakingFromHundredTimeAndDistance() {
        // 110 km/h held for 2 s, then 9 m/s² (0.92 g) to a stop; the ECU reads nothing under 3 km/h
        val v0 = 110 / 3.6
        fun kmh(t: Double) = if (t < 2) 110.0 else maxOf(0.0, (v0 - 9.0 * (t - 2)) * 3.6).let { if (it < 3) 0.0 else it }
        val tr = trace(8.0, ::kmh, pedal = { 0f })
        val m = PerfCalc.measure(tr, Interval.list("0-100,60-100"))
        val h = m.hits.single { it.key == PerfCalc.BRAKE }
        // from 100 km/h: 27.78 m/s at 9 m/s² is 3.09 s and 42.9 m (the dead sensor's last 3 km/h cost ~0.06 s)
        val v = 100 / 3.6
        assertEquals(v / 9.0, h.sec, 0.12)
        assertEquals(v * v / 2 / 9.0, h.dist!!, 1.0)
        assertTrue(m.braking)
        assertEquals(h.dist!!.toFloat(), m.results()[PerfCalc.BRAKE + PerfCalc.DIST]!!, 1e-4f)
        assertTrue(PerfCalc.peakG(m)!! < -0.85)
        // no gas held is no flag for a braking
        assertTrue(PerfCalc.flags(tr, m, h).isEmpty())
        // an ordinary stop (2 m/s²) is no test
        val soft = trace(20.0, { t -> if (t < 2) 110.0 else maxOf(0.0, (v0 - 2.0 * (t - 2)) * 3.6) })
        assertNull(PerfCalc.measure(soft, emptyList()).hits.firstOrNull { it.key == PerfCalc.BRAKE })
        // an acceleration run has none
        assertFalse(PerfCalc.measure(trace(12.0, ::standing), Interval.list("0-100")).braking)
    }

    @Test
    fun skyTestAsDragy() {
        fun pts(sats: Int?, cn0: Float?) = List(10) { GpsPt(it * 100, 50f, 3f, null, sats, cn0) }
        assertEquals(true, PerfCalc.skyOk(pts(9, 41f)))
        assertEquals(true, PerfCalc.skyOk(pts(11, 36f)))
        assertEquals(false, PerfCalc.skyOk(pts(9, 36f)))
        assertEquals(false, PerfCalc.skyOk(pts(6, 45f)))
        assertNull(PerfCalc.skyOk(pts(null, null)))
    }

    @Test
    fun traceKeepsSkyAndReadsOldFormat() {
        val tr = RunTrace(1000L, 1.02f, listOf(EcuPt(0, 10f, 2000)), listOf(GpsPt(50, 11f, 3f, 200f, 12, 41.5f)))
        val back = RunTrace.decode(tr.encode())!!
        assertEquals(12, back.gps[0].sats)
        assertEquals(41.5f, back.gps[0].cn0!!, 0.01f)
        // a trace written before the satellites were kept
        val old = RunTrace.decode("trace 1 1000 1.0000\ne 0 10.0 2000 - - - -\ng 50 11.0 3.0 200.0\n")!!
        assertNull(old.gps[0].sats)
        assertNull(old.gps[0].cn0)
        assertEquals(200f, old.gps[0].alt!!, 0.01f)
    }
}
