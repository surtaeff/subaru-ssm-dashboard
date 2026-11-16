package kg.forester.sg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.*
import kg.forester.sg.data.I18n
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.yandex.YaApi
import kg.forester.sg.service.yandex.YaAuth
import kg.forester.sg.service.yandex.YaDevice
import kg.forester.sg.service.yandex.YaLibrary
import kg.forester.sg.service.yandex.YaMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Yandex Music: the own player with Yandex's tracks (service/yandex), ported from FreeLite ---------------------

/** Moods and characters of «Моя волна»: the seed values and their names. */
private val MOODS = listOf("" to "Любое", "active" to "Бодрое", "fun" to "Весёлое", "calm" to "Спокойное", "sad" to "Грустное")
private val DIVERSITY = listOf("" to "Любой", "favorite" to "Любимое", "discover" to "Незнакомое", "popular" to "Популярное")

/**
 * The left of «Яндекс»: the track of the own player when it is Yandex's, ♥ and 👎 beside the keys. Signed out it points
 * at the sign-in in the panel beside it; signed in with nothing playing, ▶ goes on with what played last, else starts «Моя волна».
 */
@Composable
internal fun YandexHero() {
    val ctx = LocalContext.current
    val auth by YaAuth.state.collectAsState()
    val st by LocalMusic.state.collectAsState()
    val ya by YaMusic.ui.collectAsState()
    val liked by YaLibrary.liked.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        YaAuth.token(ctx)
        YaAuth.refresh(ctx, false)
        YaLibrary.ensureLikes(ctx)
        while (true) { pos = LocalMusic.position(); delay(500) }
    }
    val on = st.yandex
    val t = if (on) YaMusic.trackOf(st.current) else null
    val isLiked = t != null && t.id in liked
    Hero(
        cover = { GlowCover(if (on) st.art else null, Lucide.Waves, on && st.playing, it) },
        onNext = { if (on) LocalMusic.next(ctx) }, onPrev = { if (on) LocalMusic.prev(ctx) },
        info = {
            when {
                !auth.signedIn -> TrackInfo("Яндекс Музыка", "Войдите в Яндекс",
                    if (YaAuth.codeLogin) "Справа код для входа с телефона, или войдите на этой магнитоле" else "Справа: вход через страницу Яндекса или токен")
                !on -> TrackInfo("Яндекс Музыка", if (ya.loading) "Загружаю Мою волну…" else "Моя волна",
                    if (ya.loading) "" else "Нажмите ▶ или выберите музыку в списке")
                else -> TrackInfo(if (ya.wave) "Моя волна" else I18n.raw(ya.from.ifBlank { "Яндекс Музыка" }), I18n.raw(st.title), I18n.raw(st.artist),
                    I18n.raw(st.album), st.nextTitle.let(I18n::raw))
            }
            val err = ya.error.ifEmpty { if (on) st.error else "" }.ifEmpty { if (!auth.signedIn) auth.problem else "" }
            when {
                err.isNotEmpty() -> { Spacer(Modifier.height(8.dp)); T(err, 14.sp, C.Orange) }
                on && ya.loading -> { Spacer(Modifier.height(8.dp)); T("Загружаю…", 14.sp, C.Sub) }
                on && ya.preview -> { Spacer(Modifier.height(8.dp)); T("Фрагмент 30 с: нужна подписка Плюс", 14.sp, C.Orange) }
            }
        },
        middle = { OwnMiddle(if (on) st else st.copy(playing = false, durMs = 0), if (on) pos else 0, if (on) { ms -> LocalMusic.seek(ctx, ms); pos = ms } else null) },
        controls = {
            Control(Lucide.ThumbsDown, 54.dp) { if (on) YaMusic.dislike(ctx) }
            Control(Lucide.SkipBack, 68.dp, onLong = { if (on) LocalMusic.seekBy(ctx, -10_000) }) { if (on) LocalMusic.prev(ctx) }
            Control(if (on && st.playing) Lucide.Pause else Lucide.Play, 96.dp, primary = true) {
                if (on) LocalMusic.toggle(ctx) else if (auth.signedIn) YaMusic.resume(ctx)
            }
            Control(Lucide.SkipForward, 68.dp, onLong = { if (on) LocalMusic.seekBy(ctx, 10_000) }) { if (on) LocalMusic.next(ctx) }
            Control(Lucide.Heart, 54.dp, active = isLiked) { if (on) YaMusic.toggleLike(ctx) }
        },
    )
}

