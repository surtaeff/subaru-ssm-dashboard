package kg.forester.sg

import kg.forester.sg.data.Derived
import kg.forester.sg.service.EngineGuard
import kg.forester.sg.service.FuelCalc
import kg.forester.sg.service.Gears
import kg.forester.sg.service.GravityTracker
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.Obd2
import kg.forester.sg.ssm.ObdLink
import kg.forester.sg.ssm.ObdPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gear guess, the G-meter's gravity, the OBD-II and six-cylinder fuel, IAM by units and the MAP byte's top. */
class CalcTest {
    // ---- the gear

    @Test fun wrxGearsShowWithTheSameTables() {
        // a WRX (final drive 3.9) cruising in 4th and 5th: the old single table showed «—» or 5th for its 4th
        val wrx4 = Gears.MT5_WRX.perKmh[3]
        val wrx5 = Gears.MT5_WRX.perKmh[4]
        assertEquals(4, Gears.estimate((wrx4 * 100).toInt(), 100, automatic = false, car = null))
        assertEquals(5, Gears.estimate((wrx5 * 110).toInt(), 110, automatic = false, car = null))
        assertEquals(4, Gears.estimate((wrx4 * 100).toInt(), 100, automatic = false, car = "USDM Impreza WRX 2004 MT"))
        // a Forester's 4th and 5th (4.111–4.444) stay what they were
        val ej4 = Gears.MT5.perKmh[3]
        val ej5 = Gears.MT5.perKmh[4]
        assertEquals(4, Gears.estimate((ej4 * 90).toInt(), 90, automatic = false, car = null))
        assertEquals(5, Gears.estimate((ej5 * 120).toInt(), 120, automatic = false, car = null))
        assertEquals(5, Gears.estimate((ej5 * 120).toInt(), 120, automatic = false, car = "JDM Forester XT 2003 MT"))
        // every gear of either box reads as itself
        for (box in listOf(Gears.MT5, Gears.MT5_WRX)) box.perKmh.forEachIndexed { i, r ->
            assertEquals(box.name, i + 1, Gears.estimate((r * 60).toInt(), 60, automatic = false, car = null))
        }
        // 4EAT with the converter locked
        assertEquals(3, Gears.estimate((Gears.EAT4.perKmh[2] * 70).toInt(), 70, automatic = true, car = null))
    }

    @Test fun learnedRatiosWinAndStiWaitsForThem() {
        // a 6MT STI: no table, until the car taught its own ratios (rpm per km/h, 1st first)
        assertNull(Gears.estimate(3000, 100, automatic = false, car = "USDM Impreza STI 2005 MT"))
        val learned = listOf(140f, 92f, 68f, 52f, 41f, 32f)
        assertEquals(5, Gears.estimate(4100, 100, automatic = false, learned = learned, car = "USDM Impreza STI 2005 MT"))
        assertEquals(6, Gears.estimate(3200, 100, automatic = false, learned = learned, car = null))
        // slower revs than the top gear allows: none, not the nearest table gear
        assertNull(Gears.estimate(2500, 100, automatic = false, learned = learned))
        // the rules ask without the tables
        assertNull(Gears.estimate((Gears.MT5.perKmh[3] * 90).toInt(), 90, automatic = false, car = null, table = false))
        assertEquals("N", Gears.text(900, 3, automatic = false))
        assertEquals("—", Gears.text(null, 50, automatic = false))
    }

    // ---- the G-meter's gravity

    private val down = floatArrayOf(0f, 0f, 9.81f)

    /** [sec] of the reading [v] at 50 Hz from [t0] (ns), the time after it returned. */
    private fun GravityTracker.feed(v: FloatArray, sec: Float, t0: Long): Long {
        var t = t0
        repeat((sec * 50).toInt()) { t += 20_000_000L; sample(v, t) }
        return t
    }

    @Test fun sustainedPullIsNotWashedOut() {
        val g = GravityTracker()
        g.start(down)
        g.tauSec = 0f // speeding up: gravity held
        var t = g.feed(down, 1f, 0L)
        // 0.6 g along the sensor's x for five seconds: still 0.6 g at the end (the old filter had ~8 % after one)
        t = g.feed(floatArrayOf(0.6f * 9.81f, 0f, 9.81f), 5f, t)
        assertEquals(0.6f * 9.81f, g.linear[0], 0.05f)
        assertEquals(9.81f, g.gravity!![2], 1e-3f)
        // with no speed at all, gravity follows over 30 s: five seconds leave most of the pull
        val blind = GravityTracker()
        blind.start(down)
        blind.tauSec = GravityTracker.tau(null, null, null)
        blind.feed(floatArrayOf(0.6f * 9.81f, 0f, 9.81f), 5f, t)
        assertTrue(blind.linear[0] > 0.8f * 0.6f * 9.81f)
    }

