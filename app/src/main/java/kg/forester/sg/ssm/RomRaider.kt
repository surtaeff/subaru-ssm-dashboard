package kg.forester.sg.ssm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the RomRaider logger definitions (v370, GPL, romraider.com) know how to read over SSM, built into
 * assets/romraider_ssm.json by tools/romraider/build_defs.py: standard parameters and switches (offered when
 * the init's capability bits say the unit has them) and extended parameters at RAM addresses of a given ROM.
 */
data class RrParam(
    val id: String,
    val kind: Kind,
    /** 1 = engine unit, 2 = transmission unit, 3 = both (read from the engine unit here). */
    val target: Int,
    val name: String,
    val nameEn: String,
    val desc: String,
    /** First byte to read and how many (1, 2 or 4); -1 for a value calculated from others. */
    val addr: Int,
    val len: Int,
    /** Bit of a one-bit value (switches and a few parameters), else -1. */
    val bit: Int,
    val units: String,
    val format: String,
    /** "float", "int8", "int16", "int32" or "" (unsigned). */
    val storage: String,
    val expr: RrExpr?,
    /** Parameters a calculated one is made of. */
    val deps: List<String>,
    /** Every conversion by units, for references like [P9:kph]. */
    val conversions: Map<String, RrExpr>,
    /** Where the init answer says the unit has it (RomRaider numbering, see [EcuInit.supports]); -1 = always. */
    val capByte: Int = -1,
    val capBit: Int = -1,
    /**
     * Further bytes after the first address's, in order: a parameter RomRaider lists with several <address> elements
     * (P148 Steering Angle Sensor: the high byte at 0x8D, the low at 0x15A) is those bytes concatenated, as its
     * convertor reads them.
     */
    val more: List<Int> = emptyList(),
) {
    enum class Kind { STD, SWITCH, EXT }

    /**
     * Bytes read: [len], but always 4 for a float (E145 "Gear" lists a float at an address without a length, and one
     * byte taken as a float's bits is always about 0).
     */
    val size: Int get() = if (storage == "float" && len in 1..3) 4 else len

    val addresses: IntArray get() = if (addr < 0) IntArray(0) else IntArray(size) { addr + it } + more.toIntArray()

    /**
     * The raw number in [b] (big-endian, as the ECU stores it), the way RomRaider's convertor takes it: a float from
     * the bytes' bits; int8/int16/int32 signed at the width actually read (the byte count, not the type's name: an
     * int16 at one address is a signed byte); anything else unsigned. A bit parameter tests the bit of the unsigned bytes.
     */
    fun raw(b: ByteArray): Double {
        var u = 0L
        for (x in b) u = (u shl 8) or (x.toLong() and 0xFF)
        if (bit >= 0) return ((u shr bit) and 1L).toDouble()
        return when {
            storage == "float" -> java.lang.Float.intBitsToFloat(u.toInt()).toDouble()
            storage.startsWith("int") && b.size in 1..4 -> {
                val bits = b.size * 8
                (if (u and (1L shl (bits - 1)) != 0L) u - (1L shl bits) else u).toDouble()
            }
            else -> u.toDouble()
        }
    }

    /** [units] the Russian way for the screen (the CSV keeps RomRaider's). */
    val unitsRu: String get() = UNITS_RU[units.lowercase()] ?: units

    /** Value text with this parameter's format ("0.00" → 2 decimals). */
    fun text(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "—"
        if (kind == Kind.SWITCH) return if (v != 0.0) "вкл" else "выкл"
        if (kotlin.math.abs(v) >= 1e7) return "%.3g".format(v) // a float address read on the wrong ROM is junk
        val dec = format.substringAfter('.', "").count { it == '0' || it == '#' }
        return "%.${dec}f".format(v)
    }
}

/** A RomRaider conversion: + - * / and brackets over x, numbers and other parameters (P12, [P9:kph]). */
class RrExpr private constructor(private val root: Node) {
    /** [ref] gives another parameter's value, in the given units or its first conversion. */
    fun eval(x: Double, ref: (String, String?) -> Double?): Double? = runCatching { root.v(x, ref) }.getOrNull()

    val refs: List<String> get() = mutableListOf<String>().also { root.collect(it) }

    private sealed class Node {
        abstract fun v(x: Double, r: (String, String?) -> Double?): Double
        open fun collect(out: MutableList<String>) {}
    }
    private class Num(val n: Double) : Node() { override fun v(x: Double, r: (String, String?) -> Double?) = n }
    private object X : Node() { override fun v(x: Double, r: (String, String?) -> Double?) = x }
    private class Ref(val id: String, val units: String?) : Node() {
        override fun v(x: Double, r: (String, String?) -> Double?) = r(id, units) ?: throw IllegalStateException(id)
        override fun collect(out: MutableList<String>) { out += id }
    }
    private class Neg(val a: Node) : Node() {
        override fun v(x: Double, r: (String, String?) -> Double?) = -a.v(x, r)
        override fun collect(out: MutableList<String>) = a.collect(out)
    }
    private class Bin(val op: Char, val a: Node, val b: Node) : Node() {
        override fun v(x: Double, r: (String, String?) -> Double?): Double {
            val p = a.v(x, r); val q = b.v(x, r)
            return when (op) { '+' -> p + q; '-' -> p - q; '*' -> p * q; else -> p / q }
        }
        override fun collect(out: MutableList<String>) { a.collect(out); b.collect(out) }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { ws(); return s.getOrNull(i) }
        fun sum(): Node {
            var n = prod()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') { i++; n = Bin(c, n, prod()) } else return n
            }
        }
        fun prod(): Node {
            var n = unary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/') { i++; n = Bin(c, n, unary()) } else return n
            }
        }
        fun unary(): Node = if (peek() == '-') { i++; Neg(unary()) } else if (peek() == '+') { i++; unary() } else atom()
        fun atom(): Node {
            val c = peek() ?: error("end")
            return when {
                c == '(' -> { i++; sum().also { if (peek() != ')') error(")"); i++ } }
                c == '[' -> {
                    val end = s.indexOf(']', i)
                    val body = s.substring(i + 1, end); i = end + 1
                    Ref(body.substringBefore(':').trim(), body.substringAfter(':', "").trim().ifEmpty { null })
                }
                c.isDigit() || c == '.' -> {
                    val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'E' || s[i] == 'e' ||
                            ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'E' || s[i - 1] == 'e')))) i++
                    Num(s.substring(st, i).toDouble())
                }
                c == 'x' || c == 'X' -> { i++; X }
                c.isLetter() -> {
                    val st = i
                    while (i < s.length && s[i].isLetterOrDigit()) i++
                    Ref(s.substring(st, i), null)
                }
                else -> error("bad '$c'")
            }
        }
    }

    companion object {
        fun parse(s: String): RrExpr? = runCatching {
            val p = Parser(s.trim())
            val n = p.sum()
            if (p.peek() != null) error("trailing")
            RrExpr(n)
        }.getOrNull()
    }
}

