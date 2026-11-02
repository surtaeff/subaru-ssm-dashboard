package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kg.forester.sg.service.DynoCalc
import kg.forester.sg.service.DynoPoint
import kotlin.math.abs

private const val MARGIN_L = 44f
private const val MARGIN_R = 44f
private const val MARGIN_B = 26f
private const val MARGIN_T = 10f

private fun tick(v: Float) = if (abs(v - v.toInt()) < 0.01f) "${v.toInt()}" else "%.1f".format(v)

/**
 * The power (left scale) and torque (right scale) of a pull over the rpm, on a grid of round numbers; [ref], the pull
 * compared with, on the same scales in the same colours, dashed and paler, the difference in % under them. [percent]:
 * each curve to its own peak, for where the curve moved and not how high it is. Circles: the torque's peak and where
 * the power starts to fall.
 */
@Composable
fun DynoChart(cur: List<DynoPoint>, ref: List<DynoPoint>?, percent: Boolean, modifier: Modifier) {
    val a = remember(cur, percent) { if (percent) DynoCalc.percentOfPeak(cur) else cur }
    val b = remember(ref, percent) { ref?.let { if (percent) DynoCalc.percentOfPeak(it) else it } }
    val diff = remember(a, b) { b?.let { DynoCalc.diffPct(a, it) } ?: emptyList() }
    val all = remember(a, b) { a + (b ?: emptyList()) }
    if (all.isEmpty()) return
    val axes = remember(all) { DynoCalc.axes(all.maxOf { it.hp }, all.maxOf { it.nm }) }
    val ticks = remember(all) { DynoCalc.rpmTicks(all.minOf { it.rpm }, all.maxOf { it.rpm }) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
    val violet = C.Violet
    val blue = C.Blue
    Canvas(modifier) {
        val w = size.width - MARGIN_L - MARGIN_R
        val plotBottom = size.height - MARGIN_B
        // the bottom 20 % for the difference, when there is one
        val top = plotBottom - MARGIN_T
        val stripH = if (b != null) top * 0.2f else 0f
        val bottom = plotBottom - stripH
        val h = bottom - MARGIN_T
        val r0 = ticks.first().toFloat()
        val r1 = ticks.last().toFloat()
        fun x(r: Int) = MARGIN_L + (r - r0) / (r1 - r0) * w
        val hpTop = axes.lines * axes.hpStep
        val nmTop = axes.lines * axes.nmStep
        fun yHp(v: Float) = bottom - v / hpTop * h
        fun yNm(v: Float) = bottom - v / nmTop * h

        for (r in ticks) {
            val thick = r % 1000 == 0
            drawLine(C.Border, Offset(x(r), MARGIN_T), Offset(x(r), plotBottom), if (thick) 2f else 1f)
            if (thick) label("$r", x(r) - 22f, size.height - 5f, C.Sub, 20f)
        }
        for (i in 0..axes.lines) {
            val y = bottom - h * i / axes.lines
            drawLine(C.Border, Offset(MARGIN_L, y), Offset(MARGIN_L + w, y), if (i == 0) 2f else 1f)
            // the labels sit on their lines, the top one under its line's level so it isn't cut
            val ty = (y + 7f).coerceAtLeast(18f)
            label(tick(axes.hpStep * i), MARGIN_L - 5f, ty, violet, 20f, right = true)
            label(tick(axes.nmStep * i), size.width - MARGIN_R + 5f, ty, blue, 20f)
        }

        fun line(c: List<DynoPoint>, y: (DynoPoint) -> Float, color: Color, width: Float, dashed: Boolean) {
            if (c.size < 2) return
            val path = Path()
            c.forEachIndexed { i, q -> if (i == 0) path.moveTo(x(q.rpm), y(q)) else path.lineTo(x(q.rpm), y(q)) }
            drawPath(path, color, style = Stroke(width, pathEffect = if (dashed) dash else null))
        }
        fun marks(c: List<DynoPoint>, hp: Color, nm: Color) {
            c.maxByOrNull { it.nm }?.let { drawCircle(nm, 7f, Offset(x(it.rpm), yNm(it.nm))) }
            DynoCalc.dropAfter(c)?.let { r -> c.firstOrNull { it.rpm == r } }?.let { drawCircle(hp, 7f, Offset(x(it.rpm), yHp(it.hp))) }
        }
        if (b != null) {
            line(b, { yHp(it.hp) }, violet.copy(alpha = 0.45f), 4f, true)
            line(b, { yNm(it.nm) }, blue.copy(alpha = 0.45f), 3f, true)
            marks(b, violet.copy(alpha = 0.45f), blue.copy(alpha = 0.45f))
        }
        line(a, { yHp(it.hp) }, violet, 4f, false)
        line(a, { yNm(it.nm) }, blue, 3f, false)
        marks(a, violet, blue)
        if (b != null) differenceStrip(diff, ::x, bottom, plotBottom, w / ((r1 - r0) / 100f))
    }
}

/** Bars of the difference in %, up in green for more than the pull compared with, down in red for less. */
private fun DrawScope.differenceStrip(diff: List<Pair<Int, Float>>, x: (Int) -> Float, top: Float, bottom: Float, per100: Float) {
    val mid = (top + bottom) / 2
    drawLine(C.Border, Offset(MARGIN_L, mid), Offset(size.width - MARGIN_R, mid), 2f)
    if (diff.isEmpty()) return
    val most = maxOf(2f, diff.maxOf { abs(it.second) })
    val half = (bottom - top) / 2 - 3f
    val rpmStep = diff.zipWithNext { p, q -> q.first - p.first }.minOrNull() ?: 100
    val bar = (per100 * rpmStep / 100f * 0.7f).coerceAtLeast(2f)
    for ((r, d) in diff) {
        val len = d / most * half
        val color = if (d >= 0f) C.Green else C.Red
        drawRect(color, Offset(x(r) - bar / 2, if (d >= 0f) mid - len else mid), Size(bar, abs(len)))
    }
    label("±%.0f %%".format(most), MARGIN_L - 5f, mid + 7f, C.Sub, 18f, right = true)
}
