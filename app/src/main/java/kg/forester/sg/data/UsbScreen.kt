package kg.forester.sg.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/*
 * A colour screen on its own USB (hardware/usbscreen): an ESP32-S3 board with an AMOLED or IPS panel. The app draws
 * whole pages (its gauges and tiles, any value it has) and sends the changed parts; the board only shows them.
 */

/**
 * A board the firmware is built for (boards.h, or bigscreen/big_boards.h when [big]): its panel, [w] x [h] pixels as the
 * panel stands (rotation 0). [selfFlash]: the app can write its firmware itself; else it comes from a computer. [twoRevs]:
 * the chip has two silicon generations that need separate images ([assetPre3]).
 */
enum class ScreenModel(val id: String, val title: String, val sub: String, val w: Int, val h: Int, val round: Boolean, val amoled: Boolean = true,
                       val big: Boolean = false, val selfFlash: Boolean = true, val twoRevs: Boolean = false,
                       /** What the driver has to know about connecting it. */
                       val note: String = "") {
    LILYGO_AMOLED_191("lilygo_amoled_191", "LilyGO T-Display-S3 AMOLED 1,91\"", "536×240, RM67162. Самая распространённая, вытянутая", 240, 536, false),
    LILYGO_AMOLED_164("lilygo_amoled_164", "LilyGO T-Display-S3 AMOLED 1,64\"", "280×456, CO5300", 280, 456, false),
    LILYGO_TDISPLAY_S3("lilygo_tdisplay_s3", "LilyGO T-Display-S3 1,9\" (IPS)", "320×170, ST7789. Не AMOLED, но дешёвая и яркая", 170, 320, false, amoled = false),
    LILYGO_T4_S3("lilygo_t4_s3", "LilyGO T4-S3 AMOLED 2,41\"", "600×450, RM690B0. Большая", 450, 600, false),
    WS_AMOLED_143("ws_amoled_143", "Waveshare ESP32-S3 AMOLED 1,43\" круглая", "466×466, CO5300. Под стрелочный прибор", 466, 466, true),
    WS_AMOLED_175("ws_amoled_175", "Waveshare ESP32-S3 AMOLED 1,75\" круглая", "466×466, CO5300. Крупнее 1,43", 466, 466, true),
    WS_AMOLED_164("ws_amoled_164", "Waveshare ESP32-S3 AMOLED 1,64\"", "280×456, CO5300", 280, 456, false),
    WS_AMOLED_18("ws_amoled_18", "Waveshare ESP32-S3 AMOLED 1,8\"", "368×448, SH8601", 368, 448, false),
    WS_AMOLED_241("ws_amoled_241", "Waveshare ESP32-S3 AMOLED 2,41\"", "600×450, RM690B0. Большая", 450, 600, false),
    WS_LCD_128("ws_lcd_128", "Waveshare ESP32-S3 LCD 1,28\" круглая (IPS)", "240×240, GC9A01. Не AMOLED, самая дешёвая круглая", 240, 240, true, amoled = false),
    WS_S3_LCD_43("ws_s3_lcd_43", "Waveshare ESP32-S3 LCD 4.3\"", "800×480, RGB. Около 10 кадров/с", 800, 480, false, amoled = false, big = true,
        note = "Плата ESP32-S3-Touch-LCD-4.3. Кабель в порт USB, не в USB TO UART. Кнопки страниц на плате нет: страницы по времени"),
    WS_S3_LCD_5("ws_s3_lcd_5", "Waveshare ESP32-S3 LCD 5\" 800×480", "Питание 7-36 В. Около 10 кадров/с", 800, 480, false, amoled = false, big = true,
        note = "Плата ESP32-S3-Touch-LCD-5, с экраном 800×480. Версия 5B (1024×600) не подойдёт. Кнопки страниц на плате нет: страницы по времени"),
    WS_S3_LCD_7("ws_s3_lcd_7", "Waveshare ESP32-S3 LCD 7\"", "800×480, RGB. Около 10 кадров/с", 800, 480, false, amoled = false, big = true,
        note = "Плата ESP32-S3-Touch-LCD-7. Кабель в порт USB, не в USB TO UART. Кнопки страниц на плате нет: страницы по времени"),
    WS_P4_7B("ws_p4_7b", "Waveshare ESP32-P4 LCD 7B", "1024×600, MIPI-DSI. Около 15 кадров/с", 1024, 600, false, amoled = false, big = true, selfFlash = false, twoRevs = true,
        note = "Плата ESP32-P4-WIFI6-Touch-LCD-7B. Кабель в порт USB 1.1 FS. Кнопки страниц на плате нет: страницы по времени"),
    WS_P4_101("ws_p4_101", "Waveshare ESP32-P4 LCD 10.1\"", "800×1280, MIPI-DSI, лежит на боку. Около 20 кадров/с", 800, 1280, false, amoled = false, big = true, selfFlash = false, twoRevs = true,
        note = "Плата ESP32-P4-WIFI6-Touch-LCD-10.1. Кабель в порт USB OTG. Кнопки страниц на плате нет: страницы по времени"),
    ;

    /** The firmware image for this board in the assets. */
    val asset: String get() = "usbscreen/bin/usbscreen-$id.bin"

    /** The image for the same board with a chip before revision 3.00. */
    val assetPre3: String? get() = if (twoRevs) "usbscreen/bin/usbscreen-$id-pre3.bin" else null

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** How a page lays its values out. [slots]: how many values it shows. */
enum class ScreenLayout(val title: String, val slots: Int, val own: Boolean = false) {
    GAUGE("Стрелка", 1),
    GAUGE2("Две стрелки", 2),
    GAUGE_TILES("Стрелка и две плитки", 3),
    TILES4("Четыре плитки", 4),
    TILES6("Шесть плиток", 6),
    BIG("Крупная цифра", 1),
    LIST("Список", 8),
    // the driver's own screens, as laid out in the app (big panels only)
    HOME("Моя Главная", 0, own = true),
    DRIVE("Моя Динамика", 0, own = true),
    MY("Мой экран", 0, own = true),
    SPORT("Спорт-дисплей: тахометр", 0, own = true),
    SPORT_TABLE("Спорт-дисплей: таблица", 0, own = true),
    ;

    companion object {
        /** The layouts a board is offered: its own screens need room. */
        fun forBoard(big: Boolean) = entries.filter { big || !it.own }
    }
}

/** One page: its layout, the values by Metrics id ("coolant", "box_A0", "rr:…"), the gauge design (a DialStyle id). */
data class ScreenPage(val id: Int, val layout: ScreenLayout, val items: List<String>, val style: String = "neon") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("layout", layout.name).put("items", JSONArray(items)).put("style", style)

    companion object {
        fun fromJson(o: JSONObject) = ScreenPage(
            o.getInt("id"), runCatching { ScreenLayout.valueOf(o.getString("layout")) }.getOrDefault(ScreenLayout.GAUGE),
            o.optJSONArray("items")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(), o.optString("style", "neon"),
        )
    }
}

