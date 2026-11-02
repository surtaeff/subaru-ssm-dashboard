package kg.forester.sg.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.composables.icons.lucide.Wind
import com.composables.icons.lucide.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Disc3
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.*
import com.composables.icons.lucide.Thermometer
import com.composables.icons.lucide.Wrench
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Alerts
import kg.forester.sg.service.Maintenance
import kg.forester.sg.service.ServiceItem
import kg.forester.sg.service.Tpms
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * «Мониторинг»: engine guard (IAM, knock, turbo, oil, thermostat), the ECU's learned values, the knock map and
 * boost trends, maintenance planner, tyres, warnings log.
 */
@Composable
fun HealthScreen() {
    var tab by remember { mutableStateOf(0) }
    var engine3d by remember { mutableStateOf(Repo.settings.engine3d) }
    // another make: no Subaru engine in 3D, no ECU learning, idle references or knock map (all of them SSM)
    val other by kg.forester.sg.data.CarKind.other.collectAsState()
    if (other && tab in setOf(4, 5, 6)) tab = 0
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Мотор", tab == 0, { tab = 0 }, Lucide.HeartPulse, Modifier.keepInView(tab == 0))
            if (!other) {
                Chip("Обучение", tab == 4, { tab = 4 }, Lucide.Wind, Modifier.keepInView(tab == 4))
                Chip("Проверка", tab == 5, { tab = 5 }, Lucide.Stethoscope, Modifier.keepInView(tab == 5))
                Chip("Откаты и наддув", tab == 6, { tab = 6 }, Lucide.Grid3x3, Modifier.keepInView(tab == 6))
            }
            Chip("ТО", tab == 1, { tab = 1 }, Lucide.Wrench, Modifier.keepInView(tab == 1))
            Chip("Шины", tab == 2, { tab = 2 }, Lucide.Disc3, Modifier.keepInView(tab == 2))
            Chip("Предупреждения", tab == 3, { tab = 3 }, Lucide.Bell, Modifier.keepInView(tab == 3))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> {
                    // the engine as a 3D model or as a table of panels, whichever one likes
                    val switch: @Composable () -> Unit = { EngineViewSwitch(engine3d) { engine3d = it; Repo.settings.engine3d = it } }
                    if (other) EngineTable {} else if (engine3d) Engine3DTab(switch) else EngineTable(switch)
                }
                1 -> ServiceTab()
                2 -> TyresTab()
                4 -> LearningTab()
                5 -> CheckTab()
                6 -> KnockBoostTab()
                else -> AlertsTab()
            }
        }
    }
}

/**
 * «Проверка»: half a minute of warm idle against the factory Select Monitor table for this engine, and what the
 * analyzer watches while driving.
 */
