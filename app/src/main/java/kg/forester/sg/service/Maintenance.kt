package kg.forester.sg.service

import kg.forester.sg.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One maintenance job: every [km] kilometres or [months] months, whichever comes first. */
data class ServiceItem(
    val id: String,
    val name: String,
    val km: Int,
    val months: Int,
    val note: String = "",
    val on: Boolean = true,
    /** Odometer and date when it was last done; -1 / 0 = not entered yet. */
    val lastKm: Double = -1.0,
    val lastMs: Long = 0L,
    val custom: Boolean = false,
    /** Engine hours interval (0 = not by hours) and the engine hours when it was last done (-1 = unknown). */
    val hours: Int = 0,
    val lastHours: Double = -1.0,
) {
    fun hoursLeft(h: Double) = if (hours <= 0 || lastHours < 0) null else lastHours + hours - h

    /** Kilometres left (negative = overdue), null without an odometer or a "last done". */
    fun kmLeft(odo: Double?) = if (odo == null || lastKm < 0 || km <= 0) null else lastKm + km - odo

    fun daysLeft(now: Long) = if (lastMs == 0L || months <= 0) null else ((lastMs + months * 30.44 * 86_400_000L - now) / 86_400_000L).toInt()

    /** 0…1 of the interval used up (the larger of distance and time). */
    fun used(odo: Double?, now: Long): Float? {
        val byKm = kmLeft(odo)?.let { 1f - (it / km).toFloat() }
        val byTime = daysLeft(now)?.let { 1f - it / (months * 30.44f) }
        val byHours = hoursLeft(Maintenance.hours())?.let { 1f - (it / hours).toFloat() }
        return listOfNotNull(byKm, byTime, byHours).maxOrNull()?.coerceAtLeast(0f)
    }

    fun due(odo: Double?, now: Long) = (kmLeft(odo)?.let { it <= 500 } ?: false) || (daysLeft(now)?.let { it <= 14 } ?: false) ||
        (hoursLeft(Maintenance.hours())?.let { it <= 15 } ?: false)
}

/**
 * Maintenance planner with intervals for Subaru EJ engines (timing belt!), kept in the settings. The odometer is
 * the value the user typed in plus the distance the app counted since (trips).
 */
object Maintenance {
    private fun defaults(): List<ServiceItem> {
        val mt = Repo.settings.gearbox == "mt"
        return listOf(
            ServiceItem("oil", "Масло и фильтр двигателя", 7500, 6, "Турбо и город — лучше 5000–7000 км. EJ любит масло 5W-30/5W-40 и проверку уровня между заменами. " +
                "В пробках и на прогревах километров мало, а мотор работает: поэтому ещё и по моточасам (250 ч ≈ 7500 км со средней 30 км/ч).", hours = 250),
            ServiceItem("belt", "Ремень ГРМ, ролики, помпа", 100_000, 72, "На EJ20/EJ25 ремень. При обрыве на DOHC моторах гнёт клапаны. На FB/FA (Forester SJ/SK) цепь — этот пункт можно выключить."),
            ServiceItem("plugs", "Свечи зажигания", 30_000, 36, "Иридиевые на атмо — до 60 000 км. На EJ для замены часто снимают АКБ/воздуховод: оппозит."),
            ServiceItem("air", "Воздушный фильтр", 30_000, 24),
            ServiceItem("cabin", "Салонный фильтр", 15_000, 12),
            if (mt) ServiceItem("gear", "Масло в МКПП", 45_000, 48)
            else ServiceItem("gear", "ATF в АКПП (частичная замена)", 40_000, 36, "4EAT/5EAT: менять частично, без промывок под давлением. Перегрев ATF сокращает интервал."),
            ServiceItem("diff", "Масло в заднем редукторе", 45_000, 48, "У АКПП передний дифференциал общий с коробкой, у МКПП — в коробке."),
            ServiceItem("coolant", "Антифриз", 40_000, 36, "После замены прогнать воздух через пробку радиатора: EJ боится воздушных пробок."),
            ServiceItem("brake", "Тормозная жидкость", 40_000, 24),
            ServiceItem("belts", "Приводные ремни навесного", 40_000, 36),
            ServiceItem("pads", "Тормозные колодки и диски (осмотр)", 15_000, 12),
        )
    }

