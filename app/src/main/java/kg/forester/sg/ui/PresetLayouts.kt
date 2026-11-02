package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Undo2
import kg.forester.sg.data.FreeRect
import kg.forester.sg.data.MyBoard
import kg.forester.sg.data.Repo
import kg.forester.sg.data.TileCfg

/**
 * A ready-made «Мой экран»: the cards it shows, in order (grid) or at their places ([free]). Everything else of the
 * board is appended switched off, so the driver can go on editing it as usual.
 */
class PresetLayout(val id: String, val name: String, val about: String, val free: Boolean, val tiles: List<TileCfg>) {
    fun format(): String = MyBoard.format(tiles + MyBoard.defaults.filter { d -> tiles.none { it.id == d.id } }.map { it.copy(on = false) }, free)
}

private fun t(id: String, span: Int, style: String = "") = TileCfg(id, true, span, style)
private fun f(id: String, x: Float, y: Float, w: Float, h: Float, style: String = "") = TileCfg(id, true, 3, style, FreeRect(x, y, w, h))

private fun panel(id: String, name: String, about: String) = PresetLayout(id, name, about, free = false, tiles = listOf(t(id, 12)))

/*
 * The free one is laid out for the usual head unit area (about twice as wide as tall: 1280 × 720 minus the side
 * rail and the bars), so its dials come out round there; the grid ones fit any screen.
 */
val PresetLayouts = listOf(
    PresetLayout(
        "cluster", "Приборка", "Тахометр и спидометр по краям, температуры и передача между ними, внизу расход и запас хода",
        free = true,
        tiles = listOf(
            f("rpm", 0.01f, 0.02f, 0.31f, 0.66f, "sti"),
            f("coolant", 0.335f, 0.03f, 0.16f, 0.32f, "sti"),
            f("oil", 0.505f, 0.03f, 0.16f, 0.32f, "sti"),
            f("gear", 0.335f, 0.37f, 0.33f, 0.29f),
            f("speed", 0.68f, 0.02f, 0.31f, 0.66f, "sti"),
            f("battery", 0.01f, 0.70f, 0.24f, 0.28f),
            f("per100", 0.255f, 0.70f, 0.24f, 0.28f),
            f("range", 0.50f, 0.70f, 0.24f, 0.28f),
            f("fuelLeft", 0.745f, 0.70f, 0.245f, 0.28f),
        ),
    ),
    PresetLayout(
        "nav", "Навигация", "Приборка с картой и подсказками навигатора, под ней четыре главных числа",
        free = false,
        tiles = listOf(t("navcluster", 12), t("coolant", 3), t("per100", 3), t("range", 3), t("battery", 3)),
    ),
    PresetLayout(
        "turbo", "Турбо", "Наддув и обороты крупно, смесь, IAM, откат зажигания и всё, что важно под наддувом (для турбомоторов)",
        free = false,
        tiles = listOf(
            t("boost", 6, "pursuit"), t("rpm", 6, "pursuit"),
            t("afr", 3), t("iamV", 3), t("fbkc", 3), t("intake", 3),
            t("timing", 3), t("targetBoost", 3), t("injDuty", 3), t("coolant", 3),
        ),
    ),
    PresetLayout(
        "sport", "Спорт", "Спорт-дисплей и G-метр, под ними температуры, смесь и детонация",
        free = false,
        tiles = listOf(t("defi", 8), t("gmeter", 4), t("coolant", 3), t("oil", 3), t("afr", 3), t("knock", 3)),
    ),
    PresetLayout(
        "trip", "Трасса", "Скорость, расход и запас хода крупно, внизу итоги поездки",
        free = false,
        tiles = listOf(
            t("speed", 4, "minimal"), t("per100", 4, "minimal"), t("range", 4, "minimal"),
            t("tripAvg", 3), t("tripFuel", 3), t("coolant", 3), t("battery", 3),
        ),
    ),
    PresetLayout(
        "night", "Ночной LCD", "Три прибора с цифрами в стиле ЖК-табло, мягкие для глаз ночью",
        free = false,
        tiles = listOf(
            t("rpm", 4, "lcd_night"), t("speed", 4, "lcd_night"), t("coolant", 4, "lcd_night"),
            t("battery", 3), t("per100", 3), t("range", 3), t("oil", 3),
        ),
    ),
    PresetLayout(
        "minimal", "Минимум", "Только скорость, температура и напряжение: три крупных прибора без лишнего",
        free = false,
        tiles = listOf(t("speed", 4, "minimal"), t("coolant", 4, "minimal"), t("battery", 4, "minimal")),
    ),
    PresetLayout(
        "diag", "Диагностика", "Шестнадцать чисел: температуры, смесь и коррекции, зажигание, нагрузка",
        free = false,
        tiles = listOf(
            "coolant", "intake", "oil", "battery", "afr", "afcorr", "aflearn", "maf",
            "timing", "knock", "iamV", "load", "rpm", "throttle", "injDuty", "wgdc",
        ).map { t(it, 3) },
    ),
    panel("p_tacho_blue", "Тахо-арка: синяя", "Арка оборотов с красным концом, скорость крупно, по краям ОЖ и топливо, внизу расход и пробег"),
    panel("p_tacho_orange", "Тахо-арка: оранжевая", "Та же раскладка, тёплая оранжевая арка"),
    panel("p_tacho_teal", "Тахо-арка: бирюзовая", "Та же раскладка, градиент и точка на конце арки"),
    panel("p_turbo", "Турбо-панель", "Наддув в барах и PSI с разрежением ниже нуля, сбоку расход, скорость, ОЖ, пробег (только с турбиной)"),
    panel("p_tilt", "Крен и тангаж", "Машина сбоку и сзади наклоняется по датчику, шкалы ±40° (нужен обнулённый акселерометр)"),
    panel("p_gradar", "G-радар", "Сектор по направлению перегрузки, в центре G и скорость (нужен обнулённый акселерометр)"),
    panel("gforce", "G-сила", "Точка по перегрузке и максимумы по четырём сторонам (нужен обнулённый акселерометр)"),
    panel("p_web", "Паутина", "Скорость в центре, вокруг шесть значений: что есть из ОЖ, напряжения, оборотов, наддува, времени, высоты"),
    panel("p_accel", "Разгон: график", "Кривая последнего разгона 0–100: время и путь"),
    panel("p_brake", "Торможение: график", "Кривая последнего торможения 100–0: время и путь"),
)

