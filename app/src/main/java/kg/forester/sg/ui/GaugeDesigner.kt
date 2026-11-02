package kg.forester.sg.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.ImagePlus
import com.composables.icons.lucide.Lucide
import kg.forester.sg.data.CustomGauge
import kg.forester.sg.data.CustomGauges

/** The design a [CustomGauge] stands for: its base design with the user's changes on top. */
fun CustomGauge.toStyle(): DialStyle {
    var s = dialStyle(base).copy(id = styleId, name = name)
    fun c(v: Long) = Color(v.toInt())
    face?.let { v ->
        val f = c(v)
        s = s.copy(face = listOf(f, lerpTo(f, Color.Black, 0.25f), lerpTo(f, Color.Black, 0.5f)), minimal = false)
    }
    scale?.let { v ->
        val k = c(v)
        s = s.copy(tickMajor = k, tickMinor = k.copy(alpha = 0.55f), numbers = k, value = k, unit = k.copy(alpha = 0.75f), tickLow = null, bandText = k)
    }
    accent?.let { v ->
        val a = c(v)
        s = s.copy(arc = listOf(a, lerpTo(a, Color.White, 0.35f), a), track = s.track.takeIf { it.alpha > 0f } ?: a.copy(alpha = 0.18f))
        s.readout?.let { r -> s = s.copy(readout = r.copy(fg = a, ghost = a.copy(alpha = 0.14f))) }
    }
    glow?.let { g -> s = s.copy(glow = if (g) (accent?.let(::c) ?: s.arc?.firstOrNull() ?: s.numbers) else null) }
    needle?.let { s = s.copy(needle = c(it)) }
    rim?.let { s = s.copy(rim = c(it)) }
    ticks?.let { t -> runCatching { TickShape.valueOf(t) }.getOrNull()?.let { s = s.copy(ticks = it) } }
    pattern?.let { p ->
        runCatching { FacePattern.valueOf(p) }.getOrNull()?.let { s = s.copy(pattern = it) }
        s = s.copy(patternColor = patternColor?.let(::c) ?: defaultPatternColor(s.pattern))
    }
    when (readout) {
        "none" -> s = s.copy(readout = null, numberOnly = true)
        "lcd" -> {
            val a = accent?.let(::c) ?: Color(0xFF7DD3FC)
            s = s.copy(readout = Readout(Color(0xFF0A1016), a, a.copy(alpha = 0.14f), a.copy(alpha = 0.5f)))
        }
        "frame" -> s = s.copy(readout = Readout(Color(0x33000000), s.value, Color.Transparent, s.numbers.copy(alpha = 0.6f), segments = false))
    }
    image?.let { s = s.copy(image = it, face = listOf(Color(0x80000000), Color(0x26000000), Color(0x99000000)), minimal = false) }
    numberPct?.let { s = s.copy(numberScale = it / 100f) }
    return s
}