@Composable
private fun CheckTab() {
    val ctx = LocalContext.current
    val init by Repo.ecuInit.collectAsState()
    val demo by Repo.demoOn.collectAsState()
    val turbo = Repo.guard.collectAsState().value.turboCar
    val engine = remember(init, demo, turbo) { kg.forester.sg.ssm.EngineId.detect(ctx) }
    val table = remember(engine) { kg.forester.sg.service.IdleRef.forEngine(engine) }
    val latest by kg.forester.sg.service.RulesHost.idle.collectAsState()
    val history by kg.forester.sg.service.RulesHost.idleHistory.collectAsState()
    // a sample picked in the history below, else the latest one
    var picked by remember { mutableStateOf<kg.forester.sg.service.IdleSample?>(null) }
    val sample = picked ?: latest
    // a second sample to compare with: its values in a column of their own, with the difference
    var compare by remember { mutableStateOf<kg.forester.sg.service.IdleSample?>(null) }
    val cmp = compare?.takeIf { it.timeMs != sample?.timeMs }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Stethoscope, "Холостой ход против заводских значений")
            Spacer(Modifier.height(6.dp))
            T("${engine.title} · ${engineSource(engine)}", 12.sp, C.Sub)
            T(table?.source ?: when (engine.source) {
                "нет" -> "Таблица выберется по мотору, который определит ЭБУ"
                "Link" -> "С Link заводские нормы не применяются: у настроенного мотора свои холостые. Замеры копятся для сравнения между собой"
                else -> "Заводской таблицы для этого мотора нет"
            }, 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            NoteBox(sample?.let { (if (picked != null) "Замер из истории, ${fmt.format(Date(it.timeMs))}: " else "Замер ${fmt.format(Date(it.timeMs))}: ") +
                "среднее за полминуты прогретого холостого хода. Обновляется сам, когда мотор прогрет до 80° и стоит на холостых." }
                ?: "Прогрейте мотор до 80°, выключите кондиционер и обогревы и оставьте машину на холостых в N или P на минуту. Приложение само снимет средние значения и сравнит с заводскими.")
            if (table != null) {
                val lines = kg.forester.sg.service.IdleRef.judge(table, sample?.values.orEmpty())
                // what the colours mean, above the table so it's seen without scrolling
                Spacer(Modifier.height(8.dp))
                ColorLegend(listOf(
                    C.Green to "норма",
                    C.Yellow to "чуть за границей (у исправных бывает)",
                    C.Red to "вне нормы",
                    C.Sub to "нет данных",
                ))
                Spacer(Modifier.height(4.dp))
                val cmpLines = cmp?.let { c -> kg.forester.sg.service.IdleRef.judge(table, c.values).associateBy { it.row.key } }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Параметр", 12.sp, C.Sub, modifier = Modifier.weight(1.3f))
                    T(if (cmp != null && sample != null) fmt.format(Date(sample.timeMs)) else "У вас", 12.sp, C.Sub, modifier = Modifier.weight(0.8f), maxLines = 1)
                    if (cmp != null) Row(Modifier.weight(0.9f), verticalAlignment = Alignment.CenterVertically) {
                        T(fmt.format(Date(cmp.timeMs)), 12.sp, C.Violet, modifier = Modifier.weight(1f), maxLines = 1)
                        IconTap(Lucide.X, "Убрать сравнение", { compare = null }, iconSize = 18.dp)
                    }
                    T("Завод", 12.sp, C.Sub, modifier = Modifier.weight(if (cmp != null) 1.0f else 1.4f))
                }
                lines.forEachIndexed { i, l ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
                    val col = when (l.verdict) {
                        kg.forester.sg.service.IdleRef.Verdict.OK -> C.Green
                        kg.forester.sg.service.IdleRef.Verdict.NEAR -> C.Yellow
                        kg.forester.sg.service.IdleRef.Verdict.NONE -> C.Sub
                        else -> C.Red
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                            Spacer(Modifier.width(8.dp))
                            T(l.row.title, 14.sp, maxLines = 1)
                        }
                        T(l.value?.let { "%.${l.row.decimals}f".format(it) + (if (l.row.unit.startsWith("°")) "" else " ") + l.row.unit } ?: "—", 15.sp, if (l.verdict == kg.forester.sg.service.IdleRef.Verdict.NONE) C.Sub else col,
                            FontWeight.SemiBold, Modifier.weight(0.8f), maxLines = 1)
                        if (cmpLines != null) {
                            // the other sample, and how much this one differs from it
                            val o = cmpLines[l.row.key]
                            val ov = o?.value
                            val d = l.row.decimals
                            Column(Modifier.weight(0.9f)) {
                                T(ov?.let { "%.${d}f".format(it) } ?: "—", 14.sp, when (o?.verdict) {
                                    kg.forester.sg.service.IdleRef.Verdict.OK -> C.Green
                                    kg.forester.sg.service.IdleRef.Verdict.NEAR -> C.Yellow
                                    kg.forester.sg.service.IdleRef.Verdict.NONE, null -> C.Sub
                                    else -> C.Red
                                }, FontWeight.Medium, maxLines = 1)
                                val v = l.value
                                if (v != null && ov != null && Math.abs(v - ov) * Math.pow(10.0, d.toDouble()) >= 0.5) {
                                    T("разница %+.${d}f".format(v - ov), 11.sp, C.Sub, maxLines = 1)
                                }
                            }
                        }
                        T(l.row.norm, 12.sp, C.Sub, modifier = Modifier.weight(if (cmpLines != null) 1.0f else 1.4f), maxLines = 2)
                    }
                }
                val hints = lines.mapNotNull { kg.forester.sg.service.IdleRef.hint(it) }.distinct()
                if (hints.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionLine(Lucide.Lightbulb, "Что это может значить")
                    hints.forEach { T("• $it", 13.sp, C.Text.copy(alpha = 0.9f)) }
                }
                if (history.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    SectionLine(Lucide.History, "История замеров")
                    T("Нажмите на замер, чтобы открыть его в таблице, или на ⇄, чтобы сравнить с ним. В скобках сдвиг от первого замера: видно, куда идёт, например грязнеет датчик расхода воздуха или растёт подсос.", 12.sp, C.Sub)
                    Spacer(Modifier.height(6.dp))
                    val first = history.first()
                    fun cell(s: kg.forester.sg.service.IdleSample, k: String, d: Int): String {
                        val v = s.values[k] ?: return "—"
                        val base = first.values[k]
                        // only a real shift, not "+0.00" or "-0.0" from rounding
                        val dv = if (s !== first && base != null) v - base else 0f
                        val delta = if (Math.abs(dv) * Math.pow(10.0, d.toDouble()) >= 0.5) " (%+.${d}f)".format(dv) else ""
                        return "%.${d}f".format(v) + delta
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        listOf("Дата" to 1.1f, "Обороты" to 1f, "MAF, г/с" to 1f, "Впрыск, мс" to 1f, "Обучение, %" to 1f).forEach { (h, w) ->
                            T(h, 11.sp, C.Sub, modifier = Modifier.weight(w))
                        }
                        Spacer(Modifier.width(36.dp))
                    }
                    history.reversed().forEach { s ->
                        val on = sample?.timeMs == s.timeMs
                        val vs = cmp?.timeMs == s.timeMs
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) C.Violet.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { picked = if (s.timeMs == latest?.timeMs) null else s }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(fmt.format(Date(s.timeMs)), 12.sp, modifier = Modifier.weight(1.1f), maxLines = 1)
                            T(cell(s, "rpm", 0), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(cell(s, "maf", 1), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(cell(s, "inj", 2), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(cell(s, "aflearn", 1), 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            // compare with this one (not with itself)
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (vs) C.Violet.copy(alpha = 0.3f) else Color.Transparent)
                                    .clickable(enabled = !on) { compare = if (vs) null else s },
                                contentAlignment = Alignment.Center,
                            ) { if (!on) Icon(Lucide.ArrowLeftRight, "Сравнить", tint = if (vs) C.Text else C.Violet, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.ListChecks, "Что проверяется на ходу")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { WatchedList() }
        }
    }
}

/**
 * «Откаты и наддув»: the knock map (rpm × load, this trip or all time, pulls at an automatic's shift apart), the
 * knock sums per cylinder and the beeper; the boost against its target, the last pull and the spool point by gear.
 */
@Composable
private fun KnockBoostTab() {
    val d by kg.forester.sg.service.RulesHost.diag.collectAsState()
    val g by Repo.guard.collectAsState()
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val map = if (all) d.allKnock else d.tripKnock
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Grid3x3, "Карта откатов") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Поездка", !all, { all = false; picked = null })
                    Chip("Всё время", all, { all = true; picked = null })
                }
            }
            Spacer(Modifier.height(8.dp))
            T(when {
                map.total == 0 && map.shiftTotal == 0 -> "Откатов нет" + if (all) "" else " за эту поездку"
                else -> "${map.total} откат(ов)" + (map.worstDeg?.let { ", самый глубокий %.1f°".format(it) } ?: "") +
                    if (map.shiftTotal > 0) " · ещё ${map.shiftTotal} при переключении АКПП" else ""
            }, 13.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            KnockGrid(map, g.turboCar, picked) { picked = if (picked == it) null else it }
            Spacer(Modifier.height(6.dp))
            val cell = picked?.let { map.cells[it] }
            if (picked != null) {
                val i = picked!!
                val col = i % kg.forester.sg.data.KnockMap.COLS
                val row = i / kg.forester.sg.data.KnockMap.COLS
                val r = kg.forester.sg.data.KnockMap.RPM
                val l = kg.forester.sg.data.KnockMap.LOAD
                val rpm = if (col == r.lastIndex) "от ${r[col]} об/мин" else "${r[col]}–${r[col + 1]} об/мин"
                val load = if (row == l.lastIndex) "от %.1f г/об".format(l[row]) else "%.1f–%.1f г/об".format(l[row], l[row + 1])
                NoteBox("$rpm, $load: " + if (cell == null) "откатов не было" else
                    "${cell.count} откат(ов)" + (if (cell.count > 0) ", до %.1f°".format(cell.worstDeg) else "") +
                        if (cell.shift > 0) ", при переключении АКПП ${cell.shift}" else "")
            } else {
                ColorLegend(listOf(C.Orange to "мало", C.Red to "часто", C.Violet to "при переключении АКПП (вероятно ложные)"))
                Spacer(Modifier.height(4.dp))
                T("Считаются откаты от 0,3°. На сбросе газа, на малой нагрузке ниже 3000 об/мин и на холодном моторе не считаются: это шум датчика. Нажмите на клетку, чтобы увидеть подробности.", 12.sp, C.Sub)
            }
            d.cylKnock?.let { cyl ->
                Spacer(Modifier.height(12.dp))
                SectionLine(Lucide.AudioWaveform, "Детонация по цилиндрам за поездку")
                val top = cyl.max()
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cyl.forEachIndexed { i, n ->
                        StatTile(Lucide.Flame, "Цилиндр ${i + 1}", "$n", "", Modifier.weight(1f),
                            if (top > 0 && n == top && n >= 0.6f * cyl.sum()) C.Red else C.Text, small = true)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            KnockOptions(all, ctx)
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (g.turboCar) {
                PanelTitle(Lucide.Fan, "Наддув")
                Spacer(Modifier.height(10.dp))
                BoostCards(d, g)
                Spacer(Modifier.height(12.dp))
                SpoolCard(d.spool)
            } else {
                PanelTitle(Lucide.Cog, "Передачи и впуск")
                Spacer(Modifier.height(8.dp))
                NoteBox("Атмосферный мотор: наддува нет. Здесь передачи, которые приложение выучило по оборотам и скорости: по ним оно замечает пробуксовку сцепления или коробки на полном газу.")
            }
            Spacer(Modifier.height(12.dp))
            SectionLine(Lucide.Cog, kg.forester.sg.data.DistanceUnits.label("Передачи (обороты на 1 км/ч)"))
            if (d.gears.isEmpty()) T("Выучатся сами за несколько поездок.", 13.sp, C.Sub)
            else T(d.gears.mapIndexed { i, r -> "${i + 1}-я  %.1f".format(kg.forester.sg.data.DistanceUnits.perSpeed(r)) }.joinToString("   "), 14.sp)
            d.ambientC?.let {
                Spacer(Modifier.height(8.dp))
                T("Улица сегодня около %.0f° (по впуску после стоянки): от неё считается нагрев впуска".format(it), 12.sp, C.Sub)
            }
        }
    }
}

