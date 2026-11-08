package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.composables.icons.lucide.*
import kg.forester.sg.service.LogColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One numeric column of a CSV log; NaN where the cell was empty. */
private class LogSeries(val key: String, val title: String, val unit: String, val group: String, val v: FloatArray)

/** A CSV log read back: [t] seconds from the first line, [startMs] the wall clock of the first line if known. */
private class LogData(val t: DoubleArray, val startMs: Long?, val series: List<LogSeries>) {
    val duration get() = if (t.isEmpty()) 0.0 else t.last() - t.first()
}

private val UNITS = listOf(
    "_kmh" to kg.forester.sg.data.DistanceUnits.STORED_SPEED, "_c" to "°C", "_v" to "В", "_pct" to "%", "_kpa" to "кПа", "_deg" to "°", "_gs" to "г/с",
    "_ms" to "мс", "_lh" to "л/ч", "_m" to "м", "_g" to "g", "_grev" to "г/об", "_ohm" to "Ом", "_rpm" to "об/мин",
)

private val COLORS get() = listOf(C.Blue, C.Orange, C.Green, C.Violet)

/** Reads a log the app wrote (or battery.csv): the first column is the time, the numeric ones become charts. */
private fun readLog(f: File, rr: Map<String, kg.forester.sg.ssm.RrParam> = emptyMap()): LogData {
    val lines = f.bufferedReader().use { r -> r.lineSequence().take(400_000).toList() }
    if (lines.size < 2) return LogData(DoubleArray(0), null, emptyList())
    val head = lines[0].split(',')
    val rows = lines.drop(1).map { it.split(',') }.filter { it.size >= 2 }
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val ms = rows.mapIndexed { i, r ->
        val c = r[0]
        c.toLongOrNull() ?: runCatching { dateFmt.parse(c)?.time }.getOrNull() ?: (i * 1000L)
    }
    // the wall clock: epoch ms in the first column, else (ms from the start there) the start in the file name
    val wall = ms.firstOrNull()?.takeIf { it > 1_000_000_000_000L } ?: ms.firstOrNull()?.let { first ->
        Regex("\\d{4}-\\d{2}-\\d{2}[_ ]\\d{2}-\\d{2}-\\d{2}").find(f.name)?.value
            ?.let { runCatching { SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(it.replace(' ', '_'))?.time }.getOrNull() }
            ?.let { it + first }
    }
    val t0 = ms.firstOrNull() ?: 0L
    val t = DoubleArray(ms.size) { (ms[it] - t0) / 1000.0 }
    // battery.csv: a type per line (resting / crank / charging) — the voltage and the further numbers of each type
    val ti = head.indexOf("type"); val vi = head.indexOf("volts")
    if (ti >= 0 && vi >= 0) {
        fun col(name: String) = head.indexOf(name)
        fun series(key: String, title: String, unit: String, group: String, type: String, c: Int, k: Float = 1f): LogSeries? {
            if (c < 0) return null
            val v = FloatArray(rows.size) { i -> if (rows[i].getOrNull(ti) == type) rows[i].getOrNull(c)?.toFloatOrNull()?.times(k) ?: Float.NaN else Float.NaN }
            return if (v.all { it.isNaN() }) null else LogSeries(key, title, unit, group, v)
        }
        val list = listOfNotNull(
            series("resting", "Напряжение покоя", "В", "Покой", "resting", vi),
            series("parked", "Стояла перед замером", "ч", "Покой", "resting", col("parked_h")),
            series("soc", "Заряд по покою", "%", "Покой", "resting", col("soc_pct")),
            series("crank", "Минимум при пуске", "В", "Пуск", "crank", vi),
            series("crank_s", "Крутил стартер", "с", "Пуск", "crank", col("extra"), 0.001f),
            series("recovery", "До 13.2 В после пуска", "с", "Пуск", "crank", col("recovery_s")),
            series("crank_rpm", "Обороты прокрутки", "об/мин", "Пуск", "crank", col("crank_rpm")),
            series("crank_t", "Темп. впуска при пуске", "°C", "Пуск", "crank", col("intake_c")),
            series("charging", "Заряд, среднее", "В", "Генератор", "charging", vi),
            series("idle_v", "На холостых", "В", "Генератор", "charging", col("idle_v")),
            series("rev_v", "На 1800–3500 об/мин", "В", "Генератор", "charging", col("rev_v")),
            series("min_v", "Минимум за поездку", "В", "Генератор", "charging", col("min_v")),
            series("max_v", "Максимум за поездку", "В", "Генератор", "charging", col("max_v")),
            series("duty", "Загрузка генератора", "%", "Генератор", "charging", col("extra")),
        )
        return LogData(t, wall, list)
    }
    val known = LogColumns.all.associateBy { it.key }
    val keys = head.map { it.trim() }.toSet()
    val series = (1 until head.size).mapNotNull { c ->
        // a RomRaider parameter that repeats a regular column (older logs wrote both): shown once, under the regular name
        val param = Regex("^([PE]\\d+) ").find(head[c].trim())?.groupValues?.get(1)?.let { rr[it] }
        if (param != null && LogColumns.twinOf(param) in keys) return@mapNotNull null
        // the second clock at the end (the wall clock, or ms from the start in 0.9-beta21's logs) is no chart
        if (head[c].trim() == LogColumns.EPOCH_MS || head[c].trim() == LogColumns.T_MS) return@mapNotNull null
        val v = FloatArray(rows.size) { rows[it].getOrNull(c)?.toFloatOrNull() ?: Float.NaN }
        if (v.all { it.isNaN() }) return@mapNotNull null
        val key = head[c].trim()
        val col = known[key]
        val unit = when (key) {
            "rpm" -> "об/мин"
            else -> UNITS.firstOrNull { key.endsWith(it.first) }?.second ?: ""
        }
        // the file stays in metric; a chart of it speaks the driver's unit
        fun shownValues(u: String): FloatArray = kg.forester.sg.data.DistanceUnits.kindOf(u)?.let { k -> FloatArray(v.size) { i -> if (v[i].isNaN()) v[i] else kg.forester.sg.data.DistanceUnits.shown(k, v[i]) } } ?: v
        when {
            col != null -> LogSeries(key, col.title.substringBefore(","), kg.forester.sg.data.DistanceUnits.kindOf(unit)?.let(kg.forester.sg.data.DistanceUnits::unit) ?: unit, col.group, shownValues(unit))
            // a RomRaider parameter ticked on «Датчики»: "P12 Name units"
            param != null -> LogSeries(key, param.name, param.shownUnit, if (param.target == 2) "Датчики АКПП" else "Датчики ЭБУ", shownValues(param.unitsRu))
            Regex("^[PE]\\d+ ").containsMatchIn(key) -> LogSeries(key, key.substringAfter(' '), "", "Датчики ЭБУ", v)
            else -> LogSeries(key, key, "", "Прочее", v)
        }
    }
    return LogData(t, wall, series)
}

