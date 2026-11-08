package kg.forester.sg

import kg.forester.sg.data.VeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeFilterTest {
    @Test fun firstReadingGoesStraight() {
        assertEquals(80f, VeFilter().at(1000, 80f, 2000)!!, 0.01f)
    }

    @Test fun loneSpikeIsDropped() {
        val f = VeFilter()
        var t = 1000L
        repeat(10) { f.at(t, 80f, 2500); t += 80 }
        val spike = f.at(t, 160f, 2500)!!
        assertEquals(80f, spike, 1f)
    }

    @Test fun realStepIsFollowed() {
        val f = VeFilter()
        var t = 1000L
        repeat(10) { f.at(t, 60f, 2500); t += 80 }
        var v = 0f
        repeat(20) { v = f.at(t, 95f, 4000)!!; t += 80 }
        assertEquals(95f, v, 1f)
    }

    @Test fun sameSampleSameAnswer() {
        val f = VeFilter()
        f.at(1000, 70f, 2000)
        val a = f.at(1080, 90f, 2000)
        assertEquals(a, f.at(1080, 90f, 2000))
    }

    @Test fun shortGapHoldsLongGapClears() {
        val f = VeFilter()
        var t = 1000L
        repeat(10) { f.at(t, 75f, 2500); t += 80 }
        assertNotNull(f.at(t + 1000, null, 1800))
        assertNull(f.at(t + 2500, null, 1800))
    }

    @Test fun engineStopClears() {
        val f = VeFilter()
        f.at(1000, 75f, 900)
        assertNull(f.at(1080, null, 0))
    }

    @Test fun oldSampleDoesNotMoveIt() {
        val f = VeFilter()
        f.at(2000, 70f, 2000)
        val v = f.at(1900, 120f, 2000)
        assertTrue(v == 70f)
    }
}
