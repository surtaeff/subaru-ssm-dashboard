package kg.forester.sg.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interface colour themes, like the dash lighting of the cars: [main] is the highlight colour (C.Violet),
 * [deep] the selection (C.Purple), [second] the other end of the accent gradient. Ids are stored in the settings.
 */
enum class AccentTheme(val id: String, val title: String, val main: Color, val deep: Color, val second: Color) {
    VIOLET("violet", "Фиолетовый", Color(0xFFA855F7), Color(0xFF8B5CF6), Color(0xFF3B82F6)),
    GREEN("green", "Зелёный", Color(0xFF4ADE80), Color(0xFF16A34A), Color(0xFF0D9488)),
    RED("red", "Красный", Color(0xFFFF4D5E), Color(0xFFE11D48), Color(0xFFF97316)),
    BLUE("blue", "Синий", Color(0xFF60A5FA), Color(0xFF2563EB), Color(0xFF06B6D4)),
    ORANGE("orange", "Оранжевый", Color(0xFFFB923C), Color(0xFFEA580C), Color(0xFFE11D48));

    val theme get() = UiTheme(main, deep, second)

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: VIOLET
    }
}

/** The accent colours in use: one of the [AccentTheme]s or made from the user's own colour. */
data class UiTheme(val main: Color, val deep: Color, val second: Color) {
    companion object {
        /** From one picked colour: a deeper shade for the selection and a neighbouring hue for the gradient. */
        fun of(rgb: Int): UiTheme {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(rgb or 0xFF000000.toInt(), hsv)
            fun c(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(
                floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
            val v = hsv[2].coerceAtLeast(0.55f) // a very dark pick would vanish on the dark background
            // the main colour is also text and icons on the panels: a deep blue passes as bright and doesn't read
            val main = liftForContrast(c(hsv[0], hsv[1], v).toArgb() and 0xFFFFFF, Palette.NEON.cardHi.toArgb() and 0xFFFFFF, 4.5f)
            return UiTheme(Color(main or 0xFF000000.toInt()), c(hsv[0], hsv[1] * 1.1f + 0.05f, v * 0.8f), c(hsv[0] - 45f, hsv[1], v * 0.95f))
        }

        /** A stored id: "violet", "green", ... or "custom:RRGGBB". */
        fun parse(id: String?): UiTheme =
            id?.takeIf { it.startsWith("custom:") }?.removePrefix("custom:")?.toIntOrNull(16)?.let(::of)
                ?: AccentTheme.byId(id).theme
    }
}

object C {
    /** Stored id of the theme ("violet", ..., "custom:RRGGBB"). */
    var themeId by mutableStateOf("violet")
        private set
    /** The chosen hue as it was chosen; [theme] is how the [palette] shows it. */
    var chosen = AccentTheme.VIOLET.theme
        private set
    private var shown by mutableStateOf(chosen)
    /** The accent colours; Compose state, so everything drawn in them follows a change (also a live preview). */
    var theme: UiTheme
        get() = shown
        set(v) { chosen = v; shown = palette.accentFor(v) }

    fun applyTheme(id: String) {
        themeId = id
        theme = UiTheme.parse(id)
    }

    /** The look of the whole interface. One state, changed only on a switch: a read costs what any state read does. */
    var palette by mutableStateOf(Palette.NEON)
        private set

    fun applyPalette(id: String) {
        palette = Palette.byId(id)
        shown = palette.accentFor(chosen)
    }

    /** The Low tier: no gradient and no glow. */
    var flat by mutableStateOf(false)
    /** A 7" screen: smaller panel corners where the theme has them. */
    var smallCorners by mutableStateOf(false)

    val Bg: Color get() = palette.bg
    val Card: Color get() = palette.card
    val CardHi: Color get() = palette.cardHi
    /** A dialog's panel: solid in every theme. */
    val Sheet: Color get() = palette.sheet
    /** The raised fill of an alert: [CardHi], unless that one is see-through in this theme. */
    val DialogHi: Color get() = if (palette.cardHi.alpha < 1f) palette.sheet else palette.cardHi
    val Border: Color get() = palette.border
    val Text: Color get() = palette.text
    val Sub: Color get() = palette.sub
    /** The big values. */
    val Ink: Color get() = palette.ink
    /** The base of faint lines and fills, with an alpha. */
    val Hair: Color get() = palette.hair
    /** A darker patch over a panel, for pills and bars: as asked on the dark themes, a quarter of it on a light one. */
    fun shade(alpha: Float): Color = Color.Black.copy(alpha = if (palette.hair == Color.White) alpha else alpha * 0.25f)
    /** Text and icons on a filled accent. */
    val OnAccent: Color get() = palette.onAccent
    val Overlay: Color get() = palette.overlay
    val Veil: Color get() = palette.veil
    val Yellow: Color get() = palette.yellow
    /** Not for text: 4.30:1 on a panel, for selection and borders. */
    val Purple: Color get() = theme.deep
    val Violet: Color get() = theme.main
    val Blue: Color get() = palette.blue
    val Green: Color get() = palette.green
    val Orange: Color get() = palette.orange
    val Red: Color get() = palette.red
    /** Not for text: 3.82:1 on a panel. */
    val Gray: Color get() = palette.gray
    val Accent: Brush get() = Brush.horizontalGradient(palette.accentFill ?: listOf(theme.second, theme.main))
    /** A selected item's fill: [tint], or the solid one of the themes that have it. */
    fun activeFill(tint: Color): Color = palette.activeFill ?: tint
    /** What is written and drawn on a selected item. */
    fun activeText(usual: Color): Color = palette.activeText ?: usual
    /** A small chip's or tab's fill: solid only in the racing theme; the other solid themes write their own text on theirs. */
    fun selectFill(tint: Color): Color = if (palette.solidSmall) palette.activeFill ?: tint else tint
    /** What is written on [selectFill]. */
    fun selectText(usual: Color): Color = if (palette.solidSmall) palette.activeText ?: usual else usual
    /** A section's title where the theme colours it. */
    fun titleColor(usual: Color): Color = palette.titleBand?.let { Color.White } ?: palette.titleColor ?: usual
    /** Peaks and timers. */
    fun highlight(usual: Color): Color = palette.highlight ?: usual
    /** The menu's idle items where it has a bar of its own. */
    fun navText(usual: Color): Color = palette.navText ?: usual
    /** A selected item's outline. */
    fun activeEdge(usual: Color): Color = palette.activeEdge ?: usual
    /** An unselected chip's fill. */
    fun idleFill(usual: Color): Color = palette.idleFill ?: usual
    /** An unselected chip's thin edge where the theme wants one: its fill alone would melt into the panel. */
    fun idleEdge(usual: Color): Color = if (palette.idleFill != null) palette.border else usual
}

/**
 * For a tab in a scrolling row or the rail: [selected], it is brought fully into view, [margin] clear of the row's ends
 * (where a fade may lie over them), and so a tab half under the edge isn't left cut when it is tapped.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.keepInView(selected: Boolean, margin: Dp = 0.dp): Modifier {
    val requester = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var box by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(selected, box) {
        if (selected && box.height > 0) {
            val m = with(density) { margin.toPx() }
            requester.bringIntoView(androidx.compose.ui.geometry.Rect(-m, -m, box.width + m, box.height + m))
        }
    }
    return bringIntoViewRequester(requester).onSizeChanged { box = it }
}

/** The panels' shape in this theme. */
val Rounded: androidx.compose.ui.graphics.Shape get() = if (C.smallCorners) C.palette.shapeSmall else C.palette.shape

/** A panel's fill: flat [C.Card], or the theme's diagonal gradient, cached for each panel's size. */
fun Modifier.panelFill(fill: Color = C.Card): Modifier {
    val g = C.palette.cardGradient
    return if (g == null || C.flat) background(fill)
    else drawWithCache {
        val b = Brush.linearGradient(g, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height))
        onDrawBehind { drawRect(b) }
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    /** Over its warning line: the frame is thick and orange, so it shows without the colour of the number. */
    warn: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .panelShadow()
            .clip(Rounded)
            .panelFill()
            .panelMarks()
            .border(if (warn) BorderStroke(3.dp, C.Orange) else BorderStroke(1.dp, C.Border), Rounded)
            .padding(padding),
        content = content,
    )
}

@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = C.Text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    tracking: TextUnit = TextUnit.Unspecified,
) {
    val floor = LocalTextFloor.current
    Text(
        text, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(
            fontSize = if (floor != null && size.isSp) parkedSp(size.value, floor).sp else size, color = color,
            fontWeight = C.palette.weight(weight, if (size.isSp) size.value else 0f), fontFamily = if (C.palette.thin) androidx.compose.ui.text.font.FontFamily.SansSerif else null,
            textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified, letterSpacing = tracking,
        ),
    )
}

