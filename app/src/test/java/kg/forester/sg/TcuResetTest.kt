package kg.forester.sg

import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.KLineTransport
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTransport
import kg.forester.sg.ssm.TcuReset
import kg.forester.sg.ssm.TcuReset.Block
import kg.forester.sg.ssm.TcuReset.Stage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transmission unit on a fake line: answers reads from [mem], writes with [echo] (null: silence). */
private class FakeTcu(var echo: ((Int) -> ByteArray?)? = { v -> byteArrayOf(0xF8.toByte(), v.toByte()) }) : SsmTransport {
    override val name = "fake"
    override val isCan = false
    val mem = HashMap<Int, Int>()
    val sent = ArrayList<Pair<Int, ByteArray>>()
    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        sent += dest to body
        return when (body[0]) {
            0xA8.toByte() -> {
                val n = (body.size - 2) / 3
                byteArrayOf(0xE8.toByte()) + ByteArray(n) { i ->
                    val a = ((body[2 + i * 3].toInt() and 0xFF) shl 16) or ((body[3 + i * 3].toInt() and 0xFF) shl 8) or (body[4 + i * 3].toInt() and 0xFF)
                    (mem[a] ?: 0).toByte()
                }
            }
            0xB8.toByte() -> {
                val a = ((body[1].toInt() and 0xFF) shl 16) or ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
                val v = body[4].toInt() and 0xFF
                echo?.invoke(v)?.also { if (it.size == 2 && it[1].toInt() and 0xFF == v) mem[a] = v }
            }
            else -> null
        }
    }
    override fun close() {}
}

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

/** A unit whose flag bytes (after the ROM id) have [bits] set: (EcuInit.supports byte index, bit). */
private fun tcu(vararg bits: Pair<Int, Int>, ssmId: String = "A01B00", romId: String = "D3A0C04007"): EcuInit {
    val caps = ByteArray(48)
    for ((i, bit) in bits) caps[i - 8] = (caps[i - 8].toInt() or (1 shl bit)).toByte()
    return EcuInit(ssmId, romId, caps)
}

private val CM2 = 47 to 1

class TcuResetTest {
    @Test fun theFrameOnTheKLine() {
        // 80 18 F0 05 | B8 00 00 60 20 | checksum: the low byte of the sum
        assertArrayEquals(bytes(0x80, 0x18, 0xF0, 0x05, 0xB8, 0x00, 0x00, 0x60, 0x20, 0xC5), KLineTransport.packet(SsmClient.TCU, TcuReset.body()))
        // the codes' own clear (level 1) differs only in the value
        assertArrayEquals(bytes(0x80, 0x18, 0xF0, 0x05, 0xB8, 0x00, 0x00, 0x60, 0x40, 0xE5),
            KLineTransport.packet(SsmClient.TCU, SsmClient.writeBody(TcuReset.ADDR, TcuReset.CLEAR_MEMORY)))
        assertEquals(0x20, TcuReset.CLEAR_MEMORY_2)
        assertEquals(0x000060, TcuReset.ADDR)
    }

    @Test fun onlyTheSameByteBackCounts() {
        assertTrue(TcuReset.accepted(0x20))
        assertFalse(TcuReset.accepted(0x40))
        assertFalse(TcuReset.accepted(0x00))
        assertFalse(TcuReset.accepted(null))
    }

    @Test fun theWriteGoesToTheTransmissionAndItsEchoIsChecked() {
        val t = FakeTcu()
        assertEquals(0x20, TcuReset.clear(SsmClient(t)))
        assertEquals(1, t.sent.size)
        assertEquals(SsmClient.TCU, t.sent[0].first)
        assertArrayEquals(bytes(0xB8, 0x00, 0x00, 0x60, 0x20), t.sent[0].second)
        // another byte taken, a malformed answer, no answer
        t.echo = { bytes(0xF8, 0x40) }
        assertFalse(TcuReset.accepted(TcuReset.clear(SsmClient(t))))
        t.echo = { bytes(0xE8, 0x20) }
        assertNull(TcuReset.clear(SsmClient(t)))
        t.echo = null
        assertNull(TcuReset.clear(SsmClient(t)))
    }

