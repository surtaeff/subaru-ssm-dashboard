package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
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
import kg.forester.sg.service.DynoCalc
import kg.forester.sg.service.DynoRun
import kg.forester.sg.service.DynoStore
import kg.forester.sg.service.Motion
import kg.forester.sg.service.Perf
import kg.forester.sg.service.PerfCalc
import kg.forester.sg.service.PerfState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun runTitle(r: kg.forester.sg.service.PerfRun) = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(r.timeMs)) +
    (if (r.maxSpeed > 0) "  ·  ${if (r.braking) "с" else "до"} ${kg.forester.sg.data.DistanceUnits.speedText(r.maxSpeed)}" else "") +
    (if (r.braking) "  ·  торможение" else if (!r.standing) "  ·  на ходу" else "") +
    (r.cond.label.takeIf { it.isNotEmpty() }?.let { "  ·  «$it»" } ?: "") + if (r.demo) "  ·  демо" else ""

private fun runLine(r: kg.forester.sg.service.PerfRun, keys: List<String>) =
    keysOfRun(r, keys).mapNotNull { k ->
        r.results[k]?.let { "${resultShort(k)} %.2f".format(it) + (r.results[k + PerfCalc.DIST]?.let { d -> " · %.1f м".format(d) } ?: "") +
            if (r.clean(k)) "" else "⚠" }
    }.joinToString("   ")

private fun lapTime(sec: Float) = "%d:%05.2f".format((sec / 60).toInt(), sec % 60)

/**
 * «Замеры»: acceleration timer, series and A/B, spool test, power estimate, lap timer, G-meter and tilt. Read parked like
 * the other screens of cards and lists: the text's floor ([Parked]) keeps its 10 to 15 sp from being the smallest in the app.
 */
@Composable
fun PerfScreen() = Parked { PerfBody() }

