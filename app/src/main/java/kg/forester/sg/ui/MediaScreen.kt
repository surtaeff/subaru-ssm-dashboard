package kg.forester.sg.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import com.composables.icons.lucide.*
import kg.forester.sg.data.Repo
import kg.forester.sg.service.AudioFx
import kg.forester.sg.service.Fyt
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.MediaSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * «Медиа»: one player for everything that plays in the car. The mode is switched inside it, like the SRC key of
 * a car stereo: USB (the app's own player: stick, card, memory), Bluetooth from the phone, radio (the FYT tuner
 * and internet radio), other player apps (Yandex Music, VK…). Behind it the cover, blurred; on the right a panel
 * that slides out for the mode: folders, presets and stations, players. Accents in the interface colour.
 */
@Composable
fun MediaScreen() {
    val ctx = LocalContext.current
    val fyt = remember { Fyt.present(ctx) }
    val modes = listOf(
        Triple("files", "USB", Lucide.Usb), Triple("bt", "Bluetooth", Lucide.Bluetooth),
        Triple("radio", "Радио", Lucide.Radio), Triple("apps", "Приложения", Lucide.LayoutGrid),
    )
    var mode by remember { mutableStateOf(Repo.settings.mediaSource.takeIf { s -> modes.any { it.first == s } } ?: "files") }
    var panel by remember { mutableStateOf(true) }
    var appsKey by remember { mutableStateOf(0) }
    val appsVer by appsRefresh.collectAsState()
    val radio = rememberRadioUi(fyt)
    fun pick(s: String) {
        if (s == mode) return
        mode = s; Repo.settings.mediaSource = s
        // one source at a time, like the head unit's own: the files pause when another one takes over
        if (s != "files" && !(s == "radio" && radio.internet)) LocalMusic.pause()
        if (fyt) when (s) { "bt" -> Fyt.source(3); "radio" -> if (!radio.internet) Fyt.ensureRadio() }
    }
    // another app started playing (Yandex Music from its own screen, a voice command): offer to show it
    var other by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            other = MediaSessions.playingApp(ctx)?.takeIf { it.first != Repo.settings.musicApp || mode != "apps" }
            delay(2000)
        }
    }
    val st by LocalMusic.state.collectAsState()
    val art: Bitmap? = when (mode) {
        "files" -> st.art.takeIf { !st.stream }
        "radio" -> radio.coverBitmap()
        else -> null
    }
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(C.Card).border(1.dp, C.Border, shape)) {
        Backdrop(art)
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeSwitch(modes, mode) { pick(it) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    other?.let { (pkg, name) ->
                        Row(
                            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.Violet.copy(alpha = 0.16f))
                                .clickable { Repo.settings.musicApp = pkg; other = null; appsKey++; pick("apps") }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Bars(true)
                            Spacer(Modifier.width(10.dp))
                            T("Играет $name", 14.sp, C.Text, FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                if (mode == "files" || (mode == "radio" && radio.internet)) {
                    var sound by remember { mutableStateOf(false) }
                    RoundIcon(Lucide.SlidersHorizontal, "Звук") { sound = true }
                    if (sound) SoundDialog { sound = false }
                    Spacer(Modifier.width(8.dp))
                }
                RoundIcon(if (panel) Lucide.PanelRightClose else Lucide.PanelRightOpen, if (panel) "Скрыть список" else "Список", panel) { panel = !panel }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Crossfade(mode, label = "mode") { m ->
                        when (m) {
                            "bt" -> BluetoothHero(fyt)
                            "radio" -> RadioHero(radio)
                            "apps" -> androidx.compose.runtime.key(appsKey, appsVer) { AppsHero(fyt) }
                            else -> FilesHero()
                        }
                    }
                }
                // the list slides in from the edge and fades in: the hero beside it is laid out once at its new width,
                // not on every frame of a width animation (cover, picture and texts measured again 20 times over)
                AnimatedVisibility(
                    panel,
                    enter = slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220)),
                    exit = slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(180)),
                ) {
                    Row {
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.08f)))
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.width(420.dp).fillMaxHeight()) {
                            when (mode) {
                                "bt" -> BluetoothPanel(fyt)
                                "radio" -> RadioPanel(radio)
                                "apps" -> androidx.compose.runtime.key(appsKey) { AppsPanel(fyt) }
                                else -> FilesPanel()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The cover, blown up and blurred (a tiny copy stretched smooth, cheap on any unit), darkened for the text. */
@Composable
private fun Backdrop(art: Bitmap?) {
    val blur = remember(art) { art?.let { runCatching { Bitmap.createScaledBitmap(it, 12, 12, true).asImageBitmap() }.getOrNull() } }
    val a by animateFloatAsState(if (blur != null) 0.75f else 0f, tween(600), label = "backdrop")
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.Violet.copy(alpha = 0.20f), C.Card, C.Purple.copy(alpha = 0.12f)))))
    if (blur != null) Image(blur, null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.50f), C.Card.copy(alpha = 0.84f), C.Card.copy(alpha = 0.95f)))))
}

