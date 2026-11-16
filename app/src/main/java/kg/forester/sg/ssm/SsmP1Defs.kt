package kg.forester.sg.ssm

import org.json.JSONObject

/**
 * The pre-1999 engine units that speak SSM1: assets/ssm1_engine.json, built by tools/ssm1/build_ssm1.py from FreeSSM
 * (SSM1defs_Engine.xml) and SelectMonitor.ini. A unit is found by its 3-byte ROM id: the entry whose first two bytes
 * match and whose range holds the third; failing that, the closest entry of the same first two bytes (the same family
 * shares its map: A30111, A30113 and A30117 all keep their values at 0x0007…0x0022), marked [Match.exact] = false.
 */
object SsmP1Defs {

    /** A measuring block: what a byte at an address means. [formula] is FreeSSM's, operators left to right. */
    data class Mb(val key: String, val en: String, val unit: String, val formula: String, val precision: Int) {
        val ru: String get() = RU[key] ?: RU[baseKey(key)] ?: en
        fun value(raw: Int): Double? = scale(raw.toDouble(), formula)
    }

    data class DcBlock(val cur: Int?, val mem: Int?, val bits: Map<Int, String>)

    data class Rom(
        val b1: Int, val b2: Int, val from: Int, val to: Int, val name: String, val source: String,
        val mbs: Map<String, Int>, val dc: List<DcBlock>, val clear: Pair<Int, Int>?,
    )

    data class Dtc(val code: String, val en: String)

    data class Match(val romId: String, val rom: Rom, val exact: Boolean)

    class Base(json: String) {
        val mbs: Map<String, Mb>
        val dtcs: Map<String, Dtc>
        val roms: List<Rom>

        init {
            val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val m = o.optJSONObject("mbs")
            mbs = m?.keys()?.asSequence()?.associate { k ->
                val x = m.getJSONObject(k)
                k to Mb(k, x.optString("en"), x.optString("unit"), x.optString("f"), x.optInt("p"))
            }.orEmpty()
            val d = o.optJSONObject("dtcs")
            dtcs = d?.keys()?.asSequence()?.associate { k -> val x = d.getJSONObject(k); k to Dtc(x.optString("code"), x.optString("en")) }.orEmpty()
            val a = o.optJSONArray("roms")
            roms = (0 until (a?.length() ?: 0)).map { i ->
                val r = a!!.getJSONObject(i)
                val mb = r.optJSONObject("mb")
                val dc = r.optJSONArray("dc")
                val clear = r.optJSONArray("clear")
                Rom(
                    r.optInt("b1"), r.optInt("b2"), r.optInt("from"), r.optInt("to"), r.optString("name"), r.optString("src"),
                    mb?.keys()?.asSequence()?.associate { it to mb.getInt(it) }.orEmpty(),
                    (0 until (dc?.length() ?: 0)).map { j ->
                        val b = dc!!.getJSONObject(j)
                        val bits = b.optJSONObject("bits")
                        DcBlock(
                            if (b.isNull("cur")) null else b.optInt("cur"), if (b.isNull("mem")) null else b.optInt("mem"),
                            bits?.keys()?.asSequence()?.associate { it.toInt() to bits.getString(it) }.orEmpty(),
                        )
                    },
                    clear?.takeIf { it.length() == 2 }?.let { it.getInt(0) to it.getInt(1) },
                )
            }
        }

        /** The entry for [romId] ("A30113"), exact or the family's closest; null when the family is unknown. */
        fun find(romId: String): Match? {
            val id = romId.uppercase()
            if (id.length < 6) return null
            val b1 = id.substring(0, 2).toIntOrNull(16) ?: return null
            val b2 = id.substring(2, 4).toIntOrNull(16) ?: return null
            val b3 = id.substring(4, 6).toIntOrNull(16) ?: return null
            val family = roms.filter { it.b1 == b1 && it.b2 == b2 }
            family.firstOrNull { b3 in it.from..it.to }?.let { return Match(id, it, true) }
            val near = family.minByOrNull { minOf(kotlin.math.abs(b3 - it.from), kotlin.math.abs(b3 - it.to)) } ?: return null
            return Match(id, near, false)
        }

        /** The blocks this unit has that carry a formula (a few of FreeSSM's are listed without one), by address. */
        fun blocks(rom: Rom): List<Pair<Mb, Int>> =
            rom.mbs.mapNotNull { (k, addr) -> mbs[k]?.takeIf { it.formula.isNotEmpty() }?.let { it to addr } }.sortedBy { it.second }
    }

    @Volatile private var base: Base? = null

    fun base(ctx: android.content.Context): Base = base ?: synchronized(this) {
        base ?: Base(runCatching { ctx.assets.open("ssm1_engine.json").bufferedReader().use { it.readText() } }.getOrDefault("{}")).also { base = it }
    }

