package kg.forester.sg

import kg.forester.sg.service.EcuService
import kg.forester.sg.service.StreamRestarts
import kg.forester.sg.service.TcuMiss
import kg.forester.sg.ssm.KLineTransport
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private fun frameOf(dst: Int, src: Int, body: ByteArray): ByteArray {
    val f = byteArrayOf(0x80.toByte(), dst.toByte(), src.toByte(), body.size.toByte()) + body + 0
    f[f.size - 1] = KLineTransport.checksum(f, f.size - 1)
    return f
}

/**
 * A plain KKL line with an ECU (0x10) and a TCU (0x18): every request echoed, A8 00 answered by the unit asked, A8 01
 * streamed by the ECU (each frame in two halves, a pause after it) until the next request. [mute]: the ECU lets
 * everything pass, as the Legacy BP did; [echo] false: the line dead (the ignition off).
 */
private class Line(private val shortMs: Long = 0L, private val longMs: Long = 0L) : KLineTransport() {
    override val name = "fake KKL"
    override val quickAnswers get() = true
    override val requestGapMs get() = shortMs
    override val longGapMs get() = longMs
    @Volatile var mute = false
    @Volatile var echo = true
    @Volatile var breaks = 0
    /** Per request written: ms since the last byte the line carried, and its body. */
    val writes = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
    @Volatile private var lastByteMs = 0L
    @Volatile private var streamer: Thread? = null
    @Volatile var midFrame = false
    @Volatile var stopsInFrame = 0
    @Volatile var stops = 0

    fun hear(b: ByteArray) { lastByteMs = System.currentTimeMillis(); onBytes(b) }

    override fun breakLine(ms: Long): Boolean { breaks++; return true }

    override fun writeRaw(packet: ByteArray) {
        val now = System.currentTimeMillis()
        val body = packet.copyOfRange(4, packet.size - 1)
        val stop = body.size == 2 && body[0] == 0xA8.toByte()
        if (stop) { stops++; if (midFrame) stopsInFrame++ }
        writes += (if (lastByteMs == 0L) Long.MAX_VALUE else now - lastByteMs) to body
        streamer?.interrupt(); streamer?.join(); streamer = null
        midFrame = false
        if (!echo) return
        hear(packet)
        if (mute) return
        val dest = packet[1].toInt() and 0xFF
        when (body[0]) {
            0xBF.toByte() -> hear(frameOf(0xF0, dest, byteArrayOf(0xFF.toByte()) + ByteArray(8) { 0x20 }))
            0xA8.toByte() -> if (body.size >= 5) {
                val f = frameOf(0xF0, dest, byteArrayOf(0xE8.toByte()) + ByteArray((body.size - 2) / 3) { k -> body[4 + k * 3] })
                if (body[1] == 0x00.toByte()) hear(f) else streamer = Thread {
                    try {
                        while (true) {
                            midFrame = true
                            hear(f.copyOf(f.size / 2)); Thread.sleep(40)
                            // the last byte is on its way: the line is free once it is heard
                            midFrame = false
                            hear(f.copyOfRange(f.size / 2, f.size)); Thread.sleep(25)
                        }
                    } catch (_: InterruptedException) { midFrame = false }
                }.also { it.start() }
            }
        }
    }

    override fun close() { streamer?.interrupt() }
}

/** Answers reads of at most [limit] addresses, records each request's size; [silent] from the [silentFrom]-th request on. */
private class Sized(private val limit: Int = 64, private val silentFrom: Int = Int.MAX_VALUE) : SsmTransport {
    override val name = "fake"
    override val isCan = false
    val sizes = ArrayList<Int>()
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        val n = (body.size - 2) / 3
        sizes += n
        if (sizes.size >= silentFrom || n > limit) return null
        return byteArrayOf(0xE8.toByte()) + ByteArray(n) { (body[4 + it * 3].toInt() and 0xFF).toByte() }
    }
    override fun close() {}
}

class LinkDropTest {
    private val rom = "2F04207006"

    @Test fun aSilentRestartMakesTheTransmissionRareTwiceTurnsTheStreamOff() {
        val r = StreamRestarts()
        assertEquals(3000L, r.tcuGapMs(rom, 3))
        // frames came after the restart, it never streamed (the first start's rule), the line dead: none of them counts
        assertEquals(0, r.silent(rom, run = 4, frameSeen = true, ignored = 3))
        assertEquals(0, r.silent(rom, run = 0, frameSeen = false, ignored = 3))
        assertEquals(0, r.silent(rom, run = 0, frameSeen = true, ignored = 1))
        assertEquals(1, r.silent(rom, run = 0, frameSeen = true, ignored = 3))
        assertFalse(r.off(rom))
        assertEquals(20_000L, r.tcuGapMs(rom, 3))
        assertEquals(20_000L, EcuService.fastTcuGapMs(30, 1))
        // another ECU keeps its stream as it was
        assertEquals(3000L, r.tcuGapMs("3C04504316", 3))
        assertEquals(2, r.silent(rom, run = 0, frameSeen = true, ignored = 2))
        assertTrue(r.off(rom))
        assertFalse(r.off("3C04504316"))
    }

