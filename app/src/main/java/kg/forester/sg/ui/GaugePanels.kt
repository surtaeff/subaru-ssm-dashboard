package kg.forester.sg.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ChartLine
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.Fan
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mountain
import com.composables.icons.lucide.Radar
import kg.forester.sg.data.Repo
import kg.forester.sg.data.DistanceUnits
import kg.forester.sg.data.tr
import kg.forester.sg.service.Motion
import kg.forester.sg.service.PerfCalc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The G-force card and the ready full-card panels, as cards for the boards and the layouts. */
val GaugePanelCards: Map<String, Pair<String, ImageVector>> = linkedMapOf(
    "gforce" to ("G-сила" to Lucide.Crosshair),
    "p_tacho_blue" to ("Тахо-арка: синяя" to Lucide.Gauge),
    "p_tacho_orange" to ("Тахо-арка: оранжевая" to Lucide.Gauge),
    "p_tacho_teal" to ("Тахо-арка: бирюзовая" to Lucide.Gauge),
    "p_turbo" to ("Турбо-панель" to Lucide.Fan),
    "p_tilt" to ("Крен и тангаж" to Lucide.Mountain),
    "p_gradar" to ("G-радар" to Lucide.Radar),
    "p_web" to ("Паутина" to Lucide.Activity),
    "p_pid" to ("Параметры: список" to Lucide.ListChecks),
    "p_accel" to ("Разгон: график" to Lucide.ChartLine),
    "p_brake" to ("Торможение: график" to Lucide.ChartLine),
)

private val MotionCards = setOf("gforce", "p_tilt", "p_gradar")

/** Whether a card is offered in the add menu and the layouts: a panel that needs what this car or unit hasn't got isn't. */
fun panelOffered(id: String): Boolean = when (id) {
    in MotionCards -> Repo.motion.value.let { GaugeMath.motionOffered(it.hasSensor, it.calibrated, Repo.settings.feature("motion")) }
    "p_turbo" -> Repo.carTurbo.value
    else -> true
}

private val PlateCol = Color(0xFF06080D)
private val TextDim = Color(0xFF9AA4B4)

@Composable
fun GaugePanel(id: String, m: Modifier) {
    when (id) {
        "gforce" -> if (panelOffered(id)) GForceCard(m) else NoSensor("G-сила", m)
        "p_tacho_blue" -> TachoPanel(TachoLook.BLUE, m)
        "p_tacho_orange" -> TachoPanel(TachoLook.ORANGE, m)
        "p_tacho_teal" -> TachoPanel(TachoLook.TEAL, m)
        "p_turbo" -> if (panelOffered(id)) TurboPanel(m) else Note("Турбо-панель", "Покажется, когда турбина будет известна: на этой машине наддува пока нет", m)
        "p_tilt" -> if (panelOffered(id)) TiltPanel(m) else NoSensor("Крен и тангаж", m)
        "p_gradar" -> if (panelOffered(id)) RadarPanel(m) else NoSensor("G-радар", m)
        "p_web" -> WebPanel(m)
        "p_pid" -> PidShortcut(m)
        "p_accel" -> RunPanel(brake = false, m)
        "p_brake" -> RunPanel(brake = true, m)
    }
}

@Composable
private fun NoSensor(title: String, m: Modifier) =
    Note(title, "Нужен обнулённый акселерометр: включите «G-метр и крен» в функциях и обнулите датчик в «Замеры» → «G-метр». Во многих магнитолах FYT датчика нет", m)

@Composable
private fun Note(title: String, text: String, m: Modifier) {
    Panel(m, padding = 10.dp) {
        T(title, LocalCar.current.label, C.Sub, maxLines = 1)
        FitText(text, LocalCar.current.secondary, Modifier.fillMaxWidth().weight(1f), C.Sub, maxLines = 5)
    }
}

/** Text on a panel's canvas: one paint per panel and size. The size comes as the height of the digits, y is their middle. */
private class Pen {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val plain: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    fun DrawScope.put(t: String, x: Float, y: Float, sz: Float, col: Color, face: Typeface = plain, align: Paint.Align = Paint.Align.CENTER) {
        if (t.isEmpty()) return
        p.typeface = face; p.textSize = sz; p.color = col.toArgb(); p.textAlign = align
        drawContext.canvas.nativeCanvas.drawText(t, x, y + sz * 0.35f, p)
    }
}

/** The dark plate of a panel in a layer with [static]; [live] on top of it, every frame. In a layer of its own: a frame re-records only this panel. */
@Composable
private fun PanelCanvas(m: Modifier, vararg keys: Any?, build: CacheDrawScope.(pen: Pen) -> PanelDraw) {
    val art = remember(*keys) {
        Modifier.drawWithCache {
            val pen = Pen()
            val d = build(pen)
            val base = obtainGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
                record {
                    drawRoundRect(PlateCol, Offset.Zero, size, CornerRadius(minOf(size.width, size.height) * 0.05f))
                    d.static(this)
                }
            }
            val draw: DrawResult = onDrawBehind {
                drawLayer(base)
                d.live(this)
            }
            draw
        }
    }
    Box(m.fillMaxSize().graphicsLayer().then(art))
}

