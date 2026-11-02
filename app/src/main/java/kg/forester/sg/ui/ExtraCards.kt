package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.CircleGauge
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.Fan
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mountain
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.TrendingUp
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.Disc3
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Navigation
import kg.forester.sg.data.Repo
import kg.forester.sg.service.Maintenance
import kg.forester.sg.service.Tpms
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cards of the engine guard, performance and sensors, usable on the home and "Динамика" boards. */
val ExtraCards: Map<String, Pair<String, ImageVector>> = linkedMapOf(
    "defi" to ("Спорт-дисплей" to Lucide.Gauge),
    "navcluster" to ("Приборка с картой" to Lucide.Navigation),
    "gmeter" to ("G-метр" to Lucide.Crosshair),
    "incline" to ("Крен и уклон" to Lucide.Mountain),
    "iam" to ("IAM и детонация" to Lucide.Flame),
    "btarget" to ("Наддув: цель / факт" to Lucide.Fan),
    "peaks" to ("Пики поездки" to Lucide.TrendingUp),
    "run" to ("Разгон" to Lucide.Timer),
    "turbo" to ("Турбина и масло" to Lucide.Activity),
    "tyres" to ("Давление в шинах" to Lucide.Disc3),
    "service" to ("Ближайшее ТО" to Lucide.Wrench),
) + (1..6).associate { "s$it" to ("Датчик $it" to Lucide.ListChecks) } + GaugePanelCards

/** The card for [id] from [ExtraCards], or false if [id] is not one of them. [style]: the card's own settings, if it has any. */
@Composable
fun ExtraCard(id: String, info: String, m: Modifier, style: String = ""): Boolean {
    when (id) {
        "defi" -> SportDisplayCard(info, m, style)
        "navcluster" -> NavClusterCard(info, m, style)
        "gmeter" -> GMeterCard(info, m)
        "incline" -> InclineCard(info, m)
        "iam" -> IamCard(info, m)
        "btarget" -> BoostTargetCard(info, m)
        "peaks" -> PeaksCard(info, m)
        "run" -> RunCard(info, m)
        "turbo" -> TurboCard(info, m)
        "tyres" -> TyresCard(info, m)
        "service" -> ServiceCard(info, m)
        in GaugePanelCards -> GaugePanel(id, m)
        else -> {
            val k = id.removePrefix("s").toIntOrNull()
            if (!id.startsWith("s") || k == null) return false
            SensorSlotCard(k, info, m)
        }
    }
    return true
}

@Composable
private fun Card(m: Modifier, icon: ImageVector, title: String, info: String, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Panel(m, padding = 10.dp) {
        CardTitle(icon, title, info)
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

private fun sevColor(level: Int) = when (level) { 0 -> C.Green; 1 -> C.Orange; else -> C.Red }

@Composable
fun GMeterCard(info: String, m: Modifier, big: Boolean = false) {
    val motion = Repo.motion.collectAsState()
    val latDot = rememberSmooth(motion.slot { (it.latG ?: 0f).coerceIn(-1.2f, 1.2f) }, 2.4f)
    val lonDot = rememberSmooth(motion.slot { (it.lonG ?: 0f).coerceIn(-1.2f, 1.2f) }, 2.4f)
    val latText by rememberDigits { motion.value.latG?.let { "%.2f".format(abs(it)) } ?: "—" }
    val lonText by rememberDigits { motion.value.lonG?.let { "%.2f".format(abs(it)) } ?: "—" }
    val speedingUp by motion.slot { (it.lonG ?: 0f) >= 0f }
    val peaks by motion.slot { "макс: бок %.2f · разгон %.2f · тормоз %.2f".format(it.maxLat, it.maxAccel, it.maxBrake) }
    val source by motion.slot { it.source }
    Card(m, Lucide.Crosshair, "G-метр", info) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).padding(4.dp)) {
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                for (g in listOf(0.5f, 1f)) drawCircle(C.Border, r * g, c, style = Stroke(1.5f))
                drawLine(C.Border, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                drawLine(C.Border, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                val lat = latDot.value.floatValue
                val lon = lonDot.value.floatValue
                // up = speeding up (the body pushes back, but drivers read it as "forward"), right = right turn
                val p = Offset(c.x + lat * r, c.y - lon * r)
                drawCircle(C.Violet.copy(alpha = 0.25f), r * 0.16f, p)
                drawCircle(C.Violet, r * 0.08f, p)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                FitText("бок $latText g", if (big) LocalCar.current.label else LocalCar.current.secondary, Modifier.fillMaxWidth())
                FitText(if (speedingUp) "разгон $lonText g" else "тормоз $lonText g", if (big) LocalCar.current.label else LocalCar.current.secondary, Modifier.fillMaxWidth())
                FitText(peaks, LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 2)
                FitText(source, LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub)
            }
        }
    }
}

