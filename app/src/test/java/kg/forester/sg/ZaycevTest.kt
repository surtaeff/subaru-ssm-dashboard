package kg.forester.sg

import kg.forester.sg.service.MusicOnStart
import kg.forester.sg.service.NowPlaying
import kg.forester.sg.service.pickNow
import kg.forester.sg.service.zaycev.LinkCache
import kg.forester.sg.service.zaycev.ZaycevApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** zaycev.net: the site's answers read as they come (and as they may come incomplete), the play links' lifetime. */
class ZaycevTest {
    private val list = """{"page":1,"pagesCount":7,"trackIds":[24611123,7045611,999,24611123,24536462],
        "tracksInfo":{
          "7045611":{"size":6.26,"track":"Медуза","bitrate":320,"duration":"02:44","artistName":"MATRANG","playbackEnabled":true,
            "imageJpg":"https://cdnimg.zaycev.net/commonImage/album/308426/541_square100.jpg","explicit":false},
          "24536462":{"track":"Медуза (Remix)","artistName":"MATRANG","playbackEnabled":false,"imageWebp":"https://cdnimg.zaycev.net/x.webp"},
          "24611123":{"track":"Медуза","duration":"1:02:03","artistName":"MATRANG","explicit":true}
        },"limit":3,"popularInSearch":false}"""

    @Test fun pageKeepsTheIdsOrderAndSkipsWhatItCannotShow() {
        val p = ZaycevApi.parsePage(list)
        // 999 has no info, the second 24611123 is the same track again
        assertEquals(listOf(24611123L, 7045611L, 24536462L), p.tracks.map { it.id })
        val a = p.tracks[1]
        assertEquals("Медуза", a.title)
        assertEquals("MATRANG", a.artist)
        assertEquals(164_000L, a.durationMs)
        assertEquals("https://cdnimg.zaycev.net/commonImage/album/308426/541_square100.jpg", a.cover)
        assertTrue(a.playable)
        assertEquals(3_723_000L, p.tracks[0].durationMs)
        assertTrue("no playbackEnabled: playable until the site says otherwise", p.tracks[0].playable)
        assertTrue(p.tracks[0].explicit)
        assertFalse(p.tracks[2].playable)
        assertEquals("the webp when there is no jpg", "https://cdnimg.zaycev.net/x.webp", p.tracks[2].cover)
        assertEquals(1, p.page)
        assertEquals(7, p.pages)
        assertTrue(p.more)
        assertFalse(p.fallback)
    }

    @Test fun emptyOrBrokenAnswersGiveAnEmptyLastPage() {
        for (s in listOf("{}", "", "<html>502</html>", """{"trackIds":null}""", """{"trackIds":[1,2],"tracksInfo":{"1":{"track":"","artistName":""}}}""")) {
            val p = ZaycevApi.parsePage(s)
            assertTrue(s, p.tracks.isEmpty())
            assertFalse(s, p.more)
        }
        val last = ZaycevApi.parsePage("""{"page":3,"pagesCount":3,"trackIds":[1],"tracksInfo":{"1":{"track":"А","artistName":"Б"}}}""")
        assertEquals(1, last.tracks.size)
        assertFalse(last.more)
    }

    @Test fun aSearchThatFoundNothingIsMarked() {
        // the site answers an unknown query with its popular tracks and this flag
        val p = ZaycevApi.parsePage("""{"page":1,"pagesCount":150,"trackIds":[1],"tracksInfo":{"1":{"track":"Х","artistName":"Y"}},"popularInSearch":true}""")
        assertTrue(p.fallback)
    }

    @Test fun durations() {
        assertEquals(163_000L, ZaycevApi.duration("02:43"))
        assertEquals(3_723_000L, ZaycevApi.duration("1:02:03"))
        assertEquals(200_000L, ZaycevApi.duration(200))
        assertEquals(200_000L, ZaycevApi.duration("200"))
        assertEquals(0L, ZaycevApi.duration(null))
        assertEquals(0L, ZaycevApi.duration(""))
        assertEquals(0L, ZaycevApi.duration("--:--"))
    }

