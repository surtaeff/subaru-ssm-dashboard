package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import kg.forester.sg.data.Repo
import kg.forester.sg.service.FmRadio
import kg.forester.sg.service.Fyt
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.RadioLogos
import kg.forester.sg.service.RadioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs

/** Made-up tuner for the demo mode, so the FM side can be seen without an FYT unit. */
private object DemoRadio {
    val presets = listOf(10170, 10280, 10340, 10480, 10530, 10620)
    val names = listOf("Европа Плюс", "Русское Радио", "Ретро FM", "Авторадио", "DFM", "Радио Рекорд")
    val state = MutableStateFlow(RadioState(band = 65536, freq = 10170, preset = 1, name = names[0]))
    fun set(freq: Int) {
        val i = presets.indexOf(freq)
        state.value = state.value.copy(freq = freq, preset = if (i >= 0) i + 1 else 0, name = names.getOrElse(i) { "" })
    }
}

private fun fmText(f: Int) = String.format(Locale.US, "%.2f", f / 100.0)

/** The logo of a station, loaded once and kept in the app's files. */
@Composable
private fun rememberLogo(url: String?): Bitmap? {
    val ctx = LocalContext.current
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { if (!url.isNullOrBlank()) bmp = withContext(Dispatchers.IO) { RadioLogos.bitmap(ctx, url) } }
    return bmp
}

/**
 * The radio's state shared by the player and its panel: FM (the FYT tuner, or the demo one) or internet radio
 * (always there; the only radio on a unit without a tuner), the country, the stations named on frequencies.
 */
internal class RadioUi(val fyt: Boolean, internet: Boolean, cc: String, named: Map<String, RadioLogos.Station>) {
    var internet by mutableStateOf(internet)
    var cc by mutableStateOf(cc)
    var named by mutableStateOf(named)
    var picking by mutableStateOf(false)
    var preview by mutableStateOf<Int?>(null)
    var fmLogo by mutableStateOf<Bitmap?>(null)
    var netLogo by mutableStateOf<Bitmap?>(null)
    /** The driver's own stations (see [FmRadio]: ours, tuned by frequency, not the stock radio's slots). */
    val mine: List<FmRadio.Station> get() = FmRadio.mine
    val tuning get() = fyt && FmRadio.tuning

    fun addMine(freq: Int, name: String, band: Int) = FmRadio.add(freq, name, band)
    fun removeMine(freq: Int) = FmRadio.remove(freq)

    /** To [target]: exactly in the demo; on FYT by frequency (see [FmRadio.tune]). */
    fun tune(target: Int) { if (fyt) FmRadio.tune(target) else DemoRadio.set(target) }

    /** To the tuner's preset [p] (1..6) by its slot. */
    fun toPreset(p: Int) { if (fyt) FmRadio.toPreset(p) else DemoRadio.set(DemoRadio.presets[p - 1]) }

    /** ⏮ ⏭: through the own stations when there are any, else the tuner's presets. */
    fun step(cur: Int, next: Boolean, tunerPreset: (Boolean) -> Unit) {
        val s = FmRadio.neighbour(cur, next) ?: run { tunerPreset(next); return }
        tune(s.freq)
    }

    val demoFm get() = !fyt && Repo.settings.demo
    /** FM is there on an FYT unit (and in the demo). */
    val hasFm get() = fyt || demoFm
    fun coverBitmap(): Bitmap? = if (internet) netLogo else fmLogo
}

@Composable
internal fun rememberRadioUi(fyt: Boolean): RadioUi {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("radio", Context.MODE_PRIVATE) }
    val ui = remember {
        val cc = RadioLogos.country(ctx)
        RadioUi(fyt, sp.getBoolean("internet", !(fyt || Repo.settings.demo)) || !(fyt || Repo.settings.demo), cc, RadioLogos.allNamed(ctx, cc))
            .also { FmRadio.load(ctx) }
    }
    val real by Fyt.radio.collectAsState()
    val fake by DemoRadio.state.collectAsState()
    val r = if (fyt) real else fake
    // the station's name from the tuner (RDS, the unit's own list) or a preset: find its logo and remember it
    LaunchedEffect(r.name, r.freq, ui.cc) {
        if (r.freq > 0 && ui.named[r.freqText] == null) {
            // by the tuner's name, else a catalogue station of the country that has this frequency in its name
            withContext(Dispatchers.IO) {
                (if (r.name.isNotBlank()) RadioLogos.find(ctx, ui.cc, r.name) else null) ?: RadioLogos.byFrequency(ctx, ui.cc, r.freqText)
            }?.let { st -> RadioLogos.setNamed(ctx, ui.cc, r.freqText, st); ui.named = RadioLogos.allNamed(ctx, ui.cc) }
        }
    }
    val fmUrl = ui.named[r.freqText]?.logo
    LaunchedEffect(fmUrl, r.logo) { ui.fmLogo = r.logo ?: fmUrl?.let { withContext(Dispatchers.IO) { RadioLogos.bitmap(ctx, it) } } }
    val st by LocalMusic.state.collectAsState()
    LaunchedEffect(st.artUrl, st.stream) { ui.netLogo = if (st.stream && st.artUrl.isNotEmpty()) withContext(Dispatchers.IO) { RadioLogos.bitmap(ctx, st.artUrl) } else null }
    LaunchedEffect(ui.internet) { sp.edit().putBoolean("internet", ui.internet).apply() }
    return ui
}

