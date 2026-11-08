package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Refill
import kg.forester.sg.data.Repo
import kg.forester.sg.data.lhNow
import kg.forester.sg.data.Trip
import kg.forester.sg.data.FuelBoard
import kg.forester.sg.data.TileCfg
import kg.forester.sg.data.fuelEffective
import kg.forester.sg.service.EcuService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class FuelTab(val title: String, val icon: ImageVector) {
    Current("Текущий", Lucide.ChartColumn),
    History("История", Lucide.FileText),
    Refills("Заправки", Lucide.Fuel),
    Style("Стиль вождения", Lucide.Gauge),
    Trips("Поездки", Lucide.Route),
}

/** What the database says, off the main thread; null until the first answer, then the last one until the next. */
@Composable
internal fun <T : Any> rememberQuery(key: Any?, query: () -> T): androidx.compose.runtime.State<T?> =
    androidx.compose.runtime.produceState<T?>(null, key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { query() }
    }

@Composable
fun FuelScreen() {
    var tab by remember { mutableStateOf(FuelTab.Current) }
    val version by Repo.dataVersion.collectAsState()
    val totals by rememberQuery(version) { Repo.db.totals() }

    val car = LocalCar.current
    val fuelSaved by Repo.fuelTiles.collectAsState()
    Column(Modifier.fillMaxSize()) {
        // the tabs take the whole width where their names wouldn't fit beside the all-time figure
        val stacked = car.secondary.value > 19f
        // a row of its own costs «Текущий» the room its cards need: there the figure is a line of the tank card, if
        // that card is on the screen
        val inCard = stacked && tab == FuelTab.Current && remember(fuelSaved, car.small) {
            FuelBoard.parse(FuelEdit.shown(fuelSaved, car.small)).any { it.id == "tank" && it.on }
        }
        Row(Modifier.fillMaxWidth().height(LocalMinTap.current + 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 6.dp) {
                // five equal tabs, with their icons where every name still fits beside its icon, else names alone
                androidx.compose.ui.layout.Layout(
                    {
                        FuelTab.entries.forEach {
                            val name = if (it == FuelTab.Style) "Стиль" else it.title
                            Chip(name, tab == it, { tab = it }, it.icon)
                            Chip(name, tab == it, { tab = it }, null)
                        }
                    },
                    Modifier.fillMaxSize(),
                ) { ms, c ->
                    val n = FuelTab.entries.size
                    val gap = 8.dp.roundToPx()
                    val slot = ((c.maxWidth - gap * (n - 1)) / n).coerceAtLeast(0)
                    val icons = (0 until n).all { ms[it * 2].maxIntrinsicWidth(c.maxHeight) <= slot }
                    val placed = (0 until n).map { ms[it * 2 + if (icons) 0 else 1].measure(androidx.compose.ui.unit.Constraints.fixed(slot, c.maxHeight)) }
                    layout(c.maxWidth, c.maxHeight) { placed.forEachIndexed { i, p -> p.place(i * (slot + gap), 0) } }
                }
            }
            if (!stacked) {
                Spacer(Modifier.width(12.dp))
                Panel(Modifier.width(car.secondary.value.dp * 16f).fillMaxHeight(), padding = 6.dp) { AllTimeAverage(totals, Modifier.fillMaxSize()) }
            }
        }
        if (stacked && !inCard) {
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth().height(car.secondary.lineDp() + 20.dp), padding = 6.dp) { AllTimeAverage(totals, Modifier.fillMaxSize(), oneLine = true) }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                FuelTab.Current -> CurrentTab(inCard, totals)
                FuelTab.History -> HistoryTab()
                FuelTab.Refills -> ScrollWhenLow(470.dp) { RefillsTab() }
                FuelTab.Style -> ScrollWhenLow(380.dp) { StyleTab() }
                FuelTab.Trips -> TripsTab()
            }
        }
    }
}

/** All the trips' average: the name over the figure, or in one line beside it. */
@Composable
private fun AllTimeAverage(sums: Pair<Double, Double>?, modifier: Modifier, oneLine: Boolean = false) {
    val car = LocalCar.current
    val figure = when {
        sums == null -> ""
        sums.first > 1 -> fmt(kg.forester.sg.data.DistanceUnits.consumption(sums.second / sums.first * 100)) + " " + kg.forester.sg.data.DistanceUnits.consumptionUnitLong
        // no mileage yet: show what was burned instead of a dash
        else -> fmt(sums.second, 1) + " л · пробега пока нет"
    }
    // the panel is low: the "i" sits at the end of the row, not in a header row
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (oneLine) {
            FitText("Средний расход за всё время", car.secondary, Modifier.weight(1f), C.Sub)
            Spacer(Modifier.width(8.dp))
            T(figure, car.secondary, weight = FontWeight.Bold, maxLines = 1)
        } else Column(Modifier.weight(1f)) {
            FitText("Средний за всё время", car.secondary, Modifier.fillMaxWidth(), C.Sub)
            FitText(figure, car.secondary, Modifier.fillMaxWidth(), weight = FontWeight.Bold)
        }
        InfoButton("Средний расход (весь период)")
    }
}

/** Cards of the «Текущий» tab, for the «Добавить» menu. */
val FuelCards: CardNames = linkedMapOf(
    "inst" to ("Текущий расход" to Lucide.Fuel),
    "tank" to ("Остаток и средний расход" to Lucide.Milestone),
    "chart" to ("Динамика расхода" to Lucide.ChartColumn),
    "trip" to ("Информация о поездке" to Lucide.Timer),
    "modes" to ("Расход по режимам" to Lucide.ChartPie),
    "recent" to ("Последние поездки" to Lucide.Route),
)

/** Row heights: the gauge, the tank and the chart a little taller than the trip's row, as in the fixed layout. */
private fun fuelRowWeight(t: TileCfg) = if (t.id == "inst" || t.id == "tank" || t.id == "chart") 1.2f else 1f

internal val FuelEdit = BoardEdit(FuelBoard, rowWeight = ::fuelRowWeight, effective = ::fuelEffective)

/** An editable board, as «Динамика» and «АКПП»: long press on a card to move, size, remove or add. */
@Composable
private fun CurrentTab(allTimeHere: Boolean, totals: Pair<Double, Double>?) {
    val ts by Repo.trip.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    BoardScreen(FuelEdit, FuelCards, ::fuelRowWeight) { cfg, m ->
        when (cfg.id) {
            "inst" -> InstCard(ts, m)
            "tank" -> TankCard(ts, allTimeHere, totals, m)
            "chart" -> ChartPanel(version, m)
            "trip" -> TripInfoPanel(ts.trip, m)
            "modes" -> ModesPanel(ts.trip, m)
            "recent" -> RecentTripsPanel(version, m)
        }
    }
}