@Composable
private fun PerfBody() {
    var tab by remember { mutableStateOf(0) }
    // a run's or a lap's log on the charts, with its map
    var open by remember { mutableStateOf<java.io.File?>(null) }
    open?.let { f -> LogViewer(f) { open = null }; return }
    val turbo = kg.forester.sg.data.Repo.guard.collectAsState().value.turboCar
    if (!turbo && tab == 5) tab = 0
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Разгон", tab == 0, { tab = 0 }, Lucide.Timer, Modifier.keepInView(tab == 0))
            Chip("Серии", tab == 4, { tab = 4 }, Lucide.Tag, Modifier.keepInView(tab == 4))
            // the spool test is about the turbo: an atmospheric engine has no boost to come on
            if (turbo) Chip("Спул", tab == 5, { tab = 5 }, Lucide.Gauge, Modifier.keepInView(tab == 5))
            Chip("Диностенд", tab == 1, { tab = 1 }, Lucide.ChartSpline, Modifier.keepInView(tab == 1))
            Chip("Круги", tab == 2, { tab = 2 }, Lucide.Flag, Modifier.keepInView(tab == 2))
            Chip("G-метр и крен", tab == 3, { tab = 3 }, Lucide.Crosshair, Modifier.keepInView(tab == 3))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScrollWhenLow(440.dp) {
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
                // all that reads scrolls together, the buttons stay: on 7" the results had no height left between them
                Column(Modifier.weight(1f).cueScroll(null)) {
                T(
                    when {
                        p.running && p.rolling -> "На ходу, газ в полу: замер идёт, пока не отпустите газ"
                        p.running -> "Едем! Замер закончится, когда отпустите газ"
                        p.armed -> "Готов: трогайтесь, время пойдёт с первого движения"
                        else -> "Остановитесь полностью, чтобы замерить разгон с места. На ходу замер начинается сам, когда газ в полу"
                    }, 13.sp, if (p.armed || p.running) C.Green else C.Sub,
                )
                Spacer(Modifier.height(12.dp))
                FitValue(if (p.running || p.elapsedSec > 0) "%.2f".format(p.elapsedSec) else "—", "с", 64f, Modifier.fillMaxWidth().height(90.dp), C.highlight(C.Violet), everyMs = 0L)
                T(kg.forester.sg.data.DistanceUnits.speedText(p.speedKmh) + if (p.rolling) "" else " · %.0f м".format(p.distanceM), 18.sp)
                Spacer(Modifier.height(12.dp))
                Column {
                    keys.forEach { k ->
                        p.live[k]?.let { v ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                T(resultName(k), 14.sp, C.Sub, modifier = Modifier.weight(1f))
                                T("%.2f с".format(v) + (if (k.endsWith("m")) p.live[k.replace("m", "v")]?.let { "  ·  " + kg.forester.sg.data.DistanceUnits.speedText(it) } ?: "" else ""), 15.sp, weight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                T("Скорость — GPS, если он даёт 4 замера в секунду и чаще, иначе ЭБУ с поправкой по GPS; моменты пересечения " +
                    "интерполируются по времени замеров. " + (measured?.second?.speed?.let { "Замер на графике: ${it.precisionText()}." }
                        ?: "С GPS от 10 Гц или быстрым опросом ЭБУ точность около ±0,1 с, иначе около ±0,3 с."), 10.sp, C.Sub)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("Отрезки", Lucide.Ruler) { intervals = true }
                    SmallAction(Repo.settings.perfLabel.ifEmpty { "Метка" }, Lucide.Tag) { label = true }
                }
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
                        T("${resultShort(k)} с поправкой на погоду (SAE): ${correctedText(run, k)}", 12.sp, C.Sub, maxLines = 1)
                    }
                    T(runFacts(run, measured?.second), 12.sp, C.Sub, maxLines = 2)
                    condLine(run).takeIf { it.isNotEmpty() }?.let { T(it, 12.sp, C.Sub, maxLines = 1) }
                    RunFlags(run, keys)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (file != null) SmallAction("Графики и карта", Lucide.ChartLine) { analyse(file) }
                        measured?.let { (tr, ms) -> SmallAction("Поделиться", Lucide.Share2) { shareRun(ctx, run, tr, ms, keys) } }
                        SmallAction("Метка", Lucide.Tag) { labelRun = run }
                        var askRun by remember(run.timeMs) { mutableStateOf(false) }
                        SmallAction("Удалить", Lucide.Trash2, C.Red) { askRun = true }
                        if (askRun) ConfirmDialog(
                            "Удалить замер?", "Замер вместе с записью для графиков уйдёт из списка. Вернуть его будет нельзя.",
                            "Удалить", { Perf.deleteRun(run.timeMs); picked = null }, { askRun = false },
                        )
                    }
                }
            }
        },
        1.05f to { m ->
            Panel(m) {
                // a new run goes on top: the list follows it there, unless it was scrolled down to older ones
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(p.runs.firstOrNull()?.timeMs) { if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }
                ListWithHead({
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
                }, state = list) {
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
                            if (f != null) IconTap(Lucide.ChartLine, "Графики", { analyse(f) }, tint = C.Violet, iconSize = 22.dp)
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
    if (confirmClear) CarAlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = C.CardHi,
        title = { T("Удалить все замеры?", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            T("Из списка уйдут все замеры (${p.runs.size}) вместе с записями для графиков. " +
                "Вернуть их будет нельзя. Заезды диностенда останутся.", 15.sp)
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

/** What the dyno tab draws from the state of the timer, without the samples that change it all the time. */
private data class DynoState(val runs: List<DynoRun>, val pulling: Boolean, val rejected: Boolean, val shown: Long?)

private fun PerfState.forDyno() = DynoState(dynoRuns, dynoPulling, dynoRejected, dynoShown)

@Composable
private fun DynoTab() {
    val first = remember { Repo.perf.value.forDyno() }
    val state by remember { Repo.perf.map { it.forDyno() }.distinctUntilChanged() }.collectAsState(first)
    val s = Repo.settings
    var mass by remember { mutableStateOf(s.massKg.toString()) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var compared by remember { mutableStateOf<Long?>(null) }
    var noCompare by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf(false) }
    var labelRun by remember { mutableStateOf<DynoRun?>(null) }
    // the poll goes fast only while this is on the screen: not with the app in the background
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val cycle = owner.lifecycle
        val seen = androidx.lifecycle.LifecycleEventObserver { _, _ -> Repo.dynoTab.value = cycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        cycle.addObserver(seen)
        onDispose {
            cycle.removeObserver(seen)
            Repo.dynoTab.value = false
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val demo by Repo.demoOn.collectAsState()
    LaunchedEffect(demo) { withContext(Dispatchers.IO) { Perf.loadDyno() } }
    // a new pull is what's shown next, against the one before it
    LaunchedEffect(state.pulling) { if (state.pulling) { picked = null; compared = null; noCompare = false } }
    val runs = state.runs
    val views by produceState(emptyMap<Long, DynoStore.View>(), runs) {
        value = withContext(Dispatchers.IO) { runs.associate { it.startMs to DynoStore.view(it) } }
    }
    // the pull just driven: its longest part, not the part it ended on
    val cur = runs.firstOrNull { it.startMs == picked } ?: if (state.pulling || state.rejected) null else runs.firstOrNull { it.startMs == state.shown } ?: runs.firstOrNull()
    val ref = cur?.takeIf { !noCompare }?.let { c -> runs.firstOrNull { it.startMs == compared && it != c } ?: DynoCalc.previousSameGear(runs, c) }
    val view = cur?.let { views[it.startMs] }
    val curve = view?.curve ?: emptyList()
    val refCurve = ref?.let { views[it.startMs]?.curve }
    PerfColumns(
        2f to { m ->
            Panel(m) {
                PanelTitle(Lucide.ChartSpline, "Мощность на колёсах (оценка)") {
                    Chip("% от пика", percent, { percent = !percent })
                }
                val maxHp = curve.maxByOrNull { it.hp }
                val maxNm = curve.maxByOrNull { it.nm }
                when {
                    cur == null && state.pulling -> T("Идёт заезд: держите газ в полу до отсечки", 13.sp, C.Green)
                    cur == null && state.rejected -> T("Заезд не засчитан: слишком короткий или мало замеров. Повторите или выберите заезд в списке", 13.sp, C.Orange)
                    maxHp == null || maxNm == null -> T("Нет замера: на ровной дороге на 3-й передаче от ~2000 об/мин нажмите газ в пол до отсечки", 13.sp, C.Sub)
                    else -> {
                        T("%.0f л.с. при %d об/мин · %.0f Н·м при %d об/мин · на коленвалу ≈ %.0f л.с. (+15 %% потерь трансмиссии)"
                            .format(maxHp.hp, maxHp.rpm, maxNm.nm, maxNm.rpm, maxHp.hp * 1.15f), 13.sp, C.Sub, maxLines = 1)
                        DynoCalc.compareLines(curve, refCurve).forEach { T(it, 15.sp, weight = FontWeight.SemiBold, maxLines = 1) }
                        if (ref != null && DynoCalc.otherGear(cur!!, ref)) T(kg.forester.sg.data.DistanceUnits.label("Другая передача: обороты на км/ч отличаются больше чем на 8 %, сравнение условное"), 12.sp, C.Orange, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (curve.size >= 2) DynoChart(curve, refCurve, percent, Modifier.fillMaxSize())
                    else T("Здесь будут кривые мощности и момента", 13.sp, C.Sub)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("● ", 12.sp, C.Violet); T("мощность${if (percent) "" else ", л.с."}   ", 12.sp, C.Sub)
                    T("● ", 12.sp, C.Blue); T("момент${if (percent) "" else ", Н·м"}   ", 12.sp, C.Sub)
                    if (ref != null) T("пунктир: ${dynoTitle(ref)}   ", 12.sp, C.Sub, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (cur != null) SmallAction(cur.label ?: "Метка", Lucide.Tag) { labelRun = cur }
                }
                view?.let { v ->
                    T(listOfNotNull("%.0f замеров в секунду".format(v.hz),
                        v.slopePct?.let { "уклон %+.1f %% учтён".format(it) } ?: "уклон меньше 3 % или не измерен, дорога считается ровной",
                        cur?.iatC?.let { "IAT $it°" }, cur?.baroKpa?.let { "%.1f кПа".format(it) }, cur?.coolantC?.let { "ОЖ $it°" },
                        cur?.iam?.let { "IAM %.2f".format(it) }).joinToString(" · "), 11.sp, C.Sub, maxLines = 1)
                }
            }
        },
        1f to { m ->
            Panel(m) {
                PanelTitle(Lucide.Timer, "Заезды")
                Spacer(Modifier.height(6.dp))
                if (runs.isEmpty()) T("Заездов ещё нет. Хранятся последние 100, у каждого свой файл замеров", 13.sp, C.Sub)
                // a new pull goes on top: the list follows it there
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(runs.firstOrNull()?.startMs) { list.scrollToItem(0) }
                LazyColumn(Modifier.weight(1f), state = list) {
                    items(runs, key = { it.startMs }) { r ->
                        DynoRow(r, views[r.startMs], r == cur, r == ref, onClick = { picked = r.startMs }, onCompare = {
                            if (r == ref) { compared = null; noCompare = true } else { compared = r.startMs; noCompare = false }
                        }, onDelete = {
                            if (r.startMs == picked) picked = null
                            scope.launch(Dispatchers.IO) { Perf.deleteDyno(r) }
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
                NumberField("Масса машины с водителем, кг", mass) { mass = it; it.toIntOrNull()?.takeIf { v -> v in 600..4000 }?.let { v -> s.massKg = v } }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
                    T("Мощность = (масса × ускорение + сопротивление воздуха + качения + уклон) × скорость. Ускорение по оборотам, скорость ЭБУ задаёт " +
                        "только передаточное число заезда; уклон по высоте GPS, если он от 3 %. Это оценка: на точность влияют ветер, масса и шины, " +
                        "поэтому сравнивайте свои заезды между собой на одной передаче, в похожих условиях. Пока открыт этот экран и газ в полу, " +
                        "ЭБУ опрашивается чаще, а предупреждения проверяются реже: остальные значения приходят только раз в несколько секунд. В заезд идут " +
                        "только разгоны при открытом экране. Через ELM327 замеры идут слишком редко для диностенда.", 12.sp, C.Sub)
                }
            }
        },
    )
    labelRun?.let { r -> RunLabelDialog(r) { labelRun = null } }
}

private fun dynoTitle(r: DynoRun) = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(r.startMs)) + (r.label?.let { " «$it»" } ?: "")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
            FitValue(if (lap.running) lapTime(lap.currentSec) else "—", "", 64f, Modifier.fillMaxWidth().height(90.dp), C.highlight(C.Violet), everyMs = 0L)
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
            androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center) {
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
                        if (f != null) IconTap(Lucide.ChartLine, "Графики", { analyse(f) }, Modifier.padding(start = 6.dp), tint = C.Violet, iconSize = 22.dp)
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

