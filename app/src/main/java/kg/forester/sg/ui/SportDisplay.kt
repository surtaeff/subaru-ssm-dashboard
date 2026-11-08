package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * «Спорт-дисплей»: one card that looks like a Defi Sports Display F. A tap turns the page, as the buttons on the
 * real one do: the J-shaped tachometer with four numbers, a table of nine values, the G-force ring and the path.
 * Colours, the tachometer scale, the pages and the values shown are the card's own settings (see [SportCfg]).
 */

/** Backlight colours of the display: the face, the lit bar of the tachometer and its yellow and red zones. */
class SportTheme(
    val id: String,
    val name: String,
    val text: Color,
    val label: Color,
    val lit: Color,
    val mid: Color,
    val red: Color,
    val ring: Color,
    val bg: Color = Color(0xFF040508),
    val bezel: Color = Color(0xFF1B1E26),
    val line: Color = Color(0xFF3A3F4B),
    val plate: Color = Color(0xFF2A2E37),
)

val SportThemes = listOf(
    SportTheme("defi", "Классика", Color(0xFFF4F5F8), Color(0xFFC9CDD6), Color(0xFFE9ECF2), Color(0xFFF2E51B), Color(0xFFE5262B), Color(0xFF2E8BFF)),
    SportTheme(
        "blue", "Синяя", Color(0xFFDCEEFF), Color(0xFF8CBFFF), Color(0xFF6CB6FF), Color(0xFFF2E51B), Color(0xFFFF3B3B), Color(0xFF2E8BFF),
        line = Color(0xFF243A57), plate = Color(0xFF1C2A40),
    ),
    SportTheme(
        "red", "Красная", Color(0xFFFF6B5E), Color(0xFFE08A80), Color(0xFFFF5A4A), Color(0xFFFFB020), Color(0xFFFFFFFF), Color(0xFFFF3B30),
        line = Color(0xFF4A2626), plate = Color(0xFF3A1E1E),
    ),
    SportTheme(
        "amber", "Янтарь", Color(0xFFFFC266), Color(0xFFD9A55A), Color(0xFFFFB547), Color(0xFFFFE9A8), Color(0xFFFF3B30), Color(0xFFFFB547),
        line = Color(0xFF4A3A22), plate = Color(0xFF3A2E1C),
    ),
    SportTheme(
        "green", "Зелёная", Color(0xFFB8FFD6), Color(0xFF7FD3A4), Color(0xFF5CF29A), Color(0xFFF2E51B), Color(0xFFFF3B3B), Color(0xFF3FD98A),
        line = Color(0xFF1F4A33), plate = Color(0xFF173826),
    ),
)

/**
 * Settings of a Спорт-дисплей card, kept in its style:
 * "sd:t=<theme>|top=<rpm>|red=<rpm>|led=<0/1>|pg=<pages, e.g. 012>|a=<4 value ids>|b=<9 value ids>" (ids joined by ',';
 * no ';' after the prefix: that separates the cards of the saved layout). An empty style is the factory look.
 * The yellow zone starts 1000 rpm below the red one, the shift lights 2000 rpm below it.
 */
