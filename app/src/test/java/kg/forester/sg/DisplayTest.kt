package kg.forester.sg

import kg.forester.sg.data.BoxBoard
import kg.forester.sg.data.BoxFont
import kg.forester.sg.data.DispCfg
import kg.forester.sg.data.DispKind
import kg.forester.sg.data.DispLayout
import kg.forester.sg.data.Displays
import kg.forester.sg.data.Relays
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.DriveBoard
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.OtherTurbo
import kg.forester.sg.service.DisplayEngine
import kg.forester.sg.ui.BottomBarCfg
import kg.forester.sg.ui.CarTokens
import kg.forester.sg.ui.carDensity
import kg.forester.sg.ui.carMmPerDp
import kg.forester.sg.ui.compactChrome
import kg.forester.sg.ui.contrast
import kg.forester.sg.ui.isPhoneBy
import kg.forester.sg.ui.liftForContrast
import kg.forester.sg.ui.ScaleMigration
import kg.forester.sg.ui.diagonalPromptDue
import kg.forester.sg.ui.diagonalPromptShows
import kg.forester.sg.ui.fitValueSp
import kg.forester.sg.ui.migrateScale
import kg.forester.sg.ui.parkedSp
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisplayTest {
    private val values = mapOf("coolant" to 97f, "battery" to 14.13f, "boost" to 1.234f, "rpm" to 3250f, "intake" to -5f)
    private fun v(id: String) = values[id]

    @Before fun clean() { SensorBox.slots.value = emptyList(); Relays.channels.value = emptyList(); Displays.list.value = emptyList() }
    @After fun after() { Displays.list.value = emptyList() }

    @Test fun tmFitsFourDigitsWithDegreesWhereThereIsRoom() {
        assertEquals("97~C", Displays.tmText("coolant", 97f))
        assertEquals(" 105", Displays.tmText("coolant", 105f))
        assertEquals("-5~C", Displays.tmText("intake", -5f))
        assertEquals(" 14.1", Displays.tmText("battery", 14.13f))
        assertEquals(" 1.23", Displays.tmText("boost", 1.234f))
        assertEquals("3250", Displays.tmText("rpm", 3250f))
        assertEquals("----", Displays.tmText("coolant", null))
    }

    @Test fun tmSegmentsAreTheBoxsOwn() {
        assertArrayEquals(intArrayOf(0x6F, 0x07, 0x63, 0x39), Displays.tmSegments("97~C"))
        // a dot lights the digit before it and takes no place
        assertArrayEquals(intArrayOf(0, 0x06 or 0x80, 0x5B, 0x4F), Displays.tmSegments(" 1.23"))
        assertArrayEquals(IntArray(4) { 0x7F or 0x80 }, Displays.tmSegments("8.8.8.8."))
    }

    @Test fun oledGetsCp1251AndLcdGetsTranslit() {
        assertEquals(listOf(0xCC, 0xC0, 0xD1, 0xCB, 0xCE, 0x20, 0x39, 0x37, 0x7E, 0x43), Displays.wire("Масло 97°C", DispKind.OLED).map { it.code })
        assertEquals("MASLO 97ßC", Displays.wire("Масло 97°C", DispKind.LCD1602))
        assertEquals("ZHYU", Displays.wire("ЖЮ", DispKind.LCD2004))
        // every character the OLED gets has a glyph in the box's font
        val all = Displays.wire("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ .,:-+%/°", DispKind.OLED)
        all.forEach { assertTrue("no glyph for ${it.code}", BoxFont.glyphs.containsKey(it.code)) }
    }

    @Test fun linesFitTheScreenAndTheBoxsLineBuffer() {
        val cases = listOf(
            DispCfg(1, DispKind.OLED, DispLayout.ONE, listOf("coolant")),
            DispCfg(1, DispKind.OLED, DispLayout.TWO, listOf("coolant", "battery")),
            DispCfg(1, DispKind.OLED, DispLayout.FOUR, listOf("coolant", "battery", "boost", "rpm")),
            DispCfg(1, DispKind.LCD1602, items = listOf("coolant", "battery")),
            DispCfg(1, DispKind.LCD2004, items = listOf("coolant", "battery", "boost", "rpm")),
        )
        for (d in cases) {
            val lines = Displays.lines(d, ::v)
            assertTrue(d.toString(), lines.isNotEmpty())
            for (l in lines) {
                val w = Displays.wire(l.text, d.kind)
                val width = when (d.kind) { DispKind.OLED -> 128 / (6 * l.scale); DispKind.LCD1602 -> 16; else -> 20 }
                assertTrue("'$w' wider than $width", w.length <= width)
                assertTrue(l.row + l.scale <= if (d.kind == DispKind.OLED) 8 else d.slots)
                assertTrue("L ${l.row} ${l.scale} $w".length < 48) // sensorbox.ino: char line[48]
            }
        }
        // a text LCD row is the name on the left and the value on the right, the full width
        val lcd = Displays.lines(DispCfg(1, DispKind.LCD1602, items = listOf("coolant", "battery")), ::v)
        assertEquals("АНТИФРИЗ    97°C", lcd[0].text)
        assertEquals(16, lcd[1].text.length)
        assertTrue(lcd[1].text.endsWith("14.1В"))
        // the big value on one OLED line
        val one = Displays.lines(DispCfg(1, DispKind.OLED, DispLayout.ONE, listOf("coolant")), ::v)
        assertEquals(" 97°C", one.first { it.scale == 3 }.text)
    }

    @Test fun testPatternFitsToo() {
        for (k in DispKind.entries) for (l in DisplayEngine.testLines(DispCfg(1, k))) {
            assertTrue("L ${l.row} ${l.scale} ${Displays.wire(l.text, k)}".length < 48)
        }
    }

    @Test fun displaysTakeTheirPinsFromTheOutputs() {
        Displays.list.value = listOf(DispCfg(1, DispKind.OLED), DispCfg(2, DispKind.TM1637, clk = "D4", dio = "D5"))
        assertEquals(setOf("D2", "D3", "D4", "D5"), Displays.pins(BoxBoard.PROMICRO))
        assertEquals(setOf("A4", "A5", "D4", "D5"), Displays.pins(BoxBoard.NANO))
        // the next relay goes past them
        assertEquals("D0", Relays.free(BoxBoard.PROMICRO)!!.second)
        assertFalse(Displays.freeOutPins(BoxBoard.PROMICRO).any { it in setOf("D2", "D3", "D4", "D5") })
        assertEquals("D3", Relays.free(BoxBoard.NANO)!!.second)
        Displays.list.value = listOf(DispCfg(2, DispKind.TM1637, clk = "D3", dio = "D4"))
        assertEquals("D5", Relays.free(BoxBoard.NANO)!!.second)
        // on I2C one OLED and one text LCD together, one TM1637
        Displays.list.value = listOf(DispCfg(1, DispKind.OLED))
        assertTrue(Displays.canAdd(DispKind.LCD1602))
        assertFalse(Displays.canAdd(DispKind.OLED_13))
        Displays.list.value = listOf(DispCfg(2, DispKind.TM1637, clk = "D3", dio = "D4"))
        assertTrue(Displays.canAdd(DispKind.LCD2004))
        assertFalse(Displays.canAdd(DispKind.TM1637))
    }

    @Test fun boxSensorsGetShortNames() {
        assertEquals("ДАВЛ МАСЛА", Displays.short("Давление масла 0–10 бар"))
        assertEquals("МАСЛО", Displays.short("Температура масла, Bosch M12-H"))
        assertEquals("AFR", Displays.short("ШДК Innovate LC-2 / LC-1 / MTX-L"))
        assertEquals("Подогрев сидений", Displays.short("Подогрев сидений (мой)"))
        assertEquals("1-2", Displays.wire("1–2", DispKind.OLED))
    }

    @Test fun colourScreensKeepEverythingOnTheGlass() {
        for (k in DispKind.entries.filter { it.colour }) for (l in DispLayout.entries) {
            val d = DispCfg(1, k, l, listOf("coolant", "battery", "boost", "rpm"), cs = "D5", dc = "D6")
            val ops = Displays.colourOps(d, ::v) { id, x -> id == "coolant" && x > 95f }
            assertEquals("$k $l keys", ops.size, ops.map { it.key }.toSet().size)
            for (op in ops) when (op) {
                is kg.forester.sg.data.ColOp.Box -> assertTrue("$k $l $op", op.x >= 0 && op.y >= 0 && op.x + op.w <= k.w && op.y + op.h <= k.h)
                is kg.forester.sg.data.ColOp.Txt -> {
                    assertTrue("$k $l $op", op.x >= 0 && op.x + op.text.length * 6 * op.s <= k.w && op.y + 8 * op.s <= k.h)
                    assertTrue(Displays.wire(op, k).length < 72) // sensorbox.ino: char line[72]
                }
            }
            // coolant 97 is past the warning given: its number is red
            assertTrue(ops.any { it is kg.forester.sg.data.ColOp.Txt && it.fg == Displays.RED })
        }
        for (k in DispKind.entries.filter { it.colour }) assertTrue(Displays.colourTest(k).all { Displays.wire(it, k).length < 72 })
    }

    @Test fun theNarrowOledHasFourPages() {
        for (l in DispLayout.entries) for (line in Displays.lines(DispCfg(1, DispKind.OLED_091, l, listOf("coolant", "battery", "boost", "rpm")), ::v)) {
            assertTrue("$l $line", line.row + line.scale <= 4)
            assertTrue(Displays.wire(line.text, DispKind.OLED_091).length <= 128 / (6 * line.scale))
        }
        for (line in DisplayEngine.testLines(DispCfg(1, DispKind.OLED_091))) assertTrue(line.row + line.scale <= 4)
    }

    @Test fun spiScreenWithoutCsStaysOffTheThermocoupleBus() {
        assertEquals(null, Displays.unfit(DispKind.IPS_240, BoxBoard.NANO))
        SensorBox.slots.value = listOf(kg.forester.sg.data.BoxSlot.of("TC0", kg.forester.sg.data.BoxPresets.all.first { it.kind == kg.forester.sg.data.BoxKind.TC }))
        assertTrue(Displays.unfit(DispKind.IPS_240, BoxBoard.NANO) != null)
        assertEquals(null, Displays.unfit(DispKind.IPS_320, BoxBoard.NANO))
    }

    @Test fun savedAndReadBack() {
        val d = DispCfg(3, DispKind.TM1637, DispLayout.ONE, listOf("box_A0"), "D6", "D7", 6)
        assertEquals(d, DispCfg.fromJson(d.toJson()))
    }

    @Test fun densityUsesFitOnHeadUnits() {
        // the dpi isn't even asked: the same canvas of about 1180 by 660, up and down from any panel
        for ((w, h) in listOf(1024 to 600, 800 to 480, 1280 to 720, 2000 to 1200, 1920 to 720)) {
            val d = carDensity(w, h, 0)
            assertTrue("$w x $h: ${w / d} wide", w / d >= 1179f)
            assertTrue("$w x $h: ${h / d} tall", h / d >= 659f)
            assertTrue("$w x $h: neither side fits", w / d < 1181f || h / d < 661f)
        }
        // 2000×1200 at 160 dpi is no longer drawn smaller than a 7"
        assertTrue(carDensity(2000, 1200, 0) > 1.6f)
        // held upright: the same size of everything as lying down
        assertEquals(carDensity(1280, 720, 0), carDensity(720, 1280, 0), 0.001f)
    }

    @Test fun canvasNeverBelow900() {
        for (scale in listOf(0, 80, 100, 110, 120, 145, 300)) for ((w, h) in listOf(1024 to 600, 800 to 480, 1280 to 720, 2000 to 1200)) {
            val d = carDensity(w, h, scale)
            assertTrue("$w x $h at $scale %: ${w / d} wide", w / d >= 899.9f)
        }
        // the scale still works, on top of the fit
        assertEquals(carDensity(1280, 720, 0) * 1.1f, carDensity(1280, 720, 110), 0.001f)
    }

    @Test fun compactAlwaysHasMenu() {
        assertTrue(compactChrome(phone = true).menu)
        assertTrue(compactChrome(phone = false).menu)
        // the launch buttons stay the head unit's, a phone has its own launcher
        assertTrue(compactChrome(phone = false).quickLaunch)
        assertFalse(compactChrome(phone = true).quickLaunch)
    }

    @Test fun tokensMatchTable() {
        fun check(mm: Float, value: Int, label: Int, secondary: Int, tap: Int, gap: Int) {
            val t = CarTokens(mm)
            assertEquals("value at $mm", value.toFloat(), t.value.value, 1f)
            assertEquals("label at $mm", label.toFloat(), t.label.value, 1f)
            assertEquals("secondary at $mm", secondary.toFloat(), t.secondary.value, 1f)
            assertEquals("tap at $mm", tap.toFloat(), t.tap.value, 1f)
            assertEquals("gap at $mm", gap.toFloat(), t.gap.value, 1f)
        }
        check(0.130f, 50, 35, 26, 92, 29)
        check(0.169f, 39, 27, 20, 71, 23)
        check(0.190f, 34, 24, 18, 64, 20)
        check(0.203f, 32, 23, 17, 64, 19)
        // the diagonal and the pixels give the table's millimetres; 10.1" with 1024×600 is a 10.1" all the same
        assertEquals(0.130f, carMmPerDp(7f, 1024, 600), 0.002f)
        assertEquals(0.169f, carMmPerDp(9f, 1280, 720), 0.002f)
        assertEquals(0.190f, carMmPerDp(10.1f, 1280, 720), 0.002f)
        assertEquals(0.203f, carMmPerDp(11f, 2000, 1200), 0.002f)
        assertEquals(0.188f, carMmPerDp(10.1f, 1024, 600), 0.002f)
        // not told: the 10.1" tokens
        assertEquals(0.19f, carMmPerDp(0f, 1024, 600), 0.0001f)
    }

    @Test fun headUnitIsNoPhoneWhateverItsDpi() {
        // 1024×600 above 160 dpi: smallest width under 600, still a head unit
        assertFalse(isPhoneBy("fyt", telephony = true, smallestDp = 400, naturalLandscape = false))
        assertFalse(isPhoneBy("generic", telephony = false, smallestDp = 400, naturalLandscape = true))
        assertFalse(isPhoneBy("generic", telephony = true, smallestDp = 400, naturalLandscape = true))
        assertFalse(isPhoneBy("generic", telephony = true, smallestDp = 720, naturalLandscape = false))
        assertTrue(isPhoneBy("generic", telephony = true, smallestDp = 411, naturalLandscape = false))
    }

    @Test fun ownThemeColourKeepsItsContrast() {
        val card = 0x111522
        assertEquals(21f, contrast(0xFFFFFF, 0x000000), 0.01f)
        for (rgb in listOf(0x0000FF, 0x101010, 0x800000, 0x4B0082, 0x000000)) {
            assertTrue("%06X".format(rgb), contrast(liftForContrast(rgb, card, 4.5f), card) >= 4.5f)
        }
        // a colour that passes stays as it is
        assertEquals(0xA855F7, liftForContrast(0xA855F7, card, 4.5f))
        // and the texts: the new secondary colour on the panels
        assertTrue(contrast(0xB4BACC, card) >= 4.5f)
    }

    @Test fun sevenInchHomeIsFourByTwoAndSavedLayoutsStay() {
        val cards = HomeLayout.parse(HomeLayout.effective("", small = true))
        val rows = HomeLayout.rows(cards, HomeLayout.columnsFor(cards, small = true))
        assertEquals(listOf(4, 4), rows.map { it.size })
        // the factory screens written for a car with no turbo or a Link are still the factory's
        assertEquals(listOf(4, 4), HomeLayout.rows(HomeLayout.parse(HomeLayout.effective(HomeLayout.atmoDefault(), true)), 4).map { it.size })
        // anything the driver saved is read as it was, free ones too
        val saved = HomeLayout.board.format(HomeLayout.board.defaults.map { it.copy(on = it.id == "maf") })
        assertEquals(saved, HomeLayout.effective(saved, small = true))
        val free = HomeLayout.board.format(HomeLayout.board.defaults, free = true)
        assertEquals(free, HomeLayout.effective(free, small = true))
        // and larger screens keep the factory's grid
        assertEquals("", HomeLayout.effective("", small = false))
        assertEquals(5, HomeLayout.columnsFor(HomeLayout.parse(""), small = false))
        // a saved screen of 15 cards keeps its five columns on a 7", too
        assertEquals(5, HomeLayout.columnsFor(HomeLayout.parse(""), small = true))
    }

    private fun homeOn(saved: String) = HomeLayout.parse(HomeLayout.effective(saved, small = true)).filter { it.on }.map { it.id }
    private fun driveOn(turbo: Boolean) = DriveBoard.parse(HomeLayout.driveEffective("", small = true, turbo = turbo)).filter { it.on }.map { it.id }

    @Test fun sevenInchHomeShowsTheChosenEight() {
        assertEquals(listOf("coolant", "boost", "intake", "battery", "afr", "knock", "per100", "timing"), homeOn(""))
        // no turbo: no boost, the range is the eighth; never the ATF, a manual has none
        assertEquals(listOf("coolant", "intake", "battery", "afr", "knock", "per100", "timing", "range"), homeOn(HomeLayout.atmoDefault()))
        // a Link: the same order, what it hasn't got filled from its own list
        assertEquals(listOf("coolant", "boost", "intake", "battery", "afr", "per100", "timing", "throttle"), homeOn(HomeLayout.linkDefault()))
    }

    @Test fun sevenInchDriveGoesByTheCar() {
        assertEquals(listOf("throttle", "boost", "load", "knock", "chartBoost", "chartAfr", "injDuty", "maf", "avcs"), driveOn(true))
        assertEquals(listOf("throttle", "pedal", "load", "knock", "chartTiming", "chartAfr", "injDuty", "maf", "avcs"), driveOn(false))
        val rows = DriveBoard.rows(DriveBoard.parse(HomeLayout.driveEffective("", small = true, turbo = true)))
        assertEquals(listOf(4, 2, 3), rows.map { it.size })
        assertEquals("", HomeLayout.driveEffective("", small = false, turbo = true))
        val saved = DriveBoard.format(DriveBoard.defaults)
        assertEquals(saved, HomeLayout.driveEffective(saved, small = true, turbo = false))
    }

    @Test fun driveKnowsTheTurboTheWayHomeDoes() {
        fun t(init: Boolean, wgdc: Boolean = false, link: Boolean = false, linkTurbo: Boolean = false) = HomeLayout.carHasTurbo(init, wgdc, link, linkTurbo)
        assertTrue("nothing known yet: the factory picture", t(init = false))
        assertTrue(t(init = true, wgdc = true))
        assertFalse(t(init = true, wgdc = false))
        assertTrue(t(init = false, link = true, linkTurbo = true))
        assertFalse(t(init = false, link = true, linkTurbo = false))
    }

    @Test fun anotherMakesTurboStartsUnknownAndOnlyLatches() {
        // the owner's ruling (O1) replaced the old one: unknown shows the picture without boost, and a turbo latches
        var t = OtherTurbo().identified("VIN1")
        assertEquals(kg.forester.sg.data.TurboState.UNKNOWN, t.applied)
        assertFalse("unknown is the picture without boost", t.shown)
        t = t.with(kg.forester.sg.data.TurboState.TURBO)
        assertFalse("nothing moves during this connection", t.shown)
        t = t.connected()
        assertTrue(t.shown)
        t = t.connected().identified("VIN1")
        assertTrue(t.shown)
        t = t.identified("VIN2")
        assertEquals(kg.forester.sg.data.TurboState.UNKNOWN, t.state)
        assertFalse(t.shown)
        // the VIN coming a second after a connect without one is the same car, not a change
        var v = OtherTurbo().identified("").with(kg.forester.sg.data.TurboState.TURBO).connected()
        v = v.identified("VIN3")
        assertTrue(v.shown)
        assertEquals(v, v.identified(""))
        assertEquals(v, OtherTurbo.parse(v.format()))
        assertEquals(OtherTurbo(), OtherTurbo.parse(""))
    }

    @Test fun diagonalPromptWaitsForAStopWithNoAlert() {
        assertTrue(diagonalPromptShows(due = true, ecuKmh = 0, gpsKmh = 0f, alert = false))
        // a car that tells no speed (nothing connected, no fix): nothing says it moves
        assertTrue(diagonalPromptShows(due = true, ecuKmh = null, gpsKmh = null, alert = false))
        assertFalse("nothing due", diagonalPromptShows(due = false, ecuKmh = 0, gpsKmh = 0f, alert = false))
        assertFalse("alert", diagonalPromptShows(due = true, ecuKmh = 0, gpsKmh = 0f, alert = true))
        assertFalse("ECU speed", diagonalPromptShows(due = true, ecuKmh = 30, gpsKmh = null, alert = false))
        assertFalse("GPS speed", diagonalPromptShows(due = true, ecuKmh = null, gpsKmh = 30f, alert = false))
        // either source moving is moving
        assertFalse(diagonalPromptShows(due = true, ecuKmh = 0, gpsKmh = 30f, alert = false))
        // GPS noise at a stop isn't moving
        assertTrue(diagonalPromptShows(due = true, ecuKmh = 0, gpsKmh = 1.5f, alert = false))
        // closed by moving or an alert it isn't answered: still due at the next stop
        val asked = false
        assertTrue(diagonalPromptDue(phone = false, inches = 0f, asked = asked, setupDone = true, notice = false))
        assertTrue(diagonalPromptShows(diagonalPromptDue(false, 0f, asked, true, false), 0, 0f, false))
    }

    @Test fun smallControlsGrowOnlyToTheParkedTarget() {
        assertEquals(56.dp, kg.forester.sg.ui.parkedTapOf(56.dp))
        // Media's glance and the plain 48 dp floor keep the control's own size
        assertEquals(0.dp, kg.forester.sg.ui.parkedTapOf(48.dp))
    }

    @Test fun sevenInchBottomBarHasNoBarsUntilAskedFor() {
        assertTrue(BottomBarCfg.parse(BottomBarCfg.effective(BottomBarCfg.DEFAULT, small = true)).none { it.bar })
        assertEquals(BottomBarCfg.DEFAULT, BottomBarCfg.effective(BottomBarCfg.DEFAULT, small = false))
        // the driver's own choice stays
        assertEquals("range:1,oil:0", BottomBarCfg.effective("range:1,oil:0", small = true))
    }

    @Test fun parkedTextHasTheFloorAndKeepsItsOrder() {
        val car = CarTokens(0.19f)
        val sec = car.secondary.value
        assertEquals(sec, parkedSp(11f, car), 0.01f)
        assertEquals(sec, parkedSp(12f, car), 0.01f)
        assertTrue(parkedSp(15f, car) > parkedSp(12f, car))
        assertTrue(parkedSp(17f, car) > parkedSp(15f, car))
        assertTrue(parkedSp(18f, car) > parkedSp(17f, car))
        // what is large already stays as it is
        assertEquals(60f, parkedSp(60f, car), 0.01f)
    }

    @Test fun tokenSizesPassTheFloorUnchanged() {
        for (mm in listOf(0.130f, 0.169f, 0.190f, 0.203f)) {
            val car = CarTokens(mm)
            assertEquals(car.secondary.value, parkedSp(car.secondary.value, car), 0f)
            assertEquals(car.label.value, parkedSp(car.label.value, car), 0f)
            assertEquals(car.value.value, parkedSp(car.value.value, car), 0f)
        }
    }

    @Test fun aValueKeepsItsSizeAsItGrowsADigit() {
        fun sp(text: String, widest: String?, unit: String = "л/100", widestUnit: String? = null) =
            fitValueSp(text, widest, unit, widestUnit, maxSp = 50f, roomW = 200f, roomH = 80f, unitShare = 0.52f)
        assertEquals(sp("9.9", "88.8"), sp("10.0", "88.8"), 0f)
        assertEquals(sp("99", "888"), sp("100", "888"), 0f)
        assertEquals(sp("5", "888"), sp("100", "888"), 0f)
        // and as its unit changes
        assertEquals(sp("9.9", "88.8", "л/ч", "л/100"), sp("9.9", "88.8", "л/100", "л/100"), 0f)
        // without the widest it does jump: that is what the callers must not do
        assertTrue(sp("9.9", null, "") != sp("10.000", null, ""))
        // a longer text than promised still fits
        assertTrue(sp("12345.6", "88.8") < sp("9.9", "88.8"))
    }

    @Test fun savedScaleIsResetOnceOnAHeadUnit() {
        // a percent from the old sizes: reset, and said so
        assertEquals(ScaleMigration(100, done = true, notice = true), migrateScale(120, phone = false, done = false))
        assertEquals(ScaleMigration(100, done = true, notice = true), migrateScale(80, phone = false, done = false))
        // nothing to reset, nothing to say
        assertEquals(ScaleMigration(0, done = true, notice = false), migrateScale(0, phone = false, done = false))
        assertEquals(ScaleMigration(100, done = true, notice = false), migrateScale(100, phone = false, done = false))
        // a phone goes by its own scale
        assertEquals(ScaleMigration(130, done = true, notice = false), migrateScale(130, phone = true, done = false))
        // on a later start, and with a percent picked after the reset: left alone
        assertEquals(ScaleMigration(110, done = true, notice = false), migrateScale(110, phone = false, done = true))
        val first = migrateScale(120, phone = false, done = false)
        assertEquals(ScaleMigration(90, done = true, notice = false), migrateScale(90, phone = false, done = first.done))
    }

    @Test fun diagonalIsAskedOnceOnAHeadUnit() {
        assertTrue(diagonalPromptDue(phone = false, inches = 0f, asked = false, setupDone = true, notice = false))
        assertFalse("a phone", diagonalPromptDue(phone = true, inches = 0f, asked = false, setupDone = true, notice = false))
        assertFalse("told", diagonalPromptDue(phone = false, inches = 9f, asked = false, setupDone = true, notice = false))
        assertFalse("closed", diagonalPromptDue(phone = false, inches = 0f, asked = true, setupDone = true, notice = false))
        assertFalse("the first setup asks", diagonalPromptDue(phone = false, inches = 0f, asked = false, setupDone = false, notice = false))
        // the reset of the scale is said even with the diagonal told
        assertTrue(diagonalPromptDue(phone = false, inches = 9f, asked = true, setupDone = true, notice = true))
        assertFalse(diagonalPromptDue(phone = true, inches = 0f, asked = false, setupDone = true, notice = true))
    }

    @Test fun factoryLayoutsAreToldByTheirCardsNotTheirText() {
        val board = HomeLayout.board
        // an older version saved the same factory cards with fewer switched-off ones after them
        val older = board.format(board.defaults.filter { it.on })
        assertEquals(listOf(4, 4), HomeLayout.rows(HomeLayout.parse(HomeLayout.effective(older, true)), 4).map { it.size })
        val atmoOld = board.format(HomeLayout.parse(HomeLayout.atmoDefault()).filter { it.on })
        assertEquals(listOf(4, 4), HomeLayout.rows(HomeLayout.parse(HomeLayout.effective(atmoOld, true)), 4).map { it.size })
        val linkOld = board.format(HomeLayout.parse(HomeLayout.linkDefault()).filter { it.on })
        assertEquals(listOf(4, 4), HomeLayout.rows(HomeLayout.parse(HomeLayout.effective(linkOld, true)), 4).map { it.size })
        // what the other-make screen wrote for the cards its car fills
        for (seen in listOf(
            setOf("coolant", "intake", "battery", "throttle", "maf", "afr", "timing", "pedal", "rpm", "speed"),
            setOf("coolant", "rpm", "speed"),
            setOf("coolant", "intake", "map", "maf", "boost", "throttle"),
        )) for (turbo in listOf(false, true)) {
            val on = kg.forester.sg.data.CarKind.obdHomeCards(seen, turbo)
            val s = board.format(on.map { kg.forester.sg.data.TileCfg(it, true) } + board.defaults.filter { it.id !in on }.map { it.copy(on = false) })
            val shown = HomeLayout.parse(HomeLayout.effective(s, true)).filter { it.on }
            assertTrue("$seen: ${shown.size}", shown.size == minOf(8, on.size))
        }
        // anything of the driver's is his: a card swapped, moved, made wider, given a dial, or free
        val base = board.defaults.filter { it.on }
        val mine = listOf(
            base.map { if (it.id == "maf") it.copy(id = "oil") else it },
            base.reversed(),
            base.map { if (it.id == "maf") it.copy(span = 2) else it },
            base.map { if (it.id == "maf") it.copy(style = "neon") else it },
            base.dropLast(1),
        )
        for (l in mine) { val s = board.format(l); assertEquals(s, HomeLayout.effective(s, true)) }
        val free = board.format(base, free = true)
        assertEquals(free, HomeLayout.effective(free, true))
    }

    @Test fun aPhoneIsToldOncePerProcess() {
        val once = kg.forester.sg.ui.OnceKnown<Boolean>()
        var asked = 0
        assertTrue(once.get { asked++; true })
        // the second answer would differ: it isn't even asked
        assertTrue(once.get { asked++; false })
        assertEquals(1, asked)
    }

    @Test fun nothingElseGoesBySmallestWidthForAPhone() {
        val repo = java.io.File("src/main/java/kg/forester/sg/data/Repo.kt").readText()
        assertFalse(repo.contains("smallestScreenWidthDp"))
        assertTrue(repo.contains("private val phone = kg.forester.sg.isPhone(ctx)"))
        val main = java.io.File("src/main/java/kg/forester/sg/MainActivity.kt").readText()
        assertTrue(main.contains("if (isPhone(this)) {"))
    }

    @Test fun noDataShowsDash() {
        // a reading that isn't there is a dash, never a 0 that reads as a stopped car or an engine at rest
        assertEquals("—", kg.forester.sg.ui.orDash(null as Int?))
        assertEquals("0", kg.forester.sg.ui.orDash(0))
        assertEquals("87", kg.forester.sg.ui.orDash(87))
        // the speed and the revs of a snapshot nobody filled
        val empty = kg.forester.sg.ssm.EcuSnapshot()
        assertEquals("—", kg.forester.sg.ui.orDash(empty.speedKmh))
        assertEquals("—", kg.forester.sg.ui.orDash(empty.rpm))
    }

    @Test fun warningRulesMatchEverywhere() {
        val warn = kg.forester.sg.ui.Warn
        // the knock threshold is the engine rules' own: one number
        assertEquals(-2f, kg.forester.sg.service.KNOCK_WARN_DEG, 0f)
        assertEquals(kg.forester.sg.service.KNOCK_WARN_DEG, warn.of("knock").below)
        assertTrue(warn.of("knock").hits(-2.5f))
        assertFalse(warn.of("knock").hits(-1.5f))
        assertFalse(warn.of("knock").hits(-2f))
        assertFalse(warn.of("knock").hits(null))
        // the bottom-bar / catalogue indicators use the same rules as the home cards and the compact panel
        for (id in listOf("coolant", "intake", "battery", "range", "knock", "atf", "fbkc", "flkc")) {
            val ind = kg.forester.sg.ui.Metrics.of(id, emptyList())!!
            val r = warn.of(id)
            assertEquals("$id above", r.above, ind.warnAbove)
            assertEquals("$id below", r.below, ind.warnBelow)
        }
        // all three kinds of rule, as the home cards use them
        assertTrue(warn.of("coolant").hits(103f)); assertFalse(warn.of("coolant").hits(102f))
        assertTrue(warn.of("battery").hits(11.9f)); assertFalse(warn.of("battery").hits(12f))
        assertTrue(warn.of("afcorr").hits(-16f)); assertTrue(warn.of("afcorr").hits(16f)); assertFalse(warn.of("afcorr").hits(15f))
        assertTrue(warn.of("aflearn").hits(-10.5f)); assertFalse(warn.of("aflearn").hits(10f))
        // low range warns on the home card too
        assertTrue(warn.of("range").hits(49f)); assertFalse(warn.of("range").hits(50f))
        // an id with no rule never warns
        assertFalse(warn.of("throttle").hits(100f))
        // a needle card carries the same three rules, so it warns where the plain card does
        val cv = kg.forester.sg.ui.CardValue(kg.forester.sg.ui.Metrics.of("battery", emptyList())!!.icon, "t", 11f, 10f, 15f, "В", "%.1f", "i", warnRule = warn.of("battery"))
        assertTrue(cv.warnRule.hits(cv.value))
    }

    @Test fun settingsHintsKeepToOneLine() {
        val (short, none) = kg.forester.sg.ui.hintLine("Показывать на карте")
        assertEquals("Показывать на карте", short); assertNull(none)
        val long = "Опрос ЭБУ, расход, поездки, логи и голосовой ассистент продолжаются, когда приложение свёрнуто или открыто другое"
        val (line, rest) = kg.forester.sg.ui.hintLine(long)
        assertTrue(line, line.length <= kg.forester.sg.ui.HINT_MAX)
        assertEquals(long, rest)
        assertTrue(line.endsWith("…"))
        // cut where a clause ends, not in the middle of a word
        val two = "Хранятся последние 7 копий. Последняя была вчера в 23:10, папка Download/CarPulse на этом устройстве."
        assertEquals("Хранятся последние 7 копий…", kg.forester.sg.ui.hintLine(two).first)
        // no spaces at all: still cut to the line
        assertTrue(kg.forester.sg.ui.hintLine("а".repeat(100)).first.length <= kg.forester.sg.ui.HINT_MAX)
    }

    @Test fun railKeepsTheTapTokenAndPinsSettings() {
        val main = java.io.File("src/main/java/kg/forester/sg/MainActivity.kt").readText()
        // 13 buttons can't fit at any real height: the list scrolls, the token stays, the Settings button is pinned
        assertFalse(main.contains("railItemHeight"))
        assertTrue(main.contains("val pinned = Screen.Settings"))
        assertTrue(kg.forester.sg.ui.fullScreenOffered(true))
        assertFalse(kg.forester.sg.ui.fullScreenOffered(false))
    }
}

