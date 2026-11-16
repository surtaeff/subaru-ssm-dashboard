package kg.forester.sg

import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxCan
import kg.forester.sg.data.BoxCanCfg
import kg.forester.sg.data.BoxCanFilter
import kg.forester.sg.data.SensorBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CAN on the ESP32-S3 SensorBox (firmware 6): the lines it sends, the settings the app sends it, the sketch's side. */
class BoxCanTest {
    private val ino = File("../hardware/sensorbox/sensorbox.ino").readText()

    @Test fun framesFromTheBox() {
        val f = BoxCan.parseLine("t1238DEADBEEF00112233")!!
        assertEquals(0x123, f.id)
        assertFalse(f.ext)
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x11, 0x22, 0x33), f.data)
        val e = BoxCan.parseLine("T18DAF1104A1B2C3D4")!!
        assertEquals(0x18DAF110, e.id)
        assertTrue(e.ext)
        assertEquals(4, e.data.size)
        // an empty frame, a short one
        assertEquals(0, BoxCan.parseLine("t7FF0")!!.data.size)
        assertEquals(0x7E8, BoxCan.parseLine("t7E83026101")!!.id)
        // remote frames, cut or garbled lines and the box's other lines are no frames
        assertNull(BoxCan.parseLine("r1230"))
        assertNull(BoxCan.parseLine("t1238DEAD"))
        assertNull(BoxCan.parseLine("t12G2AABB"))
        assertNull(BoxCan.parseLine("D t=1 vcc=5000 F0=0.0"))
        assertNull(BoxCan.parseLine(""))
    }

    @Test fun helloSaysCan() {
        val i = SensorBox.parseInfo("I SENSORBOX 2 board=esp32s3 id=1A2B fw=6 rate=20 disp=none can=twai ch=A0,A1,A2,A3,TC0,F0 out=D15", "USB 303A:1001")!!
        assertEquals("twai", i.can)
        assertEquals(6, i.fw)
        assertTrue("TC0" in i.inputs)
        assertTrue(BoxCan.supported(i))
        // an older box, an Arduino: no CAN, everything else as before
        val old = SensorBox.parseInfo("I SENSORBOX 2 board=esp32s3 id=1A2B fw=5 rate=20 disp=none ch=A0,A1 out=D15", "USB 303A:1001")!!
        assertEquals("", old.can)
        assertFalse(BoxCan.supported(old))
        assertFalse(BoxCan.supported(SensorBox.parseInfo("I SENSORBOX 2 board=nano id=1A2B fw=6 rate=20 disp=none ch=A0 out=D3", "USB")!!))
        assertFalse(BoxCan.supported(i.copy(via = "демо")))
    }

    @Test fun statusInTheDataLine() {
        val f = SensorBox.parseFrame("D t=500 vcc=5000 A0=512.0 F0=0.0 Q=10 fs=0 can=l,4000,2,1,5,0")!!
        assertEquals("l,4000,2,1,5,0", f.can)
        assertEquals(512f, f.raw["A0"]!!, 0.01f) // the sensors as before
        assertEquals("10", f.outs)
        val a = BoxCan.parseStatus(f.can)!!
        assertEquals('l', a.state)
        assertEquals(4000L, a.frames)
        assertEquals(2L, a.dropped)
        assertEquals(1L, a.missed)
        assertEquals(5, a.rxErrors)
        // the rate from two lines 50 ms apart
        val b = BoxCan.parseStatus("l,4200,2,1,5,0", a, 50)!!
        assertEquals(4000f, b.perSecond, 0.1f)
        // on screen: the rate over a second of the box's own clock, kept between the D lines
        BoxCan.onStatus("l,1000,0,0,0,0", 10_000)
        BoxCan.onStatus("l,1200,0,0,0,0", 10_050)
        assertEquals(0f, BoxCan.status.value!!.perSecond, 0.1f)
        BoxCan.onStatus("l,5000,0,0,0,0", 11_000)
        assertEquals(4000f, BoxCan.status.value!!.perSecond, 0.1f)
        BoxCan.onStatus("l,5200,0,0,0,0", 11_050)
        assertEquals(4000f, BoxCan.status.value!!.perSecond, 0.1f)
        BoxCan.onStatus("", 11_100)
        assertNull(BoxCan.status.value)
        assertNull(BoxCan.parseStatus("l,12"))
        assertNull(BoxCan.parseStatus("xx,1,2,3,4,5"))
        // no CAN: no field
        assertEquals("", SensorBox.parseFrame("D t=1 vcc=5000 F0=0.0")!!.can)
    }

    @Test fun settingsToTheBox() {
        val c = BoxCanCfg(on = true, kbit = 500, listenOnly = true, tx = "39", rx = "40", s = "41")
        assertEquals(listOf("G F", "G O 500 L 39 40 41"), BoxCan.commands(c))
        assertEquals("G O 83 N 15 16 -", BoxCan.commands(c.copy(kbit = 83, listenOnly = false, tx = "15", rx = "16", s = ""))[1])
        assertEquals(listOf("G C"), BoxCan.commands(c.copy(on = false)))
        assertEquals(listOf("G C"), BoxCan.commands(c.copy(rx = "")))
        // filters: two 11-bit, one 29-bit (a second is not sent), ids cut to their length
        assertEquals("G F 360 7FF 3F0 7F0", BoxCan.filterCommand(c.copy(filters = listOf(BoxCanFilter(0x360, 0x7FF), BoxCanFilter(0x3F0, 0x7F0)))))
        assertEquals("G F X 18DAF110 1FFFFFFF", BoxCan.filterCommand(c.copy(extFilter = true,
            filters = listOf(BoxCanFilter(0x18DAF110, 0x1FFFFFFF), BoxCanFilter(1, 1)))))
        assertEquals("G F 7FF 7FF", BoxCan.filterCommand(c.copy(filters = listOf(BoxCanFilter(0xFFFF, 0xFFFF)))))
        assertEquals(0x7E8, BoxCan.hexId("7e8", false))
        assertEquals(0x7E8, BoxCan.hexId("0x7E8", false))
        assertNull(BoxCan.hexId("800", false))
        assertEquals(0x18DAF110, BoxCan.hexId("18DAF110", true))
        assertNull(BoxCan.hexId("zz", true))
        // every speed the app offers is one the sketch knows
        val sketch = Regex("case (\\d+):").findAll(ino.substringAfter("bool canTiming(").substringBefore("default:")).map { it.groupValues[1].toInt() }.toSet()
        assertEquals(BoxCan.SPEEDS.toSet(), sketch)
    }

    @Test fun settingsSurviveSaving() {
        val c = BoxCanCfg(on = true, kbit = 125, listenOnly = false, tx = "15", rx = "16", s = "", filters = listOf(BoxCanFilter(0x100, 0x700)), chip = "sn65")
        assertEquals(c, BoxCanCfg.fromJson(org.json.JSONObject(c.toJson().toString())))
        // nothing saved, or an unknown speed: listen only at 500 on the default pins
        val d = BoxCanCfg.fromJson(org.json.JSONObject("{\"kbit\":777}"))
        assertEquals(500, d.kbit)
        assertTrue(d.listenOnly)
        assertFalse(d.on)
    }

    @Test fun pinsForTheTransceiver() {
        val all = BoxBoard.ESP32S3.outPins
        val c = BoxCanCfg(on = true)
        assertEquals(c, BoxCan.pickPins(c, all))
        // a relay on 40: three other free pins, the first ones
        val p = BoxCan.pickPins(c, all - "40")
        assertEquals(listOf("15", "16", "17"), listOf(p.tx, p.rx, p.s))
        // the default pins are outputs the sketch allows and printed on the board
        BoxCan.DEFAULT_PINS.forEach { assertTrue(it, it in all) }
        // SN65HVD230 has no S: two pins
        val sn = BoxCan.pickPins(c.copy(chip = "sn65", s = ""), listOf("15", "16"))
        assertEquals(listOf("15", "16", ""), listOf(sn.tx, sn.rx, sn.s))
    }

    @Test fun sketchSpeaksTheProtocol() {
        val esp = ino.substringAfter("// ---- CAN: the TWAI controller")
        assertTrue(ino.contains("Serial.print(F(\" can=twai\"))"))
        assertTrue(esp.contains("case 'G':") || ino.contains("case 'G':"))
        // frames as slcan lines, never written cut: a line goes out whole or is counted as dropped
        assertTrue(esp.contains("b[n++] = m.rtr ? (m.extd ? 'R' : 'r') : (m.extd ? 'T' : 't');"))
        assertTrue(esp.contains("availableForWrite() < n + CAN_RESERVE"))
        // listen only drives S high (the transceiver silent) and uses the controller's listen-only mode
        assertTrue(esp.contains("digitalWrite(canS, canListen ? HIGH : LOW)"))
        assertTrue(esp.contains("canListen ? TWAI_MODE_LISTEN_ONLY : TWAI_MODE_NORMAL"))
        // the version the app ships with
        assertEquals(kg.forester.sg.data.BOX_FIRMWARE, Regex("#define FW_VERSION (\\d+)").find(ino)!!.groupValues[1].toInt())
    }
}
