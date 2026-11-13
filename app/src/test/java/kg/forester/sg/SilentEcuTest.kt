package kg.forester.sg

import kg.forester.sg.service.EcuService
import kg.forester.sg.ssm.KLineTransport
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plain KKL cable on a K-line: echoes each request (unless [echo] is off) and the ECU answers it unless [mute]. */
private class KkLine : KLineTransport() {
    override val name = "fake KKL"
    override val quickAnswers get() = true
    @Volatile var mute = false
    @Volatile var echo = true
    /** Sent once before the next request's echo: an answer that came too late for the request before. */
    @Volatile var stale: ByteArray? = null

    override fun writeRaw(packet: ByteArray) {
        stale?.let { onBytes(it); stale = null }
        if (echo) onBytes(packet)
        if (mute) return
        val body = packet.copyOfRange(4, packet.size - 1)
        when (body[0]) {
            0xBF.toByte() -> onBytes(frame(byteArrayOf(0xFF.toByte()) + ByteArray(8) { 0x20 }))
            0xA8.toByte() -> onBytes(frame(byteArrayOf(0xE8.toByte()) + ByteArray((body.size - 2) / 3) { k -> body[4 + k * 3] }))
        }
    }

    fun frame(body: ByteArray): ByteArray {
        val f = byteArrayOf(0x80.toByte(), 0xF0.toByte(), SsmClient.ECU.toByte(), body.size.toByte()) + body + 0
        f[f.size - 1] = checksum(f, f.size - 1)
        return f
    }

    override fun close() {}
}

/** Loses the requests whose numbers (from 1) are in [lose]. */
private class Lossy(private val lose: Set<Int>) : SsmTransport {
    override val name = "fake"
    override val isCan = false
    var requests = 0
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        if (++requests in lose) return null
        val n = (body.size - 2) / 3
        return byteArrayOf(0xE8.toByte()) + ByteArray(n) { (body[4 + it * 3].toInt() and 0xFF).toByte() }
    }
    override fun close() {}
}

class SilentEcuTest {
    private val eight = IntArray(8) { it + 1 }

    private fun timed(block: () -> Unit): Long {
        val t = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - t
    }

    @Test fun quickTimeoutGrowsWithTheRequest() {
        // 8 addresses: 31 bytes out, 14 back
        assertEquals(693L, KLineTransport.readTimeoutMs(31, 8))
        assertTrue(KLineTransport.readTimeoutMs(199, 64) in 1100L..1300L)
    }

    @Test fun aReadAnsweredBeforeTimesOutQuickly() {
        val line = KkLine()
        val c = SsmClient(line)
        // the first read waits the full 2 s, as for a slow first answer
        line.mute = true
        assertTrue(timed { assertNull(c.read(eight)) } >= 1900)
        line.mute = false
        assertArrayEquals(ByteArray(8) { (it + 1).toByte() }, c.read(eight))
        line.mute = true
        // two quick tries (the lost request asked once more), not 2 s each
        assertTrue(timed { assertNull(c.read(eight)) } in 1300L..1800L)
    }

    @Test fun theInitKeepsTwoSeconds() {
        val line = KkLine()
        val c = SsmClient(line)
        assertArrayEquals(eight.map { it.toByte() }.toByteArray(), c.read(eight))
        line.mute = true
        // the init asks twice on the K-line, 2 s each
        assertTrue(timed { assertNull(c.init()) } >= 3800)
    }

    @Test fun muteOnALiveLineIsSeen() {
        val line = KkLine()
        val c = SsmClient(line)
        assertTrue(c.init() != null)
        c.read(eight)!!
        assertFalse(line.ecuMute())
        line.mute = true
        val t = System.currentTimeMillis()
        while (!line.ecuMute() && System.currentTimeMillis() - t < 6000) c.read(eight)
        assertTrue(line.ecuMute())
        // one answer and it is over
        line.mute = false
        c.read(eight)!!
        assertFalse(line.ecuMute())
    }

    @Test fun noEchoIsNoMute() {
        val line = KkLine()
        val c = SsmClient(line)
        c.read(eight)!!
        // the ignition off: nothing comes back, not even our own request
        line.mute = true
        line.echo = false
        repeat(4) { c.read(eight) }
        assertFalse(line.ecuMute())
    }

    @Test fun aLateAnswerBeforeOurEchoIsNotOurs() {
        val line = KkLine()
        val c = SsmClient(line)
        c.read(eight)!!
        // the answer to an earlier request with other values, then ours
        line.stale = line.frame(byteArrayOf(0xE8.toByte()) + ByteArray(8) { 0x55 })
        assertArrayEquals(ByteArray(8) { (it + 1).toByte() }, c.read(eight))
    }

    @Test fun aLostPartIsAskedOnceMore() {
        val addrs = IntArray(25) { it + 1 }
        // parts of 8, 8, 8 and 1; the second 8 lost once and asked again
        val ecu2 = Lossy(setOf(2))
        val c2 = SsmClient(ecu2)
        c2.presetReadLimit(8)
        assertArrayEquals(ByteArray(25) { (it + 1).toByte() }, c2.read(addrs))
        assertEquals(5, ecu2.requests)
    }

    @Test fun twiceLostIsLost() {
        val ecu = Lossy(setOf(2, 3))
        val c = SsmClient(ecu)
        c.presetReadLimit(8)
        assertNull(c.read(IntArray(16) { it + 1 }))
    }

    @Test fun aFirstLongRequestIsNotRepeated() {
        // never answered as many: maybe over the unit's limit, the halving in read() sorts that out
        val ecu = Lossy(setOf(1))
        val c = SsmClient(ecu)
        assertNull(c.read(IntArray(40) { it + 1 }))
        assertEquals(1, ecu.requests)
    }

    @Test fun theLearntLimitCarriesOver() {
        val ecu = Lossy(emptySet())
        val c = SsmClient(ecu)
        c.presetReadLimit(8)
        assertEquals(8, c.ecuReadLimit)
        c.read(IntArray(25) { it + 1 })!!
        assertEquals(4, ecu.requests)
        // a limit learnt already is not overwritten
        c.presetReadLimit(32)
        assertEquals(8, c.ecuReadLimit)
    }

    @Test fun theTransmissionInterruptsTheStreamEveryFewSeconds() {
        assertEquals(3000L, EcuService.fastTcuGapMs(3))
        assertEquals(3000L, EcuService.fastTcuGapMs(10))
        assertEquals(7500L, EcuService.fastTcuGapMs(30))
        assertEquals(3000L, EcuService.fastTcuGapMs(0))
    }
}