private fun groupIcon(g: String): ImageVector = when (g) {
    "Двигатель" -> Lucide.Gauge
    "Топливо" -> Lucide.Fuel
    "Зажигание и детонация" -> Lucide.Zap
    "Наддув" -> Lucide.Fan
    "GPS" -> Lucide.Satellite
    "Перегрузки" -> Lucide.Crosshair
    "АКПП" -> Lucide.Cog
    "Уровень топлива" -> Lucide.Droplets
    "Датчики ЭБУ" -> Lucide.ListChecks
    "Покой" -> Lucide.Moon
    "Пуск" -> Lucide.Zap
    "Генератор" -> Lucide.BatteryCharging
    else -> Lucide.Activity
}

private fun clock(s: Double): String {
    val x = s.toLong().coerceAtLeast(0)
    return if (x >= 3600) "%d:%02d:%02d".format(x / 3600, x / 60 % 60, x % 60) else "%d:%02d".format(x / 60, x % 60)
}

private fun num(v: Float): String = when {
    v.isNaN() -> "—"
    kotlin.math.abs(v) >= 100 -> "%.0f".format(v)
    kotlin.math.abs(v) >= 10 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

/** "512 Б", "41 КБ", "3.2 МБ". */
fun fileSize(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    else -> "%.1f МБ".format(b / 1048576.0)
}

/** Hands the file to another app (Telegram, mail, Drive…) through a copy in the shared cache folder. */
private fun shareLog(ctx: Context, f: File) {
    runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val copy = File(dir, f.name).also { f.copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", copy)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить лог")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Saves the file to «Загрузки/CarPulse», shares it, and (with [onDeleted]) deletes it after a confirmation;
 * the log being written right now cannot be deleted.
 */
@Composable
fun LogFileActions(file: File, small: Boolean = false, onDeleted: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val writing = kg.forester.sg.data.Repo.logFile.collectAsState().value == file.name
    var ask by remember(file) { mutableStateOf(false) }
    if (small) {
        IconTap(Lucide.Download, "Скачать", { saveLog(ctx, file) }, tint = C.Text)
        IconTap(Lucide.Share2, "Поделиться", { shareLog(ctx, file) }, tint = C.Text)
        if (onDeleted != null) IconTap(Lucide.Trash2, "Удалить", { ask = true }, tint = C.Text, enabled = !writing)
    } else {
        Chip("Скачать", false, { saveLog(ctx, file) }, Lucide.Download)
        Spacer(Modifier.width(6.dp))
        Chip("Поделиться", false, { shareLog(ctx, file) }, Lucide.Share2)
        if (onDeleted != null && !writing) {
            Spacer(Modifier.width(6.dp))
            Chip("Удалить", false, { ask = true }, Lucide.Trash2)
        }
    }
    if (ask) CarAlertDialog(
        onDismissRequest = { ask = false },
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
        icon = { Icon(Lucide.Trash2, null, tint = C.Red, modifier = Modifier.size(26.dp)) },
        title = { T("Удалить лог?", 19.sp, weight = FontWeight.SemiBold) },
        text = {
            T("${file.name} (${fileSize(file.length())}) будет удалён с магнитолы. Это нельзя отменить." +
                if (file.name.startsWith("battery")) " Журнал на экране «АКБ» останется, новый файл начнётся с первой записи." else "", 14.sp)
        },
        confirmButton = {
            androidx.compose.material3.TextButton({
                ask = false
                val ok = file.delete()
                android.widget.Toast.makeText(ctx, kg.forester.sg.data.tr(if (ok) "Удалено: ${file.name}" else "Не удалось удалить"), android.widget.Toast.LENGTH_SHORT).show()
                if (ok) onDeleted?.invoke()
            }) { Text("Удалить", color = C.Red) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ ask = false }) { Text("Отмена", color = C.Sub) } },
    )
}

/** Copies the log into the public Downloads folder, where a file manager or a USB cable finds it. */
private fun saveLog(ctx: Context, f: File) {
    val msg = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/CarPulse")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("нет доступа")
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        } else {
            val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ctx.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                (ctx as? android.app.Activity)?.requestPermissions(arrayOf(perm), 41)
                error("разрешите доступ к памяти и нажмите ещё раз")
            }
            @Suppress("DEPRECATION")
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CarPulse").apply { mkdirs() }
            f.copyTo(File(dir, f.name), overwrite = true)
        }
        "Сохранено: Загрузки/CarPulse/${f.name}"
    }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("log save: $it"); "Не удалось сохранить: ${kg.forester.sg.data.Failure.text(it)}" }
    android.widget.Toast.makeText(ctx, kg.forester.sg.data.tr(msg), android.widget.Toast.LENGTH_LONG).show()
}

