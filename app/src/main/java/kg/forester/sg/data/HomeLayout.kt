package kg.forester.sg.data

/** Place of a card in the free layout, as fractions of the card area (0…1). */
data class FreeRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun format() = listOf(x, y, w, h).joinToString(",") { "%.4f".format(java.util.Locale.US, it) }

    companion object {
        fun parse(s: String?): FreeRect? {
            val f = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
            if (f.size != 4) return null
            return FreeRect(f[0].coerceIn(0f, 1f), f[1].coerceIn(0f, 1f), f[2].coerceIn(0.02f, 1f), f[3].coerceIn(0.02f, 1f))
        }
    }
}

/**
 * One card on an editable screen: shown or not, how many grid columns it spans, gauge design ("" = normal look)
 * and its place when the screen uses the free layout.
 */
data class TileCfg(val id: String, val on: Boolean, val span: Int = 1, val style: String = "", val free: FreeRect? = null)

/**
 * Which cards a screen shows, in which order and how wide. Saved in the settings under [key] as
 * "[!free;]id:on:span:style:x,y,w,h;…", "" means the factory layout; "!free" switches to cards placed anywhere. Cards added in later versions are appended switched off, so a saved layout never
 * loses them. With [stretch] the cards of a row share the full width, otherwise the rest of the row stays empty.
 */
class Board(
    val key: String,
    val columns: Int,
    val maxSpan: Int,
    val stretch: Boolean,
    val defaults: List<TileCfg>,
    /** Cards that exist only in the free layout (the home 3D block is a fixed band in the grid). */
    val freeOnly: Set<String> = emptySet(),
) {
    fun isFree(s: String) = s.startsWith(FREE)

    fun parse(s: String): List<TileCfg> {
        if (s.isBlank()) return defaults
        val known = defaults.associateBy { it.id }
        val saved = s.removePrefix(FREE).split(';').mapNotNull { e ->
            val f = e.split(':')
            val id = f.getOrNull(0) ?: return@mapNotNull null
            if (id !in known) return@mapNotNull null
            // the style may itself contain ':' ("photo:<file>"); the free rect is always the last field (5+ fields)
            val withRect = f.size >= 5
            TileCfg(
                id, f.getOrNull(1) == "1", (f.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, maxSpan),
                if (withRect) f.subList(3, f.size - 1).joinToString(":") else f.getOrNull(3) ?: "",
                if (withRect) FreeRect.parse(f.last()) else null,
            )
        }.distinctBy { it.id }
        val missing = defaults.filter { d -> saved.none { it.id == d.id } }.map { it.copy(on = false) }
        return saved + missing
    }

    fun format(list: List<TileCfg>, free: Boolean = false) = (if (free) FREE else "") +
        list.joinToString(";") { "${it.id}:${if (it.on) 1 else 0}:${it.span}:${it.style}:${it.free?.format() ?: ""}" }

    /**
     * Free-layout places for the cards that have none yet, taken from where the grid shows them now, so switching
     * to the free layout changes nothing on screen. [rowWeight] gives the relative height of a card's row,
     * [band] the top and height of the grid within the free area.
     */
    fun withFreeRects(list: List<TileCfg>, rowWeight: (TileCfg) -> Float, band: Pair<Float, Float> = 0f to 1f, columns: Int = this.columns): List<TileCfg> {
        val rows = rows(list, columns)
        val heights = rows.map { r -> r.maxOf(rowWeight) }
        val total = heights.sum().takeIf { it > 0f } ?: return list
        val placed = HashMap<String, FreeRect>()
        var y = band.first
        rows.forEachIndexed { i, row ->
            val h = heights[i] / total * band.second
            val width = if (stretch) row.sumOf { it.span }.toFloat() else columns.toFloat()
            var x = 0f
            row.forEach { t ->
                val w = t.span / width
                placed[t.id] = FreeRect(x + 0.004f, y + 0.006f, w - 0.008f, h - 0.012f)
                x += w
            }
            y += h
        }
        return list.map { t -> if (t.free != null) t else t.copy(free = placed[t.id] ?: FreeRect(0.35f, 0.35f, 0.3f, 0.3f)) }
    }

    private companion object {
        const val FREE = "!free;"
    }

    fun rows(list: List<TileCfg>, columns: Int = this.columns): List<List<TileCfg>> {
        val rows = mutableListOf<MutableList<TileCfg>>()
        var used = columns
        list.filter { it.on && it.id !in freeOnly }.forEach { t ->
            if (used + t.span > columns) { rows += mutableListOf<TileCfg>(); used = 0 }
            rows.last() += t
            used += t.span
        }
        return rows
    }
}

