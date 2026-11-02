package kg.forester.sg

import kg.forester.sg.data.KnockMap
import kg.forester.sg.service.Alerts
import kg.forester.sg.service.Analyzer
import kg.forester.sg.service.BoxInput
import kg.forester.sg.service.DiagState
import kg.forester.sg.service.EngineGuard
import kg.forester.sg.service.GearSense
import kg.forester.sg.service.MemoryRuleStore
import kg.forester.sg.service.RuleInput
import kg.forester.sg.service.SpoolPoint
import kg.forester.sg.service.SpoolTrend
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules from the owners' chat: knock map, boost against its target, spool point, trims, box pressures, gear slip. */
class DiagRulesTest {
    private class Run(val store: MemoryRuleStore = MemoryRuleStore()) {
        val said = ArrayList<Triple<String, Alerts.Level, String>>()
        var diag = DiagState()
        val a = Analyzer(store, { id, text, level, _ -> said += Triple(id, level, text) }, onDiag = { diag = it })
        var now = 1_000_000_000L

        fun feed(seconds: Int, input: (Long) -> RuleInput) {
            repeat(seconds * 2) { now += 500; a.process(input(now)) }
        }

        fun ids() = said.map { it.first }.toSet()
        fun level(id: String) = said.last { it.first == id }.second
        fun text(id: String) = said.last { it.first == id }.third
    }

    private fun cruise(t: Long, c: Int = 90) = EcuSnapshot(
        timeMs = t, coolantC = c, rpm = 2500, speedKmh = 80, timingDeg = 35f, intakeC = 25, mafGs = 18f, throttlePct = 15f,
        batteryV = 14.1f, injPwMs = 3.2f, atmKpa = 95f, mapKpa = 55f, afCorrPct = if ((t / 500) % 2 == 0L) 1.5f else -1.2f,
        afLearnPct = 2.3f, lambda = 1f,
    )

    /** Full throttle at [rpm] with [gauge] kPa of boost (load to match). */
    private fun wot(t: Long, rpm: Int, gauge: Float, wgdc: Float? = null) = cruise(t).copy(
        rpm = rpm, throttlePct = 100f, mapKpa = 95f + gauge, mafGs = rpm * (1.2f + maxOf(0f, gauge) / 100f) / 60f, injPwMs = 9f,
        lambda = 0.8f, afCorrPct = 0f, wastegatePct = wgdc,
    )

    private fun warmUp(r: Run, turbo: Boolean = false, automatic: Boolean = false) =
        r.feed(6 * 60) { RuleInput(cruise(it), turbo = turbo, iam = 1f, automatic = automatic) }

    // ---------------------------------------------------------------- knock map

