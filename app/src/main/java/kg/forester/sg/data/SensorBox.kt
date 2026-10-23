package kg.forester.sg.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/**
 * SensorBox: an Arduino on the head unit's USB with sensors the ECU doesn't have (oil temperature and pressure,
 * exhaust gas, a wideband, flex fuel). The box sends raw readings (hardware/sensorbox), this side knows what is
 * wired to which input and turns the readings into °C, bar, AFR.
 */

/** A board the sketch runs on: its inputs and how its pins sit, for the wiring pictures. USB at the left end. */
enum class BoxBoard(
    val id: String,
    val title: String,
    val sub: String,
    val analog: List<String>,
    val freqPin: String,
    /** MAX31855 wiring: the board's SCK, its MISO (the module's SO) and the chip select. */
    val sck: String,
    val miso: String,
    val cs: String,
    /** The 5 V pin as printed on the board. */
    val v5: String,
    val topRow: List<String>,
    val bottomRow: List<String>,
    /** Pins at the far end (the Mega's SPI header). */
    val endPins: List<String> = emptyList(),
    /** Arduino IDE: Tools → Board, and what else to pick there. */
    val ide: String,
    val pcb: Long,
    /** Pins the firmware lets the outputs (relays) use: free of the inputs, SPI, the frequency input and D13. */
    val outPins: List<String> = emptyList(),
    /** Of those, the ones with a timer: a level (PWM) works only there. */
    val pwmPins: List<String> = emptyList(),
    /** I2C, for a display: the pin names as the wiring picture knows them. */
    val sda: String = "A4",
    val scl: String = "A5",
    /** SPI out, for a colour screen (its SCK is [sck], shared with the MAX31855). */
    val mosi: String = "D11",
    /** A 3.3 V pin, for a module that wants it (the 2.42" OLED); the Pro Micro 5 V has none. */
    val v33: String? = "3V3",
    /** An input's pin as printed on the board, where it is not the input's own name (the ESP32-S3's GPIO numbers). */
    val pinNames: Map<String, String> = emptyMap(),
    /** 3.3 V pins: a 5 V signal needs a divider in front of them. */
    val lowVolt: Boolean = false,
) {
    NANO(
        "nano", "Arduino Nano / Nano V3", "ATmega328P, чип USB CH340 или FTDI. Самый удобный: маленький, дешёвый, 6 аналоговых входов",
        listOf("A0", "A1", "A2", "A3", "A6", "A7"), "D2", "D13", "D12", "D10", "5V",
        listOf("D12", "D11", "D10", "D9", "D8", "D7", "D6", "D5", "D4", "D3", "D2", "GND", "RST", "RX0", "TX1"),
        listOf("D13", "3V3", "REF", "A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "5V", "RST", "GND", "VIN"),
        ide = "Arduino Nano, затем Процессор: ATmega328P (не заливается, выберите ATmega328P (Old Bootloader): у большинства китайских Nano он старый)",
        pcb = 0xFF1E5AA8, outPins = (3..9).map { "D$it" }, pwmPins = listOf("D3", "D5", "D6", "D9"),
    ),
    UNO(
        "uno", "Arduino Uno", "ATmega328P, крупная плата. 6 аналоговых входов, удобно с шилдом под винтовые клеммы",
        listOf("A0", "A1", "A2", "A3", "A4", "A5"), "D2", "D13", "D12", "D10", "5V",
        listOf("SCL", "SDA", "AREF", "GND", "D13", "D12", "D11", "D10", "D9", "D8", "D7", "D6", "D5", "D4", "D3", "D2", "D1", "D0"),
        listOf("IOREF", "RST", "3V3", "5V", "GND", "GND", "VIN", "A0", "A1", "A2", "A3", "A4", "A5"),
        ide = "Arduino Uno",
        pcb = 0xFF1C7C8C, outPins = (3..9).map { "D$it" }, pwmPins = listOf("D3", "D5", "D6", "D9"), sda = "SDA", scl = "SCL",
    ),
    PROMICRO(
        "promicro", "Pro Micro 5 В / Leonardo", "ATmega32U4 со своим USB: приложение точно не спутает её с кабелем ЭБУ. 4 аналоговых входа",
        listOf("A0", "A1", "A2", "A3"), "D7", "D15", "D14", "D10", "VCC",
        listOf("RAW", "GND", "RST", "VCC", "A3", "A2", "A1", "A0", "D15", "D14", "D16", "D10"),
        listOf("D1", "D0", "GND", "GND", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9"),
        ide = "Arduino Leonardo (для Pro Micro 5 В 16 МГц подходит она) или Arduino Micro",
        pcb = 0xFFB0303A, outPins = listOf("D0", "D1", "D2", "D3", "D4", "D5", "D6", "D8", "D9"), pwmPins = listOf("D3", "D5", "D6", "D9"),
        sda = "D2", scl = "D3", mosi = "D16", v33 = null,
    ),
    MEGA(
        "mega", "Arduino Mega 2560", "Большая, 8 аналоговых входов в этой прошивке. Имеет смысл, если датчиков много",
        listOf("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"), "D2", "D52", "D50", "D10", "5V",
        listOf("SCL", "SDA", "AREF", "GND", "D13", "D12", "D11", "D10", "D9", "D8", "D7", "D6", "D5", "D4", "D3", "D2", "D1", "D0"),
        listOf("IOREF", "RST", "3V3", "5V", "GND", "GND", "VIN", "A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"),
        endPins = listOf("D53", "D52", "D51", "D50"),
        ide = "Arduino Mega or Mega 2560",
        pcb = 0xFF1E5AA8, outPins = (3..9).map { "D$it" } + listOf("D11", "D12") + (22..29).map { "D$it" },
        pwmPins = (3..9).map { "D$it" } + listOf("D11", "D12"), sda = "SDA", scl = "SCL", mosi = "D51",
    ),
    // ESP32-S3-DevKitC-1 and its clones; the rows from the USB end, as printed (hardware/sensorbox/README.md)
    ESP32S3(
        "esp32s3", "ESP32-S3 / DevKitC-1", "DevKitC-1 и клоны, свой USB, прошивается с магнитолы. Пины 3,3 В: датчики 5 В через делитель 100k/150k. " +
            "4 входа, с модулем ADS1115 ещё 4 точных",
        listOf("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"), "14", "12", "13", "10", "5V",
        listOf("GND", "5V", "14", "13", "12", "11", "10", "9", "46", "3", "8", "18", "17", "16", "15", "7", "6", "5", "4", "RST", "3V3", "3V3"),
        listOf("GND", "GND", "19", "20", "21", "47", "48", "45", "0", "35", "36", "37", "38", "39", "40", "41", "42", "2", "1", "RX", "TX", "GND"),
        ide = "ESP32S3 Dev Module (пакет esp32 от Espressif), затем USB CDC On Boot: Enabled и USB Mode: Hardware CDC and JTAG",
        pcb = 0xFF1F2430, outPins = listOf("15", "16", "17", "18", "21", "39", "40", "41", "42", "47"),
        pwmPins = listOf("15", "16", "17", "18", "21", "39", "40", "41", "42", "47"), sda = "8", scl = "9", mosi = "11",
        pinNames = mapOf("A0" to "2", "A1" to "4", "A2" to "5", "A3" to "6", "A4" to "ADS A0", "A5" to "ADS A1", "A6" to "ADS A2", "A7" to "ADS A3"),
        lowVolt = true,
    );

    /** The name without its variants: «Arduino Nano», «Pro Micro 5 В». */
    val short: String get() = title.substringBefore(" /")

    /** Every input a sensor can take: the analog pins, four thermocouples (TC1–TC3 on CS pins of their own), the frequency input. */
    val inputs: List<String> get() = analog + THERMOCOUPLES + listOf("F0")

    /** The pin a wire goes to for [input] (a thermocouple's: its CS). */
    fun pinOf(input: String) = when (input) {
        "TC0" -> cs
        "F0" -> freqPin
        in THERMOCOUPLES -> SensorBox.tcCs.value[input] ?: ""
        else -> pinNames[input] ?: input
    }

    /** The inputs on an ADS1115 module rather than the board itself (the ESP32-S3's A4..A7). */
    fun onAds(input: String) = pinNames[input]?.startsWith("ADS") == true

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NANO

        /** The thermocouple inputs: TC0 on the board's own CS, the others on a free output pin each. */
        val THERMOCOUPLES = listOf("TC0", "TC1", "TC2", "TC3")
    }
}

enum class BoxKind(val title: String) {
    /** A three-wire sender fed from the box's 5 V: 0.5 V at zero, 4.5 V at full scale. */
    PRESSURE("Давление 0,5–4,5 В"),
    /** A thermistor to ground with a pull-up to the box's 5 V. */
    NTC("Термистор NTC"),
    /** A voltage with its own reference (a wideband controller's output): value = a + b·U. */
    VOLT("Напряжение 0–5 В"),
    /** A K thermocouple on a MAX31855 module. */
    TC("Термопара K"),
    /** Pulses on the frequency input: value = a + b·f. */
    FREQ("Частота"),
    /** A resistive sender whose resistance rises with the value (VDO pressure), with a pull-up: value from [BoxPreset.res]. */
    RES("Резистивный датчик"),
    /** A sensor fed from the box's 5 V whose output scales with it (GM/Bosch MAP, ACS758): value = a + b·(x·5 V). */
    RATIO("Пропорционально питанию 5 В"),
    /** Not a sensor: an output module (relay, MOSFET, LED), only for the wiring pictures. */
    OUT("Выход"),
}

/** One way a wire goes: from the sensor's [label] to the box's [to] ("5V", "GND", "SIG", "SCK", "SO", "CS", "12V"). */
data class BoxWire(val label: String, val to: String, val color: Long)

const val WIRE_RED = 0xFFEF4444
const val WIRE_BLACK = 0xFF9CA3AF
const val WIRE_YELLOW = 0xFFFACC15
const val WIRE_GREEN = 0xFF22C55E
const val WIRE_BLUE = 0xFF3B82F6
const val WIRE_ORANGE = 0xFFF97316
const val WIRE_PURPLE = 0xFFA855F7

/** A sensor people actually fit, with everything needed to wire and read it. */
data class BoxPreset(
    val id: String,
    val title: String,
    val short: String,
    val kind: BoxKind,
    val unit: String,
    val decimals: Int,
    /** What the value is used for: see [BoxRoles]. */
    val role: String,
    val min: Float,
    val max: Float,
    val wires: List<BoxWire>,
    /** Plain words: where it goes on the car, what to buy, what to watch out for. */
    val tips: List<String>,
    /** PRESSURE: the full scale at 4.5 V. */
    val pMax: Float = 10f,
    /** NTC: the pull-up resistor, ohms. */
    val pull: Float = 2200f,
    /** NTC: °C to ohms, ascending °C. */
    val ntc: List<Pair<Float, Float>> = emptyList(),
    /** VOLT/FREQ: value = a + b·x. */
    val a: Float = 0f,
    val b: Float = 1f,
    val warnAbove: Float? = null,
    val warnBelow: Float? = null,
    /** A pull-up resistor drawn between the signal and 5 V (NTC, open-collector outputs), its text. */
    val pullUp: String? = null,
    /** RES: [value, ohm] ascending, from the sender's data sheet. */
    val res: List<Pair<Float, Float>> = emptyList(),
    // what to buy, from the catalogue (hardware/sensorbox/sensors-catalog.json, docs/knowledge/sensors-catalog.md)
    val partNumbers: List<String> = emptyList(),
    val thread: String = "",
    val priceRub: String = "",
    val buy: String = "",
    val sources: List<String> = emptyList(),
    /** "high", "medium", "low": how sure the catalogue is of the numbers. */
    val confidence: String = "",
)

object BoxRoles {
    val all = listOf(
        "" to "Просто показывать",
        "oil" to "Температура масла",
        "oilP" to "Давление масла",
        "afr" to "AFR (ШДК)",
        "egt" to "Температура выхлопа",
        "fuelP" to "Давление топлива",
        "emap" to "Давление в выхлопе",
        "iatIn" to "Воздух до интеркулера",
        "iatOut" to "Воздух после интеркулера",
        "eth" to "Этанол, %",
        "pumpV" to "Напряжение на бензонасосе",
        "fuelLevel" to "Уровень топлива (провод бака)",
    )

    /** Roles in bar whatever the sensor's unit (see [SensorBox.roleBar]). */
    val PRESSURE = setOf("oilP", "fuelP", "emap")

    fun title(r: String) = all.firstOrNull { it.first == r }?.second ?: "Просто показывать"

    /** What the app does with a role, for the line under the chooser. */
    fun meaning(r: String) = when (r) {
        "oil" -> "Заменит расчётную температуру масла везде: прогрев, турботаймер, 3D мотор, карточка масла"
        "oilP" -> "Предупредит голосом, если давление ниже заводской нормы для этих оборотов, и сразу, если оно упало почти до нуля"
        "afr" -> "Будет в логах и на плитках рядом с AFR от ЭБУ. Если у ЭБУ своего ШДК нет, заменит его везде: плитка AFR, расход, предупреждения о смеси"
        "egt" -> "Предупредит, если выхлоп горячее порога. Термопар можно до четырёх, по одной на цилиндр: тревога по самой горячей"
        "fuelP" -> "Посчитает перепад относительно давления в коллекторе (сколько держит регулятор) и предупредит, если под наддувом он проваливается"
        "fuelLevel" -> "Остаток в баке по датчику, а не только счётом от заправки. Тарировка сама: на стоянках по литрам, известным от заправок. Если уровень отдаёт ЭБУ, берётся он"
        "emap" -> "В лог рядом с наддувом: насколько турбина душит мотор (давление в выхлопе обычно в 1,5–2 раза выше наддува)"
        "iatIn" -> "С датчиком после интеркулера покажет, сколько он снимает, и его КПД (вход в турбину по датчику впуска ЭБУ в MAF)"
        "iatOut" -> "Температура воздуха, который идёт в мотор. С датчиком до интеркулера покажет его перепад и КПД"
        "eth" -> "Доля этанола в баке: на плитке и в логе"
        "pumpV" -> "Если давление топлива проседает, подскажет, не в проводке ли насоса дело (ниже 12 В)"
        else -> "Значение появится в списке индикаторов: на главном экране, в нижней панели"
    }
}

/** NTC tables: Bosch NTC M12-H 0 281 002 170 (data sheet), a 10 kΩ B3950 from its beta. */
private val BOSCH_NTC = listOf(
    -40f to 45313f, -30f to 26114f, -20f to 15462f, -10f to 9397f, 0f to 5896f, 10f to 3792f, 20f to 2500f,
    30f to 1707f, 40f to 1175f, 50f to 834f, 60f to 596f, 70f to 436f, 80f to 323f, 90f to 243f, 100f to 186f,
    110f to 144f, 120f to 113f, 130f to 89f, 140f to 71f, 150f to 57f,
)
private val NTC_10K = (-30..150 step 10).map { t ->
    val k = t + 273.15
    t.toFloat() to (10000.0 * Math.exp(3950.0 * (1 / k - 1 / 298.15))).toFloat()
}

object BoxPresets {
    /** The researched catalogue from the assets; the built-in list below until it is loaded (and in unit tests). */
    @Volatile private var catalog: List<BoxPreset> = emptyList()

    fun load(ctx: android.content.Context) {
        catalog = runCatching { parse(ctx.assets.open("sensorbox/sensors-catalog.json").bufferedReader().readText()) }
            .onFailure { android.util.Log.w("SensorBox", "catalogue: $it") }.getOrDefault(emptyList())
    }

    /** The catalogue from its text (the unit tests read the file itself). */
    fun loadText(text: String) { catalog = parse(text) }

    /** Presets from the catalogue's JSON (see docs/knowledge/sensors-catalog.md for the fields). */
    fun parse(text: String): List<BoxPreset> {
        val a = JSONArray(text)
        fun JSONObject.pairs(k: String) = optJSONArray(k)?.let { t -> List(t.length()) { t.getJSONArray(it).let { r -> r.getDouble(0).toFloat() to r.getDouble(1).toFloat() } } } ?: emptyList()
        fun JSONObject.strings(k: String) = optJSONArray(k)?.let { t -> List(t.length()) { t.getString(it) } } ?: emptyList()
        fun JSONObject.f(k: String) = if (has(k) && !isNull(k)) getDouble(k).toFloat() else null
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val w = o.getJSONArray("wires")
            BoxPreset(
                o.getString("id"), o.getString("title"), o.text("short"), BoxKind.valueOf(o.getString("kind")), o.optString("unit"),
                o.optInt("decimals", 1), o.optString("role"), o.f("min") ?: 0f, o.f("max") ?: 100f,
                List(w.length()) { j -> w.getJSONObject(j).let { x -> BoxWire(x.getString("label"), x.getString("to"), java.lang.Long.decode(x.getString("color"))) } },
                o.strings("tips"), pMax = o.f("pMax") ?: 10f, pull = o.f("pull") ?: 2200f, ntc = o.pairs("ntc"), a = o.f("a") ?: 0f, b = o.f("b") ?: 1f,
                warnAbove = o.f("warnAbove"), warnBelow = o.f("warnBelow"), pullUp = o.optString("pullUp").takeIf { it.isNotBlank() && it != "null" },
                res = o.pairs("res"), partNumbers = o.strings("partNumbers"), thread = o.text("thread"), priceRub = o.text("priceRub"),
                buy = o.text("buy"), sources = o.strings("sources"), confidence = o.text("confidence"),
            )
        }
    }

    private val ntcWires = listOf(BoxWire("Провод 1 датчика", "SIG", WIRE_YELLOW), BoxWire("Провод 2 датчика (или корпус)", "GND", WIRE_BLACK))
    private val pressWires = listOf(
        BoxWire("Питание датчика (обычно красный)", "5V", WIRE_RED),
        BoxWire("Масса датчика (обычно чёрный)", "GND", WIRE_BLACK),
        BoxWire("Сигнал (жёлтый, зелёный или белый)", "SIG", WIRE_YELLOW),
    )
    private val voltWires = listOf(BoxWire("Аналоговый выход контроллера", "SIG", WIRE_YELLOW), BoxWire("Масса контроллера (аналоговая)", "GND", WIRE_BLACK))

    /** Written into the app before the catalogue: still read by slots saved with these ids, and by the unit tests. */
    private val builtIn = listOf(
        BoxPreset(
            "oil_t_bosch", "Температура масла, Bosch", "Bosch NTC M12-H 0 281 002 170, резьба M12×1,5", BoxKind.NTC, "°C", 0, "oil", 0f, 150f,
            ntcWires, listOf(
                "Ставится в проставку под масляный фильтр (сэндвич-адаптер, резьба на EJ M20×1,5) или в сливную пробку с отверстием.",
                "Полярности нет: два провода датчика можно перепутать.",
                "Резистор 1 кОм обязателен, без него вход покажет обрыв. Подойдёт любой на 0,25 Вт.",
            ), pull = 1000f, ntc = BOSCH_NTC, warnAbove = 130f, pullUp = "1 кОм",
        ),
        BoxPreset(
            "oil_t_10k", "Температура масла, NTC 10 кОм", "Китайский датчик 1/8 NPT, 10 кОм при 25 °C (B 3950)", BoxKind.NTC, "°C", 0, "oil", 0f, 150f,
            ntcWires, listOf(
                "Подходит большинству дешёвых датчиков 1/8 NPT с маркировкой 10K или 3950.",
                "Если датчик однопроводной, второй провод это его корпус: соедините GND платы с массой двигателя рядом с датчиком.",
                "Проверьте: в тёплом гараже должно показать около комнатной температуры.",
            ), pull = 2200f, ntc = NTC_10K, warnAbove = 130f, pullUp = "2,2 кОм",
        ),
        BoxPreset(
            "oil_p_10", "Давление масла 0–10 бар", "Датчик 0,5–4,5 В, 3 провода, 1/8 NPT", BoxKind.PRESSURE, "бар", 1, "oilP", 0f, 8f,
            pressWires, listOf(
                "Ставится в тройник вместо штатного датчика лампы давления (лампа остаётся) или в проставку под фильтр. Резьба штатного датчика на EJ 1/8 BSPT (PT), нужен переходник на 1/8 NPT.",
                "Норма на прогретом моторе: от 1 бар на холостых и от 2,9 бар на 5000 об/мин.",
                "Без давления датчик показывает 0,5 В, это 0 бар. Если вход показывает обрыв, проверьте сигнальный провод.",
            ), pMax = 10f,
        ),
        BoxPreset(
            "oil_p_150", "Давление масла 150 psi", "Датчик 0–150 psi (10,3 бар), 0,5–4,5 В", BoxKind.PRESSURE, "бар", 1, "oilP", 0f, 8f,
            pressWires, listOf(
                "Американские датчики подписаны в psi: 150 psi это 10,3 бар, 100 psi это 6,9 бар (тогда поправьте предел ниже).",
                "Подключение такое же, как у любого трёхпроводного датчика давления.",
            ), pMax = 10.34f,
        ),
        BoxPreset(
            "fuel_p", "Давление топлива 0–10 бар", "Тот же трёхпроводный датчик 0,5–4,5 В", BoxKind.PRESSURE, "бар", 2, "", 0f, 6f,
            pressWires, listOf(
                "Ставится в тройник на подаче к топливной рампе. Работы с топливом делайте на холодном моторе, сбросив давление.",
                "По заводскому мануалу на холостых 2,3–2,6 бар (2.0T) или 2,1–2,35 бар (2.5i) с подключённым шлангом регулятора, 2,8–3,1 бар со снятым.",
            ), pMax = 10f,
        ),
        BoxPreset(
            "afr_lc2", "ШДК Innovate LC-2", "Аналоговый выход 1: 0 В = 7,35, 5 В = 22,39 AFR", BoxKind.VOLT, "AFR", 1, "afr", 10f, 20f,
            voltWires, listOf(
                "Если в LM Programmer меняли выход, впишите свои значения при 0 В и при 5 В ниже.",
                "Массу берите аналоговую, с самого контроллера, иначе AFR будет плавать.",
            ), a = 7.35f, b = 3.008f,
        ),
        BoxPreset(
            "afr_aem", "ШДК AEM X-Series", "Выход 0–5 В: AFR = 2,375 · U + 7,3125", BoxKind.VOLT, "AFR", 1, "afr", 10f, 20f,
            voltWires, listOf("Формула для бензина с выходом 0–5 В. Сверьте с инструкцией к своему AEM и поправьте значения при 0 В и 5 В ниже, если там другие."),
            a = 7.3125f, b = 2.375f,
        ),
        BoxPreset(
            "afr_any", "ШДК другой, 0–5 В", "Впишите, что он выдаёт при 0 В и при 5 В", BoxKind.VOLT, "AFR", 1, "afr", 10f, 20f,
            voltWires, listOf("Значения при 0 В и 5 В есть в инструкции к контроллеру, раздел про аналоговый выход."),
            a = 10f, b = 2f,
        ),
        BoxPreset(
            "egt_k", "Температура выхлопа", "Термопара K и модуль MAX31855", BoxKind.TC, "°C", 0, "egt", 0f, 1000f,
            listOf(
                BoxWire("VCC модуля", "5V", WIRE_RED), BoxWire("GND модуля", "GND", WIRE_BLACK),
                BoxWire("SCK (CLK) модуля", "SCK", WIRE_ORANGE), BoxWire("CS модуля", "CS", WIRE_BLUE), BoxWire("SO (DO) модуля", "SO", WIRE_GREEN),
            ), listOf(
                "Термопару в коллектор до турбины, ввариваемая бобышка 1/8 NPT. Провода термопары не наращивайте обычным медным проводом.",
                "Плюс и минус термопары в клеммы T+ и T− модуля, перепутаете, будет показывать меньше при нагреве.",
                "Модуль MAX6675 не подойдёт, нужен именно MAX31855.",
            ), warnAbove = 900f,
        ),
        BoxPreset(
            "flex", "Датчик этанола (flex fuel)", "GM / Continental, 50–150 Гц = 0–100 % этанола", BoxKind.FREQ, "% E", 0, "", 0f, 100f,
            listOf(
                BoxWire("Питание датчика +12 В", "12V", WIRE_PURPLE), BoxWire("Масса датчика", "GND", WIRE_BLACK),
                BoxWire("Сигнал датчика", "SIG", WIRE_YELLOW),
            ), listOf(
                "Датчику нужно 12 В: возьмите их через предохранитель 5 А с цепи, где есть плюс при включённом зажигании.",
                "Сигнал это открытый коллектор: нужен резистор 3,3 кОм от сигнального пина к 5V, внутренней подтяжки платы (20–50 кОм) мало.",
            ), a = -50f, b = 1f, pullUp = "3,3 кОм",
        ),
        BoxPreset(
            "pot", "Потенциометр (крутилка)", "Переменный резистор 10 кОм на панели: 0–100 %", BoxKind.RATIO, "%", 0, "", 0f, 100f,
            listOf(
                BoxWire("Крайний вывод 1", "5V", WIRE_RED), BoxWire("Крайний вывод 2", "GND", WIRE_BLACK), BoxWire("Средний вывод (движок)", "SIG", WIRE_YELLOW),
            ), listOf(
                "Подойдёт любой потенциометр 5–50 кОм, удобнее 10 кОм линейный (буква B на корпусе).",
                "Крутится не в ту сторону? Поменяйте местами крайние выводы.",
                "Значение в процентах можно использовать в правилах выходов или как уровень вентилятора, насоса, подсветки.",
            ), a = 0f, b = 20f,
        ),
        BoxPreset(
            "volt_any", "Свой датчик 0–5 В", "Любой датчик с выходом по напряжению", BoxKind.VOLT, "", 2, "", 0f, 5f,
            voltWires, listOf("Впишите, что датчик показывает при 0 В и при 5 В, единицы и название. Без этого покажет просто вольты."),
            a = 0f, b = 1f,
        ),
    )

    /** Kept from the built-in list when the catalogue is there: the do-it-yourself ones it has no match for. */
    private val generic = setOf("afr_any", "volt_any", "pot")

    /** What the gallery offers: the catalogue (if loaded) plus the generic do-it-yourself presets. */
    val all: List<BoxPreset> get() = catalog.ifEmpty { return builtIn } + builtIn.filter { it.id in generic && catalog.none { c -> c.id == it.id } }

    fun of(id: String) = all.firstOrNull { it.id == id } ?: builtIn.firstOrNull { it.id == id }

    /** The first of [ids] that exists (catalogue ids first, then the built-in ones). */
    fun first(vararg ids: String) = ids.firstNotNullOfOrNull { of(it) }

    /** The presets that fit [input]: the thermocouple input takes only the thermocouple, the frequency one only pulses. */
    fun forInput(input: String) = all.filter {
        when {
            input.startsWith("TC") -> it.kind == BoxKind.TC
            input == "F0" -> it.kind == BoxKind.FREQ
            else -> it.kind != BoxKind.TC && it.kind != BoxKind.FREQ
        }
    }
}

