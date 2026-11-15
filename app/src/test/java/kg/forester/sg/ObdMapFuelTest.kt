package kg.forester.sg

import kg.forester.sg.service.FuelCalc
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.Elm327
import kg.forester.sg.ssm.ElmObd
import kg.forester.sg.ssm.Obd2
import kg.forester.sg.ssm.ObdLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * An old OBD-II car on MAP alone (WSW3DKP8: ISO 9141, no MAF, no 5E, no 42): the fuel by speed density, the
 * battery by ATRV, a broken Bluetooth link thrown at once, the slow extras read every few polls.
 */
class ObdMapFuelTest {
    // ---- speed density

    @Test fun speedDensityByTheFormula() {
        // 100 kPa, 2.0 l, 3000 rpm, 30 °C: 100 / (0.287 × 303.15) = 1.1494 g/l × 2 l × 25 fills/s × 0.85
        assertEquals(48.85, FuelCalc.speedDensityGs(100f, 3000, 30, 2.0f), 0.01)
        // idle: 35 kPa, 800 rpm
        assertEquals(4.56, FuelCalc.speedDensityGs(35f, 800, 30, 2.0f), 0.01)
        // twice the size, twice the air; colder air is denser
        assertEquals(2 * FuelCalc.speedDensityGs(60f, 2000, 20, 1.6f), FuelCalc.speedDensityGs(60f, 2000, 20, 3.2f), 1e-9)
        assertTrue(FuelCalc.speedDensityGs(60f, 2000, -10, 2.0f) > FuelCalc.speedDensityGs(60f, 2000, 40, 2.0f))
    }

    @Test fun obdCarWithMapOnlyGetsAFuelFigure() {
        val s = EcuSnapshot(rpm = 3000, mapKpa = 100f, intakeC = 30, fuelSystem = 2)
        // 48.85 g/s of air / 14.7 = 3.323 g/s of petrol × 3600 / 745 g/l
        assertEquals(16.06, FuelCalc.litersPerHour(s, 1.0), 0.02)
        assertEquals("map", FuelCalc.source)
        // the refills' factor refines it as it does the MAF and the injectors
        assertEquals(FuelCalc.litersPerHour(s, 1.0) * 1.2, FuelCalc.litersPerHour(s, 1.2), 1e-9)
        // the trims count as with a MAF
        assertEquals(FuelCalc.litersPerHour(s, 1.0) * 1.1, FuelCalc.litersPerHour(s.copy(afCorrPct = 6f, afLearnPct = 4f), 1.0), 1e-9)
        // no IAT yet (OBD reads it every few polls): 30 °C taken
        assertEquals(FuelCalc.litersPerHour(s, 1.0), FuelCalc.litersPerHour(s.copy(intakeC = null), 1.0), 1e-9)
        // the deceleration cut still counts nothing
        assertEquals(0.0, FuelCalc.litersPerHour(EcuSnapshot(rpm = 2500, mapKpa = 25f, atmKpa = 100f, intakeC = 30, fuelSystem = 4), 1.0), 0.0)
    }

    @Test fun ssmWithoutMafOrInjectorTimeGoesByTheBoostByte() {
        // a K-line EJ: no MAP byte, the atmosphere plus the relative pressure (-65 kPa at idle) = 35 kPa absolute
        val ssm = EcuSnapshot(rpm = 800, atmKpa = 100f, boostKpa = -65f, intakeC = 30)
        val want = 4.56 / 14.7 * 3600 / 745
        assertEquals(want, FuelCalc.litersPerHour(ssm, 1.0), 0.01)
        assertEquals("map", FuelCalc.source)
        // a MAF reading 0xFFFF is dropped as junk before: the same figure
        assertEquals(FuelCalc.litersPerHour(ssm, 1.0), FuelCalc.litersPerHour(ssm.copy(mafGs = 655.35f).saneMaf(), 1.0), 1e-9)
    }

    @Test fun theMafAndTheInjectorsComeFirst() {
        val s = EcuSnapshot(rpm = 3000, mapKpa = 100f, intakeC = 30)
        FuelCalc.litersPerHour(s.copy(mafGs = 20f), 1.0)
        assertEquals("maf", FuelCalc.source)
        FuelCalc.litersPerHour(s.copy(injPwMs = 4f), 1.0)
        assertEquals("inj", FuelCalc.source)
        // an injection time of 0xFF is junk: by the pressure then
        FuelCalc.litersPerHour(s.copy(injPwMs = 65.28f), 1.0)
        assertEquals("map", FuelCalc.source)
        // nothing to go by
        assertEquals(0.0, FuelCalc.litersPerHour(EcuSnapshot(rpm = 3000, intakeC = 30), 1.0), 0.0)
        assertEquals("", FuelCalc.source)
    }

    @Test fun displacementDefaultsToTwoLitresWithAHint() {
        assertEquals(FuelCalc.DEFAULT_LITRES, FuelCalc.litres(), 0f)
        assertTrue(FuelCalc.litresGuessed)
    }