    @Test fun offRomsAreKeptAcrossCableReopensAndAppRestarts() {
        // Серёга's Legacy BP 1.0-beta8 log: the stream fell silent twice, the plain-requests decision then had to
        // hold through 12 cable reopens in 65 min instead of each new session firing the 25-address stream again
        val store = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
        val key = "store"
        val first = StreamRestarts(load = { store[key] ?: emptySet() }, save = { store[key] = it })
        assertEquals(1, first.silent(rom, run = 0, frameSeen = true, ignored = 3))
        assertNull(store[key])
        assertEquals(2, first.silent(rom, run = 0, frameSeen = true, ignored = 2))
        assertEquals(setOf(rom), store[key])
        // the service came back (a new EcuService instance): this ROM stays off straight away
        val second = StreamRestarts(load = { store[key] ?: emptySet() }, save = { store[key] = it })
        assertTrue(second.off(rom))
        assertFalse(second.off("3C04504316"))
        // a second ROM falling silent is added to the stored set, not written over it
        assertEquals(1, second.silent("3C04504316", run = 0, frameSeen = true, ignored = 2))
        assertEquals(setOf(rom), store[key])
        assertEquals(2, second.silent("3C04504316", run = 0, frameSeen = true, ignored = 2))
        assertEquals(setOf(rom, "3C04504316"), store[key])
        // the owner cleared the set (Fast Poll switch off and on): a fresh StreamRestarts starts blank
        store[key] = emptySet()
        val fresh = StreamRestarts(load = { store[key] ?: emptySet() }, save = { store[key] = it })
        assertFalse(fresh.off(rom))
    }

    @Test fun aRestartTheEcuIgnoresIsToldFromADeadLine() {
        val line = Line()
        val c = SsmClient(line)
        val addrs = IntArray(33) { 0x08 + it }
        val got = CopyOnWriteArrayList<ByteArray>()
        assertTrue(c.startStream(addrs) { got += it })
        Thread.sleep(250)
        assertTrue(got.isNotEmpty())
        c.stopStream()
        assertArrayEquals(byteArrayOf(0x51), c.read(intArrayOf(0x51), SsmClient.TCU))
        // the restart after the transmission's answer: echoed, never answered
        line.mute = true
        assertTrue(c.startStream(addrs) { got += it })
        Thread.sleep(200)
        c.stopStream()
        assertTrue("${line.ignoredRequests}", line.ignoredRequests >= 2)
        // the ignition off: nothing comes back at all
        val dead = Line()
        val d = SsmClient(dead)
        assertTrue(d.startStream(addrs) { })
        Thread.sleep(200)
        d.stopStream()
        dead.echo = false
        assertTrue(d.startStream(addrs) { })
        Thread.sleep(200)
        d.stopStream()
        assertTrue("${dead.ignoredRequests}", dead.ignoredRequests < 2)
        c.close(); d.close()
    }

    @Test fun theStopGoesBetweenTwoFrames() {
        val line = Line()
        val c = SsmClient(line)
        repeat(6) {
            assertTrue(c.startStream(IntArray(33) { 0x08 + it }) { })
            Thread.sleep(150)
            assertTrue(c.stopStream() == true)
        }
        assertEquals(6, line.stops)
        assertEquals("stops sent inside a frame", 0, line.stopsInFrame)
        c.close()
    }