private class PanelDraw(val static: DrawScope.() -> Unit, val live: DrawScope.() -> Unit)

private fun rad(deg: Float) = deg * (PI / 180.0).toFloat()

// ---- Тахо-арка ----

/** The speed as a panel prints it, in the chosen unit. */
private fun speedDigits(kmh: Int?): String = kmh?.let { DistanceUnits.speed(it).toString() } ?: "—"

private enum class TachoLook(val arc: List<Color>, val track: Color, val digits: Color, val dot: Boolean, val glow: Color?) {
    BLUE(listOf(Color(0xFF1466D8), Color(0xFF3FB4F5), Color(0xFF3FB4F5)), Color(0xFF12202E), Color.White, false, null),
    ORANGE(listOf(Color(0xFFB34A0A), Color(0xFFFF9A2E), Color(0xFFFFD08A)), Color(0xFF2A1A0E), Color(0xFFFFE0B8), false, Color(0xFFFF8A1F)),
    TEAL(listOf(Color(0xFF17A79B), Color(0xFF3FE0C4), Color(0xFF3F8BE8)), Color(0xFF16242A), Color.White, true, null),
}

@Composable
private fun TachoPanel(look: TachoLook, m: Modifier) {
    val e = Repo.live.collectAsState()
    val trip = Repo.trip.collectAsState()
    val engine = Repo.engineProfile.collectAsState()
    val low = kg.forester.sg.data.DeviceTier.current.collectAsState().value == kg.forester.sg.data.Tier.LOW
    val top = engine.value.rpmTop
    val red = engine.value.rpmRedFrom
    val rpmShare = remember(top) { derivedStateOf { ((e.value.rpm ?: 0) / top).coerceIn(0f, 1f) } }
    val rpm = rememberSmooth(rpmShare, 1f) { e.value.rpm != null }
    val speed = rememberDigits { speedDigits(e.value.speedKmh) }
    val cons = rememberDigits { trip.value.instPer100?.let { "%.1f".format(DistanceUnits.consumption(it)) } ?: "—" }
    val km = rememberDigits { trip.value.trip?.distanceKm?.let { "%.1f".format(DistanceUnits.distance(it)) } ?: "—" }
    val tank = GaugeMath.shownTank(Repo.settings.tankL, Repo.demoOn.collectAsState().value)
    val warnCol = C.Orange
    val coolRule = Warn.of("coolant")
    PanelCanvas(m, look, low, top, red, tank, warnCol) { pen ->
        val w = size.width
        val h = size.height
        val at = GaugeLayout.tacho(w, h, density)
        val rr = at.r
        val cx = at.cx
        val cy = at.cy
        val start = 160f
        val sw = 220f
        val redShare = red?.let { (it / top).coerceIn(0f, 1f) }
        val tl = Offset(cx - rr, cy - rr)
        val sz = Size(rr * 2, rr * 2)
        val arcW = rr * 0.12f
        val arcS = Stroke(arcW, cap = StrokeCap.Butt)
        val glowS = Stroke(arcW * 2f)
        val brush = Brush.linearGradient(look.arc, Offset(cx - rr, cy), Offset(cx + rr, cy))
        val side = rr * 1.22f
        val sTl = Offset(cx - side, cy - side)
        val sSz = Size(side * 2, side * 2)
        val sideS = Stroke(rr * 0.05f, cap = StrokeCap.Round)
        val halfSide = 28f
        val unit = tr(DistanceUnits.speedUnit)
        val perUnit = tr(DistanceUnits.consumptionUnit)
        val kmUnit = tr(DistanceUnits.distanceUnit)
        fun pt(deg: Float, r: Float) = Offset(cx + r * cos(rad(deg)), cy + r * sin(rad(deg)))
        PanelDraw(static = {
            with(pen) {
                drawArc(look.track, start, sw, false, tl, sz, style = arcS)
                if (redShare != null) drawArc(Color(0x66E5232A), start + sw * redShare, sw * (1f - redShare), false, tl, sz, style = arcS)
                val thousands = (top / 1000f).toInt()
                val every = if (at.detail) 1 else 2
                for (k in 0..thousands) {
                    val s = k * 1000f / top
                    val deg = start + sw * s
                    val inRed = redShare != null && s >= redShare
                    val col = if (inRed) Color(0xFFFF4A3A) else Color(0xFFE8EAEE)
                    drawLine(col, pt(deg, rr - arcW * 0.6f), pt(deg, rr - arcW * 1.3f), rr * 0.018f)
                    if (k % every == 0) {
                        val p = pt(deg, rr - arcW * 2.1f)
                        put(k.toString(), p.x, p.y, rr * 0.13f, col, bold)
                    }
                }
                // coolant left, fuel right: tracks and their ends
                drawArc(Color(0xFF1C242C), 180f - halfSide, halfSide * 2, false, sTl, sSz, style = sideS)
                drawArc(Color(0xFF1C242C), -halfSide, halfSide * 2, false, sTl, sSz, style = sideS)
                if (at.detail) {
                    val cold = pt(180f - halfSide, side + rr * 0.1f)
                    val hot = pt(180f + halfSide, side + rr * 0.1f)
                    put(GaugeMath.COOLANT_COLD.toInt().toString(), cold.x, cold.y, rr * 0.09f, TextDim)
                    put(GaugeMath.COOLANT_HOT.toInt().toString(), hot.x, hot.y, rr * 0.09f, TextDim)
                    val e0 = pt(halfSide, side + rr * 0.1f)
                    val e1 = pt(-halfSide, side + rr * 0.1f)
                    put("E", e0.x, e0.y, rr * 0.09f, TextDim)
                    put("F", e1.x, e1.y, rr * 0.09f, TextDim)
                    // the bottom's captions
                    put(perUnit, cx - rr * 0.62f, at.caption, rr * 0.09f, TextDim)
                    put(kmUnit, cx + rr * 0.62f, at.caption, rr * 0.09f, TextDim)
                }
                drawLine(Color(0xFF232C36), Offset(cx - rr * 1.05f, at.rule), Offset(cx + rr * 1.05f, at.rule), 1f)
            }
        }, live = {
            with(pen) {
                val s = rpm.value.floatValue
                val hot = redShare != null && s >= redShare
                if (e.value.rpm != null && s > 0.003f) {
                    if (look.glow != null && !low) drawArc(look.glow.copy(alpha = 0.22f), start, sw * s, false, tl, sz, style = glowS)
                    if (look == TachoLook.BLUE && redShare != null && s > redShare) {
                        drawArc(brush, start, sw * redShare, false, tl, sz, style = arcS)
                        drawArc(Color(0xFFFF2E2E), start + sw * redShare, sw * (s - redShare), false, tl, sz, style = arcS)
                    } else drawArc(brush, start, sw * s, false, tl, sz, style = arcS)
                    val tip = pt(start + sw * s, rr)
                    if (look.dot) {
                        drawCircle(Color(0xFF0B1A2A), arcW * 0.8f, tip)
                        drawCircle(look.arc.last(), arcW * 0.5f, tip)
                    } else drawLine(if (hot) Color(0xFFFF4A3A) else Color(0xFFFFC23D), pt(start + sw * s, rr - arcW * 0.7f), pt(start + sw * s, rr + arcW * 0.7f), rr * 0.03f)
                }
                val live = e.value
                GaugeMath.coolantShare(live.coolantC)?.let { c ->
                    val col = if (coolRule.hits(live.coolantC?.toFloat())) warnCol else Color(0xFF3FB4F5)
                    if (c > 0.01f) drawArc(col, 180f - halfSide, halfSide * 2 * c, false, sTl, sSz, style = sideS)
                }
                val t = trip.value
                GaugeMath.fuelShare(t.fuelLeftL, tank)?.let { f ->
                    val col = if (Warn.of("range").hits(t.rangeKm?.toFloat())) warnCol else Color(0xFF3FE0C4)
                    if (f > 0.01f) drawArc(col, halfSide, -halfSide * 2 * f, false, sTl, sSz, style = sideS)
                }
                put(speed.value, cx, cy - rr * 0.1f, rr * 0.56f, look.digits, light)
                put(unit, cx, cy + rr * 0.2f, rr * 0.11f, TextDim)
                put(cons.value, cx - rr * 0.62f, at.values, rr * 0.17f, Color.White)
                put(km.value, cx + rr * 0.62f, at.values, rr * 0.17f, Color.White)
            }
        })
    }
}