    @Test fun level2OnlyWhereTheFlagBitSaysSo() {
        // FreeSSM: flag byte 39 bit 1 of the flag bytes = 47/1 in EcuInit numbering
        assertTrue(TcuReset.supported(tcu(CM2)))
        assertFalse(TcuReset.supported(tcu(47 to 0, 47 to 2)))
        assertFalse(TcuReset.supported(EcuInit("A01B00", "D3A0C04007", ByteArray(32))))
        assertFalse(TcuReset.supported(null))
    }

    @Test fun whenItIsOffered() {
        val ok = TcuReset.Line(tcu = tcu(CM2), rpm = 0, speedKmh = 0, range = "P/N")
        assertNull(TcuReset.block(ok))
        // readings the app lacks do not block
        assertNull(TcuReset.block(ok.copy(rpm = null, speedKmh = null, range = null)))
        assertNull(TcuReset.block(ok.copy(range = "P")))
        assertNull(TcuReset.block(ok.copy(range = "N")))
        assertEquals(Block.DEMO, TcuReset.block(ok.copy(demo = true)))
        assertEquals(Block.OBD, TcuReset.block(ok.copy(obd = true)))
        assertEquals(Block.ELM, TcuReset.block(ok.copy(elm = true)))
        assertEquals(Block.SHARED, TcuReset.block(ok.copy(shared = true)))
        assertEquals(Block.OTHER_TESTER, TcuReset.block(ok.copy(otherTester = true)))
        assertEquals(Block.NO_LINK, TcuReset.block(ok.copy(connected = false)))
        assertEquals(Block.NO_TCU, TcuReset.block(ok.copy(tcu = null)))
        assertEquals(Block.NO_CM2, TcuReset.block(ok.copy(tcu = tcu())))
        assertEquals(Block.MOVING, TcuReset.block(ok.copy(speedKmh = 3)))
        assertEquals(Block.ENGINE, TcuReset.block(ok.copy(rpm = 750)))
        assertEquals(Block.RANGE, TcuReset.block(ok.copy(range = "D")))
        assertEquals(Block.RANGE, TcuReset.block(ok.copy(range = "R")))
        // the demo first: its made-up unit is no reason to talk about the lever
        assertEquals(Block.DEMO, TcuReset.block(ok.copy(demo = true, range = "D", rpm = 800, tcu = null)))
        // a shared line is said as such even when another tester talks on it
        assertEquals(Block.SHARED, TcuReset.block(ok.copy(shared = true, otherTester = true)))
    }

    @Test fun freeSsmAdjustmentsByFlagAndSsmId() {
        assertEquals(listOf("Line Pressure Correction"), TcuReset.adjustments(tcu(33 to 0)).map { it.title })
        // the six gear shift corrections only for SSM id A11020
        assertEquals(6, TcuReset.adjustments(tcu(ssmId = "A11020")).size)
        assertEquals(0, TcuReset.adjustments(tcu()).size)
        val all = TcuReset.adjustments(tcu(33 to 0, 34 to 7, 34 to 6, 34 to 5, 34 to 4, 34 to 3, 34 to 2, ssmId = "A11020"))
        assertEquals(TcuReset.ADJUSTMENTS, all)
        // the two-byte one: low byte first, as FreeSSM reads it
        val awd = TcuReset.adjustments(tcu(34 to 2))
        assertArrayEquals(intArrayOf(0x171, 0x170), TcuReset.addresses(awd))
        assertArrayEquals(intArrayOf(0x0201), TcuReset.values(awd, bytes(0x01, 0x02)))
    }

