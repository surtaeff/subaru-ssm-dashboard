package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Plus
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.RrParam

private val filters = listOf("all" to "Все", "ecu" to "Двигатель", "tcu" to "АКПП", "sw" to "Переключатели", "ext" to "Расширенные", "own" to "Свои")

/**
 * Every parameter of the RomRaider logger definitions this car's units have, like a laptop logger:
 * tick what to watch on the left, see current / min / max on the right (also written to the CSV log).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorsScreen() {
    val s = Repo.settings
    val avail by Repo.rrAvailable.collectAsState()
    val values by Repo.rrValues.collectAsState()
    val polled by Repo.rrPolled.collectAsState()
    val init by Repo.ecuInit.collectAsState()
    val obd by Repo.obdMode.collectAsState()
    Repo.watchVersion.collectAsState().value
    val watch = s.watchIds
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val mins = remember { mutableStateMapOf<String, Double>() }
    val maxs = remember { mutableStateMapOf<String, Double>() }
    LaunchedEffect(values) {
        values.forEach { (id, v) ->
            if (!v.isNaN()) {
                mins[id] = minOf(mins[id] ?: v, v)
                maxs[id] = maxOf(maxs[id] ?: v, v)
            }
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            var own by remember { mutableStateOf(false) }
            val q = query.trim().lowercase()
            val shown = avail.filter { p ->
                when (filter) {
                    "ecu" -> p.target != 2 && p.kind != RrParam.Kind.SWITCH
                    "tcu" -> p.target == 2
                    "sw" -> p.kind == RrParam.Kind.SWITCH
                    "ext" -> p.kind == RrParam.Kind.EXT
                    "own" -> p.desc == "свой адрес"
                    else -> true
                } && (q.isEmpty() || p.name.lowercase().contains(q) || p.nameEn.lowercase().contains(q) || p.id.lowercase() == q)
            }
            ListWithHead({
            PanelTitle(Lucide.ListChecks, "Все параметры ЭБУ") {
                Chip("Свой адрес", false, { own = true }, Lucide.Plus)
                Spacer(Modifier.width(6.dp))
            }
            if (own) CustomParamsDialog { own = false }
            Spacer(Modifier.height(4.dp))
            T(
                when {
                    obd -> "Машина отвечает только по стандартному OBD-II: параметров Subaru нет, а стандартных у неё ${avail.count { it.id.startsWith("OBD_") }}. " +
                        "Отметьте, что смотреть (каждый отмеченный замедляет опрос, особенно на K-Line)."
                    kg.forester.sg.data.CanEcu.active -> "Спортивный ЭБУ по CAN: ${avail.size} значений из его потока и своих значений из CAN " +
                        "(Настройки, Машина и адаптер). Отметьте, что смотреть."
                    init == null -> "Список зависит от ЭБУ и его прошивки: подключитесь к машине (в демо-режиме его нет)."
                    else -> "ROM ${init?.romId}: доступно ${avail.size} (база RomRaider). Отметьте, что смотреть."
                },
                12.sp, C.Sub,
            )
            if (obd) ObdbPanel()
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.forEach { (k, l) -> Chip(l, filter == k, { filter = k }) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { FieldLabel("Поиск") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            }) {
                items(shown, key = { it.id + it.target }) { p ->
                    val on = p.id in watch
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { s.watchIds = if (on) watch - p.id else watch + p.id }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (on) C.Purple else C.CardHi),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Lucide.Check, null, tint = C.Text, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Icon(Metrics.iconOf(p), null, tint = if (on) C.Violet else C.Sub, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            T(p.name, 14.sp, maxLines = 2)
                            T(listOfNotNull(
                                p.id, p.nameEn.takeIf { it != p.name && !kg.forester.sg.data.I18n.english }, p.unitsRu.ifEmpty { null },
                                if (p.target == 2) "АКПП" else null,
                            ).joinToString(" · "), 11.sp, C.Sub, maxLines = 1)
                        }
                    }
                }
            }
        }
        Panel(Modifier.weight(1.25f).fillMaxHeight()) {
            val stacked = LocalCar.current.small
            val ticked = avail.filter { it.id in watch }
            ListWithHead({
            PanelTitle(Lucide.Activity, "Выбранные параметры") {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi)
                        .clickable { mins.clear(); maxs.clear() }.padding(horizontal = 10.dp, vertical = 6.dp),
                ) { T("Сбросить мин/макс", 12.sp, C.Sub) }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (stacked) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                FitText("Мин", LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
                FitText("Сейчас", LocalCar.current.secondary, Modifier.weight(1.4f), C.Sub)
                FitText("Макс", LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
            } else Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                FitText("Параметр", LocalCar.current.secondary, Modifier.weight(2.4f), C.Sub)
                FitText("Мин", LocalCar.current.secondary, Modifier.weight(0.8f), C.Sub)
                FitText("Сейчас", LocalCar.current.secondary, Modifier.weight(0.9f), C.Sub)
                FitText("Макс", LocalCar.current.secondary, Modifier.weight(0.8f), C.Sub)
                FitText("Ед.", LocalCar.current.secondary, Modifier.weight(0.8f), C.Sub)
            }
            if (ticked.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                T("Отметьте параметры слева. Они опрашиваются отдельным запросом и попадают в CSV-лог. " +
                    "На K-Line каждый параметр добавляет к циклу опроса примерно 15 мс, так что выбирайте нужное.", 13.sp, C.Sub)
            }
            }) {
                items(ticked, key = { "w" + it.id + it.target }) { p ->
                    val reading = polled.any { it.id == p.id }
                    if (stacked) {
                        // the numbers under the name: at this size of text, in a row with it, they went to «…»
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            T(p.name, 14.sp, maxLines = 2)
                            if (!reading && init != null) T("ЭБУ не отдаёт этот параметр", 11.sp, C.Orange)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                FitText(p.shownText(mins[p.id]), LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
                                FitText(p.shownText(values[p.id]) + " " + p.shownUnit, LocalCar.current.secondary, Modifier.weight(1.4f), C.Text, FontWeight.SemiBold)
                                FitText(p.shownText(maxs[p.id]), LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
                            }
                        }
                    } else Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(2.4f)) {
                            T(p.name, 14.sp, maxLines = 2)
                            if (!reading && init != null) T("ЭБУ не отдаёт этот параметр", 11.sp, C.Orange)
                        }
                        FitText(p.shownText(mins[p.id]), LocalCar.current.secondary, Modifier.weight(0.8f), C.Sub)
                        FitText(p.shownText(values[p.id]), LocalCar.current.secondary, Modifier.weight(0.9f), C.Text, FontWeight.SemiBold)
                        FitText(p.shownText(maxs[p.id]), LocalCar.current.secondary, Modifier.weight(0.8f), C.Sub)
                        FitText(p.shownUnit, LocalCar.current.secondary, Modifier.weight(0.8f), C.Sub)
                    }
                }
            }
            T("Определения параметров: RomRaider (romraider.com), версия 370.", 10.sp, C.Sub)
        }
    }
}
