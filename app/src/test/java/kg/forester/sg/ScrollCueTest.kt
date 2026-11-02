package kg.forester.sg

import kg.forester.sg.ui.canvasDp
import kg.forester.sg.ui.scrollThumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScrollCueTest {
    @Test fun noBarWhenAllIsSeen() {
        assertNull(scrollThumb(100f, 100f, 0f))
        assertNull(scrollThumb(100f, 50f, 0f))
        assertNull(scrollThumb(0f, 50f, 0f))
    }

    @Test fun barFollowsTheOffset() {
        val top = scrollThumb(100f, 400f, 0f)!!
        assertEquals(0f, top.first, 0.001f); assertEquals(0.25f, top.second, 0.001f)
        val end = scrollThumb(100f, 400f, 300f)!!
        assertEquals(0.75f, end.first, 0.001f)
        assertEquals(1f, end.first + end.second, 0.001f)
    }

    @Test fun aHugeListKeepsAVisibleBar() {
        val t = scrollThumb(100f, 100000f, 99900f)!!
        assertEquals(0.1f, t.second, 0.001f)
        assertEquals(1f, t.first + t.second, 0.001f)
    }

    @Test fun windowRoomOnTheCanvas() {
        // 1024×600 at 160 dpi: canvas density 0.867
        assertEquals(692, canvasDp(600, 160, 0.867f))
        // the same panel saying 240 dpi: 400 of its dp, still 692 on the canvas
        assertEquals(692, canvasDp(400, 240, 0.867f))
    }

    private val ui = listOf("src/main/java/kg/forester/sg/ui", "app/src/main/java/kg/forester/sg/ui").map(::File).first { it.isDirectory }

    /** The body of the call at [from] (its opening parenthesis), to the closing one of the trailing lambda. */
    private fun block(s: String, from: Int): String {
        val open = s.indexOf('{', from)
        var d = 0; var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '"') { i++; while (i < s.length && (s[i] != '"' || s[i - 1] == '\\')) i++ }
            else if (c == '{') d++
            else if (c == '}') { d--; if (d == 0) return s.substring(from, i + 1) }
            i++
        }
        return s.substring(from)
    }

    /** A sheet with a list above its scroller: the rows would eat the height and the scroller would get none. */
    @Test fun noRowsOfVariableLengthAboveASheetsScroller() {
        val bad = ArrayList<String>()
        ui.walk().filter { it.extension == "kt" }.forEach { f ->
            val s = f.readText()
            Regex("""\bCarSheet\(""").findAll(s).forEach { m ->
                if (s.startsWith("fun ", maxOf(0, m.range.first - 4))) return@forEach
                val b = block(s, m.range.first)
                val at = Regex("""verticalScroll|cueScroll|LazyColumn|LazyVerticalGrid""").find(b)?.range?.first
                if (at != null && Regex("""forEach""").containsMatchIn(b.substring(0, at))) bad += "${f.name}: ${b.take(60)}"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    /** In a head= sheet all of the content scrolls: a scroller in there has no height to measure against, and crashes. */
    @Test fun noScrollerInsideAScrollingSheet() {
        val bad = ArrayList<String>()
        ui.walk().filter { it.extension == "kt" }.forEach { f ->
            val s = f.readText()
            Regex("""\b(CarSheet|DialogBox)\([^\n]*head = """).findAll(s).forEach { m ->
                if (Regex("""(verticalScroll|cueScroll|LazyColumn|LazyVerticalGrid)\(""").containsMatchIn(block(s, m.range.first))) bad += f.name
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    /** An alert's text scrolls by itself, unless it says it has its own scroller. */
    @Test fun alertsWithAScrollerSaySo() {
        val bad = ArrayList<String>()
        ui.walk().filter { it.extension == "kt" && it.name != "CarTokens.kt" }.forEach { f ->
            val s = f.readText()
            Regex("""\bCarAlertDialog\(""").findAll(s).forEach { m ->
                val call = callArgs(s, m.range.last)
                if (Regex("""verticalScroll\(|cueScroll\(|LazyColumn\(""").containsMatchIn(call) && !call.contains("textScrolls = false")) bad += "${f.name}:${s.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    private fun callArgs(s: String, open: Int): String {
        var d = 0; var i = open
        while (i < s.length) {
            val c = s[i]
            if (c == '"') { i++; while (i < s.length && (s[i] != '"' || s[i - 1] == '\\')) i++ }
            else if (c == '(') d++
            else if (c == ')') { d--; if (d == 0) return s.substring(open, i + 1) }
            i++
        }
        return s.substring(open)
    }

    /** A fixed height, or the window's dp taken for the canvas's, doesn't fit a low screen. */
    @Test fun dialogHeightsGoByTheCanvas() {
        val bad = ArrayList<String>()
        ui.walk().filter { it.extension == "kt" && it.name != "ScrollCue.kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, l ->
                if (l.contains("LocalConfiguration.current.screenHeightDp") || Regex("""heightIn\(max = [56]\d\d\.dp\)""").containsMatchIn(l)) bad += "${f.name}:${i + 1}"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun theSheetsHaveTheirCue() {
        val sheet = File(ui, "Components.kt").readText()
        assertTrue(sheet.contains("cueScroll()"))
        assertNotNull(scrollThumb(1f, 2f, 0f))
    }
}