/** What the driver put on one input: a preset and the numbers they changed. */
data class BoxSlot(
    val input: String,
    val preset: String,
    val name: String,
    val unit: String,
    val role: String,
    val pMax: Float,
    val a: Float,
    val b: Float,
    val warnAbove: Float?,
    val warnBelow: Float?,
) {
    val p: BoxPreset get() = BoxPresets.of(preset) ?: BoxPresets.all.last()

    fun toJson(): JSONObject = JSONObject().put("in", input).put("preset", preset).put("name", name).put("unit", unit).put("role", role)
        .put("pMax", pMax.toDouble()).put("a", a.toDouble()).put("b", b.toDouble())
        .apply { warnAbove?.let { put("hi", it.toDouble()) }; warnBelow?.let { put("lo", it.toDouble()) } }

    companion object {
        fun of(input: String, p: BoxPreset) = BoxSlot(input, p.id, p.title, p.unit, p.role, p.pMax, p.a, p.b, p.warnAbove, p.warnBelow)

        fun fromJson(o: JSONObject) = BoxSlot(
            o.getString("in"), o.getString("preset"), o.optString("name"), o.optString("unit"), o.optString("role"),
            o.optDouble("pMax", 10.0).toFloat(), o.optDouble("a", 0.0).toFloat(), o.optDouble("b", 1.0).toFloat(),
            if (o.has("hi")) o.getDouble("hi").toFloat() else null, if (o.has("lo")) o.getDouble("lo").toFloat() else null,
        )
    }
}

