package kg.forester.sg

import kg.forester.sg.data.Board
import kg.forester.sg.data.FreeRect
import kg.forester.sg.data.ScreenBoards
import kg.forester.sg.data.ScreenCfg
import kg.forester.sg.data.ScreenInfo
import kg.forester.sg.data.ScreenLayout
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.ScreenPage
import kg.forester.sg.data.TileCfg
import kg.forester.sg.data.Tier
import kg.forester.sg.service.BigSender
import kg.forester.sg.service.ScreenFrames
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BigScreenTest {
    private val MAX = 131072

    /** The board's side of the wire: P rectangles written into its picture, J counted (its pixels are the app's encoder's). */
    private class Panel(val w: Int, val h: Int) {
        val px = IntArray(w * h)
        val heads = ArrayList<String>()
        var bytes = 0
        fun put(head: String, body: ByteArray): Boolean {
            heads += head
            bytes += head.length + 1 + body.size
            val f = head.split(' ')
            val x = f[1].toInt(); val y = f[2].toInt(); val rw = f[3].toInt(); val rh = f[4].toInt()
            assertEquals(body.size, f[5].toInt())
            assertTrue("$head inside", x >= 0 && y >= 0 && x + rw <= w && y + rh <= h)
            if (f[0] == "P") {
                val un = ScreenFrames.unpack(body, rw * rh)!!
                for (r in 0 until rh) for (c in 0 until rw) px[(y + r) * w + x + c] = un[r * rw + c]
            } else if (f[0] != "J") throw AssertionError(head)
            return true
        }
    }

    @Test fun rectanglesObeyWhatTheBoardTakes() {
        val w = 800
        val h = 480
        val all = ScreenFrames.dirty(null, IntArray(w * h), w, h, maxPx = MAX)
        assertEquals(w * h, all.sumOf { it.w * it.h })
        all.forEach { r -> assertTrue(r.w * r.h <= MAX && r.x % 2 == 0 && r.y % 2 == 0 && r.w % 2 == 0 && r.h % 2 == 0) }
        assertTrue("a big board takes the frame in a few pieces: ${all.size}", all.size <= 3)
        // the small boards' limit stays the default
        assertTrue(ScreenFrames.dirty(null, IntArray(w * h), w, h).all { it.w * it.h <= ScreenFrames.MAX_PX })
    }

    @Test fun manyScatteredChangesBecomeFewRectangles() {
        val w = 1024
        val h = 600
        val rs = (0 until 40).map { ScreenFrames.Rect((it % 8) * 120, (it / 8) * 110, 16, 16) }
        val out = ScreenFrames.coalesce(rs, MAX, 12)
        assertTrue("${out.size}", out.size <= 12)
        out.forEach { r -> assertTrue(r.w * r.h <= MAX && r.x + r.w <= w && r.y + r.h <= h && r.x % 2 == 0 && r.w % 2 == 0) }
        // every changed square still inside something
        rs.forEach { a -> assertTrue(out.any { it.x <= a.x && it.y <= a.y && it.x + it.w >= a.x + a.w && it.y + it.h >= a.y + a.h }) }
        // nothing to do when already few
        assertEquals(rs.take(3), ScreenFrames.coalesce(rs.take(3), MAX, 12))
        // never past the board's limit, even if that leaves more than asked
        val huge = listOf(ScreenFrames.Rect(0, 0, 800, 160), ScreenFrames.Rect(0, 160, 800, 160), ScreenFrames.Rect(0, 320, 800, 160))
        assertEquals(huge, ScreenFrames.coalesce(huge, MAX, 1))
    }

    @Test fun jpegOnlyWhereItPays() {
        // a big change of real picture: yes
        assertTrue(ScreenFrames.jpegPays(ScreenFrames.Rect(0, 0, 800, 160), 120_000))
        // small rectangles: lossless, the board's decoder would cost more than the bytes saved
        assertFalse(ScreenFrames.jpegPays(ScreenFrames.Rect(0, 0, 64, 64), 8_000))
        // big but flat: the runs already beat it
        assertFalse(ScreenFrames.jpegPays(ScreenFrames.Rect(0, 0, 800, 160), 4_000))
    }

    @Test fun senderUsesJpegForAWholeFrameThenRepairsLosslessly() {
        val w = 800
        val h = 480
        val p = Panel(w, h)
        val rnd = java.util.Random(3)
        val a = IntArray(w * h) { rnd.nextInt(0x10000) }
        val jpegged = ArrayList<ScreenFrames.Rect>()
        val s = BigSender(w, h, MAX, { true }, { r -> jpegged += r; ByteArray(r.w * r.h / 20) }, p::put)
        assertTrue(s.frame(null, a))
        assertTrue(p.heads.all { it.startsWith("J ") })
        val jpegBytes = p.bytes
        assertTrue("full noise frame $jpegBytes bytes", jpegBytes < w * h * 2 / 10)
        // the picture stands: the lossy parts are sent again exactly, one a frame, then silence
        p.heads.clear()
        var frames = 0
        while (s.pendingRepair() > 0 && frames < 10) { assertTrue(s.frame(a, a)); frames++ }
        assertTrue(p.heads.all { it.startsWith("P ") })
        assertArrayEquals(a, p.px)
        p.heads.clear()
        assertTrue(s.frame(a, a))
        assertTrue(p.heads.isEmpty())
    }

    @Test fun senderSendsOnlyTheChangedPartAndStopsOnARefusal() {
        val w = 800
        val h = 480
        val p = Panel(w, h)
        val a = IntArray(w * h)
        val s = BigSender(w, h, MAX, { true }, { error("not worth it") }, p::put)
        val b = a.copyOf().also { it[100 * w + 300] = 0xF800 }
        assertTrue(s.frame(a, b))
        assertEquals(listOf("P 288 96 16 16"), p.heads.map { it.substringBeforeLast(' ') })
        assertEquals(0xF800, p.px[100 * w + 300])
        // JPEG off (the setting): nothing but P, even for a whole frame
        val q = Panel(w, h)
        val noise = java.util.Random(1).let { r -> IntArray(w * h) { r.nextInt(0x10000) } }
        assertTrue(BigSender(w, h, MAX, { false }, { error("off") }, q::put).frame(null, noise))
        assertArrayEquals(noise, q.px)
        // a refusal: told at once, the rest not sent
        var calls = 0
        assertFalse(BigSender(w, h, MAX, { false }, { null }, { _, _ -> calls++; false }).frame(null, noise))
        assertEquals(1, calls)
        // an encoder that fails or does no better: lossless
        val e = Panel(w, h)
        assertTrue(BigSender(w, h, MAX, { true }, { null }, e::put).frame(null, noise))
        assertArrayEquals(noise, e.px)
        val big = Panel(w, h)
        assertTrue(BigSender(w, h, MAX, { true }, { r -> ByteArray(r.w * r.h * 3) }, big::put).frame(null, noise))
        assertArrayEquals(noise, big.px)
    }

    @Test fun fpsIsCappedByTierOnlyOnBigScreens() {
        assertEquals(20, ScreenFrames.fpsFor(20, Tier.HIGH, true))
        assertEquals(12, ScreenFrames.fpsFor(20, Tier.MID, true))
        assertEquals(8, ScreenFrames.fpsFor(20, Tier.LOW, true))
        assertEquals(5, ScreenFrames.fpsFor(5, Tier.LOW, true))
        assertEquals(20, ScreenFrames.fpsFor(20, Tier.LOW, false))
    }

    @Test fun infoLineOfABigBoardAndOfAnOldOne() {
        val big = ScreenInfo.parse("I USBSCREEN 1 board=ws_s3_lcd_7 w=800 h=480 round=0 fw=1 id=A1B2 maxpx=131072 jpeg=1")!!
        assertEquals(131072, big.maxPx)
        assertTrue(big.jpeg)
        assertTrue(big.isBig)
        val old = ScreenInfo.parse("I USBSCREEN 1 board=ws_amoled_143 w=466 h=466 round=1 fw=1 id=A1B2C3D4E5F6")!!
        assertEquals(ScreenFrames.MAX_PX, old.maxPx)
        assertFalse(old.jpeg)
        assertFalse(old.isBig)
        // junk in the fields is not believed, and a limit under the old one isn't either
        assertEquals(ScreenFrames.MAX_PX, ScreenInfo.parse("I USBSCREEN 1 board=x w=1 h=1 round=0 fw=1 maxpx=abc")!!.maxPx)
        assertEquals(ScreenFrames.MAX_PX, ScreenInfo.parse("I USBSCREEN 1 board=x w=1 h=1 round=0 fw=1 maxpx=-5")!!.maxPx)
    }

    @Test fun settingsKeepTheOwnLayoutsAndOldSavesStillLoad() {
        val c = ScreenCfg(model = "ws_p4_7b", pages = listOf(ScreenPage(1, ScreenLayout.MY, emptyList()), ScreenPage(2, ScreenLayout.SPORT, emptyList())), theme = "studio", jpeg = false)
        assertEquals(c, ScreenCfg.fromJson(c.toJson()))
        val old = JSONObject("""{"model":"ws_amoled_143","pages":[{"id":1,"layout":"GAUGE","items":["rpm"]}]}""")
        val parsed = ScreenCfg.fromJson(old)
        assertEquals("", parsed.theme)
        assertTrue(parsed.jpeg)
        // a layout this version doesn't know falls back as before
        assertEquals(ScreenLayout.GAUGE, ScreenPage.fromJson(JSONObject("""{"id":1,"layout":"NOPE"}""")).layout)
        // own layouts are for big panels; the small boards' list of layouts stays as it was
        assertEquals(listOf(ScreenLayout.GAUGE, ScreenLayout.GAUGE2, ScreenLayout.GAUGE_TILES, ScreenLayout.TILES4, ScreenLayout.TILES6, ScreenLayout.BIG, ScreenLayout.LIST),
            ScreenLayout.forBoard(false))
        assertTrue(ScreenLayout.forBoard(true).containsAll(listOf(ScreenLayout.HOME, ScreenLayout.DRIVE, ScreenLayout.MY, ScreenLayout.SPORT, ScreenLayout.SPORT_TABLE)))
    }

    private val board = Board("t", 4, 4, stretch = false, defaults = listOf(
        TileCfg("a", true, 2), TileCfg("b", true, 2), TileCfg("c", true, 1), TileCfg("d", false, 1), TileCfg("e", true, 1), TileCfg("z", true, 1),
    ), freeOnly = setOf("z"))

    @Test fun aGridLayoutIsPlacedRowByRow() {
        val p = ScreenBoards.place(board, "", { if (it.id == "c") 2f else 1f })
        assertEquals(listOf("a", "b", "c", "e"), p.map { it.tile.id })
        val a = p[0].rect; val b = p[1].rect; val c = p[2].rect; val e = p[3].rect
        assertEquals(0.5f, b.x, 0.01f)
        assertEquals(0.5f, a.w, 0.02f)
        // first row weight 1, second 2: a third and two thirds
        assertEquals(1f / 3f, a.h, 0.02f)
        assertEquals(2f / 3f, c.h, 0.02f)
        assertEquals(1f / 3f, c.y, 0.02f)
        // not stretched: the row's rest stays empty
        assertEquals(0.25f, e.x, 0.01f)
        assertEquals(0.25f, e.w, 0.02f)
        p.forEach { assertTrue(it.rect.x >= 0f && it.rect.y >= 0f && it.rect.x + it.rect.w <= 1.001f && it.rect.y + it.rect.h <= 1.001f) }
    }

    @Test fun aFreeLayoutKeepsItsPlacesAndLeavesOffCardsOut() {
        val saved = board.format(listOf(
            TileCfg("a", true, 2, "neon", FreeRect(0.1f, 0.2f, 0.3f, 0.4f)),
            TileCfg("d", false, 1, "", FreeRect(0f, 0f, 1f, 1f)),
            TileCfg("z", true, 1, "", FreeRect(0.5f, 0.5f, 0.5f, 0.5f)),
            TileCfg("b", true, 1, "", FreeRect(0.9f, 0.9f, 0.5f, 0.5f)),
        ), free = true)
        val p = ScreenBoards.place(board, saved, { 1f })
        assertEquals(listOf("a", "z", "b"), p.map { it.tile.id })
        assertEquals("neon", p[0].tile.style)
        assertEquals(FreeRect(0.1f, 0.2f, 0.3f, 0.4f), p[0].rect)
        // a card hanging over the edge is pulled to fit, not cut
        assertEquals(1f, p[2].rect.x + p[2].rect.w, 0.001f)
    }

    private fun lum(c: androidx.compose.ui.graphics.Color): Double {
        fun f(x: Float) = if (x <= 0.03928f) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val x = lum(a) + 0.05
        val y = lum(b) + 0.05
        return maxOf(x, y) / minOf(x, y)
    }

    /** [top] over [under], its alpha taken into account. */
    private fun over(top: androidx.compose.ui.graphics.Color, under: androidx.compose.ui.graphics.Color) = androidx.compose.ui.graphics.lerp(under, top.copy(alpha = 1f), top.alpha)

    @Test fun chosenLooksAreReadableAndAnEmptyBarLooksEmpty() {
        for (l in kg.forester.sg.ui.ScreenLook.chosen) {
            // the lightest place the card lands on (the studio's gradient ends lighter)
            val under = l.gradient?.last() ?: l.bg
            val card = over(l.card, under)
            assertTrue("${l.id} text", contrast(l.text, card) >= 4.5)
            assertTrue("${l.id} sub", contrast(l.sub, card) >= 3.0)
            assertTrue("${l.id} danger", contrast(l.danger, card) >= 3.0)
            // a value at nothing must not look like a full bar
            assertTrue("${l.id} track", contrast(l.accent, over(l.track, card)) >= 1.5)
        }
    }

    @Test fun modelsOfBigBoardsAreSane() {
        val big = ScreenModel.entries.filter { it.big }
        assertTrue(big.size in 3..6)
        assertEquals(setOf("ws_s3_lcd_43", "ws_s3_lcd_5", "ws_s3_lcd_7", "ws_p4_7b", "ws_p4_101"), big.map { it.id }.toSet())
        big.forEach { assertFalse(it.round); assertTrue(it.w * it.h >= 800 * 480) }
        // the P4's ROM loader isn't one this app talks to: those come from a computer, for both silicon revisions
        assertEquals(setOf("ws_p4_7b", "ws_p4_101"), ScreenModel.entries.filter { !it.selfFlash }.map { it.id }.toSet())
        assertNull(ScreenModel.WS_S3_LCD_7.assetPre3)
        assertEquals("usbscreen/bin/usbscreen-ws_p4_7b-pre3.bin", ScreenModel.WS_P4_7B.assetPre3)
        assertTrue(ScreenModel.WS_AMOLED_143.selfFlash)
    }

    @Test fun bigFirmwareIsCurrent() {
        val dir = File("../hardware/usbscreen/bigscreen")
        val manifest = JSONObject(File("../hardware/usbscreen/bin/manifest-big.json").readText())
        val src = MessageDigest.getInstance("SHA-256").digest(File(dir, "bigscreen.ino").readBytes() + File(dir, "big_boards.h").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("bigscreen.ino or big_boards.h changed: run hardware/usbscreen/bigscreen/build-big.sh", manifest.getString("src_sha256"), src)
        val files = manifest.getJSONObject("files")
        for (m in ScreenModel.entries.filter { it.big }) {
            val text = File(dir, "big_boards.h").readText()
            assertTrue(m.id, text.contains("\"${m.id}\""))
            for (asset in listOfNotNull(m.asset, m.assetPre3)) {
                val f = File("../hardware/$asset")
                assertTrue("no firmware $asset", f.exists())
                val b = f.readBytes()
                val key = f.name.removePrefix("usbscreen-").removeSuffix(".bin")
                assertEquals(key, files.getJSONObject(key).getInt("bytes"), b.size)
                assertEquals(key, files.getJSONObject(key).getString("sha256"), MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) })
                if (m.selfFlash) assertNull(m.id, kg.forester.sg.flash.EspFlasher.checkImage(b))
            }
        }
        assertEquals(7, files.length())
    }

    @Test fun smallBoardsFirmwareIsUntouched() {
        // their sketch, boards and images aren't rebuilt for the big panels: same manifest, same files
        val m = JSONObject(File("../hardware/usbscreen/bin/manifest.json").readText()).getJSONObject("files")
        assertEquals(ScreenModel.entries.count { !it.big }, m.length())
        ScreenModel.entries.filter { !it.big }.forEach { assertTrue(it.id, m.has(it.id)) }
    }
}
