package kg.forester.sg

import kg.forester.sg.ssm.EngineId
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineIdTest {
    private fun e(car: String) = EngineId.fromCar(car).let { "${it.family} ${if (it.turbo) "T" else "NA"} ${it.code}" }

    @Test fun carsToEngines() {
        assertEquals("EJ20 T EJ205", e("JDM Forester XT 2003 AT"))
        assertEquals("EJ20 T EJ207", e("JDM Impreza STi 2005 MT/AT"))
        assertEquals("EJ25 T EJ257", e("USDM Impreza STI 2007 MT"))
        assertEquals("EJ25 T EJ255", e("USDM Forester XT 2005 MT"))
        assertEquals("EJ20 T EJ205", e("USDM Impreza WRX 2003 MT"))
        assertEquals("EJ25 T EJ255", e("USDM Impreza WRX 2010 MT"))
        assertEquals("FA20 T FA20DIT", e("USDM Impreza WRX 2015 MT"))
        assertEquals("FA20 NA FA20", e("USDM BRZ FA20 2015 MT"))
        assertEquals("EJ25 NA EJ253", e("USDM Impreza 2.5i 2008 AT"))
        assertEquals("EJ25 NA EJ251", e("USDM Forester 2.5 2003 MT"))
        assertEquals("EZ30 NA EZ30", e("JDM Legacy 3.0R 2004"))
        assertEquals("EZ36 NA EZ36", e("CAD Legacy 3.6R 2013 AT"))
        assertEquals("FB NA FB25", e("KDM Forester 2.5 NA 2016 MT"))
        assertEquals("EE20 T EE20", e("EDM Forester 2.0D 2013 CVT"))
        assertEquals("EE20 T EE20", e("EDM Impreza 2.0T Diesel MT"))
        assertEquals("EZ36 NA EZ36", e("USDM Tribeca 2008 AT"))
        assertEquals("EZ30 NA EZ30", e("USDM B9 Tribeca 2007 AT"))
        assertEquals("EJ25 NA EJ253", e("JDM Legacy 2.5i 2008 AT"))
        assertEquals("EJ20 T EJ20X/EJ20Y", e("JDM Legacy GT 2005 AT"))
    }

    /** European SF and SG: the Forester Turbo and the first XT were the 2.0 (EJ205, 170-177 hp), the 2.5 XT came for the facelift. */
    @Test fun europeanForesterTurboIsTheTwoLitreUpTo2005() {
        assertEquals("EJ20 T EJ205", e("EDM Forester XT 2004 MT"))
        assertEquals("EJ20 T EJ205", e("EDM Forester XT 2005 MT/AT"))
        assertEquals("EJ20 T EJ205", e("EDM Forester Turbo 2003 MT/AT"))
        assertEquals("EJ20 T EJ205", e("EDM Forester Turbo MT/AT"))
        assertEquals("EJ25 T EJ255", e("EDM Forester XT 2006 MT"))
        // no year and an XT badge: nothing says the 2.0
        assertEquals("EJ25 T EJ255", e("EDM Forester XT AT"))
        // the other markets as they were
        assertEquals("EJ25 T EJ255", e("USDM Forester XT 2004 AT"))
        assertEquals("EJ25 T EJ255", e("ADM Forester XT 2004 AT"))
    }

    /** The name is the guess, the bytes stay the answer: a byte digit 2 on a car the name calls 2004 is the 2.5 XT. */
    @Test fun europeanForesterBytesStillDecide() {
        val ids = org.json.JSONObject(java.io.File("src/main/assets/ecu_ids.json").readText()).getJSONObject("ids")
        fun b(id: String) = EngineId.fromBase(ids.getString(id), id).let { "${it.family} ${if (it.turbo) "T" else "NA"} ${it.code}" }
        assertEquals("EJ20 T EJ205", b("3144504006"))
        assertEquals("EJ20 T EJ205", b("3B44503006"))
        assertEquals("EJ25 T EJ255", b("3B42584006"))
        assertEquals("EJ25 T EJ255", b("4242584106"))
    }

    /**
     * Why the byte digit is not applied to the 13/14 ROMs: under the same digit 2 the base has both sizes
     * (13628026FF is a JDM STi, the 2.0, beside the USDM 2.5s), and the digits 1, 6, 7, 8 are 2.5s there.
     */
    @Test fun byteDigitDoesNotHoldForThe1314Roms() {
        val ids = org.json.JSONObject(java.io.File("src/main/assets/ecu_ids.json").readText()).getJSONObject("ids")
        val digit2 = ids.keys().asSequence().filter { (it.startsWith("13") || it.startsWith("14")) && it[3] == '2' }
            .map { EngineId.fromCar(ids.getString(it)).family }.toSet()
        assertEquals(setOf("EJ20", "EJ25"), digit2)
        assertEquals(null, EngineId.fromRomId("134451FFFF"))
    }

    /** ROMs the base doesn't know: the engine from the ROM ID's own bytes. */
    @Test fun fromRomIdBytes() {
        fun r(id: String) = EngineId.fromRomId(id)?.let { "${it.family} ${if (it.turbo) "T" else "NA"}" }
        assertEquals("EJ20 T", r("2A04446305"))   // JDM Forester XT 2003 (EJ205)
        assertEquals("EJ25 T", r("3B02594006"))   // JDM Forester STi 2004 (EJ255)
        assertEquals("EJ25 NA", r("3B12044206"))  // USDM Forester 2.5 2005
        assertEquals("EJ20 NA", r("2F04015206"))  // JDM Legacy 2.0 2004
        assertEquals("EJ20 T", r("2A44506005"))   // EDM Forester Turbo 2003
        assertEquals("EZ30 NA", r("2F0A366106"))  // JDM Legacy 3.0R
        assertEquals(null, r("13521031FF"))       // the later 32-bit scheme: not decoded
        assertEquals(null, r("A229703007"))
    }

    /**
     * ROMs of the base whose name and bytes disagree: the bytes give the family and the turbo. The two exceptions: a
     * byte 3 from 0xD0 up (the diesels) under a petrol name, and with it 3D04EA4605.
     */
    @Test fun romBytesWinOverNameExceptKnownOdd() {
        val ids = org.json.JSONObject(java.io.File("src/main/assets/ecu_ids.json").readText()).getJSONObject("ids")
        var decoded = 0
        ids.keys().forEach { id ->
            val car = ids.getString(id)
            val got = EngineId.fromBase(car, id)
            assertEquals(id, id, got.romId)
            assertEquals(id, car, got.car)
            val bytes = EngineId.fromRomId(id)
            when {
                // the later schemes say nothing: the name alone
                bytes == null -> assertEquals(id, EngineId.fromCar(car, "ЭБУ", id), got)
                bytes.diesel && !car.uppercase().contains("DIESEL") && !car.uppercase().contains("2.0D") -> assertEquals(id, EngineId.fromCar(car, "ЭБУ", id), got)
                else -> {
                    decoded++
                    assertEquals(id, bytes.family, got.family)
                    assertEquals(id, bytes.turbo, got.turbo)
                }
            }
        }
        assertEquals(650, ids.length())
        assertEquals(437, decoded)
        fun b(id: String) = EngineId.fromBase(ids.getString(id), id).let { "${it.family} ${if (it.turbo) "T" else "NA"} ${it.code}" }
        assertEquals("EJ25 T EJ257", b("3D04EA4605"))        // bytes wrong: a petrol STi, no diesel
        assertEquals("EE20 T EE20", b("6644D87207"))         // "2.0T Diesel"
        assertEquals("EZ36 NA EZ36", b("571B347007"))        // Tribeca 2008 on is the 3.6
        assertEquals("EZ36 NA EZ36", b("574B343107"))
        assertEquals("EZ36 NA EZ36", b("671B344007"))
        assertEquals("EZ30 NA EZ30", b("4C1A344007"))        // Tribeca 2007, still the 3.0
        assertEquals("EJ25 NA EJ253", b("5C52184007"))       // see the note in EngineId.fromBase
        assertEquals("EJ20 T EJ207", b("2E44594005"))        // EDM STi 2003: the 2.0
        assertEquals("EJ25 T EJ255", b("3B02594006"))        // JDM Forester STi: 2.5, EJ255
        assertEquals("EJ25 T EJ255", b("6902744007"))        // JDM Impreza STi AT (A-Line)
        assertEquals("EJ25 T EJ255", b("5B02564007"))        // JDM Legacy GT 2010
        assertEquals("EJ25 T EJ255", b("4EA2584007"))        // named JDM, byte 2's A is the SADM market: their 2.5
        assertEquals("EJ20 T EJ20X/EJ20Y", b("3C54504106"))  // ADM Liberty GT 2004: the twin scroll 2.0
        assertEquals("EJ20 T EJ205", b("2604446305"))        // JDM Legacy BH 2002: the name has no word for turbo
        assertEquals("EJ25 NA EJ251", b("3C02057216"))       // JDM Outback 2.5i
        assertEquals("EJ25 NA EJ253", b("6202154007"))       // JDM Legacy 2.5i 2008
        // bytes of no known size change nothing
        assertEquals("EJ25 NA EJ253", EngineId.fromBase("USDM Impreza 2.5i 2008 AT", "3B13044206").let { "${it.family} ${if (it.turbo) "T" else "NA"} ${it.code}" })
    }

    @Test fun headerFromTheEcu() {
        val e = EngineId.fromCar("JDM Forester XT 2003 AT", "ЭБУ", "2A04446305")
        assertEquals("FORESTER XT" to "2003 · EJ205 · 2.0 · турбо", kg.forester.sg.ui.carHeader(e, "FORESTER SG5", "2003   EJ20 turbo", false))
        assertEquals("Мой Форик" to "2003 · EJ205 · 2.0 · турбо", kg.forester.sg.ui.carHeader(e, "Мой Форик", "", true))
        // decoded from the ROM ID only: no model name, the engine still from the ECU
        val r = EngineId.fromRomId("3B12044206")!!
        assertEquals("SUBARU" to "EJ25 · 2.5 · атмо", kg.forester.sg.ui.carHeader(r, "FORESTER SG5", "x", false))
        // the base's "Legacy(Outback) GT(XT)": the brackets go
        val l = EngineId.fromCar("USDM Legacy(Outback) GT(XT) 2008 AT", "ЭБУ", "5212525107")
        assertEquals("LEGACY GT", kg.forester.sg.ui.carHeader(l, "x", "", false).first)
        // no ECU yet: nothing made up, only the driver's own name if there is one
        val none = kg.forester.sg.ssm.EngineInfo("EJ", false, "EJ", "нет")
        assertEquals("SUBARU" to "подключите ЭБУ", kg.forester.sg.ui.carHeader(none, "FORESTER SG5", "2003   EJ20 turbo", false))
        assertEquals("Мой Форик" to "подключите ЭБУ", kg.forester.sg.ui.carHeader(none, "Мой Форик", "", true))
    }

    @Test fun car3dFromTheEcu() {
        assertEquals("SG", kg.forester.sg.ui.car3dFor("JDM Forester XT 2003 AT"))
        assertEquals("SHNA", kg.forester.sg.ui.car3dFor("USDM Forester 2.5i 2009 AT"))
        assertEquals("GD", kg.forester.sg.ui.car3dFor("USDM Impreza STi 2005 MT"))
        assertEquals("BM", kg.forester.sg.ui.car3dFor("USDM Legacy GT 2005 MT"))
    }
}