    @Test fun restoreOfferedLikeFreeSsm() {
        val adj = TcuReset.adjustments(tcu(33 to 0, 34 to 4, 34 to 2))
        assertFalse(TcuReset.offerRestore(adj, intArrayOf(100, 0, 0)))  // all default: nothing to give back
        assertTrue(TcuReset.offerRestore(adj, intArrayOf(104, 0, 0)))
        assertTrue(TcuReset.offerRestore(adj, intArrayOf(100, 0, 65000))) // the AWD torque's range wraps
        assertFalse(TcuReset.offerRestore(adj, intArrayOf(104, 0, 3000))) // one out of range: FreeSSM offers nothing
        assertFalse(TcuReset.offerRestore(adj, intArrayOf(120, 0, 0)))
        assertEquals(listOf(0x72 to 104, 0x81 to 1, 0x171 to 0xE8, 0x170 to 0xFD), TcuReset.restoreWrites(adj, intArrayOf(104, 1, 65000)))
    }

    @Test fun adjustmentsReadBeforeTheWriteAndPutBack() {
        val t = FakeTcu()
        t.mem[0x72] = 104
        val c = SsmClient(t)
        val adj = TcuReset.adjustments(tcu(33 to 0))
        val old = TcuReset.readAdjustments(c, adj)!!
        assertArrayEquals(intArrayOf(104), old)
        TcuReset.clear(c)
        assertEquals(0xA8.toByte(), t.sent[0].second[0])
        assertEquals(0xB8.toByte(), t.sent[1].second[0])
        // the clear made it default; FreeSSM writes the old one back and reads to check
        t.mem[0x72] = 100
        assertTrue(TcuReset.restore(c, adj, old))
        assertEquals(104, t.mem[0x72])
        // a unit that takes the write but keeps another value
        t.echo = { bytes(0xF8, 0x64) }
        assertFalse(TcuReset.restore(c, adj, old))
        // silence before the write: nothing is sent
        t.echo = null
        val silent = object : SsmTransport {
            override val name = "silent"
            override val isCan = false
            override fun exchange(dest: Int, body: ByteArray): ByteArray? = null
            override fun close() {}
        }
        assertNull(TcuReset.readAdjustments(SsmClient(silent), adj))
        assertArrayEquals(IntArray(0), TcuReset.readAdjustments(SsmClient(silent), emptyList()))
    }

    @Test fun theIgnitionCycleAfterTheWrite() {
        val before = tcu(CM2)
        val cy = TcuReset.Cycle(before)
        assertEquals(Stage.IGNITION_OFF, cy.stage(true, before, 0))
        assertEquals(Stage.IGNITION_OFF, cy.stage(true, before, 3000))
        // the link lost: FreeSSM's 5 s from there
        assertEquals(Stage.WAIT, cy.stage(false, before, 4000))
        assertEquals(5, cy.waitLeftS(4000))
        assertEquals(Stage.WAIT, cy.stage(false, before, 8999))
        assertEquals(Stage.IGNITION_ON, cy.stage(false, before, 9000))
        // the engine unit back but the transmission not set up again yet
        assertEquals(Stage.IGNITION_ON, cy.stage(true, before, 10_000))
        // a new init with the same ids: the same unit
        assertEquals(Stage.SAME_UNIT, cy.stage(true, tcu(CM2), 11_000))
        assertEquals(Stage.OTHER_UNIT, cy.stage(true, tcu(CM2, romId = "D3A0C04008"), 11_000))
        assertEquals(Stage.OTHER_UNIT, cy.stage(true, tcu(CM2, ssmId = "A01B01"), 11_000))
    }

    @Test fun anInitFromBeforeTheSilenceIsNoAnswer() {
        val before = tcu(CM2)
        val cy = TcuReset.Cycle(before)
        // the service set the unit up again before the ignition went off (it had gone quiet a moment)
        val again = tcu(CM2)
        assertEquals(Stage.WAIT, cy.stage(false, again, 1000))
        assertEquals(Stage.IGNITION_ON, cy.stage(true, again, 7000))
        assertEquals(Stage.SAME_UNIT, cy.stage(true, tcu(CM2), 8000))
    }
}
