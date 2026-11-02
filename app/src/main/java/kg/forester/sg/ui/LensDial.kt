package kg.forester.sg.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.CarKind
import kg.forester.sg.data.DeviceTier
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Tier
import kg.forester.sg.service.RulesHost
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gauge «Линза»: a dark glossy lens with a crescent of light on its left that grows with the value, a hatched ring
 * round it, a thin outer arc that lights in the warning zone, and the number in the middle on a short drum.
 * What stands still is one offscreen layer per size; a frame draws that picture, the crescent and the warning arc.
 */
@Composable
internal fun LensDial(
    value: State<Float?>, max: Float, step: Float, labelDiv: Float, unit: String, redFrom: Float?,
    modifier: Modifier, style: DialStyle, min: Float, format: String,
    name: String?, warn: WarnRule?, gear: (() -> String?)?,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val l = remember(side) { LensMath.layout(side.value) }
        val low = DeviceTier.current.collectAsState().value == Tier.LOW
        val reading = remember(value, min, max) { derivedStateOf { (value.value ?: min).coerceIn(min, max) } }
        val anim = rememberSmooth(reading, max - min) { value.value != null }
        val rule = warn ?: NoWarn
        val plate = C.palette !== Palette.NEON && C.palette !== Palette.MIDNIGHT
        val art = remember(style, l, min, max, step, labelDiv, rule, redFrom, low, plate) {
            Modifier.drawWithCache {
                val base = obtainGraphicsLayer().apply {
                    compositingStrategy = CompositingStrategy.Offscreen
                    record { drawLensBase(l, style, LensMath.endLabel(min, step, labelDiv), LensMath.endLabel(max, step, labelDiv), plate, lensMarks(style, min, max, labelDiv)) }
                }
                val live = LensLive(l, style, size.minDimension / 2f, Offset(size.width / 2f, size.height / 2f), low)
                val from = LensMath.arcFrom(min, max)
                onDrawBehind {
                    drawLayer(base)
                    // the warning by the reading itself: it does not wait for the crescent to get there
                    val hot = LensMath.warnShare(value.value, min, max, rule, redFrom)
                    if (value.value != null) with(live) { crescent(from, LensMath.share(anim.value.floatValue, min, max), hot > 0f && !l.outer) }
                    if (hot > 0f && l.outer) with(live) { warnArc(hot) }
                }
            }
        }
        Box(Modifier.size(side).graphicsLayer().then(art), contentAlignment = Alignment.Center) {
            val r = side / 2
            val disk = r * l.disk
            val digits = LensMath.widest(format, min, max).length
            LensNumber(value, format, unit, disk, digits, l.drum && !style.hideValue, low, !style.hideValue, style)
            if (l.outer && !name.isNullOrEmpty()) LensName(name, r, style)
            if (gear != null) LensGear(gear, r, l, style)
        }
    }
}

private val NoWarn = WarnRule()

/** The value's name set vertically, left of the outer arc. A text of its own, so it goes through the shared text. */
@Composable
private fun LensName(name: String, r: Dp, style: DialStyle) {
    val w = r * 0.95f
    val h = r * 0.16f
    // the parent centres it: its middle goes just inside the left edge, then it turns
    Box(
        Modifier.offset(x = -r * 0.945f).requiredSize(w, h).graphicsLayer { rotationZ = -90f },
        contentAlignment = Alignment.Center,
    ) { T(name, (r.value * 0.095f).coerceIn(9f, 13f).sp, style.unit, maxLines = 1) }
}

/** The round badge at the bottom of the ring; only while the gear is known. */
@Composable
private fun LensGear(gear: () -> String?, r: Dp, l: LensLayout, style: DialStyle) {
    val g = gear() ?: return
    val d = maxOf(r * 0.24f, 18.dp)
    Box(
        Modifier.offset(y = r * (l.ring + l.lens) / 2f).size(d).background(style.face[2], CircleShape)
            .border(1.dp, Color(0x668FA8FF), CircleShape),
        contentAlignment = Alignment.Center,
    ) { T(g, (d.value * 0.56f).sp, style.value, maxLines = 1) }
}

/**
 * The number on its drum and the unit under it. A new number rolls in from below and the old one leaves upward, both
 * only moved in their layers, so a frame of the roll lays nothing out; the fade at the window's edges is two strips of the
 * disk's colour over the digits, not a mask layer.
 */
@Composable
private fun LensNumber(
    value: State<Float?>, format: String, unit: String, disk: Dp, chars: Int, drum: Boolean, low: Boolean, shown: Boolean, style: DialStyle,
) {
    val digits = rememberDigits { value.value?.let { format.format(it) } ?: "—" }
    val current = remember { mutableStateOf(androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { digits.value }) }
    val old = remember { mutableStateOf<String?>(null) }
    val roll = remember { Animatable(1f) }
    val canRoll = rememberUpdatedState(drum)
    val isLow = rememberUpdatedState(low)
    LaunchedEffect(Unit) {
        var last = 0L
        snapshotFlow { digits.value }.collectLatest { t ->
            if (t == current.value) return@collectLatest
            val now = android.os.SystemClock.uptimeMillis()
            val rolls = LensMath.shouldRoll(canRoll.value, isLow.value, now - last, current.value, t)
            last = now
            if (rolls) {
                roll.snapTo(0f)
                old.value = current.value
                current.value = t
                roll.animateTo(1f, tween(LensMath.ROLL_MS.toInt(), easing = FastOutSlowInEasing))
                old.value = null
            } else {
                old.value = null
                roll.snapTo(1f)
                current.value = t
            }
        }
    }
    val fontDp = LensMath.digitSize(disk.value, chars, style.numberScale)
    val windowH = (fontDp * 1.2f).dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val travel = with(density) { windowH.toPx() }
    val edge = style.face[0]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (shown) Box(
            Modifier.width(disk * 1.8f).height(windowH).clipToBounds().drawWithContent {
                drawContent()
                if (roll.value < 1f) {
                    val strip = size.height * 0.3f
                    drawRect(Brush.verticalGradient(listOf(edge, edge.copy(alpha = 0f)), 0f, strip), size = Size(size.width, strip))
                    drawRect(
                        Brush.verticalGradient(listOf(edge.copy(alpha = 0f), edge), size.height - strip, size.height),
                        Offset(0f, size.height - strip), Size(size.width, strip),
                    )
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val ts = with(density) { TextStyle(fontSize = fontDp.dp.toSp(), color = style.value, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum") }
            old.value?.let { o ->
                Text(o, style = ts, maxLines = 1, modifier = Modifier.graphicsLayer {
                    translationY = -travel * roll.value
                    alpha = 1f - roll.value
                })
            }
            Text(current.value, style = ts, maxLines = 1, modifier = Modifier.graphicsLayer { translationY = travel * (1f - roll.value) })
        }
        if (unit.isNotEmpty()) T(unit, (disk.value * 0.2f).coerceIn(8f, 15f).sp, style.unit, maxLines = 1)
    }
}

/** The gear for a speed gauge, read while composing the badge alone. */
@Composable
internal fun rememberLensGear(): () -> String? {
    val e = Repo.live.collectAsState()
    val t = Repo.tcu.collectAsState()
    val other = CarKind.other.collectAsState()
    val g = remember {
        derivedStateOf {
            LensMath.gear(t.value?.gear, e.value.rpm, e.value.speedKmh, Repo.settings.gearbox == "at", RulesHost.diag.value.gears, other.value)
        }
    }
    return remember { { g.value } }
}

/** What moves: the crescent and the warning arc, with their paths and brushes kept. Drawn on the screen's and the USB display's canvas. */
internal class LensLive(l: LensLayout, private val st: DialStyle, r: Float, private val c: Offset, private val low: Boolean) {
    private val rOut = r * l.ring - 1f
    private val band = r * (l.ring - l.lens)
    private val soft = Path()
    private val core = Path()
    private val softBrushes = HashMap<Int, Brush>()
    private val coreBrushes = HashMap<Int, Brush>()
    private val arc = st.arc?.takeIf { it.isNotEmpty() } ?: DefaultArc
    private val arcR = r * l.arc
    private val arcTl = Offset(c.x - arcR, c.y - arcR)
    private val arcSize = Size(arcR * 2, arcR * 2)
    private val outTl = Offset(c.x - rOut, c.y - rOut)
    private val outSize = Size(rOut * 2, rOut * 2)
    private val arcStroke = r * 0.045f
    private val warnBrush = Brush.linearGradient(
        0f to st.red.darker(), 0.35f to st.red, 0.75f to Color(0xFFFFB27A),
        start = Offset(c.x, c.y + arcR * sin(LensMath.ARC_START_DEG * (PI / 180.0).toFloat())),
        end = Offset(c.x, c.y - arcR * sin(LensMath.ARC_START_DEG * (PI / 180.0).toFloat())),
    )

    fun DrawScope.crescent(from: Float, to: Float, hot: Boolean) {
        val a0 = LensMath.START_DEG + LensMath.SWEEP_DEG * minOf(from, to)
        val a1 = LensMath.START_DEG + LensMath.SWEEP_DEG * maxOf(from, to)
        val span = a1 - a0
        if (span < 0.5f) return
        val slim = (span / 70f).coerceIn(0.35f, 1f)
        val key = LensMath.brushKey(a0, a1, hot)
        val cols = if (hot) HotArc else arc
        if (!low) {
            shape(soft, a0, a1, band * 0.96f * slim)
            drawPath(soft, softBrushes.getOrPut(key) { brush(a0, a1, cols, 0.42f) })
        }
        shape(core, a0, a1, band * 0.46f * slim)
        val b = coreBrushes.getOrPut(key) { brush(a0, a1, cols, 1f) }
        drawPath(core, b)
        if (!low) drawArc(b, a0, span, false, outTl, outSize, style = Stroke(1.5f))
    }

    fun DrawScope.warnArc(share: Float) {
        val sweep = LensMath.ARC_SWEEP_DEG * share.coerceIn(0f, 1f)
        if (!low) drawArc(st.red.copy(alpha = 0.16f), LensMath.ARC_START_DEG, sweep, false, arcTl, arcSize, style = Stroke(arcStroke * 1.8f, cap = StrokeCap.Round))
        drawArc(warnBrush, LensMath.ARC_START_DEG, sweep, false, arcTl, arcSize, style = Stroke(arcStroke, cap = StrokeCap.Round))
    }

    private fun shape(p: Path, a0: Float, a1: Float, thick: Float) {
        p.rewind()
        val deg = (PI / 180.0).toFloat()
        for (i in 0..STEPS) {
            val a = (a0 + (a1 - a0) * i / STEPS) * deg
            val x = c.x + rOut * cos(a)
            val y = c.y + rOut * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        for (i in STEPS - 1 downTo 1) {
            val t = i / STEPS.toFloat()
            val a = (a0 + (a1 - a0) * t) * deg
            val rr = rOut - thick * LensMath.taper(t)
            p.lineTo(c.x + rr * cos(a), c.y + rr * sin(a))
        }
        p.close()
    }

    /**
     * The light along the crescent: dark at its tail, bright at its head, gone at both ends. A straight gradient from tail
     * to head: a sweep one leaves a hairline at 9 o'clock on some GPUs, right in the crescent.
     */
    private fun brush(a0: Float, a1: Float, cols: List<Color>, alpha: Float): Brush {
        val deep = cols.first().copy(alpha = alpha)
        val mid = cols[cols.size / 2].copy(alpha = alpha)
        val head = cols.last().copy(alpha = alpha)
        return Brush.linearGradient(
            0f to deep.copy(alpha = 0f), 0.2f to deep, 0.68f to mid, 0.93f to head, 1f to head.copy(alpha = 0f),
            start = at(a0), end = at(a1),
        )
    }

    private fun at(deg: Float): Offset {
        val a = deg * (PI / 180.0).toFloat()
        return Offset(c.x + rOut * cos(a), c.y + rOut * sin(a))
    }

    private companion object {
        const val STEPS = 26
        val DefaultArc = listOf(Color(0xFF1238C8), Color(0xFF2E6BFF), Color(0xFFCFE2FF))
        val HotArc = listOf(Color(0xFF9A1B0A), Color(0xFFFF4A1F), Color(0xFFFFC09A))
    }
}

private fun Color.darker() = androidx.compose.ui.graphics.lerp(this, Color.Black, 0.45f)

/** All that stands still: the plate, the hatched ring, the lens with its highlights, the disk and the outer arc's track and end labels. */
internal fun DrawScope.drawLensBase(l: LensLayout, st: DialStyle, lowLabel: String, highLabel: String, plate: Boolean = true, marks: List<Pair<String, Float>> = emptyList()) {
    val r = size.minDimension / 2f
    val c = center
    val px = density
    val ring = r * l.ring
    val lens = r * l.lens
    val disk = r * l.disk
    // on a light or a mid-tone screen a plate carries what is outside the ring, the labels were drawn for the dark
    drawCircle(st.face[2], if (l.outer && plate) r else ring, c)
    // the ring and its hatching
    drawCircle(
        Brush.radialGradient(
            (lens / ring) to st.face[2], 0.9f to st.track, 1f to st.track.brighter(0.08f),
            center = c, radius = ring,
        ), ring, c,
    )
    val lines = ((2 * PI * ring) / (3f * px)).toInt().coerceIn(90, 420)
    val hatch = ArrayList<Offset>(lines * 2)
    for (i in 0 until lines) {
        val a = 2 * PI * i / lines
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        hatch += Offset(c.x + (lens + (ring - lens) * 0.06f) * ca, c.y + (lens + (ring - lens) * 0.06f) * sa)
        hatch += Offset(c.x + ring * 0.99f * ca, c.y + ring * 0.99f * sa)
    }
    drawPoints(hatch, PointMode.Lines, st.tickMinor.copy(alpha = st.tickMinor.alpha * 0.34f), strokeWidth = 0.6f * px)
    drawCircle(st.tickMinor, ring, c, style = Stroke(0.8f * px))
    // the scale's ends on the ring
    for (deg in listOf(LensMath.START_DEG, LensMath.START_DEG + LensMath.SWEEP_DEG)) {
        val a = deg * PI.toFloat() / 180f
        val u = Offset(cos(a), sin(a))
        drawLine(Color(0x88FFFFFF), c + u * (lens + (ring - lens) * 0.35f), c + u * ring, strokeWidth = 1f * px)
    }
    // the lens: a torus, lighter on its crown, dark at both edges
    val body = st.face[1]
    drawCircle(
        Brush.radialGradient(
            (disk / lens) to st.face[0], ((disk + (lens - disk) * 0.42f) / lens) to body,
            ((disk + (lens - disk) * 0.8f) / lens) to st.face[0], 1f to Color(0xFF020308),
            center = c, radius = lens,
        ), lens, c,
    )
    drawCircle(Color(0x33FFFFFF), lens, c, style = Stroke(0.8f * px))
    // the soft white highlight on the upper right, wider and fainter outwards; drawn once, no blur
    val crown = disk + (lens - disk) * 0.5f
    val tl = Offset(c.x - crown, c.y - crown)
    val sz = Size(crown * 2, crown * 2)
    val w = lens - disk
    fun shine(from: Float, to: Float, strong: Float) = Brush.sweepGradient(
        (from / 360f) to Color.White.copy(alpha = 0f), ((from + to) / 720f) to Color.White.copy(alpha = strong), (to / 360f) to Color.White.copy(alpha = 0f),
        center = c,
    )
    drawArc(shine(272f, 358f, 0.10f), 272f, 86f, false, tl, sz, style = Stroke(w * 0.72f))
    drawArc(shine(280f, 350f, 0.20f), 280f, 70f, false, tl, sz, style = Stroke(w * 0.36f))
    drawArc(shine(288f, 342f, 0.85f), 288f, 54f, false, tl, sz, style = Stroke(maxOf(w * 0.07f, 1.2f * px), cap = StrokeCap.Round))
    // and its faint answer lower right, along the outer edge
    val rimR = disk + (lens - disk) * 0.84f
    drawArc(shine(15f, 80f, 0.5f), 15f, 65f, false, Offset(c.x - rimR, c.y - rimR), Size(rimR * 2, rimR * 2), style = Stroke(1.2f * px, cap = StrokeCap.Round))
    // the disk under the number
    drawCircle(Brush.radialGradient(listOf(st.face[0].brighter(0.03f), st.face[0]), c, disk), disk, c)
    drawCircle(st.rim, disk, c, style = Stroke(1.1f * px))
    if (!l.outer) return
    // the outer arc: a thin track with fine ticks, the end labels
    val arcR = r * l.arc
    val arcTl = Offset(c.x - arcR, c.y - arcR)
    val arcSz = Size(arcR * 2, arcR * 2)
    drawArc(st.tickMinor, LensMath.ARC_START_DEG, LensMath.ARC_SWEEP_DEG, false, arcTl, arcSz, style = Stroke(1f * px))
    val ticks = ArrayList<Offset>(82)
    var deg = LensMath.ARC_START_DEG
    while (deg <= LensMath.ARC_START_DEG + LensMath.ARC_SWEEP_DEG + 0.01f) {
        val a = deg * PI.toFloat() / 180f
        val u = Offset(cos(a), sin(a))
        ticks += c + u * arcR
        ticks += c + u * (arcR - r * 0.028f)
        deg += 2f
    }
    drawPoints(ticks, PointMode.Lines, st.tickMinor.copy(alpha = st.tickMinor.alpha * 0.7f), strokeWidth = 0.7f * px)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (r * 0.085f).coerceAtLeast(9f * px)
        color = st.numbers.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        textSkewX = -0.18f
    }
    fun label(text: String, deg: Float) {
        val a = deg * PI.toFloat() / 180f
        val rr = r * (l.arc + 0.015f)
        drawContext.canvas.nativeCanvas.drawText(text, c.x + rr * cos(a), c.y + rr * sin(a) + paint.textSize * 0.35f, paint)
    }
    label(lowLabel, LensMath.ARC_START_DEG - 7f)
    label(highLabel, LensMath.ARC_START_DEG + LensMath.ARC_SWEEP_DEG + 7f)
    // the driver's own numbers, where the crescent stands at that value: only at the size that has room for them
    for ((text, share) in marks) {
        val a = (LensMath.START_DEG + LensMath.SWEEP_DEG * share) * PI.toFloat() / 180f
        val rr = r * (l.ring + l.arc) / 2f
        drawContext.canvas.nativeCanvas.drawText(text, c.x + rr * cos(a), c.y + rr * sin(a) + paint.textSize * 0.35f, paint)
    }
}

/** The driver's own numbers of this scale, with where each stands on it. */
internal fun lensMarks(st: DialStyle, min: Float, max: Float, labelDiv: Float): List<Pair<String, Float>> =
    DialOptions.onScale(st.marks, min, max).map { DialOptions.label(it, labelDiv) to LensMath.share(it, min, max) }

private fun Color.brighter(f: Float) = androidx.compose.ui.graphics.lerp(this, Color.White, f)
