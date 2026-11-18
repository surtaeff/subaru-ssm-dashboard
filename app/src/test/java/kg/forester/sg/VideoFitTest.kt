package kg.forester.sg

import kg.forester.sg.video.VideoFit
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFitTest {
    private val film = 16f / 9f

    @Test fun aWideUnitFitsWithBars() {
        val (w, h) = VideoFit.size(1920f, 720f, film, fill = false)
        assertEquals(1280f, w, 0.5f)
        assertEquals(720f, h, 0.5f)
    }

    @Test fun aWideUnitFilledCutsTopAndBottom() {
        val (w, h) = VideoFit.size(1920f, 720f, film, fill = true)
        assertEquals(1920f, w, 0.5f)
        assertEquals(1080f, h, 0.5f)
    }

    @Test fun aTallScreenFitsByWidth() {
        val (w, h) = VideoFit.size(800f, 1280f, film, fill = false)
        assertEquals(800f, w, 0.5f)
        assertEquals(450f, h, 0.5f)
    }

    @Test fun aSixteenByNineUnitIsFullEitherWay() {
        assertEquals(1280f to 720f, VideoFit.size(1280f, 720f, film, fill = false))
        assertEquals(1280f to 720f, VideoFit.size(1280f, 720f, film, fill = true))
    }
}

class RutubeTitleTest {
    @Test fun aFilmTitleForTheTrackers() {
        assertEquals("Сердце зверя", kg.forester.sg.video.Rutube.filmTitle("Сердце зверя |  Heart of the Beast (2026,4K)"))
        assertEquals("Полярные противоположности", kg.forester.sg.video.Rutube.filmTitle("Полярные противоположности (2025) / Polar Opposites"))
        assertEquals("Одиссея", kg.forester.sg.video.Rutube.filmTitle("Одиссея | The Odyssey (2026,4K)"))
    }

    @Test fun rutubeMasterKeepsOneEntryPerQuality() {
        val m = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=2x1\nhttps://salam.cdn.ru/a.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=2x1\nhttps://river-1.rutube.ru/a.m3u8\n"
        val out = kg.forester.sg.video.Rutube.dedupeMaster(m)
        assertEquals(1, Regex("STREAM-INF").findAll(out).count())
        org.junit.Assert.assertTrue(out.contains("river-1.rutube.ru"))
    }

    @Test fun youTubeDurations() {
        assertEquals(754, kg.forester.sg.video.YouTube.seconds("12:34"))
        assertEquals(3723, kg.forester.sg.video.YouTube.seconds("1:02:03"))
        assertEquals(0, kg.forester.sg.video.YouTube.seconds("LIVE"))
    }

    @Test fun youTubePageParses() {
        val html = """<script>var ytInitialData = {"contents":{"a":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Субару"}]},""" +
            """"lengthText":{"simpleText":"3:05"},"ownerText":{"runs":[{"text":"Канал"}]}}},{"videoRenderer":{"videoId":"live0000000","title":{"runs":[{"text":"Эфир"}]}}}]}};</script>"""
        val v = kg.forester.sg.video.YouTube.parsePage(html)
        assertEquals(1, v.size)
        assertEquals("abcdefghijk", v[0].id)
        assertEquals(185, v[0].durationSec)
        assertEquals("Канал", v[0].author)
    }
}

class WikiFeedTest {
    @Test fun aFilmPage() {
        val f = kg.forester.sg.video.WikiFeed.film("Одиссея (фильм, 2026)", "фильм Кристофера Нолана (2026)", "https://x/y.jpg", 10)
        assertEquals("Одиссея", f.title)
        assertEquals(2026, f.year)
        org.junit.Assert.assertFalse(f.series)
    }

    @Test fun daysMergeAndOldFilmsGo() {
        fun day(vararg a: String) = """{"mostread":{"articles":[${a.joinToString(",")}]}}"""
        val odyssey = """{"normalizedtitle":"Одиссея (фильм, 2026)","description":"фильм Кристофера Нолана (2026)","views":100}"""
        val series = """{"normalizedtitle":"Тело (телесериал)","description":"американский телесериал","views":50}"""
        val old = """{"normalizedtitle":"Рождённая революцией","description":"советский телевизионный фильм, снятый в 1974—1977","views":900}"""
        val person = """{"normalizedtitle":"Кто-то","description":"российский актёр","views":999}"""
        val out = kg.forester.sg.video.WikiFeed.merge(listOf(day(odyssey, series, old, person), day(odyssey)), 2026)
        assertEquals(listOf("Одиссея", "Тело"), out.map { it.title })
        assertEquals(200L, out[0].views)
        org.junit.Assert.assertTrue(out[1].series)
    }
}

class TorrentOrderTest {
    private fun r(name: String, q: Int, sid: Int) = kg.forester.sg.torrent.TorrentRelease(magnet = "magnet:?xt=urn:btih:$name", name = "Film",
        originalName = null, title = name, year = 2026, quality = q, voices = emptyList(), sizeBytes = 0, sizeName = "", seeders = sid,
        peers = 0, tracker = "", isSeries = false)

    @Test fun aUnitWithoutTenBitGetsAvcFirst() {
        val caps = kg.forester.sg.torrent.Codecs.Caps(hevc = true, hevc10 = false)
        val list = listOf(r("Film 2160p HDR10 HEVC", 2160, 900), r("Film TS-HEVC 10bit 1080p", 1080, 700),
            r("Film WEB-DL x264 1080p", 1080, 100), r("Film HEVC 1080p", 1080, 300), r("Dead x264", 1080, 0))
        val names = list.sortedWith(kg.forester.sg.torrent.TorrentCatalog.orderFor(caps)).map { it.title }
        assertEquals(listOf("Film WEB-DL x264 1080p", "Film HEVC 1080p", "Film TS-HEVC 10bit 1080p", "Film 2160p HDR10 HEVC", "Dead x264"), names)
    }

    @Test fun episodesInWatchingOrder() {
        fun f(id: Int, p: String) = kg.forester.sg.torrent.TorrClient.TorrFile(id, p, 500_000_000)
        val info = kg.forester.sg.torrent.TorrClient.TorrInfo("h", "n", "", 0, 0, 0,
            listOf(f(0, "Show/Show.S01E10.mkv"), f(1, "Show/Show.S01E02.mkv"), f(2, "Show/Show.S02E01.mkv"), f(3, "Show/sample.mkv").copy(length = 10), f(4, "Show/Show.S01E01.mkv")))
        assertEquals(listOf(4, 1, 0, 2), info.episodes.map { it.id })
        assertEquals("Сезон 1 · Серия 2", kg.forester.sg.torrent.Episodes.label("Show/Show.S01E02.mkv"))
        assertEquals("Серия 3", kg.forester.sg.torrent.Episodes.label("Сериал/03. Название.mkv"))
        assertEquals("Сезон 2 · Серия 5", kg.forester.sg.torrent.Episodes.label("Сериал 2 сезон/Серия 5.mkv"))
    }
}
