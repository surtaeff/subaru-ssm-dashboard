package kg.forester.sg.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kg.forester.sg.data.DeviceTier
import kg.forester.sg.data.DistanceUnits
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Tier
import kg.forester.sg.data.tr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The numbers of a design's scale. THE ONE PLACE the designs take them from: the driver's own marks instead of the usual
 * steps, as on the plain dial (none of them on this scale: the usual ones); the size factor is in [designNumeralScale].
 */
internal fun designLabels(st: DialStyle, min: Float, max: Float, step: Float, labelDiv: Float, all: Boolean): List<Pair<Float, String>> {
    val own = if (st.marks.isEmpty()) st.marks else DialOptions.onScale(st.marks, min, max)
    if (own.isNotEmpty()) return own.map { it to DialOptions.label(it, labelDiv) }
    return GaugeMath.labelSteps(min, max, step, all).map { it to GaugeMath.labelText(it, step, labelDiv) }
}

/** See [designLabels]: how much bigger the numbers are by the driver's own choice. */
internal fun designNumeralScale(st: DialStyle): Float = st.numberScale

/** A dial shown as a picture of a design, not as a reading: its indicators show samples. */
internal val LocalDialPreview = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The indicators from the app's real sources, read while drawing: a new value redraws the dial and recomposes nothing. */
@Composable
internal fun rememberGaugeInds(): State<GaugeInds> {
    // a picture in a chooser or the designer: a design looks as designed, whatever this car knows
    if (LocalDialPreview.current) {
        val km = tr(DistanceUnits.distanceUnit)
        val volt = tr("В")
        val litre = tr("л")
        return remember(km, volt, litre) { mutableStateOf(GaugeMath.sampleInds(km, volt, litre)) }
    }
    val e = Repo.live.collectAsState()
    val trip = Repo.trip.collectAsState()
    val tcu = Repo.tcu.collectAsState()
    val codes = Repo.checkEngine.collectAsState()
    val link = Repo.link.collectAsState()
    val gear = rememberLensGear()
    val clock = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        while (true) { clock.value = f.format(java.util.Date()); kotlinx.coroutines.delay(5000) }
    }
    val version = Repo.maintenanceVersion.collectAsState()
    val data = Repo.dataVersion.collectAsState()
    // from the trips table: off the main thread, and only when trips or the plan change
    val odo = produceState<Double?>(null, version.value, data.value) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { kg.forester.sg.service.Maintenance.odometer() }.getOrNull() }
    }
    val km = remember(DistanceUnits.miles) { tr(DistanceUnits.distanceUnit) }
    val volt = remember { tr("В") }
    val litre = remember { tr("л") }
    return remember(km) {
        derivedStateOf {
            val live = e.value
            val t = trip.value
            GaugeMath.indicators(
                IndInputs(
                    fuelLeftL = t.fuelLeftL, tankL = GaugeMath.shownTank(Repo.settings.tankL, Repo.demoOn.value), rangeKm = t.rangeKm,
                    volts = live.batteryV, coolantC = live.coolantC,
                    gear = gear(), selector = tcu.value?.range,
                    tripKm = t.trip?.distanceKm, odoKm = odo.value,
                    codes = codes.value, connected = link.value == LinkState.CONNECTED, clock = clock.value,
                    km = km, volt = volt, litre = litre,
                ),
                Warn.of("battery"), Warn.of("coolant"), Warn.of("range"),
            )
        }
    }
}

/**
 * A gauge with built-in indicators: what stands still is one offscreen layer per size, a frame draws that picture, then the
 * needle or arc, the digits and the indicators, with what they need kept in [DesignArt]. Nothing here recomposes with a value.
 */
@Composable
internal fun DesignDial(
    d: GaugeDesign, value: State<Float?>, max: Float, step: Float, labelDiv: Float, unit: String, redFrom: Float?,
    modifier: Modifier, style: DialStyle, min: Float, format: String, warn: WarnRule?,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val cardW = if (constraints.hasBoundedWidth) maxWidth else side
        val cardH = if (constraints.hasBoundedHeight) maxHeight else side
        val parts = remember(side) { GaugeMath.parts(side.value) }
        val low = DeviceTier.current.collectAsState().value == Tier.LOW
        val reading = remember(value, min, max) { derivedStateOf { (value.value ?: min).coerceIn(min, max) } }
        val anim = rememberSmooth(reading, max - min) { value.value != null }
        val digits = rememberDigits { value.value?.let { format.format(it) } ?: "—" }
        if (GaugeLayout.tooShortForDial(cardW.value, cardH.value)) {
            ShortCardValue(digits, unit, anim, min, max, value, warn, redFrom, style, modifier = Modifier.size(cardW, cardH))
            return@BoxWithConstraints
        }
        val inds = rememberGaugeInds()
        val ctx = LocalContext.current
        val lcd = remember { runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, kg.forester.sg.R.font.dseg7_bold_italic) }.getOrNull() }
        val warnRule = warn ?: NoRule
        val unitShown = remember(unit) { tr(unit) }
        val speedCard = DistanceUnits.isSpeedUnit(unit)
        val warnCol = C.Orange
        val art = remember(d, style, parts, low, min, max, step, labelDiv, redFrom, warnRule, unitShown, speedCard, lcd, warnCol, reading, anim) {
            Modifier.drawWithCache {
                val a = DesignArt(d, style, min, max, step, labelDiv, redFrom, size, density, parts, low, lcd, unitShown, speedCard, warnCol)
                val base = obtainGraphicsLayer().apply {
                    compositingStrategy = CompositingStrategy.Offscreen
                    record { with(a) { base() } }
                }
                onDrawBehind {
                    // half a pixel at the needle's tip: a reading trembling less than that costs no frame
                    anim.quantum = (max - min) * 0.5f / (size.minDimension * 1.9f).coerceAtLeast(1f)
                    drawLayer(base)
                    val v = value.value
                    val hot = warnRule.hits(v) || (redFrom != null && v != null && v >= redFrom)
                    with(a) { live(GaugeMath.share(anim.value.floatValue, min, max), v != null, hot, digits.value, inds.value) }
                }
            }
        }
        Box(Modifier.size(cardW, cardH).graphicsLayer().then(art))
    }
}

