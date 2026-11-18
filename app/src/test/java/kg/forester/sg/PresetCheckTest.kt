package kg.forester.sg

import kg.forester.sg.data.Checked
import kg.forester.sg.data.PresetCheck
import kg.forester.sg.data.RawPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An outside preset is checked against this app's cards before anything of it reaches a screen. */
class PresetCheckTest {
    private val styles = setOf("sti", "neon", "lcd_night")
    private fun raw(board: String, tiles: String, free: Boolean = false, extras: Map<String, String> = emptyMap(), roles: Map<String, String> = emptyMap(), name: String = "x") =
        RawPreset(1, board, name, "", free, tiles, extras, roles)
    private fun ok(r: RawPreset, local: Map<String, String> = emptyMap()) = PresetCheck.check(r, local, styles::contains) as Checked.Ok

    @Test fun unknownCardsGoAndSpansStayInTheGrid() {
        val c = ok(raw("homeTiles", "coolant:1:3::;warp:1:3::;intake:1:9::"))
        assertEquals(listOf("coolant" to 3, "intake" to 3), c.preset.tiles.map { it.id to it.span })
        assertEquals(listOf("warp"), c.dropped)
    }

    @Test fun freeRectsStayInsideTheArea() {
        val c = ok(raw("myTiles", "coolant:1:3::0.9000,0.9500,0.5000,0.5000;oil:1:3::", free = true))
        val r = c.preset.tiles[0].free!!
        assertTrue(r.x + r.w <= 1.0001f && r.y + r.h <= 1.0001f)
        assertTrue("a card that came without a place gets one", c.preset.tiles[1].free != null)
    }

    @Test fun localPicturesAndUnknownDesignsBecomeTheUsualLook() {
        val c = ok(raw("myTiles", "coolant:1:4:photo:my.png:;oil:1:4:custom:7:;battery:1:4:sti|a|g:;rpm:1:4:future_x|a:;speed:1:4:neon|z300:"))
        assertEquals(listOf("", "", "sti|g|a", "", "neon|z150"), c.preset.tiles.map { it.style })
    }

    @Test fun relaysAndRawParametersStayHome() {
        val c = ok(raw("myTiles", "outw_1:1:3::;rr:E12:1:3::;coolant:1:3::"))
        assertEquals(listOf("coolant"), c.preset.tiles.map { it.id })
        assertEquals(listOf("outw_1", "rr:E12"), c.dropped)
    }

    @Test fun sensorInputsFindTheOwnInputWithTheirRole() {
        val r = raw("myTiles", "box_A2:1:4:sti:;coolant:1:4::", roles = mapOf("box_A2" to "oilP"))
        assertEquals(listOf("box_A5", "coolant"), ok(r, mapOf("oilP" to "box_A5")).preset.tiles.map { it.id })
        assertEquals("sti", ok(r, mapOf("oilP" to "box_A5")).preset.tiles[0].style)
        val none = ok(r)
        assertEquals(listOf("coolant"), none.preset.tiles.map { it.id })
        assertEquals(listOf("box_A2"), none.dropped)
    }

    @Test fun anotherScreensPresetIsNotPutHere() {
        assertEquals(Checked.WrongBoard("driveTiles"), PresetCheck.forBoard(raw("driveTiles", "boost:1:4::"), "myTiles", emptyMap(), styles::contains))
        assertEquals(Checked.Bad, PresetCheck.forBoard(raw("nope", "boost:1:4::"), "nope", emptyMap(), styles::contains))
        assertEquals(Checked.Bad, PresetCheck.check(raw("myTiles", "warp:1:4::"), emptyMap(), styles::contains))
    }

    @Test fun onlyKnownExtrasInTheirRange() {
        val c = ok(raw("homeTiles", "coolant:1:1::", extras = mapOf("clusterSize" to "7", "tachStyle" to "nope", "speedoStyle" to "lcd_night", "bottomBar" to "x")))
        assertEquals(mapOf("clusterSize" to "3", "speedoStyle" to "lcd_night"), c.preset.extras)
        assertEquals(emptyMap<String, String>(), ok(raw("myTiles", "coolant:1:1::", extras = mapOf("clusterSize" to "2"))).preset.extras)
    }

    @Test fun theNameIsCleaned() {
        assertEquals("Трасса", ok(raw("myTiles", "coolant:1:1::", name = "  Трас\u0007са\n ")).preset.name)
        assertEquals(40, ok(raw("myTiles", "coolant:1:1::", name = "я".repeat(90))).preset.name.length)
        assertEquals("Пресет", ok(raw("myTiles", "coolant:1:1::", name = " ")).preset.name)
    }
}
