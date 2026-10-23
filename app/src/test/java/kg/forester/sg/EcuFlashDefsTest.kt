package kg.forester.sg

import kg.forester.sg.ssm.EcuFlashDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuFlashDefsTest {
    /** A ROM's own file as SubaruDefs has them: the addresses here, the size and scaling left to 32BITBASE. */
    private val child = """<?xml version="1.0" encoding="UTF-8"?>
<rom>
  <romid>
    <xmlid>A2WC522N</xmlid>
    <internalidaddress>2000</internalidaddress>
    <internalidstring>A2WC522N</internalidstring>
    <ecuid>2F12785206</ecuid>
  </romid>
  <include>32BITBASE</include>
  <table name="A/F Learning #1 Airflow Ranges" address="d6a8c" />
  <table name="Fine Correction Rows (RPM)" address="d9f40" />
  <table name="Fine Correction Columns (Load)" address="d9f58" elements="4" />
  <table name="Primary Open Loop Fueling" address="c0000">
    <table name="Fine Correction Rows (RPM)" address="11111" />
  </table>
</rom>"""

    @Test
    fun axesOfAChildDefinition() {
        assertTrue(EcuFlashDefs.looksLike(child))
        val r = EcuFlashDefs.parse(child)!!
        assertEquals("2F12785206", r.ecuId)
        assertEquals("A2WC522N", r.cal)
        assertEquals(32, r.bits)
        assertEquals(0xd6a8c, r.af!!.addr)
        // the axis of another table inside it is not the learning one
        assertEquals(0xd9f40, r.rpm!!.addr)
        assertNull(r.rpm!!.count)
        assertEquals(4, r.load!!.count)
        assertNull(r.load!!.storage)
    }

    @Test
    fun aFullDefinitionWithItsScaling() {
        val full = """<roms><rom><romid><xmlid>A4SD501A</xmlid><ecuid>1b04400405</ecuid></romid>
            <scaling name="RPM" storagetype="uint16" toexpr="x*0.1953125" />
            <table name="Fine Correction Rows (RPM)" address="2A64E" elements="7" scaling="RPM" /></rom></roms>"""
        val r = EcuFlashDefs.parse(full)!!
        assertEquals("1B04400405", r.ecuId)
        assertEquals(0, r.bits)
        assertEquals(EcuFlashDefs.Axis(0x2A64E, 7, "uint16", 0.1953125), r.rpm)
    }

    @Test
    fun notADefinition() {
        assertFalse(EcuFlashDefs.looksLike("<logger version=\"370\"><protocols/></logger>"))
        assertNull(EcuFlashDefs.parse("<rom><romid><xmlid>X</xmlid></romid></rom>"))
    }

    @Test
    fun anAxisWithoutItsCountEndsWhereItStopsRising() {
        // six RPM limits, then the table's first cells
        assertEquals(6, EcuFlashDefs.risingRun(listOf(2000.0, 2800.0, 3600.0, 4400.0, 5200.0, 6000.0, 0.5, 0.0), 0.0, 12000.0))
        assertNull(EcuFlashDefs.risingRun(listOf(Double.NaN, 1.0), 0.0, 10.0))
    }
}