// ---- Турбо ----

@Composable
private fun TurboPanel(m: Modifier) {
    val e = Repo.live.collectAsState()
    val trip = Repo.trip.collectAsState()
    val (lo, hi) = GaugeMath.boostScale(boostTop)
    val boost = remember(lo, hi) { derivedStateOf { ((e.value.gaugeKpa ?: 0f) / 100f).coerceIn(lo, hi) } }
    val anim = rememberSmooth(boost, hi - lo) { e.value.gaugeKpa != null }
    val value = rememberDigits { e.value.gaugeKpa?.let { "%+.2f".format(it / 100f) } ?: "—" }
    val cons = rememberDigits { trip.value.instPer100?.let { "%.1f".format(DistanceUnits.consumption(it)) } ?: "—" }
    val speed = rememberDigits { speedDigits(e.value.speedKmh) }
    val coolant = rememberDigits { orDash(e.value.coolantC) }
    val km = rememberDigits { trip.value.trip?.distanceKm?.let { "%.1f".format(DistanceUnits.distance(it)) } ?: "—" }
    val warnCol = C.Orange
    val coolRule = Warn.of("coolant")
    PanelCanvas(m, lo, hi, warnCol) { pen ->
        val w = size.width
        val h = size.height
        val at = GaugeLayout.turbo(w, h, density)
        val rr = at.r
        val cx = at.cx
        val cy = at.cy
        val detail = rr >= 95f * density
        val start = 135f
        val sw = 270f
        fun deg(bar: Float) = start + sw * ((bar - lo) / (hi - lo))
        fun pt(a: Float, r: Float) = Offset(cx + r * cos(rad(a)), cy + r * sin(rad(a)))
        val thin = Brush.radialGradient(listOf(Color(0xFF1A1E26), Color(0xFF0A0C10)), Offset(cx, cy), rr)
        val track = Stroke(rr * 0.05f)
        val tl = Offset(cx - rr * 0.97f, cy - rr * 0.97f)
        val sz = Size(rr * 1.94f, rr * 1.94f)
        val unit = tr("бар")
        val speedU = tr(DistanceUnits.speedUnit)
        val perU = tr(DistanceUnits.consumptionUnit)
        val kmU = tr(DistanceUnits.distanceUnit)
        val col = at.side
        val line = at.line
        // the four values: down the column, or across under the dial; a dial alone has none
        val valueSize = when {
            col != null -> minOf(rr * 0.2f, col.h / 4f * 0.42f, col.w * 0.26f)
            line != null -> minOf(line.h * 0.42f, line.w / 4f * 0.3f)
            else -> 0f
        }
        val capSize = valueSize * 0.46f
        val caption = capSize >= 7f * density
        fun slotX(i: Int) = col?.cx ?: line?.let { it.l + it.w * (i + 0.5f) / 4f } ?: 0f
        fun slotY(i: Int) = col?.let { GaugeLayout.columnY(it, i, 4) - (if (caption) capSize * 0.55f else 0f) } ?: line?.let { it.cy - (if (caption) capSize * 0.55f else 0f) } ?: 0f
        PanelDraw(static = {
            with(pen) {
                drawCircle(thin, rr, Offset(cx, cy))
                drawArc(Color(0xFF232A34), start, sw, false, tl, sz, style = track)
                // the bar scale outside, the vacuum part in blue
                var b = lo
                while (b <= hi + 0.001f) {
                    val a = deg(b)
                    val major = abs(b - Math.round(b * 2f) / 2f) < 0.001f
                    drawLine(if (b < 0f) Color(0xFF6FA8DC) else Color.White, pt(a, rr * 0.93f), pt(a, rr * 0.84f), rr * 0.018f)
                    if (if (detail) major else abs(b - Math.round(b)) < 0.001f) {
                        val p = pt(a, rr * 0.74f)
                        if (detail) put("%.1f".format(b), p.x, p.y, rr * 0.085f, if (b < 0f) Color(0xFF6FA8DC) else Color.White)
                        else put("%.0f".format(b), p.x, p.y, rr * 0.13f, if (b < 0f) Color(0xFF6FA8DC) else Color.White)
                    }
                    b += 0.5f
                }
                // PSI inside, from zero up
                if (detail) {
                    GaugeMath.psiMarks(hi).forEach { p ->
                        val a = deg(p / GaugeMath.PSI_PER_BAR)
                        val q = pt(a, rr * 0.58f)
                        put(p.toString(), q.x, q.y, rr * 0.07f, Color(0xFFFF6A5A))
                    }
                    put("PSI", cx + rr * 0.34f, cy + rr * 0.62f, rr * 0.07f, Color(0xFFFF6A5A))
                }
                put(unit, cx, cy + rr * 0.3f, rr * 0.1f, TextDim)
                if (caption) listOf(perU, speedU, "°C", kmU).forEachIndexed { i, u -> put(u, slotX(i), slotY(i) + valueSize * 0.62f + capSize * 0.5f, capSize, TextDim) }
            }
        }, live = {
            with(pen) {
                val bar = anim.value.floatValue
                val on = e.value.gaugeKpa != null
                if (on) {
                    drawArc(if (bar >= 0f) Color(0xFFFF3B2F) else Color(0xFF3F8BE8), deg(0f), deg(bar) - deg(0f), false, tl, sz, style = track)
                    drawLine(Color(0xFFFF3B2F), pt(deg(bar), rr * 0.5f), pt(deg(bar), rr * 0.96f), rr * 0.03f, cap = StrokeCap.Round)
                }
                put(value.value, cx, cy + rr * 0.02f, rr * 0.34f, Color.White, light)
                if (valueSize > 0f) {
                    put(cons.value, slotX(0), slotY(0), valueSize, Color.White)
                    put(speed.value, slotX(1), slotY(1), valueSize, Color(0xFF40E0D0))
                    put(coolant.value, slotX(2), slotY(2), valueSize, if (coolRule.hits(e.value.coolantC?.toFloat())) warnCol else Color.White)
                    put(km.value, slotX(3), slotY(3), valueSize, Color.White)
                }
            }
        })
    }
}

