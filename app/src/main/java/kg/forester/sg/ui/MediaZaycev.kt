package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.composables.icons.lucide.*
import kg.forester.sg.data.I18n
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.Voice
import kg.forester.sg.service.zaycev.ZaycevApi
import kg.forester.sg.service.zaycev.ZaycevMusic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- zaycev.net: free tracks in the own player, no account (service/zaycev) -----------------------------------------

/** The chart's periods: the site's value and its name. */
private val PERIODS = listOf("day" to "За день", "week" to "За неделю", "month" to "За месяц")

/** Where a queue came from, when it is one of the app's own names (shown translated); a search's words stay as typed. */
private val SECTIONS = setOf("Популярное", "Новинки")

private fun prefs(ctx: Context) = ctx.getSharedPreferences("zaycev", Context.MODE_PRIVATE)

/**
 * The left of «Зайцев»: the track of the own player when it is from zaycev.net, with shuffle and repeat like files.
 * With nothing of it playing, ▶ goes on with what played last, else starts today's chart.
 */
@Composable
internal fun ZaycevHero() {
    val ctx = LocalContext.current
    val st by LocalMusic.state.collectAsState()
    val zy by ZaycevMusic.ui.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = LocalMusic.position(); delay(500) } }
    val on = st.zaycev
    Hero(
        cover = { GlowCover(if (on) st.art else null, Lucide.Rabbit, on && st.playing, it) },
        onNext = { if (on) LocalMusic.next(ctx) }, onPrev = { if (on) LocalMusic.prev(ctx) },
        info = {
            if (!on) TrackInfo("Зайцев.нет", if (zy.loading) "Загружаю…" else "Выберите музыку в списке",
                if (zy.loading) "" else "Популярное, новинки и поиск, без регистрации")
            else {
                val from = zy.from.ifBlank { "Зайцев.нет" }
                TrackInfo(if (from in SECTIONS || from == "Зайцев.нет") from else I18n.raw(from), I18n.raw(st.title), I18n.raw(st.artist),
                    next = if (st.repeat == Player.REPEAT_MODE_ONE) "" else I18n.raw(st.nextTitle))
                if (st.queue.size > 1) { Spacer(Modifier.height(10.dp)); T("${st.index + 1} из ${st.queue.size}", 13.sp, C.Sub) }
            }
            val err = zy.error.ifEmpty { if (on) st.error else "" }
            if (err.isNotEmpty()) { Spacer(Modifier.height(8.dp)); T(err, 14.sp, C.Orange) }
            else if (on && zy.loading) { Spacer(Modifier.height(8.dp)); T("Загружаю…", 14.sp, C.Sub) }
        },
        middle = { OwnMiddle(if (on) st else st.copy(playing = false, durMs = 0), if (on) pos else 0, if (on) { ms -> LocalMusic.seek(ctx, ms); pos = ms } else null) },
        controls = {
            Control(Lucide.Shuffle, 54.dp, active = on && st.shuffle) { if (on) LocalMusic.setShuffle(ctx, !st.shuffle) }
            Control(Lucide.SkipBack, 68.dp, onLong = { if (on) LocalMusic.seekBy(ctx, -10_000) }) { if (on) LocalMusic.prev(ctx) }
            Control(if (on && st.playing) Lucide.Pause else Lucide.Play, 96.dp, primary = true) {
                if (on) LocalMusic.toggle(ctx) else ZaycevMusic.resume(ctx)
            }
            Control(Lucide.SkipForward, 68.dp, onLong = { if (on) LocalMusic.seekBy(ctx, 10_000) }) { if (on) LocalMusic.next(ctx) }
            Control(if (st.repeat == Player.REPEAT_MODE_ONE) Lucide.Repeat1 else Lucide.Repeat, 54.dp,
                active = on && st.repeat != Player.REPEAT_MODE_OFF) { if (on) LocalMusic.cycleRepeat(ctx) }
        },
    )
}

/** The right of «Зайцев»: the chart (by day, week or month), the new tracks, the search. */
@Composable
internal fun ZaycevPanel() {
    val ctx = LocalContext.current
    val p = remember { prefs(ctx) }
    var section by remember { mutableStateOf(p.getString("section", "top") ?: "top") }
    var period by remember { mutableStateOf(p.getString("period", "day") ?: "day") }
    var query by remember { mutableStateOf("") }
    PanelBox(Lucide.Rabbit, "Зайцев.нет") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("top", "Популярное", Lucide.Flame), Triple("new", "Новинки", Lucide.Sparkles), Triple("search", "Поиск", Lucide.Search))
                .forEach { (id, name, icon) ->
                    Pill(name, icon, section == id, 40.dp) { section = id; p.edit().putString("section", id).apply() }
                }
        }
        Spacer(Modifier.height(8.dp))
        when (section) {
            "new" -> ZyTracks("new", "Новинки", { ZaycevApi.newest(it) })
            "search" -> ZySearch(query) { query = it }
            else -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PERIODS.forEach { (v, name) ->
                        Pill(name, null, period == v, 38.dp) { period = v; p.edit().putString("period", v).apply() }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ZyTracks("top:$period", "Популярное", { ZaycevApi.top(period, it) })
            }
        }
    }
}