@Composable
internal fun RadioHero(ui: RadioUi) = if (ui.internet) NetHero(ui) else FmHero(ui)

@Composable
internal fun RadioPanel(ui: RadioUi) = if (ui.internet) NetPanel(ui) else FmPanel(ui)

/** FM or internet, when the unit has both. */
@Composable
private fun ColumnScope.RadioKind(ui: RadioUi) {
    if (!ui.hasFm) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("FM", Lucide.Radio, !ui.internet, 38.dp) {
            if (ui.internet) { ui.internet = false; LocalMusic.pause(); if (ui.fyt) Fyt.ensureRadio() }
        }
        Pill("Интернет", Lucide.Globe, ui.internet, 38.dp) { ui.internet = true }
    }
    Spacer(Modifier.height(14.dp))
}

// --- FM: the FYT tuner --------------------------------------------------------------------------------

/**
 * The tuner, as launchers for these units draw it: the band, the frequency large with the station's name and
 * logo (tap it to say which station it is), a scale you can drag with a finger (the tuner then seeks to the
 * station nearest to where it was let go; tap a mark to go to a named station), seek and preset buttons.
 */
@Composable
private fun FmHero(ui: RadioUi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val real by Fyt.radio.collectAsState()
    val fake by DemoRadio.state.collectAsState()
    val app by Fyt.appId.collectAsState()
    val fyt = ui.fyt
    val r = if (fyt) real else fake
    val on = !fyt || app == 1
    val fm = r.isFm || r.band == -1
    val station = ui.named[r.freqText]
    fun seek(up: Boolean) = if (fyt) Fyt.radioSeek(up) else DemoRadio.set((r.freq + if (up) 30 else -30).coerceIn(8750, 10800))
    fun preset(next: Boolean) = if (fyt) Fyt.radioPreset(next) else {
        val p = ((r.preset.coerceAtLeast(1) - 1 + if (next) 1 else -1) + 6) % 6
        DemoRadio.set(DemoRadio.presets[p])
    }
    fun band(b: Int) = if (fyt) Fyt.radioBand(b) else DemoRadio.state.value = r.copy(band = b)
    Hero(
        cover = { m -> Box(m.clip(RoundedCornerShape(22.dp)).clickable { ui.picking = true }) { GlowCover(ui.fmLogo, Lucide.Radio, on, Modifier.fillMaxSize(), whiteBack = true) } },
        onNext = { ui.step(r.freq, true, ::preset) }, onPrev = { ui.step(r.freq, false, ::preset) },
        info = {
            RadioKind(ui)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(65536 to "FM1", 65537 to "FM2", 65538 to "FM3", 0 to "AM").forEach { (b, t) ->
                    Pill(t, null, r.band == b || (b == 0 && r.band == 1), 34.dp) { if (r.band != b) band(b) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val p = ui.preview
            Row(verticalAlignment = Alignment.Bottom) {
                T(if (p != null) (if (fm) fmText(p) else "$p") else r.freqText, 58.sp, if (p != null) C.Violet else C.Text, FontWeight.Bold, maxLines = 1)
                T(if (fm) " МГц" else " кГц", 18.sp, C.Sub, modifier = Modifier.padding(bottom = 11.dp))
            }
            val previewName = p?.let { pv -> ui.named.entries.firstOrNull { abs((it.key.toDoubleOrNull() ?: 0.0) * 100 - pv) < 16 }?.value?.name }
            T(when {
                p != null -> previewName ?: "отпустите, найду станцию рядом"
                ui.tuning -> "ищу станцию…"
                else -> r.name.ifBlank { station?.name ?: if (on) "нажмите на логотип, чтобы указать станцию" else "Радио выключено" }
            }, 19.sp, if (r.name.isBlank() && station == null && p == null) C.Sub else C.Text.copy(alpha = 0.8f), FontWeight.Medium, maxLines = 1)
            if (r.preset > 0) { Spacer(Modifier.height(4.dp)); T("${r.bandText} · пресет ${r.preset}", 13.sp, C.Sub) }
        },
        middle = { Dial(r, (ui.named.keys.mapNotNull { k -> k.toDoubleOrNull()?.let { (it * 100).toInt() } } + ui.mine.map { it.freq }).distinct(), { ui.preview = it }, ui::tune) },
        controls = {
            Control(Lucide.SkipBack, 60.dp) { ui.step(r.freq, false, ::preset) }
            Control(Lucide.ChevronsLeft, 68.dp) { seek(false) }
            Control(if (on) Lucide.Radio else Lucide.Power, 96.dp, primary = true) { if (fyt) Fyt.ensureRadio() }
            Control(Lucide.ChevronsRight, 68.dp) { seek(true) }
            Control(Lucide.SkipForward, 60.dp) { ui.step(r.freq, true, ::preset) }
        },
    )
    if (ui.picking) StationPicker(r.freqText, if (fm) "МГц" else "кГц", ui.cc, station != null,
        onCountry = { ui.cc = it; RadioLogos.setCountry(ctx, it); ui.named = RadioLogos.allNamed(ctx, it) },
        onPick = { st -> RadioLogos.setNamed(ctx, ui.cc, r.freqText, st); ui.named = RadioLogos.allNamed(ctx, ui.cc); ui.picking = false },
        onClose = { ui.picking = false })
}

/**
 * The scale (FM 87.5–108, AM 522–1620 kHz) with the station's place and marks of the named stations. Drag along
 * it: the big number follows the finger, letting go tunes there; a tap goes to the nearest mark.
 */
@Composable
private fun Dial(r: RadioState, marks: List<Int>, onPreview: (Int?) -> Unit, onTune: (Int) -> Unit) {
    val fm = r.isFm || r.band == -1
    val lo = if (fm) 8750f else 522f
    val hi = if (fm) 10800f else 1620f
    val step = if (fm) 10 else 9
    var drag by remember { mutableStateOf<Int?>(null) }
    val at = (((drag ?: r.freq) - lo) / (hi - lo)).coerceIn(0f, 1f)
    val violet = C.Violet
    val sub = C.Sub
    val faint = Color.White.copy(alpha = 0.16f)
    val mark = C.theme.second
    fun freqAt(x: Float, w: Float): Int {
        val f = lo + (hi - lo) * (x / w).coerceIn(0f, 1f)
        return ((Math.round((f - lo) / step) * step) + lo).toInt()
    }
    Canvas(
        Modifier.fillMaxWidth().height(78.dp)
            .pointerInput(fm, marks) {
                detectTapGestures { o ->
                    val f = freqAt(o.x, size.width.toFloat())
                    // a mark within a finger's width wins
                    val near = marks.filter { abs(it - f) < (hi - lo) * 0.03f }.minByOrNull { abs(it - f) }
                    onTune(near ?: f)
                }
            }
            .pointerInput(fm) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = freqAt(o.x, size.width.toFloat()); onPreview(drag) },
                    onDragEnd = { drag?.let(onTune); drag = null; onPreview(null) },
                    onDragCancel = { drag = null; onPreview(null) },
                ) { change, _ -> drag = freqAt(change.position.x, size.width.toFloat()); onPreview(drag) }
            },
    ) {
        val h = size.height
        val steps = 41
        val p = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(170, 160, 167, 190); textSize = 22f; isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        for (i in 0..steps) {
            val x = size.width * i / steps
            val major = i % 4 == 1
            drawLine(if (major) sub else faint, Offset(x, h * 0.66f), Offset(x, if (major) h * 0.30f else h * 0.48f), strokeWidth = if (major) 3f else 2f)
            if (major) drawIntoCanvas { c ->
                val v = lo + (hi - lo) * i / steps
                c.nativeCanvas.drawText(if (fm) "%.0f".format(v / 100) else "%.0f".format(v), x, h - 2f, p)
            }
        }
        marks.forEach { m ->
            if (m < lo || m > hi) return@forEach
            drawCircle(mark, 5f, Offset(size.width * (m - lo) / (hi - lo), h * 0.18f))
        }
        val x = size.width * at
        drawLine(Brush.verticalGradient(listOf(violet, violet.copy(alpha = 0.15f))), Offset(x, h * 0.08f), Offset(x, h * 0.76f), strokeWidth = 7f, cap = StrokeCap.Round)
        drawCircle(violet, if (drag != null) 13f else 9f, Offset(x, h * 0.08f))
    }
}

