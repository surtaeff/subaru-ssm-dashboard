package kg.forester.sg

import kg.forester.sg.data.BoxCan
import kg.forester.sg.data.BoxCanBus
import kg.forester.sg.data.CanBits
import kg.forester.sg.ssm.ByteLink
import kg.forester.sg.ssm.CanBitChannel
import kg.forester.sg.ssm.CanBitrates
import kg.forester.sg.ssm.CanBus
import kg.forester.sg.ssm.CanFrame
import kg.forester.sg.ssm.CanLogFrame
import kg.forester.sg.ssm.CanLogRecorder
import kg.forester.sg.ssm.CanLogs
import kg.forester.sg.ssm.CanMonitor
import kg.forester.sg.ssm.CanSniff
import kg.forester.sg.ssm.Slcan
import kg.forester.sg.service.SlcanLinkSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the CAN branches meet: one frame model and one hub (CanBus.dispatch) for every adapter, a replayed log, the
 * recorder and the capture; one bit rate table; the sniffer's bit selection as a bit-level channel; listen-only slcan.
 */
class CanIntegrationTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun hubReachesMonitorAndListenersWithBusAndFd() {
        CanMonitor.clear()
        val got = ArrayList<Triple<CanFrame, Int?, Long>>()
        val l = CanBus.Listener { f, mux, now -> got += Triple(f, mux, now) }
        CanBus.listeners += l
        try {
            val fd = CanFrame(0x123, false, ByteArray(24) { it.toByte() }, bus = 1, fd = true)
            CanBus.dispatch(fd, 1000)
            CanBus.dispatch(CanFrame(0x18FEF100, true, bytes(1, 2)), 1010)
            assertEquals(2, got.size)
            assertEquals(1, got[0].first.bus)
            assertTrue(got[0].first.fd)
            assertNull(got[0].second)
            CanMonitor.publish(1010)
            val rows = CanMonitor.rows.value
            assertEquals(2, rows.size)
            val r = rows.first { it.id == 0x123 }
            assertEquals(1, r.bus); assertTrue(r.fd); assertEquals(24, r.data.size)
            // the key keeps the bus apart from the id
            assertEquals(Triple(0x123, false, null as Int?), CanMonitor.unkey(r.key))
            assertEquals(1, CanMonitor.busOf(r.key))
            assertEquals(Triple(0x18FEF100, true, 7 as Int?), CanMonitor.unkey(CanMonitor.key(0x18FEF100, true, 7, 2)))
        } finally {
            CanBus.listeners -= l
            CanMonitor.clear()
        }
    }

    @Test fun logFramesAreCanFramesAndKeepBusAndFd() {
        val f = CanLogFrame(5_000_000L, 0x7E8, false, ByteArray(12), bus = 2)
        val c: CanFrame = f
        assertTrue(c.fd)
        assertEquals(2, c.bus)
        assertEquals(5_000_000L, c.timeUs)
        val dir = java.nio.file.Files.createTempDirectory("canlog").toFile()
        try {
            val rec = CanLogRecorder(File(dir, "r.csv"))
            rec.add(CanFrame(0x123, false, ByteArray(16) { 1 }, bus = 1, fd = true), 1000L)
            rec.add(CanFrame(0x3E8, false, bytes(1, 2, 3)), 1001L)
            rec.close()
            val back = CanLogs.read(rec.file).toList()
            assertEquals(2, back.size)
            assertEquals(1, back[0].bus); assertTrue(back[0].fd); assertEquals(16, back[0].data.size)
            assertEquals(0, back[1].bus); assertFalse(back[1].fd)
        } finally { dir.deleteRecursively() }
    }

    @Test fun replayGoesThroughTheHub() {
        CanMonitor.clear()
        val seen = ArrayList<CanFrame>()
        val l = CanBus.Listener { f, _, _ -> seen += f }
        CanBus.listeners += l
        try {
            // what CanReplay plays: its sink is CanBus.dispatch
            kg.forester.sg.ssm.CanReplay.sink(CanLogFrame(0L, 0x5A0, false, bytes(0xAA)), 2000)
            assertEquals(1, seen.size)
            CanMonitor.publish(2000)
            assertEquals(0x5A0, CanMonitor.rows.value.single().id)
        } finally {
            CanBus.listeners -= l
            CanMonitor.clear()
        }
    }

    @Test fun oneBitRateTable() {
        // can-transport offered 50 and 100, can-tools 33.3 and 83.3: all in the one table
        assertTrue(CanBitrates.CHOICES.containsAll(listOf(33, 50, 83, 100, 125, 250, 500, 1000)))
        assertTrue(CanBitrates.PROBE.containsAll(listOf(50, 100)))
        CanBitrates.CHOICES.forEach { assertNotNull("$it", CanBitrates.slcanCommand(it)) }
        assertEquals("S2", Slcan.speedCommand(50))
        assertEquals("s4E1C", Slcan.speedCommand(33))
        assertEquals(83_333, CanBitrates.bps(83))
    }

    @Test fun bitSelectionBecomesABitLevelChannel() {
        val d = bytes(0x34, 0x12, 0xF0, 0x00)
        // a nibble (Intel, byte 2 bits 4–7): no whole bytes, so start bit + length
        val nib = CanSniff.select(23, 20, bigEndian = false)
        val c = CanBitChannel.channel(CanBitChannel.Request(0x3E8, false, null, nib), "C9")
        assertTrue(c.bits)
        assertEquals(20, c.startBit); assertEquals(4, c.bitLen); assertFalse(c.bigEndian)
        assertEquals(-1, c.mux)
        assertEquals(nib.raw(d), c.raw(d))
        assertEquals(15.0, c.decode(d)!!, 0.0)
        // a 12-bit Motorola run: the same DBC numbering as CanBits
        val m12 = CanSniff.select(3, 8, bigEndian = true)
        val cm = CanBitChannel.channel(CanBitChannel.Request(0x3E8, false, 2, m12), "C10")
        assertTrue(cm.bits); assertTrue(cm.bigEndian)
        assertEquals(m12.raw(d), CanBits.get(d, cm.startBit, cm.bitLen, true, false))
        assertEquals(2, cm.mux)
        assertEquals(cm, kg.forester.sg.data.CanChannel.fromJson(cm.toJson()))
        // whole bytes stay the byte model, past the eighth too (a CAN FD frame)
        val whole = CanBitChannel.channel(CanBitChannel.Request(0x123, false, null, CanSniff.BitSel(80, 16, false)), "C11")
        assertFalse(whole.bits)
        assertEquals(10, whole.byte); assertEquals(2, whole.len)
    }

    private class FakeLink : ByteLink {
        val sent = ArrayList<String>()
        override val label = "fake"
        override fun read(buf: ByteArray, timeoutMs: Int): Int = 0.also { Thread.sleep(minOf(timeoutMs, 5).toLong()) }
        override fun write(b: ByteArray) { sent += String(b, Charsets.US_ASCII).trimEnd('\r') }
        override fun close() {}
    }

    @Test fun slcanOnALinkListensOnlyWhenAsked() {
        val quiet = FakeLink()
        val src = SlcanLinkSource(quiet)
        // no answer to "L" is no refusal: open, but nothing may be sent
        assertTrue(src.open(500, listenOnly = true))
        assertTrue("L" in quiet.sent)
        assertFalse("O" in quiet.sent)
        assertFalse(src.canTransmit)
        val normal = FakeLink()
        val n = SlcanLinkSource(normal)
        assertTrue(n.open(83))
        assertTrue("s451C" in normal.sent)
        assertTrue("O" in normal.sent)
        assertTrue(n.canTransmit)
    }

    @Test fun sensorBoxFramesReachTheHub() {
        CanMonitor.clear()
        val seen = ArrayList<CanFrame>()
        val l = CanBus.Listener { f, _, _ -> seen += f }
        CanBus.listeners += l
        try {
            // no CAN session on the box: straight to the hub (a standard id through the Subaru broadcast table first)
            BoxCanBus.onFrame(BoxCan.parseLine("T18DAF1104A1B2C3D4")!!, 1000)
            BoxCanBus.onFrame(BoxCan.parseLine("t3E82AABB")!!, 1001)
            assertEquals(listOf(0x18DAF110, 0x3E8), seen.map { it.id })
            // a session on it: the frames wait for its source, which only listens
            BoxCanBus.Source().use { src ->
                assertEquals("SensorBox", src.title)
                assertFalse(src.canTransmit)
                BoxCanBus.onFrame(BoxCan.parseLine("t1238DEADBEEF00112233")!!, 1002)
                val got = src.read(50)
                assertEquals(0x123, got.single().id)
                assertEquals(8, got.single().len)
            }
            assertEquals(2, seen.size)
        } finally {
            CanBus.listeners -= l
            CanMonitor.clear()
        }
    }
}
