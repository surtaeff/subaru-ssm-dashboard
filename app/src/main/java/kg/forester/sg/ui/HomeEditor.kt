package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Rows3
import com.composables.icons.lucide.Grid3x3
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import kg.forester.sg.data.Board
import kg.forester.sg.data.FreeRect
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Move
import com.composables.icons.lucide.Scaling
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.ZoomOut
import com.composables.icons.lucide.Ellipsis
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kg.forester.sg.data.HomeLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import kg.forester.sg.data.Repo
import kg.forester.sg.data.TileCfg

/**
 * Edits of an editable screen's layout, made right on that screen (long press on a card).
 * [rowWeight] is the relative row height the screen gives a card, used when switching to the free layout.
 */
class BoardEdit(
    val board: Board,
    val gaugeable: Set<String> = emptySet(),
    private val rowWeight: (TileCfg) -> Float = { 1f },
    /** Top and height of the grid's part of the free area (the home grid sits below the 3D block). */
    private val gridBand: () -> Pair<Float, Float> = { 0f to 1f },
    /** Screen-specific changes when switching to the free layout and back (home: split the 3D block). */
    private val toFree: (List<TileCfg>) -> List<TileCfg> = { it },
    private val toGrid: (List<TileCfg>) -> List<TileCfg> = { it },
    private val onReset: () -> Unit = {},
    /** The factory layout this screen's size gets (see [HomeLayout.effective]): what is shown is what is edited. */
    private val effective: (String, Boolean) -> String = { s, _ -> s },
    /** How many columns the grid has for these cards. */
    private val columns: (List<TileCfg>) -> Int = { board.columns },
) {
    private val raw get() = shown(Repo.boardFlow(board.key).value, CarScreen.small)
    fun shown(saved: String, small: Boolean) = effective(saved, small)
    fun list() = board.parse(raw)
    fun isFree() = board.isFree(raw)
    private fun save(l: List<TileCfg>, free: Boolean = isFree()) = Repo.settings.setBoard(board.key, board.format(l, free))

    /** Free layout on/off; the cards keep the places they had in the grid. */
    fun setFree(on: Boolean) {
        if (on == isFree()) return
        save(if (on) toFree(board.withFreeRects(list(), rowWeight, gridBand(), columns(list()))) else toGrid(list()), on)
    }

    /** Puts [id] at [r], on top of the other cards. */
    fun place(id: String, r: FreeRect) {
        val l = list().map { if (it.id == id) it.copy(free = r) else it }.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) l.add(l.removeAt(i))
        save(l)
    }

    /**
     * Another shown card is drawn over part of [id] in the free layout: only then does "to front" change anything
     * (a card moved or sized comes to the front by itself; the home 3D card is always at the bottom).
     */
    fun covered(id: String): Boolean {
        val l = list().filter { it.on && it.id != "car3d" }
        val i = l.indexOfFirst { it.id == id }
        val r = l.getOrNull(i)?.free ?: return false
        return l.drop(i + 1).any { o ->
            val q = o.free ?: return@any false
            minOf(r.x + r.w, q.x + q.w) - maxOf(r.x, q.x) > 0.001f && minOf(r.y + r.h, q.y + q.h) - maxOf(r.y, q.y) > 0.001f
        }
    }

    /** Draws [id] on top of the others in the free layout (the home 3D card always stays at the bottom). */
    fun toFront(id: String) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        l.add(l.removeAt(i))
        save(l)
    }

    /** Swap with the nearest shown card before (-1) or after (+1). */
    fun move(id: String, dir: Int) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        var j = i + dir
        while (j in l.indices && !l[j].on) j += dir
        if (i < 0 || j !in l.indices) return
        val t = l[i]; l[i] = l[j]; l[j] = t
        save(l)
    }

    fun resize(id: String, delta: Int) =
        save(list().map { if (it.id == id) it.copy(span = (it.span + delta).coerceIn(1, board.maxSpan)) else it })

    fun setStyle(id: String, style: String) = save(list().map { if (it.id == id) it.copy(style = style) else it })

    fun hide(id: String) = save(list().map { if (it.id == id) it.copy(on = false) else it })

    /** Shows a hidden card at the end of the grid. */
    fun add(id: String) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = l.removeAt(i).copy(on = true).let { if (isFree() && it.free == null) it.copy(free = FreeRect(0.35f, 0.3f, 0.3f, 0.4f)) else it }
        l.add(l.indexOfLast { it.on } + 1, t)
        save(l)
    }

    fun reset() { Repo.settings.setBoard(board.key, ""); onReset() }
}

