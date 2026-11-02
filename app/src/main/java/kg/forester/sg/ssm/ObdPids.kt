package kg.forester.sg.ssm

/** One value out of a mode 01 (or 02) PID answer: SAE J1979 scaling, [f] gets the PID's data bytes. */
class ObdValue(
    val pid: Int,
    val id: String,
    val name: String,
    val nameEn: String,
    val units: String,
    val format: String,
    val f: (ByteArray) -> Double?,
) {
    fun param() = RrParam(
        id = id, kind = RrParam.Kind.EXT, target = 1, name = name, nameEn = nameEn, desc = "OBD-II, PID %02X".format(pid),
        addr = -1, len = 0, bit = -1, units = units, format = format, storage = "", expr = null,
        deps = emptyList(), conversions = emptyMap(),
    )

    fun text(d: ByteArray): String? = f(d)?.let { v ->
        val k = kg.forester.sg.data.DistanceUnits.kindOf(units)
        param().text(k?.let { kg.forester.sg.data.DistanceUnits.shown(it, v.toFloat()).toDouble() } ?: v) +
            (k?.let { " " + kg.forester.sg.data.DistanceUnits.unit(it) } ?: if (units.isEmpty()) "" else " $units")
    }
}

/** Standard OBD-II parameters (SAE J1979 mode 01) beyond what the gauges take, and the tables of modes 01 PID 01 and 06. */
object ObdPids {
    private fun u(d: ByteArray, i: Int) = d.getOrNull(i)?.toInt()?.and(0xFF)
    private fun a(d: ByteArray) = u(d, 0)?.toDouble()
    private fun ab(d: ByteArray, i: Int = 0): Double? {
        val h = u(d, i) ?: return null
        val l = u(d, i + 1) ?: return null
        return ((h shl 8) or l).toDouble()
    }
    private fun pct(d: ByteArray) = a(d)?.let { it * 100 / 255 }
    private fun trim(d: ByteArray, i: Int = 0) = u(d, i)?.let { (it - 128) * 100.0 / 128 }

