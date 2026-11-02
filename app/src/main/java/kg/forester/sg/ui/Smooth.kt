package kg.forester.sg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kg.forester.sg.data.frameTicks
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.exp

/** Something moved by [SmoothLoop]: [step] gets the seconds since the last frame and says whether it still moves. */
fun interface Stepper {
    fun step(dt: Float): Boolean
}

/**
 * A needle on uneven readings. The ECU's values come 2 to 12 times a second and unevenly, the screen wants one per
 * frame: [value] runs after the last reading on an exact critically damped step, so it never rings, looks the same at
 * 30, 50 or 60 Hz and goes on from where it is when the next reading comes (no kink). Its reaction time follows the
 * readings' interval; a change of more than 30 % of [span] between two readings (revs on a gear change, a pedal stamped
 * on) still travels, on the shortest reaction time. Only what is no motion at all goes at once ([snap]): a first reading
 * after none, a new scale, the return from the background.
 *
 * Only for what is drawn: alerts, warning colours and logs take the raw reading, this one lags behind it on purpose.
 * [tau] given: a fixed reaction time, for a value that is itself a transition (nothing follows the readings then).
 * [shownHz] given: what is drawn with it changes only that often, on the same frames as the rest of the picture.
 */
class Smooth(initial: Float, var span: Float = Float.POSITIVE_INFINITY, tau: Float? = null, private val shownHz: (() -> Float)? = null) : Stepper {
    val value: MutableFloatState = mutableFloatStateOf(initial)

    private val fixedTau = tau != null
    private var x = initial
    private var v = 0f
    private var target = initial
    private var lastNs = 0L
    private var fast = false
    private var wasKnown = true
    private var lastShown = initial

    /**
     * A move smaller than this isn't handed out until it adds up: under half a pixel nothing changes on the screen, and each
     * change there redraws the whole window. What it comes to rest on always goes.
     */
    var quantum: Float = 0f

    /** Seconds; about a fifth of the time to settle. */
    var tau: Float = tau ?: 0.12f
        private set

    /** Units per second, for the tests. */
    val velocity: Float get() = v

    val moving: Boolean get() = !atRest()

    /** Close enough to rest not to show: 0.05 % of the scale is a tenth of a degree on a 270° dial. */
    private val eps: Float get() = if (span.isInfinite()) 0.001f else span * 0.0005f

    private fun atRest() = x == target && v == 0f

    /** A new reading [t], taken at [nowNs]. Moves nothing by itself: the next frames go on towards it. */
    fun sample(t: Float, nowNs: Long) {
        if (t.isNaN()) return
        if (!fixedTau && lastNs != 0L) {
            val gap = ((nowNs - lastNs) / 1e9f).coerceIn(0.05f, 0.6f)
            tau = (tau + (gap * 0.45f - tau) * 0.2f).coerceIn(0.06f, 0.25f)
        }
        lastNs = nowNs
        fast = abs(t - target) > span * 0.3f
        target = t
    }

    /** A reading from a value that may be missing, then [t] is what stands for it: the first real one after none goes at once. */
    fun follow(t: Float, known: Boolean, nowNs: Long) {
        val first = known && !wasKnown
        wasKnown = known
        sample(t, nowNs)
        if (first) snap(t)
    }

    /** To [t] at once, standing still there. */
    fun snap(t: Float = target) {
        x = t
        target = t
        v = 0f
        fast = false
        lastShown = t
        value.floatValue = t
    }

    override fun step(dt: Float): Boolean {
        val w = 1f / if (fast) FAST_TAU else tau
        val e = x - target
        val k = (v + w * e) * dt
        val d = exp(-w * dt)
        x = target + (e + k) * d
        v = (v - w * k) * d
        if (abs(x - target) < eps && abs(v) < eps * w) {
            x = target
            v = 0f
            fast = false
        }
        val rest = atRest()
        if (rest || ((shownHz == null || SmoothDriver.ticks(shownHz.invoke())) && abs(x - lastShown) >= quantum)) {
            lastShown = x
            value.floatValue = x
        }
        return !rest
    }

    private companion object {
        /** Seconds, for a big change between two readings: there in about a quarter of a second. */
        const val FAST_TAU = 0.06f
    }
}

/**
 * The one frame loop of everything that moves with the readings: it asks for frames only while something moves, so a
 * parked car with nothing changing costs no frame at all. One request a frame for all of them, each step by the time
 * that really passed.
 */
