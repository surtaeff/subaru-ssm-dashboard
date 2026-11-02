package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kg.forester.sg.data.DriveBoard
import kg.forester.sg.data.HomeLayout
import kg.forester.sg.data.MyBoard
import kg.forester.sg.data.PlacedCard
import kg.forester.sg.data.Repo
import kg.forester.sg.data.ScreenBoards
import kg.forester.sg.data.ScreenCfg
import kg.forester.sg.data.ScreenLayout
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.ScreenPage
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.UsbScreen

/**
 * How a big panel looks, its own choice or as in the app. [gradient] is drawn once and kept ([ScreenRenderer]); the
 * status colours stay recognisable in each. Small boards always get [classic].
 */
class ScreenLook(
    val id: String, val name: String, val bg: Color, val card: Color, val border: Color, val text: Color, val sub: Color,
    val accent: Color, val danger: Color, val gradient: List<Color>? = null, val cardRadius: Float = 0.1f,
    /** The unlit part of a bar: never a colour that could read as a lit one. */
    val track: Color = border,
) {
    companion object {
        /** The drawn backdrop of Студия as a simple gradient for a panel; the other themes are flat there. */
        private val STUDIO_GRADIENT = listOf(Color(0xFF16262F), Color(0xFF263E4B), Color(0xFF3B5866))

        private fun from(p: Palette, id: String, name: String, accent: Color, bg: Color = p.bg) = ScreenLook(
            id, name, bg, p.card, p.border, p.text, p.sub, accent, p.red,
            gradient = if (p.backdrop == Palette.Backdrop.STUDIO) STUDIO_GRADIENT else null,
            cardRadius = if (p.backdrop == Palette.Backdrop.STUDIO) 0.16f else 0.1f,
            // Полночь's border is bright blue: as a bar's track it would look lit
            track = if (p.backdrop == Palette.Backdrop.MIDNIGHT) p.cardHi else p.border,
        )

        /** As the app is drawn now, on the theme's own background. */
        fun current() = from(C.palette, "", "Как в приложении", C.Violet)

        /** What the small boards have always shown: Неон's colours on black in any theme (a screen's stage stays dark), the chosen hue. */
        fun classic() = from(Palette.NEON, "", "Как в приложении", Palette.NEON.accentFor(C.chosen).main, Color.Black)

        /** The four themes, value for value, with the factory hue where a theme takes one. */
        val chosen: List<ScreenLook> by lazy {
            Palette.all.map { from(it, it.id, it.title, it.accentFor(AccentTheme.VIOLET.theme).main) }
        }

        fun of(id: String, big: Boolean) = if (!big) classic() else chosen.firstOrNull { it.id == id } ?: current()
    }
}

/**
 * The colour screen's pages drawn into a bitmap, outside any window (the service sends them while the app is in the
 * background): the same gauge designs as the cards ([drawDial]), tiles in the app's colours, values from [Metrics].
 */
object ScreenRenderer {
    private var segment: Typeface? = null

    /** This moment's values, for drawing outside composition. */
    fun ctxNow() = MetricCtx(Repo.live.value, Repo.tcu.value, Repo.trip.value, Repo.gps.value, Repo.guard.value, Repo.rrValues.value, SensorBox.readings.value, kg.forester.sg.data.Wideband.state.value, android.os.SystemClock.elapsedRealtime())

    /** Everything a page can show: the built-in values, the ticked «Датчики» parameters, the box inputs. */
    fun catalogue(): List<Indicator> = Metrics.all(Repo.rrAvailable.value, Repo.settings.watchIds)

    fun indicator(id: String): Indicator? = Metrics.of(id, Repo.rrAvailable.value)

    /** The page that should be on now: the chosen one, or the first page showing a value past its warning. */
    fun pageToShow(cfg: ScreenCfg, chosen: Int, m: MetricCtx): Int {
        if (cfg.alert) cfg.pages.forEachIndexed { i, p ->
            val ids = if (p.layout.own) ownCards(p.layout).map { it.tile.id } else p.items
            if (ids.any { id -> indicator(id)?.let { it.warn(it.read(m)) } == true }) return i
        }
        return chosen.coerceIn(0, cfg.pages.size - 1)
    }