/**
 * A small control ([content]) with a tap area at least [minHeight] tall: the control keeps its own size in the layout
 * (a title row does not grow) and the area that takes the tap reaches past it, up and down into the space around.
 */
@Composable
fun TapTarget(onClick: () -> Unit, minHeight: Dp = LocalMinTap.current, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(
        content = {
            Box { content() }
            Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick))
        },
    ) { measurables, c ->
        val main = measurables[0].measure(c)
        val tapH = maxOf(main.height, minHeight.roundToPx())
        val tap = measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed(main.width, tapH))
        layout(main.width, main.height) {
            main.place(0, 0)
            tap.place(0, (main.height - tapH) / 2)
        }
    }
}

/**
 * A text field's label: as large as the field gives it while the field is empty (up to 14 sp, so a long hint
 * still fits the field; it was a fixed 12 sp), shrinking with it to the small caption above the text.
 */
@Composable
fun FieldLabel(text: String) {
    val size = androidx.compose.material3.LocalTextStyle.current.fontSize
    val floor = LocalTextFloor.current
    if (floor != null) {
        // a parked screen: the field's own animation (16 sp empty, 12 sp above the text) on the secondary size
        val sp = floor.secondary.value * if (size.isSp) (size.value / 16f).coerceAtMost(1f) else 0.75f
        Text(text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = TextStyle(fontSize = sp.sp, color = C.Sub))
    } else T(text, if (size.isSp && size.value > 14f) 14.sp else if (size.isSp) size else 12.sp, C.Sub, maxLines = 1)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PanelTitle(icon: ImageVector, title: String, trailing: @Composable () -> Unit = {}) {
    // the buttons beside the name while there is room, on a line of their own when there isn't: squeezed, the name
    // went a letter a line
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val p = C.palette
        val band = p.titleBand
        Row(
            Modifier.align(Alignment.CenterVertically).then(if (band != null) Modifier.background(band).padding(horizontal = 8.dp, vertical = 3.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (band != null) Color.White else C.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            T(p.titled(title), 17.sp, C.titleColor(C.Text), FontWeight.SemiBold, tracking = if (p.titleTracking > 0f) androidx.compose.ui.unit.TextUnit(p.titleTracking, androidx.compose.ui.unit.TextUnitType.Em) else TextUnit.Unspecified)
        }
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            trailing()
            InfoButton(title)
        }
    }
}

/**
 * A shared button's tap, with its click. Plain taps only; the sound is only posted, see [kg.forester.sg.service.TapSound].
 * [afterClick]: the action first, for a switch that may be the sound's own (switching it off must not click).
 */
fun Modifier.tapClickable(enabled: Boolean = true, role: androidx.compose.ui.semantics.Role? = null, afterClick: Boolean = false, onClick: () -> Unit): Modifier =
    clickable(enabled = enabled, role = role) {
        if (!afterClick) kg.forester.sg.service.TapSound.tap()
        onClick()
        if (afterClick) kg.forester.sg.service.TapSound.tap()
    }

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            // a target a finger hits on a moving car (it was ~40): 48 dp at least, 56 on the screens read parked
            .heightIn(min = LocalMinTap.current)
            .clip(shape)
            .background(if (selected) C.activeFill(C.Purple.copy(alpha = 0.28f)) else C.idleFill(C.CardHi))
            .border(1.dp, if (selected) C.activeEdge(C.Purple) else C.Border, shape)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) C.activeText(C.Violet) else C.Sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        T(text, 14.sp, if (selected) C.activeText(C.Text) else C.Sub, FontWeight.Medium, maxLines = 1)
    }
}

