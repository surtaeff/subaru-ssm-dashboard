package kg.forester.sg.service

import kg.forester.sg.data.CustomParam
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.TcuSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** What the engine guard knows now; shown on the «Мониторинг» screen and in cards. */
data class GuardState(
    /** IAM, 0…1 (1 = full timing advance learned); null when this ECU/ROM has no IAM. */
    val iam: Float? = null,
    /** IAM at the start of this drive. */
    val iamStart: Float? = null,
    /** Feedback knock correction (now) and fine learning knock correction, degrees (negative = pulled). */
    val fbkc: Float? = null,
    val flkc: Float? = null,
    /**
     * Learned ignition timing, degrees: on the early 2000s ECUs (SG, GD, BE/BH) the standard SSM value that carries
     * what IAM does on the later ones (IAM times the knock advance map). Shown when the ROM has no IAM address.
     */
    val learnedDeg: Float? = null,
    /** Target boost relative to the atmosphere, kPa (from the ROM's extended parameters). */
    val targetBoostKpa: Float? = null,
    /** Oil temperature: read from the ECU, or estimated from the coolant and running time. */
    val oilC: Float? = null,
    val oilEstimated: Boolean = true,
    /** Engine still cold (coolant under 80 °C, where the EJ ends its warm-up enrichment): keep the revs down. */
    val cold: Boolean = false,
    val turbo: Boolean = false,
    /** A turbo car by what the ECU says (ROM, wastegate, boost seen), whether or not the turbo timer is on. */
    val turboCar: Boolean = false,
    /** Turbo heat 0…100 and, at idle after a hard drive, the seconds left before switching off. */
    val heat: Float = 0f,
    val cooldownSec: Int = 0,
    /** Minutes the last cold start took to reach 80 °C coolant. */
    val warmupMin: Float? = null,
    val thermostat: String = "",
    val coolantMax: Int? = null,
    val atfMax: Int? = null,
)

/** Highest (and lowest) values since the start of the trip or the last reset. */
data class Peaks(
    val boostBar: Float? = null,
    val rpm: Int? = null,
    val mafGs: Float? = null,
    val loadGRev: Float? = null,
    val speedKmh: Int? = null,
    val intakeC: Int? = null,
    val coolantC: Int? = null,
    val atfC: Int? = null,
    val knockDeg: Float? = null,
    val leanAfrUnderBoost: Float? = null,
    val sinceMs: Long = System.currentTimeMillis(),
)

/**
 * Watches the engine between polls: reads IAM and the knock corrections, estimates the oil temperature, keeps
 * the turbo cool-down, the thermostat (warm-up time), sudden coolant rise (EJ25 head gaskets) and the peak values.
 * The other warnings (knock, mixture, boost, idle, overheating, ATF) are the [analyzer]'s rules. Warnings go
 * through [Alerts].
 */
class EngineGuard {
    /** The rules from docs/knowledge; fed with every sample and what the guard worked out. */
    var analyzer: Analyzer? = null
    /** For the ROM base lookup (turbo or not by the ECU's ROM ID). */
    var ctx: android.content.Context? = null
    private var romIdSeen: String? = null
    private var romTurbo = false

    private var avail: List<RrParam>? = null
    private var roles = emptyMap<String, RrParam>()
    /**
     * Another make whose unit has no PID 5C (VW and others keep the oil in their own mode 22 ids): the oil temperature
     * signals of its OBDb set, all asked, the first that answers wins (which id a car has depends on the engine).
     */
    private var oilObdb = emptyList<RrParam>()
    private val oilName = Regex("\\boil temp", RegexOption.IGNORE_CASE)
    private val notOil = Regex("centrifug|transm|gear|start|limit|thresh|correct|exceed|warn|target|specified|switch", RegexOption.IGNORE_CASE)
    private var lastMs = 0L
    private var engineOnSince = 0L
    private var oilEst: Float? = null
    /** Air flow averaged over the last few minutes, g/s: how hard the engine has been working (heats the oil). */
    private var mafAvg = 0f
    private var heat = 0f
    private var cooldownAnnounced = false
    /** The cool-down countdown is on (see [process]): set at a stop after real work, kept until it runs out. */
    private var cooldownShown = false
    private var turboSeen = false
    private var boostMs = 0L
    private var iamStart: Float? = null
    private var iamLogged = false
    private var coldStartMs = 0L
    private var movingMsSinceCold = 0L
    private var warmupMin: Float? = null
    private var thermostatWarned = false
    /** Coolant samples of the last 90 s: time, °C, and whether the car was moving with the radiator fan on then. */
    private val coolant = ArrayDeque<Triple<Long, Int, Boolean>>()
    private var warmSince = 0L
    private var maintenanceChecked = 0L
    private var features = -1
    private var custom = -1
    private var pendingHours = 0.0
    private var camSkewMs = 0L

