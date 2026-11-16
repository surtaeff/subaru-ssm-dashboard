package kg.forester.sg.service

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock

/**
 * A phone call through the head unit (Bluetooth hands-free) needs the unit's only microphone. While the assistant
 * keeps its own capture open, many FYT units hand the call silence: the other side stops hearing the driver. So the
 * assistant lets go of the microphone for the whole call and takes it back a few seconds after it ends.
 *
 * A call is seen two ways, whichever the unit gives: the audio mode (ringing, in call, in communication — what the
 * FYT Bluetooth app and messengers set), and, on Android 10+, another app recording with a call source.
 */
object CallGuard {
    /** After the call: the hang-up tone and the unit switching the audio back, before the microphone is opened again. */
    private const val AFTER_CALL_MS = 3000L

    @Volatile private var lastCallMs = 0L

    /** True during a call and for a short while after it. Cheap enough to ask a few times a second. */
    fun active(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (inCall(ctx)) { lastCallMs = now; return true }
        return lastCallMs != 0L && now - lastCallMs < AFTER_CALL_MS
    }

    private fun inCall(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val others = runCatching { am.activeRecordingConfigurations }.getOrNull().orEmpty()
            if (others.any { isCallSource(it.clientAudioSource) }) return true
        }
        return false
    }

    internal fun isCallSource(source: Int) = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_UPLINK,
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> true
        else -> false
    }
}
