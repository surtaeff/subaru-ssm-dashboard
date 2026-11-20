package kg.forester.sg

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kg.forester.sg.ssm.SsmTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The last events of the process (its start, the service, the screen, ACC, USB, a blocked main thread) in a small file,
 * so that the next start can say how the run before ended. A process killed by the system, an ANR or a native crash
 * leaves no Java trace for [CrashLog], the in-memory log dies with it, and the Teyes units (sprd ums512, «Android 11»
 * with SDK 29) have no ApplicationExitInfo: 1.0-beta12 logs of «the app closes by itself» began with a clean start and
 * nothing about the end of the run before. All file work goes on one background thread, never the main one.
 */
object RunTrail {
    private const val FILE = "last-run.txt"
    const val MAX_LINES = 40
    /** How many of the previous run's last lines go into the log. */
    const val SHOWN = 15

    private val lines = ArrayDeque<String>(MAX_LINES)
    @Volatile private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "run-trail").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** At the process start: the previous run's end into the log, then this run's own file. */
    fun start(app: Application, version: String) {
        if (file != null) return
        val f = File(app.filesDir, FILE)
        file = f
        val now = System.currentTimeMillis()
        io.execute {
            runCatching {
                if (f.isFile) previous(f.readText(), f.lastModified(), now)?.let { SsmTrace.log(it, keep = true) }
            }
        }
        note("process start: CarPulse $version, pid ${android.os.Process.myPid()}")
        // who brought the process up (a foreground service, a receiver, the activity) and whether we are the home app,
        // which Android starts again at once after a death: «closes and opens again as if from scratch»
        io.execute {
            runCatching {
                val st = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
                val home = app.packageManager.resolveActivity(
                    android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo?.packageName == app.packageName
                val line = "process: importance ${st.importance} reason ${st.importanceReasonCode}, home app $home"
                note(line)
                SsmTrace.log(line, keep = true)
            }
        }
    }

    /** An event worth seeing after a death; cheap on any thread (the file is written in the background). */
    fun note(text: String) {
        val f = file ?: return
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast("${fmt.format(Date())} ${text.replace('\n', ' ')}")
        }
        io.execute {
            val all = synchronized(lines) { lines.joinToString("\n", postfix = "\n") }
            runCatching { f.writeText(all) }
        }
    }

    /** Still alive: the file's time is the last sign of life the next start reads (one metadata write, no data). */
    fun touch() {
        val f = file ?: return
        io.execute { runCatching { f.setLastModified(System.currentTimeMillis()) } }
    }

    /**
     * What the log says about the run before: when it last showed life ([aliveMs], the file's time) against this start
     * ([nowMs]), and its last [SHOWN] events. Null when there was nothing.
     */
    fun previous(text: String, aliveMs: Long, nowMs: Long): String? {
        val ls = text.lines().filter { it.isNotBlank() }
        if (ls.isEmpty()) return null
        val ago = if (aliveMs in 1..nowMs) " (${(nowMs - aliveMs) / 1000} s before this start)" else ""
        val alive = if (aliveMs > 0) SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(aliveMs)) else "?"
        val shown = ls.takeLast(SHOWN)
        val cut = if (ls.size > shown.size) "  … ${ls.size - shown.size} earlier\n" else ""
        return "the previous run: last sign of life $alive$ago, its last events:\n$cut" + shown.joinToString("\n") { "  $it" }
    }
}

/** What the watch on the main thread has to say at a moment. */
sealed class Stall {
    /** No message of the main thread has run for [ms]; [first]: the first word on this stall, else the second, later one. */
    data class Blocked(val ms: Long, val first: Boolean) : Stall()
    /** The stall that was reported is over: it lasted about [ms]. */
    data class Over(val ms: Long) : Stall()
}

/**
 * When a main thread that has not run its ticks is worth a word: at [firstMs] (before Android's 5 s input ANR) and once
 * more at [againMs] (the broadcast's 10 s), then when it runs again. One stall is told about at most twice.
 */
class StallClock(private val firstMs: Long = 4000L, private val againMs: Long = 10_000L) {
    /** The last tick before the reported stall, -1 when none is reported. */
    private var from = -1L
    private var said = 0

    /** [nowMs] and the time the main thread last ran a tick, [ackMs], both uptime. */
    fun check(nowMs: Long, ackMs: Long): Stall? {
        if (from >= 0 && ackMs > from) {
            val d = ackMs - from
            from = -1L; said = 0
            return Stall.Over(d)
        }
        val gap = nowMs - ackMs
        if (said == 0 && gap >= firstMs) { from = ackMs; said = 1; return Stall.Blocked(gap, true) }
        if (said == 1 && gap >= againMs) { said = 2; return Stall.Blocked(gap, false) }
        return null
    }
}

/**
 * A watch on the main thread for release builds, cheap: a background thread posts a tick to it every second and, when
 * the tick has not run for 4 s, logs where the main thread stands (an ANR follows at 5 s for a touch, so this is often the
 * last line of a run, and it goes into [RunTrail] for the next start). Uptime, so a unit asleep is no stall. Also the
 * [RunTrail]'s sign of life every half a minute.
 */