@Composable
fun InclineCard(info: String, m: Modifier) {
    val st by Repo.motion.collectAsState()
    Card(m, Lucide.Mountain, "Крен и уклон", info) {
        if (st.rollDeg != null && st.pitchDeg != null) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Tilt("крен", st.rollDeg!!, rear = true, Modifier.weight(1f))
                Tilt("тангаж", st.pitchDeg!!, rear = false, Modifier.weight(1.4f))
            }
            st.gradePct?.let { T("уклон %+.0f %%".format(it), LocalCar.current.secondary, C.Sub, modifier = Modifier.align(Alignment.TopEnd), maxLines = 1) }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                FitValue(st.gradePct?.let { "%+.0f".format(it) } ?: "—", "% уклон", LocalCar.current.value.value, Modifier.fillMaxWidth().weight(1f), widest = "+88")
                FitText(if (st.hasSensor) "Датчик есть: обнулите его в «Замеры» → «G-метр» на ровном месте"
                else "Уклон по GPS. Крена нет: в магнитоле нет датчика наклона", LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Tilt(label: String, deg: Float, rear: Boolean, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val model = car3dNow()
    val paint by Repo.carPaint.collectAsState()
    // another make: a plain outline instead of the Subaru (see CarKind)
    val other by kg.forester.sg.data.CarKind.other.collectAsState()
    // the chosen 3D car: from behind for the roll, from the side (nose left) for the pitch
    val still by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, model, paint, rear) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { carStill(ctx, model, paint, side = !rear) }.getOrNull() }
    }
    val shown = if (abs(deg) < 0.5f) 0f else deg
    val col = if (abs(deg) > 25) C.Red else if (abs(deg) > 15) C.Orange else C.Green
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val img = still?.asImageBitmap()
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(4.dp).clipToBounds()) {
            // the car stands on the ground line; both turn together around the middle of the line
            val ground = size.height * 0.9f
            val c = Offset(size.width / 2, ground)
            drawLine(C.Border, Offset(0f, ground), Offset(size.width, ground), 2f)
            rotate(shown, c) {
                drawLine(col, Offset(size.width * 0.04f, ground), Offset(size.width * 0.96f, ground), 3f)
                if (other) outlineCarOnGround(c.x, ground, rear)
                else if (img != null) {
                    // room for the corners when tilted: ~86 % of the width, ~90 % of the height above the line
                    val k = minOf(size.width * 0.86f / img.width, ground * 0.9f / img.height)
                    val w = img.width * k; val h = img.height * k
                    drawImage(
                        img, dstOffset = androidx.compose.ui.unit.IntOffset((c.x - w / 2).toInt(), (ground - h).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                    )
                }
            }
        }
        T("$label %+.0f°".format(shown), LocalCar.current.secondary, col, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun IamCard(info: String, m: Modifier) {
    val g by Repo.guard.collectAsState()
    Card(m, Lucide.Flame, "IAM и детонация", info) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            val iam = g.iam
            val learned = g.learnedDeg
            if (iam == null && learned != null) {
                // no IAM address for this ROM: the ECU's own learned timing tells the same story, in degrees
                FitValue("%.1f".format(learned), "° обуч. УОЗ", LocalCar.current.value.value, Modifier.fillMaxWidth().weight(1f), widest = "-8.8")
                val kc = Repo.live.collectAsState().value.knockDeg
                FitText("Обученный угол: падает, когда мотор ловит детонацию, после сброса ЭБУ обучается заново" +
                    (kc?.let { " · коррекция сейчас %.1f°".format(it) } ?: ""), LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 3)
            } else {
                val lvl = when { iam == null -> 0; iam >= 0.95f -> 0; iam >= 0.75f -> 1; else -> 2 }
                FitValue(iam?.let { "%.2f".format(it) } ?: "—", "IAM", LocalCar.current.value.value, Modifier.fillMaxWidth().weight(1f), sevColor(lvl), widest = "8.88")
                FitText(listOfNotNull(
                    g.fbkc?.let { "FBKC %.1f°".format(it) }, g.flkc?.let { "FLKC %.1f°".format(it) },
                ).joinToString(" · ").ifEmpty {
                    if (iam == null) "IAM нет в определениях этой прошивки: пришлите ROM ID из Настроек, поищем адрес" else ""
                }, LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 2)
            }
        }
    }
}