/** Small stat tile: icon + label + value + unit. */
@Composable
fun Tile(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier = Modifier, info: String = label) {
    val shown by rememberDigits { value }
    val car = LocalCar.current
    // the "i" sits in the corner, so the value and its unit keep the whole width of a narrow tile
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
        if (maxHeight > 96.dp) {
            // a tall tile: the name on top, the number as big as the tile allows
            Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 22.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = C.Sub, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    FitText(label, car.secondary, Modifier.weight(1f), C.Sub)
                }
                FitValue(shown, unit, car.label.value, Modifier.fillMaxWidth().weight(1f), widest = "88.8", everyMs = 0L)
            }
            InfoButton(label, info, Modifier.align(Alignment.TopEnd))
            return@BoxWithConstraints
        }
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = C.Sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FitText(label, car.secondary, Modifier.fillMaxWidth(), C.Sub)
                FitValue(shown, unit, car.label.value, Modifier.fillMaxWidth().weight(1f), widest = "88.8", everyMs = 0L)
            }
        }
        InfoButton(label, info, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
fun ProgressBar(fraction: Float, brush: Brush, modifier: Modifier = Modifier, height: Dp = 8.dp, known: Boolean = true) =
    ProgressBar(androidx.compose.runtime.rememberUpdatedState(fraction), brush, modifier, height) { known }

/** The same on a State, for a caller that does not recompose with each reading. */
@Composable
fun ProgressBar(fraction: androidx.compose.runtime.State<Float>, brush: Brush, modifier: Modifier = Modifier, height: Dp = 8.dp, known: () -> Boolean = { true }) {
    val reading = remember(fraction) { androidx.compose.runtime.derivedStateOf { fraction.value.coerceIn(0f, 1f) } }
    val anim = rememberSmooth(reading, 1f, known = known)
    // the width is read only while drawing: the bar's frames redraw the bar, without recomposing or re-measuring it
    Box(
        modifier
            .height(height)
            .drawBehind {
                // half a pixel of this bar: a value trembling less than that costs no frame
                anim.quantum = 0.5f / size.width.coerceAtLeast(1f)
                val rad = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(C.Border, cornerRadius = rad)
                drawRoundRect(brush, size = Size(size.width * anim.value.floatValue.coerceIn(0.001f, 1f), size.height), cornerRadius = rad)
            }
    )
}

/** 240° arc gauge with ticks and a knob at the current value. */
@Composable
fun ArcGauge(value: Float, max: Float, ticks: List<Int>, modifier: Modifier = Modifier) {
    val animFrac = rememberSmooth((value / max).coerceIn(0f, 1f), 1f)
    Canvas(modifier) {
        val stroke = (size.minDimension * 0.075f).coerceIn(4.dp.toPx(), 18.dp.toPx())
        val r = size.minDimension / 2 - stroke
        // half a pixel along the 240° arc: a reading trembling less than that costs no frame
        animFrac.quantum = 0.5f / (r * 4.19f).coerceAtLeast(1f)
        val center = Offset(size.width / 2, size.height / 2 + r * 0.15f)
        val showTicks = size.minDimension > 180.dp.toPx()
        val start = 150f
        val sweep = 240f
        val tl = Offset(center.x - r, center.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawArc(C.Border, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val frac = animFrac.value.floatValue.coerceIn(0f, 1f)
        drawArc(
            Brush.sweepGradient(listOf(C.Blue, C.Violet, C.Purple, C.Blue), center),
            start, sweep * frac, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // minor ticks
        if (showTicks) for (i in 0..40) {
            val a = Math.toRadians((start + sweep * i / 40.0))
            val r1 = r - stroke * 1.3f
            val r2 = r1 - if (i % 5 == 0) 12.dp.toPx() else 6.dp.toPx()
            drawLine(
                C.Sub.copy(alpha = 0.7f),
                Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
                strokeWidth = 2f,
            )
        }
        val ka = Math.toRadians((start + sweep * frac).toDouble())
        val kp = Offset(center.x + r * cos(ka).toFloat(), center.y + r * sin(ka).toFloat())
        drawCircle(C.Ink, stroke * 0.55f, kp)
        drawCircle(C.Purple, stroke * 0.55f, kp, style = Stroke(3f))
    }
}

/** Donut with segments (fraction, color). */
@Composable
fun Donut(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 22.dp.toPx()
        val r = size.minDimension / 2 - stroke / 2
        val tl = Offset(size.width / 2 - r, size.height / 2 - r)
        val s = Size(r * 2, r * 2)
        val total = segments.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) {
            drawArc(C.Border, 0f, 360f, false, tl, s, style = Stroke(stroke))
            return@Canvas
        }
        var a = -90f
        segments.forEach { (f, color) ->
            val sw = 360f * f / total
            drawArc(color, a + 1f, (sw - 2f).coerceAtLeast(0.5f), false, tl, s, style = Stroke(stroke))
            a += sw
        }
    }
}

/** Area line chart with a dashed average line. */
@Composable
fun AreaChart(values: List<Float?>, maxY: Float, avg: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3
            drawLine(C.Border, Offset(0f, y), Offset(w, y), 1f)
        }
        if (values.size < 2) return@Canvas
        val step = w / (values.size - 1)
        // null = no data / standing: the line breaks there instead of dropping to zero
        val path = Path()
        val fill = Path()
        var open = false
        var segStart = 0f
        var lastX = 0f
        values.forEachIndexed { i, v ->
            val x = i * step
            if (v == null) {
                if (open) { fill.lineTo(lastX, h); fill.lineTo(segStart, h); fill.close(); open = false }
                return@forEachIndexed
            }
            val y = h - (v / maxY).coerceIn(0f, 1f) * h
            if (!open) {
                path.moveTo(x, y); fill.moveTo(x, y); segStart = x; open = true
            } else {
                path.lineTo(x, y); fill.lineTo(x, y)
            }
            lastX = x
        }
        if (open) { fill.lineTo(lastX, h); fill.lineTo(segStart, h); fill.close() }
        drawPath(fill, Brush.verticalGradient(listOf(C.Purple.copy(alpha = 0.55f), C.Blue.copy(alpha = 0.05f))))
        drawPath(path, C.Violet, style = Stroke(2.5f))
        if (avg != null) {
            val y = h - (avg / maxY).coerceIn(0f, 1f) * h
            drawLine(C.Ink, Offset(0f, y), Offset(w, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}

fun fmt(v: Double?, digits: Int = 1, dash: String = "—"): String =
    if (v == null || v.isNaN() || v.isInfinite()) dash else "%.${digits}f".format(v)

fun fmtF(v: Float?, digits: Int = 1): String = fmt(v?.toDouble(), digits)


data class Series(val name: String, val values: List<Float?>, val color: Color)

/** Rolling multi-line chart for live data; [min]..[max] is the Y range. */
@Composable
fun LiveChart(series: List<Series>, min: Float, max: Float, modifier: Modifier = Modifier, zeroLine: Boolean = false) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..4) {
            val y = h * i / 4
            drawLine(C.Border, Offset(0f, y), Offset(w, y), 1f)
        }
        fun yOf(v: Float) = h - ((v - min) / (max - min)).coerceIn(0f, 1f) * h
        if (zeroLine && min < 0 && max > 0) {
            drawLine(C.Sub, Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        series.forEachIndexed { idx, s ->
            val n = s.values.size
            if (n < 2) return@forEachIndexed
            val step = w / (n - 1)
            val path = Path()
            var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = i * step
                val y = yOf(v)
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            if (idx == 0) {
                val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), Color.Transparent)))
            }
            drawPath(path, s.color, style = Stroke(3f, cap = StrokeCap.Round))
            s.values.lastOrNull()?.let { drawCircle(s.color, 5f, Offset(w, yOf(it))) }
        }
    }
}

@Composable
fun Legend(series: List<Series>, format: String = "%.1f") {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        series.forEach { s ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(s.color))
                Spacer(Modifier.width(6.dp))
                FitText("${s.name} ${s.values.lastOrNull()?.let { format.format(it) } ?: "—"}", LocalCar.current.secondary, color = C.Sub)
            }
        }
    }
}

