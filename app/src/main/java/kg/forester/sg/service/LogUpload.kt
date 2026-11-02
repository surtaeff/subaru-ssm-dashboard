package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.DeviceId
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.SsmTrace
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Sending the exchange log to the owner, signed with the device id (see DeviceId). Off until the person turns it on, and
 * there is no address in the app: it comes in latest.json (field "logUrl") once the owner has put a receiver up
 * (tools/log-upload/Code.gs). Every send carries this install's own key (DeviceId.key, https only): it proves nothing about
 * who a unit is, only that it is the same install as the first upload to that id. Never logged, never in the text.
 *
 * What is previewed is what is sent: [prepare] makes the one text (scrubbed by [LogScrub], gzipped) that both use.
 */
object LogUpload {
    /** Off until the person turns it on. */
    const val DEFAULT_ENABLED = false
    /** The receiver refuses a body above this. */
    const val MAX_BODY = 512 * 1024
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private const val PER_DAY = 5

    data class State(val enabled: Boolean = DEFAULT_ENABLED, val endpoint: String = "", val history: List<Long> = emptyList())

    /** [text] is what the person sees and what is sent; [payload] is its body: the base64 of its gzip, as text. */
    class Prepared(val text: String, val gz: ByteArray) {
        val payload: String = base64(gz)
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 without line breaks; by hand, since the JDK Base64 class is API 26 and the units start at 25. */
    fun base64(b: ByteArray): String {
        val o = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            val v = ((b[i].toInt() and 0xFF) shl 16) or ((if (n > 1) b[i + 1].toInt() and 0xFF else 0) shl 8) or (if (n > 2) b[i + 2].toInt() and 0xFF else 0)
            o.append(B64[(v shr 18) and 63]).append(B64[(v shr 12) and 63])
            o.append(if (n > 1) B64[(v shr 6) and 63] else '=').append(if (n > 2) B64[v and 63] else '=')
            i += 3
        }
        return o.toString()
    }

    /** What the receiver said: [ok] only when it answered exactly "ok"; else [reason] for the person. */
    data class Result(val ok: Boolean, val reason: String = "")

    interface Sender {
        fun post(url: String, id: String, key: String, payload: String): Result
    }

