package kg.forester.sg.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * A gauge design made in the designer: a [base] design plus the user's changes (null = as in the base).
 * Colours are ARGB longs. Used as style "custom:<id>".
 */
data class CustomGauge(
    val id: Int,
    val name: String,
    val base: String = "neon",
    val face: Long? = null,
    val scale: Long? = null,      // ticks, numbers and the big value
    val accent: Long? = null,     // lit arc / LED blocks / read-out digits
    val glow: Boolean? = null,
    val needle: Long? = null,
    val rim: Long? = null,
    val ticks: String? = null,    // TickShape name
    val pattern: String? = null,  // FacePattern name
    val patternColor: Long? = null,
    val readout: String? = null,  // "none", "lcd", "frame"
    val image: String? = null,    // GaugeImages name
    val numberPct: Int? = null,   // size of the numbers, percent; null = as in the base
) {
    val styleId get() = "custom:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("base", base)
        face?.let { put("face", it) }; scale?.let { put("scale", it) }; accent?.let { put("accent", it) }
        glow?.let { put("glow", it) }; needle?.let { put("needle", it) }; rim?.let { put("rim", it) }
        ticks?.let { put("ticks", it) }; pattern?.let { put("pattern", it) }; patternColor?.let { put("patternColor", it) }
        readout?.let { put("readout", it) }; image?.let { put("image", it) }; numberPct?.let { put("numberPct", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): CustomGauge {
            fun long(k: String) = if (o.has(k)) o.getLong(k) else null
            fun str(k: String) = if (o.has(k)) o.getString(k) else null
            return CustomGauge(
                o.getInt("id"), o.optString("name", "Мой дизайн"), o.optString("base", "neon"),
                long("face"), long("scale"), long("accent"), if (o.has("glow")) o.getBoolean("glow") else null,
                long("needle"), long("rim"), str("ticks"), str("pattern"), long("patternColor"), str("readout"), str("image"),
                if (o.has("numberPct")) o.getInt("numberPct") else null,
            )
        }
    }
}

/**
 * The user's own gauge designs. [list] is Compose state, so anything drawn from a "custom:" style redraws
 * as soon as the design is edited.
 */
object CustomGauges {
    var list by mutableStateOf(listOf<CustomGauge>())
        private set
    private lateinit var appCtx: Context

    private fun prefs() = appCtx.getSharedPreferences("custom_gauges", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::appCtx.isInitialized) return
        appCtx = ctx.applicationContext
        list = runCatching {
            val a = JSONArray(prefs().getString("list", "[]"))
            (0 until a.length()).map { CustomGauge.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun byStyleId(styleId: String) = styleId.removePrefix("custom:").toIntOrNull()?.let { id -> list.firstOrNull { it.id == id } }

    fun newDraft(base: String) = CustomGauge((list.maxOfOrNull { it.id } ?: 0) + 1, "Мой дизайн ${list.size + 1}", base)

    /** Adds or replaces [g]. */
    fun save(g: CustomGauge) {
        list = list.filter { it.id != g.id } + g
        persist()
    }

    fun delete(id: Int) {
        list = list.filter { it.id != id }
        persist()
    }

    private fun persist() = prefs().edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
}
