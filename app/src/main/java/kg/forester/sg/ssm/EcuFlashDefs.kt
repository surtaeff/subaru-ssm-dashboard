package kg.forester.sg.ssm

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * The driver's own ECUFlash ROM definition (a SubaruDefs-style <rom> file, its own ROM or a custom one with the tables
 * moved): where the ROM keeps the axes of «Обучение», the same three tables tools/romraider/build_learning.py takes
 * from SubaruDefs for the bundled ones. A file of one ROM usually leaves the size and scaling to its base
 * (32BITBASE / 16BITBASE): what it leaves out is filled in when the table is read (see [LearnedValues]).
 */
object EcuFlashDefs {
    /** One axis as the file gives it: the address, and the count, storage and multiplier when it has them. */
    data class Axis(val addr: Int, val count: Int?, val storage: String?, val mul: Double?)

    /** The axes of one ROM, by its ECU id (10 hex digits); [bits] 32, 16 or 0 when the file does not include a base. */
    data class Rom(val ecuId: String, val cal: String, val bits: Int, val af: Axis?, val rpm: Axis?, val load: Axis?) {
        val axes: Int get() = listOfNotNull(af, rpm, load).size
    }

    private val NAMES = mapOf(
        "af" to setOf("A/F Learning #1 Airflow Ranges"),
        "rpm" to setOf("Fine Correction Rows (RPM)", "Fine Correction Rows (RPM)_"),
        "load" to setOf("Fine Correction Columns (Load)", "Fine Correction Columns (Load)_"),
    )

    /** True for an ECUFlash ROM definition (not a RomRaider logger file). */
    fun looksLike(text: String): Boolean {
        val head = text.take(4000)
        return Regex("""<rom[\s>]""").containsMatchIn(head) && "<romid" in head && "<logger" !in head
    }

    /** The ROM of [text], null when it is not one or has no ECU id or none of the axes. */
    fun parse(text: String): Rom? = runCatching {
        val xp = XmlPullParserFactory.newInstance().newPullParser()
        runCatching { xp.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        xp.setInput(StringReader(text.removePrefix("﻿").replace(Regex("""<\?xml\s[^>]*\?>"""), "")))
        var ecuId = ""; var xmlId = ""; var include = ""
        val scalings = HashMap<String, Pair<String, String>>() // name → storage, toexpr
        class T(val addr: String?, val elements: Int?, val scaling: String?)
        val tables = HashMap<String, T>()
        var depth = 0
        var romDepth = -1
        var inRomId = false
        while (true) {
            val ev = xp.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG) { if (xp.name == "romid") inRomId = false; depth--; continue }
            if (ev != XmlPullParser.START_TAG) continue
            depth++
            when (xp.name) {
                "rom" -> if (romDepth < 0) romDepth = depth
                "romid" -> inRomId = true
                "ecuid" -> if (inRomId) { ecuId = xp.nextText().trim().uppercase(); depth-- }
                "xmlid" -> if (inRomId) { xmlId = xp.nextText().trim(); depth-- }
                "include" -> { include = xp.nextText().trim(); depth-- }
                "scaling" -> xp.getAttributeValue(null, "name")?.let { scalings[it] = xp.getAttributeValue(null, "storagetype").orEmpty() to xp.getAttributeValue(null, "toexpr").orEmpty() }
                "table" -> {
                    val name = xp.getAttributeValue(null, "name")
                    // the axes are tables of their own at the top of the ROM
                    if (name != null && depth == romDepth + 1 && NAMES.values.any { name in it }) {
                        tables[name] = T(xp.getAttributeValue(null, "address"), xp.getAttributeValue(null, "elements")?.toIntOrNull(),
                            xp.getAttributeValue(null, "scaling"))
                    }
                }
            }
        }
        if (!Regex("[0-9A-F]{10}").matches(ecuId)) return null
        fun axis(key: String): Axis? {
            val t = NAMES.getValue(key).firstNotNullOfOrNull { tables[it] } ?: return null
            val addr = t.addr?.removePrefix("0x")?.toIntOrNull(16) ?: return null
            val sc = t.scaling?.let { scalings[it] }
            val mul = sc?.second?.let { Regex("""\s*x\s*(?:\*\s*([0-9.eE+-]+))?\s*""").matchEntire(it)?.groupValues?.get(1)?.ifEmpty { "1" }?.toDoubleOrNull() }
            val storage = sc?.first?.takeIf { it in setOf("float", "uint8", "uint16") }
            return Axis(addr, t.elements, storage, if (storage != null) mul else null)
        }
        val bits = if ("32BIT" in include.uppercase()) 32 else if ("16BIT" in include.uppercase()) 16 else 0
        Rom(ecuId, xmlId.ifEmpty { ecuId }, bits, axis("af"), axis("rpm"), axis("load")).takeIf { it.axes > 0 }
    }.getOrNull()

    /**
     * How many leading values of an axis read without its count are its own: a rising run within [lo]..[hi] (the
     * table after an axis is no longer rising, or out of range). Null under two.
     */
    fun risingRun(values: List<Double>, lo: Double, hi: Double): Int? {
        var n = 0
        for (i in values.indices) {
            val v = values[i]
            if (v.isNaN() || v < lo || v > hi || (i > 0 && v <= values[i - 1])) break
            n++
        }
        return n.takeIf { it >= 2 }
    }
}
