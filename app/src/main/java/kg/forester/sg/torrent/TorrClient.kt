package kg.forester.sg.torrent

import kg.forester.sg.video.VideoNet
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP wrapper around TorrServer MatriX (127.0.0.1:8090). Endpoints and field names are the ones confirmed live on a
 * head unit: see docs/torrent-streaming-plan.md («ПОДТВЕРЖДЕНО на ТВ»). The server speaks JSON, and everything
 * synchronous: callers run it inside an IO coroutine, as the rest of the video sources do.
 */
/** Episode numbers from a file name: «S01E02», «1x02», «Серия 2», «02. Name», else the name in natural order. */
object Episodes {
    private val SE = Regex("""[Ss](\d{1,2})[ ._-]?[EeЕе](\d{1,3})""")
    private val X = Regex("""(?<!\d)(\d{1,2})[xXхХ](\d{1,3})(?!\d)""")
    private val EP = Regex("""(?:серия|серии|episode|ep|эпизод)[ ._-]*(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val SEASON = Regex("""(?:сезон|season)[ ._-]*(\d{1,2})|(\d{1,2})[ ._-]*сезон|[Ss](\d{1,2})(?![\dEeЕе])""", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("""^(\d{1,3})(?!\d)""")

    /** (season, episode) when the name tells; season null when only the episode is known. */
    fun numbers(path: String): Pair<Int?, Int>? {
        val name = path.substringAfterLast('/')
        SE.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        X.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val season = SEASON.find(path)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt()
        EP.find(name)?.let { return season to it.groupValues[1].toInt() }
        LEAD.find(name)?.let { return season to it.groupValues[1].toInt() }
        return null
    }

    /** «Сезон 1 · Серия 2», «Серия 2», else the file name without its extension. */
    fun label(path: String): String {
        val n = numbers(path) ?: return path.substringAfterLast('/').substringBeforeLast('.')
        return listOfNotNull(n.first?.let { "Сезон $it" }, "Серия ${n.second}").joinToString(" · ")
    }

    private val NUM = Regex("""\d+""")
    /** «Episode 2» before «Episode 10». */
    private fun natural(a: String, b: String): Int {
        val ka = NUM.replace(a.lowercase()) { it.value.padStart(6, '0') }
        val kb = NUM.replace(b.lowercase()) { it.value.padStart(6, '0') }
        return ka.compareTo(kb)
    }

    val order: Comparator<TorrClient.TorrFile> = Comparator { x, y ->
        val nx = numbers(x.path); val ny = numbers(y.path)
        if (nx != null && ny != null) {
            val s = (nx.first ?: 0).compareTo(ny.first ?: 0)
            if (s != 0) s else nx.second.compareTo(ny.second).takeIf { it != 0 } ?: natural(x.path, y.path)
        } else natural(x.path, y.path)
    }
}

class TorrClient(val baseUrl: String = DEFAULT_BASE_URL) {

    /** File inside a torrent; [id] is the `index` for a stream URL. */
    data class TorrFile(val id: Int, val path: String, val length: Long) {
        val isVideo: Boolean get() = path.substringAfterLast('.', "").lowercase() in VIDEO_EXT

        companion object {
            private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "ts", "mov", "webm", "m4v", "flv", "mpg", "mpeg", "wmv")
        }
    }

    data class TorrInfo(
        val hash: String,
        val name: String,
        val statString: String,
        val torrentSize: Long,
        val activePeers: Int,
        val seeders: Int,
        val files: List<TorrFile>,
    ) {
        /** The largest video file: usually the film or an episode. */
        val mainVideo: TorrFile? get() = files.filter { it.isVideo }.maxByOrNull { it.length }

        /**
         * The series' episodes in watching order (season, then episode, else the names in natural order); samples and
         * trailers (under a tenth of the largest file) left out. One entry for a film.
         */
        val episodes: List<TorrFile> get() {
            val videos = files.filter { it.isVideo }
            val big = videos.maxOfOrNull { it.length } ?: return emptyList()
            return videos.filter { it.length >= big / 10 }.sortedWith(Episodes.order)
        }
    }

    /** The server's version, e.g. "MatriX.148", or null when the process is not answering yet. */
    fun echo(): String? = runCatching { VideoNet.get("$baseUrl/echo", 2_000).trim() }.getOrNull()

    fun isUp(): Boolean = !echo().isNullOrBlank()

    /** Add a magnet / torrent link. `save_to_db=false`: temporary, gone after the engine restarts. */
    fun addMagnet(link: String, title: String = "", saveToDb: Boolean = false): String? {
        val body = JSONObject()
            .put("action", "add")
            .put("link", link)
            .put("title", title)
            .put("save_to_db", saveToDb)
            .toString()
        val resp = runCatching { post("$baseUrl/torrents", body) }.getOrNull() ?: return null
        return runCatching { JSONObject(resp).optString("hash").takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun info(hash: String): TorrInfo? {
        val body = JSONObject().put("action", "get").put("hash", hash).toString()
        val resp = runCatching { post("$baseUrl/torrents", body) }.getOrNull() ?: return null
        val o = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        val filesArr = o.optJSONArray("file_stats") ?: JSONArray()
        val files = (0 until filesArr.length()).mapNotNull { i ->
            val fo = filesArr.optJSONObject(i) ?: return@mapNotNull null
            val id = fo.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val path = fo.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val len = fo.optLong("length", 0L)
            TorrFile(id, path, len)
        }
        return TorrInfo(
            hash = o.optString("hash").ifBlank { hash },
            name = o.optString("name"),
            statString = o.optString("stat_string"),
            torrentSize = o.optLong("torrent_size", 0L),
            activePeers = o.optInt("active_peers", 0),
            seeders = o.optInt("connected_seeders", 0),
            files = files,
        )
    }

    fun remove(hash: String) {
        val body = JSONObject().put("action", "rem").put("hash", hash).toString()
        runCatching { post("$baseUrl/torrents", body) }
    }

    /** Apply settings. [sets] is the raw JSON object of fields (without the `{"action":"set","sets":…}` wrapper). */
    fun applySettings(sets: String): Boolean =
        runCatching { post("$baseUrl/settings", """{"action":"set","sets":$sets}""") }.isSuccess

    /** Playable URL for ExoPlayer. [index] = [TorrFile.id]; the server handles Range on it (206 for seeking). */
    fun streamUrl(hash: String, index: Int, name: String = "video"): String {
        val safe = name.substringAfterLast('/').ifBlank { "video" }
        return "$baseUrl/stream/${VideoNet.seg(safe)}?link=$hash&index=$index&play"
    }

    /** POST a JSON body without going through VideoNet.get (which does not accept bodies). */
    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${URL(url).path}")
            val stream = c.inputStream
            return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            runCatching { c.disconnect() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8090
        const val DEFAULT_BASE_URL = "http://127.0.0.1:$DEFAULT_PORT"

        // The engine runs locally and answers fast; even a slow start is handled by the watchdog's retry.
        private const val CONNECT_MS = 2_000
        private const val READ_MS = 15_000
    }
}
