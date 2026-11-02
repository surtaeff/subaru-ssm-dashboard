package kg.forester.sg

import kg.forester.sg.service.PerfCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Not everyone wants a banner for each hard stop: a measured run is told only when asked for, and it is kept either way. */
class PerfBannerTest {
    private fun text(p: String) = File("src/main/java/kg/forester/sg/$p").readText()

    @Test fun offByDefaultAndNeverInTheDemo() {
        assertFalse(PerfCalc.tellRun(shown = false, demo = false))
        assertTrue(PerfCalc.tellRun(shown = true, demo = false))
        assertFalse(PerfCalc.tellRun(shown = true, demo = true))
        val repo = text("data/Repo.kt")
        assertTrue(repo, """p.getBoolean("perfResults", false)""" in repo)
    }

    @Test fun bothRunsAskTheSettingAndAreKeptFirst() {
        val perf = text("service/Perf.kt")
        assertEquals(2, Regex("""Alerts\.raise\("run"""").findAll(perf).count())
        assertEquals(2, Regex("""PerfCalc\.tellRun\(""").findAll(perf).count())
        // keep(...) of the brake run comes before the banner's question
        assertTrue(perf.indexOf("keep(describe(tr, m, PerfRun(now, emptyMap(), m.speed") < perf.indexOf("PerfCalc.brakeText"))
        assertFalse(""""Торможение со ста: %.2f секунды, %.0f метров"""" in perf)
    }

    @Test fun metresAgreeWithTheNumber() {
        assertEquals("Торможение со ста: 2.94 секунды, 41 метр", PerfCalc.brakeText(2.94, 41.0))
        assertEquals("Торможение со ста: 2.94 секунды, 42 метра", PerfCalc.brakeText(2.94, 42.0))
        assertEquals("Торможение со ста: 2.94 секунды, 45 метров", PerfCalc.brakeText(2.94, 45.0))
        assertEquals("Торможение со ста: 2.94 секунды, 11 метров", PerfCalc.brakeText(2.94, 11.0))
        assertEquals("Торможение со ста: 3.10 секунды, 41 метр", PerfCalc.brakeText(3.1, 40.6))
    }

    @Test fun onlyWarningsAndDangerGetTheTriangle() {
        val o = text("ui/Overlays.kt")
        assertFalse("Icon(Lucide.TriangleAlert, null, tint = col" in o)
        assertEquals(2, Regex("""alertIcon\(alert\.level\)""").findAll(o).count())
    }
}
