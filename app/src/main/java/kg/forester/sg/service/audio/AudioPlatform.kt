package kg.forester.sg.service.audio

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import kg.forester.sg.service.Fyt
import kg.forester.sg.service.RadioState
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * The head unit's sound in three parts, independent of each other: on a TS18 the stock module plays Bluetooth while
 * Android's stack talks to the OBD adapter. The family is told once (see [AudioPlatforms], [detectFamily]).
 */

/** How the phone's sound reaches the speakers after [BtBackend.enter]. */
enum class BtRoute { UNIT_MODULE, ANDROID_SINK, UNIT_CHANNEL }

/** Explicit commands only, never a play / pause toggle. */
enum class Transport { PLAY, PAUSE, NEXT, PREV }

/**
 * The phone's track. [session]: Android has a media session for it. [silentUnit]: the unit tells no session at all
 * (Microntek), the music plays all the same.
 */
data class BtTrack(
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val posMs: Long = 0,
    val totalMs: Long = 0,
    val session: Boolean = false,
    val silentUnit: Boolean = false,
)

interface BtBackend {
    /** [byUser]: the driver picked it now; false: the mode was restored, the sound is routed and nothing else asked. */
    suspend fun enter(byUser: Boolean): BtRoute
    fun leave()
    val track: StateFlow<BtTrack>
    fun send(c: Transport)
    /** The play button, with what the player shows at that moment. */
    fun press(playing: Boolean) = send(if (playing) Transport.PAUSE else Transport.PLAY)
}

/** The tuner's abilities; empty: no tuner of ours, the screen shows internet radio. */
enum class RadioCap { TUNE, SEEK, BAND, PRESET }

interface RadioBackend {
    /** What the tuner can do now; a backend that has to be opened first tells only once it has been heard from. */
    val capsFlow: StateFlow<Set<RadioCap>>
    val caps: Set<RadioCap> get() = capsFlow.value
    /** Empty [caps] until [on] is tried: the screen offers FM and asks the tuner (an experimental backend). */
    val tryable: Boolean get() = false
    val state: StateFlow<RadioState>
    /** The tuner is the unit's source now; true for one that has no source of its own to tell. */
    val active: StateFlow<Boolean> get() = alwaysActive
    suspend fun on()
    /** Gives the tuner back (closes what [on] opened); the FYT's is a no-op. */
    suspend fun off()
    /** To [f] (FM in 10 kHz units, AM in kHz); true when the tuner got there. */
    suspend fun tune(f: Int): Boolean
    fun seek(up: Boolean)
    fun band(b: Int)
}

interface VolumeBackend {
    /** The unit's own volume as it reports it; null when there is none (Android's stream) or not told yet. */
    val level: StateFlow<Int?>
    suspend fun set(n: Int): VolumeSet
    /** False: the unit isn't ready and nothing was moved. */
    fun step(d: Int): Boolean
    fun mute(on: Boolean): Boolean
    /** The volume is on the unit's own scale (0..40) and not Android's stream, even if its level isn't told yet. */
    val mcu: Boolean get() = level.value != null
}

enum class VolumeSet { DONE, UNKNOWN_LEVEL, NOT_READY }

/** What to do for a volume set on the unit's scale with the level [level] known or not, the sound module [ready] or not. */
internal enum class VolumeAction { PROBE, RELATIVE, NOT_READY }

internal fun fytVolumeAction(level: Int?, ready: Boolean) = when {
    level != null -> VolumeAction.RELATIVE
    ready -> VolumeAction.PROBE
    else -> VolumeAction.NOT_READY
}

class AudioPlatform(val id: String, val bt: BtBackend, val radio: RadioBackend, val volume: VolumeBackend)

/** The unit's own sources and stock player answer commands here (FYT with its service); it decides what [Fyt] can do. */
val AudioPlatform.ownSources get() = bt is FytBt

/**
 * What a spoken phrase needs of the Bluetooth part at that moment. [BtBackend.track] runs only while the player
 * shows it, so it can't say this; each backend keeps here the path the voice has always used.
 */
interface BtVoicePath {
    /** A pause for the voice wouldn't come back: only ducking. */
    val noPauseForVoice: Boolean
    fun playingNow(): Boolean
    fun resume()
}

object AudioPlatforms {
    private var flow: MutableStateFlow<AudioPlatform>? = null
    private val built = HashMap<String, AudioPlatform>()

    /**
     * From the first call: the answer from what can be asked at once (the packages and classes the old checks used),
     * while the rest is probed once off the main thread; a unit told by that alone changes when it comes.
     */
    @Synchronized
    fun state(ctx: Context): StateFlow<AudioPlatform> {
        flow?.let { return it }
        val app = ctx.applicationContext
        val f = MutableStateFlow(build(app, detectFamily(PlatformProbe.quick(app)).id))
        flow = f
        val t = Thread({
            val signals = runCatching { PlatformProbe.full(app) }.getOrNull() ?: return@Thread
            val d = detectFamily(signals)
            if (d.matches.size > 1) SsmTrace.log("audio: several families ${d.matches.joinToString(",")}: ${d.id}", keep = true)
            SsmTrace.log("audio: platform ${d.id} ${signals.forLog()}", keep = true)
            val next = synchronized(this) { build(app, d.id) }
            val old = f.value
            f.value = next
            // the tuner the old answer opened is given back
            if (old.radio !== next.radio) audioScope.launch { runCatching { old.radio.off() } }
        }, "audio-detect")
        t.isDaemon = true
        t.start()
        return f.asStateFlow()
    }

