package kg.forester.sg.ssm

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * The driver's own RomRaider logger definition: a whole logger.xml (logger_METRIC_EN_v*.xml) or a piece of one with
 * only <parameter> / <ecuparam> / <switch> elements, the way people share addresses for one ECU. Read into the same
 * [RrParam]s as the bundled base, with tools/romraider/build_defs.py's choices: the SSM protocol only, a metric
 * conversion first. [merge] then lays it over the base: a parameter with the base's id replaces it (a formula that is
 * wrong for some ECU gets fixed so), an extended one adds or replaces its address per ROM.
 */
object RomRaiderXml {
    class Addr(val addr: Int, val len: Int, val bit: Int)

    /** An extended parameter: [param] without an address, and where it sits on each ROM it lists. */
    class Ext(val param: RrParam, val roms: Map<String, Addr>)

    class Defs(val params: List<RrParam>, val ext: List<Ext>, val version: String) {
        val size: Int get() = params.size + ext.size
    }

    /** Why a file is not a definition, in words for the driver. */
    class BadFile(message: String) : Exception(message)

    private val IMPERIAL = setOf("psi", "mmhg", "mph", "f", "inhg")
    private val EXPR_FIX = mapOf("(x*.002542923463176)-.1999836" to "(x*.002542923463176)-19.99836")

    private class Conv(val units: String, val expr: String, val format: String, val storage: String)

    /** [bytes] as the file came: UTF-8 with or without a BOM, UTF-16, or what its declaration says. */
    fun parse(bytes: ByteArray): Defs = parse(text(bytes))

