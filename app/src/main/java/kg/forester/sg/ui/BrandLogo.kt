package kg.forester.sg.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Car
import com.composables.icons.lucide.Lucide
import kg.forester.sg.R
import kg.forester.sg.data.BrandLogos
import kg.forester.sg.data.CarKind
import kg.forester.sg.data.CarKind.LogoPick
import kg.forester.sg.data.CarKind.SplashPick
import kg.forester.sg.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The header's logo: Subaru's, the make's by the VIN, the driver's own picture or nothing (Настройки → «Логотип в шапке»). */
@Composable
fun BrandLogo(modifier: Modifier) {
    val ctx = LocalContext.current
    val pick = rememberLogoPick()
    if (pick is LogoPick.Text && pick.download != null) {
        val slug = CarKind.make.collectAsState().value?.slug
        LaunchedEffect(slug, pick.download) { if (slug != null) BrandLogos.download(ctx, slug, pick.download) }
    }
    LogoView(pick, modifier)
}

/** «Без логотипа» in the settings: the header leaves the logo's room to the name. */
@Composable
fun logoShown(): Boolean = rememberLogoPick() != LogoPick.None

@Composable
private fun rememberLogoPick(): LogoPick {
    val ctx = LocalContext.current
    val ver by Repo.carVersion.collectAsState()
    val other by CarKind.other.collectAsState()
    val make by CarKind.make.collectAsState()
    val files by BrandLogos.version.collectAsState()
    return remember(ver, other, make, files) { BrandLogos.pick(ctx, Repo.settings.logoMode, other, make) }
}

/** A logo as [pick] says, fitted into [modifier]'s box. */
@Composable
fun LogoView(pick: LogoPick, modifier: Modifier, textColor: Color = C.Text) {
    when (pick) {
        LogoPick.None -> Box(modifier)
        LogoPick.Subaru -> SharpLogo("res:subaru_logo", modifier, false)
        LogoPick.Generic -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Lucide.Car, null, tint = C.Sub, modifier = Modifier.fillMaxHeight(0.8f).fillMaxWidth(0.6f))
        }
        // on a light surface a dark logo stays as it is; the start-up screen (white text) is dark in every theme
        is LogoPick.Asset -> SharpLogo("asset:" + pick.path, modifier, textColor != C.Text || C.palette.hair == Color.White)
        is LogoPick.File -> SharpLogo(pick.path, modifier, textColor != C.Text || C.palette.hair == Color.White)
        is LogoPick.Text -> MakeName(pick.name, modifier, textColor)
    }
}

/**
 * Logos at the size they are shown, made once: a picture several times too large, left to the draw call's bilinear
 * filter, turned to mush in the header. Made off the main thread, kept for the box's size.
 */
private object LogoBitmaps {
    private val cache = android.util.LruCache<String, Pair<android.graphics.Bitmap, Boolean>>(8)

    fun cached(ref: String, w: Int, h: Int) = cache.get("$ref|$w|$h")

    fun make(ctx: android.content.Context, ref: String, boxW: Int, boxH: Int): Pair<android.graphics.Bitmap, Boolean>? {
        val key = "$ref|$boxW|$boxH"
        cache.get(key)?.let { return it }
        val src = if (ref == "res:subaru_logo") android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.subaru_logo) else BrandLogos.bitmap(ctx, ref)
        src ?: return null
        val dark = ref != "res:subaru_logo" && !BrandLogos.isOwn(ref) && BrandLogos.isDark(ref, src)
        val (w, h) = LogoScale.fit(src.width, src.height, boxW, boxH)
        var b: android.graphics.Bitmap = src
        LogoScale.steps(src.width, src.height, w, h).forEach { (sw, sh) -> b = android.graphics.Bitmap.createScaledBitmap(b, sw, sh, true) }
        // made smaller by more than a little: its edges back; an own picture at its size stays as it is
        if (b !== src && src.width >= w * 3 / 2) {
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val sharp = LogoScale.sharpen(px, w, h)
            b = android.graphics.Bitmap.createBitmap(sharp, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        }
        return (b to dark).also { cache.put(key, it) }
    }
}

