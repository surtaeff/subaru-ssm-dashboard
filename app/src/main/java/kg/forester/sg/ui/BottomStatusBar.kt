package kg.forester.sg.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import kg.forester.sg.data.Repo

/** One value on the bottom bar; [bar] = with a progress bar (takes more room). */
data class BottomSlot(val id: String, val bar: Boolean)

object BottomBarCfg {
    const val MAX = 6
    const val DEFAULT = "range:1,coolant:1,battery:1,intake:0,altitude:0"
    /** The top bar, beside the car's name: short values only, no bars. */
    const val TOP_MAX = 3
    const val TOP_DEFAULT = "oil:0,tripAvg:0"

    fun parse(s: String): List<BottomSlot> = s.split(',').mapNotNull { e ->
        val f = e.trim().split(':')
        f.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { BottomSlot(it, f.getOrNull(1) == "1") }
    }.distinctBy { it.id }.take(MAX)

    /** On a small screen the factory bar has no scales: the numbers need the room. A choice of the driver's stays. */
    fun effective(saved: String, small: Boolean): String =
        if (small && saved == DEFAULT) format(parse(saved).map { it.copy(bar = false) }) else saved

    fun format(l: List<BottomSlot>) = l.joinToString(",") { "${it.id}:${if (it.bar) 1 else 0}" }
}

/**
 * The strip under every screen. What it shows is up to the driver: long press opens the editor with every value
 * of the app (and the parameters ticked on «Датчики»).
 */
@Composable
fun BottomStatusBar() {
    // a warning takes the bar's place for a few seconds (see AlertInBottomBar), then the values come back
    val alert by kg.forester.sg.service.Alerts.current.collectAsState()
    androidx.compose.animation.AnimatedContent(
        alert,
        transitionSpec = {
            (androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn()) togetherWith
                (androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut())
        },
        label = "bottomAlert",
    ) { a -> if (a != null) AlertInBottomBar(a) else BottomValues() }
}

@Composable
private fun BottomValues() {
    val cfg by Repo.bottomBar.collectAsState()
    val car = LocalCar.current
    val slots = remember(cfg, car.small) { BottomBarCfg.parse(BottomBarCfg.effective(cfg, car.small)) }
    val avail by Repo.rrAvailable.collectAsState()
    val ctx = rememberMetricCtx()
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(bottomBarHeight()).chromeLayer().panelShadow().clip(Rounded).panelFill()
            .border(1.dp, C.Border, RoundedCornerShape(14.dp))
            .onLongPressBuzz { editing = true }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slots.isEmpty()) FitText("Нижняя панель пуста: нажмите и удерживайте, чтобы выбрать показатели", car.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 2)
        slots.forEach { s ->
            val m = Metrics.of(s.id, avail)
            if (m == null) {
                // a «Датчики» parameter while the car is not connected
                BottomItem(Lucide.ListChecks, s.id.removePrefix("rr:"), Dash, null, null, Modifier.weight(0.7f))
                return@forEach
            }
            BottomSlotItem(s, m, ctx, Modifier.weight(if (s.bar) 1f else 0.7f))
        }
    }
    if (editing) BarEditor("Нижняя панель", BottomBarCfg.MAX, true, Repo.bottomBar, BottomBarCfg.DEFAULT, { Repo.settings.bottomBar = it }) { editing = false }
}

/** The bottom bar's height: a name in the secondary size over a value in the label size. */
@Composable
internal fun bottomBarHeight() = maxOf(58.dp, LocalCar.current.let { it.secondary.lineDp() + it.label.lineDp() + 6.dp })

/**
 * The values in the top bar, beside the car's name (where the logo was before it moved to the corner). Long press
 * to choose them, as on the bottom bar.
 */
