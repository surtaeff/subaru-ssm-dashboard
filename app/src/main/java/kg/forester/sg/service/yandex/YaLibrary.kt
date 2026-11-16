package kg.forester.sg.service.yandex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The driver's Yandex Music library for the panel and the player (ported from FreeLite's YaMusicLib): the liked track
 * ids (the ♥ of the player), «Мне нравится» in pages of 100, own and personal playlists, cached in memory for a few
 * minutes; covers through a 4 MB picture cache. Suspending calls: the network runs on the IO pool, a 401 signs out.
 */
object YaLibrary {
    private const val TTL = 5 * 60_000L

    /** The liked track ids (without the album). */
    val liked = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var likedKeys: List<String> = emptyList()
    @Volatile private var likedAt = 0L
    @Volatile private var lists: Pair<List<YaApi.Playlist>, List<YaApi.Playlist>>? = null
    @Volatile private var listsAt = 0L
    @Volatile private var gen = 0

    fun reset() {
        gen++
        liked.value = emptySet()
        likedKeys = emptyList()
        likedAt = 0
        lists = null
        listsAt = 0
        covers.evictAll()
    }

    fun isLiked(trackId: String?) = trackId != null && trackId in liked.value

    /** Runs [job] with the token on the IO pool; null and the error when it failed (a 401 also signs out). */
    suspend fun <T> run(ctx: Context, job: (String) -> T): Result<T> {
        val c = ctx.applicationContext
        val tok = YaAuth.token(c) ?: return Result.failure(YaApi.Err(401, "no token"))
        val g = gen
        val r = withContext(Dispatchers.IO) { runCatching { job(tok) } }
        if (YaApi.isAuth(r.exceptionOrNull()) && g == gen) withContext(Dispatchers.Main) { YaAuth.expired(c) }
        return r
    }

    /** The liked ids, read again when older than the TTL (cheap: ids only). */
    suspend fun ensureLikes(ctx: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - likedAt < TTL) return
        if (!YaAuth.signedIn(ctx)) return
        likedAt = System.currentTimeMillis()
        val uid = YaAuth.uid(ctx)
        val g = gen
        run(ctx) { YaApi.likedKeys(it, uid) }.getOrNull()?.let { k ->
            if (g != gen) return
            likedKeys = k
            liked.value = k.map(YaApi::idOf).toSet()
        }
    }

    fun likedCount() = likedKeys.size

    /** «Мне нравится», tracks [from] to [from] + 100. */
    suspend fun likedTracks(ctx: Context, from: Int): Result<List<YaApi.Track>> {
        if (from == 0 || likedKeys.isEmpty()) ensureLikes(ctx, force = true)
        val page = likedKeys.drop(from).take(100)
        if (page.isEmpty()) return Result.success(emptyList())
        return run(ctx) { tok -> YaApi.tracks(tok, page).filter { it.available } }
    }

    /** (personal, own) playlists, cached. */
    suspend fun playlists(ctx: Context, force: Boolean = false): Result<Pair<List<YaApi.Playlist>, List<YaApi.Playlist>>> {
        lists?.takeIf { !force && System.currentTimeMillis() - listsAt < TTL }?.let { return Result.success(it) }
        val uid = YaAuth.uid(ctx)
        return run(ctx) { tok ->
            val personal = try { YaApi.personal(tok) } catch (e: YaApi.Err) { if (e.auth) throw e; emptyList() } catch (e: Exception) { emptyList() }
            personal to YaApi.playlists(tok, uid)
        }.onSuccess { lists = it; listsAt = System.currentTimeMillis() }
    }

    /** ♥ on or off (shown at once, taken back when the API refuses). The message to show. */
    suspend fun setLike(ctx: Context, t: YaApi.Track, on: Boolean): String {
        mark(t, on)
        val uid = YaAuth.uid(ctx)
        val r = run(ctx) { YaApi.like(it, uid, t.key, on) }
        if (r.isFailure) { mark(t, !on); return YaApi.message(r.exceptionOrNull()) }
        return if (on) "Добавлено в «Мне нравится»" else "Убрано из «Мне нравится»"
    }

    /** «Не нравится»: the wave stops offering it. */
    suspend fun dislike(ctx: Context, t: YaApi.Track): Boolean {
        if (isLiked(t.id)) mark(t, false)
        val uid = YaAuth.uid(ctx)
        return run(ctx) { YaApi.dislike(it, uid, t.key) }.isSuccess
    }

    private fun mark(t: YaApi.Track, on: Boolean) {
        likedKeys = if (on) listOf(t.key) + likedKeys else likedKeys.filter { YaApi.idOf(it) != t.id }
        liked.value = if (on) liked.value + t.id else liked.value - t.id
    }

    // ---- covers -------------------------------------------------------------------------------------------

    private val covers = object : LruCache<String, Bitmap>(4 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cachedCover(uri: String, px: Int): Bitmap? = YaApi.cover(uri, px).takeIf { it.isNotEmpty() }?.let { covers.get(it) }

    /** The cover of [uri] at [px]×[px] (Yandex scales it), from the cache or downloaded; blocking. */
    fun coverBlocking(uri: String, px: Int): Bitmap? {
        val url = YaApi.cover(uri, px).ifEmpty { return null }
        covers.get(url)?.let { return it }
        val bytes = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", YaApi.UA)
                if (c.responseCode >= 400) null else c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }.getOrNull()?.takeIf { it.size in 100..(2 shl 20) } ?: return null
        val b = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull() ?: return null
        covers.put(url, b)
        return b
    }

    suspend fun cover(uri: String, px: Int): Bitmap? = cachedCover(uri, px) ?: withContext(Dispatchers.IO) { coverBlocking(uri, px) }
}
