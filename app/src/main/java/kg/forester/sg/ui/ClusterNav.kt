package kg.forester.sg.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ArrowUpLeft
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.CornerUpLeft
import com.composables.icons.lucide.CornerUpRight
import com.composables.icons.lucide.CornerLeftDown
import com.composables.icons.lucide.CornerRightDown
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.FlagTriangleRight
import com.composables.icons.lucide.Cctv
import com.composables.icons.lucide.Construction
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Fuel
import com.composables.icons.lucide.Thermometer
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentHeight
import kg.forester.sg.data.Repo
import kg.forester.sg.service.MediaSessions
import kg.forester.sg.service.NavGuidance
import kg.forester.sg.service.Place
import kg.forester.sg.service.Routing
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/*
 * «Приборка с картой»: two round gauges with the map between them, as the digital clusters of new cars show the
 * navigation. The map is our own (OpenStreetMap, turned along the course, the way just driven drawn on it);
 * the next turn comes from the navigator's notification (see NavGuidance). When 2GIS guides, its dashboard service
 * (DgisDashboard) gives its own map with the route, the manoeuvre, speed bumps, cameras, the limit and the lights.
 */

/** Map colours of the cluster: the OSM tiles recoloured from their brightness, like the mini-map designs. */
private class ClusterMap(val id: String, val name: String, val low: Color?, val high: Color, val bg: Color) {
    val tiles get() = low?.let { mapRecolour(it, high) }
}

private val ClusterMaps: List<ClusterMap> by lazy {
    listOf(
        ClusterMap("night", "Ночная", Color(0xFF0A111E), Color(0xFF3D5172), Color(0xFF070B14)),
        ClusterMap("dusk", "Сумерки", Color(0xFF172433), Color(0xFF6F8AA6), Color(0xFF121C28)),
        ClusterMap("graphite", "Графит", Color(0xFF0C0C0E), Color(0xFF6B6F78), Color(0xFF08080A)),
    ) + MapStyles.filter { it.low != null }.map { ClusterMap(it.id, it.name, it.low, it.high, it.bg) } +
        ClusterMap("day", "Обычная", null, Color.White, Color(0xFFF2EFE9))
}

/** Same brightness recolouring as the mini-map: buildings and water darkest, roads brightest. */
private fun mapRecolour(low: Color, high: Color): android.graphics.ColorMatrixColorFilter {
    val lo = 212f
    val k = 1f / (255f - lo)
    fun row(a: Float, b: Float): FloatArray {
        val d = (b - a) * 255f * k
        return floatArrayOf(0.30f * d, 0.59f * d, 0.11f * d, 0f, a * 255f - d * lo)
    }
    return android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix(row(low.red, high.red) + row(low.green, high.green) + row(low.blue, high.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)),
    )
}

val NavAccents = listOf(
    "sky" to ("Голубой" to Color(0xFF4FC3F7)), "violet" to ("Фиолетовый" to Color(0xFF9B7BFF)),
    "red" to ("Красный" to Color(0xFFFF5252)), "amber" to ("Янтарь" to Color(0xFFFFB547)),
    "green" to ("Зелёный" to Color(0xFF34D399)), "white" to ("Белый" to Color(0xFFE8EAF0)),
)

/**
 * Settings of a «Приборка с картой» card, kept in its style:
 * "nav:l=<left value>|r=<right value>|g=<ring, or a dial design id>|m=<map colours>|z=<zoom>|c=<accent>|h=<0 hides
 * the turn hints>|x=<0 hides the fuel and coolant scales>|t=<1 tilts 2GIS's map in perspective>" (no ';' after the prefix).
 * An empty style is the default look.
 */
data class NavCfg(
    val left: String = "speed",
    val right: String = "rpm",
    val gauge: String = "ring",
    val map: String = "night",
    val zoom: Double = 16.5,
    val accent: String = "sky",
    val hints: Boolean = true,
    val extras: Boolean = true,
    val tilt: Boolean = false,
) {
    fun format() = if (this == NavCfg()) "" else
        "nav:l=$left|r=$right|g=$gauge|m=$map|z=$zoom|c=$accent|h=${if (hints) 1 else 0}|x=${if (extras) 1 else 0}|t=${if (tilt) 1 else 0}"

    val color get() = (NavAccents.firstOrNull { it.first == accent } ?: NavAccents.first()).second.second

    companion object {
        fun parse(s: String): NavCfg {
            if (!s.startsWith("nav:")) return NavCfg()
            var c = NavCfg()
            s.removePrefix("nav:").split('|').forEach { p ->
                val v = p.substringAfter('=', "")
                when (p.substringBefore('=')) {
                    "l" -> if (v.isNotEmpty()) c = c.copy(left = v)
                    "r" -> if (v.isNotEmpty()) c = c.copy(right = v)
                    "g" -> if (v.isNotEmpty()) c = c.copy(gauge = v)
                    "m" -> if (v.isNotEmpty()) c = c.copy(map = v)
                    "z" -> v.toDoubleOrNull()?.let { c = c.copy(zoom = it.coerceIn(13.0, 19.0)) }
                    "c" -> if (v.isNotEmpty()) c = c.copy(accent = v)
                    "h" -> c = c.copy(hints = v != "0")
                    "x" -> c = c.copy(extras = v != "0")
                    "t" -> c = c.copy(tilt = v == "1")
                }
            }
            return c
        }
    }
}

