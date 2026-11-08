package kg.forester.sg.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub releases of the project (betas included): finds the newest release, downloads its APK
 * and hands it to the system installer. Releases are signed with the same key, so the update keeps the data.
 */
object Updater {
    private const val REPO = "surtaeff/subaru-ssm-dashboard"

    data class Release(val tag: String, val name: String, val notes: String, val apkUrl: String, val size: Long)

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /**
     * The newest published release that has an APK, or null (no network). Blocking. The GitHub API allows
     * 60 requests an hour per address, and a mobile operator puts many cars behind one, so when it refuses
     * the answer comes from latest.json on the main branch (raw files have no such limit).
     */
    fun latest(): Release? = fromApi() ?: fromLatestJson()

    private fun fromLatestJson(): Release? = runCatching {
        val c = URL(latestJsonUrl()).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10_000
        c.useCaches = false
        val o = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        Release(o.getString("tag"), o.optString("name"), o.optString("notes"), o.getString("apk"), o.optLong("size"))
    }.getOrNull()

    /**
     * The receiver of the log upload from latest.json ("logUrl"): empty while the owner has put none there, and that
     * keeps the upload dormant. Null when the file could not be read (the address known so far stays).
     */
    fun logUrl(): String? = runCatching {
        val c = URL(latestJsonUrl()).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10_000
        c.useCaches = false
        logUrlOf(c.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()

    /** The one file the release check and the log receiver's address are both read from. */
    fun latestJsonUrl() = "https://raw.githubusercontent.com/$REPO/main/latest.json"

    /** "logUrl" of latest.json: empty when there is none. */
    fun logUrlOf(json: String): String = org.json.JSONObject(json).optString("logUrl")

    /** Looks once a day (from the screen and the service) and puts a notice up when a newer release is out. */
    fun checkDaily(ctx: Context) {
        val s = Repo.settings
        if (!s.feature("update") || System.currentTimeMillis() - s.lastUpdateCheckMs < 24 * 3600_000L) return
        // the receiver of the log upload, only for a person who turned the upload on
        if (s.logUploadEnabled) Updater.logUrl()?.let { s.logEndpoint = kg.forester.sg.service.LogUpload.endpointOf(it).orEmpty() }
        val r = latest() ?: return
        s.lastUpdateCheckMs = System.currentTimeMillis()
        if (!isNewer(r.tag, current(ctx))) return
        kg.forester.sg.service.Alerts.raise("update", "Вышла версия ${r.tag.removePrefix("v")}: Настройки → Система → «Обновление приложения»",
            kg.forester.sg.service.Alerts.Level.INFO, 24 * 3600_000L, speak = false)
    }

    private fun fromApi(): Release? = runCatching {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=15").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000; c.readTimeout = 10_000
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }
        val withApk = all.mapNotNull { r ->
            val files = r.getJSONArray("assets")
            val assets = (0 until files.length()).map { files.getJSONObject(it) }
            val name = pickApk(assets.map { it.getString("name") }, r.getString("tag_name"), Build.SUPPORTED_ABIS.toList()) ?: return@mapNotNull null
            val apk = assets.first { it.getString("name") == name }
            Release(r.getString("tag_name"), r.optString("name"), r.optString("body"),
                apk.getString("browser_download_url"), apk.optLong("size"))
        }
        withApk.maxWithOrNull { a, b -> compare(a.tag, b.tag) }
    }.getOrNull()

    /** The ABIs a release may have a file of its own for: carpulse-<ver>-<abi>.apk. */
    private val SPLIT_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * Which APK of a release to download. Since the releases have one file per ABI: the one of the first of [abis] (the
     * unit's, best first) that has a file, smaller by what the other ABI's libraries take. Else the full one: since
     * 0.9-beta21 the app is CarPulse: carpulse.apk, then carpulse-<ver>.apk; the releases before the rename only have
     * subaru-ssm-dashboard.apk / subaru-ssm-dashboard-<ver>.apk, and then any other APK attached to the release. Never
     * another ABI's file: it installs and has no libraries, or doesn't install at all. Old installs don't know these
     * names and look for the full APK, which every release has to go on having under its old name.
     */
    fun pickApk(names: List<String>, tag: String, abis: List<String> = emptyList()): String? {
        val ver = tag.trim().removePrefix("v")
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.equals("carpulse-$ver-$abi.apk", ignoreCase = true) } }?.let { return it }
        val whole = apks.filter { n -> SPLIT_ABIS.none { n.endsWith("-$it.apk", ignoreCase = true) } }
        return listOf("carpulse.apk", "carpulse-$ver.apk", "subaru-ssm-dashboard.apk", "subaru-ssm-dashboard-$ver.apk")
            .firstNotNullOfOrNull { want -> whole.firstOrNull { it.equals(want, ignoreCase = true) } }
            ?: whole.firstOrNull { it.startsWith("carpulse", ignoreCase = true) }
            ?: whole.firstOrNull { it.startsWith("subaru-ssm-dashboard", ignoreCase = true) }
            ?: whole.firstOrNull()
    }

    /** True when [tag] (e.g. "v0.9-beta7") is newer than [installed] (e.g. "0.9-beta6"). */
    fun isNewer(tag: String, installed: String) = compare(tag, installed) > 0

    /** Versions like 0.8, 0.9-beta3, 1.0: numbers first, a beta is older than the same number without it. */
    private fun compare(a: String, b: String): Int {
        fun parse(s: String): Pair<List<Int>, Int> {
            val t = s.trim().removePrefix("v").lowercase()
            val nums = t.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val beta = Regex("beta(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            return nums to beta
        }
        val (na, ba) = parse(a)
        val (nb, bb) = parse(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val d = na.getOrElse(i) { 0 } - nb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return ba.compareTo(bb)
    }

    /** Downloads the APK into the cache; [progress] gets 0..1. Blocking. */
    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "carpulse-${r.tag}.apk")
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        // GitHub answers with a redirect to its file storage
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = c.contentLength.toLong().takeIf { it > 0 } ?: r.size
        c.inputStream.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        return f
    }

    /** False when Android first wants "install unknown apps" allowed for this app (the settings page is opened). */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
