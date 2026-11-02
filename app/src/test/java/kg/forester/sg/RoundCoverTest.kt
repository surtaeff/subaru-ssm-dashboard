package kg.forester.sg

import kg.forester.sg.ui.RoundCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class RoundCoverTest {
    private val eps = 1e-3f

    @Test fun aSquareLogoFitsInsideTheCircle() {
        // half a diagonal of the logo's square, against the circle's radius of 1/2
        assertTrue(RoundCover.LOGO_FIT * sqrt(2f) / 2f <= 0.5f)
    }

    @Test fun theIconFitsInsideTheCircle() {
        assertTrue(RoundCover.ICON_FIT * sqrt(2f) / 2f <= 0.5f)
    }

    @Test fun theRingStartsOutsideTheCoverWithASmallGap() {
        val edge = RoundCover.COVER_WITH_RING / 2f * RoundCover.GROW_MAX
        val gap = RoundCover.RAY_FROM - edge
        assertTrue("touches at its largest: $gap", gap > 0f)
        assertTrue("too far to emanate from it: $gap", gap < 0.05f)
    }

    @Test fun theLongestRayStaysInItsBox() {
        assertTrue(RoundCover.RAY_FROM + RoundCover.rayLength(1f) <= 0.5f + eps)
        assertEquals(RoundCover.rayLength(1f), RoundCover.rayLength(5f), eps)
    }

    @Test fun aRayGrowsWithItsLevel() {
        assertTrue(RoundCover.rayLength(0.5f) > RoundCover.rayLength(0.04f))
        assertTrue(RoundCover.rayLength(0f) > 0f)
    }

    @Test fun fewerRaysOnLow() {
        assertTrue(RoundCover.rays(low = true) < RoundCover.rays(low = false))
        assertEquals(96, RoundCover.rays(low = false))
    }

    @Test fun lowTierRingIsCapped() {
        // the same picture for every clock value inside one step of the cap
        val a = RoundCover.cappedClock(10.001f, low = true)
        val b = RoundCover.cappedClock(10.03f, low = true)
        val c = RoundCover.cappedClock(10.2f, low = true)
        assertEquals(a, b, 0f)
        assertTrue(c > a)
        assertEquals(10.03f, RoundCover.cappedClock(10.03f, low = false), 0f)
    }

    /** No real level: the same wave for every ray, only moved round the ring. Nothing about a bass or a band. */
    @Test fun thePlainAnimationIsOneEvenWaveRoundTheRing() {
        val rays = 96
        val third = rays / RoundCover.WAVE_HUMPS
        for (k in 0 until rays) {
            assertEquals(RoundCover.wave(k, rays, 1.3f), RoundCover.wave((k + third) % rays, rays, 1.3f), eps)
        }
        val one = FloatArray(rays) { RoundCover.wave(it, rays, 1.3f) }
        val later = FloatArray(rays) { RoundCover.wave(it, rays, 1.3f + RoundCover.WAVE_PERIOD / 4f) }
        assertFalse(one.contentEquals(later))
        // a full period brings it back
        for (k in 0 until rays) assertEquals(RoundCover.wave(k, rays, 0.4f), RoundCover.wave(k, rays, 0.4f + RoundCover.WAVE_PERIOD), eps)
        one.forEach { assertTrue(it in 0f..1f) }
    }

    @Test fun whatMovesTheRing() {
        assertEquals(RoundCover.Drive.STILL, RoundCover.drive(playing = false, live = false))
        assertEquals(RoundCover.Drive.STILL, RoundCover.drive(playing = false, live = true))
        assertEquals(RoundCover.Drive.LEVELS, RoundCover.drive(playing = true, live = true))
        assertEquals(RoundCover.Drive.WAVE, RoundCover.drive(playing = true, live = false))
    }

    @Test fun everyPlayerGoesThroughTheRoundCoverAndNothingCutsItsRing() {
        val ui = File("src/main/java/kg/forester/sg/ui")
        val media = File(ui, "MediaScreen.kt").readText()
        val radio = File(ui, "MediaRadio.kt").readText()
        val glow = media.substringAfter("internal fun GlowCover").substringBefore("/** The track:")
        assertTrue(glow.contains("CircleShape"))
        assertFalse(glow.contains("RoundedCornerShape"))
        // a clip on the box round the cover would cut the ring
        (media + radio).lines().filter { "cover = " in it }.forEach { assertFalse(it, it.contains(".clip(")) }
        // FM, internet radio, files, Bluetooth, other apps
        assertEquals(5, (media + radio).lines().count { "cover = " in it && "GlowCover(" in it })
        val mini = File(ui, "MiniPlayer.kt").readText()
        assertTrue(mini.contains("val coverShape = CircleShape"))
    }
}