    /** The RomRaider parameters the guard reads, chosen from what this ROM has (first match wins). */
    private fun chooseRoles(list: List<RrParam>) {
        // the driver's own address for a role comes first: it is there because the base had none (or a wrong one);
        // then a role given to a parameter of the driver's own RomRaider file (one under RomRaider's id needs none)
        val own = kg.forester.sg.data.EcuDefs.roles() + kg.forester.sg.data.CustomParams.roles()
        fun pick(role: String, vararg ids: String) =
            (listOfNotNull(own[role]) + ids).firstNotNullOfOrNull { id -> list.firstOrNull { it.id == id } }
        val knock = Repo.settings.feature("knock")
        roles = listOfNotNull(
            pick("iam", "P90", "E31", "E96", "E111", "E1")?.takeIf { knock }?.let { "iam" to it },
            pick("fbkc", "E39", "E94", "E102", "E10")?.takeIf { knock }?.let { "fbkc" to it },
            pick("flkc", "P91", "E41", "E95", "E101", "E12")?.takeIf { knock }?.let { "flkc" to it },
            pick("learned", "P29", "P153")?.takeIf { knock }?.let { "learned" to it },
            pick("target", "E36", "E90", "E120", "E6")?.let { "target" to it },
            // another car's own oil sensor, if its unit reports the standard PID 5C
            pick("oil", "P122", "OBD_5C")?.let { "oil" to it },
            pick("latG", "P172")?.let { "latG" to it },
            // the ROM's own manifold pressure, wider than the standard single byte (0–255 kPa absolute = 1.53 bar of boost)
            pick("mapAbs", "E51", "E129", "E20", "E88", "E98")?.let { "mapAbs" to it },
            pick("mapRel", "E113", "E72")?.let { "mapRel" to it },
            pick("mapRelSea", "E21", "E89")?.let { "mapRelSea" to it },
            pick("yaw", "P171")?.let { "yaw" to it },
            // the radiator fan relays as the ECU switches them (switch bits at 0x65), or its fan duty on later ECUs
            pick("fan1", "S28")?.let { "fan1" to it },
            pick("fan2", "S29")?.let { "fan2" to it },
            pick("fan3", "S27")?.let { "fan3" to it },
            pick("fanPct", "P92")?.let { "fanPct" to it },
            // the A/C compressor signal: the same switch byte as the fan relays, no extra read
            pick("ac", "S26")?.let { "ac" to it },
            // the gear the ECU works out (some ROMs): the spool point and gear slip by gear on a manual
            pick("gearEcu", "E25", "E59")?.let { "gearEcu" to it },
            // Knock Sum per cylinder (SH, 20X ROMs): 16 bytes more per poll, so only when asked for in «Откаты и наддув»
            *(1..4).map { n -> pick("ks$n", "E${164 + n}")?.takeIf { knock && Repo.settings.knockSums }?.let { "ks$n" to it } }.toTypedArray(),
            // the real camshaft angles (EJ20X, addresses from Zombie47): the standard AVCS parameter stops at 0°
            pick("camInL", "Z1")?.let { "camInL" to it },
            pick("camInR", "Z2")?.let { "camInR" to it },
            pick("camExL", "Z3")?.let { "camExL" to it },
            pick("camExR", "Z4")?.let { "camExR" to it },
        ).toMap() + CustomParam.STAND_IN.mapNotNull { r -> pick(r)?.let { r to it } }
        // «Engine oil temperature» first, then «Oil temperature», then the rest (measured, pulse sensor, calculated…)
        oilObdb = if (roles["oil"] != null) emptyList() else list
            .filter { it.id.startsWith(kg.forester.sg.ssm.Obdb.PREFIX) && it.units == "°C" && oilName.containsMatchIn(it.name) && !notOil.containsMatchIn(it.name) }
            .sortedBy { p -> val n = p.name.trim().lowercase(); if (n == "engine oil temperature") 0 else if (n == "oil temperature") 1 else 2 }
            .take(6)
        Repo.internalWatch.value = (roles.values.map { it.id } + oilObdb.map { it.id }).toSet()
    }

    fun role(name: String): RrParam? = roles[name]

