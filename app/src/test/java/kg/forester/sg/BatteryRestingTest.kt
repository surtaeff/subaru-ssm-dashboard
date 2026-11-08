package kg.forester.sg

import kg.forester.sg.service.BatteryMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The resting voltage of a daily start: the app connects a few seconds after the key and the driver cranks a second
 * later, so the 5 s of 0 rpm the regular reading needs never come and the АКБ screen kept a reading days old.
 */
class BatteryRestingTest {
    /** 0 rpm samples every 250 ms (plain polling) from [t0]. */
    private fun quarter(t0: Long, vararg v: Float) = v.mapIndexed { i, x -> t0 + i * 250L to x }

    @Test
    fun quickStartGivesAReading() {
        // 1 s of ignition seen before the starter: too short for the 5 s reading, enough before the crank
        val w = quarter(10_000, 12.52f, 12.48f, 12.52f, 12.44f)
        val r = BatteryMonitor.beforeCrank(w, crankMinV = 10.1f)!!
        assertEquals(12.52f, r.second, 0.001f)
        assertEquals(10_750L, r.first)
    }

    @Test
    fun starterSagAtZeroRpmIsLeftOut() {
        // an ECU that reads 0 rpm while the starter turns: the sagging samples are the crank, not the rest
        val w = quarter(0, 12.5f, 12.46f, 10.4f, 10.0f, 10.2f)
        val r = BatteryMonitor.beforeCrank(w, crankMinV = 10.0f)!!
        assertEquals(12.5f, r.second, 0.001f)
        assertEquals(250L, r.first)
    }

    @Test
    fun connectedWhileCrankingGivesNone() {
        // all samples during the starter (steady at ~10 V): close to the crank minimum, no resting reading
        assertNull(BatteryMonitor.beforeCrank(quarter(0, 10.1f, 10.0f, 10.15f), crankMinV = 10.0f))
    }

    @Test
    fun oneSampleIsNotEnough() {
        assertNull(BatteryMonitor.beforeCrank(quarter(0, 12.5f), crankMinV = 10.0f))
        assertNull(BatteryMonitor.beforeCrank(quarter(0, 12.5f, 10.2f), crankMinV = 10.0f))
        assertNull(BatteryMonitor.beforeCrank(emptyList(), crankMinV = 10.0f))
    }

    @Test
    fun noDipSeenGivesNone() {
        // the crank's dip fell between two samples: nothing tells the rest from the crank, keep the old reading
        assertNull(BatteryMonitor.beforeCrank(quarter(0, 12.5f, 12.48f), crankMinV = 12.3f))
    }
}