// ---- Крен и тангаж ----

/** A car from the side, nose to the left, standing on y = 0, in a box 2 wide. */
private fun sideCar(k: Float) = Path().apply {
    moveTo(-1f * k, -0.1f * k); lineTo(-1f * k, -0.3f * k); lineTo(-0.6f * k, -0.38f * k); lineTo(-0.3f * k, -0.68f * k); lineTo(0.5f * k, -0.68f * k)
    lineTo(0.9f * k, -0.4f * k); lineTo(1f * k, -0.3f * k); lineTo(1f * k, -0.1f * k); close()
    addOval(androidx.compose.ui.geometry.Rect(Offset(-0.58f * k, -0.14f * k), 0.17f * k))
    addOval(androidx.compose.ui.geometry.Rect(Offset(0.58f * k, -0.14f * k), 0.17f * k))
}

/** A car from behind, standing on y = 0. */
private fun rearCar(k: Float) = Path().apply {
    moveTo(-0.72f * k, -0.14f * k); lineTo(-0.72f * k, -0.56f * k); lineTo(-0.5f * k, -1.02f * k); lineTo(0.5f * k, -1.02f * k); lineTo(0.72f * k, -0.56f * k); lineTo(0.72f * k, -0.14f * k); close()
    moveTo(-0.72f * k, 0f); lineTo(-0.72f * k, -0.14f * k); lineTo(-0.42f * k, -0.14f * k); lineTo(-0.42f * k, 0f); close()
    moveTo(0.42f * k, 0f); lineTo(0.42f * k, -0.14f * k); lineTo(0.72f * k, -0.14f * k); lineTo(0.72f * k, 0f); close()
}

