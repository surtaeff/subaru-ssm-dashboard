package kg.forester.sg.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import com.composables.icons.lucide.*
import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxKind
import kg.forester.sg.data.BoxPreset
import kg.forester.sg.data.BoxWire
import kg.forester.sg.data.LoadKind
import kg.forester.sg.data.LostState
import kg.forester.sg.data.Manual
import kg.forester.sg.data.OutChannel
import kg.forester.sg.data.OutEvent
import kg.forester.sg.data.OutRule
import kg.forester.sg.data.OutStatus
import kg.forester.sg.data.OutTemplates
import kg.forester.sg.data.Relays
import kg.forester.sg.data.RuleKind
import kg.forester.sg.data.RuleSources
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.Threshold
import kg.forester.sg.data.WIRE_BLACK
import kg.forester.sg.data.WIRE_PURPLE
import kg.forester.sg.data.WIRE_RED
import kg.forester.sg.data.WIRE_YELLOW
import kg.forester.sg.service.RelayEngine
import kg.forester.sg.service.RuleRunner
import java.util.Locale

/*
 * Настройки → Датчики и экраны → «Выходы»: relays, MOSFETs and LEDs on the box, each with rules written as Russian
 * sentences («Включать, когда антифриз выше 98 °C. Выключать, когда он ниже 94 °C»). Ideas taken from ECU output
 * tables (two numbers instead of a hysteresis), Home Assistant (a "why" line, disable without deleting) and Tinkercad
 * (try it before wiring); docs/knowledge/sensorbox-relays.md.
 */

/** A widget icon: its id (saved), what it is called, the section of the picker. */
class OutIcon(val id: String, val title: String, val group: String, val v: ImageVector)

/** The icons an output's button can wear, by topic (the ids of the first ones are what the templates use). */
val OUT_ICON_SET = listOf(
    // свет
    OutIcon("light", "свет", "Свет", Lucide.Lightbulb), OutIcon("lamp", "фары", "Свет", Lucide.Flashlight),
    OutIcon("sun", "ДХО", "Свет", Lucide.Sun), OutIcon("ceiling", "салон", "Свет", Lucide.LampCeiling),
    OutIcon("wall", "люстра", "Свет", Lucide.LampWallUp), OutIcon("desk", "подсветка", "Свет", Lucide.LampDesk),
    OutIcon("floor", "свет в багажнике", "Свет", Lucide.LampFloor), OutIcon("moon", "ночной режим", "Свет", Lucide.Moon),
    OutIcon("sparkles", "подсветка днища", "Свет", Lucide.Sparkles), OutIcon("siren", "маячок", "Свет", Lucide.Siren),
    // климат
    OutIcon("fan", "вентилятор", "Климат и обогрев", Lucide.Fan), OutIcon("snow", "холод", "Климат и обогрев", Lucide.Snowflake),
    OutIcon("flame", "подогрев", "Климат и обогрев", Lucide.Flame), OutIcon("heater", "отопитель", "Климат и обогрев", Lucide.Heater),
    OutIcon("vent", "обдув", "Климат и обогрев", Lucide.AirVent), OutIcon("tsun", "жарко", "Климат и обогрев", Lucide.ThermometerSun),
    OutIcon("tsnow", "мороз", "Климат и обогрев", Lucide.ThermometerSnowflake), OutIcon("fridge", "холодильник", "Климат и обогрев", Lucide.Refrigerator),
    // мотор
    OutIcon("gauge", "наддув", "Мотор и охлаждение", Lucide.Gauge), OutIcon("therm", "температура", "Мотор и охлаждение", Lucide.Thermometer),
    OutIcon("cog", "механизм", "Мотор и охлаждение", Lucide.Cog), OutIcon("timer", "турботаймер", "Мотор и охлаждение", Lucide.Timer),
    OutIcon("rocket", "форсаж", "Мотор и охлаждение", Lucide.Rocket), OutIcon("activity", "датчик", "Мотор и охлаждение", Lucide.Activity),
    OutIcon("wind", "компрессор", "Мотор и охлаждение", Lucide.Wind), OutIcon("cylinder", "баллон", "Мотор и охлаждение", Lucide.Cylinder),
    // жидкости
    OutIcon("pump", "насос", "Жидкости и насосы", Lucide.Droplets), OutIcon("drop", "капля", "Жидкости и насосы", Lucide.Droplet),
    OutIcon("fuel", "топливо", "Жидкости и насосы", Lucide.Fuel), OutIcon("spray", "распылитель", "Жидкости и насосы", Lucide.SprayCan),
    OutIcon("waves", "омыватель", "Жидкости и насосы", Lucide.Waves), OutIcon("rain", "дворники", "Жидкости и насосы", Lucide.CloudRain),
    OutIcon("shower", "душ", "Жидкости и насосы", Lucide.ShowerHead), OutIcon("pipette", "дозатор", "Жидкости и насосы", Lucide.Pipette),
    // электрика
    OutIcon("button", "кнопка", "Электрика", Lucide.Power), OutIcon("zap", "питание", "Электрика", Lucide.Zap),
    OutIcon("plug", "розетка", "Электрика", Lucide.Plug), OutIcon("plugzap", "инвертор", "Электрика", Lucide.PlugZap),
    OutIcon("batt", "доп. АКБ", "Электрика", Lucide.BatteryCharging), OutIcon("usb", "USB-зарядка", "Электрика", Lucide.Usb),
    OutIcon("bolt", "реле", "Электрика", Lucide.Bolt), OutIcon("relay", "выключатель", "Электрика", Lucide.ToggleRight),
    // звук и устройства
    OutIcon("horn", "сигнал", "Звук и устройства", Lucide.Megaphone), OutIcon("bell", "звонок", "Звук и устройства", Lucide.BellRing),
    OutIcon("speaker", "сабвуфер", "Звук и устройства", Lucide.Speaker), OutIcon("music", "усилитель", "Звук и устройства", Lucide.Music),
    OutIcon("radio", "рация", "Звук и устройства", Lucide.Radio), OutIcon("camera", "камера", "Звук и устройства", Lucide.Camera),
    OutIcon("cctv", "регистратор", "Звук и устройства", Lucide.Cctv), OutIcon("wifi", "роутер", "Звук и устройства", Lucide.Wifi),
    OutIcon("tv", "экран", "Звук и устройства", Lucide.Tv), OutIcon("phone", "телефон", "Звук и устройства", Lucide.Smartphone),
    // безопасность
    OutIcon("lock", "замок", "Безопасность", Lucide.Lock), OutIcon("unlock", "открыть", "Безопасность", Lucide.LockOpen),
    OutIcon("key", "ключ", "Безопасность", Lucide.KeyRound), OutIcon("shield", "охрана", "Безопасность", Lucide.Shield),
    OutIcon("alert", "аварийка", "Безопасность", Lucide.TriangleAlert), OutIcon("fire", "огнетушитель", "Безопасность", Lucide.FireExtinguisher),
    // кузов, стоянка, экспедиция
    OutIcon("car", "машина", "Кузов и поездки", Lucide.CarFront), OutIcon("parking", "стоянка", "Кузов и поездки", Lucide.CircleParking),
    OutIcon("truck", "прицеп", "Кузов и поездки", Lucide.Truck), OutIcon("caravan", "автодом", "Кузов и поездки", Lucide.Caravan),
    OutIcon("tent", "кемпинг", "Кузов и поездки", Lucide.Tent), OutIcon("mountain", "бездорожье", "Кузов и поездки", Lucide.Mountain),
    OutIcon("anchor", "лебёдка", "Кузов и поездки", Lucide.Anchor), OutIcon("bike", "багажник", "Кузов и поездки", Lucide.Bike),
    OutIcon("compass", "навигация", "Кузов и поездки", Lucide.Compass), OutIcon("coffee", "кофеварка", "Кузов и поездки", Lucide.Coffee),
    OutIcon("cooking", "плитка", "Кузов и поездки", Lucide.CookingPot), OutIcon("dog", "питомец", "Кузов и поездки", Lucide.PawPrint),
)

