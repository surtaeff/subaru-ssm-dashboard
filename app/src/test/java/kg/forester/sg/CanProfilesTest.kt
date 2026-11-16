package kg.forester.sg

import kg.forester.sg.data.CanChannel
import kg.forester.sg.data.CanProfiles
import kg.forester.sg.ssm.CanDb
import kg.forester.sg.ssm.CanImport
import kg.forester.sg.ssm.RealDashXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** RealDash XML (janimm/RealDash-extras, public domain) and the built-in profiles of assets/can_profiles. */
class CanProfilesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun asset(name: String) = File("src/main/assets/can_profiles/$name").readText()
    private fun fixture(name: String) = File("src/test/resources/dbc/$name").readText()

    private fun ch(db: CanDb, id: Int, name: String): CanChannel {
        val m = db.messages.first { it.id == id && it.signals.any { s -> s.name == name } }
        return CanImport.channel(m, m.signals.first { it.name == name }, "C1")
    }

    @Test fun conversions() {
        assertEquals("x/10", RealDashXml.conversion("V/10")!!.formula)
        assertEquals("x*0.1 - 101.3", RealDashXml.conversion("V*0.1 - 101.3")!!.formula)
        assertEquals("(B0*256+B1)/10", RealDashXml.conversion("(B0*256+B1)/10")!!.formula)
        assertEquals("B0+15*(B1-43)", RealDashXml.conversion("A+15*(B-43)", abc = true)!!.formula)
        assertEquals("x*64", RealDashXml.conversion("V << 6")!!.formula)
        assertEquals("x/1.59375-10", RealDashXml.conversion("V/‭1.59375‬-10")!!.formula)
        RealDashXml.conversion("(V>>4)&3")!!.let { assertEquals(4, it.shift); assertEquals(2, it.width) }
        RealDashXml.conversion("V & 15")!!.let { assertEquals(0, it.shift); assertEquals(4, it.width) }
        RealDashXml.conversion("V>>7")!!.let { assertEquals(7, it.shift); assertNull(it.width) }
        // what a formula here can't do
        assertNull(RealDashXml.conversion("V+ID200-74.3"))
        assertNull(RealDashXml.conversion("placeholder"))
        assertNull(RealDashXml.conversion("V0.5"))
        assertNull(RealDashXml.conversion("V & 6"))
        assertNull(RealDashXml.conversion("max(V,10)"))
    }

    @Test fun maxxEcuFile() {
        val db = RealDashXml.parse(asset("maxxecu_default_can.xml"))
        assertEquals(0x520, db.messages.first().id)
        val oil = ch(db, 0x536, "Oil pressure")
        assertEquals("бар", oil.units)
        assertEquals("oilP", oil.role)
        assertEquals(4, oil.byte); assertEquals(2, oil.len); assertFalse(oil.bigEndian)
        // 3.5 bar = 3500 low byte first
        assertEquals(3.5, oil.decode(bytes(0, 0, 0, 0, 0xAC, 0x0D, 0, 0))!!, 1e-9)
        assertEquals("egt", ch(db, 0x531, "EGT 1").role)
        assertEquals("eth", ch(db, 0x531, "Ethanol concentration").role)
        // a bit of a flags byte: V>>3 with units "bit"
        val launch = ch(db, 0x526, "Launch control active")
        assertEquals(3, launch.startBit); assertEquals(1, launch.bitLen)
        assertEquals(1.0, launch.decode(bytes(0x08, 0, 0, 0, 0, 0, 0, 0))!!, 1e-9)
        assertEquals(0.0, launch.decode(bytes(0xF7, 0, 0, 0, 0, 0, 0, 0))!!, 1e-9)
        // signed, 0.1 °C
        assertEquals(-12.5, ch(db, 0x530, "Intake air temperature").decode(bytes(0, 0, 0, 0, 0x83, 0xFF, 0, 0))!!, 1e-9)
    }

    @Test fun haltechFileBigEndianAndKelvin() {
        val db = RealDashXml.parse(asset("haltech_v3_can.xml"))
        val oilT = ch(db, 0x3E0, "Oil temperature")
        assertEquals("°C", oilT.units)
        assertEquals("oil", oilT.role)
        assertTrue(oilT.bigEndian)
        // 363.15 K × 10 = 3631.5 → 0x0E2F (3631) → 89.95 °C
        assertEquals(89.95, oilT.decode(bytes(0, 0, 0, 0, 0, 0, 0x0E, 0x2F))!!, 1e-6)
        val oilP = ch(db, 0x361, "Oil pressure")
        assertEquals("кПа", oilP.units)
        assertEquals("oilP", oilP.role)
        // gauge pressure: 401.3 kPa absolute (4013) − 101.3
        assertEquals(300.0, oilP.decode(bytes(0, 0, 0x0F, 0xAD, 0, 0, 0, 0))!!, 1e-6)
        // startbit/bitcount from the offset, in a big-endian frame
        val limiting = ch(db, 0x36E, "Engine Limiting Active")
        assertEquals(8, limiting.startBit)
        assertEquals(1.0, limiting.decode(bytes(0, 1, 0, 0, 0, 0, 0, 0))!!, 1e-9)
        val method = ch(db, 0x477, "Engine Limiter Method")
        assertEquals(60, method.startBit); assertEquals(4, method.bitLen)
        assertEquals(2.0, method.decode(bytes(0, 0, 0, 0, 0, 0, 0, 0x21))!!, 1e-9)
        assertEquals(mapOf(0L to "None", 1L to "Fuel", 2L to "Ignition", 3L to "Fuel+Ignition"), db.signal(0x477, "Engine Limiter Method")!!.values)
        // the IO box frames are the dash's own output
        assertNotNull(db.messages.first { it.id == 0x2C0 }.signals.first().problem)
    }

    @Test fun ecumasterAndFlagtronicsFiles() {
        val db = RealDashXml.parse(fixture("ecumaster_emublack.xml"))
        // V0.5: a typo in the file, not a formula
        assertNotNull(db.signal(0x603, "Ignition advance")!!.problem)
        assertNull(db.signal(0x603, "Lambda 1")!!.problem)
        // startbit 7 with units bit: the brake switch
        val brake = ch(db, 0x604, "Brake pedal")
        assertEquals(55, brake.startBit)
        assertEquals(100.0, brake.decode(bytes(0, 0, 0, 0, 0, 0, 0x80, 0))!!, 1e-9)
        val fl = RealDashXml.parse(fixture("flagtronics_can_v0_4.xml"))
        val g = ch(fl, 0x4DC, "Accident Gforce")
        assertEquals(57, g.startBit); assertEquals(7, g.bitLen)
        assertEquals(0x55.toDouble(), g.decode(bytes(0, 0, 0, 0, 0, 0, 0, 0xAA))!!, 1e-9)
        // a bad file says so
        assertNull(runCatching { RealDashXml.parse("<foo/>") }.getOrNull())
        assertNull(runCatching { RealDashXml.parse("<RealDashCAN version=\"2\"><frames><frame id=") }.getOrNull())
    }

    @Test fun bytesOfTheValueInAFormula() {
        val xml = """<?xml version="1.0"?><RealDashCAN version="2"><frames baseId="0x700">
            <frame id="0x10:7,0,1"><value name="Test: Pair" offset="2" length="2" conversion="(B0*256+B1)/10"/></frame>
            <frame id="0x11" endianness="big"><value name="Nibble" offset="4" length="2" conversion="(V>>4)&amp;15"/></frame>
            </frames></RealDashCAN>"""
        val db = RealDashXml.parse(xml)
        val pair = ch(db, 0x710, "Pair")
        assertEquals(7, pair.mux)
        assertEquals(123.4, pair.decode(bytes(7, 0, 0x04, 0xD2, 0, 0, 0, 0))!!, 1e-9)
        assertNull(pair.decode(bytes(6, 0, 0x04, 0xD2, 0, 0, 0, 0)))
        // bits 4–7 of a big-endian value at bytes 4–5: bits 4–7 of byte 5
        val n = ch(db, 0x711, "Nibble")
        assertTrue(n.bigEndian)
        assertEquals(0xB.toDouble(), n.decode(bytes(0, 0, 0, 0, 0x12, 0xB3, 0, 0))!!, 1e-9)
    }

    @Test fun everyProfileResolves() {
        for (p in CanProfiles.all) {
            if (p.template != null) { assertNotNull(p.template.invoke("C1").role); continue }
            val a = p.asset ?: continue
            val db = CanImport.parse(asset(a))
            assertTrue(p.key, db.signalCount > 0)
            for (pick in p.picks) {
                val c = CanProfiles.channel(db, pick, "C1")
                assertNotNull("${p.key}: ${pick.signal} of ${"%X".format(pick.msg)}", c)
                if (pick.role != null) assertEquals("${p.key}: ${pick.signal}", pick.role, c!!.role)
                assertNotNull(kg.forester.sg.ssm.RrExpr.parse(c!!.formula))
            }
        }
    }

    @Test fun profileValuesFromSampleFrames() {
        fun value(key: String, signal: String, d: ByteArray, afr: Boolean = false): Double {
            val p = CanProfiles.byKey(key)!!
            val db = CanImport.parse(asset(p.asset!!))
            val pick = p.picks.first { it.signal == signal && it.afr == afr }
            return CanProfiles.channel(db, pick, "C1")!!.decode(d)!!
        }
        // AEM: lambda 1.0000 high byte first (10000 = 0x2710) on 29-bit 0x180
        val aem = bytes(0x27, 0x10, 0, 0, 140, 0, 0x80, 0)
        assertEquals(14.7, value("aem_x", "Lambda", aem, afr = true), 1e-9)
        assertEquals(1.0, value("aem_x", "Lambda", aem), 1e-9)
        assertEquals(1.0, value("aem_x", "LambdaValid", aem), 1e-9)
        val aemDb = CanImport.parse(asset("aem_xseries_wideband.dbc"))
        assertTrue(aemDb.messages.single().ext)
        assertEquals(500, aemDb.kbit)
        // rusEFI wideband: lambda 0.85 low byte first (8500 = 0x2134)
        assertEquals(0.85, value("rusefi_wbo", "Lambda", bytes(0xA0, 1, 0x34, 0x21, 0, 0, 0, 0)), 1e-9)
        // EMU: oil 4.5 bar = 72 × 0.0625, EGT1 850 °C low byte first
        assertEquals(4.5, value("emu_black", "OilPressure", bytes(0, 0, 0, 90, 72, 0, 0, 0)), 1e-9)
        assertEquals(850.0, value("emu_black", "EGT1", bytes(0, 0, 0, 0, 0x52, 0x03, 0, 0)), 1e-9)
        assertEquals(14.7, value("emu_black", "Lambda", bytes(0, 0, 128, 0, 0, 0, 0, 0), afr = true), 1e-9)
        // rusEFI: EGT 5 °C a step; oil 300 kPa = 9000 × 1/30
        assertEquals(800.0, value("rusefi", "EGT1", bytes(160, 0, 0, 0, 0, 0, 0, 0)), 1e-9)
        assertEquals(300.0, value("rusefi", "OilPressure", bytes(0, 0, 0x28, 0x23, 0, 0, 0, 0)), 0.01)
        // Megasquirt: 180.0 °F → 82.2 °C, high byte first
        assertEquals(82.222, value("megasquirt", "Coolant temperature", bytes(0, 0, 0, 0, 0, 0, 0x07, 0x08)), 0.001)
    }

    @Test fun addingAProfileTwiceDoublesNothing() {
        val p = CanProfiles.byKey("maxxecu")!!
        val db = CanImport.parse(asset(p.asset!!))
        val first = p.picks.mapIndexedNotNull { i, pick -> CanProfiles.channel(db, pick, "C${i + 1}") }
        val again = p.picks.mapIndexedNotNull { i, pick -> CanProfiles.channel(db, pick, "C${i + 20}") }
        assertTrue(again.all { CanProfiles.duplicate(it, first) })
        assertFalse(CanProfiles.duplicate(first[0].copy(byte = 0), first.drop(1)))
    }
}
