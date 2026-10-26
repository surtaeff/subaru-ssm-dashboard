package kg.forester.sg.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kg.forester.sg.MainActivity
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Notices the head unit waking up from ACC sleep while the service is still alive. Head units don't cold-boot,
 * so BOOT_COMPLETED rarely comes, and since Android 8 the FYT "ACC on" broadcasts don't reach receivers declared
 * in the manifest; registered here at run time they do. As a fallback, a jump in deep-sleep time between two
 * ticks means the unit slept, whatever the firmware sends.
 */
class WakeWatch(private val ctx: Context, private val onWake: (String) -> Unit) {
    private var lastWakeMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = wake(i.action ?: "?")
    }

    fun start(scope: CoroutineScope) {
        val f = IntentFilter().apply {
            ACTIONS.forEach { addAction(it) }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, f)
        }
        scope.launch {
            // elapsedRealtime counts deep sleep, uptimeMillis doesn't: their gap grows only while the unit sleeps
            var slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            while (isActive) {
                delay(5000)
                val now = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
                if (now - slept > 20_000) wake("deep sleep %d s".format((now - slept) / 1000))
                slept = now
            }
        }
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(receiver) }
    }

    private fun wake(why: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeMs < 60_000) return // the screen, ACC and the sleep gap all come at once
        lastWakeMs = now
        SsmTrace.log("wake: $why")
        onWake(why)
    }

    companion object {
        val ACTIONS = listOf(
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT,
            "com.fyt.boot.ACCON", "com.glsx.boot.ACCON", "android.intent.action.ACC_ON",
        )

        /** Set by [MainActivity] between onResume and onPause. */
        @Volatile var appOnScreen = false

        /** Brings the dashboard up after a wake if the driver asked for it (needs «поверх других приложений»). */
        fun openApp(ctx: Context) {
            if (!Repo.settings.autostartApp) return
            if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(ctx)) {
                SsmTrace.log("wake: no overlay permission, the app can't open itself")
                return
            }
            // already in front: starting it again only races the Teyes launcher waking at the same moment, after
            // which bits of the launcher's widgets could stay drawn over the dashboard until a restart
            if (appOnScreen) { SsmTrace.log("wake: the app is already on screen"); return }
            SsmTrace.log("wake: opening the app")
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }.onFailure { SsmTrace.log("wake: open failed $it") }
        }
    }
}