/** What the list shows besides the sections: an opened playlist, album or artist. */
private class Level(val title: String, val load: suspend (Context) -> Result<List<YaApi.Track>>)

/** The right of «Яндекс»: signing in, or Моя волна, Мне нравится, Плейлисты, Поиск. */
@Composable
internal fun YandexPanel() {
    val ctx = LocalContext.current
    val auth by YaAuth.state.collectAsState()
    LaunchedEffect(Unit) { YaAuth.token(ctx) }
    if (!auth.signedIn) { SignInPanel(auth.problem); return }
    var section by remember { mutableStateOf(YaAuth.prefs(ctx).getString("section", "wave") ?: "wave") }
    var level by remember { mutableStateOf<Level?>(null) }
    var query by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(false) }
    PanelBox(Lucide.Waves, "Яндекс Музыка", trailing = {
        // a long name would push the title out: cut it
        val name = auth.account?.name.orEmpty().let { if (it.length > 16) it.take(15) + "…" else it }
        Pill(I18n.raw(name), Lucide.UserRound, height = 38.dp) { account = true }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("wave", "Волна", Lucide.Waves), Triple("likes", "Мне нравится", Lucide.Heart),
                Triple("lists", "Плейлисты", Lucide.ListMusic), Triple("search", "Поиск", Lucide.Search)).forEach { (id, name, icon) ->
                Pill(name, icon, section == id && level == null, 40.dp) {
                    section = id; level = null
                    YaAuth.prefs(ctx).edit().putString("section", id).apply()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val l = level
        if (l != null) {
            androidx.activity.compose.BackHandler { level = null }
            LevelList(l) { level = null }
        } else when (section) {
            "likes" -> LikesList()
            "lists" -> PlaylistsList { level = it }
            "search" -> SearchList(query, { query = it }) { level = it }
            else -> WaveCard()
        }
    }
    if (account) AccountDialog { account = false }
}

// --- the sections -------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.WaveCard() {
    val ctx = LocalContext.current
    val st by LocalMusic.state.collectAsState()
    val ya by YaMusic.ui.collectAsState()
    val p = remember { YaAuth.prefs(ctx) }
    var mood by remember { mutableStateOf(p.getString("mood", "") ?: "") }
    var div by remember { mutableStateOf(p.getString("diversity", "") ?: "") }
    val waveOn = st.yandex && ya.wave
    fun setting(key: String, v: String) {
        p.edit().putString(key, v).apply()
        if (key == "mood") mood = v else div = v
        // a wave that plays starts over with the new seeds
        if (waveOn) YaMusic.playWave(ctx)
    }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Violet.copy(alpha = 0.14f))
                .border(1.dp, C.Violet.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Lucide.Waves, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("Моя волна", 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
                T("Подстраивается под вас: лайки, пропуски, дизлайки", 12.sp, C.Sub, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (waveOn && st.playing) "Пауза" else "Слушать", if (waveOn && st.playing) Lucide.Pause else Lucide.Play, true) {
                if (waveOn) LocalMusic.toggle(ctx) else YaMusic.playWave(ctx)
            }
        }
        Spacer(Modifier.height(14.dp))
        T("Настроение", 13.sp, C.Sub)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MOODS.forEach { (v, name) -> Pill(name, if (v.isEmpty()) null else Lucide.Sparkles, mood == v, 38.dp) { setting("mood", v) } }
        }
        Spacer(Modifier.height(12.dp))
        T("Характер", 13.sp, C.Sub)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DIVERSITY.forEach { (v, name) -> Pill(name, if (v.isEmpty()) null else Lucide.Disc3, div == v, 38.dp) { setting("diversity", v) } }
        }
        if (waveOn) {
            val next = st.queue.drop(st.index + 1).take(5).mapNotNull(YaMusic::trackOf)
            if (next.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                T("Дальше в волне", 13.sp, C.Sub)
                Spacer(Modifier.height(4.dp))
                next.forEach { t -> TrackRow(t, false, false) { val i = st.queue.indexOf(YaMusic.PREFIX + t.key); if (i >= 0) LocalMusic.playerOrNull()?.seekTo(i, 0) } }
            }
        }
    }
}

