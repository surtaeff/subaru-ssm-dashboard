package kg.forester.sg

import kg.forester.sg.data.CanBusGuide
import kg.forester.sg.ssm.CanBaudProbe
import kg.forester.sg.ssm.CanBitrates
import kg.forester.sg.ssm.CanLogFormat
import kg.forester.sg.ssm.CanLogFrame
import kg.forester.sg.ssm.CanLogRecorder
import kg.forester.sg.ssm.CanLogs
import kg.forester.sg.ssm.CanMonitor
import kg.forester.sg.ssm.CanSniff
import kg.forester.sg.ssm.Candump
import kg.forester.sg.ssm.GvretCsv
import kg.forester.sg.ssm.SlcanIo
import kg.forester.sg.ssm.SlcanListen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.io.StringWriter

class CanToolsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val frames = listOf(
        CanLogFrame(1_000_000L, 0x3EC, false, bytes(0x01, 0x02, 0xFF)),
        CanLogFrame(1_000_250L, 0x18DAF110, true, bytes(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80)),
        CanLogFrame(1_001_000L, 0x7E8, false, ByteArray(0), bus = 1),
        CanLogFrame(1_002_000L, 0x123, false, ByteArray(64) { it.toByte() }), // CAN FD
        CanLogFrame(1_003_000L, 0x5A0, false, bytes(0xAA), tx = true),
    )

    // ---------------------------------------------------------------- GVRET CSV (SavvyCAN)

    @Test
    fun gvretLineAsSavvyCanWritesIt() {
        assertEquals("1000000,000003EC,false,Rx,0,3,01,02,FF,", GvretCsv.line(frames[0]))
        assertEquals("1000250,18DAF110,true,Rx,0,8,10,20,30,40,50,60,70,80,", GvretCsv.line(frames[1]))
        assertEquals("1003000,000005A0,false,Tx,0,1,AA,", GvretCsv.line(frames[4]))
    }

    @Test
    fun gvretRoundTrip() {
        val w = StringWriter()
        GvretCsv.write(w, frames)
        assertTrue(w.toString().startsWith(GvretCsv.HEADER + "\n"))
        val back = CanLogs.read(BufferedReader(StringReader(w.toString()))).toList()
        assertEquals(frames, back)
        assertTrue(back[3].fd)
        assertEquals(64, back[3].data.size)
    }

    @Test
    fun gvretOlderHeaderWithoutDir() {
        val text = "Time Stamp,ID,Extended,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8\n" +
            "5000,000007E8,false,0,2,41,0C\n" +
            "broken,line\n" +
            "6000,1FFFFFFF,true,2,1,7F\n"
        val l = CanLogs.read(BufferedReader(StringReader(text))).toList()
        assertEquals(2, l.size)
        assertEquals(0x7E8, l[0].id)
        assertArrayEquals(bytes(0x41, 0x0C), l[0].data)
        assertTrue(l[1].ext)
        assertEquals(2, l[1].bus)
        assertFalse(l[1].tx)
    }

    @Test
    fun gvretRejectsGarbage() {
        assertNull(GvretCsv.parse(GvretCsv.HEADER))
        assertNull(GvretCsv.parse("1,XYZ,false,Rx,0,1,00,"))
        assertNull(GvretCsv.parse("1,123,false,Rx,0,4,00,01,")) // fewer bytes than LEN
        assertNull(GvretCsv.parse("1,123,false,Rx,0,1,1FF,"))
    }

    // ---------------------------------------------------------------- candump

    @Test
    fun candumpLines() {
        assertEquals("(1.000000) can0 3EC#0102FF", Candump.line(frames[0]))
        assertEquals("(1.000250) can0 18DAF110#1020304050607080", Candump.line(frames[1]))
        assertEquals("(1.001000) can1 7E8#", Candump.line(frames[2]))
        assertTrue(Candump.line(frames[3]).startsWith("(1.002000) can0 123##0000102"))
    }

    @Test
    fun candumpRoundTrip() {
        val w = StringWriter()
        Candump.write(w, frames)
        val back = CanLogs.read(BufferedReader(StringReader(w.toString()))).toList()
        // candump has no direction: the Tx frame comes back as received
        assertEquals(frames.map { CanLogFrame(it.timeUs, it.id, it.ext, it.data, it.bus, false, it.fd) }, back)
    }

    @Test
    fun candumpRealWorldLines() {
        val f = Candump.parse("(1436509052.249713) vcan0 044#2A366C2BBA")!!
        assertEquals(1436509052249713L, f.timeUs)
        assertEquals(0x44, f.id)
        assertFalse(f.ext)
        assertArrayEquals(bytes(0x2A, 0x36, 0x6C, 0x2B, 0xBA), f.data)
        assertNull(Candump.parse("(1436509052.249713) can0 123#R")) // remote request
        val fd = Candump.parse("(0.5) can0 123##1" + "00".repeat(12))!!
        assertTrue(fd.fd)
        assertEquals(12, fd.data.size)
        assertEquals(500_000L, fd.timeUs)
        // candump's screen output, as people paste it
        val s = Candump.parse("  can0  1F334455   [4]  DE AD BE EF")!!
        assertEquals(0x1F334455, s.id)
        assertTrue(s.ext)
        assertArrayEquals(bytes(0xDE, 0xAD, 0xBE, 0xEF), s.data)
        assertNull(Candump.parse("  can0  123   [4]  DE AD"))
    }

    @Test
    fun formatDetectedAndConverted() {
        assertEquals(CanLogFormat.GVRET_CSV, CanLogs.detect(listOf("", GvretCsv.HEADER)))
        assertEquals(CanLogFormat.CANDUMP, CanLogs.detect(listOf("(1.0) can0 123#00")))
        assertNull(CanLogs.detect(listOf("hello", "world")))
        val dir = createTempDir()
        try {
            val csv = File(dir, "a.csv").also { f -> f.bufferedWriter().use { GvretCsv.write(it, frames) } }
            val log = File(dir, "a.log")
            CanLogs.convert(csv, CanLogFormat.CANDUMP, log)
            assertEquals(CanLogFormat.CANDUMP, CanLogs.detect(log.readLines().take(3)))
            val again = File(dir, "b.csv")
            CanLogs.convert(log, CanLogFormat.GVRET_CSV, again)
            assertEquals(frames.map { it.data.toList() }, CanLogs.read(again).map { it.data.toList() }.toList())
            val sum = CanLogs.summary(again)
            assertEquals(5, sum.frames)
            assertEquals(5, sum.ids)
            assertEquals(3, sum.durationMs)
            assertEquals(CanLogFormat.GVRET_CSV, sum.format)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun recorderWritesReadableCsv() {
        val dir = createTempDir()
        try {
            val r = CanLogRecorder(File(dir, "rec.csv"))
            r.add(0x360, false, bytes(1, 2, 3), 1000L)
            r.add(0x1ABCDEF, true, bytes(9), 1005L)
            r.close()
            r.add(0x361, false, bytes(1), 1010L) // after close: dropped
            assertEquals(2, r.frames)
            val l = CanLogs.read(r.file).toList()
            assertEquals(2, l.size)
            assertEquals(1_000_000L, l[0].timeUs)
            assertEquals(0x1ABCDEF, l[1].id)
            assertTrue(l[1].ext)
        } finally { dir.deleteRecursively() }
    }

    // ---------------------------------------------------------------- the sniffer's arithmetic

    @Test
    fun changedBitsMarked() {
        val ms = LongArray(16)
        assertFalse(CanSniff.markChanges(bytes(1, 2), bytes(1, 2), ms, 10))
        assertTrue(CanSniff.markChanges(bytes(1, 2), bytes(3, 2), ms, 20))
        assertEquals(20L, ms[1])
        assertEquals(0L, ms[0])
        assertEquals(0L, ms[8])
        assertEquals(1f, CanSniff.fade(20, 20), 0f)
        assertEquals(0.5f, CanSniff.fade(20, 1020), 0.001f)
        assertEquals(0f, CanSniff.fade(20, 5000), 0f)
        assertEquals(0f, CanSniff.fade(0, 5000), 0f)
    }

    @Test
    fun monitorKeepsBitChangeTimes() {
        CanMonitor.clear()
        CanMonitor.feed(0x123, false, bytes(0, 0), 1000)
        CanMonitor.feed(0x123, false, bytes(0, 0x80), 1100)
        CanMonitor.publish(1100)
        val r = CanMonitor.rows.value.single()
        assertEquals(1100L, r.bitMs[15])
        assertEquals(0L, r.bitMs[0])
        assertEquals(1100L, r.changedMs)
        assertEquals(1100L, r.byteMs(1))
        assertEquals(0L, r.byteMs(0))
        assertEquals(CanMonitor.key(0x123, false, null), r.key)
        CanMonitor.clear()
    }

    @Test
    fun busLoadEstimate() {
        // 47 fixed + 64 data + half the worst-case stuffing (97 / 4 / 2 = 12)
        assertEquals(123, CanSniff.frameBits(8, false))
        assertEquals(67 + 64 + (54 + 64 - 1) / 4 / 2, CanSniff.frameBits(8, true))
        // 1000 frames/s of 8 bytes at 500 kbit: about a quarter of the bus
        assertEquals(0.246f, CanSniff.busLoad(listOf(Triple(1000f, 8, false)), 500), 0.001f)
        assertEquals(0f, CanSniff.busLoad(emptyList(), 500), 0f)
        assertEquals(83_333, CanBitrates.bps(83))
    }

    @Test
    fun bitSelectionsAsDbcSignals() {
        val d = bytes(0x34, 0x12, 0xF0, 0x00)
        // Intel 16 bits from bit 0: 0x1234
        val intel = CanSniff.select(0, 15, bigEndian = false)
        assertEquals(CanSniff.BitSel(0, 16, false), intel)
        assertEquals(0x1234L, intel.raw(d))
        assertEquals(0 to 2, intel.wholeBytes())
        // Motorola from byte 0 bit 7 down to byte 1 bit 0: 0x3412
        val moto = CanSniff.select(7, 8, bigEndian = true)
        assertEquals(CanSniff.BitSel(7, 16, true), moto)
        assertEquals(0x3412L, moto.raw(d))
        assertEquals(0 to 2, moto.wholeBytes())
        // taps in either order
        assertEquals(moto, CanSniff.select(8, 7, bigEndian = true))
        // a nibble: byte 2 bits 4–7 = 0xF, signed −1
        val nib = CanSniff.select(23, 20, bigEndian = false)
        assertEquals(CanSniff.BitSel(20, 4, false), nib)
        assertEquals(0xFL, nib.raw(d))
        assertEquals(-1L, nib.signed(d))
        assertNull(nib.wholeBytes())
        // a 12-bit Motorola signal: byte 0 bits 3–0, then byte 1: 0x412
        val m12 = CanSniff.select(3, 8, bigEndian = true)
        assertEquals(CanSniff.BitSel(3, 12, true), m12)
        assertEquals(0x412L, m12.raw(d))
        assertNull(m12.wholeBytes())
        // past the frame
        assertNull(CanSniff.BitSel(24, 16, false).raw(d))
    }

    @Test
    fun buttonCaptureLeavesNoiseOut() {
        val c = CanSniff.ChangeCapture(0, 1000, 1000)
        val counter = CanMonitor.key(0x100, false, null)
        val button = CanMonitor.key(0x200, false, null)
        val fresh = CanMonitor.key(0x300, false, null)
        // baseline: a counter in byte 0 runs, the button frame stays
        for (t in 0 until 1000 step 100) {
            c.feed(counter, bytes(t / 100 % 8, 0), t.toLong())
            c.feed(button, bytes(0, 0), t.toLong())
        }
        assertEquals(CanSniff.ChangeCapture.Phase.BASELINE, c.phase(500))
        assertEquals(1, c.left(500))
        // action: the counter runs on, the button sets byte 1 bit 2, a new id comes
        for (t in 1000 until 2000 step 100) {
            c.feed(counter, bytes(t / 100 % 8, 0), t.toLong())
            c.feed(button, bytes(0, if (t >= 1500) 4 else 0), t.toLong())
        }
        c.feed(fresh, bytes(1), 1900)
        c.feed(button, bytes(0, 0xFF), 2100) // after the window: ignored
        assertEquals(CanSniff.ChangeCapture.Phase.DONE, c.phase(2000))
        val hits = c.result()
        assertEquals(2, hits.size)
        assertEquals(button, hits[0].key)
        assertEquals(listOf(10), hits[0].bits)
        assertFalse(hits[0].newId)
        assertEquals(fresh, hits[1].key)
        assertTrue(hits[1].newId)
    }

    // ---------------------------------------------------------------- bit rate probe

    /** An slcan adapter on a bus that runs at [busKbit]: frames come only when it is open at that speed. */
    private class FakeBus(val busKbit: Int, val canL: Boolean = true, val canableFw: Boolean = false) : SlcanIo {
        val sent = ArrayList<String>()
        private val out = ArrayDeque<Byte>()
        private var kbit = 0
        var open = false
        var silent = false
        private var n = 0

        override fun write(cmd: String) {
            sent += cmd
            fun ok() = out.add('\r'.code.toByte())
            fun err() = out.add(7)
            when {
                cmd == "C" -> { open = false; ok() }
                cmd.startsWith("S") -> { kbit = mapOf('0' to 10, '1' to 20, '2' to 50, '3' to 100, '4' to 125, '5' to 250, '6' to 500, '8' to 1000)[cmd[1]] ?: 0; ok() }
                cmd.startsWith("s") -> err() // a CANable has no custom bit timing
                cmd == "L" -> if (canL) { open = true; silent = true; ok() } else err()
                cmd == "M1" -> if (canableFw) { silent = true; ok() } else err()
                cmd == "M0" -> if (canableFw) { silent = false; ok() } else err()
                cmd == "O" -> { open = true; ok() }
                else -> err()
            }
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (out.isEmpty() && open && kbit == busKbit) {
                val id = listOf(0x140, 0x360, 0x144)[n++ % 3]
                ("t%03X80102030405060708\r".format(id)).forEach { out.add(it.code.toByte()) }
            }
            if (out.isEmpty()) { Thread.sleep(minOf(timeoutMs, 5).toLong()); return 0 }
            var i = 0
            while (i < buf.size && out.isNotEmpty()) buf[i++] = out.removeFirst()
            return i
        }
    }

    @Test
    fun probeFindsTheBusListenOnly() {
        val bus = FakeBus(250)
        val out = CanBaudProbe.run(bus, canableFw = false, listenMs = 300)
        assertTrue(out is CanBaudProbe.Outcome.Found)
        out as CanBaudProbe.Outcome.Found
        assertEquals(250, out.kbit)
        assertEquals(0, out.results.first { it.kbit == 500 }.frames)
        assertTrue(out.results.first { it.kbit == 250 }.valid)
        // never opened in normal mode: nothing can have gone onto the bus
        assertFalse("O" in bus.sent)
        assertFalse(bus.sent.any { it.startsWith("t") || it.startsWith("T") })
        assertEquals("C", bus.sent.last())
    }

    @Test
    fun probeOnCanableFirmwareUsesSilentMode() {
        val bus = FakeBus(125, canL = false, canableFw = true)
        val out = CanBaudProbe.run(bus, canableFw = true, listenMs = 300)
        assertEquals(125, (out as CanBaudProbe.Outcome.Found).kbit)
        // every "O" comes right after an "M1"
        bus.sent.forEachIndexed { i, c -> if (c == "O") assertEquals("M1", bus.sent[i - 1]) }
        assertEquals("M0", bus.sent.last())
        assertFalse(bus.silent)
    }

    @Test
    fun probeRefusesWithoutListenOnly() {
        val bus = FakeBus(500, canL = false)
        assertTrue(CanBaudProbe.run(bus, canableFw = false, listenMs = 100) is CanBaudProbe.Outcome.NoListenOnly)
        assertFalse("O" in bus.sent)
    }

    @Test
    fun probeQuietBusAndCustomRates() {
        val bus = FakeBus(83) // a CANable can't do 83.3: nothing is heard anywhere
        val out = CanBaudProbe.run(bus, canableFw = false, listenMs = 60)
        assertTrue(out is CanBaudProbe.Outcome.Silent)
        val r = (out as CanBaudProbe.Outcome.Silent).results
        assertEquals(CanBitrates.PROBE, r.map { it.kbit })
        assertFalse(r.first { it.kbit == 83 }.supported)
        assertFalse(r.first { it.kbit == 33 }.supported)
        assertEquals("s451C", CanBitrates.slcanCommand(83))
        assertEquals("S6", CanBitrates.slcanCommand(500))
        assertNull(CanBitrates.slcanCommand(42))
        assertEquals("83,3 кбит", CanBitrates.label(83))
        assertEquals("1 Мбит", CanBitrates.label(1000))
    }

    @Test
    fun probePicksTheCleanSpeed() {
        val noisy = CanBaudProbe.Result(500, true, 10, 3, 1, 4)
        val clean = CanBaudProbe.Result(250, true, 40, 5, 5, 0)
        val few = CanBaudProbe.Result(125, true, 2, 1, 1, 0)
        assertEquals(250, CanBaudProbe.pick(listOf(noisy, clean, few))?.kbit)
        assertNull(CanBaudProbe.pick(listOf(noisy, few)))
    }

    @Test
    fun listenCommandReadsReplies() {
        val bus = FakeBus(500, canL = false)
        assertEquals(SlcanListen.Reply.OK, SlcanListen.command(bus, "S6"))
        assertEquals(SlcanListen.Reply.ERROR, SlcanListen.command(bus, "L"))
        assertTrue(SlcanListen.canableFw(0xAD50, 0x60C4))
        assertFalse(SlcanListen.canableFw(0x16D0, 0x117E))
    }

    // ---------------------------------------------------------------- the wizard's data

    @Test
    fun terminationReadings() {
        assertEquals(CanBusGuide.Termination.OK, CanBusGuide.termination(60f))
        assertEquals(CanBusGuide.Termination.OK, CanBusGuide.termination(62.5f))
        assertEquals(CanBusGuide.Termination.MISSING, CanBusGuide.termination(120f))
        assertEquals(CanBusGuide.Termination.EXTRA, CanBusGuide.termination(40f))
        assertEquals(CanBusGuide.Termination.SHORT, CanBusGuide.termination(2f))
        assertEquals(CanBusGuide.Termination.NONE, CanBusGuide.termination(5000f))
        assertEquals(CanBusGuide.Termination.ODD, CanBusGuide.termination(90f))
    }

    @Test
    fun busTable() {
        val ms = CanBusGuide.bus("ford_ms")!!
        assertEquals(125, ms.kbit)
        assertEquals(3 to 11, ms.obdPins)
        assertEquals(6 to 14, CanBusGuide.bus("ford_hs")!!.obdPins)
        assertEquals(500, CanBusGuide.bus("ford_hs")!!.kbit)
        assertEquals(100, CanBusGuide.bus("vag_comfort")!!.kbit)
        val k = CanBusGuide.bus("bmw_kcan")!!
        assertEquals(100, k.kbit)
        assertTrue(k.needsOtherTransceiver)
        assertEquals(83, CanBusGuide.bus("mb_canb")!!.kbit)
        val gm = CanBusGuide.bus("gm_sw")!!
        assertEquals(33, gm.kbit)
        assertEquals(CanBusGuide.Phys.SINGLE_WIRE, gm.phys)
        assertNotNull(gm.transceiverNote)
        assertNull(CanBusGuide.bus("ford_hs")!!.transceiverNote)
        // every bus has a speed the picker offers, every make some bus
        CanBusGuide.BUSES.forEach { assertTrue(it.id, it.kbit in CanBitrates.CHOICES) }
        CanBusGuide.MAKES.forEach { assertTrue(it, CanBusGuide.busesOf(it).isNotEmpty()) }
        assertEquals(CanBusGuide.BUSES.size, CanBusGuide.BUSES.map { it.id }.toSet().size)
        assertNotNull(CanBusGuide.gatewayNote(CanBusGuide.MAKES[0]))
        assertNull(CanBusGuide.gatewayNote("Subaru"))
        assertTrue(CanBusGuide.listenOnly(CanBusGuide.Scenario.CAR))
        assertFalse(CanBusGuide.listenOnly(CanBusGuide.Scenario.SENSOR))
    }
}
