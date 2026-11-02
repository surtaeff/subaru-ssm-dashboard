package kg.forester.sg

import kg.forester.sg.service.FaultWatch
import kg.forester.sg.service.LogScrub
import kg.forester.sg.service.LogUpload
import kg.forester.sg.ssm.SsmTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class LogUploadTest {
    private val src = File("src/main/java/kg/forester/sg")
    private fun text(path: String) = File(src, path).readText()
    private val url = "https://example.invalid/x"

    private class Fake(val results: List<LogUpload.Result>) : LogUpload.Sender {
        var calls = 0
        override fun post(url: String, id: String, key: String, payload: String): LogUpload.Result {
            val r = results.getOrElse(calls) { results.last() }; calls++; return r
        }
    }
    private class Boom : LogUpload.Sender {
        override fun post(url: String, id: String, key: String, payload: String): LogUpload.Result = throw OutOfMemoryError("test")
    }

    private fun tmp() = File(System.getProperty("java.io.tmpdir"), "carpulse-up-" + System.nanoTime()).apply { mkdirs() }
    private val on = LogUpload.State(enabled = true, endpoint = url)
    private val prep = { LogUpload.prepare("s", "h", "12:00:00.000 x", emptySet()) }

    // ---- C2: the reply is read, and only "ok" counts

    /** A request as the little server sees it. */
    private class Req(val method: String, val path: String, val body: String)

    /** Answers: (code, headers, body). One connection a request, on 127.0.0.1 only. */
    private class Ans(val code: Int, val body: String = "", val location: String? = null)

    private fun withServer(post: (Req) -> Ans, get: (Req) -> Ans, run: (String) -> Unit) {
        val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    val inp = c.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val ch = inp.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) sb.append(ch.toChar()) }; return sb.toString() }
                    val first = line().split(" ")
                    var len = 0
                    while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(":").trim().toInt() }
                    val body = String(inp.readNBytes(len), Charsets.US_ASCII)
                    val req = Req(first[0], first[1].substringBefore('?'), body)
                    val a = if (req.method == "POST") post(req) else get(req)
                    val b = a.body.toByteArray()
                    val head = "HTTP/1.1 ${a.code} X\r\n" + (a.location?.let { "Location: $it\r\n" } ?: "") + "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
                    c.getOutputStream().apply { write(head.toByteArray()); write(b); flush() }
                }
                runCatching { c.close() }
            }
        }
        t.isDaemon = true; t.start()
        try { run("http://127.0.0.1:${ss.localPort}/exec") } finally { ss.close() }
    }

    private fun answerOf(replyBody: String, code: Int = 200): LogUpload.Result {
        var posted: String? = null; var posts = 0
        var out: LogUpload.Result? = null
        withServer(
            { e -> posts++; posted = e.body; Ans(302, location = "/result") },
            { _ -> Ans(code, replyBody) },
        ) { base -> out = LogUpload.Http.post(base, "ABCD2345", "00112233445566778899aabbccddeeff", "QUJD") }
        assertEquals(1, posts)
        assertEquals("QUJD", posted)
        return out!!
    }

    @Test fun httpOkOnlyOnExactlyOk() {
        assertTrue(answerOf("ok").ok)
        assertTrue(answerOf("ok\n").ok)
        assertFalse(answerOf("too many").ok)
        assertTrue(answerOf("too many").reason.contains("too many"))
        assertFalse(answerOf("bad id").ok)
        assertFalse(answerOf("error").ok)
        assertFalse(answerOf("<html><title>Sign in - Google Accounts</title>").ok)
        assertFalse(answerOf("ok", code = 500).ok)
    }

    @Test fun httpFailures() {
        // the receiver itself fails
        var r: LogUpload.Result? = null
        withServer({ _ -> Ans(500, "x") }, { _ -> Ans(200, "ok") }) { base -> r = LogUpload.Http.post(base, "ABCD2345", "00112233445566778899aabbccddeeff", "QUJD") }
        assertFalse(r!!.ok); assertTrue(r!!.reason.contains("500"))
        // a 302 straight to a login page of another kind of address: not followed to plain http from https is checked by protocol
        withServer({ _ -> Ans(302, location = "https://127.0.0.1:1/login") }, { _ -> Ans(200, "ok") }) { base ->
            r = LogUpload.Http.post(base, "ABCD2345", "00112233445566778899aabbccddeeff", "QUJD")
        }
        assertFalse(r!!.ok)
        // nobody there
        val failed = runCatching { LogUpload.Http.post("http://127.0.0.1:1/exec", "ABCD2345", "00112233445566778899aabbccddeeff", "QUJD") }
        assertTrue(failed.isFailure)
        // and through send() that becomes a reason, not a crash
        val s = object : LogUpload.Sender { override fun post(url: String, id: String, key: String, payload: String) = LogUpload.Http.post("http://127.0.0.1:1/exec", id, key, payload) }
        val res = LogUpload.send(s, url, "ABCD2345", "00112233445566778899aabbccddeeff", prep())
        assertFalse(res.ok); assertTrue(res.reason.isNotEmpty())
    }

    @Test fun refusedIsKeptNotDeleted() {
        val dir = tmp()
        val refuse = Fake(listOf(LogUpload.Result(false, "приём ответил «too many»")))
        assertFalse(LogUpload.sendOrKeep(refuse, url, "ABCD2345", "00112233445566778899aabbccddeeff", prep(), dir, keep = true).ok)
        assertTrue(LogUpload.hasPending(dir))
        assertFalse(LogUpload.retryPending(refuse, url, "ABCD2345", "00112233445566778899aabbccddeeff", dir).ok)
        assertTrue("the kept file is not deleted by a refusal", LogUpload.hasPending(dir))
        assertTrue(LogUpload.retryPending(Fake(listOf(LogUpload.Result(true))), url, "ABCD2345", "00112233445566778899aabbccddeeff", dir).ok)
        assertFalse(LogUpload.hasPending(dir))
        dir.deleteRecursively()
    }

    // ---- I7 / T1: the automatic send as the app runs it

    @Test fun autoNeverPostsTwiceInOneAttempt() {
        val dir = tmp()
        val bad = Fake(listOf(LogUpload.Result(false, "x")))
        var hist = emptyList<Long>()
        // nothing kept: one POST, kept on failure
        assertEquals(LogUpload.Auto.FAILED, LogUpload.runAuto(bad, on, 1_000L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, { hist = it }, prep))
        assertEquals(1, bad.calls); assertEquals(listOf(1_000L), hist)
        // something kept: that one is the only POST, even when it fails
        val bad2 = Fake(listOf(LogUpload.Result(false, "x")))
        assertEquals(LogUpload.Auto.FAILED, LogUpload.runAuto(bad2, on, 10_000_000L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, { hist = it }, prep))
        assertEquals(1, bad2.calls)
        val good = Fake(listOf(LogUpload.Result(true)))
        assertEquals(LogUpload.Auto.RETRIED, LogUpload.runAuto(good, on, 20_000_000L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, { hist = it }, prep))
        assertEquals(1, good.calls)
        assertFalse(LogUpload.hasPending(dir))
        // at the start with nothing kept: nothing to do
        val none = Fake(listOf(LogUpload.Result(true)))
        assertEquals(LogUpload.Auto.NOTHING, LogUpload.runAuto(none, on, 30_000_000L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, true, { hist = it }, prep))
        assertEquals(0, none.calls)
        dir.deleteRecursively()
    }

    @Test fun autoOffAndLimitsMeanNoPost() {
        val dir = tmp()
        val f = Fake(listOf(LogUpload.Result(true)))
        assertEquals(LogUpload.Auto.OFF, LogUpload.runAuto(f, LogUpload.State(), 0L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, {}, prep))
        assertEquals(LogUpload.Auto.OFF, LogUpload.runAuto(f, on.copy(history = listOf(0L)), 60_000L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, {}, prep))
        assertEquals(LogUpload.Auto.OFF, LogUpload.runAuto(f, on.copy(endpoint = ""), 0L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, {}, prep))
        assertEquals(0, f.calls)
        dir.deleteRecursively()
    }

    @Test fun autoSurvivesAnything() {
        var dir = tmp()
        assertEquals(LogUpload.Auto.FAILED, LogUpload.runAuto(Boom(), on, 1L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, {}, prep))
        dir = tmp()
        assertEquals(LogUpload.Auto.FAILED, LogUpload.runAuto(Fake(listOf(LogUpload.Result(true))), on, 1L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, {}) { throw OutOfMemoryError() })
        dir = tmp()
        assertEquals(LogUpload.Auto.FAILED, LogUpload.runAuto(Fake(listOf(LogUpload.Result(true))), on, 1L, "ABCD2345", "00112233445566778899aabbccddeeff", dir, false, { throw IllegalStateException() }, prep))
        dir.deleteRecursively()
        // the thread of the real one catches Throwable too
        assertTrue(text("service/LogUpload.kt").contains("catch (t: Throwable)"))
    }

    // ---- I4: a fault, not a key-off

    @Test fun faultWatch() {
        val w = FaultWatch()
        w.newConnection()
        // key off: rpm falls to 0 and the data goes on for a while, then stops
        for (t in 0..10) w.sample(if (t < 2) 800 else 0, t * 500L)
        assertFalse(w.looksLikeFault())
        // the engine dies at once: above 500 up to the last sample
        w.newConnection()
        for (t in 0..10) w.sample(2400, t * 500L)
        assertTrue(w.looksLikeFault())
        // 30 s later still down on a running unit: once
        assertFalse(w.confirm(linkUp = true, waitedMs = 30_000))
        assertFalse(w.confirm(linkUp = false, waitedMs = 5_000))
        assertFalse("slept through", w.confirm(linkUp = false, waitedMs = 20 * 60_000))
        assertTrue(w.confirm(linkUp = false, waitedMs = 30_100))
        assertFalse(w.confirm(linkUp = false, waitedMs = 30_100))
        assertFalse(w.looksLikeFault())
        w.newConnection()
        w.sample(3000, 1L)
        assertTrue(w.looksLikeFault())
        // wiring
        val svc = text("service/EcuService.kt")
        assertTrue(svc.contains("faultWatch.sample(snap.rpm, now)"))
        assertTrue(svc.contains("faultWatch.looksLikeFault()"))
        assertTrue(svc.contains("faultWatch.confirm("))
        assertTrue(svc.contains("faultWatch.newConnection()"))
        assertFalse("not on every silence", svc.contains("LogUpload.auto(this, \"ECU silent\")"))
        assertTrue(svc.contains("LogUpload.auto(this, \"start\", onlyPending = true)"))
    }

    // ---- C1: the VIN read does not reach the log

    @Test fun mutedReadLeavesNothing() {
        SsmTrace.clear()
        SsmTrace.polling = true
        SsmTrace.log("before")
        val r = SsmTrace.muted {
            SsmTrace.log("CAN RX 7E8", byteArrayOf(0x10, 0x12, 0xE8.toByte(), 0x4A, 0x46))
            SsmTrace.log("<< E84A"); SsmTrace.log("RX 80 F0 4A 46 31", keep = true)
            7
        }
        assertEquals(7, r)
        runCatching { SsmTrace.muted { error("read failed") } }
        SsmTrace.log("VIN read ok, 17 bytes")
        SsmTrace.polling = false
        val all = SsmTrace.lines()
        assertTrue(all.contains("before")); assertTrue(all.contains("VIN read ok, 17 bytes"))
        assertFalse(all.contains("7E8")); assertFalse(all.contains("E84A")); assertFalse(all.contains("4A 46"))
        SsmTrace.clear()
    }

    @Test fun everyVinReadIsUnderTheMute() {
        val svc = text("service/EcuService.kt").lines()
        val reads = svc.filter { it.contains("IntArray(17)") || it.contains("byteArrayOf(0x09, 0x02)") }
        assertEquals(2, reads.size)
        reads.forEach { assertTrue(it, it.contains("SsmTrace.muted")) }
        val obd = text("ssm/Obd2.kt").lines()
        val info02 = obd.filter { it.contains("info(0x02)") }
        assertEquals(1, info02.size); assertTrue(info02[0].contains("SsmTrace.muted"))
        // the OBD VIN is only ever asked through that one
        assertEquals(0, obd.count { it.contains("0x09, 0x02") })
        // and the lines that say what happened carry no VIN
        assertFalse(text("service/EcuService.kt").contains("VIN: \${"))
        assertFalse(text("service/EcuService.kt").contains("VIN (OBD 09 02): \${"))
    }

    // ---- I1, I2: the real formats

    @Test fun knownValuesIncludeTheBluetoothName() {
        val k = LogUpload.knownFrom("JF1SG5LS59H012345", "Мой Форестер", "", "OBDII-Vasya", "02:00:00:00:00:01", "192.168.0.10:35000", "{\"38:89:00:00:A1:B2\":\"FL\"}", listOf("38:89:00:00:A1:B3"))
        assertTrue("OBDII-Vasya" in k && "192.168.0.10" in k && "38:89:00:00:A1:B2" in k && "38:89:00:00:A1:B3" in k && "Мой Форестер" in k)
        val out = LogScrub.scrub("12:00:00.001 === Bluetooth OBDII-Vasya 02:00:00:00:00:01, шина K\nсвязь: Подключение к OBDII-Vasya…", k)
        assertFalse(out.contains("Vasya"))
        assertTrue(text("service/LogUpload.kt").contains("s.btName"))
    }

    @Test fun realFormatsThroughThePollRing() {
        SsmTrace.clear()
        SsmTrace.polling = true
        // the format strings of LocalMusic.kt:274, DgisDashboard.kt:203-204, and a path with spaces
        SsmTrace.log("player: ERROR_CODE_DECODING_FAILED /storage/emulated/0/Music/Artist - Song.mp3")
        SsmTrace.log("2GIS manoeuvre icon «turn_left»: Поверните на улицу Ленина")
        SsmTrace.log("2GIS barrier «camera»: Камера на улице Советская")
        SsmTrace.log("stream: java.io.FileNotFoundException: /storage/emulated/0/Music/Artist - Song.mp3 (No such file)")
        SsmTrace.keepPollTail(12)
        SsmTrace.polling = false
        val out = LogScrub.scrub(SsmTrace.lines())
        for (secret in listOf("Ленина", "Советская", "Song", "Artist", "storage")) assertFalse("left: $secret\n$out", out.contains(secret))
        for (kept in listOf("ERROR_CODE_DECODING_FAILED", "turn_left", "camera")) assertTrue("lost: $kept\n$out", out.contains(kept))
        SsmTrace.clear()
    }

    @Test fun coordinatesInEveryShape() {
        for (line in listOf(
            "fix lat 42.874612 lon 74.569803", "{\"lat\":42.874612,\"lon\":74.569803}", "pos 42.874612 74.569803 age 3",
            "pos 42,874612; 74,569803", "latitude=42.874612, longitude=74.569803", "pos 42.874612,74.569803",
        )) {
            val out = LogScrub.scrub("12:00:00.001 $line")
            assertFalse(line + " -> " + out, Regex("\\d{2}[.,]\\d{4}").containsMatchIn(out))
        }
    }

    @Test fun reportsCarTheIdAndKeepWhatTheOwnerNeeds() {
        val h = LogUpload.header("ABCD2345", "Мой", "AT", "op2", "K", "—", "ok", "7A4C700A")
        assertTrue(h.startsWith("ID устройства: ABCD2345"))
        val out = LogScrub.scrub("$h\n12:00:00.001 ECU init: SSM 0A1B2C ROM 7A4C700A caps 5D 3F 00 12\n12:00:00.002 RX 80 F0 10 E8 A0 00 5D", setOf("Мой"))
        assertTrue(out.contains("ABCD2345")); assertTrue(out.contains("ROM 7A4C700A")); assertTrue(out.contains("caps 5D 3F 00 12")); assertTrue(out.contains("RX 80 F0 10 E8 A0 00 5D"))
    }

    // ---- T1: wiring

    @Test fun manualShareAndSaveGoThroughTheScrub() {
        val ui = text("ui/CarSetup.kt")
        assertTrue(ui.contains("LogUpload.prepareNow(ctx).text"))
        assertTrue(ui.contains("SsmTrace.shareIntent(ctx, text)"))
        assertTrue(ui.contains("SsmTrace.saveToDownloads(ctx, text)"))
        assertFalse(ui.contains("header()"))
        val tr = text("ssm/SsmTrace.kt")
        assertFalse("no unscrubbed report builder left", tr.contains("private fun report("))
        val lu = text("service/LogUpload.kt")
        assertTrue(lu.contains("fun prepareNow(ctx: Context): Prepared = prepare(SsmTrace.sysLine(ctx), headerNow(ctx), SsmTrace.lines(), known() + DeviceId.key(ctx))"))
        assertTrue(lu.contains("DeviceId.get(ctx)"))
    }

    @Test fun updaterReadsTheSameFile() {
        val up = text("data/Updater.kt")
        assertEquals(2, Regex("URL\\(latestJsonUrl\\(\\)\\)").findAll(up).count())
        assertFalse(up.contains("URL(\"https://raw.githubusercontent.com/\$REPO/main/latest.json\")"))
        assertEquals("https://raw.githubusercontent.com/surtaeff/subaru-ssm-dashboard/main/latest.json", kg.forester.sg.data.Updater.latestJsonUrl())
        assertEquals("https://x.example/y", kg.forester.sg.data.Updater.logUrlOf("{\"tag\":\"v1\",\"logUrl\":\"https://x.example/y\"}"))
        assertEquals("", kg.forester.sg.data.Updater.logUrlOf("{\"tag\":\"v1\"}"))
        // the daily look refreshes the address for the automatic send; the send screen looks at once, switch or not
        assertTrue(up.contains("LogUpload.endpointOf(it)"))
        val ui = text("ui/LogUploadUi.kt")
        assertTrue(ui.contains("LaunchedEffect(Unit)") && ui.contains("Updater.logUrl()"))
        assertTrue(ui.contains("LogUpload.canManual(endpoint)"))
        assertTrue(ui.contains("LogUpload.forget(ctx)"))
    }

    @Test fun backupLeavesTheIdOut() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(m.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val a = File("src/main/res/xml/backup_rules.xml").readText()
        val b = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(a.contains("<exclude domain=\"sharedpref\" path=\"device_id.xml\""))
        assertEquals(2, Regex("<exclude domain=\"sharedpref\" path=\"device_id.xml\"").findAll(b).count())
        // the id lives in exactly that file
        assertTrue(text("data/DeviceId.kt").contains("getSharedPreferences(\"device_id\""))
        // created at the first launch
        assertTrue(text("data/Repo.kt").contains("DeviceId.get(ctx.applicationContext)"))
    }

    @Test fun scriptMatchesTheClient() {
        val gs = File("../tools/log-upload/Code.gs").readText()
        assertTrue(gs.contains("Utilities.base64Decode"))
        assertFalse(gs.contains("getBytes()"))
        assertTrue(gs.contains("MAX_VERIFIED_PER_DAY = 200"))
        assertTrue(gs.contains("DEDICATED Google account"))
        assertTrue(gs.indexOf("FIRST THING TO CHECK") in 0..400)
        // clean-up is not called from the POST
        val post = gs.substring(gs.indexOf("function doPost"), gs.indexOf("function doGet"))
        assertFalse(post.contains("dailyCleanup")); assertFalse(post.contains("getFolders()"))
        assertTrue(gs.contains("keepNewest_"))
        assertEquals(32, kg.forester.sg.data.DeviceId.ALPHABET.length)
    }

    // ---- round 2: nothing above API 25 on the send path, and the manual send off the UI thread

    @Test fun noNewApiOnTheSendPath() {
        for (f in listOf("service/LogUpload.kt", "service/LogScrub.kt", "data/DeviceId.kt")) {
            val t = text(f)
            assertFalse("$f: java.util.Base64 is API 26", t.contains("java.util.Base64"))
            assertFalse("$f: readNBytes is API 33", t.contains("readNBytes"))
        }
        // the own encoder agrees with the standard one
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37 + n).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), LogUpload.base64(b))
        }
        // and lint fails the build on a new one
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("NewApi"))
        assertTrue(g.contains("lint {"))
    }

    @Test fun manualShareAndSaveRunOffTheMainThread() {
        val ui = text("ui/CarSetup.kt")
        val a = ui.indexOf("fun manualReport")
        val b = ui.indexOf("ActionButton(\"Очистить журнал\"")
        val block = ui.substring(a, b)
        assertTrue(block.contains("Dispatchers.IO"))
        assertTrue(block.contains("catch (t: Throwable)"))
        assertFalse("not computed per call on the UI thread", block.contains("report()"))
        assertEquals(1, Regex("prepareNow\\(ctx\\)").findAll(block).count())
        // no second run while one is going; the file writes are inside the IO block, only startActivity is outside it
        assertTrue(block.contains("if (busy) return"))
        val io = block.substring(block.indexOf("withContext(kotlinx.coroutines.Dispatchers.IO)"), block.indexOf("if (intent != null"))
        assertTrue(io.contains("SsmTrace.shareIntent(ctx, text)")); assertTrue(io.contains("SsmTrace.saveToDownloads(ctx, text)"))
        assertFalse(io.contains("startActivity"))
        assertFalse(text("ssm/SsmTrace.kt").contains("startActivity"))
    }
}