/** The knock map's grid: high load on top, a count in each cell, the rpm under it. */
@Composable
private fun KnockGrid(map: kg.forester.sg.data.KnockMap, turbo: Boolean, picked: Int?, onPick: (Int) -> Unit) {
    val km = kg.forester.sg.data.KnockMap
    // an atmospheric engine never gets near 1.7 g/rev: its grid stops at the «1,4 and up» row
    val rows = if (turbo) km.ROWS else km.LOAD.indexOfFirst { it >= 1.4f } + 1
    val most = map.cells.values.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    for (row in rows - 1 downTo 0) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (!turbo && row == rows - 1) "1,4+" else "%.1f".format(km.LOAD[row]), 10.sp, C.Sub, modifier = Modifier.width(roomFor(30.dp, 10f)), maxLines = 1)
            for (col in 0 until km.COLS) {
                // the top row of an atmospheric engine gathers every row above it
                val cells = if (!turbo && row == rows - 1) (row until km.ROWS).mapNotNull { map.cell(it, col) } else listOfNotNull(map.cell(row, col))
                val n = cells.sumOf { it.count }
                val shift = cells.sumOf { it.shift }
                val i = row * km.COLS + col
                val bg = when {
                    n > 0 -> androidx.compose.ui.graphics.lerp(C.Orange, C.Red, (n.toFloat() / most).coerceIn(0f, 1f)).copy(alpha = 0.35f + 0.55f * (n.toFloat() / most))
                    shift > 0 -> C.Violet.copy(alpha = 0.35f)
                    else -> C.Border.copy(alpha = 0.35f)
                }
                Box(
                    Modifier.weight(1f).height(22.dp).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(bg)
                        .then(if (picked == i) Modifier.border(1.5.dp, C.Text, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (n > 0) T("$n", 10.sp, C.Text, FontWeight.SemiBold, maxLines = 1)
                    else if (shift > 0) T("$shift", 10.sp, C.Text.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        T("г/об", 9.sp, C.Sub, modifier = Modifier.width(roomFor(30.dp, 10f)), maxLines = 1)
        for (col in 0 until km.COLS) {
            T(if (col % 2 == 0) "${km.RPM[col] / 1000.0}".removeSuffix(".0") + "к" else "", 9.sp, C.Sub, modifier = Modifier.weight(1f), maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** The beeper, reading the per-cylinder counters, starting the all-time map over. */
@Composable
private fun KnockOptions(all: Boolean, ctx: android.content.Context) {
    val s = Repo.settings
    var deg by remember { mutableStateOf(s.knockBeepDeg) }
    var rpm by remember { mutableStateOf(s.knockBeepRpm) }
    var sums by remember { mutableStateOf(s.knockSums) }
    SectionLine(Lucide.BellRing, "Пищалка на откат")
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0f to "Выкл", -1f to "от −1°", -2f to "от −2°", -3f to "от −3°").forEach { (v, t) ->
            Chip(t, deg == v, { deg = v; s.knockBeepDeg = v })
        }
        if (deg < 0f) listOf(0 to "любые обороты", 2500 to "от 2500", 3500 to "от 3500").forEach { (v, t) ->
            Chip(t, rpm == v, { rpm = v; s.knockBeepRpm = v })
        }
    }
    T("Короткий сигнал на откат угла. Порог по оборотам, чтобы −1° внизу не пищал постоянно.", 12.sp, C.Sub)
    // the per-cylinder counters only where this ROM's definition has them
    val has = Repo.rrAvailable.collectAsState().value.any { it.id == "E165" }
    if (has) {
        Spacer(Modifier.height(8.dp))
        SwitchRow("Детонация по цилиндрам", "Читать счётчики Knock Sum 1–4 из ЭБУ: видно, какой цилиндр звенит. Опрос станет чуть медленнее", sums) {
            sums = it; s.knockSums = it
        }
    }
    if (all) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { kg.forester.sg.service.RulesHost.clearKnockMap(ctx) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.RotateCcw, null, tint = C.Violet, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            T("Начать карту заново (после ремонта, смены бензина или прошивки)", 13.sp, C.Violet)
        }
    }
}

/** Boost now against the target, and the last full-throttle pull. */
@Composable
private fun BoostCards(d: kg.forester.sg.service.DiagState, g: kg.forester.sg.service.GuardState) {
    val e by Repo.live.collectAsState()
    val gauge = e.gaugeKpa
    val target = g.targetBoostKpa
    val err = if (target != null && gauge != null && gauge > 0f) target - gauge else null
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Gauge, "Наддув", gauge?.let { "%.2f".format(it / 100f) } ?: "—", "бар сейчас", Modifier.weight(1f))
        StatTile(Lucide.Target, "Цель ЭБУ", target?.let { "%.2f".format(it / 100f) } ?: "—", if (target == null) "нет в прошивке" else "бар", Modifier.weight(1f))
        StatTile(Lucide.Crosshair, "Ошибка", err?.let { "%+.2f".format(it / 100f) } ?: "—", "цель − факт", Modifier.weight(1f),
            if (err != null && (err >= 15f || err <= -20f)) C.Orange else C.Text)
    }
    val p = d.lastPull ?: run {
        Spacer(Modifier.height(8.dp))
        T("Последний разгон в пол появится здесь.", 13.sp, C.Sub)
        return
    }
    Spacer(Modifier.height(10.dp))
    SectionLine(Lucide.History, "Последний разгон в пол, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.timeMs)) +
        (p.gear?.let { ", $it-я передача" } ?: ""))
    Line("Пик наддува", "%.2f бар".format(p.peakKpa / 100f) + (p.targetKpa?.let { " при цели %.2f".format(it / 100f) } ?: ""))
    p.maxErrKpa?.let { Line("Самый большой недобор", if (it > 0f) "%.2f бар".format(it / 100f) else "нет, наддув держал цель") }
    p.wgdcMax?.let { Line("Соленоид наддува", "до %.0f %%".format(it)) }
    p.spoolRpm?.let { Line("Выход на наддув", "$it об/мин") }
}

/** Where the turbo comes on boost, per gear, against this car's usual point. */
@Composable
private fun SpoolCard(t: kg.forester.sg.service.SpoolTrend) {
    SectionLine(Lucide.TrendingUp, if (t.levelKpa > 0f) "Выход на %.1f бар по передачам".format(t.levelKpa / 100f) else "Выход на наддув по передачам")
    val gears = (t.base.keys + t.recent.map { it.gear }).toSortedSet()
    if (gears.isEmpty()) {
        T("Разгонитесь в пол со 2-й по 5-ю передачу с низких оборотов: приложение запомнит, на каких оборотах турбина выходит на наддув, и предупредит, если точка уедет вверх на 300 об/мин и больше (износ втулки вестгейта, утечки).", 12.sp, C.Sub)
        return
    }
    gears.forEach { gear ->
        val base = t.base[gear]
        val last = t.inGear(gear).takeLast(3)
        val late = base != null && last.lastOrNull()?.let { it.rpm - base >= kg.forester.sg.service.SpoolTrend.LATE_RPM } == true
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("$gear-я", 14.sp, weight = FontWeight.Medium, modifier = Modifier.width(roomFor(44.dp, 14f)))
            T(base?.let { "обычно $it" } ?: "учится", 13.sp, C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
            T(last.joinToString("  ") { "${it.rpm}" }, 14.sp, if (late) C.Orange else C.Text, FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The colour legend under the idle table: a dot and a few words each, wrapping on narrow screens. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorLegend(items: List<Pair<Color, String>>) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (c, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(5.dp))
                T(text, 11.sp, C.Sub, maxLines = 1)
            }
        }
    }
}

/** 3D or table: two segments in one pill. */
@Composable
private fun EngineViewSwitch(engine3d: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card.copy(alpha = 0.92f)).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(3.dp)) {
        listOf(Triple(true, "3D", Lucide.Box), Triple(false, "Таблица", Lucide.Table)).forEach { (v, text, icon) ->
            val on = engine3d == v
            Row(
                Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) C.selectFill(C.Purple.copy(alpha = 0.28f)) else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (on) C.selectText(C.Text) else C.Sub, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                T(text, 13.sp, if (on) C.selectText(C.Text) else C.Sub, FontWeight.Medium)
            }
        }
    }
}

