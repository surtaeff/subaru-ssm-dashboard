package kg.forester.sg.ui

import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.composables.icons.lucide.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kg.forester.sg.data.TcuBoard
import kg.forester.sg.data.TileCfg
import kg.forester.sg.ssm.LockState
import androidx.compose.ui.graphics.vector.ImageVector

/** Cards of the "АКПП" screen, for the "add" menu. */
val TcuCards: CardNames = linkedMapOf(
    "range" to ("Селектор / передача" to Lucide.Cog),
    "atf" to ("Температура ATF" to Lucide.ThermometerSun),
    "converter" to ("Гидротрансформатор" to Lucide.Lock),
    "lockup" to ("Блокировка (Lock-up)" to Lucide.Lock),
    "line" to ("Давление в линии" to Lucide.ArrowDownUp),
    "awd" to ("Муфта полного привода" to Lucide.Waypoints),
    "wheels" to ("Колёса перед / зад" to Lucide.CarFront),
    "chartAtf" to ("График: температура ATF" to Lucide.ChartLine),
    "chartLock" to ("График: блокировка и полный привод" to Lucide.ChartLine),
    "chartTurbine" to ("График: обороты двигателя и турбины" to Lucide.ChartLine),
    "chartWheels" to ("График: скорость колёс" to Lucide.ChartLine),
)

private val TcuEdit = BoardEdit(TcuBoard, gaugeable = setOf("atf", "lockup", "line"), rowWeight = ::tcuRowWeight)

/** Row heights: big cards 1.1, charts 1.2, small tiles 0.55 (or 1.1 once shown as a needle). */
private fun tcuRowWeight(t: TileCfg) = when {
    t.id.startsWith("chart") -> if (CarScreen.small) 1f else 1.2f
    // on a small screen the big cards' lines need the room, the charts give it up
    t.id in setOf("range", "atf", "converter") -> if (CarScreen.small) 1.85f else 1.1f
    t.style.isNotEmpty() -> 1.1f
    else -> 0.55f
}

/** The TCU answers less often than the ECU: two minutes on the width, and the line runs further behind its readings. */
@Composable
private fun tcuChart(vararg lines: ChartLine<kg.forester.sg.ssm.TcuSnapshot>) =
    rememberChart(Repo.tcuHistory, *lines, capacity = 600, windowMs = 120_000L, lagMs = 1500L, breakMs = 6000L)

