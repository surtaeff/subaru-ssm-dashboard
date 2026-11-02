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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.data.BOX_FIRMWARE
import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxKind
import kg.forester.sg.data.BoxPreset
import kg.forester.sg.data.BoxPresets
import kg.forester.sg.data.BoxRoles
import kg.forester.sg.data.BoxSlot
import kg.forester.sg.data.Repo
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.WIRE_BLACK
import kg.forester.sg.data.WIRE_RED
import java.util.Locale

/**
 * Настройки → «Датчики и экраны»: the SensorBox (an Arduino with sensors on the head unit's USB). The left side is the
 * board and its inputs, the right side what the chosen input is for: a sensor from the examples, how to wire it
 * (a drawing of the real board with the pins to use), and its settings with the live reading.
 */
/** The steps of setting the box up: the left column lists them, the right panel shows the open one. */
internal enum class BoxStep(val n: Int, val title: String) {
    BOARD(1, "Плата"), FLASH(2, "Прошивка"), SENSORS(3, "Датчики"), OUTPUTS(4, "Выходы"), DISPLAYS(5, "Дисплеи"), CHECK(6, "Проверка"),
}

/** A removal the driver can take back for a few seconds («… убран · Вернуть»). */
internal class Undo(val text: String, val back: () -> Unit)

internal val undoState = mutableStateOf<Undo?>(null)

internal fun offerUndo(text: String, back: () -> Unit) { undoState.value = Undo(text, back) }

@Composable
fun SensorBoxSettings() {
    val displays by kg.forester.sg.data.Displays.list.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    val outs by kg.forester.sg.data.Relays.channels.collectAsState()
    val board by SensorBox.board.collectAsState()
    // the first visit starts at the board; later at the sensors, where people come back to
    var step by remember { mutableStateOf(if (slots.isEmpty() && outs.isEmpty()) BoxStep.BOARD else BoxStep.SENSORS) }
    var selected by remember { mutableStateOf<String?>(null) }
    fun go(s: BoxStep, sel: String? = null) { step = s; selected = sel }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val leftW = if (maxWidth >= 1050.dp) 420.dp else if (LocalCar.current.small) 380.dp else 340.dp
        Row(Modifier.fillMaxSize()) {
            Panel(Modifier.width(leftW).fillMaxHeight()) {
                BoxHeader()
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    BoxSteps(step, selected) { s, sel -> go(s, sel) }
                }
            }
            Spacer(Modifier.width(12.dp))
            // a fresh scroll for every page, and again once a sensor is picked: the page starts at its top
            val presetKey = selected?.let { sel -> slots.firstOrNull { it.input == sel }?.preset }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                androidx.compose.runtime.key(step, selected, presetKey) {
                    Panel(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val sel = selected
                        when (step) {
                            BoxStep.BOARD -> BoardStep { go(BoxStep.FLASH) }
                            BoxStep.FLASH -> FlashGuide { go(BoxStep.SENSORS) }
                            BoxStep.SENSORS -> when (sel) {
                                null -> BoxIntro({ go(BoxStep.FLASH) }) { go(BoxStep.SENSORS, it) }
                                "add" -> AddSensor { go(BoxStep.SENSORS, it) }
                                else -> InputDetails(sel) { go(BoxStep.SENSORS) }
                            }
                            BoxStep.OUTPUTS -> when {
                                sel == null || sel == "out:new" -> OutputNew(board) { go(BoxStep.OUTPUTS, "out:$it") }
                                else -> OutputDetails(board, sel.removePrefix("out:").toInt()) { go(BoxStep.OUTPUTS) }
                            }
                            BoxStep.DISPLAYS -> when {
                                sel == null || sel == "disp:new" -> if (sel == null && displays.isNotEmpty())
                                    DisplayDetails(board, displays.first().id) { go(BoxStep.DISPLAYS, "disp:new") }
                                    else DisplayNew(board) { go(BoxStep.DISPLAYS, "disp:$it") }
                                else -> DisplayDetails(board, sel.removePrefix("disp:").toInt()) { go(BoxStep.DISPLAYS) }
                            }
                            BoxStep.CHECK -> BoxCheck { s, x -> go(s, x) }
                        }
                        Spacer(Modifier.height(60.dp)) // room for the undo bar
                    }
                }
                UndoBar(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun UndoBar(m: Modifier) {
    val u = undoState.value ?: return
    LaunchedEffect(u) { kotlinx.coroutines.delay(6000); if (undoState.value === u) undoState.value = null }
    Row(
        m.padding(12.dp).clip(RoundedCornerShape(14.dp)).background(C.CardHi).border(1.dp, C.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(u.text, 14.sp, maxLines = 2, modifier = Modifier.widthIn(max = roomFor(420.dp, 14f)))
        Spacer(Modifier.width(16.dp))
        Chip("Вернуть", true, { u.back(); undoState.value = null }, Lucide.Undo2)
    }
}

// ---------------------------------------------------------------- left: the status, then the steps

/** Always in sight: is the board there, which one, what firmware; the search switch, or the way out of the demo. */
@Composable
private fun BoxHeader() {
    val on by SensorBox.enabled.collectAsState()
    val info by SensorBox.info.collectAsState()
    val status by SensorBox.status.collectAsState()
    val demo = info?.via == "демо"
    val (icon, color, text) = when {
        demo -> Triple(Lucide.MonitorPlay, C.Violet, "Демо: плата придумана")
        info != null -> Triple(Lucide.CircleCheck, C.Green, "Плата на связи · ${BoxBoard.of(info!!.board).short} · прошивка ${info!!.fw}")
        on -> Triple(Lucide.Usb, C.Orange, status.ifEmpty { "Ищу плату на USB…" })
        else -> Triple(Lucide.Unplug, C.Sub, "Поиск платы на USB выключен")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(text, 14.sp, C.Text, FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
        if (demo) Chip("Выйти", false, { Repo.settings.demo = false })
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(on, { SensorBox.setEnabled(it) }, colors = SwitchDefaults.colors(
                checkedTrackColor = C.Purple, uncheckedTrackColor = C.CardHi, uncheckedThumbColor = C.Sub))
            T("искать на USB", 12.sp, C.Sub, maxLines = 1)
        }
    }
}

/** One step: its number (a tick once done), its name and a live line of where it stands. */
@Composable
private fun StepRow(st: BoxStep, status: String, done: Boolean, warn: Boolean, open: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(if (open) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(1.dp, if (open) C.Purple else C.Border, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(when { warn -> C.Orange; done -> C.Green; else -> C.Purple }), contentAlignment = Alignment.Center) {
            if (done && !warn) Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            else T(if (warn) "!" else "${st.n}", 15.sp, Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(st.title, 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(status, 13.sp, if (warn) C.Orange else C.Sub, maxLines = 2)
        }
        Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BoxSteps(step: BoxStep, selected: String?, go: (BoxStep, String?) -> Unit) {
    val board by SensorBox.board.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    val info by SensorBox.info.collectAsState()
    val readings by SensorBox.readings.collectAsState()
    val outs by kg.forester.sg.data.Relays.channels.collectAsState()
    val outStatus by kg.forester.sg.data.Relays.status.collectAsState()
    val allOff by kg.forester.sg.data.Relays.allOff.collectAsState()
    val flash by kg.forester.sg.flash.FirmwareFlasher.state.collectAsState()
    val real = info?.takeIf { it.via != "демо" }
    val mine = slots.filter { it.input in board.inputs }.sortedBy { board.inputs.indexOf(it.input) }
    val faults = mine.count { readings[it.input]?.problem != null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wrongBoard = real != null && real.board != board.id
        StepRow(BoxStep.BOARD, if (wrongBoard) "Подключена ${BoxBoard.of(real!!.board).short}, а выбрана ${board.short}" else board.title,
            done = true, warn = wrongBoard, open = step == BoxStep.BOARD) { go(BoxStep.BOARD, null) }
        val fwOld = real != null && real.fw < BOX_FIRMWARE
        StepRow(BoxStep.FLASH, when {
            flash is kg.forester.sg.flash.FlashState.Working -> "Прошиваю… ${((flash as kg.forester.sg.flash.FlashState.Working).progress * 100).toInt()} %"
            info?.via == "демо" -> "в демо не нужна"
            real == null -> "плата не на связи"
            fwOld -> "Версия ${real.fw}, есть новее: обновите"
            else -> "версия ${real.fw}, свежая"
        }, done = real != null && !fwOld, warn = fwOld, open = step == BoxStep.FLASH) { go(BoxStep.FLASH, null) }
        StepRow(BoxStep.SENSORS, when {
            mine.isEmpty() -> "пока нет"
            faults > 0 -> "${mine.size} подключено · с ошибкой: $faults"
            else -> "${mine.size} подключено"
        }, done = mine.isNotEmpty(), warn = faults > 0, open = step == BoxStep.SENSORS) { go(BoxStep.SENSORS, null) }
        if (step == BoxStep.SENSORS) Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            mine.forEach { s -> InputRow(board, s.input, s, readings[s.input], s.input == selected) { go(BoxStep.SENSORS, s.input) } }
            val free = board.inputs.count { i -> SensorBox.inputBlocked(board, i) == null }
            if (free > 0) AddRow("Добавить датчик", "свободно входов: $free", selected == "add") { go(BoxStep.SENSORS, "add") }
        }
        val onCount = outs.count { outStatus[it.ch]?.want == true }
        val manual = outs.count { it.manual != kg.forester.sg.data.Manual.AUTO }
        StepRow(BoxStep.OUTPUTS, when {
            outs.isEmpty() -> "пока нет"
            allOff -> "Всё выключено кнопкой"
            else -> "${outs.size} · включено: $onCount" + if (manual > 0) " · вручную: $manual" else ""
        }, done = outs.isNotEmpty(), warn = allOff, open = step == BoxStep.OUTPUTS) { go(BoxStep.OUTPUTS, null) }
        if (step == BoxStep.OUTPUTS) Column(Modifier.padding(start = 14.dp)) { OutputsSection(board, selected) { go(BoxStep.OUTPUTS, it) } }
        val disps by kg.forester.sg.data.Displays.list.collectAsState()
        StepRow(BoxStep.DISPLAYS, when {
            disps.isEmpty() -> "пока нет: OLED, LCD или 4 цифры"
            real != null && real.fw < 5 -> "Нужна прошивка 5: обновите"
            else -> disps.joinToString { it.kind.title.substringBefore(" с ").substringBefore(" 0,96") }
        }, done = disps.isNotEmpty(), warn = disps.isNotEmpty() && real != null && real.fw < 5, open = step == BoxStep.DISPLAYS) { go(BoxStep.DISPLAYS, null) }
        if (step == BoxStep.DISPLAYS) Column(Modifier.padding(start = 14.dp)) { DisplaysSection(selected) { go(BoxStep.DISPLAYS, it) } }
        val questions = faults + (if (real == null && info?.via != "демо") 1 else 0)
        StepRow(BoxStep.CHECK, if (mine.isEmpty() && outs.isEmpty()) "проверить подключённое вживую" else if (questions == 0) "всё в порядке" else "Есть вопросы: $questions",
            done = mine.isNotEmpty() && questions == 0, warn = questions > 0 && mine.isNotEmpty(), open = step == BoxStep.CHECK) { go(BoxStep.CHECK, null) }
    }
}

/** A «+ …» row under a step. */
@Composable
internal fun AddRow(title: String, sub: String, sel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(1.dp, if (sel) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Plus, null, tint = C.Violet, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            T(title, 14.sp, weight = FontWeight.SemiBold)
            T(sub, 12.sp, C.Sub)
        }
    }
}

@Composable
private fun InputRow(board: BoxBoard, input: String, s: BoxSlot?, r: kg.forester.sg.data.BoxReading?, sel: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val bad = r?.problem != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(if (bad) 2.dp else 1.dp, if (bad) C.Orange else if (sel) C.activeEdge(C.Purple) else C.Border, shape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(board.pcb)).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) { T(board.pinOf(input), 13.sp, Color.White, FontWeight.Bold, maxLines = 1) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(s?.name ?: "свободен", 14.sp, if (s == null) C.Sub else C.Text, maxLines = 1)
            if (s != null) T(if (s.role.isNotEmpty()) BoxRoles.title(s.role) else if (s.preset == "pot") "ручка 0–100 %" else s.p.kind.title, 12.sp, C.Sub, maxLines = 1)
        }
        when {
            s == null -> Icon(Lucide.Plus, null, tint = C.Sub, modifier = Modifier.size(18.dp))
            r?.value != null -> T(valueText(s, r.value) + " " + s.unit, 15.sp, C.Text, FontWeight.SemiBold, maxLines = 1)
            bad -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.TriangleAlert, null, tint = C.Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                T(shortProblem(r!!.problem!!), 13.sp, C.Orange, maxLines = 2, modifier = Modifier.widthIn(max = roomFor(110.dp, 13f)))
            }
            else -> T("—", 15.sp, C.Sub)
        }
    }
}

/** The fault in two or three words for a row. */
private fun shortProblem(p: String) = when {
    p.startsWith("обрыв") -> "Обрыв?"
    p.contains("массу") -> "Замыкание на массу"
    p.contains("5 В") -> "Замыкание на +5 В"
    p.contains("модул") -> "Нет модуля"
    else -> p.replaceFirstChar { it.uppercase() }
}

private fun valueText(s: BoxSlot, v: Float) = "%.${s.p.decimals}f".format(Locale("ru"), v)

// ---------------------------------------------------------------- right: step 1, the board

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardStep(onNext: () -> Unit) {
    val board by SensorBox.board.collectAsState()
    val info by SensorBox.info.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    var confirm by remember { mutableStateOf<BoxBoard?>(null) }
    T("Какая у вас плата", 18.sp, weight = FontWeight.SemiBold)
    SettingHint("От неё зависят входы и схемы подключения. Если плата уже на связи, она отмечена")
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxBoard.entries.forEach { b ->
            val sel = b == board
            val here = info?.takeIf { it.via != "демо" }?.board == b.id
            Column(
                Modifier.width(250.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                    .border(if (sel) 2.dp else 1.dp, if (sel) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(14.dp))
                    .clickable {
                        if (b == board) return@clickable
                        // the inputs the new board has not got would lose their sensors: ask first
                        if (slots.any { it.input !in b.inputs }) confirm = b else SensorBox.setBoard(b)
                    }.padding(10.dp),
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val hasModel by androidx.compose.runtime.produceState<Boolean?>(null, b) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hasModel3D(ctx, board3d(b)) }
                }
                if (hasModel == null) Box(Modifier.fillMaxWidth().height(120.dp))
                else if (hasModel == true) Model3D(board3d(b), Modifier.fillMaxWidth().height(120.dp))
                else Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Cpu, null, tint = C.Sub, modifier = Modifier.size(56.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(b.title, 15.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    if (sel) Icon(Lucide.Check, null, tint = C.Green, modifier = Modifier.size(18.dp))
                }
                if (here) T("подключена сейчас", 12.sp, C.Green)
                T(b.sub, 12.sp, C.Sub, maxLines = 4)
            }
        }
    }
    confirm?.let { b ->
        val lost = slots.filter { it.input !in b.inputs }
        CarAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Сменить плату на ${b.short}?") },
            text = { Text("На ${b.short} нет входов ${lost.joinToString { it.input }}. Эти датчики будут убраны: ${lost.joinToString { it.name }}.") },
            confirmButton = { androidx.compose.material3.TextButton({ SensorBox.setBoard(b); confirm = null }) { Text("Сменить") } },
            dismissButton = { androidx.compose.material3.TextButton({ confirm = null }) { Text("Отмена") } },
        )
    }
    Spacer(Modifier.height(14.dp))
    T("Что ещё понадобится", 16.sp, weight = FontWeight.SemiBold)
    Hint(Lucide.Cable, "USB-кабель для платы (с проводами данных, не только для зарядки)")
    Hint(Lucide.Plug, "Два клеммника (WAGO или винтовые): один для +5V, другой для массы всех датчиков")
    Hint(Lucide.Zap, "Провода 0,35–0,5 мм², термоусадка, резисторы, если датчик их просит (написано в его схеме)")
    Hint(Lucide.Usb, "Если на магнитоле один USB занят флешкой, нужен USB-хаб, лучше с отдельным питанием")
    if (board.lowVolt) {
        Hint(Lucide.TriangleAlert, "ESP32-S3 на 3,3 В: на каждый аналоговый вход делитель из резисторов 100 кОм и 150 кОм. Ещё один такой же " +
            "от 5V платы на пин 1: так плата меряет питание датчиков и считает их точно", C.Orange)
        Hint(Lucide.Cpu, "Для точных входов A4–A7 модуль ADS1115 (16 бит) на I2C: SDA на 8, SCL на 9, питание 3V3. Без него работают A0–A3 на АЦП самой платы")
        Hint(Lucide.Info, "Реле: берите модули, которые срабатывают от 3,3 В (с перемычкой High/Low или на транзисторе). Обычный модуль на 5 В от 3,3 В может не выключаться")
    }
    Spacer(Modifier.height(14.dp))
    BigButton("Дальше: прошивка", Lucide.ArrowRight, onNext)
}