/** «Мотор» as it was before the 3D engine: knock, oil/turbo/cooling, what is watched, side by side. */
@Composable
private fun EngineTable(switch: @Composable () -> Unit) {
    val g by Repo.guard.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Flame, "IAM и детонация")
            Spacer(Modifier.height(12.dp))
            KnockDetails()
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Thermometer, "Масло, турбина, охлаждение")
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                OilDetails()
                if (g.turbo) {
                    Spacer(Modifier.height(8.dp))
                    TurboDetails()
                }
                Spacer(Modifier.height(14.dp))
                SectionLine(Lucide.Thermometer, "Охлаждение и коробка")
                CoolingDetails(wide = false)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { switch() }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Bell, "Что отслеживается")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { WatchedList() }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(8.dp))
            VoiceSwitch()
        }
    }
}

/** IAM colour: full, lowered, low. */
internal fun iamColor(v: Double) = if (v >= 0.95) C.Green else if (v >= 0.75) C.Orange else C.Red

/** The knock sensor's card on the engine: IAM now, the corrections, IAM at the start of each drive. */
@Composable
internal fun KnockDetails() {
    val g by Repo.guard.collectAsState()
    val fmt = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }
    val log = remember(g.iam) {
        runCatching { JSONArray(Repo.settings.iamLog) }.getOrNull()?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty().reversed()
    }
    val refills = rememberQuery(Unit) { runCatching { Repo.db.refills() }.getOrDefault(emptyList()) }.value ?: emptyList()
    val iam = g.iam
    Row(verticalAlignment = Alignment.Bottom) {
        T(iam?.let { "%.2f".format(it) } ?: "—", 34.sp, iam?.let { iamColor(it.toDouble()) } ?: C.Sub, FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            T("IAM", 13.sp, C.Sub)
            T(when { iam == null -> "нет данных"; iam >= 0.95f -> "норма"; iam >= 0.75f -> "снижен"; else -> "низкий" }, 14.sp,
                iam?.let { iamColor(it.toDouble()) } ?: C.Sub, FontWeight.Medium)
        }
    }
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
        if (iam != null) Box(Modifier.fillMaxHeight().fillMaxWidth(iam.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(iamColor(iam.toDouble())))
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Activity, "FBKC (откат)", g.fbkc?.let { "%.1f°".format(it) } ?: "—", "сейчас", Modifier.weight(1f),
            if (Warn.of("fbkc").hits(g.fbkc)) C.Orange else C.Text, warn = Warn.of("fbkc").hits(g.fbkc))
        StatTile(Lucide.Waves, "FLKC (обучение)", g.flkc?.let { "%.1f°".format(it) } ?: "—", "выученная", Modifier.weight(1f),
            if (Warn.of("flkc").hits(g.flkc)) C.Orange else C.Text, warn = Warn.of("flkc").hits(g.flkc))
    }
    if (iam == null) {
        Spacer(Modifier.height(8.dp))
        NoteBox(if (kg.forester.sg.data.CanEcu.active) "Link не передаёт IAM и коррекции детонации: ими управляет сам ЭБУ. " +
            "Уровень детонации из его потока есть на «Датчиках» (Детонация, по цилиндрам в Generic Dash)."
            else "В определениях этой прошивки нет IAM — приложение следит за коррекцией детонации.")
    }
    Spacer(Modifier.height(12.dp))
    SectionLine(Lucide.History, "IAM по поездкам")
    T("На старте каждой поездки. Падение после заправки — признак плохого бензина на этой АЗС.", 12.sp, C.Sub)
    Spacer(Modifier.height(4.dp))
    if (log.isEmpty()) T("Пока нет данных.", 13.sp, C.Sub)
    log.take(8).forEachIndexed { i, o ->
        val t = o.getLong("t")
        val v = o.getDouble("v")
        val refill = refills.filter { it.timeMs < t }.maxByOrNull { it.timeMs }
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(iamColor(v)))
            Spacer(Modifier.width(8.dp))
            T(fmt.format(Date(t)), 13.sp, C.Sub, modifier = Modifier.width(roomFor(72.dp, 13f)), maxLines = 1)
            T("%.2f".format(v), 15.sp, iamColor(v), FontWeight.SemiBold, Modifier.width(roomFor(52.dp, 15f)), maxLines = 1)
            T(refill?.let { "после заправки ${fmt.format(Date(it.timeMs))}" } ?: "", 12.sp, C.Sub, maxLines = 1)
        }
    }
}

