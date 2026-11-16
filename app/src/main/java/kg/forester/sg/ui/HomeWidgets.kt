package kg.forester.sg.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Cog
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPinned
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Radio
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Repeat1
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Shuffle
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import kg.forester.sg.data.CarKind
import kg.forester.sg.data.I18n
import kg.forester.sg.data.Repo
import kg.forester.sg.service.DgisDashboard
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.NavGuidance
import kg.forester.sg.service.NowPlaying
import kg.forester.sg.service.Place
import kg.forester.sg.service.Routing
import kg.forester.sg.service.RulesHost
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/*
 * The home widgets of 1.0: «Карта с маршрутом», «Плеер», «Селектор и передача». Cards like the other extra cards
 * (ExtraCards.kt), on the home grid, «Динамика» and «Мой экран»; their widths per board are in HomeLayout.widgetSpan,
 * their row heights in driveRowWeight / myRowWeight. Each one lays itself out for the room it gets: a tall narrow card,
 * a wide low one, and anything a free layout makes of it.
 */

/** The widgets' names and icons for the «Добавить» menu (part of [ExtraCards]). */
val WidgetCards: Map<String, Pair<String, ImageVector>> = linkedMapOf(
    "navmap" to ("Карта с маршрутом" to Lucide.MapPinned),
    "player" to ("Плеер" to Lucide.Music),
    "gearsel" to ("Селектор и передача" to Lucide.Cog),
)

// ---- «Селектор и передача» --------------------------------------------------------------------------------------

/**
 * What the gear selector card shows, kept apart from the drawing so it can be tested.
 *
 * Where it comes from:
 * - Subaru over SSM with the 4EAT's unit (TCU 0x18) answering: [kg.forester.sg.ssm.TcuParams.selector] reads the range
 *   switch byte 0x6B: «R», «D», «3», «2», «1», «M» (the SportShift gate) or «P/N». The byte has no bit for P and none for
 *   N: zero is either of them, so both P and N light up halfway, never one of them guessed. The gear the unit holds
 *   (0x4A) goes under it in D, 3, 2, 1 and M.
 * - Anything else (another make over OBD-II, a manual, an automatic whose unit is silent): OBD-II has no standard PID for
 *   the lever, so there are no letters at all; the card shows the gear worked out from revs per km/h ([LensMath.gear]: this
 *   car's learned ratios, the Subaru gearboxes' tables only on a Subaru), and «—» with «нет данных» without it. No P, R or
 *   N is ever made up.
 *
 * The column for the 4EAT: P R N D 3 2 1 as on its lever when there is room for seven; with less room the three manual
 * ranges share the fifth place, «3·2·1» while the lever is elsewhere and the range itself («2») when it is in one
 * (the «S/L» place of other cars' selectors). In the SportShift gate it is P R N D M, as on the «АКПП» screen.
 */
object GearSlots {
    /** The shared place of the manual ranges in the short column. */
    const val LOW = "3·2·1"

    private val MANUAL = setOf("3", "2", "1")

    /** The positions shown for [range] (TcuParams.selector's text, null when the unit gave none) with room for seven or not. */
    fun slots(range: String?, roomForSeven: Boolean): List<String> = when {
        range == "M" -> listOf("P", "R", "N", "D", "M")
        roomForSeven -> listOf("P", "R", "N", "D", "3", "2", "1")
        else -> listOf("P", "R", "N", "D", LOW)
    }

    /** How lit [slot] is: 1 the position, 0.5 one of the two «P/N» can mean, 0 not it. */
    fun lit(range: String?, slot: String): Float = when {
        range == null -> 0f
        slot == range -> 1f
        range == "P/N" && (slot == "P" || slot == "N") -> 0.5f
        slot == LOW && range in MANUAL -> 1f
        else -> 0f
    }

    /** What [slot] reads: the shared manual place says which range it is in. */
    fun label(range: String?, slot: String): String = if (slot == LOW && range in MANUAL) range!! else slot

    /** The gear goes with a driving position only: in P, R or N the unit's gear number says nothing to the driver. */
    fun gearShown(range: String?, gear: Int?): Int? =
        gear?.takeIf { it > 0 && (range == null || range == "D" || range == "M" || range in MANUAL) }
}

