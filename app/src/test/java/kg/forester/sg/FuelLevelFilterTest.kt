package kg.forester.sg

import kg.forester.sg.service.FuelLevelFilter
import kg.forester.sg.service.MusicOnStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelLevelFilterTest {
    @Test fun aDipOnASlopeBarelyShows() {
        val f = FuelLevelFilter()
        f.feed(38.0, 0.0, 1.0)
        var v = 0.0
        repeat(60) { v = f.feed(34.0, 0.0, 1.0)!! } // a minute on a slope
        assertTrue("shown $v", v > 37.5)
        repeat(60) { v = f.feed(38.0, 0.0, 1.0)!! }
        assertTrue(v > 37.5 && v <= 38.0)
    }

    @Test fun burntFuelGoesDownAtOnce() {
        val f = FuelLevelFilter()
        f.feed(40.0, 0.0, 1.0)
        assertEquals(39.0, f.feed(40.0, 1.0, 0.0)!!, 0.001)
    }

    @Test fun aRefillIsTakenAfterAMinute() {
        val f = FuelLevelFilter()
        f.feed(10.0, 0.0, 1.0)
        var v = 0.0
        repeat(59) { v = f.feed(55.0, 0.0, 1.0)!! }
        assertTrue(v < 15.0)
        v = f.feed(55.0, 0.0, 1.0)!!
        assertEquals(55.0, v, 0.001)
    }

    @Test fun noSenderNoLevel() = assertNull(FuelLevelFilter().feed(null, 0.1, 1.0))

    @Test fun musicOnStartPlan() {
        assertNull(MusicOnStart.plan(false, false, "bt", ""))
        assertNull(MusicOnStart.plan(true, true, "bt", ""))
        assertNull(MusicOnStart.plan(true, false, "apps", ""))
        assertEquals("bt", MusicOnStart.plan(true, false, "bt", ""))
        assertEquals("apps", MusicOnStart.plan(true, false, "apps", "ru.yandex.music"))
    }
}
