package kg.forester.sg.ui

/** A rectangle on a card, in pixels. */
data class LayoutBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
}

/** A round dial on a card with what goes beside it ([side]) or under it ([line]); at most one of the two. */
data class DialSplit(val cx: Float, val cy: Float, val r: Float, val side: LayoutBox? = null, val line: LayoutBox? = null) {
    val dial get() = LayoutBox(cx - r, cy - r, cx + r, cy + r)
    /** Everything on the card: the margins are what is left round this. */
    val all: LayoutBox get() {
        val d = dial
        return LayoutBox(d.l, d.t, side?.r ?: d.r, line?.b ?: d.b)
    }
}

/** A design on its card: the dial and its neighbour; [plate] when a dark plate fills the card for text meant for the dark. */
data class DesignLayout(val split: DialSplit, val plate: Boolean, val rows: Int, val rowH: Float, val rowText: Float, val readoutH: Float)

/** Tachometer arc: its circle, the line under the digits and the row of values. */
data class TachoLayout(val cx: Float, val cy: Float, val r: Float, val rule: Float, val caption: Float, val values: Float, val detail: Boolean)

/** Tilt: the two scales' centres and radius, where the readings go. */
data class TiltLayout(val leftX: Float, val rightX: Float, val cy: Float, val r: Float, val top: Float, val speed: Float, val unit: Float, val labels: Boolean)

/** G-force: the circle and the size of the maxima round it. */
data class ForceLayout(val cx: Float, val cy: Float, val r: Float, val text: Float)

/** Spokes: the disk in the middle, how far out the values stand; no spokes on a card too small to read them. */
data class WebLayout(val cx: Float, val cy: Float, val r: Float, val outX: Float, val outY: Float, val spokes: Boolean)

/** G-radar: the semi-axes of the grid and the pill in the middle. */
data class RadarLayout(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val pillW: Float, val pillH: Float)

/**
 * Where everything of a design or a panel goes, from the card's real size and nothing else. Same margins left and right,
 * top and bottom; dial and what stands beside it on one centre line. Sizes in pixels, [dp] = pixels per dp.
 */
object GaugeLayout {
    private const val EDGE = 0.03f
    /** Text below this isn't read in a car: what would need it goes first. */
    const val MIN_TEXT_DP = 10f
    private const val COLUMN_MIN_DP = 76f
    private const val LINE_MIN_DP = 22f

    private fun margin(w: Float, h: Float) = minOf(w, h) * EDGE

    /** A dial can't be read on a card this low and wide (sizes in dp), a row of about 40 dp: numbers smaller than [MIN_TEXT_DP]. */
    fun tooShortForDial(wDp: Float, hDp: Float): Boolean = hDp < 64f && wDp >= hDp * 2f

    /**
     * Wide (from [wideAt] times the height): dial left, column right. Tall (from [tallAt] times the width): line under the
     * dial. Between the two, or with no room for either: the dial alone in the middle.
     */
    fun split(w: Float, h: Float, dp: Float, wideAt: Float = 1.25f, tallAt: Float = 1.2f, columnMinDp: Float = COLUMN_MIN_DP): DialSplit {
        val m = margin(w, h)
        val column = columnMinDp * dp
        if (w >= h * wideAt && w - 3 * m - column >= h * 0.45f) {
            val d = minOf(h - 2 * m, w - 3 * m - column)
            val colW = minOf(w - d - 3 * m, d * 0.72f)
            val left = (w - d - m - colW) / 2f
            val cy = h / 2f
            return DialSplit(left + d / 2f, cy, d / 2f, side = LayoutBox(left + d + m, cy - d / 2f, left + d + m + colW, cy + d / 2f))
        }
        val lineMin = LINE_MIN_DP * dp
        if (h >= w * tallAt && h - 3 * m - lineMin >= w * 0.45f) {
            val d = minOf(w - 2 * m, h - 3 * m - lineMin)
            val lineH = minOf(h - d - 3 * m, d * 0.3f)
            val top = (h - d - m - lineH) / 2f
            val cx = w / 2f
            return DialSplit(cx, top + d / 2f, d / 2f, line = LayoutBox(cx - d / 2f, top + d + m, cx + d / 2f, top + d + m + lineH))
        }
        return DialSplit(w / 2f, h / 2f, (minOf(w, h) - 2 * m) / 2f)
    }