@Composable
fun TransmissionScreen() {
    val t by Repo.tcu.collectAsState()
    val e by Repo.live.collectAsState()
    val status by Repo.tcuStatus.collectAsState()

    val rpm = e.rpm ?: 0
    val slip = t?.slipPct
    val lock = t?.lock ?: LockState.UNKNOWN
    val locked = lock == LockState.LOCKED || lock == LockState.SLIPPING

    BoardScreen(TcuEdit, TcuCards, ::tcuRowWeight) { cfg, m ->
        val info = "tcu.${cfg.id}"
        @Composable
        fun Small(icon: ImageVector, title: String, v: Float?, unit: String) =
            ValueCard(CardValue(icon, title, v, 0f, 100f, unit, "%.0f", info), cfg.style, m) {
                Tile(icon, title, fmtF(v, 0), unit, m, info)
            }
        when (cfg.id) {
            "range" -> Panel(m) {
                CardHeader(Lucide.Cog, "Селектор", C.Violet, info)
                val range = t?.range
                // M: the SportShift manual gate (TcuParams.selector); no position at all still shows the gear
                val driving = range == "D" || range == "3" || range == "2" || range == "1" || range == "M" || (range == null && t?.gear != null)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = range,
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "range",
                        ) { r -> T(r ?: "—", if ((r?.length ?: 1) > 1) 52.sp else 72.sp, if (r == null) C.Sub else C.Violet, FontWeight.Bold) }
                        if (driving) {
                            Spacer(Modifier.width(16.dp))
                            Column {
                                T("передача", 12.sp, C.Sub)
                                AnimatedContent(
                                    targetState = t?.gear,
                                    transitionSpec = {
                                        val up = (targetState ?: 0) > (initialState ?: 0)
                                        (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                                            (slideOutVertically { if (up) -it else it } + fadeOut())
                                    },
                                    label = "gear",
                                ) { g -> T(g?.toString() ?: "—", 40.sp, weight = FontWeight.Bold) }
                            }
                        }
                    }
                }
                // the selector strip, the current position lit
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (if (range == "M") listOf("P", "R", "N", "D", "M") else listOf("P", "R", "N", "D", "3", "2", "1")).forEach { pos ->
                        val on = range == pos
                        Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (on) C.Violet else C.CardHi), contentAlignment = Alignment.Center) {
                            T(pos, 13.sp, if (on) C.Text else C.Sub, if (on) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            "atf" -> ValueCard(
                CardValue(Lucide.ThermometerSun, "Температура ATF", t?.atfC?.toFloat(), 0f, 130f, "°C", "%.0f", info, 110f), cfg.style, m,
            ) { AtfCard(t?.atfC, m) }
            "converter" -> Panel(m) {
                CardHeader(Lucide.Lock, "Гидротрансформатор", C.Blue, info)
                Spacer(Modifier.height(6.dp))
                val stColor = when (lock) {
                    LockState.UNKNOWN -> C.Sub
                    LockState.OPEN -> C.Blue
                    LockState.ENGAGING -> C.Violet
                    LockState.LOCKED -> C.Green
                    LockState.SLIPPING -> C.Orange
                }
                // with no TCU data the pill says why (waiting, no answer): a status line of its own under the rows
                // was pushed off a short tile together with the last row
                val why = t == null && status.isNotEmpty()
                val stText = if (why) status else lock.text
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background((if (why) C.Orange else stColor).copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (why) Lucide.Info else if (locked) Lucide.Lock else Lucide.LockOpen, null, tint = if (why) C.Orange else stColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    T(stText, 14.sp, if (why) C.Orange else stColor, FontWeight.SemiBold, maxLines = 1)
                }
                // the rows share what is left: on a head unit's three-row board the tile is short, and the slip row
                // and the status line under it were cut off
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
                    // engine and turbine in one row: three rows did not fit a short tile at the parked text size
                    ConvRow(Lucide.Fan, "Двигатель / турбина", "${if (rpm > 0) "$rpm" else "—"} / ${t?.turbineRpm?.toString() ?: "—"}", "об/мин")
                    ConvRow(Lucide.Percent, "Проскальзывание", fmtF(slip, 0), "%", if (lock == LockState.SLIPPING) C.Orange else C.Text)
                }
            }
            "lockup" -> Small(Lucide.Lock, if (LocalCar.current.small) "Блокировка" else "Блокировка (Lock-up)", t?.lockUpPct, "%")
            "line" -> Small(Lucide.ArrowDownUp, if (LocalCar.current.small) "Давление" else "Давление в линии", t?.linePressurePct, "%")
            "awd" -> AwdTile(t?.transferPct, t?.frontWheelKmh, t?.rearWheelKmh, t?.fwdFuse == true, m)
            "wheels" -> Tile(Lucide.CarFront, if (LocalCar.current.small) "Колёса п/з" else "Колёса перед / зад", "${t?.frontWheelKmh?.let { kg.forester.sg.data.DistanceUnits.speed(it) } ?: "—"} / ${t?.rearWheelKmh?.let { kg.forester.sg.data.DistanceUnits.speed(it) } ?: "—"}", kg.forester.sg.data.DistanceUnits.speedUnit, m, info)
            "chartAtf" -> ChartCard(
                "Температура ATF, °C",
                tcuChart(ChartLine("ATF", C.Orange) { it.atfC?.toFloat() }),
                0f, 130f, m, "%.0f", info = info, icon = Lucide.ThermometerSun, tint = C.Orange,
            )
            "chartLock" -> ChartCard(
                "Блокировка, %",
                tcuChart(
                    ChartLine("lock-up", C.Green) { it.lockUpPct },
                    ChartLine("AWD", C.Blue) { it.transferPct },
                    ChartLine("линия", C.Violet) { it.linePressurePct },
                ),
                0f, 100f, m, "%.0f", info = info, icon = Lucide.Lock, tint = C.Green,
            )
            "chartTurbine" -> ChartCard(
                "Обороты турбины, об/мин",
                tcuChart(ChartLine("турбина", C.Violet) { it.turbineRpm?.toFloat() }),
                0f, 7000f, m, "%.0f", info = info, icon = Lucide.Fan,
            )
            "chartWheels" -> ChartCard(
                kg.forester.sg.data.DistanceUnits.label("Скорость колёс, км/ч"),
                tcuChart(
                    ChartLine("перед", C.Blue) { it.frontWheelKmh?.toFloat()?.let { v -> kg.forester.sg.data.DistanceUnits.speed(v) } },
                    ChartLine("зад", C.Orange) { it.rearWheelKmh?.toFloat()?.let { v -> kg.forester.sg.data.DistanceUnits.speed(v) } },
                ),
                0f, kg.forester.sg.data.DistanceUnits.top(kg.forester.sg.data.DistanceUnits.Kind.SPEED, 200f), m, "%.0f", info = info, icon = Lucide.CarFront, tint = C.Blue,
            )
        }
    }
}

