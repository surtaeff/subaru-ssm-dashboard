package kg.forester.sg

import kg.forester.sg.data.SCREEN_FIRMWARE
import kg.forester.sg.data.ScreenCfg
import kg.forester.sg.data.ScreenInfo
import kg.forester.sg.data.ScreenLayout
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.ScreenPage
import kg.forester.sg.flash.ByteLink
import kg.forester.sg.flash.EspFlasher
import kg.forester.sg.flash.EspRom
import kg.forester.sg.flash.FlashTimeout
import kg.forester.sg.service.ScreenFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ScreenTest {
    @Test fun packedPicturesComeBackTheSame() {
        val rnd = java.util.Random(5)
        val w = 64
        val h = 40
        // a gauge-like picture: long black runs, some noise, a few flat colours
        val px = IntArray(w * h) { i -> when { i % 97 < 60 -> 0; i % 13 == 0 -> rnd.nextInt(0x10000); else -> 0xF800 } }
        val r = ScreenFrames.Rect(0, 0, w, h)
        val b = ScreenFrames.pack(px, w, r)
        assertArrayEquals(px, ScreenFrames.unpack(b, w * h))
        assertTrue("runs shrink it: ${b.size} of ${px.size * 2}", b.size < px.size)
        // a sub-rectangle, all one colour: a run of 128 at most per packet
        val sub = ScreenFrames.Rect(8, 4, 32, 10)
        val flat = IntArray(w * h) { 0x1234 }
        val fb = ScreenFrames.pack(flat, w, sub)
        assertEquals((320 + 127) / 128 * 3, fb.size)
        assertArrayEquals(IntArray(320) { 0x1234 }, ScreenFrames.unpack(fb, 320))
        // noise: literals, never more than 1 byte a 128 pixels over the raw size
        val noise = IntArray(w * h) { rnd.nextInt(0x10000) }
        val nb = ScreenFrames.pack(noise, w, r)
        assertArrayEquals(noise, ScreenFrames.unpack(nb, w * h))
        assertTrue(nb.size <= w * h * 2 + (w * h + 127) / 128)
        assertTrue(nb.size <= ScreenFrames.MAX_PX * 2 + ScreenFrames.MAX_PX / 128 + 8)
        assertNull(ScreenFrames.unpack(b.copyOf(b.size - 1), w * h))
    }

    @Test fun onlyWhatChangedIsSent() {
        val w = 466
        val h = 466
        val a = IntArray(w * h)
        val all = ScreenFrames.dirty(null, a, w, h)
        assertEquals(w * h, all.sumOf { it.w * it.h })
        all.forEach { r ->
            assertTrue(r.w * r.h <= ScreenFrames.MAX_PX)
            assertTrue(r.x % 2 == 0 && r.y % 2 == 0 && r.w % 2 == 0 && r.h % 2 == 0)
            assertTrue(r.x + r.w <= w && r.y + r.h <= h)
        }
        assertTrue(ScreenFrames.dirty(a, a.copyOf(), w, h).isEmpty())
        val b = a.copyOf()
        b[200 * w + 300] = 0xFFFF
        val d = ScreenFrames.dirty(a, b, w, h)
        assertEquals(listOf(ScreenFrames.Rect(288, 192, 16, 16)), d)
        // a needle sweeping across: a few rectangles, nowhere near the whole screen
        val c = a.copyOf()
        for (k in 0 until 150) c[(233 - k / 3) * w + 233 + k] = 0xF800
        val n = ScreenFrames.dirty(a, c, w, h)
        assertTrue(n.sumOf { it.w * it.h } < w * h / 10)
    }

    @Test fun rgb565() {
        assertEquals(0xF800, ScreenFrames.rgb565(0xFFFF0000.toInt()))
        assertEquals(0x07E0, ScreenFrames.rgb565(0xFF00FF00.toInt()))
        assertEquals(0x001F, ScreenFrames.rgb565(0xFF0000FF.toInt()))
        assertEquals(0xFFFF, ScreenFrames.rgb565(-1))
    }

    /** An ESP32-S3 ROM loader as the protocol page describes it: SLIP, 4 status bytes, FLASH_* writing a flash array. */
    private class Rom(val flashSize: Int = 1 shl 20) : ByteLink {
        val flash = ByteArray(flashSize) { 0xFF.toByte() }
        private val out = ArrayDeque<Int>()
        private val frame = java.io.ByteArrayOutputStream()
        private var inFrame = false
        private var esc = false
        private var base = 0L
        private var seq = 0
        var synced = 0
        var badChecksums = 0

        override fun write(b: ByteArray) {
            for (x in b) {
                val v = x.toInt() and 0xFF
                when {
                    v == 0xC0 && !inFrame -> { inFrame = true; frame.reset() }
                    v == 0xC0 -> { inFrame = false; handle(frame.toByteArray()) }
                    esc -> { frame.write(if (v == 0xDC) 0xC0 else 0xDB); esc = false }
                    v == 0xDB -> esc = true
                    else -> frame.write(v)
                }
            }
        }

        private fun le(b: ByteArray, at: Int) = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        private fun reply(op: Int, value: Long = 0, data: ByteArray = ByteArray(0), status: Int = 0) {
            val body = data + byteArrayOf(status.toByte(), 0, 0, 0)
            val p = byteArrayOf(1, op.toByte(), body.size.toByte(), (body.size shr 8).toByte()) + EspRom.le32(value) + body
            EspRom.slip(p).forEach { out.addLast(it.toInt() and 0xFF) }
        }

        private fun handle(p: ByteArray) {
            val op = p[1].toInt() and 0xFF
            val d = p.copyOfRange(8, p.size)
            when (op) {
                EspRom.SYNC -> { synced++; repeat(8) { reply(op) } }
                EspRom.READ_REG -> reply(op, if (le(d, 0) == EspRom.CHIP_MAGIC_REG) EspRom.S3_MAGIC else 0)
                EspRom.SPI_ATTACH, EspRom.SPI_SET_PARAMS -> reply(op)
                EspRom.FLASH_BEGIN -> {
                    assertEquals(20, d.size) // the S3 ROM wants the encryption word too
                    base = le(d, 12); seq = 0
                    for (i in 0 until le(d, 0).toInt()) flash[(base + i).toInt()] = 0xFF.toByte()
                    reply(op)
                }
                EspRom.FLASH_DATA -> {
                    val len = le(d, 0).toInt()
                    val data = d.copyOfRange(16, 16 + len)
                    if (le(p, 4).toInt() != EspRom.checksum(data) || le(d, 4).toInt() != seq) { badChecksums++; reply(op, status = 1); return }
                    data.copyInto(flash, (base + seq * len).toInt())
                    seq++
                    reply(op)
                }
                EspRom.SPI_FLASH_MD5 -> {
                    val md5 = EspRom.md5Hex(flash.copyOfRange(le(d, 0).toInt(), (le(d, 0) + le(d, 4)).toInt()))
                    reply(op, data = md5.toByteArray(Charsets.US_ASCII))
                }
                EspRom.FLASH_END -> reply(op)
                else -> reply(op, status = 1)
            }
        }

        override fun byte(timeoutMs: Long) = out.removeFirstOrNull() ?: throw FlashTimeout()
        override fun drain(quietMs: Long) = out.clear()
    }

    @Test fun espRomWritesAndChecksAnImage() {
        val rom = Rom()
        val img = ByteArray(70_001) { (it * 31 + 7).toByte() }
        // bytes the SLIP has to escape
        img[10] = 0xC0.toByte(); img[11] = 0xDB.toByte()
        val loader = EspRom(rom)
        assertTrue(loader.sync())
        assertTrue(loader.isS3())
        loader.attach()
        var last = 0f
        loader.write(0, img) { last = it }
        assertEquals(1f, last)
        assertEquals(0, rom.badChecksums)
        assertArrayEquals(img, rom.flash.copyOfRange(0, img.size))
        assertEquals(EspRom.md5Hex(img), loader.md5(0, img.size))
        loader.finish()
    }

    @Test fun shippedScreenFirmwareIsCurrentAndChecks() {
        val dir = File("../hardware/usbscreen")
        val manifest = org.json.JSONObject(File(dir, "bin/manifest.json").readText())
        val src = MessageDigest.getInstance("SHA-256").digest(File(dir, "usbscreen.ino").readBytes() + File(dir, "boards.h").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("usbscreen.ino or boards.h changed: run hardware/usbscreen/build-bin.sh", manifest.getString("src_sha256"), src)
        val fw = Regex("#define FW_VERSION (\\d+)").find(File(dir, "usbscreen.ino").readText())!!.groupValues[1].toInt()
        assertEquals("SCREEN_FIRMWARE in UsbScreen.kt", fw, SCREEN_FIRMWARE)
        // the big panels' firmware is another sketch with its own manifest (BigScreenTest)
        val small = ScreenModel.entries.filter { !it.big }
        for (m in small) {
            val f = File("../hardware/" + m.asset.removePrefix("usbscreen/").let { "usbscreen/$it" })
            assertTrue("no firmware for ${m.id}", f.exists())
            assertNull(m.id, EspFlasher.checkImage(f.readBytes()))
            // every board in the app is one boards.h knows, and the other way round
            assertTrue(m.id, File(dir, "boards.h").readText().contains("\"${m.id}\""))
        }
        assertEquals(small.size, manifest.getJSONObject("files").length())
        assertTrue(EspFlasher.checkImage(ByteArray(0x20000)) != null)
    }

    @Test fun twoScreensKeepTheirOwnSettings() {
        val u = kg.forester.sg.data.UsbScreen
        u.screens.value = mapOf("" to ScreenCfg(name = "Стойка", pages = listOf(ScreenPage(1, ScreenLayout.BIG, listOf("oil")))))
        u.online.value = emptyMap()
        // the first screen plugged in takes what was set up for it beforehand
        u.attach(ScreenInfo("ws_amoled_143", 466, 466, true, 1, "AAAA"), "/dev/bus/usb/001/005")
        assertEquals("Стойка", u.cfgOf("AAAA").name)
        assertEquals("ws_amoled_143", u.cfgOf("AAAA").model)
        assertTrue("" !in u.screens.value)
        // the second gets its own, for its board: the tall LilyGO starts on its side
        u.attach(ScreenInfo("lilygo_amoled_191", 240, 536, false, 1, "BBBB"), "/dev/bus/usb/001/006")
        assertEquals(1, u.cfgOf("BBBB").rotation)
        assertEquals(2, u.online.value.size)
        u.put("BBBB", u.cfgOf("BBBB").copy(brightness = 25))
        assertEquals(200, u.cfgOf("AAAA").brightness)
        // pages turn on each by itself
        u.addPage("AAAA", ScreenLayout.TILES4)
        u.next("AAAA")
        assertEquals(1, u.pageOf("AAAA"))
        assertEquals(0, u.pageOf("BBBB"))
        u.detach("AAAA")
        assertEquals(setOf("BBBB"), u.online.value.keys)
        assertEquals("/dev/bus/usb/001/006", u.devices["BBBB"])
        // seen again later: the same settings
        u.attach(ScreenInfo("ws_amoled_143", 466, 466, true, 1, "AAAA"), "/dev/bus/usb/001/009")
        assertEquals("Стойка", u.cfgOf("AAAA").name)
        // the old way of saving (one screen) still loads
        assertEquals(ScreenCfg(model = "ws_lcd_128"), ScreenCfg.fromJson(ScreenCfg(model = "ws_lcd_128").toJson()))
    }

    @Test fun settingsAndInfoRoundTrip() {
        val c = ScreenCfg("ws_amoled_143", "Торпедо", 1, 128, listOf(ScreenPage(1, ScreenLayout.GAUGE2, listOf("rpm", "box_A0"), "sti"),
            ScreenPage(4, ScreenLayout.LIST, listOf("coolant"))), 10, false, 15)
        assertEquals(c, ScreenCfg.fromJson(c.toJson()))
        val i = ScreenInfo.parse("I USBSCREEN 1 board=ws_amoled_143 w=466 h=466 round=1 fw=1 id=A1B2C3D4E5F6")!!
        assertEquals(ScreenInfo("ws_amoled_143", 466, 466, true, 1, "A1B2C3D4E5F6"), i)
        assertNull(ScreenInfo.parse("I SENSORBOX 2 board=nano"))
    }

    @Test fun screenSurvivesSaveRestore() {
        for (s in Screen.values()) assertEquals(s, screenRestored(screenSaved(s)))
        assertNull(screenRestored("NoSuchScreen"))
    }
}