    /** A saved layout's text and its worked-out places: the text rarely changes, the frames come many times a second. */
    private val placed = java.util.concurrent.ConcurrentHashMap<ScreenLayout, Pair<String, List<PlacedCard>>>()

    /** The cards of the driver's own screen [l], with their places. */
    fun ownCards(l: ScreenLayout): List<PlacedCard> {
        // what the big screen of the app would show, as the screen itself works it out (the factory layout goes by the car)
        val saved = when (l) {
            ScreenLayout.HOME -> HomeLayout.effective(Repo.homeTiles.value, false)
            ScreenLayout.DRIVE -> HomeLayout.driveEffective(Repo.driveTiles.value, false, Repo.carTurbo.value)
            ScreenLayout.MY -> Repo.myTiles.value
            else -> return emptyList()
        }
        placed[l]?.takeIf { it.first == saved }?.let { return it.second }
        val cards = when (l) {
            ScreenLayout.HOME -> ScreenBoards.place(HomeLayout.board, saved, { if (it.style.isNotEmpty()) 1.6f else 1f })
            ScreenLayout.DRIVE -> ScreenBoards.place(DriveBoard, saved, ::driveRowWeight)
            else -> ScreenBoards.place(MyBoard, saved, ::myRowWeight)
        }
        placed[l] = saved to cards
        return cards
    }

    /** The Спорт-дисплей card's own settings: the first one on the driver's screens, else the factory look. */
    private fun sportStyle(): String = listOf(ScreenLayout.HOME, ScreenLayout.DRIVE, ScreenLayout.MY)
        .firstNotNullOfOrNull { l -> ownCards(l).firstOrNull { it.tile.id == "defi" }?.tile?.style } ?: ""

    /** The gradient of a look, drawn once for a size (one bitmap of the screen's size, none on a weak unit). */
    private var backdrop: Triple<String, Long, Bitmap>? = null