    /** Answer data length per PID (bytes after the PID), for splitting an answer that carries several. */
    val LEN = HashMap<Int, Int>().apply {
        listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0x01, 0x41, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B,
            0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x4F, 0x50, 0xA6).forEach { put(it, 4) }
        listOf(0x02, 0x03, 0x0C, 0x10, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1F, 0x21, 0x22, 0x23, 0x31,
            0x32, 0x3C, 0x3D, 0x3E, 0x3F, 0x42, 0x43, 0x44, 0x4D, 0x4E, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
            0x59, 0x5D, 0x5E, 0x63).forEach { put(it, 2) }
        listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0x11, 0x12, 0x13, 0x1C, 0x1D, 0x1E,
            0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x33, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x51, 0x52, 0x5A, 0x5B,
            0x5C, 0x5F, 0x61, 0x62).forEach { put(it, 1) }
        // 4F: the maxima of lambda, O2 volts, O2 current and MAP; 50: the MAF's maximum and three reserved bytes.
        // 68: support bits and six intake air temperatures, bank 1 and 2 sensors 1–3 (J1979-DA as OBDb's SAEJ1979
        // signal set has it; Wikipedia's table copies 67's three bytes there)
        put(0x70, 10); put(0x78, 9); put(0x79, 9); put(0x64, 5); put(0x65, 2); put(0x66, 5); put(0x67, 3)
        put(0x68, 7); put(0x6B, 5); put(0x6D, 11); put(0x6F, 3); put(0x71, 6); put(0x73, 5); put(0x74, 5); put(0x7C, 9)
    }

    private val O2_NAMES = listOf("Б1 Д1", "Б1 Д2", "Б1 Д3", "Б1 Д4", "Б2 Д1", "Б2 Д2", "Б2 Д3", "Б2 Д4")
    private val O2_EN = listOf("B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4")

    /** Every value this app knows how to read off a mode 01 PID; the gauges' own (rpm, speed…) among them for the freeze frame. */
    val ALL: List<ObdValue> = buildList {
        fun v(pid: Int, id: String, name: String, en: String, units: String, fmt: String, f: (ByteArray) -> Double?) =
            add(ObdValue(pid, "OBD_$id", name, en, units, fmt, f))
        v(0x03, "03", "Топливная система: режим", "Fuel system status", "", "0") { a(it) }
        v(0x04, "04", "Расчётная нагрузка", "Calculated load", "%", "0.0") { pct(it) }
        v(0x05, "05", "Температура ОЖ", "Coolant temperature", "°C", "0") { a(it)?.minus(40) }
        v(0x06, "06", "Краткосрочная коррекция топлива, банк 1", "STFT bank 1", "%", "0.0") { trim(it) }
        v(0x07, "07", "Долгосрочная коррекция топлива, банк 1", "LTFT bank 1", "%", "0.0") { trim(it) }
        v(0x08, "08", "Краткосрочная коррекция топлива, банк 2", "STFT bank 2", "%", "0.0") { trim(it) }
        v(0x09, "09", "Долгосрочная коррекция топлива, банк 2", "LTFT bank 2", "%", "0.0") { trim(it) }
        v(0x0A, "0A", "Давление топлива", "Fuel pressure", "кПа", "0") { a(it)?.times(3) }
        v(0x0B, "0B", "Абсолютное давление во впуске", "Intake manifold pressure", "кПа", "0") { a(it) }
        v(0x0C, "0C", "Обороты двигателя", "Engine speed", "об/мин", "0") { ab(it)?.div(4) }
        v(0x0D, "0D", "Скорость", "Vehicle speed", "км/ч", "0") { a(it) }
        v(0x0E, "0E", "Угол опережения зажигания", "Timing advance", "°", "0.0") { a(it)?.let { x -> x / 2 - 64 } }
        v(0x0F, "0F", "Температура воздуха на впуске", "Intake air temperature", "°C", "0") { a(it)?.minus(40) }
        v(0x10, "10", "Массовый расход воздуха", "MAF", "г/с", "0.00") { ab(it)?.div(100) }
        v(0x11, "11", "Положение дроссельной заслонки", "Throttle position", "%", "0.0") { pct(it) }
        for (i in 0 until 8) {
            val pid = 0x14 + i
            v(pid, "%02X".format(pid), "Кислородный датчик ${O2_NAMES[i]}: напряжение", "O2 ${O2_EN[i]} voltage", "В", "0.000") { a(it)?.div(200) }
            v(pid, "%02XT".format(pid), "Кислородный датчик ${O2_NAMES[i]}: коррекция", "O2 ${O2_EN[i]} trim", "%", "0.0") { d ->
                u(d, 1)?.takeIf { it != 0xFF }?.let { (it - 128) * 100.0 / 128 }
            }
        }
        v(0x1F, "1F", "Время с пуска двигателя", "Run time since start", "с", "0") { ab(it) }
        v(0x21, "21", "Пробег с горящим «чеком»", "Distance with MIL on", "км", "0") { ab(it) }
        v(0x22, "22", "Давление в топливной рампе (отн. впуска)", "Fuel rail pressure (rel.)", "кПа", "0.0") { ab(it)?.times(0.079) }
        v(0x23, "23", "Давление в топливной рампе", "Fuel rail gauge pressure", "кПа", "0") { ab(it)?.times(10) }
        for (i in 0 until 8) {
            val pid = 0x24 + i
            v(pid, "%02X".format(pid), "Широкополосный датчик ${O2_NAMES[i]}: лямбда", "WB O2 ${O2_EN[i]} lambda", "λ", "0.000") { ab(it)?.let { x -> x * 2 / 65536 } }
        }
        v(0x2C, "2C", "Заданное открытие EGR", "Commanded EGR", "%", "0.0") { pct(it) }
        v(0x2D, "2D", "Ошибка EGR", "EGR error", "%", "0.0") { trim(it) }
        v(0x2E, "2E", "Продувка адсорбера (заданная)", "Commanded evap purge", "%", "0.0") { pct(it) }
        v(0x2F, "2F", "Уровень топлива", "Fuel level", "%", "0.0") { pct(it) }
        v(0x30, "30", "Прогревов после сброса ошибок", "Warm-ups since codes cleared", "", "0") { a(it) }
        v(0x31, "31", "Пробег после сброса ошибок", "Distance since codes cleared", "км", "0") { ab(it) }
        v(0x33, "33", "Атмосферное давление", "Barometric pressure", "кПа", "0") { a(it) }
        for (i in 0 until 8) {
            val pid = 0x34 + i
            v(pid, "%02X".format(pid), "Широкополосный датчик ${O2_NAMES[i]}: лямбда (ток)", "WB O2 ${O2_EN[i]} lambda (current)", "λ", "0.000") { ab(it)?.let { x -> x * 2 / 65536 } }
            v(pid, "%02XI".format(pid), "Широкополосный датчик ${O2_NAMES[i]}: ток", "WB O2 ${O2_EN[i]} current", "мА", "0.00") { d -> ab(d, 2)?.let { x -> x / 256 - 128 } }
        }
        listOf(0x3C to "Б1 Д1", 0x3D to "Б2 Д1", 0x3E to "Б1 Д2", 0x3F to "Б2 Д2").forEach { (pid, n) ->
            v(pid, "%02X".format(pid), "Температура катализатора $n", "Catalyst temp ${n.replace("Б", "B").replace(" Д", "S")}", "°C", "0") { ab(it)?.let { x -> x / 10 - 40 } }
        }
        v(0x42, "42", "Напряжение блока управления", "Control module voltage", "В", "0.00") { ab(it)?.div(1000) }
        v(0x43, "43", "Абсолютная нагрузка", "Absolute load", "%", "0.0") { ab(it)?.let { x -> x * 100 / 255 } }
        v(0x44, "44", "Заданная лямбда", "Commanded equivalence ratio", "λ", "0.000") { ab(it)?.let { x -> x * 2 / 65536 } }
        v(0x45, "45", "Относительное положение дросселя", "Relative throttle position", "%", "0.0") { pct(it) }
        v(0x46, "46", "Температура окружающего воздуха", "Ambient air temperature", "°C", "0") { a(it)?.minus(40) }
        v(0x47, "47", "Положение дросселя B", "Absolute throttle position B", "%", "0.0") { pct(it) }
        v(0x49, "49", "Педаль газа D", "Accelerator pedal D", "%", "0.0") { pct(it) }
        v(0x4A, "4A", "Педаль газа E", "Accelerator pedal E", "%", "0.0") { pct(it) }
        v(0x4C, "4C", "Заданное положение дросселя", "Commanded throttle actuator", "%", "0.0") { pct(it) }
        v(0x4D, "4D", "Время с горящим «чеком»", "Time run with MIL on", "мин", "0") { ab(it) }
        v(0x4E, "4E", "Время после сброса ошибок", "Time since codes cleared", "мин", "0") { ab(it) }
        v(0x51, "51", "Тип топлива", "Fuel type", "", "0") { a(it) }
        v(0x52, "52", "Доля этанола", "Ethanol fuel", "%", "0.0") { pct(it) }
        v(0x59, "59", "Давление в топливной рампе (абс.)", "Fuel rail absolute pressure", "кПа", "0") { ab(it)?.times(10) }
        v(0x5A, "5A", "Относительное положение педали газа", "Relative accelerator pedal", "%", "0.0") { pct(it) }
        v(0x5B, "5B", "Остаток ресурса гибридной батареи", "Hybrid battery remaining life", "%", "0.0") { pct(it) }
        v(0x5C, "5C", "Температура масла двигателя", "Engine oil temperature", "°C", "0") { a(it)?.minus(40) }
        v(0x5D, "5D", "Момент впрыска", "Fuel injection timing", "°", "0.00") { ab(it)?.let { x -> (x - 26880) / 128 } }
        v(0x5E, "5E", "Расход топлива", "Engine fuel rate", "л/ч", "0.00") { ab(it)?.div(20) }
        v(0x61, "61", "Запрошенный крутящий момент", "Driver demand torque", "%", "0") { a(it)?.minus(125) }
        v(0x62, "62", "Фактический крутящий момент", "Actual engine torque", "%", "0") { a(it)?.minus(125) }
        v(0x63, "63", "Базовый крутящий момент", "Reference torque", "Н·м", "0") { ab(it) }
        // 70: A = which of the values the unit has, then commanded and actual boost of system A and B, 0.03125 kPa a bit
        v(0x70, "70", "Наддув: заданное давление", "Commanded boost pressure A", "кПа", "0.0") { d -> if ((u(d, 0) ?: 0) and 1 != 0) ab(d, 1)?.times(0.03125) else null }
        v(0x70, "70A", "Наддув: фактическое давление", "Actual boost pressure A", "кПа", "0.0") { d -> if ((u(d, 0) ?: 0) and 2 != 0) ab(d, 3)?.times(0.03125) else null }
        // 78/79: A = which of the 4 sensors the bank has, then each sensor 0.1 °C a bit from −40
        for ((pid, bank) in listOf(0x78 to 1, 0x79 to 2)) for (s in 0 until 4) {
            v(pid, "%02X_%d".format(pid, s + 1), "Температура ОГ банк $bank, датчик ${s + 1}", "EGT bank $bank sensor ${s + 1}", "°C", "0") { d ->
                if ((u(d, 0) ?: 0) shr s and 1 != 0) ab(d, 1 + s * 2)?.let { x -> x / 10 - 40 } else null
            }
        }
        v(0xA6, "A6", "Одометр", "Odometer", "км", "0.0") { d ->
            val b = (0 until 4).map { u(d, it) ?: return@v null }
            ((b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()) / 10.0
        }
    }

    /** What the gauges already show (rpm, speed, MAF…): no second entry on «Датчики». */
    private val IN_SNAPSHOT = setOf("OBD_05", "OBD_06", "OBD_07", "OBD_0B", "OBD_0C", "OBD_0D", "OBD_0E", "OBD_0F",
        "OBD_10", "OBD_11", "OBD_2F", "OBD_33", "OBD_42", "OBD_49", "OBD_24", "OBD_34")

    /** The values offered on «Датчики» (ticked ones are polled and logged). */
    val EXTRA: List<ObdValue> = ALL.filter { it.id !in IN_SNAPSHOT }

    fun byPid(pid: Int) = ALL.filter { it.pid == pid }

    // ---- mode 01 PID 01: readiness

    private val COMMON = listOf("Пропуски зажигания", "Топливная система", "Прочие компоненты")
    private val SPARK = listOf("Катализатор", "Подогрев катализатора", "Улавливание паров топлива (EVAP)", "Вторичный воздух",
        "Хладагент кондиционера", "Кислородные датчики", "Подогрев кислородных датчиков", "EGR и VVT")
    private val DIESEL = listOf("Катализатор NMHC", "Нейтрализатор NOx (SCR)", null, "Давление наддува", null,
        "Датчики отработавших газов", "Сажевый фильтр", "EGR и VVT")

    data class Monitor(val name: String, val ready: Boolean)
    /** MIL, how many stored codes, and the monitors the unit has: complete or not yet (after a clear or a dead battery). */
    data class Readiness(val mil: Boolean, val dtcCount: Int, val diesel: Boolean, val monitors: List<Monitor>) {
        val notReady get() = monitors.count { !it.ready }
    }

    fun readiness(d: ByteArray): Readiness? {
        if (d.size < 4) return null
        val a = u(d, 0)!!; val b = u(d, 1)!!; val c = u(d, 2)!!; val dd = u(d, 3)!!
        val diesel = b and 0x08 != 0
        val out = ArrayList<Monitor>()
        for (i in 0 until 3) if (b shr i and 1 != 0) out += Monitor(COMMON[i], b shr (i + 4) and 1 == 0)
        val names = if (diesel) DIESEL else SPARK
        for (i in 0 until 8) {
            val n = names[i] ?: continue
            if (c shr i and 1 != 0) out += Monitor(n, dd shr i and 1 == 0)
        }
        return Readiness(a and 0x80 != 0, a and 0x7F, diesel, out)
    }

    // ---- mode 06: on-board monitoring test results

    /** Unit and scaling of a test value (J1979 appendix E, unit and scaling ID): (scale, offset, units, signed). */
    private class Uas(val scale: Double, val offset: Double = 0.0, val units: String = "", val signed: Boolean = false, val dec: Int = 0)

    private val UAS = mapOf(
        0x01 to Uas(1.0), 0x02 to Uas(0.1, dec = 1), 0x03 to Uas(0.01, dec = 2), 0x04 to Uas(0.001, dec = 3),
        0x05 to Uas(0.0000305, dec = 4), 0x06 to Uas(0.000305, dec = 4), 0x07 to Uas(0.25, units = "об/мин"),
        0x08 to Uas(0.01, units = "км/ч", dec = 2), 0x09 to Uas(1.0, units = "км/ч"), 0x0A to Uas(0.122, units = "мВ", dec = 1),
        0x0B to Uas(0.001, units = "В", dec = 3), 0x0C to Uas(0.01, units = "В", dec = 2), 0x0D to Uas(0.00390625, units = "мА", dec = 2),
        0x0E to Uas(0.001, units = "А", dec = 3), 0x0F to Uas(0.01, units = "А", dec = 2), 0x10 to Uas(1.0, units = "мс"),
        0x11 to Uas(100.0, units = "мс"), 0x12 to Uas(1.0, units = "с"), 0x13 to Uas(1.0, units = "мОм"), 0x14 to Uas(1.0, units = "Ом"),
        0x15 to Uas(1.0, units = "кОм"), 0x16 to Uas(0.1, -40.0, "°C", dec = 1), 0x17 to Uas(0.01, units = "кПа", dec = 2),
        0x18 to Uas(0.0117, units = "кПа", dec = 2), 0x19 to Uas(0.079, units = "кПа", dec = 2), 0x1A to Uas(1.0, units = "кПа"),
        0x1B to Uas(10.0, units = "кПа"), 0x1C to Uas(0.01, units = "°", dec = 2), 0x1D to Uas(0.5, units = "°", dec = 1),
        0x1E to Uas(0.0000305, units = "λ", dec = 3), 0x1F to Uas(0.05, units = "AFR", dec = 2), 0x20 to Uas(0.0039062, dec = 3),
        0x21 to Uas(1.0, units = "мГц"), 0x22 to Uas(1.0, units = "Гц"), 0x23 to Uas(1.0, units = "кГц"), 0x24 to Uas(1.0, units = "шт."),
        0x25 to Uas(1.0, units = "км"), 0x26 to Uas(0.1, units = "мВ/мс", dec = 1), 0x27 to Uas(0.01, units = "г/с", dec = 2),
        0x28 to Uas(1.0, units = "г/с"), 0x29 to Uas(0.25, units = "Па/с", dec = 2), 0x2A to Uas(0.001, units = "кг/ч", dec = 3),
        0x2B to Uas(1.0, units = "переключ."), 0x2C to Uas(0.01, units = "г/цил.", dec = 2), 0x2D to Uas(0.01, units = "мг/такт", dec = 2),
        0x2F to Uas(0.01, units = "%", dec = 2), 0x30 to Uas(0.001526, units = "%", dec = 3), 0x31 to Uas(0.001, units = "л", dec = 3),
        0x34 to Uas(1.0, units = "мин"), 0x35 to Uas(10.0, units = "мс"),
        0x81 to Uas(1.0, signed = true), 0x82 to Uas(0.1, signed = true, dec = 1), 0x83 to Uas(0.01, signed = true, dec = 2),
        0x84 to Uas(0.001, signed = true, dec = 3), 0x85 to Uas(0.0000305, signed = true, dec = 4), 0x86 to Uas(0.000305, signed = true, dec = 4),
        0x8A to Uas(0.122, units = "мВ", signed = true, dec = 1), 0x8B to Uas(0.001, units = "В", signed = true, dec = 3),
        0x8C to Uas(0.01, units = "В", signed = true, dec = 2), 0x8D to Uas(0.00390625, units = "мА", signed = true, dec = 2),
        0x8E to Uas(0.001, units = "А", signed = true, dec = 3), 0x90 to Uas(1.0, units = "мс", signed = true),
        0x96 to Uas(0.1, units = "°C", signed = true, dec = 1), 0x9C to Uas(0.01, units = "°", signed = true, dec = 2),
        0x9D to Uas(0.5, units = "°", signed = true, dec = 1), 0xA8 to Uas(1.0, units = "г/с", signed = true),
        0xA9 to Uas(0.25, units = "Па/с", signed = true, dec = 2), 0xAF to Uas(0.01, units = "%", signed = true, dec = 2),
        0xB0 to Uas(0.003052, units = "%", signed = true, dec = 3), 0xFC to Uas(0.01, units = "кПа", signed = true, dec = 2),
        0xFD to Uas(0.001, units = "кПа", signed = true, dec = 3), 0xFE to Uas(0.25, units = "Па", signed = true, dec = 2),
    )

    /** On-board monitor IDs (J1979 appendix D): what was tested. */
    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x08 -> "Кислородный датчик ${O2_NAMES[mid - 1]}"
        in 0x09..0x10 -> "Кислородный датчик, банк ${3 + (mid - 0x09) / 4}, датчик ${(mid - 0x09) % 4 + 1}"
        0x21 -> "Катализатор, банк 1"; 0x22 -> "Катализатор, банк 2"; 0x23 -> "Катализатор, банк 3"; 0x24 -> "Катализатор, банк 4"
        0x31 -> "EGR, монитор 1"; 0x32 -> "EGR, монитор 2"; 0x33 -> "EGR, монитор 3"; 0x34 -> "EGR, монитор 4"
        0x35 -> "VVT, банк 1"; 0x36 -> "VVT, банк 2"; 0x37 -> "VVT, банк 3"; 0x38 -> "VVT, банк 4"
        0x39 -> "EVAP: утечка 0,150″"; 0x3A -> "EVAP: утечка 0,090″"; 0x3B -> "EVAP: утечка 0,040″"; 0x3C -> "EVAP: утечка 0,020″"
        0x3D -> "EVAP: продувка"
        in 0x41..0x48 -> "Подогрев кислородного датчика ${O2_NAMES[mid - 0x41]}"
        in 0x49..0x50 -> "Подогрев кислородного датчика, банк ${3 + (mid - 0x49) / 4}, датчик ${(mid - 0x49) % 4 + 1}"
        0x61 -> "Подогрев катализатора, банк 1"; 0x62 -> "Подогрев катализатора, банк 2"
        0x71 -> "Вторичный воздух 1"; 0x72 -> "Вторичный воздух 2"; 0x73 -> "Вторичный воздух 3"; 0x74 -> "Вторичный воздух 4"
        0x81 -> "Топливная система, банк 1"; 0x82 -> "Топливная система, банк 2"; 0x83 -> "Топливная система, банк 3"; 0x84 -> "Топливная система, банк 4"
        0x85 -> "Давление наддува, банк 1"; 0x86 -> "Давление наддува, банк 2"
        0x90 -> "Датчик NOx, банк 1"; 0x91 -> "Датчик NOx, банк 2"
        0x98 -> "Нейтрализатор NOx, банк 1"; 0x99 -> "Нейтрализатор NOx, банк 2"
        0xA1 -> "Пропуски зажигания, общий"
        in 0xA2..0xAD -> "Пропуски зажигания, цилиндр ${mid - 0xA1}"
        0xB0 -> "Сажевый фильтр, банк 1"; 0xB1 -> "Сажевый фильтр, банк 2"
        0xB2 -> "Сажевый фильтр: давление, банк 1"; 0xB3 -> "Сажевый фильтр: давление, банк 2"
        else -> "Монитор %02X".format(mid)
    }

    /** One test: what, the value, its limits and whether it passed (null when the unit gives no limits). */
    data class TestResult(val name: String, val value: String, val min: String, val max: String, val pass: Boolean?)

    /**
     * Mode 06 answers on CAN (46 <MID TID UASID value min max>…, 9 bytes a test) into readable results; a unit and scaling
     * this table lacks shows the raw numbers, pass or fail still holds.
     */
    fun testsCan(d: ByteArray): List<TestResult> {
        val out = ArrayList<TestResult>()
        var i = 1
        while (i + 9 <= d.size) {
            val mid = u(d, i)!!; val tid = u(d, i + 1)!!; val uas = u(d, i + 2)!!
            val raw = IntArray(3) { k -> ((u(d, i + 3 + k * 2)!! shl 8) or u(d, i + 4 + k * 2)!!) }
            val sc = UAS[uas]
            fun conv(x: Int): Double = if (sc == null) x.toDouble() else (if (sc.signed) x.toShort().toDouble() else x.toDouble()) * sc.scale + sc.offset
            fun txt(x: Int) = if (sc == null) x.toString() else "%.${sc.dec}f".format(conv(x)) + if (sc.units.isEmpty()) "" else " ${sc.units}"
            val v = conv(raw[0]); val lo = conv(raw[1]); val hi = conv(raw[2])
            val name = midName(mid) + if (tid < 0x80) ", тест %02X".format(tid) else ", тест %02X (заводской)".format(tid)
            out += TestResult(name, txt(raw[0]), txt(raw[1]), txt(raw[2]), v in lo..hi)
            i += 9
        }
        return out
    }

    /** Mode 06 before CAN: 46 TID CID value limit; bit 7 of CID says whether the limit is a minimum or a maximum. */
    fun testsKline(d: ByteArray): TestResult? {
        if (d.size < 7) return null
        val tid = u(d, 1)!!; val cid = u(d, 2)!!
        val v = ab(d, 3)!!.toInt(); val lim = ab(d, 5)!!.toInt()
        val isMin = cid and 0x80 != 0
        return TestResult("Тест %02X, компонент %02X".format(tid, cid and 0x7F), v.toString(),
            if (isMin) lim.toString() else "—", if (isMin) "—" else lim.toString(), if (isMin) v >= lim else v <= lim)
    }

    /** Fuel system status (PID 03, A) in words. */
    fun fuelStatus(v: Int): String = when (v) {
        0 -> "—"
        1 -> "открытый контур (прогрев)"; 2 -> "замкнутый контур (по датчику O2)"; 4 -> "открытый контур (нагрузка, торможение)"
        8 -> "открытый контур (неисправность)"; 16 -> "замкнутый контур, неисправность датчика"
        else -> v.toString()
    }
}