/** The way just driven, for the line behind the arrow: the last ~2 km of fixes, kept while the card is on screen. */
private object Trail {
    val points = ArrayList<GeoPoint>()

    fun add(lat: Double, lon: Double): Boolean {
        points.lastOrNull()?.let { if (it.distanceToAsDouble(GeoPoint(lat, lon)) < 4.0) return false } // standing or GPS noise
        points += GeoPoint(lat, lon)
        if (points.size > 400) points.removeAt(0)
        return true
    }
}

/** [interactive] false: a preview (the settings window), a tap does nothing there. */
@Composable
fun NavClusterCard(info: String, m: Modifier, style: String = "", interactive: Boolean = true) {
    val ctx = LocalContext.current
    val cfg = remember(style) { NavCfg.parse(style) }
    val look = ClusterMaps.firstOrNull { it.id == cfg.map } ?: ClusterMaps.first()
    val accent = cfg.color
    val gps by Repo.gps.collectAsState()
    val hint by NavGuidance.hint.collectAsState()
    val metrics = rememberMetricCtx()
    val avail by Repo.rrAvailable.collectAsState()
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName // required by the OpenStreetMap tile servers
            osmdroidBasePath = ctx.filesDir.resolve("osmdroid")
            osmdroidTileCache = ctx.cacheDir.resolve("osmdroid-tiles") // tiles seen once work offline later
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            isClickable = false
            isTilesScaledToDpi = true
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    // the way driven: a thin quiet line behind; the route ahead: a wide soft line and a sharp one on top, in the accent colour
    val trail = remember { Polyline(map).also { map.overlayManager.add(it) } }
    val glow = remember { Polyline(map).also { map.overlayManager.add(it) } }
    val line = remember { Polyline(map).also { map.overlayManager.add(it) } }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    val route by Routing.route.collectAsState()
    val progress by Routing.progress.collectAsState()
    // 2GIS's dashboard service while the card is on screen (not in the settings preview)
    if (interactive) DisposableEffect(Unit) {
        kg.forester.sg.service.DgisDashboard.start(ctx)
        onDispose { kg.forester.sg.service.DgisDashboard.stop() }
    }
    val dgis by kg.forester.sg.service.DgisDashboard.info.collectAsState()
    val dgisFrame by kg.forester.sg.service.DgisDashboard.frame.collectAsState()
    val guiding = dgis?.guiding == true
    var heading by remember { mutableStateOf(0f) }
    LaunchedEffect(gps) {
        val g = gps ?: return@LaunchedEffect
        g.bearing?.let { if (g.speedKmh > 3f) heading = it } // a standing car keeps its last course
        if (g.lat == null || g.lon == null) return@LaunchedEffect
        Routing.init(ctx)
        Routing.onFix(g.lat, g.lon)
        // the destination of the last session: its route comes back once the car's position is known
        if (Routing.route.value == null && Routing.status.value.isEmpty()) {
            kg.forester.sg.service.Place.parse(Repo.settings.navDest)?.let { Routing.build(g.lat, g.lon, it) }
        }
        if (g.accuracyM < 40f && Trail.add(g.lat, g.lon)) { trail.setPoints(Trail.points); map.invalidate() }
    }
    LaunchedEffect(route, progress) {
        val r = route
        val pts = if (r == null) emptyList() else {
            val from = (progress?.ahead ?: 0).coerceIn(0, r.lat.size - 1)
            val g = Repo.gps.value
            (if (g?.lat != null && g.lon != null && progress != null) listOf(GeoPoint(g.lat, g.lon)) else emptyList()) +
                (from until r.lat.size).map { GeoPoint(r.lat[it], r.lon[it]) }
        }
        glow.setPoints(pts); line.setPoints(pts)
        map.invalidate()
    }
    var choosing by remember { mutableStateOf(false) }
    if (choosing) DestinationDialog { choosing = false }

    BoxWithConstraints(m.clip(RoundedCornerShape(18.dp)).background(look.bg)) {
        val d = LocalDensity.current
        val wPx = with(d) { maxWidth.toPx() }
        val hPx = with(d) { maxHeight.toPx() }
        // the car sits low in the middle, so more of the road ahead is in view
        val carY = hPx * 0.68f
        // 2GIS's map, the route on it, while it guides: asked at the card's size, only then (it renders it for us).
        // Its zoom is its own; a higher dpi draws it larger: the card's zoom setting scales it, x2 per two steps
        val dpi = with(d) { (density * 160 * Math.pow(2.0, (cfg.zoom - 15.0) / 2)).toInt() }
        // the card's own size (2GIS sends nothing for a frame much taller than its screen); the tilt stretches it (see below)
        val frameH = hPx.toInt()
        LaunchedEffect(interactive, guiding, wPx.toInt(), frameH, dpi) {
            kg.forester.sg.service.DgisDashboard.frames(if (interactive && guiding) Triple(wPx.toInt(), frameH, dpi) else null)
        }
        val ownMap = dgisFrame?.takeIf { guiding }?.first
        // the map set up only when what it shows changes: the card recomposes with every ECU sample (up to 12 a second),
        // and a fresh tile colour filter or centre each time redrew the whole map, so the map, the speed and the revs
        // moved in jerks on slower head units
        DisposableEffect(look) {
            map.overlayManager.tilesOverlay.setColorFilter(look.tiles)
            map.overlayManager.tilesOverlay.loadingBackgroundColor = look.bg.toArgb()
            map.overlayManager.tilesOverlay.loadingLineColor = look.bg.toArgb()
            map.invalidate()
            onDispose {}
        }
        DisposableEffect(accent, hPx) {
            trail.outlinePaint.apply { color = Color(0x66FFFFFF).toArgb(); strokeWidth = hPx * 0.008f; strokeCap = android.graphics.Paint.Cap.ROUND }
            glow.outlinePaint.apply { color = accent.copy(alpha = 0.3f).toArgb(); strokeWidth = hPx * 0.06f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
            line.outlinePaint.apply { color = accent.toArgb(); strokeWidth = hPx * 0.022f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
            map.invalidate()
            onDispose {}
        }
        DisposableEffect(cfg.zoom, carY, hPx) {
            if (map.zoomLevelDouble != cfg.zoom) map.controller.setZoom(cfg.zoom)
            map.setMapCenterOffset(0, (carY - hPx / 2).toInt())
            onDispose {}
        }
        // from fix to fix in one glide as long as the time between them (1–2 a second): the map drives on instead of jumping
        val g0 = gps
        val lastFix = remember { LongArray(1) }
        DisposableEffect(g0?.lat, g0?.lon, heading) {
            if (g0?.lat != null && g0.lon != null) {
                val now = android.os.SystemClock.uptimeMillis()
                val dt = now - lastFix[0]
                lastFix[0] = now
                if (dt > 3000) { map.controller.setCenter(GeoPoint(g0.lat, g0.lon)); map.mapOrientation = -heading }
                else map.controller.animateTo(GeoPoint(g0.lat, g0.lon), null, dt.coerceIn(250, 1500), -heading)
            } else map.mapOrientation = -heading
            onDispose {}
        }
        AndroidView({ map }, Modifier.fillMaxSize())
        if (ownMap != null) {
            val img = remember(ownMap) { ownMap.asImageBitmap() }
            val tilt = cfg.tilt
            val cardH = maxHeight
            // over our own map, which must not show at the edges the tilt leaves
            Box(Modifier.fillMaxSize().clipToBounds().background(look.bg)) {
                if (!tilt) Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                // tilted: the frame stretched up out of the card from its bottom edge, then laid back (2GIS's camera is flat,
                // the tilt is ours): the perspective takes the stretch back, and its far part fills the top as the road ahead
                else Image(img, null, Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .wrapContentHeight(Alignment.Bottom, unbounded = true).height(cardH * 3.4f)
                    .graphicsLayer {
                        rotationX = 52f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        cameraDistance = 22f * density
                        scaleX = 1.35f
                        // the car (in the middle of 2GIS's frame) lower down, more of the road ahead in view
                        translationY = size.height * 0.1f
                    }, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                if (tilt) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to look.bg.copy(alpha = 0.85f), 0.22f to Color.Transparent)))
            }
        }
        // the map fades into the gauges at the sides and into the frame at the top and bottom. This layer over the map
        // also takes the touches (the map view would swallow them): a short tap is «where to», and the down stays
        // unconsumed, so the screen's long press (editing) still works
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                if (interactive && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) choosing = true
            }
        }) {
            drawRect(Brush.horizontalGradient(0f to look.bg, 0.22f to look.bg.copy(alpha = 0.92f), 0.4f to Color.Transparent,
                0.6f to Color.Transparent, 0.78f to look.bg.copy(alpha = 0.92f), 1f to look.bg))
            drawRect(Brush.verticalGradient(0f to look.bg.copy(alpha = 0.7f), 0.18f to Color.Transparent, 0.85f to Color.Transparent, 1f to look.bg.copy(alpha = 0.8f)))
            // 2GIS draws the car on its own map
            if (ownMap == null) drawCar(Offset(size.width / 2, carY), size.height * 0.055f, accent)
            if (gps?.lat == null && ownMap == null) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = C.Sub.toArgb(); textSize = size.height * 0.05f; textAlign = android.graphics.Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText("ждём GPS", size.width / 2, carY + size.height * 0.12f, p)
            }
        }
        val dia: Dp = minOf(maxHeight * 0.94f, maxWidth * 0.31f)
        val side = (maxWidth * 0.015f)
        NavGauge(cfg, cfg.left, true, metrics, avail, accent, Modifier.align(Alignment.CenterStart).padding(start = side).size(dia))
        NavGauge(cfg, cfg.right, false, metrics, avail, accent, Modifier.align(Alignment.CenterEnd).padding(end = side).size(dia))
        // the clock on top in the middle, the next turn under it: the navigator's own when it prints one, else the route's
        Clock(Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.03f))
        val n = hint
        val p = progress
        val dg = dgis?.takeIf { guiding && it.maneuverDistance.isNotEmpty() }
        val shown = when {
            !cfg.hints -> null
            dg != null -> kg.forester.sg.service.DgisDashboard.kind(dg.maneuverIcon).let { k ->
                // an icon name it has not told us before: the words, and the notification's arrow if there is one
                Shown(dg.maneuverDistance, dg.maneuverDescription.ifEmpty { turnWords(k) }, if (k == null) n?.icon else null, k)
            }
            n != null -> Shown(n.title, n.text, n.icon, null)
            p?.next != null -> Shown(Routing.distanceText(p.toNextM), Routing.say(p.next) + p.next.road.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty(),
                null, Routing.kind(p.next))
            else -> null
        }
        if (shown != null) TurnHint(shown, accent, Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.03f + 24.dp).widthIn(max = maxWidth * 0.38f))
        // at the bottom: what is left of the route, or how to get one
        val status by Routing.status.collectAsState()
        val dgBottom = dgis?.takeIf { guiding }?.let { i ->
            listOf(i.total, i.remaining, i.arrival.takeIf { it.isNotEmpty() }?.let { "прибытие $it" }).filter { !it.isNullOrEmpty() }.joinToString(" · ")
        }?.ifEmpty { null }
        val bottom = when {
            dgBottom != null -> dgBottom
            p != null -> "${Routing.distanceText(p.leftM)} · ${(p.leftS / 60).toInt().coerceAtLeast(1)} мин · прибытие " +
                java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, p.leftS.toInt()) }.let { "%d:%02d".format(it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE)) }
            status.isNotEmpty() -> status
            route == null -> "нажмите, чтобы проложить маршрут"
            else -> ""
        }
        // what 2GIS warns of ahead: speed bumps, a camera, the limit, the light
        dgis?.takeIf { guiding && cfg.hints }?.let { i ->
            DgisAlerts(i, accent, Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.03f + 26.dp).widthIn(max = maxWidth * 0.4f))
        }
        if (bottom.isNotEmpty()) T(bottom, 13.sp, if (p != null || dgBottom != null) Color(0xFFE2E6EE) else Color(0xFF8E95AB), FontWeight.Medium, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.03f).clip(RoundedCornerShape(8.dp))
                .background(Color(0xB3070A12)).padding(horizontal = 10.dp, vertical = 3.dp))
        InfoButton("Приборка с картой", info, Modifier.align(Alignment.TopEnd))
    }
}