@Composable
private fun TiltPanel(m: Modifier) {
    val motion = Repo.motion.collectAsState()
    val e = Repo.live.collectAsState()
    val pitch = rememberSmooth(motion.slot { (it.pitchDeg ?: 0f).coerceIn(-GaugeMath.TILT_LIMIT, GaugeMath.TILT_LIMIT) }, 2 * GaugeMath.TILT_LIMIT) { motion.value.pitchDeg != null }
    val roll = rememberSmooth(motion.slot { (it.rollDeg ?: 0f).coerceIn(-GaugeMath.TILT_LIMIT, GaugeMath.TILT_LIMIT) }, 2 * GaugeMath.TILT_LIMIT) { motion.value.rollDeg != null }
    val pText = rememberDigits { motion.value.pitchDeg?.let { if (abs(it) < 0.5f) "0°" else "%+.0f°".format(it) } ?: "—" }
    val rText = rememberDigits { motion.value.rollDeg?.let { if (abs(it) < 0.5f) "0°" else "%+.0f°".format(it) } ?: "—" }
    val speed = rememberDigits { speedDigits(e.value.speedKmh) }
    PanelCanvas(m, "tilt") { pen ->
        val w = size.width
        val h = size.height
        val at = GaugeLayout.tilt(w, h, density)
        val rr = at.r
        val cl = Offset(at.leftX, at.cy)
        val cr = Offset(at.rightX, at.cy)
        val side = sideCar(rr * 0.56f)
        val rear = rearCar(rr * 0.5f)
        val stroke = Stroke(rr * 0.022f)
        val unit = tr(DistanceUnits.speedUnit)
        fun pt(c: Offset, a: Float, r: Float) = Offset(c.x + r * cos(rad(a)), c.y + r * sin(rad(a)))
        fun DrawScope.scale(c: Offset, mid: Float, labels: Pen) = with(labels) {
            for (k in -4..4) {
                val a = mid + k * 10f
                val major = k % 2 == 0
                val col = if (abs(k) == 4) Color(0xFFFF4A3A) else Color(0xFFE8EAEE)
                drawLine(col, pt(c, a, rr), pt(c, a, rr * (if (major) 0.9f else 0.95f)), rr * 0.018f)
                if (major && at.labels) {
                    val p = pt(c, a, rr * 1.13f)
                    put(abs(k * 10).toString(), p.x, p.y, rr * 0.1f, col)
                }
            }
        }
        fun DrawScope.marker(c: Offset, a: Float) {
            val tip = pt(c, a, rr * 0.9f)
            val l = pt(c, a - 3.5f, rr * 0.8f)
            val rg = pt(c, a + 3.5f, rr * 0.8f)
            drawLine(Color(0xFFFFC23D), tip, l, rr * 0.03f)
            drawLine(Color(0xFFFFC23D), tip, rg, rr * 0.03f)
        }
        PanelDraw(static = {
            scale(cl, 180f, pen)
            scale(cr, 0f, pen)
            drawLine(Color(0xFF232C36), Offset(w / 2f, at.cy - rr), Offset(w / 2f, at.cy + rr * 0.65f), 1f)
            with(pen) { if (at.labels) put(unit, w / 2f, at.unit, rr * 0.09f, TextDim) }
        }, live = {
            with(pen) {
                val p = pitch.value.floatValue
                val q = roll.value.floatValue
                val ground = rr * 0.42f
                rotate(p, Offset(cl.x, cl.y + ground)) {
                    translate(cl.x, cl.y + ground) { drawPath(side, Color(0xFF40E0D0), style = stroke) }
                    drawLine(Color(0xFF3B4A58), Offset(cl.x - rr * 0.66f, cl.y + ground), Offset(cl.x + rr * 0.66f, cl.y + ground), rr * 0.015f)
                }
                rotate(q, Offset(cr.x, cr.y + ground)) {
                    translate(cr.x, cr.y + ground) { drawPath(rear, Color(0xFF40E0D0), style = stroke) }
                    drawLine(Color(0xFF3B4A58), Offset(cr.x - rr * 0.66f, cr.y + ground), Offset(cr.x + rr * 0.66f, cr.y + ground), rr * 0.015f)
                }
                marker(cl, 180f + p)
                marker(cr, q)
                put(pText.value, cl.x, at.top, rr * 0.24f, Color.White, light)
                put(rText.value, cr.x, at.top, rr * 0.24f, Color.White, light)
                put(speed.value, w / 2f, at.speed, rr * 0.26f, Color.White, light)
            }
        })
    }
}