data class SportCfg(
    val theme: String = "defi",
    val top: Int = 8000,
    val red: Int = 6500,
    val leds: Boolean = true,
    val pages: List<Int> = listOf(0, 1, 2),
    val arc: List<String> = ARC_DEFAULT,
    val table: List<String> = TABLE_DEFAULT,
) {
    /** An empty style is the factory look for this [redline], so only that one may be saved as empty. */
    fun format(redline: Int? = null) = if (this == factory(redline)) "" else
        "sd:t=$theme|top=$top|red=$red|led=${if (leds) 1 else 0}|pg=${pages.joinToString("")}|a=${arc.joinToString(",")}|b=${table.joinToString(",")}"

    val look get() = SportThemes.firstOrNull { it.id == theme } ?: SportThemes.first()

    companion object {
        val ARC_DEFAULT = listOf("speed", "coolant", "boost", "gear")
        val TABLE_DEFAULT = listOf("speed", "rpm", "boost", "battery", "coolant", "afr", "throttle", "intake", "gear")
        val TOPS = listOf(6000, 7000, 8000, 9000, 10000)

        /** The factory look: its red zone at this engine's [redline], null = not known. */
        fun factory(redline: Int?) = redline?.let { r -> SportCfg(top = maxOf(SportCfg().top, TOPS.first { it > r }), red = r) } ?: SportCfg()

        fun parse(s: String, redline: Int? = null): SportCfg {
            if (!s.startsWith("sd:")) return factory(redline)
            var c = factory(redline)
            s.removePrefix("sd:").split('|').forEach { p ->
                val v = p.substringAfter('=', "")
                when (p.substringBefore('=')) {
                    "t" -> c = c.copy(theme = v)
                    "top" -> v.toIntOrNull()?.let { c = c.copy(top = it.coerceIn(4000, 12000)) }
                    "red" -> v.toIntOrNull()?.let { c = c.copy(red = it) }
                    "led" -> c = c.copy(leds = v != "0")
                    "pg" -> v.mapNotNull { it.digitToIntOrNull() }.filter { it in 0..2 }.distinct().takeIf { it.isNotEmpty() }?.let { c = c.copy(pages = it.sorted()) }
                    "a" -> v.split(',').filter { it.isNotBlank() }.let { if (it.size == 4) c = c.copy(arc = it) }
                    "b" -> v.split(',').filter { it.isNotBlank() }.let { if (it.size == 9) c = c.copy(table = it) }
                }
            }
            return c.copy(red = c.red.coerceIn(c.top / 2, c.top))
        }
    }
}

private val LocalSport = staticCompositionLocalOf { SportThemes.first() }

