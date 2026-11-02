package kg.forester.sg

import kg.forester.sg.service.MiniHold
import kg.forester.sg.service.MiniSlot
import kg.forester.sg.service.NowPlaying
import kg.forester.sg.service.miniSlot
import kg.forester.sg.service.pickNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiniPlayerTest {
    private fun now(source: String, title: String = "Песня", playing: Boolean = true) = NowPlaying(source, title, "Группа", null, playing)

    // ---- which source

    @Test fun nothingKnownNothingShown() {
        assertNull(pickNow("files", null, null, null, null))
    }

    @Test fun whatMediaIsOnWhenItPlays() {
        val own = now("files")
        assertEquals(own, pickNow("files", own, null, null, now("app")))
        val bt = now("bt")
        assertEquals(bt, pickNow("bt", now("files", playing = false), null, bt, null))
        val fm = now("fm")
        assertEquals(fm, pickNow("radio", null, fm, null, null))
    }

    @Test fun whatReallyPlaysBeatsAPausedChoice() {
        val app = now("app")
        assertEquals(app, pickNow("files", now("files", playing = false), null, null, app))
        val own = now("files")
        assertEquals("a stream or a file goes on with Media set elsewhere", own, pickNow("bt", own, null, now("bt", playing = false), null))
    }

    @Test fun pausedOnlyFromTheChosenSource() {
        val paused = now("files", playing = false)
        assertEquals(paused, pickNow("files", paused, null, null, null))
        assertNull("a paused player of another source isn't the driver's", pickNow("radio", null, null, now("bt", playing = false), null))
    }

    @Test fun internetRadioIsTheOwnPlayerUnderRadio() {
        val stream = now("stream")
        assertEquals(stream, pickNow("radio", stream, null, null, null))
        assertEquals(stream, pickNow("radio", stream.copy(playing = false), null, null, null)?.copy(playing = true))
    }

    // ---- when it shows

    @Test fun neverPlayedStaysHidden() {
        val h = MiniHold()
        assertNull("a queue restored at the start, paused", h.feed(now("files", playing = false), 0L))
        assertNull(h.feed(null, 5_000L))
    }

    @Test fun playingShowsAtOnce() {
        assertEquals("Песня", MiniHold().feed(now("files"), 0L)?.title)
    }

    @Test fun pausedStaysForAWhileThenGoes() {
        val h = MiniHold()
        h.feed(now("files"), 0L)
        val paused = now("files", playing = false)
        assertNotNull(h.feed(paused, 60_000L))
        assertNotNull(h.feed(paused, MiniHold.RECENT_MS))
        assertNull(h.feed(paused, MiniHold.RECENT_MS + 1))
    }

    @Test fun aGapBetweenTracksKeepsTheStripAndItsHeight() {
        val h = MiniHold()
        h.feed(now("files", "Первая"), 0L)
        val gap = h.feed(null, 2_000L)
        assertEquals("the last track stays on the strip", "Первая", gap?.title)
        assertFalse("but it is said not to play", gap!!.playing)
        assertNotNull(h.feed(now("files", "", playing = true).copy(title = ""), 3_000L))
        assertEquals("Вторая", h.feed(now("files", "Вторая"), 4_000L)?.title)
    }

    @Test fun aSourceGoneForGoodHidesTheStripAfterTheGap() {
        val h = MiniHold()
        h.feed(now("app"), 0L)
        assertNotNull(h.feed(null, MiniHold.GAP_MS))
        assertNull(h.feed(null, MiniHold.GAP_MS + 1))
        assertNull("and nothing comes back by itself", h.feed(null, MiniHold.GAP_MS + 2))
    }

    @Test fun aNewPlayStartsAgainAfterHiding() {
        val h = MiniHold()
        h.feed(now("files"), 0L)
        assertNull(h.feed(null, MiniHold.GAP_MS + 1))
        assertNotNull(h.feed(now("bt"), MiniHold.GAP_MS + 2))
    }

    // ---- where it goes

    @Test fun placement() {
        assertEquals("the default for those who don't touch it", MiniSlot.BOTTOM, miniSlot("bottom", onMedia = false, editingFree = false, shown = true))
        assertEquals(MiniSlot.TOP, miniSlot("top", onMedia = false, editingFree = false, shown = true))
        assertEquals(MiniSlot.NONE, miniSlot("off", onMedia = false, editingFree = false, shown = true))
        assertEquals("Media is the player itself", MiniSlot.NONE, miniSlot("bottom", onMedia = true, editingFree = false, shown = true))
        assertEquals("nothing known", MiniSlot.NONE, miniSlot("top", onMedia = false, editingFree = false, shown = false))
        assertEquals("the free grid keeps its scale under the edit bar", MiniSlot.NONE, miniSlot("bottom", onMedia = false, editingFree = true, shown = true))
    }

    // ---- by source

    private val ui = File("src/main/java/kg/forester/sg/ui")

    @Test fun noMarqueeAndNoSecondControlPath() {
        val src = File(ui, "MiniPlayer.kt").readText()
        assertFalse(src.contains("basicMarquee"))
        assertFalse("through the app's own route, no media keys of its own", src.contains("KEYCODE_MEDIA"))
        for (route in listOf("LocalMusic.next", "LocalMusic.prev", "LocalMusic.toggle", ".bt.send(", ".bt.press(", "tunerPreset(", "appControl(")) {
            assertTrue(route, src.contains(route))
        }
        assertTrue("the player uses the same one", File(ui, "MediaRadio.kt").readText().contains("tunerPreset("))
        assertTrue(File(ui, "MediaScreen.kt").readText().contains("appControl("))
    }

    @Test fun theStripIsSeparateFromWhatLaysItOut() {
        val app = File("src/main/java/kg/forester/sg/MainActivity.kt").readText()
        // a reading of the track in App would recompose everything on a track change
        assertFalse(app.contains("mini.shown"))
        assertFalse(app.contains("mini.position"))
    }
}