/** Panel with a title row, legend and a live chart filling the rest. */
@Composable
fun ChartCard(title: String, series: List<Series>, min: Float, max: Float, modifier: Modifier, format: String = "%.1f", zeroLine: Boolean = false, info: String = title,
    icon: ImageVector? = null, tint: Color = C.Violet) {
    ChartPanel(title, min, max, modifier, format, info, icon, tint, { Legend(series, format) }) {
        LiveChart(series, min, max, Modifier.fillMaxSize(), zeroLine)
        if (series.all { sr -> sr.values.all { it == null } }) T("Нет данных", LocalCar.current.secondary, C.Sub, modifier = Modifier.align(Alignment.Center))
    }
}

/** The same with the running lines of [lines]. */
@Composable
fun ChartCard(title: String, lines: ChartLines, min: Float, max: Float, modifier: Modifier, format: String = "%.1f", zeroLine: Boolean = false, info: String = title,
    icon: ImageVector? = null, tint: Color = C.Violet) {
    ChartPanel(title, min, max, modifier, format, info, icon, tint, { LiveLegend(lines, format) }, !(LocalCar.current.small && lines.defs.size >= 3)) {
        LiveChart(lines, min, max, Modifier.fillMaxSize(), zeroLine)
        if (!lines.hasData.value) T("Нет данных", LocalCar.current.secondary, C.Sub, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ChartPanel(
    title: String, min: Float, max: Float, modifier: Modifier, format: String, info: String, icon: ImageVector?, tint: Color,
    legend: @Composable () -> Unit, showTitle: Boolean = true, chart: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val car = LocalCar.current
    Panel(modifier, padding = 12.dp) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            // the legend's numbers first: the name takes the rest, smaller if it has to; three lines on a small screen
            // leave it no room, the legend says what is on it and the "i" the rest
            if (showTitle) FitText(title, car.label, Modifier.weight(1f), weight = FontWeight.SemiBold)
            else Spacer(Modifier.weight(1f))
            legend()
            InfoButton(title, info)
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
            val line = car.secondary.lineDp()
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.width(car.secondary.asDp() * 2.1f).fillMaxHeight()) {
                // the middle mark only where three fit
                val middle = maxHeight >= line * 3f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    FitText(format.format(max), car.secondary, Modifier.fillMaxWidth(), C.Sub)
                    if (middle) FitText(format.format((max + min) / 2), car.secondary, Modifier.fillMaxWidth(), C.Sub)
                    FitText(format.format(min), car.secondary, Modifier.fillMaxWidth(), C.Sub)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), content = chart)
        }
    }
}

/** One line of a running chart: how it is called, its colour and what it shows of a reading. */
class ChartLine<T>(val name: String, val color: Color, val read: (T) -> Float?)

/**
 * The lines of one running chart, from a [kg.forester.sg.data.SampleLog]: X from the readings' times, so uneven readings
 * don't bend the curve, the last [windowMs] on the width. The readings come in rings of their own with no copying; a
 * new one redraws the lines once, between readings the picture only slides, on the frames' clock, a pixel at a time. It runs behind the
 * readings by [lagMs], so the line's end is off the right edge when the next reading arrives and it never shows a hole
 * there. Asleep (no frames) with no new readings, or when the whole window is one flat line.
 */
class ChartLines internal constructor(
    val defs: List<Line>, val windowMs: Long, val lagMs: Long, val breakMs: Long = ChartRing.BREAK_MS,
) : Stepper {
    class Line(val name: String, val color: Color, capacity: Int) {
        val ring = ChartRing(capacity)
    }

    /** A new reading came, read by the legend. */
    val samples = androidx.compose.runtime.mutableIntStateOf(0)

    /** The chart's turn to paint its lines anew has come ([RecordTurns]), read while drawing. */
    val paintTurn = androidx.compose.runtime.mutableIntStateOf(0)
    val hasData = androidx.compose.runtime.mutableStateOf(false)

    /**
     * Changes each time the picture has a whole pixel further to slide; read while drawing. A minute on a chart's width
     * is a pixel in some 50 ms: no frame between pixels, and all the charts of a width step on the same frame.
     */
    val pixel = androidx.compose.runtime.mutableIntStateOf(0)

    /** Pixels a second on the screen, from the drawing. */
    var pxPerS = 0f

    private var elapsed = 0.0
    private val clock = ChartClock()
    private var lastSample = 0.0
    private var newest = -1L
    private var clears = 0
    private var running = false
    private val pace = kg.forester.sg.data.RepaintPace()

    private fun clockMs() = System.nanoTime() / 1_000_000L

    /** New readings to paint: now, or when this head unit's time between repaints has passed. */
    private fun askToPaint() {
        if (pace.ask(clockMs(), kg.forester.sg.data.DeviceTier.look.chartPaintMs)) ChartPainter.ask(this)
    }

    /** The time at the right edge. */
    fun edgeMs(): Long = clock.nowMs(elapsed) - lagMs

    internal fun <T : Any> take(log: kg.forester.sg.data.SampleLog<T>, read: List<(T) -> Float?>) {
        if (log.clears != clears) {
            clears = log.clears
            defs.forEach { it.ring.clear() }
            newest = -1L
            hasData.value = false
            samples.intValue++
            ChartPainter.ask(this)
        }
        val before = newest
        log.since(newest) { t, s ->
            defs.forEachIndexed { i, l -> l.ring.add(t, read[i](s) ?: Float.NaN) }
            newest = t
        }
        if (newest == before) return
        hasData.value = defs.any { l -> (0 until l.ring.size).any { !l.ring.valueAt(it).isNaN() } }
        // a whole window of one flat line looks the same with one more reading: no frame for it, and its time stands
        // still with the picture, so a redraw for any other reason finds it where it was. Its first look is always drawn.
        val flat = flat()
        if (flat && !running && before != -1L) return
        if (!running) clock.reset()
        clock.onSample(newest, elapsed)
        lastSample = elapsed
        samples.intValue++
        askToPaint()
        if (!flat) { running = true; SmoothDriver.wake(this) }
        // no frames to wait on
        else if (!running && pace.flush(clockMs())) ChartPainter.ask(this)
    }

    private fun flat() = defs.all { it.ring.flat(newest, windowMs) }

    override fun step(dt: Float): Boolean {
        if (!running) return false
        // a long frame (the screen just back) is no reason to slide: the next reading sets the time anyway
        elapsed += minOf(dt, 0.25f).toDouble()
        kg.forester.sg.data.DeviceTier.drawing()
        val look = kg.forester.sg.data.DeviceTier.look
        if (SmoothDriver.ticks(look.chartHz)) pixel.intValue = (System.nanoTime() % 1_000_000_000_000L / 1e9 * pxPerS).toInt()
        if (pace.due(clockMs(), look.chartPaintMs)) ChartPainter.ask(this)
        running = elapsed - lastSample < lagMs / 1000.0 + 1.0 && !flat()
        if (!running && pace.flush(clockMs())) ChartPainter.ask(this)
        return running
    }
}

