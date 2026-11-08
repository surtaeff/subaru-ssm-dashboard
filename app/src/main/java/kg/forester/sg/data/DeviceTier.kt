package kg.forester.sg.data

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import kg.forester.sg.service.AudioFx
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ui.MapFrames
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** How much the head unit can draw. */
enum class Tier { LOW, MID, HIGH }

/**
 * What a tier draws: [mapHz] the map's frames, [bars] the bands of the music's picture, [nitro] the blur on a hard pull,
 * [roadHz] how often the road under the car moves on, [chartHz] how often a chart slides, [chartPaintMs] at least this
 * between repaints of its lines, [sceneHz] how often the night road card moves on, [sceneReflection] the car's reflection
 * there. [EVERY_FRAME]: no limit.
 */
data class TierLook(
    val mapHz: Float, val bars: Int, val nitro: Boolean, val roadHz: Float, val chartHz: Float, val chartPaintMs: Long,
    val sceneHz: Float, val sceneReflection: Boolean,
)

const val EVERY_FRAME = 1000f

fun lookOf(t: Tier): TierLook = when (t) {
    Tier.LOW -> TierLook(mapHz = 25f, bars = 12, nitro = false, roadHz = 20f, chartHz = 8f, chartPaintMs = 500L, sceneHz = 15f, sceneReflection = false)
    Tier.MID -> TierLook(mapHz = 40f, bars = 24, nitro = true, roadHz = 30f, chartHz = 15f, chartPaintMs = 250L, sceneHz = 30f, sceneReflection = true)
    Tier.HIGH -> TierLook(mapHz = 60f, bars = 40, nitro = true, roadHz = EVERY_FRAME, chartHz = EVERY_FRAME, chartPaintMs = 250L, sceneHz = EVERY_FRAME, sceneReflection = true)
}

/** The setting's value: null is «Авто». */
fun tierOf(setting: String): Tier? = when (setting) {
    "low" -> Tier.LOW
    "mid" -> Tier.MID
    "high" -> Tier.HIGH
    else -> null
}

private val OLD_MALI = Regex("mali[- ]?(\\d{3}|t\\d|g31\\b|g51\\b)")

/** The GPUs that hold a head unit back: Mali 400/450, T-series, G31, G51 (Allwinner T3, PX6, SC9853) and PowerVR SGX and Rogue (PX5, UIS8581). */
fun isOldGpu(renderer: String): Boolean {
    val r = renderer.lowercase(Locale.ROOT)
    return OLD_MALI.containsMatchIn(r) || ("powervr" in r && ("sgx" in r || "rogue" in r))
}

/** [gpu] null or empty: not known, so not called new. A [manual] choice goes before all of it. */
fun tierFromRamAndGpu(ramMb: Long, gpu: String?, manual: Tier? = null): Tier = manual ?: when {
    ramMb <= 2048 || (gpu != null && isOldGpu(gpu)) -> Tier.LOW
    ramMb > 3072 && !gpu.isNullOrBlank() -> Tier.HIGH
    else -> Tier.MID
}

/** The tier with [drops] steps down for slow frames in this run; a manual choice stands. */
fun effectiveTier(detected: Tier, manual: Tier?, drops: Int): Tier =
    manual ?: Tier.entries[(detected.ordinal - drops).coerceAtLeast(0)]

/** The [p]th percentile of the first [n] of [v], in any order; [v] stays as it is. */
fun percentile(v: FloatArray, n: Int, p: Int): Float {
    if (n <= 0) return 0f
    val s = v.copyOf(n).also { it.sort() }
    return s[(kotlin.math.ceil(n * p / 100.0).toInt() - 1).coerceIn(0, n - 1)]
}

/** Two refreshes of the screen: a head unit holding half the screen's rate still does what it should. */
fun frameBudgetMs(refreshHz: Float): Float = 2000f / refreshHz.coerceAtLeast(30f)

/**
 * Says when frames have been over the budget for [STRETCHES] × [STRETCH_MS] without a break: the 95th percentile of each
 * stretch. Only a stretch of what the tier changes counts: at least [MIN_FRAMES] of those frames, and most of the frames
 * it had. A parked screen redraws its digits now and then, a static one is slow for reasons a lower tier doesn't touch.
 */
class FrameBudgetWatch(private val budgetMs: Float) {
    private val frames = FloatArray(MAX_FRAMES)
    private var n = 0
    private var start = -1L
    private var over = 0
    private var others = 0