@Composable
fun GearSelectorCard(info: String, m: Modifier) {
    val tcu by Repo.tcu.collectAsState()
    val live = Repo.live.collectAsState()
    val other by CarKind.other.collectAsState()
    val t = tcu
    val automatic = Repo.settings.gearbox == "at"
    // read where it changes only: the revs move many times a second, the gear seldom
    val estimated = live.slot(Triple(t == null, automatic, other)) { e ->
        if (t != null) null else LensMath.gear(null, e.rpm, e.speedKmh, automatic, RulesHost.diag.value.gears, other)
    }.value
    val connected = live.pick { it.rpm != null }
    Panel(m, padding = 10.dp) {
        Box(Modifier.fillMaxSize()) {
            if (t != null) SelectorColumn(t.range, GearSlots.gearShown(t.range, t.gear))
            else EstimatedGear(estimated, connected, automatic && !other)
            InfoButton("Селектор и передача", info, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The lever's positions in a column (or a row on a wide, low card), the current one on an accent pill. */
@Composable
private fun SelectorColumn(range: String?, gear: Int?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vertical = maxHeight >= maxWidth * 0.9f
        val caption = gear != null || range == "P/N" || range == null
        val capH = if (caption) LocalCar.current.secondary.lineDp() + 4.dp else 0.dp
        val along = if (vertical) maxHeight - capH else maxWidth
        val across = if (vertical) maxWidth else maxHeight - capH
        // all seven only when each still gets a big letter: the short column reads better than seven small ones
        val seven = along / 7 >= if (vertical) 46.dp else 60.dp
        val list = GearSlots.slots(range, seven)
        val cell = along / list.size
        // the pill: most of its cell along the column, as wide as a fat letter and some across it
        val pillAlong = cell * 0.84f
        val pillAcross = minOf(across * 0.92f, pillAlong * if (vertical) 2.1f else 0.8f)
        val w = if (vertical) pillAcross else pillAlong
        val h = if (vertical) pillAlong else pillAcross
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val slots: @Composable () -> Unit = {
                list.forEach { s ->
                    Box(if (vertical) Modifier.fillMaxWidth().height(cell) else Modifier.width(cell).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        GearPill(GearSlots.label(range, s), GearSlots.lit(range, s), w, h)
                    }
                }
            }
            if (vertical) Column(Modifier.fillMaxWidth().height(along)) { slots() }
            else Row(Modifier.fillMaxWidth().height(across), horizontalArrangement = Arrangement.Center) { slots() }
            if (caption) FitText(
                when {
                    gear != null -> "передача $gear"
                    range == "P/N" -> "P или N: блок АКПП их не различает"
                    else -> "положение не определено"
                },
                LocalCar.current.secondary, Modifier.fillMaxWidth().height(capH), C.Sub, textAlign = TextAlign.Center,
            )
        }
    }
}

/** One position: a big bold letter, on a filled accent pill when it is the one ([lit] 1), on a faint one for «P/N» (0.5). */
@Composable
private fun GearPill(label: String, lit: Float, w: Dp, h: Dp) {
    val fill by animateColorAsState(
        when {
            lit >= 1f -> C.activeFill(C.Violet)
            lit > 0f -> C.Violet.copy(alpha = 0.32f)
            else -> Color.Transparent
        },
        label = "gear pill",
    )
    val ink = when {
        lit >= 1f -> C.activeText(C.OnAccent)
        lit > 0f -> C.Text
        else -> C.Sub.copy(alpha = 0.75f)
    }
    // the letter fills the pill's height, a longer label («3·2·1») its width
    val px = minOf(h.value * 0.68f, w.value * 0.86f / (label.length * 0.62f).coerceAtLeast(0.62f))
    Box(Modifier.size(w, h).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        T(label, with(LocalDensity.current) { px.dp.toSp() }, ink, if (lit > 0f) FontWeight.Black else FontWeight.Bold, maxLines = 1)
    }
}

/** No selector from this car: the gear by revs and speed on the pill, or a dash that says why there is none. */
@Composable
private fun EstimatedGear(gear: String?, connected: Boolean, automatic: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lines = if (maxHeight > 150.dp) 3 else 2
        val capH = LocalCar.current.secondary.lineDp() * lines
        val room = maxHeight - capH - 6.dp
        val mw = maxWidth
        val h = minOf(room * 0.8f, mw * 0.62f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(room), contentAlignment = Alignment.Center) {
                GearPill(gear ?: "—", if (gear != null) 1f else 0f, minOf(mw * 0.9f, h * 1.1f), h)
            }
            Spacer(Modifier.height(6.dp))
            FitText(
                when {
                    !connected -> "нет данных"
                    gear == null -> "передача не определена: стоим, выжато сцепление или переключение"
                    automatic -> "передача по оборотам и скорости; положение селектора эта машина не передаёт"
                    else -> "передача по оборотам и скорости"
                },
                LocalCar.current.secondary, Modifier.fillMaxWidth().height(capH), C.Sub, maxLines = lines, textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- «Карта с маршрутом» -------------------------------------------------------------------------------------

/** The map's colours: the «Ночная» ones of the cluster card, OSM tiles recoloured by brightness. */
private val MapLow = Color(0xFF0A111E)
private val MapHigh = Color(0xFF3D5172)
private val MapBg = Color(0xFF070B14)
private val ChipBg = Color(0xE6080B14)
private val ChipText = Color(0xFFE2E6EE)

/** How much of the route ahead is drawn, and how often it is laid anew (as on the cluster card). */
private const val MAP_ROUTE_M = 10_000.0
private const val MAP_ROUTE_EVERY_MS = 3000L

/** "48 мин · 12,3 км, прибытие 09:21" for [leftS] seconds and [leftM] metres still to go. */
internal fun etaText(leftS: Double, leftM: Double, nowMs: Long = System.currentTimeMillis()): String {
    val min = (leftS / 60).roundToInt().coerceAtLeast(1)
    val time = if (min >= 60) "${min / 60} ч ${min % 60} мин" else "$min мин"
    val at = java.util.Calendar.getInstance().apply { timeInMillis = nowMs + (leftS * 1000).toLong() }
    return "$time · ${Routing.distanceText(leftM)}, прибытие " + "%02d:%02d".format(at.get(java.util.Calendar.HOUR_OF_DAY), at.get(java.util.Calendar.MINUTE))
}

/**
 * A wide card with the own dark map (OpenStreetMap), the car's arrow and the route line in the theme's accent.
 * What it has of navigation is the cluster card's: the route picked in «Куда едем» (OSRM, followed by GPS), the turn
 * from a navigator's notification, or 2GIS's own while it guides. Only with one of them are the turn (top left) and the
 * arrival (bottom right) shown; without, it is a clean map with the car where it is and turned along its course.
 * Round buttons on the left: zoom, «Куда едем» and the compass (north up or along the course), as many as the
 * card's height has room for. A tap on the map opens the navigator, as the mini-map does.
 */
@Composable
fun NavMapCard(info: String, m: Modifier) {
    val ctx = LocalContext.current
    val accent = C.Violet
    val gps by Repo.gps.collectAsState()
    var zoom by remember { mutableFloatStateOf(Repo.settings.mapCardZoom) }
    var northUp by remember { mutableStateOf(Repo.settings.mapCardNorth) }
    val map = remember {
        setUpOsm(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            isClickable = false
            isTilesScaledToDpi = true
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(mapRecolour(MapLow, MapHigh))
            overlayManager.tilesOverlay.loadingBackgroundColor = MapBg.toArgb()
            overlayManager.tilesOverlay.loadingLineColor = MapBg.toArgb()
        }
    }
    // the route ahead: a wide soft line and a sharp one on top, in the accent colour
    val glow = remember { Polyline(map).also { map.overlayManager.add(it) } }
    val line = remember { Polyline(map).also { map.overlayManager.add(it) } }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    val rig = remember { CameraRig(map) }
    DisposableEffect(rig) { onDispose { SmoothDriver.drop(rig) } }
    // 2GIS's turn and arrival while it guides (its map frames are the cluster's, never asked for here)
    DisposableEffect(Unit) {
        DgisDashboard.start(ctx)
        onDispose { DgisDashboard.stop() }
    }
    val route by Routing.route.collectAsState()
    val progress by Routing.progress.collectAsState()
    val hint by NavGuidance.hint.collectAsState()
    val dgis by DgisDashboard.info.collectAsState()
    LaunchedEffect(gps) {
        val g = gps ?: return@LaunchedEffect
        rig.onFix(g)
        if (g.lat == null || g.lon == null) return@LaunchedEffect
        Routing.init(ctx)
        Routing.follow(g)
        // the destination of the last session: its route comes back once the car's position is known
        if (Routing.route.value == null && Routing.status.value.isEmpty()) Place.parse(Repo.settings.navDest)?.let { Routing.build(g.lat, g.lon, it) }
    }
    // the line is laid anew every few seconds, and only as far as the map can show
    LaunchedEffect(route) {
        while (true) {
            val r = route
            val p = Routing.progress.value
            val pts = if (r == null) emptyList() else {
                val from = (p?.ahead ?: 0).coerceIn(0, r.lat.size - 1)
                val g = Repo.gps.value
                (if (g?.lat != null && g.lon != null && p != null) listOf(GeoPoint(g.lat, g.lon)) else emptyList()) +
                    (from..lastRouteIndex(r.lat, r.lon, from, MAP_ROUTE_M)).map { GeoPoint(r.lat[it], r.lon[it]) }
            }
            glow.setPoints(pts); line.setPoints(pts)
            map.invalidate()
            if (r == null) break
            delay(MAP_ROUTE_EVERY_MS)
        }
    }
    var choosing by remember { mutableStateOf(false) }
    if (choosing) DestinationDialog { choosing = false }

    BoxWithConstraints(m.clip(RoundedCornerShape(18.dp)).background(MapBg).border(1.dp, C.Border, RoundedCornerShape(18.dp))) {
        val d = LocalDensity.current
        val wPx = with(d) { maxWidth.toPx() }
        val hPx = with(d) { maxHeight.toPx() }
        // along the course the car sits low, so more of the road ahead is in view; north up it is in the middle
        val carY = if (northUp) hPx / 2f else hPx * 0.64f
        DisposableEffect(accent, hPx) {
            glow.outlinePaint.apply { color = accent.copy(alpha = 0.3f).toArgb(); strokeWidth = maxOf(hPx * 0.05f, 10f); strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
            line.outlinePaint.apply { color = accent.toArgb(); strokeWidth = maxOf(hPx * 0.02f, 5f); strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
            map.invalidate()
            onDispose {}
        }
        // the map view is larger than the card and moved by a layer between its real moves (see CameraRig)
        val fit = remember(wPx, hPx, carY) { CameraAnchor(wPx.toInt(), hPx.toInt(), wPx / 2f, carY) }
        DisposableEffect(zoom, northUp, fit) {
            if (map.zoomLevelDouble != zoom.toDouble()) map.controller.setZoom(zoom.toDouble())
            map.setMapCenterOffset(fit.centerOffsetX, fit.centerOffsetY)
            rig.use(fit, northUp)
            onDispose {}
        }
        val moved = remember(rig, fit) { Modifier.cameraMap(rig, fit) }
        AndroidView({ map }, moved)
        // the edges fade into the card; this layer also takes the touches (the map view would swallow them): a short tap
        // opens the navigator, and the down stays unconsumed, so the screen's long press (editing) still works
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) openNavigator(ctx)
            }
        }) {
            drawRect(Brush.verticalGradient(0f to MapBg.copy(alpha = 0.55f), 0.2f to Color.Transparent, 0.8f to Color.Transparent, 1f to MapBg.copy(alpha = 0.7f)))
            drawRect(Brush.horizontalGradient(0f to MapBg.copy(alpha = 0.5f), 0.14f to Color.Transparent, 0.86f to Color.Transparent, 1f to MapBg.copy(alpha = 0.5f)))
            val c = Offset(size.width / 2f, carY)
            // north up the arrow turns with the course; along it the map turns under an arrow pointing up
            val turn = if (northUp) rig.heading.floatValue else 0f
            rotate(turn, c) { drawCar(c, (size.minDimension * 0.06f).coerceIn(9f, 40f), accent) }
        }
        if (gps?.lat == null) {
            val why by Repo.gpsStatus.collectAsState()
            T(why.ifEmpty { "ждём GPS" }, 13.sp, ChipText, maxLines = 2, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(top = 64.dp).widthIn(max = maxWidth * 0.7f)
                    .clip(RoundedCornerShape(10.dp)).background(ChipBg).padding(horizontal = 10.dp, vertical = 4.dp))
        }

        // what navigation there is: 2GIS while it guides, else the navigator's notification, else the own route
        val dg = dgis?.takeIf { it.guiding }
        val p = progress
        val turnShown = when {
            dg != null && dg.maneuverDistance.isNotEmpty() -> DgisDashboard.kind(dg.maneuverIcon).let { k ->
                Shown(dg.maneuverDistance, dg.maneuverDescription.ifEmpty { turnWords(k) }, if (k == null) hint?.icon else null, k)
            }
            hint != null -> hint!!.let { n -> Shown(kg.forester.sg.data.DistanceUnits.navText(n.title), n.text, n.icon, null) }
            p?.next != null -> Shown(Routing.distanceText(p.toNextM), Routing.say(p.next) + p.next.road.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty(),
                null, Routing.kind(p.next))
            else -> null
        }
        val eta = when {
            dg != null -> listOf(kg.forester.sg.data.DistanceUnits.navText(dg.total), kg.forester.sg.data.DistanceUnits.navText(dg.remaining),
                dg.arrival.takeIf { it.isNotEmpty() }?.let { "прибытие $it" }).filter { !it.isNullOrEmpty() }.joinToString(" · ").ifEmpty { null }
            p != null -> etaText(p.leftS, p.leftM)
            else -> null
        }
        // no arrival yet: what became of the destination picked here («строю маршрут…», «нет связи с сервером маршрутов»)
        val status by Routing.status.collectAsState()
        val edge = 10.dp
        if (turnShown != null) TurnHint(turnShown, accent, Modifier.align(Alignment.TopStart).padding(edge).widthIn(max = maxWidth * 0.62f))
        val bottom = eta ?: status.takeIf { it.isNotEmpty() }
        if (bottom != null) T(bottom, 13.sp, if (eta != null) ChipText else ChipText.copy(alpha = 0.7f), FontWeight.Medium, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(edge).widthIn(max = maxWidth * 0.7f)
                .clip(RoundedCornerShape(12.dp)).background(ChipBg).border(1.dp, if (eta != null) accent.copy(alpha = 0.45f) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp))

        // the round buttons, as many as fit under the turn: zoom first, then «Куда едем», then the compass
        val btn = (maxHeight * 0.15f).coerceIn(36.dp, 48.dp)
        val gap = 8.dp
        val top = if (turnShown != null) 72.dp else edge
        val fit4 = ((maxHeight - top - edge + gap) / (btn + gap)).toInt().coerceIn(0, 4)
        val count = if (maxWidth < 160.dp) 0 else fit4
        Column(Modifier.align(Alignment.BottomStart).padding(edge), verticalArrangement = Arrangement.spacedBy(gap)) {
            if (count >= 4) Compass(btn, northUp, rig) { northUp = !northUp; Repo.settings.mapCardNorth = northUp }
            if (count >= 3) MapButton(Lucide.Search, "Куда едем", btn) { choosing = true }
            if (count >= 2) {
                MapButton(Lucide.Plus, "Приблизить", btn, zoom < 19f) { zoom = (zoom + 1f).coerceAtMost(19f); Repo.settings.mapCardZoom = zoom }
                MapButton(Lucide.Minus, "Отдалить", btn, zoom > 13f) { zoom = (zoom - 1f).coerceAtLeast(13f); Repo.settings.mapCardZoom = zoom }
            }
        }
        // the "i", on a dark disc at the corner so it reads over the map
        InfoButton("Карта с маршрутом", info, Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(ChipBg))
    }
}

/** A round button over the map. */
@Composable
private fun MapButton(icon: ImageVector, what: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(ChipBg).border(1.dp, Color(0x33FFFFFF), CircleShape)
            .tapClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, what, tint = if (enabled) ChipText else ChipText.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.5f)) }
}