/** The mode switch: a segmented pill, the chosen part filled with the interface colour. */
@Composable
private fun ModeSwitch(modes: List<Triple<String, String, ImageVector>>, mode: String, onPick: (String) -> Unit) {
    Row(
        Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(26.dp)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { (id, title, icon) ->
            val sel = id == mode
            Row(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp))
                    .background(if (sel) C.Accent else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onPick(id) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (sel) Color.White else C.Sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                T(title, 15.sp, if (sel) Color.White else C.Sub, if (sel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun RoundIcon(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) C.Violet.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.25f))
            .border(1.dp, if (selected) C.Violet.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) C.Violet else C.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        T(label, 14.sp, C.Text, FontWeight.Medium, maxLines = 1)
    }
}

// --- the player's parts -------------------------------------------------------------------------------

/**
 * The left of the player: [cover] (swipe it for the next / previous one), the [info] beside it, [middle] (the
 * spectrum and seek bar, the radio's scale) and the [controls].
 */
@Composable
internal fun Hero(
    cover: @Composable (Modifier) -> Unit,
    onNext: (() -> Unit)?, onPrev: (() -> Unit)?,
    info: @Composable ColumnScope.() -> Unit,
    middle: @Composable ColumnScope.() -> Unit,
    controls: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .pointerInput(onNext, onPrev) {
                        var dx = 0f
                        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                            if (dx < -80) onNext?.invoke() else if (dx > 80) onPrev?.invoke()
                        }) { _, d -> dx += d }
                    },
                contentAlignment = Alignment.Center,
            ) { cover(Modifier.fillMaxSize(0.94f)) }
            Spacer(Modifier.width(22.dp))
            // a large UI scale: the big frequency or title shrinks with the rest instead of being cut at the bottom
            ShrinkToFit(Modifier.weight(1f).fillMaxHeight()) { Column(content = info) }
        }
        Spacer(Modifier.height(10.dp))
        middle()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, content = controls)
    }
}

/**
 * The music's levels, [AudioFx.BARS] bands, bass first: from the player's own output when the unit lets the app
 * listen to it, else a calm made-up movement while it plays (Bluetooth, other apps), still when paused. A State, to
 * be read only while drawing: the pictures move with the music without recomposing anything on every frame.
 */
@Composable
internal fun rememberLevels(playing: Boolean): androidx.compose.runtime.State<FloatArray> {
    val live by AudioFx.live.collectAsState()
    val n = AudioFx.BARS
    return when {
        !playing -> remember { mutableStateOf(FloatArray(n) { 0.04f }) }
        live -> AudioFx.spectrum.collectAsState()
        else -> madeUpLevels(n)
    }
}

/** The calm made-up movement; its clock runs only while it is shown, not under a paused or a live picture. */
@Composable
private fun madeUpLevels(n: Int): androidx.compose.runtime.State<FloatArray> {
    val t = rememberInfiniteTransition(label = "wave")
    val phase = t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    return remember {
        androidx.compose.runtime.derivedStateOf {
            val p = phase.value
            FloatArray(n) { i ->
                ((0.38f + 0.22f * sin(p + i * 0.45f) + 0.14f * sin(p * 2 - i * 0.8f) + 0.08f * sin(p * 3 + i * 1.3f))
                    * (1f - i / (n * 1.5f))).coerceIn(0.04f, 1f)
            }
        }
    }
}

/**
 * The cover with a glow that breathes with the bass and, when chosen in «Звук», rays round it that move with
 * the music (like a CircleLine visualizer).
 */
@Composable
internal fun GlowCover(art: Bitmap?, placeholder: ImageVector, playing: Boolean, modifier: Modifier, whiteBack: Boolean = false) {
    val cfg by AudioFx.cfg.collectAsState()
    val lv = rememberLevels(playing)
    // the glow follows the bass, eased over 140 ms as before; the levels and the glow are read only in the drawing
    // and in the cover's layer, so a playing track redraws the glow and the rays and scales the cover, while the cover
    // with its picture is not recomposed on every frame
    val glowAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(lv, playing) {
        androidx.compose.runtime.snapshotFlow { if (playing) lv.value.let { a -> (a[0] + a[1] + a[2] + a[3] + a[4]) / 5f } else 0f }
            .collectLatest { glowAnim.animateTo(it, tween(140)) }
    }
    val main = C.Violet
    val second = C.theme.second
    val ring = cfg.ring
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val s = size.minDimension
            val glow = glowAnim.value
            drawCircle(Brush.radialGradient(listOf(main.copy(alpha = 0.20f + glow * 0.40f), Color.Transparent), c, s * (0.55f + glow * 0.08f)), s * (0.55f + glow * 0.08f), c)
            if (ring) {
                val lv = lv.value
                val rays = 96
                val r0 = s * 0.44f
                for (k in 0 until rays) {
                    val a = (k.toFloat() / rays) * 2 * Math.PI.toFloat() - Math.PI.toFloat() / 2
                    // mirrored left and right, bass at the bottom
                    val half = abs(((k.toFloat() / rays) * 2f) - 1f)
                    val band = ((1f - half) * (lv.size - 1)).toInt().coerceIn(0, lv.size - 1)
                    val v = lv[band]
                    val len = s * (0.025f + v * 0.11f)
                    val dx = kotlin.math.cos(a); val dy = kotlin.math.sin(a)
                    val col = androidx.compose.ui.graphics.lerp(second, main, half)
                    drawLine(col.copy(alpha = 0.35f + v * 0.6f), Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
                        strokeWidth = s * 0.012f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.fillMaxSize(if (ring) 0.80f else 0.92f)
                .graphicsLayer { val k = 1f + glowAnim.value * 0.02f; scaleX = k; scaleY = k }.clip(shape)
                .background(if (whiteBack && art != null) Brush.verticalGradient(listOf(Color.White, Color.White))
                    else Brush.linearGradient(listOf(C.Violet.copy(alpha = 0.6f), C.Purple.copy(alpha = 0.4f), C.CardHi)))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize().padding(if (whiteBack) 12.dp else 0.dp),
                contentScale = if (whiteBack) ContentScale.Fit else ContentScale.Crop)
            else Icon(placeholder, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxSize(0.38f))
        }
    }
}

