package kg.forester.sg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Clapperboard
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SquarePlay
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Youtube
import kg.forester.sg.video.AltRow
import kg.forester.sg.video.AltStore
import kg.forester.sg.video.AltVideo
import kg.forester.sg.video.Rutube
import kg.forester.sg.video.YouTube
import kg.forester.sg.video.VideoApp
import kg.forester.sg.video.VideoApps
import kg.forester.sg.video.VideoCard
import kg.forester.sg.video.VideoCatalog
import kg.forester.sg.video.VideoDetails
import kg.forester.sg.video.VideoEpisode
import kg.forester.sg.video.VideoPolicy
import kg.forester.sg.video.VideoRegion
import kg.forester.sg.video.VideoRow
import kg.forester.sg.video.VideoSource
import kg.forester.sg.video.VideoStore
import kg.forester.sg.video.VideoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Видео». In Kyrgyzstan: ts.kg and oc.kg (catalog, search, the title page with its seasons and episodes, the player
 * with resume and the next episode), ported from FreeLite. Elsewhere those sites refuse (403), so the screen offers
 * legal services instead: Rutube's search with its own player, and the installed apps of the online cinemas.
 */
@Composable
fun VideoScreen() {
    val ctx = LocalContext.current
    remember { VideoRegion.init(ctx); true }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { VideoStore.load(ctx) }
        VideoRegion.refresh(ctx)
    }
    val kg by VideoRegion.inKyrgyzstan.collectAsState()
    var regionDialog by remember { mutableStateOf(false) }
    if (regionDialog) VideoRegionDialog { regionDialog = false }
    val openRegion = { regionDialog = true }
    when (kg) {
        true -> KgVideo(openRegion)
        false -> OtherVideo(openRegion)
        null -> RegionUnknown(openRegion)
    }
}

/** The region isn't known yet: being checked, or there was no internet for the check. */
@Composable
private fun RegionUnknown(openRegion: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            CircularProgressIndicator(color = C.Violet)
            Spacer(Modifier.height(16.dp))
            T("Определяем, откуда смотреть видео…", 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            T("Если долго нет ответа, проверьте интернет или выберите регион сами.", 14.sp, C.Sub, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Проверить снова", false, { scope.launch { VideoRegion.refresh(ctx, force = true) } }, Lucide.RefreshCw)
                Chip("Выбрать регион", false, openRegion, Lucide.MapPin)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Kyrgyzstan: ts.kg, oc.kg

@Composable
private fun KgVideo(openRegion: () -> Unit) {
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf<String?>(null) }
    val key = openKey
    if (key != null) {
        androidx.activity.compose.BackHandler { openKey = null }
        TitlePage(key, onBack = { openKey = null }, onOpen = { openKey = it })
        return
    }
    androidx.activity.compose.BackHandler(enabled = searched != null) { searched = null }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(query, "Название фильма или сериала", Modifier.weight(1f), onSearch = { if (query.trim().length >= 2) searched = query.trim() }) { query = it }
            Chip("Найти", false, { if (query.trim().length >= 2) searched = query.trim() }, Lucide.Search)
            if (searched != null) Chip("Главная", false, { searched = null }, Lucide.Clapperboard)
            Chip("Кыргызстан", false, openRegion, Lucide.MapPin)
        }
        Spacer(Modifier.height(10.dp))
        val s = searched
        if (s != null) KgSearch(s) { openKey = it } else KgHome { openKey = it }
    }
}

