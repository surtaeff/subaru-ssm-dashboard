package kg.forester.sg.ui

import androidx.compose.foundation.layout.size

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import com.composables.icons.lucide.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.DriveBoard
import kg.forester.sg.data.KnockStats
import kg.forester.sg.data.TileCfg
import androidx.compose.ui.graphics.vector.ImageVector
import kg.forester.sg.data.Repo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cards of the "Динамика" screen, for the "add" menu. */
val DriveCards: CardNames = linkedMapOf(
    "pedal" to ("Педаль газа" to Lucide.ChevronsUp),
    "throttle" to ("Дроссель" to Lucide.Disc),
    "boost" to ("Наддув" to Lucide.Fan),
    "load" to ("Нагрузка" to Lucide.Activity),
    "rpm" to ("Обороты" to Lucide.Gauge),
    "knock" to ("Детонация" to Lucide.AudioWaveform),
    "chartPedal" to ("График: педаль и дроссель" to Lucide.ChartLine),
    "chartBoost" to ("График: наддув" to Lucide.ChartLine),
    "chartAfr" to ("График: смесь (AFR)" to Lucide.ChartLine),
    "chartTiming" to ("График: зажигание и детонация" to Lucide.ChartLine),
    "chartRpm" to ("График: обороты" to Lucide.ChartLine),
    "chartTrims" to ("График: коррекции A/F" to Lucide.ChartLine),
    "chartMaf" to ("График: MAF" to Lucide.ChartLine),
    "avcs" to ("AVCS (впуск)" to Lucide.RefreshCw),
    "injDuty" to ("Загрузка форсунок" to Lucide.Droplets),
    "maf" to ("MAF" to Lucide.Wind),
    "throttleMotor" to ("Привод дросселя" to Lucide.Cog),
    "wgdc" to ("Wastegate" to Lucide.Wind),
    "pressure" to ("Абс. давление" to Lucide.Gauge),
) + ExtraCards

private val DriveEdit = BoardEdit(
    DriveBoard,
    gaugeable = setOf("pedal", "throttle", "boost", "load", "rpm", "avcs", "injDuty", "maf", "throttleMotor", "wgdc"),
    rowWeight = ::driveRowWeight,
    effective = { saved, small -> kg.forester.sg.data.HomeLayout.driveEffective(saved, small, Repo.carTurbo.value) },
)

/** Row heights: gauges 0.9, charts 1.2, small tiles 0.55; a row with a needle gauge gets 1.3 so the dial is readable. */
internal fun driveRowWeight(t: TileCfg) = when {
    t.id.startsWith("chart") -> 1.2f
    t.id == "defi" || t.id == "navcluster" || t.id in kg.forester.sg.data.GAUGE_PANELS -> 1.8f
    t.id in kg.forester.sg.data.EXTRA_CARDS -> 0.9f
    t.style.isNotEmpty() -> 1.3f
    t.id in setOf("avcs", "injDuty", "maf", "throttleMotor", "wgdc", "pressure") -> 0.55f
    else -> 0.9f
}