/** Gives the charts waiting to paint anew their turns, one a frame, on the frame loop. */
internal object ChartPainter : Stepper {
    private val turns = RecordTurns<ChartLines>()

    fun ask(c: ChartLines) {
        turns.ask(c)
        SmoothDriver.wake(this)
    }

    fun drop(c: ChartLines) = turns.drop(c)

    override fun step(dt: Float): Boolean {
        turns.next()?.let { it.paintTurn.intValue++ }
        return turns.pending
    }
}

/** The running lines over [log], one per entry of [lines]; every reading since the chart opened and what the log had. */
@Composable
fun <T : Any> rememberChart(
    log: kg.forester.sg.data.SampleLog<T>, vararg lines: ChartLine<T>, capacity: Int = 300, windowMs: Long = 60_000L, lagMs: Long = 600L,
    breakMs: Long = ChartRing.BREAK_MS,
): ChartLines {
    val read = remember(log) { lines.map { it.read } }
    // by the colours too: a new theme paints the lines anew. What the log has is taken at once, so the first frame has it
    val chart = remember(log, lines.map { it.color }) {
        ChartLines(lines.map { ChartLines.Line(it.name, it.color, capacity) }, windowMs, lagMs, breakMs).also { it.take(log, read) }
    }
    androidx.compose.runtime.LaunchedEffect(chart) { log.version.collect { chart.take(log, read) } }
    androidx.compose.runtime.DisposableEffect(chart) { onDispose { SmoothDriver.drop(chart); ChartPainter.drop(chart) } }
    return chart
}

/** [LiveChart] on running lines: the grid stays, the lines are drawn into a layer once per reading and slide in it. */
@Composable
fun LiveChart(lines: ChartLines, min: Float, max: Float, modifier: Modifier = Modifier, zeroLine: Boolean = false) {
    val grid = C.Border
    val zero = C.Sub
    val draw = remember(lines, min, max, zeroLine, grid, zero) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val px = w / lines.windowMs
            fun yOf(v: Float) = h - ((v - min) / (max - min)).coerceIn(0f, 1f) * h
            val cap = lines.defs.maxOf { it.ring.capacity }
            val xy = FloatArray(cap * 2)
            val runs = IntArray(cap * 2)
            val fill = Path()
            val line = Path()
            val lineStroke = Stroke(3f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            lines.pxPerS = px * 1000f
            // the line runs on past the right edge by the lag, and a little more for the time until the next redraw
            val room = IntSize(kotlin.math.ceil(w + lines.lagMs * px + 8f).toInt().coerceAtLeast(1), kotlin.math.ceil(h).toInt().coerceAtLeast(1))
            // a picture of its own: sliding the lines moves it. Painted by the CPU: a render target of the GPU for each new reading
            // of each chart was the costliest frame of the screen
            val picture = androidx.compose.ui.graphics.ImageBitmap(room.width, room.height)
            val onPicture = androidx.compose.ui.graphics.Canvas(picture)
            val painter = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
            var recorded = -1
            var recordedAt = 0L
            onDrawBehind {
                for (i in 0..4) {
                    val y = h * i / 4
                    drawLine(grid, Offset(0f, y), Offset(w, y), 1f)
                }
                if (zeroLine && min < 0 && max > 0) {
                    drawLine(zero, Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
                val version = lines.paintTurn.intValue
                lines.pixel.intValue
                val edge = lines.edgeMs()
                if (version != recorded) {
                    recorded = version
                    recordedAt = edge
                    picture.asAndroidBitmap().eraseColor(0)
                    painter.draw(this, layoutDirection, onPicture, Size(room.width.toFloat(), room.height.toFloat())) {
                        lines.defs.forEachIndexed { idx, l ->
                            val n = l.ring.project(xy, runs, edge, lines.windowMs, w, lines.breakMs) { yOf(it) }
                            if (idx == 0) {
                                fill.rewind()
                                for (r in 0 until n) {
                                    val from = runs[r * 2]
                                    val count = runs[r * 2 + 1]
                                    fill.moveTo(xy[from * 2], xy[from * 2 + 1])
                                    for (i in from + 1 until from + count) fill.lineTo(xy[i * 2], xy[i * 2 + 1])
                                    fill.lineTo(xy[(from + count - 1) * 2], h)
                                    fill.lineTo(xy[from * 2], h)
                                    fill.close()
                                }
                                drawPath(fill, Brush.verticalGradient(listOf(l.color.copy(alpha = 0.35f), Color.Transparent), 0f, h))
                            }
                            // one path, not points: a polygon of points goes to the GPU as hundreds of separate round-capped pieces
                            line.rewind()
                            for (r in 0 until n) {
                                val from = runs[r * 2]
                                val count = runs[r * 2 + 1]
                                if (count < 2) continue
                                line.moveTo(xy[from * 2], xy[from * 2 + 1])
                                for (i in from + 1 until from + count) line.lineTo(xy[i * 2], xy[i * 2 + 1])
                            }
                            drawPath(line, l.color, style = lineStroke)
                        }
                    }
                }
                clipRect(0f, 0f, w, h) {
                    translate(left = -kotlin.math.round((edge - recordedAt) * px)) { drawImage(picture, filterQuality = androidx.compose.ui.graphics.FilterQuality.None) }
                }
                lines.defs.forEach { l ->
                    val v = l.ring.at(edge, lines.breakMs)
                    if (!v.isNaN()) drawCircle(l.color, 5f, Offset(w, yOf(v)))
                }
            }
        }
    }
    Spacer(modifier.then(draw))
}

@Composable
private fun LiveLegend(lines: ChartLines, format: String) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        lines.defs.forEach { l ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(l.color))
                Spacer(Modifier.width(6.dp))
                val text by rememberDigits {
                    lines.samples.intValue
                    l.ring.last.takeUnless { it.isNaN() }?.let { format.format(it) } ?: "—"
                }
                T("${l.name} $text", LocalCar.current.secondary, C.Sub, maxLines = 1)
            }
        }
    }
}

/** Card header: the icon in a tinted circle, the title, an optional line under it, and the "i". */
@Composable
fun CardHeader(icon: ImageVector, title: String, tint: Color, info: String = title, sub: String? = null) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FitText(title, LocalCar.current.label, Modifier.fillMaxWidth(), weight = FontWeight.SemiBold)
            if (sub != null) FitText(sub, LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub)
        }
        InfoButton(title, info)
    }
}

/**
 * Arc gauge with its value centred inside; text is sized from the space actually available,
 * so it never overlaps the arc no matter how small the cell is.
 */
