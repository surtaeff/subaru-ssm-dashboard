package kg.forester.sg

import kg.forester.sg.data.BoardPreset
import kg.forester.sg.data.Boards
import kg.forester.sg.data.BuiltInPresets
import kg.forester.sg.data.PresetHost
import kg.forester.sg.data.PresetLayout
import kg.forester.sg.data.PresetStore
import kg.forester.sg.data.TileCfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Вернуть как было» after a preset, and the driver's own presets. */
class PresetUndoTest {
    private class Host : PresetHost {
        val layouts = HashMap<String, String>()
        val extras = hashMapOf("clusterSize" to "1", "tachStyle" to "neon", "speedoStyle" to "neon")
        val prefs = HashMap<String, String>()
        override fun layout(key: String) = layouts[key] ?: ""
        override fun setLayout(key: String, v: String) { layouts[key] = v }
        override fun extra(name: String) = extras[name] ?: ""
        override fun setExtra(name: String, v: String) { extras[name] = v }
        override fun pref(key: String) = prefs[key] ?: ""
        override fun setPref(key: String, v: String) { prefs[key] = v }
    }

    private fun layout(p: BoardPreset) = PresetLayout.format(Boards.byKey(p.board), p.tiles, p.free)
    private fun shapes(board: String) = BuiltInPresets.of(board).map { PresetLayout.shape(Boards.byKey(board), layout(it)) }.toSet()
    private val mine = "coolant:1:2::;battery:1:1::"

    @Test fun undoBringsBackTheLayoutAndHomeExtras() {
        val h = Host()
        h.layouts["homeTiles"] = mine
        val night = BuiltInPresets.all.first { it.id == "homeTiles/night" }
        PresetStore.apply(h, "homeTiles", layout(night), night.extras, shapes("homeTiles"))
        assertEquals("lcd_night", h.extras["tachStyle"])
        assertEquals("2", h.extras["clusterSize"])
        assertTrue(PresetStore.undo(h, "homeTiles"))
        assertEquals(mine, h.layouts["homeTiles"])
        assertEquals(mapOf("clusterSize" to "1", "tachStyle" to "neon", "speedoStyle" to "neon"), h.extras)
        assertNull("used up", PresetStore.prev(h, "homeTiles"))
        assertFalse(PresetStore.undo(h, "homeTiles"))
    }

    @Test fun theFactoryLayoutIsSomethingToGoBackTo() {
        val h = Host()
        val p = BuiltInPresets.of("driveTiles").first()
        PresetStore.apply(h, "driveTiles", layout(p), emptyMap(), shapes("driveTiles"))
        assertTrue(PresetStore.undo(h, "driveTiles"))
        assertEquals("", h.layouts["driveTiles"])
    }

    @Test fun samePresetTwiceOrOneAfterAnotherKeepsTheDriversOwn() {
        val h = Host()
        h.layouts["myTiles"] = mine
        val list = BuiltInPresets.of("myTiles")
        PresetStore.apply(h, "myTiles", layout(list[0]), emptyMap(), shapes("myTiles"))
        PresetStore.apply(h, "myTiles", layout(list[0]), emptyMap(), shapes("myTiles"))
        PresetStore.apply(h, "myTiles", layout(list[1]), emptyMap(), shapes("myTiles"))
        PresetStore.apply(h, "myTiles", layout(list[2]), emptyMap(), shapes("myTiles"))
        assertEquals(mine, PresetStore.prev(h, "myTiles")!!.first)
        // a preset changed by hand is the driver's own again
        val edited = layout(list[2]).replaceFirst(":1:", ":0:")
        h.layouts["myTiles"] = edited
        PresetStore.apply(h, "myTiles", layout(list[3]), emptyMap(), shapes("myTiles"))
        assertEquals(edited, PresetStore.prev(h, "myTiles")!!.first)
    }

    @Test fun theOldSingleSlotMovesOver() {
        val h = Host()
        h.prefs["myTilesPrev"] = mine
        assertEquals(mine, PresetStore.prev(h, "myTiles")!!.first)
        assertEquals("", h.prefs["myTilesPrev"])
        assertEquals(mine, PresetStore.prev(h, "myTiles")!!.first)
    }

    @Test fun ownPresetsAreKeptListedAndCapped() {
        val h = Host()
        h.layouts["homeTiles"] = mine
        h.extras["clusterSize"] = "3"
        val p = PresetStore.current(h, "homeTiles", h.layout("homeTiles"), "Мой", "", "u1")
        assertEquals(listOf("coolant", "battery"), p.tiles.map { it.id })
        assertEquals("3", p.extras["clusterSize"])
        assertTrue(PresetStore.add(h, p))
        assertTrue(PresetStore.add(h, BoardPreset("myTiles", "Второй", tiles = listOf(TileCfg("oil", true, 12, "sti|a")), id = "u2")))
        val own = PresetStore.own(h)
        assertEquals(listOf("u1", "u2"), own.map { it.id })
        assertEquals(listOf("coolant" to 2, "battery" to 1), own[0].tiles.map { it.id to it.span })
        assertEquals("sti|a", own[1].tiles[0].style)
        PresetStore.replace(h, own[1].copy(name = "Новый"))
        assertEquals("Новый", PresetStore.own(h)[1].name)
        PresetStore.delete(h, "u1")
        assertEquals(listOf("u2"), PresetStore.own(h).map { it.id })
        repeat(PresetStore.MAX_OWN) { PresetStore.add(h, p.copy(id = "x$it")) }
        assertEquals(PresetStore.MAX_OWN, PresetStore.own(h).size)
        h.prefs["userPresets"] = "{broken"
        assertEquals(emptyList<BoardPreset>(), PresetStore.own(h))
    }
}
