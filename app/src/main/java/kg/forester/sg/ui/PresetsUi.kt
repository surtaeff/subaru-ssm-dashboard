package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FlipHorizontal2
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.LayoutTemplate
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.X
import kg.forester.sg.data.Adapted
import kg.forester.sg.data.Board
import kg.forester.sg.data.BoardPreset
import kg.forester.sg.data.BuiltInPresets
import kg.forester.sg.data.CarFacts
import kg.forester.sg.data.Checked
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.PRESET_GROUPS
import kg.forester.sg.data.PresetAdapt
import kg.forester.sg.data.PresetCheck
import kg.forester.sg.data.PresetCodec
import kg.forester.sg.data.PresetLayout
import kg.forester.sg.data.PresetStore
import kg.forester.sg.data.Repo
import kg.forester.sg.data.RepoPresetHost
import kg.forester.sg.data.TileCfg
import kg.forester.sg.data.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** The screen names, as the window's title and the shared text say them. */
fun boardTitle(key: String) = when (key) {
    "homeTiles" -> "Главная"
    "myTiles" -> "Мой экран"
    "driveTiles" -> "Динамика"
    "tcuTiles" -> "АКПП"
    "fuelTiles" -> "Расход"
    else -> key
}

/** SensorBox inputs and own CAN channels by their role; an input of the box wins over a channel with the same role. */
private fun localRoles(): Map<String, String> = buildMap {
    kg.forester.sg.data.CanChannels.list().filter { it.role.isNotEmpty() }.forEach { put(it.role, "can_${it.id}") }
    if (kg.forester.sg.data.SensorBox.enabled.value || Repo.demoOn.value)
        kg.forester.sg.data.SensorBox.slots.value.filter { it.role.isNotEmpty() }.forEach { put(it.role, "box_${it.input}") }
}

/** What this car can show now, for making the presets fit it. */
fun carFactsNow(small: Boolean): CarFacts {
    val obd = Repo.obdMode.value
    return CarFacts(
        turbo = Repo.carTurbo.value, obd = obd, other = kg.forester.sg.data.CarKind.other.value && obd,
        tcu = Repo.settings.gearbox != "mt" && !obd, motion = panelOffered("gforce"), roles = localRoles(),
        wideband = kg.forester.sg.data.Wideband.config.value.enabled, noWb = kg.forester.sg.service.RulesHost.noWideband.value,
        small = small,
    )
}

private val knownStyle: (String) -> Boolean = { id -> DialStyles.any { it.id == id } }

/** A preset as this window offers it: made for the car, the layout it writes and how that looks on this screen. */
private class Entry(val p: BoardPreset, val a: Adapted, val layout: String, val shown: String, val board: Board, val misfit: Boolean) {
    val rawShape get() = PresetLayout.shape(board, layout)
    val shownShape get() = PresetLayout.shape(board, shown)
    val tiles get() = PresetLayout.shown(board, shown)
    val free get() = board.isFree(shown)
}

private fun entryOf(p: BoardPreset, edit: BoardEdit, facts: CarFacts, rowWeight: (TileCfg) -> Float, mirror: Boolean, adapt: Boolean): Entry {
    val a = if (adapt) PresetAdapt.adapt(p, facts, edit.board, edit.gaugeable, rowWeight) else Adapted(p.tiles, emptyList(), emptyList())
    val tiles = if (mirror) PresetAdapt.mirror(edit.board, a.tiles, p.free) else a.tiles
    val layout = if (p.factory) "" else PresetLayout.format(edit.board, tiles, p.free)
    return Entry(p, a, layout, edit.shown(layout, facts.small), edit.board, adapt && PresetAdapt.misfit(p, facts, a))
}

/** The preset just applied, for the «Вернуть» bar on its screen. */
private data class Applied(val board: String, val name: String, val at: Long = System.currentTimeMillis())

private val lastApplied = MutableStateFlow<Applied?>(null)

private fun applyEntry(e: Entry, presetShapes: Set<String>) {
    PresetStore.apply(RepoPresetHost, e.board.key, e.layout, e.p.extras, presetShapes)
    lastApplied.value = Applied(e.board.key, e.p.name)
}

