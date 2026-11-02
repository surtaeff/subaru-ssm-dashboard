package kg.forester.sg.data

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The arithmetic of the night road card (ui/NightRoad.kt): where its parts go at a card's size, how far the lane lines
 * have come and how many blades of the arc are lit. All sizes in dp; a position along the road is 0 at the horizon and
 * 1 at the card's bottom edge.
 */
object RoadScene {
    /** The arc's full scale, that of the speedometer dial. */
    const val TOP_KMH = 200f

    /** The arc's length: past a semicircle by 20° at each end, open below. */
    const val SWEEP_DEG = 220f
    private const val START_DEG = 160f

    /** One dash and its gap along the road, and what the card's bottom edge is away from the viewer. */
    const val DASH_PERIOD_M = 6f
    private const val DASH_LENGTH_M = 2.4f
    private const val NEAR_M = 5f

    /** How many dashes are drawn of a lane line at most: the farther ones would lie on top of each other. */
    const val MAX_DASHES = 10
    private const val FARTHEST = 0.07f

    /** Under this nothing moves or lights: it is what is left of a needle settling at 0. */
    private const val STILL_KMH = 0.5f

    /** A step of half a period or more reads as dashes going backwards. */
    private const val MAX_STEP = 0.45f

    /** The lane lines' position after [dt] seconds at [kmh]; 1 is one whole period, which looks the same as 0. */
    fun phaseAfter(phase: Float, kmh: Float, dt: Float): Float {
        if (kmh < STILL_KMH) return phase
        val step = (kmh / 3.6f / DASH_PERIOD_M * dt).coerceAtMost(MAX_STEP)
        return (phase + step) % 1f
    }

    /**
     * The dashes of one lane line at [phase], nearest first: near and far end of each in [out] as positions along the
     * road. Returns how many. A larger [phase] brings each of them nearer. Nothing allocated: this runs on each frame.
     */
    fun dashes(phase: Float, out: FloatArray): Int {
        var n = 0
        var i = 0
        while (n < MAX_DASHES) {
            val z = NEAR_M + (i - phase) * DASH_PERIOD_M
            val far = z + DASH_LENGTH_M
            i++
            if (far <= NEAR_M) continue
            val sNear = NEAR_M / maxOf(z, NEAR_M)
            if (sNear < FARTHEST) break
            out[n * 2] = sNear
            out[n * 2 + 1] = NEAR_M / far
            n++
        }
        return n
    }

    /** How many of [blades] are lit at [kmh]. */
    fun litBlades(kmh: Float, blades: Int): Int =
        if (kmh < STILL_KMH) 0 else (kmh / TOP_KMH * blades).roundToInt().coerceIn(1, blades)

    /** The direction of blade [i], in degrees clockwise from the right. */
    fun bladeDeg(i: Int, blades: Int): Float = START_DEG + SWEEP_DEG * i / (blades - 1)

    /** Where everything goes in a card of [w] × [h] dp. */
    fun layout(w: Float, h: Float): RoadLayout {
        val margin = 3f
        val r = min(h * 0.39f, w * 0.46f).coerceAtLeast(8f)
        val cx = w / 2
        val cy = margin + r
        val inner = r * 0.84f
        val unitOn = h >= 56f
        // three digits fit inside the blades at this size
        val numberSize = inner * 0.8f
        val numberCy = if (unitOn) cy - r * 0.10f else cy
        val unitSize = r * 0.24f
        val unitCy = cy + r * 0.40f

        val horizon = h * 0.62f
        val carGround = h * 0.94f
        val roadDepth = h - horizon
        val roadHalf = min(w * 0.47f, roadDepth * 2.5f)
        val laneX = roadHalf / 2
        val atCar = (carGround - horizon) / roadDepth
        val fromLane = 2 * laneX * atCar * 0.47f
        val room = carGround - (unitCy + unitSize * 0.5f)
        val carRatio = 0.62f
        val carH = if (!unitOn || room < 12f) 0f else min(fromLane * carRatio, room)
        val carW = carH / carRatio

        val beside = (w - 2 * r) / 2 - 2 * margin
        val sideW = if (beside >= 90f) min(beside, 240f) else 0f
        val blades = (Math.toRadians(SWEEP_DEG.toDouble()) * r / 3.5).roundToInt().coerceIn(24, 120)
        val pitch = Math.toRadians(SWEEP_DEG.toDouble()).toFloat() * r / (blades - 1)
        return RoadLayout(
            w, h, cx, cy, r, inner, blades, pitch * 0.5f,
            numberCy, numberSize, unitOn, unitCy, unitSize,
            horizon, roadHalf, laneX,
            carGround, carW, carH,
            sideW, (cx - r) / 2, cx + r + (cx - r) / 2, sideW > 0f && h >= 150f,
        )
    }
}

/** See [RoadScene.layout]. [laneX]: a lane line's distance from the middle at the card's bottom edge; [sideW] 0: no side texts. */
data class RoadLayout(
    val w: Float, val h: Float,
    val cx: Float, val cy: Float, val r: Float, val bladeInner: Float, val blades: Int, val bladeWidth: Float,
    val numberCy: Float, val numberSize: Float, val unitOn: Boolean, val unitCy: Float, val unitSize: Float,
    val horizonY: Float, val roadHalf: Float, val laneX: Float,
    val carGround: Float, val carW: Float, val carH: Float,
    val sideW: Float, val leftCx: Float, val rightCx: Float, val titleOn: Boolean,
)
