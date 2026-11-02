package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.BatteryCharging
import com.composables.icons.lucide.Fuel
import com.composables.icons.lucide.Lucide
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Shortcuts
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.sin

/** How the arrow in the middle looks. */
enum class MapArrow { NFS, GTA, BLIP }

/** Health / armour bars of the GTA HUDs: round around the map (GTA IV) or straight under it (GTA V). */
enum class MapBars { NONE, ARC, BELOW }

/**
 * Look of the mini-map, after the radars of the racing and GTA games. The OpenStreetMap tiles are recoloured
 * from their brightness: [low] for the darkest parts (buildings, water), [high] for the brightest (roads);
 * [tiles] null keeps the normal map colours.
 */
data class MapStyle(
    val id: String,
    val name: String,
    val low: Color?,
    val high: Color = Color.White,
    val bg: Color = Color(0xFF101420),
    val square: Boolean = false,
    val bezel: Color = Color(0xFF3A3F4B),
    val bezelWidth: Float = 0.07f,     // of the radius
    val inner: Color? = Color(0xFF8A8F99),
    val ticks: Color? = null,
    val glow: Color? = null,
    val north: Color? = null,          // "N" marker on the edge, turning with the map
    val arrow: MapArrow = MapArrow.NFS,
    val arrowColor: Color = Color(0xFFF59E0B),
    val bars: MapBars = MapBars.NONE,
) {
    val tiles get() = low?.let { gradientFilter(it, high) }
}

/** Recolours by brightness: the OSM background and roads are bright, buildings and water darker. */
private fun gradientFilter(low: Color, high: Color): ColorMatrixColorFilter {
    val lo = 212f // buildings (≈210) and water end up darkest, the OSM background (≈239) in the middle, roads brightest
    val k = 1f / (255f - lo)
    fun row(a: Float, b: Float): FloatArray {
        val d = (b - a) * 255f * k
        return floatArrayOf(0.30f * d, 0.59f * d, 0.11f * d, 0f, a * 255f - d * lo)
    }
    return ColorMatrixColorFilter(
        ColorMatrix(row(low.red, high.red) + row(low.green, high.green) + row(low.blue, high.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)),
    )
}

