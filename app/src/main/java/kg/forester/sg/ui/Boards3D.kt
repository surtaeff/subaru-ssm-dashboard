package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.LoadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Boards and modules in 3D: turntable frames rendered in Blender (tools/boards3d/render_board.py) into
 * assets/boards3d/<id>/. The Nano, Uno, Mega and the 1-channel relay are CC BY 4.0 models (credits in README.md),
 * the rest are built in the script from the boards' real layouts.
 */

/** The model of [b] (the ESP32-S3 has none rendered yet: [hasModel3D] tells). */
fun board3d(b: BoxBoard) = when (b) {
    BoxBoard.NANO -> "nano"; BoxBoard.UNO -> "uno"; BoxBoard.PROMICRO -> "promicro"; BoxBoard.MEGA -> "mega"; BoxBoard.ESP32S3 -> "esp32s3"
}

/** Whether the turntable frames of [id] are in the app. */
fun hasModel3D(ctx: android.content.Context, id: String) = Turntable.of(ctx, id) != null

/** The module an output's load is, or null (an LED needs no module). */
fun load3d(l: LoadKind) = when (l) {
    LoadKind.RELAY -> "relay1"; LoadKind.COIL12 -> "coil12kit"; LoadKind.MOSFET -> "mosfet"; LoadKind.SSR -> "ssr"; LoadKind.LED -> null
}

private class Turntable(val dir: String, val count: Int, val w: Int, val h: Int) {
    private val cache = android.util.LruCache<Int, android.graphics.Bitmap>(40)
    /** How many models on screen show this one: the frames (23 MB a board) stay only while one does. */
    private var users = 0
    fun bitmap(ctx: android.content.Context, i: Int): android.graphics.Bitmap? = cache.get(i) ?: runCatching {
        ctx.assets.open("$dir/board_%03d.webp".format(i)).use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()?.also { b -> synchronized(this) { if (users > 0) cache.put(i, b) } }

    @Synchronized fun enter() { users++ }
    @Synchronized fun leave() { if (--users == 0) cache.evictAll() }
    fun clear() = cache.evictAll()

    companion object {
        private val loaded = HashMap<String, Turntable?>()
        fun clearAll() { synchronized(loaded) { loaded.values.toList() }.forEach { it?.clear() } }
        fun of(ctx: android.content.Context, id: String): Turntable? = synchronized(loaded) {
            loaded.getOrPut(id) {
                runCatching {
                    val m = org.json.JSONObject(ctx.assets.open("boards3d/$id/meta.json").bufferedReader().readText())
                    val s = m.getJSONArray("size")
                    Turntable("boards3d/$id", m.getInt("frames"), s.getInt(0), s.getInt(1))
                }.getOrNull()
            }
        }
    }
}

/** A memory warning: the frames of the boards on screen are decoded again as they come round. */
fun trimBoardFrames() = Turntable.clearAll()

/**
 * The model turning slowly by itself; a finger turns it either way and it carries on by itself a few seconds later.
 * Nothing is shown when the model is missing.
 */
@Composable
fun Model3D(id: String, modifier: Modifier) {
    val ctx = LocalContext.current
    // the model's JSON off the main thread; its room stands empty until then
    val read by androidx.compose.runtime.produceState<Pair<String, Turntable?>?>(null, id) {
        value = id to withContext(Dispatchers.IO) { Turntable.of(ctx, id) }
    }
    val loaded = read
    if (loaded == null || loaded.first != id) {
        Box(modifier)
        return
    }
    val t = loaded.second ?: return
    var frame by remember(id) { mutableFloatStateOf(0f) }
    var touched by remember { mutableLongStateOf(0L) }
    var bmp by remember(id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.DisposableEffect(t) {
        t.enter()
        onDispose { t.leave() }
    }
    LaunchedEffect(id) {
        while (true) {
            if (System.currentTimeMillis() - touched > 3000) frame = (frame + 1f) % t.count
            delay(180)
        }
    }
    val i = ((frame.roundToInt() % t.count) + t.count) % t.count
    LaunchedEffect(id, i) { bmp = withContext(Dispatchers.IO) { t.bitmap(ctx, i) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(id) {
            detectDragGestures { change, drag ->
                touched = System.currentTimeMillis()
                frame = ((frame - drag.x / 14f) % t.count + t.count) % t.count
                change.consume()
            }
        }) {
            val b = bmp ?: return@Canvas
            val s = minOf(size.width / t.w, size.height / t.h)
            val dw = t.w * s
            val dh = t.h * s
            drawImage(b.asImageBitmap(), dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
        }
    }
}