/** The track: the mode above it small, the title large, the artist, the album, what comes next. */
@Composable
internal fun ColumnScope.TrackInfo(kicker: String, title: String, artist: String, album: String = "", next: String = "") {
    T(kicker.uppercase(), 12.sp, C.Violet, FontWeight.SemiBold, maxLines = 1)
    Spacer(Modifier.height(8.dp))
    T(title, 30.sp, weight = FontWeight.Bold, maxLines = 2)
    if (artist.isNotBlank()) { Spacer(Modifier.height(6.dp)); T(artist, 19.sp, C.Text.copy(alpha = 0.75f), maxLines = 1) }
    if (album.isNotBlank()) { Spacer(Modifier.height(2.dp)); T(album, 14.sp, C.Sub, maxLines = 1) }
    if (next.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.ListMusic, null, tint = C.Sub, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            T("Далее: $next", 13.sp, C.Sub, maxLines = 1)
        }
    }
}

/** The picture of the music under the cover, in the style chosen in «Звук»: bars with falling peaks or a wave. */
@Composable
internal fun Spectrum(playing: Boolean, modifier: Modifier) {
    val cfg by AudioFx.cfg.collectAsState()
    val lv = rememberLevels(playing)
    // off (the default): nothing, the cover gets the room
    when (cfg.vis) {
        "wave" -> WaveVis(lv, modifier)
        "bars" -> BarsVis(lv, modifier)
    }
}

/** Bars with a cap that floats at the peak and sinks slowly (the classic stereo look), and their reflection. */
@Composable
private fun BarsVis(levels: androidx.compose.runtime.State<FloatArray>, modifier: Modifier) {
    val peaks = remember { FloatArray(AudioFx.BARS) }
    val main = C.Violet
    val second = C.theme.second
    Canvas(modifier) {
        val lv = levels.value
        val n = lv.size
        val base = size.height * 0.78f
        val gap = size.width / n * 0.28f
        val w = size.width / n - gap
        for (i in 0 until n) {
            val v = lv[i].coerceIn(0f, 1f)
            peaks[i] = maxOf(v, peaks[i] - 0.012f)
            val h = (base - 6f) * v
            val x = i * (w + gap) + gap / 2
            drawRoundRect(Brush.verticalGradient(listOf(main, second.copy(alpha = 0.55f)), startY = base - h, endY = base),
                Offset(x, base - h), Size(w, h), CornerRadius(w / 3, w / 3))
            // the reflection on a glossy floor
            drawRoundRect(Brush.verticalGradient(listOf(main.copy(alpha = 0.22f), Color.Transparent), startY = base + 2f, endY = base + 2f + h * 0.28f),
                Offset(x, base + 2f), Size(w, h * 0.28f), CornerRadius(w / 3, w / 3))
            val py = base - 6f - (base - 6f) * peaks[i] - 4f
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x, py), Size(w, 3.5f), CornerRadius(2f, 2f))
        }
    }
}

/** A smooth curve mirrored over the middle line, filled and glowing. */
@Composable
private fun WaveVis(levels: androidx.compose.runtime.State<FloatArray>, modifier: Modifier) {
    val main = C.Violet
    val second = C.theme.second
    Canvas(modifier) {
        val lv = levels.value
        val n = lv.size
        val mid = size.height / 2
        fun pts(sign: Float) = List(n) { i ->
            Offset(size.width * i / (n - 1), mid - sign * (mid - 2f) * lv[i].coerceIn(0.03f, 1f))
        }
        fun path(p: List<Offset>) = androidx.compose.ui.graphics.Path().apply {
            moveTo(p[0].x, p[0].y)
            for (i in 1 until p.size) { val a = p[i - 1]; val b = p[i]; val mx = (a.x + b.x) / 2; cubicTo(mx, a.y, mx, b.y, b.x, b.y) }
        }
        val top = pts(1f)
        val bottom = pts(-1f)
        val fill = path(top).apply {
            lineTo(bottom.last().x, bottom.last().y)
            for (i in bottom.size - 2 downTo 0) { val a = bottom[i + 1]; val b = bottom[i]; val mx = (a.x + b.x) / 2; cubicTo(mx, a.y, mx, b.y, b.x, b.y) }
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(main.copy(alpha = 0.10f), main.copy(alpha = 0.45f), main.copy(alpha = 0.10f))))
        val edge = Brush.horizontalGradient(listOf(second, main, second))
        drawPath(path(top), edge, style = androidx.compose.ui.graphics.drawscope.Stroke(8f), alpha = 0.25f)
        drawPath(path(top), edge, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawPath(path(bottom), edge, style = androidx.compose.ui.graphics.drawscope.Stroke(3f), alpha = 0.6f)
    }
}

