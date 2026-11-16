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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Cable
import com.composables.icons.lucide.Car
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Microchip
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.TriangleAlert
import kg.forester.sg.data.CanBusGuide
import kg.forester.sg.data.CanBusGuide.Scenario
import kg.forester.sg.data.Repo
import kg.forester.sg.data.tr
import kg.forester.sg.ssm.CanBaudProbe
import kg.forester.sg.ssm.CanBitrates
import kg.forester.sg.ssm.CanMonitor

/** The wizard's steps: the left column lists them, the right side shows the open one. */
private enum class WizStep(val n: Int, val title: String) {
    SCENARIO(1, "Что подключаем"), BUS(2, "Машина и шина"), WIRING(3, "Подключение"), TERM(4, "Терминатор"), SPEED(5, "Скорость"), CHECK(6, "Проверка"),
}

/**
 * «Мастер подключения CAN»: what for (an own sensor, the car's bus, a sports ECU), which bus of which car and its
 * speed, a drawing of the wiring, the terminator checked with a multimeter, the speed found by listening, and a live
 * check. Every answer is saved as it is given (Repo.settings.canScenario, canBusId, canKbit, canListenOnly).
 */
@Composable
fun CanWizardDialog(onClose: () -> Unit) {
    val s = Repo.settings
    var scenario by remember { mutableStateOf(Scenario.of(s.canScenario)) }
    var busId by remember { mutableStateOf(s.canBusId) }
    var make by remember { mutableStateOf(CanBusGuide.bus(s.canBusId)?.make) }
    var step by remember { mutableStateOf(if (scenario == null) WizStep.SCENARIO else WizStep.SPEED) }
    var sniffer by remember { mutableStateOf(false) }
    if (sniffer) { CanSnifferDialog { sniffer = false }; return }
    val bus = CanBusGuide.bus(busId)
    CarSheet(onClose, 980.dp, 660.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Мастер подключения CAN", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f, fill = false)) {
            Column(Modifier.width(250.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WizStep.entries.forEach { st ->
                    val status = when (st) {
                        WizStep.SCENARIO -> scenario?.title ?: "не выбрано"
                        WizStep.BUS -> when (scenario) {
                            Scenario.CAR -> bus?.let { "${it.make}: ${CanBitrates.label(it.kbit)}" } ?: "не выбрана"
                            Scenario.ECU -> "скорость из настроек ЭБУ"
                            Scenario.SENSOR -> "своя шина"
                            null -> "—"
                        }
                        WizStep.WIRING -> when { scenario == Scenario.SENSOR -> "120 Ом на обоих концах"; bus?.tap == CanBusGuide.Tap.HEAD_UNIT -> "за магнитолой"; else -> "разъём OBD" }
                        WizStep.TERM -> "мультиметром, ~60 Ом"
                        WizStep.SPEED -> CanBitrates.label(s.canKbit) + if (s.canListenOnly) ", только приём" else ""
                        WizStep.CHECK -> "${CanMonitor.rows.value.size} ID на шине"
                    }
                    WizRow(st, status, step == st) { step = st }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                androidx.compose.runtime.key(step) {
                    when (step) {
                        WizStep.SCENARIO -> ScenarioStep(scenario) { sc ->
                            scenario = sc
                            s.canScenario = sc.id
                            s.canListenOnly = CanBusGuide.listenOnly(sc)
                            step = WizStep.BUS
                        }
                        WizStep.BUS -> BusStep(scenario, make, busId, { make = it }) { b ->
                            busId = b.id
                            s.canBusId = b.id
                            s.canKbit = b.kbit
                        }
                        WizStep.WIRING -> WiringStep(scenario, bus)
                        WizStep.TERM -> TerminationStep(scenario, bus)
                        WizStep.SPEED -> SpeedStep(scenario)
                        WizStep.CHECK -> CheckStep { sniffer = true }
                    }
                    val next = WizStep.entries.getOrNull(step.ordinal + 1)
                    Spacer(Modifier.height(14.dp))
                    if (next != null) BigButton("Дальше: ${next.title.lowercase()}", Lucide.ArrowRight, { step = next }, enabled = scenario != null)
                    else BigButton("Готово", Lucide.Check, onClose)
                }
            }
        }
    }
}

@Composable
private fun WizRow(st: WizStep, status: String, open: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(if (open) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(1.dp, if (open) C.Purple else C.Border, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(C.Purple), contentAlignment = Alignment.Center) {
            T("${st.n}", 14.sp, Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(st.title, 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(status, 12.sp, C.Sub, maxLines = 2)
        }
    }
}

@Composable
private fun WizCard(icon: ImageVector, title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 72.dp).clip(shape)
            .background(if (selected) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Purple else C.Border, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, weight = FontWeight.SemiBold)
            T(sub, 13.sp, C.Sub)
        }
    }
}

