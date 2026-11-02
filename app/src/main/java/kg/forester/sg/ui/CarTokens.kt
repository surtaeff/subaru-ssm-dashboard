package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.service.audio.GENERIC
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Cap height of the app's text font over its em: Roboto, what Text draws with no font given, 1456 of 2048 units
 * (OS/2 sCapHeight of /system/fonts/Roboto-Regular.ttf). The bundled segment font is only for the digital dials, which
 * are sized by their own box.
 */
private const val CAP_HEIGHT = 0.711f

/** The canvas every head unit gets, whatever dpi it reports. */
private const val CANVAS_LONG = 1180f
private const val CANVAS_SHORT = 660f

/** Below this width the wide layout gives way to the compact one, so no scale may go there. */
private const val CANVAS_MIN = 900f

/** What is known with no diagonal told: 10.1" at 1280×720. */
const val MM_PER_DP_UNKNOWN = 0.19f

/** 7" and 8" screens: the mm per dp of an 8" at 1280×720 is 0.150. */
private const val SMALL_MM_PER_DP = 0.155f

/**
 * Sizes for reading at a glance from 70 to 80 cm, from how large a dp really is on this screen ([mmPerDp]):
 * [value] 4.65 mm of cap height (20' at 80 cm), [label] 3.26 mm (16' at 70 cm), [secondary] 2.44 mm (12' at 70 cm),
 * [tap] at least 64 dp and 12 mm, [gap] 3.8 mm between targets.
 */
@Immutable
data class CarTokens(val mmPerDp: Float) {
    val value: TextUnit get() = capSp(4.65f)
    val label: TextUnit get() = capSp(3.26f)
    val secondary: TextUnit get() = capSp(2.44f)
    val tap: Dp get() = maxOf(64f, 12f / mmPerDp).roundToInt().dp
    val gap: Dp get() = (3.8f / mmPerDp).roundToInt().dp
    /** A 7" or 8" screen: its factory layouts have fewer, larger cards. */
    val small: Boolean get() = mmPerDp < SMALL_MM_PER_DP

    /** Not rounded, and so never the same as a size written in the code: see [parkedSp]. */
    private fun capSp(capMm: Float) = (capMm / (CAP_HEIGHT * mmPerDp)).sp
}

val LocalCar: ProvidableCompositionLocal<CarTokens> = staticCompositionLocalOf { CarTokens(MM_PER_DP_UNKNOWN) }

/** The screen's class for what lives outside composition (the layouts' edits). */
object CarScreen {
    var small by mutableStateOf(false)
}

private fun fit(widthPx: Int, heightPx: Int) =
    minOf(maxOf(widthPx, heightPx) / CANVAS_LONG, minOf(widthPx, heightPx) / CANVAS_SHORT)

/**
 * A head unit's density from its panel alone: what it reports about its dpi is no use, so the canvas is about
 * 1180×660 dp both ways, also held upright. [scale] is a percentage on top of that (0 = 100), up to the widest it
 * can be and still have the wide layout with its menu.
 */
fun carDensity(widthPx: Int, heightPx: Int, scale: Int): Float {
    val d = fit(widthPx, heightPx) * if (scale > 0) scale / 100f else 1f
    return minOf(d, maxOf(widthPx, heightPx) / CANVAS_MIN)
}

/** Millimetres in a dp on the canvas of [carDensity] at 100 %; the scale then makes everything larger, tokens too. */
fun carMmPerDp(inches: Float, widthPx: Int, heightPx: Int): Float {
    if (inches <= 0f) return MM_PER_DP_UNKNOWN
    val f = fit(widthPx, heightPx)
    return inches * 25.4f / hypot(widthPx / f, heightPx / f)
}

/** What the compact panel offers: its menu always, or a screen is a dead end; the launch buttons only on a head unit. */
data class CompactChrome(val menu: Boolean, val quickLaunch: Boolean)

fun compactChrome(phone: Boolean) = CompactChrome(menu = true, quickLaunch = !phone)

/**
 * Whether the window is in split screen, kept by the activity (MainActivity.onMultiWindowModeChanged and its start):
 * reading Activity.isInMultiWindowMode while composing can be stale at the moment the narrow layout first appears,
 * the system tells the activity of the mode after it has told it of the new size.
 */