/** Values of the common catalogue (ui/Metrics.kt) offered as home cards since 0.9-beta12. */
val MORE_VALUES = listOf(
    "rpm", "speed", "oil", "load", "injDuty", "tripAvg", "tripFuel", "fuelLeft", "iamV", "fbkc", "flkc",
    "gaugeKpa", "targetBoost", "ve", "gear", "lockupPct", "slip", "transfer", "altitude",
)

/** The ready full-card panels (ui/GaugePanels.kt) and the G-force card: off everywhere until the driver picks one. */
val GAUGE_PANELS = listOf("gforce", "p_tacho_blue", "p_tacho_orange", "p_tacho_teal", "p_turbo", "p_tilt", "p_gradar", "p_web", "p_pid", "p_accel", "p_brake")

/** Engine guard, performance and sensor cards (see ui/ExtraCards.kt), offered on the home and "Динамика" boards. */
val EXTRA_CARDS = listOf("defi", "navcluster", "gmeter", "incline", "iam", "btarget", "peaks", "run", "turbo", "tyres", "service") + (1..6).map { "s$it" } + GAUGE_PANELS


/**
 * What is known of a turbo on another make, whose ECU doesn't say ([state], by [TurboWatch] and the unit's PIDs) and
 * what the layouts were built for ([applied], taken from [state] at each connection, so nothing moves in the middle of
 * a drive). Unknown shows the picture without boost: a missing card is less wrong than 0.00 бар on a car with no turbo.
 * Another VIN is another car: nothing of the last one's stays.
 */
data class OtherTurbo(val car: String = "", val state: TurboState = TurboState.UNKNOWN, val applied: TurboState = TurboState.UNKNOWN) {
    val shown get() = applied == TurboState.TURBO

    /** [id]: the car's VIN, "" if it gave none, which says nothing of a change. */
    fun identified(id: String) = when {
        id.isEmpty() || id == car -> this
        car.isEmpty() -> copy(car = id)
        else -> OtherTurbo(id)
    }

    fun connected() = copy(applied = state)
    fun with(s: TurboState) = if (s == state) this else copy(state = s)
    fun format() = "$car|${state.name}|${applied.name}"

    companion object {
        fun parse(s: String): OtherTurbo {
            val f = s.split('|')
            if (f.size != 3) return OtherTurbo()
            // the older format: seen true/false, applied true/empty
            fun st(v: String) = when (v) {
                "true" -> TurboState.TURBO
                "false", "" -> TurboState.UNKNOWN
                else -> TurboState.entries.firstOrNull { it.name == v } ?: TurboState.UNKNOWN
            }
            return OtherTurbo(f[0], st(f[1]), st(f[2]))
        }
    }
}

/** One card of a saved layout and where it goes, as fractions of the screen. */
data class PlacedCard(val tile: TileCfg, val rect: FreeRect)