@Composable
private fun FmPanel(ui: RadioUi) {
    val ctx = LocalContext.current
    val real by Fyt.radio.collectAsState()
    val fake by DemoRadio.state.collectAsState()
    val fyt = ui.fyt
    val r = if (fyt) real else fake
    // the tuner tells only the preset that plays: remember what each one holds as it comes by
    val sp = remember { ctx.getSharedPreferences("radio", Context.MODE_PRIVATE) }
    var known by remember { mutableStateOf(
        (1..6).associateWith { p -> if (fyt) sp.getString("p$p", null) else fmText(DemoRadio.presets[p - 1]) + "|" + DemoRadio.names[p - 1] }
    ) }
    LaunchedEffect(r.preset, r.freq, r.name) {
        if (fyt && r.preset in 1..6 && r.freq > 0) {
            val v = r.freqText + "|" + r.name.ifBlank { ui.named[r.freqText]?.name.orEmpty() }
            sp.edit().putString("p${r.preset}", v).apply()
            known = known + (r.preset to v)
        }
    }
    val fm = r.isFm || r.band == -1
    PanelBox(Lucide.Star, "Мои станции", trailing = { if (fyt) Pill("Настройки", Lucide.Settings, height = 38.dp) { Fyt.openRadio() } }) {
        val saved = ui.mine.any { abs(it.freq - r.freq) <= 5 }
        Column(Modifier.weight(1f).verticalScrollCompat()) {
            MineGrid(ui, r)
            if (fm && r.freq > 0 && !saved) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, C.Violet.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable { ui.addMine(r.freq, r.name.ifBlank { ui.named[r.freqText]?.name.orEmpty() }, r.band) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Plus, null, tint = C.Violet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    T("Сохранить ${r.freqText} МГц", 15.sp, C.Text, FontWeight.SemiBold)
                }
            }
        }
        // the tuner's own presets, as a row of small buttons: the frequency a slot showed is tuned as such (after
        // the stock radio's autoscan the slot holds another station), the slot itself only when never seen
        Spacer(Modifier.height(8.dp))
        T("Пресеты тюнера", 12.sp, C.Sub)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..6).forEach { p ->
                val f = known[p]?.substringBefore('|')
                Pill(if (f != null) "P$p $f" else "P$p", null, r.preset == p, 36.dp) {
                    val freq = f?.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
                    if (fyt && freq != null) ui.tune(freq) else ui.toPreset(p)
                }
            }
            val fresh = known.values.mapNotNull { v -> v?.substringBefore('|')?.toDoubleOrNull()?.let { Math.round(it * 100).toInt() } }
                .filter { f -> f in 8750..10800 && ui.mine.none { abs(it.freq - f) < 5 } }
            if (fyt && fresh.isNotEmpty())
                Pill("В мои станции", Lucide.Plus, false, 36.dp) { FmRadio.import(known.values) }
        }
    }
}

