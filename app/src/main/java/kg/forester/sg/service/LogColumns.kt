package kg.forester.sg.service

import android.os.SystemClock
import kg.forester.sg.data.Derived
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Wideband
import kg.forester.sg.data.WidebandLinkState
import kg.forester.sg.data.WidebandState
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import java.util.Locale

/** What one CSV line is made of; [elapsedMs] is when, on the clock the wideband's samples carry. */
class LogRow(val s: EcuSnapshot, val g: GpsFix?, val lh: Double, val t: TcuSnapshot?, val guard: GuardState,
             val wideband: WidebandState = Wideband.state.value, val elapsedMs: Long = SystemClock.elapsedRealtime())

/** One column of the CSV log; [key] is the header (kept as before, so old scripts still read the files). */
class LogColumn(val key: String, val title: String, val group: String, val get: (LogRow) -> String)

/**
 * The CSV log's columns, each can be switched off on the «Лог» screen (e.g. the transmission ones, which only
 * matter when hunting a gearbox problem). New columns are on until switched off.
 */
object LogColumns {
    private fun f(x: Float?, d: Int = 2) = x?.let { "%.${d}f".format(Locale.US, it) } ?: ""
    private fun i(x: Int?) = x?.toString() ?: ""

    const val WIDEBAND = "ШДК USB"