    /** A design's place: those with a column split the card, the others are one dial in its middle. */
    fun design(d: GaugeDesign, w: Float, h: Float, dp: Float): DesignLayout {
        if (d != GaugeDesign.RED_LINE && d != GaugeDesign.BLUE_GLOW) {
            return DesignLayout(DialSplit(w / 2f, h / 2f, minOf(w, h) / 2f * 0.98f), plate = false, rows = 0, rowH = 0f, rowText = 0f, readoutH = 0f)
        }
        val s = split(w, h, dp)
        val col = s.side
        if (col == null) return DesignLayout(s, plate = s.line != null, rows = 0, rowH = 0f, rowText = 0f, readoutH = 0f)
        val readout = minOf(col.w, col.h * 0.5f) * (if (d == GaugeDesign.RED_LINE) 1f else 0.8f)
        val rowText = minOf(col.h * 0.085f, col.w * 0.17f).coerceAtLeast(MIN_TEXT_DP * dp)
        val rowH = rowText * 1.7f
        val rows = ((col.h - readout - rowText * 0.5f) / rowH).toInt().coerceIn(0, 5)
        return DesignLayout(s, plate = true, rows = rows, rowH = rowH, rowText = rowText, readoutH = readout)
    }

    /** Top of a block of a readout and [rows] rows centred on [cy]: stays on the dial's centre line however many are known. */
    fun stackTop(cy: Float, readoutH: Float, rowH: Float, rows: Int): Float = cy - (readoutH + rows * rowH) / 2f

    /** Arc over the digits and a row of two values, the whole block in the middle of the card. */
    fun tacho(w: Float, h: Float, dp: Float): TachoLayout {
        val r = minOf(w * 0.36f, h * 0.54f)
        val block = r * 1.72f
        val cy = (h - block) / 2f + r
        return TachoLayout(w / 2f, cy, r, cy + r * 0.4f, cy + r * 0.47f, cy + r * 0.62f, detail = r * 0.09f >= 7.5f * dp)
    }

    /** The boost dial with four values beside it (wide) or two by two under it (tall). */
    fun turbo(w: Float, h: Float, dp: Float): DialSplit =
        // on the smallest card the dial alone: the values beside it would be too small to read
        if (minOf(w, h) < 120f * dp || maxOf(w, h) < 200f * dp) DialSplit(w / 2f, h / 2f, (minOf(w, h) - 2 * margin(w, h)) / 2f)
        else split(w, h, dp, wideAt = 1.1f, tallAt = 1.1f, columnMinDp = 70f)

    /** Where [i] of [n] values stands in a column, as its middle. */
    fun columnY(box: LayoutBox, i: Int, n: Int): Float = box.t + box.h * (i + 0.5f) / n

    fun tilt(w: Float, h: Float, dp: Float): TiltLayout {
        val r = minOf(w * 0.19f, h * 0.44f)
        val cy = h / 2f - r * 0.145f
        return TiltLayout(w * 0.25f, w * 0.75f, cy, r, cy - r * 0.73f, cy + r * 0.89f, cy + r * 1.09f, labels = r * 0.1f >= 7f * dp)
    }

    fun radar(w: Float, h: Float): RadarLayout {
        val m = margin(w, h)
        val pillW = minOf(w * 0.36f, h * 0.9f)
        return RadarLayout(w / 2f, h / 2f, w / 2f - m, h / 2f - m, pillW, minOf(h * 0.34f, pillW * 0.6f))
    }

    fun force(w: Float, h: Float): ForceLayout {
        val r = minOf(h * 0.33f, w * 0.26f)
        return ForceLayout(w / 2f, h / 2f, r, minOf(r * 0.3f, h * 0.1f))
    }

    fun web(w: Float, h: Float, dp: Float): WebLayout {
        val small = minOf(w, h) < 170f * dp
        val r = if (small) minOf(w, h) * 0.42f else minOf(h * 0.27f, w * 0.17f)
        return WebLayout(w / 2f, h / 2f, r, w * 0.36f, h * 0.36f, spokes = !small)
    }
}
