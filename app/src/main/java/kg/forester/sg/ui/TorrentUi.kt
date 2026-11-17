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
import kg.forester.sg.torrent.TorrentRelease
import kg.forester.sg.video.rememberCarMoving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Торрент» section in «Видео». Shown in both Kyrgyz and non-Kyrgyz regions, as its own section under the online
 * results — plan explicitly says: do not merge with online cards.
 *
 * The master switch stays off until the owner turns it on on their own head unit (carpulse-local-only-overhaul memory).
 * When off, the section is a thin chip offering to turn it on from Настройки; nothing else runs, no engine spawns.
 */
@Composable
fun TorrentSection(query: String) {
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
            else -> TorrentResults(query) { r -> if (!TorrentDriving.gatePick(moving)) play = r }
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
private fun TorrentResults(query: String, onPick: (TorrentRelease) -> Unit) {
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
                        ReleaseCard(r) { onPick(r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(r: TorrentRelease, onClick: () -> Unit) {
    Column(
        Modifier.width(260.dp).clip(RoundedCornerShape(12.dp)).background(C.CardHi)
            .tapClickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val head = listOfNotNull(r.name.takeIf { it.isNotBlank() }, r.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
        T(head.ifBlank { r.title }, 15.sp, weight = FontWeight.SemiBold, maxLines = 2)
        T(r.label, 13.sp, C.Sub, maxLines = 1)
        if (r.tracker.isNotBlank()) T(r.tracker, 12.sp, C.Sub, maxLines = 1)
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
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    val moving = rememberCarMoving()

    val player = remember {
        val load = DefaultLoadControl.Builder()
            // torrent chunks arrive in bursts: keep a generous window so a briefly empty read does not stop playback
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        ExoPlayer.Builder(ctx)
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { loading = false; dur = player.duration.coerceAtLeast(0L) }
                if (state == Player.STATE_BUFFERING) loading = true
            }
            override fun onPlayerError(e: PlaybackException) {
                loading = false
                error = "Поток прервался: ${e.message ?: "сид ушёл, попробуйте другую раздачу"}"
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    LaunchedEffect(r.magnet) {
        loading = true
        error = null
        runCatching { TorrentCatalog.stream(ctx, r) }
            .onSuccess { url ->
                val item = MediaItem.fromUri(url)
                player.setMediaItem(item)
                player.prepare()
                player.playWhenReady = true
            }
            .onFailure { error = TorrentCatalog.message(it) }
    }

    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c -> android.view.SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            onRelease = { player.clearVideoSurfaceView(it) },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) CircularProgressIndicator(color = C.Violet, modifier = Modifier.align(Alignment.Center))
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T(msg, 16.sp, Color.White)
                Chip("Закрыть", false, onClose)
            }
        }
        // minimal controls — hidden while the car moves: picking a seek position or opening a new release is the gated
        // action; what is already playing goes on (per the lock's intent).
        if (!moving) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (player.isPlaying) "Пауза" else "▶", false, { player.playWhenReady = !player.playWhenReady })
                    Chip("Закрыть", false, onClose)
                }
            }
        }
        // The driving-lock overlay: a thin strip at the top so it does not cover the picture — the stream keeps playing.
        if (moving) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xCC000000)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(TorrentDriving.TITLE, 14.sp, Color.White, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                    T("Качает и хранит временные файлы во внутренней памяти. Выключено по умолчанию.", 13.sp, C.Sub)
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
