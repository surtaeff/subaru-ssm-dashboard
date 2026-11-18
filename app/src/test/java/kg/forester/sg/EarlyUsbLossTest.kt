package kg.forester.sg

import kg.forester.sg.service.EcuService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alexey's FT232RL: the cable leaves the USB a few seconds after the ECU init, on the first long reads. */
class EarlyUsbLossTest {
    @Test fun noInitYetIsNotEarly() = assertFalse(EcuService.earlyUsbLoss(0L, 5_000L))

    @Test fun threeSecondsAfterInitIsEarly() = assertTrue(EcuService.earlyUsbLoss(100_000L, 103_000L))

    @Test fun aCablePulledLaterIsNotEarly() = assertFalse(EcuService.earlyUsbLoss(100_000L, 100_000L + 10 * 60_000L))
}
