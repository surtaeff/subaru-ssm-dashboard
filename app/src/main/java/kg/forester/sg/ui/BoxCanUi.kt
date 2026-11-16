package kg.forester.sg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.TriangleAlert
import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxCan
import kg.forester.sg.data.BoxCanCfg
import kg.forester.sg.data.BoxCanFilter
import kg.forester.sg.data.BoxCanStatus
import kg.forester.sg.data.BoxInfo
import kg.forester.sg.data.BoxKind
import kg.forester.sg.data.BoxPreset
import kg.forester.sg.data.BoxWire
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.WIRE_BLACK
import kg.forester.sg.data.WIRE_BLUE
import kg.forester.sg.data.WIRE_GREEN
import kg.forester.sg.data.WIRE_ORANGE
import kg.forester.sg.data.WIRE_RED
import kg.forester.sg.data.WIRE_YELLOW

/*
 * «CAN-шина» in «Датчики и экраны»: the ESP32-S3 box reads a CAN bus through a TJA1051T/3 (or SN65HVD230) transceiver on
 * three of its output pins, in place of a separate CANable. Settings, live state, and the wiring in the same picture
 * style as the sensors.
 */

private const val WIRE_3V3 = 0xFFFB7185
private const val WIRE_S = 0xFFA78BFA
private const val WIRE_CANH = 0xFFF59E0B
private const val WIRE_CANL = 0xFF10B981

/** The name printed on a transceiver module for a wire to [to]. */
internal fun canTerminal(to: String) = when (to) {
    "CANTX" -> "TXD"; "CANRX" -> "RXD"; "CANS" -> "S"; "3V3" -> "VIO"; "5V" -> "VCC"; "CANH" -> "CANH"; "CANL" -> "CANL"
    else -> to
}

/**
 * The transceiver as a module for the wiring picture: TJA1051T/3 (VCC 5 V, VIO 3.3 V for the ESP32's logic, S the
 * hardware silent mode) or SN65HVD230 (all on 3.3 V; its Rs is wired to ground on the usual blue modules).
 */
internal fun canPreset(c: BoxCanCfg): BoxPreset {
    val tja = c.chip != "sn65"
    val wires = listOfNotNull(
        BoxWire("TXD модуля", "CANTX", WIRE_YELLOW),
        BoxWire("RXD модуля", "CANRX", WIRE_BLUE),
        if (tja && c.s.isNotEmpty()) BoxWire("S модуля (тихий режим)", "CANS", WIRE_S) else null,
        if (tja) BoxWire("VCC модуля", "5V", WIRE_RED) else null,
        BoxWire(if (tja) "VIO модуля" else "3V3 модуля", "3V3", WIRE_3V3),
        BoxWire("GND модуля", "GND", WIRE_BLACK),
        BoxWire("CAN-H", "CANH", WIRE_CANH),
        BoxWire("CAN-L", "CANL", WIRE_CANL),
    )
    return BoxPreset(
        "can_" + (if (tja) "tja1051" else "sn65hvd230"), if (tja) "CAN TJA1051T/3" else "CAN SN65HVD230",
        if (tja) "Трансивер TJA1051T/3 (модуль с выводом VIO)" else "Трансивер SN65HVD230 (модуль VP230)",
        BoxKind.OUT, "", 0, "", 0f, 0f, wires, emptyList(),
    )
}

/** The pin string the wiring picture takes for the transceiver: "TX|RX|S". */
private fun canInput(c: BoxCanCfg) = "${c.tx}|${c.rx}|${if (c.chip != "sn65") c.s else ""}"

