package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 3D models available for the home screen: assets/<dir>/car_NNN.webp (+ plates.json, meta.json).
 * meta.json may set driveFrame (straight rear view), carX (car centre in that frame) and the exhaust spot.
 */
val CarModels3d = listOf(
    "SG" to "Forester SG5",
    "SGNA" to "Forester SG5 (атмо)",
    "SH" to "Forester SH",
    "SHNA" to "Forester SH (атмо)",
    "SK" to "Forester SK 2020",
    "GRC" to "WRX STI GRC",
    "BM" to "Legacy 2.5GT tS (BM9)",
    "VA" to "WRX STI 2018 (VA)",
    "GG" to "Impreza WRX Wagon (GG)",
    "GD" to "Impreza WRX STI (GD)",
    "GV" to "Impreza WRX STI (GV)",
    "22B" to "Impreza 22B STi",
    "GC" to "Impreza Coupe WRX Type R (GC)",
    CAR3D_UNKNOWN to "Porsche 992 GT3 R",
)

/** True when the model has white-body frames, so it can take any colour (incl. white and silver). */
fun car3dAnyColour(ctx: Context, id: String): Boolean =
    car3dDir(ctx, id)?.let { d -> ctx.assets.list(d)?.any { it.startsWith("white_") } } ?: false

/** Asset folder of a 3D model: "car" for the SG5, "car_<id>" for the others (car_sh, car_sk, car_22b, ...). */
fun car3dDir(ctx: Context, id: String): String? {
    val dir = if (id == "SG" || CarModels3d.none { it.first == id }) "car" else "car_" + id.lowercase()
    return dir.takeIf { (ctx.assets.list(it)?.any { f -> f.startsWith("car_") } ?: false) }
}

/**
 * One still of the chosen 3D car in its paint, cropped to the car: the straight rear view, or ([side]) the side
 * view with the nose to the left. For the tilt card; decodes one frame, call it off the main thread.
 */
fun carStill(ctx: Context, model: String, paintId: String, side: Boolean): Bitmap? {
    val src = FrameSource(ctx.applicationContext, car3dDir(ctx, model) ?: "car", carPaint(car3dPaintId(model, paintId)))
    if (src.count == 0) return null
    val b = src.get(if (side) src.driveFrame - src.count / 4 else src.driveFrame) ?: return null
    var x0 = b.width; var y0 = b.height; var x1 = 0; var y1 = 0
    for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
        if ((b.getPixel(x, y) ushr 24) > 16) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
    }
    if (x1 <= x0 || y1 <= y0) return b
    return Bitmap.createBitmap(b, x0, y0, (x1 - x0 + 2).coerceAtMost(b.width - x0), (y1 - y0 + 2).coerceAtMost(b.height - y0))
}

/**
 * The decoded frames of the car on screen, kept while the app runs: going to another screen and back does
 * not decode them again (that took a few seconds). Only the last model/paint is kept; [FrameStore] says which frames.
 */
private object CarFrames {
    private var key = ""
    private var src: FrameSource? = null
    /** Read on the main thread, which must not wait for a model being read. */
    @Volatile private var ready: Pair<String, FrameSource>? = null
    /** Bumped when the app comes back after giving its full-size frames up: they are decoded again. */
    val returned = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** The source if it is the one in memory: the screen shows it at once. */
    fun ready(model: String, paint: String): FrameSource? = ready?.takeIf { it.first == "$model|${car3dPaintId(model, paint)}" }?.second

    /** Reads the model's lists and JSON the first time: off the main thread. */
    @Synchronized
    fun source(ctx: Context, model: String, paint: String): FrameSource {
        val shown = car3dPaintId(model, paint)
        val k = "$model|$shown"
        src?.takeIf { key == k }?.let { return it }
        src?.close()
        return FrameSource(ctx.applicationContext, car3dDir(ctx, model) ?: "car", carPaint(shown)).also {
            src = it
            key = k
            ready = k to it
            // the paint or the model changed: the old one's frames on disk go
            Thread { runCatching { it.dropOtherPaints() } }.start()
        }
    }

    @Synchronized fun trim(level: Int) { src?.trim(frameTrimFor(level)) }

    @Synchronized fun show() { if (src?.show() == true) returned.value++ }
}

/** A memory warning (off the main thread: a frame being decoded may hold it up a moment). */
fun trimCarFrames(level: Int) = CarFrames.trim(level)

/** The app is on the screen again. */
fun showCarFrames() = CarFrames.show()