    // ---- ATRV

    @Test fun atrvAnswers() {
        assertEquals(12.4f, ElmObd.parseVolts("12.4V")!!, 1e-6f)
        assertEquals(12.4f, ElmObd.parseVolts("12,4V")!!, 1e-6f)
        assertEquals(13.85f, ElmObd.parseVolts("ATRV\r13.85 V")!!, 1e-6f)
        assertNull(ElmObd.parseVolts("?"))
        assertNull(ElmObd.parseVolts("0.0V"))
        assertNull(ElmObd.parseVolts(""))
    }

    /** A K-line unit with [pids] (single PID per request); every mode 01 PID answers 0x50. */
    private class Link(val pids: Set<Int>, val volts: Float? = 12.6f, override val isCan: Boolean = false) : ObdLink {
        override val name = "test"
        val asked = ArrayList<Int>()
        var voltsAsked = 0
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
            val out = ArrayList<Byte>().apply { add(0x41) }
            for (p in ps) { out += p.toByte(); repeat(kg.forester.sg.ssm.ObdPids.LEN[p] ?: 1) { out += 0x50 } }
            return listOf(out.toByteArray())
        }
        override fun adapterVolts(): Float? { voltsAsked++; return volts }
        override fun close() {}
    }

    @Test fun batteryByAtrvEveryFiveSecondsWithoutPid42() {
        val link = Link(setOf(0x0C, 0x0D, 0x0B))
        val o = Obd2(link).also { assertTrue(it.init()) }
        assertEquals(12.6f, o.poll(1_000)!!.batteryV!!, 1e-6f)
        assertEquals(12.6f, o.poll(3_000)!!.batteryV!!, 1e-6f)
        assertEquals(1, link.voltsAsked)
        o.poll(6_100)
        assertEquals(2, link.voltsAsked)
        // the car's own PID 42: no ATRV
        val own = Link(setOf(0x0C, 0x0D, 0x20, 0x40, 0x42)) // 20 and 40: the next bitmaps are there
        Obd2(own).also { it.init() }.let { repeat(12) { i -> it.poll(1_000L + i * 1_000L) } }
        assertEquals(0, own.voltsAsked)
        // an adapter that can't: asked three times, then left alone
        val none = Link(setOf(0x0C, 0x0D), volts = null)
        val n = Obd2(none).also { it.init() }
        repeat(10) { i -> assertNull(n.poll(1_000L + i * 6_000L)!!.batteryV) }
        assertEquals(3, none.voltsAsked)
    }

    @Test fun slowExtrasEveryFourthPollFastOnesEveryPoll() {
        val link = Link(setOf(0x0C, 0x0D, 0x0B, 0x11, 0x06, 0x08, 0x09, 0x14))
        val o = Obd2(link).also { assertTrue(it.init()) }
        o.watched = setOf("OBD_08", "OBD_09", "OBD_14")
        repeat(8) { o.poll(1_000L + it * 2_500L) }
        assertEquals(8, link.asked.count { it == 0x0C })
        assertEquals(8, link.asked.count { it == 0x0B })
        assertEquals(8, link.asked.count { it == 0x14 }) // an O2 sensor: fast
        assertEquals(2, link.asked.count { it == 0x08 })
        assertEquals(2, link.asked.count { it == 0x09 })
        // a request of its own on the K-line: the bank 1 short trim every other poll
        assertEquals(4, link.asked.count { it == 0x06 })
        // the last values stay for the screen between the reads
        assertTrue("OBD_08" in o.extraValues())
        // CAN takes six PIDs a request: the short trim each time
        val can = Link(setOf(0x0C, 0x06), isCan = true)
        Obd2(can).also { it.init() }.let { o2 -> repeat(4) { o2.poll(1_000L + it * 1_000L) } }
        assertEquals(4, can.asked.count { it == 0x06 })
    }

    // ---- a broken link

    @Test fun brokenPipeIsThrownAndNothingMoreIsWritten() {
        var writes = 0
        var broken = false
        val out = object : OutputStream() {
            override fun write(b: Int) { writes++; if (broken) throw IOException("Broken pipe") }
            override fun write(b: ByteArray, off: Int, len: Int) { writes++; if (broken) throw IOException("Broken pipe") }
        }
        val elm = Elm327(ByteArrayInputStream(ByteArray(0)), out) {}
        val obd = ElmObd(elm)
        broken = true
        try { obd.request(byteArrayOf(0x01, 0x0C)); fail("swallowed") } catch (_: IOException) {}
        val after = writes
        try { obd.request(byteArrayOf(0x01, 0x0D)); fail("swallowed") } catch (_: IOException) {}
        try { obd.adapterVolts(); fail("swallowed") } catch (_: IOException) {}
        assertEquals(after, writes)
        // the OBD layer fails at once instead of waiting out timeouts
        val t0 = System.currentTimeMillis()
        assertTrue(runCatching { Obd2(obd).init() }.isFailure)
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
    }
}
