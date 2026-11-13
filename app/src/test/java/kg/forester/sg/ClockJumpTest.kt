package kg.forester.sg

import kg.forester.sg.data.Trip
import kg.forester.sg.service.FuelCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockJumpTest {
    @Test fun noJumpOnTheFirstSample() = assertNull(FuelCalc.clockBackMs(0L, 1_000L))

    @Test fun aSmallTrimIsNoJump() = assertNull(FuelCalc.clockBackMs(100_000L, 97_000L))

    @Test fun aYearBackIsAJump() {
        val year = 365L * 24 * 3600_000L
        assertEquals(-year, FuelCalc.clockBackMs(2 * year, year))
    }

    @Test fun anOldBrokenTripShowsNoNegativeTime() {
        val t = Trip(startMs = 10_000_000L, endMs = 1_000L, distanceKm = 10.9, fuelL = 2.2)
        assertEquals(0L, t.durationS)
        assertEquals(0.0, t.avgSpeed, 0.0)
    }
}