/** A layout of the driver's own screens worked out for a panel with no window of its own (the big USB screens). */
object ScreenBoards {
    /** The shown cards of [saved] (the layout's text; "" factory), in order, each with its place. */
    fun place(board: Board, saved: String, rowWeight: (TileCfg) -> Float, columns: Int = board.columns): List<PlacedCard> {
        val list = board.parse(saved)
        val shown = if (board.isFree(saved)) list.filter { it.on } else board.rows(list, columns).flatten()
        val placed = board.withFreeRects(list, rowWeight, columns = columns).associateBy { it.id }
        return shown.map { t ->
            val r = (if (board.isFree(saved)) t.free else null) ?: placed.getValue(t.id).free!!
            // a card hanging over the edge is moved in, as the screen's own free layout never shows it cut
            val w = r.w.coerceAtMost(1f)
            val h = r.h.coerceAtMost(1f)
            PlacedCard(t, FreeRect(r.x.coerceAtMost(1f - w), r.y.coerceAtMost(1f - h), w, h))
        }
    }
}

object HomeLayout {
    const val COLUMNS = 5

    /** The night road card (ui/NightRoad.kt) of another make, whose 3D block shows the stand-in car. */
    const val ROAD = "road3d"

    /** The factory layout: the 15 cards of v0.8, then extras that start switched off. */
    val board = Board(
        "homeTiles", COLUMNS, 3, stretch = false,
        defaults = listOf(
            "coolant", "intake", "battery", "throttle", "maf",
            "afr", "afcorr", "aflearn", "timing", "knock",
            "inj", "wgdc", "fuel", "boost", "gps",
        ).map { TileCfg(it, true) } + listOf(
            "pedal", "avcs", "map", "atm", "alt", "atf", "per100", "range", "tach", "speedo", "car3d", "minimap",
        ).map { TileCfg(it, false) } + TileCfg(ROAD, false, ROAD_SPAN) + EXTRA_CARDS.map { TileCfg(it, false, when (it) { "defi", in GAUGE_PANELS -> 2; "navcluster" -> 3; else -> 1 }) } +
            MORE_VALUES.map { TileCfg(it, false) } +
            OUT_WIDGETS.map { TileCfg(it, false) },
        freeOnly = setOf("car3d"),
    )

    private const val ROAD_SPAN = 2

    /**
     * Another make's factory cards ([ids], see [CarKind.obdHomeCards]) as a layout: with the night road card where the
     * three rows have room for it. The rpm and speed cards give way to it first (the dials above show those anyway), then
     * the barometer and the pedal, which a driver looks at least; nothing else does.
     */
    fun obdTiles(ids: List<String>): List<TileCfg> {
        val room = COLUMNS * 3 - ROAD_SPAN
        val slim = ids - listOf("rpm", "speed", "atm", "pedal").filter { it in ids }.take((ids.size - room).coerceAtLeast(0)).toSet()
        return if (slim.size > room) ids.map { TileCfg(it, true) } else slim.map { TileCfg(it, true) } + TileCfg(ROAD, true, ROAD_SPAN)
    }

    fun parse(s: String) = board.parse(s)
    fun rows(list: List<TileCfg>, columns: Int = COLUMNS) = board.rows(list, columns)

    /** What a 7" or 8" factory screen shows, in this order; the ATF never: on a manual it would be an empty card. */
    private val SMALL_PICKS = listOf("coolant", "boost", "intake", "battery", "afr", "knock", "per100", "timing")

    /** The shown cards in order with their widths, plain ones only: what a layout is, whatever else its text holds. */
    private fun shape(list: List<TileCfg>) = list.filter { it.on }.map { it.id to it.span }

    /**
     * The layout is one this app wrote and the driver has not touched: empty, or the cards of the factory screen, of the
     * one for a car without a turbo or for a Link, or of what another make's car fills ([CarKind.obdHomeCards]). An
     * older version wrote the same cards with other ones switched off after them. Any change of the driver's (a card,
     * its place, its width, its dial, the free layout) makes it his.
     */
    fun isUntouched(saved: String) = isFactory(saved)