private val ICONS_BY_ID = OUT_ICON_SET.associateBy { it.id }

fun outIcon(icon: String): ImageVector = ICONS_BY_ID[icon]?.v ?: Lucide.ToggleRight

/** The widget names for the screens' «Добавить виджет» menus: one per output set up. */
fun outWidgetNames(): Map<String, Pair<String, ImageVector>> =
    Relays.channels.value.associate { "outw_${it.ch}" to (it.name to outIcon(it.icon)) }

/**
 * An output on a screen: a big button (tap: on/off by hand, hold: back to the rules) or a slider for a level
 * (PWM: fan speed, brightness); the lamp is what the box confirms.
 */
@Composable
fun OutputWidget(id: String, m: Modifier) {
    val ch = id.removePrefix("outw_").toIntOrNull() ?: return
    val chans by Relays.channels.collectAsState()
    val status by Relays.status.collectAsState()
    val board by SensorBox.board.collectAsState()
    Relays.boxStates.collectAsState().value
    val c = chans.firstOrNull { it.ch == ch }
    if (c == null) { Panel(m) { T("Выход $ch не настроен: Настройки, Датчики и экраны, Выходы", 12.sp, C.Sub) }; return }
    val s = status[ch]
    val on = s?.want == true
    val col = Color(if (on) c.onColor else c.offColor)
    Panel(m, padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(outIcon(c.icon), null, tint = col, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            T(c.label.ifBlank { c.name }, 13.sp, C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
            T(when (c.manual) { Manual.AUTO -> "по правилам"; else -> "вручную" }, 12.sp, if (c.manual == Manual.AUTO) C.Violet else C.Orange)
        }
        Spacer(Modifier.height(6.dp))
        if (c.pwm(board) && c.widget == "knob") {
            Knob(c, on, Modifier.fillMaxWidth().weight(1f))
        } else if (c.pwm(board)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(if (on) "${c.level} %" else "выкл", 22.sp, if (on) C.Text else C.Sub, FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).background(col.copy(alpha = 0.2f)).clickable { Relays.toggle(ch) }, contentAlignment = Alignment.Center) {
                    Icon(Lucide.Power, "Вкл/выкл", tint = col, modifier = Modifier.size(20.dp))
                }
            }
            var v by remember(c.level) { mutableStateOf(c.level.toFloat()) }
            androidx.compose.material3.Slider(
                value = v, onValueChange = { v = it }, onValueChangeFinished = { Relays.setLevel(ch, v.toInt()) }, valueRange = 0f..100f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.Violet, activeTrackColor = C.Violet),
            )
        } else {
            ButtonFace(c, on, Modifier.fillMaxWidth().weight(1f).pointerInput(ch) {
                detectTapGestures(onTap = { Relays.toggle(ch) }, onLongPress = { Relays.auto(ch) })
            }, blinking = s?.blink == true && on)
        }
    }
}

/** The lamp: what the box says it is doing (the app's wish while there is no box). */
private fun lamp(s: OutStatus?, demoOrNoBox: Boolean): Pair<String, Color> {
    val box = s?.box
    if (!demoOrNoBox && Relays.boxFailsafe.value) return "авария" to C.Red
    return when {
        box == 'x' -> "заблокирован" to C.Red
        box == 'w' -> "ждёт" to C.Orange
        box == 'b' || (box == null && s?.blink == true && s.want) -> "мигает" to C.Green
        box == 'p' -> "импульс" to C.Green
        box == '1' || (box == null && s?.want == true) -> "ВКЛ" to C.Green
        else -> "выкл" to C.Sub
    }
}

