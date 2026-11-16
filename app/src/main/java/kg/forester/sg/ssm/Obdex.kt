package kg.forester.sg.ssm

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Details of the standard trouble codes from obdex (github.com/foerbsnavi/obdex, data CC0): what the unit checks,
 * the usual causes with their likelihood, the symptoms, a repair estimate and the flags (check engine lamp,
 * emissions, limp mode, drive cycle). Texts are English (the base has English and German only).
 *
 * Nothing ships in the APK: the driver downloads the base once on request (about 32 MB). It is read as a stream and
 * kept slim, one line per code ("P0420\t{json}") in files/obdex/codes.tsv, so neither the download nor a lookup
 * holds the whole base in memory.
 */
object Obdex {
    private const val URL_ALL = "https://foerbsnavi.github.io/OBDex/generic.min.json"
    const val SIZE_MB = 32

    data class Cause(val likelihood: String, val text: String)

    data class Entry(
        val code: String, val title: String, val description: String,
        val causes: List<Cause>, val symptoms: List<String>,
        val mil: Boolean?, val emissions: Boolean?, val limp: Boolean?, val driveCycle: Boolean?,
        val difficulty: String, val diy: Boolean?, val costEur: Pair<Int, Int>?, val hours: Pair<Double, Double>?,
        val related: List<String>,
    )

    /** Download progress for the screen: null idle, else a status line. */
    val progress = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Bumped when the base appears or goes, so open screens look again. */
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun dir(filesDir: File) = File(filesDir, "obdex")
    private fun file(filesDir: File) = File(dir(filesDir), "codes.tsv")

    fun have(filesDir: File) = file(filesDir).let { it.isFile && it.length() > 0 }