    /** The 95th percentile of the last stretch judged, ms. */
    var lastP95 = 0f
        private set

    /** A new session: what went before doesn't count. */
    fun reset() {
        n = 0
        others = 0
        start = -1L
        over = 0
    }

    /** A frame of [ms] drawn at [nowMs], [tierDraws]: with something the tier changes moving; true when it's time to go one tier down. */
    fun frame(nowMs: Long, ms: Float, tierDraws: Boolean = true): Boolean {
        if (start < 0) start = nowMs
        var drop = false
        if (nowMs - start >= STRETCH_MS) {
            val judged = n >= MIN_FRAMES && others <= n && nowMs - start < 2 * STRETCH_MS
            if (judged) lastP95 = percentile(frames, n, 95)
            over = if (judged && lastP95 > budgetMs) over + 1 else 0
            if (over >= STRETCHES) { over = 0; drop = true }
            n = 0
            others = 0
            start = nowMs
        }
        if (!tierDraws) others++ else if (n < MAX_FRAMES) frames[n++] = ms
        return drop
    }

    private companion object {
        const val STRETCH_MS = 5000L
        const val STRETCHES = 6
        const val MIN_FRAMES = 30
        const val MAX_FRAMES = 1024
    }
}

/** "Auto"'s Bluetooth tries that find nobody: 5 s apart at first, up to half a minute. */
fun autoBtRetryPause() = RetryPause(firstMs = 5_000L, maxMs = 30_000L)

/** A debug build's watch on the main thread: a line for each message that held it [limitMs] or more. */
class StallWatch(private val limitMs: Long, private val out: (String) -> Unit) {
    private var from = -1L
    private var what = ""

    fun begin(nowMs: Long, what: String) {
        from = nowMs
        this.what = what
    }

    fun end(nowMs: Long) {
        if (from >= 0 && nowMs - from >= limitMs) out("${nowMs - from} ms: ${what.take(160)}")
        from = -1L
    }
}

/** Tries that fail wait longer and longer: [firstMs], twice that and so on up to [maxMs]. */
class RetryPause(private val firstMs: Long, private val maxMs: Long) {
    private var next = 0L
    private var pause = firstMs

    @Synchronized fun due(nowMs: Long) = nowMs >= next

    @Synchronized fun failed(nowMs: Long) {
        next = nowMs + pause
        pause = minOf(pause * 2, maxMs)
    }

    @Synchronized fun reset() {
        next = 0L
        pause = firstMs
    }
}

/**
 * Is the frame from [fromNs] to [toNs] one of the [hz] a second? By the frames' clock, not by a count of its own, so
 * what goes at one rate changes on the same frames, and there is one redraw for all of it. [EVERY_FRAME]: all of them.
 */
fun frameTicks(fromNs: Long, toNs: Long, hz: Float): Boolean =
    hz >= EVERY_FRAME || (fromNs * (hz / 1e9)).toLong() != (toNs * (hz / 1e9)).toLong()

/** A chart's repaints: at most one per the time given, and what came meanwhile goes when that has passed. */
class RepaintPace {
    private var last = Long.MIN_VALUE
    private var waiting = false

    /** Something new to paint; true: now. */
    fun ask(nowMs: Long, everyMs: Long): Boolean {
        if (everyMs > 0L && last != Long.MIN_VALUE && nowMs - last < everyMs) {
            waiting = true
            return false
        }
        return painted(nowMs)
    }

    /** True: what waited is to be painted now. */
    fun due(nowMs: Long, everyMs: Long): Boolean = waiting && nowMs - last >= everyMs && painted(nowMs)

    /** No more frames are coming: true if something is still waiting. */
    fun flush(nowMs: Long): Boolean = waiting && painted(nowMs)

    private fun painted(nowMs: Long): Boolean {
        last = nowMs
        waiting = false
        return true
    }
}

/** p50, p95 and p99 of the frames of each [everyMs], for the log of a debug build. */
class FrameReport(private val everyMs: Long, private val out: (String) -> Unit) {
    private val frames = FloatArray(MAX_FRAMES)
    private var n = 0
    private var total = 0
    private var start = -1L