private val NoRule = WarnRule()

private const val WIDTHS = 8

/** A row card too low for any dial to be read: the number with its unit, and the same travel as a line beside it. */
@Composable
private fun ShortCardValue(
    digits: State<String>, unit: String, anim: Smooth, min: Float, max: Float, value: State<Float?>, warn: WarnRule?, redFrom: Float?, style: DialStyle, modifier: Modifier,
) {
    val hot by remember(warn, redFrom) { derivedStateOf { val v = value.value; (warn ?: NoRule).hits(v) || (redFrom != null && v != null && v >= redFrom) } }
    val text = if (unit.isEmpty()) digits.value else digits.value + " " + tr(unit)
    androidx.compose.foundation.layout.Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(text, LocalCar.current.label, Modifier.weight(1f), if (hot) C.Orange else C.Text, androidx.compose.ui.text.font.FontWeight.SemiBold)
        Box(Modifier.padding(start = 8.dp).weight(1f).height(6.dp).drawBehind {
            anim.quantum = 0.5f / size.width.coerceAtLeast(1f)
            val round = CornerRadius(size.height / 2f)
            drawRoundRect(C.Border, cornerRadius = round)
            drawRoundRect(if (hot) C.Orange else style.needle, size = Size(size.width * GaugeMath.share(anim.value.floatValue, min, max), size.height), cornerRadius = round)
        })
    }
}