private val UNITS_RU = mapOf(
    "degrees" to "°", "c" to "°C", "km/h" to "км/ч", "rpm" to "об/мин", "v" to "В", "volts" to "В", "a" to "А",
    "ma" to "мА", "ms" to "мс", "µs" to "мкс", "kpa" to "кПа", "kpa absolute" to "кПа абс.", "kpa relative" to "кПа отн.",
    "kpa relative sea level" to "кПа отн. уровня моря", "mpa" to "МПа", "bar" to "бар", "g/s" to "г/с", "g/rev" to "г/об",
    "km" to "км", "multiplier" to "×", "raw ecu value" to "", "rawdata" to "", "status" to "", "on/off" to "",
    "misfire count" to "пропусков", "count" to "шт.", "steps" to "шагов", "ohm" to "Ом", "lambda" to "λ",
    "estimated afr" to "AFR", "absolute %" to "%", "nm" to "Н·м", "gear" to "", "index" to "", "position" to "",
    "index position" to "", "offset" to "", "time" to "раз", "times" to "раз", "litres" to "л", "grams" to "г", "g" to "г",
    "degrees/s" to "°/с", "l/100km" to "л/100 км", "mg/cyl" to "мг/цил.", "°ca" to "° п.к.в.", "mm³/st" to "мм³/такт",
    "coefficient" to "", "ratio" to "", "-" to "",
)

object RomRaider {
    @Volatile private var all: List<RrParam>? = null
    /** ROM id → extended parameters with that ROM's address. */
    @Volatile private var byRom: Map<String, List<RrParam>> = emptyMap()
    /** The CarBerry 4.2 logger channels (assets/carberry42_logger.json), read on first use. */
    @Volatile private var carberry: List<RrParam>? = null

    /** The one ROM the CarBerry 4.2 SDlogger list is made for. */
    const val CARBERRY_ROM = "3D04EA4605"

