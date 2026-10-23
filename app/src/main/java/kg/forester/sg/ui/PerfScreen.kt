package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChartLine
import com.composables.icons.lucide.ChartSpline
import com.composables.icons.lucide.Map
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Ruler
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.Trash2
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Motion
import kg.forester.sg.service.Perf
import kg.forester.sg.service.PerfCalc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun runTitle(r: kg.forester.sg.service.PerfRun) = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(r.timeMs)) +
    (if (r.maxSpeed > 0) "  ·  ${if (r.braking) "с" else "до"} %.0f км/ч".format(r.maxSpeed) else "") +
    (if (r.braking) "  ·  торможение" else if (!r.standing) "  ·  на ходу" else "") +
    (r.cond.label.takeIf { it.isNotEmpty() }?.let { "  ·  «$it»" } ?: "") + if (r.demo) "  ·  демо" else ""

private fun runLine(r: kg.forester.sg.service.PerfRun, keys: List<String>) =
    keys.mapNotNull { k ->
        r.results[k]?.let { "${resultShort(k)} %.2f".format(it) + (r.results[k + PerfCalc.DIST]?.let { d -> " · %.1f м".format(d) } ?: "") +
            if (r.clean(k)) "" else "⚠" }
    }.joinToString("   ")

private fun lapTime(sec: Float) = "%d:%05.2f".format((sec / 60).toInt(), sec % 60)

/** «Замеры»: acceleration timer, series and A/B, spool test, power estimate, lap timer, G-meter and tilt. */
@Composable
fun PerfScreen() {
    var tab by remember { mutableStateOf(0) }
    // a run's or a lap's log on the charts, with its map
    var open by remember { mutableStateOf<java.io.File?>(null) }
    open?.let { f -> LogViewer(f) { open = null }; return }
    val turbo = kg.forester.sg.data.Repo.guard.collectAsState().value.turboCar
    if (!turbo && tab == 5) tab = 0
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Разгон", tab == 0, { tab = 0 }, Lucide.Timer)
            Chip("Серии", tab == 4, { tab = 4 }, Lucide.Tag)
            // the spool test is about the turbo: an atmospheric engine has no boost to come on
            if (turbo) Chip("Спул", tab == 5, { tab = 5 }, Lucide.Gauge)
            Chip("Диностенд", tab == 1, { tab = 1 }, Lucide.ChartSpline)
            Chip("Круги", tab == 2, { tab = 2 }, Lucide.Flag)
            Chip("G-метр и крен", tab == 3, { tab = 3 }, Lucide.Crosshair)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> RunsTab { open = it }
                1 -> DynoTab()
                2 -> LapsTab { open = it }
                4 -> SeriesTab()
                5 -> SpoolTab()
                else -> GTab()
            }
        }
    }
}

