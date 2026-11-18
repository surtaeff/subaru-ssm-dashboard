package kg.forester.sg.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kg.forester.sg.data.Repo
import kg.forester.sg.torrent.TorrentCatalog
import kg.forester.sg.torrent.TorrentPlaybackError
import kg.forester.sg.torrent.TorrentRelease
import kg.forester.sg.video.VideoText
import kg.forester.sg.video.rememberCarMoving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.ListVideo
import com.composables.icons.lucide.Minimize2
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SkipForward

/** A poster the KgSearch / AltSearch screens already have in memory for an online hit. The Torrent section reuses these
 *  by normalized-title match so a release card can show a cover without a second HTTP round-trip. */
data class OnlinePoster(val normTitle: String, val year: Int, val url: String?) {
    companion object {
        /** Match on normalized title and, if both sides know a year, also on year. Zero year = unknown = ignore. */
        fun pick(release: TorrentRelease, pool: List<OnlinePoster>): String? {
            if (pool.isEmpty()) return null
            val tNorm = VideoText.normalize(release.originalName ?: release.name)
            if (tNorm.isBlank()) return null
            val y = release.year
            // exact title + year first; title-only second; fall back to a title-contains match last.
            pool.firstOrNull { it.normTitle == tNorm && (y == 0 || it.year == 0 || it.year == y) && !it.url.isNullOrBlank() }
                ?.let { return it.url }
            pool.firstOrNull { it.normTitle == tNorm && !it.url.isNullOrBlank() }?.let { return it.url }
            return pool.firstOrNull { !it.url.isNullOrBlank() && (tNorm.contains(it.normTitle) || it.normTitle.contains(tNorm)) }?.url
        }
    }
}

/**
 * «Торрент» section in «Видео». Shown in both Kyrgyz and non-Kyrgyz regions, as its own section under the online
 * results — plan explicitly says: do not merge with online cards.
 *
 * The master switch stays off until the owner turns it on on their own head unit (carpulse-local-only-overhaul memory).
 * When off, the section is a thin chip offering to turn it on from Настройки; nothing else runs, no engine spawns.
 */
@Composable
fun TorrentSection(query: String, onlinePosters: List<OnlinePoster> = emptyList()) {
    val ctx = LocalContext.current
    val enabled = remember { mutableStateOf(Repo.settings.torrentEnabled) }
    var showSettings by remember { mutableStateOf(false) }
    var play by remember { mutableStateOf<TorrentRelease?>(null) }
    val moving = rememberCarMoving()

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTitleCompat("Торрент", "jacred.su")
            Spacer(Modifier.width(10.dp))
            Chip("Настройки", false, { showSettings = true })
        }
        Spacer(Modifier.height(8.dp))
        when {
            !enabled.value -> T("Поиск по открытым трекерам выключен. Включите в настройках, если хотите видеть здесь раздачи.", 14.sp, C.Sub)
            moving -> T(TorrentDriving.BODY, 14.sp, C.Sub)
            query.isBlank() -> T("Введите название в поиске выше — покажу живые раздачи.", 14.sp, C.Sub)
            else -> TorrentResults(query, onlinePosters) { r -> if (!TorrentDriving.gatePick(moving)) play = r }
        }
    }

    if (showSettings) TorrentSettingsDialog(
        onEnabledChange = { v -> Repo.settings.torrentEnabled = v; enabled.value = v },
        onClose = { showSettings = false },
    )
    play?.let { TorrentPlayerDialog(it) { play = null } }
}

/** Torrent-player driving lock: the online Видео players drop theirs (commit 7c41302), torrent keeps it on. Seeking a
 *  torrent stream is a real cost (chunks have to arrive, cache may not cover the jump) and a stall while a half-filled
 *  buffer catches up is worse behind the wheel than for the online players. Kept as a pure object for a JVM test. */
internal object TorrentDriving {
    /** Reuses the owner's established wording from before the online lock was dropped: «на стоянке / остановитесь». */
    const val TITLE = "Торрент: выбор раздачи только на стоянке"
    const val BODY = "Машина едет: выбрать и открыть новую раздачу нельзя. То, что уже играет, продолжит играть. Остановитесь, и можно будет открыть следующую."

    /** Pick is gated while moving. Returns true when the UI should swallow the click. */
    fun gatePick(moving: Boolean): Boolean = moving
}

