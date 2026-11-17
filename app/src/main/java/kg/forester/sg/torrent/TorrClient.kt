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
