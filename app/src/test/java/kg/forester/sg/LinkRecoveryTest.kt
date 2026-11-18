package kg.forester.sg

import kg.forester.sg.data.LINK_RECOVERY_MS
import kg.forester.sg.data.LinkLoss
import kg.forester.sg.data.LinkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A mute ECU brought back on a live K-line keeps the last values on the screens for the recovery's hold. */
class LinkRecoveryTest {
    @Test fun valuesStayWhileTheLineIsRecovered() {
        val w = LinkLoss()
        val t0 = 1_000_000L
        val hold = t0 + LINK_RECOVERY_MS
        assertFalse(w.due(LinkState.NO_ECU, t0, hold))
        assertFalse(w.due(LinkState.NO_ECU, t0 + 3_000, hold))
        assertFalse(w.lost(LinkState.NO_ECU, t0 + 3_000, hold))
        // back within the hold: nothing was wiped
        assertFalse(w.due(LinkState.CONNECTED, t0 + 3_500, hold))
    }

    @Test fun aRecoveryThatFailsWipesAtTheEndOfTheHold() {
        val w = LinkLoss()
        val t0 = 1_000_000L
        val hold = t0 + LINK_RECOVERY_MS
        assertFalse(w.due(LinkState.NO_ECU, t0, hold))
        assertTrue(w.due(LinkState.NO_ECU, hold, hold))
    }

    @Test fun anOrdinaryLossStillWipesAfterTwoSeconds() {
        val w = LinkLoss()
        assertFalse(w.due(LinkState.NO_CABLE, 0L + 5_000, 0L))
        assertTrue(w.due(LinkState.NO_CABLE, 7_000L, 0L))
    }
}