val MapStyles = listOf(
    MapStyle(
        "nfs_mw", "NFS Most Wanted", low = Color(0xFF0E1726), high = Color(0xFF9FB4D0),
        ticks = Color(0x99FFFFFF),
    ),
    MapStyle(
        "nfs_ug2", "NFS Underground 2", low = Color(0xFF07131A), high = Color(0xFF46E3D6), bg = Color(0xFF05090C),
        bezel = Color(0xFF0B2A33), inner = Color(0xFF22D3EE), glow = Color(0xFF22D3EE), arrowColor = Color(0xFFFFE14D),
    ),
    MapStyle(
        "nfs_carbon", "NFS Carbon", low = Color(0xFF0A0A0B), high = Color(0xFFB8BCC4), bg = Color(0xFF050506),
        bezel = Color(0xFF1C1C1F), inner = Color(0xFFFF7A1A), glow = Color(0xFFFF7A1A), arrowColor = Color(0xFFFF7A1A),
    ),
    MapStyle(
        "nfs_hp", "NFS Hot Pursuit", low = Color(0xFF16263A), high = Color(0xFFE6F1FF),
        bezel = Color(0xCC0B1220), inner = Color(0xFFE5232A), north = Color(0xFF5BC0EB), arrowColor = Color.White,
    ),
    MapStyle(
        "nfs_ug", "NFS Underground", low = Color(0xFF0B0A1E), high = Color(0xFF9B8CFF), bg = Color(0xFF07061A),
        bezel = Color(0xFF231F4D), inner = Color(0xFFB59CFF), glow = Color(0xFF7C5CFF), ticks = Color(0x88B59CFF), arrowColor = Color(0xFF3CFF8F),
    ),
    MapStyle(
        "gta3", "GTA III", low = Color(0xFF3B3F45), high = Color(0xFFD6D6D6), bg = Color(0xFF2A2D31),
        bezel = Color(0xFF000000), bezelWidth = 0.05f, inner = null, north = Color.White,
        arrow = MapArrow.GTA, arrowColor = Color.White,
    ),
    MapStyle(
        "gta_vc", "GTA Vice City", low = Color(0xFF2D6C8F), high = Color(0xFFFFE3F2), bg = Color(0xFF1E5470),
        bezel = Color(0xFFFF3EA5), bezelWidth = 0.06f, inner = Color(0xFF29E0FF), glow = Color(0xFFFF3EA5), north = Color.White,
        arrow = MapArrow.GTA, arrowColor = Color.White,
    ),
    MapStyle(
        "gta_sa", "GTA San Andreas", low = Color(0xFF4B5A3C), high = Color(0xFFE2DCC8), bg = Color(0xFF3E4B33),
        bezel = Color(0xFF000000), bezelWidth = 0.09f, inner = null, north = Color.White,
        arrow = MapArrow.GTA, arrowColor = Color.White,
    ),
    MapStyle(
        "gta_iv", "GTA IV", low = Color(0xFF24272B), high = Color(0xFFB9BEC6), bg = Color(0xFF1B1D20),
        bezel = Color(0xCC0E0F11), bezelWidth = 0.1f, inner = null, north = Color.White,
        arrow = MapArrow.BLIP, arrowColor = Color.White, bars = MapBars.ARC,
    ),
    MapStyle(
        "gta_v", "GTA V", low = Color(0xFF2A2E33), high = Color(0xFF8E959E), bg = Color(0xFF22262A),
        square = true, bezel = Color(0xFF0D0F11), bezelWidth = 0.03f, inner = null, north = Color.White,
        arrow = MapArrow.BLIP, arrowColor = Color.White, bars = MapBars.BELOW,
    ),
    MapStyle(
        "gta_chinatown", "GTA Chinatown Wars", low = Color(0xFF1A1030), high = Color(0xFFFFC94D), bg = Color(0xFF120A22),
        bezel = Color(0xFFE11D48), bezelWidth = 0.06f, inner = Color(0xFFFFC94D), north = Color(0xFFFFC94D),
        arrow = MapArrow.GTA, arrowColor = Color(0xFFFFC94D),
    ),
    MapStyle(
        "day", "Обычная карта", low = null, bg = Color(0xFFF2EFE9),
        bezel = Color(0xFF2A2F3A), inner = Color(0xFF8A8F99), arrowColor = Color(0xFF2563EB),
    ),
)

/**
 * Settings of a mini-map card, kept in its style: "map:<design>|z=<zoom>|n=<1 if north up>|b=<0 hides the fuel and battery bars of the GTA looks>" (no ';' or ':'
 * after the prefix: those separate the cards and their fields in the saved layout).
 * An empty style is the default look.
 */
data class MapCfg(val style: String = "nfs_mw", val zoom: Double = 16.5, val northUp: Boolean = false, val bars: Boolean = true) {
    fun format() = "map:$style|z=$zoom|n=${if (northUp) 1 else 0}|b=${if (bars) 1 else 0}"
    val look get() = (MapStyles.firstOrNull { it.id == style } ?: MapStyles.first()).let { if (bars) it else it.copy(bars = MapBars.NONE) }

    companion object {
        fun parse(s: String): MapCfg {
            if (!s.startsWith("map:")) return MapCfg()
            val parts = s.removePrefix("map:").split('|')
            var c = MapCfg(style = parts.first())
            parts.drop(1).forEach { p ->
                when {
                    p.startsWith("z=") -> p.removePrefix("z=").toDoubleOrNull()?.let { c = c.copy(zoom = it.coerceIn(13.0, 19.0)) }
                    p.startsWith("n=") -> c = c.copy(northUp = p == "n=1")
                    p.startsWith("b=") -> c = c.copy(bars = p != "b=0")
                }
            }
            return c
        }
    }
}

private fun setUpOsm(ctx: Context) {
    Configuration.getInstance().apply {
        userAgentValue = ctx.packageName // required by the OpenStreetMap tile servers
        osmdroidBasePath = ctx.filesDir.resolve("osmdroid")
        osmdroidTileCache = ctx.cacheDir.resolve("osmdroid-tiles") // tiles seen once work offline later
    }
}