@Composable
fun TopValues(modifier: Modifier, limit: Int? = null) {
    val cfg by Repo.topBar.collectAsState()
    val slots = remember(cfg) { BottomBarCfg.parse(cfg).take(BottomBarCfg.TOP_MAX) }
    val avail by Repo.rrAvailable.collectAsState()
    val ctx = rememberMetricCtx()
    var editing by remember { mutableStateOf(false) }
    // no room for any now (the cool-down plate came up): only its editor stays, it mustn't close under the driver's finger
    // squeezed (the turbo countdown, the demo pill, a bigger scale): the values that fit whole, not each cut to «16.…»
    if (limit != 0) androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
    // a value at least: a short number with its name beside the icon
    val shown = slots.take(limit ?: (maxWidth / (LocalCar.current.label.asDp() * 3.2f + 40.dp)).toInt())
    Row(
        Modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .onLongPressBuzz { editing = true }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // readable (about 4.9:1 on the header) but quieter than the values it stands in for (C.Border was ~1.3:1)
        if (slots.isEmpty()) FitText("удерживайте, чтобы вывести показатели", LocalCar.current.secondary, Modifier.weight(1f), C.Sub.copy(alpha = 0.85f), maxLines = 2)
        shown.forEach { s ->
            val m = Metrics.of(s.id, avail)
            if (m == null) BottomItem(Lucide.ListChecks, s.id.removePrefix("rr:"), Dash, null, null, Modifier.weight(1f, fill = false))
            else BottomSlotItem(s.copy(bar = false), m, ctx, Modifier.weight(1f, fill = false))
        }
    }
    }
    if (editing) BarEditor("Верхняя панель", BottomBarCfg.TOP_MAX, false, Repo.topBar, BottomBarCfg.TOP_DEFAULT, { Repo.settings.topBar = it }) { editing = false }
}

/** The bottom bar's editor, for the settings: the same one a long press on the bar opens. */
@Composable
fun BottomBarEditorDialog(onClose: () -> Unit) =
    BarEditor("Нижняя панель", BottomBarCfg.MAX, true, Repo.bottomBar, BottomBarCfg.DEFAULT, { Repo.settings.bottomBar = it }, onClose)

/** The same for the top bar. */
@Composable
fun TopBarEditorDialog(onClose: () -> Unit) =
    BarEditor("Верхняя панель", BottomBarCfg.TOP_MAX, false, Repo.topBar, BottomBarCfg.TOP_DEFAULT, { Repo.settings.topBar = it }, onClose)

private val Dash = androidx.compose.runtime.mutableStateOf("—")

/** One value of a bar on its own: a reading recomposes this value when its text or its warning changes, not the bar and the header. */
@Composable
private fun BottomSlotItem(s: BottomSlot, m: Indicator, ctx: androidx.compose.runtime.State<MetricCtx>, modifier: Modifier) {
    val v = metricSlot(ctx, m.id) { m.read(it) }
    val warn by remember(m.id) { androidx.compose.runtime.derivedStateOf { m.warn(v.value) } }
    val text = rememberDigits {
        val now = v.value
        m.text(now) + if (m.unit.isEmpty() || now == null) "" else if (m.unit.startsWith("°")) m.unit else " ${m.unit}"
    }
    val frac = if (!s.bar) null else metricSlot(ctx, m.id + ":bar") { c ->
        if (m.id == "range") {
            val tank = Repo.settings.tankL
            if (tank > 0) ((c.trip.fuelLeftL ?: 0.0) / tank).toFloat() else null
        } else m.read(c)?.let { ((it - m.min) / (m.max - m.min)).coerceIn(0f, 1f) } ?: 0f
    }
    val brush = when {
        warn -> Brush.horizontalGradient(listOf(C.Orange, C.Red))
        m.id == "range" -> C.Accent
        m.id == "battery" -> Brush.horizontalGradient(listOf(C.Green, C.Green))
        else -> Brush.horizontalGradient(listOf(C.Blue, C.Violet))
    }
    BottomItem(m.icon, m.short, text, frac, brush, modifier, warn)
}

