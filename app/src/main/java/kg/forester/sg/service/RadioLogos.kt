package kg.forester.sg.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kg.forester.sg.ssm.SsmTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.TimeZone

/**
 * Station names and logos for the radio: there is no open list of FM frequencies by city for the CIS, so the
 * logos come from radio-browser.info (an open, community-kept catalogue of stations) by the station's name in
 * the car's country, and the driver tells once which station sits on a frequency (it is remembered per country).
 * Lists and pictures are cached in the app's files, so a known station shows its logo without the internet.
 */
object RadioLogos {
    /** [url] is the stream (internet radio), [id] radio-browser's station uuid; both empty for an FM name. */
    data class Station(val name: String, val logo: String, val url: String = "", val id: String = "", val tags: String = "") {
        fun json(): JSONObject = JSONObject().put("n", name).put("l", logo).put("u", url).put("i", id).put("t", tags)

        companion object {
            fun of(o: JSONObject) = Station(o.getString("n"), o.optString("l"), o.optString("u"), o.optString("i"), o.optString("t"))
        }
    }

    /** The CIS countries offered in the station picker. */
    val COUNTRIES = linkedMapOf(
        "RU" to "Россия", "KZ" to "Казахстан", "KG" to "Кыргызстан", "BY" to "Беларусь", "UZ" to "Узбекистан",
        "TJ" to "Таджикистан", "AM" to "Армения", "AZ" to "Азербайджан", "GE" to "Грузия", "MD" to "Молдова", "UA" to "Украина",
    )

    private val ZONES = mapOf(
        "Asia/Bishkek" to "KG", "Europe/Minsk" to "BY", "Asia/Tashkent" to "UZ", "Asia/Samarkand" to "UZ", "Asia/Dushanbe" to "TJ",
        "Asia/Yerevan" to "AM", "Asia/Baku" to "AZ", "Asia/Tbilisi" to "GE", "Europe/Chisinau" to "MD", "Europe/Kiev" to "UA",
        "Europe/Kyiv" to "UA", "Asia/Ashgabat" to "TM", "Asia/Almaty" to "KZ", "Asia/Aqtobe" to "KZ", "Asia/Aqtau" to "KZ",
        "Asia/Atyrau" to "KZ", "Asia/Qyzylorda" to "KZ", "Asia/Oral" to "KZ", "Asia/Qostanay" to "KZ",
    )

