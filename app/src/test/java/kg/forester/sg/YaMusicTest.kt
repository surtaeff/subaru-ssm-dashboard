package kg.forester.sg

import kg.forester.sg.service.NowPlaying
import kg.forester.sg.service.pickNow
import kg.forester.sg.service.yandex.YaApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Yandex Music without an account: the link's sign, the answers' parsing, the OAuth redirect, the mini player's pick. */
class YaMusicTest {
    @Test fun signIsMd5OfSaltPathAndS() {
        // md5(salt + path without its «/» + s), as both reference clients compute it
        assertEquals(YaApi.md5(YaApi.SIGN_SALT + "get-mp3/abc/123" + "s1"), YaApi.sign("/get-mp3/abc/123", "s1"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", YaApi.md5(""))
        val url = YaApi.directUrl("s1.storage.yandex.net", "/rmusic/U2FsdGVk", "0005f2", "abc", "aac")
        assertEquals("https://s1.storage.yandex.net/get-aac/${YaApi.sign("/rmusic/U2FsdGVk", "abc")}/0005f2/rmusic/U2FsdGVk", url)
        assertTrue(YaApi.directUrl("h", "/p", "t", "s", "").contains("/get-mp3/"))
    }

    @Test fun downloadInfoXmlAndJson() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><download-info><host>s9.storage.yandex.net</host><path>/rmusic/abc</path>" +
            "<ts>0005f2a1</ts><region>-1</region><s>e4b1c</s></download-info>"
        assertEquals(listOf("s9.storage.yandex.net", "/rmusic/abc", "0005f2a1", "e4b1c"), YaApi.parseDownloadInfo(xml))
        assertEquals(listOf("h", "/p", "t", "s"), YaApi.parseDownloadInfo("""{"host":"h","path":"/p","ts":"t","s":"s"}"""))
        assertNull("a part missing", YaApi.parseDownloadInfo("<download-info><host>h</host><path>/p</path></download-info>"))
    }

    @Test fun variantIsTheBestUpTo320AacOnATie() {
        val arr = JSONArray("""[
            {"codec":"mp3","bitrateInKbps":192},{"codec":"aac","bitrateInKbps":192},{"codec":"mp3","bitrateInKbps":320},
            {"codec":"aac","bitrateInKbps":64},{"codec":"flac","bitrateInKbps":1411},{"codec":"aac","bitrateInKbps":2048}]""")
        assertEquals(2, YaApi.pickVariant(arr, mp3Only = false))
        val tie = JSONArray("""[{"codec":"mp3","bitrateInKbps":256},{"codec":"aac","bitrateInKbps":256}]""")
        assertEquals(1, YaApi.pickVariant(tie, mp3Only = false))
        assertEquals("after an AAC failure, MP3 only", 0, YaApi.pickVariant(tie, mp3Only = true))
        assertEquals(-1, YaApi.pickVariant(JSONArray("""[{"codec":"flac","bitrateInKbps":1411}]"""), false))
    }

    @Test fun oauthRedirect() {
        val r = YaApi.fromRedirect("https://music.yandex.ru/#access_token=y0_AgAAAA%2Babc&token_type=bearer&expires_in=31536000")
        assertEquals("y0_AgAAAA+abc", r?.first)
        assertEquals("31536000", r?.second)
        assertEquals("access_denied", YaApi.fromRedirect("https://oauth.yandex.ru/verification_code?error=access_denied")?.third)
        assertNull(YaApi.fromRedirect("https://passport.yandex.ru/auth?retpath=x"))
        assertNull(YaApi.fromRedirect(null))
    }

    @Test fun trackParsedAndKeptForResume() {
        val o = JSONObject("""{"id":"123","realId":"456","title":"Кукушка","version":"Live","durationMs":401000,"available":true,
            "artists":[{"id":"1","name":"Кино"},{"id":"2","name":""}],"albums":[{"id":"789","title":"Чёрный альбом","coverUri":"avatars.yandex.net/a/%%"}]}""")
        val t = YaApi.Track.parse(o)!!
        assertEquals("456", t.id)
        assertEquals("456:789", t.key)
        assertEquals("Кино", t.artists)
        assertEquals("Кукушка (Live)", t.fullTitle)
        assertEquals("https://avatars.yandex.net/a/400x400", YaApi.cover(t.cover, 400))
        assertEquals(t, YaApi.Track.load(t.save()))
        assertNull(YaApi.Track.parse(JSONObject("{}")))
        assertEquals("456", YaApi.idOf("456:789"))
    }

    @Test fun answersUnwrappedAndErrorsKept() {
        assertEquals("x", (YaApi.result(200, """{"invocationInfo":{},"result":{"a":"x"}}""") as JSONObject).getString("a"))
        val e = runCatching { YaApi.result(401, """{"error":{"name":"session-expired","message":"no"}}""") }.exceptionOrNull()
        assertTrue(YaApi.isAuth(e))
        assertEquals("Вход в Яндекс устарел, войдите снова", YaApi.message(e))
        assertFalse(YaApi.isAuth(runCatching { YaApi.result(200, "<html>") }.exceptionOrNull()))
    }

    @Test fun waveBatchAndSearch() {
        val b = YaApi.batch(JSONObject("""{"radioSessionId":"S1","batchId":"B1","sequence":[
            {"track":{"id":"1","title":"a","albums":[{"id":"10"}]}},{"track":{"id":"2","title":"b","available":false}},{"track":{"id":"3","title":"c"}}]}"""), "")
        assertEquals("S1", b.session)
        assertEquals("B1", b.batch)
        assertEquals("unavailable tracks are left out", listOf("1:10", "3"), b.tracks.map { it.key })
        val s = YaApi.parseSearch(JSONObject("""{"best":{"type":"artist"},"artists":{"results":[{"id":"7","name":"Кино","cover":{"uri":"x/%%"}}]},
            "tracks":{"results":[{"id":"5","title":"t"}]},"playlists":{"results":[{"uid":"9","kind":"3","title":"p","trackCount":4}]}}"""))
        assertEquals("artist", s.best)
        assertEquals("x/%%", s.artists[0].cover)
        assertEquals(1, s.tracks.size)
        assertEquals("9", s.playlists[0].owner)
        assertTrue(s.albums.isEmpty())
    }

    @Test fun accountAndSubscription() {
        val a = YaApi.Account.parse(JSONObject("""{"account":{"uid":"42","login":"ivan","displayName":"Иван"},"plus":{"hasPlus":true},
            "subscription":{"autoRenewable":[{"expires":"2027-03-01T00:00:00+03:00"}]}}"""))!!
        assertEquals("42", a.uid)
        assertTrue(a.plus)
        assertEquals("2027-03-01T00:00:00+03:00", a.until)
    }

    @Test fun miniPlayerFollowsYandex() {
        fun now(src: String, playing: Boolean = true) = NowPlaying(src, "Песня", "Исполнитель", null, playing)
        val ya = now("yandex", playing = false)
        assertEquals("Media on Yandex shows its track paused", ya, pickNow("yandex", ya, null, null, null))
        assertNull("files aren't Yandex's", pickNow("yandex", now("files", playing = false), null, null, null))
        val playing = now("yandex")
        assertEquals("what really plays with Media set elsewhere", playing, pickNow("files", playing, null, null, null))
    }
}
