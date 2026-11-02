package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import kg.forester.sg.data.Repo
import kg.forester.sg.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Small pictures of the 3D models for the chooser: one frame of the model's own stock colour, cropped to the car. Read off
 * the main thread, two at a time, and kept while the app runs (they are few and small).
 */
private object CarThumbs {
    private const val WIDTH_PX = 240
    private val cache = android.util.LruCache<String, ImageBitmap>(CarModels3d.size)
    private val gate = Semaphore(2)

    fun cached(id: String): ImageBitmap? = cache.get(id)

    suspend fun load(ctx: Context, id: String): ImageBitmap? = cache.get(id) ?: gate.withPermit {
        withContext(Dispatchers.IO) {
            runCatching { read(ctx, id) }.getOrNull()?.asImageBitmap()?.also { cache.put(id, it) }
        }
    }

    private fun read(ctx: Context, id: String): Bitmap? {
        val dir = car3dDir(ctx, id) ?: return null
        val frame = runCatching {
            org.json.JSONObject(ctx.assets.open("$dir/meta.json").bufferedReader().readText()).optInt("driveFrame", 0)
        }.getOrDefault(0)
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val b = ctx.assets.open("$dir/car_%03d.webp".format(frame)).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        var x0 = b.width; var y0 = b.height; var x1 = 0; var y1 = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            if ((b.getPixel(x, y) ushr 24) > 16) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        val car = if (x1 <= x0 || y1 <= y0) b
            else Bitmap.createBitmap(b, x0, y0, (x1 - x0 + 2).coerceAtMost(b.width - x0), (y1 - y0 + 2).coerceAtMost(b.height - y0))
        if (car.width <= WIDTH_PX) return car
        return Bitmap.createScaledBitmap(car, WIDTH_PX, (car.height * WIDTH_PX / car.width).coerceAtLeast(1), true)
    }
}

@Composable
private fun CarThumb(id: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val pic by produceState(CarThumbs.cached(id), id) { value = CarThumbs.load(ctx, id) }
    Box(modifier, contentAlignment = Alignment.Center) {
        pic?.let { Image(it, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) }
    }
}

