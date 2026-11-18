package kg.forester.sg.video

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** A film or series people read about now, for the torrent search: its plain title, year and poster. */
data class FeedFilm(val title: String, val year: Int?, val series: Boolean, val thumb: String?, val views: Long)

/**
 * «Сейчас смотрят»: the films and series among Russian Wikipedia's most read articles of the last days (its public
 * featured feed, no key, reachable from Russia and Kyrgyzstan). A neutral source of what is new and talked about, for
 * the torrent search to start from; not a streaming service's own catalogue (the owner, 2026-11-18).
 */
object WikiFeed {
    private const val DAYS = 10
    private const val CACHE_MS = 6 * 3600_000L
    @Volatile private var cached: Pair<Long, List<FeedFilm>>? = null

    private val KIND = Regex("фильм|сериал|мультфильм|мультсериал|аниме", RegexOption.IGNORE_CASE)
    private val SERIES = Regex("сериал|аниме", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?<!\d)(19[5-9]\d|20\d\d)(?!\d)""")

    /** What [films] found last, without the network (the torrent results take their posters from it). */
    fun cachedFilms(): List<FeedFilm> = cached?.second.orEmpty()

    /** The films of the last [DAYS] days, most read first. Blocking; days that fail are left out. */
    fun films(nowMs: Long = System.currentTimeMillis()): List<FeedFilm> {
        cached?.let { (at, list) -> if (nowMs - at < CACHE_MS && list.isNotEmpty()) return list }
        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        // the days at once: one by one they took 20 s on a head unit's network
        val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
        val days = try {
            (1..DAYS).map { back ->
                val c = Calendar.getInstance().apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, -back) }
                val url = "https://ru.wikipedia.org/api/rest_v1/feed/featured/" + fmt.format(c.time)
                pool.submit<String?> {
                    runCatching { VideoNet.get(url, 10_000, "User-Agent", "CarPulse/1.0 (https://github.com/surtaeff/subaru-ssm-dashboard)") }.getOrNull()
                }
            }.mapNotNull { runCatching { it.get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
        } finally { pool.shutdownNow() }
        val year = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.YEAR)
        return merge(days, year).also { if (it.isNotEmpty()) cached = nowMs to it }
    }

    /** The feed days' films merged by title, views added up; old films (a TV anniversary) left out. */
    fun merge(days: List<String>, year: Int): List<FeedFilm> {
        val byTitle = LinkedHashMap<String, FeedFilm>()
        for (body in days) {
            val arts = runCatching { JSONObject(body).optJSONObject("mostread")?.optJSONArray("articles") }.getOrNull() ?: continue
            for (i in 0 until arts.length()) {
                val a = arts.optJSONObject(i) ?: continue
                val desc = VideoJson.str(a, "description") ?: continue
                if (!KIND.containsMatchIn(desc)) continue
                val page = VideoJson.str(a, "normalizedtitle") ?: VideoJson.str(a, "title")?.replace('_', ' ') ?: continue
                val f = film(page, desc, VideoJson.str(a.optJSONObject("thumbnail"), "source"), a.optLong("views"))
                if (f.year != null && f.year < year - 3) continue
                val old = byTitle[f.title]
                byTitle[f.title] = if (old == null) f else old.copy(views = old.views + f.views, thumb = old.thumb ?: f.thumb, year = old.year ?: f.year)
            }
        }
        return byTitle.values.sortedByDescending { it.views }
    }

    /** «Одиссея (фильм, 2026)» + «фильм Кристофера Нолана (2026)» → Одиссея, 2026, a film. */
    fun film(page: String, desc: String, thumb: String?, views: Long): FeedFilm {
        val title = page.substringBefore(" (").trim().ifEmpty { page.trim() }
        val year = YEAR.find(page)?.value?.toInt() ?: YEAR.find(desc)?.value?.toInt()
        return FeedFilm(title, year, SERIES.containsMatchIn(desc) || SERIES.containsMatchIn(page), thumb, views)
    }
}