object SplitScreen {
    val on = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** «Весь экран» leaves split screen: it does something only in split screen, so only there is it shown. */
fun fullScreenOffered(inSplitScreen: Boolean) = inSplitScreen

/**
 * A phone: no head-unit family's signs, a telephony stack, a small width and upright by nature. The smallest width
 * alone made a phone of every 1024×600 above 160 dpi. Head units have telephony too (a 4G slot) and lie about their
 * dpi, but they aren't upright by nature; the upright ones of known families are told by their family.
 */
fun isPhoneBy(family: String, telephony: Boolean, smallestDp: Int, naturalLandscape: Boolean) =
    family == GENERIC && telephony && smallestDp < 600 && !naturalLandscape

/** What is told once for the whole process: the first answer stays. */
class OnceKnown<T : Any> {
    @Volatile private var known: T? = null
    fun get(find: () -> T): T = known ?: find().also { known = it }
}

private fun luminance(rgb: Int): Double {
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * ch(rgb shr 16 and 255) + 0.7152 * ch(rgb shr 8 and 255) + 0.0722 * ch(rgb and 255)
}

/** The WCAG contrast of two colours (0xRRGGBB). */
fun contrast(fg: Int, bg: Int): Float {
    val a = luminance(fg)
    val b = luminance(bg)
    return ((maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)).toFloat()
}

/** [rgb] as it is if it reads on [bg], otherwise mixed towards white just enough. */
fun liftForContrast(rgb: Int, bg: Int, min: Float): Int {
    if (contrast(rgb, bg) >= min) return rgb
    fun mix(v: Int, t: Int) = v + (255 - v) * t / 100
    return (1..100).asSequence().map { t ->
        mix(rgb shr 16 and 255, t) shl 16 or (mix(rgb shr 8 and 255, t) shl 8) or mix(rgb and 255, t)
    }.first { contrast(it, bg) >= min }
}

/**
 * A size on a screen read when parked: nothing below [CarTokens.secondary], what was larger stays larger by half of
 * the difference, so captions, text and headings keep their order, and what is large anyway stays. The tokens
 * themselves pass as they are.
 */
fun parkedSp(size: Float, car: CarTokens): Float {
    if (size == car.secondary.value || size == car.label.value || size == car.value.value) return size
    return maxOf(size, car.secondary.value + (size - 12f).coerceAtLeast(0f) * 0.5f)
}

/**
 * The size of a number in a box ([roomW], [roomH] in sp): as large as [maxSp], never wider or taller than the box. By
 * [widest] and [widestUnit], the longest it can be, not by what it is now: 9.9 → 10.0 doesn't change the size.
 */
fun fitValueSp(text: String, widest: String?, unit: String, widestUnit: String?, maxSp: Float, roomW: Float, roomH: Float, unitShare: Float, digitEm: Float = 0.62f): Float {
    val digits = maxOf(text.length, widest?.length ?: 0).coerceAtLeast(2)
    val units = maxOf(unit.length, widestUnit?.length ?: 0)
    // a bold digit is ~0.6 em wide, the line is ~1.2 em tall
    val byH = roomH / 1.2f
    val byW = (roomW - if (units == 0) 0f else 5f) / (digits * digitEm + units * 0.56f * unitShare)
    // a card stretched over many cells grows its number instead of keeping the size of a small card: up to 0.7 of
    // the height, and up to 0.85 of it as the width to spare grows (a wide card on a 1920×720 screen)
    val share = 0.7f + 0.15f * (byW / byH.coerceAtLeast(1f) - 1f).coerceIn(0f, 1f)
    return minOf(maxOf(maxSp, byH * share), byH, byW).coerceAtLeast(10f)
}

/**
 * The prompt is a window over everything: only at a standstill and with no alert to cover. Moving or an alert closes it
 * unanswered, so it is due again at the next stop. A car that tells no speed at all counts as standing: nothing says it moves.
 */
fun diagonalPromptShows(due: Boolean, ecuKmh: Int?, gpsKmh: Float?, alert: Boolean): Boolean =
    due && !alert && maxOf(ecuKmh?.toFloat() ?: 0f, gpsKmh ?: 0f) < PROMPT_MOVING_KMH

/** Below this GPS noise at a stop doesn't count as moving. */
private const val PROMPT_MOVING_KMH = 3f

/** [notice]: a percent was reset, and the driver is to be told. */
data class ScaleMigration(val scale: Int, val done: Boolean, val notice: Boolean)

/**
 * Once, on a head unit: a percent picked against the old sizes (of the dpi it reported) means something else on the
 * canvas of [carDensity]; 80 % could come out far too small. Back to 100, and said so. A phone goes by its own.
 */
fun migrateScale(saved: Int, phone: Boolean, done: Boolean): ScaleMigration =
    if (done || phone || saved == 0 || saved == 100) ScaleMigration(saved, true, false) else ScaleMigration(100, true, true)

/** The prompt on Home: the diagonal isn't known and wasn't asked yet, or a reset of the scale is to be told. */
fun diagonalPromptDue(phone: Boolean, inches: Float, asked: Boolean, setupDone: Boolean, notice: Boolean): Boolean =
    !phone && setupDone && (notice || (inches <= 0f && !asked))

/** Set: every [T] in here keeps to [parkedSp]. */
val LocalTextFloor = staticCompositionLocalOf<CarTokens?> { null }

/** The least height of a target: [Chip]s and buttons of the common kit. */
val LocalMinTap = staticCompositionLocalOf { 48.dp }

/** What is read parked (settings, logs, editors, pop-ups): secondary at least, targets of 56 dp. */
@Composable
fun Parked(tap: Dp = 56.dp, content: @Composable () -> Unit) {
    val car = LocalCar.current
    val secondary = car.secondary
    CompositionLocalProvider(
        LocalTextFloor provides car,
        LocalMinTap provides tap,
        // what has no size of its own: the text in the input fields
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontSize = secondary),
        content = content,
    )
}