@Composable
private fun TorrentResults(query: String, onlinePosters: List<OnlinePoster>, onPick: (TorrentRelease) -> Unit) {
    val ctx = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val found by produceState<Result<List<TorrentRelease>>?>(null, query, retry) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { TorrentCatalog.search(ctx, query) } }
    }
    when (val r = found) {
        null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Violet) }
        else -> {
            val list = r.getOrNull()
            when {
                list == null -> ErrorLineCompat(TorrentCatalog.message(r.exceptionOrNull())) { retry++ }
                list.isEmpty() -> T("Раздач не нашлось", 15.sp, C.Sub)
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list.take(40), key = { TorrentCatalog.infohash(it.magnet) }) { r ->
                        ReleaseCard(r, OnlinePoster.pick(r, onlinePosters)) { onPick(r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(r: TorrentRelease, posterUrl: String?, onClick: () -> Unit) {
    Column(
        Modifier.width(260.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .tapClickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Cover: if KgSearch/AltSearch already fetched a poster for this title, reuse it (zero new HTTP). Else a neutral
        // tile with the quality badge — no network call, no «нет превью» embarrassment.
        Box(
            Modifier.fillMaxWidth().height(146.dp).background(C.Card),
            contentAlignment = Alignment.Center,
        ) {
            if (!posterUrl.isNullOrBlank()) {
                RemoteImage(posterUrl, Modifier.fillMaxSize())
            } else {
                T("Торрент", 20.sp, C.Sub, weight = FontWeight.SemiBold)
            }
            if (r.quality > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    T("${r.quality}p", 12.sp, Color.White, weight = FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val head = listOfNotNull(r.name.takeIf { it.isNotBlank() }, r.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
            T(head.ifBlank { r.title }, 15.sp, weight = FontWeight.SemiBold, maxLines = 2)
            T(r.label, 13.sp, C.Sub, maxLines = 1)
            if (r.tracker.isNotBlank()) T(r.tracker, 12.sp, C.Sub, maxLines = 1)
        }
    }
}

/** Minimal full-screen player for torrent streams. ExoPlayer with a bigger LoadControl so small stalls over the local
 *  HTTP are absorbed. Range / seek already works because TorrServer answers 206 on /stream.
 *
 *  Driving lock: while the car is moving, playback of what is already streaming continues (per coordinator intent — a
 *  stall over Range seeking mid-movement is riskier than letting the current stream play out). But the driver can't
 *  open the file-pick / seek-to-any-position controls: the overlay is shown instead. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun TorrentPlayerDialog(r: TorrentRelease, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var fill by remember { mutableStateOf(kg.forester.sg.video.VideoFit.fill(ctx)) }
    val moving = rememberCarMoving()
    // the release's episodes (one for a film); a series asks which one before it starts
    var opened by remember { mutableStateOf<TorrentCatalog.Opened?>(null) }
    var episode by remember { mutableStateOf<kg.forester.sg.torrent.TorrClient.TorrFile?>(null) }
    var picking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Подключаюсь к раздаче…") }

    val player = remember {
        // Head units sit at a 48–192 MB Java heap even with largeHeap=true; a 4K stream at 50 Mb/s × 120 s maxBuffer plans
        // ~750 MB of buffer alone and blows OOM before the first picture. Cut the buffer window to ~45 s max — car trips
        // don't need more, and the local TorrServer answers Range instantly on a seek.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
            .build()
        // the local TorrServer answers a stream only once it has the start of the film from the peers: ExoPlayer's 8 s
        // read timeout gave «нет ответа от раздачи» on a live release (emulator and the owner's unit, 2026-11-18)
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(90_000).setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, http)))
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
    }

    fun next(): kg.forester.sg.torrent.TorrClient.TorrFile? {
        val eps = opened?.episodes ?: return null
        val i = eps.indexOf(episode)
        return eps.getOrNull(i + 1)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { loading = false; dur = player.duration.coerceAtLeast(0L) }
                if (state == Player.STATE_BUFFERING) loading = true
                // the next episode by itself, as on ts.kg
                if (state == Player.STATE_ENDED) next()?.let { episode = it } ?: run { controls = true }
            }
            // a format this unit's decoder can't take: ExoPlayer still tries and hangs at the start with no error
            // (HEVC 10 bit on the emulator, 2026-11-18), so it is said at once
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val video = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                if (video.isNotEmpty() && video.none { g -> (0 until g.length).any { g.isTrackSupported(it, false) } }) {
                    player.stop()
                    loading = false
                    error = "Это видео магнитола не декодирует (${video.first().getTrackFormat(0).codecs ?: "кодек"}). " +
                        "Выберите раздачу с H.264 (AVC, x264), без HEVC 10 бит и HDR"
                }
            }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                loading = false
                error = TorrentPlaybackError.message(e)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    DisposableEffect(opened?.hash) {
        val hash = opened?.hash
        onDispose { if (hash != null) TorrentCatalog.close(ctx, hash) }
    }
    LaunchedEffect(r.magnet) {
        loading = true
        error = null
        runCatching { TorrentCatalog.open(ctx, r) }
            .onSuccess { o ->
                opened = o
                if (o.episodes.size == 1) episode = o.episodes[0] else { loading = false; picking = true }
            }
            .onFailure { error = TorrentCatalog.message(it) }
    }
    LaunchedEffect(episode) {
        val o = opened ?: return@LaunchedEffect
        val f = episode ?: return@LaunchedEffect
        loading = true
        error = null
        player.setMediaItem(MediaItem.fromUri(TorrentCatalog.episodeUrl(ctx, o.hash, f)))
        player.prepare()
        player.playWhenReady = true
        // while the start comes in: how many peers give it, so the wait isn't a bare spinner
        while (player.playbackState != Player.STATE_READY && error == null) {
            TorrentCatalog.peers(ctx, o.hash)?.let { (seeders, peers) ->
                status = if (peers == 0) "Ищу пиров…" else "Загружаю начало: $seeders сид., $peers пиров"
            }
            kotlinx.coroutines.delay(1_500)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            pos = player.currentPosition
            dur = player.duration.takeIf { it > 0 } ?: 0L
        }
    }
    LaunchedEffect(controls, touched, playing) { if (controls && playing) { kotlinx.coroutines.delay(4_000); controls = false } }

    FullScreen(onClose) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                controls = !controls; touched++
            },
            contentAlignment = Alignment.Center,
        ) {
            val (w, h) = kg.forester.sg.video.VideoFit.size(maxWidth.value, maxHeight.value, aspect, fill)
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c -> android.view.TextureView(c).also { player.setVideoTextureView(it) } },
                onRelease = { player.clearVideoTextureView(it) },
                modifier = Modifier.requiredSize(w.dp, h.dp),
            )
            if (loading && error == null && !picking) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = C.Violet)
                Spacer(Modifier.height(12.dp))
                T(status, 15.sp, Color.White)
            }
            error?.let { msg ->
                Column(Modifier.background(Color(0xCC000000)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    T(msg, 17.sp, Color.White)
                    Chip("Закрыть", false, onClose)
                }
            }
            if (picking) EpisodePicker(opened?.episodes.orEmpty(), episode, moving, onPick = { picking = false; episode = it }, onClose = {
                picking = false; if (episode == null) onClose()
            })
            if (controls && error == null && !picking) {
                Column(Modifier.fillMaxSize().background(Color(0x55000000)).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Lucide.X, "Закрыть", 56.dp, onClick = onClose)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            T(r.title.ifBlank { r.name }, 20.sp, Color.White, FontWeight.SemiBold, maxLines = 1)
                            val eps = opened?.episodes.orEmpty()
                            episode?.takeIf { eps.size > 1 }?.let { T(kg.forester.sg.torrent.Episodes.label(it.path), 15.sp, Color(0xFFCCCCCC), maxLines = 1) }
                        }
                        if ((opened?.episodes?.size ?: 0) > 1 && !moving) {
                            Chip("Серии", false, { picking = true }, Lucide.ListVideo)
                            Spacer(Modifier.width(10.dp))
                        }
                        Chip(if (fill) "Вписать" else "Заполнить экран", fill, {
                            fill = !fill; kg.forester.sg.video.VideoFit.setFill(ctx, fill); touched++
                        }, if (fill) Lucide.Minimize2 else Lucide.Maximize2)
                    }
                    Spacer(Modifier.weight(1f))
                    // seeking and the next file are the gated actions while moving; play and pause stay
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!moving) RoundButton(Lucide.RotateCcw, "Назад 10 с") { player.seekTo(maxOf(0, player.currentPosition - 10_000)); touched++ }
                        RoundButton(if (playing) Lucide.Pause else Lucide.Play, if (playing) "Пауза" else "Смотреть", 88.dp) {
                            if (player.isPlaying) player.pause() else player.play(); touched++
                        }
                        if (!moving) RoundButton(Lucide.RotateCw, "Вперёд 30 с") { player.seekTo(player.currentPosition + 30_000); touched++ }
                        if (!moving && next() != null) RoundButton(Lucide.SkipForward, "Следующая серия") { next()?.let { episode = it }; touched++ }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(kg.forester.sg.video.VideoText.time(pos), 15.sp, Color.White)
                        Slider(
                            value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                            onValueChange = { f -> if (dur > 0 && !moving) { pos = (f * dur).toLong(); touched++ } },
                            onValueChangeFinished = { if (dur > 0 && !moving) player.seekTo(pos) },
                            enabled = dur > 0 && !moving,
                            colors = SliderDefaults.colors(thumbColor = C.Violet, activeTrackColor = C.Violet),
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        )
                        T(if (dur > 0) kg.forester.sg.video.VideoText.time(dur) else "--:--", 15.sp, Color.White)
                    }
                }
            }
            // the driving lock: a thin strip at the top, the stream keeps playing
            if (moving) Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xCC000000)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { T(TorrentDriving.TITLE, 14.sp, Color.White, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
        }
    }
}

/** A series' episodes to start from; big rows for a car screen, the one playing marked. Only parked. */
@Composable
private fun EpisodePicker(
    eps: List<kg.forester.sg.torrent.TorrClient.TorrFile>,
    current: kg.forester.sg.torrent.TorrClient.TorrFile?,
    moving: Boolean,
    onPick: (kg.forester.sg.torrent.TorrClient.TorrFile) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(0xEE000000)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Выберите серию", 22.sp, Color.White, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T("${eps.size} в раздаче", 15.sp, Color(0xFFCCCCCC))
            Spacer(Modifier.width(14.dp))
            Chip("Закрыть", false, onClose, Lucide.X)
        }
        Spacer(Modifier.height(12.dp))
        // a release with several seasons: a row of seasons, the episodes of the chosen one below
        val seasonOf = remember(eps) { eps.associateWith { kg.forester.sg.torrent.Episodes.numbers(it.path)?.first } }
        val seasons = remember(eps) { seasonOf.values.filterNotNull().distinct().sorted() }
        var season by remember(eps) { mutableStateOf(current?.let { seasonOf[it] } ?: seasons.firstOrNull()) }
        if (seasons.size > 1 && !moving) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons.size) { i -> val n = seasons[i]; Chip("Сезон $n", n == season, { season = n }) }
            }
            Spacer(Modifier.height(12.dp))
        }
        val shown = if (seasons.size > 1) eps.filter { seasonOf[it] == season || seasonOf[it] == null } else eps
        if (moving) T(TorrentDriving.BODY, 15.sp, Color(0xFFCCCCCC))
        else androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown.size) { i ->
                val f = shown[i]
                val now = f == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (now) C.Violet.copy(alpha = 0.35f) else C.CardHi).clickable { onPick(f) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(kg.forester.sg.torrent.Episodes.label(f.path), 18.sp, Color.White, FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                    T(if (f.length >= 1L shl 30) "%.1f ГБ".format(f.length / (1L shl 30).toDouble()) else fileSize(f.length), 14.sp, Color(0xFFCCCCCC))
                }
            }
        }
    }
}

@Composable
private fun TorrentSettingsDialog(onEnabledChange: (Boolean) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Repo.settings.torrentEnabled) }
    val rungs = kg.forester.sg.torrent.TorrEngine.Settings.CACHE_RUNGS_MB
    var rung by remember { mutableIntStateOf(rungs.indexOf(Repo.settings.torrentCacheMb).coerceAtLeast(0)) }
    var hosts by remember { mutableStateOf(Repo.settings.torrentJacredHosts) }

    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.CardHi).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            T("Торрент", 20.sp, weight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Показывать раздел «Торрент» в Видео", 15.sp)
                    T("Качает и хранит временные файлы во внутренней памяти. Включено по умолчанию.", 13.sp, C.Sub)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; onEnabledChange(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Purple, checkedThumbColor = Color.White),
                )
            }
            Column {
                T("Кэш на диске: ${rungs[rung]} МБ", 15.sp)
                Slider(
                    value = rung.toFloat(),
                    onValueChange = { rung = it.toInt().coerceIn(0, rungs.lastIndex); Repo.settings.torrentCacheMb = rungs[rung] },
                    valueRange = 0f..rungs.lastIndex.toFloat(),
                    steps = rungs.size - 2,
                )
                T("Удаляется при закрытии приложения.", 13.sp, C.Sub)
            }
            OutlinedTextField(
                value = hosts,
                onValueChange = { hosts = it; Repo.settings.torrentJacredHosts = it },
                singleLine = true,
                placeholder = { T("jacred.su, jac.red — через запятую, пусто = по умолчанию", 13.sp, C.Sub, maxLines = 1) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onClose() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Очистить кэш", false, {
                    // engine may not be alive yet — the Catalog starts it lazily on a click; here we only clear the dir
                    val dir = java.io.File(ctx.applicationContext.filesDir, "torrserver/cache")
                    dir.listFiles()?.forEach { it.deleteRecursively() }
                    dir.mkdirs()
                })
                Chip("Готово", false, onClose)
            }
        }
    }
}

/** Minimal row-title to avoid a public symbol collision with the file-private RowTitle in VideoScreen. */
@Composable
private fun RowTitleCompat(title: String, tag: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(title, 17.sp, weight = FontWeight.SemiBold)
        if (tag != null) { Spacer(Modifier.width(8.dp)); T(tag, 14.sp, C.Sub) }
    }
}

@Composable
private fun ErrorLineCompat(text: String, retry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 15.sp, C.Sub, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Chip("Повторить", false, retry)
    }
}