    @Test fun aPlainCableWaitsLongOnlyAfterAnotherUnitsAnswer() {
        val short = KLineTransport.REQUEST_GAP_MS
        val long = KLineTransport.LONG_GAP_MS
        val line = Line(short, long)
        val c = SsmClient(line)
        assertTrue(c.init() != null)
        repeat(3) {
            c.read(intArrayOf(0x65))!!
            c.read(intArrayOf(0x08, 0x09, 0x0A, 0x0E, 0x0F, 0x14), SsmClient.TCU)!!
            c.read(IntArray(25) { 0x08 + it })!!
        }
        // init, then (0x65, the transmission, 25 addresses) three times: the engine after its own answer waits the
        // short gap, a request after the other unit's answer the long one (the transmission's answer, then the
        // engine's request: the Legacy BP's worst spot)
        val w = line.writes.map { it.first }
        assertEquals(10, w.size)
        for (i in 1 until w.size) {
            if (i % 3 == 1) assertTrue("$i: ${w[i]} ms", w[i] >= short && w[i] < long)
            else assertTrue("$i: ${w[i]} ms", w[i] >= long)
        }
        assertFalse(line.longGap)
        // and the stream started right after the transmission's answer
        c.read(intArrayOf(0x51), SsmClient.TCU)!!
        assertTrue(c.startStream(IntArray(10) { 0x08 + it }) { })
        assertTrue("${line.writes.last().first} ms", line.writes.last().first >= long)
        c.stopStream()
        c.close()
    }

    @Test fun otherAdaptersAreNotSlowed() {
        val line = Line()
        val c = SsmClient(line)
        c.read(intArrayOf(0x65))!!
        val t = System.currentTimeMillis()
        repeat(5) { c.read(intArrayOf(0x65))!! }
        assertTrue(System.currentTimeMillis() - t < 5 * KLineTransport.LONG_GAP_MS)
    }

    @Test fun ourInitAfterTheBreakIsNotAnotherTester() {
        val line = Line(KLineTransport.REQUEST_GAP_MS, KLineTransport.LONG_GAP_MS)
        val c = SsmClient(line)
        assertTrue(c.init() != null)
        line.mute = true
        // the mute ECU, then a garbled half frame with a long length: whatever follows waits behind it
        line.hear(byteArrayOf(0x80.toByte(), 0xF0.toByte(), 0x10, 0x30, 0x11, 0x22))
        assertTrue(line.holdLineLow(10))
        assertEquals(1, line.breaks)
        // held low for a mute ECU: the long gap from now on
        assertTrue(line.longGap)
        line.mute = false
        assertTrue(c.init() != null)
        repeat(4) { c.read(IntArray(25) { 0x08 + it })!! }
        assertFalse(line.passive)
    }

    @Test fun anEchoParsedLateIsStillOurs() {
        val line = Line()
        val c = SsmClient(line)
        c.read(intArrayOf(0x65))!!
        // a stray header (a collision) claiming 48 bytes: the echoes behind it come out only after a few more requests
        line.hear(byteArrayOf(0x80.toByte(), 0xF0.toByte(), 0x10, 0x30, 0x11, 0x22))
        assertTrue(c.init() != null)
        repeat(4) { c.read(IntArray(8) { 0x08 + it })!! }
        assertFalse(line.passive)
        assertEquals(0, line.ignoredRequests)
    }

    @Test fun aMuteEcuTeachesNoLimit() {
        val t = Sized(silentFrom = 2)
        val c = SsmClient(t)
        c.read(IntArray(25) { it + 1 })!!
        repeat(4) { assertNull(c.read(IntArray(25) { it + 1 })) }
        assertEquals(0, c.ecuReadLimit)
    }

    @Test fun aMuteEcuOnTheLineTeachesNoLimitButTheLongGap() {
        val line = Line(KLineTransport.REQUEST_GAP_MS, KLineTransport.LONG_GAP_MS)
        val c = SsmClient(line)
        val addrs = IntArray(25) { 0x08 + it }
        assertTrue(c.init() != null)
        c.read(addrs)!!
        assertFalse(line.longGap)
        line.mute = true
        repeat(2) { assertNull(c.read(addrs)) }
        assertTrue(line.ecuMute())
        assertEquals(0, c.ecuReadLimit)
        // gone mute once: the long gap even after its own answers
        assertTrue(line.longGap)
        line.mute = false
        c.read(intArrayOf(0x65))!!
        c.read(intArrayOf(0x65))!!
        assertTrue("${line.writes.last().first} ms", line.writes.last().first >= KLineTransport.LONG_GAP_MS)
    }

    @Test fun aRealLimitIsStillLearnt() {
        val t = Sized(limit = 12)
        val c = SsmClient(t)
        assertNull(c.read(IntArray(25) { it + 1 }))
        assertArrayEquals(ByteArray(25) { (it + 1).toByte() }, c.read(IntArray(25) { it + 1 }))
        assertEquals(12, c.ecuReadLimit)
        assertEquals(listOf(25, 25, 8, 8, 9), t.sizes)
    }