    /**
     * Left and right camshafts of the same kind sit at the same angle on a warm idle. One of them 12° or more off
     * for 15 s (a few degrees apart is normal; Zombie47's case: exhausts 63.5° and 81.9°): the timing belt has jumped a tooth on that side (about 15–20° on EJ20X), or its AVCS valve is stuck. A
     * value outside −60…120° is a wrong address, not a cam.
     */
    private fun camCheck(inL: Float?, inR: Float?, exL: Float?, exR: Float?, e: EcuSnapshot, dt: Float) {
        fun ok(x: Float?) = x != null && !x.isNaN() && x in -60f..120f
        val idle = (e.rpm ?: 0) in 550..1200 && (e.speedKmh ?: 0) == 0 && (e.coolantC ?: 0) >= 75
        val skewIn = if (ok(inL) && ok(inR)) kotlin.math.abs(inL!! - inR!!) else 0f
        val skewEx = if (ok(exL) && ok(exR)) kotlin.math.abs(exL!! - exR!!) else 0f
        camSkewMs = if (idle && maxOf(skewIn, skewEx) >= 12f) camSkewMs + (dt * 1000).toLong() else 0L
        if (camSkewMs < 15_000L) return
        val (kind, l, r) = if (skewEx >= skewIn) Triple("выпускные", exL!!, exR!!) else Triple("впускные", inL!!, inR!!)
        Alerts.raise("camSkew", "Левый и правый $kind распредвалы стоят по-разному: %.0f° и %.0f°. Так бывает, когда ремень ГРМ перескочил на зуб или не работает клапан AVCS одной стороны. Проверьте метки ГРМ"
            .format(l, r), Alerts.Level.WARN, 6 * 3600_000L)
    }

    /**
     * [e] with the manifold pressure from the ROM's direct parameter when it has one (4-byte float or 2-byte): the
     * standard address is one byte, 0–255 kPa absolute, so above ~1.53 bar of boost it sticks at 255. And the knock
     * correction from the FBKC when that pulls deeper than the standard byte.
     */
    fun withDirectMap(e: EcuSnapshot): EcuSnapshot {
        val rr = Repo.rrValues.value
        fun v(role: String) = roles[role]?.let { rr[it.id] }?.toFloat()
        val abs = v("mapAbs")?.takeIf { it in 10f..450f }
            ?: v("mapRel")?.let { r -> e.atmKpa?.let { it + r } }?.takeIf { it in 10f..450f }
            ?: v("mapRelSea")?.let { it + 101.325f }?.takeIf { it in 10f..450f }
        mapLimit(e, abs != null)
        // the knock correction: some ROMs (a 16-bit 572 of a tester) keep the standard byte 0x22 at 0 and pull timing
        // only in their FBKC, so the cards and the knock counter showed nothing while the log had the pulls
        val fbkc = v("fbkc")?.takeIf { it in -16f..2f }
        val knock = if (fbkc != null && fbkc < (e.knockDeg ?: 0f)) fbkc else e.knockDeg
        // the driver's own values for what the ECU doesn't give or gives wrong (old JECS units); a reading out of
        // range is a wrong address or formula and leaves the ECU's value
        val afr = v("afr")?.takeIf { it in 6f..25f }
            // a wideband on the SensorBox stands in for the ECU's own only where it has none (or sends a constant 14.7)
            ?: kg.forester.sg.data.SensorBox.role("afr")?.takeIf { (e.lambda == null || RulesHost.noWideband.value) && it in 6f..25f }
        val out = e.copy(
            mapKpa = abs ?: e.mapKpa,
            knockDeg = knock,
            mafGs = v("maf")?.takeIf { it in 0f..600f } ?: e.mafGs,
            intakeC = v("iat")?.takeIf { it in -40f..150f }?.let { kotlin.math.round(it).toInt() } ?: e.intakeC,
            coolantC = v("coolant")?.takeIf { it in -40f..150f }?.let { kotlin.math.round(it).toInt() } ?: e.coolantC,
            throttlePct = v("throttle")?.takeIf { it in 0f..100f } ?: e.throttlePct,
            injPwMs = v("injPw")?.takeIf { it in 0f..50f } ?: e.injPwMs,
            timingDeg = v("timing")?.takeIf { it in -30f..70f } ?: e.timingDeg,
            atmKpa = v("atm")?.takeIf { it in 50f..110f } ?: e.atmKpa,
            lambda = afr?.let { it / 14.7f } ?: e.lambda,
        )
        return if (out == e) e else out
    }

    /** Since when the standard MAP byte sits at its top (0 = it doesn't). */
    private var mapTopMs = 0L

    /**
     * The standard manifold pressure is one byte, 0–255 kPa absolute (SSM 0x0D, OBD-II PID 0B): held at 255 for a
     * second with no wider parameter to take instead, the boost above ~1.5 bar is not seen, and the driver is told
     * once in a while that the peaks shown are the byte's limit, not the turbo's.
     */
    private fun mapLimit(e: EcuSnapshot, direct: Boolean) {
        // a sports ECU's MAP on the CAN is two bytes: 255 kPa is a real reading there
        if (!mapAtLimit(e.mapKpa, direct || kg.forester.sg.data.CanEcu.active)) { mapTopMs = 0L; return }
        if (mapTopMs == 0L) mapTopMs = e.timeMs
        if (e.timeMs - mapTopMs < 1000L) return
        Alerts.raise("mapLimit", "Давление во впуске упёрлось в 255 кПа: это предел стандартного параметра (один байт), наддув выше " +
            "примерно 1,5 бар он не показывает, и пик наддува занижен. Если ЭБУ отдаёт прямой параметр давления (у Subaru E51, E20, E88, E98), " +
            "приложение возьмёт его само", Alerts.Level.INFO, 12 * 3600_000L, speak = false)
    }