@Composable
private fun SearchField(value: String, hint: String, modifier: Modifier, onSearch: (() -> Unit)? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { T(hint, 14.sp, C.Sub, maxLines = 1) },
        leadingIcon = { Icon(Lucide.Search, null, tint = C.Sub, modifier = Modifier.size(20.dp)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = modifier,
    )
}

@Composable
private fun KgHome(open: (String) -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val home by produceState<Map<VideoSource, VideoCatalog.Result<List<VideoRow>>>?>(null, reload) {
        value = VideoCatalog.home(ctx, force = reload > 0)
    }
    val version by VideoStore.version.collectAsState()
    val recent = remember(version) { VideoStore.recent(20) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (recent.isNotEmpty()) item("recent") {
            RowTitle(Lucide.History, "Продолжить просмотр")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent, key = { it.key }) { e ->
                    Box {
                        PosterCard(e.title ?: "", e.label, e.poster, parseVideoSource(e.key), progress = if (e.dur > 0) e.pos.toFloat() / e.dur else null) { open(e.key) }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp).clip(CircleShape).background(Color(0xAA000000))
                                .tapClickable { VideoStore.dismiss(ctx, e.key) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.X, "Убрать из «Продолжить»", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
        val h = home
        if (h == null) item("loading") { Loading() }
        else VideoSource.entries.forEach { src ->
            val r = h[src]
            val rows = r?.value
            if (rows == null) item("err-${src.key}") {
                ErrorLine("${src.label}: " + VideoCatalog.message(r?.error)) { reload++ }
            } else rows.filter { it.items.isNotEmpty() }.forEach { row ->
                item(row.id) {
                    RowTitle(Lucide.Clapperboard, row.title, row.source.label)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(row.items, key = { it.key }) { c -> PosterCard(c) { open(c.key) } }
                    }
                }
            }
        }
        item("apps") { AppTiles(title = "Приложения кинотеатров") }
    }
}

@Composable
private fun KgSearch(query: String, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val found by produceState<Map<VideoSource, VideoCatalog.Result<List<VideoCard>>>?>(null, query, retry) {
        value = null
        value = VideoCatalog.search(ctx, query)
    }
    val f = found
    if (f == null) { Loading(); return }
    // Collect every online hit's (normalized title, year, poster) so the Торрент section can reuse a cover without a
    // second HTTP round-trip (bug fix: torrent cards previously showed as text-only «нет превью»).
    val onlinePosters = remember(f) {
        VideoSource.entries.flatMap { src ->
            f[src]?.value.orEmpty().map { c ->
                OnlinePoster(VideoText.normalize(c.original ?: c.title), c.year, c.poster)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VideoSource.entries.forEach { src ->
            val r = f[src]
            item(src.key) {
                val list = r?.value
                RowTitle(Lucide.Search, "Найдено", src.label + (list?.let { " · ${it.size}" } ?: ""))
                when {
                    list == null -> ErrorLine(VideoCatalog.message(r?.error)) { retry++ }
                    list.isEmpty() -> T("Ничего не нашлось", 15.sp, C.Sub)
                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.key }) { c -> PosterCard(c) { open(c.key) } }
                    }
                }
            }
        }
        item("torrent") { TorrentSection(query, onlinePosters) }
    }
}

private fun parseVideoSource(key: String): VideoSource? = kg.forester.sg.video.parseVideoKey(key)?.first

@Composable
private fun RowTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tag: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, null, tint = C.Sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        T(title, 17.sp, weight = FontWeight.SemiBold)
        if (tag != null) { Spacer(Modifier.width(8.dp)); T(tag, 14.sp, C.Sub) }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Violet) }
}

@Composable
private fun ErrorLine(text: String, retry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Rounded).background(C.CardHi).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 15.sp, C.Sub, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Chip("Повторить", false, retry, Lucide.RefreshCw)
    }
}

@Composable
private fun PosterCard(c: VideoCard, onClick: () -> Unit) =
    PosterCard(c.title, c.subtitle ?: c.year.takeIf { it > 0 }?.toString(), c.poster, c.source, rating = c.rating, onClick = onClick)

