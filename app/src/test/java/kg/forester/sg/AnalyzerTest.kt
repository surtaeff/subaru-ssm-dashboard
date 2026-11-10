package kg.forester.sg

import kg.forester.sg.service.Alerts
import kg.forester.sg.service.Analyzer
import kg.forester.sg.service.EngineGuard
import kg.forester.sg.service.IdleRef
import kg.forester.sg.service.IdleSample
import kg.forester.sg.service.MemoryRuleStore
import kg.forester.sg.service.RuleInput
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.EngineInfo
import kg.forester.sg.ssm.EngineProfile
import kg.forester.sg.ssm.EngineProfiles
import kg.forester.sg.ssm.TcuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private class Run(val store: MemoryRuleStore = MemoryRuleStore()) {
        val said = ArrayList<Triple<String, Alerts.Level, String>>()
        var idle: IdleSample? = null
        val a = Analyzer(store, { id, text, level, _ -> said += Triple(id, level, text) }) { idle = it }
        var now = 1_000_000_000L

        /** [seconds] of samples every half second. */
        fun feed(seconds: Int, input: (Long) -> RuleInput) {
            repeat(seconds * 2) { now += 500; a.process(input(now)) }
        }

        fun ids() = said.map { it.first }.toSet()
        fun level(id: String) = said.last { it.first == id }.second
    }

    /** A healthy warm car cruising: 2500 rpm, light throttle, trims near zero. */
    private fun cruise(t: Long, c: Int = 90) = EcuSnapshot(
        timeMs = t, coolantC = c, rpm = 2500, speedKmh = 80, timingDeg = 35f, intakeC = 25, mafGs = 18f, throttlePct = 15f,
        batteryV = 14.1f, injPwMs = 3.2f, atmKpa = 95f, mapKpa = 55f, afCorrPct = if ((t / 500) % 2 == 0L) 1.5f else -1.2f,
        afLearnPct = 2.3f, lambda = 1f,
    )

    private fun warmUp(r: Run, turbo: Boolean = false) = r.feed(6 * 60) { RuleInput(cruise(it), turbo = turbo, iam = 1f) }

    private val na25 = EngineProfiles.of(EngineInfo("EJ25", false, "EJ253", "ЭБУ"))
    private val turbo20 = EngineProfiles.of(EngineInfo("EJ20", true, "EJ205", "ЭБУ"))

    /** One drive: warm cruise at [from], then [from] to [to] over a minute, then a little more at [to]. [maf] by the share of the rise done. */
    private fun coolantRise(r: Run, from: Int, to: Int, p: EngineProfile, fan: Boolean?, turbo: Boolean = false, ac: Boolean? = null, maf: (Float) -> Float = { 18f }, holdS: Int = 2) {
        fun s(t: Long, c: Int, share: Float) = RuleInput(cruise(t, c).copy(mafGs = maf(share)), fan = fan, ac = ac, turbo = turbo, profile = p)
        r.feed(6 * 60) { s(it, from, 0f) }
        var i = 0
        r.feed(60) { i++; s(it, from + (to - from) * i / 120, i / 120f) }
        r.feed(holdS) { s(it, to, 1f) }
    }

    /** Engine off and a gap: the next sample is a new drive. */
    private fun park(r: Run) {
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 0, speedKmh = 0)) }
        r.now += 7 * 3600_000L
    }

    private fun Run.texts(id: String) = said.filter { it.first == id }.map { it.third }

    /** The report that started this: a 2.5 NA at 90 to 95, its normal thermostat band, with the A/C running the fan. */
    @Test fun spike90to95OnNaWithAcIsSilent() {
        val r = Run()
        coolantRise(r, 90, 95, na25, fan = true, ac = true)
        assertFalse(r.ids().contains("spike"))
        // the same rise on the same engine over its 95° thermostat does count
        val over = Run()
        coolantRise(over, 92, 100, na25, fan = true, ac = true)
        assertTrue(over.ids().contains("spike"))
    }

    @Test fun spikeWithUnknownFanBelowFanHighIsSilent() {
        val r = Run()
        coolantRise(r, 92, 96, turbo20, fan = null, turbo = true)
        assertFalse(r.ids().contains("spike"))
        // a full 8° up to 96: over the rule's start, but the fan may simply not have cut in yet
        val below = Run()
        coolantRise(below, 88, 96, turbo20, fan = null, turbo = true)
        assertFalse(below.ids().contains("spike"))
        // from 3° over the fan's point an unknown fan no longer explains it
        val over = Run()
        coolantRise(over, 92, 100, turbo20, fan = null, turbo = true)
        assertTrue(over.ids().contains("spike"))
    }

    @Test fun spikeWithTheFanOffIsSilent() {
        val r = Run()
        coolantRise(r, 92, 100, turbo20, fan = false, turbo = true)
        assertFalse(r.ids().contains("spike"))
    }

    /** The fan cuts in during the rise: it has to be on now, not all through the window. */
    @Test fun spikeWithTheFanCuttingInOnTheWayCounts() {
        val r = Run()
        fun s(t: Long, c: Int) = RuleInput(cruise(t, c), fan = c >= 97, turbo = true, profile = turbo20)
        r.feed(6 * 60) { s(it, 90) }
        var i = 0
        r.feed(60) { i++; s(it, 90 + 8 * i / 120) }
        r.feed(2) { s(it, 98) }
        assertEquals(Alerts.Level.INFO, r.level("spike"))
        assertTrue(r.texts("spike").last(), r.texts("spike").last().contains("с 90° до 98°"))
    }

    @Test fun spike90to95IsSilentOnTheTurbosToo() {
        val ej255 = EngineProfiles.of(EngineInfo("EJ25", true, "EJ255", "ЭБУ"))
        listOf(turbo20, ej255).forEach { p ->
            val r = Run()
            coolantRise(r, 90, 95, p, fan = true, turbo = true)
            assertFalse(p.key, r.ids().contains("spike"))
        }
    }

    @Test fun aRestartWithinSixHoursIsTheSameDrive() {
        val r = Run()
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 0, speedKmh = 0)) }
        r.now += 3600_000L
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(1, r.store.spikeDrives)
        val counted = r.store.spikeLastMs
        // more of them don't move the six hours on
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 0, speedKmh = 0)) }
        r.now += 3600_000L
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(counted, r.store.spikeLastMs)
        park(r)
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(2, r.store.spikeDrives)
    }

    @Test fun spikeCountStartsOverAfter90Days() {
        val day = 24 * 3600_000L
        val old = Run(MemoryRuleStore().apply { spikeDrives = 5; spikeLastMs = 1_000_000_000L - 91 * day })
        coolantRise(old, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(1, old.store.spikeDrives)
        assertEquals(Alerts.Level.INFO, old.level("spike"))
        val recent = Run(MemoryRuleStore().apply { spikeDrives = 5; spikeLastMs = 1_000_000_000L - 80 * day })
        coolantRise(recent, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(6, recent.store.spikeDrives)
        assertEquals(Alerts.Level.WARN, recent.level("spike"))
    }

    @Test fun aClockSetBackNeitherCountsNorWipes() {
        val day = 24 * 3600_000L
        val last = 400 * day
        val store = MemoryRuleStore().apply { spikeDrives = 2; spikeLastMs = last }
        // booted with a reset clock, a year behind
        val reset = Run(store).apply { now = last - 365 * day }
        coolantRise(reset, 97, 105, turbo20, fan = true, turbo = true)
        assertTrue(reset.ids().contains("spike"))
        assertEquals(2, store.spikeDrives)
        assertEquals(last, store.spikeLastMs)
        // the clock corrected: an ordinary next drive, nothing lost
        val corrected = Run(store).apply { now = last + 7 * 3600_000L }
        coolantRise(corrected, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(3, store.spikeDrives)
        assertEquals(Alerts.Level.WARN, corrected.level("spike"))
    }

    @Test fun spikeOnceIsInfoThreeDrivesIsWarn() {
        val r = Run()
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(Alerts.Level.INFO, r.level("spike"))
        assertTrue(r.texts("spike").none { it.contains("ГБЦ") || it.contains("прокладк") })
        assertEquals(1, r.store.spikeDrives)
        park(r)
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(Alerts.Level.INFO, r.level("spike"))
        assertTrue(r.texts("spike").none { it.contains("ГБЦ") })
        assertEquals(2, r.store.spikeDrives)
        park(r)
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(Alerts.Level.WARN, r.level("spike"))
        assertTrue(r.texts("spike").last().contains("ГБЦ"))
        assertEquals(3, r.store.spikeDrives)
        // the count outlives the app: a new analyzer on the same store goes on from it
        val again = Run(r.store)
        again.now = r.now + 7 * 3600_000L
        coolantRise(again, 97, 105, turbo20, fan = true, turbo = true)
        assertEquals(Alerts.Level.WARN, again.level("spike"))
    }

    @Test fun spikeUnderLoadChangeIsSilent() {
        val r = Run()
        coolantRise(r, 97, 105, turbo20, fan = true, turbo = true, maf = { 18f * (1f + 0.4f * it) })
        assertFalse(r.ids().contains("spike"))
        // within 30 % it is still a steady load
        val steady = Run()
        coolantRise(steady, 97, 105, turbo20, fan = true, turbo = true, maf = { 18f * (1f + 0.2f * it) })
        assertTrue(steady.ids().contains("spike"))
    }

    @Test fun spikeInTrafficIsSilent() {
        val r = Run()
        r.feed(6 * 60) { RuleInput(cruise(it, 97), fan = true, profile = turbo20) }
        var i = 0
        r.feed(62) { i++; RuleInput(cruise(it, 97 + minOf(i, 120) * 8 / 120).copy(speedKmh = 15), fan = true, profile = turbo20) }
        assertFalse(r.ids().contains("spike"))
    }

    @Test fun genericProfileNeverNamesGasket() {
        val makes = listOf(
            EngineProfiles.of(EngineInfo("EJ", true, "Link", "Link")),  // boost seen on a sports ECU
            EngineProfiles.GENERIC,                                     // another make
        )
        makes.forEach { p ->
            val r = Run()
            repeat(3) {
                // long enough at 100° and speed for the other cooling warning too
                coolantRise(r, 92, 100, p, fan = true, holdS = 70)
                park(r)
            }
            assertEquals(Alerts.Level.WARN, r.level("spike"))
            assertTrue(r.ids().contains("hotSpeed"))
            assertTrue(r.said.none { it.third.contains("ГБЦ") || it.third.contains("прокладк") })
        }
    }

    @Test fun hotAtSpeedOnASubaruStillNamesTheGasket() {
        val r = Run()
        coolantRise(r, 92, 100, turbo20, fan = true, holdS = 70)
        assertTrue(r.texts("hotSpeed").last().contains("ГБЦ"))
    }

    @Test fun sohcEj25TalksAboutCoolantNotGas() {
        val r = Run()
        repeat(3) { coolantRise(r, 92, 100, na25, fan = true); park(r) }
        assertEquals(Alerts.Level.WARN, r.level("spike"))
        val last = r.texts("spike").last()
        assertTrue(last, last.contains("уровень"))
        assertTrue(last, last.contains("наружу"))
        assertTrue(r.texts("spike").none { it.contains("газ") })
    }

    @Test fun olderNaInternalGasketTalksAboutGas() {
        val p = EngineProfiles.of(EngineInfo("EJ22", false, "EJ22", "ЭБУ"))
        val r = Run()
        repeat(3) { coolantRise(r, 90, 98, p, fan = true); park(r) }
        assertEquals(Alerts.Level.WARN, r.level("spike"))
        assertTrue(r.texts("spike").last().contains("прорыве газов"))
    }

    @Test fun fanRelayCreepStartsAtTheProfilesPoint() {
        fun jam(p: EngineProfile, top: Int): Set<String> {
            val r = Run()
            r.feed(6 * 60) { RuleInput(cruise(it, top - 4), fan = true, fanLow = true, profile = p) }
            var i = 0
            r.feed(190) { i++; RuleInput(cruise(it, top - 4 + (i - 240).coerceIn(0, 120) * 4 / 120).copy(speedKmh = 5, throttlePct = 0f, rpm = 800, mafGs = 3f), fan = true, fanLow = true, profile = p) }
            return r.ids()
        }
        assertFalse(jam(na25, 95).contains("fanRelay"))
        assertTrue(jam(na25, 97).contains("fanRelay"))
        assertTrue(jam(turbo20, 93).contains("fanRelay"))
    }

    @Test fun healthyCarStaysQuiet() {
        val r = Run()
        r.feed(20 * 60) { RuleInput(cruise(it), iam = 1f, fbkc = 0f, flkc = 0f, oilC = 90f) }
        assertEquals(emptySet<String>(), r.ids())
    }

    @Test fun warmIsByTheCoolantAt80NotTheOilEstimate() {
        // revving a 75° engine: still cold by the ECU's own warm-up enrichment
        val cold = Run()
        cold.feed(30) { RuleInput(cruise(it, c = 75).copy(rpm = 4000), oilC = 70f) }
        assertTrue(cold.ids().contains("cold"))
        // 82° coolant with the oil estimate still behind: warm, no warning
        val warm = Run()
        warm.feed(30) { RuleInput(cruise(it, c = 82).copy(rpm = 4000), oilC = 55f) }
        assertFalse(warm.ids().contains("cold"))
    }

    @Test fun lowIamAfterEcuResetIsNotADanger() {
        val r = Run(MemoryRuleStore().apply { lastIam = 1f })
        r.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.5f) }
        assertTrue(r.ids().contains("iamRelearn"))
        assertFalse(r.ids().contains("iam"))
        assertTrue(r.store.iamResetMs != 0L)
    }

    @Test fun lowIamWithoutResetIsADangerOnATurbo() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.72f })
        r.feed(10 * 60) { RuleInput(loaded(it), turbo = true, iam = 0.7f, fbkc = -3f, flkc = 0f) }
        assertTrue(r.ids().contains("iam"))
        assertEquals(Alerts.Level.DANGER, r.level("iam"))
    }

    /** A loaded pull: 4000 rpm, 1.5 g/rev, where knock counts. */
    private fun loaded(t: Long) = cruise(t).copy(rpm = 4000, throttlePct = 70f, mafGs = 100f)

    /** A turbo drive: a warm-up at IAM 1.0, then [seconds] with the IAM [iam] gives for each moment and the corrections as given, on [snap]. */
    private fun iamDrive(r: Run, seconds: Int, fbkc: Float?, flkc: Float?, snap: (Long) -> EcuSnapshot = ::loaded, iam: (Long) -> Float) {
        warmUp(r, turbo = true)
        r.feed(seconds) { RuleInput(snap(it), turbo = true, iam = iam(it), fbkc = fbkc, flkc = flkc) }
    }

    @Test fun singleLowIamSampleDoesNotAlarm() {
        val r = Run()
        warmUp(r, turbo = true)
        val glitch = r.now + 60_000L
        r.feed(120) { RuleInput(cruise(it), turbo = true, iam = if (it == glitch) 0.5f else 1f, fbkc = 0f, flkc = 0f) }
        assertFalse(r.ids().contains("iam"))
        assertFalse(r.ids().contains("iamDrop"))
    }

    @Test fun iamHeldLowWithoutKnockEvidenceIsWarnNotDanger() {
        val r = Run()
        iamDrive(r, 30, fbkc = 0f, flkc = 0f) { 0.69f }
        assertTrue(r.ids().contains("iam"))
        assertEquals(Alerts.Level.WARN, r.level("iam"))
        assertFalse(r.texts("iam").any { it.contains("сильно урезал") || it.contains("как обычно") })
        assertTrue(r.texts("iam").all { it.contains("Откатов сейчас нет") })
        // corrections not read: no claim that there are none
        val blind = Run()
        iamDrive(blind, 30, fbkc = null, flkc = null) { 0.69f }
        assertFalse(blind.texts("iam").any { it.contains("Откатов сейчас нет") })
    }

    @Test fun knockAtLightLoadIsNoEvidence() {
        val r = Run()
        iamDrive(r, 30, fbkc = -3f, flkc = 0f, snap = ::cruise) { 0.69f }
        assertEquals(Alerts.Level.WARN, r.level("iam"))
    }

    /** Bad fuel in the last drive left IAM low: the next drives start low too, and knock or a learned FLKC still count. */
    @Test fun lowLastIamWithKnockStaysDanger() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.69f })
        r.feed(10 * 60) { RuleInput(loaded(it), turbo = true, iam = 0.69f, fbkc = -3f, flkc = 0f) }
        assertEquals(Alerts.Level.DANGER, r.level("iam"))
    }

    @Test fun lowLastIamWithLearnedFlkcIsNotParked() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.69f })
        r.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.69f, fbkc = 0f, flkc = -0.7f) }
        assertEquals(Alerts.Level.WARN, r.level("iam"))
    }

    @Test fun lastIamExactlyAtInitialIsParked() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.70f })
        r.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.70f, fbkc = 0f, flkc = 0f) }
        assertFalse(r.ids().contains("iam"))
    }

    @Test fun iamInitialFromTheRomMovesTheParkedLine() {
        val rom = Run()
        rom.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.72f, fbkc = 0f, flkc = 0f, iamInitial = 0.75f) }
        assertFalse(rom.ids().contains("iam"))
        // 0.72 on the typical 0.70 is a learned value
        val typical = Run()
        typical.feed(10 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.72f, fbkc = 0f, flkc = 0f) }
        assertTrue(typical.ids().contains("iam"))
    }

    @Test fun iamLowWithKnockIsDanger() {
        val r = Run()
        iamDrive(r, 30, fbkc = -3f, flkc = 0f) { 0.69f }
        assertEquals(Alerts.Level.DANGER, r.level("iam"))
        assertFalse(r.texts("iam").any { it.contains("сейчас детонирует") })
    }

    /** IAM sitting at the ROM's initial value and climbing by 1/16 with no corrections: what a reset or a fresh flash looks like, a month on too. */
    @Test fun iamParkedAtInitialIsResetState() {
        val r = Run(MemoryRuleStore().apply { iamResetMs = 1_000_000_000L - 31L * 24 * 3600_000L })
        val t0 = r.now
        r.feed(20 * 60) { RuleInput(cruise(it), turbo = true, iam = 0.6875f + 0.0625f * ((it - t0) / (8 * 60_000L)).coerceAtMost(3), fbkc = 0f, flkc = 0f) }
        assertFalse(r.ids().contains("iam"))
    }

    @Test fun flkcLadderAndLowLoadNoise() {
        val r = Run()
        warmUp(r)
        // light load at low revs: the sensor's taps on a healthy engine, nothing to say
        r.feed(30) { RuleInput(cruise(it).copy(rpm = 2000, mafGs = 15f), flkc = -3.0f, fbkc = -4.5f) }
        assertEquals(emptySet<String>(), r.ids())
        // −4° under real load: the ECU is about to reset IAM
        r.feed(10) { RuleInput(cruise(it).copy(rpm = 3500, mafGs = 70f, throttlePct = 70f), flkc = -4.2f) }
        assertEquals(Alerts.Level.DANGER, r.level("flkc"))
    }

    @Test fun fbkcRightAfterAThrottleStabIsIgnored() {
        val r = Run()
        warmUp(r, turbo = true)
        var i = 0
        // the throttle jumps every second: always transient
        r.feed(20) { RuleInput(cruise(it).copy(rpm = 4000, mafGs = 90f, mapKpa = 160f, throttlePct = if (i++ % 2 == 0) 90f else 30f), turbo = true, fbkc = -4.5f) }
        assertFalse(r.ids().contains("fbkc"))
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 4000, mafGs = 90f, mapKpa = 160f, throttlePct = 90f), turbo = true, fbkc = -4.5f) }
        assertTrue(r.ids().contains("fbkc"))
    }

    @Test fun leanTrimsInClosedLoop() {
        val r = Run()
        warmUp(r)
        r.feed(150) { RuleInput(cruise(it).copy(afCorrPct = if ((it / 500) % 2 == 0L) 9f else 11f, afLearnPct = 8f)) }
        assertTrue(r.ids().contains("lean"))
        assertEquals(Alerts.Level.WARN, r.level("lean"))
    }

    @Test fun fuelCutAfterHighBoost() {
        val r = Run()
        warmUp(r, turbo = true)
        // the injectors off at full throttle without boost before it: not an overboost cut
        r.feed(2) { RuleInput(cruise(it).copy(rpm = 4500, mafGs = 60f, mapKpa = 95f, throttlePct = 100f, injPwMs = 0.2f, lambda = 0.8f), turbo = true) }
        assertFalse(r.ids().contains("fuelCut"))
        r.feed(2) { RuleInput(cruise(it).copy(rpm = 4500, mafGs = 120f, mapKpa = 190f, throttlePct = 100f, injPwMs = 9f, lambda = 0.8f), turbo = true) }
        // the cut: the boost falls away as the fuel stops
        r.feed(1) { RuleInput(cruise(it).copy(rpm = 4600, mafGs = 80f, mapKpa = 120f, throttlePct = 100f, injPwMs = 0.2f, lambda = 0.8f), turbo = true) }
        assertTrue(r.ids().contains("fuelCut"))
        // at the rev limiter the ECU cuts fuel on purpose
        val r2 = Run()
        warmUp(r2, turbo = true)
        r2.feed(2) { RuleInput(cruise(it).copy(rpm = 6300, mafGs = 120f, mapKpa = 190f, throttlePct = 100f, injPwMs = 9f, lambda = 0.8f), turbo = true) }
        r2.feed(1) { RuleInput(cruise(it).copy(rpm = 6500, mafGs = 120f, mapKpa = 190f, throttlePct = 100f, injPwMs = 0.2f, lambda = 0.8f), turbo = true) }
        assertFalse(r2.ids().contains("fuelCut"))
    }

    @Test fun trafficThenANightThenTheMorning() {
        val r = Run()
        warmUp(r)
        // a summer jam: 101° standing for two minutes, then the engine off
        r.feed(120) { RuleInput(cruise(it, c = 101).copy(speedKmh = 0, throttlePct = 0f, rpm = 750, mafGs = 3f)) }
        r.feed(5) { RuleInput(cruise(it, c = 101).copy(speedKmh = 0, rpm = 0)) }
        // the next morning: cold start, off we go
        r.now += 10 * 3600_000L
        r.feed(4 * 60) { RuleInput(cruise(it, c = 40)) }
        assertFalse(r.ids().contains("fans"))
    }

    @Test fun hotRestartIsNotOverheating() {
        val r = Run()
        warmUp(r)
        r.feed(5) { RuleInput(cruise(it).copy(rpm = 0, speedKmh = 0)) }
        r.now += 10 * 60_000L // ten minutes at the shop
        // heat soak: the sensor reads 106° for the first minute after the restart
        r.feed(60) { RuleInput(cruise(it, c = 106).copy(speedKmh = 0, throttlePct = 0f, rpm = 800)) }
        assertFalse(r.ids().contains("hot"))
        assertFalse(r.ids().contains("warm102"))
    }

    @Test fun highAltitudeIdleIsFine() {
        val r = Run()
        warmUp(r)
        // a pass at 2500 m: 75 kPa outside, the idle manifold as everywhere
        r.feed(90) { RuleInput(cruise(it).copy(rpm = 720, speedKmh = 0, throttlePct = 0f, mafGs = 2.6f, atmKpa = 75f, mapKpa = 34f)) }
        assertFalse(r.ids().contains("idleMap"))
        assertFalse(r.ids().contains("atm"))
    }

    @Test fun tunedIdleOnALinkIsNoFault() {
        // cams and a raised idle target: 1150 rpm at 50 kPa
        val idle = { r: Run, sport: Boolean -> r.feed(120) { RuleInput(cruise(it).copy(rpm = 1150, speedKmh = 0, throttlePct = 0f, mafGs = 4f, mapKpa = 50f), sportEcu = sport) } }
        val stock = Run(); warmUp(stock); idle(stock, false)
        assertTrue(stock.ids().contains("idleMap"))
        assertTrue(stock.ids().contains("idleHigh"))
        val link = Run(); warmUp(link); idle(link, true)
        assertFalse(link.ids().contains("idleMap"))
        assertFalse(link.ids().contains("idleHigh"))
    }

    @Test fun iamRecoveringAfterBadFuelIsNotAReset() {
        val r = Run(MemoryRuleStore().apply { lastIam = 0.81f })
        var iam = 0.81f
        r.feed(10 * 60) { iam = minOf(0.875f, iam + 0.0002f); RuleInput(cruise(it), iam = iam) }
        assertEquals(0L, r.store.iamResetMs)
    }

    @Test fun transmissionHeldInThirdIsTheFailsafe() {
        val r = Run()
        var v = 0
        r.feed(60) { RuleInput(cruise(it).copy(speedKmh = minOf(90, v++)), TcuSnapshot(timeMs = it, atfC = 60, gear = 3, range = "D")) }
        assertTrue(r.ids().contains("limp"))
    }

    @Test fun lockUpSlipByTheFactoryCriterion() {
        val r = Run()
        // 50 rpm apart at 90 km/h is within 40 + 90/2: fine
        r.feed(15) { RuleInput(cruise(it).copy(speedKmh = 90), TcuSnapshot(timeMs = it, atfC = 70, gear = 4, lockUpPct = 95f, turbineRpm = 2450, range = "D")) }
        assertFalse(r.ids().contains("lockSlipF"))
        r.feed(15) { RuleInput(cruise(it).copy(speedKmh = 90), TcuSnapshot(timeMs = it, atfC = 70, gear = 4, lockUpPct = 95f, turbineRpm = 2380, range = "D")) }
        assertTrue(r.ids().contains("lockSlipF"))
    }

    @Test fun sensorsDisagreeAfterALongPark() {
        val r = Run(MemoryRuleStore().apply { lastRunMs = 1_000_000_000L - 10 * 3600_000L })
        r.feed(1) { RuleInput(EcuSnapshot(timeMs = it, coolantC = 4, intakeC = 19, rpm = 0)) }
        assertTrue(r.ids().contains("tempPlaus"))
        // a block heater warmed the coolant overnight: that is not a faulty sensor
        val h = Run(MemoryRuleStore().apply { lastRunMs = 1_000_000_000L - 10 * 3600_000L })
        h.feed(1) { RuleInput(EcuSnapshot(timeMs = it, coolantC = 25, intakeC = -15, rpm = 0)) }
        assertFalse(h.ids().contains("tempPlaus"))
    }

    @Test fun warmIdleIsSampledAndJudged() {
        val r = Run()
        warmUp(r)
        r.feed(70) {
            RuleInput(cruise(it).copy(rpm = 920, speedKmh = 0, throttlePct = 0f, mafGs = 3.0f, injPwMs = 1.9f, timingDeg = 12f, mapKpa = 33f, afCorrPct = 1f, afLearnPct = 3f))
        }
        val sample = r.idle
        assertNotNull(sample)
        assertEquals(920f, sample!!.values["rpm"]!!, 1f)
        val table = IdleRef.forEngine(EngineInfo("EJ20", true, "EJ205", "настройки"))!!
        val lines = IdleRef.judge(table, sample.values).associateBy { it.row.key }
        assertEquals(IdleRef.Verdict.HIGH, lines["rpm"]!!.verdict)
        assertEquals(IdleRef.Verdict.OK, lines["maf"]!!.verdict)
        assertEquals(IdleRef.Verdict.OK, lines["map"]!!.verdict)
    }

    @Test fun oneAtfMisreadIsNotASensorFault() {
        val r = Run()
        var n = 0
        // one sample of nonsense in the middle of a normal 70°
        r.feed(60) { RuleInput(cruise(it), TcuSnapshot(timeMs = it, atfC = if (n++ == 40) 15 else 70, gear = 4, range = "D")) }
        assertFalse(r.ids().contains("atfSensor"))
        // the reading keeps leaping: that is the sensor or its wiring
        r.feed(60) { RuleInput(cruise(it), TcuSnapshot(timeMs = it, atfC = if (n++ % 8 == 0) 15 else 70, gear = 4, range = "D")) }
        assertTrue(r.ids().contains("atfSensor"))
    }

    @Test fun factoryRangesAndJustOutside() {
        assertEquals(29.5f to 37f, IdleRef.factory("29,5–37 кПа"))
        assertEquals(-10f to 10f, IdleRef.factory("−10…+10 %"))
        assertEquals(-5f to 5f, IdleRef.factory("±5°"))
        assertEquals(null, IdleRef.factory("около 700"))
        val table = IdleRef.forEngine(EngineInfo("EJ20", true, "EJ205", "ЭБУ"))!!
        val l = IdleRef.judge(table, mapOf("inj" to 2.3f, "rpm" to 700f, "avcs" to 0.5f, "map" to 45f)).associateBy { it.row.key }
        assertEquals(IdleRef.Verdict.NEAR, l["inj"]!!.verdict)   // 2.3 against 1.2–2.2: just outside, healthy cars do it
        assertEquals(IdleRef.Verdict.OK, l["rpm"]!!.verdict)
        assertEquals(IdleRef.Verdict.OK, l["avcs"]!!.verdict)
        assertEquals(IdleRef.Verdict.HIGH, l["map"]!!.verdict)
    }

    @Test fun idleHistoryKeepsOnePerHalfHour() {
        val h = kg.forester.sg.service.RulesHost
        var list = emptyList<IdleSample>()
        list = h.addToHistory(list, IdleSample(0L, mapOf("rpm" to 700f)))
        list = h.addToHistory(list, IdleSample(60_000L, mapOf("rpm" to 710f)))       // the same stop: replaces
        list = h.addToHistory(list, IdleSample(3600_000L, mapOf("rpm" to 720f)))     // another day: added
        assertEquals(listOf(710f, 720f), list.map { it.values["rpm"] })
        repeat(50) { list = h.addToHistory(list, IdleSample(7200_000L + it * 3600_000L, mapOf("rpm" to 700f))) }
        assertEquals(40, list.size)
    }

    @Test fun p23IsNotALivePull() {
        // P23 is knock correction advance (KA x IAM + FBKC + FLKC): a negative one with the FBKC at 0 is no live pull
        assertEquals(0f, EngineGuard.livePull(-2.8f, 0f, true)!!, 0f)
        // the pull comes from the FBKC when the ROM has it, whatever P23 says
        assertEquals(-2.8f, EngineGuard.livePull(3.2f, -2.8f, true)!!, 0f)
        assertEquals(0f, EngineGuard.livePull(3.2f, 0.5f, true)!!, 0f)
        // an address borrowed from a sibling ROM may read a constant 0: the deeper of the two stays
        assertEquals(-2.8f, EngineGuard.livePull(-2.8f, 0f, false)!!, 0f)
        assertEquals(-3f, EngineGuard.livePull(1f, -3f, false)!!, 0f)
        assertEquals(0.5f, EngineGuard.livePull(3.2f, 0.5f, false)!!, 0f)
        // the knock events start at START_DEG: with an own FBKC at 0 a negative P23 stays below it
        assertTrue(EngineGuard.livePull(-2.8f, 0f, true)!! > kg.forester.sg.data.KnockStats.START_DEG)
        // no FBKC to read: P23 as it is
        assertEquals(-2.8f, EngineGuard.livePull(-2.8f, null, true)!!, 0f)
        assertEquals(null, EngineGuard.livePull(null, null, true))
        // a learned FLKC held by the ECU is a pull as well, as up to 0.9-beta32
        assertEquals(-2.8f, EngineGuard.livePull(0f, 0f, true, -2.8f)!!, 0f)
        assertEquals(-4.2f, EngineGuard.livePull(0f, -1.4f, true, -2.8f)!!, 1e-4f)
        assertEquals(0f, EngineGuard.livePull(0f, 0f, true, 0.5f)!!, 0f)
    }
}
