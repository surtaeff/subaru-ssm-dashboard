package kg.forester.sg

import android.app.Application
import kg.forester.sg.ssm.SsmTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The earliest code of the process, before any activity or service: the crash handler goes in here. */
class CarPulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // before anything goes online: Android 6–7.0 don't trust Let's Encrypt by themselves (see TlsRoots)
        kg.forester.sg.service.TlsRoots.install(this)
        // how the previous run ended when it left no crash trace (a kill, an ANR, a native crash), and a watch on the
        // main thread that logs where it is stuck before Android's ANR kills the process
        RunTrail.start(this, CrashLog.version)
        MainWatch.start()
        Thread({ ExitInfo.logNew(this) }, "exit-info").apply { isDaemon = true; start() }
        // Before any service or activity: Android brings EcuService back by itself (a sticky service after the head unit's
        // sleep or a killed process) and its fields read Repo.settings while it is being constructed, before its onCreate.
        // beta11 crashed that way at ignition off (BK7Y68V2 and Роман, 2026-11-18): «lateinit property settings has not
        // been initialized» in EcuService.<init>. Repo.init is idempotent, MainActivity and EcuService still call it.
        kg.forester.sg.data.Repo.init(this)
    }
}

/**
 * A crash leaves its stack trace: in the exchange log at once (kept, not in the polling lines) and in a file that the next
 * start puts back into the log, since the in-memory log dies with the process. Then the handler that was there before
 * (Android's: the crash dialog, the exit reason) goes on as without it.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    /** A deep recursion or a long cause chain must not fill the log. */
    private const val MAX_LINES = 120
    private const val MAX_CHARS = 16_000

    /** The installed versionName, known after [install]. */
    var version = "?"
        private set

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        version = ver
        runCatching { SsmTrace.init(app) }
        Thread.setDefaultUncaughtExceptionHandler(Handler(app.filesDir, ver, prev))
        restore(app.filesDir)?.let { SsmTrace.log("the previous run crashed:\n$it", keep = true) }
    }

    /** The stack trace as it goes to the log: the thread, the exception and its causes, cut at [MAX_LINES]. */
    fun text(thread: String, t: Throwable, version: String, at: Long): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val lines = sw.toString().trimEnd().lines()
        val kept = lines.take(MAX_LINES).joinToString("\n").take(MAX_CHARS)
        val cut = if (lines.size > MAX_LINES || kept.length >= MAX_CHARS) "\n  … ${lines.size} lines in all" else ""
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
        return "CRASH $time CarPulse $version, thread \"$thread\": $kept$cut"
    }

    /** The trace saved by the run before, once: the file goes. */
    fun restore(dir: File): String? {
        val f = File(dir, FILE)
        if (!f.isFile) return null
        val t = runCatching { f.readText() }.getOrNull()
        f.delete()
        return t?.takeIf { it.isNotBlank() }
    }

    fun save(dir: File, text: String) {
        File(dir, FILE).writeText(text)
    }

    class Handler(private val dir: File, private val version: String, private val prev: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(th: Thread, e: Throwable) {
            // nothing here may throw: the previous handler must run whatever happens
            try {
                val t = text(th.name, e, version, System.currentTimeMillis())
                runCatching { save(dir, t) }
                runCatching { SsmTrace.log(t, keep = true) }
                runCatching { RunTrail.note("crash in \"${th.name}\": $e") }
            } catch (_: Throwable) {
            }
            if (prev != null) prev.uncaughtException(th, e)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