/** A poster with the name under it. ts.kg's posters have the name drawn in, so they are shown whole. */
@Composable
private fun PosterCard(
    title: String, sub: String?, poster: String?, source: VideoSource?, progress: Float? = null, rating: Double = 0.0, onClick: () -> Unit,
) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).tapClickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
            RemoteImage(poster, Modifier.fillMaxSize(), if (source == VideoSource.TSKG) ContentScale.Fit else ContentScale.Crop)
            if (rating > 0) Row(
                Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                T(String.format(java.util.Locale.US, "%.1f", rating), 12.sp, Color.White)
            }
            if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(C.Violet))
            }
        }
        Spacer(Modifier.height(6.dp))
        T(title, 14.sp, maxLines = 2, weight = FontWeight.Medium)
        if (sub != null) T(sub, 12.sp, C.Sub, maxLines = 1)
    }
}

/** A title: the poster and what to play on the left, about it and the episodes on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitlePage(key: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val loaded by produceState<VideoCatalog.Result<VideoDetails>?>(null, key, retry) {
        value = null
        value = VideoCatalog.details(ctx, key)
    }
    var playing by remember { mutableStateOf<Pair<VideoDetails, VideoEpisode>?>(null) }
    playing?.let { (d, e) -> VideoPlayerDialog(d, e) { playing = null } }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("Назад", false, onBack, Lucide.ArrowLeft)
        }
        Spacer(Modifier.height(10.dp))
        val r = loaded
        val d = r?.value
        if (r == null) { Loading(); return@Column }
        if (d == null) { ErrorLine(VideoCatalog.message(r.error)) { retry++ }; return@Column }
        val version by VideoStore.version.collectAsState()
        val saved = remember(version, d) { VideoStore.get(d.key) }
        val target = remember(version, d) { VideoPolicy.resumeTarget(d, saved) }
        val seasons = remember(d) { d.episodes.map { it.season }.distinct() }
        var season by remember(d) { mutableStateOf(target?.season ?: seasons.firstOrNull() ?: 1) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(220.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(if (d.card.source == VideoSource.TSKG) 256f / 204f else 2f / 3f).clip(Rounded).background(C.CardHi)) {
                    RemoteImage(d.card.poster, Modifier.fillMaxSize(), if (d.card.source == VideoSource.TSKG) ContentScale.Fit else ContentScale.Crop)
                }
                Spacer(Modifier.height(12.dp))
                if (target != null) {
                    val started = saved != null && saved.episodeId == target.id && saved.pos > 0
                    val label = when {
                        started -> "Продолжить" + (d.label(target)?.let { " · " + it.substringBefore(" · ") } ?: "") + " · " + VideoText.time(saved!!.pos)
                        saved != null && d.series -> "Смотреть · " + (d.label(target)?.substringBefore(" · ") ?: "")
                        else -> "Смотреть"
                    }
                    PlayButton(label) { playing = d to target }
                    if (started || (saved != null && d.series && target.id != d.episodes.firstOrNull()?.id)) {
                        Spacer(Modifier.height(8.dp))
                        Chip("С начала", false, { d.episodes.firstOrNull()?.let { playing = d to it } }, Lucide.Play, Modifier.fillMaxWidth())
                    }
                } else T("Нет серий для просмотра", 14.sp, C.Sub)
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item("head") {
                    Column {
                        T(d.card.title, 22.sp, weight = FontWeight.Bold)
                        val meta = listOfNotNull(d.card.original, d.card.year.takeIf { it > 0 }?.toString(), d.countries.take(2).joinToString(", ").ifEmpty { null },
                            d.card.source.label).joinToString(" · ")
                        T(meta, 14.sp, C.Sub)
                        if (d.genres.isNotEmpty()) T(d.genres.take(4).joinToString(", "), 14.sp, C.Sub)
                        val ratings = listOfNotNull(d.ratingKp.takeIf { it > 0 }?.let { "КП " + String.format(java.util.Locale.US, "%.1f", it) },
                            d.ratingImdb.takeIf { it > 0 }?.let { "IMDb " + String.format(java.util.Locale.US, "%.1f", it) })
                        if (ratings.isNotEmpty()) T(ratings.joinToString("   "), 14.sp, C.Yellow)
                        d.description?.let { Spacer(Modifier.height(8.dp)); T(it, 15.sp, maxLines = 6) }
                    }
                }
                if (d.related.size > 1) item("related") {
                    Column {
                        RowTitle(Lucide.Clapperboard, "Сезоны и части")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.related.forEach { c -> Chip(c.subtitle ?: c.title, c.key == d.key, { if (c.key != d.key) onOpen(c.key) }) }
                        }
                    }
                }
                if (d.series && seasons.size > 1) item("seasons") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEach { s -> Chip("Сезон $s", s == season, { season = s }) }
                    }
                }
                val shown = if (d.series && seasons.size > 1) d.episodes.filter { it.season == season } else d.episodes
                if (d.episodes.size > 1) items(shown, key = { "ep-" + it.id }) { e ->
                    EpisodeRow(d, e, current = e.id == target?.id, version = version) { playing = d to e }
                }
                if (d.similar.isNotEmpty()) item("similar") {
                    Column {
                        RowTitle(Lucide.Clapperboard, "Похожие")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(d.similar, key = { it.key }) { c -> PosterCard(c) { onOpen(c.key) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalCar.current.tap).clip(RoundedCornerShape(12.dp)).background(C.Accent)
            .tapClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Play, null, tint = C.OnAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        T(text, 15.sp, C.OnAccent, FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun EpisodeRow(d: VideoDetails, e: VideoEpisode, current: Boolean, @Suppress("UNUSED_PARAMETER") version: Int, onClick: () -> Unit) {
    val p = VideoStore.episode(d.key, e.id)
    val watched = p?.get(3) == 1L
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalMinTap.current).clip(RoundedCornerShape(10.dp))
            .background(if (current) C.Purple.copy(alpha = 0.22f) else C.CardHi)
            .border(1.dp, if (current) C.Purple else C.Border, RoundedCornerShape(10.dp))
            .tapClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Play, null, tint = if (watched) C.Sub else C.Violet, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val name = if (d.series) "Серия ${e.number}" + (e.name?.let { " · $it" } ?: "") else e.name ?: d.card.title
            T(name, 15.sp, if (watched) C.Sub else C.Text, maxLines = 1)
            val sub = listOfNotNull(VideoText.duration(e.durationSec), e.voice, if (e.hd) "HD" else null, if (watched) "просмотрено" else null).joinToString(" · ")
            if (sub.isNotEmpty()) T(sub, 12.sp, C.Sub, maxLines = 1)
        }
        if (p != null && !watched && p[1] > 0) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(60.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
                Box(Modifier.fillMaxWidth((p[0].toFloat() / p[1]).coerceIn(0f, 1f)).fillMaxHeight().background(C.Violet))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- outside Kyrgyzstan

/**
 * Outside Kyrgyzstan: Rutube (its tops, films with rights, search) in the app's own player, YouTube (search with no
 * key, or with the driver's own; watched in its embed on a new enough WebView, else in its app), and the installed apps
 * of VK Видео and the online cinemas. Nothing opens a browser.
 */
