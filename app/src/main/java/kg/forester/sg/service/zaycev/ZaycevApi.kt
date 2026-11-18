package kg.forester.sg.service.zaycev

import kg.forester.sg.video.VideoNet
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * zaycev.net without an account: the JSON its own site asks for under /api/external (no token, no cookie, no signature).
 * A list (search, the chart, the new tracks) gives ids with their names and covers; a track plays in two steps:
 * POST track/filezmeta with the ids gives each one's streaming hash (false when it is blocked, a hash that stays the same),
 * GET track/play/{hash} gives a dl.zaycev.net link that redirects to the CDN's mp3 (Range works, any User-Agent).
 * The CDN's address carries an hour it is good until, so a link is asked for when it is played, not kept with the queue.
 */
object ZaycevApi {
    const val BASE = "https://zaycev.net/api/external/"
    /** Search gives at most 20 a page whatever is asked; the chart and the new tracks 50. */
    const val SEARCH_LIMIT = 20
    const val LIST_LIMIT = 50

    class Err(val code: Int, msg: String) : IOException(msg)

    /** One track; [playable] false when the site marks it blocked (the rights holder, the country). */
    data class Track(
        val id: Long, val title: String, val artist: String, val durationMs: Long, val cover: String,
        val playable: Boolean = true, val explicit: Boolean = false,
    ) {
        val key: String get() = id.toString()

        fun save(): JSONObject = JSONObject().put("id", id).put("t", title).put("a", artist).put("d", durationMs).put("c", cover)

        companion object {
            fun load(o: JSONObject?): Track? {
                o ?: return null
                val id = o.optLong("id", 0)
                if (id <= 0) return null
                return Track(id, o.optString("t"), o.optString("a"), o.optLong("d", 0), o.optString("c"))
            }

            /** A track of a list's tracksInfo; one without a name is of no use on the screen. */
            fun parse(id: Long, o: JSONObject?): Track? {
                o ?: return null
                val title = o.optString("track").trim()
                val artist = o.optString("artistName").trim()
                if (id <= 0 || (title.isEmpty() && artist.isEmpty())) return null
                val cover = o.optString("imageJpg").ifEmpty { o.optString("imageWebp") }.takeIf { it.startsWith("http") }.orEmpty()
                return Track(id, title, artist, duration(o.opt("duration")), cover,
                    playable = o.optBoolean("playbackEnabled", true), explicit = o.optBoolean("explicit", false))
            }
        }
    }

    /** A page of a list. [fallback]: a search that found nothing, the site's answer is its popular tracks instead. */
    data class Page(val tracks: List<Track>, val page: Int, val pages: Int, val fallback: Boolean = false) {
        val more: Boolean get() = page < pages && tracks.isNotEmpty()
    }

    /** "02:43", "1:02:03" or seconds as a number; 0 when it can't be read. */
    fun duration(v: Any?): Long {
        if (v is Number) return v.toLong() * 1000
        val s = (v as? String)?.trim().orEmpty()
        if (s.isEmpty()) return 0
        s.toLongOrNull()?.let { return it * 1000 }
        val parts = s.split(':').map { it.trim().toLongOrNull() ?: return 0 }
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    /** A list's answer: the ids in [trackIds] give the order (the info is an object, its keys in no useful order). */
    fun parsePage(json: String): Page {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Page(emptyList(), 1, 1)
        val info = o.optJSONObject("tracksInfo") ?: JSONObject()
        val ids = o.optJSONArray("trackIds") ?: JSONArray()
        val seen = HashSet<Long>()
        val out = ArrayList<Track>()
        for (i in 0 until ids.length()) {
            val id = ids.optLong(i, 0)
            if (id <= 0 || !seen.add(id)) continue
            Track.parse(id, info.optJSONObject(id.toString()))?.let(out::add)
        }
        val page = o.optInt("page", 1).coerceAtLeast(1)
        return Page(out, page, o.optInt("pagesCount", page).coerceAtLeast(page), o.optBoolean("popularInSearch", false))
    }

    /** filezmeta's answer: id → its streaming hash, "" for a blocked one; ids it didn't mention are left out. */
    fun parseHashes(json: String): Map<Long, String> {
        val arr = runCatching { JSONObject(json).optJSONArray("tracks") }.getOrNull() ?: return emptyMap()
        val out = HashMap<Long, String>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optLong("id", 0)
            if (id <= 0) continue
            val h = t.opt("streaming")
            out[id] = if (h is String && h.isNotBlank() && h != "false") h.trim() else ""
        }
        return out
    }