/**
 * The compass button: its needle points north on the turned map (red end), and a tap switches between north up and
 * along the course; north up it stands still with the accent ring round it.
 */
@Composable
private fun Compass(size: Dp, northUp: Boolean, rig: CameraRig, onClick: () -> Unit) {
    val accent = C.Violet
    Box(
        Modifier.size(size).clip(CircleShape).background(ChipBg)
            .border(if (northUp) 2.dp else 1.dp, if (northUp) accent else Color(0x33FFFFFF), CircleShape)
            .tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) {
            // along the course the map is turned by the heading: north is that much to the left of up
            val turn = if (northUp) 0f else -rig.heading.floatValue
            val c = center
            val r = this.size.minDimension * 0.5f
            rotate(turn, c) {
                val n = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.32f, c.y); lineTo(c.x - r * 0.32f, c.y); close() }
                val s = Path().apply { moveTo(c.x, c.y + r); lineTo(c.x + r * 0.32f, c.y); lineTo(c.x - r * 0.32f, c.y); close() }
                drawPath(n, Color(0xFFFF5252))
                drawPath(s, ChipText.copy(alpha = 0.8f))
            }
        }
    }
}

// ---- «Плеер» ------------------------------------------------------------------------------------------------------

/**
 * The player card: the cover, what plays, the progress with the time gone and the length, and shuffle, previous, the big
 * play / pause in the accent, next, repeat. The same track and the same buttons as the strip under the screens
 * ([MiniPlayerState.shared], [miniStep], [miniPress]): whatever plays, the own player, Bluetooth, the tuner or another
 * app's session. Shuffle and repeat are the own player's (Android gives no way to set them in another app's session):
 * with any other source they stay greyed. A tap on the words opens Media.
 */