/** PNGs are written one at a time away from the decoding: the next frame shouldn't wait for the flash. */
private val carTintWriter = java.util.concurrent.ThreadPoolExecutor(
    1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(2),
    // the queue holds the bitmaps, and the cache is optional: a frame not written is painted again next time
    java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
).apply { allowCoreThreadTimeOut(true) }

/** Pre-rendered 360° turntable frames in assets/car/car_NNN.webp. */
private class FrameSource(private val ctx: Context, private val dir: String, private val paint: CarPaint) {
    val count: Int = ctx.assets.list(dir)?.count { it.startsWith("car_") } ?: 0

    private val meta = runCatching {
        org.json.JSONObject(ctx.assets.open("$dir/meta.json").bufferedReader().readText())
    }.getOrNull()
    /** Straight rear view used while driving, and where things are in it (SG5 values by default). */
    val driveFrame = meta?.optInt("driveFrame", 43) ?: 43
    val carX = meta?.optDouble("carX", 0.464)?.toFloat() ?: 0.464f // the car is not centred in its frame
    val exhaustX = meta?.optDouble("exhaustX", 0.366)?.toFloat() ?: 0.366f
    val exhaustY = meta?.optDouble("exhaustY", 0.86)?.toFloat() ?: 0.86f
    /** Exhaust tips in the rear view (the SH XT has two). */
    val exhausts: List<Pair<Float, Float>> = listOfNotNull(
        exhaustX to exhaustY,
        meta?.takeIf { it.has("exhaust2X") }?.let { it.getDouble("exhaust2X").toFloat() to it.getDouble("exhaust2Y").toFloat() },
    )
    // Decoding on the fly made the rotation stutter, all frames at full size took 98 MB: 10 MB of them stay full size
    // (the rear view and what is around it), and a turn by hand runs on half-size frames, sharp again once it stops.
    private val store = FrameStore<Bitmap>(count, driveFrame) { Bitmap.createScaledBitmap(it, it.width / 2, it.height / 2, true) }
    /** Another paint or model took over the screen: whatever still loads for this one stops. */
    @Volatile private var closed = false

    fun close() { closed = true }

    private val window = frameWindow(count, driveFrame)

    fun inWindow(i: Int) = index(i) in window

    /** Decodes the full-size frames of the window, nearest to [first] (the view on screen) first. */
    fun preload(first: Int = 0, onFirst: () -> Unit = {}) {
        val idx = index(first)
        var told = false
        window.sortedBy { nearness(it, idx) }.forEach { i ->
            if (closed || store.hidden) return
            get(i)
            if (!told) { told = true; onFirst() }
        }
    }

    /** The half-size frames for a turn by hand, nearest to [first] first. */
    fun loadHalves(first: Int) {
        val idx = index(first)
        (0 until count).sortedBy { nearness(it, idx) }.forEach { i ->
            if (closed || store.hidden) return
            half(i)
        }
    }

    val halvesLoaded get() = store.halvesComplete()

    private fun nearness(i: Int, first: Int) = minOf((i - first + count) % count, (first - i + count) % count)

    fun trim(t: FrameTrim) { store.trim(t) }

    /** True if the frames had been given up for being off the screen. */
    fun show(): Boolean = store.hidden.also { store.show() }

    fun dropOtherPaints() = disk.retain(diskKey)

    private val hasWhite = ctx.assets.list(dir)?.any { it.startsWith("white_") } ?: false

    /** The frames are repainted here, slow enough to keep the result on disk; the others are plain asset decodes. */
    private val painted = paint.id != "chameleon" && (hasWhite || (!paint.needsWhite && (paint.hue != null || paint.grey != 0f)))
    private val disk = TintCache(java.io.File(ctx.cacheDir, "car_tint"), CAR_TINT_CACHE_BYTES)
    private val diskKey by lazy {
        val build = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        carTintKey(dir, paint, build)
    }

