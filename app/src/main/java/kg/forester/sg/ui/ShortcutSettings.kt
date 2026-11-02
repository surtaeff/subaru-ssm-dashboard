package kg.forester.sg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.composables.icons.lucide.*
import kg.forester.sg.data.InstalledApp
import kg.forester.sg.data.Shortcut
import kg.forester.sg.data.Shortcuts

/** Manage the side-panel quick-launch buttons: add, edit, reorder, delete. */
@Composable
fun ShortcutSettings() {
    val ctx = LocalContext.current
    val items by Shortcuts.list.collectAsState()
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    val apps = remember { Shortcuts.installedApps(ctx) }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(470.dp).fillMaxHeight()) {
            ListWithHead({
                PanelTitle(Lucide.LayoutGrid, "Кнопки быстрого запуска")
                Spacer(Modifier.height(4.dp))
                SettingHint("Появляются в боковой панели и внизу компактного режима. «Рядом» — открыть в половине экрана рядом с приборами.")
                Spacer(Modifier.height(8.dp))
                SplitServiceRow()
                Spacer(Modifier.height(10.dp))
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.id }) { sc ->
                    val i = items.indexOf(sc)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (editing?.id == sc.id) C.Purple.copy(alpha = 0.25f) else C.CardHi)
                            .clickable { editing = sc }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Shortcuts.icon(sc.icon), null, tint = C.Blue, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            T(sc.label, 15.sp, maxLines = 1)
                            T("${Shortcuts.appLabel(ctx, sc.pkg)} · ${if (sc.split) "рядом" else "весь экран"}", 11.sp, C.Sub, maxLines = 1)
                        }
                        IconTap(Lucide.ArrowUp, "Выше", { Shortcuts.save(ctx, items.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0)
                        IconTap(Lucide.ArrowDown, "Ниже", { Shortcuts.save(ctx, items.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < items.size - 1)
                        var ask by remember(sc.id) { mutableStateOf(false) }
                        IconTap(Lucide.Trash2, "Удалить", { ask = true })
                        if (ask) ConfirmDialog(
                            "Убрать кнопку «${sc.label}»?", "Кнопка уйдёт из боковой панели и компактного режима. Добавить её можно заново.",
                            "Убрать", { Shortcuts.save(ctx, items.filter { it.id != sc.id }); if (editing?.id == sc.id) editing = null }, { ask = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ActionButton("+ Добавить кнопку", true) {
                editing = Shortcut(System.currentTimeMillis(), "", "", "app", true)
            }
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            val ed = editing
            if (ed == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    T("Выберите кнопку слева или добавьте новую", 15.sp, C.Sub)
                }
            } else {
                ShortcutEditor(ed, apps, onSave = { s ->
                    val list = items.toMutableList()
                    val idx = list.indexOfFirst { it.id == s.id }
                    if (idx >= 0) list[idx] = s else list += s
                    Shortcuts.save(ctx, list)
                    editing = s
                })
            }
        }
    }
}

@Composable
private fun ShortcutEditor(initial: Shortcut, apps: List<InstalledApp>, onSave: (Shortcut) -> Unit) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var pkg by remember(initial.id) { mutableStateOf(initial.pkg) }
    var icon by remember(initial.id) { mutableStateOf(initial.icon) }
    var split by remember(initial.id) { mutableStateOf(initial.split) }
    var phrase by remember(initial.id) { mutableStateOf(initial.phrase) }
    var query by remember(initial.id) { mutableStateOf("") }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            T("Приложение", 14.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { T("Поиск", 13.sp, C.Sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val filtered = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            val found = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.weight(1f).scrollCue(found), state = found) {
                items(filtered, key = { it.pkg }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (a.pkg == pkg) C.Purple.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { pkg = a.pkg; if (label.isBlank()) label = a.label }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = remember(a.pkg) { runCatching { a.icon?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull() }
                        if (bmp != null) Image(bmp, null, Modifier.size(28.dp)) else Spacer(Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        T(a.label, 14.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            // «Сохранить» stays in sight, what is above it scrolls: on 7" it can't all fit
            Column(Modifier.weight(1f).cueScroll()) {
            OutlinedTextField(
                value = label, onValueChange = { label = it }, singleLine = true,
                label = { FieldLabel("Название кнопки") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = phrase, onValueChange = { phrase = it }, singleLine = true,
                label = { FieldLabel("Голосовая фраза (необязательно)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Открывать рядом с приборами", 13.sp, modifier = Modifier.weight(1f))
                Switch(split, { split = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(6.dp))
            T("Иконка", 13.sp, C.Sub)
            Spacer(Modifier.height(4.dp))
            Shortcuts.icons.keys.toList().chunked(6).forEach { row -> Row {
                row.forEach { k ->
                    Box(
                        Modifier.padding(3.dp).size(42.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (k == icon) C.Purple.copy(alpha = 0.3f) else C.CardHi)
                            .then(if (k == icon) Modifier.border(1.dp, C.Purple, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable { icon = k },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Shortcuts.icon(k), null, tint = if (k == icon) C.Violet else C.Sub, modifier = Modifier.size(20.dp)) }
                }
            } }
            }
            Spacer(Modifier.height(8.dp))
            ActionButton("Сохранить", pkg.isNotBlank() && label.isNotBlank()) {
                onSave(initial.copy(label = label.trim(), pkg = pkg, icon = icon, split = split, phrase = phrase.trim()))
            }
        }
    }
}

/**
 * «Рядом» without root needs our accessibility service (it only turns split screen on and off): its state and a
 * button to the Android settings, re-checked when coming back from there.
 */
@Composable
private fun SplitServiceRow() {
    val ctx = LocalContext.current
    var resumed by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val on = remember(resumed) { kg.forester.sg.service.SplitService.enabled(ctx) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("«Рядом» без root: " + if (on) "включено" else "выключено", 13.sp, if (on) C.Green else C.Orange)
            SettingHint("Служба специальных возможностей «CarPulse: разделение экрана» только включает и выключает " +
                "разделение экрана. Приборы встают в одну половину, приложение — в другую.")
        }
        if (!on) {
            Spacer(Modifier.width(8.dp))
            Chip("Включить", false, {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
    }
}
