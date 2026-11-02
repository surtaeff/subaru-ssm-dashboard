package kg.forester.sg

import kg.forester.sg.service.GapLog
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ui.CameraAnchor
import kg.forester.sg.ui.MapCamera
import kg.forester.sg.ui.lastRouteIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.TileSystem

class CameraPredictTest {
    private val lat0 = 43.0
    private val lon0 = 74.0
    private val metresPerDeg = 111_319.49

    private fun north(c: MapCamera) = (c.lat - lat0) * metresPerDeg
    private fun east(c: MapCamera) = (c.lon - lon0) * metresPerDeg * cos(lat0 * PI / 180)
    private fun latAt(northM: Double) = lat0 + northM / metresPerDeg
    private fun lonAt(eastM: Double) = lon0 + eastM / (metresPerDeg * cos(lat0 * PI / 180))

    private fun run(c: MapCamera, seconds: Float, hz: Int = 30, each: () -> Unit = {}) {
        repeat((seconds * hz).toInt()) { c.step(1f / hz); each() }
    }

    /** Standing at the start, the course north. */
    private fun camera(kmh: Float = 36f, bearing: Float? = 0f) = MapCamera().apply { fix(lat0, lon0, kmh, bearing, 0f) }

    // ---- the prediction ----

    @Test
    fun predictsAlongSpeed() {
        val c = camera(36f, 0f)
        run(c, 1f)
        assertEquals(10.0, north(c), 0.1)
        assertEquals(0.0, east(c), 0.01)

        val e = camera(36f, 90f)
        run(e, 0.5f)
        assertEquals(5.0, east(e), 0.1)
        assertEquals(0.0, north(e), 0.01)
    }

    @Test
    fun predictionCountsTheFixAge() {
        val c = MapCamera().apply { fix(lat0, lon0, 36f, 0f, 0.3f) }
        c.step(0f)
        assertEquals(3.0, north(c), 0.01)
    }

    @Test
    fun capsExtrapolationAt2s() {
        val c = camera(72f)
        run(c, 2f)
        val at2 = north(c)
        assertTrue("under 2 s of driving: $at2", at2 <= 40.0 + 0.01)
        assertTrue("it did go on: $at2", at2 > 30.0)
        run(c, 3f)
        assertEquals(at2, north(c), 1e-6)
    }

    @Test
    fun slowsBeforeTheCap() {
        val c = camera(72f)
        run(c, 1f)
        val before = north(c)
        repeat(6) { c.step(1f / 30) }
        val cruising = north(c) - before
        repeat(18) { c.step(1f / 30) }
        val a = north(c)
        repeat(3) { c.step(1f / 30) }
        val late = north(c) - a
        assertEquals(4.0, cruising, 0.05)
        assertTrue("the speed fades: 0.1 s gives $late of 2 m", late < 1.0)
    }

    @Test
    fun noPredictionBelow3Kmh() {
        val c = camera(2.9f, 0f)
        val moved = c.step(1f / 30)
        run(c, 2f)
        assertFalse(moved)
        assertEquals(0.0, north(c), 1e-9)
        assertEquals(0.0, east(c), 1e-9)
    }

    @Test
    fun noPredictionWithoutACourse() {
        val c = camera(50f, null)
        run(c, 1f)
        assertEquals(0.0, north(c), 1e-9)
    }

    @Test
    fun aFixWithoutABearingGoesOnTheLastCourse() {
        val c = camera(36f, 90f)
        run(c, 3f)
        c.fix(c.lat, c.lon, 36f, null, 0f)
        val from = east(c)
        run(c, 0.5f)
        assertEquals(from + 5.0, east(c), 0.2)
    }

    // ---- the heading ----

    @Test
    fun bearingUnwrapsAcrossZero() {
        val c = camera(36f, 350f)
        c.fix(lat0, lon0, 36f, 10f, 0f)
        var seen = false
        repeat(90) {
            c.step(1f / 30)
            val h = c.headingDeg
            assertTrue("the short way, not round the back: $h", h >= 349f || h <= 11f)
            if (h < 1f || h > 359f) seen = true
        }
        assertTrue(seen)
        assertEquals(10f, c.headingDeg, 0.5f)
    }