/**
 * The screen's settings: which board, how it is turned (0..3 quarter turns), brightness 10..255, the pages, how they
 * change ([switchS] seconds, 0 = by the board's button only) and whether a value past its warning takes the screen.
 */
data class ScreenCfg(
    val model: String = "",
    /** Where it stands, the driver's word for it («стойка», «торпедо»); empty: the board's name. */
    val name: String = "",
    val rotation: Int = 0,
    val brightness: Int = 200,
    val pages: List<ScreenPage> = listOf(ScreenPage(1, ScreenLayout.GAUGE, listOf("rpm"))),
    val switchS: Int = 0,
    val alert: Boolean = true,
    val fps: Int = 10,
    /** The look on a big panel: "" as in the app, or one chosen for this screen ([kg.forester.sg.ui.ScreenLook]). */
    val theme: String = "",
    /** A big panel may get large changes as JPEG; off: lossless only, slower. */
    val jpeg: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("model", model).put("name", name).put("rotation", rotation).put("brightness", brightness)
        .put("pages", JSONArray().also { a -> pages.forEach { a.put(it.toJson()) } }).put("switchS", switchS).put("alert", alert).put("fps", fps)
        .put("theme", theme).put("jpeg", jpeg)

    companion object {
        fun fromJson(o: JSONObject) = ScreenCfg(
            o.optString("model"), o.optString("name"), o.optInt("rotation"), o.optInt("brightness", 200),
            o.optJSONArray("pages")?.let { a -> List(a.length()) { ScreenPage.fromJson(a.getJSONObject(it)) } }?.takeIf { it.isNotEmpty() }
                ?: listOf(ScreenPage(1, ScreenLayout.GAUGE, listOf("rpm"))),
            o.optInt("switchS"), o.optBoolean("alert", true), o.optInt("fps", 10).coerceIn(2, 30),
            o.optString("theme"), o.optBoolean("jpeg", true),
        )
    }
}

