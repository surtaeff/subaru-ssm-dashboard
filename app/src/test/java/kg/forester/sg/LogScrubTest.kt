package kg.forester.sg

import kg.forester.sg.service.LogScrub
import kg.forester.sg.service.LogUpload
import kg.forester.sg.ssm.SsmTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

class LogScrubTest {
    private val vin = "JF1SG5LS59H012345"
    private val vinHex = vin.map { "%02X".format(it.code) }.joinToString(" ")

    private val raw = listOf(
        "12:00:00.001 VIN: $vin",
        "12:00:00.002 VIN (OBD 09 02): $vin",
        "12:00:00.003 << 014|0: 49 02 01 4A 46 31|1: 53 47 35 4C 53 35|2: 39 48 30 31 32 33|3: 34 35",
        "12:00:00.004 K RX 87 F1 11 49 02 01 4A 46 31 53 47 35 4C 53 35 39 48 30 31 32 33 34 35 3B",
        "12:00:00.005 RX 80 F0 10 E8 $vinHex 5C",
        "12:00:00.006 === Bluetooth OBDII 02:00:00:00:00:01, шина K",
        "12:00:00.007 TPMS unknown BR-TPMS 38:89:00:00:A1:B2: 0100:80AA",
        "12:00:00.008 === Wi-Fi 192.168.0.10:35000, шина K",
        "12:00:00.009 gps fix 42.874612, 74.569803 age 40 ms",
        "12:00:00.010 voice heard: включи музыку и поехали домой",
        "12:00:00.011 player: ERROR_CODE_IO_FILE_NOT_FOUND /storage/emulated/0/Music/Artist - Song.mp3",
        "12:00:00.012 2GIS manoeuvre icon «turn_left»: Поверните налево на улицу Ленина",
        "12:00:00.013 bt: broadcast com.android.music.metachanged track=Last Christmas,artist=Wham,id=77",
        "12:00:00.014 music index: java.io.IOException: /sdcard/Music/Секрет.mp3",
        "12:00:00.015 ECU init: SSM 0A1B2C ROM 7A4C700A caps 5D 3F 00 12",
        "12:00:00.016 TX 80 10 F0 05 A8 00 00 00 08 00 5D",
        "12:00:00.018 screen: esp32s3 480x480 fw 12 id 3C8A1F5B9D20",
        "12:00:00.017 Multitronics: Mult-II, «1.2», build 83",
    ).joinToString("\n")

    private val header = "Машина: «Мой Форестер Вася», коробка AT; адаптер op2, шина K; найден: —; связь: ok; ROM 7A4C700A"

    @Test fun scrubRemovesPrivate() {
        val out = LogScrub.scrub("$header\n\n$raw", setOf("Мой Форестер Вася"))
        for (secret in listOf(
            vin, vinHex, "4A 46 31 53 47 35", "02:00:00:00:00:01", "38:89:00:00:A1:B2", "192.168.0.10",
            "42.874612", "74.569803", "включи музыку", "Вася", "Last Christmas", "Wham", "Артист", "Artist - Song", "Секрет",
            "Ленина", "49 02 01", "3C8A1F5B9D20",
        )) assertFalse("left in: $secret\n$out", out.contains(secret))
        // what the owner needs stays
        for (kept in listOf(
            "ROM 7A4C700A", "caps 5D 3F 00 12", "SSM 0A1B2C", "TX 80 10 F0 05 A8 00 00 00 08 00 5D", "build 83",
            "ERROR_CODE_IO_FILE_NOT_FOUND", "turn_left", "com.android.music.metachanged", "track=", "VIN", "адаптер op2",
        )) assertTrue("lost: $kept\n$out", out.contains(kept))
        assertEquals(out, LogScrub.scrub(out, setOf("Мой Форестер Вася")))
    }

    @Test fun scrubKnownValuesAnywhere() {
        val out = LogScrub.scrub("link to 99:88:77:66:55:44 ok, vin ${vin.lowercase()} car Sam", setOf(vin, "Sam", "99:88:77:66:55:44"))
        assertFalse(out.contains("99:88"))
        assertFalse(out.lowercase().contains(vin.lowercase()))
        assertFalse(out.contains("Sam"))
    }

    // ---- the real path: what is previewed is what is sent, scrubbed

    private class Fake(val ok: Boolean = true) : LogUpload.Sender {
        var url: String? = null; var id: String? = null; var body: String? = null; var calls = 0
        override fun post(url: String, id: String, key: String, payload: String): LogUpload.Result {
            calls++; this.url = url; this.id = id; this.body = payload
            return if (ok) LogUpload.Result(true) else LogUpload.Result(false, "fake")
        }
    }

