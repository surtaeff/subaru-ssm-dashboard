package kg.forester.sg.ssm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the ECU has learned, as RomRaider's «Learning Table Values» shows it: IAM, the stored A/F trims by airflow
 * range and the whole fine learning knock correction (FLKC) table by RPM and load.
 *
 * The values sit in RAM at the addresses the RomRaider logger knows for the ROM (IAM, A/F Learning #1/#2 A–D).
 * The FLKC table follows IAM: 0x14 bytes on in a 32-bit ECU (floats, every second one), 2 bytes on in a 16-bit
 * one (a byte per cell); RomRaider finds it the same way. The axes (airflow limits, RPM rows, load columns) are
 * in the ROM, at the addresses of assets/learning_axes.json (from SubaruDefs, see tools/romraider/build_learning.py),
 * or of the driver's own ECUFlash definition for the ROM ([EcuFlashDefs], first: a custom ROM may have them moved).
 */
object LearnedValues {
    data class Result(
        val romId: String,
        val cal: String?,
        val timeMs: Long,
        /** IAM as a multiplier, 1.0 = full advance. */
        val iam: Double?,
        /** Lower airflow limit of the ranges A, B, C, D (g/s), or null when the ROM's axes are unknown. */
        val afStarts: List<Double>?,
        /** Stored trims A–D (%), one list per bank. */
        val afBanks: List<List<Double?>>,
        /** Lower RPM limit of each FLKC row and lower load limit (g/rev) of each column. */
        val rpmStarts: List<Double>?,
        val loadStarts: List<Double>?,
        /** FLKC degrees, rows × columns; null when the table can't be placed. */
        val flkc: List<List<Double>>?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("rom", romId); put("cal", cal ?: ""); put("t", timeMs)
            iam?.let { put("iam", it) }
            afStarts?.let { put("afStarts", JSONArray(it)) }
            put("af", JSONArray(afBanks.map { b -> JSONArray(b.map { it ?: JSONObject.NULL }) }))
            rpmStarts?.let { put("rpm", JSONArray(it)) }
            loadStarts?.let { put("load", JSONArray(it)) }
            flkc?.let { put("flkc", JSONArray(it.map { r -> JSONArray(r) })) }
        }

        companion object {
            fun fromJson(o: JSONObject): Result {
                fun nums(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getDouble(i) } }
                val af = o.optJSONArray("af")
                return Result(
                    romId = o.getString("rom"), cal = o.optString("cal").ifEmpty { null }, timeMs = o.getLong("t"),
                    iam = if (o.has("iam")) o.getDouble("iam") else null,
                    afStarts = nums(o.optJSONArray("afStarts")),
                    afBanks = if (af == null) emptyList() else List(af.length()) { i ->
                        val b = af.getJSONArray(i); List(b.length()) { j -> if (b.isNull(j)) null else b.getDouble(j) }
                    },
                    rpmStarts = nums(o.optJSONArray("rpm")), loadStarts = nums(o.optJSONArray("load")),
                    flkc = o.optJSONArray("flkc")?.let { t -> List(t.length()) { i -> nums(t.getJSONArray(i))!! } },
                )
            }
        }
    }

    /** [auto]: the count is not known, [count] values are read and the rising ones at the start kept. */
    internal data class Axis(val addr: Int, val count: Int, val storage: String, val mul: Double, val auto: Boolean = false) {
        val size get() = when (storage) { "float" -> 4; "uint16" -> 2; else -> 1 }
        val addresses get() = IntArray(count * size) { addr + it }
        fun values(b: ByteArray): List<Double> = List(count) { i ->
            var u = 0L
            for (k in 0 until size) u = (u shl 8) or (b[i * size + k].toLong() and 0xFF)
            (if (storage == "float") java.lang.Float.intBitsToFloat(u.toInt()).toDouble() else u.toDouble()) * mul
        }
    }

    internal data class Axes(val cal: String, val bits: Int, val af: Axis?, val rpm: Axis?, val load: Axis?)

    @Volatile private var axes: Map<String, Axes>? = null

    private fun axesFor(ctx: Context, romId: String): Axes? {
        val all = axes ?: synchronized(this) {
            axes ?: runCatching {
                val roms = JSONObject(ctx.assets.open("learning_axes.json").bufferedReader().readText()).getJSONObject("roms")
                roms.keys().asSequence().associateWith { id ->
                    val e = roms.getJSONObject(id)
                    fun axis(k: String) = e.optJSONArray(k)?.let { Axis(it.getInt(0), it.getInt(1), it.getString(2), it.getDouble(3)) }
                    Axes(e.getString("cal"), e.getInt("bits"), axis("af"), axis("rpm"), axis("load"))
                }
            }.getOrDefault(emptyMap()).also { axes = it }
        }
        return all[romId.uppercase()]
    }

    /** Made-up but typical values for the demo mode: a healthy engine with a little knock learned under load. */
    fun demo(): Result = Result(
        romId = "DEMO", cal = "демо", timeMs = System.currentTimeMillis(), iam = 1.0,
        afStarts = listOf(0.0, 5.6, 9.0, 40.0), afBanks = listOf(listOf(-1.95, -0.49, 0.98, 1.46)),
        rpmStarts = listOf(0.0, 2000.0, 2800.0, 3600.0, 4400.0, 5200.0, 6000.0),
        loadStarts = listOf(0.0, 1.15, 1.5, 1.9, 4.0),
        flkc = List(7) { r -> List(5) { c -> if (r == 3 && c == 3) -1.05 else if ((r == 3 || r == 4) && c >= 2) -0.35 else 0.0 } },
    )

    /** True when RomRaider knows where this ROM keeps IAM, so there is something to read. */
    fun supported(ctx: Context, init: EcuInit): Boolean =
        RomRaider.available(ctx, init, false).any { it.id == "E31" || it.id == "E1" }

    /** Reads everything over [client] (blocking, a few seconds over an ELM327); throws with a message for the driver. */
    fun read(ctx: Context, client: SsmClient, init: EcuInit): Result {
        val params = RomRaider.available(ctx, init, false).filter { it.kind == RrParam.Kind.EXT }.associateBy { it.id }
        val own = kg.forester.sg.data.EcuDefs.romAxes(ctx)[init.romId.uppercase()]
        return read(client, init, params, own?.let { ownAxes(it, if ("E31" in params) 32 else 16) } ?: axesFor(ctx, init.romId))
    }

    /**
     * The axes of the driver's own ECUFlash file, what it leaves to its base filled in as the bases have it: floats on a
     * 32-bit ECU, on a 16-bit one 16-bit numbers with SubaruDefs' multipliers; without a count, read up to 8.
     */
    internal fun ownAxes(r: EcuFlashDefs.Rom, ecuBits: Int): Axes {
        val bits = if (r.bits != 0) r.bits else ecuBits
        fun axis(a: EcuFlashDefs.Axis?, mul16: Double): Axis? = a?.let {
            val storage = it.storage ?: if (bits == 32) "float" else "uint16"
            val mul = it.mul ?: if (storage == "float") 1.0 else mul16
            Axis(it.addr, it.count ?: 8, storage, mul, auto = it.count == null)
        }
        return Axes(r.cal, bits, axis(r.af, 0.004577637), axis(r.rpm, 0.1953125), axis(r.load, 6.103515625e-05))
    }

    /** [params]: the ROM's extended parameters by id; [ax]: its axes, null when unknown. */
    internal fun read(client: SsmClient, init: EcuInit, params: Map<String, RrParam>, ax: Axes?): Result {
        fun value(id: String): Double? {
            val p = params[id] ?: return null
            val raw = client.read(p.addresses) ?: throw IllegalStateException("ЭБУ не ответил")
            return p.expr?.eval(p.raw(raw)) { _, _ -> null }
        }
        val iam32 = params["E31"]
        val iam16 = params["E1"]
        if (iam32 == null && iam16 == null) throw IllegalStateException(
            "Для прошивки ${init.romId} в базе RomRaider нет адресов обучения, прочитать таблицу не получится")
        val iam = if (iam32 != null) value("E31") else value("E1")?.let { it / 16.0 } // 16-bit: 16 = full advance
        val banks = listOf(listOf("E44", "E45", "E46", "E47"), listOf("E13", "E14", "E15", "E16"), listOf("E62", "E63", "E64", "E65"))
            .filter { ids -> ids.all { it in params } }
            // a 32-bit ROM may list the 16-bit ones too: keep the first bank #1 found and bank #2
            .let { found -> listOfNotNull(found.firstOrNull { it[0] != "E62" }, found.firstOrNull { it[0] == "E62" }) }
            .map { ids -> ids.map { value(it) } }

        // an axis without its count: the rising run at its start, within what such an axis can be
        fun readAxis(a: Axis?, hi: Double): List<Double>? = a?.let { x ->
            client.read(x.addresses)?.let(x::values)?.let { v -> if (!x.auto) v else EcuFlashDefs.risingRun(v, 0.0, hi)?.let { v.take(it) } }
        }
        val afLimits = readAxis(ax?.af, 1000.0)
        val rpmLimits = readAxis(ax?.rpm, 12000.0)
        val loadLimits = readAxis(ax?.load, 10.0)

        var flkc: List<List<Double>>? = null
        if (rpmLimits != null && loadLimits != null) {
            val rows = rpmLimits.size + 1
            val cols = loadLimits.size + 1
            val n = rows * cols
            flkc = if (iam32 != null) {
                // floats, each followed by its check copy: every 8 bytes
                val base = iam32.addr + 0x14
                val addrs = IntArray(n * 4) { base + (it / 4) * 8 + it % 4 }
                val raw = client.read(addrs) ?: throw IllegalStateException("ЭБУ не ответил")
                List(n) { i ->
                    var u = 0
                    for (k in 0 until 4) u = (u shl 8) or (raw[i * 4 + k].toInt() and 0xFF)
                    java.lang.Float.intBitsToFloat(u).toDouble()
                }
            } else {
                val base = iam16!!.addr + 2
                val raw = client.read(IntArray(n) { base + it }) ?: throw IllegalStateException("ЭБУ не ответил")
                val conv = params["E12"]?.expr
                List(n) { i ->
                    val x = (raw[i].toInt() and 0xFF).toDouble()
                    conv?.eval(x) { _, _ -> null } ?: (x * 0.3515625 - 45)
                }
            }.chunked(cols)
        }
        val starts = { l: List<Double>? -> l?.let { listOf(0.0) + it } }
        return Result(
            romId = init.romId, cal = ax?.cal, timeMs = System.currentTimeMillis(), iam = iam,
            afStarts = starts(afLimits), afBanks = banks,
            rpmStarts = starts(rpmLimits), loadStarts = starts(loadLimits), flkc = flkc,
        )
    }
}
