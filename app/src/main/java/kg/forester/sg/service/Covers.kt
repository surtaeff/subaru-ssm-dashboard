package kg.forester.sg.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Covers for tracks that come without one (Bluetooth from the phone, players that send none): the iTunes Search
 * API by artist and title, no key needed. Found pictures and misses are kept in the app's files (at most 200 or
 * 30 MB, the longest unused go first), so a track is looked up once; offline it simply stays without a cover.
 */
object Covers {
    private const val TAG = "Covers"
    private const val MAX_FILES = 200
    private const val MAX_BYTES = 30L * 1024 * 1024
    /** A track iTunes didn't know is asked about again after a month (the catalogue grows). */
    private const val MISS_KEEP_MS = 30L * 24 * 3600_000
    /** A lookup that failed (no internet) waits this long before the next try for the same track. */
    private const val RETRY_MS = 10 * 60_000L

    private val failed = HashMap<String, Long>()

    /** The cover of [artist] – [title], from the cache or looked up once. Blocking: call off the main thread. */
    fun find(ctx: Context, artistIn: String, titleIn: String): Bitmap? {
        val (artist, t) = split(artistIn, titleIn)
        if (t.isEmpty()) return null
        val key = "${artist.trim().lowercase()}|${t.lowercase()}"
        val dir = File(ctx.filesDir, "covers")
        val name = hash(key)
        val pic = File(dir, "$name.jpg")
        val miss = File(dir, "$name.none")
        if (pic.exists()) {
            pic.setLastModified(System.currentTimeMillis())
            return runCatching { BitmapFactory.decodeFile(pic.path) }.getOrNull()
        }
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_KEEP_MS) return null
        synchronized(failed) { failed[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null } }
        val url = runCatching { lookup(artist.trim(), t) }.getOrElse {
            Log.d(TAG, "lookup failed: $it")
            synchronized(failed) { failed[key] = System.currentTimeMillis() }
            return null
        }
        dir.mkdirs()
        if (url == null) { runCatching { miss.writeText("") }; trim(dir); return null }
        val bytes = runCatching { open(url).inputStream.use { it.readBytes() } }.getOrElse {
            synchronized(failed) { failed[key] = System.currentTimeMillis() }
            return null
        }
        if (bytes.size !in 1_000..3_000_000) return null
        runCatching { pic.writeBytes(bytes) }
        trim(dir)
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    /**
     * Some phones send «Artist - Title» as the title over Bluetooth and no artist (or «Unknown»): the two are split
     * so the search has an artist to check the match against.
     */
    internal fun split(artist: String, title: String): Pair<String, String> {
        val a = artist.trim().takeUnless { it.lowercase() in setOf("unknown", "unknown artist", "неизвестно", "неизвестный исполнитель", "<unknown>") } ?: ""
        val t = title.trim()
        if (a.isEmpty()) {
            val m = Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(t)
            if (m != null) return m.groupValues[1].trim() to m.groupValues[2].trim()
        }
        return a to t
    }

    /**
     * The best match's picture: iTunes first, then Deezer (it knows far more Russian music, and its artist names are
     * in the original script, where iTunes often has only a Latin spelling); null when neither has the track.
     */
    private fun lookup(artist: String, title: String): String? {
        val first = runCatching { itunes(artist, title) }
        first.getOrNull()?.let { return it }
        val second = runCatching { deezer(artist, title) }
        second.getOrNull()?.let { return it }
        // both unreachable: a failure (asked again later), not a miss kept for a month
        if (first.isFailure && second.isFailure) throw first.exceptionOrNull()!!
        return null
    }

    /** Deezer's 1000×1000 cover of the best match (public search, no key). */
    private fun deezer(artist: String, title: String): String? {
        val bare = term("", title)
        val q = if (artist.isNotEmpty()) "artist:\"$artist\" track:\"$bare\"" else bare
        val text = open("https://api.deezer.com/search?q=${URLEncoder.encode(q, "UTF-8")}&limit=5").inputStream.bufferedReader().use { it.readText() }
        val results = JSONObject(text).optJSONArray("data") ?: return null
        val want = norm(artist)
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val art = r.optJSONObject("album")?.optString("cover_xl")?.takeIf { it.startsWith("http") } ?: continue
            val by = norm(r.optJSONObject("artist")?.optString("name") ?: "")
            if (want.isNotEmpty() && !(by.contains(want) || want.contains(by))) continue
            return art
        }
        return null
    }

    /** The 600×600 picture of the best match, null when iTunes has no such track; throws when it can't be asked. */
    private fun itunes(artist: String, title: String): String? {
        val q = term(artist, title)
        Log.d(TAG, "search: $q")
        val text = open("https://itunes.apple.com/search?term=${URLEncoder.encode(q, "UTF-8")}&media=music&entity=song&limit=5").inputStream.bufferedReader().use { it.readText() }
        val results = JSONObject(text).optJSONArray("results") ?: return null
        val want = norm(artist)
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val art = r.optString("artworkUrl100").takeIf { it.startsWith("http") } ?: continue
            // with an artist known, only a track of theirs: a namesake song's cover is worse than none
            val by = norm(r.optString("artistName"))
            if (want.isNotEmpty() && !(by.contains(want) || want.contains(by))) continue
            return art.replace("100x100bb", "600x600bb")
        }
        return null
    }

    /** What is searched for: the artist and the title without "(Remastered 2011)", "[Live]" and the like. */
    internal fun term(artist: String, title: String): String {
        val bare = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { title.trim() }
        return "${artist.trim()} $bare".trim()
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", "CarPulse/0.9 (github.com/surtaeff/subaru-ssm-dashboard)")
        connectTimeout = 4000; readTimeout = 6000
        if (responseCode != 200) { disconnect(); error("HTTP $responseCode") }
    }

    private fun hash(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** The longest unused first, until there are at most [MAX_FILES] files and [MAX_BYTES]. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (files.isNotEmpty() && (files.size > MAX_FILES || total > MAX_BYTES)) {
            val f = files.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }
}
