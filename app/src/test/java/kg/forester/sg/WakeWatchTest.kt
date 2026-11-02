package kg.forester.sg

import kg.forester.sg.service.SleepWakes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWatchTest {
    private val screenOn = "android.intent.action.SCREEN_ON"
    private val unlock = "android.intent.action.USER_PRESENT"

    @Test fun theScreenOrAnUnlockIsNoNewSession() {
        val w = SleepWakes()
        assertFalse(w.take(screenOn, 1_000_000L))
        assertFalse(w.take(unlock, 2_000_000L))
    }

    @Test fun accAndADeepSleepGapAre() {
        for (why in listOf("com.fyt.boot.ACCON", "com.glsx.boot.ACCON", "android.intent.action.ACC_ON", "deep sleep 3600 s")) {
            assertTrue(why, SleepWakes().take(why, 1_000_000L))
        }
    }

    /** The screen usually comes first: it must not use up what ACC says a moment later. */
    @Test fun accRightAfterTheScreenStillCounts() {
        val w = SleepWakes()
        assertFalse(w.take(screenOn, 1_000_000L))
        assertTrue(w.take("com.fyt.boot.ACCON", 1_000_500L))
    }

    @Test fun oneWakeSaysSoOnce() {
        val w = SleepWakes()
        assertTrue(w.take("com.fyt.boot.ACCON", 1_000_000L))
        assertFalse("the gap of the same sleep", w.take("deep sleep 3600 s", 1_004_000L))
        assertTrue("the next sleep", w.take("com.fyt.boot.ACCON", 1_060_000L))
    }
}