@Composable
private fun BottomItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: androidx.compose.runtime.State<String>,
    frac: androidx.compose.runtime.State<Float?>?, brush: Brush?, modifier: Modifier, warn: Boolean = false,
) {
    val car = LocalCar.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // over the line: a triangle in place of the icon, a sign that is not only a colour
        Icon(if (warn) Lucide.TriangleAlert else icon, if (warn) "Выше нормы" else null, tint = if (warn) C.Orange else C.Sub, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        // with a bar the text keeps a steady width, so the bar doesn't hop sideways when the value gets a digit longer
        val bar by remember(frac, brush != null) {
            androidx.compose.runtime.derivedStateOf { frac?.value?.isNaN() == false && brush != null }
        }
        Column(Modifier.weight(1f, fill = false).then(if (bar) Modifier.widthIn(min = car.label.asDp() * 3.6f) else Modifier)) {
            FitText(label, car.secondary, color = C.Sub)
            FitText(value.value, car.label, color = if (warn) C.Orange else C.Text, weight = FontWeight.SemiBold, min = car.secondary)
        }
        if (frac != null && brush != null && bar) {
            Spacer(Modifier.width(10.dp))
            val share = remember(frac) { androidx.compose.runtime.derivedStateOf { frac.value ?: 0f } }
            ProgressBar(share, brush, Modifier.width(car.tap), 6.dp) { frac.value != null }
        }
    }
}

/**
 * Pick what a bar shows: the chosen values in order (move, bar on/off where [bars], remove) and everything to add.
 * [flow] is the bar's setting as stored, [store] writes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarEditor(
    title: String, max: Int, bars: Boolean, flow: kotlinx.coroutines.flow.StateFlow<String>, default: String,
    store: (String) -> Unit, onClose: () -> Unit,
) {
    val cfg by flow.collectAsState()
    val slots = BottomBarCfg.parse(BottomBarCfg.effective(cfg, LocalCar.current.small && bars)).take(max)
    val avail by Repo.rrAvailable.collectAsState()
    Repo.watchVersion.collectAsState().value
    val all = Metrics.all(avail, Repo.settings.watchIds)
    fun save(l: List<BottomSlot>) { store(BottomBarCfg.format(l)) }
    CarSheet(onClose, 860.dp, 600.dp, head = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Как было", false, { store(default) })
                Spacer(Modifier.width(8.dp))
                Chip("Готово", true, onClose)
            }
            Spacer(Modifier.height(4.dp))
    }) {
            T(if (bars) "До $max показателей. «Полоска» — шкала рядом с числом, она занимает больше места." else "До $max показателей.", 12.sp, C.Sub)
            Spacer(Modifier.height(10.dp))
            slots.forEachIndexed { i, s ->
                val m = Metrics.of(s.id, avail)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(m?.icon ?: Lucide.ListChecks, null, tint = C.Violet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    T(m?.title ?: s.id, 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    if (bars) {
                        Chip("Полоска", s.bar, { save(slots.mapIndexed { j, x -> if (j == i) x.copy(bar = !x.bar) else x }) })
                        Spacer(Modifier.width(6.dp))
                    }
                    IconTap(Lucide.ChevronUp, "Выше", { save(slots.toMutableList().also { l -> l[i] = l[i - 1].also { l[i - 1] = l[i] } }) }, tint = C.Text, enabled = i > 0)
                    IconTap(Lucide.ChevronDown, "Ниже", { save(slots.toMutableList().also { l -> l[i] = l[i + 1].also { l[i + 1] = l[i] } }) }, tint = C.Text, enabled = i < slots.size - 1)
                    IconTap(Lucide.X, "Убрать", { save(slots.filterIndexed { j, _ -> j != i }) }, tint = C.Red)
                }
            }
            Spacer(Modifier.height(10.dp))
            T(if (slots.size >= max) "Панель заполнена: уберите что-нибудь, чтобы добавить другое" else "Добавить:", 13.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            Column {
                all.groupBy { it.group }.forEach { (group, list) ->
                    T(group, 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { m ->
                            val on = slots.any { it.id == m.id }
                            Chip(m.title, on, {
                                if (on) save(slots.filter { it.id != m.id })
                                else if (slots.size < max) save(slots + BottomSlot(m.id, false))
                            }, m.icon)
                        }
                    }
                }
            }
        
    }
}