/** How wide a text comes out as this theme writes it (translated, in its case and face): to lay out by what really fits. */
@Composable
private fun rememberTextWidth(): (String, TextUnit, Boolean) -> Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val look = C.palette
    return remember(measurer, density, look) {
        { text, size, bold ->
            val shown = look.cased(kg.forester.sg.data.tr(text))
            val style = look.textStyle(
                androidx.compose.ui.text.TextStyle(fontSize = size, fontWeight = look.weight(if (bold) FontWeight.Bold else FontWeight.Normal, size.value)), shown, C.Sub,
            )
            with(density) { measurer.measure(shown, style, maxLines = 1, softWrap = false).size.width.toDp() }
        }
    }
}

/**
 * The first of [content]'s children whose line fits the width, else the last one (cut at its end): a long name and its
 * short form, a line of figures and a shorter one, for what the parked text size leaves room for. Only the one picked
 * is measured and drawn. [fill]: it takes the whole width (a row with its figure at the end).
 */
@Composable
private fun FirstThatFits(modifier: Modifier = Modifier, fill: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { ms, c ->
        if (ms.isEmpty()) return@Layout layout(c.minWidth, c.minHeight) {}
        // a little to spare: a slanted face's last letter reaches past its advance
        val spare = 4.dp.roundToPx()
        val pick = ms.firstOrNull { !c.hasBoundedWidth || it.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) + spare <= c.maxWidth } ?: ms.last()
        val p = pick.measure(if (fill && c.hasBoundedWidth) c.copy(minWidth = c.maxWidth) else c.copy(minWidth = 0))
        val w = p.width.coerceIn(c.minWidth, c.maxWidth)
        val h = p.height.coerceIn(c.minHeight, c.maxHeight)
        layout(w, h) { p.place(0, (h - p.height) / 2) }
    }
}

/** Children one under another, [gap] apart, as many as fit the height whole: the rest is left out, not cut in half. */
@Composable
private fun WhileTheyFit(modifier: Modifier = Modifier, gap: Dp = 5.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { ms, c ->
        val g = gap.roundToPx()
        val loose = c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val placed = ArrayList<androidx.compose.ui.layout.Placeable>()
        var y = 0
        for (m in ms) {
            val p = m.measure(loose)
            if (c.hasBoundedHeight && y + p.height > c.maxHeight) break
            placed += p
            y += p.height + g
        }
        val h = if (c.hasBoundedHeight) c.maxHeight else (y - g).coerceAtLeast(0)
        layout(c.maxWidth.takeIf { c.hasBoundedWidth } ?: placed.maxOfOrNull { it.width } ?: 0, h.coerceAtLeast(c.minHeight)) {
            var at = 0
            placed.forEach { it.place(0, at); at += it.height + g }
        }
    }
}

/**
 * A card's name in one line, as [PanelTitle] writes it: the short form where the whole one doesn't fit, then without the
 * icon; [trailing] and the «i» at the end. (PanelTitle wraps a long name to a second line, which the cards here can't spare.)
 */