/** The oil's card: the temperature (read or estimated) and whether the engine may be loaded. */
@Composable
internal fun OilDetails() {
    val g by Repo.guard.collectAsState()
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Droplet, if (g.oilC != null && g.oilEstimated) "Масло ≈" else "Масло", g.oilC?.let { "${it.roundToInt()}°" } ?: "—",
            if (g.oilC == null) "" else if (g.oilEstimated) "оценка по ОЖ и нагрузке" else "датчик", Modifier.weight(1f))
        StatTile(Lucide.Gauge, "Прогрев", if (g.cold) "холодный" else if (g.oilC != null) "прогрет" else "—",
            if (g.cold) "не выше 3000 об/мин" else g.warmupMin?.let { "до 80° за %.0f мин".format(it) } ?: if (g.oilC != null) "можно нагружать" else "", Modifier.weight(1f),
            if (g.cold) C.Orange else if (g.oilC != null) C.Green else C.Text)
    }
}

/** The turbo's card: the turbo timer. */
@Composable
internal fun TurboDetails() {
    val g by Repo.guard.collectAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Fan, null, tint = C.Sub, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            T("Турботаймер", 13.sp, C.Sub, modifier = Modifier.weight(1f))
            T("жар ${g.heat.roundToInt()}", 12.sp, C.Sub)
        }
        Spacer(Modifier.height(6.dp))
        val hot = g.heat > 12f
        T(when {
            g.cooldownSec > 0 -> "подождите ${g.cooldownSec} с"
            hot -> "~${g.heat.toInt()} с на холостых"
            else -> "можно глушить"
        }, 20.sp, if (hot || g.cooldownSec > 0) C.Orange else C.Green, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((g.heat / 100f).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp))
                .background(if (g.heat > 60) C.Red else if (g.heat > 30) C.Orange else C.Green))
        }
    }
}

/** The coolant's card: the highest coolant and ATF this session, and the thermostat. */
@Composable
internal fun CoolingDetails(wide: Boolean = true) {
    val g by Repo.guard.collectAsState()
    // wide: three in a row; narrow: the thermostat under the two maximums
    @Composable fun thermostat(m: Modifier) = StatTile(Lucide.Repeat, "Термостат", g.thermostat.ifEmpty { "—" },
        if (g.thermostat.isEmpty()) "оценится после холодного пуска" else "", m, small = true)
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Lucide.Thermometer, "Макс. ОЖ", g.coolantMax?.let { "$it°" } ?: "—", "за сеанс", Modifier.weight(1f),
            if ((g.coolantMax ?: 0) >= 105) C.Red else C.Text)
        StatTile(Lucide.Cog, "Макс. ATF", g.atfMax?.let { "$it°" } ?: "—", "за сеанс", Modifier.weight(1f),
            if ((g.atfMax ?: 0) >= 110) C.Orange else C.Text)
        if (wide) thermostat(Modifier.weight(1f))
    }
    if (!wide) {
        Spacer(Modifier.height(8.dp))
        thermostat(Modifier.fillMaxWidth())
    }
}

/** What the guard watches, and the voice warnings switch. */
@Composable
internal fun WatchedDetails() {
    WatchedList()
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
    Spacer(Modifier.height(8.dp))
    VoiceSwitch()
}

@Composable
private fun WatchedList() {
    listOf(
        Triple(Lucide.Flame, "Детонация", "IAM ниже 0,75, −0,1 за поездку или после заправки, FLKC −1,4 / −2,8 / −4°, откат FBKC под наддувом и на разгоне, цилиндр по счётчикам, карта откатов. После сброса ЭБУ, на малой нагрузке, холодном моторе и переключениях АКПП не пугает"),
        Triple(Lucide.Droplets, "Смесь", "ЭБУ долго добавляет или убирает больше 15 % топлива, подсос только на холостых, коррекция качается от упора до упора, датчик расхода завышает, датчик состава смеси не работает или его нет, форсунки на пределе"),
        Triple(Lucide.Fan, "Наддув", "перенаддув и крип, отсечка топлива, езда на пружине, недобор цели при соленоиде на пределе, плавающий наддув, точка выхода на наддув уехала вверх, бедная смесь под наддувом, турботаймер"),
        Triple(Lucide.Gauge, "Холостой ход", "плавающие, высокие или низкие обороты, AVCS не возвращается, слабое разрежение"),
        Triple(Lucide.Thermometer, "Охлаждение", "горячо от 102°, перегрев от 105°, выше 100° на скорости, перегрев только в пробке (вентиляторы), резкий рост за минуту на ровном ходу при работающем вентиляторе (про прокладку только с третьей такой поездки), рост в пробке при включённом вентиляторе (реле первой скорости)"),
        Triple(Lucide.Snowflake, "Холодный мотор", "высокие обороты, полный газ или наддув на непрогретом масле"),
        Triple(Lucide.Repeat, "Термостат", "20 минут езды, а ОЖ ниже 72°"),
        Triple(Lucide.Cog, "АКПП", "ATF от 100°, 110° и 120°, странный датчик, проскальзывание блокировки и пробуксовка на полном газу (и сцепления на МКПП), аварийный режим на третьей"),
        Triple(Lucide.Disc3, "Полный привод", "передние и задние колёса крутятся с разной скоростью: разный износ или давление"),
        Triple(Lucide.BatteryCharging, "АКБ и генератор", "разряд на стоянке, слабый пуск, не заряжает ниже 12,6 В, перезаряд выше 15 В, магнитола не выключилась. 12,5–13 В на ходу у Subaru 2001–2008 норма"),
        Triple(Lucide.Cpu, "Доп датчики", "давление топлива к коллектору (около 3 бар) и его пульсации, масло на 3500 горячим, эффективность интеркулера, горячий впуск на полном газу"),
        Triple(Lucide.ThermometerSnowflake, "Датчики", "после долгой стоянки ОЖ и воздух показывают по-разному, нереальное атмосферное давление"),
        Triple(Lucide.Wrench, "ТО и шины", "сроки по пробегу, времени, моточасам; давление в шинах"),
    ).forEachIndexed { i, (icon, title, sub) ->
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(C.Violet.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = C.Violet, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(title, 14.sp, weight = FontWeight.Medium)
                T(sub, 12.sp, C.Sub)
            }
        }
    }
}