    /**
     * FreeSSM's scaling: [formula] is a chain of operations applied strictly left to right ("*8/100", "-128*100/128",
     * "*-64/450+45/10"); an operand may carry its own sign. Null for a formula it can't read.
     */
    fun scale(x: Double, formula: String): Double? {
        if (formula.isEmpty() || formula[0] !in "+-*/") return null
        var v = x
        var i = 0
        while (i < formula.length) {
            val op = formula[i]
            if (op !in "+-*/") return null
            var j = i + 1
            if (j < formula.length && formula[j] in "+-") j++ // the operand's own sign
            while (j < formula.length && (formula[j].isDigit() || formula[j] == '.')) j++
            val n = formula.substring(i + 1, j).toDoubleOrNull() ?: return null
            if (n == 0.0) return null
            v = when (op) { '+' -> v + n; '-' -> v - n; '*' -> v * n; else -> v / n }
            i = j
        }
        return v
    }

    /** "MB_O2_Rmax_SVX" → "MB_O2": the value a variant measures. */
    fun baseKey(key: String): String = key.split('_').take(2).joinToString("_")

    /** The app's names, in the words of the SSM2 screens; a variant without its own name takes its base's. */
    private val RU = mapOf(
        "MB_VB" to "Напряжение АКБ",
        "MB_VSP" to "Скорость",
        "MB_EREV" to "Обороты",
        "MB_TW" to "Температура ОЖ",
        "MB_ADVS" to "Угол опережения зажигания",
        "MB_QA" to "Датчик расхода воздуха (напряжение)",
        "MB_LDATA" to "Нагрузка (данные ЭБУ)",
        "MB_THV" to "Датчик дросселя (напряжение)",
        "MB_TIM" to "Длительность впрыска",
        "MB_ISC" to "Клапан холостого хода (ISC)",
        "MB_O2" to "Лямбда-зонд (напряжение)",
        "MB_O2_Rmax" to "Лямбда-зонд, максимум",
        "MB_O2_Rmin" to "Лямбда-зонд, минимум",
        "MB_O2_R_SVX" to "Лямбда-зонд правый (напряжение)",
        "MB_O2_Rmax_SVX" to "Лямбда-зонд правый, максимум",
        "MB_O2_Rmin_SVX" to "Лямбда-зонд правый, минимум",
        "MB_O2_L_SVX" to "Лямбда-зонд левый (напряжение)",
        "MB_O2_Lmax_SVX" to "Лямбда-зонд левый, максимум",
        "MB_O2_Lmin_SVX" to "Лямбда-зонд левый, минимум",
        "MB_ALPHA" to "Коррекция смеси по лямбде",
        "MB_ALPHA_R_SVX" to "Коррекция смеси, правый ряд",
        "MB_ALPHA_L_SVX" to "Коррекция смеси, левый ряд",
        "MB_RTRD" to "Коррекция по детонации",
        "MB_WGC" to "Клапан управления наддувом",
        "MB_FPDY" to "Производительность бензонасоса",
        "MB_CPCP" to "Клапан продувки адсорбера",
        "MB_COADJ" to "Подстройка CO",
        "MB_ALT" to "Отключение генератора",
        "MB_BARO.P" to "Атмосферное давление",
    )

    private val UNITS = mapOf("V" to "В", "km/h" to "км/ч", "rpm" to "об/мин", "ms" to "мс", "kg/h" to "кг/ч")
    fun unitRu(u: String) = UNITS[u] ?: u

    /** The id a block has as a parameter on «Датчики», in the log and on the cards. */
    const val PREFIX = "SSM1_"

    fun param(mb: Mb, addr: Int): RrParam = RrParam(
        id = PREFIX + mb.key, kind = RrParam.Kind.EXT, target = 1, name = mb.ru, nameEn = mb.en,
        desc = "SSM1, адрес 0x%04X".format(addr), addr = addr, len = 1, bit = -1,
        units = unitRu(mb.unit), format = if (mb.precision <= 0) "0" else "0." + "0".repeat(mb.precision),
        storage = "", expr = null, deps = emptyList(), conversions = emptyMap(),
    )

    /**
     * The blocks that fill the common snapshot, so the home screen, the log and the trips work as on SSM2. Only those
     * whose unit is the snapshot's: the airflow, throttle and O2 voltages stay parameters of their own (a voltage taken
     * for g/s or % would make the fuel and load figures up).
     */
    fun snapshot(values: Map<String, Double>, now: Long): EcuSnapshot {
        fun v(base: String) = values.entries.firstOrNull { baseKey(it.key) == base }?.value
        return EcuSnapshot(
            timeMs = now,
            rpm = v("MB_EREV")?.toInt(),
            speedKmh = v("MB_VSP")?.toInt(),
            coolantC = v("MB_TW")?.toInt(),
            batteryV = v("MB_VB")?.toFloat(),
            timingDeg = v("MB_ADVS")?.toFloat(),
            injPwMs = v("MB_TIM")?.toFloat(),
            afCorrPct = v("MB_ALPHA")?.toFloat(),
            wastegatePct = v("MB_WGC")?.toFloat(),
        )
    }

    /** The order a round reads in: the gauges' own values first, every round; the rest one or two per round. */
    val FAST = setOf("MB_EREV", "MB_VSP", "MB_THV")
}