class SmoothLoop(private val now: () -> Long = { System.nanoTime() }) {
    private val trackers = ArrayList<Smooth>()
    private var returnedNs = Long.MIN_VALUE / 2

    /** [s] is on the screen; it comes to its reading at once on [onReturn]. */
    fun register(s: Smooth) { trackers.add(s) }

    fun unregister(s: Smooth) { trackers.remove(s) }

    /** The screen is back from the background: what happened meanwhile is no motion to replay. */
    fun onReturn() {
        returnedNs = now()
        trackers.forEach { it.snap() }
    }

    /** Just back: a reading held up by the stopped screen goes at once, too. */
    fun returning(): Boolean = now() - returnedNs < RETURN_NS

    private var frameFromNs = 0L
    private var frameToNs = 0L

    /** Is this frame one of the [hz] a second? For what doesn't have to move on every frame. */
    fun ticks(hz: Float): Boolean = frameTicks(frameFromNs, frameToNs, hz)

    private val active = ArrayList<Stepper>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** [s] has something to do: it gets steps until it says it is done. */
    fun wake(s: Stepper) {
        if (s !in active) active.add(s)
        wakeUp.trySend(Unit)
    }

    fun drop(s: Stepper) {
        active.remove(s)
    }

    suspend fun run() {
        while (true) {
            while (active.isEmpty()) wakeUp.receive()
            var last = now()
            while (active.isNotEmpty()) {
                withFrameNanos { t ->
                    val dt = ((t - last) / 1e9f).coerceAtLeast(0f)
                    frameFromNs = last
                    frameToNs = t
                    last = t
                    var i = active.size - 1
                    while (i >= 0) {
                        val s = active[i]
                        if (!s.step(dt)) active.remove(s)
                        // a step may have dropped others
                        i = minOf(i, active.size) - 1
                    }
                }
            }
        }
    }
}

private const val RETURN_NS = 500_000_000L

/**
 * True from [frames] frames after this comes on the screen. A screen shows at once and fills in behind it: what is composed in one
 * frame holds up the frame and the finger, on a slow unit for hundreds of ms. The caller keeps the room, so nothing moves.
 */
@Composable
fun rememberLater(frames: Int = 1): Boolean {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(frames) { withFrameNanos { } }
        on = true
    }
    return on
}

/** The app's loop; [SmoothRoot] runs it. */
val SmoothDriver = SmoothLoop()

/** At the root of the screen: the loop lives as long as the screen. */
@Composable
fun SmoothRoot() {
    LaunchedEffect(Unit) { SmoothDriver.run() }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) SmoothDriver.onReturn()
        }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
}

/**
 * A [Smooth] following [source]. Read its value while drawing or in a graphicsLayer only: then a frame of the needle
 * redraws the needle and recomposes nothing.
 */
@Composable
fun rememberSmooth(
    source: State<Float>, span: Float = Float.POSITIVE_INFINITY, tau: Float? = null, shownHz: (() -> Float)? = null,
    /** False while there is no reading and [source] only stands for one: the first real one goes at once. */
    known: () -> Boolean = AlwaysKnown,
): Smooth {
    val s = remember { Smooth(Snapshot.withoutReadObservation { source.value }, span, tau, shownHz) }
    val isKnown = rememberUpdatedState(known)
    LaunchedEffect(source) {
        snapshotFlow { source.value to isKnown.value() }.collect { (t, k) ->
            if (SmoothDriver.returning()) s.snap(t) else s.follow(t, k, System.nanoTime())
            if (s.moving) SmoothDriver.wake(s)
        }
    }
    // a new scale is no motion: the needle stands where the new scale puts the reading
    LaunchedEffect(s, span) {
        if (s.span != span) { s.span = span; s.snap(Snapshot.withoutReadObservation { source.value }) }
    }
    DisposableEffect(s) {
        SmoothDriver.register(s)
        onDispose { SmoothDriver.unregister(s); SmoothDriver.drop(s) }
    }
    return s
}

private val AlwaysKnown: () -> Boolean = { true }

/** The same for a plain value of a composable that recomposes with it anyway. */
@Composable
fun rememberSmooth(target: Float, span: Float = Float.POSITIVE_INFINITY, tau: Float? = null, known: () -> Boolean = AlwaysKnown): Smooth =
    rememberSmooth(rememberUpdatedState(target), span, tau, known = known)

/**
 * Seconds running while [on], from one frame loop with the needles (music bars and the like). Read while drawing.
 * Stands still and costs nothing when off.
 */