@Composable
internal fun SeekBar(posMs: Long, durMs: Long, onSeek: ((Long) -> Unit)?) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = dragging ?: if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    if (onSeek != null && durMs > 0) {
        Slider(
            value = frac, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek((it * durMs).toLong()) }; dragging = null },
            modifier = Modifier.fillMaxWidth().height(26.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.Violet, inactiveTrackColor = Color.White.copy(alpha = 0.12f)),
        )
    } else {
        Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) { ProgressBar(frac, C.Accent, Modifier.fillMaxWidth(), 6.dp) }
    }
    Row(Modifier.fillMaxWidth()) {
        val p = dragging?.let { (it * durMs).toLong() } ?: posMs
        T(time(p), 12.sp, C.Sub, modifier = Modifier.weight(1f))
        T(if (durMs > 0) "−" + time((durMs - p).coerceAtLeast(0)) else "", 12.sp, C.Sub)
    }
}

/** A round button; [primary] is the big one in the interface colour; hold [onLong] (winding). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Control(icon: ImageVector, size: Dp, primary: Boolean = false, active: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (primary) C.Accent else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.04f))))
            .then(if (active) Modifier.border(1.5.dp, C.Violet, CircleShape) else Modifier)
            .combinedClickable(onLongClick = onLong, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (primary) Color.White else if (active) C.Violet else C.Text, modifier = Modifier.size(size * 0.40f))
    }
}

/** The panel's title row and its content. */
@Composable
internal fun PanelBox(icon: ImageVector, title: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            T(title, 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            trailing()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
internal fun Pill(text: String, icon: ImageVector? = null, selected: Boolean = false, height: Dp = 44.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        Modifier.height(height).clip(shape).background(if (selected) C.Violet.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) C.Violet else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (selected) C.Violet else C.Sub, modifier = Modifier.size(18.dp)); if (text.isNotEmpty()) Spacer(Modifier.width(8.dp)) }
        if (text.isNotEmpty()) T(text, 14.sp, if (selected) C.Text else C.Sub, FontWeight.Medium, maxLines = 1)
    }
}

/** Three bars dancing beside what plays (still while paused). */
@Composable
internal fun Bars(playing: Boolean) {
    // paused: still bars and no clock ticking under them
    val hs = if (playing) {
        val t = rememberInfiniteTransition(label = "eq")
        listOf(0, 160, 320).map { d ->
            t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, delayMillis = d), androidx.compose.animation.core.RepeatMode.Reverse), label = "eq$d")
        }
    } else null
    val violet = C.Violet
    Canvas(Modifier.size(20.dp)) {
        val w = size.width / 5
        for (i in 0 until 3) {
            val f = hs?.get(i)?.value ?: listOf(0.4f, 0.7f, 0.5f)[i]
            drawRoundRect(violet, Offset(w * (i * 2), size.height * (1 - f)), Size(w, size.height * f), CornerRadius(w / 2, w / 2))
        }
    }
}

/** A row of a list: [lead] at the left, the text, [trail] at the right; highlighted when [current]. */
@Composable
internal fun ListRow(title: String, sub: String = "", current: Boolean = false, lead: @Composable () -> Unit, trail: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp))
            .background(if (current) C.Violet.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(title, 15.sp, if (current) C.Violet else C.Text, if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            if (sub.isNotBlank()) T(sub, 12.sp, C.Sub, maxLines = 1)
        }
        trail()
    }
}

@Composable
internal fun IconTile(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Violet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(19.dp))
    }
}

internal fun time(ms: Long): String {
    val s = (if (ms > 100_000_000) ms / 1000 else ms) / 1000 // tolerate µs/ms
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Spectrum + seek bar of the own player, listening only while the player is on screen. */
@Composable
internal fun ColumnScope.OwnMiddle(st: LocalMusic.State, pos: Long, onSeek: ((Long) -> Unit)?, live: Boolean = false) {
    val ctx = LocalContext.current
    val canListen = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    DisposableEffect(st.audioSession, canListen) {
        if (canListen) AudioFx.startSpectrum(st.audioSession)
        onDispose { AudioFx.stopSpectrum() }
    }
    Spectrum(st.playing, Modifier.fillMaxWidth().height(56.dp).padding(bottom = 4.dp))
    if (!live) SeekBar(pos, st.durMs, onSeek)
    else Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        // a stream has no length: say it is live instead of an empty bar
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.playing) C.Red else C.Sub))
        Spacer(Modifier.width(8.dp))
        T(if (st.playing) "В ЭФИРЕ" else "пауза", 13.sp, if (st.playing) C.Text else C.Sub, FontWeight.SemiBold)
    }
}

// --- USB: the own player ------------------------------------------------------------------------------

