package kg.forester.sg

import kg.forester.sg.ssm.RomRaider
import kg.forester.sg.ssm.RomRaiderXml
import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RomRaiderXmlTest {
    /** A cut-down logger.xml as RomRaider ships it: BOM, CRLF, a DOCTYPE with its own DTD, another protocol before SSM. */
    private val logger = ("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<!-- ROMRAIDER METRIC UNITS LOGGER DEFINITION FILE -->\n" +
        "<!DOCTYPE logger [\n<!ELEMENT address ( #PCDATA ) >\n<!ATTLIST address length CDATA #IMPLIED >\n]>\n" +
        "<logger version=\"370\">\n<protocols>\n" +
        "<protocol id=\"OBD\" baud=\"500000\"><parameters>\n" +
        "<parameter id=\"P8\" name=\"Engine Speed\" desc=\"P8\" target=\"1\"><address>0x0C</address>" +
        "<conversions><conversion units=\"RPM\" expr=\"x/4\" format=\"0\"/></conversions></parameter>\n" +
        "</parameters></protocol>\n" +
        "<protocol id=\"SSM\" baud=\"4800\">\n" +
        "<transports><transport id=\"iso9141\"><module id=\"ecu\" address=\"0x10\"/></transport></transports>\n" +
        "<parameters>\n" +
        "<parameter id=\"P7\" name=\"Manifold Absolute Pressure\" desc=\"P7\" ecubyteindex=\"8\" ecubit=\"1\" target=\"1\">\n" +
        "  <address>0x00000D</address>\n" +
        "  <conversions>\n" +
        "    <conversion units=\"psi\" expr=\"x*37/255\" format=\"0.00\"/>\n" +
        "    <conversion units=\"kPa\" expr=\"x\" format=\"0\" gauge_min=\"0\" gauge_max=\"300\"/>\n" +
        "  </conversions>\n" +
        "</parameter>\n" +
        "<parameter id=\"P8\" name=\"Engine Speed\" desc=\"P8\" ecubyteindex=\"8\" ecubit=\"0\" target=\"3\">" +
        "<address length=\"2\">14</address><conversions><conversion units=\"rpm\" expr=\"x/4\" format=\"0\"/></conversions></parameter>\n" +
        "<parameter id=\"P200\" name=\"Engine Load (Calculated)\" desc=\"P200-Engine load as calculated from MAF and RPM.\" target=\"1\">\n" +
        "  <depends><ref parameter=\"P8\"/><ref parameter=\"P12\"/></depends>\n" +
        "  <conversions><conversion units=\"g/rev\" expr=\"(P12*60)/P8\" format=\"0.00\"/></conversions>\n" +
        "</parameter>\n" +
        "</parameters>\n" +
        "<switches>\n<switch id=\"S20\" name=\"Clutch Switch\" desc=\"S20\" byte=\"0x000063\" bit=\"3\" ecubyteindex=\"21\" target=\"1\"/>\n</switches>\n" +
        "<ecuparams>\n" +
        "<ecuparam id=\"E51\" name=\"Manifold Absolute Pressure (4-byte)*\" desc=\"E51-MAP is limited to about 37 psi.\" target=\"1\">\n" +
        "  <ecu id=\"4B04505207, 3c04784316\"><address length=\"4\">0xFF6A30</address></ecu>\n" +
        "  <ecu id=\"AAAAAAAAAA\"><address length=\"4\">16739000</address></ecu>\n" +
        "  <conversions>\n" +
        "    <conversion units=\"psi\" expr=\"x*0.145037738\" format=\"0.00\" storagetype=\"float\"/>\n" +
        "    <conversion units=\"kPa absolute\" expr=\"x\" format=\"0.0\" storagetype=\"float\"/>\n" +
        "  </conversions>\n" +
        "</ecuparam>\n" +
        "</ecuparams>\n" +
        "<dtcodes><dtcode id=\"D1\" name=\"P0335 CKP\" tmpaddr=\"0x8E\" memaddr=\"0xA4\" bit=\"7\"/></dtcodes>\n" +
        "</protocol>\n</protocols>\n</logger>\n").replace("\n", "\r\n")

    @Test fun readsLogger() {
        val d = RomRaiderXml.parse(logger.toByteArray(Charsets.UTF_8))
        assertEquals("370", d.version)
        // the OBD protocol's P8 is not taken: only SSM
        assertEquals(listOf("P7", "P8", "P200", "S20"), d.params.map { it.id })
        val map = d.params[0]
        assertEquals(0x0D, map.addr)
        assertEquals("kPa", map.units) // metric first, although psi comes first in the file
        assertEquals(8, map.capByte); assertEquals(1, map.capBit)
        assertEquals(2, map.conversions.size)
        val rpm = d.params[1]
        assertEquals(14, rpm.addr) // a decimal address
        assertEquals(2, rpm.len)
        assertEquals(3, rpm.target)
        val load = d.params[2]
        assertEquals(-1, load.addr)
        assertEquals(listOf("P8", "P12"), load.deps)
        assertEquals(1.5, load.expr!!.eval(0.0) { id, _ -> if (id == "P12") 50.0 else 2000.0 }!!, 1e-9)
        val sw = d.params[3]
        assertEquals(RrParam.Kind.SWITCH, sw.kind)
        assertEquals(0x63, sw.addr); assertEquals(3, sw.bit); assertEquals(21, sw.capByte)
        assertEquals(1, d.ext.size)
        val e = d.ext[0]
        assertEquals("Manifold Absolute Pressure (4-byte)", e.param.name)
        assertEquals(setOf("4B04505207", "3C04784316", "AAAAAAAAAA"), e.roms.keys)
        assertEquals(0xFF6A30, e.roms["3C04784316"]!!.addr)
        assertEquals(16739000, e.roms["AAAAAAAAAA"]!!.addr)
        assertEquals("kPa absolute", e.param.units)
        assertEquals("float", e.param.storage)
    }

    /** A piece people pass around for one ECU: no wrapper, a bare "&", only ecuparams. */
    @Test fun readsSnippet() {
        val s = """
            <!-- camshafts, Zombie47 -->
            <ecuparam id="Z1" name="Camshaft In L (actual)" desc="Intake & exhaust" target="1">
                <ecu id="4B04505207"><address length="4">0xFF88EC</address></ecu>
                <conversions><conversion units="degrees" expr="x" format="0.0" storagetype="float"/></conversions>
            </ecuparam>
            <ecuparam id="Z3" name="Camshaft Ex L (actual)" target="1">
                <ecu id="4B04505207"><address length="4">0xFF8A80</address></ecu>
                <conversions><conversion units="degrees" expr="x" format="0.0" storagetype="float"/></conversions>
            </ecuparam>
        """.trimIndent()
        val d = RomRaiderXml.parse(s)
        assertEquals(listOf("Z1", "Z3"), d.ext.map { it.param.id })
        assertTrue(d.params.isEmpty())
        // a float read off the ECU through the parsed definition
        val p = d.ext[0].param.copy(addr = 0xFF88EC, len = 4)
        val bits = java.lang.Float.floatToIntBits(63.5f)
        val raw = ByteArray(4) { k -> (bits shr (24 - 8 * k)).toByte() }
        val v = RomRaider.decode(listOf(p), p.addresses, raw, listOf(p))
        assertEquals(63.5, v["Z1"]!!, 1e-6)
    }

    @Test fun reportsBadFiles() {
        fun why(s: String): String = try { RomRaiderXml.parse(s); fail("parsed: $s"); "" } catch (e: RomRaiderXml.BadFile) { e.message!! }
        assertTrue(why("<html><body>not a logger</body></html>").contains("нет элементов"))
        assertTrue(why("<logger><protocols><protocol id=\"OBD\"><parameters><parameter id=\"P8\" name=\"x\"><address>0x0C</address></parameter></parameters></protocol></protocols></logger>")
            .contains("SSM"))
        assertTrue(why("<parameter id=\"P8\" name=\"x\"><address>zz</address></parameter>").contains("P8"))
    }

    private fun std(id: String, addr: Int, expr: String, name: String = id, nameEn: String = id) = RrParam(
        id = id, kind = RrParam.Kind.STD, target = 1, name = name, nameEn = nameEn, desc = "", addr = addr, len = 1, bit = -1,
        units = "kPa", format = "0", storage = "", expr = RrExpr.parse(expr), deps = emptyList(), conversions = emptyMap(),
    )

    @Test fun mergesOverBase() {
        val base = listOf(std("P7", 0x0D, "x", "Давление во впуске", "Manifold Absolute Pressure"), std("P8", 0x0E, "x/4"))
        val e51 = base[0].copy(id = "E51", kind = RrParam.Kind.EXT, nameEn = "Manifold Absolute Pressure (4-byte)", name = "Давление, 4 байта",
            addr = 0xFF4DC0, len = 4, storage = "float", units = "kPa absolute")
        val baseByRom = mapOf("ROMA" to listOf(e51), "ROMB" to listOf(e51.copy(addr = 0xFF4EB0)))
        val file = """
            <parameter id="P7" name="Manifold Absolute Pressure"><address>0x00000D</address>
              <conversions><conversion units="kPa" expr="x*2" format="0"/></conversions></parameter>
            <parameter id="P8" name="Engine Speed"><address>0x00000E</address>
              <conversions><conversion units="kPa" expr="x*0.25" format="0"/></conversions></parameter>
            <parameter id="P900" name="My Own"><address>0x000100</address>
              <conversions><conversion units="kPa" expr="x" format="0"/></conversions></parameter>
            <ecuparam id="E51" name="Manifold Absolute Pressure (4-byte)*">
              <ecu id="ROMA"><address length="4">0xFF4DC0</address></ecu>
              <ecu id="ROMB"><address length="4">0xFF4EB4</address></ecu>
              <ecu id="ROMC"><address length="4">0xFF5000</address></ecu>
              <conversions><conversion units="kPa absolute" expr="x" format="0.0" storagetype="float"/></conversions>
            </ecuparam>
        """.trimIndent()
        val m = RomRaiderXml.merge(base, baseByRom, listOf("mine.xml" to RomRaiderXml.parse(file)))
        val all = m.all.associateBy { it.id }
        // P7's formula replaced, the Russian name kept; P8 is the same formula written otherwise; P900 is new
        assertEquals(20.0, all["P7"]!!.expr!!.eval(10.0) { _, _ -> null }!!, 1e-9)
        assertEquals("Давление во впуске", all["P7"]!!.name)
        assertEquals("", all["P8"]!!.desc) // the base's own, untouched
        assertNotNull(all["P900"])
        assertTrue(all["P900"]!!.desc.startsWith("из файла mine.xml"))
        val st = m.stats.single()
        assertEquals(setOf("P7", "P900"), st.std)
        // E51: ROMA the same, ROMB moved, ROMC new; one id that replaced something
        assertEquals(setOf("ROMB", "ROMC"), st.ext["E51"])
        assertEquals(1, st.added) // P900 (E51 had ROMB already: replaced)
        assertEquals(2, st.replaced)
        assertEquals(1, st.same)
        assertEquals(0xFF4DC0, m.byRom["ROMA"]!!.single().addr)
        assertEquals(0xFF4EB4, m.byRom["ROMB"]!!.single().addr)
        assertEquals("Давление, 4 байта", m.byRom["ROMC"]!!.single().name)
        assertEquals(setOf("P7", "P900", "E51"), st.ids("romc"))
        assertEquals(setOf("P7", "P900"), st.ids("ROMA"))
        assertNull(m.byRom["ROMD"])
    }

    /**
     * RomRaider's own v370 file, the one the bundled base was built from: read here it must come out the same as the
     * base, so a driver loading a full logger.xml replaces nothing that wasn't really different.
     */
    @Test fun fullLoggerMatchesBase() {
        val gz = java.io.File("../tools/romraider/logger_METRIC_EN_v370.xml.gz")
        val json = java.io.File("src/main/assets/romraider_ssm.json")
        if (!gz.isFile || !json.isFile) return
        val d = RomRaiderXml.parse(java.util.zip.GZIPInputStream(gz.inputStream()).use { it.readBytes() })
        val (base, byRom) = RomRaider.parseBase(json.readText())
        assertEquals(base.size, d.params.size)
        val m = RomRaiderXml.merge(base, byRom, listOf("logger_METRIC_EN_v370.xml" to d))
        val st = m.stats.single()
        assertEquals("changed: ${st.std.take(10)} ${st.ext.keys.take(10)}", 0, st.added + st.replaced)
        assertEquals(d.size, st.same)
    }

    @Test
    fun theBaseExportedReadsBackAsTheBase() {
        val json = java.io.File("src/main/assets/romraider_ssm.json").readText()
        val xml = RomRaiderXml.exportBase(json)
        val defs = RomRaiderXml.parse(xml)
        val (std, rom) = RomRaider.parseBase(json)
        assertEquals(std.size, defs.params.size)
        assertEquals(rom.values.flatten().map { it.id }.toSet().size, defs.ext.size)
        // laid over the base, nothing is added or changed: the copy is the base itself
        val st = RomRaiderXml.merge(std, rom, listOf("base.xml" to defs)).stats.single()
        assertEquals(0, st.added)
        assertEquals(0, st.replaced)
        assertTrue(xml.contains("<!-- Нагрузка двигателя (относительная) -->"))
    }
}
