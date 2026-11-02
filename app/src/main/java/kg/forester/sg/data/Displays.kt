package kg.forester.sg.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * Displays on the SensorBox (firmware 5+): an OLED or a text LCD on I2C, a 4-digit TM1637 on two pins, a colour
 * OLED or IPS screen on SPI.
 * The app decides what they show and sends the finished lines; the box only draws them.
 */

/** How a display hangs on the box: I2C (SDA/SCL), a TM1637's two pins, SPI (SCK/MOSI of the board, its own CS and DC). */
enum class DispBus { I2C, TM, SPI }

/**
 * Every display the box can drive. [w] x [h] are pixels (characters on a text LCD), [code] what the firmware is told
 * (Y O for an OLED's controller, Y C for a colour one), [module] its 3D model and wiring terminals, [cs] whether the
 * module has a CS pin (the common 1.3" ST7789 has none).
 */
enum class DispKind(val title: String, val sub: String, val module: String, val bus: DispBus, val w: Int, val h: Int, val code: Char, val cs: Boolean = true) {
    OLED("OLED 0,96\" 128×64", "SSD1306, 4 провода (I2C). Чёткий, видно днём, есть русские буквы", "oled", DispBus.I2C, 128, 64, 'S'),
    OLED_13("OLED 1,3\" 128×64", "SH1106, 4 провода (I2C). Крупнее 0,96 при той же чёткости", "oled13", DispBus.I2C, 128, 64, 'H'),
    OLED_242("OLED 2,42\" 128×64", "SSD1309, большой: видно с водительского места. I2C, вывод RES к RST платы", "oled242", DispBus.I2C, 128, 64, 'S'),
    OLED_091("OLED 0,91\" 128×32", "SSD1306, узкая полоска: одно-два значения", "oled091", DispBus.I2C, 128, 32, 'S'),
    LCD1602("LCD 1602 с переходником I2C", "16 знаков × 2 строки, синяя подсветка, только латиница", "lcd1602", DispBus.I2C, 16, 2, 'L'),
    LCD2004("LCD 2004 с переходником I2C", "20 знаков × 4 строки, только латиница", "lcd2004", DispBus.I2C, 20, 4, 'L'),
    TM1637("Цифровой TM1637", "4 яркие цифры, одно значение: давление, температура", "tm1637", DispBus.TM, 4, 1, 'T'),
    OLED_RGB("Цветной OLED 1,5\" 128×128", "SSD1351 на SPI: цветные цифры и полосы на чёрном", "oled151", DispBus.SPI, 128, 128, 'S'),
    IPS_240("Цветной IPS 1,3\" 240×240", "ST7789 на SPI: крупные цветные цифры, яркий", "ips13", DispBus.SPI, 240, 240, '7', cs = false),
    IPS_320("Цветной IPS 2\" 320×240", "ST7789 на SPI: четыре значения с полосами", "ips20", DispBus.SPI, 320, 240, '8'),
    ;

    val i2c: Boolean get() = bus == DispBus.I2C
    val lcd: Boolean get() = code == 'L'
    /** A monochrome OLED: drawn line by line in pages of 8 pixels. */
    val oled: Boolean get() = i2c && !lcd
    val colour: Boolean get() = bus == DispBus.SPI
    /** Drawn with the box's own font (Russian capitals, °): OLEDs and colour screens; a text LCD has its ROM. */
    val ownFont: Boolean get() = oled || colour
}

/** How an OLED or a colour screen lays its values out. */
enum class DispLayout(val title: String, val items: Int) { ONE("Одно крупно", 1), TWO("Два значения", 2), FOUR("Четыре строки", 4) }