    val all: List<LogColumn> = listOf(
        LogColumn("rpm", "Обороты", "Двигатель") { i(it.s.rpm) },
        LogColumn("speed_kmh", "Скорость", "Двигатель") { i(it.s.speedKmh) },
        LogColumn("coolant_c", "Темп. ОЖ", "Двигатель") { i(it.s.coolantC) },
        LogColumn("intake_c", "Темп. впуска", "Двигатель") { i(it.s.intakeC) },
        LogColumn("battery_v", "Напряжение", "Двигатель") { f(it.s.batteryV) },
        LogColumn("throttle_pct", "Дроссель", "Двигатель") { f(it.s.throttlePct) },
        LogColumn("pedal_pct", "Педаль", "Двигатель") { f(it.s.pedalPct) },
        LogColumn("load_grev", "Нагрузка, г/об", "Двигатель") { f(it.s.loadGRev, 3) },
        LogColumn("avcs_deg", "AVCS", "Двигатель") { f(it.s.avcsDeg) },
        LogColumn("alt_duty_pct", "Генератор", "Двигатель") { f(it.s.altDutyPct) },
        LogColumn("maf_gs", "MAF", "Топливо") { f(it.s.mafGs) },
        LogColumn("inj_pw_ms", "Впрыск, мс", "Топливо") { f(it.s.injPwMs) },
        LogColumn("inj_duty_pct", "Загрузка форсунок", "Топливо") { f(it.s.injDutyPct) },
        LogColumn("af_corr_pct", "Коррекция A/F", "Топливо") { f(it.s.afCorrPct) },
        LogColumn("af_learn_pct", "Обучение A/F", "Топливо") { f(it.s.afLearnPct) },
        LogColumn("afr", "AFR", "Топливо") { f(it.s.afr) },
        LogColumn("fuel_lh", "Расход, л/ч", "Топливо") { "%.3f".format(Locale.US, it.lh) },
        LogColumn("timing_deg", "УОЗ", "Зажигание и детонация") { f(it.s.timingDeg) },
        LogColumn("knock_deg", "Откат по детонации", "Зажигание и детонация") { f(it.s.knockDeg) },
        LogColumn("iam", "IAM", "Зажигание и детонация") { f(it.guard.iam, 3) },
        LogColumn("fbkc_deg", "FBKC (откат)", "Зажигание и детонация") { f(it.guard.fbkc) },
        LogColumn("flkc_deg", "FLKC (обучение)", "Зажигание и детонация") { f(it.guard.flkc) },
        LogColumn("atm_kpa", "Атм. давление", "Наддув") { f(it.s.atmKpa) },
        LogColumn("map_kpa", "Абс. давление", "Наддув") { f(it.s.mapKpa) },
        LogColumn("boost_kpa", "Наддув (отн.)", "Наддув") { f(it.s.gaugeKpa) },
        LogColumn("target_boost_kpa", "Целевой наддув", "Наддув") { f(it.guard.targetBoostKpa) },
        LogColumn("wgdc_pct", "Wastegate", "Наддув") { f(it.s.wastegatePct) },
        LogColumn("gps_speed_kmh", "Скорость GPS", "GPS") { f(it.g?.speedKmh) },
        LogColumn("gps_alt_m", "Высота GPS", "GPS") { it.g?.altitudeM?.let { a -> "%.0f".format(Locale.US, a) } ?: "" },
        LogColumn("gps_lat", "Широта", "GPS") { it.g?.lat?.let { a -> "%.6f".format(Locale.US, a) } ?: "" },
        LogColumn("gps_lon", "Долгота", "GPS") { it.g?.lon?.let { a -> "%.6f".format(Locale.US, a) } ?: "" },
        LogColumn("gear", "Передача", "АКПП") { i(it.t?.gear) },
        LogColumn("atf_c", "Темп. ATF", "АКПП") { i(it.t?.atfC) },
        LogColumn("turbine_rpm", "Турбинное колесо", "АКПП") { i(it.t?.turbineRpm) },
        LogColumn("lockup_pct", "Блокировка ГДТ", "АКПП") { f(it.t?.lockUpPct) },
        LogColumn("converter_slip_pct", "Проскальзывание ГДТ", "АКПП") { f(it.t?.slipPct) },
        LogColumn("lockup_state", "Состояние ГДТ", "АКПП") { it.t?.lock?.name?.lowercase() ?: "" },
        LogColumn("fwd_fuse", "Предохранитель FWD", "АКПП") { it.t?.fwdFuse?.let { b -> if (b) "1" else "0" } ?: "" },
        LogColumn("transfer_pct", "Муфта полного привода", "АКПП") { f(it.t?.transferPct) },
        LogColumn("front_wheel_kmh", "Скорость передних колёс", "АКПП") { i(it.t?.frontWheelKmh) },
        LogColumn("rear_wheel_kmh", "Скорость задних колёс", "АКПП") { i(it.t?.rearWheelKmh) },
        LogColumn("fuel_sensor_v", "Датчик топлива, В", "Уровень топлива") { f(it.s.fuelSensorV) },
        LogColumn("fuel_sensor_ohm", "Датчик топлива, Ом", "Уровень топлива") { f(it.s.fuelSensorOhm) },
        // added in 0.9-beta21: after all the old ones, so the columns of everybody's files stay where they were
        // the EJ has no oil sensor: then the number is the guard's estimate, and the next column says so
        LogColumn("oil_c", "Темп. масла", "Двигатель") { f(it.guard.oilC, 0) },
        LogColumn("oil_estimated", "Масло: оценка (1) или датчик (0)", "Двигатель") { r -> r.guard.oilC?.let { if (r.guard.oilEstimated) "1" else "0" } ?: "" },
        LogColumn("boost_error_kpa", "Ошибка наддува (цель − факт)", "Наддув") { r -> f(r.guard.targetBoostKpa?.let { t -> r.s.gaugeKpa?.let { t - it } }) },
        LogColumn("lat_g", "Перегрузка боковая", "Перегрузки") { f(Repo.motion.value.latG) },
        LogColumn("lon_g", "Перегрузка продольная", "Перегрузки") { f(Repo.motion.value.lonG) },
        LogColumn("ve_pct", "Наполнение VE (расчётное)", "Топливо") {
            f(Derived.vePct(it.s.mafGs, it.s.rpm, it.s.intakeC, it.s.mapKpa, RulesHost.displacementNow()), 1)
        },
        // P23 as the ECU gives it: a sum (KA x IAM + FBKC + FLKC), not the live pull of knock_deg
        LogColumn("kca_deg", "P23: поправка УОЗ по детонации (сумма)", "Зажигание и детонация") { f(it.s.kcaDeg) },
    ) + listOf(
        // the USB wideband (Innovate LC-2): after the columns above, and only while it is switched on (see [offered]);
        // later additions go before it. Readers (LogViewer, TrackMap) find columns by header name, not by position
        LogColumn("wideband_afr", "AFR ШДК USB", WIDEBAND) { f(it.wideband.afr(it.elapsedMs)) },
        LogColumn("wideband_lambda", "λ ШДК USB", WIDEBAND) { f(it.wideband.lambda(it.elapsedMs), 3) },
        LogColumn("wideband_status", "Статус ШДК USB", WIDEBAND) { it.wideband.status(it.elapsedMs) },
        LogColumn("wideband_age_ms", "Возраст данных ШДК, мс", WIDEBAND) { r -> r.wideband.sample?.let { (r.elapsedMs - it.elapsedMs).coerceAtLeast(0).toString() } ?: "" },
        LogColumn("wideband_demo", "ШДК: тестовые данные", WIDEBAND) { if (it.wideband.link == WidebandLinkState.DEMO) "1" else "0" },
    )