@Composable
fun SportDisplayCard(info: String, m: Modifier, style: String = "") {
    val redline = Repo.engineProfile.collectAsState().value.redlineRpm
    val cfg = remember(style, redline) { SportCfg.parse(style, redline) }
    val p = cfg.look
    val live = Repo.live.collectAsState()
    var page by remember(info) { mutableIntStateOf(Repo.settings.sportPage(info)) }
    val shown = cfg.pages[page.coerceIn(0, cfg.pages.size - 1)]
    // the bar of a real gauge does not jump between the ECU's updates: read only while drawing, its frames recompose nothing.
    // The shift lights' flash goes by the reading itself, it does not wait for the bar
    val reading = remember { androidx.compose.runtime.derivedStateOf { (live.value.rpm ?: 0).toFloat() } }
    val rpm = rememberSmooth(reading, cfg.top.toFloat()) { live.value.rpm != null }
    CompositionLocalProvider(LocalSport provides p) {
        BoxWithConstraints(
            m.clip(RoundedCornerShape(18.dp)).background(p.bezel).padding(3.dp).clip(RoundedCornerShape(15.dp))
                .background(p.bg).pointerInput(info, cfg.pages.size) {
                    // a short tap turns the page; the down is left unconsumed, so the screen's long press (editing) still works
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if (cfg.pages.size > 1 && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                            page = (page.coerceIn(0, cfg.pages.size - 1) + 1) % cfg.pages.size
                            Repo.settings.setSportPage(info, page)
                        }
                    }
                },
        ) {
            val full = maxHeight
            val leds = cfg.leds && full > 150.dp
            val pad = (full * 0.04f).coerceIn(4.dp, 14.dp)
            // a low card (a row of the home grid on an 8" unit): the pages would be squeezed past reading, so a strip
            // of the revs and the four numbers in a row instead
            val compact = full < 120.dp
            Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = pad * 0.7f)) {
                if (compact) {
                    Canvas(Modifier.fillMaxWidth().height((full * 0.18f).coerceIn(6.dp, 14.dp))) { drawRevStrip(rpm.value.floatValue, cfg, p) }
                    Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        sportValues(cfg.arc).forEach { Cell(it, Modifier.weight(1f)) }
                    }
                    return@Column
                }
                if (leds) ShiftLights(rpm, reading, cfg, Modifier.fillMaxWidth().height((full * 0.06f).coerceIn(8.dp, 16.dp)))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (shown) {
                        0 -> ArcPage(rpm, cfg, Modifier.fillMaxSize())
                        1 -> TablePage(cfg, Modifier.fillMaxSize())
                        else -> GForcePage(Modifier.fillMaxSize())
                    }
                }
            }
            // where am I: a dot per page in the corner, the current one lit
            if (cfg.pages.size > 1 && !compact) Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                cfg.pages.forEach { Box(Modifier.size(4.dp).clip(CircleShape).background(if (it == shown) p.text else p.line)) }
            }
            InfoButton("Спорт-дисплей", info, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The bezel's LED row: green, yellow, red as the revs climb, all of them flashing red at the shift point. */
@Composable
private fun ShiftLights(smooth: Smooth, reading: androidx.compose.runtime.State<Float>, cfg: SportCfg, m: Modifier) {
    val p = LocalSport.current
    val red = cfg.red.toFloat()
    val from = red - 2000f
    // from the raw reading: over the shift point the lights flash at once, whatever the bar is doing
    val over by remember(red) { androidx.compose.runtime.derivedStateOf { reading.value >= red } }
    // on and off 2.5 times a second (more than 3 is not advised for flashes), only while over the shift point, so a calm
    // drive costs no frames; it is read while drawing, so the flashing redraws the lights without recomposing the card
    var blink by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(over) {
        blink = true
        while (over) { kotlinx.coroutines.delay(200); blink = !blink }
    }
    Canvas(m) {
        val rpm = smooth.value.floatValue
        val n = 9
        val r = minOf(size.height / 2f, size.width / (n * 4.5f))
        val step = size.width * 0.6f / (n - 1)
        val x0 = (size.width - step * (n - 1)) / 2
        for (i in 0 until n) {
            val on = rpm >= from + (red - from) * i / n
            val base = when { i < 3 -> Color(0xFF2BD34F); i < 6 -> p.mid; else -> p.red }
            val col = when {
                over -> if (blink) p.red else p.red.copy(alpha = 0.15f)
                on -> base
                else -> p.red.copy(alpha = 0.16f)
            }
            val c = Offset(x0 + step * i, size.height / 2)
            if (col.alpha > 0.5f) drawCircle(col.copy(alpha = 0.3f), r * 1.9f, c)
            drawCircle(col, r, c)
        }
    }
}

/** The compact card's revs: the tachometer's segments in a straight line, with its yellow and red zones. */
private fun DrawScope.drawRevStrip(rpm: Float, cfg: SportCfg, p: SportTheme) {
    val n = 40
    val top = cfg.top.toFloat()
    val red = cfg.red.toFloat()
    val seg = size.width / n
    for (i in 0 until n) {
        val v = (i + 0.5f) / n * top
        val zone = when { v >= red -> p.red; v >= red - 1000f -> p.mid; else -> p.lit }
        val lit = rpm > i.toFloat() / n * top && rpm > 300f
        drawRect(if (lit) zone else zone.copy(alpha = 0.14f), Offset(i * seg + seg * 0.15f, 0f), androidx.compose.ui.geometry.Size(seg * 0.7f, size.height))
    }
}

// ---- page 1: the J-shaped tachometer ----

/** The bar's outer edge: up the left side, a quarter turn, then along the top. [at] gives the point and the inward normal. */
private class JPath(val x0: Float, val yBottom: Float, val top: Float, val r: Float, val xEnd: Float) {
    private val yc = top + r
    private val l1 = yBottom - yc
    private val l2 = (PI / 2 * r).toFloat()
    val length = l1 + l2 + (xEnd - x0 - r)

    fun at(s: Float): FloatArray = when {
        s <= l1 -> floatArrayOf(x0, yBottom - s, 1f, 0f)
        s <= l1 + l2 -> {
            val a = (s - l1) / r
            floatArrayOf(x0 + r - r * cos(a), yc - r * sin(a), cos(a), sin(a))
        }
        else -> floatArrayOf(x0 + r + (s - l1 - l2), top, 0f, 1f)
    }
}

@Composable
private fun ArcPage(rpm: Smooth, cfg: SportCfg, m: Modifier) {
    val p = LocalSport.current
    val vals = sportValues(cfg.arc)
    BoxWithConstraints(m) {
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        val path = remember(w, h) { JPath(h * 0.02f, h * 0.99f, h * 0.03f, h * 0.36f, w * 0.99f) }
        val thick = { s: Float -> h * (0.06f + 0.15f * (s / path.length).let { it * it }) }
        Box(Modifier.fillMaxSize().then(remember(path, cfg, p, h) { tachDrawing(path, thick, rpm, h, cfg, p) }))
        // the numbers sit in the bend of the bar, out of the way of its thickest part
        val left = with(d) { (path.x0 + thick(path.length * 0.3f) + h * 0.17f).toDp() }
        val topPad = with(d) { (path.top + thick(path.length) + h * 0.14f).toDp() }
        Column(Modifier.fillMaxSize().padding(start = left, top = topPad)) {
            vals.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Cell(row[0], Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Cell(row[1], Modifier.weight(1f))
                }
            }
            TimeRow(Modifier.fillMaxWidth().weight(0.8f))
        }
    }
}

