package kg.forester.sg

import kg.forester.sg.data.CanChannel
import kg.forester.sg.data.CanEcu
import kg.forester.sg.ssm.LinkDash
import kg.forester.sg.ssm.Slcan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkCanTest {
    @Before fun clean() { LinkDash.reset(); CanEcu.reset(); CanEcu.active = false }

    @Test fun slcanLinesBecomeFrames() {
        val f = Slcan.parse("t3E8800B80BC800000000")!!
        assertEquals(0x3E8, f.id)
        assertFalse(f.ext)
        assertArrayEquals(byteArrayOf(0, 0xB8.toByte(), 0x0B, 0xC8.toByte(), 0, 0, 0, 0), f.data)
        // with a time stamp after the data, as some firmwares send it
        assertEquals(2, Slcan.parse("t12320102ABCD")!!.data.size)
        val e = Slcan.parse("T18FEF100111")!!
        assertTrue(e.ext)
        assertEquals(0x18FEF100, e.id)
        assertEquals(1, e.data.size)
        assertNull(Slcan.parse("")) // OK answer
        assertNull(Slcan.parse("r3E80")) // remote request
        assertNull(Slcan.parse("t3E8")) // cut short
        assertNull(Slcan.parse("t3E89"))
        // one bit rate table (CanBitrates) for the settings, the wizard, the probe and the slcan command
        assertEquals("S6", Slcan.speedCommand(500))
        assertEquals("S8", Slcan.speedCommand(1000))
        assertEquals("S2", Slcan.speedCommand(50))
        assertEquals("S3", Slcan.speedCommand(100))
        assertEquals("s451C", Slcan.speedCommand(83))
        assertEquals("S6", Slcan.speedCommand(42)) // no way to set it: 500
        assertTrue(Slcan.matches(0xAD50, 0x60C4, null))
        assertTrue(Slcan.matches(0x0483, 0x1234, "CANable 2.0"))
        assertFalse(Slcan.matches(0x0483, 0x5740, "STM32 Virtual ComPort"))
    }

    /** Generic Dash frame [n]: byte 0 the number, then three values low byte first. */
    private fun frame(n: Int, a: Int, b: Int, c: Int) = byteArrayOf(
        n.toByte(), 0, (a and 0xFF).toByte(), (a shr 8).toByte(), (b and 0xFF).toByte(), (b shr 8).toByte(), (c and 0xFF).toByte(), (c shr 8).toByte(),
    )

    private fun dash(rpm: Int = 3000, wheels: Int = 600): List<ByteArray> = listOf(
        frame(0, rpm, 150, 150),          // rpm, MAP 150 kPa, MGP +50
        frame(1, 1013, 250, 456),          // baro 101.3, TPS 25.0 %, IDC 45.6 %
        frame(2, 0, 3500, 140),            // IDC2, inj 3.5 ms, ECT 90
        frame(3, 80, 1380, 0),             // IAT 30, 13.8 V, MAF 0 (speed density)
        frame(4, 3, 360, 1150),            // gear 3, inj timing, ignition 15.0
        frame(5, 250, 255, 250),           // cams (the exhaust ones count backwards)
        frame(6, 250, 850, 860),           // cam ex R, lambda 0.850 / 0.860
        frame(7, 0, 1, 300),               // trigger errors, 1 fault, fuel 300 kPa
        frame(8, 150, 350, wheels),        // oil 100°, oil 350 kPa, LF wheel
        frame(9, wheels, wheels, wheels),  // LR, RF, RR
        frame(10, 1, 2, 3),
        frame(11, 4, 5, 6),
        frame(12, 7, 8, 1),                // knock 7, 8; limits: RPM limit
    )

    @Test fun genericDashIsFoundAndDecoded() {
        val now = 10_000L
        // another unit's compound-looking chatter on 0x200 must not be taken for the Link
        for (i in 0 until 8) assertNull(LinkDash.feed(0x200, false, byteArrayOf(i.toByte(), 1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), now))
        dash().forEachIndexed { i, f -> LinkDash.feed(1000, false, f, now + i) }
        assertEquals(1000, LinkDash.id)
        val s = LinkDash.snapshot(now + 20)
        assertEquals(3000, s.rpm)
        assertEquals(150f, s.mapKpa!!, 0.01f)
        assertEquals(101.3f, s.atmKpa!!, 0.01f)
        assertEquals(25f, s.throttlePct!!, 0.01f)
        assertEquals(3.5f, s.injPwMs!!, 0.001f)
        assertEquals(90, s.coolantC)
        assertEquals(30, s.intakeC)
        assertEquals(13.8f, s.batteryV!!, 0.001f)
        assertNull("a speed-density Link sends no MAF", s.mafGs)
        assertEquals(15f, s.timingDeg!!, 0.01f)
        assertEquals(0.85f, s.lambda!!, 0.001f)
        assertEquals(60, s.speedKmh)
        assertEquals(0.49f, s.boostBar!!, 0.01f)
        val v = LinkDash.values(now + 20)
        assertEquals(100f, v["OILT"]!!, 0.01f)
        assertEquals(3.5f, v["OILP"]!!, 0.001f)
        assertEquals(3.0f, v["FUELP"]!!, 0.001f)
        assertEquals(3f, v["GEAR"]!!, 0.01f)
        assertEquals(0.86f, v["LAM2"]!!, 0.001f)
        assertEquals(-25f, v["CAMEL"]!!, 0.01f)
        assertEquals(40f, v["KNOCK"]!!, 0.01f) // 8 × 5
        assertEquals(listOf("RPM limit"), LinkDash.limits(now + 20))
        // frames gone stale: nothing made up
        assertNull(LinkDash.snapshot(now + 10_000).rpm)
    }

    @Test fun wheelSpeedsOnlyOnceTheLinkGetsThem() {
        dash(wheels = 0).forEach { LinkDash.feed(1000, false, it, 1000L) }
        assertNull("zeros from an unwired input are no standstill", LinkDash.snapshot(1000L).speedKmh)
        dash(wheels = 300).forEach { LinkDash.feed(1000, false, it, 2000L) }
        assertEquals(30, LinkDash.snapshot(2000L).speedKmh)
        dash(wheels = 0).forEach { LinkDash.feed(1000, false, it, 3000L) }
        assertEquals(0, LinkDash.snapshot(3000L).speedKmh)
    }

    @Test fun oilPressureAlarmOnlyFromAWiredSensor() {
        CanEcu.active = true
        // engine off, sensor not wired: 0 must not read as "no oil pressure"
        dash().map { if (it[0].toInt() == 8) frame(8, 150, 0, 600) else it }.forEach { LinkDash.feed(1000, false, it, 1000L) }
        assertNull(CanEcu.role("oilP", 1000L))
        dash().forEach { LinkDash.feed(1000, false, it, 2000L) }
        assertEquals(3.5f, CanEcu.role("oilP", 2000L)!!, 0.001f)
        // once it has shown pressure, a drop to zero is real
        dash().map { if (it[0].toInt() == 8) frame(8, 150, 0, 600) else it }.forEach { LinkDash.feed(1000, false, it, 3000L) }
        assertEquals(0f, CanEcu.role("oilP", 3000L)!!, 0.001f)
        CanEcu.active = false
        assertNull(CanEcu.role("oilP", 3000L))
    }

    @Test fun ownChannelReadsBytes() {
        val egt = CanChannel("C1", "ЕГТ", 1001, false, -1, 2, 2, bigEndian = false, signed = false, formula = "x", units = "°C", decimals = 0, role = "egt")
        assertEquals(850.0, egt.decode(byteArrayOf(0, 0, 0x52, 0x03, 0, 0, 0, 0))!!, 0.01)
        val be = egt.copy(bigEndian = true)
        assertEquals(850.0, be.decode(byteArrayOf(0, 0, 0x03, 0x52, 0, 0, 0, 0))!!, 0.01)
        val scaled = egt.copy(formula = "x*0.1-50", signed = true)
        assertEquals(-60.0, scaled.decode(byteArrayOf(0, 0, 0x9C.toByte(), 0xFF.toByte()))!!, 0.01) // −100 raw
        assertNull("frame too short", egt.decode(byteArrayOf(0, 0, 1)))
        val muxed = egt.copy(mux = 7)
        assertNull("another frame of the compound stream", muxed.decode(byteArrayOf(6, 0, 0x52, 0x03)))
        assertEquals(850.0, muxed.decode(byteArrayOf(7, 0, 0x52, 0x03))!!, 0.01)
        val back = CanChannel.fromJson(egt.toJson())
        assertEquals(egt, back)
        assertNotNull(egt.toParam())
    }

    /** Dash2Pro frame: plain bytes, high byte first. */
    private fun be(vararg b: Int) = ByteArray(8) { (b.getOrElse(it) { 0 } and 0xFF).toByte() }

    private fun dash2Pro(rpm: Int = 2500, vss: Int = 0): List<Pair<Int, ByteArray>> = listOf(
        // rpm, MAP 95 kPa, CLT 90, IAT 25, 14.1 V, oil 95
        1000 to be(rpm shr 8, rpm, 0, 95, 140, 75, 141, 145),
        // TPS 12.5 %, ignition 20.0°, speed, oil 4.2 bar, fuel 3.0 bar, ECU 45°
        1001 to be(0, 125, 1200 shr 8, 1200 and 0xFF, vss, 42, 30, 95),
        // lambda 1.000 / 0.980, baro 100.0 kPa
        1002 to be(1000 shr 8, 1000 and 0xFF, 980 shr 8, 980 and 0xFF, 0, 0, 1000 shr 8, 1000 and 0xFF),
        // gear 4, no fuel cut, 0 % ignition cut, injection 2.500 ms, 0 faults, knock 12
        1003 to be(4, 0, 0, 2500 shr 8, 2500 and 0xFF, 0, 0, 12),
    )

    @Test fun dash2ProIsFoundAndDecoded() {
        // RealDash's "LINK: Short CAN": ids 1000–1003, any order
        dash2Pro().reversed().forEach { (id, d) -> LinkDash.feed(id, false, d, 5000L) }
        assertEquals(LinkDash.Format.DASH2PRO, LinkDash.format)
        assertEquals(1000, LinkDash.id)
        val s = LinkDash.snapshot(5000L)
        assertEquals(2500, s.rpm)
        assertEquals(95f, s.mapKpa!!, 0.01f)
        assertEquals(90, s.coolantC)
        assertEquals(25, s.intakeC)
        assertEquals(14.1f, s.batteryV!!, 0.001f)
        assertEquals(12.5f, s.throttlePct!!, 0.01f)
        assertEquals(20f, s.timingDeg!!, 0.01f)
        assertEquals(1.0f, s.lambda!!, 0.001f)
        assertEquals(100f, s.atmKpa!!, 0.01f)
        assertEquals(2.5f, s.injPwMs!!, 0.0001f)
        assertNull("speed 0 before the Link ever sent one", s.speedKmh)
        val v = LinkDash.values(5000L)
        assertEquals(95f, v["OILT"]!!, 0.01f)
        assertEquals(4.2f, v["OILP"]!!, 0.001f)
        assertEquals(3.0f, v["FUELP"]!!, 0.001f)
        assertEquals(0.98f, v["LAM2"]!!, 0.001f)
        assertEquals(4f, v["GEAR"]!!, 0.01f)
        assertEquals(45f, v["ECUT"]!!, 0.01f)
        assertEquals(12f, v["KNOCK"]!!, 0.01f)
        assertNull("Generic Dash only", v["CAMIL"])
        dash2Pro(vss = 88).forEach { (id, d) -> LinkDash.feed(id, false, d, 6000L) }
        assertEquals(88, LinkDash.snapshot(6000L).speedKmh)
    }

    @Test fun dash2ProOnAnotherBaseAndNotFromJunk() {
        // four sequential ids of something else: voltage 25.5 V, coolant 205° — not a Link
        (0..3).forEach { LinkDash.feed(0x500 + it, false, be(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), 1000L) }
        assertNull(LinkDash.format)
        // PCLink set to base 1200
        dash2Pro().forEach { (id, d) -> LinkDash.feed(id + 200, false, d, 2000L) }
        assertEquals(1200, LinkDash.id)
        assertEquals(2500, LinkDash.snapshot(2000L).rpm)
    }

    @Test fun linkHomeLayoutShowsWhatTheStreamGives() {
        val l = kg.forester.sg.data.HomeLayout.parse(kg.forester.sg.data.HomeLayout.linkDefault())
        val on = l.filter { it.on }.map { it.id }
        assertEquals(15, on.size)
        assertTrue(on.containsAll(listOf("oil", "map", "gaugeKpa", "afr", "timing")))
        assertTrue(on.none { it in setOf("maf", "afcorr", "aflearn", "knock", "wgdc") })
        // every card of the factory board is still there to switch on
        assertEquals(kg.forester.sg.data.HomeLayout.board.defaults.map { it.id }.toSet(), l.map { it.id }.toSet())
    }
}
