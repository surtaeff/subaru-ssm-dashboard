package kg.forester.sg

import kg.forester.sg.data.CarMods
import kg.forester.sg.service.Analyzer
import kg.forester.sg.service.DynoCalc
import kg.forester.sg.service.DynoRun
import kg.forester.sg.service.DynoSample
import kg.forester.sg.service.DynoStore
import kg.forester.sg.service.EngineGuard
import kg.forester.sg.service.LogColumns
import kg.forester.sg.service.MemoryRuleStore
import kg.forester.sg.service.RuleInput
import kg.forester.sg.service.leanBoostText
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** «Лог для настройщика», power by airflow, the lean-on-boost text and «Доработки машины». */
class TunerAndModsTest {
    private val offered = LogColumns.all.map { it.key }

    @Test fun tunerPresetIsTheTuningSet() {
        val keys = LogColumns.tunerKeys(offered, widebandOn = false, turbo = true, automatic = false)
        val want = listOf("rpm", "speed_kmh", "coolant_c", "intake_c", "throttle_pct", "pedal_pct", "load_grev", "avcs_deg", "maf_gs",
            "inj_pw_ms", "inj_duty_pct", "af_corr_pct", "af_learn_pct", "afr", "timing_deg", "iam", "fbkc_deg", "flkc_deg",
            "target_boost_kpa", "wgdc_pct", "boost_bar")
        assertEquals(want, keys)
        // every key is a real column, in the order of the list
        assertTrue(keys.all { it in offered })
        assertEquals(offered.filter { it in keys }, keys)
    }

    @Test fun tunerPresetTakesTheWidebandTheGearAndLeavesTurboColumnsOnAnNa() {
        val wb = LogColumns.tunerKeys(offered, widebandOn = true, turbo = true, automatic = true)
        assertTrue("wideband_afr" in wb && "afr" !in wb && "gear" in wb)
        val na = LogColumns.tunerKeys(offered, widebandOn = false, turbo = false, automatic = false)
        assertFalse("wgdc_pct" in na || "target_boost_kpa" in na || "gear" in na)
        assertTrue("afr" in na && "boost_bar" in na)
        // the wideband switched off: its columns aren't offered, the front sensor's AFR it is
        val noWb = LogColumns.tunerKeys(offered.filter { !it.startsWith("wideband") }, widebandOn = true, turbo = true, automatic = false)
        assertTrue("afr" in noWb && "wideband_afr" !in noWb)
    }

    private fun s(t: Long, maf: Float?) = DynoSample(t, 3000 + t.toInt(), 60f, 100f, 80f, maf)

    @Test fun airflowPowerFromThePullsOwnPeakMaf() {
        assertEquals(250f, DynoCalc.airflowHp(200f), 0.01f)
        val read = listOf(s(0, 40f), s(100, 120f), s(200, 160f), s(300, 190f), s(400, 185f))
        assertEquals(190f, DynoCalc.mafPeak(read)!!, 0.01f)
        // the fast poll: the MAF from before the pull repeated, or a single fresh read, is no peak
        assertNull(DynoCalc.mafPeak(listOf(s(0, 40f), s(100, 40f), s(200, 40f), s(300, 40f))))
        assertNull(DynoCalc.mafPeak(listOf(s(0, 40f), s(100, 40f), s(200, 150f), s(300, 150f))))
        assertNull(DynoCalc.mafPeak(listOf(s(0, null), s(100, null), s(200, null))))
    }

    @Test fun drivetrainLossAndAirDensity() {
        assertEquals(0.20f, DynoCalc.drivetrainLoss(false), 1e-6f)
        assertEquals(0.25f, DynoCalc.drivetrainLoss(true), 1e-6f)
        assertEquals(250f, DynoCalc.crankHp(200f, 0.20f), 0.01f)
        assertEquals(1.225, DynoCalc.airDensity(101.325f, 15), 0.002)
        // a mountain pass in summer: thinner air, less drag
        assertTrue(DynoCalc.airDensity(80f, 30) < 0.95)
        assertEquals(DynoCalc.RHO, DynoCalc.airDensity(null, 20), 1e-9)
        assertEquals(DynoCalc.RHO, DynoCalc.airDensity(101f, 120), 1e-9)
        // thinner air: less of the power goes to the drag, so less is counted at the wheels
        val pull = List(121) { i -> DynoSample(1000L + i * 33, (2500 + i * 30), (2500 + i * 30) / 35f, 100f, 80f) }
        val sea = DynoCalc.curve(pull, 1550f, 1f, null, 1.2).maxOf { it.hp }
        val high = DynoCalc.curve(pull, 1550f, 1f, null, 0.9).maxOf { it.hp }
        assertTrue(high < sea)
    }

    @Test fun runKeepsItsPeakMafAndMods() {
        val dir = Files.createTempDirectory("dyno").toFile()
        val mods = CarMods(downpipe = true, tune = CarMods.Tune.STAGE2).encode()
        val r = DynoRun(9000L, 1550f, 1f, 3, 35f, "Stage 2 · даунпайп", 25, 99f, 90, 1f, DynoCalc.ALGO, 185f, mods)
        DynoStore.write(dir, r, List(20) { DynoSample(9000L + it * 50, 3000 + it * 50, 80f, 100f, 90f) }, null)
        assertEquals(listOf(r), DynoStore.scan(dir, DynoStore.metaJson(listOf(r))))
        dir.deleteRecursively()
    }

