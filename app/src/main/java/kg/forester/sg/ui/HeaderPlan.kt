package kg.forester.sg.ui

/** What the top bar gives each part, in dp: see [headerPlan]. */
data class HeaderPlan(
    val nameDp: Float, val nameWantedDp: Float, val noLinkDp: Float,
    /** How many of the chosen top values are shown, and the room each takes. */
    val values: Int, val valueDp: Float,
    val demoDp: Float, val statusDp: Float,
    val exitDp: Float, val clockDp: Float,
    /** What can't give way: the buttons, the gps, the clock, the exit, and the readings while there is room for them. */
    val mandatoryDp: Float,
    /** The volts and the coolant at the right: the last to give way, and only when even «нет связи» would not fit with them. */
    val readings: Boolean = true,
    /** The check-engine icon's slot, 0 while there is nothing to show. It outranks the readings and the plates. */
    val checkDp: Float = 0f,
) {
    val valuesDp: Float get() = values * valueDp
    val sumDp: Float get() = mandatoryDp + nameDp + valuesDp + demoDp + statusDp
}

/** The width of a reading's slot in the label size: six characters, 0.58 em each. */
const val READING_SLOT_EM = 6 * 0.58f

/** The longest the car's name column is. */
const val HEADER_NAME_MAX_DP = 280f

/** The cool-down plate's slot, the plate being fixed in it. */
const val HEADER_STATUS_DP = 200f

/**
 * The top bar's room, given out in this order, the first kept last when the room runs out: the name column's least
 * ([HeaderPlan.noLinkDp]: «нет связи» is always read whole), the exit, the clock, the check-engine icon, the cool-down plate, the demo plate (at
 * its full size: it is the way to turn the demo off), the volts and the coolant, the car's title up to its width, the
 * top values one by one. The theme, link and gps buttons are fixed slots. An item has its working width or is absent,
 * never a sliver: the readings are dropped whole (the bottom bar shows them as well).
 *
 * [labelDp] and [secondaryDp] are the sizes of the label and secondary text (sp taken as dp).
 */
fun headerPlan(
    totalDp: Float, tapDp: Float, labelDp: Float, secondaryDp: Float, logo: Boolean, demo: Boolean, status: Boolean, valuesWanted: Int, titleChars: Int = 8,
    check: Boolean = false,
): HeaderPlan {
    // «нет связи» whole: its text shrinks to three quarters of the secondary size in the column (FitText), never less
    val noLink = 9 * 0.56f * secondaryDp * 0.75f + 8f
    val clock = 5 * 0.62f * labelDp
    val readings = 22f + READING_SLOT_EM * labelDp + 22f + 22f + READING_SLOT_EM * labelDp
    val gps = 22f + 22f * 0.7f * 0.62f * 8f
    // logo or its gap, the gap after the name, theme button, gap, gap, gps, gap, link button, gap, clock, gap, exit
    val fixed = (if (logo) 96f + 16f else 4f) + 14f + tapDp + 16f + 18f + gps + 12f + tapDp + 22f + clock + 14f + tapDp
    var room = totalDp - fixed - noLink
    fun take(want: Float): Float = if (want > 0f && want <= room) { room -= want; want } else 0f
    val checkDp = take(if (check) tapDp else 0f)
    val statusDp = take(if (status) HEADER_STATUS_DP else 0f)
    val demoDp = take(if (demo) 74f + 2.5f * secondaryDp else 0f)
    val showReadings = readings <= room
    if (showReadings) room -= readings
    // the title: what its letters take, up to its longest, never less than the status line
    val nameWanted = (titleChars * 0.64f * labelDp + 4f).coerceIn(noLink, HEADER_NAME_MAX_DP)
    val nameExtra = minOf(nameWanted - noLink, room.coerceAtLeast(0f)); room -= nameExtra
    val valueDp = labelDp * 3.2f + 40f + 18f
    // the values only while the readings and the whole title are there
    val values = if (showReadings && nameExtra >= nameWanted - noLink - 0.01f) minOf(valuesWanted, (room.coerceAtLeast(0f) / valueDp).toInt()) else 0
    val mandatory = fixed + checkDp + if (showReadings) readings else 0f
    return HeaderPlan(noLink + nameExtra, nameWanted, noLink, values, valueDp, demoDp, statusDp, tapDp, clock, mandatory, showReadings, checkDp)
}
