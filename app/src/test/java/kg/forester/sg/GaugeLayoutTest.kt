package kg.forester.sg

import kg.forester.sg.ui.GaugeDesign
import kg.forester.sg.ui.GaugeLayout
import kg.forester.sg.ui.LayoutBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing outside the card, the same margins each way, at the card sizes of a 7" and a 10" screen. */
class GaugeLayoutTest {
    private val cards = listOf(
        "1x1" to (150f to 130f), "2x1" to (300f to 130f), "2x2" to (300f to 260f), "3x2" to (450f to 260f),
        "1x2" to (150f to 260f), "1x1 big" to (240f to 210f), "2x1 big" to (480f to 210f), "2x2 big" to (480f to 420f), "3x2 big" to (720f to 420f),
    )
    private val eps = 0.01f

    private fun inside(what: String, b: LayoutBox, w: Float, h: Float) {
        assertTrue("$what $b in $w x $h", b.l >= -eps && b.t >= -eps && b.r <= w + eps && b.b <= h + eps)
    }

    private fun equalMargins(what: String, b: LayoutBox, w: Float, h: Float) {
        assertEquals("$what left and right", b.l, w - b.r, 0.5f)
        assertEquals("$what top and bottom", b.t, h - b.b, 0.5f)
    }

    @Test fun everyDesignStaysOnItsCardWithEqualMargins() {
        for ((name, card) in cards) for (dp in listOf(1f, 1.5f, 2f)) for (d in GaugeDesign.entries) {
            val w = card.first * dp
            val h = card.second * dp
            val s = GaugeLayout.design(d, w, h, dp).split
            inside("$name $d dial", s.dial, w, h)
            s.side?.let { inside("$name $d column", it, w, h) }
            s.line?.let { inside("$name $d line", it, w, h) }
            equalMargins("$name $d", s.all, w, h)
        }
    }

    @Test fun aColumnStandsOnTheDialsCentreLineOnAWideCard() {
        for (d in listOf(GaugeDesign.RED_LINE, GaugeDesign.BLUE_GLOW)) {
            val l = GaugeLayout.design(d, 450f, 260f, 1f)
            val col = assertColumn(l.split.side)
            assertEquals(l.split.cy, col.cy, eps)
            assertEquals(130f, l.split.cy, eps)
            assertTrue("dial left, column right", l.split.dial.r <= col.l)
            assertTrue(l.plate)
        }
    }

    private fun assertColumn(b: LayoutBox?): LayoutBox { assertNotNull(b); return b!! }

    @Test fun onASquareCardTheDialIsInTheMiddleWithNoPlate() {
        for (d in listOf(GaugeDesign.RED_LINE, GaugeDesign.BLUE_GLOW)) {
            val l = GaugeLayout.design(d, 300f, 260f, 1f)
            assertNull(l.split.side)
            assertNull(l.split.line)
            assertEquals(150f, l.split.cx, eps)
            assertEquals(130f, l.split.cy, eps)
            assertFalse(l.plate)
        }
    }

    @Test fun onATallCardALineGoesUnderTheDial() {
        val s = GaugeLayout.design(GaugeDesign.RED_LINE, 150f, 260f, 1f).split
        val line = assertColumn(s.line)
        assertTrue(s.dial.b <= line.t)
        assertEquals(75f, s.cx, eps)
        assertEquals(s.cx, line.cx, eps)
    }

    @Test fun plainDesignsAreOneDialInTheMiddleOfAnyCard() {
        val plain = GaugeDesign.entries - listOf(GaugeDesign.RED_LINE, GaugeDesign.BLUE_GLOW)
        for ((_, card) in cards) for (d in plain) {
            val s = GaugeLayout.design(d, card.first, card.second, 1f).split
            assertEquals(card.first / 2f, s.cx, eps)
            assertEquals(card.second / 2f, s.cy, eps)
            assertNull(s.side)
        }
    }

    @Test fun columnRowsAreCentredAndFit() {
        for ((name, card) in cards) for (d in listOf(GaugeDesign.RED_LINE, GaugeDesign.BLUE_GLOW)) {
            val l = GaugeLayout.design(d, card.first, card.second, 1f)
            val col = l.split.side ?: continue
            assertTrue("$name $d readout", l.readoutH <= col.h)
            assertTrue("$name $d rows", l.readoutH + l.rows * l.rowH <= col.h + eps)
            for (n in 0..l.rows) {
                val top = GaugeLayout.stackTop(col.cy, l.readoutH, l.rowH, n)
                assertTrue("$name $d top with $n rows", top >= col.t - eps)
                assertEquals(col.cy, top + (l.readoutH + n * l.rowH) / 2f, eps)
            }
            assertTrue("$name $d text", l.rows == 0 || l.rowText >= GaugeLayout.MIN_TEXT_DP)
        }
    }

