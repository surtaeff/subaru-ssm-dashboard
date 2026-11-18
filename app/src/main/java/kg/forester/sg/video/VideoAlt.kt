package kg.forester.sg.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The legal services for everyone outside Kyrgyzstan (and for anyone who wants them). No pirate sites: Rutube's own
 * public API and its official embedded player, YouTube's official Data API (with the driver's own key) and its official
 * embedded player, and the official apps of VK Видео and the online cinemas, opened as they are. Nothing goes to a browser.
 */

/** A video of Rutube or YouTube, played in that service's own embedded player. */
data class AltVideo(
    val service: Service,
    val id: String,
    val title: String,
    val author: String? = null,
    val thumb: String? = null,
    val durationSec: Int = 0,
    /** Rutube says it holds the rights (its own flag): the film rows show only these. */
    val licensed: Boolean = false,
) {
    enum class Service(val key: String, val label: String) { RUTUBE("rt", "Rutube"), YOUTUBE("yt", "YouTube") }

    val key: String get() = "${service.key}:$id"
    val pageUrl: String get() = when (service) {
        Service.RUTUBE -> "https://rutube.ru/video/$id/"
        Service.YOUTUBE -> "https://www.youtube.com/watch?v=$id"
    }
}

data class AltRow(val id: String, val title: String, val items: List<AltVideo>)

/**
 * Rutube: the API its own site uses, no key (rutube.ru/api/…), answering from Kyrgyzstan, Russia, Kazakhstan, Europe
 * and the US. Played in Rutube's documented embed (rutube.ru/info/embed): start time `t`, events by postMessage.
 */
object Rutube {
    /**
     * Off (2026-11-18). Rutube's embed is black on an old WebView (Android 9, WebView 66: «globalThis is not defined»),
     * and its HLS in the app's own player ([kg.forester.sg.ui.AltPlayerDialog]) started only after a seek on the test
     * emulator, so the owner had it switched off until it is checked on a real head unit. Back on: true here.
     */
    const val ENABLED = false

    private val ID = Regex("[0-9a-f]{32}")

    /** The rows of the home: Rutube's own tops and new films. Films, series and cartoons only where Rutube marks the rights. */
    private class Feed(val id: String, val title: String, val url: String, val licensedOnly: Boolean)
    private val FEEDS = listOf(
        Feed("rt-movies", "Топ фильмов", "https://rutube.ru/api/v2/video/recommendation/main/top_movie?type=tag&limit=30", true),
        Feed("rt-new", "Новинки кино", "https://rutube.ru/api/tags/video/8151/?limit=40&sort=tagged_d", true),
        Feed("rt-serials", "Топ сериалов", "https://rutube.ru/api/v2/video/recommendation/main/top_serial?type=tag&limit=40", true),
        Feed("rt-kids", "Мультфильмы", "https://rutube.ru/api/v2/video/recommendation/main/top_cartoon?type=tag&limit=30", true),
        Feed("rt-auto", "Авто", "https://rutube.ru/api/tags/video/6102/?sort=tagged_d&limit=30", false),
    )

    /**
     * A Rutube film's own title for a torrent search: «Сердце зверя | Heart of the Beast (2026,4K)» → «Сердце зверя»,
     * «Полярные противоположности (2025) / Polar Opposites» → «Полярные противоположности».
     */
    fun filmTitle(title: String): String =
        title.substringBefore('|').substringBefore(" / ").replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().ifEmpty { title.trim() }

    /** Each row on its own: one that fails or comes back empty is left out. Blocking. */
    fun home(): List<AltRow> = FEEDS.mapNotNull { f ->
        runCatching {
            val list = parse(VideoNet.get(f.url + "&show_hidden_videos=False&format=json", 12_000))
            AltRow(f.id, f.title, if (f.licensedOnly) list.filter { it.licensed } else list).takeIf { it.items.isNotEmpty() }
        }.getOrNull()
    }

