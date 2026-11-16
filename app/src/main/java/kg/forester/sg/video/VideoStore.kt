package kg.forester.sg.video

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What to resume and what to play next (port of FreeLite's VideoPolicy.java). Pure, for the tests.
 */
object VideoPolicy {
    const val COMPLETE_RATIO = 0.95

    /** An unknown duration (≤ 0) is never «watched». */
    fun isCompleted(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs >= durationMs * COMPLETE_RATIO

    /** A watched episode starts from the beginning, otherwise from where it stopped. */
    fun resumePosition(positionMs: Long, durationMs: Long) = if (isCompleted(positionMs, durationMs)) 0L else positionMs

    /** The title independent of the site: kinopoisk / imdb id, else the normalized original title + year. */
    fun canonicalKey(d: VideoDetails): String? = when {
        d.kpId != null -> "kp:${d.kpId}"
        d.imdbId != null -> "imdb:${d.imdbId}"
        d.card.year > 0 -> "t:" + VideoText.normalize(d.card.original ?: d.card.title) + ":" + d.card.year
        else -> null
    }

    /** A movie's several files are copies (HD/SD), not a playlist: no «next». */
    fun nextEpisode(d: VideoDetails, currentId: String?): VideoEpisode? {
        if (!d.series) return null
        val i = d.indexOf(currentId)
        return if (i < 0 || i + 1 >= d.episodes.size) null else d.episodes[i + 1]
    }

    fun prevEpisode(d: VideoDetails, currentId: String?): VideoEpisode? {
        if (!d.series) return null
        val i = d.indexOf(currentId)
        return if (i <= 0) null else d.episodes[i - 1]
    }

    /** What «Продолжить» opens: a watched episode → the next one, else the saved one, else the first. */
    fun resumeTarget(d: VideoDetails, saved: VideoStore.Entry?): VideoEpisode? {
        val ep = d.episode(saved?.episodeId) ?: return d.episodes.firstOrNull()
        if (saved!!.completed) return nextEpisode(d, ep.id) ?: ep
        return ep
    }

    /**
     * What plays after [currentId]: (title key, episode id or null for the first). On oc.kg every season is a title of
     * its own, so after a season's last episode comes the next related season.
     */
    fun nextAcrossRelated(d: VideoDetails, currentId: String?): Pair<String, String?>? {
        if (d.indexOf(currentId) < 0) return null
        nextEpisode(d, currentId)?.let { return d.key to it.id }
        if (!d.series) return null
        val i = d.related.indexOfFirst { it.key == d.key }
        if (i < 0 || i + 1 >= d.related.size) return null
        return d.related[i + 1].key to null
    }

    private val SEASON_TITLE = Regex("^(.*?)\\s*\\d+\\s*сезон", RegexOption.IGNORE_CASE)

    /** «Продолжить»: seasons of one oc.kg series collapse into one card, the freshest. Input is newest first. */
    fun collapseSeasons(rows: List<VideoStore.Entry>): List<VideoStore.Entry> {
        val groups = LinkedHashMap<String, VideoStore.Entry>()
        for (e in rows) {
            val k = showKey(e) ?: e.key
            val cur = groups[k]
            if (cur == null || e.updated > cur.updated) groups[k] = e
        }
        return groups.values.toList()
    }

    private fun showKey(p: VideoStore.Entry): String? {
        if (parseVideoKey(p.key)?.first != VideoSource.OCKG) return null
        val m = SEASON_TITLE.find(p.title ?: return null) ?: return null
        return VideoText.normalize(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { "ockg-show:$it" }
    }
}

/**
 * Local watch progress (port of FreeLite's VideoStore.java): SharedPreferences "video", one JSON string. One entry per
 * title, the last episode and where it stopped, plus each episode's own place. At most 50 titles (the oldest go) and
 * 80 episodes a title.
 */
object VideoStore {
    const val MAX_TITLES = 50
    const val MAX_EPISODES = 80

    class Entry(val key: String) {
        var title: String? = null
        var original: String? = null
        var poster: String? = null
        var episodeId: String? = null
        var label: String? = null
        var canon: String? = null
        var year = 0
        var pos = 0L
        var dur = 0L
        var updated = 0L
        var completed = false
        /** Episode id → [position ms, duration ms, updated, watched 0/1]; insertion = oldest first. */
        val eps = LinkedHashMap<String, LongArray>()
    }

    private val titles = LinkedHashMap<String, Entry>()
    @Volatile private var loaded = false

    /** Bumped on every change: what shows progress reads it to redraw. */
    val version = MutableStateFlow(0)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)

    /** Reads the file once; small, so fine on any thread. */
    fun load(c: Context) {
        if (loaded) return
        val raw = prefs(c).getString("titles", "[]") ?: "[]"
        synchronized(this) {
            if (loaded) return
            runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) fromJson(a.getJSONObject(i))?.let { if (it.key !in titles) titles[it.key] = it }
            }
            loaded = true
        }
        version.value++
    }

    @Synchronized fun get(key: String?): Entry? = key?.let { titles[it] }

    /** [position, duration, updated, watched] of one episode, or null. */
    @Synchronized fun episode(key: String, episodeId: String?): LongArray? = episodeId?.let { titles[key]?.eps?.get(it) }

    fun watched(key: String, episodeId: String?) = episode(key, episodeId)?.get(3) == 1L

    /** «Продолжить просмотр»: unfinished titles, newest first, oc.kg seasons collapsed. */
    @Synchronized fun recent(limit: Int): List<Entry> =
        VideoPolicy.collapseSeasons(titles.values.filter { !it.completed }.sortedByDescending { it.updated }).take(limit)

    /** Where to start this episode: its own place, else (movies) the same film from the other site. */
    @Synchronized fun resumeOf(d: VideoDetails, e: VideoEpisode): Long {
        episode(d.key, e.id)?.let { return VideoPolicy.resumePosition(it[0], it[1]) }
        if (d.series) return 0
        val canon = VideoPolicy.canonicalKey(d) ?: return 0
        var best: LongArray? = null
        for (t in titles.values) if (canon == t.canon) for (ep in t.eps.values) if (best == null || ep[2] > best[2]) best = ep
        return best?.let { VideoPolicy.resumePosition(it[0], it[1]) } ?: 0
    }

    /** Every ~5 s while playing and on pause, stop and the end. */
    fun save(c: Context, d: VideoDetails, ep: VideoEpisode, position: Long, duration: Long) {
        if (position <= 0 && duration <= 0) return
        val now = System.currentTimeMillis()
        val completed = VideoPolicy.isCompleted(position, duration)
        synchronized(this) {
            val key = d.key
            val e = titles.remove(key) ?: Entry(key)
            e.title = d.card.title
            e.original = d.card.original
            e.year = d.card.year
            e.poster = d.card.poster
            e.canon = VideoPolicy.canonicalKey(d)
            // a watched episode moves «Продолжить» on to the next one
            val next = if (completed) VideoPolicy.nextEpisode(d, ep.id) else null
            if (next != null) {
                e.episodeId = next.id; e.pos = 0; e.dur = 0; e.completed = false; e.label = d.label(next)
            } else {
                e.episodeId = ep.id; e.pos = position; e.dur = maxOf(0, duration); e.completed = completed; e.label = d.label(ep)
            }
            e.updated = now
            val old = e.eps.remove(ep.id)
            e.eps[ep.id] = longArrayOf(position, maxOf(0, duration), now, if (completed || old?.get(3) == 1L) 1 else 0)
            while (e.eps.size > MAX_EPISODES) e.eps.remove(e.eps.keys.first())
            titles[key] = e
            // a season finished: the next related season (oc.kg) takes its place in «Продолжить» if not started yet
            if (completed && next == null) {
                val n = VideoPolicy.nextAcrossRelated(d, ep.id)
                if (n != null && n.first != key && n.first !in titles) {
                    d.related.firstOrNull { it.key == n.first }?.let { card ->
                        titles[card.key] = Entry(card.key).apply {
                            title = card.title; original = card.original; year = card.year
                            poster = card.poster ?: d.card.poster; episodeId = ""; label = card.subtitle; updated = now + 1
                        }
                    }
                }
            }
            while (titles.size > MAX_TITLES) titles.remove(titles.values.minByOrNull { it.updated }!!.key)
        }
        version.value++
        persist(c)
    }

    /** «Убрать из Продолжить»: the title stays known (episode marks), it only leaves the row. */
    fun dismiss(c: Context, key: String) {
        synchronized(this) { val e = titles[key] ?: return; e.completed = true }
        version.value++
        persist(c)
    }

    private fun persist(c: Context) {
        val json = synchronized(this) { JSONArray().apply { titles.values.forEach { put(toJson(it)) } }.toString() }
        prefs(c).edit().putString("titles", json).apply()
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("k", e.key); put("t", e.title); put("p", e.poster); put("e", e.episodeId); put("l", e.label)
        put("ck", e.canon); put("o", e.original); put("y", e.year); put("pos", e.pos); put("dur", e.dur); put("u", e.updated); put("c", e.completed)
        put("eps", JSONObject().apply { e.eps.forEach { (id, v) -> put(id, JSONArray().put(v[0]).put(v[1]).put(v[2]).put(v[3])) } })
    }

    private fun fromJson(o: JSONObject): Entry? {
        val key = VideoJson.str(o, "k")?.takeIf { parseVideoKey(it) != null } ?: return null
        return Entry(key).apply {
            title = VideoJson.str(o, "t"); poster = VideoJson.str(o, "p"); episodeId = VideoJson.str(o, "e"); label = VideoJson.str(o, "l")
            canon = VideoJson.str(o, "ck"); original = VideoJson.str(o, "o"); year = o.optInt("y")
            pos = o.optLong("pos"); dur = o.optLong("dur"); updated = o.optLong("u"); completed = o.optBoolean("c")
            val list = ArrayList<Pair<String, LongArray>>()
            o.optJSONObject("eps")?.let { eo ->
                for (id in eo.keys()) eo.optJSONArray(id)?.let { v -> list += id to longArrayOf(v.optLong(0), v.optLong(1), v.optLong(2), v.optLong(3)) }
            }
            // a JSON object keeps no order: oldest first again by the update time
            list.sortedBy { it.second[2] }.forEach { (id, v) -> eps[id] = v }
        }
    }
}
