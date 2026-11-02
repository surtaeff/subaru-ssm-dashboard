package kg.forester.sg.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The window's room on the app's canvas ([carDensity]). LocalConfiguration says it in the window's own dp, which are
 * another size: at 240 dpi on 1024×600 its 400 «dp» are a good half of the screen on the canvas.
 */
@Immutable
data class CanvasScreen(val screenWidthDp: Int, val screenHeightDp: Int)

fun canvasDp(windowDp: Int, densityDpi: Int, canvasDensity: Float): Int =
    (windowDp * densityDpi / 160f / canvasDensity).toInt()

@Composable
fun canvasScreen(): CanvasScreen {
    val cfg = LocalConfiguration.current
    val d = LocalDensity.current.density
    return CanvasScreen(canvasDp(cfg.screenWidthDp, cfg.densityDpi, d), canvasDp(cfg.screenHeightDp, cfg.densityDpi, d))
}

/** The colour of the voice's status: see [SwitchRow]. */
@Composable
fun speechColor(): Color {
    val failed = kg.forester.sg.service.Speech.failed.collectAsState().value
    val ready = kg.forester.sg.service.Speech.status.collectAsState().value.startsWith("готов")
    return if (failed) C.Orange else if (ready) C.Green else C.Sub
}

/** The bar's place in the room of a list, as parts of the room: the start and the length; null when all of it is seen. */
fun scrollThumb(viewport: Float, content: Float, offset: Float, minLength: Float = 0.1f): Pair<Float, Float>? {
    if (viewport <= 0f || content <= viewport + 0.5f) return null
    val length = (viewport / content).coerceIn(minLength.coerceAtMost(1f), 1f)
    val moved = (offset / (content - viewport)).coerceIn(0f, 1f)
    return moved * (1f - length) to length
}

/** How far a lazy list is, by what it has laid out: its items may differ, the bar only has to tell where in the list it is. */
private fun LazyListState.thumb(viewport: Float): Pair<Float, Float>? {
    val info = layoutInfo
    val seen = info.visibleItemsInfo
    if (seen.isEmpty()) return null
    if (seen.size == info.totalItemsCount && seen.first().offset >= 0 && seen.last().let { it.offset + it.size } <= viewport) return null
    val item = seen.sumOf { it.size }.toFloat() / seen.size
    return scrollThumb(viewport, item * info.totalItemsCount, item * firstVisibleItemIndex + firstVisibleItemScrollOffset)
}

private fun ContentDrawScope.cue(thumb: Pair<Float, Float>?, upMore: Boolean, downMore: Boolean, fade: Color?) {
    drawContent()
    if (thumb == null) return
    if (fade != null) {
        val h = minOf(18.dp.toPx(), size.height / 4)
        if (upMore) drawRect(Brush.verticalGradient(listOf(fade, fade.copy(alpha = 0f)), 0f, h), size = Size(size.width, h))
        if (downMore) drawRect(Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade), size.height - h, size.height),
            Offset(0f, size.height - h), Size(size.width, h))
    }
    // in the frame's padding beside the list when there is one: it covers nothing there, and a parent's clip keeps it in
    val w = 4.dp.toPx()
    drawRoundRect(
        C.Sub.copy(alpha = 0.75f), Offset(size.width + 3.dp.toPx(), size.height * thumb.first),
        Size(w, size.height * thumb.second), CornerRadius(w / 2),
    )
}

/** A thin bar beside what scrolls, and a fade at the edge where more is cut off. Read while drawing: a scroll only redraws. */
fun Modifier.scrollCue(state: ScrollState, fade: Color? = null): Modifier = drawWithContent {
    val more = state.maxValue.toFloat()
    cue(
        if (more > 0f && more < Int.MAX_VALUE) scrollThumb(size.height, size.height + more, state.value.toFloat()) else null,
        state.value > 0, state.value < state.maxValue, fade,
    )
}

fun Modifier.scrollCue(state: LazyListState, fade: Color? = null): Modifier = drawWithContent {
    cue(state.thumb(size.height), state.canScrollBackward, state.canScrollForward, fade)
}

/**
 * A panel's list with what stands above it: [head] stays in place only when the panel has plenty of room ([roomyAt]); on
 * a low screen it goes up with the list. Pinned there, it took the whole panel and the list had no height left.
 */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.ListWithHead(
    head: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical = androidx.compose.foundation.layout.Arrangement.Top,
    roomyAt: androidx.compose.ui.unit.Dp = 460.dp,
    fade: Color? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val roomy = maxHeight >= roomyAt
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            if (roomy) head()
            androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth().weight(1f).scrollCue(state, fade), state = state, verticalArrangement = verticalArrangement) {
                if (!roomy) item(key = "head") { androidx.compose.foundation.layout.Column { head() } }
                content()
            }
        }
    }
}

/**
 * A screen laid out to fill its room, with panels that can't shrink past some height. On a low screen (7", the menu at
 * the bottom) it keeps [minHeight] and scrolls as one, or what is at the bottom of its panels is cut off for good.
 */
@Composable
fun ScrollWhenLow(minHeight: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxHeight >= minHeight) content()
        else androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().cueScroll(null)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(minHeight)) { content() }
        }
    }
}

/** verticalScroll with its [scrollCue], for what is read inside a frame of the kit. */
@Composable
fun Modifier.cueScroll(fade: Color? = C.Sheet, state: ScrollState = rememberScrollState()): Modifier =
    scrollCue(state, fade).verticalScroll(state)

/**
 * Compose's scrollers take a drag from a finger or a pen, not from a pointer that says it is a mouse (a real mouse, and
 * touch panels on USB that report themselves as one): such a drag starts nothing, a tap works. A press of such a pointer
 * goes on as a finger's. Wheels and hover are other events and stay as they are.
 */
fun Window.dragsLikeFinger() {
    val inner = callback ?: return
    if (inner !is FingerCallback) callback = FingerCallback(inner)
}

private class FingerCallback(private val inner: Window.Callback) : Window.Callback by inner {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val finger = event.asFinger() ?: return inner.dispatchTouchEvent(event)
        return try { inner.dispatchTouchEvent(finger) } finally { finger.recycle() }
    }
}

private fun MotionEvent.asFinger(): MotionEvent? {
    if (getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return null
    // given as on the screen and moved back: Compose takes the raw position, and a dialog's window isn't at the corner
    val dx = rawX - x
    val dy = rawY - y
    val props = Array(pointerCount) { i -> MotionEvent.PointerProperties().also { getPointerProperties(i, it); it.toolType = MotionEvent.TOOL_TYPE_FINGER } }
    val coords = Array(pointerCount) { i -> MotionEvent.PointerCoords().also { getPointerCoords(i, it); it.x += dx; it.y += dy } }
    return MotionEvent.obtain(
        downTime, eventTime, action, pointerCount, props, coords, metaState, 0, xPrecision, yPrecision, deviceId, edgeFlags,
        InputDevice.SOURCE_TOUCHSCREEN, flags,
    ).also { it.offsetLocation(-dx, -dy) }
}
