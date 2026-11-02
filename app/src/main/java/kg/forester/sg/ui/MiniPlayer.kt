package kg.forester.sg.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import kg.forester.sg.data.DeviceTier
import kg.forester.sg.data.I18n
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Tier
import kg.forester.sg.service.FmRadio
import kg.forester.sg.service.Fyt
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.MediaSessions
import kg.forester.sg.service.MiniHold
import kg.forester.sg.service.NowPlaying
import kg.forester.sg.service.audio.AudioPlatforms
import kg.forester.sg.service.audio.BtTrack
import kg.forester.sg.service.audio.RadioCap
import kg.forester.sg.service.audio.Transport
import kg.forester.sg.service.pickNow
import kg.forester.sg.service.playerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the mini player knows, kept apart from what lays it out: [known] is read by the screen's layout and changes only when
 * the strip comes or goes; the track and the position are read by the strip alone (the position only while drawing).
 */
class MiniPlayerState {
    var known by mutableStateOf(false)
        private set
    var track by mutableStateOf<NowPlaying?>(null)
        private set
    val share = mutableFloatStateOf(0f)

    private val hold = MiniHold()
    private var bt: BtTrack? = null
    private var appNow: NowPlaying? = null

    /** Runs while the app is on the screen: a look once a second, the other players' sessions off the main thread. */
    suspend fun run(ctx: Context) = coroutineScope {
        var btJob: Job? = null
        launch(Dispatchers.IO) {
            while (true) {
                appNow = runCatching { otherPlayer(ctx) }.getOrNull()
                delay(if (DeviceTier.current.value == Tier.LOW) 4000 else 2000)
            }
        }
        while (true) {
            val mode = Repo.settings.mediaSource
            // the Bluetooth part runs only while someone listens (see BtBackend.track), so only while it is what Media is on
            if (mode == "bt" && btJob == null) {
                val track = withContext(Dispatchers.IO) { AudioPlatforms.current(ctx).bt.track }
                btJob = launch { track.collect { bt = it } }
            } else if (mode != "bt" && btJob != null) { btJob?.cancel(); btJob = null; bt = null }
            val fm = if (mode == "radio" && !internetRadio(ctx)) withContext(Dispatchers.IO) { tunerNow(ctx) } else null
            val now = pickNow(mode, ownNow(), fm, bt?.let(::btNow), appNow)
            val shown = hold.feed(now, System.currentTimeMillis())
            // same text and state: the strip isn't recomposed for it (a cover comes as a new object with each look)
            if (shown?.key() != track?.key()) track = shown
            known = shown != null
            if (DeviceTier.current.value != Tier.LOW && shown != null) {
                val pos = if (shown.source == "files") LocalMusic.position() else shown.posMs
                share.floatValue = if (shown.durMs > 0) (pos.toFloat() / shown.durMs).coerceIn(0f, 1f) else 0f
            }
            delay(1000)
        }
    }

    private fun NowPlaying.key() = listOf(source, title, artist, playing, durMs, pkg, art != null)

    private fun ownNow(): NowPlaying? {
        val st = LocalMusic.state.value
        if (st.current == null) return null
        return if (st.stream) NowPlaying("stream", st.station.ifBlank { st.title }, if (st.station.isBlank()) "" else st.title, null, st.playing)
        else NowPlaying("files", st.title, st.artist, st.art, st.playing, durMs = st.durMs)
    }

    private fun btNow(t: BtTrack): NowPlaying? {
        if (t.title.isBlank() && !t.silentUnit && !t.playing) return null
        return NowPlaying("bt", t.title.ifBlank { "Музыка с телефона" }, t.artist, t.art, t.playing, t.posMs, t.totalMs)
    }

    private fun tunerNow(ctx: Context): NowPlaying? {
        val radio = AudioPlatforms.current(ctx).radio
        if (RadioCap.TUNE !in radio.caps) return null
        val r = radio.state.value
        if (r.freq <= 0) return null
        val unit = if (r.isFm || r.band == -1) "МГц" else "кГц"
        return NowPlaying("fm", "${r.freqText} $unit", r.name, r.logo, radio.active.value)
    }

    private fun otherPlayer(ctx: Context): NowPlaying? {
        val mode = Repo.settings.mediaSource
        val chosen = Repo.settings.musicApp
        if (mode == "apps" && chosen == "fyt") {
            val m = Fyt.music.value
            return if (m.title.isBlank() && !m.playing) null
            else NowPlaying("app", m.title, m.artist, m.art, m.source == "music" && m.playing, m.posMs, m.totalMs, "fyt")
        }
        val pkg = MediaSessions.playingApp(ctx)?.first ?: chosen.takeIf { mode == "apps" && it.isNotEmpty() } ?: return null
        val p = playerState(ctx, pkg)
        return if (!p.session || p.title.isBlank()) null else NowPlaying("app", p.title, p.artist, p.art, p.playing, p.posMs, p.totalMs, pkg)
    }

