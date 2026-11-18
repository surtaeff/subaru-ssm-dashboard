package kg.forester.sg.data

/*
 * The ready-made layouts of every board. The rules they keep (see the presets design notes):
 * - the values that guard the engine (coolant, oil, boost on a turbo, ATF on an automatic) sit in the first row, on the
 *   driver's side, and in the same places across the presets of a board, so switching presets doesn't move them;
 * - a driving layout has one to three big items read in one glance and at most a row of four numbers under them; on a
 *   7–8" screen cards are dropped, never made smaller (CarTokens keeps the text at 20′ of arc);
 * - the dense ones are for a parked car and say so;
 * - grid rows add up to the board's width, so no row is left half empty by mistake.
 */

private fun t(id: String, span: Int = 1, style: String = "") = TileCfg(id, true, span, style)
private fun row(span: Int, vararg ids: String) = ids.map { t(it, span) }
private fun f(id: String, x: Float, y: Float, w: Float, h: Float, style: String = "") = TileCfg(id, true, 3, style, FreeRect(x, y, w, h))

const val G_DRIVE = "Езда"
const val G_ENGINE = "Мотор и турбо"
const val G_OFFROAD = "Бездорожье и АКПП"
const val G_NIGHT = "Ночь и минимум"
const val G_DIAG = "Диагностика"
const val G_PANELS = "Готовые панели"

/** The order of the groups in the window. */
val PRESET_GROUPS = listOf(G_DRIVE, G_ENGINE, G_OFFROAD, G_NIGHT, G_DIAG, G_PANELS)

private val ATF_FOR_INTAKE = listOf(Upgrade("intake", Need.TCU, "atf"))

private fun my(
    id: String, name: String, about: String, group: String, tiles: List<TileCfg>, free: Boolean = false, small: List<TileCfg>? = null,
    needs: Set<Need> = emptySet(), reserve: List<String> = emptyList(), swaps: Map<String, List<String>> = emptyMap(),
    upgrades: List<Upgrade> = emptyList(),
) = BoardPreset("myTiles", name, about, free, tiles, emptyMap(), small, reserve, needs, "myTiles/$id", group, swaps, upgrades)

private fun panel(id: String, name: String, about: String, needs: Set<Need> = emptySet()) =
    my(id, name, about, G_PANELS, listOf(t(id, 12)), needs = needs)

