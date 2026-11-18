package kg.forester.sg

import kg.forester.sg.data.Boards
import kg.forester.sg.data.BuiltInPresets
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.PresetAdapt
import kg.forester.sg.data.PresetLayout
import kg.forester.sg.data.TileCfg
import kg.forester.sg.ui.DialOptions
import kg.forester.sg.ui.driveRowWeight
import kg.forester.sg.ui.myRowWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The ready-made layouts use real cards and designs, fill their rows and fit the screens they are meant for. */
class BuiltInPresetsTest {
    private val styleIds = listOf("src/main/java/kg/forester/sg/ui/GaugePresets.kt", "src/main/java/kg/forester/sg/ui/Gauges.kt")
        .joinToString("\n") { File(it).readText() }
        .let { src -> Regex("""(?:DialStyle\(\s*|artDial\(|lcdDial\()"([a-z0-9_]+)"""").findAll(src).map { it.groupValues[1] }.toSet() }

    private fun variants() = BuiltInPresets.all.flatMap { p -> listOfNotNull(p to p.tiles, p.small?.let { p to it }) }

    @Test fun everyBoardHasSome() {
        for (k in listOf("homeTiles", "myTiles", "driveTiles", "tcuTiles", "fuelTiles")) assertTrue(k, BuiltInPresets.of(k).size >= 5)
    }

    @Test fun realCardsAndDesigns() {
        for ((p, tiles) in variants()) {
            val b = Boards.byKey(p.board)
            val known = b.defaults.map { it.id }.toSet()
            tiles.forEach { t ->
                assertTrue("${p.id}: no card ${t.id}", t.id in known || t.id.startsWith("@"))
                assertTrue("${p.id}/${t.id}: span ${t.span}", t.span in 1..b.maxSpan)
                if (t.style.isNotEmpty()) assertTrue("${p.id}/${t.id}: design ${t.style}", DialOptions.parse(t.style).base in styleIds)
            }
            assertEquals(p.id, tiles.map { it.id }.distinct(), tiles.map { it.id })
            p.upgrades.forEach { assertTrue(p.id, it.to in known) }
            p.swaps.values.flatten().forEach { assertTrue("${p.id}: $it", it in known) }
            p.reserve.forEach { assertTrue("${p.id}: $it", it in known) }
        }
        listOf("tachStyle", "speedoStyle").forEach { k -> BuiltInPresets.all.mapNotNull { it.extras[k] }.forEach { assertTrue(it, it in styleIds) } }
    }

    @Test fun gridRowsAreFull() {
        for ((p, tiles) in variants().filter { !it.first.free }) {
            val b = Boards.byKey(p.board)
            val smallHome = p.board == HomeLayout.board.key && tiles === p.small
            val columns = if (smallHome) 4 else b.columns
            if (smallHome) assertTrue("${p.id}: 7–8\" Home has 6–8 columns of cards", tiles.sumOf { it.span } in 6..PresetAdapt.HOME_SMALL_CARDS)
            val rows = b.rows(tiles, columns)
            rows.forEachIndexed { i, r ->
                val last = i == rows.lastIndex && !b.stretch
                val used = r.sumOf { it.span }
                assertTrue("${p.id}: row $i takes $used of $columns", if (last) used <= columns else used == columns)
            }
        }
    }

    @Test fun freeCardsInsideAndApart() {
        for (p in BuiltInPresets.all.filter { it.free }) {
            val rects = p.tiles.map { it.id to it.free!! }
            rects.forEach { (id, r) -> assertTrue("${p.id}/$id", r.x >= 0f && r.y >= 0f && r.x + r.w <= 1.001f && r.y + r.h <= 1.001f) }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val (a, ra) = rects[i]
                val (b, rb) = rects[j]
                val overlap = minOf(ra.x + ra.w, rb.x + rb.w) - maxOf(ra.x, rb.x) > 0.001f && minOf(ra.y + ra.h, rb.y + rb.h) - maxOf(ra.y, rb.y) > 0.001f
                assertTrue("${p.id}: $a over $b", !overlap)
            }
        }
    }

    @Test fun theyFitTheScreens() {
        fun weight(board: String, tiles: List<TileCfg>, w: (TileCfg) -> Float) = Boards.byKey(board).rows(tiles).sumOf { r -> r.maxOf(w).toDouble() }
        for (p in BuiltInPresets.all.filter { !it.free && (it.board == "myTiles" || it.board == "driveTiles") }) {
            val w: (TileCfg) -> Float = if (p.board == "myTiles") ::myRowWeight else ::driveRowWeight
            val budget = PresetAdapt.budget(p.board)!!
            // a 10" screen holds about 3.5; a 7–8" one the budget, from the preset's own small variant or after trimming
            assertTrue("${p.id}: ${weight(p.board, p.tiles, w)}", weight(p.board, p.tiles, w) <= 3.55)
            if (p.small != null) assertTrue("${p.id} small: ${weight(p.board, p.small!!, w)}", weight(p.board, p.small!!, w) <= budget + 0.001)
        }
    }

    @Test fun namesAndIdsOnce() {
        BuiltInPresets.all.groupBy { it.board }.forEach { (b, list) ->
            assertEquals(b, list.map { it.name }.distinct().size, list.size)
            list.forEach { assertTrue(it.id, it.id.startsWith("$b/")) }
        }
        assertEquals(BuiltInPresets.all.size, BuiltInPresets.all.map { it.id }.distinct().size)
    }

    @Test fun aFormattedPresetKeepsTheWholeBoard() {
        for (p in BuiltInPresets.all.filter { p -> p.tiles.none { it.id.startsWith("@") } }) {
            val b = Boards.byKey(p.board)
            val s = PresetLayout.format(b, p.tiles, p.free)
            val parsed = b.parse(s)
            assertEquals("${p.id}: every card of the board is still there", b.defaults.map { it.id }.toSet(), parsed.map { it.id }.toSet())
            assertEquals(p.tiles.map { it.id }, parsed.filter { it.on }.map { it.id })
            assertEquals(p.free, b.isFree(s))
        }
    }
}