@Composable
private fun OtherVideo(openRegion: () -> Unit) {
    val ctx = LocalContext.current
    remember { YouTube.loadKey(ctx); true }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AltStore.load(ctx) } }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<AltVideo?>(null) }
    open?.let { AltPlayerDialog(it) { open = null } }
    val ytKey by YouTube.key.collectAsState()
    // YouTube where it can be watched: its embed on a new enough WebView, else its app; never a browser
    val ytOn = remember { YouTube.watchable(ctx) }
    fun play(v: AltVideo) {
        open = v
    }
    androidx.activity.compose.BackHandler(enabled = searched != null) { searched = null }
    fun find() { if (query.trim().length >= 2) searched = query.trim() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(query, listOfNotNull("Rutube".takeIf { Rutube.ENABLED }, "YouTube".takeIf { ytOn }).let {
                if (it.isEmpty()) "Поиск фильма" else "Поиск на " + it.joinToString(" и ")
            }, Modifier.weight(1f), onSearch = ::find) { query = it }
            Chip("Найти", false, ::find, Lucide.Search)
            if (searched != null) Chip("Главная", false, { searched = null }, Lucide.Clapperboard)
            // YouTube and VK Видео as their apps are in «Приложения» below; no buttons that open a browser
            Chip("Другая страна", false, openRegion, Lucide.Globe)
        }
        Spacer(Modifier.height(10.dp))
        val s = searched
        // with Rutube off its catalog still gives the films and posters; a tap looks the film up on the torrent trackers
        if (s != null) AltSearch(s, ytKey, ytOn, ::play) else AltHome(::play) { title -> query = title; searched = title }
    }
}