/** The page's main action: full width, tall, in the accent colour. */
@Composable
internal fun BigButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().height(maxOf(LocalMinTap.current, LocalCar.current.secondary.lineDp() * 2f + 8.dp)).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) C.Purple else C.CardHi).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Color.White else C.Sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(text, 16.sp, if (enabled) Color.White else C.Sub, FontWeight.SemiBold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- right: step 3, nothing selected

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxIntro(onFlash: () -> Unit, onSelect: (String) -> Unit) {
    val board by SensorBox.board.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    val mine = slots.filter { it.input in board.inputs }
    val outs by kg.forester.sg.data.Relays.channels.collectAsState()
    val empty = mine.isEmpty() && outs.isEmpty()
    var howOpen by remember { mutableStateOf(empty) }
    if (!empty) {
        T("Ваша плата целиком", 18.sp, weight = FontWeight.SemiBold)
        SettingHint("Все датчики и выходы: что в какой пин, и общие клеммники питания и массы. Добавили или поменяли датчик — " +
            "плату перепрошивать не нужно, приложение пересчитывает всё само")
        Spacer(Modifier.height(8.dp))
        BoardOverview(board, mine, null)
        Spacer(Modifier.height(8.dp))
        PinTable(board, mine)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { howOpen = !howOpen }, verticalAlignment = Alignment.CenterVertically) {
            T("Как это работает", 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (howOpen) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
        }
        if (!howOpen) return
    } else T("Как это работает", 18.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    T("ЭБУ Subaru не знает давление масла, его настоящую температуру и температуру выхлопа. Эти датчики ставятся отдельно, " +
        "провода от них идут в маленькую плату Arduino, а плата втыкается в USB магнитолы рядом с кабелем ЭБУ. Приложение читает " +
        "и машину, и плату одновременно.", 14.sp, C.Sub)
    Spacer(Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowBox(Lucide.Thermometer, "Датчики", "масло, давление, выхлоп, ШДК")
        FlowArrow("провода")
        FlowBox(Lucide.Cpu, "Arduino", "Nano, Uno, Pro Micro, Mega")
        FlowArrow("USB")
        FlowBox(Lucide.MonitorSmartphone, "Магнитола", "это приложение")
        FlowArrow("USB", left = true)
        FlowBox(Lucide.Cable, "Кабель ЭБУ", "KKL или OpenPort")
    }
    Spacer(Modifier.height(16.dp))
    T("С чего начать", 16.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Step(1, "Купите плату и датчики", "Проще всего Arduino Nano. Датчики продаются под резьбу 1/8 NPT или 1/8 PT, к ним проставка под масляный фильтр. Модели с номерами есть в выборе датчика.")
    Step(2, "Залейте прошивку", "Одной кнопкой прямо с магнитолы: прошивка уже лежит в приложении.", onFlash)
    Step(3, "Подключите датчики и выберите их тут", "Слева «Добавить датчик»: выберите свой, и появится рисунок вашей платы, на нём цветом показано, какой провод в какой пин.")
    Spacer(Modifier.height(14.dp))
    if (empty) {
        BigButton("Добавить первый датчик", Lucide.Plus, { onSelect("add") })
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(C.Violet.copy(alpha = 0.12f))
                .border(1.dp, C.Violet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable { Repo.settings.demo = true; SensorBox.fillExample(); onSelect(board.analog.first()) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Lucide.Sparkles, null, tint = C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            FitText("Посмотреть на примере (демо, ничего паять не надо)", LocalCar.current.secondary, Modifier.weight(1f), C.Text, FontWeight.SemiBold, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
    }
    Hint(Lucide.TriangleAlert, "Плата питается от USB, 12 В на неё не подавайте. На входы платы больше 5 В нельзя.")
}

// ---------------------------------------------------------------- right: step 3, adding a sensor

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSensor(onAdded: (String) -> Unit) {
    val board by SensorBox.board.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    val free = board.inputs.filter { i -> SensorBox.inputBlocked(board, i) == null }
    if (free.isEmpty()) { T("Все входы платы заняты", 16.sp, C.Sub); return }
    var input by remember { mutableStateOf(free.first()) }
    T("Добавить датчик", 18.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    T("Куда подключаете", 14.sp, C.Sub)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        free.forEach { i ->
            Chip(when {
                i == "TC0" -> board.pinOf(i) + " · термопара"
                i.startsWith("TC") -> "$i · термопара ещё одна"
                i == "F0" -> board.pinOf(i) + " · частота"
                else -> i
            }, i == input, { input = i })
        }
    }
    // inputs not offered now, and why: no guessing why A4 is missing
    board.inputs.mapNotNull { i -> SensorBox.inputBlocked(board, i)?.takeIf { slots.none { s -> s.input == i } }?.let { "$i: $it" } }
        .takeIf { it.isNotEmpty() }?.let { T("Недоступно: " + it.joinToString("; "), 12.sp, C.Sub) }
    T(when {
        input.startsWith("TC") && input != "TC0" -> "Ещё одна термопара (по одной на цилиндр): свой модуль MAX31855 на тех же SCK и SO, свой CS на свободном пине выходов, он выберется сам"
        input == "TC0" -> "Сюда только термопара выхлопа через модуль MAX31855"
        input == "F0" -> "Сюда датчик с частотным выходом: этанол (flex fuel)"
        else -> "Аналоговый вход 0–5 В: температура, давление, ШДК, потенциометр"
    }, 13.sp, C.Sub)
    Spacer(Modifier.height(10.dp))
    androidx.compose.runtime.key(input) {
        PresetGallery(board, input, null) { p -> SensorBox.put(BoxSlot.of(input, p)); onAdded(input) }
    }
}

@Composable
private fun FlowBox(icon: ImageVector, title: String, sub: String) {
    Column(
        Modifier.width(roomFor(118.dp, 13f)).clip(RoundedCornerShape(12.dp)).background(C.CardHi).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        T(title, 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        T(sub, 12.sp, C.Sub, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun FlowArrow(label: String, left: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (left) Lucide.ArrowLeft else Lucide.ArrowRight, null, tint = C.Sub, modifier = Modifier.size(18.dp))
        T(label, 11.sp, C.Sub, maxLines = 1)
    }
}

@Composable
private fun Step(n: Int, title: String, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(4.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(C.Purple), contentAlignment = Alignment.Center) {
            T("$n", 14.sp, Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(title, 14.sp, weight = FontWeight.SemiBold)
            T(text, 12.sp, C.Sub)
        }
        if (onClick != null) Icon(Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
    }
}

@Composable
internal fun Hint(icon: ImageVector, text: String, color: Color = C.Sub) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        T(text, 12.sp, C.Sub)
    }
}

// ---------------------------------------------------------------- right: flashing the board

/** A file's name as the picker shows it. */
private fun fileName(ctx: android.content.Context, uri: android.net.Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "файл"

/**
 * «Своя прошивка»: the shipped files to take away (the .hex for this board, the sketch to change), and a .hex of one's
 * own, checked before it goes on (format, size, which board, whether it is the SensorBox) and after (does it answer).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnFirmware(board: BoxBoard) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by kg.forester.sg.flash.FirmwareFlasher.state.collectAsState()
    val info by SensorBox.info.collectAsState()
    var open by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var picked by remember(board) { mutableStateOf<Pair<String, kg.forester.sg.flash.FirmwareCheck>?>(null) }
    // the ESP32-S3 takes one image for address 0 (.bin), the AVR boards an Intel HEX
    val esp = board == BoxBoard.ESP32S3
    val asset = if (esp) kg.forester.sg.flash.FirmwareFlasher.ESP_ASSET else kg.forester.sg.flash.FirmwareFlasher.specs.getValue(board).asset
    val ext = if (esp) ".bin" else ".hex"
    val limit = if (esp) kg.forester.sg.flash.FirmwareCheck.MAX_BIN else kg.forester.sg.flash.FirmwareCheck.MAX_FILE
    fun copy(asset: String, uri: android.net.Uri?) {
        if (uri == null) return
        msg = runCatching {
            ctx.assets.open(asset).use { i -> ctx.contentResolver.openOutputStream(uri)!!.use { i.copyTo(it) } }
            "Сохранено: ${fileName(ctx, uri)}"
        }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("save: $it"); "Не удалось сохранить: ${kg.forester.sg.data.Failure.text(it)}" }
    }
    val saveHex = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { copy(asset, it) }
    val saveIno = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { copy("sensorbox/sensorbox.ino", it) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = fileName(ctx, uri)
        picked = name to runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { input ->
                // at most a byte past the limit: enough to tell it is too big without reading a film into memory
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= limit) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            if (bytes.size > limit) kg.forester.sg.flash.FirmwareCheck(null,
                listOf(false to "Файл больше ${limit / 1024 / 1024} МБ: это не прошивка для ${board.short}"), null, null)
            else if (esp) kg.forester.sg.flash.FirmwareCheck.ofEsp(bytes)
            else kg.forester.sg.flash.FirmwareCheck.of(String(bytes, Charsets.ISO_8859_1), board)
        }.getOrElse { kg.forester.sg.flash.FirmwareCheck(null, listOf(false to "Не удалось прочитать файл: ${kg.forester.sg.data.Failure.text(it)}"), null, null) }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        T("Своя прошивка: сохранить, изменить, залить", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
    }
    if (!open) return
    SettingHint("Готовая прошивка платы и её исходник: чтобы держать копию, поменять скетч под себя в Arduino IDE и залить обратно отсюда же.")
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Сохранить готовую прошивку ($ext) для ${board.short}", false, { saveHex.launch(asset.substringAfterLast('/')) }, Lucide.Download)
        Chip("Сохранить исходник sensorbox.ino", false, { saveIno.launch("sensorbox.ino") }, Lucide.FileCode)
        Chip("Залить свою прошивку ($ext)…", false, { pick.launch(arrayOf("*/*")) }, Lucide.Upload)
    }
    if (msg.isNotEmpty()) T(msg, 13.sp, if (msg.startsWith("Сохранено")) C.Green else C.Orange)
    T(if (esp) "Свой .bin собирается в Arduino IDE: Скетч, Экспорт бинарного файла. Берите sensorbox.ino.merged.bin: в нём и загрузчик, и таблица разделов."
        else "Свой .hex собирается в Arduino IDE: Скетч, Экспорт бинарного файла. Берите файл без with_bootloader в имени.", 12.sp, C.Sub)
    val p = picked ?: return
    val (name, chk) = p
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).border(1.dp, if (chk.ok) C.Border else C.Orange, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        T(name, 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
        chk.lines.forEach { (good, text) ->
            Row {
                Icon(if (good) Lucide.CircleCheck else Lucide.TriangleAlert, null, tint = if (good) C.Green else C.Orange, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                T(text, 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val working = st is kg.forester.sg.flash.FlashState.Working
            if (chk.ok) Chip(if (working) "Прошиваю…" else "Залить в ${board.short}", true, {
                if (!working && info?.via != "демо") kg.forester.sg.flash.FirmwareFlasher.start(ctx, board, chk.image, chk.bin)
            }, Lucide.Zap, Modifier.alpha(if (working || info?.via == "демо") 0.4f else 1f))
            Chip("Убрать файл", false, { picked = null }, Lucide.X)
        }
        if (info?.via == "демо") T("В демо заливать некуда: подключите настоящую плату", 12.sp, C.Sub)
        else if (chk.ok) SettingHint("Ход прошивки и ответ платы появятся выше, под большой кнопкой")
    }
}

/** The upload from the head unit: a check of what is on the cable, one big button, the progress, the next step. */
@Composable
private fun FlashHere(board: BoxBoard, onNext: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by kg.forester.sg.flash.FirmwareFlasher.state.collectAsState()
    val info by SensorBox.info.collectAsState()
    val on by SensorBox.enabled.collectAsState()
    val working = st is kg.forester.sg.flash.FlashState.Working
    // the screen stays on while the board is being written
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(working) { view.keepScreenOn = working; onDispose { view.keepScreenOn = false } }
    val real = info?.takeIf { it.via != "демо" }
    when {
        real != null -> Hint(Lucide.CircleCheck, "На USB: ${BoxBoard.of(real.board).short}, на ней прошивка ${real.fw}. Будет записана версия $BOX_FIRMWARE." +
            if (real.fw >= BOX_FIRMWARE) " Обновлять не обязательно." else "", C.Green)
        info?.via == "демо" -> Hint(Lucide.Info, "Сейчас демо: настоящей платы нет. Подключите плату к USB магнитолы, выйдите из демо и включите поиск.")
        !on -> Hint(Lucide.TriangleAlert, "Включите «искать на USB» вверху слева, чтобы приложение увидело плату. Прошить можно и так: вставьте плату в USB магнитолы.", C.Orange)
        else -> Hint(Lucide.Usb, "Плата не видна на USB как SensorBox: это нормально для новой платы без прошивки. Вставьте её в USB магнитолы и нажмите кнопку.")
    }
    Spacer(Modifier.height(8.dp))
    BigButton(if (working) "Прошиваю…" else "Прошить ${board.short} с магнитолы", Lucide.Download, {
        kg.forester.sg.flash.FirmwareFlasher.start(ctx, board)
    }, enabled = !working && info?.via != "демо")
    when (val s = st) {
        is kg.forester.sg.flash.FlashState.Working -> {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = C.Violet)
            Spacer(Modifier.height(6.dp))
            T(s.text, 14.sp)
        }
        is kg.forester.sg.flash.FlashState.Done -> {
            Spacer(Modifier.height(10.dp))
            val c = if (s.warn) C.Orange else C.Green
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.14f))
                    .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (s.warn) Lucide.TriangleAlert else Lucide.CircleCheck, null, tint = c, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                T(s.text, 14.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if (!s.warn) BigButton("Дальше: подключить датчики", Lucide.ArrowRight, onNext)
        }
        is kg.forester.sg.flash.FlashState.Failed -> {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Orange.copy(alpha = 0.14f))
                    .border(1.dp, C.Orange.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Icon(Lucide.TriangleAlert, null, tint = C.Orange, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                T(s.text, 14.sp, modifier = Modifier.weight(1f))
            }
        }
        else -> Unit
    }
    Spacer(Modifier.height(10.dp))
    if (board == BoxBoard.PROMICRO) Hint(Lucide.Info, "Pro Micro на время прошивки становится другим USB-устройством, и Android спросит доступ ещё раз. " +
        "Поставьте галочку «по умолчанию» и нажмите ОК в течение 5 секунд, тогда в следующий раз спрашивать не будет.")
    if (board == BoxBoard.ESP32S3) Hint(Lucide.Info, "ESP32-S3 прошивается через свой USB: если на плате два разъёма, нужен тот, что подписан USB (не UART или COM). " +
        "Если загрузчик не отвечает, зажмите BOOT, нажмите и отпустите RST, отпустите BOOT и прошейте ещё раз.")
    Hint(Lucide.Info, "Если прервалось (выдернули кабель, пропало питание), ничего страшного: загрузчик платы не трогается, просто прошейте ещё раз.")
    Hint(Lucide.Info, "Лучше прошивать, когда плата воткнута в магнитолу напрямую, без пассивного хаба.")
}

@Composable
private fun FlashGuide(onNext: () -> Unit) {
    val board by SensorBox.board.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var msg by remember { mutableStateOf("") }
    var pc by remember { mutableStateOf(false) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = runCatching {
            ctx.assets.open("sensorbox/sensorbox.ino").use { i -> ctx.contentResolver.openOutputStream(uri)!!.use { i.copyTo(it) } }
            "Сохранено. Перекиньте файл на компьютер"
        }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("save: $it"); "Не удалось сохранить: ${kg.forester.sg.data.Failure.text(it)}" }
    }
    T("Прошивка платы: ${board.title}", 18.sp, weight = FontWeight.SemiBold)
    SettingHint("Плата подключена к USB магнитолы, компьютер не нужен. Прошивка уже лежит в приложении.")
    Spacer(Modifier.height(8.dp))
    Hint(Lucide.CircleCheck, "Прошивают один раз. Прошивка одна на любые датчики: плата не знает, что где подключено, она отдаёт сырые " +
        "показания всех входов, а в градусы и бары их пересчитывает приложение. Выходы, дисплеи и пины термопар приложение само " +
        "передаёт плате при каждом подключении. Поменяли датчик или настройку — перепрошивать не нужно. Нужно только когда вышла " +
        "новая версия прошивки: приложение напишет об этом здесь и в шаге слева.", C.Green)
    Hint(Lucide.Info, "Модуль термопары TC0 и дисплей на I2C плата ищет при включении: после того как подключили их, переткните USB платы " +
        "(у дисплея есть кнопка «Найти ещё раз»).")
    Spacer(Modifier.height(10.dp))
    FlashHere(board, onNext)
    Spacer(Modifier.height(16.dp))
    OwnFirmware(board)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pc = !pc }, verticalAlignment = Alignment.CenterVertically) {
        T("Не получается с магнитолы? Прошить с компьютера", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (pc) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
    }
    if (!pc) return
    SettingHint("Делается один раз на компьютере (Windows, Mac или Linux), потом плата работает сама.")
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("Сохранить прошивку sensorbox.ino", false, { save.launch("sensorbox.ino") }, Lucide.Download)
        if (msg.isNotEmpty()) T(msg, 13.sp, C.Green)
    }
    SettingHint("Или скачайте её с GitHub: surtaeff/subaru-ssm-dashboard, папка hardware/sensorbox")
    Spacer(Modifier.height(10.dp))
    Step(1, "Поставьте Arduino IDE", "Бесплатная программа с сайта arduino.cc, раздел Software.")
    Step(2, "Откройте файл sensorbox.ino", "Двойным щелчком или Файл, Открыть. Программа спросит, можно ли положить его в папку sensorbox: нажмите ОК.")
    Step(3, "Подключите плату к компьютеру", "Обычным USB-кабелем (не только для зарядки, в нём должны быть провода данных).")
    Step(4, "Выберите плату и порт", "Инструменты, Плата, ${board.ide}. Затем Инструменты, Порт: тот, что появился после подключения платы.")
    Step(5, "Нажмите Загрузка", "Круглая кнопка со стрелкой вправо вверху. Внизу должно появиться «Загрузка завершена».")
    Step(6, "Проверьте", "Инструменты, Монитор порта, справа внизу скорость 115200. Напишите ? и нажмите Enter: должна прийти строка I SENSORBOX, а за ней побегут строки D t=…")
    Step(7, "Подключите к магнитоле", "Плату в USB магнитолы, в приложении откройте «Датчики и экраны». Android спросит разрешение на USB: поставьте галочку «по умолчанию» и ОК.")
    Spacer(Modifier.height(10.dp))
    T("Если не получается", 15.sp, weight = FontWeight.SemiBold)
    if (board == BoxBoard.NANO) {
        Hint(Lucide.CircleAlert, "Порт не появляется в Windows: у китайских Nano чип CH340, нужен драйвер CH340 (ищите CH341SER.EXE).")
        Hint(Lucide.CircleAlert, "Ошибка avrdude: not in sync: выберите Процессор, ATmega328P (Old Bootloader) и загрузите снова.")
    }
    if (board == BoxBoard.PROMICRO) {
        Hint(Lucide.CircleAlert, "Плата пропала из портов после загрузки: это нормально, она перезагружается. Если зависла, замкните RST на GND два раза быстро и сразу жмите Загрузка.")
        Hint(Lucide.CircleAlert, "Выбирайте версию 5 В / 16 МГц. Платы на 3,3 В для датчиков 0,5–4,5 В не подходят.")
    }
    if (board == BoxBoard.ESP32S3) {
        Hint(Lucide.CircleAlert, "Платы ESP32S3 нет в списке: Инструменты, Плата, Менеджер плат, найдите esp32 от Espressif и установите.")
        Hint(Lucide.CircleAlert, "Порт пропал после загрузки или не появился: подключите разъём USB (не UART/COM), зажмите BOOT, нажмите RST, отпустите BOOT и снова Загрузка.")
    }
    Hint(Lucide.CircleAlert, "Модуль термопары не нужен для прошивки: без него вход TC0 просто не появится.")
}

// ---------------------------------------------------------------- right: one input

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputDetails(input: String, onClose: () -> Unit) {
    val board by SensorBox.board.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    val slot = slots.firstOrNull { it.input == input }
    var choosing by remember(input) { mutableStateOf(slot == null) }
    if (slot == null || choosing) {
        PresetGallery(board, input, slot) { p ->
            SensorBox.put(BoxSlot.of(input, p))
            choosing = false
        }
        if (slot != null) {
            Spacer(Modifier.height(10.dp))
            Chip("Оставить ${slot.name}", false, { choosing = false })
        }
        return
    }
    val r = SensorBox.readings.collectAsState().value[input]
    val p = slot.p
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(board.pcb)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            T(board.pinOf(input), 14.sp, Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(slot.name, 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(p.short, 12.sp, C.Sub, maxLines = 1)
        }
        Chip("Другой датчик", false, { choosing = true }, Lucide.RefreshCw)
        Spacer(Modifier.width(6.dp))
        Chip("Убрать", false, {
            SensorBox.remove(input)
            offerUndo("Датчик «${slot.name}» убран") { SensorBox.put(slot) }
            onClose()
        }, Lucide.Trash2)
    }
    Spacer(Modifier.height(10.dp))
    // the module itself, where the sensor has one of its own
    when {
        p.kind == BoxKind.TC -> Model3D("max31855", Modifier.fillMaxWidth().height(170.dp))
        p.id == "pot" -> Model3D("pot", Modifier.fillMaxWidth().height(170.dp))
    }
    LiveValue(slot, r)
    if (input != "TC0" && input.startsWith("TC")) TcCsPicker(board, input)
    Spacer(Modifier.height(12.dp))
    val others = slots.filter { it.input != input && it.input in board.inputs }
    var whole by remember(input) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Как подключить", 16.sp, weight = FontWeight.SemiBold)
            T(if (whole) "Все датчики на плате сразу, этот ярче" else "Рисунок вашей платы сверху, USB слева. Цветом выделены пины, которые нужны", 12.sp, C.Sub)
        }
        if (others.isNotEmpty()) {
            Chip("Этот датчик", !whole, { whole = false })
            Spacer(Modifier.width(6.dp))
            Chip("Вся плата (${others.size + 1})", whole, { whole = true })
        }
    }
    Spacer(Modifier.height(6.dp))
    if (whole) {
        BoardOverview(board, slots.filter { it.input in board.inputs }, input)
        Spacer(Modifier.height(8.dp))
        PinTable(board, slots.filter { it.input in board.inputs })
    } else {
        WiringPicture(board, input, p, others,
            Modifier.fillMaxWidth().height((if (board.endPins.isNotEmpty() || p.wires.size > 3) 320 else 280).dp + if (others.isNotEmpty()) 16.dp else 0.dp),
            fault = r?.problem)
        Spacer(Modifier.height(8.dp))
        WiringSteps(board, input, p, others)
    }
    Spacer(Modifier.height(8.dp))
    p.tips.forEach { Hint(Lucide.Lightbulb, it) }
    BuyInfo(p)
    Spacer(Modifier.height(12.dp))
    SlotSettings(slot)
}

/** What to buy and where, from the catalogue: part numbers, thread, shops, how sure the numbers are, sources. */
@Composable
private fun BuyInfo(p: BoxPreset) {
    // no prices: they change every month
    if (p.partNumbers.isEmpty() && p.thread.isEmpty()) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(12.dp))
    T("Что купить", 16.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    @Composable
    fun Line(k: String, v: String) = Row(Modifier.padding(vertical = 2.dp)) {
        T(k, 12.sp, C.Sub, modifier = Modifier.width(roomFor(120.dp, 12f)))
        T(v, 12.sp, modifier = Modifier.weight(1f))
    }
    if (p.partNumbers.isNotEmpty()) Line("Номера", p.partNumbers.joinToString("; "))
    if (p.thread.isNotEmpty()) Line("Резьба", p.thread)
    if (p.buy.isNotEmpty()) Line("Где искать", p.buy)
    if (p.confidence.isNotEmpty()) Line("Точность данных", when (p.confidence) {
        "high" -> "проверено по даташиту"; "medium" -> "часть по даташитам, часть по инструкциям и форумам: проверьте на своей машине"
        else -> "приблизительно, проверьте сами"
    })
    if (p.sources.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        T("Источники", 12.sp, C.Sub)
        p.sources.take(4).forEach { url ->
            T(url.removePrefix("https://").removePrefix("www.").take(70), 12.sp, C.Violet, maxLines = 1,
                modifier = Modifier.clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(vertical = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetGallery(board: BoxBoard, input: String, current: BoxSlot?, onPick: (BoxPreset) -> Unit) {
    T("Что подключаете к ${board.pinOf(input)}?", 18.sp, weight = FontWeight.SemiBold)
    SettingHint("Выберите свой датчик или похожий. Цифры потом можно поправить")
    Spacer(Modifier.height(10.dp))
    val groups = BoxPresets.forInput(input).groupBy(::presetGroup)
    groups.entries.sortedBy { PRESET_GROUPS.indexOf(it.key).let { i -> if (i < 0) 99 else i } }.forEach { (g, list) ->
        T(g, 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p ->
                val sel = current?.preset == p.id
                Row(
                    Modifier.width(250.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                        .border(1.dp, if (sel) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(p), null, tint = C.Violet, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        T(p.title, 13.sp, weight = FontWeight.SemiBold, maxLines = 2)
                        T(p.short, 12.sp, C.Sub, maxLines = 2)
                    }
                }
            }
        }
    }
}

private val PRESET_GROUPS = listOf(
    "Температура масла", "Давление масла", "Температура антифриза, АКПП, редуктора", "Воздух на впуске", "Наддув (давление во впуске)",
    "Топливо", "Широкополосная лямбда (ШДК)", "Выхлоп", "Ток", "Тормоза", "Своё",
)

/** The section a sensor is listed under when choosing one for an input. */
private fun presetGroup(p: BoxPreset): String = when {
    p.id.startsWith("oil_t") || (p.role == "oil") -> "Температура масла"
    p.id.startsWith("oil_p") || p.role == "oilP" -> "Давление масла"
    p.id.startsWith("coolant") || p.id.startsWith("atf") || p.id.startsWith("diff") -> "Температура антифриза, АКПП, редуктора"
    p.id.startsWith("iat") || p.id.startsWith("tmap") -> "Воздух на впуске"
    p.id.startsWith("map") -> "Наддув (давление во впуске)"
    p.id.startsWith("fuel") || p.id.startsWith("flex") -> "Топливо"
    p.id.startsWith("afr") || p.role == "afr" -> "Широкополосная лямбда (ШДК)"
    p.id.startsWith("egt") || p.role == "egt" || p.id.startsWith("exh") -> "Выхлоп"
    p.id.startsWith("batt") -> "Ток"
    p.id.startsWith("brake") -> "Тормоза"
    else -> "Своё"
}

private fun kindIcon(p: BoxPreset): ImageVector = when (p.role) {
    "oil" -> Lucide.Thermometer
    "oilP" -> Lucide.Gauge
    "afr" -> Lucide.Flame
    "egt" -> Lucide.ThermometerSun
    "fuelLevel" -> Lucide.Fuel
    else -> when {
        p.id.startsWith("map") -> Lucide.Fan
        p.id.startsWith("batt") -> Lucide.Zap
        p.id.startsWith("iat") || p.id.startsWith("tmap") -> Lucide.Wind
        p.kind == BoxKind.NTC -> Lucide.Thermometer
        p.kind == BoxKind.FREQ -> Lucide.Leaf
        p.kind == BoxKind.PRESSURE || p.kind == BoxKind.RES -> Lucide.Gauge
        else -> Lucide.Cpu
    }
}

@Composable
private fun LiveValue(s: BoxSlot, r: kg.forester.sg.data.BoxReading?) {
    val info by SensorBox.info.collectAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T("Сейчас", 12.sp, C.Sub)
            when {
                info == null -> SettingHint("Плата не подключена. Значение появится, когда она будет на связи (или включите демо)")
                r?.value != null -> Row(verticalAlignment = Alignment.Bottom) {
                    val warn = (s.warnAbove != null && r.value > s.warnAbove) || (s.warnBelow != null && r.value < s.warnBelow)
                    T(valueText(s, r.value), 40.sp, if (warn) C.Orange else C.Text, FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    T(s.unit, 16.sp, C.Sub, modifier = Modifier.padding(bottom = 8.dp))
                }
                r?.problem != null -> {
                    T(r.problem.replaceFirstChar { it.uppercase() }, 18.sp, C.Orange, FontWeight.SemiBold)
                    T("Проверьте провод сигнала и массу по схеме ниже", 12.sp, C.Sub)
                }
                else -> T("Жду данные…", 14.sp, C.Sub)
            }
        }
        if (r != null && r.rawText.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
            T("на входе", 12.sp, C.Sub)
            T(r.rawText, 13.sp)
        }
    }
}

@Composable
internal fun WiringSteps(board: BoxBoard, input: String, p: BoxPreset, others: List<BoxSlot>) {
    var n = 0
    // 5 V and ground are one pin each for every sensor on the board: say so where it matters
    val power = others.filter { o -> o.p.wires.any { it.to == "5V" } || o.p.pullUp != null }
    val ground = others.filter { o -> o.p.wires.any { it.to == "GND" } }
    val needs5 = p.wires.any { it.to == "5V" } || p.pullUp != null
    // 3.3 V pins (the ESP32-S3): an analog signal of up to 5 V comes in through a divider
    val divided = board.lowVolt && input in board.analog
    val sigPin = if (board.onAds(input)) "вход ${board.pinOf(input).removePrefix("ADS ")} модуля ADS1115" else "пин ${board.pinOf(input)} платы"
    p.wires.forEach { w ->
        val target = when {
            w.to == "12V" -> "+12 В машины через предохранитель 5 А (не к плате!)"
            w.to == "5V" && power.isNotEmpty() -> "клеммник +5V (общий с: ${power.joinToString { it.name }})"
            w.to == "GND" && ground.isNotEmpty() -> "клеммник GND (общий с: ${ground.joinToString { it.name }})"
            w.to == "SIG" && divided -> "$sigPin через делитель (следующий шаг)"
            else -> "пин ${pinFor(board, input, w.to)} платы"
        }
        WireStep(++n, Color(w.color), "${w.label} → $target")
        if (w.to == "SIG" && divided) WireStep(++n, C.Orange, "Делитель на $sigPin: резистор 100 кОм от сигнального провода к пину, 150 кОм " +
            "от пина на GND, рядом конденсатор 100 нФ на GND. Пины ESP32-S3 на 3,3 В: 5 В напрямую их сожгут")
    }
    if (board.lowVolt && input == "F0") WireStep(++n, C.Orange, "Датчик с открытым коллектором (flex fuel, датчик Холла) прямо на пин ${board.freqPin}: " +
        "плата сама подтягивает его к 3,3 В. Датчик с выходом 5 В только через делитель 100k/150k")
    p.pullUp?.let {
        WireStep(++n, Color(WIRE_RED), "Резистор ${it}: одной ногой в " + (if (divided) "сигнальный провод датчика (до делителя)" else board.pinOf(input)) + ", другой в " +
            if (power.isNotEmpty()) "клеммник +5V" else "${board.v5} (к тому же пину можно подпаять провод питания)")
    }
    if (needs5 && power.isNotEmpty() || ground.isNotEmpty()) WireStep(++n, C.Violet,
        "Пин ${board.v5} и GND на плате общие для всех датчиков. Соберите плюсы в один клеммник (WAGO или винтовой), массы в другой, " +
            "и от каждого клеммника один провод к плате. Как это выглядит целиком, видно на «Вся плата»")
    WireStep(++n, C.Violet, "Плату USB-кабелем в магнитолу. Если магнитола ещё не видела плату, Android спросит разрешение: галочка «по умолчанию» и ОК")
}

@Composable
private fun WireStep(n: Int, color: Color, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            T("$n", 12.sp, if (color == Color(0xFFFACC15)) Color.Black else Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        T(text, 13.sp)
    }
}

/**
 * One more thermocouple (an EGT per cylinder): another MAX31855 on the same SCK and SO, its own CS on a free output
 * pin, picked here (the first free one was taken for it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TcCsPicker(board: BoxBoard, input: String) {
    val cs by SensorBox.tcCs.collectAsState()
    val cur = cs[input].orEmpty()
    val owners = SensorBox.pinOwners(board, "tc:$input")
    Spacer(Modifier.height(10.dp))
    T("Пин CS этого модуля", 15.sp, weight = FontWeight.SemiBold)
    SettingHint("Модули термопар висят на одних проводах SCK (${board.sck}) и SO (${board.miso}), у каждого свой CS. Первый, TC0, на ${board.cs}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        board.outPins.forEach { p ->
            val who = owners[p]
            Chip(p + (who?.let { " · занят: $it" } ?: ""), p == cur, { if (who == null) SensorBox.setTcCs(input, p) }, modifier = Modifier.alpha(if (who != null) 0.4f else 1f))
        }
    }
    if (cur.isEmpty()) T("Свободных пинов выходов нет: уберите один из выходов или дисплеев", 12.sp, C.Orange)
}

private fun pinFor(board: BoxBoard, input: String, to: String) = when (to) {
    "5V" -> board.v5
    "GND" -> "GND"
    "SIG" -> board.pinOf(input)
    "SCK" -> board.sck
    "SO" -> board.miso
    // a thermocouple's own CS: TC0 on the board's, TC1…TC3 on the pins picked for them
    "CS" -> if (input.startsWith("TC")) board.pinOf(input) else board.cs
    "SDA" -> board.sda
    "SCL" -> board.scl
    "MOSI" -> board.mosi
    "3V3" -> board.v33 ?: board.v5
    // a display's own pins come as "D4|D5": a TM1637's CLK and DIO, a colour screen's CS and DC
    "CLK", "LCS" -> input.substringBefore('|')
    "DIO", "DC" -> input.substringAfter('|')
    else -> to
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotSettings(s: BoxSlot) {
    T("Настройка", 16.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    var name by remember(s.input, s.preset) { mutableStateOf(s.name) }
    var unit by remember(s.input, s.preset) { mutableStateOf(s.unit) }
    var pMax by remember(s.input, s.preset) { mutableStateOf(num(s.pMax)) }
    var v0 by remember(s.input, s.preset) { mutableStateOf(num(s.a)) }
    var v5 by remember(s.input, s.preset) { mutableStateOf(num(s.a + 5 * s.b)) }
    var hi by remember(s.input, s.preset) { mutableStateOf(s.warnAbove?.let(::num) ?: "") }
    var lo by remember(s.input, s.preset) { mutableStateOf(s.warnBelow?.let(::num) ?: "") }
    val linear = s.p.kind == BoxKind.VOLT || s.p.kind == BoxKind.RATIO
    fun save() {
        val a = v0.toF()
        val top = v5.toF()
        SensorBox.put(s.copy(
            name = name.ifBlank { s.p.title }, unit = unit,
            pMax = pMax.toF()?.takeIf { it > 0f } ?: s.pMax,
            a = if (linear && a != null) a else s.a,
            b = if (linear && a != null && top != null && top != a) (top - a) / 5f else s.b,
            warnAbove = hi.toF(), warnBelow = lo.toF(),
        ))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxField("Название", name, Modifier.weight(1f), text = true) { name = it; save() }
        BoxField("Единицы", unit, Modifier.width(roomFor(110.dp, 13f)), text = true) { unit = it; save() }
    }
    Spacer(Modifier.height(6.dp))
    when (s.p.kind) {
        BoxKind.PRESSURE -> BoxField("Предел датчика (что он показывает при 4,5 В), ${s.unit}", pMax, Modifier.fillMaxWidth()) { pMax = it; save() }
        BoxKind.VOLT, BoxKind.RATIO -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxField("Показание при 0 В", v0, Modifier.weight(1f)) { v0 = it; save() }
            BoxField("Показание при 5 В", v5, Modifier.weight(1f)) { v5 = it; save() }
        }
        BoxKind.NTC -> T("Термистор с подтяжкой ${s.p.pullUp ?: "2,2 кОм"}: таблица датчика уже внутри, настраивать нечего.", 12.sp, C.Sub)
        BoxKind.RES -> T("Резистивный датчик с подтяжкой ${s.p.pullUp ?: ""}: таблица датчика уже внутри, настраивать нечего.", 12.sp, C.Sub)
        BoxKind.TC -> T("Термопара: модуль сам отдаёт градусы, настраивать нечего.", 12.sp, C.Sub)
        BoxKind.FREQ -> T("50 Гц это 0 % этанола, 150 Гц это 100 %.", 12.sp, C.Sub)
        BoxKind.OUT -> Unit
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxField("Предупреждать выше", hi, Modifier.weight(1f)) { hi = it; save() }
        BoxField("Предупреждать ниже", lo, Modifier.weight(1f)) { lo = it; save() }
    }
    Spacer(Modifier.height(10.dp))
    T("Для чего этот датчик", 14.sp)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxRoles.all.forEach { (id, title) ->
            Chip(title, s.role == id, {
                // one input per role (the guard reads the first it finds); exhaust gas may have one per cylinder: the hottest counts
                if (id != "egt") SensorBox.slots.value.filter { it.role == id && it.input != s.input && id.isNotEmpty() }.forEach { SensorBox.put(it.copy(role = "")) }
                SensorBox.put(s.copy(role = id))
            })
        }
    }
    Spacer(Modifier.height(4.dp))
    T(BoxRoles.meaning(s.role), 12.sp, C.Sub)
    if (s.role == "fuelLevel") {
        FuelLineCalibration(s.input)
    }
    Spacer(Modifier.height(10.dp))
    var home by remember(s.input) { mutableStateOf(SensorBox.homeCard(s.input)) }
    var homeMsg by remember(s.input) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip(if (home != null) "На главном: плитка «Датчик $home»" else "Показать на главном экране", home != null, {
            val k = SensorBox.showOnHome(s.input)
            home = k
            homeMsg = if (k == null) "Все шесть плиток «Датчик» заняты: освободите одну в редакторе главного экрана" else ""
        }, Lucide.LayoutGrid)
    }
    if (homeMsg.isNotEmpty()) T(homeMsg, 12.sp, C.Orange)
}

/**
 * «Тарировка по шагам» of the fuel gauge wire, as with Multitronics: the tank almost empty, set the litres in it and
 * mark; pour a step at the pump, «+ шаг», wait for the volts to settle, mark again; and so on to full.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FuelLineCalibration(input: String) {
    val pts by kg.forester.sg.data.FuelLine.points.collectAsState()
    val litres by kg.forester.sg.data.FuelLine.litres.collectAsState()
    val volts = SensorBox.readings.collectAsState().value[input]?.value
    val tank = Repo.settings.tankL.toFloat()
    val known = Repo.settings.fuelLevelKnown
    var manual by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T("Тарировка бака: сама", 15.sp, weight = FontWeight.SemiBold)
        SettingHint("Залейте полный бак и отметьте это на экране «Расход» (Заправка, галочка «полный бак»). Дальше приложение знает, " +
            "сколько сожгло, и на каждой остановке от 15 секунд (светофор, пробка, стоянка с мотором) само ставит точку " +
            "«напряжение = литры», примерно через каждые 3 л. За один бак шкала готова, следующие баки её уточняют.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T("Сейчас на проводе " + (volts?.let { "%.2f В".format(Locale("ru"), it) } ?: "—"), 13.sp)
            val span = if (pts.size >= 2) pts.maxOf { it.second } - pts.minOf { it.second } else 0f
            T("точек ${pts.size}" + if (pts.size >= 2) ", от %.0f до %.0f л".format(pts.minOf { it.second }, pts.maxOf { it.second }) else "", 13.sp, C.Sub)
            if (tank > 0 && pts.size >= 2) T("${(span / tank * 100).toInt()} % бака", 13.sp, if (span >= tank * 0.8f) C.Green else C.Violet)
        }
        when {
            !known -> T("Ждём полного бака: пока уровень не известен точно, точки не ставятся", 12.sp, C.Orange)
            pts.size < 2 -> T("Полный бак отмечен: точки появятся на первых остановках", 12.sp, C.Violet)
            !kg.forester.sg.data.FuelLine.consistent(pts) -> T("Точки идут вразнобой: проверьте провод (напряжение должно меняться вместе с уровнем) " +
                "или сотрите точки и начните с полного бака", 12.sp, C.Orange)
            else -> T("Остаток в баке считается по точкам там, где они есть" + (volts?.let { v ->
                kg.forester.sg.data.FuelLine.covered(v, tank)?.let { " · сейчас %.0f л".format(it) } } ?: ""), 12.sp, C.Green)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (manual) "Скрыть ручную" else "Поправить вручную", false, { manual = !manual }, Lucide.Pencil)
            if (pts.isNotEmpty()) Chip("Стереть точки", false, {
                val keep = pts
                kg.forester.sg.data.FuelLine.clear()
                offerUndo("Точки тарировки стёрты") {
                    kg.forester.sg.data.FuelLine.points.value = keep
                    Repo.settings.fuelLinePoints = kg.forester.sg.data.FuelLine.format(keep)
                }
            }, Lucide.Trash2)
        }
        if (manual) {
            SettingHint("Если знаете, сколько сейчас в баке (например, только что залили до полного), поставьте точку сами")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("В баке", 13.sp, C.Sub, modifier = Modifier.align(Alignment.CenterVertically))
                Chip("−5", false, { kg.forester.sg.data.FuelLine.litres.value = (litres - 5f).coerceAtLeast(0f) })
                Chip("−1", false, { kg.forester.sg.data.FuelLine.litres.value = (litres - 1f).coerceAtLeast(0f) })
                T("%.0f л".format(litres), 18.sp, weight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 6.dp))
                Chip("+1", false, { kg.forester.sg.data.FuelLine.litres.value = (litres + 1f).let { if (tank > 0) it.coerceAtMost(tank) else it } })
                Chip("+5", false, { kg.forester.sg.data.FuelLine.litres.value = (litres + 5f).let { if (tank > 0) it.coerceAtMost(tank) else it } })
                if (tank > 0) Chip("Полный (${tank.toInt()} л)", false, { kg.forester.sg.data.FuelLine.litres.value = tank })
                Chip(volts?.let { "Запомнить: %.2f В = %.0f л".format(Locale("ru"), it, litres) } ?: "Нет показаний", false,
                    { volts?.let { kg.forester.sg.data.FuelLine.mark(it) } }, Lucide.MapPin)
            }
            if (pts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pts.forEach { p -> Chip("%.2f В · %.0f л".format(Locale("ru"), p.first, p.second), false, { kg.forester.sg.data.FuelLine.remove(p) }, Lucide.X) }
            }
        }
    }
}

private fun num(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.3f".format(Locale.US, v).trimEnd('0').trimEnd('.')
private fun String.toF() = replace(',', '.').trim().toFloatOrNull()

@Composable
private fun BoxField(label: String, value: String, modifier: Modifier, text: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        label = { FieldLabel(label) },
        keyboardOptions = if (text) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
    )
}

// ---------------------------------------------------------------- the wiring picture

private class Pin(val name: String, val x: Float, val y: Float, val side: Int) // side: -1 top row, 1 bottom row, 0 end

/**
 * The board as it looks from above (USB at the left, both pin rows labelled as printed on it), the sensor at the
 * right, and a coloured wire from each pin used to the sensor's terminal; a pull-up resistor where one is needed.
 */
@Composable
fun WiringPicture(board: BoxBoard, input: String, p: BoxPreset, others: List<BoxSlot>, modifier: Modifier, fault: String? = null) {
    val tm = rememberTextMeasurer()
    val top = rememberBoardTop(board3d(board))
    val module = rememberBoardTop(module3d(p))
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0F1A))) {
        if (top.loaded && module.loaded) drawWiring(tm, board, input, p, others, top.top, module.top, fault)
    }
}

/** A pin on the board's top view: its name, where it is (0..1 of the image) and which way its wire leaves. */
class TopPin(val name: String, val fx: Float, val fy: Float, val side: Int)

/**
 * The board's real top view (tools/boards3d/render_board.py --top): the rendered model seen from straight above,
 * USB at the left, and where each header pin is on it, so the wires start at the real holes.
 */
class BoardTop(val img: androidx.compose.ui.graphics.ImageBitmap, val w: Int, val h: Int, val pitch: Float, val pins: List<TopPin>) {
    companion object {
        private val cache = HashMap<String, BoardTop?>()
        /** Reads a JSON and decodes a picture the first time: off the main thread. */
        fun load(ctx: android.content.Context, id: String): BoardTop? = synchronized(cache) { cache.getOrPut(id) {
            runCatching {
                val j = org.json.JSONObject(ctx.assets.open("boards3d/$id/top.json").bufferedReader().readText())
                val bmp = ctx.assets.open("boards3d/$id/top.webp").use { android.graphics.BitmapFactory.decodeStream(it) }
                val a = j.getJSONArray("pins")
                val sz = j.getJSONArray("size")
                BoardTop(bmp.asImageBitmap(), sz.getInt(0), sz.getInt(1), j.getDouble("pitch").toFloat(), List(a.length()) { i ->
                    a.getJSONArray(i).let { r -> TopPin(r.getString(0), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getInt(3)) }
                })
            }.getOrNull()
        } }
    }
}

/** [loaded] false: not read yet, so nothing to draw; true and [top] null: there is no such picture. */
class BoardTopRead(val loaded: Boolean, val top: BoardTop?)

/** The top view [id] (null: none wanted), read off the main thread. */
@Composable
fun rememberBoardTop(id: String?): BoardTopRead {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val read by androidx.compose.runtime.produceState<Pair<String?, BoardTop?>?>(null, id) {
        value = id to id?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BoardTop.load(ctx, it) } }
    }
    val v = read
    return if (v != null && v.first == id) BoardTopRead(true, v.second) else BoardTopRead(false, null)
}

private val SIGNALS = setOf("SIG", "SCK", "SO", "CS")
private val TAKEN = Color(0xFF64748B)

/** [pitch]: one pin step in pixels on a real top view (0 on the drawn board). */
private class BoardGeo(val bl: Float, val bt: Float, val br: Float, val bb: Float, val pins: List<Pin>, val pitch: Float = 0f)

/**
 * The board from above around height [cy]: its real top view when there is one ([top]), else a drawn one (the PCB,
 * the USB plug, the chip); the cable to the head unit; the pin positions (drawn by [drawPins]).
 */
private fun DrawScope.drawBoard(tm: TextMeasurer, board: BoxBoard, cy: Float, widthShare: Float, top: BoardTop? = null): BoardGeo {
    val d = density
    fun dp(v: Float) = v * d
    val bl = dp(46f)
    val bw = (size.width * widthShare).coerceAtMost(dp(470f))
    if (top != null) {
        val ih = bw * top.h / top.w
        val it0 = cy - ih / 2
        drawLine(Color(0xFF64748B), Offset(dp(4f), cy), Offset(bl + dp(4f), cy), dp(5f), StrokeCap.Round)
        text(tm, "USB", Offset(dp(6f), cy + dp(8f)), 10.5f, Color(0xFF94A3B8))
        text(tm, "к магнитоле", Offset(dp(4f), cy + dp(20f)), 10f, Color(0xFF64748B))
        drawImage(top.img, dstOffset = androidx.compose.ui.unit.IntOffset(bl.toInt(), it0.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(bw.toInt(), ih.toInt()))
        val pins = top.pins.map { Pin(it.name, bl + it.fx * bw, it0 + it.fy * ih, it.side) }
        return BoardGeo(bl, it0, bl + bw, it0 + ih, pins, top.pitch * bw)
    }
    val bh = dp(104f)
    val bt = cy - bh / 2
    val br = bl + bw
    val bb = bt + bh
    drawRoundRect(Color(board.pcb), Offset(bl, bt), Size(bw, bh), CornerRadius(dp(6f)))
    // USB connector and the cable to the head unit
    drawRect(Color(0xFFB8BEC8), Offset(bl - dp(14f), cy - dp(10f)), Size(dp(20f), dp(20f)))
    drawLine(Color(0xFF64748B), Offset(dp(4f), cy), Offset(bl - dp(14f), cy), dp(5f), StrokeCap.Round)
    text(tm, "USB", Offset(dp(6f), cy + dp(14f)), 10.5f, Color(0xFF94A3B8))
    text(tm, "к магнитоле", Offset(dp(4f), cy + dp(26f)), 10f, Color(0xFF64748B))
    // the chip and the board's name
    drawRoundRect(Color(0xFF111111), Offset(bl + bw * 0.42f, cy - dp(10f)), Size(dp(32f), dp(32f)), CornerRadius(dp(2f)))
    text(tm, board.title.substringBefore(" /").uppercase(), Offset(bl + bw * 0.42f + dp(40f), cy - dp(8f)), 10.5f, Color.White.copy(alpha = 0.85f), bold = true)
    fun row(names: List<String>, y: Float, side: Int, from: Float, to: Float) = names.mapIndexed { i, n ->
        Pin(n, from + (to - from) * (i + 0.5f) / names.size, y, side)
    }
    val endW = if (board.endPins.isNotEmpty()) dp(26f) else 0f
    val pins = row(board.topRow, bt + dp(8f), -1, bl + dp(10f), br - dp(6f) - endW) +
        row(board.bottomRow, bb - dp(8f), 1, bl + dp(10f), br - dp(6f) - endW) +
        board.endPins.mapIndexed { i, n -> Pin(n, br - dp(9f), bt + dp(30f) + i * dp(15f), 0) }
    return BoardGeo(bl, bt, br, bb, pins)
}

/**
 * Pads and their printed labels: [used] ones bright in their wire's colour, [taken] ones violet, the rest dim. On a
 * real top view ([pitch] > 0) the board shows its own pins: only the used and taken ones get a ring and a label.
 */
private fun DrawScope.drawPins(tm: TextMeasurer, pins: List<Pin>, used: Map<Pin, Color>, taken: Set<Pin>, pitch: Float = 0f) {
    val d = density
    if (pitch > 0f) {
        val r = pitch * 0.48f
        for (pin in pins) {
            val c = used[pin] ?: if (pin in taken) TAKEN else continue
            val at = Offset(pin.x, pin.y)
            drawCircle(Color.Black.copy(alpha = 0.55f), r * 1.35f, at)
            drawCircle(c, r * 1.15f, at, style = Stroke(r * 0.55f))
            drawCircle(c.copy(alpha = 0.35f), r * 0.75f, at)
            // a pin another sensor uses: struck through
            if (!used.containsKey(pin)) drawLine(c, at + Offset(-r, r), at + Offset(r, -r), r * 0.45f)
            val bold = used.containsKey(pin)
            when (pin.side) {
                -1 -> vtext(tm, pin.name, Offset(pin.x, pin.y + r * 1.6f), 9.5f, c, bold, down = true, backing = true)
                1 -> vtext(tm, pin.name, Offset(pin.x, pin.y - r * 1.6f), 9.5f, c, bold, down = false, backing = true)
                // the end header's pins sit a pitch apart, too close for labels: their names go into the tags outside
                else -> Unit
            }
        }
        return
    }
    for (pin in pins) {
        val c = used[pin] ?: if (pin in taken) TAKEN else null
        val at = Offset(pin.x, pin.y)
        if (c != null) {
            drawCircle(c, 6.5f * d, at)
            drawCircle(Color(0xFFE5C07B), 3f * d, at)
        } else drawCircle(Color(0xFFE5C07B).copy(alpha = 0.75f), 2.6f * d, at)
        val labelColor = c ?: Color.White.copy(alpha = 0.5f)
        val size = if (c != null) 9.5f else 7.5f
        when (pin.side) {
            -1 -> vtext(tm, pin.name, Offset(pin.x, pin.y + 8f * d), size, labelColor, c != null, down = true)
            1 -> vtext(tm, pin.name, Offset(pin.x, pin.y - 8f * d), size, labelColor, c != null, down = false)
            else -> text(tm, pin.name, Offset(pin.x - 30f * d, pin.y - 6f * d), size, labelColor, c != null)
        }
    }
}

/** The module with a real top view a sensor or output connects through, if any. */
fun module3d(p: BoxPreset): String? = when {
    p.kind == BoxKind.TC -> "max31855"
    p.id == "out_RELAY" -> "relay1"
    p.id == "out_MOSFET" -> "mosfet"
    p.id == "out_SSR" -> "ssr"
    p.id == "out_COIL12" -> "coil12kit"
    p.id.startsWith("disp_") -> p.id.removePrefix("disp_")
    else -> null
}

/** The name printed on the module for a wire to [to]. */
private fun moduleTerminal(p: BoxPreset, to: String) = if (p.id.startsWith("disp_"))
    kg.forester.sg.data.DispKind.entries.firstOrNull { "disp_${it.module}" == p.id }?.let { kg.forester.sg.data.Displays.terminal(it, to) } ?: to
else when (p.id) {
    "out_RELAY" -> when (to) { "5V" -> "VCC"; "SIG" -> "IN"; else -> to }
    "out_SSR" -> if (to == "SIG") "3+" else "4-"
    else -> if (to == "5V") "VCC" else to
}

private fun DrawScope.drawWiring(tm: TextMeasurer, board: BoxBoard, input: String, p: BoxPreset, others: List<BoxSlot>, top: BoardTop? = null,
                                 module: BoardTop? = null, fault: String? = null) {
    val d = density
    fun dp(v: Float) = v * d
    val w = size.width
    val h = size.height

    val g = drawBoard(tm, board, h / 2 - dp(6f), 0.52f, top)
    val bt = g.bt
    val br = g.br
    val bb = g.bb
    val pins = g.pins

    // which pin each wire goes to: GND on the same side as the signal, it reads better
    val sigPin = pins.firstOrNull { it.name == board.pinOf(input) }
    val v5Pin = pins.firstOrNull { it.name == board.v5 }
    // a pull-up is drawn as a bridge from the signal pin to the 5 V pin, just off the board: no other wire may leave between them
    val bridge = if (p.pullUp != null && sigPin != null && v5Pin != null && sigPin.side == v5Pin.side && sigPin.side != 0) sigPin to v5Pin else null
    fun inBridge(pin: Pin) = bridge != null && pin.side == bridge.first.side &&
        pin.x > minOf(bridge.first.x, bridge.second.x) && pin.x < maxOf(bridge.first.x, bridge.second.x)
    fun pinNamed(name: String) = pins.filter { it.name == name }.let { l ->
        l.firstOrNull { it.side == (sigPin?.side ?: 1) && !inBridge(it) } ?: l.firstOrNull { !inBridge(it) } ?: l.firstOrNull()
    }
    class W(val label: String, val pin: Pin?, val color: Color, val external: Boolean, val to: String)
    val wires = p.wires.map { wi ->
        W(terminalLabel(wi.to, p), if (wi.to == "12V") null else pinNamed(pinFor(board, input, wi.to)), Color(wi.color), wi.to == "12V", wi.to)
    }

    val used = wires.mapNotNull { it.pin }.associateWith { pin -> wires.first { it.pin == pin }.color } +
        (bridge?.let { mapOf(it.second to Color(WIRE_RED)) } ?: emptyMap())
    // pins other sensors already use: violet, so nobody wires two sensors to one input
    val takenNames = others.flatMap { o -> o.p.wires.filter { it.to in SIGNALS }.map { pinFor(board, o.input, it.to) } }.toSet()
    val taken = pins.filter { it.name in takenNames && it !in used }.toSet()
    drawPins(tm, pins, used, taken, g.pitch)
    if (taken.isNotEmpty()) {
        drawCircle(TAKEN, dp(4f), Offset(dp(12f), h - dp(12f)))
        text(tm, "серые зачёркнутые пины заняты другими датчиками", Offset(dp(20f), h - dp(19f)), 10.5f, Color(0xFF94A3B8))
    }

    // --- the sensor: the module's real top view with its own pins when there is one, else a drawn box
    val modPins = module?.let { m -> wires.all { moduleTerminal(p, it.to) in m.pins.map { t -> t.name } } }
    val sensorW = dp(128f)
    var sl = w - sensorW - dp(if (p.kind == BoxKind.TC || p.kind == BoxKind.PRESSURE || p.kind == BoxKind.NTC || p.kind == BoxKind.RES || p.id == "out_COIL12" || p.id == "pot") 58f else 12f)
    val toSensor = wires
    // terminals in the order the wires arrive: top lanes first (outermost first), then end pins, then bottom (innermost first)
    // the +12 V from the car comes down from above, closest to the sensor: its terminal is the first
    val top = toSensor.filter { it.external } + toSensor.filter { it.pin?.side == -1 }.sortedBy { it.pin!!.x }
    val end = toSensor.filter { it.pin?.side == 0 }
    val bottom = toSensor.filter { it.pin?.side == 1 }.sortedByDescending { it.pin!!.x }
    val order = top + end + bottom
    val gap = dp(24f)
    val termY: Map<W, Float>
    val termX = HashMap<W, Float>()
    if (module != null && modPins == true) {
        var mw = (w * 0.36f).coerceAtMost(dp(260f))
        var mh = mw * module.h / module.w
        if (mh > h - dp(16f)) { mh = h - dp(16f); mw = mh * module.w / module.h }
        val mx = w - mw - dp(8f)
        val my = h / 2 - mh / 2
        drawImage(module.img, dstOffset = androidx.compose.ui.unit.IntOffset(mx.toInt(), my.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(mw.toInt(), mh.toInt()))
        val r = module.pitch * mw * 0.5f
        val ys = HashMap<W, Float>()
        val termNames = LinkedHashMap<W, String>()
        for (wi in wires) {
            val t = module.pins.firstOrNull { it.name == moduleTerminal(p, wi.to) } ?: continue
            val x = mx + t.fx * mw
            val y = my + t.fy * mh
            ys[wi] = y
            termX[wi] = x
            drawCircle(Color.Black.copy(alpha = 0.55f), r * 1.4f, Offset(x, y))
            drawCircle(wi.color, r * 1.15f, Offset(x, y), style = Stroke(r * 0.55f))
            termNames[wi] = t.name
        }
        termY = ys
        sl = (termX.values.minOrNull() ?: mx) - dp(6f)
        // the terminal's name above where its wire arrives
        for ((wi, nm) in termNames) {
            val x = termX[wi]!!
            val y = termY[wi]!!
            val l = tm.measure(kg.forester.sg.data.tr(nm), TextStyle(fontSize = 10.5.sp, color = if (wi.color == Color(WIRE_BLACK)) Color(0xFFE5E7EB) else wi.color, fontWeight = FontWeight.Bold))
            val tl = Offset(x - r * 1.5f - l.size.width - dp(3f), y - l.size.height - dp(2f))
            drawRoundRect(Color(0xE60B0F1A), tl - Offset(dp(2f), dp(1f)), Size(l.size.width + dp(4f), l.size.height + dp(2f)), CornerRadius(dp(3f)))
            drawText(l, topLeft = tl)
        }
    } else {
        val sh = gap * (order.size + 1) + dp(18f)
        val st = (h / 2 - sh / 2).coerceAtLeast(dp(6f))
        drawRoundRect(Color(0xFF1F2937), Offset(sl, st), Size(sensorW, sh), CornerRadius(dp(8f)))
        drawRoundRect(Color(0xFF475569), Offset(sl, st), Size(sensorW, sh), CornerRadius(dp(8f)), style = Stroke(dp(1f)))
        text(tm, sensorTitle(p), Offset(sl + dp(8f), st + dp(4f)), 10.5f, Color.White, bold = true)
        termY = order.mapIndexed { i, wi -> wi to st + dp(18f) + gap * (i + 1) }.toMap()
        order.forEach { wi ->
            val y = termY[wi]!!
            drawCircle(Color(0xFFCBD5E1), dp(3.5f), Offset(sl, y))
            text(tm, wi.label, Offset(sl + dp(8f), y - dp(7f)), 10.5f, Color(0xFFCBD5E1))
        }
        drawSensorBody(tm, p, sl + sensorW, st, sh)
    }

    // --- the wires: out of the board to a lane, along it, down or up to the terminal
    val stroke = dp(3f)
    // the wires a fault the box reports points at: dashed orange, with a word on where to look
    val faultWires: Set<String> = when {
        fault == null -> emptySet()
        fault.startsWith("обрыв") -> if (p.kind == BoxKind.NTC || p.kind == BoxKind.RES) setOf("SIG", "GND") else setOf("SIG")
        fault.contains("массу") -> setOf("SIG", "GND")
        fault.contains("5 В") -> setOf("SIG", "5V")
        p.kind == BoxKind.TC -> setOf("SO", "SCK", "CS")
        else -> setOf("SIG")
    }
    fun path(pts: List<Offset>, c: Color, to: String = "") {
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        val bad = to in faultWires
        val black = c == Color(WIRE_BLACK)
        drawPath(path, if (black) Color(0xFF9CA3AF) else Color.Black.copy(alpha = 0.6f), style = Stroke(stroke + dp(2f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, if (bad) C.Orange else if (black) Color(0xFF111111) else c, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round,
            pathEffect = if (bad) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dp(7f), dp(5f))) else null))
    }
    // a numbered disc on each wire, the same number as its line in the steps under the picture
    fun badge(n: Int, at: Offset, c: Color) {
        drawCircle(Color.Black.copy(alpha = 0.6f), dp(10f), at)
        drawCircle(c, dp(8.5f), at)
        val l = tm.measure("$n", TextStyle(fontSize = 12.sp, color = if (c == Color(0xFFFACC15) || c == Color(0xFFFBBF24) || c == Color(0xFFA3E635)) Color.Black else Color.White, fontWeight = FontWeight.Bold))
        drawText(l, topLeft = at - Offset(l.size.width / 2f, l.size.height / 2f))
    }
    val badges = ArrayList<Triple<Int, Offset, Color>>()
    var faultAt: Offset? = null
    val topWires = wires.filter { it.pin?.side == -1 }.sortedByDescending { it.pin!!.x } // innermost lane: the rightmost pin
    val bottomWires = wires.filter { it.pin?.side == 1 }.sortedByDescending { it.pin!!.x }
    val laneY = HashMap<W, Float>()
    // lanes leave room for the pull-up bridge and its label on its side
    fun lanes(list: List<W>, sign: Int, edge: Float) {
        val start = if (bridge != null && bridge.first.side == sign) dp(34f) else dp(14f)
        list.forEachIndexed { i, wi -> laneY[wi] = edge + sign * (start + i * dp(13f)) }
    }
    if (termX.isNotEmpty()) {
        // fixed terminals: the wire ending highest gets the outermost lane above and turns furthest right, and the
        // other way round below, so lanes and turns nest instead of crossing
        lanes(topWires.sortedByDescending { termY[it] }, -1, bt)
        lanes(bottomWires.sortedBy { termY[it] }, 1, bb)
    } else {
        lanes(topWires, -1, bt)
        lanes(bottomWires, 1, bb)
    }
    // x where each wire turns towards its terminal: outer lanes turn closest to the sensor
    val turnX = HashMap<W, Float>()
    if (termX.isNotEmpty()) {
        // fixed terminals: the wire ending highest turns furthest right (from above), the lowest (from below), so none cross
        topWires.sortedBy { termY[it] }.forEachIndexed { i, wi -> turnX[wi] = sl - dp(14f) - i * dp(11f) }
        bottomWires.sortedByDescending { termY[it] }.forEachIndexed { i, wi -> turnX[wi] = sl - dp(14f) - i * dp(11f) }
        end.sortedBy { termY[it] }.forEachIndexed { i, wi -> turnX[wi] = sl - dp(14f) - (topWires.size + i) * dp(11f) }
    } else {
        topWires.sortedBy { laneY[it] }.forEachIndexed { i, wi -> turnX[wi] = sl - dp(14f) - i * dp(11f) }
        bottomWires.sortedByDescending { laneY[it] }.forEachIndexed { i, wi -> turnX[wi] = sl - dp(14f) - i * dp(11f) }
        end.forEachIndexed { i, wi -> turnX[wi] = sl - dp(14f) - (topWires.size + i) * dp(11f) }
    }

    for (wi in wires) {
        val pin = wi.pin
        if (wi.external && termX[wi] != null) {
            // the car's +12 V comes down from above straight onto the module's terminal
            val ty = termY[wi]!!
            val ex = termX[wi]!!
            val x = ex - dp(16f)
            path(listOf(Offset(x, dp(4f)), Offset(x, ty), Offset(ex, ty)), c = wi.color)
            text(tm, "+12 В через предохранитель", Offset((x - dp(170f)).coerceAtLeast(br + dp(4f)), dp(4f)), 10.5f, wi.color)
            badges += Triple(wires.indexOf(wi) + 1, Offset(x, (dp(4f) + ty) / 2), wi.color)
            continue
        }
        if (wi.external) {
            val ty = termY[wi] ?: continue
            val x = sl - dp(6f)
            path(listOf(Offset(x, dp(4f)), Offset(x, ty), Offset(sl, ty)), c = wi.color)
            text(tm, "+12 В через предохранитель", Offset((x - dp(170f)).coerceAtLeast(br + dp(4f)), dp(4f)), 10.5f, wi.color)
            badges += Triple(wires.indexOf(wi) + 1, Offset(x, (dp(4f) + ty) / 2), wi.color)
            continue
        }
        if (pin == null) continue
        val ty = termY[wi] ?: continue
        val tx = turnX[wi] ?: (sl - dp(14f))
        val ex = termX[wi] ?: sl
        if (pin.side == 0) path(listOf(Offset(pin.x, pin.y), Offset(tx, pin.y), Offset(tx, ty), Offset(ex, ty)), c = wi.color, to = wi.to)
        else {
            val ly = laneY[wi]!!
            path(listOf(Offset(pin.x, pin.y), Offset(pin.x, ly), Offset(tx, ly), Offset(tx, ty), Offset(ex, ty)), c = wi.color, to = wi.to)
        }
        // on the long run along the board, clear of the terminals at the far end
        val mid = if (pin.side == 0) Offset((pin.x + tx) / 2, pin.y) else Offset(pin.x + (tx - pin.x) * 0.55f, laneY[wi]!!)
        badges += Triple(wires.indexOf(wi) + 1, mid, if (wi.to in faultWires) C.Orange else if (wi.color == Color(WIRE_BLACK)) Color(0xFF374151) else wi.color)
        if (wi.to in faultWires && faultAt == null) faultAt = Offset(tx, (ty + (laneY[wi] ?: ty)) / 2)
    }
    badges.forEach { (n, at, c) -> badge(n, at, c) }
    // where to look when the box reports a fault, by the first wire it concerns
    faultAt?.let { at ->
        val msg = when {
            fault!!.startsWith("обрыв") -> "Обрыв? Проверьте этот провод"
            fault.contains("массу") -> "Сигнал касается массы"
            fault.contains("5 В") -> "Сигнал касается +5 В"
            else -> fault.replaceFirstChar { it.uppercase() }
        }
        val l = tm.measure(kg.forester.sg.data.tr(msg), TextStyle(fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold))
        val tl = Offset((at.x - l.size.width - dp(10f)).coerceAtLeast(dp(4f)), at.y - l.size.height / 2f)
        drawRoundRect(C.Orange, tl - Offset(dp(5f), dp(3f)), Size(l.size.width + dp(10f), l.size.height + dp(6f)), CornerRadius(dp(5f)))
        drawText(l, topLeft = tl)
    }

    // --- the pull-up: a resistor from the signal pin to the 5 V pin, right by the board
    bridge?.let { (sig, v5) ->
        val sign = sig.side
        val y = (if (sign == 1) bb else bt) + sign * dp(9f)
        val legs = Color(0xFFCBD5E1)
        drawLine(legs, Offset(sig.x, sig.y), Offset(sig.x, y), dp(2f))
        drawLine(legs, Offset(v5.x, v5.y), Offset(v5.x, y), dp(2f))
        drawLine(legs, Offset(sig.x, y), Offset(v5.x, y), dp(2f))
        val mid = (sig.x + v5.x) / 2
        val len = (kotlin.math.abs(v5.x - sig.x) - dp(16f)).coerceIn(dp(14f), dp(30f))
        drawRoundRect(Color(0xFFD6B98C), Offset(mid - len / 2, y - dp(5f)), Size(len, dp(10f)), CornerRadius(dp(4f)))
        listOf(0.25f to Color(0xFFEF4444), 0.45f to Color(0xFFEF4444), 0.65f to Color(0xFF8B4513)).forEach { (f, c) ->
            drawLine(c, Offset(mid - len / 2 + len * f, y - dp(5f)), Offset(mid - len / 2 + len * f, y + dp(5f)), dp(2f))
        }
        drawCircle(legs, dp(3f), Offset(sig.x, y))
        val label = tm.measure(kg.forester.sg.data.tr("резистор ${p.pullUp}"), TextStyle(fontSize = 11.sp, color = Color(0xFFD6B98C), fontWeight = FontWeight.Bold))
        drawText(label, topLeft = Offset(mid - label.size.width / 2f, if (sign == 1) y + dp(7f) else y - dp(7f) - label.size.height))
    }
}

private fun terminalLabel(to: String, p: BoxPreset) = if (p.role == "fuelLevel") when (to) {
    "SIG" -> "провод бака, 22 кОм"; else -> "масса, 10 кОм"
} else if (p.id == "pot") when (to) {
    "5V" -> "крайний вывод"; "GND" -> "другой крайний"; else -> "средний (движок)"
} else when (p.kind) {
    BoxKind.NTC -> if (to == "SIG") "вывод 1" else "вывод 2"
    BoxKind.TC -> when (to) { "5V" -> "VCC"; "GND" -> "GND"; "SCK" -> "SCK"; "CS" -> "CS"; else -> "SO" }
    BoxKind.VOLT -> if (to == "SIG") "выход" else "масса"
    else -> when (to) { "5V" -> "питание"; "GND" -> "масса"; "12V" -> "+12 В"; else -> "сигнал" }
}

private fun sensorTitle(p: BoxPreset) = if (p.id == "pot") "Потенциометр" else when (p.kind) {
    BoxKind.PRESSURE, BoxKind.RES -> "Датчик давления"
    BoxKind.RATIO -> when {
        p.id.startsWith("batt") -> "Датчик тока"
        p.id.startsWith("map") -> "Датчик давления"
        else -> p.title.substringBefore(",").take(18)
    }
    BoxKind.NTC -> "Датчик температуры"
    BoxKind.TC -> "Модуль MAX31855"
    BoxKind.VOLT -> when (p.role) { "afr" -> "Контроллер ШДК"; "fuelLevel" -> "Уровень топлива"; else -> "Датчик 0–5 В" }
    BoxKind.FREQ -> "Датчик этанола"
    BoxKind.OUT -> p.title
}

/** What sticks out of the sensor on the right: a threaded body, the thermocouple probe. */
private fun DrawScope.drawSensorBody(tm: TextMeasurer, p: BoxPreset, x: Float, top: Float, hgt: Float) {
    val d = density
    val cy = top + hgt / 2
    if (p.id == "pot") {
        // a knob with its pointer
        drawCircle(Color(0xFF111827), 18f * d, Offset(x + 26f * d, cy))
        drawCircle(Color(0xFF94A3B8), 18f * d, Offset(x + 26f * d, cy), style = Stroke(1.5f * d))
        drawLine(Color.White, Offset(x + 26f * d, cy), Offset(x + 26f * d + 10f * d, cy - 10f * d), 3f * d, StrokeCap.Round)
        return
    }
    when (p.kind) {
        BoxKind.PRESSURE, BoxKind.NTC, BoxKind.RES -> {
            // hex and thread
            val hex = Path().apply {
                val r = 13f * d
                for (i in 0..5) {
                    val a = Math.toRadians(60.0 * i)
                    val px = x + 14f * d + r * Math.cos(a).toFloat()
                    val py = cy + r * Math.sin(a).toFloat()
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(hex, Color(0xFF9CA3AF))
            drawRect(Color(0xFFB8BEC8), Offset(x + 27f * d, cy - 6f * d), Size(20f * d, 12f * d))
            for (i in 0..4) drawLine(Color(0xFF6B7280), Offset(x + (29f + i * 4f) * d, cy - 6f * d), Offset(x + (31f + i * 4f) * d, cy + 6f * d), 1f * d)
            text(tm, "1/8 NPT", Offset(x + 6f * d, cy + 16f * d), 10f, Color(0xFF94A3B8))
        }
        BoxKind.TC -> {
            drawLine(Color(0xFFFACC15), Offset(x, cy - 3f * d), Offset(x + 30f * d, cy - 3f * d), 2f * d)
            drawLine(Color(0xFFEF4444), Offset(x, cy + 3f * d), Offset(x + 30f * d, cy + 3f * d), 2f * d)
            drawRoundRect(Color(0xFFB8BEC8), Offset(x + 30f * d, cy - 4f * d), Size(22f * d, 8f * d), CornerRadius(4f * d))
            text(tm, "T+ T−", Offset(x + 2f * d, cy - 18f * d), 10f, Color(0xFF94A3B8))
            text(tm, "термопара K", Offset(x - 4f * d, cy + 10f * d), 10f, Color(0xFF94A3B8))
        }
        BoxKind.OUT -> if (p.id == "out_COIL12") {
            // the relay it drives: coil 85/86 with its diode, contacts 30/87 to the load
            val bx = x + 10f * d
            val by = cy - 30f * d
            drawRoundRect(Color(0xFF334155), Offset(bx, by), Size(44f * d, 60f * d), CornerRadius(4f * d))
            drawRoundRect(Color(0xFF94A3B8), Offset(bx, by), Size(44f * d, 60f * d), CornerRadius(4f * d), style = Stroke(1f * d))
            text(tm, "реле", Offset(bx + 8f * d, by + 2f * d), 10f, Color.White, bold = true)
            text(tm, "85 86", Offset(bx + 6f * d, by + 16f * d), 10f, Color(0xFFCBD5E1))
            text(tm, "30 87", Offset(bx + 6f * d, by + 30f * d), 10f, Color(0xFFCBD5E1))
            text(tm, "диод", Offset(bx + 6f * d, by + 44f * d), 7f, Color(0xFFFACC15))
            drawLine(Color(0xFF94A3B8), Offset(x, cy), Offset(bx, cy), 1.5f * d)
        }
        else -> Unit
    }
}

private fun DrawScope.text(tm: TextMeasurer, s: String, at: Offset, sizeSp: Float, color: Color, bold: Boolean = false) {
    drawText(tm, s, at, TextStyle(fontSize = sizeSp.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
}

/** A pin label turned along the pin row, as printed on the boards: reading into the board from the pin. */
private fun DrawScope.vtext(tm: TextMeasurer, s: String, at: Offset, sizeSp: Float, color: Color, bold: Boolean, down: Boolean, backing: Boolean = false) {
    val r = tm.measure(kg.forester.sg.data.tr(s), TextStyle(fontSize = sizeSp.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    rotate(if (down) 90f else -90f, pivot = at) {
        // on a real top view the label sits over the board's own print: a dark backing keeps it readable
        if (backing) drawRoundRect(Color(0xE60B0F1A), Offset(at.x - 2f * density, at.y - r.size.height / 2f - 1f * density),
            Size(r.size.width + 4f * density, r.size.height + 2f * density), CornerRadius(3f * density))
        drawText(r, topLeft = Offset(at.x, at.y - r.size.height / 2f))
    }
}

// ---------------------------------------------------------------- the whole board: every sensor at once

private val SENSOR_COLORS = listOf(
    // no red (+5 V), black (GND) or purple (+12 V): those colours mean power
    0xFFFACC15, 0xFF22D3EE, 0xFF4ADE80, 0xFFF97316, 0xFF38BDF8, 0xFFFBBF24, 0xFFA3E635, 0xFF60A5FA, 0xFFF472B6, 0xFF2DD4BF,
)

/** What one sensor takes on the board: its signal pins (pin, what for), and whether it wants 5 V, ground, the car's 12 V. */
private class BoardUse(val name: String, val input: String, val pullUp: String?, val color: Color, val signals: List<Pair<String, String>>,
                       val power: Boolean, val resistor: Boolean, val ground: Boolean, val car12: Boolean)

/** The sensors first, then the outputs (relays, MOSFETs, LEDs): everything that takes pins on the board. */
private fun boardUses(board: BoxBoard, slots: List<BoxSlot>, outs: List<kg.forester.sg.data.OutChannel> = kg.forester.sg.data.Relays.channels.value): List<BoardUse> {
    var i = 0
    val a = slots.sortedBy { board.inputs.indexOf(it.input) }.map { s ->
        val p = s.p
        BoardUse(
            s.name, s.input, p.pullUp, Color(SENSOR_COLORS[i++ % SENSOR_COLORS.size]),
            p.wires.filter { it.to in SIGNALS }.map { pinFor(board, s.input, it.to) to if (it.to == "SIG") "сигнал" else it.to },
            p.wires.any { it.to == "5V" }, p.pullUp != null, p.wires.any { it.to == "GND" }, p.wires.any { it.to == "12V" },
        )
    }
    val b = outs.filter { it.pin in board.outPins }.map { c ->
        BoardUse(
            "Выход ${c.ch}: ${c.name}", c.pin, null, Color(SENSOR_COLORS[i++ % SENSOR_COLORS.size]), listOf(c.pin to "выход"),
            c.load == kg.forester.sg.data.LoadKind.RELAY, false, true, c.load == kg.forester.sg.data.LoadKind.COIL12,
        )
    }
    val c = kg.forester.sg.data.Displays.list.value.map { d ->
        val sig = when (d.kind.bus) {
            kg.forester.sg.data.DispBus.I2C -> listOf(board.sda to "SDA", board.scl to "SCL")
            kg.forester.sg.data.DispBus.TM -> listOf(d.clk to "CLK", d.dio to "DIO")
            kg.forester.sg.data.DispBus.SPI -> listOf(d.cs to "CS", d.dc to "DC")
        }.filter { it.first.isNotEmpty() }
        BoardUse("Дисплей ${d.kind.title.substringBefore(" с ").substringBefore(" 0,96")}", sig.firstOrNull()?.first ?: "", null,
            Color(SENSOR_COLORS[i++ % SENSOR_COLORS.size]), sig, true, false, true, false)
    }
    return a + b + c
}

private fun sideOf(board: BoxBoard, pin: String) = when (pin) { in board.topRow -> -1; in board.bottomRow -> 1; else -> 0 }

/**
 * Every sensor on the board in one picture: each signal pin with a tag of what goes there, and the two shared
 * terminal blocks (+5 V and ground) with who takes a wire from them; [highlight] (an input) bright, the rest softer.
 */
@Composable
fun BoardOverview(board: BoxBoard, slots: List<BoxSlot>, highlight: String?, modifier: Modifier = Modifier) {
    val topRead = rememberBoardTop(board3d(board))
    val boardTop = topRead.top
    val tm = rememberTextMeasurer()
    val uses = boardUses(board, slots)
    val sigs = uses.flatMap { u -> u.signals.map { it.first } }
    fun side(n: String) = boardTop?.pins?.firstOrNull { it.name == n }?.side ?: sideOf(board, n)
    val nTop = sigs.count { side(it) == -1 }
    val nBottom = sigs.count { side(it) == 1 }
    val n5 = uses.count { it.power || it.resistor }
    val nG = uses.count { it.ground }
    val topSpace = 20 + nTop * 16 + 30
    val bottom = 20 + nBottom * 16 + 30
    val blocks = (n5 + nG) * 18 + 90
    val height = maxOf(topSpace + 104 + bottom, blocks, 240)
    Canvas(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0F1A))) {
        if (topRead.loaded) drawOverview(tm, board, uses, highlight, (topSpace + 52).dp.toPx(), boardTop)
    }
}

private fun DrawScope.drawOverview(tm: TextMeasurer, board: BoxBoard, uses: List<BoardUse>, highlight: String?, cy: Float, top: BoardTop? = null) {
    val d = density
    fun dp(v: Float) = v * d
    val w = size.width
    val h = size.height
    fun col(u: BoardUse) = if (highlight == null || u.input == highlight) u.color else u.color.copy(alpha = 0.45f)
    val g = drawBoard(tm, board, cy, 0.42f, top)
    val pins = g.pins
    fun pin(name: String, side: Int? = null) = pins.filter { it.name == name }.let { l -> l.firstOrNull { side == null || it.side == side } ?: l.firstOrNull() }

    // the signal pins and their tags
    class Tag(val pin: Pin, val text: String, val color: Color)
    val tags = uses.flatMap { u -> u.signals.mapNotNull { (n, what) ->
        pin(n)?.let { Tag(it, (if (it.side == 0) "${it.name} · " else "") + u.name.substringBefore(",").substringBefore(" (").take(24) +
            if (what == "сигнал" || what == "выход") "" else " · $what", col(u)) }
    } }
    val v5 = pin(board.v5)
    val gndSide = if (tags.count { it.pin.side == 1 } > tags.count { it.pin.side == -1 }) -1 else 1
    val gnd = pin("GND", gndSide)
    val used = tags.associate { it.pin to it.color } +
        listOfNotNull(v5?.takeIf { uses.any { it.power || it.resistor } }?.let { it to Color(WIRE_RED) }, gnd?.takeIf { uses.any { it.ground } }?.let { it to Color(WIRE_BLACK) })
    drawPins(tm, pins, used, emptySet(), g.pitch)
    val bySide = tags.groupBy { it.pin.side }

    // the shared terminal blocks
    val bx = w - dp(196f)
    val pitch = dp(18f)
    val powered = uses.filter { it.power || it.resistor }
    val grounded = uses.filter { it.ground }
    val h5 = if (powered.isEmpty()) 0f else pitch * powered.size + dp(10f)
    val hG = if (grounded.isEmpty()) 0f else pitch * grounded.size + dp(10f)
    val total = h5 + hG + (if (h5 > 0 && hG > 0) dp(34f) else 0f)
    var y0 = (h / 2 - total / 2 + dp(10f)).coerceAtLeast(dp(18f))
    class Block(val top: Float, val height: Float, val color: Color)
    fun block(title: String, color: Color, rows: List<Pair<String, Color>>): Block? {
        if (rows.isEmpty()) return null
        val bh = pitch * rows.size + dp(10f)
        text(tm, title, Offset(bx - dp(4f), y0 - dp(15f)), 10.5f, color, bold = true)
        drawRoundRect(Color(0xFF334155), Offset(bx, y0), Size(dp(16f), bh), CornerRadius(dp(3f)))
        drawRoundRect(color, Offset(bx, y0), Size(dp(16f), bh), CornerRadius(dp(3f)), style = Stroke(dp(1.5f)))
        rows.forEachIndexed { i, (label, c) ->
            val y = y0 + dp(5f) + pitch * (i + 0.5f)
            drawCircle(Color(0xFFCBD5E1), dp(4f), Offset(bx + dp(8f), y))
            drawLine(Color(0xFF475569), Offset(bx + dp(5f), y), Offset(bx + dp(11f), y), dp(1.2f))
            drawLine(c, Offset(bx + dp(16f), y), Offset(bx + dp(26f), y), dp(2.5f))
            text(tm, label, Offset(bx + dp(30f), y - dp(7f)), 10.5f, c)
        }
        return Block(y0, bh, color).also { y0 += bh + dp(34f) }
    }
    // a block fed from above sits above the one fed from below: their feeds never cross
    val rows5 = powered.map { u ->
        (if (u.resistor && !u.power) "резистор ${u.pullUp} к ${board.pinOf(u.input)}" else u.name).take(28) to col(u)
    }
    val rowsG = grounded.map { u -> u.name.take(28) to col(u) }
    var b5: Block? = null
    var bG: Block? = null
    if ((gnd?.side ?: 1) < (v5?.side ?: 1)) {
        bG = block("GND: клеммник", Color(WIRE_BLACK), rowsG); b5 = block("+5V: клеммник", Color(WIRE_RED), rows5)
    } else {
        b5 = block("+5V: клеммник", Color(WIRE_RED), rows5); bG = block("GND: клеммник", Color(WIRE_BLACK), rowsG)
    }

    // one wire from the board to each block, beyond the tags; the upper block takes the lane nearer the board
    class Feed(val pin: Pin, val b: Block)
    val feeds = listOfNotNull(v5?.let { p -> b5?.let { Feed(p, it) } }, gnd?.let { p -> bG?.let { Feed(p, it) } })
    for ((side, list) in feeds.groupBy { it.pin.side }) {
        val n = list.size
        val base = dp(14f + (bySide[side]?.size ?: 0) * 16f + 8f)
        list.sortedBy { it.b.top }.forEachIndexed { i, f ->
            val k = if (side == -1) n - 1 - i else i // top: the upper block on the outer lane
            val lane = if (side == -1) g.bt - base - k * dp(13f) else g.bb + base + k * dp(13f)
            val turn = bx - dp(12f) - (if (side == -1) i else n - 1 - i) * dp(12f)
            val attach = f.b.top + f.b.height / 2
            val path = Path().apply {
                moveTo(f.pin.x, f.pin.y); lineTo(f.pin.x, lane); lineTo(turn, lane); lineTo(turn, attach); lineTo(bx, attach)
            }
            drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(dp(5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, f.b.color, style = Stroke(dp(3f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // the tags last, on a dark backing, so a feed passing behind never hides one;
    // the deepest tag for the leftmost pin: the leaders never cross a tag
    for ((side, list) in bySide) {
        // the end header's pins are a pitch apart: their tags step down far enough not to overlap
        val sideY = HashMap<Tag, Float>()
        if (side == 0) list.sortedBy { it.pin.y }.fold(-1e9f) { prev, t -> maxOf(t.pin.y, prev + dp(15f)).also { sideY[t] = it } }
        list.sortedByDescending { it.pin.x }.forEachIndexed { r, t ->
            val label = tm.measure(kg.forester.sg.data.tr(t.text), TextStyle(fontSize = 11.sp, color = t.color, fontWeight = FontWeight.Bold))
            val (lx, ly) = if (side == 0) {
                val y = sideY[t] ?: t.pin.y
                drawLine(t.color, Offset(t.pin.x, t.pin.y), Offset(g.br + dp(6f), t.pin.y), dp(2f))
                drawLine(t.color, Offset(g.br + dp(6f), t.pin.y), Offset(g.br + dp(12f), y), dp(2f))
                g.br + dp(15f) to y
            } else {
                val y = if (side == 1) g.bb + dp(14f + r * 16f) else g.bt - dp(14f + r * 16f)
                drawLine(t.color, Offset(t.pin.x, t.pin.y), Offset(t.pin.x, y), dp(2f))
                drawCircle(t.color, dp(2.5f), Offset(t.pin.x, y))
                t.pin.x + dp(5f) to y
            }
            drawRoundRect(Color(0xE60B0F1A), Offset(lx - dp(2f), ly - label.size.height / 2f - dp(1f)),
                Size(label.size.width + dp(4f), label.size.height + dp(2f)), CornerRadius(dp(3f)))
            drawText(label, topLeft = Offset(lx, ly - label.size.height / 2f))
        }
    }
}

/** The same in words: pin by pin, what goes where. */
@Composable
fun PinTable(board: BoxBoard, slots: List<BoxSlot>) {
    val uses = boardUses(board, slots)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        uses.forEach { u ->
            u.signals.forEach { (pin, what) ->
                PinRow(u.color, pin, when (what) {
                    "сигнал" -> "${u.name}: сигнал датчика"
                    "выход" -> "${u.name}: управление (IN модуля, база ключа или анод светодиода)"
                    else -> "${u.name}: $what " + if (u.name.startsWith("Дисплей")) "дисплея" else "модуля"
                })
            }
        }
        val powered = uses.filter { it.power }
        val pulled = uses.filter { it.resistor }
        if (powered.isNotEmpty() || pulled.isNotEmpty()) PinRow(Color(WIRE_RED), board.v5,
            "в клеммник +5V, а к нему: " + (powered.map { "питание ${it.name}" } +
                pulled.map { "резистор ${it.pullUp} от ${board.pinOf(it.input)} (${it.name})" }).joinToString("; "))
        val grounded = uses.filter { it.ground }
        if (grounded.isNotEmpty()) PinRow(Color(WIRE_BLACK), "GND", "в клеммник GND, а к нему массы: " + grounded.joinToString(", ") { it.name })
        uses.filter { it.car12 }.forEach { PinRow(Color(0xFFA855F7), "+12 В", "${it.name}: от машины через предохранитель, не от платы") }
    }
}

@Composable
private fun PinRow(color: Color, pin: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(vertical = 3.dp), contentAlignment = Alignment.Center) { T(pin, 12.sp, color, FontWeight.Bold, maxLines = 1) }
        Spacer(Modifier.width(10.dp))
        T(text, 12.sp, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- right: step 6, the check

/** How to try a sensor by hand, so its value has to move. */
private fun liveTest(s: BoxSlot): String = when {
    s.p.kind == BoxKind.TC -> "Поднесите зажигалку к щупу на пару секунд: температура должна расти"
    s.preset == "pot" -> "Покрутите ручку: проценты должны меняться от 0 до 100"
    s.role == "oilP" || s.p.id.startsWith("oil_p") -> "Заведите мотор: давление масла должно вырасти до 1–4 бар"
    s.role == "afr" -> "Через минуту после пуска на холостых должно быть около 14,7"
    s.p.kind == BoxKind.FREQ -> "На обычном бензине около 0–10 %"
    s.p.kind == BoxKind.NTC -> "Зажмите датчик в кулаке: температура должна подняться на несколько градусов"
    s.p.kind == BoxKind.PRESSURE || s.p.kind == BoxKind.RES || s.p.kind == BoxKind.RATIO -> "Без давления должно быть около нуля, с мотором значение растёт"
    else -> "Значение должно меняться вместе с тем, что меряет датчик"
}

@Composable
private fun BoxCheck(go: (BoxStep, String?) -> Unit) {
    val board by SensorBox.board.collectAsState()
    val info by SensorBox.info.collectAsState()
    val slots by SensorBox.slots.collectAsState()
    val readings by SensorBox.readings.collectAsState()
    val outs by kg.forester.sg.data.Relays.channels.collectAsState()
    val outStatus by kg.forester.sg.data.Relays.status.collectAsState()
    // the spread each value has shown since this page opened: a moving value has passed its live test
    val seen = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    readings.forEach { (k, r) -> r.value?.let { v -> val (lo, hi) = seen[k] ?: (v to v); seen[k] = minOf(lo, v) to maxOf(hi, v) } }
    T("Проверка", 18.sp, weight = FontWeight.SemiBold)
    SettingHint("Пройдитесь по списку: каждый датчик и выход проверяется вживую. Нажмите на строку, чтобы открыть его схему")

    Spacer(Modifier.height(12.dp))
    T("Связь", 16.sp, weight = FontWeight.SemiBold)
    val real = info?.takeIf { it.via != "демо" }
    CheckLine(real != null || info?.via == "демо", when {
        info?.via == "демо" -> "Демо: плата придумана, значения из демо-поездки"
        real != null -> "${BoxBoard.of(real.board).short} на связи (${real.via}), данные идут ${real.rate} раз в секунду"
        else -> "Плата не на связи: проверьте USB-кабель и что поиск на USB включён"
    })
    if (real != null) CheckLine(real.fw >= BOX_FIRMWARE, if (real.fw >= BOX_FIRMWARE) "Прошивка версии ${real.fw}, свежая" else "Прошивка версии ${real.fw}: обновите на шаге «Прошивка»")

    Spacer(Modifier.height(12.dp))
    T("Датчики", 16.sp, weight = FontWeight.SemiBold)
    val mine = slots.filter { it.input in board.inputs }.sortedBy { board.inputs.indexOf(it.input) }
    if (mine.isEmpty()) T("Пока нет: добавьте на шаге «Датчики»", 14.sp, C.Sub)
    mine.forEach { s ->
        val r = readings[s.input]
        val moved = seen[s.input]?.let { (lo, hi) -> hi - lo > (s.p.max - s.p.min) * 0.02f } == true
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
                .border(1.dp, if (r?.problem != null) C.Orange else C.Border, RoundedCornerShape(12.dp))
                .clickable { go(BoxStep.SENSORS, s.input) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(board.pcb)).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                T(board.pinOf(s.input), 13.sp, Color.White, FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(s.name, 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                T(if (r?.problem != null) "Проверьте провода по схеме: ${r.problem}" else liveTest(s), 13.sp, if (r?.problem != null) C.Orange else C.Sub, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                T(r?.value?.let { valueText(s, it) + " " + s.unit } ?: "—", 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                if (r != null && r.rawText.isNotEmpty()) T(r.rawText, 12.sp, C.Sub, maxLines = 1)
                T(when { r?.problem != null -> "ошибка"; moved -> "меняется ✓"; r?.value != null -> "ждёт проверки"; else -> "нет данных" }, 12.sp,
                    when { r?.problem != null -> C.Orange; moved -> C.Green; else -> C.Sub }, FontWeight.SemiBold)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    T("Выходы", 16.sp, weight = FontWeight.SemiBold)
    if (outs.isEmpty()) T("Пока нет: добавьте на шаге «Выходы»", 14.sp, C.Sub)
    outs.forEach { c ->
        var asked by remember(c.ch) { mutableStateOf(false) }
        var answer by remember(c.ch) { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
                .border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(outIcon(c.icon), null, tint = if (outStatus[c.ch]?.want == true) C.Green else C.Sub, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { go(BoxStep.OUTPUTS, "out:${c.ch}") }) {
                    T("${c.pin} · ${c.name}", 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    T(outStatus[c.ch]?.why ?: "—", 13.sp, C.Sub, maxLines = 2)
                }
                Chip("Проверить 1 с", false, { kg.forester.sg.service.RelayEngine.test(c.ch); asked = true; answer = "" }, Lucide.Zap)
            }
            if (asked) {
                Spacer(Modifier.height(8.dp))
                T(if (c.load == kg.forester.sg.data.LoadKind.LED) "Светодиод мигнул?" else "Щёлкнуло реле и включилась нагрузка?", 14.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Да", answer == "yes", { answer = "yes" })
                    Chip("Нет", answer == "no", { answer = "no" })
                    if (c.load == kg.forester.sg.data.LoadKind.RELAY || c.load == kg.forester.sg.data.LoadKind.COIL12)
                        Chip("Щёлкнуло, но нагрузка не включилась", answer == "half", { answer = "half" })
                }
                when (answer) {
                    "yes" -> CheckLine(true, "Выход работает")
                    "no" -> Hint(Lucide.TriangleAlert, when (c.load) {
                        kg.forester.sg.data.LoadKind.RELAY -> "Проверьте VCC, GND и IN модуля по схеме. Если реле щёлкает наоборот (включено, когда должно быть выключено), переключите «Реле работает наоборот»."
                        kg.forester.sg.data.LoadKind.COIL12 -> "Проверьте +12 В на контакте 86 и предохранитель, провод от пина к базе через 1 кОм, массу эмиттера. Транзистор: плоской стороной к себе, слева направо коллектор, база, эмиттер (BC337)."
                        kg.forester.sg.data.LoadKind.LED -> "Светодиод длинной ногой к пину через резистор 330 Ом, короткой к GND. Перевёрнутый светодиод не светит."
                        else -> "Проверьте провода сигнала и массы по схеме и что выход не заблокирован (строка состояния выше)."
                    }, C.Orange)
                    "half" -> Hint(Lucide.TriangleAlert, "Управление в порядке, дело в силовой части: предохранитель нагрузки, +12 В на контакте 30, провод от 87 к нагрузке, масса нагрузки.", C.Orange)
                }
            }
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Lucide.CircleCheck else Lucide.CircleAlert, null, tint = if (ok) C.Green else C.Orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        T(text, 14.sp)
    }
}
