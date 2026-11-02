package kg.forester.sg

import kg.forester.sg.service.BtPlayStep
import kg.forester.sg.service.GateEvent
import kg.forester.sg.service.GateLog
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.MusicState
import kg.forester.sg.service.NativeLease
import kg.forester.sg.service.Phrase
import kg.forester.sg.service.PhraseQueue
import kg.forester.sg.service.RadioLogos
import kg.forester.sg.service.RadioState
import kg.forester.sg.service.RadioWake
import kg.forester.sg.service.RmsGate
import kg.forester.sg.service.SayRoute
import kg.forester.sg.service.SpeechIdle
import kg.forester.sg.service.Utterance
import kg.forester.sg.service.UtteranceStep
import kg.forester.sg.service.VoiceRun
import kg.forester.sg.service.VoiceTurns
import kg.forester.sg.service.audio.BtTrack
import kg.forester.sg.service.audio.FocusStep
import kg.forester.sg.service.audio.MicrontekSound
import kg.forester.sg.service.audio.SinkFacts
import kg.forester.sg.service.audio.SinkPlan
import kg.forester.sg.service.audio.Transport
import kg.forester.sg.service.audio.VolumeAction
import kg.forester.sg.service.audio.awaitUpdate
import kg.forester.sg.service.audio.enterPlan
import kg.forester.sg.service.audio.fytTrack
import kg.forester.sg.service.audio.fytTuneModes
import kg.forester.sg.service.audio.fytVolumeAction
import kg.forester.sg.service.audio.kickDue
import kg.forester.sg.service.audio.mcuVolumeSteps
import kg.forester.sg.service.audio.microntekPressSends
import kg.forester.sg.service.audio.microntekSound
import kg.forester.sg.service.audio.nwdSourceRequest
import kg.forester.sg.service.audio.pressPlan
import kg.forester.sg.service.audio.provesSoundInvisible
import kg.forester.sg.service.audio.readBackConfirms
import kg.forester.sg.service.audio.sessionlessKey
import kg.forester.sg.service.audio.tsFingerprintOk
import kg.forester.sg.service.audio.tsGridIndex
import kg.forester.sg.service.audio.tuneReached
import kg.forester.sg.service.audio.twMarkFrom
import kg.forester.sg.service.audio.twProbeDue
import kg.forester.sg.service.audio.unmuteCode
import kg.forester.sg.service.audio.voiceVolumePlan
import kg.forester.sg.service.btNeedsToggle
import kg.forester.sg.service.btPlayCode
import kg.forester.sg.service.btPlayStep
import kg.forester.sg.service.btReached
import kg.forester.sg.service.btStillDue
import kg.forester.sg.service.btToggleDue
import kg.forester.sg.service.pickBtSession
import kg.forester.sg.service.pickSession
import kg.forester.sg.service.radioWakeStep
import kg.forester.sg.service.sayRoute
import kg.forester.sg.service.systemFlushes
import kg.forester.sg.service.utteranceStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaTest {
    // ---- the microphone's gate

    private val chunk = 2048 // 128 ms at 16 kHz, what the capture reads

    /** A chunk at about [dbfs], each sample also numbered through [seq] so the test can tell which audio came out. */
    private fun tone(dbfs: Double, seq: IntArray): ShortArray {
        val a = 32768.0 * Math.pow(10.0, dbfs / 20) * Math.sqrt(2.0)
        return ShortArray(chunk) { (a * Math.sin(seq[0]++ * 0.3)).toInt().toShort() }
    }

    private class Fed(val audio: ArrayList<ShortArray> = ArrayList()) {
        val samples get() = audio.sumOf { it.size }
    }

    private fun RmsGate.push(c: ShortArray, fed: Fed): GateEvent =
        feed(c, c.size) { a, n -> fed.audio += a.copyOf(n) }

    @Test fun rmsGateKeepsPreRoll() {
        val gate = RmsGate()
        val fed = Fed()
        val seq = intArrayOf(0)
        // a silent cabin, with every chunk remembered to compare with what comes out
        val before = ArrayList<Short>()
        repeat(30) { val c = tone(-60.0, seq); assertEquals(GateEvent.SILENT, gate.push(c, fed)); before += c.toList() }
        assertEquals("silence does not go to the recogniser", 0, fed.samples)

        val word = tone(-30.0, seq)
        assertEquals(GateEvent.OPENED, gate.push(word, fed))
        val out = fed.audio.flatMap { it.toList() }
        // exactly 300 ms before the word, unbroken up to it, then the word
        assertEquals(4800 + chunk, out.size)
        assertEquals(before.takeLast(4800), out.take(4800))
        assertEquals(word.toList(), out.drop(4800))

        // on the go: the next chunks follow one by one, nothing repeated
        fed.audio.clear()
        val next = tone(-30.0, seq)
        assertEquals(GateEvent.OPEN, gate.push(next, fed))
        assertEquals(next.toList(), fed.audio.single().toList())
    }

    @Test fun rmsGatePreRollShorterThan300msRightAfterTheStart() {
        val gate = RmsGate()
        val fed = Fed()
        val seq = intArrayOf(0)
        gate.push(tone(-60.0, seq), fed)
        gate.push(tone(-30.0, seq), fed)
        assertEquals(chunk * 2, fed.samples)
    }

    @Test fun rmsGateFollowsTheCabinNoise() {
        val seq = intArrayOf(0)
        // the same quiet voice, -44 dBFS: opens in a quiet parked car
        val parked = RmsGate()
        repeat(40) { parked.push(tone(-60.0, seq), Fed()) }
        assertEquals(GateEvent.OPENED, parked.push(tone(-44.0, seq), Fed()))

        // on the road the cabin itself is at that level: after a moment it is just noise and nothing goes on...
        val road = RmsGate()
        repeat(40) { road.push(tone(-60.0, seq), Fed()) }
        val fed = Fed()
        repeat(200) { road.push(tone(-44.0, seq), fed) } // 25 s, the window went down
        fed.audio.clear()
        repeat(40) { assertEquals(GateEvent.SILENT, road.push(tone(-44.0, seq), fed)) }
        assertEquals(0, fed.samples)
        // ...and a voice only a little over that noise still gets through
        assertEquals(GateEvent.OPENED, road.push(tone(-39.0, seq), fed))
    }

    @Test fun rmsGateCatchesUpQuicklyWhenTheCabinGoesQuiet() {
        val seq = intArrayOf(0)
        val g = RmsGate()
        repeat(100) { g.push(tone(-40.0, seq), Fed()) }
        repeat(40) { g.push(tone(-65.0, seq), Fed()) } // 5 s: the engine stopped
        assertEquals(GateEvent.OPENED, g.push(tone(-55.0, seq), Fed()))
    }

    @Test fun rmsGateLeavesAVeryLoudCabinToTheRecogniser() {
        // music or wind louder than a voice could stand out from by loudness: everything goes on, as with no gate at all
        val seq = intArrayOf(0)
        val g = RmsGate()
        val fed = Fed()
        repeat(300) { g.push(tone(-18.0, seq), fed) }
        fed.audio.clear()
        repeat(20) { assertEquals(GateEvent.OPEN, g.push(tone(-18.0, seq), fed)) }
        assertEquals(20 * chunk, fed.samples)
    }

    @Test fun rmsGateHoldsThroughAPauseAndSaysWhenItCloses() {
        val seq = intArrayOf(0)
        val g = RmsGate()
        val fed = Fed()
        repeat(30) { g.push(tone(-60.0, seq), fed) }
        g.push(tone(-30.0, seq), fed)
        // a pause inside the phrase, then the end the recogniser needs to hear to finish it: over a second of quiet
        val events = (0 until 30).map { g.push(tone(-60.0, seq), fed) }
        val open = events.takeWhile { it == GateEvent.OPEN }.size
        assertTrue("held $open chunks", open * 128 >= 1000)
        assertEquals("closes once", 1, events.count { it == GateEvent.CLOSED })
        assertEquals(GateEvent.CLOSED, events[open])
        assertTrue(events.drop(open + 1).all { it == GateEvent.SILENT })
        // the audio of the closing chunk goes back to the pre-roll, with no repeat next time
        fed.audio.clear()
        assertEquals(GateEvent.OPENED, g.push(tone(-30.0, seq), fed))
        assertEquals(4800 + chunk, fed.samples)
    }

    @Test fun rmsGateClosesWithSilenceForTheRecognisersOwnEndpoint() {
        val seq = intArrayOf(0)
        val g = RmsGate()
        val fed = Fed()
        repeat(30) { g.push(tone(-60.0, seq), fed) }
        g.push(tone(-30.0, seq), fed)
        fed.audio.clear()
        var e = GateEvent.OPEN
        while (e == GateEvent.OPEN) { fed.audio.clear(); e = g.push(tone(-60.0, seq), fed) }
        assertEquals(GateEvent.CLOSED, e)
        val tail = fed.audio.single()
        assertEquals(16000 * 400 / 1000, tail.size)
        assertTrue(tail.all { it.toInt() == 0 })
    }

    /** A chunk of road rumble at 100 Hz, [dbfs], with a voice at 1 kHz, [voiceDb]; null = no voice. */
    private fun road(dbfs: Double, voiceDb: Double?, seq: IntArray): ShortArray {
        val a = 32768.0 * Math.pow(10.0, dbfs / 20) * Math.sqrt(2.0)
        val v = voiceDb?.let { 32768.0 * Math.pow(10.0, it / 20) * Math.sqrt(2.0) } ?: 0.0
        return ShortArray(chunk) {
            val n = seq[0]++
            (a * Math.sin(n * 2 * Math.PI * 100 / 16000) + v * Math.sin(n * 2 * Math.PI * 1000 / 16000)).toInt().toShort()
        }
    }

    @Test fun rmsGateHearsAVoiceUnderRoadRumble() {
        // the rumble is 10 dB over the voice in plain loudness: only the voice's higher frequencies can tell it
        val seq = intArrayOf(0)
        val g = RmsGate()
        val fed = Fed()
        repeat(100) { g.push(road(-30.0, null, seq), fed) }
        fed.audio.clear()
        repeat(20) { assertEquals(GateEvent.SILENT, g.push(road(-30.0, null, seq), fed)) }
        assertEquals(GateEvent.OPENED, g.push(road(-30.0, -40.0, seq), fed))
    }

    @Test fun rmsGateCountsItsOpeningsAndTheLogLineComesAtMostOnceAMinute() {
        val seq = intArrayOf(0)
        val g = RmsGate()
        repeat(30) { g.push(tone(-60.0, seq), Fed()) }
        g.push(tone(-30.0, seq), Fed())
        repeat(30) { g.push(tone(-60.0, seq), Fed()) }
        assertEquals(1, g.opened)
        assertEquals(1, g.closed)
        val log = GateLog()
        assertNull("not in the first minute", log.line(10_000, g.opened, g.closed))
        assertEquals("voice gate: opened 1, closed 1", log.line(60_000, g.opened, g.closed))
        assertNull(log.line(70_000, g.opened, g.closed))
        assertNull("nothing new, nothing to say", log.line(130_000, g.opened, g.closed))
        assertEquals("voice gate: opened 2, closed 1", log.line(190_000, 2, 1))
    }

    @Test fun rmsGateAcceptsShortReads() {
        val seq = intArrayOf(0)
        val g = RmsGate()
        val fed = Fed()
        repeat(30) { g.push(tone(-60.0, seq), fed) }
        val c = tone(-30.0, seq)
        assertEquals(GateEvent.OPENED, g.feed(c, 512) { a, n -> fed.audio += a.copyOf(n) })
        assertEquals(4800 + 512, fed.samples)
    }

    // ---- native models

    @Test fun nativeModelClosesOnlyAfterTheLastUserIsDone() {
        var opened = 0
        var closed = 0
        val l = NativeLease({ opened++; "model" }, { closed++ })
        assertEquals("model", l.acquire())
        assertEquals("a restart's new listener takes it before the old one lets go", "model", l.acquire())
        l.release()
        assertEquals("the old listener left, the new one still recognises", 0, closed)
        assertEquals(1, opened)
        l.release()
        assertEquals("voice off: closed", 1, closed)
        assertFalse(l.held)
        l.acquire()
        assertEquals("on again: a new one", 2, opened)
    }

    @Test fun nativeModelThatFailsToOpenLeavesNothingHeld() {
        var fail = true
        var closed = 0
        val l = NativeLease({ if (fail) error("broken") else "model" }, { closed++ })
        assertTrue(runCatching { l.acquire() }.isFailure)
        assertFalse(l.held)
        fail = false
        assertEquals("model", l.acquire())
        l.release()
        assertEquals(1, closed)
    }

    @Test fun nativeModelReleaseWithoutAcquireDoesNothing() {
        var closed = 0
        val l = NativeLease({ "model" }, { closed++ })
        l.release()
        assertEquals(0, closed)
        l.acquire(); l.release()
        assertEquals(1, closed)
    }

    @Test fun nativeModelThatFailsToCloseStillLetsGo() {
        val l = NativeLease({ "model" }, { error("native") })
        l.acquire()
        l.release()
        assertFalse(l.held)
    }

    @Test fun stopDuringModelOpenStopsThePendingStart() {
        val r = VoiceRun()
        r.started()
        r.stopped()
        assertFalse("the pending start must give up", r.mayListen(enabled = true))
    }

    @Test fun stopThenStartEndsRunningEvenWithAStartInFlight() {
        val r = VoiceRun()
        r.started() // in flight, opening the model
        r.stopped()
        r.started() // VoiceUi: stop(); start(): this one returns at once, the one in flight carries on for it
        assertTrue(r.mayListen(enabled = true))
        assertFalse("switched off in the settings", r.mayListen(enabled = false))
    }

    // ---- the voice loaded when needed

    @Test fun idleDueAfterSixtySeconds() {
        val idle = SpeechIdle()
        idle.touch(1000)
        assertFalse(idle.due(60_999))
        assertTrue(idle.due(61_000))
        idle.touch(50_000) // another phrase
        assertFalse(idle.due(61_000))
    }

    @Test fun idleUnderMemoryPressureDueAfterFiveSeconds() {
        val idle = SpeechIdle()
        idle.touch(1000)
        assertFalse(idle.due(3000, pressure = true))
        assertTrue(idle.due(7000, pressure = true))
    }

    private fun route(loaded: Boolean = false, installed: Boolean = true, system: Boolean = true, preview: Boolean = false, danger: Boolean = false) =
        sayRoute(loaded, installed, system, preview, danger)

    @Test fun sayRouteOfAnOrdinaryPhrase() {
        // the owner's voice is the normal one
        assertEquals(SayRoute.MODEL, route(loaded = true))
        assertEquals(SayRoute.MODEL_OR_SYSTEM_AFTER_WAIT, route())
        // no Russian Android voice: only the model can say it
        assertEquals(SayRoute.MODEL, route(system = false))
        // trying a voice in the settings: that voice
        assertEquals(SayRoute.MODEL, route(preview = true))
        // not downloaded: the caller's fallback
        assertEquals(SayRoute.CALLER, route(installed = false))
    }

    @Test fun sayRouteOfADanger() {
        // the system voice whenever it is ready, even with the model loaded; no model load for it
        assertEquals(SayRoute.SYSTEM_NOW, route(danger = true))
        assertEquals(SayRoute.SYSTEM_NOW, route(loaded = true, danger = true))
        assertEquals(SayRoute.SYSTEM_NOW, route(installed = false, danger = true))
        // no Russian voice on the unit
        assertEquals(SayRoute.MODEL, route(loaded = true, system = false, danger = true))
        assertEquals(SayRoute.BEEP_THEN_MODEL, route(system = false, danger = true))
        assertEquals(SayRoute.CALLER, route(installed = false, system = false, danger = true))
    }

    @Test fun dangerSkipsAheadOfQueuedPhrases() {
        val q = PhraseQueue<String>()
        q.add("a", urgent = false)
        q.add("b", urgent = false)
        q.add("danger1", urgent = true)
        q.add("danger2", urgent = true)
        q.add("c", urgent = false)
        assertEquals(listOf("danger1", "danger2", "a", "b", "c", null), List(6) { q.poll() })
    }

    @Test fun onlyOneVoiceGetsAPhrase() {
        val p = Phrase("текст", urgent = false)
        assertFalse(p.taken)
        assertTrue(p.claim())
        assertFalse(p.claim())
        assertTrue(p.taken)
    }

    @Test fun systemPhraseThatNeverStartedFailsOnceAndEndsOnce() {
        var fails = 0
        var ends = 0
        val u = Utterance({ ends++ }, { fails++ })
        u.fail()
        u.fail() // the engine's onError after the timeout
        u.done()
        assertEquals(1, fails)
        assertEquals(1, ends)
    }

    @Test fun systemPhraseSaidDoesNotFail() {
        var fails = 0
        var ends = 0
        val u = Utterance({ ends++ }, { fails++ })
        u.start()
        assertTrue(u.startedOk)
        u.done()
        u.fail() // too late to matter
        assertEquals(0, fails)
        assertEquals(1, ends)
    }

    @Test fun systemPhraseFailingMidWayFails() {
        var fails = 0
        val u = Utterance({}, { fails++ })
        u.start()
        u.fail()
        assertEquals(1, fails)
    }

    @Test fun anEngineWithoutCallbacksIsNotJudgedFailedOnceHeardTalking() {
        var fails = 0
        var ends = 0
        val u = Utterance({ ends++ }, { fails++ })
        assertEquals(UtteranceStep.WAIT, u.look(speaking = true, elapsedMs = 500))
        // finished, and the engine never said so
        assertEquals(UtteranceStep.END, u.look(speaking = false, elapsedMs = 3000))
        // heard talking is not started: a danger pushing it out before its turn still counts it as not said
        assertFalse(u.startedOk)
        assertEquals(0, fails)
        val silent = Utterance({}, {})
        assertEquals(UtteranceStep.FAIL, silent.look(speaking = false, elapsedMs = 2000))
    }

    @Test fun aDangerFlushesOnlyToGetAheadOfOrdinarySpeech() {
        assertTrue(systemFlushes(urgent = true, dangerInAir = false))
        assertFalse("a danger must not cut a danger", systemFlushes(urgent = true, dangerInAir = true))
        assertFalse(systemFlushes(urgent = false, dangerInAir = false))
        assertFalse(systemFlushes(urgent = false, dangerInAir = true))
    }

    @Test fun musicResumesOnlyAfterTheLastVoiceIsDone() {
        val t = VoiceTurns()
        t.begin() // the head unit's voice
        // it fails: onFail queues the model's phrase, holding its own place, then onEnd ends the system turn
        t.begin()
        assertFalse(t.end())
        assertTrue("still loading and speaking", t.busy)
        t.begin(); assertFalse(t.end()) // the model's play()
        assertTrue(t.end())
        assertFalse(t.busy)
        assertTrue("an extra end does no harm", t.end())
        t.begin()
        assertTrue(t.busy)
    }

    @Test fun systemPhraseWatchWaits2sThenGivesUp() {
        assertEquals(UtteranceStep.WAIT, utteranceStep(started = false, speaking = false, elapsedMs = 1000))
        assertEquals(UtteranceStep.FAIL, utteranceStep(started = false, speaking = false, elapsedMs = 2000))
        // the engine is talking: as good as started
        assertEquals(UtteranceStep.WAIT, utteranceStep(started = false, speaking = true, elapsedMs = 15_000))
        assertEquals(UtteranceStep.END, utteranceStep(started = false, speaking = true, elapsedMs = 60_000))
        // started and the engine never said it finished
        assertEquals(UtteranceStep.WAIT, utteranceStep(started = true, speaking = true, elapsedMs = 20_000))
        assertEquals(UtteranceStep.END, utteranceStep(started = true, speaking = false, elapsedMs = 20_000))
        assertEquals(UtteranceStep.END, utteranceStep(started = true, speaking = true, elapsedMs = 60_000))
    }

    @Test fun stationsNamingTheFrequencyComeFirst() {
        val l = listOf(
            RadioLogos.Station("Юмор FM", ""),
            RadioLogos.Station("Радио 101.75", ""), // another frequency
            RadioLogos.Station("Хит 1101.7", ""), // not a frequency
            RadioLogos.Station("Европа Плюс Бишкек 101,7 FM", ""),
        )
        assertEquals("Европа Плюс Бишкек 101,7 FM", RadioLogos.withFreqFirst(l, "101.70").first().name)
        assertEquals("Юмор FM", RadioLogos.withFreqFirst(l, "99.90").first().name)
    }

    @Test fun trackNumbersLeaveTheName() {
        assertEquals("Highway Lights", LocalMusic.pretty(File("01 - Highway Lights.mp3")))
        assertEquals("Дождь на стекле", LocalMusic.pretty(File("2 Дождь на стекле.mp3")))
        assertEquals("Song", LocalMusic.pretty(File("03. Song.flac")))
        assertEquals("1999", LocalMusic.pretty(File("1999.mp3"))) // a title that is a number stays
        assertEquals("Intro", LocalMusic.pretty(File("Intro.mp3")))
    }

    @Test fun musicSearchNormalisesAndSwapsLayout() {
        assertEquals("ночной город", kg.forester.sg.service.LocalMusic.norm("Ночной  Город!"))
        assertEquals("еж", kg.forester.sg.service.LocalMusic.norm("Ёж"))
        assertEquals("subaru", kg.forester.sg.service.LocalMusic.otherLayout("ыгифкг"))
        assertEquals("рок", kg.forester.sg.service.LocalMusic.otherLayout("hjr"))
    }

    @Test fun coverSearchLeavesOutEditionNotes() {
        assertEquals("Queen Bohemian Rhapsody", kg.forester.sg.service.Covers.term("Queen", "Bohemian Rhapsody (Remastered 2011)"))
        assertEquals("Кино Группа крови", kg.forester.sg.service.Covers.term(" Кино ", "Группа крови [Live]"))
        assertEquals("Intro", kg.forester.sg.service.Covers.term("", "Intro"))
        assertEquals("(Untitled)", kg.forester.sg.service.Covers.term("", "(Untitled)")) // nothing but a note: kept
    }

    @Test fun fytBluetoothModuleWinsOverTheSessionOnceItSpeaks() {
        val session = BtTrack("из сессии", "исполнитель", playing = true, posMs = 5000, totalMs = 90_000, session = true)
        // nothing from the module yet: the session's, and the player says "Включите музыку" for a blank track, never "Нет трека"
        val none = fytTrack(MusicState(source = "bt"), session)
        assertEquals("из сессии", none.title)
        assertEquals("исполнитель", none.artist)
        assertEquals(true, none.playing)
        assertEquals(5000L, none.posMs)
        assertEquals(false, fytTrack(MusicState(source = "bt"), BtTrack(session = true)).session)
        // the module's words, even its pause and an empty artist; what it leaves out comes from the session
        val module = fytTrack(MusicState(source = "bt", title = "из модуля", playing = false, updatedMs = 1), session)
        assertEquals("из модуля", module.title)
        assertEquals("", module.artist)
        assertEquals(false, module.playing)
        assertEquals(90_000L, module.totalMs)
    }

    private val idle = SinkFacts(sdk = 28, session = true, playing = false, heard = false)
    private val wake = SinkPlan(FocusStep.PULSE, prepare = true, play = true)
    private val neverPulsed = Long.MAX_VALUE

    @Test fun pickDoesNotAutoplayAtBoot() {
        // whatever the phone and the sink are doing, a restored Bluetooth mode asks for nothing
        for (sdk in listOf(25, 28, 29, 34)) for (playing in listOf(false, true)) for (heard in listOf(false, true)) {
            assertEquals(SinkPlan(), enterPlan(byUser = false, SinkFacts(sdk, true, playing, heard)))
        }
        // and the second-by-second check doesn't do it behind the driver's back either
        assertFalse(kickDue(userAsked = false, playing = true, heard = false, sinceMs = neverPulsed))
        assertTrue(kickDue(userAsked = true, playing = true, heard = false, sinceMs = neverPulsed))
        assertFalse(kickDue(userAsked = true, playing = true, heard = false, sinceMs = 14_000))
        // the driver picked it: the sink is made to ask for the focus again
        assertEquals(wake, enterPlan(byUser = true, idle))
        assertEquals(wake, enterPlan(byUser = true, idle.copy(sdk = 34, playing = true)))
        // a paused phone on a newer Android: its sink asks by itself, nobody loses the focus
        assertEquals(SinkPlan(prepare = true, play = true), enterPlan(byUser = true, idle.copy(sdk = 34)))
    }

    @Test fun focusPulseCostsOtherAppsNothingWithoutAReason() {
        // no phone to play: whoever holds the focus would lose it for nothing
        assertEquals(SinkPlan(), enterPlan(byUser = true, idle.copy(session = false)))
        // the sink already sounds: the pulse would take the focus from it
        assertEquals(FocusStep.NONE, enterPlan(byUser = true, idle.copy(playing = true, heard = true)).focus)
        // another app is heard: a permanent loss never comes back to it
        assertEquals(FocusStep.NONE, enterPlan(byUser = true, idle.copy(heard = true)).focus)
        // the navigator is speaking: after the phrase, not over it
        assertEquals(FocusStep.AFTER_VOICE, enterPlan(byUser = true, idle.copy(voiceBusy = true)).focus)
        assertEquals(FocusStep.AFTER_VOICE, pressPlan(idle.copy(sdk = 34, playing = true, voiceBusy = true), neverPulsed).focus)
    }

    @Test fun silentPlayingPressRecoversInsteadOfPause() {
        val silent = idle.copy(sdk = 34, playing = true, heard = false)
        val plan = pressPlan(silent, neverPulsed)
        assertEquals(wake, plan)
        assertFalse(plan.pause)
        // heard: an ordinary pause, and a paused phone an ordinary play with the same recovery
        assertEquals(SinkPlan(pause = true), pressPlan(silent.copy(heard = true), neverPulsed))
        assertEquals(wake, pressPlan(idle, neverPulsed))
        // a unit whose sound never shows in Android's audio: the second press still has to pause
        assertEquals(SinkPlan(pause = true), pressPlan(silent, sincePulseMs = 5_000))
        assertEquals(wake, pressPlan(silent, sincePulseMs = 60_000))
    }

    private data class S(val playing: Boolean = false, val track: Boolean = false, val system: Boolean = true, val bt: Boolean = true)

    private fun pickBt(all: List<S>, a2dp: Boolean) = pickBtSession(all, { it.playing }, { it.track }, { it.system }, { a2dp })
    private fun pick(all: List<S>, a2dp: Boolean) = pickSession(all, { it.playing }, { it.track }, { it.bt }, { a2dp })

    @Test fun idleSessionOnObdIsIgnored() {
        // TS18: Android's stack sits on the OBD module, its session is there and says nothing
        val obd = S()
        assertNull(pickBt(listOf(obd), a2dp = false))
        assertNull(pick(listOf(obd), a2dp = false))
        // the same session counts once it plays, shows a track, or a phone is connected
        assertEquals(S(playing = true), pickBt(listOf(obd, S(playing = true)), a2dp = false))
        assertEquals(S(track = true), pickBt(listOf(obd, S(track = true)), a2dp = false))
        assertEquals(obd, pickBt(listOf(obd), a2dp = true))
        assertEquals(obd, pick(listOf(obd), a2dp = true))
        // a unit's own app before Android's
        assertEquals(S(system = false), pickBt(listOf(obd, S(system = false)), a2dp = true))
        // other players aren't touched by this
        val yandex = S(system = false, bt = false)
        assertEquals(yandex, pick(listOf(obd, yandex), a2dp = false))
    }

    @Test fun fytBluetoothCommandsAreExplicit() {
        assertEquals(27, btPlayCode(true))
        assertEquals(3, btPlayCode(false))
        val heard = MusicState(source = "bt", playing = true, updatedMs = 1)
        // already there counts as done: a toggle sent after it would undo it
        assertTrue(btReached(true, heard))
        assertFalse(btReached(false, heard))
        // a module that has said nothing has reached nothing
        assertFalse(btReached(false, MusicState(source = "bt")))
    }

    @Test fun fytRadioServiceComesBeforeTheActivity() {
        assertEquals(RadioWake.SERVICE, radioWakeStep(serviceThere = true, serviceUseless = false))
        assertEquals(RadioWake.ACTIVITY, radioWakeStep(serviceThere = true, serviceUseless = true))
        assertEquals(RadioWake.ACTIVITY, radioWakeStep(serviceThere = false, serviceUseless = false))
    }

    @Test fun microntekUnmuteNumberComesOnlyFromTheUnitsStub() {
        assertEquals(39, unmuteCode(unmute = 39, mute = 38)) // v1
        assertEquals(40, unmuteCode(unmute = 40, mute = 39)) // v2
        assertEquals(40, unmuteCode(unmute = 40, mute = null))
        assertNull(unmuteCode(unmute = null, mute = 39))
        assertNull(unmuteCode(unmute = 0, mute = null))
        assertNull(unmuteCode(unmute = 39, mute = 39))
    }

    @Test fun microntekHopIsOnlyTheFallbackOfAPick() {
        assertEquals(MicrontekSound.UNMUTED, microntekSound(byUser = true, unmuted = true, hopFree = true))
        assertEquals(MicrontekSound.UNMUTED, microntekSound(byUser = false, unmuted = true, hopFree = true))
        assertEquals(MicrontekSound.HOP, microntekSound(byUser = true, unmuted = false, hopFree = true))
        assertEquals(MicrontekSound.NONE, microntekSound(byUser = true, unmuted = false, hopFree = false))
        // at boot the stock app doesn't flash up
        assertEquals(MicrontekSound.NONE, microntekSound(byUser = false, unmuted = false, hopFree = true))
    }

    private val paused = MusicState(source = "bt", playing = false, updatedMs = 1)
    private val sounding = MusicState(source = "bt", playing = true, updatedMs = 1)

    @Test fun fytPlayAReportingModuleGetsTheExplicitCommandAndAToggleOnlyIfItDidNothing() {
        assertEquals(BtPlayStep(27, 0, true), btPlayStep(true, reported = true, toggleOnly = false, wasSource = true))
        assertEquals(BtPlayStep(3, 0, true), btPlayStep(false, reported = true, toggleOnly = false, wasSource = true))
        assertEquals(BtPlayStep(27, 0, true), btPlayStep(true, reported = true, toggleOnly = false, wasSource = false))
        // reached, or already there before the command: a toggle would undo it
        assertFalse(btNeedsToggle(true, before = paused, now = sounding))
        assertFalse(btNeedsToggle(true, before = sounding, now = paused))
        // the firmware ignored it: one toggle, then the toggle for the rest of the run with nothing after it
        assertTrue(btNeedsToggle(true, before = paused, now = paused))
        assertEquals(BtPlayStep(2, 0, false), btPlayStep(true, reported = true, toggleOnly = true, wasSource = true))
    }

    @Test fun fytPlaySilentModuleGetsTheToggleWithNoFollowUp() {
        assertEquals(BtPlayStep(2, 0, false), btPlayStep(true, reported = false, toggleOnly = false, wasSource = true))
        assertEquals(BtPlayStep(2, 0, false), btPlayStep(false, reported = false, toggleOnly = false, wasSource = true))
    }

    @Test fun fytToggleAfterASourceSwitchWaitsForTheUnitsOwnResume() {
        assertEquals(800L, btPlayStep(true, reported = false, toggleOnly = false, wasSource = false).settleMs)
        assertEquals(800L, btPlayStep(true, reported = true, toggleOnly = true, wasSource = false).settleMs)
    }

    @Test fun secondPressInsideTheWindowProvesTheSoundIsInvisible() {
        val silent = idle.copy(sdk = 34, playing = true)
        assertTrue(provesSoundInvisible(silent, sincePulseMs = 5_000))
        assertFalse(provesSoundInvisible(silent, sincePulseMs = 60_000))
        assertFalse(provesSoundInvisible(silent, neverPulsed))
        assertFalse(provesSoundInvisible(silent.copy(heard = true), sincePulseMs = 5_000))
        assertFalse(provesSoundInvisible(idle, sincePulseMs = 5_000))
        // from then on the first press pauses, and a play costs nobody the focus
        assertEquals(SinkPlan(pause = true), pressPlan(silent, neverPulsed, soundInvisible = true))
        assertEquals(SinkPlan(prepare = true, play = true), pressPlan(idle, neverPulsed, soundInvisible = true))
    }

    @Test fun keyWithoutASessionIsExplicitAndLeavesOurOwnPlayerAlone() {
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, sessionlessKey(Transport.PLAY, ownQueue = false))
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, sessionlessKey(Transport.PAUSE, ownQueue = false))
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, sessionlessKey(Transport.NEXT, ownQueue = false))
        for (t in Transport.entries) assertNull(sessionlessKey(t, ownQueue = true))
    }

    @Test fun microntekPressThatJustOpenedTheSoundSendsNoPause() {
        assertFalse(microntekPressSends(wasShut = true, playing = true, soundOpen = true))
        assertTrue(microntekPressSends(wasShut = true, playing = false, soundOpen = true))
        assertTrue(microntekPressSends(wasShut = false, playing = true, soundOpen = true))
        assertTrue(microntekPressSends(wasShut = true, playing = true, soundOpen = false))
    }

    @Test fun fytOnlyTheLatestPressOnBluetoothActsLater() {
        // play, then pause within the 2 s: the first press's check must not flip the phone back
        assertFalse(btToggleDue(true, before = paused, now = paused, press = 1, latest = 2, s0 = 3, source = 3))
        assertTrue(btToggleDue(true, before = paused, now = paused, press = 2, latest = 2, s0 = 3, source = 3))
        // the driver moved on to another source: neither a toggle nor a fallback's media key
        assertFalse(btToggleDue(true, before = paused, now = paused, press = 2, latest = 2, s0 = 3, source = 1))
        assertFalse(btStillDue(press = 2, latest = 2, s0 = 3, source = 8))
        assertFalse(btStillDue(press = 1, latest = 2, s0 = 3, source = 3))
        assertTrue(btStillDue(press = 2, latest = 2, s0 = 3, source = 3))
    }

    @Test fun fytBluetoothChecksSurviveFirmwareThatNeverReportsTheSource() {
        // appId reads 0 all along: the toggle and the fallbacks still run
        assertTrue(btStillDue(press = 2, latest = 2, s0 = 0, source = 0))
        assertTrue(btToggleDue(true, before = paused, now = paused, press = 2, latest = 2, s0 = 0, source = 0))
        // the knob moved to the radio after BT was confirmed: not any more
        assertFalse(btStillDue(press = 2, latest = 2, s0 = 3, source = 1))
    }

    @Test fun fytRadioPicksModeByType() {
        assertEquals(listOf(3), fytTuneModes("1", -1))
        assertEquals(listOf(1), fytTuneModes("3", -1))
        assertEquals(listOf(1), fytTuneModes("4", 3))
        // type unknown: both, the one that worked last first
        assertEquals(listOf(3, 1), fytTuneModes("?", -1))
        assertEquals(listOf(1, 3), fytTuneModes("", 1))
    }

    @Test fun tuneReturnsTrueOnlyOnReadBack() {
        assertFalse(tuneReached(10170, null, 15))
        assertFalse(tuneReached(10170, 9000, 15))
        assertTrue(tuneReached(10170, 10160, 15))
        assertTrue(tuneReached(1000, 1005, 9))
        assertFalse(tuneReached(1000, 1010, 9))
    }

    @Test fun voiceVolumeUsesMcuWhenFytVolumeUnknown() {
        // no ramp to the floor: a dropped burst could end louder
        assertEquals(emptyList<Int>(), mcuVolumeSteps(null, 12))
        assertEquals(emptyList<Int>(), mcuVolumeSteps(null, 0))
        assertEquals(listOf(-3), mcuVolumeSteps(15, 12))
        assertEquals(emptyList<Int>(), mcuVolumeSteps(12, 12))
        // unknown level: one step down to make the unit tell it; the stream of Android isn't touched either way
        assertEquals(VolumeAction.PROBE, fytVolumeAction(null, true))
        assertEquals(VolumeAction.RELATIVE, fytVolumeAction(15, true))
        assertEquals(VolumeAction.NOT_READY, fytVolumeAction(null, false))
        assertEquals(VolumeAction.RELATIVE, fytVolumeAction(15, false))
        assertEquals(20, voiceVolumePlan(true, 50, true, 15))
        assertEquals(8, voiceVolumePlan(false, 50, true, 15))
        assertEquals(15, voiceVolumePlan(false, 99, false, 15))
    }

    @Test fun readBackNeedsAReportAfterTheCommand() {
        // the state was already on the station: no new report, no confirmation
        assertFalse(readBackConfirms(10170, 10170, 15, reportsBefore = 4, reportsNow = 4))
        assertTrue(readBackConfirms(10170, 10170, 15, reportsBefore = 4, reportsNow = 5))
        assertFalse(readBackConfirms(10170, 9000, 15, reportsBefore = 4, reportsNow = 5))
        assertFalse(readBackConfirms(10170, null, 15, reportsBefore = 4, reportsNow = 5))
    }

    @Test fun oldTopwayFlagCountsAsAMarkWithTheCurrentTime() {
        assertEquals(0L, twMarkFrom(oldFlag = false, at = 0L, now = 500L))
        assertEquals(500L, twMarkFrom(oldFlag = true, at = 0L, now = 500L))
        assertEquals(100L, twMarkFrom(oldFlag = true, at = 100L, now = 500L))
        assertEquals(100L, twMarkFrom(oldFlag = false, at = 100L, now = 500L))
    }

    @Test fun awaitUpdateIsBoundedAndShort() = runBlocking {
        val s = MutableStateFlow(RadioState(freq = 9000))
        // no update at all: gives up at the bound
        val t0 = System.currentTimeMillis()
        assertEquals(9000, s.awaitUpdate(9000, 200, 50))
        assertTrue(System.currentTimeMillis() - t0 < 700)
        // the update comes: no waiting for the bound
        val job = launch { delay(30); s.value = RadioState(freq = 10170) }
        val t1 = System.currentTimeMillis()
        assertEquals(10170, s.awaitUpdate(9000, 5000, 60))
        assertTrue(System.currentTimeMillis() - t1 < 1500)
        job.join()
    }

    @Test fun topwayProbeRetriesAfterThirtyDays() {
        val day = 86_400_000L
        assertTrue(twProbeDue(0, 1_000_000))
        assertFalse(twProbeDue(1_000_000, 1_000_000 + 29 * day))
        assertTrue(twProbeDue(1_000_000, 1_000_000 + 30 * day))
        // the clock went back: not stuck for ever
        assertTrue(twProbeDue(5_000_000, 1_000_000))
    }

    @Test fun nwdSwitchesSourceOnlyFromAndroidOrRadioNotOftenerThanThreeSeconds() {
        assertEquals(4, nwdSourceRequest(0, true, -10_000, 0))
        assertEquals(0, nwdSourceRequest(4, false, -10_000, 0))
        assertNull(nwdSourceRequest(4, true, -10_000, 0))
        assertNull(nwdSourceRequest(2, true, -10_000, 0))
        assertNull(nwdSourceRequest(null, true, -10_000, 0))
        assertNull(nwdSourceRequest(0, true, 1000, 3999))
        assertEquals(4, nwdSourceRequest(0, true, 1000, 4000))
    }

    @Test fun tsTransactionNumbersNeedTheFingerprint() {
        assertTrue(tsFingerprintOk("com.ts.main.aidl.ITsRadioCommon", 10170))
        assertTrue(tsFingerprintOk("com.ts.x.ITsRadioCommon", 981))
        assertFalse(tsFingerprintOk("com.ts.main.aidl.ITsCommon", 10170))
        assertFalse(tsFingerprintOk(null, 10170))
        // the answer to the frequency call must look like a frequency
        assertFalse(tsFingerprintOk("com.ts.x.ITsRadioCommon", 0))
        assertFalse(tsFingerprintOk("com.ts.x.ITsRadioCommon", null))
    }

    @Test fun tsGridIndexFindsTheFrequencyOnlyExactlyOnTheGrid() {
        val grid = (8750..10800 step 10).toList()
        assertEquals(0, tsGridIndex(grid.size, 8750) { grid[it] })
        assertEquals(94, tsGridIndex(grid.size, 9690) { grid[it] })
        assertEquals(grid.size - 1, tsGridIndex(grid.size, 10800) { grid[it] })
        assertNull(tsGridIndex(grid.size, 9695) { grid[it] })
        assertNull(tsGridIndex(grid.size, 11000) { grid[it] })
        assertNull(tsGridIndex(0, 9690) { 0 })
    }
}
