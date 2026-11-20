package kg.forester.sg

import kg.forester.sg.ssm.KLineTransport
import kg.forester.sg.ssm.SsmClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A CH340 KKL cable (1A86:7523) on an NWD unit, Forester ROM 4244307006, 1.0-beta12: the bytes are the log's. A new
 * connection heard a request of ours left in the chip and went quiet for «another tester»; the chip lost our echoes
 * while the ECU's answers came, and each was a timeout.
 */
class KLineLeftoverTest {
    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Echoes each request unless [echo] is off; the ECU answers a read with the low byte of each address, or [next]
     * when set, [answerMs] after the request.
     */
    private class Ch340 : KLineTransport() {
        override val name = "fake CH340"
        override val quickAnswers get() = true
        @Volatile var echo = true
        @Volatile var answerMs = 0L
        @Volatile var next: ByteArray? = null
        val sent = ArrayList<ByteArray>()

        fun hear(b: ByteArray) = onBytes(b)

        override fun writeRaw(packet: ByteArray) {
            sent += packet
            if (echo) onBytes(packet)
            val body = packet.copyOfRange(4, packet.size - 1)
            val answer = next?.also { next = null } ?: run {
                if (body[0] != 0xA8.toByte()) return
                val f = byteArrayOf(0x80.toByte(), 0xF0.toByte(), SsmClient.ECU.toByte(), (1 + (body.size - 2) / 3).toByte(), 0xE8.toByte()) +
                    ByteArray((body.size - 2) / 3) { body[4 + it * 3] } + 0
                f[f.size - 1] = checksum(f, f.size - 1)
                f
            }
            if (answerMs == 0L) onBytes(answer) else Thread { Thread.sleep(answerMs); onBytes(answer) }.start()
        }

        override fun close() {}
    }

    @Before fun fresh() = KLineTransport.forgetOwn()

    @Test fun ourRequestLeftInTheChipIsNotAnotherTester() {
        // the connection before polled these 7 addresses
        val before = Ch340()
        SsmClient(before).read(intArrayOf(0x14, 0x15, 0x1C, 0x20, 0x22, 0x23, 0x24))!!
        val leftover = hex("80 10 F0 17 A8 00 00 00 14 00 00 15 00 00 1C 00 00 20 00 00 22 00 00 23 00 00 24 0D")
        assertArrayEquals(leftover, before.sent.last())
        // 50 ms after the next open, before any request of its own
        val after = Ch340()
        after.hear(leftover)
        assertFalse(after.passive)
        after.hear(leftover)
        assertFalse(after.passive)
        assertNotNull(SsmClient(after).read(intArrayOf(0x08)))
    }

    @Test fun oneStrayRequestIsNotAnotherTester() {
        val line = Ch340()
        val stray = hex("80 10 F0 17 A8 00 00 00 3A 00 00 FA 00 00 2E 00 00 18 00 00 19 00 00 28 00 00 65 5F")
        line.hear(stray)
        assertFalse(line.passive)
        // two in one burst are still one hearing
        line.hear(stray + stray)
        assertFalse(line.passive)
        // and the line stays ours
        assertNotNull(SsmClient(line).read(intArrayOf(0x08)))
        assertTrue(line.sent.isNotEmpty())
    }

    @Test fun aTesterThatKeepsAskingIsHeard() {
        val line = Ch340()
        val ask = hex("80 10 F0 17 A8 00 00 00 3A 00 00 FA 00 00 2E 00 00 18 00 00 19 00 00 28 00 00 65 5F")
        line.hear(ask)
        Thread.sleep(KLineTransport.FOREIGN_SPACING_MS + 30)
        line.hear(ask)
        assertTrue(line.passive)
        assertNull(SsmClient(line).read(intArrayOf(0x08)))
        assertTrue(line.sent.isEmpty())
    }

