package kg.forester.sg.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.CarKind
import kg.forester.sg.data.Repo

/** The outline car from the side, nose to the right, in a box at ([x0], [y0]) [cw] wide and 0.36 of that tall (wheels a bit below). */
fun DrawScope.outlineCarSide(x0: Float, y0: Float, cw: Float, glow: Float = 0f) {
    val ch = cw * 0.36f
    fun p(x: Float, y: Float) = Offset(x0 + x * cw, y0 + y * ch)
    val r = 0.078f * cw
    val wheelY = 0.80f
    val rear = 0.21f
    val front = 0.79f
    val body = Path().apply {
        moveTo(p(0.03f, 0.80f).x, p(0.03f, 0.80f).y)
        lineTo(p(0.015f, 0.58f).x, p(0.015f, 0.58f).y)
        quadraticBezierTo(p(0.02f, 0.45f).x, p(0.02f, 0.45f).y, p(0.09f, 0.43f).x, p(0.09f, 0.43f).y)
        lineTo(p(0.22f, 0.39f).x, p(0.22f, 0.39f).y)
        quadraticBezierTo(p(0.30f, 0.14f).x, p(0.30f, 0.14f).y, p(0.40f, 0.11f).x, p(0.40f, 0.11f).y)
        lineTo(p(0.56f, 0.10f).x, p(0.56f, 0.10f).y)
        quadraticBezierTo(p(0.63f, 0.12f).x, p(0.63f, 0.12f).y, p(0.73f, 0.37f).x, p(0.73f, 0.37f).y)
        lineTo(p(0.90f, 0.44f).x, p(0.90f, 0.44f).y)
        quadraticBezierTo(p(0.985f, 0.47f).x, p(0.985f, 0.47f).y, p(0.985f, 0.62f).x, p(0.985f, 0.62f).y)
        lineTo(p(0.975f, 0.80f).x, p(0.975f, 0.80f).y)
        lineTo(p(front, wheelY).x + r * 1.18f, p(front, wheelY).y)
        arcTo(Rect(p(front, wheelY), r * 1.18f), 0f, -180f, false)
        lineTo(p(rear, wheelY).x + r * 1.18f, p(rear, wheelY).y)
        arcTo(Rect(p(rear, wheelY), r * 1.18f), 0f, -180f, false)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(C.Violet.copy(alpha = 0.30f), C.Purple.copy(alpha = 0.06f)), y0, y0 + ch))
    drawPath(body, C.Violet.copy(alpha = 0.55f + glow * 0.4f), style = Stroke(2.2.dp.toPx()))
    val glass = Path().apply {
        moveTo(p(0.245f, 0.40f).x, p(0.245f, 0.40f).y)
        quadraticBezierTo(p(0.31f, 0.18f).x, p(0.31f, 0.18f).y, p(0.40f, 0.16f).x, p(0.40f, 0.16f).y)
        lineTo(p(0.555f, 0.155f).x, p(0.555f, 0.155f).y)
        quadraticBezierTo(p(0.61f, 0.17f).x, p(0.61f, 0.17f).y, p(0.69f, 0.38f).x, p(0.69f, 0.38f).y)
        close()
    }
    drawPath(glass, Color.White.copy(alpha = 0.07f))
    drawPath(glass, Color.White.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
    drawLine(Color.White.copy(alpha = 0.35f), p(0.47f, 0.155f), p(0.47f, 0.39f), 1.5.dp.toPx())
    // the waist line and the lights
    drawLine(C.Violet.copy(alpha = 0.35f), p(0.06f, 0.52f), p(0.95f, 0.55f), 1.dp.toPx())
    drawLine(Color(0xFFFF3B4E).copy(alpha = 0.85f), p(0.022f, 0.50f), p(0.022f, 0.58f), 3.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.8f), p(0.965f, 0.52f), p(0.98f, 0.58f), 3.dp.toPx())
    for (cx in listOf(rear, front)) {
        val c = p(cx, wheelY)
        drawCircle(Color(0xFF0B0E18), r, c)
        drawCircle(C.Sub.copy(alpha = 0.8f), r, c, style = Stroke(2.dp.toPx()))
        drawCircle(C.Sub.copy(alpha = 0.5f), r * 0.55f, c, style = Stroke(1.5.dp.toPx()))
        drawCircle(C.Violet.copy(alpha = 0.6f + glow * 0.4f), r * 0.14f, c)
    }
}

