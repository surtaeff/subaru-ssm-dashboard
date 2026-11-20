package kg.forester.sg.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kg.forester.sg.ssm.SsmTrace

/**
 * A phone call through the head unit (Bluetooth hands-free) needs the unit's only microphone. While the assistant
 * keeps its own capture open, many units hand the call silence: the other side stops hearing the driver. So the
 * assistant lets go of the microphone for the whole call and takes it back a few seconds after it ends.
 *
 * Head units seldom say "a call" the Android way: the unit's own Bluetooth app (com.nwd.* on NWD units) talks to its
 * own module and the audio mode may never change. So whatever the unit gives counts:
 * - the audio mode (ringing, in call, in communication: the FYT Bluetooth app, messengers);
 * - on Android 10+ another app recording with a call source, or any other app that starts recording after the
 *   assistant (Android 10 lets both record and gives the sound to the app on screen, often ours, and the call hears
 *   silence), for as long as it records;
 * - a call's or a ringtone's sound playing;
 * - our player held back by a short focus loss (the Bluetooth app taking the sound), not by our own voice;
 * - Android's hands-free client audio or a SCO link up.
 * Each change goes to the log, with NWD's mcu_current_source, to see on a unit which of them it gives.
 */
object CallGuard {
    /** After the call: the hang-up tone and the unit switching the audio back, before the microphone is opened again. */
    private const val AFTER_CALL_MS = 3000L
    /** Another app's capture on this long is no call but a voice service always on: listened over after that. */
    internal const val RECORDER_MAX_MS = 20 * 60_000L
    private const val MAX_LINES = 80
    private const val OTHER_APP = "another app records"
    private const val HFP_AUDIO = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
    private const val HFP_CALL = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
    private const val EXTRA_STATE = "android.bluetooth.profile.extra.STATE"
    private const val HFP_AUDIO_CONNECTED = 2

    /** Another app's capture as Android shows it (on 10+ without the app, but with these). */
    internal data class Rec(val session: Int, val source: Int)

    @Volatile private var lastCallMs = 0L
    /** Our capture's session while the assistant holds the microphone, 0 when it doesn't. */
    @Volatile private var ours = 0
    private val lock = Any()
    /** Other apps' captures that are no call: on before ours began (a voice service always on). */
    private val ignored = HashSet<Int>()
    /** When each other capture was first seen. */
    private val firstSeen = HashMap<Int, Long>()
    private var looked = false
    @Volatile private var playerHeld = false
    @Volatile private var playerHeldMs = 0L
    @Volatile private var hfpAudio = false
    @Volatile private var scoAudio = false
    @Volatile private var started = false
    private var said: List<String>? = null
    private var mcuSaid: String? = null
    private var mcuMs = 0L
    private var lines = 0

    /** What told of the call last time one was seen, for the log and the status line. */
    @Volatile var signs: List<String> = emptyList()
        private set

    /** Only other apps recording, nothing that says a call: the status line says so instead of "a call". */
    val otherAppOnly: Boolean get() = signs.isNotEmpty() && signs.all { it.startsWith(OTHER_APP) }

    /** True during a call and for a short while after it. Cheap enough to ask a few times a second. */
    fun active(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val s = look(ctx, now)
        if (s.isNotEmpty()) { signs = s; lastCallMs = now; return true }
        return lastCallMs != 0L && now - lastCallMs < AFTER_CALL_MS
    }

    /**
     * Our capture has begun with [session]: other apps' captures already seen at the last look (when there was no
     * call) are no call. One that came up since may be the call starting this very moment, so it still counts.
     */
    fun capturing(ctx: Context, session: Int) {
        ours = session
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val ids = recorders(am).map { it.session }.filter { it != session }
        synchronized(lock) { ids.filter { it in firstSeen }.forEach { ignored += it } }
    }

    fun released() { ours = 0 }

    /** Our player held back by a short focus loss (another app took the sound for a while), not by our own voice. */
    fun playerHeld(on: Boolean) {
        if (on == playerHeld) return
        playerHeld = on
        playerHeldMs = SystemClock.elapsedRealtime()
        line("call guard: our player ${if (on) "held back by a short focus loss" else "free again"}")
    }