/** The edit bar's way in: «Пресеты». */
@Composable
fun PresetsChip(edit: BoardEdit, rowWeight: (TileCfg) -> Float, names: CardNames) {
    var open by remember { mutableStateOf(false) }
    Chip("Пресеты", false, { open = true }, Lucide.LayoutTemplate)
    if (open) PresetWindow(edit, rowWeight, names) { open = false }
}

/** A line over a factory screen: setting it up card by card takes long, a preset is one tap. */
@Composable
fun PresetHint(edit: BoardEdit, rowWeight: (TileCfg) -> Float, names: CardNames, text: String, closable: Boolean = true) {
    val key = "presetHint.${edit.board.key}"
    var closed by remember { mutableStateOf(closable && Repo.settings.text(key) == "1") }
    if (closed) return
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.LayoutTemplate, null, tint = C.Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        FitText(text, LocalCar.current.secondary, Modifier.weight(1f), C.Sub, maxLines = 2)
        Spacer(Modifier.width(8.dp))
        Parked { PresetsChip(edit, rowWeight, names) }
        if (closable) {
            Spacer(Modifier.width(8.dp))
            Parked { Chip("Скрыть", false, { closed = true; Repo.settings.setText(key, "1") }) }
        }
    }
}

/** For 8 s after a preset went on [board]'s screen: its name and «Вернуть». Over the cards, touches around it pass. */
@Composable
fun PresetUndoBar(board: String) {
    val last by lastApplied.collectAsState()
    val now = last?.takeIf { it.board == board } ?: return
    LaunchedEffect(now) {
        delay(8000)
        if (lastApplied.value == now) lastApplied.value = null
    }
    CarPopup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -with(LocalDensity.current) { 24.dp.roundToPx() })) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(C.Sheet).border(1.dp, C.Purple, RoundedCornerShape(14.dp))
                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Lucide.Check, null, tint = C.Green, modifier = Modifier.size(20.dp))
            T("Пресет «${now.name}» применён", 15.sp, maxLines = 1, modifier = Modifier.widthIn(max = 420.dp))
            Chip("Вернуть", true, { PresetStore.undo(RepoPresetHost, board); lastApplied.value = null }, Lucide.Undo2)
            Box(
                Modifier.size(LocalCar.current.tap).clip(RoundedCornerShape(12.dp)).clickable { lastApplied.value = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.X, "Закрыть", tint = C.Sub, modifier = Modifier.size(22.dp)) }
        }
    }
}

private fun cardName(id: String, names: CardNames): String = when (id) {
    "@oilP" -> "Давление масла"
    "@oil" -> "Температура масла"
    "@egt" -> "Температура выхлопа"
    "@afr" -> "Смесь (AFR)"
    "@fuelP" -> "Давление топлива"
    else -> names[id]?.first ?: Metrics.of(id, emptyList())?.title ?: ExtraCards[id]?.first ?: id
}

/** «Для вашей машины: Наддув → Нагрузка, без IAM»; null when the preset goes on as it is. */
private fun adaptNote(a: Adapted, names: CardNames): String? {
    if (a.changes == 0) return null
    val parts = a.swaps.map { (from, to) -> "${tr(cardName(from, names))} → ${tr(cardName(to, names))}" } +
        a.dropped.map { tr("без ${tr(cardName(it, names))}") }
    return "Для вашей машины: ${parts.joinToString(", ")}"
}

private fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

/** Each local SensorBox / CAN card to its role, so another car can put its own input with that role there. */
private fun exportRoles(): Map<String, String> = localRoles().entries.associate { (role, id) -> id to role }