private val ClusterParts = setOf("car3d", "tach", "speedo")

val HomeEdit = BoardEdit(
    // the extra cards (Спорт-дисплей, G-метр…) and the output widgets have no needle look: they get their own buttons
    HomeLayout.board, HomeLayout.board.defaults.map { it.id }.toSet() - "car3d" - "minimap" - HomeLayout.ROAD -
        kg.forester.sg.data.EXTRA_CARDS.toSet() - kg.forester.sg.data.OUT_WIDGETS.toSet(),
    gridBand = {
        val c = HomeLayout.clusterWeight(Repo.clusterSize.value)
        c / (c + 3f) to 3f / (c + 3f)
    },
    // the 3D block becomes three cards (car, tachometer, speedometer) in the places they had in the band
    toFree = { list ->
        val c = HomeLayout.clusterWeight(Repo.clusterSize.value)
        val band = c / (c + 3f)
        if (band == 0f) list else {
            val dh = (band - 0.04f).coerceAtLeast(0.05f)
            val dw = dh / 2f // the area is about twice as wide as it is tall
            val y = (band - dh) / 2f
            val out = list.map {
                when (it.id) {
                    "car3d" -> it.copy(on = true, free = FreeRect(0f, 0f, 1f, band))
                    "tach" -> if (it.on) it else it.copy(on = true, free = FreeRect(0.012f, y, dw, dh))
                    "speedo" -> if (it.on) it else it.copy(on = true, free = FreeRect(1f - 0.012f - dw, y, dw, dh))
                    else -> it
                }
            }
            out.filter { it.id == "car3d" } + out.filter { it.id != "car3d" } // the car at the bottom
        }
    },
    toGrid = { list -> list.map { if (it.id in ClusterParts) it.copy(on = false) else it } },
    onReset = { Repo.settings.clusterSize = 1 },
    effective = HomeLayout::effective,
    columns = { HomeLayout.columnsFor(it, CarScreen.small) },
)

/** Card names and icons for the "add" menu, by card id. */
typealias CardNames = Map<String, Pair<String, ImageVector>>

/** Toolbar shown above the home grid while editing: the 3D block size, then the common buttons. */
@Composable
fun HomeEditBar(onDone: () -> Unit) {
    val cluster by Repo.clusterSize.collectAsState()
    val free = HomeLayout.board.isFree(Repo.homeTiles.collectAsState().value)
    BoardEditBar(HomeEdit, if (free) HomeTileInfo else HomeTileInfo - "car3d", onDone) {
        Chip("Просто", false, {
            val turbo = Repo.guard.value.turboCar
            // another make: no ATF temperature (that is the Subaru transmission unit's)
            Repo.settings.homeTiles = HomeLayout.simple(turbo, Repo.settings.gearbox != "mt" && !kg.forester.sg.data.CarKind.other.value)
            Repo.settings.clusterSize = 2
        }, Lucide.Sparkles)
        if (free) return@BoardEditBar // the 3D block is a card there, sized by dragging
        T("3D:", 14.sp, C.Sub)
        listOf(0 to "Скрыть", 1 to "Обычный", 2 to "Большой", 3 to "Макс.").forEach { (v, l) ->
            Chip(l, cluster == v, { Repo.settings.clusterSize = v })
        }
    }
}