/** The left column's part: the outputs with their lamps, «Добавить выход», «Всё выключить». */
@Composable
fun OutputsSection(board: BoxBoard, selected: String?, onSelect: (String) -> Unit) {
    val chans by Relays.channels.collectAsState()
    val status by Relays.status.collectAsState()
    val allOff by Relays.allOff.collectAsState()
    val info by SensorBox.info.collectAsState()
    Relays.boxStates.collectAsState().value
    var askPanic by remember { mutableStateOf(false) }
    if (info != null && info!!.via != "демо" && info!!.fw < 3) T("Для выходов нужна прошивка платы версии 3 или новее: обновите её на шаге «Прошивка»", 13.sp, C.Orange)
    if (askPanic) CarAlertDialog(
        onDismissRequest = { askPanic = false },
        title = { Text("Выключить все выходы?") },
        text = { Text("Правила не будут работать, пока не вернёте их этой же кнопкой.") },
        confirmButton = { androidx.compose.material3.TextButton({ RelayEngine.panic(true); askPanic = false }) { Text("Выключить всё") } },
        dismissButton = { androidx.compose.material3.TextButton({ askPanic = false }) { Text("Отмена") } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chans.forEach { c ->
            val sel = selected == "out:${c.ch}"
            val (txt, col) = lamp(status[c.ch], info == null || info!!.via == "демо")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                    .border(1.dp, if (sel) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(10.dp)).clickable { onSelect("out:${c.ch}") }
                    .heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(44.dp).clip(RoundedCornerShape(6.dp)).background(Color(board.pcb)).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    T(c.pin, 12.sp, Color.White, FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                Icon(outIcon(c.icon), null, tint = col, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T(c.name, 14.sp, maxLines = 1)
                    T(c.load.title, 12.sp, C.Sub, maxLines = 1)
                }
                T(txt + if (c.manual != Manual.AUTO) " · вручную" else "", 13.sp, if (c.manual != Manual.AUTO) C.Orange else col, FontWeight.SemiBold)
            }
        }
        AddRow("Добавить выход", "вентилятор, насос, свет, кнопка", selected == "out:new") { onSelect("out:new") }
        if (chans.isNotEmpty()) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (allOff) C.Red.copy(alpha = 0.25f) else C.Red.copy(alpha = 0.08f))
                .border(1.dp, C.Red.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).clickable { if (allOff) RelayEngine.panic(false) else askPanic = true }.heightIn(min = 48.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.OctagonX, null, tint = C.Red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            T(if (allOff) "Всё выключено. Нажмите, чтобы вернуть правила" else "Всё выключить", 14.sp, weight = FontWeight.SemiBold)
        }
    }
}

