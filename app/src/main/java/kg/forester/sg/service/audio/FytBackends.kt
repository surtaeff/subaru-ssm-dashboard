package kg.forester.sg.service.audio

import android.content.Context
import kg.forester.sg.service.BtAudio
import kg.forester.sg.service.Fyt
import kg.forester.sg.service.MusicState
import kg.forester.sg.service.RadioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * FYT's Bluetooth module (source 3): its own reports, which the stock player's now-playing pushes don't cover up;
 * the session (when the unit has one) fills in what the module leaves out: the track, the cover.
 */
internal fun fytTrack(m: MusicState, session: BtTrack): BtTrack = BtTrack(
    title = m.title.ifBlank { session.title },
    artist = if (m.title.isNotBlank()) m.artist else session.artist,
    art = m.art ?: session.art,
    // the module's play state once it has said anything, the session's before that
    playing = if (m.updatedMs > 0) m.playing else session.playing,
    posMs = if (m.posMs > 0) m.posMs else session.posMs,
    totalMs = if (m.totalMs > 0) m.totalMs else session.totalMs,
)

/**
 * The frequency command's mode by the unit's radio type (sys.fyt.radio_type): 1, the MCU's own tuner, takes mode 3
 * at once; 3 and 4, a tuner the stock app drives, mode 1. Unknown: both, the one that worked last ([learned]) first.
 */
internal fun fytTuneModes(type: String, learned: Int): List<Int> = when (type.trim()) {
    "1" -> listOf(3)
    "3", "4" -> listOf(1)
    else -> (listOf(learned).filter { it == 3 || it == 1 } + listOf(3, 1)).distinct()
}

/** Master volume steps to reach [target] on the 0..40 scale; none from an unknown level (a ramp could end louder). */
internal fun mcuVolumeSteps(cur: Int?, target: Int): List<Int> = when {
    cur == null || target == cur -> emptyList()
    else -> listOf(target - cur)
}

class FytBt(private val ctx: Context) : BtBackend, BtVoicePath {
    override suspend fun enter(byUser: Boolean): BtRoute {
        BtAudio.logPick(ctx)
        if (byUser) Fyt.source(3)
        return BtRoute.UNIT_MODULE
    }

    override fun leave() = Fyt.btCancel()

    override val track: StateFlow<BtTrack> = combine(Fyt.btMusic, polled { sessionTrack(ctx) }, ::fytTrack)
        .stateIn(audioScope, SharingStarted.WhileSubscribed(), BtTrack())

    // straight to the BT module: Fyt.next() / prev() go by the source, which is still the old one right after the switch
    override fun send(c: Transport) = when (c) {
        Transport.PLAY -> Fyt.btPlay(true)
        Transport.PAUSE -> Fyt.btPlay(false)
        Transport.NEXT -> Fyt.btNext()
        Transport.PREV -> Fyt.btPrev()
    }

    // a pause doesn't come back on FYT Bluetooth music: the phone stays paused
    override val noPauseForVoice get() = Fyt.btActive()
    override fun playingNow() = Fyt.btActive() && Fyt.btMusic.value.let { it.playing && it.updatedMs > 0 }
    override fun resume() = Fyt.btPlay(true)
}

/**
 * The FYT tuner. The preset slots and the stock radio's screen aren't in [RadioBackend]: they stay with [Fyt] and
 * [kg.forester.sg.service.FmRadio] behind [RadioCap.PRESET].
 */
class FytRadio(private val ctx: Context) : RadioBackend {
    override val capsFlow = MutableStateFlow(setOf(RadioCap.TUNE, RadioCap.SEEK, RadioCap.BAND, RadioCap.PRESET)).asStateFlow()
    override val state: StateFlow<RadioState> = Fyt.radio
    override val active: StateFlow<Boolean> = Fyt.appId.map { it == 1 }.stateIn(audioScope, SharingStarted.Eagerly, Fyt.appId.value == 1)

    /**
     * Makes the radio the source and waits for it: coming up, it tunes to its own last station, and a seek or
     * preset sent before that was undone by it.
     */
    override suspend fun on() {
        // a Bluetooth press still waiting would take the source back
        Fyt.btCancel()
        if (Fyt.appId.value == 1 || Fyt.radioUnconfirmed) return
        Fyt.ensureRadio()
        // the source command, and the radio's activity when that alone didn't do (after 1.5 s)
        withTimeoutOrNull(5000) { Fyt.appId.first { it == 1 } }
        // its own last station comes as a frequency message: waited for, not a fixed pause (bounded: no word, no stall)
        Fyt.radio.awaitUpdate(Fyt.radio.value.freq, 1500, 400)
    }

