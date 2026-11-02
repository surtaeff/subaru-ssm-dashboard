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
    fun launch(ctx: Context, s: Shortcut) {
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
            if (ok) return
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
            val inSplit = (ctx as? android.app.Activity)?.isInMultiWindowMode == true
            if (!inSplit) {
                if (kg.forester.sg.service.SplitService.toggleSplit()) {
                    // give the system a moment to dock our window before the other app takes the free half
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { ctx.startActivity(intent) } }, 700)
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
    }

    /** Leaves split screen: closes the left window (the app keeps running in background) so ours goes full screen. */
    fun exitSplit(ctx: Context) {
        val script = "id=${'$'}(am stack list | awk '/^Stack id=/{id=${'$'}2; sub(\"id=\",\"\",id)} /mWindowingMode=split-screen-primary/{print id; exit}'); [ -n \"${'$'}id\" ] && am stack remove ${'$'}id"
        val ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0 }.getOrDefault(false)
        // without root the same accessibility action turns split screen off again
        if (!ok && (ctx as? android.app.Activity)?.isInMultiWindowMode == true) kg.forester.sg.service.SplitService.toggleSplit()
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