@Composable
fun RingGauge(
    fraction: Float,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    unitColor: Color = C.Sub,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = if (maxWidth < maxHeight) maxWidth else maxHeight
        Box(Modifier.size(d), contentAlignment = Alignment.Center) {
            ArcGauge(fraction.coerceIn(0f, 1f) * 100f, 100f, emptyList(), Modifier.fillMaxSize())
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = d * 0.08f),
            ) {
                val digits = value.length.coerceAtLeast(2)
                // inner free width is ~0.62·d; a digit is ~0.58 em wide
                val byWidth = d.value * 0.62f / (digits * 0.58f)
                val size = minOf(byWidth, d.value * 0.26f, 60f)
                T(value, size.sp, weight = FontWeight.Bold, maxLines = 1)
                // a long unit («л/ч · стоянка») inside the ring: as large as its width there lets it be
                FitText(
                    unit, maxOf(minOf(LocalCar.current.secondary.value, d.value * 0.11f), size * 0.32f).sp, Modifier.widthIn(max = d * 0.66f), unitColor,
                    min = (d.value * 0.06f).coerceAtMost(LocalCar.current.secondary.value * 0.7f).sp,
                )
            }
        }
    }
}

/**
 * Demo on: a plate in the header that says so plainly (the made-up drive looks like a real one), a tap turns it off
 * and the app goes back to looking for the car.
 */
@Composable
fun DemoPill(short: Boolean = false, modifier: Modifier = Modifier) {
    val on by kg.forester.sg.data.Repo.demoOn.collectAsState()
    if (!on) return
    var ask by remember { mutableStateOf(false) }
    // one tap only asks: the plate is in the header, where a stray touch would end the drive that is on the screen
    if (ask) ConfirmDialog(
        "Выключить демо?", "Демо-данные пропадут с экранов, приложение снова будет искать машину.",
        "Выключить", { kg.forester.sg.data.Repo.settings.demo = false }, { ask = false }, danger = false,
    )
    Row(
        modifier.padding(horizontal = 6.dp).heightIn(min = LocalCar.current.tap).clip(RoundedCornerShape(10.dp)).background(C.Violet.copy(alpha = 0.22f))
            .border(1.dp, C.Violet, RoundedCornerShape(10.dp)).clickable { ask = true }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.MonitorPlay, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        T(if (short) "Демо" else "Демо: не ваша машина", LocalCar.current.secondary, C.Text, FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.X, "Выключить демо", tint = C.Sub, modifier = Modifier.size(14.dp))
    }
}

/**
 * GPS in the header: a satellite, green with a fresh fix, orange while searching, grey when location is off or not
 * allowed; in brackets the satellites used in the fix (seen ones while there is none yet). A tap tells why.
 */
@Composable
fun GpsIcon(size: Dp) {
    // each fix is a new object: this recomposes with what is shown of it, not with every fix
    val fixState = kg.forester.sg.data.Repo.gps.collectAsState()
    val sats by kg.forester.sg.data.Repo.gnssSats.collectAsState()
    val status by kg.forester.sg.data.Repo.gpsStatus.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); now = System.currentTimeMillis() } }
    var ask by remember { mutableStateOf(false) }
    val fresh by remember { androidx.compose.runtime.derivedStateOf { fixState.value?.let { now - it.timeMs < 5000 } == true } }
    val fixSats by remember { androidx.compose.runtime.derivedStateOf { fixState.value?.satellites } }
    val off = !fresh && (status.startsWith("нет разрешения") || status.startsWith("геолокация выключена"))
    // the count from GnssStatus; a fix from a unit that doesn't report satellites still shows its own number
    val used = if (sats.first > 0) sats.first else fixSats?.takeIf { fresh } ?: 0
    val tint = if (off) C.Sub else if (fresh) C.Green else C.Orange
    // a slot of its own width: «(9)» to «(10)» or «(12/24)» doesn't move the buttons beside it
    Row(
        Modifier.width(size + (size.value * 0.7f * 0.62f * 8).dp).heightIn(min = LocalCar.current.tap).clip(RoundedCornerShape(10.dp))
            .clickable { ask = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Satellite, "Спутники GPS", tint = tint, modifier = Modifier.size(size))
        if (!off) T(" (${if (fresh || sats.second == 0) used else "${used}/${sats.second}"})", (size.value * 0.7f).sp, tint, maxLines = 1)
    }
    if (ask) {
        CarAlertDialog(
            onDismissRequest = { ask = false },
            containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
            icon = { Icon(Lucide.Satellite, null, tint = C.Violet, modifier = Modifier.size(26.dp)) },
            title = { T("Спутники GPS", 19.sp, weight = FontWeight.SemiBold) },
            text = {
                T(
                    when {
                        fresh -> "Есть позиция: в решении $used" + (if (sats.second > 0) " из ${sats.second} видимых" else "") +
                            fixState.value?.accuracyM?.let { ", точность около ${it.toInt()} м" }.orEmpty()
                        off -> status
                        else -> "Позиции пока нет: " + (if (sats.second > 0) "видно ${sats.second}, в решении $used. " else "") +
                            "Нужно 4 спутника и больше. Под крышей и в гараже спутников не видно, антенне нужен открытый кусок неба"
                    }, 15.sp,
                )
            },
            confirmButton = { androidx.compose.material3.TextButton({ ask = false }) { T("OK", 15.sp, C.Violet) } },
        )
    }
}

/**
 * The connection in the header: how the app talks to the car (USB cable, Wi-Fi or Bluetooth ELM327, demo),
 * green when the ECU answers, orange (struck through) while it does not, grey when let go for another app. A tap
 * lets the adapter go for another diagnostic app (BTSSM…) and takes it back.
 */
