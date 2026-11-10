package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.BatteryEvent
import kg.forester.sg.data.BatteryState
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks battery health from ECU voltage, the way a battery / alternator check is done with a voltmeter:
 *  - resting: ignition on, engine not running (before cranking), with how long the car stood (surface charge
 *    is gone after ~2 h, only then the voltage tells the state of charge);
 *  - cranking: minimum voltage while the starter turns, how long it turned, how fast, the air temperature
 *    (a cold start is compared with cold starts) and how many seconds the voltage needs to be back at 13.2 V;
 *  - charging: alternator output at idle and at 1800–3500 rpm, with the lowest and highest of the drive.
 * The last value of each kind is restored from the event log on start.
 */
class BatteryMonitor(private val ctx: Context) {
    private data class Sample(val t: Long, val v: Float, val rpm: Int)

    private val recent = ArrayDeque<Sample>()
    private var wasRunning = false
    private var lastStoppedMs = 0L
    private var runningSinceMs = 0L
    /** Resting voltage over the last [RESTING_MS]: time, volts. */
    private val restingSamples = ArrayDeque<Pair<Long, Float>>()
    private var restingLogged = false
    /** Running voltage over the last [CHARGE_MS]: time, volts. */
    private val chargeSamples = ArrayDeque<Pair<Long, Float>>()
    private var chargeLogged = false
    private var voltSum = 0.0
    private var voltN = 0
    private var voltSlot = 0L
    private val idleSamples = ArrayList<Float>()
    private val revSamples = ArrayList<Float>()
    private var idleRevMs = 0L
    private var chargeMin = Float.NaN
    private var chargeMax = Float.NaN
    private var lastAltDuty = 0f
    /** A start whose record waits for the voltage to recover (or a minute to pass). */
    private var pendingCrank: BatteryEvent? = null
    private var restingSinceMs = 0L
    /** When the last sample came: a long pause before the next one is the ignition switched off in between. */
    private var lastSampleMs = 0L
    /** Voltage of the last 30 s with the engine running, for the alternator warnings. */
    private val window30 = ArrayDeque<Pair<Long, Float>>()
    /** This drive the alternator was seen charging (13.6 V+): 12.5–13 V later is the ECU's charge cut-out, not a fault. */
    private var seenCharging = false

    init {
        restoreFromLog()
    }