    fun search(query: String): List<AltVideo> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return parse(VideoNet.get("https://rutube.ru/api/search/video/?format=json&query=" + VideoNet.q(q), 12_000))
    }

    fun parse(body: String): List<AltVideo> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<AltVideo>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            if (VideoJson.bool(o, "is_adult") || VideoJson.bool(o, "is_hidden") || VideoJson.bool(o, "is_livestream") ||
                VideoJson.bool(o, "is_paid") || VideoJson.bool(o, "is_deleted")) continue
            val id = VideoJson.str(o, "id")?.takeIf { ID.matches(it) } ?: continue
            val title = VideoJson.str(o, "title") ?: continue
            out += AltVideo(AltVideo.Service.RUTUBE, id, title, VideoJson.str(o.optJSONObject("author"), "name"),
                VideoJson.str(o, "thumbnail_url") ?: VideoJson.str(o, "picture_url"), VideoJson.int(o, "duration"), VideoJson.bool(o, "is_licensed"))
        }
        return out.distinctBy { it.id }
    }

    /**
     * The film's HLS address from Rutube's own play API (what its site's player asks for): played in the app's own
     * player, not in Rutube's embed, whose script needs a newer WebView than many head units have (Android 9 with
     * WebView 66: «globalThis is not defined», a black screen; 2026-11-18). Null when Rutube gives no stream (paid,
     * blocked in the region, removed). Blocking.
     */
    fun streamUrl(id: String): String? {
        if (!ID.matches(id)) return null
        val body = VideoNet.get("https://rutube.ru/api/play/options/$id/?format=json&no_404=true&referer=https%3A%2F%2Frutube.ru",
            12_000, "Referer", "https://rutube.ru/")
        return streamOf(body)
    }

    /**
     * Rutube's master playlist names every quality twice (a local ISP cache and Rutube's own CDN, the same bitrate):
     * the player started on one and then stopped loading, BUFFERING at 0:00 for good (emulator, 2026-11-18). One entry
     * per quality, Rutube's own host first.
     */
    fun dedupeMaster(m3u8: String): String {
        val lines = m3u8.lines()
        val out = ArrayList<String>()
        val seen = HashMap<String, Int>() // stream-info line → index of its address in [out]
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) {
                val url = lines[i + 1].trim()
                val at = seen[l]
                if (at == null) { out += l; out += url; seen[l] = out.size - 1 }
                else if (ownHost(url) && !ownHost(out[at])) out[at] = url
                i += 2
                continue
            }
            out += l
            i++
        }
        return out.joinToString("\n")
    }

    private fun ownHost(url: String) = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").endsWith("rutube.ru")

    fun streamOf(body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (o.optJSONObject("acl_access")?.optBoolean("allowed", true) == false) return null
        val b = o.optJSONObject("video_balancer") ?: return null
        return (VideoJson.str(b, "m3u8") ?: VideoJson.str(b, "default"))?.takeIf { it.startsWith("https://") }
    }

}

/**
 * YouTube: its official Data API v3 (search), with the driver's own key from Google Cloud: the open-source app ships no
 * key. Without one the search goes to the YouTube app. Played in YouTube's official embedded player.
 */
object YouTube {
    private val ID = Regex("[A-Za-z0-9_-]{11}")
    val key = MutableStateFlow("")