@Composable
private fun AltHome(play: (AltVideo) -> Unit, find: (String) -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val torrent = remember { kg.forester.sg.data.Repo.settings.torrentEnabled }
    val rows by produceState<Result<List<AltRow>>?>(null, reload) {
        value = null
        value = if (Rutube.ENABLED) withContext(Dispatchers.IO) { runCatching { Rutube.home() } } else Result.success(emptyList())
    }
    // what people read about now (Wikipedia), for the torrent search; not a streaming service's catalogue
    val feed by produceState<Result<List<kg.forester.sg.video.FeedFilm>>?>(null, reload) {
        value = null
        value = if (torrent) withContext(Dispatchers.IO) { runCatching { kg.forester.sg.video.WikiFeed.films() } } else Result.success(emptyList())
    }
    val version by AltStore.version.collectAsState()
    // what can be opened now: no Rutube entries while it is off
    val recent = remember(version) { AltStore.recent(20).filter { Rutube.ENABLED || it.video.service != AltVideo.Service.RUTUBE } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("apps") { AppTiles(title = "Приложения", emptyHint = !Rutube.ENABLED && !torrent) }
        if (recent.isNotEmpty()) item("recent") {
            RowTitle(Lucide.History, "Продолжить просмотр")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent, key = { it.video.key }) { e ->
                    Box {
                        AltCard(e.video, if (e.dur > 0) e.pos.toFloat() / e.dur else null) { play(e.video) }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp).clip(CircleShape).background(Color(0xAA000000))
                                .tapClickable { AltStore.dismiss(ctx, e.video.key) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.X, "Убрать из «Продолжить»", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
        if (torrent) {
            val f = feed
            when {
                f == null -> item("feed-loading") { Loading() }
                f.getOrNull().isNullOrEmpty() -> item("feed-err") {
                    ErrorLine("Подборка: " + VideoCatalog.message(f.exceptionOrNull()) + ". Найдите фильм через поиск выше") { reload++ }
                }
                else -> item("feed") {
                    RowTitle(Lucide.Flame, "Сейчас смотрят", "торрент")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(f.getOrNull()!!, key = { it.title }) { film -> FeedCard(film) { find(film.title) } }
                    }
                }
            }
        }
        val r = rows
        if (Rutube.ENABLED) when {
            r == null -> item("loading") { Loading() }
            r.getOrNull().isNullOrEmpty() -> item("err") { ErrorLine("Rutube: " + VideoCatalog.message(r.exceptionOrNull())) { reload++ } }
            else -> r.getOrNull()!!.forEach { row ->
                item(row.id) {
                    RowTitle(Lucide.Clapperboard, row.title, "Rutube")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(row.items, key = { it.key }) { v -> AltCard(v) { play(v) } }
                    }
                }
            }
        }
    }
}

/** A film of «Сейчас смотрят»: its poster (Wikipedia's picture of the article), title, year; a tap searches the trackers. */
@Composable
private fun FeedCard(f: kg.forester.sg.video.FeedFilm, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).tapClickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(C.CardHi), contentAlignment = Alignment.Center) {
            if (f.thumb != null) RemoteImage(f.thumb, Modifier.fillMaxSize())
            else T(f.title, 15.sp, C.Sub, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp))
        }
        Spacer(Modifier.height(6.dp))
        T(f.title, 15.sp, maxLines = 2)
        T(listOfNotNull(f.year?.toString(), if (f.series) "сериал" else "фильм").joinToString(" · "), 13.sp, C.Sub, maxLines = 1)
    }
}