/** The arrow of the car, pointing up the screen (the map turns under it). */
private fun DrawScope.drawCar(c: Offset, s: Float, accent: Color) {
    drawCircle(accent.copy(alpha = 0.22f), s * 1.9f, c)
    drawCircle(Color.White, s * 1.15f, c)
    val p = Path().apply {
        moveTo(c.x, c.y - s * 0.85f); lineTo(c.x + s * 0.62f, c.y + s * 0.62f); lineTo(c.x, c.y + s * 0.3f); lineTo(c.x - s * 0.62f, c.y + s * 0.62f); close()
    }
    drawPath(p, accent)
}

/** One round gauge of the cluster: the ring with a big number, or a needle dial in one of the gauge designs. */
@Composable
private fun NavGauge(
    cfg: NavCfg, id: String, left: Boolean, ctx: MetricCtx, avail: List<kg.forester.sg.ssm.RrParam>, accent: Color, m: Modifier,
) {
    val ind = Metrics.of(id, avail)
    val v = ind?.read(ctx)
    val extra = if (!cfg.extras) null else if (left) fuelShare(ctx) else ctx.e.coolantC?.let { ((it - 40f) / 80f).coerceIn(0f, 1f) }
    if (cfg.gauge != "ring" && ind != null) {
        val (lo, hi, step) = niceScale(ind.min, ind.max)
        val div = if (hi >= 1000f) 1000f else 1f
        Box(m.clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xE6070A12))) {
            Dial(v, hi, step, div, ind.unit, if (div > 1f) "x1000" else null, ind.warnAbove, Modifier.fillMaxSize(), dialStyle(cfg.gauge),
                min = lo, format = ind.format)
        }
        return
    }
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (ind == null || v == null) 0f else ((v - ind.min) / (ind.max - ind.min)).coerceIn(0f, 1f),
        androidx.compose.animation.core.tween(240), label = "ring",
    )
    // where the red zone starts on the scale (the rpm limit), null without one
    val red = ind?.warnAbove?.let { ((it - ind.min) / (ind.max - ind.min)).coerceIn(0f, 1f) }
    val warn = ind?.warn(v) == true
    val lit = if (warn) C.Orange else accent
    BoxWithConstraints(m, contentAlignment = Alignment.Center) {
        val r = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val d = size.minDimension
            val sw = d * 0.04f
            val radius = d / 2 - sw * 1.4f
            val tl = Offset(center.x - radius, center.y - radius)
            val sz = Size(radius * 2, radius * 2)
            // a dark disc behind, so the gauge reads over any map
            drawCircle(Brush.radialGradient(listOf(Color(0xF2070A12), Color(0xE0070A12), Color(0x00070A12)), radius = d * 0.56f), d * 0.56f)
            // the track, its red zone, and the ticks inside it: every 5 % thin, every 25 % long
            drawArc(Color(0x1FFFFFFF), 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            red?.let { z -> drawArc(Color(0x66FF5252), 135f + 270f * z, 270f * (1f - z), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Butt)) }
            for (k in 0..20) {
                val f = k / 20f
                val a = Math.toRadians((135f + 270f * f).toDouble())
                val major = k % 5 == 0
                val r1 = radius - sw * 1.1f
                val r2 = r1 - d * (if (major) 0.06f else 0.03f)
                val col = when {
                    f <= shown -> lit.copy(alpha = 0.95f)
                    red != null && f >= red -> Color(0xAAFF5252)
                    else -> Color(if (major) 0x66FFFFFF else 0x33FFFFFF)
                }
                drawLine(col, Offset(center.x + r1 * kotlin.math.cos(a).toFloat(), center.y + r1 * kotlin.math.sin(a).toFloat()),
                    Offset(center.x + r2 * kotlin.math.cos(a).toFloat(), center.y + r2 * kotlin.math.sin(a).toFloat()),
                    if (major) d * 0.012f else d * 0.007f, StrokeCap.Round)
            }
            if (shown > 0.002f) {
                // the value: a soft glow under a bright stroke, its colour growing from the start, and a lit tip
                rotate(135f) {
                    val g = Brush.sweepGradient(0f to lit.copy(alpha = 0.35f), (270f * shown / 360f).coerceAtLeast(0.01f) to lit, 1f to lit)
                    drawArc(lit.copy(alpha = 0.18f), 0f, 270f * shown, false, tl, sz, style = Stroke(sw * 2.6f, cap = StrokeCap.Round))
                    drawArc(g, 0f, 270f * shown, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                val a = Math.toRadians((135f + 270f * shown).toDouble())
                val tip = Offset(center.x + radius * kotlin.math.cos(a).toFloat(), center.y + radius * kotlin.math.sin(a).toFloat())
                drawCircle(lit.copy(alpha = 0.35f), sw * 1.5f, tip)
                drawCircle(Color.White, sw * 0.62f, tip)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(ind?.text(v) ?: "—", (r.value * 0.26f).coerceIn(14f, 96f).sp, if (warn) C.Orange else Color.White, FontWeight.SemiBold, maxLines = 1)
            T(ind?.unit?.ifEmpty { ind.short } ?: "", (r.value * 0.075f).coerceIn(9f, 22f).sp, Color(0xFFB4BCCB), maxLines = 1)
            if (ind != null && ind.unit.isNotEmpty()) T(ind.short, (r.value * 0.055f).coerceIn(8f, 16f).sp, Color(0xFF8E95AB), maxLines = 1)
        }
        // the small scale in the gap at the bottom: fuel left on the left gauge, coolant on the right, with its icon
        extra?.let { f ->
            val low = if (left) f < 0.12f else f > 0.85f
            val col = if (low) (if (left) C.Orange else C.Red) else Color(0xFFE2E6EE)
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = r * 0.07f), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (left) Lucide.Fuel else Lucide.Thermometer, null, tint = col, modifier = Modifier.size((r.value * 0.07f).coerceIn(10f, 18f).dp))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(r * 0.22f).height((r.value * 0.022f).coerceIn(3f, 6f).dp).clip(RoundedCornerShape(50)).background(Color(0x2EFFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(50)).background(col))
                }
            }
        }
    }
}