    private fun recorders(am: AudioManager): List<Rec> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        runCatching { am.activeRecordingConfigurations.map { Rec(it.clientAudioSessionId, it.clientAudioSource) } }.getOrNull().orEmpty()
    else emptyList()

    private fun look(ctx: Context, now: Long): List<String> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        listen(ctx)
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        val others = recorders(am).filter { it.session != ours }
        val sounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { am.activePlaybackConfigurations.map { it.audioAttributes.usage } }.getOrNull().orEmpty() else emptyList()
        val bt = when {
            hfpAudio -> "Bluetooth hands-free audio on"
            scoAudio -> "Bluetooth SCO audio on"
            else -> null
        }
        val s = synchronized(lock) {
            val ids = others.map { it.session }.toSet()
            firstSeen.keys.retainAll(ids)
            ignored.retainAll(ids)
            for (id in ids) firstSeen.getOrPut(id) { now }
            // what records when the assistant first looks is there for its own reasons (a call already on is rare)
            if (!looked) { looked = true; ignored += ids }
            callSigns(mode, others, ignored, firstSeen, sounds, playerHeld && now - playerHeldMs < RECORDER_MAX_MS, bt, now)
        }
        note(ctx, s, mode, others, now)
        return s
    }

    /** Each sign of a call, in words for the log; empty when nothing tells of one. */
    internal fun callSigns(mode: Int, others: List<Rec>, ignored: Set<Int>, firstSeen: Map<Int, Long>, sounds: List<Int>,
                           playerHeld: Boolean, bt: String?, now: Long): List<String> {
        val s = ArrayList<String>()
        if (mode > AudioManager.MODE_NORMAL) s += "audio mode $mode"
        for (r in others) when {
            isCallSource(r.source) -> s += "a call records (source ${r.source})"
            r.session !in ignored && now - (firstSeen[r.session] ?: now) < RECORDER_MAX_MS -> s += "$OTHER_APP (source ${r.source})"
        }
        if (sounds.any { it == AudioAttributes.USAGE_VOICE_COMMUNICATION || it == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING }) s += "a call sounds"
        if (AudioAttributes.USAGE_NOTIFICATION_RINGTONE in sounds) s += "a ringtone sounds"
        if (playerHeld) s += "our player held back"
        if (bt != null) s += bt
        return s
    }

    internal fun isCallSource(source: Int) = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_UPLINK,
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> true
        else -> false
    }

    /** Every change of what is seen goes to the log; the NWD source setting too, the one thing such a unit may change. */
    @Synchronized private fun note(ctx: Context, s: List<String>, mode: Int, others: List<Rec>, now: Long) {
        if (now - mcuMs >= 2000) {
            mcuMs = now
            val v = runCatching { android.provider.Settings.System.getString(ctx.contentResolver, "mcu_current_source") }.getOrNull()
            if (v != null && v != mcuSaid) {
                if (mcuSaid != null) line("call guard: mcu_current_source $mcuSaid -> $v")
                mcuSaid = v
            }
        }
        if (s == said) return
        val first = said == null
        said = s
        if (first && s.isEmpty()) return
        line("call guard: " + (if (s.isEmpty()) "no call" else s.joinToString("; ")) +
            " (mode $mode, other recorders ${others.map { it.source }}, mcu_current_source=${mcuSaid ?: "-"})")
    }

    @Synchronized private fun line(msg: String) {
        if (lines >= MAX_LINES) return
        lines++
        SsmTrace.log(msg, keep = true)
    }

    /** Android's own hands-free client (units that pair the phone through Android's Bluetooth): the call's audio link. */
    private fun listen(ctx: Context) {
        if (started) return
        started = true
        val f = IntentFilter().apply { addAction(HFP_AUDIO); addAction(HFP_CALL); addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    HFP_AUDIO -> {
                        hfpAudio = i.getIntExtra(EXTRA_STATE, 0) == HFP_AUDIO_CONNECTED
                        line("call guard: hands-free audio ${if (hfpAudio) "on" else "off"}")
                    }
                    HFP_CALL -> line("call guard: hands-free call changed")
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val on = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, 0) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                        if (on != scoAudio) line("call guard: SCO audio ${if (on) "on" else "off"}")
                        scoAudio = on
                    }
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(ctx.applicationContext, r, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { line("call guard: no Bluetooth broadcasts: $it") }
    }
}