private val MY = listOf(
    my(
        "everyday", "Каждый день", "Скорость, температуры ОЖ и масла крупно, внизу напряжение, расход, запас хода и впуск", G_DRIVE,
        listOf(t("speed", 4, "neon"), t("coolant", 4, "neon|a"), t("oil", 4, "neon|a")) + row(3, "battery", "per100", "range", "intake"),
        upgrades = ATF_FOR_INTAKE,
    ),
    my(
        "trip", "Трасса", "Скорость, расход и запас хода крупно, внизу итоги поездки и топливо в баке", G_DRIVE,
        listOf(t("speed", 4, "minimal"), t("per100", 4, "minimal"), t("range", 4, "minimal")) + row(3, "tripAvg", "tripFuel", "fuelLeft", "coolant"),
    ),
    my(
        "cluster", "Приборка", "Тахометр и спидометр по краям, температуры и передача между ними, внизу расход и запас хода", G_DRIVE,
        free = true,
        tiles = listOf(
            f("rpm", 0.01f, 0.02f, 0.31f, 0.66f, "sti"),
            f("coolant", 0.335f, 0.03f, 0.16f, 0.32f, "sti"),
            f("oil", 0.505f, 0.03f, 0.16f, 0.32f, "sti"),
            f("gear", 0.335f, 0.37f, 0.33f, 0.29f),
            f("speed", 0.68f, 0.02f, 0.31f, 0.66f, "sti"),
            f("battery", 0.01f, 0.70f, 0.24f, 0.28f),
            f("per100", 0.255f, 0.70f, 0.24f, 0.28f),
            f("range", 0.50f, 0.70f, 0.24f, 0.28f),
            f("fuelLeft", 0.745f, 0.70f, 0.245f, 0.28f),
        ),
    ),
    my(
        "nav", "Навигация", "Приборка с картой и подсказками навигатора, под ней четыре главных числа", G_DRIVE,
        listOf(t("navcluster", 12)) + row(3, "coolant", "per100", "range", "battery"),
        small = listOf(t("navcluster", 12)),
    ),
    my(
        "warmup", "Прогрев и зима", "ОЖ и масло крупно, напряжение, впуск и подсказка, когда можно крутить мотор и глушить турбину", G_DRIVE,
        listOf(t("coolant", 6, "lcd_amber"), t("oil", 6, "lcd_amber")) + row(4, "battery", "intake", "turbo"),
        upgrades = ATF_FOR_INTAKE,
    ),
    my(
        "sport", "Спорт", "Спорт-дисплей и G-метр, под ними температуры, смесь и детонация", G_DRIVE,
        listOf(t("defi", 8), t("gmeter", 4)) + row(3, "coolant", "oil", "afr", "knock"),
        small = listOf(t("defi", 8), t("gmeter", 4)),
    ),
    my(
        "track", "Трек-день", "Масло и ОЖ с тревогой, наддув, перегрузки и пики, внизу смесь, детонация и напряжение", G_DRIVE,
        listOf(t("oil", 4, "sti|a"), t("coolant", 4, "sti|a"), t("boost", 4, "sti")) + row(6, "gmeter", "peaks") +
            row(3, "afr", "knock", "battery", "intake"),
        small = listOf(t("oil", 4, "sti|a"), t("coolant", 4, "sti|a"), t("boost", 4, "sti")) + row(6, "gmeter", "peaks"),
        swaps = mapOf("boost" to listOf("rpm")), upgrades = ATF_FOR_INTAKE,
    ),
    my(
        "turbo", "Турбо", "Наддув и обороты крупно, масло, смесь, откат и IAM, ниже ошибка наддува и форсунки", G_ENGINE,
        listOf(t("boost", 6, "pursuit"), t("rpm", 6, "pursuit")) + row(3, "oil", "afr", "fbkc", "iamV") +
            row(3, "coolant", "intake", "boostErr", "injDuty"),
        small = listOf(t("boost", 6, "pursuit"), t("rpm", 6, "pursuit")) + row(3, "oil", "afr", "fbkc", "iamV"),
        needs = setOf(Need.TURBO), reserve = listOf("timing", "knock", "load"),
    ),
    my(
        "sensorbox", "SensorBox", "Давление и температура масла, температура выхлопа и смесь с ваших датчиков", G_ENGINE,
        listOf(t("@oilP", 4, "sti|a"), t("@oil", 4, "sti|a"), t("@egt", 4, "sti")) + row(3, "boost", "@afr", "coolant", "battery"),
        needs = setOf(Need.BOX), reserve = listOf("oil", "intake", "afr"),
    ),
    my(
        "offroad", "Бездорожье и прицеп", "ATF, ОЖ и масло с тревогой, крен и уклон, муфта полного привода и блокировка", G_OFFROAD,
        listOf(t("atf", 4, "sti|a"), t("coolant", 4, "sti|a"), t("oil", 4, "sti")) + listOf(t("incline", 6), t("transfer", 3), t("lockupPct", 3)),
        needs = setOf(Need.TCU), swaps = mapOf("transfer" to listOf("battery"), "lockupPct" to listOf("per100")),
    ),
    my(
        "night", "Ночной LCD", "Три прибора с цифрами в стиле ЖК-табло, мягкие для глаз ночью", G_NIGHT,
        listOf(t("rpm", 4, "lcd_night"), t("speed", 4, "lcd_night"), t("coolant", 4, "lcd_night")) + row(3, "battery", "per100", "range", "oil"),
    ),
    my(
        "minimal", "Минимум", "Только скорость, температура и напряжение: три крупных прибора без лишнего", G_NIGHT,
        listOf(t("speed", 4, "minimal"), t("coolant", 4, "minimal"), t("battery", 4, "minimal")),
    ),
    my(
        "diag", "Диагностика", "На стоянке: шестнадцать чисел, температуры, смесь и коррекции, зажигание, нагрузка", G_DIAG,
        row(3, "coolant", "intake", "oil", "battery", "afr", "afcorr", "aflearn", "maf", "timing", "knock", "iamV", "load",
            "rpm", "throttle", "injDuty", "wgdc"),
        small = row(3, "coolant", "intake", "oil", "battery", "afr", "afcorr", "aflearn", "maf", "timing", "knock", "iamV", "load"),
        reserve = listOf("map", "pedal", "atm", "speed"),
    ),
    panel("p_tacho_blue", "Тахо-арка: синяя", "Арка оборотов с красным концом, скорость крупно, по краям ОЖ и топливо, внизу расход и пробег"),
    panel("p_tacho_orange", "Тахо-арка: оранжевая", "Та же раскладка, тёплая оранжевая арка"),
    panel("p_tacho_teal", "Тахо-арка: бирюзовая", "Та же раскладка, градиент и точка на конце арки"),
    panel("p_turbo", "Турбо-панель", "Наддув в барах и PSI с разрежением ниже нуля, сбоку расход, скорость, ОЖ, пробег (только с турбиной)", setOf(Need.TURBO)),
    panel("p_tilt", "Крен и тангаж", "Машина сбоку и сзади наклоняется по датчику, шкалы ±40° (нужен обнулённый акселерометр)", setOf(Need.MOTION)),
    panel("p_gradar", "G-радар", "Сектор по направлению перегрузки, в центре G и скорость (нужен обнулённый акселерометр)", setOf(Need.MOTION)),
    panel("gforce", "G-сила", "Точка по перегрузке и максимумы по четырём сторонам (нужен обнулённый акселерометр)", setOf(Need.MOTION)),
    panel("p_web", "Паутина", "Скорость в центре, вокруг шесть значений: что есть из ОЖ, напряжения, оборотов, наддува, времени, высоты"),
    panel("p_accel", "Разгон: график", "Кривая последнего разгона 0–100: время и путь"),
    panel("p_brake", "Торможение: график", "Кривая последнего торможения 100–0: время и путь"),
)

