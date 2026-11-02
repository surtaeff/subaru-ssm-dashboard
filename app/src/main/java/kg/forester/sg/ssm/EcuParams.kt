package kg.forester.sg.ssm

/** One decoded snapshot of the engine state. Null = not available. */
data class EcuSnapshot(
    val timeMs: Long = 0,
    val coolantC: Int? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val timingDeg: Float? = null,
    val intakeC: Int? = null,
    val mafGs: Float? = null,
    val throttlePct: Float? = null,
    val batteryV: Float? = null,
    val injPwMs: Float? = null,
    /** The live timing pull: P23 until the guard replaces it with the FBKC where the ROM has one (see EngineGuard.livePull). */
    val knockDeg: Float? = null,
    /** P23 as the ECU gives it: the knock correction advance, KA x IAM + FBKC + FLKC, a sum and no live pull. */
    val kcaDeg: Float? = null,
    val atmKpa: Float? = null,
    val boostKpa: Float? = null,
    val afCorrPct: Float? = null,
    val afLearnPct: Float? = null,
    val lambda: Float? = null,
    val wastegatePct: Float? = null,
    val pedalPct: Float? = null,
    val mapKpa: Float? = null,
    val avcsDeg: Float? = null,
    val altDutyPct: Float? = null,
    val throttleMotorPct: Float? = null,
    /** Fuel level sender, only on ECUs that report it (not the SG). */
    val fuelSensorV: Float? = null,
    val fuelSensorOhm: Float? = null,
    /** Fuel level in percent of the tank, from OBD-II (PID 2F). */
    val fuelLevelPct: Float? = null,
    /** OBD-II fuel system status (PID 03, system 1): 2 closed loop, 4 open loop by load or the deceleration cut. */
    val fuelSystem: Int? = null,
    /** OBD-II engine fuel rate, L/h (PID 5E), on the units that have it. */
    val fuelRateLh: Float? = null,
    /** Calculated engine load, percent (OBD-II PID 04). */
    val engineLoadPct: Float? = null,
) {
    val afr: Float? get() = lambda?.let { it * 14.7f }

    /** Gauge pressure = manifold absolute − atmospheric, kPa (negative = vacuum). */
    val gaugeKpa: Float?
        get() = if (mapKpa != null && atmKpa != null) mapKpa - atmKpa else boostKpa

    /** Boost above atmosphere in bar, never negative. */
    val boostBar: Float? get() = gaugeKpa?.let { maxOf(0f, it) / 100f }

    /** Manifold vacuum in kPa (0 when under boost). */
    val vacuumKpa: Float? get() = gaugeKpa?.let { maxOf(0f, -it) }

    /** Engine load, grams of air per revolution. */
    val loadGRev: Float?
        get() = if (mafGs != null && rpm != null && rpm > 300) mafGs * 60f / rpm else null

    /** Injector duty cycle (batch: one injection per 2 revolutions). */
    val injDutyPct: Float?
        get() = if (injPwMs != null && rpm != null) injPwMs * rpm / 1200f else null

    /**
     * Drops a MAF reading no engine of these cars can pull: a 2.5 l at 2.5 bar absolute takes ~3.8 g per rev
     * (0.063 g/s per rpm), plus a margin for idle. A garbled answer or an address this ROM keeps elsewhere
     * (0xFFFF = 655 g/s) would otherwise show 200+ l/h at idle and spoil the trip, refills and calibration.
     * The top follows the MAF scale this car has learned ([learnedTop], g/s: kg.forester.sg.data.Scales.maf): 400 on
     * a stock one, up to [MAF_CEIL] on a car that really flows more (a big turbo and MAF, 450+ hp), never 0xFFxx.
     */
    fun saneMaf(learnedTop: Float = 0f): EcuSnapshot {
        val maf = mafGs ?: return this
        val top = maxOf(400f, learnedTop + 100f).coerceAtMost(MAF_CEIL)
        return if (maf > (rpm ?: 0) * 0.07f + 15f || maf > top) copy(mafGs = null) else this
    }
}

/**
 * Standard SSM2 single-byte addresses (RomRaider logger "STD" set) polled in one request.
 * Order matters: values come back in the same order.
 */
/** The highest MAF taken as real, g/s: two bytes of 0xFF.. read as 652.8+ are junk on any car. */
const val MAF_CEIL = 640f

object EcuParams {
    const val COOLANT = 0x08
    const val RPM_H = 0x0E
    const val RPM_L = 0x0F
    const val SPEED = 0x10
    const val TIMING = 0x11
    const val INTAKE = 0x12
    const val MAF_H = 0x13
    const val MAF_L = 0x14
    const val THROTTLE = 0x15
    const val BATTERY = 0x1C
    const val INJ_PW = 0x20
    const val KNOCK = 0x22
    const val ATM = 0x23
    const val BOOST = 0x24
    const val AF_CORR = 0x09
    const val AF_LEARN = 0x0A
    const val AF_SENSOR = 0x46
    const val WGDC = 0x30
    const val PEDAL = 0x29
    const val MAP = 0x0D
    const val AVCS = 0x3C
    const val ALT_DUTY = 0x3A
    const val THROTTLE_MOTOR = 0xFA
    const val FUEL_LEVEL_V = 0x2E
    const val FUEL_LEVEL_OHM = 0x10D