@Composable
fun BoostTargetCard(info: String, m: Modifier) {
    val g by Repo.guard.collectAsState()
    val e by Repo.live.collectAsState()
    Card(m, Lucide.Fan, "Наддув: цель / факт", info) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            val fact = e.boostBar
            val target = g.targetBoostKpa?.let { maxOf(0f, it) / 100f }
            val top = maxOf(1.4f, boostTop, (target ?: 0f) + 0.1f)
            Bar("цель", target, top, C.Blue)
            Bar("факт", fact, top, if (target != null && fact != null && fact > target + 0.15f) C.Red else C.Green)
            if (target == null) FitText("цели нет в определениях этой прошивки", LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub)
        }
    }
}

@Composable
private fun Bar(label: String, v: Float?, max: Float, col: Color, fmt: String = " %.2f") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label, LocalCar.current.secondary, C.Sub, modifier = Modifier.width(LocalCar.current.secondary.asDp() * 2.4f), maxLines = 1)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.CardHi)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(((v ?: 0f) / max).coerceIn(0f, 1f)).background(col))
        }
        T(v?.let { fmt.format(it) } ?: " —", LocalCar.current.secondary, modifier = Modifier.width(LocalCar.current.secondary.asDp() * 2.8f), maxLines = 1)
    }
}