/** «Добавить выход»: what it is first (templates), the pin and the rules come filled in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputNew(board: BoxBoard, onCreated: (Int) -> Unit) {
    T("Что будет включать плата?", 18.sp, weight = FontWeight.SemiBold)
    SettingHint("Выберите готовый вариант: цифры в нём уже подобраны для Subaru, потом поменяете под себя")
    Spacer(Modifier.height(10.dp))
    val free = Relays.free(board)
    if (free == null) { T("Свободных пинов для выходов на этой плате больше нет", 13.sp, C.Orange); return }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutTemplates.all.forEach { t ->
            Row(
                Modifier.width(260.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi).border(1.dp, C.Border, RoundedCornerShape(12.dp))
                    .clickable { Relays.put(t.make(free.first, free.second)); onCreated(free.first) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(outIcon(t.icon), null, tint = C.Violet, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    T(t.title, 13.sp, weight = FontWeight.SemiBold, maxLines = 2)
                    T(t.sub, 12.sp, C.Sub, maxLines = 3)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingHint("Займёт выход ${free.first} на пине ${free.second}. Пин можно поменять потом.")
    Spacer(Modifier.height(12.dp))
    SettingHint("Модули реле бывают на 1, 4 и 8 каналов: один модуль на несколько выходов, каждому выходу свой вход IN")
    Row(Modifier.fillMaxWidth()) {
        listOf("relay1", "relay4", "relay8").forEach { Model3D(it, Modifier.weight(1f).height(130.dp)) }
    }
    if (board != BoxBoard.PROMICRO) {
        Spacer(Modifier.height(8.dp))
        Hint(Lucide.TriangleAlert, "На ${board.title.substringBefore(" /")} при каждом подключении приложения плата перезагружается, " +
            "и выходы на пару секунд выключаются. Для реле лучше Pro Micro: она так не делает.")
    }
}

/** One output: its lamp and why, manual control, the rule sentences, how it is wired, the box's own guards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputDetails(board: BoxBoard, ch: Int, onGone: () -> Unit) {
    val chans by Relays.channels.collectAsState()
    val c = chans.firstOrNull { it.ch == ch } ?: run { onGone(); return }
    val status by Relays.status.collectAsState()
    val info by SensorBox.info.collectAsState()
    Relays.boxStates.collectAsState().value
    val failsafe by Relays.boxFailsafe.collectAsState()
    val s = status[ch]
    val (txt, col) = lamp(s, info == null || info!!.via == "демо")
    var confirmTest by remember(ch) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(board.pcb)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            T(c.pin, 14.sp, Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T("Выход ${c.ch} · ${c.name}", 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(c.load.title, 12.sp, C.Sub, maxLines = 1)
        }
        Chip("Проверить 1 с", false, { confirmTest = true }, Lucide.Zap)
        Spacer(Modifier.width(6.dp))
        Chip("Убрать", false, {
            Relays.remove(ch)
            offerUndo("Выход «${c.name}» убран") { Relays.put(c) }
            onGone()
        }, Lucide.Trash2)
    }
    Spacer(Modifier.height(10.dp))
    // the lamp and the reason
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.12f)).border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(col.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(outIcon(c.icon), null, tint = col, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(txt, 20.sp, col, FontWeight.Bold)
            T(s?.why ?: "—", 13.sp)
            T(when {
                info == null -> "Плата не на связи: правила считаются, но включать нечем"
                info!!.via == "демо" -> "Демо: плата придумана"
                failsafe -> "Плата не получает команд от приложения и держит выходы в аварийном состоянии"
                else -> "Плата подтверждает: ${if (s?.box == '1' || s?.box == 'b' || s?.box == 'p') "включено" else "выключено"}"
            }, 12.sp, C.Sub)
        }
    }
    if (c.manual != Manual.AUTO) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Orange.copy(alpha = 0.14f))
                .border(1.dp, C.Orange.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Hand, null, tint = C.Orange, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            T("${if (c.manual == Manual.ON) "Включено" else "Выключено"} вручную: правила ниже сейчас не действуют", 14.sp, modifier = Modifier.weight(1f))
            Chip("Вернуть правила", true, { Relays.put(c.copy(manual = Manual.AUTO)) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("Управление:", 14.sp, C.Sub)
        Spacer(Modifier.width(8.dp))
        listOf(Manual.AUTO to "По правилам", Manual.ON to "Включить", Manual.OFF to "Выключить").forEach { (m, l) ->
            Chip(l, c.manual == m, { Relays.put(c.copy(manual = m)) })
            Spacer(Modifier.width(6.dp))
        }
    }
    if (confirmTest) CarAlertDialog(
        onDismissRequest = { confirmTest = false },
        title = { Text("Включить на 1 секунду?") },
        text = { Text(if (c.icon == "pump") "Насос будет качать 1 секунду. Мотор заглушен?" else "Реле должно щёлкнуть, а нагрузка включиться на секунду") },
        confirmButton = { androidx.compose.material3.TextButton({ confirmTest = false; RelayEngine.test(ch) }) { Text("Включить") } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmTest = false }) { Text("Отмена") } },
    )

    // the rules
    Spacer(Modifier.height(14.dp))
    T("Когда включать", 16.sp, weight = FontWeight.SemiBold)
    if (c.rules.count { it.kind != RuleKind.BLOCK } > 1) T("Включено, если сработало любое из правил", 13.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        c.rules.forEachIndexed { i, r ->
            if (i > 0 && r.kind != RuleKind.BLOCK && c.rules[i - 1].kind != RuleKind.BLOCK) T("или", 12.sp, C.Violet, FontWeight.SemiBold)
            RuleCard(c, r)
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("+ Ещё случай, когда включать", false, {
            Relays.put(c.copy(rules = c.rules + OutRule(OutTemplates.newRuleId(), RuleKind.LEVEL, main = Threshold("coolant", true, 95f, 91f))))
        })
        Chip("+ Запрет: никогда не включать, если…", false, {
            Relays.put(c.copy(rules = c.rules + OutRule(OutTemplates.newRuleId(), RuleKind.BLOCK, main = Threshold("battery", false, 11.5f, 11.8f))))
        })
        Chip("+ По событию (мотор завёлся…)", false, {
            Relays.put(c.copy(rules = c.rules + OutRule(OutTemplates.newRuleId(), RuleKind.EVENT, event = OutEvent.ENGINE_ON, holdS = 600)))
        })
    }

    // wiring
    Spacer(Modifier.height(10.dp))
    Section("Как подключить", true) {
    val preset = outPreset(c.load)
    WiringPicture(board, c.pin, preset, SensorBox.slots.collectAsState().value.filter { it.input in board.inputs }, Modifier.fillMaxWidth().height(280.dp))
    Spacer(Modifier.height(6.dp))
    preset.tips.forEach { Hint(Lucide.Lightbulb, it) }
    }

    // the channel itself
    Section("Название и кнопка на экране", false) {
    var name by remember(ch) { mutableStateOf(c.name) }
    androidx.compose.material3.OutlinedTextField(
        value = name, onValueChange = { name = it; Relays.put(c.copy(name = it.ifBlank { "Выход $ch" })) }, singleLine = true,
        label = { FieldLabel("Название") }, modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
    )
    Spacer(Modifier.height(8.dp))
    T("Виджет на экран", 13.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Кнопка вкл/выкл", c.widget == "button", { Relays.put(c.copy(widget = "button")) }, Lucide.Power)
        val smooth = c.load == LoadKind.MOSFET || c.load == LoadKind.LED
        val dim = Modifier.alpha(if (smooth) 1f else 0.4f)
        Chip("Ползунок 0–100 %", c.widget == "slider", { if (smooth) Relays.put(c.copy(widget = "slider")) }, Lucide.SlidersHorizontal, dim)
        Chip("Крутилка 0–100 %", c.widget == "knob", { if (smooth) Relays.put(c.copy(widget = "knob")) }, Lucide.CircleGauge, dim)
    }
    if (c.widget != "button" && c.pwm(board)) {
        // the level from the screen, or from a real potentiometer on the box
        val pots = SensorBox.slots.collectAsState().value.filter { it.unit == "%" || it.preset == "pot" }
        T("Уровень задаёт", 13.sp, C.Sub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Виджет на экране", c.levelSrc.isEmpty(), { Relays.put(c.copy(levelSrc = "")) })
            pots.forEach { p -> Chip("Потенциометр ${p.input}: ${p.name}", c.levelSrc == "box_${p.input}", { Relays.put(c.copy(levelSrc = "box_${p.input}")) }) }
        }
        if (pots.isEmpty()) SettingHint("Настоящую крутилку можно поставить на панель: подключите потенциометр к входу платы (в выборе датчика «Потенциометр»), и он появится здесь")
    }
    if (c.load != LoadKind.MOSFET && c.load != LoadKind.LED) T("Плавная регулировка только с MOSFET-модулем или светодиодом на пинах ${board.pwmPins.joinToString(", ")}", 13.sp, C.Sub)
    if (c.widget != "button" && !c.pwm(board)) T(
        if (c.load != LoadKind.MOSFET && c.load != LoadKind.LED) "Плавно умеет только MOSFET-модуль или светодиод: реле так не может, будет просто вкл/выкл"
        else "Плавно работает только на пинах ${board.pwmPins.joinToString(", ")}: переставьте выход на один из них", 12.sp, C.Orange)
    Spacer(Modifier.height(4.dp))
    var look by remember(ch) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ButtonFace(c, true, Modifier.width(110.dp).height(76.dp))
        Spacer(Modifier.width(6.dp))
        ButtonFace(c, false, Modifier.width(110.dp).height(76.dp))
        Spacer(Modifier.width(10.dp))
        Chip("Вид кнопки…", false, { look = true }, Lucide.Palette)
    }
    if (look) ButtonLookDialog(c) { look = false }
    Spacer(Modifier.height(6.dp))
    var shown by remember(ch) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip("Показать на «Мой экран»", false, {
            shown = if (Relays.showOnMine(ch)) "Добавлено на «Мой экран»" else "Уже там. На главную: долгое нажатие, Добавить виджет, Выходы"
        }, Lucide.LayoutDashboard)
        Spacer(Modifier.width(8.dp))
        if (shown.isNotEmpty()) T(shown, 13.sp, C.Green)
    }
    }
    Section("Пин и модуль: ${c.pin} · ${c.load.title}", false) {
    T("Пин платы", 14.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // every holder of a pin, not only the other outputs: displays and thermocouples too
        val owners = SensorBox.pinOwners(board, "out:$ch")
        board.outPins.forEach { p ->
            val usedBy = owners[p]
            Chip(p + (usedBy?.let { " · занят: $it" } ?: ""), c.pin == p, { if (usedBy == null) Relays.put(c.copy(pin = p)) },
                modifier = Modifier.alpha(if (usedBy == null) 1f else 0.4f))
        }
    }
    Spacer(Modifier.height(8.dp))
    T("Что подключено", 13.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LoadKind.entries.forEach { l -> Chip(l.title, c.load == l, { Relays.put(c.copy(load = l, activeLow = l == LoadKind.RELAY)) }) }
    }
    T(c.load.sub, 12.sp, C.Sub)
    load3d(c.load)?.let { Model3D(it, Modifier.fillMaxWidth().height(170.dp)) }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Реле работает наоборот", 15.sp)
            SettingHint("Включено у большинства синих модулей реле (вход IN, на плате оптрон). Если реле щёлкает, когда должно быть выключено, переключите")
        }
        Switch(c.activeLow, { Relays.put(c.copy(activeLow = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
    }
    }
    val risky = (c.icon == "pump" && c.lost == LostState.ON) || (c.icon == "fan" && c.lost == LostState.OFF)
    Section("Защита и что делать без связи" + if (risky) " · ⚠" else "", risky) {
    Stepper("Не выключать раньше чем через", c.minOnS, "с", 0, 600, 5) { Relays.put(c.copy(minOnS = it)) }
    Stepper("Не включать снова раньше чем через", c.minOffS, "с", 0, 600, 1) { Relays.put(c.copy(minOffS = it)) }
    Stepper("Не держать включённым дольше (0 = без предела)", c.maxOnS, "с", 0, 3600, 10) { Relays.put(c.copy(maxOnS = it)) }
    Spacer(Modifier.height(6.dp))
    SettingHint("Если связи с магнитолой нет (зависла, выдернули кабель), плата сама:")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LostState.entries.forEach { l -> Chip(l.title, c.lost == l, { Relays.put(c.copy(lost = l, lostHoldS = if (l == LostState.HOLD && c.lostHoldS == 0) 60 else c.lostHoldS)) }) }
    }
    if (c.lost == LostState.HOLD) Stepper("Подержать включённым", c.lostHoldS, "с", 5, 600, 5) { Relays.put(c.copy(lostHoldS = it)) }
    when {
        c.icon == "pump" && c.lost == LostState.ON -> Hint(Lucide.TriangleAlert, "Насос, который остаётся включённым без связи, может качать без остановки. Лучше «выключить»", C.Red)
        c.icon == "fan" && c.lost == LostState.OFF -> Hint(Lucide.TriangleAlert, "Вентилятор лучше включить, когда связи нет: перегреться хуже, чем лишний раз покрутиться", C.Orange)
    }
    }
}

/** A part of a long page that opens on a tap: its title row is a full-width target. */
@Composable
private fun Section(title: String, startOpen: Boolean, content: @Composable () -> Unit) {
    var open by remember(title.substringBefore(":")) { mutableStateOf(startOpen) }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .border(1.dp, C.Border, RoundedCornerShape(12.dp)).clickable { open = !open }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, 16.sp, if (title.endsWith("⚠")) C.Orange else C.Text, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
    }
    if (open) Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) { content() }
}

