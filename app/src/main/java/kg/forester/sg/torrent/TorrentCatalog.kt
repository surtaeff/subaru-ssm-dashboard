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

    /** jacred fan-out, deduped by btih and sorted for a head unit ([headUnitOrder]). Cached 2 min per query. */
    suspend fun search(c: Context, query: String): List<TorrentRelease> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        synchronized(cache) { cache[q]?.let { (rs, at) -> if (SystemClock.elapsedRealtime() - at < SEARCH_TTL_MS) return@withContext rs } }
        val hosts = Repo.settings.jacredHosts()
        val client = JacredClient(hosts)
        val releases = runCatching { client.search(q, exact = false) }.getOrDefault(emptyList())
            .distinctBy { infohash(it.magnet) }
            .sortedWith(headUnitOrder)
        synchronized(cache) { cache[q] = releases to SystemClock.elapsedRealtime() }
        releases
    }

    /** Playable local URL for ExoPlayer. Spawns the engine if needed, waits for file_stats to appear (jacred can list a magnet
     *  with zero seeders; the server hangs on torrent_info until a peer answers). */
    /** A release added to the engine: its hash and the episodes (one for a film), for [episodeUrl]. */
    data class Opened(val hash: String, val episodes: List<TorrClient.TorrFile>)

    /** Adds [r] and waits for its file list (see [stream]); the episodes come back to pick from. */
    suspend fun open(c: Context, r: TorrentRelease): Opened = withContext(Dispatchers.IO) {
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
        val eps = info?.episodes.orEmpty()
        if (eps.isEmpty()) throw VideoSourceException(FAKE_SOURCE, "в раздаче нет видеофайла (сид есть?)")
        Opened(hash, eps)
    }

    fun episodeUrl(c: Context, hash: String, f: TorrClient.TorrFile): String = engine(c).client.streamUrl(hash, f.id, f.path)

    /**
     * The player closed: the release leaves the engine, so it neither downloads nor seeds behind the driver's back on a
     * car's mobile data (it stayed and went on after the player closed, 2026-11-18).
     */
    fun close(c: Context, hash: String) {
        val eng = engine(c)
        // and its pieces leave the unit's memory: the cache folder is wiped (the owner: no leftovers on the head unit)
        Thread({ runCatching { eng.client.remove(hash) }; runCatching { eng.clearCache() } }, "torrent close").start()
    }

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

    /**
     * What a head unit plays best first: 1080p and 720p (4K is too heavy for its decoder and its network, and an 82 GB
     * 2160p release on top timed out on the emulator, 2026-11-18), then by seeders; releases with no seeders last.
     */
    val headUnitOrder: Comparator<TorrentRelease> get() = orderFor(Codecs.caps)

    /**
     * With what this unit decodes: a release it can't play goes down (HEVC 10 bit stalled for good on the emulator, and
     * most head units decode no 10-bit or HDR at all), then 1080p/720p, then the seeders.
     */
    fun orderFor(caps: Codecs.Caps): Comparator<TorrentRelease> =
        compareByDescending<TorrentRelease> { it.seeders > 0 }
            .thenBy { Codecs.penalty(it.title + " " + it.name, caps) }
            .thenBy { when (it.quality) { 1080 -> 0; 720 -> 1; in 481..719 -> 2; 2160 -> 4; else -> 3 } }
            .thenByDescending { it.seeders }

    /** Seeders and peers the engine is connected to for [hash] now; null when the engine isn't up. Blocking-safe. */
    suspend fun peers(c: Context, hash: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        runCatching { engine(c).client.info(hash) }.getOrNull()?.let { it.seeders to it.activePeers }
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
        is VideoSourceException -> if (e.blocked) "Торрент: раздачи недоступны в вашей стране" else "Торрент: не удалось загрузить"
        is java.net.UnknownHostException -> "Нет интернета"
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "jacred не отвечает. Попробуйте позже"
        null -> "Ничего не нашлось"
        else -> "Торрент: не удалось загрузить"
    }
}
