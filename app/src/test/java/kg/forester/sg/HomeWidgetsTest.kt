package kg.forester.sg

import kg.forester.sg.data.DriveBoard
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.MyBoard
import kg.forester.sg.data.WIDGET_CARDS
import kg.forester.sg.ui.GearSlots
import kg.forester.sg.ui.clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map, player and gear selector cards: what the selector lights for each position the 4EAT reports, and where they are offered. */
class HomeWidgetsTest {
    @Test fun sevenPlacesWithRoomFiveWithout() {
        assertEquals(listOf("P", "R", "N", "D", "3", "2", "1"), GearSlots.slots("D", roomForSeven = true))
        assertEquals(listOf("P", "R", "N", "D", GearSlots.LOW), GearSlots.slots("D", roomForSeven = false))
        // the SportShift gate as on the «АКПП» screen
        assertEquals(listOf("P", "R", "N", "D", "M"), GearSlots.slots("M", roomForSeven = true))
    }

    @Test fun pOrNLightsBothHalfwayAndNothingIsGuessed() {
        assertEquals(0.5f, GearSlots.lit("P/N", "P"), 0f)
        assertEquals(0.5f, GearSlots.lit("P/N", "N"), 0f)
        assertEquals(0f, GearSlots.lit("P/N", "R"), 0f)
        assertEquals(1f, GearSlots.lit("R", "R"), 0f)
        assertEquals(1f, GearSlots.lit("D", "D"), 0f)
        // the unit gave no position (two range bits): nothing lit
        listOf("P", "R", "N", "D", "3", GearSlots.LOW).forEach { assertEquals(it, 0f, GearSlots.lit(null, it), 0f) }
    }

    @Test fun theSharedPlaceSaysWhichManualRange() {
        assertEquals(1f, GearSlots.lit("2", GearSlots.LOW), 0f)
        assertEquals("2", GearSlots.label("2", GearSlots.LOW))
        assertEquals(GearSlots.LOW, GearSlots.label("D", GearSlots.LOW))
        assertEquals(1f, GearSlots.lit("3", "3"), 0f)
        assertEquals(0f, GearSlots.lit("3", "2"), 0f)
    }

    @Test fun theGearOnlyWithADrivingPosition() {
        assertEquals(3, GearSlots.gearShown("D", 3))
        assertEquals(2, GearSlots.gearShown("M", 2))
        assertEquals(1, GearSlots.gearShown("1", 1))
        assertEquals(null, GearSlots.gearShown("P/N", 1))
        assertEquals(null, GearSlots.gearShown("R", 1))
        assertEquals(null, GearSlots.gearShown("D", null))
    }

    @Test fun offeredSwitchedOffOnEveryBoardWithTheirWidths() {
        // map, player, selector
        val widths = mapOf(HomeLayout.board to listOf(2, 2, 1), DriveBoard to listOf(6, 6, 2), MyBoard to listOf(6, 6, 2))
        for ((board, w) in widths) {
            val d = board.defaults.associateBy { it.id }
            WIDGET_CARDS.forEach { assertFalse("${board.key}/$it off", d.getValue(it).on) }
            assertEquals(w, listOf("navmap", "player", "gearsel").map { d.getValue(it).span })
        }
        // a layout saved before them reads with them appended, switched off
        val old = HomeLayout.board.format(HomeLayout.board.defaults.filter { it.id !in WIDGET_CARDS })
        val parsed = HomeLayout.board.parse(old)
        WIDGET_CARDS.forEach { id -> assertTrue(parsed.any { it.id == id && !it.on }) }
    }

    @Test fun trackTimes() {
        assertEquals("0:00", clock(0))
        assertEquals("3:07", clock(187_000))
        assertEquals("1:02:45", clock(3_765_000))
    }
}