// ---- G-радар ----

private const val RADAR_SECTORS = 8
private const val RADAR_RINGS = 4
private const val RADAR_FULL_G = 1.2f

@Composable
private fun RadarPanel(m: Modifier) {
    val motion = Repo.motion.collectAsState()
    val e = Repo.live.collectAsState()
    val g = rememberDigits {
        val s = motion.value
        if (s.latG == null && s.lonG == null) "—" else "%.2f".format(sqrt((s.latG ?: 0f).let { it * it } + (s.lonG ?: 0f).let { it * it }))
    }
    val speed = rememberDigits { speedDigits(e.value.speedKmh) }
    val sector by motion.slot { GaugeMath.radarSector(it.latG ?: 0f, it.lonG ?: 0f, RADAR_SECTORS) }
    val rings by motion.slot { GaugeMath.radarRings(sqrt((it.latG ?: 0f).let { a -> a * a } + (it.lonG ?: 0f).let { a -> a * a }), RADAR_RINGS, RADAR_FULL_G) }
    PanelCanvas(m, "radar") { pen ->
        val w = size.width
        val h = size.height
        val at = GaugeLayout.radar(w, h)
        val c = Offset(at.cx, at.cy)
        val rx = at.rx
        val ry = at.ry
        val grid = Stroke(1.2f * density)
        val each = 360f / RADAR_SECTORS
        val unit = tr(DistanceUnits.speedUnit)
        fun rect(k: Int): Pair<Offset, Size> {
            val f = k.toFloat() / RADAR_RINGS
            return Offset(c.x - rx * f, c.y - ry * f) to Size(rx * f * 2, ry * f * 2)
        }
        val ovals = Array(RADAR_RINGS + 1) { rect(it) }
        val fills = Array(RADAR_RINGS + 1) { Color(0xFFE5232A).copy(alpha = 0.3f + 0.1f * it) }
        val pillW = at.pillW
        val pillH = at.pillH
        PanelDraw(static = {
            for (k in 1..RADAR_RINGS) {
                val (tl, sz) = ovals[k]
                drawOval(Color(0xFF2C3540), tl, sz, style = grid)
            }
            for (i in 0 until RADAR_SECTORS) {
                val a = rad(270f - each / 2f + i * each)
                drawLine(Color(0xFF2C3540), c, Offset(c.x + rx * cos(a), c.y + ry * sin(a)), grid.width)
            }
        }, live = {
            with(pen) {
                sector?.let { i ->
                    val from = 270f - each / 2f + i * each
                    val (tl, sz) = ovals[rings]
                    drawArc(fills[rings], from, each, true, tl, sz)
                }
                drawRoundRect(Color(0xF0050608), Offset(c.x - pillW / 2f, c.y - pillH / 2f), Size(pillW, pillH), CornerRadius(pillH / 2f))
                drawRoundRect(Color(0xFFE8EAEE), Offset(c.x - pillW / 2f, c.y - pillH / 2f), Size(pillW, pillH), CornerRadius(pillH / 2f), style = grid)
                put(g.value + " G", c.x, c.y - pillH * 0.16f, pillH * 0.34f, Color.White, light)
                put(speed.value + " " + unit, c.x, c.y + pillH * 0.26f, pillH * 0.2f, Color(0xFF40E0D0))
            }
        })
    }
}

// ---- G-сила ----

