package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.Screen
import kg.forester.sg.data.NavMenu
import kg.forester.sg.data.Repo

/**
 * The driver's menu: order by big arrows or by the handle (a drag is poor in a moving car and on resistive screens),
 * a switch to hide. Applied as it goes; «Сбросить» brings the factory's back. Lists only what this car has.
 */
@Composable
fun MenuEditorDialog(onClose: () -> Unit) {
    val menu by Repo.navMenu.collectAsState()
    val factory = remember { Screen.entries.map { it.name } }
    val there = kg.forester.sg.availableScreens()
    val rows = NavMenu.merge(menu.order, factory).mapNotNull { n -> there.firstOrNull { it.name == n } }
    val shownIds = rows.map { it.name }
    fun store(m: NavMenu) { Repo.settings.navMenu = m }
    CarSheet(onClose, 720.dp, 600.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Меню", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Сбросить", false, { store(NavMenu.reset()) })
            Spacer(Modifier.width(8.dp))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.padding(top = 4.dp))
        SettingHint("Порядок и видимость экранов. Скрытый открывается голосом и по ссылкам. На боковой панели «Настройки» всегда внизу")
        Spacer(Modifier.padding(top = 4.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEachIndexed { i, sc ->
                key(sc.name) {
                    MenuRow(
                        sc, i, rows.size, sc.name !in menu.hiddenIds || sc.name == NavMenu.PINNED,
                        onMove = { dir -> store(Repo.navMenu.value.moved(sc.name, dir, factory, shownIds)) },
                        onShow = { store(menu.hidden(sc.name, !it, factory)) },
                    )
                }
            }
        }
    }
}


@Composable
private fun MenuRow(sc: Screen, index: Int, count: Int, shown: Boolean, onMove: (Int) -> Unit, onShow: (Boolean) -> Unit) {
    val pinned = sc.name == NavMenu.PINNED
    var rowH by remember { mutableIntStateOf(1) }
    val move by rememberUpdatedState(onMove)
    val canUp by rememberUpdatedState(index > 0)
    val canDown by rememberUpdatedState(index < count - 1)
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalMinTap.current).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
            .onSizeChanged { rowH = it.height.coerceAtLeast(1) }.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the handle takes the drag from the list's own scroll: a step at each row's height passed
        Box(
            Modifier.size(LocalMinTap.current).pointerInput(sc.name) {
                var sum = 0f
                detectVerticalDragGestures(onDragStart = { sum = 0f }) { change, dy ->
                    change.consume()
                    sum += dy
                    val step = rowH + 4.dp.toPx()
                    while (sum > step * 0.6f && canDown) { move(1); sum -= step }
                    while (sum < -step * 0.6f && canUp) { move(-1); sum += step }
                }
            },
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.GripVertical, "Перетащить", tint = C.Sub, modifier = Modifier.size(24.dp)) }
        Icon(sc.icon, null, tint = if (shown) C.Violet else C.Gray, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(sc.title, LocalCar.current.secondary, if (shown) C.Text else C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
        IconTap(Lucide.ChevronUp, "Выше", { onMove(-1) }, tint = C.Text, enabled = index > 0, side = LocalMinTap.current)
        IconTap(Lucide.ChevronDown, "Ниже", { onMove(1) }, tint = C.Text, enabled = index < count - 1, side = LocalMinTap.current)
        Spacer(Modifier.width(4.dp))
        // the way back to all this can't be hidden
        androidx.compose.material3.Switch(
            shown, if (pinned) null else onShow, enabled = !pinned,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Purple),
        )
    }
}
