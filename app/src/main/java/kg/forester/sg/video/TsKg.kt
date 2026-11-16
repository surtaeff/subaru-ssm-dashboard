package kg.forester.sg.video

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * ts.kg requests (port of FreeLite's TsKg.java). Blocking: call from Dispatchers.IO. The site and its video nodes answer
 * only Kyrgyz addresses: from abroad www.ts.kg says 403 and node*.ts.kg doesn't answer at all.
 */
object TsKg {
    const val BASE = "https://www.ts.kg/"

    fun search(query: String): List<VideoCard> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return TsKgParser.search(get(BASE + "shows/search/" + VideoNet.seg(q), 8_000))
    }

    fun details(slug: String): VideoDetails = TsKgParser.show(get(BASE + "show/" + VideoNet.seg(slug), VideoNet.READ_MS), slug)

    /** The episode's stream: the site's own player asks this with X-Requested-With, without it the answer is 404. */
    fun stream(episodeId: String): VideoStream =
        TsKgParser.episode(get(BASE + "show/episode/episode.json?episode=" + VideoNet.q(episodeId), VideoNet.READ_MS, "X-Requested-With", "XMLHttpRequest"))

    fun home(): List<VideoRow> = TsKgParser.home(get(BASE, VideoNet.READ_MS))

    private fun get(url: String, readMs: Int, vararg headers: String): String = try {
        VideoNet.get(url, readMs, *headers)
    } catch (e: IOException) {
        throw VideoSourceException(VideoSource.TSKG, "ts.kg request failed", e, blocked = (e as? VideoNet.HttpError)?.code == 403)
    }
}

/**
 * ts.kg pages → model (port of FreeLite's TsKgParser.java; jsoup + org.json). The home page is ~800 KB: only the blocks
 * shown are handed to jsoup, which keeps the temporary DOM several times smaller on the head unit.
 */
object TsKgParser {
    const val SITE = "https://www.ts.kg"
    private val TRAILING_ORIGINAL = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$")
    private val EPISODE_HREF = Regex("^/show/([^/]+)/(\\d+)/(\\d+)$")
    private val SHOW_HREF = Regex("^/show/([^/?#]+)")
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
    private val DURATION = Regex("(\\d+):(\\d{2})(?::(\\d{2}))?")
    private val SEASON_EPISODE = Regex("\\d+\\s*сезон,\\s*\\d+(?:-\\d+)?\\s*сери[яи]")

    /** 256×204 poster with the title baked in. */
    fun poster(slug: String) = "$SITE/posters/$slug.png"

