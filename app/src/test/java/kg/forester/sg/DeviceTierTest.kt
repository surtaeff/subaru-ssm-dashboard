package kg.forester.sg

import kg.forester.sg.data.EVERY_FRAME
import kg.forester.sg.data.FrameBudgetWatch
import kg.forester.sg.data.FrameReport
import kg.forester.sg.data.RepaintPace
import kg.forester.sg.data.RetryPause
import kg.forester.sg.data.StallWatch
import kg.forester.sg.data.Tier
import kg.forester.sg.data.autoBtRetryPause
import kg.forester.sg.data.frameBudgetMs
import kg.forester.sg.data.effectiveTier
import kg.forester.sg.data.frameTicks
import kg.forester.sg.data.isOldGpu
import kg.forester.sg.data.lookOf
import kg.forester.sg.data.percentile
import kg.forester.sg.data.tierFromRamAndGpu
import kg.forester.sg.data.tierOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierTest {
    private val g52 = "Mali-G52"

    @Test fun twoGigabytesOrLessIsLow() {
        assertEquals(Tier.LOW, tierFromRamAndGpu(2048, g52))
        assertEquals(Tier.LOW, tierFromRamAndGpu(1860, g52))
        assertEquals(Tier.LOW, tierFromRamAndGpu(1024, "Adreno (TM) 660"))
    }

    @Test fun oldGpuIsLowWhateverTheMemory() {
        for (old in listOf("Mali-400 MP", "Mali-450 MP", "Mali-T720", "Mali-T820", "Mali-T860", "Mali-G31 MP2", "Mali-G51",
            "PowerVR SGX 544MP", "PowerVR Rogue G6110", "PowerVR Rogue GE8300", "PowerVR Rogue GM9446")) {
            assertTrue(old, isOldGpu(old))
            assertEquals(old, Tier.LOW, tierFromRamAndGpu(8192, old))
        }
    }

    @Test fun overThreeGigabytesAndANewGpuIsHigh() {
        for (new in listOf(g52, "Mali-G57", "Mali-G610", "Adreno (TM) 610", "PowerVR B-Series BXM-8-256")) {
            assertFalse(new, isOldGpu(new))
            assertEquals(new, Tier.HIGH, tierFromRamAndGpu(3700, new))
        }
    }

    @Test fun inBetweenIsMid() {
        assertEquals(Tier.MID, tierFromRamAndGpu(2049, g52))
        // a "3 GB" unit reports less, and exactly 3 GB is not over 3
        assertEquals(Tier.MID, tierFromRamAndGpu(2850, g52))
        assertEquals(Tier.MID, tierFromRamAndGpu(3072, g52))
    }

    @Test fun unknownGpuIsNotCalledNew() {
        assertEquals(Tier.MID, tierFromRamAndGpu(8192, null))
        assertEquals(Tier.MID, tierFromRamAndGpu(8192, ""))
        assertEquals(Tier.LOW, tierFromRamAndGpu(2048, null))
    }

    @Test fun manualChoiceBeatsTheDetection() {
        assertEquals(Tier.HIGH, tierFromRamAndGpu(1024, "Mali-400 MP", Tier.HIGH))
        assertEquals(Tier.LOW, tierFromRamAndGpu(8192, g52, Tier.LOW))
    }

    @Test fun settingNamesTheChoice() {
        assertNull(tierOf("auto"))
        assertNull(tierOf(""))
        assertEquals(Tier.LOW, tierOf("low"))
        assertEquals(Tier.MID, tierOf("mid"))
        assertEquals(Tier.HIGH, tierOf("high"))
    }

    @Test fun lookOfEachTier() {
        val low = lookOf(Tier.LOW)
        assertEquals(25f, low.mapHz)
        assertEquals(12, low.bars)
        assertFalse(low.nitro)
        val high = lookOf(Tier.HIGH)
        assertEquals(60f, high.mapHz)
        assertEquals(40, high.bars)
        assertTrue(high.nitro)
        // the road and the charts go less often the lower the tier
        val mid = lookOf(Tier.MID)
        assertTrue(low.roadHz < mid.roadHz && mid.roadHz < high.roadHz)
        assertTrue(low.chartHz < mid.chartHz && mid.chartHz < high.chartHz)
        assertTrue(low.chartPaintMs > high.chartPaintMs)
        // a chart may repaint no later than its line, running past the edge by the lag, lasts
        assertTrue(low.chartPaintMs <= 600L)
    }

    @Test fun droppedTiersStopAtLow() {
        assertEquals(Tier.HIGH, effectiveTier(Tier.HIGH, null, 0))
        assertEquals(Tier.MID, effectiveTier(Tier.HIGH, null, 1))
        assertEquals(Tier.LOW, effectiveTier(Tier.HIGH, null, 2))
        assertEquals(Tier.LOW, effectiveTier(Tier.HIGH, null, 5))
    }

    @Test fun manualChoiceIsNotDropped() {
        assertEquals(Tier.HIGH, effectiveTier(Tier.LOW, Tier.HIGH, 3))
    }

    @Test fun percentiles() {
        val v = FloatArray(100) { (it + 1).toFloat() }
        assertEquals(50f, percentile(v, 100, 50), 0f)
        assertEquals(95f, percentile(v, 100, 95), 0f)
        assertEquals(99f, percentile(v, 100, 99), 0f)
        assertEquals(0f, percentile(FloatArray(4), 0, 95), 0f)
    }

    /** [ms] a frame at 60 frames a second, from [from] for [seconds]; true if the watch asked for a drop. */
    private fun FrameBudgetWatch.run(from: Long, seconds: Int, ms: Float): Boolean {
        var drop = false
        for (i in 0 until seconds * 60) if (frame(from + i * 1000L / 60, ms)) drop = true
        return drop
    }

    @Test fun thirtySecondsOverBudgetAskForADrop() {
        val w = FrameBudgetWatch(budgetMs = 16.7f)
        assertFalse(w.run(0, 25, 30f))
        assertTrue(w.run(25_000, 6, 30f))
    }

    @Test fun aGoodStretchStartsTheCountAgain() {
        val w = FrameBudgetWatch(budgetMs = 16.7f)
        assertFalse(w.run(0, 25, 30f))
        assertFalse(w.run(25_000, 6, 8f))
        assertFalse(w.run(31_000, 25, 30f))
    }

    @Test fun onlyTheSlowestFramesDoNotCount() {
        val w = FrameBudgetWatch(budgetMs = 16.7f)
        var drop = false
        // 3 % slow frames: p95 stays in the budget
        for (i in 0 until 60 * 60) if (w.frame(i * 1000L / 60, if (i % 33 == 0) 80f else 8f)) drop = true
        assertFalse(drop)
    }

    @Test fun aParkedScreenIsNotJudged() {
        val w = FrameBudgetWatch(budgetMs = 16.7f)
        var drop = false
        // a slow frame or two a second, as the digits redraw: too few to say anything
        for (s in 0 until 120) if (w.frame(s * 1000L, 40f)) drop = true
        assertFalse(drop)
    }

    @Test fun afterADropTheCountStartsOver() {
        val w = FrameBudgetWatch(budgetMs = 16.7f)
        assertTrue(w.run(0, 31, 30f))
        assertFalse(w.run(31_000, 20, 30f))
    }

    @Test fun retryPauseGrowsAndStops() {
        val p = RetryPause(firstMs = 5_000, maxMs = 60_000)
        assertTrue(p.due(0))
        p.failed(0)
        assertFalse(p.due(4_999))
        assertTrue(p.due(5_000))
        p.failed(5_000)
        assertFalse(p.due(14_999))
        assertTrue(p.due(15_000))
        p.failed(15_000) // 20 s
        p.failed(35_000) // 40 s
        p.failed(75_000) // 60 s, not 80
        assertFalse(p.due(134_999))
        assertTrue(p.due(135_000))
        p.failed(135_000)
        assertTrue(p.due(195_000))
    }

    @Test fun retryPauseStartsOverWhenTold() {
        val p = RetryPause(firstMs = 5_000, maxMs = 60_000)
        p.failed(0); p.failed(5_000); p.failed(15_000)
        p.reset()
        assertTrue(p.due(15_001))
        p.failed(15_001)
        assertTrue(p.due(20_001))
    }

    /** How many of the frames of [dt] seconds in [seconds] are ones of the [hz]. */
    private fun ticks(hz: Float, dt: Float, seconds: Int, startNs: Long = 123_456_789L): Int {
        val step = (dt * 1e9).toLong()
        return (0 until (seconds / dt).toInt()).count { frameTicks(startNs + it * step, startNs + (it + 1) * step, hz) }
    }

    @Test fun aWeakUnitGoesAtItsRate() {
        val n = ticks(hz = 25f, dt = 1f / 60f, seconds = 10)
        assertTrue("$n in 10 s", n in 249..251)
    }

    @Test fun aStrongUnitGoesEveryFrame() {
        assertEquals((10 / (1f / 60f)).toInt(), ticks(hz = EVERY_FRAME, dt = 1f / 60f, seconds = 10))
    }

    @Test fun framesAlreadySlowerThanTheRateAllGo() {
        assertEquals(100, ticks(hz = 20f, dt = 0.1f, seconds = 10))
    }

    @Test fun whatGoesAtOneRateChangesOnTheSameFrames() {
        val a = (0 until 600).filter { frameTicks(it * 16_666_666L, (it + 1) * 16_666_666L, 20f) }
        val b = (0 until 600).filter { frameTicks(it * 16_666_666L, (it + 1) * 16_666_666L, 20f) }
        assertEquals(a, b)
        assertTrue(a.size in 199..201)
    }

    @Test fun aChartPaintsAtOnceWithoutALimit() {
        val p = RepaintPace()
        assertTrue(p.ask(1000L, 0L))
        assertTrue(p.ask(1000L, 0L))
        assertFalse(p.due(1000L, 0L))
    }

    @Test fun aChartPaintsAtMostOncePerItsTimeAndKeepsWhatCameMeanwhile() {
        val p = RepaintPace()
        assertTrue("the first look is drawn", p.ask(1000L, 500L))
        assertFalse(p.ask(1100L, 500L))
        assertFalse(p.due(1499L, 500L))
        assertTrue("what waited goes when its time comes", p.due(1500L, 500L))
        assertFalse("and once", p.due(1600L, 500L))
        assertFalse("the time counts from that paint", p.ask(1700L, 500L))
        assertTrue(p.ask(2000L, 500L))
    }

    @Test fun aChartPaintsWhatWaitedWhenItStops() {
        val p = RepaintPace()
        p.ask(1000L, 500L)
        p.ask(1100L, 500L)
        assertTrue(p.flush(1200L))
        assertFalse(p.flush(1300L))
    }

    @Test fun frameReportGivesPercentilesEachWindow() {
        val lines = ArrayList<String>()
        val r = FrameReport(everyMs = 30_000L) { lines += it }
        for (i in 0 until 1000) r.frame(i * 30L, (i % 100 + 1).toFloat())
        r.frame(30_000L, 1f)
        assertEquals(1, lines.size)
        assertTrue(lines[0], "p50 50.0" in lines[0] && "p95 95.0" in lines[0] && "p99 99.0" in lines[0])
        assertTrue(lines[0], "1000 frames" in lines[0])
        // and the next window starts empty
        r.frame(59_000L, 1f)
        assertEquals(1, lines.size)
    }

    @Test fun frameReportCountsFramesPastWhatItKeeps() {
        val lines = ArrayList<String>()
        val r = FrameReport(everyMs = 30_000L) { lines += it }
        for (i in 0 until 20_000) r.frame(i.toLong(), 8f)
        r.frame(30_000L, 8f)
        assertTrue(lines.toString(), "20000 frames in 30 s" in lines[0])
    }

    @Test fun theBudgetIsTwoRefreshes() {
        assertEquals(33.3f, frameBudgetMs(60f), 0.1f)
        val w = FrameBudgetWatch(frameBudgetMs(60f))
        assertFalse("25 ms a frame at 60 Hz is no reason", w.run(0, 40, 25f))
        assertTrue(w.run(40_000, 40, 40f))
    }

    /** As [run], the frames of a screen the tier changes nothing on. */
    private fun FrameBudgetWatch.runStatic(from: Long, seconds: Int, ms: Float): Boolean {
        var drop = false
        for (i in 0 until seconds * 60) if (frame(from + i * 1000L / 60, ms, tierDraws = false)) drop = true
        return drop
    }

    @Test fun aScreenTheTierDoesNothingForIsNotJudged() {
        val w = FrameBudgetWatch(budgetMs = 33.3f)
        assertFalse(w.runStatic(0, 60, 80f))
    }

    @Test fun aStaticScreenInBetweenStartsTheCountAgain() {
        val w = FrameBudgetWatch(budgetMs = 33.3f)
        assertFalse(w.run(0, 25, 80f))
        assertFalse(w.runStatic(25_000, 10, 80f))
        assertFalse(w.run(35_000, 25, 80f))
    }

    @Test fun aWindowMostlyOfAStaticScreenDoesNotCount() {
        val w = FrameBudgetWatch(budgetMs = 33.3f)
        var drop = false
        for (i in 0 until 60 * 60) if (w.frame(i * 1000L / 60, 80f, tierDraws = i % 4 == 0)) drop = true
        assertFalse(drop)
    }

    @Test fun theWatchSaysWhatP95TookItDown() {
        val w = FrameBudgetWatch(budgetMs = 33.3f)
        assertTrue(w.run(0, 31, 40f))
        assertEquals(40f, w.lastP95, 0f)
    }

    @Test fun aWakeStartsTheCountOver() {
        val w = FrameBudgetWatch(budgetMs = 33.3f)
        assertFalse(w.run(0, 25, 40f))
        w.reset()
        assertFalse("half a minute from here, not from before", w.run(25_000, 25, 40f))
        assertTrue(w.run(50_000, 10, 40f))
    }

    @Test fun autoBluetoothWaitsThirtySecondsAtMost() {
        val p = autoBtRetryPause()
        var now = 0L
        repeat(8) { p.failed(now); now += 200_000L }
        p.failed(now)
        assertFalse(p.due(now + 29_999))
        assertTrue(p.due(now + 30_000))
    }

    @Test fun aChoiceOrAWakeLetsAutoBluetoothTryAtOnceAndFromTheStart() {
        val p = autoBtRetryPause()
        var now = 0L
        repeat(8) { p.failed(now); now += 200_000L }
        p.failed(now)
        p.reset()
        assertTrue("not after the rest of the pause", p.due(now + 1))
        p.failed(now + 1)
        assertFalse(p.due(now + 5_000))
        assertTrue("the pauses grow from the first again", p.due(now + 5_001))
    }

    @Test fun onlyALongMessageIsAStall() {
        val lines = ArrayList<String>()
        val w = StallWatch(50L) { lines += it }
        w.begin(1000L, "short")
        w.end(1049L)
        assertTrue(lines.toString(), lines.isEmpty())
        w.begin(2000L, "Handler (x) {1} y")
        w.end(2050L)
        assertEquals(1, lines.size)
        assertTrue(lines[0], "50 ms" in lines[0] && "Handler (x)" in lines[0])
    }

    @Test fun aStallWatchStartedHalfWaySaysNothing() {
        val lines = ArrayList<String>()
        val w = StallWatch(50L) { lines += it }
        w.end(5000L)
        w.begin(6000L, "a")
        w.end(6010L)
        w.end(9000L)
        assertTrue(lines.toString(), lines.isEmpty())
    }
}