@Composable
fun DriveScreen() {
    // each card reads its own value (see [pick]): a reading recomposes the cards whose number changed
    val live = Repo.live.collectAsState()
    val engine by Repo.engineProfile.collectAsState()
    val knock by Repo.knock.collectAsState()

    val turbo by Repo.carTurbo.collectAsState()
    BoardScreen(DriveEdit, DriveCards, ::driveRowWeight, factoryKey = turbo) { t, m ->
        val info = "drive.${t.id}"
        @Composable
        fun Mini(icon: ImageVector, title: String, v: Float?, min: Float, max: Float, unit: String, fmt: String, sub: String? = null, red: Float? = null) =
            ValueCard(CardValue(icon, title, v, min, max, unit, fmt, info, red, warnRule = WarnRule(above = red)), t.style, m) {
                MiniGauge(icon, title, v, min, max, unit, fmt, m, sub = sub, info = info, warnRule = WarnRule(above = red))
            }
        @Composable
        fun Small(icon: ImageVector, title: String, v: Float?, min: Float, max: Float, unit: String, fmt: String) =
            ValueCard(CardValue(icon, title, v, min, max, unit, fmt, info), t.style, m) {
                Tile(icon, title, v?.let { fmt.format(it) } ?: "—", unit, m, info)
            }
        @Composable
        fun Chart(title: String, lines: ChartLines, min: Float, max: Float, fmt: String = "%.1f", zero: Boolean = false) =
            ChartCard(title, lines, min, max, m, fmt, zero, info)
        when (t.id) {
            "pedal" -> Mini(Lucide.ChevronsUp, "Педаль газа", live.pick { it.pedalPct }, 0f, 100f, "%", "%.0f")
            "throttle" -> Mini(Lucide.Disc, "Дроссель", live.pick { it.throttlePct }, 0f, 100f, "%", "%.0f")
            "boost" -> Mini(Lucide.Fan, "Наддув", live.pick { it.boostBar }, 0f, boostTop, "бар", "%.2f",
                sub = live.pick { e -> e.vacuumKpa?.takeIf { it > 1f }?.let { "разрежение %.0f кПа".format(it) } })
            "load" -> Mini(Lucide.Activity, "Нагрузка", live.pick { it.loadGRev }, 0f, loadTop, "г/об", "%.2f")
            "rpm" -> Mini(Lucide.Gauge, "Обороты", live.pick { it.rpm?.toFloat() }, 0f, engine.rpmTop, "об/мин", "%.0f", red = engine.rpmRedFrom)
            "knock" -> KnockCard(knock, live.pick { it.knockDeg }, m)
            "chartPedal" -> Chart(
                "Педаль и дроссель, %",
                rememberChart(Repo.history, ChartLine("педаль", C.Violet) { it.pedalPct }, ChartLine("дроссель", C.Blue) { it.throttlePct }),
                0f, 100f, "%.0f",
            )
            "chartBoost" -> Chart("Наддув, бар", rememberChart(Repo.history, ChartLine("наддув", C.Green) { it.boostBar }), 0f, boostTop, "%.2f")
            "chartAfr" -> Chart("Смесь (AFR)", rememberChart(Repo.history, ChartLine("AFR", C.Orange) { it.afr }), 10f, 20f)
            "chartTiming" -> Chart(
                "Зажигание, °",
                rememberChart(Repo.history, ChartLine("УОЗ", C.Blue) { it.timingDeg }, ChartLine("детонация", C.Red) { it.knockDeg }),
                -10f, 45f, "%.0f", zero = true,
            )
            "chartRpm" -> Chart("Обороты, об/мин", rememberChart(Repo.history, ChartLine("обороты", C.Violet) { it.rpm?.toFloat() }), 0f, engine.rpmTop, "%.0f")
            "chartTrims" -> Chart(
                "Коррекции A/F, %",
                rememberChart(Repo.history, ChartLine("коррекция", C.Orange) { it.afCorrPct }, ChartLine("обучение", C.Blue) { it.afLearnPct }),
                -25f, 25f, "%.0f", zero = true,
            )
            "chartMaf" -> Chart("MAF, г/с", rememberChart(Repo.history, ChartLine("MAF", C.Blue) { it.mafGs }), 0f, mafTop, "%.0f")
            "avcs" -> Small(Lucide.RefreshCw, "AVCS (впуск)", live.pick { it.avcsDeg }, -10f, 50f, "°", "%.0f")
            "injDuty" -> Small(Lucide.Droplets, "Форсунки", live.pick { it.injDutyPct }, 0f, 100f, "%", "%.0f")
            "maf" -> Small(Lucide.Wind, "MAF", live.pick { it.mafGs }, 0f, mafTop, "г/с", "%.1f")
            "throttleMotor" -> Small(Lucide.Cog, "Привод др.", live.pick { it.throttleMotorPct }, -100f, 100f, "%", "%.0f")
            "wgdc" -> Small(Lucide.Wind, "Wastegate", live.pick { it.wastegatePct }, 0f, 100f, "%", "%.0f")
            "pressure" -> Tile(Lucide.Gauge, "Абс. давление", live.pick { e -> "${fmtF(e.mapKpa, 0)} / ${fmtF(e.atmKpa, 0)}" }, "кПа", m, info)
            else -> ExtraCard(t.id, info, m, t.style)
        }
    }
}

