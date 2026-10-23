package kg.forester.sg

import kg.forester.sg.ssm.Multitronics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultitronicsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun framesAsItsAppBuildsThem() {
        // the app's own templates with their checks: {5,75,1,8,·} and {4,75,-15,·}
        assertArrayEquals(bytes(5, 0x4B, 0x01, 0x08, 0xA7), Multitronics.STATUS)
        assertArrayEquals(bytes(4, 0x4B, 0xF1, 0xC0), Multitronics.IDENTIFY)
        val c = java.util.Calendar.getInstance().apply { set(2026, 9, 23, 16, 5, 9) }
        val clock = Multitronics.clock(c)
        assertEquals(10, clock.size)
        assertArrayEquals(bytes(10, 0x4B, 0x0B, 16, 5, 9, 23, 10, 26), clock.copyOf(9))
        assertTrue(Multitronics.checkOk(IntArray(clock.size) { clock[it].toInt() and 0xFF }))
    }

    /** A 01 08 answer of 147 bytes with [set] values (field key → raw number) in place. */
    private fun status(set: Map<String, Int>, protocol: Int = 64): ByteArray {
        val f = IntArray(147)
        f[0] = 147; f[1] = 0x4B; f[2] = 0x01; f[3] = 0x08; f[67] = protocol
        for (fl in Multitronics.FIELDS) {
            val raw = set[fl.key] ?: continue
            val v = raw and 0xFFFF
            f[fl.at + 1] = v and 0xFF
            if (fl.len == 2) f[fl.at + 2] = v shr 8
        }
        f[146] = (-(f.sum()) and 0xFF)
        return ByteArray(147) { f[it].toByte() }
    }

    @Test
    fun theLayoutOfTheStatusFrame() {
        val fl = Multitronics.FIELDS
        assertEquals(41, fl.size)
        assertEquals(67, fl.first().at)
        // the single bytes, the last value ends right before the check
        assertEquals(listOf(101, 124, 127, 136), fl.filter { it.len == 1 }.map { it.at })
        assertEquals(145, fl.last().at + fl.last().len)
        assertEquals("ECT", fl[9].key); assertEquals(85, fl[9].at)
        assertEquals("BOOST", fl[38].key)
    }

    @Test
    fun valuesScaledAndZerosLeftOutUntilSeen() {
        val raw = status(mapOf(
            "SPD" to 0, "RPM" to 850, "INJ" to 245, "LH" to 92, "VBAT" to 141, "ECT" to 90, "IAT" to -5,
            "MAF" to 125, "TPS" to 3, "IGN" to 155, "TANK" to 30, "BOOST" to -60, "OILP" to 0,
        ))
        val frames = Multitronics.Frames()
        // in two pieces with noise in front, as Bluetooth hands it over
        assertTrue(frames.feed(bytes(0x0D, 0x41, 0x54) + raw.copyOf(60)).isEmpty())
        val f = frames.feed(raw.copyOfRange(60, raw.size)).single()
        assertTrue(Multitronics.isStatus(f))
        assertEquals(64, Multitronics.protocol(f))
        val seen = HashSet<String>()
        val v = Multitronics.values(f, seen)
        assertEquals(0f, v["SPD"]!!, 0f) // the speed counts at zero
        assertEquals(2.45f, v["INJ"]!!, 1e-4f)
        assertEquals(14.1f, v["VBAT"]!!, 1e-4f)
        assertEquals(-5f, v["IAT"]!!, 0f)
        assertEquals(15.5f, v["IGN"]!!, 1e-4f)
        assertNull(v["OILP"]) // a sensor the car has not got reads 0
        val s = Multitronics.snapshot(v, 1000L, 60.0)
        assertEquals(850, s.rpm)
        assertEquals(12.5f / 3.6f, s.mafGs!!, 1e-4f)
        assertEquals(0.92f, s.fuelRateLh!!, 1e-4f)
        assertEquals(50f, s.fuelLevelPct!!, 1e-3f)
        assertEquals(-60f, s.gaugeKpa!!, 0f)
        // the rest goes to «Датчики»: the tank in litres, not the oil pressure that never came
        assertEquals(30.0, Multitronics.extras(v)["MT_TANK"]!!, 0.0)
        assertFalse("MT_OILP" in Multitronics.extras(v))
        // once seen, a zero stays a value
        val v2 = Multitronics.values(Multitronics.Frames().feed(status(mapOf("RPM" to 900, "TPS" to 0))).single(), seen)
        assertEquals(0f, v2["TPS"]!!, 0f)
    }

    @Test
    fun aBadCheckIsDroppedAndTheNextFrameFound() {
        val bad = status(mapOf("RPM" to 1000)).also { it[100] = (it[100] + 1).toByte() }
        val good = status(mapOf("RPM" to 2000))
        val out = Multitronics.Frames().feed(bad + good)
        assertEquals(1, out.size)
        assertEquals(2000f, Multitronics.FIELDS[1].value(out[0])!!, 0f)
    }

    @Test
    fun whoTheBoxIs() {
        val f = IntArray(40)
        f[0] = 40; f[1] = 0x4B; f[2] = 0xF1
        "MPC800 2.10".forEachIndexed { i, c -> f[3 + i] = c.code }
        f[15] = 83; f[16] = 0; f[29] = 3
        val info = Multitronics.info(f)!!
        assertEquals("MPC800 2.10", info.version)
        assertEquals(83, info.build)
        assertEquals("MPC-800", info.model)
    }
}
