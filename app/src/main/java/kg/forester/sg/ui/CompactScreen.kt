package kg.forester.sg.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.data.lhNow
import kg.forester.sg.data.Shortcuts
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A value of the grid, each part read where it is shown: a new reading recomposes only the cards it changes. */
private class Metric(
    val title: String, val value: () -> String, val unit: () -> String, val frac: () -> Float?, val warn: () -> Boolean = { false },
    /** The longest the number and the unit get: the size stays when they change. */
    val widest: String = "88.8", val widestUnit: String = "",
)

/**
 * Driver panel used when the window is narrow (split screen next to the navigator):
 * big speed / RPM, the key readings in a grid, and the quick-launch buttons. The hero always keeps its speed, its rpm
 * and its bar: a short window (500 px) has fewer rows of readings instead.
 */
@Composable
fun CompactScreen(chrome: CompactChrome, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val live = Repo.live.collectAsState()
    val engine by Repo.engineProfile.collectAsState()
    val tcu = Repo.tcu.collectAsState()
    val trip = Repo.trip.collectAsState()
    val link by Repo.link.collectAsState()
    val shortcuts by Shortcuts.list.collectAsState()
    val car = LocalCar.current
    val inSplit by SplitScreen.on.collectAsState()
    // the text, not the moment: the clock costs a frame once a minute, not every look at it
    var now by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).format(Date())) }
    LaunchedEffect(Unit) { while (true) { now = SimpleDateFormat("HH:mm", Locale.US).format(Date()); delay(5000) } }

    val rpmLine = car.label.lineDp()
    val speedLine = car.value.lineDp()
    val cardMin = car.secondary.lineDp() + car.value.lineDp() + 20.dp
    // the menu is the only way out of here: a whole target
    val headerH = car.tap
    val launchH = car.tap
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(10.dp)
    ) {
        val cols = if (maxWidth > 560.dp) 3 else 2
        val heroMin = 24.dp + speedLine + rpmLine + 11.dp
        val spacing = 8.dp
        val fixed = headerH + spacing + (if (chrome.quickLaunch) launchH + spacing else 0.dp)
        val roomLeft = maxHeight - fixed
        val allRows = kotlin.math.ceil((if (cols == 3) 9 else 8) / cols.toFloat()).toInt()
        // as many rows as fit whole beside the least the hero takes, and the hero its share of what remains
        val rowsShown = ((roomLeft - heroMin) / (cardMin + spacing)).toInt().coerceIn(0, allRows)
        val heroH = maxOf(heroMin, (roomLeft - spacing * rowsShown) * (1.15f / (1.15f + rowsShown)))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            // header
            Row(Modifier.height(headerH), verticalAlignment = Alignment.CenterVertically) {
                if (chrome.menu) {
                    Box(
                        Modifier.size(headerH).clip(RoundedCornerShape(10.dp)).background(C.CardHi).clickable(onClick = onMenu),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.Menu, "Меню", tint = C.Text, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(10.dp))
                }
                val noLink = rememberNoLink()
                FitText(kg.forester.sg.data.noLinkText(noLink) ?: rememberCarHeader().first, car.label, Modifier.weight(1f), if (noLink) C.Orange else C.Text, weight = FontWeight.Bold)
                DemoPill(short = true)
                if (rememberCheckEngineShown()) CheckEngineIcon()
                ThemeButton(18.dp)
                Spacer(Modifier.width(4.dp))
                LinkIcon(18.dp)
                Spacer(Modifier.width(10.dp))
                T(now, car.label, weight = FontWeight.SemiBold, maxLines = 1)
            }

            // hero: speed + rpm + gear
            Panel(Modifier.fillMaxWidth().height(heroH), padding = 12.dp) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val speed by rememberDigits { orDash(live.value.speedKmh?.let { kg.forester.sg.data.DistanceUnits.speed(it) }) }
                        // the speed takes what the rpm row and the bar leave, as large as the window lets it be
                        FitValue(
                            speed, kg.forester.sg.data.DistanceUnits.speedUnit, car.value.value, Modifier.fillMaxWidth().height(heroH - 24.dp - rpmLine - 11.dp),
                            widest = "888", everyMs = 0L,
                        )
                        Row {
                            val rpm by rememberDigits { live.value.rpm?.toString() ?: "—" }
                            T(rpm, car.label, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.alignByBaseline())
                            T(" об/мин", car.secondary, C.Sub, maxLines = 1, modifier = Modifier.alignByBaseline())
                        }
                        Spacer(Modifier.height(4.dp))
                        val share = remember(engine) { derivedStateOf { (live.value.rpm ?: 0) / engine.rpmTop } }
                        ProgressBar(
                            share,
                            Brush.horizontalGradient(listOf(C.Blue, C.Violet, C.Orange, C.Red)),
                            Modifier.fillMaxWidth(), 7.dp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val selector by remember { derivedStateOf(structuralEqualityPolicy()) { tcu.value?.range to tcu.value?.gear } }
                        AnimatedContent(selector, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "g") { (r, g) ->
                            val txt = when (r) {
                                null -> "—"
                                "D", "3", "2", "1" -> if (g != null) "$r$g" else r
                                else -> r
                            }
                            T(txt, if (txt.length > 2) car.label else car.value, C.Violet, FontWeight.Bold, maxLines = 1)
                        }
                        T("АКПП", car.secondary, C.Sub, maxLines = 1)
                    }
                }
            }

            // metrics grid
            val boostScale = boostTop
            // no turbo (or not known yet): no boost card, a missing card is less wrong than 0.00 бар (Repo.carTurbo, per connection)
            val turbo by Repo.carTurbo.collectAsState()
            val metrics = remember(boostScale, cols, turbo) {
                val e = { live.value }
                listOfNotNull(
                    Metric("Наддув", { fmtF(e().boostBar, 2) }, { "бар" }, { e().boostBar?.let { it / boostScale } }).takeIf { turbo },
                    Metric("Расход", { trip.value.let { ts -> ts.instPer100?.let { fmt(kg.forester.sg.data.DistanceUnits.consumption(it)) } ?: fmt(ts.lhNow) } }, { if (trip.value.instPer100 != null) kg.forester.sg.data.DistanceUnits.consumptionUnit else "л/ч" }, { null }, widestUnit = kg.forester.sg.data.DistanceUnits.consumptionUnit),
                    Metric("Запас хода", { fmt(kg.forester.sg.data.DistanceUnits.distance(trip.value.rangeKm), 0) }, { kg.forester.sg.data.DistanceUnits.distanceUnit }, { null }, { Warn.of("range").hits(trip.value.rangeKm?.toFloat()) }, widest = "888"),
                    Metric("ОЖ", { e().coolantC?.toString() ?: "—" }, { "°C" }, { e().coolantC?.let { (it - 40) / 70f } }, { Warn.of("coolant").hits(e().coolantC?.toFloat()) }),
                    Metric("ATF", { tcu.value?.atfC?.toString() ?: "—" }, { "°C" }, { tcu.value?.atfC?.let { it / 130f } }, { Warn.of("atf").hits(tcu.value?.atfC?.toFloat()) }),
                    Metric("Напряжение", { fmtF(e().batteryV, 1) }, { "В" }, { e().batteryV?.let { (it - 10f) / 5f } }, { Warn.of("battery").hits(e().batteryV) }),
                    Metric("AFR", { fmtF(e().afr, 1) }, { "" }, { e().afr?.let { (it - 10f) / 10f } }),
                    Metric("Впуск", { e().intakeC?.toString() ?: "—" }, { "°C" }, { e().intakeC?.let { (it + 20) / 90f } }, { Warn.of("intake").hits(e().intakeC?.toFloat()) }),
                    Metric("Детонация", { fmtF(e().knockDeg, 1) }, { "°" }, { null }, { Warn.of("knock").hits(e().knockDeg) }),
                ).take(if (cols == 3) 9 else 8)
            }
            metrics.chunked(cols).take(rowsShown).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { MetricCard(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            // quick launch: only on a head unit, a phone has its own launcher
            if (chrome.quickLaunch) Row(Modifier.fillMaxWidth().height(launchH), horizontalArrangement = Arrangement.spacedBy(minOf(car.gap, 16.dp))) {
                shortcuts.take(4).forEach { sc ->
                    LaunchButton(Shortcuts.icon(sc.icon), sc.label, Modifier.weight(1f)) { Shortcuts.launch(ctx, sc) }
                }
                // only in split screen: anywhere else (a narrow window that isn't one) the button did nothing
                if (fullScreenOffered(inSplit)) LaunchButton(Lucide.Maximize2, "Весь экран", Modifier.weight(1f), highlight = true) { Shortcuts.exitSplit(ctx) }
            }
        }
    }
}