    fun frame(nowMs: Long, ms: Float) {
        if (start < 0) start = nowMs
        if (nowMs - start >= everyMs) {
            out("%d frames in %d s: p50 %.1f, p95 %.1f, p99 %.1f ms".format(Locale.ROOT, total, (nowMs - start) / 1000,
                percentile(frames, n, 50), percentile(frames, n, 95), percentile(frames, n, 99)))
            n = 0
            total = 0
            start = nowMs
        }
        if (n < MAX_FRAMES) frames[n++] = ms
        total++
    }

    private companion object {
        const val MAX_FRAMES = 8192
    }
}

/**
 * The tier of this head unit: from its memory and GPU, or the driver's choice in Настройки. Slow frames for half a minute,
 * on what the tier changes, take it a step down until the next start or wake from ACC; the choice is not touched by that.
 */
object DeviceTier {
    val current = MutableStateFlow(Tier.HIGH)
    /** What the settings say under «Авто», e.g. "2 ГБ, Mali-G52". */
    val found = MutableStateFlow("")

    @Volatile var look = lookOf(Tier.HIGH)
        private set

    private var ramMb = 0L
    @Volatile private var gpu: String? = null
    @Volatile private var drops = 0
    @Volatile private var watch: FrameBudgetWatch? = null

    fun init(ctx: Context) {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        ramMb = mi.totalMem / (1024 * 1024)
        val s = Repo.settings
        // asked once per firmware: the GPU doesn't change, a GL context costs time on every start
        val build = android.os.Build.FINGERPRINT.orEmpty()
        if (s.gpuBuild == build) gpu = s.gpuName
        apply("start")
        if (s.gpuBuild != build) Thread({
            val name = runCatching { askGpu() }.getOrNull()
            if (!name.isNullOrBlank()) {
                s.gpuName = name
                s.gpuBuild = build
                gpu = name
                apply("gpu")
            }
        }, "gpu").start()
    }

    /** The head unit woke from ACC: a new session, with what it could do at its start. */
    fun newSession() {
        frameHandler.post {
            watch?.reset()
            if (drops > 0) {
                drops = 0
                apply("wake")
            }
        }
    }

    /** Called on each frame by what the tier slows down (road, charts, map, music's picture). */
    fun drawing() { drewNs = System.nanoTime() }

    @Volatile private var drewNs = 0L

    private fun drewJustNow() = System.nanoTime() - drewNs < DREW_NS

    private const val DREW_NS = 600_000_000L

    /** The choice in the settings changed. */
    fun choiceChanged() = apply("choice")

    @Synchronized private fun apply(why: String) {
        val manual = tierOf(Repo.settings.tierChoice)
        val t = effectiveTier(tierFromRamAndGpu(ramMb, gpu), manual, drops)
        val l = lookOf(t)
        look = l
        MapFrames.hz = l.mapHz
        AudioFx.bars = l.bars
        found.value = "%.1f ГБ".format(ramMb / 1024f) + (gpu?.let { ", $it" } ?: "")
        val was = current.value
        current.value = t
        if (t != was || why != "gpu") SsmTrace.log("tier: $t ($why) ram=${ramMb}MB gpu=${gpu ?: "?"} manual=${manual ?: "-"} drops=$drops", keep = true)
    }

    /** The renderer's name, from a small context of its own; off the main thread. */
    private fun askGpu(): String? {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(d, v, 0, v, 1)) return null
        try {
            val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(d, attrs, 0, cfg, 0, 1, count, 0) || count[0] == 0) return null
            val ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (!EGL14.eglMakeCurrent(d, surface, surface, ctx)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(d, surface)
                EGL14.eglDestroyContext(d, ctx)
            }
        } finally {
            // not eglTerminate: the display is the one the screen itself draws on
            EGL14.eglReleaseThread()
        }
    }

    /** Watches this window's frames, off the main thread. */
    fun watchFrames(window: Window, refreshHz: Float) {
        if (android.os.Build.VERSION.SDK_INT < 24) return // FrameMetrics is Android 7.0+: no frame watch on Android 6
        val w = FrameBudgetWatch(frameBudgetMs(refreshHz))
        watch = w
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            if (watch === w && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L && current.value != Tier.LOW && tierOf(Repo.settings.tierChoice) == null &&
                w.frame(android.os.SystemClock.elapsedRealtime(), m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f, drewJustNow())
            ) {
                drops++
                apply("slow frames, p95 %.0f ms".format(Locale.ROOT, w.lastP95))
            }
        }, frameHandler)
    }

    private val frameHandler by lazy { Handler(HandlerThread("frames").apply { start() }.looper) }
}