/**
 * Mini-map in the style of a game radar: our own OpenStreetMap map (another app's map cannot be shown inside
 * this one), centred on the GPS position and turned so the course points up (or north up), with the player
 * arrow in the middle. A tap opens the navigator (the "Навигатор" shortcut, else any map app).
 * Health / armour bars of the GTA looks show the fuel left and the battery voltage.
 * A long press opens the design window; [onStyle] saves what is picked there.
 */
@Composable
fun MiniMapCard(styleText: String, modifier: Modifier, onStyle: (String) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    if (picking) MapStylePicker(styleText, onStyle) { picking = false }
    val cfg = remember(styleText) { MapCfg.parse(styleText) }
    val look = cfg.look
    val gps by Repo.gps.collectAsState()
    val map = remember {
        setUpOsm(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            isClickable = false
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    val lat = gps?.lat
    val rig = remember { CameraRig(map) }
    DisposableEffect(rig) { onDispose { SmoothDriver.drop(rig) } }
    LaunchedEffect(gps) { gps?.let { rig.onFix(it) } }
    val fuel = fuelFraction()
    val volts by Repo.live.collectAsState()
    val armour = volts.batteryV?.let { ((it - 11.5f) / 3f).coerceIn(0f, 1f) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val belowBars = look.bars == MapBars.BELOW
        val d = minOf(maxWidth, if (belowBars) maxHeight / 1.14f else maxHeight)
        val shape: Shape = if (look.square) RoundedCornerShape(d * 0.06f) else CircleShape
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(d).clip(shape).background(look.bg), contentAlignment = Alignment.Center) {
                // the tile colours are worked out once per look, not for each recomposition
                DisposableEffect(look) {
                    map.overlayManager.tilesOverlay.setColorFilter(look.tiles)
                    map.overlayManager.tilesOverlay.loadingBackgroundColor = look.bg.toArgb()
                    map.overlayManager.tilesOverlay.loadingLineColor = look.bg.toArgb()
                    map.invalidate()
                    onDispose {}
                }
                // the map view is larger than the card and moved by a layer between its real moves (see CameraRig)
                val dPx = with(LocalDensity.current) { d.toPx() }
                val fit = remember(dPx) { CameraAnchor(dPx.toInt(), dPx.toInt(), dPx / 2, dPx / 2) }
                DisposableEffect(cfg.zoom, cfg.northUp, fit) {
                    if (map.zoomLevelDouble != cfg.zoom) map.controller.setZoom(cfg.zoom)
                    rig.use(fit, cfg.northUp)
                    onDispose {}
                }
                val moved = remember(rig, fit) { Modifier.cameraMap(rig, fit) }
                AndroidView({ map }, moved)
                if (lat == null) {
                    val why by Repo.gpsStatus.collectAsState()
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        T("Нет GPS", 13.sp, if (look.low == null) Color.Black else C.Sub)
                        if (why.isNotEmpty()) T(why, 10.sp, if (look.low == null) Color.DarkGray else C.Sub, maxLines = 3)
                    }
                }
                // frame and arrow on top of the map, so they also take the taps (the map itself never pans)
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { openNavigator(ctx) }, onLongPress = { picking = true }) }) {
                    val heading = rig.heading.floatValue
                    drawMapFrame(look, if (cfg.northUp) 0f else heading, if (cfg.northUp) heading else 0f, fuel, armour)
                }
                if (look.bars == MapBars.ARC) {
                    // what the GTA IV ring halves mean: fuel on the left, battery on the right
                    BarIcon(Lucide.Fuel, Color(0xFF55B95A), d * 0.11f, Modifier.align(Alignment.CenterStart).padding(start = d * 0.015f))
                    BarIcon(Lucide.BatteryCharging, Color(0xFF4FA3E0), d * 0.11f, Modifier.align(Alignment.CenterEnd).padding(end = d * 0.015f))
                }
            }
            if (belowBars) {
                Spacer(Modifier.height(d * 0.03f))
                Row(Modifier.size(d, d * 0.09f), horizontalArrangement = Arrangement.spacedBy(d * 0.02f), verticalAlignment = Alignment.CenterVertically) {
                    BarIcon(Lucide.Fuel, Color(0xFF55B95A), d * 0.09f)
                    HudBar(fuel, Color(0xFF55B95A), Modifier.weight(1f))
                    BarIcon(Lucide.BatteryCharging, Color(0xFF4FA3E0), d * 0.09f)
                    HudBar(armour, Color(0xFF4FA3E0), Modifier.weight(1f))
                }
            }
        }
        // the "i", on a dark disc at the card's corner so it reads over any map design
        InfoButton(
            "Мини-карта", "home.minimap",
            Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Color(0xB30B0F1C)),
        )
    }
}

