package kg.forester.sg

import kg.forester.sg.service.BoxFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxFirstTest {
    private val nano = "/dev/bus/usb/001/004"
    private val kkl = "/dev/bus/usb/001/005"

    @Test fun ecuWaitsForTheBoxLoopAfterAStart() {
        val f = BoxFirst()
        f.holdAll(1_000)
        // the ECU loop looks first: the CH340 the box loop has yet to ask is not for it
        assertFalse(f.ecuMayTake(nano, isBox = false, asked = false, now = 2_000))
        // asked and not the box: the ECU loop's cable
        assertTrue(f.ecuMayTake(kkl, isBox = false, asked = true, now = 2_000))
        // the box loop never got to it: not kept from the ECU for ever
        assertTrue(f.ecuMayTake(nano, isBox = false, asked = false, now = 1_000 + BoxFirst.HOLD_MS))
    }

    @Test fun theBoxDeviceIsNeverTheEcus() {
        val f = BoxFirst()
        assertFalse(f.ecuMayTake(nano, isBox = true, asked = true, now = 10_000_000))
        assertFalse(f.ecuMayTake(nano, isBox = true, asked = false, now = 10_000_000))
        // nothing held, nothing happened: as before
        assertTrue(f.ecuMayTake(kkl, isBox = false, asked = false, now = 10_000_000))
    }

    @Test fun aRememberedBoxWaitsLonger() {
        val f = BoxFirst()
        f.holdAll(0)
        f.hold(nano, 0, BoxFirst.REMEMBERED_MS)
        assertTrue(f.ecuMayTake(kkl, isBox = false, asked = false, now = BoxFirst.HOLD_MS))
        assertFalse(f.ecuMayTake(nano, isBox = false, asked = false, now = BoxFirst.HOLD_MS))
        assertTrue(f.ecuMayTake(nano, isBox = false, asked = true, now = BoxFirst.HOLD_MS))
        f.gone(setOf(kkl))
        assertTrue(f.ecuMayTake(nano, isBox = false, asked = false, now = BoxFirst.HOLD_MS))
    }

    @Test fun silentKlineLetsGoOncePerWake() {
        val f = BoxFirst()
        assertFalse(f.ecuYields(nano, BoxFirst.NO_ECHO_ROUNDS - 1, boxMayAsk = true))
        // an OpenPort or a CANable: the box loop never asks it, so nothing to let go for
        assertFalse(f.ecuYields(nano, BoxFirst.NO_ECHO_ROUNDS, boxMayAsk = false))
        assertTrue(f.ecuYields(nano, BoxFirst.NO_ECHO_ROUNDS, boxMayAsk = true))
        // a real KKL cable with the ignition off, back from the box loop: kept now
        assertFalse(f.ecuYields(nano, BoxFirst.NO_ECHO_ROUNDS + 5, boxMayAsk = true))
        f.holdAll(0)
        assertTrue(f.ecuYields(nano, BoxFirst.NO_ECHO_ROUNDS, boxMayAsk = true))
        // let go: the ECU waits for the box loop to ask it
        f.hold(nano, 100_000)
        assertFalse(f.ecuMayTake(nano, isBox = false, asked = false, now = 101_000))
    }

    @Test fun askedAgainAfterAnErrorWhenItWasTheBox() {
        val f = BoxFirst()
        // the USB error after a sleep ended a session that had answered: asked again
        assertTrue(f.askAgain(nano, answered = true, lastBox = true, remembered = true))
        // the box's device, silent or failing: asked again and again (the ECU loop never takes it)
        repeat(10) { assertTrue(f.askAgain(nano, answered = false, lastBox = true, remembered = true)) }
        // a device with a remembered box's ids (a KKL on the same CH340 has them too): a few tries only
        repeat(BoxFirst.REMEMBERED_TRIES) { assertTrue(f.askAgain(kkl, answered = false, lastBox = false, remembered = true)) }
        assertFalse(f.askAgain(kkl, answered = false, lastBox = false, remembered = true))
        // anything else: once, as before
        assertFalse(f.askAgain("/dev/bus/usb/001/009", answered = false, lastBox = false, remembered = false))
        // a wake starts the tries afresh
        f.holdAll(0)
        assertTrue(f.askAgain(kkl, answered = false, lastBox = false, remembered = true))
    }

    @Test fun boxIds() {
        assertEquals("1A86:7523", BoxFirst.id(0x1A86, 0x7523, null))
        assertEquals("1A86:7523", BoxFirst.id(0x1A86, 0x7523, ""))
        assertEquals("2341:8036/HIDPC", BoxFirst.id(0x2341, 0x8036, "HIDPC"))
        val ids = listOf("1A86:7523", "1B4F:9206/ABC")
        assertTrue(BoxFirst.remembered(ids, "1A86:7523"))
        assertTrue(BoxFirst.remembered(ids, "1B4F:9206/ABC"))
        // no permission yet, no serial: by VID:PID
        assertTrue(BoxFirst.remembered(ids, "1B4F:9206"))
        assertFalse(BoxFirst.remembered(ids, "1B4F:9206/XYZ"))
        assertFalse(BoxFirst.remembered(ids, "0403:6001"))
        assertFalse(BoxFirst.remembered(emptyList(), "1A86:7523"))
    }
}