@Composable
private fun VoiceSwitch() {
    var voice by remember { mutableStateOf(Repo.settings.voiceAlerts) }
    val ctx = LocalContext.current
    val speech by kg.forester.sg.service.Speech.status.collectAsState()
    SwitchRow("Голосовые предупреждения", "Встроенный русский голос (" +
        (kg.forester.sg.service.Speech.current.mb.takeIf { it > 0 }?.let { "$it МБ, один раз" } ?: "голос магнитолы") + ")", voice,
        status = kg.forester.sg.service.Speech.shown(voice, speech), statusColor = speechColor()) {
        voice = it; Repo.settings.voiceAlerts = it
        if (it) kg.forester.sg.service.Speech.prepare(ctx)
    }
}

/** A value in a rounded tile: icon and label on top, the value large, a note under it. */
@Composable
private fun StatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, note: String,
    modifier: Modifier, color: androidx.compose.ui.graphics.Color = C.Text, small: Boolean = false,
    /** Over its warning line (see Warn): a thick frame and a triangle, not only the colour of the number. */
    warn: Boolean = false,
) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .then(if (warn) Modifier.border(3.dp, C.Orange, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.Sub, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            T(label, 12.sp, C.Sub, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (warn) { Spacer(Modifier.width(6.dp)); Icon(Lucide.TriangleAlert, "Выше нормы", tint = C.Orange, modifier = Modifier.size(16.dp)) }
        }
        Spacer(Modifier.height(4.dp))
        T(value, if (small) 15.sp else 22.sp, color, FontWeight.Bold, maxLines = 2)
        if (note.isNotEmpty()) T(note, 11.sp, C.Sub, maxLines = 2)
    }
}

@Composable
private fun SectionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        T(title, 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun NoteBox(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Violet.copy(alpha = 0.10f)).padding(10.dp)) {
        Icon(Lucide.Info, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        T(text, 12.sp, C.Text)
    }
}

@Composable
private fun Line(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        T(k, 13.sp, C.Sub, modifier = Modifier.width(roomFor(150.dp, 13f)))
        T(v, 13.sp, modifier = Modifier.weight(1f))
    }
}

/** A distance in the driver's unit, in the way this tab writes its numbers: «60 000 км». */
private fun spacedDistance(km: Double) = spaced(kg.forester.sg.data.DistanceUnits.distance(km).roundToInt()) + " " + kg.forester.sg.data.DistanceUnits.distanceUnit

@Composable
private fun ServiceTab() {
    Repo.maintenanceVersion.collectAsState().value
    val odo = Maintenance.odometer()
    val now = System.currentTimeMillis()
    val items = Maintenance.items()
    var odoText by remember(kg.forester.sg.data.DistanceUnits.miles) { mutableStateOf(odo?.let { kg.forester.sg.data.DistanceUnits.distanceField(it) } ?: "") }
    var editing by remember { mutableStateOf<ServiceItem?>(null) }
    val fmt = remember { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Gauge, "Пробег")
            Spacer(Modifier.height(8.dp))
            T(if (odo == null) "Введите пробег с приборки один раз — дальше приложение прибавляет пройденное по поездкам."
            else "Сейчас ≈ ${kg.forester.sg.data.DistanceUnits.distanceText(odo)} (введённый пробег + поездки)", 12.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            NumberField("Пробег по одометру, ${kg.forester.sg.data.DistanceUnits.distanceUnit}", odoText) { odoText = it }
            Spacer(Modifier.height(8.dp))
            val odoTyped = kg.forester.sg.data.DistanceUnits.distanceFromField(odoText, odo)
            ActionButton("Сохранить пробег", odoTyped != null) { odoTyped?.let { Maintenance.setOdometer(it) } }
            Spacer(Modifier.height(14.dp))
            var hoursText by remember { mutableStateOf("%.1f".format(java.util.Locale.US, Maintenance.hours())) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Clock, null, tint = C.Violet, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                T("Моточасы", 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("%.1f ч".format(Maintenance.hours()), 16.sp, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            T("Приложение считает их само, пока работает двигатель. Если знаете точнее (с прошлого ТО), поправьте.", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            NumberField("Моточасы", hoursText) { hoursText = it }
            Spacer(Modifier.height(6.dp))
            val hoursValue = hoursText.replace(',', '.').trim().toDoubleOrNull()
            ActionButton("Сохранить моточасы", hoursValue != null) { hoursValue?.let { Maintenance.setHours(it) } }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Spacer(Modifier.height(12.dp))
            T("Нажмите на пункт, чтобы указать, когда его делали, и поменять интервал. «Сделано» — сегодня на текущем пробеге.", 11.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            ActionButton("Добавить свой пункт", true) {
                editing = ServiceItem("c" + System.currentTimeMillis(), "", 10_000, 12, custom = true)
            }
        }
        Panel(Modifier.weight(2f).fillMaxHeight()) {
            PanelTitle(Lucide.Wrench, "Обслуживание")
            Spacer(Modifier.height(8.dp))
            // how many jobs are in each state, at a glance
            val states = items.filter { it.on }.map { serviceState(it, odo, now) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(ServiceState.OVERDUE, "просрочено", Lucide.CircleAlert),
                    Triple(ServiceState.DUE, "скоро", Lucide.Clock),
                    Triple(ServiceState.OK, "в норме", Lucide.CircleCheck),
                    Triple(ServiceState.UNKNOWN, "не отмечено", Lucide.CircleDot),
                ).forEach { (st, label, icon) ->
                    val n = states.count { it == st }
                    if (n > 0) StatusPill(icon, "$label: $n", st.color)
                }
            }
            Spacer(Modifier.height(8.dp))
            val groups = items.groupBy { serviceGroup(it) }.toList().sortedBy { SERVICE_GROUPS.indexOf(it.first) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (group, list) ->
                    item(key = "g$group") {
                        Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(group.uppercase(), 11.sp, C.Violet, weight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f).height(1.dp).background(C.Border))
                        }
                    }
                    items(list, key = { it.id }) { ServiceRow(it, odo, now, fmt) { editing = it } }
                }
            }
        }
    }
    editing?.let { item -> ServiceDialog(item) { editing = null } }
}

