package kg.forester.sg

import kg.forester.sg.ui.LogoScale
import kg.forester.sg.ui.SplashFrame
import kg.forester.sg.ui.StartSteps
import kg.forester.sg.ui.car3dAutomatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplashFrameTest {
    private val sizes = listOf(1024f to 600f, 1280f to 720f, 1920f to 720f, 1920f to 1080f, 800f to 480f, 1080f to 1920f, 720f to 1280f, 1080f to 2400f, 2560f to 720f)

    @Test fun theWindowsShapePicksThePicture() {
        assertSame(SplashFrame.LANDSCAPE, SplashFrame.artFor(1024f, 600f))
        assertSame(SplashFrame.PORTRAIT, SplashFrame.artFor(600f, 1024f))
    }

    @Test fun thePictureCoversEveryWindowWithoutStretching() {
        for ((w, h) in sizes) {
            val a = SplashFrame.artFor(w, h)
            val c = SplashFrame.cover(a, w, h)
            assertTrue("$w x $h left", c.left <= 0.5f)
            assertTrue("$w x $h top", c.top <= 0.5f)
            assertTrue("$w x $h right", c.left + a.w * c.scale >= w - 0.5f)
            assertTrue("$w x $h bottom", c.top + a.h * c.scale >= h - 0.5f)
        }
    }

    @Test fun theLogoStaysInTheFrameAtTheBriefsSizes() {
        for ((w, h) in listOf(1024f to 600f, 1920f to 720f, 1080f to 1920f, 720f to 1280f)) {
            val a = SplashFrame.artFor(w, h)
            val c = SplashFrame.cover(a, w, h)
            assertTrue("$w x $h", c.x(a.focusX0) >= -0.5f && c.x(a.focusX1) <= w + 0.5f)
            assertTrue("$w x $h", c.y(a.focusY0) >= -0.5f && c.y(a.focusY1) <= h + 0.5f)
        }
    }

    @Test fun theLoaderIsNeverCropped() {
        for ((w, h) in sizes) {
            val a = SplashFrame.artFor(w, h)
            val c = SplashFrame.cover(a, w, h)
            val b = SplashFrame.bar(a, c, w, h)
            assertTrue("$w x $h x0 ${b.x0}", b.x0 >= 0f)
            assertTrue("$w x $h x1 ${b.x1}", b.x1 <= w)
            assertTrue("$w x $h y0 ${b.y0}", b.y0 >= 0f)
            assertTrue("$w x $h word ${b.wordY1}", b.wordY1 <= h)
            assertTrue("$w x $h has room", b.x1 - b.x0 > w * 0.3f)
        }
    }

    @Test fun theLoaderLiesExactlyOnItsPlaceWhereNothingCutsIt() {
        val a = SplashFrame.LANDSCAPE
        val c = SplashFrame.cover(a, 1024f, 600f)
        val b = SplashFrame.bar(a, c, 1024f, 600f)
        assertEquals(c.x(a.barX0), b.x0, 0.01f)
        assertEquals(c.y(a.barY0), b.y0, 0.01f)
        assertEquals(c.x(a.barX1), b.x1, 0.01f)
        assertEquals(c.y(a.wordY1), b.wordY1, 0.01f)
    }

    @Test fun theBarGoesByRealStepsOnly() {
        assertEquals(0f, SplashFrame.share(0), 0f)
        assertEquals(1f, SplashFrame.share(StartSteps.TOTAL), 0f)
        assertTrue(SplashFrame.share(1) < SplashFrame.share(2))
    }

    @Test fun whoNeverPickedGetsCarPulseAndWhoPickedKeepsIt() {
        val repo = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertTrue("p.getString(\"splashMode\", \"carpulse\")" in repo)
        val brand = File("src/main/java/kg/forester/sg/ui/BrandLogo.kt").readText()
        for (mode in listOf("carpulse", "route", "subaru", "car", "custom", "name")) assertTrue(mode, "\"$mode\" to" in brand)
    }

    @Test fun thePicturesAreSmallAndNotTheFullPng() {
        val res = File("src/main/res/drawable-nodpi")
        for (n in listOf("splash_carpulse_land.webp", "splash_carpulse_port.webp")) {
            val f = File(res, n)
            assertTrue(n, f.exists() && f.length() < 220_000)
        }
    }

    // ---- logos

    @Test fun aLogoShrinksByHalvesThenTheLastStep() {
        assertEquals(listOf(300 to 148, 150 to 74, 110 to 54), LogoScale.steps(600, 296, 110, 54))
        assertEquals(listOf(107 to 64), LogoScale.steps(214, 128, 107, 64).take(1))
        assertEquals(emptyList<Pair<Int, Int>>(), LogoScale.steps(100, 50, 100, 50))
    }

    @Test fun aLogoFitsItsBoxAndIsNeverBlownUp() {
        assertEquals(162 to 80, LogoScale.fit(600, 296, 200, 80))
        assertEquals(214 to 128, LogoScale.fit(214, 128, 1000, 1000))
    }

    @Test fun sharpeningLiftsAnEdgeAndLeavesTheOutlineAlone() {
        val dark = 0xFF202020.toInt()
        val light = 0xFF808080.toInt()
        val px = IntArray(25) { if (it % 5 >= 3) light else dark }
        val out = LogoScale.sharpen(px, 5, 5)
        assertTrue("the light side of the edge lighter", (out[5 + 3] and 0xFF) > 0x80)
        assertTrue("the dark side darker", (out[5 + 2] and 0xFF) < 0x20)
        assertEquals("the rim of the picture untouched", px[0], out[0])
        val clear = IntArray(25) { if (it == 12) 0xFF808080.toInt() else 0 }
        assertEquals("next to transparency no halo", clear.toList(), LogoScale.sharpen(clear, 5, 5).toList())
    }

    // ---- «Автоматически»

    @Test fun automaticGoesBackToTheEcusModel() {
        assertEquals("no ROM seen yet: as it is", "SK", car3dAutomatic("SK", "", null))
        assertEquals("a sports ECU says nothing of the model", "SK", car3dAutomatic("SK", "LINK", null))
        assertEquals("the ECU names no car", kg.forester.sg.ui.CAR3D_UNKNOWN, car3dAutomatic("SK", "A1B2", null))
        assertEquals("the ECU names one", kg.forester.sg.ui.car3dFromEcu("SK", false, "Forester SH"), car3dAutomatic("SK", "A1B2", "Forester SH"))
    }
}
