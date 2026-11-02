package kg.forester.sg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*
import kg.forester.sg.data.Derived
import kg.forester.sg.data.DistanceUnits
import kg.forester.sg.service.RulesHost
import kg.forester.sg.data.Scales
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.data.lhNow
import kg.forester.sg.data.TripState
import kg.forester.sg.service.GuardState
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.TcuSnapshot

/** Everything a value is computed from, one frame's worth. */
class MetricCtx(
    val e: EcuSnapshot,
    val t: TcuSnapshot?,
    val trip: TripState,
    val gps: GpsFix?,
    val guard: GuardState,
    val rr: Map<String, Double>,
    /** SensorBox values by input ("A0", "TC0"). */
    val box: Map<String, kg.forester.sg.data.BoxReading> = emptyMap(),
    val wideband: kg.forester.sg.data.WidebandState = kg.forester.sg.data.WidebandState(),
    val elapsedMs: Long = 0,
)

/**
 * One value the app can show anywhere: a home card, the bottom bar. [id] is shared with the home cards and the
 * trip min/max, so a value keeps its range wherever it is shown. Scale [min]…[max] is for bars and needles.
 */
class Indicator(
    val id: String,
    title: String,
    short: String,
    val icon: ImageVector,
    unit: String,
    val format: String,
    val min: Float,
    max: Float,
    val group: String,
    warnAbove: Float? = null,
    warnBelow: Float? = null,
    /** Too far from zero either way (the A/F corrections). */
    val warnAbs: Float? = null,
    /** Scale top that can change at run time (boost), instead of the fixed max. */
    private val maxNow: (() -> Float)? = null,
    /** A mark after the title while the value is only worked out, not measured (the oil without a sensor: " ≈"). */
    private val markNow: (() -> String)? = null,
    /** Warning level that can change at run time (this engine's redline), instead of the fixed one; null = the fixed one. */
    private val warnNow: (() -> Float?)? = null,
    /** Speed, distance or consumption: [source] gives it in metric, everything here says it in the driver's unit. */
    val kind: DistanceUnits.Kind? = null,
    private val source: (MetricCtx) -> Float?,
) {
    val read: (MetricCtx) -> Float? = if (kind == null) source else { c -> source(c)?.let { DistanceUnits.shown(kind, it) } }
    val unit: String get() = kind?.let(DistanceUnits::unit) ?: fixedUnit
    val warnBelow: Float? get() = fixedBelow?.let { b -> kind?.let { DistanceUnits.shown(it, b) } ?: b }
    private val fixedUnit = unit
    private val fixedBelow = warnBelow
    private val fixedTitle = title
    private val fixedShort = short
    val title: String get() = (if (kind != null) DistanceUnits.label(fixedTitle) else fixedTitle) + (markNow?.invoke() ?: "")
    val short: String get() = fixedShort + (markNow?.invoke() ?: "")
    private val fixedMax = max
    val max: Float get() = maxNow?.invoke() ?: kind?.let { DistanceUnits.top(it, fixedMax) } ?: fixedMax
    private val fixedWarn = warnAbove
    val warnAbove: Float? get() = warnNow?.invoke() ?: fixedWarn
    fun text(v: Float?) = v?.takeIf { !it.isNaN() }?.let { format.format(it) } ?: "—"
    fun warn(v: Float?) = WarnRule(warnAbove, warnBelow, warnAbs).hits(v)
    val warnRule: WarnRule get() = WarnRule(warnAbove, warnBelow, warnAbs)
}

/**
 * The catalogue of values: everything on the «Лог → Текущие значения» list, the transmission, trip and GPS, plus
 * any parameter ticked on «Датчики» (id "rr:<RomRaider id>") and the user's own ECU addresses.
 */
