package kg.forester.sg.service.yandex

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.MediaFocus
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Yandex Music in the app's own player (ported from FreeLite's YaMusicService, without its separate service): the tracks
 * are items of [LocalMusic]'s ExoPlayer, so its media session, the mini player, the steering-wheel keys, the equalizer
 * and the spectrum work as for a file. An item's URI is `yandex://track/{id}`; the player's data source asks [resolver]
 * for the real link when it opens one (the next track's while the current one ends): download-info, the signed MP3/AAC
 * link (the highest bitrate up to 320 kbit/s, AAC on a tie), the token in the header to Yandex's storage only.
 *
 * A queue (playlist, «Мне нравится», album, search) or «Моя волна»: a rotor session that gets more tracks when two are
 * left and is told radioStarted / trackStarted / trackFinished / skip / like, so the wave adapts. A link that doesn't
 * play is fetched once more as MP3 only, then the track is skipped; three failures in a row stop with a message; a 401
 * signs out. The place (queue, track, position, the wave's seeds) is kept in the prefs «yamusic_play» for «Продолжать».
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object YaMusic {
    /** A Yandex item's media id: «ya:» + the track's key (id:albumId). */
    const val PREFIX = "ya:"
    private const val SCHEME = "yandex"
    private const val LINK_TTL_MS = 10 * 60_000L

    /** What the player screen shows beside the player's own state. */
    data class Ui(val wave: Boolean = false, val from: String = "", val loading: Boolean = false, val error: String = "", val preview: Boolean = false)

    val ui = MutableStateFlow(Ui())

    private val known = ConcurrentHashMap<String, YaApi.Track>()
    private val links = ConcurrentHashMap<String, Pair<YaApi.Stream, Long>>()
    // not ConcurrentHashMap.newKeySet(): that is Android 7+, and Android 6 units are in the budget (minSdk 23)
    private val mp3Only: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private val previews: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private val failures = ConcurrentHashMap<String, Throwable>()
    private val retried = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var errors = 0
    private var gen = 0
    private var wave = false
    private var from = ""
    private val seeds = ArrayList<String>()
    private var waveSession = ""
    private var waveBatch = ""
    private val played = ArrayList<String>()
    private var loadingMore = false
    private var waitingMore = false
    private var playId = ""

    fun isYa(mediaId: String?) = mediaId?.startsWith(PREFIX) == true
    fun trackOf(mediaId: String?): YaApi.Track? = mediaId?.takeIf(::isYa)?.let { known[it.removePrefix(PREFIX)] }
    fun current(): YaApi.Track? = trackOf(LocalMusic.state.value.current)
    /** The player holds a Yandex queue now. */
    fun active() = LocalMusic.state.value.yandex
    fun waveOn() = wave && active()

    fun item(t: YaApi.Track): MediaItem {
        known[t.key] = t
        return MediaItem.Builder().setUri(Uri.parse("$SCHEME://track/${t.id}")).setMediaId(PREFIX + t.key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.fullTitle).setArtist(t.artists).setAlbumTitle(t.album)
                .setArtworkUri(YaApi.cover(t.cover, 400).takeIf { it.isNotEmpty() }?.let(Uri::parse)).build())
            .build()
    }

    // ---- the link, on the player's loading thread -------------------------------------------------------

    /**
     * The player's data source asks here before it opens an item: a `yandex://` one becomes the signed link with the token
     * in its header (only to Yandex's hosts), anything else passes as it is. Not found or not allowed isn't retried by the
     * player ([FileNotFoundException]); the network is.
     */
    fun resolver(ctx: Context) = object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme != SCHEME) return dataSpec
            val id = dataSpec.uri.lastPathSegment ?: throw FileNotFoundException("yandex: no id")
            val tok = YaAuth.token(ctx) ?: throw FileNotFoundException("yandex: signed out").also { failures[id] = YaApi.Err(401, "no token") }
            val now = SystemClock.elapsedRealtime()
            val s = links[id]?.takeIf { now - it.second < LINK_TTL_MS }?.first ?: try {
                YaApi.stream(tok, id, id in mp3Only).also { links[id] = it to now }
            } catch (e: Throwable) {
                failures[id] = e
                SsmTrace.log("yandex: no link for $id: ${(e as? YaApi.Err)?.code ?: e.javaClass.simpleName}")
                throw if (e is YaApi.Err && e.code in 400..499) FileNotFoundException("yandex ${e.code}") else e as? IOException ?: IOException(e)
            }
            failures.remove(id)
            if (s.preview) previews += id else previews -= id
            val uri = Uri.parse(s.url)
            val host = uri.host.orEmpty()
            val ours = host.endsWith(".yandex.net") || host.endsWith(".yandex.ru")
            return dataSpec.withUri(uri).let { if (ours) it.withAdditionalHeaders(mapOf("Authorization" to "OAuth $tok")) else it }
        }
    }

    // ---- starting -----------------------------------------------------------------------------------------

    /** The seeds of «Моя волна» with the chosen mood and character. */
    fun moodSeeds(ctx: Context): List<String> {
        val p = YaAuth.prefs(ctx)
        return listOfNotNull(YaApi.WAVE, p.getString("mood", "")?.takeIf { it.isNotEmpty() }?.let { "settingMoodEnergy:$it" },
            p.getString("diversity", "")?.takeIf { it.isNotEmpty() }?.let { "settingDiversity:$it" })
    }

    /** «Моя волна», a new session with [seeds] (the mood and character chosen when none are given). */
    fun playWave(ctx: Context, seeds: List<String> = moodSeeds(ctx)) {
        app = ctx.applicationContext
        val g = ++gen
        wave = true
        from = "Моя волна"
        this.seeds.clear(); this.seeds += seeds
        waveSession = ""; waveBatch = ""
        played.clear()
        waitingMore = false
        loadingMore = false
        errors = 0
        LocalMusic.pause()
        ui.value = Ui(wave = true, from = from, loading = true)
        val tok = YaAuth.token(ctx) ?: run { fail(YaApi.Err(401, "no token")); return }
        loadingMore = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YaApi.waveNew(tok, seeds, emptyList()) } }
            loadingMore = false
            if (g != gen) return@launch
            val b = r.getOrNull()
            if (b == null || b.tracks.isEmpty()) { fail(r.exceptionOrNull() ?: YaApi.Err(404, "empty wave")); return@launch }
            waveSession = b.session; waveBatch = b.batch
            feedback("radioStarted", null, 0.0)
            start(ctx, b.tracks, 0, 0, play = true)
        }
    }

    /** A queue: a playlist, «Мне нравится», an album, an artist, the found tracks. */
    fun playQueue(ctx: Context, tracks: List<YaApi.Track>, index: Int, from: String, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        app = ctx.applicationContext
        gen++
        wave = false
        this.from = from
        seeds.clear()
        errors = 0
        waitingMore = false
        ui.value = Ui(from = from)
        start(ctx, tracks, index, 0, play = true, shuffle = shuffle)
    }

    private fun start(ctx: Context, tracks: List<YaApi.Track>, index: Int, pos: Long, play: Boolean, shuffle: Boolean = false) {
        val p = LocalMusic.player(ctx)
        LocalMusic.startService(ctx)
        retried.clear()
        p.shuffleModeEnabled = shuffle && !wave
        p.repeatMode = if (wave) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        val i = if (shuffle) tracks.indices.random() else index.coerceIn(tracks.indices)
        p.setMediaItems(tracks.map(::item), i, pos)
        p.prepare()
        if (play) LocalMusic.play(p) else p.pause()
        ui.value = ui.value.copy(loading = false, error = "")
        LocalMusic.state.value = LocalMusic.state.value.copy(error = "")
        save(p)
    }

    /**
     * «Продолжать»: what plays goes on; else the saved queue or wave; else a new «Моя волна». False when not signed in.
     */
    fun resume(ctx: Context): Boolean {
        if (!YaAuth.signedIn(ctx)) return false
        app = ctx.applicationContext
        val p = LocalMusic.player(ctx)
        if (isYa(p.currentMediaItem?.mediaId)) { if (!p.isPlaying) LocalMusic.toggle(ctx); return true }
        if (!restore(ctx, play = true)) playWave(ctx)
        return true
    }

    // ---- the player's events (main thread, from LocalMusic) ----------------------------------------------

    /** A track ended or was skipped: the wave is told, the listening reported. */
    fun onDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        val auto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        if (!auto && reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (!auto && old.mediaItemIndex == new.mediaItemIndex) return
        val t = trackOf(old.mediaItem?.mediaId) ?: return
        val sec = if (auto) maxOf(t.durationMs, old.positionMs) / 1000.0 else old.positionMs / 1000.0
        feedback(if (auto) "trackFinished" else "skip", t, sec)
        report(t, sec)
    }

    fun onTransition(p: Player, item: MediaItem?) {
        val t = trackOf(item?.mediaId) ?: return
        playId = UUID.randomUUID().toString()
        retried.remove(t.id)
        ui.value = ui.value.copy(preview = t.id in previews)
        if (!wave) return
        played += t.key
        while (played.size > 40) played.removeAt(0)
        if (p.isPlaying) started(t)
        if (p.mediaItemCount - p.currentMediaItemIndex - 1 <= 2) more(then = false)
    }

    fun onEvents(p: Player, events: Player.Events) {
        if (!isYa(p.currentMediaItem?.mediaId)) return
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && p.isPlaying) {
            trackOf(p.currentMediaItem?.mediaId)?.let(::started)
            errors = 0
            ui.value = ui.value.copy(error = "", preview = trackOf(p.currentMediaItem?.mediaId)?.id?.let { it in previews } == true)
        }
        // the end of the wave's tracks: more of it, then on
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && p.playbackState == Player.STATE_ENDED && wave) more(then = true)
    }

    /** ⏭ at the last track: the wave goes on with its next batch. */
    fun nextAtEnd() { if (waveOn()) more(then = true) }

    /** The player failed on a Yandex item: true when handled here (retry as MP3, skip, sign out). */
    fun onError(p: ExoPlayer, e: PlaybackException): Boolean {
        val t = trackOf(p.currentMediaItem?.mediaId) ?: return false
        val cause = failures[t.id]
        SsmTrace.log("yandex: ${e.errorCodeName} on ${t.id}${cause?.let { " (" + ((it as? YaApi.Err)?.code ?: it.javaClass.simpleName) + ")" }.orEmpty()}")
        if (YaApi.isAuth(cause)) { app?.let(YaAuth::expired); fail(cause); return true }
        if (t.id !in retried && (cause == null || cause !is YaApi.Err || cause.code !in 400..499)) {
            // once more, MP3 only: some AAC links don't play on older units
            retried += t.id
            mp3Only += t.id
            links.remove(t.id)
            p.prepare()
            p.playWhenReady = true
            return true
        }
        if (++errors >= 3) { fail(cause ?: YaApi.Err(502, "playback")); return true }
        scope.launch {
            kotlinx.coroutines.delay(1200)
            if (!isYa(p.currentMediaItem?.mediaId)) return@launch
            if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.playWhenReady = true }
            else if (wave) more(then = true)
        }
        return true
    }

    private fun fail(e: Throwable?) {
        val msg = YaApi.message(e)
        ui.value = ui.value.copy(loading = false, error = msg)
        LocalMusic.state.value = LocalMusic.state.value.copy(error = msg)
    }

    // ---- the wave ----------------------------------------------------------------------------------------

    private fun more(then: Boolean) {
        if (!wave) return
        if (then) waitingMore = true
        if (loadingMore) return
        val c = app ?: return
        val tok = YaAuth.token(c) ?: return
        loadingMore = true
        if (then) ui.value = ui.value.copy(loading = true)
        val ses = waveSession
        val q = played.toList()
        val sd = seeds.toList()
        val g = gen
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (ses.isEmpty()) YaApi.waveNew(tok, sd, q) else YaApi.waveMore(tok, ses, q) } }
            loadingMore = false
            if (g != gen) return@launch
            ui.value = ui.value.copy(loading = false)
            val b = r.getOrNull()
            if (b == null) {
                if (YaApi.isAuth(r.exceptionOrNull())) YaAuth.expired(c)
                if (waitingMore) { waitingMore = false; fail(r.exceptionOrNull()) }
                return@launch
            }
            val fresh = ses.isEmpty()
            waveSession = b.session; waveBatch = b.batch
            if (fresh) feedback("radioStarted", null, 0.0)
            val p = LocalMusic.player(c)
            if (!isYa(p.currentMediaItem?.mediaId)) return@launch
            val have = (0 until p.mediaItemCount).mapNotNull { trackOf(p.getMediaItemAt(it).mediaId)?.id }.toHashSet()
            p.addMediaItems(b.tracks.filter { have.add(it.id) }.map(::item))
            if (waitingMore) {
                waitingMore = false
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); if (p.playbackState == Player.STATE_IDLE) p.prepare(); LocalMusic.play(p) }
            }
        }
    }

    private var startedFor = ""

    /** trackStarted, once for each time a track comes on (it may come on paused and start later). */
    private fun started(t: YaApi.Track) {
        if (startedFor == playId) return
        startedFor = playId
        feedback("trackStarted", t, 0.0)
    }

    private fun feedback(type: String, t: YaApi.Track?, sec: Double) {
        if (!wave || waveSession.isEmpty()) return
        val tok = app?.let(YaAuth::token) ?: return
        val ses = waveSession
        val batch = waveBatch
        scope.launch(Dispatchers.IO) { runCatching { YaApi.waveFeedback(tok, ses, batch, type, t?.key, sec) } }
    }

    private fun report(t: YaApi.Track, sec: Double) {
        val c = app ?: return
        val tok = YaAuth.token(c) ?: return
        if (sec < 1) return
        val uid = YaAuth.uid(c)
        val pid = playId
        val src = if (wave) YaApi.FROM else "carpulse-playlist"
        scope.launch(Dispatchers.IO) { runCatching { YaApi.playAudio(tok, uid, t, src, pid, sec) } }
    }

    // ---- ♥ and 👎 ------------------------------------------------------------------------------------------

    fun toggleLike(ctx: Context) {
        val t = current() ?: return
        setLike(ctx, t, !YaLibrary.isLiked(t.id))
    }

    fun setLike(ctx: Context, t: YaApi.Track, on: Boolean) {
        scope.launch {
            val msg = YaLibrary.setLike(ctx, t, on)
            if (current()?.id == t.id) feedback(if (on) "like" else "unlike", t, 0.0)
            note(msg)
        }
    }

    /** «Не нравится»: the API's dislike (the wave stops offering it), then the next track. */
    fun dislike(ctx: Context) {
        val t = current() ?: return
        scope.launch { if (!YaLibrary.dislike(ctx, t)) note("Не получилось: сервис недоступен") }
        LocalMusic.next(ctx)
    }

    private val artLoading = HashSet<String>()

    /** The cover of the item [mediaId] into the player's state when it has come, if that item still plays. */
    fun loadArt(mediaId: String, cover: String) {
        if (cover.isEmpty() || !artLoading.add(mediaId)) return
        scope.launch {
            val b = YaLibrary.cover(cover, 400)
            artLoading.remove(mediaId)
            if (b != null && LocalMusic.state.value.current == mediaId) LocalMusic.state.value = LocalMusic.state.value.copy(art = b)
        }
    }

    private fun note(text: String) { kg.forester.sg.service.Voice.toast.value = SystemClock.elapsedRealtime() to text }

    // ---- voice --------------------------------------------------------------------------------------------

    /** «Включи Кино»: the best of the search played; the phrase to say (on the main thread). */
    suspend fun playFound(ctx: Context, q: String, focus: MediaFocus?): String {
        val r = YaLibrary.run(ctx) { tok ->
            val s = YaApi.search(tok, q)
            val kind = when (focus) {
                MediaFocus.ARTIST -> "artist"
                MediaFocus.ALBUM -> "album"
                MediaFocus.PLAYLIST -> "playlist"
                MediaFocus.TRACK -> "track"
                else -> s.best
            }
            when {
                kind == "artist" && s.artists.isNotEmpty() -> s.artists[0].name to YaApi.artistTracks(tok, s.artists[0].id)
                kind == "album" && s.albums.isNotEmpty() -> s.albums[0].title to YaApi.albumTracks(tok, s.albums[0].id)
                kind == "playlist" && s.playlists.isNotEmpty() -> s.playlists[0].let { it.title to YaApi.playlistTracks(tok, it.owner, it.kind) }
                s.tracks.isNotEmpty() -> q to s.tracks
                s.artists.isNotEmpty() -> s.artists[0].name to YaApi.artistTracks(tok, s.artists[0].id)
                else -> q to emptyList()
            }
        }
        val (title, tracks) = r.getOrNull() ?: return YaApi.message(r.exceptionOrNull())
        if (tracks.isEmpty()) return "Не нашёл в Яндекс Музыке: $q"
        playQueue(ctx, tracks, 0, title)
        return "Включаю: $title"
    }

    /** «Мне нравится» from the newest one. */
    suspend fun playLiked(ctx: Context, shuffle: Boolean = false): String {
        val r = YaLibrary.likedTracks(ctx, 0)
        val l = r.getOrNull() ?: return YaApi.message(r.exceptionOrNull())
        if (l.isEmpty()) return "В «Мне нравится» пока пусто"
        playQueue(ctx, l, 0, "Мне нравится", shuffle)
        return "Мне нравится"
    }

    // ---- the place (resume) -------------------------------------------------------------------------------

    private fun saved(c: Context) = c.applicationContext.getSharedPreferences("yamusic_play", Context.MODE_PRIVATE)

    /** Kept by LocalMusic.save for a Yandex queue: five behind, sixty ahead, the track and its position. */
    fun save(p: Player) {
        val c = app ?: return
        val i = p.currentMediaItemIndex
        if (i < 0 || !isYa(p.currentMediaItem?.mediaId)) return
        val lo = maxOf(0, i - 5)
        val hi = minOf(p.mediaItemCount, i + 60)
        val q = JSONArray()
        for (k in lo until hi) trackOf(p.getMediaItemAt(k).mediaId)?.let { q.put(it.save()) }
        val o = JSONObject().put("w", wave).put("s", JSONArray(seeds)).put("f", from).put("i", i - lo).put("p", p.currentPosition)
        saved(c).edit().putString("q", o.toString()).putString("queue", q.toString()).apply()
    }

    fun hasSaved(c: Context) = saved(c).contains("q")

    /** The saved queue or wave back into the player; false when nothing was saved. */
    private fun restore(ctx: Context, play: Boolean): Boolean {
        val sp = saved(ctx)
        val o = runCatching { JSONObject(sp.getString("q", null) ?: return false) }.getOrNull() ?: return false
        val q = runCatching { JSONArray(sp.getString("queue", "[]")) }.getOrDefault(JSONArray())
        val tracks = (0 until q.length()).mapNotNull { YaApi.Track.load(q.optJSONObject(it)) }
        val w = o.optBoolean("w", false)
        val sd = o.optJSONArray("s")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (tracks.isEmpty()) { if (w) { playWave(ctx, sd.ifEmpty { moodSeeds(ctx) }); return true }; return false }
        gen++
        wave = w
        from = o.optString("f", "")
        seeds.clear(); seeds += if (w && sd.isEmpty()) listOf(YaApi.WAVE) else sd
        waveSession = ""; waveBatch = ""   // a new session when its tracks run short
        played.clear()
        errors = 0
        waitingMore = false
        ui.value = Ui(wave = w, from = from)
        start(ctx, tracks, o.optInt("i", 0), maxOf(0L, o.optLong("p", 0)), play)
        return true
    }

    /** Signed out: the Yandex queue leaves the player. */
    fun signedOut(c: Context) {
        gen++
        val p = LocalMusic.playerOrNull()
        if (p != null && isYa(p.currentMediaItem?.mediaId)) { p.stop(); p.clearMediaItems() }
        links.clear()
        ui.value = Ui()
    }

    fun forget(c: Context) { saved(c).edit().clear().apply() }
}