/**
 * The log viewer of the «Лог» screen: up to four parameters on stacked charts over the time of the drive, a
 * cursor that shows the values at one moment (drag over the charts), and an overview strip of the whole file to
 * move the zoomed window.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LogViewer(file: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // Back closes the log and returns to the screen it was opened from, not to Home
    androidx.activity.compose.BackHandler(onBack = onClose)
    var data by remember(file) { mutableStateOf<LogData?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        runCatching { withContext(Dispatchers.IO) { readLog(file, kg.forester.sg.ssm.RomRaider.load(ctx).associateBy { it.id }) } }
            .onSuccess { data = it }.onFailure { kg.forester.sg.ssm.SsmTrace.log("log read: $it"); error = kg.forester.sg.data.Failure.text(it, "файл испорчен") }
    }
    var picked by remember(file) { mutableStateOf(listOf<String>()) }
    // the window shown: centre and width as fractions of the whole file
    var zoom by remember(file) { mutableStateOf(1f) }
    var centre by remember(file) { mutableStateOf(0.5f) }
    var cursor by remember(file) { mutableStateOf(0.5f) } // 0…1 across the window
    val d = data
    LaunchedEffect(d) {
        if (d != null && picked.isEmpty()) {
            val pref = listOf("rpm", "speed_kmh", "boost_kpa", "coolant_c", "resting", "crank", "charging", "atf_c")
            picked = (pref.filter { k -> d.series.any { it.key == k } } + d.series.map { it.key }).distinct().take(3)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(Modifier.fillMaxWidth().clip(Rounded).panelFill().border(1.dp, C.Border, Rounded)
            .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Lucide.ArrowLeft, null, tint = C.Text, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(6.dp))
            Icon(Lucide.FileSpreadsheet, null, tint = C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                T(file.name, 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                val info = d?.let {
                    val start = it.startMs?.let { ms -> SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(ms)) + " · " } ?: ""
                    "$start${clock(it.duration)} · ${it.t.size} строк · ${fileSize(file.length())}"
                } ?: (error?.let { "не удалось прочитать: $it" } ?: "читаю…")
                T(info, 12.sp, C.Sub, maxLines = 1)
            }
            IconTap(Lucide.ZoomOut, "Уменьшить", { zoom = (zoom / 2f).coerceAtLeast(1f) }, tint = C.Text, enabled = zoom > 1f)
            IconTap(Lucide.ZoomIn, "Увеличить", { centre = windowAt(centre, zoom, cursor); zoom = (zoom * 2f).coerceAtMost(256f); cursor = 0.5f }, tint = C.Text, enabled = d != null && zoom < 256f)
            IconTap(Lucide.Maximize2, "Весь график", { zoom = 1f; centre = 0.5f }, tint = C.Text, enabled = zoom > 1f)
            Spacer(Modifier.width(6.dp))
            LogFileActions(file, onDeleted = onClose)
        }
        Spacer(Modifier.height(10.dp))
        if (d == null) return@Column
        if (d.series.isEmpty()) { T("В файле нет данных.", 14.sp, C.Sub); return@Column }

        // the window shown and the moment under the cursor, for the charts and the map
        val n = d.t.size
        val total = d.duration.coerceAtLeast(1.0)
        val half = 0.5f / zoom
        val c = centre.coerceIn(half, 1f - half)
        val w0 = d.t.first() + (c - half) * total
        val w1 = d.t.first() + (c + half) * total
        val i0 = lowerBound(d.t, w0).coerceIn(0, n - 1)
        val i1 = (lowerBound(d.t, w1) + 1).coerceIn(i0 + 1, n)
        val at = w0 + cursor * (w1 - w0)
        val ic = lowerBound(d.t, at).coerceIn(0, n - 1)
        // a log with GPS: where the car was, the dot follows the cursor
        val track = remember(d) {
            val la = d.series.firstOrNull { it.key == "gps_lat" }
            val lo = d.series.firstOrNull { it.key == "gps_lon" }
            if (la == null || lo == null) null
            else Track(DoubleArray(n) { la.v[it].toDouble() }, DoubleArray(n) { lo.v[it].toDouble() }).takeIf { it.any }
        }

        var pickAll by remember(file) { mutableStateOf(false) }
        fun toggle(key: String) {
            val i = picked.indexOf(key)
            picked = if (i >= 0) picked - key else (picked + key).takeLast(4)
        }
        if (pickAll) SeriesDialog(d.series, picked, ::toggle) { pickAll = false }

        Row(Modifier.fillMaxSize()) {
            // on a 600-high screen the 250 dp map left the parameters a strip of two rows: it gives way on low screens
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.width(300.dp).fillMaxHeight()) {
            val mapHeight = (maxHeight * 0.42f).coerceIn(150.dp, 250.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (track != null) Panel(Modifier.fillMaxWidth().height(mapHeight), padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Map, null, tint = C.Violet, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        T("Траектория", 13.sp, C.Sub, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        T("нажмите на точку", 11.sp, C.Sub)
                    }
                    // a tap (or a finger moved) on the track puts the charts' cursor at that moment
                    fun seek(size: androidx.compose.ui.unit.IntSize, p: Offset) {
                        val fit = TrackFit.of(listOf(track), null, size.width.toFloat(), size.height.toFloat()) ?: return
                        val i = fit.nearest(track, p) ?: return
                        val t = d.t[i]
                        // a moment outside the zoomed window: the window moves to it
                        val cc = ((t - d.t.first()) / total).toFloat().coerceIn(half, 1f - half)
                        if (t < w0 || t > w1) centre = cc
                        val a = if (t < w0 || t > w1) d.t.first() + (cc - half) * total else w0
                        val b = if (t < w0 || t > w1) d.t.first() + (cc + half) * total else w1
                        cursor = ((t - a) / (b - a)).toFloat().coerceIn(0f, 1f)
                    }
                    Canvas(
                        Modifier.fillMaxWidth().weight(1f)
                            .pointerInput(track, zoom, centre) { detectTapGestures { p -> seek(size, p) } }
                            .pointerInput(track, zoom, centre) { detectDragGestures { ch, _ -> seek(size, ch.position) } },
                    ) {
                        drawTracks(listOf(TrackLine(track, C.Violet, 3.5f, hi = if (zoom > 1f) i0 until i1 else null, mark = ic, start = true, finish = true)))
                    }
                }
                // parameters to show
                Panel(Modifier.fillMaxWidth().weight(1f), padding = 12.dp) {
                    // a driver did not notice the list scrolls: the whole list opens wide on a tap, a bar and «ещё ниже» mark the rest
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("Параметры", 15.sp, weight = FontWeight.SemiBold)
                            T("на графике ${picked.size} из 4", 12.sp, if (picked.size >= 4) C.Orange else C.Sub)
                        }
                        Chip("Все ${d.series.size}", false, { pickAll = true }, Lucide.LayoutGrid)
                    }
                    Spacer(Modifier.height(8.dp))
                    val list = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize().cueScroll(null, list), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SeriesGroups(d.series, picked, ::toggle)
                        }
                        // gone at the end of the list, so it never hides the last chips
                        if (list.value < list.maxValue) MoreBelow(Modifier.align(Alignment.BottomCenter)) {
                            scope.launch { list.animateScrollTo(list.value + (list.viewportSize * 0.7f).toInt()) }
                        }
                    }
                }
            }
            }
            Spacer(Modifier.width(12.dp))
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 12.dp) {
                val shown = picked.mapNotNull { k -> d.series.firstOrNull { it.key == k } }

                // the moment under the cursor
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Clock, null, tint = C.Sub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    // the time of the record the values below are from
                    val ik = shown.firstOrNull()?.let { nearest(it.v, d.t, at, ic) } ?: ic
                    val wall = d.startMs?.let { "  ·  " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it + (d.t[ik] * 1000).toLong())) } ?: ""
                    T("${clock(d.t[ik])}$wall", 13.sp, C.Text, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    T(if (zoom > 1f) "показано ${clock(w1 - w0)} из ${clock(total)}  ·  ведите пальцем" else "ведите пальцем по графику", 12.sp, C.Sub)
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .pointerInput(file) {
                            // the plot starts after the lane padding and the value scale
                            val left = (LANE_PAD + GUTTER).toPx(); val right = LANE_PAD.toPx()
                            detectTapGestures { p -> cursor = ((p.x - left) / (size.width - left - right)).coerceIn(0f, 1f) }
                        }
                        .pointerInput(file) {
                            val left = (LANE_PAD + GUTTER).toPx(); val right = LANE_PAD.toPx()
                            detectDragGestures { ch, _ -> cursor = ((ch.position.x - left) / (size.width - left - right)).coerceIn(0f, 1f) }
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (shown.isEmpty()) T("Выберите параметры слева.", 14.sp, C.Sub)
                    shown.forEachIndexed { idx, s ->
                        val color = COLORS[picked.indexOf(s.key).coerceIn(0, 3)]
                        val stats = remember(s, i0, i1) { stats(s.v, i0, i1) }
                        val (lo, hi) = axis(stats)
                        // the sample nearest to the cursor (the battery journal has one every few minutes)
                        val k = nearest(s.v, d.t, at, ic)
                        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                            .padding(horizontal = LANE_PAD, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                                Spacer(Modifier.width(6.dp))
                                T(s.title, 13.sp, C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
                                T(k?.let { num(s.v[it]) } ?: "—", 16.sp, color, weight = FontWeight.Bold)
                                if (s.unit.isNotEmpty()) { Spacer(Modifier.width(3.dp)); T(s.unit, 11.sp, C.Sub) }
                                Spacer(Modifier.width(12.dp))
                                T("мин ${num(stats[0])} · ср ${num(stats[1])} · макс ${num(stats[2])}", 11.sp, C.Sub)
                            }
                            Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                                val g = GUTTER.toPx()
                                drawScale(lo, hi, g)
                                inset(left = g, top = 0f, right = 0f, bottom = 0f) {
                                    drawSeries(d.t, s.v, i0, i1, w0, w1, lo, hi, color, fill = idx == 0)
                                    val x = cursor * size.width
                                    drawLine(C.Text.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), 1.5f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                                    if (k != null && !lo.isNaN()) {
                                        val px = ((d.t[k] - w0) / (w1 - w0) * size.width).toFloat()
                                        val py = size.height - (s.v[k] - lo) / (hi - lo) * size.height
                                        drawCircle(C.Ink, 6.5f, Offset(px, py))
                                        drawCircle(color, 4.5f, Offset(px, py))
                                    }
                                }
                            }
                        }
                    }
                    if (shown.isNotEmpty()) TimeAxis(w0, w1, d.startMs)
                }
                // the whole file, with the zoomed window marked; tap or drag to move it
                Spacer(Modifier.height(8.dp))
                val first = shown.firstOrNull() ?: d.series.first()
                val all = remember(first) { stats(first.v, 0, n) }
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(C.Card)
                    .border(1.dp, C.Border, RoundedCornerShape(8.dp))
                    .pointerInput(file) { detectTapGestures { p -> centre = (p.x / size.width).coerceIn(0f, 1f) } }
                    .pointerInput(file) { detectDragGestures { ch, _ -> centre = (ch.position.x / size.width).coerceIn(0f, 1f) } }) {
                    Canvas(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        val (alo, ahi) = axis(all)
                        drawSeries(d.t, first.v, 0, n, d.t.first(), d.t.first() + total, alo, ahi, C.Gray, fill = false)
                        if (zoom > 1f) {
                            val l = (c - half) * size.width
                            drawRect(C.Violet.copy(alpha = 0.22f), Offset(l, -4f), Size(size.width / zoom, size.height + 8f))
                            drawRect(C.Violet, Offset(l, -4f), Size(size.width / zoom, size.height + 8f), style = Stroke(2f))
                        }
                    }
                    val hm = SimpleDateFormat("HH:mm", Locale.US)
                    T(d.startMs?.let { hm.format(Date(it)) } ?: clock(0.0), 10.sp, C.Sub,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp))
                    T(d.startMs?.let { hm.format(Date(it + (total * 1000).toLong())) } ?: clock(total), 10.sp, C.Sub,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 2.dp))
                }
            }
        }
    }
}

private val LANE_PAD = 10.dp
private val GUTTER = 40.dp

/** The value range drawn: the samples' min…max with some room above and below (a value that never moved gets ±5 %). */
private fun axis(st: FloatArray): Pair<Float, Float> {
    val lo = st[0]; val hi = st[2]
    if (lo.isNaN()) return Float.NaN to Float.NaN
    val pad = if (hi - lo < 1e-6f) maxOf(kotlin.math.abs(hi) * 0.05f, 0.5f) else (hi - lo) * 0.1f
    return (lo - pad) to (hi + pad)
}

