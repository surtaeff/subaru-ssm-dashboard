package kg.forester.sg.service

import kg.forester.sg.data.Derived
import kg.forester.sg.data.KnockMap
import kg.forester.sg.data.KnockStats
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.EngineProfile
import kg.forester.sg.ssm.EngineProfiles
import kg.forester.sg.ssm.GasketMode
import kg.forester.sg.ssm.TcuSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Timing pulled further than this many degrees (FBKC, the live knock pull) is knock: the rules and every screen go by it. */
const val KNOCK_WARN_DEG = -2f

/** One sample for the rules: the engine, the transmission and what the guard worked out from them. */
data class RuleInput(
    val e: EcuSnapshot,
    val t: TcuSnapshot? = null,
    val now: Long = e.timeMs,
    /** Merged frames of the dyno's fast poll were left out before this one (see [DynoPoll]): a pull around them has holes. */
    val skippedMerged: Boolean = false,
    /** IAM 0…1, knock corrections in degrees (negative = timing pulled), from the guard's roles. */
    val iam: Float? = null,
    val fbkc: Float? = null,
    val flkc: Float? = null,
    /** The ROM's own IAM Initial (what IAM starts from after a reset) and Boost Cut IAM; null = not read. */
    val iamInitial: Float? = null,
    val iamBoostCut: Float? = null,
    /** Oil temperature, read or estimated by the guard. */
    val oilC: Float? = null,
    val turbo: Boolean = false,
    val targetBoostKpa: Float? = null,
    /** A sports ECU (Link on the CAN): no wastegate duty, IAM or factory idle to judge by. */
    val sportEcu: Boolean = false,
    /** SensorBox inputs with a role; all null when the box isn't there. */
    val box: BoxInput = BoxInput(),
    /** The radiator fan as the ECU switches it: the first speed relay, any of them; null = this ECU doesn't say. */
    val fanLow: Boolean? = null,
    val fan: Boolean? = null,
    /** The A/C compressor (or its switch) on; null = not reported. */
    val ac: Boolean? = null,
    /** The ROM's Knock Sum counters, cylinders 1…4; null = not read. */
    val knockSums: List<Float>? = null,
    /** The gear the ECU works out itself (some ROMs); null = none. */
    val ecuGear: Int? = null,
    /** An automatic gearbox: its shifts can fake knock, its converter slips on full throttle. */
    val automatic: Boolean = false,
    /** Engine displacement, litres; null = not known. */
    val displacementL: Float? = null,
    /** What is normal for this engine; [EngineProfiles.GENERIC] = not known. */
    val profile: EngineProfile = EngineProfiles.GENERIC,
    /** This trip's average consumption, l/100 km, once 20 km are behind; null = not yet. */
    val tripPer100: Float? = null,
    /** Another make over OBD-II known to have a turbo: this car's VIN ("" if none given) for [kg.forester.sg.data.BoostNorm]; else null. */
    val boostNormCar: String? = null,
)

/** SensorBox readings by role: fuel rail and oil pressure in bar (over the atmosphere), air before and after the intercooler, the pump's volts. */
data class BoxInput(
    val fuelBar: Float? = null,
    val oilBar: Float? = null,
    val iatInC: Float? = null,
    val iatOutC: Float? = null,
    val pumpV: Float? = null,
)

/** What the rules keep between drives: in the app's preferences, in memory for the tests. */
interface RuleStore {
    /** The last time the engine was seen running (saved about once a minute): how long the car has been parked. */
    var lastRunMs: Long
    /** IAM at the end of the last drive, NaN = unknown. */
    var lastIam: Float
    /** When an ECU reset (IAM relearning from its initial value) was noticed, 0 = none. */
    var iamResetMs: Long
    /** This car's usual peak boost on a full-throttle pull, kPa, 0 = not learned yet. */
    var boostBase: Float
    /** Another make's learned pulls ([kg.forester.sg.data.BoostNorm.format]), "" = nothing yet. */
    var boostNorm: String
    /** The A/F learning last seen on a warm engine, NaN = unknown: exactly 0 after it says the ECU was reset. */
    var lastAfLearn: Float
    /** Outside air by the intake after a long park, and when; 0 = never. */
    var ambientC: Float
    var ambientMs: Long
    /** [GearSense], [SpoolTrend] and the all-time [KnockMap] as JSON, "" = nothing yet. */
    var gears: String
    var spool: String
    var knockMap: String
    /** Drives with a sudden coolant rise on a steady cruise (see Analyzer.spike), and when the last was counted; 0 = never. */
    var spikeDrives: Int
    var spikeLastMs: Long
    /** The last refill, for «after the refill» in the knock warnings; null = unknown. */
    fun lastRefillMs(): Long? = null
    /** IAM at the start of the last drive before [timeMs] (the IAM log), null = unknown. */
    fun iamBefore(timeMs: Long): Float? = null
}

class MemoryRuleStore : RuleStore {
    override var lastRunMs = 0L
    override var lastIam = Float.NaN
    override var iamResetMs = 0L
    override var boostBase = 0f
    override var boostNorm = ""
    override var lastAfLearn = Float.NaN
    override var ambientC = 0f
    override var ambientMs = 0L
    override var gears = ""
    override var spool = ""
    override var knockMap = ""
    override var spikeDrives = 0
    override var spikeLastMs = 0L
    var refillMs: Long? = null
    /** IAM log for the tests: time to value. */
    val iamLog = ArrayList<Pair<Long, Float>>()
    override fun lastRefillMs() = refillMs
    override fun iamBefore(timeMs: Long) = iamLog.lastOrNull { it.first < timeMs }?.second
}

/** Where a finding goes: [Alerts] in the app, a list in the tests. */
fun interface RuleSink {
    fun raise(id: String, text: String, level: Alerts.Level, cooldownMs: Long)
}

/** Average values over half a minute of warm idle, for the comparison with the factory table. */
data class IdleSample(val timeMs: Long, val values: Map<String, Float>)

/**
 * The rules from docs/knowledge (engine-ej.md, transmission-awd-electrical.md): knock and IAM with the ECU reset
 * and the low-load noise kept out, the knock map, mixture trims, air, boost against its target and the spool point,
 * idle, cooling and the fans, the SensorBox's fuel and oil pressure, the intercooler, ATF and lock-up, gear slip,
 * front/rear tyres.
 * Each rule looks only at the situations where its numbers hold (warm engine, closed loop, warm idle, a pull),
 * so a normal car stays quiet. [features] are the switches in Settings → Предупреждения.
 */