    @Test fun partsAreEven() {
        val t = Sized()
        val c = SsmClient(t)
        c.presetReadLimit(12)
        assertArrayEquals(ByteArray(33) { (it + 1).toByte() }, c.read(IntArray(33) { it + 1 }))
        assertEquals(listOf(11, 11, 11), t.sizes)
        val t8 = Sized()
        val c8 = SsmClient(t8)
        c8.presetReadLimit(8)
        assertArrayEquals(ByteArray(33) { (it + 1).toByte() }, c8.read(IntArray(33) { it + 1 }))
        assertEquals(listOf(6, 6, 7, 7, 7), t8.sizes)
        t8.sizes.clear()
        c8.read(IntArray(16) { it + 1 })!!
        assertEquals(listOf(8, 8), t8.sizes)
    }

    @Test fun aLongerStreamFrameUndoesALowLimit() {
        val line = Line()
        val c = SsmClient(line)
        c.presetReadLimit(8)
        assertEquals(8, c.ecuReadLimit)
        val got = CopyOnWriteArrayList<ByteArray>()
        assertTrue(c.startStream(IntArray(33) { 0x08 + it }) { got += it })
        Thread.sleep(250)
        c.stopStream()
        assertTrue(got.isNotEmpty())
        assertEquals(0, c.ecuReadLimit)
        c.close()
    }

    @Test fun aTransmissionGoneQuietIsAskedAgainLater() {
        // before any answer: two misses drop 0x6A first
        assertEquals(TcuMiss.NONE, EcuService.tcuMiss(1, answered = false, fwdInList = true))
        assertEquals(TcuMiss.DROP_FWD, EcuService.tcuMiss(2, answered = false, fwdInList = true))
        assertEquals(TcuMiss.NONE, EcuService.tcuMiss(2, answered = false, fwdInList = false))
        // once it has answered, three in a row: its values go, init again in half a minute
        assertEquals(TcuMiss.NONE, EcuService.tcuMiss(2, answered = true, fwdInList = true))
        assertEquals(TcuMiss.BACK_OFF, EcuService.tcuMiss(3, answered = true, fwdInList = true))
        assertEquals(TcuMiss.BACK_OFF, EcuService.tcuMiss(3, answered = false, fwdInList = false))
        assertTrue(EcuService.TCU_RETRY_MS in 30_000L..60_000L)
    }

    @Test fun aLineSharedWithAGaugeIsOnlyListenedTo() {
        val line = Line()
        line.listenOnly = true
        val c = SsmClient(line)
        assertTrue(line.passive)
        assertNull(c.init())
        assertNull(c.read(intArrayOf(0x08)))
        assertFalse(c.startStream(intArrayOf(0x08)) { })
        assertFalse(line.holdLineLow(2500))
        assertEquals(0, line.breaks)
        assertTrue("nothing sent", line.writes.isEmpty())
        // a gauge at its own tester address, the continuous request: each answer is taken
        val ask = byteArrayOf(0xA8.toByte(), 0x01, 0, 0, 0x0E, 0, 0, 0x0F, 0, 0, 0x12)
        line.hear(frameOf(SsmClient.ECU, 0xF1, ask))
        line.hear(frameOf(0xF1, SsmClient.ECU, byteArrayOf(0xE8.toByte(), 0x2E, 0xE0.toByte(), 0x55)))
        assertArrayEquals(byteArrayOf(0x2E, 0xE0.toByte(), 0x55), line.sniffed(intArrayOf(0x0E, 0x0F, 0x12), 1000)!!.second)
        line.hear(frameOf(0xF1, SsmClient.ECU, byteArrayOf(0xE8.toByte(), 0x30, 0x10, 0x56)))
        val (a, v) = line.sniffed(intArrayOf(0x08, 0x0E, 0x0F, 0x12), 1000)!!
        assertArrayEquals(intArrayOf(0x0E, 0x0F, 0x12), a)
        assertArrayEquals(byteArrayOf(0x30, 0x10, 0x56), v)
        // an answer of another length is not taken for that list
        line.hear(frameOf(0xF1, SsmClient.ECU, byteArrayOf(0xE8.toByte(), 0x01)))
        assertArrayEquals(byteArrayOf(0x30, 0x10, 0x56), line.sniffed(intArrayOf(0x0E, 0x0F, 0x12), 1000)!!.second)
        line.listenOnly = false
    }

    @Test fun aTesterAtAnotherAddressIsHeard() {
        val line = Line()
        assertFalse(line.passive)
        line.hear(frameOf(SsmClient.ECU, 0xF1, byteArrayOf(0xA8.toByte(), 0, 0, 0, 0x08)))
        assertTrue(line.passive)
        assertNull(SsmClient(line).read(intArrayOf(0x08)))
        assertTrue(line.writes.isEmpty())
    }
}
