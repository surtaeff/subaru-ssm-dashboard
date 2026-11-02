package kg.forester.sg.service

/**
 * The colour screen's picture on the wire (hardware/usbscreen/usbscreen.ino): which parts changed since the last frame,
 * and each part packed the way the board unpacks it. Pixels are RGB565 in an IntArray, row by row.
 */
object ScreenFrames {
    /** The board takes a rectangle of at most this many pixels at a time. */
    const val MAX_PX = 16384

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    fun rgb565(argb: Int): Int = ((argb shr 8) and 0xF800) or ((argb shr 5) and 0x07E0) or ((argb shr 3) and 0x001F)

    /**
     * The rectangles that differ between [prev] and [cur] ([prev] null: all of it), found by [tile]-pixel squares, joined
     * along a row and then down while they keep the same span and stay under [MAX_PX]. Edges stay even: AMOLED panels
     * write pixel pairs.
     */
    fun dirty(prev: IntArray?, cur: IntArray, w: Int, h: Int, tile: Int = 16, maxPx: Int = MAX_PX): List<Rect> {
        val tw = (w + tile - 1) / tile
        val th = (h + tile - 1) / tile
        fun changed(tx: Int, ty: Int): Boolean {
            if (prev == null) return true
            val x1 = minOf(w, (tx + 1) * tile)
            val y1 = minOf(h, (ty + 1) * tile)
            for (y in ty * tile until y1) {
                val row = y * w
                for (x in tx * tile until x1) if (prev[row + x] != cur[row + x]) return true
            }
            return false
        }
        // spans of changed tiles on each tile row
        val spans = ArrayList<Rect>()
        for (ty in 0 until th) {
            var tx = 0
            while (tx < tw) {
                if (!changed(tx, ty)) { tx++; continue }
                val start = tx
                while (tx < tw && changed(tx, ty)) tx++
                val x = start * tile
                val y = ty * tile
                spans += Rect(x, y, minOf(w, tx * tile) - x, minOf(h, (ty + 1) * tile) - y)
            }
        }
        // the same span on the next tile rows: one rectangle, as tall as the board takes
        val out = ArrayList<Rect>()
        for (r in spans) {
            val last = out.lastOrNull()
            if (last != null && last.x == r.x && last.w == r.w && last.y + last.h == r.y && last.w * (last.h + r.h) <= maxPx) {
                out[out.size - 1] = last.copy(h = last.h + r.h)
            } else out += r
        }
        // still too big (a very wide panel): cut into strips
        return out.flatMap { r ->
            if (r.w * r.h <= maxPx) listOf(r) else {
                val rows = maxOf(2, (maxPx / r.w) and 1.inv())
                (0 until r.h step rows).map { dy -> Rect(r.x, r.y + dy, r.w, minOf(rows, r.h - dy)) }
            }
        }.map(::even)
    }

    /**
     * [rects] joined until at most [limit] are left, always the pair that adds the least empty area, never past [maxPx]:
     * each rectangle costs the board a line and an answer, so many small ones lose to one a little bigger.
     */
    fun coalesce(rects: List<Rect>, maxPx: Int, limit: Int): List<Rect> {
        if (rects.size <= limit) return rects
        val out = rects.toMutableList()
        fun join(a: Rect, b: Rect): Rect {
            val x = minOf(a.x, b.x)
            val y = minOf(a.y, b.y)
            return Rect(x, y, maxOf(a.x + a.w, b.x + b.w) - x, maxOf(a.y + a.h, b.y + b.h) - y)
        }
        while (out.size > limit) {
            var bestI = -1
            var bestJ = -1
            var least = Long.MAX_VALUE
            for (i in out.indices) for (j in i + 1 until out.size) {
                val u = join(out[i], out[j])
                val area = u.w.toLong() * u.h
                if (area > maxPx) continue
                val waste = area - out[i].w * out[i].h - out[j].w * out[j].h
                if (waste < least) { least = waste; bestI = i; bestJ = j }
            }
            if (bestI < 0) break
            out[bestI] = join(out[bestI], out[bestJ])
            out.removeAt(bestJ)
        }
        return out
    }

    /** Below these a rectangle stays lossless: the board's decoder would take longer than the saved bytes on the wire. */
    private const val JPEG_MIN_PX = 20_000
    private const val JPEG_MIN_BYTES = 20_000

    /** Is [r], [packedBytes] long when packed, worth encoding as JPEG? */
    fun jpegPays(r: Rect, packedBytes: Int): Boolean = r.w * r.h >= JPEG_MIN_PX && packedBytes >= JPEG_MIN_BYTES

    /** The frames a second asked of the head unit: a big panel costs it a whole bitmap a frame, so a weak unit draws fewer. */
    fun fpsFor(chosen: Int, tier: kg.forester.sg.data.Tier, big: Boolean): Int = if (!big) chosen else minOf(chosen, when (tier) {
        kg.forester.sg.data.Tier.LOW -> 8
        kg.forester.sg.data.Tier.MID -> 12
        kg.forester.sg.data.Tier.HIGH -> 30
    })