/**
 * The ready-made layouts of [edit]'s screen and the driver's own, each drawn as it would come out on this car and
 * screen. A tap puts it on at once (one tap, as cheap to undo: the bar under the screen and «Вернуть как было» here).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetWindow(edit: BoardEdit, rowWeight: (TileCfg) -> Float, names: CardNames, onClose: () -> Unit) {
    val key = edit.board.key
    val small = LocalCar.current.small
    val saved by Repo.boardFlow(key).collectAsState()
    var ownVersion by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var mirror by remember { mutableStateOf(Repo.settings.text("presetMirror") == "1") }
    var misfitsOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<BoardPreset?>(null) }
    var importing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<BoardPreset?>(null) }
    val facts = remember(small) { carFactsNow(small) }
    val builtIn = remember(facts, mirror) { BuiltInPresets.of(key).map { entryOf(it, edit, facts, rowWeight, mirror, adapt = true) } }
    val own = remember(facts, ownVersion) {
        PresetStore.own(RepoPresetHost).filter { it.board == key }.map { entryOf(it, edit, facts, rowWeight, false, adapt = false) }
    }
    val presetShapes = remember(builtIn) { builtIn.map { it.rawShape }.toSet() }
    val shownNow = remember(saved, small) { PresetLayout.shape(edit.board, edit.shown(saved, small)) }
    val canUndo = remember(saved, ownVersion) { PresetStore.prev(RepoPresetHost, key) != null }
    // the clipboard holds a preset (copied from a messenger): offered right away
    val clip = LocalClipboardManager.current
    val clipName = remember {
        runCatching { clip.getText()?.text }.getOrNull()?.takeIf { PresetCodec.PREFIX in it }
            ?.let { (PresetCodec.decode(it) as? PresetCodec.Decoded.Ok)?.raw?.name }
    }
    fun pick(e: Entry) { applyEntry(e, presetShapes); onClose() }
    fun currentPreset(name: String, about: String, id: String) =
        PresetStore.current(RepoPresetHost, key, edit.shown(saved, small), name, about, id)

    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = canvasScreen()
        val cols = if (small) 3 else 4
        val cardW = ((screen.screenWidthDp * 0.96f - 28f - 10f * (cols - 1)) / cols).coerceAtLeast(200f).dp
        Column(
            Modifier.widthIn(max = (screen.screenWidthDp * 0.96f).dp).heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .clip(RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    T("Пресеты · ${boardTitle(key)}", 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    T("Пресет заменит раскладку, её можно править как обычно", 12.sp, C.Sub, maxLines = 2)
                }
                if (canUndo) Chip("Вернуть как было", false, { PresetStore.undo(RepoPresetHost, key); onClose() }, Lucide.Undo2)
                Chip("Импорт", false, { importing = true }, Lucide.Download)
                Chip("Закрыть", false, onClose)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Готовые", tab == 0, { tab = 0 })
                Chip("Мои · ${own.size}", tab == 1, { tab = 1 })
                Spacer(Modifier.weight(1f))
                if (tab == 0) Chip("Правый руль", mirror, {
                    mirror = !mirror
                    Repo.settings.setText("presetMirror", if (mirror) "1" else "")
                }, Lucide.FlipHorizontal2)
                else Chip("Поделиться текущим", false, { sharing = currentPreset(tr(boardTitle(key)), "", "") }, Lucide.Share2)
            }
            if (clipName != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Purple.copy(alpha = 0.14f))
                        .border(1.dp, C.Purple, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.ClipboardPaste, null, tint = C.Violet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    T("В буфере есть пресет «$clipName»: добавить?", 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    Chip("Посмотреть", true, { importing = true }, Lucide.Download)
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.cueScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable
                fun Cards(list: List<Entry>) = FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    list.forEach { e ->
                        PresetCard(e, e.shownShape == shownNow, cardW, rowWeight, names, adaptNote(e.a, names)) { pick(e) }
                    }
                }
                if (tab == 0) {
                    val fit = builtIn.filter { !it.misfit }
                    val groups = fit.groupBy { it.p.group }.entries.sortedBy { PRESET_GROUPS.indexOf(it.key) }
                    groups.forEach { (g, list) ->
                        if (groups.size > 1 && g.isNotEmpty()) T(g, 14.sp, C.Sub, FontWeight.SemiBold)
                        Cards(list)
                    }
                    val misfits = builtIn.filter { it.misfit }
                    if (misfits.isNotEmpty()) {
                        Chip("Не для этой машины · ${misfits.size}", misfitsOpen, { misfitsOpen = !misfitsOpen },
                            if (misfitsOpen) Lucide.ChevronUp else Lucide.ChevronDown)
                        if (misfitsOpen) Cards(misfits)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SaveCard(cardW) {
                            val day = java.text.SimpleDateFormat("dd.MM", java.util.Locale.US).format(java.util.Date())
                            saving = currentPreset("${tr(boardTitle(key))} $day", "", "")
                        }
                        own.forEach { e ->
                            PresetCard(e, e.shownShape == shownNow, cardW, rowWeight, names, null, menu = {
                                OwnMenu(
                                    onShare = { sharing = e.p },
                                    onRename = { saving = e.p },
                                    onUpdate = {
                                        PresetStore.replace(RepoPresetHost, currentPreset(e.p.name, e.p.about, e.p.id))
                                        ownVersion++
                                    },
                                    onDelete = { PresetStore.delete(RepoPresetHost, e.p.id); ownVersion++ },
                                )
                            }) { pick(e) }
                        }
                    }
                    if (own.isEmpty()) T("Сохраните свою раскладку, чтобы вернуться к ней одним касанием или отправить её другу", 13.sp, C.Sub)
                }
            }
        }
    }
    saving?.let { p ->
        SaveDialog(p, onSave = { name, about ->
            val ok = if (p.id.isEmpty()) PresetStore.add(RepoPresetHost, p.copy(name = name, about = about, id = "u${System.currentTimeMillis()}"))
            else { PresetStore.replace(RepoPresetHost, p.copy(name = name, about = about)); true }
            if (ok) { saving = null; ownVersion++; tab = 1 }
            ok
        }, onClose = { saving = null })
    }
    sharing?.let { p -> ShareDialog(p) { sharing = null } }
    if (importing) ImportDialog(edit, facts, rowWeight, names, onApply = { e -> importing = false; pick(e) },
        onSaved = { importing = false; ownVersion++; tab = 1 }, onClose = { importing = false })
}

@Composable
private fun PresetCard(
    e: Entry, active: Boolean, width: Dp, rowWeight: (TileCfg) -> Float, names: CardNames, note: String?,
    menu: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?,
) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(12.dp))
            .background(if (active) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(if (active) 2.dp else 1.dp, if (active) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(8.dp),
    ) {
        Box {
            val cluster = e.p.extras["clusterSize"]?.toIntOrNull() ?: Repo.clusterSize.value
            val small = LocalCar.current.small
            Canvas(Modifier.fillMaxWidth().aspectRatio(2.1f).clip(RoundedCornerShape(8.dp)).background(C.Bg)) {
                drawLayout(e.board, e.tiles, e.free, rowWeight, names, if (e.board.key == HomeLayout.board.key) cluster else null, small)
            }
            if (active) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape).background(C.Purple),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Check, "Сейчас на экране", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(e.p.name, 15.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            menu?.invoke()
        }
        if (e.p.about.isNotEmpty()) T(e.p.about, 12.sp, C.Sub, maxLines = 3)
        if (note != null) T(note, 12.sp, C.Orange, maxLines = 2)
    }
}

/** «Сохранить текущий»: the first place in «Мои», as big as a preset. */
@Composable
private fun SaveCard(width: Dp, onClick: () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .border(1.dp, C.Purple, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.1f).clip(RoundedCornerShape(8.dp)).background(C.Bg), contentAlignment = Alignment.Center) {
            Icon(Lucide.Plus, null, tint = C.Violet, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(6.dp))
        T("Сохранить текущий", 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
        T("Раскладка этого экрана как она есть сейчас: вернуть одним касанием или поделиться", 12.sp, C.Sub, maxLines = 3)
    }
}