@Composable
fun rememberSmoothClock(on: Boolean): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    val active by rememberUpdatedState(on)
    val st = remember {
        Stepper { dt ->
            // a float of seconds loses its smoothness after hours: the pictures using it repeat long before that
            seconds.floatValue = (seconds.floatValue + dt) % 3600f
            active
        }
    }
    LaunchedEffect(on) { if (on) SmoothDriver.wake(st) }
    DisposableEffect(st) { onDispose { SmoothDriver.drop(st) } }
    return seconds
}

/** How long a number stays before the next: 4–5 changes a second, what the eye can still read. */
const val DIGITS_MS = 220L

/**
 * What a number on the screen shows: at most one change per [DIGITS_MS], and a value flicking between two neighbours
 * (14,7 / 14,8) stays put until the new one lasts. Words and «—» have no neighbours and show as soon as they may.
 * Colours do not go through here: a warning is worked out from the raw reading, at once.
 */
class DigitGate(initial: String, private val everyMs: Long = DIGITS_MS) {
    var shown: String = initial
        private set
    private var nextMs = 0L
    private var heldSince = -1L
    private var held = ""

    /** The latest [text] at [nowMs]. Returns how many ms later to offer it again, 0 when nothing is waiting. */
    fun offer(text: String, nowMs: Long): Long {
        if (text == shown) { heldSince = -1L; return 0L }
        val a = NUMBER.matchEntire(shown)
        val b = NUMBER.matchEntire(text)
        if (a == null || b == null || a.groupValues[3] != b.groupValues[3]) return show(text, nowMs)
        if (nowMs < nextMs) return nextMs - nowMs
        if (neighbours(a.groupValues[1], b.groupValues[1])) {
            if (heldSince < 0L || held != text) { heldSince = nowMs; held = text; return everyMs }
            if (nowMs - heldSince < everyMs) return everyMs - (nowMs - heldSince)
        }
        return show(text, nowMs)
    }

    private fun show(text: String, nowMs: Long): Long {
        shown = text
        nextMs = nowMs + everyMs
        heldSince = -1L
        return 0L
    }

    private companion object {
        /** A number with what follows it (a unit). */
        val NUMBER = Regex("([-+]?\\d+([.,]\\d+)?)(.*)")

        /** One step of the last digit apart, with as many decimals. */
        fun neighbours(a: String, b: String): Boolean {
            if (decimals(a) != decimals(b)) return false
            val x = a.filter { it.isDigit() || it == '-' }.toLongOrNull() ?: return false
            val y = b.filter { it.isDigit() || it == '-' }.toLongOrNull() ?: return false
            return abs(x - y) == 1L
        }

        private fun decimals(s: String): Int = s.indexOfFirst { it == '.' || it == ',' }.let { if (it < 0) 0 else s.length - it }
    }
}

/**
 * The text of a number from [read] (which may read states), as [DigitGate] lets it through. [read] runs apart from the
 * composition: a new reading recomposes only what reads the returned state, and that at most 4–5 times a second.
 */
@Composable
fun rememberDigits(everyMs: Long = DIGITS_MS, read: () -> String): State<String> {
    val latest = rememberUpdatedState(read)
    val initial = remember { Snapshot.withoutReadObservation { read() } }
    val shown = remember { mutableStateOf(initial) }
    val gate = remember { DigitGate(initial, everyMs) }
    LaunchedEffect(gate) {
        snapshotFlow { latest.value() }.collectLatest { t ->
            while (true) {
                val wait = gate.offer(t, android.os.SystemClock.uptimeMillis())
                shown.value = gate.shown
                if (wait <= 0L) break
                delay(wait)
            }
        }
    }
    return shown
}

/**
 * A chart's time: from the readings' own clock, running on between them on the frames' clock, so the line slides evenly
 * however unevenly the readings come. Starts again from a reading only if it is far off (the loop slept).
 */
class ChartClock {
    private var baseMs = 0L
    private var baseS = 0.0
    private var started = false

    fun onSample(tMs: Long, elapsedS: Double) {
        if (!started || abs(tMs - nowMs(elapsedS)) > 1000L) {
            baseMs = tMs
            baseS = elapsedS
            started = true
        }
    }

    fun nowMs(elapsedS: Double): Long = baseMs + ((elapsedS - baseS) * 1000.0).toLong()

    /** The frames' clock stood still for a while: the next reading sets the time. */
    fun reset() { started = false }
}

