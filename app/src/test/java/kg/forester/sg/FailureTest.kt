package kg.forester.sg

import kg.forester.sg.data.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FailureTest {
    @Test fun noNetwork() {
        for (t in listOf(
            UnknownHostException("Unable to resolve host \"github.com\": No address associated with hostname"),
            ConnectException("failed to connect to github.com"), NoRouteToHostException("No route to host"),
            SocketException("Network is unreachable"), IOException("x", UnknownHostException("github.com")),
        )) {
            assertEquals(Failure.Kind.NO_NETWORK, Failure.kind(t))
            assertEquals("Нет интернета", Failure.text(t))
        }
    }

    @Test fun timeout() {
        assertEquals(Failure.Kind.TIMEOUT, Failure.kind(SocketTimeoutException("timeout")))
        assertEquals("Сервер не отвечает", Failure.text(SocketTimeoutException("failed to connect after 10000ms")))
    }

    @Test fun noSpace() {
        val t = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(Failure.Kind.NO_SPACE, Failure.kind(t))
        assertEquals("Мало места на устройстве", Failure.text(t))
    }

    @Test fun serverRefused() {
        for (t in listOf(IllegalStateException("HTTP 404"), IllegalStateException("HTTP 503"),
            java.io.FileNotFoundException("https://github.com/a/b.bin"))) {
            assertEquals(Failure.Kind.REFUSED, Failure.kind(t))
            assertEquals("Сервер отказал, попробуйте позже", Failure.text(t))
        }
    }

    @Test fun anOpenFileThatIsMissingIsNoServerRefusal() {
        val t = java.io.FileNotFoundException("/data/user/0/kg.forester.sg/files/a.csv: open failed: ENOENT (No such file or directory)")
        assertEquals(Failure.Kind.OTHER, Failure.kind(t))
    }

    @Test fun noPermission() {
        assertEquals("Нет разрешения", Failure.text(SecurityException("Permission Denial: reading")))
    }

    @Test fun ownRussianTextStays() {
        assertEquals("разрешите доступ к памяти и нажмите ещё раз", Failure.text(IllegalStateException("разрешите доступ к памяти и нажмите ещё раз")))
    }

    @Test fun nothingRawGoesThrough() {
        val other = Failure.text(NullPointerException("Attempt to invoke virtual method 'int java.lang.String.length()'"))
        assertEquals("Не получилось", other)
        assertEquals("Не прочиталось", Failure.text(RuntimeException("java.io.EOFException"), "Не прочиталось"))
        assertEquals("Не получилось", Failure.text(RuntimeException()))
    }

    @Test fun aLongChainEnds() {
        val a = RuntimeException("a"); val b = RuntimeException("b", a); a.initCause(b)
        assertEquals(Failure.Kind.OTHER, Failure.kind(a))
    }

    @Test fun whatTheVoiceSaysIsShortAndHuman() {
        val t = Failure.text(UnknownHostException("github.com"))
        assertFalse(t.contains("github"))
        assertEquals("Нет интернета, голос скачается, когда он появится",
            Failure.waitingText(UnknownHostException("github.com"), "голос"))
        assertEquals("Нет интернета, модель скачается, когда он появится",
            Failure.waitingText(UnknownHostException("github.com"), "модель"))
        assertEquals("Сервер не отвечает, голос скачается позже", Failure.waitingText(SocketTimeoutException(), "голос"))
        assertEquals("Мало места на устройстве", Failure.waitingText(IOException("ENOSPC"), "голос"))
    }

    @Test fun retriesOnlyWhatPassesByItself() {
        assertTrue(Failure.passes(UnknownHostException("x")))
        assertTrue(Failure.passes(SocketTimeoutException()))
        assertTrue(Failure.passes(IllegalStateException("HTTP 503")))
        assertFalse(Failure.passes(IOException("ENOSPC")))
        assertFalse(Failure.passes(IllegalStateException("HTTP 404")))
    }

    @Test fun theVoiceComesWhenTheNetworkReturns() {
        val r = kg.forester.sg.service.SpeechRetry(15_000L)
        assertFalse("switched off", r.due(on = false, failed = true, busy = false, nowMs = 1000L))
        assertFalse("not failed", r.due(on = true, failed = false, busy = false, nowMs = 1000L))
        assertFalse("already going", r.due(on = true, failed = true, busy = true, nowMs = 1000L))
        assertTrue(r.due(on = true, failed = true, busy = false, nowMs = 1000L))
        assertFalse("a flapping network", r.due(on = true, failed = true, busy = false, nowMs = 2000L))
        assertTrue(r.due(on = true, failed = true, busy = false, nowMs = 17000L))
    }

    /** Raw exception text must not reach a screen: every such place goes through the mapper. */
    @Test fun noRawExceptionTextInWhatIsShown() {
        val root = listOf("src/main/java/kg/forester/sg", "app/src/main/java/kg/forester/sg").map(::File).first { it.isDirectory }
        val raw = Regex("""\$\{(it|e|x|err|t)\.(message|localizedMessage)|(it|e|x)\.message \?:|= (it|e|x)\.message\b|\((it|e|x)\.message \?:""")
        val hits = root.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (raw.containsMatchIn(l) && !l.contains("Log.") && !l.contains("SsmTrace")) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }.toList()
        // RomRaiderXml.BadFile: a message written here, for people
        val own = hits.filterNot { it.startsWith("EcuDefs.kt") && (it.contains("Файл не подходит") || it.contains("Loaded(e, null, x.message)")) }
        assertEquals(emptyList<String>(), own)
    }
}