@Composable
fun PeaksCard(info: String, m: Modifier) {
    val p by Repo.peaks.collectAsState()
    val tcu by Repo.tcu.collectAsState()
    Panel(m, padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardTitle(Lucide.TrendingUp, "Пики поездки", info) }
            T("сброс", LocalCar.current.secondary, C.Sub, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.CardHi)
                .clickable { Repo.peaks.value = kg.forester.sg.service.Peaks() }.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        val rows = listOfNotNull(
            p.boostBar?.let { "наддув" to "%.2f бар".format(it) },
            p.mafGs?.let { "MAF" to "%.0f г/с".format(it) },
            p.loadGRev?.let { "нагрузка" to "%.2f г/об".format(it) },
            p.rpm?.let { "обороты" to "$it" },
            p.speedKmh?.let { "скорость" to kg.forester.sg.data.DistanceUnits.speedText(it) },
            p.intakeC?.let { "впуск" to "$it°" },
            p.coolantC?.let { "ОЖ" to "$it°" },
            (p.atfC ?: tcu?.atfC)?.takeIf { p.atfC != null }?.let { "ATF" to "$it°" },
            p.knockDeg?.takeIf { it != 0f }?.let { "детонация" to "%.1f°".format(it) },
            p.leanAfrUnderBoost?.let { "AFR под наддувом" to "%.1f".format(it) },
        )
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            if (rows.isEmpty()) T("—", LocalCar.current.secondary, C.Sub)
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (k, v) ->
                        // the value whole, the name with what is left, and room before the next pair
                        Row(Modifier.weight(1f).padding(end = 10.dp)) {
                            FitText("$k ", LocalCar.current.secondary, Modifier.weight(1f, fill = false), C.Sub)
                            FitText(v, LocalCar.current.secondary, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun RunCard(info: String, m: Modifier) {
    val p by Repo.perf.collectAsState()
    Card(m, Lucide.Timer, "Разгон", info) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            when {
                p.running -> {
                    FitValue("%.2f".format(p.elapsedSec), "с", LocalCar.current.value.value, Modifier.fillMaxWidth().weight(1f), C.Violet, widest = "88.88", everyMs = 0L)
                    FitText(kg.forester.sg.data.DistanceUnits.speedText(p.speedKmh) + " · %.0f м".format(p.distanceM) +
                        (p.live[kg.forester.sg.service.PerfCalc.headline]?.let { " · ${resultShort(kg.forester.sg.service.PerfCalc.headline)} %.2f с".format(it) } ?: ""), LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub)
                }
                else -> {
                    val last = p.runs.firstOrNull { kg.forester.sg.service.PerfCalc.headline in it.results }
                    val best = p.runs.mapNotNull { it.results[kg.forester.sg.service.PerfCalc.headline] }.minOrNull()
                    FitValue(last?.results?.get(kg.forester.sg.service.PerfCalc.headline)?.let { "%.2f".format(it) } ?: "—", "с ${resultShort(kg.forester.sg.service.PerfCalc.headline)}", LocalCar.current.value.value, Modifier.fillMaxWidth().weight(1f), widest = "88.88")
                    FitText((if (p.armed) "готов: трогайтесь" else "встаньте, чтобы засечь") +
                        (best?.let { " · лучший %.2f".format(it) } ?: ""), LocalCar.current.secondary, Modifier.fillMaxWidth(), if (p.armed) C.Green else C.Sub)
                }
            }
        }
    }
}

@Composable
fun TurboCard(info: String, m: Modifier) {
    val g by Repo.guard.collectAsState()
    Card(m, Lucide.Activity, "Турбина и масло", info) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            if (g.turbo) {
                Bar("жар", g.heat, 100f, if (g.heat > 60) C.Red else if (g.heat > 30) C.Orange else C.Green, " %.0f")
                FitText(when {
                    g.cooldownSec > 0 -> "подождите ${g.cooldownSec} с перед выключением"
                    g.heat > 12f -> "после остановки — ~${g.heat.toInt()} с на холостых"
                    else -> "можно глушить"
                }, LocalCar.current.secondary, Modifier.fillMaxWidth(), if (g.heat > 12f) C.Orange else C.Green)
            }
            FitText("масло ${g.oilC?.let { "${it.roundToInt()}°" } ?: "—"}${if (g.oilEstimated && g.oilC != null) " (оценка)" else ""}" +
                if (g.cold) " · не выше 3000 об/мин" else "", LocalCar.current.secondary, Modifier.fillMaxWidth(), if (g.cold) C.Orange else C.Text, maxLines = 2)
        }
    }
}

