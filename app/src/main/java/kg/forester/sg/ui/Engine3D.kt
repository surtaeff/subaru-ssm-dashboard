package kg.forester.sg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kg.forester.sg.data.Repo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * «3D мотор» (test page): a boxer EJ built from boxes and prisms right here (no model file), turned by a finger
 * flat-shaded and sorted back to front on a Canvas. Every part the app has a reading for
 * carries a callout with the live value in the colour of its state; a tap explains what it is and what is normal.
 */

private data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
}

private class Face(val pts: List<V3>, val color: Color)

/** The parts, in model units (a decimetre or so): x across (the cylinder banks), y up, z from the front (−) back. */
/** The engine shaped after [e]: 2 or 3 cylinders a side, the bore of a 2.0 or a 2.5, turbo parts only on a turbo. */
private fun engineMesh(e: kg.forester.sg.ssm.EngineInfo): List<Face> {
    val turbo = e.turbo
    // valve covers by family: red for the EJ turbos, black for FA/FB, silver for the sixes, dark for the rest
    val accent = when {
        e.family.startsWith("EJ") && turbo -> Color(0xFF9B1C22)
        e.family == "FA20" || e.family == "FB" -> Color(0xFF26292F)
        e.cylinders == 6 -> Color(0xFFA9AFB8)
        else -> Color(0xFF4A505A)
    }
    val zs = if (e.cylinders == 6) listOf(-0.8f, 0f, 0.8f) else listOf(-0.55f, 0.55f)
    val len = if (e.cylinders == 6) 2.9f else 2.2f
    val bore = if (e.family == "EJ25" || e.family == "FB" || e.cylinders == 6) 0.42f else 0.38f
    val out = ArrayList<Face>()
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, c: Color) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2; val y0 = cy - sy / 2; val y1 = cy + sy / 2; val z0 = cz - sz / 2; val z1 = cz + sz / 2
        fun f(vararg p: V3) { out += Face(p.toList(), c) }
        f(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y1, z0), V3(x0, y1, z0))
        f(V3(x0, y0, z1), V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y0, z1))
        f(V3(x0, y0, z0), V3(x0, y1, z0), V3(x0, y1, z1), V3(x0, y0, z1))
        f(V3(x1, y0, z0), V3(x1, y0, z1), V3(x1, y1, z1), V3(x1, y1, z0))
        f(V3(x0, y1, z0), V3(x1, y1, z0), V3(x1, y1, z1), V3(x0, y1, z1))
        f(V3(x0, y0, z0), V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y0, z0))
    }
    /** A round part as a 14-sided prism along [axis] ('x', 'y' or 'z'). */
    fun prism(cx: Float, cy: Float, cz: Float, r: Float, len: Float, axis: Char, c: Color, n: Int = 14) {
        fun at(a: Float, t: Float): V3 {
            val u = r * cos(a); val v = r * sin(a)
            return when (axis) {
                'x' -> V3(cx + t, cy + u, cz + v)
                'y' -> V3(cx + u, cy + t, cz + v)
                else -> V3(cx + u, cy + v, cz + t)
            }
        }
        val h = len / 2
        val ring = List(n) { (it.toFloat() / n) * 2 * Math.PI.toFloat() }
        for (i in 0 until n) {
            val a = ring[i]; val b = ring[(i + 1) % n]
            out += Face(listOf(at(a, -h), at(b, -h), at(b, h), at(a, h)), c)
        }
        out += Face(ring.map { at(it, -h) }, c)
        out += Face(ring.map { at(it, h) }.reversed(), c)
    }
    val metal = Color(0xFF8C929C); val dark = Color(0xFF2E323A); val alu = Color(0xFFB4BAC4); val rust = Color(0xFF8A5F44)
    box(0f, 0f, 0f, 1.3f, 1.0f, len, metal)                        // crankcase
    for (s in listOf(-1f, 1f)) {
        for (z in zs) prism(s * 0.95f, 0.05f, z, bore, 0.65f, 'x', alu)                     // cylinders
        box(s * 1.52f, 0.05f, 0f, 0.42f, 0.95f, len - 0.1f, Color(0xFF7C828C))              // heads
        box(s * 1.83f, 0.05f, 0f, 0.2f, 0.76f, len - 0.3f, accent)                          // valve covers
        for (z in zs) box(s * 0.92f, 0.58f, z, 0.95f, 0.16f, 0.2f, Color(0xFF606775))       // runners
        for (z in zs) prism(s * 0.9f, -0.58f, z, 0.09f, 0.95f, 'x', rust)                   // exhaust
        box(s * 1.5f, 0.1f, -1.12f, 0.46f, 0.9f, 0.1f, dark)                                  // belt covers
    }
    box(0f, -0.75f, 0.1f, 1.1f, 0.42f, len - 0.4f, Color(0xFF3B3F47)) // oil pan
    box(0f, 0.75f, 0f, 0.95f, 0.34f, len - 0.7f, Color(0xFF5B6170))   // intake plenum
    prism(0f, 0.75f, -0.95f, 0.17f, 0.3f, 'z', alu)                 // throttle body
    prism(-0.55f, 0.88f, -1.35f, 0.15f, 0.6f, 'z', Color(0xFF3A3F48)) // intake pipe with the MAF
    box(0f, 0.05f, -1.17f, 1.2f, 0.7f, 0.08f, dark)                 // centre belt cover
    prism(0f, -0.25f, -1.3f, 0.32f, 0.12f, 'z', Color(0xFF30343C))  // crank pulley
    prism(-0.78f, 0.62f, -1.15f, 0.26f, 0.45f, 'z', alu)            // alternator
    prism(-0.8f, -0.45f, -0.9f, 0.11f, 0.25f, 'x', Color(0xFF9AA0AA)) // thermostat housing
    prism(0.15f, 0.55f, 0.45f, 0.07f, 0.12f, 'y', Color(0xFF444850))   // knock sensor
    prism(0.25f, -0.78f, 0.95f, 0.06f, 0.2f, 'y', Color(0xFFD9DDE3))    // A/F sensor
    if (turbo) {
        box(0f, 1.24f, -0.1f, 1.55f, 0.2f, 1.2f, Color(0xFFC3C9D2))     // intercooler
        prism(1.1f, -0.2f, 1.25f, 0.32f, 0.42f, 'x', Color(0xFFB9BEC6)) // compressor
        prism(1.48f, -0.2f, 1.25f, 0.27f, 0.34f, 'x', Color(0xFFB07A4A)) // turbine (hot side)
    }
    return out
}

