package kg.forester.sg

import kg.forester.sg.video.OcKgParser
import kg.forester.sg.video.Rutube
import kg.forester.sg.video.TsKgParser
import kg.forester.sg.video.VideoCard
import kg.forester.sg.video.VideoDetails
import kg.forester.sg.video.VideoEpisode
import kg.forester.sg.video.VideoPolicy
import kg.forester.sg.video.VideoRegion
import kg.forester.sg.video.VideoSource
import kg.forester.sg.video.VideoText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Видео»: the parsers on pieces shaped like the sites' answers (2026-11), what to resume and play next, the driving lock, the region. */
class VideoTest {
    private val showPage = """
        <html><body>
        <div class="app-h1">Друзья</div><div class="app-h2">Friends</div><div class="app-h3"> 1994-2004 </div>
        <div class="app-show-info"><p>Шестеро друзей в Нью-Йорке.</p><p class="text-danger">реклама</p></div>
        <ul class="app-show-tags"><li><a href="/genre/comedy">Комедия</a></li><li><a class="app-show-tags-flag" title="США" href="/country/us">.</a></li></ul>
        <a href="/show/friends/1/2" class="episode as1" data-id="13316">2</a>
        <a href="/show/friends/1/1" class="episode as1" data-id="13306">1</a>
        <a href="/show/friends/2/1" class="episode as1" data-id="14001">1</a>
        <table><tr><td>
          <span><a href="/show/friends/1/1" class="btn"><strong class="text-danger">HD </strong></a></span>
          <span><a href="/show/friends/1/1" class="text-primary" id="app-show-episode-title-13306"><strong>1</strong>: The One Where It All Began</a> <small class="text-muted">| 22:44</small></span>
          <small class="text-muted clearfix"> Нота (профессиональная многоголосая) [субтитры]</small>
        </td></tr></table>
        </body></html>
    """.trimIndent()

    @Test fun tsKgShowPage() {
        val d = TsKgParser.show(showPage, "friends")
        assertEquals("Друзья", d.card.title)
        assertEquals("Friends", d.card.original)
        assertEquals(1994, d.card.year)
        assertEquals("Шестеро друзей в Нью-Йорке.", d.description)
        assertEquals(listOf("Комедия"), d.genres)
        assertEquals(listOf("США"), d.countries)
        assertTrue(d.series)
        // in watching order whatever the page's order
        assertEquals(listOf("13306", "13316", "14001"), d.episodes.map { it.id })
        val e = d.episodes[0]
        assertEquals("The One Where It All Began", e.name)
        assertEquals(22 * 60 + 44, e.durationSec)
        assertEquals("Нота (профессиональная многоголосая)", e.voice)
        assertTrue(e.hd)
    }

    @Test fun tsKgEpisodeAndSearch() {
        val s = TsKgParser.episode("""{"id":13306,"video":{"url":"https:\/\/node3.ts.kg\/01\/friends\/1\/smil:1.smil\/playlist.m3u8","videotype":"HLS","subtitles":"https:\/\/node3.ts.kg\/01\/friends\/1\/1.vtt"}}""")
        assertEquals("https://node3.ts.kg/01/friends/1/smil:1.smil/playlist.m3u8", s.url)
        assertTrue(s.hls)
        val found = TsKgParser.search("""[{"url":"/show/friends","name":"Друзья (Friends)"},{"url":"/show/friends","name":"Друзья"},{"url":"/news/1","name":"x"}]""")
        assertEquals(1, found.size)
        assertEquals("Friends", found[0].original)
        assertEquals("https://www.ts.kg/posters/friends.png", found[0].poster)
        assertEquals("Задорные друзья" to "Smiling Friends", TsKgParser.splitName("Задорные друзья | Улыбающиеся друзья (Smiling Friends)"))
    }

    private val movie = """
        {"json":[{"status":200,"message":null,"response":{"movie":{"movie_id":"24018","name":"Аллен","international_name":"Allen","year":"2026",
        "genres":["Драма","Комедия"],"countries":["Австралия"],"rating_imdb_value":"5.3","rating_kinopoisk_value":null,
        "imdb_url":"https://www.imdb.com/title/tt1234567/","cover":"/media/thumbnails/a/b/0123456789abcdef0123456789abcdef_40x60.jpg",
        "covers":[{"thumbnail":"/media/thumbnails/a/b/0123456789abcdef0123456789abcdef_200x296.jpg","original":"/media/images/a/b/x/image.jpg"}],
        "files":[{"file_id":"31962","name":"Allen.mp4","path":"/home/video/video/b/6/Allen.mp4","is_dir":false,
          "links":{"license":"pl.php?player=ftp&uid=1953&movieid=24018&fileid=31962&v=6e52"},"metainfo":{"playtime_seconds":"5400","video":{"label":"1920x800"}}}],
        "other_movies":[]}}}]}
    """.trimIndent()