/** The key: only in the first request, never in the text. */
class LogUploadKeyTest {
    private val key = "00112233445566778899aabbccddeeff"

    @Test fun theKeyGoesInThePostAddressAndNotInTheRedirect() {
        val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = ArrayList<String>()
        val t = Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val first = inp.readLine()
                    seen += first
                    var len = 0
                    while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(":").trim().toInt() }
                    repeat(len) { inp.read() }
                    val head = if (first.startsWith("POST")) "HTTP/1.1 302 X\r\nLocation: /result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 X\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    c.getOutputStream().apply { write(head.toByteArray()); flush() }
                }
                runCatching { c.close() }
            }
        }
        t.isDaemon = true; t.start()
        try {
            assertTrue(LogUpload.Http.post("http://127.0.0.1:${ss.localPort}/exec", "ABCD2345", key, "QUJD").ok)
        } finally { ss.close() }
        assertEquals(2, seen.size)
        assertTrue(seen[0], seen[0].contains("id=ABCD2345&key=$key"))
        assertFalse(seen[1], seen[1].contains(key))
    }

    @Test fun theKeyInALogLineIsCutOut() {
        val p = LogUpload.prepare("sys $key", "head", "12:00:00.000 key=$key\n", setOf(key))
        assertFalse(p.text.contains(key))
    }

    @Test fun theKeyIsNotInAFailureReason() {
        val s = object : LogUpload.Sender {
            override fun post(url: String, id: String, key: String, payload: String): LogUpload.Result = throw java.io.IOException("$url?key=$key")
        }
        val r = LogUpload.send(s, "https://example.invalid/x", "ABCD2345", key, LogUpload.prepare("s", "h", "", emptySet()))
        assertFalse(r.reason.contains(key))
    }

    @Test fun theSourceHasNoLogOfTheKey() {
        val src = File("src/main/java/kg/forester/sg/service/LogUpload.kt").readLines()
        assertTrue(src.none { it.contains("SsmTrace.log") && it.contains("key", ignoreCase = true) })
    }
}