    /** Role → the parameter id read for it (IAM, FBKC…), for the log: those already have a column of their own. */
    fun roleIds(): Map<String, String> = roles.mapValues { it.value.id }

    /** The rail's height over the manifold at idle, learnt each drive; what it should stay at under boost. */
    private var fuelDpIdle: Float? = null
    private var fuelDpLowSince = 0L

    /**
     * Fuel pressure from the box (or a CAN channel with that role) against the manifold: a return-type regulator
     * keeps their difference the same at idle and under boost. Under boost 0.5 bar or more below what it was at idle,
     * for 2 s: the pump or the regulator is not keeping up, and the mixture goes lean exactly when it matters.
     */
    private fun fuelPressure(now: Long, rpm: Int, gaugeKpa: Float?, dt: Float) {
        val gauge = gaugeKpa ?: return
        val dp = kg.forester.sg.data.Derived.fuelDiffBar(kg.forester.sg.data.SensorBox.roleBar("fuelP"), gauge) ?: return
        // averaged over ~5 s of idle, by time (0.05 a sample at the plain polling's 4 a second)
        if (rpm in 600..1100 && gauge < -30f) fuelDpIdle = fuelDpIdle?.let { it + (dp - it) * (dt / 5f).coerceAtMost(1f) } ?: dp
        val base = fuelDpIdle ?: return
        val low = gauge > 40f && dp < base - 0.5f
        fuelDpLowSince = if (low) fuelDpLowSince.takeIf { it > 0 } ?: now else 0L
        if (fuelDpLowSince > 0 && now - fuelDpLowSince > 2000)
            Alerts.raise("fuelDp", "Давление топлива не успевает за наддувом: перепад %.1f бар, на холостых был %.1f. Проверьте бензонасос, фильтр и регулятор, смесь под наддувом беднеет"
                .format(dp, base), Alerts.Level.WARN, 10 * 60_000L)
    }

    /** The SensorBox inputs the rules read, by their roles (fuel rail in bar whatever the sensor's unit). */
    private fun boxInput(): BoxInput {
        val box = kg.forester.sg.data.SensorBox
        // the box or an own CAN channel with that role (SensorBox.role), pressures in bar whatever the unit
        return BoxInput(box.roleBar("fuelP"), box.roleBar("oilP"), box.role("iatIn"), box.role("iatOut"), box.role("pumpV"))
    }

    private var oilPLowSince = 0L
    private var oilPZeroSince = 0L

    /** Oil pressure and exhaust gas from the SensorBox, when they are wired: the warnings the EJ itself can't give. */
    private fun boxAlarms(now: Long, running: Boolean, rpm: Int, oil: Float?, gaugeKpa: Float?, dt: Float) {
        val p = kg.forester.sg.data.SensorBox.roleBar("oilP")
        if (p != null && running && engineOnSince > 0 && now - engineOnSince > 5000) {
            oilPZeroSince = if (p < 0.4f) oilPZeroSince.takeIf { it > 0 } ?: now else 0L
            // the factory minimum on oil at 80°: 98 kPa at 800 rpm, 294 kPa at 5000 (FSM), on a line between
            val min = 0.98f + (rpm - 800).coerceIn(0, 4200) / 4200f * (2.94f - 0.98f)
            val low = (oil ?: 0f) >= 70f && p < min * 0.85f
            oilPLowSince = if (low) oilPLowSince.takeIf { it > 0 } ?: now else 0L
            if (oilPZeroSince > 0 && now - oilPZeroSince > 1500)
                Alerts.raise("oilPress", "Давления масла почти нет: остановитесь и заглушите мотор", Alerts.Level.DANGER, 60_000L)
            else if (oilPLowSince > 0 && now - oilPLowSince > 5000)
                Alerts.raise("oilPress", "Давление масла %.1f бар, ниже нормы для %d оборотов: проверьте уровень масла".format(p, rpm),
                    Alerts.Level.WARN, 10 * 60_000L)
        } else { oilPLowSince = 0L; oilPZeroSince = 0L }
        fuelPressure(now, rpm, gaugeKpa, dt)
        val egt = kg.forester.sg.data.SensorBox.role("egt")
        val egtMax = kg.forester.sg.data.SensorBox.slots.value.firstOrNull { it.role == "egt" }?.warnAbove ?: 900f
        if (egt != null && egt > egtMax) Alerts.raise("egt", "Выхлоп ${egt.toInt()} градусов: сбавьте нагрузку", Alerts.Level.WARN, 2 * 60_000L)
    }