/** What the board said about itself: "I USBSCREEN 1 board=ws_amoled_143 w=466 h=466 round=1 fw=1 id=A1B2C3D4E5F6". */
data class ScreenInfo(val board: String, val w: Int, val h: Int, val round: Boolean, val fw: Int, val id: String = "",
                      /** The largest rectangle it takes; a board that doesn't say takes [OLD_MAX_PX]. */
                      val maxPx: Int = OLD_MAX_PX,
                      /** It decodes JPEG rectangles (J). */
                      val jpeg: Boolean = false) {
    /** A big panel's firmware (bigscreen.ino). */
    val isBig get() = maxPx > OLD_MAX_PX

    companion object {
        /** usbscreen.ino's MAX_PX. */
        const val OLD_MAX_PX = 16384

        fun parse(line: String): ScreenInfo? {
            if (!line.startsWith("I USBSCREEN")) return null
            val kv = line.split(' ').mapNotNull { w -> w.indexOf('=').takeIf { it > 0 }?.let { w.substring(0, it) to w.substring(it + 1) } }.toMap()
            return ScreenInfo(kv["board"] ?: "?", kv["w"]?.toIntOrNull() ?: 0, kv["h"]?.toIntOrNull() ?: 0, kv["round"] == "1",
                kv["fw"]?.toIntOrNull() ?: 0, kv["id"] ?: "",
                kv["maxpx"]?.toIntOrNull()?.takeIf { it >= OLD_MAX_PX } ?: OLD_MAX_PX, kv["jpeg"] == "1")
        }
    }
}

/** The firmware version shipped in the app (FW_VERSION in hardware/usbscreen/usbscreen.ino; a unit test keeps them equal). */
const val SCREEN_FIRMWARE = 1

/**
 * The colour screens: two or three can hang on one head unit (a hub), each with its own settings, found again by its
 * chip id. The settings under "" are a screen set up before it was ever plugged in: the first new screen takes them.
 */
object UsbScreen {
    val screens = MutableStateFlow<Map<String, ScreenCfg>>(mapOf("" to ScreenCfg()))
    /** Screens on USB now, by id. */
    val online = MutableStateFlow<Map<String, ScreenInfo>>(emptyMap())
    /** The USB device each screen on line is on, by id. */
    val devices = java.util.concurrent.ConcurrentHashMap<String, String>()
    val status = MutableStateFlow("")
    /** The page each screen shows now (an index into its pages). */
    val pages = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Frames a second really reaching each screen, for the settings. */
    val fps = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** The screen the settings show. */
    val selected = MutableStateFlow("")

    fun cfgOf(id: String): ScreenCfg = screens.value[id] ?: screens.value[""] ?: ScreenCfg()

