package kg.forester.sg.service

import kg.forester.sg.data.Bucket
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Trip
import kg.forester.sg.data.TripState
import kg.forester.sg.ssm.EcuSnapshot
import kotlin.math.abs

object FuelCalc {
    /**
     * Petrol, g/L: the middle of EN 228's 720–775 kg/m³ at 15 °C. RomRaider's P203 takes 761; the refills calibrate
     * [litersPerHour]'s k against the litres really bought, so this only sets the figures before the first refills
     * (2 % apart), and another constant would shift every calibrated figure by as much: 745 stays.
     */
    private const val GASOLINE_G_PER_L = 745.0

    /** Cylinders of the engine (6 on the EZ30/EZ36), for the count by the injectors; set on connecting. */
    @Volatile var cylinders = 4

    /**
     * Fuel flow in L/h from MAF. The ECU meters fuel as MAF / target AFR and then applies the
     * A/F correction + learning trims, so the trims are included (they matter on this car: an air
     * leak shows up as large positive trims). Outside closed loop the measured AFR is used as is.
     * An OBD-II unit with its own fuel rate (PID 5E) gives the number itself, the cuts included.
     * [k] is learned from real refills.
     */
    fun litersPerHour(s: EcuSnapshot, k: Double, cylinders: Int = this.cylinders): Double {
        val rpm = s.rpm ?: return 0.0
        if (rpm < 300) return 0.0
        s.fuelRateLh?.takeIf { it in 0f..200f }?.let { source = "obd"; return it * k }
        val pw = s.injPwMs
        if (pw != null && pw < 0.2f && rpm > 1100) return 0.0 // deceleration fuel cut
        if (obdCut(s, rpm)) return 0.0
        val maf = s.mafGs ?: return byInjectors(pw, rpm, k, s.batteryV, cylinders)
        source = "maf"
        val measured = s.afr?.toDouble() ?: estimatedEnrichedAfr(s)
        val closedLoop = measured == null || measured in 13.5..16.0
        val fuelGs = if (closedLoop) {
            // stoichiometric target corrected by the short + long term trims (real ones stay within ±25 %)
            val trims = ((s.afCorrPct ?: 0f) + (s.afLearnPct ?: 0f)) / 100.0
            maf / 14.7 * (1 + trims.coerceIn(-0.35, 0.35))
        } else {
            // enrichment / lean: the wideband AFR is air over the fuel actually injected, trims included
            maf / measured!!.coerceIn(10.0, 18.5)
        } * k
        return fuelGs * 3600.0 / GASOLINE_G_PER_L
    }

    /**
     * The AFR to assume without a wideband or an A/F sensor reading: only at full throttle under boost, where the ECU
     * richens the mixture (about 12.5 at the start of boost to 11.5 from 80 kPa); null elsewhere, where 14.7 holds.
     */
    private fun estimatedEnrichedAfr(s: EcuSnapshot): Double? {
        val boost = s.boostKpa ?: return null
        val wot = (s.throttlePct ?: s.pedalPct ?: 0f) >= 90f
        if (!wot || boost < 20f) return null
        return (12.5 - (boost - 20.0) / 60.0).coerceIn(11.5, 12.5)
    }

    /** How the last [litersPerHour] got its number: "maf", "inj" (no MAF, by the injectors), "obd" (the unit's own PID 5E) or "" (none). */
    @Volatile var source = ""
        private set

    /**
     * Seconds since the previous sample, by the clock: the fast poll's ~12 samples a second each count a third of
     * the plain polling's share. A gap over 2 s (the line silent) counts as 2 s, not as burning all along.
     */
    fun sampleDt(lastMs: Long, now: Long): Double =
        if (lastMs == 0L) 0.0 else ((now - lastMs) / 1000.0).coerceIn(0.0, 2.0)

    /**
     * Longest silence between two running samples that is bridged: the engine ran all along, only answers were lost.
     * 40 s: a Legacy BP ECU (Серёга) went fully mute for about 30 s every two minutes until the line was reset.
     */
    const val BRIDGE_MAX_S = 40.0

