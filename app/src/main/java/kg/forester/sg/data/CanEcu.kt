package kg.forester.sg.data

import kg.forester.sg.ssm.LinkDash
import kg.forester.sg.ssm.RrParam

/**
 * A sports ECU on a CANable (Link G4X and the like): its stream's values beyond the regular sample, and the driver's
 * own CAN channels, as parameters the rest of the app already knows (cards, «Датчики», the log, the engine guard).
 */
object CanEcu {
    /** A CANable session is on: only then do its values stand in for the SensorBox roles. */
    @Volatile var active = false

    const val PREFIX = "LNK_"

    private val linkParams: List<RrParam> = LinkDash.channels.map { c ->
        RrParam(
            id = PREFIX + c.key, kind = RrParam.Kind.EXT, target = 1, name = c.name, nameEn = c.nameEn, desc = "Link по CAN",
            addr = -1, len = 0, bit = -1, units = c.units, format = c.format, storage = "", expr = null,
            deps = emptyList(), conversions = emptyMap(),
        )
    }

    /** What this session offers: the channels of the Link's stream once found, and the own channels always. */
    fun params(): List<RrParam> {
        val keys = LinkDash.channelsNow().map { PREFIX + it.key }.toSet()
        return linkParams.filter { it.id in keys } + CanChannels.list().map { it.toParam() }
    }

    fun values(now: Long = System.currentTimeMillis()): Map<String, Double> =
        LinkDash.values(now).mapKeys { PREFIX + it.key }.mapValues { it.value.toDouble() } + CanChannels.values(now)

    /**
     * [role]'s value (EGT and oil temperature in °C, oil pressure in bar) from an own channel set to it, else from
     * the Link's stream; null without a CANable session.
     */
    fun role(role: String, now: Long = System.currentTimeMillis()): Float? {
        if (!active) return null
        val own = CanChannels.list().filter { it.role == role }.mapNotNull { CanChannels.values(now)[it.id] }.maxOrNull()
        if (own != null) return own.toFloat()
        val v = LinkDash.values(now)
        // an input with no sensor on it sends 0 (oil pressure "none", oil at −50°): only once it has shown a real value
        return when (role) {
            "oil" -> v["OILT"]?.takeIf { it > -40f }?.also { if (it > 0f) wired += "OILT" }?.takeIf { "OILT" in wired }
            "oilP" -> v["OILP"]?.also { if (it > 0.5f) wired += "OILP" }?.takeIf { "OILP" in wired }
            else -> null
        }
    }

    /** The unit of what [role] gives from the Link's stream. */
    fun roleUnit(role: String) = when (role) { "oilP" -> "бар"; "oil" -> "°C"; else -> "" }

    private val wired = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun reset() { wired.clear() }
}