/** The line under «CAN-шина» in the steps. */
internal fun boxCanSummary(board: BoxBoard, c: BoxCanCfg, st: BoxCanStatus?, real: BoxInfo?): String = when {
    board != BoxBoard.ESP32S3 -> "только на ESP32-S3"
    !c.on -> "выключена: вместо CANable"
    real != null && !BoxCan.supported(real) -> "Нужна прошивка 6: обновите"
    st == null -> BoxCan.speedTitle(c.kbit) + ", ждёт плату"
    st.state == 'b' -> "bus off: проверьте проводку и скорость"
    else -> BoxCan.speedTitle(c.kbit) + " · " + (if (c.listenOnly) "слушает" else "своя шина") + " · ${st.perSecond.toInt()} кадр/с"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxCanPage(board: BoxBoard) {
    val c by BoxCan.cfg.collectAsState()
    val st by BoxCan.status.collectAsState()
    val info by SensorBox.info.collectAsState()
    val real = info?.takeIf { it.via != "демо" }
    var monitor by remember { mutableStateOf(false) }
    fun set(n: BoxCanCfg) = SensorBox.setCan(n)

    T("CAN-шина", 18.sp, weight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    T("ESP32-S3 читает CAN своим контроллером через модуль-трансивер, отдельный CANable не нужен. Кадры идут туда же, " +
        "что и от CANable: «Что идёт по CAN», свои значения из CAN, поток Link. Датчики и выходы платы работают как прежде", 13.sp, C.Sub)
    Spacer(Modifier.height(10.dp))
    if (board != BoxBoard.ESP32S3) {
        Hint(Lucide.Info, "CAN есть только у ESP32-S3: у неё свой контроллер CAN (TWAI). У Arduino Nano, Uno, Pro Micro и Mega его нет. " +
            "Выберите ESP32-S3 на шаге «Плата», если она у вас")
        return
    }
    if (real != null && !BoxCan.supported(real)) Hint(Lucide.TriangleAlert, "На плате прошивка ${real.fw}: CAN появился в прошивке 6. " +
        "Обновите её на шаге «Прошивка», датчики и выходы останутся как были", C.Orange)

    SwitchRow("Читать CAN-шину", "Коробка вместо CANable: в «Машина и адаптер» выберите CANable (Link), и если CANable на USB нет, " +
        "приложение берёт кадры с коробки. С другим адаптером кадры видны в «Что идёт по CAN» и в своих значениях", c.on) { on ->
        set(if (on) autoPins(c.copy(on = true), board) else c.copy(on = false))
    }

    // the mode: the car's bus is listen only, always
    Spacer(Modifier.height(10.dp))
    T("Какая шина", 15.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Chip("Шина машины: только слушать", c.listenOnly, { set(c.copy(listenOnly = true)) })
        Chip("Своя шина датчиков", !c.listenOnly, { set(c.copy(listenOnly = false)) })
    }
    if (c.listenOnly) SettingHint("Коробка не подтверждает кадры и не шлёт ошибок, а вывод S держит передатчик трансивера выключенным: " +
        "на шину машины она не влияет никак")
    else Hint(Lucide.ShieldAlert, "Обычный режим: коробка подтверждает кадры (ACK), иначе датчик на своей шине уйдёт в ошибку. " +
        "Только для своей шины (CAN-датчики, ЭБУ на стенде), не для шины машины: там это может вызвать ошибки ABS, SRS, иммобилайзера", C.Orange)

    Spacer(Modifier.height(10.dp))
    T("Скорость", 15.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        BoxCan.SPEEDS.forEach { k -> Chip(BoxCan.speedTitle(k), c.kbit == k, { set(c.copy(kbit = k)) }) }
    }
    SettingHint("Моторная шина и OBD-II обычно 500 кбит, комфортная VW 100 (до 2000 года 62,5), Mercedes CAN-B 83,3, Haltech и часть " +
        "ЭБУ 1 Мбит. Скорость неверная: кадров нет, растут ошибки приёма")

    Spacer(Modifier.height(10.dp))
    T("Трансивер", 15.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Chip("TJA1051T/3", c.chip != "sn65", { set(c.copy(chip = "tja1051")) })
        Chip("SN65HVD230", c.chip == "sn65", { set(c.copy(chip = "sn65", s = "")) })
    }
    SettingHint(if (c.chip != "sn65") "Лучший выбор: вывод S выключает передатчик аппаратно, а VIO даёт логику 3,3 В для ESP32-S3. " +
        "Берите именно T/3 (с VIO): у TJA1050 и TJA1051T без /3 логика 5 В"
    else "Модуль на 3,3 В. Вывода S у него нет (Rs на массе), тихий режим только программный. Для шины машины лучше TJA1051T/3")

    // pins: three of the free output pins
    Spacer(Modifier.height(10.dp))
    T("Пины платы", 15.sp, weight = FontWeight.SemiBold)
    val owners = SensorBox.pinOwners(board, "can")
    val picks = listOfNotNull(
        Triple("TX", c.tx) { p: String -> c.copy(tx = p) },
        Triple("RX", c.rx) { p: String -> c.copy(rx = p) },
        if (c.chip != "sn65") Triple("S", c.s) { p: String -> c.copy(s = p) } else null,
    )
    picks.forEach { (what, cur, put) ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            T(what, 14.sp, C.Sub, modifier = Modifier.width(32.dp).align(Alignment.CenterVertically))
            board.outPins.forEach { p ->
                val who = owners[p] ?: picks.firstOrNull { it.first != what && it.second == p }?.let { "CAN ${it.first}" }
                Chip(p + (who?.let { " · $it" } ?: ""), cur == p, { if (who == null) set(put(p)) }, modifier = Modifier.alpha(if (who != null && cur != p) 0.4f else 1f))
            }
            if (what == "S") Chip("не подключён", cur.isEmpty(), { set(put("")) })
        }
    }
    if (c.chip != "sn65" && c.s.isEmpty()) T("Без S тихий режим только программный: трансивер по умолчанию в обычном режиме", 12.sp, C.Orange)

    FilterBlock(c) { set(it) }

    // live
    Spacer(Modifier.height(12.dp))
    T("Сейчас", 15.sp, weight = FontWeight.SemiBold)
    val s = st
    when {
        !c.on -> T("CAN выключен", 13.sp, C.Sub)
        real == null -> T("Плата не на связи", 13.sp, C.Sub)
        !BoxCan.supported(real) -> T("Прошивка платы без CAN", 13.sp, C.Orange)
        s == null -> T("Плата открывает шину…", 13.sp, C.Sub)
        else -> {
            T("Шина ${s.title}: ${s.perSecond.toInt()} кадров в секунду, всего ${s.frames}", 13.sp, if (s.state == 'b') C.Orange else C.Green)
            if (s.rxErrors > 0) T("Ошибки приёма: ${s.rxErrors}. Растут: не та скорость, перепутаны CAN-H и CAN-L, нет терминатора на своей шине", 12.sp, C.Orange)
            if (s.missed > 0 || s.dropped > 0) T("Потеряно кадров: в контроллере ${s.missed}, на USB ${s.dropped}. Шина загружена сильнее, " +
                "чем успевает магнитола: поставьте фильтр по ID", 12.sp, C.Orange)
        }
    }
    Spacer(Modifier.height(6.dp))
    Chip("Что идёт по CAN", false, { monitor = true }, Lucide.Activity)
    if (monitor) CanMonitorDialog { monitor = false }

    // wiring
    Spacer(Modifier.height(14.dp))
    T("Как подключить", 15.sp, weight = FontWeight.SemiBold)
    val preset = canPreset(c)
    val sensors = SensorBox.slots.collectAsState().value.filter { it.input in board.inputs }
    WiringPicture(board, canInput(c), preset, sensors, Modifier.fillMaxWidth().height(300.dp))
    Spacer(Modifier.height(6.dp))
    CanWiringSteps(board, c, preset)
}

/** Up to two acceptance filters (11-bit) or one (29-bit), as hex id and mask; empty: every frame. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBlock(c: BoxCanCfg, set: (BoxCanCfg) -> Unit) {
    Spacer(Modifier.height(10.dp))
    T("Фильтр по ID", 15.sp, weight = FontWeight.SemiBold)
    SettingHint("Пусто: все кадры. Фильтр работает в самой плате и разгружает USB, когда шина забита, а нужны пара ID. " +
        "Маска: единичные биты должны совпасть (7FF: ровно этот ID, 7F0: шестнадцать подряд)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Chip("11 бит", !c.extFilter, { set(c.copy(extFilter = false)) })
        Chip("29 бит", c.extFilter, { set(c.copy(extFilter = true, filters = c.filters.take(1))) })
    }
    val rows = if (c.extFilter) 1 else 2
    for (i in 0 until rows) {
        val f = c.filters.getOrNull(i)
        var id by remember(c.extFilter, i) { mutableStateOf(f?.let { "%X".format(it.id) } ?: "") }
        var mask by remember(c.extFilter, i) { mutableStateOf(f?.let { "%X".format(it.mask) } ?: "") }
        fun save() {
            val fid = BoxCan.hexId(id, c.extFilter)
            val fm = if (mask.isBlank()) (if (c.extFilter) 0x1FFFFFFF else 0x7FF) else BoxCan.hexId(mask, c.extFilter)
            val l = c.filters.toMutableList()
            when {
                id.isBlank() -> if (i < l.size) l.removeAt(i)
                fid != null && fm != null -> if (i < l.size) l[i] = BoxCanFilter(fid, fm) else l += BoxCanFilter(fid, fm)
                else -> return
            }
            set(c.copy(filters = l))
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FormField("ID ${i + 1}, hex", id, Modifier.width(150.dp), caps = KeyboardCapitalization.Characters) { t ->
                id = t.filter { it.isLetterOrDigit() }.take(8).uppercase(); save()
            }
            Spacer(Modifier.width(8.dp))
            FormField("Маска, hex", mask, Modifier.width(150.dp), caps = KeyboardCapitalization.Characters) { t ->
                mask = t.filter { it.isLetterOrDigit() }.take(8).uppercase(); save()
            }
        }
    }
}

/** The wires one by one (numbered as in the picture), then the terminator, the tap into the car and the protection. */
@Composable
private fun CanWiringSteps(board: BoxBoard, c: BoxCanCfg, p: BoxPreset) {
    var n = 0
    val tja = c.chip != "sn65"
    p.wires.forEach { w ->
        val t = when (w.to) {
            "CANTX" -> "${w.label} → пин ${c.tx} платы (CAN TX)"
            "CANRX" -> "${w.label} → пин ${c.rx} платы (CAN RX)"
            "CANS" -> "${w.label} → пин ${c.s} платы, и резистор 10 кОм от S к 3V3: пока плата загружается, трансивер молчит " +
                "(без резистора у TJA1051 S внутри подтянут к массе, это обычный режим)"
            "5V" -> "${w.label} → пин ${board.v5} платы: трансивер питается от 5 В"
            "3V3" -> if (tja) "${w.label} → пин ${board.v33 ?: "3V3"} платы: уровни TXD и RXD 3,3 В, как у ESP32-S3"
                else "${w.label} → пин ${board.v33 ?: "3V3"} платы"
            "GND" -> "${w.label} → GND платы. Масса коробки и шины должна быть общей (у магнитолы она общая с кузовом)"
            "CANH" -> "${w.label} → провод CAN-H шины (на разъёме OBD-II это пин 6)"
            "CANL" -> "${w.label} → провод CAN-L шины (на OBD-II пин 14). CAN-H и CAN-L это витая пара, не раскручивайте её дальше, чем нужно"
            else -> "${w.label} → ${w.to}"
        }
        WireStep(++n, Color(w.color), t)
    }
    if (c.listenOnly) WireStep(++n, C.Orange, "Перемычку (или джампер) 120 Ом на модуле снимите: у шины машины уже есть свои два терминатора. " +
        "Мультиметр между CAN-H и CAN-L при выключенном зажигании покажет около 60 Ом")
    else WireStep(++n, C.Orange, "Своя шина: по терминатору 120 Ом на двух её концах. Коробка на конце шины: перемычку 120 Ом на модуле " +
        "оставьте. Проверка мультиметром без питания: 60 Ом хорошо, 120 не хватает одного, около 40 один лишний")
    WireStep(++n, C.Violet, "Плату USB-кабелем в магнитолу, как обычно. Коробка питается от USB, +12 В машины ей не нужно")
    Spacer(Modifier.height(6.dp))
    Hint(Lucide.Lightbulb, "Где подключиться к машине. OBD-II пины 6 и 14: проще всего, но на многих машинах там только диагностика за " +
        "шлюзом (gateway), и по шине почти ничего не идёт. Больше данных за разъёмом магнитолы (комфортная или мультимедийная шина), " +
        "у блока кузова (BCM) или климата. Врезка пайкой с термоусадкой или T-разветвитель OBD, не скрутка")
    Hint(Lucide.ShieldAlert, "Защита: TVS-сборка для CAN (PESD1CAN, NUP2105L) между CAN-H, CAN-L и массой у самого разъёма. Если своя шина " +
        "питается от машины: предохранитель 1 А и автомобильный DC-DC, не напрямую от 12 В. Шина машины только на прослушивание: " +
        "передача в неё может вызвать ошибки ABS, SRS, иммобилайзера", C.Orange)
    Hint(Lucide.Info, "Не подойдут без другого трансивера: K-CAN BMW (отказоустойчивая шина 100 кбит, нужен TJA1055), однопроводная GMLAN " +
        "33,3 кбит (TH8056). CAN FD плата не читает: такие кадры для неё ошибки")
}

/** [c] with pins for the transceiver nobody else holds: its own if they are free, else 39/40/41, else the first free ones. */
internal fun autoPins(c: BoxCanCfg, board: BoxBoard): BoxCanCfg {
    if (board != BoxBoard.ESP32S3) return c
    val free = SensorBox.freeOutPins(board, "can")
    return BoxCan.pickPins(c, free)
}