    /**
     * Seconds to count between two samples that both had the engine running when the line was silent for more than
     * the 2 s of [sampleDt] but at most [BRIDGE_MAX_S]: some ECUs (Legacy BP) skip answers for 4-9 s while the car
     * drives on, and capping those at 2 s lost about a third of the km. Null when there is nothing to bridge.
     */
    fun bridgeDt(lastMs: Long, now: Long, wasRunning: Boolean, running: Boolean): Double? {
        if (lastMs == 0L || !wasRunning || !running) return null
        val gap = (now - lastMs) / 1000.0
        return if (gap > 2.0 && gap <= BRIDGE_MAX_S) gap else null
    }

    /**
     * How far the unit's clock went back between two samples, ms (negative), or null when it didn't. Some units lose
     * the time on the way and come back with a date a year old; a trip then ended before it began (Enderold: a trip
     * of -11614 h). A step back of a few seconds is the clock being trimmed, not a jump.
     */
    fun clockBackMs(lastMs: Long, now: Long): Long? =
        if (lastMs != 0L && now < lastMs - 5_000L) now - lastMs else null

    /** Distance the range's average consumption covers: the trips before this one too, not just this one. */
    const val RANGE_KM = 300.0

    /**
     * l/100 km the range is counted with: [current] and the trips before it ([past], newest first) until
     * [RANGE_KM] are covered. The trip's own figure climbs with every minute in a jam (litres without km), and the
     * range taken from it fell from 71 to 30 km in ten minutes of traffic that burnt a few hundred ml.
     */
    fun rangePer100(current: Trip?, past: List<Trip>): Double {
        var km = 0.0
        var l = 0.0
        // the current trip is saved as it goes: its row in [past] is an older copy of it
        val trips = listOfNotNull(current) + past.filter { current == null || current.id == 0L || it.id != current.id }
        for (t in trips) {
            if (km >= RANGE_KM) break
            km += t.distanceKm
            l += t.fuelL
        }
        // a junk MAF reading in an old trip must not turn the range into nonsense either way
        return if (km > 20 && l > 0) (l / km * 100).coerceIn(4.0, 30.0) else 10.0
    }

    /**
     * OBD-II's deceleration fuel cut: PID 03 = 4 is open loop "by load or the deceleration cut" (SAE J1979), so only
     * with the throttle shut above idle: the manifold under half the atmosphere (any engine size), or without a MAP
     * the throttle under 25 %. With the pedal down the same 4 is enrichment, and that fuel counts.
     */
    fun obdCut(s: EcuSnapshot, rpm: Int): Boolean {
        if (s.fuelSystem != 4 || rpm <= 1100) return false
        val map = s.mapKpa
        if (map != null && map > 0f) return map < (s.atmKpa ?: 101f) * 0.5f
        return (s.throttlePct ?: 100f) < 25f
    }

    /**
     * For an ECU without a usable MAF (some early 2000s ones answer 0xFFFF there): one injector a cylinder, each firing
     * once per two turns, open for the pulse width less the time they take to open, at a typical flow for the
     * engine. The refills calibrate [k] as they do for the MAF way.
     */
    private fun byInjectors(pw: Float?, rpm: Int, k: Double, volts: Float?, cylinders: Int): Double {
        if (pw == null || pw !in 0.3f..40f) { source = ""; return 0.0 }
        source = "inj"
        val turbo = runCatching {
            kg.forester.sg.data.Repo.guard.value.turboCar
        }.getOrDefault(false)
        // the driver's injectors when set (a Link car's are rarely stock), else typical for the engine
        val ccPerMin = runCatching { kg.forester.sg.data.Repo.settings.injectorCc }.getOrDefault(0).takeIf { it in 100..3000 }?.toDouble()
            ?: if (turbo) 380.0 else 260.0
        val at14 = runCatching { kg.forester.sg.data.Repo.settings.injectorDeadMs }.getOrDefault(0f).takeIf { it in DEAD_RANGE }?.toDouble()
            ?: DEAD_MS_14V
        return byInjectors(pw, rpm, ccPerMin, deadTimeMs(at14, volts), cylinders) * k
    }

