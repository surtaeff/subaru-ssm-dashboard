package kg.forester.sg.ssm

import android.content.Context
import kg.forester.sg.data.Repo

/**
 * Which engine the car has, from what the ECU itself says, never from what the driver typed in the settings:
 * the ROM ID looked up in the base of known ROMs (assets/ecu_ids.json: "JDM Forester XT 2003 AT" → EJ20 turbo),
 * else decoded from the ROM ID's own bytes, else what the ECU said at the last connection. A wastegate or boost
 * seen makes it a turbo. The family is sure; the exact code (EJ205, EJ257…) is the likely one for that market and year.
 */
data class EngineInfo(
    /** "EJ20", "EJ22", "EJ25", "FA20", "FB", "EZ30", "EZ36", "EE20"; "EJ" when the size is not known. */
    val family: String,
    val turbo: Boolean,
    /** The likely code, e.g. "EJ205"; may be just the family. */
    val code: String,
    /** How it was found: "ЭБУ" (ROM in the base), "ROM" (decoded from the ROM ID), "прошлое" (the last connection),
     *  "ЭБУ без модели" (the ECU answered, its ROM ID says nothing), "демо", "Link" (a sports ECU on the CAN),
     *  "нет" (no ECU yet). */
    val source: String,
    val romId: String? = null,
    val car: String? = null,
) {
    val cylinders get() = if (family.startsWith("EZ")) 6 else 4
    val litres get() = when (family) { "EJ25", "FB" -> "2.5"; "EJ22" -> "2.2"; "EZ30" -> "3.0"; "EZ36" -> "3.6"; "EJ" -> ""; else -> "2.0" }
    val diesel get() = family == "EE20"
    /** Known from the ECU (now or at the last connection), not a placeholder. */
    val known get() = source == "ЭБУ" || source == "ROM" || source == "прошлое"
    val title: String get() = when (source) {
        "нет" -> "Мотор не определён"
        else -> listOf(code, litres, if (diesel) "дизель" else if (turbo) "турбо" else "атмо").filter { it.isNotEmpty() }.joinToString(" · ") +
            (if (cylinders == 6) " · шесть цилиндров" else "") + if (family == "EJ") " · объём не определён" else ""
    }
}

object EngineId {
    private val TURBO_WORDS = listOf("XT", "WRX", "STI", "GT", "TURBO", "SPEC.B", "SGT", "B4", "2.0GT", "DIT", "S.EDITION")

    /** The engine from a car description of the ECU id base ("USDM Impreza STI 2007 MT"). */
    fun fromCar(car: String, source: String = "ЭБУ", romId: String? = null): EngineInfo {
        val u = car.uppercase()
        val words = u.split(' ', '/').filter { it.isNotBlank() }
        val year = yearOf(u)
        val market = words.firstOrNull().orEmpty()
        val turbo = TURBO_WORDS.any { t -> words.any { it == t || it.startsWith(t) } } && !u.contains(" NA ")
        fun info(f: String, t: Boolean, code: String) = EngineInfo(f, t, code, source, romId, car)
        return when {
            u.contains("2.0D") || u.contains("DIESEL") -> info("EE20", true, "EE20")
            // the Tribeca got the 3.6 for 2008
            u.contains("3.6") || (u.contains("TRIBECA") && (year ?: 0) >= 2008) -> info("EZ36", false, "EZ36")
            u.contains("3.0R") || u.contains(" H6") || u.contains("TRIBECA") || u.contains("B9") -> info("EZ30", false, "EZ30")
            u.contains("FA20") || u.contains("BRZ") || (u.contains("WRX") && !u.contains("STI") && (year ?: 0) >= 2015) ->
                info("FA20", u.contains("WRX"), if (u.contains("WRX")) "FA20DIT" else "FA20")
            u.contains("FB25") || (u.contains(" FB") ) || u.contains(" XV") || (u.contains("FORESTER") && !turbo && (year ?: 0) >= 2011) ->
                info("FB", false, if (u.contains("FB20") || u.contains("2.0")) "FB20" else "FB25")
            turbo -> {
                // the 2.0 turbos: Japan, early WRX elsewhere, and Europe's SF and SG Forester up to 2005 (the XT badge with no
                // year proves nothing: later ones are the 2.5); the rest of the world got the 2.5
                val edmForester = (market == "EDM" || market == "EUDM") && u.contains("FORESTER") &&
                    (year?.let { it <= 2005 } ?: u.contains("TURBO"))
                val two = market == "JDM" || u.contains("2.0") || edmForester ||
                    (u.contains("WRX") && (year ?: 2005) <= 2005 && market != "JDM") || u.contains("EXIGA")
                val family = if (two) "EJ20" else "EJ25"
                info(family, true, ejCode(family, true, u, year))
            }
            u.contains("2.0") || (market == "JDM" && !u.contains("2.5")) -> info("EJ20", false, ejCode("EJ20", false, u, year))
            u.contains("2.2") -> info("EJ22", false, "EJ22")
            else -> info("EJ25", false, ejCode("EJ25", false, u, year))
        }
    }