    /** A standard code obdex can know: five characters, P/B/C/U, not the make's own range. */
    fun applies(code: String): Boolean {
        val c = code.uppercase()
        return c.length == 5 && c[0] in "PBCU" && c.substring(1).all { it.isDigit() || it in 'A'..'F' } && !GenericDtc.isManufacturer(c)
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val missing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** The details of [code], or null when the base is not downloaded or does not have it. Reads the file: call off the main thread. */
    fun lookup(filesDir: File, code: String): Entry? {
        val c = code.uppercase()
        cache[c]?.let { return it }
        if (c in missing || !applies(c)) return null
        val f = file(filesDir)
        if (!f.isFile) return null
        val prefix = "$c\t"
        val line = runCatching { f.bufferedReader().useLines { s -> s.firstOrNull { it.startsWith(prefix) } } }.getOrNull()
        val e = line?.let { runCatching { entry(c, JSONObject(it.substring(prefix.length))) }.getOrNull() }
        if (e == null) missing += c else cache[c] = e
        return e
    }

    private fun entry(code: String, o: JSONObject): Entry {
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        fun bool(k: String) = if (o.has(k)) o.optBoolean(k) else null
        val causes = o.optJSONArray("ca")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { Cause(it.optString(0), it.optString(1)) } }.filter { it.text.isNotBlank() }
        } ?: emptyList()
        val cost = o.optJSONArray("eur")?.takeIf { it.length() == 2 }?.let { it.optInt(0) to it.optInt(1) }
        val hours = o.optJSONArray("h")?.takeIf { it.length() == 2 }?.let { it.optDouble(0) to it.optDouble(1) }
        return Entry(
            code, o.optString("t"), o.optString("d"), causes, strs("sy"),
            bool("mil"), bool("em"), bool("limp"), bool("dc"),
            o.optString("dif"), bool("diy"), cost, hours, strs("rel"),
        )
    }

    /** Removes the downloaded base. */
    fun delete(filesDir: File) {
        dir(filesDir).deleteRecursively()
        cache.clear(); missing.clear()
        changed.value++
    }

    /**
     * Downloads the base and writes the slim file. Returns the number of codes, or null on failure (the previous
     * base, if any, stays). Blocking: call off the main thread.
     */
    fun download(filesDir: File): Int? {
        val d = dir(filesDir).apply { mkdirs() }
        val part = File(d, "codes.tsv.part")
        progress.value = "Скачиваю базу obdex…"
        val n = runCatching {
            val c = URL(URL_ALL).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            try {
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val total = c.contentLength.toLong()
                var count = 0
                val counting = object : java.io.FilterInputStream(c.inputStream) {
                    var read = 0L
                    var shown = -1L
                    private fun step(k: Int) {
                        if (k <= 0) return
                        read += k
                        val mb = read / (1024 * 1024)
                        if (mb != shown) {
                            shown = mb
                            progress.value = if (total > 0) "Скачиваю базу obdex: $mb из ${total / (1024 * 1024)} МБ" else "Скачиваю базу obdex: $mb МБ"
                        }
                    }
                    override fun read(): Int = super.read().also { if (it >= 0) step(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { step(it) }
                }
                part.bufferedWriter().use { out ->
                    JsonReader(counting.bufferedReader()).use { r ->
                        r.beginArray()
                        while (r.hasNext()) {
                            val v = readValue(r) as? Map<*, *> ?: continue
                            val code = (v["code"] as? String)?.uppercase() ?: continue
                            out.write(code); out.write("\t"); out.write(slim(v).toString()); out.write("\n")
                            count++
                        }
                        r.endArray()
                    }
                }
                count
            } finally { c.disconnect() }
        }.onFailure { SsmTrace.log("obdex: download failed: $it"); part.delete() }.getOrNull()
        if (n == null || n < 1000) {
            part.delete()
            progress.value = null
            return null
        }
        val f = file(filesDir)
        f.delete()
        if (!part.renameTo(f)) { part.delete(); progress.value = null; return null }
        cache.clear(); missing.clear()
        SsmTrace.log("obdex: $n codes, ${f.length()} bytes")
        progress.value = null
        changed.value++
        return n
    }

    /** A record of obdex cut to what the screen shows, English only. */
    private fun slim(v: Map<*, *>): JSONObject {
        fun en(x: Any?): String = when (x) {
            is Map<*, *> -> (x["en"] as? String) ?: ""
            is String -> x
            else -> ""
        }
        val o = JSONObject()
        o.put("t", en(v["title"]))
        o.put("d", en(v["description"]))
        (v["common_causes"] as? List<*>)?.let { l ->
            o.put("ca", JSONArray().apply {
                l.forEach { c -> (c as? Map<*, *>)?.let { put(JSONArray().put(it["likelihood"] as? String ?: "").put(en(it["label"]))) } }
            })
        }
        (v["symptoms"] as? List<*>)?.let { l -> o.put("sy", JSONArray().apply { l.forEach { put(en(it)) } }) }
        (v["flags"] as? Map<*, *>)?.let { f ->
            (f["mil"] as? Boolean)?.let { o.put("mil", it) }
            (f["emissions_relevant"] as? Boolean)?.let { o.put("em", it) }
            (f["limp_mode_possible"] as? Boolean)?.let { o.put("limp", it) }
            (f["drive_cycle_required"] as? Boolean)?.let { o.put("dc", it) }
        }
        (v["repair"] as? Map<*, *>)?.let { r ->
            (r["difficulty"] as? String)?.let { o.put("dif", it) }
            (r["diy_possible"] as? Boolean)?.let { o.put("diy", it) }
            (r["estimated_cost_eur"] as? List<*>)?.takeIf { it.size == 2 }?.let { o.put("eur", JSONArray().put((it[0] as Number).toInt()).put((it[1] as Number).toInt())) }
            (r["estimated_hours"] as? List<*>)?.takeIf { it.size == 2 }?.let { o.put("h", JSONArray().put((it[0] as Number).toDouble()).put((it[1] as Number).toDouble())) }
        }
        (v["related_codes"] as? List<*>)?.let { l -> o.put("rel", JSONArray().apply { l.forEach { (it as? String)?.let { s -> put(s) } } }) }
        return o
    }

    /** One JSON value as Map / List / String / Number / Boolean / null. */
    private fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = readValue(r)
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l += readValue(r)
            r.endArray()
            l
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() ?: 0 }
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    /** Russian words for the base's fixed values. */
    fun likelihoodRu(l: String) = when (l) { "high" -> "часто"; "medium" -> "иногда"; "low" -> "редко"; else -> "" }
    fun difficultyRu(d: String) = when (d) { "easy" -> "простой"; "medium" -> "средний"; "hard" -> "сложный"; "shop_only" -> "только в сервисе"; else -> "" }
}
