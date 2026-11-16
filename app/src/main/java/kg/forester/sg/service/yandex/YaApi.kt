package kg.forester.sg.service.yandex

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Client of the unofficial Yandex Music API (api.music.yandex.net), the way the open clients MarshalX/yandex-music-api
 * and DECE2183/yamusic-tui use it (ported from FreeLite's YaMusicApi). The token goes only into the
 * `Authorization: OAuth …` header, never into a URL or the log. Answers come wrapped in {invocationInfo, result}.
 * Covers what the player needs: the account, «Моя волна» (rotor sessions and their feedback), likes and dislikes,
 * playlists (own and the personal ones: «Плейлист дня», «Дежавю»…), search, albums, artists and the direct
 * MP3/AAC link (download-info → XML → MD5 sign with the salt). Every call blocks: run it off the main thread.
 *
 * If Yandex changes the API, this is the one place to fix: the link (download-info and [SIGN_SALT]), the client
 * header [CLIENT], the wave (`rotor/session/…`), the personal playlists (`landing3`, then `feed`).
 */
object YaApi {
    const val BASE = "https://api.music.yandex.net/"
    const val CLIENT = "YandexMusicAndroid/24024312"
    const val UA = "okhttp/4.12.0"
    /** Salt of the download-info sign (the same in both reference clients). */
    const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
    const val WAVE = "user:onyourwave"
    const val FROM = "radio-mobile-user-onyourwave-default"
    private const val CONNECT_MS = 8000
    private const val READ_MS = 12000

    /** An HTTP or API failure; [auth]: the token is not accepted (sign in again). */
    class Err(val code: Int, msg: String) : IOException(msg) {
        val auth get() = code == 401
    }

    /** What to tell the driver about a failure. */
    fun message(e: Throwable?): String = when {
        e is Err && e.auth -> "Вход в Яндекс устарел, войдите снова"
        e is Err && e.code == 404 -> "Не найдено в Яндекс Музыке"
        else -> "Сервис недоступен, попробуйте позже"
    }

    fun isAuth(e: Throwable?) = e is Err && e.auth

    // ---- models -------------------------------------------------------------------------------------------

    data class Track(
        val id: String, val albumId: String = "", val title: String = "", val version: String = "", val artists: String = "",
        val album: String = "", val cover: String = "", val durationMs: Long = 0, val available: Boolean = true,
    ) {
        /** «id:albumId», as the rotor and the likes want it. */
        val key: String get() = if (albumId.isEmpty()) id else "$id:$albumId"
        val fullTitle: String get() = if (version.isEmpty()) title else "$title ($version)"

        /** The short form kept for «Продолжать» (the queue in the prefs). */
        fun save(): JSONObject = JSONObject().put("i", id).put("a", albumId).put("t", title).put("v", version).put("r", artists)
            .put("l", album).put("c", cover).put("d", durationMs)

        companion object {
            fun parse(o: JSONObject?): Track? {
                if (o == null) return null
                val id = o.optString("realId", "").ifEmpty { o.optString("id", "") }
                if (id.isEmpty()) return null
                val artists = o.optJSONArray("artists")?.let { ar -> (0 until ar.length()).mapNotNull { ar.optJSONObject(it)?.optString("name", "")?.takeIf(String::isNotEmpty) } }
                    .orEmpty().joinToString(", ")
                val al = o.optJSONArray("albums")?.optJSONObject(0)
                val cover = o.optString("coverUri", "").ifEmpty { al?.optString("coverUri", "").orEmpty() }.ifEmpty { o.optString("ogImage", "") }
                return Track(
                    id = id, albumId = al?.optString("id", "").orEmpty(), title = o.optString("title", ""), version = o.optString("version", ""),
                    artists = artists, album = al?.optString("title", "").orEmpty(), cover = cover, durationMs = o.optLong("durationMs", 0),
                    available = o.optBoolean("available", true),
                )
            }

            fun load(o: JSONObject?): Track? {
                val id = o?.optString("i", "").orEmpty()
                if (o == null || id.isEmpty()) return null
                return Track(id, o.optString("a", ""), o.optString("t", ""), o.optString("v", ""), o.optString("r", ""),
                    o.optString("l", ""), o.optString("c", ""), o.optLong("d", 0))
            }
        }
    }

    data class Playlist(val owner: String, val kind: String, val title: String, val cover: String, val count: Int, val type: String = "") {
        companion object {
            fun parse(o: JSONObject?, type: String = ""): Playlist? {
                if (o == null) return null
                val owner = o.optString("uid", "").ifEmpty { o.optJSONObject("owner")?.optString("uid", "").orEmpty() }
                val kind = o.optString("kind", "")
                if (owner.isEmpty() || kind.isEmpty()) return null
                return Playlist(owner, kind, o.optString("title", ""), coverOf(o.optJSONObject("cover")).ifEmpty { o.optString("ogImage", "") },
                    o.optInt("trackCount", 0), type)
            }
        }
    }

    data class Album(val id: String, val title: String, val artists: String, val cover: String, val year: Int) {
        companion object {
            fun parse(o: JSONObject?): Album? {
                val id = o?.optString("id", "").orEmpty()
                if (o == null || id.isEmpty()) return null
                val artists = o.optJSONArray("artists")?.let { ar -> (0 until ar.length()).mapNotNull { ar.optJSONObject(it)?.optString("name", "")?.takeIf(String::isNotEmpty) } }
                    .orEmpty().joinToString(", ")
                return Album(id, o.optString("title", ""), artists, o.optString("coverUri", "").ifEmpty { o.optString("ogImage", "") }, o.optInt("year", 0))
            }
        }
    }

    data class Artist(val id: String, val name: String, val cover: String) {
        companion object {
            fun parse(o: JSONObject?): Artist? {
                val id = o?.optString("id", "").orEmpty()
                if (o == null || id.isEmpty()) return null
                return Artist(id, o.optString("name", ""), coverOf(o.optJSONObject("cover")).ifEmpty { o.optString("ogImage", "") })
            }
        }
    }

    data class Account(val uid: String, val login: String, val name: String, val plus: Boolean, val until: String) {
        companion object {
            fun parse(r: JSONObject?): Account? {
                val a = r?.optJSONObject("account") ?: return null
                val login = a.optString("login", "")
                val name = a.optString("displayName", "").ifEmpty { a.optString("fullName", "") }.ifEmpty { login }
                val until = r.optJSONObject("subscription")?.optJSONArray("autoRenewable")?.optJSONObject(0)?.optString("expires", "")?.takeIf { it.isNotEmpty() }
                    ?: r.optJSONObject("permissions")?.optString("until", "").orEmpty()
                return Account(a.optString("uid", ""), login, name, r.optJSONObject("plus")?.optBoolean("hasPlus", false) == true, until)
            }
        }
    }

    /** A wave batch: its session, its id and the tracks. */
    data class Batch(val session: String, val batch: String, val tracks: List<Track>)

    /** One playable link; [preview]: 30 s without a Plus subscription. */
    data class Stream(val url: String, val codec: String, val bitrate: Int, val preview: Boolean)

    data class Search(val tracks: List<Track>, val albums: List<Album>, val artists: List<Artist>, val playlists: List<Playlist>, val best: String)

    // ---- helpers ------------------------------------------------------------------------------------------

    /** «avatars.yandex.net/get-music-content/…/%%» to the https link of a [size]×[size] picture ("" when there is none). */
    fun cover(uri: String?, size: Int): String {
        if (uri.isNullOrEmpty()) return ""
        val u = uri.replace("%%", "${size}x$size")
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http") -> u
            else -> "https://$u"
        }
    }

    private fun coverOf(c: JSONObject?): String {
        if (c == null) return ""
        return c.optString("uri", "").ifEmpty { c.optJSONArray("itemsUri")?.optString(0, "").orEmpty() }
    }

    fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The download sign: md5(salt + path without its leading «/» + s). */
    fun sign(path: String, s: String) = md5(SIGN_SALT + path.removePrefix("/") + s)

    /** https://{host}/get-{codec}/{sign}/{ts}{path}; MP3 when the codec is unknown. */
    fun directUrl(host: String, path: String, ts: String, s: String, codec: String?): String =
        "https://$host/get-${codec?.ifEmpty { null } ?: "mp3"}/${sign(path, s)}/$ts$path"

    private val XML_TAG = Regex("<(host|path|ts|s)>([^<]*)</\\1>")

    /** {host, path, ts, s} from the download-info XML (or its JSON form); null when something is missing. */
    fun parseDownloadInfo(body: String): List<String>? {
        val b = body.trim()
        val m = HashMap<String, String>()
        if (b.startsWith("{")) runCatching { JSONObject(b).let { o -> listOf("host", "path", "ts", "s").forEach { k -> m[k] = o.optString(k, "") } } }
        else XML_TAG.findAll(b).forEach { m[it.groupValues[1]] = it.groupValues[2].trim() }
        val r = listOf("host", "path", "ts", "s").map { m[it].orEmpty() }
        return r.takeIf { l -> l.none { it.isEmpty() } }
    }

    /** The variant to play: the highest bitrate up to 320 kbit/s, AAC on a tie; [mp3Only] after an AAC link failed. -1: none. */
    fun pickVariant(arr: JSONArray, mp3Only: Boolean): Int {
        var best = -1
        var bestRate = -1
        var bestAac = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val codec = o.optString("codec", "")
            val aac = codec == "aac"
            if (!(codec == "mp3" || aac) || (mp3Only && aac)) continue
            val rate = o.optInt("bitrateInKbps", 0)
            if (rate > 320) continue
            if (rate > bestRate || (rate == bestRate && aac && !bestAac)) { best = i; bestRate = rate; bestAac = aac }
        }
        return best
    }

    /** The OAuth redirect: (access_token, expires_in, error) from its fragment or query; null when it carries none. */
    fun fromRedirect(url: String?): Triple<String?, String?, String?>? {
        if (url == null) return null
        val h = url.indexOf('#')
        val q = url.indexOf('?')
        val frag = if (h >= 0) url.substring(h + 1) else if (q >= 0) url.substring(q + 1) else ""
        if (!frag.contains("access_token=") && !frag.contains("error=")) return null
        var tok: String? = null
        var exp: String? = null
        var err: String? = null
        for (kv in frag.split('&')) {
            val e = kv.indexOf('=')
            if (e <= 0) continue
            val v = runCatching { java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8") }.getOrDefault(kv.substring(e + 1))
            when (kv.substring(0, e)) { "access_token" -> tok = v; "expires_in" -> exp = v; "error" -> err = v }
        }
        return if (tok == null && err == null) null else Triple(tok, exp, err)
    }

    fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    fun form(vararg kv: String?): String = kv.toList().chunked(2).filter { it.size == 2 && it[1] != null }
        .joinToString("&") { URLEncoder.encode(it[0], "UTF-8") + "=" + URLEncoder.encode(it[1], "UTF-8") }

    // ---- HTTP ---------------------------------------------------------------------------------------------

    /** GET / POST to the API; the «result» (JSONObject, JSONArray or a string). */
    fun call(token: String?, method: String, path: String, query: String?, body: String?, contentType: String?): Any {
        val c = URL(BASE + path + if (query.isNullOrEmpty()) "" else "?$query").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.requestMethod = method
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", "ru")
            c.setRequestProperty("X-Yandex-Music-Client", CLIENT)
            c.setRequestProperty("User-Agent", UA)
            if (!token.isNullOrEmpty()) c.setRequestProperty("Authorization", "OAuth $token")
            if (body != null) {
                val b = body.toByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.let { read(it, 4 shl 20) }.orEmpty()
            return result(code, text)
        } finally {
            c.disconnect()
        }
    }

    /** Unwraps {invocationInfo, result} or {error}. */
    fun result(code: Int, text: String): Any {
        val o = runCatching { if (text.trim().startsWith("{")) JSONObject(text) else null }.getOrNull()
        if (code >= 400 || o == null || (o.has("error") && !o.has("result"))) {
            val e = o?.opt("error")
            val msg = when (e) { is JSONObject -> e.optString("name", "HTTP $code") + ": " + e.optString("message", ""); null -> "HTTP $code"; else -> e.toString() }
            throw Err(if (code >= 400) code else 500, msg)
        }
        return o.opt("result") ?: JSONObject()
    }

    fun read(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream(8192)
        val buf = ByteArray(8192)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException("too large")
            }
        }
        return out.toString("UTF-8")
    }

    private fun obj(r: Any) = r as? JSONObject ?: JSONObject()
    private fun arr(r: Any) = r as? JSONArray ?: JSONArray()

    fun get(token: String?, path: String, query: String? = null) = call(token, "GET", path, query, null, null)
    fun postForm(token: String?, path: String, form: String) = call(token, "POST", path, null, form, "application/x-www-form-urlencoded")
    fun postJson(token: String?, path: String, json: JSONObject) = call(token, "POST", path, null, json.toString(), "application/json")

    // ---- account ------------------------------------------------------------------------------------------

    fun account(token: String): Account {
        val a = Account.parse(obj(get(token, "account/status")))
        if (a == null || a.uid.isEmpty()) throw Err(401, "no account")
        return a
    }

    // ---- «Моя волна» --------------------------------------------------------------------------------------

    fun waveNew(token: String, seeds: List<String>, queue: List<String>): Batch {
        val b = JSONObject().put("seeds", JSONArray(seeds)).put("includeTracksInResponse", true).put("includeWaveModel", false).put("interactive", true)
        if (queue.isNotEmpty()) b.put("queue", JSONArray(queue))
        return batch(obj(postJson(token, "rotor/session/new", b)), "")
    }

    fun waveMore(token: String, session: String, queue: List<String>): Batch =
        batch(obj(postJson(token, "rotor/session/$session/tracks", JSONObject().put("queue", JSONArray(queue)))), session)

    fun batch(r: JSONObject, session: String): Batch {
        val seq = r.optJSONArray("sequence") ?: JSONArray()
        val tracks = (0 until seq.length()).mapNotNull { Track.parse(seq.optJSONObject(it)?.optJSONObject("track")) }.filter { it.available }
        return Batch(r.optString("radioSessionId", session), r.optString("batchId", ""), tracks)
    }

    /** radioStarted / trackStarted / trackFinished / skip / like / unlike: the wave adapts to them. */
    fun waveFeedback(token: String, session: String, batch: String, type: String, trackKey: String?, playedSec: Double) {
        val ev = JSONObject().put("type", type).put("timestamp", now())
        if (!trackKey.isNullOrEmpty()) ev.put("trackId", trackKey)
        if (playedSec > 0) ev.put("totalPlayedSeconds", Math.round(playedSec * 10) / 10.0)
        val b = JSONObject().put("event", ev).put("from", FROM)
        if (batch.isNotEmpty()) b.put("batchId", batch)
        postJson(token, "rotor/session/$session/feedback", b)
    }

    /** How much was listened to, for the history and the recommendations (best effort). */
    fun playAudio(token: String, uid: String, t: Track, from: String, playId: String, played: Double) {
        val ts = now()
        postForm(token, "play-audio", form("track-id", t.id, "from-cache", "false", "from", from, "play-id", playId, "uid", uid,
            "timestamp", ts, "track-length-seconds", (t.durationMs / 1000).toString(), "total-played-seconds", played.toString(),
            "end-position-seconds", played.toString(), "album-id", t.albumId, "client-now", ts))
    }

    // ---- likes --------------------------------------------------------------------------------------------

    /** The liked tracks' keys, newest first. */
    fun likedKeys(token: String, uid: String): List<String> {
        val ar = obj(get(token, "users/$uid/likes/tracks", "if-modified-since-revision=0")).optJSONObject("library")?.optJSONArray("tracks") ?: return emptyList()
        return (0 until ar.length()).mapNotNull { i ->
            val o = ar.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "").ifEmpty { return@mapNotNull null }
            val a = o.optString("albumId", "")
            if (a.isEmpty()) id else "$id:$a"
        }
    }

    /** The tracks of [keys] (100 at most a call). */
    fun tracks(token: String, keys: List<String>): List<Track> {
        if (keys.isEmpty()) return emptyList()
        val ar = arr(postForm(token, "tracks", form("track-ids", keys.joinToString(","), "with-positions", "false")))
        return (0 until ar.length()).mapNotNull { Track.parse(ar.optJSONObject(it)) }
    }

    fun like(token: String, uid: String, key: String, on: Boolean) {
        postForm(token, "users/$uid/likes/tracks/" + if (on) "add-multiple" else "remove", form("track-ids", key))
    }

    fun dislike(token: String, uid: String, key: String) {
        postForm(token, "users/$uid/dislikes/tracks/add-multiple", form("track-ids", key))
    }

    fun idOf(key: String) = key.substringBefore(':')

    // ---- playlists ----------------------------------------------------------------------------------------

    fun playlists(token: String, uid: String): List<Playlist> {
        val ar = arr(get(token, "users/$uid/playlists/list"))
        return (0 until ar.length()).mapNotNull { Playlist.parse(ar.optJSONObject(it)) }
    }

    /** «Плейлист дня», «Дежавю», «Премьера», «Тайник»…: from landing3, else from the feed. */
    fun personal(token: String): List<Playlist> {
        val out = ArrayList<Playlist>()
        var err: IOException? = null
        try {
            val blocks = obj(get(token, "landing3", "blocks=personalplaylists")).optJSONArray("blocks") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                val ent = blocks.optJSONObject(i)?.optJSONArray("entities") ?: continue
                for (k in 0 until ent.length()) {
                    val d = ent.optJSONObject(k)?.optJSONObject("data") ?: continue
                    Playlist.parse(d.optJSONObject("data"), d.optString("type", ""))?.let { out += it }
                }
            }
        } catch (e: IOException) { err = e }
        if (out.isEmpty()) {
            try {
                val gen = obj(get(token, "feed")).optJSONArray("generatedPlaylists") ?: JSONArray()
                for (i in 0 until gen.length()) {
                    val g = gen.optJSONObject(i) ?: continue
                    Playlist.parse(g.optJSONObject("data"), g.optString("type", ""))?.let { out += it }
                }
            } catch (e: IOException) { if (err == null) err = e }
        }
        if (out.isEmpty() && err != null) throw err
        return out
    }

    fun playlistTracks(token: String, owner: String, kind: String): List<Track> {
        val p = arr(get(token, "users/$owner/playlists", form("kinds", kind, "mixed", "true", "rich-tracks", "true"))).optJSONObject(0)
        val tr = p?.optJSONArray("tracks") ?: return emptyList()
        val out = ArrayList<Track>()
        val missing = ArrayList<String>()
        for (i in 0 until tr.length()) {
            val o = tr.optJSONObject(i) ?: continue
            val t = Track.parse(o.optJSONObject("track"))
            if (t != null) { if (t.available) out += t } else if (o.optString("id", "").isNotEmpty() && missing.size < 100) missing += o.optString("id")
        }
        if (out.isEmpty() && missing.isNotEmpty()) out += tracks(token, missing).filter { it.available }
        return out
    }

    // ---- search, albums, artists --------------------------------------------------------------------------

    fun search(token: String, text: String): Search = parseSearch(obj(get(token, "search", form("text", text, "type", "all", "page", "0", "nocorrect", "false"))))

    fun parseSearch(r: JSONObject): Search {
        fun results(k: String) = r.optJSONObject(k)?.optJSONArray("results") ?: JSONArray()
        fun <T> take(a: JSONArray, max: Int, parse: (JSONObject?) -> T?) = (0 until a.length()).asSequence().mapNotNull { parse(a.optJSONObject(it)) }.take(max).toList()
        return Search(
            take(results("tracks"), 30) { o -> Track.parse(o)?.takeIf { it.available } },
            take(results("albums"), 10, Album::parse),
            take(results("artists"), 10, Artist::parse),
            take(results("playlists"), 10) { Playlist.parse(it) },
            r.optJSONObject("best")?.optString("type", "").orEmpty(),
        )
    }

    fun albumTracks(token: String, albumId: String): List<Track> {
        val vols = obj(get(token, "albums/$albumId/with-tracks")).optJSONArray("volumes") ?: return emptyList()
        val out = ArrayList<Track>()
        for (v in 0 until vols.length()) {
            val vol = vols.optJSONArray(v) ?: continue
            for (i in 0 until vol.length()) Track.parse(vol.optJSONObject(i))?.takeIf { it.available }?.let { out += if (it.albumId.isEmpty()) it.copy(albumId = albumId) else it }
        }
        return out
    }

    fun artistTracks(token: String, artistId: String): List<Track> {
        val ar = obj(get(token, "artists/$artistId/tracks", form("page", "0", "page-size", "50"))).optJSONArray("tracks") ?: return emptyList()
        return (0 until ar.length()).mapNotNull { Track.parse(ar.optJSONObject(it))?.takeIf { t -> t.available } }
    }

    // ---- the stream ---------------------------------------------------------------------------------------

    /** The direct MP3/AAC link of a track (download-info → XML → signed URL). The info link lives about a minute. */
    fun stream(token: String, trackId: String, mp3Only: Boolean): Stream {
        val variants = arr(get(token, "tracks/$trackId/download-info"))
        val i = pickVariant(variants, mp3Only)
        if (i < 0) throw Err(404, "no stream")
        val v = variants.getJSONObject(i)
        val infoUrl = v.optString("downloadInfoUrl", "").ifEmpty { throw Err(404, "no download info") }
        val c = URL(infoUrl).openConnection() as HttpURLConnection
        val body = try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Authorization", "OAuth $token")
            val code = c.responseCode
            if (code >= 400) throw Err(code, "download-info $code")
            read(c.inputStream, 64 shl 10)
        } finally {
            c.disconnect()
        }
        val d = parseDownloadInfo(body) ?: throw Err(502, "bad download info")
        val codec = v.optString("codec", "mp3")
        return Stream(directUrl(d[0], d[1], d[2], d[3], codec), codec, v.optInt("bitrateInKbps", 0), v.optBoolean("preview", false))
    }
}