    fun search(body: String): List<VideoCard> {
        val items = runCatching { JSONArray(body) }.getOrElse { throw error("bad search json", it) }
        val out = ArrayList<VideoCard>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = VideoJson.str(o, "url") ?: continue
            val name = VideoJson.str(o, "name") ?: continue
            val slug = SHOW_HREF.find(url)?.groupValues?.get(1) ?: continue
            val (title, original) = splitName(name)
            out += VideoCard(VideoSource.TSKG, slug, title, original, 0, poster(slug))
        }
        return VideoText.distinct(out)
    }

    fun show(html: String, slug: String): VideoDetails {
        val doc = Jsoup.parse(html, SITE)
        val h1 = doc.selectFirst(".app-h1") ?: throw error("no title on show page $slug")
        val episodes = episodes(doc, slug)
        val original = doc.selectFirst(".app-h2")?.text()?.let(VideoText::blankToNull)
        val year = doc.selectFirst(".app-h3")?.text()?.let(::year) ?: 0
        val desc = doc.select(".app-show-info p:not(.text-danger)").joinToString("\n") { it.text() }
        return VideoDetails(
            card = VideoCard(VideoSource.TSKG, slug, h1.text(), original, year, poster(slug)),
            series = episodes.size != 1,
            episodes = episodes,
            description = VideoText.blankToNull(desc),
            genres = doc.select(".app-show-tags a[href^='/genre/']").map { it.text() },
            countries = doc.select(".app-show-tags a.app-show-tags-flag[title]").map { it.attr("title") },
            backdrop = doc.selectFirst("#show-shots a[href]")?.absUrl("href")?.let(VideoText::blankToNull),
        )
    }

    fun episode(body: String): VideoStream {
        val root = runCatching { JSONObject(body) }.getOrElse { throw error("bad episode json", it) }
        val video = root.optJSONObject("video") ?: throw error("no video in episode")
        val url = VideoJson.str(video, "url") ?: throw error("no video url")
        val hls = "HLS".equals(VideoJson.str(video, "videotype"), ignoreCase = true) || url.contains(".m3u8")
        return VideoStream(url, hls, VideoJson.str(video, "subtitles"))
    }

    fun home(html: String): List<VideoRow> = listOf(
        VideoRow("tskg-episodes", "Новые серии", VideoSource.TSKG, newEpisodes(fragment(html, "index-page-news-list", 48_000), 40)),
        VideoRow("tskg-new", "Новое", VideoSource.TSKG, VideoText.newestYearFirst(cards(fragment(html, "app-index-shows-new-m", 160_000), "#app-index-shows-new-m"))),
        VideoRow("tskg-top", "Популярное", VideoSource.TSKG, cards(fragment(html, "app-index-shows-top-m", 160_000), "#app-index-shows-top-m")),
    )

    /** The element with this id and what follows it (at most [maxLen] chars) as its own small document; the whole page when the marker moved. */
    private fun fragment(html: String, id: String, maxLen: Int): Document {
        val at = html.indexOf("id=\"$id\"")
        if (at < 0) return Jsoup.parse(html, SITE)
        var start = html.lastIndexOf('<', at)
        if (start < 0) start = at
        return Jsoup.parseBodyFragment(html.substring(start, minOf(html.length, start + maxLen)), SITE)
    }

    /** "Задорные друзья | Улыбающиеся друзья (Smiling Friends)" → ("Задорные друзья", "Smiling Friends"). */
    fun splitName(name: String): Pair<String, String?> {
        var main = name
        var original: String? = null
        TRAILING_ORIGINAL.find(name)?.let {
            main = it.groupValues[1]
            original = VideoText.blankToNull(it.groupValues[2].trim())
        }
        val bar = main.indexOf(" | ")
        if (bar >= 0) main = main.substring(0, bar)
        return main.trim() to original
    }

    private fun episodes(doc: Document, slug: String): List<VideoEpisode> {
        // one pass over the DOM instead of a lookup per episode («Симпсоны» have 700+)
        val rows = HashMap<String, Element>()
        for (e in doc.select("[id^='app-show-episode-title-']")) rows[e.id()] = e
        val out = ArrayList<VideoEpisode>()
        val seen = HashSet<String>()
        for (a in doc.select("a.episode[href^='/show/$slug/']")) {
            val m = EPISODE_HREF.find(a.attr("href")) ?: continue
            val id = a.attr("data-id")
            if (id.isBlank() || !seen.add(id)) continue
            val row = rows["app-show-episode-title-$id"]?.closest("td")
            var name: String? = null
            var voice: String? = null
            var dur = 0
            var hd = false
            if (row != null) {
                name = episodeName(row)
                for (s in row.select("small.text-muted")) { val v = parseDuration(s.text()); if (v > 0) { dur = v; break } }
                row.selectFirst("small.clearfix")?.let { vo -> voice = VideoText.blankToNull(trim(vo.text().removeSuffix("[субтитры]"))) }
                hd = row.select("strong.text-danger").any { it.text().contains("HD") }
            }
            out += VideoEpisode(id, m.groupValues[2].toInt(), m.groupValues[3].toInt(), name, dur, voice, hd)
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private fun episodeName(td: Element): String? {
        val t = td.selectFirst("a[id^='app-show-episode-title-']")?.text() ?: return null
        val c = t.indexOf(':')
        return if (c < 0) null else VideoText.blankToNull(t.substring(c + 1).trim())
    }

    private fun parseDuration(text: String): Int {
        if (text.indexOf('|') < 0) return 0
        val m = DURATION.find(text) ?: return 0
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val c = m.groupValues[3]
        return if (c.isEmpty()) a * 60 + b else a * 3600 + b * 60 + c.toInt()
    }

    private fun cards(doc: Document, container: String): List<VideoCard> {
        val out = ArrayList<VideoCard>()
        for (item in doc.select("$container .app-shows-item-full")) {
            val link = item.selectFirst("a[href^='/show/']") ?: continue
            val slug = SHOW_HREF.find(link.attr("href"))?.groupValues?.get(1) ?: continue
            val title = item.selectFirst(".app-shows-card-title") ?: continue
            val tip = item.selectFirst(".app-shows-card-tooltip")?.text() ?: ""
            out += VideoCard(VideoSource.TSKG, slug, title.text(), null, year(tip), poster(slug), genres(tip))
        }
        return VideoText.distinct(out)
    }

    /** Tooltip «2022, Боевик, Фантастика» → «Боевик, Фантастика» (two genres at most). */
    private fun genres(tip: String): String? =
        VideoText.blankToNull(tip.split(',').map { it.trim() }.filter { it.isNotEmpty() && !YEAR.matches(it) }.take(2).joinToString(", "))

    private fun newEpisodes(doc: Document, limit: Int): List<VideoCard> {
        val out = ArrayList<VideoCard>()
        val seen = HashSet<String>()
        for (n in doc.select("#index-page-news-list .news")) {
            val a = n.selectFirst("a.app-news-link") ?: continue
            val slug = SHOW_HREF.find(a.attr("href"))?.groupValues?.get(1) ?: continue
            if (!seen.add(slug)) continue
            var t = a.text()
            val bar = t.indexOf(" | ")
            if (bar >= 0) t = t.substring(0, bar)
            val sub = n.selectFirst("small")?.text()?.let { SEASON_EPISODE.find(it)?.value }
            out += VideoCard(VideoSource.TSKG, slug, t.trim(), null, 0, poster(slug), sub)
            if (out.size >= limit) break
        }
        return out
    }

    private fun year(s: String): Int = YEAR.find(s)?.value?.toInt() ?: 0

    private fun trim(s: String): String = s.trim { it.isWhitespace() || Character.isSpaceChar(it) || it == '⠀' }

    private fun error(message: String, cause: Throwable? = null) = VideoSourceException(VideoSource.TSKG, message, cause)
}