@Composable
private fun OwnMenu(onShare: () -> Unit, onRename: () -> Unit, onUpdate: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var sure by remember { mutableStateOf(false) }
    val tap = LocalCar.current.tap
    Box {
        Box(
            Modifier.size(tap).clip(RoundedCornerShape(12.dp)).clickable { open = true; sure = false },
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Ellipsis, "Действия", tint = C.Text, modifier = Modifier.size(24.dp)) }
        CarDropdownMenu(open, { open = false }, shape = RoundedCornerShape(16.dp), containerColor = C.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, C.Border)) {
            MenuRow(Lucide.Share2, "Поделиться") { open = false; onShare() }
            MenuRow(Lucide.Pencil, "Переименовать") { open = false; onRename() }
            MenuRow(Lucide.RefreshCw, "Обновить из текущего") { open = false; onUpdate() }
            // a second tap: a saved layout is gone for good
            MenuRow(Lucide.Trash2, if (sure) "Точно удалить?" else "Удалить", C.Red) { if (sure) { open = false; onDelete() } else sure = true }
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color = C.Text, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(min = 280.dp).heightIn(min = LocalCar.current.tap).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        T(text, 17.sp, tint, maxLines = 1)
    }
}

/** A name (and a line about it) for an own preset; returns false from [onSave] when the list is full. */
@Composable
private fun SaveDialog(p: BoardPreset, onSave: (String, String) -> Boolean, onClose: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var about by remember { mutableStateOf(p.about) }
    var full by remember { mutableStateOf(false) }
    CarAlertDialog(
        onDismissRequest = onClose,
        title = { T(if (p.id.isEmpty()) "Сохранить пресет" else "Переименовать пресет", 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { FieldLabel("Название") },
                    modifier = Modifier.fillMaxWidth(), colors = colors,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = about, onValueChange = { about = it.take(200) }, maxLines = 3, label = { FieldLabel("Описание (можно пусто)") },
                    modifier = Modifier.fillMaxWidth(), colors = colors,
                )
                if (full) T("Мест нет: удалите какой-нибудь из своих пресетов (их до ${PresetStore.MAX_OWN})", 13.sp, C.Orange)
            }
        },
        confirmButton = {
            Chip("Сохранить", true, {
                val n = PresetCheck.cleanText(name, 40).ifEmpty { tr(boardTitle(p.board)) }
                full = !onSave(n, PresetCheck.cleanText(about, 200))
            }, Lucide.Check)
        },
        dismissButton = { Chip("Отмена", false, onClose) },
    )
}