    internal fun parseCarberry(text: String): List<RrParam> {
        val o = JSONObject(text)
        require(o.getString("ecuId").equals(CARBERRY_ROM, ignoreCase = true))
        val ps = o.getJSONArray("params")
        return List(ps.length()) { i ->
            val p = ps.getJSONObject(i)
            val a = p.getJSONArray("a")
            param(p, RrParam.Kind.EXT, a.getInt(0), a.getInt(1), a.getInt(2), emptyList())
                .copy(desc = "CarBerry 4.2 · SDlogger rev1.9. " + p.optString("d"))
        }
    }

    private fun carberry(ctx: Context): List<RrParam> = carberry ?: runCatching {
        parseCarberry(ctx.assets.open("carberry42_logger.json").bufferedReader().use { it.readText() })
    }.onFailure { SsmTrace.log("CarBerry 4.2: $it") }.getOrDefault(emptyList()).also { carberry = it }

    /** The CarBerry 4.2 profile is picked and the engine unit has its ROM; any other ROM keeps the stock list. */
    fun useCarberry(profile: String, rom: String, tcu: Boolean) =
        profile == "carberry42" && rom.equals(CARBERRY_ROM, ignoreCase = true) && !tcu

    /**
     * The stock extended parameters stay as they are (IAM, FBKC, FLKC… keep their ids for the engine guard); the
     * CarBerry list only adds the channels the stock one doesn't have.
     */
    internal fun withCarberry(stock: List<RrParam>, cb: List<RrParam>): List<RrParam> {
        val ids = stock.map { it.id }.toSet()
        return stock + cb.filter { it.id !in ids && it.expr != null }
    }
    var source = ""
        private set

    /** The bundled base as read (standard parameters and switches, ROM → extended), before the driver's files. */
    @Volatile private var base: Pair<List<RrParam>, Map<String, List<RrParam>>>? = null
    /** [kg.forester.sg.data.EcuDefs.stamp] the merged lists were made for. */
    @Volatile private var loaded = -1
    /** The driver's own definition files as last read: what each one added or replaced, or why it could not be read. */
    @Volatile var userFiles: List<kg.forester.sg.data.EcuDefs.Loaded> = emptyList()
        private set

    /** The base with the driver's own RomRaider files over it (see [RomRaiderXml.merge]); read again when they change. */
    fun load(ctx: Context): List<RrParam> {
        val stamp = kg.forester.sg.data.EcuDefs.stamp(ctx)
        all?.takeIf { loaded == stamp }?.let { return it }
        synchronized(this) {
            all?.takeIf { loaded == stamp }?.let { return it }
            val (std, rom) = base ?: (loadBase(ctx) ?: return emptyList<RrParam>().also { all = it; loaded = stamp }).also { base = it }
            val user = runCatching { kg.forester.sg.data.EcuDefs.read(ctx) }.getOrDefault(emptyList())
            val ok = user.filter { it.defs != null }
            val m = RomRaiderXml.merge(std, rom, ok.map { it.entry.name to it.defs!! })
            userFiles = user.map { u ->
                val k = ok.indexOf(u)
                if (k < 0) u else kg.forester.sg.data.EcuDefs.Loaded(u.entry, u.defs, null, m.stats[k])
            }
            userFiles.forEach { u ->
                SsmTrace.log("own definitions ${u.entry.name}: " + (u.error ?: u.rom?.let { "ECUFlash axes for ${it.ecuId}" }
                    ?: u.stats?.let { "added ${it.added}, replaced ${it.replaced}, same ${it.same}" }.orEmpty()))
            }
            byRom = m.byRom
            loaded = stamp
            all = m.all
            return m.all
        }
    }

    private fun loadBase(ctx: Context): Pair<List<RrParam>, Map<String, List<RrParam>>>? {
        val text = runCatching { ctx.assets.open("romraider_ssm.json").bufferedReader().readText() }.getOrNull()
            ?: return null
        return parseBase(text)
    }