private fun fuelShare(ctx: MetricCtx): Float? {
    val tank = Repo.settings.tankL
    return ctx.trip.fuelLeftL?.takeIf { tank > 0 }?.let { (it / tank).toFloat().coerceIn(0f, 1f) }
}

/** 2GIS's warnings in a row: the speed limit sign, a barrier (or speed bump) ahead, a camera, the traffic light. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DgisAlerts(i: kg.forester.sg.service.DgisDashboard.Info, accent: Color, m: Modifier) {
    val barrier = i.barrierDescription.ifEmpty { if (i.barrierIcon.isNotEmpty()) "Препятствие" else "" }
    val camera = i.cameraType.takeIf { it.isNotEmpty() }?.let { kg.forester.sg.service.DgisDashboard.cameraText(it) }
    val light = i.lightColor.takeIf { it.isNotEmpty() }
    if (barrier.isEmpty() && camera == null && light == null && i.speedLimit <= 0.0) return
    FlowRow(m, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // the road sign: a red ring, the number black (red over the limit)
        if (i.speedLimit > 0.0) Box(
            Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White)
                .border(4.5.dp, Color(0xFFE53935), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { T("%.0f".format(i.speedLimit), 14.sp, if (i.overLimit) Color(0xFFE53935) else Color.Black, FontWeight.Bold, maxLines = 1) }
        if (barrier.isNotEmpty()) AlertChip(Lucide.Construction, barrier, C.Orange)
        camera?.let { AlertChip(Lucide.Cctv, it, C.Orange) }
        light?.let { c ->
            val col = when (c) { "red" -> Color(0xFFFF5252); "yellow" -> Color(0xFFFFC107); else -> Color(0xFF34D399) }
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE6080B14)).border(1.dp, col.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // the three lamps, the lit one in its colour
                listOf("red", "yellow", "green").forEach { l ->
                    Box(Modifier.padding(horizontal = 1.5.dp).size(9.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (l == c) col else Color(0x33FFFFFF)))
                }
                if (i.lightCountdown >= 0) { Spacer(Modifier.width(6.dp)); T("${i.lightCountdown} с", 12.sp, Color.White, FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

/** A warning: an icon in its colour and a few words. */
@Composable
private fun AlertChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE6080B14)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        T(text, 12.sp, Color.White, FontWeight.Medium, maxLines = 1)
    }
}

