package kg.forester.sg.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** What a preset's cards rely on, for the «Не для этой машины» section of the window. */
enum class Need { TURBO, TCU, SSM, MOTION, BOX }

/** On a car that has [need], [from] gives way to [to] (an automatic: the ATF instead of the intake air). */
data class Upgrade(val from: String, val need: Need, val to: String)

/**
 * A ready-made or saved arrangement of one board: the cards it shows, in order (grid) or at their places ([free]).
 * Ids starting with «@» are SensorBox / CAN roles, put on the car's own input with that role when applied.
 */
data class BoardPreset(
    val board: String,
    val name: String,
    val about: String = "",
    val free: Boolean = false,
    val tiles: List<TileCfg>,
    /** Home only: clusterSize, tachStyle, speedoStyle. */
    val extras: Map<String, String> = emptyMap(),
    /** The 7–8" variant (built-ins only); null: the same cards, trimmed to the screen by [PresetAdapt]. */
    val small: List<TileCfg>? = null,
    /** Cards that fill a place left by one this car has not got, before the board's own fallbacks. */
    val reserve: List<String> = emptyList(),
    val needs: Set<Need> = emptySet(),
    /** "<board>/<name>" for a built-in, "u<time>" for the driver's own. */
    val id: String = "",
    val group: String = "",
    /** This preset's own stand-ins, tried instead of the common ones of [PresetAdapt.standIns]. */
    val swaps: Map<String, List<String>> = emptyMap(),
    val upgrades: List<Upgrade> = emptyList(),
    /** The screen as it comes: applying it clears the saved layout, so the size-dependent factory picture stays. */
    val factory: Boolean = false,
)

object PresetLayout {
    /** [tiles] shown, every other card of [board] after them switched off, so the driver can go on editing as usual. */
    fun format(board: Board, tiles: List<TileCfg>, free: Boolean): String {
        val on = tiles.map { it.copy(on = true) }.distinctBy { it.id }
        return board.format(on + board.defaults.filter { d -> on.none { it.id == d.id } }.map { it.copy(on = false) }, free)
    }

    /** The cards a saved layout shows, in drawing order. */
    fun shown(board: Board, saved: String): List<TileCfg> =
        if (board.isFree(saved)) board.parse(saved).filter { it.on } else board.rows(board.parse(saved)).flatten()

    /**
     * What the driver sees of a layout: its cards, their widths, designs and (free) places. Two layouts with the same
     * shape look the same, whatever cards they keep switched off (a later version adds new ones to every board).
     */
    fun shape(board: Board, saved: String): String {
        val free = board.isFree(saved)
        return (if (free) "F|" else "G|") + shown(board, saved).joinToString(";") { t ->
            "${t.id}:${t.span}:${t.style}" + if (free) ":" + (t.free?.format() ?: "") else ""
        }
    }
}

/** RFC 4648 base64url without padding: the JDK's Base64 is API 26 and android.util.Base64 is a stub in JVM tests. */
object Base64Url {
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val index = IntArray(128) { -1 }.also { a -> ABC.forEachIndexed { i, c -> a[c.code] = i } }

    fun isDigit(c: Char) = c.code < 128 && index[c.code] >= 0

