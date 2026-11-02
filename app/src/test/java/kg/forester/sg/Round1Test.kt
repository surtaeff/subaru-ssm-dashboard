package kg.forester.sg

import kg.forester.sg.data.LinkLoss
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.OtherTurbo
import kg.forester.sg.data.afterLinkLoss
import kg.forester.sg.data.lhNow
import kg.forester.sg.service.afterLinkLoss
import kg.forester.sg.data.TripState
import kg.forester.sg.data.TurboState
import kg.forester.sg.data.TurboWatch
import kg.forester.sg.data.turboFromPids
import kg.forester.sg.service.GuardState
import kg.forester.sg.service.RunEdge
import kg.forester.sg.ui.CarTokens
import kg.forester.sg.ui.carMmPerDp
import kg.forester.sg.ui.headerPlan
import kg.forester.sg.ui.hintInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fix round 1 of Task 15. */
class Round1Test {
    private fun src(path: String) = File("src/main/java/kg/forester/sg/$path").readText()

    // ---- C1
    @Test fun linkDropWithEngineRunningDoesNotFireEngineOnAgain() {
        val e = RunEdge()
        assertTrue(e.engineOn(800, true))
        assertFalse(e.engineOn(900, true))
        // the link goes, the wipe leaves no rpm: nothing fires and nothing is forgotten
        assertFalse(e.engineOn(null, false))
        assertFalse(e.engineOn(null, true))      // first frame of the new session, no rpm yet
        assertFalse(e.engineOn(850, true))       // still the same run: no ENGINE_ON on reconnect
        // the engine really stopped, then started: that is an edge
        assertFalse(e.engineOn(0, true))
        assertTrue(e.engineOn(700, true))
    }

    @Test fun anEngineThatStoppedDuringTheDropFiresOnTheNextStart() {
        val e = RunEdge()
        assertTrue(e.engineOn(800, true))
        assertFalse(e.engineOn(null, false))
        assertFalse(e.engineOn(0, true))         // reconnected with the engine off
        assertTrue(e.engineOn(750, true))
    }

    // ---- I1
    @Test fun linkLossWipesEveryLiveReadingAndKeepsWhatMustSurvive() {
        val g = GuardState(fbkc = -5f, flkc = -2f, oilC = 95f, targetBoostKpa = 80f, turbo = true, turboCar = true, heat = 40f, iam = 0.9f, cooldownSec = 20)
        val w = g.afterLinkLoss()
        assertNull(w.fbkc); assertNull(w.flkc); assertNull(w.oilC); assertNull(w.targetBoostKpa)
        assertTrue(w.turboCar); assertTrue(w.turbo); assertEquals(40f, w.heat, 0f); assertEquals(0.9f, w.iam!!, 0f); assertEquals(20, w.cooldownSec)
        val t = TripState(instLh = 7.0, instPer100 = 9.0, rangeKm = 300.0).afterLinkLoss()
        assertFalse(t.instKnown); assertNull(t.instPer100); assertNull(t.lhNow); assertEquals(300.0, t.rangeKm!!, 0.0)
        assertEquals(7.0, TripState(instLh = 7.0).lhNow!!, 0.0)
        val svc = src("service/EcuService.kt")
        assertTrue(svc.contains("afterLinkLoss()") && svc.contains("Repo.rrValues.value = emptyMap()"))
    }

    // ---- I2
    @Test fun noLinkShowsEvenIfTheLinkCyclesThroughStates() {
        val w = LinkLoss()
        w.due(LinkState.CONNECTED, 0)
        assertFalse(w.lost(LinkState.NO_ECU, 1000))
        // a cable that fails to open: CONNECTING, ERROR, CONNECTING, ERROR every second
        assertFalse(w.lost(LinkState.CONNECTING, 1500))
        assertFalse(w.lost(LinkState.ERROR, 2500))
        assertTrue(w.lost(LinkState.CONNECTING, 3000))
        assertTrue(w.lost(LinkState.ERROR, 4000))
        assertFalse(w.lost(LinkState.CONNECTED, 5000))
        assertTrue(src("ui/Components.kt").contains("LinkLoss()"))
    }