/** A thin circle with a cross of ticks, the dot moved by the force, the largest G each way round it, MAX and reset. */
@Composable
fun GForceCard(m: Modifier) {
    val motion = Repo.motion.collectAsState()
    val lat = rememberSmooth(motion.slot { (it.latG ?: 0f).coerceIn(-G_FULL, G_FULL) }, 2 * G_FULL)
    val lon = rememberSmooth(motion.slot { (it.lonG ?: 0f).coerceIn(-G_FULL, G_FULL) }, 2 * G_FULL)
    Panel(m, padding = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("G-сила", LocalCar.current.label, C.Sub, maxLines = 1, modifier = Modifier.weight(1f))
            T("Сбросить MAX", LocalCar.current.secondary, C.Text, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                .clickable { Motion.resetMax() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        PanelCanvas(Modifier.weight(1f).fillMaxWidth(), "gforce") { pen ->
            val w = size.width
            val h = size.height
            val at = GaugeLayout.force(w, h)
            val rr = at.r
            val c = Offset(at.cx, at.cy)
            val ring = Stroke(1.6f * density)
            PanelDraw(static = {
                drawCircle(Color(0xFFE8EAEE), rr, c, style = ring)
                for (k in 1..3) {
                    val d = rr * k * 0.25f
                    val t = rr * 0.05f
                    for (sgn in intArrayOf(-1, 1)) {
                        drawLine(Color(0xFFE8EAEE), Offset(c.x + sgn * d, c.y - t), Offset(c.x + sgn * d, c.y + t), ring.width)
                        drawLine(Color(0xFFE8EAEE), Offset(c.x - t, c.y + sgn * d), Offset(c.x + t, c.y + sgn * d), ring.width)
                    }
                }
            }, live = {
                with(pen) {
                    val s = motion.value
                    val sz = at.text
                    put("%.2f".format(s.maxAccel), c.x, c.y - rr - sz * 0.95f, sz, Color.White)
                    put("%.2f".format(s.maxBrake), c.x, c.y + rr + sz * 0.95f, sz, Color.White)
                    put("%.2f".format(s.maxLeft), c.x - rr - sz * 0.5f, c.y, sz, Color.White, plain, Paint.Align.RIGHT)
                    put("%.2f".format(s.maxRight), c.x + rr + sz * 0.5f, c.y, sz, Color.White, plain, Paint.Align.LEFT)
                    put("MAX", w - sz * 1.7f, sz, sz * 0.7f, TextDim)
                    if (s.latG != null || s.lonG != null) {
                        val (x, y) = GaugeMath.gDot(lat.value.floatValue, lon.value.floatValue, G_FULL)
                        val p = Offset(c.x + x * rr, c.y + y * rr)
                        drawCircle(Color(0x55FF5A2E), rr * 0.13f, p)
                        drawCircle(Color(0xFFFF3B1F), rr * 0.075f, p)
                    }
                }
            })
        }
    }
}

private const val G_FULL = 1.2f

// ---- Паутина ----

private class Spoke(val text: String, val label: String, val warn: Boolean)

@Composable
private fun WebPanel(m: Modifier) {
    val e = Repo.live.collectAsState()
    val turbo = Repo.carTurbo.collectAsState()
    val ctx = rememberMetricCtx()
    val altitude = metricSlot(ctx, "altitude") { Metrics.of("altitude", emptyList())?.read(it) }
    val speed = rememberDigits { speedDigits(e.value.speedKmh) }
    val clock = androidx.compose.runtime.produceState("") {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        while (true) { value = f.format(java.util.Date()); kotlinx.coroutines.delay(5000) }
    }
    val labels = remember { listOf(tr("ОЖ"), tr("АКБ"), tr("об/мин"), tr("бар"), tr("время"), tr("м")) }
    val coolRule = Warn.of("coolant")
    val battRule = Warn.of("battery")
    // from what is there: a value the car doesn't give leaves no empty spoke
    val spokes = remember {
        derivedStateOf {
        val s = e.value
        listOfNotNull(
            s.coolantC?.let { Spoke("$it°", labels[0], coolRule.hits(it.toFloat())) },
            s.batteryV?.let { Spoke("%.1f".format(it), labels[1], battRule.hits(it)) },
            s.rpm?.let { Spoke(it.toString(), labels[2], false) },
            s.gaugeKpa?.takeIf { turbo.value }?.let { Spoke("%+.2f".format(it / 100f), labels[3], false) },
            clock.value.takeIf { it.isNotEmpty() }?.let { Spoke(it, labels[4], false) },
            altitude.value?.let { Spoke("%.0f".format(it), labels[5], false) },
        )
        }
    }
    val warnCol = C.Orange
    PanelCanvas(m, "web", warnCol) { pen ->
        val w = size.width
        val h = size.height
        val at = GaugeLayout.web(w, h, density)
        val c = Offset(at.cx, at.cy)
        val rr = at.r
        val unit = tr(DistanceUnits.speedUnit)
        val line = Stroke(1.5f * density)
        PanelDraw(static = {
            with(pen) {
                drawCircle(Color(0xFF0C1218), rr, c)
                drawCircle(Color(0xFF3FB4F5), rr, c, style = line)
                put(unit, c.x, c.y + rr * 0.5f, rr * 0.17f, TextDim)
            }
        }, live = {
            with(pen) {
                put(speed.value, c.x, c.y - rr * 0.08f, rr * 0.7f, Color.White, light)
                val list = if (at.spokes) spokes.value else emptyList()
                list.forEachIndexed { i, s ->
                    val a = rad(GaugeMath.spokeDeg(i, list.size))
                    val ox = cos(a); val oy = sin(a)
                    val tx = c.x + ox * at.outX
                    val ty = c.y + oy * at.outY
                    // along the line to its value, from the disk's edge to before the text
                    val dx = tx - c.x; val dy = ty - c.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > rr * 1.5f) drawLine(Color(0xFF3FB4F5), Offset(c.x + dx / len * rr, c.y + dy / len * rr), Offset(c.x + dx / len * (len - rr * 0.5f), c.y + dy / len * (len - rr * 0.5f)), line.width)
                    put(s.text, tx, ty - rr * 0.1f, rr * 0.34f, if (s.warn) warnCol else Color.White)
                    put(s.label, tx, ty + rr * 0.22f, rr * 0.17f, TextDim)
                }
            }
        })
    }
}

// ---- PID ----

/** «Датчики» is that live list of raw values already, with every parameter of the unit: the panel is a way to it. */
@Composable
private fun PidShortcut(m: Modifier) {
    Panel(m.clickable { Repo.openScreen.value = System.nanoTime() to "Sensors" }, padding = 10.dp) {
        T("Параметры: список", LocalCar.current.label, C.Sub, maxLines = 1)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Lucide.ListChecks, null, tint = C.Violet, modifier = androidx.compose.ui.Modifier.padding(4.dp))
                T("Открыть экран «Датчики»", LocalCar.current.secondary, C.Text, maxLines = 2)
            }
        }
    }
}

