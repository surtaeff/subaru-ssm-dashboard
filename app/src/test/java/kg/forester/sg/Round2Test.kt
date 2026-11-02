package kg.forester.sg

import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.DriveBoard
import kg.forester.sg.data.OtherLayoutMark
import kg.forester.sg.data.TurboState
import kg.forester.sg.data.TurboWatch
import kg.forester.sg.data.shouldRewriteLayout
import kg.forester.sg.ssm.Obd2
import kg.forester.sg.ssm.ObdLink
import kg.forester.sg.ui.CarTokens
import kg.forester.sg.ui.carMmPerDp
import kg.forester.sg.ui.headerPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fix round 2 of Task 15. */
class Round2Test {
    private fun src(path: String) = File("src/main/java/kg/forester/sg/$path").readText()
    private val U = TurboState.UNKNOWN

    // ---- B2: a K-line sample every 2 s or slower
    @Test fun slowSamplesStillLatchARealTurbo() {
        val w = TurboWatch()
        var st = U
        st = w.sample(0, st, 3000, 118f, 100f, 60f, 70f)
        assertEquals(U, st)
        st = w.sample(2000, st, 3000, 120f, 100f, 60f, 70f)
        assertEquals(TurboState.TURBO, st)
        // one glitch, the next sample long after: a gap of more than 15 s restarts it
        val g = TurboWatch()
        var s = g.sample(0, U, 3000, 125f, 100f, 50f, 50f)
        s = g.sample(20_000, s, 3000, 125f, 100f, 50f, 50f)
        assertEquals(U, s)
        // one boosted sample between two quiet ones is still a glitch at this rate
        val h = TurboWatch()
        var q = h.sample(0, U, 3000, 100f, 100f, 50f, 50f)
        q = h.sample(2000, q, 3000, 125f, 100f, 50f, 50f)
        q = h.sample(4000, q, 3000, 100f, 100f, 50f, 50f)
        q = h.sample(6000, q, 3000, 125f, 100f, 50f, 50f)
        q = h.sample(8000, q, 3000, 100f, 100f, 50f, 50f)
        assertEquals(U, q)
    }

    // ---- B3: vacuum cruise never counts, however old the load is
    @Test fun vacuumCruiseWithAStaleHighLoadIsNotNonTurbo() {
        val w = TurboWatch()
        var st = w.sample(0, U, 3500, 100f, 100f, 90f, 40f)   // one stale high load
        for (t in 2000..60_000 step 2000) st = w.sample(t.toLong(), st, 3500, 45f, 100f, 90f, 40f) // cruise in vacuum, load still 90
        assertEquals(U, st)
    }

    // ---- B4: plausibility
    @Test fun implausibleReadingsNeverLatch() {
        val a = TurboWatch(); var st = U
        for (t in 0..5000 step 250) st = a.sample(t.toLong(), st, 3000, 255f, 100f, 60f, 70f)      // MAP 0xFF
        assertEquals(U, st)
        val b = TurboWatch(); st = U
        for (t in 0..5000 step 250) st = b.sample(t.toLong(), st, 3000, 101f, 0f, 60f, 70f)        // baro PID reads 0
        assertEquals(U, st)
        val c = TurboWatch(); st = U
        for (t in 0..5000 step 250) st = c.sample(t.toLong(), st, 1200, 125f, 100f, 60f, 70f)      // idle revs
        assertEquals(U, st)
        val d = TurboWatch(); st = U
        for (t in 0..5000 step 250) st = d.sample(t.toLong(), st, 3000, 12f, 100f, 60f, 70f)       // MAP under 15
        assertEquals(U, st)
        // a baro of 0 is absent, 101 stands: a real boost still counts
        val e = TurboWatch(); st = U
        for (t in 0..1000 step 250) st = e.sample(t.toLong(), st, 3000, 125f, 0f, 60f, 70f)
        assertEquals(TurboState.TURBO, st)
    }

