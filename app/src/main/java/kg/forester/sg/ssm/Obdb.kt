package kg.forester.sg.ssm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manufacturer parameters from OBDb (github.com/OBDb, the community database behind the Sidecar app): per make and
 * model a "signal set" of mode 21/22 requests to the engine and the other units, with the bits and the scaling of
 * each value in the answer. The data are CC BY-SA 4.0 (attribution, share-alike): nothing of it ships in the APK, the
 * driver downloads the set of his model into files/obdb/ on request, and the screen that shows it names the source.
 *
 * Only what an OBD session here can ask is taken: CAN with 11-bit ids (the adapter talks to [Command.tx], the unit
 * answers from [Command.rx]); K-line, 29-bit ids and extended addressing are left out.
 */
object Obdb {
    const val PREFIX = "OBDB_"
    /** Bumped when a set is downloaded, picked or the switch flips: a running session takes it up. */
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0)
    private const val RAW = "https://raw.githubusercontent.com/OBDb/"
    private const val API = "https://api.github.com/"

    data class Signal(
        val id: String, val name: String, val path: String,
        val bix: Int, val len: Int, val blsb: Boolean, val sign: Boolean,
        val mul: Double, val div: Double, val add: Double,
        val min: Double?, val max: Double?, val nullMin: Double?, val nullMax: Double?,
        val unit: String, val enumerated: Boolean,
    )

    class Command(val tx: Int, val rx: Int, val req: ByteArray, val freqS: Double, val signals: List<Signal>) {
        /** Bytes of the answer before the signals' bits: the response code and the PID (61 xx / 62 xxxx). */
        val skip get() = req.size
    }

    /** The commands of a signal set (its JSON text) for [year] (null = any year); what this app can't ask is left out. */
    fun parse(json: String, year: Int?): List<Command> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("commands") ?: return emptyList()
        val out = ArrayList<Command>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (c.has("eax") || c.has("proto") && !c.optString("proto").startsWith("15765-4-11")) continue
            val hdr = c.optString("hdr")
            if (hdr.length != 3) continue
            val tx = hdr.toIntOrNull(16) ?: continue
            // a command for some model years only: left out for another year, kept when the year is unknown
            val filter = c.optJSONObject("filter")
            if (filter != null && year != null && !matches(filter, year)) continue
            val rx = c.optString("rax").takeIf { it.isNotEmpty() }?.toIntOrNull(16) ?: (tx + 8)
            if (rx > 0x7FF) continue
            val cmd = c.optJSONObject("cmd") ?: continue
            val req = when {
                cmd.has("22") -> cmd.getString("22").toIntOrNull(16)?.let { byteArrayOf(0x22, (it shr 8).toByte(), it.toByte()) }
                cmd.has("21") -> cmd.getString("21").toIntOrNull(16)?.let { byteArrayOf(0x21, it.toByte()) }
                else -> null // mode 01 is read the standard way already
            } ?: continue
            val sigs = c.optJSONArray("signals")?.let { signals(it) } ?: continue
            if (sigs.isEmpty()) continue
            out += Command(tx, rx, req, c.optDouble("freq", 1.0).coerceAtLeast(0.2), sigs)
        }
        return out
    }

    private fun signals(a: JSONArray): List<Signal> = (0 until a.length()).mapNotNull { k ->
        val s = a.optJSONObject(k) ?: return@mapNotNull null
        if (s.optBoolean("hidden")) return@mapNotNull null
        val f = s.optJSONObject("fmt") ?: return@mapNotNull null
        fun d(key: String) = if (f.has(key)) f.optDouble(key) else null
        Signal(
            id = s.optString("id"), name = s.optString("name", s.optString("id")), path = s.optString("path"),
            bix = f.optInt("bix", 0), len = f.optInt("len", 8), blsb = f.optBoolean("blsb"), sign = f.optBoolean("sign"),
            mul = f.optDouble("mul", 1.0), div = f.optDouble("div", 1.0).takeIf { it != 0.0 } ?: 1.0, add = f.optDouble("add", 0.0),
            min = d("min"), max = d("max"), nullMin = d("nullmin"), nullMax = d("nullmax"),
            unit = f.optString("unit"), enumerated = f.has("map"),
        ).takeIf { it.id.isNotEmpty() && it.len in 1..64 }
    }

    /** OBDb's year filter: a range, open on one side, a list of years; "from" after "to" means outside the range. */
    fun matches(f: JSONObject, year: Int): Boolean {
        val from = if (f.has("from")) f.optInt("from") else null
        val to = if (f.has("to")) f.optInt("to") else null
        val years = f.optJSONArray("years")
        if (years != null) for (i in 0 until years.length()) if (years.optInt(i) == year) return true
        return when {
            from != null && to != null -> if (from < to) year in from..to else year >= from || year <= to
            to != null -> year <= to
            from != null -> year >= from
            else -> false
        }
    }

    /**
     * A signal's value out of [payload] (the answer after its response code and PID): [Signal.len] bits from bit
     * [Signal.bix], most significant first (bytes reversed first when [Signal.blsb]), then × mul ÷ div + add, held
     * within min…max; null on a null bound or when the answer is too short.
     */
    fun decode(s: Signal, payload: ByteArray): Double? {
        val end = s.bix + s.len
        if (end > payload.size * 8) return null
        var data = payload
        if (s.blsb && s.len > 8) {
            data = payload.copyOf()
            val first = s.bix / 8
            val n = (s.len + 7) / 8
            val last = minOf(first + n, data.size)
            val rev = data.copyOfRange(first, last).reversedArray()
            rev.copyInto(data, first)
        }
        var raw = 0L
        for (i in s.bix until end) {
            val bit = (data[i / 8].toInt() shr (7 - i % 8)) and 1
            raw = (raw shl 1) or bit.toLong()
        }
        if (s.sign && s.len < 64 && raw and (1L shl (s.len - 1)) != 0L) raw -= 1L shl s.len
        var v = raw * s.mul / s.div + s.add
        val lo = s.min ?: 0.0
        if (s.max != null && s.max > lo) v = v.coerceIn(lo, s.max)
        if (s.nullMin != null && v <= s.nullMin + 1e-12) return null
        if (s.nullMax != null && v >= s.nullMax - 1e-12) return null
        return v
    }

    private val UNITS = mapOf(
        "celsius" to "°C", "fahrenheit" to "°F", "percent" to "%", "volts" to "В", "millivolts" to "мВ", "amps" to "А",
        "milliamps" to "мА", "kilometersPerHour" to "км/ч", "kilometers" to "км", "miles" to "мили", "rpm" to "об/мин",
        "kilopascal" to "кПа", "bars" to "бар", "psi" to "psi", "newtonMeters" to "Н·м", "liters" to "л", "seconds" to "с",
        "milliseconds" to "мс", "minutes" to "мин", "hours" to "ч", "degrees" to "°", "gramsPerSecond" to "г/с",
        "kilowatts" to "кВт", "watts" to "Вт", "kilowattHours" to "кВт·ч", "ampereHours" to "А·ч", "millimeters" to "мм",
        "centimeters" to "см", "meters" to "м", "metersPerSecondSquared" to "м/с²", "gravity" to "g", "hertz" to "Гц",
        "kiloohms" to "кОм", "ohms" to "Ом", "litersPerHour" to "л/ч", "days" to "дн.",
    )
    private val SWITCHES = setOf("offon", "onoff", "noyes", "yesno")

    /** A signal as a «Датчики» parameter: OBDb names are English, the units are shown the Russian way. */
    fun param(s: Signal): RrParam {
        val step = kotlin.math.abs(s.mul / s.div)
        val dec = if (step >= 1 && s.add == Math.floor(s.add)) 0 else kotlin.math.ceil(-kotlin.math.log10(step.coerceAtLeast(1e-6))).toInt().coerceIn(0, 3)
        val sw = s.unit in SWITCHES && s.len == 1
        return RrParam(
            id = PREFIX + s.id, kind = if (sw) RrParam.Kind.SWITCH else RrParam.Kind.EXT, target = 1, name = s.name, nameEn = s.name,
            desc = "OBDb" + if (s.path.isNotEmpty()) ", ${s.path}" else "", addr = -1, len = 0, bit = -1,
            units = if (s.enumerated || sw) "" else UNITS[s.unit] ?: "", format = if (dec == 0) "0" else "0." + "0".repeat(dec),
            storage = "", expr = null, deps = emptyList(), conversions = emptyMap(),
        )
    }

    /**
     * Asks the commands that carry a ticked signal, each no more often than its set says (freq, s) and at most
     * [perCycle] of them per poll, so the gauges' own requests stay quick.
     */
    class Poller(val commands: List<Command>, private val perCycle: Int = 2) {
        private val lastMs = HashMap<Command, Long>()
        private val values = HashMap<String, Double>()
        private val fails = HashMap<Command, Int>()
        @Volatile var watched: Set<String> = emptySet()

        val params: List<RrParam> by lazy { commands.flatMap { c -> c.signals.map { param(it) } }.distinctBy { it.id } }

        fun poll(link: ObdLink, now: Long) {
            val due = commands.filter { c ->
                (fails[c] ?: 0) < 3 && c.signals.any { PREFIX + it.id in watched } &&
                    now - (lastMs[c] ?: 0L) >= (c.freqS * 1000).toLong().coerceAtLeast(1000)
            }.sortedBy { lastMs[it] ?: 0L }.take(perCycle)
            for (c in due) {
                lastMs[c] = now
                val a = runCatching { link.requestTo(c.tx, c.rx, c.req) }.getOrNull()
                // the answer must be to this PID: 62 hi lo / 61 pid
                if (a == null || a.size < c.skip || (1 until c.skip).any { a[it] != c.req[it] }) {
                    if (((fails[c] ?: 0) + 1).also { fails[c] = it } >= 3) SsmTrace.log("OBDb: %03X %s gets no answer, dropped".format(c.tx, c.req.toHex()))
                    continue
                }
                fails[c] = 0
                val payload = a.copyOfRange(c.skip, a.size)
                for (s in c.signals) decode(s, payload)?.let { values[PREFIX + s.id] = it }
            }
        }

        fun values(): Map<String, Double> = values.filterKeys { it in watched }
    }

    // ---- files and download

    fun dir(filesDir: File) = File(filesDir, "obdb")

    /** The downloaded signal set of [repo] ("Toyota-RAV4"), or null. */
    fun file(filesDir: File, repo: String): File? = File(dir(filesDir), "$repo.json").takeIf { it.isFile && it.length() > 0 }

    /** The repository prefix OBDb files a make under: "Toyota", "Land-Rover", "VauxhallOpel", "MINI"… */
    fun makePrefixes(make: String): List<String> {
        val m = make.trim().lowercase().replace(' ', '-')
        return when (m) {
            "opel", "vauxhall" -> listOf("VauxhallOpel", "Vauxhall", "Opel")
            "vw", "volkswagen" -> listOf("Volkswagen", "VW")
            "mercedes", "mercedes-benz" -> listOf("Mercedes-Benz", "Mercedes")
            "land-rover", "landrover" -> listOf("Land-Rover", "Landrover")
            "nissan", "infiniti" -> listOf(m.replaceFirstChar { it.uppercase() }, "INFINITI", "NissanInfiniti")
            else -> listOf(m.split('-').joinToString("-") { w -> w.replaceFirstChar { it.uppercase() } })
        }
    }

    /** The models OBDb has for [make] (repository names, "Toyota-RAV4"), from GitHub's search; empty when offline. */
    fun models(make: String): List<String> {
        val prefixes = makePrefixes(make).map { it.lowercase() + "-" }
        val q = makePrefixes(make).first().substringBefore('-')
        val text = get("${API}search/repositories?q=org:OBDb+${java.net.URLEncoder.encode(q, "UTF-8")}+in:name&per_page=100") ?: return emptyList()
        val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it).optString("name") }
            .filter { n -> prefixes.any { n.lowercase().startsWith(it) } }.sorted()
    }

    /**
     * Downloads the signal set of [repo] for [year]: the year-range file that covers it (signalsets/v3/2015-2019.json),
     * else default.json. Returns the file, or null when nothing came.
     */
    fun download(filesDir: File, repo: String, year: Int?): File? {
        var name = "default.json"
        if (year != null) {
            get("${API}repos/OBDb/$repo/contents/signalsets/v3")?.let { t ->
                val a = runCatching { JSONArray(t) }.getOrNull() ?: return@let
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i).optString("name")
                    val m = Regex("(\\d{4})-(\\d{4})\\.json").matchEntire(n) ?: continue
                    if (year in m.groupValues[1].toInt()..m.groupValues[2].toInt()) { name = n; break }
                }
            }
        }
        val text = get("$RAW$repo/main/signalsets/v3/$name") ?: return null
        runCatching { JSONObject(text) }.getOrElse { return null }
        val d = dir(filesDir).apply { mkdirs() }
        return File(d, "$repo.json").also { it.writeText(text) }
            .also { SsmTrace.log("OBDb: $repo/$name downloaded, ${text.length} bytes") }
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.onFailure { SsmTrace.log("OBDb: $url: $it") }.getOrNull()

    /** The model year by the 10th VIN character (the newest year it can stand for that has already come). */
    fun modelYear(vin: String, nowYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): Int? {
        if (vin.length != 17) return null
        val i = "ABCDEFGHJKLMNPRSTVWXY123456789".indexOf(vin[9].uppercaseChar())
        if (i < 0) return null
        var y = 1980 + i
        while (y + 30 <= nowYear + 1) y += 30
        return y
    }
}