    @Test fun hashesBlockedAndMissing() {
        val h = ZaycevApi.parseHashes("""{"tracks":[{"id":24611123,"streaming":"d03bd96d89774e0b","download":"df39d86c887d4408"},
            {"id":25077263,"streaming":false,"download":false},{"streaming":"abc"},{"id":5}]}""")
        assertEquals("d03bd96d89774e0b", h[24611123L])
        assertEquals("blocked", "", h[25077263L])
        assertEquals("no streaming field: blocked as well", "", h[5L])
        assertEquals(3, h.size)
        assertTrue(ZaycevApi.parseHashes("{}").isEmpty())
        assertTrue(ZaycevApi.parseHashes("oops").isEmpty())
        val body = JSONObject(ZaycevApi.hashesBody(listOf(1L, 2L)))
        assertEquals(2, body.getJSONArray("trackIds").length())
        assertFalse(body.getBoolean("subscription"))
    }

    @Test fun playLinkAndBigCover() {
        assertEquals("https://dl.zaycev.net/track_auth/24611123/Cq23", ZaycevApi.parsePlayUrl("""{"url":"https://dl.zaycev.net/track_auth/24611123/Cq23"}"""))
        assertNull(ZaycevApi.parsePlayUrl("""{"url":""}"""))
        assertNull(ZaycevApi.parsePlayUrl("""{"url":"javascript:x"}"""))
        assertNull(ZaycevApi.parsePlayUrl("""{}"""))
        assertNull(ZaycevApi.parsePlayUrl("not json"))
        assertEquals("https://c/219_square250.jpg", ZaycevApi.parseBigCover("""{"info":{"imageJpg":"https://c/1_square100.jpg","bigImageJpg":"https://c/219_square250.jpg"}}"""))
        assertNull(ZaycevApi.parseBigCover("""{"info":{}}"""))
    }

    @Test fun addresses() {
        assertEquals("https://zaycev.net/api/external/pages/search/tracks?q=%D0%BA%D0%B8%D0%BD%D0%BE%20%D0%B3%D1%80%D1%83%D0%BF%D0%BF%D0%B0&page=2&limit=20",
            ZaycevApi.searchUrl("  кино группа ", 2))
        assertEquals("https://zaycev.net/api/external/pages/index/top?page=1&limit=50&period=week&entity=track", ZaycevApi.topUrl("week", 1))
        assertEquals("https://zaycev.net/api/external/pages/new?page=3&limit=50&limitRecommended=0", ZaycevApi.newUrl(3))
        assertEquals("https://zaycev.net/api/external/track/play/d03bd96d89774e0b", ZaycevApi.playUrl("d03bd96d89774e0b"))
    }

    @Test fun trackKeptForTheNextStart() {
        val t = ZaycevApi.Track(24611123, "Медуза", "MATRANG", 163_000, "https://c/1.jpg")
        assertEquals(t, ZaycevApi.Track.load(JSONObject(t.save().toString())))
        assertNull(ZaycevApi.Track.load(null))
        assertNull(ZaycevApi.Track.load(JSONObject("""{"t":"no id"}""")))
        assertEquals("24611123", t.key)
    }

    @Test fun linksLastTheirTime() {
        val c = LinkCache(15 * 60_000L)
        c.put(1, "https://a", 1_000)
        assertEquals("https://a", c.get(1, 1_000 + 14 * 60_000L))
        assertNull("expired", c.get(1, 1_000 + 15 * 60_000L))
        assertNull("a clock that went back", c.get(1, 0))
        assertNull(c.get(2, 1_000))
        c.drop(1)
        assertNull(c.get(1, 2_000))
    }

    @Test fun messages() {
        assertEquals("Этот трек недоступен для прослушивания", ZaycevApi.message(ZaycevApi.Err(ZaycevApi.BLOCKED, "blocked")))
        assertEquals("Нет связи с zaycev.net: проверьте интернет", ZaycevApi.message(java.net.UnknownHostException("zaycev.net")))
        assertEquals("Нет связи с zaycev.net: проверьте интернет", ZaycevApi.message(IOException("reset")))
        assertEquals("zaycev.net не отвечает, попробуйте позже", ZaycevApi.message(ZaycevApi.Err(502, "bad gateway")))
    }

    @Test fun theSourceIsKnownToTheMiniPlayerAndTheStart() {
        val own = NowPlaying("zaycev", "Медуза", "MATRANG", null, playing = false)
        assertEquals(own, pickNow("zaycev", own, null, null, null))
        assertNull("files mode doesn't show a paused zaycev.net track", pickNow("files", own, null, null, null))
        assertEquals("zaycev", MusicOnStart.plan(true, false, "zaycev", ""))
    }
}
