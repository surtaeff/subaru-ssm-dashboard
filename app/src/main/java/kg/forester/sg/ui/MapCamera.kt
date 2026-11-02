package kg.forester.sg.ui

import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kg.forester.sg.data.GpsFix
import kg.forester.sg.service.GapLog
import kg.forester.sg.ssm.SsmTrace
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How many times a second the map moves: the one place for the device's tier, 30 and more on a strong unit, 20–30 on a weak one. */
object MapFrames {
    @Volatile var hz = 60f
}

/** Metres of a degree of latitude, on the sphere of the map's projection. */
private const val METRES_PER_DEG = 111_319.49

/**
 * Where the map looks at this moment. GPS gives a position once a second or more seldom, and already old; the camera
 * goes on from the last fix along its course at its speed, up to [CAP_S], the speed fading over the end of that so it
 * does not stop dead. What a new fix says differently is taken up over about [TAU_S] on top of the driving; never
 * backwards along the course (the camera then only slows down until the car has caught up), except what is more than
 * [SNAP_M] off: that is no motion and goes at once.
 *
 * Under 3 km/h there is no prediction and no new course: GPS wanders at a standstill. A wander of less than [NOISE_M]
 * does not move the camera at all, so a parked car costs no frame. A car that stopped keeps its place over a fix up to
 * [HOLD_M] behind it, too; a fix that sat here over [CAP_S] counts as standing.
 *
 * All in one thread (the screen's); no Android in here.
 */
class MapCamera {
    var lat = 0.0
        private set
    var lon = 0.0
        private set

    /** The course shown, 0 to 360. */
    val headingDeg: Float get() = ((heading % 360f) + 360f) % 360f

    var placed = false
        private set

    private var fixLat = 0.0
    private var fixLon = 0.0
    private var speed = 0f
    private var ageS = 0f

    // the direction of driving of the last fix that had one
    private var courseKnown = false
    private var alongE = 0f
    private var alongN = 1f

    // metres from the fix's predicted place to the shown one, fading out
    private var offE = 0.0
    private var offN = 0.0

    // running on past 360, so a turn across north is not a turn the long way round
    private var heading = 0f
    private var headingTo = 0f

    private var moving = false

    // a new course counts (see courseOf)
    private var tracking = false

    // a stopped car keeps its place over a fix behind it, unless that is too far to be the prediction's overshoot
    private var holdStopped = true
    private var lastGoodMs = NEVER

    private val metresPerDegLon get() = METRES_PER_DEG * cos(lat * PI / 180.0)

    /**
     * Whether a fix of this accuracy counts: once placed, a poor one (network fixes in a tunnel) is passed over for
     * [POOR_FOR_MS] after the last good one; only a [network] fix is held back, a GPS fix always moves the map.
     * [nowMs]: any steady clock.
     */
    fun takes(accuracyM: Float, nowMs: Long, network: Boolean = false): Boolean {
        if (accuracyM < POOR_M) {
            lastGoodMs = nowMs
            return true
        }
        if (!network) return true
        return !placed || lastGoodMs == NEVER || nowMs - lastGoodMs > POOR_FOR_MS
    }

    /**
     * A new fix, [ageNowS] old by its source's clock, [waitedS] since it got here; [speedKmh] null: the source gives no
     * speed. Moves nothing by itself but what is no
     * motion; true: the camera wants steps.
     */
    fun fix(lat: Double, lon: Double, speedKmh: Float?, bearing: Float?, ageNowS: Float, waitedS: Float = 0f): Boolean {
        if (!placed) return place(lat, lon, speedKmh, bearing, ageNowS, waitedS)
        val kmh = fresh(speedKmh, waitedS)
        val course = courseOf(kmh, bearing)
        val metresPerS = drivingSpeed(kmh, bearing, course)
        if (metresPerS == 0f) {
            if (distanceTo(lat, lon, this.lat, this.lon) < NOISE_M) {
                // where it stands is good enough; only a turn still under way goes on
                take(this.lat, this.lon, 0f, course, 0f)
                offE = 0.0
                offN = 0.0
                moving = headingTo != heading
                return moving
            }
            if (speed == 0f && distanceTo(lat, lon, fixLat, fixLon) < NOISE_M) {
                // the same standstill as the fix before: all is as it was
                take(fixLat, fixLon, 0f, course, ageS)
                moving = moving || headingTo != heading
                return moving
            }
        }
        take(lat, lon, metresPerS, course, ageNowS.coerceIn(0f, MAX_AGE_S))
        val driven = predicted(ageS)
        val toFixE = (this.lon - fixLon) * metresPerDegLon
        val toFixN = (this.lat - fixLat) * METRES_PER_DEG
        offE = toFixE - driven * alongE
        offN = toFixN - driven * alongN
        val off = hypot(offE, offN)
        holdStopped = off <= HOLD_M
        if (off > SNAP_M) {
            offE = 0.0
            offN = 0.0
            heading = headingTo
            show(driven)
        }
        moving = true
        return true
    }