    @Test fun tachoArcAndItsRowsStayOnTheCard() {
        for ((name, card) in cards) for (dp in listOf(1f, 2f)) {
            val w = card.first * dp
            val h = card.second * dp
            val t = GaugeLayout.tacho(w, h, dp)
            assertEquals("$name centred", w / 2f, t.cx, eps)
            val top = t.cy - t.r
            val bottom = t.cy + t.r * 0.72f
            assertEquals("$name top and bottom", top, h - bottom, 0.5f)
            assertTrue("$name side arcs", t.cx - t.r * 1.34f >= 0f)
            assertTrue("$name in the row order", t.rule < t.caption && t.caption < t.values && t.values < h)
        }
    }

    @Test fun turboDialAndValuesKeepEqualMargins() {
        for ((name, card) in cards) {
            val s = GaugeLayout.turbo(card.first, card.second, 1f)
            equalMargins(name, s.all, card.first, card.second)
            inside(name, s.dial, card.first, card.second)
            s.side?.let { assertEquals(name, s.cy, it.cy, eps) }
        }
        assertNotNull(GaugeLayout.turbo(300f, 130f, 1f).side)
        assertNotNull(GaugeLayout.turbo(450f, 260f, 1f).side)
    }

    @Test fun columnValuesSpreadEvenlyOverTheColumn() {
        val b = LayoutBox(0f, 10f, 50f, 110f)
        assertEquals(22.5f, GaugeLayout.columnY(b, 0, 4), eps)
        assertEquals(97.5f, GaugeLayout.columnY(b, 3, 4), eps)
    }

    @Test fun tiltLabelsStayOnTheCard() {
        for ((name, card) in cards) {
            val w = card.first
            val h = card.second
            val t = GaugeLayout.tilt(w, h, 1f)
            assertEquals(name, t.leftX, w - t.rightX, eps)
            assertTrue("$name left label", t.leftX - t.r * 1.13f - t.r * 0.1f >= 0f)
            assertTrue("$name middle", t.leftX + t.r * 1.0f <= w / 2f)
            assertTrue("$name top", t.top - t.r * 0.12f >= 0f)
            assertTrue("$name bottom", t.unit + t.r * 0.05f <= h)
        }
    }

    @Test fun radarGridKeepsTheSameMarginEachWay() {
        for ((name, card) in cards) {
            val r = GaugeLayout.radar(card.first, card.second)
            assertEquals(name, card.first / 2f - r.rx, card.second / 2f - r.ry, eps)
            assertTrue(name, r.pillW <= card.first && r.pillH <= card.second)
        }
    }

    @Test fun gForceNumbersStayOnTheCard() {
        for ((name, card) in cards) {
            val f = GaugeLayout.force(card.first, card.second)
            val widest = f.text * 0.6f * 4f
            assertTrue("$name left", f.cx - f.r - f.text * 0.5f - widest >= 0f)
            assertTrue("$name top", f.cy - f.r - f.text * 1.5f >= 0f)
        }
    }

    @Test fun spokesOnlyWhereTheirTextCanBeRead() {
        assertFalse(GaugeLayout.web(150f, 130f, 1f).spokes)
        assertTrue(GaugeLayout.web(300f, 260f, 1f).spokes)
        for ((name, card) in cards) {
            val g = GaugeLayout.web(card.first, card.second, 1f)
            assertTrue(name, g.cx - g.r >= 0f && g.cy - g.r >= 0f)
            if (g.spokes) assertTrue("$name values clear the disk", g.outX * 0.5f + g.r * 0.5f < card.first)
        }
    }

    @Test
    fun aDialIsDroppedOnlyOnALowWideCard() {
        org.junit.Assert.assertTrue(kg.forester.sg.ui.GaugeLayout.tooShortForDial(300f, 40f))
        org.junit.Assert.assertFalse(kg.forester.sg.ui.GaugeLayout.tooShortForDial(300f, 110f))
        org.junit.Assert.assertFalse(kg.forester.sg.ui.GaugeLayout.tooShortForDial(70f, 50f))
    }
}
