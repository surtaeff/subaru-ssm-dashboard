package kg.forester.sg.data

import androidx.compose.runtime.mutableStateOf
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * «Единицы расстояния»: the one place that turns the app's numbers into what the driver chose. Everything is stored,
 * computed, logged and compared in km, km/h and l/100 km; this converts at the point of showing and, for a field the
 * driver types into, on the way back. Fuel stays in litres, pressure and temperature don't change.
 *
 * A new gauge or card uses this and needs nothing else: [shown] with a [Kind], or a [kg.forester.sg.ui.Indicator] made
 * with its `kind`. [miles] is snapshot state: a composable, a derived state or a canvas reading it follows a change at
 * once, without a restart.
 *
 * The unit names are Russian, as all the app's texts, and the dictionary has their English: mph, mi, mpg.
 */
object DistanceUnits {
    enum class Kind { SPEED, DISTANCE, CONSUMPTION }

    const val KM = "km"
    const val MI = "mi"
    const val KM_IN_MILE = 1.609344

    /** The unit of what is kept: a log's speed columns. */
    const val STORED_SPEED = "км/ч"

    /** l/100 km × mpg (US) */
    private const val MPG_FACTOR = 235.2145833

    /** What «no fuel burned» shows (a fuel cut on the overrun): mpg of it would be infinity. */
    const val MPG_TOP = 99.9

    private const val FEET_IN_M = 3.28084
    private const val SPEED_STEP = 20f
    private const val MPG_SCALE_TOP = 60f

    private val state = mutableStateOf(false)

    var miles: Boolean
        get() = state.value
        set(v) { state.value = v }

    // ---- the numbers

    fun speed(kmh: Double): Double = if (miles) kmh / KM_IN_MILE else kmh
    fun speed(kmh: Float): Float = if (miles) (kmh / KM_IN_MILE).toFloat() else kmh
    fun speed(kmh: Int): Int = if (miles) (kmh / KM_IN_MILE).roundToInt() else kmh
    fun speed(kmh: Double?): Double? = kmh?.let { speed(it) }
    fun speedToKmh(shown: Double): Double = if (miles) shown * KM_IN_MILE else shown

    fun distance(km: Double): Double = if (miles) km / KM_IN_MILE else km
    fun distance(km: Double?): Double? = km?.let { distance(it) }
    fun distanceToKm(shown: Double): Double = if (miles) shown * KM_IN_MILE else shown

    /** l/100 km or mpg (US); a very low burn stops at [MPG_TOP]. */
    fun consumption(l100: Double): Double =
        if (!miles) l100 else if (l100 <= MPG_FACTOR / MPG_TOP) MPG_TOP else MPG_FACTOR / l100
    fun consumption(l100: Double?): Double? = l100?.let { consumption(it) }
    fun consumptionToL100(shown: Double): Double = if (miles) MPG_FACTOR / shown else shown

    /** Revs per km/h as learned for a gear, or per mph. */
    fun perSpeed(perKmh: Float): Float = if (miles) (perKmh * KM_IN_MILE).toFloat() else perKmh

    fun shown(kind: Kind, metric: Float): Float = when (kind) {
        Kind.SPEED -> speed(metric)
        Kind.DISTANCE -> distance(metric.toDouble()).toFloat()
        Kind.CONSUMPTION -> consumption(metric.toDouble()).toFloat()
    }

    /** A value's lowest and highest in the shown unit: more litres are fewer miles to the gallon. */
    fun shownRange(kind: Kind, lo: Float, hi: Float): Pair<Float, Float> {
        val a = shown(kind, lo)
        val b = shown(kind, hi)
        return minOf(a, b) to maxOf(a, b)
    }

