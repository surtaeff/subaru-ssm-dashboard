package kg.forester.sg

import kg.forester.sg.service.EngineGuard
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtmFallbackTest {
    @Test fun aGoodBarometerStays() = assertEquals(95f, EngineGuard.atmOf(95f, 99f, 800.0)!!, 0.01f)

    @Test fun noBarometerStaysNull() = assertNull(EngineGuard.atmOf(null, 99f, 800.0))

    @Test fun nonsenseTakesTheStillManifold() = assertEquals(99f, EngineGuard.atmOf(150f, 99f, 800.0)!!, 0.01f)

    @Test fun nonsenseWithoutStillManifoldTakesGpsAltitude() {
        // about 92 kPa at 800 m
        assertEquals(92f, EngineGuard.atmOf(150f, null, 800.0)!!, 0.6f)
    }

    @Test fun nonsenseWithNothingElseIsSeaLevel() = assertEquals(101.3f, EngineGuard.atmOf(150f, null, null)!!, 0.01f)

    @Test fun boostShowsWithTheStandIn() {
        // Tribe: MAP 160 kPa under boost, barometer reading 150
        val e = EcuSnapshot(mapKpa = 160f, atmKpa = EngineGuard.atmOf(150f, 100f, null))
        assertEquals(0.6f, e.boostBar!!, 0.001f)
    }
}