    /** Only https, and only what latest.json gave. */
    fun endpointOf(raw: String?): String? = raw?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) && it.length > 12 }

    /** Not more than once an hour and 5 a day, and only when it is on and the address is known. */
    fun canAuto(s: State, now: Long): Boolean {
        if (!s.enabled || endpointOf(s.endpoint) == null) return false
        val day = s.history.filter { now - it < DAY }
        if (day.size >= PER_DAY) return false
        return day.none { now - it < HOUR }
    }

    /** The button works whenever an address is known, switch or not, and no limit applies. */
    fun canManual(endpoint: String): Boolean = endpointOf(endpoint) != null

    fun parseHistory(s: String): List<Long> = s.split(',').mapNotNull { it.trim().toLongOrNull() }

    /** The sends of the last day, and this one. */
    fun record(history: List<Long>, now: Long): List<Long> = history.filter { now - it < DAY } + now

    private fun gz(t: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(t.toByteArray(Charsets.UTF_8)) } }.toByteArray()

    fun prepare(sys: String, header: String, lines: String, known: Set<String>): Prepared {
        val head = LogScrub.scrub("$sys\n$header\n\n", known)
        var body = LogScrub.scrub(lines, known)
        var p = Prepared(head + body, gz(head + body))
        // too long (as the text the receiver gets, base64): the oldest lines go, the end of the log shows the failure
        while (p.payload.length > MAX_BODY && body.isNotEmpty()) {
            val cut = body.indexOf('\n', body.length / 4).let { if (it < 0) body.length else it + 1 }
            body = body.substring(cut)
            val text = head + "(начало журнала обрезано)\n" + body
            p = Prepared(text, gz(text))
        }
        return p
    }

    fun send(sender: Sender, url: String, id: String, key: String, p: Prepared): Result =
        runCatching { sender.post(url, id, key, p.payload) }.getOrElse { Result(false, reason(it)) }

    private fun reason(t: Throwable): String {
        SsmTrace.log("log upload: $t")
        return kg.forester.sg.data.Failure.text(t, "нет связи")
    }

    private const val PENDING = "pending.b64"

    /**
     * One POST. A failure leaves one file (replaced by the next failure) to go again at the next start, but only when
     * [keep]: with the upload off nothing stays on the unit after a failed press of the button.
     */
    fun sendOrKeep(sender: Sender, url: String, id: String, key: String, p: Prepared, dir: File, keep: Boolean): Result {
        val r = send(sender, url, id, key, p)
        if (!r.ok && keep) runCatching { dir.mkdirs(); File(dir, PENDING).writeText(p.payload) }
        return r
    }

    fun hasPending(dir: File) = File(dir, PENDING).isFile

    fun clearPending(dir: File) { File(dir, PENDING).delete() }

    fun retryPending(sender: Sender, url: String, id: String, key: String, dir: File): Result {
        val f = File(dir, PENDING)
        val body = runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull() ?: return Result(false, "нет файла")
        val r = runCatching { sender.post(url, id, key, body) }.getOrElse { Result(false, reason(it)) }
        if (r.ok) f.delete()
        return r
    }

    /** What [auto] did, for the tests and the log. */
    enum class Auto { OFF, NOTHING, RETRIED, SENT, FAILED }

    /**
     * The whole of an automatic send, away from Android: the kept file if there is one (that is the one POST of this
     * attempt, never a second), else the log made by [make]. Every failure, a thrown one too, is caught here: a crash of
     * this thread must not take the ECU service down. [onTry] stores the history before the POST.
     */
    fun runAuto(sender: Sender, st: State, now: Long, id: String, key: String, dir: File, onlyPending: Boolean,
                onTry: (List<Long>) -> Unit, make: () -> Prepared): Auto = try {
        val url = endpointOf(st.endpoint)
        if (!canAuto(st, now) || url == null) Auto.OFF
        else if (hasPending(dir)) {
            onTry(record(st.history, now))
            if (retryPending(sender, url, id, key, dir).ok) Auto.RETRIED else Auto.FAILED
        } else if (onlyPending) Auto.NOTHING
        else {
            onTry(record(st.history, now))
            if (sendOrKeep(sender, url, id, key, make(), dir, keep = true).ok) Auto.SENT else Auto.FAILED
        }
    } catch (t: Throwable) {
        Auto.FAILED
    }

    /**
     * The real sender. The text goes in a POST (Apps Script runs it and answers 302 to the page with the result, whatever
     * the result was), so the redirect is not followed by the POST: its Location is read with a GET, and only the reply
     * "ok" counts. Anything else is a reason, and the log was not taken.
     */
    object Http : Sender {
        override fun post(url: String, id: String, key: String, payload: String): Result {
            val sep = if ('?' in url) '&' else '?'
            // the key is only in this request: the redirect's GET goes to the address the receiver gave
            val first = URL("$url${sep}id=$id&key=$key")
            val c = first.openConnection() as HttpURLConnection
            val code: Int
            val location: String?
            val direct: String
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                val bytes = payload.toByteArray(Charsets.US_ASCII)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
                code = c.responseCode
                location = c.getHeaderField("Location")
                direct = if (code in 200..299) readShort(c) else ""
            } finally {
                c.disconnect()
            }
            if (code in 200..299) return answer(direct)
            if (code in 301..303 || code == 307 || code == 308) {
                val to = location ?: return Result(false, "переадресация без адреса")
                val next = runCatching { URL(first, to) }.getOrNull() ?: return Result(false, "плохой адрес ответа")
                // never down to plain http, and not to somewhere else with a login page
                if (next.protocol != first.protocol) return Result(false, "ответ по другому протоколу")
                val g = next.openConnection() as HttpURLConnection
                try {
                    g.instanceFollowRedirects = false
                    g.connectTimeout = 10_000; g.readTimeout = 20_000
                    if (g.responseCode !in 200..299) return Result(false, "ответ ${g.responseCode}")
                    return answer(readShort(g))
                } finally {
                    g.disconnect()
                }
            }
            return Result(false, "ответ $code")
        }

        private fun readShort(c: HttpURLConnection): String =
            c.inputStream.use { ins ->
                // by hand: the stream helper for this is API 33
                val buf = ByteArray(300)
                var n = 0
                while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                String(buf, 0, n, Charsets.UTF_8)
            }

        private fun answer(text: String): Result {
            val t = text.trim()
            return if (t == "ok") Result(true) else Result(false, "приём ответил «${t.take(40).replace(Regex("\\s+"), " ")}»")
        }
    }

    // ---- the app side

    fun state(): State {
        val s = Repo.settings
        return State(s.logUploadEnabled, s.logEndpoint, parseHistory(s.logUploadHistory))
    }

    /** Values the app knows are private, to cut out of a log wherever they stand. */
    fun knownFrom(vin: String, carTitle: String, carSub: String, btName: String, btAddress: String, wifiHost: String,
                  tpmsWheelsJson: String, sensors: Collection<String>): Set<String> {
        val out = mutableSetOf(vin, carTitle, carSub, btName, btAddress, wifiHost.substringBefore(':'))
        runCatching { org.json.JSONObject(tpmsWheelsJson).keys().forEach { out += it } }
        out += sensors
        return out.filter { it.isNotBlank() }.toSet()
    }

    fun known(): Set<String> {
        val s = Repo.settings
        return knownFrom(Repo.vin.value, s.carTitle, s.carSub, s.btName, s.btAddress, s.wifiHost, s.tpmsWheels,
            Repo.tyres.value.values.map { it.sensor })
    }

    /** The header of every report, the manual one too: the device id first, so that the owner finds the unit. */
    fun header(id: String, carTitle: String, gearbox: String, adapter: String, bus: String, found: String, msg: String, rom: String?) =
        "ID устройства: $id. Машина: «$carTitle», коробка $gearbox; адаптер $adapter, шина $bus; найден: ${found.ifEmpty { "—" }}; связь: $msg; ROM ${rom ?: "—"}"

    fun headerNow(ctx: Context): String {
        val s = Repo.settings
        return header(DeviceId.get(ctx), s.carTitle, s.gearbox, s.adapter, s.bus, Repo.adapterFound.value, Repo.linkMessage.value, Repo.ecuInit.value?.romId)
    }

    /** The text that will go, exactly: for the preview, the upload and the manual send and save. */
    fun prepareNow(ctx: Context): Prepared = prepare(SsmTrace.sysLine(ctx), headerNow(ctx), SsmTrace.lines(), known() + DeviceId.key(ctx))

    private fun dir(ctx: Context) = File(ctx.cacheDir, "log-upload")

    /** The switch turned off: nothing is kept for later. */
    fun forget(ctx: Context) = clearPending(dir(ctx))

    /** The button: at once, whatever the limits say. A failure is kept for the next start only when the upload is on. */
    fun sendNow(ctx: Context, p: Prepared): Result {
        val url = endpointOf(Repo.settings.logEndpoint) ?: return Result(false, "адрес приёма не известен")
        return sendOrKeep(Http, url, DeviceId.get(ctx), DeviceId.key(ctx), p, dir(ctx), keep = Repo.settings.logUploadEnabled)
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * By itself, off the caller's thread: see [runAuto]. At the start only the kept file goes (the log of a new process is
     * empty). Nothing happens unless it is on, the address is known and the hour and day limits allow it.
     */
    fun auto(ctx: Context, why: String, onlyPending: Boolean = false) {
        if (!busy.compareAndSet(false, true)) return
        runCatching {
            Thread({
                try {
                    val now = System.currentTimeMillis()
                    val st = state()
                    if (canAuto(st, now)) {
                        if (!onlyPending) SsmTrace.log("log upload: $why", keep = true)
                        runAuto(Http, st, now, DeviceId.get(ctx), DeviceId.key(ctx), dir(ctx), onlyPending,
                            { Repo.settings.logUploadHistory = it.joinToString(",") }) { prepareNow(ctx) }
                    }
                } catch (t: Throwable) {
                } finally {
                    busy.set(false)
                }
            }, "log-upload").apply { isDaemon = true; start() }
        }.onFailure { busy.set(false) }
    }
}

/**
 * Tells a fault from a key-off: the log goes by itself only if the engine ran (above 500 rpm) within 2 s of the last
 * sample, and the link is still down 30 s later on a unit that has been running all that time. Once per connection.
 */
class FaultWatch {
    private var lastSample = 0L
    private var lastHigh = 0L
    private var fired = false

    fun sample(rpm: Int?, now: Long) { lastSample = now; if ((rpm ?: 0) > 500) lastHigh = now }

    fun newConnection() { lastSample = 0; lastHigh = 0; fired = false }

    /** The data stopped: does it look like a fault (and not like the key turned off)? */
    fun looksLikeFault(): Boolean = !fired && lastHigh > 0 && lastSample - lastHigh <= 2000

    /** 30 s on: still down on a running unit (not slept through: at most a minute and a half passed)? Fires once. */
    fun confirm(linkUp: Boolean, waitedMs: Long): Boolean {
        if (fired || linkUp || waitedMs < 30_000 || waitedMs > 90_000) return false
        fired = true
        return true
    }
}