private fun home(
    id: String, name: String, about: String, group: String, cluster: Int?, tiles: List<TileCfg>, free: Boolean = false,
    small: List<TileCfg>? = null, needs: Set<Need> = emptySet(), swaps: Map<String, List<String>> = emptyMap(),
    upgrades: List<Upgrade> = emptyList(), extras: Map<String, String> = emptyMap(),
) = BoardPreset(
    "homeTiles", name, about, free, tiles, (if (cluster != null) mapOf("clusterSize" to "$cluster") else emptyMap()) + extras,
    small, emptyList(), needs, "homeTiles/$id", group, swaps, upgrades,
)

private val HOME = listOf(
    home(
        "simple", "Просто", "Большой блок с машиной и приборами, пять главных чисел", G_DRIVE, 2,
        row(1, "coolant", "battery", "per100", "range", "boost"),
        // as the «Просто» chip always did: without a turbine the ATF on an automatic, else the intake air
        swaps = mapOf("boost" to listOf("atf", "intake")),
    ),
    home(
        "everyday", "Каждый день", "Машина и приборы, температуры, напряжение, расход, запас хода и смесь", G_DRIVE, 1,
        row(1, "coolant", "oil", "battery", "per100", "range", "intake", "afr", "timing", "fuel", "gps"),
        small = row(1, "coolant", "oil", "battery", "per100", "range", "intake", "afr", "timing"),
        upgrades = ATF_FOR_INTAKE,
    ),
    home(
        "trip", "Трасса", "Большие приборы, расход, средний, запас хода и топливо в баке", G_DRIVE, 2,
        row(1, "per100", "tripAvg", "range", "fuelLeft", "coolant"),
    ),
    home(
        "warmup", "Прогрев", "ОЖ, масло, напряжение, впуск и подсказка про прогрев и охлаждение турбины", G_DRIVE, 1,
        row(1, "coolant", "oil", "battery", "intake", "turbo"),
        upgrades = ATF_FOR_INTAKE,
    ),
    home(
        "nav", "Навигация", "Карта с маршрутом на две трети, спидометр и четыре числа", G_DRIVE, null,
        free = true,
        tiles = listOf(
            f("navmap", 0f, 0f, 0.655f, 1f),
            f("speedo", 0.67f, 0f, 0.33f, 0.48f),
            f("coolant", 0.67f, 0.50f, 0.16f, 0.24f),
            f("per100", 0.835f, 0.50f, 0.165f, 0.24f),
            f("range", 0.67f, 0.76f, 0.16f, 0.24f),
            f("battery", 0.835f, 0.76f, 0.165f, 0.24f),
        ),
    ),
    home(
        "turbo", "Турбо-контроль", "Наддув, смесь, детонация, IAM и масло, ниже цель наддува и форсунки", G_ENGINE, 1,
        row(1, "boost", "afr", "knock", "iamV", "oil", "coolant", "intake", "targetBoost", "injDuty", "battery"),
        small = row(1, "boost", "afr", "knock", "iamV", "oil", "coolant", "intake", "battery"),
        needs = setOf(Need.TURBO),
    ),
    home(
        "offroad", "Бездорожье", "ATF, ОЖ, масло, крен и уклон, муфта полного привода и блокировка", G_OFFROAD, 1,
        listOf(t("atf"), t("coolant"), t("oil"), t("incline", 2)) + row(1, "transfer", "lockupPct", "slip", "battery", "gps"),
        small = row(1, "atf", "coolant", "oil", "battery") + listOf(t("incline", 2), t("transfer"), t("lockupPct")),
        needs = setOf(Need.TCU),
        swaps = mapOf("transfer" to listOf("per100"), "lockupPct" to listOf("range"), "slip" to listOf("fuel")),
    ),
    home(
        "night", "Ночь", "Мягкие ЖК-приборы и три числа", G_NIGHT, 2,
        row(1, "coolant", "battery", "range"),
        extras = mapOf("tachStyle" to "lcd_night", "speedoStyle" to "lcd_night"),
    ),
    home(
        "diag", "Диагностика", "На стоянке: пятнадцать чисел без машины, температуры, смесь, коррекции, зажигание, давление", G_DIAG, 0,
        row(1, "coolant", "intake", "battery", "alt", "throttle", "maf", "afr", "afcorr", "aflearn", "inj", "timing", "knock", "avcs", "map", "atm"),
    ),
)

