package kg.forester.sg.ssm

/** Where a failing head gasket of this engine usually shows: gases into the coolant, coolant out of the block, not known. */
enum class GasketMode { INTERNAL, EXTERNAL, UNKNOWN }

/**
 * What is normal for this engine's cooling, and its redline: the thermostat fully open [thermoFullC], the fan's high
 * speed [fanHighC], from where a coolant rise counts [spikeFromC] and how big [spikeRiseC], from where a creep in
 * traffic counts [fanCreepFromC]. [sure] is false when part of the numbers is assumed, not from a factory source.
 */
data class EngineProfile(
    val key: String,
    val thermoFullC: Int,
    val fanHighC: Int,
    val spikeFromC: Int,
    val spikeRiseC: Int = 8,
    val fanCreepFromC: Int,
    val redlineRpm: Int?,
    val gasketMode: GasketMode,
    val sure: Boolean,
) {
    /** The rev scale: the top of a tachometer and where its red zone starts; the usual 7000 and 6000 with no known redline. */
    val rpmTop: Float get() = redlineRpm?.let { EngineProfiles.rpmScaleTop(it).toFloat() } ?: 7000f
    val rpmRedFrom: Float get() = redlineRpm?.toFloat() ?: 6000f
}

object EngineProfiles {
    // the 91° thermostat: a rise counts from 96°
    private val EJ_NA_OLD = EngineProfile("EJ_NA_OLD", 91, 97, 96, fanCreepFromC = 92, redlineRpm = null, gasketMode = GasketMode.INTERNAL, sure = false)
    private val EJ_T = EngineProfile("EJ_T", 91, 97, 96, fanCreepFromC = 92, redlineRpm = null, gasketMode = GasketMode.UNKNOWN, sure = true)
    private val EJ_T_STI = EngineProfile("EJ_T_STI", 91, 97, 96, fanCreepFromC = 92, redlineRpm = null, gasketMode = GasketMode.UNKNOWN, sure = false)
    // the 95° thermostat: 90 to 95 is its normal band, and the fan starts about there, so a rise counts from 100°
    private val EJ_NA = EngineProfile("EJ_NA", 95, 97, 100, fanCreepFromC = 96, redlineRpm = null, gasketMode = GasketMode.EXTERNAL, sure = true)
    private fun assumed(key: String, redline: Int? = null) =
        EngineProfile(key, 95, 97, 100, fanCreepFromC = 96, redlineRpm = redline, gasketMode = GasketMode.UNKNOWN, sure = false)

    /** Nothing known of the engine (a sports ECU, another make, no ECU yet). */
    val GENERIC = assumed("GENERIC")

    /** The profile by the code with its turbo and year, else by the family and turbo, else by the turbo alone. */
    fun of(e: EngineInfo): EngineProfile {
        // a sports ECU or no ECU says nothing of the engine, boost or not; the diesel has no numbers of its own
        if (e.source == "Link" || e.source == "нет" || e.diesel) return GENERIC
        return byCode(e.code, e.turbo, e.car?.let { EngineId.yearOf(it) }) ?: byFamily(e.family, e.turbo) ?: if (e.turbo) EJ_T else GENERIC
    }

    private fun byCode(code: String, turbo: Boolean, year: Int?): EngineProfile? = if (turbo) when (code) {
        "EJ205" -> EJ_T.copy(redlineRpm = 7000)
        "EJ20X/EJ20Y", "EJ255" -> EJ_T
        "EJ207" -> EJ_T_STI.copy(redlineRpm = 8000)
        "EJ257" -> EJ_T_STI.copy(redlineRpm = 6700)
        "FA20DIT" -> assumed("FA20DIT", 6700)
        else -> null
    } else when (code) {
        "EJ22", "EJ25D" -> EJ_NA_OLD
        // where inside MY2001–2003 the thermostat changed isn't known: 2001 and 2002 take the less alarming one
        "EJ251" -> if (year != null && year < 2001) EJ_NA_OLD else EJ_NA
        "EJ252", "EJ253" -> EJ_NA
        "FA20" -> assumed("FA20", 7400)
        else -> null
    }

    private fun byFamily(family: String, turbo: Boolean): EngineProfile? = when (family) {
        "EJ20", "EJ25" -> if (turbo) EJ_T else EJ_NA
        "EJ22" -> EJ_NA_OLD
        "EZ30" -> assumed("EZ30", 6500)
        "EZ36" -> assumed("EZ36")
        "FB" -> assumed("FB")
        "FA20" -> if (turbo) assumed("FA20DIT", 6700) else assumed("FA20", 7400)
        else -> null
    }

    /** The profile before this drive's first sample: the last connected engine's, unless the car is taken for another make. */
    fun seed(saved: EngineInfo?, otherMake: Boolean) = if (otherMake || saved == null) GENERIC else of(saved)

    /** The top of a tachometer for this redline: the next thousand above it, so the red zone has room. */
    fun rpmScaleTop(redlineRpm: Int) = (redlineRpm / 1000 + 1) * 1000
}