@Composable
private fun AltSearch(query: String, ytKey: String, ytOn: Boolean, play: (AltVideo) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val rt by produceState<Result<List<AltVideo>>?>(null, query, retry) {
        value = null
        if (Rutube.ENABLED) value = withContext(Dispatchers.IO) { runCatching { Rutube.search(query) } }
    }
    val yt by produceState<Result<List<AltVideo>>?>(null, query, ytKey, retry) {
        value = null
        if (ytOn) value = withContext(Dispatchers.IO) { runCatching { YouTube.search(query, ytKey) } }
    }
    // Same poster-reuse as the Kyrgyzstan path: AltVideo's thumb goes into the pool so Торрент can match on normalized title.
    val onlinePosters = remember(rt, yt) {
        (rt?.getOrNull().orEmpty() + yt?.getOrNull().orEmpty()).map { v ->
            OnlinePoster(VideoText.normalize(v.title), 0, v.thumb)
        } + kg.forester.sg.video.WikiFeed.cachedFilms().map { f -> OnlinePoster(VideoText.normalize(f.title), f.year ?: 0, f.thumb) }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("Rutube" to rt, "YouTube" to yt).forEach { (name, res) ->
            if (name == "YouTube" && !ytOn) return@forEach
            if (name == "Rutube" && !Rutube.ENABLED) return@forEach
            item(name) {
                val list = res?.getOrNull()
                RowTitle(Lucide.Search, "Найдено", name + (list?.let { " · ${it.size}" } ?: ""))
                when {
                    res == null -> Loading()
                    list == null -> ErrorLine(name + ": " + altMessage(res.exceptionOrNull())) { retry++ }
                    list.isEmpty() -> T("Ничего не нашлось", 15.sp, C.Sub)
                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.key }) { v -> AltCard(v) { play(v) } }
                    }
                }
            }
        }
        item("torrent") { TorrentSection(query, onlinePosters) }
    }
}

/** YouTube says 400/403 to a wrong key or a used-up daily quota. */
private fun altMessage(e: Throwable?): String =
    if ((e as? kg.forester.sg.video.VideoNet.HttpError)?.code.let { it == 400 || it == 403 }) "ключ API не подходит или кончилась дневная квота"
    else VideoCatalog.message(e)

@Composable
private fun AltCard(v: AltVideo, progress: Float? = null, onClick: () -> Unit) {
    Column(Modifier.width(240.dp).clip(RoundedCornerShape(12.dp)).tapClickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(C.CardHi)) {
            RemoteImage(v.thumb, Modifier.fillMaxSize())
            VideoText.duration(v.durationSec)?.let {
                T(it, 12.sp, Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(C.Violet))
            }
        }
        Spacer(Modifier.height(6.dp))
        T(v.title, 14.sp, maxLines = 2, weight = FontWeight.Medium)
        T(listOfNotNull(v.author, v.service.label).joinToString(" · "), 12.sp, C.Sub, maxLines = 1)
    }
}

