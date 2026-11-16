package kg.forester.sg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A round digital meter after the JDM clocks: a dark bezel, black glass, the value in big glowing segment digits
 * over their unlit «8»s, the units, the value's name and a small [DialStyle.caption] under them. No needle, no scale:
 * the number is all, so it reads from across the cabin. The digits keep their size from «9» to «999» (sized for the
 * widest number of the range) and turn red over the value's warning line.
 */
@Composable
internal fun LcdDial(
    value: State<Float?>, min: Float, max: Float, unit: String, redFrom: Float?, modifier: Modifier, style: DialStyle,
    format: String, name: String?, warn: WarnRule?,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val face = remember(style) {
            Modifier.drawWithCache {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                val glass = r * 0.86f
                val bezel = Brush.linearGradient(
                    listOf(lighten(style.rim, 0.35f), style.rim, Color(0xFF050608), style.rim),
                    Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r),
                )
                val faceBrush = Brush.radialGradient(style.face, c, glass)
                val halo = Brush.radialGradient(listOf(style.value.copy(alpha = 0.10f), Color.Transparent), c, glass * 0.8f)
                onDrawBehind {
                    drawCircle(bezel, r, c)
                    drawCircle(Color.Black.copy(alpha = 0.55f), r * 0.93f, c, style = Stroke(r * 0.02f))
                    drawCircle(faceBrush, glass, c)
                    drawCircle(halo, glass, c)
                    drawCircle(Color.Black, glass, c, style = Stroke(r * 0.025f))
                    // no glare of its own: the glass is the driver's choice («Эффект стекла», Gauges.glassOver)
                }
            }
        }
        Box(Modifier.size(side).then(face), contentAlignment = Alignment.Center) {
            val big = side >= 120.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (!style.hideValue) LcdDigits(value, min, max, format, style, warn, redFrom, side)
                Spacer(Modifier.height(side * 0.015f))
                if (unit.isNotEmpty()) LcdLine(unit, side * 0.075f, style.unit, FontWeight.SemiBold, glow = style.unit)
                if (big && !name.isNullOrEmpty()) {
                    Spacer(Modifier.height(side * 0.035f))
                    LcdLine(name, side * 0.085f, Color(0xFFF2F4F8), FontWeight.Bold)
                }
                if (side >= 160.dp) style.caption?.let { LcdLine(it, side * 0.045f, Color(0xFFA3A8B3), FontWeight.Normal) }
            }
        }
    }
}

@Composable
private fun LcdDigits(
    value: State<Float?>, min: Float, max: Float, format: String, style: DialStyle, warn: WarnRule?, redFrom: Float?, side: Dp,
) {
    val text by rememberDigits { value.value?.let { format.format(it) } ?: "--" }
    val v = value.value
    val hot = (warn?.hits(v) == true) || (redFrom != null && v != null && v >= redFrom)
    val lit = if (hot) style.red else style.value
    val widest = remember(format, min, max) {
        val hi = format.format(max)
        val lo = format.format(min)
        if (lo.length > hi.length) lo else hi
    }
    val digits = maxOf(text.length, widest.length).coerceAtLeast(2)
    val d = LocalDensity.current
    // the glass is 0.86 of the dial across; the digits take at most 0.58 of it, lit «8»s and the glow included,
    // with DSEG's advance taken at a full em so the widest number never reaches the edge
    val sp = with(d) { minOf(side * 0.26f, side * 0.58f / digits).toSp() }
    val blur = with(d) { (side * 0.045f).toPx() }
    // a "1" is as wide as an "8" in DSEG, so the unlit digits line up with the lit ones
    val ghost = text.map { if (it.isDigit() || it == '-') '8' else it }.joinToString("").padStart(digits, '8')
    Box(contentAlignment = Alignment.CenterEnd) {
        Text(ghost, color = style.tickMinor, fontSize = sp, fontFamily = SegmentFont, maxLines = 1)
        Text(
            text.padStart(digits, ' '), maxLines = 1,
            style = TextStyle(color = lit, fontSize = sp, fontFamily = SegmentFont, shadow = Shadow(lit.copy(alpha = 0.75f), Offset.Zero, blur)),
        )
    }
}

@Composable
private fun LcdLine(text: String, size: Dp, color: Color, weight: FontWeight, glow: Color? = null) {
    val d = LocalDensity.current
    val sp = with(d) { size.toSp() }
    val blur = with(d) { (size * 0.35f).toPx() }
    Text(
        text, maxLines = 1,
        style = TextStyle(
            color = color, fontSize = sp, fontWeight = weight, letterSpacing = 0.04.sp * sp.value,
            shadow = glow?.let { Shadow(it.copy(alpha = 0.6f), Offset.Zero, blur) },
        ),
    )
}

private fun lighten(c: Color, f: Float) = Color(c.red + (1f - c.red) * f, c.green + (1f - c.green) * f, c.blue + (1f - c.blue) * f, c.alpha)