    fun loadKey(c: Context) { key.value = prefs(c).getString("ytKey", "") ?: "" }
    fun setKey(c: Context, k: String) { key.value = k.trim(); prefs(c).edit().putString("ytKey", k.trim()).apply() }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)

    /** YouTube plays in the app's own player through NewPipe Extractor (see [YouTubeStreams]): on every unit. */
    @Suppress("UNUSED_PARAMETER")
    fun watchable(c: Context): Boolean = true

    /**
     * Search with no key: YouTube's own results page, its `ytInitialData` (the way the TV launcher does it). With the
     * driver's own API key the Data API instead. Blocking.
     */
    fun search(query: String, apiKey: String): List<AltVideo> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        // no key: NewPipe's search (the page parse below stays as the fallback)
        if (apiKey.isBlank()) return runCatching { YouTubeStreams.search(q) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: parsePage(VideoNet.get("https://www.youtube.com/results?hl=ru&search_query=" + VideoNet.q(q),
                15_000, "Accept-Language", "ru-RU,ru;q=0.9"))
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&safeSearch=moderate" +
            "&relevanceLanguage=ru&maxResults=25&q=" + VideoNet.q(q) + "&key=" + VideoNet.q(apiKey)
        return parse(VideoNet.get(url, 12_000))
    }

    fun parse(body: String): List<AltVideo> {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        val out = ArrayList<AltVideo>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = VideoJson.str(o.optJSONObject("id"), "videoId")?.takeIf { ID.matches(it) } ?: continue
            val sn = o.optJSONObject("snippet") ?: continue
            if (VideoJson.str(sn, "liveBroadcastContent").let { it != null && it != "none" }) continue
            val thumbs = sn.optJSONObject("thumbnails")
            val thumb = VideoJson.str(thumbs?.optJSONObject("medium"), "url") ?: VideoJson.str(thumbs?.optJSONObject("default"), "url")
            out += AltVideo(AltVideo.Service.YOUTUBE, id, androidx.core.text.HtmlCompat.fromHtml(VideoJson.str(sn, "title") ?: continue, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                VideoJson.str(sn, "channelTitle"), thumb)
        }
        return out
    }

    private val INITIAL = Regex("""var ytInitialData = (\{.*?\});</script>""", RegexOption.DOT_MATCHES_ALL)

    /** The videos of a results page (its videoRenderer entries, in order, no live streams, no repeats). */
    fun parsePage(html: String): List<AltVideo> {
        val raw = INITIAL.find(html)?.groupValues?.get(1) ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, AltVideo>()
        fun text(o: JSONObject?): String? {
            o ?: return null
            VideoJson.str(o, "simpleText")?.let { return it }
            val runs = o.optJSONArray("runs") ?: return null
            return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.ifEmpty { null }
        }
        fun video(v: JSONObject) {
            val id = VideoJson.str(v, "videoId")?.takeIf { ID.matches(it) } ?: return
            val title = text(v.optJSONObject("title")) ?: return
            val length = text(v.optJSONObject("lengthText")) ?: return // none: a live stream or a premiere
            val thumbs = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.let { VideoJson.str(it, "url") }?.substringBefore('?')
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            out.putIfAbsent(id, AltVideo(AltVideo.Service.YOUTUBE, id, title,
                text(v.optJSONObject("ownerText")) ?: text(v.optJSONObject("longBylineText")), thumb, seconds(length)))
        }
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    x.optJSONObject("videoRenderer")?.let(::video)
                    x.keys().forEach { walk(x.opt(it)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(root)
        return out.values.toList()
    }

    /** «1:02:03» or «12:34» in seconds, 0 when it is something else. */
    fun seconds(s: String): Int = s.split(':').map { it.trim().toIntOrNull() ?: return 0 }.fold(0) { a, n -> a * 60 + n }

}

/**
 * Where a Rutube video stopped (Rutube's embed tells the time), for «Продолжить просмотр» outside Kyrgyzstan. At most
 * 30, the oldest go. SharedPreferences "video", key "alt".
 */
object AltStore {
    class Entry(val video: AltVideo, val pos: Long, val dur: Long, val updated: Long)

    private val list = ArrayList<Entry>()
    @Volatile private var loaded = false
    val version = MutableStateFlow(0)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        val raw = prefs(c).getString("alt", "[]") ?: "[]"
        synchronized(this) {
            if (loaded) return
            runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val s = AltVideo.Service.entries.firstOrNull { it.key == o.optString("s") } ?: continue
                    list += Entry(AltVideo(s, o.getString("id"), o.getString("t"), VideoJson.str(o, "a"), VideoJson.str(o, "p"), o.optInt("d")),
                        o.optLong("pos"), o.optLong("dur"), o.optLong("u"))
                }
            }
            loaded = true
        }
        version.value++
    }

    @Synchronized fun get(key: String): Entry? = list.firstOrNull { it.video.key == key }

    /** Unfinished, newest first. */
    @Synchronized fun recent(limit: Int): List<Entry> =
        list.filter { !VideoPolicy.isCompleted(it.pos, it.dur) && it.pos > 30_000 }.sortedByDescending { it.updated }.take(limit)

    fun save(c: Context, v: AltVideo, pos: Long, dur: Long) {
        if (pos <= 0) return
        synchronized(this) {
            list.removeAll { it.video.key == v.key }
            list += Entry(v, pos, dur, System.currentTimeMillis())
            while (list.size > 30) list.remove(list.minByOrNull { it.updated }!!)
        }
        version.value++
        val json = synchronized(this) {
            JSONArray().apply {
                list.forEach { e ->
                    put(JSONObject().put("s", e.video.service.key).put("id", e.video.id).put("t", e.video.title).put("a", e.video.author)
                        .put("p", e.video.thumb).put("d", e.video.durationSec).put("pos", e.pos).put("dur", e.dur).put("u", e.updated))
                }
            }.toString()
        }
        prefs(c).edit().putString("alt", json).apply()
    }

    fun dismiss(c: Context, key: String) {
        val e = get(key) ?: return
        save(c, e.video, e.dur.coerceAtLeast(1), e.dur.coerceAtLeast(1))
    }
}

