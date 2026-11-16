package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.Filter
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import kg.forester.sg.data.CanChannel
import kg.forester.sg.data.CanChannels
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.CanBitChannel
import kg.forester.sg.ssm.CanBitrates
import kg.forester.sg.ssm.CanCapture
import kg.forester.sg.ssm.CanLogFormat
import kg.forester.sg.ssm.CanLogs
import kg.forester.sg.ssm.CanMonitor
import kg.forester.sg.ssm.CanRecording
import kg.forester.sg.ssm.CanReplay
import kg.forester.sg.ssm.CanSniff
import kg.forester.sg.ssm.LinkDash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Что идёт по CAN», the sniffer: every id with its bytes, a changed byte lit up and fading out (as SavvyCAN does),
 * «Только меняющиеся», «Нажмите кнопку» (what answers a button: see [CanSniff.ChangeCapture]), rates and the bus
 * load, recording to a log and playing one back. A row opens the bit viewer, where a run of bits becomes an own value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanSnifferDialog(onClose: () -> Unit) {
    val rows by CanMonitor.rows.collectAsState()
    val replay by CanReplay.state.collectAsState()
    val rec by CanRecording.recorder.collectAsState()
    val capture by CanCapture.state.collectAsState()
    var onlyChanging by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(if (capture != null) "capture" else "") }
    var bits by remember { mutableStateOf<Pair<Long, Set<Int>>?>(null) }
    var start by remember { mutableStateOf<CanChannel?>(null) }
    val ctx = LocalContext.current
    // the fades and the countdowns move on with no frame coming too
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }

    start?.let { c -> CanChannelsDialog(c) { start = null }; return }
    bits?.let { (k, marks) -> CanBitsDialog(k, marks, { bits = null }) { start = it; bits = null }; return }

    CarSheet(onClose, 900.dp, 640.dp, head = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Что идёт по CAN", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (replay != null) SnifBadge("Воспроизведение", C.Violet)
            if (rec != null) SnifBadge("Запись", C.Red)
            Spacer(Modifier.width(8.dp))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Только меняющиеся", onlyChanging, { onlyChanging = !onlyChanging }, Lucide.Filter)
            Chip("Нажмите кнопку", panel == "capture", { panel = if (panel == "capture") "" else "capture" }, Lucide.Hand)
            if (rec == null) Chip("Записать лог", false, { CanRecording.start(CanRecording.dir(ctx)) }, Lucide.Circle)
            else Chip("Стоп записи", true, { CanRecording.stop(); panel = "logs" }, Lucide.Square)
            Chip("Логи и воспроизведение", panel == "logs", { panel = if (panel == "logs") "" else "logs" }, Lucide.FileDown)
        }
        Spacer(Modifier.height(6.dp))
    }) {
        replay?.let { p ->
            SnifBanner(C.Violet, "Воспроизведение лога «${p.name}»: кадр ${p.pos} из ${p.frames}" + (if (p.round > 1) ", круг ${p.round}" else "") +
                ". Значения ниже из файла, не из машины", "Стоп") { CanReplay.stop() }
        }
        rec?.let { r -> T("Идёт запись: ${r.frames} кадров, ${(now - r.startMs) / 1000} с · ${r.file.name}", 13.sp, C.Red) }
        val kbit = if (Repo.settings.adapter == "canable" || CanReplay.active) Repo.settings.canKbit else 500
        val fps = rows.filter { now - it.lastMs < 2000 }.sumOf { it.hz.toDouble() }
        val load = CanMonitor.busLoad(kbit, now)
        val link = LinkDash.id
        T(listOfNotNull(
            "${rows.size} ID",
            "%.0f кадров/с".format(fps),
            if (rows.isNotEmpty()) "загрузка шины ≈ %.0f %% при %s".format(load * 100, CanBitrates.label(kbit)) else null,
            link?.let { "поток Link ${LinkDash.format?.title} на ID $it (0x%03X)".format(it) },
        ).joinToString(" · "), 13.sp, if (load > 0.7f) C.Orange else C.Sub)
        Spacer(Modifier.height(6.dp))
        when (panel) {
            "capture" -> CapturePanel(now, rows) { k, m -> bits = k to m }
            "logs" -> LogsPanel()
        }
        T("Нажмите на строку: биты кадра, из них своё значение. Подсвечены байты, которые только что изменились", 12.sp, C.Sub)
        Spacer(Modifier.height(6.dp))
        val shown = if (onlyChanging) rows.filter { it.changedMs > 0 && now - it.changedMs < 5000 } else rows
        if (rows.isEmpty()) T("Кадров нет. CAN-адаптер подключён, выбран в настройках (или Авто для CANable), скорость шины верная, зажигание включено? " +
            "Без машины: «Логи и воспроизведение», откройте лог", 14.sp, C.Sub)
        else if (shown.isEmpty()) T("Сейчас ничего не меняется", 14.sp, C.Sub)
        Column {
            shown.forEach { r -> SnifferRow(r, now) { bits = r.key to emptySet() } }
        }
    }
}