    @Synchronized private fun backdropOf(look: ScreenLook, w: Int, h: Int): Bitmap? {
        val g = look.gradient ?: return null
        if (kg.forester.sg.data.DeviceTier.current.value == kg.forester.sg.data.Tier.LOW) return null
        val size = (w.toLong() shl 32) or h.toLong()
        backdrop?.takeIf { it.first == look.id && it.second == size }?.let { return it.third }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val colors = g.map { it.toArgb() }.toIntArray()
        c.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, android.graphics.Shader.TileMode.CLAMP) })
        // a slightly lighter floor
        c.drawRect(0f, h * 0.78f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, h * 0.78f, 0f, h.toFloat(), 0x00FFFFFF, 0x22FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        })
        backdrop = Triple(look.id, size, b)
        return b
    }

    /**
     * [page] upright, [w] x [h] (the panel turned as set); [round] masks it to the circle. [into]: a bitmap of that size to
     * draw on, so a big panel doesn't get a new one a frame.
     */
    fun render(ctx: Context, page: ScreenPage, w: Int, h: Int, round: Boolean, m: MetricCtx = ctxNow(), look: ScreenLook = ScreenLook.classic(), into: Bitmap? = null): Bitmap {
        if (segment == null) segment = runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, kg.forester.sg.R.font.dseg7_bold_italic) }.getOrNull()
        val bmp = into ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(bmp.asImageBitmap())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
            drawRect(look.bg)
            backdropOf(look, w, h)?.let { drawImage(it.asImageBitmap()) }
            if (page.layout.own) {
                own(ctx, page, m, Rect(0f, 0f, w.toFloat(), h.toFloat()), look)
                return@draw
            }
            val items = page.items.map { id -> indicator(id)?.let { it to it.read(m) } }
            // a round panel: the tiles and lists inside the square that fits in the circle
            val inner = if (round) Rect(Offset(w / 2f, h / 2f), minOf(w, h) * 0.354f) else Rect(0f, 0f, w.toFloat(), h.toFloat())
            val full = Rect(0f, 0f, w.toFloat(), h.toFloat())
            val r = when {
                page.layout == ScreenLayout.GAUGE -> full
                // two dials in a circle: each as wide as its radius, a little in from the glass edge
                page.layout == ScreenLayout.GAUGE2 -> if (round) full.deflate(minOf(w, h) * 0.03f) else full
                else -> inner
            }
            layout(ctx, page, items, r, look)
            if (round) {
                // outside the glass stays black
                val path = androidx.compose.ui.graphics.Path().apply {
                    addRect(full)
                    addOval(Rect(Offset(w / 2f, h / 2f), minOf(w, h) / 2f))
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                }
                drawPath(path, Color.Black)
            }
        }
        return bmp
    }

    /** [b] turned [quarter] quarter turns clockwise: from upright to how the panel stands. */
    fun toPanel(b: Bitmap, quarter: Int): Bitmap {
        if (quarter % 4 == 0) return b
        val mtx = android.graphics.Matrix().apply { postRotate(90f * (quarter % 4)) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, mtx, false)
    }

    /** [b] upright into [out] turned [quarter] quarter turns clockwise: as [toPanel], with no new bitmap a frame. */
    fun toPanelInto(b: Bitmap, quarter: Int, out: Bitmap) {
        val c = android.graphics.Canvas(out)
        c.rotate(90f * (quarter % 4))
        when (quarter % 4) {
            1 -> c.translate(0f, -b.height.toFloat())
            2 -> c.translate(-b.width.toFloat(), -b.height.toFloat())
            3 -> c.translate(-b.width.toFloat(), 0f)
        }
        c.drawBitmap(b, 0f, 0f, null)
    }

    /** What stands in for a chart on a panel: the value it plots; and a card named otherwise than its value. */
    private val chartValue = mapOf(
        "chartPedal" to "pedal", "chartBoost" to "boost", "chartAfr" to "afr", "chartTiming" to "timing",
        "chartRpm" to "rpm", "chartTrims" to "afcorr", "chartMaf" to "maf", "pressure" to "map",
    )

    /** The driver's own screen: every card at its place, in its own design. A card with no live value here keeps its place, empty. */
    private fun DrawScope.own(ctx: Context, page: ScreenPage, m: MetricCtx, r: Rect, look: ScreenLook) {
        val avail = Repo.rrAvailable.value
        when (page.layout) {
            ScreenLayout.SPORT, ScreenLayout.SPORT_TABLE -> {
                val red = Repo.engineProfile.value.redlineRpm
                val cfg = SportCfg.parse(sportStyle(), red)
                val text = { t: String -> kg.forester.sg.data.tr(t) }
                if (page.layout == ScreenLayout.SPORT) drawSportArc(cfg, m, avail, r, text) else drawSportTable(cfg, m, avail, r, text)
                return
            }
            else -> Unit
        }
        val gap = minOf(r.width, r.height) * 0.006f
        for (c in ownCards(page.layout)) {
            val cell = Rect(r.left + c.rect.x * r.width + gap, r.top + c.rect.y * r.height + gap,
                r.left + (c.rect.x + c.rect.w) * r.width - gap, r.top + (c.rect.y + c.rect.h) * r.height - gap)
            val t = c.tile
            if (t.id == "defi") {
                drawSportArc(SportCfg.parse(t.style, Repo.engineProfile.value.redlineRpm), m, avail, cell) { kg.forester.sg.data.tr(it) }
                continue
            }
            val ind = indicator(chartValue[t.id] ?: t.id)
            val item = ind?.let { it to it.read(m) }
            when {
                item == null -> tile(null, cell, look)
                // a dial needs a design and room; on a card too flat for one, the number
                t.style.isNotEmpty() && !t.style.startsWith("sd:") && t.id !in chartValue && minOf(cell.width, cell.height) >= 90f -> gauge(ctx, t.style, item, cell)
                else -> tile(item, cell, look)
            }
        }
    }

    private fun DrawScope.layout(ctx: Context, page: ScreenPage, items: List<Pair<Indicator, Float?>?>, r: Rect, look: ScreenLook) {
        val wide = r.width >= r.height
        fun cell(x: Float, y: Float, cw: Float, ch: Float) = Rect(r.left + x, r.top + y, r.left + x + cw, r.top + y + ch)
        val gap = minOf(r.width, r.height) * 0.025f
        when (page.layout) {
            ScreenLayout.GAUGE -> gauge(ctx, page.style, items.getOrNull(0), r)
            ScreenLayout.GAUGE2 -> if (wide) {
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, r.width / 2, r.height))
                gauge(ctx, page.style, items.getOrNull(1), cell(r.width / 2, 0f, r.width / 2, r.height))
            } else {
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, r.width, r.height / 2))
                gauge(ctx, page.style, items.getOrNull(1), cell(0f, r.height / 2, r.width, r.height / 2))
            }
            ScreenLayout.GAUGE_TILES -> if (wide) {
                val gw = minOf(r.height, r.width * 0.55f)
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, gw, r.height))
                tile(items.getOrNull(1), cell(gw + gap, 0f, r.width - gw - gap, r.height / 2 - gap / 2), look)
                tile(items.getOrNull(2), cell(gw + gap, r.height / 2 + gap / 2, r.width - gw - gap, r.height / 2 - gap / 2), look)
            } else {
                val gh = minOf(r.width, r.height * 0.6f)
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, r.width, gh))
                tile(items.getOrNull(1), cell(0f, gh + gap, r.width / 2 - gap / 2, r.height - gh - gap), look)
                tile(items.getOrNull(2), cell(r.width / 2 + gap / 2, gh + gap, r.width / 2 - gap / 2, r.height - gh - gap), look)
            }
            ScreenLayout.TILES4, ScreenLayout.TILES6 -> {
                val n = page.layout.slots
                val cols = if (n == 4) 2 else if (wide) 3 else 2
                val rows = n / cols
                val cw = (r.width - gap * (cols - 1)) / cols
                val ch = (r.height - gap * (rows - 1)) / rows
                for (i in 0 until n) tile(items.getOrNull(i), cell((i % cols) * (cw + gap), (i / cols) * (ch + gap), cw, ch), look)
            }
            ScreenLayout.BIG -> big(items.getOrNull(0), r, look)
            ScreenLayout.LIST -> list(items, r, look)
            else -> Unit // the driver's own screens: [own]
        }
    }

    private fun paint(size: Float, color: Color, bold: Boolean = true, align: Paint.Align = Paint.Align.LEFT, face: Typeface? = null) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color.toArgb()
            textAlign = align
            typeface = face ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** A number must never lose digits: its paint made smaller until [t] fits [maxW]. */
    private fun shrink(t: String, p: Paint, maxW: Float): String {
        while (p.measureText(t) > maxW && p.textSize > 6f) p.textSize *= 0.92f
        return t
    }

    /** [t] (a name) cut to fit [maxW] with its paint. */
    private fun fit(t: String, p: Paint, maxW: Float): String {
        // the text as shown is what's cut: a cut name has no translation
        var s = kg.forester.sg.data.tr(t)
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        return s
    }

    private fun DrawScope.text(t: String, x: Float, y: Float, p: Paint) = drawContext.canvas.nativeCanvas.drawText(kg.forester.sg.data.tr(t), x, y, p)

    /** A round gauge of one value in [style], as on the cards: the dial, the number in the middle, the name under it. */
    private fun DrawScope.gauge(ctx: Context, style: String, it: Pair<Indicator, Float?>?, r: Rect) {
        val side = minOf(r.width, r.height)
        val l = r.left + (r.width - side) / 2
        val t = r.top + (r.height - side) / 2
        if (it == null) return
        val (ind, v) = it
        val st = dialStyle(style)
        val (lo, hi, step) = niceScale(ind.min, ind.max)
        val div = if (hi >= 1000f) 1000f else 1f
        val photo = st.image?.let { name -> kg.forester.sg.data.GaugeImages.bitmap(ctx, name)?.asImageBitmap() }
        inset(l, t, size.width - l - side, size.height - t - side) {
            if (st.lens && st.image == null) {
                // no drum on a screen fed frame by frame; the name goes under the number, there is no rotated text here
                val lay = LensMath.layout(side / density)
                drawLensBase(lay, st, LensMath.endLabel(lo, step, div), LensMath.endLabel(hi, step, div), marks = lensMarks(st, lo, hi, div))
                val live = LensLive(lay, st, side / 2f, center, low = true)
                val warnAt = LensMath.warnShare(v, lo, hi, WarnRule(ind.warnAbove, ind.warnBelow, ind.warnAbs), null)
                if (v != null) with(live) { crescent(LensMath.arcFrom(lo, hi), LensMath.share(v, lo, hi), warnAt > 0f && !lay.outer) }
                if (warnAt > 0f && lay.outer) with(live) { warnArc(warnAt) }
                if (!st.hideValue) {
                    val disk = side / 2f * lay.disk
                    val p = paint(LensMath.digitSize(disk, LensMath.widest(ind.format, lo, hi).length, st.numberScale), st.value, false, Paint.Align.CENTER)
                    text(shrink(ind.text(v), p, disk * 1.7f), center.x, center.y + p.textSize * 0.2f, p)
                    val np = paint(disk * 0.17f, st.unit, false, Paint.Align.CENTER)
                    text(fit("${ind.short} ${ind.unit}", np, disk * 1.5f), center.x, center.y + p.textSize * 0.2f + np.textSize * 1.4f, np)
                }
                return@inset
            }
            val ro = st.readout
            val at = (v ?: lo).coerceIn(lo, hi)
            drawDial(at, lo, hi, step, div, ind.warnAbove, st, photo, layer = if (ro != null && !st.hideValue) DialLayer.FACE else DialLayer.ALL)
            val c = center
            val hot = ind.warn(v)
            if (!st.hideValue) {
                if (ro != null) {
                    val bw = side * 0.44f
                    val bh = bw * 0.38f
                    val top = c.y + side * 0.2f
                    drawRoundRect(ro.bg, Offset(c.x - bw / 2, top), Size(bw, bh), CornerRadius(bw * 0.08f))
                    drawRoundRect(ro.border, Offset(c.x - bw / 2, top), Size(bw, bh), CornerRadius(bw * 0.08f), style = Stroke(2f))
                    val p = paint(bh * 0.62f, if (hot) C.Red else ro.fg, align = Paint.Align.RIGHT, face = if (ro.segments) segment else null)
                    text(ind.text(v).replace('—', '-'), c.x + bw / 2 - bw * 0.06f, top + bh * 0.78f, p)
                    // the needle passes over the read-out window, as on the cards
                    drawDial(at, lo, hi, step, div, ind.warnAbove, st, photo, layer = DialLayer.NEEDLE)
                } else {
                    val p = paint(side * 0.13f * st.numberScale, if (hot) C.Red else st.value, align = Paint.Align.CENTER)
                    text(shrink(ind.text(v), p, side * 0.34f * st.numberScale), c.x, c.y + side * 0.2f, p)
                }
            }
            // the name and the unit in the opening at the bottom of the scale
            val unit = if (div > 1f) "×1000 ${ind.unit}" else ind.unit
            if (ro != null && !st.hideValue) {
                // under the read-out window: one line
                val np = paint(side * 0.042f, st.unit, false, Paint.Align.CENTER)
                text(fit("${ind.short} $unit", np, side * 0.42f), c.x, c.y + side * 0.43f, np)
            } else {
                val np = paint(side * 0.05f, st.unit, false, Paint.Align.CENTER)
                text(fit(ind.short, np, side * 0.36f), c.x, c.y + side * 0.36f, np)
                val up = paint(side * 0.042f, st.unit.copy(alpha = 0.7f), false, Paint.Align.CENTER)
                text(fit(unit, up, side * 0.3f), c.x, c.y + side * 0.42f, up)
            }
        }
    }

    /** A tile as on the home screen: the name, the number (red past its warning), the unit and a bar of the scale. */
    private fun DrawScope.tile(it: Pair<Indicator, Float?>?, r: Rect, look: ScreenLook) {
        val rad = minOf(r.width, r.height) * look.cardRadius
        drawRoundRect(look.card, r.topLeft, r.size, CornerRadius(rad))
        drawRoundRect(look.border, r.topLeft, r.size, CornerRadius(rad), style = Stroke(2f))
        if (it == null) return
        val (ind, v) = it
        val hot = ind.warn(v)
        val pad = minOf(r.width, r.height) * 0.1f
        val avail = r.width - pad * 2
        val num = paint(r.height * 0.36f, if (hot) look.danger else look.text)
        val unit = paint(r.height * 0.13f, look.sub, false)
        val vt = ind.text(v)
        // a narrow tile: the unit goes under the number, the number keeps its size
        val inline = num.measureText(vt) + unit.measureText(ind.unit) + pad * 0.4f <= avail
        val name = paint(r.height * 0.15f, look.sub, false)
        val title = ind.short
        while (name.measureText(title) > avail && name.textSize > r.height * 0.11f) name.textSize *= 0.94f
        text(fit(title, name, avail), r.left + pad, r.top + pad + name.textSize * 0.8f, name)
        shrink(vt, num, if (inline) avail - unit.measureText(ind.unit) - pad * 0.4f else avail)
        val baseline = r.top + r.height * (if (inline) 0.68f else 0.62f)
        text(vt, r.left + pad, baseline, num)
        if (inline) text(ind.unit, r.left + pad + num.measureText(vt) + pad * 0.3f, baseline, unit)
        else text(fit(ind.unit, unit, avail), r.left + pad, baseline + unit.textSize * 1.25f, unit)
        bar(ind, v, Rect(r.left + pad, r.bottom - pad - r.height * 0.06f, r.right - pad, r.bottom - pad), hot, look)
    }

    private fun DrawScope.bar(ind: Indicator, v: Float?, r: Rect, hot: Boolean, look: ScreenLook) {
        val f = if (v == null || ind.max <= ind.min) 0f else ((v - ind.min) / (ind.max - ind.min)).coerceIn(0f, 1f)
        drawRoundRect(look.track, r.topLeft, r.size, CornerRadius(r.height / 2))
        if (f > 0f) drawRoundRect(if (hot) look.danger else look.accent, r.topLeft, Size(r.width * f, r.height), CornerRadius(r.height / 2))
    }

    private fun DrawScope.big(it: Pair<Indicator, Float?>?, r: Rect, look: ScreenLook) {
        if (it == null) return
        val (ind, v) = it
        val hot = ind.warn(v)
        val cx = r.center.x
        val name = paint(r.height * 0.11f, look.sub, false, Paint.Align.CENTER)
        text(fit(ind.title, name, r.width * 0.9f), cx, r.top + r.height * 0.18f, name)
        val num = paint(r.height * 0.42f, if (hot) look.danger else look.text, align = Paint.Align.CENTER)
        while (num.measureText(ind.text(v)) > r.width * 0.9f && num.textSize > 10f) num.textSize *= 0.9f
        text(ind.text(v), cx, r.top + r.height * 0.62f, num)
        text(ind.unit, cx, r.top + r.height * 0.78f, paint(r.height * 0.09f, look.sub, false, Paint.Align.CENTER))
        bar(ind, v, Rect(r.left + r.width * 0.08f, r.top + r.height * 0.86f, r.right - r.width * 0.08f, r.top + r.height * 0.9f), hot, look)
    }

    private fun DrawScope.list(items: List<Pair<Indicator, Float?>?>, r: Rect, look: ScreenLook) {
        val shown = items.filterNotNull()
        if (shown.isEmpty()) return
        val rh = r.height / shown.size.coerceAtLeast(4)
        shown.forEachIndexed { i, (ind, v) ->
            val y = r.top + rh * i
            val hot = ind.warn(v)
            val name = paint(rh * 0.42f, look.sub, false)
            val num = paint(rh * 0.55f, if (hot) look.danger else look.text, align = Paint.Align.RIGHT)
            val unit = paint(rh * 0.32f, look.sub, false)
            val right = r.right - rh * 0.2f
            val uw = unit.measureText(ind.unit) + rh * 0.15f
            text(ind.unit, right - uw + rh * 0.15f, y + rh * 0.68f, unit)
            text(shrink(ind.text(v), num, r.width * 0.45f), right - uw, y + rh * 0.7f, num)
            text(fit(ind.short, name, r.width - num.measureText(ind.text(v)) - uw - rh * 0.3f), r.left + rh * 0.15f, y + rh * 0.66f, name)
            if (i < shown.size - 1) drawLine(look.border, Offset(r.left, y + rh), Offset(r.right, y + rh), 1.5f)
        }
    }

    /** The upright size pages are drawn at for this setting (the panel's when no board is chosen yet: a sample). */
    fun canvas(cfg: ScreenCfg): Triple<Int, Int, Boolean> {
        val m = ScreenModel.of(cfg.model) ?: ScreenModel.WS_AMOLED_143
        val (w, h) = UsbScreen.canvasSize(m, cfg.rotation)
        return Triple(w, h, m.round)
    }
}