/** Настройки → «Внешний вид»: which 3D car, its paint and its plate. */
@Composable
internal fun CarLookSettings() {
    val ctx = LocalContext.current
    Repo.carVersion.collectAsState().value
    val otherCar by kg.forester.sg.data.CarKind.other.collectAsState()
    val custom by Repo.car3dCustom.collectAsState()
    val car3d = car3dNow()
    var sheet by remember { mutableStateOf(false) }
    val name = CarModels3d.firstOrNull { it.first == car3d }?.second ?: car3d
    SettingRowButton({ sheet = true }) {
        CarThumb(car3d, Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(C.shade(0.25f)).border(1.dp, C.Border, RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T("3D-модель на главном экране", 15.sp, maxLines = 2)
            T(if (custom) name else tr("Автоматически") + " · " + tr(name), 12.sp, C.Sub, maxLines = 1)
        }
    }
    if (sheet) CarModelSheet(car3d, custom) { sheet = false }
    Spacer(Modifier.height(12.dp))
    if (car3d == CAR3D_UNKNOWN) T("У этой модели своя раскраска, цвет не меняется.", 11.sp, C.Sub) else CarPaintChooser(ctx, car3d)
    // the plate goes on the 3D Subaru: another car has only a silhouette
    if (!otherCar && car3d != CAR3D_UNKNOWN) { Spacer(Modifier.height(14.dp)); PlateSettings() }
}

/** «Автоматически», then every model with its picture, in a grid that scrolls. */
@Composable
private fun CarModelSheet(car3d: String, custom: Boolean, onClose: () -> Unit) {
    val s = Repo.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    CarSheet(onClose, 720.dp, 600.dp, head = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            T("3D-модель на главном экране", 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(10.dp))
    }) {
        BoxWithConstraints {
            val perRow = (maxWidth / 150.dp).toInt().coerceIn(2, 5)
            val all = listOf("" to "Автоматически") + CarModels3d
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                all.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (id, title) ->
                            val on = if (id.isEmpty()) !custom else custom && id == car3d
                            val shape = RoundedCornerShape(12.dp)
                            Column(
                                Modifier.weight(1f).clip(shape).background(if (on) C.Purple.copy(alpha = 0.22f) else C.DialogHi)
                                    .border(if (on) 2.dp else 1.dp, if (on) C.activeEdge(C.Purple) else C.Border, shape)
                                    .tapClickable {
                                        if (id.isEmpty()) {
                                            s.car3dCustom = false
                                            // back to what the ECU says, at once when it has already been seen
                                            scope.launch {
                                                val rom = s.lastRomId
                                                val known = withContext(Dispatchers.IO) { kg.forester.sg.ssm.EcuIds.car(ctx, rom) }
                                                car3dAutomatic(s.car3d, rom, known).let { if (it != s.car3d) s.car3d = it }
                                            }
                                        } else { s.car3d = id; s.car3dCustom = true }
                                        onClose()
                                    }.padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (id.isEmpty()) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
                                        Icon(Lucide.Cpu, null, tint = C.Sub, modifier = Modifier.size(34.dp))
                                    }
                                } else CarThumb(id, Modifier.fillMaxWidth().aspectRatio(1.6f))
                                Spacer(Modifier.height(4.dp))
                                FitText(title, 13.sp, Modifier.fillMaxWidth(), if (on) C.Text else C.Sub, FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarPaintChooser(ctx: Context, car3d: String) {
    val s = Repo.settings
    T("Цвет кузова", 13.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    val paintSel by Repo.carPaint.collectAsState()
    // the model's folder is listed off the main thread
    val folder by produceState(true to true, car3d) {
        value = withContext(Dispatchers.IO) { car3dAnyColour(ctx, car3d) to (car3dDir(ctx, car3d) != null) }
    }
    val anyColour = folder.first
    var picker by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarPaints.forEach { cp ->
            val on = paintSel == cp.id
            val usable = anyColour || !cp.needsWhite
            Box(
                Modifier.size(34.dp).alpha(if (usable) 1f else 0.3f).clip(CircleShape)
                    .background(cp.swatch)
                    .border(if (on) 3.dp else 1.dp, if (on) C.Violet else C.Border, CircleShape)
                    .clickable(enabled = usable) { s.carPaint = cp.id },
            )
        }
        // any colour: rainbow swatch opens the picker
        val custom = paintSel.startsWith("custom:")
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                .border(if (custom) 3.dp else 1.dp, if (custom) C.Violet else C.Border, CircleShape)
                .clickable { picker = !picker },
        )
    }
    T(carPaint(paintSel).name + if (!anyColour) " · у этой модели нет белого и серебра — выберите другую 3D-модель" else "", 11.sp, C.Sub)
    if (picker) {
        Spacer(Modifier.height(8.dp))
        ColourPicker(paintSel.removePrefix("custom:").toIntOrNull(16) ?: 0x2563EB) { rgb ->
            s.carPaint = "custom:%06X".format(rgb and 0xFFFFFF); picker = false
        }
    }
    if (!folder.second) {
        Spacer(Modifier.height(4.dp))
        T("Модель $car3d ещё готовится, пока показывается SG5.", 11.sp, C.Orange)
    }
}

/** A setting's one row: tap to open what it chooses. */
@Composable
private fun SettingRowButton(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalMinTap.current).clip(shape).background(C.idleFill(C.CardHi)).border(1.dp, C.Border, shape)
            .tapClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
    }
}

/**
 * A choice among more options than two rows of chips hold on a 7" screen: one row with the current one, a tap opens the
 * list. The options are (saved value, name).
 */
@Composable
internal fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRowButton({ open = true }) {
        Column(Modifier.weight(1f)) {
            T(title, 15.sp, maxLines = 1)
            T(options.firstOrNull { it.first == selected }?.second ?: "—", 12.sp, C.Sub, maxLines = 1)
        }
    }
    if (open) CarSheet({ open = false }, 520.dp, 600.dp, head = {
        T(title, 17.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (k, l) ->
                Chip(l, selected == k, { open = false; onPick(k) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
