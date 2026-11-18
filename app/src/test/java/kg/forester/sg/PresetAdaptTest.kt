package kg.forester.sg

import kg.forester.sg.data.BoardPreset
import kg.forester.sg.data.Boards
import kg.forester.sg.data.BuiltInPresets
import kg.forester.sg.data.CarFacts
import kg.forester.sg.data.FreeRect
import kg.forester.sg.data.MyBoard
import kg.forester.sg.data.PresetAdapt
import kg.forester.sg.data.TileCfg
import kg.forester.sg.ui.driveRowWeight
import kg.forester.sg.ui.myRowWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A preset is made for the car once, when it is applied: what it hasn't got gives way, nothing shows «—» for good. */
class PresetAdaptTest {
    private fun preset(id: String) = BuiltInPresets.all.first { it.id == id }
    private fun ids(id: String, f: CarFacts) = PresetAdapt.adapt(preset(id), f).tiles.map { it.id }

    private val na = CarFacts(turbo = false)
    private val mt = CarFacts(tcu = false)
    private val other = CarFacts(obd = true, other = true, tcu = false)

    @Test fun withoutATurbine() {
        val a = PresetAdapt.adapt(preset("driveTiles/accel"), na)
        val got = a.tiles.map { it.id }
        assertFalse("boost" in got || "chartBoost" in got || "wgdc" in got)
        assertTrue("boost" to "load" in a.swaps)
        assertTrue("chartBoost" to "chartTiming" in a.swaps)
        // the preset's own choice beats the common one
        assertEquals("rpm", ids("myTiles/track", na)[2])
        // «Просто» as the chip did it: the ATF on an automatic, the intake air on a manual
        assertEquals("atf", ids("homeTiles/simple", na).last())
        assertEquals("intake", ids("homeTiles/simple", CarFacts(turbo = false, tcu = false)).last())
        assertEquals("boost", ids("homeTiles/simple", CarFacts()).last())
    }

    @Test fun aManualGearbox() {
        assertEquals(listOf("intake", "coolant", "oil", "incline", "battery", "per100"), ids("myTiles/offroad", mt))
        // the tall selector only where a card has room for it
        assertTrue("gearsel" in ids("myTiles/cluster", mt))
        val grid = BoardPreset("myTiles", "g", tiles = listOf(TileCfg("gear", true, 6), TileCfg("speed", true, 6)))
        assertEquals(listOf("rpm", "speed"), PresetAdapt.adapt(grid, mt).tiles.map { it.id })
        // an automatic with the unit answering gets the ATF where the others have the intake air
        assertEquals("atf", ids("myTiles/everyday", CarFacts()).last())
        assertEquals("intake", ids("myTiles/everyday", mt).last())
    }

    @Test fun anotherMakeOnObd() {
        val a = PresetAdapt.adapt(preset("myTiles/turbo"), other)
        val got = a.tiles.map { it.id }
        assertTrue("fbkc" to "timing" in a.swaps)
        assertTrue("iamV" to "afcorr" in a.swaps)
        assertTrue("injDuty" to "maf" in a.swaps)
        assertFalse(got.any { it in setOf("fbkc", "iamV", "injDuty", "knock") })
        assertEquals(preset("myTiles/turbo").tiles.size, got.size)
    }

    @Test fun noWidebandSensor() {
        val a = PresetAdapt.adapt(preset("homeTiles/everyday"), CarFacts(noWb = true))
        assertTrue("afr" to "afcorr" in a.swaps)
        assertTrue("chartAfr" to "chartTrims" in PresetAdapt.adapt(preset("driveTiles/minimal"), CarFacts(noWb = true)).swaps)
    }

    @Test fun sensorRolesFindTheInputs() {
        val box = CarFacts(roles = mapOf("oilP" to "box_A5", "oil" to "box_A1", "egt" to "can_C2"))
        assertEquals(listOf("box_A5", "box_A1", "can_C2", "boost", "afr", "coolant", "battery"), ids("myTiles/sensorbox", box))
        // without the inputs: the estimated oil, the reserve for what has no stand-in
        val bare = PresetAdapt.adapt(preset("myTiles/sensorbox"), CarFacts())
        assertEquals(7, bare.tiles.size)
        assertTrue("oil" in bare.tiles.map { it.id })
    }

