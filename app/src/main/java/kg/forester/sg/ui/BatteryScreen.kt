package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import com.composables.icons.lucide.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kg.forester.sg.service.BatteryMonitor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Verdict(val text: String, val color: Color)

private fun restingVerdict(v: Float?) = when {
    v == null -> Verdict("включите зажигание", C.Sub)
    v >= 12.5f -> Verdict("заряжен", C.Green)
    v >= 12.2f -> Verdict("заряжен частично", C.Orange)
    else -> Verdict("разряжен: зарядите", C.Red)
}

private fun crankVerdict(v: Float?) = when {
    v == null -> Verdict("запишется при пуске", C.Sub)
    v >= 10.0f -> Verdict("отлично держит пуск", C.Green)
    v >= 9.6f -> Verdict("норма", C.Orange)
    else -> Verdict("слабый: проверьте", C.Red)
}

private fun chargeVerdict(v: Float?) = when {
    v == null -> Verdict("через 30 с работы", C.Sub)
    v < 13.2f -> Verdict("недозаряд: проверьте", C.Red)
    v <= 14.8f -> Verdict("генератор в норме", C.Green)
    else -> Verdict("перезаряд: проверьте", C.Red)
}

@Composable
fun BatteryScreen() {
    val e by Repo.live.collectAsState()
    val b by Repo.battery.collectAsState()
    val version by Repo.dataVersion.collectAsState()
    var week by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val volts = rememberQuery(Triple(week, version, tick)) {
        val now = System.currentTimeMillis()
        if (week) Repo.db.volts(now - 7 * 86_400_000L, 3600_000L) else Repo.db.volts(now - 86_400_000L, 600_000L)
    }.value ?: emptyList()
    val events = rememberQuery(version) { Repo.db.batteryEvents(60) }.value ?: emptyList()
    val time = remember { SimpleDateFormat("dd.MM HH:mm", Locale.US) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val csv = remember { java.io.File(ctx.getExternalFilesDir(null), "logs/battery.csv") }
    var viewing by remember { mutableStateOf(false) }
    var csvTick by remember { mutableStateOf(0) }
    if (viewing) { LogViewer(csv) { viewing = false }; return }

    ScrollWhenLow(470.dp) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1.5f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // now: the voltage on a scale with the zones, and the charge estimated from the resting voltage
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 14.dp) {
                CardHead(Lucide.Battery, "Сейчас", "напряжение сети", C.Blue, "Сейчас")
                Spacer(Modifier.weight(1f))
                val v = e.batteryV
                ValueLine(v, if (v == null) C.Text else if (v < 12f || v > 15f) C.Red else if (v < 12.4f) C.Orange else C.Text)
                Spacer(Modifier.height(6.dp))
                VoltScale(v)
                Spacer(Modifier.height(8.dp))
                val soc = b.restingV?.let { BatteryMonitor.socPct(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when { soc == null -> Lucide.Battery; soc >= 80 -> Lucide.BatteryFull; soc >= 50 -> Lucide.BatteryMedium; else -> Lucide.BatteryLow },
                        null, tint = C.Sub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    T("заряд в покое: ", 12.sp, C.Sub)
                    T(soc?.let { "$it %" } ?: "—", 12.sp, weight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
            }
            VoltCard(Lucide.Moon, "Покой", "мотор заглушен", C.Violet, "Покой (зажигание, двигатель заглушен)",
                b.restingV, restingVerdict(b.restingV), b.restingTimeMs, Modifier.weight(1f),
                extra = b.restingParkedH?.let { Lucide.Clock to parkedText(it) },
                // an old reading: the app has not seen the engine standing with the ignition on since (it
                // connects a few seconds after the key, the start came first)
                extra2 = if (b.restingTimeMs > 0 && System.currentTimeMillis() - b.restingTimeMs > 20 * 3_600_000L)
                    Lucide.Info to "до пуска подождите 3 с" else null)
            VoltCard(Lucide.Zap, "Пуск", "просадка", C.Orange, "Просадка при пуске",
                b.crankMinV, crankVerdict(b.crankMinV), b.crankTimeMs, Modifier.weight(1f),
                extra = b.crankMs?.let { Lucide.Timer to "крутил стартер %.1f с".format(it / 1000f) },
                extra2 = listOfNotNull(
                    b.crankRecoveryS?.let { if (it < 0) "не вернулось к 13.2 В за минуту" else "13.2 В через %.0f с".format(it) },
                    b.crankTempC?.let { "впуск $it°" }, b.crankRpm?.let { "$it об/мин" },
                ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Lucide.Activity to it })
            VoltCard(Lucide.BatteryCharging, "Генератор", "заряд на ходу", C.Green, "Заряд от генератора",
                b.chargingV, chargeVerdict(b.chargingV), 0, Modifier.weight(1f),
                extra = b.chargingMinV?.let {
                    Lucide.Activity to ("%.2f…%.2f В".format(b.chargingMinV, b.chargingMaxV) +
                        (e.altDutyPct?.takeIf { d -> d > 0f }?.let { d -> " · загрузка %.0f %%".format(d) } ?: ""))
                },
                extra2 = listOfNotNull(b.chargeIdleV?.let { "ХХ %.2f".format(it) }, b.chargeRevV?.let { "2–3 тыс. %.2f В".format(it) })
                    .joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Lucide.Gauge to it })
        }
        Row(Modifier.weight(1.05f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeadIcon(Lucide.Activity, C.Green)
                    Spacer(Modifier.width(10.dp))
                    T("Последняя минута", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    InfoButton("Напряжение, последняя минута")
                }
                Spacer(Modifier.height(6.dp))
                VoltChart(Modifier.weight(1f).fillMaxWidth()) { LiveChart(rememberChart(Repo.history, ChartLine("В", C.Green) { it.batteryV }), 9f, 15f, it) }
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeadIcon(Lucide.History, C.Blue)
                    Spacer(Modifier.width(10.dp))
                    T("История", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Chip("24 часа", !week, { week = false })
                    Spacer(Modifier.width(6.dp))
                    Chip("Неделя", week, { week = true })
                    InfoButton("История напряжения")
                }
                Spacer(Modifier.height(6.dp))
                if (volts.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    T("За этот период записей нет", 13.sp, C.Sub)
                } else VoltChart(Modifier.weight(1f).fillMaxWidth()) { LiveChart(listOf(Series("В", volts.map { p -> p.second.toFloat() }, C.Blue)), 9f, 15f, it) }
            }
        }
        Panel(Modifier.weight(0.75f).fillMaxWidth(), padding = 12.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeadIcon(Lucide.ScrollText, C.Violet)
                Spacer(Modifier.width(10.dp))
                T("Журнал", 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                T("покой, пуски и заряд · также в logs/battery.csv", 12.sp, C.Sub, modifier = Modifier.weight(1f))
                if (csvTick >= 0 && csv.isFile) {
                    Chip("Открыть", false, { viewing = true }, Lucide.ChartLine)
                    Spacer(Modifier.width(6.dp))
                    LogFileActions(csv, small = true) { csvTick++ }
                }
                InfoButton("Журнал аккумулятора")
            }
            Spacer(Modifier.height(6.dp))
            if (events.isEmpty()) T("Пока пусто: запись появится после включения зажигания или запуска.", 13.sp, C.Sub)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(events.size) { i ->
                    val ev = events[i]
                    val (icon, name, verdict) = when (ev.type) {
                        "resting" -> Triple(Lucide.Moon, "Покой", restingVerdict(ev.value.toFloat()))
                        "crank" -> Triple(Lucide.Zap, "Пуск", crankVerdict(ev.value.toFloat()))
                        "charging" -> Triple(Lucide.BatteryCharging, "Заряд", chargeVerdict(ev.value.toFloat()))
                        else -> Triple(Lucide.Battery, ev.type, Verdict("", C.Text))
                    }
                    val extra = when (ev.type) {
                        "resting" -> listOfNotNull(ev.num("parked_h")?.let { parkedText(it.toFloat()) }, ev.num("soc_pct")?.let { "%.0f %%".format(it) })
                        "crank" -> listOfNotNull("%.1f с".format(ev.extra / 1000),
                            ev.num("recovery_s")?.let { if (it < 0) "не восстановилось" else "13.2 В за %.0f с".format(it) },
                            ev.num("intake_c")?.let { "%.0f°".format(it) })
                        "charging" -> listOfNotNull(ev.num("idle_v")?.let { "ХХ %.2f".format(it) }, ev.num("rev_v")?.let { "обор. %.2f".format(it) },
                            if (ev.extra > 0) "ген. %.0f %%".format(ev.extra) else null)
                        else -> emptyList()
                    }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = verdict.color, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        T(name, 13.sp, modifier = Modifier.width(56.dp))
                        T("%.2f В".format(ev.value), 14.sp, verdict.color, FontWeight.SemiBold, Modifier.width(72.dp))
                        T(extra, 12.sp, C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
                        T(time.format(Date(ev.timeMs)), 12.sp, C.Sub)
                    }
                }
            }
        }
    }
    }
}

