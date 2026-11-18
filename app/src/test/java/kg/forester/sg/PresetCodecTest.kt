package kg.forester.sg

import kg.forester.sg.data.Base64Url
import kg.forester.sg.data.BoardPreset
import kg.forester.sg.data.BuiltInPresets
import kg.forester.sg.data.PresetCodec
import kg.forester.sg.data.PresetCodec.Decoded
import kg.forester.sg.data.TileCfg
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The «CPP1:» code a preset travels in: through a messenger, a QR, a file, and back whole or not at all. */
class PresetCodecTest {
    private fun ok(code: String) = (PresetCodec.decode(code) as Decoded.Ok).raw

    @Test fun base64UrlKeepsToRfc4648() {
        val vectors = listOf("" to "", "f" to "Zg", "fo" to "Zm8", "foo" to "Zm9v", "foob" to "Zm9vYg", "fooba" to "Zm9vYmE", "foobar" to "Zm9vYmFy")
        for ((plain, coded) in vectors) {
            assertEquals(coded, Base64Url.encode(plain.toByteArray()))
            assertEquals(plain, String(Base64Url.decode(coded)!!))
        }
        // the url-safe pair instead of + and /
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        val all = ByteArray(256) { it.toByte() }
        assertTrue(all.contentEquals(Base64Url.decode(Base64Url.encode(all))))
        assertNull(Base64Url.decode("Zm9v+"))
        assertNull(Base64Url.decode("Z"))
    }

    @Test fun everyBuiltInGoesThereAndBack() {
        for (p in BuiltInPresets.all.filter { p -> p.tiles.none { it.id.startsWith("@") } }) {
            val raw = ok(PresetCodec.encode(p))
            assertEquals(p.board, raw.board)
            assertEquals(p.name, raw.name)
            assertEquals(p.free, raw.free)
            assertEquals(p.extras, raw.extras)
            val back = kg.forester.sg.data.Boards.byKey(p.board).parse(raw.tiles).filter { it.on }
            assertEquals(p.id, p.tiles.map { listOf(it.id, it.span, it.style, it.free) }, back.map { listOf(it.id, it.span, it.style, it.free) })
        }
    }

    @Test fun cyrillicNamesAndAShortCode() {
        val p = BoardPreset("myTiles", "Мой «зимний» экран", "Ёлки, ОЖ и масло", tiles = listOf(TileCfg("coolant", true, 6, "sti|a"), TileCfg("oil", true, 6)))
        val code = PresetCodec.encode(p, app = "1.0-beta12")
        assertEquals("Мой «зимний» экран", ok(code).name)
        assertEquals("Ёлки, ОЖ и масло", ok(code).about)
        // small enough for a QR a phone reads off a 7" screen
        assertTrue(code.length.toString(), code.length < 400)
    }

    @Test fun foundInsideAMessengerMessage() {
        val code = PresetCodec.encode(BuiltInPresets.of("myTiles").first())
        val msg = "Пресет CarPulse «Каждый день» для экрана «Мой экран»:\n«$code»\nпробуй"
        assertEquals("Каждый день", ok(msg).name)
        // a messenger that broke the long word over lines
        val broken = code.substring(0, 30) + "\n" + code.substring(30, 70) + " \r\n" + code.substring(70)
        assertEquals("Каждый день", ok("Вот:\n$broken").name)
    }

    @Test fun aCutOrChangedCodeIsRefused() {
        val code = PresetCodec.encode(BuiltInPresets.of("homeTiles").first())
        assertEquals(Decoded.Damaged, PresetCodec.decode(code.dropLast(1)))
        val dot = code.indexOf('.')
        assertEquals(Decoded.Damaged, PresetCodec.decode(code.substring(0, dot - 3) + code.substring(dot)))
        val i = 12
        val changed = code.substring(0, i) + (if (code[i] == 'A') 'B' else 'A') + code.substring(i + 1)
        assertEquals(Decoded.Damaged, PresetCodec.decode(changed))
        assertEquals(Decoded.Damaged, PresetCodec.decode("CPP1:"))
        assertEquals(Decoded.Damaged, PresetCodec.decode("CPP1:абв.AAAA"))
        assertEquals(Decoded.NoCode, PresetCodec.decode("просто текст"))
    }

    @Test fun oversizeAndBombsAreRefused() {
        assertEquals(Decoded.Damaged, PresetCodec.decode("CPP1:" + "A".repeat(5000) + ".AAAA"))
        // a few hundred bytes that would inflate to 100 KB
        val bomb = PresetCodec.pack("{\"v\":1,\"b\":\"myTiles\",\"t\":\"coolant:1:3::\",\"n\":\"" + " ".repeat(100_000) + "\"}")
        assertTrue(bomb.length < PresetCodec.MAX_CODE)
        assertEquals(Decoded.Damaged, PresetCodec.decode(bomb))
        assertEquals(Decoded.Damaged, PresetCodec.decode(PresetCodec.pack("not json")))
    }

    @Test fun aLaterSchemaAsksForAnUpdate() {
        val o = JSONObject().put("v", 2).put("b", "myTiles").put("t", "coolant:1:3::").put("n", "x")
        assertEquals(Decoded.Newer, PresetCodec.decode(PresetCodec.pack(o.toString())))
    }

    @Test fun localThingsStayHome() {
        val p = BoardPreset("myTiles", "x", tiles = listOf(
            TileCfg("coolant", true, 4, "photo:my.png"), TileCfg("oil", true, 4, "custom:abc"), TileCfg("outw_1", true, 4),
            TileCfg("box_A2", true, 4, "sti"),
        ))
        val raw = ok(PresetCodec.encode(p, roles = mapOf("box_A2" to "oilP")))
        assertEquals("coolant:1:4::;oil:1:4::;box_A2:1:4:sti:", raw.tiles)
        assertEquals(mapOf("box_A2" to "oilP"), raw.roles)
    }
}