@Composable
fun TyresCard(info: String, m: Modifier) {
    val t by Repo.tyres.collectAsState()
    val min = Repo.settings.tpmsMinBar
    Card(m, Lucide.Disc3, "Давление в шинах", info) {
        if (t.isEmpty()) {
            FitText(if (Repo.settings.tpms) "ищу датчики…" else "включите в «Мониторинг» → «Шины»", LocalCar.current.secondary, Modifier.align(Alignment.Center), C.Sub, maxLines = 3)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            listOf("FL" to "FR", "RL" to "RR").forEach { (a, b) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (w in listOf(a, b)) {
                        val r = t[w]
                        T(r?.let { "%.1f".format(it.bar) } ?: "—", LocalCar.current.label,
                            if (r == null) C.Sub else if (r.bar < min || r.leak) C.Red else C.Text, FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceCard(info: String, m: Modifier) {
    val version by Repo.maintenanceVersion.collectAsState()
    val data by Repo.dataVersion.collectAsState()
    // the odometer comes from the trips table: read it when trips or the plan change, not on every frame
    val odo = androidx.compose.runtime.remember(version, data) { Maintenance.odometer() }
    val items = androidx.compose.runtime.remember(version) { Maintenance.items() }
    val now = System.currentTimeMillis()
    val next = items.filter { it.on }.maxByOrNull { it.used(odo, now) ?: -1f }
    Card(m, Lucide.Wrench, "Ближайшее ТО", info) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            if (odo == null || next?.used(odo, now) == null) {
                FitText("Введите пробег и даты в «Мониторинг» → «ТО»", LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 3)
            } else {
                val km = next.kmLeft(odo)?.roundToInt()
                val days = next.daysLeft(now)
                FitText(next.name, LocalCar.current.secondary, Modifier.fillMaxWidth(), maxLines = 2)
                FitText(listOfNotNull(km?.let { if (it < 0) "просрочено ${kg.forester.sg.data.DistanceUnits.distanceText(-it.toDouble())}" else "через ${kg.forester.sg.data.DistanceUnits.distanceText(it.toDouble())}" },
                    next.hoursLeft(Maintenance.hours())?.roundToInt()?.let { if (it < 0) "просрочено ${-it} м·ч" else "$it м·ч" },
                    days?.let { if (it < 0) "${-it} дн. назад" else "$it дн." }).joinToString(" · "), LocalCar.current.secondary, Modifier.fillMaxWidth(),
                    if (next.due(odo, now)) C.Orange else C.Sub)
            }
        }
    }
}

@Composable
fun SensorSlotCard(k: Int, info: String, m: Modifier) {
    val avail by Repo.rrAvailable.collectAsState()
    val values by Repo.rrValues.collectAsState()
    Repo.watchVersion.collectAsState().value
    Repo.slotVersion.collectAsState().value
    val chosen = Repo.settings.slot(k)
    if (chosen.isNotEmpty()) {
        val ind = Metrics.of(chosen, avail)
        val v by metricSlot(rememberMetricCtx(), chosen to avail) { ind?.read(it) }
        Card(m, ind?.icon ?: Lucide.ListChecks, ind?.title ?: chosen.removePrefix("rr:"), info) {
            if (ind == null) FitText("Параметр появится, когда приложение подключится к машине", LocalCar.current.secondary, Modifier.fillMaxWidth(), C.Sub, maxLines = 3)
            else FitValue(ind.text(v), ind.unit, LocalCar.current.value.value, Modifier.fillMaxSize(), if (ind.warn(v)) C.Orange else C.Ink, widest = "888.8")
        }
        return
    }
    val ticked = avail.filter { it.id in Repo.settings.watchIds }
    val p = ticked.getOrNull(k - 1)
    Card(m, Lucide.ListChecks, p?.name ?: "Датчик $k", info) {
        if (p == null) FitText("Выберите показатель кнопкой со списком в режиме редактирования (долгое нажатие) " +
            "или отметьте параметр на экране «Датчики» — $k-й по списку появится здесь", LocalCar.current.secondary, Modifier.fillMaxSize(), C.Sub, maxLines = 4)
        else FitValue(p.text(values[p.id]), p.unitsRu, LocalCar.current.value.value, Modifier.fillMaxSize(), widest = "888.8")
    }
}

/** Pick what the «Датчик [k]» card shows: any value of the app or a parameter ticked on «Датчики». */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SlotPicker(k: Int, onClose: () -> Unit) {
    val avail by Repo.rrAvailable.collectAsState()
    Repo.slotVersion.collectAsState().value
    val chosen = Repo.settings.slot(k)
    CarDialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(760.dp).heightIn(max = minOf(560f, canvasScreen().screenHeightDp * 0.94f).dp).clip(RoundedCornerShape(16.dp)).background(C.Sheet)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Датчик $k: что показывать", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("По списку «Датчики»", chosen.isEmpty(), { Repo.settings.setSlot(k, "") })
                Spacer(Modifier.width(8.dp))
                Chip("Готово", true, onClose)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.cueScroll()) {
                Metrics.all(avail, Repo.settings.watchIds).groupBy { it.group }.forEach { (group, list) ->
                    T(group, 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        list.forEach { ind -> Chip(ind.title, ind.id == chosen, { Repo.settings.setSlot(k, ind.id) }, ind.icon) }
                    }
                }
            }
        }
    }
}