private fun drive(
    id: String, name: String, about: String, group: String, tiles: List<TileCfg>, small: List<TileCfg>? = null,
    needs: Set<Need> = emptySet(), swaps: Map<String, List<String>> = emptyMap(), reserve: List<String> = emptyList(),
) = BoardPreset("driveTiles", name, about, false, tiles, emptyMap(), small, reserve, needs, "driveTiles/$id", group, swaps)

private val DRIVE = listOf(
    drive(
        "response", "Отклик", "Педаль и дроссель, нагрузка и детонация, графики педали и зажигания, внизу AVCS, MAF и форсунки", G_DRIVE,
        row(3, "pedal", "throttle", "load", "knock") + row(6, "chartPedal", "chartTiming") + row(4, "avcs", "maf", "injDuty"),
    ),
    drive(
        "track", "Трек", "Спорт-дисплей и G-метр, графики оборотов и зажигания", G_DRIVE,
        listOf(t("defi", 8), t("gmeter", 4)) + row(6, "chartRpm", "chartTiming"),
    ),
    drive(
        "runs", "Замеры", "Разгон, пики поездки и перегрузки, графики последнего разгона и торможения", G_DRIVE,
        row(4, "run", "peaks", "gmeter") + row(6, "p_accel", "p_brake"),
    ),
    drive(
        "accel", "Разгон (турбо)", "Наддув, дроссель и детонация, графики наддува и смеси, форсунки и wastegate", G_ENGINE,
        row(4, "boost", "throttle", "knock") + row(6, "chartBoost", "chartAfr") + row(3, "injDuty", "wgdc", "maf", "pressure"),
        small = row(3, "throttle", "boost", "load", "knock") + row(6, "chartBoost", "chartAfr") + row(4, "injDuty", "maf", "avcs"),
        needs = setOf(Need.TURBO),
    ),
    drive(
        "target", "Наддув: цель", "Цель и факт наддува, IAM, график наддува, wastegate и форсунки", G_ENGINE,
        row(6, "btarget", "iam") + listOf(t("chartBoost", 12)) + row(3, "wgdc", "injDuty", "maf", "pressure"),
        needs = setOf(Need.TURBO, Need.SSM),
    ),
    drive(
        "knock", "Детонация", "Откат и IAM крупно, график зажигания во всю ширину, внизу нагрузка, наддув и дроссель", G_ENGINE,
        row(4, "knock", "iam", "rpm") + listOf(t("chartTiming", 12)) + row(4, "load", "boost", "throttle"),
        needs = setOf(Need.SSM), swaps = mapOf("boost" to listOf("pedal", "maf")),
    ),
    drive(
        "minimal", "Минимум", "Две большие стрелки и график смеси", G_NIGHT,
        listOf(t("boost", 6, "minimal"), t("rpm", 6, "minimal"), t("chartAfr", 12)),
        swaps = mapOf("boost" to listOf("load", "throttle")),
    ),
    drive(
        "mixture", "Смесь и коррекции", "AFR и коррекции, MAF и зажигание на графиках, внизу MAF, нагрузка и форсунки", G_DIAG,
        row(6, "chartAfr", "chartTrims", "chartMaf", "chartTiming") + row(4, "maf", "load", "injDuty"),
        small = row(6, "chartAfr", "chartTrims") + row(4, "maf", "load", "injDuty"),
    ),
)