/**
 * Toolbar shown above an editable grid: [leading] screen-specific controls, add, reset, done. On a narrow screen
 * (an 8" unit is 1024 px wide) the controls scroll sideways and «Готово» stays in sight at the right end.
 */
@Composable
fun BoardEditBar(edit: BoardEdit, names: CardNames, onDone: () -> Unit, leading: @Composable RowScope.() -> Unit = {}) {
    var adding by remember { mutableStateOf(false) }
    val free = edit.board.isFree(Repo.boardFlow(edit.board.key).collectAsState().value)
    Parked { Row(
        Modifier.fillMaxWidth().clip(Rounded).panelFill()
            .border(1.dp, C.Purple, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.weight(1f).drawWithContent {
            drawContent()
            // more to the left or right: the edge fades, so a control half under «Готово» reads as «scroll me»
            val fade = 28.dp.toPx()
            if (scroll.value < scroll.maxValue) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.Card.copy(alpha = 0f), C.Card), size.width - fade, size.width),
                androidx.compose.ui.geometry.Offset(size.width - fade, 0f), androidx.compose.ui.geometry.Size(fade, size.height))
            if (scroll.value > 0) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.Card, C.Card.copy(alpha = 0f)), 0f, fade),
                androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(fade, size.height))
        }) {
            val room = maxWidth
            Row(
                // at least the bar's width, so the spacer still pushes the grid controls right on a wide screen
                Modifier.horizontalScroll(scroll).widthIn(min = room),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leading()
                Spacer(Modifier.weight(1f))
                Chip("Рядами", !free, { edit.setFree(false) }, Lucide.Rows3)
                Chip("По клеткам", free, { edit.setFree(true) }, Lucide.LayoutGrid)
                if (free) {
                    val grid by Repo.freeGrid.collectAsState()
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Chip(grid.ifEmpty { "без привязки" }.replace("x", "×"), false, { open = true }, Lucide.Grid3x3)
                        CarDropdownMenu(open, { open = false }) {
                            listOf("8x5", "10x6", "12x8", "").forEach { g ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (g.isEmpty()) "Без привязки" else g.replace("x", " × ")) },
                                    onClick = { open = false; Repo.settings.freeGrid = g },
                                )
                            }
                        }
                    }
                }
                Box {
                    Chip("Добавить", false, { adding = true }, Lucide.Plus)
                    if (adding) AddMenu(edit, names) { adding = false }
                }
                Chip("Сбросить", false, { edit.reset() })
            }
        }
        Chip("Готово", true, onDone)
    } }
}