    /**
     * L/h of [cylinders] injectors (one each) of [ccPerMin] open [pw] ms (less [deadMs] of opening) once per two
     * turns at [rpm]: four on the flat-fours, six on the EZ30/EZ36.
     */
    fun byInjectors(pw: Float, rpm: Int, ccPerMin: Double, deadMs: Double, cylinders: Int = 4): Double =
        rpm * (pw - deadMs).coerceAtLeast(0.0) * ccPerMin * 2e-6 * cylinders / 4.0

    /** The injectors' opening time at 14 V when the driver set none, ms. */
    const val DEAD_MS_14V = 0.6
    /** What the setting takes, ms (high-impedance injectors 0.3–1.2, old low-impedance ones up to 2). */
    val DEAD_RANGE = 0.1f..3f

    /**
     * The injectors' opening time at [volts], ms: [at14] (the time at 14 V, as data sheets and the Link's injector
     * setup give it) × (14 / V)^2.4. That is the shape of the latency tables in Subaru ROMs and of injector data
     * sheets: about 1.45× at 12 V, 2.2× at 10 V, 0.73× at 16 V; one number per car is enough, the curve does the
     * rest. The voltage is clamped to 9–16 V (an odd reading must not zero the count or double it); none = 14 V.
     * An approximation fitted to that shape, not a published formula: the refills' k takes up what it misses.
     */
    fun deadTimeMs(at14: Double, volts: Float?): Double {
        val v = (volts?.takeIf { !it.isNaN() } ?: 14f).coerceIn(9f, 16f).toDouble()
        return at14 * Math.pow(14.0 / v, 2.4)
    }
}

/**
 * The fuel left as shown, from a sender that swings by litres on a slope and in turns (Ruen16, SG: 38, 34, 38 again
 * over a week). It goes down by the fuel counted as burnt and is pulled to the sender slowly (about 10 min), so a
 * dip doesn't show; a rise of over [REFILL_L] that holds a minute is a refill and is taken at once.
 */
class FuelLevelFilter {
    var shown: Double? = null
        private set
    private var highS = 0.0

    fun feed(sender: Double?, burntL: Double, dtS: Double): Double? {
        if (sender == null) { shown = null; highS = 0.0; return null }
        val was = shown ?: return sender.also { shown = it }
        var v = (was - burntL).coerceAtLeast(0.0)
        if (sender > v + REFILL_L) {
            highS += dtS
            if (highS >= 60.0) { highS = 0.0; shown = sender; return sender }
        } else highS = 0.0
        v += (sender - v) * (dtS / 600.0).coerceIn(0.0, 1.0)
        shown = v
        return v
    }

    companion object { const val REFILL_L = 5.0 }
}

/** The SensorBox fuel line's learning: points ready, share of the tank covered (null: no such input). */
val boxProgress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Double>?>(null)

/** Integrates samples into the current trip, buckets, fuel level and learned corrections. */
/** [onNewTrip] gets the start time of every new trip. */
class TripTracker(ctx: android.content.Context, private val onNewTrip: (Long) -> Unit = {}) {
    private val s get() = Repo.settings
    private val db get() = Repo.db

    private var trip: Trip? = null
    private var lastSampleMs = 0L
    private var lastEngineOnMs = 0L
    private var lastSpeed = 0.0
    private var lastLh = 0.0
    private var lastSampleRunning = false
    private var lastHardEventMs = 0L
    private var accelFromMs = 0L
    private var accelFromSpeed = 0.0
    private var lastSaveMs = 0L
    /** Finished trips, newest first, for the range's average: read again on each save, not on every sample. */
    private var pastTrips: List<Trip>? = null

    private var bucketStart = 0L
    private var bucketFuel = 0.0
    private var bucketKm = 0.0

    private val speedWindow = ArrayDeque<Pair<Long, Double>>()
    private var speedK = s.speedK
    private val sensorCal = FuelSensorCal(ctx)
    /** The fuel gauge wire read by the SensorBox (SG and older: the ECU has no fuel level), its line volts 0–15. */
    private val boxCal = FuelSensorCal(ctx, "fuelcal_box", 15.0)
    private var standMs = 0L
    private var standSum = 0f
    private var standN = 0
    private var standMarked = false

