package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChartScatter
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Ruler
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Interval
import kg.forester.sg.service.Measured
import kg.forester.sg.service.Perf
import kg.forester.sg.service.PerfCalc
import kg.forester.sg.service.PerfRun
import kg.forester.sg.service.RunTrace
import kg.forester.sg.service.Spool
import kg.forester.sg.service.SpoolTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- names and texts of the results

/** The keys of the results shown, in order: the intervals, then 60 ft, 201 and 402 m, then the braking 100–0. */
fun resultKeys(intervals: List<Interval>) = intervals.map { it.key } + PerfCalc.DISTANCES.map { it.second } + PerfCalc.BRAKE

/** "0-100" → "0–100 км/ч", "402m" → "402 м (1/4 мили)". */
fun resultName(key: String) = when (key) {
    "18m" -> "60 футов (18 м)"
    "201m" -> "201 м (1/8 мили)"
    "402m" -> "402 м (1/4 мили)"
    PerfCalc.BRAKE -> "Торможение 100–0 км/ч"
    else -> key.replace("-", "–") + " км/ч"
}

/** The short name for lists: "0–100", "60ft", "402м". */
fun resultShort(key: String) = when (key) {
    "18m" -> "60ft"
    "201m" -> "201м"
    "402m" -> "402м"
    else -> key.replace("-", "–")
}

fun flagText(flag: String, run: PerfRun?) = when (flag) {
    PerfCalc.Flag.WOT -> "газ не в пол"
    PerfCalc.Flag.LIMP -> "педаль в полу, дроссель прикрыт (аварийный режим?)"
    PerfCalc.Flag.ZERO -> "время шло, когда машина стояла"
    PerfCalc.Flag.GAP -> "пропуски данных"
    PerfCalc.Flag.SLOPE -> run?.slope?.let { "уклон %+.1f %%".format(it) } ?: "уклон"
    PerfCalc.Flag.GNSS -> "слабый сигнал GPS"
    else -> flag
}

/** The flags of a run in one line, by result: "100–200: газ не в пол; уклон +1,4 %". */
fun flagsLine(run: PerfRun, keys: List<String>): String? = keys.mapNotNull { k ->
    run.flags[k]?.takeIf { it.isNotEmpty() }?.let { f -> "${resultShort(k)}: " + f.joinToString(", ") { flagText(it, run) } }
}.distinct().joinToString("; ").ifEmpty { null }

/** "IAT 18° · 99,1 кПа · ОЖ 90° · IAM 1,00 · «98» · двое". */
fun condLine(run: PerfRun): String {
    val c = run.cond
    return listOfNotNull(
        c.iatC?.let { "IAT $it°" }, c.baroKpa?.let { "%.1f кПа".format(it) }, c.ectC?.let { "ОЖ $it°" },
        c.iam?.let { "IAM %.2f".format(it) }, c.label.takeIf { it.isNotEmpty() }?.let { "«$it»" }, c.note.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
}

/** The run's result in standard air (SAE J1349), null without the intake temperature or the barometer, or for a braking. */
fun corrected(run: PerfRun, key: String): Float? =
    run.results[key]?.takeIf { key != PerfCalc.BRAKE }?.let { PerfCalc.corrected(it.toDouble(), run.cond.iatC?.toDouble(), run.cond.baroKpa?.toDouble())?.toFloat() }

// ---- layout

/** Panels side by side on a wide screen, one under another (scrolled) on a narrow one (a phone held upright). */
@Composable
fun PerfColumns(vararg parts: Pair<Float, @Composable (Modifier) -> Unit>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            parts.forEach { (w, c) -> c(Modifier.weight(w).fillMaxHeight()) }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            parts.forEach { (_, c) -> c(Modifier.fillMaxWidth().height(470.dp)) }
        }
    }
}

/** A small tappable word with an icon, for the corners of the panels. */
@Composable
fun SmallAction(
    text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, color: Color = C.Violet, modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        T(text, 13.sp, C.Text, maxLines = 1)
    }
}

// ---- a run from its samples