@Composable
fun LinkIcon(size: Dp) {
    val link by kg.forester.sg.data.Repo.link.collectAsState()
    val found by kg.forester.sg.data.Repo.adapterFound.collectAsState()
    val released by kg.forester.sg.data.Repo.adapterReleased.collectAsState()
    var ask by remember { mutableStateOf(false) }
    val ok = link == kg.forester.sg.data.LinkState.CONNECTED
    val s = kg.forester.sg.data.Repo.settings
    val via = when {
        s.demo -> "demo"
        found.startsWith("Wi-Fi") -> "wifi"
        found.startsWith("Bluetooth") -> "bt"
        found.startsWith("USB") -> "usb"
        s.adapter == "elm_wifi" -> "wifi"
        s.adapter == "elm_bt" || s.adapter == "mt_bt" -> "bt"
        else -> "usb"
    }
    val icon = when (via) {
        "demo" -> Lucide.MonitorPlay
        "wifi" -> if (ok) Lucide.Wifi else Lucide.WifiOff
        "bt" -> if (ok) Lucide.Bluetooth else Lucide.BluetoothOff
        else -> if (ok) Lucide.Usb else Lucide.Unplug
    }
    IconTap(
        if (released) Lucide.Unplug else icon, "Связь с ЭБУ", { if (via != "demo") ask = true },
        tint = if (released) C.Sub else if (via == "demo") C.Violet else if (ok) C.Green else C.Orange, iconSize = size,
    )
    if (ask) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val message by kg.forester.sg.data.Repo.linkMessage.collectAsState()
        CarAlertDialog(
            onDismissRequest = { ask = false },
            containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
            icon = { Icon(if (released) Lucide.Unplug else icon, null, tint = C.Violet, modifier = Modifier.size(26.dp)) },
            title = { T("Связь с ЭБУ", 19.sp, weight = FontWeight.SemiBold) },
            text = {
                T(
                    if (released) "Адаптер сейчас свободен для другой программы. Закройте её (BTSSM и т. п.) и подключитесь снова."
                    else (if (ok) "Подключено: $found. " else "$message. ") + "\n\nНужен адаптер для BTSSM или другой программы диагностики? " +
                        "Отпустите его: приложение перестанет опрашивать ЭБУ и освободит кабель или Bluetooth, пока вы не нажмёте «Подключиться» " +
                        "(или до следующего запуска приложения). Это же есть в уведомлении.",
                    14.sp,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    ask = false
                    kg.forester.sg.service.EcuService.start(ctx, if (released) kg.forester.sg.service.EcuService.ACTION_RECONNECT
                    else kg.forester.sg.service.EcuService.ACTION_RELEASE)
                }) { Text(if (released) "Подключиться" else "Отпустить адаптер", color = C.Violet) }
            },
            dismissButton = { androidx.compose.material3.TextButton({ ask = false }) { Text("Закрыть", color = C.Sub) } },
        )
    }
}

/**
 * [content] as tall as it wants at the width given, shrunk as a whole (never grown) when that is more than the height
 * there is: a block of big text that a large UI scale would otherwise cut at the bottom. Centred vertically, at the start.
 */
@Composable
fun ShrinkToFit(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier.clipToBounds()) { measurables, c ->
        val p = measurables.firstOrNull()?.measure(c.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            ?: return@Layout layout(c.minWidth, c.minHeight) {}
        val k = if (c.hasBoundedHeight && p.height > c.maxHeight) c.maxHeight.toFloat() / p.height else 1f
        val h = if (c.hasBoundedHeight) c.maxHeight.coerceAtLeast(c.minHeight) else (p.height * k).toInt()
        val w = p.width.coerceIn(c.minWidth, c.maxWidth)
        layout(w, h) {
            p.placeWithLayer(0, ((h - p.height * k) / 2).toInt()) {
                scaleX = k; scaleY = k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
        }
    }
}


// ---- the common kit of buttons, rows and dialogs
//
// Buttons: [Chip] (a word, wrapping choice), [ActionButton] (the full-width main action of a panel), [SmallAction] (a small
// word with an icon in a panel's corner), [IconTap] (an icon alone), [Pill] (the player's rounded button).
// Rows: [SwitchRow]. Dialogs: [CarSheet] for a form, [ConfirmDialog] for a question.

@Composable
fun ActionButton(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = LocalMinTap.current).clip(RoundedCornerShape(10.dp))
            .background(
                if (!enabled) androidx.compose.ui.graphics.SolidColor(C.idleFill(C.Border))
                else if (danger) androidx.compose.ui.graphics.SolidColor(if (C.palette.dangerText != null) C.Red else C.Red.copy(alpha = 0.8f)) else C.Accent
            )
            .tapClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        // one line, whatever the language or the theme's font: smaller first, down to the secondary size
        val floor = LocalTextFloor.current
        FitText(text, if (floor != null) parkedSp(15f, floor).sp else 15.sp, Modifier, if (!enabled) (if (C.palette.idleFill != null) C.Sub else C.Hair) else if (danger) C.palette.dangerText ?: Color.White else C.OnAccent, FontWeight.SemiBold, min = LocalCar.current.secondary)
    }
}

/** A small tappable word with an icon, for the corners of the panels. */
@Composable
fun SmallAction(
    text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, color: Color = C.Violet, modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.heightIn(min = parkedTap()).clip(RoundedCornerShape(8.dp)).background(C.CardHi).tapClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        T(text, 13.sp, C.Text, maxLines = 1)
    }
}

@Composable
internal fun Pill(text: String, icon: ImageVector? = null, selected: Boolean = false, height: Dp = 44.dp, onClick: () -> Unit) {
    // its own height on the player, the parked target in the dialogs
    val shownHeight = maxOf(height, parkedTap())
    val shape = RoundedCornerShape(shownHeight / 2)
    Row(
        Modifier.height(shownHeight).clip(shape).background(if (selected) C.activeFill(C.Violet.copy(alpha = 0.22f)) else C.idleFill(C.Hair.copy(alpha = 0.06f)))
            .border(1.dp, if (selected) C.activeEdge(C.Violet) else C.idleEdge(Color.Transparent), shape)
            .tapClickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (selected) C.activeText(C.Violet) else C.Sub, modifier = Modifier.size(18.dp)); if (text.isNotEmpty()) Spacer(Modifier.width(8.dp)) }
        if (text.isNotEmpty()) T(text, 14.sp, if (selected) C.activeText(C.Text) else C.Sub, FontWeight.Medium, maxLines = 1)
    }
}

/**
 * An icon alone as a button: the area that takes the tap is the car's tap token ([CarTokens.tap], 64 dp and 12 mm at
 * least), the icon at [iconSize]. The delete crosses and the header's icons were 28 to 34 dp.
 */
@Composable
fun IconTap(
    icon: ImageVector, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.Sub,
    iconSize: Dp = 22.dp, enabled: Boolean = true, background: Color? = null,
    side: Dp = maxOf(LocalCar.current.tap, LocalMinTap.current),
) {
    val p = C.palette
    val own = p.iconFill.takeIf { background == null }
    Box(
        modifier.size(side).clip(if (p.iconRound) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(10.dp))
            .then(if (background != null) Modifier.background(background) else Modifier)
            // small buttons in the middle of the tap area, as the theme has them
            .then(if (own != null) Modifier.drawWithCache {
                val d = (iconSize + 16.dp).toPx().coerceAtMost(size.minDimension)
                val at = androidx.compose.ui.geometry.Offset((size.width - d) / 2, (size.height - d) / 2)
                val corner = if (p.iconRound) d / 2 else 12.dp.toPx()
                val edge = androidx.compose.ui.graphics.drawscope.Stroke(1f)
                onDrawBehind {
                    drawRoundRect(own, at, androidx.compose.ui.geometry.Size(d, d), androidx.compose.ui.geometry.CornerRadius(corner))
                    drawRoundRect(p.border, at, androidx.compose.ui.geometry.Size(d, d), androidx.compose.ui.geometry.CornerRadius(corner), style = edge)
                }
            } else Modifier)
            .tapClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (enabled) tint else C.Border, modifier = Modifier.size(iconSize)) }
}

