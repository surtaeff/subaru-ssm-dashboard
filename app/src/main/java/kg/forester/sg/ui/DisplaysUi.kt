package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxFont
import kg.forester.sg.data.DispCfg
import kg.forester.sg.data.DispKind
import kg.forester.sg.data.DispLayout
import kg.forester.sg.data.DispLine
import kg.forester.sg.data.Displays
import kg.forester.sg.data.Relays
import kg.forester.sg.data.RuleSources
import kg.forester.sg.data.SensorBox
import kg.forester.sg.service.DisplayEngine
import kg.forester.sg.service.RelayEngine

/*
 * Настройки → Датчики и экраны → «Дисплеи»: a small screen wired to the box (an OLED, a text LCD, four digits) showing what
 * the driver picks. The preview here draws with the box's own font, so what is on the head unit is what the car gets.
 */

/** How the display the box reports goes with the one chosen: the words and the colour of the status line. */
private fun dispStatus(d: DispCfg): Pair<String, Color> {
    val info = SensorBox.info.value
    return when {
        info == null -> "Плата не на связи" to C.Sub
        info.via == "демо" -> "Демо: экран показан только здесь" to C.Violet
        info.fw < 5 -> "Нужна прошивка 5 или новее: шаг «Прошивка»" to C.Orange
        d.kind == DispKind.TM1637 -> if (d.clk.isEmpty() || d.dio.isEmpty()) "Выберите пины CLK и DIO" to C.Orange else "Плата выводит на ${d.clk} и ${d.dio}" to C.Green
        // SPI has no way to ask a screen whether it is there: the box draws, the driver looks
        d.kind.colour -> if (d.dc.isEmpty() || (d.kind.cs && d.cs.isEmpty())) "Выберите пины" + (if (d.kind.cs) " CS и DC" else " DC") to C.Orange
            else "Плата выводит на SPI, CS ${d.cs.ifEmpty { "нет" }}, DC ${d.dc}" to C.Green
        "oled" in info.disp && d.kind.oled -> "Плата нашла OLED" to C.Green
        "lcd" in info.disp && d.kind.lcd -> "Плата нашла LCD" to C.Green
        info.disp == "none" -> "Плата не нашла дисплей на I2C" to C.Orange
        else -> "Плата не нашла ${if (d.kind.oled) "OLED" else "LCD"} на I2C (нашла ${info.disp.uppercase().replace("+", " и ")})" to C.Orange
    }
}

private fun kindIcon(k: DispKind) = when {
    k.oled -> Lucide.Monitor
    k.colour -> Lucide.Palette
    k == DispKind.TM1637 -> Lucide.Hash
    else -> Lucide.RectangleHorizontal
}

/** The left column's part: the displays with their status, «Добавить дисплей». */
@Composable
fun DisplaysSection(selected: String?, onSelect: (String) -> Unit) {
    val list by Displays.list.collectAsState()
    SensorBox.info.collectAsState().value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { d ->
            val sel = selected == "disp:${d.id}"
            val (txt, col) = dispStatus(d)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                    .border(1.dp, if (sel) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(10.dp)).clickable { onSelect("disp:${d.id}") }
                    .heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(kindIcon(d.kind), null, tint = col, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(d.kind.title, 14.sp, maxLines = 1)
                    T(txt, 12.sp, col, maxLines = 1)
                }
            }
        }
        if (DispKind.entries.any { Displays.canAdd(it) }) AddRow("Добавить дисплей", "OLED, LCD, цветной или 4 цифры", selected == "disp:new") { onSelect("disp:new") }
    }
}