@Composable
private fun SharpLogo(ref: String, modifier: Modifier, onDark: Boolean) {
    val ctx = LocalContext.current
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // with it: draw it light, a dark logo being invisible on the dark header (and the black start-up screen)
    val bmp by produceState(null as Pair<android.graphics.Bitmap, Boolean>?, ref, box) {
        if (box.width > 0 && box.height > 0) value = LogoBitmaps.cached(ref, box.width, box.height)
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LogoBitmaps.make(ctx, ref, box.width, box.height) }
    }
    Box(modifier.onSizeChanged { box = it }) {
        val pair = bmp ?: return@Box
        val (b, dark) = pair
        Image(
            remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            colorFilter = if (dark && onDark) androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFE6E8F0), androidx.compose.ui.graphics.BlendMode.SrcIn) else null,
        )
    }
}

/** The make's name in heavy capitals, as large as the box takes. */
@Composable
private fun MakeName(name: String, modifier: Modifier, color: Color) {
    val text = name.uppercase()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = LocalDensity.current
        // a capital of this weight is ~0.68 of the font size wide (with the extra spacing), ~0.72 tall
        val byWidth = with(d) { (maxWidth / (text.length.coerceAtLeast(3) * 0.68f)).toSp().value }
        val byHeight = with(d) { (maxHeight * 0.62f).toSp().value }
        val sp = minOf(byWidth, byHeight).coerceIn(8f, 160f)
        Text(
            text, maxLines = 1, softWrap = false,
            style = TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = (sp * 0.04f).sp,
                textAlign = TextAlign.Center),
        )
    }
}

/** The pick for this start: the demo drives a Subaru and the header says SUBARU, so the last real car's logo would contradict it. An own picture or the app's name, picked on purpose, stays. */
private fun splashPick(ctx: android.content.Context): SplashPick {
    val s = Repo.settings
    return if (s.demo && (s.splashMode == "subaru" || s.splashMode == "car")) SplashPick.Subaru else BrandLogos.splash(ctx)
}

/** The picture that goes up before the app is composed, so it covers the real loading; only once per process. */
fun splashComesFirst(ctx: android.content.Context): Boolean = !splashShown && splashPick(ctx) == SplashPick.CarPulse

/**
 * The start-up screen over everything for a moment (the 3D frames load meanwhile). «Как сейчас» is the Subaru picture
 * as it always was; the others (the car's logo, an own picture, the app's name) stay a second at most, less once the
 * car's data comes. «CarPulse» stays exactly as long as the start really takes (see [StartSteps]), no fixed wait.
 * A tap closes it. The window behind is plain black, so nothing else flashes first.
 * [appOn]: the app is being composed behind it; [onPicture]: time for that, the picture is up (or will not come).
 */
@Composable
fun StartSplash(appOn: Boolean = true, onPicture: () -> Unit = {}) {
    // once per start of the app: the activity is created again on a turn to landscape (phones), a new VIN or a
    // theme change, and a second splash then flashed after the first, possibly a different one
    if (remember { splashShown }) return
    val ctx = LocalContext.current
    val pick = remember { splashPick(ctx) }
    DisposableEffect(Unit) { onDispose { splashShown = true } }
    // a full-screen picture stays its whole time, as the Subaru one always did
    val subaru = pick == SplashPick.Subaru || pick == SplashPick.Route
    val own = pick == SplashPick.CarPulse
    var show by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(0) }
    val appIsOn = androidx.compose.runtime.rememberUpdatedState(appOn)
    LaunchedEffect(Unit) {
        if (subaru) { delay(1800); show = false; splashShown = true; return@LaunchedEffect }
        if (own) {
            androidx.compose.runtime.snapshotFlow { appIsOn.value }.first { it }
            // the frame after this one has the whole app in it
            androidx.compose.runtime.withFrameNanos { }
            done = 2
            if (StartSteps.carAsked) withTimeoutOrNull(OWN_SPLASH_CAR_MS) { StartSteps.carShown.first { it } }
            done = 3
        } else {
            delay(400)
            withTimeoutOrNull(600) { Repo.live.first { it.rpm != null } }
        }
        show = false
        splashShown = true
    }
    androidx.compose.animation.AnimatedVisibility(
        show,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (subaru) 600 else 300)),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, null) { show = false; onPicture() },
            contentAlignment = Alignment.Center,
        ) {
            when (pick) {
                SplashPick.CarPulse -> CarPulseSplash(done, { done = maxOf(done, 1) }, onPicture)
                SplashPick.Route -> RouteSplash()
                // drawable-port has an upright version for phones, so cropping never cuts the emblem
                SplashPick.Subaru -> Image(painterResource(R.drawable.splash), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                SplashPick.Name -> AppName()
                is SplashPick.Logo -> {
                    // an own picture takes the whole screen (fitted, on black); a logo sits in the middle
                    val photo = pick.logo is LogoPick.File && Repo.settings.splashMode == "custom"
                    if (photo) LogoView(pick.logo, Modifier.fillMaxSize())
                    else LogoView(pick.logo, Modifier.fillMaxWidth(0.46f).fillMaxHeight(0.38f), Color.White)
                }
            }
        }
    }
}

