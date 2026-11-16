package kg.forester.sg.video

import java.util.Locale

/**
 * «Видео»: the model shared by the ts.kg / oc.kg parsers, the screen and the player. Ported from FreeLite's
 * VideoModel.java (itself a port of the TV launcher's source/Model.kt). Plain Kotlin, no Android types, so the parsers
 * are checked on the JVM.
 */
enum class VideoSource(val key: String, val label: String) {
    TSKG("tskg", "ts.kg"),
    OCKG("ockg", "oc.kg");

    companion object {
        fun byKey(k: String): VideoSource? = entries.firstOrNull { it.key.equals(k, ignoreCase = true) }
    }
}

/** «tskg:friends» → (TSKG, "friends"); null when the key is broken. */
fun parseVideoKey(key: String?): Pair<VideoSource, String>? {
    if (key == null) return null
    val i = key.indexOf(':')
    if (i <= 0 || i == key.length - 1) return null
    val s = VideoSource.byKey(key.substring(0, i)) ?: return null
    return s to key.substring(i + 1)
}

/** One poster in a row or a search result. [year] 0 = unknown, [rating] 0 = none. */
data class VideoCard(
    val source: VideoSource,
    val id: String,
    val title: String,
    val original: String? = null,
    val year: Int = 0,
    val poster: String? = null,
    val subtitle: String? = null,
    val rating: Double = 0.0,
) {
    val key: String get() = "${source.key}:$id"
}

data class VideoEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val name: String? = null,
    val durationSec: Int = 0,
    val voice: String? = null,
    val hd: Boolean = false,
    val thumb: String? = null,
)

class VideoDetails(
    val card: VideoCard,
    val series: Boolean,
    /** In watching order: season, then number. */
    val episodes: List<VideoEpisode>,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val backdrop: String? = null,
    val ratingKp: Double = 0.0,
    val ratingImdb: Double = 0.0,
    val kpId: String? = null,
    val imdbId: String? = null,
    /** Linked titles (oc.kg seasons or parts) including this one, in order; subtitle «Сезон N» or the year. */
    val related: List<VideoCard> = emptyList(),
    /** «Похожие фильмы»: other titles, not parts of this one. */
    val similar: List<VideoCard> = emptyList(),
) {
    val key: String get() = card.key

    fun episode(id: String?): VideoEpisode? = id?.let { e -> episodes.firstOrNull { it.id == e } }
    fun indexOf(id: String?): Int = episodes.indexOfFirst { it.id == id }

    /** «С1 Э3 · Name» for series, null for movies. */
    fun label(e: VideoEpisode?): String? =
        if (e == null || !series) null else "С${e.season} Э${e.number}" + (e.name?.let { " · $it" } ?: "")
}

data class VideoStream(val url: String, val hls: Boolean, val subtitles: String? = null)

data class VideoRow(val id: String, val title: String, val source: VideoSource, val items: List<VideoCard>)

/**
 * A site failing: carries the site so the message can name it. [blocked]: it answered 403, which ts.kg and oc.kg do to
 * every address outside Kyrgyzstan.
 */
class VideoSourceException(val source: VideoSource, message: String, cause: Throwable? = null, val blocked: Boolean = false) :
    Exception(message, cause)

object VideoText {
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{Nd}]+")

    /** New releases: the newest year first, the site's order inside a year, unknown year last. */
    fun newestYearFirst(list: List<VideoCard>): List<VideoCard> =
        list.sortedByDescending { if (it.year == 0) Int.MIN_VALUE else it.year }

    fun distinct(list: List<VideoCard>): List<VideoCard> = list.distinctBy { it.key }

    /** Lower case, ё→е, letters and digits separated by single spaces. */
    fun normalize(s: String?): String =
        if (s == null) "" else NOT_LETTER_OR_DIGIT.replace(s.lowercase(Locale.ROOT).replace('ё', 'е'), " ").trim()

    /** «1:02:03» / «42:05» for a position in ms. */
    fun time(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** «1 ч 52 мин» / «42 мин» for a duration in seconds, null when unknown. */
    fun duration(sec: Int): String? {
        if (sec <= 0) return null
        val m = Math.round(sec / 60f)
        if (m < 60) return "$m мин"
        return "${m / 60} ч" + if (m % 60 == 0) "" else " ${m % 60} мин"
    }

    fun blankToNull(s: String?): String? = s?.takeIf { it.isNotBlank() }
}