object Metrics {
    val builtIn: List<Indicator> = listOf(
        // engine
        Indicator("rpm", "Обороты", "Обороты", Lucide.Gauge, "об/мин", "%.0f", 0f, 7000f, "Двигатель", warnAbove = 6000f,
            maxNow = { Repo.engineProfile.value.rpmTop }, warnNow = { Repo.engineProfile.value.rpmRedFrom }) { it.e.rpm?.toFloat() },
        Indicator("speed", "Скорость", "Скорость", Lucide.Car, "км/ч", "%.0f", 0f, 200f, "Двигатель", kind = DistanceUnits.Kind.SPEED) { it.e.speedKmh?.toFloat() },
        Indicator("coolant", "Темп. ОЖ", "Темп. ОЖ", Lucide.Thermometer, "°C", "%.0f", 40f, 110f, "Двигатель", warnAbove = Warn.of("coolant").above) { it.e.coolantC?.toFloat() },
        Indicator("intake", "Темп. впуска", "Впуск", Lucide.ThermometerSnowflake, "°C", "%.0f", -20f, 70f, "Двигатель", warnAbove = Warn.of("intake").above) { it.e.intakeC?.toFloat() },
        // without an oil sensor (the EJ has none) the number is the guard's estimate from the coolant and load: "≈" says so
        Indicator("oil", "Темп. масла", "Масло", Lucide.Droplet, "°C", "%.0f", 0f, 140f, "Двигатель", warnAbove = 125f,
            markNow = { Repo.guard.value.let { g -> if (g.oilC != null && g.oilEstimated) " ≈" else "" } }) { it.guard.oilC },
        Indicator("battery", "Напряжение", "Напряжение", Lucide.BatteryCharging, "В", "%.1f", 10f, 15f, "Двигатель", warnBelow = Warn.of("battery").below) { it.e.batteryV },
        Indicator("throttle", "Дроссель", "Дроссель", Lucide.Disc, "%", "%.0f", 0f, 100f, "Двигатель") { it.e.throttlePct },
        Indicator("pedal", "Педаль газа", "Педаль", Lucide.ChevronsUp, "%", "%.0f", 0f, 100f, "Двигатель") { it.e.pedalPct },
        Indicator("load", "Нагрузка", "Нагрузка", Lucide.Weight, "г/об", "%.2f", 0f, 2.5f, "Двигатель", maxNow = { Scales.load.top.value }) { it.e.loadGRev },
        Indicator("avcs", "AVCS", "AVCS", Lucide.Timer, "°", "%.0f", -10f, 50f, "Двигатель") { it.e.avcsDeg },
        Indicator("alt", "Генератор", "Генератор", Lucide.Activity, "%", "%.0f", 0f, 100f, "Двигатель") { it.e.altDutyPct },
        // fuel
        Indicator("maf", "MAF", "MAF", Lucide.Wind, "г/с", "%.1f", 0f, 150f, "Топливо", maxNow = { Scales.maf.top.value }) { it.e.mafGs },
        Indicator("inj", "Впрыск", "Впрыск", Lucide.Syringe, "мс", "%.2f", 0f, 20f, "Топливо") { it.e.injPwMs },
        Indicator("injDuty", "Загрузка форсунок", "Форсунки", Lucide.Percent, "%", "%.0f", 0f, 100f, "Топливо", warnAbove = 85f) { it.e.injDutyPct },
        Indicator("afr", "AFR", "AFR", Lucide.Flame, "", "%.1f", 10f, 20f, "Топливо") { it.e.afr },
        Indicator("wideband_afr", "AFR ШДК (USB)", "AFR ШДК", Lucide.Flame, "", "%.2f", 7f, 23f, "ШДК USB") { it.wideband.afr(it.elapsedMs) },
        Indicator("wideband_lambda", "λ ШДК (USB)", "λ ШДК", Lucide.Flame, "λ", "%.3f", 0.5f, 1.6f, "ШДК USB") { it.wideband.lambda(it.elapsedMs) },
        // on a turbo car the IAT is read before the intercooler, so the number is relative (compare runs, not engines)
        Indicator("ve", "Наполнение VE (расчётное)", "VE", Lucide.Wind, "%", "%.0f", 0f, 130f, "Топливо") { c ->
            Derived.vePct(c.e.mafGs, c.e.rpm, c.e.intakeC, c.e.mapKpa, RulesHost.displacementNow())
        },
        Indicator("afcorr", "Коррекция A/F", "Корр. A/F", Lucide.SlidersHorizontal, "%", "%+.1f", -25f, 25f, "Топливо", warnAbs = Warn.of("afcorr").abs) { it.e.afCorrPct },
        Indicator("aflearn", "Обучение A/F", "Обуч. A/F", Lucide.Brain, "%", "%+.1f", -25f, 25f, "Топливо", warnAbs = Warn.of("aflearn").abs) { it.e.afLearnPct },
        Indicator("fuel", "Расход, л/ч", "Расход", Lucide.Fuel, "л/ч", "%.1f", 0f, 40f, "Топливо") { it.trip.lhNow?.toFloat() },
        Indicator("per100", "Расход, л/100", "Расход", Lucide.Fuel, "л/100", "%.1f", 0f, 30f, "Топливо", kind = DistanceUnits.Kind.CONSUMPTION) { it.trip.instPer100?.toFloat() },
        Indicator("tripAvg", "Средний расход поездки", "Ср. расход", Lucide.ChartLine, "л/100", "%.1f", 0f, 30f, "Топливо", kind = DistanceUnits.Kind.CONSUMPTION) { c ->
            c.trip.trip?.takeIf { it.distanceKm > 1.0 }?.let { (it.fuelL * 100 / it.distanceKm).toFloat() }
        },
        Indicator("tripFuel", "Топливо за поездку", "За поездку", Lucide.Droplets, "л", "%.1f", 0f, 20f, "Топливо") { it.trip.trip?.fuelL?.toFloat() },
        Indicator("fuelLeft", "Топливо в баке", "В баке", Lucide.Fuel, "л", "%.0f", 0f, 60f, "Топливо") { it.trip.fuelLeftL?.toFloat() },
        Indicator("range", "Запас хода", "Топливо", Lucide.Fuel, "км", "%.0f", 0f, 800f, "Топливо", warnBelow = Warn.of("range").below, kind = DistanceUnits.Kind.DISTANCE) { it.trip.rangeKm?.toFloat() },
        // ignition and knock
        Indicator("timing", "УОЗ", "УОЗ", Lucide.Zap, "°", "%.1f", -10f, 50f, "Зажигание") { it.e.timingDeg },
        Indicator("knock", "Откат по детонации", "Детонация", Lucide.AudioWaveform, "°", "%.1f", -10f, 2f, "Зажигание", warnBelow = Warn.of("knock").below) { it.e.knockDeg },
        Indicator("iamV", "IAM", "IAM", Lucide.Scale, "", "%.2f", 0f, 1f, "Зажигание", warnBelow = 0.75f) { it.guard.iam },
        Indicator("fbkc", "FBKC (откат)", "FBKC", Lucide.SquareActivity, "°", "%.1f", -8f, 0f, "Зажигание", warnBelow = Warn.of("fbkc").below) { it.guard.fbkc },
        Indicator("flkc", "FLKC (обучение)", "FLKC", Lucide.Waves, "°", "%.1f", -8f, 0f, "Зажигание", warnBelow = Warn.of("flkc").below) { it.guard.flkc },
        // boost
        Indicator("boost", "Наддув", "Наддув", Lucide.Fan, "бар", "%.2f", 0f, 1.2f, "Наддув", maxNow = { Scales.boost.top.value }) { it.e.boostBar },
        Indicator("gaugeKpa", "Наддув / разрежение", "Наддув", Lucide.CircleArrowUp, "кПа", "%.0f", -80f, 120f, "Наддув", maxNow = { Scales.boostKpa }) { it.e.gaugeKpa },
        Indicator("targetBoost", "Целевой наддув", "Цель", Lucide.Target, "кПа", "%.0f", 0f, 120f, "Наддув", maxNow = { Scales.boostKpa }) { it.guard.targetBoostKpa },
        // the target minus what the manifold has: positive = short of it (after the spool ±10 kPa is normal, Cobb)
        Indicator("boostErr", "Ошибка наддува (цель − факт)", "Ошибка надд.", Lucide.Crosshair, "кПа", "%+.0f", -40f, 40f, "Наддув", warnAbove = 15f, warnBelow = -20f) { c ->
            c.guard.targetBoostKpa?.let { t -> c.e.gaugeKpa?.takeIf { it > 0f }?.let { t - it } }
        },
        Indicator("map", "Абс. давление", "Абс. давл.", Lucide.Gauge, "кПа", "%.0f", 20f, 220f, "Наддув", maxNow = { 100f + Scales.boostKpa }) { it.e.mapKpa },
        Indicator("atm", "Атм. давление", "Атм.", Lucide.Cloudy, "кПа", "%.0f", 70f, 105f, "Наддув") { it.e.atmKpa },
        Indicator("wgdc", "Wastegate", "Wastegate", Lucide.Tornado, "%", "%.0f", 0f, 100f, "Наддув") { it.e.wastegatePct },
        // transmission
        Indicator("atf", "Темп. ATF", "ATF", Lucide.ThermometerSun, "°C", "%.0f", 0f, 130f, "АКПП", warnAbove = Warn.of("atf").above) { it.t?.atfC?.toFloat() },
        Indicator("gear", "Передача", "Передача", Lucide.Cog, "", "%.0f", 0f, 4f, "АКПП") { it.t?.gear?.toFloat() },
        Indicator("lockupPct", "Блокировка ГДТ", "Lock-up", Lucide.Lock, "%", "%.0f", 0f, 100f, "АКПП") { it.t?.lockUpPct },
        Indicator("slip", "Проскальзывание ГДТ", "Проскальз.", Lucide.Percent, "%", "%.0f", 0f, 100f, "АКПП") { it.t?.slipPct },
        Indicator("transfer", "Муфта полного привода", "AWD", Lucide.Waypoints, "%", "%.0f", 0f, 100f, "АКПП") { it.t?.transferPct },
        // GPS
        Indicator("gps", "Скорость GPS", "GPS", Lucide.Satellite, "км/ч", "%.0f", 0f, 200f, "GPS", kind = DistanceUnits.Kind.SPEED) { it.gps?.speedKmh },
        Indicator("altitude", "Высота", "Высота", Lucide.Mountain, "м", "%.0f", 0f, 3000f, "GPS") { c ->
            c.gps?.altitudeM?.toFloat() ?: c.e.atmKpa?.let { (44330.0 * (1 - Math.pow(it / 101.325, 0.1903))).toFloat() }
        },
    )