/** "стояла 40 мин" / "стояла 9 ч"; under 2 h the battery still has surface charge. */
private fun parkedText(h: Float) = when {
    h < 1f -> "стояла %.0f мин".format(h * 60) + " (мало для точного замера)"
    h < 2f -> "стояла %.1f ч (мало для точного замера)".format(h)
    h < 48f -> "стояла %.0f ч".format(h)
    else -> "стояла %.0f дн.".format(h / 24)
}

@Composable
private fun HeadIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** Icon, short title, what it means in small letters, and the "i". */
@Composable
private fun CardHead(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color, info: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HeadIcon(icon, tint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(title, 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(sub, 11.sp, C.Sub, maxLines = 1)
        }
        InfoButton(title, info)
    }
}

@Composable
private fun ValueLine(v: Float?, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        T(fmtF(v, 2), 38.sp, if (v == null) C.Sub else color, FontWeight.Bold)
        T(" В", 15.sp, C.Sub, modifier = Modifier.padding(bottom = 7.dp))
    }
}

/** 10…15 V with the zones coloured (flat / resting / charging / too high) and a marker at the voltage. */
@Composable
private fun VoltScale(v: Float?) {
    val zones = listOf(10f to 12f to C.Red, 12f to 12.4f to C.Orange, 12.4f to 13.2f to C.Green.copy(alpha = 0.55f),
        13.2f to 14.8f to C.Green, 14.8f to 15f to C.Red)
    val label = C.Sub
    val marker = rememberSmooth((v ?: 10f).coerceIn(10f, 15f), 5f, known = { v != null })
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val h = 6.dp.toPx()
        val mid = 7.dp.toPx()
        val top = mid - h / 2
        val pad = 6.dp.toPx() // room for the marker and the labels at the ends
        val w = size.width - pad * 2
        fun x(u: Float) = pad + (u - 10f) / 5f * w
        zones.forEach { (r, col) ->
            drawRect(col.copy(alpha = col.alpha * 0.75f), androidx.compose.ui.geometry.Offset(x(r.first), top),
                androidx.compose.ui.geometry.Size(x(r.second) - x(r.first) - 2f, h))
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = label.toArgb(); textSize = 10.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        for (u in listOf(10f, 12f, 12.4f, 13.2f, 14.8f)) {
            val t = if (u % 1f == 0f) "%.0f".format(u) else "%.1f".format(u)
            paint.textAlign = when (u) { 10f -> android.graphics.Paint.Align.LEFT; 15f -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.CENTER }
            val tx = when (u) { 10f -> x(u) - pad; 15f -> x(u) + pad; 12.4f -> x(u) + 8.dp.toPx(); 12f -> x(u) - 6.dp.toPx(); 14.8f -> x(u) - 4.dp.toPx(); else -> x(u) }
            drawContext.canvas.nativeCanvas.drawText(kg.forester.sg.data.tr(t), tx, size.height - 2.dp.toPx(), paint)
        }
        if (v != null) {
            val mx = x(marker.value.floatValue.coerceIn(10f, 15f))
            drawCircle(C.Ink, 6.dp.toPx(), androidx.compose.ui.geometry.Offset(mx, mid))
            drawCircle(C.Card, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(mx, mid))
        }
    }
}

