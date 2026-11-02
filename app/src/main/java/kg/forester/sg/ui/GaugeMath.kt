package kg.forester.sg.ui

import kg.forester.sg.data.DistanceUnits
import kg.forester.sg.service.CheckEngine
import kg.forester.sg.service.checkEngineShown
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.sqrt

/** The gauge designs with built-in indicators; drawn in GaugeDesigns.kt. The ids in the settings are [DialStyle.id]. */
enum class GaugeDesign { HUD_AMBER, HUD_BLUE, RED_LINE, BLUE_GLOW, CHROME, DOUBLE, SPORT_WHITE }

/** Where the driver's «Цифры в центре» puts the number: the design's own way, plain, on an LCD, in a frame. */
enum class NumberMode { DESIGN, PLAIN, LCD, FRAME }

/** What the classic's window holds; with [NONE] it isn't drawn at all. */
enum class ClassicWindow { NONE, VALUE, OTHER }

/** What a design shows at its size: indicators go first as the card gets smaller, then the window, then every other number. */
data class DesignParts(val indicators: Boolean, val window: Boolean, val side: Boolean, val allLabels: Boolean)

/** One built-in indicator: text as shown, over its warning line or not, how full (for a bar), null when it has none. */
data class GaugeInd(val text: String, val warn: Boolean = false, val share: Float? = null)

/** What the indicators read, each null while the app doesn't really know it. */
data class IndInputs(
    val fuelLeftL: Double? = null, val tankL: Double = 0.0, val rangeKm: Double? = null,
    val volts: Float? = null, val coolantC: Int? = null,
    val gear: String? = null, val selector: String? = null,
    val tripKm: Double? = null, val odoKm: Double? = null,
    val codes: CheckEngine? = null, val connected: Boolean = false, val clock: String? = null,
    /** The distance unit as shown, by [DistanceUnits]; [rangeKm], [tripKm], [odoKm] stay in km and are converted for the text. */
    val km: String = DistanceUnits.distanceUnit, val volt: String = "В", val litre: String = "л",
)

/** All indicators a design may show; an absent one is null and draws nothing. */
data class GaugeInds(
    val fuel: GaugeInd? = null, val battery: GaugeInd? = null, val coolant: GaugeInd? = null,
    val gear: GaugeInd? = null, val selector: GaugeInd? = null, val trip: GaugeInd? = null, val odo: GaugeInd? = null,
    val check: GaugeInd? = null, val clock: GaugeInd? = null,
)

/** The arithmetic of the designs and panels; the drawing is in GaugeDesigns.kt and GaugePanels.kt. Angles: 0 = 3 o'clock, clockwise. */
object GaugeMath {
    const val INDICATORS_MIN_DP = 220f
    const val SIDE_MIN_DP = 190f
    const val WINDOW_MIN_DP = 110f
    const val ALL_LABELS_MIN_DP = 130f

    /** Coolant needle's scale ends («C» and «H»), as on a car's own gauge. */
    const val COOLANT_COLD = 40f
    const val COOLANT_HOT = 130f

    /** The double-needle dial: fuel over the top from E to F, coolant under the bottom from C to H. */
    const val FUEL_E_DEG = 205f
    const val FUEL_SWEEP = 130f
    const val COOL_C_DEG = 155f
    const val COOL_SWEEP = -130f

    /** Below this force the G-radar lights no sector: an engine shaking or a slight slope has no direction. */
    const val RADAR_MIN_G = 0.06f

    /** The tilt panel's scale ends, degrees. */
    const val TILT_LIMIT = 40f

    const val PSI_PER_BAR = 14.5038f

    fun parts(sideDp: Float): DesignParts = DesignParts(
        indicators = sideDp >= INDICATORS_MIN_DP, window = sideDp >= WINDOW_MIN_DP,
        side = sideDp >= SIDE_MIN_DP, allLabels = sideDp >= ALL_LABELS_MIN_DP,
    )

    /** The tank a fuel needle goes by: the demo's own when none is set, as its fuel is counted from that. */
    fun shownTank(tankL: Double, demo: Boolean): Double = if (tankL > 0.0) tankL else if (demo) 60.0 else 0.0

    /** A needle with nothing to read rests at its start. */
    fun needleRest(share: Float?): Float = share ?: 0f