    /**
     * The one-time step for another make's layout this app wrote before it kept a mark (when a car with no answer was
     * taken for a turbo one): only an untouched layout, and only by what the applied state [applied] says. NON_TURBO: the
     * boost card is switched off, every other card and its place as saved. TURBO: the same text, so the caller writes
     * the mark. UNKNOWN waits for evidence (null), and so does a layout that is the driver's.
     */
    fun migrateUnmarked(saved: String, applied: TurboState): String? {
        if (saved.isBlank() || !isUntouched(saved)) return null
        return when (applied) {
            TurboState.UNKNOWN -> null
            TurboState.TURBO -> saved
            TurboState.NON_TURBO -> board.format(parse(saved).map { if (it.id == "boost") it.copy(on = false) else it })
        }
    }

    private fun isFactory(saved: String): Boolean {
        if (saved.isBlank()) return true
        if (board.isFree(saved)) return false
        val list = parse(saved)
        if (list.any { it.on && (it.style.isNotEmpty() || it.free != null) }) return false
        // the night road card at its place behind the factory cards is part of the factory screen, narrowed it is the driver's
        val cards = shape(list).let { if (it.lastOrNull() == (ROAD to ROAD_SPAN)) it.dropLast(1) else it }
        if (cards == shape(board.defaults) || cards == shape(parse(atmoDefault())) || cards == shape(parse(linkDefault()))) return true
        if (cards.any { it.second != 1 }) return false
        val ids = cards.map { it.first }
        // the "maf" and "map" a car has both of: the first is all it shows
        return listOf(ids.toSet(), ids.toSet() + "map").any { seen -> listOf(false, true).any { CarKind.obdHomeCards(seen, it) == ids } }
    }

    /**
     * The layout to show: on a small screen a factory one comes down to eight cards, the most important first, and
     * anything the driver saved, free layouts too, is read as it is.
     */
    fun effective(saved0: String, small: Boolean): String {
        // another make over OBD-II with no turbo known: the layout with no boost until something is saved
        val saved = forCar(saved0, CarKind.other.value && Repo.obdMode.value, Repo.carTurbo.value)
        if (!small || !isFactory(saved)) return saved
        val list = parse(saved)
        val shown = list.filter { it.on }
        // l/100 reads better than l/h: it takes the place of «fuel», switched off in the factory list
        val offer = if (shown.any { it.id == "fuel" } && shown.none { it.id == "per100" }) shown.map { if (it.id == "fuel") it.copy(id = "per100") else it } else shown
        val picks = SMALL_PICKS.mapNotNull { id -> offer.firstOrNull { it.id == id } }
        // what a car hasn't got is filled from its own list; on the factory screen with no turbo that is the range
        val atmo = shape(list) == shape(parse(atmoDefault()))
        val fill = (if (atmo) offer.filter { it.id == "range" } else emptyList()) + offer.filter { it.id != "atf" }
        // the night road card keeps its two places, the cards take what is left
        val road = shown.firstOrNull { it.id == ROAD }
        val on = (picks + fill).filter { it.id != ROAD }.distinctBy { it.id }.take(SMALL_CARDS - (road?.span ?: 0)) + listOfNotNull(road)
        return board.format(on + list.filter { t -> on.none { it.id == t.id } }.map { it.copy(on = false) })
    }

    /** Four columns on a small screen for as many cards as two such rows hold; a saved screen of more keeps its five. */
    fun columnsFor(list: List<TileCfg>, small: Boolean): Int =
        if (small && list.filter { it.on && it.id !in board.freeOnly }.sumOf { it.span } in 6..SMALL_CARDS) 4 else COLUMNS

    /** Real boost on a car that got the no-turbo factory screen: back to the factory one. Anything the driver saved stays. */
    fun tilesAfterBoost(saved: String): String = if (saved == atmoDefault()) "" else saved

    /** A car whose ECU says: the init (see EngineId.turbo) or a Link's setting. Another make: see [OtherTurbo]. Nothing known: the factory picture, with boost. */
    fun carHasTurbo(initKnown: Boolean, wgdcReported: Boolean, link: Boolean, linkTurbo: Boolean) = when {
        link -> linkTurbo
        initKnown -> wgdcReported
        else -> true
    }