/** A voltage chart 9…15 V with the scale on the left. */
@Composable
private fun VoltChart(modifier: Modifier, chart: @Composable (Modifier) -> Unit) {
    Row(modifier) {
        Column(Modifier.width(30.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            T("15", 10.sp, C.Sub); T("12", 10.sp, C.Sub); T("9", 10.sp, C.Sub)
        }
        chart(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun VoltCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color, info: String,
    v: Float?, verdict: Verdict, timeMs: Long, modifier: Modifier,
    extra: Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = null,
    extra2: Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = null,
) {
    Panel(modifier.fillMaxHeight(), padding = 11.dp) {
        CardHead(icon, title, sub, tint, info)
        Spacer(Modifier.weight(1f))
        ValueLine(v, verdict.color.takeIf { v != null } ?: C.Text)
        Spacer(Modifier.height(4.dp))
        // the verdict as a coloured pill
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(verdict.color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(when (verdict.color) { C.Green -> Lucide.CircleCheck; C.Sub -> Lucide.Clock; else -> Lucide.TriangleAlert },
                null, tint = verdict.color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            T(verdict.text, 12.sp, verdict.color, FontWeight.Medium, maxLines = 2)
        }
        Spacer(Modifier.height(2.dp))
        if (extra != null) MiniLine(extra.first, extra.second)
        if (extra2 != null) MiniLine(extra2.first, extra2.second)
        if (timeMs > 0) MiniLine(Lucide.Clock, SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(timeMs)))
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MiniLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        T(text, 12.sp, C.Sub, maxLines = 1)
    }
}