/** The tachometer: its segments, rail and numbers are worked out once for a size, a frame of the bar only paints them. */
private fun tachDrawing(path: JPath, thick: (Float) -> Float, rpm: Smooth, h: Float, cfg: SportCfg, p: SportTheme) = Modifier.drawWithCache {
    val top = cfg.top.toFloat()
    val red = cfg.red.toFloat()
    val yellow = red - 1000f
    val n = 64
    val segLen = path.length / n
    val gap = segLen * 0.3f
    val quads = Array(n) { i ->
        val s0 = i * segLen + gap / 2
        val s1 = (i + 1) * segLen - gap / 2
        val a = path.at(s0); val b = path.at(s1)
        val ta = thick(s0); val tb = thick(s1)
        Path().apply {
            moveTo(a[0], a[1]); lineTo(b[0], b[1])
            lineTo(b[0] + b[2] * tb, b[1] + b[3] * tb); lineTo(a[0] + a[2] * ta, a[1] + a[3] * ta); close()
        }
    }
    val zones = Array(n) { i ->
        val v = (i + 0.5f) / n * top
        val zone = when { v >= red -> p.red; v >= yellow -> p.mid; else -> p.lit }
        zone to zone.copy(alpha = if (v >= yellow) 0.2f else 0.11f)
    }
    // a thin rail along the inside of the bar, as on the Defi face
    val rail = Path()
    var s = 0f
    while (s <= path.length) {
        val q = path.at(s); val t = thick(s) + h * 0.025f
        if (s == 0f) rail.moveTo(q[0] + q[2] * t, q[1] + q[3] * t) else rail.lineTo(q[0] + q[2] * t, q[1] + q[3] * t)
        s += path.length / 120
    }
    // 0…top (thousands of rpm), just inside the rail
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD_ITALIC)
        textSize = h * 0.085f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    onDrawBehind {
        val now = rpm.value.floatValue
        for (i in 0 until n) {
            val lit = now > i.toFloat() / n * top && now > 300f
            drawPath(quads[i], if (lit) zones[i].first else zones[i].second)
        }
        drawPath(rail, p.line, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (k in 0..(top / 1000).toInt()) {
            val sk = (k * 1000f / top * path.length).coerceIn(segLen / 2, path.length - segLen / 2)
            val q = path.at(sk)
            val t = thick(sk) + h * 0.08f
            paint.color = (if (k * 1000f >= red) p.red else p.text).toArgb()
            drawContext.canvas.nativeCanvas.drawText("$k", q[0] + q[2] * t, q[1] + q[3] * t + paint.textSize * 0.36f, paint)
        }
    }
}

// ---- page 2: nine values ----

@Composable
private fun TablePage(cfg: SportCfg, m: Modifier) {
    val vals = sportValues(cfg.table)
    Column(m.padding(top = 4.dp)) {
        vals.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Cell(it, Modifier.weight(1f)) }
            }
        }
        TimeRow(Modifier.fillMaxWidth().weight(0.8f))
    }
}

// ---- the values ----

/** One value as the display prints it. */
data class SportValue(val label: String, val unit: String, val text: String, val warn: Boolean)

/** Labels in the Defi's short capitals for the values it shows by default; the rest use the catalogue's short name. */
private val SportLabels = mapOf(
    "speed" to "СКОРОСТЬ", "rpm" to "ОБОРОТЫ", "boost" to "НАДДУВ", "battery" to "АКБ", "coolant" to "ОЖ",
    "afr" to "AFR", "throttle" to "ДРОССЕЛЬ", "intake" to "ВПУСК", "gear" to "ПЕРЕДАЧА", "oil" to "МАСЛО",
)

