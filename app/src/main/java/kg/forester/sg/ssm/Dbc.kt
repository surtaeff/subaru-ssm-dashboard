package kg.forester.sg.ssm

/**
 * A reader of Vector's DBC, the subset a dash needs: BO_ messages, SG_ signals (Intel and Motorola, signed, factor
 * and offset, min/max, units, simple multiplexing with M and mN), VAL_ value names, CM_ comments, BA_ cycle times and
 * the bus speed. Everything else (nodes, attribute definitions, value tables, environment variables, extended
 * multiplexing) is passed over. Written after the format as opendbc's files and cantools read it.
 */
object Dbc {
    class BadFile(msg: String) : Exception(msg)

    // \x22 is the quote: a quote in these patterns would end the string for the i18n scan
    private val SG = Regex(
        """^SG_\s+(\w+)\s*(M|m\d*M?)?\s*:\s*(\d+)\s*\|\s*(\d+)\s*@\s*([01])\s*([+-])\s*""" +
            """\(\s*([^,\s]+)\s*,\s*([^)\s]+)\s*\)\s*\[\s*([^|\]\s]*)\s*\|\s*([^\]\s]*)\s*]\s*\x22((?:[^\x22\\]|\\.)*)\x22""",
    )
    private val BO = Regex("""^BO_\s+(\d+)\s+(\w+)\s*:\s*(\d+)""")
    private val STRING = Regex("""\x22((?:[^\x22\\]|\\.)*)\x22""")
    private val RANGE = Regex("""(\d+)\s*-\s*(\d+)""")
    private val VALUE = Regex("""(-?\d+(?:\.\d+)?)\s+\x22((?:[^\x22\\]|\\.)*)\x22""")

    /** Pseudo message of signals not in any frame (Vector's editor writes it). */
    private const val INDEPENDENT = 0xC0000000L

    private class Msg(val id: Int, val ext: Boolean, val name: String, val size: Int) {
        val signals = ArrayList<CanSig>()
        var comment = ""
        var cycle: Int? = null
    }

