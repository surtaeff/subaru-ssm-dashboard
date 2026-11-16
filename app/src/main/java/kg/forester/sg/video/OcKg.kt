package kg.forester.sg.video

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/**
 * oc.kg requests (port of FreeLite's OcKg.java): batched api.php calls. Blocking: call from Dispatchers.IO. api.php and
 * the player page answer 403 outside Kyrgyzstan; the files on p1.oc.kg:8082 are served to anyone, but their paths only
 * come from api.php.
 */
object OcKg {
    const val BASE = "https://www.oc.kg/"
    private const val CACHE_TTL_MS = 30 * 60_000L

    /** Big series take the site seconds to answer: the last 8 getMovie answers (the player needs the same body for the stream). */
    private val movies = object : LinkedHashMap<String, Pair<String, Long>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?) = size > 8
    }

    fun search(query: String): List<VideoCard> = OcKgParser.catalog(api(8_000, arrayOf("Video.search", "query", query)), 0)

    fun details(id: String): VideoDetails = OcKgParser.movie(movie(id))

    private fun movie(id: String): String {
        synchronized(movies) {
            movies[id]?.let { (body, at) -> if (SystemClock.elapsedRealtime() - at < CACHE_TTL_MS) return body }
        }
        val body = api(VideoNet.READ_MS, arrayOf("Video.getMovie", "movie_id", id))
        OcKgParser.movie(body) // errors are not cached
        synchronized(movies) { movies[id] = body to SystemClock.elapsedRealtime() }
        return body
    }

    fun stream(fileId: String, movieId: String): VideoStream {
        val (license, path) = OcKgParser.file(movie(movieId), fileId)
        var url: String? = null
        if (license != null) {
            url = runCatching { OcKgParser.playerMp4(VideoNet.get(URL(URL(BASE), license).toString())) }.getOrNull()
        }
        if (url == null) url = OcKgParser.pathToUrl(path)
        if (url == null) throw VideoSourceException(VideoSource.OCKG, "no stream for file $fileId")
        return VideoStream(url, false)
    }

    fun home(): List<VideoRow> {
        val body = api(VideoNet.READ_MS,
            arrayOf("Video.getCatalog", "offset", "0", "size", "20", "order", "0"),
            arrayOf("Video.getCatalog", "offset", "0", "size", "20", "order", "8"))
        return listOf(
            VideoRow("ockg-new", "Новое", VideoSource.OCKG, VideoText.newestYearFirst(OcKgParser.catalog(body, 0))),
            VideoRow("ockg-trend", "В тренде", VideoSource.OCKG, OcKgParser.catalog(body, 1)),
        )
    }

    /** Batched api.php: each action is {name, key, value, key, value…} and gets its own index [i]. */
    private fun api(readMs: Int, vararg actions: Array<String>): String {
        val url = StringBuilder(BASE).append("api.php?format=json")
        actions.forEachIndexed { i, a ->
            url.append('&').append(VideoNet.q("action[$i]")).append('=').append(VideoNet.q(a[0]))
            var k = 1
            while (k + 1 < a.size) { url.append('&').append(VideoNet.q("${a[k]}[$i]")).append('=').append(VideoNet.q(a[k + 1])); k += 2 }
        }
        return try {
            VideoNet.get(url.toString(), readMs)
        } catch (e: IOException) {
            throw VideoSourceException(VideoSource.OCKG, "oc.kg request failed", e, blocked = (e as? VideoNet.HttpError)?.code == 403)
        }
    }
}

/** oc.kg api.php answers → model (port of FreeLite's OcKgParser.java; org.json). */
object OcKgParser {
    const val SITE = "https://www.oc.kg"
    const val VIDEO_HOST = "https://p1.oc.kg:8082/"
    private val PLAYER_SRC = Regex("src:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\"")
    private val EXTENSION = Regex("\\.(mp4|m4v|avi|mkv)$", RegexOption.IGNORE_CASE)
    private val LEADING_NUMBER = Regex("^\\s*\\d+")
    private val SEASON = Regex("(\\d+)\\s*сезон", RegexOption.IGNORE_CASE)
    private val IMDB = Regex("tt\\d+")
    private val KINOPOISK = Regex("/film/(\\d+)")
    private val THUMBNAIL = Regex("/media/thumbnails/([0-9a-f]/[0-9a-f])/([0-9a-f]{32})_(\\d+)x\\d+\\.jpg")