/** The short label a value is printed with, also for the editor. */
fun sportLabel(id: String, avail: List<kg.forester.sg.ssm.RrParam>): String =
    SportLabels[id] ?: Metrics.of(id, avail)?.short?.uppercase() ?: id

/** The values as States, each changing on its own, read by its [Cell] alone: a reading doesn't recompose the page. */
@Composable
private fun sportValues(ids: List<String>): List<androidx.compose.runtime.State<SportValue>> {
    val ctx = rememberMetricCtx()
    val avail = Repo.rrAvailable.collectAsState()
    return remember(ids, ctx, avail) {
        ids.map { id ->
            androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) { sportValue(id, ctx.value, avail.value) }
        }
    }
}

private fun sportValue(id: String, ctx: MetricCtx, avail: List<kg.forester.sg.ssm.RrParam>): SportValue {
    val e = ctx.e
    return when (id) {
        // gauge pressure in bar with its sign, vacuum included, as the Defi's IN-MF.P.
        "boost" -> SportValue("НАДДУВ", "бар", e.gaugeKpa?.let { "%.2f".format(it / 100f) } ?: "—", false)
        "gear" -> SportValue("ПЕРЕДАЧА", "", gearText(e, ctx.t), false)
        "coolant" -> SportValue("ОЖ", "°C", e.coolantC?.toString() ?: "—", e.coolantC?.let { it >= 105 } == true)
        "battery" -> SportValue("АКБ", "В", e.batteryV?.let { "%.1f".format(it) } ?: "—", e.batteryV?.let { it < 12.0f || it > 15.2f } == true)
        else -> {
            val ind = Metrics.of(id, avail)
            if (ind == null) SportValue(sportLabel(id, avail), "", "—", false)
            else {
                val v = ind.read(ctx)
                SportValue(sportLabel(id, avail), ind.unit, ind.text(v), ind.warn(v))
            }
        }
    }
}

// ---- the same faces outside any window, for a big USB panel ----

/** A value drawn as the display's [Cell] prints it: its label over the number and the unit. */
private fun DrawScope.printCell(v: SportValue, p: SportTheme, l: Float, t: Float, w: Float, h: Float, text: (String) -> String) {
    val canvas = drawContext.canvas.nativeCanvas
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    paint.textSize = h * 0.2f
    paint.color = p.label.toArgb()
    canvas.drawText(text(v.label), l, t + h * 0.22f, paint)
    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD_ITALIC)
    paint.textSize = h * 0.58f
    paint.textAlign = android.graphics.Paint.Align.RIGHT
    val unit = text(v.unit)
    val up = android.graphics.Paint().apply { isAntiAlias = true; textSize = h * 0.18f; color = p.label.toArgb(); textAlign = android.graphics.Paint.Align.RIGHT }
    val uw = if (unit.isEmpty()) 0f else up.measureText(unit) + h * 0.06f
    while (paint.measureText(v.text) > w - uw && paint.textSize > 8f) paint.textSize *= 0.92f
    paint.color = (if (v.warn) p.red else p.text).toArgb()
    canvas.drawText(v.text, l + w - uw, t + h * 0.88f, paint)
    if (unit.isNotEmpty()) canvas.drawText(unit, l + w, t + h * 0.88f, up)
}