    fun parse(text: String): CanDb {
        val msgs = LinkedHashMap<Long, Msg>()
        val warnings = ArrayList<String>()
        var kbit: Int? = null
        var current: Msg? = null
        var skipped = false
        val sigComments = HashMap<Pair<Long, String>, String>()
        val sigValues = HashMap<Pair<Long, String>, Map<Long, String>>()
        val types = HashMap<Pair<Long, String>, Int>()
        val mulVal = HashMap<Pair<Long, String>, Pair<String, List<LongRange>>>()
        for (line in statements(text)) {
            val t = line.trim()
            val word = t.split(Regex("[\\s:]"), 2)[0]
            when (word) {
                "BO_" -> {
                    val m = BO.find(t)
                    if (m == null) { warnings += "BO_: не разобрано: " + t.take(60); current = null; continue }
                    val raw = m.groupValues[1].toLong()
                    skipped = raw == INDEPENDENT
                    if (skipped) { current = null; continue }
                    val ext = raw and 0x80000000L != 0L
                    val msg = Msg((raw and 0x1FFFFFFFL).toInt(), ext, m.groupValues[2], m.groupValues[3].toInt())
                    msgs[raw] = msg
                    current = msg
                }
                "SG_" -> {
                    val msg = current ?: continue
                    val m = SG.find(t)
                    if (m == null) { warnings += "${msg.name}: не разобрано: " + t.take(60); continue }
                    val g = m.groupValues
                    val factor = g[7].toDoubleOrNull()
                    val offset = g[8].toDoubleOrNull()
                    val len = g[4].toInt()
                    val muxTag = g[2]
                    val muxN = muxTag.drop(1).removeSuffix("M").toIntOrNull()
                    msg.signals += CanSig(
                        name = g[1], startBit = g[3].toInt(), bitLen = len, motorola = g[5] == "0", signed = g[6] == "-",
                        formula = if (factor != null && offset != null) CanImport.linear(factor, offset) else "x",
                        units = unescape(g[11]), min = g[9].toDoubleOrNull(), max = g[10].toDoubleOrNull(),
                        // "m" alone is a multiplexor too in some files (opendbc's vw_pq)
                        muxValue = if (muxTag.startsWith("m")) muxN else null,
                        isMuxer = muxTag == "M" || muxTag == "m" || (muxTag.length > 1 && muxTag.endsWith("M")),
                        problem = when {
                            len !in 1..64 -> "длина $len бит"
                            factor == null || offset == null -> "множитель не число"
                            else -> null
                        },
                    )
                }
                "CM_" -> {
                    current = null
                    val p = t.removePrefix("CM_").trim().split(Regex("\\s+"), limit = 4)
                    val s = STRING.find(t)?.groupValues?.get(1)?.let { unescape(it) } ?: continue
                    when (p.getOrNull(0)) {
                        "BO_" -> p.getOrNull(1)?.toLongOrNull()?.let { msgs[it]?.comment = s }
                        "SG_" -> { val id = p.getOrNull(1)?.toLongOrNull(); val n = p.getOrNull(2); if (id != null && n != null) sigComments[id to n] = s }
                    }
                }
                "VAL_" -> {
                    current = null
                    val p = t.removePrefix("VAL_").trim().split(Regex("\\s+"), limit = 3)
                    val id = p.getOrNull(0)?.toLongOrNull() ?: continue // a value table of an environment variable
                    val n = p.getOrNull(1) ?: continue
                    val body = p.getOrNull(2).orEmpty()
                    val map = LinkedHashMap<Long, String>()
                    VALUE.findAll(body).forEach { v ->
                        v.groupValues[1].toDoubleOrNull()?.let { map[it.toLong()] = unescape(v.groupValues[2]) }
                    }
                    sigValues[id to n] = map
                }
                "BA_" -> {
                    current = null
                    val name = STRING.find(t)?.groupValues?.get(1) ?: continue
                    val rest = t.substringAfter("\"$name\"").trim().removeSuffix(";").trim().split(Regex("\\s+"))
                    when {
                        name == "GenMsgCycleTime" && rest.getOrNull(0) == "BO_" ->
                            rest.getOrNull(1)?.toLongOrNull()?.let { msgs[it]?.cycle = rest.getOrNull(2)?.toDoubleOrNull()?.toInt()?.takeIf { c -> c > 0 } }
                        (name.equals("Baudrate", true) || name.equals("BusSpeed", true)) && rest.size == 1 ->
                            rest[0].toDoubleOrNull()?.let { b -> kbit = (if (b >= 10000) b / 1000 else b).toInt() }
                    }
                }
                "SIG_VALTYPE_" -> {
                    current = null
                    val p = t.removePrefix("SIG_VALTYPE_").trim().split(Regex("[\\s:;]+"))
                    val id = p.getOrNull(0)?.toLongOrNull() ?: continue
                    p.getOrNull(2)?.toIntOrNull()?.let { types[id to p[1]] = it }
                }
                // SG_MUL_VAL_ 1152 MO5_Abgastyp MO5_Mp_Code 3-3, 5-7;
                "SG_MUL_VAL_" -> {
                    current = null
                    val p = t.removePrefix("SG_MUL_VAL_").trim().split(Regex("\\s+"), limit = 4)
                    val id = p.getOrNull(0)?.toLongOrNull() ?: continue
                    val sig = p.getOrNull(1) ?: continue
                    val muxer = p.getOrNull(2) ?: continue
                    val ranges = RANGE.findAll(p.getOrNull(3).orEmpty()).map { it.groupValues[1].toLong()..it.groupValues[2].toLong() }.toList()
                    if (ranges.isNotEmpty()) mulVal[id to sig] = muxer to ranges
                }
                "" -> {}
                else -> current = null
            }
        }
        if (msgs.isEmpty()) throw BadFile(if ("<" in text.take(200)) "это не DBC" else "в файле нет сообщений BO_")
        val out = msgs.map { (raw, m) ->
            CanMsg(
                m.id, m.ext, m.name, m.size,
                m.signals.map { s ->
                    val k = raw to s.name
                    val vt = types[k] ?: 0
                    val mv = mulVal[k]
                    s.copy(
                        comment = sigComments[k].orEmpty(), values = sigValues[k].orEmpty(),
                        valueType = if (vt in 1..2) vt else 0, muxOf = mv?.first, muxRanges = mv?.second.orEmpty(),
                        problem = s.problem ?: when {
                            vt == 1 && s.bitLen != 32 -> "float: нужно 32 бит, в файле ${s.bitLen}"
                            vt == 2 && s.bitLen != 64 -> "double: нужно 64 бит, в файле ${s.bitLen}"
                            vt !in 0..2 -> "тип значения $vt"
                            else -> null
                        },
                    )
                },
                m.cycle, m.comment,
            )
        }.map { m ->
            // a multiplexor named in SG_MUL_VAL_ that the message doesn't have, or a loop of them
            m.copy(signals = m.signals.map { s -> if (s.problem == null && CanImport.muxChain(m, s) == null) s.copy(problem = "мультиплексор не найден") else s })
        }
        return CanDb(out, kbit, warnings)
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * The file in statements: a line each, but a quoted string (a comment over several lines) keeps its line breaks
     * inside its statement.
     */
    internal fun statements(text: String): List<String> {
        val out = ArrayList<String>()
        val b = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '\\' && i + 1 < text.length -> { b.append(c).append(text[i + 1]); i += 2; continue }
                c == '"' -> { quoted = !quoted; b.append(c) }
                !quoted && (c == '\n' || c == '\r') -> { if (b.isNotBlank()) out += b.toString(); b.clear() }
                else -> b.append(c)
            }
            i++
        }
        if (b.isNotBlank()) out += b.toString()
        return out
    }
}