/** A run counted from its samples on [intervals], read off the main thread; null while reading or for a run without them. */
@Composable
fun rememberMeasured(run: PerfRun?, intervals: List<Interval>): Pair<RunTrace, Measured>? {
    val v by produceState<Pair<RunTrace, Measured>?>(null, run?.timeMs, run?.trace, intervals) {
        value = run?.let { r -> withContext(Dispatchers.IO) { Perf.trace(r)?.let { it to PerfCalc.measure(it, intervals) } } }
    }
    return v
}

private fun DrawScope.label(text: String, x: Float, y: Float, color: Color, size: Float = 22f, right: Boolean = false) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb(); textSize = size; isAntiAlias = true
        textAlign = if (right) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/**
 * The acceleration of a run in G over the time from the start (or over the speed), the speed under it, the gear
 * changes shaded with how long each took: where the G drops at a shift, the time goes.
 */
@Composable
fun RunGraph(m: Measured, bySpeed: Boolean, modifier: Modifier) {
    val a = m.start ?: m.speed.t.firstOrNull() ?: 0.0
    val b = m.end ?: m.speed.t.lastOrNull() ?: 1.0
    val g = remember(m) { PerfCalc.gCurve(m.speed, a - 0.3, b + 0.3) }
    if (g.size < 2) { Box(modifier, contentAlignment = Alignment.Center) { T("Слишком мало замеров для графика", 13.sp, C.Sub) }; return }
    val violet = C.Violet
    // a braking goes below zero, down to a g or more
    val braking = m.braking
    Canvas(modifier) {
        // a braking's top line (0.2 g) kept inside the graph, its label off the panel's title
        val gMax = if (braking) maxOf(0.3, g.maxOf { it.g } * 1.2) else maxOf(0.5, g.maxOf { it.g } * 1.2)
        val gMin = if (braking) minOf(-0.5, g.minOf { it.g } * 1.2).coerceAtLeast(-2.0) else minOf(0.0, g.minOf { it.g }).coerceAtLeast(-0.4)
        val vMax = maxOf(1.0, g.maxOf { it.kmh } * 1.05)
        val t0 = a - 0.3; val t1 = b + 0.3
        fun x(p: Double, kmh: Double) = (if (bySpeed) kmh / vMax else (p - t0) / (t1 - t0)).toFloat() * size.width
        fun yg(v: Double) = (size.height - (v - gMin) / (gMax - gMin) * size.height).toFloat()
        // the grid: every 0.2 g
        var lv = if (braking) kotlin.math.ceil(gMin / 0.2) * 0.2 else 0.0
        while (lv <= gMax) {
            drawLine(C.Border, Offset(0f, yg(lv)), Offset(size.width, yg(lv)), if (kotlin.math.abs(lv) < 1e-6) 2f else 1f)
            // not on top of the time axis at the bottom
            if (yg(lv) < size.height - 26f) label("%.1f g".format(if (kotlin.math.abs(lv) < 1e-6) 0.0 else lv), 4f, yg(lv) - 4f, C.Sub, 20f)
            lv += 0.2
        }
        // shift labels in rows by their real width: shifts close together and the «0.6 g» of the grid at the left
        // edge used to print over each other
        val measure = android.graphics.Paint().apply { textSize = 20f }
        val rowEnds = FloatArray(3) { 64f } // the grid's «x.x g» labels take the first 64 px of every row
        for (sh in m.shifts) {
            if (sh.to < t0 || sh.from > t1) continue
            val xa = x(sh.from, sh.kmh); val xb = if (bySpeed) xa + 6f else x(sh.to, sh.kmh)
            drawRect(C.Orange.copy(alpha = 0.18f), Offset(xa, 0f), Size((xb - xa).coerceAtLeast(3f), size.height))
            val text = "%.2f с".format(sh.sec)
            val w = measure.measureText(text)
            val lx = (xa + 4f).coerceIn(70f, (size.width - w - 2f).coerceAtLeast(70f))
            val row = rowEnds.indices.firstOrNull { lx >= rowEnds[it] + 6f } ?: continue
            label(text, lx, 22f + row * 24f, C.Orange, 20f)
            rowEnds[row] = lx + w
        }
        if (!bySpeed) {
            val sp = Path()
            g.forEachIndexed { i, q -> val px = x(q.t, q.kmh); val py = (size.height - q.kmh / vMax * size.height).toFloat(); if (i == 0) sp.moveTo(px, py) else sp.lineTo(px, py) }
            drawPath(sp, C.Blue.copy(alpha = 0.7f), style = Stroke(2.5f))
            label("%.0f км/ч".format(g.maxOf { it.kmh }), size.width - 4f, 44f, C.Blue, 20f, right = true)
        }
        val gp = Path()
        g.forEachIndexed { i, q -> val px = x(q.t, q.kmh); val py = yg(q.g); if (i == 0) gp.moveTo(px, py) else gp.lineTo(px, py) }
        drawPath(gp, violet, style = Stroke(4f))
        // the axis under it
        if (bySpeed) for (v in 0..vMax.toInt() step 50) label("$v", x(0.0, v.toDouble()) + 3f, size.height - 4f, C.Sub, 19f)
        else { var s = 0; while (a + s <= t1) { label("$s с", x(a + s, 0.0) + 3f, size.height - 4f, C.Sub, 19f); s += if (b - a > 20) 5 else 2 } }
    }
}

