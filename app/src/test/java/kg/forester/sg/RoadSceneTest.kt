package kg.forester.sg

import kg.forester.sg.data.CarKind
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.RoadScene
import kg.forester.sg.data.Tier
import kg.forester.sg.data.TileCfg
import kg.forester.sg.data.lookOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RoadSceneTest {
    // ---- the dashes ----

    @Test fun aStandingCarMovesNoDash() {
        assertEquals(0.3f, RoadScene.phaseAfter(0.3f, 0f, 0.1f), 0f)
        assertEquals(0.3f, RoadScene.phaseAfter(0.3f, 0.2f, 0.1f), 0f)
    }

    @Test fun thePhaseGoesWithSpeedAndTime() {
        val at60 = RoadScene.phaseAfter(0f, 60f, 0.02f)
        assertEquals(at60 * 2, RoadScene.phaseAfter(0f, 120f, 0.02f), 1e-5f)
        assertEquals(at60 * 2, RoadScene.phaseAfter(0f, 60f, 0.04f), 1e-5f)
        // the road's own scale: 60 km/h is 16.7 m/s over a period of 6 m
        assertEquals(60f / 3.6f / RoadScene.DASH_PERIOD_M * 0.02f, at60, 1e-5f)
    }

    @Test fun thePhaseStaysInOnePeriod() {
        var p = 0f
        repeat(10_000) {
            p = RoadScene.phaseAfter(p, 137f, 1 / 60f)
            assertTrue(p >= 0f && p < 1f)
        }
    }

    @Test fun aLongFrameNeverLooksLikeDashesGoingBack() {
        // more than half a period in one step reads as the wheel turning backwards; so does the return from the background
        val step = RoadScene.phaseAfter(0f, 200f, 1f / 15f)
        assertTrue("$step", step < 0.5f)
        assertTrue(RoadScene.phaseAfter(0f, 100f, 3600f) < 0.5f)
    }

    @Test fun dashesComeTowardsTheViewer() {
        val a = FloatArray(RoadScene.MAX_DASHES * 2)
        val b = FloatArray(RoadScene.MAX_DASHES * 2)
        val na = RoadScene.dashes(0.1f, a)
        val nb = RoadScene.dashes(0.2f, b)
        assertTrue(na >= 5)
        assertEquals(na, nb)
        for (i in 0 until na) {
            assertTrue("a farther dash is nearer the horizon", i == 0 || a[i * 2] < a[(i - 1) * 2 + 1])
            assertTrue(a[i * 2] > a[i * 2 + 1]) // the near end is lower on the screen
            assertTrue(b[i * 2 + 1] > a[i * 2 + 1])
            assertTrue(a[i * 2] <= 1f && a[i * 2 + 1] > 0f)
        }
    }

    @Test fun oneWholePeriodIsTheSamePicture() {
        val a = FloatArray(RoadScene.MAX_DASHES * 2)
        val b = FloatArray(RoadScene.MAX_DASHES * 2)
        val na = RoadScene.dashes(0f, a)
        val nb = RoadScene.dashes(0.9999f, b)
        // the nearest one has just gone off the bottom, the second has taken its place
        assertEquals(a[0], b[0], 0.01f)
        assertTrue(kotlin.math.abs(na - nb) <= 1)
    }

    // ---- the blades ----

    @Test fun bladesLightWithSpeed() {
        assertEquals(0, RoadScene.litBlades(0f, 60))
        assertEquals(30, RoadScene.litBlades(RoadScene.TOP_KMH / 2, 60))
        assertEquals(60, RoadScene.litBlades(RoadScene.TOP_KMH, 60))
        assertEquals(60, RoadScene.litBlades(RoadScene.TOP_KMH * 2, 60))
        assertEquals(0, RoadScene.litBlades(-5f, 60))
        // a crawling car shows something; what is left of a needle settling at 0 shows nothing
        assertEquals(1, RoadScene.litBlades(1f, 60))
        assertEquals(0, RoadScene.litBlades(0.1f, 60))
    }

    @Test fun litBladesNeverGoBackAsSpeedGrows() {
        var was = 0
        var k = 0f
        while (k < 260f) {
            val n = RoadScene.litBlades(k, 96)
            assertTrue(n >= was)
            was = n
            k += 0.5f
        }
    }

    @Test fun theArcIsANearlyClosedSemicircleOpenBelow() {
        val first = RoadScene.bladeDeg(0, 60)
        val last = RoadScene.bladeDeg(59, 60)
        assertEquals(RoadScene.SWEEP_DEG, last - first, 0.01f)
        assertTrue(RoadScene.SWEEP_DEG in 190f..240f)
        // both ends below the centre (y down), the middle straight above
        assertTrue(sin(Math.toRadians(first.toDouble())) > 0)
        assertTrue(sin(Math.toRadians(last.toDouble())) > 0)
        val mid = Math.toRadians(RoadScene.bladeDeg(30, 61).toDouble())
        assertEquals(-1.0, sin(mid), 1e-3)
        assertEquals(0.0, cos(mid), 1e-3)
    }

    // ---- the layout, in dp: a card of one and two columns and rows on the screens the app runs on ----

    private val oneByOne = RoadScene.layout(230f, 105f)
    private val twoByOne = RoadScene.layout(480f, 105f)
    private val twoByTwo = RoadScene.layout(480f, 230f)
    private val big = RoadScene.layout(1240f, 540f)
    private val allSizes = listOf(oneByOne, twoByOne, twoByTwo, big, RoadScene.layout(180f, 300f), RoadScene.layout(900f, 80f), RoadScene.layout(56f, 40f))

    @Test fun theArcStaysInTheCard() {
        for (l in allSizes) {
            assertTrue("$l", l.cy - l.r >= 0f && l.cx - l.r >= 0f && l.cx + l.r <= l.w)
            assertTrue("$l", l.cy + l.r * 0.4f <= l.h)
            assertEquals(l.w / 2, l.cx, 0.01f)
        }
    }

    @Test fun theNumberFitsInsideTheArc() {
        for (l in allSizes) {
            val threeDigits = 3 * 0.58f * l.numberSize
            assertTrue("$l", threeDigits <= 2 * l.bladeInner)
            assertTrue("$l", l.numberCy - l.numberSize * 0.6f >= l.cy - l.bladeInner)
        }
    }

    @Test fun theCarIsLowOnTheRoadAndUnderTheText() {
        for (l in listOf(oneByOne, twoByOne, twoByTwo, big)) {
            assertTrue("$l", l.carH > 0f && l.carW > l.carH)
            assertTrue("$l", l.carGround <= l.h && l.carGround > l.horizonY)
            assertTrue("$l", l.carGround - l.carH >= l.unitCy + l.unitSize * 0.5f)
            assertTrue("car in its lane $l", l.carW / 2 <= l.laneX * (l.carGround - l.horizonY) / (l.h - l.horizonY) * 1.05f)
        }
    }

    @Test fun theHorizonIsLow() {
        for (l in allSizes) assertTrue("$l", l.horizonY >= l.h * 0.5f && l.horizonY < l.h)
    }

    @Test fun aSmallCardShowsNoSideTexts() {
        assertEquals(0f, oneByOne.sideW, 0f)
        assertFalse(oneByOne.titleOn)
    }

    @Test fun aWideCardHasSideTextsBesideTheArc() {
        for (l in listOf(twoByOne, twoByTwo, big)) {
            assertTrue("$l", l.sideW >= 90f)
            assertTrue("$l", l.leftCx + l.sideW / 2 <= l.cx - l.r)
            assertTrue("$l", l.rightCx - l.sideW / 2 >= l.cx + l.r)
            assertTrue("$l", l.leftCx - l.sideW / 2 >= 0f && l.rightCx + l.sideW / 2 <= l.w)
        }
    }

    @Test fun onlyATallCardHasRoomForTheTrack() {
        assertFalse(twoByOne.titleOn)
        assertTrue(twoByTwo.titleOn)
        assertTrue(big.titleOn)
    }

    @Test fun thinBladesKeepTheirGapsAtEverySize() {
        for (l in allSizes) {
            assertTrue("$l", l.blades in 24..120)
            val pitch = Math.toRadians(RoadScene.SWEEP_DEG.toDouble()) * l.r / (l.blades - 1)
            assertTrue("$l", l.bladeWidth < pitch)
        }
    }

    @Test fun aCardTooLowForAllOfItKeepsTheNumber() {
        val tiny = RoadScene.layout(56f, 40f)
        assertTrue(tiny.numberSize > 0f)
        assertFalse(tiny.unitOn)
        assertEquals(0f, tiny.carH, 0f)
    }

    // ---- the tiers ----

    @Test fun aWeakUnitGoesSlowerAndWithoutTheReflection() {
        assertEquals(15f, lookOf(Tier.LOW).sceneHz, 0f)
        assertFalse(lookOf(Tier.LOW).sceneReflection)
        assertEquals(30f, lookOf(Tier.MID).sceneHz, 0f)
        assertTrue(lookOf(Tier.MID).sceneReflection)
        assertEquals(kg.forester.sg.data.EVERY_FRAME, lookOf(Tier.HIGH).sceneHz, 0f)
        assertTrue(lookOf(Tier.HIGH).sceneReflection)
    }

    // ---- the layouts ----

    private fun onCards(text: String) = HomeLayout.parse(text).filter { it.on }

    private fun format(tiles: List<TileCfg>) =
        HomeLayout.board.format(tiles + HomeLayout.board.defaults.filter { d -> tiles.none { it.id == d.id } }.map { it.copy(on = false) })

    @Test fun aSubaruGetsNoSceneOnItsFactoryScreens() {
        assertFalse(HomeLayout.board.defaults.first { it.id == HomeLayout.ROAD }.on)
        assertEquals("", HomeLayout.forCar("", false, false))
        assertFalse(onCards(HomeLayout.atmoDefault()).any { it.id == HomeLayout.ROAD })
        assertFalse(onCards(HomeLayout.linkDefault()).any { it.id == HomeLayout.ROAD })
    }

    @Test fun aSavedLayoutGetsTheCardSwitchedOff() {
        val saved = "coolant:1:1::;intake:1:1::"
        val road = HomeLayout.parse(saved).first { it.id == HomeLayout.ROAD }
        assertFalse(road.on)
        assertEquals(2, road.span)
    }

    @Test fun anotherMakesFirstScreenHasTheSceneInTheRoomItLeft() {
        val cards = onCards(HomeLayout.forCar("", true, false))
        assertEquals(HomeLayout.ROAD, cards.last().id)
        assertEquals(2, cards.last().span)
        assertEquals(HomeLayout.COLUMNS * 3, cards.sumOf { it.span })
        assertEquals(3, HomeLayout.rows(cards).size)
    }

    @Test fun whatACarFillsLeavesRoomForTheScene() {
        val few = HomeLayout.obdTiles(CarKind.obdHomeCards(setOf("coolant", "intake", "throttle", "map", "timing"), false))
        assertEquals(HomeLayout.ROAD, few.last().id)
        assertEquals(2, few.last().span)
        assertTrue(few.dropLast(1).all { it.span == 1 })
        assertTrue(HomeLayout.rows(few).size <= 3)
    }

    @Test fun aCarFillingAllRowsGivesUpTheLeastLookedAtForTheScene() {
        val ids = CarKind.obdHomeCards(setOf("coolant", "intake", "battery", "throttle", "maf", "afr", "afcorr", "aflearn", "timing", "pedal", "atm", "rpm", "speed"), false)
        assertEquals(15, ids.size)
        val tiles = HomeLayout.obdTiles(ids)
        assertTrue(tiles.any { it.id == HomeLayout.ROAD })
        assertEquals(3, HomeLayout.rows(tiles).size)
        assertTrue(tiles.sumOf { it.span } <= 15)
        assertFalse(tiles.any { it.id == "atm" || it.id == "pedal" })
        // the engine's cards and the fuel and GPS ones stay
        assertTrue(tiles.map { it.id }.containsAll(listOf("coolant", "intake", "battery", "throttle", "maf", "afr", "afcorr", "aflearn", "timing")))
        assertTrue(tiles.map { it.id }.containsAll(listOf("fuel", "per100", "range", "gps")))
    }

    @Test fun theSpareCardsGiveWayOneByOne() {
        val ids = (1..12).map { "x$it" } + "rpm" + "speed" + "atm"
        assertEquals((1..12).map { "x$it" } + "atm" + HomeLayout.ROAD, HomeLayout.obdTiles(ids).map { it.id })
    }

    @Test fun aFullLayoutWithoutWhatGaveWayIsStillTheFactoryOne() {
        val ids = CarKind.obdHomeCards(setOf("coolant", "intake", "battery", "throttle", "maf", "afr", "afcorr", "aflearn", "timing", "pedal", "atm", "rpm", "speed"), false)
        assertTrue(HomeLayout.isUntouched(format(HomeLayout.obdTiles(ids))))
    }

    @Test fun thereIsNoSceneWhereItDoesNotFit() {
        val full = (1..15).map { "x$it" }
        assertEquals(full, HomeLayout.obdTiles(full).map { it.id })
    }

    @Test fun theAppsOwnLayoutWithTheSceneStaysUntouched() {
        val text = format(HomeLayout.obdTiles(CarKind.obdHomeCards(setOf("coolant", "intake", "throttle", "map", "timing"), false)))
        assertTrue(HomeLayout.isUntouched(text))
    }

    @Test fun aSceneTheDriverNarrowedIsHis() {
        val tiles = HomeLayout.obdTiles(CarKind.obdHomeCards(setOf("coolant", "intake", "throttle", "map", "timing"), false))
        val text = format(tiles.map { if (it.id == HomeLayout.ROAD) it.copy(span = 1) else it })
        assertFalse(HomeLayout.isUntouched(text))
        assertEquals(text, HomeLayout.effective(text, small = true))
    }

    @Test fun aSevenInchFactoryScreenKeepsTheSceneAndSixCards() {
        val text = format(HomeLayout.obdTiles(CarKind.obdHomeCards(setOf("coolant", "intake", "battery", "throttle", "maf", "afr", "timing"), false)))
        val cards = onCards(HomeLayout.effective(text, small = true))
        assertEquals(HomeLayout.ROAD, cards.last().id)
        assertEquals(7, cards.size)
        assertEquals(4, HomeLayout.columnsFor(cards, small = true))
        assertEquals(listOf(4, 3), HomeLayout.rows(cards, 4).map { it.size })
    }

    @Test fun aFactoryScreenWithoutTheSceneIsAsItWas() {
        val cards = onCards(HomeLayout.effective("", small = true))
        assertEquals(8, cards.size)
        assertFalse(cards.any { it.id == HomeLayout.ROAD })
    }
}