    /** track/play's answer: the link, null when there is none. */
    fun parsePlayUrl(json: String): String? =
        runCatching { JSONObject(json).optString("url") }.getOrNull()?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** The track page's bigger cover (250 px; the lists only have 100). */
    fun parseBigCover(json: String): String? =
        runCatching { JSONObject(json).optJSONObject("info") }.getOrNull()?.let { i ->
            i.optString("bigImageJpg").ifEmpty { i.optString("bigImageWebp") }
        }?.takeIf { it.startsWith("http") }

    fun searchUrl(q: String, page: Int) = BASE + "pages/search/tracks?q=" + VideoNet.seg(q.trim()) + "&page=$page&limit=$SEARCH_LIMIT"

    /** The chart; [period] "day", "week" or "month". */
    fun topUrl(period: String, page: Int) = BASE + "pages/index/top?page=$page&limit=$LIST_LIMIT&period=" + VideoNet.q(period) + "&entity=track"

    fun newUrl(page: Int) = BASE + "pages/new?page=$page&limit=$LIST_LIMIT&limitRecommended=0"

    fun playUrl(hash: String) = BASE + "track/play/" + VideoNet.seg(hash)

    fun trackUrl(id: Long) = BASE + "pages/track?id=$id&othersLimit=1&limitPopular=1&limitMusicset=1"

    fun hashesBody(ids: List<Long>): String = JSONObject().put("trackIds", JSONArray(ids)).put("subscription", false).toString()

    // ---- the network (blocking: call off the main thread) ------------------------------------------------------------

    fun search(q: String, page: Int = 1): Page = parsePage(get(searchUrl(q, page)))
    fun top(period: String, page: Int = 1): Page = parsePage(get(topUrl(period, page)))
    fun newest(page: Int = 1): Page = parsePage(get(newUrl(page)))

    /** The streaming hashes of [ids], at most 100 in one go (a list's page is 50). */
    fun hashes(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        ids.distinct().chunked(100).forEach { out += parseHashes(post(BASE + "track/filezmeta", hashesBody(it))) }
        return out
    }

    fun stream(hash: String): String = parsePlayUrl(get(playUrl(hash))) ?: throw Err(404, "no link")

    fun bigCover(id: Long): String? = parseBigCover(get(trackUrl(id)))

    private fun get(url: String): String = try {
        VideoNet.get(url, 15_000, "Accept", "application/json", "Referer", "https://zaycev.net/")
    } catch (e: VideoNet.HttpError) {
        throw Err(e.code, e.message.orEmpty())
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = VideoNet.CONNECT_MS
            c.readTimeout = 15_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", VideoNet.UA)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Referer", "https://zaycev.net/")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw Err(code, "HTTP $code")
            var input = c.inputStream
            if ("gzip".equals(c.contentEncoding, ignoreCase = true)) input = GZIPInputStream(input)
            val out = ByteArrayOutputStream()
            input.use { s ->
                val buf = ByteArray(16 shl 10)
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 2 shl 20) throw IOException("response too large")
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** What to tell the driver about [e]. */
    fun message(e: Throwable?): String = when {
        e is Err && e.code == BLOCKED -> "Этот трек недоступен для прослушивания"
        e is Err && e.code == 404 -> "Трек не найден на zaycev.net"
        e is Err && e.code == 403 -> "zaycev.net не пускает: возможно, недоступен в этой стране"
        e is Err -> "zaycev.net не отвечает, попробуйте позже"
        e is IOException -> "Нет связи с zaycev.net: проверьте интернет"
        else -> "Не получилось, попробуйте ещё раз"
    }

    /** The code a blocked track's [Err] carries (the site has no hash for it). */
    const val BLOCKED = 451
}

/** Played links, each good for [ttlMs] after it was asked for (the CDN's address expires within hours). */
class LinkCache(private val ttlMs: Long) {
    private val links = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Long>>()

    fun get(id: Long, now: Long): String? = links[id]?.takeIf { now - it.second in 0 until ttlMs }?.first

    fun put(id: Long, url: String, now: Long) { links[id] = url to now }

    fun drop(id: Long) { links.remove(id) }

    fun clear() = links.clear()
}