    /**
     * Values worked out from the extra sensors (SensorBox inputs or CAN channels with a role, see [Derived]): offered
     * only once the sensors they need are set up, still found by id for a card that already shows one.
     */
    private val derived: List<Pair<Indicator, List<String>>> = listOf(
        // the fuel pressure regulator holds the rail this much above the manifold: it should not change with boost
        Indicator("fuelDp", "Перепад давления топлива", "Перепад топл.", Lucide.Fuel, "бар", "%.2f", 0f, 5f, "Доп датчики") { c ->
            Derived.fuelDiffBar(SensorBox.roleBar("fuelP"), c.e.gaugeKpa)
        } to listOf("fuelP"),
        Indicator("icDrop", "Интеркулер: перепад", "Интеркулер", Lucide.ThermometerSnowflake, "°C", "%.0f", 0f, 60f, "Доп датчики") {
            Derived.intercoolerDropC(SensorBox.role("iatIn"), SensorBox.role("iatOut"))
        } to listOf("iatIn", "iatOut"),
        // the air into the turbo: the stock ECU's intake sensor sits in the MAF, before it (a Link's is after the intercooler)
        Indicator("icEff", "КПД интеркулера", "КПД интерк.", Lucide.Percent, "%", "%.0f", 0f, 100f, "Доп датчики", warnBelow = 50f) { c ->
            Derived.intercoolerEfficiencyPct(SensorBox.role("iatIn"), SensorBox.role("iatOut"),
                c.e.intakeC?.toFloat()?.takeIf { !kg.forester.sg.data.CanEcu.active })
        } to listOf("iatIn", "iatOut"),
        Indicator("exhRatio", "Выхлоп к наддуву", "Выхлоп/наддув", Lucide.Tornado, "", "%.2f", 0f, 3f, "Доп датчики", warnAbove = 2.2f) { c ->
            Derived.exhaustRatio(SensorBox.roleBar("emap"), c.e.gaugeKpa)
        } to listOf("emap"),
        Indicator("exhOver", "Выхлоп выше наддува", "Выхлоп − надд.", Lucide.Gauge, "бар", "%.2f", -0.5f, 2f, "Доп датчики") { c ->
            Derived.exhaustOverBoostBar(SensorBox.roleBar("emap"), c.e.gaugeKpa)
        } to listOf("emap"),
    )