    fun items(): List<ServiceItem> {
        val saved = runCatching { JSONArray(Repo.settings.maintenance) }.getOrNull()
        val byId = HashMap<String, JSONObject>()
        if (saved != null) for (i in 0 until saved.length()) saved.getJSONObject(i).let { byId[it.getString("id")] = it }
        fun merge(d: ServiceItem, o: JSONObject?) = if (o == null) d else d.copy(
            name = o.optString("name", d.name), km = o.optInt("km", d.km), months = o.optInt("months", d.months),
            on = o.optBoolean("on", d.on), lastKm = o.optDouble("lastKm", d.lastKm), lastMs = o.optLong("lastMs", d.lastMs),
            hours = o.optInt("hours", d.hours), lastHours = o.optDouble("lastHours", d.lastHours),
        )
        val base = defaults().map { merge(it, byId[it.id]) }
        val custom = byId.values.filter { it.optBoolean("custom") }.map {
            merge(ServiceItem(it.getString("id"), it.optString("name"), 10_000, 12, custom = true), it)
        }.sortedBy { it.id }
        return base + custom
    }

    fun save(list: List<ServiceItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("km", it.km).put("months", it.months)
                .put("on", it.on).put("lastKm", it.lastKm).put("lastMs", it.lastMs).put("custom", it.custom)
                .put("hours", it.hours).put("lastHours", it.lastHours))
        }
        Repo.settings.maintenance = arr.toString()
        Repo.maintenanceVersion.value++
    }

    fun update(item: ServiceItem) = save(items().map { if (it.id == item.id) item else it }.let { l ->
        if (l.any { it.id == item.id }) l else l + item
    })

    fun remove(id: String) = save(items().filter { it.id != id })

    /** Current odometer, km, or null until the user enters it once. */
    fun odometer(): Double? {
        val s = Repo.settings
        if (s.odoBaseKm < 0) return null
        return s.odoBaseKm + (Repo.db.totals().first - s.odoBaseTripsKm).coerceAtLeast(0.0)
    }

    fun setOdometer(km: Double) {
        Repo.settings.odoBaseKm = km
        Repo.settings.odoBaseTripsKm = Repo.db.totals().first
        Repo.maintenanceVersion.value++
    }

    /** Engine hours now (counted by the app while the engine runs, the user can set the starting value). */
    fun hours(): Double = Repo.settings.engineHours

    fun setHours(h: Double) {
        Repo.settings.engineHours = h
        Repo.maintenanceVersion.value++
    }

    /** "Сделано сейчас": today's date, odometer and engine hours. */
    fun markDone(item: ServiceItem) = update(item.copy(lastKm = odometer() ?: item.lastKm, lastMs = System.currentTimeMillis(), lastHours = hours()))

    /** The most urgent due job as a sentence, for the daily reminder. */
    fun dueText(): String? {
        val odo = odometer()
        val now = System.currentTimeMillis()
        val due = items().filter { it.on && it.due(odo, now) }
            .maxByOrNull { it.used(odo, now) ?: 0f } ?: return null
        val km = due.kmLeft(odo)?.roundToInt()
        val days = due.daysLeft(now)
        val h = due.hoursLeft(hours())?.roundToInt()
        val left = when {
            km != null && km < 0 -> "просрочено на ${kg.forester.sg.data.DistanceUnits.distanceText(-km.toDouble())}"
            days != null && days < 0 -> "просрочено на ${-days} дн."
            h != null && h < 0 -> "просрочено на ${-h} моточасов"
            km != null && km <= 500 -> "осталось ${kg.forester.sg.data.DistanceUnits.distanceText(km.toDouble())}"
            h != null && h <= 15 -> "осталось $h моточасов"
            days != null -> "осталось $days дн."
            else -> ""
        }
        return "Пора на обслуживание: ${due.name.lowercase()}, $left"
    }
}