    /**
     * Forget everything learned from the samples so far: called when the demo is switched on or off, so its made-up
     * boost doesn't make an NA car a turbo (and its injectors 380 cc), its heat doesn't keep the turbo timer going.
     */
    fun reset() {
        oilEst = null; mafAvg = 0f; heat = 0f; cooldownAnnounced = false; turboSeen = false; boostMs = 0L
        iamStart = null; iamLogged = false; coldStartMs = 0L; movingMsSinceCold = 0L; warmupMin = null
        thermostatWarned = false; coolant.clear(); warmSince = 0L; engineOnSince = 0L; lastMs = 0L; romIdSeen = null
        analyzer?.reset()
    }

    fun newTrip(now: Long) {
        Repo.peaks.value = Peaks(sinceMs = now)
        Repo.tripRange.value = emptyMap()
        iamStart = null
        iamLogged = false
    }

    fun process(e: EcuSnapshot, t: TcuSnapshot?, now: Long) {
        val list = Repo.rrAvailable.value
        if (list !== avail || features != Repo.featureVersion.value || custom != Repo.customVersion.value) {
            avail = list; features = Repo.featureVersion.value; custom = Repo.customVersion.value; chooseRoles(list)
        }
        val s = Repo.settings
        val fKnock = s.feature("knock"); val fTurbo = s.feature("turbo")
        val fCool = s.feature("cooling"); val fService = s.feature("service")
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 2f)
        lastMs = now
        val rpm = e.rpm ?: 0
        val running = rpm > 400
        // engine hours: counted in memory, saved once a minute (flash wear)
        if (running && !s.demo) {
            pendingHours += dt / 3600.0
            if (pendingHours > 1.0 / 60) { s.engineHours += pendingHours; pendingHours = 0.0 }
        }
        if (!running) { engineOnSince = 0L; oilEst = null }
        else if (engineOnSince == 0L) engineOnSince = now
        val rr = Repo.rrValues.value
        fun v(role: String) = roles[role]?.let { rr[it.id] }?.toFloat()

        // --- IAM and knock corrections
        // a role read from a borrowed or user-typed address can be junk: keep only values that make sense for it
        val iam = v("iam")?.let { iamOf(it, roles["iam"]?.units.orEmpty()) }
        if (iam != null && running && fKnock) {
            if (iamStart == null) iamStart = iam
            if (!iamLogged) { iamLogged = true; if (!s.demo) logIam(now, iam) }
        }
        camCheck(v("camInL"), v("camInR"), v("camExL"), v("camExR"), e, dt)

        val fbkc = v("fbkc")?.takeIf { it in -16f..2f }
        val flkc = v("flkc")?.takeIf { it in -16f..2f }
        val target = targetBoost(e)

        // --- turbo heat and cool-down
        val gauge = e.gaugeKpa ?: 0f
        // turbo only after real boost: above 0.15 bar, below 2.5 bar (a junk 0xFF reads +127 kPa), held for 2 s
        boostMs = if (gauge in 15f..250f && (e.rpm ?: 0) > 1500) boostMs + (dt * 1000).toLong() else 0L
        if (boostMs > 2000) turboSeen = true
        // turbo by what the ECU says (its ROM ID, a wastegate it reports, boost seen), not by the name typed in the settings
        val init = Repo.ecuInit.value
        if (init?.romId != romIdSeen) {
            romIdSeen = init?.romId
            romTurbo = init != null && ((ctx?.let { kg.forester.sg.ssm.EngineId.romTurbo(it, init.romId) } ?: kg.forester.sg.ssm.EngineId.fromRomId(init.romId)?.turbo) == true ||
                (kg.forester.sg.ssm.EcuParams.reported(init, kg.forester.sg.ssm.EcuParams.WGDC) && init.capabilities.size > 4))
        }
        val sport = kg.forester.sg.data.CanEcu.active
        if (sport && turboSeen && !s.linkTurbo) s.linkTurbo = true
        val turboCar = turboSeen || romTurbo || s.demo || (sport && s.linkTurbo)
        val turbo = fTurbo && turboCar
        val speed = e.speedKmh ?: 0
        val idle = running && rpm < 1300 && speed < 3
        heat = when {
            !running -> (heat - dt * 1.2f).coerceAtLeast(0f)
            turboSeen && gauge in 10f..250f -> heat + dt * (gauge / 40f + if (rpm > 4000) 0.6f else 0.2f) * 2.5f
            idle -> heat - dt * 1.1f
            else -> heat - dt * 0.25f
        }.coerceIn(0f, 100f)
        // the countdown (a big card over the screen) and the voice only after real work, ≈15 s at 0.5 bar or a long
        // pull: from heat 12 one overtake before a traffic light put «не глушите» up at every stop. Once up, it counts
        // down to 12 as before
        if (!(turbo && idle)) cooldownShown = false else if (heat > 60f) cooldownShown = true
        val cooldown = if (cooldownShown && heat > 12f) heat.toInt() else 0
        if (turbo && idle && heat > 60f && !cooldownAnnounced) {
            cooldownAnnounced = true
            Alerts.raise("turbo", "Турбина горячая: дайте мотору поработать на холостых около ${heat.toInt()} секунд перед выключением", Alerts.Level.INFO, 5 * 60_000L,
                banner = false) // the countdown is on screen already
        }
        // the same moment the countdown ends (heat 12): spoken only, the header pill turns green «можно глушить»
        if (cooldownAnnounced && heat <= 12f) {
            cooldownAnnounced = false
            Alerts.clear("turbo")
            if (idle) Alerts.raise("turboOk", "Турбина остыла, можно глушить", Alerts.Level.INFO, 5 * 60_000L, banner = false)
        }
        if (!idle && heat > 60f) cooldownAnnounced = false