private fun shareMessage(p: BoardPreset, code: String) =
    tr("Пресет CarPulse «${p.name}» для экрана «${tr(boardTitle(p.board))}»:") + "\n" + code

private fun sendText(ctx: Context, text: String) {
    runCatching {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Отправить пресет"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun sendFile(ctx: Context, p: BoardPreset, text: String) {
    runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val safe = p.name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("").trim().ifEmpty { "preset" }
        val f = File(dir, "$safe.cpreset").also { it.writeText(text) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Отправить пресет"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** A preset as a QR (a phone's camera reads it off the screen), as text for a messenger, as a file. */
@Composable
private fun ShareDialog(p: BoardPreset, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val code = remember(p) { PresetCodec.encode(p, exportRoles(), appVersion(ctx)) }
    val text = remember(p, code) { shareMessage(p, code) }
    val qrDp = if (LocalCar.current.small) 260.dp else 300.dp
    val px = with(LocalDensity.current) { qrDp.roundToPx() }
    val qr = remember(code, px) { qrBitmap(code, px)?.asImageBitmap() }
    var copied by remember { mutableStateOf(false) }
    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, RoundedCornerShape(16.dp)).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // the quiet zone a camera needs round the code
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(14.dp)) {
                    if (qr != null) Image(qr, "QR", Modifier.size(qrDp), filterQuality = FilterQuality.None)
                    else Box(Modifier.size(qrDp))
                }
                Spacer(Modifier.height(6.dp))
                T("${code.take(10)}…${code.takeLast(6)}", 12.sp, C.Sub, maxLines = 1)
            }
            Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("Поделиться пресетом", 18.sp, weight = FontWeight.SemiBold)
                T("«${p.name}» для экрана «${tr(boardTitle(p.board))}»", 14.sp, maxLines = 2)
                T("Отсканируйте телефоном или покажите другу. Код можно отправить в Telegram или WhatsApp, на другой машине: «Пресеты», «Импорт».", 13.sp, C.Sub)
                Chip("Отправить код", true, { sendText(ctx, text) }, Lucide.Send, Modifier.fillMaxWidth())
                Chip("Файлом", false, { sendFile(ctx, p, text) }, Lucide.FileText, Modifier.fillMaxWidth())
                Chip(if (copied) "Скопировано" else "Скопировать код", copied, { clip.setText(AnnotatedString(text)); copied = true }, Lucide.Copy, Modifier.fillMaxWidth())
                Chip("Закрыть", false, onClose, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A preset from a code: pasted, from the clipboard or from a file; checked and shown before anything changes. */
@Composable
private fun ImportDialog(
    edit: BoardEdit, facts: CarFacts, rowWeight: (TileCfg) -> Float, names: CardNames,
    onApply: (Entry) -> Unit, onSaved: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val fromClip = remember { runCatching { clip.getText()?.text }.getOrNull()?.takeIf { PresetCodec.PREFIX in it } }
    var text by remember { mutableStateOf(fromClip ?: "") }
    var fileNote by remember { mutableStateOf("") }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val read = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(8192)
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull()
        if (read != null) { text = read; fileNote = "" } else fileNote = "Файл не открылся"
    }
    val decoded = remember(text) { if (text.isBlank()) null else PresetCodec.decode(text) }
    val local = remember { localRoles() }
    val checked = (decoded as? PresetCodec.Decoded.Ok)?.let { PresetCheck.forBoard(it.raw, edit.board.key, local, knownStyle) }
    CarDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val screen = canvasScreen()
        Column(
            Modifier.widthIn(max = minOf(760f, screen.screenWidthDp * 0.96f).dp).heightIn(max = (screen.screenHeightDp * 0.94f).dp)
                .clip(RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Purple, RoundedCornerShape(16.dp))
                .padding(16.dp).cueScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("Импорт пресета", 18.sp, weight = FontWeight.SemiBold)
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, maxLines = 3, label = { FieldLabel("Код пресета (CPP1:…)") },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Вставить из буфера", false, { runCatching { clip.getText()?.text }.getOrNull()?.let { text = it } }, Lucide.ClipboardPaste)
                Chip("Открыть файл", false, { runCatching { open.launch(arrayOf("*/*")) } }, Lucide.FolderOpen)
            }
            if (fileNote.isNotEmpty()) T(fileNote, 13.sp, C.Orange)
            when (decoded) {
                null -> T("Вставьте код, который вам прислали, или откройте файл .cpreset", 13.sp, C.Sub)
                PresetCodec.Decoded.NoCode -> T("Здесь нет кода пресета: он начинается с CPP1:", 14.sp, C.Orange)
                PresetCodec.Decoded.Damaged -> T("Код повреждён или обрезан", 14.sp, C.Orange)
                PresetCodec.Decoded.Newer -> T("Пресет из более новой версии CarPulse: обновите приложение", 14.sp, C.Orange)
                is PresetCodec.Decoded.Ok -> when (checked) {
                    is Checked.WrongBoard -> {
                        T("Это пресет для экрана «${tr(boardTitle(checked.board))}»", 15.sp, C.Orange)
                        T("Сохраните его в «Мои»: он появится в пресетах того экрана", 13.sp, C.Sub)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Сохранить в «Мои»", true, {
                                val ok = PresetCheck.check(decoded.raw, local, knownStyle) as? Checked.Ok
                                if (ok != null && PresetStore.add(RepoPresetHost, ok.preset.copy(id = "u${System.currentTimeMillis()}"))) onSaved()
                            }, Lucide.Plus)
                            Chip("Отмена", false, onClose)
                        }
                    }
                    is Checked.Ok -> {
                        val e = remember(checked) { entryOf(checked.preset, edit, facts, rowWeight, false, adapt = true) }
                        PresetCard(e, false, 320.dp, rowWeight, names, adaptNote(e.a, names), onClick = null)
                        if (checked.dropped.isNotEmpty()) T("Не перенесено: ${checked.dropped.joinToString(", ") { tr(cardName(it, names)) }}", 13.sp, C.Sub)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Применить", true, { onApply(e) }, Lucide.Check)
                            Chip("Сохранить в «Мои»", false, {
                                val p = checked.preset.copy(tiles = e.a.tiles, id = "u${System.currentTimeMillis()}")
                                if (PresetStore.add(RepoPresetHost, p)) onSaved()
                            }, Lucide.Plus)
                            Chip("Отмена", false, onClose)
                        }
                    }
                    else -> T("Код повреждён или обрезан", 14.sp, C.Orange)
                }
            }
        }
    }
}