    /** At once, as after a long time away: nothing to glide over. True: the camera wants a step to show it. */
    fun place(lat: Double, lon: Double, speedKmh: Float?, bearing: Float?, ageNowS: Float, waitedS: Float = 0f): Boolean {
        val kmh = fresh(speedKmh, waitedS)
        val course = courseOf(kmh, bearing)
        placed = true
        this.lat = lat
        this.lon = lon
        take(lat, lon, drivingSpeed(kmh, bearing, course), course, ageNowS.coerceIn(0f, MAX_AGE_S))
        heading = headingTo
        offE = 0.0
        offN = 0.0
        show(predicted(ageS))
        moving = true
        return true
    }

    /**
     * A fix that sat here longer than the prediction reaches says nothing of the speed now. Judged by when it got here:
     * a source's own clock may be off, and a fresh fix from it must not lose the prediction.
     */
    private fun fresh(speedKmh: Float?, waitedS: Float) = if (speedKmh != null && waitedS > CAP_S) 0f else speedKmh

    /**
     * The course this fix gives, null if none. Without a speed its bearing is all there is. At a walk GPS's course is
     * noise, or the way back when reversing: once a course is known a new one counts from [COURSE_KMH], and then down
     * to [STILL_KMH].
     */
    private fun courseOf(kmh: Float?, bearing: Float?): Float? {
        if (kmh == null) return bearing
        if (kmh >= COURSE_KMH) tracking = true else if (kmh <= STILL_KMH) tracking = false
        return if (kmh > STILL_KMH && (tracking || !courseKnown)) bearing else null
    }

    /** Metres a second to predict with, 0 for none; none, too, when the fix goes against the course it keeps (reversing). */
    private fun drivingSpeed(kmh: Float?, bearing: Float?, course: Float?): Float {
        if (kmh == null || kmh <= STILL_KMH) return 0f
        if (course == null && bearing != null) {
            val r = bearing * PI / 180.0
            if (sin(r) * alongE + cos(r) * alongN < 0.0) return 0f
        }
        return kmh / 3.6f
    }

    private fun take(lat: Double, lon: Double, metresPerS: Float, bearing: Float?, age: Float) {
        fixLat = lat
        fixLon = lon
        ageS = age
        if (bearing != null) {
            courseKnown = true
            val r = bearing * PI / 180.0
            alongE = sin(r).toFloat()
            alongN = cos(r).toFloat()
            // the short way from where the heading is
            headingTo = heading + (((bearing - heading) % 360f + 540f) % 360f - 180f)
        }
        speed = if (courseKnown) metresPerS else 0f
    }

    /** [dt] seconds passed. True: go on stepping. */
    fun step(dt: Float): Boolean {
        if (!placed || !moving) return false
        ageS += dt
        val driven = predicted(ageS)
        val fade = exp(-dt / TAU_S).toDouble()
        val shownE = (lon - fixLon) * metresPerDegLon
        val shownN = (lat - fixLat) * METRES_PER_DEG
        offE *= fade
        offN *= fade
        var wantE = driven * alongE + offE
        var wantN = driven * alongN + offN
        // never back along the course: what would be goes on waiting in the offset
        val forward = (wantE - shownE) * alongE + (wantN - shownN) * alongN
        val held = forward < 0.0 && courseKnown && (speed > 0f || holdStopped)
        if (held) {
            wantE -= forward * alongE
            wantN -= forward * alongN
            offE = wantE - driven * alongE
            offN = wantN - driven * alongN
        }
        val left = if (held) hypot(wantE - shownE, wantN - shownN) else hypot(offE, offN)
        val still = (speed == 0f || ageS >= CAP_S) && left < REST_M
        if (still && !held) {
            wantE -= offE
            wantN -= offN
            offE = 0.0
            offN = 0.0
        }
        lat = fixLat + wantN / METRES_PER_DEG
        lon = fixLon + wantE / metresPerDegLon
        heading = headingTo + (heading - headingTo) * exp(-dt / TAU_S)
        if (abs(headingTo - heading) < REST_DEG) heading = headingTo
        moving = !still || heading != headingTo
        return moving
    }

    private fun show(driven: Double) {
        lat = fixLat + driven * alongN / METRES_PER_DEG
        lon = fixLon + driven * alongE / metresPerDegLon
    }

    /** Metres driven since the fix, at [age] seconds. */
    private fun predicted(age: Float): Double {
        if (speed <= 0f) return 0.0
        val a = min(age, CAP_S)
        val past = a - FADE_FROM_S
        val time = if (past <= 0f) a else FADE_FROM_S + past - past * past / (2f * (CAP_S - FADE_FROM_S))
        return (speed * time).toDouble()
    }

