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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Refill
import kg.forester.sg.data.Repo
import kg.forester.sg.data.lhNow
import kg.forester.sg.data.Trip
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
    Column(Modifier.fillMaxSize()) {
        // the tabs take the whole width where their names wouldn't fit beside the all-time figure
        val stacked = car.secondary.value > 19f
        Row(Modifier.fillMaxWidth().height(LocalMinTap.current + 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 6.dp) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    // five equal tabs: where an icon leaves the name cut, the name goes first
                    val icons = maxWidth >= 150.dp * FuelTab.entries.size
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FuelTab.entries.forEach {
                            Chip(if (it == FuelTab.Style) "Стиль" else it.title, tab == it, { tab = it }, if (icons) it.icon else null, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
            if (!stacked) {
                Spacer(Modifier.width(12.dp))
                Panel(Modifier.width(car.secondary.value.dp * 16f).fillMaxHeight(), padding = 6.dp) { AllTimeAverage(totals, Modifier.fillMaxSize()) }
            }
        }
        if (stacked) {
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth().height(car.secondary.lineDp() + 20.dp), padding = 6.dp) { AllTimeAverage(totals, Modifier.fillMaxSize(), oneLine = true) }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                FuelTab.Current -> CurrentTab()
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

@Composable
private fun CurrentTab() {
    val ts by Repo.trip.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    val trip = ts.trip
    val tank = Repo.settings.tankL

    val car = LocalCar.current
    val line = car.secondary.asDp()
    // the panels share the height if it holds what they show at this size of text; otherwise the tab scrolls
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val topH = maxOf((maxHeight - 12.dp) * (1.25f / 2.25f), line * 16f)
    val bottomH = maxOf((maxHeight - 12.dp) * (1f / 2.25f), line * 14f)
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Row(Modifier.height(topH).fillMaxWidth()) {
            Panel(Modifier.weight(1.05f).fillMaxHeight()) {
                PanelTitle(Lucide.Fuel, "Текущий расход")
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val per100 = ts.instPer100
                        RingGauge(
                            ((per100 ?: 0.0) / 20.0).toFloat(),
                            if (per100 != null) fmt(kg.forester.sg.data.DistanceUnits.consumption(per100)) else fmt(ts.lhNow),
                            if (per100 != null) kg.forester.sg.data.DistanceUnits.consumptionUnitLong else "л/ч · стоянка",
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                        FitText("Мгновенный ${fmt(ts.lhNow)} л/ч", car.secondary, color = C.Sub)
                        // an ECU without a usable MAF: say how the number comes about (or why there is none)
                        val live by Repo.live.collectAsState()
                        if ((live.rpm ?: 0) > 300 && live.mafGs == null && live.fuelRateLh == null) {
                            val inj = kg.forester.sg.service.FuelCalc.source == "inj"
                            // a sports ECU on the CAN usually runs on MAP with no MAF at all: that is how it works, not a fault
                            if (inj && kg.forester.sg.data.CanEcu.active)
                                T("Расход по времени впрыска (у Link нет MAF). Точнее станет после 1–2 заправок до полного", 11.sp, C.Sub, maxLines = 2)
                            else T(if (inj) "ЭБУ не отдаёт MAF: расход по форсункам, уточнится по заправкам"
                                else "ЭБУ не отдаёт ни MAF, ни время впрыска: расход не считается", 11.sp, C.Orange, maxLines = 3)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.width(line * 11.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tile(Lucide.Milestone, "Запас хода", fmt(kg.forester.sg.data.DistanceUnits.distance(ts.rangeKm), 0), kg.forester.sg.data.DistanceUnits.distanceUnit, Modifier.fillMaxWidth().weight(1f))
                        Box(Modifier.fillMaxWidth().weight(1.25f).clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
                            val leftTitle = if (ts.fuelFromSensor) "Осталось (датчик)" else "Осталось"
                            // shrunk as a whole when a big UI scale doesn't leave room for every line (the «62%» was cut)
                            ShrinkToFit(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Lucide.Fuel, null, tint = C.Sub, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        T(leftTitle, 11.sp, C.Sub, maxLines = 1)
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            T(fmt(ts.fuelLeftL, 0), 20.sp, weight = FontWeight.Bold)
                                            T(" л", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                                            Spacer(Modifier.weight(1f))
                                            // fuel used since the last refill, in two short lines: in one it was cut and ran into the number
                                            Column(horizontalAlignment = Alignment.End) {
                                                T("с заправки", 10.sp, C.Sub, maxLines = 1)
                                                T("${fmt(Repo.settings.usedSinceRefillL)} л", 10.sp, C.Sub, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                                if (tank > 0) {
                                    val frac = ((ts.fuelLeftL ?: 0.0) / tank).toFloat()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ProgressBar(frac, C.Accent, Modifier.weight(1f), 6.dp)
                                        Spacer(Modifier.width(8.dp))
                                        T("${(frac * 100).toInt()}%", 11.sp, C.Sub)
                                    }
                                }
                                val live by Repo.live.collectAsState()
                                val sensor = live.fuelSensorV?.let { "датчик ${"%.2f".format(it)} В" }
                                    ?: live.fuelSensorOhm?.let { "датчик ${"%.0f".format(it)} Ом" }
                                if (sensor != null) T(sensor, 10.sp, C.Sub, maxLines = 1)
                            }
                            }
                            InfoButton(leftTitle, "Осталось топлива", Modifier.align(Alignment.TopEnd))
                        }
                        if (trip != null && trip.per100 == null && trip.durationS > 30) {
                            Tile(Lucide.Gauge, "На стоянке", fmt(trip.fuelL / (trip.durationS / 3600.0)), "л/ч", Modifier.fillMaxWidth().weight(1f), info = "Средний (стоянка)")
                        } else {
                            Tile(Lucide.Gauge, "За поездку", fmt(kg.forester.sg.data.DistanceUnits.consumption(trip?.per100)), kg.forester.sg.data.DistanceUnits.consumptionUnitLong, Modifier.fillMaxWidth().weight(1f), info = "Средний (поездка)")
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ChartPanel(version, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))
        // a small screen: the six tiles over the whole width, or their names don't fit
        if (car.small) {
            TripInfoPanel(trip, Modifier.height(bottomH).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        val lowerOn = rememberLater()
        if (lowerOn) Row(Modifier.height(bottomH).fillMaxWidth()) {
            if (!car.small) {
                TripInfoPanel(trip, Modifier.weight(1.05f).fillMaxHeight())
                Spacer(Modifier.width(12.dp))
            }
            ModesPanel(trip, Modifier.weight(if (car.small) 1f else 0.75f).fillMaxHeight())
            Spacer(Modifier.width(12.dp))
            RecentTripsPanel(version, Modifier.weight(if (car.small) 1f else 0.75f).fillMaxHeight())
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
    Panel(modifier) {
        PanelTitle(Lucide.ChartColumn, "Динамика расхода")
        Spacer(Modifier.height(6.dp))
        run {
            // its own row: beside the name, at a larger text, they squeezed it to a letter a line
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.forEach { r ->
                    // the chip keeps its look and its place in the title row; the area that takes the tap is 48 dp tall
                    TapTarget({ range = r }) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (r == range) C.selectFill(C.Purple.copy(alpha = 0.3f)) else C.idleFill(C.CardHi))
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) { T(r.title, 13.sp, if (r == range) C.selectText(C.Text) else C.Sub) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText(kg.forester.sg.data.DistanceUnits.consumptionUnitLong, LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
            Box(Modifier.size(8.dp).clip(CircleShape).background(C.Violet))
            T(" Сейчас   ", 12.sp, C.Sub, maxLines = 1)
            T("- - Средний ${fmtF(data.second?.let { kg.forester.sg.data.DistanceUnits.consumption(it.toDouble()).toFloat() })}", 12.sp, C.Sub, maxLines = 1)
        }
        Row(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Column(Modifier.fillMaxHeight().width(roomFor(26.dp, 11f)), verticalArrangement = Arrangement.SpaceBetween) {
                (if (kg.forester.sg.data.DistanceUnits.miles) listOf("60", "40", "20", "0") else listOf("30", "20", "10", "0")).forEach { T(it, 11.sp, C.Sub, maxLines = 1) }
            }
            AreaChart(
                data.first.map { v -> v?.let { kg.forester.sg.data.DistanceUnits.consumption(it.toDouble()).toFloat() } }, kg.forester.sg.data.DistanceUnits.top(kg.forester.sg.data.DistanceUnits.Kind.CONSUMPTION, 30f),
                data.second?.let { kg.forester.sg.data.DistanceUnits.consumption(it.toDouble()).toFloat() }, Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TripInfoPanel(trip: Trip?, modifier: Modifier) {
    val ctx = LocalContext.current
    val price = Repo.settings.pricePerL
    Panel(modifier) {
        PanelTitle(Lucide.Timer, "Информация о поездке") {
            TapTarget({ EcuService.start(ctx, EcuService.ACTION_RESET_TRIP) }) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi).padding(horizontal = 14.dp, vertical = 7.dp)
                ) { T("Сбросить", 13.sp, C.Sub) }
            }
        }
        Spacer(Modifier.height(10.dp))
        val dur = trip?.durationS ?: 0
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (dv, du) = if (dur < 3600) "${dur / 60}" to "мин" else "%d:%02d".format(dur / 3600, dur / 60 % 60) to "ч"
                Tile(Lucide.Clock, "В пути", if (trip == null) "—" else dv, du, Modifier.weight(1f).fillMaxHeight())
                Tile(Lucide.Activity, kg.forester.sg.data.I18n.pick("Пробег", "Dist."), fmt(kg.forester.sg.data.DistanceUnits.distance(trip?.distanceKm)), kg.forester.sg.data.DistanceUnits.distanceUnit, Modifier.weight(1f).fillMaxHeight(), info = "Пробег")
                Tile(Lucide.Gauge, kg.forester.sg.data.I18n.pick("Ср.", "Avg"), fmt(kg.forester.sg.data.DistanceUnits.speed(trip?.avgSpeed), 0), kg.forester.sg.data.DistanceUnits.speedUnit, Modifier.weight(1f).fillMaxHeight(), info = "Ср. скорость")
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trip != null && trip.per100 == null && trip.durationS > 30) {
                    Tile(Lucide.Fuel, "Ср. расход", fmt(trip.fuelL / (trip.durationS / 3600.0)), "л/ч", Modifier.weight(1f).fillMaxHeight())
                } else {
                    Tile(Lucide.Fuel, "Ср. расход", fmt(kg.forester.sg.data.DistanceUnits.consumption(trip?.per100)), kg.forester.sg.data.DistanceUnits.consumptionUnit, Modifier.weight(1f).fillMaxHeight())
                }
                Tile(Lucide.Droplet, "Топливо", fmt(trip?.fuelL), "л", Modifier.weight(1f).fillMaxHeight())
                Tile(Lucide.Banknote, "Стоимость", fmt(trip?.fuelL?.times(price), 0), Repo.settings.currency, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

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
    fun per(km: Double, l: Double) = if (km > 0.3) "${fmt(kg.forester.sg.data.DistanceUnits.consumption(l / km * 100))} ${kg.forester.sg.data.DistanceUnits.consumptionUnitLong}" else "— ${kg.forester.sg.data.DistanceUnits.consumptionUnitLong}"
    fun pct(l: Double) = if (totalL > 0) (l / totalL * 100).toInt() else 0
    Panel(modifier, padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Расход по режимам", 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            InfoButton("Расход по режимам")
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(124.dp), contentAlignment = Alignment.Center) {
                Donut(
                    listOf(cityL.toFloat() to C.Violet, hwyL.toFloat() to C.Blue, mixL.toFloat() to C.Gray, idleL.toFloat() to C.Orange),
                    Modifier.fillMaxSize()
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (totalKm > 0.3) {
                        T(fmt(kg.forester.sg.data.DistanceUnits.consumption(totalL / totalKm * 100)), 22.sp, weight = FontWeight.Bold)
                        T(kg.forester.sg.data.DistanceUnits.consumptionUnitLong, 10.sp, C.Sub)
                    } else {
                        T(fmt(totalL, 2), 22.sp, weight = FontWeight.Bold)
                        T("литров", 10.sp, C.Sub)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            // four rows of two lines: at a large UI scale they shrink to the panel instead of losing «Стоянка»
            ShrinkToFit(Modifier.weight(1f).fillMaxHeight()) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeRow("Город", C.Violet, per(cityKm, cityL), pct(cityL))
                ModeRow("Трасса", C.Blue, per(hwyKm, hwyL), pct(hwyL))
                ModeRow("Смешанный", C.Gray, per(mixKm, mixL), pct(mixL))
                ModeRow(
                    "Стоянка", C.Orange,
                    "${fmt(idleL, 2)} л · ${if (idleS > 60) fmt(idleL / idleS * 3600) else "—"} л/ч", pct(idleL),
                )
            } }
        }
    }
}

@Composable
private fun ModeRow(name: String, color: Color, detail: String, pct: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(roomFor(118.dp, 13f))) {
            Row {
                T(name, 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                T("$pct%", 13.sp, if (color == C.Gray) C.Sub else color, FontWeight.SemiBold)
            }
            T(detail, 11.sp, C.Sub, maxLines = 1)
        }
    }
}

@Composable
private fun RecentTripsPanel(version: Long, modifier: Modifier) {
    val read = rememberQuery(version) { Repo.db.trips(4) }.value
    val trips = read ?: emptyList()
    Panel(modifier, padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Последние поездки", 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            InfoButton("Последние поездки")
        }
        Spacer(Modifier.height(8.dp))
        if (read != null && trips.isEmpty()) T("Пока нет поездок", 13.sp, C.Sub)
        var open by remember { mutableStateOf<Trip?>(null) }
        trips.forEach { TripRow(it) { open = it }; Spacer(Modifier.height(5.dp)) }
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(if (parked) fmt(t.fuelL, 2) else fmt(kg.forester.sg.data.DistanceUnits.consumption(t.per100)), 16.sp, weight = FontWeight.Bold, maxLines = 1)
                T(if (parked) " л" else " " + kg.forester.sg.data.DistanceUnits.consumptionUnitLong, 11.sp, C.Sub, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
            }
            val km = if (parked) "стоянка" else "${fmt(kg.forester.sg.data.DistanceUnits.distance(t.distanceKm), 0)} ${kg.forester.sg.data.DistanceUnits.distanceUnit}"
            T("${dayLabel(t.startMs)} · $km · %d:%02d".format(d / 3600, d / 60 % 60), 11.sp, C.Sub, maxLines = 1)
        }
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
        HeaderRow(listOf("Дата", kg.forester.sg.data.DistanceUnits.label("Пробег, км"), "Топливо, л", kg.forester.sg.data.DistanceUnits.label("Расход, л/100"), "Стоимость, ${Repo.settings.currency}"))
        LazyColumn {
            items(days) { b ->
                val per = if (b.km > 0.5) b.fuelL / b.km * 100 else null
                DataRow(
                    listOf(
                        SimpleDateFormat("dd.MM.yyyy, EE", Locale("ru")).format(Date(b.timeMs)),
                        fmt(kg.forester.sg.data.DistanceUnits.distance(b.km)), fmt(b.fuelL), fmt(kg.forester.sg.data.DistanceUnits.consumption(per)), fmt(b.fuelL * Repo.settings.pricePerL, 0)
                    )
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

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(roomFor(380.dp, 16f)).fillMaxHeight()) {
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
            HeaderRow(listOf("Дата", "Литры", "Цена", "Сумма", "Полный", "Расчёт, л"))
            LazyColumn {
                items(refills) { r: Refill ->
                    DataRow(
                        listOf(
                            SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(Date(r.timeMs)),
                            fmt(r.liters), fmt(r.pricePerL), fmt(r.liters * r.pricePerL, 0),
                            if (r.full) "да" else "нет", fmt(r.odoComputedL)
                        )
                    )
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
        Spacer(Modifier.height(16.dp))
        if (t == null) {
            if (read != null) T("Нет данных", 14.sp, C.Sub)
            return@Panel
        }
        val idlePct = if (t.durationS > 0) t.idleS / t.durationS * 100 else 0.0
        // events per hour, but a short trip counts as at least 30 min: one hard stop on a 5-min run is not -48
        val perHour = 3600.0 / maxOf(t.durationS, 1800L)
        val score = (100 - (t.hardAccel + t.hardBrake) * perHour * 4 - idlePct * 0.3).coerceIn(0.0, 100.0)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Lucide.Star, "Оценка", fmt(score, 0), "/ 100", Modifier.weight(1f))
            Tile(Lucide.TrendingUp, "Резкие разгоны", t.hardAccel.toString(), "раз", Modifier.weight(1f))
            Tile(Lucide.TrendingDown, "Резкие торможения", t.hardBrake.toString(), "раз", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Lucide.Hourglass, "Холостой ход", fmt(idlePct, 0), "% времени", Modifier.weight(1f))
            Tile(Lucide.Wind, "Макс. наддув", fmt(t.maxBoostKpa / 100, 2), "бар", Modifier.weight(1f))
            Tile(Lucide.Gauge, "Макс. обороты", t.maxRpm.toString(), "об/мин", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
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
        HeaderRow(listOf("Начало", "Время", kg.forester.sg.data.DistanceUnits.label("Пробег, км"), "Топливо, л", kg.forester.sg.data.DistanceUnits.consumptionUnit, "Ср. скорость", "Город/Трасса"))
        LazyColumn {
            items(trips) { t ->
                val d = t.durationS
                val tot = (t.cityKm + t.hwyKm + t.mixKm).coerceAtLeast(0.001)
                DataRow(
                    listOf(
                        dayLabel(t.startMs), "%d:%02d".format(d / 3600, d / 60 % 60), fmt(kg.forester.sg.data.DistanceUnits.distance(t.distanceKm)), fmt(t.fuelL),
                        fmt(kg.forester.sg.data.DistanceUnits.consumption(t.per100)), fmt(kg.forester.sg.data.DistanceUnits.speed(t.avgSpeed), 0), "${(t.cityKm / tot * 100).toInt()}% / ${(t.hwyKm / tot * 100).toInt()}%"
                    )
                )
            }
        }
    }
}

@Composable
fun HeaderRow(cols: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cols.forEach { T(it, 12.sp, C.Sub, modifier = Modifier.weight(1f)) }
    }
}

@Composable
fun DataRow(cols: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        cols.forEach { T(it, 14.sp, modifier = Modifier.weight(1f)) }
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
