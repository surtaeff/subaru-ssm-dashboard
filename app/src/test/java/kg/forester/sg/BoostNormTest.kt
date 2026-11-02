package kg.forester.sg

import kg.forester.sg.data.BoostNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostNormTest {
    private var t = 0L
    private val warns = ArrayList<BoostNorm.Over>()

    /** A pull: [peak] kPa for [ms], then a lift. 100 ms samples unless told otherwise. */
    private fun BoostNorm.pull(peak: Float, ms: Long = 3000L, baro: Float? = 100f, step: Long = 100L) {
        var left = ms
        while (left > 0) { sample(t, peak, baro, 3500)?.let { warns += it }; t += step; left -= step }
        sample(t, 90f, baro, 3000)?.let { warns += it }; t += step
    }

    @Test fun aShortRiseIsNoPull() {
        val n = BoostNorm()
        n.pull(160f, ms = 1400L)
        assertEquals(0, n.count)
        n.pull(160f, ms = 1600L)
        assertEquals(1, n.count)
    }

    @Test fun belowThirtyOverBaroIsNoPull() {
        val n = BoostNorm()
        n.pull(129f)
        assertEquals(0, n.count)
        n.pull(131f)
        assertEquals(1, n.count)
    }

    @Test fun recordsTheHighestHeldForASecond() {
        val n = BoostNorm()
        // 150 for a while, a spike of 180 for only 300 ms, then 160 for 1.2 s
        for (i in 0 until 15) { n.sample(t, 150f, 100f, 3500); t += 100 }
        for (i in 0 until 3) { n.sample(t, 180f, 100f, 3500); t += 100 }
        for (i in 0 until 12) { n.sample(t, 160f, 100f, 3500); t += 100 }
        n.sample(t, 90f, 100f, 3000)
        assertEquals(listOf(160f), n.pulls)
    }

    @Test fun normIsTheEightiethPercentileFromFivePulls() {
        val n = BoostNorm()
        listOf(150f, 152f, 154f, 156f).forEach { n.pull(it) }
        assertNull(n.norm)
        n.pull(158f)
        assertEquals(156f, n.norm!!, 0.01f)
    }

    @Test fun keepsTheLastTen() {
        val n = BoostNorm()
        repeat(14) { n.pull(140f + it) }
        assertEquals(10, n.count)
        assertEquals(144f, n.pulls.first(), 0.01f)
    }

    @Test fun noWarningBeforeFivePulls() {
        val n = BoostNorm()
        repeat(4) { n.pull(150f) }
        n.pull(240f)
        assertTrue(warns.isEmpty())
        assertEquals(5, n.count)
    }

    @Test fun warnsAboveNormPlus25HeldASecondWithBothNumbers() {
        val n = BoostNorm()
        repeat(5) { n.pull(150f) }
        // 174 is under norm + 25, nothing
        n.pull(174f)
        assertTrue(warns.isEmpty())
        // a short spike over: nothing
        n.pull(150f, ms = 1000L); n.pull(200f, ms = 800L)
        assertTrue(warns.isEmpty())
        n.pull(190f)
        assertTrue(warns.isNotEmpty())
        assertEquals(190f, warns.first().mapKpa, 0.01f)
        assertEquals(150f, warns.first().normKpa, 0.01f)
    }

    @Test fun thinAirDoesNotAlarmBecauseAbsolutePressureIsHeld() {
        val n = BoostNorm()
        repeat(10) { n.pull(170f, baro = 100f) }
        // the mountains: gauge pressure grows by 25 kPa, the wastegate holds the absolute
        repeat(5) { n.pull(170f, baro = 75f) }
        assertTrue(warns.isEmpty())
        assertEquals(10, n.count)
    }

    @Test fun aRetunedCarStopsWarningAfterAFewPulls() {
        val n = BoostNorm()
        repeat(10) { n.pull(150f) }
        var warnedPulls = 0
        repeat(8) {
            val before = warns.size
            n.pull(200f)
            if (warns.size > before) warnedPulls++
        }
        assertEquals("warned pulls are recorded, the third moves the norm", 3, warnedPulls)
        assertEquals(200f, n.norm!!, 0.01f)
    }

    @Test fun anotherVinStartsOver() {
        val n = BoostNorm()
        n.forCar("VIN1")
        repeat(6) { n.pull(150f) }
        n.forCar("")
        assertEquals("no VIN says nothing of a change", 6, n.count)
        n.forCar("VIN1")
        assertEquals(6, n.count)
        n.forCar("VIN2")
        assertEquals(0, n.count)
        assertNull(n.norm)
    }

    @Test fun survivesAStore() {
        val n = BoostNorm()
        n.forCar("VIN1")
        repeat(6) { n.pull(150f + it) }
        val m = BoostNorm.parse(n.format())
        assertEquals(n.pulls, m.pulls)
        assertEquals("VIN1", m.car)
        assertEquals(0, BoostNorm.parse("garbage\nx,y").count)
        assertEquals(0, BoostNorm.parse("").count)
    }

    @Test fun junkIsNoEvidence() {
        val n = BoostNorm()
        repeat(5) { n.pull(150f) }
        // the 0xFF byte, a long run of it: out of the plausible range
        n.pull(255f, ms = 4000L)
        // zero and below the range
        n.pull(5f, ms = 4000L)
        // revs under the limit
        repeat(40) { n.sample(t, 200f, 100f, 1000)?.let { warns += it }; t += 100 }
        n.sample(t, 90f, 100f, 3000); t += 100
        // no MAP at all
        repeat(40) { n.sample(t, null, 100f, 3500)?.let { warns += it }; t += 100 }
        assertTrue(warns.isEmpty())
        assertEquals(5, n.count)
    }

    @Test fun oneJunkSampleInAPullLeavesThePull() {
        val n = BoostNorm()
        repeat(10) { n.sample(t, 150f, 100f, 3500); t += 100 }
        n.sample(t, 255f, 100f, 3500); t += 100
        repeat(10) { n.sample(t, 150f, 100f, 3500); t += 100 }
        n.sample(t, 90f, 100f, 3000)
        assertEquals(listOf(150f), n.pulls)
    }

    @Test fun aBadBaroFallsBackToTheDefault() {
        val n = BoostNorm()
        n.pull(135f, baro = 20f)
        assertEquals(1, n.count)
        n.pull(135f, baro = null)
        assertEquals(2, n.count)
        // 101 + 30 is the line with no good baro
        n.pull(128f, baro = 200f)
        assertEquals(2, n.count)
    }

    @Test fun aSlowBusStillCounts() {
        // K-line, a sample every 800 ms
        val n = BoostNorm()
        n.pull(150f, ms = 4000L, step = 800L)
        assertEquals(listOf(150f), n.pulls)
    }

    @Test fun aLostLinkEndsThePull() {
        val n = BoostNorm()
        n.sample(t, 150f, 100f, 3500); t += 100
        n.sample(t, 150f, 100f, 3500); t += 60_000
        n.sample(t, 150f, 100f, 3500); t += 100
        n.sample(t, 90f, 100f, 3000)
        assertEquals("two scraps of a pull with a hole between are no 1.5 s", 0, n.count)
        assertNotNull(n)
    }

    @Test fun onePullOnlyOnceEvenWithoutALift() {
        val n = BoostNorm()
        repeat(100) { n.sample(t, 150f, 100f, 3500); t += 100 }
        assertEquals("still in the pull", 0, n.count)
        n.sample(t, 90f, 100f, 3000)
        assertEquals(1, n.count)
    }

    // ---- the route: Analyzer, the same id and repeat as its other overboost warning

    private fun analyzer(store: kg.forester.sg.service.MemoryRuleStore, raised: MutableList<Triple<String, String, Long>>, level: MutableList<kg.forester.sg.service.Alerts.Level>) =
        kg.forester.sg.service.Analyzer(store, { id, text, l, cool -> raised += Triple(id, text, cool); level += l })

    private fun snap(ms: Long, map: Float) = kg.forester.sg.ssm.EcuSnapshot(timeMs = ms, rpm = 3500, speedKmh = 80, coolantC = 90, mapKpa = map, atmKpa = 100f, throttlePct = 95f)

    private fun drive(a: kg.forester.sg.service.Analyzer, car: String?, peak: Float, turbo: Boolean = true) {
        repeat(30) { a.process(kg.forester.sg.service.RuleInput(snap(t, peak), turbo = turbo, boostNormCar = car)); t += 100 }
        repeat(10) { a.process(kg.forester.sg.service.RuleInput(snap(t, 90f), turbo = turbo, boostNormCar = car)); t += 100 }
    }

    @Test fun theAnalyzerWarnsOnceLearnedAndKeepsTheLearningInItsStore() {
        val store = kg.forester.sg.service.MemoryRuleStore()
        val raised = ArrayList<Triple<String, String, Long>>()
        val level = ArrayList<kg.forester.sg.service.Alerts.Level>()
        t = 1_000_000L
        val a = analyzer(store, raised, level)
        repeat(5) { drive(a, "VIN1", 150f) }
        assertTrue(raised.none { it.first == "overboost" })
        assertTrue(store.boostNorm, store.boostNorm.startsWith("VIN1\n"))
        drive(a, "VIN1", 190f)
        val w = raised.first { it.first == "overboost" }
        assertTrue(w.second, w.second.contains("190") && w.second.contains("150") && w.second.contains("обычн"))
        assertEquals(10 * 60_000L, w.third)
        assertEquals(kg.forester.sg.service.Alerts.Level.WARN, level[raised.indexOf(w)])
        // a new Analyzer (a new start of the app) knows the car
        val raised2 = ArrayList<Triple<String, String, Long>>()
        drive(analyzer(store, raised2, ArrayList()), "VIN1", 195f)
        assertTrue(raised2.any { it.first == "overboost" })
    }

    @Test fun onlyWhenTheGuardNamesTheCar() {
        val store = kg.forester.sg.service.MemoryRuleStore()
        val raised = ArrayList<Triple<String, String, Long>>()
        t = 1_000_000L
        val a = analyzer(store, raised, ArrayList())
        repeat(6) { drive(a, null, 150f) }
        drive(a, null, 240f)
        assertTrue(raised.none { it.first == "overboost" })
        assertEquals("", store.boostNorm)
    }
}