class Analyzer(
    private val store: RuleStore,
    private val sink: RuleSink,
    private val features: (String) -> Boolean = { true },
    /** The knock map, the spool trend, the last pull, the gears: for «Откаты и наддув», when they change. */
    private val onDiag: (DiagState) -> Unit = {},
    private val onIdle: (IdleSample) -> Unit = {},
) {
    /** Milliseconds a condition has held; 0 when it doesn't. */
    private class Hold {
        private var since = Long.MIN_VALUE
        private var seen = Long.MIN_VALUE
        fun ms(cond: Boolean, now: Long): Long {
            // not looked at for a while (the engine was off, a rule skipped): the condition starts over
            if (seen != Long.MIN_VALUE && now - seen > 5000L) since = Long.MIN_VALUE
            seen = now
            if (!cond) { since = Long.MIN_VALUE; return 0L }
            if (since == Long.MIN_VALUE) since = now
            return now - since
        }
    }

    private var lastSampleMs = Long.MIN_VALUE
    private var runningSince = 0L
    private var lastRunSaved = 0L
    private var lastIamSeen: Float? = null
    private var transientUntil = 0L
    private var lastThrottle: Pair<Long, Float>? = null
    private var lastGear: Int? = null
    private val speeds = ArrayDeque<Pair<Long, Int>>()
    private var accel = 0f
    private var corrNonZeroMs = Long.MIN_VALUE
    private var corrEverNonZero = false

    // knock
    private var iamStart: Float? = null
    private var iamPrevTrip = Float.NaN
    private var flkcLearned = false
    private var knockEvidenceMs = Long.MIN_VALUE
    private var tripRefill: Long? = null
    private var fbkcEvents = 0
    private var fbkcInEvent = false
    // mixture
    private val trims = ArrayDeque<Pair<Long, Float>>()
    private var idleTrimS = 0.0; private var idleTrimSum = 0.0
    private var cruiseTrimS = 0.0; private var cruiseTrimSum = 0.0
    private var idleLeanDone = false
    // boost
    private val gauges = ArrayDeque<Pair<Long, Float>>()
    private var pullStart = 0L
    private var pullPeak = 0f
    private var pullMaxRpm = 0
    private var pullMaxWgdc = 0f
    private val pullLows = ArrayDeque<Boolean>()
    // idle
    private val idleRpm = ArrayDeque<Pair<Long, Int>>()
    private val idleSums = HashMap<String, Double>()
    private var idleSumS = 0.0
    // cooling
    private var jamHotPeak = 0
    private var jamMoveStart = 0L
    /** Coolant on the last minute: whether it was a real move (40+ km/h), and the air flow. */
    private class CoolSample(val t: Long, val c: Int, val moving: Boolean, val maf: Float?)
    private val coolWin = ArrayDeque<CoolSample>()
    private var warmSince = 0L
    private var spikeThisDrive = false
    // ATF
    private var lastAtf: Pair<Long, Int>? = null
    /** Times the ATF reading leapt by more than 20° in a second: one is a misread byte, several are a sensor. */
    private val atfJumps = ArrayDeque<Long>()
    private var atfImplausibleUntil = 0L
    private var g3Start = 0L
    private var g3Min = Int.MAX_VALUE
    private var g3Max = 0
    // tyres
    private var tyreS = 0.0; private var tyreFront = 0.0; private var tyreRear = 0.0
    private var tyresDone = false

    // gears and shifts
    private var gearSense = GearSense.fromJson(store.gears)
    private var gearsSavedMs = 0L
    /** When gear changes were seen (TCU, ECU, or the ratio jumping), the last few seconds. */
    private val shifts = ArrayDeque<Long>()
    private var lastRatio: Pair<Long, Float>? = null
    private var lastEcuGear: Int? = null
    private var lastTcuGear: Pair<Long, Int>? = null
    private var closingUntil = 0L
    private val slipWin = ArrayDeque<Pair<Long, Float>>()
    // knock map
    private class MapPull(val startMs: Long, var endMs: Long, var deg: Float, var rpm: Int, var load: Float, val valid: Boolean, var open: Boolean = true)
    private var tripKnock = KnockMap()
    private var allKnock = KnockMap.fromJson(store.knockMap)
    private var mapPull: MapPull? = null
    private var mapSavedMs = 0L
    private var mapDirty = false
    private var shiftPulls = 0
    private var shiftRpmMin = Int.MAX_VALUE; private var shiftRpmMax = 0
    // knock sums per cylinder, IAM after the refill
    private var ksLast: List<Float>? = null
    private val ksCount = IntArray(4)
    private var ksRead = false
    private var iamFuelSaid = false
    // the A/F sensor, closed loop after an ECU reset
    private var lambdaMoved = false
    private var lambdaFixedS = 0.0
    private var lambdaThrMin = 100f; private var lambdaThrMax = 0f
    private var noWideband = false
    private var learnResetMs = 0L
    private var learnSavedMs = 0L
    private val swing = ArrayDeque<Triple<Long, Float, Float>>()
    private var cruiseLearnS = 0.0; private var cruiseLearnSum = 0.0
    // boost: the pull's target, spool and wobble
    private var spool = SpoolTrend.fromJson(store.spool)
    private var boostNorm = kg.forester.sg.data.BoostNorm.parse(store.boostNorm)
    private var lastPull: PullSummary? = null
    private var pullStartRpm = 0; private var pullStartGauge = 0f
    private var pullGear: Int? = null; private var pullGearMixed = false
    private var pullSpoolRpm: Int? = null
    private var pullHoles = false
    private var pullTargetAtPeak: Float? = null
    private var pullMaxErr: Float? = null
    private var prevBoost: Pair<Int, Float>? = null
    private var overboostAt = 0L
    private var floatDir = 0; private var floatExt = 0f; private var floatTurns = 0
    private var floatMin = Float.MAX_VALUE; private var floatMax = -Float.MAX_VALUE
    private var icSum = 0.0; private var icN = 0
    // SensorBox fuel pressure, cooling trend in traffic
    private val fuelDp = ArrayDeque<Pair<Long, Float>>()
    private val trafficEct = ArrayDeque<Pair<Long, Int>>()
    private var diagDirty = false
    private var diagSentMs = 0L

    private val idleHold = Hold(); private val cruiseHold = Hold(); private val wotHold = Hold()
    private val belowMaxHold = Hold(); private val iamLowHold = Hold(); private val iamDropHold = Hold(); private val leanBoostHold = Hold(); private val learnHold = Hold(); private val railHold = Hold()
    private val frozenHold = Hold(); private val atmHold = Hold(); private val mapHold = Hold(); private val overboostHold = Hold()
    private val cutHold = Hold(); private val wgdcHold = Hold(); private val dutyHold = Hold(); private val idleHighHold = Hold()
    private val idleLowHold = Hold(); private val avcsHold = Hold(); private val warm102Hold = Hold(); private val speedHold = Hold()
    private val atfBadHold = Hold(); private val atf120Hold = Hold(); private val atf110Hold = Hold(); private val atf100Hold = Hold()
    private val atf105Hold = Hold(); private val lampHold = Hold(); private val lockHold = Hold(); private val jamHold = Hold()
    private val idleMafHold = Hold(); private val swingHold = Hold(); private val resetLoopHold = Hold(); private val underTargetHold = Hold()
    private val springHold = Hold(); private val slipHold = Hold(); private val soakHold = Hold(); private val dpHighHold = Hold()
    private val dpLowHold = Hold(); private val pulseHold = Hold(); private val oilRevHold = Hold(); private val trafficHold = Hold()
    private val fanOnHold = Hold()

    private fun on(f: String) = features(f)

    /** Set from the screen (another thread): the all-time knock map starts over on the next sample. */
    @Volatile private var clearMap = false
    fun clearKnockMap() { clearMap = true }

    /**
     * Forget the drive in progress (demo switched on or off, another car): the next sample starts a new drive, and
     * what is learned (gears, spool point, the knock map) is read again from the store, the demo's or this car's.
     */
    fun reset() {
        lastSampleMs = Long.MIN_VALUE; runningSince = 0L; lastIamSeen = null
        gearSense = GearSense.fromJson(store.gears); spool = SpoolTrend.fromJson(store.spool); allKnock = KnockMap.fromJson(store.knockMap)
        boostNorm = kg.forester.sg.data.BoostNorm.parse(store.boostNorm)
        lastPull = null; mapPull = null; diagDirty = true
    }
    private fun say(id: String, text: String, level: Alerts.Level, cooldownMs: Long) = sink.raise(id, text, level, cooldownMs)

    /** The current sample is from a sports ECU (see [RuleInput.sportEcu]); the car has an automatic. */
    private var sport = false
    private var automatic = false

    fun process(x: RuleInput) {
        sport = x.sportEcu
        automatic = x.automatic
        if (clearMap) { clearMap = false; allKnock = KnockMap(x.now); store.knockMap = ""; mapDirty = false; diagDirty = true }
        val e = x.e
        val now = x.now
        val rpm = e.rpm ?: 0
        val running = rpm > 400
        val speed = e.speedKmh ?: 0
        val thr = e.throttlePct ?: e.pedalPct
        val c = e.coolantC
        val load = e.loadGRev
        val gauge = e.gaugeKpa

        // a gap in the samples = the ignition was off: a new key-on
        val keyOn = lastSampleMs == Long.MIN_VALUE || now - lastSampleMs > 60_000L
        val dt = if (keyOn) 0f else ((now - lastSampleMs) / 1000f).coerceIn(0f, 2f)
        lastSampleMs = now
        if (keyOn) {
            // the last drive may have ended with the ECU just going silent: keep its IAM all the same
            lastIamSeen?.let { store.lastIam = it }
            keyOnChecks(e, now)
            newDrive(e, now)
        }

        if (!running) {
            if (runningSince != 0L) endDrive(now)
            runningSince = 0L
        } else {
            if (runningSince == 0L) runningSince = now
            if (now - lastRunSaved > 60_000L) {
                lastRunSaved = now; store.lastRunMs = now
                lastIamSeen?.let { store.lastIam = it }
            }
        }
        val runMs = if (runningSince == 0L) 0L else now - runningSince

        // --- contexts
        // warm: 75° and the engine running five minutes this time (not since some earlier drive)
        val warm = running && c != null && c >= 75 && runMs >= 5 * 60_000L
        if (thr != null) {
            lastThrottle?.let { (t0, v0) ->
                val s = (now - t0) / 1000f
                if (s in 0.05f..2f && abs(thr - v0) / s > 30f) transientUntil = now + 1500
                if (s in 0.05f..2f && (v0 - thr) / s > 30f) closingUntil = now + 1500
            }
            lastThrottle = now to thr
        }
        val gear = x.t?.gear
        if (gear != null && lastGear != null && gear != lastGear) transientUntil = now + 1500
        lastGear = gear
        // acceleration over about two seconds: the ECU's whole km/h flicker (79, 80, 79) is not a change of pace
        speeds.addLast(now to speed)
        while (speeds.size > 2 && now - speeds.first().first > 2500L) speeds.removeFirst()
        speeds.first().let { (t0, v0) -> val s = (now - t0) / 1000f; accel = if (s >= 1f) (speed - v0) / s else 0f }
        val transient = now < transientUntil
        val decel = thr != null && thr < 1f && rpm > 1300 && speed > 10
        if (e.afCorrPct != null && e.afCorrPct != 0f) { corrNonZeroMs = now; corrEverNonZero = true }
        val closedLoop = warm && thr != null && thr < 60f && (e.mafGs ?: 0f) < 50f && (e.injPwMs ?: 1f) > 0.5f && !decel &&
            corrNonZeroMs != Long.MIN_VALUE && now - corrNonZeroMs < 5000
        val idleNow = running && speed == 0 && (thr ?: 0f) < 3f && rpm < 1200
        val idleMs = idleHold.ms(idleNow, now)
        val hotIdle = warm && c != null && c >= 80 && idleMs >= 20_000L
        val cruiseMs = cruiseHold.ms(speed >= 40 && abs(accel) < 1f && thr != null && thr in 5f..40f, now)
        val cruise = warm && cruiseMs >= 10_000L
        val wot = (thr ?: 0f) >= 90f
        val wotMs = wotHold.ms(wot, now)
        val pull = wotMs >= 1000L

        if (running) {
            val gearNow = gears(x, now, rpm, speed, thr)
            if (now < shiftUntil()) transientUntil = maxOf(transientUntil, now + 1000)
            knock(x, now, warm, transient, pull, load, rpm, thr, gauge)
            if (on("knock")) { knockMap(x, now, rpm, load, decel); knockSums(x) }
            if (on("mixture")) {
                mixture(e, now, dt, warm, closedLoop, hotIdle, cruise, speed, thr)
                sensorAndReset(x, now, dt, warm, runMs, speed, thr, closedLoop, cruise, rpm)
            }
            air(e, now, hotIdle, x.displacementL, rpm)
            if (x.turbo && on("boost")) boost(x, now, rpm, thr, gauge, wot, c, gearNow)
            intake(x, now, gearNow, wotMs, speed)
            if (on("idle")) idle(e, now, hotIdle, rpm)
            if (on("cooling") && c != null) cooling(x, now, c, speed, runMs)
            if (on("warmup")) cold(x, rpm, load, gauge)
            boxRules(x, now, rpm, gauge, warm, idleNow && idleMs >= 10_000L, runMs)
            if (on("atf")) slip(x, now, gearNow, rpm, speed, wotMs)
        }
        if (hotIdle) collectIdle(e, x, now, dt) else { idleSums.clear(); idleSumS = 0.0 }
        x.t?.let { t ->
            if (on("atf")) atf(t, now, speed, rpm, running)
            if (on("awd")) tyres(t, now, dt, speed, thr)
        }
        if (runMs > 0) lastIamSeen = x.iam ?: lastIamSeen
        // the A/F learning of a warm engine, kept for spotting the next ECU reset (it comes back exactly 0)
        e.afLearnPct?.let { if (warm && it != 0f && now - learnSavedMs > 60_000L) { learnSavedMs = now; store.lastAfLearn = it } }
        if (diagDirty && now - diagSentMs >= 1000L) publish(now)
    }

    private fun newDrive(e: EcuSnapshot, now: Long) {
        iamStart = null; flkcLearned = false; fbkcEvents = 0; fbkcInEvent = false
        trims.clear(); idleTrimS = 0.0; idleTrimSum = 0.0; cruiseTrimS = 0.0; cruiseTrimSum = 0.0; idleLeanDone = false
        tyreS = 0.0; tyreFront = 0.0; tyreRear = 0.0; tyresDone = false
        jamHotPeak = 0; jamMoveStart = 0L; coolWin.clear(); warmSince = 0L; spikeThisDrive = false; atfJumps.clear(); lastAtf = null; g3Start = 0L; pullStart = 0L
        tripRefill = store.lastRefillMs()
        tripKnock = KnockMap(now); mapPull = null; shiftPulls = 0; shiftRpmMin = Int.MAX_VALUE; shiftRpmMax = 0
        ksLast = null; ksCount.fill(0); ksRead = false; iamFuelSaid = false
        lambdaMoved = false; lambdaFixedS = 0.0; lambdaThrMin = 100f; lambdaThrMax = 0f; noWideband = false
        swing.clear(); cruiseLearnS = 0.0; cruiseLearnSum = 0.0; fuelDp.clear(); trafficEct.clear(); shifts.clear()
        // the A/F learning exactly 0 where it was something before: the ECU was reset (battery off, reflash)
        val before = store.lastAfLearn
        learnResetMs = if (e.afLearnPct == 0f && !before.isNaN() && abs(before) >= 1f) now else 0L
        diagDirty = true
    }

    private fun endDrive(now: Long) {
        lastIamSeen?.let { store.lastIam = it }
        store.lastRunMs = now
        mapPull?.let { if (!it.open) commit(it) }
        mapPull = null
        if (mapDirty) { mapDirty = false; mapSavedMs = now; store.knockMap = allKnock.toJson() }
        store.gears = gearSense.toJson()
    }

    /** What «Откаты и наддув» shows, sent at most once a second and only after a change. */
    private fun publish(now: Long) {
        diagDirty = false; diagSentMs = now
        val gears = if (gearSense.wot.isNotEmpty()) gearSense.wot.toSortedMap().values.filter { it.n >= 20 }.map { it.ratio } else gearSense.gears()
        onDiag(DiagState(tripKnock, allKnock, if (ksRead) ksCount.toList() else null, spool, lastPull, gears, ambient(now), noWideband))
    }

    /** Outside air by the intake at the start after a long park, for the next 12 hours; null = not known. */
    private fun ambient(now: Long) = store.ambientC.takeIf { store.ambientMs != 0L && now - store.ambientMs in 0..12 * 3600_000L }

    // ---------------------------------------------------------------- gears and shifts (transmission C)

    private fun shift(t: Long) {
        shifts.addLast(t)
        while (shifts.size > 8) shifts.removeFirst()
    }

    /** A shift was seen just now (the TCU's gear comes up to a second late): treat the moment as a transient. */
    private fun shiftUntil() = (shifts.lastOrNull() ?: Long.MIN_VALUE / 2) + 1000L

    /** The gear now: the TCU's, the ECU's own, or (manual) by the learned ratios; and the shifts on the way. */
    private fun gears(x: RuleInput, now: Long, rpm: Int, speed: Int, thr: Float?): Int? {
        val ratio = if (speed >= 10 && rpm > 700) rpm / speed.toFloat() else null
        val t = x.t
        val tg = t?.gear
        if (t != null && tg != null) {
            lastTcuGear?.let { (t0, g0) -> if (g0 != tg && t.timeMs != t0) shift(t.timeMs) }
            lastTcuGear = t.timeMs to tg
        }
        x.ecuGear?.let { g -> if (lastEcuGear != null && g != lastEcuGear && g > 0) shift(now); lastEcuGear = g }
        // the ratio leaping between two samples is a shift too (an automatic with no TCU data); at 20 km/h and up,
        // where the whole km/h steps of the speed are small against it
        val r0 = lastRatio
        if (ratio != null && speed >= 20 && r0 != null && now - r0.first <= 700L && abs(ratio / r0.second - 1f) >= 0.12f) shift((now + r0.first) / 2)
        lastRatio = ratio?.let { now to it }
        // a manual on the move with the clutch in: the steady ratios are its gears
        if (!x.automatic && ratio != null && speed >= 15 && rpm >= 1100 && abs(accel) < 1.5f && (thr ?: 0f) >= 3f) gearSense.learn(ratio)
        if (now - gearsSavedMs > 60_000L) { gearsSavedMs = now; store.gears = gearSense.toJson() }
        return tg?.takeIf { it > 0 } ?: x.ecuGear?.takeIf { it > 0 } ?: if (!x.automatic && ratio != null) gearSense.gearOf(ratio) else null
    }

    // ---------------------------------------------------------------- knock and IAM (engine-ej K01…K13)

    /** After an ECU reset IAM starts low (often 0.5) and climbs as the ECU relearns: not a knock problem. */
    private fun afterReset(iam: Float?, now: Long): Boolean {
        val since = store.iamResetMs
        if (since == 0L) return false
        if ((iam != null && iam >= 0.95f) || now - since > 30L * 24 * 3600_000L) { store.iamResetMs = 0L; return false }
        return true
    }

    private fun knock(x: RuleInput, now: Long, warm: Boolean, transient: Boolean, pull: Boolean, load: Float?, rpm: Int, thr: Float?, gauge: Float?) {
        if (!on("knock")) return
        val iam = x.iam
        // where IAM starts after a reset: the ROM's own value when it is read, else the typical one (11/16)
        val initial = x.iamInitial ?: 0.70f
        if (iam != null) {
            if (iamStart == null) {
                iamStart = iam
                // lower than at the end of the last drive: IAM never drops with the engine off, so the ECU was reset
                val before = store.lastIam
                iamPrevTrip = before
                if (iam <= 0.8f && ((!before.isNaN() && before - iam >= 0.2f) || x.e.afLearnPct == 0f)) store.iamResetMs = now
            } else if (iamStart!! <= initial && iam - iamStart!! >= 0.05f && store.iamResetMs == 0L) {
                store.iamResetMs = now // climbing from an initial-looking value during the drive: the ECU relearns after a reset
            } else if (iam < iamStart!! - 0.04f && store.iamResetMs != 0L) {
                store.iamResetMs = 0L // falling, not relearning: knock again, the low-IAM warnings apply
            }
        }
        val reset = afterReset(iam, now)
        if (iam != null && reset) {
            say("iamRelearn", "После сброса ЭБУ заново подбирает угол зажигания, IAM сейчас %.2f. Это нормально. ".format(iam) +
                kg.forester.sg.data.DistanceUnits.prose("Первые 50–100 км поездите спокойно, без полного газа на холодном моторе"), Alerts.Level.INFO, 24 * 3600_000L)
        }
        // knock counts only where it means something: warm, not a light-load tap, not a throttle stab or a shift
        val c = x.e.coolantC ?: 0
        val loaded = if (load != null) !(load < 0.75f && rpm < 3000) else (thr ?: 0f) >= 40f || rpm >= 3000
        val valid = warm && c >= 60 && loaded && !transient
        if (valid && ((x.fbkc ?: 0f) < KNOCK_WARN_DEG || (x.flkc ?: 0f) <= -1.4f)) knockEvidenceMs = now
        if ((x.flkc ?: 0f) != 0f) flkcLearned = true
        if (iam != null && warm && !reset) {
            val start = iamStart ?: iam
            // the trip began at the initial value, and not after a higher one, with no FLKC learned since: the ECU is
            // relearning (or never learned), not pulling timing for knock; knock seen overrides it
            val parked = start <= initial && iamPrevTrip.let { it.isNaN() || it <= initial } && !flkcLearned
            // low IAM is a danger only with knock behind it, seen in the last ten minutes
            val knocking = now - knockEvidenceMs in 0..10 * 60_000L
            // lower than before the last refill (within three days of it): the new fuel is worse, or the ROM wants a
            // higher octane than the pump gave (engine-ej K15)
            val refill = tripRefill?.takeIf { now - it in 0..3 * 24 * 3600_000L }
            val before = refill?.let { store.iamBefore(it) }
            if (refill != null && before != null && before - iam >= 0.1f && !iamFuelSaid) {
                iamFuelSaid = true
                say("iamFuel", "IAM упал после заправки ${SimpleDateFormat("dd.MM в HH:mm", Locale("ru")).format(Date(refill))}: был %.2f, сейчас %.2f. ".format(before, iam) +
                    "Этот бензин хуже прошлого, или прошивка настроена под бензин с большим октановым числом. Не давите в пол до заправки на проверенной АЗС",
                    Alerts.Level.WARN, 24 * 3600_000L)
            }
            // a few seconds, not one sample: a glitch of the reading is not a drop
            val lowHeld = iamLowHold.ms(iam < 0.75f && (!parked || knocking), now) >= 5000L
            val dropHeld = iamDropHold.ms(start - iam >= 0.1f, now) >= 5000L
            when {
                lowHeld -> if (knocking) {
                    say("iam", "IAM упал до %.2f, и мотор недавно детонировал: ЭБУ убирает угол зажигания. ".format(iam) +
                        "Не давите газ в пол и не грузите мотор, пока не заправитесь хорошим бензином", if (x.turbo) Alerts.Level.DANGER else Alerts.Level.WARN, 30 * 60_000L)
                } else {
                    say("iam", "IAM держится на %.2f, это ниже нормы: ЭБУ когда-то слышал детонацию и убавил угол. ".format(iam) +
                        (if (x.fbkc == 0f && x.flkc == 0f) "Откатов сейчас нет. " else "") +
                        "Заправьтесь хорошим бензином и понаблюдайте", Alerts.Level.WARN, 30 * 60_000L)
                }
                dropHeld && !iamFuelSaid -> {
                    val fuel = tripRefill?.let { now - it < 2 * 24 * 3600_000L } == true
                    say("iamDrop", "IAM снизился с %.2f до %.2f за поездку: была детонация.".format(start, iam) +
                        if (fuel) " Вы недавно заправлялись, скорее всего дело в бензине" else " Проверьте бензин", Alerts.Level.WARN, 30 * 60_000L)
                }
            }
            if (belowMaxHold.ms(iam < 0.95f && (!parked || knocking), now) >= 10 * 60_000L) {
                say("iamBelow", "IAM %.2f, ниже максимума: ЭБУ раньше слышал детонацию и убавил угол в таблице опережения от детонации. ".format(iam) +
                    "Заметно это мало: при %.2f на клетке в 6° это около %.1f°. Чаще всего помогает хороший бензин".format(iam, 6f * (1f - iam)), Alerts.Level.INFO, 24 * 3600_000L)
            }
        } else belowMaxHold.ms(false, now)

        val heavy = load?.let { it >= 1.0f } ?: ((thr ?: 0f) >= 60f)
        val hot = x.e.intakeC?.takeIf { it >= if (x.turbo) 50 else 55 }
        val hotNote = hot?.let { " Воздух во впуске очень горячий ($it°), от этого мотор склонен к детонации. Дайте машине проехать спокойно, чтобы впуск остыл" } ?: ""
        val flkc = x.flkc
        if (flkc != null && valid) {
            when {
                flkc <= -4f && heavy -> say("flkc", "Мотор устойчиво детонирует под нагрузкой: ЭБУ выучил поправку %.1f°, дальше он сбросит IAM. ".format(flkc) +
                    "Снизьте нагрузку и заправьтесь хорошим бензином. Если повторится, нужна диагностика.$hotNote", Alerts.Level.DANGER, 30 * 60_000L)
                flkc <= -2.8f -> say("flkc", "Выученная коррекция по детонации %.1f°: мотор стабильно детонирует на этом режиме. Проверьте бензин.$hotNote".format(flkc),
                    Alerts.Level.WARN, 30 * 60_000L)
                flkc <= -1.4f && heavy -> say("flkcStep", "ЭБУ уже снимал угол на этом режиме (%.1f°). Не крутите мотор в натяг на этой передаче и присмотритесь к бензину.$hotNote".format(flkc),
                    Alerts.Level.INFO, 6 * 3600_000L)
            }
        }
        val fbkc = x.fbkc
        if (fbkc != null && valid) {
            val boosted = (gauge ?: 0f) > 20f
            if (boosted && fbkc <= -4f) {
                if (!fbkcInEvent) { fbkcInEvent = true; fbkcEvents++ }
                val bad = fbkc <= -6f || fbkcEvents >= 3
                say("fbkc", "Сильный откат зажигания %.1f° под наддувом".format(fbkc) +
                    (if (bad) ", и не первый раз за поездку. Не разгоняйтесь в пол до проверки бензина и смеси" else "") + ".$hotNote",
                    if (bad) Alerts.Level.DANGER else Alerts.Level.WARN, if (bad) 10 * 60_000L else 5 * 60_000L)
            } else if (pull && heavy && fbkc <= KNOCK_WARN_DEG) {
                say("fbkcPull", "Во время разгона мотор детонировал, ЭБУ убрал %.1f°. Если повторяется на тех же оборотах, ".format(fbkc) +
                    "не разгоняйтесь в пол до проверки бензина и смеси.$hotNote", Alerts.Level.WARN, 10 * 60_000L)
            }
        }
        if ((fbkc ?: 0f) > KNOCK_WARN_DEG) fbkcInEvent = false

        // lean under boost: the stock sensor is poor there and reads rich at worst, so lean is worth a warning
        val lean = x.turbo && (gauge ?: 0f) > 50f && (thr ?: 0f) >= 90f && (x.e.lambda ?: 0f) > 0.85f
        if (leanBoostHold.ms(lean, now) >= 1000L) {
            say("leanBoost", "Под наддувом смесь бедная (AFR %.1f). Это опасно для поршней. Не давите газ в пол до проверки топливной системы".format(x.e.afr ?: 0f),
                Alerts.Level.DANGER, 10 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- knock map, knock at a shift, per cylinder (K16…K18)

    /**
     * Timing pulls into the map, the way KnockWatch counts them (any pull of 0.3° or more, flicker within 3 s is
     * one): placed at their deepest point, kept out on a lifted foot, at light load below 3000 rpm, on a cold engine.
     */
    private fun knockMap(x: RuleInput, now: Long, rpm: Int, load: Float?, decel: Boolean) {
        val k = x.e.knockDeg ?: x.fbkc ?: return
        mapPull?.let { if (!it.open && now - it.endMs > KnockStats.MERGE_MS) { commit(it); mapPull = null } }
        val p = mapPull
        if (k <= KnockStats.START_DEG) {
            if (p == null) {
                val lowLoad = load == null || (load < 0.75f && rpm < 3000)
                val valid = (x.e.coolantC ?: 0) >= 60 && !decel && now >= closingUntil && !lowLoad
                mapPull = MapPull(now, now, k, rpm, load ?: 0f, valid)
            } else {
                p.open = true
                if (k < p.deg) { p.deg = k; p.rpm = rpm; p.load = load ?: p.load }
            }
        } else if (p != null && p.open && k >= 0f) { p.open = false; p.endMs = now }
    }

    /** A finished pull into the trip's and the all-time map; at an automatic's shift it is counted apart. */
    private fun commit(p: MapPull) {
        if (!p.valid) return
        // the TCU's gear comes with its poll, up to ~0.7 s off the moment: «within half a second» with that margin
        val atShift = automatic && shifts.any { abs(it - p.startMs) <= 700L }
        tripKnock = tripKnock.add(p.rpm, p.load, p.deg, atShift)
        allKnock = allKnock.add(p.rpm, p.load, p.deg, atShift)
        mapDirty = true; diagDirty = true
        if (p.endMs - mapSavedMs > 30_000L) { mapDirty = false; mapSavedMs = p.endMs; store.knockMap = allKnock.toJson() }
        if (!atShift) return
        shiftPulls++
        shiftRpmMin = minOf(shiftRpmMin, p.rpm); shiftRpmMax = maxOf(shiftRpmMax, p.rpm)
        if (shiftPulls >= 3) {
            say("atShiftKnock", "Откаты зажигания совпадают с переключениями АКПП ($shiftPulls раз за поездку, $shiftRpmMin–$shiftRpmMax об/мин). " +
                "Вероятно, это ложный детон от переключения: датчик слышит щелчки гидроблока, или ЭБУ сам убирает угол на время переключения. " +
                "На карте откатов они отмечены отдельно", Alerts.Level.INFO, 24 * 3600_000L)
        }
    }

    /** The ROM's Knock Sum per cylinder: the rises counted for the drive, one cylinder well ahead of the others named. */
    private fun knockSums(x: RuleInput) {
        val s = x.knockSums?.takeIf { it.size == 4 } ?: return
        ksRead = true
        val last = ksLast
        var grew = false
        if (last != null) for (i in 0 until 4) {
            val d = s[i] - last[i]
            // a counter: an ECU reset clears it, a byte wraps; only a plausible rise counts
            if (d > 0f && d < 50f) { ksCount[i] += d.toInt(); grew = true }
        }
        ksLast = s
        if (!grew) return
        diagDirty = true
        val total = ksCount.sum()
        val top = ksCount.indices.maxByOrNull { ksCount[it] } ?: return
        if (total >= 8 && ksCount[top] >= 0.6f * total) {
            say("knockCyl", "Детонация чаще всего в цилиндре ${top + 1}: ${ksCount[top]} из $total срабатываний за поездку. " +
                "Если так повторяется, проверьте этот цилиндр: свечу, катушку, компрессию", Alerts.Level.INFO, 24 * 3600_000L)
        }
    }

    // ---------------------------------------------------------------- cold engine (O01, B07)

    private fun cold(x: RuleInput, rpm: Int, load: Float?, gauge: Float?) {
        // warm at 80° coolant: that is where every EJ ends its warm-up enrichment (the oil is only an estimate)
        val c = x.e.coolantC ?: return
        if (c >= 80) return
        if (x.turbo && (gauge ?: 0f) > 30f) {
            say("coldBoost", "Мотор холодный, а вы уже под наддувом. Холодное масло хуже защищает вкладыши и турбину, подождите прогрева",
                Alerts.Level.WARN, 5 * 60_000L)
        } else if (rpm > 3600 || (load ?: 0f) > 1.0f) {
            say("cold", "Мотор не прогрет: антифриз $c°, прогрет он с 80. Пока не выше 3000 оборотов и без полного газа", Alerts.Level.WARN, 2 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- mixture (F01…F08)

    private fun mixture(e: EcuSnapshot, now: Long, dt: Float, warm: Boolean, closedLoop: Boolean, hotIdle: Boolean, cruise: Boolean, speed: Int, thr: Float?) {
        val corr = e.afCorrPct
        val learn = e.afLearnPct
        val total = if (corr != null && learn != null) corr + learn else null
        if (total != null && closedLoop) {
            trims.addLast(now to total)
            while (trims.isNotEmpty() && now - trims.first().first > 120_000L) trims.removeFirst()
            if (trims.size >= 30 && now - trims.first().first >= 60_000L) {
                val mean = trims.sumOf { it.second.toDouble() } / trims.size
                when {
                    mean >= 25 -> say("lean", "Смесь сильно бедная: ЭБУ добавляет %.0f%% топлива. Проверьте, не слетела ли гофра впуска, ".format(mean) +
                        "шланги и хомуты. Не нагружайте мотор до проверки", Alerts.Level.DANGER, 30 * 60_000L)
                    mean >= 15 -> say("lean", "ЭБУ постоянно добавляет топливо (%.0f%%): в мотор попадает лишний воздух или не хватает бензина. ".format(mean) +
                        "Проверьте гофру между датчиком расхода воздуха и дросселем, шланги вентиляции картера, почистите датчик расхода воздуха", Alerts.Level.WARN, 30 * 60_000L)
                    mean <= -25 -> say("rich", "Смесь сильно богатая: ЭБУ убирает %.0f%% топлива. Растёт расход и страдает катализатор, нужна проверка".format(-mean),
                        Alerts.Level.DANGER, 30 * 60_000L)
                    mean <= -15 -> say("rich", "ЭБУ постоянно убирает топливо (%.0f%%), смесь слишком богатая. ".format(-mean) +
                        "Частые причины: клапан продувки бензобака, подтекающая форсунка, высокое давление топлива", Alerts.Level.WARN, 30 * 60_000L)
                }
            }
        }
        val learnMs = learnHold.ms(warm && learn != null && abs(learn) >= 14.5f, now)
        if (learn != null && learnMs >= 30_000L) {
            if (learn > 0) say("lean", "Обучение смеси упёрлось в +%.0f%%: ЭБУ больше не может компенсировать нехватку топлива. ".format(learn) +
                "Ищите подсос воздуха после датчика расхода или слабый бензонасос", Alerts.Level.WARN, 30 * 60_000L)
            else say("rich", "Обучение смеси упёрлось в %.0f%%: смесь слишком богатая. Проверьте клапан продувки бензобака и форсунки".format(learn),
                Alerts.Level.WARN, 30 * 60_000L)
        }
        // lean only at idle = a vacuum leak (it shows most at small air flow)
        if (total != null && closedLoop) {
            if (hotIdle) { idleTrimS += dt; idleTrimSum += total * dt }
            if (cruise) { cruiseTrimS += dt; cruiseTrimSum += total * dt }
            if (!idleLeanDone && idleTrimS >= 60 && cruiseTrimS >= 60) {
                idleLeanDone = true
                val diff = idleTrimSum / idleTrimS - cruiseTrimSum / cruiseTrimS
                if (diff >= 8) say("idleLean", "На холостых ЭБУ добавляет заметно больше топлива, чем в движении (на %.0f%%). ".format(diff) +
                    "Так обычно проявляется подсос воздуха: трещина в гофре впуска или шланге", Alerts.Level.WARN, 6 * 3600_000L)
            }
        }
        if (corr != null && railHold.ms(closedLoop && abs(corr) >= 24f, now) >= 10_000L) {
            say("afRail", "Смесь резко ушла из нормы прямо сейчас (коррекция %.0f%%). Если мотор троит или глохнет, проверьте, не слетела ли гофра воздушного фильтра".format(corr),
                Alerts.Level.WARN, 15 * 60_000L)
        }
        // the correction stuck at exactly 0 while driving: the ECU ignores the A/F sensor (its heater, usually)
        // (right after an ECU reset that is normal for a while: see sensorAndReset)
        val frozen = corrEverNonZero && corr == 0f && warm && speed >= 20 && thr != null && thr in 5f..40f && !freshReset(now)
        if (frozenHold.ms(frozen, now) >= 60_000L) {
            say("afFrozen", "ЭБУ не использует датчик состава смеси и работает вслепую. Обычно это неисправность датчика или его подогрева, проверьте ошибки. Расход может вырасти",
                Alerts.Level.WARN, 3 * 3600_000L)
        }
        val duty = e.injDutyPct
        val dutyMs = dutyHold.ms((duty ?: 0f) >= 85f, now)
        if (duty != null && dutyMs >= 1000L) {
            say("injDuty", "Форсунки работают почти без пауз (%.0f%%). Запаса по топливу больше нет, это опасно на полном газу".format(duty),
                if (duty >= 95f) Alerts.Level.DANGER else Alerts.Level.WARN, 10 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- A/F sensor, trims after a reset, MAF reading high (F11…F14)

    /** An ECU reset seen in the last 40 minutes: the A/F learning back at exactly 0, or IAM relearning. */
    private fun freshReset(now: Long) = (learnResetMs != 0L && now - learnResetMs < 40 * 60_000L) ||
        (store.iamResetMs != 0L && now - store.iamResetMs < 40 * 60_000L)

    private fun sensorAndReset(x: RuleInput, now: Long, dt: Float, warm: Boolean, runMs: Long, speed: Int, thr: Float?, closedLoop: Boolean, cruise: Boolean, rpm: Int) {
        val e = x.e
        // λ exactly 1.0 through a varied drive (cruise, a lifted foot and full throttle all the same): the ECU has no
        // A/F sensor and answers a constant; «нет ШДК» is shown instead of a fake 14.7
        val l = e.lambda
        if (l != null && !lambdaMoved) {
            if (abs(l - 1f) > 0.003f) { lambdaMoved = true; if (noWideband) { noWideband = false; diagDirty = true } }
            else if (speed >= 20 && thr != null && runMs > 0) {
                lambdaFixedS += dt; lambdaThrMin = minOf(lambdaThrMin, thr); lambdaThrMax = maxOf(lambdaThrMax, thr)
                if (!noWideband && lambdaFixedS >= 120 && lambdaThrMax - lambdaThrMin >= 20f) { noWideband = true; diagDirty = true }
            }
        }
        // after an ECU reset the closed loop comes on late (two minutes to twenty by owners' logs) and the learning
        // starts from zero: said once, nothing alarmed (afFrozen keeps quiet meanwhile)
        val corr = e.afCorrPct
        if (resetLoopHold.ms(freshReset(now) && warm && corr == 0f && speed >= 20, now) >= 60_000L) {
            say("resetLoop", "После сброса ЭБУ коррекция смеси включается не сразу, от пары минут до 20 минут езды, а обучение набирается первые ${kg.forester.sg.data.DistanceUnits.distanceText(50.0)}. " +
                "Пока ЭБУ держит смесь по своим таблицам, это нормально", Alerts.Level.INFO, 24 * 3600_000L)
        }
        // trims swinging rail to rail at a steady throttle below 3000 rpm: a dying A/F sensor or an exhaust leak before it
        if (closedLoop && corr != null && thr != null && rpm < 3000) {
            swing.addLast(Triple(now, corr, thr))
            while (swing.isNotEmpty() && now - swing.first().first > 20_000L) swing.removeFirst()
        } else swing.clear()
        var swinging = false
        if (swing.size >= 20 && now - swing.first().first >= 15_000L) {
            val t = swing.map { it.third }
            val c = swing.map { it.second }
            val mean = c.average().toFloat()
            var turns = 0
            var side = 0
            for (v in c) {
                val s = if (v - mean >= 10f) 1 else if (v - mean <= -10f) -1 else 0
                if (s != 0 && s != side) { if (side != 0) turns++; side = s }
            }
            swinging = t.max() - t.min() <= 5f && c.max() - c.min() >= 30f && turns >= 4
        }
        if (swingHold.ms(swinging, now) >= 20_000L) {
            say("trimSwing", "На ровном газу коррекция смеси качается от упора до упора. Так умирает датчик состава смеси, " +
                "или выхлоп подсасывает воздух до него (прокладка приёмной трубы, повело фланец). Обороты при этом могут плавать",
                Alerts.Level.WARN, 3 * 3600_000L)
        }
        // the learning holding about -10 % while the trip burns a lot: the MAF reads more air than there is
        val learn = e.afLearnPct
        if (closedLoop && cruise && learn != null) { cruiseLearnS += dt; cruiseLearnSum += learn * dt }
        val per100 = x.tripPer100
        if (cruiseLearnS >= 300 && per100 != null) {
            val mean = cruiseLearnSum / cruiseLearnS
            if (mean in -14.5..-7.0 && per100 >= 15f) {
                say("mafHigh", kg.forester.sg.data.DistanceUnits.prose("ЭБУ постоянно убирает около %.0f%% топлива, а расход за поездку %.1f л/100. Так бывает, когда датчик расхода воздуха завышает: ".format(-mean, per100)) +
                    "не тот датчик или трубка, грязный датчик, неверная калибровка в прошивке. Почистите датчик и сравните расход", Alerts.Level.WARN, 24 * 3600_000L)
            }
        }
    }

    // ---------------------------------------------------------------- air and sensors (A01, A02, A06, A07)

    private fun air(e: EcuSnapshot, now: Long, hotIdle: Boolean, litres: Float?, rpm: Int) {
        // air at warm idle against the revs and the size: about 3 g/s at 900 rpm on a 2.0 (0.00167 g/s per rpm and
        // litre); a lot more at 700–750 is a MAF, its pipe or its scaling reading high, a lot less a leak after it
        val maf = e.mafGs
        if (on("idle") && !sport && litres != null && maf != null && rpm > 500) {
            val usual = 0.00167f * rpm * litres
            val k = maf / usual
            if (idleMafHold.ms(hotIdle && (k >= 1.7f || k <= 0.65f), now) >= 60_000L) {
                if (k >= 1.7f) say("idleMaf", "На холостых мотор берёт много воздуха для этих оборотов: %.1f г/с на %d об/мин, для %.1f л обычно около %.1f. ".format(maf, rpm, litres, usual) +
                    "Если смесь при этом в норме, датчик расхода воздуха завышает: проверьте его, трубку и калибровку в прошивке. Кондиционер и обогревы тоже добавляют",
                    Alerts.Level.INFO, 6 * 3600_000L)
                else say("idleMaf", "На холостых датчик видит мало воздуха: %.1f г/с на %d об/мин, для %.1f л обычно около %.1f. ".format(maf, rpm, litres, usual) +
                    "Так бывает при подсосе воздуха после датчика расхода или когда сам датчик грязный", Alerts.Level.INFO, 6 * 3600_000L)
            }
        }
        val atm = e.atmKpa
        // 50 kPa: the highest passes here (Ak-Baital ~57 kPa) are real
        if (atm != null && atmHold.ms(atm < 50f || atm > 108f, now) >= 5000L) {
            say("atm", "Датчик атмосферного давления показывает нереальные %.0f кПа. ЭБУ может неверно считать наддув и смесь".format(atm),
                Alerts.Level.WARN, 3 * 3600_000L)
        }
        val map = e.mapKpa
        // absolute: a healthy engine idles at about the same manifold pressure at any altitude (the factory 24–41 kPa)
        // the factory 24–41 kPa: a tuned engine on a sports ECU (cams, its own idle) is no fault above it
        if (on("idle") && !sport && map != null && mapHold.ms(hotIdle && map > 43f, now) >= 30_000L) {
            say("idleMap", "На холостых во впуске слабое разрежение (%.0f кПа). Так бывает при большом подсосе воздуха, низкой компрессии или сбитых фазах".format(map),
                Alerts.Level.WARN, 6 * 3600_000L)
        }
    }

    /** Right after a long park the coolant and the intake air are at the same (outside) temperature. */
    private fun keyOnChecks(e: EcuSnapshot, now: Long) {
        val parked = store.lastRunMs
        // after six hours parked the intake reads the outside air: the reference for heat soak (engine-ej A05, A08)
        if (parked != 0L && now - parked >= 6 * 3600_000L && (e.rpm ?: 0) <= 1200) e.intakeC?.let { store.ambientC = it.toFloat(); store.ambientMs = now }
        // ignition on, engine not turned yet (a remote start or a block heater warms the coolant: not a fault)
        if (parked == 0L || now - parked < 9 * 3600_000L || (e.rpm ?: 0) > 0) return
        val c = e.coolantC ?: return
        val i = e.intakeC ?: return
        // the coolant reading colder than the air after a night is the implausible direction (the ECU then over-fuels)
        if (c <= 30 && i <= 30 && i - c > 8) {
            say("tempPlaus", "После долгой стоянки датчики температуры антифриза ($c°) и воздуха ($i°) должны показывать почти одинаково. " +
                "Один из датчиков может врать, из-за этого хуже пуск и расход", Alerts.Level.WARN, 24 * 3600_000L)
        }
    }

    // ---------------------------------------------------------------- boost (B01…B05, B09…B13, turbo only)

    /** Another make over OBD-II: no target to compare with, so against what this car usually makes (see BoostNorm). */
    private fun learnedBoost(car: String, e: EcuSnapshot, now: Long, rpm: Int) {
        val n = boostNorm
        val before = n.revision
        n.forCar(car)
        val over = n.sample(now, e.mapKpa, e.atmKpa, rpm)
        if (n.revision != before) store.boostNorm = n.format()
        if (over != null) {
            say("overboost", "Наддув выше обычного для этой машины: %.0f кПа абсолютного давления при обычных %.0f. "
                .format(over.mapKpa, over.normKpa) + "Если машину не перенастраивали, проверьте управление наддувом: это нагружает мотор",
                Alerts.Level.WARN, 10 * 60_000L)
        }
    }

    private fun boost(x: RuleInput, now: Long, rpm: Int, thr: Float?, gauge: Float?, wot: Boolean, c: Int?, gear: Int?) {
        val e = x.e
        if (gauge != null) {
            gauges.addLast(now to gauge)
            while (gauges.isNotEmpty() && now - gauges.first().first > 1500L) gauges.removeFirst()
        }
        x.boostNormCar?.let { car -> learnedBoost(car, e, now, rpm) }
        val target = x.targetBoostKpa
        val wgdc = e.wastegatePct
        // over a target only means something on real boost, with a target that is one: no gate here once sounded at idle
        val overPlausible = target != null && gauge != null && target >= 30f && gauge >= 15f && rpm >= 2500
        if (overPlausible && gauge!! > target!! + 15f) overboostAt = now
        if (overboostHold.ms(overPlausible && gauge!! > target!! + 20f, now) >= 1000L) {
            // over the target with the solenoid nearly letting the wastegate open: it can't bleed enough (creep)
            val text = if (wgdc != null && wgdc <= 20f)
                "Наддув выше цели: %.2f бар при цели %.2f, хотя соленоид почти отпустил вестгейт (%.0f%%). ".format(gauge!! / 100f, target!! / 100f, wgdc) +
                    "Вестгейт не стравливает: пружина или сам актуатор, ось калитки; на прямотоке так бывает крип. Это нагружает поршни"
            else if (wgdc != null) "Наддув выше нормы: %.2f бар при цели %.2f. Это нагружает поршни. Проверьте шланги к актуатору вестгейта и соленоиду".format(gauge!! / 100f, target!! / 100f)
            // no duty read: nothing here says what the wastegate is doing
            else "Наддув выше нормы: %.2f бар при цели %.2f. Это нагружает поршни. Проверьте управление наддувом".format(gauge!! / 100f, target!! / 100f)
            say("overboost", text, Alerts.Level.WARN, 10 * 60_000L)
        }
        // fuel cut: full throttle, the injectors suddenly off, right after high boost
        val ref = store.boostBase.takeIf { it > 0f } ?: 70f
        val recentPeak = gauges.filter { now - it.first <= 1000L }.maxOfOrNull { it.second } ?: 0f
        // below the rev limiter (EJ25x cut fuel at ~6400–6700 rpm on purpose)
        val cut = (thr ?: 0f) >= 80f && rpm in 2500..6000 && ((e.injPwMs?.let { it < 0.5f } == true) || (e.lambda?.let { it > 1.5f } == true))
        if (cutHold.ms(cut, now) >= 200L && recentPeak > 0.8f * ref) {
            overboostAt = now
            say("fuelCut", "На разгоне ЭБУ отключил подачу топлива из-за превышения наддува, отсюда рывок. Не разгоняйтесь в пол до проверки управления наддувом",
                Alerts.Level.DANGER, 10 * 60_000L)
        }
        // after an overboost the ECU drops the boost control (duty 0) until the foot lifts: the car rides on the spring
        val spring = wot && now - overboostAt in 0..6000L && wgdc != null && wgdc <= 5f && target != null && target >= 50f &&
            gauge != null && gauge in 15f..0.7f * target
        if (springHold.ms(spring, now) >= 1500L) {
            say("springBoost", "После перенаддува ЭБУ отключил управление наддувом, и машина едет на одной пружине вестгейта (%.2f бар). ".format((gauge ?: 0f) / 100f) +
                "Управление вернётся после сброса газа. Причина в самом перенаддуве: проверьте вестгейт, его актуатор и шланги соленоида", Alerts.Level.WARN, 30 * 60_000L)
        }
        // underboost: each full-throttle pull compared with this car's usual peak; its target, spool point and wobble.
        // A pull with merged frames left out (the dyno's fast poll takes a full read only now and then) says nothing of
        // its peak or spool point: it isn't judged, nor kept. By that cause, not by the time between samples: a slow
        // cable's pull is judged as ever
        if (pullStart != 0L && x.skippedMerged) pullHoles = true
        if (wot) {
            if (pullStart == 0L) {
                pullHoles = x.skippedMerged
                pullStart = now; pullPeak = 0f; pullMaxRpm = 0; pullMaxWgdc = 0f
                pullStartRpm = rpm; pullStartGauge = gauge ?: 0f; pullGear = gear; pullGearMixed = false; pullSpoolRpm = null
                pullTargetAtPeak = null; pullMaxErr = null; prevBoost = null
                floatDir = 0; floatTurns = 0; floatMin = Float.MAX_VALUE; floatMax = -Float.MAX_VALUE; icSum = 0.0; icN = 0
            }
            if (gauge != null && gauge > pullPeak) pullTargetAtPeak = target
            pullPeak = maxOf(pullPeak, gauge ?: 0f)
            pullMaxRpm = maxOf(pullMaxRpm, rpm)
            pullMaxWgdc = maxOf(pullMaxWgdc, wgdc ?: 0f)
            if (gear != null && gear != pullGear) { if (pullGear == null) pullGear = gear else pullGearMixed = true }
            // the spool point: where the boost crossed the level, between the last sample and this one
            val level = spoolLevel()
            if (level > 0f && gauge != null && pullSpoolRpm == null) {
                prevBoost?.let { (r0, g0) -> if (g0 < level && gauge >= level) pullSpoolRpm = (r0 + (level - g0) / (gauge - g0) * (rpm - r0)).toInt() }
            }
            if (gauge != null) prevBoost = rpm to gauge
            val onBoost = now - pullStart >= 1500L
            if (onBoost && target != null && gauge != null && rpm >= 3000) pullMaxErr = maxOf(pullMaxErr ?: (target - gauge), target - gauge)
            if (onBoost && gauge != null && rpm in 3500..6500) floating(gauge, now)
            // the intercooler's efficiency, (in − out) / (in − outside), once there is heat to judge it by; the outside
            // by the intake after a long park, else the ECU's own sensor, which sits before the turbo
            val i = x.box.iatInC
            val o = x.box.iatOutC
            val inlet = ambient(now) ?: e.intakeC?.toFloat()
            val eff = if (onBoost) Derived.intercoolerEfficiencyPct(i, o, inlet) else null
            if (eff != null) { icSum += eff / 100.0; icN++; icIn = i!!; icOut = o!!; icInlet = inlet!! }
        } else if (pullStart != 0L && (thr ?: 0f) < 80f) {
            val long = now - pullStart >= 1500L
            pullStart = 0L
            if (long && !pullHoles) endPull(x, now, c)
            if (long && !pullHoles && pullMaxRpm >= 4000 && (c ?: 0) >= 70) judgePull(x)
        }
        // short of the target with the solenoid near its top: the turbo, a leak or the wastegate (B09)
        val short = pullStart != 0L && now - pullStart >= 1500L && rpm in 3500..6000 && target != null && target >= 30f &&
            gauge != null && target - gauge >= 15f && wgdc != null && wgdc >= 85f
        if (underTargetHold.ms(short, now) >= 1500L) {
            say("underTarget", "Наддув не дотягивает до цели: %.2f бар при цели %.2f, а соленоид уже на %.0f%%. ".format((gauge ?: 0f) / 100f, (target ?: 0f) / 100f, wgdc ?: 0f) +
                "Устала турбина, утечка во впуске или вестгейт не закрывается до конца: проверьте пружину актуатора и ось калитки", Alerts.Level.WARN, 30 * 60_000L)
        }
        if (wgdcHold.ms((wgdc ?: 0f) >= 95f, now) >= 3000L) {
            say("wgdcMax", "Соленоид наддува работает на пределе: турбина или впуск не справляются с запросом ЭБУ", Alerts.Level.INFO, 6 * 3600_000L)
        }
    }

    private var icIn = 0f
    private var icOut = 0f
    private var icInlet = 0f

    /** The boost the spool point is taken at: 1 bar, or 80 % of this car's usual peak where that is lower (stock SG5 0.75–0.87 bar). */
    private fun spoolLevel(): Float {
        if (spool.levelKpa > 0f) return spool.levelKpa
        val base = store.boostBase
        if (base < 40f) return 0f
        val level = if (base >= 115f) 100f else maxOf(30f, (0.8f * base / 10f).toInt() * 10f)
        spool = spool.copy(levelKpa = level)
        store.spool = spool.toJson()
        return level
    }

    /** The boost wobbling on the pull: turns of 8 kPa or more each way, counted once the pull is on boost (B04). */
    private fun floating(g: Float, now: Long) {
        floatMin = minOf(floatMin, g); floatMax = maxOf(floatMax, g)
        when {
            floatDir == 0 -> { floatDir = 1; floatExt = g }
            floatDir > 0 -> if (g > floatExt) floatExt = g else if (floatExt - g >= 8f) { floatDir = -1; floatExt = g; floatTurns++ }
            else -> if (g < floatExt) floatExt = g else if (g - floatExt >= 8f) { floatDir = 1; floatExt = g; floatTurns++ }
        }
        // a taper towards the redline is one turn at most; two full swings are floating
        if (floatTurns >= 4 && floatMax - floatMin >= 15f) {
            say("boostFloat", "Наддув на разгоне плавает, от %.2f до %.2f бар. Обычно это управление наддувом: соленоид и его шланги, слабая пружина актуатора. ".format(floatMin / 100f, floatMax / 100f) +
                "Или турбина не держит наддув на верхах", Alerts.Level.INFO, 6 * 3600_000L)
        }
    }

    /** The pull is over: its summary for the card, its spool point for the trend, the intercooler's efficiency. */
    private fun endPull(x: RuleInput, now: Long, c: Int?) {
        val sp = pullSpoolRpm
        val g = pullGear?.takeIf { !pullGearMixed }
        lastPull = PullSummary(now, g, pullPeak, pullTargetAtPeak, pullMaxErr, if (x.e.wastegatePct != null) pullMaxWgdc else null, sp)
        diagDirty = true
        val level = spoolLevel()
        // a pull counts when it started well below the level and below that rpm, in 2nd to 5th (1st doesn't reach
        // full boost), on a warm engine
        if (sp != null && g != null && g in 2..5 && (c ?: 0) >= 70 && pullStartGauge <= level - 30f && pullStartRpm <= sp - 400) {
            val (t, late) = spool.add(SpoolPoint(now, g, sp, x.e.intakeC))
            spool = t
            store.spool = t.toJson()
            val usual = t.base[g]
            if (late && usual != null) {
                say("spoolLate", "Турбина стала выходить на наддув позже: на $g-й передаче %.1f бар теперь на $sp об/мин, обычно было на $usual. ".format(level / 100f) +
                    (if (pullMaxWgdc > 0f || x.e.wastegatePct != null) "Так бывает при износе втулки вестгейта (калитка неплотно закрывается), утечке во впуске или прорыве выхлопа до турбины. "
                    else "Так бывает при утечке во впуске или прорыве выхлопа до турбины. ") +
                    "Проверьте впуск опрессовкой и прокладки выпускного коллектора", Alerts.Level.WARN, 24 * 3600_000L)
            }
        }
        if (icN >= 3) {
            val eff = icSum / icN
            if (eff < 0.6) {
                say("icEff", "Интеркулер снимает мало тепла: на разгоне его эффективность около %.0f%% (воздух до него %.0f°, после %.0f°, на входе в турбину %.0f°). ".format(eff * 100, icIn, icOut, icInlet) +
                    "У исправного воздушного кулера около 70%%. Проверьте, не забиты ли соты грязью и листвой", Alerts.Level.INFO, 24 * 3600_000L)
            }
        }
    }

    private fun judgePull(x: RuleInput) {
        val base = store.boostBase
        if (base <= 0f) { if (pullPeak >= 30f) store.boostBase = pullPeak; return }
        val low = pullPeak < 0.6f * base
        if (!low) store.boostBase = base * 0.85f + pullPeak * 0.15f
        pullLows.addLast(low)
        while (pullLows.size > 3) pullLows.removeFirst()
        if (low && base >= 40f && pullLows.count { it } >= 2) {
            val text = when {
                // a Link sends no wastegate duty nor IAM: nothing to blame the ECU with
                x.sportEcu -> "Наддув на разгоне ниже обычного: %.2f бар вместо %.2f. Проверьте впуск на утечки, шланги соленоида и буст-контроль в настройке Link".format(pullPeak / 100f, base / 100f)
                pullMaxWgdc >= 70f -> "Турбина не даёт положенного наддува (%.2f бар вместо обычных %.2f), хотя ЭБУ его просит. ".format(pullPeak / 100f, base / 100f) +
                    "Обычно это утечка во впуске после турбины или заклинивший вестгейт: проверьте хомуты и патрубки интеркулера"
                (x.iam ?: 1f) < (x.iamBoostCut ?: 0.75f) || pullMaxWgdc < 20f -> "ЭБУ сам ограничивает наддув (%.2f бар вместо %.2f). Чаще всего после детонации или из-за ошибки: ".format(pullPeak / 100f, base / 100f) +
                    "посмотрите коды и IAM, заправьтесь хорошим бензином"
                else -> "Наддув на разгоне ниже обычного: %.2f бар вместо %.2f. Проверьте впуск на утечки и шланги соленоида наддува".format(pullPeak / 100f, base / 100f)
            }
            say("underboost", text, Alerts.Level.WARN, 30 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- idle (I01, I02, V01) and the idle sample

    private fun idle(e: EcuSnapshot, now: Long, hotIdle: Boolean, rpm: Int) {
        if (idleHighHold.ms(hotIdle && !sport && (e.batteryV ?: 0f) > 12.8f && rpm > 1050, now) >= 60_000L) {
            say("idleHigh", "Прогретый мотор держит повышенные обороты на холостых ($rpm). Обычно это подсос воздуха или грязный дроссель и клапан холостого хода",
                Alerts.Level.INFO, 6 * 3600_000L)
        }
        if (idleLowHold.ms(hotIdle && rpm < 550, now) >= 5000L) {
            say("idleLow", "Обороты холостого хода падают ($rpm), мотор может заглохнуть. Помогает чистка дросселя и клапана холостого хода",
                Alerts.Level.WARN, 60 * 60_000L)
        }
        if (hotIdle) {
            idleRpm.addLast(now to rpm)
            while (idleRpm.isNotEmpty() && now - idleRpm.first().first > 20_000L) idleRpm.removeFirst()
            if (now - idleRpm.first().first >= 15_000L) {
                val r = idleRpm.map { it.second }
                var turns = 0
                var dir = 0
                // steps of 30 rpm from the last counted point, not from the previous sample: at the fast poll's
                // 12 samples a second a slow swing moves only ~10 rpm a sample and was never counted
                var ref = r[0]
                for (i in 1 until r.size) {
                    val d = r[i] - ref
                    if (abs(d) < 30) continue
                    ref = r[i]
                    val s = if (d > 0) 1 else -1
                    if (dir != 0 && s != dir) turns++
                    dir = s
                }
                if (r.max() - r.min() >= 150 && turns >= 4) {
                    say("idleHunt", "Обороты холостого хода плавают (${r.min()}–${r.max()}). Помогает чистка дросселя и клапана холостого хода, проверьте подсос воздуха",
                        Alerts.Level.WARN, 60 * 60_000L)
                }
            }
        } else idleRpm.clear()
        val avcs = e.avcsDeg
        if (avcs != null && avcsHold.ms(hotIdle && abs(avcs) > 5f, now) >= 10_000L) {
            say("avcsIdle", "Фазорегулятор распредвала не возвращается в исходное положение на холостых (%.0f°). Проверьте уровень и состояние масла, сетки клапанов AVCS".format(avcs),
                Alerts.Level.WARN, 6 * 3600_000L)
        }
    }

    private fun collectIdle(e: EcuSnapshot, x: RuleInput, now: Long, dt: Float) {
        if (dt <= 0f) return
        fun add(k: String, v: Float?) { if (v != null && !v.isNaN()) idleSums[k] = (idleSums[k] ?: 0.0) + v * dt }
        add("rpm", e.rpm?.toFloat()); add("coolant", e.coolantC?.toFloat()); add("timing", e.timingDeg); add("maf", e.mafGs)
        add("throttle", e.throttlePct); add("inj", e.injPwMs); add("afcorr", e.afCorrPct); add("aflearn", e.afLearnPct)
        add("map", e.mapKpa); add("battery", e.batteryV); add("avcs", e.avcsDeg); add("intake", e.intakeC?.toFloat())
        add("knock", x.fbkc ?: e.knockDeg)
        idleSumS += dt
        if (idleSumS >= 30.0) {
            onIdle(IdleSample(now, idleSums.mapValues { (it.value / idleSumS).toFloat() }))
            idleSums.clear(); idleSumS = 0.0
        }
    }

    // ---------------------------------------------------------------- cooling (C01…C03, C08)

    private fun cooling(x: RuleInput, now: Long, c: Int, speed: Int, runMs: Long) {
        // a hot restart: the coolant by the sensor reads 103–108° for a minute or so (heat soak), not overheating
        if (runMs < 90_000L) { warm102Hold.ms(false, now); jamHold.ms(false, now); coolWin.clear(); warmSince = 0L; return }
        when {
            c >= 110 -> say("hot", "Перегрев! ОЖ $c°. Остановитесь в безопасном месте и дайте мотору остыть, проверьте уровень антифриза", Alerts.Level.DANGER, 2 * 60_000L)
            c >= 105 -> say("hot", "Перегрев! ОЖ $c°. Сбросьте нагрузку, следите за стрелкой, при 110° остановитесь", Alerts.Level.DANGER, 2 * 60_000L)
        }
        if (warm102Hold.ms(c >= 102, now) >= 30_000L && c < 105) {
            say("warm102", "Мотор горячее обычного: ОЖ $c°. Снизьте нагрузку и следите за температурой", Alerts.Level.WARN, 10 * 60_000L)
        }
        if (speedHold.ms(speed >= 70, now) >= 60_000L && c >= 100) {
            say("hotSpeed", "На скорости мотор не должен греться выше 100°, а сейчас $c°. Проверьте уровень антифриза" +
                (if (x.profile.key == EngineProfiles.GENERIC.key) "" else ". Если он уходит без видимых потёков, нужна проверка прокладки ГБЦ"),
                Alerts.Level.WARN, 30 * 60_000L)
        }
        spike(x, now, c, speed)
        // hot only in traffic and cooling down on the move: the radiator fans don't switch on (they should cut in at 97–99°)
        if (jamHold.ms(c >= 100 && speed < 20, now) >= 60_000L) { jamHotPeak = maxOf(jamHotPeak, c); jamMoveStart = 0L }
        if (jamHotPeak > 0 && speed > 30) {
            if (jamMoveStart == 0L) jamMoveStart = now
            if (now - jamMoveStart > 3 * 60_000L) jamHotPeak = 0
            else if (c <= jamHotPeak - 5) {
                say("fans", "Мотор перегревался в пробке ($jamHotPeak°) и остывает на ходу. Скорее всего, не включаются вентиляторы радиатора: проверьте предохранители и реле",
                    Alerts.Level.WARN, 6 * 3600_000L)
                jamHotPeak = 0
            }
        }
        // creeping up in traffic while the ECU already runs the fan (or the A/C is on, which asks for it): the fan
        // doesn't turn at its first speed, often the oxidised pins of that relay (C08)
        val traffic = trafficHold.ms(speed < 20, now) >= 120_000L
        if (speed < 20) {
            trafficEct.addLast(now to c)
            while (trafficEct.isNotEmpty() && now - trafficEct.first().first > 70_000L) trafficEct.removeFirst()
        } else trafficEct.clear()
        val minuteAgo = trafficEct.firstOrNull()?.takeIf { now - it.first >= 60_000L }?.second
        val rising = minuteAgo != null && c >= x.profile.fanCreepFromC && c - minuteAgo >= 2
        val cmd = x.fanLow ?: x.fan
        val fanLong = fanOnHold.ms(cmd == true, now) >= 60_000L
        if (traffic && rising && (fanLong || (cmd == null && x.ac == true))) {
            say("fanRelay", "В пробке ОЖ ползёт вверх ($c°)" + (if (fanLong) ", хотя ЭБУ уже включил вентилятор" else " с включённым кондиционером") +
                ". Похоже, вентилятор не крутится на первой скорости: часто это окисленные ножки реле первой скорости или его предохранитель. " +
                "Проверьте, крутятся ли вентиляторы, когда включён кондиционер", Alerts.Level.INFO, 6 * 3600_000L)
        }
    }

    /**
     * A sudden coolant rise on a warm engine. Up to 95–97° it is the thermostat's band and the fan's switch-on point,
     * so the fan must be on by now (it cuts in during the rise, so only this sample is asked), or not known and the
     * coolant 3° over the fan's point; the A/C alone runs the fan, which is why that isn't enough without
     * [EngineProfile.spikeFromC]. A rise counts only on a real move (40+ km/h, the radiator in the airflow) at a steady
     * load: standing, crawling or climbing it is no fault. One drive says only what to look at; the gasket is named
     * from the third drive. Drives count 6 hours apart at least (a restart or a lost link is the same drive), and
     * start over after 90 days without one (air left by a coolant change must not make every later rise a WARN). The
     * 8° and the minute have no source, to be checked on logs.
     */
    private fun spike(x: RuleInput, now: Long, c: Int, speed: Int) {
        val p = x.profile
        coolWin.addLast(CoolSample(now, c, speed >= 40, x.e.mafGs))
        while (coolWin.isNotEmpty() && now - coolWin.first().t > 60_000L) coolWin.removeFirst()
        // right after reaching 80° the thermostat may still be opening and the coolant overshoots: no check then
        if (c < 80) warmSince = 0L else if (warmSince == 0L) warmSince = now
        val first = coolWin.first()
        if (now - first.t < 50_000L || now - warmSince <= 5 * 60_000L) return
        val fanSays = x.fan == true || (x.fan == null && c >= p.fanHighC + 3)
        if (!fanSays || c < p.spikeFromC || c - first.c < p.spikeRiseC || !coolWin.all { it.moving }) return
        val mafs = coolWin.map { it.maf ?: return }
        if (mafs.max() > mafs.min() * 1.3f) return
        if (!spikeThisDrive) { spikeThisDrive = true; countSpikeDrive(now) }
        val rise = "ОЖ выросла с ${first.c}° до $c° за минуту на ровном ходу" + if (x.fan == true) " при работающем вентиляторе" else ""
        val drives = store.spikeDrives
        if (drives < 3) say("spike", "$rise. Проверьте уровень антифриза, когда мотор остынет", Alerts.Level.INFO, 20 * 60_000L)
        else say("spike", "$rise, поездок с таким ростом уже $drives. " + when {
            p.key == EngineProfiles.GENERIC.key -> "Так бывает при воздухе в системе охлаждения. Проверьте уровень антифриза и нет ли пузырей в расширительном бачке"
            p.gasketMode == GasketMode.INTERNAL -> "Так бывает при воздухе в системе охлаждения и при прорыве газов через прокладку ГБЦ. Проверьте уровень антифриза и нет ли пузырей в расширительном бачке"
            p.gasketMode == GasketMode.EXTERNAL -> "На этом моторе прокладка ГБЦ обычно течёт наружу: проверьте уровень антифриза и нет ли потёков на стыке головки и блока"
            else -> "Так бывает при воздухе в системе охлаждения, реже из-за прокладки ГБЦ. Проверьте уровень антифриза и нет ли пузырей в расширительном бачке"
        }, Alerts.Level.WARN, 20 * 60_000L)
    }

    private fun countSpikeDrive(now: Long) {
        val since = now - store.spikeLastMs
        // a clock set back (head units lose it until they get the time again) tells nothing: no count, nothing written,
        // or the correction would look like 90 days gone by
        if (since < 0L || (store.spikeLastMs != 0L && since < 6 * 3600_000L)) return
        if (since > 90 * 24 * 3600_000L) store.spikeDrives = 0
        store.spikeDrives += 1
        store.spikeLastMs = now
    }

    // ---------------------------------------------------------------- intake heat (A08)

    /** Full throttle in 3rd to 5th with the intake 25° or more over the outside air: heat soak. */
    private fun intake(x: RuleInput, now: Long, gear: Int?, wotMs: Long, speed: Int) {
        val amb = ambient(now) ?: return
        // after the intercooler where the box has a sensor there, else the ECU's own
        val iat = x.box.iatOutC ?: x.e.intakeC?.toFloat() ?: return
        val gearOk = if (gear != null) gear in 3..5 else speed >= 60
        if (soakHold.ms(wotMs >= 1000L && gearOk && iat - amb >= 25f, now) >= 1000L) {
            say("heatSoak", "На полном газу воздух во впуске горячее улицы на %.0f° (%.0f° при %.0f° на улице). ".format(iat - amb, iat, amb) +
                "Впуск и интеркулер перегреты: пробка, стоянка с работающим мотором, забитые соты. От этого мотор склонен к детонации, дайте машине проехать спокойно",
                Alerts.Level.INFO, 60 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- gear slip on full throttle (transmission gear_ratio_slip_wot)

    /**
     * The engine to road speed ratio on full throttle against the learned one for the gear: a manual's own gear line
     * (+4 %), an automatic's usual full-throttle ratio by the TCU's gear, its normal converter slip included (+6 %).
     */
    private fun slip(x: RuleInput, now: Long, gear: Int?, rpm: Int, speed: Int, wotMs: Long) {
        fun none() { slipWin.clear(); slipHold.ms(false, now) }
        val minRpm = if (x.automatic) 3500 else 2500
        if (gear == null || speed < 50 || wotMs < 1000L || rpm !in minRpm..6500 || now < shiftUntil() + 500L) return none()
        val ratio = rpm / speed.toFloat()
        val base: Float
        val limit: Float
        if (x.automatic) {
            val tg = x.t?.gear?.takeIf { it > 0 } ?: return none()
            // learned only near what it was, so a slipping pull never becomes the norm
            if (accel > 0f) gearSense.learnWot(tg, ratio)
            val r = gearSense.wot[tg]?.takeIf { it.n >= 30 } ?: return none()
            base = r.ratio; limit = 0.06f
        } else {
            base = gearSense.gears().getOrNull(gear - 1) ?: return none()
            limit = 0.04f
        }
        slipWin.addLast(now to ratio / base - 1f)
        while (slipWin.isNotEmpty() && now - slipWin.first().first > 2000L) slipWin.removeFirst()
        val mean = slipWin.map { it.second }.average().toFloat()
        if (slipHold.ms(slipWin.size >= 3 && mean >= limit, now) >= 1500L) {
            val usual = (base * speed).toInt()
            say("gearSlip", if (x.automatic) "На полном газу на $gear-й передаче обороты выше обычного на %.0f%%: $rpm вместо примерно $usual на этой скорости. ".format(mean * 100) +
                "Похоже на проскальзывание гидротрансформатора или фрикционов: проверьте уровень и состояние ATF и ошибки коробки"
                else "На полном газу на $gear-й передаче обороты растут быстрее скорости: $rpm вместо примерно $usual (на %.0f%% выше). ".format(mean * 100) +
                "Так пробуксовывает сцепление: изношен диск или нет свободного хода педали", Alerts.Level.WARN, 30 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- SensorBox: fuel and oil pressure (F15, F16, O05)

    private fun boxRules(x: RuleInput, now: Long, rpm: Int, gauge: Float?, warm: Boolean, steadyIdle: Boolean, runMs: Long) {
        val b = x.box
        val fuel = b.fuelBar
        // the rail over the manifold on vacuum (idle, cruise), the last 20 s
        val dp = Derived.fuelDiffBar(fuel, gauge)
        if (dp != null && warm && (gauge ?: 0f) < -20f) {
            fuelDp.addLast(now to dp)
            while (fuelDp.isNotEmpty() && now - fuelDp.first().first > 20_000L) fuelDp.removeFirst()
        } else fuelDp.clear()
        val full = fuelDp.size >= 10 && now - fuelDp.first().first >= 15_000L
        val mean = if (full) fuelDp.map { it.second }.average().toFloat() else 3f
        val pump = b.pumpV?.takeIf { it < 12f }?.let { " На насос приходит всего %.1f В: проверьте его проводку и реле".format(it) } ?: ""
        // the regulator keeps ~3 bar over the manifold (2.5–3.5): above, the return can't drain (a big pump on a stock
        // regulator, a pinched return line); below, the pump, the filter or the regulator
        if (dpHighHold.ms(full && mean > 3.5f, now) >= 10_000L) {
            say("fuelDp", "Давление топлива выше нормы: %.1f бар над коллектором вместо около 3. Регулятор не справляется или пережата обратка, ".format(mean) +
                "так бывает с производительным насосом на штатном регуляторе.$pump", Alerts.Level.WARN, 6 * 3600_000L)
        }
        if (dpLowHold.ms(full && mean < 2.5f, now) >= 10_000L) {
            say("fuelDp", "Давление топлива ниже нормы: %.1f бар над коллектором вместо около 3. Проверьте бензонасос, топливный фильтр и регулятор, ".format(mean) +
                "под нагрузкой смесь может обеднеть.$pump", Alerts.Level.WARN, 6 * 3600_000L)
        }
        // pulsing at a steady idle: the pulse damper or the regulator's vacuum line
        val recent = fuelDp.filter { now - it.first <= 4000L }.map { it.second }
        val swingBar = if (recent.size >= 4) recent.max() - recent.min() else 0f
        if (pulseHold.ms(steadyIdle && swingBar >= 0.5f, now) >= 20_000L) {
            say("fuelPulse", "Давление топлива на холостых пульсирует, размах %.1f бар. Обычно это демпфер пульсаций или вакуумный шланг регулятора".format(swingBar),
                Alerts.Level.INFO, 24 * 3600_000L)
        }
        // oil at 3500 hot: owners count 5 bar and more as healthy and about 4 as worn bearings (the factory minimum
        // line, warned of by the guard, is lower: see engine-ej O05)
        val p = b.oilBar
        val revs = p != null && (x.oilC ?: 0f) >= 90f && runMs >= 10 * 60_000L && rpm in 3300..3700
        if (oilRevHold.ms(revs && p!! < 5f, now) >= 3000L && p != null) {
            if (p < 4f) say("oilRevs", "Давление масла на 3500 оборотах горячим %.1f бар. У здорового EJ обычно 5 и выше, около 4 бывает при изношенных вкладышах или маслонасосе. ".format(p) +
                "Проверьте уровень и вязкость масла", Alerts.Level.WARN, 6 * 3600_000L)
            else say("oilRevs", "Давление масла на 3500 оборотах горячим %.1f бар, чуть ниже привычных владельцам 5 бар. Следите, не падает ли оно от раза к разу".format(p),
                Alerts.Level.INFO, 24 * 3600_000L)
        }
    }

    // ---------------------------------------------------------------- ATF, lock-up, failsafe (transmission A, B, C)

    private fun atf(t: TcuSnapshot, now: Long, speed: Int, rpm: Int, running: Boolean) {
        val a = t.atfC
        if (a != null) {
            // a leap of more than 20° within a second; the way back from a one-sample spike is the same misread, not another
            if (lastAtf?.let { (t0, v0) -> now - t0 <= 1500L && abs(a - v0) > 20 } == true &&
                (atfJumps.isEmpty() || now - atfJumps.last() > 3000L)) atfJumps.addLast(now)
            while (atfJumps.isNotEmpty() && now - atfJumps.first() > 10 * 60_000L) atfJumps.removeFirst()
            lastAtf = now to a
            if (atfBadHold.ms(a <= -45 || a >= 150, now) >= 5000L || atfJumps.size >= 3) {
                atfJumps.clear()
                atfImplausibleUntil = now + 60_000L
                say("atfSensor", "Датчик температуры масла в коробке показывает неправдоподобные значения ($a°). Из-за этого коробка может переключаться жёстко, проверьте ошибки и разъём датчика",
                    Alerts.Level.WARN, 3 * 3600_000L)
            }
            val sane = now >= atfImplausibleUntil
            when {
                sane && (a >= 130 || atf120Hold.ms(a >= 120, now) >= 30_000L) ->
                    say("atfHot", "Коробка опасно перегрета: ATF $a°. Остановитесь в безопасном месте, поставьте P и дайте мотору поработать на холостых несколько минут",
                        Alerts.Level.DANGER, 3 * 60_000L)
                sane && atf110Hold.ms(a >= 110, now) >= 60_000L ->
                    say("atfWarm", "Коробка перегрета: ATF $a°. Сбавьте нагрузку, дайте ей остыть на ходу без рывков, проверьте радиатор и уровень масла",
                        Alerts.Level.WARN, 10 * 60_000L)
                sane && (atf100Hold.ms(a >= 100, now) >= 5 * 60_000L || atf105Hold.ms(a >= 105, now) >= 2 * 60_000L) ->
                    say("atfElevated", "Масло в коробке выше 100°. В пробке на долгих стоянках лучше ставить N или P, а в горку и с прицепом ехать спокойнее",
                        Alerts.Level.INFO, 3 * 3600_000L)
            }
            if (a < 120) atf120Hold.ms(false, now)
            if (a < 110) atf110Hold.ms(false, now)
        }
        if (lampHold.ms(t.atfLamp == true && speed > 10, now) >= 3000L) {
            say("atfLamp", "Горит лампа температуры коробки: блок АКПП сам видит перегрев или неисправность. Снизьте нагрузку и прочитайте ошибки АКПП", Alerts.Level.DANGER, 10 * 60_000L)
        }
        // failsafe: the TCU holds 3rd whatever the speed (cold ATF keeps out 4th on purpose, hence ATF ≥ 20°)
        val g = t.gear
        if (running && g == 3 && t.range == "D" && (a ?: 0) >= 20) {
            if (g3Start == 0L) { g3Start = now; g3Min = speed; g3Max = speed }
            g3Min = minOf(g3Min, speed); g3Max = maxOf(g3Max, speed)
            val held = now - g3Start
            if ((g3Min <= 10 && g3Max >= 80) || (held >= 2 * 60_000L && g3Min <= 20 && g3Max >= 90)) {
                say("limp", "Коробка ушла в аварийный режим и держит третью передачу. Ехать можно спокойно до сервиса, но без нагрузки, и нужно прочитать ошибки АКПП",
                    Alerts.Level.DANGER, 30 * 60_000L)
            }
        } else g3Start = 0L
        // the factory lock-up slip criterion (P0741 logic): commanded on, yet engine and turbine apart
        val tr = t.turbineRpm
        // steady speed only: the turbine speed comes a TCU poll (up to a second) later than the engine's
        val slip = t.lockUpPct != null && t.lockUpPct >= 90f && g != null && g in 3..4 && (a ?: 0) >= 50 && tr != null &&
            abs(accel) < 1.5f && rpm - tr > 40 + speed / 2
        if (lockHold.ms(slip, now) >= 10_000L) {
            say("lockSlipF", "Блокировка гидротрансформатора проскальзывает: мотор крутится на ${rpm - (tr ?: rpm)} об/мин быстрее турбины. " +
                "Начните с проверки уровня и замены масла в коробке, старое масло часто даёт именно это", Alerts.Level.WARN, 30 * 60_000L)
        }
    }

    /** Front and rear axles at different speeds on a steady straight road: different tyres (or pressures). */
    private fun tyres(t: TcuSnapshot, now: Long, dt: Float, speed: Int, thr: Float?) {
        val f = t.frontWheelKmh ?: return
        val r = t.rearWheelKmh ?: return
        if (tyresDone || dt <= 0f) return
        if (speed in 50..110 && abs(accel) < 1f && thr != null && thr in 5f..40f && r > 0) {
            tyreS += dt; tyreFront += f * dt; tyreRear += r * dt
        }
        if (tyreS >= 300) {
            tyresDone = true
            val ratio = (tyreFront - tyreRear) / tyreRear
            if (abs(ratio) >= 0.006) {
                say("tyres", "Передние и задние колёса крутятся с разной скоростью (на %.1f%%), похоже на разный износ или давление в шинах. ".format(abs(ratio) * 100) +
                    "Для полного привода Subaru это вредно: муфта постоянно работает на проскальзывание", Alerts.Level.INFO, 3 * 24 * 3600_000L)
            }
        }
    }
}
