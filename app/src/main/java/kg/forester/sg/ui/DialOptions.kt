package kg.forester.sg.ui

import kotlin.math.abs
import kotlin.math.round

/**
 * What a card's style id carries beside its design: `<design>|m40,60,90,110|z120|n`. The tails are recognised one by one
 * from the end, so a design's own id (a picture's file name) keeps whatever it has. «|n» goes last.
 */
data class DialOptions(
    val base: String,
    val noNumber: Boolean = false,
    /** The driver's own numbers on the scale, in the value's units; empty = the scale's usual ones. */
    val marks: List<Float> = emptyList(),
    /** The size of the dial's numbers in percent; null = as in the design. */
    val numberPct: Int? = null,
) {
    fun format(): String = buildString {
        append(base)
        if (marks.isNotEmpty()) append(MARKS).append(marks.joinToString(",") { markNumber(it) })
        numberPct?.let { append(SCALE).append(it) }
        if (noNumber) append(NO_NUMBER)
    }

    companion object {
        private const val MARKS = "|m"
        private const val SCALE = "|z"
        private const val MAX_MARKS = 16
        const val MIN_PCT = 70
        const val MAX_PCT = 150

        fun parse(id: String): DialOptions {
            if ('|' !in id) return DialOptions(id)
            var rest = id
            var o = DialOptions("")
            while (true) {
                val cut = rest.lastIndexOf('|')
                if (cut < 0) break
                val tail = rest.substring(cut)
                when {
                    tail == NO_NUMBER -> o = o.copy(noNumber = true)
                    tail.startsWith(SCALE) && tail.length > 2 && tail.drop(2).all { it.isDigit() } ->
                        o = o.copy(numberPct = tail.drop(2).toIntOrNull()?.coerceIn(MIN_PCT, MAX_PCT))
                    tail.startsWith(MARKS) && tail.length > 2 && tail.drop(2).all { it.isDigit() || it == ',' || it == '.' || it == '-' } ->
                        o = o.copy(marks = parseMarks(tail.drop(2)))
                    else -> break
                }
                rest = rest.substring(0, cut)
            }
            return o.copy(base = rest)
        }

        /** What the driver typed: numbers apart by commas, spaces or semicolons, the rest ignored; sorted, each once. */
        fun parseMarks(text: String): List<Float> =
            text.split(',', ';', ' ', '\n').mapNotNull { it.trim().toFloatOrNull()?.takeIf { v -> !v.isNaN() && !v.isInfinite() } }
                .distinct().sorted().take(MAX_MARKS)

        fun marksText(marks: List<Float>): String = marks.joinToString(", ") { markNumber(it) }

        private fun markNumber(v: Float): String =
            if (abs(v - round(v)) < 0.001f) round(v).toInt().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')

        /** The marks that fit this scale. */
        fun onScale(marks: List<Float>, min: Float, max: Float): List<Float> = marks.filter { it in min..max }

        /** A mark as written on the scale, whose numbers are divided by [labelDiv] (×1000 on a tachometer). */
        fun label(v: Float, labelDiv: Float): String = markNumber(v / labelDiv)
    }
}