data class DispCfg(
    val id: Int,
    val kind: DispKind,
    val layout: DispLayout = DispLayout.TWO,
    /** The values shown, by RuleSources id: "coolant", "box_A0", "out_1"… */
    val items: List<String> = listOf("coolant", "battery"),
    /** TM1637 only: its two pins. */
    val clk: String = "",
    val dio: String = "",
    /** TM1637 only: brightness 0..7. */
    val bright: Int = 4,
    /** A colour screen on SPI: its CS (empty when the module has none) and DC pins. */
    val cs: String = "",
    val dc: String = "",
) {
    /** How many values this display shows. */
    val slots: Int get() = when {
        kind.oled || kind.colour -> layout.items
        kind == DispKind.LCD1602 -> 2
        kind == DispKind.LCD2004 -> 4
        else -> 1
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("layout", layout.name)
        .put("items", JSONArray(items)).put("clk", clk).put("dio", dio).put("bright", bright).put("cs", cs).put("dc", dc)

    companion object {
        fun fromJson(o: JSONObject) = DispCfg(
            o.getInt("id"), runCatching { DispKind.valueOf(o.getString("kind")) }.getOrDefault(DispKind.OLED),
            runCatching { DispLayout.valueOf(o.getString("layout")) }.getOrDefault(DispLayout.TWO),
            o.optJSONArray("items")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            o.optString("clk"), o.optString("dio"), o.optInt("bright", 4), o.optString("cs"), o.optString("dc"),
        )
    }
}

/** One thing a colour screen draws: a box, or text by pixel; [key] tells what it replaces when it changes. */
sealed class ColOp {
    abstract val key: String
    data class Box(override val key: String, val x: Int, val y: Int, val w: Int, val h: Int, val c: Int) : ColOp()
    data class Txt(override val key: String, val x: Int, val y: Int, val s: Int, val fg: Int, val bg: Int, val text: String) : ColOp()
}

/** One line a display shows: the row (OLED page / LCD row), the size (OLED 1..3), the text as it will be drawn. */
data class DispLine(val row: Int, val scale: Int, val text: String)

object Displays {
    val list = MutableStateFlow<List<DispCfg>>(emptyList())

    fun load() {
        list.value = runCatching {
            val a = JSONArray(Repo.settings.displays)
            List(a.length()) { DispCfg.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save() { Repo.settings.displays = JSONArray().also { a -> list.value.forEach { a.put(it.toJson()) } }.toString() }

    fun put(d: DispCfg) { list.value = (list.value.filter { it.id != d.id } + d).sortedBy { it.id }; save() }
    fun remove(id: Int) { list.value = list.value.filter { it.id != id }; save() }

    /**
     * One display of each kind of hook-up: on I2C one OLED and one text LCD (their addresses differ, both can be on the
     * bus), one TM1637, one colour screen on SPI.
     */
    fun canAdd(k: DispKind) = list.value.none { it.kind.bus == k.bus && (!k.i2c || it.kind.lcd == k.lcd) }

    /**
     * Why [k] does not go with this box, or null when it does. A colour screen without CS listens to everything on SPI,
     * so it cannot share the bus with the thermocouple module.
     */
    fun unfit(k: DispKind, board: BoxBoard): String? = when {
        k.colour && !k.cs && SensorBox.slots.value.any { it.p.kind == BoxKind.TC } ->
            "Без вывода CS не уживётся с термопарой на той же шине SPI: возьмите модуль с CS (2\" 320×240 или цветной OLED)"
        k.i2c && i2cClash(board).isNotEmpty() ->
            "На Uno SDA и SCL это A4 и A5, а там датчик ${i2cClash(board).joinToString { "«${it.name}»" }}: перенесите его на другой вход"
        k.colour && freeOutPins(board).size < (if (k.cs) 2 else 1) -> "Не хватает свободных пинов выходов: нужно ${if (k.cs) 2 else 1}"
        k == DispKind.TM1637 && freeOutPins(board).size < 2 -> "Не хватает свободных пинов выходов: нужно 2"
        else -> null
    }

    /** The board's pins the displays take: SDA/SCL for one on I2C, CLK/DIO for a TM1637, CS/DC for a colour screen. */
    fun pins(board: BoxBoard, l: List<DispCfg> = list.value): Set<String> = l.flatMap {
        when (it.kind.bus) { DispBus.I2C -> listOf(board.sda, board.scl); DispBus.TM -> listOf(it.clk, it.dio); DispBus.SPI -> listOf(it.cs, it.dc) }
    }.filter { it.isNotEmpty() }.toSet()

    /** Output pins nothing uses yet: no relay, no display. */
    fun freeOutPins(board: BoxBoard, except: DispCfg? = null): List<String> =
        SensorBox.freeOutPins(board, except?.let { "disp:${it.id}" } ?: "")

    /** The sensors that lose their input to an I2C display: the Uno's A4/A5 are its SDA/SCL too (the Nano keeps them off the inputs). */
    fun i2cClash(board: BoxBoard): List<BoxSlot> {
        val shared = if (board == BoxBoard.UNO) setOf("A4", "A5") else emptySet()
        return SensorBox.slots.value.filter { it.input in shared }
    }

    fun new(k: DispKind, board: BoxBoard): DispCfg {
        val id = (list.value.maxOfOrNull { it.id } ?: 0) + 1
        val free = freeOutPins(board)
        return when (k) {
            DispKind.TM1637 -> DispCfg(id, k, items = listOf("box_${SensorBox.slots.value.firstOrNull { it.role == "oilP" }?.input ?: "A0"}"
                .takeIf { SensorBox.slots.value.isNotEmpty() } ?: "coolant"), clk = free.getOrElse(0) { "" }, dio = free.getOrElse(1) { "" })
            else -> if (k.colour) DispCfg(id, k, DispLayout.FOUR, defaultItems(4),
                cs = if (k.cs) free.getOrElse(0) { "" } else "", dc = free.getOrElse(if (k.cs) 1 else 0) { "" })
            else DispCfg(id, k, if (k.oled) DispLayout.TWO else DispLayout.FOUR, defaultItems(4))
        }
    }

    /** Oil from the box when it is there, else the ECU's coolant, then the rest that is usual. */
    private fun defaultItems(n: Int): List<String> {
        val box = SensorBox.slots.value
        val l = listOfNotNull(
            box.firstOrNull { it.role == "oil" }?.let { "box_${it.input}" } ?: "coolant",
            box.firstOrNull { it.role == "oilP" }?.let { "box_${it.input}" } ?: "battery",
            box.firstOrNull { it.role == "afr" }?.let { "box_${it.input}" } ?: "boost",
            "coolant", "battery", "intake",
        ).distinct()
        return l.take(n)
    }

    // ---- the text

    /** A source's short name for a display: «МАСЛО», «ВОДА»…, uppercase, at most [n] characters. */
    fun label(src: String, n: Int): String {
        val t = when (src) {
            "coolant" -> "АНТИФРИЗ"; "oil" -> "МАСЛО"; "intake" -> "ВПУСК"; "atf" -> "ATF"; "rpm" -> "ОБОРОТЫ"; "speed" -> "СКОРОСТЬ"
            "boost" -> "НАДДУВ"; "afr" -> "AFR"; "throttle" -> "ДРОССЕЛЬ"; "battery" -> "АКБ"
            else -> when {
                src.startsWith("box_") -> SensorBox.slots.value.firstOrNull { "box_${it.input}" == src }?.let { short(it.name) }
                src.startsWith("out_") -> Relays.channels.value.firstOrNull { "out_${it.ch}" == src }?.name
                else -> null
            } ?: RuleSources.of(src)?.title ?: src
        }
        return tr(t).uppercase(Locale("ru")).take(n).trimEnd()
    }

    /** A box sensor's name as a few words for a small screen: «Давление масла 0–10 бар» → «ДАВЛ МАСЛА». */
    fun short(name: String): String {
        val n = name.lowercase(Locale("ru"))
        val known = listOf(
            "температура масла" to "МАСЛО", "давление масла" to "ДАВЛ МАСЛА", "давление топлива" to "ДАВЛ ТОПЛ",
            "температура выхлопа" to "ВЫХЛОП", "доп. температура ож" to "ОЖ", "температура atf" to "ATF",
            "температура заднего редуктора" to "РЕДУКТОР", "воздух после интеркулера" to "ПОСЛЕ ИК", "воздух до интеркулера" to "ДО ИК",
            "воздух" to "ВОЗДУХ", "наддув" to "НАДДУВ", "шдк" to "AFR", "датчик этанола" to "ЭТАНОЛ", "ток генератора" to "ТОК ГЕН",
            "ток акб" to "ТОК АКБ", "давление в тормозах" to "ТОРМОЗА", "потенциометр" to "РУЧКА",
        )
        known.firstOrNull { n.startsWith(it.first) }?.let { return it.second }
        // a name of one's own: up to a comma, a bracket or the first figure
        val cut = name.indexOfFirst { it == ',' || it == '(' || it.isDigit() }.let { if (it > 0) name.substring(0, it) else name }
        return cut.trim().ifEmpty { name }
    }

    /** [v] as a display shows it: the number, then the unit without spaces («95°C», «3.4БАР»). */
    fun value(src: String, v: Float?, withUnit: Boolean = true): String {
        val s = RuleSources.of(src) ?: return v?.let { "%.1f".format(Locale.US, it) } ?: "--"
        if (v == null) return "--"
        if (s.bool) return if (v >= 0.5f) "ВКЛ" else "ВЫКЛ"
        val miles = src == "speed"
        val num = "%.${s.decimals}f".format(Locale.US, if (miles) DistanceUnits.speed(v) else v)
        // AFR is already the name of the value: «14.7», not «14.7AFR»
        val unit = (if (miles) DistanceUnits.speedUnit else s.unit).replace(" ", "").uppercase(Locale("ru")).takeUnless { it == "AFR" } ?: ""
        return if (withUnit) num + unit else num
    }

    /** [t] in the middle of [places] characters (cut to them). */
    fun centre(t: String, places: Int) = t.take(places).let { v -> " ".repeat((places - v.length) / 2) + v }

    /** [a] on the left, [b] on the right, [w] characters in all. */
    fun spread(a: String, b: String, w: Int): String {
        val right = b.take(w)
        val left = a.take((w - right.length - 1).coerceAtLeast(0))
        return left + " ".repeat((w - left.length - right.length).coerceAtLeast(1)) + right
    }

    /** What [d] shows for these values (a colour screen: see [colourOps]). */
    fun lines(d: DispCfg, value: (String) -> Float?): List<DispLine> {
        fun item(i: Int) = d.items.getOrNull(i)
        fun big(s: String, places: Int) = centre(value(s, value(s)), places)
        return when {
            // 0.91": four pages of 8 pixels
            d.kind.oled && d.kind.h == 32 -> when (d.layout) {
                DispLayout.ONE -> item(0)?.let { s -> listOf(DispLine(0, 1, label(s, 21)), DispLine(1, 3, big(s, 7))) } ?: emptyList()
                DispLayout.TWO -> (0..1).mapNotNull { i -> item(i)?.let { s -> DispLine(i * 2, 2, spread(label(s, 4), value(s, value(s)), 10)) } }
                DispLayout.FOUR -> (0..3).mapNotNull { i -> item(i)?.let { s -> DispLine(i, 1, spread(label(s, 12), value(s, value(s)), 21)) } }
            }
            d.kind.oled -> when (d.layout) {
                // the big number in the middle: 7 places at this size
                DispLayout.ONE -> item(0)?.let { s -> listOf(DispLine(0, 1, label(s, 21)), DispLine(2, 3, big(s, 7)), DispLine(6, 1, "")) } ?: emptyList()
                DispLayout.TWO -> (0..1).flatMap { i ->
                    val s = item(i) ?: return@flatMap emptyList()
                    listOf(DispLine(i * 4, 1, label(s, 21)), DispLine(i * 4 + 1, 2, value(s, value(s))))
                }
                DispLayout.FOUR -> (0..3).mapNotNull { i -> item(i)?.let { s -> DispLine(i * 2, 1, spread(label(s, 12), value(s, value(s)), 21)) } }
            }
            d.kind.lcd -> (0 until d.slots).mapNotNull { i -> item(i)?.let { s -> DispLine(i, 1, spread(label(s, d.kind.w - 7), value(s, value(s)), d.kind.w)) } }
            d.kind == DispKind.TM1637 -> item(0)?.let { s -> listOf(DispLine(0, 1, tmText(s, value(s)))) } ?: emptyList()
            else -> emptyList()
        }
    }

    // ---- a colour screen: the app places every box and text; the box only draws them (RGB565)

    const val BLACK = 0x0000
    const val WHITE = 0xFFFF
    const val GREY = 0x9CF3
    const val TRACK = 0x2945
    const val ACCENT = 0x2E7F
    const val GREEN = 0x3666
    const val RED = 0xF8A2

    /**
     * What a colour screen shows: per value its name, the number (red past the warning [warn] gives) and a bar of where
     * it stands on its scale. Texts are padded to fixed widths so a shorter number wipes the longer one before it.
     */
    fun colourOps(d: DispCfg, value: (String) -> Float?, warn: (String, Float) -> Boolean = { _, _ -> false }): List<ColOp> {
        val w = d.kind.w
        val h = d.kind.h
        val u = if (w >= 240) 2 else 1
        val ops = ArrayList<ColOp>()
        fun cols(s: Int) = (w - 8) / (6 * s)
        fun bar(key: String, src: String, v: Float?, x: Int, y: Int, bw: Int, bh: Int, hot: Boolean) {
            val r = RuleSources.of(src)
            val f = if (v == null || r == null || r.max <= r.min) 0f else ((v - r.min) / (r.max - r.min)).coerceIn(0f, 1f)
            val lit = (bw * f).toInt()
            ops += ColOp.Box("${key}a", x, y, lit, bh, if (hot) RED else ACCENT)
            ops += ColOp.Box("${key}b", x + lit, y, bw - lit, bh, TRACK)
        }
        fun item(i: Int) = d.items.getOrNull(i)?.takeIf { it.isNotEmpty() }
        when (d.layout) {
            DispLayout.ONE -> item(0)?.let { s ->
                val v = value(s)
                val hot = v != null && warn(s, v)
                val bs = if (w >= 240) 6 else 3
                val n = cols(bs)
                ops += ColOp.Txt("l0", 4, 4 * u, u, GREY, BLACK, label(s, cols(u)).padEnd(cols(u)))
                ops += ColOp.Txt("v0", (w - n * 6 * bs) / 2, h / 2 - 4 * bs, bs, if (hot) RED else WHITE, BLACK, centre(value(s, v), n).padEnd(n))
                bar("b0", s, v, 8, h - 10 * u, w - 16, 5 * u, hot)
            }
            DispLayout.TWO -> (0..1).forEach { i ->
                val s = item(i) ?: return@forEach
                val v = value(s)
                val hot = v != null && warn(s, v)
                val y0 = i * h / 2
                val bs = if (w >= 240) 4 else 2
                ops += ColOp.Txt("l$i", 4, y0 + 3 * u, u, GREY, BLACK, label(s, cols(u)).padEnd(cols(u)))
                ops += ColOp.Txt("v$i", 4, y0 + 12 * u, bs, if (hot) RED else WHITE, BLACK, value(s, v).take(cols(bs)).padEnd(cols(bs)))
                bar("b$i", s, v, 4, y0 + h / 2 - 8 * u, w - 8, 3 * u, hot)
            }
            DispLayout.FOUR -> (0..3).forEach { i ->
                val s = item(i) ?: return@forEach
                val v = value(s)
                val hot = v != null && warn(s, v)
                val y0 = i * h / 4
                val vs = if (w >= 240) 3 else 2
                val vw = 7 * 6 * vs
                val ls = u
                val ln = ((w - vw - 12) / (6 * ls)).coerceAtLeast(1)
                ops += ColOp.Txt("l$i", 4, y0 + (h / 4 - 8 * vs) / 2 + (8 * vs - 8 * ls) / 2, ls, GREY, BLACK, label(s, ln).padEnd(ln))
                ops += ColOp.Txt("v$i", w - 4 - vw, y0 + (h / 4 - 8 * vs) / 2 - u, vs, if (hot) RED else WHITE, BLACK, value(s, v).take(7).padStart(7))
                bar("b$i", s, v, 4, y0 + h / 4 - 3 * u - 1, w - 8, 2 * u, hot)
            }
        }
        return ops
    }

    /** «Проверить экран» on a colour screen: the primary colours in bands and the font in white, so a wrong order shows. */
    fun colourTest(k: DispKind): List<ColOp> {
        val w = k.w
        val h = k.h
        val u = if (w >= 240) 2 else 1
        val bands = listOf(0xF800, 0x07E0, 0x001F, 0xFFE0, 0x07FF, 0xF81F, 0xFFFF, 0x8410)
        return bands.mapIndexed { i, c -> ColOp.Box("t$i", i * w / bands.size, 0, w / bands.size + 1, h / 3, c) } + listOf(
            ColOp.Txt("tx", 4, h / 3 + 6 * u, u, WHITE, BLACK, "ПРОВЕРКА ЭКРАНА"),
            ColOp.Txt("ty", 4, h / 3 + 20 * u, 2 * u, GREEN, BLACK, "97~C 3.4"),
            ColOp.Txt("tz", 4, h / 3 + 42 * u, u, RED, BLACK, "АБВГД 0123456789"),
        )
    }

    /** [op] as the line the box takes. */
    fun wire(op: ColOp, k: DispKind): String = when (op) {
        is ColOp.Box -> "Q ${op.x} ${op.y} ${op.w} ${op.h} %X".format(op.c)
        is ColOp.Txt -> "T ${op.x} ${op.y} ${op.s} %X %X ".format(op.fg, op.bg) + wire(op.text, k)
    }

    /** Four characters for a TM1637: the number first, a °C sign where it fits; a dot does not take a place. */
    fun tmText(src: String, v: Float?): String {
        if (v == null) return "----"
        val s = RuleSources.of(src)
        if (s?.bool == true) return if (v >= 0.5f) " On" else "OFF"
        val temp = s?.unit == "°C"
        val digits = if (temp) 2 else 4
        var dec = (s?.decimals ?: 1)
        var t: String
        while (true) {
            t = "%.${dec}f".format(Locale.US, v)
            if (t.replace(".", "").length <= digits || dec == 0) break
            dec--
        }
        if (temp && t.replace(".", "").length <= 2) t = t.padStart(2) + "~C"
        return t.padStart(4 + if (t.contains('.')) 1 else 0)
    }

    // ---- to the box: OLED gets cp1251 capitals and '~' for °; an LCD's ROM has Latin only, so Russian is spelled out

    private const val RU = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    private val LAT = listOf("A", "B", "V", "G", "D", "E", "ZH", "Z", "I", "Y", "K", "L", "M", "N", "O", "P", "R", "S", "T", "U", "F", "H",
        "C", "CH", "SH", "SH", "", "Y", "", "E", "YU", "YA")

    /** [t] in the bytes the box draws (each char below 256, sent as ISO-8859-1). */
    fun wire(t: String, kind: DispKind): String = buildString {
        for (ch in t.uppercase(Locale("ru")).replace('Ё', 'Е')) {
            val i = RU.indexOf(ch)
            when {
                ch == '°' -> append(if (kind.ownFont) '~' else 'ß')  // the HD44780 ROM has ° at 0xDF
                ch == '–' || ch == '—' -> append('-')
                ch == '×' -> append('X')
                ch == '·' -> append('.')
                i >= 0 && kind.ownFont -> append((0xC0 + i).toChar())
                i >= 0 -> append(LAT[i])
                ch.code in 32..126 -> append(ch)
                else -> append('?')
            }
        }
    }

    /** The segments a TM1637 lights for [t], as the box draws them (bit 0 = a … bit 6 = g, bit 7 = the dot). */
    fun tmSegments(t: String): IntArray {
        val seg = IntArray(4)
        var n = 0
        for (ch in t) {
            if (n >= 4 && ch != '.') break
            if (ch == '.' && n > 0) { seg[n - 1] = seg[n - 1] or 0x80; continue }
            if (n < 4) seg[n++] = tmSeg(ch)
        }
        return seg
    }

    private fun tmSeg(c: Char): Int = when (c) {
        in '0'..'9' -> intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)[c - '0']
        '-' -> 0x40; '_' -> 0x08; '~' -> 0x63
        'A', 'a' -> 0x77; 'B', 'b' -> 0x7C; 'C' -> 0x39; 'c' -> 0x58; 'D', 'd' -> 0x5E; 'E', 'e' -> 0x79; 'F', 'f' -> 0x71; 'H' -> 0x76
        'h' -> 0x74; 'L', 'l' -> 0x38; 'n' -> 0x54; 'O' -> 0x3F; 'o' -> 0x5C; 'P', 'p' -> 0x73; 'r' -> 0x50; 't' -> 0x78; 'U' -> 0x3E; 'u' -> 0x1C
        else -> 0
    }

    /** How a display is wired, as a "sensor" for the wiring picture (its module's top view has these terminals). */
    fun preset(k: DispKind): BoxPreset {
        fun w(label: String, to: String, c: Long) = BoxWire("$label дисплея", to, c)
        val wires = when {
            k == DispKind.TM1637 -> listOf(w("CLK", "CLK", WIRE_YELLOW), w("DIO", "DIO", WIRE_GREEN), w("VCC", "5V", WIRE_RED), w("GND", "GND", WIRE_BLACK))
            k == DispKind.OLED_242 -> listOf(w("GND", "GND", WIRE_BLACK), w("VCC", "3V3", WIRE_RED), w("SCL", "SCL", WIRE_YELLOW),
                w("SDA", "SDA", WIRE_GREEN), w("RES", "RST", WIRE_ORANGE))
            k.i2c -> listOf(w("GND", "GND", WIRE_BLACK), w("VCC", "5V", WIRE_RED), w("SCL", "SCL", WIRE_YELLOW), w("SDA", "SDA", WIRE_GREEN))
            else -> listOfNotNull(w("GND", "GND", WIRE_BLACK), w("VCC", "5V", WIRE_RED), w(terminal(k, "SCK"), "SCK", WIRE_YELLOW),
                w(terminal(k, "MOSI"), "MOSI", WIRE_GREEN), w(terminal(k, "RST"), "RST", WIRE_ORANGE), w("DC", "DC", WIRE_BLUE),
                if (k.cs) w("CS", "LCS", 0xFFF472B6) else null)
        }
        val logic = "Логика у модуля 3,3 В: на каждый сигнальный провод от платы поставьте резистор 1 кОм последовательно " +
            "(или плату преобразователя уровней), иначе 5 В платы со временем убьют контроллер экрана."
        val tips = when (k) {
            DispKind.OLED, DispKind.OLED_091, DispKind.OLED_13 -> listOf(
                "Берите модуль на 4 провода (GND VCC SCL SDA), адрес 0x3C. На 7 проводов это SPI: в I2C его надо переключить перемычками.",
                "Порядок пинов у разных продавцов бывает GND VCC или VCC GND: смотрите подписи на самом модуле.",
                "Провода до дисплея короче 50 см: I2C не любит длинных линий. Нужно дальше, скрутите SDA и SCL с массой.",
            ) + if (k == DispKind.OLED_13) listOf("1,3\" почти всегда на SH1106. Если по краю полоса мусора или всё сдвинуто на 2 точки, у вас SSD1306: выберите 0,96\".") else emptyList()
            DispKind.OLED_242 -> listOf(
                "Модуль 2,42\" обычно приходит в режиме SPI: для I2C на обороте перепаяйте перемычки (резисторы) по надписям IIC, а выводы DC и CS соедините с GND.",
                "Вывод RES обязателен: без сброса SSD1309 не запускается. Его провод к пину RST платы, экран сбросится вместе с ней.",
                "Питание 3,3 В: от вывода 3V3 платы" + " (у Pro Micro его нет: тогда VCC модуля к VCC платы, если на модуле есть стабилизатор).",
            )
            DispKind.LCD1602, DispKind.LCD2004 -> listOf(
                "Нужен LCD с напаянным переходником I2C (чёрная плата PCF8574 сзади). Без него проводов 16, плата так не умеет.",
                "На схеме модуль показан сзади: провода идут на 4 пина переходника GND VCC SDA SCL у края платы.",
                "Не видно букв: покрутите синий потенциометр на переходнике, это контраст.",
                "Экран показывает только латиницу: русские подписи плата пишет транслитом (MASLO, AKB).",
            )
            DispKind.TM1637 -> listOf(
                "Подойдёт любой модуль TM1637 на 4 цифры, с двоеточием или с точками.",
                "CLK и DIO можно на любые свободные пины выходов: выбираются выше, в «Пины платы».",
            )
            DispKind.OLED_RGB -> listOf("Модуль SSD1351 с 7 выводами (Waveshare 1,5\" RGB OLED и подобные). Питание 3,3–5 В.", logic,
                "SCK общий с модулем термопары, у каждого свой CS: так они делят одну шину.")
            DispKind.IPS_240 -> listOf("У модуля 1,3\" 240×240 нет вывода CS: тогда на шине SPI он должен быть один, без модуля термопары.", logic,
                "Вывод BLK (подсветка) оставьте свободным: она включена всегда.")
            DispKind.IPS_320 -> listOf("Модуль 2\" 320×240 на ST7789 с выводом CS.", logic, "Вывод BL (подсветка) к 5 В через резистор 100 Ом или оставьте свободным, если на модуле он подтянут.")
        }
        return BoxPreset("disp_${k.module}", k.title, "", BoxKind.OUT, "", 0, "", 0f, 1f, wires, tips)
    }

    /** The name printed on [k]'s module for a wire to the board's [to]. */
    fun terminal(k: DispKind, to: String): String = when (to) {
        "5V", "3V3" -> "VCC"
        "SCK" -> if (k == DispKind.OLED_RGB) "CLK" else "SCL"
        "MOSI" -> if (k == DispKind.OLED_RGB) "DIN" else "SDA"
        "RST" -> if (k == DispKind.OLED_RGB) "RST" else "RES"
        "LCS" -> "CS"
        else -> to
    }
}
