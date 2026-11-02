package kg.forester.sg

import kg.forester.sg.service.TapPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TapSoundTest {
    private class Out : TapPlayer.Out {
        val log = mutableListOf<String>()
        var next = 10
        override fun play(): Int { val s = next++; log += "play$s"; return s }
        override fun stop(stream: Int) { log += "stop$stream" }
    }

    /** Holds what was posted, as the sound's own thread would until it gets to it. */
    private class Later {
        val jobs = mutableListOf<Runnable>()
        val post: (Runnable) -> Unit = { jobs += it }
        fun run() { jobs.forEach { it.run() }; jobs.clear() }
    }

    private fun player(out: Out, later: Later, on: Boolean = true) = TapPlayer(out, later.post, on).apply { ready = true }

    @Test fun theTapItselfOnlyPosts() {
        val out = Out()
        val later = Later()
        player(out, later).tap()
        assertTrue("nothing audio on the tapping thread", out.log.isEmpty())
        later.run()
        assertEquals(listOf("play10"), out.log)
    }

    @Test fun aRepeatedTapRestartsAndDoesNotStack() {
        val out = Out()
        val later = Later()
        val p = player(out, later)
        p.tap(); later.run()
        p.tap(); later.run()
        p.tap(); later.run()
        assertEquals(listOf("play10", "stop10", "play11", "stop11", "play12"), out.log)
    }

    @Test fun switchedOffIsSilentAndPostsNothing() {
        val out = Out()
        val later = Later()
        val p = player(out, later, on = false)
        p.tap()
        assertTrue(later.jobs.isEmpty())
        p.enabled = true
        p.tap(); later.run()
        assertEquals(listOf("play10"), out.log)
        p.enabled = false
        p.tap()
        assertTrue(later.jobs.isEmpty())
    }

    @Test fun beforeTheSoundIsLoadedATapDoesNothing() {
        val out = Out()
        val later = Later()
        val p = TapPlayer(out, later.post, true)
        p.tap(); later.run()
        assertTrue(out.log.isEmpty())
        p.ready = true
        p.tap(); later.run()
        assertEquals(listOf("play10"), out.log)
    }

    @Test fun aFailedPlayIsNotStoppedLater() {
        val out = object : TapPlayer.Out {
            val stops = mutableListOf<Int>()
            override fun play() = 0 // SoundPool's answer when it could not play
            override fun stop(stream: Int) { stops += stream }
        }
        val later = Later()
        val p = TapPlayer(out, later.post, true).apply { ready = true }
        p.tap(); later.run()
        p.tap(); later.run()
        assertTrue(out.stops.isEmpty())
    }

    // ---- the wiring: this module has no device tests, so the source says what is wired ----

    private val ui = File("src/main/java/kg/forester/sg/ui")
    private val components = File(ui, "Components.kt").readText()
    private val activity = File("src/main/java/kg/forester/sg/MainActivity.kt").readText()
    private val sound = File("src/main/java/kg/forester/sg/service/TapSound.kt").readText()

    private fun body(src: String, head: String): String {
        val from = src.indexOf(head)
        assertTrue("no $head", from >= 0)
        val to = src.indexOf("\n}\n", from)
        return src.substring(from, to)
    }

    @Test fun everySharedButtonTapsThroughTheSound() {
        for (head in listOf("fun Chip(", "fun ActionButton(", "fun SmallAction(", "internal fun Pill(", "fun IconTap(", "fun SwitchRow(")) {
            val b = body(components, head)
            assertTrue("$head has no sound", ".tapClickable(" in b)
            assertFalse("$head has a silent click left", ".clickable(" in b)
        }
        val rail = body(activity, "private fun RailItem(")
        assertTrue(".tapClickable(" in rail)
        assertFalse(".clickable(" in rail)
    }

    @Test fun thePhoneMenuTapsToo() {
        val menu = body(activity, "private fun PhoneMenu(")
        // the row of each screen and the way out; the dim background around the list only closes it
        assertEquals(2, Regex("\\.tapClickable").findAll(menu).count())
        assertTrue(".tapClickable(onClick = onMenu)" in body(activity, "private fun NarrowScreen("))
    }

    @Test fun tapClickablePlaysOnAPlainTapOnly() {
        val helper = body(components, "fun Modifier.tapClickable(")
        assertTrue("TapSound.tap()" in helper)
        assertFalse("combinedClickable" in helper)
        assertFalse("onLongClick" in helper)
    }

    @Test fun aSwitchRowChangesFirstSoSwitchingTheSoundOffIsSilent() {
        val b = body(components, "fun SwitchRow(")
        assertTrue(b, Regex("""tapClickable\(role = [^)]*Role\.Switch, afterClick = true\) \{ onChange\(!on\) \}""").containsMatchIn(b))
    }

    @Test fun theSettingIsOnByDefaultAndReachesThePlayer() {
        val repo = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertTrue("getBoolean(\"buttonSound\", true)" in repo)
        assertTrue(Regex("""putBoolean\("buttonSound", v\).*TapSound\.setEnabled\(v\)""").containsMatchIn(repo))
        assertTrue("TapSound.init(applicationContext, Repo.settings.buttonSound)" in activity)
    }

    @Test fun theSettingIsInTheSoundSection() {
        val s = File(ui, "LogSettingsScreens.kt").readText()
        val general = s.substring(s.indexOf("internal fun SoundSettings("))
        assertTrue(Regex("""SwitchRow\("Звук кнопок", "[^"]+", sound\) \{ sound = it; s\.buttonSound = it \}""").containsMatchIn(general))
    }

    @Test fun onlySonificationAndNoFocus() {
        assertTrue("USAGE_ASSISTANCE_SONIFICATION" in sound)
        assertTrue("setMaxStreams(1)" in sound)
        assertFalse("requestAudioFocus" in sound)
        assertFalse("AudioFocusRequest" in sound)
        assertTrue(File("src/main/res/raw/button.mp3").length() in 1000L..20_000L)
    }

    @Test fun slidersAndGaugesStaySilent() {
        for (f in listOf("Gauges.kt", "LensDial.kt", "CardValue.kt")) assertFalse(f, "TapSound" in File(ui, f).readText())
    }
}