    fun process(s: EcuSnapshot) {
        // below 5 V the ECU itself would be off: a dead or garbled answer read as volts, not the battery (0.00 V
        // resting and crank on a Legacy GT whose link kept dropping)
        val v = s.batteryV?.takeIf { it >= MIN_VALID_V && it <= 20f } ?: return
        val rpm = s.rpm ?: 0
        val now = s.timeMs
        val running = rpm > 500

        recent.addLast(Sample(now, v, rpm))
        while (recent.isNotEmpty() && now - recent.first().t > 4000) recent.removeFirst()

        // 1-minute averages for the history chart
        val slot = now / 60_000 * 60_000
        if (voltSlot != 0L && slot != voltSlot && voltN > 0) {
            Repo.db.addVolt(voltSlot, voltSum / voltN, wasRunning)
            voltSum = 0.0; voltN = 0
        }
        voltSlot = slot
        voltSum += v; voltN++

        // no data for a while: the ignition was off. The ECU loses power with the key, mostly before it has read
        // 0 rpm, so the engine stopped when it was last heard running, not now (else the next morning's first
        // reading would say "stood 0 min"), and the stop's resting reading is a new one
        if (lastSampleMs != 0L && now - lastSampleMs > SESSION_GAP_MS) {
            if (wasRunning) {
                Repo.settings.lastEngineOffMs = lastSampleMs
                logCharging(lastSampleMs)
                finishCrank(lastSampleMs, -1f)
                wasRunning = false
            }
            restingSamples.clear()
            restingLogged = false
        }
        lastSampleMs = now

        if (!running && wasRunning) {
            Repo.settings.lastEngineOffMs = now
            logCharging(now)
            finishCrank(now, -1f)
        }
        if (rpm == 0) {
            lastStoppedMs = now
            if (restingSamples.isEmpty()) restingSinceMs = now
            restingSamples.addLast(now to v)
            while (now - restingSamples.first().first > RESTING_MS) restingSamples.removeFirst()
            // after ~5 s of steady ignition-on readings publish the resting voltage: by time, not by count (OBD-II
            // reads the voltage only every few polls, the fast poll 12 times a second)
            if (restingSamples.size >= 5 && now - restingSinceMs >= 5000) {
                resting(restingSamples.map { it.second }.sorted()[restingSamples.size / 2], now, s)
            }
        }

        if (running && !wasRunning) {
            runningSinceMs = now
            window30.clear()
            chargeSamples.clear()
            chargeLogged = false
            seenCharging = false
            idleSamples.clear(); revSamples.clear(); chargeMin = Float.NaN; chargeMax = Float.NaN
            // A real start: the engine was stopped within the last 10 s. The ECU can report 0 rpm while
            // the starter turns, so take the minimum over the last few seconds of samples.
            if (now - lastStoppedMs < 10_000) {
                val window = recent.filter { it.rpm < 500 }
                val minV = window.minOfOrNull { it.v }
                if (minV != null) {
                    val crankStart = window.firstOrNull { it.v < (restingSamples.lastOrNull()?.second ?: minV) - 0.3f }?.t
                    val dur = if (crankStart != null) now - crankStart else 0L
                    // cranking speed: the ECU's rpm while the starter turns (0 on some ECUs: then unknown)
                    val crankRpm = window.map { it.rpm }.filter { it in 50..499 }.takeIf { it.isNotEmpty() }?.average()?.toInt()
                    Repo.battery.value = Repo.battery.value.copy(
                        crankMinV = minV, crankTimeMs = now, crankMs = dur, crankRecoveryS = null,
                        crankTempC = s.intakeC, crankRpm = crankRpm,
                    )
                    pendingCrank = BatteryEvent(now, "crank", minV.toDouble(), dur.toDouble(), info(
                        "intake_c" to s.intakeC?.toDouble(), "coolant_c" to s.coolantC?.toDouble(), "crank_rpm" to crankRpm?.toDouble(),
                    ))
                    // cranked before the 5 s of a resting reading: what the ECU gave between the key and the starter
                    // (a daily start, the app connected a second or two before it, never had one)
                    if (!restingLogged) beforeCrank(restingSamples, minV)?.let { (t, rv) -> resting(rv, t, s) }
                }
            }
            restingSamples.clear()
            restingLogged = false
        }

        // recovery after the start: how soon the alternator brings the voltage back to 13.2 V
        pendingCrank?.let { c ->
            if (running && v >= 13.2f) finishCrank(now, (now - c.timeMs) / 1000f)
            else if (now - c.timeMs > 60_000) finishCrank(now, -1f)
        }

        // charging seen from the first seconds after the start (right after cranking the alternator gives 14+ V)
        if (running && v >= 13.6f) seenCharging = true
        if (running && now - runningSinceMs > 30_000) {
            window30.addLast(now to v)
            while (window30.isNotEmpty() && now - window30.first().first > 30_000) window30.removeFirst()
            if (Repo.settings.feature("altAlert") && window30.size >= 5 && now - runningSinceMs > 60_000) {
                val avg = window30.map { it.second }.average().toFloat()
                val last5 = window30.filter { now - it.first <= 5_000 }.map { it.second }
                val runMin = (now - runningSinceMs) / 60_000f
                // 2001–2008 Subarus cut the charging out at low load: 12.5–13 V on the move is normal there
                // (docs/knowledge/transmission-awd-electrical.md, charging_cutout_normal)
                when {
                    // below the cut-out band (12.5–13.1 V), or low without the alternator ever charging this drive
                    avg < 12.35f || (avg < 12.6f && !seenCharging) -> Alerts.raise("altLow", "Генератор не заряжает: %.2f В на работающем моторе. Машина едет на запасе аккумулятора: ".format(avg) +
                        "выключите лишние потребители и проверьте ремень, клеммы и генератор", Alerts.Level.DANGER, 30 * 60_000L)
                    avg < 13.0f && !seenCharging && runMin >= 5f -> Alerts.raise("altLow", "Похоже, генератор не заряжает: %.2f В, и с пуска напряжение ни разу не поднималось до 13,6 В. ".format(avg) +
                        "Проверьте ремень и генератор", Alerts.Level.WARN, 30 * 60_000L)
                    // the factory ECU's cut-out is no excuse on a sports ECU: it rarely drives the alternator that way
                    avg < 13.15f && seenCharging && window30.maxOf { it.second } - window30.minOf { it.second } < 0.3f ->
                        if (kg.forester.sg.data.CanEcu.active) Alerts.raise("altLow", "Напряжение на ходу %.2f В. Со штатным ЭБУ так бывает при умной зарядке, у Link её обычно нет: ".format(avg) +
                            "генератор должен давать 13,6–14,6 В, проверьте его и ремень", Alerts.Level.INFO, 60 * 60_000L)
                        else cutoutInfo(avg, now)
                    last5.size >= 2 && last5.min() > 15.5f -> Alerts.raise("altHigh", "Перезаряд: %.2f В. Неисправен регулятор генератора, это портит аккумулятор и электронику".format(last5.average()),
                        Alerts.Level.DANGER, 30 * 60_000L)
                    avg > 15.0f && (runMin >= 10f || (s.coolantC ?: 0) >= 60) -> Alerts.raise("altHigh", "Напряжение зарядки высокое: %.2f В. Проверьте регулятор генератора".format(avg),
                        Alerts.Level.WARN, 30 * 60_000L)
                }
            }
            // a sample per quarter second, as the plain polling gives: 3000 of them are the last ~12 min at that rpm
            if (now - idleRevMs >= 200) {
                idleRevMs = now
                if (rpm < 1000) idleSamples.add(v) else if (rpm in 1800..3500) revSamples.add(v)
                if (idleSamples.size > 3000) idleSamples.removeAt(0)
                if (revSamples.size > 3000) revSamples.removeAt(0)
            }
            chargeMin = if (chargeMin.isNaN()) v else minOf(chargeMin, v)
            chargeMax = if (chargeMax.isNaN()) v else maxOf(chargeMax, v)
            lastAltDuty = s.altDutyPct ?: lastAltDuty
            chargeSamples.addLast(now to v)
            while (now - chargeSamples.first().first > CHARGE_MS) chargeSamples.removeFirst()
            val b = Repo.battery.value
            Repo.battery.value = b.copy(
                chargingV = chargeSamples.map { it.second }.average().toFloat(),
                chargingMinV = minOf(b.chargingMinV ?: v, v),
                chargingMaxV = maxOf(b.chargingMaxV ?: v, v),
                chargeIdleV = idleSamples.takeIf { it.size >= 5 }?.average()?.toFloat() ?: b.chargeIdleV,
                chargeRevV = revSamples.takeIf { it.size >= 5 }?.average()?.toFloat() ?: b.chargeRevV,
            )
            // one record per drive: at the engine stop, or after 10 minutes if the drive is long
            if (!chargeLogged && now - runningSinceMs > 10 * 60_000L) logCharging(now)
        }
        wasRunning = running
    }