/** The official apps of the online cinemas that are installed: opened as they are, with the driver's own subscription. */
@Composable
private fun AppTiles(title: String, emptyHint: Boolean = false) {
    val ctx = LocalContext.current
    val found by produceState<List<VideoApp>?>(null) { value = withContext(Dispatchers.IO) { VideoApps.installed(ctx) } }
    val list = found ?: return
    if (list.isEmpty()) {
        // nothing else on this screen then (Rutube off, YouTube not playable here): say what makes it useful
        if (emptyHint) T("Здесь появятся установленные приложения онлайн-кинотеатров: Кинопоиск, Okko, Wink, ivi, Start, Premier, Kion, VK Видео, YouTube. " +
            "Поставьте нужное на магнитолу, и оно откроется отсюда." + if (YouTube.watchable(ctx)) " Поиск выше ищет видео на YouTube." else "",
            15.sp, C.Sub)
        return
    }
    val apps = list
    Column {
        RowTitle(Lucide.Clapperboard, title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { it.pkg }) { a ->
                Column(
                    Modifier.width(110.dp).clip(RoundedCornerShape(14.dp)).background(C.CardHi).tapClickable { VideoApps.open(ctx, a.pkg) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bmp by produceState(AppIcons.cache.get(a.pkg), a.pkg) {
                        if (value == null) value = withContext(Dispatchers.IO) {
                            runCatching { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()?.also { AppIcons.cache.put(a.pkg, it) }
                        }
                    }
                    bmp.let { if (it != null) Image(it, a.name, Modifier.size(52.dp)) else Spacer(Modifier.size(52.dp)) }
                    Spacer(Modifier.height(6.dp))
                    T(a.name, 13.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- the region setting

@Composable
private fun VideoRegionDialog(onClose: () -> Unit) {
    CarAlertDialog(
        onDismissRequest = onClose,
        confirmButton = { Chip("Готово", false, onClose) },
        title = { T("Регион видео", 18.sp, weight = FontWeight.SemiBold) },
        text = { Column { VideoRegionSetting() } },
    )
}

/** «Регион видео: авто / Кыргызстан / другая страна», with what «авто» found. In the dialog and in Настройки → Функции. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoRegionSetting() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { VideoRegion.init(ctx); true }
    val setting by VideoRegion.setting.collectAsState()
    val kg by VideoRegion.inKyrgyzstan.collectAsState()
    T("Регион видео", 15.sp, weight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(VideoRegion.AUTO to "Авто", VideoRegion.KG to "Кыргызстан", VideoRegion.OTHER to "Другая страна").forEach { (id, label) ->
            Chip(label, setting == id, {
                VideoRegion.set(ctx, id)
                if (id == VideoRegion.AUTO) scope.launch { VideoRegion.refresh(ctx, force = true) }
            })
        }
        if (setting == VideoRegion.AUTO) Chip("Проверить сейчас", false, { scope.launch { VideoRegion.refresh(ctx, force = true) } }, Lucide.RefreshCw)
    }
    Spacer(Modifier.height(6.dp))
    val nowText = when (kg) { true -> "Кыргызстан: ts.kg и oc.kg"; false -> if (Rutube.ENABLED) "другая страна: Rutube и приложения кинотеатров" else "другая страна: YouTube и приложения кинотеатров"; null -> "ещё не определён" }
    SettingHint(
        (if (setting == VideoRegion.AUTO) "Сейчас: $nowText. " else "") +
            "ts.kg и oc.kg открываются только из Кыргызстана. «Авто» спрашивает их сервер раз в 12 часов: отказ значит другую страну, " +
            "и тогда вместо них ${if (Rutube.ENABLED) "Rutube" else "YouTube"} и приложения онлайн-кинотеатров.",
    )
    Spacer(Modifier.height(12.dp))
    remember { YouTube.loadKey(ctx); true }
    val saved by YouTube.key.collectAsState()
    var key by remember(saved) { mutableStateOf(saved) }
    T("Ключ YouTube Data API (необязательно)", 15.sp, weight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() }, singleLine = true,
            placeholder = { T("AIza…", 14.sp, C.Sub) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border),
            modifier = Modifier.weight(1f),
        )
        Chip(if (key == saved) "Сохранён" else "Сохранить", false, { YouTube.setKey(ctx, key) })
    }
    SettingHint("Поиск YouTube работает и без ключа. С ключом он идёт через официальный YouTube Data API. Ключ бесплатный: Google Cloud Console → YouTube Data API v3 → «Создать учётные данные». " +
        "Хранится только на этом устройстве. Если WebView на магнитоле старый, видео YouTube открывается в приложении YouTube.")
}
