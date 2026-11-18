package kg.forester.sg

import android.media.AudioAttributes
import kg.forester.sg.service.AudioWatch
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioWatchTest {
    private fun p(u: Int) = AudioWatch.Player(u, 0)

    @Test fun nobodyPlays() = assertEquals("nothing", AudioWatch.summary(emptyList()))

    @Test fun sameSetSameLineInAnyOrder() {
        val a = AudioWatch.summary(listOf(p(AudioAttributes.USAGE_MEDIA), p(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION), p(AudioAttributes.USAGE_MEDIA)))
        val b = AudioWatch.summary(listOf(p(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION), p(AudioAttributes.USAGE_MEDIA), p(AudioAttributes.USAGE_MEDIA)))
        assertEquals("media×2, sonification", a)
        assertEquals(a, b)
    }
}