    @Test fun previewEqualsSentAndBothAreScrubbed() {
        SsmTrace.clear()
        SsmTrace.log("VIN (OBD 09 02): $vin")
        SsmTrace.log("=== Bluetooth OBDII 02:00:00:00:00:01, шина K")
        SsmTrace.log("ECU init: SSM 0A1B2C ROM 7A4C700A caps 5D 3F")
        val p = LogUpload.prepare("CarPulse test", header, SsmTrace.lines(), setOf("Мой Форестер Вася"))
        assertFalse(p.text.contains(vin)); assertFalse(p.text.contains("02:00:00:00:00:01")); assertFalse(p.text.contains("Вася"))
        assertTrue(p.text.contains("ROM 7A4C700A"))
        val f = Fake()
        assertTrue(LogUpload.send(f, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", p).ok)
        assertEquals("ABCD2345", f.id)
        val sent = GZIPInputStream(ByteArrayInputStream(java.util.Base64.getDecoder().decode(f.body!!))).readBytes().toString(Charsets.UTF_8)
        assertEquals(p.text, sent)
        SsmTrace.clear()
    }

    @Test fun bodyFitsTheLimit() {
        val big = (0 until 60_000).joinToString("\n") { "12:00:00.000 RX ${"%08X".format(it * 2654435761L)} noise noise noise" }
        val p = LogUpload.prepare("sys", "head", big, emptySet())
        assertTrue(p.payload.length <= LogUpload.MAX_BODY)
        assertTrue(p.text.contains("sys")); assertTrue(p.text.contains("head"))
    }

    @Test fun failedSendIsKeptAsOneFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "carpulse-pending-test").apply { deleteRecursively(); mkdirs() }
        val p = LogUpload.prepare("s", "h", "12:00:00.000 hello", emptySet())
        val f = Fake(ok = false)
        // the upload is off: a failed press of the button leaves nothing behind
        assertFalse(LogUpload.sendOrKeep(f, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", p, dir, keep = false).ok)
        assertEquals(0, dir.listFiles()!!.size)
        // on: one file, replaced by the next failure
        assertFalse(LogUpload.sendOrKeep(f, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", p, dir, keep = true).ok)
        assertEquals(1, dir.listFiles()!!.size)
        LogUpload.sendOrKeep(f, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", LogUpload.prepare("s", "h", "12:00:01.000 hello2", emptySet()), dir, keep = true)
        assertEquals(1, dir.listFiles()!!.size)
        val ok = Fake()
        assertTrue(LogUpload.retryPending(ok, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", dir).ok)
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(1, ok.calls)
        assertFalse(LogUpload.retryPending(ok, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", dir).ok)
        // turning the switch off deletes what waits
        LogUpload.sendOrKeep(f, "https://example.invalid/x", "ABCD2345", "00112233445566778899aabbccddeeff", p, dir, keep = true)
        LogUpload.clearPending(dir)
        assertFalse(LogUpload.hasPending(dir))
        dir.deleteRecursively()
    }

    // ---- when it may go

    @Test fun uploadOffByDefault() {
        val s = LogUpload.State()
        assertFalse(s.enabled)
        assertEquals("", s.endpoint)
        assertFalse(LogUpload.canAuto(s, 0L))
        assertFalse(LogUpload.canAuto(LogUpload.State(enabled = true), 0L))
        assertFalse(LogUpload.canAuto(LogUpload.State(endpoint = "https://example.invalid/x"), 0L))
        assertTrue(LogUpload.canAuto(LogUpload.State(enabled = true, endpoint = "https://example.invalid/x"), 0L))
        // only https
        assertNull(LogUpload.endpointOf("http://example.invalid/x"))
        assertNull(LogUpload.endpointOf(""))
        assertEquals("https://example.invalid/x", LogUpload.endpointOf(" https://example.invalid/x "))
        // wiring: the stored setting starts from the same default, and nothing is sent without the endpoint of latest.json
        val settings = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertTrue(settings.contains("getBoolean(\"logUpload\", LogUpload.DEFAULT_ENABLED)"))
        assertFalse(LogUpload.DEFAULT_ENABLED)
        val all = File("src/main/java/kg/forester/sg").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("no address in the code", Regex("script\\.google(usercontent)?\\.com/macros|workers\\.dev|functions\\.yandexcloud").containsMatchIn(all))
    }

    @Test fun rateLimited() {
        val on = LogUpload.State(enabled = true, endpoint = "https://example.invalid/x")
        val h = 3_600_000L
        assertTrue(LogUpload.canAuto(on, 0L))
        val one = on.copy(history = listOf(0L))
        assertFalse(LogUpload.canAuto(one, 30 * 60_000L))
        assertTrue(LogUpload.canAuto(one, h + 1))
        // five in a day, an hour apart, then no more until the first is a day old
        val five = on.copy(history = (0 until 5).map { it * h + 1 })
        assertFalse(LogUpload.canAuto(five, 6 * h))
        assertFalse(LogUpload.canAuto(five, 23 * h))
        assertTrue(LogUpload.canAuto(five, 24 * h + 2))
        // the button always works
        assertTrue(LogUpload.canManual("https://example.invalid/x"))
        assertFalse(LogUpload.canManual(""))
        assertFalse(LogUpload.canManual("http://example.invalid/x"))
        // history keeps a day and is stored as text
        assertEquals(listOf(1L, 2L), LogUpload.parseHistory("1,2"))
        assertEquals(emptyList<Long>(), LogUpload.parseHistory(""))
        assertEquals(listOf(10 * h, 30 * h), LogUpload.record(listOf(0L, 10 * h), 30 * h))
    }
}
