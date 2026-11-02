package kg.forester.sg

import kg.forester.sg.service.CheckEngine
import kg.forester.sg.ui.GaugeDesign
import kg.forester.sg.ui.ClassicWindow
import kg.forester.sg.ui.GaugeMath
import kg.forester.sg.ui.NumberMode
import kg.forester.sg.ui.IndInputs
import kg.forester.sg.ui.Warn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GaugeMathTest {
    private fun inds(i: IndInputs) = GaugeMath.indicators(i, Warn.of("battery"), Warn.of("coolant"), Warn.of("range"))

    // ---- what drops at small sizes ----

    @Test fun indicatorsDropFirstThenTheWindowThenNumbers() {
        val big = GaugeMath.parts(320f)
        assertTrue(big.indicators && big.window && big.side && big.allLabels)
        val mid = GaugeMath.parts(150f)
        assertFalse(mid.indicators); assertFalse(mid.side)
        assertTrue(mid.window && mid.allLabels)
        val small = GaugeMath.parts(90f)
        assertFalse(small.indicators || small.window || small.side || small.allLabels)
    }

    // ---- geometry ----

    @Test fun scaleIsCentredOnTheBottom() {
        assertEquals(135f, GaugeMath.startDeg(270f), 1e-4f)
        assertEquals(135f, GaugeMath.deg(0f, 270f), 1e-4f)
        assertEquals(405f, GaugeMath.deg(1f, 270f), 1e-4f)
        assertEquals(270f, GaugeMath.deg(0.5f, 270f), 1e-4f)
        assertEquals(405f, GaugeMath.deg(5f, 270f), 1e-4f)
    }

    @Test fun segmentsLightWithTheValue() {
        assertEquals(0, GaugeMath.lit(0f, 72))
        assertEquals(36, GaugeMath.lit(0.5f, 72))
        assertEquals(72, GaugeMath.lit(1f, 72))
        assertEquals(0, GaugeMath.lit(-1f, 72))
    }

    @Test fun labelsFollowTheScaleAndThinOutOnSmallDials() {
        assertEquals(listOf(0f, 1000f, 2000f, 3000f), GaugeMath.labelSteps(0f, 3000f, 1000f, true))
        assertEquals(listOf(0f, 2000f), GaugeMath.labelSteps(0f, 3000f, 1000f, false))
        assertEquals(11, GaugeMath.labelSteps(0f, 200f, 20f, true).size)
        assertTrue(GaugeMath.labelSteps(0f, 0f, 20f, true).isEmpty())
    }

    @Test fun twoNeedlesEachKeepToTheirHalf() {
        assertEquals(205f, GaugeMath.fuelNeedleDeg(0f), 1e-4f)
        assertEquals(335f, GaugeMath.fuelNeedleDeg(1f), 1e-4f)
        assertEquals(155f, GaugeMath.coolNeedleDeg(0f), 1e-4f)
        assertEquals(25f, GaugeMath.coolNeedleDeg(1f), 1e-4f)
        assertTrue(GaugeMath.fuelNeedleDeg(0.5f) > 180f)
        assertTrue(GaugeMath.coolNeedleDeg(0.5f) < 180f)
    }

    // ---- indicators: real values or nothing ----

    @Test fun whatIsNotKnownIsAbsentNotZero() {
        val i = inds(IndInputs())
        assertNull(i.fuel); assertNull(i.battery); assertNull(i.coolant); assertNull(i.gear)
        assertNull(i.trip); assertNull(i.odo); assertNull(i.check); assertNull(i.clock); assertNull(i.selector)
    }

    @Test fun warningsComeFromTheSharedLines() {
        assertTrue(inds(IndInputs(volts = 11.5f)).battery!!.warn)
        assertFalse(inds(IndInputs(volts = 13.9f)).battery!!.warn)
        assertTrue(inds(IndInputs(coolantC = 110)).coolant!!.warn)
        assertFalse(inds(IndInputs(coolantC = 90)).coolant!!.warn)
        assertTrue(inds(IndInputs(fuelLeftL = 3.0, tankL = 60.0, rangeKm = 30.0)).fuel!!.warn)
        assertFalse(inds(IndInputs(fuelLeftL = 30.0, tankL = 60.0, rangeKm = 300.0)).fuel!!.warn)
    }

    @Test fun aTankOfUnknownSizeHasNoFuelNeedle() {
        val f = inds(IndInputs(fuelLeftL = 20.0, tankL = 0.0)).fuel
        assertNotNull(f)
        assertNull(f!!.share)
        assertEquals(0.5f, inds(IndInputs(fuelLeftL = 30.0, tankL = 60.0)).fuel!!.share!!, 1e-4f)
    }

    @Test fun checkEngineFollowsTheHeadersRule() {
        assertNull(inds(IndInputs(codes = CheckEngine(), connected = true)).check)
        assertNull(inds(IndInputs(codes = CheckEngine(engine = 2), connected = false)).check)
        assertEquals("2", inds(IndInputs(codes = CheckEngine(engine = 2), connected = true)).check!!.text)
        assertEquals("", inds(IndInputs(codes = CheckEngine(mil = true), connected = true)).check!!.text)
    }

    @Test fun odometerOnlyWhenTheAppKnowsIt() {
        assertNull(inds(IndInputs(odoKm = null, tripKm = 10.0)).odo)
        assertEquals("123457", inds(IndInputs(odoKm = 123456.7)).odo!!.text)
    }

    @Test fun theSelectorLightsItsLetters() {
        assertTrue(GaugeMath.selectorLit("D", "D"))
        assertFalse(GaugeMath.selectorLit("R", "D"))
        assertTrue(GaugeMath.selectorLit("N", "P/N"))
        assertTrue(GaugeMath.selectorLit("P", "P/N"))
        assertFalse(GaugeMath.selectorLit("D", null))
    }

    // ---- G ----

    @Test fun theDotStaysInTheCircle() {
        val (x, y) = GaugeMath.gDot(0.5f, 0.5f, 1f)
        assertEquals(0.5f, x, 1e-4f); assertEquals(-0.5f, y, 1e-4f)
        val (a, b) = GaugeMath.gDot(3f, 4f, 1f)
        assertEquals(0.6f, a, 1e-4f); assertEquals(-0.8f, b, 1e-4f)
    }

    @Test fun theRadarSectorIsWhereTheForcePoints() {
        assertNull(GaugeMath.radarSector(0.01f, 0.02f, 8))
        assertEquals(0, GaugeMath.radarSector(0f, 0.5f, 8))  // speeding up: straight up, as on the G-meter
        assertEquals(2, GaugeMath.radarSector(0.5f, 0f, 8))  // to the right
        assertEquals(4, GaugeMath.radarSector(0f, -0.5f, 8)) // braking
        assertEquals(6, GaugeMath.radarSector(-0.5f, 0f, 8)) // to the left
        assertEquals(1, GaugeMath.radarSector(0.4f, 0.4f, 8))
    }

    @Test fun theRadarFillsOutwards() {
        assertEquals(1, GaugeMath.radarRings(0.07f, 4, 1f))
        assertEquals(2, GaugeMath.radarRings(0.5f, 4, 1f))
        assertEquals(4, GaugeMath.radarRings(3f, 4, 1f))
    }

    @Test fun noCalibratedSensorNoOffer() {
        assertFalse(GaugeMath.motionOffered(false, false, true))
        assertFalse(GaugeMath.motionOffered(true, false, true))
        assertFalse(GaugeMath.motionOffered(true, true, false))
        assertTrue(GaugeMath.motionOffered(true, true, true))
    }

    @Test fun tiltStopsAtTheScaleEnds() {
        assertEquals(0.5f, GaugeMath.tiltShare(20f), 1e-4f)
        assertEquals(-1f, GaugeMath.tiltShare(-55f), 0f)
    }

    // ---- boost ----

    @Test fun theBoostScaleHasVacuumBelowZero() {
        assertEquals(-1f to 2f, GaugeMath.boostScale(1.4f))
        assertEquals(-1f to 3f, GaugeMath.boostScale(2.2f))
        assertEquals(listOf(0, 5, 10, 15, 20, 25), GaugeMath.psiMarks(2f))
    }

    // ---- spokes ----

    @Test fun sixSpokesEvenlyRound() {
        assertEquals(240f, GaugeMath.spokeDeg(0, 6), 1e-4f)
        assertEquals(300f, GaugeMath.spokeDeg(1, 6), 1e-4f)
        assertEquals(540f, GaugeMath.spokeDeg(5, 6), 1e-4f)
    }

    // ---- run charts ----

    @Test fun aCurveFitsThePlot() {
        val t = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val v = doubleArrayOf(100.0, 75.0, 50.0, 25.0, 0.0)
        val c = GaugeMath.chart(t, v, 0.0, 4.0, 100.0)
        assertEquals(10, c.size)
        assertEquals(0f, c[0], 0f); assertEquals(1f, c[1], 0f)
        assertEquals(1f, c[8], 0f); assertEquals(0f, c[9], 0f)
    }

    @Test fun nothingToDrawIsEmpty() {
        assertEquals(0, GaugeMath.chart(DoubleArray(0), DoubleArray(0), 0.0, 1.0, 100.0).size)
        assertEquals(0, GaugeMath.chart(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), 2.0, 1.0, 100.0).size)
        assertEquals(0, GaugeMath.chart(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), 5.0, 6.0, 100.0).size)
    }

    @Test fun theDistanceIsTheSpeedOverTime() {
        // 36 km/h = 10 m/s for 4 s
        val t = doubleArrayOf(0.0, 2.0, 4.0)
        val v = doubleArrayOf(36.0, 36.0, 36.0)
        assertEquals(40.0, GaugeMath.distanceM(t, v, 0.0, 4.0), 1e-6)
        assertEquals(20.0, GaugeMath.distanceM(t, v, 1.0, 3.0), 1e-6)
    }

    @Test fun aLongRunIsThinnedOut() {
        val t = DoubleArray(5000) { it * 0.01 }
        val v = DoubleArray(5000) { 50.0 }
        assertTrue(GaugeMath.chart(t, v, 0.0, 50.0, 100.0, 240).size <= 2 * 260)
    }

    // ---- the styles (Gauges.kt needs Android's Typeface: read as source, as LensMathTest does) ----

    @Test fun everyDesignIsOfferedAndNoDefaultChanges() {
        val src = File("src/main/java/kg/forester/sg/ui/GaugePresets.kt").readText()
        GaugeDesign.values().forEach { assertTrue(it.name, "design = GaugeDesign.${it.name}" in src) }
        val repo = File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertTrue("getString(\"tachStyle\", \"neon\")" in repo)
        assertTrue("getString(\"speedoStyle\", \"neon\")" in repo)
    }

    @Test fun theDialTakesTheDesignBranchAndAPictureStaysAPlainDial() {
        val g = File("src/main/java/kg/forester/sg/ui/Gauges.kt").readText()
        assertTrue("style.design != null && style.image == null) DesignDial(" in g)
    }

    @Test fun thePanelsAreOffOnEveryBoardByDefault() {
        val h = File("src/main/java/kg/forester/sg/data/HomeLayout.kt").readText()
        assertTrue("(1..6).map { \"s\$it\" } + GAUGE_PANELS" in h)
        assertTrue("EXTRA_CARDS.map { TileCfg(it, false," in h)
    }

    // ---- the hooks filled after the merge: miles, own marks, numbers scale ----

    @Test fun indicatorsFollowTheChosenDistanceUnit() {
        val locale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            val i = IndInputs(rangeKm = 161.0, tripKm = 16.1, odoKm = 1609.3)
            assertEquals("161 км", inds(i).fuel?.text)
            kg.forester.sg.data.DistanceUnits.miles = true
            val mi = inds(IndInputs(rangeKm = 161.0, tripKm = 16.1, odoKm = 1609.3))
            assertEquals("100 миль", mi.fuel?.text)
            assertEquals("10.0 миль", mi.trip?.text)
            assertEquals("1000", mi.odo?.text)
        } finally {
            kg.forester.sg.data.DistanceUnits.miles = false
            java.util.Locale.setDefault(locale)
        }
    }

    @Test fun designsDrawTheDriversOwnMarksAndSize() {
        val st = kg.forester.sg.ui.PresetDialStyles.first().copy(marks = listOf(40f, 60f, 90f, 110f, 900f), numberScale = 1.25f)
        val labels = kg.forester.sg.ui.designLabels(st, 0f, 200f, 20f, 1f, true)
        assertEquals(listOf("40", "60", "90", "110"), labels.map { it.second })
        assertEquals(1.25f, kg.forester.sg.ui.designNumeralScale(st), 0f)
        val plain = kg.forester.sg.ui.designLabels(kg.forester.sg.ui.PresetDialStyles.first(), 0f, 200f, 20f, 1f, true)
        assertTrue(plain.size > 4)
        assertEquals(1f, kg.forester.sg.ui.designNumeralScale(kg.forester.sg.ui.PresetDialStyles.first()), 0f)
    }

    @Test fun theRunSourceLineIsInTheDictionary() {
        val hit = File("src/main/assets/i18n/en.tsv").readLines().filter { it.startsWith("ЭБУ ~# Гц ×#") }
        assertEquals(2, hit.size)
        kg.forester.sg.data.I18n.install(File("src/main/assets/i18n/en.tsv").readLines())
        kg.forester.sg.data.I18n.english = true
        try {
            assertEquals("ECU ~4 Hz ×1.000", kg.forester.sg.data.tr("ЭБУ ~4 Гц ×1.000"))
            assertEquals("ECU ~4 Hz ×1.000 fitted to the run's GPS", kg.forester.sg.data.tr("ЭБУ ~4 Гц ×1.000 по GPS замера"))
        } finally {
            kg.forester.sg.data.I18n.english = false
            kg.forester.sg.data.I18n.install(emptyList())
        }
    }

    // ---- previews and the classic's window ----

    @Test fun samplesFillEveryIndicatorAPreviewDraws() {
        val i = GaugeMath.sampleInds("км", "В", "л")
        assertTrue(i.fuel?.share != null && i.coolant?.share != null && i.battery != null && i.gear != null)
        assertTrue(i.selector != null && i.trip != null && i.odo != null && i.clock != null)
        assertTrue("a sample shows no fault", i.check == null)
    }

    @Test fun classicWindowHoldsSomethingOrIsNotThere() {
        fun win(mode: NumberMode = NumberMode.DESIGN, room: Boolean = true, value: Boolean = true, speed: Boolean = false, other: Boolean = false) =
            GaugeMath.chromeWindow(mode, room, value, speed, other)
        assertEquals(ClassicWindow.VALUE, win())
        assertEquals(ClassicWindow.OTHER, win(speed = true, other = true))
        assertEquals("no odometer or trip: the speed itself", ClassicWindow.VALUE, win(speed = true))
        assertEquals("a card that isn't speed keeps its own value there", ClassicWindow.VALUE, win(other = true))
        assertEquals("no room", ClassicWindow.NONE, win(room = false))
        assertEquals("number hidden, nothing else", ClassicWindow.NONE, win(value = false))
        assertEquals(ClassicWindow.OTHER, win(value = false, speed = true, other = true))
    }

    @Test fun classicNumberOptionsReallyMoveTheNumber() {
        fun win(mode: NumberMode, other: Boolean = false) = GaugeMath.chromeWindow(mode, true, true, other, other)
        assertEquals(ClassicWindow.NONE, win(NumberMode.PLAIN))
        assertEquals(ClassicWindow.VALUE, win(NumberMode.LCD))
        assertEquals(ClassicWindow.NONE, win(NumberMode.FRAME))
        assertEquals("LCD: the number takes the window from the odometer", ClassicWindow.VALUE, win(NumberMode.LCD, other = true))
        assertEquals(ClassicWindow.OTHER, win(NumberMode.PLAIN, other = true))
    }

    @Test fun aNeedleWithNoDataRestsAtItsStart() {
        assertEquals(GaugeMath.FUEL_E_DEG, GaugeMath.fuelNeedleDeg(GaugeMath.needleRest(null)), 1e-4f)
        assertEquals(GaugeMath.COOL_C_DEG, GaugeMath.coolNeedleDeg(GaugeMath.needleRest(null)), 1e-4f)
        assertEquals(0.5f, GaugeMath.needleRest(0.5f), 1e-4f)
    }
}
