package kg.forester.sg.data

import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A value the driver takes off the CAN bus by id and bytes, the way dash makers do it: an EGT the tuner put in a
 * user stream of the Link, a sensor of a Haltech or ECUMaster, a third-party CAN sensor board on the car's bus. Found
 * with the CAN monitor, checked by its live value. [role] makes it stand in for a SensorBox input of that role (EGT,
 * oil, fuel and exhaust pressure, intercooler air, ethanol): cards, the engine guard and the analyzer read it the same.
 */
data class CanChannel(
    val id: String,
    val name: String,
    val canId: Int,
    val ext: Boolean,
    /** Compound stream: byte 0 must be this frame number; -1 = any frame of the id. */
    val mux: Int,
    /** First byte, 0–7. */
    val byte: Int,
    /** 1, 2 or 4 bytes. */
    val len: Int,
    /** Motorola (high byte first) or Intel (low byte first, as the Link's Generic Dash). */
    val bigEndian: Boolean,
    val signed: Boolean,
    /** Over x, the raw number: "x*0.1", "x-50"… */
    val formula: String,
    val units: String,
    val decimals: Int,
    /** One of [ROLES]: a SensorBox role (°C, pressures in [units] or bar, % ethanol) or "fault" (the Link's fault code number). */
    val role: String,
    /**
     * A bit-level signal as a DBC (or RealDash's startbit/bitcount) gives it: its start bit, Intel or Motorola by
     * [bigEndian] (see [CanBits]); -1 = the byte model of [byte] and [len], which then stay a rough fallback.
     */
    val startBit: Int = -1,
    /** 1–64 bits of the bit-level signal. */
    val bitLen: Int = 0,
    /** Where a DBC multiplexor lies when it is not byte 0: start bit, -1 = byte 0 (the Link's compound stream). */
    val muxStart: Int = -1,
    val muxLen: Int = 8,
    val muxBe: Boolean = false,
) {
    private val expr by lazy { RrExpr.parse(formula.ifBlank { "x" }) }

    val bits get() = startBit >= 0 && bitLen > 0

    /** The raw number in [d], or null when the frame is too short (or not the right frame of a compound stream). */
    fun raw(d: ByteArray): Long? {
        if (mux >= 0) {
            val m = if (muxStart >= 0) CanBits.get(d, muxStart, muxLen, muxBe, false) else d.firstOrNull()?.let { it.toLong() and 0xFF }
            if (m != mux.toLong()) return null
        }
        if (bits) return CanBits.get(d, startBit, bitLen, bigEndian, signed)
        if (byte < 0 || byte + len > d.size) return null
        var u = 0L
        for (i in 0 until len) {
            val b = d[byte + if (bigEndian) i else len - 1 - i].toLong() and 0xFF
            u = (u shl 8) or b
        }
        if (signed) { val bits = len * 8; if (u and (1L shl (bits - 1)) != 0L) u -= 1L shl bits }
        return u
    }

    fun decode(d: ByteArray): Double? = raw(d)?.let { r ->
        // all 64 bits unsigned: past Long's range
        val x = if (bits && bitLen == 64 && !signed && r < 0) r.toULong().toDouble() else r.toDouble()
        // RealDash's names in a formula: V the value, B0… its bytes from the first one
        expr?.eval(x) { ref, _ ->
            when {
                ref == "V" -> x
                ref.length > 1 && ref[0] == 'B' && ref.drop(1).all { it.isDigit() } -> d.getOrNull(byte + ref.drop(1).toInt())?.let { (it.toInt() and 0xFF).toDouble() }
                else -> null
            }
        } ?: x
    }

    fun matches(id: Int, isExt: Boolean) = id == canId && isExt == ext

    fun toParam(): RrParam = RrParam(
        id = id, kind = RrParam.Kind.EXT, target = 1, name = name, nameEn = name, desc = "CAN ${idText()}",
        addr = -1, len = 0, bit = -1, units = units, format = if (decimals > 0) "0." + "0".repeat(decimals) else "0",
        storage = "", expr = null, deps = emptyList(), conversions = emptyMap(),
    )

    fun idText() = (if (ext) "%08X" else "%03X").format(canId) + (if (mux >= 0) " #$mux" else "") +
        if (bits) ", биты $startBit|$bitLen" + (if (bigEndian) "@0" else "@1") else ", байт $byte" + (if (len > 1) "–${byte + len - 1}" else "")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("can", canId).put("ext", ext).put("mux", mux)
        .put("byte", byte).put("len", len).put("be", bigEndian).put("signed", signed).put("formula", formula)
        .put("units", units).put("dec", decimals).put("role", role)
        .apply {
            // only when used: a list without them reads the same in an older version
            if (bits) put("sb", startBit).put("bits", bitLen)
            if (muxStart >= 0) put("msb", muxStart).put("mbits", muxLen).put("mbe", muxBe)
        }

    companion object {
        /** The SensorBox roles (but the tank wire's volts) and the Link's fault code. */
        val ROLES = listOf("" to "нет") + BoxRoles.all.filter { it.first.isNotEmpty() && it.first != "fuelLevel" } +
            ("fault" to "Код ошибки Link")

        fun fromJson(o: JSONObject) = CanChannel(
            o.getString("id"), o.optString("name"), o.getInt("can"), o.optBoolean("ext"), o.optInt("mux", -1),
            o.optInt("byte").coerceIn(0, 7), o.optInt("len", 2).let { if (it in setOf(1, 2, 4)) it else 2 }, o.optBoolean("be"),
            o.optBoolean("signed"), o.optString("formula", "x"), o.optString("units"), o.optInt("dec", 1), o.optString("role"),
            startBit = o.optInt("sb", -1).takeIf { it in 0..511 && o.optInt("bits") in 1..64 } ?: -1,
            bitLen = o.optInt("bits").takeIf { it in 1..64 && o.optInt("sb", -1) in 0..511 } ?: 0,
            muxStart = o.optInt("msb", -1).takeIf { it in 0..511 } ?: -1, muxLen = o.optInt("mbits", 8).coerceIn(1, 32),
            muxBe = o.optBoolean("mbe"),
        )
    }
}