/**
 * «Добавить дисплей»: every kind, each turning in 3D, with what it is good for; what does not go with this board or
 * with what is already on it is dimmed with the reason. The colour screens on an ESP32 have their own tab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayNew(board: BoxBoard, onCreated: (Int) -> Unit) {
    Displays.list.collectAsState().value
    T("Какой дисплей подключаете?", 18.sp, weight = FontWeight.SemiBold)
    SettingHint("Плата рисует на нём то, что выберете: температуры, давление, напряжение. Пропадёт связь с магнитолой, на экране будет НЕТ СВЯЗИ вместо старых цифр")
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DispKind.entries.forEach { k ->
            val unfit = Displays.unfit(k, board)
            val can = Displays.canAdd(k) && unfit == null
            Column(
                Modifier.width(250.dp).alpha(if (can) 1f else 0.45f).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
                    .border(1.dp, C.Border, RoundedCornerShape(12.dp))
                    .clickable(enabled = can) { val d = Displays.new(k, board); Displays.put(d); onCreated(d.id) }.padding(10.dp),
            ) {
                Model3D(k.module, Modifier.fillMaxWidth().height(120.dp))
                T(k.title, 14.sp, weight = FontWeight.SemiBold, maxLines = 2)
                T(k.sub, 12.sp, C.Sub, maxLines = 3)
                when {
                    !Displays.canAdd(k) -> T(when (k.bus) {
                        kg.forester.sg.data.DispBus.I2C -> if (k.lcd) "Текстовый LCD уже есть" else "OLED уже есть: на I2C один OLED и один LCD"
                        kg.forester.sg.data.DispBus.TM -> "Цифровой уже есть"
                        kg.forester.sg.data.DispBus.SPI -> "Цветной на SPI уже есть"
                    }, 12.sp, C.Orange)
                    unfit != null -> T(unfit, 12.sp, C.Orange)
                    k.i2c -> T("Провода: ${board.sda} и ${board.scl}" + if (board == BoxBoard.UNO) " (это же A4 и A5)" else "", 12.sp, C.Violet)
                    k.colour -> T("Провода: ${board.sck}, ${board.mosi}, RST и ${if (k.cs) "два" else "один"} из свободных пинов", 12.sp, C.Violet)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Hint(Lucide.Lightbulb, "Можно поставить сразу четыре: OLED и текстовый LCD на одних проводах I2C, цифровой TM1637 и цветной на SPI")
    Hint(Lucide.Info, "Цветной AMOLED с ESP32-S3 (LilyGO, Waveshare, круглые) к этой плате не подключается: он идёт отдельным устройством в USB магнитолы. " +
        "Настройки, Датчики и экраны, Цветной экран")
}

/** One display: what it shows (a preview drawn with the box's font), what goes on each line, how to wire it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayDetails(board: BoxBoard, id: Int, onGone: () -> Unit) {
    val list by Displays.list.collectAsState()
    val d = list.firstOrNull { it.id == id } ?: run { onGone(); return }
    val boxInfo by SensorBox.info.collectAsState()
    // the preview follows the values (and the test pattern) three times a second
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(330); value = System.currentTimeMillis() }
    }
    SensorBox.info.collectAsState().value
    val (txt, col) = dispStatus(d)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(kindIcon(d.kind), null, tint = C.Violet, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(d.kind.title, 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(txt, 13.sp, col, maxLines = 2)
        }
        Chip("Проверить экран", false, { DisplayEngine.test() }, Lucide.ScanEye)
        Spacer(Modifier.width(6.dp))
        Chip("Убрать", false, {
            Displays.remove(d.id)
            offerUndo("Дисплей «${d.kind.title}» убран") { Displays.put(d) }
            onGone()
        }, Lucide.Trash2)
    }
    if (d.kind.i2c && boxInfo?.let { it.via.startsWith("USB") && it.fw >= 5 && (if (d.kind.lcd) "lcd" else "oled") !in it.disp } == true) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Подключили только что? Плата ищет экран при включении", 13.sp, C.Sub, modifier = Modifier.weight(1f))
            Chip("Найти ещё раз", false, { DisplayEngine.findAgain() }, Lucide.RefreshCw)
        }
    }
    Spacer(Modifier.height(10.dp))

    // the preview: the same lines the box gets, drawn the way it draws them
    val testing = now < DisplayEngine.testUntil
    val lines = if (testing) DisplayEngine.testLines(d) else Displays.lines(d) { RelayEngine.value(it) }
    val ops = if (!d.kind.colour) emptyList() else if (testing) Displays.colourTest(d.kind) else Displays.colourOps(d, { RelayEngine.value(it) }, DisplayEngine::warn)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DisplayPreview(d, lines, Modifier.widthIn(max = if (d.kind.colour && d.kind.h >= d.kind.w) 340.dp else 460.dp).fillMaxWidth(), ops)
    }
    T(when {
        d.kind.oled -> "Так будет на экране ${d.kind.w}×${d.kind.h}. Русские буквы, градусы и знаки плата рисует сама"
        d.kind.colour -> "Так будет на экране ${d.kind.w}×${d.kind.h}: подписи, числа (красные, когда за пределом нормы) и полосы шкалы"
        d.kind == DispKind.TM1637 -> "Четыре цифры: число и, если влезает, °C. Подписи такой экран не умеет"
        else -> "Экран знает только латиницу: подписи написаны транслитом"
    }, 12.sp, C.Sub)

    // what goes where
    Spacer(Modifier.height(12.dp))
    if (d.kind.oled || d.kind.colour) {
        T("Как расположить", 15.sp, weight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DispLayout.entries.forEach { l -> Chip(l.title, d.layout == l, { Displays.put(d.copy(layout = l)) }) }
        }
        Spacer(Modifier.height(8.dp))
    }
    T(if (d.slots == 1) "Что показывать" else "Что на каждой строке", 15.sp, weight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        (0 until d.slots).forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d.slots > 1) T("${i + 1}.", 14.sp, C.Sub, modifier = Modifier.width(28.dp))
                ItemPicker(d.items.getOrNull(i), now) { src ->
                    val items = (0 until maxOf(d.slots, d.items.size)).map { j -> if (j == i) src else d.items.getOrNull(j) ?: "" }
                    Displays.put(d.copy(items = items.dropLastWhile { it.isEmpty() }))
                }
            }
        }
    }

    if (d.kind == DispKind.TM1637 || d.kind.colour) {
        Spacer(Modifier.height(12.dp))
        T("Пины платы", 15.sp, weight = FontWeight.SemiBold)
        val free = Displays.freeOutPins(board, except = d)
        // the pins this display picks itself, with how to set each
        val picks: List<Triple<String, String, (String) -> DispCfg>> = if (d.kind == DispKind.TM1637)
            listOf(Triple("CLK", d.clk) { p -> d.copy(clk = p) }, Triple("DIO", d.dio) { p -> d.copy(dio = p) })
        else listOfNotNull(if (d.kind.cs) Triple("CS", d.cs) { p: String -> d.copy(cs = p) } else null, Triple("DC", d.dc) { p -> d.copy(dc = p) })
        picks.forEach { (what, cur, set) ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                T(what, 14.sp, C.Sub, modifier = Modifier.width(40.dp).align(Alignment.CenterVertically))
                val owners = SensorBox.pinOwners(board, "disp:${d.id}")
                board.outPins.forEach { p ->
                    val busy = p !in free || picks.any { it.first != what && it.second == p }
                    val who = owners[p]
                    Chip(p + (who?.let { " · $it" } ?: ""), cur == p, { if (!busy) Displays.put(set(p)) },
                        modifier = Modifier.alpha(if (busy && cur != p) 0.4f else 1f))
                }
            }
        }
        if (picks.any { it.second.isEmpty() }) T("Свободных пинов не хватает: уберите один из выходов", 12.sp, C.Orange)
        if (d.kind.colour) SettingHint("SCK (${board.sck}) и MOSI (${board.mosi}) у шины SPI свои, RES к пину RST платы")
    }
    if (d.kind == DispKind.TM1637) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Яркость", 14.sp, modifier = Modifier.weight(1f))
            (0..7).forEach { b ->
                Chip("${b + 1}", d.bright == b, { Displays.put(d.copy(bright = b)) })
                Spacer(Modifier.width(4.dp))
            }
        }
    }

    // pins it takes away from others
    if (d.kind.i2c) {
        val clash = Displays.i2cClash(board)
        if (clash.isNotEmpty()) Hint(Lucide.TriangleAlert, "На Uno пины SDA и SCL это те же A4 и A5: датчик ${clash.joinToString { "«${it.name}» (${it.input})" }} " +
            "перестанет читаться, пока подключён дисплей. Перенесите его на свободный вход", C.Orange)
        if (board == BoxBoard.PROMICRO) Hint(Lucide.Info, "На Pro Micro I2C это пины D2 и D3: выходы на них больше не поставить")
    }

    // wiring
    Spacer(Modifier.height(12.dp))
    T("Как подключить", 15.sp, weight = FontWeight.SemiBold)
    val preset = Displays.preset(d.kind)
    val input = when {
        d.kind == DispKind.TM1637 -> "${d.clk}|${d.dio}"
        d.kind.colour -> "${d.cs}|${d.dc}"
        else -> ""
    }
    val sensors = SensorBox.slots.collectAsState().value.filter { it.input in board.inputs }
    WiringPicture(board, input, preset, sensors, Modifier.fillMaxWidth().height(280.dp))
    Spacer(Modifier.height(6.dp))
    WiringSteps(board, input, preset, sensors)
    preset.tips.forEach { Hint(Lucide.Lightbulb, it) }
}

/** A source for one line: a chip with its name and live value ([now] redraws it with the preview), a menu of everything there is. */
@Composable
private fun ItemPicker(src: String?, @Suppress("UNUSED_PARAMETER") now: Long, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val s = src?.takeIf { it.isNotEmpty() }?.let { RuleSources.of(it) }
    val live = s?.let { RelayEngine.value(it.id) }
    Box {
        Chip(s?.let { it.title + (live?.let { v -> " · " + kg.forester.sg.service.RuleRunner.num(it.id, v) } ?: "") } ?: "пусто", s != null, { open = true }, Lucide.ChevronDown)
        CarDropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Ничего (пустая строка)") }, onClick = { open = false; onPick("") })
            RuleSources.all().forEach { r -> DropdownMenuItem(text = { Text(r.title) }, onClick = { open = false; onPick(r.id) }) }
        }
    }
}