@Composable
private fun AtfCard(atf: Int?, modifier: Modifier) {
    val (label, color) = when {
        atf == null -> "нет данных" to C.Sub
        atf < 40 -> "холодная, не нагружайте" to C.Blue
        atf <= 100 -> "норма" to C.Green
        atf <= 115 -> "горячая" to C.Orange
        else -> "перегрев!" to C.Red
    }
    Panel(modifier) {
        CardHeader(Lucide.ThermometerSun, "Температура ATF", C.Orange, "tcu.atf")
        RingGauge((atf ?: 0) / 130f, atf?.let { "$it°" } ?: "—", label, Modifier.weight(1f).fillMaxWidth(), unitColor = color)
        Spacer(Modifier.height(4.dp))
        // zones: cold (do not load it) / normal / hot / overheating
        val zones = listOf(40f to C.Blue, 60f to C.Green, 15f to C.Orange, 15f to C.Red)
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            zones.forEach { (w, c) -> Box(Modifier.weight(w).fillMaxHeight().background(c.copy(alpha = 0.7f))) }
        }
        ScaleLabels(listOf(0f to "0", 40f to "40", 100f to "100", 115f to "115"), 130f)
    }
}

/**
 * Numbers under a zone bar, each centred on its value and kept inside the bar. With the text floor of a parked
 * screen the labels are wide: one that would touch the label before it is left out (100 and 115 ran into «100115»),
 * and the row is as tall as the text instead of a fixed 18 dp that cut it off.
 */
@Composable
private fun ScaleLabels(marks: List<Pair<Float, String>>, max: Float) {
    androidx.compose.ui.layout.Layout(
        content = { marks.forEach { (_, l) -> T(l, 10.sp, C.Sub, maxLines = 1) } },
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val ps = measurables.map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val gap = 6.dp.roundToPx()
        var lastEnd = Int.MIN_VALUE
        val xs = ps.mapIndexed { i, p ->
            val x = ((w * (marks[i].first / max)).toInt() - p.width / 2).coerceIn(0, (w - p.width).coerceAtLeast(0))
            if (x < lastEnd + gap) null else x.also { lastEnd = x + p.width }
        }
        layout(w, ps.maxOfOrNull { it.height } ?: 0) { ps.forEachIndexed { i, p -> xs[i]?.let { p.placeRelative(it, 0) } } }
    }
}

@Composable
private fun ConvRow(icon: ImageVector, label: String, value: String, unit: String, color: androidx.compose.ui.graphics.Color = C.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        FitText(label, LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
        T(value, 15.sp, color, FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        T(unit, 11.sp, C.Sub)
    }
}

/**
 * Transfer clutch duty plus a rough front:rear split. SSM has no split parameter; this assumes the
 * clutch engages linearly with the duty, for the AWD layout chosen in the settings. Still to be
 * checked against logs from a real car (transfer_pct and the wheel speeds are in the CSV).
 */
@Composable
private fun AwdTile(duty: Float?, front: Int?, rear: Int?, fwd: Boolean, modifier: Modifier) {
    Repo.carVersion.collectAsState().value
    val vtd = Repo.settings.awd == "vtd"
    // MP-T: 0 % = front only, 100 % = locked ≈ 50:50. VTD: centre diff 45:55, the clutch locks it towards 50:50.
    // FWD fuse in: the TCU keeps the rear axle off on purpose, the split would only be noise
    val rearShare = duty?.takeIf { !fwd }?.let { d ->
        val e = d.coerceIn(0f, 100f) / 100f
        (if (vtd) 55f - 5f * e else 50f * e).roundToInt()
    }
    val slip = front != null && rear != null && rear > 5 && front - rear > 3
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
    if (maxHeight > 96.dp) {
        // a tall tile, laid out like its neighbours (see [Tile]): the name on top, the number as big as the tile allows,
        // the split beside it (it was the compact one-line layout among three big numbers)
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 22.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Waypoints, null, tint = C.Sub, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                FitText(if (LocalCar.current.small) "Муфта" else "Муфта привода", LocalCar.current.secondary, color = C.Sub)
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom) {
                FitValue(fmtF(duty, 0), "%", 20f, Modifier.weight(1f).fillMaxHeight(), widest = "100")
                if (rearShare != null) Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    T("≈ ${100 - rearShare}:$rearShare", 18.sp, C.Violet, FontWeight.SemiBold, maxLines = 1)
                    T(" β", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            if (fwd) T("FWD: вставлен предохранитель, задний мост отключён", 11.sp, C.Orange, maxLines = 1)
            else if (slip) T("передние буксуют", 11.sp, C.Orange, maxLines = 1)
        }
    } else Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Waypoints, null, tint = C.Sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            FitText(if (LocalCar.current.small) "Муфта" else "Муфта привода", LocalCar.current.secondary, color = C.Sub)
            Row(verticalAlignment = Alignment.Bottom) {
                T(fmtF(duty, 0), 20.sp, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                T("%", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                if (rearShare != null) {
                    Spacer(Modifier.width(10.dp))
                    T("≈ ${100 - rearShare}:$rearShare", 14.sp, C.Violet, FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
                    T(" β", 11.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            if (fwd) T("FWD: вставлен предохранитель, задний мост отключён", 11.sp, C.Orange, maxLines = 1)
            else if (slip) T("передние буксуют", 11.sp, C.Orange, maxLines = 1)
        }
    }
    InfoButton("Муфта полного привода", "tcu.awd", Modifier.align(Alignment.TopEnd))
    }
}
