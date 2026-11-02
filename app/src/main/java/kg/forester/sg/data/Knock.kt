package kg.forester.sg.data

/**
 * One timing pull by the ECU: the knock correction went down to [KnockStats.START_DEG] or lower and stayed
 * negative until [durationMs] later. The engine values are taken at the deepest point of the pull.
 */
data class KnockEvent(
    val startMs: Long,
    val durationMs: Long = 0,
    val maxPullDeg: Float,
    val rpm: Int?,
    val loadGRev: Float?,
    val throttlePct: Float?,
    val speedKmh: Int?,
    val coolantC: Int?,
    val timingDeg: Float?,
    val active: Boolean = true,
    /** «Похоже на кочку», see [BumpRule]: still kept and written, out of the headline count only. */
    val bump: Boolean = false,
    /** The strongest vertical shock around the pull, g; null with no accelerometer. Logged so the limit can be checked. */
    val shockG: Float? = null,
)

/**
 * What a pull looked like from start to end, beside the values at its deepest point. Null anywhere = not known,
 * and what isn't known can't speak for a bump.
 */
data class KnockSeen(
    val maxBoostKpa: Float? = null,
    val maxLoad: Float? = null,
    val minThrottle: Float? = null,
    val maxThrottle: Float? = null,
    val flkcStart: Float? = null,
    val iamStart: Float? = null,
    val flkcEnd: Float? = null,
    val iamEnd: Float? = null,
) {
    fun with(boostKpa: Float?, load: Float?, throttle: Float?, flkc: Float?, iam: Float?) = copy(
        maxBoostKpa = boostKpa?.let { b -> maxBoostKpa?.let { maxOf(it, b) } ?: b } ?: maxBoostKpa,
        maxLoad = load?.let { l -> maxLoad?.let { maxOf(it, l) } ?: l } ?: maxLoad,
        minThrottle = throttle?.let { t -> minThrottle?.let { minOf(it, t) } ?: t } ?: minThrottle,
        maxThrottle = throttle?.let { t -> maxThrottle?.let { maxOf(it, t) } ?: t } ?: maxThrottle,
        flkcEnd = flkc, iamEnd = iam,
    )
}

/**
 * A rough road read as knock: a label, never a deletion. All must hold; the shock limit is a guess, logged with each
 * event so that it can be checked. Existing alerts don't look at this.
 */
object BumpRule {
    const val SHOCK_G = 0.35f
    /** The shock counts this close to the pull. */
    const val SHOCK_NEAR_MS = 500L
    /** No other pull this close, before or after. */
    const val QUIET_MS = 10_000L
    const val MAX_MS = 1000L
    const val MAX_BOOST_KPA = 20f
    const val MAX_LOAD = 1f
    /** Below full throttle: the throttle a pull is judged by, kept under what the rules call a pull. */
    const val MAX_THROTTLE = 80f
    /** Throttle "steady" over the pull and the sample before it, percentage points. */
    const val STEADY_THROTTLE = 5f

    fun isBump(e: KnockEvent, seen: KnockSeen, neighbour: Boolean): Boolean {
        val boost = seen.maxBoostKpa ?: return false
        val load = seen.maxLoad ?: return false
        val low = seen.minThrottle ?: return false
        val high = seen.maxThrottle ?: return false
        val shock = e.shockG ?: return false
        if (seen.flkcStart == null || seen.iamStart == null) return false
        return !neighbour && e.maxPullDeg > -2f && e.durationMs < MAX_MS && boost <= MAX_BOOST_KPA && load < MAX_LOAD &&
            high < MAX_THROTTLE && high - low <= STEADY_THROTTLE && seen.flkcEnd == seen.flkcStart && seen.iamEnd == seen.iamStart &&
            shock >= SHOCK_G
    }
}

/** Timing pulls since the current trip started. */
data class KnockStats(val sinceMs: Long = 0, val events: List<KnockEvent> = emptyList()) {
    /** The headline: the pulls not marked as a bump. */
    val count get() = events.count { !it.bump }
    val bumpCount get() = events.count { it.bump }
    /** The deepest pull of the trip, a negative number of degrees. */
    val maxPullDeg get() = events.filter { !it.bump }.minOfOrNull { it.maxPullDeg }

    companion object {
        /**
         * Any pull counts, the ECU's smallest steps too (0.35° on the 16-bit ones, 0.5° in the standard byte):
         * with -1° the small pulls BTSSM shows never got here. Flicker is one event, see [MERGE_MS].
         */
        const val START_DEG = -0.3f
        /** A pull starting this soon after the last one ended carries on that event instead of counting again. */
        const val MERGE_MS = 3000L
    }
}

/** Pulls in one cell of the [KnockMap]: real ones, ones at an automatic gearbox shift, the deepest of them. */
data class KnockCell(val count: Int = 0, val shift: Int = 0, val worstDeg: Float = 0f)

/**
 * Timing pulls by where they happened, engine speed × load (g/rev), a count per cell: the knock map owners read in
 * BTSSM. Pulls on a lifted foot, at light load below 3000 rpm and on a cold engine never get here (noise, see
 * engine-ej K07, K08, K13); those within ~0.5 s of an automatic gearbox shift are counted apart in [KnockCell.shift]:
 * mostly the valve body's click or the TCU's own torque cut, not knock (engine-ej K16).
 */
data class KnockMap(val sinceMs: Long = 0, val cells: Map<Int, KnockCell> = emptyMap()) {
    val total get() = cells.values.sumOf { it.count }
    val shiftTotal get() = cells.values.sumOf { it.shift }
    val worstDeg get() = cells.values.minOfOrNull { it.worstDeg }

    fun cell(row: Int, col: Int) = cells[row * COLS + col]

    fun add(rpm: Int, load: Float, deg: Float, atShift: Boolean): KnockMap {
        val i = indexOf(rpm, load) ?: return this
        val c = cells[i] ?: KnockCell()
        val next = if (atShift) c.copy(shift = c.shift + 1) else c.copy(count = c.count + 1, worstDeg = minOf(c.worstDeg, deg))
        return copy(cells = cells + (i to next))
    }

    fun toJson(): String {
        val a = org.json.JSONArray()
        cells.forEach { (i, c) -> a.put(org.json.JSONArray().put(i).put(c.count).put(c.shift).put(c.worstDeg.toDouble())) }
        return org.json.JSONObject().put("since", sinceMs).put("cells", a).toString()
    }

    companion object {
        /** Lower edges of the columns, rpm; the last one is open. */
        val RPM = IntArray(12) { 1000 + it * 500 }
        /** Lower edges of the rows, g/rev, bottom to top; the last one is open. */
        val LOAD = floatArrayOf(0f, 0.6f, 0.8f, 1.0f, 1.2f, 1.4f, 1.7f, 2.0f, 2.4f)
        val COLS = RPM.size
        val ROWS = LOAD.size

        fun indexOf(rpm: Int, load: Float): Int? {
            if (rpm < RPM[0] || load.isNaN()) return null
            val col = RPM.indexOfLast { rpm >= it }
            val row = LOAD.indexOfLast { load >= it }.coerceAtLeast(0)
            return row * COLS + col
        }

        fun fromJson(s: String): KnockMap = runCatching {
            val o = org.json.JSONObject(s)
            val a = o.getJSONArray("cells")
            KnockMap(o.optLong("since"), (0 until a.length()).associate { k ->
                val c = a.getJSONArray(k)
                c.getInt(0) to KnockCell(c.getInt(1), c.getInt(2), c.getDouble(3).toFloat())
            })
        }.getOrDefault(KnockMap())
    }
}