/** The own stations as tiles: logo, name, frequency; tap to tune, ✕ to forget. */
@Composable
private fun MineGrid(ui: RadioUi, r: RadioState) {
    if (ui.mine.isEmpty()) {
        T("Настройтесь на станцию и нажмите «Сохранить»: она появится здесь, ⏮ ⏭ будут листать ваши станции.", 13.sp, C.Sub)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = (maxWidth - 8.dp) / 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.mine.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (f, name) ->
                        val sel = abs(r.freq - f) <= 5
                        val st = ui.named[fmText(f)]
                        val logo = rememberLogo(st?.logo)
                        val shape = RoundedCornerShape(16.dp)
                        Row(
                            Modifier.width(w).height(72.dp).clip(shape)
                                .background(if (sel) Brush.verticalGradient(listOf(C.Violet.copy(alpha = 0.30f), C.Purple.copy(alpha = 0.16f)))
                                    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.03f))))
                                .border(1.dp, if (sel) C.Violet else Color.Transparent, shape)
                                .clickable { ui.tune(f) }.padding(start = 10.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(if (logo != null) Color.White else C.Violet.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center) {
                                if (logo != null) Image(logo.asImageBitmap(), null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                                else Icon(Lucide.Radio, null, tint = C.Violet, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                T(fmText(f), 19.sp, if (sel) C.Text else C.Text.copy(alpha = 0.85f), FontWeight.Bold, maxLines = 1)
                                val n = name.ifBlank { st?.name.orEmpty() }
                                if (n.isNotBlank()) T(n, 12.sp, C.Sub, maxLines = 1)
                            }
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable { ui.removeMine(f) }, contentAlignment = Alignment.Center) {
                                Icon(Lucide.X, "Убрать", tint = C.Sub, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

/**
 * «Какая станция на 101.70?»: the country's popular stations with logos (radio-browser.info), a search line,
 * the country to choose. The pick is remembered for this frequency.
 */
@Composable
private fun StationPicker(
    freq: String, unit: String, cc: String, isNamed: Boolean,
    onCountry: (String) -> Unit, onPick: (RadioLogos.Station?) -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var list by remember(cc) { mutableStateOf<List<RadioLogos.Station>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(cc) { list = withContext(Dispatchers.IO) { RadioLogos.popular(ctx, cc) } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(900.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(20.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(20.dp)).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Какая станция на $freq $unit?", 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isNamed) { Pill("Убрать", Lucide.Trash2, height = 38.dp) { onPick(null) }; Spacer(Modifier.width(8.dp)) }
                Pill("Закрыть", Lucide.X, height = 38.dp, onClick = onClose)
            }
            Spacer(Modifier.height(10.dp))
            CountryRow(cc, onCountry)
            Spacer(Modifier.height(10.dp))
            SearchLine(query) { query = it }
            Spacer(Modifier.height(10.dp))
            val l = list
            when {
                l == null -> T("Загружаю станции…", 14.sp, C.Sub)
                l.isEmpty() -> T("Не удалось загрузить список: нужен интернет. Логотипы, которые уже были, работают без него.", 14.sp, C.Sub)
                else -> {
                    val q = query.trim().lowercase()
                    val shown = RadioLogos.withFreqFirst(if (q.isEmpty()) l else l.filter { it.name.lowercase().contains(q) }, freq)
                    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.name + it.logo }) { st ->
                            val logo = rememberLogo(st.logo)
                            Column(
                                Modifier.height(116.dp).clip(RoundedCornerShape(14.dp)).background(C.CardHi).clickable { onPick(st) }.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                                    if (logo != null) Image(logo.asImageBitmap(), null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                                    else Icon(Lucide.Radio, null, tint = C.Gray, modifier = Modifier.size(24.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                                T(st.name, 13.sp, C.Text, FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            T("Станции и логотипы: открытый каталог radio-browser.info. Выбор запоминается для этой частоты.", 11.sp, C.Sub)
        }
    }
}

@Composable
private fun CountryRow(cc: String, onCountry: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RadioLogos.COUNTRIES.forEach { (code, name) -> Pill(name, null, code == cc, 38.dp) { onCountry(code) } }
    }
}

@Composable
internal fun SearchLine(query: String, hint: String = "Название станции", onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Search, null, tint = C.Sub, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) T(hint, 15.sp, C.Sub, maxLines = 1)
            BasicTextField(query, onChange, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(C.Violet), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) Icon(Lucide.X, "Очистить", tint = C.Sub, modifier = Modifier.size(18.dp).clickable { onChange("") })
    }
}

// --- internet radio -----------------------------------------------------------------------------------

/**
 * Internet radio through the own player (the equalizer, the steering wheel keys work): the station's logo, the
 * song the stream announces, ⏮ ⏭ through the list it was started from, the star keeps it in «Избранное».
 */
@Composable
private fun NetHero(ui: RadioUi) {
    val ctx = LocalContext.current
    val st by LocalMusic.state.collectAsState()
    val on = st.stream
    var favs by remember { mutableStateOf(RadioLogos.favourites(ctx)) }
    val cur = remember(st.current, favs) { st.current?.removePrefix(LocalMusic.STREAM) }
    val fav = favs.any { it.url == cur }
    Hero(
        cover = { GlowCover(if (on) ui.netLogo else null, Lucide.Globe, on && st.playing, it, whiteBack = true) },
        onNext = { if (on) LocalMusic.next(ctx) }, onPrev = { if (on) LocalMusic.prev(ctx) },
        info = {
            RadioKind(ui)
            if (!on) TrackInfo("Интернет-радио", "Выберите станцию в списке", RadioLogos.COUNTRIES[ui.cc] ?: "")
            else {
                TrackInfo("Интернет-радио · в эфире", st.title, st.artist, next = st.nextTitle)
                if (st.error.isNotEmpty()) { Spacer(Modifier.height(8.dp)); T(st.error, 14.sp, C.Orange) }
            }
        },
        middle = { OwnMiddle(st.copy(playing = st.playing && on), 0, null, live = true) },
        controls = {
            Control(Lucide.Star, 54.dp, active = fav) {
                val s = ui.netStation(st) ?: return@Control
                RadioLogos.setFavourite(ctx, s, !fav); favs = RadioLogos.favourites(ctx); netFavsVersion.value++
            }
            Control(Lucide.SkipBack, 68.dp) { if (on) LocalMusic.prev(ctx) }
            Control(if (on && st.playing) Lucide.Pause else Lucide.Play, 96.dp, primary = true) { if (on) LocalMusic.toggle(ctx) }
            Control(Lucide.SkipForward, 68.dp) { if (on) LocalMusic.next(ctx) }
            Box(Modifier.size(54.dp))
        },
    )
}

/** The playing stream as a station (for the star), from the player's queue. */
private fun RadioUi.netStation(st: LocalMusic.State): RadioLogos.Station? {
    val url = st.current?.removePrefix(LocalMusic.STREAM) ?: return null
    return RadioLogos.Station(st.station, st.artUrl, url)
}

/** Bumped when the star changes a favourite, so the panel's list follows. */
private val netFavsVersion = MutableStateFlow(0)

@Composable
private fun NetPanel(ui: RadioUi) {
    val ctx = LocalContext.current
    val st by LocalMusic.state.collectAsState()
    val favVer by netFavsVersion.collectAsState()
    var tab by remember { mutableStateOf(if (RadioLogos.favourites(ctx).isNotEmpty()) "fav" else "top") }
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<RadioLogos.Station>?>(null) }
    LaunchedEffect(tab, ui.cc, query, favVer) {
        list = null
        list = when {
            query.isNotBlank() -> { kotlinx.coroutines.delay(500); withContext(Dispatchers.IO) { RadioLogos.search(query.trim()) } }
            tab == "fav" -> RadioLogos.favourites(ctx)
            else -> withContext(Dispatchers.IO) { RadioLogos.streams(ctx, ui.cc) }
        }
    }
    PanelBox(Lucide.Globe, "Станции") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Избранное", Lucide.Star, tab == "fav" && query.isBlank(), 40.dp) { tab = "fav"; query = "" }
            Pill("Популярные", Lucide.Flame, tab == "top" && query.isBlank(), 40.dp) { tab = "top"; query = "" }
        }
        Spacer(Modifier.height(8.dp))
        SearchLine(query) { query = it }
        if (tab == "top" && query.isBlank()) {
            Spacer(Modifier.height(8.dp))
            CountryRow(ui.cc) { ui.cc = it; RadioLogos.setCountry(ctx, it) }
        }
        Spacer(Modifier.height(8.dp))
        val l = list
        when {
            l == null -> T("Загружаю…", 14.sp, C.Sub)
            l.isEmpty() -> T(if (tab == "fav" && query.isBlank()) "Нажмите ★ у станции, и она будет здесь"
                else "Ничего не нашлось. Нужен интернет.", 14.sp, C.Sub)
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(l.size, key = { l[it].url + it }) { i ->
                    val s = l[i]
                    val cur = st.stream && st.current == LocalMusic.STREAM + s.url
                    val logo = rememberLogo(s.logo)
                    ListRow(s.name, s.tags.replace(",", ", "), cur, lead = {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (logo != null) Color.White else C.Violet.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center) {
                            if (logo != null) Image(logo.asImageBitmap(), null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                            else Icon(Lucide.Radio, null, tint = C.Violet, modifier = Modifier.size(18.dp))
                        }
                    }, trail = { if (cur) Bars(st.playing) }) {
                        if (cur) LocalMusic.toggle(ctx) else LocalMusic.playStreams(ctx, l, i)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        T("Бесплатные станции из открытого каталога radio-browser.info. Нужен интернет (мобильный или раздача с телефона).", 11.sp, C.Sub)
    }
}

