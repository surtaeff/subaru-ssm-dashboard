package kg.forester.sg

import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxPresets
import kg.forester.sg.data.BoxSlot
import kg.forester.sg.data.SensorBox
import kg.forester.sg.ssm.CanBroadcast
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.KLineTransport
import kg.forester.sg.ssm.SsmClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BusListenTest {
    /** A K-line where we can put any bytes, as if another cable sent them. */
    private class Line : KLineTransport() {
        override val name = "test"
        val sent = ArrayList<ByteArray>()
        override fun writeRaw(packet: ByteArray) { sent += packet; onBytes(packet) } // a KKL cable hears its own request
        fun hear(b: ByteArray) = onBytes(b)
        override fun close() {}
    }

    private fun frame(dst: Int, src: Int, body: ByteArray): ByteArray {
        val p = byteArrayOf(0x80.toByte(), dst.toByte(), src.toByte(), body.size.toByte()) + body
        return p + KLineTransport.checksum(p + 0, p.size)
    }

    @Test fun aLaptopOnTheSameKLineIsHeardAndLeftAlone() {
        KLineTransport.forgetOwn()
        val line = Line()
        assertFalse(line.passive)
        // the laptop asks coolant (0x08) and rpm (0x0E, 0x0F) over and over; the ECU answers it
        val ask = frame(SsmClient.ECU, 0xF0, byteArrayOf(0xA8.toByte(), 0, 0, 0, 0x08, 0, 0, 0x0E, 0, 0, 0x0F))
        line.hear(ask)
        // one request alone may be a leftover: not yet
        assertFalse(line.passive)
        Thread.sleep(KLineTransport.FOREIGN_SPACING_MS + 20)
        line.hear(ask)
        assertTrue(line.passive)
        line.hear(frame(0xF0, SsmClient.ECU, byteArrayOf(0xE8.toByte(), 0x82.toByte(), 0x0C, 0x80.toByte())))
        val (a, v) = line.sniffed(intArrayOf(0x08, 0x0E, 0x0F, 0x15), 2000)!!
        assertArrayEquals(intArrayOf(0x08, 0x0E, 0x0F), a)
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x0C, 0x80.toByte()), v)
        // and we keep quiet: nothing goes on the line while it talks
        assertNull(line.exchange(SsmClient.ECU, byteArrayOf(0xA8.toByte(), 0, 0, 0, 0x08)))
        assertTrue(line.sent.isEmpty())
    }

    @Test fun ourOwnEchoIsNotAnotherTester() {
        val line = Line()
        // our request echoes back; no ECU here, so it times out, but the line stays ours
        Thread { Thread.sleep(50); line.hear(frame(0xF0, SsmClient.ECU, byteArrayOf(0xE8.toByte(), 0x5A))) }.start()
        val r = line.exchange(SsmClient.ECU, byteArrayOf(0xA8.toByte(), 0, 0, 0, 0x08))
        assertArrayEquals(byteArrayOf(0xE8.toByte(), 0x5A), r)
        assertFalse(line.passive)
        // a garbled frame (bad checksum) is not a tester either
        line.hear(byteArrayOf(0x80.toByte(), 0x10, 0xF0.toByte(), 1, 0xA8.toByte(), 0x00))
        assertFalse(line.passive)
    }

    @Test fun subaruCanBroadcastDecodes() {
        CanBroadcast.clear()
        val now = 1_000_000L
        // 0x140: pedal 50 % (128), rpm 2500 in bits 16..29
        CanBroadcast.feed(0x140, byteArrayOf(128.toByte(), 0, (2500 and 0xFF).toByte(), (2500 shr 8).toByte(), 0, 0, 0, 0), now)
        // 0x360: oil 95 °C, coolant 88 °C (+40)
        CanBroadcast.feed(0x360, byteArrayOf(0, 0, (95 + 40).toByte(), (88 + 40).toByte(), 0, 0, 0, 0), now)
        // 0xD1: 72 km/h / 0.05625 = 1280
        CanBroadcast.feed(0xD1, byteArrayOf((1280 and 0xFF).toByte(), (1280 shr 8).toByte(), 0, 0, 0, 0, 0, 0), now)
        // 0x148: gear 3 in bits 48..51; 0x144: cruise on and active (bits 48, 49)
        CanBroadcast.feed(0x148, byteArrayOf(0, 0, 0, 0, 0, 0, 3, 0), now)
        CanBroadcast.feed(0x144, byteArrayOf(0, 0, 0, 0, 0, 0, 3, 0), now)
        // a diagnostic answer is not broadcast
        CanBroadcast.feed(0x7E8, byteArrayOf(1, 2, 3), now)
        val v = CanBroadcast.values(now + 100)
        assertEquals(2500, v.rpm)
        assertEquals(50.2f, v.pedalPct!!, 0.1f)
        assertEquals(95, v.oilC)
        assertEquals(88, v.coolantC)
        assertEquals(72f, v.speedKmh!!, 0.01f)
        assertEquals(3, v.gear)
        assertEquals(true, v.cruiseOn)
        assertEquals(true, v.cruiseActive)
        assertEquals(5, CanBroadcast.idsSeen)
        // the diagnostics win; the bus fills only what they left empty
        val e = CanBroadcast.fill(EcuSnapshot(timeMs = now, rpm = 2490), now + 100)
        assertEquals(2490, e.rpm)
        assertEquals(88, e.coolantC)
        assertEquals(72, e.speedKmh)
        // old frames are not taken for live ones
        assertNull(CanBroadcast.values(now + 5000).rpm)
        assertFalse(CanBroadcast.fresh(now + 5000))
    }

    @Test fun realCamAnglesAreInTheParameterBase() {
        val j = org.json.JSONObject(File("src/main/assets/romraider_ssm.json").readText())
        val ep = j.getJSONArray("ecuparams")
        val byId = (0 until ep.length()).map { ep.getJSONObject(it) }.associateBy { it.getString("id") }
        for (id in listOf("Z1", "Z2", "Z3", "Z4", "Z5", "Z6", "Z7", "Z8")) {
            val p = byId.getValue(id)
            assertEquals("float", p.getJSONArray("c").getJSONArray(0).getString(3))
            val r = p.getJSONObject("r")
            for (rom in listOf("3C04784316", "3C04504316", "5204504007", "5204584007", "5204784007", "4B04505207")) {
                assertEquals(4, r.getJSONArray(rom).getInt(1))
            }
        }
        // the left exhaust cam of A2WD012B is where Zombie47 found it
        assertEquals(0xFF93CC, byId.getValue("Z3").getJSONObject("r").getJSONArray("3C04784316").getInt(0))
    }

    @Test fun fourThermocouples() {
        val f = SensorBox.parseFrame("D t=10 vcc=5000 A0=512.0 TC0=812.50 TC1=845.25 TC2=!1 TC3=!8 F0=0.0")!!
        assertEquals(812.5f, f.tc)
        assertEquals(845.25f, f.tcs["TC1"])
        assertEquals(1, f.tcFaults["TC2"])
        assertEquals(8, f.tcFaults["TC3"])
        val tc = BoxPresets.all.first { it.kind == kg.forester.sg.data.BoxKind.TC }
        assertEquals(845.25f, SensorBox.read(BoxSlot.of("TC1", tc), f).value)
        assertEquals("обрыв термопары", SensorBox.read(BoxSlot.of("TC2", tc), f).problem)
        assertEquals("нет модуля MAX31855", SensorBox.read(BoxSlot.of("TC3", tc), f).problem)
        assertTrue(BoxBoard.NANO.inputs.containsAll(listOf("TC0", "TC1", "TC2", "TC3")))
        assertTrue(BoxPresets.forInput("TC2").all { it.kind == kg.forester.sg.data.BoxKind.TC })
    }

    @Test fun fuelGaugeWireReadsTheLineVoltage() {
        BoxPresets.loadText(File("../hardware/sensorbox/sensors-catalog.json").readText())
        val p = BoxPresets.of("fuel_level_line")!!
        assertEquals("fuelLevel", p.role)
        val s = BoxSlot.of("A0", p)
        // 1.5 V at the input behind the 22k/10k divider = 4.8 V on the gauge wire
        val f = SensorBox.parseFrame("D t=1 vcc=5000 A0=306.9")!!
        assertEquals(4.8f, SensorBox.read(s, f).value!!, 0.02f)
        // 15 V on the wire (open sender, charging voltage) stays under 5 V at the input
        assertTrue(15f / 3.2f < 5f)
    }

    @Test fun fuelLineByHandMarkedPoints() {
        val f = kg.forester.sg.data.FuelLine
        // higher volts = less fuel on a Subaru sender (more ohms when empty)
        val pts = f.parse("6.0,5;4.5,25;3.0,45;1.2,60")
        assertEquals(listOf(1.2f, 3.0f, 4.5f, 6.0f), pts.map { it.first })
        assertEquals(35f, f.litresAt(3.75f, pts)!!, 0.01f)
        assertEquals(60f, f.litresAt(0.5f, pts)!!, 0.01f) // past the end: the end value
        assertEquals(5f, f.litresAt(7f, pts)!!, 0.01f)
        assertNull(f.litresAt(3f, pts.take(1)))
        assertTrue(f.consistent(pts))
        assertFalse(f.consistent(pts + (3.5f to 50f)))
        assertEquals(pts, f.parse(f.format(pts)))
        // learnt only near the top of the tank: lower down it is not guessed from the last point
        val top = f.parse("1.2,60;1.6,57")
        assertNull(f.covered(4f, 60f, top))
        assertEquals(58.5f, f.covered(1.4f, 60f, top)!!, 0.01f)
        assertEquals(35f, f.covered(3.75f, 60f, pts)!!, 0.01f)
    }

    @Test fun fuelLinePointsAtStopsFromAFullTank() {
        val f = kg.forester.sg.data.FuelLine
        f.points.value = emptyList()
        // driving down from a full 60 l tank, a stop every couple of litres: a point every ~3 l
        var l = 60f
        while (l > 10f) { f.learn(1.0f + (60f - l) * 0.1f, l); l -= 1.3f }
        val pts = f.points.value
        assertTrue("${pts.size}", pts.size in 11..17)
        assertTrue(pts.zipWithNext().all { (a, b) -> kotlin.math.abs(a.second - b.second) >= 2.9f })
        assertTrue(f.consistent(pts))
        // the next tank reads 0.3 V higher at 60 l: the point moves a third of the way, not all of it
        val before = pts.first { it.second == 60f }.first
        f.learn(before + 0.3f, 60f)
        assertEquals(before + 0.1f, f.points.value.first { it.second == 60f }.first, 0.001f)
        f.points.value = emptyList()
    }

    @Test fun noPinIsOfferedTwice() {
        val b = BoxBoard.NANO
        val tc = BoxPresets.all.first { it.kind == kg.forester.sg.data.BoxKind.TC }
        SensorBox.slots.value = emptyList(); SensorBox.tcCs.value = emptyMap()
        kg.forester.sg.data.Displays.list.value = emptyList()
        kg.forester.sg.data.Relays.channels.value = emptyList()
        // a relay on D3, a TM1637 on D4/D5, a thermocouple's CS on the next free pin
        kg.forester.sg.data.Relays.channels.value = listOf(kg.forester.sg.data.OutTemplates.all.first().make(1, "D3"))
        kg.forester.sg.data.Displays.list.value = listOf(kg.forester.sg.data.DispCfg(1, kg.forester.sg.data.DispKind.TM1637, clk = "D4", dio = "D5"))
        SensorBox.put(BoxSlot.of("TC1", tc))
        assertEquals("D6", SensorBox.tcCs.value["TC1"])
        val owners = SensorBox.pinOwners(b)
        assertEquals(setOf("D3", "D4", "D5", "D6"), owners.keys)
        // whatever picks next gets none of them: a new output, a display, another thermocouple
        assertEquals("D7", kg.forester.sg.data.Relays.free(b)!!.second)
        assertFalse(kg.forester.sg.data.Displays.freeOutPins(b).any { it in owners.keys })
        SensorBox.put(BoxSlot.of("TC2", tc))
        assertEquals("D7", SensorBox.tcCs.value["TC2"])
        // the thing being edited still sees its own pins as its own
        assertTrue("D4" !in SensorBox.pinOwners(b, "disp:1").keys)
        assertTrue("D3" !in SensorBox.pinOwners(b, "out:1").keys)
        // an input with a sensor is not offered again; on an Uno an I2C display takes A4/A5
        assertTrue(SensorBox.inputBlocked(b, "TC1") != null)
        kg.forester.sg.data.Displays.list.value = listOf(kg.forester.sg.data.DispCfg(2, kg.forester.sg.data.DispKind.OLED))
        assertTrue(SensorBox.inputBlocked(BoxBoard.UNO, "A4") != null)
        assertNull(SensorBox.inputBlocked(BoxBoard.UNO, "A0"))
        // and the other way: a sensor on A4 of an Uno keeps an I2C display out
        kg.forester.sg.data.Displays.list.value = emptyList()
        SensorBox.put(BoxSlot.of("A4", BoxPresets.all.first { it.kind == kg.forester.sg.data.BoxKind.PRESSURE }))
        assertTrue(kg.forester.sg.data.Displays.unfit(kg.forester.sg.data.DispKind.OLED, BoxBoard.UNO) != null)
        SensorBox.slots.value = emptyList(); SensorBox.tcCs.value = emptyMap()
        kg.forester.sg.data.Relays.channels.value = emptyList()
    }

    @Test fun noCatalogueFieldShowsTheWordNull() {
        BoxPresets.loadText(File("../hardware/sensorbox/sensors-catalog.json").readText())
        for (p in BoxPresets.all) for ((k, v) in listOf("short" to p.short, "thread" to p.thread, "buy" to p.buy, "confidence" to p.confidence) +
            p.partNumbers.map { "part" to it } + p.tips.map { "tip" to it }) {
            assertFalse("${p.id} $k", v.trim().equals("null", ignoreCase = true))
        }
    }

    @Test fun fuelPressureUnits() {
        assertEquals(3.0f, kg.forester.sg.ui.Metrics.fuelBar(300f, "кПа"), 0.001f)
        assertEquals(3.0f, kg.forester.sg.ui.Metrics.fuelBar(43.51f, "psi"), 0.01f)
        assertEquals(3.0f, kg.forester.sg.ui.Metrics.fuelBar(3f, "бар"), 0.001f)
    }
}