internal fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun FilesHero() {
    val ctx = LocalContext.current
    val st by LocalMusic.state.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    val granted = ctx.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(granted) {
        if (granted) LocalMusic.player(ctx)
        while (true) { pos = LocalMusic.position(); delay(500) }
    }
    val files = !st.stream
    Hero(
        cover = { GlowCover(if (files) st.art else null, Lucide.Disc3, st.playing && files, it) },
        onNext = { LocalMusic.next(ctx) }, onPrev = { LocalMusic.prev(ctx) },
        info = {
            if (!files || st.current == null) TrackInfo("USB · свой плеер", if (granted) "Выберите музыку в списке" else "Нужен доступ к музыке",
                "Флешка, карта или память магнитолы")
            else {
                TrackInfo(st.current?.let { File(it).parentFile?.name }.orEmpty(), st.title, st.artist, st.album,
                    if (st.repeat == Player.REPEAT_MODE_ONE) "" else st.nextTitle)
                if (st.error.isNotEmpty()) { Spacer(Modifier.height(8.dp)); T(st.error, 14.sp, C.Orange) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (st.queue.size > 1) T("${st.index + 1} из ${st.queue.size}", 13.sp, C.Sub)
                    if (st.long) {
                        // audiobooks and podcasts: faster reading, the place is kept per file
                        Spacer(Modifier.width(12.dp))
                        listOf(1f, 1.25f, 1.5f, 2f).forEach { v ->
                            Pill(if (v == 1f) "1×" else "%s×".format(v.toString().trimEnd('0').trimEnd('.')), null, abs(st.speed - v) < 0.01f, 32.dp) {
                                LocalMusic.setBookSpeed(ctx, v)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }
        },
        middle = { OwnMiddle(st, pos, if (files) { ms -> LocalMusic.seek(ctx, ms); pos = ms } else null) },
        controls = {
            Control(Lucide.Shuffle, 54.dp, active = st.shuffle && files) { LocalMusic.setShuffle(ctx, !st.shuffle) }
            Control(Lucide.SkipBack, 68.dp, onLong = { LocalMusic.seekBy(ctx, -10_000) }) { LocalMusic.prev(ctx) }
            Control(if (st.playing && files) Lucide.Pause else Lucide.Play, 96.dp, primary = true) { LocalMusic.toggle(ctx) }
            Control(Lucide.SkipForward, 68.dp, onLong = { LocalMusic.seekBy(ctx, 10_000) }) { LocalMusic.next(ctx) }
            Control(if (st.repeat == Player.REPEAT_MODE_ONE) Lucide.Repeat1 else Lucide.Repeat, 54.dp,
                active = st.repeat != Player.REPEAT_MODE_OFF && files) { LocalMusic.cycleRepeat(ctx) }
        },
    )
}

private val FAV_ROOT = File("/★")
private val RECENT_ROOT = File("/⟲")

/** Folders of the chosen drive: tap a folder to go in, a track to play the folder from it. */
@Composable
private fun FilesPanel() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        PanelBox(Lucide.FolderOpen, "Музыка") {
            T("Играет mp3, flac, m4a, ogg, opus и wav с флешки, карты и памяти магнитолы, запоминает место, держит эквалайзер. " +
                "Разрешите доступ к музыке, чтобы увидеть папки.", 14.sp, C.Sub)
            Spacer(Modifier.height(14.dp))
            Pill("Разрешить доступ", Lucide.ShieldCheck, true) { ask.launch(audioPermission()) }
        }
        return
    }
    val st by LocalMusic.state.collectAsState()
    var roots by remember { mutableStateOf(LocalMusic.roots(ctx)) }
    // a stick put in while the screen is open shows up within a few seconds
    LaunchedEffect(Unit) { while (true) { delay(4000); roots = LocalMusic.roots(ctx) } }
    var dir by remember {
        mutableStateOf(st.folder.takeIf { it.isNotEmpty() && File(it).isDirectory }?.let(::File) ?: roots.firstOrNull()?.dir)
    }
    var favVersion by remember { mutableStateOf(0) }
    val root = roots.filter { r -> dir?.path?.startsWith(r.dir.path) == true }.maxByOrNull { it.dir.path.length }
    var listing by remember { mutableStateOf(LocalMusic.Listing(emptyList(), emptyList())) }
    LaunchedEffect(dir, favVersion) {
        val d = dir
        listing = when (d) {
            FAV_ROOT -> LocalMusic.Listing(LocalMusic.favourites(), emptyList())
            RECENT_ROOT -> LocalMusic.Listing(LocalMusic.recent(), emptyList())
            null -> LocalMusic.Listing(emptyList(), emptyList())
            else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LocalMusic.refreshMusicDirs(ctx)
                runCatching { LocalMusic.list(d) }.getOrDefault(LocalMusic.Listing(emptyList(), emptyList()))
            }
        }
    }
    val special = dir == FAV_ROOT || dir == RECENT_ROOT
    // search over all the music at once (sticks, card, memory): title, artist, album, file or folder name
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<LocalMusic.Hit>?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = null; return@LaunchedEffect }
        delay(250) // typing: wait for a pause
        hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { LocalMusic.search(ctx, query) }.getOrDefault(emptyList()) }
    }
    val found = hits
    PanelBox(Lucide.FolderOpen, "Музыка", trailing = {
        if (found == null && listing.tracks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Все", Lucide.Play, height = 38.dp) { dir?.let { LocalMusic.playFolder(ctx, it, listing.tracks, 0) } }
                Pill("", Lucide.Shuffle, height = 38.dp) { dir?.let { LocalMusic.playFolder(ctx, it, listing.tracks, 0, shuffle = true) } }
            }
        }
    }) {
        SearchLine(query, "Песня, исполнитель или папка") { query = it }
        Spacer(Modifier.height(8.dp))
        if (query.trim().length >= 2) {
            when {
                found == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { T("Ищу…", 14.sp, C.Sub) }
                found.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    T("Ничего не нашлось. Попробуйте часть названия или имя исполнителя", 14.sp, C.Sub, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(found, key = { "h" + it.file.path }) { h ->
                        val cur = h.file.path == st.current
                        ListRow(h.title, h.sub, cur, lead = { if (cur) Bars(st.playing) else IconTile(Lucide.Music) },
                            trail = { T(h.file.extension.uppercase(), 11.sp, C.Sub.copy(alpha = 0.6f)) }) {
                            if (cur) LocalMusic.toggle(ctx) else LocalMusic.playFound(ctx, h.file)
                        }
                    }
                }
            }
            return@PanelBox
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            roots.forEach { r ->
                Pill(r.name, when (r.kind) { LocalMusic.Kind.USB -> Lucide.Usb; LocalMusic.Kind.SD -> Lucide.MemoryStick; else -> Lucide.HardDrive },
                    !special && root == r, 40.dp) { dir = r.dir }
            }
            Pill("Избранное", Lucide.Star, dir == FAV_ROOT, 40.dp) { dir = FAV_ROOT }
            Pill("Недавние", Lucide.History, dir == RECENT_ROOT, 40.dp) { dir = RECENT_ROOT }
        }
        Spacer(Modifier.height(8.dp))
        val atRoot = special || root == null || dir?.path == root.dir.path
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.22f))
                .clickable(enabled = !atRoot) { dir = dir?.parentFile }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (atRoot) Lucide.Folder else Lucide.ArrowLeft, null, tint = if (atRoot) C.Sub else C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            val path = when {
                dir == FAV_ROOT -> "Избранные папки"
                dir == RECENT_ROOT -> "Недавно игравшие папки"
                root == null || dir == null -> ""
                else -> dir!!.path.removePrefix(root.dir.path).trim('/')
            }
            T(if (path.isEmpty()) (root?.name ?: "—") else path.replace("/", "  ›  "), 14.sp, C.Text, FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
            val d = dir
            if (!special && d != null && !atRoot) {
                val fav = remember(d, favVersion) { LocalMusic.isFavourite(d) }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { LocalMusic.setFavourite(d, !fav); favVersion++ }, contentAlignment = Alignment.Center) {
                    Icon(Lucide.Star, if (fav) "Убрать из избранного" else "В избранное", tint = if (fav) C.Orange else C.Sub, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (listing.folders.isEmpty() && listing.tracks.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                T(when {
                    dir == FAV_ROOT -> "Отметьте папку звёздочкой, и она будет здесь"
                    dir == RECENT_ROOT -> "Здесь появятся папки, которые вы слушали"
                    roots.none { it.kind != LocalMusic.Kind.MEMORY } -> "Здесь нет музыки. Вставьте флешку или карту: она появится вверху"
                    else -> "Здесь нет музыки и папок"
                }, 14.sp, C.Sub, textAlign = TextAlign.Center)
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(listing.folders, key = { "d" + it.path }) { f ->
                ListRow(f.name, if (special) f.parent.orEmpty() else "", lead = { IconTile(if (special) Lucide.FolderHeart else Lucide.Folder) },
                    trail = { Icon(Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(18.dp)) }) { dir = f }
            }
            items(listing.tracks.size, key = { "t" + listing.tracks[it].path }) { i ->
                val f = listing.tracks[i]
                val cur = f.path == st.current
                ListRow(if (cur && st.title.isNotBlank()) st.title else LocalMusic.pretty(f), if (cur) st.artist else "", cur,
                    lead = { if (cur) Bars(st.playing) else T("${i + 1}", 13.sp, C.Sub) },
                    trail = { T(f.extension.uppercase(), 11.sp, C.Sub.copy(alpha = 0.6f)) }) {
                    if (cur) LocalMusic.toggle(ctx) else dir?.let { LocalMusic.playFolder(ctx, it, listing.tracks, i) }
                }
            }
        }
    }
}

// --- Bluetooth ----------------------------------------------------------------------------------------

@Composable
private fun BluetoothHero(fyt: Boolean) {
    val ctx = LocalContext.current
    // FYT: the BT module's own reports, which the stock player's now-playing pushes don't cover up
    val m by Fyt.btMusic.collectAsState()
    // other head units: the phone's music arrives as the Bluetooth app's media session; on FYT the session (when
    // the unit has one) fills in what the module leaves out: the track, the cover
    var ext by remember { mutableStateOf(PlayerState()) }
    LaunchedEffect(fyt) { while (true) { ext = btSession(ctx)?.let { playerState(ctx, it) } ?: PlayerState(); delay(1000) } }
    val known = if (fyt) m.title.isNotBlank() else ext.title.isNotBlank()
    val title = when {
        fyt -> m.title.ifBlank { ext.title.ifBlank { "Включите музыку на телефоне" } }
        else -> ext.title.ifBlank { if (ext.session) "Нет трека" else "Включите музыку на телефоне" }
    }
    val artist = if (fyt) (if (known) m.artist else ext.artist) else ext.artist
    // the module's play state once it has said anything, the session's before that
    val playing = if (fyt && m.updatedMs > 0) m.playing else ext.playing
    val pos = if (fyt && m.posMs > 0) m.posMs else ext.posMs
    val dur = if (fyt && m.totalMs > 0) m.totalMs else ext.totalMs
    val track = if (fyt) m.title.ifBlank { ext.title } else ext.title
    val cover = rememberCover((if (fyt) m.art else null) ?: ext.art, artist, track)
    // straight to the BT module: Fyt.next() / prev() go by the source, which is still the old one right after the switch
    fun prev() { if (fyt) Fyt.btPrev() else btControl(ctx) { it.skipToPrevious() } }
    fun next() { if (fyt) Fyt.btNext() else btControl(ctx) { it.skipToNext() } }
    Hero(
        // a track without a cover gets a note, nothing at all the Bluetooth sign
        cover = { GlowCover(cover, if (track.isNotBlank()) Lucide.Music else Lucide.Bluetooth, playing, it) },
        onNext = ::next, onPrev = ::prev,
        info = { TrackInfo("Bluetooth · телефон", title, artist) },
        middle = { Spectrum(playing, Modifier.fillMaxWidth().height(48.dp).padding(bottom = 4.dp)); SeekBar(pos, dur, null) },
        controls = {
            Control(Lucide.SkipBack, 68.dp) { prev() }
            Control(if (playing) Lucide.Pause else Lucide.Play, 96.dp, primary = true) {
                if (fyt) Fyt.btPlay(!playing) else btControl(ctx) { if (ext.playing) it.pause() else it.play() }
            }
            Control(Lucide.SkipForward, 68.dp) { next() }
        },
    )
}

@Composable
private fun BluetoothPanel(fyt: Boolean) {
    val ctx = LocalContext.current
    PanelBox(Lucide.Smartphone, "Телефон") {
        T("Музыка с телефона идёт через Bluetooth магнитолы: трек и кнопки появятся здесь, как только телефон начнёт играть.", 14.sp, C.Sub)
        Spacer(Modifier.height(12.dp))
        Step(Lucide.Bluetooth, "Подключите телефон к магнитоле", "один раз, в настройках Bluetooth")
        Step(Lucide.Music, "Включите музыку на телефоне", "Яндекс Музыка, VK, файлы, подкасты")
        Step(Lucide.CircleDot, "Кнопки на руле работают", "следующий, предыдущий, пауза")
        Spacer(Modifier.weight(1f))
        Pill("Настройки Bluetooth", Lucide.Settings) {
            val i = if (fyt) ctx.packageManager.getLaunchIntentForPackage("com.syu.bt") else null
            runCatching { ctx.startActivity((i ?: Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(12.dp))
        Column { T(title, 15.sp, weight = FontWeight.Medium); T(sub, 12.sp, C.Sub) }
    }
}

/**
 * [art] when the player sends one, else the cover found online by artist and title (once per track, cached;
 * nothing offline, the placeholder stays).
 */
@Composable
private fun rememberCover(art: Bitmap?, artist: String, title: String): Bitmap? {
    val ctx = LocalContext.current
    var found by remember { mutableStateOf<Bitmap?>(null) }
    val key = if (art == null && title.isNotBlank()) "$artist|$title" else ""
    LaunchedEffect(key) {
        found = null
        if (key.isNotEmpty()) found = withContext(Dispatchers.IO) { runCatching { kg.forester.sg.service.Covers.find(ctx, artist, title) }.getOrNull() }
    }
    return art ?: found
}

/** The Bluetooth music session's app (see [MediaSessions.btController]: on two-module units not Android's own). */
private fun btSession(ctx: Context): String? = MediaSessions.btController(ctx)?.packageName

private fun btControl(ctx: Context, block: (android.media.session.MediaController.TransportControls) -> Unit) {
    MediaSessions.btController(ctx)?.transportControls?.let(block)
}

// --- other player apps --------------------------------------------------------------------------------

/** What another player's media session shows: track, cover, position, play state. */
internal data class PlayerState(
    val title: String = "", val artist: String = "", val album: String = "", val art: Bitmap? = null,
    val posMs: Long = 0, val totalMs: Long = 0, val playing: Boolean = false, val session: Boolean = false,
)

internal fun playerState(ctx: Context, pkg: String): PlayerState {
    val c = MediaSessions.controllerOf(ctx, pkg) ?: return PlayerState()
    val md = c.metadata
    val st = c.playbackState
    val playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
    // the session reports the position at its last update: run it on while playing
    val pos = st?.let {
        if (playing && it.lastPositionUpdateTime > 0)
            it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
        else it.position
    } ?: 0L
    return PlayerState(
        title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
        artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
        album = md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
        art = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART),
        posMs = pos.coerceAtLeast(0), totalMs = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0,
        playing = playing, session = true,
    )
}

private fun appLabel(ctx: Context, pkg: String): String = when (pkg) {
    "" -> ""
    "fyt" -> "Плеер магнитолы"
    else -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}

@Composable
private fun AppsHero(fyt: Boolean) {
    val ctx = LocalContext.current
    val m by Fyt.music.collectAsState()
    val pkg = remember { Repo.settings.musicApp }
    var ext by remember { mutableStateOf(PlayerState()) }
    LaunchedEffect(pkg) { while (true) { ext = if (pkg.isNotEmpty() && pkg != "fyt") playerState(ctx, pkg) else PlayerState(); delay(1000) } }
    val stock = pkg == "fyt"
    val label = remember(pkg) { appLabel(ctx, pkg) }
    fun control(block: (android.media.session.MediaController.TransportControls) -> Unit) {
        val c = MediaSessions.controllerOf(ctx, pkg)
        if (c == null) MediaSessions.open(ctx, pkg) else block(c.transportControls)
    }
    val playing = if (stock) m.source == "music" && m.playing else ext.playing
    fun prev() { if (stock) Fyt.prev() else if (pkg.isNotEmpty()) control { it.skipToPrevious() } }
    fun next() { if (stock) Fyt.next() else if (pkg.isNotEmpty()) control { it.skipToNext() } }
    Hero(
        cover = { GlowCover(rememberCover(ext.art, ext.artist, if (stock) "" else ext.title), Lucide.Music, playing, it) },
        onNext = ::next, onPrev = ::prev,
        info = {
            when {
                pkg.isEmpty() -> TrackInfo("Приложения", "Выберите плеер в списке", "Яндекс Музыка, VK, YouTube Music…")
                stock -> TrackInfo(label, m.title.ifBlank { "Нет трека" }, m.artist)
                else -> TrackInfo(label, ext.title.ifBlank { if (ext.session) "Нет трека" else "Нажмите ▶, чтобы открыть $label" }, ext.artist, ext.album)
            }
        },
        middle = {
            Spectrum(playing, Modifier.fillMaxWidth().height(48.dp).padding(bottom = 4.dp))
            SeekBar(if (stock) m.posMs else ext.posMs, if (stock) m.totalMs else ext.totalMs,
                if (!stock && ext.session && ext.totalMs > 0) { ms -> control { it.seekTo(ms) } } else null)
        },
        controls = {
            Control(Lucide.SkipBack, 68.dp) { prev() }
            Control(if (playing) Lucide.Pause else Lucide.Play, 96.dp, primary = true) {
                if (stock) Fyt.playPause() else if (pkg.isNotEmpty()) control { if (ext.playing) it.pause() else it.play() }
            }
            Control(Lucide.SkipForward, 68.dp) { next() }
        },
    )
}

@Composable
private fun AppsPanel(fyt: Boolean) {
    val ctx = LocalContext.current
    var pkg by remember { mutableStateOf(Repo.settings.musicApp) }
    var allowed by remember { mutableStateOf(MediaSessions.allowed(ctx)) }
    LaunchedEffect(Unit) { while (true) { allowed = MediaSessions.allowed(ctx); delay(2000) } }
    val players = remember { MediaSessions.players(ctx) }
    val label = remember(pkg) { appLabel(ctx, pkg) }
    PanelBox(Lucide.LayoutGrid, "Плееры", trailing = {
        if (pkg.isNotEmpty()) Pill("Открыть", Lucide.ExternalLink, height = 38.dp) { if (pkg == "fyt") Fyt.openMusic() else MediaSessions.open(ctx, pkg) }
    }) {
        if (!allowed && pkg.isNotEmpty() && pkg != "fyt") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Orange.copy(alpha = 0.12f)).clickable {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Bell, null, tint = C.Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                T("Чтобы видеть трек и управлять $label отсюда, нажмите и разрешите CarPulse доступ к уведомлениям", 13.sp, C.Text)
            }
            Spacer(Modifier.height(10.dp))
        }
        val tiles = buildList { if (fyt) add("fyt" to "Плеер магнитолы"); addAll(players) }
        if (tiles.isEmpty()) T("Плееров не нашёл: установите Яндекс Музыку, VK Музыку или другой", 14.sp, C.Sub)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val cols = 3
            val w = (maxWidth - 8.dp * (cols - 1)) / cols
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                tiles.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { (p, name) ->
                            AppTile(p, name, p == pkg, w) {
                                pkg = p; Repo.settings.musicApp = p
                                LocalMusic.pause()
                                appsRefresh.value++
                            }
                        }
                    }
                }
            }
        }
        T("Видео из приложений здесь не показывается: «Открыть» выводит само приложение.", 11.sp, C.Sub)
    }
}

/** Bumped when the panel picks another player, so the left side follows. */
internal val appsRefresh = kotlinx.coroutines.flow.MutableStateFlow(0)

@Composable
private fun AppTile(pkg: String, name: String, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(pkg) {
        if (pkg == "fyt") null else runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.width(width).height(108.dp).clip(shape)
            .background(if (selected) Brush.verticalGradient(listOf(C.Violet.copy(alpha = 0.28f), C.Purple.copy(alpha = 0.14f)))
                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.03f))))
            .border(1.dp, if (selected) C.Violet else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Image(icon, null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
        else IconTile(Lucide.Music)
        Spacer(Modifier.height(8.dp))
        T(name, 13.sp, if (selected) C.Text else C.Sub, FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
    }
}