/** A turn hint as shown: the arrow of its [kind] (see Turn), else the navigator's own arrow picture. */
private class Shown(val title: String, val text: String, val icon: android.graphics.Bitmap?, val kind: String?)

/** The arrow of a manoeuvre kind, Lucide's: a corner for a turn, a diagonal for a slight one, a hook for a U-turn. */
private fun turnIcon(kind: String?): androidx.compose.ui.graphics.vector.ImageVector? = when (kind) {
    kg.forester.sg.service.Turn.STRAIGHT -> Lucide.ArrowUp
    "slight_left" -> Lucide.ArrowUpLeft
    "slight_right" -> Lucide.ArrowUpRight
    "left" -> Lucide.CornerUpLeft
    "right" -> Lucide.CornerUpRight
    "sharp_left" -> Lucide.CornerLeftDown
    "sharp_right" -> Lucide.CornerRightDown
    kg.forester.sg.service.Turn.UTURN_LEFT -> Lucide.Undo2
    kg.forester.sg.service.Turn.UTURN_RIGHT -> Lucide.Redo2
    kg.forester.sg.service.Turn.ROUND -> Lucide.RotateCcw
    kg.forester.sg.service.Turn.FINISH -> Lucide.FlagTriangleRight
    else -> null
}

/** What to do, for a manoeuvre 2GIS gives no words for. */
private fun turnWords(kind: String?): String = when (kind) {
    kg.forester.sg.service.Turn.STRAIGHT -> "Прямо"
    "slight_left" -> "Левее"; "slight_right" -> "Правее"
    "left" -> "Налево"; "right" -> "Направо"
    "sharp_left" -> "Резко налево"; "sharp_right" -> "Резко направо"
    kg.forester.sg.service.Turn.UTURN_LEFT, kg.forester.sg.service.Turn.UTURN_RIGHT -> "Разворот"
    kg.forester.sg.service.Turn.ROUND -> "Круговое движение"
    kg.forester.sg.service.Turn.FINISH -> "Финиш"
    else -> ""
}