private enum class ServiceState(val color: androidx.compose.ui.graphics.Color) {
    OVERDUE(C.Red), DUE(C.Orange), OK(C.Green), UNKNOWN(C.Sub), OFF(C.Sub)
}

private fun serviceState(it: ServiceItem, odo: Double?, now: Long): ServiceState {
    if (!it.on) return ServiceState.OFF
    val used = it.used(odo, now) ?: return ServiceState.UNKNOWN
    return when {
        used >= 1f -> ServiceState.OVERDUE
        it.due(odo, now) -> ServiceState.DUE
        else -> ServiceState.OK
    }
}

private val SERVICE_GROUPS = listOf("Двигатель", "Трансмиссия", "Охлаждение и тормоза", "Салон", "Свои пункты")

private fun serviceGroup(it: ServiceItem) = when (it.id) {
    "oil", "belt", "plugs", "air", "belts" -> "Двигатель"
    "gear", "diff" -> "Трансмиссия"
    "coolant", "brake", "pads" -> "Охлаждение и тормоза"
    "cabin" -> "Салон"
    else -> "Свои пункты"
}

private fun serviceIcon(it: ServiceItem) = when (it.id) {
    "oil" -> Lucide.Droplet
    "belt" -> Lucide.Timer
    "plugs" -> Lucide.Zap
    "air" -> Lucide.Wind
    "belts" -> Lucide.RefreshCw
    "gear" -> Lucide.Cog
    "diff" -> Lucide.Settings2
    "coolant" -> Lucide.Thermometer
    "brake" -> Lucide.Droplets
    "pads" -> Lucide.Disc3
    "cabin" -> Lucide.Fan
    else -> Lucide.Wrench
}

private fun spaced(n: Int) = "%,d".format(n).replace(',', ' ')

@Composable
private fun StatusPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        T(text, 12.sp, color)
    }
}