@Composable
private fun fuelFraction(): Float? {
    val ts by Repo.trip.collectAsState()
    val tank = Repo.settings.tankL
    return ts.fuelLeftL?.takeIf { tank > 0 }?.let { (it / tank).toFloat().coerceIn(0f, 1f) }
}

/** Icon on a dark disc that says what a health / armour bar shows (fuel, battery). */
@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, col: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color(0xE60D0F11)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = col, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
private fun HudBar(f: Float?, col: Color, modifier: Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color(0xCC0D0F11))
        drawRect(col.copy(alpha = 0.3f), Offset(size.height * 0.2f, size.height * 0.2f), Size(size.width - size.height * 0.4f, size.height * 0.6f))
        if (f != null) drawRect(col, Offset(size.height * 0.2f, size.height * 0.2f), Size((size.width - size.height * 0.4f) * f, size.height * 0.6f))
    }
}

/**
 * Bezel, north marker, bars and the player arrow. [mapTurn] is how far the map is turned (the course when the
 * map follows it), [arrowTurn] how far the arrow is turned (the course when north is up).
 */
private fun DrawScope.drawMapFrame(look: MapStyle, mapTurn: Float, arrowTurn: Float, fuel: Float?, armour: Float?) {
    val r = size.minDimension / 2f
    val c = center
    val bw = r * look.bezelWidth
    if (look.square) {
        val cr = CornerRadius(r * 0.12f)
        drawRoundRect(look.bezel, Offset(bw / 2, bw / 2), Size(size.width - bw, size.height - bw), cr, style = Stroke(bw))
        look.inner?.let { drawRoundRect(it, Offset(bw, bw), Size(size.width - bw * 2, size.height - bw * 2), cr, style = Stroke(r * 0.012f)) }
    } else {
        look.glow?.let { drawCircle(it.copy(alpha = 0.35f), r - bw * 0.5f, c, style = Stroke(bw * 1.8f)) }
        drawCircle(look.bezel, r - bw / 2, c, style = Stroke(bw))
        look.inner?.let { drawCircle(it, r - bw, c, style = Stroke(r * 0.014f)) }
        look.ticks?.let { col ->
            repeat(36) { k ->
                val a = Math.toRadians(k * 10.0)
                val o = r * 0.99f
                val i = r * (if (k % 9 == 0) 0.9f else 0.94f)
                drawLine(col, Offset(c.x + o * cos(a).toFloat(), c.y + o * sin(a).toFloat()), Offset(c.x + i * cos(a).toFloat(), c.y + i * sin(a).toFloat()), r * 0.012f)
            }
        }
        if (look.bars == MapBars.ARC) {
            // GTA IV: health on the left half of the ring, armour on the right, both filling upwards
            val tl = Offset(c.x - (r - bw / 2), c.y - (r - bw / 2))
            val sz = Size((r - bw / 2) * 2, (r - bw / 2) * 2)
            val st = Stroke(bw * 0.55f, cap = StrokeCap.Butt)
            drawArc(Color(0x5555B95A), 92f, 176f, false, tl, sz, style = st)
            drawArc(Color(0x554FA3E0), 272f, 176f, false, tl, sz, style = st)
            fuel?.let { drawArc(Color(0xFF55B95A), 92f, 176f * it, false, tl, sz, style = st) }
            armour?.let { drawArc(Color(0xFF4FA3E0), 88f, -176f * it, false, tl, sz, style = st) }
        }
    }
    // "N" on the edge, where north is on the turned map
    look.north?.let { col ->
        val a = Math.toRadians((-90.0 - mapTurn))
        val nr = if (look.square) r * 0.86f else r - bw * 1.9f
        val p = Offset(c.x + nr * cos(a).toFloat(), c.y + nr * sin(a).toFloat())
        drawCircle(Color(0xCC000000), r * 0.085f, p)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = col.toArgb(); textSize = r * 0.12f; textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        drawContext.canvas.nativeCanvas.drawText("N", p.x, p.y + paint.textSize * 0.36f, paint)
    }
    // the player
    val s = r * 0.11f
    drawContext.canvas.save()
    drawContext.canvas.translate(c.x, c.y)
    drawContext.canvas.rotate(arrowTurn)
    val arrow = when (look.arrow) {
        MapArrow.NFS -> Path().apply {
            moveTo(0f, -s); lineTo(s * 0.75f, s * 0.8f); lineTo(0f, s * 0.4f); lineTo(-s * 0.75f, s * 0.8f); close()
        }
        MapArrow.GTA -> Path().apply { moveTo(0f, -s * 1.05f); lineTo(s * 0.7f, s * 0.75f); lineTo(-s * 0.7f, s * 0.75f); close() }
        MapArrow.BLIP -> Path().apply {
            moveTo(0f, -s); lineTo(s * 0.62f, s * 0.7f); lineTo(0f, s * 0.38f); lineTo(-s * 0.62f, s * 0.7f); close()
        }
    }
    drawPath(arrow, look.arrowColor)
    drawPath(arrow, Color(0xE6000000), style = Stroke(r * 0.018f))
    drawContext.canvas.restore()
}