/** A small picture of a layout: each card where it would be, drawn by its kind; Home with its 3D band on top. */
private fun DrawScope.drawLayout(
    board: Board, tiles: List<TileCfg>, free: Boolean, rowWeight: (TileCfg) -> Float, names: CardNames, homeCluster: Int?, small: Boolean,
) {
    val gap = size.width * 0.012f
    val placed = ArrayList<Pair<TileCfg, Rect>>()
    if (free) tiles.forEach { t ->
        val r = t.free ?: return@forEach
        placed += t to Rect(r.x * size.width + gap / 2, r.y * size.height + gap / 2, (r.x + r.w) * size.width - gap / 2, (r.y + r.h) * size.height - gap / 2)
    } else {
        val columns = if (homeCluster != null) HomeLayout.columnsFor(tiles, small) else board.columns
        val rows = board.rows(tiles, columns)
        val weights = rows.map { r -> r.maxOf(rowWeight) }
        val band = if (homeCluster != null) HomeLayout.clusterWeight(homeCluster) else 0f
        val total = (weights.sum() + band).takeIf { it > 0f } ?: return
        val room = size.height - gap * (rows.size + 1 + if (band > 0f) 1 else 0)
        var y = gap
        if (band > 0f) {
            val h = room * band / total
            drawCluster(Rect(gap, y, size.width - gap, y + h))
            y += h + gap
        }
        rows.forEachIndexed { i, row ->
            val h = room * weights[i] / total
            val spans = if (board.stretch) row.sumOf { it.span }.toFloat() else columns.toFloat()
            var x = gap
            val wAll = size.width - gap * ((if (board.stretch) row.size else columns) + 1)
            row.forEach { t ->
                val w = wAll * t.span / spans + if (board.stretch) 0f else gap * (t.span - 1)
                placed += t to Rect(x, y, x + w, y + h)
                x += w + gap
            }
            y += h + gap
        }
    }
    placed.forEach { (t, r) -> drawTile(t, r, names) }
}

