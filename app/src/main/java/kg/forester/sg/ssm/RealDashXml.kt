package kg.forester.sg.ssm

import kg.forester.sg.data.CanBits
import kg.forester.sg.data.CanChannel
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * A reader of RealDash's CAN description (XML version 2, janimm/RealDash-extras, public domain): frames with their
 * values by offset/length or startbit/bitcount, endianness, signed, units and the conversion formula. A conversion
 * over V and the value's bytes B0… (or A, B… of conversionABC) is kept as a formula; "V>>n" and "&mask" become bit
 * fields; anything else of RealDash's (functions, other inputs, comparisons) marks the value as not readable here.
 */
object RealDashXml {
    class BadFile(msg: String) : Exception(msg)

    /** Names of RealDash's inputs (targetId) the bundled files use; others take the comment after the value. */
    private val TARGETS = mapOf(
        0 to "AFR 1", 1 to "AFR 2", 6 to "Acceleration X", 7 to "Acceleration Y", 8 to "Acceleration Z",
        11 to "Barometric pressure", 12 to "Battery voltage", 14 to "Coolant temperature", 17 to "EGO correction 1",
        18 to "EGO correction 2", 23 to "Ignition dwell", 27 to "Intake air temperature", 28 to "Knock retard",
        29 to "Knock level", 31 to "MAP", 35 to "Injector pulse width 1", 36 to "Injector pulse width 2", 37 to "RPM",
        38 to "Ignition advance", 42 to "TPS", 43 to "Total fuel correction", 64 to "Vehicle speed",
        66 to "Error code count", 81 to "Vehicle speed 1", 99 to "Vehicle speed 2", 102 to "Long term trim 1",
        104 to "Long term trim 2", 105 to "Error code", 119 to "Injector duty 1", 120 to "Injector duty 2",
        138 to "Transmission oil temperature", 151 to "Oil pressure", 152 to "Oil temperature", 159 to "Reverse light",
        164 to "Parking brake", 179 to "Traction power limit", 200 to "Gear", 202 to "Fuel pressure", 230 to "Brake pedal",
        231 to "Clutch pedal", 254 to "Lambda 1", 255 to "Lambda 2", 256 to "Lambda target 1", 270 to "Boost target",
        271 to "RPM limit", 335 to "AC", 394 to "Launch control active", 397 to "Overrun active", 406 to "Fuel cut",
        489 to "Neutral switch", 490 to "Fuel flow", 492 to "Intake cam 1", 493 to "Exhaust cam 1", 494 to "Intake cam 2",
        495 to "Exhaust cam 2", 496 to "Intake cam target", 497 to "Exhaust cam target", 499 to "Fuel temperature",
        759 to "Coolant pressure",
    ) + (0..7).associate { 106 + it to "EGT ${it + 1}" }

    private class Val(val a: Map<String, String>, val line: Int) { var label: String? = null }
    private class Frame(val a: Map<String, String>, val base: Long) { val values = ArrayList<Val>() }