/** The tachometer page in [r] of a panel: the J-shaped bar and four values. */
internal fun DrawScope.drawSportArc(cfg: SportCfg, ctx: MetricCtx, avail: List<kg.forester.sg.ssm.RrParam>, r: androidx.compose.ui.geometry.Rect, text: (String) -> String) {
    val p = cfg.look
    val pad = r.height * 0.04f
    drawRect(p.bg, r.topLeft, r.size)
    val h = r.height - pad * 2
    val w = r.width - pad * 2
    val path = JPath(r.left + pad + h * 0.02f, r.top + pad + h * 0.99f, r.top + pad + h * 0.03f, h * 0.36f, r.left + pad + w * 0.99f)
    val thick = { s: Float -> h * (0.06f + 0.15f * (s / path.length).let { it * it }) }
    val now = (ctx.e.rpm ?: 0).toFloat()
    val top = cfg.top.toFloat()
    val n = 64
    val segLen = path.length / n
    val gap = segLen * 0.3f
    val q = Path()
    for (i in 0 until n) {
        val s0 = i * segLen + gap / 2
        val s1 = (i + 1) * segLen - gap / 2
        val a = path.at(s0); val b = path.at(s1)
        val ta = thick(s0); val tb = thick(s1)
        q.reset()
        q.moveTo(a[0], a[1]); q.lineTo(b[0], b[1])
        q.lineTo(b[0] + b[2] * tb, b[1] + b[3] * tb); q.lineTo(a[0] + a[2] * ta, a[1] + a[3] * ta); q.close()
        val v = (i + 0.5f) / n * top
        val zone = when { v >= cfg.red -> p.red; v >= cfg.red - 1000f -> p.mid; else -> p.lit }
        val lit = now > i.toFloat() / n * top && now > 300f
        drawPath(q, if (lit) zone else zone.copy(alpha = if (v >= cfg.red - 1000f) 0.2f else 0.11f))
    }
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD_ITALIC)
        textSize = h * 0.075f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    for (k in 0..(top / 1000).toInt()) {
        val sk = (k * 1000f / top * path.length).coerceIn(segLen / 2, path.length - segLen / 2)
        val a = path.at(sk)
        val t = thick(sk) + h * 0.08f
        paint.color = (if (k * 1000f >= cfg.red) p.red else p.text).toArgb()
        drawContext.canvas.nativeCanvas.drawText("$k", a[0] + a[2] * t, a[1] + a[3] * t + paint.textSize * 0.36f, paint)
    }
    val left = path.x0 + thick(path.length * 0.3f) + h * 0.2f
    val tp = path.top + thick(path.length) + h * 0.16f
    val cw = (r.right - pad - left - h * 0.05f) / 2
    val ch = (r.bottom - pad - tp) / 2
    cfg.arc.forEachIndexed { i, id ->
        printCell(sportValue(id, ctx, avail), p, left + (i % 2) * (cw + h * 0.05f), tp + (i / 2) * ch, cw, ch * 0.92f, text)
    }
}

/** The table page in [r] of a panel: nine values. */
internal fun DrawScope.drawSportTable(cfg: SportCfg, ctx: MetricCtx, avail: List<kg.forester.sg.ssm.RrParam>, r: androidx.compose.ui.geometry.Rect, text: (String) -> String) {
    val p = cfg.look
    drawRect(p.bg, r.topLeft, r.size)
    val pad = r.height * 0.04f
    val cw = (r.width - pad * 4) / 3
    val ch = (r.height - pad * 2) / 3
    cfg.table.forEachIndexed { i, id ->
        printCell(sportValue(id, ctx, avail), p, r.left + pad + (i % 3) * (cw + pad), r.top + pad + (i / 3) * ch, cw, ch * 0.92f, text)
    }
}

// ---- page 3: G-force and the path ----

/** The last few kilometres of GPS fixes, kept while a Спорт-дисплей card is on screen. */
private object Locus {
    val lat = ArrayList<Double>()
    val lon = ArrayList<Double>()

    fun add(la: Double, lo: Double) {
        if (lat.isNotEmpty()) {
            val dy = (la - lat.last()) * 111_000
            val dx = (lo - lon.last()) * 111_000 * cos(Math.toRadians(la))
            if (dx * dx + dy * dy < 9.0) return // under 3 m: standing still or GPS noise
        }
        lat += la; lon += lo
        if (lat.size > 900) { lat.removeAt(0); lon.removeAt(0) }
    }
}