@Composable
private fun ColumnScope.ZySearch(query: String, onQuery: (String) -> Unit) {
    // typing: the list is asked for after a pause, not for every letter
    var asked by remember { mutableStateOf(query.trim()) }
    LaunchedEffect(query) {
        if (query.trim().length >= 2) delay(600)
        asked = query.trim()
    }
    SearchLine(query, "Песня или исполнитель") { onQuery(it) }
    Spacer(Modifier.height(8.dp))
    if (asked.length < 2) Note("Найдётся всё, что есть на zaycev.net")
    else ZyTracks("q:$asked", asked, { ZaycevApi.search(asked, it) }, empty = "Ничего не нашлось")
}

/**
 * A list of zaycev.net, page by page ([load] gets the page number): «Слушать» and ⤨ above it, a tap plays the list from
 * that track, «Ещё» at the end while the site has more. The blocked ones are greyed and left out of the queue.
 */
@Composable
private fun ColumnScope.ZyTracks(key: String, from: String, load: (Int) -> ZaycevApi.Page, empty: String = "Здесь пока пусто") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by LocalMusic.state.collectAsState()
    var tracks by remember(key) { mutableStateOf<List<ZaycevApi.Track>?>(null) }
    var error by remember(key) { mutableStateOf("") }
    var page by remember(key) { mutableIntStateOf(1) }
    var more by remember(key) { mutableStateOf(false) }
    var loadingMore by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        error = ""
        val r = ZaycevMusic.page { load(1) }
        val pg = r.getOrNull()
        // a search that found nothing gets the site's popular tracks instead: that's not what was asked for
        tracks = if (pg == null) null else if (pg.fallback) emptyList() else pg.tracks
        more = pg != null && !pg.fallback && pg.more
        page = 1
        error = if (r.isFailure) ZaycevApi.message(r.exceptionOrNull()) else ""
    }
    val l = tracks
    val top: @Composable ColumnScope.() -> Unit = {
        if (!l.isNullOrEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Слушать", Lucide.Play, height = 38.dp) { ZaycevMusic.playQueue(ctx, l, 0, from) }
                Pill("", Lucide.Shuffle, height = 38.dp) { ZaycevMusic.playQueue(ctx, l, 0, from, shuffle = true) }
                Spacer(Modifier.width(6.dp))
                T(if (more) "Треков: ${l.size}+" else "Треков: ${l.size}", 13.sp, C.Sub)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    when {
        l == null && error.isEmpty() -> Note("Загружаю…")
        l == null -> {
            Note(error)
            Pill("Повторить", Lucide.RefreshCw, height = 40.dp) { attempt++ }
        }
        l.isEmpty() -> Note(empty)
        else -> ListWithHead(top) {
            items(l.size, key = { "z" + l[it].key }) { i ->
                val t = l[i]
                val cur = st.current == ZaycevMusic.PREFIX + t.key
                ZyRow(t, cur, cur && st.playing) {
                    when {
                        cur -> LocalMusic.toggle(ctx)
                        !t.playable -> Voice.toast.value = SystemClock.elapsedRealtime() to ZaycevApi.message(ZaycevApi.Err(ZaycevApi.BLOCKED, "blocked"))
                        else -> ZaycevMusic.playQueue(ctx, l, i, from)
                    }
                }
            }
            if (more) item(key = "more") {
                ListRow(if (loadingMore) "Загружаю…" else "Ещё", lead = { IconTile(Lucide.ListMusic) }) {
                    if (loadingMore) return@ListRow
                    loadingMore = true
                    scope.launch {
                        val next = page + 1
                        val r = ZaycevMusic.page { load(next) }
                        loadingMore = false
                        val add = r.getOrNull() ?: return@launch
                        // the chart moves while it is read: a track already shown isn't shown twice
                        val have = l.mapTo(HashSet()) { it.id }
                        tracks = l + add.tracks.filter { have.add(it.id) }
                        page = next
                        more = add.more
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Note(text: String) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { T(text, 14.sp, C.Sub, textAlign = TextAlign.Center) }
}

/** The list's cover (zaycev.net's small one), a note until it has come or when there is none. */
@Composable
private fun ZyCover(url: String) {
    val bmp by produceState<Bitmap?>(ZaycevMusic.cachedCover(url), url) { if (value == null && url.isNotEmpty()) value = ZaycevMusic.cover(url) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    else IconTile(Lucide.Music)
}

@Composable
private fun ZyRow(t: ZaycevApi.Track, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    // a blocked one says so under its name, the artist goes up beside the title
    val title = if (t.playable || t.artist.isBlank()) t.title else "${t.artist} – ${t.title}"
    Box(if (t.playable) Modifier else Modifier.alpha(0.45f)) {
        ListRow(I18n.raw(title), if (t.playable) I18n.raw(t.artist) else "Недоступен для прослушивания", current, lead = { if (current) Bars(playing) else ZyCover(t.cover) },
            trail = { if (t.durationMs > 0) T(time(t.durationMs), 12.sp, C.Sub.copy(alpha = 0.7f)) }, onClick = onClick)
    }
}