/** A part with a reading: where its callout points ([anchor]), the metric ids to show, what to say about it. */
private class Spot(val id: String, val title: String, val anchor: V3, val metrics: List<String>, val about: String, val turboOnly: Boolean = false)

internal const val STOCK_EJ20T = "Сток EJ20T обычно до 0,7–0,9 бар (зависит от версии)."

/** What a callout says on this engine: the stock EJ20T's boost figure only on one. */
internal fun spotAbout(about: String, e: kg.forester.sg.ssm.EngineInfo) =
    if (InfoTexts.ej20Boost(e)) about else about.replace("$STOCK_EJ20T ", "")

private val SPOTS = listOf(
    Spot("coolant", "Охлаждение (ОЖ)", V3(-0.8f, -0.45f, -0.9f), listOf("coolant"),
        "Датчик температуры ОЖ у термостата. Прогретый мотор на ходу держит примерно 85–97°. Выше 100° повод присмотреться, 105° и выше перегрев. Долго ниже 75° в движении: подклинил открытым термостат."),
    Spot("maf", "Расходомер (MAF)", V3(-0.55f, 0.88f, -1.55f), listOf("maf"),
        "Сколько воздуха идёт в мотор, г/с. На прогретых холостых у EJ20 обычно около 2,5–4 г/с, растёт с оборотами и нагрузкой. Заниженный при той же нагрузке: грязный датчик или подсос воздуха после него."),
    Spot("throttle", "Дроссель", V3(0f, 0.75f, -1.1f), listOf("throttle"),
        "Насколько открыта заслонка. На холостых почти закрыта, в пол около 100%."),
    Spot("intake", "Воздух на впуске", V3(0f, 1.0f, 0.55f), listOf("intake"),
        "Температура воздуха во впуске. Обычно выше уличной на 10–30°. Сильно горячий воздух забирает мощность и повышает риск детонации."),
    Spot("turbo", "Турбина", V3(1.3f, -0.2f, 1.25f), listOf("boost", "wgdc"),
        "Наддув над атмосферой и открытие вестгейта. $STOCK_EJ20T Не добирает: утечка во впуске или вестгейт. Перебирает: вестгейт или прошивка.", turboOnly = true),
    Spot("inj", "Форсунки", V3(1.25f, 0.62f, -0.55f), listOf("inj", "injDuty"),
        "Время впрыска и загрузка форсунок. На холостых около 2–3 мс. Загрузка выше 85% на пике значит, форсунки на пределе."),
    Spot("knock", "Датчик детонации", V3(0.15f, 0.62f, 0.45f), listOf("fbkc", "flkc", "iamV"),
        "Слышит детонацию, а ЭБУ убирает угол. Откат FBKC и выученная FLKC в норме 0. IAM 1,0 значит мозги довольны бензином и мотором."),
    Spot("afr", "Лямбда (A/F)", V3(0.25f, -0.86f, 0.95f), listOf("afr", "afcorr", "aflearn"),
        "Смесь и коррекции. На прогретых холостых смесь около 14,7. Коррекция плюс обучение в сумме ±5% норма, больше +10% похоже на подсос воздуха или слабое топливо."),
    Spot("alt", "Генератор", V3(-0.78f, 0.62f, -1.4f), listOf("battery", "alt"),
        "Напряжение бортсети. На работающем моторе 13,5–14,5 В. Ниже 13 генератор не заряжает, выше 15 перезаряд."),
    Spot("avcs", "Распредвал (AVCS)", V3(-1.55f, 0.45f, -1.1f), listOf("avcs"),
        "Насколько муфта AVCS повернула впускной распредвал. На холостых около нуля, в нагрузке до 20–35°."),
    Spot("oil", "Масло", V3(0f, -0.95f, 0.3f), listOf("oil"),
        "Температура масла. У большинства EJ своего датчика нет, это оценка по ОЖ, времени и нагрузке: прогретое масло обычно на 10–20° горячее антифриза, под нагрузкой больше, но точность у оценки условная (если есть с чем сравнить, в настройках машины есть поправка). Пока антифриз ниже 80°, не крутите выше 3000–3500 оборотов. " +
            "Лампа давления масла загорается почти при нуле (0,15 бар, а норма на горячих холостых от 1 бара): если загорелась, глушите сразу. Уровень щупом проверяйте хотя бы раз в пару заправок."),
    Spot("crank", "Коленвал", V3(0f, -0.25f, -1.38f), listOf("rpm", "load"),
        "Обороты и нагрузка (воздух за оборот, г/об). Прогретые холостые EJ около 650–800 об/мин."),
)