@Composable
private fun GForcePage(m: Modifier) {
    val p = LocalSport.current
    val motion = Repo.motion.collectAsState()
    // read in the drawing only: the dot's frames and the sensor's readings redraw the ring, not the page
    val st by motion
    val latDot = rememberSmooth(motion.slot { (it.latG ?: 0f).coerceIn(-1.2f, 1.2f) }, 2.4f)
    val lonDot = rememberSmooth(motion.slot { (it.lonG ?: 0f).coerceIn(-1.2f, 1.2f) }, 2.4f)
    val gps by Repo.gps.collectAsState()
    var points by remember { mutableIntStateOf(Locus.lat.size) }
    LaunchedEffect(gps) {
        val g = gps ?: return@LaunchedEffect
        if (g.lat != null && g.lon != null && g.accuracyM < 30f) { Locus.add(g.lat, g.lon); points = Locus.lat.size }
    }
    Column(m) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Header("ПЕРЕГРУЗКИ")
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Canvas(Modifier.fillMaxSize()) { drawGRing(latDot.value.floatValue, lonDot.value.floatValue, st.maxLat, st.maxAccel, st.maxBrake, p) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Header("ТРАЕКТОРИЯ")
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (points < 2) Text(
                        if (gps?.lat == null) "ждём GPS" else "поехали, след появится",
                        style = TextStyle(fontSize = 12.sp, color = p.label, fontStyle = FontStyle.Italic),
                    )
                    else Canvas(Modifier.fillMaxSize().padding(6.dp)) { drawLocus(points, p) }
                }
            }
        }
        TimeRow(Modifier.fillMaxWidth().weight(0.3f))
    }
}

private fun DrawScope.drawGRing(lat: Float?, lon: Float?, maxLat: Float, maxAccel: Float, maxBrake: Float, p: SportTheme) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = minOf(size.width, size.height) / 2 * 0.78f
    // the glow of the outer ring
    for (k in 4 downTo 1) drawCircle(p.ring.copy(alpha = 0.07f * (5 - k)), r + k * 3f, c, style = Stroke(3f))
    drawCircle(p.ring, r, c, style = Stroke(2.5f))
    drawCircle(Color(0xFF3FB6A8).copy(alpha = 0.7f), r * 0.5f, c, style = Stroke(1.5f))
    drawLine(p.line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
    drawLine(p.line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
    // 1.2 g at the ring: up = speeding up, right = right turn, as on the G-метр card
    val x = ((lat ?: 0f) / 1.2f).coerceIn(-1f, 1f)
    val y = ((lon ?: 0f) / 1.2f).coerceIn(-1f, 1f)
    val dot = Offset(c.x + x * r, c.y - y * r)
    drawCircle(p.mid.copy(alpha = 0.3f), r * 0.13f, dot)
    drawCircle(p.mid, r * 0.07f, dot)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD_ITALIC)
        textSize = (r * 0.17f).coerceAtLeast(14f)
        color = p.text.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val nc = drawContext.canvas.nativeCanvas
    // the trip's peaks around the ring, as the Defi prints them
    nc.drawText("%.1f".format(maxAccel), c.x, c.y - r - paint.textSize * 0.45f, paint)
    nc.drawText("%.1f".format(maxBrake), c.x, c.y + r + paint.textSize * 1.15f, paint)
    paint.textAlign = android.graphics.Paint.Align.RIGHT
    nc.drawText("%.1f".format(maxLat), c.x - r - paint.textSize * 0.4f, c.y + paint.textSize * 0.36f, paint)
    paint.textAlign = android.graphics.Paint.Align.LEFT
    nc.drawText("%.1f".format(maxLat), c.x + r + paint.textSize * 0.4f, c.y + paint.textSize * 0.36f, paint)
}

private fun DrawScope.drawLocus(@Suppress("UNUSED_PARAMETER") count: Int, p: SportTheme) {
    val la = Locus.lat; val lo = Locus.lon
    if (la.size < 2) return
    val k = cos(Math.toRadians(la.last()))
    val minX = lo.min() * k; val maxX = lo.max() * k
    val minY = la.min(); val maxY = la.max()
    // at least ~200 m across, so a slow start does not fill the box with GPS jitter
    val span = maxOf(maxX - minX, maxY - minY, 200.0 / 111_000)
    val scale = minOf(size.width, size.height) / span
    val ox = size.width / 2 - ((minX + maxX) / 2 * scale)
    val oy = size.height / 2 + ((minY + maxY) / 2 * scale)
    fun pt(i: Int) = Offset((lo[i] * k * scale + ox).toFloat(), (oy - la[i] * scale).toFloat())
    val path = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until la.size) pt(i).let { lineTo(it.x, it.y) } }
    drawPath(path, p.red.copy(alpha = 0.35f), style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, p.red, style = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val now = pt(la.size - 1)
    drawCircle(p.ring.copy(alpha = 0.35f), 12f, now)
    drawCircle(p.ring, 6f, now)
}