    val pollAddresses = intArrayOf(
        COOLANT, RPM_H, RPM_L, SPEED, TIMING, INTAKE, MAF_H, MAF_L, THROTTLE, BATTERY,
        INJ_PW, KNOCK, ATM, BOOST, AF_CORR, AF_LEARN, AF_SENSOR, WGDC,
        PEDAL, MAP, AVCS, ALT_DUTY, THROTTLE_MOTOR,
    )

    /** Capability bit of each address: (index in the init capability bytes, bit), from FreeSSM. */
    private val capability = mapOf(
        COOLANT to (0 to 6), RPM_H to (0 to 0), RPM_L to (0 to 0), SPEED to (1 to 7), TIMING to (1 to 6),
        INTAKE to (1 to 5), MAF_H to (1 to 4), MAF_L to (1 to 4), THROTTLE to (1 to 3), BATTERY to (2 to 7),
        INJ_PW to (2 to 3), KNOCK to (2 to 1), ATM to (2 to 0), BOOST to (3 to 7), AF_CORR to (0 to 5),
        AF_LEARN to (0 to 4), AF_SENSOR to (7 to 5), WGDC to (4 to 3), PEDAL to (3 to 2), MAP to (0 to 1),
        AVCS to (6 to 7), ALT_DUTY to (5 to 1), THROTTLE_MOTOR to (30 to 5),
    )

    /** Asked for only when the init says the ECU has them, never part of the base list. */
    private val optional = mapOf(FUEL_LEVEL_V to (4 to 5), FUEL_LEVEL_OHM to (33 to 2))

    fun optionalSupported(init: EcuInit): IntArray =
        optional.filter { (_, c) -> init.supports(c.first + 8, c.second) }.keys.toIntArray()

    /** False only when the init clearly lists the capability byte and the bit is off (then the byte is junk). */
    fun reported(init: EcuInit, addr: Int): Boolean {
        val (i, bit) = capability[addr] ?: return true
        return i >= init.capabilities.size || init.supports(i + 8, bit)
    }

    /**
     * Addresses in [addrs] the init clearly marks as not supported: SSM answers them anyway with junk (an EJ253
     * showed "knock correction +63.5°"), so their values are dropped.
     */
    fun unsupported(init: EcuInit, addrs: IntArray): Set<Int> = addrs.filter { !reported(init, it) }.toSet()

    /** The poll addresses this ECU reports as supported in its init answer. */
    fun supported(init: EcuInit): IntArray =
        pollAddresses.filter { a -> capability[a]?.let { (i, bit) -> init.supports(i + 8, bit) } ?: false }.toIntArray()

    /** Decodes a read of [addrs]; parameters the ECU was not asked for stay null. */
    fun decode(addrs: IntArray, raw: ByteArray, now: Long, skip: Set<Int> = emptySet()): EcuSnapshot {
        val v = HashMap<Int, Int>(addrs.size)
        addrs.forEachIndexed { i, a -> if (i < raw.size && a !in skip) v[a] = raw[i].toInt() and 0xFF }
        // knock correction 0xFF (+63.5°) is what an ECU without the parameter sends back
        if (v[KNOCK] == 0xFF) v.remove(KNOCK)
        fun g(a: Int) = v[a]
        fun pair(h: Int, l: Int) = g(h)?.let { hi -> g(l)?.let { lo -> (hi shl 8) or lo } }
        return EcuSnapshot(
            timeMs = now,
            coolantC = g(COOLANT)?.let { it - 40 },
            rpm = pair(RPM_H, RPM_L)?.let { it / 4 },
            speedKmh = g(SPEED),
            timingDeg = g(TIMING)?.let { (it - 128) / 2f },
            intakeC = g(INTAKE)?.let { it - 40 },
            mafGs = pair(MAF_H, MAF_L)?.let { it / 100f },
            throttlePct = g(THROTTLE)?.let { it * 100f / 255f },
            batteryV = g(BATTERY)?.let { it * 0.08f },
            injPwMs = g(INJ_PW)?.let { it * 0.256f },
            knockDeg = g(KNOCK)?.let { (it - 128) / 2f },
            kcaDeg = g(KNOCK)?.let { (it - 128) / 2f },
            atmKpa = g(ATM)?.toFloat(),
            boostKpa = g(BOOST)?.let { (it - 128).toFloat() },
            afCorrPct = g(AF_CORR)?.let { (it - 128) * 100f / 128f },
            afLearnPct = g(AF_LEARN)?.let { (it - 128) * 100f / 128f },
            lambda = g(AF_SENSOR)?.takeIf { it != 0 }?.let { it / 128f },
            wastegatePct = g(WGDC)?.let { it * 100f / 255f },
            pedalPct = g(PEDAL)?.let { it * 100f / 255f },
            mapKpa = g(MAP)?.toFloat(),
            avcsDeg = g(AVCS)?.let { (it - 50).toFloat() },
            altDutyPct = g(ALT_DUTY)?.toFloat(),
            throttleMotorPct = g(THROTTLE_MOTOR)?.let { (it - 128) * 100f / 128f },
            fuelSensorV = g(FUEL_LEVEL_V)?.let { it / 50f },
            fuelSensorOhm = g(FUEL_LEVEL_OHM)?.let { it * 2f },
        )
    }
}