    private fun even(r: Rect) = Rect(r.x and 1.inv(), r.y and 1.inv(), (r.w + (r.x and 1) + 1) and 1.inv(), (r.h + (r.y and 1) + 1) and 1.inv())

    /**
     * [r] of [px] (a [w]-wide picture) packed: a byte h, then for h & 0x80 a run of (h & 0x7F) + 1 pixels of the one
     * colour that follows, else h + 1 pixels as they are; colours little-endian.
     */
    fun pack(px: IntArray, w: Int, r: Rect): ByteArray {
        val n = r.w * r.h
        fun at(i: Int) = px[(r.y + i / r.w) * w + r.x + i % r.w]
        val out = java.io.ByteArrayOutputStream(n / 2 + 16)
        val lit = IntArray(128)
        var nl = 0
        fun flush() {
            if (nl == 0) return
            out.write(nl - 1)
            for (k in 0 until nl) { out.write(lit[k] and 0xFF); out.write((lit[k] shr 8) and 0xFF) }
            nl = 0
        }
        var i = 0
        while (i < n) {
            val c = at(i)
            var run = 1
            while (i + run < n && run < 128 && at(i + run) == c) run++
            if (run >= 3) {
                flush()
                out.write(0x80 or (run - 1)); out.write(c and 0xFF); out.write((c shr 8) and 0xFF)
                i += run
            } else {
                lit[nl++] = c
                if (nl == 128) flush()
                i++
            }
        }
        flush()
        return out.toByteArray()
    }

    /** [pack] undone, as the board does it; null when the bytes do not make exactly [count] pixels. */
    fun unpack(b: ByteArray, count: Int): IntArray? {
        val out = IntArray(count)
        var i = 0
        var o = 0
        while (i < b.size && o < count) {
            val h = b[i++].toInt() and 0xFF
            val k = (h and 0x7F) + 1
            if (o + k > count) return null
            if (h and 0x80 != 0) {
                if (i + 2 > b.size) return null
                val c = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                i += 2
                repeat(k) { out[o++] = c }
            } else {
                if (i + 2 * k > b.size) return null
                repeat(k) { out[o++] = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8); i += 2 }
            }
        }
        return if (o == count && i == b.size) out else null
    }
}

/**
 * A frame for a big panel (hardware/usbscreen/bigscreen): the changed rectangles, each packed, or as JPEG where that pays.
 * A JPEG isn't the exact picture, so what went that way is sent again lossless afterwards, one rectangle a frame, while
 * no JPEG is going out. [put] sends a line (no newline) with its bytes and returns whether the board took it.
 */
class BigSender(
    private val w: Int, private val h: Int, private val maxPx: Int, private val jpegOn: () -> Boolean,
    private val jpeg: (ScreenFrames.Rect) -> ByteArray?, private val put: (String, ByteArray) -> Boolean,
) {
    private val lossy = ArrayDeque<ScreenFrames.Rect>()

    /** Rectangles still showing a JPEG's approximation. */
    fun pendingRepair() = lossy.size

    fun frame(prev: IntArray?, cur: IntArray): Boolean {
        if (prev == null) lossy.clear()
        var usedJpeg = false
        val rects = ScreenFrames.coalesce(ScreenFrames.dirty(prev, cur, w, h, maxPx = maxPx), maxPx, MAX_RECTS)
        for (r in rects) {
            val packed = ScreenFrames.pack(cur, w, r)
            val small = if (jpegOn() && ScreenFrames.jpegPays(r, packed.size)) jpeg(r)?.takeIf { it.size * 5 / 4 < packed.size } else null
            val ok = if (small != null) {
                usedJpeg = true
                lossy.removeAll { inside(it, r) }
                lossy.addLast(r)
                put("J ${r.x} ${r.y} ${r.w} ${r.h} ${small.size}", small)
            } else {
                lossy.removeAll { inside(it, r) }
                put("P ${r.x} ${r.y} ${r.w} ${r.h} ${packed.size}", packed)
            }
            if (!ok) return false
        }
        if (!usedJpeg && lossy.isNotEmpty()) {
            val r = lossy.removeFirst()
            val packed = ScreenFrames.pack(cur, w, r)
            if (!put("P ${r.x} ${r.y} ${r.w} ${r.h} ${packed.size}", packed)) return false
        }
        return true
    }

    private fun inside(a: ScreenFrames.Rect, b: ScreenFrames.Rect) = a.x >= b.x && a.y >= b.y && a.x + a.w <= b.x + b.w && a.y + a.h <= b.y + b.h

    private companion object {
        /** Each rectangle costs a round trip of about 2 ms plus the board's work. */
        const val MAX_RECTS = 16
    }
}
