package kg.forester.sg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunTrailTest {
    @Test fun previousRunShowsItsLastEventsAndLastSignOfLife() {
        val text = (1..20).joinToString("\n") { "11-19 14:51:%02d.000 event $it".format(it) } + "\n"
        val now = 1_000_000_000L
        val s = RunTrail.previous(text, now - 34_000L, now)!!
        assertTrue(s.startsWith("the previous run: last sign of life "))
        assertTrue(s.contains("(34 s before this start)"))
        assertTrue(s.contains("… 5 earlier"))
        assertTrue(s.contains("event 20"))
        assertFalse(s.contains("event 5\n"))
        assertEquals(RunTrail.SHOWN + 2, s.lines().size)
    }

    @Test fun noPreviousRunNoLine() {
        assertNull(RunTrail.previous("", 0L, 10L))
        assertNull(RunTrail.previous("\n\n", 5L, 10L))
    }

    @Test fun stallToldAtFourSecondsOnceMoreAtTenThenWhenOver() {
        val c = StallClock()
        assertNull(c.check(3000, 0))
        assertEquals(Stall.Blocked(4000, true), c.check(4000, 0))
        assertNull(c.check(5000, 0))
        assertEquals(Stall.Blocked(10_000, false), c.check(10_000, 0))
        assertNull(c.check(15_000, 0))
        assertEquals(Stall.Over(16_000), c.check(17_000, 16_000))
        // a healthy main thread after it: nothing
        assertNull(c.check(18_000, 17_900))
        // a new stall is told about again
        assertEquals(Stall.Blocked(4100, true), c.check(22_000, 17_900))
    }

    @Test fun shortHiccupIsNoStall() {
        val c = StallClock()
        assertNull(c.check(3900, 0))
        assertNull(c.check(5000, 4900))
    }

    @Test fun stallTextHasTheMainThreadsFrames() {
        val stack = Array(30) { StackTraceElement("kg.forester.sg.service.Fyt", "module$it", "Fyt.kt", it) }
        val t = MainWatch.stallText(Stall.Blocked(4200, true), stack)
        assertTrue(t.startsWith("main thread blocked for 4.2 s"))
        assertTrue(t.contains("\n  at kg.forester.sg.service.Fyt.module0(Fyt.kt:0)"))
        assertTrue(t.contains("… 5 more"))
        assertEquals(1 + MainWatch.FRAMES + 1, t.lines().size)
        assertTrue(MainWatch.stallText(Stall.Blocked(10_000, false), stack).startsWith("main thread still blocked after 10.0 s"))
        assertEquals("main thread free again after 6.3 s", MainWatch.stallText(Stall.Over(6300), emptyArray()))
    }

    @Test fun anrTraceGivesTheMainThreadOnly() {
        val trace = """
            ----- pid 1234 at 2026-11-20 13:55:00 -----
            Cmd line: kg.forester.sg

            "main" prio=5 tid=1 Native
              | group="main" sCount=1
              native: #00 pc 000000000009aa34  /apex/com.android.runtime/lib64/bionic/libc.so (__ioctl+4)
              at android.os.BinderProxy.transactNative(Native method)
              at kg.forester.sg.service.Fyt.module(Fyt.kt:186)

            "Signal Catcher" daemon prio=5 tid=2 Runnable
        """.trimIndent()
        val main = ExitInfo.anrMain(trace, 40)
        assertTrue(main.first().startsWith("\"main\" prio=5"))
        assertTrue(main.last().contains("Fyt.module"))
        assertFalse(main.any { it.contains("Signal Catcher") })
        assertEquals(2, ExitInfo.anrMain(trace, 2).size)
        assertTrue(ExitInfo.anrMain("no threads here", 40).isEmpty())
    }

    @Test fun nativeTombstoneKeepsTheReadableClues() {
        val parts = listOf("\u0001\u0002", "SIGSEGV", "\u0000\u0003", "/system/lib64/libusbhost.so", "\u0000",
            "usb_request_wait", "\u0000", "/system/lib64/libusbhost.so", "\u0000", "abc", "\u0000", "kg.forester.sg.ssm.KklTransport")
        val bytes = parts.joinToString("").toByteArray(Charsets.ISO_8859_1)
        val l = ExitInfo.nativeLines(bytes, 25)
        assertEquals(listOf("SIGSEGV", "/system/lib64/libusbhost.so", "kg.forester.sg.ssm.KklTransport"), l)
        assertEquals(1, ExitInfo.nativeLines(bytes, 1).size)
    }

    @Test fun reasonsByName() {
        assertEquals("ANR", ExitInfo.reasonName(6))
        assertEquals("native crash", ExitInfo.reasonName(5))
        assertEquals("low memory", ExitInfo.reasonName(3))
        assertEquals("reason 99", ExitInfo.reasonName(99))
    }

    @Test fun startedInTheApplicationBeforeEverythingElse() {
        val app = File("src/main/java/kg/forester/sg/CarPulseApp.kt").readText()
        val crash = app.indexOf("CrashLog.install(this)")
        assertTrue(crash in 0 until app.indexOf("RunTrail.start(this"))
        assertTrue(app.indexOf("MainWatch.start()") < app.indexOf("Repo.init(this)"))
        assertTrue(app.contains("ExitInfo.logNew(this)"))
    }
}