    // ---- I3
    private fun plan(inches: Float, total: Float, demo: Boolean = false, status: Boolean = false, logo: Boolean = false, values: Int = 2): Pair<kg.forester.sg.ui.HeaderPlan, CarTokens> {
        val car = CarTokens(carMmPerDp(inches, 1280, 720))
        return headerPlan(total, car.tap.value, car.label.value, car.secondary.value, logo, demo, status, values) to car
    }

    @Test fun headerKeepsNoLinkReadableAndExitAndClockAlive() {
        for ((inches, total) in listOf(7f to 1004f, 8f to 1004f, 10f to 1050f, 11f to 1050f)) {
            for (demo in listOf(false, true)) for (status in listOf(false, true)) for (logo in listOf(false, true)) {
                val (p, car) = plan(inches, total, demo = demo, status = status, logo = logo)
                val tag = "$inches in demo=$demo status=$status logo=$logo"
                assertTrue("$tag: name ${p.nameDp} < ${p.noLinkDp}", p.nameDp >= p.noLinkDp)
                assertTrue("$tag: exit ${p.exitDp}", p.exitDp >= car.tap.value)
                assertTrue("$tag: clock ${p.clockDp}", p.clockDp > 0f)
                assertTrue("$tag: sum ${p.sumDp} > $total", p.sumDp <= total + 0.5f)
            }
        }
        // 7 inches: the top values are the ones that give way
        val (p7, _) = plan(7f, 1004f)
        assertEquals(0, p7.values)
        // 10 inches keeps at least one
        assertTrue(plan(10f, 1050f).first.values >= 1)
    }

    @Test fun headerGivesRoomUpInOrderValuesThenTitle() {
        var lastValues = Int.MAX_VALUE
        var lastName = Float.MAX_VALUE
        var valuesGone = false
        for (total in 1300 downTo 900 step 10) {
            val (p, _) = plan(10f, total.toFloat())
            assertTrue(p.values <= lastValues); assertTrue(p.nameDp <= lastName + 0.01f)
            // the title keeps all it wants while any top value is still shown
            if (p.values > 0) assertEquals(p.nameWantedDp, p.nameDp, 0.01f)
            if (p.values == 0) valuesGone = true
            lastValues = p.values; lastName = p.nameDp
        }
        assertTrue(valuesGone)
    }

    @Test fun coolDownPillHasAFixedSlot() {
        val (a, _) = plan(10f, 1200f, status = true)
        val (b, _) = plan(10f, 1200f, status = false)
        assertTrue(a.statusDp > 0f); assertEquals(0f, b.statusDp, 0f)
        assertTrue(src("MainActivity.kt").contains("plan.statusDp"))
    }

    // ---- I4
    @Test fun oneKnockThresholdAndEveryCardWarns() {
        val d = src("ui/DriveScreen.kt")
        assertFalse(d.contains("(knockDeg ?: 0f) < 0f"))
        assertTrue(d.contains("Warn.of(\"knock\")"))
        val h = src("ui/HealthScreen.kt")
        assertFalse(h.contains("< -1f"))
        assertTrue(h.contains("Warn.of(\"flkc\")"))
        // the plain mini gauge takes the rule the needle style has
        assertTrue(d.contains("warnRule = "))
        assertTrue(src("ui/MyScreen.kt").contains("warnRule"))
    }

    // ---- I5
    @Test fun backStepsBackEverywhere() {
        val m = src("MainActivity.kt")
        assertTrue(m.contains("BackHandler(enabled = picked != Screen.Home)"))
        assertTrue(src("ui/MediaScreen.kt").contains("BackHandler"))
        assertTrue(src("ui/HomeEditor.kt").contains("BackHandler"))
        assertTrue(src("ui/HomeScreen.kt").contains("BackHandler"))
    }