    /** The model year in a car description of the base, null when it has none. */
    internal fun yearOf(car: String) = car.split(' ', '/').mapNotNull { it.toIntOrNull() }.firstOrNull { it in 1990..2030 }

    /** The likely code of an EJ of this size, by what the (uppercase) car description says. */
    private fun ejCode(family: String, turbo: Boolean, u: String, year: Int?) = when {
        family == "EJ20" && !turbo -> "EJ20 (EJ201/EJ202/EJ204)"
        family == "EJ20" && u.contains("STI") -> "EJ207"
        family == "EJ20" && (u.contains("LEGACY") || u.contains("B4") || u.contains("GT")) && (year ?: 2004) >= 2003 -> "EJ20X/EJ20Y"
        family == "EJ20" -> "EJ205"
        !turbo -> if ((year ?: 2005) >= 2006) "EJ253" else "EJ251"
        // the JDM 2.5 turbos (Forester STi, the A-Line) carry the EJ255 whatever the badge says
        u.contains("STI") && !u.startsWith("JDM") -> "EJ257"
        else -> "EJ255"
    }

    /**
     * The engine of a ROM in the base. 57 of its 650 ROMs have a name and bytes that disagree; read one by one, the
     * bytes are right: the name's market or size was guessed ("EDM Impreza STi" is the 2.0, "JDM Forester STi" the
     * 2.5). So the family and the turbo come from the bytes, the model and year stay the name's. Not checked on the
     * ECUs themselves, this follows from the ROMs' signatures. Left to the name:
     * - byte 3 from 0xD0 up (the diesels) under a petrol name. Only 3D04EA4605 ("USDM Impreza STi"): its bytes are wrong.
     * - bytes of no known size.
     * 5C52184007 "ADM Forester 2.5 FB 2012" has no exception: 2012 is its name's guess. Its bytes are the ADM twin of
     * 5C12184007 "USDM Forester 2.5i 2009", prefix 5C is the 2009 batch and the real ADM FB25 is 7152345007, so
     * it is the EJ253 of an SH.
     */
    fun fromBase(car: String, romId: String, source: String = "ЭБУ"): EngineInfo {
        val named = fromCar(car, source, romId)
        val bytes = fromRomId(romId) ?: return named
        if (bytes.family == "EJ" || (bytes.diesel && !named.diesel)) return named
        if (bytes.family == named.family && bytes.turbo == named.turbo) return named
        val code = if (bytes.family.startsWith("EJ")) ejCode(bytes.family, bytes.turbo, car.uppercase(), yearOf(car)) else bytes.code
        return named.copy(family = bytes.family, turbo = bytes.turbo, code = code)
    }

