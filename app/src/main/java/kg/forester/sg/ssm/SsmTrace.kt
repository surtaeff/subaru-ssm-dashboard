package kg.forester.sg.ssm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw exchange log for testers (Настройки → «Журнал обмена»): every frame to and from the adapter.
 * The last few thousand lines are always kept in memory, so a tester can send them with one button
 * without turning anything on first; with the switch on they also go to logs/ssm_trace.txt.
 */
object SsmTrace {
    @Volatile var enabled = false
    private var file: File? = null
    private var w: FileWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val KEEP = 3000
    private const val KEEP_POLL = 400
    /** Connecting, errors, everything but the steady polling: what shows why a car does not connect. */
    private val ring = ArrayDeque<String>(KEEP)
    /** The steady polling once connected: many lines a second, so only the latest are kept, apart. */
    private val pollRing = ArrayDeque<String>(KEEP_POLL)
    /** Set by the service while it polls a connected ECU. */
    @Volatile var polling = false

    fun init(ctx: Context) {
        if (file != null) return
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        file = File(dir, "ssm_trace.txt")
    }

    val path: String? get() = file?.absolutePath

    /** [keep]: a rare line from elsewhere (the radio's commands) that must not drown in the polling. */
    @Synchronized
    fun log(msg: String, keep: Boolean = false) {
        val line = "${fmt.format(Date())} $msg"
        val poll = polling && !keep
        val r = if (poll) pollRing else ring
        if (r.size >= (if (poll) KEEP_POLL else KEEP)) r.removeFirst()
        r.addLast(line)
        if (!enabled) return
        val f = file ?: return
        runCatching {
            if (w == null || f.length() > 4_000_000) {
                w?.close()
                w = FileWriter(f, f.length() <= 4_000_000)
            }
            w!!.write(line + "\n")
            w!!.flush()
        }
    }

    fun log(dir: String, b: ByteArray, len: Int = b.size) = log("$dir ${b.copyOf(len).toHex(" ")}")

    /** The last [n] polling lines copied into the main log: what the ECU was asked just before it fell silent. */
    @Synchronized
    fun keepPollTail(n: Int) {
        if (pollRing.isEmpty()) return
        val tail = pollRing.takeLast(n)
        log("--- the last ${tail.size} polling lines ---", keep = true)
        tail.forEach { log("  $it", keep = true) }
    }

    @Synchronized
    fun clear() {
        runCatching { w?.close() }
        w = null
        file?.delete()
        ring.clear()
        pollRing.clear()
    }

    /** What the tester sends: the phone and settings first, then the recent exchange. */
    @Synchronized
    private fun report(ctx: Context, header: String): String {
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return buildString {
            append("CarPulse $ver, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ")
            append("${Build.MANUFACTURER} ${Build.MODEL}\n")
            append(header).append("\n\n")
            ring.forEach { append(it).append('\n') }
            if (pollRing.isNotEmpty()) {
                append("\n--- последние запросы опроса ---\n")
                pollRing.forEach { append(it).append('\n') }
            }
        }
    }

    /** Share sheet (Telegram, mail…) with the report as a text file. */
    fun share(ctx: Context, header: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "carpulse-log.txt")
        f.writeText(report(ctx, header))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "CarPulse: журнал обмена")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Отправить журнал").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copy into Download/CarPulse for head units without a messenger; returns where, or null. */
    @Suppress("DEPRECATION")
    fun saveToDownloads(ctx: Context, header: String): String? = runCatching {
        val name = "log-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".txt"
        val text = report(ctx, header).toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CarPulse")
            }) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text) } ?: return null
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CarPulse")
            dir.mkdirs()
            File(dir, name).writeBytes(text)
        }
        "Download/CarPulse/$name"
    }.getOrNull()
}

fun ByteArray.toHex(sep: String): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }
