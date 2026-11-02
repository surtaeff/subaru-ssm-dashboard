package kg.forester.sg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kg.forester.sg.data.DeviceTier
import kg.forester.sg.data.RoadLayout
import kg.forester.sg.data.RoadScene
import kg.forester.sg.data.Tier
import kg.forester.sg.data.TripState
import kg.forester.sg.data.lookOf
import kg.forester.sg.service.MediaSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The scene's own colours: a night in every theme, only the card's frame goes with the theme. */
private val BladeLow = Color(0xFF123C9C)
private val BladeMid = Color(0xFF2F7BFF)
private val BladeHigh = Color(0xFF8FDCFF)
private val RoadGlow = Color(0xFF2F7BFF)
private val TailRed = Color(0xFFFF2A3C)
private val Dim = Color(0xFFC9D2E0)

/**
 * Another make's picture for the 3D car it hasn't got: a car from behind on a night road under an arc speedometer, all
 * drawn, no model files. What never changes is one bitmap, made off the main thread once per size and kept for the
 * return to the screen; a frame puts it down and draws only the dashes, and the blades when their number changes.
 * Nothing here runs on a frame while the car stands, and leaving the screen ends it with the composition. In the
 * background [SmoothDriver] gets no frames: the recomposer's clock stands still from ON_STOP to ON_START.
 */
@Composable
fun NightRoadCard(live: State<kg.forester.sg.ssm.EcuSnapshot>, trip: State<TripState>, modifier: Modifier = Modifier) {
    Panel(modifier, padding = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth > 0.dp && maxHeight > 0.dp) {
                val l = remember(maxWidth, maxHeight) { RoadScene.layout(maxWidth.value, maxHeight.value) }
                NightRoadScene(l, live, trip)
            }
        }
    }
}

@Composable
private fun NightRoadScene(l: RoadLayout, live: State<kg.forester.sg.ssm.EcuSnapshot>, trip: State<TripState>) {
    val density = LocalDensity.current
    val tier by DeviceTier.current.collectAsState()
    val art by produceState(RoadArt.ready(l, density.density, tier), l, density.density, tier) {
        value = RoadArt.ready(l, density.density, tier) ?: withContext(Dispatchers.Default) { RoadArt.make(l, density.density, density.fontScale, tier) }
    }
    val reading = remember(live) { derivedStateOf { (live.value.speedKmh?.toFloat() ?: 0f).coerceIn(0f, RoadScene.TOP_KMH) } }
    val speed = rememberSmooth(reading, RoadScene.TOP_KMH, shownHz = SceneHz) { live.value.speedKmh != null }

    Box(Modifier.fillMaxSize().drawBehind {
        val a = art
        if (a == null) drawRect(Color.Black) else drawImage(a.backdrop)
    })
    RoadDashes(l, speed, art)
    Blades(l, speed)
    SpeedText(l, live, density)
    SideTexts(l, trip)
}

private val SceneHz = { DeviceTier.look.sceneHz }

/** The lane lines and, over them, the car: the only thing that changes on each frame of a moving car. */
@Composable
private fun RoadDashes(l: RoadLayout, speed: Smooth, art: RoadArt?) {
    val phase = remember { mutableFloatStateOf(0f) }
    val stepper = remember(speed) {
        object : Stepper {
            var waited = 0f
            override fun step(dt: Float): Boolean {
                val v = speed.value.floatValue
                if (v < MOVING_KMH) return false
                DeviceTier.drawing()
                waited += dt
                if (SmoothDriver.ticks(SceneHz())) {
                    phase.floatValue = RoadScene.phaseAfter(phase.floatValue, v, waited)
                    waited = 0f
                }
                return true
            }
        }
    }
    LaunchedEffect(stepper) {
        snapshotFlow { speed.value.floatValue >= MOVING_KMH }.collect { if (it) SmoothDriver.wake(stepper) }
    }
    DisposableEffect(stepper) { onDispose { SmoothDriver.drop(stepper) } }
    Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
        val d = density
        val ends = FloatArray(RoadScene.MAX_DASHES * 2)
        val dash = Path()
        val horizon = l.horizonY * d
        val depth = (l.h - l.horizonY) * d
        val cx = l.cx * d
        val lane = l.laneX * d
        val half = max(1.5f * d, l.h * 0.008f * d)
        onDrawBehind {
            val n = RoadScene.dashes(phase.floatValue, ends)
            for (i in 0 until n) {
                val near = ends[i * 2]
                val far = ends[i * 2 + 1]
                val yNear = horizon + depth * near
                val yFar = horizon + depth * far
                val colour = Color.White.copy(alpha = (0.22f + 0.78f * near).coerceAtMost(0.95f))
                for (side in -1..1 step 2) {
                    dash.rewind()
                    dash.moveTo(cx + side * (lane * near - half * near), yNear)
                    dash.lineTo(cx + side * (lane * near + half * near), yNear)
                    dash.lineTo(cx + side * (lane * far + half * far), yFar)
                    dash.lineTo(cx + side * (lane * far - half * far), yFar)
                    dash.close()
                    drawPath(dash, colour)
                }
            }
            art?.car?.let { drawImage(it, Offset(art.carX, art.carY)) }
        }
    })
}

/** The lit blades of the arc: redrawn only when their number changes. The dark ones are in the bitmap. */
@Composable
private fun Blades(l: RoadLayout, speed: Smooth) {
    val lit = remember(speed, l.blades) { derivedStateOf { RoadScene.litBlades(speed.value.floatValue, l.blades) } }
    Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
        val d = density
        val n = l.blades
        val colours = Array(n) { bladeColour(it.toFloat() / (n - 1)) }
        val coords = FloatArray(n * 4)
        for (i in 0 until n) {
            val a = Math.toRadians(RoadScene.bladeDeg(i, n).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            coords[i * 4] = (l.cx + c * l.bladeInner) * d
            coords[i * 4 + 1] = (l.cy + s * l.bladeInner) * d
            coords[i * 4 + 2] = (l.cx + c * l.r) * d
            coords[i * 4 + 3] = (l.cy + s * l.r) * d
        }
        val width = l.bladeWidth * d
        onDrawBehind {
            for (i in 0 until lit.value) {
                drawLine(colours[i], Offset(coords[i * 4], coords[i * 4 + 1]), Offset(coords[i * 4 + 2], coords[i * 4 + 3]), width)
            }
        }
    })
}

private fun bladeColour(f: Float): Color =
    if (f < 0.5f) androidx.compose.ui.graphics.lerp(BladeLow, BladeMid, f * 2) else androidx.compose.ui.graphics.lerp(BladeMid, BladeHigh, (f - 0.5f) * 2)

/** The number, as on the other cards: from the same reading at the same pace, «—» without one. */
@Composable
private fun SpeedText(l: RoadLayout, live: State<kg.forester.sg.ssm.EcuSnapshot>, density: Density) {
    val text by rememberDigits { orDash(live.value.speedKmh?.let { kg.forester.sg.data.DistanceUnits.speed(it) }) }
    // the size by the card, not by the system's font setting: it fills the arc as it is
    fun fixedSp(dp: Float) = (dp / density.fontScale).sp
    Box(Modifier.offset((l.cx - l.bladeInner).dp, (l.numberCy - l.numberSize * 0.75f).dp).size((l.bladeInner * 2).dp, (l.numberSize * 1.5f).dp), contentAlignment = Alignment.Center) {
        Text(
            text, maxLines = 1,
            style = TextStyle(
                fontSize = fixedSp(l.numberSize), color = Color.White, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center, fontFeatureSettings = "tnum",
            ),
        )
    }
    if (l.unitOn) Box(Modifier.offset((l.cx - l.bladeInner).dp, (l.unitCy - l.unitSize).dp).size((l.bladeInner * 2).dp, (l.unitSize * 2).dp), contentAlignment = Alignment.Center) {
        Text(kg.forester.sg.data.DistanceUnits.speedUnit, maxLines = 1, style = TextStyle(fontSize = fixedSp(l.unitSize), color = Dim, fontFamily = FontFamily.SansSerif))
    }
}

/** Range, trip and track beside the arc where there is room, from real values only; what has none is left out. */
@Composable
private fun SideTexts(l: RoadLayout, trip: State<TripState>) {
    if (l.sideW <= 0f) return
    val range by trip.slot { t -> t.rangeKm?.let { kg.forester.sg.data.DistanceUnits.distanceText(it) } }
    val driven by trip.slot { t -> t.trip?.distanceKm?.takeIf { it >= 0.05 }?.let { kg.forester.sg.data.DistanceUnits.distanceText(it, 1) } }
    val track by trackNow(l.titleOn)
    val big = min(l.h * 0.11f, 26f).coerceAtLeast(15f)
    val small = (big * 0.56f).coerceAtLeast(10f)
    val top = (l.cy - big * 1.1f).coerceAtLeast(2f)
    Column(Modifier.offset((l.leftCx - l.sideW / 2).dp, top.dp).width(l.sideW.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SideValue("Запас хода", range, big, small)
        if (l.titleOn) track?.let { Text(kg.forester.sg.data.I18n.raw(it), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = small.sp, color = Dim, textAlign = TextAlign.Center)) }
    }
    Column(Modifier.offset((l.rightCx - l.sideW / 2).dp, top.dp).width(l.sideW.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SideValue("Поездка", driven, big, small)
    }
}

@Composable
private fun SideValue(label: String, value: String?, big: Float, small: Float) {
    if (value == null) return
    Text(label, maxLines = 1, style = TextStyle(fontSize = small.sp, color = Dim))
    Text(value, maxLines = 1, style = TextStyle(fontSize = big.sp, color = Color.White, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif))
}

/** What plays, looked at every few seconds off the main thread while the screen is in front; null: nothing, or no access. */
@Composable
private fun trackNow(on: Boolean): State<String?> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    return produceState<String?>(null, on, owner) {
        if (!on) { value = null; return@produceState }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { runCatching { if (MediaSessions.allowed(ctx)) MediaSessions.nowPlaying(ctx) else null }.getOrNull() }
                delay(TRACK_EVERY_MS)
            }
        }
    }
}

private const val MOVING_KMH = 0.5f
private const val TRACK_EVERY_MS = 3000L

/** The pictures of one size and tier. One at a time: there is one such card on the screen. */
private class RoadArt(val key: String, val backdrop: ImageBitmap, val car: ImageBitmap?, val carX: Float, val carY: Float) {
    companion object {
        @Volatile private var last: RoadArt? = null

        private fun keyOf(l: RoadLayout, d: Float, tier: Tier) = "${l.w}x${l.h}@$d/${lookOf(tier).sceneReflection}"

        fun ready(l: RoadLayout, d: Float, tier: Tier): RoadArt? = last?.takeIf { it.key == keyOf(l, d, tier) }

        fun make(l: RoadLayout, d: Float, fontScale: Float, tier: Tier): RoadArt {
            ready(l, d, tier)?.let { return it }
            val density = Density(d, fontScale)
            val w = (l.w * d).toInt().coerceAtLeast(1)
            val h = (l.h * d).toInt().coerceAtLeast(1)
            val backdrop = ImageBitmap(w, h)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(backdrop), Size(w.toFloat(), h.toFloat())) { drawBackdrop(l, d) }
            var carBmp: ImageBitmap? = null
            var carX = 0f
            var carY = 0f
            if (l.carH > 0f) {
                // the body, the glow of its lights round it and the reflection under it
                val cw = l.carW * d
                val ch = l.carH * d
                val reflect = if (lookOf(tier).sceneReflection) min(ch * 0.42f, (l.h - l.carGround) * d + ch * 0.1f) else 0f
                val pad = ch * 0.25f
                val bw = (cw + pad * 2).toInt().coerceAtLeast(1)
                val bh = (ch + pad + max(reflect, 0f) + 1).toInt().coerceAtLeast(1)
                val b = ImageBitmap(bw, bh)
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(b), Size(bw.toFloat(), bh.toFloat())) {
                    translate(pad, pad) { drawCar(cw, ch, d, reflect) }
                }
                carBmp = b
                carX = l.cx * d - cw / 2 - pad
                carY = l.carGround * d - ch - pad
            }
            return RoadArt(keyOf(l, d, tier), backdrop, carBmp, carX, carY).also { last = it }
        }
    }
}

/** Sky, dusk, dunes, water, the road with its glow and the dark blades: all that stands still. */
private fun DrawScope.drawBackdrop(l: RoadLayout, d: Float) {
    val w = size.width
    val h = size.height
    val cx = l.cx * d
    val horizon = l.horizonY * d
    // the sky
    drawRect(Color.Black)
    drawRect(Brush.verticalGradient(0f to Color.Black, 0.45f to Color(0xFF03050F), 0.82f to Color(0xFF0A1636), 1f to Color(0xFF1B2A55), startY = 0f, endY = horizon), size = Size(w, horizon))
    // faint stars: always the same ones, more in a bigger sky
    val rnd = Random(47)
    val stars = (w * horizon / (d * d) / 450f).toInt().coerceIn(24, 150)
    repeat(stars) {
        val y = rnd.nextFloat()
        val px = rnd.nextFloat() * w
        val py = y * y * horizon * 0.92f
        val a = 0.12f + rnd.nextFloat() * 0.45f * (1f - y * 0.7f)
        drawCircle(Color.White.copy(alpha = a), (0.45f + rnd.nextFloat() * 0.6f) * d, Offset(px, py))
    }
    // the dusk: orange at the middle of the horizon going into deep blue
    val glowW = w * 0.62f
    val glowH = (horizon * 0.55f).coerceAtLeast(14f * d)
    clipRect(0f, 0f, w, horizon) {
        withScale(1f, glowH / glowW, Offset(cx, horizon)) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFFA24A).copy(alpha = 0.95f), 0.16f to Color(0xFFF2652A).copy(alpha = 0.72f), 0.42f to Color(0xFF8A3A5E).copy(alpha = 0.34f),
                    0.72f to Color(0xFF1F2F7A).copy(alpha = 0.16f), 1f to Color.Transparent, center = Offset(cx, horizon), radius = glowW,
                ),
                glowW, Offset(cx, horizon),
            )
        }
    }
    // the dunes, dark against it, leaving the middle open
    drawDune(0f, w * 0.40f, horizon, l.h * d * 0.10f, true)
    drawDune(w, w * 0.40f, horizon, l.h * d * 0.085f, false)
    // the ground and the water under the horizon, blue with the dusk reflected at its middle
    drawRect(Color(0xFF04060C), Offset(0f, horizon), Size(w, h - horizon))
    val water = ((h - horizon) * 0.16f).coerceAtLeast(2f * d)
    drawRect(Brush.verticalGradient(0f to Color(0xFF2A5BC8), 0.5f to Color(0xFF12306F), 1f to Color(0xFF04060C), startY = horizon, endY = horizon + water), Offset(0f, horizon), Size(w, water))
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 0.36f to Color(0xFFFF8A3C).copy(alpha = 0.10f), 0.5f to Color(0xFFFFA24A).copy(alpha = 0.55f), 0.64f to Color(0xFFFF8A3C).copy(alpha = 0.10f), 1f to Color.Transparent, startX = 0f, endX = w),
        Offset(0f, horizon), Size(w, water * 0.62f),
    )
    // the bright line of the horizon
    drawLine(Brush.horizontalGradient(0f to Color.Transparent, 0.3f to Color(0xFF3B6BDA).copy(alpha = 0.5f), 0.5f to Color(0xFFFFC27A), 0.7f to Color(0xFF3B6BDA).copy(alpha = 0.5f), 1f to Color.Transparent), Offset(0f, horizon), Offset(w, horizon), 1.1f * d)
    // the road to the vanishing point
    val half = l.roadHalf * d
    val road = Path().apply {
        moveTo(cx, horizon)
        lineTo(cx + half, h)
        lineTo(cx - half, h)
        close()
    }
    drawPath(road, Brush.verticalGradient(0f to Color(0xFF0B1020), 0.5f to Color(0xFF0C0F18), 1f to Color(0xFF11141D), startY = horizon, endY = h))
    // the blue glow along it: wider and fainter strokes instead of a blur, all in this one picture
    for ((wide, alpha) in listOf(16f to 0.05f, 9f to 0.08f, 4.5f to 0.14f, 1.6f to 0.75f)) {
        val bw = wide * d * (l.h / 200f).coerceIn(0.5f, 1.6f)
        for (side in -1..1 step 2) {
            drawLine(
                Brush.linearGradient(listOf(RoadGlow.copy(alpha = alpha * 0.25f), RoadGlow.copy(alpha = alpha)), Offset(cx, horizon), Offset(cx + side * half, h)),
                Offset(cx, horizon), Offset(cx + side * half, h), bw, StrokeCap.Butt,
            )
        }
    }
    // the arc's dark blades and its outer line
    val n = l.blades
    for (i in 0 until n) {
        val a = Math.toRadians(RoadScene.bladeDeg(i, n).toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        drawLine(Color(0xFF7FA8FF).copy(alpha = 0.16f), Offset((l.cx + c * l.bladeInner) * d, (l.cy + s * l.bladeInner) * d), Offset((l.cx + c * l.r) * d, (l.cy + s * l.r) * d), l.bladeWidth * d)
    }
    val outer = l.r * 1.03f * d
    drawArc(
        Color(0xFF6E9BFF).copy(alpha = 0.55f), RoadScene.bladeDeg(0, n), RoadScene.SWEEP_DEG, false,
        Offset(cx - outer, l.cy * d - outer), Size(outer * 2, outer * 2), style = Stroke(max(0.8f * d, l.r * 0.008f * d), cap = StrokeCap.Round),
    )
}

private inline fun DrawScope.withScale(sx: Float, sy: Float, pivot: Offset, block: DrawScope.() -> Unit) {
    val c = drawContext.canvas
    c.save()
    c.translate(pivot.x, pivot.y)
    c.scale(sx, sy)
    c.translate(-pivot.x, -pivot.y)
    block()
    c.restore()
}

/** A dune from the edge at [edge] towards the middle over [reach], [tall] above the horizon. */
private fun DrawScope.drawDune(edge: Float, reach: Float, horizon: Float, tall: Float, left: Boolean) {
    val dir = if (left) 1f else -1f
    val back = Path().apply {
        moveTo(edge, horizon)
        lineTo(edge, horizon - tall * 0.75f)
        cubicTo(edge + dir * reach * 0.25f, horizon - tall * 1.15f, edge + dir * reach * 0.5f, horizon - tall * 0.55f, edge + dir * reach * 1.05f, horizon)
        close()
    }
    drawPath(back, Color(0xFF0A1024))
    val front = Path().apply {
        moveTo(edge, horizon)
        lineTo(edge, horizon - tall * 0.35f)
        cubicTo(edge + dir * reach * 0.2f, horizon - tall * 0.7f, edge + dir * reach * 0.42f, horizon - tall * 0.25f, edge + dir * reach * 0.72f, horizon)
        close()
    }
    drawPath(front, Color(0xFF05070F))
}

/** A dark car from behind in a box of [cw] × [ch] at the origin, of no make; [reflect] px of its reflection below. */
private fun DrawScope.drawCar(cw: Float, ch: Float, d: Float, reflect: Float) {
    fun x(f: Float) = f * cw
    fun y(f: Float) = f * ch
    val body = Path().apply {
        moveTo(x(0.03f), y(0.86f))
        lineTo(x(0.0f), y(0.55f))
        cubicTo(x(0.0f), y(0.46f), x(0.04f), y(0.43f), x(0.11f), y(0.41f))
        lineTo(x(0.17f), y(0.39f))
        cubicTo(x(0.22f), y(0.12f), x(0.27f), y(0.03f), x(0.36f), y(0.02f))
        lineTo(x(0.64f), y(0.02f))
        cubicTo(x(0.73f), y(0.03f), x(0.78f), y(0.12f), x(0.83f), y(0.39f))
        lineTo(x(0.89f), y(0.41f))
        cubicTo(x(0.96f), y(0.43f), x(1f), y(0.46f), x(1f), y(0.55f))
        lineTo(x(0.97f), y(0.86f))
        close()
    }
    // soft pools instead of a blur: the red bar and the dark body in the wet road, out by the edges
    fun DrawScope.pool(colour: Color, cy: Float, half: Float, tall: Float) = withScale(1f, tall / half, Offset(cw / 2, cy)) {
        drawCircle(Brush.radialGradient(0f to colour, 1f to colour.copy(alpha = 0f), center = Offset(cw / 2, cy), radius = half), half, Offset(cw / 2, cy))
    }
    if (reflect > 0f) {
        pool(Color(0xFF232C40).copy(alpha = 0.6f), ch, cw * 0.5f, reflect)
        pool(TailRed.copy(alpha = 0.34f), ch + reflect * 0.25f, cw * 0.46f, reflect * 0.6f)
    }
    // the shadow on the road
    pool(Color.Black.copy(alpha = 0.9f), y(0.96f), cw * 0.58f, ch * 0.13f)
    val tyre = Size(cw * 0.15f, ch * 0.2f)
    val round = androidx.compose.ui.geometry.CornerRadius(cw * 0.022f)
    drawRoundRect(Color(0xFF020306), Offset(x(0.05f), y(0.8f)), tyre, round)
    drawRoundRect(Color(0xFF020306), Offset(x(0.8f), y(0.8f)), tyre, round)
    drawPath(body, Brush.verticalGradient(0f to Color(0xFF1B2333), 0.5f to Color(0xFF0E121C), 1f to Color(0xFF06080D), startY = 0f, endY = y(0.86f)))
    // the dusk and the road's glow catching its edge: without it a dark car is gone on a dark road
    drawPath(body, Color(0xFF6E8FD8).copy(alpha = 0.30f), style = Stroke(max(0.7f * d, cw * 0.005f)))
    val glass = Path().apply {
        moveTo(x(0.22f), y(0.37f))
        cubicTo(x(0.26f), y(0.17f), x(0.3f), y(0.09f), x(0.37f), y(0.08f))
        lineTo(x(0.63f), y(0.08f))
        cubicTo(x(0.7f), y(0.09f), x(0.74f), y(0.17f), x(0.78f), y(0.37f))
        close()
    }
    drawPath(glass, Brush.verticalGradient(0f to Color(0xFF2B3D63), 1f to Color(0xFF0A0F1C), startY = y(0.08f), endY = y(0.37f)))
    // the thin red bar across the tail, with its glow
    val bar = y(0.5f)
    val th = max(1.2f * d, ch * 0.022f)
    for ((wide, alpha) in listOf(7f to 0.07f, 3.8f to 0.16f, 2f to 0.32f)) {
        drawLine(TailRed.copy(alpha = alpha), Offset(x(0.02f), bar), Offset(x(0.98f), bar), th * wide, StrokeCap.Round)
    }
    drawLine(Color(0xFFFF5564), Offset(x(0.02f), bar), Offset(x(0.98f), bar), th, StrokeCap.Round)
    // the plate's place and the bumper line: shape, no make
    drawLine(Color(0xFF2A3346).copy(alpha = 0.8f), Offset(x(0.1f), y(0.7f)), Offset(x(0.9f), y(0.7f)), max(0.7f * d, ch * 0.008f))
}