/** One job: its icon, the interval, how much of it is used, what is left, and «Сделано». */
@Composable
private fun ServiceRow(it: ServiceItem, odo: Double?, now: Long, fmt: SimpleDateFormat, onEdit: () -> Unit) {
    val st = serviceState(it, odo, now)
    val used = it.used(odo, now)
    val km = it.kmLeft(odo)?.roundToInt()
    val days = it.daysLeft(now)
    val hours = it.hoursLeft(Maintenance.hours())?.roundToInt()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .border(1.dp, if (st == ServiceState.OVERDUE || st == ServiceState.DUE) st.color.copy(alpha = 0.5f) else C.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (st == ServiceState.OFF) C.Sub else if (st == ServiceState.UNKNOWN) C.Violet else st.color
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(serviceIcon(it), null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(it.name, 14.sp, if (it.on) C.Text else C.Sub, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                when (st) {
                    ServiceState.OVERDUE -> StatusPill(Lucide.CircleAlert, "просрочено", st.color)
                    ServiceState.DUE -> StatusPill(Lucide.Clock, "скоро", st.color)
                    ServiceState.OFF -> StatusPill(Lucide.CircleDot, "выключено", st.color)
                    else -> {}
                }
            }
            T(listOfNotNull("каждые ${spacedDistance(it.km.toDouble())}", "${it.months} мес.", if (it.hours > 0) "${it.hours} м·ч" else null).joinToString(" · ") +
                (if (it.lastMs > 0) "   ·   было ${fmt.format(Date(it.lastMs))}" + (if (it.lastKm >= 0) " на ${spacedDistance(it.lastKm)}" else "") else ""),
                11.sp, C.Sub, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
                if (used != null) Box(Modifier.fillMaxHeight().fillMaxWidth(used.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(st.color))
            }
            Spacer(Modifier.height(4.dp))
            if (used == null) T("нажмите, чтобы указать, когда делали, или «Сделано», если сегодня", 11.sp, C.Sub)
            else {
                val left = listOfNotNull(
                    km?.let { k -> if (k < 0) "−${spacedDistance(-k.toDouble())}" else spacedDistance(k.toDouble()) },
                    days?.let { d -> if (d < 0) "−${-d} дн." else "$d дн." },
                    hours?.let { h -> if (h < 0) "−${-h} м·ч" else "$h м·ч" },
                ).joinToString(" · ")
                Row {
                    T(if (st == ServiceState.OVERDUE) "просрочено: " else "осталось: ", 11.sp, C.Sub)
                    T(left, 11.sp, if (st == ServiceState.OK) C.Text else st.color, weight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(C.Purple.copy(alpha = 0.3f))
                .clickable { Maintenance.markDone(it) }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Check, null, tint = C.Text, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            T("Сделано", 13.sp)
        }
    }
}

@Composable
private fun DialogSection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        T(title, 13.sp, C.Violet, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ServiceDialog(item: ServiceItem, onClose: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    var name by remember { mutableStateOf(item.name) }
    var km by remember { mutableStateOf(kg.forester.sg.data.DistanceUnits.distanceField(item.km.toDouble())) }
    var months by remember { mutableStateOf(item.months.toString()) }
    var lastKm by remember { mutableStateOf(if (item.lastKm >= 0) kg.forester.sg.data.DistanceUnits.distanceField(item.lastKm) else "") }
    // not entered yet: today and now, the usual case is "just did it"
    var lastDate by remember { mutableStateOf(fmt.format(Date(if (item.lastMs > 0) item.lastMs else System.currentTimeMillis()))) }
    var hours by remember { mutableStateOf(if (item.hours > 0) item.hours.toString() else "") }
    var lastHours by remember { mutableStateOf(if (item.lastHours >= 0) "%.0f".format(item.lastHours) else "") }
    var on by remember { mutableStateOf(item.on) }
    CarAlertDialog(
        onDismissRequest = onClose, textScrolls = false,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(C.Violet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(serviceIcon(item), null, tint = C.Violet, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                T(if (item.custom && item.name.isEmpty()) "Новый пункт" else item.name, 20.sp, weight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.cueScroll(C.DialogHi), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.note.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Violet.copy(alpha = 0.10f)).padding(10.dp),
                ) {
                    Icon(Lucide.Info, null, tint = C.Violet, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    T(kg.forester.sg.data.DistanceUnits.shownProse(item.note), 12.sp, C.Text)
                }
                if (item.custom) TextFieldDark("Название", name) { name = it }
                DialogSection(Lucide.Repeat, "Интервал — что наступит раньше")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField(kg.forester.sg.data.DistanceUnits.distanceUnit, km) { km = it } }
                    Box(Modifier.weight(1f)) { NumberField("месяцев", months) { months = it } }
                    Box(Modifier.weight(1f)) { NumberField("моточасов", hours) { hours = it } }
                }
                T("Моточасы можно оставить пустыми — тогда только по пробегу и времени.", 11.sp, C.Sub)
                DialogSection(Lucide.History, "Когда делали в последний раз")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField("пробег, ${kg.forester.sg.data.DistanceUnits.distanceUnit}", lastKm) { lastKm = it } }
                    Box(Modifier.weight(1f)) { NumberField("моточасы", lastHours) { lastHours = it } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TextFieldDark("дата и время (дд.мм.гггг чч:мм)", lastDate) { lastDate = it } }
                    Spacer(Modifier.width(8.dp))
                    Chip("Сегодня", false, {
                        lastDate = fmt.format(Date())
                        Maintenance.odometer()?.let { lastKm = kg.forester.sg.data.DistanceUnits.distanceField(it) }
                        lastHours = "%.0f".format(Maintenance.hours())
                    }, Lucide.CalendarCheck)
                }
                if (item.lastMs == 0L) T("Подставлены сегодня и сейчас — поменяйте, если делали раньше. Пустая дата — «не отмечено».", 11.sp, C.Sub)
                Spacer(Modifier.height(4.dp))
                SwitchRow("Следить за этим пунктом", "Выключенный пункт не напоминает о себе", on) { on = it }
            }
        },
        confirmButton = {
            TextButton({
                val ms = runCatching { fmt.parse(lastDate.trim())?.time }.getOrNull()
                    ?: runCatching { dayFmt.parse(lastDate.trim())?.time }.getOrNull() ?: item.lastMs
                Maintenance.update(item.copy(
                    name = name.ifBlank { item.name.ifBlank { "Свой пункт" } },
                    km = kg.forester.sg.data.DistanceUnits.distanceFromField(km, item.km.toDouble())?.roundToInt() ?: item.km, months = months.toIntOrNull() ?: item.months,
                    lastKm = kg.forester.sg.data.DistanceUnits.distanceFromField(lastKm, item.lastKm) ?: -1.0, lastMs = if (lastDate.isBlank()) 0L else ms, on = on,
                    hours = hours.toIntOrNull() ?: 0, lastHours = lastHours.toDoubleOrNull() ?: -1.0,
                ))
                onClose()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (item.custom) TextButton({ Maintenance.remove(item.id); onClose() }) { Text("Удалить") }
                TextButton(onClose) { Text("Отмена") }
            }
        },
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
    )
}

@Composable
private fun TyresTab() {
    val ctx = LocalContext.current
    val tyres by Repo.tyres.collectAsState()
    val s = Repo.settings
    var on by remember { mutableStateOf(s.tpms) }
    var min by remember { mutableStateOf("%.1f".format(Locale.US, s.tpmsMinBar)) }
    var picked by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Tpms.permitted(ctx)) { s.tpms = true; on = true; Tpms.start(ctx) }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Disc3, "Датчики давления (TPMS)")
            Spacer(Modifier.height(8.dp))
            SwitchRow("Слушать Bluetooth-датчики", "Недорогие BLE-датчики на ниппель или внутрь колеса (видны как TPMS1…TPMS4)", on) { v ->
                if (v && !Tpms.permitted(ctx)) {
                    ask.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                } else {
                    on = v; s.tpms = v
                    if (v) Tpms.start(ctx) else Tpms.stop(ctx)
                }
            }
            Spacer(Modifier.height(10.dp))
            NumberField("Предупреждать ниже, бар", min) { min = it; it.toFloatOrNull()?.takeIf { v -> v in 0.5f..4f }?.let { v -> s.tpmsMinBar = v } }
            Spacer(Modifier.height(10.dp))
            T("Датчик 1 — переднее левое, 2 — переднее правое, 3 — заднее левое, 4 — заднее правое. Если перепутали — нажмите " +
                "на одно колесо, потом на другое: датчики поменяются местами. Предупреждение придёт и при медленной утечке " +
                "(минус 0,15 бар за 5–10 минут). Неизвестные датчики с «TPMS» в имени пишутся в журнал обмена — пришлите его, добавлю поддержку.", 11.sp, C.Sub)
        }
        Panel(Modifier.weight(1.2f).fillMaxHeight()) {
            PanelTitle(Lucide.Disc3, "Колёса")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("FL" to "FR", "RL" to "RR").forEach { (a, b) ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (w in listOf(a, b)) {
                            val r = tyres[w]
                            val bad = r != null && (r.bar < s.tpmsMinBar || r.leak)
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.CardHi)
                                    .border(2.dp, if (picked == w) C.Purple else if (bad) C.Red else C.Border, RoundedCornerShape(12.dp))
                                    .clickable {
                                        val p = picked
                                        picked = if (p == null) w else { if (p != w) Tpms.swap(p, w); null }
                                    }.padding(12.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                T(Tpms.NAMES[w] ?: w, 12.sp, C.Sub)
                                T(r?.let { "%.2f".format(it.bar) } ?: "—", 30.sp, if (bad) C.Red else C.Text, FontWeight.Bold)
                                T("бар", 12.sp, C.Sub)
                                if (r != null) T("%.0f° · батарея %d%%".format(r.tempC, r.battery) +
                                    " · ${((System.currentTimeMillis() - r.timeMs) / 60_000)} мин назад", 11.sp, C.Sub)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsTab() {
    val list by Alerts.history.collectAsState()
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.Bell, "Последние предупреждения")
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) T("Предупреждений не было", 13.sp, C.Sub)
        LazyColumn {
            items(list) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    T(fmt.format(Date(a.timeMs)), 13.sp, C.Sub, modifier = Modifier.width(roomFor(100.dp, 13f)), maxLines = 1)
                    T(a.text, 14.sp, when (a.level) { Alerts.Level.DANGER -> C.Red; Alerts.Level.WARN -> C.Orange; else -> C.Text })
                }
            }
        }
    }
}
