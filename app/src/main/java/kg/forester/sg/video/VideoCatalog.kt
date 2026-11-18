package kg.forester.sg.video

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Both Kyrgyz sites behind one suspend API (after FreeLite's VideoCatalog.java). One site failing never hides the
 * other. Home rows are kept 10 min, details 30 min (the player and the title page share them). A 403 from either site
 * tells [VideoRegion] the driver is outside Kyrgyzstan.
 */
object VideoCatalog {
    private const val HOME_TTL_MS = 10 * 60_000L
    private const val DETAILS_TTL_MS = 30 * 60_000L

    private val home = HashMap<VideoSource, Pair<List<VideoRow>, Long>>()
    private val details = object : LinkedHashMap<String, Pair<VideoDetails, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<VideoDetails, Long>>?) = size > 16
    }

    class Result<T>(val value: T?, val error: Throwable?)

    /** Both sites' rows: each its own result, so one failing still shows the other. */
    suspend fun home(c: Context, force: Boolean = false): Map<VideoSource, Result<List<VideoRow>>> = coroutineScope {
        VideoSource.entries.associateWith { s -> async { load(c) { homeOf(s, force) } } }.mapValues { it.value.await() }
    }

    private fun homeOf(s: VideoSource, force: Boolean): List<VideoRow> {
        synchronized(home) { home[s]?.let { (rows, at) -> if (!force && SystemClock.elapsedRealtime() - at < HOME_TTL_MS) return rows } }
        val rows = when (s) { VideoSource.TSKG -> TsKg.home(); VideoSource.OCKG -> OcKg.home() }
        synchronized(home) { home[s] = rows to SystemClock.elapsedRealtime() }
        return rows
    }

    suspend fun search(c: Context, query: String): Map<VideoSource, Result<List<VideoCard>>> = coroutineScope {
        VideoSource.entries.associateWith { s ->
            async { load(c) { when (s) { VideoSource.TSKG -> TsKg.search(query); VideoSource.OCKG -> OcKg.search(query) } } }
        }.mapValues { it.value.await() }
    }

    suspend fun details(c: Context, key: String): Result<VideoDetails> = load(c) {
        synchronized(details) { details[key]?.let { (d, at) -> if (SystemClock.elapsedRealtime() - at < DETAILS_TTL_MS) return@load d } }
        val (s, id) = parseVideoKey(key) ?: throw IllegalArgumentException("bad key $key")
        val d = when (s) { VideoSource.TSKG -> TsKg.details(id); VideoSource.OCKG -> OcKg.details(id) }
        synchronized(details) { details[key] = d to SystemClock.elapsedRealtime() }
        d
    }

    suspend fun stream(c: Context, d: VideoDetails, e: VideoEpisode): Result<VideoStream> = load(c) {
        when (d.card.source) {
            VideoSource.TSKG -> TsKg.stream(e.id)
            VideoSource.OCKG -> OcKg.stream(e.id, d.card.id)
        }
    }

    private suspend fun <T> load(c: Context, block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result(block(), null)
        } catch (e: Throwable) {
            if (e is VideoSourceException && e.blocked) VideoRegion.blockedSeen(c)
            Result(null, e)
        }
    }

    /** What the driver reads instead of an exception. */
    fun message(e: Throwable?): String = when {
        e is VideoSourceException && e.blocked ->
            "${e.source.label} открывается только из Кыргызстана. В других странах смотрите YouTube и приложения кинотеатров: Настройки → Функции → «Регион видео»."
        e is VideoSourceException && e.cause is java.net.UnknownHostException -> "Нет интернета"
        e is VideoSourceException && (e.cause is java.net.SocketTimeoutException || e.cause is java.net.ConnectException) ->
            "${e.source.label} не отвечает. Попробуйте позже"
        e is VideoSourceException -> "${e.source.label}: не удалось загрузить"
        e is java.net.UnknownHostException -> "Нет интернета"
        else -> "Не удалось загрузить"
    }
}
