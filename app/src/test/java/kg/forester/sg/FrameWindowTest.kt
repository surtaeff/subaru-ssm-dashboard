package kg.forester.sg

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import kg.forester.sg.ui.CarPaint
import kg.forester.sg.ui.FrameStore
import kg.forester.sg.ui.FrameTrim
import kg.forester.sg.ui.TintCache
import kg.forester.sg.ui.carTintCompressed
import kg.forester.sg.ui.carTintKey
import kg.forester.sg.ui.frameTrimFor
import kg.forester.sg.ui.frameWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FrameWindowTest {
    private val frameBytes = 836L * 408 * 4

    /** A frame as a string: "f12" the full one, "h12" the half one; shrinking is what a trim does to a full frame. */
    private fun store(count: Int = 72, drive: Int = 43) = FrameStore<String>(count, drive) { it.replace('f', 'h') }

    @Test fun frameWindowKeepsSevenAroundDrive() {
        assertEquals((40..46).toSet(), frameWindow(72, 43))
        assertEquals(7, frameWindow(72, 43).size)
        // round the turntable's seam
        assertEquals(setOf(70, 71, 0, 1, 2, 3, 4), frameWindow(72, 1))
        // a model with few frames: all of them, each once
        assertEquals((0 until 5).toSet(), frameWindow(5, 2))
        assertEquals(emptySet<Int>(), frameWindow(0, 0))
    }

    @Test fun onlyTheWindowAndTheViewOnScreenStayFull() {
        val s = store()
        for (i in 0 until 72) { s.look(i); s.putFull(i, "f$i") }
        assertEquals(frameWindow(72, 43) + 71, s.fullHeld())
        assertTrue(s.fullHeld().size * frameBytes < 11_000_000)
        // the view left behind is still there at half size, a turn back has a picture at once
        s.look(40)
        assertEquals(frameWindow(72, 43), s.fullHeld())
        assertEquals("h71", s.best(71))
        assertNull(s.full(71))
        // a window frame is never given up for the view on screen
        s.look(5); s.putFull(5, "f5")
        s.look(6)
        assertEquals("f43", s.full(43))
        assertEquals("h5", s.best(5))
    }

    @Test fun lateDecodeOfAViewAlreadyLeftIsNotKept() {
        val s = store()
        s.look(10)
        s.look(11)
        s.putFull(10, "f10") // the decode that finished after the finger moved on
        assertTrue(s.fullHeld().isEmpty())
    }

    @Test fun halvesAreTwentyFourMegabytesOnlyAfterTheyAreAskedFor() {
        val s = store()
        for (i in frameWindow(72, 43)) s.putFull(i, "f$i")
        assertTrue("nothing at half size until the first touch", s.halfHeld().isEmpty())
        for (i in 0 until 72) if (s.best(i) == null) s.putHalf(i, "h$i")
        assertEquals(65, s.halfHeld().size)
        assertTrue(s.halfHeld().size * frameBytes / 4 < 24_600_000)
        assertTrue(s.halvesComplete())
    }

    @Test fun trimReleaseKeepsHalfResForInstantReturn() {
        val s = store()
        for (i in frameWindow(72, 43)) s.putFull(i, "f$i")
        s.look(10); s.putFull(10, "f10")
        assertTrue(s.trim(FrameTrim.FULL))

        assertTrue("full frames released", s.fullHeld().isEmpty())
        // back on the screen: the car is there at once, in the view it was left in and in the rear view
        assertEquals("h10", s.best(10))
        for (i in frameWindow(72, 43)) assertEquals("h$i", s.best(i))
        assertTrue(s.hidden)
        // nothing fills up behind the app's back
        s.putFull(43, "f43")
        assertNull(s.full(43))

        s.show()
        assertFalse(s.hidden)
        s.look(10); s.putFull(10, "f10")
        assertEquals("then it sharpens", "f10", s.best(10))
    }

    @Test fun trimKeepsHalvesAlreadyLoaded() {
        val s = store()
        for (i in 0 until 72) s.putHalf(i, "h$i")
        s.putFull(43, "f43")
        s.trim(FrameTrim.FULL)
        assertEquals(72, s.halfHeld().size)
    }

    @Test fun hardTrimDropsHalvesExceptWhatTheReturnShows() {
        val s = store()
        for (i in 0 until 72) s.putHalf(i, "h$i")
        s.look(10); s.putFull(10, "f10")
        s.trim(FrameTrim.ALL)
        assertEquals(frameWindow(72, 43) + 10, s.halfHeld())
        assertTrue(s.fullHeld().isEmpty())
        assertEquals("h10", s.best(10))
        assertTrue(s.hidden)
    }

    @Test fun pressureOnScreenLeavesTheCarOnScreenAlone() {
        val s = store()
        for (i in frameWindow(72, 43)) s.putFull(i, "f$i")
        s.look(10); s.putFull(10, "f10")
        for (i in 0 until 72) s.putHalf(i, "h$i")
        s.trim(FrameTrim.HALVES)
        assertFalse(s.hidden)
        assertEquals(frameWindow(72, 43) + 10, s.fullHeld())
        assertEquals("f10", s.best(10))
        assertEquals(frameWindow(72, 43) + 10, s.halfHeld())
    }

    @Test fun eachLevelHasItsPlan() {
        assertEquals(FrameTrim.NONE, frameTrimFor(TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(FrameTrim.HALVES, frameTrimFor(TRIM_MEMORY_RUNNING_LOW))
        assertEquals(FrameTrim.HALVES, frameTrimFor(TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(FrameTrim.FULL, frameTrimFor(TRIM_MEMORY_UI_HIDDEN))
        assertEquals(FrameTrim.FULL, frameTrimFor(TRIM_MEMORY_BACKGROUND))
        assertEquals(FrameTrim.ALL, frameTrimFor(TRIM_MEMORY_MODERATE))
        assertEquals(FrameTrim.ALL, frameTrimFor(TRIM_MEMORY_COMPLETE))
    }

    @Test fun aModelWithoutFramesIsHarmless() {
        val s = store(count = 0, drive = 0)
        s.look(3)
        assertNull(s.best(3))
        assertFalse(s.trim(FrameTrim.NONE))
        s.trim(FrameTrim.FULL)
    }

    // ---- the painted frames on disk

    private val blue = CarPaint("blue", "Синий WR", 0x1D4ED8, hue = 222f)

    @Test fun tintKeyChangesWithPaintModelAndBuild() {
        val k = carTintKey("car", blue, 40)
        assertEquals(k, carTintKey("car", blue.copy(), 40))
        assertNotEquals(k, carTintKey("car_sh", blue, 40))
        assertNotEquals(k, carTintKey("car", blue.copy(id = "red", hue = 356f), 40))
        // the same id with another definition (a new app version retuned the colour) and another build
        assertNotEquals(k, carTintKey("car", blue.copy(hue = 225f), 40))
        assertNotEquals(k, carTintKey("car", blue, 41))
        // a safe folder name for any paint, custom:1d4ed8 included
        assertTrue(carTintKey("car", blue.copy(id = "custom:1d4ed8"), 40).matches(Regex("[A-Za-z0-9._-]+")))
    }

    @Test fun tintCacheGivesBackWhatWasWrittenAndAnotherPaintDoesNot() = withDir { dir ->
        val c = TintCache(dir, 1000)
        assertNull(c.file("a", "f_043"))
        c.write("a", "f_043") { it.write(png(20)) }
        assertEquals(20L, c.file("a", "f_043")?.length())
        assertNull(c.file("b", "f_043"))
    }

    @Test fun tintCacheRetainDropsOtherPaints() = withDir { dir ->
        val c = TintCache(dir, 1000)
        c.write("old", "f_043") { it.write(png(20)) }
        c.write("new", "f_043") { it.write(png(20)) }
        c.retain("new")
        assertNull(c.file("old", "f_043"))
        assertFalse(File(dir, "old").exists())
        assertEquals(20L, c.file("new", "f_043")?.length())
    }

    @Test fun tintCacheStaysWithinItsSizeDroppingTheOldest() = withDir { dir ->
        val c = TintCache(dir, 100)
        for (i in 0 until 5) {
            c.write("a", "f_$i") { it.write(png(30)) }
            File(File(dir, "a"), "f_$i.png").setLastModified(1_000_000L + i * 10_000L)
        }
        c.write("a", "f_5") { it.write(png(30)) }
        assertTrue(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 100)
        assertNull("the oldest went", c.file("a", "f_0"))
        assertEquals("the one just written stays", 30L, c.file("a", "f_5")?.length())
    }

    @Test fun aWriteCutOffByThePowerLeavesNoFrame() = withDir { dir ->
        val c = TintCache(dir, 1000)
        runCatching { c.write("a", "f_1") { it.write(1); error("the ignition went off") } }
        assertNull(c.file("a", "f_1"))
        assertTrue("no leftovers", File(dir, "a").listFiles().orEmpty().isEmpty())
    }

    @Test fun aTruncatedFrameOnDiskIsNotAFrame() = withDir { dir ->
        val c = TintCache(dir, 1000)
        c.write("a", "f_1") { it.write(png(100)) }
        val f = File(File(dir, "a"), "f_1.png")
        f.writeBytes(f.readBytes().copyOf(60)) // written by a version without the check, or cut short by the card
        assertNull(c.file("a", "f_1"))
        assertFalse("deleted", f.exists())
        c.write("a", "f_2") { it.write(ByteArray(3)) }
        assertNull("too short to be anything", c.file("a", "f_2"))
    }

    @Test fun aDiskFullDuringCompressLeavesNoFrame() = withDir { dir ->
        val c = TintCache(dir, 1000)
        val r = runCatching { c.write("a", "f_1") { it.write(1); carTintCompressed(false) } }
        assertTrue(r.isFailure)
        assertNull(c.file("a", "f_1"))
    }

    @Test fun aFrameThatCannotShrinkIsSimplyLetGo() {
        val s = FrameStore<String>(72, 43) { error("out of memory") }
        s.putFull(43, "f43")
        s.trim(FrameTrim.FULL)
        assertTrue(s.fullHeld().isEmpty())
        assertNull(s.best(43))
    }

    /** Bytes that end as a PNG does. */
    private fun png(n: Int) = ByteArray(n).also { b ->
        val end = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
        end.copyInto(b, n - end.size)
    }

    private fun withDir(body: (File) -> Unit) {
        val d = Files.createTempDirectory("tint").toFile()
        try { body(d) } finally { d.deleteRecursively() }
    }
}
