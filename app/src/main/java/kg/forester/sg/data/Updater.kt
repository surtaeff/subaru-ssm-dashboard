package kg.forester.sg.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the GitHub releases of the project (betas included): finds the newest release, downloads its APK
 * and hands it to the system installer. Releases are signed with the same key, so the update keeps the data.
 */
object Updater {
    private const val REPO = "surtaeff/subaru-ssm-dashboard"

    /** [size] and [sha256] (hex) of the APK as the release states them: 0 and "" when it doesn't say. */
    data class Release(val tag: String, val name: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String = "")

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
        Release(o.getString("tag"), o.optString("name"), o.optString("notes"), o.getString("apk"), o.optLong("size"),
            shaOf(o.optString("sha256")))
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
                apk.getString("browser_download_url"), apk.optLong("size"), shaOf(apk.optString("digest")))
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

    // ---- checks that need no Android (UpdaterTest)

    /** The finished file of a release, and the one it is written to until it is whole: never the same name. */
    fun apkName(tag: String) = "carpulse-" + tag.trim().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

    fun partName(tag: String) = apkName(tag) + ".part"

    /** A SHA-256 as a release gives it ("sha256:ab12…" from GitHub, plain hex from latest.json): lower hex, or "". */
    fun shaOf(raw: String?): String {
        val h = raw.orEmpty().trim().lowercase().removePrefix("sha256:")
        return if (h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' }) h else ""
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Is a downloaded file the release's whole APK? Null when it is, else why not, for the person. [contentLength] is what
     * the server said for this download, [assetSize] and [wantSha] what the release says; 0, -1 and "" are unknown.
     */
    fun checkApk(length: Long, contentLength: Long, assetSize: Long, sha: String, wantSha: String): String? = when {
        length <= 0 -> "Файл не скачался, попробуйте ещё раз"
        contentLength > 0 && length != contentLength -> "Скачалось не полностью (${mb(length)} из ${mb(contentLength)} МБ), попробуйте ещё раз"
        assetSize > 0 && length != assetSize -> "Размер файла не совпал с релизом (${mb(length)} вместо ${mb(assetSize)} МБ), попробуйте ещё раз"
        wantSha.isNotEmpty() && !sha.equals(wantSha, ignoreCase = true) -> "Файл повреждён при скачивании (не совпала контрольная сумма), попробуйте ещё раз"
        else -> null
    }

    /** What an unknown size is taken for: the universal APK is about 125 MB. */
    private const val GUESS_SIZE = 130L * 1024 * 1024

    /** The download, the installer's own copy and its unpacking: about two and a half sizes free. */
    fun spaceNeeded(size: Long): Long = (if (size > 0) size else GUESS_SIZE) * 5 / 2

    /** Null when [free] is enough for an APK of [size], else what to tell the person. */
    fun spaceProblem(free: Long, size: Long): String? {
        val need = spaceNeeded(size)
        return if (free >= need) null
        else "Мало места: для обновления нужно около ${mb(need)} МБ свободной памяти, сейчас ${mb(free)} МБ. Освободите место и попробуйте снова"
    }

    private fun mb(b: Long) = (b + 512 * 1024) / (1024 * 1024)

    enum class Verdict { OK, UNREADABLE, OTHER_APP, NOT_NEWER, OTHER_SIGNATURE }

    /**
     * May the downloaded APK go over the installed app? [apkCerts] and [ownCerts] are SHA-256 of the signing
     * certificates, null when Android didn't give them (old units read no signatures from a file): then the installer
     * decides. A key rotation keeps the old certificate in the history, so one shared is enough.
     */
    fun verdict(apkPackage: String?, apkCode: Long, apkCerts: Set<String>?, ownPackage: String, ownCode: Long, ownCerts: Set<String>?): Verdict = when {
        apkPackage == null -> Verdict.UNREADABLE
        apkPackage != ownPackage -> Verdict.OTHER_APP
        apkCode <= ownCode -> Verdict.NOT_NEWER
        !apkCerts.isNullOrEmpty() && !ownCerts.isNullOrEmpty() && apkCerts.intersect(ownCerts).isEmpty() -> Verdict.OTHER_SIGNATURE
        else -> Verdict.OK
    }

    /** For the person: null when the install may go on. */
    fun verdictText(v: Verdict): String? = when (v) {
        Verdict.OK -> null
        Verdict.UNREADABLE -> "Скачанный файл не читается как приложение, скачайте ещё раз"
        Verdict.OTHER_APP -> "Скачан файл другого приложения, обновление отменено"
        Verdict.NOT_NEWER -> "Скачанная версия не новее установленной, обновлять нечего"
        Verdict.OTHER_SIGNATURE -> "У установленной версии другая подпись (0.8 или собранная самостоятельно): поверх неё обновление не встанет. " +
            "Сохраните резервную копию, удалите приложение и поставьте новую версию заново"
    }

    /** The installer's answer (PackageInstaller.STATUS_*, as numbers: they are fixed) in the person's words. */
    fun installText(status: Int, message: String?): String = when (status) {
        0 -> "Обновление установлено"
        2 -> "Не установилось: установку запретила система или другое приложение"
        3 -> "Установка отменена"
        4 -> "Не установилось: файл повреждён или не подходит, нажмите «Скачать и установить» ещё раз"
        5 -> "Не установилось: конфликт с установленной версией (другая подпись). Сохраните резервную копию, удалите приложение и поставьте заново"
        6 -> "Не установилось: мало места в памяти устройства"
        7 -> "Не установилось: эта версия не подходит для устройства"
        8 -> "Не установилось: установщик не дождался ответа, попробуйте ещё раз"
        else -> "Не установилось: ошибка установщика" + (message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    // ---- one download and one install per process: the screen only shows this

    /** What the update section shows; it outlives the screen, so coming back to it shows the same download. */
    data class UiState(
        val status: String = "",
        val failed: Boolean = false,
        val checking: Boolean = false,
        val release: Release? = null,
        /** Not null while the file is being downloaded or handed to the installer. */
        val progress: Float? = null,
        val apk: File? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var conn: HttpURLConnection? = null
    /** The installer's confirmation of the session waiting for the person, and that session: shown again, not a new one. */
    @Volatile private var confirm: Pair<Int, Intent>? = null

    val busy: Boolean get() = job?.isActive == true

    @Synchronized
    fun check(ctx: Context) {
        if (busy || _state.value.checking) return
        val app = ctx.applicationContext
        val current = current(app)
        _state.update { it.copy(status = "Проверяю…", failed = false, checking = true) }
        scope.launch {
            val r = latest()
            val newer = r?.takeIf { isNewer(it.tag, current) }
            val status = when {
                r == null -> "Не удалось проверить: нет интернета или GitHub недоступен"
                // the installed one, not the release found: a build newer than the last release says so
                newer == null && isNewer(current, r.tag) -> "У вас $current, новее опубликованной (${r.tag.removePrefix("v")})"
                newer == null -> "У вас последняя версия ($current)"
                else -> "Доступна версия ${r.tag.removePrefix("v")}"
            }
            _state.update { s ->
                // a download started meanwhile keeps its release
                if (s.progress != null) s.copy(checking = false)
                else s.copy(status = status, failed = r == null, checking = false, release = newer,
                    apk = s.apk?.takeIf { newer != null && it.name == apkName(newer.tag) && it.isFile })
            }
        }
    }

    /** The button: a second press while it runs changes nothing, the progress is already on the screen. */
    @Synchronized
    fun downloadAndInstall(ctx: Context) {
        if (busy) return
        val r = _state.value.release ?: return
        val app = ctx.applicationContext
        _state.update { it.copy(progress = 0f, failed = false, status = "Скачиваю…") }
        job = scope.launch {
            val f = runCatching {
                download(app, r, { !isActive }) { p -> _state.update { s -> if (s.progress != null && p - s.progress >= 0.005f) s.copy(progress = p) else s } }
            }
            f.onFailure { t ->
                val cancelled = !isActive
                SsmTrace.log("update: download ${if (cancelled) "cancelled" else "failed"}: $t", keep = true)
                _state.update { it.copy(progress = null, failed = !cancelled,
                    status = when {
                        cancelled -> "Скачивание отменено"
                        // the app's own reason (size, space, checksum) says it all
                        Failure.kind(t) == Failure.Kind.OWN -> Failure.text(t)
                        else -> "Не скачалось: ${Failure.text(t)}"
                    }) }
            }
            val file = f.getOrNull() ?: return@launch
            _state.update { it.copy(apk = file, progress = 1f, status = "Готовлю установку…") }
            val (ok, text) = runCatching { install(app, file) }.getOrElse { t ->
                SsmTrace.log("update: install failed: $t", keep = true)
                false to "Не установилось: ${Failure.text(t)}"
            }
            _state.update { it.copy(progress = null, failed = !ok, status = text) }
        }
    }

    fun cancel() {
        job?.cancel()
        runCatching { conn?.disconnect() }
    }

    /**
     * The release's APK in cache/update, whole: written to a .part file and renamed only once its size (and SHA-256 when
     * the release gives one) is right, so an installer that has the file open never sees it half written. A finished
     * file of the same tag is used again; files of other tags go. Blocking; [cancelled] is asked between blocks.
     */
    fun download(ctx: Context, r: Release, cancelled: () -> Boolean = { false }, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val f = File(dir, apkName(r.tag))
        val part = File(dir, partName(r.tag))
        dir.listFiles()?.forEach { if (it.name != f.name) it.deleteRecursively() }
        if (f.isFile) {
            val bad = checkApk(f.length(), -1, r.size, if (r.sha256.isEmpty()) "" else hex(sha256(f)), r.sha256)
            if (bad == null) { SsmTrace.log("update: ${f.name} already downloaded", keep = true); progress(1f); return f }
            SsmTrace.log("update: old ${f.name} dropped: $bad", keep = true)
            f.delete()
        }
        fun free() = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        spaceProblem(free(), r.size)?.let { SsmTrace.log("update: ${free()} bytes free", keep = true); error(it) }
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        // GitHub answers with a redirect to its file storage
        while (true) {
            if (cancelled()) error("cancelled")
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            conn = c
            val code = c.responseCode
            if (code in 300..399 && hops++ < 8) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) { c.disconnect(); error("HTTP $code") }
            break
        }
        try {
            // getContentLength is an Int: an APK over 2 GB is not a worry
            val length = c.contentLength.toLong()
            val total = length.takeIf { it > 0 } ?: r.size
            if (length > 0 && length != r.size) spaceProblem(free(), length)?.let { error(it) }
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                java.io.FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) error("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            }
            val sha = hex(md.digest())
            checkApk(part.length(), length, r.size, sha, r.sha256)?.let { bad ->
                SsmTrace.log("update: ${part.name} ${part.length()} bytes, Content-Length $length, release ${r.size}, sha $sha / ${r.sha256.ifEmpty { "-" }}", keep = true)
                part.delete()
                error(bad)
            }
            if (!part.renameTo(f)) { part.delete(); error("Не удалось сохранить файл обновления") }
            confirm = null
            SsmTrace.log("update: ${f.name} downloaded, ${f.length()} bytes, sha256 $sha", keep = true)
            return f
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            c.disconnect()
            conn = null
        }
    }

    private fun sha256(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    @Suppress("DEPRECATION")
    private fun certsOf(p: PackageInfo?): Set<String>? {
        if (p == null) return null
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        else p.signatures
        return sigs?.filterNotNull()?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet()?.takeIf { it.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun codeOf(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private val SIG_FLAGS get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** What [verdict] says of [apk] against this install. */
    fun verdictOf(ctx: Context, apk: File): Verdict {
        val pm = ctx.packageManager
        val a = runCatching { pm.getPackageArchiveInfo(apk.path, SIG_FLAGS) }.getOrNull()
        val own = pm.getPackageInfo(ctx.packageName, SIG_FLAGS)
        val v = verdict(a?.packageName, a?.let { codeOf(it) } ?: 0, certsOf(a), ctx.packageName, codeOf(own), certsOf(own))
        SsmTrace.log("update: ${apk.name}: ${a?.packageName} code ${a?.let { codeOf(it) }} over ${codeOf(own)}, " +
            "signed ${if (certsOf(a) == null) "unknown" else if (v == Verdict.OTHER_SIGNATURE) "by another key" else "alike"}: $v", keep = true)
        return v
    }

    /**
     * Hands the APK to the installer; blocking (the session copies the file). (started, text for the person). Not started
     * when Android first wants "install unknown apps" allowed for this app (its settings page is opened) or the file is
     * not an update of this install. The result of the session comes to [InstallReceiver].
     */
    fun install(ctx: Context, apk: File): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false to "Разрешите установку из этого приложения и нажмите «Скачать и установить» ещё раз"
        }
        val v = verdictOf(ctx, apk)
        verdictText(v)?.let { text ->
            if (v == Verdict.UNREADABLE) apk.delete()
            return false to text
        }
        // a session already waiting for the person: its confirmation again, not a second copy
        confirm?.let { (id, i) ->
            val alive = runCatching { ctx.packageManager.packageInstaller.getSessionInfo(id) != null }.getOrDefault(false)
            if (alive && runCatching { ctx.startActivity(i) }.isSuccess) return true to WAITING
            confirm = null
        }
        return runCatching { session(ctx, apk); true to WAITING }.getOrElse { t ->
            // the units whose installer service doesn't take sessions still open an APK file
            SsmTrace.log("update: session failed, the file goes to the installer: $t", keep = true)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true to "Установщик открыт: подтвердите обновление. Если он пишет «конфликт пакетов» — у вас версия с другой " +
                "подписью (0.8 или собранная самостоятельно): сохраните резервную копию (слева), удалите приложение и поставьте заново"
        }
    }

    private const val WAITING = "Установщик открыт: подтвердите обновление"
    const val ACTION_INSTALLED = "kg.forester.sg.UPDATE_INSTALLED"

    private fun session(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        // what a press before left behind (the person closed its dialog or the app was killed)
        pi.mySessions.forEach { runCatching { pi.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    s.fsync(out)
                }
                val i = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALLED)
                // the installer puts its status into the intent: mutable, and explicit to a receiver nobody else can reach
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, i, flags).intentSender)
            }
            SsmTrace.log("update: session $id committed, ${apk.length()} bytes", keep = true)
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(id) }
            throw t
        }
    }

    /** From [InstallReceiver]: the confirmation is shown, any other status goes to the log and the screen. */
    fun onInstallStatus(ctx: Context, session: Int, status: Int, message: String?, confirmIntent: Intent?) {
        SsmTrace.log("update: session $session, installer status $status ${message.orEmpty()}", keep = true)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val i = confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
            confirm = session to i
            runCatching { ctx.startActivity(i) }.onFailure {
                SsmTrace.log("update: confirmation not shown: $it", keep = true)
                _state.update { s -> s.copy(failed = true, status = "Откройте приложение и нажмите «Скачать и установить» ещё раз") }
            }
            return
        }
        confirm = null
        // a broken file is not offered again
        if (status == PackageInstaller.STATUS_FAILURE_INVALID) _state.value.apk?.delete()
        _state.update { s -> s.copy(failed = status != PackageInstaller.STATUS_SUCCESS, status = installText(status, message),
            apk = if (status == PackageInstaller.STATUS_FAILURE_INVALID) null else s.apk) }
    }
}

/** Where the installer session tells how it ended (registered in the manifest, not exported). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Updater.ACTION_INSTALLED) return
        @Suppress("DEPRECATION")
        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        Updater.onInstallStatus(ctx.applicationContext, i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}
