package kg.forester.sg

import android.app.Activity
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.metrics.performance.JankStats
import kg.forester.sg.data.FrameReport
import kg.forester.sg.data.StallWatch
import kg.forester.sg.ssm.SsmTrace

/**
 * What a debug build tells about itself in logcat (tags StrictMode, frames, stall) and in the log: the main thread's disk
 * and network calls, its messages of 50 ms and more, and p50, p95 and p99 of the frames every half a minute.
 */
object DebugWatch {
    private var strict = false

    fun start(activity: Activity) {
        if (!strict) {
            strict = true
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
            val stalls = StallWatch(50L) {
                Log.w("stall", it)
                SsmTrace.gap("stall: $it")
            }
            Looper.getMainLooper().setMessageLogging { line ->
                if (line.startsWith(">")) stalls.begin(SystemClock.uptimeMillis(), line) else stalls.end(SystemClock.uptimeMillis())
            }
        }
        val report = FrameReport(30_000L) {
            Log.i("frames", it)
            SsmTrace.gap("frames: $it")
        }
        activity.window.decorView // JankStats wants it there, and setContent comes later
        JankStats.createAndTrack(activity.window) { f ->
            report.frame(SystemClock.uptimeMillis(), f.frameDurationUiNanos / 1_000_000f)
        }
    }
}
