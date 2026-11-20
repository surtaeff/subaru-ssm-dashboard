package kg.forester.sg

import kg.forester.sg.service.UsbOpenFailures
import kg.forester.sg.service.UsbOpenFailures.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roman's FT232R: «Reset failed» on every open after a hub event, the same attempt every 6 s for 9 minutes. */
class UsbOpenFailuresTest {
    @Test fun firstFailureIsRetriedAsBefore() {
        val f = UsbOpenFailures()
        assertEquals(Step.RETRY, f.failed())
        assertEquals(1000L, f.pauseMs(Step.RETRY))
    }

    @Test fun secondFailureResetsTheBusAndWaitsForTheDevice() {
        val f = UsbOpenFailures()
        f.failed()
        val step = f.failed()
        assertEquals(Step.BUS_RESET, step)
        assertTrue(f.pauseMs(step) >= UsbOpenFailures.BUS_RESET_PAUSE_MS)
    }

    @Test fun busResetEveryThirdFailureAfterThat() {
        val f = UsbOpenFailures()
        val steps = (1..11).map { f.failed() }
        val resets = steps.withIndex().filter { it.value == Step.BUS_RESET }.map { it.index + 1 }
        // every third, and no more than MAX_RESETS of them without a working open in between
        assertEquals(listOf(2, 5, 8), resets)
    }

    @Test fun pauseGrowsAndIsCapped() {
        val f = UsbOpenFailures()
        val pauses = (1..12).map { f.pauseMs(f.failed()) }
        assertEquals(listOf(1000L, 3000L, 4000L, 8000L, 16_000L, 30_000L), pauses.take(6))
        assertTrue(pauses.all { it <= UsbOpenFailures.MAX_PAUSE_MS })
        assertEquals(UsbOpenFailures.MAX_PAUSE_MS, pauses.last())
    }

    @Test fun anOpenStartsOver() {
        val f = UsbOpenFailures()
        repeat(4) { f.failed() }
        f.opened()
        assertEquals(0, f.streak)
        assertEquals(Step.RETRY, f.failed())
        assertEquals(1000L, f.pauseMs(Step.RETRY))
    }

    /** Cranking: the cable leaves and comes back under a new path; its first failing opens are not reset at once. */
    @Test fun aCableBackUnderANewPathStartsOverAndIsNotResetWhileFresh() {
        val f = UsbOpenFailures()
        assertEquals(Step.RETRY, f.failed("/dev/bus/usb/001/004", 10_000L))
        f.gone()
        assertEquals(Step.RETRY, f.failed("/dev/bus/usb/001/005", 20_000L))
        assertEquals(1, f.streak)
        assertEquals("fresh: retried", Step.RETRY, f.failed("/dev/bus/usb/001/005", 21_000L))
        repeat(2) { f.failed("/dev/bus/usb/001/005", 23_000L) }
        assertEquals("old enough: reset", Step.BUS_RESET, f.failed("/dev/bus/usb/001/005", 26_000L))
    }

    @Test fun aPathChangeWithoutGoneStartsOverToo() {
        val f = UsbOpenFailures()
        f.failed("/dev/bus/usb/001/004", 0L)
        f.failed("/dev/bus/usb/001/004", 10_000L)
        f.failed("/dev/bus/usb/001/007", 11_000L)
        assertEquals(1, f.streak)
    }

    @Test fun aSecondUsbLossSoonAfterTheFirstIsBlamedOneAtEachEngineStartIsNot() {
        val b = kg.forester.sg.service.UsbBlame()
        assertEquals(false, b.strike("2F04207006", 1_000L))
        assertEquals(false, b.strike("4244307006", 2_000L))
        // the cable that fails on long frames: again a few seconds after the reconnect
        assertEquals(true, b.strike("2F04207006", 9_000L))
        assertEquals(true, b.strike("2F04207006", 15_000L))
        // cranking at the next start, an hour on in the same service run: one loss again
        assertEquals(false, b.strike("4244307006", 3_600_000L))
        assertEquals(false, b.strike("2F04207006", 7_200_000L))
    }

    /** A reset that re-enumerates the chip under a new path without curing it must not go on for ever. */
    @Test fun busResetsStopAfterAFewWithoutAWorkingOpen() {
        val f = UsbOpenFailures()
        var now = 0L
        var resets = 0
        repeat(12) { i ->
            val path = "/dev/bus/usb/001/%03d".format(10 + i)
            f.failed(path, now); now += 6_000L
            if (f.failed(path, now) == Step.BUS_RESET) resets++
            now += 3_000L
            f.gone()
        }
        assertEquals(UsbOpenFailures.MAX_RESETS, resets)
        f.opened()
        f.failed("/dev/bus/usb/001/099", now)
        assertEquals(Step.BUS_RESET, f.failed("/dev/bus/usb/001/099", now + 6_000L))
    }
}