/** Tracks loaded by [load]: a «Слушать» and ⤨ above them, a tap plays the list from that one. */
@Composable
private fun ColumnScope.TrackList(
    key: Any, from: String, load: suspend (Context) -> Result<List<YaApi.Track>>, more: (suspend (Context, Int) -> Result<List<YaApi.Track>>)? = null,
    head: @Composable ColumnScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by LocalMusic.state.collectAsState()
    var tracks by remember(key) { mutableStateOf<List<YaApi.Track>?>(null) }
    var error by remember(key) { mutableStateOf("") }
    var end by remember(key) { mutableStateOf(more == null) }
    var loadingMore by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val r = load(ctx)
        tracks = r.getOrNull()
        error = if (r.isFailure) YaApi.message(r.exceptionOrNull()) else ""
        if ((tracks?.size ?: 0) < 100) end = true
    }
    val l = tracks
    val top: @Composable ColumnScope.() -> Unit = {
        head()
        if (!l.isNullOrEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Слушать", Lucide.Play, height = 38.dp) { YaMusic.playQueue(ctx, l, 0, from) }
                Pill("", Lucide.Shuffle, height = 38.dp) { YaMusic.playQueue(ctx, l, 0, from, shuffle = true) }
                Spacer(Modifier.width(6.dp))
                T(if (end) "Треков: ${l.size}" else "Треков: ${l.size}+", 13.sp, C.Sub)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    when {
        l == null && error.isEmpty() -> { top(); Centered("Загружаю…") }
        l == null -> { top(); Centered(error) }
        l.isEmpty() -> { top(); Centered("Здесь пока пусто") }
        else -> ListWithHead(top) {
            items(l.size, key = { "y$it" + l[it].key }) { i ->
                val t = l[i]
                val cur = st.current == YaMusic.PREFIX + t.key
                TrackRow(t, cur, cur && st.playing) { if (cur) LocalMusic.toggle(ctx) else YaMusic.playQueue(ctx, l, i, from) }
            }
            if (!end && more != null) item(key = "more") {
                ListRow(if (loadingMore) "Загружаю…" else "Ещё", lead = { IconTile(Lucide.ListMusic) }) {
                    if (loadingMore) return@ListRow
                    loadingMore = true
                    scope.launch {
                        val r = more(ctx, l.size)
                        loadingMore = false
                        val add = r.getOrNull()
                        if (add != null) { tracks = l + add; if (add.size < 100) end = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Centered(text: String) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { T(text, 14.sp, C.Sub, textAlign = TextAlign.Center) }
}

@Composable
private fun ColumnScope.LikesList() {
    TrackList("likes", "Мне нравится", { YaLibrary.likedTracks(it, 0) }, { c, from -> YaLibrary.likedTracks(c, from) })
}

@Composable
private fun ColumnScope.LevelList(l: Level, onBack: () -> Unit) {
    TrackList(l, l.title, l.load, head = {
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(C.shade(0.22f)).clickable(onClick = onBack).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.ArrowLeft, null, tint = C.Violet, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            T(I18n.raw(l.title), 14.sp, C.Text, FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
    })
}

private fun playlistLevel(p: YaApi.Playlist) = Level(p.title) { c -> YaLibrary.run(c) { YaApi.playlistTracks(it, p.owner, p.kind) } }
private fun albumLevel(a: YaApi.Album) = Level(a.title) { c -> YaLibrary.run(c) { YaApi.albumTracks(it, a.id) } }
private fun artistLevel(a: YaApi.Artist) = Level(a.name) { c -> YaLibrary.run(c) { YaApi.artistTracks(it, a.id) } }

@Composable
private fun ColumnScope.PlaylistsList(open: (Level) -> Unit) {
    val ctx = LocalContext.current
    var lists by remember { mutableStateOf<Pair<List<YaApi.Playlist>, List<YaApi.Playlist>>?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val r = YaLibrary.playlists(ctx)
        lists = r.getOrNull()
        error = if (r.isFailure) YaApi.message(r.exceptionOrNull()) else ""
    }
    val l = lists
    when {
        l == null -> Centered(error.ifEmpty { "Загружаю…" })
        l.first.isEmpty() && l.second.isEmpty() -> Centered("Плейлистов нет")
        else -> ListWithHead({}) {
            fun LazyListScope.group(title: String, ps: List<YaApi.Playlist>) {
                if (ps.isEmpty()) return
                item(key = "h$title") { T(title, 13.sp, C.Sub, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                items(ps, key = { "p" + title + it.owner + ":" + it.kind }) { p ->
                    CoverRow(I18n.raw(p.title), if (p.count > 0) "Треков: ${p.count}" else "", p.cover, Lucide.ListMusic) { open(playlistLevel(p)) }
                }
            }
            group("Для вас", l.first)
            group("Мои плейлисты", l.second)
        }
    }
}

@Composable
private fun ColumnScope.SearchList(query: String, onQuery: (String) -> Unit, open: (Level) -> Unit) {
    val ctx = LocalContext.current
    val st by LocalMusic.state.collectAsState()
    var found by remember { mutableStateOf<YaApi.Search?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        found = null; error = ""
        if (query.trim().length < 2) return@LaunchedEffect
        delay(600) // typing: wait for a pause
        val r = YaLibrary.run(ctx) { YaApi.search(it, query.trim()) }
        found = r.getOrNull()
        error = if (r.isFailure) YaApi.message(r.exceptionOrNull()) else ""
    }
    SearchLine(query, "Песня, исполнитель, альбом") { onQuery(it) }
    Spacer(Modifier.height(8.dp))
    val f = found
    when {
        query.trim().length < 2 -> Centered("Найдётся всё, что есть в Яндекс Музыке")
        f == null -> Centered(error.ifEmpty { "Ищу…" })
        f.tracks.isEmpty() && f.artists.isEmpty() && f.albums.isEmpty() && f.playlists.isEmpty() -> Centered("Ничего не нашлось")
        else -> ListWithHead({}) {
            if (f.artists.isNotEmpty()) {
                item(key = "ha") { T("Исполнители", 13.sp, C.Sub, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                items(f.artists.take(3), key = { "a" + it.id }) { a -> CoverRow(I18n.raw(a.name), "", a.cover, Lucide.MicVocal) { open(artistLevel(a)) } }
            }
            if (f.tracks.isNotEmpty()) {
                item(key = "ht") { T("Треки", 13.sp, C.Sub, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                items(f.tracks.size, key = { "t" + f.tracks[it].key }) { i ->
                    val t = f.tracks[i]
                    val cur = st.current == YaMusic.PREFIX + t.key
                    TrackRow(t, cur, cur && st.playing) { if (cur) LocalMusic.toggle(ctx) else YaMusic.playQueue(ctx, f.tracks, i, query.trim()) }
                }
            }
            if (f.albums.isNotEmpty()) {
                item(key = "hl") { T("Альбомы", 13.sp, C.Sub, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                items(f.albums, key = { "l" + it.id }) { a ->
                    CoverRow(I18n.raw(a.title), I18n.raw(listOf(a.artists, if (a.year > 0) a.year.toString() else "").filter { it.isNotBlank() }.joinToString(" · ")), a.cover, Lucide.Disc3) { open(albumLevel(a)) }
                }
            }
            if (f.playlists.isNotEmpty()) {
                item(key = "hp") { T("Плейлисты", 13.sp, C.Sub, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                items(f.playlists, key = { "p" + it.owner + ":" + it.kind }) { p -> CoverRow(I18n.raw(p.title), if (p.count > 0) "Треков: ${p.count}" else "", p.cover, Lucide.ListMusic) { open(playlistLevel(p)) } }
            }
        }
    }
}

// --- rows -------------------------------------------------------------------------------------------------------

/** A cover from Yandex (its small size), the [fallback] icon until it has come or when there is none. */
@Composable
private fun YaCover(uri: String, fallback: ImageVector) {
    val bmp by produceState<Bitmap?>(YaLibrary.cachedCover(uri, 100), uri) { if (value == null && uri.isNotEmpty()) value = YaLibrary.cover(uri, 100) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    else IconTile(fallback)
}

@Composable
private fun TrackRow(t: YaApi.Track, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    ListRow(I18n.raw(t.fullTitle), I18n.raw(t.artists), current, lead = { if (current) Bars(playing) else YaCover(t.cover, Lucide.Music) },
        trail = { if (t.durationMs > 0) T(time(t.durationMs), 12.sp, C.Sub.copy(alpha = 0.7f)) }, onClick = onClick)
}

@Composable
private fun CoverRow(title: String, sub: String, cover: String, icon: ImageVector, onClick: () -> Unit) {
    ListRow(title, sub, lead = { YaCover(cover, icon) }, trail = { Icon(Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(18.dp)) }, onClick = onClick)
}

// --- signing in: right in the panel, beside the player, no window over it ------------------------------------

/** What the signed-out panel shows: the ways in (the phone code first where the build has it), Yandex's page, a token. */
private enum class SignIn { CHOICES, WEB, PASTE }

@Composable
private fun SignInPanel(problem: String) {
    var mode by remember { mutableStateOf(SignIn.CHOICES) }
    var said by remember { mutableStateOf("") }
    // a failure comes back to the ways in with its reason; a success replaces the whole panel (YaAuth.state)
    val done: (Boolean, String) -> Unit = { ok, msg -> if (!ok) { said = msg; mode = SignIn.CHOICES } }
    if (mode != SignIn.CHOICES) androidx.activity.compose.BackHandler { mode = SignIn.CHOICES }
    PanelBox(Lucide.Waves, "Яндекс Музыка", trailing = {
        if (mode != SignIn.CHOICES) Pill("Назад", Lucide.ArrowLeft, height = 38.dp) { mode = SignIn.CHOICES }
    }) {
        when (mode) {
            SignIn.WEB -> WebLogin(done)
            SignIn.PASTE -> PasteToken(done)
            SignIn.CHOICES -> Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                val about = "Моя волна, «Мне нравится», плейлисты и поиск играют прямо здесь: через эквалайзер, кнопки руля и мини-плеер. " +
                    "Нужен аккаунт Яндекса с подпиской Плюс, без неё только 30-секундные фрагменты."
                if (YaAuth.codeLogin) {
                    DeviceLogin()
                    Spacer(Modifier.height(12.dp))
                    T("Или войдите на этой магнитоле:", 13.sp, C.Sub)
                } else T(about, 14.sp, C.Sub)
                Spacer(Modifier.height(10.dp))
                Pill("Войти через страницу Яндекса", Lucide.LogIn, !YaAuth.codeLogin) { said = ""; mode = SignIn.WEB }
                Spacer(Modifier.height(8.dp))
                Pill("Вставить токен", Lucide.KeyRound) { said = ""; mode = SignIn.PASTE }
                val note = said.ifEmpty { problem }
                if (note.isNotEmpty()) { Spacer(Modifier.height(10.dp)); T(note, 13.sp, C.Orange) }
                Spacer(Modifier.height(14.dp))
                if (YaAuth.codeLogin) { T(about, 12.sp, C.Sub); Spacer(Modifier.height(6.dp)) }
                T("Пароль вводится на странице Яндекса, приложение его не видит. Токен хранится только на этой магнитоле, " +
                    "зашифрованный ключом Android Keystore. Доступ к Яндекс Музыке неофициальный, для личного использования.", 12.sp, C.Sub)
            }
        }
    }
}

/**
 * The device flow (ya.ru/device) in a card of the panel: the code big, a QR of the address, the time it is still good
 * for; checked every few seconds until the phone confirms. An expired or refused code asks for a new one by a tap.
 * The steps are [YaDevice]'s, tested on their own.
 */
@Composable
private fun DeviceLogin() {
    val ctx = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var flow by remember { mutableStateOf<YaDevice.Flow>(YaDevice.Flow.Requesting) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attempt) {
        flow = YaDevice.Flow.Requesting
        var f = YaDevice.start(withContext(Dispatchers.IO) { runCatching { YaAuth.requestCode() }.getOrNull() })
        flow = f
        while (f is YaDevice.Flow.Waiting) {
            delay(f.intervalMs)
            val w = f
            f = YaDevice.next(w, withContext(Dispatchers.IO) { runCatching { YaAuth.pollCode(w.code) }.getOrNull() }, System.currentTimeMillis())
            flow = f
        }
        val c = f
        if (c is YaDevice.Flow.Confirming) YaAuth.signIn(ctx, c.token, c.expiresInSec) { ok, msg ->
            if (!ok) flow = YaDevice.Flow.Failed(YaDevice.Reason.SIGN_IN, msg)
        }
    }
    val waiting = flow is YaDevice.Flow.Waiting
    LaunchedEffect(waiting) { while (waiting) { now = System.currentTimeMillis(); delay(1000) } }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(C.Violet.copy(alpha = 0.12f)).border(1.dp, C.Violet.copy(alpha = 0.35f), shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Lucide.Smartphone)
            Spacer(Modifier.width(12.dp))
            T("Вход с телефона", 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        T("На телефоне, где вы вошли в Яндекс, откройте ya.ru/device и введите этот код", 14.sp, C.Sub)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                when (val f = flow) {
                    YaDevice.Flow.Requesting -> {
                        T("· · ·", 44.sp, C.Sub, FontWeight.Bold)
                        T("Получаю код…", 14.sp, C.Sub, textAlign = TextAlign.Center)
                    }
                    is YaDevice.Flow.Waiting -> {
                        T(I18n.raw(f.code.userCode), 44.sp, C.Violet, FontWeight.Bold, maxLines = 1)
                        T("Жду подтверждения, код действует ещё ${YaDevice.clock(YaDevice.secondsLeft(f.code, now))}", 13.sp, C.Sub, textAlign = TextAlign.Center)
                    }
                    is YaDevice.Flow.Confirming -> {
                        Spacer(Modifier.height(8.dp))
                        T("Проверяю вход…", 16.sp, C.Text, textAlign = TextAlign.Center)
                    }
                    YaDevice.Flow.Expired -> {
                        T("Код истёк", 18.sp, C.Orange, FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Pill("Получить новый код", Lucide.RefreshCw, true) { attempt++ }
                    }
                    is YaDevice.Flow.Failed -> {
                        T(when (f.reason) {
                            YaDevice.Reason.UNAVAILABLE -> "Сервис недоступен, попробуйте позже"
                            YaDevice.Reason.DENIED -> "Вход отклонён на телефоне"
                            YaDevice.Reason.REJECTED -> "Яндекс не подтвердил вход"
                            YaDevice.Reason.SIGN_IN -> f.detail
                        }, 15.sp, C.Orange, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Pill("Получить новый код", Lucide.RefreshCw, true) { attempt++ }
                    }
                }
            }
            val w = flow as? YaDevice.Flow.Waiting
            if (w != null) {
                // the address as a QR: the phone's camera opens the page, the code is typed there
                val qr = remember(w.code.url) { qrBitmap(w.code.url, 256) }
                if (qr != null) {
                    Spacer(Modifier.width(12.dp))
                    Image(qr.asImageBitmap(), kg.forester.sg.data.tr("QR-код страницы входа"), Modifier.size(112.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color.White).padding(6.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                }
            }
        }
    }
}

/** Yandex's sign-in page in the panel; only the redirect with the token is read, nothing on the page. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ColumnScope.WebLogin(done: (Boolean, String) -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Открываю страницу входа Яндекса…") }
    var finished by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            view?.let { runCatching { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() } }
            YaAuth.clearCookies() // the token is all that is kept
        }
    }
    T(status, 14.sp, C.Sub)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            val w = runCatching { WebView(c) }.getOrNull()
            if (w == null) {
                status = "На этой магнитоле нет системного WebView: вставьте токен"
                return@AndroidView android.view.View(c)
            }
            view = w
            w.settings.apply {
                javaScriptEnabled = true // Yandex's page needs it; no JavaScript interface is added
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            /** True: the link is ours to handle (the token, an error, a page off Yandex). Never logged. */
            fun check(url: String?): Boolean {
                if (finished || url == null) return finished
                val r = YaApi.fromRedirect(url)
                if (r != null) {
                    finished = true
                    val (tok, exp, _) = r
                    if (tok == null) { done(false, "Вход отменён"); return true }
                    status = "Проверяю вход…"
                    YaAuth.signIn(ctx, tok, exp?.toLongOrNull() ?: 0, done)
                    return true
                }
                return !YaAuth.yandexPage(url)
            }
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = check(r.url.toString())
                @Deprecated("for Android 6")
                override fun shouldOverrideUrlLoading(v: WebView, url: String) = check(url)
                override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { if (check(url)) v.stopLoading() }
                override fun onPageFinished(v: WebView, url: String?) {
                    if (finished) return
                    check(url)
                    if (!finished) status = "Введите логин, пароль и код на странице Яндекса. Приложение не видит и не хранит пароль."
                }
                override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) { check(url) }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame && !finished) status = "Страница Яндекса не открылась: проверьте интернет и попробуйте позже"
                }
            }
            w.loadUrl(YaAuth.AUTHORIZE)
            w
        })
    }
}

/** «Вставить токен» in the panel: for those who have one. */
@Composable
private fun ColumnScope.PasteToken(done: (Boolean, String) -> Unit) {
    val ctx = LocalContext.current
    var tok by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        T("Токен Яндекс Музыки (обычно начинается с «y0_»). Он будет проверен и сохранён зашифрованным; никуда, кроме Яндекса, не отправляется.", 14.sp, C.Sub)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = tok, onValueChange = { tok = it.trim() }, singleLine = true, label = { T("Токен", 13.sp, C.Sub) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border),
            modifier = Modifier.fillMaxWidth(),
        )
        if (status.isNotEmpty()) { Spacer(Modifier.height(8.dp)); T(status, 13.sp, C.Orange) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Из буфера", Lucide.ClipboardPaste) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim() }.getOrNull()?.let { tok = it }
            }
            Pill("Войти", Lucide.LogIn, true) {
                status = "Проверяю…"
                // a wrong token stays here with its reason, to fix it; the right one replaces the panel
                YaAuth.signIn(ctx, tok, 0) { ok, msg -> if (!ok) status = msg }
            }
        }
    }
}

/** A window of the sign-in: its title, ✕, and [content] in a fixed frame. */
@Composable
private fun LoginFrame(title: String, icon: ImageVector, onClose: () -> Unit, tall: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val screen = canvasScreen()
    CarDialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(minOf(if (tall) 900 else 620, screen.screenWidthDp - 32).dp)
                .then(if (tall) Modifier.height((screen.screenHeightDp * 0.92f).dp) else Modifier)
                .clip(RoundedCornerShape(16.dp)).background(C.Sheet).border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon)
                Spacer(Modifier.width(12.dp))
                T(title, 19.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                IconTap(Lucide.X, "Закрыть", onClose)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** The account: the subscription, «Обновить», «Выйти». */
@Composable
private fun AccountDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val auth by YaAuth.state.collectAsState()
    val a = auth.account
    var confirm by remember { mutableStateOf(false) }
    LoginFrame("Яндекс Музыка", Lucide.UserRound, onClose, tall = false) {
        T(I18n.raw(a?.name ?: ""), 20.sp, weight = FontWeight.Bold)
        if (a != null && a.login.isNotEmpty() && a.login != a.name) T(I18n.raw(a.login), 14.sp, C.Sub)
        Spacer(Modifier.height(10.dp))
        T(YaAuth.plusText(a), 15.sp, if (a?.plus == true) C.Green else C.Orange, FontWeight.Medium)
        if (auth.expiresAt > 0) {
            Spacer(Modifier.height(4.dp))
            T("Вход действует до ${java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.ROOT).format(java.util.Date(auth.expiresAt))}", 13.sp, C.Sub)
        }
        if (auth.problem.isNotEmpty()) { Spacer(Modifier.height(4.dp)); T(auth.problem, 13.sp, C.Orange) }
        Spacer(Modifier.height(10.dp))
        T("Токен хранится только на этой магнитоле, зашифрованный ключом Android Keystore. Неофициальный доступ к API: для личного использования.", 13.sp, C.Sub)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Обновить", Lucide.RefreshCw) { YaAuth.refresh(ctx, true); onClose() }
            Pill("Выйти", Lucide.LogOut) { confirm = true }
        }
    }
    if (confirm) ConfirmDialog("Выйти из Яндекса?", "Токен, сохранённая очередь и данные аккаунта будут удалены с магнитолы.", "Выйти",
        onConfirm = { YaAuth.logout(ctx); onClose() }, onDismiss = { confirm = false })
}
