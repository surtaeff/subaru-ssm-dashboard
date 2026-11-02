package kg.forester.sg.ui

import kg.forester.sg.service.Gears
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Radii of the gauge «Линза», as shares of half its side, and what is shown at this size. */
data class LensLayout(
    val outer: Boolean, val drum: Boolean,
    val ring: Float, val lens: Float, val disk: Float, val arc: Float,
)

/** The arithmetic of the gauge «Линза»; the drawing is in LensDial.kt. Angles: 0 = 3 o'clock, clockwise. */
object LensMath {
    /** The crescent's whole scale, from lower left over the left side to upper right. */
    const val START_DEG = 125f
    const val SWEEP_DEG = 200f

    /** The thin outer arc, on the left. */
    const val ARC_START_DEG = 140f
    const val ARC_SWEEP_DEG = 80f

    /** Under this side (dp) the outer arc goes, its name and labels would not read; under the next the drum too. */
    const val OUTER_MIN_DP = 240f
    const val DRUM_MIN_DP = 130f

    /** Shorter than [DIGITS_MS], so a roll has ended before the next number may come. */
    const val ROLL_MS = 160L

    /** A number that lasted less than this before the next one changes without a roll: the value is moving too fast for it. */
    const val ROLL_SPACING_MS = 320L

    /** How much of the outer arc lights just over the line, and at the far end of the warning zone. */
    const val WARN_MIN = 0.3f
    const val WARN_MAX = 0.75f

    /** Width of a digit of the light face, in em. */
    const val DIGIT_EM = 0.56f

    fun layout(sideDp: Float): LensLayout =
        if (sideDp >= OUTER_MIN_DP) LensLayout(true, true, ring = 0.76f, lens = 0.56f, disk = 0.42f, arc = 0.86f)
        else LensLayout(false, sideDp >= DRUM_MIN_DP, ring = 0.98f, lens = 0.8f, disk = 0.66f, arc = 0.98f)

    /** Where [v] is on the scale, 0..1. */
    fun share(v: Float, min: Float, max: Float): Float {
        if (max <= min || v.isNaN()) return 0f
        return ((v - min) / (max - min)).coerceIn(0f, 1f)
    }

    /** Where the crescent starts: the scale's start, or zero on a scale with both signs (trims, timing). */
    fun arcFrom(min: Float, max: Float): Float = if (min < 0f && max > 0f) share(0f, min, max) else 0f

    /** The crescent's thickness along its length, t 0..1: nothing at the ends, full in the middle. */
    fun taper(t: Float): Float = sqrt(sin(PI.toFloat() * t.coerceIn(0f, 1f)).coerceAtLeast(0f))

    /**
     * The crescent's fade goes with its ends, a brush is kept per 2 degrees of them. The ends themselves are transparent,
     * so a step of 2 degrees does not show.
     */
    fun brushKey(a0: Float, a1: Float, hot: Boolean): Int =
        (round(a0 / 2f).toInt() * 1000 + round(a1 / 2f).toInt()) * 2 + if (hot) 1 else 0

    /**
     * How much of the outer arc lights from its lower end, 0 outside the warning zone. The lines are the gauge's own: its
     * Warn rule and the red zone it already has. Deeper into the zone lights more.
     */
    fun warnShare(v: Float?, min: Float, max: Float, rule: WarnRule, redFrom: Float?): Float {
        if (v == null || v.isNaN()) return 0f
        fun deep(over: Float, room: Float) = if (room <= 0f) 1f else (over / room).coerceIn(0f, 1f)
        var depth = -1f
        val line = listOfNotNull(rule.above, redFrom).minOrNull()
        if (rule.above != null && v > rule.above || redFrom != null && v >= redFrom) depth = maxOf(depth, deep(v - line!!, max - line))
        if (rule.below != null && v < rule.below) depth = maxOf(depth, deep(rule.below - v, rule.below - min))
        if (rule.abs != null && abs(v) > rule.abs) depth = maxOf(depth, deep(abs(v) - rule.abs, maxOf(abs(min), abs(max)) - rule.abs))
        return if (depth < 0f) 0f else WARN_MIN + (WARN_MAX - WARN_MIN) * depth
    }

    /**
     * Whether a new number rolls in. Not on the Low tier or where the drum is dropped, not to or from «—», and not when the
     * last number lasted too short a time: then the number just changes, it never lags behind the value.
     */
    fun shouldRoll(drum: Boolean, lowTier: Boolean, sinceLastMs: Long, old: String, new: String): Boolean =
        drum && !lowTier && old != new && sinceLastMs >= ROLL_SPACING_MS && old.any { it.isDigit() } && new.any { it.isDigit() }

    /** The text the number is sized for, so its digits stay one size across the scale. */
    fun widest(format: String, min: Float, max: Float): String {
        val lo = format.format(min)
        val hi = format.format(max)
        return if (lo.length > hi.length) lo else hi
    }

    /** The number's font size for this disk radius, in the same unit. */
    fun digitSize(disk: Float, chars: Int, scale: Float = 1f): Float =
        minOf(disk * 2f * 0.86f / (chars.coerceAtLeast(1) * DIGIT_EM), disk * 0.8f * scale)

    /** A scale's end, written as the numbers of the round dials. */
    fun endLabel(x: Float, step: Float, labelDiv: Float): String =
        if (step / labelDiv < 1f) "%.1f".format(x / labelDiv) else round(x / labelDiv).toInt().toString()

    /**
     * The gear for the badge, null when it isn't known. From the gearbox unit when it answers, else from revs per km/h;
     * the tables behind that are Subaru gearboxes, so on another make only ratios learned on the car itself count.
     * No «N»: a standing car in gear would read as neutral.
     */
    fun gear(tcuGear: Int?, rpm: Int?, kmh: Int?, automatic: Boolean, learned: List<Float>, otherMake: Boolean): String? {
        tcuGear?.takeIf { it > 0 }?.let { return it.toString() }
        if (rpm == null || kmh == null) return null
        return Gears.estimate(rpm, kmh, automatic, learned, table = !otherMake)?.toString()
    }
}