    // ---- I7 / I8
    @Test fun hintOverflowOpensTheInfoToo() {
        assertNull(hintInfo("Коротко", false))
        assertEquals("Коротко", hintInfo("Коротко", true))
        val long = "Опрос ЭБУ, расход, поездки, логи и голосовой ассистент продолжаются, когда приложение свёрнуто"
        assertEquals(long, hintInfo(long, false))
    }

    @Test fun statusStaysOnTheRowAndOnlyExplanationsGoBehindI() {
        val ls = src("ui/LogSettingsScreens.kt")
        assertFalse(ls.contains("хранятся последние 7. Последняя"))
        assertFalse(ls.contains("Голос: \$speech\" else \"\""))
        assertTrue(ls.contains("status = "))
        assertTrue(src("ui/HealthScreen.kt").contains("status = "))
        assertTrue(src("ui/Components.kt").contains("status: String? = null"))
    }

    // ---- S1
    @Test fun everyDeleteAsks() {
        assertTrue(src("ui/PerfScreen.kt").contains("ConfirmDialog"))
        assertTrue(src("ui/ShortcutSettings.kt").contains("ConfirmDialog"))
        assertTrue(src("ui/VoiceUi.kt").contains("ConfirmDialog"))
    }

    // ---- S2
    @Test fun noLongParagraphsLeftInSettings() {
        val long = Regex("T\\(\"(?:[^\"\\\\]|\\\\.){61,}\", (?:11|12|13)\\.sp, C\\.Sub")
        for (f in listOf("ui/LogSettingsScreens.kt", "ui/ShortcutSettings.kt", "ui/CarSetup.kt")) {
            val bad = long.findAll(src(f)).map { it.value.take(60) }.toList()
            assertTrue("$f: $bad", bad.isEmpty())
        }
    }

    // ---- S3
    @Test fun noDeadCodeAndTheUiUsesNoLinkText() {
        val c = src("ui/Components.kt")
        assertFalse(c.contains("fun orDash(v: Float?"))
        assertFalse(src("ui/Warn.kt").contains("fun orDash(v: Float?"))
        assertFalse(c.contains("border: Color = C.Border,\n    content: @Composable ColumnScope.() -> Unit,\n) {\n    CarDialog"))
        assertFalse(c.contains("border: Color"))
        assertFalse(src("MainActivity.kt").contains("\"нет связи\""))
        assertFalse(src("ui/CompactScreen.kt").contains("\"нет связи\""))
        assertTrue(src("MainActivity.kt").contains("noLinkText("))
    }