    internal fun parseBase(text: String): Pair<List<RrParam>, Map<String, List<RrParam>>> {
        val o = JSONObject(text)
        source = o.optString("source")
        val out = ArrayList<RrParam>()
        val ps = o.getJSONArray("params")
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            val a = p.optJSONArray("a")
            out += param(
                p, RrParam.Kind.STD,
                addr = a?.getInt(0) ?: -1, len = a?.getInt(1) ?: 0, bit = a?.getInt(2) ?: -1,
                deps = p.optJSONArray("dep").strings(),
            ).copy(capByte = p.optInt("b", -1), capBit = p.optInt("cb", -1), more = moreBytes(p.optJSONArray("aa")))
        }
        val ss = o.getJSONArray("switches")
        for (i in 0 until ss.length()) {
            val p = ss.getJSONObject(i)
            out += RrParam(
                id = p.getString("id"), kind = RrParam.Kind.SWITCH, target = p.getInt("t"),
                name = p.optString("ru").ifEmpty { p.getString("n") }, nameEn = p.getString("n"), desc = "",
                addr = p.getInt("a"), len = 1, bit = p.getInt("bit"), units = "", format = "0", storage = "",
                expr = RrExpr.parse("x"), deps = emptyList(), conversions = emptyMap(),
                capByte = p.optInt("b", -1), capBit = p.getInt("bit"),
            )
        }
        val rom = HashMap<String, MutableList<RrParam>>()
        val es = o.getJSONArray("ecuparams")
        for (i in 0 until es.length()) {
            val p = es.getJSONObject(i)
            val r = p.getJSONObject("r")
            for (id in r.keys()) {
                val a = r.getJSONArray(id)
                rom.getOrPut(id) { ArrayList() } += param(p, RrParam.Kind.EXT, a.getInt(0), a.getInt(1), a.getInt(2), emptyList())
            }
        }
        baseRoms = rom.keys.toSet()
        return out to rom
    }

    /** ROMs the bundled base has extended parameters for (a ROM only the driver's file knows still borrows a sibling's). */
    @Volatile private var baseRoms: Set<String> = emptySet()

    private fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { getString(it) }

    /** "aa": the further addresses ([address, length] each) as single bytes, in order (see [RrParam.more]). */
    private fun moreBytes(aa: JSONArray?): List<Int> = if (aa == null) emptyList() else
        (0 until aa.length()).flatMap { i -> aa.getJSONArray(i).let { x -> List(x.getInt(1)) { x.getInt(0) + it } } }

    private fun param(p: JSONObject, kind: RrParam.Kind, addr: Int, len: Int, bit: Int, deps: List<String>): RrParam {
        val c = p.getJSONArray("c")
        val convs = LinkedHashMap<String, RrExpr>()
        var first: JSONArray? = null
        var typed = ""
        for (k in 0 until c.length()) {
            val cv = c.getJSONArray(k)
            if (first == null) first = cv
            if (typed.isEmpty()) typed = cv.getString(3)
            RrExpr.parse(cv.getString(1))?.let { convs.putIfAbsent(cv.getString(0).lowercase(), it) }
        }
        return RrParam(
            id = p.getString("id"), kind = kind, target = p.getInt("t"),
            name = p.optString("ru").ifEmpty { p.getString("n") }, nameEn = p.getString("n"), desc = p.optString("d"),
            addr = addr, len = len, bit = bit,
            // one value in memory, one storage: E36 leaves it out on its kPa conversion only, the others say float
            units = first?.getString(0) ?: "", format = first?.getString(2) ?: "0", storage = first?.getString(3)?.ifEmpty { typed } ?: "",
            expr = first?.let { RrExpr.parse(it.getString(1)) }, deps = deps, conversions = convs,
        )
    }

    /**
     * What this unit can give: standard parameters and switches its init lists, calculated ones whose parts it
     * has, and the extended parameters known for its ROM. [tcu] picks the transmission unit's list.
     */
    fun available(ctx: Context, init: EcuInit, tcu: Boolean,
                  profile: String = kg.forester.sg.data.Repo.settings.loggerProfile): List<RrParam> {
        val defs = load(ctx)
        fun forUnit(t: Int) = if (tcu) t == 2 else t == 1 || t == 3
        val std = defs.filter { p ->
            forUnit(p.target) && p.expr != null && when (p.kind) {
                RrParam.Kind.SWITCH -> capOk(p, init)
                else -> p.addr >= 0 && capOk(p, init)
            }
        }
        val ids = std.map { it.id }.toSet()
        val calculated = defs.filter { p ->
            forUnit(p.target) && p.kind == RrParam.Kind.STD && p.addr < 0 && p.expr != null && p.deps.all { it in ids }
        }
        val rom = init.romId.uppercase()
        val stock = if (tcu) emptyList() else {
            val known = byRom[rom]?.filter { it.expr != null }
            // a ROM only the driver's own file lists: its parameters, the rest from a sibling as before
            if (rom in baseRoms) known.orEmpty() else known.orEmpty().let { k -> k + sibling(rom).filter { p -> k.none { it.id == p.id } } }
        }
        val ext = if (useCarberry(profile, rom, tcu)) withCarberry(stock, carberry(ctx)) else stock
        // the driver's own addresses (engine unit only), for ROMs this base has no extended parameters for
        val own = if (tcu) emptyList() else kg.forester.sg.data.CustomParams.list().map { it.toParam() }.filter { it.expr != null }
        return std + calculated + ext + own
    }

    /**
     * For a ROM the base doesn't know: the extended parameters of its nearest sibling (same first 7 and last 2
     * hex digits, e.g. 2A04446405 → 2A04446305, two revisions of one JDM Forester XT ECU), which usually keep their
     * RAM layout. Marked in the description; the engine guard ignores readings that make no sense for a role.
     */
    private fun sibling(rom: String): List<RrParam> {
        if (rom.length != 10) return emptyList()
        val sib = baseRoms.filter { it.length == 10 && it != rom && it.take(7) == rom.take(7) && it.takeLast(2) == rom.takeLast(2) }
            .minByOrNull { kotlin.math.abs((it[7].digitToIntOrNull(16) ?: 99) - (rom[7].digitToIntOrNull(16) ?: 0)) } ?: return emptyList()
        return byRom[sib].orEmpty().filter { it.expr != null }
            .map { it.copy(desc = "адрес соседней прошивки $sib: значения стоит проверить. " + it.desc) }
    }

    /** ROMs whose extended parameters come from a sibling (see [sibling]), for the log. */
    fun siblingOf(rom: String): String? = if (rom.uppercase() in baseRoms) null
        else sibling(rom.uppercase()).firstOrNull()?.desc?.substringAfter("прошивки ")?.substringBefore(':')

    private fun capOk(p: RrParam, init: EcuInit) = p.capByte < 0 || init.supports(p.capByte, p.capBit)

    /**
     * Values of [params] from one read of [addrs] ([raw] in the same order); calculated parameters use the
     * others' values, so their parts must be in the read as well (see [readList]).
     */
    fun decode(params: List<RrParam>, addrs: IntArray, raw: ByteArray, all: List<RrParam>): Map<String, Double> {
        val byAddr = HashMap<Int, Byte>(addrs.size)
        addrs.forEachIndexed { i, a -> if (i < raw.size) byAddr[a] = raw[i] }
        val defs = all.associateBy { it.id }
        val rawOf = HashMap<String, Double>()
        fun rawValue(p: RrParam): Double? {
            rawOf[p.id]?.let { return it }
            val ad = p.addresses
            val bytes = ByteArray(ad.size) { k -> byAddr[ad[k]] ?: return null }
            return p.raw(bytes).also { rawOf[p.id] = it }
        }
        val out = HashMap<String, Double>()
        fun value(p: RrParam, units: String?, depth: Int): Double? {
            if (depth > 4) return null
            val expr = units?.let { p.conversions[it.lowercase()] } ?: p.expr ?: return null
            val x = if (p.addr >= 0) rawValue(p) ?: return null else 0.0
            // per revolution while cranking or stopping (a few rpm): P200 load came out as 842 g/rev
            if (p.addr < 0 && "P8" in p.deps && (defs["P8"]?.let { value(it, null, depth + 1) } ?: 0.0) < 300) return null
            return expr.eval(x) { id, u -> defs[id]?.let { value(it, u, depth + 1) } }
        }
        for (p in params) value(p, null, 0)?.let { out[p.id] = it }
        return out
    }

    /** Addresses to read for [params], parts of calculated ones included, without repeats. */
    fun readList(params: List<RrParam>, all: List<RrParam>): IntArray {
        val defs = all.associateBy { it.id }
        val out = LinkedHashSet<Int>()
        fun add(p: RrParam, depth: Int) {
            if (depth > 4) return
            p.addresses.forEach { out += it }
            (p.deps + (p.expr?.refs ?: emptyList())).forEach { id -> defs[id]?.let { add(it, depth + 1) } }
        }
        params.forEach { add(it, 0) }
        return out.toIntArray()
    }
}

/** ECU id (the init's "ROM ID") → the car it comes from, "JDM Forester XT 2003 AT" (assets/ecu_ids.json). */
object EcuIds {
    @Volatile private var ids: Map<String, String>? = null

    fun car(ctx: Context, romId: String): String? {
        val m = ids ?: synchronized(this) {
            ids ?: runCatching {
                val o = JSONObject(ctx.assets.open("ecu_ids.json").bufferedReader().readText()).getJSONObject("ids")
                o.keys().asSequence().associateWith { o.getString(it) }
            }.getOrDefault(emptyMap()).also { ids = it }
        }
        return m[romId.uppercase()]
    }
}