/** What the designs draw, for one size and one set of colours; made once per cache, its paths, paints and strokes are reused on every frame. */
internal class DesignArt(
    private val d: GaugeDesign, private val st: DialStyle, private val min: Float, private val max: Float, private val step: Float, private val labelDiv: Float,
    private val redFrom: Float?, size: Size, private val px: Float, private val parts: DesignParts, private val low: Boolean, lcd: Typeface?, private val unit: String,
    private val speedCard: Boolean, private val warnCol: Color,
) {
    private val w = size.width
    private val h = size.height
    private val layout = GaugeLayout.design(d, w, h, px)
    private val column = layout.split.side
    private val under = layout.split.line
    private val r = layout.split.r
    private val big = r / 0.98f
    private val cx = layout.split.cx
    private val cy = layout.split.cy
    private val c = Offset(cx, cy)
    private val mode = st.numberMode()
    private val sweep = st.sweep
    private val redShare = redFrom?.let { GaugeMath.share(it, min, max) }
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val plain: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val segment: Typeface = lcd ?: bold
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT; style = Paint.Style.STROKE }
    private val arcR = r * when (d) { GaugeDesign.HUD_AMBER -> 0.56f; GaugeDesign.BLUE_GLOW -> 0.94f; else -> 0.9f }
    private val arcTl = Offset(cx - arcR, cy - arcR)
    private val arcSize = Size(arcR * 2, arcR * 2)
    private val arcStroke = Stroke(r * (if (d == GaugeDesign.BLUE_GLOW) 0.04f else 0.085f), cap = StrokeCap.Round)
    private val arcGlow = Stroke(r * 0.16f, cap = StrokeCap.Round)
    private val arcBrush = Brush.sweepGradient(st.arc ?: listOf(st.needle, st.needle), c)
    private val hotBrush = Brush.linearGradient(listOf(C.Orange, C.Red), Offset(cx - r, cy), Offset(cx + r, cy))
    private val sweepTl = Offset(cx - r * 0.72f, cy - r * 0.72f)
    private val sweepSize = Size(r * 1.44f, r * 1.44f)
    private val ring = Segments(if (d == GaugeDesign.HUD_AMBER) 72 else 0, r * 0.88f, r * 0.995f)
    private val glyphPx = if (column != null) layout.rowText * 1.15f else big * 0.13f
    private val thinLine = Stroke(big * 0.012f)
    private val hairLine = Stroke(big * 0.006f)
    private val dimNeedle = st.needle.copy(alpha = 0.35f)
    private val windowTl = Offset(cx - r * 0.33f, cy + r * 0.4f)
    private val windowSize = Size(r * 0.66f, r * 0.22f)
    private val windowCorner = CornerRadius(r * 0.03f)
    private val glyphs = Glyphs(glyphPx)
    private val glyphStroke = Stroke(glyphPx / 12f, cap = StrokeCap.Round)

    /** Radial bars, already worked out: lit up to a share by drawing the first of them. */
    private inner class Segments(val n: Int, ri: Float, ro: Float) {
        val pts = FloatArray(n * 4).also { p ->
            for (i in 0 until n) {
                val a = rad(GaugeMath.startDeg(sweep) + sweep * (i + 0.5f) / n)
                p[i * 4] = cx + ri * cos(a); p[i * 4 + 1] = cy + ri * sin(a)
                p[i * 4 + 2] = cx + ro * cos(a); p[i * 4 + 3] = cy + ro * sin(a)
            }
        }
    }

    private fun rad(deg: Float) = deg * (PI / 180.0).toFloat()
    private fun at(deg: Float, rr: Float) = Offset(cx + rr * cos(rad(deg)), cy + rr * sin(rad(deg)))
    private fun ang(s: Float) = GaugeMath.startDeg(sweep) + sweep * s

    private fun DrawScope.put(t: String, x: Float, y: Float, sz: Float, col: Color, face: Typeface = plain, align: Paint.Align = Paint.Align.CENTER) {
        if (t.isEmpty()) return
        text.typeface = face; text.textSize = sz; text.color = col.toArgb(); text.textAlign = align
        drawContext.canvas.nativeCanvas.drawText(t, x, y + sz * 0.35f, text)
    }

    // the few strings a design writes, drawn each frame and changing a few times a second: their widths aren't measured again
    private val widthT = arrayOfNulls<String>(WIDTHS)
    private val widthFace = arrayOfNulls<Typeface>(WIDTHS)
    private val widthSz = FloatArray(WIDTHS)
    private val widthPx = FloatArray(WIDTHS)
    private var widthNext = 0

    private fun widthOf(t: String, sz: Float, face: Typeface): Float {
        for (i in 0 until WIDTHS) if (widthSz[i] == sz && widthFace[i] === face && widthT[i] == t) return widthPx[i]
        text.typeface = face; text.textSize = sz
        val w = text.measureText(t)
        val i = widthNext
        widthNext = (i + 1) % WIDTHS
        widthT[i] = t; widthFace[i] = face; widthSz[i] = sz; widthPx[i] = w
        return w
    }

    /** [t] in a box at most [most] wide, its size lowered only if it won't fit. */
    private fun fit(t: String, sz: Float, most: Float, face: Typeface): Float {
        val tw = widthOf(t, sz, face)
        return if (tw > most) sz * most / tw else sz
    }

    /** The card's number in the place a design has for it, as the driver's «Цифры в центре» says. */
    private fun DrawScope.number(t: String, x: Float, y: Float, sz: Float, col: Color, face: Typeface, most: Float) {
        val shown = fit(t, sz, most, face)
        val ro = st.readout
        when {
            mode == NumberMode.LCD && ro != null -> {
                val f = if (t.length == 1 && t[0] == '—') plain else segment
                val s2 = fit(t, shown * 0.82f, most, f)
                val bw = widthOf(t, s2, f) + s2 * 0.7f
                val bh = s2 * 1.5f
                drawRoundRect(ro.bg, Offset(x - bw / 2f, y - bh / 2f), Size(bw, bh), CornerRadius(bh * 0.14f))
                drawRoundRect(ro.border, Offset(x - bw / 2f, y - bh / 2f), Size(bw, bh), CornerRadius(bh * 0.14f), style = hairLine)
                put(t, x, y, s2, ro.fg, f)
            }
            mode == NumberMode.FRAME && ro != null -> {
                val bw = widthOf(t, shown, face) + shown * 0.7f
                val bh = shown * 1.45f
                drawRoundRect(ro.bg, Offset(x - bw / 2f, y - bh / 2f), Size(bw, bh), CornerRadius(bh * 0.14f))
                drawRoundRect(ro.border, Offset(x - bw / 2f, y - bh / 2f), Size(bw, bh), CornerRadius(bh * 0.14f), style = hairLine)
                put(t, x, y, shown, col, face)
            }
            else -> put(t, x, y, shown, col, face)
        }
    }

    /** A number with its unit small beside it, the pair centred on [x]. */
    private fun DrawScope.pair(t: String, x: Float, y: Float, sz: Float, col: Color, face: Typeface, unitSz: Float, unitCol: Color, most: Float) {
        val uw = if (unit.isEmpty()) 0f else widthOf(unit, unitSz, plain) + unitSz * 0.4f
        val shown = fit(t, sz, (most - uw).coerceAtLeast(most * 0.5f), face)
        val tw = widthOf(t, shown, face)
        val left = x - (tw + uw) / 2f
        put(t, left, y, shown, col, face, Paint.Align.LEFT)
        if (uw > 0f) put(unit, left + tw + unitSz * 0.4f, y + (shown - unitSz) * 0.35f, unitSz, unitCol, plain, Paint.Align.LEFT)
    }

    /** The ticks and the numbers of the scale. */
    private fun DrawScope.scale(rOut: Float, major: Float, minor: Float, rNum: Float, numSize: Float, col: Color = st.numbers) {
        val minorStep = step / 2f
        var i = 0
        while (true) {
            val x = min + minorStep * i
            if (x > max + minorStep * 0.01f) break
            val isMajor = i % 2 == 0
            i++
            val s = GaugeMath.share(x, min, max)
            val a = ang(s)
            val inRed = redShare != null && s >= redShare
            val tick = if (inRed) st.red else if (isMajor) st.tickMajor else st.tickMinor
            drawLine(tick, at(a, rOut), at(a, rOut - (if (isMajor) major else minor)), if (isMajor) big * 0.016f else big * 0.009f)
        }
        val sz = numSize * designNumeralScale(st)
        designLabels(st, min, max, step, labelDiv, parts.allLabels).forEach { (x, label) ->
            val s = GaugeMath.share(x, min, max)
            val p = at(ang(s), rNum)
            put(label, p.x, p.y, sz, if (redShare != null && s >= redShare) st.red else col, if (st.italic) light else plain)
        }
    }

    private fun DrawScope.disc(rr: Float, col: Color, stroke: Float? = null) {
        if (stroke != null) drawCircle(col, rr, c, style = Stroke(stroke)) else drawCircle(col, rr, c)
    }

    /** What stands still. */
    fun DrawScope.base() {
        // text beside or under the dial is meant for the dark, whatever the theme: a plate that fills the card
        if (layout.plate) drawRoundRect(st.face.last(), Offset.Zero, this@DesignArt.run { Size(w, h) }, CornerRadius(minOf(w, h) * 0.05f))
        drawCircle(Brush.radialGradient(st.face, c, r), r, c)
        when (d) {
            GaugeDesign.HUD_AMBER -> {
                seg.color = st.track.toArgb(); seg.strokeWidth = big * 0.016f
                drawContext.canvas.nativeCanvas.drawLines(ring.pts, seg)
                disc(r * 0.84f, st.rim, big * 0.007f)
                scale(r * 0.82f, r * 0.05f, r * 0.03f, r * 0.69f, r * 0.095f)
                drawArc(st.track, ang(0f), sweep, false, arcTl, arcSize, style = arcStroke)
                if (redShare != null) drawArc(st.red.copy(alpha = 0.5f), ang(redShare), sweep * (1f - redShare), false, arcTl, arcSize, style = Stroke(r * 0.03f))
            }
            GaugeDesign.HUD_BLUE -> {
                disc(r * 0.995f, st.rim, big * 0.012f)
                disc(r * 0.965f, st.rim.copy(alpha = 0.35f), big * 0.006f)
                scale(r * 0.93f, r * 0.08f, r * 0.04f, r * 0.74f, r * 0.105f)
                if (!low) drawCircle(Brush.radialGradient(listOf(st.rim.copy(alpha = 0.35f), Color.Transparent), c, r * 0.46f), r * 0.46f, c)
                disc(r * 0.3f, st.rim.copy(alpha = 0.6f), big * 0.008f)
                // the pill is only there with something in it
                if (parts.window && !st.hideValue && mode == NumberMode.DESIGN) {
                    drawRoundRect(Color(0xE6050C18), Offset(cx - r * 0.34f, cy + r * 0.38f), Size(r * 0.68f, r * 0.22f), CornerRadius(r * 0.11f))
                    drawRoundRect(st.rim, Offset(cx - r * 0.34f, cy + r * 0.38f), Size(r * 0.68f, r * 0.22f), CornerRadius(r * 0.11f), style = Stroke(big * 0.008f))
                }
            }
            GaugeDesign.RED_LINE -> {
                disc(r * 0.995f, st.rim, big * 0.012f)
                disc(r * 0.3f, st.rim.copy(alpha = 0.35f), big * 0.006f)
                if (!low) drawArc(Brush.radialGradient(listOf(Color.Transparent, Color(0x12FFFFFF), Color.Transparent), c, r * 0.9f), 280f, 70f, true, Offset(cx - r * 0.9f, cy - r * 0.9f), Size(r * 1.8f, r * 1.8f))
                scale(r * 0.93f, r * 0.08f, r * 0.04f, r * 0.74f, r * 0.105f)
            }
            GaugeDesign.BLUE_GLOW -> {
                disc(r * 0.995f, st.rim.copy(alpha = 0.5f), big * 0.01f)
                drawArc(st.track, ang(0f), sweep, false, arcTl, arcSize, style = arcStroke)
                scale(r * 0.87f, r * 0.08f, r * 0.04f, r * 0.68f, r * 0.11f)
                // the dashed ring round the centre
                for (i in 0 until 16) drawArc(st.rim, i * 22.5f + 4f, 14f, false, Offset(cx - r * 0.34f, cy - r * 0.34f), Size(r * 0.68f, r * 0.68f), style = Stroke(r * 0.05f))
            }
            GaugeDesign.CHROME -> {
                // the bezel: a metal ring catching light twice on its way round
                drawCircle(
                    Brush.sweepGradient(listOf(Color(0xFFF2F4F7), Color(0xFF5B6068), Color(0xFFDDE1E6), Color(0xFF3A3D42), Color(0xFFF2F4F7), Color(0xFF6F747C), Color(0xFFF2F4F7)), c),
                    r, c, style = Stroke(r * 0.05f),
                )
                drawCircle(Brush.radialGradient(st.face, c, r * 0.95f), r * 0.95f, c)
                if (redShare != null) drawArc(st.red, ang(redShare), sweep * (1f - redShare), false, Offset(cx - r * 0.86f, cy - r * 0.86f), Size(r * 1.72f, r * 1.72f), style = Stroke(r * 0.05f))
                scale(r * 0.9f, r * 0.1f, r * 0.05f, r * 0.68f, r * 0.13f)
            }
            GaugeDesign.DOUBLE -> {
                drawCircle(
                    Brush.sweepGradient(listOf(Color(0xFFE4E7EB), Color(0xFF55595F), Color(0xFFD0D4D9), Color(0xFF44474C), Color(0xFFE4E7EB)), c),
                    r, c, style = Stroke(r * 0.035f),
                )
                drawCircle(Brush.radialGradient(st.face, c, r * 0.965f), r * 0.965f, c)
                halfScale(GaugeMath.FUEL_E_DEG, GaugeMath.FUEL_SWEEP, "E", "F", null)
                halfScale(GaugeMath.COOL_C_DEG, GaugeMath.COOL_SWEEP, "C", "H", GaugeMath.share(Warn.of("coolant").above ?: GaugeMath.COOLANT_HOT, GaugeMath.COOLANT_COLD, GaugeMath.COOLANT_HOT))
                if (parts.indicators) {
                    translate(cx - r * 0.1f, cy - r * 0.46f) { drawPath(glyphs.fuel, st.tickMinor, style = glyphStroke) }
                    translate(cx - r * 0.1f, cy + r * 0.28f) { drawPath(glyphs.coolant, st.tickMinor, style = glyphStroke) }
                }
            }
            GaugeDesign.SPORT_WHITE -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF05060A), Color(0xFF090A10)), c, r * 0.72f), r * 0.72f, c)
                val wide = r * 0.26f
                val mid = r * 0.86f
                val ringStroke = Stroke(wide)
                val tl = Offset(cx - mid, cy - mid)
                val sz = Size(mid * 2, mid * 2)
                // the ring goes on a little past both ends of the scale, for the first and last numbers to stand on it
                val past = 8f
                drawArc(st.tickMinor, ang(0f) - past, sweep + past * 2, false, tl, sz, style = ringStroke)
                if (redShare != null) drawArc(st.red, ang(redShare), sweep * (1f - redShare) + past, false, tl, sz, style = ringStroke)
                drawArc(Color(0xFF2B2D33), ang(1f) + past, 360f - sweep - past * 2, false, tl, sz, style = ringStroke)
                val minorStep = step / 2f
                var i = 0
                while (true) {
                    val x = min + minorStep * i
                    if (x > max + minorStep * 0.01f) break
                    val a = ang(GaugeMath.share(x, min, max))
                    if (i % 2 == 0) drawLine(Color(0xFF111217), at(a, r * 0.98f), at(a, r * 0.92f), big * 0.016f)
                    else drawLine(Color(0x88111217), at(a, r * 0.98f), at(a, r * 0.95f), big * 0.008f)
                    i++
                }
                val marks = designLabels(st, min, max, step, labelDiv, parts.allLabels)
                // three digits on a ring as wide as this one take a smaller size
                val sz2 = r * (if (marks.any { it.second.length > 2 }) 0.105f else 0.15f) * designNumeralScale(st)
                marks.forEach { (x, label) ->
                    val p = at(ang(GaugeMath.share(x, min, max)), r * 0.82f)
                    put(label, p.x, p.y, sz2, st.bandText, bold)
                }
            }
        }
    }

    private fun DrawScope.halfScale(start: Float, sw: Float, a: String, b: String, redAt: Float?) {
        for (i in 0..8) {
            val s = i / 8f
            val deg = start + sw * s
            val major = i % 4 == 0
            val col = if (redAt != null && s >= redAt) st.red else if (major) st.tickMajor else st.tickMinor
            drawLine(col, at(deg, r * 0.9f), at(deg, r * (if (major) 0.79f else 0.84f)), if (major) big * 0.016f else big * 0.009f)
        }
        if (redAt != null && redAt < 1f) {
            drawArc(st.red, start + sw * redAt, sw * (1f - redAt), false, Offset(cx - r * 0.9f, cy - r * 0.9f), Size(r * 1.8f, r * 1.8f), style = Stroke(r * 0.05f))
        }
        val pa = at(start, r * 0.68f)
        val pb = at(start + sw, r * 0.68f)
        put(a, pa.x, pa.y, r * 0.13f, st.numbers, bold)
        put(b, pb.x, pb.y, r * 0.13f, st.numbers, bold)
    }

    private fun DrawScope.needle(s: Float, from: Float, to: Float, col: Color, width: Float) {
        val a = ang(s)
        if (!low) drawLine(col.copy(alpha = 0.3f), at(a, from), at(a, to), width * 2.4f, cap = StrokeCap.Round)
        drawLine(col, at(a, from), at(a, to), width, cap = StrokeCap.Round)
    }

    private fun DrawScope.hub(rr: Float, col: Color) {
        drawCircle(col, rr, c)
        drawCircle(Color(0x33FFFFFF), rr * 0.4f, Offset(cx - rr * 0.25f, cy - rr * 0.25f))
    }

    private fun DrawScope.glyph(p: Path, x: Float, y: Float, col: Color) {
        translate(x, y) { drawPath(p, col, style = glyphStroke) }
    }

    /** A row of icon and text, the two together centred on [x]. */
    private fun DrawScope.ind(p: Path, i: GaugeInd?, x: Float, y: Float, sz: Float) {
        if (i == null) return
        val col = if (i.warn) warnCol else st.unit
        val size = maxOf(sz, 10f * px)
        text.typeface = plain; text.textSize = size
        val gap = glyphPx * 0.25f
        val left = x - (glyphPx + gap + text.measureText(i.text)) / 2f
        glyph(p, left, y - glyphPx / 2f, col)
        put(i.text, left + glyphPx + gap, y, size, if (i.warn) warnCol else st.value, plain, Paint.Align.LEFT)
    }

    /** How many rows the column has in it now. */
    private fun rows(i: GaugeInds): Int {
        var n = 0
        if (i.check != null) n++
        if (i.coolant != null) n++
        if (i.fuel != null) n++
        if (i.battery != null) n++
        if (i.selector != null || i.gear != null) n++
        return n.coerceAtMost(layout.rows)
    }

    /** The column's rows from [top], the most urgent first; [n] of them, as [rows] said. */
    private fun DrawScope.columnRows(i: GaugeInds, x: Float, top: Float, n: Int) {
        var y = top + layout.rowH / 2f
        var left = n
        if (left > 0 && i.check != null) { check(i.check, x, y); y += layout.rowH; left-- }
        if (left > 0 && i.coolant != null) { ind(glyphs.coolant, i.coolant, x, y, layout.rowText); y += layout.rowH; left-- }
        if (left > 0 && i.fuel != null) { ind(glyphs.fuel, i.fuel, x, y, layout.rowText); y += layout.rowH; left-- }
        if (left > 0 && i.battery != null) { ind(glyphs.battery, i.battery, x, y, layout.rowText); y += layout.rowH; left-- }
        if (left > 0) {
            if (i.selector != null) strip(i, x, y, layout.rowText * 1.1f)
            else i.gear?.let { put(it.text, x, y, layout.rowText * 1.3f, st.value, bold) }
        }
    }

    /** The compact line under the dial on a tall card: as many as go in, evenly. */
    private fun DrawScope.lineRow(i: GaugeInds, box: LayoutBox) {
        val most = (box.w / (44f * px)).toInt().coerceIn(1, 3)
        var n = 0
        if (i.check != null) n++
        if (i.coolant != null) n++
        if (i.fuel != null) n++
        if (i.battery != null) n++
        n = n.coerceAtMost(most)
        if (n == 0) return
        val sz = minOf(box.h * 0.5f, box.w / n * 0.2f)
        var k = 0
        fun x(at: Int) = box.l + box.w * (at + 0.5f) / n
        if (k < n && i.check != null) { check(i.check, x(k), box.cy); k++ }
        if (k < n && i.coolant != null) { ind(glyphs.coolant, i.coolant, x(k), box.cy, sz); k++ }
        if (k < n && i.fuel != null) { ind(glyphs.fuel, i.fuel, x(k), box.cy, sz); k++ }
        if (k < n && i.battery != null) { ind(glyphs.battery, i.battery, x(k), box.cy, sz); k++ }
    }

    private fun DrawScope.check(i: GaugeInd?, x: Float, y: Float) {
        if (i == null) return
        glyph(glyphs.engine, x - glyphPx / 2f, y - glyphPx / 2f, warnCol)
        if (i.text.isNotEmpty()) put(i.text, x + glyphPx * 0.8f, y, glyphPx * 0.8f, warnCol, bold)
    }

    /** A bar with an icon under it, how full a real value is. */
    private fun DrawScope.miniBar(p: Path, i: GaugeInd?, x: Float, y: Float, bh: Float) {
        val bw = r * 0.09f
        drawRoundRect(st.track, Offset(x - bw / 2f, y), Size(bw, bh), CornerRadius(bw * 0.25f))
        if (i == null) return
        val col = if (i.warn) warnCol else st.arc?.last() ?: st.needle
        val f = (i.share ?: 0f).coerceIn(0f, 1f)
        if (f > 0f) drawRoundRect(col, Offset(x - bw / 2f, y + bh * (1f - f)), Size(bw, bh * f), CornerRadius(bw * 0.25f))
        glyph(p, x - glyphPx / 2f, y + bh + r * 0.02f, if (i.warn) warnCol else st.unit)
    }

    /** R N D: lit by what the gearbox unit reports, the gear itself beside it; drawn only when the unit tells the selector. */
    private fun DrawScope.strip(i: GaugeInds, x: Float, y: Float, sz: Float) {
        val sel = i.selector ?: return
        put("R", x - sz * 1.1f, y, sz, if (GaugeMath.selectorLit("R", sel.text)) st.value else st.tickMinor, bold)
        put("N", x, y, sz, if (GaugeMath.selectorLit("N", sel.text)) st.value else st.tickMinor, bold)
        put("D", x + sz * 1.1f, y, sz, if (GaugeMath.selectorLit("D", sel.text)) st.value else st.tickMinor, bold)
    }

    /** Inside the dial, or on the line under it: what a card without a column still has room for. */
    private fun DrawScope.onDial(i: GaugeInds) {
        if (under != null) { lineRow(i, under); return }
        if (!parts.indicators) return
        val y = cy + r * 0.82f
        ind(glyphs.coolant, i.coolant, cx - r * 0.3f, y, r * 0.07f)
        ind(glyphs.fuel, i.fuel, cx + r * 0.3f, y, r * 0.07f)
        check(i.check, cx, cy - r * 0.46f)
    }

    /** What moves, each frame: nothing is made here. */
    fun DrawScope.live(s: Float, known: Boolean, hot: Boolean, digits: String, i: GaugeInds) {
        val valueCol = if (hot) warnCol else st.value
        val showValue = !st.hideValue
        val ownSize = designNumeralScale(st).coerceAtMost(1.3f) // as the plain dial's readout
        when (d) {
            GaugeDesign.HUD_AMBER -> {
                val n = GaugeMath.lit(s, ring.n)
                if (known && n > 0) {
                    seg.color = (if (hot) C.Red else st.red).toArgb(); seg.strokeWidth = big * 0.02f
                    drawContext.canvas.nativeCanvas.drawLines(ring.pts, 0, n * 4, seg)
                }
                if (known && s > 0.002f) {
                    if (!low) st.glow?.let { drawArc(it.copy(alpha = 0.22f), ang(0f), sweep * s, false, arcTl, arcSize, style = arcGlow) }
                    drawArc(if (hot) hotBrush else arcBrush, ang(0f), sweep * s, false, arcTl, arcSize, style = arcStroke)
                }
                if (showValue) {
                    number(digits, cx, cy - r * 0.1f, (r * 0.34f) * ownSize, valueCol, light, r * 0.92f)
                    put(unit, cx, cy + r * 0.11f, r * 0.09f, st.unit)
                }
                if (parts.indicators) {
                    val y = cy + r * 0.2f
                    val bh = r * 0.16f
                    miniBar(glyphs.fuel, i.fuel, cx - r * 0.2f, y, bh)
                    miniBar(glyphs.coolant, i.coolant, cx, y, bh)
                    miniBar(glyphs.battery, i.battery, cx + r * 0.2f, y, bh)
                    check(i.check, cx + r * 0.42f, cy + r * 0.74f)
                    i.gear?.let { put(it.text, cx - r * 0.42f, cy + r * 0.74f, r * 0.13f, st.value, bold) }
                }
            }
            GaugeDesign.HUD_BLUE -> {
                needle(s, r * 0.14f, r * 0.9f, st.needle, big * 0.022f)
                hub(r * 0.09f, st.hub ?: st.rim)
                if (showValue) {
                    if (parts.window && mode == NumberMode.DESIGN) pair(digits, cx, cy + r * 0.49f, (r * 0.15f) * ownSize, valueCol, plain, r * 0.08f, st.unit, r * 0.62f)
                    else number(digits, cx, cy + r * 0.47f, (r * 0.24f) * ownSize, valueCol, plain, r * 0.68f)
                }
                if (parts.indicators) {
                    val y = cy + r * 0.72f
                    ind(glyphs.coolant, i.coolant, cx - r * 0.3f, y, r * 0.075f)
                    ind(glyphs.fuel, i.fuel, cx + r * 0.3f, y, r * 0.075f)
                    ind(glyphs.battery, i.battery, cx, cy + r * 0.86f, r * 0.075f)
                    check(i.check, cx + r * 0.48f, cy + r * 0.86f)
                }
            }
            GaugeDesign.RED_LINE -> {
                needle(s, r * 0.12f, r * 0.9f, st.needle, big * 0.012f)
                hub(r * 0.06f, st.hub ?: st.rim)
                if (column != null) {
                    val n = rows(i)
                    val top = GaugeLayout.stackTop(cy, layout.readoutH, layout.rowH, n)
                    val rc = layout.readoutH / 2f
                    val at = Offset(column.cx, top + rc)
                    drawCircle(Color(0xFF12080A), rc, at)
                    drawCircle(st.rim, rc - thinLine.width / 2f, at, style = thinLine)
                    if (showValue) {
                        number(digits, at.x, at.y - rc * 0.1f, (rc * 0.66f) * ownSize, valueCol, light, rc * 1.6f)
                        put(unit, at.x, at.y + rc * 0.46f, rc * 0.24f, st.unit)
                    }
                    columnRows(i, column.cx, top + layout.readoutH, n)
                } else {
                    if (showValue) {
                        number(digits, cx, cy + r * 0.44f, (r * 0.24f) * ownSize, valueCol, light, r * 0.7f)
                        put(unit, cx, cy + r * 0.63f, r * 0.09f, st.unit)
                    }
                    onDial(i)
                }
            }
            GaugeDesign.BLUE_GLOW -> {
                if (known && s > 0.002f) {
                    if (!low) st.glow?.let { drawArc(it.copy(alpha = 0.3f), ang(0f), sweep * s, false, arcTl, arcSize, style = arcGlow) }
                    drawArc(if (hot) hotBrush else arcBrush, ang(0f), sweep * s, false, arcTl, arcSize, style = arcStroke)
                }
                needle(s, r * 0.14f, r * 0.82f, st.needle, big * 0.02f)
                hub(r * 0.1f, st.hub ?: st.rim)
                if (column != null) {
                    val n = rows(i)
                    val top = GaugeLayout.stackTop(cy, layout.readoutH, layout.rowH, n)
                    val fh = layout.readoutH * 0.92f
                    val fw = column.w
                    drawRoundRect(st.rim.copy(alpha = 0.7f), Offset(column.l, top), Size(fw, fh), CornerRadius(big * 0.04f), style = hairLine)
                    if (showValue) {
                        number(digits, column.cx, top + fh * 0.42f, fh * 0.46f * ownSize, valueCol, plain, fw * 0.88f)
                        put(unit, column.cx, top + fh * 0.8f, fh * 0.17f, st.unit)
                    }
                    columnRows(i, column.cx, top + layout.readoutH, n)
                } else {
                    if (showValue) {
                        number(digits, cx, cy + r * 0.5f, (r * 0.2f) * ownSize, valueCol, plain, r * 0.7f)
                        put(unit, cx, cy + r * 0.66f, r * 0.08f, st.unit)
                    }
                    if (!parts.indicators && under == null) i.clock?.takeIf { parts.window }?.let { put(it.text, cx, cy + r * 0.82f, r * 0.09f, st.unit) }
                    onDial(i)
                }
            }
            GaugeDesign.CHROME -> {
                val other = i.odo ?: i.trip
                val window = GaugeMath.chromeWindow(mode, parts.window, showValue, speedCard, other != null)
                if (window != ClassicWindow.NONE) {
                    val ro = st.readout.takeIf { mode == NumberMode.LCD }
                    drawRoundRect(ro?.bg ?: Color(0xFF0B1210), windowTl, windowSize, windowCorner)
                    drawRoundRect(ro?.border ?: Color(0xFF3A3F45), windowTl, windowSize, windowCorner, style = hairLine)
                }
                val lcdCol = st.readout.takeIf { mode == NumberMode.LCD }?.fg ?: Color(0xFF7DF0D0)
                val windowY = windowTl.y + windowSize.height / 2f
                if (window == ClassicWindow.VALUE) {
                    val f = if (digits.length == 1 && digits[0] == '—') plain else segment
                    pair(digits, cx, windowY, r * 0.135f, if (hot) warnCol else lcdCol, f, r * 0.065f, st.unit, r * 0.6f)
                } else if (window == ClassicWindow.OTHER && other != null) {
                    put(other.text, cx, windowY, fit(other.text, r * 0.12f, r * 0.6f, if (i.odo != null) segment else plain), lcdCol, if (i.odo != null) segment else plain)
                }
                // the lower half, where the needle never goes
                if (showValue && window != ClassicWindow.VALUE) {
                    if (window == ClassicWindow.OTHER) pair(digits, cx, cy + r * 0.27f, (r * 0.15f) * ownSize, valueCol, plain, r * 0.07f, st.unit, r * 0.5f)
                    else {
                        number(digits, cx, cy + r * 0.43f, (r * 0.22f) * ownSize, valueCol, plain, r * 0.66f)
                        put(unit, cx, cy + r * 0.64f, r * 0.08f, st.unit)
                    }
                }
                needle(s, r * 0.1f, r * 0.84f, st.needle, big * 0.016f)
                hub(r * 0.11f, st.hub ?: Color(0xFF26282D))
                if (parts.indicators) {
                    check(i.check, cx, cy + r * 0.75f)
                    i.gear?.let { put(it.text, cx - r * 0.3f, cy + r * 0.75f, r * 0.12f, st.unit, bold) }
                    if (window == ClassicWindow.OTHER) i.trip?.takeIf { i.odo != null }?.let { put(it.text, cx + r * 0.32f, cy + r * 0.75f, r * 0.09f, st.unit) }
                }
            }
            GaugeDesign.DOUBLE -> {
                // a needle with nothing to read rests at its start, dim: the design stays what it is
                val fuelShare = i.fuel?.share
                val coolShare = i.coolant?.share
                val fuelDeg = GaugeMath.fuelNeedleDeg(GaugeMath.needleRest(fuelShare))
                val coolDeg = GaugeMath.coolNeedleDeg(GaugeMath.needleRest(coolShare))
                drawLine(if (fuelShare == null) dimNeedle else if (i.fuel.warn) warnCol else st.needle, at(fuelDeg, r * 0.17f), at(fuelDeg, r * 0.84f), big * 0.016f, cap = StrokeCap.Round)
                drawLine(if (coolShare == null) dimNeedle else if (i.coolant.warn) warnCol else st.needle, at(coolDeg, r * 0.17f), at(coolDeg, r * 0.84f), big * 0.016f, cap = StrokeCap.Round)
                // the band across the middle goes over the needles' roots
                drawRoundRect(Color(0xF00A0B0E), Offset(cx - r * 0.9f, cy - r * 0.2f), Size(r * 1.8f, r * 0.4f), CornerRadius(r * 0.04f))
                drawLine(st.tickMinor, Offset(cx - r * 0.9f, cy - r * 0.2f), Offset(cx + r * 0.9f, cy - r * 0.2f), big * 0.008f)
                drawLine(st.tickMinor, Offset(cx - r * 0.9f, cy + r * 0.2f), Offset(cx + r * 0.9f, cy + r * 0.2f), big * 0.008f)
                if (showValue) {
                    number(digits, cx, cy - r * 0.02f, (r * 0.26f) * ownSize, if (hot) warnCol else st.value, plain, r * 0.82f)
                    put(unit, cx + r * 0.58f, cy, r * 0.09f, st.unit)
                }
                check(i.check, cx - r * 0.62f, cy)
                if (parts.indicators) {
                    put(i.fuel?.text ?: "—", cx + r * 0.2f, cy - r * 0.38f, r * 0.1f, if (i.fuel?.warn == true) warnCol else st.unit, plain, Paint.Align.LEFT)
                    put(i.coolant?.text ?: "—", cx + r * 0.2f, cy + r * 0.38f, r * 0.1f, if (i.coolant?.warn == true) warnCol else st.unit, plain, Paint.Align.LEFT)
                }
            }
            GaugeDesign.SPORT_WHITE -> {
                val a = ang(s)
                if (known && s > 0.002f) {
                    // the soft sector behind the position: wedges of falling strength, none on the Low tier but the first
                    val wedges = if (low) 1 else 6
                    for (k in 0 until wedges) {
                        val width = 6.5f
                        val from = a - width * (k + 1)
                        drawArc(st.red.copy(alpha = 0.42f * (wedges - k) / wedges), from.coerceAtLeast(ang(0f)), a - width * k - from.coerceAtLeast(ang(0f)), true, sweepTl, sweepSize)
                        if (from <= ang(0f)) break
                    }
                }
                drawLine(st.needle, at(a, r * 0.42f), at(a, r * 0.76f), big * 0.018f, cap = StrokeCap.Round)
                val tx = if (parts.indicators) cx - r * 0.1f else cx
                if (showValue) {
                    number(digits, tx, cy - r * 0.04f, (r * 0.34f) * ownSize, valueCol, light, if (parts.indicators) r * 0.6f else r * 0.76f)
                    put(unit, tx, cy + r * 0.2f, r * 0.09f, st.unit)
                }
                if (parts.indicators) {
                    val gx = cx + r * 0.33f
                    i.gear?.let { put(it.text, gx, cy - r * 0.04f, r * 0.18f, Color(0xFF7CFC9A), bold) }
                    if (i.gear == null) i.selector?.let { put(it.text, gx, cy - r * 0.04f, r * 0.14f, Color(0xFF7CFC9A), bold) }
                    ind(glyphs.fuel, i.fuel, cx - r * 0.02f, cy + r * 0.42f, r * 0.075f)
                    check(i.check, cx, cy - r * 0.46f)
                }
            }
        }
    }
}