    @Test
    fun headingFollowsAtAboutPoint4s() {
        val c = camera(36f, 0f)
        c.fix(lat0, lon0, 36f, 60f, 0f)
        run(c, 0.4f)
        // 1 - 1/e of the way
        assertEquals(60f * 0.632f, c.headingDeg, 2f)
    }

    @Test
    fun aStandingCarKeepsItsCourse() {
        val c = camera(36f, 120f)
        c.fix(c.lat, c.lon, 1f, 300f, 0f)
        run(c, 2f)
        assertEquals(120f, c.headingDeg, 0.01f)
    }

    // ---- a new fix ----

    @Test
    fun snapsOver50m() {
        val c = camera(0f, 0f)
        c.fix(latAt(60.0), lon0, 0f, 0f, 0f)
        assertEquals(60.0, north(c), 0.01)
        assertFalse(c.step(1f / 30))
    }

    @Test
    fun under50mGlides() {
        val c = camera(0f, 0f)
        c.fix(latAt(40.0), lon0, 0f, 0f, 0f)
        assertEquals(0.0, north(c), 0.01)
        c.step(1f / 30)
        val first = north(c)
        assertTrue("no jump: $first", first in 0.1..5.0)
        run(c, 4f)
        assertEquals(40.0, north(c), 0.05)
    }

    @Test
    fun neverStepsBackOnEarlyFix() {
        val c = camera(72f, 0f)
        run(c, 1f)
        val shown = north(c)
        // earlier than the camera thinks: 20 m driven, this fix says 12 m and slower
        c.fix(latAt(12.0), lon0, 40f, 0f, 0f)
        var last = north(c)
        assertEquals(shown, last, 0.01)
        repeat(90) {
            c.step(1f / 30)
            assertTrue("back from $last to ${north(c)}", north(c) >= last - 1e-6)
            last = north(c)
        }
    }

    @Test
    fun sidewaysCorrectionStillComes() {
        val c = camera(36f, 0f)
        run(c, 1f)
        c.fix(c.lat - 5.0 / metresPerDeg, lonAt(4.0), 36f, 0f, 0f)
        run(c, 1.5f)
        assertEquals(4.0, east(c), 0.2)
    }

    @Test
    fun aRealGapDoesNotStopTheCamera() {
        // 1 Hz, uneven: the speed the eye sees stays near the car's
        val c = camera(36f, 0f)
        var truth = 0.0
        var worst = 0.0
        for (gap in listOf(0.9f, 1.1f, 1.0f, 0.7f, 1.3f, 1.0f)) {
            var last = north(c)
            repeat((gap * 30).toInt()) {
                c.step(1f / 30)
                worst = maxOf(worst, abs(north(c) - last - 10.0 / 30))
                last = north(c)
            }
            truth += (gap * 30).toInt() / 30.0 * 10.0
            c.fix(latAt(truth), lon0, 36f, 0f, 0f)
        }
        assertTrue("worst difference a frame: $worst m", worst < 0.1)
    }

    // ---- parked ----

    @Test
    fun parkedStillCostsNoStep() {
        val c = camera(0f, 90f)
        assertFalse(c.step(1f / 30))
        repeat(5) {
            assertFalse("the same place: nothing to do", c.fix(lat0, lon0, 0f, null, 0f))
        }
    }

    @Test
    fun parkedNoiseCostsNoStep() {
        val c = camera(0f, 90f)
        assertFalse(c.fix(latAt(3.0), lonAt(-2.0), 1f, 200f, 0f))
        assertEquals(0.0, north(c), 1e-9)
        assertEquals(0f, c.headingDeg, 0f)
    }

    @Test
    fun stopsAskingForStepsWhenTheFixesEnd() {
        val c = camera(72f)
        var steps = 0
        while (c.step(1f / 30) && steps < 300) steps++
        assertTrue("about 2 s: $steps", steps in 50..70)
    }

    @Test
    fun stoppingCarDoesNotRollBack() {
        val c = camera(36f, 0f)
        run(c, 1f)
        val shown = north(c)
        c.fix(latAt(shown - 3.0), lon0, 0f, null, 0f)
        run(c, 1f)
        assertEquals(shown, north(c), 0.01)
    }