object CanChannels {
    @Volatile private var cache: List<CanChannel>? = null
    private val values = ConcurrentHashMap<String, Pair<Double, Long>>()

    fun list(): List<CanChannel> = cache ?: runCatching {
        val a = JSONArray(Repo.settings.canChannels)
        List(a.length()) { CanChannel.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList()).also { cache = it }

    fun save(l: List<CanChannel>) {
        Repo.settings.canChannels = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
        cache = l
        Repo.customVersion.value++
    }

    /** "C1", "C2"…: apart from RomRaider's P/E/S and the own addresses' U. */
    fun newId(): String {
        val used = list().map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "C$it" }.first { it !in used }
    }

    fun put(c: CanChannel) {
        val l = list()
        save(if (l.any { it.id == c.id }) l.map { if (it.id == c.id) c else it } else l + c)
        if (c.id !in Repo.settings.watchIds) Repo.settings.watchIds = Repo.settings.watchIds + c.id
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        values.remove(id)
        Repo.settings.watchIds = Repo.settings.watchIds - id
    }

    /** A frame off the bus: every channel it carries takes its value. */
    fun feed(id: Int, ext: Boolean, d: ByteArray, now: Long = System.currentTimeMillis()) {
        for (c in list()) if (c.matches(id, ext)) c.decode(d)?.let { values[c.id] = it to now }
    }

    /** Values up to [maxAgeMs] old, by channel id. */
    fun values(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 3000): Map<String, Double> =
        values.filter { now - it.value.second <= maxAgeMs }.mapValues { it.value.first }

    fun clearValues() = values.clear()

    /** Fresh values of the channels set to [role], each with its unit (see [SensorBox.roleWithUnit]). */
    fun roleValues(role: String, now: Long = System.currentTimeMillis()): List<Pair<Float, String>> =
        if (role.isEmpty()) emptyList() else roleValues(list(), values(now), role)

    fun roleValues(list: List<CanChannel>, values: Map<String, Double>, role: String): List<Pair<Float, String>> =
        list.filter { it.role == role }.mapNotNull { c -> values[c.id]?.let { it.toFloat() to c.units } }

    /**
     * [rr] with the channels' fresh values in place of the old ones: heard while the diagnostics run (an OpenPort
     * listening to the bus), they are read like the ticked parameters. A channel gone quiet leaves no stale number.
     */
    fun withValues(rr: Map<String, Double>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val l = list()
        if (l.isEmpty()) return rr
        val fresh = values(now)
        val out = rr - l.map { it.id }.toSet() + fresh
        return if (out == rr) rr else out
    }
}
