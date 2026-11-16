package kg.forester.sg.video

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant JSON reads (port of FreeLite's VideoJson.java): the sites send numbers as numbers or strings, and null as JSON
 * null. Never "null" for a JSON null (org.json's optString gives that).
 */
internal object VideoJson {
    fun str(o: JSONObject?, k: String): String? {
        val v = o?.opt(k) ?: return null
        if (v == JSONObject.NULL || v is JSONObject || v is JSONArray) return null
        return v.toString().takeIf { it.isNotBlank() }
    }

    fun int(o: JSONObject?, k: String): Int {
        val s = str(o, k)?.trim() ?: return 0
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt() ?: 0
    }

    fun dbl(o: JSONObject?, k: String): Double = str(o, k)?.replace(',', '.')?.trim()?.toDoubleOrNull() ?: 0.0

    fun bool(o: JSONObject?, k: String): Boolean = str(o, k).let { it == "true" || it == "1" }

    fun strings(o: JSONObject?, k: String): List<String> {
        val a = o?.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.opt(i)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } }
    }
}
