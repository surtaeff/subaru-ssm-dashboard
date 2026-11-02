package kg.forester.sg

import kg.forester.sg.data.CarKind
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.TurboState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Round3Test {
    private fun layout(vararg on: String): String {
        val board = HomeLayout.board
        return board.format(on.map { kg.forester.sg.data.TileCfg(it, true) } + board.defaults.filter { it.id !in on }.map { it.copy(on = false) })
    }
    private fun onIds(s: String) = HomeLayout.parse(s).filter { it.on }.map { it.id }

    // what the old code wrote for a turbo-by-default car: obdHomeCards(seen, true)
    private val old = layout(*CarKind.obdHomeCards(setOf("coolant", "intake", "battery", "throttle", "maf", "boost", "afr"), true).toTypedArray())

    @Test fun anUnmarkedAppLayoutOfAnAtmoCarLosesOnlyTheBoostCard() {
        assertTrue("boost" in onIds(old))
        val m = HomeLayout.migrateUnmarked(old, TurboState.NON_TURBO)!!
        assertFalse("boost" in onIds(m))
        assertEquals(onIds(old) - "boost", onIds(m))
    }

    @Test fun aCardTheDriverHidAnywayStaysHidden() {
        val hidden = layout(*(CarKind.obdHomeCards(setOf("coolant", "intake", "battery", "throttle", "maf", "boost", "afr"), true) - "afr").toTypedArray())
        // «afr» is just not in the list: the layout is still one this app could have written, minus a card
        val m = HomeLayout.migrateUnmarked(hidden, TurboState.NON_TURBO)
        assertNotNull(m)
        assertFalse("afr" in onIds(m!!)); assertFalse("boost" in onIds(m))
    }

    @Test fun unknownWaitsAndTurboOnlyGetsTheMark() {
        assertNull(HomeLayout.migrateUnmarked(old, TurboState.UNKNOWN))
        assertEquals(old, HomeLayout.migrateUnmarked(old, TurboState.TURBO))
    }

    @Test fun aLayoutTheDriverMadeIsNeverTouched() {
        val own = HomeLayout.board.format(HomeLayout.parse(old).map { if (it.id == "coolant") it.copy(span = 2) else it })
        assertNull(HomeLayout.migrateUnmarked(own, TurboState.NON_TURBO))
        assertNull(HomeLayout.migrateUnmarked(own, TurboState.TURBO))
    }

    @Test fun wiredInTheLoopAndTheKdocIsRight() {
        assertTrue(File("src/main/java/kg/forester/sg/data/CarKind.kt").readText().contains("migrateUnmarked("))
        val r = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        val i = r.indexOf("var otherLayoutMark")
        val j = r.indexOf("var homeTiles")
        assertTrue(i in 0 until j)
        // nothing but the mark's own KDoc between the mark and homeTiles' doc
        assertFalse(r.substring(r.lastIndexOf("/**", j), j).contains("otherLayoutMark"))
    }
}