/** The least height of a control made smaller than the kit's: the parked one where the targets are that, else its own. */
@Composable
fun parkedTap(): Dp = parkedTapOf(LocalMinTap.current)

fun parkedTapOf(minTap: Dp): Dp = if (minTap >= 56.dp) minTap else 0.dp

/** A fixed width made for a text of [sp]: wider as far as that text gets larger on a parked screen. */
@Composable
fun roomFor(base: Dp, sp: Float): Dp =
    LocalTextFloor.current.let { if (it != null) base * (parkedSp(sp, it) / sp) else base }

/** A text size as the room it takes. */
@Composable
fun TextUnit.asDp(): Dp = with(LocalDensity.current) { this@asDp.toDp() }

/** Height of a line of this size. */
@Composable
fun TextUnit.lineDp(): Dp = asDp() * 1.2f

/**
 * Text at [max], smaller only as far as its box needs, down to [min]; past that it ends in «…». For names and captions
 * of fixed cards, where a long Russian word on a small screen is the usual case.
 */
@Composable
fun FitText(
    text: String, max: TextUnit, modifier: Modifier = Modifier, color: Color = C.Text, weight: FontWeight = FontWeight.Normal,
    /** On a parked screen the secondary size is the least, then «…»; on a glance screen 0.7 of it as a last resort. */
    min: TextUnit = LocalCar.current.secondary.let { if (LocalTextFloor.current != null) it else (it.value * 0.7f).sp }, maxLines: Int = 1,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val look = C.palette
    val shown = look.cased(kg.forester.sg.data.tr(text))
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val pxPerSp = LocalDensity.current.let { it.density * it.fontScale }
    // the theme's italic depends on the colour only here; any other colour change just redraws what is laid out
    val subItalic = look.subItalic && color == look.sub
    // one node, no subcomposition: what a screen with dozens of these spent most of its first frame on. The size is worked out
    // when the node is measured, once for this text and room; the numbers that change often are the only ones laid out again
    val policy = remember(shown, max, min, weight, maxLines, pxPerSp, look.id, textAlign, subItalic, measurer) {
        FitTextPolicy(shown, max.value, min.value, weight, maxLines, pxPerSp, look, textAlign, if (subItalic) look.sub else Color.Unspecified, measurer)
    }
    androidx.compose.ui.layout.Layout(
        {},
        modifier
            .fitTextSemantics(shown)
            .drawBehind { policy.draw(this, color) },
        policy,
    )
}