// ---- Разгон и торможение ----

@Composable
private fun RunPanel(brake: Boolean, m: Modifier) {
    val perf by Repo.perf.collectAsState()
    val headline = PerfCalc.headline
    val run = remember(perf.runs, brake, headline) { perf.runs.firstOrNull { if (brake) it.braking else !it.braking && headline in it.results } }
    val measured = rememberMeasured(run, perf.intervals)
    val car = LocalCar.current
    Panel(m, padding = 10.dp) {
        val miles = DistanceUnits.miles
        val key = if (brake) PerfCalc.BRAKE else headline
        val hit = measured?.second?.hits?.firstOrNull { it.key == key }
        val series = measured?.second?.speed
        val dist = hit?.dist ?: if (hit != null && series != null) GaugeMath.distanceM(series.t, series.v, hit.from, hit.to) else null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(when {
                !miles -> if (brake) "Торможение 100–0" else "Разгон 0–100"
                brake -> "Торможение ${PerfCalc.brakeFromMiles()}–0 миль/ч"
                else -> "Разгон 0–60 миль/ч"
            }, car.label, C.Sub, maxLines = 1)
            T(hit?.let { "%.2f с".format(it.sec) } ?: "—", car.label, C.Text, FontWeight.SemiBold, maxLines = 1)
            T(dist?.let { "%.0f м".format(it) } ?: "—", car.label, C.Text, FontWeight.SemiBold, maxLines = 1)
        }
        if (hit == null || series == null) {
            val waiting = when {
                brake && miles -> "Ожидание: разгонитесь до ${PerfCalc.brakeFromMiles()} миль/ч и затормозите до остановки, замер поймается сам"
                brake -> "Ожидание: разгонитесь до 100 и затормозите до остановки, замер поймается сам"
                perf.running -> "Идёт замер"
                perf.armed -> "Ожидание старта: трогайтесь"
                else -> "Встаньте, чтобы засечь разгон"
            }
            FitText(waiting, car.secondary, Modifier.fillMaxWidth().weight(1f), C.Sub, maxLines = 3)
            if (brake) T("Время и путь считаются по скорости: точность ограничена частотой её данных", car.secondary, C.Sub, maxLines = 2)
        } else {
            val t0 = hit.from
            val t1 = hit.to + if (brake) 0.5 else 1.0
            // the chart in the shown unit, its red line at the run's own target
            val shownV = remember(series, miles) { DoubleArray(series.v.size) { DistanceUnits.speed(series.v[it]) } }
            val floorTop = if (miles) 80.0 else 120.0
            val target = if (miles) 60 else 100
            val top = if (brake) floorTop else maxOf(floorTop, shownV.maxOrNull() ?: floorTop)
            val points = remember(shownV, t0, t1, top) { GaugeMath.chart(series.t, shownV, t0, t1, top) }
            PanelCanvas(Modifier.weight(1f).fillMaxWidth(), points, t0, t1, top, target) { pen ->
                val w = size.width
                val h = size.height
                val left = h * 0.06f * 2.3f
                val bottom = h * 0.12f
                val pw = w - left - w * 0.02f
                val ph = h - bottom - h * 0.03f
                val curve = Path().apply {
                    var i = 0
                    while (i + 1 < points.size) {
                        val x = left + points[i] * pw
                        val y = h - bottom - points[i + 1] * ph
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        i += 2
                    }
                }
                val stroke = Stroke(2.2f * density)
                PanelDraw(static = {
                    with(pen) {
                        val sz = h * 0.06f
                        var v = 0
                        while (v <= top) {
                            val y = h - bottom - (v / top).toFloat() * ph
                            drawLine(if (v == target) Color(0xFFE5232A) else Color(0xFF232C36), Offset(left, y), Offset(left + pw, y), 1f * density)
                            put(v.toString(), left - sz * 0.3f, y, sz, TextDim, plain, Paint.Align.RIGHT)
                            v += 20
                        }
                        val span = (t1 - t0).toFloat()
                        val every = if (span > 12f) 2 else 1
                        var s = 0
                        while (s <= span) {
                            val x = left + s / span * pw
                            drawLine(Color(0xFF1A2028), Offset(x, h - bottom), Offset(x, h - bottom - ph), 1f)
                            put(s.toString(), x, h - bottom * 0.45f, sz, TextDim)
                            s += every
                        }
                        drawPath(curve, Color(0xFFFFC23D), style = stroke)
                    }
                }, live = {})
            }
            run?.source?.takeIf { it.isNotEmpty() }?.let { src ->
                T("Точность ограничена частотой данных скорости: ${tr(src)}", car.secondary, C.Sub, maxLines = 1)
            }
        }
    }
}