    /**
     * The engine from the ROM ID's own bytes, for a ROM the base doesn't know (K-line SSM2 ECUs of 1999–2010, ROM
     * IDs 1B…6F). Checked against the ~600 ROMs of the base: byte 2's low digit is the engine (2 = 2.5 l, 4 = 2.0 l,
     * A/B = the sixes), byte 3 from 0x40 up is a turbo (agrees on 99 % of them). Null for the later schemes.
     */
    fun fromRomId(romId: String): EngineInfo? {
        val id = romId.uppercase()
        if (id.length < 6 || id.startsWith("13") || id.startsWith("14")) return null
        val b1 = id.substring(0, 2).toIntOrNull(16) ?: return null
        val b2 = id.substring(2, 4).toIntOrNull(16) ?: return null
        val b3 = id.substring(4, 6).toIntOrNull(16) ?: return null
        if (b1 !in 0x1B..0x6F) return null
        fun info(f: String, t: Boolean, code: String) = EngineInfo(f, t, code, "ROM", id)
        return when (b2 and 0xF) {
            0xA -> info("EZ30", false, "EZ30")
            0xB -> info("EZ36", false, "EZ36")
            else -> {
                if (b3 >= 0xD0) return info("EE20", true, "EE20") // the diesels
                val turbo = b3 >= 0x40
                when (b2 and 0xF) {
                    2 -> info("EJ25", turbo, if (turbo) "EJ255/EJ257" else "EJ25")
                    4 -> info("EJ20", turbo, if (turbo) "EJ205/EJ207" else "EJ20")
                    else -> info("EJ", turbo, "EJ")
                }
            }
        }
    }

    /** The best guess now: the ECU's ROM, else what the ECU said last time; a wastegate or boost seen makes it a turbo. */
    fun detect(ctx: Context): EngineInfo {
        if (Repo.settings.demo) return EngineInfo("EJ20", true, "EJ205", "демо")
        // a sports ECU tells nothing of the engine: not the last Subaru's either; turbo once boost was seen on it
        if (kg.forester.sg.data.CanEcu.active) return EngineInfo("EJ", Repo.guard.value.turboCar || Repo.settings.linkTurbo, "Link", "Link")
        val init = Repo.ecuInit.value
        val seen = Repo.guard.value.turboCar
        val base = when {
            init != null -> EcuIds.car(ctx, init.romId)?.let { fromBase(it, init.romId) }
                ?: fromRomId(init.romId)
                ?: EngineInfo("EJ", false, "EJ", "ЭБУ без модели", init.romId)
            else -> saved(ctx) ?: EngineInfo("EJ", false, "EJ", "нет")
        }
        // turbo by the ECU (boost seen, a wastegate it reports), whether or not the turbo timer is switched on
        val turbo = if (init != null) turbo(ctx, init, seen || Repo.settings.boostRom == init.romId) else seen || base.turbo
        val e = if (turbo != base.turbo) base.copy(turbo = turbo) else base
        if (init != null && e.source != "ЭБУ без модели") save(ctx, e)
        return e
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE)

    private fun save(ctx: Context, e: EngineInfo) {
        val o = org.json.JSONObject().put("family", e.family).put("turbo", e.turbo).put("code", e.code)
            .put("romId", e.romId ?: "").put("car", e.car ?: "")
        prefs(ctx).edit().putString("engine", o.toString()).apply()
    }

    /** What the ECU said at the last connection. */
    fun saved(ctx: Context): EngineInfo? = runCatching {
        val o = org.json.JSONObject(prefs(ctx).getString("engine", null) ?: return null)
        EngineInfo(o.getString("family"), o.getBoolean("turbo"), o.getString("code"), "прошлое",
            o.optString("romId").ifEmpty { null }, o.optString("car").ifEmpty { null })
    }.getOrNull()

    /** Turbo or not for this init: [ecuTurbo] is the answer, the same for the layout, the guard and [detect]. */
    fun turbo(ctx: Context?, init: EcuInit, boostSeen: Boolean): Boolean = kg.forester.sg.data.ecuTurbo(
        ctx?.let { romTurbo(it, init.romId) } ?: fromRomId(init.romId)?.takeIf { it.family != "EJ" || it.turbo }?.turbo,
        EcuParams.reported(init, EcuParams.WGDC), init.capabilities.size > 4, boostSeen,
    )

    /** Turbo by the ECU alone (its ROM ID): null = can't tell. */
    fun romTurbo(ctx: Context, romId: String): Boolean? =
        EcuIds.car(ctx, romId)?.let { fromBase(it, romId).turbo } ?: fromRomId(romId)?.takeIf { it.family != "EJ" || it.turbo }?.turbo
}