    private fun decode(i: Int, half: Boolean): Bitmap? {
        val onDisk = (if (half) "h_%03d" else "f_%03d").format(i)
        if (painted) disk.file(diskKey, onDisk)?.let { f ->
            runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.let { return it }
            disk.delete(diskKey, onDisk) // a broken file: paint it again
        }
        val opts = BitmapFactory.Options().apply { if (half) inSampleSize = 2 }
        fun load(name: String) = ctx.assets.open("$dir/%s_%03d.webp".format(name, i)).use { BitmapFactory.decodeStream(it, null, opts) }
        val b = runCatching {
            when {
                paint.id == "chameleon" -> load("car")
                hasWhite -> tint(load("white")!!, load("mask")!!, paint)
                paint.needsWhite -> load("car") // no white frames for this model: light colours are not possible
                else -> load("car")?.let { recolor(it, paint) }
            }
        }.getOrNull()
        if (painted && b != null) carTintWriter.execute {
            if (!closed) runCatching { disk.write(diskKey, onDisk) { carTintCompressed(b.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        }
        return b
    }

    /** Per-frame plate corners (front/rear), normalised 0..1, TL TR BR BL. */
    val plates: List<Map<String, FloatArray>> = runCatching {
        val arr = org.json.JSONArray(ctx.assets.open("$dir/plates.json").bufferedReader().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.keys().asSequence().associateWith { k ->
                val q = o.getJSONArray(k)
                FloatArray(8) { j -> q.getJSONArray(j / 2).getDouble(j % 2).toFloat() }
            }
        }
    }.getOrDefault(emptyList())

    fun index(i: Int) = if (count == 0) 0 else ((i % count) + count) % count

    /** The sharpest picture of [i] in memory. */
    fun cached(i: Int): Bitmap? = if (count == 0) null else store.best(index(i))

    /** [i] in full size, if it is in memory. */
    fun sharp(i: Int): Bitmap? = if (count == 0) null else store.full(index(i))

    /** [i] is on the screen now: the view before it gives its full-size frame up. */
    fun look(i: Int) { if (count != 0) store.look(index(i)) }

    /** Width of the car itself in each frame, as a part of the frame width (the rear view is narrow). */
    private val carW = FloatArray(count) { 1f }
    fun carWidth(i: Int) = if (count == 0) 1f else carW[index(i)]

    private val measuredFull = BooleanArray(count)

    /** The full-size frame has the last word, the size of the car at rest is then what it always was. */
    private fun measure(idx: Int, b: Bitmap, half: Boolean = false) {
        if (measuredFull[idx] || (half && carW[idx] != 1f)) return
        if (!half) measuredFull[idx] = true
        var mn = b.width; var mx = 0
        for (x in 0 until b.width step if (half) 2 else 3) for (y in 0 until b.height step if (half) 3 else 6) {
            if ((b.getPixel(x, y) ushr 24) > 8) { if (x < mn) mn = x; if (x > mx) mx = x }
        }
        if (mx > mn) carW[idx] = (mx - mn).toFloat() / b.width
    }

    /** [i] in full size, decoded if needed; off the screen only what is there. */
    fun get(i: Int): Bitmap? {
        if (count == 0) return null
        val idx = index(i)
        store.full(idx)?.let { return it }
        if (store.hidden) return store.best(idx)
        return synchronized(this) {
            store.full(idx) ?: decode(idx, half = false)?.also { measure(idx, it); store.putFull(idx, it) }
        }
    }

    /** A picture of [i] as soon as can be: what is in memory, else the half-size frame decoded. */
    fun half(i: Int): Bitmap? {
        if (count == 0) return null
        val idx = index(i)
        store.best(idx)?.let { return it }
        return synchronized(this) {
            store.best(idx) ?: decode(idx, half = true)?.also { measure(idx, it, half = true); store.putHalf(idx, it) }
        }
    }
}

/** Directions of the speed streaks, x and y in turn: working out 26 to 66 sines on each frame of the road is wasted. */
private val StreakRays = FloatArray(66 * 2).also { a ->
    for (i in 0 until 66) {
        val rad = Math.toRadians(((i * 137.508f) % 360f).toDouble())
        a[i * 2] = kotlin.math.cos(rad).toFloat()
        a[i * 2 + 1] = kotlin.math.sin(rad).toFloat() * 0.55f
    }
}
private val BlurRays = FloatArray(46 * 2).also { a ->
    for (i in 0 until 46) {
        val rad = Math.toRadians(((i * 97.31f + 13f) % 360f).toDouble())
        a[i * 2] = kotlin.math.cos(rad).toFloat()
        a[i * 2 + 1] = kotlin.math.sin(rad).toFloat() * 0.6f
    }
}

/** The road under the car: on at 3 km/h, off below 2, so a crawl around the threshold does not make it blink. */
internal fun roadOn(was: Boolean, speed: Int): Boolean = speed >= 3 || (was && speed >= 2)

/**
 * Camera lag on a pull: the car a bit smaller while accelerating, a bit larger while braking. Only at speed: moving off
 * or stopping the car keeps the size and height it has standing still.
 */
internal fun carPullZoom(accel: Float, speed: Int): Float =
    1f - (accel / 12f).coerceIn(-0.6f, 1f) * 0.07f * ((speed - 40) / 40f).coerceIn(0f, 1f)

/** Left edge of a frame [drawn] wide in a box [width] wide: the car ([carX], from the model's meta.json) on the centre line. */
internal fun carFrameLeft(width: Float, drawn: Float, carX: Float): Float = (width - drawn) / 2 + (0.5f - carX) * drawn

/** Where the car looks when it comes on the screen, in frames: the rear view it drives in, never driven or not. */
internal fun carStartAngle(driveFrame: Int): Float = driveFrame.toFloat()

/** How long the car stands still before the half-size view gets its full-size frame. */
private const val SHARPEN_AFTER_MS = 150L

/** How often what is drawn of the road may change: this head unit's rate, see DeviceTier. */
private val RoadHz = { kg.forester.sg.data.DeviceTier.look.roadHz }

/**
 * Rotatable car: stands in its rear view, drag horizontally to turn it.
 * The neon floor glow pulses with engine speed.
 */
@Composable
fun CarViewer(
    live: State<kg.forester.sg.ssm.EcuSnapshot>, modifier: Modifier = Modifier,
    /** Part of the width the car itself may take (the dials sit left and right). */
    carShare: Float = 1f,
    /** Car size relative to the largest that fits; a smaller car sits lower, on the road. */
    carScale: Float = 1f,
) {
    val ctx = LocalContext.current
    val model = car3dNow()
    val paintId by kg.forester.sg.data.Repo.carPaint.collectAsState()
    // the model's lists and JSON are read off the main thread; until then the room is there, empty
    val src by androidx.compose.runtime.produceState(CarFrames.ready(model, paintId), model, paintId) {
        value = CarFrames.ready(model, paintId) ?: withContext(Dispatchers.IO) { CarFrames.source(ctx, model, paintId) }
    }
    val source = src
    StartSteps.carAsked = true
    if (source == null) androidx.compose.foundation.layout.Box(modifier)
    else CarOnRoad(source, live, modifier, carShare, carScale)
}

@Composable
private fun CarOnRoad(src: FrameSource, live: State<kg.forester.sg.ssm.EcuSnapshot>, modifier: Modifier, carShare: Float, carScale: Float) {
    val ctx = LocalContext.current
    // the readings are read where they are used, in the frames and the drawing: a new one recomposes nothing here
    val speed by remember(live) { derivedStateOf { live.value.speedKmh ?: 0 } }
    // always in the pose it sets off in; a turn by hand lasts while the car stays on the screen and it stands
    var angle by remember(src) { mutableFloatStateOf(carStartAngle(src.driveFrame)) } // in frames
    // back on this screen the car is there from the first frame, half size at worst
    var frame by remember { mutableStateOf(src.cached(angle.roundToInt())) }
    var frameIdx by remember { mutableStateOf(src.index(angle.roundToInt())) }
    val returned by CarFrames.returned.collectAsState()
    /** The half-size frames for turning are decoded on the first touch, or when moving off with the car turned away. */
    var wantHalves by remember(src) { mutableStateOf(false) }
    val plate by kg.forester.sg.data.Plate.bitmap.collectAsState()
    LaunchedEffect(Unit) { if (kg.forester.sg.data.Plate.bitmap.value == null) kg.forester.sg.data.Plate.load(ctx) }

    // Parked: the car stands still, a finger turns it. Driving: turn to the straight rear view (like the Teyes
    // launcher), let the road and speed streaks run, follow GPS turns, the camera lag behind on a pull and the
    // exhaust spit flames. Only a parked car can be turned by hand.
    // Turn rate from the GPS course (°/s, + = right), only on the move where the course is reliable.
    var yaw by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var prev: kg.forester.sg.data.GpsFix? = null
        kg.forester.sg.data.Repo.gps.collect { g ->
            val before = prev
            prev = g
            if (g?.bearing == null || g.speedKmh < 10f || before?.bearing == null) { yaw *= 0.5f; return@collect }
            val dt = ((g.timeMs - before.timeMs) / 1000f).takeIf { it in 0.2f..3f } ?: return@collect
            var d = g.bearing - before.bearing
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            yaw += ((d / dt).coerceIn(-30f, 30f) - yaw) * 0.45f
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if ((kg.forester.sg.data.Repo.gps.value?.let { System.currentTimeMillis() - it.timeMs } ?: 99_999) > 3000) {
                // to an exact zero: the halves of a halve would go on moving the turn, unseen, for minutes
                yaw = if (kotlin.math.abs(yaw) < 0.05f) 0f else yaw * 0.5f
            }
        }
    }
    val turn = rememberSmooth(remember { derivedStateOf { (yaw / 22f).coerceIn(-1f, 1f) } }, tau = 0.15f, shownHz = RoadHz).value
    var road by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(src, returned) { withContext(Dispatchers.Default) { src.preload(angle.roundToInt()) { StartSteps.carShown.value = true } } }
    LaunchedEffect(src, wantHalves, returned) {
        if (wantHalves) { withContext(Dispatchers.Default) { src.loadHalves(angle.roundToInt()) }; wantHalves = false }
    }
    // parked: the car stays where it is (turn it with a finger) and nothing wakes up; driving: chase view, eased into turns
    LaunchedEffect(src) {
        snapshotFlow { speed >= 3 }.collectLatest { driving ->
            while (driving) {
                delay(35)
                val n = src.count.coerceAtLeast(1)
                val cur = angle.roundToInt()
                if (!src.inWindow(cur) && !src.halvesLoaded) wantHalves = true
                // in a right turn the nose swings right and the chase view sees the car's right flank:
                // higher frame numbers (the camera moves to the car's right), up to 3 frames (15°)
                val target = src.driveFrame + (turn.floatValue * 3f).roundToInt()
                var diff = ((target - cur) % n + n) % n
                if (diff > n / 2) diff -= n
                if (diff != 0) angle = (cur + if (diff > 0) 1 else -1).toFloat()
            }
        }
    }
    val rpmNow by remember(live) { derivedStateOf { live.value.rpm ?: 0 } }
    val throttle by remember(live) { derivedStateOf { live.value.throttlePct ?: 0f } }
    var accel by remember { mutableFloatStateOf(0f) } // smoothed, km/h per second
    var flame by remember { mutableFloatStateOf(0f) } // 1 = fresh backfire, fades to 0
    var clock by remember { mutableFloatStateOf(0f) }
    val motion = remember {
        object : Stepper {
            var nowNs = 1L
            var lastV = 0f
            var vT = 0L
            // rpm and throttle as they changed: what they were 0.4 s ago is the last entry older than that
            val recent = ArrayDeque<Triple<Long, Int, Float>>()
            var roadS = 0f

            override fun step(dt: Float): Boolean {
                nowNs += (dt * 1e9f).toLong()
                val t = nowNs
                clock += dt
                if (speed >= 3) {
                    // ~2 road dashes per second at 30 km/h, faster with speed
                    kg.forester.sg.data.DeviceTier.drawing()
                    roadS += dt
                    if (SmoothDriver.ticks(RoadHz())) {
                        road = (road + roadS * speed / 14f) % 1f
                        roadS = 0f
                    }
                }
                // speed only changes 4–12 times a second (plain or fast poll): measure acceleration between updates,
                // smoothed by time so the fast poll's more frequent steps don't make the camera twitchier
                if (speed.toFloat() != lastV) {
                    if (vT != 0L) {
                        val span = ((t - vT) / 1e9f).coerceAtLeast(0.1f)
                        val a = (speed - lastV) / span
                        accel += (a - accel) * (span / 0.7f).coerceAtMost(1f)
                    }
                    lastV = speed.toFloat(); vT = t
                } else if (vT != 0L && t - vT > 1_500_000_000L) {
                    // decays to an exact zero (a leftover 0.01 km/h/s changes nothing on screen but kept every frame busy)
                    accel *= kotlin.math.exp(-dt / 0.325f)
                    if (kotlin.math.abs(accel) < 0.05f) accel = 0f
                }
                // backfire: throttle snapped shut at high rpm, or an upshift under load
                val ms = t / 1_000_000L
                val rpmIs = rpmNow
                val throttleIs = throttle
                if (recent.isEmpty() || recent.last().second != rpmIs || recent.last().third != throttleIs) recent.addLast(Triple(ms, rpmIs, throttleIs))
                while (recent.size > 1 && recent[1].first <= ms - 400) recent.removeFirst()
                val old = recent.first()
                val lift = old.third - throttleIs > 25f && old.second > 2800
                val shift = rpmIs < old.second - 600 && throttleIs > 50f && old.second > 2500
                if ((lift || shift) && flame < 0.2f && speed >= 3) flame = 1f
                flame = (flame - dt * 2.8f).coerceAtLeast(0f)
                // parked with nothing left to play (no flame, the camera settled): no work and no frames
                return speed >= 3 || flame != 0f || accel != 0f
            }
        }
    }
    LaunchedEffect(motion) { snapshotFlow { speed >= 3 || flame != 0f || accel != 0f }.collect { if (it) SmoothDriver.wake(motion) } }
    androidx.compose.runtime.DisposableEffect(motion) { onDispose { SmoothDriver.drop(motion) } }
    LaunchedEffect(angle.roundToInt(), src, returned) {
        val i = angle.roundToInt()
        src.look(i)
        // a decode (with re-colouring) takes longer than one rotation step: finish it even if the angle moves on,
        // otherwise a turning car never gets a picture
        val now = src.cached(i) ?: withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { src.half(i) }
        // a sharp frame still on the screen after a return stays until the sharp one is back
        if (now != null && (src.sharp(i) != null || frame == null || frameIdx != src.index(i))) { frame = now; frameIdx = src.index(i) }
        // the car stopped turning (each step cancels this): the sharp frame
        if (src.sharp(i) == null) {
            delay(SHARPEN_AFTER_MS)
            val b = withContext(Dispatchers.IO) { src.get(i) }
            if (b != null) { frame = b; frameIdx = src.index(i) }
        }
    }


    var onRoad by remember { mutableStateOf(androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { speed >= 3 }) }
    LaunchedEffect(Unit) { snapshotFlow { speed }.collect { onRoad = roadOn(onRoad, it) } }
    // the road fades in over some 800 ms: it is decoration, a car rolling off is no moment for a flash
    val roadAlpha = rememberSmooth(remember { derivedStateOf { if (onRoad) 1f else 0f } }, tau = 0.16f, shownHz = RoadHz).value
    // camera lags on a pull (car gets a bit smaller), catches up when braking
    val zoom = rememberSmooth(
        remember { derivedStateOf { carPullZoom(accel, speed) } }, tau = 0.1f, shownHz = RoadHz,
    ).value
    // "nitro": boost plus a hard pull, like the NFS blur when the bottle opens; a weak head unit goes without
    val tier by kg.forester.sg.data.DeviceTier.current.collectAsState()
    val nitroLook = kg.forester.sg.data.lookOf(tier).nitro
    val nitro = rememberSmooth(
        remember(live, nitroLook) { derivedStateOf { if (!nitroLook) 0f else (((live.value.boostBar ?: 0f) - 0.35f) / 0.5f + (accel - 6f) / 10f).coerceIn(0f, 1f) * roadAlpha.floatValue } }, tau = 0.07f, shownHz = RoadHz,
    ).value
    val turbo = rememberSmooth(
        remember(live) { derivedStateOf { ((live.value.boostBar ?: 0f) / 0.9f).coerceIn(0f, 1f) * roadAlpha.floatValue } }, tau = 0.06f, shownHz = RoadHz,
    ).value
    val glow = rememberSmooth(remember { derivedStateOf { (rpmNow / 7000f).coerceIn(0f, 1f) } }, shownHz = RoadHz).value
    val nitroOn by remember { derivedStateOf { nitro.floatValue > 0.02f } }

    Box(
        // by src: angle and wantHalves are new states for a new paint or model, a gesture must not go on writing the old ones
        modifier.pointerInput(src) {
            detectHorizontalDragGestures(onDragStart = { if (speed < 3) wantHalves = true }) { _, drag ->
                // on the move the car always shows its rear (road, exhaust flames): no turning it by hand
                if (speed >= 3) return@detectHorizontalDragGestures
                angle -= drag / 9f
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            // The frames are rendered from slightly above the roof: the vanishing point is at roof level, standing
            // and driving alike. Any lower and the car looks nose-up on the floor, and drops when it moves.
            val horizon = h * 0.24f
            // the rays of the floor grid stand still with the horizon: one picture, not 21 lines a frame
            val rays = obtainGraphicsLayer().apply {
                record {
                    for (k in -10..10) {
                        drawLine(C.Violet.copy(alpha = 0.12f), Offset(w / 2 + k * w * 0.02f, horizon), Offset(w / 2 + k * w * 0.16f, h), 1.2f)
                    }
                }
            }
            val asphalt = androidx.compose.ui.graphics.Path()
            val asphaltBrush = Brush.verticalGradient(listOf(Color(0x00101626), Color(0xCC0E1322)), horizon, h)
            var brushGlow = Float.NaN
            var horizonBrush: Brush? = null
            var floorBrush: Brush? = null
            onDrawBehind {
                val roadAlpha = roadAlpha.floatValue
                val road = road
                val turn = turn.floatValue
                // again only when the glow has changed
                val glow = glow.floatValue
                if (glow != brushGlow) {
                    brushGlow = glow
                    horizonBrush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to C.Purple.copy(alpha = 0.18f + glow * 0.25f),
                        0.62f to C.Violet.copy(alpha = 0.35f + glow * 0.4f),
                        0.70f to C.Purple.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                    )
                    floorBrush = Brush.radialGradient(
                        listOf(C.Violet.copy(alpha = 0.55f + glow * 0.45f), Color.Transparent),
                        center = Offset(w / 2, h * 0.80f), radius = w * 0.34f,
                    )
                }
                // horizon glow
                drawRect(horizonBrush!!)
                // perspective floor grid
                for (k in 0..9) {
                    val t = (k + road) / 9f // the lines drift towards the viewer while driving
                    if (t <= 0f || t > 1f) continue
                    val y = horizon + (h - horizon) * t * t
                    drawLine(C.Violet.copy(alpha = 0.10f + t * 9f * 0.015f), Offset(0f, y), Offset(w, y), 1.2f)
                }
                drawLayer(rays)
                // the road: appears while driving, its dashes run towards the viewer with the speed
                // in a turn the far end of the road swings to the side (more far away, none at the car)
                val bend = turn * roadAlpha * w * 0.15f
                // t runs from the horizon (0) to the bottom (1); t² is the perspective scale for x and y alike
                fun bendAt(t: Float) = bend * (1f - t * t) * (1f - t * t)
                if (roadAlpha > 0.01f) {
                    val cx = w / 2
                    fun xAt(t: Float, side: Float) = cx + side * (h * 0.02f + t * t * h * 0.95f) + bendAt(t)
                    fun yAt(t: Float) = horizon + (h - horizon) * t * t
                    val steps = 16
                    asphalt.rewind()
                    asphalt.moveTo(xAt(0f, -1f), yAt(0f))
                    for (i in 1..steps) { val t = i / steps.toFloat(); asphalt.lineTo(xAt(t, -1f), yAt(t)) }
                    for (i in steps downTo 0) { val t = i / steps.toFloat(); asphalt.lineTo(xAt(t, 1f), yAt(t)) }
                    asphalt.close()
                    drawPath(asphalt, asphaltBrush, alpha = roadAlpha)
                    for (side in -1..1 step 2) {
                        for (i in 0 until steps) {
                            val t0 = i / steps.toFloat()
                            val t1 = (i + 1) / steps.toFloat()
                            drawLine(C.Violet.copy(alpha = 0.55f * roadAlpha), Offset(xAt(t0, side.toFloat()), yAt(t0)), Offset(xAt(t1, side.toFloat()), yAt(t1)), 2.5f)
                        }
                    }
                    val n = 7
                    for (k in -1..n) {
                        val t0 = ((k + road) / n).coerceIn(0f, 1f)
                        val t1 = ((k + road + 0.45f) / n).coerceIn(0f, 1f)
                        if (t1 <= t0) continue
                        drawLine(
                            Color.White.copy(alpha = (0.25f + 0.6f * t1) * roadAlpha),
                            Offset(cx + bendAt(t0), yAt(t0)), Offset(cx + bendAt(t1), yAt(t1)), 1.5f + t1 * 7f,
                        )
                    }
                }
                // speed streaks from the vanishing point: motion blur, stronger with speed and when pulling
                if (roadAlpha > 0f) {
                    val nitro = nitro.floatValue
                    val streak = (roadAlpha * (speed / 110f + accel.coerceAtLeast(0f) / 15f + nitro * 0.6f)).coerceIn(0f, 1f)
                    if (streak > 0.02f) {
                        val turbo = turbo.floatValue
                        val vx = w / 2 + bend
                        for (i in 0 until (26 + (nitro * 40).toInt())) {
                            // fixed pseudo-random rays, each with its own phase
                            val dx = StreakRays[i * 2]
                            val dy = StreakRays[i * 2 + 1]
                            val ph = (road * 2f + i * 0.37f) % 1f
                            val r0 = w * (0.10f + ph * 0.55f)
                            val r1 = r0 + w * (0.05f + 0.18f * streak + 0.35f * nitro) * (0.4f + ph)
                            val tint = if ((turbo > 0.3f || nitro > 0.2f) && i % 3 == 0) Color(0xFF7DD3FC) else Color.White
                            drawLine(
                                tint.copy(alpha = streak * 0.35f * (1f - ph) * (0.5f + 0.5f * ph * 2f).coerceAtMost(1f)),
                                Offset(vx + dx * r0, horizon + dy * r0), Offset(vx + dx * r1, horizon + dy * r1),
                                1f + 2.5f * ph,
                            )
                        }
                    }
                }
                // neon under-glow ellipse, brighter with rpm
                drawOval(floorBrush!!, topLeft = Offset(w * 0.16f, h * 0.70f), size = Size(w * 0.68f, h * 0.2f))
            }
        })
        val f = frame
        val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val rect = remember { android.graphics.RectF() }
        if (f != null) {
            Canvas(Modifier.fillMaxSize()) {
                val zoom = zoom.floatValue
                val roadAlpha = roadAlpha.floatValue
                val turbo = turbo.floatValue
                // fit the frame into the box (like ContentScale.Fit)
                // the car itself (not the frame with its empty margins) fits the room between the dials
                val scale = minOf(size.width * carShare / (f.width * src.carWidth(frameIdx).coerceAtLeast(0.35f)), size.height / f.height) * zoom * carScale
                val dw = f.width * scale
                val dh = f.height * scale
                // the car (not its frame) on the road's centre line, with or without the road
                val ox = carFrameLeft(size.width, dw, src.carX)
                val oy = (size.height - dh) / 2 + (1f - zoom) * size.height * 0.25f + (1f - carScale) * size.height * 0.32f
                drawIntoCanvas { cnv ->
                    val nc = cnv.nativeCanvas
                    rect.set(ox, oy, ox + dw, oy + dh)
                    nc.drawBitmap(f, null, rect, paint)
                    val pb = plate
                    val quads = src.plates.getOrNull(frameIdx)
                    if (pb != null && quads != null) {
                        val srcPts = floatArrayOf(0f, 0f, pb.width.toFloat(), 0f, pb.width.toFloat(), pb.height.toFloat(), 0f, pb.height.toFloat())
                        quads.values.forEach { q ->
                            val dst = FloatArray(8) { j -> if (j % 2 == 0) ox + q[j] * dw else oy + q[j] * dh }
                            val m = android.graphics.Matrix()
                            if (m.setPolyToPoly(srcPts, 0, dst, 0, 4)) nc.drawBitmap(pb, m, paint)
                        }
                    }
                }
                // exhaust: orange backfire, blue turbo flicker under boost (only in the rear view)
                if (frameIdx == src.driveFrame && roadAlpha > 0.5f && (flame > 0f || turbo > 0.15f)) src.exhausts.forEach { (exX, exY) ->
                    val ex = Offset(ox + exX * dw, oy + exY * dh)
                    val flick = 0.75f + 0.25f * kotlin.math.sin(clock * 47f) * kotlin.math.sin(clock * 31f)
                    if (turbo > 0.15f) {
                        val r = dw * 0.035f * (0.6f + turbo) * flick
                        drawCircle(
                            Brush.radialGradient(listOf(Color(0xFFE0F2FE), Color(0xFF38BDF8).copy(alpha = 0.8f), Color.Transparent), ex, r),
                            r, ex, alpha = turbo,
                        )
                    }
                    if (flame > 0f) {
                        val r = dw * (0.05f + 0.11f * flame) * flick
                        val c = Offset(ex.x, ex.y + r * 0.15f)
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color(0xFFFFF7D6), Color(0xFFFFC233), Color(0xFFFF6A00).copy(alpha = 0.7f), Color.Transparent),
                                c, r,
                            ),
                            r, c, alpha = flame.coerceAtMost(1f),
                        )
                    }
                }
            }
        }
        // NFS-style speed blur in front of everything: long streaks along the edges and a dark tinted vignette
        if (nitroOn) Canvas(Modifier.fillMaxSize()) {
            val nitro = nitro.floatValue
            val w = size.width
            val h = size.height
            val c = Offset(w / 2 + turn.floatValue * w * 0.15f * roadAlpha.floatValue, h * 0.24f)
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent, 0.55f to Color.Transparent,
                    0.85f to Color(0xFF0B1A3A).copy(alpha = 0.55f * nitro), 1f to Color(0xFF020617).copy(alpha = 0.85f * nitro),
                    center = c, radius = w * 0.62f,
                )
            )
            for (i in 0 until 46) {
                val dx = BlurRays[i * 2]
                val dy = BlurRays[i * 2 + 1]
                val ph = (road * 3f + i * 0.21f) % 1f
                val r0 = w * (0.30f + ph * 0.30f)
                val r1 = r0 + w * (0.12f + 0.30f * nitro)
                drawLine(
                    (if (i % 4 == 0) Color(0xFF93C5FD) else Color.White).copy(alpha = nitro * 0.45f * (1f - ph)),
                    Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * r1, c.y + dy * r1), 1.5f + 3f * ph,
                )
            }
        }
    }
}