@Composable
private fun RunsTab(analyse: (java.io.File) -> Unit) {
    val p by Repo.perf.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val keys = resultKeys(p.intervals)
    // the run on the graph and the map: the one tapped in the list, else the last one
    var picked by remember { mutableStateOf<Long?>(null) }
    val run = p.runs.firstOrNull { it.timeMs == picked } ?: p.runs.firstOrNull()
    var intervals by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(false) }
    var labelRun by remember { mutableStateOf<kg.forester.sg.service.PerfRun?>(null) }
    var showMap by remember { mutableStateOf(false) }
    var bySpeed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val live = p.running || run == null
    // the run on the graph, counted from its samples: the graph, the facts under it and how close its times are
    val measured = rememberMeasured(run?.takeIf { !live }, p.intervals)
    PerfColumns(
        1f to { m ->
            Panel(m) {
                PanelTitle(Lucide.Timer, "Замер разгона")
                Spacer(Modifier.height(8.dp))
                T(
                    when {
                        p.running && p.rolling -> "На ходу, газ в полу: замер идёт, пока не отпустите газ"
                        p.running -> "Едем! Замер закончится, когда отпустите газ"
                        p.armed -> "Готов: трогайтесь, время пойдёт с первого движения"
                        else -> "Остановитесь полностью, чтобы замерить разгон с места. На ходу замер начинается сам, когда газ в полу"
                    }, 13.sp, if (p.armed || p.running) C.Green else C.Sub,
                )
                Spacer(Modifier.height(12.dp))
                FitValue(if (p.running || p.elapsedSec > 0) "%.2f".format(p.elapsedSec) else "—", "с", 64f, Modifier.fillMaxWidth().height(90.dp), C.Violet)
                T("%.0f км/ч".format(p.speedKmh) + if (p.rolling) "" else " · %.0f м".format(p.distanceM), 18.sp)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    keys.forEach { k ->
                        p.live[k]?.let { v ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                T(resultName(k), 14.sp, C.Sub, modifier = Modifier.weight(1f))
                                T("%.2f с".format(v) + (if (k.endsWith("m")) p.live[k.replace("m", "v")]?.let { "  ·  %.0f км/ч".format(it) } ?: "" else ""), 15.sp, weight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("Отрезки", Lucide.Ruler) { intervals = true }
                    SmallAction(Repo.settings.perfLabel.ifEmpty { "Метка" }, Lucide.Tag) { label = true }
                }
                Spacer(Modifier.height(6.dp))
                T("Скорость — GPS, если он даёт 4 замера в секунду и чаще, иначе ЭБУ с поправкой по GPS; моменты пересечения " +
                    "интерполируются по времени замеров. " + (measured?.second?.speed?.let { "Замер на графике: ${it.precisionText()}." }
                        ?: "С GPS от 10 Гц или быстрым опросом ЭБУ точность около ±0,1 с, иначе около ±0,3 с."), 10.sp, C.Sub)
            }
        },
        1.25f to { m ->
            Panel(m) {
                PanelTitle(if (showMap) Lucide.Map else Lucide.ChartLine, if (showMap) "Траектория" else "Ускорение") {
                    // the padding is inside the click area: the tap target is the size of a button, not of the 12 sp word
                    val link = Modifier.clip(RoundedCornerShape(8.dp))
                    T(if (showMap) "график" else "карта", 12.sp, C.Violet,
                        modifier = link.clickable { showMap = !showMap }.padding(horizontal = 8.dp, vertical = 6.dp))
                    if (!showMap) T(if (bySpeed) "по времени" else "по скорости", 12.sp, C.Violet,
                        modifier = link.clickable { bySpeed = !bySpeed }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                Spacer(Modifier.height(6.dp))
                val file = run?.let { Perf.logFile(it.log, it.demo) }
                if (showMap) {
                    val saved by androidx.compose.runtime.produceState<Track?>(null, file) {
                        value = file?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Track.read(it) } }
                    }
                    val track = if (live) Track.of(p.track) else saved
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (track?.any == true) Canvas(Modifier.fillMaxSize()) {
                            drawTracks(listOf(TrackLine(track, if (live) C.Violet else C.Orange, 4f, mark = if (live) track.size - 1 else null,
                                start = true, finish = !live)))
                        }
                        else T(if (live) "Здесь будет путь разгона по GPS" else "У этого замера нет записи GPS", 13.sp, C.Sub)
                    }
                } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        live -> T(if (p.running) "Идёт замер: график появится, когда он закончится" else "Замеров ещё нет", 13.sp, C.Sub)
                        measured != null -> RunGraph(measured.second, bySpeed, Modifier.fillMaxSize())
                        run?.trace == null -> T("У этого замера нет записи для графика", 13.sp, C.Sub)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (!live) {
                    T(runTitle(run!!), 12.sp, C.Sub, maxLines = 1)
                    T(runLine(run, keys), 14.sp, weight = FontWeight.SemiBold, maxLines = 2)
                    keys.firstOrNull { it in run.results && corrected(run, it) != null }?.let { k ->
                        T("${resultShort(k)} с поправкой на погоду (SAE): %.2f с".format(corrected(run, k)), 12.sp, C.Sub, maxLines = 1)
                    }
                    T(runFacts(run, measured?.second), 12.sp, C.Sub, maxLines = 2)
                    condLine(run).takeIf { it.isNotEmpty() }?.let { T(it, 12.sp, C.Sub, maxLines = 1) }
                    RunFlags(run, keys)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (file != null) SmallAction("Графики и карта", Lucide.ChartLine) { analyse(file) }
                        measured?.let { (tr, ms) -> SmallAction("Поделиться", Lucide.Share2) { shareRun(ctx, run, tr, ms, keys) } }
                        SmallAction("Метка", Lucide.Tag) { labelRun = run }
                        SmallAction("Удалить", Lucide.Trash2, C.Red) { Perf.deleteRun(run.timeMs); picked = null }
                    }
                }
            }
        },
        1.05f to { m ->
            Panel(m) {
                // clearing the list moved under it, as a proper button asking first (it was a small word beside the "i",
                // one stray tap away from losing every run)
                PanelTitle(Lucide.Flag, "Лучшие и последние")
                Spacer(Modifier.height(8.dp))
                // the best of the clean runs only: one with the pedal not held or downhill is no record
                val best = keys.mapNotNull { k -> p.runs.filter { it.clean(k) }.mapNotNull { it.results[k] }.minOrNull()?.let { k to it } }
                if (best.isEmpty()) T("Замеров ещё нет", 13.sp, C.Sub)
                best.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        row.forEach { (k, v) ->
                            Row(Modifier.weight(1f)) {
                                T("${resultShort(k)} ", 12.sp, C.Sub)
                                T("%.2f с".format(v), 13.sp, weight = FontWeight.SemiBold)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                // a new run goes on top: the list follows it there, unless it was scrolled down to older ones
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(p.runs.firstOrNull()?.timeMs) { if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }
                LazyColumn(Modifier.weight(1f), state = list) {
                    items(p.runs, key = { it.timeMs }) { r ->
                        val on = r == run && !p.running
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) C.Purple.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { picked = r.timeMs }.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(runTitle(r), 11.sp, C.Sub, maxLines = 1)
                                T(runLine(r, keys), 13.sp, if (r.flags.isEmpty()) C.Text else C.Orange)
                            }
                            val f = Perf.logFile(r.log, r.demo)
                            if (f != null) androidx.compose.material3.Icon(Lucide.ChartLine, "Графики", tint = C.Violet,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { analyse(f) }.padding(7.dp))
                        }
                    }
                }
                if (p.runs.isNotEmpty() && !p.running) {
                    Spacer(Modifier.height(8.dp))
                    SmallAction("Очистить список", Lucide.Trash2, C.Red, Modifier.heightIn(min = 48.dp)) { confirmClear = true }
                }
            }
        },
    )
    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = C.CardHi,
        title = { T("Удалить все замеры?", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            T("Из списка уйдут все замеры (${p.runs.size}) вместе с записями для графиков, и замеры мощности тоже. " +
                "Вернуть их будет нельзя.", 15.sp)
        },
        confirmButton = {
            androidx.compose.material3.TextButton({ confirmClear = false; picked = null; Perf.clearRuns() }) { T("Удалить все", 16.sp, C.Red) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ confirmClear = false }) { T("Отмена", 16.sp, C.Text) } },
    )
    if (intervals) IntervalsDialog { intervals = false }
    if (label) RunLabelDialog(null) { label = false }
    labelRun?.let { r -> RunLabelDialog(r) { labelRun = null } }
}