    private val byId = (builtIn + derived.map { it.first }).associateBy { it.id }

    /** A box pressure in bar, whatever unit its sensor was set up in. */
    fun fuelBar(v: Float, unit: String) = Derived.bar(v, unit)

    /** An icon for [p] by what it measures (its name and units): the «Датчики» list and its cards. */
    fun iconOf(p: RrParam): androidx.compose.ui.graphics.vector.ImageVector {
        val n = (p.name + " " + p.nameEn).lowercase()
        val u = p.unitsRu
        return when {
            p.kind == RrParam.Kind.SWITCH -> Lucide.ToggleRight
            "детонац" in n || "knock" in n -> Lucide.AudioWaveform
            "ошибк" in n || "fault" in n || "error" in n -> Lucide.TriangleAlert
            "передач" in n || "gear" in n || p.target == 2 -> Lucide.Cog
            "вал" in n || "cam" in n || "avcs" in n -> Lucide.Timer
            "колес" in n || "wheel" in n || "скорост" in n -> Lucide.Car
            "темп" in n || u == "°C" -> Lucide.Thermometer
            "лямбд" in n || "lambda" in n || u == "λ" || "afr" in n || "смес" in n -> Lucide.Flame
            "форсун" in n || "inject" in n || "впрыск" in n || u == "мс" -> Lucide.Syringe
            "давлен" in n || "наддув" in n || u == "бар" || u.startsWith("кПа") || u == "МПа" -> Lucide.Gauge
            u == "В" -> Lucide.BatteryCharging
            u == "г/с" -> Lucide.Wind
            "отсечк" in n || "срез" in n || "cut" in n -> Lucide.Scissors
            u == "°" -> Lucide.Zap
            else -> Lucide.ListChecks
        }
    }