/** One rule as a sentence with its numbers in steppers and the live value next to its source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(c: OutChannel, r: OutRule) {
    fun put(n: OutRule) = Relays.put(c.copy(rules = c.rules.map { if (it.id == r.id) n else it }))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (r.kind == RuleKind.BLOCK) C.Red.copy(alpha = 0.06f) else C.CardHi)
            .border(1.dp, if (r.kind == RuleKind.BLOCK) C.Red.copy(alpha = 0.4f) else C.Border, RoundedCornerShape(12.dp)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(when (r.kind) { RuleKind.LEVEL -> if (r.blink) "Мигать" else "Включать"; RuleKind.BLOCK -> "Никогда не включать"; RuleKind.EVENT -> "Когда" }, 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(r.enabled, { put(r.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(48.dp).clickable {
                Relays.put(c.copy(rules = c.rules - r))
                offerUndo("Правило удалено") { Relays.channel(c.ch)?.let { cur -> Relays.put(cur.copy(rules = cur.rules + r)) } }
            }, contentAlignment = Alignment.Center) { Icon(Lucide.Trash2, "Удалить правило", tint = C.Sub, modifier = Modifier.size(22.dp)) }
        }
        when (r.kind) {
            RuleKind.LEVEL, RuleKind.BLOCK -> {
                val m = r.main ?: return@Column
                ThresholdRow(if (r.kind == RuleKind.BLOCK) "если" else "когда", m, showOff = r.kind == RuleKind.LEVEL, selfCh = c.ch) { put(r.copy(main = it)) }
                r.also.forEachIndexed { i, t ->
                    ThresholdRow("и если", t, showOff = false, onRemove = { put(r.copy(also = r.also.filterIndexed { j, _ -> j != i })) }, selfCh = c.ch) { n ->
                        put(r.copy(also = r.also.mapIndexed { j, x -> if (j == i) n else x }))
                    }
                }
                if (r.kind == RuleKind.LEVEL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip("+ И ещё условие", false, { put(r.copy(also = r.also + Threshold("rpm", true, 400f, 300f))) })
                    }
                    StepperF("если держится дольше", r.onDelayS, "с", 0f, 60f, 0.5f) { put(r.copy(onDelayS = it)) }
                    StepperF("и не выключать ещё", r.offDelayS, "с", 0f, 600f, 5f) { put(r.copy(offDelayS = it)) }
                    if (c.load == LoadKind.LED) Row(verticalAlignment = Alignment.CenterVertically) {
                        T("Мигать вместо горения", 13.sp, modifier = Modifier.weight(1f))
                        Switch(r.blink, { put(r.copy(blink = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
                    }
                }
            }
            RuleKind.EVENT -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutEvent.entries.forEach { e -> Chip(e.title, r.event == e, { put(r.copy(event = e)) }) }
                }
                T("включить", 13.sp, C.Sub)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "пока магнитола не уснёт", 60 to "на 1 мин", 300 to "на 5 мин", 600 to "на 10 мин", 1800 to "на 30 мин").forEach { (s, l) ->
                        Chip(l, r.holdS == s, { put(r.copy(holdS = s)) })
                    }
                }
            }
        }
    }
}

/** «[source] выше [on]», and «выключать, когда он ниже [off]» under it; the live value beside the source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThresholdRow(lead: String, t: Threshold, showOff: Boolean, onRemove: (() -> Unit)? = null, selfCh: Int = 0, onChange: (Threshold) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val src = RuleSources.of(t.src) ?: RuleSources.builtIn.first()
    val live = liveValue(t.src)
    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        T(lead, 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
        Box {
            Chip(src.title + (live?.let { " · " + RuleRunner.num(t.src, it) } ?: ""), true, { picking = true })
            CarDropdownMenu(picking, { picking = false }) {
                // an output can't be its own condition
                RuleSources.all().filter { it.id != "out_$selfCh" }.forEach { s ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(s.title) }, onClick = {
                        picking = false
                        if (s.bool) { onChange(Threshold(s.id, true, 1f, 1f)); return@DropdownMenuItem }
                        // the new source's own scale: start in its middle with a sensible gap
                        val on = ((s.min + s.max) / 2 / s.step).toInt() * s.step
                        val gap = s.step * if (s.step >= 1f) 4 else 4
                        onChange(Threshold(s.id, t.above, on, if (t.above) on - gap else on + gap))
                    })
                }
            }
        }
        if (src.bool) {
            // another output: on or off, nothing in between
            Chip("включён", t.above, { onChange(Threshold(t.src, true, 1f, 1f)) })
            Chip("выключен", !t.above, { onChange(Threshold(t.src, false, 0f, 0f)) })
        } else {
            // [выше | ниже]: the off value goes to the other side, keeping its gap
            val gapAbs = kotlin.math.abs(t.on - t.off)
            Chip("выше", t.above, { if (!t.above) onChange(t.copy(above = true, off = t.on - gapAbs)) })
            Chip("ниже", !t.above, { if (t.above) onChange(t.copy(above = false, off = t.on + gapAbs)) })
            // the off value moves along, keeping the gap the driver set
            ValueStepper(t.on, src) { v -> onChange(t.copy(on = v, off = v - (t.on - t.off))) }
        }
        if (onRemove != null) IconTap(Lucide.X, "Убрать условие", onRemove, Modifier.align(Alignment.CenterVertically))
    }
    if (showOff && !src.bool) FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        T("Выключать, когда станет ${if (t.above) "ниже" else "выше"}", 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
        ValueStepper(t.off, src) { v -> onChange(t.copy(off = if (t.above) minOf(v, t.on) else maxOf(v, t.on))) }
        val gap = kotlin.math.abs(t.on - t.off)
        T(if (gap < src.step * 2) "Нужен зазор хотя бы ${RuleRunner.num(src.id, src.step * 2)}, иначе реле будет щёлкать" else "между ними выход остаётся как был", 13.sp,
            if (gap < src.step * 2) C.Orange else C.Sub, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

// the values are read as they are at each refresh of the relay status (four times a second), not followed one by one
@android.annotation.SuppressLint("StateFlowValueCalledInComposition")
@Composable
private fun liveValue(src: String): Float? {
    Relays.status.collectAsState().value // refreshes four times a second with the engine
    val e = kg.forester.sg.data.Repo.live.value
    return when (src) {
        "coolant" -> e.coolantC?.toFloat()
        "oil" -> kg.forester.sg.data.Repo.guard.value.oilC
        "intake" -> e.intakeC?.toFloat()
        "atf" -> kg.forester.sg.data.Repo.tcu.value?.atfC?.toFloat()
        "rpm" -> e.rpm?.toFloat()
        "speed" -> e.speedKmh?.toFloat()
        "boost" -> e.gaugeKpa?.let { it / 100f }
        "afr" -> e.afr
        "throttle" -> e.throttlePct
        "battery" -> e.batteryV
        else -> when {
            src.startsWith("box_") -> SensorBox.readings.value[src.removePrefix("box_")]?.value
            src.startsWith("out_") -> src.removePrefix("out_").toIntOrNull()?.let { Relays.status.value[it] }?.let { if (it.want) 1f else 0f }
            else -> null
        }
    }
}

@Composable
private fun ValueStepper(v: Float, s: kg.forester.sg.data.RuleSource, onChange: (Float) -> Unit) {
    // in miles a speed goes in whole steps of the shown unit (and is kept in km/h)
    val shownSpeed = s.id == "speed" && kg.forester.sg.data.DistanceUnits.miles
    fun snap(x: Float) = (Math.round(x / s.step) * s.step).coerceIn(s.min, s.max)
    fun stepped(sign: Int): Float = if (!shownSpeed) snap(v + sign * s.step) else
        kg.forester.sg.data.DistanceUnits.speedToKmh((Math.round(kg.forester.sg.data.DistanceUnits.speed(v) / s.step) + sign) * s.step.toDouble()).toFloat().coerceIn(s.min, s.max)
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clickable { onChange(stepped(-1)) }, contentAlignment = Alignment.Center) { Icon(Lucide.Minus, null, tint = C.Text, modifier = Modifier.size(18.dp)) }
        T(RuleRunner.num(s.id, v), 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
        Box(Modifier.size(48.dp).clickable { onChange(stepped(1)) }, contentAlignment = Alignment.Center) { Icon(Lucide.Plus, null, tint = C.Text, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun Stepper(label: String, v: Int, unit: String, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 13.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable { onChange((v - step).coerceIn(min, max)) }, contentAlignment = Alignment.Center) { Icon(Lucide.Minus, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
            T("$v $unit", 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
            Box(Modifier.size(48.dp).clickable { onChange((v + step).coerceIn(min, max)) }, contentAlignment = Alignment.Center) { Icon(Lucide.Plus, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun StepperF(label: String, v: Float, unit: String, min: Float, max: Float, step: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 13.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable { onChange((v - step).coerceIn(min, max)) }, contentAlignment = Alignment.Center) { Icon(Lucide.Minus, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
            T("${RuleRunner.fmt(v)} $unit", 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
            Box(Modifier.size(48.dp).clickable { onChange((v + step).coerceIn(min, max)) }, contentAlignment = Alignment.Center) { Icon(Lucide.Plus, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** How an output module is wired, as a "sensor" for the wiring picture the inputs use. */