        // --- oil temperature and cold engine
        val c = e.coolantC
        // a real sensor: the ECU's own (FA/FB, another car's OBD PID 5C) or one on the SensorBox or a CAN channel
        val oilEcu = v("oil") ?: oilObdb.firstNotNullOfOrNull { rr[it.id] }?.toFloat() ?: kg.forester.sg.data.SensorBox.role("oil")
        e.mafGs?.let { m -> mafAvg += (m - mafAvg) * (dt / 180f).coerceAtMost(1f) }
        // the estimate is the EJ's (its gap over the coolant, its warm-up): another car shows the oil only from a sensor
        val estimate = !kg.forester.sg.data.CarKind.other.value
        if (!estimate) oilEst = null
        val oil = oilEcu ?: if (running && c != null && estimate) {
            // Most EJs have no oil temperature sensor: estimated. The oil lags the coolant by several minutes after a
            // cold start, and once warm it runs hotter than the coolant, the more so the harder the engine works
            // (a stock STI cruising at 105 km/h: oil ~106° at 90–95° coolant; the turbo is cooled by the oil too).
            // owners with a real gauge see the oil at least ~10° over the coolant once warm (drive2 feedback, 2026-10):
            // the gap starts there and grows with the load
            val warmth = ((c - 55) / 20f).coerceIn(0f, 1f) // no gap while the thermostat is still shut
            val gap = (if (turboCar) 10f + 0.3f * mafAvg else 8f + 0.25f * mafAvg).coerceAtMost(35f) * warmth
            val target = c + gap
            val o = oilEst ?: (c - 5f)
            (o + (target - o) * (dt / 300f).coerceAtMost(1f)).also { oilEst = it }
        } else null
        // warm by the coolant, as the ECU sees it: every EJ ends its warm-up enrichment at 80° (the oil is only an estimate)
        val cold = running && c != null && c < 80
        boxAlarms(now, running, rpm, oil, e.gaugeKpa, dt)

        // --- thermostat: warm-up time from a cold start
        if (running && c != null) {
            if (coldStartMs == 0L && c < 45 && engineOnSince != 0L && now - engineOnSince < 120_000L) {
                coldStartMs = now; movingMsSinceCold = 0L; thermostatWarned = false
            }
            if (coldStartMs != 0L) {
                if (speed > 30) movingMsSinceCold += (dt * 1000).toLong()
                if (c >= 80) {
                    warmupMin = (now - coldStartMs) / 60_000f
                    coldStartMs = 0L
                } else if (fCool && movingMsSinceCold > 20 * 60_000L && c < 72 && !thermostatWarned) {
                    thermostatWarned = true
                    Alerts.raise("thermostat", "Мотор не прогревается: 20 минут в движении, а ОЖ всего $c°. Похоже, термостат открыт", Alerts.Level.WARN, 6 * 3600_000L)
                }
            }
        } else if (!running) coldStartMs = 0L

