package kg.forester.sg

import android.media.MediaRecorder
import kg.forester.sg.service.CallGuard
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
}