    fun encode(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= b.size) {
            val n = (b[i].toInt() and 0xFF shl 16) or (b[i + 1].toInt() and 0xFF shl 8) or (b[i + 2].toInt() and 0xFF)
            sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63]).append(ABC[n shr 6 and 63]).append(ABC[n and 63])
            i += 3
        }
        when (b.size - i) {
            1 -> {
                val n = b[i].toInt() and 0xFF shl 16
                sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63])
            }
            2 -> {
                val n = (b[i].toInt() and 0xFF shl 16) or (b[i + 1].toInt() and 0xFF shl 8)
                sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63]).append(ABC[n shr 6 and 63])
            }
        }
        return sb.toString()
    }

    /** null on a character outside the alphabet or a length no encoding gives. */
    fun decode(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        val out = ByteArrayOutputStream(s.length * 3 / 4 + 1)
        var buf = 0
        var bits = 0
        for (c in s) {
            if (!isDigit(c)) return null
            buf = (buf shl 6) or index[c.code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buf shr bits and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/** A preset as it came in a code, not yet checked against this app's cards. */
data class RawPreset(
    val v: Int,
    val board: String,
    val name: String,
    val about: String,
    val free: Boolean,
    val tiles: String,
    val extras: Map<String, String>,
    /** Each exported SensorBox / CAN card id to its role, so the receiver can use its own input with that role. */
    val roles: Map<String, String>,
    val id: String = "",
)

/**
 * «CPP1:» + base64url(1 byte of flags + raw DEFLATE of the JSON) + «.» + base64url of the CRC32's low 24 bits.
 * Short enough for a QR a phone reads off a 7" screen; the CRC tells a code a messenger cut short from a whole one.
 */
object PresetCodec {
    const val PREFIX = "CPP1:"
    const val SCHEMA = 1
    const val MAX_CODE = 4096
    const val MAX_JSON = 16 * 1024
    const val MAX_TILES = 64

    sealed interface Decoded {
        data class Ok(val raw: RawPreset) : Decoded
        /** No «CPP1:» in the text at all. */
        object NoCode : Decoded
        /** Cut short, changed or not a preset. */
        object Damaged : Decoded
        /** Made by a later version of the app. */
        object Newer : Decoded
    }

    /** [p] as a code: switched-off cards, relays and local pictures stay home (see [PresetCheck.portableStyle]). */
    fun encode(p: BoardPreset, roles: Map<String, String> = emptyMap(), app: String = ""): String =
        pack(toJson(p, roles, app).toString())

    fun pack(json: String): String {
        val d = Deflater(9, true)
        d.setInput(json.toByteArray(Charsets.UTF_8))
        d.finish()
        val out = ByteArrayOutputStream()
        out.write(0)
        val buf = ByteArray(1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        val payload = out.toByteArray()
        return PREFIX + Base64Url.encode(payload) + "." + Base64Url.encode(crc(payload))
    }

    private fun crc(b: ByteArray): ByteArray {
        val c = CRC32().apply { update(b) }.value
        return byteArrayOf((c shr 16).toByte(), (c shr 8).toByte(), c.toByte())
    }

    /**
     * The code's body and check found anywhere in [text]: after a «Пресет … :» line, in quotes, broken over lines by a
     * messenger. Null without «CPP1:»; empty strings when the code is there but cut or mixed with other text.
     */
    fun extract(text: String): Pair<String, String>? {
        val at = text.indexOf(PREFIX)
        if (at < 0) return null
        val body = StringBuilder()
        var i = at + PREFIX.length
        while (i < text.length && text[i] != '.') {
            val c = text[i]
            if (Base64Url.isDigit(c)) body.append(c) else if (!c.isWhitespace()) return "" to ""
            if (body.length > MAX_CODE) return "" to ""
            i++
        }
        if (i >= text.length) return "" to ""
        i++
        val check = StringBuilder()
        while (i < text.length && check.length < 4) {
            val c = text[i]
            if (Base64Url.isDigit(c)) check.append(c) else if (!c.isWhitespace()) break
            i++
        }
        return body.toString() to check.toString()
    }

    fun decode(text: String): Decoded {
        val (body, check) = extract(text) ?: return Decoded.NoCode
        if (body.isEmpty() || body.length > MAX_CODE || check.length != 4) return Decoded.Damaged
        val payload = Base64Url.decode(body) ?: return Decoded.Damaged
        val sum = Base64Url.decode(check) ?: return Decoded.Damaged
        if (payload.isEmpty() || !sum.contentEquals(crc(payload))) return Decoded.Damaged
        if (payload[0].toInt() != 0) return Decoded.Newer
        val json = inflate(payload, 1) ?: return Decoded.Damaged
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Decoded.Damaged
        val v = o.optInt("v", 0)
        if (v > SCHEMA) return Decoded.Newer
        return fromJson(o)?.let { Decoded.Ok(it) } ?: Decoded.Damaged
    }

    /** The JSON, or null when it is cut or would come out over [MAX_JSON] (a small code that inflates to megabytes). */
    private fun inflate(b: ByteArray, from: Int): String? {
        val inf = Inflater(true)
        return try {
            // the raw stream wants one byte past its end
            inf.setInput(b.copyOfRange(from, b.size) + 0)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_JSON) return null
            }
            out.toString("UTF-8")
        } catch (_: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    fun toJson(p: BoardPreset, roles: Map<String, String> = emptyMap(), app: String = ""): JSONObject {
        val tiles = p.tiles.filter { !it.id.startsWith("outw_") && !it.id.startsWith("rr:") }
            .map { it.copy(on = true, style = PresetCheck.portableStyle(it.id, it.style)) }
        val r = JSONObject()
        tiles.forEach { t -> roles[t.id]?.let { r.put(t.id, it) } }
        return JSONObject().put("v", SCHEMA).put("b", p.board).put("n", p.name).put("a", p.about).put("f", if (p.free) 1 else 0)
            .put("t", Boards.byKey(p.board).format(tiles))
            .put("x", JSONObject().apply { p.extras.forEach { (k, v) -> put(k, v) } })
            .apply { if (r.length() > 0) put("r", r) }
            .apply { if (app.isNotEmpty()) put("app", app) }
            .apply { if (p.id.isNotEmpty()) put("id", p.id) }
    }

    fun fromJson(o: JSONObject): RawPreset? {
        val board = o.optString("b")
        val tiles = o.optString("t")
        if (board.isEmpty() || tiles.isEmpty()) return null
        fun map(k: String): Map<String, String> = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } } ?: emptyMap()
        return RawPreset(o.optInt("v", SCHEMA), board, o.optString("n"), o.optString("a"), o.optInt("f") == 1, tiles, map("x"), map("r"), o.optString("id"))
    }
}

/** An outside preset checked against this app's cards. */
sealed interface Checked {
    /** [dropped]: the card ids that had no place here (another app version's card, a sensor input this car hasn't). */
    data class Ok(val preset: BoardPreset, val dropped: List<String>) : Checked
    data class WrongBoard(val board: String) : Checked
    object Bad : Checked
}

object PresetCheck {
    val KEYS = listOf("homeTiles", "myTiles", "driveTiles", "tcuTiles", "fuelTiles")
    private const val MAX_NAME = 40
    private const val MAX_ABOUT = 200
    private val GRIDS = setOf("8x5", "10x6", "12x8", "")

    /** A card's design that means the same on another unit: a local picture or an own dial goes back to the usual look. */
    fun portableStyle(id: String, style: String): String =
        if (style.startsWith("photo:") || style.startsWith("custom:") || style.contains("|photo:")) "" else style

    /** [style] of card [id] as this app can show it; an unknown design becomes the usual look. */
    fun cleanStyle(id: String, style: String, knownStyle: (String) -> Boolean): String {
        val s = portableStyle(id, style)
        if (s.isEmpty()) return ""
        return when (id) {
            "minimap" -> s // a map design; an unknown one is drawn as the first
            "car3d" -> if (s.startsWith("scale:")) s else ""
            "defi" -> if (s.startsWith("sd:")) s else ""
            "navcluster" -> if (s.startsWith("nav:")) s else ""
            else -> {
                val o = kg.forester.sg.ui.DialOptions.parse(s)
                if (knownStyle(o.base)) o.format() else ""
            }
        }
    }

    fun cleanText(s: String, max: Int): String =
        s.filter { !it.isISOControl() }.trim().take(max).trim()

    fun forBoard(raw: RawPreset, key: String, local: Map<String, String>, knownStyle: (String) -> Boolean): Checked {
        if (raw.board !in KEYS) return Checked.Bad
        if (raw.board != key) return Checked.WrongBoard(raw.board)
        return check(raw, local, knownStyle)
    }

    /** [local]: each SensorBox / CAN role this car has, to the card id of its input. */
    fun check(raw: RawPreset, local: Map<String, String>, knownStyle: (String) -> Boolean): Checked {
        if (raw.board !in KEYS) return Checked.Bad
        val board = Boards.byKey(raw.board)
        val dropped = ArrayList<String>()
        val entries = raw.tiles.removePrefix("!free;").split(';').filter { it.isNotBlank() }.take(PresetCodec.MAX_TILES)
        val mapped = entries.mapNotNull { e ->
            val id = if (e.startsWith("rr:")) e.split(':').take(2).joinToString(":") else e.substringBefore(':')
            when {
                id.startsWith("rr:") || id.startsWith("outw_") -> { dropped += id; null }
                e.removePrefix(id).split(':').getOrNull(1) == "0" -> null
                id.startsWith("box_") || id.startsWith("can_") -> {
                    val mine = raw.roles[id]?.let { local[it] }
                    if (mine == null) { dropped += id; null } else mine + e.removePrefix(id)
                }
                else -> e
            }
        }
        if (mapped.isEmpty()) return Checked.Bad
        val parsed = board.parse(mapped.joinToString(";")).filter { it.on }
        val kept = parsed.map { it.id }.toSet()
        mapped.map { it.substringBefore(':') }.forEachIndexed { i, id ->
            // a second card that took the same id (two inputs with one role here) is gone too
            if (id !in kept || mapped.take(i).any { it.substringBefore(':') == id }) dropped += id
        }
        if (parsed.isEmpty()) return Checked.Bad
        val tiles = parsed.map { t ->
            val r = t.free?.let { r ->
                val w = r.w.coerceAtMost(1f)
                val h = r.h.coerceAtMost(1f)
                FreeRect(r.x.coerceAtMost(1f - w), r.y.coerceAtMost(1f - h), w, h)
            }
            t.copy(style = cleanStyle(t.id, t.style, knownStyle), free = if (raw.free) r ?: FreeRect(0.35f, 0.3f, 0.3f, 0.4f) else r)
        }
        val extras = if (raw.board != HomeLayout.board.key) emptyMap() else buildMap {
            raw.extras["clusterSize"]?.toIntOrNull()?.let { put("clusterSize", it.coerceIn(0, 3).toString()) }
            listOf("tachStyle", "speedoStyle").forEach { k -> raw.extras[k]?.takeIf(knownStyle)?.let { put(k, it) } }
        }
        val name = cleanText(raw.name, MAX_NAME).ifEmpty { "Пресет" }
        return Checked.Ok(BoardPreset(raw.board, name, cleanText(raw.about, MAX_ABOUT), raw.free, tiles, extras), dropped.distinct())
    }

    fun isGrid(s: String) = s in GRIDS
}

/** What the car can show, taken once when a preset is applied (so nothing moves in the middle of a drive). */
data class CarFacts(
    val turbo: Boolean = true,
    /** The engine unit answers only standard OBD-II: no knock, AVCS or injector data, no transmission unit. */
    val obd: Boolean = false,
    /** Another make (not a Subaru). */
    val other: Boolean = false,
    val tcu: Boolean = true,
    /** A calibrated accelerometer with the feature on. */
    val motion: Boolean = true,
    /** SensorBox / CAN role to the card id of the input with it. */
    val roles: Map<String, String> = emptyMap(),
    val wideband: Boolean = false,
    val noWb: Boolean = false,
    /** Card ids that had a value lately; null while that is not known. */
    val seen: Set<String>? = null,
    /** A 7–8" screen. */
    val small: Boolean = false,
) {
    fun has(n: Need) = when (n) {
        Need.TURBO -> turbo
        Need.TCU -> tcu
        Need.SSM -> !obd
        Need.MOTION -> motion
        Need.BOX -> roles.isNotEmpty()
    }
}

/** A preset made for this car: [swaps] (from, to) and [dropped] cards are what the window tells before it is applied. */
data class Adapted(val tiles: List<TileCfg>, val swaps: List<Pair<String, String>>, val dropped: List<String>) {
    val changes get() = swaps.size + dropped.size
}

object PresetAdapt {
    private val TURBO_ONLY = setOf("gaugeKpa", "targetBoost", "boostErr", "wgdc")
    private val KNOCK = setOf("knock", "fbkc", "flkc", "iamV", "iam")
    private val SSM_ONLY = setOf("avcs", "alt", "inj", "injDuty", "throttleMotor")
    private val TCU_VALUES = setOf("lockupPct", "slip", "transfer")
    private val MOTION = setOf("gforce", "p_tilt", "p_gradar")

    private val MY_FALLBACK = listOf("coolant", "oil", "battery", "per100", "range", "intake", "timing", "afcorr", "maf", "rpm")
    private val DRIVE_FALLBACK = listOf("throttle", "load", "maf", "rpm", "chartRpm")

    /** Cards that fill a gap on [key] when the preset's own reserve runs out. */
    fun fallback(key: String) = when (key) {
        DriveBoard.key -> DRIVE_FALLBACK
        TcuBoard.key, FuelBoard.key -> emptyList()
        else -> MY_FALLBACK
    }

    /**
     * Total row weight that still reads at full text size on a 7–8" screen (see CarTokens): about 130 dp a plain card
     * of some 500 dp. Null: the board keeps all its rows (its own small variants say what goes).
     */
    fun budget(key: String): Float? = when (key) {
        MyBoard.key, DriveBoard.key -> 2.7f
        else -> null
    }

    /** Home on 7–8": at most this many cards beside the 3D block, two rows of four (see HomeLayout.columnsFor). */
    const val HOME_SMALL_CARDS = 8

    /** null: [id] works on this car; else what may stand in, best first (empty: nothing, the place goes to a filler). */
    fun standIns(id: String, f: CarFacts, free: Boolean, board: String = ""): List<String>? = when {
        id == "boost" && !f.turbo -> listOf("load", "map", "throttle")
        id in TURBO_ONLY && !f.turbo -> emptyList()
        id == "chartBoost" && !f.turbo -> listOf("chartTiming", "chartRpm")
        id == "btarget" && !f.turbo -> listOf("iam")
        id == "p_turbo" && !f.turbo -> listOf("p_tacho_blue")
        id in KNOCK && f.obd -> listOf("timing", "afcorr")
        id in SSM_ONLY && f.obd -> listOf("maf", "fuel")
        id == "afr" && f.noWb -> listOf("afcorr")
        id == "chartAfr" && f.noWb -> listOf("chartTrims")
        (id == "wideband_afr" || id == "wideband_lambda") && !f.wideband -> listOf("afr")
        board == TcuBoard.key -> null // the screen is there only with the transmission unit
        id == "atf" && !f.tcu -> listOf("oil", "intake")
        id == "gear" && !f.tcu -> if (free) listOf("gearsel") else listOf("rpm")
        id in TCU_VALUES && !f.tcu -> listOf("per100", "range", "battery")
        id in MOTION && !f.motion -> listOf("gmeter", "incline")
        id == "pedal" && f.seen != null && "pedal" !in f.seen -> listOf("throttle")
        id == HomeLayout.ROAD && !f.other -> emptyList()
        else -> null
    }

    /** A role placeholder's card here: the input set to that role, else the nearest built-in value (or none). */
    private fun role(id: String, f: CarFacts): String? {
        val r = id.removePrefix("@")
        f.roles[r]?.let { return it }
        return when (id) {
            "@oil" -> "oil"
            "@afr" -> if (f.wideband) "wideband_afr" else "afr"
            else -> null
        }
    }

    fun adapt(
        p: BoardPreset, f: CarFacts, board: Board = Boards.byKey(p.board), gaugeable: Set<String> = emptySet(),
        rowWeight: (TileCfg) -> Float = { 1f },
    ): Adapted {
        val known = board.defaults.map { it.id }.toSet()
        val base = (if (f.small && p.small != null) p.small else p.tiles).map { it.copy(on = true) }
        val swaps = ArrayList<Pair<String, String>>()
        val dropped = ArrayList<String>()
        // every id the preset names: a stand-in never takes the place of a card that comes later
        val planned = base.map { it.id }.toMutableSet()
        val used = HashSet<String>()
        fun works(id: String) = id in known && standIns(id, f, p.free, board.key) == null
        fun styled(id: String, t: TileCfg) = if (id == t.id || id in gaugeable) t.style else ""
        val out = ArrayList<TileCfg?>()
        val gaps = HashMap<Int, String>()
        base.forEachIndexed { i, t0 ->
            val up = p.upgrades.firstOrNull { it.from == t0.id && f.has(it.need) && it.to !in planned && works(it.to) }
            val t = if (up == null) t0 else {
                swaps += t0.id to up.to
                planned += up.to
                t0.copy(id = up.to, style = styled(up.to, t0))
            }
            val id = if (t.id.startsWith("@")) role(t.id, f)?.let { r ->
                if (standIns(r, f, p.free, board.key) == null) r
                else standIns(r, f, p.free, board.key)!!.firstOrNull { works(it) && it !in planned && it !in used }
            } else {
                val s = standIns(t.id, f, p.free, board.key)
                if (s == null) t.id.takeIf { it in known } else (p.swaps[t.id] ?: s).firstOrNull { works(it) && it !in planned && it !in used }
            }
            if (id == null || id in used) {
                out += null
                gaps[i] = t.id
            } else {
                if (id != t.id && !t.id.startsWith("@")) swaps += t.id to id
                used += id
                planned += id
                out += t.copy(id = id, style = styled(id, t))
            }
        }
        // a place left empty: the preset's reserve, then the board's usual cards; none left: the row closes up
        val fill = (p.reserve + fallback(board.key)).distinct()
        val tiles = ArrayList<TileCfg>()
        base.indices.forEach { i ->
            out[i]?.let { tiles += it; return@forEach }
            val gone = gaps.getValue(i)
            val c = fill.firstOrNull { works(it) && it !in used && it !in planned && (f.seen == null || it in f.seen) }
            if (c == null) { dropped += gone; return@forEach }
            swaps += gone to c
            used += c
            planned += c
            tiles += base[i].copy(id = c, style = styled(c, base[i]))
        }
        val trimmed = if (!f.small || p.free || p.small != null) tiles else trim(board, tiles, p.extras, rowWeight)
        return Adapted(trimmed, swaps, dropped)
    }

    /** The cards that fit a 7–8" screen at full text size: the last rows go first, dials are never shrunk. */
    fun trim(board: Board, tiles: List<TileCfg>, extras: Map<String, String>, rowWeight: (TileCfg) -> Float): List<TileCfg> {
        if (board.key == HomeLayout.board.key) {
            // the 3D block takes a row's height: beside it two rows of four; without it the cards may have the screen
            if (extras["clusterSize"] == "0") return tiles
            return tiles.filter { it.id in board.freeOnly } + tiles.filter { it.id !in board.freeOnly }.take(HOME_SMALL_CARDS)
        }
        val budget = budget(board.key) ?: return tiles
        val rows = board.rows(tiles).toMutableList()
        while (rows.size > 1 && rows.sumOf { r -> r.maxOf(rowWeight).toDouble() } > budget + 0.001) rows.removeAt(rows.lastIndex)
        return rows.flatten()
    }

    /** For a right-hand drive car: each row the other way round, a free card at the same distance from the other side. */
    fun mirror(board: Board, tiles: List<TileCfg>, free: Boolean): List<TileCfg> =
        if (free) tiles.map { t -> t.free?.let { r -> t.copy(free = r.copy(x = (1f - r.x - r.w).coerceIn(0f, (1f - r.w).coerceAtLeast(0f)))) } ?: t }
        else board.rows(tiles.map { it.copy(on = true) }).flatMap { it.reversed() } + tiles.filter { it.id in board.freeOnly }

    /** A preset whose core needs what this car hasn't got: shown apart, under «Не для этой машины». */
    fun misfit(p: BoardPreset, f: CarFacts, a: Adapted): Boolean =
        p.needs.any { !f.has(it) } || (p.tiles.isNotEmpty() && a.changes * 2 >= p.tiles.size)
}

/** Where presets and the undo snapshot live: the settings in the app, a map in the tests. */
interface PresetHost {
    fun layout(key: String): String
    fun setLayout(key: String, v: String)
    /** A Home extra by name (clusterSize, tachStyle, speedoStyle) as text. */
    fun extra(name: String): String
    fun setExtra(name: String, v: String)
    fun pref(key: String): String
    fun setPref(key: String, v: String)
}

object PresetStore {
    private const val OWN = "userPresets"
    const val MAX_OWN = 40
    val HOME_EXTRAS = listOf("clusterSize", "tachStyle", "speedoStyle")
    private fun prevKey(key: String) = "presetPrev.$key"

    fun own(h: PresetHost): List<BoardPreset> = runCatching {
        val a = JSONArray(h.pref(OWN).ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val raw = PresetCodec.fromJson(a.getJSONObject(i)) ?: return@mapNotNull null
            // the own list was written by this app: only the cards are parsed again (a later version may have dropped one)
            val b = Boards.byKey(raw.board)
            val tiles = b.parse(raw.tiles).filter { it.on }
            if (raw.board !in PresetCheck.KEYS || tiles.isEmpty()) null
            else BoardPreset(raw.board, raw.name, raw.about, raw.free, tiles, raw.extras, id = raw.id)
        }
    }.getOrDefault(emptyList())

    private fun write(h: PresetHost, list: List<BoardPreset>) =
        h.setPref(OWN, JSONArray().apply { list.forEach { put(PresetCodec.toJson(it)) } }.toString())

    /** False when the list is full. */
    fun add(h: PresetHost, p: BoardPreset): Boolean {
        val list = own(h)
        if (list.size >= MAX_OWN) return false
        write(h, list + p)
        return true
    }

    fun replace(h: PresetHost, p: BoardPreset) = write(h, own(h).map { if (it.id == p.id) p else it })

    fun delete(h: PresetHost, id: String) = write(h, own(h).filter { it.id != id })

    /**
     * The driver's layout of [key] as [saved] shows it (the factory one as this screen size gets it), as a preset named
     * [name]; Home with its 3D block and dial designs.
     */
    fun current(h: PresetHost, key: String, saved: String, name: String, about: String, id: String): BoardPreset {
        val board = Boards.byKey(key)
        val extras = if (key == HomeLayout.board.key) HOME_EXTRAS.associateWith { h.extra(it) } else emptyMap()
        return BoardPreset(key, name, about, board.isFree(saved), PresetLayout.shown(board, saved), extras, id = id)
    }

    /** The layout before the last preset, with Home's extras; null when there is nothing to go back to. */
    fun prev(h: PresetHost, key: String): Pair<String, Map<String, String>>? {
        var s = h.pref(prevKey(key))
        if (s.isEmpty() && key == MyBoard.key) {
            // what the first version of the window kept, in a key of its own
            val old = h.pref("myTilesPrev")
            if (old.isNotEmpty()) {
                s = JSONObject().put("l", old).toString()
                h.setPref(prevKey(key), s)
                h.setPref("myTilesPrev", "")
            }
        }
        if (s.isEmpty()) return null
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return null
        val x = o.optJSONObject("x")
        return o.optString("l") to (x?.keys()?.asSequence()?.associateWith { x.optString(it) } ?: emptyMap())
    }

    /**
     * Puts [layout] (and [extras]) on [key]. What was there is kept for «Вернуть» unless it looks the same as [layout],
     * or it is itself one of the presets ([presetShapes]) while an older layout of the driver's is already kept: trying
     * one preset after another still brings back the driver's own screen.
     */
    fun apply(h: PresetHost, key: String, layout: String, extras: Map<String, String>, presetShapes: Set<String> = emptySet()) {
        val board = Boards.byKey(key)
        val now = h.layout(key)
        val shapeNow = PresetLayout.shape(board, now)
        if (shapeNow != PresetLayout.shape(board, layout) && (prev(h, key) == null || shapeNow !in presetShapes)) {
            val x = if (key == HomeLayout.board.key) HOME_EXTRAS.associateWith { h.extra(it) } else emptyMap()
            h.setPref(prevKey(key), JSONObject().put("l", now).put("x", JSONObject(x)).toString())
        }
        h.setLayout(key, layout)
        extras.forEach { (k, v) -> if (key == HomeLayout.board.key && k in HOME_EXTRAS) h.setExtra(k, v) }
    }

    /** Back to the layout before the presets; the snapshot is used up. */
    fun undo(h: PresetHost, key: String): Boolean {
        val (l, x) = prev(h, key) ?: return false
        h.setLayout(key, l)
        x.forEach { (k, v) -> if (k in HOME_EXTRAS) h.setExtra(k, v) }
        h.setPref(prevKey(key), "")
        return true
    }
}

/** The app's settings as a [PresetHost]. */
object RepoPresetHost : PresetHost {
    override fun layout(key: String) = Repo.settings.board(key)
    override fun setLayout(key: String, v: String) = Repo.settings.setBoard(key, v)
    override fun extra(name: String): String = when (name) {
        "clusterSize" -> Repo.settings.clusterSize.toString()
        "tachStyle" -> Repo.settings.tachStyle
        "speedoStyle" -> Repo.settings.speedoStyle
        else -> ""
    }
    override fun setExtra(name: String, v: String) {
        when (name) {
            "clusterSize" -> v.toIntOrNull()?.let { Repo.settings.clusterSize = it.coerceIn(0, 3) }
            "tachStyle" -> if (v.isNotEmpty()) Repo.settings.tachStyle = v
            "speedoStyle" -> if (v.isNotEmpty()) Repo.settings.speedoStyle = v
        }
    }
    override fun pref(key: String) = Repo.settings.text(key)
    override fun setPref(key: String, v: String) = Repo.settings.setText(key, v)
}
