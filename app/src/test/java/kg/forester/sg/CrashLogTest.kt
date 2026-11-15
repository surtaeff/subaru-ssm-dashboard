package kg.forester.sg

import kg.forester.sg.ssm.SsmTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashLogTest {
    @Test fun traceWithCauseAndThread() {
        val t = IllegalStateException("outer", RuntimeException("inner"))
        val text = CrashLog.text("ecu-poll", t, "1.0-beta5", 0L)
        assertTrue(text.startsWith("CRASH "))
        assertTrue(text.contains("CarPulse 1.0-beta5, thread \"ecu-poll\""))
        assertTrue(text.contains("IllegalStateException: outer"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: inner"))
        assertTrue(text.contains("CrashLogTest"))
    }

    @Test fun deepTraceIsCut() {
        fun deep(n: Int): Nothing = if (n == 0) throw StackOverflowError("deep") else deep(n - 1)
        val t = runCatching { deep(400) }.exceptionOrNull()!!
        val text = CrashLog.text("main", t, "?", 0L)
        assertTrue(text.lines().size <= 122)
        assertTrue(text.contains("lines in all"))
    }

    @Test fun savedForTheNextStartOnce() {
        val dir = File.createTempFile("crash", "").apply { delete(); mkdirs() }
        assertNull(CrashLog.restore(dir))
        CrashLog.save(dir, "CRASH x")
        assertEquals("CRASH x", CrashLog.restore(dir))
        assertNull(CrashLog.restore(dir))
        dir.deleteRecursively()
    }

    @Test fun handlerKeepsTheTraceAndChains() {
        val dir = File.createTempFile("crash", "").apply { delete(); mkdirs() }
        var chained: Throwable? = null
        val prev = Thread.UncaughtExceptionHandler { _, e -> chained = e }
        val boom = IllegalArgumentException("boom in the service")
        CrashLog.Handler(dir, "1.0", prev).uncaughtException(Thread.currentThread(), boom)
        assertSame(boom, chained)
        assertTrue(SsmTrace.lines().contains("boom in the service"))
        assertTrue(CrashLog.restore(dir)!!.contains("boom in the service"))
        dir.deleteRecursively()
    }

    @Test fun installedInTheApplication() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".CarPulseApp\""))
        val app = File("src/main/java/kg/forester/sg/CarPulseApp.kt").readText()
        assertTrue(app.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(app.contains("prev.uncaughtException(th, e)"))
        assertTrue(app.contains("keep = true"))
    }
}