    /** The driver's choice, else the mobile network's country, else the time zone (head units often run in English). */
    fun country(ctx: Context): String {
        prefs(ctx).getString("country", null)?.let { return it }
        val net = runCatching {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).networkCountryIso.uppercase()
        }.getOrNull()
        if (!net.isNullOrBlank() && net in COUNTRIES) return net
        val tz = TimeZone.getDefault().id
        ZONES[tz]?.let { return it }
        val loc = java.util.Locale.getDefault().country.uppercase()
        return if (loc in COUNTRIES) loc else "RU"
    }

    fun setCountry(ctx: Context, cc: String) { prefs(ctx).edit().putString("country", cc).apply() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)

    // --- which station sits on a frequency -----------------------------------------------------------------

    /** The station the driver put on [freqText] ("101.70") in [cc]. */
    fun named(ctx: Context, cc: String, freqText: String): Station? =
        prefs(ctx).getString("st:$cc:$freqText", null)?.let { s ->
            runCatching { JSONObject(s).let { Station(it.getString("n"), it.optString("l")) } }.getOrNull()
        }

    fun setNamed(ctx: Context, cc: String, freqText: String, st: Station?) {
        val e = prefs(ctx).edit()
        if (st == null) e.remove("st:$cc:$freqText") else e.putString("st:$cc:$freqText", JSONObject().put("n", st.name).put("l", st.logo).toString())
        e.apply()
    }

    /** Every frequency named in [cc], for the marks on the scale: "101.70" → station. */
    fun allNamed(ctx: Context, cc: String): Map<String, Station> = prefs(ctx).all.mapNotNull { (k, v) ->
        if (!k.startsWith("st:$cc:") || v !is String) return@mapNotNull null
        runCatching { k.removePrefix("st:$cc:") to JSONObject(v).let { Station(it.getString("n"), it.optString("l")) } }.getOrNull()
    }.toMap()

    // --- the catalogue -------------------------------------------------------------------------------------

    private val SERVERS = listOf("de1.api.radio-browser.info", "fi1.api.radio-browser.info", "all.api.radio-browser.info")

    /** Opens [url] following redirects, http → https ones too (HttpURLConnection stops at those). */
    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "CarPulse/0.9 (github.com/surtaeff/subaru-ssm-dashboard)")
            c.connectTimeout = 6000; c.readTimeout = 10_000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code !in 300..399) return c
            u = URL(URL(u), c.getHeaderField("Location") ?: return c).toString()
            c.disconnect()
        }
        error("too many redirects")
    }

    /**
     * [path] from the first server that answers. When none does, why goes to the log in one line (an Android 6 unit
     * said «Ничего не нашлось» for every country and the log had nothing on it), and the last failure is returned.
     */
    private fun fetch(path: String): Result<String> {
        val why = ArrayList<String>()
        var last: Throwable? = null
        for (host in SERVERS) {
            try {
                return Result.success(open("https://$host$path").inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                last = e
                why += "${host.substringBefore('.')} ${reason(e)}"
            }
        }
        SsmTrace.log("radio: catalogue failed ${path.substringBefore('?')}: ${why.joinToString("; ")}", keep = true)
        return Result.failure(last ?: IllegalStateException("no server"))
    }

    /**
     * "SSLHandshakeException: … ← CertPathValidatorException: Trust anchor for certification path not found."
     * For the exchange log only: the panel shows Failure.text, never this.
     */
    internal fun reason(e: Throwable): String = generateSequence(e) { it.cause }.take(3)
        .joinToString(" ← ") { c -> c.javaClass.simpleName + ": " + c.message.orEmpty().take(90) }

    private fun get(path: String): String? = fetch(path).getOrNull()

    /** What the panel shows: the stations, and when the catalogue could not be read, why (else "nothing found" is true). */
    class Loaded(val stations: List<Station>, val error: Throwable? = null)

    /** A logo that is the station's own picture, not a catalogue site's generic icon. */
    private fun goodLogo(url: String) = url.startsWith("http") && !url.contains("radiomap.eu/2015/images/icon") && !url.endsWith("favicon.ico")

    private fun key(name: String) = name.lowercase().replace(Regex("[^a-zа-яё0-9]"), "")

    /** [needLogo]: only stations with a real logo (for naming FM stations); streams are kept without one too. */
    private fun parse(text: String, needLogo: Boolean = true): List<Station> {
        val a = JSONArray(text)
        val out = LinkedHashMap<String, Station>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val name = o.optString("name").trim()
            val logo = o.optString("favicon").trim().takeIf(::goodLogo).orEmpty()
            if (name.isEmpty() || (needLogo && logo.isEmpty())) continue
            // one entry per station (the catalogue has a stream per city and quality)
            val k = key(name).take(14)
            if (k.isNotEmpty() && k !in out) out[k] = Station(
                name.replace(Regex("\\s*\\(.*?\\)\\s*$"), ""), logo,
                o.optString("url_resolved").ifEmpty { o.optString("url") }, o.optString("stationuuid"), o.optString("tags").take(60),
            )
        }
        return out.values.toList()
    }

    /** The country's most listened stations with a logo, cached for a week. Blocking. */
    fun popular(ctx: Context, cc: String): List<Station> {
        val f = File(ctx.filesDir, "radio/popular_$cc.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) runCatching { return parse(f.readText()) }
        val text = get("/json/stations/bycountrycodeexact/$cc?order=votes&reverse=true&limit=300&hidebroken=true")
            ?: return runCatching { parse(f.readText()) }.getOrDefault(emptyList())
        f.parentFile?.mkdirs(); f.writeText(text)
        return parse(text)
    }

    /** "101.70" → the ways a name may carry it: "101.7", "101,7", "101.70". */
    private fun freqForms(freqText: String): List<String> {
        val short = freqText.trimEnd('0').trimEnd('.')
        return listOf(freqText, short, short.replace('.', ',')).distinct()
    }

    private fun hasFreq(name: String, freqText: String) = freqForms(freqText).any { f ->
        Regex("(^|[^0-9.,])" + Regex.escape(f) + "($|[^0-9])").containsMatchIn(name)
    }

    /** A station of [cc] whose catalogue name carries the frequency ("Русское радио Минск 98.9 FM"). Blocking. */
    fun byFrequency(ctx: Context, cc: String, freqText: String): Station? =
        popular(ctx, cc).firstOrNull { hasFreq(it.name, freqText) }

    /** [list] with the stations naming [freqText] first. */
    fun withFreqFirst(list: List<Station>, freqText: String): List<Station> = list.sortedBy { if (hasFreq(it.name, freqText)) 0 else 1 }

    /** Internet radio of [cc]: the most listened streams, cached for a day. Blocking. */
    fun streams(ctx: Context, cc: String): Loaded {
        val f = File(ctx.filesDir, "radio/popular_$cc.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000L) runCatching { return Loaded(parse(f.readText(), needLogo = false)) }
        val got = fetch("/json/stations/bycountrycodeexact/$cc?order=votes&reverse=true&limit=300&hidebroken=true")
        // no catalogue: the list of an earlier day, if there is one
        val text = got.getOrElse { e -> return Loaded(runCatching { parse(f.readText(), needLogo = false) }.getOrDefault(emptyList()), e) }
        // not the catalogue's JSON (a Wi-Fi login page and the like): not cached, and said as a failure
        val list = runCatching { parse(text, needLogo = false) }.getOrElse { e -> SsmTrace.log("radio: catalogue answer unreadable: ${reason(e)}", keep = true); return Loaded(emptyList(), e) }
        f.parentFile?.mkdirs(); f.writeText(text)
        return Loaded(list)
    }

    /** Streams by name, anywhere. Blocking. */
    fun search(query: String): Loaded {
        val text = fetch("/json/stations/search?name=${URLEncoder.encode(query, "UTF-8")}&order=votes&reverse=true&limit=60&hidebroken=true")
            .getOrElse { return Loaded(emptyList(), it) }
        return runCatching { Loaded(parse(text, needLogo = false)) }.getOrElse { Loaded(emptyList(), it) }
    }

    /** Tells the catalogue a station was played, as it asks apps to (it ranks stations by it). Blocking. */
    fun clicked(id: String) { if (id.isNotEmpty()) get("/json/url/$id") }

    // --- favourite internet stations -----------------------------------------------------------------------

    fun favourites(ctx: Context): List<Station> = runCatching {
        val a = JSONArray(prefs(ctx).getString("inetFavs", "[]"))
        List(a.length()) { Station.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun isFavourite(ctx: Context, st: Station) = favourites(ctx).any { it.url == st.url }

    fun setFavourite(ctx: Context, st: Station, on: Boolean) {
        val l = favourites(ctx).filter { it.url != st.url } + if (on) listOf(st) else emptyList()
        prefs(ctx).edit().putString("inetFavs", JSONArray(l.map { it.json() }).toString()).apply()
    }

    /** A station of [cc] by its name (from the tuner's RDS or a preset), or null. Blocking. */
    fun find(ctx: Context, cc: String, name: String): Station? {
        if (name.isBlank()) return null
        popular(ctx, cc).firstOrNull { key(it.name) == key(name) }?.let { return it }
        // in the country first; a network station (Europa Plus in Bishkek) is often listed under another one
        for (where in listOf("&countrycode=$cc", "")) {
            val text = get("/json/stations/search?name=${URLEncoder.encode(name, "UTF-8")}$where&order=votes&reverse=true&limit=10&hidebroken=true")
                ?: continue
            runCatching { parse(text).firstOrNull() }.getOrNull()?.let { return it }
        }
        return null
    }

    /** The logo picture, from the cache or downloaded once. Blocking. */
    fun bitmap(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val f = File(ctx.filesDir, "radio/logo_${url.hashCode().toUInt().toString(16)}")
        if (!f.exists()) runCatching {
            val bytes = open(url).inputStream.use { it.readBytes() }
            if (bytes.size in 200..2_000_000) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        }
        return runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }
}