    @Test
    fun hardStopDoesNotRollBack() {
        val c = camera(72f, 0f)
        run(c, 1f)
        val shown = north(c)
        c.fix(latAt(shown - 8.0), lon0, 0f, null, 0f)
        repeat(90) {
            val before = north(c)
            c.step(1f / 30)
            assertTrue("back from $before to ${north(c)}", north(c) >= before - 1e-6)
        }
        assertEquals(shown, north(c), 0.01)
        assertFalse("nothing left to do", c.step(1f / 30))
        assertFalse("standing there costs no step", c.fix(latAt(shown - 8.0), lon0, 0f, null, 0f))
    }

    @Test
    fun farOffAfterAStopGoesBackAfterAll() {
        val c = camera(72f, 0f)
        run(c, 1f)
        val shown = north(c)
        c.fix(latAt(shown - 40.0), lon0, 0f, null, 0f)
        run(c, 5f)
        assertEquals(shown - 40.0, north(c), 0.05)
    }

    @Test
    fun firstFixTakesNoStandstillBearing() {
        val c = camera(0f, 200f)
        assertEquals(0f, c.headingDeg, 0f)
        c.fix(lat0, lon0, 36f, 90f, 0f)
        run(c, 3f)
        assertEquals(90f, c.headingDeg, 0.5f)
    }

    @Test
    fun aFixThatSatAppliesNoSpeed() {
        val c = camera(36f, 0f)
        run(c, 3f)
        val shown = north(c)
        c.fix(c.lat, c.lon, 36f, 0f, 2.5f, waitedS = 2.5f)
        run(c, 1f)
        assertEquals(shown, north(c), 1e-6)

        val p = MapCamera().apply { place(lat0, lon0, 36f, 0f, 2.5f, waitedS = 2.5f) }
        run(p, 1f)
        assertEquals(0.0, north(p), 1e-6)
    }

    @Test
    fun aBadSourceClockKeepsThePrediction() {
        // the source says 3 s old, it has only just reached us
        val c = camera(36f, 0f)
        run(c, 3f)
        val shown = north(c)
        c.fix(latAt(shown), lon0, 36f, 0f, 3f, waitedS = 0.01f)
        run(c, 1f)
        assertTrue("goes on: ${north(c) - shown}", north(c) - shown > 5.0)

        val p = MapCamera().apply { place(lat0, lon0, 36f, 0f, 3f, waitedS = 0f) }
        val at = north(p)
        run(p, 0.5f)
        assertEquals(at + 5.0, north(p), 0.2)
    }

    @Test
    fun bearingWithoutASpeedStillTurnsAndPredictsNothing() {
        val c = MapCamera().apply { fix(lat0, lon0, null, 90f, 0f) }
        assertEquals(90f, c.headingDeg, 0f)
        c.fix(lat0, lon0, null, 180f, 0f)
        run(c, 3f)
        assertEquals(180f, c.headingDeg, 0.5f)
        assertEquals(0.0, north(c), 1e-9)
        assertEquals(0.0, east(c), 1e-9)
    }

    @Test
    fun newCourseOnlyFrom5KmhWithHysteresis() {
        val c = camera(36f, 0f)
        c.fix(c.lat, c.lon, 2f, 40f, 0f)
        run(c, 2f)
        // crawling after a stop: the course stands, and it goes on along it
        c.fix(c.lat, c.lon, 4f, 10f, 0f)
        val from = north(c)
        run(c, 1f)
        assertEquals(0f, c.headingDeg, 0.01f)
        assertEquals(from + 1.11, north(c), 0.05)
        // over 5: it counts again, and keeps counting down to 3
        c.fix(c.lat, c.lon, 6f, 90f, 0f)
        run(c, 3f)
        assertEquals(90f, c.headingDeg, 0.5f)
        c.fix(c.lat, c.lon, 4f, 120f, 0f)
        run(c, 3f)
        assertEquals(120f, c.headingDeg, 0.5f)
    }

    @Test
    fun reversingSlowlyPredictsNothingAndKeepsTheCourse() {
        val c = camera(36f, 0f)
        c.fix(c.lat, c.lon, 2f, null, 0f)
        run(c, 2f)
        val from = north(c)
        c.fix(c.lat, c.lon, 4f, 180f, 0f)
        run(c, 1f)
        assertEquals(0f, c.headingDeg, 0.01f)
        assertEquals(from, north(c), 1e-6)
    }