private fun lerpTo(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

private fun defaultPatternColor(p: FacePattern) = when (p) {
    FacePattern.SAKURA -> Color(0xFFF9A8D4)
    FacePattern.CAT -> Color(0xFFB8BBC2)
    FacePattern.CHECKER -> Color(0xCC34D399)
    FacePattern.SYNTH -> Color(0x99FF4FD8)
    FacePattern.STARS, FacePattern.SPARKLES -> Color(0xCCFFFFFF)
    FacePattern.CIRCUIT -> Color(0x5534D399)
    else -> Color(0x30FFFFFF)
}

/** Colours offered in the designer. */
private val Swatches = listOf(
    0xFFFFFFFF, 0xFFCBD5E1, 0xFF64748B, 0xFF1E293B, 0xFF000000,
    0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFFDE047, 0xFFA3E635,
    0xFF22C55E, 0xFF14B8A6, 0xFF22D3EE, 0xFF38BDF8, 0xFF3B82F6,
    0xFF6366F1, 0xFF8B5CF6, 0xFFD946EF, 0xFFFF4FD8, 0xFFF9A8D4,
)

private val PatternNames = listOf(
    "NONE" to "Нет", "CARBON" to "Карбон", "FLAMES" to "Пламя", "CIRCUIT" to "Плата", "SPARKLES" to "Искры",
    "SAKURA" to "Сакура", "CHECKER" to "Сетка EL", "SYNTH" to "Synthwave", "STARS" to "Звёзды", "CAT" to "Котик",
)

/**
 * Editor of an own gauge design: live preview on the left, settings on the right. [onSaved] gets the saved
 * design (already stored); [onDelete] is offered for a design that exists already.
 */
@Composable
fun GaugeDesigner(start: CustomGauge, onSaved: (CustomGauge) -> Unit, onClose: () -> Unit) {
    var g by remember { mutableStateOf(start) }
    val exists = remember { CustomGauges.list.any { it.id == start.id } }
    val pickPicture = LocalPickGaugeImage.current
    // a picture picked for this design arrives through the stored copy (see GaugeImages.onPicked)
    val stored = CustomGauges.list.firstOrNull { it.id == g.id }
    LaunchedEffect(stored?.image) { stored?.image?.let { if (it != g.image) g = g.copy(image = it) } }

    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).clip(RoundedCornerShape(16.dp)).background(C.Sheet)
                .border(1.dp, C.Purple, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // preview: the needle sweeps from idle to the red line, so every part of the scale is visible and the number is a real one
            Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                T("Свой дизайн", 18.sp, weight = FontWeight.SemiBold)
                val sweep by rememberInfiniteTransition(label = "demo").animateFloat(
                    800f, 6800f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "rpm",
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val d = minOf(maxWidth, maxHeight)
                        androidx.compose.runtime.CompositionLocalProvider(LocalDialPreview provides true) {
                            Dial(sweep, 7000f, 1000f, 1000f, "об/мин", "x1000", 6000f, Modifier.size(d), g.toStyle())
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (exists) Chip("Удалить", false, { CustomGauges.delete(g.id); onClose() })
                    Chip("Отмена", false, onClose)
                    Chip("Сохранить", true, { CustomGauges.save(g); onSaved(g) })
                }
            }
            Column(Modifier.weight(0.58f).fillMaxHeight().cueScroll()) {
                Label("Основа")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialStyles.forEach { st ->
                        val on = st.id == g.base
                        Column(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .border(if (on) 2.dp else 1.dp, if (on) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(10.dp))
                                .clickable { g = g.copy(base = st.id) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalDialPreview provides true) {
                                Dial(5200f, 7000f, 1000f, 1000f, "", null, 6000f, Modifier.size(64.dp), st)
                            }
                            T(st.name, 10.sp, if (on) C.Text else C.Sub, maxLines = 1, modifier = Modifier.width(64.dp))
                        }
                    }
                }
                Label("Фон")
                Swatches(g.face) { g = g.copy(face = it, image = null) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("Своя картинка…", g.image != null, {
                        // the picture goes to the stored copy, so store the draft first
                        CustomGauges.save(g)
                        pickPicture(g.styleId)
                    }, Lucide.ImagePlus)
                    if (g.image != null) {
                        Spacer(Modifier.width(6.dp))
                        Chip("Убрать картинку", false, { g = g.copy(image = null) })
                    }
                }
                Label("Шкала и цифры")
                Swatches(g.scale) { g = g.copy(scale = it) }
                Label("Подсветка шкалы")
                Swatches(g.accent) { g = g.copy(accent = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Свечение", 13.sp, C.Sub)
                    Spacer(Modifier.width(6.dp))
                    Switch(g.glow ?: (g.toStyle().glow != null), { g = g.copy(glow = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
                }
                Label("Стрелка")
                Swatches(g.needle) { g = g.copy(needle = it) }
                Label("Обод")
                Swatches(g.rim) { g = g.copy(rim = it) }
                Label("Деления")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Как в основе", "LINE" to "Линии", "DOT" to "Точки", "TRIANGLE" to "Треугольники").forEach { (v, l) ->
                        Chip(l, g.ticks == v, { g = g.copy(ticks = v) })
                    }
                }
                Label("Узор на циферблате")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Как в основе", g.pattern == null, { g = g.copy(pattern = null, patternColor = null) })
                    PatternNames.forEach { (v, l) -> Chip(l, g.pattern == v, { g = g.copy(pattern = v) }) }
                }
                if (g.pattern != null && g.pattern != "NONE") {
                    Label("Цвет узора")
                    Swatches(g.patternColor) { g = g.copy(patternColor = it) }
                }
                Label("Цифры в центре")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Как в основе", "none" to "Число", "lcd" to "LCD-табло", "frame" to "В рамке").forEach { (v, l) ->
                        Chip(l, g.readout == v, { g = g.copy(readout = v) })
                    }
                }
                Label("Размер цифр")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Как в основе", 80 to "80%", 100 to "100%", 120 to "120%", 140 to "140%").forEach { (v, l) ->
                        Chip(l, g.numberPct == v, { g = g.copy(numberPct = v) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(12.dp))
    T(text, 13.sp, C.Violet, FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

/** Colour swatches with "as in the base" (null) first. */
@Composable
private fun Swatches(current: Long?, onPick: (Long?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(C.CardHi)
                .border(if (current == null) 2.dp else 1.dp, if (current == null) C.Purple else C.Border, CircleShape)
                .clickable { onPick(null) },
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Ban, "Как в основе", tint = C.Sub, modifier = Modifier.size(16.dp)) }
        Swatches.forEach { v ->
            val on = current == v
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(v.toInt()))
                    .border(if (on) 3.dp else 1.dp, if (on) C.activeEdge(C.Purple) else C.Border, CircleShape)
                    .clickable { onPick(v) },
            )
        }
    }
}

/** ARGB of a Compose colour as stored in a [CustomGauge]. */
fun Color.toArgbLong() = toArgb().toLong() and 0xFFFFFFFFL