    // ---- S4: the wiring the named tests can't see
    @Test fun theWiringIsThere() {
        assertTrue(src("service/EcuService.kt").contains("scope.launch { linkLossWatch() }"))
        assertTrue(src("ui/CompactScreen.kt").contains("orDash(live.value.speedKmh?.let"))
        assertTrue(src("ui/PerfTabs.kt").contains("orDash(live.rpm)"))
        for (f in listOf("ui/HomeScreen.kt", "ui/CompactScreen.kt", "ui/CardValue.kt", "ui/Metrics.kt")) assertTrue(f, src(f).contains("Warn.") || src(f).contains("WarnRule"))
        // no knock comparison anywhere but Warn and the Analyzer
        val knock = Regex("(knockDeg|fbkc|flkc)[^\\n]{0,24}(<|<=|>|>=) *-?[0-9]")
        val root = File("src/main/java/kg/forester/sg")
        val bad = root.walkTopDown().filter { it.extension == "kt" && it.name != "Analyzer.kt" && it.name != "Warn.kt" && it.name != "EngineGuard.kt" && it.name != "ObdPids.kt" && it.name != "Demo.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (knock.containsMatchIn(l) && !l.trim().startsWith("//") && !l.trim().startsWith("*")) "${f.name}:${i + 1}: ${l.trim().take(90)}" else null } }.toList()
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    // ---- V1
    @Test fun splitScreenStateIsTrackedNotReadOnce() {
        assertTrue(src("MainActivity.kt").contains("onMultiWindowModeChanged"))
        assertFalse(src("ui/CompactScreen.kt").contains("isInMultiWindowMode"))
    }

    // ---- O1
    private val baro = 100f
    private fun watch() = TurboWatch()

    @Test fun boostPidsTellTurboAtOnce() {
        assertEquals(TurboState.TURBO, turboFromPids(setOf(0x0B, 0x0C, 0x70)))
        assertEquals(TurboState.TURBO, turboFromPids(setOf(0x0B, 0x6F)))
        assertEquals(TurboState.TURBO, turboFromPids(setOf(0x0B, 0x71)))
        assertNull(turboFromPids(setOf(0x0B, 0x0C)))
        assertNull(turboFromPids(emptySet()))
        // a car with no MAP at all has no boost to show
        assertEquals(TurboState.NON_TURBO, turboFromPids(setOf(0x0C, 0x0D, 0x11)))
    }

    @Test fun sustainedBoostLatchesTurboAndGlitchDoesNot() {
        val w = watch()
        var st = TurboState.UNKNOWN
        for (t in 0..750 step 250) st = w.sample(t.toLong(), st, 3000, 115f, baro, 60f, 70f)
        assertEquals(TurboState.UNKNOWN, st)
        st = w.sample(1000, st, 3000, 115f, baro, 60f, 70f)
        assertEquals(TurboState.TURBO, st)
        // one glitch sample
        val g = watch()
        var s2 = g.sample(0, TurboState.UNKNOWN, 3000, 125f, baro, 50f, 50f)
        s2 = g.sample(250, s2, 3000, 100f, baro, 50f, 50f)
        s2 = g.sample(500, s2, 3000, 100f, baro, 50f, 50f)
        s2 = g.sample(1500, s2, 3000, 100f, baro, 50f, 50f)
        assertEquals(TurboState.UNKNOWN, s2)
        // no baro PID: 101 kPa stands
        val n = watch()
        var s3 = TurboState.UNKNOWN
        for (t in 0..1000 step 250) s3 = n.sample(t.toLong(), s3, 3000, 112f, null, 60f, 60f)
        assertEquals(TurboState.TURBO, s3)
        val m = watch()
        var s4 = TurboState.UNKNOWN
        for (t in 0..2000 step 250) s4 = m.sample(t.toLong(), s4, 3000, 110f, null, 60f, 60f)   // 9 kPa over 101: not enough
        assertEquals(TurboState.UNKNOWN, s4)
    }

    @Test fun cruiseAtLowLoadStaysUnknownAndATurboCarOffBoostIsNotCalledNonTurbo() {
        val w = watch()
        var st = TurboState.UNKNOWN
        for (t in 0..30_000 step 250) st = w.sample(t.toLong(), st, 3500, 100f, baro, 30f, 20f)
        assertEquals(TurboState.UNKNOWN, st)
        // low rpm, high load: not the evidence either
        val v = watch()
        for (t in 0..30_000 step 250) st = v.sample(t.toLong(), st, 2000, 100f, baro, 95f, 90f)
        assertEquals(TurboState.UNKNOWN, st)
    }

    @Test fun highLoadWithNoBoostForThreeSecondsInTotalIsNonTurbo() {
        val w = watch()
        var st = TurboState.UNKNOWN
        for (t in 0..2500 step 500) st = w.sample(t.toLong(), st, 3500, 102f, baro, 90f, 80f)
        assertEquals(TurboState.UNKNOWN, st)
        st = w.sample(3000, st, 3500, 102f, baro, 90f, 80f)
        assertEquals(TurboState.NON_TURBO, st)
        // in total across samples: a pause between two bursts doesn't reset it
        val s = watch()
        var x = TurboState.UNKNOWN
        for (t in 0..1500 step 500) x = s.sample(t.toLong(), x, 3500, 102f, baro, 90f, 80f)   // 1.5 s
        for (t in 2000..4000 step 500) x = s.sample(t.toLong(), x, 3500, 100f, baro, 20f, 10f) // low load
        for (t in 4500..6000 step 500) x = s.sample(t.toLong(), x, 3500, 102f, baro, 90f, 80f) // another 1.5 s
        assertEquals(TurboState.NON_TURBO, x)
        // wide-open throttle counts without a load PID
        val o = watch()
        var y = TurboState.UNKNOWN
        for (t in 0..3000 step 500) y = o.sample(t.toLong(), y, 4000, 101f, baro, null, 100f)
        assertEquals(TurboState.NON_TURBO, y)
    }

    @Test fun aLaterBoostMovesNonTurboToTurboAndTurboStays() {
        val w = watch()
        var st = TurboState.NON_TURBO
        for (t in 0..1000 step 250) st = w.sample(t.toLong(), st, 3500, 118f, baro, 90f, 90f)
        assertEquals(TurboState.TURBO, st)
        for (t in 2000..12_000 step 250) st = w.sample(t.toLong(), st, 3500, 100f, baro, 90f, 90f)
        assertEquals(TurboState.TURBO, st)
    }

    @Test fun layoutFollowsFromTheNextConnectionAndAVinChangeResets() {
        var o = OtherTurbo().identified("VIN1")
        assertFalse(o.shown)
        o = o.with(TurboState.TURBO)
        assertFalse(o.shown)               // mid-drive nothing moves
        o = o.connected()
        assertTrue(o.shown)
        assertEquals(TurboState.NON_TURBO, OtherTurbo("V", TurboState.NON_TURBO, TurboState.UNKNOWN).connected().applied)
        assertFalse(OtherTurbo("V", TurboState.NON_TURBO, TurboState.UNKNOWN).connected().shown)
        assertFalse(OtherTurbo("V", TurboState.UNKNOWN, TurboState.UNKNOWN).connected().shown)
        // same VIN or none: kept; another VIN: a different car, nothing of the last one's
        assertEquals(o, o.identified("")); assertEquals(o, o.identified("VIN1"))
        val other = o.identified("VIN2")
        assertEquals(TurboState.UNKNOWN, other.state); assertEquals(TurboState.UNKNOWN, other.applied); assertEquals("VIN2", other.car)
        // storage, and the older format
        assertEquals(o, OtherTurbo.parse(o.format()))
        assertEquals(TurboState.TURBO, OtherTurbo.parse("V|true|true").state)
        assertTrue(OtherTurbo.parse("V|true|true").shown)
        assertEquals(TurboState.UNKNOWN, OtherTurbo.parse("V|false|").state)
    }

    @Test fun noTurboDefaultRemainsAndTheCellIsLocked() {
        assertFalse(src("data/HomeLayout.kt").contains("applied ?: true"))
        assertFalse(src("data/HomeLayout.kt").contains("?: true\n"))
        // one writer at a time: nobody reads, changes and writes otherTurbo apart
        val root = File("src/main/java/kg/forester/sg")
        val bad = root.walkTopDown().filter { it.extension == "kt" && it.name != "Repo.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (Regex("otherTurbo = .*otherTurbo").containsMatchIn(l)) "${f.name}:${i + 1}" else null } }.toList()
        assertTrue(bad.toString(), bad.isEmpty())
        assertTrue(src("data/Repo.kt").contains("fun updateOtherTurbo"))
        assertTrue(src("data/Repo.kt").contains("@Synchronized fun updateOtherTurbo") || src("data/Repo.kt").contains("synchronized"))
    }
}