    fun catalog(body: String, index: Int): List<VideoCard> {
        val movies = (response(body, index) as? JSONObject)?.optJSONArray("movies") ?: return emptyList()
        return VideoText.distinct((0 until movies.length()).mapNotNull { card(movies.optJSONObject(it)) })
    }

    fun movie(body: String): VideoDetails {
        val movie = (response(body, 0) as? JSONObject)?.optJSONObject("movie") ?: throw error("no movie in response")
        val card = card(movie) ?: throw error("movie without id")
        // without file_id a file can't be played; repeats break the lists on screen
        val ids = HashSet<String>()
        val files = flatten(movie.optJSONArray("files")).filter { f -> VideoJson.str(f, "file_id")?.let(ids::add) == true }
        val genres = VideoJson.strings(movie, "genres")
        val season = seasonNumber(card.title).takeIf { it > 0 } ?: 1
        val episodes = files.mapIndexed { i, f -> episode(f, season, i + 1) }
        val numbered = episodes.count { e -> e.name != null && LEADING_NUMBER.containsMatchIn(e.name) }
        val series = episodes.size > 1 && ("Сериал" in genres || numbered > episodes.size / 2)
        // cards and the title page show ~200 px posters: the 200×296 thumbnail is enough
        val cover = movie.optJSONArray("covers")?.optJSONObject(0)
        val thumb = VideoJson.str(cover, "thumbnail")
        val orig = VideoJson.str(cover, "original")
        val poster = when {
            thumb != null && thumbWidth(thumb) >= 150 -> absolute(thumb)
            orig != null -> absolute(orig)
            else -> card.poster
        }
        val main = card.copy(poster = poster, subtitle = null)
        val backdrop = files.firstOrNull()?.let { VideoJson.strings(it, "frames").firstOrNull() }?.let(::absolute)
        val imdbId = VideoJson.str(movie, "imdb_url")?.let { IMDB.find(it)?.value }
        val kpId = VideoJson.str(movie, "kinopoisk_url")?.let { KINOPOISK.find(it)?.groupValues?.get(1) }
        // other_movies is either «Фильмы из этой серии» (seasons, parts) or «Похожие фильмы»
        val om = movie.optJSONArray("other_movies")
        val others = if (om == null) emptyList() else (0 until om.length()).mapNotNull { card(om.optJSONObject(it)) }
        val similarList = VideoJson.str(movie, "other_title")?.lowercase()?.contains("похож") == true
        return VideoDetails(
            card = main, series = series, episodes = episodes,
            description = VideoJson.str(movie, "description"), genres = genres, countries = VideoJson.strings(movie, "countries"),
            backdrop = backdrop, ratingKp = VideoJson.dbl(movie, "rating_kinopoisk_value"), ratingImdb = VideoJson.dbl(movie, "rating_imdb_value"),
            kpId = kpId, imdbId = imdbId,
            related = if (similarList) emptyList() else related(main, others),
            similar = if (similarList) VideoText.distinct(others) else emptyList(),
        )
    }

    /** This title + other_movies: by season number when every name has one, otherwise by year. */
    private fun related(current: VideoCard, others: List<VideoCard>): List<VideoCard> {
        if (others.isEmpty()) return emptyList()
        val all = VideoText.distinct(listOf(current) + others)
        return if (all.all { seasonNumber(it.title) > 0 }) {
            all.sortedBy { seasonNumber(it.title) }.map { it.copy(subtitle = "Сезон ${seasonNumber(it.title)}") }
        } else {
            all.sortedWith(compareBy<VideoCard>({ if (it.year == 0) Int.MAX_VALUE else it.year }, { it.id.toIntOrNull() ?: Int.MAX_VALUE }))
                .map { it.copy(subtitle = if (it.year == 0) null else it.year.toString()) }
        }
    }

