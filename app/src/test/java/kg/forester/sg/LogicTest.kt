package kg.forester.sg

import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.LockState
import kg.forester.sg.ui.BottomBarCfg
import kg.forester.sg.ui.BottomSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LogicTest {
    @Test fun lockUpStates() {
        assertEquals(LockState.UNKNOWN, LockState.of(null, null, 0))
        assertEquals(LockState.OPEN, LockState.of(0f, 100f, 0))
        assertEquals(LockState.ENGAGING, LockState.of(40f, 20f, 0))
        assertEquals(LockState.ENGAGING, LockState.of(90f, 20f, 1000)) // commanded, still slipping, not for long
        assertEquals(LockState.LOCKED, LockState.of(90f, 1f, 0))
        assertEquals(LockState.LOCKED, LockState.of(90f, null, 0)) // no turbine speed: trust the command
        assertEquals(LockState.SLIPPING, LockState.of(90f, 15f, 3000))
    }

    @Test fun mafJunkIsDropped() {
        // 0xFFFF / 100 at idle: the 261 l/h report
        assertNull(EcuSnapshot(rpm = 800, mafGs = 655.35f).saneMaf().mafGs)
        assertNull(EcuSnapshot(rpm = 800, mafGs = 450f).saneMaf().mafGs)
        assertNull(EcuSnapshot(rpm = 1000, mafGs = 120f).saneMaf().mafGs)
        val idle = EcuSnapshot(rpm = 750, mafGs = 3.2f)
        assertSame(idle, idle.saneMaf())
        val wot = EcuSnapshot(rpm = 6500, mafGs = 280f) // EJ20 turbo at full boost
        assertSame(wot, wot.saneMaf())
    }

    @Test fun mafTopFollowsTheLearnedScale() {
        // 450 g/s at 6500 rpm: past the stock top, real on a car whose MAF scale has grown to 450
        val big = EcuSnapshot(rpm = 6500, mafGs = 450f)
        assertNull(big.saneMaf().mafGs)
        assertNull(big.saneMaf(150f).mafGs)
        assertSame(big, big.saneMaf(450f))
        // 0xFFFF and a per-rpm impossible reading stay junk whatever the scale
        assertNull(EcuSnapshot(rpm = 7000, mafGs = 655.35f).saneMaf(600f).mafGs)
        assertNull(EcuSnapshot(rpm = 2000, mafGs = 300f).saneMaf(600f).mafGs)
    }

    @Test fun bottomBarConfig() {
        val l = BottomBarCfg.parse(BottomBarCfg.DEFAULT)
        assertEquals(listOf("range", "coolant", "battery", "intake", "altitude"), l.map { it.id })
        assertEquals(listOf(true, true, true, false, false), l.map { it.bar })
        assertEquals(BottomBarCfg.DEFAULT, BottomBarCfg.format(l))
        assertEquals(emptyList<BottomSlot>(), BottomBarCfg.parse(""))
        // repeats dropped, at most MAX
        assertEquals(listOf("a", "b"), BottomBarCfg.parse("a:1,b:0,a:0").map { it.id })
        assertEquals(BottomBarCfg.MAX, BottomBarCfg.parse((1..10).joinToString(",") { "x$it:0" }).size)
    }

    @Test fun valuesClearAfterLinkLoss() {
        val w = kg.forester.sg.data.LinkLoss()
        val on = kg.forester.sg.data.LinkState.CONNECTED
        val lost = kg.forester.sg.data.LinkState.NO_ECU
        assertFalse(w.due(on, 0))
        // out of CONNECTED at 1000: nothing yet, and still not a moment before the two seconds are out
        assertFalse(w.due(lost, 1000))
        assertFalse(w.due(lost, 2999))
        assertTrue(w.due(lost, 3000))
        // said once, not on every tick
        assertFalse(w.due(lost, 3500))
        // the other states of a lost link don't start the count again
        assertFalse(w.due(kg.forester.sg.data.LinkState.CONNECTING, 4000))
        assertFalse(w.due(kg.forester.sg.data.LinkState.ERROR, 9000))
        // a link that comes back within two seconds clears nothing
        assertFalse(w.due(on, 10_000))
        assertFalse(w.due(kg.forester.sg.data.LinkState.CONNECTING, 10_500))
        assertFalse(w.due(on, 11_000))
        assertFalse(w.due(lost, 12_000))
        assertFalse(w.due(on, 13_000))
        // and the next loss is counted from its own start
        assertFalse(w.due(lost, 20_000))
        assertTrue(w.due(lost, 22_000))
        // what the cleared screen is: no value, so every card shows a dash, and it says why
        assertNull(EcuSnapshot().rpm)
        assertEquals("нет связи", kg.forester.sg.data.noLinkText(true))
        assertNull(kg.forester.sg.data.noLinkText(false))
    }
}