    @Test fun standingTakesInTheSlopeAndMovingOffIsTakenBack() {
        val g = GravityTracker()
        g.start(down)
        g.tauSec = GravityTracker.STILL_SEC
        // parked nose up 6°: within ten seconds that is gravity, not acceleration
        val slope = floatArrayOf(9.81f * 0.1045f, 0f, 9.81f * 0.9945f)
        var t = g.feed(slope, 10f, 0L)
        assertEquals(0f, g.linear[0], 0.05f)
        // moving off at 0.4 g while the ECU still shows 0 km/h for half a second, then the speed comes
        val pull = floatArrayOf(slope[0] + 0.4f * 9.81f, 0f, slope[2])
        t = g.feed(pull, 0.5f, t)
        g.rewind(GravityTracker.MOVE_OFF_SEC)
        g.tauSec = GravityTracker.tau(8f, 0.4f, null)
        g.feed(pull, 1f, t)
        assertEquals(0.4f * 9.81f, g.linear[0], 0.1f)
    }

    @Test fun gravityFollowsOnlyWhenStillOrSteady() {
        assertEquals(GravityTracker.NO_SPEED_SEC, GravityTracker.tau(null, null, null), 0f)
        assertEquals(GravityTracker.STILL_SEC, GravityTracker.tau(0f, null, null), 0f)
        assertEquals(0f, GravityTracker.tau(60f, 0.2f, null), 0f)          // speeding up
        assertEquals(0f, GravityTracker.tau(60f, null, null), 0f)          // GPS speed only: no slope to judge by
        assertEquals(GravityTracker.STEADY_BLIND_SEC, GravityTracker.tau(90f, 0.01f, null), 0f)
        assertEquals(GravityTracker.STEADY_SEC, GravityTracker.tau(90f, 0.01f, 0.02f), 0f)
        assertEquals(0f, GravityTracker.tau(90f, 0.01f, 0.3f), 0f)          // a long bend
    }

    // ---- fuel

    @Test fun obdDecelerationCutAndFuelRate() {
        // PID 03 = 4 with the throttle shut above idle: the fuel cut, nothing burnt (the MAF still reads air)
        val cut = EcuSnapshot(rpm = 2200, mafGs = 4f, mapKpa = 25f, atmKpa = 100f, fuelSystem = 4)
        assertEquals(0.0, FuelCalc.litersPerHour(cut, 1.0), 0.0)
        // the same 4 with the pedal down is enrichment: counted
        val wot = EcuSnapshot(rpm = 4500, mafGs = 90f, mapKpa = 98f, atmKpa = 100f, fuelSystem = 4, lambda = 0.85f)
        assertTrue(FuelCalc.litersPerHour(wot, 1.0) > 20.0)
        // no MAP: by the throttle
        assertEquals(0.0, FuelCalc.litersPerHour(EcuSnapshot(rpm = 2200, mafGs = 4f, throttlePct = 12f, fuelSystem = 4), 1.0), 0.0)
        assertTrue(FuelCalc.litersPerHour(EcuSnapshot(rpm = 2200, mafGs = 4f, mapKpa = 25f, atmKpa = 100f, fuelSystem = 2), 1.0) > 0.5)
        // idle in open loop is no cut
        assertTrue(FuelCalc.litersPerHour(EcuSnapshot(rpm = 800, mafGs = 2.5f, mapKpa = 30f, atmKpa = 100f, fuelSystem = 4), 1.0) > 0.5)
        // PID 5E, the unit's own figure: taken as it is (times the refills' correction), even with no MAF
        assertEquals(6.5 * 1.1, FuelCalc.litersPerHour(EcuSnapshot(rpm = 2000, fuelRateLh = 6.5f), 1.1), 1e-6)
        assertEquals("obd", FuelCalc.source)
        assertEquals(6.5, FuelCalc.litersPerHour(EcuSnapshot(rpm = 2000, mafGs = 20f, fuelRateLh = 6.5f), 1.0), 1e-6)
    }

    @Test fun sixCylindersBurnHalfAsMuchMoreByTheInjectors() {
        val four = FuelCalc.byInjectors(4f, 3000, 260.0, 0.6)
        assertEquals(four * 1.5, FuelCalc.byInjectors(4f, 3000, 260.0, 0.6, 6), 1e-9)
        val snap = EcuSnapshot(rpm = 2500, injPwMs = 3.2f)
        assertEquals(FuelCalc.litersPerHour(snap, 1.0, 4) * 1.5, FuelCalc.litersPerHour(snap, 1.0, 6), 1e-9)
    }

    /** A unit with [pids] answering [data]; [fixed] = one answer frame for any mode 01 request but the bitmaps. */
    private class Link(val pids: Set<Int>, val data: Map<Int, IntArray>, val fixed: ByteArray? = null) : ObdLink {
        override val name = "test"
        override val isCan = true
        val asked = ArrayList<Int>()
        override fun request(req: ByteArray): List<ByteArray>? {
            val ps = req.drop(1).map { it.toInt() and 0xFF }
            if (req[0].toInt() != 1) return null
            if (ps.size == 1 && ps[0] % 0x20 == 0) {
                val base = ps[0]
                var bits = 0L
                for (p in pids) if (p in base + 1..base + 32) bits = bits or (1L shl (31 - (p - base - 1)))
                return listOf(byteArrayOf(0x41, base.toByte(), (bits shr 24).toByte(), (bits shr 16).toByte(), (bits shr 8).toByte(), bits.toByte()))
            }
            asked += ps
            fixed?.let { return listOf(it) }
            val out = ArrayList<Byte>().apply { add(0x41) }
            for (p in ps) data[p]?.let { d -> out += p.toByte(); d.forEach { out += it.toByte() } }
            return listOf(out.toByteArray())
        }
        override fun close() {}
    }

