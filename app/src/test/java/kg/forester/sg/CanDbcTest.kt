package kg.forester.sg

import kg.forester.sg.data.CanBits
import kg.forester.sg.data.CanChannel
import kg.forester.sg.ssm.CanImport
import kg.forester.sg.ssm.Dbc
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bit-level signals and the DBC reader, against opendbc's files (MIT, test/resources/dbc/LICENSE-opendbc.txt). */
class CanDbcTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun dbc(name: String) = Dbc.parse(File("src/test/resources/dbc/$name").readText())

    @Test fun intelAndMotorolaFields() {
        // Intel 16 bits from bit 0: low byte first
        assertEquals(0x1234L, CanBits.get(bytes(0x34, 0x12), 0, 16, false, false))
        // Motorola 16 bits from bit 7 (byte 0's top bit): high byte first
        assertEquals(0x1234L, CanBits.get(bytes(0x12, 0x34), 7, 16, true, false))
        // 12 bits across a byte boundary both ways
        assertEquals(0xABCL, CanBits.get(bytes(0xAB, 0xC0), 7, 12, true, false))
        assertEquals(0xABCL, CanBits.get(bytes(0xC0, 0xAB), 4, 12, false, false))
        // a lone bit and a signed nibble
        assertEquals(1L, CanBits.get(bytes(0, 0x80), 15, 1, false, false))
        assertEquals(-2L, CanBits.get(bytes(0xE0), 4, 4, false, true))
        // past the frame
        assertNull(CanBits.get(bytes(0x12), 7, 16, true, false))
        assertNull(CanBits.get(bytes(0x12, 0x34), 8, 16, false, false))
        assertEquals(0..1, CanBits.byteSpan(7, 16, true))
        assertEquals(1..2, CanBits.byteSpan(12, 8, false))
    }

    @Test fun theByteModelAndItsBitTwinReadAlike() {
        val r = java.util.Random(7)
        repeat(200) {
            val d = ByteArray(8).also { r.nextBytes(it) }
            for (len in listOf(1, 2, 4)) for (byte in 0..8 - len) for (be in listOf(false, true)) for (signed in listOf(false, true)) {
                val old = CanChannel("C1", "", 0x100, false, -1, byte, len, be, signed, "x", "", 0, "")
                val start = if (be) byte * 8 + 7 else byte * 8
                val bit = old.copy(startBit = start, bitLen = len * 8)
                assertEquals("byte $byte len $len be $be", old.raw(d), bit.raw(d))
            }
        }
    }

    @Test fun opendbcTestFile() {
        val db = dbc("test.dbc")
        val steer = db.messages.first { it.id == 228 }
        assertEquals(5, steer.size)
        val torque = steer.signals.first { it.name == "STEER_TORQUE" }
        assertTrue(torque.motorola && torque.signed)
        val c = CanImport.channel(steer, torque, "C1")
        // −1000 as 16 bits, high byte first, from byte 0
        assertEquals(-1000.0, c.decode(bytes(0xFC, 0x18, 0, 0, 0))!!, 1e-9)
        val fd = db.messages.first { it.id == 245 }
        assertEquals(32, fd.size)
        val le = CanImport.channel(fd, fd.signals.first { it.name == "64_BIT_LE" }, "C2")
        val be = CanImport.channel(fd, fd.signals.first { it.name == "64_BIT_BE" }, "C3")
        // Intel 159|64: the lowest bit is byte 19's top bit, the highest bit 222 (byte 27, bit 6)
        assertEquals(1L, le.raw(ByteArray(32).also { it[19] = 0x80.toByte() }))
        assertEquals(Long.MIN_VALUE, le.raw(ByteArray(32).also { it[27] = 0x40 }))
        // Motorola 80|64: the highest bit is byte 10's bit 0, then bytes 11–17 whole, the lowest is byte 18's bit 1
        assertEquals(Long.MIN_VALUE, be.raw(ByteArray(32).also { it[10] = 1 }))
        assertEquals(1L, be.raw(ByteArray(32).also { it[18] = 2 }))
        assertEquals(0xFFL shl 55, be.raw(ByteArray(32).also { it[11] = 0xFF.toByte() }))
        // unsigned 64 bits: past Long's range, not negative
        assertEquals(9.223372036854776E18, be.decode(ByteArray(32).also { it[10] = 1 })!!, 1e3)
        // a DBC file needs messages
        assertEquals(null, runCatching { Dbc.parse("VERSION \"\"\n") }.getOrNull())
    }

    @Test fun mazdaRx8MotorolaSignals() {
        val db = dbc("mazda_rx8.dbc")
        assertEquals(7, db.messages.size)
        val speed = db.messages.first { it.id == 513 }
        val rpm = CanImport.channel(speed, speed.signals.first { it.name == "EngineRPM" }, "C1")
        // 7|16@0+ is bytes 0–1 high first: the byte model, which the form shows
        assertEquals(-1, rpm.startBit)
        assertEquals(0, rpm.byte); assertEquals(2, rpm.len); assertTrue(rpm.bigEndian)
        assertEquals("x*0.25", rpm.formula)
        assertEquals("об/мин", rpm.units)
        val f = bytes(0x2E, 0xE0, 0, 0, 0x2E, 0xE0, 0x50, 0)
        assertEquals(3000.0, rpm.decode(f)!!, 1e-9)
        val vss = CanImport.channel(speed, speed.signals.first { it.name == "VehicleSpeed" }, "C2")
        assertEquals("x*0.01-100", vss.formula)
        assertEquals("км/ч", vss.units)
        assertEquals(20.0, vss.decode(f)!!, 1e-9)
        assertEquals(40.0, CanImport.channel(speed, speed.signals.first { it.name == "AcceleratorPos" }, "C3").decode(f)!!, 1e-9)
        val iat = db.messages.first { it.id == 592 }.let { m -> CanImport.channel(m, m.signals.first { it.name == "IntakeAirTemperature" }, "C4") }
        assertEquals("°C", iat.units)
        assertEquals(25.0, iat.decode(bytes(0, 0, 0, 65, 0, 0, 0, 0))!!, 1e-9)
        val brake = db.messages.first { it.id == 658 }.let { m -> CanImport.channel(m, m.signals.first { it.name == "BrakePedalSwitch" }, "C5") }
        assertEquals(43, brake.startBit); assertEquals(1, brake.bitLen)
        assertEquals(1.0, brake.decode(bytes(0, 0, 0, 0, 0, 0x08, 0, 0))!!, 1e-9)
        assertEquals(0.0, brake.decode(bytes(0, 0, 0, 0, 0, 0xF7, 0, 0))!!, 1e-9)
        assertEquals("Steering wheel angle: positive is right and negative is left", db.signal(129, "SteeringAngle")!!.comment)
    }

    @Test fun teslaMultiplexedSignals() {
        val db = dbc("tesla_model3_party.dbc")
        val m = db.messages.first { it.id == 545 }
        val muxer = m.muxer!!
        assertEquals(0, muxer.startBit); assertEquals(5, muxer.bitLen)
        val pcs = m.signals.first { it.name == "VCFRONT_pcsLVState" }
        assertEquals(1, pcs.muxValue)
        assertEquals(mapOf(0L to "LV_OFF", 1L to "LV_ON", 2L to "LV_GOING_DOWN", 3L to "LV_FAULT"), db.signal(545, "VCFRONT_uiAudioLVState")!!.values)
        val c = CanImport.channel(m, pcs, "C1")
        // the multiplexor is 5 bits from bit 0, not byte 0: it keeps its place
        assertEquals(1, c.mux); assertEquals(0, c.muxStart); assertEquals(5, c.muxLen)
        assertEquals(2.0, c.decode(bytes(0x01, 0, 0x02, 0, 0, 0, 0, 0))!!, 1e-9)
        // frame 0 of the compound message: not this signal's
        assertNull(c.decode(bytes(0x00, 0, 0x02, 0, 0, 0, 0, 0)))
        // bits 5–7 of byte 0 belong to nobody's index
        assertEquals(3.0, c.decode(bytes(0xE1, 0, 0x03, 0, 0, 0, 0, 0))!!, 1e-9)
        // the JSON keeps it
        val back = CanChannel.fromJson(c.toJson())
        assertEquals(c, back)
    }

    @Test fun commentsAttributesValuesAndFloats() {
        val text = """
            VERSION ""
            BS_:
            BU_: ECU
            BO_ 2364540158 EEC1: 8 ECU
             SG_ EngineSpeed : 24|16@1+ (0.125,0) [0|8031.875] "rpm" Vector__XXX
             SG_ Torque : 16|8@1+ (1,-125) [-125|125] "%" Vector__XXX
            BO_ 1024 MISC: 8 ECU
             SG_ OilPress : 0|16@1+ (0.1,0) [0|1000] "kPa" Vector__XXX
             SG_ Ratio : 16|32@1+ (1,0) [0|0] "" Vector__XXX
             SG_ ExhaustGasTemp : 48|16@1- (1,0) [0|1200] "degC" Vector__XXX
            CM_ BO_ 1024 "Misc values";
            CM_ SG_ 1024 OilPress "Oil pressure,
            gauge, after the filter with \"quotes\"";
            BA_DEF_ BO_ "GenMsgCycleTime" INT 0 10000;
            BA_ "GenMsgCycleTime" BO_ 1024 100;
            BA_ "Baudrate" 250000;
            VAL_ 2364540158 Torque 0 "none" 250 "full" ;
            SIG_VALTYPE_ 1024 Ratio : 1;
        """.trimIndent()
        val db = Dbc.parse(text)
        assertEquals(250, db.kbit)
        val eec = db.messages.first { it.name == "EEC1" }
        assertTrue(eec.ext)
        assertEquals(0x0CF004FE, eec.id)
        val misc = db.messages.first { it.id == 1024 }
        assertEquals(100, misc.cycleMs)
        assertEquals("Misc values", misc.comment)
        val oil = misc.signals.first { it.name == "OilPress" }
        assertEquals("Oil pressure,\ngauge, after the filter with \"quotes\"", oil.comment)
        assertEquals(mapOf(0L to "none", 250L to "full"), eec.signals.first { it.name == "Torque" }.values)
        assertNotNull(misc.signals.first { it.name == "Ratio" }.problem)
        // roles where they are plain
        assertEquals("oilP", CanImport.channel(misc, oil, "C1").role)
        assertEquals("egt", CanImport.channel(misc, misc.signals.first { it.name == "ExhaustGasTemp" }, "C2").role)
        assertEquals("", CanImport.channel(eec, eec.signals.first { it.name == "EngineSpeed" }, "C3").role)
        val rpm = CanImport.channel(eec, eec.signals.first { it.name == "EngineSpeed" }, "C3")
        assertTrue(rpm.ext)
        assertEquals(1000.0, rpm.decode(bytes(0, 0, 0, 0x40, 0x1F, 0, 0, 0))!!, 1e-9)
    }

    @Test fun savedChannelsOfTheOldFormatStillLoad() {
        // as 1.0-beta8 writes it
        val old = JSONObject("""{"id":"C1","name":"ЕГТ","can":1004,"ext":false,"mux":-1,"byte":0,"len":2,"be":true,"signed":false,"formula":"x","units":"°C","dec":0,"role":"egt"}""")
        val c = CanChannel.fromJson(old)
        assertEquals(-1, c.startBit); assertEquals(-1, c.muxStart)
        assertEquals(850.0, c.decode(bytes(0x03, 0x52, 0, 0, 0, 0, 0, 0))!!, 1e-9)
        // and a byte channel is written as before: no new keys
        assertTrue(!c.toJson().has("sb") && !c.toJson().has("msb"))
        val bits = c.copy(startBit = 12, bitLen = 4, bigEndian = false)
        assertEquals(bits, CanChannel.fromJson(bits.toJson()))
        assertEquals(", биты 12|4@1", bits.idText().substringAfter("3EC"))
    }

    @Test fun formulasOfDbcNumbers() {
        assertEquals("x", CanImport.linear(1.0, 0.0))
        assertEquals("x*0.03333333", CanImport.linear(0.03333333, 0.0))
        assertEquals("x*0.1-273.15", CanImport.linear(0.1, -273.15))
        assertEquals("x*5+10", CanImport.linear(5.0, 10.0))
        assertEquals("x*0.00001", CanImport.linear(1e-5, 0.0))
        assertEquals(1, CanImport.decimals("x*0.1"))
        assertEquals(0, CanImport.decimals("x-40"))
        assertEquals(3, CanImport.decimals("x*0.0078125"))
        assertEquals("°C" to "(x*0.1)-273.15", CanImport.normalize("K", "x*0.1"))
        assertEquals(30.0, kg.forester.sg.ssm.RrExpr.parse(CanImport.normalize("F", "x/10").second)!!.eval(860.0) { _, _ -> null }!!, 1e-9)
    }
}