    /** A scale's top for [metricTop]: round numbers (200 km/h is 120 mph with a mark each 20, 800 km is 500 mi). */
    fun top(kind: Kind, metricTop: Float): Float {
        if (!miles) return metricTop
        return when (kind) {
            Kind.SPEED -> (floor(speed(metricTop) / SPEED_STEP) * SPEED_STEP).coerceAtLeast(SPEED_STEP)
            Kind.DISTANCE -> distance(metricTop.toDouble()).toFloat().let { d ->
                val m = 10f.pow(floor(log10(d)) - 1)
                (d / m).roundToInt() * m
            }
            Kind.CONSUMPTION -> MPG_SCALE_TOP
        }
    }

    /** The marks of a speed scale. */
    fun speedStep(): Float = SPEED_STEP

    // ---- the names

    val speedUnit: String get() = if (miles) "миль/ч" else "км/ч"
    val distanceUnit: String get() = if (miles) "миль" else "км"
    val consumptionUnit: String get() = if (miles) "миль/гал" else "л/100"
    val consumptionUnitLong: String get() = if (miles) "миль/гал" else "л/100 км"

    /** A card's unit says it shows speed: the metric id it is written with, or the unit as shown. */
    fun isSpeedUnit(unit: String): Boolean = unit == speedUnit || unit == "км/ч"

    fun unit(kind: Kind): String = when (kind) {
        Kind.SPEED -> speedUnit
        Kind.DISTANCE -> distanceUnit
        Kind.CONSUMPTION -> consumptionUnit
    }

    /** What a unit written in a parameter's data measures; null: anything else, it doesn't change. */
    fun kindOf(units: String): Kind? = when (units) {
        "км/ч" -> Kind.SPEED
        "км" -> Kind.DISTANCE
        "л/100", "л/100 км", "л/100км" -> Kind.CONSUMPTION
        else -> null
    }

    /** A value of a catalogue with a range kept by its id (the trip's min and max). */
    fun kindOfMetric(id: String): Kind? = when (id) {
        "speed", "gps" -> Kind.SPEED
        "range" -> Kind.DISTANCE
        "per100", "tripAvg" -> Kind.CONSUMPTION
        else -> null
    }

    /** A title or a label that names a unit, without a number: «Скорость колёс, км/ч». */
    fun label(text: String): String = if (!miles) text else
        text.replace("км/ч", "миль/ч").replace("л/100 км", "миль/гал").replace("л/100", "миль/гал").replace(Regex("""\bкм\b"""), "миль")

    // ---- the texts

    fun speedText(kmh: Double, decimals: Int = 0): String = "%.${decimals}f".format(speed(kmh)) + " " + speedUnit
    fun speedText(kmh: Float, decimals: Int = 0): String = speedText(kmh.toDouble(), decimals)
    fun speedText(kmh: Int): String = "${speed(kmh)} $speedUnit"
    fun distanceText(km: Double, decimals: Int = 0): String = "%.${decimals}f".format(distance(km)) + " " + distanceUnit
    fun consumptionText(l100: Double): String = "%.1f".format(consumption(l100)) + " " + consumptionUnitLong

    /** A road distance for navigation hints: feet up close, then tenths of a mile; metres and kilometres otherwise. */
    fun navDistance(m: Double): String = when {
        !miles && m < 1000 -> "${((m / 10).toInt() * 10).coerceAtLeast(10)} м"
        !miles && m < 10_000 -> "%.1f км".format(m / 1000).replace('.', ',')
        !miles -> "${(m / 1000).toInt()} км"
        m < 150 -> "${(((m * FEET_IN_M) / 10).roundToInt() * 10).coerceAtLeast(10)} футов"
        m / 1000 / KM_IN_MILE < 10 -> "%.1f".format(m / 1000 / KM_IN_MILE) + " миль"
        else -> "${(m / 1000 / KM_IN_MILE).roundToInt()} миль"
    }