    fun parse(text: String): CanDb {
        val xp = XmlPullParserFactory.newInstance().newPullParser()
        runCatching { xp.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        xp.setInput(StringReader(text.trimStart('\uFEFF')))
        val frames = ArrayList<Frame>()
        var root = false
        var base = 0L
        var frame: Frame? = null
        var last: Val? = null
        try {
            while (true) {
                val ev = xp.nextToken()
                if (ev == XmlPullParser.END_DOCUMENT) break
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val a = (0 until xp.attributeCount).associate { xp.getAttributeName(it).lowercase() to xp.getAttributeValue(it).trim() }
                        when (xp.name.lowercase()) {
                            "realdashcan" -> root = true
                            "frames" -> base = a["baseid"]?.let { number(it) } ?: 0L
                            "frame" -> frame = Frame(a, base).also { frames += it }
                            "value" -> frame?.let { f -> Val(a, xp.lineNumber).also { f.values += it; last = it } }
                        }
                    }
                    XmlPullParser.END_TAG -> when (xp.name.lowercase()) {
                        "frame" -> frame = null
                        "frames" -> base = 0L
                    }
                    // <value targetId="37" …></value> <!-- RPM -->: the comment names it
                    XmlPullParser.COMMENT -> last?.let { v -> if (v.line == xp.lineNumber && v.label == null) v.label = xp.text.trim() }
                }
            }
        } catch (e: XmlPullParserException) {
            throw BadFile("ошибка в XML, строка ${e.lineNumber}")
        }
        if (!root) throw BadFile("это не описание RealDash CAN (нет RealDashCAN)")
        val msgs = frames.mapNotNull { message(it) }
        if (msgs.isEmpty()) throw BadFile("в файле нет кадров frame")
        return CanDb(msgs)
    }

    private fun number(s: String): Long? = s.trim().let { t ->
        if (t.startsWith("0x", true)) t.drop(2).toLongOrNull(16) else t.toLongOrNull()
    }

    private fun bool(s: String?) = s.equals("true", true) || s == "1"

    private fun message(f: Frame): CanMsg? {
        // "0x3E8:5533,0,2": a running number in the data (low byte first) tells the frames of one id apart
        val idText = f.a["id"] ?: return null
        val canId = number(idText.substringBefore(':'))?.plus(f.base) ?: return null
        val comp = idText.substringAfter(':', "").split(',').map { it.trim() }
        val compValue = comp.getOrNull(0)?.let { number(it) }?.toInt()
        val muxer = if (compValue != null) {
            val off = comp.getOrNull(1)?.toIntOrNull() ?: 0
            val len = comp.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            CanSig("composite id", off * 8, len * 8, motorola = false, signed = false, isMuxer = true)
        } else null
        val writes = f.a["writeinterval"] != null
        val size = f.a["size"]?.toIntOrNull()?.coerceIn(1, 64) ?: 8
        val sigs = f.values.map { v -> signal(f, v, writes).let { if (compValue != null) it.copy(muxValue = compValue) else it } }
        val ext = canId > 0x7FF
        return CanMsg(
            canId.toInt(), ext, if (compValue != null) "0x%X:%d".format(canId, compValue) else "0x%03X".format(canId), size,
            listOfNotNull(muxer) + sigs,
        )
    }

    private fun signal(f: Frame, v: Val, writes: Boolean): CanSig {
        val a = v.a
        val tid = a["targetid"]?.toIntOrNull()
        val rawName = a["name"]?.takeIf { it.isNotBlank() }
        val name = rawName?.substringAfter(": ", rawName)?.trim()
            ?: tid?.let { TARGETS[it] } ?: v.label?.takeIf { it.length in 1..60 } ?: tid?.let { "targetId $it" } ?: "?"
        val big = (a["endianness"] ?: a["endianess"] ?: f.a["endianness"] ?: f.a["endianess"]).equals("big", true)
        val signed = bool(a["signed"] ?: f.a["signed"])
        val offset = a["offset"]?.toIntOrNull() ?: 0
        val length = a["length"]?.toIntOrNull() ?: 1
        val startbit = a["startbit"]?.toIntOrNull()
        val bitcount = a["bitcount"]?.toIntOrNull()
        val unitsRaw = a["units"].orEmpty()
        val isBit = unitsRaw.equals("bit", true)
        val enum = enumValues(a["enum"])
        val comment = listOfNotNull(rawName?.takeIf { it != name }, v.label?.takeIf { it != name }).joinToString(" · ")

        fun bad(why: String) = CanSig(name, offset * 8, 8, false, signed, units = unitsRaw, comment = comment, problem = why)

        if (writes) return bad("этот кадр отправляет сам дашборд (writeInterval)")
        // IEEE 754: 4 bytes float, 8 bytes double, as RealDash reads them (whole bytes, the frame's byte order)
        val vt = when { bool(a["double"]) -> CanChannel.FLOAT64; bool(a["float"]) -> CanChannel.FLOAT32; else -> CanChannel.INT }
        if (vt != CanChannel.INT) {
            val need = if (vt == CanChannel.FLOAT64) 8 else 4
            if (startbit != null || bitcount != null || a["length"]?.toIntOrNull() != need) return bad("float/double: длина должна быть $need байт")
            val abc = a["conversionabc"]
            val conv = conversion(abc ?: a["conversion"], abc != null)
            if (conv == null || conv.shift != 0 || conv.width != null) return bad("формула RealDash не поддерживается: " + (abc ?: a["conversion"]).orEmpty())
            val (u, formula) = CanImport.normalize(unitsRaw, conv.formula)
            return CanSig(
                name, if (big) CanBits.valueBit(offset, need, true, need * 8 - 1) else offset * 8, need * 8, big, false, formula, u,
                comment = comment, valueType = vt, byte = if (need == 4) offset else -1, len = if (need == 4) 4 else 0,
            )
        }
        if (length !in 1..8 && bitcount == null) return bad("длина $length байт")
        val abc = a["conversionabc"]
        val conv = conversion(abc ?: a["conversion"], abc != null) ?: return bad("формула RealDash не поддерживается: " + (abc ?: a["conversion"]).orEmpty())

        // the value's own bits: j = 0 its lowest
        val width = bitcount ?: (length * 8)
        val intel = startbit != null || !big
        fun pos(j: Int) = if (startbit != null || bitcount != null) offset * 8 + (startbit ?: 0) + j else CanBits.valueBit(offset, length, big, j)
        val shift = conv.shift
        val k = conv.width ?: if (isBit && bitcount == null) 1 else width - shift
        if (k < 1 || shift + k > width) return bad("сдвиг за пределами значения")
        val wholeBytes = startbit == null && bitcount == null && shift == 0 && k == width && length in setOf(1, 2, 4)
        val (u, formula) = CanImport.normalize(unitsRaw, conv.formula)
        return CanSig(
            name = name,
            startBit = if (intel || k == 1) pos(shift) else pos(shift + k - 1),
            bitLen = k, motorola = !intel && k > 1, signed = signed && k > 1, formula = formula, units = u,
            min = a["rangemin"]?.toDoubleOrNull(), max = a["rangemax"]?.toDoubleOrNull(), comment = comment, values = enum,
            byte = if (wholeBytes) offset else -1, len = if (wholeBytes) length else 0,
        )
    }

    /** RealDash's enum="72:P,24:R,0~10:x,#:err": the plain numbers only. */
    private fun enumValues(s: String?): Map<Long, String> = s.orEmpty().split(',').mapNotNull { p ->
        val k = p.substringBefore(':').trim().toLongOrNull() ?: return@mapNotNull null
        k to p.substringAfter(':', "").trim()
    }.toMap()

    /** A conversion as a formula over x, with the bit field it picks out of the value ("V>>3", "V&15"). */
    class Conv(val formula: String, val shift: Int = 0, val width: Int? = null)

    private val SHIFT_MASK = Regex("""^\(?\s*V\s*>>\s*(\d+)\s*\)?\s*(?:&\s*(0x[0-9a-fA-F]+|\d+))?$""")
    private val MASK = Regex("""^V\s*&\s*(0x[0-9a-fA-F]+|\d+)$""")
    private val LEFT = Regex("""V\s*<<\s*(\d+)""")
    private val WORD = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val ABC = Regex("""\b([A-Z]{1,2})\b""")

    /** RealDash's conversion (or conversionABC) as ours, null when it uses what a formula here has not. */
    fun conversion(src: String?, abc: Boolean = false): Conv? {
        // stray invisible characters are in some files ("V/‭1.59375‬-10")
        var s = src.orEmpty().filter { it.code in 32..126 }.trim()
        if (abc) s = ABC.replace(s) { m ->
            val w = m.groupValues[1]
            "B" + (if (w.length == 1) w[0] - 'A' else (w[0] - 'A' + 1) * 26 + (w[1] - 'A'))
        }
        if (s.isEmpty() || s == "V") return Conv("x")
        SHIFT_MASK.find(s)?.let { m ->
            val mask = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { maskWidth(it) ?: return null }
            return Conv("x", m.groupValues[1].toInt(), mask)
        }
        MASK.find(s)?.let { m -> return Conv("x", 0, maskWidth(m.groupValues[1]) ?: return null) }
        s = LEFT.replace(s) { m -> "V*" + (1L shl m.groupValues[1].toInt().coerceIn(0, 62)) }
        if (s.any { it !in "0123456789.+-*/() " && !it.isLetter() && it != '_' }) return null
        val f = WORD.replace(s) { m -> if (m.value == "V") "x" else m.value }
        if (WORD.findAll(f).any { it.value != "x" && !Regex("B\\d+").matches(it.value) }) return null
        RrExpr.parse(f) ?: return null
        return Conv(f)
    }

    private fun maskWidth(s: String): Int? {
        val m = (if (s.startsWith("0x", true)) s.drop(2).toLongOrNull(16) else s.toLongOrNull()) ?: return null
        if (m <= 0 || (m and (m + 1)) != 0L) return null
        return java.lang.Long.bitCount(m)
    }
}