    /** Once a month: the 12.8 V on the move is the ECU saving fuel, so the driver doesn't take it for a fault. */
    private fun cutoutInfo(avg: Float, now: Long) {
        val p = ctx.getSharedPreferences("analyzer", Context.MODE_PRIVATE)
        if (now - p.getLong("cutoutInfo", 0L) < 30L * 24 * 3600_000L) return
        p.edit().putLong("cutoutInfo", now).apply()
        Alerts.raise("altCutout", "Напряжение %.1f В на ходу у этой Subaru нормально: блок двигателя сам временно отключает зарядку при малой нагрузке. ".format(avg) +
            "Включите фары или печку, и зарядка вернётся", Alerts.Level.INFO, 0L, speak = false)
    }

    /** A resting voltage [med] read at [now]: on the screen, and the stop's first one in the log. */
    private fun resting(med: Float, now: Long, s: EcuSnapshot) {
        Repo.battery.value = Repo.battery.value.copy(restingV = med, restingTimeMs = now)
        val off = Repo.settings.lastEngineOffMs
        val parkedH = if (off > 0 && now > off) (now - off) / 3_600_000f else null
        Repo.battery.value = Repo.battery.value.copy(restingParkedH = parkedH)
        if (restingLogged) return
        restingLogged = true
        // a flat battery: below 12.2 V after standing long enough for the surface charge to go
        if (Repo.settings.feature("battAlert") && med < 12.2f && (parkedH == null || parkedH >= 2f)) {
            Alerts.raise("battLow", "Аккумулятор разряжен: %.1f В, около %d %%".format(med, socPct(med)), Alerts.Level.WARN, 6 * 3_600_000L)
        }
        event(now, "resting", med.toDouble(), 0.0, info(
            "parked_h" to parkedH?.toDouble(), "soc_pct" to socPct(med).toDouble(),
            "intake_c" to s.intakeC?.toDouble(), "coolant_c" to s.coolantC?.toDouble(),
        ))
    }