/** The outline car from behind, standing on [ground] around [cx], [cw] wide and 0.78 of that tall. */
fun DrawScope.outlineCarRear(cx: Float, ground: Float, cw: Float) {
    val ch = cw * 0.78f
    val x0 = cx - cw / 2
    val y0 = ground - ch
    fun p(x: Float, y: Float) = Offset(x0 + x * cw, y0 + y * ch)
    // the tyres under the body
    for (x in listOf(0.06f, 0.80f)) {
        drawRoundRect(Color(0xFF0B0E18), p(x, 0.80f), Size(cw * 0.14f, ch * 0.20f), androidx.compose.ui.geometry.CornerRadius(cw * 0.03f))
        drawRoundRect(C.Sub.copy(alpha = 0.8f), p(x, 0.80f), Size(cw * 0.14f, ch * 0.20f), androidx.compose.ui.geometry.CornerRadius(cw * 0.03f),
            style = Stroke(2.dp.toPx()))
    }
    val body = Path().apply {
        moveTo(p(0.04f, 0.86f).x, p(0.04f, 0.86f).y)
        lineTo(p(0.0f, 0.52f).x, p(0.0f, 0.52f).y)
        quadraticBezierTo(p(0.01f, 0.43f).x, p(0.01f, 0.43f).y, p(0.10f, 0.42f).x, p(0.10f, 0.42f).y)
        lineTo(p(0.19f, 0.40f).x, p(0.19f, 0.40f).y)
        quadraticBezierTo(p(0.24f, 0.08f).x, p(0.24f, 0.08f).y, p(0.34f, 0.07f).x, p(0.34f, 0.07f).y)
        lineTo(p(0.66f, 0.07f).x, p(0.66f, 0.07f).y)
        quadraticBezierTo(p(0.76f, 0.08f).x, p(0.76f, 0.08f).y, p(0.81f, 0.40f).x, p(0.81f, 0.40f).y)
        lineTo(p(0.90f, 0.42f).x, p(0.90f, 0.42f).y)
        quadraticBezierTo(p(0.99f, 0.43f).x, p(0.99f, 0.43f).y, p(1.0f, 0.52f).x, p(1.0f, 0.52f).y)
        lineTo(p(0.96f, 0.86f).x, p(0.96f, 0.86f).y)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(C.Violet.copy(alpha = 0.30f), C.Purple.copy(alpha = 0.06f)), y0, ground))
    drawPath(body, C.Violet.copy(alpha = 0.8f), style = Stroke(2.2.dp.toPx()))
    val glass = Path().apply {
        moveTo(p(0.25f, 0.38f).x, p(0.25f, 0.38f).y)
        quadraticBezierTo(p(0.29f, 0.14f).x, p(0.29f, 0.14f).y, p(0.36f, 0.13f).x, p(0.36f, 0.13f).y)
        lineTo(p(0.64f, 0.13f).x, p(0.64f, 0.13f).y)
        quadraticBezierTo(p(0.71f, 0.14f).x, p(0.71f, 0.14f).y, p(0.75f, 0.38f).x, p(0.75f, 0.38f).y)
        close()
    }
    drawPath(glass, Color.White.copy(alpha = 0.07f))
    drawPath(glass, Color.White.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
    // the lights and the plate
    drawLine(Color(0xFFFF3B4E).copy(alpha = 0.85f), p(0.05f, 0.53f), p(0.24f, 0.53f), 4.dp.toPx())
    drawLine(Color(0xFFFF3B4E).copy(alpha = 0.85f), p(0.76f, 0.53f), p(0.95f, 0.53f), 4.dp.toPx())
    drawRect(Color.White.copy(alpha = 0.5f), p(0.39f, 0.62f), Size(cw * 0.22f, ch * 0.09f), style = Stroke(1.5.dp.toPx()))
}

/** The «Наклон» card's car for another make: from behind for the roll, from the side (nose left) for the pitch, on [ground]. */
fun DrawScope.outlineCarOnGround(cx: Float, ground: Float, rear: Boolean) {
    if (rear) {
        outlineCarRear(cx, ground, minOf(size.width * 0.62f, ground * 0.9f / 0.78f))
        return
    }
    // the wheels reach ~0.37 of the width below the box's top
    val cw = minOf(size.width * 0.86f, ground * 0.9f / 0.37f)
    scale(-1f, 1f, Offset(cx, ground)) { outlineCarSide(cx - cw / 2, ground - cw * 0.366f, cw) }
}

/** «Тип машины» in Настройки → «Машина и адаптер»: found by itself, or said by hand. */
@Composable
fun CarKindSettings() {
    val s = Repo.settings
    Repo.carVersion.collectAsState().value
    val other by CarKind.other.collectAsState()
    val make by CarKind.make.collectAsState()
    val kind = s.carKind
    T("Тип машины", 15.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("auto" to "Авто", "subaru" to "Subaru", "other" to "Другая").forEach { (k, l) ->
            Chip(l, kind == k, { s.carKind = k })
        }
    }
    val now = if (other) "другая машина" + (make?.let { " (${it.name})" } ?: "") else "Subaru"
    T((if (kind == "auto") "Сейчас: $now. По VIN и по ответу ЭБУ. " else "") +
        "У другой машины скрыто то, что есть только у Subaru: экран АКПП, «Обучение», профили RomRaider и CarBerry.",
        11.sp, C.Sub)
}