private fun Modifier.fitTextSemantics(shown: String): Modifier =
    semantics { this.text = androidx.compose.ui.text.AnnotatedString(shown) }

/** How a [FitText] finds its size and lays itself out: as a Box of the text at the start, in the middle of its height. */
private class FitTextPolicy(
    private val text: String, private val maxSp: Float, private val minSp: Float, private val weight: FontWeight, private val maxLines: Int,
    private val pxPerSp: Float, private val look: Palette, private val textAlign: androidx.compose.ui.text.style.TextAlign?,
    private val styleColor: Color, private val measurer: androidx.compose.ui.text.TextMeasurer,
) : androidx.compose.ui.layout.MeasurePolicy {
    private var w = -1
    private var h = -1
    private var sp = 0f
    private var result: androidx.compose.ui.text.TextLayoutResult? = null
    private var top = 0
    private var left = 0

    private fun style(size: Float) = look.textStyle(TextStyle(fontSize = size.sp, fontWeight = weight), text)

    private fun sizeFor(w: Int, h: Int): Float {
        // short and plainly fitting, which most are: no need to lay it out twice
        val maxPx = maxSp * pxPerSp
        if (maxLines == 1 && text.length * look.charEm * maxPx <= w && maxPx * 1.2f <= h) return maxSp
        if (maxLines == 1) {
            // one line grows with its size: one measurement tells it
            val r = measurer.measure(text, style(maxSp), maxLines = 1, softWrap = false)
            val by = minOf(w.toFloat() / r.size.width.coerceAtLeast(1), h.toFloat() / r.size.height.coerceAtLeast(1))
            // with a little to spare: at exactly the width the last letter can still go to «…»
            return if (by >= 1.03f) maxSp else (maxSp * by * 0.97f).coerceIn(minOf(minSp, maxSp), maxSp)
        }
        var lo = minOf(minSp, maxSp)
        var hi = maxSp
        repeat(4) {
            val mid = (lo + hi) / 2
            val r = measurer.measure(text, style(mid), maxLines = maxLines, constraints = Constraints(maxWidth = w))
            if (!r.hasVisualOverflow && r.size.height <= h) lo = mid else hi = mid
        }
        return lo
    }

    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurables: List<androidx.compose.ui.layout.Measurable>, constraints: Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val cw = constraints.maxWidth
        val ch = constraints.maxHeight
        if (cw != w || ch != h || result == null) {
            w = cw
            h = ch
            sp = sizeFor(cw, ch)
            val final = look.textStyle(
                TextStyle(
                    fontSize = sp.sp, color = styleColor, fontWeight = look.weight(weight, sp), fontFamily = if (look.thin) androidx.compose.ui.text.font.FontFamily.SansSerif else null,
                    textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified,
                ),
                text, styleColor,
            )
            result = measurer.measure(
                text, final, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, maxLines = maxLines,
                constraints = if (textAlign != null) Constraints(minWidth = cw, maxWidth = cw, maxHeight = ch) else Constraints(maxWidth = cw, maxHeight = ch),
            )
        }
        val r = result!!
        val width = r.size.width.coerceIn(constraints.minWidth, cw)
        val height = r.size.height.coerceIn(constraints.minHeight, ch)
        return layout(width, height) {
            left = 0
            top = androidx.compose.ui.Alignment.CenterVertically.align(r.size.height, height)
        }
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, color: Color) {
        val r = result ?: return
        with(scope) { drawText(r, color = color, topLeft = androidx.compose.ui.geometry.Offset(left.toFloat(), top.toFloat())) }
    }
}

/**
 * A dialog on the app's canvas. Each dialog is a view of its own and gets the density of the window again, which
 * knows nothing of [carDensity]: on a 2000×1200 at 160 dpi it came out at half the size, on 1024×600 at 240 dpi too
 * large. What is read in it is read parked.
 */
@Composable
fun CarDialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        // a drag from a pointer that says it is a mouse scrolls here too
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.dragsLikeFinger()
        CompositionLocalProvider(LocalDensity provides density) { Parked(content = content) }
    }
}