object MainWatch {
    private const val TICK_MS = 1000L
    private const val BEAT_MS = 30_000L
    /** Stalls told about in one process: a unit that keeps choking must not fill the log. */
    private const val MAX_REPORTS = 10
    const val FRAMES = 25
    @Volatile private var ackMs = 0L
    private val pending = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val looper = Looper.getMainLooper()
        val main = Handler(looper)
        val tick = Runnable { ackMs = SystemClock.uptimeMillis(); pending.set(false) }
        ackMs = SystemClock.uptimeMillis()
        Thread({
            val clock = StallClock()
            var reports = 0
            var told = false
            var beat = 0L
            while (true) {
                if (pending.compareAndSet(false, true)) main.post(tick)
                try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                when (val s = clock.check(now, ackMs)) {
                    is Stall.Blocked -> if (reports < MAX_REPORTS || !s.first) {
                        if (s.first) { reports++; told = true }
                        if (told) report(stallText(s, looper.thread.stackTrace))
                    }
                    is Stall.Over -> if (told) { told = false; report(stallText(s, emptyArray())) }
                    null -> {}
                }
                if (now - beat >= BEAT_MS) { beat = now; RunTrail.touch() }
            }
        }, "main-watch").apply { isDaemon = true; start() }
    }

    private fun report(text: String) {
        // the trail first: if the main thread holds the log's lock, the next start still learns about it
        RunTrail.note(text.lineSequence().take(9).joinToString(" < "))
        SsmTrace.log(text, keep = true)
    }

    /** The log's lines for [s]: with the main thread's top [FRAMES] frames while it is blocked. */
    fun stallText(s: Stall, stack: Array<StackTraceElement>): String = when (s) {
        is Stall.Over -> "main thread free again after %.1f s".format(Locale.US, s.ms / 1000f)
        is Stall.Blocked -> {
            val head = if (s.first) "main thread blocked for %.1f s".format(Locale.US, s.ms / 1000f)
            else "main thread still blocked after %.1f s".format(Locale.US, s.ms / 1000f)
            val frames = stack.take(FRAMES).joinToString("") { "\n  at $it" }
            val more = if (stack.size > FRAMES) "\n  … ${stack.size - FRAMES} more" else ""
            head + frames + more
        }
    }
}

/**
 * Android's own record of how the previous processes ended (API 30+): each new one once, with its description and, for an
 * ANR, the main thread of its trace; for a native crash the readable bits of its tombstone. Off the main thread.
 */
object ExitInfo {
    private const val PREFS = "diag"
    private const val SEEN = "exitSeenMs"
    /** An ANR trace has every thread of the process: the main one is near its top. */
    private const val TRACE_BYTES = 256 * 1024
    const val ANR_LINES = 40
    const val NATIVE_LINES = 25

    fun logNew(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching { logNew30(ctx) }.onFailure { SsmTrace.log("exit reasons: $it", keep = true) }
    }

    @androidx.annotation.RequiresApi(30)
    private fun logNew30(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = sp.getLong(SEEN, 0L)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val fresh = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }
        if (fresh.isEmpty()) return
        sp.edit().putLong(SEEN, fresh.maxOf { it.timestamp }).apply()
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        fresh.sortedBy { it.timestamp }.forEach { r ->
            val head = "exit: ${reasonName(r.reason)} at ${time.format(Date(r.timestamp))}, importance ${r.importance}, " +
                "status ${r.status}, pss ${r.pss / 1024} MB, rss ${r.rss / 1024} MB ${r.description.orEmpty()}".trimEnd()
            val trace = when (r.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> readTrace(r)?.let { anrMain(String(it, Charsets.UTF_8), ANR_LINES) }
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> readTrace(r)?.let { nativeLines(it, NATIVE_LINES) }
                else -> null
            }
            SsmTrace.log(if (trace.isNullOrEmpty()) head else "$head\n" + trace.joinToString("\n") { "  $it" }, keep = true)
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun readTrace(r: android.app.ApplicationExitInfo): ByteArray? = runCatching {
        r.traceInputStream?.use { ins ->
            val buf = java.io.ByteArrayOutputStream()
            val b = ByteArray(16 * 1024)
            while (buf.size() < TRACE_BYTES) { val n = ins.read(b); if (n < 0) break; buf.write(b, 0, n) }
            buf.toByteArray()
        }
    }.getOrNull()

    fun reasonName(r: Int): String = when (r) {
        1 -> "exit self"
        2 -> "signaled"
        3 -> "low memory"
        4 -> "crash"
        5 -> "native crash"
        6 -> "ANR"
        7 -> "initialization failure"
        8 -> "permission change"
        9 -> "excessive resource use"
        10 -> "user requested"
        11 -> "user stopped"
        12 -> "dependency died"
        13 -> "other"
        14 -> "freezer"
        15 -> "package state change"
        16 -> "package updated"
        else -> "reason $r"
    }

    /** The main thread's block of an ANR trace ("main" prio=5 tid=1 … up to the blank line), at most [max] lines. */
    fun anrMain(text: String, max: Int): List<String> {
        val ls = text.lines()
        val at = ls.indexOfFirst { it.startsWith("\"main\"") }
        if (at < 0) return emptyList()
        return ls.drop(at).takeWhile { it.isNotBlank() }.take(max).map { it.trimEnd() }
    }

    /**
     * A native crash's tombstone (protobuf since Android 12) is not text: its readable runs that say what failed and where
     * (the signal, the abort message, the libraries and our own frames), each once, at most [max].
     */
    fun nativeLines(bytes: ByteArray, max: Int): List<String> {
        val out = LinkedHashSet<String>()
        val run = StringBuilder()
        val wanted = Regex("""\.so\b|signal|SIG[A-Z]{3,}|[Aa]bort|Cause|kg\.forester""")
        fun flush() {
            if (run.length >= 6) run.toString().trim().takeIf { wanted.containsMatchIn(it) }?.let { out += it.take(200) }
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
            if (out.size >= max) break
        }
        flush()
        return out.take(max)
    }
}