    @Test fun ocKgMovie() {
        val d = OcKgParser.movie(movie)
        assertEquals("ockg:24018", d.key)
        assertEquals("Allen", d.card.original)
        assertEquals(2026, d.card.year)
        assertFalse(d.series)
        assertEquals(1, d.episodes.size)
        assertEquals("Allen", d.episodes[0].name)
        assertEquals(5400, d.episodes[0].durationSec)
        assertTrue(d.episodes[0].hd)
        assertEquals("tt1234567", d.imdbId)
        assertEquals("https://www.oc.kg/media/thumbnails/a/b/0123456789abcdef0123456789abcdef_200x296.jpg", d.card.poster)
        val (license, path) = OcKgParser.file(movie, "31962")
        assertEquals("pl.php?player=ftp&uid=1953&movieid=24018&fileid=31962&v=6e52", license)
        assertEquals("https://p1.oc.kg:8082/video/b/6/Allen.mp4", OcKgParser.pathToUrl(path))
        // the player page: the first mp4 that isn't the ad background
        assertEquals("https://p1.oc.kg:8082/video/b/6/Allen.mp4?an=0",
            OcKgParser.playerMp4("""src: "/background/c_x.mp4" … src: "https://p1.oc.kg:8082/video/b/6/Allen.mp4?an=0""""))
    }

    @Test fun ocKgErrorStatus() {
        val e = runCatching { OcKgParser.movie("""{"json":[{"status":404,"message":"not found","response":null}]}""") }.exceptionOrNull()
        assertEquals("not found", e?.message)
    }

    @Test fun rutubeSkipsAdultHiddenPaidAndKeepsRights() {
        val list = Rutube.parse("""{"results":[
            {"id":"9c6df91219c4bb53ded45019c8475768","title":"Маша","duration":5891,"author":{"name":"Канал"},"thumbnail_url":"https://pic/1.jpg","is_licensed":true},
            {"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"18+","is_adult":true},
            {"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","title":"Платно","is_paid":true},
            {"id":"not-an-id","title":"x"}]}""")
        assertEquals(1, list.size)
        assertEquals("Канал", list[0].author)
        assertTrue(list[0].licensed)
    }

    private fun ep(id: String, s: Int, n: Int) = VideoEpisode(id, s, n)

    @Test fun resumeAndNext() {
        val d = VideoDetails(VideoCard(VideoSource.OCKG, "1", "Свинка Пеппа 1 сезон"), true, listOf(ep("a", 1, 1), ep("b", 1, 2)),
            related = listOf(VideoCard(VideoSource.OCKG, "1", "Свинка Пеппа 1 сезон"), VideoCard(VideoSource.OCKG, "2", "Свинка Пеппа 2 сезон")))
        assertEquals("b", VideoPolicy.nextEpisode(d, "a")?.id)
        assertNull(VideoPolicy.nextEpisode(d, "b"))
        // after the season's last episode: the next season, from its first
        assertEquals("ockg:2" to null, VideoPolicy.nextAcrossRelated(d, "b"))
        assertTrue(VideoPolicy.isCompleted(96_000, 100_000))
        assertEquals(0L, VideoPolicy.resumePosition(96_000, 100_000))
        assertEquals(50_000L, VideoPolicy.resumePosition(50_000, 100_000))
        assertFalse(VideoPolicy.isCompleted(50_000, 0))
        assertEquals("С1 Э2", d.label(d.episodes[1]))
    }

    @Test fun regionProbe() {
        assertEquals(true, VideoRegion.decide(200))
        assertEquals(false, VideoRegion.decide(403))
        assertNull(VideoRegion.decide(-1))
        assertNull(VideoRegion.decide(502))
    }

    @Test fun texts() {
        assertEquals("1:02:03", VideoText.time(3_723_000))
        assertEquals("42 мин", VideoText.duration(42 * 60))
        assertEquals("1 ч 52 мин", VideoText.duration(112 * 60))
        assertEquals("елка 2", VideoText.normalize("Ёлка-2!"))
    }
}
