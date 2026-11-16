package kg.forester.sg.ui

import kg.forester.sg.service.KNOCK_WARN_DEG

/** When a reading is over the line: above [above], below [below], or further than [abs] from zero either way. */
data class WarnRule(val above: Float? = null, val below: Float? = null, val abs: Float? = null) {
    fun hits(v: Float?): Boolean =
        v != null && ((above != null && v > above) || (below != null && v < below) || (abs != null && kotlin.math.abs(v) > abs))
}

/**
 * The lines every place that shows a value warns by: the home cards (plain and needle), the compact panel and the
 * catalogue the bottom bar reads. A value is the same over the line on every screen.
 */
object Warn {
    /** Fewer kilometres of fuel than this is a warning. */
    const val RANGE_LOW_KM = 50f

    private val rules = mapOf(
        "coolant" to WarnRule(above = 102f),
        "intake" to WarnRule(above = 55f),
        "battery" to WarnRule(below = 12f),
        "range" to WarnRule(below = RANGE_LOW_KM),
        "knock" to WarnRule(below = KNOCK_WARN_DEG),
        "fbkc" to WarnRule(below = KNOCK_WARN_DEG),
        "flkc" to WarnRule(below = KNOCK_WARN_DEG),
        "atf" to WarnRule(above = 110f),
        "afcorr" to WarnRule(abs = 15f),
        "aflearn" to WarnRule(abs = 10f),
    )

    private val none = WarnRule()

    fun of(id: String): WarnRule = rules[id] ?: none

    /** The rule of a value that is compared as shown, in the driver's unit. */
    fun ofShown(id: String, kind: kg.forester.sg.data.DistanceUnits.Kind): WarnRule = of(id).let { w ->
        fun c(v: Float?) = v?.let { kg.forester.sg.data.DistanceUnits.shown(kind, it) }
        WarnRule(c(w.above), c(w.below), c(w.abs))
    }
}

/** What a missing reading shows: a dash, never a 0 that reads as a real one. */
fun orDash(v: Int?): String = v?.toString() ?: "—"

/**
 * The colour everything over its warning line is drawn in: the ring round a dial, the triangle by a card's name, the
 * number of a plain card, the bottom bar's icon. Picked in Настройки, Функции; 0 = the theme's own orange.
 */
object WarnColor {
    val choices: List<Pair<Long, String>> = listOf(
        0L to "Как в теме", 0xFFFF3B3BL to "Красный", 0xFFFFD60AL to "Жёлтый", 0xFFFF4FD8L to "Розовый",
        0xFF22D3EEL to "Голубой", 0xFFFFFFFFL to "Белый",
    )
    private val state = androidx.compose.runtime.mutableStateOf(of(kg.forester.sg.data.Repo.settings.warnColor))
    val color: androidx.compose.ui.graphics.Color? get() = state.value
    val current: Long get() = kg.forester.sg.data.Repo.settings.warnColor

    fun set(v: Long) {
        kg.forester.sg.data.Repo.settings.warnColor = v
        state.value = of(v)
    }

    private fun of(v: Long) = if (v == 0L) null else androidx.compose.ui.graphics.Color(v)
}