/** No longer than this for the 3D car's first frame: the app is there behind it, a slow unit needn't wait for the car. */
private const val OWN_SPLASH_CAR_MS = 2500L
/** A picture slower than this doesn't hold the app back. */
private const val OWN_PICTURE_MS = 400L

/**
 * The owner's picture by the window's shape, filling it, with the live loader over the place of the bar that was
 * retouched out of the file. Decoded off the main thread; the bitmap goes with this composable when the splash ends.
 */
@Composable
private fun CarPulseSplash(done: Int, onDecoded: () -> Unit, onPicture: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val art = remember(w, h) { SplashFrame.artFor(w, h) }
        val cover = remember(art, w, h) { SplashFrame.cover(art, w, h) }
        val bar = remember(art, cover, w, h) { SplashFrame.bar(art, cover, w, h) }
        val land = art === SplashFrame.LANDSCAPE
        val drawnW = Math.round(art.w * cover.scale)
        val drawnH = Math.round(art.h * cover.scale)
        var tried by remember { mutableStateOf(false) }
        val pic by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, land) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    android.graphics.BitmapFactory.decodeResource(ctx.resources, if (land) R.drawable.splash_carpulse_land else R.drawable.splash_carpulse_port)?.let { fitSplash(it, drawnW, drawnH) }?.asImageBitmap()
                }.getOrNull()
            }
            tried = true
        }
        LaunchedEffect(tried) {
            if (tried) {
                onDecoded()
                androidx.compose.runtime.withFrameNanos { } // drawn, and the first step shown: composing the app holds the thread
                androidx.compose.runtime.withFrameNanos { }
                onPicture()
            }
        }
        LaunchedEffect(Unit) { delay(OWN_PICTURE_MS); onPicture() }
        val share by androidx.compose.animation.core.animateFloatAsState(SplashFrame.share(done), if (done <= 1) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(220), label = "bar")
        val moving = androidx.compose.animation.core.rememberInfiniteTransition(label = "sheen")
        val sheen = moving.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100)), label = "sheenAt")
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            pic?.let {
                drawImage(
                    it,
                    dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(cover.left), Math.round(cover.top)),
                    dstSize = androidx.compose.ui.unit.IntSize(drawnW, drawnH),
                    filterQuality = if (it.width == drawnW) androidx.compose.ui.graphics.FilterQuality.None else androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
            val tl = androidx.compose.ui.geometry.Offset(bar.x0, bar.y0)
            val barH = bar.y1 - bar.y0
            val barW = bar.x1 - bar.x0
            val round = androidx.compose.ui.geometry.CornerRadius(barH / 2f)
            val full = androidx.compose.ui.geometry.Size(barW, barH)
            drawRoundRect(Color(0xF2070A10), tl, full, round)
            val inset = barH * 0.16f
            val fillW = (barW - inset * 2) * share.coerceAtLeast(0.04f)
            val innerH = barH - inset * 2
            val inner = androidx.compose.ui.geometry.Offset(bar.x0 + inset, bar.y0 + inset)
            // a soft red glow, then the fill; the light moving along it says the app is alive between the steps
            drawRoundRect(Color(0x55FF1A10), androidx.compose.ui.geometry.Offset(inner.x - inset, inner.y - inset), androidx.compose.ui.geometry.Size(fillW + inset * 2, innerH + inset * 2), round)
            drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFF2A1A), Color(0xFFFF6A4A), Color(0xFFE5120C)), inner.y, inner.y + innerH), inner, androidx.compose.ui.geometry.Size(fillW, innerH), androidx.compose.ui.geometry.CornerRadius(innerH / 2f))
            val lightW = fillW * 0.22f
            val at = inner.x + (fillW - lightW) * sheen.value
            drawRoundRect(Color(0x66FFFFFF), androidx.compose.ui.geometry.Offset(at, inner.y + innerH * 0.2f), androidx.compose.ui.geometry.Size(lightW, innerH * 0.3f), androidx.compose.ui.geometry.CornerRadius(innerH * 0.15f))
            drawRoundRect(Color(0xFF8E939E), tl, full, round, style = androidx.compose.ui.graphics.drawscope.Stroke(maxOf(1f, barH * 0.05f)))
        }
        val d = LocalDensity.current
        val wordPx = (bar.wordY1 - bar.wordY0) * 0.95f
        val wordSp = with(d) { wordPx.toSp() }
        Box(
            Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(Math.round((bar.x0 + bar.x1) / 2f - w / 2f), Math.round(bar.wordY0)) },
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                "Загрузка…", maxLines = 1, softWrap = false,
                style = TextStyle(fontSize = wordSp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color(0xFFD5D8DF), textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * The picture at the size it is drawn, when that is smaller than the file: no scaling on each frame of the loader, less to upload
 * and hold. A larger size keeps the file's pixels.
 */
private fun fitSplash(b: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Bitmap {
    if (w <= 0 || h <= 0 || w >= b.width) return b
    val small = android.graphics.Bitmap.createScaledBitmap(b, w, h, true)
    if (small !== b) b.recycle()
    return small
}

/** The splash has been on in this process (see [StartSplash]). */
private var splashShown = false

/**
 * The neon road through the mountains (3:2). Its ring must stay whole on any screen: a wide unit shows it at full
 * height with the dark edges carried on by a matching background, a 16:9 one crops a little off the top and bottom,
 * a phone held upright shows it at full width.
 */
@Composable
private fun RouteSplash() {
    androidx.compose.foundation.layout.BoxWithConstraints(
        // the picture's own edges: near black on the left, a touch of blue on the right
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF000104), Color(0xFF010913)))),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = maxWidth / maxHeight
        val scale = when {
            ratio >= 1.5f -> ContentScale.FillHeight
            ratio >= 1f -> ContentScale.Crop
            else -> ContentScale.FillWidth
        }
        Image(painterResource(R.drawable.splash_route), null, Modifier.fillMaxSize(), contentScale = scale)
    }
}

