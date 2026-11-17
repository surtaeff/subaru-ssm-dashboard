package kg.forester.sg.torrent

import kg.forester.sg.video.VideoJson
import kg.forester.sg.video.VideoNet
import org.json.JSONArray
import org.json.JSONObject

/** One torrent release from the JacRed balancer. */
data class TorrentRelease(
    val magnet: String,
    val name: String,
    val originalName: String?,
    val title: String,
    val year: Int,
    val quality: Int,
    val voices: List<String>,
    val sizeBytes: Long,
    val sizeName: String,
    val seeders: Int,
    val peers: Int,
    val tracker: String,
    val isSeries: Boolean,
) {
    /** A short label for the version chip: «1080p · Дубляж · 2.3 GB · 6 сид». */
    val label: String
        get() = listOfNotNull(
            quality.takeIf { it > 0 }?.let { "${it}p" },
            voices.firstOrNull(),
            sizeName.takeIf { it.isNotBlank() },
            "$seeders сид",
        ).joinToString(" · ")
}

/**
 * JacRed (immisterio's Jackett fork) client. Field names and quirks confirmed live on jacred.su on 2026-11-05 (see the
 * torrent streaming plan): the year field is `relased` (sic), `kinopoisk=` is ignored, `exact=true&year=` filters well,
 * no API key is needed, and the public JacRed hosts die periodically — we cycle through them. Hosts are ordered by
 * priority; the first one answering is used.
 */
class JacredClient(val hosts: List<String> = DEFAULT_HOSTS) {

    /**
     * Search releases by title (+year). An empty list or an error on one host → try the next. Sorted by seeders so the
     * well-seeded releases come up first.
     */
    fun search(title: String, year: Int? = null, exact: Boolean = true): List<TorrentRelease> {
        for (host in hosts) {
            val releases = runCatching { query(host, title, year, exact) }.getOrNull()
            if (!releases.isNullOrEmpty()) return releases.sortedByDescending { it.seeders }
        }
        return emptyList()
    }

    private fun query(host: String, title: String, year: Int?, exact: Boolean): List<TorrentRelease> {
        val sb = StringBuilder(host).append("/api/v1.0/torrents?search=").append(VideoNet.q(title))
        if (year != null) sb.append("&year=").append(year)
        if (exact) sb.append("&exact=true")
        val body = VideoNet.get(sb.toString(), 12_000)
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
    }

    private fun parse(o: JSONObject?): TorrentRelease? {
        if (o == null) return null
        val magnet = VideoJson.str(o, "magnet") ?: return null
        if (!magnet.startsWith("magnet:")) return null
        val name = VideoJson.str(o, "name") ?: VideoJson.str(o, "title") ?: return null
        return TorrentRelease(
            magnet = magnet,
            name = name,
            originalName = VideoJson.str(o, "originalname"),
            title = VideoJson.str(o, "title") ?: name,
            year = VideoJson.int(o, "relased"),
            quality = VideoJson.int(o, "quality"),
            voices = VideoJson.strings(o, "voices"),
            sizeBytes = VideoJson.str(o, "size")?.toLongOrNull() ?: 0L,
            sizeName = VideoJson.str(o, "sizeName") ?: "",
            seeders = VideoJson.int(o, "sid"),
            peers = VideoJson.int(o, "pir"),
            tracker = VideoJson.str(o, "tracker") ?: "",
            isSeries = (o.optJSONArray("seasons")?.length() ?: 0) > 0,
        )
    }

    companion object {
        /** Priority ordered. jacred.su is the alive public instance on 2026-11-05; the rest are a fallback. */
        val DEFAULT_HOSTS = listOf("https://jacred.su", "https://jac.red", "http://jacred.xyz")
    }
}
