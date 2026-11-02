package kg.forester.sg

import kg.forester.sg.data.ecuTurbo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The one answer on a turbo for a car whose ECU answers an init: the layout, the guard and the engine title all ask [ecuTurbo]. */
class TurboDecisionTest {
    private fun text(rel: String): String =
        listOf("src/main/java/kg/forester/sg", "app/src/main/java/kg/forester/sg").map { File(it, rel) }.first { it.isFile }.readText()

    @Test fun everyCombination() {
        // rom, wastegate bit, long init, boost seen -> turbo
        val table = listOf(
            listOf(null, false, false, false) to false,
            listOf(null, false, false, true) to true,
            listOf(null, false, true, false) to false,
            listOf(null, false, true, true) to true,
            // the mismatch: a short init has no such byte and "reported" says yes to what it doesn't list
            listOf(null, true, false, false) to false,
            listOf(null, true, false, true) to true,
            listOf(null, true, true, false) to true,
            listOf(null, true, true, true) to true,
            listOf(true, false, false, false) to true,
            listOf(true, false, true, false) to true,
            listOf(true, true, false, false) to true,
            listOf(true, true, true, true) to true,
            listOf(false, false, false, false) to false,
            listOf(false, true, false, false) to false,
            listOf(false, true, true, false) to false,
            listOf(false, false, true, false) to false,
            // real boost beats a ROM that says atmo
            listOf(false, false, false, true) to true,
            listOf(false, true, true, true) to true,
        )
        for ((c, want) in table) {
            assertEquals(c.toString(), want, ecuTurbo(c[0] as Boolean?, c[1] as Boolean, c[2] as Boolean, c[3] as Boolean))
        }
    }

    @Test fun allThreeGoThroughTheOneFunction() {
        val service = text("service/EcuService.kt")
        val guard = text("service/EngineGuard.kt")
        val engine = text("ssm/EngineId.kt")
        assertFalse(text("data/HomeLayout.kt").contains("fun initTurbo"))
        assertFalse(service.contains("initTurbo"))
        assertTrue(service.contains("EngineId.turbo("))
        assertTrue(guard.contains("EngineId.turbo("))
        assertTrue(engine.contains("ecuTurbo("))
        assertFalse("no second reading of the wastegate bit", guard.contains("capabilities.size"))
        assertEquals("one place reads the init's length", 1, Regex("capabilities\\.size").findAll(engine).count())
    }

    @Test fun boostSeenReachesTheCards() {
        val guard = text("service/EngineGuard.kt")
        assertTrue(guard.contains("s.carTurbo = true"))
        assertTrue(guard.contains("s.boostRom = "))
        assertTrue(text("service/EcuService.kt").contains("Repo.settings.boostRom"))
    }

    @Test fun anAtmoFactoryScreenGoesBackWhenBoostShowsUp() {
        val atmo = kg.forester.sg.data.HomeLayout.atmoDefault()
        assertEquals("", kg.forester.sg.data.HomeLayout.tilesAfterBoost(atmo))
        val own = kg.forester.sg.data.HomeLayout.board.format(kg.forester.sg.data.HomeLayout.board.defaults.take(3))
        assertEquals("a driver's own screen stays", own, kg.forester.sg.data.HomeLayout.tilesAfterBoost(own))
        assertEquals("", kg.forester.sg.data.HomeLayout.tilesAfterBoost(""))
    }
}