@Composable
fun MiniGauge(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Float?, min: Float, max: Float, unit: String, format: String, modifier: Modifier, sub: String? = null, big: Boolean = false, info: String = title, warnRule: WarnRule = WarnRule()) {
    val warn = warnRule.hits(value)
    val car = LocalCar.current
    val titleH = car.label.lineDp()
    val valueH = car.value.lineDp()
    val lineH = car.secondary.lineDp()
    // The card height depends on the screen; drop the scale labels and shrink the number when it is short
    // instead of letting the number get clipped.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
        val roomy = maxHeight >= titleH + valueH + lineH * 2 + 40.dp
        val tight = maxHeight < titleH + valueH + 30.dp
        val showSub = maxHeight >= titleH + valueH + lineH + 30.dp
        val shownTitle = if (tight) minOf(titleH, maxHeight * 0.3f) else titleH
        Panel(Modifier.fillMaxSize(), padding = if (tight) 8.dp else 12.dp, warn = warn) {
            CardTitle(icon, title, info, shownTitle, warn)
            FitValue(
                value?.let { format.format(it) } ?: "—", unit, if (big) car.value.value * 1.4f else car.value.value,
                Modifier.weight(1f).fillMaxWidth(), if (warn) C.Orange else C.Text, widest = listOf(format.format(min), format.format(max)).maxBy { it.length },
            )
            // the note only where the number keeps its size beside it
            if (sub != null && showSub) FitText(sub, car.secondary, Modifier.fillMaxWidth(), C.Sub)
            else if (roomy && !big) Spacer(Modifier.height(lineH))
            Spacer(Modifier.height(if (tight) 4.dp else 6.dp))
            ProgressBar(value?.let { (it - min) / (max - min) } ?: 0f, C.Accent, Modifier.fillMaxWidth(), if (tight) 5.dp else 7.dp, known = value != null)
            if (roomy) {
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth()) {
                    T(format.format(min), car.secondary, C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
                    T(format.format(max), car.secondary, C.Sub, maxLines = 1)
                }
            }
        }
    }
}

/** Card header: a small accent icon and the parameter name in the label size, smaller if a low or narrow card needs it. */
@Composable
fun CardTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, info: String = title,
    height: androidx.compose.ui.unit.Dp = LocalCar.current.label.lineDp(),
    /** Over its warning line: a triangle after the name, a sign that is not only a colour. */
    warn: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Violet.copy(alpha = 0.85f), modifier = Modifier.size(minOf(height * 0.8f, 24.dp)))
        Spacer(Modifier.width(6.dp))
        FitText(title, LocalCar.current.label, Modifier.weight(1f), C.Sub)
        if (warn) Icon(
            com.composables.icons.lucide.Lucide.TriangleAlert, "Выше нормы", tint = C.Orange, modifier = Modifier.size(minOf(height * 0.8f, 24.dp)),
        )
        InfoButton(title, info)
    }
}

/**
 * Big number + unit on one baseline, as large as [maxSp] but never taller or wider than its box. [widest] is the
 * longest text the number can take (the scale's ends): sized by it, the number keeps its size from «99» to «100».
 */
@Composable
fun FitValue(
    text: String, unit: String, maxSp: Float, modifier: Modifier, color: androidx.compose.ui.graphics.Color = C.Text,
    widest: String? = null, align: Alignment = Alignment.BottomStart,
    /** How often the number may change ([DigitGate]); 0 for a timer, or when the text has been through a gate already. */
    everyMs: Long = DIGITS_MS,
    /** The longest unit it can have, for one that changes. */
    widestUnit: String? = null,
) {
    val text = if (everyMs > 0L) rememberDigits(everyMs) { text }.value else text
    val unit = kg.forester.sg.data.tr(unit)
    val car = LocalCar.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = align) {
        val d = androidx.compose.ui.platform.LocalDensity.current
        // the unit goes along with the number, at the ratio of the secondary and value sizes: its room is part of the sum
        val unitShare = car.secondary.value / car.value.value
        val size = with(d) { fitValueSp(text, widest, unit, widestUnit, maxSp, maxWidth.toSp().value, maxHeight.toSp().value, unitShare, C.palette.digitEm) }
        Row {
            // tabular figures: every digit as wide as the others, so a changing number does not shift sideways
            Text(
                text, Modifier.alignByBaseline(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(fontSize = size.sp, color = color, fontWeight = C.palette.weight(FontWeight.Bold, size), fontFamily = if (C.palette.thin) androidx.compose.ui.text.font.FontFamily.SansSerif else null, fontFeatureSettings = "tnum"),
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(5.dp))
                // sized here with the number: past the floor of the parked screens
                Text(
                    unit, Modifier.alignByBaseline(), maxLines = 1,
                    style = androidx.compose.ui.text.TextStyle(fontSize = minOf(size * unitShare, car.secondary.value).sp, color = C.Sub),
                )
            }
        }
    }
}