    fun load() {
        screens.value = runCatching {
            val o = JSONObject(Repo.settings.usbScreen)
            when {
                o.has("screens") -> o.getJSONObject("screens").let { m -> m.keys().asSequence().associateWith { ScreenCfg.fromJson(m.getJSONObject(it)) } }
                o.has("pages") -> mapOf("" to ScreenCfg.fromJson(o)) // one screen, as saved before there could be more
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: mapOf("" to ScreenCfg())
        selected.value = screens.value.keys.first()
    }

    private fun save() {
        val m = JSONObject()
        screens.value.forEach { (k, v) -> m.put(k, v.toJson()) }
        runCatching { Repo.settings.usbScreen = JSONObject().put("screens", m).toString() } // (no settings in the unit tests)
    }

    fun put(id: String, c: ScreenCfg) { screens.value = screens.value + (id to c); save() }

    fun putPage(id: String, p: ScreenPage) = cfgOf(id).let { c -> put(id, c.copy(pages = c.pages.map { if (it.id == p.id) p else it })) }

    fun addPage(id: String, layout: ScreenLayout): ScreenPage {
        val c = cfgOf(id)
        val p = ScreenPage((c.pages.maxOfOrNull { it.id } ?: 0) + 1, layout, defaultItems(layout.slots))
        put(id, c.copy(pages = c.pages + p))
        return p
    }

    fun removePage(id: String, pageId: Int) {
        val c = cfgOf(id)
        if (c.pages.size <= 1) return
        put(id, c.copy(pages = c.pages.filter { it.id != pageId }))
        pages.value = pages.value + (id to pageOf(id).coerceAtMost(cfgOf(id).pages.size - 1))
    }

    fun pageOf(id: String) = pages.value[id] ?: 0

    fun next(id: String) { pages.value = pages.value + (id to (pageOf(id) + 1) % cfgOf(id).pages.size.coerceAtLeast(1)) }

    /**
     * A screen answered on USB: its settings by its id, or the ones set up for a screen not yet seen, or new ones for
     * its board (a tall panel starts turned on its side, as it sits in a car).
     */
    fun attach(info: ScreenInfo, device: String) {
        val id = info.id.ifEmpty { "dev:$device" }
        devices[id] = device
        if (id !in screens.value) {
            val template = screens.value[""]
            val m = ScreenModel.of(info.board)
            val c = template?.copy(model = info.board)
                ?: ScreenCfg(model = info.board, rotation = if (m != null && m.h > m.w * 1.4f) 1 else 0, pages = listOf(ScreenPage(1, ScreenLayout.GAUGE, listOf("rpm"))))
            screens.value = (screens.value - "") + (id to c)
            save()
            if (selected.value == "" || selected.value !in screens.value) selected.value = id
        } else if (screens.value[id]?.model != info.board) put(id, cfgOf(id).copy(model = info.board))
        online.value = online.value + (id to info.copy(id = id))
    }

    fun detach(id: String) {
        devices.remove(id)
        online.value = online.value - id
        fps.value = fps.value - id
    }

    /** A screen not on line any more taken off the list; the last one stays as the template. */
    fun forget(id: String) {
        val left = screens.value - id
        screens.value = left.ifEmpty { mapOf("" to ScreenCfg()) }
        save()
        if (selected.value == id) selected.value = screens.value.keys.first()
    }

    /** Settings for one more screen before it is plugged in. */
    fun addTemplate() {
        if ("" !in screens.value) put("", ScreenCfg())
        selected.value = ""
    }

    /** The name a screen goes by in the list. */
    fun title(id: String): String {
        val c = cfgOf(id)
        return c.name.ifEmpty { ScreenModel.of(c.model)?.title ?: if (id.isEmpty()) "Новый экран" else "Экран" }
    }

    /** The usual ones first, the box's oil and pressure when it has them. */
    fun defaultItems(n: Int): List<String> {
        val box = SensorBox.slots.value
        return listOfNotNull(
            "rpm", box.firstOrNull { it.role == "oil" }?.let { "box_${it.input}" } ?: "coolant",
            box.firstOrNull { it.role == "oilP" }?.let { "box_${it.input}" } ?: "boost", "battery", "oil", "intake", "afr", "speed",
        ).distinct().take(n)
    }

    /** The size pages are drawn at: the panel turned by the rotation. */
    fun canvasSize(m: ScreenModel, rotation: Int): Pair<Int, Int> = if (rotation % 2 == 1) m.h to m.w else m.w to m.h
}
