package kg.forester.sg

import kg.forester.sg.ssm.SsmP1Client
import kg.forester.sg.ssm.SsmP1Defs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pre-1999 units over SSM1: the base, FreeSSM's formulas, the ROM id from the wire. */
class Ssm1Test {
    private val base = SsmP1Defs.Base(File("src/main/assets/ssm1_engine.json").readText())

    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    @Test fun formulasGoLeftToRightAsFreeSsm() {
        assertEquals(12.8, SsmP1Defs.scale(160.0, "*8/100")!!, 1e-9)
        assertEquals(2500.0, SsmP1Defs.scale(100.0, "*25")!!, 1e-9)
        assertEquals(40.0, SsmP1Defs.scale(90.0, "-50")!!, 1e-9)
        // (x − 128) × 100 / 128: the mixture correction around 0
        assertEquals(0.0, SsmP1Defs.scale(128.0, "-128*100/128")!!, 1e-9)
        assertEquals(-25.0, SsmP1Defs.scale(96.0, "-128*100/128")!!, 1e-9)
        // a signed operand, then left to right: ((x × −64) / 450 + 45) / 10
        assertEquals(4.5, SsmP1Defs.scale(0.0, "*-64/450+45/10")!!, 1e-9)
        assertNull(SsmP1Defs.scale(1.0, ""))
        assertNull(SsmP1Defs.scale(1.0, "x2"))
        assertNull(SsmP1Defs.scale(1.0, "/0"))
    }

    @Test fun theBaseHasTheNinetiesUnits() {
        assertTrue(base.roms.size >= 20)
        val gc8 = base.find("A30113")!!
        assertTrue(gc8.exact)
        // the map P1kachu read on a JDM GC8 STi 1997 and the ini gives for the Impreza GT
        assertEquals(0x0009, gc8.rom.mbs["MB_EREV"])
        assertEquals(0x0008, gc8.rom.mbs["MB_VSP"])
        assertEquals(0x000A, gc8.rom.mbs["MB_TW"])
        assertEquals(0x0007, gc8.rom.mbs["MB_VB"])
        // an unlisted unit of the same family takes the family's map, marked as not exact
        val sf5 = base.find("A30115")!!
        assertFalse(sf5.exact)
        assertEquals(0x0009, sf5.rom.mbs["MB_EREV"])
        assertNull(base.find("123456"))
        assertNull(base.find("A3"))
    }

    @Test fun legacyTurboHasItsCodes() {
        val m = base.find("723425")!!
        assertTrue(m.exact)
        assertTrue(m.rom.dc.isNotEmpty())
        val ids = m.rom.dc.flatMap { it.bits.values }
        // FreeSSM has no text for a few (DTC_49_SYS_ERR): they still carry their number
        assertTrue(ids.all { it in base.dtcs || Regex("DTC_\\d\\d.*").matches(it) })
        assertTrue(base.dtcs.values.any { it.code == "11" })
        assertNotNull(m.rom.clear)
    }

    @Test fun everyBlockOfEveryUnitScales() {
        for (r in base.roms) for ((mb, _) in base.blocks(r)) {
            assertNotNull("${mb.key} of ${r.name}", mb.value(128))
            assertTrue(mb.ru.isNotBlank())
        }
    }

    @Test fun theSnapshotTakesOnlyItsOwnUnits() {
        val s = SsmP1Defs.snapshot(mapOf("MB_EREV" to 2500.0, "MB_TW" to 85.0, "MB_QA" to 1.9, "MB_O2_R" to 0.45, "MB_ALPHA_R" to -3.0), 0)
        assertEquals(2500, s.rpm)
        assertEquals(85, s.coolantC)
        assertEquals(-3.0f, s.afCorrPct!!, 1e-6f)
        // a voltage is no g/s and no lambda
        assertNull(s.mafGs)
        assertNull(s.lambda)
        assertNull(s.throttlePct)
    }

    @Test fun theRomIdComesOffTheWire() {
        // a 7x id streams over and over: confirmed by its repeat, after stray bytes of the last stream
        val a = SsmP1Client.extractId(b(0x13, 0x09, 0x55, 0x72, 0x34, 0x25, 0x72, 0x34, 0x25))!!
        assertArrayEquals(b(0x72, 0x34, 0x25), a.first)
        assertTrue(a.second)
        assertNull(SsmP1Client.extractId(b(0x72, 0x34, 0x25)))
        // the 1997–98 units: Ax 01 zz once, then data
        val c = SsmP1Client.extractId(b(0x00, 0xA3, 0x01, 0x13, 0x5A, 0x00))!!
        assertArrayEquals(b(0xA3, 0x01, 0x13), c.first)
        assertFalse(c.second)
        // an A-byte that is not an id head
        assertNull(SsmP1Client.extractId(b(0xA3, 0x55, 0x13, 0x00)))
    }
}
