package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import kg.forester.sg.data.Repo

/** Palette icon for the top bar: opens the [ThemeDialog]. */
@Composable
fun ThemeButton(size: Dp) {
    var open by remember { mutableStateOf(false) }
    IconTap(Lucide.Palette, "Тема и цвет", { open = true }, tint = C.Violet, iconSize = size)
    if (open) ThemeDialog { open = false }
}

@Composable
fun ThemeDialog(onClose: () -> Unit) {
    CarSheet(onClose, 620.dp, 600.dp, head = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Тема и цвет", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(10.dp))
    }) {
        UiThemeChooser()
        Spacer(Modifier.height(14.dp))
        AccentSection()
    }
}

/** The looks, each a small picture of itself, in rows as wide as they fit. Applied at once, nothing restarts. */
@Composable
fun UiThemeChooser() {
    val current = C.palette
    androidx.compose.foundation.layout.BoxWithConstraints {
        // a name on one line under a picture large enough to tell the themes apart
        val perRow = (maxWidth / (LocalCar.current.secondary.asDp() * 6f + 16.dp)).toInt().coerceIn(2, Palette.all.size)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette.all.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        val shape = RoundedCornerShape(12.dp)
                        val on = p.id == current.id
                        Column(
                            Modifier.weight(1f).clip(shape).background(if (on) C.Purple.copy(alpha = 0.22f) else C.DialogHi)
                                .border(if (on) 2.dp else 1.dp, if (on) C.activeEdge(C.Purple) else C.Border, shape)
                                .clickable { Repo.settings.uiTheme = p.id }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ThemeThumbnail(p, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.height(6.dp))
                            FitText(p.title, 14.sp, Modifier.fillMaxWidth(), if (on) C.Text else C.Sub, FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    // a short last row keeps the size of the others
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A tiny screen in [p]'s colours: its background, a rail, two cards with a number and a bar. */
@Composable
private fun ThemeThumbnail(p: Palette, modifier: Modifier) {
    // read for the update when the hue changes; the picture shows the hue as this theme would show it
    val accent = C.theme.let { p.accentFor(C.chosen) }
    Canvas(modifier.themeBackdrop(p, flat = false)) {
        val w = size.width
        val h = size.height
        val r = androidx.compose.ui.geometry.CornerRadius(minOf(w, h) * (p.corner.value / 110f))
        fun card(x: Float, y: Float, cw: Float, ch: Float) {
            val g = p.cardGradient
            if (p.race) {
                val cut = ch * 0.16f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, y); lineTo(x + cw - cut, y); lineTo(x + cw, y + cut); lineTo(x + cw, y + ch); lineTo(x + cut, y + ch); lineTo(x, y + ch - cut); close()
                }
                drawPath(path, p.card)
            } else if (g != null) drawRoundRect(Brush.linearGradient(g, Offset(x, y), Offset(x + cw, y + ch)), Offset(x, y), Size(cw, ch), r)
            else drawRoundRect(p.card, Offset(x, y), Size(cw, ch), r)
            if (p.panelMark == Palette.PanelMark.STRIPE) drawRect(Color(0xFFC8141E), Offset(x, y), Size(w * 0.012f, ch))
            if (p.panelMark == Palette.PanelMark.CORNERS) {
                val a = ch * 0.1f
                for (cx in listOf(x + 2f, x + cw - 2f)) for (cy in listOf(y + 2f, y + ch - 2f)) {
                    drawLine(Color.White, Offset(cx, cy), Offset(cx + if (cx > x + 2f) -a else a, cy), 1f)
                    drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + if (cy > y + 2f) -a else a), 1f)
                }
            }
            if (!p.race) drawRoundRect(p.border, Offset(x, y), Size(cw, ch), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            // the number
            drawRoundRect(p.ink, Offset(x + cw * 0.14f, y + ch * 0.22f), Size(cw * 0.5f, ch * 0.22f), androidx.compose.ui.geometry.CornerRadius(ch * 0.04f))
            // the label
            drawRoundRect(p.sub, Offset(x + cw * 0.14f, y + ch * 0.55f), Size(cw * 0.34f, ch * 0.07f), androidx.compose.ui.geometry.CornerRadius(ch * 0.03f))
            // the bar
            drawRoundRect(accent.main, Offset(x + cw * 0.14f, y + ch * 0.75f), Size(cw * 0.6f, ch * 0.07f), androidx.compose.ui.geometry.CornerRadius(ch * 0.03f))
        }
        p.navBar?.let { drawRect(it, Offset.Zero, Size(w * 0.17f, h)) }
        // the active item of the rail
        drawRoundRect(p.activeFill.takeIf { p.solidSmall } ?: accent.deep.copy(alpha = 0.5f), Offset(w * 0.025f, h * 0.08f), Size(w * 0.13f, h * 0.2f), androidx.compose.ui.geometry.CornerRadius(h * 0.04f))
        for (i in 1..3) drawCircle((p.navText ?: p.sub).copy(alpha = 0.7f), h * 0.03f, Offset(w * 0.09f, h * (0.18f + 0.24f * i)))
        card(w * 0.19f, h * 0.08f, w * 0.37f, h * 0.84f)
        card(w * 0.6f, h * 0.08f, w * 0.37f, h * 0.84f)
    }
}

/** The hue, where this theme has one. */
@Composable
fun AccentSection() {
    if (C.palette.hueFollows) {
        T("Цвет интерфейса", 15.sp)
        SettingHint("Под подсветку приборов вашей машины, или любой свой")
        Spacer(Modifier.height(6.dp))
        ThemeChooser()
    } else SettingHint("В этой теме свой цвет. Выбранный оттенок вернётся в темах Неон и Светлая")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeChooser() {
    val s = Repo.settings
    val id = C.themeId
    var picker by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { C.applyTheme(s.accentTheme) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AccentTheme.entries.forEach { t ->
            Swatch(Brush.horizontalGradient(listOf(t.second, t.main)), t.title, id == t.id && !picker) {
                picker = false
                s.accentTheme = t.id
            }
        }
        Swatch(
            Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
            "Свой", picker || id.startsWith("custom:"),
        ) {
            if (picker) C.applyTheme(s.accentTheme) // closing the picker drops the preview
            picker = !picker
        }
    }
    if (picker) {
        Spacer(Modifier.height(8.dp))
        val start = id.takeIf { it.startsWith("custom:") }?.removePrefix("custom:")?.toIntOrNull(16)
            ?: (C.theme.main.toArgb() and 0xFFFFFF)
        ColourPicker(start, onChange = { C.theme = UiTheme.of(it) }) { rgb ->
            s.accentTheme = "custom:%06X".format(rgb and 0xFFFFFF)
            picker = false
        }
    }
}

@Composable
private fun Swatch(brush: Brush, title: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).background(if (selected) C.selectFill(C.Purple.copy(alpha = 0.28f)) else C.idleFill(C.CardHi))
            .border(1.dp, if (selected) C.activeEdge(C.Purple) else C.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(brush))
        Spacer(Modifier.width(8.dp))
        T(title, 14.sp, if (selected) C.selectText(C.Text) else C.Text)
    }
}