    fun current(ctx: Context): AudioPlatform = state(ctx).value

    private fun build(app: Context, id: String): AudioPlatform = built.getOrPut(id) {
        val android = GenericVolume(app)
        when {
            // the signs without the service (only its properties seen): none of its commands can arrive
            id == FYT && Fyt.present(app) -> AudioPlatform(id, FytBt(app), FytRadio(app), FytVolume())
            id == MICRONTEK -> AudioPlatform(id, MicrontekBt(app), GenericRadio, android)
            // experimental, never run on hardware; nothing here touches the device, that waits for the driver's pick
            id == NWD -> AudioPlatform(id, GenericBt(app), guarded(id) { NwdRadio(app) }, android)
            id == TS -> AudioPlatform(id, GenericBt(app), guarded(id) { TsRadio(app) }, android)
            id == TOPWAY -> AudioPlatform(id, GenericBt(app), guarded(id) { TopwayRadio(app) }, android)
            else -> AudioPlatform(id, GenericBt(app), GenericRadio, android)
        }
    }
}

private fun guarded(id: String, make: () -> RadioBackend): RadioBackend =
    runCatching(make).onFailure { SsmTrace.log("audio: $id radio failed to start: $it", keep = true) }.getOrNull() ?: GenericRadio

private val alwaysActive = MutableStateFlow(true).asStateFlow()

/** The tuner is at [reported] (as read back from it, null: it told nothing) within [tol] of [target]. */
internal fun tuneReached(target: Int, reported: Int?, tol: Int) = reported != null && abs(reported - target) <= tol

/** [tuneReached] by a report that came after the command ([reportsNow] above [reportsBefore]); a state seen before doesn't count. */
internal fun readBackConfirms(target: Int, reported: Int?, tol: Int, reportsBefore: Int, reportsNow: Int) =
    reportsNow > reportsBefore && tuneReached(target, reported, tol)

/** FM tolerance in the tuner's units: 10 kHz steps for FM, kHz for AM. */
internal fun tuneTolerance(f: Int) = if (f >= 8750) 15 else 9

/** Volume to [n] steps or [percent] of the maximum: on the unit's own 0..40 scale, or on Android's stream up to [androidMax]. */
internal fun voiceVolumePlan(mcuScale: Boolean, n: Int, percent: Boolean, androidMax: Int): Int =
    if (mcuScale) (if (percent) Math.round(40 * n / 100f) else n).coerceIn(0, 40)
    else (if (percent) Math.round(androidMax * n / 100f) else n).coerceIn(0, androidMax)

/** No tuner of ours: the Media screen shows internet radio. */
object GenericRadio : RadioBackend {
    override val capsFlow = MutableStateFlow(emptySet<RadioCap>()).asStateFlow()
    override val state = MutableStateFlow(RadioState()).asStateFlow()
    override suspend fun on() {}
    override suspend fun off() {}
    override suspend fun tune(f: Int) = false
    override fun seek(up: Boolean) {}
    override fun band(b: Int) {}
}

/** Android's music stream. Units that keep the volume in their own processor move the stream and not the sound. */
class GenericVolume(private val ctx: Context) : VolumeBackend {
    override val level = MutableStateFlow<Int?>(null).asStateFlow()

    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun set(n: Int): VolumeSet {
        val am = am
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, n, AudioManager.FLAG_SHOW_UI)
        trace("set $n", before, am)
        return VolumeSet.DONE
    }

    /** The music stream, and if that didn't move, the stream the system picks. */
    override fun step(d: Int): Boolean {
        val am = am
        val dir = if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        repeat(kotlin.math.abs(d)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == before) {
            repeat(kotlin.math.abs(d)) { am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI) }
        }
        trace("step $d", before, am)
        return true
    }

    override fun mute(on: Boolean): Boolean {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return true
    }

    /** Which unit and what the stream did, for the log. */
    private fun trace(what: String, before: Int, am: AudioManager) {
        SsmTrace.log(
            "voice volume $what: android stream $before → ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, " +
                "platform=${AudioPlatforms.current(ctx).id} sound=${Fyt.soundReady()} fytVol=${Fyt.volume.value} " +
                "music=${am.isMusicActive} ${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.HARDWARE}",
            keep = true,
        )
    }
}

/** For the tracks that are polled: runs only while someone collects, as the player's own loop did. */
internal val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The Bluetooth session's state once a second, off the main thread. */
internal fun polled(read: () -> BtTrack): Flow<BtTrack> =
    flow { while (true) { emit(runCatching(read).getOrDefault(BtTrack())); delay(1000) } }.flowOn(Dispatchers.IO)

/**
 * The frequency once the tuner has said something new after [from] (at most [boundMs] for the first word, so a tuner
 * that stays silent doesn't hold us), then once it has stayed on it for [quietMs]. [from] when nothing came.
 */
internal suspend fun StateFlow<RadioState>.awaitUpdate(from: Int, boundMs: Long, quietMs: Long): Int {
    val first = withTimeoutOrNull(boundMs) { first { it.freq != from } } ?: return from
    return settled(first.freq, quietMs)
}

/** The frequency once the tuner has stayed on it for [quietMs], starting from [from] (at most ~15 s). */
internal suspend fun StateFlow<RadioState>.settled(from: Int, quietMs: Long): Int {
    var f = from
    repeat(15_000 / quietMs.toInt()) {
        f = withTimeoutOrNull(quietMs) { first { it.freq != f } }?.freq ?: return f
    }
    return f
}