    @Test fun obdAsksForTheFuelRateOrTheStatusOnlyWhenTheUnitHasThem() {
        val data = mapOf(0x0C to intArrayOf(0x1F, 0x40), 0x0D to intArrayOf(50), 0x03 to intArrayOf(4, 0), 0x5E to intArrayOf(0, 130))
        val withRate = Link(setOf(0x03, 0x0C, 0x0D, 0x20, 0x40, 0x5E), data)
        val a = Obd2(withRate).also { assertTrue(it.init()) }.poll(1000)!!
        assertEquals(6.5f, a.fuelRateLh!!, 1e-4f)
        assertEquals(2000, a.rpm)
        assertFalse(0x03 in withRate.asked)
        val statusOnly = Link(setOf(0x03, 0x0C, 0x0D), data)
        val b = Obd2(statusOnly).also { assertTrue(it.init()) }.poll(1000)!!
        assertEquals(4, b.fuelSystem)
        assertNull(b.fuelRateLh)
        assertFalse(0x5E in statusOnly.asked)
        val neither = Link(setOf(0x0C, 0x0D), data)
        Obd2(neither).also { it.init() }.poll(1000)
        assertTrue(neither.asked.none { it == 0x03 || it == 0x5E })
    }

    @Test fun obdAnswerLengths() {
        assertEquals(4, ObdPids.LEN[0x50])
        assertEquals(4, ObdPids.LEN[0x4F])
        assertEquals(7, ObdPids.LEN[0x68])
        // a CAN answer with 50 before 0C splits right: 50 carries four bytes (with two, 0C was lost behind it)
        val answer = byteArrayOf(0x41, 0x50, 20, 0, 0, 0, 0x0C, 0x0B, 0xB8.toByte())
        val o = Obd2(Link(setOf(0x0C, 0x50), emptyMap(), answer)).also { it.init() }
        assertEquals(750, o.poll(0)!!.rpm)
    }

    // ---- IAM and the MAP byte

    @Test fun iamByTheUnitsOfItsConversion() {
        // E1's first conversion is the raw 0…16: 8 is IAM 0.5, and so is a raw 0.5 never taken for 0.5
        assertEquals(0.5f, EngineGuard.iamOf(8f, "raw ecu value")!!, 1e-6f)
        assertEquals(0.03125f, EngineGuard.iamOf(0.5f, "raw ecu value")!!, 1e-6f)
        assertEquals(1f, EngineGuard.iamOf(16f, "raw ecu value")!!, 1e-6f)
        assertEquals(0.625f, EngineGuard.iamOf(0.625f, "multiplier")!!, 1e-6f)
        assertNull(EngineGuard.iamOf(16f, "multiplier"))
        assertEquals(0.8f, EngineGuard.iamOf(80f, "%")!!, 1e-6f)
        // a driver's definition with units of its own: by the size, as before
        assertEquals(0.75f, EngineGuard.iamOf(12f, "")!!, 1e-6f)
        assertEquals(0.75f, EngineGuard.iamOf(0.75f, "")!!, 1e-6f)
        assertNull(EngineGuard.iamOf(20f, "raw ecu value"))
    }

    @Test fun mapByteAtItsTop() {
        assertTrue(EngineGuard.mapAtLimit(255f, direct = false))
        assertTrue(EngineGuard.mapAtLimit(255.1f, direct = false))
        assertFalse(EngineGuard.mapAtLimit(255f, direct = true))
        assertFalse(EngineGuard.mapAtLimit(240f, direct = false))
        assertFalse(EngineGuard.mapAtLimit(null, direct = false))
    }

    @Test fun vePctMatchesFormula() {
        val want = 20f * 60 / 3000 * 2 * 287.05f * (30f + 273.15f) / (100f * 2.5f * 1000) * 100
        assertEquals(want, Derived.vePct(20f, 3000, 30, 100f, 2.5f)!!, 1e-3f)
    }

    @Test fun vePctNullBelow800Rpm() {
        assertNull(Derived.vePct(20f, 799, 30, 100f, 2.0f))
        assertEquals(true, Derived.vePct(20f, 800, 30, 100f, 2.0f) != null)
    }

    @Test fun vePctNullWithoutMaf() {
        assertNull(Derived.vePct(null, 3000, 30, 100f, 2.0f))
        assertNull(Derived.vePct(20f, 3000, 30, 100f, null))
    }

    @Test fun vePctNullWithMapUnder20() {
        assertNull(Derived.vePct(20f, 3000, 30, 19.9f, 2.0f))
        assertNull(Derived.vePct(20f, 3000, 30, null, 2.0f))
    }
}