    @Test fun leanTextShowsTheTargetAndWhatToCheck() {
        val t = leanBoostText(13.3f, 11.2f)
        assertTrue(t, t.contains("13") && t.contains("ЭБУ просит 11"))
        assertTrue(t.contains("давление топлива") && t.contains("3 бар") && t.contains("ДМРВ") && t.contains("форсунки") && t.contains("лог"))
        assertFalse(leanBoostText(13.3f, null).contains("ЭБУ просит"))
        // a wrong address is no target
        assertFalse(leanBoostText(13.3f, 2f).contains("ЭБУ просит"))
    }

    @Test fun targetAfrFromEachUnit() {
        assertEquals(11.0f, EngineGuard.afrOf(11.0f, "estimated AFR")!!, 1e-4f)
        assertEquals(11.76f, EngineGuard.afrOf(0.8f, "lambda")!!, 1e-3f)
        assertEquals(11.76f, EngineGuard.afrOf(1.25f, "fuel-air equivalence ratio")!!, 1e-3f)
        assertNull(EngineGuard.afrOf(0f, "fuel-air equivalence ratio"))
        assertNull(EngineGuard.afrOf(40f, "estimated AFR"))
    }

    @Test fun leanRuleSaysWhatTheEcuAskedFor() {
        val said = ArrayList<String>()
        val a = Analyzer(MemoryRuleStore(), { id, text, _, _ -> if (id == "leanBoost") said += text })
        for (i in 0..30) {
            val e = EcuSnapshot(timeMs = 100_000L + i * 100L, rpm = 4500, speedKmh = 90, coolantC = 90, throttlePct = 100f,
                mapKpa = 200f, atmKpa = 100f, lambda = 13.3f / 14.7f, mafGs = 180f)
            a.process(RuleInput(e, turbo = true, targetAfr = 11.0f))
        }
        assertTrue(said.toString(), said.isNotEmpty() && said.first().contains("ЭБУ просит 11"))
    }

    @Test fun modsRoundTripAndWhatResetsTheBoostNorms() {
        val m = CarMods(catFront = true, downpipe = true, intake = true, tune = CarMods.Tune.STAGE1)
        assertEquals(m, CarMods.parse(m.encode()))
        assertEquals(CarMods(), CarMods.parse(""))
        assertEquals(CarMods(), CarMods.parse("tune=nonsense,unknown"))
        // the intake changes the MAF, not the boost
        assertEquals(CarMods().boostKey, CarMods(intake = true).boostKey)
        assertNotEquals(CarMods().boostKey, CarMods(tune = CarMods.Tune.STAGE1).boostKey)
        assertNotEquals(CarMods().boostKey, CarMods(bigTurbo = true).boostKey)
        assertEquals("", CarMods().tag())
        assertEquals("Stage 1 · без перед. кат. · даунпайп · впуск", m.tag())
        assertTrue(CarMods(downpipe = true).stockTuneFreeFlow)
        assertFalse(CarMods(downpipe = true, tune = CarMods.Tune.STAGE2).stockTuneFreeFlow)
    }

    @Test fun catalystCodesWithTheCatalystGone() {
        val base = "КПД катализатора ниже порога"
        assertEquals(base, CarMods().dtcHint("P0420", base))
        // a rear catalyst sits after the rear sensor: the monitor doesn't see it
        assertEquals(base, CarMods(catRear = true).dtcHint("P0420", base))
        assertTrue(CarMods(catFront = true).dtcHint("P0420", base)!!.contains("прошивка"))
        assertTrue(CarMods(catUpPipe = true).dtcHint("P2096", null)!!.contains("заднему зонду"))
        assertEquals("x", CarMods(catFront = true).dtcHint("P0171", "x"))
    }

    @Test fun modsChangeTheHintsAndForgetTheBoost() {
        val said = HashMap<String, String>()
        val store = MemoryRuleStore()
        val a = Analyzer(store, { id, text, _, _ -> said[id] = text })
        val mods = CarMods(downpipe = true)
        for (i in 0..30) {
            val e = EcuSnapshot(timeMs = 200_000L + i * 100L, rpm = 4000, speedKmh = 90, coolantC = 90, throttlePct = 100f,
                mapKpa = 230f, atmKpa = 100f, wastegatePct = 10f, lambda = 0.78f)
            a.process(RuleInput(e, turbo = true, targetBoostKpa = 90f, mods = mods))
        }
        assertTrue(said["overboost"].toString(), said["overboost"]!!.contains("WGDC"))
        store.boostBase = 95f; store.spool = "{}"
        a.forgetBoostNorms()
        a.process(RuleInput(EcuSnapshot(timeMs = 300_000L, rpm = 800, coolantC = 90), mods = mods))
        assertEquals(0f, store.boostBase, 0f)
        assertEquals("", store.spool)
    }
}