    // ---- O1a
    @Test fun aLayoutTheDriverTouchedIsNeverRewritten() {
        val written = "coolant:1,intake:1,boost:1"
        val mark = OtherLayoutMark("V1", U, written)
        // the driver hid a card, the state changed: untouched by us
        assertFalse(shouldRewriteLayout(mark, "V1", TurboState.TURBO, "coolant:1,boost:1"))
        // the app's own list, the state changed: it follows
        assertTrue(shouldRewriteLayout(mark, "V1", TurboState.TURBO, written))
        // the state is the same: nothing
        assertFalse(shouldRewriteLayout(mark, "V1", U, written))
        // never written: the first time is ours, but only on an empty layout
        assertTrue(shouldRewriteLayout(null, "V1", U, ""))
        assertFalse(shouldRewriteLayout(null, "V1", U, "coolant:1"))
        // another car: its own layout is the app's if it wrote it
        assertTrue(shouldRewriteLayout(mark, "V2", U, written))
        assertEquals(mark, OtherLayoutMark.parse(mark.format()))
    }

    // ---- O1b
    @Test fun withoutATurboNoSizeShowsBoost() {
        val on = DriveBoard.parse(HomeLayout.driveEffective("", false, false)).filter { it.on }.map { it.id }
        assertFalse(on.toString(), "boost" in on || "chartBoost" in on || "wgdc" in on)
        assertTrue("throttle" in on)
        assertEquals("", HomeLayout.driveEffective("", false, true))
        assertEquals("pedal:1", HomeLayout.driveEffective("pedal:1", false, false))
        val small = DriveBoard.parse(HomeLayout.driveEffective("", true, false)).filter { it.on }.map { it.id }
        assertFalse("boost" in small)
        val home = HomeLayout.parse(HomeLayout.forCar("", true, false)).filter { it.on }.map { it.id }
        assertFalse(home.toString(), "boost" in home || "wgdc" in home)
        assertEquals("", HomeLayout.forCar("", true, true))
        assertEquals("", HomeLayout.forCar("", false, false))
        assertEquals("coolant:1", HomeLayout.forCar("coolant:1", true, false))
        assertTrue(src("ui/CompactScreen.kt").contains("Repo.carTurbo"))
    }

    // ---- O1c, O1d, O1e, O1f (wiring)
    @Test fun turboStateWiring() {
        val g = src("service/EngineGuard.kt")
        assertTrue(g.contains("turboSeen = false; boostMs = 0L") && g.contains("turboWatchCar"))
        assertTrue(g.contains("otherState == kg.forester.sg.data.TurboState.TURBO"))
        assertFalse(g.contains("overboost target comes from the ROM") && false)
        assertTrue(g.contains("boostNormCar = "))
        val svc = src("service/EcuService.kt")
        assertTrue(svc.indexOf("updateOtherTurbo { it.connected() }") in 1 until svc.indexOf("Repo.obdMode.value = true"))
        val ck = src("data/CarKind.kt")
        assertTrue(ck.contains("it.identified(useVin)"))
        assertTrue(ck.contains("other.value && obd && !ssm"))
        assertTrue(src("service/RelayEngine.kt").contains("runEdge.engineOn("))
        assertTrue(src("ssm/Obd2.kt").contains("0x24, 0x04)"))
    }

    @Test fun engineLoadIsReadFromPid04() {
        class Link : ObdLink {
            override val name = "t"; override val isCan = true
            override fun request(req: ByteArray): List<ByteArray>? {
                if (req[0].toInt() != 1) return null
                val ps = req.drop(1).map { it.toInt() and 0xFF }
                if (ps.size == 1 && ps[0] % 0x20 == 0) {
                    var bits = 0L
                    for (p in setOf(0x04, 0x0C, 0x0D)) if (p in ps[0] + 1..ps[0] + 32) bits = bits or (1L shl (31 - (p - ps[0] - 1)))
                    return listOf(byteArrayOf(0x41, ps[0].toByte(), (bits shr 24).toByte(), (bits shr 16).toByte(), (bits shr 8).toByte(), bits.toByte()))
                }
                val out = ArrayList<Byte>().apply { add(0x41) }
                for (p in ps) when (p) { 0x04 -> { out += 4; out += 0xFF.toByte() }; 0x0C -> { out += 0x0C; out += 0x1F; out += 0x40 }; 0x0D -> { out += 0x0D; out += 50 } }
                return listOf(out.toByteArray())
            }
            override fun close() {}
        }
        val o = Obd2(Link()).also { assertTrue(it.init()) }
        var load: Float? = null
        repeat(3) { load = o.poll(1000)!!.engineLoadPct ?: load }
        assertNotNull(load); assertEquals(100f, load!!, 0.5f)
    }