private fun outPreset(l: LoadKind): BoxPreset {
    fun p(title: String, wires: List<BoxWire>, tips: List<String>) =
        BoxPreset("out_${l.name}", title, "", BoxKind.OUT, "", 0, "", 0f, 1f, wires, tips)
    return when (l) {
        LoadKind.RELAY -> p("Модуль реле", listOf(
            BoxWire("VCC модуля", "5V", WIRE_RED), BoxWire("GND модуля", "GND", WIRE_BLACK), BoxWire("IN модуля", "SIG", WIRE_YELLOW),
        ), listOf(
            "Реле модуля включает не саму нагрузку, а автомобильное реле на 30–40 А, и уже оно вентилятор или насос. Предохранитель на каждую нагрузку.",
            "Катушкам реле нужно своё питание 5 В от понижающего модуля с 12 В: снимите перемычку JD-VCC и подайте 5 В туда. От USB магнитолы хватит только на одно реле.",
            "Берите модуль с оптронами: так помехи от реле не дойдут до платы.",
            "Модуль с реле на 12 В: VCC модуля от 5 В платы, а 12 В через предохранитель на JD-VCC (перемычку снять). Без оптрона и JD-VCC такой модуль от платы не управится, берите вариант «Реле 12 В через транзистор».",
        ))
        LoadKind.COIL12 -> p("Ключ: транзистор или MOSFET", listOf(
            BoxWire("База BC337 через 1 кОм (или затвор IRLZ44N через 100 Ом)", "SIG", WIRE_YELLOW),
            BoxWire("Эмиттер (исток) на массу", "GND", WIRE_BLACK),
            BoxWire("+12 В на контакт 86 катушки реле", "12V", WIRE_PURPLE),
        ), listOf(
            "Пин платы даёт 5 В и до 20 мА, а катушке 12-вольтового реле нужно 12 В и около 150 мА. Поэтому между ними ключ: транзистор BC337 (база через резистор 1 кОм) или логический MOSFET IRLZ44N, AO3400 (затвор через 100 Ом и 10 кОм на массу).",
            "Контакт 85 катушки на коллектор (сток) ключа, контакт 86 на +12 В через предохранитель 5 А. Ключ замыкает катушку на массу.",
            "Диод 1N4007 прямо на катушке: полоской к +12 В (контакт 86), другим концом к 85. Без него выброс от катушки убьёт транзистор.",
            "Нагрузка через контакты реле: 30 на +12 В через предохранитель под её ток, 87 на вентилятор или насос.",
            "Масса ключа, масса платы и минус 12 В в одной точке.",
        ))
        LoadKind.MOSFET -> p("MOSFET-модуль", listOf(
            BoxWire("SIG (IN) модуля", "SIG", WIRE_YELLOW), BoxWire("GND модуля", "GND", WIRE_BLACK),
        ), listOf(
            "Нагрузка подключается к выходу модуля, плюс 12 В через предохранитель. Для мотора (вентилятор, насос) нужен диод параллельно нагрузке, если его нет на модуле.",
            "Между входом модуля и массой резистор 10 кОм: пока плата перезагружается, нагрузка не включится сама.",
        ))
        LoadKind.SSR -> p("Твердотельное реле", listOf(
            BoxWire("Вход + (3)", "SIG", WIRE_YELLOW), BoxWire("Вход − (4)", "GND", WIRE_BLACK),
        ), listOf("Только реле DC-DC: реле для переменного тока (AC) постоянный ток не выключит."))
        LoadKind.LED -> p("Светодиод", listOf(
            BoxWire("Анод (+, длинная нога) через резистор 330 Ом", "SIG", WIRE_YELLOW), BoxWire("Катод (−)", "GND", WIRE_BLACK),
        ), listOf("Не больше 15 мА на пин. Лампу на 12 В подключайте через MOSFET-модуль."))
    }
}