    @Test fun knockMapCountsRealPullsAndKeepsNoiseOut() {
        val r = Run()
        warmUp(r)
        // two loaded pulls at 3200 rpm, 1.2 g/rev, each ending well apart
        repeat(2) {
            r.feed(1) { RuleInput(cruise(it).copy(rpm = 3200, mafGs = 64f, throttlePct = 60f, knockDeg = -1.4f)) }
            r.feed(5) { RuleInput(cruise(it).copy(rpm = 3200, mafGs = 64f, throttlePct = 60f, knockDeg = 0f)) }
        }
        // light load low down: sensor noise
        r.feed(1) { RuleInput(cruise(it).copy(rpm = 2000, mafGs = 15f, knockDeg = -1.4f)) }
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 2000, mafGs = 15f, knockDeg = 0f)) }
        // a lifted foot
        r.feed(1) { RuleInput(cruise(it).copy(rpm = 3500, mafGs = 10f, throttlePct = 0f, knockDeg = -2f)) }
        r.feed(5) { RuleInput(cruise(it).copy(knockDeg = 0f)) }
        val m = r.diag.tripKnock
        assertEquals(2, m.total)
        val cell = m.cells[KnockMap.indexOf(3200, 1.2f)!!]!!
        assertEquals(2, cell.count)
        assertEquals(-1.4f, cell.worstDeg, 0.01f)
        assertEquals(2, r.diag.allKnock.total)
        assertTrue(r.store.knockMap.isNotEmpty())
    }

    @Test fun pullsAtAnAutomaticShiftAreCountedApart() {
        val r = Run()
        warmUp(r, automatic = true)
        repeat(3) {
            // the TCU goes 3 → 4 exactly as the pull starts
            r.feed(1) { RuleInput(cruise(it).copy(rpm = 2300, mafGs = 45f, throttlePct = 40f), TcuSnapshot(timeMs = it, gear = 3, atfC = 70, range = "D"), automatic = true) }
            r.feed(1) { RuleInput(cruise(it).copy(rpm = 2300, mafGs = 45f, throttlePct = 40f, knockDeg = -1f), TcuSnapshot(timeMs = it, gear = 4, atfC = 70, range = "D"), automatic = true) }
            r.feed(5) { RuleInput(cruise(it).copy(rpm = 2300, mafGs = 45f, throttlePct = 40f, knockDeg = 0f), TcuSnapshot(timeMs = it, gear = 4, atfC = 70, range = "D"), automatic = true) }
            r.feed(2) { RuleInput(cruise(it).copy(rpm = 2300, mafGs = 45f, throttlePct = 40f), TcuSnapshot(timeMs = it, gear = 3, atfC = 70, range = "D"), automatic = true) }
        }
        r.feed(5) { RuleInput(cruise(it), TcuSnapshot(timeMs = it, gear = 3, atfC = 70, range = "D"), automatic = true) }
        assertEquals(0, r.diag.tripKnock.total)
        assertEquals(3, r.diag.tripKnock.shiftTotal)
        assertTrue(r.ids().contains("atShiftKnock"))
        assertEquals(Alerts.Level.INFO, r.level("atShiftKnock"))
    }

    @Test fun knockSumsNameTheCylinder() {
        val r = Run()
        warmUp(r)
        var n = 0f
        r.feed(30) { n += 0.5f; RuleInput(cruise(it), knockSums = listOf(1f, 2f, 0f, 10f + n.toInt())) }
        assertTrue(r.ids().contains("knockCyl"))
        assertTrue(r.text("knockCyl").contains("цилиндре 4"))
        assertEquals(4, r.diag.cylKnock!!.size)
    }

    @Test fun iamLowerThanBeforeTheRefill() {
        val store = MemoryRuleStore().apply {
            lastIam = 0.86f
            refillMs = 1_000_000_000L - 24 * 3600_000L
            iamLog += (1_000_000_000L - 3 * 24 * 3600_000L) to 1f
        }
        val r = Run(store)
        r.feed(10 * 60) { RuleInput(cruise(it), iam = 0.86f) }
        assertTrue(r.ids().contains("iamFuel"))
        assertFalse(r.ids().contains("iamDrop"))
        // the same IAM as before the refill: nothing about the fuel
        val ok = Run(MemoryRuleStore().apply { lastIam = 0.86f; refillMs = 1_000_000_000L - 3600_000L; iamLog += 1L to 0.88f })
        ok.feed(10 * 60) { RuleInput(cruise(it), iam = 0.86f) }
        assertFalse(ok.ids().contains("iamFuel"))
    }

    // ---------------------------------------------------------------- mixture and air

    @Test fun idleAirAgainstRevsAndSize() {
        val idle = { maf: Float -> Run().also { r ->
            warmUp(r)
            r.feed(100) { RuleInput(cruise(it).copy(rpm = 730, speedKmh = 0, throttlePct = 0f, mafGs = maf, mapKpa = 33f), displacementL = 2.0f) }
        } }
        assertTrue(idle(4.5f).ids().contains("idleMaf"))   // 3–5 g/s at 700–750 on a 2.0 is too much
        assertFalse(idle(2.6f).ids().contains("idleMaf"))
        assertTrue(idle(1.4f).ids().contains("idleMaf"))
    }

    @Test fun trimsSwingingRailToRail() {
        val r = Run()
        warmUp(r)
        r.feed(90) { RuleInput(cruise(it).copy(afCorrPct = if ((it / 2000) % 2 == 0L) 18f else -18f)) }
        assertTrue(r.ids().contains("trimSwing"))
    }

    @Test fun constantFourteenSevenIsNoSensor() {
        val r = Run()
        var i = 0
        r.feed(5 * 60) { RuleInput(cruise(it).copy(throttlePct = if (i++ % 40 < 20) 10f else 45f, lambda = 1f)) }
        assertTrue(r.diag.noWideband)
        val real = Run()
        var j = 0
        real.feed(5 * 60) { RuleInput(cruise(it).copy(throttlePct = if (j % 40 < 20) 10f else 45f, lambda = if (j++ % 3 == 0) 0.992f else 1f)) }
        assertFalse(real.diag.noWideband)
    }

    @Test fun closedLoopLateAfterAnEcuResetIsExplained() {
        val r = Run(MemoryRuleStore().apply { lastAfLearn = 6f })
        r.feed(6 * 60) { RuleInput(cruise(it).copy(afLearnPct = 0f)) }
        r.feed(3 * 60) { RuleInput(cruise(it).copy(afLearnPct = 0f, afCorrPct = 0f)) }
        assertTrue(r.ids().contains("resetLoop"))
        assertFalse(r.ids().contains("afFrozen"))
        // no reset: the stuck correction is a fault
        val f = Run(MemoryRuleStore().apply { lastAfLearn = 6f })
        f.feed(6 * 60) { RuleInput(cruise(it)) }
        f.feed(3 * 60) { RuleInput(cruise(it).copy(afCorrPct = 0f)) }
        assertTrue(f.ids().contains("afFrozen"))
    }

    @Test fun learningMinusTenWithHighConsumptionIsTheMaf() {
        val run = { per100: Float -> Run().also { r ->
            warmUp(r)
            r.feed(8 * 60) { RuleInput(cruise(it).copy(afLearnPct = -10f), tripPer100 = per100) }
        } }
        assertTrue(run(16f).ids().contains("mafHigh"))
        assertFalse(run(11f).ids().contains("mafHigh"))
    }

    // ---------------------------------------------------------------- boost

    @Test fun shortOfTargetWithTheSolenoidAtItsTop() {
        val r = Run()
        warmUp(r, turbo = true)
        r.feed(5) { RuleInput(wot(it, 4500, 80f, wgdc = 90f), turbo = true, targetBoostKpa = 110f) }
        assertTrue(r.ids().contains("underTarget"))
        val ok = Run()
        warmUp(ok, turbo = true)
        ok.feed(5) { RuleInput(wot(it, 4500, 105f, wgdc = 60f), turbo = true, targetBoostKpa = 110f) }
        assertFalse(ok.ids().contains("underTarget"))
    }

    @Test fun overTargetWithLowDutyIsCreepThenTheSpring() {
        val r = Run()
        warmUp(r, turbo = true)
        r.feed(3) { RuleInput(wot(it, 4500, 135f, wgdc = 10f), turbo = true, targetBoostKpa = 100f) }
        assertTrue(r.ids().contains("overboost"))
        assertTrue(r.text("overboost").contains("крип"))
        // the ECU drops the control: duty 0, the boost down to the spring
        r.feed(3) { RuleInput(wot(it, 5000, 45f, wgdc = 0f), turbo = true, targetBoostKpa = 100f) }
        assertTrue(r.ids().contains("springBoost"))
    }

    /** A1 of the tester's report: a borrowed address reads raw 0, so the "target" is about -100 kPa and any pressure is over it. */
    @Test fun junkTargetNeverCallsOverboost() {
        val idle = Run()
        warmUp(idle, turbo = true)
        idle.feed(30) { RuleInput(cruise(it).copy(rpm = 800, speedKmh = 0, throttlePct = 0f, mapKpa = 35f, mafGs = 3f), turbo = true, targetBoostKpa = -95f) }
        assertFalse(idle.ids().contains("overboost"))
        // the same junk on a real pull
        val pull = Run()
        warmUp(pull, turbo = true)
        pull.feed(5) { RuleInput(wot(it, 4500, 80f), turbo = true, targetBoostKpa = -95f) }
        assertFalse(pull.ids().contains("overboost"))
    }

    @Test fun overboostNeedsRevsAndRealBoost() {
        // a sane target, but off boost: cruising, or low revs
        val cruising = Run()
        warmUp(cruising, turbo = true)
        cruising.feed(5) { RuleInput(wot(it, 4500, 14f), turbo = true, targetBoostKpa = -10f) }
        cruising.feed(5) { RuleInput(wot(it, 2400, 80f), turbo = true, targetBoostKpa = 40f) }
        assertFalse(cruising.ids().contains("overboost"))
        // just over each limit
        val edge = Run()
        warmUp(edge, turbo = true)
        edge.feed(3) { RuleInput(wot(it, 2500, 55f), turbo = true, targetBoostKpa = 30f) }
        assertTrue(edge.ids().contains("overboost"))
    }

    @Test fun overboostWithoutADutyDoesNotBlameTheWastegate() {
        val r = Run()
        warmUp(r, turbo = true)
        r.feed(3) { RuleInput(wot(it, 4500, 135f), turbo = true, targetBoostKpa = 100f) }
        assertEquals(Alerts.Level.WARN, r.level("overboost"))
        assertFalse(r.text("overboost"), r.text("overboost").contains("вестгейт", ignoreCase = true))
        // a duty that says the solenoid is working: the actuator's hoses are a fair guess
        val d = Run()
        warmUp(d, turbo = true)
        d.feed(3) { RuleInput(wot(it, 4500, 135f, wgdc = 50f), turbo = true, targetBoostKpa = 100f) }
        assertTrue(d.text("overboost").contains("вестгейт"))
    }

    @Test fun lateSpoolWithoutADutyDoesNotBlameTheWastegate() {
        fun late(wgdc: Float?): String {
            val r = Run(MemoryRuleStore().apply { boostBase = 100f })
            warmUp(r, turbo = true)
            fun pull(spool: Int) {
                var rpm = 2000
                r.feed(6) {
                    rpm += 250
                    RuleInput(wot(it, rpm, ((rpm - spool) * 0.08f + 80f).coerceIn(-30f, 110f), wgdc), turbo = true, ecuGear = 3)
                }
                r.feed(10) { RuleInput(cruise(it), turbo = true, ecuGear = 3) }
            }
            repeat(3) { pull(3000) }
            repeat(2) { pull(3400) }
            return r.text("spoolLate")
        }
        assertTrue(late(60f).contains("вестгейт"))
        assertFalse(late(null).contains("вестгейт"))
    }

    @Test fun oilCorrectionOnlyMovesTheEstimate() {
        assertEquals(93f, EngineGuard.oilEstimate(100f, -7), 0f)
        assertEquals(100f, EngineGuard.oilEstimate(100f, 0), 0f)
        // clamped: a stored value out of the setting's range
        assertEquals(85f, EngineGuard.oilEstimate(100f, -40), 0f)
        assertEquals(115f, EngineGuard.oilEstimate(100f, 40), 0f)
    }

    @Test fun targetFromASiblingOrOutOfRangeIsNoTarget() {
        // raw 0 in the ROM's absolute units
        assertNull(EngineGuard.targetKpa(0f, "kpa", 95f, false))
        assertNull(EngineGuard.targetKpa(0f, "psi", 95f, false))
        // an 0xFFFF that scaled to a huge pressure
        assertNull(EngineGuard.targetKpa(65535f, "kpa", 95f, false))
        // a real target by each unit
        assertEquals(100f, EngineGuard.targetKpa(195f, "kpa", 95f, false)!!, 0.01f)
        assertEquals(100f, EngineGuard.targetKpa(1f, "bar relative", 95f, false)!!, 0.01f)
        // the same number from a neighbour's address counts for nothing
        assertNull(EngineGuard.targetKpa(195f, "kpa", 95f, true))
    }

    @Test fun boostFloatingAtTheTopButNotATaper() {
        val r = Run()
        warmUp(r, turbo = true)
        var i = 0
        r.feed(6) { RuleInput(wot(it, 4500, if (i++ % 2 == 0) 90f else 110f, 70f), turbo = true) }
        assertTrue(r.ids().contains("boostFloat"))
        val t = Run()
        warmUp(t, turbo = true)
        var g = 130f
        t.feed(6) { g -= 3f; RuleInput(wot(it, 5000, g, 70f), turbo = true) }
        assertFalse(t.ids().contains("boostFloat"))
    }

    /** Two weak pulls at IAM 0.78: the ECU's own boost limit only when the ROM's Boost Cut IAM is above that. */
    @Test fun boostCutIamFromTheRomDecidesWhoLimitsBoost() {
        fun weakPulls(cut: Float?): String {
            val r = Run(MemoryRuleStore().apply { boostBase = 100f })
            warmUp(r, turbo = true)
            repeat(2) {
                r.feed(4) { RuleInput(wot(it, 4500, 50f, 40f), turbo = true, iam = 0.78f, iamBoostCut = cut) }
                r.feed(4) { RuleInput(cruise(it), turbo = true, iam = 0.78f, iamBoostCut = cut) }
            }
            return r.text("underboost")
        }
        assertFalse(weakPulls(null).contains("ЭБУ сам ограничивает"))
        assertTrue(weakPulls(0.80f).contains("ЭБУ сам ограничивает"))
    }

    @Test fun spoolPointMovingUpByGear() {
        val r = Run(MemoryRuleStore().apply { boostBase = 100f })   // spool level: 0.8 bar
        warmUp(r, turbo = true)
        /** A pull in 3rd from 2000 rpm, 250 rpm a sample, the boost reaching 0.8 bar at [spool]. */
        fun pull(spool: Int) {
            var rpm = 2000
            r.feed(6) {
                rpm += 250
                RuleInput(wot(it, rpm, ((rpm - spool) * 0.08f + 80f).coerceIn(-30f, 110f), 60f), turbo = true, ecuGear = 3)
            }
            r.feed(10) { RuleInput(cruise(it), turbo = true, ecuGear = 3) }
        }
        repeat(3) { pull(3000) }
        assertTrue(kotlin.math.abs(r.diag.spool.base[3]!! - 3000) <= 30)
        assertFalse(r.ids().contains("spoolLate"))
        repeat(2) { pull(3400) }
        assertTrue(r.ids().contains("spoolLate"))
        assertEquals(3, r.diag.lastPull!!.gear)
    }

    @Test fun spoolTrendAlone() {
        var t = SpoolTrend(80f)
        listOf(3000, 3050, 2980).forEach { t = t.add(SpoolPoint(0, 3, it)).first }
        assertEquals(3000, t.base[3])
        val (t2, late1) = t.add(SpoolPoint(0, 3, 3350))
        assertFalse(late1)                               // one late pull is not a trend
        val (t3, late2) = t2.add(SpoolPoint(0, 3, 3400))
        assertTrue(late2)
        assertEquals(3000, t3.base[3])                   // the base never follows a jump
        val back = SpoolTrend.fromJson(t3.toJson())
        assertEquals(t3, back)
    }

    @Test fun intercoolerEfficiencyAndHeatSoak() {
        val r = Run(MemoryRuleStore().apply { lastRunMs = 1_000_000_000L - 10 * 3600_000L })
        r.feed(1) { RuleInput(EcuSnapshot(timeMs = it, coolantC = 20, intakeC = 20, rpm = 0), turbo = true) }
        assertEquals(20f, r.diag.let { r.store.ambientC }, 0.1f)
        warmUp(r, turbo = true)
        r.feed(4) { RuleInput(wot(it, 4500, 90f, 60f).copy(intakeC = 50, speedKmh = 100), turbo = true, box = BoxInput(iatInC = 70f, iatOutC = 55f)) }
        r.feed(2) { RuleInput(cruise(it), turbo = true) }
        assertTrue(r.ids().contains("icEff"))           // (70 − 55) / (70 − 20) = 30 %
        assertTrue(r.ids().contains("heatSoak"))        // 55° after the cooler, 20° outside
    }

    // ---------------------------------------------------------------- SensorBox, cooling, gears

    private fun idle(t: Long) = cruise(t).copy(rpm = 750, speedKmh = 0, throttlePct = 0f, mafGs = 2.6f, mapKpa = 35f)

    @Test fun fuelPressureAgainstTheManifold() {
        val high = Run()
        warmUp(high)
        // gauge −0.6 bar: 3.3 bar on the rail is 3.9 over the manifold
        high.feed(40) { RuleInput(idle(it), box = BoxInput(fuelBar = 3.3f, pumpV = 11.4f)) }
        assertTrue(high.ids().contains("fuelDp"))
        assertTrue(high.text("fuelDp").contains("выше"))
        assertTrue(high.text("fuelDp").contains("В:"))
        val low = Run()
        warmUp(low)
        low.feed(40) { RuleInput(idle(it), box = BoxInput(fuelBar = 1.6f)) }
        assertTrue(low.text("fuelDp").contains("ниже"))
        val ok = Run()
        warmUp(ok)
        ok.feed(40) { RuleInput(idle(it), box = BoxInput(fuelBar = 2.4f)) }
        assertFalse(ok.ids().contains("fuelDp"))
        assertFalse(ok.ids().contains("fuelPulse"))
        val pulse = Run()
        warmUp(pulse)
        var i = 0
        pulse.feed(60) { RuleInput(idle(it), box = BoxInput(fuelBar = if (i++ % 2 == 0) 2.1f else 2.7f)) }
        assertTrue(pulse.ids().contains("fuelPulse"))
    }

    @Test fun oilPressureAt3500Hot() {
        val at = { p: Float -> Run().also { r ->
            r.feed(11 * 60) { RuleInput(cruise(it), oilC = 100f) }
            r.feed(10) { RuleInput(cruise(it).copy(rpm = 3500), oilC = 100f, box = BoxInput(oilBar = p)) }
        } }
        assertEquals(Alerts.Level.WARN, at(3.5f).level("oilRevs"))
        assertEquals(Alerts.Level.INFO, at(4.5f).level("oilRevs"))
        assertFalse(at(5.5f).ids().contains("oilRevs"))
    }

    @Test fun creepingInTrafficWithTheFanOn() {
        val r = Run()
        warmUp(r)
        var s = 0
        r.feed(4 * 60) { s++; RuleInput(cruise(it, c = 88 + s / 40).copy(speedKmh = 0, throttlePct = 0f, rpm = 800, mafGs = 3f), fanLow = true, fan = true) }
        assertTrue(r.ids().contains("fanRelay"))
        val off = Run()
        warmUp(off)
        var k = 0
        off.feed(4 * 60) { k++; RuleInput(cruise(it, c = 88 + k / 40).copy(speedKmh = 0, throttlePct = 0f, rpm = 800, mafGs = 3f), fanLow = false, fan = false) }
        assertFalse(off.ids().contains("fanRelay"))
    }

    @Test fun clutchSlipOnAManual() {
        val run = { wotRpm: Int -> Run().also { r ->
            warmUp(r)   // 4th: 2500 rpm at 80
            r.feed(40) { RuleInput(cruise(it).copy(rpm = 3000, speedKmh = 70)) }   // 3rd
            r.feed(10) { RuleInput(cruise(it)) }
            r.feed(5) { RuleInput(cruise(it).copy(rpm = wotRpm, throttlePct = 100f, mafGs = 70f)) }
        } }
        assertTrue(run(2700).ids().contains("gearSlip"))   // 8 % over the gear's line
        assertFalse(run(2520).ids().contains("gearSlip"))
    }

    @Test fun gearsByTheRatio() {
        val g = GearSense()
        repeat(40) { g.learn(42.8f) }
        repeat(40) { g.learn(31.3f) }
        repeat(5) { g.learn(36f) }                        // a slipping start: never a gear
        assertEquals(2, g.gears().size)
        assertEquals(1, g.gearOf(43f))
        assertEquals(2, g.gearOf(33.8f))                  // 8 % over 2nd's line is still 2nd
        assertNull(g.gearOf(20f))
        val back = GearSense.fromJson(g.toJson())
        assertEquals(g.gears(), back.gears())
    }

    @Test fun knockMapCellsAndJson() {
        var m = KnockMap(5L)
        m = m.add(3200, 1.25f, -1.4f, false).add(3300, 1.3f, -2.1f, false).add(2200, 1.0f, -1f, true).add(800, 1f, -1f, false)
        assertEquals(2, m.total)
        assertEquals(1, m.shiftTotal)
        assertEquals(-2.1f, m.worstDeg!!, 0.01f)
        assertEquals(m, KnockMap.fromJson(m.toJson()))
        assertNull(KnockMap.indexOf(900, 1f))
        assertEquals(KnockMap.ROWS * KnockMap.COLS - 1, KnockMap.indexOf(7200, 3f))
    }
}