/** An official service's app, opened as it is: the driver's own subscription, nothing played by us. */
data class VideoApp(val pkg: String, val name: String)

object VideoApps {
    const val YOUTUBE = "com.google.android.youtube"
    const val VK_VIDEO = "com.vk.vkvideo"
    const val RUTUBE = "ru.rutube.app"

    /** In the order they're shown; only the installed ones are. */
    val known = listOf(
        VideoApp("ru.kinopoisk", "Кинопоиск"),
        VideoApp("ru.more.play", "Okko"),
        VideoApp("ru.rt.video.app.mobile", "Wink"),
        VideoApp("ru.rt.video.app.tv", "Wink"),
        VideoApp("ru.ivi.client", "ivi"),
        VideoApp("ru.start.androidmobile", "Start"),
        VideoApp("gpm.tnt_premier", "Premier"),
        VideoApp("ru.mts.mtstv", "Kion"),
        VideoApp(VK_VIDEO, "VK Видео"),
        VideoApp(RUTUBE, "Rutube"),
        VideoApp(YOUTUBE, "YouTube"),
        VideoApp("com.netflix.mediaclient", "Netflix"),
        VideoApp("com.amazon.avod.thirdpartyclient", "Prime Video"),
        VideoApp("com.disney.disneyplus", "Disney+"),
    )

    fun installed(c: Context): List<VideoApp> =
        known.filter { c.packageManager.getLaunchIntentForPackage(it.pkg) != null }.distinctBy { it.name }

    fun open(c: Context, pkg: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { c.startActivity(i) }.isSuccess
    }

    fun appOf(v: AltVideo): String = if (v.service == AltVideo.Service.RUTUBE) RUTUBE else YOUTUBE

    fun hasApp(c: Context, pkg: String): Boolean = c.packageManager.getLaunchIntentForPackage(pkg) != null

    /**
     * A video in its service's app; false when the app isn't there. Never a browser: on a head unit a browser page is
     * a dead end (the owner, 2026-11-18: VK Видео opening the browser is no good).
     */
    fun openInApp(c: Context, v: AltVideo): Boolean {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(v.pageUrl)).setPackage(appOf(v)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { c.startActivity(app) }.isSuccess
    }

    fun view(c: Context, url: String): Boolean =
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
