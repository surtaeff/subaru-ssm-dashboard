package kg.forester.sg

import kg.forester.sg.ssm.RomRaider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarberryTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText()
    private fun params() = RomRaider.parseCarberry(asset("carberry42_logger.json"))

    @Test fun theEcusOwnVeIsThereForThisRomOnly() {
        val ve = params().single { it.id == RomRaider.CARBERRY_VE }
        assertEquals("Volumetric Efficiency", ve.nameEn)
        assertEquals("Наполнение VE (из ЭБУ)", ve.name)
        // the stock base has no live VE for any 16-bit ROM (only MerpMod's patched E1101 and the calculated P242, P243)
        val defs = org.json.JSONObject(asset("romraider_ssm.json")).getJSONArray("ecuparams")
        val named = (0 until defs.length()).map { defs.getJSONObject(it) }.filter { "volumetric" in it.getString("n").lowercase() }
        assertEquals(listOf("E1101"), named.map { it.getString("id") })
    }

    /** The petrol ECU's exhaust temperature: a standard parameter, there only with its capability bit. */
    @Test fun exhaustTemperatureGoesByTheCapabilityBit() {
        val ps = org.json.JSONObject(asset("romraider_ssm.json")).getJSONArray("params")
        val egt = (0 until ps.length()).map { ps.getJSONObject(it) }.filter { it.getString("id") in RomRaider.ECU_EGT }
        assertEquals(2, egt.size)
        for (p in egt) { assertTrue(p.getInt("b") >= 0); assertTrue(p.getInt("cb") >= 0) }
        val metrics = File("src/main/java/kg/forester/sg/ui/Metrics.kt").readText()
        assertTrue("it.id in kg.forester.sg.ssm.RomRaider.ECU_EGT" in metrics)
        assertTrue("\"egtEcu\"" in File("src/main/java/kg/forester/sg/service/EngineGuard.kt").readText())
    }

    @Test fun profileIsExplicitAndRestrictedToEngineId() {
        assertTrue(RomRaider.useCarberry("carberry42", "3d04ea4605", false))
        assertFalse(RomRaider.useCarberry("stock", "3D04EA4605", false))
        assertFalse(RomRaider.useCarberry("carberry42", "3D04EA4705", false))
        assertFalse(RomRaider.useCarberry("carberry42", "3D04EA4605", true))
    }

    @Test fun allChannelsParseAndNewIdsCannotCollideWithStock() {
        val ps = params()
        assertEquals(67, ps.size)
        assertEquals(67, ps.map { it.id }.toSet().size)
        assertEquals(35, ps.count { it.id.startsWith("CB42_") })
        assertTrue(ps.all { it.expr != null && it.addr in 1..0xFFFFFF && it.len in listOf(1, 2, 4) })
        assertFalse(ps.any { it.id == "E2" || it.id == "E153" })
        assertTrue(ps.all { it.expr!!.refs.isEmpty() })
    }

    @Test fun commonChannelsKeepStockAddressesAndUnits() {
        val original = JSONObject(asset("romraider_ssm.json")).getJSONArray("ecuparams")
        val stock = (0 until original.length()).map { original.getJSONObject(it) }.associateBy { it.getString("id") }
        for (p in params().filterNot { it.id.startsWith("CB42_") }) {
            val old = stock.getValue(p.id)
            val a = old.getJSONObject("r").getJSONArray("3D04EA4605")
            assertEquals(a.getInt(0), p.addr)
            assertEquals(a.getInt(1), p.len)
            assertEquals(old.getJSONArray("c").getJSONArray(0).getString(0), p.units)
        }
    }

    @Test fun decodeCarberrySpecificSyntheticByteVectors() {
        val all = params()
        fun decode(id: String, bytes: ByteArray): Double {
            val p = all.single { it.id == id }
            return RomRaider.decode(listOf(p), p.addresses, bytes, all).getValue(id)
        }
        // Synthetic protocol vectors from the XML formulas, not a hardware recording.
        assertEquals(1.0, decode("CB42_E153", byteArrayOf(0x20, 0)), 1e-9)
        assertEquals(50.0, decode("CB42_E176", byteArrayOf(0x80.toByte(), 0)), 1e-9)
        assertEquals(50.0, decode("CB42_E182", byteArrayOf(0x80.toByte(), 0)), 1e-9)
        assertEquals(100.0, decode("CB42_E158", byteArrayOf(0x80.toByte(), 0)), 1e-9)
        // 16-bit ECU IAM uses 0..16 here; EngineGuard normalizes it for display.
        assertEquals(16.0, decode("E1", byteArrayOf(16)), 1e-9)
    }

    @Test fun stockChannelsNeverDisappear() {
        val original = JSONObject(asset("romraider_ssm.json")).getJSONArray("ecuparams")
        val stockIds = (0 until original.length()).map { original.getJSONObject(it) }
            .filter { it.getJSONObject("r").has("3D04EA4605") }.map { it.getString("id") }
        val cb = params()
        // stand-ins for the stock list: only ids matter here
        val stock = cb.filter { it.id in stockIds } + cb.filter { it.id in stockIds }.take(1).map { it.copy(id = "E999") }
        val merged = RomRaider.withCarberry(stock, cb)
        assertEquals(stock, merged.take(stock.size))
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        assertTrue(merged.map { it.id }.containsAll(cb.map { it.id }))
        // IAM, FBKC, FLKC keep their stock ids for the engine guard
        assertTrue(merged.map { it.id }.containsAll(listOf("E1", "E10", "E12")))
        // any other ROM, or the stock profile: no CarBerry channels at all
        assertFalse(RomRaider.useCarberry("carberry42", "2A04446405", false))
        assertFalse(RomRaider.useCarberry("stock", "3d04ea4605", false))
    }

    @Test fun carberryChannelsAreMetric() {
        val ps = params()
        assertTrue(ps.none { it.units.equals("F", true) || it.units.lowercase().startsWith("psi") })
        fun decode(id: String, bytes: ByteArray): Double {
            val p = ps.single { it.id == id }
            return RomRaider.decode(listOf(p), p.addresses, bytes, ps).getValue(id)
        }
        val port = ps.single { it.id == "CB42_E171" }
        assertEquals("C", port.units)
        // 0x8000 → 104 °F → 40 °C
        assertEquals(40.0, decode("CB42_E171", byteArrayOf(0x80.toByte(), 0)), 1e-6)
        assertEquals("kPa/sample", ps.single { it.id == "CB42_E177" }.units)
        // 1 mmHg a sample in kPa
        assertEquals(0.1333, decode("CB42_E177", byteArrayOf(0x80.toByte(), 1)), 1e-3)
    }
}
