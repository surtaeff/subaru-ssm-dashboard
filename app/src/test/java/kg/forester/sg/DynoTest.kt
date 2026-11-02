package kg.forester.sg

import kg.forester.sg.service.Analyzer
import kg.forester.sg.service.Demo
import kg.forester.sg.service.DynoCalc
import kg.forester.sg.service.DynoPoint
import kg.forester.sg.service.DynoPoll
import kg.forester.sg.service.DynoRun
import kg.forester.sg.service.DynoSample
import kg.forester.sg.service.DynoStore
import kg.forester.sg.service.DynoTracker
import kg.forester.sg.service.EngineGuard
import kg.forester.sg.service.MemoryRuleStore
import kg.forester.sg.service.PerfCalc
import kg.forester.sg.service.RuleInput
import kg.forester.sg.service.niceStep
import kg.forester.sg.ssm.EcuParams
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class DynoTest {
    /** A pull at a steady [accel] m/s²: rpm as smooth as the ECU gives it, the speed in whole km/h as the ECU gives it. */
    private fun pull(hz: Int, seconds: Double, accel: Double = 2.0, ratio: Double = 35.0, from: Double = 60.0, t0: Long = 1000L): List<DynoSample> {
        val n = (seconds * hz).toInt()
        return List(n + 1) { i ->
            val t = i.toDouble() / hz
            val kmh = from + accel * 3.6 * t
            DynoSample(t0 + (t * 1000).toLong(), (kmh * ratio).toInt(), kmh.toInt().toFloat(), 100f, 80f)
        }
    }

    private fun run(startMs: Long, ratio: Float = 35f, gear: Int? = 3, label: String? = null) =
        DynoRun(startMs, 1550f, 1f, gear, ratio, label, 20, 99.5f, 88, 1f, DynoCalc.ALGO)

    // ---- the counting

    @Test fun dynoFromRpmNotSpeed() {
        val curve = DynoCalc.curve(pull(30, 8.0), 1550f, 1f)
        assertTrue("cells: ${curve.size}", curve.size >= 10)
        val m = 1550.0
        for (c in curve) {
            val rpm = c.rpm
            val v = rpm / 35.0 / 3.6
            val f = m * 2.0 * PerfCalc.rotatingMass(rpm, v) + 0.5 * 1.2 * 0.85 * v * v + 0.015 * m * 9.81
            val truth = f * v / 735.5
            assertEquals("hp at ${c.rpm}", truth, c.hp.toDouble(), truth * 0.04)
        }
        // the whole-km/h steps of the speed leave no saw in the curve
        val steps = curve.zipWithNext { a, b -> abs(b.hp - a.hp) }
        assertTrue("max step ${steps.max()}", steps.max() < 6f)
    }

    @Test fun gearFactorIsTheRegressionOfSpeedOnRpm() {
        // v = k·ω: 35 rpm per km/h is (1/35 km/h per rpm)
        val k = DynoCalc.gearK(pull(30, 8.0), 1f)
        val exact = 1.0 / 35.0 / 3.6 / (2 * Math.PI / 60)
        assertEquals(exact, k, exact * 0.02)
        assertEquals(35f, DynoCalc.rpmPerKmh(pull(30, 8.0), 1f), 0.7f)
    }

    @Test fun pullSplitsOnShift() {
        val first = pull(30, 4.0, ratio = 45.0, from = 60.0)
        // the next gear: the revs fall by far more than 200
        val second = pull(30, 4.0, ratio = 30.0, from = first.last().kmh.toDouble() + 1, t0 = first.last().tMs + 33)
        val parts = DynoCalc.split(first + second)
        assertEquals(2, parts.size)
        assertEquals(first.size, parts[0].size)
        // a clutch slipping: rpm/v leaves by more than 8 % with the revs going up
        val clean = pull(30, 3.0, ratio = 35.0, from = 80.0)
        val slip = clean.takeLast(30).map { it.copy(tMs = it.tMs + 3100, rpm = (it.rpm * 1.12f + 400).toInt()) }
        assertEquals(2, DynoCalc.split(clean + slip).size)
        // a wobble of 100 rpm and a hole left by a full read split nothing
        val wobble = pull(30, 4.0).mapIndexed { i, s -> if (i % 10 == 0) s.copy(rpm = s.rpm - 100) else s }
        assertEquals(1, DynoCalc.split(wobble).size)
        assertEquals(1, DynoCalc.split(pull(30, 4.0).filterIndexed { i, _ -> i !in 40..50 }).size)
    }

    @Test fun peakIgnoresSingleSampleBins() {
        val dense = (0 until 300).map { DynoCalc.Raw(3000 + it * 10 / 3, 100f, 200f) }
        val spike = dense + DynoCalc.Raw(5150, 300f, 500f)
        val cells = DynoCalc.cells(spike)
        assertTrue(cells.none { it.rpm == 5150 })
        assertEquals(100f, cells.maxOf { it.hp }, 0.01f)
        assertEquals("100 rpm cells at 3+ samples each", 100, cells[1].rpm - cells[0].rpm)
        // two samples a cell at 100 rpm: the run takes cells of 250
        val sparse = (0 until 60).map { DynoCalc.Raw(3000 + it * 50, 100f, 200f) }
        val wide = DynoCalc.cells(sparse)
        assertEquals(250, wide[1].rpm - wide[0].rpm)
        // the smoothing window by the sampling rate
        assertEquals(150L, DynoCalc.windowMs(pull(30, 4.0)))
        assertEquals(150L, DynoCalc.windowMs(pull(20, 4.0)))
        assertEquals(300L, DynoCalc.windowMs(pull(12, 4.0)))
    }

    @Test fun gradeIsSubtracted() {
        val s = pull(30, 6.0, accel = 1.5, from = 90.0)
        fun hp(slope: Float?) = DynoCalc.curve(s, 1550f, 1f, slope).first { it.rpm == 3550 }.hp
        // downhill the road gave those horses: they come off, uphill they are added, about 6 hp per 1 % at 100 km/h
        assertEquals(5.8f, hp(null) - hp(-1f), 0.5f)
        assertEquals(5.8f, hp(1f) - hp(null), 0.5f)
        assertEquals(hp(null), hp(0f), 0.001f)
    }

    @Test fun curveClearsAtRunStart() {
        val t = DynoTracker()
        fun feed(list: List<DynoSample>) = list.flatMap { t.feed(it, 1550f, 1f) } + t.feed(null, 1550f, 1f)
        val good = feed(pull(30, 6.0))
        assertEquals(1, good.size)
        assertTrue(t.curve.size >= 4)
        assertFalse(t.rejected)
        // the new pull wipes the old curve from its first sample
        val short = pull(30, 0.5, t0 = 100_000L)
        t.feed(short[0], 1550f, 1f)
        assertTrue(t.pulling)
        assertTrue(t.curve.isEmpty())
        short.drop(1).forEach { t.feed(it, 1550f, 1f) }
        assertTrue(t.feed(null, 1550f, 1f).isEmpty())
        // and a pull of under 4 cells leaves none, saying so
        assertFalse(t.pulling)
        assertTrue(t.curve.isEmpty())
        assertTrue(t.rejected)
    }

    @Test fun shiftInAPullGivesTwoRuns() {
        val t = DynoTracker()
        val first = pull(30, 5.0, ratio = 45.0, from = 60.0)
        val second = pull(30, 5.0, ratio = 30.0, from = first.last().kmh.toDouble() + 1, t0 = first.last().tMs + 33)
        (first + second).forEach { t.feed(it, 1550f, 1f) }
        val done = t.feed(null, 1550f, 1f)
        assertEquals(2, done.size)
        assertEquals(first.size, done[0].samples.size)
    }

    // ---- the storage

    @Test fun samplesSurviveTheFile() {
        val dir = Files.createTempDirectory("dyno").toFile()
        val s = pull(30, 1.0).let { it.dropLast(1) + it.last().copy(gas = null, boostKpa = null) }
        DynoStore.write(dir, run(s.first().tMs), s, -1.5f)
        val f = File(dir, "${s.first().tMs}.txt")
        assertTrue(f.readText().startsWith("dyno 1 ${s.first().tMs} 1550.0 1.0000\ns "))
        val back = DynoStore.decode(f.readText())
        assertNotNull(back)
        assertEquals(s.size, back!!.samples.size)
        assertEquals(s.last(), back.samples.last())
        assertEquals(-1.5f, back.slopePct!!, 0.001f)
        assertEquals(s[3].rpm, back.samples[3].rpm)
        dir.deleteRecursively()
    }

    @Test fun oldOrBrokenFileIsSkipped() {
        val dir = Files.createTempDirectory("dyno").toFile()
        val good = pull(30, 4.0)
        DynoStore.write(dir, run(5000L, label = "98"), good, null)
        val meta = DynoStore.metaJson(listOf(run(5000L, label = "98")))
        File(dir, "1000.txt").writeBytes(byteArrayOf(0, 1, 2, 3, -1, -2))
        File(dir, "2000.txt").writeText("dyno 9 2000 1550.0 1.0000\ns 1 3000 100.0 90.0 50.0\n")
        File(dir, "3000.txt").writeText("trace 1 3000 1.0\n")
        File(dir, "4000.txt").writeText("dyno 1 4000 1550.0 1.0000\ns 1 3000 oops\n")
        File(dir, "4500.txt").writeText("")
        File(dir, "notes.bin").writeText("x")
        val runs = DynoStore.scan(dir, meta)
        assertEquals(listOf(5000L), runs.map { it.startMs })
        assertEquals("98", runs[0].label)
        // bad metadata: the runs are still there, by their files
        val noMeta = DynoStore.scan(dir, "{not json")
        assertEquals(listOf(5000L), noMeta.map { it.startMs })
        assertEquals(35f, noMeta[0].rpmPerKmh, 1f)
        assertNull(DynoStore.decode("dyno 1 4000 NaN 1.0\n"))
        // a missing folder is an empty list
        assertTrue(DynoStore.scan(File(dir, "nope"), "[]").isEmpty())
        dir.deleteRecursively()
    }

    @Test fun onlyAHundredRunsAreKept() {
        val dir = Files.createTempDirectory("dyno").toFile()
        val runs = (1L..103L).map { run(it * 1000) }.reversed()
        val s = pull(30, 0.2)
        runs.forEach { DynoStore.write(dir, it, s, null) }
        val kept = DynoStore.prune(dir, runs)
        assertEquals(100, kept.size)
        assertEquals(103_000L, kept.first().startMs)
        assertEquals(4000L, kept.last().startMs)
        assertFalse(File(dir, "3000.txt").exists())
        assertTrue(File(dir, "4000.txt").exists())
        dir.deleteRecursively()
    }

    @Test fun metaKeepsWhatTheRunWasDrivenIn() {
        val dir = Files.createTempDirectory("dyno").toFile()
        val r = DynoRun(7000L, 1600f, 1.03f, null, 34.5f, null, null, null, null, null, 1)
        val r2 = run(8000L, label = "95")
        DynoStore.write(dir, r, pull(30, 0.5), null)
        DynoStore.write(dir, r2, pull(30, 0.5), null)
        val back = DynoStore.scan(dir, DynoStore.metaJson(listOf(r2, r)))
        assertEquals(listOf(r2, r), back)
        dir.deleteRecursively()
    }

    // ---- what the comparison says

    private fun curve(plateauFrom: Int, plateauTo: Int, dropAt: Int): List<DynoPoint> {
        val peak = 300f
        return (2500..6500 step 250).map { r ->
            val nm = when {
                r < plateauFrom -> peak * 0.8f
                r <= plateauTo -> peak
                else -> peak * 0.85f
            }
            // the power up to its peak 500 rpm before the drop, then under 97 %
            val hp = when {
                r < dropAt - 500 -> 100f + r / 50f
                r < dropAt -> 100f + (dropAt - 500) / 50f
                else -> (100f + (dropAt - 500) / 50f) * 0.9f
            }
            DynoPoint(r, hp, nm)
        }
    }

    @Test fun plateauAndDropCompare() {
        val now = curve(3250, 4750, 5750)
        val was = curve(3000, 4500, 5500)
        assertEquals(3250..4750, DynoCalc.plateau(now))
        assertEquals(5750, DynoCalc.dropAfter(now))
        assertEquals(
            listOf("Плато момента 3250–4750, было 3000–4500 (+250)", "Мощность падает после 5750, было 5500 (+250)"),
            DynoCalc.compareLines(now, was),
        )
        // moved the other way
        assertEquals("Плато момента 3000–4500, было 3250–4750 (-250)", DynoCalc.compareLines(was, now)[0])
        // nothing to compare with
        assertEquals(listOf("Плато момента 3250–4750", "Мощность падает после 5750"), DynoCalc.compareLines(now, null))
        // the longest stretch, not the first
        val two = listOf(2500, 2750, 3000, 3250, 3500, 3750, 4000).map { DynoPoint(it, 100f, if (it in 3250..4000 || it == 2500) 300f else 200f) }
        assertEquals(3250..4000, DynoCalc.plateau(two))
        // a pull that ended before the power fell
        val rising = (3000..5000 step 250).map { DynoPoint(it, it / 20f, 300f) }
        assertNull(DynoCalc.dropAfter(rising))
        assertEquals("Мощность не упала до конца заезда, было 5500", DynoCalc.compareLines(rising, was)[1])
        // other gear: rpm/v differs by more than 8 %
        assertTrue(DynoCalc.otherGear(run(1, 35f), run(2, 38.5f)))
        assertFalse(DynoCalc.otherGear(run(1, 35f), run(2, 37f)))
    }

    @Test fun compareLinesRoundTo50() {
        fun c(from: Int, to: Int, drop: Int) = listOf(
            DynoPoint(2000, 100f, 100f), DynoPoint(from, 150f, 300f), DynoPoint(to, 200f, 300f), DynoPoint(drop, 100f, 100f),
        )
        // cells of 250 stand at their centres, ..75 and ..25
        assertEquals(
            listOf("Плато момента 3400–4900, было 3250–4750 (+150)", "Мощность падает после 5650, было 5550 (+100)"),
            DynoCalc.compareLines(c(3375, 4875, 5625), c(3250, 4750, 5550)),
        )
        // down, rounded the same way
        assertEquals(
            listOf("Плато момента 3250–4750, было 3400–4900 (-150)", "Мощность падает после 5550, было 5650 (-100)"),
            DynoCalc.compareLines(c(3250, 4750, 5550), c(3375, 4875, 5625)),
        )
    }

    @Test fun everyDirectMapRoleIsReadInThePull() {
        // what withDirectMap puts over the MAP byte must be read as fresh as it: else its old value freezes the boost
        assertEquals("a", EngineGuard.fastMap(mapOf("mapAbs" to "a", "mapRel" to "b", "mapRelSea" to "c")))
        assertEquals("b", EngineGuard.fastMap(mapOf("mapRel" to "b", "mapRelSea" to "c")))
        assertEquals("c", EngineGuard.fastMap(mapOf("mapRelSea" to "c")))
        assertNull(EngineGuard.fastMap(mapOf("fbkc" to "x")))
    }

    @Test fun previousRunOfTheSameGearIsCompared() {
        val runs = listOf(run(5000, 35f), run(4000, 24f), run(3000, 35.5f), run(2000, 35f))
        assertEquals(3000L, DynoCalc.previousSameGear(runs, runs[0])?.startMs)
        assertEquals(2000L, DynoCalc.previousSameGear(runs, runs[2])?.startMs)
        assertNull(DynoCalc.previousSameGear(runs, runs[1]))
    }

    @Test fun differenceAndPercentOfPeak() {
        val a = listOf(DynoPoint(3000, 110f, 200f), DynoPoint(3500, 220f, 400f), DynoPoint(9000, 1f, 1f))
        val b = listOf(DynoPoint(3000, 100f, 300f), DynoPoint(4000, 200f, 300f))
        val d = DynoCalc.diffPct(a, b)
        // over the rpm both have, the other curve taken between its cells
        assertEquals(listOf(3000, 3500), d.map { it.first })
        assertEquals(10f, d[0].second, 0.01f)
        assertEquals(46.67f, d[1].second, 0.01f)
        val p = DynoCalc.percentOfPeak(a)
        assertEquals(100f, p[1].hp, 0.001f)
        assertEquals(50f, p[0].hp, 0.001f)
        assertEquals(100f, p[1].nm, 0.001f)
    }

    // ---- the scales

    @Test fun niceStepGivesRoundTicks() {
        assertEquals(20f, niceStep(100f, 5), 1e-4f)
        assertEquals(50f, niceStep(130f, 5), 1e-4f)
        assertEquals(2.5f, niceStep(12f, 5), 1e-4f)
        assertEquals(10f, niceStep(47f, 5), 1e-4f)
        assertEquals(1f, niceStep(3f, 5), 1e-4f)
        assertEquals(1f, niceStep(10f, 10), 1e-4f)
        assertEquals(200f, niceStep(1000f, 5), 1e-3f)
        assertEquals(100f, niceStep(450f, 5), 1e-3f)
    }

    @Test fun axesShareTheirLines() {
        // 100 hp, 118 Nm: 6 lines of 20 hp; 6 × 20 Nm = 120 is short of 118 × 1.05, so the next step
        val a = DynoCalc.axes(100f, 118f)
        assertEquals(20f, a.hpStep, 1e-4f)
        assertEquals(6, a.lines)
        assertEquals(25f, a.nmStep, 1e-4f)
        val b = DynoCalc.axes(200f, 300f)
        assertEquals(50f, b.hpStep, 1e-4f)
        assertEquals(5, b.lines)
        assertEquals(100f, b.nmStep, 1e-4f)
        assertTrue(b.lines * b.nmStep >= 300f * 1.05f)
    }

    @Test fun rpmGridGoesBy500() {
        assertEquals(listOf(2500, 3000, 3500, 4000), DynoCalc.rpmTicks(2750, 3600))
        assertEquals(listOf(3000, 3500), DynoCalc.rpmTicks(3000, 3000))
    }

    // ---- the fast poll

    @Test fun shortListIsFiveAddressesAndWhatTheRomAdds() {
        val base = intArrayOf(EcuParams.RPM_H, EcuParams.RPM_L, EcuParams.SPEED, EcuParams.THROTTLE, EcuParams.PEDAL)
        // the standard MAP byte when the ECU has it
        assertArrayEquals(base + EcuParams.MAP, DynoPoll.shortList(EcuParams.pollAddresses, emptySet(), 64))
        val noMap = EcuParams.pollAddresses.filter { it != EcuParams.MAP }.toIntArray()
        assertArrayEquals(base, DynoPoll.shortList(noMap, emptySet(), 64))
        // the ROM's own FBKC and direct MAP go after them, each address once
        assertArrayEquals(base + intArrayOf(0x20A, 0x20B), DynoPoll.shortList(noMap, emptySet(), 64, intArrayOf(0x20A, 0x20B, EcuParams.SPEED)))
        // an ECU without the pedal: the throttle is enough
        val th = intArrayOf(EcuParams.RPM_H, EcuParams.RPM_L, EcuParams.SPEED, EcuParams.THROTTLE)
        assertArrayEquals(th, DynoPoll.shortList(noMap.filter { it != EcuParams.PEDAL }.toIntArray(), emptySet(), 64))
        assertArrayEquals(th, DynoPoll.shortList(noMap, setOf(EcuParams.PEDAL), 64))
        // no rpm, no speed, or no gas: no short list
        assertNull(DynoPoll.shortList(intArrayOf(EcuParams.SPEED, EcuParams.THROTTLE), emptySet(), 64))
        assertNull(DynoPoll.shortList(intArrayOf(EcuParams.RPM_H, EcuParams.RPM_L, EcuParams.THROTTLE), emptySet(), 64))
        assertNull(DynoPoll.shortList(intArrayOf(EcuParams.RPM_H, EcuParams.RPM_L, EcuParams.SPEED), emptySet(), 64))
        // ELM327 asks one address at a time: too slow for this at any list
        assertNull(DynoPoll.shortList(EcuParams.pollAddresses, emptySet(), 1))
        // more than one request can carry: the extras stay out
        assertArrayEquals(base, DynoPoll.shortList(noMap, emptySet(), 6, intArrayOf(0x20A, 0x20B)))
    }

    @Test fun mergedFramesNeverReachTheRules() {
        val full = DynoPoll.route(merged = false, knockFresh = false)
        assertTrue(full.rules); assertTrue(full.scales); assertTrue(full.knock)
        val merged = DynoPoll.route(merged = true, knockFresh = false)
        assertFalse(merged.rules); assertFalse(merged.scales); assertFalse(merged.knock)
        // the FBKC read in the short frame itself: the knock events see it, still not the rules
        val fbkc = DynoPoll.route(merged = true, knockFresh = true)
        assertFalse(fbkc.rules); assertFalse(fbkc.scales); assertTrue(fbkc.knock)
    }

    @Test fun mapInTheShortFrameIsFresh() {
        val full = EcuSnapshot(timeMs = 1L, mapKpa = 100f, atmKpa = 95f, coolantC = 90)
        val short = EcuSnapshot(timeMs = 2L, rpm = 4000, mapKpa = 190f)
        val m = DynoPoll.merge(full, short)
        assertEquals(190f, m.mapKpa!!, 0f)
        assertEquals(95f, m.gaugeKpa!!, 0f)
        assertEquals(100f, DynoPoll.merge(full, short.copy(mapKpa = null)).mapKpa!!, 0f)
    }

    private fun warmCar(store: MemoryRuleStore, said: MutableList<String>): Pair<Analyzer, Long> {
        val a = Analyzer(store, { id, _, _, _ -> said += id }) {}
        var now = 1_000_000_000L
        repeat(720) { now += 500; a.process(RuleInput(pullFrame(now, 2500, -40f, 15f), turbo = true, ecuGear = 3)) }
        return a to now
    }

    private fun pullFrame(t: Long, rpm: Int, gauge: Float, gas: Float) = EcuSnapshot(timeMs = t, coolantC = 90, rpm = rpm, speedKmh = 80, timingDeg = 20f, intakeC = 25,
        mafGs = 18f, throttlePct = gas, batteryV = 14f, injPwMs = 3f, atmKpa = 95f, mapKpa = 95f + gauge, afCorrPct = 0f, afLearnPct = 0f, lambda = 1f)

    /** A pull of [ms] as the rules are given it: real reads at [times] ms into it, then the lift. */
    private fun drivePull(a: Analyzer, start: Long, ms: Long, times: List<Long>, skippedFrom: Long = Long.MAX_VALUE): Long {
        for (t in times) {
            val rpm = 2500 + (t * 3000 / ms).toInt()
            val gauge = -40f + t * 140f / minOf(ms, 4000L).toFloat()
            a.process(RuleInput(pullFrame(start + t, rpm, gauge.coerceAtMost(100f), 100f), turbo = true, ecuGear = 3, skippedMerged = t >= skippedFrom))
        }
        var now = start + ms
        repeat(4) { now += 250; a.process(RuleInput(pullFrame(now, 2500, -40f, 10f), turbo = true, ecuGear = 3)) }
        return now
    }

    @Test fun spoolBaselineStaysThroughAPullWithHoles() {
        // the control: the same pull read every 100 ms leaves a spool point
        val dense = MemoryRuleStore().apply { boostBase = 100f }
        val saidDense = ArrayList<String>()
        val (ad, t0) = warmCar(dense, saidDense)
        drivePull(ad, t0 + 500, 8000, (0L..8000L step 100).toList())
        assertTrue("dense: ${dense.spool}", "\"recent\":[[" in dense.spool)

        // one read before the short list, the hole of 6 s, then a read every 2 s
        val sparse = MemoryRuleStore().apply { boostBase = 100f }
        val saidSparse = ArrayList<String>()
        val (a, t1) = warmCar(sparse, saidSparse)
        val base = sparse.boostBase
        drivePull(a, t1 + 500, 8000, listOf(0L, 100L, 6100L, 8000L), skippedFrom = 6100L)
        assertFalse("sparse: ${sparse.spool}", "\"recent\":[[" in sparse.spool)
        assertEquals("the usual boost isn't judged by it", base, sparse.boostBase, 0f)
    }

    @Test fun aSlowCableKeepsItsPullRules() {
        // an ELM327 around a second a sample, no merged frames: the pull is stored as ever
        val store = MemoryRuleStore().apply { boostBase = 95f }
        val (a, t0) = warmCar(store, ArrayList())
        drivePull(a, t0 + 500, 8800, (0L..8800L step 1100).toList())
        assertTrue("stored: ${store.spool}", "\"recent\":[[" in store.spool)
        assertTrue("usual boost judged by it", store.boostBase != 95f)
    }

    @Test fun pullsWithHolesRaiseNoUnderboost() {
        val store = MemoryRuleStore().apply { boostBase = 100f }
        val said = ArrayList<String>()
        val (a, t0) = warmCar(store, said)
        var now = t0
        val before = said.size
        // the reads catch only the bottom of each pull, at 4200 rpm and hardly any boost: then the 6 s hole, then the lift
        repeat(4) {
            repeat(2) { now += 100; a.process(RuleInput(pullFrame(now, 4200, 5f, 100f), turbo = true, ecuGear = 3)) }
            now += 6000
            // the first full frame after the short ones says some were skipped
            now += 250; a.process(RuleInput(pullFrame(now, 2500, -40f, 15f), turbo = true, ecuGear = 3, skippedMerged = true))
            repeat(40) { now += 250; a.process(RuleInput(pullFrame(now, 2500, -40f, 15f), turbo = true, ecuGear = 3)) }
        }
        assertEquals(100f, store.boostBase, 0f)
        assertEquals(said.toString(), before, said.size)
    }

    @Test fun shortFrameMergesIntoTheFullSnapshot() {
        val raw = byteArrayOf(0x4E, 0x20, 100, 0x80.toByte(), 0xFF.toByte())
        val short = EcuParams.decode(DynoPoll.shortList(EcuParams.pollAddresses, emptySet(), 64)!!, raw, 9000L)
        val full = EcuSnapshot(timeMs = 8000L, coolantC = 105, rpm = 2000, speedKmh = 50, knockDeg = -2.5f, kcaDeg = -1f, lambda = 0.8f,
            atmKpa = 99f, mapKpa = 200f, batteryV = 13.9f, mafGs = 120f, intakeC = 40, afCorrPct = 3f, throttlePct = 10f, pedalPct = 10f)
        val m = DynoPoll.merge(full, short)
        // fresh from the short frame
        assertEquals(9000L, m.timeMs)
        assertEquals(5000, m.rpm)
        assertEquals(100, m.speedKmh)
        assertEquals(50.2f, m.throttlePct!!, 0.1f)
        assertEquals(100f, m.pedalPct!!, 0.1f)
        // everything the alerts read stays, none of it gone
        assertEquals(105, m.coolantC)
        assertEquals(-2.5f, m.knockDeg!!, 0f)
        assertEquals(-1f, m.kcaDeg!!, 0f)
        assertEquals(0.8f, m.lambda!!, 0f)
        assertEquals(99f, m.atmKpa!!, 0f)
        assertEquals(200f, m.mapKpa!!, 0f)
        assertEquals(13.9f, m.batteryV!!, 0f)
        assertEquals(120f, m.mafGs!!, 0f)
        assertEquals(40, m.intakeC)
        assertEquals(3f, m.afCorrPct!!, 0f)
        // a value the short frame has no answer for keeps the last one
        val noPedal = short.copy(pedalPct = null)
        assertEquals(10f, DynoPoll.merge(full, noPedal).pedalPct!!, 0f)
    }

    @Test fun shortPollOnlyInAPullOnTheTab() {
        val floored = EcuSnapshot(pedalPct = 100f, speedKmh = 60)
        val p = DynoPoll()
        assertFalse("not on the dyno tab", p.wanted(false, floored, 1000L))
        assertFalse("pedal up", p.wanted(true, EcuSnapshot(pedalPct = 20f, speedKmh = 60), 1000L))
        assertFalse("standing", p.wanted(true, EcuSnapshot(pedalPct = 100f, speedKmh = 5), 1000L))
        assertFalse("no gas value", p.wanted(true, EcuSnapshot(speedKmh = 60), 1000L))
        assertTrue(p.wanted(true, floored, 2000L))
        // the pedal wobbling just under the line doesn't restart the stream
        assertTrue(p.wanted(true, EcuSnapshot(pedalPct = 80f, speedKmh = 60), 2100L))
        assertFalse(p.wanted(true, EcuSnapshot(pedalPct = 70f, speedKmh = 60), 2200L))
        assertFalse(p.wanted(true, EcuSnapshot(pedalPct = 80f, speedKmh = 60), 2300L))
        // the throttle where there is no pedal sensor
        assertTrue(p.wanted(true, EcuSnapshot(throttlePct = 95f, speedKmh = 60), 2400L))
        // leaving the tab ends it at once
        assertFalse(p.wanted(false, floored, 2500L))
    }

    @Test fun aLongFullThrottleGoesBackToTheFullList() {
        val floored = EcuSnapshot(pedalPct = 100f, speedKmh = 150)
        val p = DynoPoll()
        assertTrue(p.wanted(true, floored, 1000L))
        assertTrue(p.wanted(true, floored, 1000L + DynoPoll.MAX_SHORT_MS - 1))
        assertFalse(p.wanted(true, floored, 1000L + DynoPoll.MAX_SHORT_MS))
        // the transmission and the whole list again until the pedal comes up
        assertFalse(p.wanted(true, floored, 1000L + DynoPoll.MAX_SHORT_MS + 5000))
        assertFalse(p.wanted(true, EcuSnapshot(pedalPct = 10f, speedKmh = 150), 90_000L))
        assertTrue(p.wanted(true, floored, 90_100L))
    }

    @Test fun noFullReadInTheFirstSecondsOfAPull() {
        val floored = EcuSnapshot(pedalPct = 100f, speedKmh = 60)
        val p = DynoPoll()
        assertTrue("nothing to merge into yet", p.fullDue(1000L))
        assertNull(p.full)
        val snap = EcuSnapshot(timeMs = 1000L, coolantC = 90)
        p.noteFull(snap, 1000L)
        assertEquals(snap, p.full)
        assertTrue(p.wanted(true, floored, 1000L))
        // the first 6 s are left whole: a read there is a hole in the measured range
        assertFalse(p.fullDue(1000L + DynoPoll.FIRST_FULL_MS - 1))
        assertTrue(p.fullDue(1000L + DynoPoll.FIRST_FULL_MS))
        p.noteFull(snap, 7000L)
        // then every 2 s
        assertFalse(p.fullDue(7000L + DynoPoll.FULL_EVERY_MS - 1))
        assertTrue(p.fullDue(7000L + DynoPoll.FULL_EVERY_MS))
    }

    // ---- what a pull belongs to

    @Test fun demoPullIsNotFinishedByTheCar() {
        val t = DynoTracker()
        val s = pull(30, 6.0)
        s.take(100).forEach { t.feed(it, 1550f, 1f, demo = true) }
        assertTrue(t.pulling)
        // the demo goes off mid-pull: the demo's part is thrown away, the car's sample starts its own
        val rest = s.drop(100)
        assertTrue(t.feed(rest[0], 1550f, 1f, demo = false).isEmpty())
        assertTrue(t.pulling)
        rest.drop(1).forEach { t.feed(it, 1550f, 1f, demo = false) }
        val done = t.feed(null, 1550f, 1f, demo = false)
        assertEquals(1, done.size)
        assertFalse(done[0].demo)
        assertEquals(rest.size, done[0].samples.size)
        // gone off with the pedal up: nothing to save
        s.forEach { t.feed(it.copy(tMs = it.tMs + 100_000), 1550f, 1f, demo = true) }
        assertTrue(t.feed(null, 1550f, 1f, demo = false).isEmpty())
        assertFalse(t.pulling)
        assertFalse(t.rejected)
        // a demo pull says so
        s.forEach { t.feed(it.copy(tMs = it.tMs + 200_000), 1550f, 1f, demo = true) }
        assertTrue(t.feed(null, 1550f, 1f, demo = true).single().demo)
    }

    @Test fun aGapOverTwoSecondsEndsThePull() {
        val t = DynoTracker()
        val first = pull(30, 5.0)
        first.forEach { t.feed(it, 1550f, 1f) }
        val second = pull(30, 5.0, t0 = first.last().tMs + 2100)
        val done = t.feed(second[0], 1550f, 1f)
        assertEquals(first.size, done.single().samples.size)
        assertTrue("the next one has started", t.pulling)
        assertTrue(t.curve.isEmpty())
        second.drop(1).forEach { t.feed(it, 1550f, 1f) }
        assertEquals(second.size, t.feed(null, 1550f, 1f).single().samples.size)
        // a hole under 2 s stays one pull
        val u = DynoTracker()
        val a = pull(30, 3.0)
        val b = pull(30, 3.0, from = 80.0, t0 = a.last().tMs + 1900)
        (a + b).forEach { assertTrue(u.feed(it, 1550f, 1f).isEmpty()) }
    }

    // ---- the grade

    @Test fun gradeUnder3PercentIsFlat() {
        assertNull(DynoCalc.usableSlope(null))
        assertNull(DynoCalc.usableSlope(2.9f))
        assertNull(DynoCalc.usableSlope(-2.9f))
        assertEquals(3f, DynoCalc.usableSlope(3f)!!, 0f)
        assertEquals(-4.5f, DynoCalc.usableSlope(-4.5f)!!, 0f)
        assertNull("past any road", DynoCalc.usableSlope(10.5f))
    }

    // ---- cells and holes

    @Test fun cellsAreAtTheirCentre() {
        val c = DynoCalc.cells((0 until 300).map { DynoCalc.Raw(3000 + it / 3, 100f, 200f) })
        assertEquals(3050, c.first().rpm)
        val w = DynoCalc.cells((0 until 60).map { DynoCalc.Raw(3000 + it * 50, 100f, 200f) })
        assertEquals(3125, w.first().rpm)
        // the same car by 100 and by 250 rpm: no false shift between the two curves
        val fine = DynoCalc.curve(pull(30, 8.0), 1550f, 1f)
        val coarse = DynoCalc.curve(pull(5, 8.0), 1550f, 1f)
        assertEquals(100, fine[1].rpm - fine[0].rpm)
        assertEquals(250, coarse[1].rpm - coarse[0].rpm)
        val d = DynoCalc.diffPct(fine, coarse)
        assertTrue(d.size >= 10)
        assertTrue("diff $d", d.drop(1).dropLast(1).all { abs(it.second) < 1f })
    }

    @Test fun aHoleOverTwoCellsEndsThePlateau() {
        fun c(vararg r: Int) = r.map { DynoPoint(it, 100f, 300f) }
        // two cells missing: the range goes on
        assertEquals(3000..3600, DynoCalc.plateau(c(3000, 3100, 3200, 3500, 3600)))
        // three cells missing: two ranges, the longer one taken
        assertEquals(3000..3200, DynoCalc.plateau(c(3000, 3100, 3200, 3600, 3700)))
    }

    // ---- the file

    @Test fun writeLeavesNoTempFile() {
        val dir = Files.createTempDirectory("dyno").toFile()
        DynoStore.write(dir, run(5000L), pull(30, 0.5), null)
        assertEquals(listOf("5000.txt"), dir.list()!!.toList())
        // an old temp file from a crash is not a run, and goes
        File(dir, "6000.tmp").writeText("dyno 1 6000 1550.0 1.0000\ns 1 3000 100.0 90.0 50.0\n")
        assertEquals(listOf(5000L), DynoStore.scan(dir, "[]").map { it.startMs })
        assertEquals(listOf("5000.txt"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    // ---- the demo

    @Test(timeout = 5000) fun demoPullInATallGearEnds() {
        // the air holds it under the top of the rev range: it must end anyway
        val s = Demo.dynoPull(1000L, 0, 24.5)
        assertTrue("samples ${s.size}", s.size in 100..2000)
        assertTrue(DynoCalc.curve(s, 1550f, 1f).size >= DynoCalc.MIN_CELLS)
    }

    @Test fun demoPullsShowAMovedPlateau() {
        val was = Demo.dynoPull(1000L, 0)
        val now = Demo.dynoPull(1000L, 250)
        assertTrue("rate", DynoCalc.windowMs(was) == 150L)
        val a = DynoCalc.curve(was, 1550f, 1f)
        val b = DynoCalc.curve(now, 1550f, 1f)
        assertTrue(a.size >= 10)
        val pa = DynoCalc.plateau(a)!!
        val pb = DynoCalc.plateau(b)!!
        assertEquals(250f, ((pb.first + pb.last) - (pa.first + pa.last)) / 2f, 150f)
        assertEquals(250f, (DynoCalc.dropAfter(b)!! - DynoCalc.dropAfter(a)!!).toFloat(), 150f)
    }
}
