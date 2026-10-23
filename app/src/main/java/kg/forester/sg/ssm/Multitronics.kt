package kg.forester.sg.ssm

import java.io.InputStream
import java.io.OutputStream

/**
 * Multitronics MPC-800 trip computer over Bluetooth (serial port profile), as its own app (com.magictronics,
 * 02.10.2026) speaks to it: the box reads the car itself (K-line, CAN, its own protocol list) and hands out what it
 * read. Frames are [length]['K' 0x4B][command …][check]: the length counts every byte, the check makes the sum of all
 * of them 0. F1 asks who it is, 0B sets its clock, 01 08 gives the live values (a 147-byte frame).
 */
object Multitronics {
    /** A frame: the length, 'K', [body], the check. */
    fun frame(vararg body: Int): ByteArray {
        val f = ByteArray(body.size + 3)
        f[0] = f.size.toByte(); f[1] = 0x4B
        body.forEachIndexed { i, b -> f[i + 2] = b.toByte() }
        f[f.size - 1] = (-(f.sumOf { it.toInt() } and 0xFF)).toByte()
        return f
    }

    val IDENTIFY = frame(0xF1)
    val STATUS = frame(0x01, 0x08)

    /** The box's clock set to the head unit's, as its app does right after connecting. */
    fun clock(c: java.util.Calendar = java.util.Calendar.getInstance()): ByteArray = frame(
        0x0B, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND),
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.YEAR) % 100,
    )

    fun checkOk(f: IntArray) = f.sum() and 0xFF == 0

    /**
     * Frames out of the byte stream: the first byte is the length; a frame whose sum is not 0 is dropped a byte at a
     * time until one fits (its app throws the whole buffer away and waits for the next answer).
     */
    class Frames {
        private val buf = ArrayList<Int>()

        fun feed(bytes: ByteArray, n: Int = bytes.size): List<IntArray> {
            for (i in 0 until n) buf += bytes[i].toInt() and 0xFF
            val out = ArrayList<IntArray>()
            while (buf.isNotEmpty()) {
                val len = buf[0]
                if (len < 3 || (buf.size >= 2 && buf[1] != 0x4B)) { buf.removeAt(0); continue }
                if (buf.size < len) {
                    // a noise byte taken for a long frame's length must not hold back a whole frame behind it
                    val p = (1 until buf.size).firstOrNull { whole(it) } ?: break
                    repeat(p) { buf.removeAt(0) }
                    continue
                }
                val f = IntArray(len) { buf[it] }
                if (checkOk(f)) { out += f; repeat(len) { buf.removeAt(0) } } else buf.removeAt(0)
            }
            return out
        }

        /** A whole frame with a good check starts at [p]. */
        private fun whole(p: Int): Boolean {
            val len = buf[p]
            if (len < 3 || p + len > buf.size || buf[p + 1] != 0x4B) return false
            return (p until p + len).sumOf { buf[it] } and 0xFF == 0
        }

        fun clear() = buf.clear()
    }

    /** What F1 says: the firmware's name, its build (the app 02.10.2026 wants 83) and the model. */
    data class Info(val version: String, val build: Int, val type: Int) {
        val model: String get() = when (type) {
            1 -> "MPC-810"; 2 -> "MPC-810 (24 В)"; 3 -> "MPC-800"; 4 -> "MPC-800 мото"; 5 -> "MPC-850 (лодки)"; 6 -> "MPC-800 (гибриды)"
            else -> "Multitronics ($type)"
        }
    }

    fun info(f: IntArray): Info? {
        if (f.size < 30 || f[2] != 0xF1) return null
        val name = (3 until 15).map { f[it] }.takeWhile { it != 0 }.map { it.toChar() }.joinToString("").trim()
        return Info(name, f[15] + f[16] * 256, f[29])
    }

    /**
     * One value of the 01 08 frame, as its app writes them to its DataStream log: [at] in that app's numbering (its
     * own copy of the frame drops the 'K', so the byte in the frame is at + 1), one or two bytes (low first), and
     * how it is scaled: 1 as is, 2 ÷100, 4 signed ÷10, 5 signed, 7 signed ÷2, 16 the gear.
     */
    class Field(val key: String, val name: String, val units: String, val scale: Int, val at: Int, val len: Int) {
        val id get() = PREFIX + key

        fun value(f: IntArray): Float? {
            val i = at + 1
            if (i + len > f.size - 1) return null
            val raw = if (len == 1) f[i] else f[i] + f[i + 1] * 256
            fun s16() = if (raw < 32768) raw else raw - 65536
            return when (scale) {
                2 -> raw / 100f
                4 -> s16() / 10f
                5 -> s16().toFloat()
                7 -> s16() / 2f
                16 -> when (raw) { in 0..12 -> raw.toFloat(); 20 -> 13f; 30 -> 14f; else -> 0f }
                else -> raw.toFloat()
            }
        }

        fun param() = RrParam(
            id = id, kind = RrParam.Kind.EXT, target = 1, name = name, nameEn = name, desc = "Multitronics",
            addr = -1, len = 0, bit = -1, units = units, format = if (scale == 1 || scale == 5 || scale == 16) "0" else "0.0",
            storage = "", expr = null, deps = emptyList(), conversions = emptyMap(),
        )
    }

    const val PREFIX = "MT_"

    /** The 41 values from byte 67 on, two bytes each but for the four single ones, in its app's order. */
    val FIELDS: List<Field> = run {
        val keys = listOf(
            Triple("SPD", "Скорость", "км/ч"), Triple("RPM", "Обороты", "об/мин"), Triple("INJ", "Время впрыска", "мс"),
            Triple("L100", "Мгновенный расход на 100 км", "л/100"), Triple("LH", "Мгновенный расход в час", "л/ч"),
            Triple("TANK", "Остаток топлива", "л"), Triple("OAT", "Температура наружного воздуха", "°C"),
            Triple("RANGE", "Запас хода (Multitronics)", "км"), Triple("VBAT", "Напряжение бортсети", "В"),
            Triple("ECT", "Температура ОЖ", "°C"), Triple("IAT", "Температура воздуха на впуске", "°C"),
            Triple("MAF", "Расход воздуха", "кг/ч"), Triple("MAP", "Давление во впускном коллекторе", "кПа"),
            Triple("LOAD", "Расчётная нагрузка", "%"), Triple("O2A", "Датчик кислорода 1", "В"), Triple("O2B", "Датчик кислорода 2", "В"),
            Triple("TPS", "Дроссель", "%"), Triple("TACT", "Активность дросселя", ""), Triple("TPS2", "Дроссель 2", "%"),
            Triple("IAC", "Регулятор холостого хода", "шаги"), Triple("IGN", "Угол опережения зажигания", "°"),
            Triple("VAF", "Датчик VAF", "В"), Triple("EMV", "Электромагнитный клапан", "%"),
            Triple("ACP", "Датчик давления кондиционера", "В"), Triple("CORL", "Коррекция левая", "В"),
            Triple("CORR", "Коррекция правая", "В"), Triple("IGNC", "Коррекция угла зажигания", "°"),
            Triple("FUELP", "Давление топлива", "кПа"), Triple("ATF", "Температура масла АКПП", "°C"),
            Triple("LOWB", "Ближний свет", ""), Triple("TKM", "Поездка: пробег", "км"), Triple("TSPD", "Поездка: средняя скорость", "км/ч"),
            Triple("TMIN", "Поездка: время", "мин"), Triple("TL100", "Поездка: средний расход", "л/100"),
            Triple("TL", "Поездка: топливо", "л"), Triple("TCOST", "Поездка: стоимость", ""), Triple("GEAR", "Режим КПП", ""),
            Triple("OILT", "Температура масла двигателя", "°C"), Triple("BOOST", "Давление наддува", "кПа"),
            Triple("OILP", "Давление масла", "кПа"), Triple("HOURS", "Моточасы", "ч"),
        )
        val scales = intArrayOf(1, 1, 2, 4, 2, 1, 5, 1, 4, 5, 5, 4, 5, 5, 2, 2, 1, 1, 5, 1, 4, 2, 5, 4, 2, 2, 7, 5, 5, 1, 4, 1, 1, 4, 4, 1, 16, 5, 5, 5, 5)
        val single = setOf(101, 124, 127, 136)
        var at = 67
        keys.mapIndexed { i, (k, n, u) ->
            val len = if (at in single) 1 else 2
            Field(k, n, u, scales[i], at, len).also { at += len }
        }
    }

    /** A 01 08 answer: long enough for every value. */
    fun isStatus(f: IntArray) = f.size >= 147 && f[2] == 0x01 && f[3] == 0x08

    /** The number of the car protocol the box is on (its list: 64 Subaru 1999–2005, 103–105 OBD-II…), 0: none yet. */
    fun protocol(f: IntArray): Int = f.getOrElse(67) { 0 }

    /**
     * The values of one 01 08 frame, the box's way: it sends 0 for what the car does not give, so a value counts only
     * once it has been something else this session ([seen], kept by the caller); the speed and rpm always.
     */
    fun values(f: IntArray, seen: MutableSet<String>): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        for (fl in FIELDS) {
            val v = fl.value(f) ?: continue
            if (v != 0f) seen += fl.key
            if (fl.key == "SPD" || fl.key == "RPM" || fl.key in seen) out[fl.key] = v
        }
        return out
    }

    /** The regular sample out of [v] ([values]); [tankL] turns the litres left into the tank's percent. */
    fun snapshot(v: Map<String, Float>, now: Long, tankL: Double): EcuSnapshot = EcuSnapshot(
        timeMs = now,
        speedKmh = v["SPD"]?.toInt(),
        rpm = v["RPM"]?.toInt(),
        injPwMs = v["INJ"],
        fuelRateLh = v["LH"],
        batteryV = v["VBAT"],
        coolantC = v["ECT"]?.toInt(),
        intakeC = v["IAT"]?.toInt(),
        mafGs = v["MAF"]?.let { it / 3.6f },
        mapKpa = v["MAP"],
        boostKpa = v["BOOST"],
        throttlePct = v["TPS"],
        timingDeg = v["IGN"],
        fuelLevelPct = v["TANK"]?.takeIf { tankL > 0 }?.let { (it / tankL * 100).toFloat().coerceIn(0f, 100f) },
    )

    /** The values beyond the regular sample, for «Датчики», the cards and the log. */
    fun extras(v: Map<String, Float>): Map<String, Double> =
        v.filterKeys { it !in IN_SNAPSHOT }.entries.associate { (k, x) -> PREFIX + k to x.toDouble() }

    fun params(v: Map<String, Float>): List<RrParam> = FIELDS.filter { it.key in v && it.key !in IN_SNAPSHOT }.map { it.param() }

    private val IN_SNAPSHOT = setOf("SPD", "RPM", "INJ", "LH", "VBAT", "ECT", "IAT", "MAF", "MAP", "BOOST", "TPS", "IGN")

    // ---- for the engine guard: oil temperature and pressure, while a box is on

    @Volatile private var last: Map<String, Float> = emptyMap()
    @Volatile var active = false

    fun publish(v: Map<String, Float>) { last = v }

    fun reset() { last = emptyMap(); active = false }

    /** [role]'s value: "oil" °C, "oilP" bar (the box gives kPa); null without a box or the sensor. */
    fun role(role: String): Float? {
        if (!active) return null
        return when (role) {
            "oil" -> last["OILT"]
            "oilP" -> last["OILP"]?.let { it / 100f }
            else -> null
        }
    }

    fun roleUnit(role: String) = when (role) { "oilP" -> "бар"; "oil" -> "°C"; else -> "" }
}

/** The box on a byte stream: one request, its answer. */
class MultitronicsBox(private val input: InputStream, private val output: OutputStream) {
    private val frames = Multitronics.Frames()
    private val buf = ByteArray(512)

    /** Sends [req] and waits up to [timeoutMs] for an answer to the same command; null when none came. */
    fun request(req: ByteArray, timeoutMs: Long): IntArray? {
        while (input.available() > 0) input.read(buf, 0, minOf(buf.size, input.available()))
        frames.clear()
        output.write(req); output.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(buf, 0, minOf(buf.size, input.available()))
                if (n < 0) throw java.io.IOException("Multitronics: связь потеряна")
                frames.feed(buf, n).firstOrNull { it[2] == (req[2].toInt() and 0xFF) }?.let { return it }
            } else Thread.sleep(5)
        }
        return null
    }
}