    @Test fun smallScreensDropRowsNotTextSize() {
        val small = CarFacts(small = true)
        // its own 7–8" variant
        assertEquals(12, ids("myTiles/diag", small).size)
        // none: the last rows go until the screen holds them at full size
        val tall = BoardPreset("myTiles", "x", tiles = listOf(TileCfg("rpm", true, 6, "sti"), TileCfg("speed", true, 6, "sti")) +
            (1..3).flatMap { listOf("coolant", "oil", "battery", "intake", "afr", "timing", "maf", "load", "per100", "range", "fuel", "map").chunked(4)[it - 1].map { id -> TileCfg(id, true, 3) } })
        val got = PresetAdapt.adapt(tall, small, rowWeight = ::myRowWeight).tiles
        val weight = MyBoard.rows(got).sumOf { r -> r.maxOf(::myRowWeight).toDouble() }
        assertTrue("$weight", weight <= PresetAdapt.budget("myTiles")!! + 0.001)
        // the dials and one row of four: 2.4 of the 2.7
        assertEquals(6, got.size)
        // Home: two rows of four beside the 3D block, all of them without it
        val home = BoardPreset("homeTiles", "h", tiles = (1..10).map { TileCfg(listOf("coolant", "oil", "battery", "per100", "range", "intake", "afr", "timing", "fuel", "gps")[it - 1], true) },
            extras = mapOf("clusterSize" to "1"))
        assertEquals(8, PresetAdapt.adapt(home, small).tiles.size)
        assertEquals(10, PresetAdapt.adapt(home.copy(extras = mapOf("clusterSize" to "0")), small).tiles.size)
        assertEquals(10, PresetAdapt.adapt(home, CarFacts()).tiles.size)
    }

    @Test fun neverTheSameCardTwice() {
        val facts = listOf(CarFacts(), na, mt, other, other.copy(turbo = false), CarFacts(noWb = true, motion = false), CarFacts(small = true, turbo = false, tcu = false))
        for (p in BuiltInPresets.all) for (f in facts) {
            val a = PresetAdapt.adapt(p, f, rowWeight = if (p.board == "driveTiles") ::driveRowWeight else ::myRowWeight)
            val got = a.tiles.map { it.id }
            assertEquals("${p.id} $f", got.distinct(), got)
            val known = Boards.byKey(p.board).defaults.map { it.id }.toSet()
            assertTrue("${p.id} $f: $got", got.all { it in known })
            assertFalse("${p.id} $f: empty", got.isEmpty())
        }
    }

    @Test fun theMisfitsAreToldApart() {
        val turbo = preset("myTiles/turbo")
        assertTrue(PresetAdapt.misfit(turbo, na, PresetAdapt.adapt(turbo, na)))
        assertFalse(PresetAdapt.misfit(turbo, CarFacts(), PresetAdapt.adapt(turbo, CarFacts())))
        val off = preset("homeTiles/offroad")
        assertTrue(PresetAdapt.misfit(off, mt, PresetAdapt.adapt(off, mt)))
    }

    @Test fun aRightHandDriveMirrorStaysInside() {
        for (p in BuiltInPresets.all.filter { it.free }) {
            val m = PresetAdapt.mirror(Boards.byKey(p.board), p.tiles, true)
            m.forEach { t ->
                val r = t.free!!
                assertTrue("${p.id}/${t.id}", r.x >= 0f && r.x + r.w <= 1.0001f)
            }
        }
        val row = listOf(TileCfg("speed", true, 4), TileCfg("coolant", true, 4), TileCfg("oil", true, 4), TileCfg("battery", true, 12))
        assertEquals(listOf("oil", "coolant", "speed", "battery"), PresetAdapt.mirror(MyBoard, row, false).map { it.id })
        assertEquals(FreeRect(0.7f, 0f, 0.3f, 1f), PresetAdapt.mirror(MyBoard, listOf(TileCfg("rpm", true, 3, "", FreeRect(0f, 0f, 0.3f, 1f))), true)[0].free)
    }
}
