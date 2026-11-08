package kg.forester.sg

import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.TcuParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selector of the АКПП screens: lever range bits, the SportShift manual-gate switch and the gear held. */
class TcuSelectorTest {
    @Test
    fun leverRanges() {
        assertEquals("P/N", TcuParams.selector(0x01, null, 1))
        assertEquals("P/N", TcuParams.selector(0x00, null, 1))
        assertEquals("R", TcuParams.selector(0x02, null, 1))
        assertEquals("D", TcuParams.selector(0x04, null, 4))
        assertEquals("3", TcuParams.selector(0x08, null, 3))
        assertEquals("2", TcuParams.selector(0x10, null, 2))
        assertEquals("1", TcuParams.selector(0x20, null, 1))
        // Hold / Power mode bits (6, 7) are no position
        assertEquals("D", TcuParams.selector(0x84, null, 2))
        assertNull(TcuParams.selector(null, null, 2))
    }

    @Test
    fun manualSwitchDecides() {
        // the gate switch on: «M» whatever range bit the unit raises for the gear it holds
        assertEquals("M", TcuParams.selector(0x20, true, 1))
        assertEquals("M", TcuParams.selector(0x08, true, 3))
        assertEquals("M", TcuParams.selector(0x00, true, 4))
        assertEquals("M", TcuParams.selector(null, true, 2))
        // listed and off: the lever range as it is
        assertEquals("1", TcuParams.selector(0x20, false, 2))
        assertEquals("D", TcuParams.selector(0x04, false, 3))
    }

    @Test
    fun noSwitchGearAboveTheRangeIsManual() {
        // the reported case: the gate reads as «1» while the box holds 3rd
        assertEquals("M", TcuParams.selector(0x20, null, 3))
        assertEquals("M", TcuParams.selector(0x10, null, 3))
        assertEquals("M", TcuParams.selector(0x08, null, 4))
        // at or below the lever's number it may still be the lever
        assertEquals("1", TcuParams.selector(0x20, null, 1))
        assertEquals("3", TcuParams.selector(0x08, null, 2))
        assertEquals("1", TcuParams.selector(0x20, null, null))
        // D has no number to be above
        assertEquals("D", TcuParams.selector(0x04, null, 4))
    }

    @Test
    fun twoRangesAtOnceAreNoPosition() {
        assertNull(TcuParams.selector(0x24, null, 3))
        assertNull(TcuParams.selector(0x03, null, 1))
    }

    @Test
    fun decodeReadsTheSwitchOnlyWhereListed() {
        // capability bytes from init byte 8: index 28 is the 21st; bit 7 = manual gate switch, bit 0 = FWD switch
        val caps = ByteArray(24).also { it[28 - 8] = 0x80.toByte() }
        val withSwitch = EcuInit("x", "y", caps)
        assertTrue(TcuParams.manualSwitch(withSwitch))
        assertTrue(TcuParams.supported(withSwitch).contains(TcuParams.FWD))
        val addrs = intArrayOf(TcuParams.GEAR, TcuParams.RANGE, TcuParams.FWD)
        // gear raw 1 = 2nd, range bit «2», gate switch on, FWD bit set but not listed
        val raw = byteArrayOf(1, 0x10, 0x81.toByte())
        val t = TcuParams.decode(addrs, raw, 0L, withSwitch)
        assertEquals("M", t.range)
        assertEquals(2, t.gear)
        assertNull(t.fwdFuse)
        // the same bytes from a unit that doesn't list the switch: bit 7 ignored, the FWD bit read as before
        val old = TcuParams.decode(addrs, raw, 0L)
        assertEquals("2", old.range)
        assertEquals(true, old.fwdFuse)
    }
}