// ---------------------------------------------------------------- the preview

private val OLED_ON = Color(0xFF7FE3FF)
private val LCD_BG = Color(0xFF2F6FE0)
private val LCD_ON = Color(0xFFF2F6FF)
private val TM_ON = Color(0xFFFF3B3B)

/** [lines] drawn the way the box draws them on [d]. */
@Composable
fun DisplayPreview(d: DispCfg, lines: List<DispLine>, modifier: Modifier, ops: List<kg.forester.sg.data.ColOp> = emptyList()) {
    val k = d.kind
    val ratio = when {
        k.oled || k.colour -> k.w.toFloat() / k.h
        k.lcd -> k.w * 6f / (k.h * 9f)
        else -> 3.2f
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D0D10)).border(2.dp, Color(0xFF2A2F3A), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(ratio)) {
            when {
                k.oled -> drawOled(lines.map { it.copy(text = Displays.wire(it.text, k)) }, k.h)
                k.colour -> drawColour(ops, k)
                k == DispKind.TM1637 -> drawTm(lines.firstOrNull()?.text ?: "")
                else -> drawLcd(lines.map { it.copy(text = Displays.wire(it.text, k)) }, k.w, d.slots)
            }
        }
    }
}

private fun rgb565(c: Int) = Color(((c shr 11) and 0x1F) * 255 / 31, ((c shr 5) and 0x3F) * 255 / 63, (c and 0x1F) * 255 / 31)