@Composable
private fun AddMenu(edit: BoardEdit, names: CardNames, onClose: () -> Unit) {
    val hidden = edit.list().filter { !it.on && names[it.id] != null && panelOffered(it.id) }
    val avail = Repo.rrAvailable.collectAsState().value
    // in sections, as in the rest of the app: a long flat list hid the sensor cards among the gauges
    val groups = hidden.groupBy { cardGroup(it.id) }.entries.sortedBy { CARD_GROUPS.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
    CarPopup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, 120), onDismissRequest = onClose) {
        Column(
            Modifier.width(LocalCar.current.secondary.value.dp * 20f).heightIn(max = minOf(520f, canvasScreen().screenHeightDp - 140f).dp).clip(RoundedCornerShape(14.dp)).background(C.Sheet)
                .border(1.dp, C.Border, RoundedCornerShape(14.dp)).padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (hidden.isEmpty()) T("Все виджеты уже на экране", 14.sp, C.Sub, modifier = Modifier.padding(16.dp))
            groups.forEach { (group, list) ->
                T(group, 12.sp, C.Sub, FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                list.forEach { t ->
                    val (title, icon) = names[t.id]!!
                    // a «Датчик N» card says what it is set to show
                    val shows = t.id.removePrefix("s").toIntOrNull()?.takeIf { t.id.startsWith("s") }?.let { k ->
                        Repo.settings.slot(k).takeIf { it.isNotEmpty() }?.let { Metrics.of(it, avail)?.title }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { edit.add(t.id); onClose() }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            T(title, 15.sp, maxLines = 1)
                            if (shows != null) T(shows, 11.sp, C.Sub, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private val CARD_GROUPS = listOf(
    "Двигатель", "Топливо", "Зажигание", "Наддув", "АКПП", "Приборы и карта", "Динамика и замеры", "Защита мотора",
    "Шины и ТО", "Доп датчики", "Выходы: кнопки и регуляторы", "Свои датчики (Датчик 1–6)", "Прочее",
)

/** The section a card is offered under in «Добавить виджет». */
private fun cardGroup(id: String): String = when {
    id in setOf("defi", "navcluster", "tach", "speedo", "car3d", HomeLayout.ROAD, "minimap", "gps", "altitude") -> "Приборы и карта"
    id in setOf("gmeter", "incline", "peaks", "run") -> "Динамика и замеры"
    id in setOf("iam", "btarget", "turbo") -> "Защита мотора"
    id in setOf("tyres", "service") -> "Шины и ТО"
    id.startsWith("outw_") -> "Выходы: кнопки и регуляторы"
    Regex("^s\\d$").matches(id) -> "Свои датчики (Датчик 1–6)"
    else -> when (val g = Metrics.of(id, emptyList())?.group) {
        null -> "Прочее"
        "Зажигание" -> "Зажигание"
        "GPS" -> "Приборы и карта"
        else -> if (g in CARD_GROUPS) g else "Прочее"
    }
}

/**
 * Controls drawn over a card while editing: one «⋯» button in its middle, opening a menu with the card's actions —
 * move, narrower/wider, gauge design (cards with one number), remove — in buttons a finger hits on a moving car
 * (it was six 28 dp buttons squeezed into the card). In the free layout ([free]) moving and sizing are done by
 * dragging, so only "to front", design and remove stay. Move and size leave the menu open for the next step.
 */
@Composable
fun BoxScope.TileEditOverlay(edit: BoardEdit, cfg: TileCfg, title: String = "", free: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier.matchParentSize().clip(Rounded).background(Color(0x66070A14))
            .border(2.dp, C.Purple, RoundedCornerShape(14.dp)),
    )
    BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
        val gauge = cfg.id in edit.gaugeable
        val car = cfg.id == "car3d" // the 3D card: car size instead of a gauge design
        val map = cfg.id == "minimap" // the map card: map design
        val slot = Regex("s[1-6]").matches(cfg.id) // «Датчик k»: which value it shows
        val own = cfg.id == "defi" || cfg.id == "navcluster" // cards with an editor of their own
        val btn = minOf(56.dp, maxWidth - 8.dp, maxHeight - 6.dp)
        Box {
            EditButton(Lucide.Ellipsis, btn, tint = C.Text, label = "Действия: $title") { menu = true }
            CarDropdownMenu(
                menu, { menu = false },
                shape = RoundedCornerShape(16.dp), containerColor = C.Card,
                border = androidx.compose.foundation.BorderStroke(1.dp, C.Border),
            ) {
                if (title.isNotEmpty()) T(title, 14.sp, C.Sub, FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp))
                if (free) {
                    // only when another card covers part of this one: bring it over
                    if (edit.covered(cfg.id)) MenuAction(Lucide.Layers, "Поверх других") { edit.toFront(cfg.id); menu = false }
                } else {
                    MenuStepper("Место", null, Lucide.ChevronLeft, true, { edit.move(cfg.id, -1) },
                        Lucide.ChevronRight, true) { edit.move(cfg.id, 1) }
                    MenuStepper("Ширина", "${cfg.span}", Lucide.Minus, cfg.span > 1, { edit.resize(cfg.id, -1) },
                        Lucide.Plus, cfg.span < edit.board.maxSpan) { edit.resize(cfg.id, 1) }
                }
                if (car) {
                    val s = carScaleOf(cfg.style)
                    MenuStepper("Размер машины", null,
                        Lucide.ZoomOut, s > 0.31f, { edit.setStyle(cfg.id, "scale:%.1f".format(java.util.Locale.US, s - 0.1f)) },
                        Lucide.ZoomIn, s < 0.99f) { edit.setStyle(cfg.id, "scale:%.1f".format(java.util.Locale.US, s + 0.1f)) }
                }
                if (gauge) MenuAction(Lucide.Gauge, "Вид прибора", if (cfg.style.isEmpty()) C.Text else C.Violet) { menu = false; picking = true }
                if (map) MenuAction(Lucide.Palette, "Вид карты", C.Violet) { menu = false; picking = true }
                if (slot) MenuAction(Lucide.ListChecks, "Что показывает", C.Violet) { menu = false; picking = true }
                if (own) MenuAction(Lucide.Palette, "Настроить вид", C.Violet) { menu = false; picking = true }
                MenuAction(Lucide.X, "Убрать с экрана", C.Red) { menu = false; edit.hide(cfg.id) }
            }
        }
    }
    if (picking && cfg.id == "minimap") MapStylePicker(cfg.style, { edit.setStyle(cfg.id, it) }, { picking = false })
    else if (picking && cfg.id == "defi") SportEditor(cfg.style, { edit.setStyle(cfg.id, it) }) { picking = false }
    else if (picking && cfg.id == "navcluster") NavClusterEditor(cfg.style, { edit.setStyle(cfg.id, it) }) { picking = false }
    else if (picking && Regex("s[1-6]").matches(cfg.id)) SlotPicker(cfg.id.drop(1).toInt()) { picking = false }
    else if (picking) {
        // the number in the dial's middle can be left out; it rides along with whatever design is picked
        val o = DialOptions.parse(cfg.style)
        val base = o.base
        StylePicker(
            "Вид: $title", base, "Обычный", Modifier, target = "${edit.board.key}/${cfg.id}",
            preview = { st, m ->
                Dial(62f, 100f, 20f, 1f, "", null, 80f, m, st.copy(hideValue = o.noNumber, marks = o.marks, numberScale = o.numberPct?.let { it / 100f } ?: st.numberScale))
            },
            onPick = { edit.setStyle(cfg.id, if (it.isNotEmpty()) o.copy(base = it).format() else it) },
            onClose = { picking = false },
            numberShown = if (base.isNotEmpty()) !o.noNumber else null,
            onNumber = { show -> edit.setStyle(cfg.id, o.copy(noNumber = !show).format()) },
            options = o.takeIf { base.isNotEmpty() },
            onOptions = { edit.setStyle(cfg.id, it.format()) },
        )
    }
}

/**
 * An editable screen: rows of cards from [edit]'s layout, each row as tall as [rowWeight] of its tallest card.
 * Long press on any card switches to editing.
 */
@Composable
fun BoardScreen(
    edit: BoardEdit, names: CardNames, rowWeight: (TileCfg) -> Float,
    /** Screen-specific controls at the start of the edit bar («Мой экран»: the ready-made layouts). */
    leading: @Composable RowScope.() -> Unit = {},
    /** What else the factory picture of this screen goes by, when it changes. */
    factoryKey: Any? = null,
    card: @Composable (TileCfg, Modifier) -> Unit,
) {
    val saved by Repo.boardFlow(edit.board.key).collectAsState()
    val small = LocalCar.current.small
    val carTurbo by Repo.carTurbo.collectAsState()
    val layout = remember(saved, small, factoryKey, carTurbo) { edit.shown(saved, small) }
    val list = remember(layout) { edit.board.parse(layout) }
    val rows = remember(layout) { edit.board.rows(list) }
    var editing by remember { mutableStateOf(false) }
    val longPress = Modifier.onLongPressBuzz { editing = true }
    // Back ends the editing before it leaves the screen
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }
    if (edit.board.isFree(layout)) {
        FreeFrame(if (editing) ({ BoardEditBar(edit, names, { editing = false }, leading) }) else null, Modifier.fillMaxSize()) { m ->
            FreeArea(edit, list.filter { it.on }, editing, names, longPress, m, card = card)
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (editing) BoardEditBar(edit, names, { editing = false }, leading)
        rows.forEachIndexed { rowAt, row ->
            val rowOn = rememberLater(rowAt)
            Row(Modifier.weight(row.maxOf { rowWeight(it) }).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (rowOn || rowAt == 0) row.forEach { t ->
                    // keyed by the card: one moved along its row takes its state along (the open edit menu stays its own)
                    key(t.id) {
                        Box(Modifier.weight(t.span.toFloat()).fillMaxHeight().then(longPress)) {
                            card(t, Modifier.fillMaxSize())
                            if (editing) TileEditOverlay(edit, t, names[t.id]?.first ?: "")
                        }
                    }
                }
                val used = row.sumOf { it.span }
                if (!edit.board.stretch && used < edit.board.columns) Spacer(Modifier.weight((edit.board.columns - used).toFloat()))
            }
        }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().then(longPress), contentAlignment = Alignment.Center) {
                T(if (editing) "Добавьте виджеты кнопкой «Добавить виджет»" else "Виджетов нет: нажмите и удерживайте здесь, чтобы настроить", 16.sp, C.Sub)
            }
        }
    }
}

/**
 * The free layout's area always measured at its usual size, with or without the edit bar over it: the bar (or a tip)
 * only shrinks the picture of it, keeping its proportions, so cards stay exactly where they were set while editing.
 * (Laid out below the bar instead, the area was shorter while editing, and the cards moved when the bar went away.)
 * While [editing], the bottom bar steps aside and its room makes up for the edit bar's: the grid keeps its scale and
 * the whole width, instead of shrinking with empty strips at its sides.
 */
@Composable
fun FreeFrame(header: (@Composable () -> Unit)?, modifier: Modifier, editing: Boolean = header != null, content: @Composable (Modifier) -> Unit) {
    DisposableEffect(editing) {
        Repo.freeEditing.value = editing
        onDispose { Repo.freeEditing.value = false }
    }
    BoxWithConstraints(modifier) {
        val now = constraints.maxHeight
        // the area's height without the edit bar (and with the bottom bar): the size its cards were set at
        var usual by remember { mutableStateOf(0) }
        if (!editing && now > 0 && usual != now) SideEffect { usual = now }
        val base = (if (usual > 0) usual else now).toFloat()
        var barH by remember { mutableStateOf(0) }
        val gap = with(LocalDensity.current) { 12.dp.toPx() }
        val top = if (header != null && barH > 0) barH + gap else 0f
        val k = if (base > 0f) ((now - top) / base).coerceIn(0.3f, 1f) else 1f
        val baseDp = with(LocalDensity.current) { base.toDp() }
        content(Modifier.fillMaxWidth().requiredHeight(baseDp).align(Alignment.TopCenter).graphicsLayer {
            scaleX = k; scaleY = k
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            // requiredHeight centres an area taller than the room: put its top just under the bar
            translationY = top - if (base > now) (now - base) / 2f else 0f
        })
        if (header != null) Box(Modifier.fillMaxWidth().onSizeChanged { barH = it.height }.zIndex(2f)) { header() }
    }
}

/**
 * Cards placed anywhere, each at its [TileCfg.free] rect (later cards on top). While [editing], drag a card to
 * move it and the corner handle to resize it in both directions; both snap to the cells of the grid picked in the
 * edit bar (10 × 6 by default, drawn while editing, as icons on a phone's home screen), or to 0.5 % steps without one.
 */
@Composable
fun FreeArea(
    edit: BoardEdit, tiles: List<TileCfg>, editing: Boolean, names: CardNames, longPress: Modifier, modifier: Modifier,
    backgroundPress: Boolean = true,
    card: @Composable (TileCfg, Modifier) -> Unit,
) {
    // without [backgroundPress] the empty space passes touches through to what is drawn below (the 3D block)
    BoxWithConstraints(if (backgroundPress) modifier.then(longPress) else modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val minW = with(density) { 56.dp.toPx() } / w
        val minH = with(density) { 40.dp.toPx() } / h
        val grid by Repo.freeGrid.collectAsState()
        val cells = grid.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }
        fun snapX(x: Float) = if (cells != null) kotlin.math.round(x * cells[0]) / cells[0] else kotlin.math.round(x * 200f) / 200f
        fun snapY(y: Float) = if (cells != null) kotlin.math.round(y * cells[1]) / cells[1] else kotlin.math.round(y * 200f) / 200f
        // at least one cell, and inside the area
        fun sized(r: FreeRect): FreeRect {
            if (cells == null) return r.copy(w = kotlin.math.round(r.w * 200f) / 200f, h = kotlin.math.round(r.h * 200f) / 200f)
            val w = (snapX(r.w)).coerceIn(1f / cells[0], 1f - r.x)
            val h = (snapY(r.h)).coerceIn(1f / cells[1], 1f - r.y)
            return r.copy(w = w, h = h)
        }
        // whichever edge is nearer a grid line sticks to it: a card sized on another grid (not a whole number of cells)
        // still reaches the right and bottom edges of the area
        fun near(at: Float, size: Float, snap: (Float) -> Float): Float {
            val byStart = snap(at)
            val byEnd = snap(at + size) - size
            return (if (kotlin.math.abs(byEnd - at) < kotlin.math.abs(byStart - at)) byEnd else byStart).coerceIn(0f, (1f - size).coerceAtLeast(0f))
        }
        fun moved(r: FreeRect): FreeRect = r.copy(x = near(r.x, r.w, ::snapX), y = near(r.y, r.h, ::snapY))
        if (editing && cells != null) androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val line = C.Border.copy(alpha = 0.7f)
            for (k in 1 until cells[0]) drawLine(line, androidx.compose.ui.geometry.Offset(size.width * k / cells[0], 0f),
                androidx.compose.ui.geometry.Offset(size.width * k / cells[0], size.height), 1.5f)
            for (k in 1 until cells[1]) drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height * k / cells[1]),
                androidx.compose.ui.geometry.Offset(size.width, size.height * k / cells[1]), 1.5f)
        }
        tiles.forEach { t ->
            key(t.id) {
                val placed = t.free ?: FreeRect(0.35f, 0.3f, 0.3f, 0.4f)
                // the gestures live as long as the card; they must see where it is now, not where it was when they began
                val at by rememberUpdatedState(placed)
                // while a finger moves or sizes the card, only its outline follows (the card itself stays put, so a
                // heavy gauge or the map is not redrawn on every move); the card jumps there once, on release
                var ghost by remember(t.id) { mutableStateOf<FreeRect?>(null) }
                var sizing by remember(t.id) { mutableStateOf(false) }
                Box(
                    Modifier
                        .offset { IntOffset((placed.x * w).roundToInt(), (placed.y * h).roundToInt()) }
                        .size(with(density) { (placed.w * w).toDp() }, with(density) { (placed.h * h).toDp() })
                        .then(
                            if (!editing) longPress
                            else Modifier.pointerInput(t.id) {
                                detectDragGestures(
                                    onDragStart = { ghost = at; sizing = false },
                                    onDragEnd = { ghost?.let { edit.place(t.id, moved(it)) }; ghost = null },
                                    onDragCancel = { ghost = null },
                                ) { change, d ->
                                    change.consume()
                                    val g = ghost ?: return@detectDragGestures
                                    ghost = g.copy(x = (g.x + d.x / w).coerceIn(0f, 1f - g.w), y = (g.y + d.y / h).coerceIn(0f, 1f - g.h))
                                }
                            },
                        )
                        .graphicsLayer { alpha = if (ghost != null) 0.35f else 1f },
                ) {
                    // on a grid, cards touch edge to edge: a hair of space keeps them apart
                    card(t, Modifier.fillMaxSize().padding(if (cells != null) 3.dp else 0.dp))
                    if (editing) {
                        TileEditOverlay(edit, t, names[t.id]?.first ?: "", free = true)
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(34.dp)
                                .pointerInput(t.id) {
                                    detectDragGestures(
                                        onDragStart = { ghost = at; sizing = true },
                                        onDragEnd = { ghost?.let { edit.place(t.id, sized(it)) }; ghost = null },
                                        onDragCancel = { ghost = null },
                                    ) { change, d ->
                                        change.consume()
                                        val g = ghost ?: return@detectDragGestures
                                        ghost = g.copy(w = (g.w + d.x / w).coerceIn(minW, 1f - g.x), h = (g.h + d.y / h).coerceIn(minH, 1f - g.y))
                                    }
                                }
                                .clip(RoundedCornerShape(topStart = 10.dp, bottomEnd = 14.dp)).background(C.Purple),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.Scaling, "Размер", tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
                ghost?.let { g ->
                    // where it lands: the cells it will take, filled; the finger's own rectangle, a thin line
                    val land = if (sizing) sized(g) else moved(g)
                    Box(
                        Modifier.zIndex(5f)
                            .offset { IntOffset((land.x * w).roundToInt(), (land.y * h).roundToInt()) }
                            .size(with(density) { (land.w * w).toDp() }, with(density) { (land.h * h).toDp() })
                            .clip(RoundedCornerShape(12.dp)).background(C.Violet.copy(alpha = 0.18f))
                            .border(2.dp, C.Violet, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { T(names[t.id]?.first ?: "", 13.sp, C.Text, FontWeight.SemiBold, maxLines = 2) }
                    Box(
                        Modifier.zIndex(6f)
                            .offset { IntOffset((g.x * w).roundToInt(), (g.y * h).roundToInt()) }
                            .size(with(density) { (g.w * w).toDp() }, with(density) { (g.h * h).toDp() })
                            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
        if (tiles.isEmpty()) T(
            if (editing) "Добавьте виджеты кнопкой «Добавить виджет»" else "Виджетов нет: нажмите и удерживайте здесь, чтобы настроить",
            16.sp, C.Sub, modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun EditButton(
    icon: ImageVector, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, tint: Color = C.Text, label: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(if (size >= 48.dp) 14.dp else 9.dp)).background(C.CardHi)
            .border(1.dp, C.Border, RoundedCornerShape(if (size >= 48.dp) 14.dp else 9.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) tint else C.Border, modifier = Modifier.size(if (size >= 48.dp) 24.dp else 18.dp)) }
}

/** A line of the card's edit menu: icon and words, 56 dp tall. */
@Composable
private fun MenuAction(icon: ImageVector, text: String, tint: Color = C.Text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(min = 280.dp).heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        T(text, 17.sp, if (tint == C.Red) C.Red else C.Text, maxLines = 1)
    }
}

/** A line of the card's edit menu with a pair of 56 dp buttons (left/right, narrower/wider) and the [value] between. */
@Composable
private fun MenuStepper(
    label: String, value: String?, less: ImageVector, lessOn: Boolean, onLess: () -> Unit,
    more: ImageVector, moreOn: Boolean, onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().widthIn(min = 280.dp).padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 16.sp, C.Sub, maxLines = 1, modifier = Modifier.weight(1f).padding(end = 12.dp))
        EditButton(less, 56.dp, lessOn, onClick = onLess)
        if (value != null) T(value, 18.sp, weight = FontWeight.Bold, modifier = Modifier.widthIn(min = 36.dp).padding(horizontal = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        else Spacer(Modifier.width(10.dp))
        EditButton(more, 56.dp, moreOn, onClick = onMore)
    }
}