    private fun distanceTo(lat: Double, lon: Double, toLat: Double, toLon: Double) =
        hypot((lon - toLon) * metresPerDegLon, (lat - toLat) * METRES_PER_DEG)

    private companion object {
        const val STILL_KMH = 3f
        const val COURSE_KMH = 5f
        const val CAP_S = 2f
        const val FADE_FROM_S = 1.5f
        const val TAU_S = 0.4f
        const val SNAP_M = 50.0
        const val NOISE_M = 4.0
        const val HOLD_M = 30.0
        const val POOR_M = 50f
        const val POOR_FOR_MS = 60_000L
        const val NEVER = Long.MIN_VALUE
        const val REST_M = 0.02
        const val REST_DEG = 0.05f

        /** A fix older than this on arrival is a clock that is off: more would only put the map ahead of the car. */
        const val MAX_AGE_S = 1f
    }
}

/**
 * The map view larger than the card by [marginPx] all round, drawn for one place and course (the anchor) and moved
 * from there as a whole by a layer: no redraw of the tiles for a frame. [update] says when the layer has gone as far
 * as the margin allows; then the view is really moved and set anew. [pivotX], [pivotY]: where the car is on the card.
 */
class CameraAnchor(wPx: Int, hPx: Int, pivotX: Float, pivotY: Float) {
    private val maxShiftPx = maxOf(MIN_SHIFT_PX, min(wPx, hPx) * 0.1f)

    val marginPx: Int = run {
        val far = maxOf(hypot(pivotX, pivotY), hypot(wPx - pivotX, pivotY), hypot(pivotX, hPx - pivotY), hypot(wPx - pivotX, hPx - pivotY))
        ceil(far * 2f * sin(MAX_TURN_DEG / 2f * PI.toFloat() / 180f) + maxShiftPx).toInt() + 1
    }
    val mapW = wPx + 2 * marginPx
    val mapH = hPx + 2 * marginPx

    /** The car's place as a share of the map view: the layer turns about it. */
    val originX = (pivotX + marginPx) / mapW.coerceAtLeast(1)
    val originY = (pivotY + marginPx) / mapH.coerceAtLeast(1)

    /** From the view's middle to the car: osmdroid turns its map about this point. */
    val centerOffsetX = (pivotX - wPx / 2f).toInt()
    val centerOffsetY = (pivotY - hPx / 2f).toInt()

    /** What the layer does now. */
    var tx = 0f
        private set
    var ty = 0f
        private set
    var rot = 0f
        private set

    private var lat = 0.0
    private var lon = 0.0
    private var orientation = 0f
    private var pxPerM = 1f

    /** The view now really shows this. */
    fun set(lat: Double, lon: Double, orientation: Float, pxPerM: Float) {
        this.lat = lat
        this.lon = lon
        this.orientation = orientation
        this.pxPerM = pxPerM
        tx = 0f
        ty = 0f
        rot = 0f
    }

    /** The layer for this camera; false: too far for the margin, the view has to move. */
    fun update(lat: Double, lon: Double, orientation: Float): Boolean {
        val x = (lon - this.lon) * METRES_PER_DEG * cos(this.lat * PI / 180.0) * pxPerM
        val y = -(lat - this.lat) * METRES_PER_DEG * pxPerM
        val o = orientation * PI / 180.0
        val c = cos(o)
        val s = sin(o)
        tx = (-(x * c - y * s)).toFloat()
        ty = (-(x * s + y * c)).toFloat()
        rot = ((orientation - this.orientation) % 360f + 540f) % 360f - 180f
        return hypot(tx, ty) <= maxShiftPx && abs(rot) <= MAX_TURN_DEG
    }

    private companion object {
        const val MAX_TURN_DEG = 6f
        const val MIN_SHIFT_PX = 24f
    }
}

/** The index of the route's point about [maxM] metres of road on from [from], the last one if the route ends earlier. */
fun lastRouteIndex(lat: DoubleArray, lon: DoubleArray, from: Int, maxM: Double): Int {
    var left = maxM
    var i = from
    while (i < lat.size - 1) {
        val e = (lon[i + 1] - lon[i]) * METRES_PER_DEG * cos(lat[i] * PI / 180.0)
        val n = (lat[i + 1] - lat[i]) * METRES_PER_DEG
        left -= sqrt(e * e + n * n)
        i++
        if (left < 1.0) break
    }
    return i
}

/**
 * The camera on a [MapView]. Frames from the one [SmoothLoop]; a car standing or without new fixes asks for none.
 * The map's own animateTo is not used: it never cancels the one before, and two animators then write the centre.
 */