/** The 3D block of Home: the car between the two dials. */
private fun DrawScope.drawCluster(r: Rect) {
    drawRoundRect(C.CardHi, r.topLeft, r.size, CornerRadius(r.height * 0.1f))
    val d = minOf(r.height * 0.42f, r.width * 0.12f)
    listOf(r.left + r.width * 0.14f, r.right - r.width * 0.14f).forEach { x -> ring(Offset(x, r.center.y), d, C.Violet, 0.6f) }
    val car = Size(r.width * 0.3f, r.height * 0.36f)
    drawRoundRect(C.Border, Offset(r.center.x - car.width / 2, r.center.y - car.height / 2), car, CornerRadius(car.height * 0.4f))
}

private fun DrawScope.drawTile(t: TileCfg, r: Rect, names: CardNames) {
    val corner = CornerRadius(minOf(r.width, r.height) * 0.12f)
    val c = r.center
    val name = Metrics.of(t.id, emptyList())?.short ?: names[t.id]?.first ?: ExtraCards[t.id]?.first ?: ""
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
            label("88", Offset(r.left + r.width * 0.62f, r.top + r.height * 0.7f), r.height * 0.3f, Color(0xFFF4F5F8), r.width * 0.5f)
        }
        t.id == "navcluster" || t.id == "navmap" || t.id == "minimap" -> {
            drawRoundRect(Color(0xFF070B14), r.topLeft, r.size, corner)
            val road = Color(0xFF3D5172)
            for (k in 1..4) drawLine(road, Offset(r.left + r.width * (0.3f + k * 0.08f), r.top), Offset(r.left + r.width * (0.25f + k * 0.1f), r.bottom), r.height * 0.02f)
            drawLine(road, Offset(r.left + r.width * 0.2f, r.top + r.height * 0.4f), Offset(r.right - r.width * 0.2f, r.top + r.height * 0.55f), r.height * 0.03f)
            drawLine(Color(0xFF4FC3F7), Offset(c.x, r.bottom - r.height * 0.1f), Offset(c.x, r.top + r.height * 0.62f), r.height * 0.04f)
            if (t.id == "navcluster") {
                val d = minOf(r.height * 0.9f, r.width * 0.3f) / 2
                listOf(r.left + r.width * 0.015f + d, r.right - r.width * 0.015f - d).forEach { x ->
                    drawCircle(Color(0xFF070B14), d, Offset(x, c.y))
                    ring(Offset(x, c.y), d * 0.9f, Color(0xFF4FC3F7), 0.6f)
                }
            }
        }
        t.id in kg.forester.sg.data.GAUGE_PANELS -> {
            drawRoundRect(Color(0xFF06080D), r.topLeft, r.size, corner)
            ring(Offset(c.x, c.y + r.height * 0.08f), minOf(r.width, r.height) * 0.36f, Color(0xFF4FC3F7), 0.6f)
            label(name, Offset(c.x, r.bottom - r.height * 0.08f), r.height * 0.1f, Color(0xFFE8EAEE), r.width * 0.9f)
        }
        t.id == "gmeter" -> {
            drawRoundRect(C.Card, r.topLeft, r.size, corner)
            val d = minOf(r.width, r.height) * 0.38f
            drawCircle(C.Border, d, c, style = Stroke(r.height * 0.02f))
            drawCircle(C.Border, d * 0.5f, c, style = Stroke(r.height * 0.02f))
            drawCircle(C.Violet, d * 0.12f, Offset(c.x + d * 0.3f, c.y - d * 0.2f))
        }
        t.id.startsWith("chart") || t.id == "chart" -> {
            drawRoundRect(C.Card, r.topLeft, r.size, corner)
            val line = Path().apply {
                val n = 8
                (0..n).forEach { k ->
                    val x = r.left + r.width * (0.06f + 0.88f * k / n)
                    val y = r.top + r.height * (0.62f - 0.3f * kotlin.math.sin(k * 0.9f + t.id.length).toFloat())
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(line, C.Violet, style = Stroke(r.height * 0.05f, cap = StrokeCap.Round))
            label(name, Offset(c.x, r.top + r.height * 0.24f), r.height * 0.16f, C.Sub, r.width * 0.9f)
        }
        t.id == "tach" || t.id == "speedo" || t.style.isNotEmpty() && !t.style.startsWith("sd:") && !t.style.startsWith("nav:") -> {
            drawRoundRect(C.Card, r.topLeft, r.size, corner)
            val st = dialStyle(DialOptions.parse(t.style).base)
            val d = minOf(r.width, r.height) * 0.42f
            drawCircle(st.face.lastOrNull() ?: C.Bg, d, c)
            ring(c, d * 0.86f, st.arc?.lastOrNull() ?: st.needle, 0.55f, st.track)
            label(name, Offset(c.x, c.y + d * 0.2f), d * 0.32f, st.numbers, d * 1.6f)
        }
        else -> {
            drawRoundRect(C.CardHi, r.topLeft, r.size, corner)
            label(name, Offset(c.x, c.y + r.height * 0.08f), minOf(r.height * 0.28f, r.width * 0.16f), C.Sub, r.width * 0.9f)
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

/** [text] centred at [at], smaller as far as it needs to fit [maxWidth]. */
private fun DrawScope.label(text: String, at: Offset, size: Float, col: Color, maxWidth: Float) {
    if (text.isEmpty() || size < 4f) return
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = col.toArgb(); textSize = size; textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val s = tr(text)
    val w = p.measureText(s)
    if (w > maxWidth && w > 0f) p.textSize = size * maxWidth / w
    if (p.textSize < 4f) return
    drawContext.canvas.nativeCanvas.drawText(s, at.x, at.y, p)
}