        // --- coolant: overheating and sudden rise on a warm engine
        if (c != null && running && fCool) {
            // the radiator fan: null when this ECU does not report it
            val fan = listOf("fan1", "fan2", "fan3").mapNotNull { v(it) }.takeIf { it.isNotEmpty() }?.any { it >= 0.5f }
                ?: v("fanPct")?.let { it > 5f }
            // a rise counts only on a real move (40+ km/h, the radiator in the airflow) with the fan on: standing or
            // crawling in a jam the coolant climbs to the fan's switch-on point (97–99°) by itself, and that is what kept
            // raising this warning in traffic while the dash needle (buffered from ~70 to ~105°) didn't move
            coolant.addLast(Triple(now, c, speed >= 40 && fan != false))
            while (coolant.isNotEmpty() && now - coolant.first().first > 90_000L) coolant.removeFirst()
            // right after reaching 80° the thermostat may still be opening and the coolant overshoots: no spike check then
            if (c < 80) warmSince = 0L else if (warmSince == 0L) warmSince = now
            // 80→88 under hard throttle is the thermostat opening late, normal; gases in the coolant show as a jump of a
            // warm engine already at 90, to 95–100 within a minute
            val from = coolant.indexOfFirst { now - it.first in 50_000L..90_000L }
            val old = coolant.getOrNull(from)?.second
            val driving = from >= 0 && (from until coolant.size).all { coolant[it].third }
            if (old != null && driving && old in 90..97 && c >= 95 && c - old >= 5 && now - warmSince > 5 * 60_000L) {
                Alerts.raise("spike", "ОЖ выросла с $old° до $c° за минуту на ходу" + (if (fan == true) " при работающем вентиляторе" else "") +
                    ". Так бывает при воздухе в системе охлаждения и при прорыве газов через прокладку ГБЦ. " +
                    "Проверьте уровень антифриза и нет ли пузырей в расширительном бачке", Alerts.Level.WARN, 20 * 60_000L)
            }
        } else if (!running) warmSince = 0L
        val atf = t?.atfC
        // the transmission's sample only while fresh: a TCU that stopped answering must not feed a gear frozen at 3
        val tFresh = t?.takeIf { now - it.timeMs in 0L..2500L }
        // a rule failing must not cost the guard's own update below
        runCatching {
            analyzer?.process(RuleInput(e, tFresh, now, iam = if (fKnock) iam else null, fbkc = if (fKnock) fbkc else null, flkc = if (fKnock) flkc else null,
                oilC = oil, turbo = turboCar, targetBoostKpa = target, sportEcu = sport, box = boxInput(),
                fanLow = v("fan1")?.let { it >= 0.5f }, fan = listOf("fan1", "fan2", "fan3").mapNotNull { v(it) }.takeIf { it.isNotEmpty() }?.any { it >= 0.5f } ?: v("fanPct")?.let { it > 5f },
                ac = v("ac")?.let { it >= 0.5f }, knockSums = (1..4).map { v("ks$it") ?: return@map null }.takeIf { l -> l.all { it != null } }?.filterNotNull(),
                ecuGear = v("gearEcu")?.toInt(), automatic = s.gearbox != "mt", displacementL = ctx?.let { RulesHost.displacement(it) },
                tripPer100 = Repo.trip.value.trip?.takeIf { it.distanceKm >= 20.0 }?.let { (it.fuelL * 100 / it.distanceKm).toFloat() }))
        }.onFailure { kg.forester.sg.ssm.SsmTrace.log("analyzer: $it") }

        // --- maintenance reminder once a day when driving off
        if (fService && running && now - maintenanceChecked > 24 * 3600_000L) {
            maintenanceChecked = now
            Maintenance.dueText()?.let { Alerts.raise("service", it, Alerts.Level.INFO, 24 * 3600_000L) }
        }

        // --- shift light
        Repo.shift.value = s.shiftLight && rpm >= s.shiftRpm

