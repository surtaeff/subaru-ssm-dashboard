package kg.forester.sg

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import kg.forester.sg.service.CallGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGuardTest {
    @Test fun callSourcesMakeTheAssistantLetGo() {
        assertTrue(CallGuard.isCallSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        assertTrue(CallGuard.isCallSource(MediaRecorder.AudioSource.VOICE_CALL))
        assertTrue(CallGuard.isCallSource(MediaRecorder.AudioSource.VOICE_UPLINK))
        assertTrue(CallGuard.isCallSource(MediaRecorder.AudioSource.VOICE_DOWNLINK))
    }

    @Test fun otherRecordersDoNot() {
        assertFalse(CallGuard.isCallSource(MediaRecorder.AudioSource.MIC))
        assertFalse(CallGuard.isCallSource(MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertFalse(CallGuard.isCallSource(MediaRecorder.AudioSource.CAMCORDER))
    }

    private fun signs(mode: Int = AudioManager.MODE_NORMAL, others: List<CallGuard.Rec> = emptyList(), ignored: Set<Int> = emptySet(),
                      firstSeen: Map<Int, Long> = emptyMap(), sounds: List<Int> = emptyList(), held: Boolean = false, bt: String? = null, now: Long = 100_000L) =
        CallGuard.callSigns(mode, others, ignored, firstSeen, sounds, held, bt, now)

    @Test fun nothingIsNoCall() {
        assertTrue(signs(sounds = listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)).isEmpty())
    }

    @Test fun eachSignOfACallCounts() {
        assertEquals(1, signs(mode = AudioManager.MODE_IN_CALL).size)
        assertEquals(1, signs(mode = AudioManager.MODE_IN_COMMUNICATION).size)
        assertEquals(1, signs(mode = AudioManager.MODE_RINGTONE).size)
        assertEquals(1, signs(sounds = listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)).size)
        assertEquals(1, signs(sounds = listOf(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)).size)
        assertEquals(1, signs(held = true).size)
        assertEquals(1, signs(bt = "Bluetooth hands-free audio on").size)
    }

    @Test fun anAppThatStartsRecordingAfterUsGetsTheMicrophone() {
        // the unit's Bluetooth app opens the microphone with a plain source when the call is answered
        val bt = CallGuard.Rec(session = 77, source = MediaRecorder.AudioSource.MIC)
        assertEquals(1, signs(others = listOf(bt), firstSeen = mapOf(77 to 99_000L)).size)
        // a voice service that recorded before the assistant began is no call
        assertTrue(signs(others = listOf(bt), ignored = setOf(77), firstSeen = mapOf(77 to 99_000L)).isEmpty())
        // nor one that has recorded for longer than any call
        assertTrue(signs(others = listOf(bt), firstSeen = mapOf(77 to 100_000L - CallGuard.RECORDER_MAX_MS - 1)).isEmpty())
        // a call source counts even when it was there before
        val call = CallGuard.Rec(session = 78, source = MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        assertEquals(1, signs(others = listOf(call), ignored = setOf(78), firstSeen = mapOf(78 to 0L)).size)
    }
}