@Composable
private fun SnifBadge(text: String, color: Color) {
    Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.2f))
        .border(1.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        T(text, 13.sp, color, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SnifBanner(color: Color, text: String, action: String?, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 14.sp, modifier = Modifier.weight(1f))
        if (action != null) { Spacer(Modifier.width(8.dp)); Chip(action, false, onAction) }
    }
}

/** One id: its bytes, each lit while it just changed and fading out; the rate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnifferRow(r: CanMonitor.Row, now: Long, onClick: () -> Unit) {
    val stale = now - r.lastMs > 1500
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(C.CardHi)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.idText, Modifier.width(150.dp), color = if (stale) C.Sub else C.Violet, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            r.data.forEachIndexed { i, b ->
                val f = CanSniff.fade(r.byteMs(i), now)
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.Orange.copy(alpha = 0.65f * f)).padding(horizontal = 3.dp, vertical = 1.dp)) {
                    Text("%02X".format(b), color = if (f > 0.3f) C.Text else C.Text.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                }
            }
        }
        T(if (r.hz >= 10f) "%.0f Гц".format(r.hz) else "%.1f Гц".format(r.hz), 12.sp, C.Sub, modifier = Modifier.width(64.dp))
        T("${r.count}", 12.sp, C.Sub, modifier = Modifier.width(64.dp), maxLines = 1)
    }
}

/** Bits as "байт 2: 5, 7 · байт 3: 0–7". */
private fun bitsText(bits: List<Int>): String = bits.groupBy { it / 8 }.toSortedMap().entries.joinToString(" · ") { (byte, l) ->
    val s = l.map { it % 8 }.sorted()
    val runs = ArrayList<String>()
    var a = s.first(); var p = a
    for (x in s.drop(1)) { if (x == p + 1) { p = x; continue }; runs += if (a == p) "$a" else "$a–$p"; a = x; p = x }
    runs += if (a == p) "$a" else "$a–$p"
    "байт $byte: " + runs.joinToString(", ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapturePanel(now: Long, rows: List<CanMonitor.Row>, open: (Long, Set<Int>) -> Unit) {
    val cap by CanCapture.state.collectAsState()
    var base by remember { mutableStateOf(5) }
    var act by remember { mutableStateOf(5) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.Card)
        .border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        val c = cap
        when (c?.phase(now)) {
            null -> {
                T("Какой ID и бит у кнопки, рычага, двери", 15.sp, weight = FontWeight.SemiBold)
                T("Сначала ничего не трогайте: приложение запомнит всё, что меняется само (счётчики, обороты). Потом нажимайте " +
                    "кнопку: останутся только биты, которые сдвинулись от неё", 12.sp, C.Sub)
                Spacer(Modifier.height(6.dp))
                Labeled("Ничего не трогать") { listOf(3, 5, 10).forEach { n -> Chip("$n с", base == n, { base = n }) } }
                Labeled("Нажимать кнопку") { listOf(3, 5, 10).forEach { n -> Chip("$n с", act == n, { act = n }) } }
                Spacer(Modifier.height(6.dp))
                BigButton("Начать", Lucide.Play, { CanCapture.start(base * 1000L, act * 1000L) }, enabled = rows.isNotEmpty())
            }
            CanSniff.ChangeCapture.Phase.BASELINE -> {
                T("Ничего не трогайте… ${c.left(now)} с", 22.sp, C.Text, FontWeight.Bold)
                T("Запоминаю, что меняется само", 13.sp, C.Sub)
                Spacer(Modifier.height(6.dp))
                Chip("Отмена", false, { CanCapture.stop() })
            }
            CanSniff.ChangeCapture.Phase.ACTION -> {
                T("Нажмите кнопку сейчас! ${c.left(now)} с", 24.sp, C.Orange, FontWeight.Bold)
                T("Нажимайте несколько раз, держите, отпускайте", 13.sp, C.Sub)
                Spacer(Modifier.height(6.dp))
                Chip("Отмена", false, { CanCapture.stop() })
            }
            CanSniff.ChangeCapture.Phase.DONE -> {
                val hits = remember(c) { c.result() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(if (hits.isEmpty()) "Ничего не сдвинулось только от кнопки" else "От кнопки изменилось: ${hits.size}", 15.sp,
                        weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Chip("Ещё раз", false, { CanCapture.start(base * 1000L, act * 1000L) })
                    Spacer(Modifier.width(6.dp))
                    Chip("Закрыть", false, { CanCapture.stop() })
                }
                if (hits.isEmpty()) T("Кнопка на другой шине, или её кадр идёт только при нажатии реже, чем окно. Попробуйте окно подольше", 12.sp, C.Sub)
                hits.take(30).forEach { h ->
                    val (id, ext, mux) = CanCapture.unkey(h.key)
                    val idText = (if (ext) "%08X" else "%03X").format(id) + (mux?.let { " #$it" } ?: "")
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                            .clickable { open(h.key, h.bits.toSet()) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(idText, Modifier.width(150.dp), color = C.Violet, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                        T(if (h.newId) "новый ID: появился только с кнопкой" else bitsText(h.bits), 14.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

// ---------------------------------------------------------------- the bit viewer

/**
 * One id's frame bit by bit, live: a bit that just changed glows, [marks] (from «Нажмите кнопку») are framed. Two taps
 * pick a run of bits; it shows as a number and becomes an own value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanBitsDialog(key: Long, marks: Set<Int>, onClose: () -> Unit, onChannel: (CanChannel) -> Unit) {
    val rows by CanMonitor.rows.collectAsState()
    var last by remember { mutableStateOf<CanMonitor.Row?>(null) }
    val live = rows.firstOrNull { it.key == key }
    if (live != null && live !== last) last = live
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
    var be by remember { mutableStateOf(false) }
    var a by remember { mutableStateOf<Int?>(null) }
    var b by remember { mutableStateOf<Int?>(null) }
    val (id, ext, mux) = CanCapture.unkey(key)
    val idText = (if (ext) "%08X" else "%03X").format(id) + (mux?.let { " #$it" } ?: "")
    val sel = a?.let { x -> CanSniff.select(x, b ?: x, be) }
    val selBits = sel?.bits()?.toSet().orEmpty()
    CarSheet(onClose, 860.dp, 640.dp, head = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Биты ID $idText", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Назад", true, onClose)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Intel (младший первым)", !be, { be = false })
            Chip("Motorola (старший первым)", be, { be = true })
            if (a != null) Chip("Сбросить выбор", false, { a = null; b = null })
        }
        Spacer(Modifier.height(6.dp))
    }) {
        val r = last
        if (r == null) { T("Кадров с этим ID сейчас нет", 14.sp, C.Sub); return@CarSheet }
        T("Нажмите на первый и последний бит значения. Биты считаются как в DBC: бит 0 младший в байте 0" +
            if (marks.isNotEmpty()) ". В рамке: что изменилось от кнопки" else "", 12.sp, C.Sub)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(76.dp))
            (7 downTo 0).forEach { T("$it", 12.sp, C.Sub, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        r.data.forEachIndexed { i, byte ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                T("байт $i", 13.sp, C.Sub, modifier = Modifier.width(76.dp))
                for (bit in 7 downTo 0) {
                    val n = i * 8 + bit
                    val v = (byte.toInt() shr bit) and 1
                    val f = CanSniff.fade(r.bitMs.getOrElse(n) { 0L }, now)
                    val picked = n in selBits
                    Box(
                        Modifier.padding(horizontal = 2.dp).size(40.dp).clip(RoundedCornerShape(6.dp))
                            .background(when { picked -> C.Purple; f > 0f -> C.Orange.copy(alpha = 0.25f + 0.5f * f); else -> C.CardHi })
                            .border(if (n in marks) 2.dp else 1.dp, if (n in marks) C.Orange else C.Border, RoundedCornerShape(6.dp))
                            .clickable {
                                when {
                                    a == null || b != null -> { a = n; b = null }
                                    else -> b = n
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { T("$v", 16.sp, if (picked) Color.White else if (v == 1) C.Text else C.Sub, if (v == 1) FontWeight.Bold else FontWeight.Normal) }
                }
                Spacer(Modifier.width(10.dp))
                Text("%02X".format(byte), color = C.Text, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                T("  ${byte.toInt() and 0xFF}", 13.sp, C.Sub)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (sel == null) { T("Ничего не выбрано", 14.sp, C.Sub); return@CarSheet }
        val raw = sel.raw(r.data)
        T("Стартовый бит ${sel.start}, длина ${sel.length}, ${if (be) "Motorola" else "Intel"}: " +
            (raw?.let { "x = $it (0x%X), со знаком ${sel.signed(r.data)}".format(it) } ?: "за пределами кадра"), 15.sp)
        Spacer(Modifier.height(6.dp))
        // whole bytes in the byte model, any other run as a bit-level signal (start bit, length, byte order): the form opens with it
        BigButton("Сделать своим значением", Lucide.Play, {
            onChannel(CanBitChannel.channel(CanBitChannel.Request(id, ext, mux, sel), CanChannels.newId()))
        })
    }
}

// ---------------------------------------------------------------- logs: record, share, open, replay

/** A file's name as the picker shows it. */
private fun pickedName(ctx: Context, uri: android.net.Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: "log"

/** [f] as [fmt] in the shared cache folder (converted when it is the other one); off the main thread. */
private fun shareCopy(ctx: Context, f: File, fmt: CanLogFormat): File {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val out = File(dir, f.nameWithoutExtension + "." + fmt.ext)
    val have = runCatching { f.bufferedReader().use { r -> CanLogs.detect(List(10) { r.readLine() ?: "" }) } }.getOrNull()
    if (have == fmt) f.copyTo(out, overwrite = true) else CanLogs.convert(f, fmt, out)
    return out
}

/** [out] to another app through the share sheet. */
private fun shareCanLog(ctx: Context, out: File, fmt: CanLogFormat) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(fmt.mime)
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), kg.forester.sg.data.tr("Отправить лог CAN"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun LogsPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { CanRecording.dir(ctx) }
    var version by remember { mutableStateOf(0) }
    val files = remember(version) { CanRecording.files(dir) }
    val rec by CanRecording.recorder.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    fun share(f: File, fmt: CanLogFormat) = scope.launch {
        runCatching { shareCanLog(ctx, withContext(Dispatchers.IO) { shareCopy(ctx, f, fmt) }, fmt) }
            .onFailure { msg = "Не удалось отправить: ${kg.forester.sg.data.Failure.text(it)}" }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = pickedName(ctx, uri)
        msg = "Читаю $name…"
        scope.launch {
            msg = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(dir, "import-tmp")
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val sum = CanLogs.summary(tmp)
                    if (sum.format == null || sum.frames == 0) { tmp.delete(); "В «$name» нет кадров CAN в формате SavvyCAN (CSV) или candump" }
                    else {
                        val dst = File(dir, "import-" + name.substringBeforeLast('.').replace(Regex("[^\\w.-]"), "_") + "." + sum.format.ext)
                        tmp.renameTo(dst)
                        CanReplay.start(dst, name)
                        "«$name»: ${sum.frames} кадров, ${sum.ids} ID, ${sum.durationMs / 1000} с. Воспроизводится"
                    }
                }.getOrElse { "Не удалось прочитать: ${kg.forester.sg.data.Failure.text(it)}" }
            }
            version++
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.Card)
        .border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Логи CAN", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Открыть лог", false, { pick.launch(arrayOf("*/*")) }, Lucide.FileDown)
        }
        T("Запись идёт в CSV SavvyCAN; отправить можно и как candump. Открытый лог (SavvyCAN CSV или candump) воспроизводится " +
            "сюда и в свои значения: удобно разбираться без машины", 12.sp, C.Sub)
        msg?.let { T(it, 13.sp, C.Text) }
        Spacer(Modifier.height(4.dp))
        if (files.isEmpty()) T("Логов пока нет", 13.sp, C.Sub)
        files.take(20).forEach { f ->
            val recording = rec?.file == f
            var ask by remember(f) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(f.name, 14.sp, maxLines = 1)
                    T(fileSize(f.length()) + if (recording) " · пишется" else "", 12.sp, C.Sub)
                }
                if (!recording) {
                    IconTap(Lucide.Play, "Воспроизвести", { CanReplay.start(f) }, tint = C.Violet, iconSize = 20.dp)
                    Chip("CSV", false, { share(f, CanLogFormat.GVRET_CSV) }, Lucide.Share2)
                    Spacer(Modifier.width(4.dp))
                    Chip("candump", false, { share(f, CanLogFormat.CANDUMP) }, Lucide.Share2)
                    IconTap(Lucide.Trash2, "Удалить", { ask = true }, tint = C.Red, iconSize = 20.dp)
                }
            }
            if (ask) ConfirmDialog("Удалить лог?", "«${f.name}» удалится с магнитолы.", "Удалить", { f.delete(); version++ }, { ask = false })
        }
    }
    Spacer(Modifier.height(6.dp))
}
