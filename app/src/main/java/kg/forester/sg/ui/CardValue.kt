package kg.forester.sg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A single number a card can show either in its normal look or as a needle gauge. */
data class CardValue(
    val icon: ImageVector,
    val title: String,
    val value: Float?,
    val min: Float,
    val max: Float,
    val unit: String,
    val format: String,
    /** Info text key for the "i" button. */
    val info: String,
    /** Start of the red zone on the gauge, if the value has a "too high". */
    val redFrom: Float? = null,
    /** The value as a State, for a caller that does not recompose with each reading; then [value] is not used. */
    val live: androidx.compose.runtime.State<Float?>? = null,
    /** All the lines the value warns by; [redFrom] is only the top one, drawn on the dial. */
    val warnRule: WarnRule = WarnRule(above = redFrom),
)

/** Round scale bounds and a major step giving 5–8 numbered ticks. */
internal fun niceScale(min: Float, max: Float): Triple<Float, Float, Float> {
    val raw = (max - min) / 6f
    val mag = 10f.pow(floor(log10(raw)))
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
    return Triple(floor(min / step) * step, ceil(max / step) * step, step)
}

/** [m] as a needle gauge in [style], as large as the card allows, with the card title and "i" on top; no card frame. */
@Composable
fun NeedleCard(m: CardValue, style: DialStyle, modifier: Modifier) {
    val (lo, hi, step) = niceScale(m.min, m.max)
    val div = if (hi >= 1000f) 1000f else 1f // rpm: numbers in thousands like the tachometer
    val value = m.live ?: androidx.compose.runtime.rememberUpdatedState(m.value)
    // the plain card's lines, all three: from the reading itself, as it comes
    val id = m.info.substringAfter('.')
    val gear = if (style.lens && id == "speed") rememberLensGear() else null
    val warn by remember(m.warnRule, value) { androidx.compose.runtime.derivedStateOf { m.warnRule.hits(value.value) } }
    androidx.compose.foundation.layout.Column(
        modifier.then(if (warn) Modifier.border(3.dp, C.Orange, Rounded) else Modifier).padding(4.dp),
    ) {
        CardTitle(m.icon, m.title, m.info, warn = warn)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // a design with built-in indicators lays itself out on the whole card; the other dials are a square
            val whole = style.design != null && style.image == null
            Box(Modifier.padding(2.dp).then(if (whole) Modifier.fillMaxSize() else Modifier.aspectRatio(1f, matchHeightConstraintsFirst = maxHeight < maxWidth))) {
                val range = tripRangeState(id, value)
                Dial(value, hi, step, div, m.unit, if (div > 1f) "x1000" else null, m.redFrom, Modifier.fillMaxSize(), style, min = lo,
                    format = m.format, range = { range.value }, name = m.title, warn = m.warnRule, gear = gear)
            }
        }
    }
}

/** [plain] unless the card was switched to a gauge design ([style] = its id). */
@Composable
fun ValueCard(m: CardValue, style: String, modifier: Modifier, plain: @Composable () -> Unit) {
    if (style.isEmpty()) plain() else NeedleCard(m, dialStyle(style), modifier)
}
