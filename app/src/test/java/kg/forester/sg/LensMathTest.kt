package kg.forester.sg

import kg.forester.sg.ui.DialStyles
import kg.forester.sg.ui.LensMath
import kg.forester.sg.ui.WarnRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LensMathTest {
    // ---- the crescent ----

    @Test fun theShareFollowsTheValueInsideTheScale() {
        assertEquals(0f, LensMath.share(0f, 0f, 200f), 0f)
        assertEquals(0.5f, LensMath.share(100f, 0f, 200f), 1e-5f)
        assertEquals(1f, LensMath.share(200f, 0f, 200f), 0f)
        assertEquals(0.25f, LensMath.share(50f, 40f, 80f), 1e-5f)
    }

    @Test fun aValueOutOfTheScaleStopsAtItsEnds() {
        assertEquals(0f, LensMath.share(-5f, 0f, 200f), 0f)
        assertEquals(1f, LensMath.share(300f, 0f, 200f), 0f)
    }

    @Test fun anEmptyScaleLightsNothing() {
        assertEquals(0f, LensMath.share(5f, 10f, 10f), 0f)
        assertEquals(0f, LensMath.share(Float.NaN, 0f, 10f), 0f)
    }

    @Test fun theCrescentStartsAtTheScalesStartOrAtZeroWithBothSigns() {
        assertEquals(0f, LensMath.arcFrom(0f, 200f), 0f)
        assertEquals(0f, LensMath.arcFrom(40f, 110f), 0f)
        assertEquals(0.5f, LensMath.arcFrom(-20f, 20f), 1e-5f)
        assertEquals(0.25f, LensMath.arcFrom(-10f, 30f), 1e-5f)
    }

    @Test fun theCrescentStaysOnTheLeftAndClearOfTheBadge() {
        // 0 = 3 o'clock, clockwise; the badge sits at 90
        assertTrue(LensMath.START_DEG > 110f)
        assertTrue(LensMath.START_DEG + LensMath.SWEEP_DEG < 345f)
    }

    @Test fun theBrushKeyIsCoarseAndTheEndsLandInTheStops() {
        assertEquals(LensMath.brushKey(180.2f, 200.1f, false), LensMath.brushKey(180.4f, 200.3f, false))
        assertFalse(LensMath.brushKey(180f, 200f, false) == LensMath.brushKey(180f, 230f, false))
        assertFalse(LensMath.brushKey(180f, 200f, false) == LensMath.brushKey(180f, 200f, true))
    }

    @Test fun theCrescentTapersToNothingAtBothEnds() {
        assertEquals(0f, LensMath.taper(0f), 1e-3f)
        assertEquals(0f, LensMath.taper(1f), 1e-3f)
        assertEquals(1f, LensMath.taper(0.5f), 1e-3f)
        assertTrue(LensMath.taper(0.25f) > 0.5f)
    }

    // ---- the warning arc ----

    @Test fun noRuleNeverLights() {
        for (v in listOf(0f, 100f, 200f)) assertEquals(0f, LensMath.warnShare(v, 0f, 200f, WarnRule(), null), 0f)
        assertEquals(0f, LensMath.warnShare(null, 0f, 200f, WarnRule(above = 10f), 10f), 0f)
    }

    @Test fun overTheLineLights() {
        val rule = WarnRule(above = 102f)
        assertEquals(0f, LensMath.warnShare(100f, 40f, 120f, rule, null), 0f)
        assertEquals(0f, LensMath.warnShare(102f, 40f, 120f, rule, null), 0f)
        assertTrue(LensMath.warnShare(103f, 40f, 120f, rule, null) >= LensMath.WARN_MIN)
    }

    @Test fun theRedZoneOfADialLightsAtItsStart() {
        // a dial's red zone starts at the line itself, as its lit arc goes red
        assertEquals(0f, LensMath.warnShare(5999f, 0f, 7000f, WarnRule(), 6000f), 0f)
        assertTrue(LensMath.warnShare(6000f, 0f, 7000f, WarnRule(), 6000f) >= LensMath.WARN_MIN)
    }

    @Test fun deeperLightsMoreAndNeverPastTheArc() {
        val r = WarnRule(above = 100f)
        val a = LensMath.warnShare(105f, 0f, 120f, r, null)
        val b = LensMath.warnShare(115f, 0f, 120f, r, null)
        assertTrue(b > a)
        assertTrue(LensMath.warnShare(500f, 0f, 120f, r, null) <= 1f)
    }

    @Test fun belowAndAbsWork() {
        val low = WarnRule(below = 12f)
        assertEquals(0f, LensMath.warnShare(12.5f, 10f, 15f, low, null), 0f)
        assertTrue(LensMath.warnShare(11f, 10f, 15f, low, null) >= LensMath.WARN_MIN)
        assertTrue(LensMath.warnShare(10f, 10f, 15f, low, null) > LensMath.warnShare(11.9f, 10f, 15f, low, null))
        val abs = WarnRule(abs = 15f)
        assertEquals(0f, LensMath.warnShare(14f, -30f, 30f, abs, null), 0f)
        assertTrue(LensMath.warnShare(-20f, -30f, 30f, abs, null) >= LensMath.WARN_MIN)
        assertTrue(LensMath.warnShare(20f, -30f, 30f, abs, null) >= LensMath.WARN_MIN)
    }

    @Test fun aLineAtTheScalesEndStillLights() {
        // no room to go deeper: not a division by zero
        val s = LensMath.warnShare(130f, 0f, 120f, WarnRule(above = 120f), null)
        assertTrue(s in LensMath.WARN_MIN..1f)
    }

    // ---- the drum ----

    @Test fun anOrdinaryChangeRolls() {
        assertTrue(LensMath.shouldRoll(true, false, 1000L, "38", "39"))
    }

    @Test fun theRollIsShorterThanTheGateOfTheDigits() {
        assertTrue(LensMath.ROLL_MS < kg.forester.sg.ui.DIGITS_MS)
        assertTrue(LensMath.ROLL_SPACING_MS > LensMath.ROLL_MS)
    }

    @Test fun lowTierAndSmallCardsDoNotRoll() {
        assertFalse(LensMath.shouldRoll(true, true, 1000L, "38", "39"))
        assertFalse(LensMath.shouldRoll(false, false, 1000L, "38", "39"))
    }

    @Test fun fastChangesSkipTheRoll() {
        assertFalse(LensMath.shouldRoll(true, false, LensMath.ROLL_SPACING_MS - 1, "38", "39"))
        assertTrue(LensMath.shouldRoll(true, false, LensMath.ROLL_SPACING_MS, "38", "39"))
    }

    @Test fun nothingRollsToOrFromNoValueOrTheSameText() {
        assertFalse(LensMath.shouldRoll(true, false, 1000L, "—", "39"))
        assertFalse(LensMath.shouldRoll(true, false, 1000L, "39", "—"))
        assertFalse(LensMath.shouldRoll(true, false, 1000L, "39", "39"))
    }

    // ---- sizes ----

    @Test fun smallSizesDropTheOuterArcFirstThenTheDrum() {
        val big = LensMath.layout(320f)
        val mid = LensMath.layout(160f)
        val small = LensMath.layout(100f)
        assertTrue(big.outer && big.drum)
        assertFalse(mid.outer); assertTrue(mid.drum)
        assertFalse(small.outer); assertFalse(small.drum)
    }

    @Test fun withoutTheOuterArcTheLensTakesTheRoom() {
        assertTrue(LensMath.layout(160f).ring > LensMath.layout(320f).ring)
        assertTrue(LensMath.layout(160f).disk > LensMath.layout(320f).disk)
    }

    @Test fun ringsAreNestedAtEverySize() {
        for (s in listOf(60f, 100f, 129f, 130f, 199f, 200f, 400f, 900f)) {
            val l = LensMath.layout(s)
            assertTrue("$s", l.disk < l.lens && l.lens < l.ring && l.ring <= 1f)
            if (l.outer) assertTrue("$s", l.ring < l.arc && l.arc < 1f)
        }
    }

    @Test fun theNumberFitsTheDiskAtAnySizeAndLength() {
        for (side in listOf(100f, 160f, 240f, 400f)) {
            val disk = LensMath.layout(side).disk * side / 2
            for (widest in listOf("8", "88", "888", "8888", "-12.5")) {
                val px = LensMath.digitSize(disk, widest.length)
                assertTrue("$side $widest", px * widest.length * LensMath.DIGIT_EM <= disk * 2)
                assertTrue("$side $widest", px > 0f)
            }
        }
    }

    @Test fun theNumberKeepsItsSizeByTheWidestOfTheScale() {
        assertEquals("100", LensMath.widest("%.0f", 0f, 100f))
        assertEquals("-30", LensMath.widest("%.0f", -30f, 10f))
        assertEquals("15.0", LensMath.widest("%.1f", 10f, 15f).replace(',', '.'))
    }

    @Test fun theEndLabelsReadAsTheScale() {
        assertEquals("0", LensMath.endLabel(0f, 1000f, 1000f))
        assertEquals("7", LensMath.endLabel(7000f, 1000f, 1000f))
        assertEquals("200", LensMath.endLabel(200f, 20f, 1f))
        assertEquals("1.5", LensMath.endLabel(1500f, 500f, 1000f).replace(',', '.'))
    }

    // ---- the gear ----

    @Test fun theBadgeShowsAKnownGearOnly() {
        assertEquals("3", LensMath.gear(3, 2000, 50, false, emptyList(), false))
        assertNull(LensMath.gear(null, null, null, false, emptyList(), false))
        // an idling car or the clutch down: no gear to tell, and never a made-up N
        assertNull(LensMath.gear(null, 800, 0, false, emptyList(), false))
        assertNull(LensMath.gear(0, 800, 0, true, emptyList(), false))
    }

    @Test fun theSubaruTablesAreNotGuessedForAnotherMake() {
        val rpm = 3000; val kmh = (3000 / kg.forester.sg.service.Gears.EAT4.perKmh[2]).toInt()
        assertEquals("3", LensMath.gear(null, rpm, kmh, true, emptyList(), false))
        assertNull(LensMath.gear(null, rpm, kmh, true, emptyList(), true))
        assertEquals("5", LensMath.gear(5, rpm, kmh, true, emptyList(), true))
        // learned ratios are the car's own
        assertEquals("2", LensMath.gear(null, 3000, 30, false, listOf(200f, 100f, 60f), true))
    }

    // ---- the choice ----

    @Test fun itIsAChoiceAtTheEndAndChangesNoDefault() {
        val src = File("src/main/java/kg/forester/sg/ui/GaugePresets.kt").readText()
        assertTrue("\"lens\", \"Линза\"" in src)
        assertTrue(src.indexOf("\"lens\", \"Линза\"") > src.lastIndexOf("DialStyle(\n") - 1)
        val repo = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertTrue("getString(\"tachStyle\", \"neon\")" in repo)
        assertTrue("getString(\"speedoStyle\", \"neon\")" in repo)
    }

    @Test fun theDialTakesTheLensBranchAndThePictureStaysAPlainDial() {
        val g = File("src/main/java/kg/forester/sg/ui/Gauges.kt").readText()
        assertTrue("if (style.lens && style.image == null) LensDial(" in g)
        val r = File("src/main/java/kg/forester/sg/ui/ScreenRenderer.kt").readText()
        assertTrue("st.lens" in r)
    }

    @Test fun staticPartsAreCachedAndNotDrawnPerFrame() {
        val l = File("src/main/java/kg/forester/sg/ui/LensDial.kt").readText()
        assertTrue("drawWithCache" in l)
        assertTrue("obtainGraphicsLayer" in l)
        val draw = l.substring(l.indexOf("onDrawBehind"), l.indexOf("onDrawBehind") + 400)
        assertFalse("drawLensBase(" in draw)
    }
}