private val LOOK_COLORS = listOf(
    0xFF22C55E, 0xFF16A34A, 0xFF84CC16, 0xFFFACC15, 0xFFF59E0B, 0xFFF97316, 0xFFEF4444, 0xFFE11D48, 0xFFEC4899, 0xFFA855F7,
    0xFF8B5CF6, 0xFF3B82F6, 0xFF0EA5E9, 0xFF06B6D4, 0xFF14B8A6, 0xFFFFFFFF, 0xFF94A3B8, 0xFF64748B, 0xFF334155,
)

/** The output's button in one position, as the widget draws it: its icon, its text and its colour for that position. */
@Composable
fun ButtonFace(c: OutChannel, on: Boolean, m: Modifier, blinking: Boolean = false) {
    val col = Color(if (on) c.onColor else c.offColor)
    Box(
        m.clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = if (on) 0.24f else 0.10f))
            .border(2.dp, col.copy(alpha = if (on) 1f else 0.6f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Icon(outIcon(c.icon), null, tint = col, modifier = Modifier.size(30.dp))
            T(if (blinking) "мигает" else if (on) c.onText else c.offText, 14.sp, col, FontWeight.Bold, maxLines = 1)
        }
    }
}

/** «Вид кнопки»: the text, the words and colours for on and off, the icon by topic; a live preview of both positions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonLookDialog(c0: OutChannel, onClose: () -> Unit) {
    var c by remember { mutableStateOf(c0) }
    var group by remember { mutableStateOf(OUT_ICON_SET.firstOrNull { it.id == c0.icon }?.group ?: OUT_ICON_SET.first().group) }
    CarDialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(820.dp).screenCap().clip(RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, RoundedCornerShape(16.dp))
                .cueScroll().padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Вид кнопки: ${c.name}", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Отмена", false, onClose)
                Spacer(Modifier.width(8.dp))
                Chip("Готово", true, { Relays.put(c); onClose() })
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T("включено", 12.sp, C.Sub)
                    ButtonFace(c, true, Modifier.width(150.dp).height(110.dp))
                    Spacer(Modifier.height(8.dp))
                    T("выключено", 12.sp, C.Sub)
                    ButtonFace(c, false, Modifier.width(150.dp).height(110.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    LookField("Надпись над кнопкой (пусто = название выхода)", c.label) { c = c.copy(label = it.take(24)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { LookField("Текст, когда включено", c.onText) { c = c.copy(onText = it.take(12)) } }
                        Box(Modifier.weight(1f)) { LookField("Текст, когда выключено", c.offText) { c = c.copy(offText = it.take(12)) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    T("Цвет, когда включено", 13.sp, C.Sub)
                    ColorRow(c.onColor) { c = c.copy(onColor = it) }
                    Spacer(Modifier.height(6.dp))
                    T("Цвет, когда выключено", 13.sp, C.Sub)
                    ColorRow(c.offColor) { c = c.copy(offColor = it) }
                }
            }
            Spacer(Modifier.height(12.dp))
            T("Иконка", 15.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OUT_ICON_SET.map { it.group }.distinct().forEach { g -> Chip(g, g == group, { group = g }) }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OUT_ICON_SET.filter { it.group == group }.forEach { ic ->
                    val sel = c.icon == ic.id
                    Column(
                        Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) C.Purple.copy(alpha = 0.25f) else C.CardHi)
                            .border(if (sel) 2.dp else 1.dp, if (sel) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(12.dp))
                            .clickable { c = c.copy(icon = ic.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(ic.v, null, tint = if (sel) Color(c.onColor) else C.Text, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(4.dp))
                        T(ic.title, 12.sp, C.Sub, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** No taller than most of the screen: the dialog scrolls inside. */