/** Timing pulls this trip; pulses red while the ECU is pulling timing, tap for the list. */
@Composable
private fun KnockCard(stats: KnockStats, knockDeg: Float?, modifier: Modifier) {
    // knockDeg is the live pull (the FBKC where the ROM has one), not P23, which is a sum and can sit above 0 during a pull
    val active = Warn.of("knock").hits(knockDeg)
    var open by remember { mutableStateOf(false) }
    // the pulse runs only while timing is being pulled; its alpha is applied in the layer, not by recomposing the card
    val pulse = if (active) rememberInfiniteTransition(label = "knock").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse",
    ) else null
    val car = LocalCar.current
    Panel(modifier.fillMaxHeight().clip(Rounded).clickable { open = true }, padding = 12.dp, warn = active) {
        Row(Modifier.fillMaxWidth().height(car.label.lineDp()), verticalAlignment = Alignment.CenterVertically) {
            FitText("Детонация", car.label, Modifier.weight(1f), C.Sub)
            if (active) Icon(Lucide.TriangleAlert, "Откат зажигания", tint = C.Orange, modifier = Modifier.size(24.dp))
            InfoButton("Детонация", "drive.knock")
        }
        FitValue(
            knockDeg?.takeIf { it != 0f }?.let { "%.1f°".format(it) } ?: "нет", "", car.value.value,
            Modifier.weight(1f).fillMaxWidth().graphicsLayer {
                alpha = pulse?.value ?: 1f
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            },
            if (active) C.Red else C.Green, widest = "-8.8°", everyMs = 0L,
        )
        FitText(
            (stats.maxPullDeg?.let { "Откатов: ${stats.count} · %.1f°".format(it) } ?: "Откатов не было") +
                if (stats.bumpCount > 0) " · на кочках: ${stats.bumpCount}" else "",
            car.secondary, Modifier.fillMaxWidth(), C.Sub,
        )
    }
    if (open) KnockDialog(stats) { open = false }
}

@Composable
private fun KnockDialog(stats: KnockStats, onClose: () -> Unit) {
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    CarAlertDialog(
        onDismissRequest = onClose,
        containerColor = C.CardHi, textScrolls = false,
        title = { T("Откаты зажигания за поездку", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column {
                T(
                    "Это не отдельные стуки, а случаи, когда ЭБУ по сигналу датчика детонации убирал угол зажигания. " +
                        "Считаются откаты от ${"%.0f".format(-KnockStats.START_DEG)}° и глубже; случай длится, пока коррекция не вернётся к нулю.",
                    13.sp, C.Sub,
                )
                Spacer(Modifier.height(10.dp))
                if (stats.events.isEmpty()) T("С начала поездки откатов не было.", 14.sp)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(stats.events.asReversed()) { ev ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(time.format(Date(ev.startMs)), 13.sp, C.Sub, modifier = Modifier.width(70.dp))
                            T("%.1f°".format(ev.maxPullDeg), 15.sp, if (ev.maxPullDeg <= -3f) C.Red else C.Orange, FontWeight.Bold, Modifier.width(60.dp))
                            T(
                                listOfNotNull(
                                    ev.rpm?.let { "$it об/мин" },
                                    ev.loadGRev?.let { "%.2f г/об".format(it) },
                                    ev.throttlePct?.let { "дроссель %.0f%%".format(it) },
                                    ev.speedKmh?.let { kg.forester.sg.data.DistanceUnits.speedText(it) },
                                    if (ev.active) "идёт сейчас" else "%.1f с".format(ev.durationMs / 1000f),
                                    if (ev.bump) "похоже на кочку" else null,
                                ).joinToString(" · "),
                                13.sp, maxLines = 2, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                T(
                    "Редкие откаты на 1–2° под нагрузкой — обычная работа ЭБУ (плохой бензин, жара, кочки дают ложную детонацию). " +
                        "Стоит разобраться, если откаты глубже 3°, частые или идут на прогретом моторе при ровной езде. " +
                        "Все случаи пишутся в logs/knock.csv. «Похоже на кочку»: короткий неглубокий откат без нагрузки в тот же миг, что толчок по датчику магнитолы; " +
                        "в счёт откатов такие не идут, предупреждения от этого не меняются.",
                    12.sp, C.Sub,
                )
            }
        },
        confirmButton = { TextButton(onClose) { Text("Закрыть") } },
    )
}