/** The index of the non-empty sample of [v] closest in time to [at] ([from] = the index at or after it). */
private fun nearest(v: FloatArray, t: DoubleArray, at: Double, from: Int): Int? {
    var a = from; var b = from - 1
    var best: Int? = null
    var steps = 0
    while ((a < v.size || b >= 0) && steps++ < 20_000) {
        if (a < v.size && !v[a].isNaN()) { best = a; break }
        if (b >= 0 && !v[b].isNaN()) { best = b; break }
        a++; b--
    }
    // the first hit going one way may still be farther than one the other way a step later
    if (best != null) {
        val other = if (best >= from) (best - 1 downTo maxOf(0, best - 2 * (best - from) - 2)).firstOrNull { !v[it].isNaN() }
        else (best + 1 until minOf(v.size, best + 2 * (from - best) + 2)).firstOrNull { !v[it].isNaN() }
        if (other != null && kotlin.math.abs(t[other] - at) < kotlin.math.abs(t[best] - at)) best = other
    }
    return best
}

/** Three values on the left of a lane, at the top, middle and bottom grid lines. */
private fun DrawScope.drawScale(lo: Float, hi: Float, gutter: Float) {
    if (lo.isNaN()) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 0x8E, 0x95, 0xAB); textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.RIGHT
    }
    val h = size.height
    for ((frac, v) in listOf(0.25f to hi - (hi - lo) * 0.25f, 0.5f to (lo + hi) / 2, 0.75f to lo + (hi - lo) * 0.25f)) {
        drawContext.canvas.nativeCanvas.drawText(num(v), gutter - 6.dp.toPx(), h * frac + 4.dp.toPx(), paint)
    }
}