    /** A ticked «Датчики» parameter (or the user's own address) as a metric; its scale comes from what it has shown. */
    fun ofParam(p: RrParam): Indicator {
        if (p.id in kg.forester.sg.ssm.RomRaider.ECU_EGT) return Indicator(
            "rr:${p.id}", "Температура выхлопа", "Выхлоп", Lucide.ThermometerSun, "°C", "%.0f", 0f, 1000f, "Двигатель", warnAbove = EGT_WARN_C,
        ) { it.rr[p.id]?.toFloat() }
        val dec = p.format.substringAfter('.', "").length.coerceAtMost(3)
        return Indicator(
            "rr:${p.id}", p.name, p.name, iconOf(p), p.unitsRu,
            "%.${dec}f", 0f, 100f, if (p.target == 2) "АКПП (датчики)" else "Датчики", kind = DistanceUnits.kindOf(p.unitsRu),
        ) { it.rr[p.id]?.toFloat() }
    }

    /** An input of the SensorBox as a metric ("box_A0": no colon, the layouts keep their fields apart with colons), named and scaled the way the driver set it up. */
    fun ofBox(s: kg.forester.sg.data.BoxSlot): Indicator {
        val p = s.p
        val icon = when (s.role) {
            "oil" -> Lucide.Droplet; "oilP" -> Lucide.Gauge; "afr" -> Lucide.Flame; "egt" -> Lucide.ThermometerSun
            else -> Lucide.Cpu
        }
        return Indicator(
            "box_${s.input}", s.name, s.name.take(14), icon, s.unit, "%.${p.decimals}f", p.min, p.max, "Доп датчики",
            warnAbove = s.warnAbove, warnBelow = s.warnBelow,
        ) { it.box[s.input]?.value }
    }

