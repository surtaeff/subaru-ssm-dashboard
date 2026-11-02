package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kg.forester.sg.data.Repo

/**
 * Settings of one Спорт-дисплей card, with the card itself as a live preview (a tap on it turns the page there
 * too): backlight colour, the tachometer scale and shift lights, which pages it shows and the values on them.
 * Every change is saved at once through [onChange] (the card's style).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportEditor(style: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val redline = Repo.engineProfile.collectAsState().value.redlineRpm
    val cfg = remember(style, redline) { SportCfg.parse(style, redline) }
    val avail by Repo.rrAvailable.collectAsState()
    // which value slot is being picked: page 0 (around the tachometer) or 1 (the table), and its index
    var picking by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun set(c: SportCfg) = onChange(c.format(redline))

    picking?.let { (pg, i) ->
        val current = if (pg == 0) cfg.arc[i] else cfg.table[i]
        ValuePicker("Что показывать: ${if (pg == 0) "у тахометра" else "в таблице"}, место ${i + 1}", current, onPick = { id ->
            set(if (pg == 0) cfg.copy(arc = cfg.arc.toMutableList().also { it[i] = id }) else cfg.copy(table = cfg.table.toMutableList().also { it[i] = id }))
            picking = null
        }, onClose = { picking = null })
    }

    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = canvasScreen()
        BoxWithConstraints(
            Modifier.widthIn(max = (screen.screenWidthDp * 0.96f).dp).heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .clip(kg.forester.sg.ui.RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, kg.forester.sg.ui.RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            val wide = maxWidth > 760.dp
            val preview = @Composable { m: Modifier ->
                Column(m) {
                    SportDisplayCard("preview.defi", Modifier.fillMaxWidth().aspectRatio(1.7f), cfg.format(redline))
                    Spacer(Modifier.height(6.dp))
                    T("Нажатие на дисплей листает страницы, как в машине", 12.sp, C.Sub)
                }
            }
            val controls = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Section("Подсветка")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SportThemes.forEach { t -> Swatch(t.lit, t.name, cfg.theme == t.id) { set(cfg.copy(theme = t.id)) } }
                    }
                    Section("Тахометр: шкала до, об/мин")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SportCfg.TOPS.forEach { top ->
                            Chip("${top / 1000} 000", cfg.top == top, {
                                // keep the red zone where it was when it still fits under the new top
                                set(cfg.copy(top = top, red = if (cfg.red < top) cfg.red else top - 1500))
                            })
                        }
                    }
                    Section("Красная зона и мигание ламп с, об/мин")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (cfg.top - 3000..cfg.top - 500 step 500).forEach { r ->
                            Chip("%.1f".format(r / 1000f).replace('.', ',') + " тыс.", cfg.red == r, { set(cfg.copy(red = r)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("Лампы переключения:", 13.sp, C.Sub)
                        Chip("Показывать", cfg.leds, { set(cfg.copy(leds = true)) })
                        Chip("Скрыть", !cfg.leds, { set(cfg.copy(leds = false)) })
                    }
                    Section("Страницы")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Тахометр", 1 to "Таблица", 2 to "Перегрузки и трек").forEach { (pg, name) ->
                            val on = pg in cfg.pages
                            // at least one page stays
                            Chip(name, on, { if (!on) set(cfg.copy(pages = (cfg.pages + pg).sorted())) else if (cfg.pages.size > 1) set(cfg.copy(pages = cfg.pages - pg)) })
                        }
                    }
                    Section("Цифры у тахометра")
                    SlotRow(cfg.arc.map { sportLabel(it, avail) }) { picking = 0 to it }
                    Section("Таблица")
                    SlotRow(cfg.table.map { sportLabel(it, avail) }) { picking = 1 to it }
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Спорт-дисплей", 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Chip("Как было", false, { set(SportCfg.factory(redline)) })
                    Spacer(Modifier.width(8.dp))
                    Chip("Готово", true, onClose)
                }
                Spacer(Modifier.height(10.dp))
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    preview(Modifier.weight(1f))
                    controls(Modifier.weight(1.1f).cueScroll())
                } else Column(Modifier.cueScroll()) {
                    // a narrow screen: the preview on top, everything scrolls together
                    preview(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) = T(text, 13.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))

/** The value places of a page as numbered chips; a tap picks what that place shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotRow(labels: List<String>, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l -> Chip("${i + 1}. $l", false, { onPick(i) }) }
    }
}

/** A colour choice: a lit dot in that colour and its name. */
@Composable
fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).background(if (selected) C.selectFill(C.Purple.copy(alpha = 0.28f)) else C.idleFill(C.CardHi))
            .border(1.dp, if (selected) C.activeEdge(C.Purple) else C.Border, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        T(name, 15.sp, if (selected) C.selectText(C.Text) else C.Sub)
    }
}

/** Pick any value of the app (the catalogue, extra sensors, own CAN channels, parameters ticked on «Датчики»). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValuePicker(title: String, current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val avail by Repo.rrAvailable.collectAsState()
    CarDialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = canvasScreen()
        Column(
            Modifier.widthIn(max = minOf(760f, screen.screenWidthDp * 0.94f).dp).heightIn(max = (screen.screenHeightDp * 0.9f).dp)
                .clip(kg.forester.sg.ui.RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Border, kg.forester.sg.ui.RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Отмена", false, onClose)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.cueScroll()) {
                Metrics.all(avail, Repo.settings.watchIds).groupBy { it.group }.forEach { (group, list) ->
                    T(group, 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { ind -> Chip(ind.title, ind.id == current, { onPick(ind.id) }, ind.icon) }
                    }
                }
            }
        }
    }
}