    fun seasonNumber(name: String?): Int = name?.let { SEASON.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

    /** (links.license, path) of a file: what the stream is resolved from. */
    fun file(body: String, fileId: String): Pair<String?, String?> {
        val movie = (response(body, 0) as? JSONObject)?.optJSONObject("movie") ?: throw error("no movie in response")
        val f = flatten(movie.optJSONArray("files")).firstOrNull { VideoJson.str(it, "file_id") == fileId } ?: throw error("file $fileId not found")
        return VideoJson.str(f.optJSONObject("links"), "license") to VideoJson.str(f, "path")
    }

    /** The movie's MP4 from the player page: the first src that isn't the ad background. */
    fun playerMp4(html: String): String? = PLAYER_SRC.findAll(html).map { it.groupValues[1] }.firstOrNull { !it.contains("/background/") }

    fun pathToUrl(path: String?): String? =
        if (path != null && path.startsWith("/home/video/")) VIDEO_HOST + path.removePrefix("/home/video/") else null

    private fun response(body: String, index: Int): Any? {
        val root = runCatching { JSONObject(body) }.getOrElse { throw error("bad json", it) }
        val item = root.optJSONArray("json")?.optJSONObject(index) ?: throw error("no json[$index]")
        if (VideoJson.int(item, "status") != 200) throw error(VideoJson.str(item, "message") ?: "status ${VideoJson.str(item, "status")}")
        return item.opt("response")
    }

    private fun card(m: JSONObject?): VideoCard? {
        if (m == null) return null
        val id = VideoJson.str(m, "movie_id") ?: return null
        val name = VideoJson.str(m, "name") ?: return null
        val genres = VideoJson.strings(m, "genres")
        val sub = when (genres.size) { 0 -> null; 1 -> genres[0]; else -> genres[0] + ", " + genres[1] }
        return VideoCard(VideoSource.OCKG, id, name, VideoJson.str(m, "international_name"), VideoJson.int(m, "year"),
            VideoJson.str(m, "cover")?.let(::poster), sub, VideoJson.dbl(m, "rating_kinopoisk_value"))
    }

    /** Catalog thumbnails are 200×300, enough for a card; tiny ones (search: 40 px) → the original picture. */
    private fun poster(cover: String): String {
        val m = THUMBNAIL.find(cover) ?: return absolute(cover)
        if (m.groupValues[3].toInt() >= 150) return absolute(cover)
        return "$SITE/media/images/${m.groupValues[1]}/${m.groupValues[2]}/image.jpg"
    }

    private fun thumbWidth(cover: String): Int = THUMBNAIL.find(cover)?.groupValues?.get(3)?.toInt() ?: 0

    private fun flatten(files: JSONArray?): List<JSONObject> {
        if (files == null) return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (!VideoJson.bool(f, "is_dir")) out += f
            out += flatten(f.optJSONArray("childs"))
        }
        return out
    }

    private fun episode(f: JSONObject, season: Int, number: Int): VideoEpisode {
        val meta = f.optJSONObject("metainfo")
        val label = VideoJson.str(meta?.optJSONObject("video"), "label")
        val width = label?.let { l -> l.substringBefore('x').trim().toIntOrNull() } ?: 0
        val quality = VideoJson.str(f, "quality")
        return VideoEpisode(
            id = VideoJson.str(f, "file_id")!!, season = season, number = number,
            name = VideoJson.str(f, "name")?.let { EXTENSION.replace(it, "") },
            durationSec = VideoJson.int(meta, "playtime_seconds"), voice = VideoJson.str(f, "translation"),
            hd = width >= 1280 || quality?.uppercase()?.contains("HD") == true,
            thumb = VideoJson.strings(f, "small_frames").firstOrNull()?.let(::absolute),
        )
    }

    fun absolute(path: String): String = if (path.startsWith("http")) path else "$SITE/" + path.trimStart('/')

    private fun error(message: String, cause: Throwable? = null) = VideoSourceException(VideoSource.OCKG, message, cause)
}
