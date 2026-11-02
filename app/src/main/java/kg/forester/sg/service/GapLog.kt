package kg.forester.sg.service

/**
 * How evenly something comes (GPS fixes, ECU samples, frames of the map), for the log the owner of a car can send: a
 * line for a gap of [slowMs] or more, a few a window at most, and every [everyMs] a line with how the gaps were.
 * Nothing is allocated between those lines.
 */
class GapLog(private val label: String, private val slowMs: Int, private val everyMs: Long = 60_000L, private val out: (String) -> Unit) {
    private val gaps = IntArray(1024)
    private var count = 0
    private var slow = 0
    private var max = 0
    private var lastMs = NONE
    private var windowMs = NONE

    @Synchronized
    fun tick(nowMs: Long) {
        val before = lastMs
        lastMs = nowMs
        if (before == NONE || nowMs < before) {
            if (windowMs == NONE) windowMs = nowMs
            return
        }
        val gap = (nowMs - before).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        gaps[count % gaps.size] = gap
        count++
        if (gap > max) max = gap
        if (gap >= slowMs) {
            if (slow < SLOW_LINES) out("$label gap $gap ms")
            slow++
        }
        if (nowMs - windowMs >= everyMs) {
            val sorted = gaps.copyOf(minOf(count, gaps.size)).also { it.sort() }
            fun at(q: Float) = sorted[((sorted.size - 1) * q).toInt()]
            val tail = if (count > gaps.size) " of the last ${gaps.size}" else ""
            out("$label gaps: $count in ${(nowMs - windowMs) / 1000} s, p50 ${at(0.5f)}, p95 ${at(0.95f)}$tail, max $max, over $slowMs ms: $slow")
            count = 0
            slow = 0
            max = 0
            windowMs = nowMs
        }
    }

    /** It stopped on purpose (nothing to move): the time until the next tick is no gap. */
    @Synchronized
    fun restart() {
        lastMs = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val SLOW_LINES = 3
    }
}
