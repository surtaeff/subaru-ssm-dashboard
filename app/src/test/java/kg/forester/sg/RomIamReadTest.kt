package kg.forester.sg

import kg.forester.sg.service.EngineGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomIamReadTest {
    private val now = 100L * 24 * 3600_000L

    @Test fun fewerThanThreeFailuresStillTry() {
        assertTrue(EngineGuard.shouldReadRomIam(0, 0L, now))
        assertTrue(EngineGuard.shouldReadRomIam(2, now - 1000L, now))
    }

    @Test fun threeFailuresJustNowSkip() = assertFalse(EngineGuard.shouldReadRomIam(3, now - 1000L, now))

    @Test fun threeFailuresAMonthAgoTryAgain() = assertTrue(EngineGuard.shouldReadRomIam(3, now - 31L * 24 * 3600_000L, now))

    @Test fun clockSetBackTriesAgain() = assertTrue(EngineGuard.shouldReadRomIam(3, now + 5L * 24 * 3600_000L, now))
}