    /**
     * «Динамика» on a small screen until the driver saves his own: four gauges, two charts, three wide tiles. [turbo] is
     * decided per connection (see Repo.carTurbo), so the screen doesn't change in the middle of a drive, unless real boost
     * shows that a car taken for one without a turbo has one.
     */
    fun driveEffective(saved: String, small: Boolean, turbo: Boolean): String {
        if (saved.isNotBlank()) return saved
        // no turbo: no boost gauge, boost chart or wastegate at any size, until the driver saves his own
        if (!small) {
            if (turbo) return saved
            val off = setOf("boost", "chartBoost", "wgdc")
            return DriveBoard.format(DriveBoard.defaults.map { if (it.id in off) it.copy(on = false) else it })
        }
        val on = listOf("throttle", if (turbo) "boost" else "pedal", "load", "knock").map { TileCfg(it, true, 3) } +
            listOf(if (turbo) "chartBoost" else "chartTiming", "chartAfr").map { TileCfg(it, true, 6) } +
            listOf("injDuty", "maf", "avcs").map { TileCfg(it, true, 4) }
        return DriveBoard.format(on + DriveBoard.defaults.filter { d -> on.none { it.id == d.id } }.map { it.copy(on = false) })
    }

    private const val SMALL_CARDS = 8

    /**
     * The factory layout of another make on OBD-II ([otherObd]) with no turbo ([turbo] false: unknown or none) until the
     * app or the driver has saved one: the same cards with the night road card for boost and wastegate. Anything saved is the saved one.
     */
    fun forCar(saved: String, otherObd: Boolean, turbo: Boolean): String =
        if (saved.isBlank() && otherObd && !turbo) board.format(board.defaults.map {
            when (it.id) {
                "boost", "wgdc" -> it.copy(on = false)
                ROAD -> it.copy(on = true) // in the two places they leave
                else -> it
            }
        }) else saved

    /** The factory layout for an engine without a turbo: boost and wastegate give way to range and ATF. */
    fun atmoDefault(): String = board.format(board.defaults.map {
        when (it.id) {
            "wgdc" -> it.copy(on = false)
            "boost" -> it.copy(on = false)
            "range", "atf" -> it.copy(on = true)
            else -> it
        }
    }.sortedBy { if (it.id == "range" || it.id == "atf") board.defaults.indexOfFirst { d -> d.id == "wgdc" } else board.defaults.indexOf(it) })

    /**
     * The factory layout for a sports ECU on the CAN (Link): what its stream gives instead of the cards it leaves
     * empty (MAF, the A/F trims, knock correction, wastegate).
     */
    fun linkDefault(): String {
        val on = listOf(
            "coolant", "intake", "battery", "throttle", "oil",
            "afr", "timing", "map", "inj", "gaugeKpa",
            "boost", "fuel", "per100", "range", "gps",
        )
        return board.format(on.map { TileCfg(it, true) } + board.defaults.filter { it.id !in on }.map { it.copy(on = false) })
    }

    /**
     * "Просто": a large 3D block with the dials and five big numbers that matter while driving, for those who
     * find the factory screen too busy. [turbo] picks boost over ATF.
     */
    fun simple(turbo: Boolean, automatic: Boolean): String {
        val picks = listOf("coolant", "battery", "per100", "range", if (turbo) "boost" else if (automatic) "atf" else "intake")
        val list = picks.map { TileCfg(it, true) } + board.defaults.filter { it.id !in picks }.map { it.copy(on = false) }
        return board.format(list)
    }

    fun clusterWeight(size: Int) = when (size) {
        0 -> 0f
        2 -> 3.0f
        3 -> 4.6f
        else -> 1.9f
    }
}