/** A colour screen as the box's colFill()/colText() draw it: boxes, and the font scaled up cell by cell, [fg] on [bg]. */
private fun DrawScope.drawColour(ops: List<kg.forester.sg.data.ColOp>, k: DispKind) {
    val p = size.width / k.w
    drawRect(Color.Black)
    for (op in ops) when (op) {
        is kg.forester.sg.data.ColOp.Box -> if (op.w > 0 && op.h > 0) drawRect(rgb565(op.c), Offset(op.x * p, op.y * p), Size(op.w * p, op.h * p))
        is kg.forester.sg.data.ColOp.Txt -> {
            val fg = rgb565(op.fg)
            val bg = rgb565(op.bg)
            val text = Displays.wire(op.text, k)
            val cw = 6 * op.s
            for (i in text.indices) {
                val x0 = op.x + i * cw
                if (x0 + cw > k.w) break
                drawRect(bg, Offset(x0 * p, op.y * p), Size(cw * p, 8 * op.s * p))
                val g = glyph(text[i]) ?: continue
                for (x in 0 until 5) for (y in 0 until 8) if ((g[x] shr y) and 1 == 1)
                    drawRect(fg, Offset((x0 + x * op.s) * p, (op.y + y * op.s) * p), Size(op.s * p, op.s * p))
            }
        }
    }
}