/** Times under the charts: clock time when the file has it, else time from the start. */
@Composable
private fun TimeAxis(w0: Double, w1: Double, startMs: Long?) {
    val fmt = SimpleDateFormat(if (w1 - w0 < 600) "HH:mm:ss" else "HH:mm", Locale.US)
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val left = (LANE_PAD + GUTTER).toPx(); val right = LANE_PAD.toPx()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(200, 0x8E, 0x95, 0xAB); textSize = 10.dp.toPx()
        }
        for (k in 0..4) {
            val tt = w0 + (w1 - w0) * k / 4
            val label = startMs?.let { fmt.format(Date(it + (tt * 1000).toLong())) } ?: clock(tt)
            paint.textAlign = when (k) { 0 -> android.graphics.Paint.Align.LEFT; 4 -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.CENTER }
            drawContext.canvas.nativeCanvas.drawText(kg.forester.sg.data.tr(label), left + (size.width - left - right) * k / 4, size.height - 2.dp.toPx(), paint)
        }
    }
}

/** Where the cursor is in the whole file (0…1): the zoomed-in window is centred there. */
private fun windowAt(centre: Float, zoom: Float, cursor: Float): Float {
    val half = 0.5f / zoom
    val c = centre.coerceIn(half, 1f - half)
    return c - half + cursor * 2 * half
}

