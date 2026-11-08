package kg.forester.sg.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A side-panel button that launches another app, either next to ours (split screen) or full screen. */
data class Shortcut(
    val id: Long,
    val label: String,
    val pkg: String,
    val icon: String,
    val split: Boolean,
    /** Extra voice phrase (the label always works too). */
    val phrase: String = "",
)

data class InstalledApp(val label: String, val pkg: String, val icon: Drawable?)

object Shortcuts {
    /** Icons offered in the picker (Lucide). */
    val icons: Map<String, ImageVector> = linkedMapOf(
        "navigation" to Lucide.Navigation, "map" to Lucide.Map, "compass" to Lucide.Compass,
        "music" to Lucide.Music, "headphones" to Lucide.Headphones, "radio" to Lucide.Radio,
        "play" to Lucide.Play, "youtube" to Lucide.Youtube, "video" to Lucide.Video, "tv" to Lucide.Tv,
        "phone" to Lucide.Phone, "message" to Lucide.MessageCircle, "mic" to Lucide.Mic,
        "bluetooth" to Lucide.Bluetooth, "camera" to Lucide.Camera, "globe" to Lucide.Globe,
        "cloud" to Lucide.Cloud, "folder" to Lucide.Folder, "image" to Lucide.Image, "car" to Lucide.Car,
        "fuel" to Lucide.Fuel, "wrench" to Lucide.Wrench, "cart" to Lucide.ShoppingCart, "coffee" to Lucide.Coffee,
        "game" to Lucide.Gamepad2, "star" to Lucide.Star, "grid" to Lucide.LayoutGrid, "app" to Lucide.AppWindow,
    )

    fun icon(name: String): ImageVector = icons[name] ?: Lucide.AppWindow