/** The box's glyph for a byte: 5 columns, bit 0 at the top; the LCD's ° (0xDF) borrows the OLED's '~'. */
private fun glyph(c: Char): IntArray? = BoxFont.glyphs[if (c.code == 0xDF) '~'.code else c.code]

/** The OLED page by page, as oledLine() in the firmware: 6×[scale] pixels a character, the rest of the line blank. */
private fun DrawScope.drawOled(lines: List<DispLine>, rows: Int = 64) {
    val px = Array(rows) { BooleanArray(128) }
    for (l in lines) {
        val scale = l.scale.coerceIn(1, 3)
        if (l.row + scale > rows / 8) continue
        for (y in l.row * 8 until (l.row + scale) * 8) px[y].fill(false)
        val chars = 128 / (6 * scale)
        for (i in 0 until minOf(chars, l.text.length)) {
            val g = glyph(l.text[i]) ?: continue
            for (x in 0 until 5) for (r in 0 until 8 * scale) {
                val src = r / scale
                if (src < 8 && (g[x] shr src) and 1 == 1) for (k in 0 until scale) {
                    val cx = (i * 6 + x) * scale + k
                    if (cx < 128) px[l.row * 8 + r][cx] = true
                }
            }
        }
    }
    val p = size.width / 128f
    for (y in 0 until rows) for (x in 0 until 128) if (px[y][x]) drawRect(OLED_ON, Offset(x * p, y * p), Size(p * 0.92f, p * 0.92f))
}

/** A text LCD: cells of 5×8 dots, the unlit ones faintly seen, as on the real glass. */
private fun DrawScope.drawLcd(lines: List<DispLine>, cols: Int, rows: Int) {
    drawRoundRect(LCD_BG, Offset.Zero, size, CornerRadius(4.dp.toPx()))
    val cw = size.width / cols
    val ch = size.height / rows
    val dot = minOf(cw / 6f, ch / 9f)
    val ox = (cw - dot * 6f) / 2 + dot * 0.5f
    val oy = (ch - dot * 9f) / 2 + dot * 0.5f
    val text = Array(rows) { r -> lines.firstOrNull { it.row == r }?.text ?: "" }
    for (r in 0 until rows) for (c in 0 until cols) {
        val g = text[r].getOrNull(c)?.let { glyph(it) }
        for (x in 0 until 5) for (y in 0 until 8) {
            val on = g != null && (g[x] shr y) and 1 == 1
            drawRect(if (on) LCD_ON else Color.White.copy(alpha = 0.06f), Offset(c * cw + ox + x * dot, r * ch + oy + y * dot), Size(dot * 0.88f, dot * 0.88f))
        }
    }
}

/** Four seven-segment digits with their dots, lit as the box's tmSeg() lights them. */
private fun DrawScope.drawTm(t: String) {
    val seg = Displays.tmSegments(t)
    val dw = size.width / 4.4f
    val h = size.height * 0.86f
    val top = (size.height - h) / 2
    val th = h * 0.1f
    val w = dw * 0.62f
    for (k in 0 until 4) {
        val x0 = size.width * 0.06f + k * dw
        val s = seg[k]
        fun on(bit: Int) = if ((s shr bit) and 1 == 1) TM_ON else TM_ON.copy(alpha = 0.08f)
        // a, b, c, d, e, f, g
        drawRect(on(0), Offset(x0 + th, top), Size(w - 2 * th, th))
        drawRect(on(1), Offset(x0 + w - th, top + th), Size(th, h / 2 - 1.5f * th))
        drawRect(on(2), Offset(x0 + w - th, top + h / 2 + th / 2), Size(th, h / 2 - 1.5f * th))
        drawRect(on(3), Offset(x0 + th, top + h - th), Size(w - 2 * th, th))
        drawRect(on(4), Offset(x0, top + h / 2 + th / 2), Size(th, h / 2 - 1.5f * th))
        drawRect(on(5), Offset(x0, top + th), Size(th, h / 2 - 1.5f * th))
        drawRect(on(6), Offset(x0 + th, top + h / 2 - th / 2), Size(w - 2 * th, th))
        drawCircle(on(7), th * 0.7f, Offset(x0 + w + th, top + h - th / 2))
    }
}