@Composable
private fun DynoTab() {
    val p by Repo.perf.collectAsState()
    val s = Repo.settings
    var mass by remember { mutableStateOf(s.massKg.toString()) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(2f).fillMaxHeight()) {
            PanelTitle(Lucide.ChartSpline, "Мощность на колёсах (оценка)")
            val maxHp = p.dyno.maxByOrNull { it.hp }
            val maxNm = p.dyno.maxByOrNull { it.nm }
            T(if (maxHp == null) "Нет замера: на ровной дороге на 3-й передаче от ~2000 об/мин нажмите газ в пол до отсечки"
            else "%.0f л.с. при %d об/мин · %.0f Н·м при %d об/мин (на коленвалу, без потерь) · на коленвалу ≈ %.0f л.с. (+15 %% потерь трансмиссии)"
                .format(maxHp.hp, maxHp.rpm, maxNm!!.nm, maxNm.rpm, maxHp.hp * 1.15f), 13.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (p.dyno.size >= 2) Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    val r0 = p.dyno.first().rpm.toFloat(); val r1 = p.dyno.last().rpm.toFloat().coerceAtLeast(r0 + 1)
                    val hpMax = (p.dyno.maxOf { it.hp } * 1.15f).coerceAtLeast(1f)
                    val nmMax = (p.dyno.maxOf { it.nm } * 1.15f).coerceAtLeast(1f)
                    fun x(r: Int) = (r - r0) / (r1 - r0) * size.width
                    for (k in 0..4) drawLine(C.Border, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
                    val hp = Path(); val nm = Path()
                    p.dyno.forEachIndexed { i, d ->
                        val yh = size.height - d.hp / hpMax * size.height
                        val yn = size.height - d.nm / nmMax * size.height
                        if (i == 0) { hp.moveTo(x(d.rpm), yh); nm.moveTo(x(d.rpm), yn) } else { hp.lineTo(x(d.rpm), yh); nm.lineTo(x(d.rpm), yn) }
                    }
                    drawPath(hp, C.Violet, style = Stroke(4f))
                    drawPath(nm, C.Blue, style = Stroke(3f))
                }
            }
            Row {
                T("— мощность, л.с.   ", 12.sp, C.Violet)
                T("— момент, Н·м   ", 12.sp, C.Blue)
                if (p.dyno.isNotEmpty()) T("${p.dyno.first().rpm}…${p.dyno.last().rpm} об/мин", 12.sp, C.Sub)
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Timer, "Как считается")
            Spacer(Modifier.height(8.dp))
            NumberField("Масса машины с водителем, кг", mass) { mass = it; it.toIntOrNull()?.takeIf { v -> v in 600..4000 }?.let { v -> s.massKg = v } }
            Spacer(Modifier.height(8.dp))
            T("Мощность = (масса × ускорение + сопротивление воздуха + качения) × скорость. Ускорение — по скорости ЭБУ, " +
                "сглаживание 0,6 с. Это оценка «виртуального диностенда»: на точность влияют уклон, ветер, масса и шины. " +
                "Сравнивайте свои замеры между собой (до и после доработки) в одинаковых условиях.", 12.sp, C.Sub)
        }
    }
}

