package kg.forester.sg.service.audio

import android.content.Context
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import kg.forester.sg.service.BtAudio
import kg.forester.sg.service.LocalMusic
import kg.forester.sg.service.playerState
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Android's own A2DP sink (see [BtAudio]): the phone's session for the track and the buttons. */
open class GenericBt(protected val ctx: Context) : BtBackend, BtVoicePath {
    /** Counts the [leave]s: an entry still waiting for the browser or the navigator's voice is off once it moved. */
    @Volatile protected var left = 0
    /** The driver asked for this sound in this run: until then nothing here sends a play. */
    @Volatile private var userAsked = false
    @Volatile private var pulsedAt = 0L
    /** A second press soon after a pulse said this unit's sound never shows in Android's audio: true for the run. */
    @Volatile private var soundInvisible = false
    /** The latest action: one still waiting for the voice gives way to it. */
    @Volatile private var runs = 0

    override suspend fun enter(byUser: Boolean): BtRoute {
        BtAudio.logPick(ctx)
        attach()
        if (!byUser) return BtRoute.ANDROID_SINK
        userAsked = true
        val gone = left
        // the browser connects by itself in its own time, and a first pick found no session at all
        BtAudio.awaitSession(ctx, 500)
        if (gone == left) carryOut(enterPlan(true, BtAudio.facts(ctx)))
        return BtRoute.ANDROID_SINK
    }

    protected fun attach() {
        BtAudio.logLaunchers(ctx)
        BtAudio.connect(ctx)
    }

    override fun leave() { left++; userAsked = false }