    val list = MutableStateFlow<List<Shortcut>>(emptyList())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val raw = prefs(ctx).getString("list", null)
        list.value = if (raw == null) defaults(ctx) else runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Shortcut(
                    o.getLong("id"), o.getString("label"), o.getString("pkg"), o.optString("icon", "app"),
                    o.optBoolean("split", true), o.optString("phrase", ""),
                )
            }
        }.getOrDefault(defaults(ctx))
    }

    fun save(ctx: Context, items: List<Shortcut>) {
        list.value = items
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("pkg", it.pkg).put("icon", it.icon).put("split", it.split).put("phrase", it.phrase))
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    private fun defaults(ctx: Context): List<Shortcut> {
        val pm = ctx.packageManager
        fun has(p: String) = pm.getLaunchIntentForPackage(p) != null
        val out = ArrayList<Shortcut>()
        listOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.google.android.apps.maps").firstOrNull(::has)
            ?.let { out += Shortcut(1, "Навигатор", it, "navigation", true) }
        listOf("com.syu.music", "ru.yandex.music", "com.yf.teyesspotify", "com.spotify.music").firstOrNull(::has)
            ?.let { out += Shortcut(2, "Музыка", it, "music", false) }
        return out
    }

    fun installedApps(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, runCatching { it.loadIcon(pm) }.getOrNull()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * Opens the app. Split mode puts it in the left half (split-screen primary) and our app takes the
     * other half with root (am start --windowingMode 3). Without root: launched adjacent if split screen is
     * already on, else split screen is turned on through our accessibility service (our app goes to the first
     * half, the other app to the second); with the service off it opens full screen and says how to enable it.
     */
    fun launch(ctx: Context, s: Shortcut, then: Intent? = null) {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(s.pkg) ?: return
        val comp: ComponentName = intent.component ?: return
        if (s.split) {
            // This firmware ignores --windowingMode for a task that already exists, so an existing full-screen
            // window of the app is closed first (its process keeps running, the navigator keeps its route) and
            // the app is reopened in the left half; our app then fills the right half.
            val script = """
                st=${'$'}(am stack list | awk -v p="${s.pkg}/" '/^Stack id=/{id=${'$'}2; sub("id=","",id); mode=""} /mWindowingMode=split-screen-primary/{mode="split"} index(${'$'}0,p) && /taskId=/{print id, mode; exit}')
                set -- ${'$'}st
                if [ -n "${'$'}1" ] && [ "${'$'}2" != "split" ]; then am stack remove ${'$'}1; fi
                am start --windowingMode 3 -n ${comp.flattenToShortString()}
            """.trimIndent()
            val ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0 }.getOrDefault(false)
            if (ok) {
                // whatever is under the new left half fills the right one: home there would minimise the navigator to a strip
                kg.forester.sg.ssm.SsmTrace.log("split: root ${s.pkg} onTop=${kg.forester.sg.service.WakeWatch.appOnScreen} ${homeInfo(ctx)}", keep = true)
                startThen(ctx, then)
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
            // voice and map links come with the application context: then the mode the activity last reported
            val act = ctx as? android.app.Activity
            val inSplit = if (act != null) android.os.Build.VERSION.SDK_INT >= 24 && act.isInMultiWindowMode else kg.forester.sg.ui.SplitScreen.on.value
            // the toggle docks whatever is on top: with our window not there it docks the navigator itself, which
            // the system then shrinks to a strip beside the home screen that the divider can't be dragged out of
            val onTop = kg.forester.sg.service.WakeWatch.appOnScreen
            kg.forester.sg.ssm.SsmTrace.log("split: ${s.pkg} inSplit=$inSplit onTop=$onTop act=${act != null} pending=${splitPending()} ${homeInfo(ctx)}", keep = true)
            if (!inSplit && splitPending()) return  // a second tap while the first one is still docking: the toggle would undo it
            if (!inSplit && onTop) {
                if (kg.forester.sg.service.SplitService.toggleSplit()) {
                    startWhenDocked(ctx, intent, then)
                    return
                }
                kg.forester.sg.service.Alerts.raise(
                    "splitHelp", "Чтобы открывать «рядом» без root, включите службу «CarPulse: разделение экрана» " +
                        "в Настройки → Кнопки запуска", kg.forester.sg.service.Alerts.Level.INFO, 24 * 3600_000L, speak = false,
                )
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
        startThen(ctx, then?.also { if (s.split) it.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) })
    }

    /**
     * A link for the app just opened (a route, a place), sent only after the app is in its half: sent at once, it
     * brought the navigator up full screen before the split toggle, which then docked the navigator as a strip.
     */
    private fun startThen(ctx: Context, then: Intent?) {
        if (then == null) return
        then.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(then) }.onFailure { kg.forester.sg.ssm.SsmTrace.log("split: link failed: $it") }
    }

    /** Until when a split toggle is still being waited for (uptime ms). */
    @Volatile private var splitPendingUntil = 0L

    private fun splitPending() = android.os.SystemClock.uptimeMillis() < splitPendingUntil

    /**
     * Opens the app in the free half once our window is really docked (the activity reports split screen), not after
     * a fixed pause: launched before that, on a slow head unit, the app came up full screen on top and the late
     * toggle docked it instead of ours, as a narrow strip. Gives up waiting after 2 s and launches anyway.
     */
    private fun startWhenDocked(ctx: Context, intent: Intent, then: Intent? = null) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val t0 = android.os.SystemClock.uptimeMillis()
        splitPendingUntil = t0 + 2500
        val tick = object : Runnable {
            override fun run() {
                val docked = kg.forester.sg.ui.SplitScreen.on.value
                val waited = android.os.SystemClock.uptimeMillis() - t0
                if (!docked && waited < 2000) { h.postDelayed(this, 100); return }
                kg.forester.sg.ssm.SsmTrace.log("split: docked=$docked after ${waited}ms, launching adjacent", keep = true)
                // a moment for the divider to settle once docked
                h.postDelayed({
                    splitPendingUntil = 0L
                    runCatching { ctx.startActivity(intent) }
                    then?.let { l -> h.postDelayed({ startThen(ctx, l.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)) }, 400L) }
                }, if (docked) 200L else 0L)
            }
        }
        h.postDelayed(tick, 150)
    }

    /**
     * For the log: whether we are the default home screen and whether one of our tasks was started as home. A home
     * task can't be docked, and beside a docked app it makes the system minimise that app to a strip.
     */
    private fun homeInfo(ctx: Context): String = runCatching {
        val home = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val homeTask = am.appTasks.any { t -> runCatching { t.taskInfo.baseIntent.hasCategory(Intent.CATEGORY_HOME) }.getOrDefault(false) }
        "defaultHome=${home == ctx.packageName} homeTask=$homeTask"
    }.getOrDefault("home=?")

    /** Leaves split screen: closes the left window (the app keeps running in background) so ours goes full screen. */
    fun exitSplit(ctx: Context) {
        val script = "id=${'$'}(am stack list | awk '/^Stack id=/{id=${'$'}2; sub(\"id=\",\"\",id)} /mWindowingMode=split-screen-primary/{print id; exit}'); [ -n \"${'$'}id\" ] && am stack remove ${'$'}id"
        val ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0 }.getOrDefault(false)
        // without root the same accessibility action turns split screen off again
        if (!ok && (android.os.Build.VERSION.SDK_INT >= 24 && (ctx as? android.app.Activity)?.isInMultiWindowMode == true)) kg.forester.sg.service.SplitService.toggleSplit()
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