@Composable
private fun Modifier.screenCap(): Modifier = this.heightIn(max = (canvasScreen().screenHeightDp * 0.92f).dp)

@Composable
private fun LookField(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { FieldLabel(label) },
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(current: Long, onPick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LOOK_COLORS.forEach { col ->
            val sel = col == current
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(col))
                    .border(if (sel) 3.dp else 1.dp, if (sel) C.Text else C.Border, CircleShape).clickable { onPick(col) },
            )
        }
    }
}


/**
 * The "крутилка": a 270° knob for an output's level. Drag round it to set the level (sent when the finger lifts),
 * tap the middle for on/off; with a real potentiometer as the source it only shows where that one stands.
 */
@Composable
private fun Knob(c: OutChannel, on: Boolean, m: Modifier) {
    val pot = c.levelSrc.isNotEmpty()
    var v by remember(c.level) { mutableStateOf(c.level.toFloat()) }
    val shown = if (pot) (Relays.status.collectAsState().value[c.ch]?.let { c.level.toFloat() } ?: v) else v
    val col = Color(if (on) c.onColor else c.offColor)
    androidx.compose.foundation.layout.BoxWithConstraints(m, contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight)
        Box(
            Modifier.size(d).pointerInput(c.ch, pot) {
                if (pot) return@pointerInput
                detectDragGestures(
                    onDragEnd = { Relays.setLevel(c.ch, v.toInt()) },
                ) { change, _ ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    // 0 % at the lower left (135°), 100 % at the lower right (405°), clockwise
                    var a = Math.toDegrees(kotlin.math.atan2((change.position.y - cy).toDouble(), (change.position.x - cx).toDouble())).toFloat()
                    if (a < 90f) a += 360f
                    v = ((a - 135f) / 270f * 100f).coerceIn(0f, 100f)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val stroke = size.minDimension * 0.09f
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(C.Border, 135f, 270f, false, tl, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawArc(col, 135f, 270f * shown / 100f, false, tl, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                // the knob's mark
                val r = size.minDimension / 2 - stroke * 1.9f
                val ang = Math.toRadians((135.0 + 270.0 * shown / 100.0))
                val cx = size.width / 2
                val cy = size.height / 2
                drawCircle(C.DialogHi, r, androidx.compose.ui.geometry.Offset(cx, cy))
                // the mark at the knob's rim, clear of the number in the middle
                drawLine(col, androidx.compose.ui.geometry.Offset(cx + (r * 0.80f) * kotlin.math.cos(ang).toFloat(), cy + (r * 0.80f) * kotlin.math.sin(ang).toFloat()),
                    androidx.compose.ui.geometry.Offset(cx + (r * 0.97f) * kotlin.math.cos(ang).toFloat(), cy + (r * 0.97f) * kotlin.math.sin(ang).toFloat()), stroke * 0.45f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Column(
                Modifier.clip(CircleShape).clickable { Relays.toggle(c.ch) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(outIcon(c.icon), null, tint = col, modifier = Modifier.size((d.value * 0.16f).coerceIn(14f, 28f).dp))
                T(if (on) "${shown.toInt()} %" else c.offText, (d.value * 0.12f).coerceIn(11f, 22f).sp, col, FontWeight.Bold, maxLines = 1)
                if (pot) T("потенциометр", 11.sp, C.Sub, maxLines = 1)
            }
        }
    }
}