/** A frame from the box: the counts of the analog inputs (0…1023), its supply in mV, the thermocouple, the frequency. */
data class BoxFrame(val timeMs: Long, val vccMv: Int, val raw: Map<String, Float>, val tc: Float?, val tcFault: Int, val hz: Float?,
                    /** The outputs as the box reports them ("Q=10w-"), and its failsafe. */
                    val outs: String = "", val failsafe: Boolean = false,
                    /** Every thermocouple the frame has (TC0…TC3): its °C, or null with its fault in [tcFaults]. */
                    val tcs: Map<String, Float?> = emptyMap(), val tcFaults: Map<String, Int> = emptyMap())

/** What an input shows now: the value, or why there is none ("обрыв"), and the raw reading under it. */
data class BoxReading(val value: Float?, val problem: String?, val rawText: String)

data class BoxInfo(val board: String, val id: String, val rate: Int, val inputs: List<String>, val via: String, val fw: Int = 0,
                   /** The display the box found on I2C: "oled", "lcd" or "none". */
                   val disp: String = "none")

/** The firmware version shipped in the app (FW_VERSION in hardware/sensorbox/sensorbox.ino; a unit test keeps them equal). */
const val BOX_FIRMWARE = 5

object SensorBox {
    /** The configured inputs (saved), the board the driver said they have. */
    val slots = MutableStateFlow<List<BoxSlot>>(emptyList())
    val board = MutableStateFlow(BoxBoard.NANO)
    val enabled = MutableStateFlow(false)
    /** The box on the cable, null while none answers. */
    val info = MutableStateFlow<BoxInfo?>(null)
    val readings = MutableStateFlow<Map<String, BoxReading>>(emptyMap())
    val status = MutableStateFlow("")
    /** The CS pin of each extra thermocouple (TC1…TC3), picked from the free output pins. */
    val tcCs = MutableStateFlow<Map<String, String>>(emptyMap())