private fun tcu(id: String, name: String, about: String, group: String, tiles: List<TileCfg>, small: List<TileCfg>? = null, factory: Boolean = false) =
    BoardPreset("tcuTiles", name, about, false, tiles, small = small, id = "tcuTiles/$id", group = group, factory = factory)

private val TCU = listOf(
    tcu("factory", "Как было", "Селектор, ATF и гидротрансформатор, блокировка, давление, муфта, колёса и два графика", G_DRIVE,
        TcuBoard.defaults.filter { it.on }, factory = true),
    tcu("atf", "Температура ATF", "ATF крупно со стрелкой и тревогой, селектор, график температуры: для прицепа и пробок", G_OFFROAD,
        listOf(t("atf", 6, "sti|a"), t("range", 6), t("chartAtf", 12)) + row(4, "lockup", "line", "awd")),
    tcu("awd", "Полный привод", "Муфта, скорость колёс и ATF, графики колёс и блокировки: проверка пробуксовки", G_OFFROAD,
        row(4, "awd", "wheels", "atf") + row(6, "chartWheels", "chartLock")),
    tcu("minimal", "Минимум", "Селектор, ATF и гидротрансформатор, под ними график температуры", G_NIGHT,
        row(4, "range", "atf", "converter") + listOf(t("chartAtf", 12))),
    tcu("lockup", "Блокировка ГДТ", "Гидротрансформатор и блокировка, графики блокировки и оборотов турбины", G_DIAG,
        listOf(t("converter", 6), t("lockup", 6, "minimal")) + row(6, "chartLock", "chartTurbine") + row(4, "range", "atf", "line")),
    tcu("diag", "Диагностика АКПП", "На стоянке: селектор, ATF, гидротрансформатор, давление, муфта, колёса и графики турбины и колёс", G_DIAG,
        listOf(t("range", 3), t("atf", 3), t("converter", 6)) + row(3, "lockup", "line", "awd", "wheels") + row(6, "chartTurbine", "chartWheels"),
        small = listOf(t("range", 3), t("atf", 3), t("converter", 6)) + row(3, "lockup", "line", "awd", "wheels")),
)

private fun fuel(id: String, name: String, about: String, tiles: List<TileCfg>, factory: Boolean = false) =
    BoardPreset("fuelTiles", name, about, false, tiles, id = "fuelTiles/$id", factory = factory)

private val FUEL = listOf(
    fuel("factory", "Как было", "Текущий расход, бак, график, поездка, режимы и последние поездки", FuelBoard.defaults.filter { it.on }, factory = true),
    fuel("compact", "Компактный", "Текущий расход и бак крупно, под ними поездка во всю ширину", listOf(t("inst", 5), t("tank", 7), t("trip", 12))),
    fuel("trip", "Поездка", "Текущий расход и поездка, ниже график и расход по режимам", listOf(t("inst", 4), t("trip", 8)) + row(6, "chart", "modes")),
    fuel("eco", "Экономия", "Текущий расход и режимы крупно, ниже график и бак", row(6, "inst", "modes") + listOf(t("chart", 8), t("tank", 4))),
    fuel("history", "История", "Бак и последние поездки, график расхода во всю ширину", row(6, "tank", "recent") + listOf(t("chart", 12))),
)

object BuiltInPresets {
    val all: List<BoardPreset> by lazy { HOME + MY + DRIVE + TCU + FUEL }

    fun of(board: String): List<BoardPreset> = all.filter { it.board == board }
}