/** The small icons, drawn in code, as outlines in a box of [s]. */
internal class Glyphs(s: Float) {
    private val k = s / 24f
    val fuel = Path().apply {
        moveTo(4 * k, 21 * k); lineTo(4 * k, 4 * k); lineTo(13 * k, 4 * k); lineTo(13 * k, 21 * k); close()
        moveTo(2 * k, 21 * k); lineTo(15 * k, 21 * k)
        moveTo(6 * k, 8 * k); lineTo(11 * k, 8 * k)
        moveTo(13 * k, 12 * k); lineTo(16 * k, 12 * k); lineTo(16 * k, 18 * k); lineTo(20 * k, 18 * k); lineTo(20 * k, 9 * k); lineTo(17 * k, 6 * k)
    }
    val coolant = Path().apply {
        moveTo(12 * k, 3 * k); lineTo(12 * k, 15 * k)
        moveTo(12 * k, 5 * k); lineTo(16 * k, 5 * k)
        moveTo(12 * k, 9 * k); lineTo(16 * k, 9 * k)
        addOval(androidx.compose.ui.geometry.Rect(Offset(12 * k, 17 * k), 2.5f * k))
        moveTo(3 * k, 21 * k); lineTo(8 * k, 19.5f * k); lineTo(16 * k, 19.5f * k); lineTo(21 * k, 21 * k)
    }
    val battery = Path().apply {
        moveTo(3 * k, 7 * k); lineTo(21 * k, 7 * k); lineTo(21 * k, 20 * k); lineTo(3 * k, 20 * k); close()
        moveTo(6 * k, 7 * k); lineTo(6 * k, 4 * k); lineTo(9 * k, 4 * k); lineTo(9 * k, 7 * k)
        moveTo(15 * k, 7 * k); lineTo(15 * k, 4 * k); lineTo(18 * k, 4 * k); lineTo(18 * k, 7 * k)
        moveTo(6 * k, 13 * k); lineTo(10 * k, 13 * k)
        moveTo(14 * k, 13 * k); lineTo(18 * k, 13 * k)
        moveTo(16 * k, 11 * k); lineTo(16 * k, 15 * k)
    }
    val engine = Path().apply {
        moveTo(9 * k, 5 * k); lineTo(15 * k, 5 * k)
        moveTo(12 * k, 5 * k); lineTo(12 * k, 8 * k)
        moveTo(2 * k, 10 * k); lineTo(2 * k, 16 * k)
        moveTo(2 * k, 13 * k); lineTo(5 * k, 13 * k)
        moveTo(5 * k, 10 * k); lineTo(7 * k, 10 * k); lineTo(7 * k, 8 * k); lineTo(16 * k, 8 * k); lineTo(18 * k, 11 * k); lineTo(20 * k, 11 * k); lineTo(20 * k, 9 * k); lineTo(22 * k, 9 * k)
        lineTo(22 * k, 17 * k); lineTo(20 * k, 17 * k); lineTo(20 * k, 15 * k); lineTo(18 * k, 15 * k); lineTo(15 * k, 19 * k); lineTo(9 * k, 19 * k); lineTo(7 * k, 16 * k); lineTo(5 * k, 16 * k); close()
    }
}