    @Test
    fun poorNetworkFixesIgnoredFor60sAfterAGoodOne() {
        val c = MapCamera()
        assertTrue("nothing better yet", c.takes(99f, 0L, true))
        c.fix(lat0, lon0, 0f, null, 0f)
        assertTrue("still nothing better", c.takes(99f, 1000L, true))
        assertTrue(c.takes(5f, 2000L))
        assertFalse(c.takes(50f, 3000L, true))
        assertFalse(c.takes(99f, 62_000L, true))
        assertTrue(c.takes(99f, 62_001L, true))
    }

    @Test
    fun aNetworkFixHasNoSpeedAndTheCameraTreatsItAsNoStop() {
        val g = kg.forester.sg.data.GpsFix.fromNetwork(lat0, lon0, 10.0, 20f, true, 5, 1000L, 1L)
        assertFalse(g.speedKnown)
        assertTrue(g.network)
        assertEquals(50f, g.accuracyM, 0f)
        val c = MapCamera()
        c.place(lat0, lon0, null, null, 0f, 0f)
        c.fix(lat0 + 0.0005, lon0, null, null, 0f)
        assertEquals("a fix with no speed moves the map, no stand-still hold", lat0 + 0.0005, c.lat, 1e-4)
    }

    @Test
    fun poorFixesFromTheNetworkAreGatedButGpsAlwaysMovesTheMap() {
        val c = MapCamera()
        c.fix(lat0, lon0, 0f, null, 0f)
        assertTrue(c.takes(5f, 2000L))
        assertFalse("network, poor, soon after a good one", c.takes(99f, 3000L, network = true))
        assertTrue("a weak GPS fix still moves the map", c.takes(99f, 3000L))
        assertTrue("so does one of 50 m", c.takes(50f, 4000L))
    }

    // ---- what the map view is told ----

    @Test
    fun realScaleAtLatitude60MatchesTheProjection() {
        val zoom = 16.5
        val mapSize = TileSystem.MapSize(zoom)
        fun y(lat: Double): Double {
            val s = sin(lat * PI / 180)
            return mapSize * (0.5 - ln((1 + s) / (1 - s)) / (4 * PI))
        }
        val lat = 60.0
        val lon = 30.0
        val pxPerM = 1f / TileSystem.GroundResolution(lat, zoom).toFloat()
        val a = CameraAnchor(1000, 360, 500f, 245f)
        a.set(lat, lon, 0f, pxPerM)
        // 30 m on: within the margin at any zoom the card offers
        val dLat = 0.00027
        val dLon = 0.00054
        assertTrue(a.update(lat + dLat, lon + dLon, 0f) || abs(a.tx) > 0f)
        assertEquals(y(lat) - y(lat + dLat), a.ty.toDouble(), 0.05)
        assertEquals(-dLon / 360.0 * mapSize, a.tx.toDouble(), 0.05)
    }

    private val anchor get() = CameraAnchor(wPx = 1000, hPx = 360, pivotX = 500f, pivotY = 245f)

    @Test
    fun layerMovesTheMapUnderTheCar() {
        val a = anchor
        a.set(lat0, lon0, 0f, 2f)
        assertTrue(a.update(latAt(5.0), lon0, 0f))
        // north up, 5 m north: the map 10 px down
        assertEquals(0f, a.tx, 0.01f)
        assertEquals(10f, a.ty, 0.01f)
    }

    @Test
    fun layerFollowsTheOrientation() {
        val a = anchor
        // driving east, the map turned so that east is up
        a.set(lat0, lon0, -90f, 2f)
        assertTrue(a.update(lat0, lonAt(5.0), -90f))
        assertEquals(0f, a.tx, 0.01f)
        assertEquals(10f, a.ty, 0.01f)
        assertEquals(0f, a.rot, 0f)
    }

    @Test
    fun layerTurnsTheShortWay() {
        val a = anchor
        a.set(lat0, lon0, 358f, 2f)
        assertTrue(a.update(lat0, lon0, 2f))
        assertEquals(4f, a.rot, 0.01f)
    }

    @Test
    fun farOrTurnedAsksForARealMove() {
        val a = anchor
        a.set(lat0, lon0, 0f, 2f)
        assertFalse("too far", a.update(latAt(200.0), lon0, 0f))
        assertFalse("too much of a turn", a.update(lat0, lon0, 30f))
    }