    private fun internetRadio(ctx: Context) = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE).getBoolean("internet", false)
}

/** The strip's room: buttons of the parked target, one line of text. */
@Composable
internal fun miniPlayerHeight() = maxOf(56.dp, LocalCar.current.secondary.lineDp() + 8.dp)

/**
 * The strip: cover, what plays, previous, play/pause, next; a tap on the text opens Media. The buttons go by the same route
 * as Media's own for that source. No marquee, an ellipsis: nothing runs here but a line of progress, redrawn once a second.
 */
@Composable
fun MiniPlayerStrip(state: MiniPlayerState, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val car = LocalCar.current
    val now = state.track ?: return
    val h = miniPlayerHeight()
    val accent = C.theme.deep
    fun step(next: Boolean) = when (now.source) {
        "bt" -> AudioPlatforms.current(ctx).bt.send(if (next) Transport.NEXT else Transport.PREV)
        "fm" -> {
            val radio = AudioPlatforms.current(ctx).radio
            FmRadio.load(ctx)
            FmRadio.neighbour(radio.state.value.freq, next)?.let { FmRadio.tune(it.freq) } ?: tunerPreset(radio, next)
        }
        "app" -> appControl(ctx, now.pkg) { if (next) it.skipToNext() else it.skipToPrevious() }
        else -> if (next) LocalMusic.next(ctx) else LocalMusic.prev(ctx)
    }
    fun press() {
        when (now.source) {
            "bt" -> AudioPlatforms.current(ctx).bt.press(now.playing)
            // the tuner has no pause of its own: as on Media, the button brings it on
            "fm" -> scope.launch { AudioPlatforms.current(ctx).radio.on() }
            "app" -> if (now.pkg == "fyt") Fyt.playPause() else appControl(ctx, now.pkg) { if (now.playing) it.pause() else it.play() }
            else -> LocalMusic.toggle(ctx)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(h).clip(Rounded).panelFill().border(1.dp, C.Border, Rounded).drawBehind {
            // read only here: the second's tick redraws this line, nothing is composed for it
            val share = state.share.floatValue
            if (share > 0f) drawRect(accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width * share, 2.dp.toPx()))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().tapClickable(onClick = onOpen).padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val coverShape = CircleShape
            Box(Modifier.size(h - 12.dp).clip(coverShape).background(C.CardHi), contentAlignment = Alignment.Center) {
                val art = now.art
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop)
                else Icon(if (now.source == "fm" || now.source == "stream") Lucide.Radio else Lucide.Music, null, tint = C.Sub, modifier = Modifier.size(20.dp))
            }
            // a song's name isn't translated; the tuner's «МГц» and «Музыка с телефона» are the app's
            val title = if (now.source == "fm" || now.title == "Музыка с телефона") now.title else I18n.raw(now.title)
            T(title, car.secondary, C.Text, FontWeight.Medium, Modifier.weight(1f, fill = false), maxLines = 1)
            if (now.artist.isNotBlank()) T(I18n.raw(now.artist), car.secondary, C.Sub, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
        }
        StripButton(Lucide.SkipBack, "Предыдущий") { step(false) }
        StripButton(if (now.source == "fm") Lucide.Radio else if (now.playing) Lucide.Pause else Lucide.Play, if (now.playing) "Пауза" else "Играть", primary = true) { press() }
        StripButton(Lucide.SkipForward, "Следующий") { step(true) }
        Spacer(Modifier.width(4.dp))
    }
}

/** As tall as the strip, as wide as the tap token: a finger on a moving car has more room sideways. */
@Composable
private fun StripButton(icon: ImageVector, what: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().width(maxOf(56.dp, LocalCar.current.tap)).clip(RoundedCornerShape(10.dp)).tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, what, tint = if (primary) C.Violet else C.Text, modifier = Modifier.size(26.dp)) }
}

/** The room the strip takes at its place in a column: itself and the gap of that layout. */
@Composable
fun MiniPlayerSlot(state: MiniPlayerState, gap: androidx.compose.ui.unit.Dp, above: Boolean, onOpen: () -> Unit) {
    if (above) Spacer(Modifier.height(gap))
    MiniPlayerStrip(state, onOpen)
    if (!above) Spacer(Modifier.height(gap))
}