@Composable
fun PlayerCard(info: String, m: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = MiniPlayerState.shared
    val now = state.track
    val own by LocalMusic.state.collectAsState()
    val files = now?.source == "files"
    // the position: the own player asked twice a second, the others as the strip has it (its share of the length)
    var posMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now?.source, now?.title, now?.durMs) {
        while (true) {
            val n = now ?: break
            posMs = if (n.source == "files") LocalMusic.position() else (state.share.floatValue * n.durMs).toLong()
            delay(500)
        }
    }
    fun openMedia() { Repo.openScreen.value = System.nanoTime() to "Media" }
    Panel(m, padding = 12.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val mw = maxWidth
            val mh = maxHeight
            val wide = mw >= mh * 1.15f
            val roomy = maxHeight >= if (wide) 120.dp else 240.dp
            val play = (if (wide) maxHeight * 0.34f else maxWidth * 0.26f).coerceIn(44.dp, 76.dp)
            val small = play * 0.72f
            val controls: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    val extras = mw >= if (wide) 380.dp else 240.dp
                    if (extras) PlayerButton(Lucide.Shuffle, "Перемешать", small, active = files && own.shuffle, enabled = files) {
                        LocalMusic.setShuffle(ctx, !own.shuffle)
                    }
                    PlayerButton(Lucide.SkipBack, "Предыдущий", small, enabled = now != null) { now?.let { miniStep(ctx, it, false) } }
                    PlayButton(now, play) { if (now == null) openMedia() else miniPress(ctx, scope, now) }
                    PlayerButton(Lucide.SkipForward, "Следующий", small, enabled = now != null) { now?.let { miniStep(ctx, it, true) } }
                    if (extras) PlayerButton(
                        if (files && own.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Lucide.Repeat1 else Lucide.Repeat, "Повтор", small,
                        active = files && own.repeat != androidx.media3.common.Player.REPEAT_MODE_OFF, enabled = files,
                    ) { LocalMusic.cycleRepeat(ctx) }
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Cover(now, minOf(mh, mw * 0.36f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    TrackWords(now, Modifier.fillMaxWidth().tapClickable(onClick = ::openMedia))
                    if (roomy) Progress(now, posMs)
                    controls()
                }
            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                val words = LocalCar.current.label.lineDp() + LocalCar.current.secondary.lineDp()
                val coverRoom = mh - words - play - (if (roomy) 40.dp else 0.dp) - 24.dp
                if (coverRoom >= 48.dp) Cover(now, minOf(coverRoom, mw))
                TrackWords(now, Modifier.fillMaxWidth().tapClickable(onClick = ::openMedia), center = true)
                if (roomy) Progress(now, posMs)
                controls()
            }
            InfoButton("Плеер", info, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun Cover(now: NowPlaying?, side: Dp) {
    Box(Modifier.size(side).clip(RoundedCornerShape(14.dp)).background(C.CardHi), contentAlignment = Alignment.Center) {
        val art = now?.art
        if (art != null) {
            val img = remember(art) { art.asImageBitmap() }
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Icon(if (now?.source == "fm" || now?.source == "stream") Lucide.Radio else Lucide.Music, null, tint = C.Sub, modifier = Modifier.size(side * 0.4f))
    }
}

/** The title big, the artist under it; with nothing known, what to do about it. */
@Composable
private fun TrackWords(now: NowPlaying?, modifier: Modifier, center: Boolean = false) {
    val car = LocalCar.current
    val align = if (center) TextAlign.Center else null
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        if (now == null) {
            FitText("Ничего не играет", car.label, Modifier.fillMaxWidth(), C.Text, FontWeight.SemiBold, textAlign = align)
            FitText("Нажмите, чтобы открыть «Медиа»", car.secondary, Modifier.fillMaxWidth(), C.Sub, textAlign = align)
            return@Column
        }
        // a song's name isn't translated; the tuner's «МГц» and «Музыка с телефона» are the app's
        val title = if (now.source == "fm" || now.title == "Музыка с телефона") now.title else I18n.raw(now.title)
        T(title, car.label, C.Text, FontWeight.SemiBold, maxLines = 1, textAlign = align)
        if (now.artist.isNotBlank()) T(I18n.raw(now.artist), car.secondary, C.Sub, maxLines = 1, textAlign = align)
    }
}

/** The bar with the time gone at its left end and the length at its right; a station has no length: «в эфире». */
@Composable
private fun Progress(now: NowPlaying?, posMs: Long) {
    val dur = now?.durMs ?: 0L
    val car = LocalCar.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.shade(0.35f))) {
            if (dur > 0) Box(Modifier.fillMaxHeight().fillMaxWidth((posMs.toFloat() / dur).coerceIn(0f, 1f)).clip(CircleShape).background(C.Violet))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            when {
                now == null -> {}
                dur > 0 -> {
                    T(clock(posMs), car.secondary, C.Sub, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    T(clock(dur), car.secondary, C.Sub, maxLines = 1)
                }
                now.source == "fm" || now.source == "stream" -> T(if (now.playing) "в эфире" else "пауза", car.secondary, C.Sub, maxLines = 1)
            }
        }
    }
}

/** "3:07", "1:02:45". */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PlayerButton(icon: ImageVector, what: String, size: Dp, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).tapClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, what, tint = when { !enabled -> C.Sub.copy(alpha = 0.35f); active -> C.Violet; else -> C.Text }, modifier = Modifier.size(size * 0.5f))
    }
}

/** The big round button in the accent: pause while it plays, play otherwise; the tuner's is its «on». */
@Composable
private fun PlayButton(now: NowPlaying?, size: Dp, onClick: () -> Unit) {
    val playing = now?.playing == true
    Box(
        Modifier.size(size).clip(CircleShape).background(C.activeFill(C.Violet)).tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing && now?.source != "fm") Lucide.Pause else Lucide.Play, if (playing) "Пауза" else "Играть",
            tint = C.activeText(C.OnAccent), modifier = Modifier.size(size * 0.46f))
    }
}