/** "Динамика": 12 columns, so five gauges (span 2) and six tiles (span 2) each fill a row as before. */
val DriveBoard = Board(
    "driveTiles", 12, 12, stretch = true,
    defaults = listOf(
        TileCfg("pedal", true, 2), TileCfg("throttle", true, 2), TileCfg("boost", true, 2),
        TileCfg("load", true, 2), TileCfg("knock", true, 2),
        TileCfg("chartPedal", true, 6), TileCfg("chartBoost", true, 6),
        TileCfg("chartAfr", true, 6), TileCfg("chartTiming", true, 6),
        TileCfg("avcs", true, 2), TileCfg("injDuty", true, 2), TileCfg("maf", true, 2),
        TileCfg("throttleMotor", true, 2), TileCfg("wgdc", true, 2), TileCfg("pressure", true, 2),
        TileCfg("rpm", false, 2), TileCfg("chartRpm", false, 6), TileCfg("chartTrims", false, 6),
        TileCfg("chartMaf", false, 6),
    ) + EXTRA_CARDS.map { TileCfg(it, false, if (it == "navcluster") 12 else if (it == "defi" || it in GAUGE_PANELS) 6 else if (it == "gmeter" || it == "peaks") 4 else 3) },
)

/** "АКПП": selector 3 + ATF 4 + torque converter 5 columns, the proportions of the fixed layout. */
val TcuBoard = Board(
    "tcuTiles", 12, 12, stretch = true,
    defaults = listOf(
        TileCfg("range", true, 3), TileCfg("atf", true, 4), TileCfg("converter", true, 5),
        TileCfg("lockup", true, 3), TileCfg("line", true, 3), TileCfg("awd", true, 3), TileCfg("wheels", true, 3),
        TileCfg("chartAtf", true, 6), TileCfg("chartLock", true, 6),
        TileCfg("chartTurbine", false, 6), TileCfg("chartWheels", false, 6),
    ),
)

/** The SensorBox outputs' widgets (button or slider) a screen can show. */
val OUT_WIDGETS = (1..8).map { "outw_$it" }

/** SensorBox inputs a «Мой экран» card can show (box:A0…), whichever board is fitted. */
val BOX_VALUES = listOf("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "TC0", "F0").map { "box_$it" }

/** Places for the own CAN channels (C1…C8, see CanChannels.newId), as for the SensorBox inputs. */
val CAN_VALUES = (1..8).map { "can_C$it" }

/**
 * «Мой экран»: the driver's own screen next to the author's ones. Any value of the app, the extra cards and the
 * SensorBox inputs; it starts with three dials and four numbers.
 */
val MyBoard: Board by lazy {
    val on = listOf(
        TileCfg("rpm", true, 4, "neon"), TileCfg("speed", true, 4, "neon"), TileCfg("coolant", true, 4, "neon"),
        TileCfg("oil", true, 3), TileCfg("battery", true, 3), TileCfg("per100", true, 3), TileCfg("range", true, 3),
    )
    val rest = (kg.forester.sg.ui.Metrics.builtIn.map { it.id } + kg.forester.sg.ui.Metrics.derivedIds).filter { id -> on.none { it.id == id } }.map { TileCfg(it, false, 3) } +
        EXTRA_CARDS.map { TileCfg(it, false, when (it) { "navcluster" -> 12; "defi", in GAUGE_PANELS -> 6; else -> 4 }) } + BOX_VALUES.map { TileCfg(it, false, 3) } +
        CAN_VALUES.map { TileCfg(it, false, 3) } + OUT_WIDGETS.map { TileCfg(it, false, 3) }
    Board("myTiles", 12, 12, stretch = true, defaults = on + rest)
}

/** Editable layouts by their settings key. */
object Boards {
    fun byKey(key: String) = when (key) {
        DriveBoard.key -> DriveBoard
        TcuBoard.key -> TcuBoard
        "myTiles" -> MyBoard
        else -> HomeLayout.board
    }
}