    fun load() {
        val o = runCatching { JSONObject(Repo.settings.sensorBox) }.getOrNull() ?: JSONObject()
        enabled.value = o.optBoolean("on", false)
        board.value = BoxBoard.of(o.optString("board"))
        slots.value = runCatching {
            val a = o.getJSONArray("slots")
            List(a.length()) { BoxSlot.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        tcCs.value = o.optJSONObject("tcCs")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap()
    }

    private fun save() {
        runCatching {
            Repo.settings.sensorBox = JSONObject().put("on", enabled.value).put("board", board.value.id)
                .put("slots", JSONArray().also { a -> slots.value.forEach { a.put(it.toJson()) } })
                .put("tcCs", JSONObject(tcCs.value as Map<*, *>)).toString()
        } // (no settings in the unit tests)
    }

    fun setEnabled(on: Boolean) { enabled.value = on; if (!on) { info.value = null; readings.value = emptyMap() }; save() }
    fun setBoard(b: BoxBoard) { board.value = b; slots.value = slots.value.filter { it.input in b.inputs }; save() }
    fun put(s: BoxSlot) {
        slots.value = slots.value.filter { it.input != s.input } + s
        // one more thermocouple: its CS on the first free output pin
        if (s.input in BoxBoard.THERMOCOUPLES && s.input != "TC0" && tcCs.value[s.input].isNullOrEmpty()) {
            freeOutPins(board.value).firstOrNull()?.let { tcCs.value = tcCs.value + (s.input to it) }
        }
        save()
    }

    fun remove(input: String) {
        slots.value = slots.value.filter { it.input != input }
        readings.value = readings.value - input
        tcCs.value = tcCs.value - input
        save()
    }

    fun setTcCs(input: String, pin: String) { tcCs.value = tcCs.value + (input to pin); save() }

    /**
     * Who holds each pin of the board besides the sensor inputs: the outputs, the displays (I2C, a TM1637's two, a
     * colour screen's CS and DC) and the extra thermocouples' CS. One table for every picker, so no two things are
     * ever offered the same pin. [except] leaves out the thing being edited ("out:2", "disp:1", "tc:TC1").
     */
    fun pinOwners(b: BoxBoard, except: String = ""): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        fun put(pin: String, who: String, key: String) { if (pin.isNotEmpty() && key != except) m.putIfAbsent(pin, who) }
        Relays.channels.value.forEach { put(it.pin, "выход ${it.ch}: ${it.name}", "out:${it.ch}") }
        Displays.list.value.forEach { d ->
            val name = "дисплей " + d.kind.title.substringBefore(" с ").substringBefore(" 0,96")
            when (d.kind.bus) {
                DispBus.I2C -> { put(b.sda, "$name (SDA)", "disp:${d.id}"); put(b.scl, "$name (SCL)", "disp:${d.id}") }
                DispBus.TM -> { put(d.clk, "$name (CLK)", "disp:${d.id}"); put(d.dio, "$name (DIO)", "disp:${d.id}") }
                DispBus.SPI -> { put(d.cs, "$name (CS)", "disp:${d.id}"); put(d.dc, "$name (DC)", "disp:${d.id}") }
            }
        }
        tcCs.value.forEach { (input, pin) -> put(pin, "термопара $input (CS)", "tc:$input") }
        return m
    }

    /**
     * Why [input] cannot take a sensor now, or null when it can: taken by another sensor, an Uno's A4/A5 under an I2C
     * display, an extra thermocouple with no free pin left for its CS.
     */
    fun inputBlocked(b: BoxBoard, input: String): String? = when {
        slots.value.any { it.input == input } -> "уже занят датчиком «${slots.value.first { it.input == input }.name}»"
        b == BoxBoard.UNO && input in setOf("A4", "A5") && Displays.list.value.any { it.kind.i2c } ->
            "на Uno это SDA/SCL, их занимает дисплей на I2C"
        input in setOf("TC1", "TC2", "TC3") && tcCs.value[input].isNullOrEmpty() && freeOutPins(b).isEmpty() ->
            "нет свободного пина для CS модуля"
        // the box on the cable said which inputs it has: without the module there are no A4..A7
        b.onAds(input) && info.value?.let { it.via.startsWith("USB") && input !in it.inputs } == true ->
            "нужен модуль ADS1115 на I2C (SDA ${b.sda}, SCL ${b.scl}), плата его не нашла"
        else -> null
    }

    /** Output pins nothing uses yet: no relay, no display, no thermocouple's CS (but [except]'s own). */
    fun freeOutPins(b: BoxBoard, except: String = ""): List<String> {
        val taken = pinOwners(b, except).keys
        return b.outPins.filter { it !in taken }
    }

    /** An example set for a first look: oil temperature and pressure, a wideband, exhaust gas. */
    fun fillExample() {
        val b = board.value
        val l = listOfNotNull(
            b.analog.getOrNull(0)?.let { BoxSlot.of(it, BoxPresets.first("oil_t_bosch_m12h", "oil_t_bosch")!!) },
            b.analog.getOrNull(1)?.let { BoxSlot.of(it, BoxPresets.first("oil_p_10")!!) },
            b.analog.getOrNull(2)?.let { BoxSlot.of(it, BoxPresets.first("afr_lc2")!!) },
            BoxSlot.of("TC0", BoxPresets.first("egt_k31855", "egt_k")!!),
        )
        slots.value = slots.value.filter { s -> l.none { it.input == s.input } } + l
        save()
    }

    fun slot(input: String) = slots.value.firstOrNull { it.input == input }

    /** The «Датчик N» home card showing [input] (and switched on), or null. */
    fun homeCard(input: String): Int? {
        val tiles = HomeLayout.parse(Repo.settings.homeTiles)
        return (1..6).firstOrNull { k -> Repo.settings.slot(k) in setOf("box_$input", "box:$input") && tiles.any { it.id == "s$k" && it.on } }
    }

    /** Puts [input] on a free «Датчик N» home card and switches it on; N, or null when all six are taken. */
    fun showOnHome(input: String): Int? {
        val s = Repo.settings
        val tiles = HomeLayout.parse(s.homeTiles)
        val k = (1..6).firstOrNull { s.slot(it) in setOf("box_$input", "box:$input") }
            ?: (1..6).firstOrNull { k -> s.slot(k).isEmpty() && tiles.none { it.id == "s$k" && it.on } } ?: return null
        s.setSlot(k, "box_$input")
        s.homeTiles = HomeLayout.board.format(tiles.map { if (it.id == "s$k") it.copy(on = true) else it }, HomeLayout.board.isFree(s.homeTiles))
        return k
    }

    /**
     * The value of the input with [role], for the engine guard; with several (an EGT per cylinder), the highest.
     * Without one on the box, an own CAN channel with that role stands in (a third-party CAN sensor board, an EGT in
     * a Link's own stream), then a sports ECU's stream (CanEcu).
     */
    fun role(role: String): Float? = roleWithUnit(role)?.first

    /** A pressure role ([BoxRoles.PRESSURE]) in bar, whatever unit its sensor or CAN channel gives. */
    fun roleBar(role: String): Float? = roleWithUnit(role)?.let { Derived.bar(it.first, it.second) }

    /** [role]'s value and the unit it is in, from the first source that has it (see [role]). */
    fun roleWithUnit(role: String): Pair<Float, String>? {
        val box = if (!enabled.value && !Repo.demoOn.value) emptyList()
            else slots.value.filter { it.role == role }.mapNotNull { s -> readings.value[s.input]?.value?.let { it to s.unit } }
        return pickRole(box, CanChannels.roleValues(role)) ?: CanEcu.role(role)?.let { it to CanEcu.roleUnit(role) }
            ?: kg.forester.sg.ssm.Multitronics.role(role)?.let { it to kg.forester.sg.ssm.Multitronics.roleUnit(role) }
    }

    /** Some input or CAN channel is set to [role] (it may have no value right now). */
    fun hasRole(role: String): Boolean =
        ((enabled.value || Repo.demoOn.value) && slots.value.any { it.role == role }) || CanChannels.list().any { it.role == role }

    /** The first source that has a value: the box before the CAN channels; of several in one (an EGT per cylinder), the highest. */
    fun pickRole(vararg sources: List<Pair<Float, String>>): Pair<Float, String>? =
        sources.firstNotNullOfOrNull { l -> l.filter { !it.first.isNaN() }.maxByOrNull { it.first } }

    /** Values by indicator id ("box_A0"), for the home cards and the bottom bar. */
    fun values(): Map<String, Double> = readings.value.mapNotNull { (k, r) -> r.value?.let { "box_$k" to it.toDouble() } }.toMap()

    fun apply(f: BoxFrame) {
        readings.value = slots.value.associate { it.input to read(it, f) }
        Relays.boxStates.value = f.outs
        Relays.boxFailsafe.value = f.failsafe
    }

    /** [s]'s value in frame [f]. */
    fun read(s: BoxSlot, f: BoxFrame): BoxReading {
        val p = s.p
        fun num(v: Float, d: Int) = "%.${d}f".format(java.util.Locale("ru"), v)
        return when (p.kind) {
            BoxKind.TC -> {
                val t = if (s.input == "TC0") f.tc ?: f.tcs["TC0"] else f.tcs[s.input]
                val fault = if (s.input == "TC0") f.tcFault else f.tcFaults[s.input] ?: 0
                when {
                    fault and 8 != 0 -> BoxReading(null, "нет модуля MAX31855", "на пине CS ${BoxBoard.of(board.value.id).pinOf(s.input)} никто не отвечает")
                    fault and 1 != 0 -> BoxReading(null, "обрыв термопары", "модуль сообщает обрыв")
                    fault != 0 -> BoxReading(null, "замыкание термопары", "модуль сообщает замыкание")
                    t == null -> BoxReading(null, "нет модуля MAX31855", if (s.input == "TC0") "плата не нашла модуль при включении" else "прошивке платы не задан пин CS: обновите её")
                    else -> BoxReading(t, null, "термопара ${num(t, 1)} °C")
                }
            }
            BoxKind.FREQ -> {
                val hz = f.hz ?: return BoxReading(null, "нет входа", "")
                if (hz < 1f) BoxReading(null, "нет сигнала", "0 Гц") else BoxReading(s.a + s.b * hz, null, "${num(hz, 1)} Гц")
            }
            else -> {
                val raw = f.raw[s.input] ?: return BoxReading(null, "нет такого входа", "")
                val x = raw / 1023f
                val vcc = if (f.vccMv in 4000..5600) f.vccMv / 1000f else 5f
                val u = x * vcc
                val rawText = "${num(u, 2)} В"
                when (p.kind) {
                    BoxKind.PRESSURE -> when {
                        x < 0.05f -> BoxReading(null, "обрыв: нет сигнала", rawText)
                        x > 0.97f -> BoxReading(null, "замыкание на 5 В", rawText)
                        else -> BoxReading(pressure(x, s.pMax), null, rawText)
                    }
                    BoxKind.NTC -> when {
                        x > 0.985f -> BoxReading(null, "обрыв: датчик не подключён", rawText)
                        x < 0.01f -> BoxReading(null, "замыкание на массу", rawText)
                        else -> {
                            val ohm = p.pull * x / (1 - x)
                            BoxReading(ntc(ohm, p.ntc), null, "$rawText · ${num(ohm, 0)} Ом")
                        }
                    }
                    BoxKind.RES -> when {
                        x < 0.01f -> BoxReading(null, "замыкание на массу", rawText)
                        x > 0.6f -> BoxReading(null, "обрыв: датчик не подключён", rawText)
                        else -> {
                            val ohm = p.pull * x / (1 - x)
                            BoxReading(rising(ohm, p.res), null, "$rawText · ${num(ohm, 0)} Ом")
                        }
                    }
                    // the sensor and the ADC share the box's 5 V: the ratio is what counts, not the volts
                    BoxKind.RATIO -> BoxReading(s.a + s.b * x * 5f, null, rawText)
                    else -> BoxReading(s.a + s.b * u, null, rawText)
                }
            }
        }
    }

    /** 0.5 V (x 0.1) is zero, 4.5 V (x 0.9) the full scale. */
    fun pressure(x: Float, pMax: Float) = ((x - 0.1f) / 0.8f * pMax).coerceAtLeast(0f)

    /** °C for [ohm] from a table, interpolated on ln R (an NTC is near exponential). */
    fun ntc(ohm: Float, table: List<Pair<Float, Float>>): Float {
        if (ohm >= table.first().second) return table.first().first
        if (ohm <= table.last().second) return table.last().first
        for (i in 0 until table.size - 1) {
            val (t0, r0) = table[i]
            val (t1, r1) = table[i + 1]
            if (ohm <= r0 && ohm >= r1) return t0 + (t1 - t0) * ((ln(r0) - ln(ohm)) / (ln(r0) - ln(r1))).toFloat()
        }
        return table.last().first
    }

    /** A value for [ohm] from a [value, ohm] table with the resistance rising (a VDO pressure sender), interpolated. */
    fun rising(ohm: Float, table: List<Pair<Float, Float>>): Float {
        if (table.isEmpty()) return 0f
        if (ohm <= table.first().second) return table.first().first
        if (ohm >= table.last().second) return table.last().first
        for (i in 0 until table.size - 1) {
            val (v0, r0) = table[i]
            val (v1, r1) = table[i + 1]
            if (ohm in r0..r1) return v0 + (v1 - v0) * (ohm - r0) / (r1 - r0)
        }
        return table.last().first
    }

    /** The counts an input would show for [value]: the demo box drives the same conversion backwards. */
    fun rawFor(s: BoxSlot, value: Float, vcc: Float = 5f): Float {
        val p = s.p
        val x = when (p.kind) {
            BoxKind.PRESSURE -> 0.1f + 0.8f * value / s.pMax
            BoxKind.NTC -> {
                val t = p.ntc
                val v = value.coerceIn(t.first().first, t.last().first)
                val i = t.indexOfLast { it.first <= v }.coerceAtMost(t.size - 2)
                val (t0, r0) = t[i]
                val (t1, r1) = t[i + 1]
                val ohm = Math.exp(ln(r0.toDouble()) + (ln(r1.toDouble()) - ln(r0.toDouble())) * (v - t0) / (t1 - t0)).toFloat()
                ohm / (ohm + p.pull)
            }
            BoxKind.RES -> {
                val t = p.res
                val v = value.coerceIn(t.first().first, t.last().first)
                val i = t.indexOfLast { it.first <= v }.coerceAtMost(t.size - 2)
                val (v0, r0) = t[i]
                val (v1, r1) = t[i + 1]
                val ohm = r0 + (r1 - r0) * (v - v0) / (v1 - v0)
                ohm / (ohm + p.pull)
            }
            BoxKind.RATIO -> (value - s.a) / s.b / 5f
            else -> (value - s.a) / s.b / vcc
        }
        return (x.coerceIn(0f, 1f) * 1023f)
    }

    // --- the protocol (hardware/sensorbox/README.md)

    /** "I SENSORBOX 1 board=nano id=1A2B rate=20 ch=A0,A1,TC0,F0" */
    fun parseInfo(line: String, via: String): BoxInfo? {
        if (!line.startsWith("I SENSORBOX")) return null
        val kv = line.split(' ').mapNotNull { w -> w.indexOf('=').takeIf { it > 0 }?.let { w.substring(0, it) to w.substring(it + 1) } }.toMap()
        return BoxInfo(kv["board"] ?: "?", kv["id"] ?: "?", kv["rate"]?.toIntOrNull() ?: 0, kv["ch"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(), via,
            kv["fw"]?.toIntOrNull() ?: 1, kv["disp"] ?: "none")
    }

    /** "D t=81234 vcc=4987 A0=512.3 A1=88.0 TC0=745.25 F0=0.0", "TC0=!1" for a thermocouple fault. */
    fun parseFrame(line: String): BoxFrame? {
        if (!line.startsWith("D ")) return null
        var t = 0L
        var vcc = 0
        var tc: Float? = null
        var fault = 0
        val tcs = HashMap<String, Float?>()
        val faults = HashMap<String, Int>()
        var hz: Float? = null
        var outs = ""
        var fs = false
        val raw = HashMap<String, Float>()
        for (w in line.substring(2).split(' ')) {
            val i = w.indexOf('=')
            if (i <= 0) continue
            val k = w.substring(0, i)
            val v = w.substring(i + 1)
            when {
                k == "t" -> t = v.toLongOrNull() ?: 0L
                k == "vcc" -> vcc = v.toIntOrNull() ?: 0
                k.length == 3 && k.startsWith("TC") -> {
                    val bad = if (v.startsWith("!")) v.drop(1).toIntOrNull() ?: 1 else 0
                    val t = if (bad == 0) v.toFloatOrNull() else null
                    tcs[k] = t
                    if (bad != 0) faults[k] = bad
                    if (k == "TC0") { tc = t; fault = bad }
                }
                k == "F0" -> hz = v.toFloatOrNull()
                k == "Q" -> outs = v
                k == "fs" -> fs = v == "1"
                k.startsWith("A") -> v.toFloatOrNull()?.let { raw[k] = it }
            }
        }
        return BoxFrame(t, vcc, raw, tc, fault, hz, outs, fs, tcs, faults)
    }
}

/** A text field of the catalogue: absent or JSON null is "" (optString gives the word "null" for the latter). */
private fun JSONObject.text(k: String): String = if (isNull(k)) "" else optString(k)
