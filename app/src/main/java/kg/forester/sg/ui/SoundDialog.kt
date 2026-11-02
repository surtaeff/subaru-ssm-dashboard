package kg.forester.sg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import kg.forester.sg.service.AudioFx

/**
 * «Звук» of the own player: presets, the equalizer as tall sliders (drag or tap) with the curve drawn over them,
 * bass, surround, extra loudness, the volume that rises with speed, the soft start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundDialog(onClose: () -> Unit) {
    val cfg by AudioFx.cfg.collectAsState()
    val hz by AudioFx.bandsHz.collectAsState()
    CarDialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(900.dp).heightIn(max = minOf(600f, canvasScreen().screenHeightDp * 0.94f).dp).clip(RoundedCornerShape(20.dp))
                .background(C.Sheet).background(Brush.verticalGradient(listOf(C.Violet.copy(alpha = 0.14f), C.Sheet, C.Sheet)))
                .border(1.dp, C.Border, RoundedCornerShape(20.dp)).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Звук", 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("Сбросить", Lucide.RotateCcw, height = 38.dp) { AudioFx.reset() }
                Spacer(Modifier.width(8.dp))
                Pill("Готово", Lucide.Check, true, 38.dp, onClose)
            }
            Spacer(Modifier.height(12.dp))
            // «Готово» stays in sight, the rest scrolls
            Column(Modifier.weight(1f, fill = false).cueScroll(null)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (AudioFx.PRESETS.keys + if (cfg.preset == "Свой") listOf("Свой") else emptyList()).forEach { name ->
                    Pill(name, null, cfg.preset == name, 40.dp) { if (name != "Свой") AudioFx.setPreset(name) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // equalizer
                Box(
                    Modifier.weight(1.4f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(C.CardHi.copy(alpha = 0.7f)).padding(12.dp),
                ) {
                    if (hz.isEmpty()) SettingHint("Эквалайзер появится, когда плеер начнёт играть (на этой магнитоле его может не быть).")
                    else Bands(hz, cfg.bands)
                }
                // effects
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Knob("Бас", "глубже низы", cfg.bass, AudioFx.hasBass) { v -> AudioFx.update { it.copy(bass = v) } }
                    Knob("Объём", "шире сцена", cfg.surround, AudioFx.hasSurround) { v -> AudioFx.update { it.copy(surround = v) } }
                    Knob("Громкость+", "+%.0f дБ".format(cfg.loud), cfg.loud / 10f, AudioFx.hasLoud) { v -> AudioFx.update { it.copy(loud = v * 10f) } }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("Громкость по скорости",
                    kg.forester.sg.data.DistanceUnits.prose("Громче на трассе, тише в пробке: до +6 дБ к 130 км/ч") + if (AudioFx.speedBoostDb > 0) " · сейчас +%.1f".format(AudioFx.speedBoostDb) else "",
                    cfg.speedVolume) { on -> AudioFx.update { it.copy(speedVolume = on) } }
                Toggle("Плавное начало", "Музыка нарастает за секунду, без удара по ушам", cfg.fadeIn) { on -> AudioFx.update { it.copy(fadeIn = on) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Эффект:", 14.sp, C.Sub)
                Pill("Кольцо вокруг обложки", Lucide.CircleDashed, cfg.ring, 38.dp) { AudioFx.update { it.copy(ring = !cfg.ring) } }
                Spacer(Modifier.width(8.dp))
                T("Полоса под обложкой:", 14.sp, C.Sub)
                listOf("off" to "Нет", "bars" to "Столбики", "wave" to "Волна").forEach { (id, name) ->
                    Pill(name, null, cfg.vis == id, 38.dp) { AudioFx.update { it.copy(vis = id) } }
                }
            }
            }
        }
    }
}

/** The bands as tall bars, the gain curve through their tops; drag a bar or tap where it should be. */
@Composable
private fun Bands(hz: List<Float>, bands: List<Float>) {
    val (lo, hi) = AudioFx.rangeDb
    val violet = C.Violet
    val second = C.theme.second
    val border = C.Border
    Column {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // the scale
            Column(Modifier.fillMaxHeight().width(34.dp), verticalArrangement = Arrangement.SpaceBetween) {
                T("+%.0f".format(hi), 11.sp, C.Sub); T("0", 11.sp, C.Sub); T("%.0f".format(lo), 11.sp, C.Sub)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // the curve behind the bars
                Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                    if (bands.isEmpty()) return@Canvas
                    val n = bands.size
                    val pts = bands.mapIndexed { i, db ->
                        Offset(size.width * (i + 0.5f) / n, size.height * (1 - (db - lo) / (hi - lo)))
                    }
                    drawLine(border, Offset(0f, size.height * (1 - (0 - lo) / (hi - lo))), Offset(size.width, size.height * (1 - (0 - lo) / (hi - lo))), 2f)
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) {
                            val a = pts[i - 1]; val b = pts[i]; val mx = (a.x + b.x) / 2
                            cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                        }
                    }
                    val fill = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(violet.copy(alpha = 0.28f), Color.Transparent)))
                    drawPath(path, Brush.horizontalGradient(listOf(second, violet)), style = Stroke(5f))
                }
                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                    bands.forEachIndexed { i, db ->
                        Canvas(
                            Modifier.weight(1f).fillMaxHeight()
                                .pointerInput(i, lo, hi) {
                                    fun at(y: Float) = (hi - (y / size.height) * (hi - lo))
                                    detectTapGestures { o -> AudioFx.setBand(i, (Math.round(at(o.y) * 2) / 2f)) }
                                }
                                .pointerInput("d$i", lo, hi) {
                                    detectVerticalDragGestures { change, _ ->
                                        AudioFx.setBand(i, Math.round((hi - (change.position.y / size.height) * (hi - lo)) * 2) / 2f)
                                    }
                                },
                        ) {
                            val w = 10.dp.toPx()
                            val x = size.width / 2 - w / 2
                            drawRoundRect(border.copy(alpha = 0.7f), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
                            val zero = size.height * (1 - (0 - lo) / (hi - lo))
                            val y = size.height * (1 - (db - lo) / (hi - lo))
                            drawRoundRect(Brush.verticalGradient(listOf(violet, second)), Offset(x, minOf(y, zero)), Size(w, kotlin.math.abs(zero - y).coerceAtLeast(2f)), CornerRadius(w / 2))
                            drawCircle(C.Ink, 11.dp.toPx(), Offset(size.width / 2, y))
                            drawCircle(violet, 6.dp.toPx(), Offset(size.width / 2, y))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
            hz.forEachIndexed { i, f ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("%+.1f".format(bands.getOrElse(i) { 0f }), 11.sp, C.Text, FontWeight.SemiBold)
                    T(if (f >= 1000) "%.0fк".format(f / 1000) else "%.0f".format(f), 11.sp, C.Sub, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun Knob(title: String, sub: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.CardHi.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the name whole, whatever is beside it takes what's left: squeezed, the name went a letter a line
            T(title, 15.sp, if (enabled) C.Text else C.Sub, FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            FitText(if (enabled) sub else "нет на этой магнитоле", LocalCar.current.secondary, Modifier.weight(1f), C.Sub, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Slider(
            value = value.coerceIn(0f, 1f), onValueChange = onChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = SliderDefaults.colors(thumbColor = C.Ink, activeTrackColor = C.Violet, inactiveTrackColor = C.Border),
        )
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(420.dp).clip(RoundedCornerShape(14.dp)).background(C.CardHi.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 4.dp)) {
        SwitchRow(title, sub, on, onChange = onChange)
    }
}