    private val NAV_ONLY = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(м|км|m|km)\s*$""")

    /** A navigator's own line («350 м»): converted when it is only a distance, whatever else it says stays as it came. */
    fun navText(text: String): String {
        if (!miles) return text
        val m = NAV_ONLY.matchEntire(text) ?: return text
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return text
        return navDistance(if (m.groupValues[2] == "м" || m.groupValues[2] == "m") n else n * 1000)
    }

    // ---- fields the driver types into

    /** A stored distance as a field shows it, a whole number in the chosen unit. */
    fun distanceField(km: Double): String = distance(km).roundToInt().toString()

    /**
     * What a distance field saves: the old value [oldKm] to the metre when the number wasn't changed, so opening and
     * saving doesn't move it by the rounding; null when it isn't a number.
     */
    fun distanceFromField(text: String, oldKm: Double?): Double? {
        val typed = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (oldKm != null && oldKm >= 0 && text.trim() == distanceField(oldKm)) return oldKm
        return distanceToKm(typed)
    }

    // ---- sentences

    private val NUMBER = """\d[\d  ]*(?:[.,]\d+)*"""
    private val PROSE = Regex(
        """(?<![\p{L}\d.,])($NUMBER)(?:\s?([–-])\s?($NUMBER))?\s?(тыс\.\s)?(км/ч|km/h|л/100\s?км|l/100\s?km|л/100|l/100|км|km)(?![\p{L}\d/])""",
    )

    /** Numbers with a unit inside a sentence, written or translated, in the chosen unit. Nothing changes in km. */
    fun prose(text: String): String {
        if (!miles) return text
        return WORDS.fold(PROSE.replace(text) { m ->
            val unit = m.groupValues[5]
            val latin = unit[0] in 'a'..'z'
            val kind = when {
                unit == "км/ч" || unit == "km/h" -> Kind.SPEED
                unit.startsWith("л") || unit.startsWith("l") -> Kind.CONSUMPTION
                else -> Kind.DISTANCE
            }
            val a = proseNumber(m.groupValues[1]) ?: return@replace m.value
            val b = if (m.groupValues[3].isEmpty()) null else proseNumber(m.groupValues[3]) ?: return@replace m.value
            val thousands = m.groupValues[4]
            val grouped = ',' in m.groupValues[1] && m.groupValues[1].substringAfter(',').length == 3
            fun one(v: Double): Double = shown(kind, v.toFloat()).toDouble()
            val first = one(a)
            val second = b?.let(::one)
            val low = if (second != null) minOf(first, second) else first
            val high = second?.let { maxOf(first, it) }
            val fine = kind == Kind.DISTANCE && thousands.isEmpty() && (high ?: low) < 10
            fun text(v: Double): String = when {
                fine -> "%.1f".format(java.util.Locale.US, v).let { if (latin) it else it.replace('.', ',') }
                grouped -> "%,d".format(java.util.Locale.US, Math.round(v))
                else -> Math.round(v).toString()
            }
            val name = when (kind) {
                Kind.SPEED -> if (latin) "mph" else "миль/ч"
                Kind.DISTANCE -> if (latin) "mi" else "миль"
                Kind.CONSUMPTION -> if (latin) "mpg" else "миль/гал"
            }
            text(low) + (high?.let { m.groupValues[2] + text(it) } ?: "") + " " + thousands + name
        }) { t, (km, mi) -> t.replace(km, mi) }
    }

    /** The word without a number: «Сколько километров», a line that names no figure. */
    private val WORDS = listOf(
        "километров" to "миль", "Километров" to "Миль", "километры" to "мили", "Километры" to "Мили",
        "kilometres" to "miles", "Kilometres" to "Miles", "kilometers" to "miles",
    )

    private fun proseNumber(s: String): Double? {
        val t = s.replace(" ", "").replace(" ", "")
        return (if (Regex("""\d{1,3}(,\d{3})+""").matches(t)) t.replace(",", "") else t.replace(',', '.')).toDoubleOrNull()
    }

    /** [ru] as it goes on a screen: unchanged in km (the text view translates it); in miles translated first, then converted. */
    fun shownProse(ru: String): String = if (!miles) ru else I18n.raw(prose(tr(ru)))
}