@Composable
private fun LapsTab(analyse: (java.io.File) -> Unit) {
    val p by Repo.perf.collectAsState()
    val gps by Repo.gps.collectAsState()
    val lap = p.lap
    // the lap on the map under the one being driven: the one tapped in the list, else the best
    var picked by remember { mutableStateOf<Long?>(null) }
    val shown = lap.laps.firstOrNull { it.timeMs == picked } ?: lap.laps.minByOrNull { it.sec }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(0.9f).fillMaxHeight()) {
            PanelTitle(Lucide.Flag, "Секундомер кругов")
            Spacer(Modifier.height(8.dp))
            T(if (!lap.lineSet) "Встаньте на линии старта/финиша и нажмите кнопку. Круг засчитывается, когда машина проезжает в 25 м от этой точки после отъезда дальше 60 м."
            else "Линия старта задана. Каждое прохождение рядом с ней — новый круг.", 13.sp, C.Sub)
            Spacer(Modifier.height(12.dp))
            FitValue(if (lap.running) lapTime(lap.currentSec) else "—", "", 64f, Modifier.fillMaxWidth().height(90.dp), C.Violet)
            lap.laps.lastOrNull()?.let { T("последний ${lapTime(it.sec)}", 16.sp) }
            lap.best?.let { T("лучший ${lapTime(it)}", 16.sp, C.Green) }
            Spacer(Modifier.weight(1f))
            ActionButton("Старт/финиш здесь", gps?.lat != null) { Perf.setLapLine(gps) }
            Spacer(Modifier.height(8.dp))
            ActionButton("Сбросить круги", lap.lineSet, danger = true) { Perf.clearLaps() }
        }
        Panel(Modifier.weight(1.3f).fillMaxHeight()) {
            PanelTitle(Lucide.Map, "Траектория")
            Spacer(Modifier.height(6.dp))
            val file = shown?.let { Perf.logFile(it.log, it.demo) }
            val saved by androidx.compose.runtime.produceState<Track?>(null, file) {
                value = file?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Track.read(it) } }
            }
            val line = Repo.settings.lapLine.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            val current = Track.of(lap.track)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (saved?.any == true || current.any || line != null) Canvas(Modifier.fillMaxSize()) {
                    drawTracks(listOfNotNull(
                        // the lap to beat underneath, the one being driven over it
                        saved?.let { TrackLine(it, C.Orange.copy(alpha = if (lap.running) 0.55f else 1f), 4f) },
                        current.takeIf { it.any }?.let { TrackLine(it, C.Violet, 4.5f, mark = it.size - 1) },
                    ), flag = line)
                }
                else T("Задайте старт/финиш: здесь появится трасса", 13.sp, C.Sub)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current.any) { T("● ", 13.sp, C.Violet); T(if (lap.running) "текущий круг   " else "к линии старта   ", 13.sp, C.Sub) }
                if (shown != null) {
                    T("● ", 13.sp, C.Orange)
                    T("круг ${lap.laps.indexOf(shown) + 1}  ${lapTime(shown.sec)}" + if (shown.sec == lap.best) " (лучший)" else "", 13.sp, C.Sub)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (shown != null) ActionButton(if (file != null) "Графики и карта круга" else "Лога этого круга нет", file != null) { file?.let(analyse) }
        }
        Panel(Modifier.weight(0.9f).fillMaxHeight()) {
            PanelTitle(Lucide.Timer, "Круги")
            LazyColumn {
                items(lap.laps.withIndex().reversed().toList(), key = { it.value.timeMs }) { (i, l) ->
                    val f = Perf.logFile(l.log, l.demo)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (l == shown) C.Purple.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { picked = l.timeMs }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T("Круг ${i + 1}" + if (l.demo) " · демо" else "", 14.sp, C.Sub, modifier = Modifier.weight(1f))
                        T(lapTime(l.sec), 15.sp, if (l.sec == lap.best) C.Green else C.Text, FontWeight.SemiBold)
                        if (f != null) androidx.compose.material3.Icon(Lucide.ChartLine, "Графики", tint = C.Violet,
                            modifier = Modifier.padding(start = 6.dp).size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { analyse(f) }.padding(7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GTab() {
    val st by Repo.motion.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GMeterCard("perf.gmeter", Modifier.weight(1.3f).fillMaxHeight(), big = true)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InclineCard("perf.incline", Modifier.weight(1f).fillMaxWidth())
            Panel(Modifier.fillMaxWidth()) {
                T(when {
                    !st.hasSensor -> "В этом устройстве нет акселерометра: перегрузки считаются по скорости ЭБУ и повороту по GPS " +
                        "(на SH и новее — по датчику VDC, если блок его отдаёт), крена нет, уклон — по высоте GPS."
                    !st.calibrated -> "Датчик наклона есть. Поставьте машину на ровное место и нажмите «Обнулить»: так приложение узнает, как стоит магнитола."
                    else -> "Датчик откалиброван. Если переставили магнитолу или телефон — обнулите снова на ровном месте."
                }, 12.sp, C.Sub)
                Spacer(Modifier.height(8.dp))
                if (st.hasSensor) { ActionButton("Обнулить (машина стоит ровно)", true) { Motion.calibrate() }; Spacer(Modifier.height(8.dp)) }
                ActionButton("Сбросить максимумы", true) { Motion.resetMax() }
            }
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, singleLine = true,
        label = { FieldLabel(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A text field in the app's dark style (same look as [NumberField]); [digits] keeps only digits and dots. */
@Composable
fun TextFieldDark(label: String, value: String, digits: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(if (digits) v.filter { it.isDigit() || it == '.' } else v) }, singleLine = true,
        label = { FieldLabel(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 15.sp)
            if (sub.isNotEmpty()) T(sub, 11.sp, C.Sub)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
    }
}
