package kg.forester.sg.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Where the «CarPulse» start-up picture lies on a window and where its live loader goes. Pure, so every screen size is a
 * test. Sizes in the same pixels as the window; marks in the picture's own pixels, as in the retouched WebP files.
 */
object SplashFrame {
    class Art(
        val w: Int, val h: Int,
        /** What must stay in the frame when the window crops the picture: the logo with its slogan. */
        val focusX0: Int, val focusY0: Int, val focusX1: Int, val focusY1: Int,
        /** Where the loader belongs: its pill, and the word under it. */
        val barX0: Int, val barY0: Int, val barX1: Int, val barY1: Int, val wordY0: Int, val wordY1: Int,
    )

    /** Measured on the owner's two pictures; the bar and the word are retouched out of the files at these places. */
    val LANDSCAPE = Art(1672, 941, 920, 280, 1590, 540, 960, 629, 1550, 661, 675, 702)
    val PORTRAIT = Art(1086, 1448, 625, 440, 1060, 575, 277, 1250, 807, 1288, 1310, 1347)

    /** A portrait window gets the portrait picture, whatever the device. */
    fun artFor(windowW: Float, windowH: Float): Art = if (windowH > windowW) PORTRAIT else LANDSCAPE

    class Cover(val scale: Float, val left: Float, val top: Float) {
        fun x(px: Int) = left + px * scale
        fun y(px: Int) = top + px * scale
    }

    /** Fills the window without stretching. The crop falls in the middle unless that would cut the logo. */
    fun cover(a: Art, w: Float, h: Float): Cover {
        val scale = max(w / a.w, h / a.h)
        fun offset(window: Float, full: Float, f0: Int, f1: Int): Float {
            if (full <= window) return (window - full) / 2f
            var o = (window - full) / 2f
            val from = f0 * scale
            val to = f1 * scale
            if (to - from >= window) return (window - (from + to)) / 2f // wider than the frame: its middle in the middle
            if (from + o < 0f) o = -from
            if (to + o > window) o = window - to
            return o.coerceIn(window - full, 0f)
        }
        return Cover(scale, offset(w, a.w * scale, a.focusX0, a.focusX1), offset(h, a.h * scale, a.focusY0, a.focusY1))
    }

    class Bar(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val wordY0: Float, val wordY1: Float)

    /** The loader over its mark in the picture; moved and narrowed to the visible frame where the crop would cut it. */
    fun bar(a: Art, c: Cover, w: Float, h: Float): Bar {
        val margin = w * 0.04f
        val width = min((a.barX1 - a.barX0) * c.scale, w - margin * 2)
        val wanted = c.x(a.barX0)
        val x0 = wanted.coerceIn(margin, max(margin, w - margin - width))
        val tall = (a.wordY1 - a.barY0) * c.scale
        val bottom = h * 0.97f
        val shift = max(0f, c.y(a.barY0) + tall - bottom)
        val y0 = max(h * 0.03f, c.y(a.barY0) - shift)
        return Bar(x0, y0, x0 + width, y0 + (a.barY1 - a.barY0) * c.scale,
            y0 + (a.wordY0 - a.barY0) * c.scale, y0 + (a.wordY1 - a.barY0) * c.scale)
    }

    /** How far the bar has come after [done] of the [StartSteps]: real steps only, and never a number on the screen. */
    fun share(done: Int): Float = (done.toFloat() / StartSteps.TOTAL).coerceIn(0f, 1f)
}

/** What really happens at a start with the «CarPulse» picture: the picture is up, the app is composed, the 3D car's first frame. */
object StartSteps {
    const val TOTAL = 3
    /** A screen with the 3D car asks for it; none on screen, nothing to wait for. */
    @Volatile var carAsked = false
    val carShown = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * One large picture to a target size: halving first, as one bilinear step of 5-7 times loses most of the pixels and leaves
 * mush, then the last step. Returns the sizes to go through, the last being the target.
 */
object LogoScale {
    fun steps(srcW: Int, srcH: Int, targetW: Int, targetH: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var w = srcW
        var h = srcH
        while (w / 2 >= targetW && h / 2 >= targetH) { w /= 2; h /= 2; out += w to h }
        if (w != targetW || h != targetH) out += targetW to targetH
        return out
    }

    /**
     * A light unsharp mask on the finished small picture: the fine lines of a badge lose their edge in any downscale. ARGB
     * pixels; alpha stays, a pixel next to a transparent one isn't pushed, so no halo at the logo's outline.
     */
    fun sharpen(px: IntArray, w: Int, h: Int, amount: Float = 0.6f): IntArray {
        val out = px.copyOf()
        fun ch(p: Int, s: Int) = (p ushr s) and 0xFF
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val p = px[i]
            val around = intArrayOf(px[i - 1], px[i + 1], px[i - w], px[i + w])
            if (around.any { (it ushr 24) != (p ushr 24) } || (p ushr 24) == 0) continue
            var v = p and 0xFF000000.toInt()
            for (s in intArrayOf(16, 8, 0)) {
                val mean = around.sumOf { ch(it, s) } / 4f
                val c = ch(p, s) + (ch(p, s) - mean) * amount
                v = v or (c.toInt().coerceIn(0, 255) shl s)
            }
            out[i] = v
        }
        return out
    }

    /** The size a picture fits a box at without changing its shape; never larger than the picture. */
    fun fit(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        val k = min(1f, min(boxW.toFloat() / srcW, boxH.toFloat() / srcH))
        return max(1, Math.round(srcW * k)) to max(1, Math.round(srcH * k))
    }
}