/** The app's alert on [CarDialog]: the look of the Material one, whose own measures would be in the window's density. */
@Composable
fun CarAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    containerColor: Color = C.DialogHi,
    titleContentColor: Color = C.Text,
    textContentColor: Color = C.Text,
    /** Off for a [text] that has a scroller of its own: one can't be measured inside the other. */
    textScrolls: Boolean = true,
) {
    // the platform's width is of the window, in its density: this one is on the canvas
    CarDialog(onDismissRequest, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Column(
            modifier.padding(24.dp).widthIn(min = 280.dp, max = 720.dp)
                .clip(RoundedCornerShape(28.dp)).background(containerColor).padding(24.dp),
        ) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides C.Text) {
                if (icon != null) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { icon() }
                    Spacer(Modifier.height(14.dp))
                }
                if (title != null) {
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides titleContentColor) { title() }
                    Spacer(Modifier.height(14.dp))
                }
                if (text != null) {
                    // a long text on a low screen scrolls between the title and the buttons, which stay
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f, fill = false).then(if (textScrolls) Modifier.cueScroll(containerColor) else Modifier)) {
                        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides textContentColor) { text() }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                // Material's buttons in there: their text and their target to the parked sizes
                val type = androidx.compose.material3.MaterialTheme.typography
                androidx.compose.material3.MaterialTheme(typography = type.copy(labelLarge = type.labelLarge.copy(fontSize = LocalCar.current.secondary))) {
                    CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides LocalMinTap.current) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                            if (dismissButton != null) { dismissButton(); Spacer(Modifier.width(8.dp)) }
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/** The same for a pop-up: a view of its own with the window's density. */
@Composable
fun CarPopup(
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    offset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.ui.window.Popup(alignment = alignment, offset = offset, onDismissRequest = onDismissRequest) {
        CompositionLocalProvider(LocalDensity provides density) { Parked(content = content) }
    }
}

/** The same for a menu: what is in it on the canvas. */
@Composable
fun CarDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.material3.MenuDefaults.shape,
    containerColor: Color = androidx.compose.material3.MenuDefaults.containerColor,
    border: androidx.compose.foundation.BorderStroke? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier, shape = shape, containerColor = containerColor, border = border) {
        CompositionLocalProvider(LocalDensity provides density) { Parked { androidx.compose.foundation.layout.Column { content() } } }
    }
}

/**
 * Once on Home on a head unit that doesn't know its diagonal, also after an update, and to say that the scale was reset.
 * Over the cards, not among them; it decides nothing itself, goes away with a choice or when closed.
 */
@Composable
fun DiagonalPrompt() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s = kg.forester.sg.data.Repo.settings
    val inches by kg.forester.sg.data.Repo.screenInches.collectAsState()
    var closed by remember { mutableStateOf(false) }
    val notice = remember { s.scaleResetNotice }
    val due = remember(inches, closed) { !closed && diagonalPromptDue(kg.forester.sg.isPhone(ctx), inches, s.inchesAsked, s.setupDone, notice) }
    val live = kg.forester.sg.data.Repo.live.collectAsState()
    val gps = kg.forester.sg.data.Repo.gps.collectAsState()
    val alert = kg.forester.sg.service.Alerts.current.collectAsState()
    // read through this: speeds change all the time, the answer seldom
    val shows by remember(due) { androidx.compose.runtime.derivedStateOf { diagonalPromptShows(due, live.value.speedKmh, gps.value?.speedKmh, alert.value != null) } }
    if (!shows) return
    fun close() { s.inchesAsked = true; s.scaleResetNotice = false; closed = true }
    CarAlertDialog(
        onDismissRequest = ::close,
        confirmButton = { Chip("Закрыть", false, ::close) },
        title = { T("Размеры под ваш экран", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            androidx.compose.foundation.layout.Column {
                if (notice) {
                    T("Масштаб интерфейса сброшен на 100 %: теперь размеры идут от самой панели, прежний процент значил бы другое. " +
                        "Изменить: Настройки → «Внешний вид» → «Масштаб интерфейса».", 13.sp)
                    Spacer(Modifier.height(10.dp))
                }
                if (inches <= 0f) ScreenInchesChooser { close() }
            }
        },
    )
}
