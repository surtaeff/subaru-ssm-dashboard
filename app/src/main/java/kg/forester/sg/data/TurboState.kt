package kg.forester.sg.data

/** What is known of a turbo: no evidence yet, a turbo, or a car shown to have none. */
enum class TurboState { UNKNOWN, TURBO, NON_TURBO }

/** The evidence for [TurboState] on another make over OBD-II, in one place. */
object TurboRules {
    /** MAP over the barometric pressure by this much is boost. */
    const val BOOST_OVER_BARO_KPA = 10f
    /** ... held this long, so one glitch doesn't latch it. */
    const val BOOST_HOLD_MS = 1000L
    /** Barometric pressure when PID 33 isn't there. */
    const val BARO_DEFAULT_KPA = 101f
    /** High load for the no-turbo evidence: PID 04 at least this, or the throttle at least [WOT_PCT]. */
    const val HIGH_LOAD_PCT = 85f
    const val WOT_PCT = 90f
    const val HIGH_LOAD_RPM = 3000
    /** MAP at or below baro plus this while under high load: the engine breathes without help. */
    const val NO_BOOST_OVER_BARO_KPA = 3f
    /** The high-load, no-boost time that makes a car non-turbo, in total across samples. */
    const val NON_TURBO_TOTAL_MS = 3000L
    /** A real gap longer than this (the link was out) restarts the sustained-boost test; K-line gives a sample every 2 s or so. */
    const val MAX_RUN_GAP_MS = 15_000L
    /** One sample counts for no more than this much time: a slow bus must not give a whole cycle to one reading. */
    const val SAMPLE_CAP_MS = 3000L
    /** A sample counts only with MAP in this range (0xFF is a junk byte), revs above [MIN_RPM], baro in range or absent. */
    const val MAP_MIN_KPA = 15f
    const val MAP_MAX_KPA = 250f
    const val MIN_RPM = 1500
    const val BARO_MIN_KPA = 70f
    const val BARO_MAX_KPA = 110f
    /** Under high load with no boost, MAP is near baro: from baro minus this to baro plus [NO_BOOST_OVER_BARO_KPA] (vacuum cruise isn't). */
    const val NO_BOOST_UNDER_BARO_KPA = 10f

    /** The OBD-II boost PIDs: turbocharger compressor inlet pressure, boost pressure control, variable geometry turbo. */
    val BOOST_PIDS = setOf(0x6F, 0x70, 0x71)
    const val MAP_PID = 0x0B
}

/**
 * Whether a car whose ECU answers an init has a turbo: the one answer for the layout, the guard and the engine's title.
 * Real boost is a turbo whatever else says. Then the ROM, when it tells. A ROM nobody knows: the wastegate bit, but only of an
 * init long enough to carry that byte, since a short one can't say either way (EcuParams.reported says yes to what it
 * doesn't list). So not a turbo until boost is really seen.
 */
fun ecuTurbo(romTurbo: Boolean?, wastegateReported: Boolean, longInit: Boolean, boostSeen: Boolean): Boolean =
    boostSeen || (romTurbo ?: (wastegateReported && longInit))

/** What the PIDs a unit lists say: a boost PID is a turbo at once; no MAP PID at all is a car with no boost to show. */
fun turboFromPids(supported: Set<Int>): TurboState? = when {
    supported.isEmpty() -> null
    supported.any { it in TurboRules.BOOST_PIDS } -> TurboState.TURBO
    TurboRules.MAP_PID !in supported -> TurboState.NON_TURBO
    else -> null
}

/**
 * Accumulates the evidence from the samples. [sample] gives the state after this one: a turbo stays a turbo; a real
 * boost (two or more consecutive plausible samples spanning [TurboRules.BOOST_HOLD_MS]) moves anything to TURBO; high
 * load with MAP near baro, in total, makes NON_TURBO; everything else changes nothing. The load PID is slow on K-line, so
 * the MAP of the sample itself must agree: a vacuum cruise never counts, however old the load is.
 */
class TurboWatch {
    private var boostSince: Long? = null
    private var boosted = 0
    private var lastMs: Long? = null
    private var noBoostMs = 0L

    fun reset() { boostSince = null; boosted = 0; lastMs = null; noBoostMs = 0L }

    fun sample(nowMs: Long, state: TurboState, rpm: Int?, mapKpa: Float?, baroKpa: Float?, loadPct: Float?, throttlePct: Float?): TurboState {
        val dt = lastMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        lastMs = nowMs
        if (dt > TurboRules.MAX_RUN_GAP_MS) { boostSince = null; boosted = 0 }
        val plausible = mapKpa != null && mapKpa in TurboRules.MAP_MIN_KPA..TurboRules.MAP_MAX_KPA && (rpm ?: 0) > TurboRules.MIN_RPM
        if (!plausible || mapKpa == null) { boostSince = null; boosted = 0; return state }
        val baro = baroKpa?.takeIf { it in TurboRules.BARO_MIN_KPA..TurboRules.BARO_MAX_KPA } ?: TurboRules.BARO_DEFAULT_KPA
        if (mapKpa - baro >= TurboRules.BOOST_OVER_BARO_KPA) {
            val since = boostSince ?: nowMs.also { boostSince = it }
            boosted++
            return if (boosted >= 2 && nowMs - since >= TurboRules.BOOST_HOLD_MS) TurboState.TURBO else state
        }
        boostSince = null; boosted = 0
        if (state == TurboState.TURBO) return state
        val heavy = (loadPct ?: 0f) >= TurboRules.HIGH_LOAD_PCT || (throttlePct ?: 0f) >= TurboRules.WOT_PCT
        val breathing = mapKpa in (baro - TurboRules.NO_BOOST_UNDER_BARO_KPA)..(baro + TurboRules.NO_BOOST_OVER_BARO_KPA)
        if (heavy && (rpm ?: 0) >= TurboRules.HIGH_LOAD_RPM && breathing) {
            if (dt <= TurboRules.MAX_RUN_GAP_MS) noBoostMs += minOf(dt, TurboRules.SAMPLE_CAP_MS)
            if (noBoostMs >= TurboRules.NON_TURBO_TOTAL_MS) return TurboState.NON_TURBO
        }
        return state
    }
}

/**
 * What the app itself last wrote as another make's home layout, for which car and which turbo state: only a layout that
 * is still exactly this text is the app's to rewrite; anything else is the driver's.
 */
data class OtherLayoutMark(val car: String, val state: TurboState, val text: String) {
    fun format() = "$car\n${state.name}\n$text"

    companion object {
        fun parse(s: String): OtherLayoutMark? {
            val f = s.split('\n', limit = 3)
            if (f.size != 3) return null
            return OtherLayoutMark(f[0], TurboState.entries.firstOrNull { it.name == f[1] } ?: return null, f[2])
        }
    }
}

/**
 * Whether another make's home layout is to be written now: an empty one the first time, and afterwards only if it is
 * still exactly what the app wrote ([mark]) and the applied turbo state, or the car, is not the one it was written for.
 * A layout the driver changed in any way is never touched.
 */
fun shouldRewriteLayout(mark: OtherLayoutMark?, car: String, applied: TurboState, saved: String): Boolean = when {
    saved.isBlank() -> true
    mark == null -> false
    saved != mark.text -> false
    else -> mark.car != car || mark.state != applied
}
