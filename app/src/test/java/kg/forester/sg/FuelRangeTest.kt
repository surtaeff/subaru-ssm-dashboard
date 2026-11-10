package kg.forester.sg

import kg.forester.sg.data.Trip
import kg.forester.sg.service.FuelCalc
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelRangeTest {
    private fun trip(id: Long, km: Double, l: Double) = Trip(id = id, startMs = 0, endMs = 0, distanceKm = km, fuelL = l)

    /** Ten trips of 40 km at 11 l/100, newest first. */
    private val past = (10L downTo 1L).map { trip(it, 40.0, 4.4) }

    /** The owner's jam: ten minutes at idle cost the range about 2 km, not 40. */
    @Test fun rangeHoldsInAJam() {
        var cur = trip(0, 12.0, 3.0) // a slow trip so far: 25 l/100
        var left = 7.5
        val before = left / FuelCalc.rangePer100(cur, past) * 100
        val idle = FuelCalc.litersPerHour(EcuSnapshot(rpm = 750, mafGs = 3.2f), 1.0) * 10 / 60.0
        cur = cur.copy(distanceKm = cur.distanceKm + 0.5, fuelL = cur.fuelL + idle)
        left -= idle
        val after = left / FuelCalc.rangePer100(cur, past) * 100
        assertEquals(65.0, before, 3.0)
        assertTrue("range $before → $after", before - after in 0.5..4.0)
    }

    @Test fun rangeAverageWindow() {
        // 12 + 8 × 40 km covers the 300 km: the two oldest trips stay out
        assertEquals((3.0 + 8 * 4.4) / 332 * 100, FuelCalc.rangePer100(trip(0, 12.0, 3.0), past), 1e-9)
        // the current trip's saved row is an older copy of it: counted once
        val saved = listOf(trip(11, 10.0, 2.5)) + past
        assertEquals(FuelCalc.rangePer100(trip(11, 12.0, 3.0), past), FuelCalc.rangePer100(trip(11, 12.0, 3.0), saved), 1e-9)
        // too little driven yet: the usual figure; a junk old trip doesn't run away
        assertEquals(10.0, FuelCalc.rangePer100(trip(0, 5.0, 1.0), emptyList()), 0.0)
        assertEquals(30.0, FuelCalc.rangePer100(null, listOf(trip(1, 50.0, 125.0))), 0.0)
        assertEquals(4.0, FuelCalc.rangePer100(null, listOf(trip(1, 50.0, 0.5))), 0.0)
    }

    /** Ten minutes at idle: the same litres at the plain 4 samples a second and the fast poll's ~12, uneven ones too. */
    @Test fun idleFuelByTimeNotBySamples() {
        val idle = EcuSnapshot(rpm = 750, mafGs = 3.2f, afCorrPct = 2f, afLearnPct = -1f)
        val lh = FuelCalc.litersPerHour(idle, 1.0)
        assertEquals(1.06, lh, 0.05) // an EJ20 at idle: about a litre an hour
        fun integrate(stepsMs: List<Long>): Double {
            var last = 0L
            var now = 1_000_000L
            var litres = 0.0
            val end = now + 600_000
            var i = 0
            while (now < end) {
                litres += lh * FuelCalc.sampleDt(last, now) / 3600.0
                last = now
                now += stepsMs[i++ % stepsMs.size]
            }
            return litres
        }
        val plain = integrate(listOf(250))
        val fast = integrate(listOf(83))
        val uneven = integrate(listOf(60, 110, 70, 95)) // a TCU read in between stops and restarts the stream
        assertEquals(0.177, plain, 0.01)
        assertEquals(plain, fast, plain * 0.01)
        assertEquals(plain, uneven, plain * 0.01)
        // the line silent for half a minute: 2 s counted, not the whole gap at the last flow
        assertEquals(2.0, FuelCalc.sampleDt(1_000, 31_000), 0.0)
        assertEquals(0.0, FuelCalc.sampleDt(0, 31_000), 0.0)
    }

    @Test fun lostAnswersWhileDrivingStillCountKm() {
        // Legacy BP: 4-9 s without an answer while driving on, the engine running at both ends
        assertEquals(6.5, FuelCalc.bridgeDt(1_000, 7_500, wasRunning = true, running = true)!!, 0.0)
        assertEquals(null, FuelCalc.bridgeDt(1_000, 2_500, wasRunning = true, running = true)) // a normal step
        assertEquals(null, FuelCalc.bridgeDt(1_000, 31_000, wasRunning = true, running = true)) // ignition off
        assertEquals(null, FuelCalc.bridgeDt(1_000, 7_500, wasRunning = false, running = true)) // a start
        assertEquals(null, FuelCalc.bridgeDt(0, 7_500, wasRunning = true, running = true))
    }

    @Test fun missingLambdaEnrichesOnBoost() {
        // MAF 120 g/s at full throttle and 80 kPa of boost, no wideband: the real AFR is ~11.5 (50.4 L/h), not 14.7 (39.4)
        val s = EcuSnapshot(rpm = 5500, mafGs = 120f, throttlePct = 100f, boostKpa = 80f, mapKpa = 180f, atmKpa = 100f)
        val lh = FuelCalc.litersPerHour(s, 1.0)
        assertTrue("was $lh", lh > 39.4 * 1.15)
        assertEquals(50.4, lh, 50.4 * 0.15)
    }

    @Test fun missingLambdaCruiseStaysStoich() {
        val cruise = EcuSnapshot(rpm = 2500, mafGs = 20f, throttlePct = 25f, boostKpa = 5f, mapKpa = 90f, atmKpa = 100f)
        assertEquals(20.0 / 14.7 * 3600 / 745, FuelCalc.litersPerHour(cruise, 1.0), 1e-6)
        // full throttle with no boost (a naturally aspirated car, or a light foot on a turbo) stays stoichiometric too
        val na = EcuSnapshot(rpm = 5500, mafGs = 60f, throttlePct = 100f, boostKpa = -5f, mapKpa = 95f, atmKpa = 100f)
        assertEquals(60.0 / 14.7 * 3600 / 745, FuelCalc.litersPerHour(na, 1.0), 1e-6)
    }
}
