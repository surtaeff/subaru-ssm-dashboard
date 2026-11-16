package kg.forester.sg

import kg.forester.sg.data.CanConfig
import kg.forester.sg.service.CanLinks
import kg.forester.sg.ssm.ByteLink
import kg.forester.sg.ssm.CanFrame
import kg.forester.sg.ssm.CanProbe
import kg.forester.sg.ssm.CanSource
import kg.forester.sg.ssm.CanTx
import kg.forester.sg.ssm.Gvret
import kg.forester.sg.ssm.RealDashCan
import kg.forester.sg.ssm.Slcan
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanTransportTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun le32(v: Long) = bytes((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())

    private fun rdDecode(vararg chunks: ByteArray, text: ((Int, String) -> Unit)? = null): Pair<List<CanFrame>, RealDashCan.Stats> {
        val d = RealDashCan.Decoder(text)
        val out = ArrayList<CanFrame>()
        chunks.forEach { d.feed(it, 0, it.size, out) }
        return out to d.stats
    }

    /** Every byte on its own: a frame split at any point still comes out whole. */
    private fun oneByOne(b: ByteArray) = Array(b.size) { byteArrayOf(b[it]) }

    // ---- RealDash CAN

    @Test fun realdash44Frame() {
        val raw = bytes(0x44, 0x33, 0x22, 0x11, 0xE8, 0x03, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        val (f, st) = rdDecode(raw)
        assertEquals(1, f.size)
        assertEquals(0x3E8, f[0].id)
        assertFalse(f[0].ext)
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8), f[0].data)
        assertEquals(0, st.skipped)
        assertArrayEquals(raw, RealDashCan.frame44(0x3E8, bytes(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test fun realdashSplitAnywhere() {
        val a = RealDashCan.frame44(0x100, bytes(9, 8, 7))
        val b = RealDashCan.frame66(0x18FEF100, ByteArray(20) { it.toByte() })
        val (f, st) = rdDecode(*oneByOne(a + b))
        assertEquals(2, f.size)
        assertEquals(0x100, f[0].id)
        assertArrayEquals(bytes(9, 8, 7, 0, 0, 0, 0, 0), f[0].data) // padded to 8
        assertEquals(0x18FEF100, f[1].id)
        assertTrue(f[1].ext) // above 7FF
        assertEquals(20, f[1].len)
        assertTrue(f[1].fd)
        assertEquals(0, st.crcErrors)
        // the same split in two arbitrary places
        for (cut in 1 until (a + b).size) {
            val all = a + b
            assertEquals("cut at $cut", 2, rdDecode(all.copyOfRange(0, cut), all.copyOfRange(cut, all.size)).first.size)
        }
    }

    @Test fun realdashResyncsAfterGarbage() {
        // a stream joined midway: the tail of a frame, noise with a false tag start, then good frames
        val good = RealDashCan.frame44(0x3E9, bytes(1, 1, 1, 1, 1, 1, 1, 1))
        val junk = bytes(0x05, 0x06, 0x44, 0x33, 0x00, 0x66, 0x33, 0x22, 0x99, 0x11, 0x22)
        val (f, st) = rdDecode(good.copyOfRange(5, 16) + junk + good + good)
        assertEquals(2, f.size)
        assertTrue(f.all { it.id == 0x3E9 })
        assertEquals(11 + junk.size, st.skipped)
    }

    @Test fun realdash66CrcAndFd() {
        val data = ByteArray(64) { (it * 3).toByte() }
        val ok = RealDashCan.frame66(0x7E8, data)
        assertEquals(8 + 64 + 4, ok.size)
        assertEquals(0x1F, ok[3].toInt())
        val bad = ok.copyOf().also { it[20] = (it[20] + 1).toByte() }
        val (f, st) = rdDecode(bad + ok)
        assertEquals(1, f.size)
        assertArrayEquals(data, f[0].data)
        assertEquals(1, st.crcErrors)
        // the CRC is the standard CRC-32 the spec's table makes
        assertEquals(0xCBF43926L, RealDashCan.crc("123456789".toByteArray(), 0, 9))
        assertEquals(8, RealDashCan.sizeOf(0x11))
        assertEquals(64, RealDashCan.sizeOf(0x1F))
        assertNull(RealDashCan.sizeOf(0x20))
        assertEquals(0x12, RealDashCan.sizeCode(9)) // 12 bytes
        assertEquals(0x11, RealDashCan.sizeCode(3))
        // an 8-byte 66 frame is no CAN FD frame
        assertFalse(rdDecode(RealDashCan.frame66(1, ByteArray(8))).first[0].fd)
    }

    @Test fun realdashTextAndConfigFramesSkipped() {
        var text: String? = null
        val t = bytes(0x55, 0x33, 0x22, 0x11, 0x10, 0, 0, 0) + "hello".toByteArray() + bytes(0)
        val cfg = RealDashCan.configSpeed(500)!! + RealDashCan.configMode(RealDashCan.MODE_SILENT)
        val frame = RealDashCan.frame44(0x200, ByteArray(8))
        val (f, st) = rdDecode(*oneByOne(t + cfg + frame)) { _, s -> text = s }
        assertEquals("hello", text)
        assertEquals(1, f.size)
        assertEquals(2, st.configs)
        assertEquals(0, st.skipped)
        assertEquals(0, st.crcErrors)
        // the speed frame's layout: 67 33 22 02 15, speed, CRC32 of the six
        val s = RealDashCan.configSpeed(500)!!
        assertArrayEquals(bytes(0x67, 0x33, 0x22, 0x02, 0x15, 0x06), s.copyOf(6))
        assertArrayEquals(le32(RealDashCan.crc(s, 0, 6)), s.copyOfRange(6, 10))
        assertNull(RealDashCan.configSpeed(83))
    }

    @Test fun realdashSetValue44() {
        val b = RealDashCan.setValue44(CanFrame(0x3E8, false, bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertEquals(17, b.size)
        val sum = b.take(16).sumOf { it.toInt() and 0xFF } and 0xFF
        assertEquals(sum, b[16].toInt() and 0xFF)
        assertArrayEquals(bytes(0x44, 0x33, 0x22, 0x11, 0xE8, 0x03, 0, 0), b.copyOf(8))
    }

    // ---- GVRET (layouts as ESP32RET's commbuffer.cpp sends them)

    private fun gvretClassic(ts: Long, id: Long, bus: Int, data: ByteArray) =
        bytes(0xF1, 0) + le32(ts) + le32(id) + bytes(data.size or (bus shl 4)) + data + bytes(0)

    private fun gvretFd(ts: Long, id: Long, bus: Int, data: ByteArray) =
        bytes(0xF1, 20) + le32(ts) + le32(id) + bytes(data.size, bus) + data + bytes(0)

    private fun gvDecode(vararg chunks: ByteArray): Pair<List<CanFrame>, Gvret.Decoder> {
        val d = Gvret.Decoder()
        val out = ArrayList<CanFrame>()
        chunks.forEach { d.feed(it, 0, it.size, out) }
        return out to d
    }

    @Test fun gvretFrames() {
        val a = gvretClassic(123456, 0x3E8, 1, bytes(0xF1, 0xF1, 0xE7))
        val b = gvretClassic(5, 0x18FEF100L or 0x80000000L, 0, ByteArray(8) { 0x55 })
        val c = gvretFd(7, 0x123, 2, ByteArray(64) { it.toByte() })
        val text = "ESP32RET build 618\r\n".toByteArray()
        val (f, d) = gvDecode(*oneByOne(text + a + b + c))
        assertEquals(3, f.size)
        assertEquals(0x3E8, f[0].id); assertFalse(f[0].ext); assertEquals(1, f[0].bus); assertEquals(123456L, f[0].timeUs)
        assertArrayEquals(bytes(0xF1, 0xF1, 0xE7), f[0].data)
        assertEquals(0x18FEF100, f[1].id); assertTrue(f[1].ext); assertEquals(8, f[1].len)
        assertTrue(f[2].fd); assertEquals(2, f[2].bus); assertEquals(64, f[2].len); assertEquals(63, f[2].data[63].toInt())
        assertEquals(0, d.dropped)
    }

    @Test fun gvretAnswersAndResync() {
        val numBuses = bytes(0xF1, 12, 3)
        val devInfo = bytes(0xF1, 7, 0x6A, 0x02, 0x20, 0, 0, 0)
        val params = bytes(0xF1, 6, 0x11) + le32(500_000) + bytes(0x01) + le32(250_000)
        val keep = bytes(0xF1, 9, 0xDE, 0xAD)
        val fd = bytes(0xF1, 22, 2, 0x01) + le32(2_000_000) + bytes(0x00) + le32(0)
        // a frame with an impossible length is dropped, the next one is read
        val broken = bytes(0xF1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0x0C)
        val good = gvretClassic(1, 0x10, 0, bytes(1, 2))
        val (f, d) = gvDecode(*oneByOne(numBuses + devInfo + params + keep + fd + broken + good))
        assertEquals(3, d.info.buses)
        assertEquals(618, d.info.build)
        assertEquals(listOf(500_000, 250_000), d.info.speeds)
        assertEquals(listOf(true, false), d.info.listenOnly)
        assertEquals(1, d.info.keepAlive)
        assertEquals(listOf(true, false), d.info.fdCapable)
        assertEquals(1, f.size)
        assertEquals(0x10, f[0].id)
        assertEquals(1, d.dropped)
    }

    @Test fun gvretToBoard() {
        // host to board, opcode 0: F1 00 id(4, bit 31 extended) bus len data trailing
        assertArrayEquals(bytes(0xF1, 0, 0xE8, 0x03, 0, 0, 1, 2, 0xAA, 0xBB, 0),
            Gvret.frame(CanFrame(0x3E8, false, bytes(0xAA, 0xBB), bus = 1)))
        val ext = Gvret.frame(CanFrame(0x18FEF100, true, bytes(1)))
        assertArrayEquals(le32(0x18FEF100L or 0x80000000L), ext.copyOfRange(2, 6))
        // opcode 20 with BRS in bit 7 of the length, padded to an FD length
        val fd = Gvret.frame(CanFrame(0x100, false, ByteArray(10), fd = true, brs = true))
        assertEquals(20, fd[1].toInt())
        assertEquals(12 or 0x80, fd[7].toInt() and 0xFF)
        assertEquals(8 + 12 + 1, fd.size)
        // bus setup: speed, the flags bit, enabled, listen only
        assertEquals(0xE007A120L, Gvret.busWord(500_000, true, true))
        assertEquals(0xC007A120L, Gvret.busWord(500_000, true, false))
        assertEquals(0L, Gvret.busWord(500_000, false, false))
        val setup = Gvret.setupBuses(0xE007A120L, 0L)
        assertArrayEquals(bytes(0xF1, 5) + le32(0xE007A120L) + le32(0) + bytes(0), setup)
        assertArrayEquals(bytes(0xE7, 0xE7, 0xF1, 12, 0xF1, 7, 0xF1, 6, 0xF1, 22), Gvret.hello())
    }

    // ---- slcan and frame lengths

    @Test fun slcanFdLines() {
        val d = Slcan.parse("d123F" + "AB".repeat(64))!!
        assertTrue(d.fd); assertFalse(d.brs); assertFalse(d.ext); assertEquals(64, d.len)
        val b = Slcan.parse("B18FEF100A" + "01".repeat(16))!!
        assertTrue(b.fd); assertTrue(b.brs); assertTrue(b.ext); assertEquals(16, b.len)
        assertNull(Slcan.parse("d123F" + "AB".repeat(10))) // cut short
        assertEquals("t3E83010203", Slcan.format(CanFrame(0x3E8, false, bytes(1, 2, 3))))
        assertEquals("T18FEF1001FF", Slcan.format(CanFrame(0x18FEF100, true, bytes(0xFF))))
        assertEquals("b1009" + "00".repeat(12), Slcan.format(CanFrame(0x100, false, ByteArray(9), fd = true, brs = true)))
        assertTrue(Slcan.versionReply("V1013\r"))
        assertTrue(Slcan.versionReply("b158aa7 github.com/normaldotcom/canable2-fw.git\r"))
        assertFalse(Slcan.versionReply("V\r")) // a KKL cable's echo
        assertFalse(Slcan.versionReply("V\r?\r\r>")) // an ELM327
        assertEquals(64, CanFrame.dlcToLen(15, fd = true))
        assertEquals(8, CanFrame.dlcToLen(15, fd = false))
        assertEquals(9, CanFrame.lenToDlc(12))
        assertEquals(12, CanFrame.fdLen(9))
        assertEquals(64, CanFrame.fdLen(49))
    }

    // ---- the transmit gate

    private val bench = CanTx.Policy(enabled = true, simulate = false, allowed = setOf(CanTx.key(0x300, false)),
        perIdPerSec = 3, totalPerSec = 4, roles = mapOf(0 to CanTx.Role.SENSOR, 1 to CanTx.Role.POWERTRAIN))

    @Test fun gateChecks() {
        val g = CanTx.Gate()
        val f = CanFrame(0x300, false, bytes(1))
        assertEquals(CanTx.Verdict.OFF, g.check(f, bench.copy(enabled = false), 0))
        assertEquals(CanTx.Verdict.NOT_LISTED, g.check(CanFrame(0x301, false, bytes(1)), bench, 0))
        assertEquals(CanTx.Verdict.NOT_LISTED, g.check(CanFrame(0x300, true, bytes(1)), bench, 0)) // 29-bit is another id
        val diag = bench.copy(allowed = setOf(CanTx.key(0x7DF, false), CanTx.key(0x7E0, false), CanTx.key(0x18DB33F1, true)))
        assertEquals(CanTx.Verdict.DIAGNOSTIC, g.check(CanFrame(0x7DF, false, bytes(2, 1, 0)), diag, 0))
        assertEquals(CanTx.Verdict.DIAGNOSTIC, g.check(CanFrame(0x7E0, false, bytes(2, 1, 0)), diag, 0))
        assertEquals(CanTx.Verdict.DIAGNOSTIC, g.check(CanFrame(0x18DB33F1, true, bytes(2, 1, 0)), diag, 0))
        assertEquals(CanTx.Verdict.POWERTRAIN, g.check(CanFrame(0x300, false, bytes(1), bus = 1), bench, 0))
        assertEquals(CanTx.Verdict.BUS_NOT_MARKED, g.check(CanFrame(0x300, false, bytes(1), bus = 2), bench, 0))
        assertEquals(CanTx.Verdict.POWERTRAIN, g.check(f, bench, 0) { true }) // a Link stream on it
        assertEquals(CanTx.Verdict.BAD_FRAME, g.check(CanFrame(0x300, false, ByteArray(9)), bench, 0))
        // rate: 3 a second for the id, then wait for the window
        repeat(3) { assertNull(g.check(f, bench, 100L + it)) }
        assertEquals(CanTx.Verdict.RATE, g.check(f, bench, 200))
        assertNull(g.check(f, bench, 1101))
        // and 4 in all
        val two = bench.copy(allowed = bench.allowed + CanTx.key(0x301, false), perIdPerSec = 10)
        val g2 = CanTx.Gate()
        repeat(4) { assertNull(g2.check(CanFrame(0x300 + it % 2, false, bytes(1)), two, 0)) }
        assertEquals(CanTx.Verdict.RATE, g2.check(CanFrame(0x301, false, bytes(1)), two, 10))
    }

    private class FakeSource : CanSource {
        val sent = ArrayList<CanFrame>()
        override fun read(timeoutMs: Int) = emptyList<CanFrame>()
        override val errors = 0
        override val title = "Fake"
        override val canTransmit = true
        override fun transmit(f: CanFrame, permit: CanTx.Permit): Boolean { if (permit.covers(f)) sent += f; return permit.covers(f) }
        override fun close() {}
    }

    @After fun detach() { CanTx.source?.let { CanTx.detach(it) }; CanTx.policy = { CanTx.Policy() }; CanTx.clearLog() }

    @Test fun gateSimulatesSendsAndLogs() {
        val src = FakeSource()
        CanTx.attach(src)
        val f = CanFrame(0x300, false, bytes(1, 2))
        assertEquals(CanTx.Verdict.OFF, CanTx.send(f, 0)) // the default: off
        CanTx.policy = { bench.copy(simulate = true) }
        assertEquals(CanTx.Verdict.SIMULATED, CanTx.send(f, 0))
        assertTrue(src.sent.isEmpty())
        CanTx.policy = { bench }
        assertEquals(CanTx.Verdict.SENT, CanTx.send(f, 2000))
        assertEquals(listOf(f), src.sent)
        assertEquals(3, CanTx.log.value.size)
        assertEquals(CanTx.Verdict.SENT, CanTx.log.value.first().verdict)
        CanTx.detach(src)
        assertEquals(CanTx.Verdict.NO_ADAPTER, CanTx.send(f, 4000))
    }

    /** Nothing but the gate makes a permit: no other source file may call its constructor. */
    @Test fun onlyTheGateMakesPermits() {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "CanTx.kt" }
            .filter { Regex("""\bPermit\s*\(""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("files making a CanTx.Permit: $offenders", offenders.isEmpty())
        assertTrue(root.walkTopDown().any { it.name == "CanTx.kt" })
    }

    @Test fun configParsing() {
        val ids = CanConfig.parseIds("3E8, 0x7ff 18FF0001x;100x junk ZZ, 20000000")
        assertEquals(setOf(CanTx.key(0x3E8, false), CanTx.key(0x7FF, false), CanTx.key(0x18FF0001, true), CanTx.key(0x100, true)), ids)
        val roles = CanConfig.parseRoles("0:sensor, 1:powertrain,2:bogus,x:comfort")
        assertEquals(CanTx.Role.SENSOR, roles[0]); assertEquals(CanTx.Role.POWERTRAIN, roles[1]); assertEquals(CanTx.Role.UNKNOWN, roles[2])
        assertEquals("0:sensor,1:powertrain", CanConfig.formatRoles(roles))
        assertEquals("192.168.4.1" to 23, CanLinks.hostPort("192.168.4.1"))
        assertEquals("10.0.0.5" to 35000, CanLinks.hostPort(" 10.0.0.5:35000 "))
        assertNull(CanLinks.hostPort(""))
        assertNull(CanLinks.hostPort("host:99999"))
        val f = CanLinks.parseFrame("3e8", false, "01 02 0a ff", 0, false)!!
        assertEquals(0x3E8, f.id); assertArrayEquals(bytes(1, 2, 0x0A, 0xFF), f.data)
        assertNull(CanLinks.parseFrame("800", false, "", 0, false)) // above 7FF in 11 bits
        assertNull(CanLinks.parseFrame("1", false, "010203040506070809", 0, false)) // 9 bytes, not FD
        assertNotNull(CanLinks.parseFrame("1", false, "010203040506070809", 0, true))
        assertNull(CanLinks.parseFrame("1", false, "012", 0, false))
    }

    // ---- probing, with a scripted adapter

    /** An adapter that answers what it is sent with [reply], and streams [stream] by itself. */
    private class FakeLink(private val stream: ByteArray = ByteArray(0), private val reply: (ByteArray) -> ByteArray = { ByteArray(0) }) : ByteLink {
        private val inbox = ArrayDeque<Byte>()
        private var streamAt = 0
        val written = ArrayList<ByteArray>()
        override val label = "fake"
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            // the stream comes on and on, 32 bytes at a time
            if (stream.isNotEmpty()) repeat(32) { inbox.addLast(stream[streamAt++ % stream.size]) }
            var n = 0
            while (n < buf.size && inbox.isNotEmpty()) buf[n++] = inbox.removeFirst()
            if (n == 0) Thread.sleep(2)
            return n
        }
        override fun write(b: ByteArray) { written += b; reply(b).forEach { inbox.addLast(it) } }
        override fun close() {}
    }

    @Test fun probeFindsEachProtocol() {
        val rdStream = (0 until 20).map { RealDashCan.frame44(0x3E8 + it, ByteArray(8)) }.reduce { a, b -> a + b }
        assertEquals("realdash", CanProbe.detect(FakeLink(rdStream), listenMs = 300))
        val gv = FakeLink { w -> if (w.isNotEmpty() && w[0] == 0xE7.toByte()) bytes(0xF1, 12, 1, 0xF1, 7, 0x6A, 0x02, 0x20, 0, 0, 0) else ByteArray(0) }
        assertEquals("gvret", CanProbe.detect(gv, listenMs = 100))
        val sl = FakeLink { w -> if (String(w) == "V\r") "V1013\r".toByteArray() else if (w.all { it == '\r'.code.toByte() }) w else ByteArray(0) }
        assertEquals("slcan", CanProbe.detect(sl, listenMs = 100))
        // a KKL cable echoes everything back: none of them
        assertNull(CanProbe.detect(FakeLink { it }, listenMs = 100))
        // a GVRET board's console text is no RealDash stream
        assertNull(CanProbe.detect(FakeLink("ESP32RET build 618\r\n".repeat(20).toByteArray()), order = listOf("realdash"), listenMs = 200))
    }
}