    @Test
    fun oversizeCoversTheCardWithinTheLimits() {
        val a = anchor
        a.set(lat0, lon0, 20f, 2f)
        var checked = 0
        for (northM in -40..40 step 4) for (eastM in -40..40 step 4) for (o in 14..26) {
            if (!a.update(latAt(northM.toDouble()), lonAt(eastM.toDouble()), o.toFloat())) continue
            checked++
            val r = Math.toRadians(-a.rot.toDouble())
            for (x in listOf(0f, 1000f)) for (y in listOf(0f, 360f)) {
                // the corner of the card in the coordinates of the unturned, unmoved map view
                val px = x - 500f - a.tx
                val py = y - 245f - a.ty
                val mx = 500f + a.marginPx + (px * cos(r) - py * sin(r)).toFloat()
                val my = 245f + a.marginPx + (px * sin(r) + py * cos(r)).toFloat()
                assertTrue("x $mx at $northM $eastM $o", mx >= -0.5f && mx <= a.mapW + 0.5f)
                assertTrue("y $my at $northM $eastM $o", my >= -0.5f && my <= a.mapH + 0.5f)
            }
        }
        assertTrue(checked > 100)
    }

    // ---- the route ----

    @Test
    fun routeTrimmedToTheNearestKilometres() {
        val lat = DoubleArray(300) { latAt(it * 100.0) }
        val lon = DoubleArray(300) { lon0 }
        // from point 50, 10 km on: point 150
        assertEquals(150, lastRouteIndex(lat, lon, 50, 10_000.0))
        assertEquals(299, lastRouteIndex(lat, lon, 250, 10_000.0))
        assertEquals(0, lastRouteIndex(doubleArrayOf(lat0), doubleArrayOf(lon0), 0, 10_000.0))
    }

    // ---- the log of gaps ----

    @Test
    fun gapLogSaysHowUnevenItWas() {
        val lines = ArrayList<String>()
        val g = GapLog("gps", slowMs = 1500, everyMs = 10_000) { lines += it }
        var t = 1000L
        g.tick(t)
        repeat(8) { t += 1000; g.tick(t) }
        t += 2600; g.tick(t)
        assertTrue(lines.toString(), lines.any { it.contains("gps") && it.contains("2600") })
        repeat(2) { t += 1000; g.tick(t) }
        val sum = lines.last()
        assertTrue(sum, sum.contains("p50 1000"))
        assertTrue(sum, sum.contains("max 2600"))
        assertTrue(sum, sum.contains("over 1500 ms: 1"))
    }

    @Test
    fun gapLogDoesNotFloodOnASlowSource() {
        val lines = ArrayList<String>()
        val g = GapLog("gps", slowMs = 1500, everyMs = 60_000) { lines += it }
        var t = 0L
        repeat(29) { t += 2000; g.tick(t) }
        assertTrue(lines.toString(), lines.size <= 5)
    }

    @Test
    fun gapLogSaysWhenItsPercentilesAreOnlyTheEnd() {
        val lines = ArrayList<String>()
        val g = GapLog("frame", slowMs = 1000, everyMs = 60_000) { lines += it }
        var t = 0L
        repeat(2000) { t += 16; g.tick(t) }
        repeat(1000) { t += 32; g.tick(t) }
        assertTrue(lines.toString(), lines.last().contains("of the last 1024"))
        val few = ArrayList<String>()
        val h = GapLog("gps", slowMs = 1500, everyMs = 10_000) { few += it }
        repeat(12) { h.tick(it * 1000L) }
        assertFalse(few.toString(), few.last().contains("last"))
    }

    @Test
    fun gapLinesLeaveTheConnectionHistoryAlone() {
        SsmTrace.clear()
        repeat(2) { SsmTrace.log("connection line $it") }
        repeat(5000) { SsmTrace.gap("gap line $it") }
        val text = SsmTrace.lines()
        assertTrue(text.contains("connection line 0"))
        assertTrue(text.contains("gap line 4999"))
        assertEquals(300, text.lines().count { it.contains("gap line") })
        SsmTrace.clear()
    }

    @Test
    fun gapLogRestartDoesNotCountTheSilence() {
        val lines = ArrayList<String>()
        val g = GapLog("frame", slowMs = 100, everyMs = 60_000) { lines += it }
        g.tick(1000)
        g.restart()
        g.tick(50_000)
        assertTrue(lines.toString(), lines.isEmpty())
    }
}
