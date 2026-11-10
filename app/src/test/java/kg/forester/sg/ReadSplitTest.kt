package kg.forester.sg

import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An engine unit that never answers a request with more than [limit] addresses (a Legacy GT 2004 and 64). */
private class LimitedEcu(private val limit: Int) : SsmTransport {
    override val name = "fake"
    override val isCan = false
    var requests = 0
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        requests++
        val n = (body.size - 2) / 3
        if (n > limit) return null
        return byteArrayOf(0xE8.toByte()) + ByteArray(n) { (body[4 + it * 3].toInt() and 0xFF).toByte() }
    }
    override fun close() {}
}

class ReadSplitTest {
    private val addrs = IntArray(64) { it + 1 }

    @Test fun longRequestsSplitAfterTwoSilentOnes() {
        val ecu = LimitedEcu(40)
        val c = SsmClient(ecu)
        assertNull(c.read(addrs)) // one miss can be a lost answer
        val r = c.read(addrs)!!   // the second: half as many per request
        assertArrayEquals(ByteArray(64) { (it + 1).toByte() }, r)
        assertEquals(32, c.ecuReadLimit)
        ecu.requests = 0
        c.read(addrs)!!
        assertEquals(2, ecu.requests)
    }

    @Test fun aUnitThatTakesAllKeepsOneRequest() {
        val ecu = LimitedEcu(64)
        val c = SsmClient(ecu)
        repeat(3) { c.read(addrs)!! }
        assertEquals(0, c.ecuReadLimit)
        assertEquals(3, ecu.requests)
    }

    @Test fun theTransmissionIsNotSplit() {
        val c = SsmClient(LimitedEcu(4))
        repeat(3) { assertNull(c.read(IntArray(10) { it }, SsmClient.TCU)) }
        assertEquals(0, c.ecuReadLimit)
    }
}