    @Test fun garbledEchoesAreNoTester() {
        val line = Ch340()
        // our 23-address request with bytes lost in the middle, the ECU's answer right behind it
        line.hear(hex("80 10 F0 47 A8 00 00 00 08 00 00 0E 00 00 0F 00 00 10 00 00 11 00 00 12 00 00 13 00 00 14 00 00 15 00 00 1C " +
            "00 00 20 00 00 22 00 00 23 00 00 24 00 00 09 00 00 0A 00 00 46 00 00 30 00 00 29 00 80 F0 10 18 E8 7F 2A C4 60 CA 32 07 " +
            "32 29 B0 0E 80 61 4C 82 7F 7F FF 31 2D 32 00 8C 31"))
        assertFalse(line.passive)
        // a frame whose checksum fits by chance but is no read request (half an address)
        val odd = byteArrayOf(0x80.toByte(), 0x10, 0xF0.toByte(), 0x04, 0xA8.toByte(), 0x00, 0x00, 0x00, 0x00)
        odd[8] = KLineTransport.checksum(odd, 8)
        line.hear(odd)
        Thread.sleep(KLineTransport.FOREIGN_SPACING_MS + 30)
        line.hear(odd)
        assertFalse(line.passive)
    }

    @Test fun anAnswerWhoseEchoTheChipLostIsTaken() {
        val line = Ch340()
        val c = SsmClient(line)
        // the cable echoes: seen on the first request
        c.read(intArrayOf(0x08))!!
        line.echo = false
        line.answerMs = 120
        // 8 addresses: no echo at all, the answer whole
        line.next = hex("80 F0 10 09 E8 82 26 A6 55 D2 35 01 BB D7")
        assertArrayEquals(hex("82 26 A6 55 D2 35 01 BB"), c.read(intArrayOf(0x08, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14)))
        assertArrayEquals(hex("80 10 F0 1A A8 00 00 00 08 00 00 0E 00 00 0F 00 00 10 00 00 11 00 00 12 00 00 13 00 00 14 C1"), line.sent.last())
        // 7 addresses: no echo and the answer's header lost too
        line.next = hex("E8 81 26 05 54 D2 35 02 79")
        assertArrayEquals(hex("81 26 05 54 D2 35 02"), c.read(intArrayOf(0x08, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13)))
        // 8 addresses, the answer's first byte lost
        line.next = hex("F0 10 09 E8 81 25 8F 54 CE 35 05 02 04")
        assertArrayEquals(hex("81 25 8F 54 CE 35 05 02"), c.read(intArrayOf(0x08, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14)))
        assertFalse(line.passive)
    }

    @Test fun anAnswerBehindAPieceOfOurEchoIsTaken() {
        val line = Ch340()
        val c = SsmClient(line)
        c.read(intArrayOf(0x08))!!
        line.echo = false
        line.answerMs = 250
        // the head of our 23-address request lost, its tail and the answer came
        line.next = hex("15 00 00 1C 00 00 20 00 00 22 00 00 23 00 00 24 00 00 09 00 00 0A 00 00 46 00 00 30 00 00 29 00 00 0D 00 00 3C " +
            "00 00 3A 00 00 FA D7 80 F0 10 18 E8 7F 2A 8D 5F C6 32 09 B5 2F B1 13 80 61 5A 84 80 7F FF 3D 3A 34 00 8D B3")
        val addrs = intArrayOf(0x08, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x1C, 0x20, 0x22, 0x23, 0x24, 0x09, 0x0A, 0x46, 0x30, 0x29, 0x0D, 0x3C, 0x3A, 0xFA)
        assertArrayEquals(hex("7F 2A 8D 5F C6 32 09 B5 2F B1 13 80 61 5A 84 80 7F FF 3D 3A 34 00 8D"), c.read(addrs))
    }

    @Test fun anAnswerTooEarlyForOurRequestIsNotTaken() {
        val line = Ch340()
        val c = SsmClient(line)
        c.read(intArrayOf(0x08, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14))!!
        line.echo = false
        // no echo, and an answer at once: the line could not have carried our request yet, it is an earlier one's
        line.next = hex("80 F0 10 09 E8 82 26 A6 55 D2 35 01 BB D7")
        assertNull(c.read(intArrayOf(0x08, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14)))
    }
}