private data class Proj(val x: Float, val y: Float, val z: Float, val visible: Boolean = true)

/**
 * A real engine model rendered in Blender (tools/engine3d/render_engine.py): turntable frames in assets/<dir>/
 * and, per frame, where each sensor is ([x, y] in 0..1 of the frame) and whether a part hides it.
 */
private class EngineFrames(val dir: String, val count: Int, val w: Int, val h: Int, val anchors: Map<String, List<FloatArray>>) {
    private val cache = android.util.LruCache<Int, android.graphics.Bitmap>(14)

    fun bitmap(ctx: android.content.Context, i: Int): android.graphics.Bitmap? = cache.get(i) ?: runCatching {
        ctx.assets.open("$dir/engine_%03d.webp".format(i)).use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()?.also { cache.put(i, it) }

    companion object {
        /** The rendered model for [e]'s family, if the app has one: EJ turbo, EJ without the turbo parts, FA. */
        fun load(ctx: android.content.Context, e: kg.forester.sg.ssm.EngineInfo): EngineFrames? {
            val dir = when {
                e.family.startsWith("EJ") && e.turbo -> "engine_ej_t"
                e.family.startsWith("EJ") -> "engine_ej_na"
                e.family == "FA20" || e.family == "FB" -> "engine_fa"
                else -> return null
            }
            return runCatching {
                val o = org.json.JSONObject(ctx.assets.open("$dir/anchors.json").bufferedReader().readText())
                val size = o.getJSONArray("size")
                val an = o.getJSONObject("anchors")
                EngineFrames(dir, o.getInt("frames"), size.getInt(0), size.getInt(1), an.keys().asSequence().associateWith { k ->
                    val a = an.getJSONArray(k)
                    List(a.length()) { i -> a.getJSONArray(i).let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } }
                })
            }.getOrNull()
        }
    }
}