    init {
        // the trip in memory is gone after a restart; its saved row stays as it is and a new trip starts
        s.currentTripId = 0
    }
    private var sensorLeftL: Double? = null
    private val levelFilter = FuelLevelFilter()
    private var lhShown = 0.0
    private var pendingFuel = 0.0

    private fun flushFuel() {
        if (pendingFuel <= 0.0) return
        s.usedSinceRefillL += pendingFuel
        s.usedSinceFullL += pendingFuel
        pendingFuel = 0.0
    }
    private var lastSpeedKSave = 0L

    @Synchronized
    fun process(e: EcuSnapshot, gps: GpsFix?) {
        val now = e.timeMs
        // a garbled answer (bad line, an address this ECU maps elsewhere) must not end up in the trip and fuel totals
        if ((e.rpm ?: 0) > 10_000 || (e.speedKmh ?: 0) > 300 || (e.mafGs ?: 0f) > kg.forester.sg.ssm.MAF_CEIL) return
        FuelCalc.clockBackMs(lastSampleMs, now)?.let { shiftClock(it) }
        tick(now) // closes the trip after 2 min with the engine off, even while the ECU keeps answering
        val engineOn = (e.rpm ?: 0) > 300
        val bridge = FuelCalc.bridgeDt(lastSampleMs, now, lastSampleRunning, engineOn)
        val dt = FuelCalc.sampleDt(lastSampleMs, now)
        lastSampleMs = now
        lastSampleRunning = engineOn

        val gpsFresh = gps != null && now - gps.timeMs < 3000 && gps.accuracyM < 15
        learnSpeed(e, gps, gpsFresh, now, dt)
        val speed = when {
            e.speedKmh != null -> e.speedKmh * speedK
            gpsFresh -> gps!!.speedKmh.toDouble()
            else -> 0.0
        }

        val lh = if (engineOn) FuelCalc.litersPerHour(e, s.fuelK) else 0.0
        // a bridged gap: the mean of both ends over the whole silence, not 2 s at the new sample's rate
        val dFuel = if (bridge != null) (lastLh + lh) / 2 * bridge / 3600.0 else lh * dt / 3600.0
        val dKm = if (bridge != null) (lastSpeed + speed) / 2 * bridge / 3600.0 else speed * dt / 3600.0

        if (engineOn) {
            lastEngineOnMs = now
            if (trip == null) {
                trip = Trip(startMs = now, endMs = now, distanceKm = 0.0, fuelL = 0.0)
                onNewTrip(now)
            }
        }

        var t = trip
        if (t != null && dt > 0) {
            speedWindow.addLast(now to speed)
            while (speedWindow.isNotEmpty() && now - speedWindow.first().first > 120_000) speedWindow.removeFirst()
            val avg = speedWindow.sumOf { it.second } / speedWindow.size
            val moving = speed >= 2
            // m/s^2 over half a second at least: the fast poll's samples 80 ms apart made each 1 km/h step 3.3 m/s^2
            var accel = 0.0
            val span = (now - accelFromMs) / 1000.0
            if (span >= 0.5) {
                accel = (speed - accelFromSpeed) / 3.6 / span
                accelFromMs = now; accelFromSpeed = speed
            }
            var hardA = 0
            var hardB = 0
            if (now - lastHardEventMs > 3000) {
                if (accel > 3.0) { hardA = 1; lastHardEventMs = now }
                if (accel < -3.5) { hardB = 1; lastHardEventMs = now }
            }
            t = t.copy(
                endMs = now,
                distanceKm = t.distanceKm + dKm,
                fuelL = t.fuelL + dFuel,
                cityKm = t.cityKm + if (moving && avg < 45) dKm else 0.0,
                cityL = t.cityL + if (moving && avg < 45) dFuel else 0.0,
                hwyKm = t.hwyKm + if (moving && avg >= 75) dKm else 0.0,
                hwyL = t.hwyL + if (moving && avg >= 75) dFuel else 0.0,
                mixKm = t.mixKm + if (moving && avg >= 45.0 && avg < 75.0) dKm else 0.0,
                mixL = t.mixL + if (moving && avg >= 45.0 && avg < 75.0) dFuel else 0.0,
                idleS = t.idleS + if (engineOn && !moving) (bridge ?: dt) else 0.0,
                idleL = t.idleL + if (!moving) dFuel else 0.0,
                hardAccel = t.hardAccel + hardA,
                hardBrake = t.hardBrake + hardB,
                maxBoostKpa = maxOf(t.maxBoostKpa, (e.gaugeKpa ?: 0f).toDouble()),
                maxRpm = maxOf(t.maxRpm, e.rpm ?: 0),
            )
            trip = t
        }
        lastSpeed = speed
        lastLh = lh

        if (dFuel > 0) {
            pendingFuel += dFuel // written to the settings every 30 s, not 4 times a second (flash wear)
        }
        addToBucket(now, dFuel, dKm)
        if (e.fuelLevelPct != null && s.tankL > 0) {
            // OBD-II gives the level in percent already
            sensorLeftL = e.fuelLevelPct / 100.0 * s.tankL
        } else if (e.fuelSensorV != null || e.fuelSensorOhm != null) {
            val known = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL - pendingFuel).coerceAtLeast(0.0) else null
            sensorLeftL = sensorCal.process(e.fuelSensorV, e.fuelSensorOhm, known, speed < 1, s.tankL, now)
        } else {
            // no level from the ECU: the gauge wire on the SensorBox, learnt the same way, if one is wired
            val line = kg.forester.sg.data.SensorBox.role("fuelLevel")
            sensorLeftL = if (line != null && line in 0.05f..15f) {
                val known = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL - pendingFuel).coerceAtLeast(0.0) else null
                val learnt = boxCal.process(line, null, known, speed < 1, s.tankL, now)
                // standing 15 s (the fuel in the tank settles) with the litres known from a full tank: a point by itself
                if (speed < 1 && engineOn) {
                    standMs += (dt * 1000).toLong()
                    if (standMs >= 5_000) { standSum += line; standN++ } // the first seconds the fuel still sloshes
                    if (standMs >= 15_000 && !standMarked && s.fuelLevelKnown && known != null && standN > 0) {
                        standMarked = true
                        kg.forester.sg.data.FuelLine.learn(standSum / standN, known.toFloat())
                    }
                } else { standMs = 0; standSum = 0f; standN = 0; standMarked = false }
                // marked by hand (tank filled step by step): those points win over what the refills taught
                kg.forester.sg.data.FuelLine.covered(line, s.tankL.toFloat())?.toDouble()?.let { if (s.tankL > 0) it.coerceIn(0.0, s.tankL) else it } ?: learnt
            } else null
            boxProgress.value = if (line == null) null else boxCal.progress(s.tankL)
        }
        sensorLeftL = levelFilter.feed(sensorLeftL, dFuel, dt)

        if (now - lastSaveMs > 30_000) save(now)
        // the number on screen is smoothed over ~1.5 s; the trip totals above use the raw flow. By time, not per
        // sample: the fast poll's 12 samples a second would make it three times as jumpy
        lhShown += (lh - lhShown) * (dt / 0.8).coerceAtMost(1.0)
        publish(lhShown, speed)
    }

    /**
     * The clock went back by -[d] ms mid-trip: move every time kept here by the same step, so the trip goes on with
     * its length intact instead of ending before it began, and the 2 min engine-off timer still runs.
     */
    private fun shiftClock(d: Long) {
        trip = trip?.let { it.copy(startMs = it.startMs + d, endMs = it.endMs + d) }
        lastSampleMs += d
        if (lastEngineOnMs != 0L) lastEngineOnMs += d
        if (lastHardEventMs != 0L) lastHardEventMs += d
        if (accelFromMs != 0L) accelFromMs += d
        lastSaveMs += d
        lastSpeedKSave += d
        val w = speedWindow.map { (it.first + d) to it.second }
        speedWindow.clear(); speedWindow.addAll(w)
        flushBucket()
    }

    /** Called periodically even without ECU data so trips close when the engine stops. */
    @Synchronized
    fun tick(now: Long) {
        val t = trip ?: return
        if (now - lastEngineOnMs > 120_000) {
            if (t.distanceKm > 0.05 || t.fuelL > 0.02) {
                val id = db.upsertTrip(t)
                trip = null
                s.currentTripId = 0
                pastTrips = null
                Repo.touch()
                if (id < 0) return
            } else {
                trip = null
            }
            flushBucket()
            publish(0.0, 0.0)
        }
    }

    @Synchronized
    fun resetTrip() {
        trip?.let { if (it.distanceKm > 0.05) db.upsertTrip(it) }
        pastTrips = null
        trip = trip?.let { Trip(startMs = System.currentTimeMillis(), endMs = System.currentTimeMillis(), distanceKm = 0.0, fuelL = 0.0) }
        s.currentTripId = 0
        onNewTrip(System.currentTimeMillis())
        Repo.touch()
        publish(0.0, 0.0)
    }

    @Synchronized
    fun shutdown() {
        flushFuel()
        save(System.currentTimeMillis())
        flushBucket()
    }

    private fun save(now: Long) {
        lastSaveMs = now
        flushFuel()
        pastTrips = null
        val t = trip ?: return
        if (t.distanceKm < 0.05 && t.fuelL < 0.02) return
        val id = db.upsertTrip(t.copy(id = s.currentTripId))
        if (s.currentTripId == 0L) {
            s.currentTripId = id
        }
        trip = t.copy(id = s.currentTripId)
        Repo.touch()
    }

    private fun learnSpeed(e: EcuSnapshot, gps: GpsFix?, gpsFresh: Boolean, now: Long, dt: Double) {
        val ecu = e.speedKmh ?: return
        if (!gpsFresh || gps == null) return
        if (ecu < 40 || gps.speedKmh < 40) return
        if (abs(ecu * speedK - lastSpeed) > 3) return // only steady driving
        val ratio = (gps.speedKmh / ecu).toDouble()
        if (ratio !in 0.85..1.15) return
        // 0.5 % per quarter second, by time: the fast poll's 12 samples a second must not learn three times as fast
        val a = (dt / 0.25 * 0.005).coerceAtMost(0.02)
        speedK = speedK * (1 - a) + ratio * a
        if (now - lastSpeedKSave > 60_000) {
            s.speedK = speedK; lastSpeedKSave = now
        }
    }

    private fun addToBucket(now: Long, dFuel: Double, dKm: Double) {
        val slot = now / 10_000 * 10_000
        if (bucketStart != 0L && slot != bucketStart) flushBucket()
        if (bucketStart == 0L) bucketStart = slot
        bucketFuel += dFuel
        bucketKm += dKm
    }

    private fun flushBucket() {
        if (bucketStart != 0L && (bucketFuel > 0 || bucketKm > 0)) {
            db.addBucket(Bucket(bucketStart, bucketFuel, bucketKm))
        }
        bucketStart = 0L; bucketFuel = 0.0; bucketKm = 0.0
    }

    private fun publish(lh: Double, speed: Double) {
        // the learned fuel sender wins when the ECU has one; otherwise count from the refills (SG)
        val left = sensorLeftL
            ?: if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL - pendingFuel).coerceAtLeast(0.0) else null
        val t = trip
        val past = pastTrips ?: db.trips(100).also { pastTrips = it }
        val avg = FuelCalc.rangePer100(t, past)
        Repo.trip.value = TripState(
            trip = t,
            instLh = lh, instKnown = true,
            // below 20 km/h litres per 100 km shoot past 100 when pulling away: show litres per hour there
            instPer100 = if (speed >= 20) lh / speed * 100 else null,
            fuelLeftL = left,
            fuelFromSensor = sensorLeftL != null,
            rangeKm = left?.let { it / avg * 100 },
        )
    }
}