/**
 * The last readings of one line of a chart: times in ms and values, [Float.NaN] for no reading, in fixed arrays filled
 * round and round. Nothing is allocated for a new reading. For one thread (the screen's).
 */
class ChartRing(val capacity: Int) {
    companion object {
        /** A silence this long between two readings breaks the line. */
        const val BREAK_MS = 2500L
    }

    private val times = LongArray(capacity)
    private val values = FloatArray(capacity)
    private var head = 0

    var size = 0
        private set

    /** When the line last changed, -1 before the first reading. */
    var lastChangeMs = -1L
        private set

    val newestMs: Long get() = if (size == 0) 0L else timeAt(size - 1)

    /** The newest reading, [Float.NaN] if none. */
    val last: Float get() = if (size == 0) Float.NaN else valueAt(size - 1)

    fun add(t: Long, v: Float) {
        val before = last
        if (size == 0 || !(before == v || (before.isNaN() && v.isNaN()))) lastChangeMs = t
        times[head] = t
        values[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        head = 0
        size = 0
        lastChangeMs = -1L
    }

    /** [i] from the oldest. */
    fun timeAt(i: Int): Long = times[(head - size + i + capacity) % capacity]

    fun valueAt(i: Int): Float = values[(head - size + i + capacity) % capacity]

    /** The line's height at [tMs], straight between two readings; the newest past the end, nothing beside a gap or across a break. */
    fun at(tMs: Long, breakMs: Long = BREAK_MS): Float {
        if (size == 0) return Float.NaN
        if (tMs >= newestMs) return last
        if (tMs < timeAt(0)) return Float.NaN
        var i = size - 1
        while (timeAt(i - 1) > tMs) i--
        val a = valueAt(i - 1)
        val b = valueAt(i)
        if (a.isNaN() || b.isNaN()) return Float.NaN
        val t0 = timeAt(i - 1)
        val t1 = timeAt(i)
        if (t1 - t0 > breakMs) return Float.NaN
        return if (t1 == t0) b else a + (b - a) * (tMs - t0) / (t1 - t0)
    }

    /** Nothing has changed on the line for [windowMs]: drawn at any time it looks the same. */
    fun flat(nowMs: Long, windowMs: Long) = nowMs - lastChangeMs > windowMs

    /**
     * The line as points for the width [width], its right edge at the time [atMs], [windowMs] wide, X from the readings'
     * times: pairs into [out], and for each unbroken stretch its first point and number of points into [runs]. Returns
     * the number of stretches. What lies left of the window is left out, but for one point, so the line reaches the edge.
     * More than [breakMs] between two readings is a break: no line is drawn across a silence, however short the reconnect.
     */
    inline fun project(
        out: FloatArray, runs: IntArray, atMs: Long, windowMs: Long, width: Float, breakMs: Long = BREAK_MS, yOf: (Float) -> Float,
    ): Int {
        val px = width / windowMs
        val from = atMs - windowMs
        var first = 0
        while (first < size - 1 && timeAt(first + 1) <= from) first++
        // that one point only if the line goes on from it
        if (first < size - 1 && timeAt(first) < from && timeAt(first + 1) - timeAt(first) > breakMs) first++
        var points = 0
        var count = 0
        var open = false
        for (i in first until size) {
            val v = valueAt(i)
            if (v.isNaN()) { open = false; continue }
            if (open && timeAt(i) - timeAt(i - 1) > breakMs) open = false
            if (!open) {
                runs[count * 2] = points
                runs[count * 2 + 1] = 0
                count++
                open = true
            }
            out[points * 2] = width - (atMs - timeAt(i)) * px
            out[points * 2 + 1] = yOf(v)
            runs[(count - 1) * 2 + 1]++
            points++
        }
        return count
    }
}

/**
 * Whose turn it is to paint its picture anew, one a frame in the order they asked: several charts getting a reading at
 * once would all re-raster on one frame and make it several frames long. Asking while waiting keeps the place, so
 * nobody waits longer than the number of those ahead. For one thread (the screen's).
 */
class RecordTurns<T : Any> {
    private val waiting = ArrayDeque<T>()

    val pending: Boolean get() = waiting.isNotEmpty()

    fun ask(x: T) {
        if (x !in waiting) waiting.addLast(x)
    }

    fun drop(x: T) {
        waiting.remove(x)
    }

    /** Who goes on this frame. */
    fun next(): T? = waiting.removeFirstOrNull()
}