/** Pop-up with a preview of every map design, plus zoom and "north up". */
@Composable
fun MapStylePicker(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val cfg = remember(current) { MapCfg.parse(current) }
    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.clip(kg.forester.sg.ui.RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, kg.forester.sg.ui.RoundedCornerShape(16.dp))
                .heightIn(max = (canvasScreen().screenHeightDp * 0.92f).dp)
                .cueScroll().padding(14.dp),
        ) {
            T("Дизайн карты", 16.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Приближение:", 13.sp, C.Sub, modifier = Modifier.padding(top = 8.dp))
                listOf(14.5 to "Далеко", 15.5 to "Средне", 16.5 to "Близко", 17.5 to "Очень близко").forEach { (z, l) ->
                    Chip(l, cfg.zoom == z, { onPick(cfg.copy(zoom = z).format()) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Поворот:", 13.sp, C.Sub, modifier = Modifier.padding(top = 8.dp))
                Chip("По ходу движения", !cfg.northUp, { onPick(cfg.copy(northUp = false).format()) })
                Chip("Север вверху", cfg.northUp, { onPick(cfg.copy(northUp = true).format()) })
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Полоски топлива и АКБ (GTA IV, V):", 13.sp, C.Sub, modifier = Modifier.padding(top = 8.dp))
                Chip("Показывать", cfg.bars, { onPick(cfg.copy(bars = true).format()) })
                Chip("Скрыть", !cfg.bars, { onPick(cfg.copy(bars = false).format()) })
            }
            Spacer(Modifier.height(6.dp))
            T("По нажатию открывать:", 13.sp, C.Sub)
            Spacer(Modifier.height(4.dp))
            MapAppChooser()
            Spacer(Modifier.height(10.dp))
            MapStyles.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { st ->
                        val on = st.id == cfg.style
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(if (on) C.Purple.copy(alpha = 0.25f) else Color.Transparent)
                                .border(if (on) 2.dp else 1.dp, if (on) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(12.dp))
                                .clickable { onPick(cfg.copy(style = st.id).format()); onClose() }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            MapPreview(if (cfg.bars) st else st.copy(bars = MapBars.NONE), Modifier.size(112.dp))
                            T(st.name, 12.sp, if (on) C.Text else C.Sub, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** A drawn stand-in for the map (blocks, roads, water) in the design's colours, so the list needs no tiles. */
@Composable
private fun MapPreview(st: MapStyle, modifier: Modifier) {
    val shape: Shape = if (st.square) RoundedCornerShape(8.dp) else CircleShape
    Box(modifier.clip(shape)) {
        Canvas(Modifier.fillMaxSize()) {
            val low = st.low ?: Color(0xFFD9D0C9)
            val high = st.high.takeIf { st.low != null } ?: Color.White
            fun mix(f: Float) = androidx.compose.ui.graphics.lerp(low, high, f)
            drawRect(mix(0.7f))
            val w = size.width
            // water, blocks, roads
            drawRect(if (st.low == null) Color(0xFFAAD3DF) else mix(0f), Offset(0f, size.height * 0.72f), Size(w * 0.42f, size.height * 0.3f))
            for (i in 0..3) for (j in 0..3) {
                if ((i + j) % 3 == 0) continue
                drawRect(mix(0.2f), Offset(w * (0.08f + i * 0.24f), size.height * (0.06f + j * 0.22f)), Size(w * 0.16f, size.height * 0.14f))
            }
            drawLine(mix(1f), Offset(0f, size.height * 0.48f), Offset(w, size.height * 0.36f), w * 0.05f)
            drawLine(mix(1f), Offset(w * 0.56f, 0f), Offset(w * 0.5f, size.height), w * 0.04f)
            drawMapFrame(st, 25f, 0f, 0.7f, 0.5f)
        }
    }
}

/** Navigators and map apps, in the order they are offered when installed. */
private val KnownMapApps = listOf(
    "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.google.android.apps.maps", "ru.dublgis.dgismobile",
    "com.waze", "net.osmand.plus", "net.osmand", "com.sygic.aura", "cityguide.probki.net", "app.organicmaps",
)

/** Installed apps the mini-map can open: the known navigators first, then anything that opens a map link. */
fun mapApps(ctx: Context): List<kg.forester.sg.data.InstalledApp> {
    val pm = ctx.packageManager
    val geo = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), 0).map { it.activityInfo.packageName }
    val pkgs = (KnownMapApps.filter { pm.getLaunchIntentForPackage(it) != null } + geo)
        .distinct().filter { it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null }
    return pkgs.map { p ->
        kg.forester.sg.data.InstalledApp(Shortcuts.appLabel(ctx, p), p, runCatching { pm.getApplicationIcon(p) }.getOrNull())
    }
}

/** Choice of the app a tap on the mini-map opens (Settings → Система and the map design window). */
@Composable
fun MapAppChooser() {
    val ctx = LocalContext.current
    val apps = remember { mapApps(ctx) }
    var current by remember { mutableStateOf(Repo.settings.mapApp) }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip("Кнопка «Навигатор»", current.isEmpty(), { current = ""; Repo.settings.mapApp = "" })
        apps.forEach { a -> Chip(a.label, current == a.pkg, { current = a.pkg; Repo.settings.mapApp = a.pkg }) }
    }
    if (apps.isEmpty()) T("Навигаторы не найдены: установите, например, Яндекс Навигатор или Google Карты.", 12.sp, C.Sub)
}

/**
 * Opens the app chosen in the settings; by default the "Навигатор" shortcut (with its split screen),
 * otherwise whatever app handles a map link.
 */
fun openNavigator(ctx: Context) {
    val chosen = Repo.settings.mapApp
    val shortcuts = Shortcuts.list.value
    if (chosen.isNotEmpty()) {
        // the same app as a side-panel shortcut: launch it the same way (split screen if set there)
        shortcuts.firstOrNull { it.pkg == chosen }?.let { Shortcuts.launch(ctx, it); return }
        ctx.packageManager.getLaunchIntentForPackage(chosen)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
    }
    val nav = shortcuts.firstOrNull { it.icon == "navigation" } ?: shortcuts.firstOrNull { it.icon == "map" }
    if (nav != null) {
        Shortcuts.launch(ctx, nav)
        return
    }
    val g = Repo.gps.value
    val uri = if (g?.lat != null && g.lon != null) "geo:${g.lat},${g.lon}" else "geo:0,0"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
