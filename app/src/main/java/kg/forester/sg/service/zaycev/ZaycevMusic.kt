package kg.forester.sg.service.zaycev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.video.VideoNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * zaycev.net in the app's own player ([LocalMusic]), like Yandex Music: one player, so the media session, the steering
 * wheel keys, the mini player and the equalizer work as with files. An item's address is «zaycev://track/<id>»; its link
 * is asked for when the player opens it ([resolver]) and kept a quarter of an hour, so a queue saved yesterday still plays.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object ZaycevMusic {
    const val PREFIX = "zy:"
    const val SCHEME = "zaycev"
    private const val LINK_TTL_MS = 15 * 60_000L

    data class Ui(val from: String = "", val loading: Boolean = false, val error: String = "")

    val ui = MutableStateFlow(Ui())

    private val known = ConcurrentHashMap<Long, ZaycevApi.Track>()
    private val hashes = ConcurrentHashMap<Long, String>()
    private val links = LinkCache(LINK_TTL_MS)
    private val failures = ConcurrentHashMap<Long, Throwable>()
    private val retried = HashSet<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var errors = 0
    private var from = ""

    fun isZy(mediaId: String?) = mediaId?.startsWith(PREFIX) == true
    fun trackOf(mediaId: String?): ZaycevApi.Track? = mediaId?.takeIf(::isZy)?.removePrefix(PREFIX)?.toLongOrNull()?.let { known[it] }
    fun active() = LocalMusic.state.value.zaycev

    fun item(t: ZaycevApi.Track): MediaItem {
        known[t.id] = t
        return MediaItem.Builder().setUri(Uri.parse("$SCHEME://track/${t.id}")).setMediaId(PREFIX + t.key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist)
                .setArtworkUri(t.cover.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build())
            .build()
    }

    // ---- lists: a page and which of its tracks can be played -----------------------------------------------------------

    /**
     * [load] a page and mark its blocked tracks, asking for all its hashes in one go (kept for playing). Without that
     * answer the tracks stay as the list said; the link is asked for again when one is played.
     */
    suspend fun page(load: () -> ZaycevApi.Page): Result<ZaycevApi.Page> = withContext(Dispatchers.IO) {
        runCatching {
            val p = load()
            val h = runCatching { ZaycevApi.hashes(p.tracks.filter { it.playable }.map { it.id }) }.getOrDefault(emptyMap())
            hashes.putAll(h)
            p.copy(tracks = p.tracks.map { t -> if (h[t.id] == "") t.copy(playable = false) else t })
        }
    }

    // ---- playing ----------------------------------------------------------------------------------------------------

    fun resolver() = object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme != SCHEME) return dataSpec
            val id = dataSpec.uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("zaycev: no id")
            val now = SystemClock.elapsedRealtime()
            val url = links.get(id, now) ?: try {
                val h = hashes[id] ?: ZaycevApi.hashes(listOf(id))[id]?.also { hashes[id] = it } ?: throw ZaycevApi.Err(404, "no hash")
                if (h.isEmpty()) throw ZaycevApi.Err(ZaycevApi.BLOCKED, "blocked")
                ZaycevApi.stream(h).also { links.put(id, it, now) }
            } catch (e: Throwable) {
                failures[id] = e
                SsmTrace.log("zaycev: no link for $id: ${(e as? ZaycevApi.Err)?.code ?: e.javaClass.simpleName}")
                throw if (e is ZaycevApi.Err && e.code in 400..499) FileNotFoundException("zaycev ${e.code}") else e as? IOException ?: IOException(e)
            }
            failures.remove(id)
            // the site's own player sends a browser's name; the CDN takes any, this keeps it the same as the API calls
            return dataSpec.withUri(Uri.parse(url)).withAdditionalHeaders(mapOf("User-Agent" to VideoNet.UA))
        }
    }

    /** Plays [tracks] from [index] (the blocked ones left out), the list being what ⏮ ⏭ go through. */
    fun playQueue(ctx: Context, tracks: List<ZaycevApi.Track>, index: Int, from: String, shuffle: Boolean = false) {
        val chosen = tracks.getOrNull(index)
        val list = tracks.filter { it.playable }
        // nothing to play: said here only, what else plays goes on
        if (list.isEmpty()) { ui.value = ui.value.copy(error = ZaycevApi.message(ZaycevApi.Err(ZaycevApi.BLOCKED, "blocked"))); return }
        app = ctx.applicationContext
        this.from = from
        errors = 0
        ui.value = Ui(from = from)
        val i = chosen?.let { c -> list.indexOfFirst { it.id == c.id } }?.takeIf { it >= 0 } ?: 0
        start(ctx, list, i, 0, play = true, shuffle = shuffle)
    }

    private fun start(ctx: Context, tracks: List<ZaycevApi.Track>, index: Int, pos: Long, play: Boolean, shuffle: Boolean = false) {
        val p = LocalMusic.player(ctx)
        LocalMusic.startService(ctx)
        retried.clear()
        p.shuffleModeEnabled = shuffle
        p.repeatMode = Player.REPEAT_MODE_ALL
        val i = if (shuffle) tracks.indices.random() else index.coerceIn(tracks.indices)
        p.setMediaItems(tracks.map(::item), i, pos)
        p.prepare()
        if (play) LocalMusic.play(p) else p.pause()
        LocalMusic.state.value = LocalMusic.state.value.copy(error = "")
        save(p)
    }

    /** ▶ with nothing of zaycev.net in the player: what played last, else today's chart. */
    fun resume(ctx: Context): Boolean {
        app = ctx.applicationContext
        val p = LocalMusic.player(ctx)
        if (isZy(p.currentMediaItem?.mediaId)) { if (!p.isPlaying) LocalMusic.toggle(ctx); return true }
        if (restore(ctx, play = true)) return true
        ui.value = Ui(from = "Популярное", loading = true)
        scope.launch {
            val r = page { ZaycevApi.top("day") }
            val l = r.getOrNull()?.tracks.orEmpty()
            if (l.none { it.playable }) { fail(r.exceptionOrNull() ?: ZaycevApi.Err(404, "empty")); return@launch }
            playQueue(ctx, l, 0, "Популярное")
        }
        return true
    }

    fun onTransition(item: MediaItem?) {
        val t = trackOf(item?.mediaId) ?: return
        retried.remove(t.id)
    }

    fun onEvents(p: Player, events: Player.Events) {
        if (!isZy(p.currentMediaItem?.mediaId)) return
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && p.isPlaying) {
            errors = 0
            if (ui.value.error.isNotEmpty() || ui.value.loading) ui.value = ui.value.copy(error = "", loading = false)
        }
    }

    /**
     * A zaycev.net track that doesn't play: once more with a new link (it may have expired), then on to the next one;
     * three in a row and it stops with the reason. False when it isn't one of ours.
     */
    fun onError(p: ExoPlayer, e: PlaybackException): Boolean {
        val t = trackOf(p.currentMediaItem?.mediaId) ?: return false
        val cause = failures[t.id]
        SsmTrace.log("zaycev: ${e.errorCodeName} on ${t.id}${cause?.let { " (" + ((it as? ZaycevApi.Err)?.code ?: it.javaClass.simpleName) + ")" }.orEmpty()}")
        val blocked = (cause as? ZaycevApi.Err)?.code == ZaycevApi.BLOCKED
        if (!blocked && t.id !in retried) {
            retried += t.id
            links.drop(t.id)
            hashes.remove(t.id)
            p.prepare()
            p.playWhenReady = true
            return true
        }
        if (++errors >= 3) { fail(cause ?: ZaycevApi.Err(502, "playback")); return true }
        ui.value = ui.value.copy(error = ZaycevApi.message(cause ?: ZaycevApi.Err(502, "playback")))
        scope.launch {
            delay(1200)
            if (!isZy(p.currentMediaItem?.mediaId)) return@launch
            if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.playWhenReady = true }
        }
        return true
    }

    private fun fail(e: Throwable?) {
        val msg = ZaycevApi.message(e)
        ui.value = ui.value.copy(loading = false, error = msg)
        LocalMusic.state.value = LocalMusic.state.value.copy(error = msg)
    }

    // ---- covers: the lists' small ones, the track page's bigger one for the player ------------------------------------

    private val covers = object : LruCache<String, Bitmap>(3 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val big = ConcurrentHashMap<Long, String>()
    private val artLoading = HashSet<String>()

    fun cachedCover(url: String): Bitmap? = url.takeIf { it.isNotEmpty() }?.let { covers.get(it) }

    /** The cover in the player: the 250 px one once it has come, else the list's. */
    fun cachedArt(t: ZaycevApi.Track): Bitmap? = big[t.id]?.let(::cachedCover) ?: cachedCover(t.cover)

    suspend fun cover(url: String): Bitmap? = cachedCover(url) ?: withContext(Dispatchers.IO) { coverBlocking(url) }

    private fun coverBlocking(url: String): Bitmap? {
        if (url.isEmpty()) return null
        covers.get(url)?.let { return it }
        val bytes = runCatching { VideoNet.bytes(url, 8000, 10_000, 2 shl 20) }.getOrNull()?.takeIf { it.size >= 100 } ?: return null
        val b = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        covers.put(url, b)
        return b
    }

    /** The playing track's cover from the network, shown if that track still plays when it comes. */
    fun loadArt(mediaId: String, t: ZaycevApi.Track) {
        if (!artLoading.add(mediaId)) return
        scope.launch {
            val b = withContext(Dispatchers.IO) {
                val url = big[t.id] ?: runCatching { ZaycevApi.bigCover(t.id) }.getOrNull()?.also { big[t.id] = it }
                url?.let(::coverBlocking) ?: coverBlocking(t.cover)
            }
            artLoading.remove(mediaId)
            if (b != null && LocalMusic.state.value.current == mediaId) LocalMusic.state.value = LocalMusic.state.value.copy(art = b)
        }
    }

    // ---- the queue kept for the next start ----------------------------------------------------------------------------

    private fun saved(c: Context) = c.applicationContext.getSharedPreferences("zaycev_play", Context.MODE_PRIVATE)

    fun save(p: Player) {
        val c = app ?: return
        val i = p.currentMediaItemIndex
        if (i < 0 || !isZy(p.currentMediaItem?.mediaId)) return
        val lo = maxOf(0, i - 20)
        val hi = minOf(p.mediaItemCount, i + 100)
        val q = JSONArray()
        var at = 0
        for (k in lo until hi) {
            val t = trackOf(p.getMediaItemAt(k).mediaId) ?: continue
            if (k < i) at++
            q.put(t.save())
        }
        val o = JSONObject().put("f", from).put("i", at).put("p", p.currentPosition).put("s", p.shuffleModeEnabled)
        saved(c).edit().putString("q", o.toString()).putString("queue", q.toString()).apply()
    }

    private fun restore(ctx: Context, play: Boolean): Boolean {
        val sp = saved(ctx)
        val o = runCatching { JSONObject(sp.getString("q", null) ?: return false) }.getOrNull() ?: return false
        val q = runCatching { JSONArray(sp.getString("queue", "[]")) }.getOrDefault(JSONArray())
        val tracks = (0 until q.length()).mapNotNull { ZaycevApi.Track.load(q.optJSONObject(it)) }
        if (tracks.isEmpty()) return false
        from = o.optString("f", "")
        errors = 0
        ui.value = Ui(from = from)
        val i = o.optInt("i", 0).coerceIn(tracks.indices)
        start(ctx, tracks, i, maxOf(0L, o.optLong("p", 0)), play)
        // the order it was in: start() deals a new one only when asked to shuffle
        LocalMusic.player(ctx).shuffleModeEnabled = o.optBoolean("s", false)
        return true
    }
}