    fun text(bytes: ByteArray): String {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            at(0) == 0xFE && at(1) == 0xFF -> String(bytes, Charsets.UTF_16)
            at(0) == 0xFF && at(1) == 0xFE -> String(bytes, Charsets.UTF_16)
            else -> {
                val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
                val enc = Regex("""<\?xml[^>]*encoding\s*=\s*["']([\w.-]+)["']""").find(head)?.groupValues?.get(1)
                val cs = enc?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
                String(bytes, cs)
            }
        }
    }

    fun parse(text: String): Defs {
        val xp = XmlPullParserFactory.newInstance().newPullParser()
        // hand-made snippets have a bare "&" or an HTML entity in a description more often than not
        runCatching { xp.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        // one root around everything: a snippet is a list of elements, not a document
        xp.setInput(StringReader("<defs>" + body(text) + "</defs>"))
        val params = ArrayList<RrParam>()
        val ext = ArrayList<Ext>()
        var version = ""
        var protocols = 0
        try {
            while (true) {
                val ev = xp.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev != XmlPullParser.START_TAG) continue
                when (xp.name) {
                    "logger" -> version = xp.attr("version")
                    "protocol" -> { protocols++; if (!xp.attr("id").equals("SSM", true)) skip(xp) }
                    "parameter" -> params += parameter(xp)
                    "switch" -> params += switch(xp)
                    "ecuparam" -> ext += ecuparam(xp)
                    "transports", "dtcodes" -> skip(xp)
                }
            }
        } catch (e: XmlPullParserException) {
            val why = e.message.orEmpty().substringBefore(" (position").substringBefore("(position").trim()
            throw BadFile("ошибка в XML, строка ${e.lineNumber}: $why")
        }
        if (params.isEmpty() && ext.isEmpty())
            throw BadFile(if (protocols > 0) "в файле нет параметров протокола SSM" else "в файле нет элементов parameter, ecuparam или switch")
        return Defs(params, ext, version)
    }

    /**
     * The bundled base ([json], assets/romraider_ssm.json) back as a RomRaider logger.xml: the same parameters, switches
     * and extended parameters this app reads, each with its Russian name in a comment, for the driver to copy what
     * needs fixing into a file of their own (a parameter under the base's id replaces it). Read back it is the base.
     */
    fun exportBase(json: String): String {
        val o = org.json.JSONObject(json)
        val sb = StringBuilder()
        fun esc(v: String) = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        fun comment(v: String) = v.replace("--", "—")
        fun hex(a: Int) = "0x%06X".format(a)
        fun addr(a: Int, len: Int, bit: Int) =
            "<address" + (if (len > 1) " length=\"$len\"" else "") + (if (bit >= 0) " bit=\"$bit\"" else "") + ">${hex(a)}</address>"
        fun convs(c: org.json.JSONArray, pad: String) {
            sb.append("$pad<conversions>\n")
            for (k in 0 until c.length()) {
                val cv = c.getJSONArray(k)
                sb.append("$pad  <conversion units=\"${esc(cv.getString(0))}\" expr=\"${esc(cv.getString(1))}\" format=\"${esc(cv.getString(2))}\"" +
                    cv.getString(3).takeIf { it.isNotEmpty() }?.let { " storagetype=\"${esc(it)}\"" }.orEmpty() + " />\n")
            }
            sb.append("$pad</conversions>\n")
        }
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!-- База параметров SSM, по которой работает CarPulse: ${comment(o.optString("source"))}.\n")
        sb.append("     Это копия для справки, приложение её не читает. Чтобы поправить адрес или формулу, скопируйте parameter\n")
        sb.append("     или ecuparam в свой .xml в папке defs (уровнем выше): параметр с тем же id заменит базовый. -->\n")
        sb.append("<logger version=\"CarPulse base\">\n<protocols>\n<protocol id=\"SSM\">\n<parameters>\n")
        val ps = o.getJSONArray("params")
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            p.optString("ru").takeIf { it.isNotEmpty() }?.let { sb.append("  <!-- ${comment(it)} -->\n") }
            sb.append("  <parameter id=\"${esc(p.getString("id"))}\" name=\"${esc(p.getString("n"))}\"")
            p.optString("d").takeIf { it.isNotEmpty() }?.let { sb.append(" desc=\"${esc(it)}\"") }
            sb.append(" target=\"${p.getInt("t")}\"")
            if (p.has("b")) sb.append(" ecubyteindex=\"${p.getInt("b")}\" ecubit=\"${p.getInt("cb")}\"")
            sb.append(">\n")
            p.optJSONArray("a")?.let { a -> sb.append("    ${addr(a.getInt(0), a.getInt(1), a.getInt(2))}\n") }
            p.optJSONArray("aa")?.let { aa -> for (k in 0 until aa.length()) aa.getJSONArray(k).let { x -> sb.append("    ${addr(x.getInt(0), x.getInt(1), -1)}\n") } }
            p.optJSONArray("dep")?.takeIf { it.length() > 0 }?.let { d ->
                sb.append("    <depends>\n")
                for (k in 0 until d.length()) sb.append("      <ref parameter=\"${esc(d.getString(k))}\" />\n")
                sb.append("    </depends>\n")
            }
            convs(p.getJSONArray("c"), "    ")
            sb.append("  </parameter>\n")
        }
        sb.append("</parameters>\n<switches>\n")
        val ss = o.getJSONArray("switches")
        for (i in 0 until ss.length()) {
            val p = ss.getJSONObject(i)
            p.optString("ru").takeIf { it.isNotEmpty() }?.let { sb.append("  <!-- ${comment(it)} -->\n") }
            sb.append("  <switch id=\"${esc(p.getString("id"))}\" name=\"${esc(p.getString("n"))}\" byte=\"${hex(p.getInt("a"))}\" bit=\"${p.getInt("bit")}\"" +
                " target=\"${p.getInt("t")}\"" + (if (p.has("b")) " ecubyteindex=\"${p.getInt("b")}\"" else "") + " />\n")
        }
        sb.append("</switches>\n<ecuparams>\n")
        val es = o.getJSONArray("ecuparams")
        for (i in 0 until es.length()) {
            val p = es.getJSONObject(i)
            p.optString("ru").takeIf { it.isNotEmpty() }?.let { sb.append("  <!-- ${comment(it)} -->\n") }
            sb.append("  <ecuparam id=\"${esc(p.getString("id"))}\" name=\"${esc(p.getString("n"))}\"")
            p.optString("d").takeIf { it.isNotEmpty() }?.let { sb.append(" desc=\"${esc(it)}\"") }
            sb.append(" target=\"${p.getInt("t")}\">\n")
            // the ROMs with the same address in one <ecu>, as RomRaider writes them
            val r = p.getJSONObject("r")
            val byAddr = LinkedHashMap<String, MutableList<String>>()
            for (rom in r.keys()) {
                val a = r.getJSONArray(rom)
                byAddr.getOrPut(addr(a.getInt(0), a.getInt(1), a.getInt(2))) { ArrayList() } += rom
            }
            for ((a, roms) in byAddr) sb.append("    <ecu id=\"${roms.joinToString(",")}\">\n      $a\n    </ecu>\n")
            convs(p.getJSONArray("c"), "    ")
            sb.append("  </ecuparam>\n")
        }
        sb.append("</ecuparams>\n</protocol>\n</protocols>\n</logger>\n")
        return sb.toString()
    }

    /** [text] without what can't stand inside another element: the BOM, the declaration, the DOCTYPE (lines kept). */
    private fun body(text: String): String {
        var s = text.removePrefix("\uFEFF")
        s = Regex("""<\?xml\s[^>]*\?>""").replace(s, "")
        val dt = s.indexOf("<!DOCTYPE")
        if (dt >= 0) {
            val gt = s.indexOf('>', dt)
            val br = s.indexOf('[', dt)
            val end = if (br in dt until (if (gt < 0) Int.MAX_VALUE else gt)) Regex("""]\s*>""").find(s, br)?.range?.last ?: -1 else gt
            if (end > dt) s = s.substring(0, dt) + "\n".repeat(s.substring(dt, end + 1).count { it == '\n' }) + s.substring(end + 1)
        }
        return s
    }

    private fun XmlPullParser.attr(name: String): String = getAttributeValue(null, name)?.trim().orEmpty()

    private fun skip(xp: XmlPullParser) {
        val depth = xp.depth
        while (true) {
            val ev = xp.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && xp.depth == depth)) return
        }
    }

    /** Every element inside the current one, in order; [tag] may read its text. */
    private fun children(xp: XmlPullParser, tag: (String) -> Unit) {
        val depth = xp.depth
        while (true) {
            val ev = xp.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && xp.depth == depth)) return
            if (ev == XmlPullParser.START_TAG) tag(xp.name)
        }
    }

    /** "0x00000E", "0xFF4DC0" or a decimal number, as RomRaider writes addresses. */
    fun number(s: String): Int? {
        val t = s.trim()
        return if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLongOrNull(16)?.toInt() else t.toLongOrNull()?.toInt()
    }

    private fun address(xp: XmlPullParser, id: String): Addr {
        val len = xp.attr("length").toIntOrNull() ?: 1
        val bit = xp.attr("bit").toIntOrNull() ?: -1
        val text = xp.nextText()
        val a = number(text) ?: throw BadFile("у параметра $id непонятный адрес «${text.trim()}»")
        if (a !in 0..0xFFFFFF || len !in 1..4) throw BadFile("у параметра $id адрес ${text.trim()} длиной $len вне памяти ЭБУ")
        return Addr(a, len, bit)
    }

    private fun conversion(xp: XmlPullParser): Conv {
        val units = xp.attr("units")
        var expr = xp.attr("expr").ifEmpty { "x" }
        if (units.lowercase().startsWith("kpa")) expr = EXPR_FIX[expr] ?: expr
        return Conv(units, expr, xp.attr("format").ifEmpty { "0" }, xp.attr("storagetype"))
    }

    private fun parameter(xp: XmlPullParser): RrParam {
        val id = xp.attr("id")
        if (id.isEmpty()) throw BadFile("parameter без id, строка ${xp.lineNumber}")
        val name = xp.attr("name").ifEmpty { id }
        val desc = xp.attr("desc")
        val target = xp.attr("target").toIntOrNull() ?: 1
        val capByte = xp.attr("ecubyteindex").toIntOrNull() ?: -1
        val capBit = xp.attr("ecubit").toIntOrNull() ?: -1
        var a: Addr? = null
        val more = ArrayList<Int>()
        val deps = ArrayList<String>()
        val cs = ArrayList<Conv>()
        children(xp) { t ->
            when (t) {
                // several addresses are one value, their bytes in order, as RomRaider's convertor reads them
                // (P148: the steering angle's high byte at 0x8D, its low byte at 0x15A)
                "address" -> address(xp, id).let { x -> if (a == null) a = x else repeat(x.len) { more += x.addr + it } }
                "ref" -> xp.attr("parameter").ifEmpty { xp.attr("ecuparam") }.takeIf { it.isNotEmpty() }?.let { deps += it }
                "conversion" -> cs += conversion(xp)
            }
        }
        return build(id, RrParam.Kind.STD, target, name, desc, a, deps, cs).copy(capByte = capByte, capBit = capBit, more = more)
    }

    private fun switch(xp: XmlPullParser): RrParam {
        val id = xp.attr("id")
        if (id.isEmpty()) throw BadFile("switch без id, строка ${xp.lineNumber}")
        val addr = number(xp.attr("byte"))?.takeIf { it in 0..0xFFFFFF } ?: throw BadFile("у переключателя $id непонятный байт «${xp.attr("byte")}»")
        val bit = xp.attr("bit").toIntOrNull()?.takeIf { it in 0..7 } ?: throw BadFile("у переключателя $id непонятный бит «${xp.attr("bit")}»")
        val name = xp.attr("name").ifEmpty { id }
        return RrParam(
            id = id, kind = RrParam.Kind.SWITCH, target = xp.attr("target").toIntOrNull() ?: 1, name = name, nameEn = name, desc = "",
            addr = addr, len = 1, bit = bit, units = "", format = "0", storage = "", expr = RrExpr.parse("x"),
            deps = emptyList(), conversions = emptyMap(), capByte = xp.attr("ecubyteindex").toIntOrNull() ?: -1, capBit = bit,
        ).also { skip(xp) }
    }

    private fun ecuparam(xp: XmlPullParser): Ext {
        val id = xp.attr("id")
        if (id.isEmpty()) throw BadFile("ecuparam без id, строка ${xp.lineNumber}")
        val name = xp.attr("name").trimEnd('*').trim().ifEmpty { id }
        val desc = xp.attr("desc")
        val target = xp.attr("target").toIntOrNull() ?: 1
        val roms = LinkedHashMap<String, Addr>()
        var ecu = emptyList<String>()
        val cs = ArrayList<Conv>()
        children(xp) { t ->
            when (t) {
                "ecu" -> ecu = xp.attr("id").split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                "address" -> { val a = address(xp, id); ecu.forEach { roms[it] = a }; ecu = emptyList() }
                "conversion" -> cs += conversion(xp)
            }
        }
        return Ext(build(id, RrParam.Kind.EXT, target, name, desc, null, emptyList(), cs), roms)
    }

    private fun build(id: String, kind: RrParam.Kind, target: Int, name: String, desc: String, a: Addr?, deps: List<String>, all: List<Conv>): RrParam {
        // some ecuparams list psi or mmHg before kPa: a metric one first, like the converter
        val cs = all.sortedBy { it.units.lowercase().substringBefore(' ') in IMPERIAL }
        val convs = LinkedHashMap<String, RrExpr>()
        cs.forEach { c -> RrExpr.parse(c.expr)?.let { convs.putIfAbsent(c.units.lowercase(), it) } }
        val first = cs.firstOrNull()
        return RrParam(
            id = id, kind = kind, target = target, name = name, nameEn = name, desc = desc,
            addr = a?.addr ?: -1, len = a?.len ?: 0, bit = a?.bit ?: -1,
            // one value in memory, one storage: E36 leaves it out on its kPa conversion only, the others say float
            units = first?.units ?: "", format = first?.format ?: "0",
            storage = first?.storage?.ifEmpty { cs.firstOrNull { it.storage.isNotEmpty() }?.storage.orEmpty() } ?: "",
            expr = first?.let { RrExpr.parse(it.expr) }, deps = deps, conversions = convs,
        )
    }

    /**
     * What one file did over the base and the files before it: [std] the standard parameters and switches it added
     * or changed, [ext] the extended ones with the ROMs where it added or changed an address. [same] counts what was
     * already there exactly so (a whole logger.xml repeats most of the base).
     */
    class Stats(val added: Int, val replaced: Int, val same: Int, val std: Set<String>, val ext: Map<String, Set<String>>) {
        /** The ids this file changes on [rom]. */
        fun ids(rom: String): Set<String> = std + ext.filterValues { rom.uppercase() in it }.keys
        val roms: Int get() = ext.values.flatten().toSet().size
    }

    class Merged(val all: List<RrParam>, val byRom: Map<String, List<RrParam>>, val stats: List<Stats>)

    /**
     * [files] (name and contents, later ones win) over the base. A parameter with the base's id replaces it; an
     * extended one replaces the base's only on the ROMs it lists, the other ROMs keep theirs. A name the base has in
     * Russian stays Russian when the English one is the same.
     */
    fun merge(base: List<RrParam>, baseByRom: Map<String, List<RrParam>>, files: List<Pair<String, Defs>>): Merged {
        val baseById = base.associateBy { it.id }
        val baseExt = HashMap<String, RrParam>()
        baseByRom.values.forEach { l -> l.forEach { baseExt.putIfAbsent(it.id, it) } }
        val all = LinkedHashMap<String, RrParam>()
        base.forEach { all[it.id] = it }
        val touched = HashMap<String, LinkedHashMap<String, RrParam>>()
        fun romMap(rom: String) = touched.getOrPut(rom) { LinkedHashMap<String, RrParam>().also { m -> baseByRom[rom]?.forEach { m[it.id] = it } } }
        val stats = files.map { (file, d) ->
            var added = 0; var replaced = 0; var same = 0
            val std = LinkedHashSet<String>()
            val ext = LinkedHashMap<String, Set<String>>()
            for (p in d.params) {
                val q = named(p, baseById[p.id], file)
                val old = all[p.id]
                if (old != null && same(old, q)) { same++; continue }
                if (old != null) replaced++ else added++
                all[p.id] = q
                std += p.id
            }
            for (e in d.ext) {
                val b = baseExt[e.param.id]
                val q = named(e.param, b, file)
                val roms = LinkedHashSet<String>()
                var had = false
                for ((rom, a) in e.roms) {
                    val m = romMap(rom)
                    val p = q.copy(addr = a.addr, len = a.len, bit = a.bit)
                    val old = m[p.id]
                    if (old != null && same(old, p)) continue
                    if (old != null) had = true
                    m[p.id] = p
                    roms += rom
                }
                // a known id on ROMs that didn't have it yet adds, not replaces
                when {
                    roms.isEmpty() -> same++
                    had -> replaced++
                    else -> added++
                }
                if (roms.isNotEmpty()) ext[q.id] = roms
            }
            Stats(added, replaced, same, std, ext)
        }
        val byRom = if (touched.isEmpty()) baseByRom else HashMap(baseByRom).apply { touched.forEach { (rom, m) -> put(rom, m.values.toList()) } }
        return Merged(all.values.toList(), byRom, stats)
    }

    private fun named(p: RrParam, base: RrParam?, file: String): RrParam {
        val sameName = base != null && base.nameEn.trimEnd('*').trim().equals(p.nameEn.trimEnd('*').trim(), ignoreCase = true)
        // RomRaider's own descriptions start with the id ("E51-MAP is limited…") or are only the id
        val own = p.desc.removePrefix(p.id).removePrefix("-").trim()
        val desc = if (sameName) base!!.desc else own
        return p.copy(name = if (sameName) base!!.name else p.name, desc = listOf("из файла $file", desc).filter { it.isNotBlank() }.joinToString(". "))
    }

    /** The same reading: address, storage, units and formula (compared by value, not by spelling). */
    fun same(a: RrParam, b: RrParam): Boolean =
        a.kind == b.kind && a.target == b.target && a.addr == b.addr && a.len == b.len && a.bit == b.bit && a.more == b.more &&
            a.storage == b.storage && a.units.equals(b.units, ignoreCase = true) && a.capByte == b.capByte && a.capBit == b.capBit &&
            a.deps.toSet() == b.deps.toSet() && sameExpr(a.expr, b.expr)

    private fun sameExpr(a: RrExpr?, b: RrExpr?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.refs.toSet() != b.refs.toSet()) return false
        val ref = { id: String, u: String? -> 50.0 + ("$id:$u".hashCode() and 0xFF) }
        return listOf(0.0, 1.0, 37.0, 200.0, 1234.5, -3.0).all { x ->
            val p = a.eval(x, ref)
            val q = b.eval(x, ref)
            p == q || (p != null && q != null && kotlin.math.abs(p - q) <= 1e-9 * maxOf(1.0, kotlin.math.abs(p)))
        }
    }
}
