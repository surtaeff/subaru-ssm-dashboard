package kg.forester.sg

import kg.forester.sg.service.EngineGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The oil estimate starts again from the coolant after a key-off, not from the last drive's warm oil. */
class OilSessionTest {
    @Test fun firstSampleIsNoNewSession() = assertFalse(EngineGuard.newSession(0L, 1_000_000L))

    @Test fun pollingPaceKeepsTheSession() = assertFalse(EngineGuard.newSession(1_000_000L, 1_000_500L))

    @Test fun aShortEcuSilenceKeepsTheSession() = assertFalse(EngineGuard.newSession(1_000_000L, 1_030_000L))

    @Test fun aNightStandIsANewSession() = assertTrue(EngineGuard.newSession(1_000_000L, 1_000_000L + 10 * 3600_000L))

    @Test fun aClockSetBackIsANewSession() = assertTrue(EngineGuard.newSession(1_000_000L, 500_000L))
}