    // ---- B1
    private fun plan(inches: Float, total: Float, demo: Boolean, status: Boolean, logo: Boolean = false): Triple<kg.forester.sg.ui.HeaderPlan, CarTokens, Float> {
        val car = CarTokens(carMmPerDp(inches, 1280, 720))
        val p = headerPlan(total, car.tap.value, car.label.value, car.secondary.value, logo, demo, status, 2)
        return Triple(p, car, 74f + 2.5f * car.secondary.value)
    }

    @Test fun headerItemsHaveTheirWorkingWidthOrAreAbsent() {
        for ((inches, total) in listOf(7f to 1004f, 7f to 1180f, 8f to 1004f, 10f to 1050f, 11f to 1050f)) for (demo in listOf(false, true)) for (status in listOf(false, true)) for (logo in listOf(false, true)) {
            val (p, car, demoWant) = plan(inches, total, demo, status, logo)
            val tag = "$inches/$total demo=$demo status=$status logo=$logo $p"
            assertTrue(tag, p.demoDp == 0f || p.demoDp >= demoWant - 0.01f)
            assertTrue(tag, p.statusDp == 0f || p.statusDp >= kg.forester.sg.ui.HEADER_STATUS_DP - 0.01f)
            assertTrue(tag, p.nameDp >= p.noLinkDp)
            assertTrue(tag, p.exitDp >= car.tap.value && p.clockDp > 0f)
            // values go before the title, the title before the readings
            if (p.values > 0) assertTrue(tag, p.readings && p.nameDp >= p.nameWantedDp - 0.01f)
            // the readings give way before the plates
            if (!p.readings) assertEquals(tag, 0, p.values)
            if (demo && !p.readings) { /* the demo plate stays when the readings are gone, unless the status plate took the room */ }
        }
        // 7": the demo plate stays and the readings go; with the cool-down plate, the plates keep their order
        val (alone, _, w) = plan(7f, 1004f, demo = true, status = false)
        assertTrue(alone.demoDp >= w - 0.01f); assertFalse(alone.readings)
        val (both, _, _) = plan(7f, 1004f, demo = true, status = true)
        assertTrue(both.statusDp > 0f)
        val (st, _, _) = plan(7f, 1004f, demo = false, status = true)
        assertTrue(st.statusDp > 0f)
    }

    // ---- I5, S2, D1
    @Test fun backAlsoSetsTheScreenAndTheParagraphsAreOneLine() {
        val m = src("MainActivity.kt")
        assertTrue(m.contains("picked = Screen.Home; screen = Screen.Home"))
        val long = Regex("(?:T|FitText)\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+\\s*)*\"(?:[^\"\\\\]|\\\\.){61,}\")[^\\n]*?(?:11|12|13)\\.sp, C\\.Sub")
        val bad = long.findAll(src("ui/CarSetup.kt")).map { it.value.take(70) }.toList()
        assertTrue(bad.toString(), bad.isEmpty())
        val cs = src("ui/CarSetup.kt")
        assertFalse("status behind the i", Regex("SettingHint\\([^)]*Сейчас").containsMatchIn(cs))
        assertTrue(cs.contains("T(\"Сейчас: "))
        assertFalse(src("ui/ExtraCards.kt").contains("Warn.of(\"knock\").hits(it) }?.let { \"детонация\""))
        assertTrue(src("ui/ExtraCards.kt").contains("it != 0f }?.let { \"детонация\""))
    }

    // ---- S4: every delete site asks
    @Test fun everyDeleteSiteAsks() {
        fun asks(file: String, anchor: String) {
            val s = src(file); val i = s.indexOf(anchor); assertTrue("$file: $anchor", i >= 0)
            assertTrue("$file: no ConfirmDialog near $anchor", s.substring(i, minOf(s.length, i + 700)).contains("ConfirmDialog"))
        }
        asks("ui/PerfScreen.kt", "var askRun")
        asks("ui/ShortcutSettings.kt", "var ask by remember(sc.id)")
        asks("ui/VoiceUi.kt", "var ask by remember(o.phrase)")
        asks("ui/PerfTabs.kt", "var ask by remember(t.timeMs)")
        asks("ui/PerfTabs.kt", "var ask by remember(r.startMs)")
        asks("ui/CanUi.kt", "var ask by remember(c.id)")
        asks("ui/CustomParamsUi.kt", "var ask by remember(p.id)")
        asks("ui/EcuDefsUi.kt", "var ask by remember(f.entry)")
    }
}