@Composable
private fun MetricCard(m: Metric, modifier: Modifier) {
    val value by rememberDigits(read = m.value)
    val unit by remember(m) { derivedStateOf(m.unit) }
    // from the reading itself, at once
    val warn by remember(m) { derivedStateOf(m.warn) }
    val bar by remember(m) { derivedStateOf { m.frac() != null } }
    val car = LocalCar.current
    Panel(modifier, padding = 10.dp, warn = warn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText(m.title, car.secondary, Modifier.weight(1f), C.Sub)
            if (warn) Icon(Lucide.TriangleAlert, "Выше нормы", tint = C.Orange, modifier = Modifier.size(car.secondary.asDp() * 1.2f))
        }
        FitValue(value, unit, car.value.value, Modifier.fillMaxWidth().weight(1f), if (warn) C.Orange else C.Text, widest = m.widest, everyMs = 0L, widestUnit = m.widestUnit)
        if (bar) {
            Spacer(Modifier.height(4.dp))
            val share = remember(m) { derivedStateOf { m.frac() ?: 0f } }
            ProgressBar(share, if (warn) Brush.horizontalGradient(listOf(C.Orange, C.Red)) else C.Accent, Modifier.fillMaxWidth(), 5.dp)
        }
    }
}

@Composable
private fun LaunchButton(icon: ImageVector, label: String, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(if (highlight) C.Purple.copy(alpha = 0.3f) else C.CardHi)
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (highlight) C.Violet else C.Blue, modifier = Modifier.size(20.dp))
        FitText(label, LocalCar.current.secondary, Modifier.fillMaxWidth().weight(1f), C.Text, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
