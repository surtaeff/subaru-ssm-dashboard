package kg.forester.sg.ui

import android.annotation.SuppressLint
import android.view.SurfaceView
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.X
import kg.forester.sg.video.AltStore
import kg.forester.sg.video.AltVideo
import kg.forester.sg.video.Rutube
import kg.forester.sg.video.YouTube
import kg.forester.sg.video.VideoApps
import kg.forester.sg.video.VideoCatalog
import kg.forester.sg.video.VideoDetails
import kg.forester.sg.video.VideoEpisode
import kg.forester.sg.video.VideoNet
import kg.forester.sg.video.VideoPolicy
import kg.forester.sg.video.VideoRegion
import kg.forester.sg.video.VideoStore
import kg.forester.sg.video.VideoText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The whole screen for a player: no system bars, the screen stays on while it is up. */
@Composable
private fun FullScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    CarDialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { view.keepScreenOn = false }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, size: Dp = 64.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0x99000000)).tapClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) Color.White else Color(0x55FFFFFF), modifier = Modifier.size(size * 0.45f)) }
}

/**
 * ts.kg / oc.kg in ExoPlayer: from where it stopped, saved every 5 s and on pause, the next episode by itself at the
 * end (on oc.kg also the next season).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun VideoPlayerDialog(start: VideoDetails, startEpisode: VideoEpisode, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf(start) }
    var episode by remember { mutableStateOf(startEpisode) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }

    val player = remember {
        val http = DefaultHttpDataSource.Factory().setUserAgent(VideoNet.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(VideoNet.CONNECT_MS).setReadTimeoutMs(VideoNet.READ_MS)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
    }
    // what the player really has: the states above change before the new stream is there, a save goes by this
    var loaded by remember { mutableStateOf<Pair<VideoDetails, VideoEpisode>?>(null) }
    fun save() {
        val (d, e) = loaded ?: return
        VideoStore.save(ctx, d, e, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
    }
    /** After [ep] of [d]: the next episode, or the first of the next season (oc.kg), or the end. */
    fun goNext(auto: Boolean) {
        val n = VideoPolicy.nextAcrossRelated(details, episode.id)
        if (n == null) { if (auto) { playing = false; controls = true }; return }
        if (n.first == details.key) { details.episode(n.second)?.let { episode = it }; return }
        scope.launch {
            loading = true
            val r = VideoCatalog.details(ctx, n.first)
            val d = r.value
            val first = d?.let { VideoPolicy.resumeTarget(it, VideoStore.get(it.key)) }
            if (d == null || first == null) { loading = false; error = VideoCatalog.message(r.error); return@launch }
            details = d
            episode = first
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (!isPlaying) save() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) loading = false
                if (state == Player.STATE_BUFFERING) loading = true
                if (state == Player.STATE_ENDED) { save(); goNext(auto = true) }
            }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                loading = false
                val code = ((e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode)
                if (code == 403) VideoRegion.blockedSeen(ctx)
                error = if (code == 403) "${details.card.source.label} открывается только из Кыргызстана" else "Видео не воспроизводится. Попробуйте ещё раз или другую серию"
            }
        }
        player.addListener(l)
        onDispose {
            save()
            player.removeListener(l)
            player.release()
        }
    }
    // the stream of this episode, from where it stopped
    LaunchedEffect(details.key, episode.id, retry) {
        error = null
        loading = true
        save()
        loaded = null
        player.stop()
        val r = VideoCatalog.stream(ctx, details, episode)
        val s = r.value
        if (s == null) { loading = false; error = VideoCatalog.message(r.error); return@LaunchedEffect }
        val item = MediaItem.Builder().setUri(s.url).apply { if (s.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        player.setMediaItem(item, VideoStore.resumeOf(details, episode))
        loaded = details to episode
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(500)
            pos = player.currentPosition
            dur = player.duration.takeIf { it > 0 } ?: 0L
            if (++n % 10 == 0 && player.isPlaying) save()
        }
    }
    LaunchedEffect(controls, touched, playing) { if (controls && playing) { delay(4_000); controls = false } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    FullScreen(onClose) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { controls = !controls; touched++ },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView({ c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } }, Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true),
                onRelease = { player.clearVideoSurfaceView(it) })
            if (loading && error == null) CircularProgressIndicator(color = Color.White)
            error?.let { msg ->
                Column(Modifier.background(Color(0xCC000000)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(msg, 17.sp, Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Ещё раз", false, { retry++ }, Lucide.RotateCw)
                        Chip("Закрыть", false, onClose, Lucide.X)
                    }
                }
            }
            if (controls && error == null) {
                Column(Modifier.fillMaxSize().background(Color(0x55000000)).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Lucide.X, "Закрыть", 56.dp, onClick = onClose)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            T(details.card.title, 20.sp, Color.White, FontWeight.SemiBold, maxLines = 1)
                            details.label(episode)?.let { T(it, 15.sp, Color(0xFFCCCCCC), maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        val prev = VideoPolicy.prevEpisode(details, episode.id)
                        val next = VideoPolicy.nextAcrossRelated(details, episode.id)
                        if (details.series) RoundButton(Lucide.SkipBack, "Предыдущая серия", enabled = prev != null) { save(); prev?.let { episode = it } }
                        RoundButton(Lucide.RotateCcw, "Назад 10 с") { player.seekTo(maxOf(0, player.currentPosition - 10_000)); touched++ }
                        RoundButton(if (playing) Lucide.Pause else Lucide.Play, if (playing) "Пауза" else "Смотреть", 88.dp) {
                            if (player.isPlaying) player.pause() else player.play(); touched++
                        }
                        RoundButton(Lucide.RotateCw, "Вперёд 30 с") { player.seekTo(player.currentPosition + 30_000); touched++ }
                        if (details.series) RoundButton(Lucide.SkipForward, "Следующая серия", enabled = next != null) { save(); goNext(auto = false) }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(VideoText.time(pos), 15.sp, Color.White)
                        Slider(
                            value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                            onValueChange = { f -> if (dur > 0) { pos = (f * dur).toLong(); touched++ } },
                            onValueChangeFinished = { if (dur > 0) player.seekTo(pos) },
                            enabled = dur > 0,
                            colors = SliderDefaults.colors(thumbColor = C.Violet, activeTrackColor = C.Violet),
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        )
                        T(if (dur > 0) VideoText.time(dur) else "--:--", 15.sp, Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Rutube's or YouTube's own embedded player (their controls, their ads), full screen. Rutube's embed tells the time
 * (postMessage), so the next opening starts at `t` where it stopped.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun AltPlayerDialog(v: AltVideo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // the last time and length the embed told, in ms
    val pos = remember { longArrayOf(AltStore.get(v.key)?.pos ?: 0L, AltStore.get(v.key)?.dur ?: 0L) }
    fun save() { if (v.service == AltVideo.Service.RUTUBE) AltStore.save(ctx, v, pos[0], pos[1]) }
    DisposableEffect(Unit) { onDispose { save() } }
    FullScreen(onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Lucide.X, "Закрыть", 52.dp, onClick = onClose)
                Spacer(Modifier.width(14.dp))
                T(v.title, 17.sp, Color.White, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Chip("Открыть в ${v.service.label}", false, { save(); VideoApps.openInApp(ctx, v) }, Lucide.ExternalLink)
            }
            val start = remember { VideoPolicy.resumePosition(pos[0], pos[1]) / 1000 }
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        setBackgroundColor(android.graphics.Color.BLACK)
                        addJavascriptInterface(object {
                            private var last = 0L
                            @android.webkit.JavascriptInterface
                            fun on(type: String, json: String) {
                                val d = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
                                when (type) {
                                    "player:currentTime" -> pos[0] = (d.optDouble("time", 0.0) * 1000).toLong()
                                    "player:durationChange" -> pos[1] = (d.optDouble("duration", 0.0) * 1000).toLong()
                                    "player:playComplete" -> if (pos[1] > 0) pos[0] = pos[1]
                                    else -> return
                                }
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (type != "player:currentTime" || now - last > 5_000) { last = now; android.os.Handler(android.os.Looper.getMainLooper()).post { save() } }
                            }
                        }, "CarPulse")
                        val page = if (v.service == AltVideo.Service.RUTUBE) Rutube.embedPage(v.id, start) else YouTube.embedPage(v.id, start)
                        loadDataWithBaseURL("https://kg.forester.sg/", page, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRelease = { w -> w.stopLoading(); w.loadUrl("about:blank"); w.destroy() },
            )
        }
    }
}