        val g = Repo.guard.value
        Repo.guard.value = GuardState(
            iam = iam, iamStart = iamStart, fbkc = fbkc, flkc = flkc, targetBoostKpa = target,
            learnedDeg = v("learned")?.takeIf { it in -20f..30f },
            oilC = oil, oilEstimated = oilEcu == null, cold = cold, turbo = turbo, turboCar = turboCar, heat = heat, cooldownSec = cooldown,
            warmupMin = warmupMin ?: g.warmupMin,
            thermostat = when {
                coldStartMs != 0L -> "прогрев: ${(now - coldStartMs) / 60_000} мин, ОЖ ${c ?: "—"}°"
                warmupMin != null -> "последний прогрев до 80°: %.0f мин".format(warmupMin)
                else -> ""
            },
            coolantMax = maxOf(g.coolantMax ?: Int.MIN_VALUE, c ?: Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            atfMax = maxOf(g.atfMax ?: Int.MIN_VALUE, atf ?: Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
        )
        updatePeaks(e, t)
        updateRange(e, t)
    }

    /** Min / max of every home card value over the trip (like BtSSM), only while the engine runs. */
    private fun updateRange(e: EcuSnapshot, t: TcuSnapshot?) {
        if ((e.rpm ?: 0) < 400) return
        val trip = Repo.trip.value
        val now = mapOf(
            "coolant" to e.coolantC?.toFloat(), "intake" to e.intakeC?.toFloat(), "battery" to e.batteryV,
            "throttle" to e.throttlePct, "maf" to e.mafGs, "afr" to e.afr, "afcorr" to e.afCorrPct, "aflearn" to e.afLearnPct,
            "timing" to e.timingDeg, "knock" to e.knockDeg, "inj" to e.injPwMs, "wgdc" to e.wastegatePct, "boost" to e.boostBar,
            "pedal" to e.pedalPct, "avcs" to e.avcsDeg, "map" to e.mapKpa, "atm" to e.atmKpa, "alt" to e.altDutyPct,
            "atf" to t?.atfC?.toFloat(), "fuel" to trip.instLh.toFloat(), "per100" to trip.instPer100?.toFloat(),
            "gps" to Repo.gps.value?.speedKmh, "load" to e.loadGRev,
            "rpm" to e.rpm?.toFloat(), "speed" to e.speedKmh?.toFloat(),
        )
        val old = Repo.tripRange.value
        val next = HashMap(old)
        for ((k, v) in now) {
            if (v == null || v.isNaN()) continue
            val r = old[k]
            next[k] = if (r == null) v to v else minOf(r.first, v) to maxOf(r.second, v)
        }
        Repo.tripRange.value = next
    }

    companion object {
        /** The standard MAP byte at its top (255 kPa absolute) with no wider parameter in its place. */
        fun mapAtLimit(mapKpa: Float?, direct: Boolean) = !direct && mapKpa != null && mapKpa >= 254.5f

        /**
         * IAM 0…1 from the value read in the parameter's [units]: RomRaider's "raw ecu value" (E1 and the 1-byte
         * ones: the ECU's 0…16) over 16, a "multiplier" (P90, E31) as it is, a percentage over 100. A driver's own
         * definition with other units: by the size of the value, as before. Null when it is no IAM at all.
         */
        fun iamOf(v: Float, units: String): Float? {
            val u = units.lowercase()
            val x = when {
                "raw" in u -> v / 16f
                "multiplier" in u -> v
                "%" in u || "percent" in u -> v / 100f
                else -> if (v > 1.5f) v / 16f else v
            }
            return x.takeIf { it in 0f..1f }
        }
    }

    /** Target boost relative to the atmosphere, whatever units this ROM's definition uses. */
    private fun targetBoost(e: EcuSnapshot): Float? {
        val p = roles["target"] ?: return null
        val raw = Repo.rrValues.value[p.id]?.toFloat() ?: return null
        val u = p.units.lowercase()
        val k = when { "bar" in u -> 100f; "psi" in u -> 6.895f; "mmhg" in u -> 0.1333f; else -> 1f }
        val atm = e.atmKpa ?: 101.3f
        return when {
            "sea level" in u -> raw * k + 101.3f - atm
            "relative" in u -> raw * k
            else -> raw * k - atm
        }
    }

    private fun updatePeaks(e: EcuSnapshot, t: TcuSnapshot?) {
        val p = Repo.peaks.value
        fun mx(a: Float?, b: Float?) = if (a == null) b else if (b == null) a else maxOf(a, b)
        fun mxi(a: Int?, b: Int?) = if (a == null) b else if (b == null) a else maxOf(a, b)
        val lean = e.afr?.takeIf { (e.gaugeKpa ?: 0f) > 30f }
        Repo.peaks.value = p.copy(
            boostBar = mx(p.boostBar, e.boostBar),
            mafGs = mx(p.mafGs, e.mafGs),
            loadGRev = mx(p.loadGRev, e.loadGRev),
            rpm = mxi(p.rpm, e.rpm),
            speedKmh = mxi(p.speedKmh, e.speedKmh),
            intakeC = mxi(p.intakeC, e.intakeC),
            coolantC = mxi(p.coolantC, e.coolantC),
            atfC = mxi(p.atfC, t?.atfC),
            knockDeg = e.knockDeg?.let { k -> p.knockDeg?.let { minOf(it, k) } ?: k },
            leanAfrUnderBoost = mx(p.leanAfrUnderBoost, lean),
        )
    }

    /** One IAM reading per drive, with the date: shows after which refill it went down. */
    private fun logIam(now: Long, iam: Float) {
        val s = Repo.settings
        val arr = runCatching { JSONArray(s.iamLog) }.getOrDefault(JSONArray())
        val last = if (arr.length() > 0) arr.getJSONObject(arr.length() - 1) else null
        if (last != null && kotlin.math.abs(last.getDouble("v") - iam) < 0.01 && now - last.getLong("t") < 7 * 24 * 3600_000L) return
        arr.put(JSONObject().put("t", now).put("v", iam.toDouble()))
        while (arr.length() > 60) arr.remove(0)
        s.iamLog = arr.toString()
    }
}
