package kg.forester.sg.data

/**
 * What boost is usual for this car, learned from the car itself, for another make over OBD-II, which gives no boost target.
 * A pull is absolute MAP (PID 0B) above baro + [PULL_OVER_BARO_KPA] for at least [PULL_MS]; its value is the highest
 * held for at least [HELD_MS]. The last [KEEP] pulls of this VIN are kept; the norm is their 80th percentile, once there are
 * [MIN_PULLS]. MAP above norm + [WARN_OVER_NORM_KPA] for [HELD_MS] is reported by [sample].
 *
 * Absolute on purpose: in thin air the gauge pressure rises while a wastegate holds the absolute one, and that must not alarm.
 * A pull that was warned about is recorded like any other, so a re-tuned car stops warning after a few pulls.
 * What counts as a reading is [TurboRules]'s plausibility; a sample out of it says nothing, it neither ends nor feeds a pull.
 */
class BoostNorm(pulls: List<Float> = emptyList(), car: String = "") {
    /** Over the norm now: both numbers, for the text. */
    data class Over(val mapKpa: Float, val normKpa: Float)

    var car = car
        private set
    private val kept = ArrayList(pulls.takeLast(KEEP))
    val pulls: List<Float> get() = kept
    val count get() = kept.size

    /** Changes when what [format] gives does, so the store is written only then. */
    var revision = 0
        private set

    val norm: Float? get() {
        if (kept.size < MIN_PULLS) return null
        val s = kept.sorted()
        return s[kotlin.math.ceil(s.size * 0.8).toInt() - 1]
    }

    private var pullSince = NONE
    private var lastMs = NONE
    private var best = Float.NEGATIVE_INFINITY
    private val window = ArrayDeque<Pair<Long, Float>>()

    /** [id]: the car's VIN; "" says nothing of a change. Another VIN starts over. */
    fun forCar(id: String) {
        if (id.isEmpty() || id == car) return
        if (car.isNotEmpty()) { kept.clear(); endPull(false) }
        car = id
        revision++
    }

    fun sample(nowMs: Long, mapKpa: Float?, baroKpa: Float?, rpm: Int): Over? {
        if (lastMs != NONE && nowMs - lastMs > TurboRules.MAX_RUN_GAP_MS) endPull(true)
        if (mapKpa == null || mapKpa !in TurboRules.MAP_MIN_KPA..TurboRules.MAP_MAX_KPA) return null
        lastMs = nowMs
        val baro = baroKpa?.takeIf { it in TurboRules.BARO_MIN_KPA..TurboRules.BARO_MAX_KPA } ?: TurboRules.BARO_DEFAULT_KPA
        if (rpm <= TurboRules.MIN_RPM || mapKpa <= baro + PULL_OVER_BARO_KPA) { endPull(true); return null }
        if (pullSince == NONE) pullSince = nowMs
        window.addLast(nowMs to mapKpa)
        // the newest sample at least HELD_MS old stays as the start of what has been held
        while (window.size > 1 && nowMs - window[1].first >= HELD_MS) window.removeFirst()
        if (nowMs - window.first().first < HELD_MS) return null
        val held = window.minOf { it.second }
        if (held > best) best = held
        val n = norm ?: return null
        return if (held > n + WARN_OVER_NORM_KPA) Over(held, n) else null
    }

    private fun endPull(record: Boolean) {
        val long = lastMs != NONE && pullSince != NONE && window.isNotEmpty() && window.last().first - pullSince >= PULL_MS
        if (record && long && best > Float.NEGATIVE_INFINITY) {
            kept += best
            while (kept.size > KEEP) kept.removeAt(0)
            revision++
        }
        pullSince = NONE; best = Float.NEGATIVE_INFINITY; window.clear()
    }

    fun format() = car + "\n" + kept.joinToString(",") { it.toString() }

    companion object {
        const val PULL_OVER_BARO_KPA = 30f
        const val PULL_MS = 1500L
        const val HELD_MS = 1000L
        const val KEEP = 10
        const val MIN_PULLS = 5
        const val WARN_OVER_NORM_KPA = 25f
        private const val NONE = Long.MIN_VALUE

        fun parse(s: String): BoostNorm {
            val f = s.split('\n', limit = 2)
            if (f.size != 2) return BoostNorm()
            return BoostNorm(f[1].split(',').mapNotNull { it.trim().toFloatOrNull() }, f[0])
        }
    }
}
