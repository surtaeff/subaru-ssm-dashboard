package kg.forester.sg

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.graphics.Color
import kg.forester.sg.data.SampleLog
import kg.forester.sg.ui.ChartClock
import kg.forester.sg.ui.ChartLines
import kg.forester.sg.ui.ChartRing
import kg.forester.sg.ui.DigitGate
import kg.forester.sg.ui.RecordTurns
import kg.forester.sg.ui.Smooth
import kg.forester.sg.ui.SmoothLoop
import kg.forester.sg.ui.Stepper
import kg.forester.sg.ui.roadOn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothTest {
    private val second = 1_000_000_000L

    /** Runs [s] for [seconds] at [hz], calling [each] after every step. */
    private fun run(s: Smooth, seconds: Float, hz: Int, each: () -> Unit = {}) {
        repeat((seconds * hz).toInt()) { s.step(1f / hz); each() }
    }

    // ---- the tracker ----

    @Test
    fun aMoveUnderTheQuantumIsNotHandedOut() {
        val s = Smooth(0.5f, 1f)
        s.quantum = 0.01f
        var writes = 0
        var last = s.value.floatValue
        s.sample(0.503f, second)
        run(s, 1f, 60) { if (s.value.floatValue != last) { writes++; last = s.value.floatValue } }
        // comes to rest in one write, not in sixty
        assertEquals(1, writes)
        assertEquals(0.503f, s.value.floatValue, 0.0001f)
    }

    @Test
    fun withAQuantumItStillGetsThere() {
        val s = Smooth(0f, 1f)
        s.quantum = 0.05f
        s.sample(1f, second)
        var writes = 0
        var last = s.value.floatValue
        run(s, 2f, 60) { if (s.value.floatValue != last) { writes++; last = s.value.floatValue } }
        assertEquals(1f, s.value.floatValue, 0.0001f)
        assertTrue(writes in 5..21)
    }

    @Test
    fun noOvershoot() {
        val s = Smooth(0f, 8000f)
        s.sample(2000f, second)
        var top = 0f
        run(s, 3f, 60) { top = maxOf(top, s.value.floatValue) }
        assertTrue("reached $top", top <= 2000f + 0.01f)
        assertEquals(2000f, s.value.floatValue, 0.01f)
    }

    @Test
    fun noOvershootAfterARampStops() {
        val s = Smooth(1000f, 8000f)
        var t = second
        var reading = 1000f
        repeat(20) {
            reading += 100f
            t += second / 10
            s.sample(reading, t)
            run(s, 0.1f, 60)
        }
        var top = 0f
        run(s, 3f, 60) { top = maxOf(top, s.value.floatValue) }
        assertTrue("reached $top, last reading $reading", top <= reading + 0.01f)
    }

    @Test
    fun retargetKeepsVelocityNoKink() {
        val s = Smooth(0f, 8000f)
        s.sample(1000f, second)
        run(s, 0.1f, 60)
        val x = s.value.floatValue
        val v = s.velocity
        assertTrue("moving: $v", v > 0f)
        s.sample(1500f, second + second / 10)
        assertEquals("a new reading moves nothing by itself", x, s.value.floatValue, 0f)
        assertEquals("nor changes the speed", v, s.velocity, 0f)
        // and the first frame after it goes on from there: no step in speed bigger than the new pull can give in a frame
        s.step(1f / 60)
        assertTrue(abs(s.velocity - v) < 1500f * 1.5f)
        assertTrue(s.value.floatValue > x)
    }

    @Test
    fun sameResultAt30And60Hz() {
        val a = Smooth(0f, 8000f)
        val b = Smooth(0f, 8000f)
        val c = Smooth(0f, 8000f)
        a.sample(2000f, second)
        b.sample(2000f, second)
        c.sample(2000f, second)
        run(a, 0.5f, 30)
        run(b, 0.5f, 60)
        run(c, 0.5f, 50)
        assertEquals(a.value.floatValue, b.value.floatValue, 0.5f)
        assertEquals(a.value.floatValue, c.value.floatValue, 0.5f)
        assertTrue(a.value.floatValue in 500f..1999f)
    }

    @Test
    fun aLongFrameIsStable() {
        val s = Smooth(0f, 8000f)
        s.sample(2000f, second)
        s.step(1f)
        assertTrue(s.value.floatValue in 0f..2000f)
    }

    @Test
    fun sleepsWhenSettled() {
        val s = Smooth(0f, 8000f)
        s.sample(1000f, second)
        assertTrue("just got a reading", s.step(1f / 60))
        var frames = 0
        while (s.step(1f / 60) && frames < 600) frames++
        assertTrue("took $frames frames", frames in 5 until 300)
        assertEquals("rests exactly on the reading", 1000f, s.value.floatValue, 0f)
        assertEquals(0f, s.velocity, 0f)
        assertFalse(s.step(1f / 60))
        assertFalse(s.moving)
    }

    @Test
    fun bigJumpTravelsFast() {
        val s = Smooth(0f, 8000f)
        s.sample(2500f, second) // 31 % of the scale
        assertEquals("it does not teleport", 0f, s.value.floatValue, 0f)
        assertTrue(s.moving)
        s.step(1f / 60)
        val first = s.value.floatValue
        assertTrue("on its way: $first", first in 1f..2400f)
        run(s, 0.25f, 60)
        // 0.06 s: most of the way before the next reading of a 4 Hz line
        assertTrue("got to ${s.value.floatValue}", s.value.floatValue > 2200f)
        val slow = Smooth(0f, 8000f)
        slow.sample(2000f, second)
        run(slow, 0.25f + 1f / 60, 60)
        assertTrue(slow.value.floatValue / 2000f < s.value.floatValue / 2500f)
    }

    @Test
    fun aSmallReadingAfterABigJumpGoesOnWithoutAKink() {
        val s = Smooth(0f, 8000f)
        s.sample(4000f, second)
        run(s, 0.05f, 60)
        val x = s.value.floatValue
        val v = s.velocity
        s.sample(4100f, second + second / 4)
        assertEquals(x, s.value.floatValue, 0f)
        assertEquals(v, s.velocity, 0f)
    }

    @Test
    fun noReadingToAReadingSnaps() {
        val s = Smooth(0f, 8000f)
        s.follow(0f, known = false, nowNs = second)
        s.follow(3000f, known = true, nowNs = second + second / 4)
        assertEquals(3000f, s.value.floatValue, 0f)
        assertFalse(s.moving)
        // from then on it eases, and a reading lost again travels back
        s.follow(3500f, known = true, nowNs = second * 2)
        assertEquals(3000f, s.value.floatValue, 0f)
        s.follow(0f, known = false, nowNs = second * 3)
        assertEquals(3000f, s.value.floatValue, 0f)
        assertTrue(s.moving)
    }

    @Test
    fun snapGoesToTheReadingAtOnce() {
        val s = Smooth(0f, 8000f)
        s.sample(1000f, second)
        run(s, 0.05f, 60)
        s.snap()
        assertEquals(1000f, s.value.floatValue, 0f)
        assertEquals(0f, s.velocity, 0f)
        assertFalse(s.moving)
        s.snap(1500f)
        assertEquals(1500f, s.value.floatValue, 0f)
        assertFalse(s.moving)
    }

    @Test
    fun jumpIsMeasuredBetweenReadingsNotFromTheNeedle() {
        // a long pull leaves the needle behind the reading, that is no jump
        val s = Smooth(0f, 8000f)
        var t = second
        var reading = 0f
        repeat(10) {
            reading += 1000f
            t += second / 2
            s.sample(reading, t)
            assertTrue(s.value.floatValue < reading)
        }
    }

    @Test
    fun tauFollowsTheSampleInterval() {
        val fast = Smooth(0f, 8000f)
        val slow = Smooth(0f, 8000f)
        var t = second
        repeat(60) {
            t += second / 12
            fast.sample(it.toFloat(), t)
        }
        t = second
        repeat(60) {
            t += second / 2
            slow.sample(it.toFloat(), t)
        }
        // 0.45 of the interval, within 0.06–0.25 s
        assertEquals(0.06f, fast.tau, 0.001f)
        assertEquals(0.225f, slow.tau, 0.002f)
        val huge = Smooth(0f, 8000f)
        t = second
        repeat(60) { t += 5 * second; huge.sample(it.toFloat(), t) }
        assertEquals(0.25f, huge.tau, 0.001f)
    }

    @Test
    fun aFixedTauIgnoresTheInterval() {
        val s = Smooth(0f, tau = 0.1f)
        var t = second
        repeat(60) { t += second / 2; s.sample(it.toFloat(), t) }
        assertEquals(0.1f, s.tau, 0f)
    }

    @Test
    fun withoutAScaleNothingSnaps() {
        val s = Smooth(0f, tau = 0.06f)
        s.sample(1f, second)
        assertEquals(0f, s.value.floatValue, 0f)
        assertTrue(s.moving)
    }

    // ---- the one loop ----

    private class CountingClock : MonotonicFrameClock {
        var frames = 0
        var t = 1_000_000_000L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            frames++
            t += 16_666_667L
            yield()
            return onFrame(t)
        }
    }

    @Test
    fun theLoopAsksForNoFrameWhileNothingMoves() = runBlocking {
        val clock = CountingClock()
        val loop = SmoothLoop()
        val job = launch(clock, CoroutineStart.UNDISPATCHED) { loop.run() }
        repeat(10) { yield() }
        assertEquals("nothing to move", 0, clock.frames)

        val s = Smooth(0f, 8000f)
        s.sample(1000f, second)
        loop.wake(s)
        var guard = 0
        while (s.moving && guard++ < 2000) yield()
        assertFalse("settled", s.moving)
        val spent = clock.frames
        assertTrue("took $spent frames", spent in 2..600)
        repeat(50) { yield() }
        assertTrue("asleep again: ${clock.frames} after $spent", clock.frames <= spent + 1)

        // and it wakes for the next reading
        s.sample(1200f, second * 2)
        loop.wake(s)
        guard = 0
        while (s.moving && guard++ < 2000) yield()
        assertEquals(1200f, s.value.floatValue, 0f)
        job.cancel()
    }

    @Test
    fun theLoopDrivesManyWithOneRequestPerFrame() = runBlocking {
        val clock = CountingClock()
        val loop = SmoothLoop()
        val seen = IntArray(3)
        var left = 10
        val job = launch(clock, CoroutineStart.UNDISPATCHED) { loop.run() }
        repeat(3) { i ->
            loop.wake(Stepper { _ -> seen[i]++; if (i == 0) --left > 0 else left > 0 })
        }
        var guard = 0
        while (left > 0 && guard++ < 2000) yield()
        repeat(20) { yield() }
        assertTrue("one frame request for all three: ${clock.frames}", clock.frames <= 12)
        // the first says stop on the tenth frame, the other two hear of it on the next
        assertEquals(10, seen[0])
        assertEquals(11, seen[1])
        assertEquals(11, seen[2])
        job.cancel()
    }

    @Test
    fun aDroppedStepperIsNotCalled() = runBlocking {
        val clock = CountingClock()
        val loop = SmoothLoop()
        var calls = 0
        val st = Stepper { _ -> calls++; true }
        val job = launch(clock, CoroutineStart.UNDISPATCHED) { loop.run() }
        loop.wake(st)
        repeat(5) { yield() }
        loop.drop(st)
        val had = calls
        val frames = clock.frames
        repeat(10) { yield() }
        assertTrue(calls <= had + 1)
        assertTrue(clock.frames <= frames + 1)
        job.cancel()
    }

    private class ManualClock(var t: Long) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            t += 16_000_000L
            yield()
            return onFrame(t)
        }
    }

    @Test
    fun aWakeCountsFromTheWakeNotFromZeroAndNeverBackwards() = runBlocking {
        val clock = ManualClock(5 * second)
        var now = 5 * second
        val loop = SmoothLoop { now }
        val dts = ArrayList<Float>()
        val job = launch(clock, CoroutineStart.UNDISPATCHED) { loop.run() }
        var left = 3
        // the wake is 4 ms before the next frame's stamp is taken: the first step has real time in it
        now = clock.t + 12_000_000L
        loop.wake(Stepper { dt -> dts += dt; --left > 0 })
        var guard = 0
        while (left > 0 && guard++ < 200) yield()
        assertEquals(3, dts.size)
        assertEquals(0.004f, dts[0], 0.0005f)
        assertEquals(0.016f, dts[1], 0.0005f)

        // a frame stamped before the wake: no time, not negative time
        dts.clear()
        left = 1
        now = clock.t + 1_000_000_000L
        loop.wake(Stepper { dt -> dts += dt; --left > 0 })
        guard = 0
        while (left > 0 && guard++ < 200) yield()
        assertEquals(0f, dts[0], 0f)
        job.cancel()
    }

    @Test
    fun backFromTheBackgroundEveryTrackerIsAtItsReading() {
        var now = 5 * second
        val loop = SmoothLoop { now }
        val moving = Smooth(0f, 200f)
        val asleep = Smooth(0f, 200f)
        loop.register(moving)
        loop.register(asleep)
        moving.sample(30f, now)
        loop.wake(moving)
        asleep.sample(40f, now) // came in the background, its first frame never was
        assertFalse(loop.returning())
        loop.onReturn()
        assertEquals(30f, moving.value.floatValue, 0f)
        assertEquals(40f, asleep.value.floatValue, 0f)
        assertFalse(moving.moving)
        // a reading held up by the stopped composition comes a moment later: it jumps too, later ones ease
        assertTrue(loop.returning())
        now += 2 * second
        assertFalse(loop.returning())
        // a tracker gone from the screen is left alone
        loop.unregister(asleep)
        asleep.sample(50f, now)
        loop.onReturn()
        assertEquals(40f, asleep.value.floatValue, 0f)
    }

    // ---- the digits ----

    @Test
    fun digitsChangeAtMostFourOrFiveTimesASecond() {
        val g = DigitGate("1000")
        var t = 0L
        var changes = 0
        var last = g.shown
        var n = 1000
        // 12 Hz of values far apart for two seconds, a frame at a time
        while (t < 2000) {
            n += 50
            g.offer(n.toString(), t)
            if (g.shown != last) { changes++; last = g.shown }
            t += 83
        }
        assertTrue("$changes changes in 2 s", changes in 8..10)
    }

    @Test
    fun digitsSayWhenToLookAgain() {
        val g = DigitGate("1000")
        assertEquals(0L, g.offer("1500", 0))
        assertEquals("1500", g.shown)
        val wait = g.offer("1600", 50)
        assertEquals("1500", g.shown)
        assertEquals(170L, wait)
        // the last value is shown even if no other comes after it
        assertEquals(0L, g.offer("1600", 50 + wait))
        assertEquals("1600", g.shown)
    }

    @Test
    fun aNumberFlickingBetweenTwoNeighboursStaysPut() {
        val g = DigitGate("14,7")
        var t = 1000L
        repeat(40) {
            g.offer(if (it % 2 == 0) "14,8" else "14,7", t)
            assertEquals("14,7", g.shown)
            t += 100
        }
    }

    @Test
    fun aNeighbourThatStaysIsShown() {
        val g = DigitGate("14,7")
        val wait = g.offer("14,8", 1000)
        assertEquals("14,7", g.shown)
        assertTrue(wait in 1..300)
        assertEquals(0L, g.offer("14,8", 1000 + wait))
        assertEquals("14,8", g.shown)
    }

    @Test
    fun aNumberMovingFurtherIsNotHeldBack() {
        val g = DigitGate("14,7")
        g.offer("14,9", 1000)
        assertEquals("14,9", g.shown)
        val h = DigitGate("880")
        h.offer("905", 1000)
        assertEquals("905", h.shown)
    }

    @Test
    fun noReadingAndWordsAreShownAtOnce() {
        val g = DigitGate("880")
        g.offer("900", 1000)
        g.offer("—", 1010)
        assertEquals("—", g.shown)
        g.offer("905", 1020)
        assertEquals("905", g.shown)
        val h = DigitGate("D3")
        h.offer("D4", 5)
        h.offer("N", 10)
        assertEquals("N", h.shown)
    }

    @Test
    fun differentDecimalsAreNotNeighbours() {
        val g = DigitGate("9,9")
        g.offer("10", 1000)
        assertEquals("10", g.shown)
    }

    // ---- the chart's ring ----

    @Test
    fun ringKeepsOrderAcrossTheWrap() {
        val r = ChartRing(4)
        for (i in 1..6) r.add(i * 100L, i.toFloat())
        assertEquals(4, r.size)
        assertEquals(300L, r.timeAt(0))
        assertEquals(3f, r.valueAt(0), 0f)
        assertEquals(600L, r.timeAt(3))
        assertEquals(6f, r.valueAt(3), 0f)
        assertEquals(600L, r.newestMs)
        assertEquals(6f, r.last, 0f)
    }

    @Test
    fun ringXFollowsTimeNotIndex() {
        val r = ChartRing(8)
        // unevenly spaced: 0, 100, 600, 1000 ms
        r.add(0, 1f); r.add(100, 2f); r.add(600, 3f); r.add(1000, 4f)
        val out = FloatArray(16)
        val runs = IntArray(16)
        val n = r.project(out, runs, atMs = 1000, windowMs = 1000, width = 100f) { it * 10f }
        assertEquals(1, n)
        assertEquals(0, runs[0])
        assertEquals(4, runs[1])
        assertArrayEquals(floatArrayOf(0f, 10f, 10f, 20f, 60f, 30f, 100f, 40f), out.copyOf(8), 0.001f)
    }

    @Test
    fun ringLeavesOutWhatIsPastTheWindowButKeepsTheLineToTheEdge() {
        val r = ChartRing(8)
        r.add(0, 1f); r.add(100, 2f); r.add(1000, 3f); r.add(1100, 4f)
        val out = FloatArray(16)
        val runs = IntArray(16)
        val n = r.project(out, runs, atMs = 1100, windowMs = 500, width = 100f) { it }
        assertEquals(1, n)
        // the last point left of the window stays, so the line reaches the edge
        assertEquals(0, runs[0])
        assertEquals(3, runs[1])
        assertEquals(100f - (1100f - 100f) / 500f * 100f, out[0], 0.001f)
        assertEquals(80f, out[2], 0.001f)
        assertEquals(100f, out[4], 0.001f)
    }

    @Test
    fun ringBreaksTheLineWhereThereIsNoReading() {
        val r = ChartRing(8)
        r.add(0, 1f); r.add(100, 2f); r.add(200, Float.NaN); r.add(300, 4f); r.add(400, 5f); r.add(500, Float.NaN); r.add(600, 7f)
        val out = FloatArray(16)
        val runs = IntArray(16)
        val n = r.project(out, runs, atMs = 600, windowMs = 600, width = 600f) { it }
        assertEquals(3, n)
        assertEquals(0, runs[0]); assertEquals(2, runs[1])
        assertEquals(2, runs[2]); assertEquals(2, runs[3])
        assertEquals(4, runs[4]); assertEquals(1, runs[5])
    }

    @Test
    fun ringReadsItsValueAtATime() {
        val r = ChartRing(8)
        r.add(100, 10f); r.add(300, 30f); r.add(400, Float.NaN); r.add(500, 50f)
        assertEquals(20f, r.at(200), 0.001f)
        assertEquals("past the newest: the newest", 50f, r.at(900), 0f)
        assertEquals("before the oldest: nothing", true, r.at(50).isNaN())
        assertEquals("beside a gap: nothing", true, r.at(350).isNaN())
    }

    @Test
    fun ringKnowsWhenItsLineIsFlat() {
        val r = ChartRing(8)
        r.add(0, 5f)
        r.add(100, 5f)
        assertEquals(0L, r.lastChangeMs)
        assertTrue(r.flat(100, windowMs = 50))
        r.add(200, 6f)
        assertEquals(200L, r.lastChangeMs)
        assertFalse(r.flat(250, windowMs = 500))
        assertTrue(r.flat(1000, windowMs = 500))
        r.add(1100, Float.NaN)
        assertEquals(1100L, r.lastChangeMs)
        r.add(1200, Float.NaN)
        assertEquals("no reading is still no reading", 1100L, r.lastChangeMs)
    }

    @Test
    fun ringBreaksTheLineOverALongSilence() {
        val r = ChartRing(8)
        r.add(0, 1f); r.add(1000, 2f); r.add(4000, 3f); r.add(5000, 4f)
        val out = FloatArray(16)
        val runs = IntArray(16)
        val n = r.project(out, runs, atMs = 5000, windowMs = 5000, width = 500f) { it }
        assertEquals(2, n)
        assertEquals(0, runs[0]); assertEquals(2, runs[1])
        assertEquals(2, runs[2]); assertEquals(2, runs[3])
        assertTrue("nothing to read across it", r.at(2500).isNaN())
        assertEquals(1.5f, r.at(500), 0.001f)
        // the usual gap of a slow line is no silence
        val slow = ChartRing(8)
        slow.add(0, 1f); slow.add(2400, 2f)
        assertEquals(1, slow.project(out, runs, 2400, 5000, 500f) { it })
    }

    @Test
    fun aPointLeftOfTheWindowIsNotKeptAcrossABreak() {
        val r = ChartRing(8)
        r.add(0, 1f); r.add(9000, 2f); r.add(9500, 3f)
        val out = FloatArray(16)
        val runs = IntArray(16)
        val n = r.project(out, runs, atMs = 9500, windowMs = 1000, width = 100f) { it }
        assertEquals(1, n)
        assertEquals(2, runs[1])
        assertEquals(50f, out[0], 0.001f)
    }

    @Test
    fun aLongerBreakCanBeAsked() {
        val r = ChartRing(8)
        r.add(0, 1f); r.add(5000, 2f)
        assertEquals(1, r.project(FloatArray(16), IntArray(16), 5000, 10_000, 100f, breakMs = 6000) { it })
    }

    @Test
    fun ringClears() {
        val r = ChartRing(4)
        r.add(100, 1f)
        r.clear()
        assertEquals(0, r.size)
        assertTrue(r.last.isNaN())
        assertEquals(0, r.project(FloatArray(8), IntArray(8), 100, 100, 10f) { it })
    }

    @Test
    fun theChartsTimeRunsOnFromTheReadingsWithoutJumps() {
        val c = ChartClock()
        c.onSample(10_000, 0.0)
        assertEquals(10_000L, c.nowMs(0.0))
        assertEquals(10_500L, c.nowMs(0.5))
        // a reading stamped a little off the frames' clock: no jump
        c.onSample(10_480, 0.5)
        assertEquals(10_500L, c.nowMs(0.5))
        // the loop slept or the readings stopped for a long time: start again from the reading
        c.onSample(60_000, 1.0)
        assertEquals(60_000L, c.nowMs(1.0))
    }

    @Test
    fun theChartsTimeDoesNotHopInALongSession() {
        val c = ChartClock()
        val day = 86_400.0
        c.onSample(1_000_000L, day)
        assertEquals(1_000_000L + 16L, c.nowMs(day + 0.0167))
    }

    @Test
    fun aSlowerPictureMovesOnlyOnItsFramesAndStillArrives() {
        val s = Smooth(0f, tau = 0.05f, shownHz = { 25f })
        s.sample(1f, 1L)
        // these steps are outside the frame loop: none of them is one of the 25
        repeat(5) { s.step(0.016f) }
        assertEquals("nothing shown between its frames", 0f, s.value.floatValue, 0f)
        var left = 10_000
        while (s.step(0.016f) && left-- > 0) { /* on to the reading */ }
        assertEquals("at rest it shows where it is", 1f, s.value.floatValue, 0f)
    }

    // ---- the running chart ----

    private class Item(val wallMs: Long, val v: Float?)

    private fun chart(windowMs: Long = 1000L) = ChartLines(listOf(ChartLines.Line("x", Color.Red, 64)), windowMs, 100L)

    private val read = listOf<(Item) -> Float?>({ it.v })

    @Test
    fun aFlatChartStandsStillWithMoreReadings() {
        var now = 10_000L
        val log = SampleLog<Item>(64) { now }
        val c = chart()
        repeat(20) { log.add(Item(now, 5f)); now += 100 }
        c.take(log, read)
        assertEquals("the first look at a flat line is drawn", 1, c.samples.intValue)
        val edge = c.edgeMs()
        repeat(50) {
            log.add(Item(now, 5f))
            now += 100
            c.take(log, read)
            c.step(0.1f)
        }
        assertEquals("no redraw for it", 1, c.samples.intValue)
        assertEquals("nor does the picture move in a redraw for another reason", edge, c.edgeMs())
        // and it comes alive with the first change
        log.add(Item(now, 6f))
        c.take(log, read)
        assertEquals(2, c.samples.intValue)
        assertEquals(now - 100L, c.edgeMs())
    }

    @Test
    fun aRunningChartStopsOnTheLastPictureWhenItTurnsFlat() {
        var now = 10_000L
        val log = SampleLog<Item>(64) { now }
        val c = chart()
        log.add(Item(now, 1f))
        c.take(log, read)
        log.add(Item(now + 100, 2f)); now += 100
        c.take(log, read)
        val before = c.samples.intValue
        repeat(15) {
            now += 100
            log.add(Item(now, 2f))
            c.take(log, read)
            c.step(0.1f)
        }
        val settled = c.samples.intValue
        assertTrue(settled > before)
        val edge = c.edgeMs()
        repeat(5) {
            now += 100
            log.add(Item(now, 2f))
            c.take(log, read)
        }
        assertEquals(settled, c.samples.intValue)
        assertEquals(edge, c.edgeMs())
    }

    @Test
    fun aBackwardJumpOfTheWallClockDoesNotFreezeTheChart() {
        var now = 500_000L
        val log = SampleLog<Item>(64) { now }
        val c = chart()
        log.add(Item(1_700_000_000_000L, 1f))
        c.take(log, read)
        now += 200
        log.add(Item(1_700_000_000_200L, 2f))
        c.take(log, read)
        assertEquals(2, c.defs[0].ring.size)
        // GPS or the user set the clock an hour back; the elapsed clock does not care
        now += 200
        log.add(Item(1_699_999_996_400L, 3f))
        c.take(log, read)
        assertEquals(3, c.defs[0].ring.size)
        assertEquals(3f, c.defs[0].ring.last, 0f)
        assertEquals(500_400L, c.defs[0].ring.newestMs)
    }

    @Test
    fun theLogGivesEachReadingItsOwnTime() {
        val log = SampleLog<Item>(8) { 1000L }
        log.add(Item(0, 1f)); log.add(Item(0, 2f))
        val seen = ArrayList<Long>()
        log.since(-1L) { t, _ -> seen += t }
        assertEquals(listOf(1000L, 1001L), seen)
        seen.clear()
        log.since(1000L) { t, _ -> seen += t }
        assertEquals(listOf(1001L), seen)
    }

    @Test
    fun aLongFrameMovesAChartAQuarterSecondAtMost() {
        var now = 10_000L
        val log = SampleLog<Item>(64) { now }
        val c = chart()
        log.add(Item(now, 1f)); now += 100; log.add(Item(now, 2f))
        c.take(log, read)
        val edge = c.edgeMs()
        c.step(30f)
        assertEquals(edge + 250L, c.edgeMs())
    }

    @Test
    fun oneChartARecordAFrameInTurn() {
        val q = RecordTurns<String>()
        q.ask("a"); q.ask("b"); q.ask("c")
        assertTrue(q.pending)
        assertEquals("a", q.next())
        assertEquals("b", q.next())
        assertEquals("c", q.next())
        assertEquals(null, q.next())
        assertFalse(q.pending)
    }

    @Test
    fun aChartAlreadyWaitingKeepsItsPlace() {
        val q = RecordTurns<String>()
        q.ask("a"); q.ask("b"); q.ask("a")
        assertEquals("a", q.next())
        assertEquals("b", q.next())
        assertEquals(null, q.next())
    }

    @Test
    fun noChartWaitsLongerThanTheOthersAhead() {
        // seven charts asking at each reading, a reading every 12 frames
        val q = RecordTurns<Int>()
        val askedAt = IntArray(7) { -1 }
        var worst = 0
        for (frame in 0 until 600) {
            if (frame % 12 == 0) for (c in 0 until 7) { if (askedAt[c] < 0) askedAt[c] = frame; q.ask(c) }
            val c = q.next() ?: continue
            worst = maxOf(worst, frame - askedAt[c])
            askedAt[c] = -1
        }
        assertEquals(6, worst)
    }

    @Test
    fun aChartGoneFromTheScreenLosesItsTurn() {
        val q = RecordTurns<String>()
        q.ask("a"); q.ask("b")
        q.drop("a")
        assertEquals("b", q.next())
        assertFalse(q.pending)
    }

    @Test
    fun theRoadStaysUntilTheCarHasReallyStopped() {
        assertFalse(roadOn(false, 2))
        assertTrue(roadOn(false, 3))
        assertTrue(roadOn(true, 2))
        assertFalse(roadOn(true, 1))
    }
}
