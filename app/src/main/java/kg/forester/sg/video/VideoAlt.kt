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
 * embedded player, and the official apps of VK Видео and the online cinemas, opened as they are.
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
     * The page the WebView loads: Rutube's iframe from [startSec], its events handed to `CarPulse.on(type, json)`.
     * A page of our own around the iframe, so the player knows where it is embedded (as on any site).
     */
    fun embedPage(id: String, startSec: Long): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style></head>
        <body><iframe id="p" src="https://rutube.ru/play/embed/$id?autoplay=true${if (startSec > 5) "&t=$startSec" else ""}"
          allow="clipboard-write; autoplay; fullscreen" allowfullscreen></iframe>
        <script>
          window.addEventListener('message', function (e) {
            try {
              var m = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
              if (m && m.type) CarPulse.on(String(m.type), JSON.stringify(m.data || {}));
            } catch (x) {}
          });
          function cmd(type) { document.getElementById('p').contentWindow.postMessage(JSON.stringify({type: type, data: {}}), '*'); }
        </script></body></html>
    """.trimIndent()
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

    fun search(query: String, apiKey: String): List<AltVideo> {
        val q = query.trim()
        if (q.length < 2 || apiKey.isBlank()) return emptyList()
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

    /** YouTube's iframe from [startSec]; the page is ours so the embed has an origin (YouTube refuses embeds without one). */
    fun embedPage(id: String, startSec: Long): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style></head>
        <body><iframe id="p" src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0${if (startSec > 5) "&start=$startSec" else ""}"
          referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
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

    /** The query in YouTube's app (its own search), else on the site in a browser. */
    fun youtubeSearch(c: Context, query: String): Boolean {
        val inApp = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(inApp) }.isSuccess) return true
        return view(c, "https://www.youtube.com/results?search_query=" + VideoNet.q(query))
    }

    /** VK Видео has no search a link can open: its app, else its site. */
    fun vkVideo(c: Context): Boolean = open(c, VK_VIDEO) || view(c, "https://vkvideo.ru/")

    /** A video in its service's app when that is there, else in a browser. */
    fun openInApp(c: Context, v: AltVideo): Boolean {
        val pkg = if (v.service == AltVideo.Service.RUTUBE) RUTUBE else YOUTUBE
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(v.pageUrl)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(app) }.isSuccess) return true
        return view(c, v.pageUrl)
    }

    fun view(c: Context, url: String): Boolean =
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