    /**
     * An own CAN channel as a metric ("can_C1", no colon as for the box), the way a SensorBox input is one: there
     * with or without a sports ECU, its value in the ticked parameters' values (CanChannels.withValues).
     */
    fun ofCan(c: kg.forester.sg.data.CanChannel): Indicator {
        val icon = when (c.role) {
            "oil" -> Lucide.Droplet; "oilP", "fuelP", "emap" -> Lucide.Gauge; "afr" -> Lucide.Flame; "egt" -> Lucide.ThermometerSun
            "iatIn", "iatOut" -> Lucide.ThermometerSnowflake
            else -> Lucide.Cpu
        }
        val name = c.name.ifBlank { c.id }
        return Indicator("can_${c.id}", name, name.take(14), icon, c.units, "%.${c.decimals}f", 0f, 100f, "Доп датчики") { it.rr[c.id]?.toFloat() }
    }

    /** The values worked out from the extra sensors whose sensors are set up now (see [derived]). */
    fun derivedNow(): List<Indicator> = derived.filter { (_, roles) -> roles.all { SensorBox.hasRole(it) } }.map { it.first }

    /** Every value worked out from the extra sensors, set up or not: the layouts' places for them. */
    val derivedIds: List<String> get() = derived.map { it.first.id }

    /** [id] as a metric: built in, "rr:<id>" for a parameter of this car's units (null while not connected), "box_<input>" ("box:" in beta18 tests), "can_<channel>". */
    fun of(id: String, avail: List<RrParam>): Indicator? =
        byId[id] ?: if (id.startsWith("rr:")) avail.firstOrNull { it.id == id.removePrefix("rr:") }?.let(::ofParam)
        else if (id.startsWith("box_") || id.startsWith("box:")) kg.forester.sg.data.SensorBox.slot(id.drop(4))?.let(::ofBox)
        else if (id.startsWith("can_")) kg.forester.sg.data.CanChannels.list().firstOrNull { it.id == id.drop(4) }?.let(::ofCan) else null

    /** As the SensorBox's EGT warns by default. */
    private const val EGT_WARN_C = 900f

    /** Built-in ones plus the parameters ticked on «Датчики», the SensorBox inputs and the own CAN channels. */
    fun all(avail: List<RrParam>, watch: Set<String>): List<Indicator> {
        val can = kg.forester.sg.data.CanChannels.list()
        val canIds = can.map { it.id }.toSet()
        return builtIn + derivedNow() +
            avail.filter { (it.id in watch || it.id in kg.forester.sg.ssm.RomRaider.ECU_EGT || it.id.startsWith(kg.forester.sg.data.CanEcu.PREFIX)) && it.id !in canIds }.map(::ofParam) +
            kg.forester.sg.data.SensorBox.slots.value.map(::ofBox) + can.map(::ofCan)
    }
}

/**
 * The latest values, as a State: don't read it in the composition but through [metricSlot] (or in a [rememberDigits]),
 * so that a new reading reaches only what shows a value that changed, not the header, the bars and the cards around it.
 */
@Composable
fun rememberMetricCtx(): State<MetricCtx> {
    val e = Repo.live.collectAsState()
    val t = Repo.tcu.collectAsState()
    val trip = Repo.trip.collectAsState()
    val gps = Repo.gps.collectAsState()
    val guard = Repo.guard.collectAsState()
    val rr = Repo.rrValues.collectAsState()
    val box = kg.forester.sg.data.SensorBox.readings.collectAsState()
    val wideband = kg.forester.sg.data.Wideband.state.collectAsState()
    return remember {
        derivedStateOf {
            MetricCtx(e.value, t.value, trip.value, gps.value, guard.value, rr.value, box.value, wideband.value, android.os.SystemClock.elapsedRealtime())
        }
    }
}

/** What [read] makes of the latest values, changing only when its result does. */
@Composable
fun <R> metricSlot(ctx: State<MetricCtx>, key: Any?, read: (MetricCtx) -> R): State<R> =
    remember(ctx, key) { derivedStateOf(structuralEqualityPolicy()) { read(ctx.value) } }

/** Tops of the scales that grow with the car (see [Scales]): boost in bar, MAF in g/s, load in g/rev. */
val boostTop: Float
    @Composable get() = Scales.boost.top.collectAsState().value
val mafTop: Float
    @Composable get() = Scales.maf.top.collectAsState().value
val loadTop: Float
    @Composable get() = Scales.load.top.collectAsState().value
