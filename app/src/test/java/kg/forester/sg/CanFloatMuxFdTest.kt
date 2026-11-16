package kg.forester.sg

import kg.forester.sg.data.CanChannel
import kg.forester.sg.ssm.CanDb
import kg.forester.sg.ssm.CanFrame
import kg.forester.sg.ssm.CanImport
import kg.forester.sg.ssm.Dbc
import kg.forester.sg.ssm.RealDashXml
import kg.forester.sg.ssm.Slcan
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * IEEE floats, extended multiplexing (SG_MUL_VAL_, m3M) and CAN FD frames. carpulse_mux_float.dbc is written for these
 * tests; vw_pq.dbc is opendbc's (MIT, test/resources/dbc/LICENSE-opendbc.txt).
 */
class CanFloatMuxFdTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val db by lazy { Dbc.parse(File("src/test/resources/dbc/carpulse_mux_float.dbc").readText()) }

    private fun ch(d: CanDb, id: Int, name: String): CanChannel {
        val m = d.messages.first { it.id == id }
        return CanImport.channel(m, m.signals.first { it.name == name }, "C1")
    }

    @Test fun floatsIntelAndMotorola() {
        // 1.5f low byte first, from byte 0: the byte model of 4 bytes
        val f = ch(db, 1281, "IntelFloat")
        assertEquals(CanChannel.FLOAT32, f.valueType)
        assertEquals(-1, f.startBit); assertEquals(4, f.len); assertFalse(f.bigEndian)
        assertFalse(f.signed)
        assertEquals(2, f.decimals)
        assertEquals(1.5, f.decode(hex("0000c03f00000000"))!!, 0.0)
        // NaN (a sensor's "no value") and infinity give nothing
        assertNull(f.decode(hex("0000c07f00000000")))
        assertNull(f.decode(hex("0000807f00000000")))
        // −40.25f high byte first at bytes 4–7, units °C
        val m = ch(db, 1281, "MotoFloat")
        assertTrue(m.bigEndian); assertEquals(4, m.byte)
        assertEquals("°C", m.units)
        assertEquals(-40.25, m.decode(hex("00000000c2210000"))!!, 0.0)
        // doubles are 64 bits: bit-level channels
        val dl = ch(db, 1282, "IntelDouble")
        assertEquals(CanChannel.FLOAT64, dl.valueType)
        assertEquals(0, dl.startBit); assertEquals(64, dl.bitLen)
        assertEquals(-273.15, dl.decode(hex("66666666661271c0"))!!, 0.0)
        val db2 = ch(db, 1283, "MotoDouble")
        assertEquals(7, db2.startBit); assertTrue(db2.bigEndian)
        assertEquals(12345.678, db2.decode(hex("40c81cd6c8b43958"))!!, 0.0)
        // a float of 16 bits is no float
        assertNotNull(db.signal(1283, "BadFloat")!!.problem)
        // the JSON keeps the type, and an old list has none
        assertEquals(dl, CanChannel.fromJson(dl.toJson()))
        assertEquals(CanChannel.FLOAT32, CanChannel.fromJson(f.toJson()).valueType)
        assertFalse(ch(db, 1280, "Temp").toJson().has("vt"))
        // a float set on 2 bytes (a hand-made mistake) reads nothing rather than junk
        assertNull(f.copy(len = 2).decode(hex("0000c03f00000000")))
        assertTrue(f.idText().endsWith(" float"))
    }

    @Test fun floatsInRealDashXml() {
        val xml = """<RealDashCAN version="2"><frames>
            <frame id="0x300" endianness="big"><value name="F" offset="2" length="4" float="true" conversion="V*2"/></frame>
            <frame id="0x301"><value name="D" offset="0" length="8" double="true"/><value name="Bad" offset="0" length="2" float="true"/></frame>
            </frames></RealDashCAN>"""
        val d = RealDashXml.parse(xml)
        val f = ch(d, 0x300, "F")
        assertEquals(CanChannel.FLOAT32, f.valueType); assertTrue(f.bigEndian); assertEquals(2, f.byte)
        assertEquals(3.0, f.decode(hex("00003fc000000000"))!!, 0.0)
        val dd = ch(d, 0x301, "D")
        assertEquals(CanChannel.FLOAT64, dd.valueType)
        assertEquals(-273.15, dd.decode(hex("66666666661271c0"))!!, 0.0)
        assertNotNull(d.signal(0x301, "Bad")!!.problem)
    }

    @Test fun extendedAndNestedMultiplexing() {
        val m = db.messages.first { it.id == 1280 }
        assertEquals("Mode", m.muxer!!.name)
        val sub = m.signals.first { it.name == "Sub" }
        assertTrue(sub.isMuxer); assertEquals(3, sub.muxValue)
        // one value of byte 0: the simple field, as the Link's compound stream
        val temp = ch(db, 1280, "Temp")
        assertEquals(1, temp.mux); assertEquals(-1, temp.muxStart); assertTrue(temp.muxChain.isEmpty())
        assertEquals(60.0, temp.decode(bytes(1, 0, 100, 0, 0, 0, 0, 0))!!, 1e-9)
        assertNull(temp.decode(bytes(2, 0, 100, 0, 0, 0, 0, 0)))
        assertEquals(100.0, ch(db, 1280, "Press").decode(bytes(2, 0, 0xE8, 0x03, 0, 0, 0, 0))!!, 1e-9)
        // Deep: Mode must be 3 (so that Sub is there) and Sub 5–7
        val deep = ch(db, 1280, "Deep")
        assertEquals(-1, deep.mux)
        assertEquals(2, deep.muxChain.size)
        assertEquals(listOf(3L..3L), deep.muxChain[0].ranges)
        assertEquals(8, deep.muxChain[1].start); assertEquals(4, deep.muxChain[1].len)
        assertEquals(42.0, deep.decode(bytes(3, 0x06, 0, 42, 0, 0, 0, 0))!!, 1e-9)
        assertEquals(42.0, deep.decode(bytes(3, 0xF7, 0, 42, 0, 0, 0, 0))!!, 1e-9)
        assertNull(deep.decode(bytes(3, 0x04, 0, 42, 0, 0, 0, 0)))
        assertNull(deep.decode(bytes(2, 0x06, 0, 42, 0, 0, 0, 0)))
        assertTrue(deep.idText().contains("#3/5–7"))
        assertEquals(deep, CanChannel.fromJson(deep.toJson()))
        // several ranges of one multiplexor
        val ranged = ch(db, 1280, "Ranged")
        assertEquals(1, ranged.muxChain.size)
        assertEquals(9.0, ranged.decode(bytes(10, 0, 0, 0, 9, 0, 0, 0))!!, 1e-9)
        assertEquals(9.0, ranged.decode(bytes(5, 0, 0, 0, 9, 0, 0, 0))!!, 1e-9)
        assertNull(ranged.decode(bytes(7, 0, 0, 0, 9, 0, 0, 0)))
        assertNull(ranged.decode(bytes(3, 0, 0, 0, 9, 0, 0, 0)))
        // a multiplexor the message doesn't have
        assertNotNull(db.signal(1280, "Orphan")!!.problem)
        // a channel saved before has no chain and reads as before
        val old = CanChannel.fromJson(JSONObject("""{"id":"C1","name":"x","can":1004,"mux":2,"byte":2,"len":1}"""))
        assertTrue(old.muxChain.isEmpty())
        assertEquals(7.0, old.decode(bytes(2, 0, 7, 0, 0, 0, 0, 0))!!, 1e-9)
    }

    @Test fun opendbcVwPqMultiplexing() {
        val vw = Dbc.parse(File("src/test/resources/dbc/vw_pq.dbc").readText())
        assertTrue(vw.messages.size > 50)
        // "m" alone is a multiplexor there: 2 bits from bit 6
        val mo2 = vw.messages.first { it.id == 648 }
        assertTrue(mo2.signals.first { it.name == "MO2_Mp_Code" }.isMuxer)
        val maxMo = ch(vw, 648, "MO2_max_Mo")
        assertEquals(3, maxMo.mux); assertEquals(6, maxMo.muxStart); assertEquals(2, maxMo.muxLen)
        assertEquals(200.0, maxMo.decode(bytes(0xD4, 0, 0, 0, 0, 0, 0, 0))!!, 1e-9)
        assertNull(maxMo.decode(bytes(0x94, 0, 0, 0, 0, 0, 0, 0)))
        // a one-bit multiplexor (MSR request) picks how byte 5 is read
        val inv = ch(vw, 416, "BR1_MSR_Mo_inv")
        assertEquals(60.45, inv.decode(bytes(0x02, 0, 0, 0, 0, 100, 0, 0))!!, 1e-9)
        assertNull(inv.decode(bytes(0x00, 0, 0, 0, 0, 100, 0, 0)))
        assertEquals(39.0, ch(vw, 416, "BR1_ASRMo_fa").decode(bytes(0x00, 0, 0, 0, 0, 100, 0, 0))!!, 1e-9)
        assertTrue(vw.messages.all { m -> m.signals.none { it.problem == "мультиплексор не найден" } })
    }

    @Test fun canFdFrames() {
        val r = java.util.Random(3)
        for (size in listOf(12, 16, 20, 24, 32, 48, 64)) {
            val data = ByteArray(size).also { r.nextBytes(it) }
            val hexData = data.joinToString("") { "%02X".format(it) }
            val code = "%X".format(CanFrame.lenToDlc(size))
            val f = Slcan.parse("d123$code$hexData")!!
            assertEquals(0x123, f.id); assertFalse(f.ext)
            assertArrayEquals(data, f.data)
            // the one slcan parser (Slcan.parse, CanFrame's DLC table) marks them FD, "b"/"B" with the bit rate switch
            assertTrue(f.fd); assertFalse(f.brs); assertEquals(size, f.len)
            val e = Slcan.parse("B18FEF100$code${hexData}1A2B")!!
            assertTrue(e.ext); assertEquals(0x18FEF100, e.id); assertArrayEquals(data, e.data)
            assertTrue(e.fd); assertTrue(e.brs)
            // and back: the line the adapter is sent is the line it gave
            assertEquals("d123$code$hexData", Slcan.format(f))
            // the last two bytes, both models, both orders
            val last = CanChannel("C1", "", 0x123, false, -1, size - 2, 2, true, false, "x", "", 0, "")
            val want = ((data[size - 2].toInt() and 0xFF) shl 8 or (data[size - 1].toInt() and 0xFF)).toDouble()
            assertEquals(want, last.decode(data)!!, 0.0)
            assertEquals(want, last.copy(startBit = (size - 2) * 8 + 7, bitLen = 16).decode(data)!!, 0.0)
            assertEquals(last, CanChannel.fromJson(last.toJson()))
            // a byte past the frame
            assertNull(last.copy(byte = size - 1).decode(data))
        }
        assertNull(Slcan.parse("d1239AABB")) // 12 bytes promised, 2 given
        // bit 500–507 of a 64-byte frame: byte 62's high nibble and byte 63's low one
        val d = ByteArray(64).also { it[62] = 0xA0.toByte(); it[63] = 0x0B }
        val c = CanChannel("C1", "", 0x123, false, -1, 0, 1, false, false, "x", "", 0, "", startBit = 500, bitLen = 8)
        assertEquals(0xBA.toDouble(), c.decode(d)!!, 0.0)
        assertNull(c.decode(ByteArray(48)))
        // a DBC's FD message, a multiplexor at byte 40
        val fd = Dbc.parse("""
            BO_ 1300 FD_MSG: 48 X
             SG_ Page M : 320|8@1+ (1,0) [0|255] "" X
             SG_ Far m2 : 376|16@1+ (0.5,0) [0|0] "" X
        """.trimIndent())
        val far = ch(fd, 1300, "Far")
        assertEquals(47, far.byte); assertEquals(320, far.muxStart)
        val frame = ByteArray(48).also { it[40] = 2; it[47] = 10 }
        assertNull(far.decode(frame)) // byte 48 isn't there: 47–48 runs past a 48-byte frame
        val far2 = CanImport.channel(fd.messages[0], fd.messages[0].signals.first { it.name == "Far" }.copy(startBit = 368), "C2")
        assertEquals(5.0, far2.decode(ByteArray(48).also { it[40] = 2; it[46] = 10 })!!, 0.0)
        assertNull(far2.decode(ByteArray(48).also { it[40] = 1; it[46] = 10 }))
    }
}