@Composable
private fun WizWarn(text: String, color: Color = C.Orange) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(10.dp),
    ) {
        Icon(Lucide.TriangleAlert, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        T(text, 13.sp)
    }
}

// ---------------------------------------------------------------- 1. what for

@Composable
private fun ScenarioStep(cur: Scenario?, pick: (Scenario) -> Unit) {
    T("Что подключаем к адаптеру CAN", 17.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    WizCard(Lucide.Microchip, Scenario.SENSOR.title, Scenario.SENSOR.sub, cur == Scenario.SENSOR) { pick(Scenario.SENSOR) }
    WizCard(Lucide.Car, Scenario.CAR.title, Scenario.CAR.sub, cur == Scenario.CAR) { pick(Scenario.CAR) }
    WizCard(Lucide.Cpu, Scenario.ECU.title, Scenario.ECU.sub, cur == Scenario.ECU) { pick(Scenario.ECU) }
    Spacer(Modifier.height(6.dp))
    SettingHint("На шине машины адаптер только слушает: не подтверждает кадры и ничего не отправляет. На своей шине " +
        "(адаптер и датчик вдвоём) адаптер работает обычно, иначе датчику некому подтверждать кадры")
}

// ---------------------------------------------------------------- 2. which car, which bus

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BusStep(scenario: Scenario?, make: String?, busId: String, onMake: (String) -> Unit, onBus: (CanBusGuide.Bus) -> Unit) {
    when (scenario) {
        null -> { T("Сначала выберите, что подключаем", 14.sp, C.Sub); return }
        Scenario.SENSOR -> {
            T("Своя шина: адаптер и датчик", 17.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            T("Скорость задаёт датчик (обычно 500 кбит или 1 Мбит, смотрите его инструкцию). На шаге «Скорость» её можно подобрать " +
                "автоматически. Датчику нужен ответ на кадры: адаптер работает в обычном режиме", 14.sp)
            return
        }
        Scenario.ECU -> {
            T("Спортивный ЭБУ", 17.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            var ek by remember { mutableStateOf(Repo.settings.canKbit) }
            CanBusGuide.ECUS.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        T(e.name, 15.sp, weight = FontWeight.SemiBold)
                        T(e.note, 13.sp, C.Sub)
                    }
                    if (e.kbit != null) Chip(CanBitrates.label(e.kbit), ek == e.kbit, { ek = e.kbit; Repo.settings.canKbit = e.kbit })
                }
            }
            return
        }
        Scenario.CAR -> Unit
    }
    T("Машина", 17.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CanBusGuide.MAKES.forEach { m -> Chip(m, make == m, { onMake(m) }) }
    }
    if (make == null) return
    CanBusGuide.gatewayNote(make)?.let { Spacer(Modifier.height(6.dp)); WizWarn("Шлюз: $it") }
    Spacer(Modifier.height(8.dp))
    T("Шина", 17.sp, weight = FontWeight.SemiBold)
    CanBusGuide.busesOf(make).forEach { b ->
        val sel = b.id == busId
        Column(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                .background(if (sel) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                .border(if (sel) 2.dp else 1.dp, if (sel) C.Purple else C.Border, RoundedCornerShape(12.dp))
                .clickable { onBus(b) }.padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(b.name, 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T(CanBitrates.label(b.kbit), 15.sp, C.Violet, FontWeight.SemiBold)
            }
            T(listOfNotNull(
                b.obdPins?.let { "OBD: контакты ${it.first} (CAN-H) и ${it.second} (CAN-L)" } ?: "не на OBD",
                b.phys.title,
            ).joinToString(" · "), 13.sp, C.Sub)
            if (b.where.isNotEmpty()) T(b.where, 13.sp)
            if (b.note.isNotEmpty()) T(b.note, 12.sp, C.Sub)
            b.transceiverNote?.let { T(it, 13.sp, C.Red) }
        }
    }
    Spacer(Modifier.height(6.dp))
    SettingHint("Скорости типичные: на конкретной машине бывает иначе. Шаг «Скорость» проверит её по живой шине, ничего в неё не отправляя")
}

// ---------------------------------------------------------------- 3. wiring

@Composable
private fun WiringStep(scenario: Scenario?, bus: CanBusGuide.Bus?) {
    when {
        scenario == Scenario.SENSOR || scenario == Scenario.ECU -> {
            T(if (scenario == Scenario.SENSOR) "Своя шина: 120 Ом на обоих концах" else "Шина ЭБУ для дашборда", 17.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OwnBusPicture(Modifier.fillMaxWidth().height(230.dp))
            Spacer(Modifier.height(6.dp))
            WireLine(1, "CAN-H к CAN-H, CAN-L к CAN-L, витой парой (скрутите провода, шаг 2–3 см). Длина до нескольких метров не важна")
            WireLine(2, "На каждом конце шины по резистору 120 Ом между H и L. У CANable свой терминатор (перемычка или переключатель): включите его, если адаптер на конце")
            WireLine(3, "Масса датчика и масса машины общие. Питание датчика от +12 В через предохранитель, не от адаптера")
            WireLine(4, "Ответвление к датчику в середине шины короткое, до 30 см")
            if (scenario == Scenario.ECU) WizWarn("У многих ЭБУ терминатор уже стоит внутри (у Link, Haltech настраивается). Проверьте на шаге «Терминатор»", C.Violet)
        }
        bus?.tap == CanBusGuide.Tap.HEAD_UNIT || (bus != null && bus.obdPins == null) -> {
            T("Подключение за магнитолой или блоком", 17.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            HeadUnitPicture(Modifier.fillMaxWidth().height(230.dp), bus)
            Spacer(Modifier.height(6.dp))
            WireLine(1, "Найдите витую пару CAN в разъёме магнитолы или блока (по схеме машины: CAN-H, CAN-L; у однопроводной один провод)")
            WireLine(2, "Подключитесь параллельно, не разрезая: прокалывающие клеммы или пайка с термоусадкой. Отвод к адаптеру до 30 см, тоже витой")
            WireLine(3, "Терминатор на адаптере выключите: на шине машины оба уже стоят")
            WireLine(4, "Масса адаптера на массу машины (там же в разъёме)")
            bus?.transceiverNote?.let { WizWarn(it, C.Red) }
        }
        else -> {
            val pins = bus?.obdPins ?: (6 to 14)
            T("Подключение к разъёму OBD", 17.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ObdPicture(Modifier.fillMaxWidth().height(250.dp), pins)
            Spacer(Modifier.height(6.dp))
            WireLine(1, "Контакт ${pins.first} к CAN-H адаптера, ${pins.second} к CAN-L, контакт 5 (сигнальная масса) к GND")
            WireLine(2, "Контакт 16 (+12 В) к адаптеру не подключайте: CANable питается от USB")
            WireLine(3, "Терминатор на адаптере выключите: на шине машины оба уже стоят")
            WireLine(4, "Готовый кабель OBD → DB9 подходит, если его распиновка совпадает с адаптером: проверьте мультиметром")
            bus?.make?.let { CanBusGuide.gatewayNote(it) }?.let { WizWarn("Шлюз: $it") }
        }
    }
}

@Composable
private fun WireLine(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(C.Violet), contentAlignment = Alignment.Center) {
            T("$n", 12.sp, Color.White, FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        T(text, 13.sp)
    }
}

private val WIRE_H = Color(0xFFFACC15)
private val WIRE_L = Color(0xFF4ADE80)
private val WIRE_GND = Color(0xFF94A3B8)

private fun DrawScope.label(tm: TextMeasurer, s: String, at: Offset, color: Color, size: Float = 12f, bold: Boolean = false, center: Boolean = false) {
    val r = tm.measure(tr(s), TextStyle(fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    drawText(r, topLeft = if (center) Offset(at.x - r.size.width / 2f, at.y - r.size.height / 2f) else at)
}

/** A box with its name and terminals on its left side: the adapter. Returns the terminals' points (H, L, GND). */
private fun DrawScope.adapter(tm: TextMeasurer, x: Float, y: Float, w: Float, h: Float, name: String, card: Color, edge: Color, text: Color, sub: Color, gnd: Boolean = true): List<Offset> {
    drawRoundRect(card, Offset(x, y), Size(w, h), CornerRadius(10f * density))
    drawRoundRect(edge, Offset(x, y), Size(w, h), CornerRadius(10f * density), style = Stroke(1.5f * density))
    label(tm, name, Offset(x + w / 2, y + 16f * density), text, 13f, bold = true, center = true)
    val names = if (gnd) listOf("CAN-H", "CAN-L", "GND") else listOf("CAN-H", "CAN-L")
    return names.mapIndexed { i, n ->
        val p = Offset(x, y + h * (0.42f + 0.2f * i))
        drawCircle(edge, 4f * density, p)
        label(tm, n, Offset(p.x + 8f * density, p.y - 8f * density), sub, 11f)
        p
    }
}

/** The OBD-II socket as seen from the driver's seat, pins 1–8 above 9–16; [pins] lit and wired to the adapter. */
@Composable
private fun ObdPicture(m: Modifier, pins: Pair<Int, Int>) {
    val tm = rememberTextMeasurer()
    val card = C.CardHi; val edge = C.Border; val text = C.Text; val sub = C.Sub; val warn = C.Red
    Canvas(m) {
        val d = density
        val sw = size.width * 0.5f
        val sh = size.height * 0.5f
        val sx = 16f * d
        val sy = size.height * 0.2f
        // the socket: a trapezoid, wider at the top
        val p = Path().apply {
            moveTo(sx, sy); lineTo(sx + sw, sy); lineTo(sx + sw - sw * 0.06f, sy + sh); lineTo(sx + sw * 0.06f, sy + sh); close()
        }
        drawPath(p, card)
        drawPath(p, edge, style = Stroke(2f * d))
        label(tm, "Разъём OBD-II (вид спереди)", Offset(sx, sy - 22f * d), sub, 12f)
        fun pinAt(n: Int): Offset {
            val top = n <= 8
            val i = if (top) n - 1 else n - 9
            val pitch = (sw - 40f * d) / 7f
            return Offset(sx + 20f * d + i * pitch, sy + if (top) sh * 0.32f else sh * 0.7f)
        }
        val lit = mapOf(pins.first to WIRE_H, pins.second to WIRE_L, 5 to WIRE_GND)
        for (n in 1..16) {
            val c = lit[n]
            val at = pinAt(n)
            drawCircle(c ?: edge, (if (c != null) 9f else 6f) * d, at)
            if (n == 16) drawCircle(warn, 9f * d, at, style = Stroke(2f * d))
            label(tm, "$n", Offset(at.x, at.y + (if (n <= 8) -17f else 17f) * d), if (c != null) text else sub, 10f, bold = c != null, center = true)
        }
        val ax = size.width * 0.72f
        val ah = size.height * 0.62f
        val term = adapter(tm, ax, size.height * 0.18f, size.width * 0.24f, ah, "CANable", card, edge, text, sub)
        listOf(pins.first, pins.second, 5).forEachIndexed { i, n ->
            val from = pinAt(n)
            val to = term[i]
            val midX = sx + sw + 20f * d + i * 14f * d
            val path = Path().apply {
                moveTo(from.x, from.y); lineTo(from.x, if (n <= 8) sy - 6f * d - i * 6f * d else sy + sh + 8f * d + i * 6f * d)
                lineTo(midX, if (n <= 8) sy - 6f * d - i * 6f * d else sy + sh + 8f * d + i * 6f * d); lineTo(midX, to.y); lineTo(to.x, to.y)
            }
            drawPath(path, listOf(WIRE_H, WIRE_L, WIRE_GND)[i], style = Stroke(3f * d))
        }
        label(tm, "16: +12 В, не подключать", Offset(sx, sy + sh + 34f * d), warn, 11f)
        label(tm, "терминатор адаптера: выкл", Offset(ax, size.height * 0.18f + ah + 6f * d), sub, 11f)
    }
}

/** A twisted pair between a unit and the head unit, the adapter tapped into its middle with a short stub. */
@Composable
private fun HeadUnitPicture(m: Modifier, bus: CanBusGuide.Bus?) {
    val tm = rememberTextMeasurer()
    val card = C.CardHi; val edge = C.Border; val text = C.Text; val sub = C.Sub
    val single = bus?.phys == CanBusGuide.Phys.SINGLE_WIRE
    Canvas(m) {
        val d = density
        val bw = size.width * 0.2f
        val bh = size.height * 0.34f
        val y0 = size.height * 0.1f
        listOf(0f to "Блок / шлюз\n(120 Ом внутри)", size.width - bw to "Магнитола\n(120 Ом внутри)").forEach { (x, n) ->
            drawRoundRect(card, Offset(x, y0), Size(bw, bh), CornerRadius(10f * d))
            drawRoundRect(edge, Offset(x, y0), Size(bw, bh), CornerRadius(10f * d), style = Stroke(1.5f * d))
            n.split("\n").forEachIndexed { i, line -> label(tm, line, Offset(x + bw / 2, y0 + bh * (0.35f + 0.3f * i)), if (i == 0) text else sub, if (i == 0) 13f else 11f, i == 0, center = true) }
        }
        val yH = y0 + bh * 0.4f
        val yL = y0 + bh * 0.62f
        // the pair, twisted
        val steps = 40
        val x1 = bw; val x2 = size.width - bw
        val ph = Path(); val pl = Path()
        for (i in 0..steps) {
            val x = x1 + (x2 - x1) * i / steps
            val k = kotlin.math.sin(i * Math.PI / 4).toFloat()
            val mid = (yH + yL) / 2; val amp = (yL - yH) / 2
            if (i == 0) { ph.moveTo(x, mid - amp * k); pl.moveTo(x, mid + amp * k) } else { ph.lineTo(x, mid - amp * k); pl.lineTo(x, mid + amp * k) }
        }
        drawPath(ph, WIRE_H, style = Stroke(3f * d))
        if (!single) drawPath(pl, WIRE_L, style = Stroke(3f * d))
        label(tm, if (single) "один провод CAN + масса" else "витая пара CAN-H / CAN-L", Offset((x1 + x2) / 2, y0 - 2f * d), sub, 11f, center = true)
        // the stub down to the adapter
        val tx = (x1 + x2) / 2
        val ay = size.height * 0.62f
        val term = adapter(tm, tx - size.width * 0.13f, ay, size.width * 0.26f, size.height * 0.34f, "CANable", card, edge, text, sub, gnd = false)
        val stubX = tx - size.width * 0.13f - 26f * d
        drawCircle(WIRE_H, 5f * d, Offset(tx - 6f * d, (yH + yL) / 2))
        drawPath(Path().apply { moveTo(tx - 6f * d, (yH + yL) / 2); lineTo(stubX, (yH + yL) / 2 + 16f * d); lineTo(stubX, term[0].y); lineTo(term[0].x, term[0].y) }, WIRE_H, style = Stroke(3f * d))
        if (!single) {
            drawCircle(WIRE_L, 5f * d, Offset(tx + 6f * d, (yH + yL) / 2))
            drawPath(Path().apply { moveTo(tx + 6f * d, (yH + yL) / 2); lineTo(stubX - 10f * d, (yH + yL) / 2 + 24f * d); lineTo(stubX - 10f * d, term[1].y); lineTo(term[1].x, term[1].y) }, WIRE_L, style = Stroke(3f * d))
        }
        label(tm, "отвод до 30 см", Offset(tx + size.width * 0.15f, ay + 10f * d), sub, 11f)
        label(tm, "терминатор адаптера: выкл", Offset(tx + size.width * 0.15f, ay + 28f * d), sub, 11f)
    }
}

/** An own bus: a straight pair, the sensor at one end and the adapter at the other, 120 Ω across each end. */
@Composable
private fun OwnBusPicture(m: Modifier) {
    val tm = rememberTextMeasurer()
    val card = C.CardHi; val edge = C.Border; val text = C.Text; val sub = C.Sub; val res = C.Orange
    Canvas(m) {
        val d = density
        val yH = size.height * 0.45f
        val yL = size.height * 0.62f
        val x1 = size.width * 0.18f
        val x2 = size.width * 0.82f
        drawLine(WIRE_H, Offset(x1, yH), Offset(x2, yH), 3f * d)
        drawLine(WIRE_L, Offset(x1, yL), Offset(x2, yL), 3f * d)
        label(tm, "CAN-H", Offset((x1 + x2) / 2, yH - 14f * d), sub, 11f, center = true)
        label(tm, "CAN-L", Offset((x1 + x2) / 2, yL + 14f * d), sub, 11f, center = true)
        fun resistor(x: Float) {
            drawLine(res, Offset(x, yH), Offset(x, yL), 2f * d)
            drawRect(card, Offset(x - 7f * d, yH + (yL - yH) * 0.2f), Size(14f * d, (yL - yH) * 0.6f))
            drawRect(res, Offset(x - 7f * d, yH + (yL - yH) * 0.2f), Size(14f * d, (yL - yH) * 0.6f), style = Stroke(2f * d))
            label(tm, "120 Ом", Offset(x, yL + 30f * d), res, 12f, bold = true, center = true)
        }
        resistor(x1 + 16f * d)
        resistor(x2 - 16f * d)
        fun node(cx: Float, name: String, sub2: String) {
            val w = size.width * 0.16f; val h = size.height * 0.3f
            drawRoundRect(card, Offset(cx - w / 2, size.height * 0.04f), Size(w, h), CornerRadius(10f * d))
            drawRoundRect(edge, Offset(cx - w / 2, size.height * 0.04f), Size(w, h), CornerRadius(10f * d), style = Stroke(1.5f * d))
            label(tm, name, Offset(cx, size.height * 0.04f + h * 0.38f), text, 13f, true, center = true)
            label(tm, sub2, Offset(cx, size.height * 0.04f + h * 0.7f), sub, 10f, center = true)
            drawLine(WIRE_H, Offset(cx - 5f * d, size.height * 0.04f + h), Offset(cx - 5f * d, yH), 3f * d)
            drawLine(WIRE_L, Offset(cx + 5f * d, size.height * 0.04f + h), Offset(cx + 5f * d, yL), 3f * d)
        }
        node(x1, "Датчик", "на конце шины")
        node((x1 + x2) / 2, "Ещё датчик", "отвод до 30 см")
        node(x2, "CANable", "терминатор вкл")
        label(tm, "Между H и L при выключенном питании: около 60 Ом", Offset(size.width / 2, size.height * 0.93f), sub, 12f, center = true)
    }
}

// ---------------------------------------------------------------- 4. the terminator, by multimeter

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminationStep(scenario: Scenario?, bus: CanBusGuide.Bus?) {
    var ohm by remember { mutableStateOf("") }
    T("Проверка терминатора мультиметром", 17.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    val pins = bus?.obdPins
    WireLine(1, "Зажигание выключено, ключ вынут; подождите пару минут, пока блоки уснут. На своей шине выключите питание датчиков")
    WireLine(2, "Адаптер от шины отключите (или выключите его терминатор)")
    WireLine(3, "Мультиметр в режим сопротивления, предел 200 Ом")
    WireLine(4, if (pins != null && scenario == Scenario.CAR) "Щупы на контакты ${pins.first} и ${pins.second} разъёма OBD" else "Щупы на провода CAN-H и CAN-L")
    if (bus?.needsOtherTransceiver == true) WizWarn("У этой шины (${bus.phys.title}) терминаторы другие, и 60 Ом там не будет: этот шаг для обычной двухпроводной шины", C.Violet)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormField("Показал, Ом", ohm, Modifier.width(160.dp), KeyboardType.Decimal) { t -> ohm = t.filter { it.isDigit() || it == '.' || it == ',' }.take(6) }
        Spacer(Modifier.width(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("60", "120", "40").forEach { v -> Chip("≈ $v", ohm == v, { ohm = v }) }
        }
    }
    val v = ohm.replace(',', '.').toFloatOrNull()
    if (v != null) {
        val r = CanBusGuide.termination(v)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background((if (r.ok) C.Green else C.Orange).copy(alpha = 0.14f))
                .border(1.dp, if (r.ok) C.Green else C.Orange, RoundedCornerShape(10.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (r.ok) Lucide.Check else Lucide.TriangleAlert, null, tint = if (r.ok) C.Green else C.Orange, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            T(r.text, 14.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingHint("Два резистора по 120 Ом параллельно дают 60. На шине машины они уже стоят в блоках: свой не нужен, лишний портит сигнал")
}

// ---------------------------------------------------------------- 5. speed: picked, or found by listening

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedStep(scenario: Scenario?) {
    val s = Repo.settings
    var kbit by remember { mutableStateOf(s.canKbit) }
    var listen by remember { mutableStateOf(s.canListenOnly) }
    val probe by CanBaudProbe.state.collectAsState()
    LaunchedEffect(probe) {
        val p = probe
        if (p is CanBaudProbe.State.Finished && p.outcome is CanBaudProbe.Outcome.Found) kbit = s.canKbit
    }
    T("Скорость шины", 17.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CanBitrates.CHOICES.forEach { k -> Chip(CanBitrates.label(k), kbit == k, { kbit = k; s.canKbit = k }) }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Только приём", 15.sp)
            T(if (scenario == Scenario.CAR) "На шине машины всегда: адаптер не подтверждает кадры и ничего не отправляет"
                else "Для своей шины выключено: датчику нужен кто-то, кто подтверждает кадры", 12.sp, C.Sub)
        }
        Switch(listen, { if (scenario != Scenario.CAR) { listen = it; s.canListenOnly = it } },
            enabled = scenario != Scenario.CAR, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
    }
    Spacer(Modifier.height(10.dp))
    when (val p = probe) {
        CanBaudProbe.State.Idle -> {
            BigButton("Подобрать скорость", Lucide.Search, {
                // the probe runs in the CANable session: it has to be the adapter
                if (s.adapter != "canable" && s.adapter != "auto") s.adapter = "canable"
                CanBaudProbe.request()
            })
            Spacer(Modifier.height(4.dp))
            SettingHint("Приложение по очереди слушает шину на 500, 250, 125, 1000, 100, 83,3, 50 и 33,3 кбит в режиме «только приём» и " +
                "выбирает скорость, на которой идут целые кадры без ошибок. В шину при этом ничего не отправляется. Зажигание должно быть включено")
        }
        CanBaudProbe.State.Waiting -> {
            T("Жду CANable на USB… Адаптер подключён и выбран в настройках (CANable или Авто)?", 14.sp)
            Chip("Отмена", false, { CanBaudProbe.cancel() })
        }
        is CanBaudProbe.State.Trying -> {
            T("Слушаю на ${CanBitrates.label(p.kbit)}…", 16.sp, C.Violet, FontWeight.SemiBold)
            ProbeTable(p.done)
            Chip("Остановить", false, { CanBaudProbe.cancel() })
        }
        is CanBaudProbe.State.Finished -> {
            val o = p.outcome
            when (o) {
                is CanBaudProbe.Outcome.Found -> T("Найдено: ${CanBitrates.label(o.kbit)}. Сохранено", 16.sp, C.Green, FontWeight.SemiBold)
                is CanBaudProbe.Outcome.Silent -> WizWarn("Ни на одной скорости не пришло целых кадров. Зажигание включено? Провода H и L не перепутаны? " +
                    "Шина за шлюзом или нужна другая (см. «Машина и шина»)")
                CanBaudProbe.Outcome.NoListenOnly -> WizWarn("Адаптер не включил режим «только приём», поэтому подбор не запускался: на чужой скорости " +
                    "он засыпал бы шину машины ошибками. Обновите прошивку slcan или выберите скорость вручную")
                CanBaudProbe.Outcome.Cancelled -> T("Остановлено", 14.sp, C.Sub)
            }
            when (o) {
                is CanBaudProbe.Outcome.Found -> ProbeTable(o.results)
                is CanBaudProbe.Outcome.Silent -> ProbeTable(o.results)
                else -> Unit
            }
            Spacer(Modifier.height(6.dp))
            Chip("Подобрать ещё раз", false, { CanBaudProbe.request() })
        }
    }
}

@Composable
private fun ProbeTable(results: List<CanBaudProbe.Result>) {
    Column(Modifier.padding(vertical = 6.dp)) {
        results.forEach { r ->
            Row(Modifier.padding(vertical = 1.dp)) {
                T(CanBitrates.label(r.kbit), 13.sp, if (r.valid) C.Green else C.Text, modifier = Modifier.width(110.dp))
                T(when {
                    !r.supported -> "адаптер не умеет эту скорость"
                    r.frames == 0 && r.errors == 0 -> "тишина"
                    else -> "кадров ${r.frames}, ID ${r.ids}" + if (r.errors > 0) ", ошибок ${r.errors}" else ""
                }, 13.sp, C.Sub)
            }
        }
    }
}

// ---------------------------------------------------------------- 6. live check

@Composable
private fun CheckStep(openSniffer: () -> Unit) {
    val rows by CanMonitor.rows.collectAsState()
    val msg by Repo.linkMessage.collectAsState()
    T("Проверка вживую", 17.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    T(msg.ifEmpty { "Адаптер не на связи" }, 14.sp)
    Spacer(Modifier.height(6.dp))
    val now = System.currentTimeMillis()
    val fps = rows.filter { now - it.lastMs < 2000 }.sumOf { it.hz.toDouble() }
    val load = CanMonitor.busLoad(Repo.settings.canKbit, now)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WizStat(Lucide.Cable, "${rows.size}", "ID на шине")
        WizStat(Lucide.Gauge, "%.0f".format(fps), "кадров/с")
        WizStat(Lucide.Gauge, "%.0f %%".format(load * 100), "загрузка шины")
    }
    Spacer(Modifier.height(8.dp))
    if (rows.isEmpty()) T("Кадров нет: проверьте скорость, провода и зажигание. Подбор скорости на шаге 5", 13.sp, C.Sub)
    else if (load > 0.7f) T("Шина загружена сильно: на медленном USB или Bluetooth часть кадров может теряться", 13.sp, C.Orange)
    Spacer(Modifier.height(8.dp))
    BigButton("Открыть «Что идёт по CAN»", Lucide.Search, openSniffer)
}

@Composable
private fun WizStat(icon: ImageVector, value: String, label: String) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(22.dp))
        T(value, 22.sp, weight = FontWeight.Bold)
        T(label, 12.sp, C.Sub)
    }
}