class CameraRig(private val map: MapView, private val live: Boolean = true) : Stepper {
    val tx = mutableFloatStateOf(0f)
    val ty = mutableFloatStateOf(0f)
    val rot = mutableFloatStateOf(0f)

    /** The course shown, for what is drawn with the map. */
    val heading = mutableFloatStateOf(0f)

    private val camera = MapCamera()
    private var anchor: CameraAnchor? = null
    private var northUp = false
    private var waitedS = 0f
    private var paused = false
    private var latest: GpsFix? = null

    // what reaches the map and how its frames go: is it the fixes, or is the screen standing still?
    private val fixGaps = GapLog("map fix", 1500) { SsmTrace.gap(it) }
    private val frameGaps = GapLog("map frame", 250) { SsmTrace.gap(it) }

    fun onFix(g: GpsFix) {
        if (g.lat == null || g.lon == null) return
        val now = SystemClock.uptimeMillis()
        if (!camera.takes(g.accuracyM, now, g.network)) return
        fixGaps.tick(now)
        if (paused) {
            latest = g
            return
        }
        if (!live) {
            place(g)
            showCamera()
            return
        }
        // back from the background: what happened meanwhile is no motion to replay
        val go = if (SmoothDriver.returning()) place(g) else camera.fix(g.lat, g.lon, speedOf(g), g.bearing, ageOf(g), waitedOf(g))
        if (go) SmoothDriver.wake(this)
    }

    /** Something covers the map: no frames for it, and of the fixes only the latest is remembered. */
    fun pause() {
        paused = true
        SmoothDriver.drop(this)
    }

    /** The map shows again, where the car is now. */
    fun resume() {
        paused = false
        frameGaps.restart()
        val g = latest ?: return
        latest = null
        place(g)
        showCamera()
        if (live) SmoothDriver.wake(this)
    }

    /** The card's size, the zoom or the turning has changed: the view shows it now. */
    fun use(a: CameraAnchor, northUp: Boolean = false) {
        anchor = a
        this.northUp = northUp
        if (camera.placed) moveView(a) else if (northUp) map.mapOrientation = 0f
    }

    override fun step(dt: Float): Boolean {
        kg.forester.sg.data.DeviceTier.drawing()
        // not quite one interval: frames at about this rate must not fall every second one
        waitedS += dt
        if (waitedS < 0.8f / MapFrames.hz) return true
        val more = camera.step(waitedS)
        waitedS = 0f
        showCamera()
        // time in the background is no gap between frames
        if (SmoothDriver.returning()) frameGaps.restart()
        if (more) frameGaps.tick(SystemClock.uptimeMillis()) else frameGaps.restart()
        return more
    }

    private fun place(g: GpsFix) = camera.place(g.lat!!, g.lon!!, speedOf(g), g.bearing, ageOf(g), waitedOf(g))

    private fun speedOf(g: GpsFix) = if (g.speedKnown) g.speedKmh else null

    private fun ageOf(g: GpsFix) = (System.currentTimeMillis() - g.timeMs) / 1000f

    private fun waitedOf(g: GpsFix) = if (g.arrivedMs == 0L) 0f else (SystemClock.elapsedRealtime() - g.arrivedMs) / 1000f

    private fun showCamera() {
        val a = anchor
        if (a != null) {
            if (!a.update(camera.lat, camera.lon, orientation())) moveView(a)
            show(a)
        }
        heading.floatValue = camera.headingDeg
    }

    private fun orientation() = if (northUp) 0f else -camera.headingDeg

    private fun moveView(a: CameraAnchor) {
        val o = orientation()
        map.controller.setCenter(GeoPoint(camera.lat, camera.lon))
        // setting it asks for a new layout of the view every time, changed or not
        if (map.mapOrientation != o) map.mapOrientation = o
        a.set(camera.lat, camera.lon, o, 1f / TileSystem.GroundResolution(camera.lat, map.zoomLevelDouble).toFloat())
        show(a)
    }

    private fun show(a: CameraAnchor) {
        tx.floatValue = a.tx
        ty.floatValue = a.ty
        rot.floatValue = a.rot
    }
}

/** The map view at the oversize of [a], moved by the [rig]'s layer. The parent clips what sticks out. */
fun Modifier.cameraMap(rig: CameraRig, a: CameraAnchor): Modifier = this
    .layout { m, c ->
        val p = m.measure(Constraints.fixed(a.mapW, a.mapH))
        layout(c.maxWidth, c.maxHeight) { p.place(-a.marginPx, -a.marginPx) }
    }
    .graphicsLayer {
        translationX = rig.tx.floatValue
        translationY = rig.ty.floatValue
        rotationZ = rig.rot.floatValue
        transformOrigin = TransformOrigin(a.originX, a.originY)
    }