// ---- pieces ----

@Composable
private fun Header(text: String) {
    val p = LocalSport.current
    Box(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color.Transparent, p.ring.copy(alpha = 0.35f), Color.Transparent))),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontSize = 13.sp, color = p.text, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) }
}

/** A value as the Defi prints it: italic label and unit on top, a big italic number on the right, a line under it. */
@Composable
private fun Cell(value: androidx.compose.runtime.State<SportValue>, m: Modifier) {
    val p = LocalSport.current
    // the number at its own pace, the colour from the reading at once
    val text by rememberDigits { value.value.text }
    val head by remember(value) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) { value.value.copy(text = "") }
    }
    BoxWithConstraints(m.fillMaxHeight()) {
        val small = (maxHeight.value * 0.2f).coerceIn(9f, 15f).sp
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(head.label, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                    style = TextStyle(fontSize = small, color = p.label, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                Spacer(Modifier.weight(1f))
                if (head.unit.isNotEmpty()) Text(head.unit, maxLines = 1, style = TextStyle(fontSize = small * 0.85f, color = p.label, fontStyle = FontStyle.Italic))
            }
            BigNumber(text, if (head.warn) p.red else p.text, Modifier.fillMaxWidth().weight(1f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(p.line, p.label.copy(alpha = 0.6f), p.line))))
        }
    }
}

@Composable
private fun BigNumber(value: String, color: Color, m: Modifier, align: Alignment = Alignment.CenterEnd) {
    BoxWithConstraints(m, contentAlignment = align) {
        val d = LocalDensity.current
        val byH = with(d) { (maxHeight * 0.92f).toSp().value }
        val byW = with(d) { (maxWidth / (value.length.coerceAtLeast(2) * 0.62f)).toSp().value }
        Text(
            value, maxLines = 1, modifier = Modifier.padding(end = 4.dp),
            style = TextStyle(
                fontSize = minOf(byH, byW, 72f).coerceAtLeast(10f).sp, color = color, fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic, textAlign = TextAlign.End,
                lineHeight = minOf(byH, byW, 72f).coerceAtLeast(10f).sp,
            ),
        )
    }
}

/** The bottom strip: «ВРЕМЯ» on a slanted plate and the time of this trip. */
@Composable
private fun TimeRow(m: Modifier) {
    val p = LocalSport.current
    val ts by Repo.trip.collectAsState()
    val sec = ts.trip?.durationS ?: 0L
    val text = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    BoxWithConstraints(m.padding(top = 3.dp)) {
        val plateH: Dp = minOf(maxHeight * 0.55f, 22.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(plateH).clip(Slanted)
                    .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(p.plate, Color.White, 0.12f), p.plate))),
                contentAlignment = Alignment.Center,
            ) {
                Text("ВРЕМЯ", maxLines = 1, style = TextStyle(fontSize = (plateH.value * 0.55f).sp, color = p.text, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
            }
            Spacer(Modifier.width(10.dp))
            BigNumber(if (ts.trip == null) "0:00" else text, p.text, Modifier.weight(1.3f).fillMaxHeight())
        }
    }
}

private val Slanted = GenericShape { s, _ ->
    val k = s.height * 0.6f
    moveTo(k, 0f); lineTo(s.width, 0f); lineTo(s.width - k, s.height); lineTo(0f, s.height); close()
}

/**
 * The gear: from the automatic's TCU when it answers, otherwise from revs per km/h by [kg.forester.sg.service.Gears]
 * (this car's learned ratios, else the usual Subaru gearboxes). "—" with the clutch down or between gears.
 * «M3» in the SportShift manual gate.
 */
private fun gearText(e: kg.forester.sg.ssm.EcuSnapshot, tcu: kg.forester.sg.ssm.TcuSnapshot?): String {
    tcu?.gear?.takeIf { it > 0 }?.let { return if (tcu.range == "M") "M$it" else "$it" }
    return kg.forester.sg.service.Gears.text(e.rpm, e.speedKmh, Repo.settings.gearbox == "at",
        kg.forester.sg.service.RulesHost.diag.value.gears)
}
