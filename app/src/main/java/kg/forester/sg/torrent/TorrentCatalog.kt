package kg.forester.sg.torrent

import android.content.Context
import android.os.SystemClock
import kg.forester.sg.data.Repo
import kg.forester.sg.video.VideoSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Torrent as a search source over [JacredClient] and a stream source over [TorrEngine]. Separate from [VideoCatalog] on
 * purpose: the Kyrgyz sites group by source and have their own TTL cache, torrents don't (one magnet per click). The
 * «Торрент» section in «Видео» talks to this object alone.
 */
object TorrentCatalog {
    private const val SEARCH_TTL_MS = 2 * 60_000L
    private const val FILES_TIMEOUT_MS = 40_000L

    private val cache = HashMap<String, Pair<List<TorrentRelease>, Long>>()
    @Volatile private var engine: TorrEngine? = null

    /** Lazily initialised engine, started on the first click. */
    fun engine(c: Context): TorrEngine {
        val cur = engine
        if (cur != null) return cur
        return synchronized(this) {
            engine ?: run {
                val cfg = Repo.settings.torrentSettings()
                TorrEngine(c).also {
                    it.settings = cfg
                    it.start()
                    engine = it
                }
            }
        }
    }

    /** jacred fan-out, deduped by btih and sorted by quality then seeders. Cached 2 min per query. */
    suspend fun search(c: Context, query: String): List<TorrentRelease> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        synchronized(cache) { cache[q]?.let { (rs, at) -> if (SystemClock.elapsedRealtime() - at < SEARCH_TTL_MS) return@withContext rs } }
        val hosts = Repo.get(c).jacredHosts()
        val client = JacredClient(hosts)
        val releases = runCatching { client.search(q, exact = false) }.getOrDefault(emptyList())
            .distinctBy { infohash(it.magnet) }
            .sortedWith(compareByDescending<TorrentRelease> { it.quality }.thenByDescending { it.seeders })
        synchronized(cache) { cache[q] = releases to SystemClock.elapsedRealtime() }
        releases
    }

    /** Playable local URL for ExoPlayer. Spawns the engine if needed, waits for file_stats to appear (jacred can list a magnet
     *  with zero seeders; the server hangs on torrent_info until a peer answers). */
    suspend fun stream(c: Context, r: TorrentRelease): String = withContext(Dispatchers.IO) {
        val eng = engine(c)
        if (!eng.ensureRunning()) throw VideoSourceException(FAKE_SOURCE, "торрент-движок не запустился")
        val hash = eng.client.addMagnet(r.magnet, title = r.name)
            ?: throw VideoSourceException(FAKE_SOURCE, "не удалось добавить раздачу")
        var info = eng.client.info(hash)
        val deadline = System.currentTimeMillis() + FILES_TIMEOUT_MS
        while ((info == null || info.files.isEmpty()) && System.currentTimeMillis() < deadline) {
            delay(1_000)
            info = eng.client.info(hash)
        }
        val file = info?.mainVideo
            ?: throw VideoSourceException(FAKE_SOURCE, "в раздаче нет видеофайла (сид есть?)")
        eng.client.streamUrl(hash, file.id, file.path)
    }

    /** btih from a magnet — stable id across trackers. */
    fun infohash(magnet: String): String =
        Regex("btih:([A-Za-z0-9]+)").find(magnet)?.groupValues?.get(1)?.lowercase() ?: magnet

    /** Torrent errors go through VideoSourceException so the existing message() helper formats them. The source label is
     *  the one the user sees; "Торрент" fits on the error card. */
    private val FAKE_SOURCE =
        kg.forester.sg.video.VideoSource.TSKG // VideoSourceException needs an enum; its label is overridden in the UI line

    /** Human-readable error for the «Торрент» section: picks up offline / 403 / no-releases. */
    fun message(e: Throwable?): String = when (e) {
        is VideoSourceException -> "Торрент: ${e.message ?: "не удалось загрузить"}"
        is java.net.UnknownHostException -> "Нет интернета"
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "jacred не отвечает. Попробуйте позже"
        null -> "Ничего не нашлось"
        else -> "Торрент: не удалось загрузить"
    }
}