    private fun logCharging(now: Long) {
        // at least a minute of charging and some readings (OBD-II reads the voltage only every few polls)
        if (chargeLogged || chargeSamples.size < 10 || now - runningSinceMs < 90_000) return
        chargeLogged = true
        event(now, "charging", chargeSamples.map { it.second.toDouble() }.average(), lastAltDuty.toDouble(), info(
            "idle_v" to idleSamples.takeIf { it.size >= 5 }?.average(), "rev_v" to revSamples.takeIf { it.size >= 5 }?.average(),
            "min_v" to chargeMin.takeUnless { it.isNaN() }?.toDouble(), "max_v" to chargeMax.takeUnless { it.isNaN() }?.toDouble(),
        ))
    }

    /** Writes the waiting start record with its recovery time (-1 = not back at 13.2 V within a minute). */
    private fun finishCrank(now: Long, recoveryS: Float) {
        val c = pendingCrank ?: return
        pendingCrank = null
        Repo.battery.value = Repo.battery.value.copy(crankRecoveryS = recoveryS)
        if (Repo.settings.feature("battAlert")) {
            when {
                c.value < 9.6 -> Alerts.raise("battCrank", "Слабый пуск: напряжение падало до %.1f В — проверьте аккумулятор".format(c.value),
                    Alerts.Level.WARN, 6 * 3_600_000L)
                recoveryS < 0 -> Alerts.raise("battRecover", "После пуска напряжение минуту не поднимается до 13,2 В — проверьте генератор и АКБ",
                    Alerts.Level.WARN, 6 * 3_600_000L)
            }
        }
        val info = c.info + (if (c.info.isEmpty()) "" else ";") + "recovery_s=%.1f".format(Locale.US, recoveryS)
        event(c.timeMs, c.type, c.value, c.extra, info)
    }

