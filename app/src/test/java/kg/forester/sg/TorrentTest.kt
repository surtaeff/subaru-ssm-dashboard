package kg.forester.sg

import kg.forester.sg.torrent.JacredClient
import kg.forester.sg.torrent.TorrClient
import kg.forester.sg.torrent.TorrEngine
import kg.forester.sg.torrent.TorrentCatalog
import kg.forester.sg.torrent.TorrentRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTest {

    // ---- TorrClient: URL construction, no network.

    @Test fun streamUrl_encodesPathSegment() {
        val c = TorrClient("http://127.0.0.1:8090")
        val url = c.streamUrl("ABC123", 7, "Papa Loses His Keys (2024) [1080p].mkv")
        assertTrue(url.startsWith("http://127.0.0.1:8090/stream/"))
        assertTrue(url.contains("?link=ABC123&index=7&play"))
        // the space in the filename is URL-encoded, no raw space in the segment
        assertFalse(url.contains(" "))
    }

    @Test fun streamUrl_fallsBackToVideoWhenNameBlank() {
        val c = TorrClient()
        assertTrue(c.streamUrl("x", 0, "").contains("/stream/video?"))
        assertTrue(c.streamUrl("x", 0, "/path/to/file.mp4").contains("/stream/file.mp4?"))
    }

    @Test fun baseUrl_defaults() {
        assertEquals("http://127.0.0.1:8090", TorrClient.DEFAULT_BASE_URL)
        assertEquals(8090, TorrClient.DEFAULT_PORT)
    }

    // ---- TorrEngine.Settings: the JSON matches what TorrServer /settings accepts.

    @Test fun defaultSettings_areCarSafe() {
        val s = TorrEngine.Settings.DEFAULT
        // 128 MiB — car head units run a lot at once, so not the TV 384 MiB
        assertEquals(128L * 1024 * 1024, s.cacheBytes)
        assertEquals(true, s.useDisk)
        assertEquals(true, s.removeCacheOnDrop)
        assertEquals(40, s.connectionsLimit)
    }

    @Test fun settings_json_escapesPath() {
        val json = TorrEngine.Settings.DEFAULT.toJson("""/data/user/0/kg.forester.sg/files/torr"server\cache""")
        assertTrue(json.contains("\"UseDisk\": true"))
        assertTrue(json.contains("\"CacheSize\": 134217728"))
        // the path's double-quote and backslash go through escaped: TorrServer must receive valid JSON
        assertTrue(json.contains("torr\\\"server\\\\cache"))
    }

    @Test fun settings_json_hasEverySetting() {
        val json = TorrEngine.Settings.DEFAULT.toJson("/x")
        for (key in listOf(
            "CacheSize", "ReaderReadAHead", "PreloadCache", "UseDisk",
            "TorrentsSavePath", "RemoveCacheOnDrop", "ConnectionsLimit", "EnableIPv6",
        )) assertTrue("missing $key", json.contains("\"$key\""))
    }

    @Test fun cacheRungs_startAt64_areOrdered() {
        val rungs = TorrEngine.Settings.CACHE_RUNGS_MB
        assertEquals(64, rungs.first())
        for (i in 1 until rungs.size) assertTrue("rungs out of order", rungs[i] > rungs[i - 1])
    }

    // ---- JacredClient: parse a frozen sample from the balancer.

    @Test fun jacred_parsesLiveShape() {
        val body = """
            [
              {
                "magnet": "magnet:?xt=urn:btih:ABCDEF0123456789&dn=Some.Movie.2024.1080p",
                "name": "Какой-то фильм",
                "originalname": "Some Movie",
                "title": "Some Movie / Какой-то фильм",
                "relased": 2024,
                "quality": 1080,
                "voices": ["Дубляж", "Гаврилов"],
                "size": "2456789123",
                "sizeName": "2.3 GB",
                "sid": 42,
                "pir": 7,
                "tracker": "rutor",
                "seasons": []
              },
              {
                "magnet": "magnet:?xt=urn:btih:0BADF00D0000&dn=Series",
                "name": "Сериал",
                "title": "Сериал",
                "relased": 2023,
                "quality": 720,
                "voices": [],
                "size": "0",
                "sizeName": "",
                "sid": 0,
                "pir": 0,
                "tracker": "",
                "seasons": [1, 2, 3]
              },
              {
                "magnet": "notamagnet",
                "name": "Bad"
              }
            ]
        """.trimIndent()
        val releases = parseJacred(body)
        assertEquals(2, releases.size)
        val movie = releases[0]
        assertEquals(2024, movie.year)
        assertEquals(1080, movie.quality)
        assertEquals(listOf("Дубляж", "Гаврилов"), movie.voices)
        assertEquals(2_456_789_123L, movie.sizeBytes)
        assertEquals("2.3 GB", movie.sizeName)
        assertEquals(42, movie.seeders)
        assertFalse("movie must not be a series", movie.isSeries)
        val series = releases[1]
        assertTrue(series.isSeries)
        assertEquals(0, series.seeders)
    }

    @Test fun release_label_isHumanReadable() {
        val r = TorrentRelease(
            magnet = "magnet:?xt=urn:btih:X", name = "X", originalName = null, title = "X",
            year = 2024, quality = 1080, voices = listOf("Дубляж"), sizeBytes = 0, sizeName = "2.3 GB",
            seeders = 42, peers = 1, tracker = "rutor", isSeries = false,
        )
        assertEquals("1080p · Дубляж · 2.3 GB · 42 сид", r.label)
    }

    @Test fun release_label_handlesMissingFields() {
        val r = TorrentRelease(
            magnet = "magnet:?xt=urn:btih:X", name = "X", originalName = null, title = "X",
            year = 0, quality = 0, voices = emptyList(), sizeBytes = 0, sizeName = "",
            seeders = 3, peers = 0, tracker = "", isSeries = false,
        )
        assertEquals("3 сид", r.label)
    }

    @Test fun jacred_defaultHosts_startWithJacredSu() {
        assertEquals("https://jacred.su", JacredClient.DEFAULT_HOSTS.first())
    }

    // ---- TorrentCatalog helpers.

    @Test fun infohash_extractsBtih() {
        val m = "magnet:?xt=urn:btih:abcdef0123456789&dn=File&tr=udp://tracker"
        assertEquals("abcdef0123456789", TorrentCatalog.infohash(m))
    }

    @Test fun infohash_fallsBackToWholeLink() {
        // no btih at all → the whole string is the id (no crash)
        assertEquals("whatever", TorrentCatalog.infohash("whatever"))
    }

    @Test fun message_picksErrorByCause() {
        assertEquals("Нет интернета", TorrentCatalog.message(java.net.UnknownHostException("jacred.su")))
        val timeout = java.net.SocketTimeoutException()
        assertTrue(TorrentCatalog.message(timeout).contains("jacred"))
        assertEquals("Ничего не нашлось", TorrentCatalog.message(null))
    }

    // ---- Parser shim: JacredClient.parse is private, so we invoke it via reflection on a frozen body.

    private fun parseJacred(body: String): List<TorrentRelease> {
        val arr = org.json.JSONArray(body)
        val out = ArrayList<TorrentRelease>()
        val client = JacredClient()
        val m = JacredClient::class.java.getDeclaredMethod("parse", org.json.JSONObject::class.java)
        m.isAccessible = true
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = m.invoke(client, o) as TorrentRelease?
            if (r != null) out += r
        }
        return out
    }

    // sanity: parseJacred throws when the body is not a JSON array
    @Test(expected = org.json.JSONException::class)
    fun parseJacred_onNonArrayThrows() {
        parseJacred("""{"not":"an array"}""")
    }

    // reflection stays stable: the private method must be there (guards against a rename)
    @Test fun parse_methodIsReachable() {
        val m = JacredClient::class.java.getDeclaredMethod("parse", org.json.JSONObject::class.java)
        assertNotNull(m)
    }

    @Test fun badMagnet_isDropped() {
        val releases = parseJacred("""[{"magnet":"notamagnet","name":"Bad"}]""")
        assertTrue(releases.isEmpty())
    }

    @Test fun missingName_isDropped() {
        val releases = parseJacred("""[{"magnet":"magnet:?xt=urn:btih:X"}]""")
        assertTrue(releases.isEmpty())
    }

    @Test fun nullObject_returnsNull() {
        val m = JacredClient::class.java.getDeclaredMethod("parse", org.json.JSONObject::class.java)
        m.isAccessible = true
        assertNull(m.invoke(JacredClient(), null as org.json.JSONObject?))
    }

    // ---- TorrentDriving (TorrentUi internal object): pure, testable from JVM.

    @Test fun torrentDriving_gatePickFollowsMoving() {
        val cls = Class.forName("kg.forester.sg.ui.TorrentDriving")
        val instance = cls.getDeclaredField("INSTANCE").get(null)
        val gate = cls.getDeclaredMethod("gatePick", Boolean::class.javaPrimitiveType)
        // car is moving → pick is swallowed; car is still → pick goes through
        assertTrue(gate.invoke(instance, true) as Boolean)
        assertFalse(gate.invoke(instance, false) as Boolean)
    }

    @Test fun torrentDriving_wordingMatchesOwnersPreLockDropVocabulary() {
        val cls = Class.forName("kg.forester.sg.ui.TorrentDriving")
        val title = cls.getDeclaredField("TITLE").get(null) as String
        val body = cls.getDeclaredField("BODY").get(null) as String
        assertTrue("TITLE must talk about «на стоянке»", title.contains("на стоянке"))
        assertTrue("BODY must say «Машина едет»", body.contains("Машина едет"))
        assertTrue("BODY must tell the driver to stop", body.contains("Остановитесь"))
    }

    // Sanity on the project's shared driving-lock helper this file reuses: the hysteresis is kept at the documented
    // 5 km/h-start / 2 km/h-stop, so if someone changes it under us the test fails and the torrent lock gets re-reviewed.
    @Test fun sharedHysteresis_stillAt5and2() {
        val cls = Class.forName("kg.forester.sg.video.VideoDriving")
        val vd = cls.getDeclaredField("INSTANCE").get(null)
        val mov = cls.getDeclaredField("MOVING_KMH").getFloat(vd)
        val stp = cls.getDeclaredField("STOPPED_KMH").getFloat(vd)
        assertEquals(5f, mov, 0.01f)
        assertEquals(2f, stp, 0.01f)
    }
}