    override fun press(playing: Boolean) {
        userAsked = true
        audioScope.launch {
            val since = if (pulsedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - pulsedAt
            val f = BtAudio.facts(ctx).copy(playing = playing)
            if (provesSoundInvisible(f, since)) { soundInvisible = true; SsmTrace.log("bt: sound doesn't show in Android's audio on this unit", keep = true) }
            carryOut(pressPlan(f, since, soundInvisible))
        }
    }

    private suspend fun carryOut(plan: SinkPlan) {
        SsmTrace.log("bt: $plan", keep = true)
        val mine = ++runs
        val gone = left
        fun stale() = mine != runs || gone != left
        var step = plan.focus
        if (step == FocusStep.AFTER_VOICE) {
            var waited = 0L
            while (BtAudio.voiceBusy(ctx) && waited < VOICE_WAIT_MS) { delay(200); waited += 200 }
            if (stale()) return
            // a call goes on and on: music started under it would come up when it ends, unasked
            if (BtAudio.callOn(ctx)) { SsmTrace.log("bt: call still on, nothing sent", keep = true); return }
            step = focusStep(BtAudio.facts(ctx)).takeIf { it == FocusStep.PULSE } ?: FocusStep.NONE
        }
        var answer: Int? = null
        if (step == FocusStep.PULSE) {
            pulsedAt = SystemClock.elapsedRealtime()
            answer = BtAudio.focusPulse(ctx)
            // the sink's loss arrives on its own thread: a play before it finds the sink still as it was
            delay(PULSE_GAP_MS)
            if (stale()) return
        }
        if (plan.prepare) BtAudio.prepare(ctx)
        if (plan.play) send(Transport.PLAY)
        if (plan.pause) send(Transport.PAUSE)
        if (step == FocusStep.PULSE) audioScope.launch {
            delay(OUTCOME_AFTER_MS)
            val f = BtAudio.facts(ctx)
            SsmTrace.log("bt: pulse outcome: answer $answer, session ${f.session} playing ${f.playing} heard ${f.heard}", keep = true)
        }
    }

    override val track: StateFlow<BtTrack> = polled { read() }.stateIn(audioScope, SharingStarted.WhileSubscribed(), BtTrack())

    protected open fun read(): BtTrack {
        val t = sessionTrack(ctx)
        // the phone plays, nothing sounds: Android's Bluetooth lost the audio focus (CarPlay, another player)
        BtAudio.kick(ctx, userAsked && !soundInvisible)
        return t
    }

    override fun send(c: Transport) = BtAudio.control(ctx, sessionlessKey(c, LocalMusic.state.value.queue.isNotEmpty())) {
        when (c) {
            Transport.PLAY -> it.play()
            Transport.PAUSE -> it.pause()
            Transport.NEXT -> it.skipToNext()
            Transport.PREV -> it.skipToPrevious()
        }
    }

    override val noPauseForVoice get() = false
    override fun playingNow() = BtAudio.controller(ctx)?.playbackState?.state == PlaybackState.STATE_PLAYING
    override fun resume() { BtAudio.controller(ctx)?.transportControls?.play() }
}

/** The phone's track as the Bluetooth session shows it; blank without a session. */
internal fun sessionTrack(ctx: Context): BtTrack {
    val s = BtAudio.controller(ctx)?.let { playerState(it) } ?: return BtTrack()
    return BtTrack(s.title, s.artist, s.art, s.playing, s.posMs, s.totalMs, s.session)
}

/** What the sink's state comes down to, as far as Android tells it. */
internal data class SinkFacts(
    val sdk: Int,
    /** A session that counts ([kg.forester.sg.service.pickBtSession]): there is a phone to play. */
    val session: Boolean,
    val playing: Boolean,
    /** [android.media.AudioManager.isMusicActive]: the sink, or anyone else on the music stream. */
    val heard: Boolean,
    /** A voice or a call is on: the navigator's phrase is not to be cut. */
    val voiceBusy: Boolean = false,
    /**
     * The session is Android's own sink (com.android.bluetooth): only its sound shows in [heard] and only it waits for
     * the focus. A head unit's own module app plays through the unit's audio path, invisible to Android: a focus
     * pulse would only stop it, and its play/pause is a plain toggle, as up to 0.9-beta32.
     */
    val androidSink: Boolean = true,
)

internal enum class FocusStep { NONE, PULSE, AFTER_VOICE }

internal data class SinkPlan(val focus: FocusStep = FocusStep.NONE, val prepare: Boolean = false, val play: Boolean = false, val pause: Boolean = false)

private const val VOICE_WAIT_MS = 8000L
private const val RECOVER_GAP_MS = 20_000L
private const val KICK_GAP_MS = 15_000L
private const val PULSE_GAP_MS = 250L
private const val OUTCOME_AFTER_MS = 2000L

/**
 * GAIN and an abandon at once ([BtAudio.focusPulse]). The sink asks for the focus only from "none": after a transient
 * loss (CarPlay, on Android below 10) it stays silent while the seconds run. A GAIN of ours sends it there, as the
 * radio does: that is why radio, Bluetooth, play gave sound.
 *
 * The price: everyone holding the focus gets a permanent loss and drops out of the focus stack, so the abandon gives
 * them nothing back and a player stays paused. So only with a phone that will take over, never while anyone is heard
 * (the sink itself would lose the focus, and a player or CarPlay holding a transient focus gives it back by itself),
 * and after the navigator's phrase, not over it.
 */
internal fun focusStep(f: SinkFacts): FocusStep = when {
    !f.session || !f.androidSink || !(f.sdk < 29 || f.playing) -> FocusStep.NONE
    f.voiceBusy -> FocusStep.AFTER_VOICE
    f.heard -> FocusStep.NONE
    else -> FocusStep.PULSE
}

private fun wakePlan(f: SinkFacts, soundInvisible: Boolean = false) =
    SinkPlan(if (soundInvisible) FocusStep.NONE else focusStep(f), prepare = f.session, play = true)

/**
 * The one rule for what entering Bluetooth asks: a mode restored at start asks for nothing, the focus and the play
 * come from the driver's action only. No phone: nothing either (a media key would start whichever app played last).
 */
internal fun enterPlan(byUser: Boolean, f: SinkFacts): SinkPlan = if (byUser && f.session) wakePlan(f) else SinkPlan()

/**
 * The play button. A session that plays with nothing heard is the silent sink: a pause would only stop the phone. A
 * second press soon after a pulse pauses, and says the unit's sound just doesn't show in Android's audio
 * ([provesSoundInvisible]): from then on [soundInvisible] says pause at once, and nothing is pulsed for.
 */
internal fun pressPlan(f: SinkFacts, sincePulseMs: Long, soundInvisible: Boolean = false): SinkPlan = when {
    !f.playing -> wakePlan(f, soundInvisible)
    f.heard || !f.androidSink || soundInvisible || provesSoundInvisible(f, sincePulseMs) -> SinkPlan(pause = true)
    else -> wakePlan(f)
}

internal fun provesSoundInvisible(f: SinkFacts, sincePulseMs: Long): Boolean = f.playing && !f.heard && sincePulseMs < RECOVER_GAP_MS

/**
 * The media key when there is no session to tell: the head unit sends it to its source, but it goes to the app that
 * played last too. So explicit, and none when that is the app's own player, which would start instead.
 */
internal fun sessionlessKey(c: Transport, ownQueue: Boolean): Int? = if (ownQueue) null else when (c) {
    Transport.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
    Transport.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
    Transport.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
    Transport.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
}

/** The check while the player shows: only after the driver asked in this run, at most every 15 s. */
internal fun kickDue(userAsked: Boolean, playing: Boolean, heard: Boolean, sinceMs: Long): Boolean =
    userAsked && playing && !heard && sinceMs >= KICK_GAP_MS