    private fun info(vararg kv: Pair<String, Double?>) =
        kv.filter { it.second != null }.joinToString(";") { (k, v) -> "$k=" + "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.') }

    private fun restoreFromLog() {
        runCatching { Repo.db.purgeBogusCranks() }
        val events = runCatching { Repo.db.batteryEvents(200) }.getOrDefault(emptyList())
        val resting = events.firstOrNull { it.type == "resting" }
        val crank = events.firstOrNull { it.type == "crank" }
        val charging = events.firstOrNull { it.type == "charging" }
        Repo.battery.value = BatteryState(
            restingV = resting?.value?.toFloat(), restingTimeMs = resting?.timeMs ?: 0,
            restingParkedH = resting?.num("parked_h")?.toFloat(),
            crankMinV = crank?.value?.toFloat(), crankTimeMs = crank?.timeMs ?: 0, crankMs = crank?.extra?.toLong(),
            crankRecoveryS = crank?.num("recovery_s")?.toFloat(), crankTempC = crank?.num("intake_c")?.toInt(),
            crankRpm = crank?.num("crank_rpm")?.toInt(),
            chargingV = charging?.value?.toFloat(),
            chargingMinV = charging?.num("min_v")?.toFloat(), chargingMaxV = charging?.num("max_v")?.toFloat(),
            chargeIdleV = charging?.num("idle_v")?.toFloat(), chargeRevV = charging?.num("rev_v")?.toFloat(),
        )
    }

    private fun event(now: Long, type: String, value: Double, extra: Double, info: String = "") {
        val e = BatteryEvent(now, type, value, extra, info)
        Repo.db.addBatteryEvent(e)
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val f = File(dir, "battery.csv")
            // a file from an older version has fewer columns: keep it aside, start a new one
            if (f.exists() && f.bufferedReader().use { it.readLine() } != CSV_HEAD) {
                f.renameTo(File(dir, "battery-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + "-old.csv"))
            }
            val newFile = !f.exists()
            FileWriter(f, true).use { w ->
                if (newFile) w.write(CSV_HEAD + "\n")
                val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
                fun col(k: String) = e.num(k)?.let { "%.2f".format(Locale.US, it).trimEnd('0').trimEnd('.') } ?: ""
                w.write("$t,$type,${"%.2f".format(Locale.US, value)},${"%.0f".format(Locale.US, extra)}," +
                    CSV_INFO.joinToString(",") { col(it) } + "\n")
            }
        }
        Repo.touch()
    }

    companion object {
        /** The resting median's window and the charging average's: 40 and 600 samples of the plain polling. */
        private const val RESTING_MS = 10_000L
        private const val CHARGE_MS = 150_000L
        /** Longer without a sample: the ignition was off in between (a lost answer or two are a second). */
        const val SESSION_GAP_MS = 30_000L
        /** Lowest voltage taken as a reading: the ECU resets below about 6 V, so anything lower is a bad answer. */
        const val MIN_VALID_V = 5f

        /**
         * The resting voltage from the 0 rpm samples (time, volts; oldest first) of a stop too short for the 5 s
         * reading, at the start whose lowest voltage was [crankMinV]: the time of the last one used, and their median.
         * Some ECUs read 0 rpm while the starter turns, so what sags 0.3 V+ below the highest is the starter and
         * left out, two samples must remain, and they must be clearly (0.5 V) above the crank's minimum: else they
         * were the cranking itself (the app connected while the starter turned). Null when there is no such reading.
         */
        fun beforeCrank(samples: List<Pair<Long, Float>>, crankMinV: Float): Pair<Long, Float>? {
            val top = samples.maxOfOrNull { it.second } ?: return null
            val kept = samples.filter { it.second > top - 0.3f }
            if (kept.size < 2) return null
            val med = kept.map { it.second }.sorted()[kept.size / 2]
            if (med - crankMinV < 0.5f) return null
            return kept.last().first to med
        }
        /** battery.csv: extra = crank ms / alternator duty %, then the further numbers of each reading. */
        private val CSV_INFO = listOf("parked_h", "soc_pct", "intake_c", "coolant_c", "crank_rpm", "recovery_s", "idle_v", "rev_v", "min_v", "max_v")
        private val CSV_HEAD = "datetime,type,volts,extra," + CSV_INFO.joinToString(",")

        /** Approximate state of charge of a rested 12 V lead-acid battery. */
        fun socPct(v: Float): Int = when {
            v >= 12.7f -> 100
            v <= 11.8f -> 0
            else -> ((v - 11.8f) / 0.9f * 100).toInt()
        }
    }
}