// ---- dialogs

/** The label and note of new runs ([run] = null) or of a run already kept. */
@Composable
fun RunLabelDialog(run: PerfRun?, onClose: () -> Unit) {
    val s = Repo.settings
    var label by remember { mutableStateOf(run?.cond?.label ?: s.perfLabel) }
    var note by remember { mutableStateOf(run?.cond?.note ?: s.perfNote) }
    val perf by Repo.perf.collectAsState()
    val known = perf.runs.map { it.cond.label }.filter { it.isNotEmpty() }.distinct().take(8)
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.CardHi,
        title = { T(if (run == null) "Метка новых замеров" else "Метка замера", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                T("По метке замеры собираются в серии для сравнения: топливо (95, 98, 100), присадка, доработка.", 13.sp, C.Sub)
                Spacer(Modifier.height(8.dp))
                TextFieldDark("Метка серии", label) { label = it }
                if (known.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    known.forEach { k -> SmallAction(k, null) { label = k } }
                }
                Spacer(Modifier.height(8.dp))
                TextFieldDark("Заметка: пассажиры, груз, шины", note) { note = it }
                if (run == null) {
                    Spacer(Modifier.height(8.dp))
                    T("Новая серия для A/B-сравнения? Сбросьте ЭБУ (клемму АКБ на 10–15 минут), проедьте одинаково для прогрева, " +
                        "и только потом мерьте: иначе выученные коррекции и IAM смешают результаты.", 12.sp, C.Orange)
                }
            }
        },
        confirmButton = {
            TextButton({
                if (run == null) { s.perfLabel = label.trim(); s.perfNote = note.trim() } else Perf.setRunInfo(run.timeMs, label, note)
                onClose()
            }) { T("Сохранить", 15.sp, C.Violet) }
        },
        dismissButton = { TextButton(onClose) { T("Отмена", 15.sp, C.Sub) } },
    )
}

private val PRESETS = listOf("0-60", "0-100", "0-150", "0-200", "50-150", "60-100", "80-120", "100-150", "100-200", "150-200")

/** The intervals the runs are timed on; on «Готово» every kept run is counted again on them. */
@Composable
fun IntervalsDialog(onClose: () -> Unit) {
    var list by remember { mutableStateOf(Repo.perf.value.intervals) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.CardHi,
        title = { T("Отрезки замера", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                T("От 0 — с места, остальные — на ходу: замер начинается, когда газ в полу. Старые замеры пересчитаются по сохранённой записи.", 13.sp, C.Sub)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { iv -> SmallAction(resultShort(iv.key), Lucide.X, C.Sub) { list = list - iv } }
                }
                Spacer(Modifier.height(10.dp))
                T("Добавить", 13.sp, C.Sub)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.mapNotNull { Interval.parse(it) }.filter { it !in list }.forEach { iv -> SmallAction(resultShort(iv.key), null) { list = Interval.list((list + iv).joinToString(",") { it.key }) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField("от, км/ч", from) { from = it } }
                    Box(Modifier.weight(1f)) { NumberField("до, км/ч", to) { to = it } }
                    val iv = Interval.parse("${from.substringBefore('.')}-${to.substringBefore('.')}")
                    SmallAction("Добавить", null, if (iv != null) C.Violet else C.Sub) {
                        if (iv != null && iv !in list) { list = Interval.list((list + iv).joinToString(",") { it.key }); from = ""; to = "" }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val l = list
                scope.launch(Dispatchers.IO) { Perf.setIntervals(l) }
                onClose()
            }) { T("Готово", 15.sp, C.Violet) }
        },
        dismissButton = { TextButton(onClose) { T("Отмена", 15.sp, C.Sub) } },
    )
}