@Composable
private fun AppName() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            // the name has no make in it, so it fits any car
            androidx.compose.ui.res.stringResource(R.string.app_name), maxLines = 1,
            style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp),
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Accent))
    }
}

private val logoModes = listOf("auto" to "Авто по машине", "subaru" to "Subaru", "custom" to "Своя картинка", "none" to "Без логотипа")
private val splashModes = listOf(
    "carpulse" to "CarPulse", "route" to "Неоновый маршрут", "subaru" to "Авто: Subaru или логотип марки", "car" to "Логотип машины",
    "custom" to "Своя картинка", "name" to "Только название",
)

/** Настройки → «Внешний вид»: the header's logo and the start-up screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandSettings() {
    val ctx = LocalContext.current
    val s = Repo.settings
    Repo.carVersion.collectAsState().value
    BrandLogos.version.collectAsState().value
    val pickPicture = LocalPickGaugeImage.current
    val logo = s.logoMode
    val splash = s.splashMode

    T("Логотип в шапке", 15.sp)
    T("«Авто»: Subaru или логотип марки по VIN. Нет логотипа марки — её название крупными буквами", 11.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        logoModes.forEach { (k, l) ->
            Chip(l, logo == k, {
                // an own picture: the gallery first, the mode switches once a picture is in
                if (k == "custom" && BrandLogos.custom(ctx, "logo") == null) pickPicture("brand:logo") else s.logoMode = k
            })
        }
    }
    if (logo == "custom") {
        Spacer(Modifier.height(8.dp))
        PicturePreview(BrandLogos.custom(ctx, "logo")) { pickPicture("brand:logo") }
    }
    Spacer(Modifier.height(14.dp))
    ChoiceRow("Экран загрузки", splashModes, splash) { k ->
        if (k == "custom" && BrandLogos.custom(ctx, "splash") == null) pickPicture("brand:splash") else s.splashMode = k
    }
    if (splash == "custom") {
        Spacer(Modifier.height(8.dp))
        PicturePreview(BrandLogos.custom(ctx, "splash")) { pickPicture("brand:splash") }
    }
    if (splash == "car") {
        val make = CarKind.make.collectAsState().value
        T(make?.let { "Сейчас: ${it.name}" } ?: "Марка определится по VIN, пока — название приложения или Subaru", 11.sp, C.Sub)
    }
}

@Composable
private fun PicturePreview(path: String?, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 110.dp, height = 58.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(1.dp, C.Border, RoundedCornerShape(10.dp)).padding(6.dp),
        ) { if (path != null) LogoView(LogoPick.File(path), Modifier.fillMaxSize()) }
        Spacer(Modifier.width(10.dp))
        Chip(if (path == null) "Выбрать картинку" else "Другая картинка", false, onPick)
    }
}