    /**
     * What the classic's window shows: an odometer or trip on a speed card, the card's number where there's nothing else for
     * it, or no window. An LCD asked for by the driver is the number's, a plain or framed number stands by itself.
     */
    fun chromeWindow(mode: NumberMode, room: Boolean, value: Boolean, speedCard: Boolean, hasOther: Boolean): ClassicWindow = when {
        !room -> ClassicWindow.NONE
        mode == NumberMode.LCD && value -> ClassicWindow.VALUE
        speedCard && hasOther -> ClassicWindow.OTHER
        mode == NumberMode.DESIGN && value -> ClassicWindow.VALUE
        else -> ClassicWindow.NONE
    }

    /** What a picture of a design shows: plausible, and not a warning. */
    fun sampleInds(km: String, volt: String, litre: String): GaugeInds = WarnRule().let { none ->
        indicators(
            IndInputs(
                fuelLeftL = 38.0, tankL = 60.0, rangeKm = 420.0, volts = 14.1f, coolantC = 88, gear = "3", selector = "D",
                tripKm = 124.6, odoKm = 84210.0, clock = "12:30", km = km, volt = volt, litre = litre,
            ),
            none, none, none,
        )
    }

    fun share(v: Float, min: Float, max: Float): Float {
        if (max <= min || v.isNaN()) return 0f
        return ((v - min) / (max - min)).coerceIn(0f, 1f)
    }

    /** Where a scale of [sweep] degrees starts: centred on the bottom. */
    fun startDeg(sweep: Float): Float = 90f + (360f - sweep) / 2f

    fun deg(share: Float, sweep: Float): Float = startDeg(sweep) + sweep * share.coerceIn(0f, 1f)

    /** How many of [n] segments are lit at [share]: none at the start, all at the end, the last only at the very end. */
    fun lit(share: Float, n: Int): Int = (share.coerceIn(0f, 1f) * n + 1e-4f).toInt().coerceIn(0, n)

    /** Numbered scale positions from [min] to [max]; every other one when they would be too many or the dial too small. */
    fun labelSteps(min: Float, max: Float, step: Float, all: Boolean): List<Float> {
        if (step <= 0f || max <= min) return emptyList()
        val n = round((max - min) / step).toInt()
        val every = if (!all || n > 10) 2 else 1
        return (0..n).filter { it % every == 0 }.map { min + step * it }
    }

    /** A scale's number as the round dials write it. */
    fun labelText(x: Float, step: Float, labelDiv: Float): String = LensMath.endLabel(x, step, labelDiv)

    /** The share of the tank left, null without a tank size or a known level. */
    fun fuelShare(leftL: Double?, tankL: Double): Float? =
        if (leftL == null || tankL <= 0.0) null else (leftL / tankL).toFloat().coerceIn(0f, 1f)

    fun coolantShare(c: Int?): Float? = c?.let { share(it.toFloat(), COOLANT_COLD, COOLANT_HOT) }

    fun fuelNeedleDeg(share: Float): Float = FUEL_E_DEG + FUEL_SWEEP * share.coerceIn(0f, 1f)
    fun coolNeedleDeg(share: Float): Float = COOL_C_DEG + COOL_SWEEP * share.coerceIn(0f, 1f)

    /**
     * The indicators from what the app knows: what isn't known is absent, never a zero. Warnings by the shared lines:
     * [fuel] by the range left, the volts and the coolant by theirs.
     */
    fun indicators(i: IndInputs, batteryRule: WarnRule, coolantRule: WarnRule, rangeRule: WarnRule): GaugeInds {
        val fuelText = i.fuelLeftL?.let { "%.0f %s".format(it, i.litre) } ?: i.rangeKm?.let { "%.0f %s".format(DistanceUnits.distance(it), i.km) }
        return GaugeInds(
            fuel = fuelText?.let { GaugeInd(it, rangeRule.hits(i.rangeKm?.toFloat()), fuelShare(i.fuelLeftL, i.tankL)) },
            battery = i.volts?.let { GaugeInd("%.1f %s".format(it, i.volt), batteryRule.hits(it), share(it, 10f, 15f)) },
            coolant = i.coolantC?.let { GaugeInd("$it°", coolantRule.hits(it.toFloat()), coolantShare(it)) },
            gear = i.gear?.let { GaugeInd(it) },
            selector = i.selector?.let { GaugeInd(it) },
            trip = i.tripKm?.let { GaugeInd("%.1f %s".format(DistanceUnits.distance(it), i.km)) },
            odo = i.odoKm?.let { GaugeInd("%.0f".format(DistanceUnits.distance(it))) },
            check = if (checkEngineShown(i.codes, i.connected)) GaugeInd(i.codes!!.count.takeIf { it > 0 }?.toString().orEmpty(), warn = true) else null,
            clock = i.clock?.let { GaugeInd(it) },
        )
    }