/** The longest a hint under a setting is on its row. */
const val HINT_MAX = 60

/**
 * A hint for a row of settings: what fits one line of [HINT_MAX] characters, and the whole text for the «i» when there
 * is more (null when the hint fits). The line ends where its first sentence or clause does, else at a word.
 */
fun hintLine(text: String, max: Int = HINT_MAX): Pair<String, String?> {
    val t = text.trim()
    if (t.length <= max) return t to null
    val head = t.substring(0, max)
    val sentence = head.indexOfFirst { it == '.' || it == ':' || it == ';' }
    val clause = head.indexOfLast { it == ',' || it == '(' }
    val stop = if (sentence >= 12) sentence else if (clause >= max / 2) clause else -1
    val line = if (stop > 0) head.substring(0, stop).trimEnd() else head.substring(0, head.lastIndexOf(' ').let { if (it < max / 2) max - 1 else it }).trimEnd()
    return line.trimEnd('.', ',', ':', ';', '(', ' ') + "…" to t
}

/**
 * What opens behind a hint's «i»: the whole text when it is longer than [HINT_MAX], and also when the one line [overflow]s
 * the room it has (the row fits about 40 characters at 10" and 24 at 7"), so no text is ever out of reach.
 */
fun hintInfo(text: String, overflow: Boolean): String? = hintLine(text).second ?: text.trim().takeIf { overflow && it.isNotEmpty() }

/** One line of hint that tells when it doesn't fit its room, for the «i». */
@Composable
private fun HintLine(line: String, onOverflow: () -> Unit, modifier: Modifier = Modifier) {
    val sp = LocalCar.current.secondary.value.coerceAtLeast(11f)
    Text(
        line, modifier = modifier, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) onOverflow() },
        style = TextStyle(fontSize = sp.sp, color = C.Sub),
    )
}

/**
 * The one switch row of the settings: title, a hint of one line (the rest, and whatever doesn't fit the line, behind
 * «i»), the switch; the whole row takes the tap. [status] is what is going on now (a download, a date, a failure): it is
 * not an explanation, so it stays on the row, whatever its length. [statusColor]: the accent can be any hue, green too, so
 * it isn't used for that: quiet by default, orange for a failure, green only for what is really done.
 */
@Composable
fun SwitchRow(title: String, sub: String, on: Boolean, status: String? = null, statusColor: Color = C.Sub, onChange: (Boolean) -> Unit) {
    val sub = kg.forester.sg.data.tr(sub)
    val (line, _) = remember(sub) { hintLine(sub) }
    var overflow by remember(sub) { mutableStateOf(false) }
    val full = hintInfo(sub, overflow)
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalMinTap.current).clip(RoundedCornerShape(10.dp))
            .tapClickable(role = androidx.compose.ui.semantics.Role.Switch, afterClick = true) { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(title, 15.sp)
            if (line.isNotEmpty()) HintLine(line, { overflow = true })
            if (!status.isNullOrEmpty()) T(status, LocalCar.current.secondary.value.coerceAtLeast(11f).sp, statusColor)
        }
        if (full != null) IconTap(Lucide.Info, "Подробнее", { open = true }, tint = C.Sub, iconSize = 20.dp)
        Spacer(Modifier.width(4.dp))
        androidx.compose.material3.Switch(on, null, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Purple))
    }
    if (open && full != null) CarAlertDialog(
        onDismissRequest = { open = false },
        containerColor = C.CardHi,
        title = { T(title, 18.sp, weight = FontWeight.SemiBold) },
        text = { T(full, 15.sp) },
        confirmButton = { androidx.compose.material3.TextButton({ open = false }) { T("Понятно", 15.sp, C.Violet) } },
    )
}

/** A question before something that can't be taken back: [danger] paints the answer red. */
@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true,
) {
    CarAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.CardHi,
        title = { T(title, 18.sp, weight = FontWeight.SemiBold) },
        text = { T(text, 15.sp) },
        confirmButton = {
            androidx.compose.material3.TextButton({ onDismiss(); onConfirm() }) { T(confirm, 16.sp, if (danger) C.Red else C.Violet) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { T("Отмена", 16.sp, C.Text) } },
    )
}

/**
 * The frame of a form in a dialog: [width] wide, [maxHeight] tall at most and never taller than the window, on the card
 * colour. [head] (the title and its buttons) stays in place and all of [content] below it scrolls as one, with its cue;
 * so does all of it with [scroll]. With neither, the content does its own scrolling: its list takes what is left.
 */
@Composable
fun CarSheet(
    onClose: () -> Unit, width: Dp, maxHeight: Dp = 600.dp, scroll: Boolean = false,
    head: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CarDialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(width).heightIn(max = minOf(maxHeight, (canvasScreen().screenHeightDp * 0.94f).dp)).clip(RoundedCornerShape(16.dp)).background(C.Sheet)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            head?.invoke(this)
            if (head != null || scroll) Column(Modifier.weight(1f, fill = false).cueScroll(), content = content)
            else content()
        }
    }
}


/** The link has been gone for [kg.forester.sg.data.LINK_LOSS_MS]: the screens say «нет связи» (the values are wiped by then, see LinkLoss). */
@Composable
fun rememberNoLink(): Boolean {
    // one clock for the whole outage, whatever states the link passes through (it used to restart at every one of them)
    val watch = remember { kg.forester.sg.data.LinkLoss() }
    var lost by remember { mutableStateOf(false) }
    LaunchedEffect(watch) {
        while (true) {
            lost = watch.lost(kg.forester.sg.data.Repo.link.value, System.currentTimeMillis())
            kotlinx.coroutines.delay(250)
        }
    }
    return lost
}

/** A long press that buzzes: the finger knows the editor is opening, with no screen to look at. */
@Composable
fun Modifier.onLongPressBuzz(onLong: () -> Unit): Modifier {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onLong)
    return pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            latest.value()
        })
    }
}


/** A hint under a setting: one line of [HINT_MAX] characters, the rest of it (and what doesn't fit the line) behind «i». */
@Composable
fun SettingHint(text: String, modifier: Modifier = Modifier) {
    val text = kg.forester.sg.data.tr(text)
    val (line, _) = remember(text) { hintLine(text) }
    var overflow by remember(text) { mutableStateOf(false) }
    val full = hintInfo(text, overflow)
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HintLine(line, { overflow = true }, Modifier.weight(1f, fill = false))
        if (full != null) IconTap(Lucide.Info, "Подробнее", { open = true }, iconSize = 18.dp, side = LocalMinTap.current)
    }
    if (open && full != null) CarAlertDialog(
        onDismissRequest = { open = false },
        containerColor = C.CardHi,
        text = { T(full, 15.sp) },
        confirmButton = { androidx.compose.material3.TextButton({ open = false }) { T("Понятно", 15.sp, C.Violet) } },
    )
}