/** The next turn: its arrow on an accent disc, the distance big, what to do and the street under it. */
@Composable
private fun TurnHint(h: Shown, accent: Color, m: Modifier) {
    Row(
        m.clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(Color(0xF00E1424), Color(0xF0080B14))))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(start = 8.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val vec = turnIcon(h.kind)
        val pic = remember(h.icon) { h.icon?.asImageBitmap() }
        if (vec != null || pic != null) {
            Box(
                Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.75f)))),
                contentAlignment = Alignment.Center,
            ) {
                // the navigators' arrows are glyphs: drawn white, whatever colour the app gave them
                if (vec != null) Icon(vec, null, tint = Color.White, modifier = Modifier.size(30.dp))
                else Image(pic!!, null, Modifier.size(32.dp), colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column {
            if (h.title.isNotEmpty()) T(h.title, 22.sp, Color.White, FontWeight.Bold, maxLines = 1)
            if (h.text.isNotEmpty()) T(h.text, 13.sp, Color(0xFFC9CFDB), maxLines = 2)
        }
    }
}

@Composable
private fun Clock(m: Modifier) {
    var now by remember { mutableStateOf(java.util.Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.util.Calendar.getInstance()
            kotlinx.coroutines.delay(60_000L - now.get(java.util.Calendar.SECOND) * 1000L)
        }
    }
    T("%d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)), 15.sp, Color(0xFFC9CFDB), FontWeight.Medium, modifier = m)
}

/**
 * Settings of one «Приборка с картой» card, with the card itself as a live preview: the values of the two gauges,
 * their look (rings or a needle design), the accent colour, the map colours and zoom, the turn hints.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NavClusterEditor(style: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cfg = remember(style) { NavCfg.parse(style) }
    val avail by Repo.rrAvailable.collectAsState()
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = the left gauge
    fun set(c: NavCfg) = onChange(c.format())
    picking?.let { left ->
        ValuePicker(if (left) "Левый прибор" else "Правый прибор", if (left) cfg.left else cfg.right, onPick = {
            set(if (left) cfg.copy(left = it) else cfg.copy(right = it)); picking = null
        }, onClose = { picking = null })
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = LocalConfiguration.current
        Column(
            Modifier.widthIn(max = (screen.screenWidthDp * 0.96f).dp).heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .clip(RoundedCornerShape(16.dp)).background(C.Card).border(1.dp, C.Purple, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Приборка с картой", 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Как было", false, { set(NavCfg()) })
                Spacer(Modifier.width(8.dp))
                Chip("Готово", true, onClose)
            }
            Spacer(Modifier.height(10.dp))
            // at most a third of the screen tall, so the settings below keep their room
            val previewH = minOf((screen.screenWidthDp * 0.96f - 28f) / 2.8f, screen.screenHeightDp * 0.34f).dp
            NavClusterCard("preview.navcluster", Modifier.align(Alignment.CenterHorizontally).height(previewH).aspectRatio(2.8f), cfg.format(), interactive = false)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("Приборы:", 13.sp, C.Sub, modifier = Modifier.padding(top = 10.dp))
                    Chip("Слева: ${Metrics.of(cfg.left, avail)?.title ?: cfg.left}", false, { picking = true })
                    Chip("Справа: ${Metrics.of(cfg.right, avail)?.title ?: cfg.right}", false, { picking = false })
                    Chip(if (cfg.extras) "Шкалы топлива и ОЖ: есть" else "Шкалы топлива и ОЖ: нет", cfg.extras, { set(cfg.copy(extras = !cfg.extras)) })
                }
                T("Вид приборов: кольца или любая стрелка из дизайнов приборов (листается вбок)", 13.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                // a hundred designs: one row that scrolls sideways, so the colour and the map stay in sight
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Кольца", cfg.gauge == "ring", { set(cfg.copy(gauge = "ring")) })
                    DialStyles.filter { it.image == null }.forEach { st -> Chip(st.name, cfg.gauge == st.id, { set(cfg.copy(gauge = st.id)) }) }
                }
                T("Цвет", 13.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NavAccents.forEach { (id, v) -> Swatch(v.second, v.first, cfg.accent == id) { set(cfg.copy(accent = id)) } }
                }
                T("Карта", 13.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClusterMaps.forEach { mp -> Swatch(mp.high, mp.name, cfg.map == mp.id) { set(cfg.copy(map = mp.id)) } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("Приближение:", 13.sp, C.Sub, modifier = Modifier.padding(top = 10.dp))
                    listOf(15.0 to "Далеко", 16.0 to "Средне", 16.5 to "Близко", 17.5 to "Очень близко", 18.5 to "Максимально").forEach { (z, l) ->
                        Chip(l, cfg.zoom == z, { set(cfg.copy(zoom = z)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("Карта 2ГИС:", 13.sp, C.Sub, modifier = Modifier.padding(top = 10.dp))
                    Chip("Сверху", !cfg.tilt, { set(cfg.copy(tilt = false)) })
                    Chip("3D-наклон", cfg.tilt, { set(cfg.copy(tilt = true)) })
                }
                T("Подсказки навигатора", 13.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Показывать", cfg.hints, { set(cfg.copy(hints = true)) })
                    Chip("Скрыть", !cfg.hints, { set(cfg.copy(hints = false)) })
                }
                T(
                    "Пока 2ГИС ведёт по маршруту, на приборке его карта с маршрутом, манёвры, камеры, шлагбаумы и ограничения: " +
                        "он сам отдаёт их приборкам. С Яндексом, Google Картами и другими стрелка, расстояние и улица берутся из " +
                        "уведомления навигатора, а карта своя: свою они наружу не отдают.", 12.sp, C.Sub,
                )
                var allowed by remember { mutableStateOf(MediaSessions.allowed(ctx)) }
                if (!allowed) Chip("Разрешить доступ к уведомлениям", false, {
                    runCatching {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    allowed = MediaSessions.allowed(ctx)
                })
            }
        }
    }
}

/**
 * «Куда едем»: find a place (OpenStreetMap search) or pick a recent one; the route is built from where the car is
 * and, if ticked, the same destination goes to the navigator so it guides the same way. Also opens the navigator
 * as it is, and drops the route.
 */
@Composable
fun DestinationDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val route by Routing.route.collectAsState()
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Place>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var handoff by remember { mutableStateOf(Repo.settings.navHandoff) }
    val recent = remember { Repo.settings.navRecent.lines().mapNotNull(Place::parse) }
    fun go(p: Place) {
        Repo.settings.navDest = p.format()
        Repo.settings.navRecent = (listOf(p) + recent.filter { Routing.metres(it.lat, it.lon, p.lat, p.lon) > 50 }).take(8).joinToString("\n") { it.format() }
        val g = Repo.gps.value
        Routing.init(ctx)
        if (g?.lat != null && g.lon != null) Routing.build(g.lat, g.lon, p)
        else Routing.status.value = "ждём GPS, маршрут построится сам"
        if (handoff) sendToNavigator(ctx, p)
        onClose()
    }
    fun find() {
        val q = query.trim()
        if (q.length < 2) return
        note = "ищу…"
        val g = Repo.gps.value
        scope.launch {
            val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Routing.search(q, g?.lat, g?.lon) } }
            found = r.getOrDefault(emptyList())
            note = when {
                r.isFailure -> "нет связи с поиском, проверьте интернет"
                found.isEmpty() -> "ничего не нашлось, попробуйте иначе: «Ленина 10», «аэропорт»"
                else -> ""
            }
        }
    }
    // only on «Найти»: the OpenStreetMap search's rules forbid searching as the driver types
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = LocalConfiguration.current
        Column(
            Modifier.widthIn(max = minOf(820f, screen.screenWidthDp * 0.96f).dp).heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .clip(RoundedCornerShape(16.dp)).background(C.Card).border(1.dp, C.Purple, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Куда едем", 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Открыть навигатор", false, { openNavigator(ctx); onClose() })
                Spacer(Modifier.width(8.dp))
                Chip("Закрыть", false, onClose)
            }
            route?.let { r ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Маршрут: ${r.dest.name}", 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Chip("В навигатор", false, { sendToNavigator(ctx, r.dest); onClose() })
                    Spacer(Modifier.width(8.dp))
                    Chip("Сбросить маршрут", false, { Routing.clear(); onClose() })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormField("Адрес или место", query, Modifier.weight(1f)) { query = it }
                Spacer(Modifier.width(8.dp))
                Chip("Найти", true, { find() })
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (handoff) "Отправлять в навигатор: да" else "Отправлять в навигатор: нет", handoff, {
                    handoff = !handoff; Repo.settings.navHandoff = handoff
                })
                T("тот же адрес откроется в навигаторе, и он поведёт по похожему маршруту", 12.sp, C.Sub, modifier = Modifier.weight(1f))
            }
            if (note.isNotEmpty()) T(note, 13.sp, C.Sub, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.padding(top = 6.dp).verticalScroll(rememberScrollState())) {
                val list = found.ifEmpty { recent }
                if (found.isEmpty() && recent.isNotEmpty()) T("Недавние", 12.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                list.forEach { p ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { go(p) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        T(p.name, 15.sp, maxLines = 1)
                        if (p.address.isNotEmpty()) T(p.address, 12.sp, C.Sub, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Opens a route to [p] in the navigator picked for the map cards (else the first one installed), through the link
 * each of them understands; any other map app gets a plain geo link.
 */
fun sendToNavigator(ctx: android.content.Context, p: Place) {
    val pm = ctx.packageManager
    val pkg = Repo.settings.mapApp.ifEmpty {
        listOf("ru.yandex.yandexnavi", "ru.dublgis.dgismobile", "ru.yandex.yandexmaps", "com.google.android.apps.maps", "com.waze")
            .firstOrNull { pm.getLaunchIntentForPackage(it) != null }.orEmpty()
    }
    val uri = when (pkg) {
        "ru.yandex.yandexnavi" -> "yandexnavi://build_route_on_map?lat_to=${p.lat}&lon_to=${p.lon}"
        "ru.yandex.yandexmaps" -> "yandexmaps://maps.yandex.ru/?rtext=~${p.lat},${p.lon}&rtt=auto"
        "ru.dublgis.dgismobile" -> "dgis://2gis.ru/routeSearch/rsType/car/to/${p.lon},${p.lat}"
        "com.google.android.apps.maps" -> "google.navigation:q=${p.lat},${p.lon}"
        "com.waze" -> "waze://?ll=${p.lat},${p.lon}&navigate=yes"
        else -> "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${android.net.Uri.encode(p.name)})"
    }
    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (pkg.isNotEmpty()) i.setPackage(pkg)
    runCatching { ctx.startActivity(i) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