@Composable
internal fun Engine3DTab(bottom: @Composable () -> Unit = {}) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val init by Repo.ecuInit.collectAsState()
    val g by Repo.guard.collectAsState()
    val guardTurbo = g.turboCar
    // the engine of this car: from the ECU's ROM, else the car set up; unknown: the generic EJ drawn here
    val demoOn by Repo.demoOn.collectAsState()
    val engine = remember(init, guardTurbo, demoOn) { kg.forester.sg.ssm.EngineId.detect(appCtx) }
    // the real model when the app has it rendered for this family, else the one drawn here; its JSON is read off the
    // main thread, and the room stands empty until then (which one it is decides where everything goes)
    val read by androidx.compose.runtime.produceState<Pair<kg.forester.sg.ssm.EngineInfo, EngineFrames?>?>(null, engine) {
        value = engine to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EngineFrames.load(appCtx, engine) }
    }
    val loaded = read
    if (loaded == null || loaded.first != engine) {
        Box(Modifier.fillMaxSize())
        return
    }
    val frames = loaded.second
    val turbo = engine.turbo
    val mesh = remember(engine) { engineMesh(engine) }
    var frameF by remember { mutableFloatStateOf(0f) }
    val frame = frames?.let { ((frameF.roundToInt() % it.count) + it.count) % it.count } ?: 0
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(frames, frame) {
        if (frames != null) bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { frames.bitmap(appCtx, frame) } ?: bmp
    }
    val avail by Repo.rrAvailable.collectAsState()
    val ctx by rememberMetricCtx()
    var yaw by remember { mutableFloatStateOf(0.75f) }
    var pitch by remember { mutableFloatStateOf(0.5f) }

    var selected by remember { mutableStateOf<Spot?>(null) }
    var watched by remember { mutableStateOf(false) }
    val spots = SPOTS.filter { (!it.turboOnly || turbo) && (it.metrics.any { id -> Metrics.of(id, avail)?.read(ctx) != null } || guardNote(it.id, g) != null) }
        .ifEmpty { SPOTS.filter { !it.turboOnly || turbo } }
        .filter { frames == null || frames.anchors.containsKey(it.id) }

    val calloutW = calloutWidth()
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
            .background(Brush.radialGradient(listOf(C.palette.stageTop, C.palette.stageFoot)))
            .border(1.dp, C.Border, RoundedCornerShape(18.dp)),
    ) {
        val d = LocalDensity.current
        val wPx = with(d) { maxWidth.toPx() }
        val hPx = with(d) { maxHeight.toPx() }
        val labelW = with(d) { calloutW.toPx() }
        // six to a side have to fit the panel's height: a low panel gets lower callouts, their text with them
        val calloutH = calloutHeight(maxHeight)
        val labelH = with(d) { calloutH.toPx() }
        val margin = with(d) { 14.dp.toPx() }
        val cy = hPx * 0.58f
        val cx = wPx * 0.5f
        val focal = minOf(wPx, hPx) * 1.25f

        fun project(p: V3): Proj {
            // turn about y (yaw) then x (pitch), then the camera 6 units back
            val x1 = p.x * cos(yaw) + p.z * sin(yaw)
            val z1 = -p.x * sin(yaw) + p.z * cos(yaw)
            // pitch > 0: the camera above, looking down on the engine
            val y2 = p.y * cos(pitch) + z1 * sin(pitch)
            val z2 = -p.y * sin(pitch) + z1 * cos(pitch) + 6.2f
            return Proj(cx + focal * x1 / z2, cy - focal * y2 / z2, z2)
        }

        // the rendered frame fitted into the area (a little below the middle, under the engine card)
        val fs = frames?.let { minOf(wPx * 0.62f / it.w, hPx * 0.76f / it.h) } ?: 1f
        val fx = frames?.let { cx - it.w * fs / 2 } ?: 0f
        val fy = frames?.let { hPx * 0.53f - it.h * fs / 2 } ?: 0f
        // callouts: left or right of the engine by where their point is, spread so they don't overlap
        val anchors = spots.associateWith { s ->
            if (frames == null) project(s.anchor)
            else frames.anchors[s.id]!![frame].let { a -> Proj(fx + a[0] * frames.w * fs, fy + a[1] * frames.h * fs, 0f, a[2] > 0.5f) }
        }
        val placed = HashMap<Spot, Offset>()
        // a column holds only so many; the extra ones (nearest the middle) go to the other side
        val fit = ((hPx - 2 * margin + margin * 0.6f) / (labelH + margin * 0.6f)).toInt().coerceAtLeast(1)
        val onLeft = spots.filter { anchors[it]!!.x < cx }.toMutableList()
        val onRight = spots.filter { anchors[it]!!.x >= cx }.toMutableList()
        while (onLeft.size > fit && onRight.size < fit) onLeft.maxBy { anchors[it]!!.x }.let { onLeft -= it; onRight += it }
        while (onRight.size > fit && onLeft.size < fit) onRight.minBy { anchors[it]!!.x }.let { onRight -= it; onLeft += it }
        for (left in listOf(true, false)) {
            val side = (if (left) onLeft else onRight).sortedBy { anchors[it]!!.y }
            val gap = margin * 0.6f
            val ys = FloatArray(side.size)
            var start = margin
            side.forEachIndexed { i, s -> ys[i] = maxOf(start, anchors[s]!!.y - labelH / 2); start = ys[i] + labelH + gap }
            // too low at the bottom: move the column up as much as needed (the top stays inside)
            val over = (ys.lastOrNull() ?: 0f) + labelH + margin - hPx
            if (over > 0) for (i in ys.indices.reversed()) {
                val limit = if (i == ys.lastIndex) hPx - labelH - margin else ys[i + 1] - labelH - gap
                ys[i] = minOf(ys[i], limit).coerceAtLeast(margin + i * (labelH + gap))
            }
            side.forEachIndexed { i, s -> placed[s] = Offset(if (left) margin else wPx - labelW - margin, ys[i]) }
        }

        val violet = C.Violet
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    yaw += drag.x * 0.008f
                    pitch = (pitch + drag.y * 0.006f).coerceIn(-0.3f, 1.3f)
                    // the rendered model: a frame per ~10 px of the finger
                    frameF -= drag.x / 10f
                    change.consume()
                }
            },
        ) {
            if (frames != null) {
                bmp?.let { b ->
                    drawImage(b.asImageBitmap(), dstOffset = IntOffset(fx.roundToInt(), fy.roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize((frames.w * fs).roundToInt(), (frames.h * fs).roundToInt()))
                }
            } else {
            // a soft floor shadow
            drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), Offset(cx, cy + focal * 1.2f / 6.2f + 30f), focal * 0.42f),
                Offset(cx - focal * 0.42f, cy + focal * 1.2f / 6.2f), Size(focal * 0.84f, focal * 0.14f))
            // faces back to front, lit from the upper left front
            val light = V3(-0.45f, 0.75f, -0.5f).let { val l = it.len(); V3(it.x / l, it.y / l, it.z / l) }
            val drawn = mesh.map { f ->
                val p = f.pts.map { project(it) }
                val cam = f.pts.map { v ->
                    val x1 = v.x * cos(yaw) + v.z * sin(yaw); val z1 = -v.x * sin(yaw) + v.z * cos(yaw)
                    V3(x1, v.y * cos(pitch) + z1 * sin(pitch), -v.y * sin(pitch) + z1 * cos(pitch))
                }
                val n = (cam[1] - cam[0]).cross(cam[2] - cam[0]).let { val l = it.len().coerceAtLeast(1e-6f); V3(it.x / l, it.y / l, it.z / l) }
                Triple(p, p.map { it.z }.average().toFloat(), abs(n.x * light.x + n.y * light.y + n.z * light.z) to f.color)
            }.sortedByDescending { it.second }
            for ((p, _, shade) in drawn) {
                val (lit, base) = shade
                val k = 0.32f + 0.68f * lit
                val col = Color(base.red * k, base.green * k, base.blue * k, 1f)
                val path = Path().apply { moveTo(p[0].x, p[0].y); for (i in 1 until p.size) lineTo(p[i].x, p[i].y); close() }
                drawPath(path, col)
                drawPath(path, Color.White.copy(alpha = 0.05f + 0.07f * lit), style = Stroke(1.2f))
            }
            }
            // leader lines: from the part to its callout, with a glowing dot on the part
            for (s in spots) {
                val a = anchors[s] ?: continue
                val o = placed[s] ?: continue
                val left = o.x < cx
                val end = Offset(if (left) o.x + labelW else o.x, o.y + labelH / 2)
                val mid = Offset(end.x + if (left) 18f else -18f, end.y)
                val col = if (selected == s) Color.White else violet
                // a sensor behind the engine on this frame: a faint line and dot
                val al = if (a.visible) 1f else 0.3f
                drawLine(col.copy(alpha = 0.7f * al), Offset(a.x, a.y), mid, strokeWidth = 2f)
                drawLine(col.copy(alpha = 0.7f * al), mid, end, strokeWidth = 2f)
                drawCircle(col.copy(alpha = 0.25f * al), 12f, Offset(a.x, a.y))
                drawCircle(col.copy(alpha = al), 5f, Offset(a.x, a.y))
            }
        }

        for (s in spots) {
            val o = placed[s] ?: continue
            Callout(s, avail, ctx, guardNote(s.id, g), selected == s, calloutH, Modifier.offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }) {
                selected = if (selected == s) null else s
                watched = false
            }
        }

        // which engine and how it is known; what the app watches behind the bell
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).width(with(d) { (wPx - 2 * (labelW + margin) - 24f).toDp() })
                .clip(RoundedCornerShape(14.dp)).background(C.Card.copy(alpha = 0.85f)).border(1.dp, C.Border, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                T(engine.title, 16.sp, weight = FontWeight.Bold, maxLines = 1)
                T(engineSource(engine), 11.sp, C.Sub, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (watched) C.Violet.copy(alpha = 0.3f) else C.Violet.copy(alpha = 0.14f))
                    .clickable { watched = !watched; selected = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Bell, "Что отслеживается", tint = C.Violet, modifier = Modifier.size(18.dp)) }
        }

        // under the engine: the 3D / table switch
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { bottom() }

        // a part's card (its readings, what the guard knows about it, what is normal) or what the app watches
        val cardMaxH = maxHeight - 24.dp
        AnimatedVisibility(selected != null || watched, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn() + slideInHorizontally { it / 4 }, exit = fadeOut() + slideOutHorizontally { it / 4 }) {
            val s = selected
            Column(
                Modifier.width(560.dp).heightIn(max = cardMaxH).clip(RoundedCornerShape(16.dp)).background(C.Card.copy(alpha = 0.97f))
                    .border(1.dp, C.Violet.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(s?.title ?: "Что отслеживается", 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconTap(Lucide.X, "Закрыть", { selected = null; watched = false })
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (s == null) WatchedDetails()
                    else {
                        val shown = s.metrics.mapNotNull { id -> Metrics.of(id, avail)?.let { it to it.read(ctx) } }.filter { it.second != null }
                        if (shown.isNotEmpty()) Row(Modifier.padding(bottom = 8.dp)) {
                            shown.forEach { (m, v) ->
                                Column(Modifier.padding(end = 18.dp)) {
                                    T(m.short, 11.sp, C.Sub)
                                    T(valueText(m, v), 18.sp, if (m.warn(v)) C.Orange else C.Text, FontWeight.Bold)
                                }
                            }
                        }
                        when (s.id) {
                            "knock" -> KnockDetails()
                            "oil" -> OilDetails()
                            "turbo" -> TurboDetails()
                            "coolant" -> CoolingDetails()
                        }
                        Spacer(Modifier.height(10.dp))
                        T(spotAbout(s.about, engine), 13.sp, C.Text.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

/** What the engine guard adds to a part's callout: a short state and its colour. */
private fun guardNote(id: String, g: kg.forester.sg.service.GuardState): Pair<String, Color>? = when (id) {
    "knock" -> g.iam?.let { "IAM %.2f".format(it) to iamColor(it.toDouble()) }
        ?: g.flkc?.let { "FLKC %.1f°".format(it) to (if (it <= -2.8f) C.Red else if (it < -1f) C.Orange else C.Green) }
    "oil" -> if (g.oilC == null) null else if (g.cold) "холодный" to C.Orange else "прогрет" to C.Green
    "turbo" -> when {
        !g.turbo -> null
        g.cooldownSec > 0 -> "ждать ${g.cooldownSec} с" to C.Orange
        g.heat > 12f -> "~${g.heat.toInt()} с ХХ" to C.Orange
        else -> null
    }
    "coolant" -> g.coolantMax?.let { "макс $it°" to (if (it >= 105) C.Red else C.Sub) }
    else -> null
}

/** How the engine was found, in one line under its name. */
internal fun engineSource(e: kg.forester.sg.ssm.EngineInfo): String = when (e.source) {
    "ЭБУ" -> "Определён по ЭБУ: ROM ${e.romId}, ${e.car}"
    "ROM" -> "Определён по коду прошивки ЭБУ ${e.romId} (её нет в базе: тип и объём по ROM ID)"
    "прошлое" -> "По ЭБУ при прошлом подключении: ROM ${e.romId ?: "—"}"
    "ЭБУ без модели" -> "ЭБУ ответил, но по ROM ${e.romId} модель не определить"
    "демо" -> "Демо: пример мотора, не ваша машина"
    "Link" -> "Спортивный ЭБУ Link: модель мотора он не сообщает, турбо по увиденному наддуву"
    else -> "Определится, когда ответит ЭБУ (настройки не используются)"
}

private fun valueText(m: Indicator, v: Float?): String =
    m.text(v) + if (m.unit.isEmpty() || v == null) "" else if (m.unit.startsWith("°")) m.unit else " ${m.unit}"

/** A callout's size: two lines of the secondary size beside its icon, no wider than the engine can spare. */
@Composable
private fun calloutWidth() = maxOf(184.dp, LocalCar.current.secondary.asDp() * 8.5f)

@Composable
private fun calloutHeight(room: Dp) = minOf(maxOf(58.dp, LocalCar.current.secondary.lineDp() * 2f + 2.dp), (room - 28.dp) / 6f - 8.4.dp).coerceAtLeast(44.dp)

/** [main] and, bottom-aligned after it, [aside] if the rest of the width takes it whole; otherwise [aside] is left out. */
@Composable
private fun MainAndAside(main: @Composable () -> Unit, aside: (@Composable () -> Unit)?) {
    androidx.compose.ui.layout.Layout({ main(); aside?.invoke() }) { ms, c ->
        val m = ms[0].measure(c.copy(minWidth = 0))
        val left = c.maxWidth - m.width
        val a = ms.getOrNull(1)?.takeIf { it.maxIntrinsicWidth(c.maxHeight) <= left }
            ?.measure(androidx.compose.ui.unit.Constraints(maxWidth = left.coerceAtLeast(0), maxHeight = c.maxHeight))
        val h = maxOf(m.height, a?.height ?: 0)
        layout(m.width + (a?.width ?: 0), h) {
            m.place(0, h - m.height)
            a?.place(m.width, h - a.height)
        }
    }
}

/** A callout card: the part, its main value large in the colour of its state, a second value small. */
@Composable
private fun Callout(
    s: Spot, avail: List<kg.forester.sg.ssm.RrParam>, ctx: MetricCtx, note: Pair<String, Color>?, selected: Boolean, height: Dp, modifier: Modifier,
    onClick: () -> Unit,
) {
    val main = s.metrics.firstNotNullOfOrNull { Metrics.of(it, avail) }
    val v = main?.read(ctx)
    val second = s.metrics.drop(1).firstNotNullOfOrNull { id -> Metrics.of(id, avail)?.let { m -> m.read(ctx)?.let { m to it } } }
    val state = when {
        note != null && (note.second == C.Red || note.second == C.Orange) -> note.second
        v == null -> if (note != null) note.second else C.Gray
        main.warn(v) -> C.Orange
        else -> C.Green
    }
    Row(
        modifier.width(calloutWidth()).height(height).clip(RoundedCornerShape(14.dp))
            .background(C.Card.copy(alpha = if (selected) 0.98f else 0.86f))
            // «all fine» stays quiet (the icon says it), so an orange or red frame stands out among a dozen cards
            .border(1.dp, if (selected) Color.White else state.copy(alpha = if (state == C.Green) 0.3f else 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(state.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(main?.icon ?: Lucide.Gauge, null, tint = state, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column {
            // six to a side and the panel low: the value alone at the full size, the icon and a tap say what it is
            val textSp = LocalCar.current.secondary
            if (height >= textSp.lineDp() * 2f) FitText(s.title, textSp, color = C.Sub)
            // the small second value only when it fits whole beside the main one (it was cut to «0.1…» in the card's
            // fixed width); the main value always stays
            MainAndAside(
                { FitText(if (v == null && note != null) note.first else main?.let { valueText(it, v) } ?: "—", textSp, color = C.Text, weight = FontWeight.Bold) },
                // no reading of its own: the guard's value takes its place
                if (note != null && v != null) ({ T("  " + note.first, 11.sp, note.second, maxLines = 1) })
                else if (second != null) ({ T("  " + valueText(second.first, second.second), 11.sp, C.Sub, maxLines = 1) })
                else null,
            )
        }
    }
}