/** Puts [p] on «Мой экран»; the driver's own layout (not another ready-made one) is kept for «Вернуть мой экран». */
fun applyPreset(p: PresetLayout) {
    val now = Repo.settings.board(MyBoard.key)
    if (now.isNotEmpty() && PresetLayouts.none { it.format() == now }) Repo.settings.myTilesPrev = now
    Repo.settings.setBoard(MyBoard.key, p.format())
}

/** The ready-made layouts with a drawn picture of each; a tap puts one on the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetPicker(onClose: () -> Unit) {
    val current by Repo.myTiles.collectAsState()
    val prev = Repo.settings.myTilesPrev
    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = canvasScreen()
        Column(
            Modifier.widthIn(max = (screen.screenWidthDp * 0.96f).dp).heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .clip(RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Шаблоны экрана", 17.sp, weight = FontWeight.SemiBold)
                    T("Шаблон заменит раскладку, дальше её можно править как обычно", 12.sp, C.Sub)
                }
                if (prev.isNotEmpty() && prev != current) {
                    Chip("Вернуть мой экран", false, { Repo.settings.setBoard(MyBoard.key, prev); onClose() }, Lucide.Undo2)
                    Spacer(Modifier.width(8.dp))
                }
                Chip("Закрыть", false, onClose)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                Modifier.cueScroll(),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PresetLayouts.filter { l -> l.tiles.all { panelOffered(it.id) } }.forEach { p ->
                    val on = p.format() == current
                    Column(
                        Modifier.width(286.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                            .border(if (on) 2.dp else 1.dp, if (on) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(12.dp))
                            .clickable { applyPreset(p); onClose() }.padding(8.dp),
                    ) {
                        Canvas(Modifier.fillMaxWidth().aspectRatio(2.1f).clip(RoundedCornerShape(8.dp)).background(C.Bg)) { drawPreset(p) }
                        Spacer(Modifier.height(6.dp))
                        T(p.name, 15.sp, weight = FontWeight.SemiBold)
                        T(p.about, 12.sp, C.Sub, maxLines = 3)
                    }
                }
            }
        }
    }
}

/** A small picture of [p]: each card where it would be, drawn by its kind. */
private fun DrawScope.drawPreset(p: PresetLayout) {
    val gap = size.width * 0.012f
    val placed: List<Pair<TileCfg, androidx.compose.ui.geometry.Rect>> = if (p.free) p.tiles.mapNotNull { t ->
        val r = t.free ?: return@mapNotNull null
        t to androidx.compose.ui.geometry.Rect(r.x * size.width + gap / 2, r.y * size.height + gap / 2, (r.x + r.w) * size.width - gap / 2, (r.y + r.h) * size.height - gap / 2)
    } else {
        val rows = MyBoard.rows(p.tiles)
        val weights = rows.map { r -> r.maxOf(::myRowWeight) }
        val total = weights.sum()
        var y = gap
        val out = ArrayList<Pair<TileCfg, androidx.compose.ui.geometry.Rect>>()
        rows.forEachIndexed { i, row ->
            val h = (size.height - gap * (rows.size + 1)) * weights[i] / total
            val spans = row.sumOf { it.span }.toFloat()
            var x = gap
            val wAll = size.width - gap * (row.size + 1)
            row.forEach { t ->
                val w = wAll * t.span / spans
                out += t to androidx.compose.ui.geometry.Rect(x, y, x + w, y + h)
                x += w + gap
            }
            y += h + gap
        }
        out
    }
    placed.forEach { (t, r) -> drawTile(t, r) }
}

