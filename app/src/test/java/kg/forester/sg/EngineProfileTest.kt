package kg.forester.sg

import kg.forester.sg.ssm.EngineId
import kg.forester.sg.ssm.EngineInfo
import kg.forester.sg.ssm.EngineProfiles
import kg.forester.sg.ssm.GasketMode
import kg.forester.sg.ui.InfoTexts
import kg.forester.sg.ui.SportCfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfileTest {
    private fun key(car: String) = EngineProfiles.of(EngineId.fromCar(car)).key
    private fun of(family: String, turbo: Boolean, code: String, source: String = "ЭБУ") =
        EngineProfiles.of(EngineInfo(family, turbo, code, source))

    @Test fun theTableOfProfiles() {
        fun row(family: String, turbo: Boolean, code: String) = of(family, turbo, code).run {
            listOf(key, thermoFullC, fanHighC, spikeFromC, spikeRiseC, fanCreepFromC, redlineRpm, gasketMode, sure)
        }
        assertEquals(listOf("EJ_NA_OLD", 91, 97, 96, 8, 92, null, GasketMode.INTERNAL, false), row("EJ22", false, "EJ22"))
        assertEquals(listOf("EJ_NA", 95, 97, 100, 8, 96, null, GasketMode.EXTERNAL, true), row("EJ25", false, "EJ253"))
        assertEquals(listOf("EJ_T", 91, 97, 96, 8, 92, 7000, GasketMode.UNKNOWN, true), row("EJ20", true, "EJ205"))
        assertEquals(listOf("EJ_T", 91, 97, 96, 8, 92, null, GasketMode.UNKNOWN, true), row("EJ25", true, "EJ255"))
        assertEquals(listOf("EJ_T_STI", 91, 97, 96, 8, 92, 8000, GasketMode.UNKNOWN, false), row("EJ20", true, "EJ207"))
        assertEquals(listOf("EJ_T_STI", 91, 97, 96, 8, 92, 6700, GasketMode.UNKNOWN, false), row("EJ25", true, "EJ257"))
        assertEquals(listOf("EZ30", 95, 97, 100, 8, 96, 6500, GasketMode.UNKNOWN, false), row("EZ30", false, "EZ30"))
        assertEquals(listOf("EZ36", 95, 97, 100, 8, 96, null, GasketMode.UNKNOWN, false), row("EZ36", false, "EZ36"))
        assertEquals(listOf("FB", 95, 97, 100, 8, 96, null, GasketMode.UNKNOWN, false), row("FB", false, "FB25"))
        assertEquals(listOf("FA20", 95, 97, 100, 8, 96, 7400, GasketMode.UNKNOWN, false), row("FA20", false, "FA20"))
        assertEquals(listOf("FA20DIT", 95, 97, 100, 8, 96, 6700, GasketMode.UNKNOWN, false), row("FA20", true, "FA20DIT"))
        assertEquals(listOf("GENERIC", 95, 97, 100, 8, 96, null, GasketMode.UNKNOWN, false), row("EJ", false, "EJ"))
    }

    /** The move from the 91° to the 95° thermostat falls inside MY2001–2003: before 2001 the old one, then the less alarming one. */
    @Test fun naByModelYear() {
        assertEquals("EJ_NA_OLD", key("USDM Forester 2.5 1999 MT"))
        assertEquals("EJ_NA_OLD", key("USDM Impreza 2.2 2000 MT"))
        assertEquals("EJ_NA", key("USDM Forester 2.5 2001 MT"))
        assertEquals("EJ_NA", key("USDM Forester 2.5 2002 MT"))
        assertEquals("EJ_NA", key("USDM Forester 2.5 2003 MT"))
        assertEquals("EJ_NA", key("USDM Impreza 2.5i 2008 AT"))
        assertEquals("EJ_NA", key("JDM Legacy 2.0 2004 AT"))
        // no year in the name: the less alarming one too
        assertEquals("EJ_NA", key("ADM Forester 2.5 MT"))
    }

    @Test fun orderOfTheMatch() {
        // the code, whether or not it says "turbo"
        assertEquals("EJ_T", of("EJ20", true, "EJ20X/EJ20Y").key)
        // the code is the base's guess for an NA, boost was seen: family and turbo
        assertEquals("EJ_T", of("EJ25", true, "EJ251").key)
        // decoded from the ROM ID: family and turbo, no redline for a code that isn't sure
        assertEquals("EJ_T", of("EJ20", true, "EJ205/EJ207", "ROM").key)
        assertEquals(null, of("EJ20", true, "EJ205/EJ207", "ROM").redlineRpm)
        assertEquals("EJ_T", of("EJ25", true, "EJ255/EJ257", "ROM").key)
        assertEquals("EJ_NA", of("EJ25", false, "EJ25", "ROM").key)
        assertEquals("EJ_NA", of("EJ20", false, "EJ20", "ROM").key)
        // only the turbo flag
        assertEquals("EJ_T", of("EJ", true, "EJ", "ЭБУ без модели").key)
        // nothing to go by
        assertEquals("GENERIC", of("EJ", false, "EJ", "ЭБУ без модели").key)
        // a sports ECU or no ECU tells nothing of the engine, boost seen or not; a diesel has no row of its own
        assertEquals("GENERIC", of("EJ", true, "Link", "Link").key)
        assertEquals("GENERIC", of("EJ", true, "EJ", "нет").key)
        assertEquals("GENERIC", of("EE20", true, "EE20").key)
    }

    @Test fun everyRomOfTheBaseHasAProfile() {
        val ids = org.json.JSONObject(java.io.File("src/main/assets/ecu_ids.json").readText()).getJSONObject("ids")
        ids.keys().forEach { id -> assertNotNull(id, EngineProfiles.of(EngineId.fromBase(ids.getString(id), id)).key) }
    }

    @Test fun tachScaleLeavesRoomForTheRedZone() {
        assertEquals(8000, EngineProfiles.rpmScaleTop(7000))
        assertEquals(9000, EngineProfiles.rpmScaleTop(8000))
        assertEquals(7000, EngineProfiles.rpmScaleTop(6500))
        assertEquals(8000, EngineProfiles.rpmScaleTop(7400))
    }

    @Test fun sportDisplayFollowsTheRedlineOnlyInItsFactoryLook() {
        assertEquals(SportCfg(), SportCfg.parse(""))
        assertEquals(SportCfg(), SportCfg.parse("", 6500))
        assertEquals(7000, SportCfg.parse("", 7000).red)
        assertEquals(8000, SportCfg.parse("", 7000).top)
        assertEquals(8000, SportCfg.parse("", 8000).red)
        assertEquals(9000, SportCfg.parse("", 8000).top)
        // a look equal to the base default is a choice on an engine whose factory look differs, and it stays
        val six = SportCfg.factory(7000).copy(red = 6500)
        assertEquals(6500, SportCfg.parse(six.format(7000), 7000).red)
        val base = SportCfg()
        assertEquals(base, SportCfg.parse(base.format(7000), 7000))
        // the factory look itself is saved as empty, and follows the engine
        assertEquals("", SportCfg.factory(7000).format(7000))
        assertEquals("", SportCfg().format())
        // what the driver chose stays
        assertEquals(6000, SportCfg.parse("sd:t=defi|top=8000|red=6000|led=1|pg=012", 7000).red)
    }

    @Test fun seedBeforeTheFirstSample() {
        val saved = EngineInfo("EJ20", true, "EJ205", "прошлое")
        assertEquals("EJ_T", EngineProfiles.seed(saved, otherMake = false).key)
        assertEquals(7000, EngineProfiles.seed(saved, otherMake = false).redlineRpm)
        assertEquals("GENERIC", EngineProfiles.seed(saved, otherMake = true).key)
        assertEquals("GENERIC", EngineProfiles.seed(null, otherMake = false).key)
    }

    @Test fun revScaleOfAProfile() {
        val ej205 = of("EJ20", true, "EJ205")
        assertEquals(8000f, ej205.rpmTop)
        assertEquals(7000f, ej205.rpmRedFrom)
        assertEquals(7000f, EngineProfiles.GENERIC.rpmTop)
        assertEquals(6000f, EngineProfiles.GENERIC.rpmRedFrom)
        fun how(k: String, e: EngineInfo) = InfoTexts.forEngine(InfoTexts.byKey[k]!!, e).how
        val turbo = EngineInfo("EJ20", true, "EJ205", "ЭБУ")
        val na = EngineInfo("EJ25", false, "EJ253", "ЭБУ")
        assertTrue(how("drive.rpm", turbo).contains("Шкала 0…8000, на стрелочном виде красная зона с 7000."))
        assertTrue(how("drive.rpm", na).contains("Шкала 0…7000, на стрелочном виде красная зона с 6000."))
        assertTrue(how("drive.chartRpm", turbo).contains("Шкала 0…8000 об/мин."))
    }

    @Test fun engine3dCalloutsFollowTheEngine() {
        val about = "Наддув. ${kg.forester.sg.ui.STOCK_EJ20T} Не добирает: утечка."
        assertTrue(kg.forester.sg.ui.spotAbout(about, EngineInfo("EJ20", true, "EJ205", "ЭБУ")).contains("0,7–0,9"))
        assertFalse(kg.forester.sg.ui.spotAbout(about, EngineInfo("EJ25", true, "EJ255", "ЭБУ")).contains("EJ20T"))
        assertEquals("Наддув. Не добирает: утечка.", kg.forester.sg.ui.spotAbout(about, EngineInfo("EJ25", true, "EJ255", "ЭБУ")))
    }

    @Test fun ej20BoostFigureOnlyOnAnEj20Turbo() {
        val keys = listOf("home.boost", "drive.boost", "drive.chartBoost")
        val figure = "0,7–0,9 бар"
        fun shown(e: EngineInfo) = InfoTexts.byKey.values.count { InfoTexts.forEngine(it, e).norm.orEmpty().contains(figure) }
        val all = InfoTexts.byKey.values.count { it.norm.orEmpty().contains(figure) }
        assertEquals(4, all)
        assertEquals(all, shown(EngineInfo("EJ20", true, "EJ205", "ЭБУ")))
        assertEquals(0, shown(EngineInfo("EJ25", true, "EJ255", "ЭБУ")))
        assertEquals(0, shown(EngineInfo("EJ20", true, "EJ207", "ЭБУ")))
        assertEquals(0, shown(EngineInfo("EJ25", false, "EJ253", "ЭБУ")))
        assertEquals(0, shown(EngineInfo("EJ", true, "Link", "Link")))
        // the rest of the text stays, on any engine
        keys.forEach { k ->
            val text = InfoTexts.byKey[k]
            assertNotNull(k, text)
            assertTrue(k, InfoTexts.forEngine(text!!, EngineInfo("EJ25", false, "EJ253", "ЭБУ")).norm!!.isNotBlank())
        }
        assertFalse(InfoTexts.forEngine(InfoTexts.byKey["home.boost"]!!, EngineInfo("EJ25", true, "EJ255", "ЭБУ")).norm!!.contains("EJ20"))
        // no stray spaces where the figure is or was
        listOf(EngineInfo("EJ20", true, "EJ205", "ЭБУ"), EngineInfo("EJ25", false, "EJ253", "ЭБУ")).forEach { e ->
            InfoTexts.byKey.values.mapNotNull { InfoTexts.forEngine(it, e).norm }.filter { it.contains("EJ20:") || it.contains("наддув") }.forEach { n ->
                assertEquals(n.trim(), n)
                assertFalse(n, n.contains("  "))
            }
        }
    }

    @Test fun coolantTextsGiveOneRange() {
        val range = "85–97"
        val texts = InfoTexts.byKey.values.mapNotNull { it.norm }
        assertEquals(3, texts.count { it.contains(range) })
        assertFalse(texts.any { it.contains("85–95") || it.contains("85–98") || it.contains("85–100") })
    }
}