private fun lowerBound(t: DoubleArray, x: Double): Int {
    var lo = 0; var hi = t.size
    while (lo < hi) { val m = (lo + hi) ushr 1; if (t[m] < x) lo = m + 1 else hi = m }
    return lo
}

/** min, mean, max of the numbers in [i0, i1). */
private fun stats(v: FloatArray, i0: Int, i1: Int): FloatArray {
    var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY; var sum = 0.0; var k = 0
    for (i in i0 until i1.coerceAtMost(v.size)) {
        val x = v[i]
        if (x.isNaN()) continue
        if (x < mn) mn = x
        if (x > mx) mx = x
        sum += x; k++
    }
    return if (k == 0) floatArrayOf(Float.NaN, Float.NaN, Float.NaN) else floatArrayOf(mn, (sum / k).toFloat(), mx)
}

/** One line per two pixels (the low and high of the samples there), broken where the log has a gap. */
private fun DrawScope.drawSeries(
    t: DoubleArray, v: FloatArray, i0: Int, i1: Int, w0: Double, w1: Double,
    min: Float, max: Float, color: Color, fill: Boolean,
) {
    val w = size.width; val h = size.height
    for (k in 1..3) drawLine(C.Border.copy(alpha = 0.5f), Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
    if (min.isNaN()) return
    val span = (max - min).takeIf { it > 0f } ?: 1f
    fun y(x: Float) = h - (x - min) / span * h
    val path = Path()
    var started = false
    var bucket = -1; var lo = 0f; var hi = 0f; var lastT = Double.NaN
    var firstX = 0f; var lastX = 0f
    fun flush() {
        if (bucket < 0) return
        val x = bucket * 2f
        if (!started) { path.moveTo(x, y(lo)); started = true; firstX = x } else path.lineTo(x, y(lo))
        if (hi != lo) path.lineTo(x, y(hi))
        lastX = x
    }
    for (i in i0 until i1) {
        val x = v[i]
        if (x.isNaN() || (!lastT.isNaN() && t[i] - lastT > 5.0)) { flush(); bucket = -1; started = false; if (x.isNaN()) continue }
        lastT = t[i]
        val b = (((t[i] - w0) / (w1 - w0)) * w / 2).toInt()
        if (b != bucket) { flush(); bucket = b; lo = x; hi = x } else { if (x < lo) lo = x; if (x > hi) hi = x }
    }
    flush()
    // sparse samples (the battery journal: minutes apart) would be only gaps: draw each one as a dot
    var n = 0
    for (i in i0 until i1) if (!v[i].isNaN()) n++
    val sparse = n > 0 && (t[(i1 - 1).coerceAtLeast(i0)] - t[i0]) / n > 5.0
    if (sparse) {
        // a thin line through the records shows the trend, a dot marks each record
        val trend = Path()
        var first = true
        for (i in i0 until i1) {
            if (v[i].isNaN()) continue
            val p = Offset(((t[i] - w0) / (w1 - w0) * w).toFloat(), y(v[i]))
            if (first) { trend.moveTo(p.x, p.y); first = false } else trend.lineTo(p.x, p.y)
        }
        drawPath(trend, color.copy(alpha = 0.45f), style = Stroke(1.5f))
        for (i in i0 until i1) {
            if (v[i].isNaN()) continue
            drawCircle(color, 3.5f, Offset(((t[i] - w0) / (w1 - w0) * w).toFloat(), y(v[i])))
        }
    }
    if (fill && started && !sparse) {
        val area = Path().apply { addPath(path); lineTo(lastX, h); lineTo(firstX, h); close() }
        drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
    }
    drawPath(path, color, style = Stroke(2.5f))
}

/** The log's parameters by group, a chip each; a picked one in the colour of its chart. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeriesGroups(series: List<LogSeries>, picked: List<String>, onToggle: (String) -> Unit) {
    series.groupBy { it.group }.forEach { (g, list) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(groupIcon(g), null, tint = C.Violet, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            T(g, 13.sp, C.Sub, weight = FontWeight.Medium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { s ->
                val i = picked.indexOf(s.key)
                SeriesChip(s.title, if (i >= 0) COLORS[i] else null) { onToggle(s.key) }
            }
        }
    }
}

/** All the parameters on the width of the screen, where most logs fit without scrolling. */
@Composable
private fun SeriesDialog(series: List<LogSeries>, picked: List<String>, onToggle: (String) -> Unit, onClose: () -> Unit) {
    CarAlertDialog(
        onDismissRequest = onClose, textScrolls = false,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.ChartLine, null, tint = C.Violet, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                T("Параметры", 20.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("на графике ${picked.size} из 4", 13.sp, if (picked.size >= 4) C.Orange else C.Sub)
            }
        },
        text = {
            Column(Modifier.cueScroll(C.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Нажмите, чтобы показать или убрать. Сразу видно до четырёх графиков; пятый заменит первый.", 13.sp, C.Sub)
                SeriesGroups(series, picked, onToggle)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClose) { Text("Готово", color = C.Violet) } },
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
    )
}

/** A button over the bottom of a list that has more below: a tap scrolls on. */
@Composable
private fun MoreBelow(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier.padding(bottom = 2.dp).heightIn(min = LocalMinTap.current).clip(shape).background(C.Card)
            .border(1.dp, C.Violet, shape).tapClickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("ещё ниже", 13.sp, C.Text, FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.ChevronsDown, null, tint = C.Violet, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SeriesChip(text: String, color: Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        // a finger's target on a car screen: 48 dp high at least (it was about 30)
        Modifier.heightIn(min = LocalMinTap.current).clip(shape).background(if (color != null) color.copy(alpha = 0.18f) else C.CardHi)
            .border(1.dp, color ?: C.Border, shape).tapClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)) }
        T(text, 13.sp, if (color != null) C.Text else C.Sub)
    }
}