    /** Standard SSM addresses (RomRaider "P" parameters) that a regular column already logs, by column key. */
    private val ecuAddr = mapOf(
        0x08 to "coolant_c", 0x09 to "af_corr_pct", 0x0A to "af_learn_pct", 0x0D to "map_kpa", 0x0E to "rpm", 0x10 to "speed_kmh",
        0x11 to "timing_deg", 0x12 to "intake_c", 0x13 to "maf_gs", 0x15 to "throttle_pct", 0x1C to "battery_v", 0x20 to "inj_pw_ms",
        0x22 to "kca_deg", 0x23 to "atm_kpa", 0x24 to "boost_kpa", 0x29 to "pedal_pct", 0x2E to "fuel_sensor_v", 0x30 to "wgdc_pct",
        0x3A to "alt_duty_pct", 0x3C to "avcs_deg", 0x46 to "afr", 0x4A to "gear",
    )
    private val tcuAddr = mapOf(
        0x48 to "front_wheel_kmh", 0x49 to "atf_c", 0x4A to "gear", 0x4C to "lockup_pct", 0x4D to "transfer_pct", 0x4F to "turbine_rpm",
        0x51 to "rear_wheel_kmh", 0x56 to "atf_c",
    )
    /** The engine guard's own parameters (see EngineGuard.roleIds) and the column each one is logged in. */
    private val roleColumn = mapOf(
        "iam" to "iam", "fbkc" to "fbkc_deg", "flkc" to "flkc_deg", "target" to "target_boost_kpa",
        "mapAbs" to "map_kpa", "mapRel" to "map_kpa", "mapRelSea" to "map_kpa",
    )

    /**
     * The parameters read besides the regular ones that are worth a column of their own: not one a regular column
     * already has (the engine speed ticked on «Датчики», the IAM the guard reads for itself), so a log has no twins.
     */
    fun extras(polled: List<kg.forester.sg.ssm.RrParam>, roleIds: Map<String, String>): List<kg.forester.sg.ssm.RrParam> {
        val on = enabled().map { it.key }.toSet()
        val covered = roleIds.filter { (role, _) -> roleColumn[role] in on }.values.toSet()
        return polled.filter { p ->
            p.id !in covered && !(p.kind == kg.forester.sg.ssm.RrParam.Kind.STD && p.bit < 0 &&
                (if (p.target == 2) tcuAddr[p.addr] else ecuAddr[p.addr]) in on)
        }.distinctBy { it.id }
    }

    /** The engine guard's usual ids for its roles (EngineGuard.pick), for logs written before the twins were left out. */
    private val roleIdsKnown = mapOf(
        "iam" to listOf("P90", "E31", "E96", "E111", "E1"), "fbkc_deg" to listOf("E39", "E94", "E102", "E10"),
        "flkc_deg" to listOf("P91", "E41", "E95", "E101", "E12"), "target_boost_kpa" to listOf("E36", "E90", "E120", "E6"),
        "map_kpa" to listOf("E51", "E129", "E20", "E88", "E98", "E113", "E72", "E21", "E89"),
    )

    /** The regular column [p] repeats, if any: the log viewer hides such a twin in older logs. */
    fun twinOf(p: kg.forester.sg.ssm.RrParam): String? =
        (if (p.kind == kg.forester.sg.ssm.RrParam.Kind.STD && p.bit < 0) (if (p.target == 2) tcuAddr[p.addr] else ecuAddr[p.addr]) else null)
            ?: roleIdsKnown.entries.firstOrNull { p.id in it.value }?.key

    /** The columns there can be: the wideband's only while it is switched on. */
    fun offered(widebandOn: Boolean = Wideband.config.value.enabled): List<LogColumn> =
        if (widebandOn) all else all.filter { it.group != WIDEBAND }

    fun enabled(): List<LogColumn> {
        val off = Repo.settings.logOff
        return offered().filter { it.key !in off }
    }

    /**
     * The first column (since 0.9-beta22): ms from the start of the file, as RomRaider writes its «Time». EcuEdit
     * and the like take the first column for the time axis and choke on epoch ms.
     */
    const val TIME = "Time"
    /** The last one: the sample's wall clock in epoch ms (the first column «time» up to 0.9-beta21). */
    const val EPOCH_MS = "epoch_ms"
    /** 0.9-beta21's last column: ms from the start, when the first one was still the epoch. */
    const val T_MS = "t_ms"

    /** A log's first line: [TIME], the [names] in between, [EPOCH_MS]. */
    fun header(names: List<String>): String = (listOf(TIME) + names + EPOCH_MS).joinToString(",")

    /** One line under [header]: [values] already joined by commas, the file started at [t0]. */
    fun line(timeMs: Long, t0: Long, values: String): String = buildString {
        append((timeMs - t0).coerceAtLeast(0))
        if (values.isNotEmpty()) append(',').append(values)
        append(',').append(timeMs)
    }
}