    /** Nothing has ever switched it off: the next source takes over. */
    override suspend fun off() {}

    /**
     * The frequency command; no reaction at all → the stock radio app is woken (it relays our commands on radio
     * type 3 / 4) and the command sent again: by its service first, which shows nothing, and by its activity for a
     * moment only when the tuner is still silent after that ([Fyt.wakeRadioApp]). A tuner that still doesn't take a
     * frequency gets there seek by seek.
     */
    override suspend fun tune(f: Int): Boolean {
        val tol = tuneTolerance(f)
        if (near(f, tol)) return true
        val f0 = Fyt.radio.value.freq
        if (direct(f, tol)) return true
        // not a single update: the command went nowhere
        for (step in 0 until 2) {
            if (Fyt.radio.value.freq != f0 || !Fyt.wakeRadioApp()) break
            withTimeoutOrNull(5000) { Fyt.appId.first { it == 1 } }
            // it comes up on its own last station: let it, then ours
            Fyt.radio.awaitUpdate(Fyt.radio.value.freq, 1500, 400)
            if (near(f, tol) || direct(f, tol)) return true
        }
        seekTo(f, tol)
        return near(f, tol)
    }

    private fun near(target: Int, tol: Int) = abs(Fyt.radio.value.freq - target) <= tol

    /**
     * The frequency command in the mode of this unit's radio type ([fytTuneModes]; a unit of unknown type tries each,
     * the one that worked last first, remembered); true when the tuner got there.
     */
    private suspend fun direct(target: Int, tol: Int): Boolean {
        val sp = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val learned = sp.getInt("tuneMode", -1)
        val modes = fytTuneModes(Fyt.radioType, learned)
        for (mode in modes) {
            Fyt.radioTune(target, mode)
            if (withTimeoutOrNull(2500) { Fyt.radio.first { abs(it.freq - target) <= tol } } == null) continue
            Fyt.radio.settled(Fyt.radio.value.freq, 400)
            if (mode != learned) sp.edit().putInt("tuneMode", mode).apply()
            return near(target, tol)
        }
        return false
    }

    /** Station by station towards [target], for a tuner without the frequency command. */
    private suspend fun seekTo(target: Int, tol: Int) {
        for (i in 0 until 12) {
            val cur = Fyt.radio.value.freq
            if (abs(cur - target) <= tol) break
            val up = target > cur
            Fyt.radioSeek(up, ensure = false)
            val first = withTimeoutOrNull(8000) { Fyt.radio.first { it.freq != cur } }?.freq ?: break
            // the tuner reports the frequencies it passes while seeking; the next seek sent before it stops
            // cancelled this one, and the tuner went back to where it started
            val now = Fyt.radio.settled(first, 900)
            // past the target, or round the end of the band: stop at this station
            if ((up && (now >= target - tol || now < cur)) || (!up && (now <= target + tol || now > cur))) break
        }
    }

    override fun seek(up: Boolean) = Fyt.radioSeek(up)
    override fun band(b: Int) = Fyt.radioBand(b)
}

/** The master volume lives in the MCU, like the knob; Android's stream is not the unit's sound, so never a fallback. */
class FytVolume : VolumeBackend {
    override val level: StateFlow<Int?> = Fyt.volume
    override val mcu get() = true

    /**
     * Relative to the level the unit reports. Not told yet: one step down makes it tell (waited for ~1 s); if it
     * doesn't, nothing moves further.
     */
    override suspend fun set(n: Int): VolumeSet {
        when (fytVolumeAction(level.value, Fyt.soundReady())) {
            VolumeAction.NOT_READY -> return VolumeSet.NOT_READY
            VolumeAction.PROBE -> {
                Fyt.volumeStep(-1)
                withTimeoutOrNull(1000) { level.first { it != null } } ?: return VolumeSet.UNKNOWN_LEVEL
            }
            VolumeAction.RELATIVE -> {}
        }
        mcuVolumeSteps(level.value, n).forEach { Fyt.volumeStep(it) }
        return VolumeSet.DONE
    }

    override fun step(d: Int) = Fyt.volumeStep(d)
    override fun mute(on: Boolean) = Fyt.mute(on)
}