// ---- series and A/B

/**
 * «Серии»: the runs of one interval grouped by their label, with the mean and the scatter of each, and two of them
 * compared: is the difference more than the scatter explains, or within it.
 */
@Composable
fun SeriesTab() {
    val p by Repo.perf.collectAsState()
    val keys = resultKeys(p.intervals)
    var key by remember { mutableStateOf("0-100") }
    // the interval picked, or the first one with runs
    if (key !in keys || p.runs.none { key in it.results }) keys.firstOrNull { k -> p.runs.any { k in it.results } }?.let { if (it != key) key = it }
    var weather by remember { mutableStateOf(false) }
    var flagged by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(false) }
    // the times of the series, newest first within each
    val rows = p.runs.mapNotNull { r ->
        val t = r.results[key] ?: return@mapNotNull null
        if (!flagged && !r.clean(key)) return@mapNotNull null
        r to ((if (weather) corrected(r, key) else null) ?: t).toDouble()
    }
    val groups = rows.groupBy { it.first.cond.label }.entries.sortedBy { e -> e.value.minOf { it.first.timeMs } }
    var a by remember { mutableStateOf<String?>(null) }
    var b by remember { mutableStateOf<String?>(null) }
    val labels = groups.map { it.key }
    if (a !in labels) a = null
    if (b !in labels) b = null
    if (a == null && b == null && labels.size == 2) { a = labels[0]; b = labels[1] }
    val ta = groups.firstOrNull { it.key == a }?.value?.map { it.second } ?: emptyList()
    val tb = groups.firstOrNull { it.key == b }?.value?.map { it.second } ?: emptyList()
    val hidden = p.runs.count { key in it.results && !it.clean(key) }
    fun name(l: String?) = if (l.isNullOrEmpty()) "без метки" else "«$l»"

    PerfColumns(
        1.1f to { m ->
            Panel(m) {
                PanelTitle(Lucide.Tag, "Серии замеров")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.filter { k -> p.runs.any { k in it.results } }.forEach { k -> Chip(resultShort(k), k == key, { key = k }) }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("С поправкой на погоду", "SAE J1349 по IAT и давлению, без учёта влажности", weather) { weather = it }
                SwitchRow("Учитывать замеры с пометками", if (hidden > 0) "сейчас не учтено: $hidden" else "газ не в пол, уклон, пропуски", flagged) { flagged = it }
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) T("Замеров «${resultShort(key)}» ещё нет", 13.sp, C.Sub)
                T("Нажмите на серию, чтобы выбрать её как A или B", 11.sp, C.Sub)
                LazyColumn(Modifier.weight(1f)) {
                    items(groups, key = { it.key }) { (label, list) ->
                        val st = PerfCalc.stats(list.map { it.second })!!
                        val tag = when (label) { a -> "A"; b -> "B"; else -> null }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (tag != null) C.Purple.copy(alpha = 0.2f) else C.CardHi)
                                .clickable {
                                    when {
                                        label == a -> a = null
                                        label == b -> b = null
                                        a == null -> a = label
                                        else -> b = label
                                    }
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(tag ?: "", 16.sp, if (tag == "A") C.Blue else C.Orange, FontWeight.Bold, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                T(name(label) + "  ·  ${st.n} шт.", 13.sp, C.Sub, maxLines = 1)
                                T("%.2f ± %.2f с  ·  лучший %.2f".format(st.mean, st.sd, st.min), 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        1.3f to { m ->
            Panel(m) {
                PanelTitle(Lucide.ChartScatter, "Сравнение A/B")
                Spacer(Modifier.height(8.dp))
                val v = if (ta.isNotEmpty() && tb.isNotEmpty()) PerfCalc.compare(ta, tb) else null
                val (text, color) = when {
                    v == null -> "Выберите две серии слева: A — как было, B — что проверяете" to C.Sub
                    v.significant == null -> "Мало замеров: нужно хотя бы по 2 в каждой серии, лучше 3–5" to C.Orange
                    v.significant -> "${name(b)} ${if (v.diff < 0) "быстрее" else "медленнее"} на %.2f с: больше погрешности (±%.2f с)".format(kotlin.math.abs(v.diff), v.margin) to
                        (if (v.diff < 0) C.Green else C.Red)
                    else -> "Разница %.2f с — в пределах погрешности (±%.2f с): разницы не видно, или нужно больше замеров".format(v.diff, v.margin) to C.Text
                }
                T(text, 16.sp, color, FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (ta.isNotEmpty() || tb.isNotEmpty()) SeriesPlot(ta, tb, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(6.dp))
                listOf("A" to (a to ta), "B" to (b to tb)).forEach { (tag, g) ->
                    val st = PerfCalc.stats(g.second) ?: return@forEach
                    Row {
                        T("$tag ", 13.sp, if (tag == "A") C.Blue else C.Orange, FontWeight.Bold)
                        T("${name(g.first)}: среднее %.2f с, разброс ±%.2f с (%d шт.), %.2f…%.2f".format(st.mean, st.sd, st.n, st.min, st.max), 12.sp, C.Sub, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(4.dp))
                T("Погрешность — 95 % по t-критерию Уэлча: разница меньше неё может быть случайной.", 10.sp, C.Sub)
            }
        },
        0.9f to { m ->
            Panel(m.verticalScroll(rememberScrollState())) {
                PanelTitle(Lucide.Gauge, "Как сравнивать")
                Spacer(Modifier.height(8.dp))
                T("Новые замеры: " + (Repo.settings.perfLabel.ifEmpty { "без метки" }) + Repo.settings.perfNote.let { if (it.isEmpty()) "" else " · $it" }, 14.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                ActionButton("Сменить метку", true) { editLabel = true }
                Spacer(Modifier.height(10.dp))
                T("• Перед новой серией сбросьте ЭБУ (клемма АКБ на 10–15 минут) и прогрейте одинаково: иначе коррекции и IAM смешают результаты.\n" +
                    "• Один и тот же участок, в обе стороны (ветер и уклон), чередуйте A и B.\n" +
                    "• Хотя бы 3–5 замеров в серии; одинаковые бак, пассажиры, давление в шинах.\n" +
                    "• Замеры с пометками (газ не в пол, уклон больше 1 %, пропуски) в сравнение не идут.\n" +
                    "• Поправка на погоду пересчитывает время к 25 °C и 99 кПа по IAT и давлению из ЭБУ; у турбомотора с регулировкой наддува она завышена — сравнивайте в похожую погоду.",
                    12.sp, C.Sub)
            }
        },
    )
    if (editLabel) RunLabelDialog(null) { editLabel = false }
}

/** The times of two series as dots on one axis, each with its mean and ±1 scatter. */
@Composable
private fun SeriesPlot(a: List<Double>, b: List<Double>, modifier: Modifier) {
    val all = a + b
    val lo = all.min() - 0.1; val hi = all.max() + 0.1
    Canvas(modifier) {
        fun x(v: Double) = ((v - lo) / (hi - lo)).toFloat() * size.width
        var tick = kotlin.math.floor(lo * 10) / 10
        val step = if (hi - lo > 2) 0.5 else 0.1
        while (tick <= hi) {
            if (tick >= lo) { drawLine(C.Border, Offset(x(tick), 0f), Offset(x(tick), size.height - 24f), 1f); label("%.1f".format(tick), x(tick) + 2f, size.height - 4f, C.Sub, 19f) }
            tick += step
        }
        listOf(a to C.Blue, b to C.Orange).forEachIndexed { row, (l, c) ->
            if (l.isEmpty()) return@forEachIndexed
            val y = (size.height - 24f) * (row + 1) / 3f
            val st = PerfCalc.stats(l)!!
            drawRect(c.copy(alpha = 0.18f), Offset(x(st.mean - st.sd), y - 26f), Size((x(st.mean + st.sd) - x(st.mean - st.sd)).coerceAtLeast(2f), 52f))
            drawLine(c, Offset(x(st.mean), y - 30f), Offset(x(st.mean), y + 30f), 3f)
            l.forEach { drawCircle(c, 8f, Offset(x(it), y)) }
            label(if (row == 0) "A" else "B", 4f, y - 32f, c, 22f)
        }
    }
}

// ---- spool

/** «Спул»: the rpm where the boost comes, tested now and then the same way, and how it moved over the months. */
@Composable
fun SpoolTab() {
    val p by Repo.perf.collectAsState()
    val live by Repo.live.collectAsState()
    val sp = p.spool
    var picked by remember { mutableStateOf<Long?>(null) }
    val test = sp.tests.firstOrNull { it.timeMs == picked } ?: sp.tests.firstOrNull()
    // only tests in the same gear compare: the engine speed per km/h within 8 %
    val same = test?.rpmPerKmh?.let { k -> sp.tests.filter { t -> t.rpmPerKmh != null && kotlin.math.abs(t.rpmPerKmh / k - 1) < 0.08f } } ?: emptyList()
    val drift = PerfCalc.spoolDrift(same.reversed().mapNotNull { it.rpm1 ?: it.rpm0 })
    val first = same.lastOrNull()?.takeIf { it != test }
    PerfColumns(
        1f to { m ->
            Panel(m) {
                PanelTitle(Lucide.Gauge, "Тест спула")
                Spacer(Modifier.height(8.dp))
                T(when {
                    sp.pulling -> "Держите газ в полу до ~4500 об/мин"
                    sp.armed -> "Ровная дорога, 4-я передача, 1500–2000 об/мин — и газ в пол до ~4500"
                    else -> "Раз в месяц один и тот же тест: на 4-й передаче с 1500–2000 об/мин газ в пол. Приложение запишет, на каких оборотах пришли 0 и 1 бар."
                }, 13.sp, if (sp.armed) C.Green else C.Sub)
                if (sp.hint.isNotEmpty()) T(sp.hint, 13.sp, C.Orange)
                Spacer(Modifier.height(12.dp))
                FitValue((live.rpm ?: 0).toString(), "об/мин", 48f, Modifier.fillMaxWidth().height(64.dp), if (sp.pulling) C.Violet else C.Text)
                T("наддув " + (live.gaugeKpa?.let { "%+.2f бар".format(it / 100f) } ?: "—"), 18.sp)
                Spacer(Modifier.height(12.dp))
                test?.let { t ->
                    T("Последний: 1 бар на ${t.rpm1 ?: "—"} об/мин", 16.sp, weight = FontWeight.SemiBold)
                    T("0 бар на ${t.rpm0 ?: "—"}" + (t.rpmTarget?.let { " · цель ${"%.2f".format((t.targetKpa ?: 0f) / 100f)} бар на $it" } ?: "") +
                        " · пик %.2f бар".format(t.peakKpa / 100f), 13.sp, C.Sub)
                }
                Spacer(Modifier.weight(1f))
                ActionButton(if (sp.armed) "Отменить тест" else "Начать тест", true, danger = sp.armed) { Spool.arm(!sp.armed) }
            }
        },
        1.3f to { m ->
            Panel(m) {
                PanelTitle(Lucide.ChartScatter, "Наддув по оборотам")
                Spacer(Modifier.height(6.dp))
                if (drift != null) T(if (drift >= 300) "Спул сместился вверх на $drift об/мин: проверьте впуск на утечки (тест дымом), вестгейт и соленоид наддува"
                    else "Спул стабилен: %+d об/мин к первым тестам".format(drift), 13.sp, if (drift >= 300) C.Red else C.Green)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (test != null && test.curve.size >= 2) SpoolPlot(test, first, Modifier.fillMaxSize())
                    else T("Здесь будет кривая наддува по оборотам", 13.sp, C.Sub)
                }
                Row {
                    T("— этот тест   ", 12.sp, C.Violet)
                    if (first != null) T("— первый на той же передаче", 12.sp, C.Sub)
                }
            }
        },
        0.9f to { m ->
            Panel(m) {
                PanelTitle(Lucide.Ruler, "История спула")
                Spacer(Modifier.height(6.dp))
                if (sp.tests.isEmpty()) T("Тестов ещё нет", 13.sp, C.Sub)
                LazyColumn(Modifier.weight(1f)) {
                    items(sp.tests, key = { it.timeMs }) { t -> SpoolRow(t, t == test) { picked = t.timeMs } }
                }
            }
        },
    )
}

@Composable
private fun SpoolRow(t: SpoolTest, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) C.Purple.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(t.timeMs)) +
                listOfNotNull(t.iatC?.let { "  ·  IAT $it°" }, t.rpmPerKmh?.let { "  ·  %.0f об/км/ч".format(it) }).joinToString("") +
                if (t.demo) "  ·  демо" else "", 11.sp, C.Sub, maxLines = 1)
            T("0 бар ${t.rpm0 ?: "—"}  ·  1 бар ${t.rpm1 ?: "—"}" + (t.rpmTarget?.let { "  ·  цель $it" } ?: ""), 13.sp, maxLines = 1)
        }
        Icon(Lucide.X, "Удалить", tint = C.Sub, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable { Spool.delete(t.timeMs) }.padding(7.dp))
    }
}

@Composable
private fun SpoolPlot(t: SpoolTest, ref: SpoolTest?, modifier: Modifier) {
    val violet = C.Violet
    Canvas(modifier.padding(4.dp)) {
        val pts = t.curve + (ref?.curve ?: emptyList())
        val r0 = (pts.minOf { it.rpm } / 500) * 500f; val r1 = maxOf(r0 + 500f, pts.maxOf { it.rpm }.toFloat())
        val k0 = minOf(-60f, pts.minOf { it.kpa }); val k1 = maxOf(120f, pts.maxOf { it.kpa } + 10f)
        fun x(r: Int) = (r - r0) / (r1 - r0) * size.width
        fun y(k: Float) = size.height - 24f - (k - k0) / (k1 - k0) * (size.height - 24f)
        for (k in listOf(0f, 100f)) { drawLine(C.Border, Offset(0f, y(k)), Offset(size.width, y(k)), 2f); label(if (k == 0f) "0 бар" else "1 бар", 4f, y(k) - 4f, C.Sub, 20f) }
        var r = r0.toInt()
        while (r <= r1) { label("$r", x(r) + 3f, size.height - 4f, C.Sub, 19f); r += 500 }
        fun line(c: List<kg.forester.sg.service.SpoolPt>, color: Color, w: Float) {
            val path = Path()
            c.forEachIndexed { i, q -> if (i == 0) path.moveTo(x(q.rpm), y(q.kpa)) else path.lineTo(x(q.rpm), y(q.kpa)) }
            drawPath(path, color, style = Stroke(w))
        }
        ref?.let { line(it.curve, C.Sub, 2.5f) }
        line(t.curve, violet, 4f)
        for (rp in listOfNotNull(t.rpm0, t.rpm1)) drawCircle(C.Orange, 7f, Offset(x(rp), y(if (rp == t.rpm0) 0f else 100f)))
    }
}

// ---- the run under the graph

/** Peak G, 60 ft, the shifts and where the speed came from, in one line. */
fun runFacts(run: PerfRun, m: Measured?): String = listOfNotNull(
    run.peakG?.let { "пик %.2f g".format(it) },
    run.results["18m"]?.let { "60 футов %.2f с".format(it) },
    // the same standing interval as a drag strip or Dragy times it, from one foot of roll-out
    run.results.keys.filter(PerfCalc::isRollout).let { ks -> ks.firstOrNull { it == "0-100" + PerfCalc.ROLLOUT } ?: ks.firstOrNull() }
        ?.let { k -> "${resultShort(k.removeSuffix(PerfCalc.ROLLOUT))} с roll-out 1 фут %.2f с".format(run.results[k]) },
    m?.shifts?.takeIf { it.isNotEmpty() }?.let { s -> "переключения " + s.joinToString(" / ") { "%.2f".format(it.sec) } + " с" },
    run.source.takeIf { it.isNotEmpty() },
).joinToString("  ·  ")

/** The flags of [run] with a warning sign, if there are any. */
@Composable
fun RunFlags(run: PerfRun, keys: List<String>) {
    val line = flagsLine(run, keys) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.TriangleAlert, null, tint = C.Orange, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        T(line, 12.sp, C.Orange, maxLines = 2)
    }
}