private fun DrawScope.drawTile(t: TileCfg, r: androidx.compose.ui.geometry.Rect) {
    val corner = CornerRadius(minOf(r.width, r.height) * 0.12f)
    val c = r.center
    when {
        t.id == "defi" -> {
            drawRoundRect(Color(0xFF040508), r.topLeft, r.size, corner)
            // the J of the tachometer: up the left, round the corner, along the top
            val s = r.height * 0.12f
            val path = Path().apply {
                moveTo(r.left + r.width * 0.08f, r.bottom - r.height * 0.12f)
                lineTo(r.left + r.width * 0.08f, r.top + r.height * 0.42f)
                quadraticTo(r.left + r.width * 0.08f, r.top + r.height * 0.16f, r.left + r.width * 0.26f, r.top + r.height * 0.16f)
                lineTo(r.right - r.width * 0.06f, r.top + r.height * 0.16f)
            }
            drawPath(path, Color(0xFFE9ECF2), style = Stroke(s, cap = StrokeCap.Butt))
            drawLine(Color(0xFFE5262B), Offset(r.right - r.width * 0.2f, r.top + r.height * 0.16f), Offset(r.right - r.width * 0.06f, r.top + r.height * 0.16f), s)
            label("88", Offset(r.left + r.width * 0.62f, r.top + r.height * 0.7f), r.height * 0.3f, Color(0xFFF4F5F8))
        }
        t.id == "navcluster" -> {
            drawRoundRect(Color(0xFF070B14), r.topLeft, r.size, corner)
            val road = Color(0xFF3D5172)
            for (k in 1..4) drawLine(road, Offset(r.left + r.width * (0.3f + k * 0.08f), r.top), Offset(r.left + r.width * (0.25f + k * 0.1f), r.bottom), r.height * 0.02f)
            drawLine(road, Offset(r.left + r.width * 0.3f, r.top + r.height * 0.4f), Offset(r.right - r.width * 0.3f, r.top + r.height * 0.55f), r.height * 0.03f)
            drawLine(Color(0xFF4FC3F7), Offset(c.x, r.bottom - r.height * 0.1f), Offset(c.x, r.top + r.height * 0.62f), r.height * 0.04f)
            val d = minOf(r.height * 0.9f, r.width * 0.3f) / 2
            listOf(r.left + r.width * 0.015f + d, r.right - r.width * 0.015f - d).forEach { x ->
                drawCircle(Color(0xFF070B14), d, Offset(x, c.y))
                ring(Offset(x, c.y), d * 0.9f, Color(0xFF4FC3F7), 0.6f)
            }
        }
        t.id in kg.forester.sg.data.GAUGE_PANELS -> {
            drawRoundRect(Color(0xFF06080D), r.topLeft, r.size, corner)
            ring(Offset(c.x, c.y + r.height * 0.08f), minOf(r.width, r.height) * 0.36f, Color(0xFF4FC3F7), 0.6f)
            label(ExtraCards[t.id]?.first ?: "", Offset(c.x, r.bottom - r.height * 0.08f), r.height * 0.1f, Color(0xFFE8EAEE))
        }
        t.id == "gmeter" -> {
            drawRoundRect(C.Card, r.topLeft, r.size, corner)
            val d = minOf(r.width, r.height) * 0.38f
            drawCircle(C.Border, d, c, style = Stroke(r.height * 0.02f))
            drawCircle(C.Border, d * 0.5f, c, style = Stroke(r.height * 0.02f))
            drawCircle(C.Violet, d * 0.12f, Offset(c.x + d * 0.3f, c.y - d * 0.2f))
        }
        t.style.isNotEmpty() -> {
            drawRoundRect(C.Card, r.topLeft, r.size, corner)
            val st = dialStyle(t.style)
            val d = minOf(r.width, r.height) * 0.42f
            drawCircle(st.face.lastOrNull() ?: C.Bg, d, c)
            ring(c, d * 0.86f, st.arc?.lastOrNull() ?: st.needle, 0.55f, st.track)
            label(Metrics.of(t.id, emptyList())?.short ?: "", Offset(c.x, c.y + d * 0.2f), d * 0.32f, st.numbers)
        }
        else -> {
            drawRoundRect(C.CardHi, r.topLeft, r.size, corner)
            label(Metrics.of(t.id, emptyList())?.short ?: "", Offset(c.x, c.y + r.height * 0.08f), minOf(r.height * 0.28f, r.width * 0.16f), C.Sub)
        }
    }
}

private fun DrawScope.ring(c: Offset, rad: Float, col: Color, share: Float, track: Color = Color(0x33FFFFFF)) {
    val tl = Offset(c.x - rad, c.y - rad)
    val sz = Size(rad * 2, rad * 2)
    val st = Stroke(rad * 0.14f, cap = StrokeCap.Round)
    drawArc(track, 135f, 270f, false, tl, sz, style = st)
    drawArc(col, 135f, 270f * share, false, tl, sz, style = st)
}

private fun DrawScope.label(text: String, at: Offset, size: Float, col: Color) {
    if (text.isEmpty() || size < 4f) return
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = col.toArgb(); textSize = size; textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    drawContext.canvas.nativeCanvas.drawText(kg.forester.sg.data.tr(text), at.x, at.y, p)
}