@Composable
private fun FuelTitle(icon: ImageVector, title: String, short: String? = null, info: String = title, trailing: @Composable () -> Unit = {}) {
    val p = C.palette
    val band = p.titleBand
    val tracking = if (p.titleTracking > 0f) androidx.compose.ui.unit.TextUnit(p.titleTracking, androidx.compose.ui.unit.TextUnitType.Em) else TextUnit.Unspecified
    @Composable
    fun name(text: String, withIcon: Boolean) = Row(
        if (band != null) Modifier.background(band).padding(horizontal = 8.dp, vertical = 3.dp) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withIcon) {
            Icon(icon, null, tint = if (band != null) Color.White else C.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        T(p.titled(text), 17.sp, C.titleColor(C.Text), FontWeight.SemiBold, maxLines = 1, tracking = tracking)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FirstThatFits(Modifier.weight(1f)) {
            name(title, true)
            if (short != null) name(short, true)
            name(short ?: title, false)
        }
        trailing()
        InfoButton(title, info)
    }
}

/** A number in bold at the label size with its unit beside it at the secondary size: both read at a glance. */
@Composable
private fun Figure(value: String, unit: String, color: Color = C.Text, size: TextUnit = LocalCar.current.label) {
    val car = LocalCar.current
    Row {
        T(value, size, color, FontWeight.Bold, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            T(unit, car.secondary, C.Sub, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

/** A line of the tank card: the name and the figure in one line where they fit, the figure under the name where not. */
@Composable
private fun StatLine(
    label: String, value: String, unit: String, title: String, info: String, size: TextUnit = LocalCar.current.label,
    /** The unit in short («л/100» for «л/100 км»), tried before the figure goes under the name. */
    shortUnit: String? = null,
) {
    val car = LocalCar.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FirstThatFits(Modifier.weight(1f), fill = true) {
            @Composable
            fun oneLine(u: String) = Row(verticalAlignment = Alignment.CenterVertically) {
                T(label, car.secondary, C.Sub, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Spacer(Modifier.weight(1f))
                Figure(value, u, size = size)
            }
            oneLine(unit)
            if (shortUnit != null && shortUnit != unit) oneLine(shortUnit)
            Column {
                T(label, car.secondary, C.Sub, maxLines = 1)
                Figure(value, unit, size = size)
            }
        }
        InfoButton(title, info)
    }
}

private fun allTimeFigure(sums: Pair<Double, Double>?): Pair<String, String> = when {
    sums == null -> "" to ""
    sums.first > 1 -> fmt(kg.forester.sg.data.DistanceUnits.consumption(sums.second / sums.first * 100)) to kg.forester.sg.data.DistanceUnits.consumptionUnitLong
    else -> fmt(sums.second, 1) to "л"
}

/** The consumption now: the ring in l/100 km on the move (l/h standing) and the l/h under it. */
@Composable
private fun InstCard(ts: kg.forester.sg.data.TripState, modifier: Modifier) {
    val car = LocalCar.current
    Panel(modifier, padding = 12.dp) {
        FuelTitle(Lucide.Fuel, "Текущий расход", "Сейчас")
        val per100 = ts.instPer100
        RingGauge(
            ((per100 ?: 0.0) / 20.0).toFloat(),
            if (per100 != null) fmt(kg.forester.sg.data.DistanceUnits.consumption(per100)) else fmt(ts.lhNow),
            // standing: the ring is in l/h and the line under it says so («л/ч · стоянка» was cut inside the ring)
            if (per100 != null) kg.forester.sg.data.DistanceUnits.consumptionUnitLong else "л/ч",
            Modifier.weight(1f).fillMaxWidth(),
        )
        FirstThatFits(Modifier.align(Alignment.CenterHorizontally)) {
            if (per100 != null) {
                T("Мгновенный ${fmt(ts.lhNow)} л/ч", car.secondary, C.Sub, maxLines = 1)
                T("${fmt(ts.lhNow)} л/ч", car.secondary, C.Sub, maxLines = 1)
            } else T("На стоянке", car.secondary, C.Sub, maxLines = 1)
        }
        // an ECU without a usable MAF: say how the number comes about (or why there is none)
        val live by Repo.live.collectAsState()
        if ((live.rpm ?: 0) > 300 && live.mafGs == null && live.fuelRateLh == null) {
            val inj = kg.forester.sg.service.FuelCalc.source == "inj"
            // a sports ECU on the CAN usually runs on MAP with no MAF at all: that is how it works, not a fault
            val (note, color) = if (inj && kg.forester.sg.data.CanEcu.active)
                "Расход по времени впрыска (у Link нет MAF). Точнее станет после 1–2 заправок до полного" to C.Sub
            else (if (inj) "ЭБУ не отдаёт MAF: расход по форсункам, уточнится по заправкам"
                else "ЭБУ не отдаёт ни MAF, ни время впрыска: расход не считается") to C.Orange
            T(note, car.secondary, color, maxLines = 3)
        }
    }
}

/** What is in the tank, how far it goes, the trip's average and (where the header has no room for it) the all-time one. */
@Composable
private fun TankCard(ts: kg.forester.sg.data.TripState, allTimeHere: Boolean, totals: Pair<Double, Double>?, modifier: Modifier) {
    val car = LocalCar.current
    val trip = ts.trip
    val tank = Repo.settings.tankL
    val live by Repo.live.collectAsState()
    Panel(modifier, padding = 12.dp) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // the figures at the label size where the lines fit so; on a low card (a 7" screen) at the secondary one,
            // and the line of what went since the refill goes first
            val lines = if (allTimeHere) 4 else 3
            val bar = if (tank > 0) 14.dp else 0.dp
            // a line of the theme's type is up to 1.4 of its size (the «i» beside a secondary one is about that too)
            val labelLine = car.label.asDp() * 1.4f
            val secondaryLine = car.secondary.asDp() * 1.4f
            fun line(sz: TextUnit) = if (sz == car.label) labelLine else secondaryLine
            val size = if (line(car.label) * lines + bar + line(car.secondary) <= maxHeight) car.label else car.secondary
            val withNote = line(size) * lines + bar + line(car.secondary) <= maxHeight
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                StatLine("Осталось", fmt(ts.fuelLeftL, 0), "л", if (ts.fuelFromSensor) "Осталось (датчик)" else "Осталось", "Осталось топлива", size)
                if (tank > 0) {
                    val frac = ((ts.fuelLeftL ?: 0.0) / tank).toFloat()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressBar(frac, C.Accent, Modifier.weight(1f), 6.dp)
                        Spacer(Modifier.width(8.dp))
                        T("${(frac * 100).toInt()}%", car.secondary, C.Sub, maxLines = 1)
                    }
                }
                if (withNote) {
                    val since = "с заправки ${fmt(Repo.settings.usedSinceRefillL)} л"
                    val sensor = live.fuelSensorV?.let { "датчик ${"%.2f".format(it)} В" }
                        ?: live.fuelSensorOhm?.let { "датчик ${"%.0f".format(it)} Ом" }
                    FirstThatFits {
                        if (sensor != null) T("$since · $sensor", car.secondary, C.Sub, maxLines = 1)
                        T(since, car.secondary, C.Sub, maxLines = 1)
                    }
                }
                StatLine("Запас хода", fmt(kg.forester.sg.data.DistanceUnits.distance(ts.rangeKm), 0), kg.forester.sg.data.DistanceUnits.distanceUnit, "Запас хода", "Запас хода", size)
                if (trip != null && trip.per100 == null && trip.durationS > 30) {
                    StatLine("На стоянке", fmt(trip.fuelL / (trip.durationS / 3600.0)), "л/ч", "Средний (стоянка)", "Средний (стоянка)", size)
                } else {
                    StatLine("За поездку", fmt(kg.forester.sg.data.DistanceUnits.consumption(trip?.per100)), kg.forester.sg.data.DistanceUnits.consumptionUnitLong, "Средний (поездка)", "Средний (поездка)", size, kg.forester.sg.data.DistanceUnits.consumptionUnit)
                }
                if (allTimeHere) {
                    val (v, u) = allTimeFigure(totals)
                    StatLine("За всё время", v, u, "Средний расход (весь период)", "Средний расход (весь период)", size, kg.forester.sg.data.DistanceUnits.consumptionUnit.takeIf { u != "л" })
                }
            }
        }
    }
}

private enum class Range(val title: String, val spanMs: Long, val stepMs: Long) {
    M10("10 мин", 10 * 60_000L, 10_000L),
    H1("1 час", 3600_000L, 60_000L),
    Today("Сегодня", 0, 10 * 60_000L),
    Week("Неделя", 7 * 86_400_000L, 3 * 3600_000L),
}

/** The chart's points (L/100 per step, null = a gap), the average over the range and its start. */
private fun chartData(range: Range): Triple<List<Float?>, Float?, Long> {
    val now = System.currentTimeMillis()
    val from = if (range == Range.Today) startOfDay(now) else now - range.spanMs
    val off = java.util.TimeZone.getDefault().getOffset(now).toLong()
    val buckets = Repo.db.buckets(from, range.stepMs, off)
    val byTime = buckets.associateBy { it.timeMs }
    val values = ArrayList<Float?>()
    // a point needs some distance behind it (≥ 10 km/h average over the step, at most 1 km): a few metres
    // while pulling away would read hundreds of L/100. Idle and no-data steps are gaps, not zeros.
    val minKm = maxOf(minOf(range.stepMs / 3_600_000.0 * 10.0, 1.0), 0.02)
    var t = (from + off) / range.stepMs * range.stepMs - off
    while (t <= now) {
        val b = byTime[t]
        values += if (b != null && b.km >= minKm) (b.fuelL / b.km * 100).toFloat().coerceAtMost(30f) else null
        t += range.stepMs
    }
    val km = buckets.sumOf { it.km }
    val l = buckets.sumOf { it.fuelL }
    return Triple(values, if (km > 0.2) (l / km * 100).toFloat() else null, from)
}

@Composable
private fun ChartPanel(version: Long, modifier: Modifier) {
    var range by remember { mutableStateOf(Range.H1) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    // off the main thread (every 10 s, while the service writes to the same database): the panel stands empty until the
    // first result, and the chart keeps the last one until the new one is in
    var data by remember { mutableStateOf(Triple(emptyList<Float?>(), null as Float?, 0L)) }
    LaunchedEffect(range, version, tick) { data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { chartData(range) } }
    Panel(modifier, padding = 12.dp) {
        val car = LocalCar.current
        @Composable
        fun chips() = Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Range.entries.forEach { r ->
                // the chip keeps its look and its place in the title row; the area that takes the tap is 48 dp tall
                TapTarget({ range = r }) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (r == range) C.selectFill(C.Purple.copy(alpha = 0.3f)) else C.idleFill(C.CardHi))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) { T(r.title, 13.sp, if (r == range) C.selectText(C.Text) else C.Sub, maxLines = 1) }
                }
            }
        }
        val avg = "- - Средний ${fmtF(data.second?.let { kg.forester.sg.data.DistanceUnits.consumption(it.toDouble()).toFloat() })}"
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // the periods beside the name on a wide card; on its own row otherwise (squeezed beside it, at a larger text,
            // they went a letter a line)
            val inTitle = maxWidth >= car.secondary.asDp() * 40f
            val line = car.secondary.lineDp()
            Column(Modifier.fillMaxSize()) {
                FuelTitle(Lucide.ChartColumn, "Динамика расхода", "Динамика") { if (inTitle) chips() }
                if (!inTitle) {
                    Spacer(Modifier.height(6.dp))
                    chips()
                }
                Spacer(Modifier.height(6.dp))
                FirstThatFits(Modifier.fillMaxWidth(), fill = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(kg.forester.sg.data.DistanceUnits.consumptionUnitLong, car.secondary, C.Sub, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(C.Violet))
                        Spacer(Modifier.width(5.dp))
                        T("Сейчас", car.secondary, C.Sub, maxLines = 1)
                        Spacer(Modifier.width(14.dp))
                        T(avg, car.secondary, C.Sub, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(kg.forester.sg.data.DistanceUnits.consumptionUnitLong, car.secondary, C.Sub, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Spacer(Modifier.weight(1f))
                        T(avg, car.secondary, C.Sub, maxLines = 1)
                    }
                    T(avg, car.secondary, C.Sub, maxLines = 1)
                }
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                    // the scale's four lines where they have the room, its ends where they don't (they ran into each other)
                    val marks = if (kg.forester.sg.data.DistanceUnits.miles) listOf("60", "40", "20", "0") else listOf("30", "20", "10", "0")
                    val shown = if (maxHeight >= line * 4.4f) marks else listOf(marks.first(), marks.last())
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxHeight().width(roomFor(26.dp, 11f)), verticalArrangement = Arrangement.SpaceBetween) {
                            shown.forEach { T(it, 11.sp, C.Sub, maxLines = 1) }
                        }
                        AreaChart(
                            data.first.map { v -> v?.let { kg.forester.sg.data.DistanceUnits.consumption(it.toDouble()).toFloat() } }, kg.forester.sg.data.DistanceUnits.top(kg.forester.sg.data.DistanceUnits.Kind.CONSUMPTION, 30f),
                            data.second?.let { kg.forester.sg.data.DistanceUnits.consumption(it.toDouble()).toFloat() }, Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** A figure of the trip card: [label] or, where it doesn't fit, [short]; [info] has the whole story under the «i». */
private class TripStat(val icon: ImageVector, val label: String, val short: String?, val value: String, val unit: String, val info: String)

@Composable
private fun TripInfoPanel(trip: Trip?, modifier: Modifier) {
    val ctx = LocalContext.current
    val price = Repo.settings.pricePerL
    val car = LocalCar.current
    Panel(modifier, padding = 12.dp) {
        FuelTitle(Lucide.Timer, "Информация о поездке", "О поездке") {
            TapTarget({ EcuService.start(ctx, EcuService.ACTION_RESET_TRIP) }) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi).padding(horizontal = 10.dp, vertical = 3.dp)
                ) { T("Сбросить", 13.sp, C.Sub, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        val dur = trip?.durationS ?: 0
        val (dv, du) = if (dur < 3600) "${dur / 60}" to "мин" else "%d:%02d".format(dur / 3600, dur / 60 % 60) to "ч"
        val du0 = kg.forester.sg.data.DistanceUnits
        val stats = listOf(
            TripStat(Lucide.Clock, "В пути", null, if (trip == null) "—" else dv, du, "В пути"),
            TripStat(Lucide.Activity, "Пробег", null, fmt(du0.distance(trip?.distanceKm)), du0.distanceUnit, "Пробег"),
            TripStat(Lucide.Gauge, "Ср. скорость", "Скорость", fmt(du0.speed(trip?.avgSpeed), 0), du0.speedUnit, "Ср. скорость"),
            if (trip != null && trip.per100 == null && trip.durationS > 30)
                TripStat(Lucide.Fuel, "Ср. расход", "Расход", fmt(trip.fuelL / (trip.durationS / 3600.0)), "л/ч", "Ср. расход")
            else TripStat(Lucide.Fuel, "Ср. расход", "Расход", fmt(du0.consumption(trip?.per100)), du0.consumptionUnit, "Ср. расход"),
            TripStat(Lucide.Droplet, "Топливо", null, fmt(trip?.fuelL), "л", "Топливо"),
            TripStat(Lucide.Banknote, "Стоимость", "Сумма", fmt(trip?.fuelL?.times(price), 0), Repo.settings.currency, "Стоимость"),
        )
        // what the names and the figures take across, as this theme writes them: the tiles are laid out by it
        val measure = rememberTextWidth()
        fun width(text: String, size: TextUnit, bold: Boolean = false) = measure(text, size, bold)
        fun nameW(st: TripStat) = minOf(width(st.label, car.secondary), st.short?.let { width(it, car.secondary) } ?: Dp.Infinity)
        // a figure as long as «88.8» at least, so the tiles don't change their layout as the numbers grow
        fun figureW(st: TripStat) = width(if (st.value.length > 4) st.value else "88.8", (car.secondary.value * 1.25f).sp, true) + 5.dp + width(st.unit, car.secondary)
        val nameW = stats.maxOf(::nameW)
        val figureW = stats.maxOf(::figureW)
        val pairW = stats.maxOf { nameW(it) + figureW(it) }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = 6.dp
            // a tile holds a line of the name and one of the figure
            val needH = car.secondary.lineDp() * 2f + 6.dp
            val needW = maxOf(nameW + 34.dp, figureW + 16.dp)
            fun tileW(cols: Int) = (maxWidth - gap * (cols - 1)) / cols
            fun tileH(cols: Int) = ((stats.size + cols - 1) / cols).let { rows -> (maxHeight - gap * (rows - 1)) / rows }
            // three across as before; in one row on a wide low card, in two columns on a narrow tall one. Where none
            // has the room, the names come first: the widest tiles that are still the tallest
            val options = listOf(3, 6, 2)
            // a card too low for two lines a tile (a 7" screen), but wide: the name and the figure in one line
            val oneLineW = pairW + 40.dp
            val oneLineH = car.secondary.lineDp() + 8.dp
            val two = options.firstOrNull { tileW(it) >= needW && tileH(it) >= needH }
            val one = if (two == null) listOf(2, 3, 1).firstOrNull { tileW(it) >= oneLineW && tileH(it) >= oneLineH } else null
            val cols = two ?: one
                ?: options.filter { tileW(it) >= needW }.maxByOrNull { tileH(it) }
                ?: 2
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                stats.chunked(cols).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { if (one != null) StatLineTile(it, Modifier.weight(1f).fillMaxHeight()) else StatTile(it, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The name and the figure in one line, the figure as large as the tile's height lets it be. */
@Composable
private fun StatLineTile(s: TripStat, modifier: Modifier) {
    val car = LocalCar.current
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the figure keeps its room, the name takes what is left (its short form where the whole one doesn't fit)
        FirstThatFits(Modifier.weight(1f).padding(end = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(s.icon, null, tint = C.Sub, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                T(s.label, car.secondary, C.Sub, maxLines = 1)
            }
            T(s.label, car.secondary, C.Sub, maxLines = 1)
            if (s.short != null) T(s.short, car.secondary, C.Sub, maxLines = 1)
        }
        FitText(s.value, car.label, weight = FontWeight.Bold)
        if (s.unit.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            T(s.unit, car.secondary, C.Sub, maxLines = 1)
        }
        InfoButton(s.label, s.info)
    }
}

/** The name over the figure; the «i» in the corner, so the name and the figure keep the tile's width. */
@Composable
private fun StatTile(s: TripStat, modifier: Modifier) {
    val car = LocalCar.current
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
        Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
            FirstThatFits(Modifier.padding(end = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.icon, null, tint = C.Sub, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    T(s.label, car.secondary, C.Sub, maxLines = 1)
                }
                T(s.label, car.secondary, C.Sub, maxLines = 1)
                if (s.short != null) T(s.short, car.secondary, C.Sub, maxLines = 1)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FitText(s.value, car.label, Modifier.weight(1f, fill = false), weight = FontWeight.Bold)
                if (s.unit.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    T(s.unit, car.secondary, C.Sub, maxLines = 1)
                }
            }
        }
        InfoButton(s.label, s.info, Modifier.align(Alignment.TopEnd))
    }
}

/** A mode of the modes card: its share of the fuel, its consumption in full and in short. */
private class ModeLine(val name: String, val short: String?, val color: Color, val detail: String, val brief: String, val figure: String, val pct: Int)

@Composable
private fun ModesPanel(trip: Trip?, modifier: Modifier) {
    // Current trip if it has data, otherwise all trips. Shares are by fuel burned.
    val version by Repo.dataVersion.collectAsState()
    val all = rememberQuery(version) { Repo.db.trips(500) }.value ?: emptyList()
    val src = if (trip != null && trip.fuelL > 0.2) listOf(trip) else all
    // Trips recorded before the idle mode existed put parked fuel into "city": treat 0-km trips as parked.
    val driven = src.filter { it.cityKm + it.hwyKm + it.mixKm >= 0.05 }
    val parked = src.filter { it.cityKm + it.hwyKm + it.mixKm < 0.05 }
    val cityKm = driven.sumOf { it.cityKm }; val cityL = driven.sumOf { it.cityL }
    val hwyKm = driven.sumOf { it.hwyKm }; val hwyL = driven.sumOf { it.hwyL }
    val mixKm = driven.sumOf { it.mixKm }; val mixL = driven.sumOf { it.mixL }
    val idleL = driven.sumOf { it.idleL } + parked.sumOf { it.fuelL }
    val idleS = driven.sumOf { it.idleS } + parked.sumOf { it.durationS.toDouble() }
    val totalL = cityL + hwyL + mixL + idleL
    val totalKm = cityKm + hwyKm + mixKm
    val units = kg.forester.sg.data.DistanceUnits
    fun per(km: Double, l: Double) = if (km > 0.3) fmt(units.consumption(l / km * 100)) else "—"
    fun pct(l: Double) = if (totalL > 0) (l / totalL * 100).toInt() else 0
    val idleLh = if (idleS > 60) fmt(idleL / idleS * 3600) else "—"
    val modes = listOf(
        ModeLine("Город", null, C.Violet, "${per(cityKm, cityL)} ${units.consumptionUnitLong}", "${per(cityKm, cityL)} ${units.consumptionUnit}", per(cityKm, cityL), pct(cityL)),
        ModeLine("Трасса", null, C.Blue, "${per(hwyKm, hwyL)} ${units.consumptionUnitLong}", "${per(hwyKm, hwyL)} ${units.consumptionUnit}", per(hwyKm, hwyL), pct(hwyL)),
        ModeLine("Смешанный", "Смеш.", C.Gray, "${per(mixKm, mixL)} ${units.consumptionUnitLong}", "${per(mixKm, mixL)} ${units.consumptionUnit}", per(mixKm, mixL), pct(mixL)),
        ModeLine("Стоянка", null, C.Orange, "${fmt(idleL, 2)} л · $idleLh л/ч", "$idleLh л/ч", idleLh, pct(idleL)),
    )
    val measure = rememberTextWidth()
    val car = LocalCar.current
    Panel(modifier, padding = 12.dp) {
        FuelTitle(Lucide.ChartPie, "Расход по режимам", "По режимам")
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val line = car.secondary.lineDp()
            // the name over its consumption where four such pairs fit the height, one line each where they don't
            val two = maxHeight >= line * 8f + 18.dp
            // the ring where it leaves the lines their room beside it
            val ring = minOf(maxHeight, 124.dp)
            val withRing = ring >= 84.dp && maxWidth - ring - 12.dp >= car.secondary.asDp() * 9.5f
            val roomW = maxWidth
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (withRing) {
                    Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                        Donut(
                            listOf(cityL.toFloat() to C.Violet, hwyL.toFloat() to C.Blue, mixL.toFloat() to C.Gray, idleL.toFloat() to C.Orange),
                            Modifier.fillMaxSize()
                        )
                        Column(Modifier.widthIn(max = ring - 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (totalKm > 0.3) {
                                FitText(fmt(units.consumption(totalL / totalKm * 100)), car.label, weight = FontWeight.Bold)
                                FirstThatFits {
                                    T(units.consumptionUnitLong, car.secondary, C.Sub, maxLines = 1)
                                    T(units.consumptionUnit, car.secondary, C.Sub, maxLines = 1)
                                }
                            } else {
                                FitText(fmt(totalL, 2), car.label, weight = FontWeight.Bold)
                                FirstThatFits {
                                    T("литров", car.secondary, C.Sub, maxLines = 1)
                                    T("л", car.secondary, C.Sub, maxLines = 1)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                // one line each: the same parts in every row (a row of its own choice read as a different table), the
                // most that fits: the name, the consumption with its unit, without it, or the share alone
                val rowsW = roomW - (if (withRing) ring + 12.dp else 0.dp) - 17.dp
                val sp = car.secondary
                fun need(m: ModeLine, level: Int): Dp {
                    val name = if (level % 2 == 1) m.short ?: m.name else m.name
                    val figure = when (level / 2) { 0 -> m.brief; 1 -> m.figure; else -> null }
                    return measure(name, sp, false) + 8.dp + (figure?.let { measure(it, sp, false) + 10.dp } ?: 0.dp) + measure("${m.pct}%", sp, true)
                }
                val level = (0..5).firstOrNull { l -> modes.all { need(it, l) <= rowsW } } ?: 5
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    modes.forEach { ModeRow(it, two, level) }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(m: ModeLine, two: Boolean, level: Int) {
    val car = LocalCar.current
    val pctColor = if (m.color == C.Gray) C.Sub else m.color
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(m.color))
        Spacer(Modifier.width(8.dp))
        if (two) Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FirstThatFits(Modifier.weight(1f)) {
                    T(m.name, car.secondary, maxLines = 1)
                    if (m.short != null) T(m.short, car.secondary, maxLines = 1)
                }
                T("${m.pct}%", car.secondary, pctColor, FontWeight.SemiBold, maxLines = 1)
            }
            FirstThatFits {
                T(m.detail, car.secondary, C.Sub, maxLines = 1)
                T(m.brief, car.secondary, C.Sub, maxLines = 1)
            }
        } else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // the name, its consumption and its share, as much of it as [level] says every row has room for
            val figure = when (level / 2) { 0 -> m.brief; 1 -> m.figure; else -> null }
            T(if (level % 2 == 1) m.short ?: m.name else m.name, car.secondary, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
            if (figure != null) {
                T(figure, car.secondary, C.Sub, maxLines = 1)
                Spacer(Modifier.width(10.dp))
            }
            T("${m.pct}%", car.secondary, pctColor, FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun RecentTripsPanel(version: Long, modifier: Modifier) {
    val read = rememberQuery(version) { Repo.db.trips(6) }.value
    val trips = read ?: emptyList()
    val car = LocalCar.current
    Panel(modifier, padding = 12.dp) {
        FuelTitle(Lucide.Route, "Последние поездки", "Поездки")
        Spacer(Modifier.height(6.dp))
        if (read != null && trips.isEmpty()) T("Пока нет поездок", car.secondary, C.Sub)
        var open by remember { mutableStateOf<Trip?>(null) }
        // as many as the card holds whole
        WhileTheyFit(Modifier.weight(1f).fillMaxWidth()) { trips.forEach { TripRow(it) { open = it } } }
        open?.let { TripDialog(it) { open = null } }
    }
}


/** The CSV logs that overlap [t]: started (with the engine) before its end and written to after its start. */
private fun tripLogs(ctx: android.content.Context, t: Trip): List<java.io.File> {
    val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    return java.io.File(ctx.getExternalFilesDir(null), "logs").listFiles().orEmpty().filter { f ->
        val start = runCatching { fmt.parse(f.name.removeSuffix(".csv"))?.time }.getOrNull() ?: return@filter false
        start <= t.endMs && f.lastModified() >= t.startMs + 10_000L
    }.sortedBy { it.name }
}

/** Everything about one trip, with its CSV log as charts if it is still kept. */
@Composable
private fun TripDialog(t: Trip, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val logs = remember(t) { tripLogs(ctx, t) }
    var viewing by remember { mutableStateOf<java.io.File?>(null) }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val d = t.durationS
    val parked = t.per100 == null
    val cur = Repo.settings.currency
    CarAlertDialog(
        onDismissRequest = onClose,
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text, textScrolls = false,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(C.Blue.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(if (parked) Lucide.SquareParking else Lucide.Route, null, tint = C.Blue, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    T(if (parked) "Стоянка с мотором" else "Поездка", 20.sp, weight = FontWeight.SemiBold)
                    T("${dayLabel(t.startMs)} · ${hm.format(Date(t.startMs))}–${hm.format(Date(t.endMs))} · " +
                        if (d < 3600) "${d / 60} мин" else "%d ч %02d мин".format(d / 3600, d / 60 % 60), 13.sp, C.Sub)
                }
            }
        },
        text = {
            Column(Modifier.cueScroll(C.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun row(vararg items: Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String>) =
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { (icon, label, value) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, tint = C.Sub, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    T(label, 11.sp, C.Sub, maxLines = 1)
                                }
                                T(value, 17.sp, weight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                row(
                    Triple(Lucide.Activity, "Пробег", "${fmt(kg.forester.sg.data.DistanceUnits.distance(t.distanceKm))} ${kg.forester.sg.data.DistanceUnits.distanceUnit}"),
                    Triple(Lucide.Droplet, "Топливо", "${fmt(t.fuelL, 2)} л"),
                    Triple(Lucide.Fuel, "Расход", t.per100?.let { "${fmt(kg.forester.sg.data.DistanceUnits.consumption(it))} ${kg.forester.sg.data.DistanceUnits.consumptionUnit}" } ?: if (d > 30) "${fmt(t.fuelL / (d / 3600.0))} л/ч" else "—"),
                )
                row(
                    Triple(Lucide.Gauge, "Средняя скорость", if (parked) "—" else "${fmt(kg.forester.sg.data.DistanceUnits.speed(t.avgSpeed), 0)} ${kg.forester.sg.data.DistanceUnits.speedUnit}"),
                    Triple(Lucide.Banknote, "Стоимость", "${fmt(t.fuelL * Repo.settings.pricePerL, 0)} $cur"),
                    Triple(Lucide.Clock, "На холостых", "${(t.idleS / 60).toInt()} мин · ${fmt(t.idleL, 2)} л"),
                )
                row(
                    Triple(Lucide.Gauge, "Макс. обороты", if (t.maxRpm > 0) "${t.maxRpm}" else "—"),
                    Triple(Lucide.Fan, "Макс. наддув", if (t.maxBoostKpa > 0) "%.2f бар".format(t.maxBoostKpa / 100) else "—"),
                    Triple(Lucide.Zap, "Резкие разгоны / торм.", "${t.hardAccel} / ${t.hardBrake}"),
                )
                if (!parked) {
                    T("По режимам", 13.sp, C.Violet, FontWeight.SemiBold)
                    listOf(Triple("Город", t.cityKm, t.cityL), Triple("Трасса", t.hwyKm, t.hwyL), Triple("Смешанный", t.mixKm, t.mixL))
                        .filter { it.second > 0.05 }.forEach { (name, km, l) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T(name, 13.sp, modifier = Modifier.width(roomFor(100.dp, 13f)), maxLines = 1)
                                T("${fmt(kg.forester.sg.data.DistanceUnits.distance(km))} ${kg.forester.sg.data.DistanceUnits.distanceUnit}", 13.sp, C.Sub, modifier = Modifier.width(roomFor(80.dp, 13f)), maxLines = 1)
                                T(if (km > 0.3) "${fmt(kg.forester.sg.data.DistanceUnits.consumption(l / km * 100))} ${kg.forester.sg.data.DistanceUnits.consumptionUnitLong}" else "${fmt(l, 2)} л", 13.sp, weight = FontWeight.SemiBold)
                            }
                        }
                }
                T("Лог поездки", 13.sp, C.Violet, FontWeight.SemiBold)
                if (logs.isEmpty()) T("Подробный лог уже удалён или не писался (включается на экране «Лог»).", 12.sp, C.Sub)
                logs.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.FileSpreadsheet, null, tint = C.Sub, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        T(f.name, 12.sp, C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
                        Chip("Графики", false, { viewing = f }, Lucide.ChartLine)
                        Spacer(Modifier.width(4.dp))
                        LogFileActions(f, small = true)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClose) { Text("Закрыть", color = C.Violet) } },
    )
    viewing?.let { f ->
        CarDialog({ viewing = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(C.Bg).padding(12.dp)) { LogViewer(f) { viewing = null } }
        }
    }
}

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    val d = t.durationS
    val parked = t.per100 == null
    val car = LocalCar.current
    val units = kg.forester.sg.data.DistanceUnits
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // the mode's icon only where the figures keep their room beside it
        val withIcon = maxWidth >= car.secondary.asDp() * 15f
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi).clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val value = if (parked) fmt(t.fuelL, 2) else fmt(units.consumption(t.per100))
                @Composable
                fun figure(unit: String) = Row(verticalAlignment = Alignment.Bottom) {
                    T(value, car.label, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(5.dp))
                    T(unit, car.secondary, C.Sub, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
                FirstThatFits {
                    figure(if (parked) "л" else units.consumptionUnitLong)
                    figure(if (parked) "л" else units.consumptionUnit)
                }
                val km = if (parked) "стоянка" else "${fmt(units.distance(t.distanceKm), 0)} ${units.distanceUnit}"
                val time = "%d:%02d".format(d / 3600, d / 60 % 60)
                FirstThatFits {
                    T("${dayLabel(t.startMs)} · $km · $time", car.secondary, C.Sub, maxLines = 1)
                    T("${dayLabel(t.startMs)} · $km", car.secondary, C.Sub, maxLines = 1)
                    T(dayLabel(t.startMs), car.secondary, C.Sub, maxLines = 1)
                }
            }
            if (withIcon) {
                val icon = when {
                    parked -> Lucide.SquareParking
                    t.hwyKm >= t.cityKm && t.hwyKm >= t.mixKm -> Lucide.Milestone
                    t.cityKm >= t.mixKm -> Lucide.Building2
                    else -> Lucide.Route
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(C.Blue.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = C.Blue, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


@Composable
private fun HistoryTab() {
    val version by Repo.dataVersion.collectAsState()
    val read = rememberQuery(version) {
        val now = System.currentTimeMillis()
        val from = startOfDay(now) - 29 * 86_400_000L
        Repo.db.buckets(from, 86_400_000L, java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()).filter { it.km > 0.1 || it.fuelL > 0.05 }.reversed()
    }.value
    val days = read ?: emptyList()
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.FileText, "История за 30 дней")
        Spacer(Modifier.height(8.dp))
        HeaderRow(listOf("Дата", kg.forester.sg.data.DistanceUnits.label("Пробег, км"), "Топливо, л", kg.forester.sg.data.DistanceUnits.label("Расход, л/100"), "Стоимость, ${Repo.settings.currency}"), gap = 8.dp)
        LazyColumn {
            items(days) { b ->
                val per = if (b.km > 0.5) b.fuelL / b.km * 100 else null
                DataRow(
                    listOf(
                        SimpleDateFormat("dd.MM.yyyy, EE", Locale("ru")).format(Date(b.timeMs)),
                        fmt(kg.forester.sg.data.DistanceUnits.distance(b.km)), fmt(b.fuelL), fmt(kg.forester.sg.data.DistanceUnits.consumption(per)), fmt(b.fuelL * Repo.settings.pricePerL, 0)
                    ),
                    gap = 8.dp,
                )
            }
        }
        if (read != null && days.isEmpty()) T("Данных пока нет", 14.sp, C.Sub)
    }
}

@Composable
private fun RefillsTab() {
    val version by Repo.dataVersion.collectAsState()
    val refills = rememberQuery(version) { Repo.db.refills() }.value ?: emptyList()
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(fmt(Repo.settings.pricePerL, 1)) }
    var full by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    // the form at most 0.42 of the width: wider, it left the table's six columns too narrow for their names
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val formW = minOf(roomFor(380.dp, 16f), maxWidth * 0.42f)
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(formW).fillMaxHeight()) {
            PanelTitle(Lucide.Fuel, "Новая заправка")
            Spacer(Modifier.height(12.dp))
            NumField("Сколько залил, литров", liters) { liters = it }
            Spacer(Modifier.height(10.dp))
            NumField("Цена за литр, ${Repo.settings.currency} (необязательно)", price) { price = it }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { full = !full }) {
                Checkbox(full, { full = it }, colors = CheckboxDefaults.colors(checkedColor = C.Purple))
                T("Залил до полного (для точной калибровки)", 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Accent)
                    .clickable {
                        val l = liters.replace(',', '.').toDoubleOrNull()
                        val p = price.replace(',', '.').toDoubleOrNull() ?: Repo.settings.pricePerL
                        if (l == null || l <= 0) {
                            msg = "Введите литры"
                        } else {
                            msg = RefillLogic.add(l, p, full)
                            liters = ""
                        }
                    }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) { T("Добавить", 16.sp, weight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            T("Дата и время подставятся автоматически", 12.sp, C.Sub)
            T(msg, 13.sp, C.Sub)
            Spacer(Modifier.weight(1f))
            T("Коэффициент расхода: ${fmt(Repo.settings.fuelK, 3)}", 12.sp, C.Sub)
            T("Уточняется автоматически между двумя заправками до полного бака.", 12.sp, C.Sub)
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.FileText, "Заправки")
            Spacer(Modifier.height(8.dp))
            // each column as wide as its longest word needs (the date breaks between its day and its time): equal ones
            // broke «Полный» and «Литры» in the middle of the word
            val heads = listOf("Дата", "Литры", "Цена", "Сумма", "Полный", "Расчёт, л")
            val measure = rememberTextWidth()
            val sec = LocalCar.current.secondary
            val widths = heads.zip(listOf("30.10.26", "142.5", "62.5", "12656", "нет", "137.2")).map { (h, sample) ->
                maxOf(h.split(' ').maxOf { measure(it, sec, false) }, measure(sample, (sec.value + 1f).sp, false)).value + 4f
            }
            HeaderRow(heads, widths, 8.dp)
            LazyColumn {
                items(refills) { r: Refill ->
                    DataRow(
                        listOf(
                            SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(Date(r.timeMs)),
                            fmt(r.liters), fmt(r.pricePerL), fmt(r.liters * r.pricePerL, 0),
                            if (r.full) "да" else "нет", fmt(r.odoComputedL)
                        ),
                        widths, 8.dp,
                    )
                }
            }
        }
    }
    }
}

object RefillLogic {
    fun add(liters: Double, price: Double, full: Boolean): String {
        val s = Repo.settings
        s.pricePerL = price
        val computed = s.usedSinceFullL
        var note = "Заправка добавлена"
        if (full) {
            // Litres put in since the previous full tank (this one + partial refills in between).
            val partials = Repo.db.refills().takeWhile { !it.full }.sumOf { it.liters }
            val actual = liters + partials
            if (s.hadFullRefill && computed >= 20 && actual >= 15 && actual / computed !in 0.5..2.0) {
                // twice off is not a calibration error but bad data (e.g. a junk MAF reading counted as 250 l/h)
                note = "Расчёт (${fmt(computed)} л) слишком далёк от факта (${fmt(actual)} л): похоже на сбой данных, " +
                    "калибровка пропущена. Счёт начат заново с этого бака."
            } else if (s.hadFullRefill && computed >= 20 && actual >= 15) {
                // "Full" is never exactly full: only long intervals are trusted, one step is at most ±25 %
                // and the coefficient as a whole stays within 0.7…1.4, so a bad refill can't run away.
                val ratio = (actual / computed).coerceIn(0.8, 1.25)
                s.fuelK = (s.fuelK * ratio).coerceIn(0.7, 1.4)
                note = "Калибровка: факт ${fmt(actual)} л, расчёт ${fmt(computed)} л → коэф. ${fmt(s.fuelK, 3)}"
            } else if (s.hadFullRefill) {
                note = "Полный бак отмечен. Для калибровки мало проехали (нужно от 20 л), коэффициент не менялся."
            } else {
                note = "Полный бак отмечен. Калибровка будет после следующего полного бака."
            }
            s.hadFullRefill = true
            s.usedSinceFullL = 0.0
            if (s.tankL > 0) { s.fuelAtRefillL = s.tankL; s.fuelLevelKnown = true }
        } else {
            // Unknown previous level counts as empty; the estimate gets exact after a full tank.
            if (s.fuelAtRefillL < 0) s.fuelLevelKnown = false
            val left = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL).coerceAtLeast(0.0) else 0.0
            val after = left + liters
            s.fuelAtRefillL = if (s.tankL > 0) after.coerceAtMost(s.tankL) else after
            note = "Добавлено ${fmt(liters)} л. В баке ≈ ${fmt(s.fuelAtRefillL, 0)} л"
        }
        s.usedSinceRefillL = 0.0
        Repo.db.addRefill(Refill(timeMs = System.currentTimeMillis(), liters = liters, pricePerL = price, full = full, odoComputedL = computed))
        Repo.touch()
        return note
    }
}

@Composable
private fun StyleTab() {
    val ts by Repo.trip.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    val read = rememberQuery(version) { Repo.db.trips(1) }.value
    val last = read?.firstOrNull()
    val t = ts.trip?.takeIf { it.durationS > 60 } ?: last
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.Gauge, if (t === ts.trip) "Стиль вождения — текущая поездка" else "Стиль вождения — последняя поездка")
        Spacer(Modifier.height(12.dp))
        if (t == null) {
            if (read != null) T("Нет данных", 14.sp, C.Sub)
            return@Panel
        }
        val idlePct = if (t.durationS > 0) t.idleS / t.durationS * 100 else 0.0
        // events per hour, but a short trip counts as at least 30 min: one hard stop on a 5-min run is not -48
        val perHour = 3600.0 / maxOf(t.durationS, 1800L)
        val score = (100 - (t.hardAccel + t.hardBrake) * perHour * 4 - idlePct * 0.3).coerceIn(0.0, 100.0)
        // the names in short where a third of the width doesn't hold them whole (the «i» has them in full)
        val measure = rememberTextWidth()
        val sec = LocalCar.current.secondary
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val tileW = (maxWidth - 24.dp) / 3
            @Composable
            fun tile(icon: ImageVector, label: String, short: String, value: String, unit: String, m: Modifier) =
                Tile(icon, if (measure(label, sec, false) + 64.dp <= tileW) label else short, value, unit, m, info = label)
            // two rows sharing the height: unsized, the first one took it all and pushed the second off the panel
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tile(Lucide.Star, "Оценка", "Оценка", fmt(score, 0), "/ 100", Modifier.weight(1f).fillMaxHeight())
                    tile(Lucide.TrendingUp, "Резкие разгоны", "Разгоны", t.hardAccel.toString(), "раз", Modifier.weight(1f).fillMaxHeight())
                    tile(Lucide.TrendingDown, "Резкие торможения", "Торможения", t.hardBrake.toString(), "раз", Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tile(Lucide.Hourglass, "Холостой ход", "Холостые", fmt(idlePct, 0), "% времени", Modifier.weight(1f).fillMaxHeight())
                    tile(Lucide.Wind, "Макс. наддув", "Наддув", fmt(t.maxBoostKpa / 100, 2), "бар", Modifier.weight(1f).fillMaxHeight())
                    tile(Lucide.Gauge, "Макс. обороты", "Обороты", t.maxRpm.toString(), "об/мин", Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        T("Оценка снижается за резкие разгоны/торможения (в пересчёте на час) и долгий холостой ход.", 13.sp, C.Sub)
    }
}

@Composable
private fun TripsTab() {
    val version by Repo.dataVersion.collectAsState()
    val trips = rememberQuery(version) { Repo.db.trips(300) }.value ?: emptyList()
    Panel(Modifier.fillMaxSize()) {
        PanelTitle(Lucide.Route, "Все поездки")
        Spacer(Modifier.height(8.dp))
        // the start («Сегодня, 08:15») is the longest; the time and the consumption are short
        val widths = listOf(1.5f, 0.8f, 1f, 1f, 0.9f, 1.1f, 1.2f)
        HeaderRow(listOf("Начало", "Время", kg.forester.sg.data.DistanceUnits.label("Пробег, км"), "Топливо, л", kg.forester.sg.data.DistanceUnits.consumptionUnit, "Ср. скорость", "Город/Трасса"), widths, 8.dp)
        LazyColumn {
            items(trips) { t ->
                val d = t.durationS
                val tot = (t.cityKm + t.hwyKm + t.mixKm).coerceAtLeast(0.001)
                DataRow(
                    listOf(
                        dayLabel(t.startMs), "%d:%02d".format(d / 3600, d / 60 % 60), fmt(kg.forester.sg.data.DistanceUnits.distance(t.distanceKm)), fmt(t.fuelL),
                        fmt(kg.forester.sg.data.DistanceUnits.consumption(t.per100)), fmt(kg.forester.sg.data.DistanceUnits.speed(t.avgSpeed), 0), "${(t.cityKm / tot * 100).toInt()}% / ${(t.hwyKm / tot * 100).toInt()}%"
                    ),
                    widths, 8.dp,
                )
            }
        }
    }
}

/** A table's head; [weights]: how wide each column is against the others (all alike when not given). */
@Composable
fun HeaderRow(cols: List<String>, weights: List<Float> = emptyList(), gap: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        cols.forEachIndexed { i, c -> T(c, 12.sp, C.Sub, modifier = Modifier.weight(weights.getOrElse(i) { 1f })) }
    }
}

@Composable
fun DataRow(cols: List<String>, weights: List<Float> = emptyList(), gap: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        cols.forEachIndexed { i, c -> T(c, 14.sp, modifier = Modifier.weight(weights.getOrElse(i) { 1f })) }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { FieldLabel(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun dayLabel(ms: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    return when {
        ms >= today -> "Сегодня, $time"
        ms >= today - 86_400_000L -> "Вчера, $time"
        else -> SimpleDateFormat("dd.MM, HH:mm", Locale.US).format(Date(ms))
    }
}
