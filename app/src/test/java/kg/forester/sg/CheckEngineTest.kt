package kg.forester.sg

import kg.forester.sg.service.CheckEngine
import kg.forester.sg.service.CodeWatch
import kg.forester.sg.service.checkEngineShown
import kg.forester.sg.service.currentCodes
import kg.forester.sg.ui.headerPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckEngineTest {
    // ---- what counts ----

    @Test fun nothingWhileUnknownOrWithNoCodes() {
        assertFalse(checkEngineShown(null, connected = true))
        assertFalse(checkEngineShown(CheckEngine(), connected = true))
    }

    @Test fun aCodeOfEitherUnitOrTheLampShows() {
        assertTrue(checkEngineShown(CheckEngine(engine = 1), connected = true))
        assertTrue(checkEngineShown(CheckEngine(gearbox = 2), connected = true))
        assertTrue(checkEngineShown(CheckEngine(mil = true), connected = true))
        assertEquals(3, CheckEngine(engine = 1, gearbox = 2).count)
    }

    @Test fun noLinkMeansUnknownNotTheLastAnswer() {
        assertFalse(checkEngineShown(CheckEngine(engine = 3), connected = false))
    }

    // ---- the status bytes ----

    @Test fun onlyTheBitsOfTheTableCount() {
        val table = listOf(0x8E to 0, 0x8E to 1, 0x8F to 7)
        assertEquals(0, currentCodes(table, mapOf(0x8E to 0, 0x8F to 0)))
        assertEquals(2, currentCodes(table, mapOf(0x8E to 0b11, 0x8F to 0)))
        // a bit the table doesn't explain raises no icon: the screen shows it as a question, on a read by hand
        assertEquals(1, currentCodes(table, mapOf(0x8E to 0b100, 0x8F to 0x80)))
    }

    @Test fun anUnimplementedAddressIsNoCode() {
        assertEquals(0, currentCodes(listOf(0x8E to 0, 0x8F to 3), mapOf(0x8E to 0xFF, 0x8F to 0xFF)))
    }

    @Test fun aCodeListedTwiceCountsOnce() {
        assertEquals(1, currentCodes(listOf(0x8E to 0, 0x8E to 0), mapOf(0x8E to 1)))
    }

    // ---- how often the ECU is asked ----

    @Test fun oneCheckSoonAfterConnectingThenRarely() {
        val w = CodeWatch()
        w.newConnection(1000L)
        assertFalse(w.due(1000L))
        assertTrue(w.due(1000L + CodeWatch.FIRST_MS))
        w.done(1000L + CodeWatch.FIRST_MS)
        assertFalse(w.due(1000L + CodeWatch.FIRST_MS + 1))
        assertFalse(w.due(1000L + CodeWatch.FIRST_MS + CodeWatch.EVERY_MS - 1))
        assertTrue(w.due(1000L + CodeWatch.FIRST_MS + CodeWatch.EVERY_MS))
    }

    @Test fun theScheduleIsEightSecondsThenEveryFiveMinutes() {
        assertEquals(8_000L, CodeWatch.FIRST_MS)
        assertEquals(5 * 60_000L, CodeWatch.EVERY_MS)
    }

    @Test fun readingOrClearingOnTheScreenAsksAgainAtOnce() {
        val w = CodeWatch()
        w.newConnection(0L)
        w.done(CodeWatch.FIRST_MS)
        assertFalse(w.due(CodeWatch.FIRST_MS + 10))
        CodeWatch.ask()
        assertTrue(w.due(CodeWatch.FIRST_MS + 10))
        w.done(CodeWatch.FIRST_MS + 10)
        assertFalse(w.due(CodeWatch.FIRST_MS + 20))
    }

    @Test fun theUiNeverAsksTheEcuItself() {
        // the icon reads the state the service keeps: no ECU request from the header or the gauges
        val root = listOf("src/main/java/kg/forester/sg/ui", "app/src/main/java/kg/forester/sg/ui").map(::File).first { it.exists() }
        val src = File(root, "CheckEngineIcon.kt").readText()
        assertFalse("EcuLink" in src)
        assertFalse("DtcRepo" in src)
    }

    // ---- the header's room ----

    private fun plan(total: Float, check: Boolean, status: Boolean = false, demo: Boolean = false) =
        headerPlan(total, 64f, 18f, 14f, logo = false, demo = demo, status = status, valuesWanted = 2, titleChars = 8, check = check)

    @Test fun noCodesTakeNoRoom() {
        val a = plan(900f, false)
        assertEquals(0f, a.checkDp, 0f)
        assertEquals(headerPlan(900f, 64f, 18f, 14f, false, false, false, 2, 8), a)
    }

    @Test fun itOutranksTheVoltsAndTheCoolantAtAnyWidth() {
        var w = 300f
        while (w < 1500f) {
            val p = plan(w, true)
            // never the readings without the icon, as soon as the fixed buttons fit at all
            if (p.readings) assertTrue("at $w", p.checkDp > 0f)
            w += 10f
        }
        // there is a width where the icon stays and the readings have gone
        val narrow = (300..1500 step 10).map { plan(it.toFloat(), true) }.first { it.checkDp > 0f }
        assertFalse(narrow.readings)
    }

    @Test fun itOutranksThePlatesToo() {
        val w = (300..1500 step 10).first { plan(it.toFloat(), true, status = true, demo = true).checkDp > 0f }
        val p = plan(w.toFloat(), true, status = true, demo = true)
        assertEquals(0f, p.statusDp, 0f)
        assertEquals(0f, p.demoDp, 0f)
    }

    @Test fun itsRoomIsInTheSum() {
        val with = plan(1100f, true)
        val without = plan(1100f, false)
        assertTrue(with.sumDp <= 1100f + 0.01f)
        assertTrue(with.mandatoryDp > without.mandatoryDp)
    }
}