    /** A letter of the strip R N D lights by the selector the gearbox unit reports; with «P/N» both of its letters. */
    fun selectorLit(letter: String, selector: String?): Boolean = selector != null && letter in selector.split('/')

    // ---- G-force ----

    /** The G dot, in shares of the circle's radius (x right, y down), at most on its edge. [full] is the g at the edge. Up = speeding up, as on the G-meter. */
    fun gDot(lat: Float, lon: Float, full: Float): Pair<Float, Float> {
        val x = lat / full
        val y = -lon / full
        val d = sqrt(x * x + y * y)
        return if (d > 1f) (x / d) to (y / d) else x to y
    }

    /** The sector of [n] (0 from straight up, clockwise) the force points into; null while it is too small to say. */
    fun radarSector(lat: Float, lon: Float, n: Int): Int? {
        if (sqrt(lat * lat + lon * lon) < RADAR_MIN_G) return null
        val a = atan2(lat.toDouble(), lon.toDouble())
        val turn = (a / (2 * PI) + 1.0 + 0.5 / n) % 1.0
        return (turn * n).toInt().coerceIn(0, n - 1)
    }

    /** How many of [rings] rings the force fills: at least one when it has a direction, all at [full]. */
    fun radarRings(g: Float, rings: Int, full: Float): Int = ceil(g / full * rings).toInt().coerceIn(1, rings)

    /** The accelerometer's data counts only with the sensor, its zero set, the feature on. Elsewhere it isn't offered. */
    fun motionOffered(hasSensor: Boolean, calibrated: Boolean, featureOn: Boolean): Boolean = hasSensor && calibrated && featureOn

    fun tiltShare(deg: Float): Float = (deg / TILT_LIMIT).coerceIn(-1f, 1f)

    // ---- boost ----

    /** The boost dial's scale in bar: vacuum to -1, the top a whole bar over what the app expects of this car, at least 2. */
    fun boostScale(top: Float): Pair<Float, Float> = -1f to ceil(top.coerceAtLeast(2f))

    /** PSI marks for a boost scale: from 0 up every 5, nothing under zero: below it is vacuum. */
    fun psiMarks(maxBar: Float): List<Int> = (0..(maxBar * PSI_PER_BAR).toInt() step 5).toList()

    // ---- the spokes ----

    /** The angles of [n] spokes round the speed, evenly from upper left; the same six places as long as there are six. */
    fun spokeDeg(i: Int, n: Int): Float = if (n <= 0) 0f else 240f + 360f * i / n

    // ---- run charts ----

    /** The way covered between [t0] and [t1], metres: speed (km/h) over the samples' times (s). The samples' rate limits it. */
    fun distanceM(t: DoubleArray, v: DoubleArray, t0: Double, t1: Double): Double {
        var d = 0.0
        for (i in 1 until minOf(t.size, v.size)) {
            val a = maxOf(t[i - 1], t0)
            val b = minOf(t[i], t1)
            if (b > a) d += (v[i - 1] + v[i]) / 2.0 / 3.6 * (b - a)
        }
        return d
    }

    /**
     * The curve of a run as x, y pairs in shares of the plot (y up from the bottom): the time from [t0] to [t1], speed to
     * [top], at most [points] of them. Empty when there is nothing to draw.
     */
    fun chart(t: DoubleArray, v: DoubleArray, t0: Double, t1: Double, top: Double, points: Int = 240): FloatArray {
        val n = minOf(t.size, v.size)
        if (n < 2 || t1 <= t0 || top <= 0.0) return FloatArray(0)
        val from = t.indexOfFirst { it >= t0 }.let { if (it < 0) return FloatArray(0) else it }
        val to = (t.indexOfLast { it <= t1 }).coerceAtMost(n - 1)
        if (to <= from) return FloatArray(0)
        val stride = ((to - from) / points + 1).coerceAtLeast(1)
        val out = ArrayList<Float>()
        var i = from
        while (i <= to) {
            out += ((t[i] - t0) / (t1 - t0)).toFloat().coerceIn(0f, 1f)
            out += (v[i] / top).toFloat().coerceIn(0f, 1f)
            i += stride
        }
        return out.toFloatArray()
    }
}
