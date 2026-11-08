package kg.forester.sg

import kg.forester.sg.data.Boards
import kg.forester.sg.data.FuelBoard
import kg.forester.sg.data.fuelEffective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Расход», tab «Текущий» as an editable board: the factory layout, what older or odd saved text gives, the 7" one. */
class FuelBoardTest {
    @Test
    fun nothingSavedIsTheFixedLayoutOfBefore() {
        val rows = FuelBoard.rows(FuelBoard.parse(""))
        assertEquals(listOf(listOf("inst", "tank", "chart"), listOf("trip", "modes", "recent")), rows.map { r -> r.map { it.id } })
        rows.forEach { r -> assertEquals(12, r.sumOf { it.span }) }
    }

    @Test
    fun oddSavedTextKeepsWorking() {
        // an unknown card, a span past the grid, a card missing: dropped, held to the grid, added switched off
        val list = FuelBoard.parse("inst:1:3::;bogus:1:2::;tank:0:99::;chart:1:5:")
        assertEquals(FuelBoard.defaults.map { it.id }.toSet(), list.map { it.id }.toSet())
        assertEquals(12, list.first { it.id == "tank" }.span)
        assertTrue(list.filter { it.id in setOf("trip", "modes", "recent") }.none { it.on })
        // the free layout's text reads back the same
        val free = FuelBoard.format(FuelBoard.withFreeRects(FuelBoard.defaults, { 1f }), free = true)
        assertTrue(FuelBoard.isFree(free))
        assertEquals(FuelBoard.defaults.map { it.id }, FuelBoard.parse(free).map { it.id })
    }

    @Test
    fun aSmallScreenGetsFewerCardsUntilOneIsSaved() {
        val small = FuelBoard.parse(fuelEffective("", small = true))
        assertEquals(listOf("inst", "tank", "trip"), small.filter { it.on }.map { it.id })
        assertEquals(FuelBoard.defaults.size, small.size)
        assertEquals("", fuelEffective("", small = false))
        val saved = FuelBoard.format(FuelBoard.defaults)
        assertEquals(saved, fuelEffective(saved, small = true))
    }

    @Test
    fun itsKeyFindsIt() = assertSame(FuelBoard, Boards.byKey("fuelTiles"))
}
