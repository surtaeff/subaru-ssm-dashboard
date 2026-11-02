package kg.forester.sg.data

import android.content.Context

/**
 * The app's texts are Russian literals, some of them logic keys too, so they are translated where they are shown, not
 * where they are written: [tr] at the point of display. With Russian chosen it hands the argument straight back.
 *
 * English is assets/i18n/en.tsv, `key<TAB>text`, read once off the main thread and only when English is chosen. A key
 * is the text as shown, or a pattern: `#` a number, `{}` any text (itself translated, so a pattern and a word make a
 * sentence). `{1}`, `{2}` in the English take the holes out of order; `\n` is a line break; `//` starts a comment.
 * What has no line stays Russian: a half English screen beats a blank one.
 */
object I18n {
    const val CACHE_MAX = 512
    private const val MISSING_MAX = 2000
    private const val DEPTH_MAX = 4

    /** Number runs of a text, the k-th as this char + k: the same sentence with other numbers is one cache line. */
    private const val MARK = ''
    private const val MARK_LAST = ''
    private const val RAW = ''
    private const val PUNCT = "  :,.…—–·;!?-"
    private val SEPARATORS = arrayOf("\n", "   ", " · ", " — ", " – ", " → ", " | ", "; ", ": ", ". ", ", ", " / ", " + ")

    @Volatile var english = false

    /** The dictionary has been read. Until then, and with Russian, the original shows. */
    @Volatile var ready = false
        private set

    /** Told once for each text with no line: the debug build logs it. */
    @Volatile var onMissing: ((String) -> Unit)? = null
    val missing = HashSet<String>()

    @Volatile private var dict = Dict(emptyList())

    /** Pairs that come with the data: a trouble code or a parameter has its own English in its asset. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Normal form of a text → its translation with marks in place of the numbers, or NONE. */
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > CACHE_MAX
    }
    private const val NONE = ""

    fun install(lines: Iterable<String>, loaded: Boolean = true) {
        val d = Dict(lines)
        synchronized(cache) {
            cache.clear()
            dict = d
            ready = loaded
        }
    }

    fun cacheSize() = synchronized(cache) { cache.size }

    /** [ru] has its English in the data that came with it ([learned]): no dictionary line needed. */
    fun learn(ru: String, en: String) {
        if (ru.isNotEmpty() && en.isNotEmpty() && ru != en) learned[ru] = en
    }

    /** A text not of the app: a song, a station, a name the driver gave. Not looked up, even if it reads like a word. */
    fun raw(s: String): String = if (english && s.isNotEmpty()) RAW + s else s

    /** One Russian word that is two English ones (Карта: a map, a memory card): the place that knows says which. */
    fun pick(ru: String, en: String): String = if (english) en else ru

    /** At start-up: English chosen is English from now on, and its dictionary is read on the side. */
    fun init(ctx: Context, language: String) {
        val app = ctx.applicationContext
        english = language == EN
        if (onMissing == null && app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            onMissing = { android.util.Log.i("I18nMissing", it) }
        }
        if (english && !ready) Thread({ load(app) }, "i18n").start()
    }

    /** The language was changed: reads the dictionary when it is needed. Blocks: not on the main thread. */
    fun choose(ctx: Context, language: String) {
        english = language == EN
        if (english && !ready) load(ctx.applicationContext)
    }

    /** Until the dictionary is there; blocks. */
    fun await() {
        var left = 400
        while (english && !ready && left-- > 0) Thread.sleep(10)
    }

    private val loadLock = Any()

    private fun load(ctx: Context) = synchronized(loadLock) {
        if (ready) return@synchronized
        val lines = runCatching { ctx.assets.open("i18n/en.tsv").bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
        install(lines)
    }

    const val RU = "ru"
    const val EN = "en"

    internal fun translate(text: String): String {
        if (text.isNotEmpty() && text[0] == RAW) return text.substring(1)
        if (!hasCyrillic(text) || !ready) return text
        val d = dict
        d.exact[text]?.let { return it }
        learned[text]?.let { return it }
        val numbers = ArrayList<String>(4)
        val form = normal(text, numbers) ?: return text
        val known = synchronized(cache) { cache[form] }
        val made = known ?: run {
            val run = Run(numbers)
            val hit = d.resolve(indexed(form), 0, run)
            val r = hit?.takeIf { it.done || hasLatin(it.text) }?.text ?: NONE
            if (!run.exactNumbers) synchronized(cache) { cache[form] = r }
            if (hit == null || !hit.done) tell(text)
            r
        }
        if (made == NONE) return text
        return fill(made, numbers)
    }

    private fun tell(text: String) {
        val o = onMissing ?: return
        val first = synchronized(missing) { missing.size < MISSING_MAX && missing.add(text) }
        if (first) o(text)
    }

    private fun hasCyrillic(s: String): Boolean {
        for (ch in s) if (ch in 'Ѐ'..'ӿ') return true
        return false
    }

    private fun hasLatin(s: String): Boolean {
        for (ch in s) if (ch in 'a'..'z' || ch in 'A'..'Z') return true
        return false
    }

    private fun isLetter(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z' || ch in 'Ѐ'..'ӿ'
    private fun isLatin(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z'
    private fun isDigit(ch: Char) = ch in '0'..'9'
    private fun isGroupSpace(ch: Char) = ch == ' ' || ch == ' ' || ch == ' '

    /**
     * The text with each number run as one [MARK], by a plain scan: a run takes `.`, `,`, `:` between digits and a
     * space before a group of three. A digit stuck to a letter before it or a Latin one after it is part of a name:
     * F1, P0420, 4WD. Null when there are too many runs to mark.
     */
    private fun normal(s: String, numbers: MutableList<String>): String? {
        var b: StringBuilder? = null
        var i = 0
        val n = s.length
        var copied = 0
        while (i < n) {
            if (!isDigit(s[i])) { i++; continue }
            if (i > 0 && (isLetter(s[i - 1]) || isDigit(s[i - 1]))) { i++; continue }
            var j = i + 1
            while (j < n) {
                val c = s[j]
                if (isDigit(c)) j++
                else if ((c == '.' || c == ',' || c == ':') && j + 1 < n && isDigit(s[j + 1])) j += 2
                else if (isGroupSpace(c) && j + 3 < n + 0 && isThree(s, j + 1)) j += 4
                else break
            }
            if (j < n && isLatin(s[j])) { i = j; continue }
            if (numbers.size >= (MARK_LAST - MARK)) return null
            val out = b ?: StringBuilder(n).also { b = it }
            out.append(s, copied, i).append(MARK)
            numbers.add(s.substring(i, j))
            copied = j
            i = j
        }
        val out = b ?: return s
        return out.append(s, copied, n).toString()
    }

    private fun isThree(s: String, at: Int): Boolean {
        if (at + 3 > s.length) return false
        for (k in at until at + 3) if (!isDigit(s[k])) return false
        return at + 3 == s.length || !isDigit(s[at + 3])
    }

    /** [MARK] [MARK] … → the k-th mark names the k-th number, so that a translation may move them. */
    private fun indexed(form: String): String {
        if (form.indexOf(MARK) < 0) return form
        val b = StringBuilder(form.length)
        var k = 0
        for (c in form) if (c == MARK) b.append(MARK + k++) else b.append(c)
        return b.toString()
    }

    private fun fill(made: String, numbers: List<String>): String {
        var has = false
        for (c in made) if (c in MARK..MARK_LAST) { has = true; break }
        if (!has) return made
        val b = StringBuilder(made.length + 8)
        for (c in made) if (c in MARK..MARK_LAST) b.append(numbers.getOrElse(c - MARK) { "" }) else b.append(c)
        return b.toString()
    }

    private fun isMark(c: Char) = c in MARK..MARK_LAST

    private class Hit(val text: String, val done: Boolean)

    /** One lookup: its numbers; [exactNumbers] once a line held good for just these digits, so the form can't be kept. */
    private class Run(val numbers: List<String>) { var exactNumbers = false }

    private sealed interface Tok
    private class Lit(val s: String) : Tok
    private object Num : Tok
    private object Any : Tok

    private class Pattern(val toks: List<Tok>, val value: String, val literal: Int)

    private class Dict(lines: Iterable<String>) {
        /** The text as shown → English. */
        val exact = HashMap<String, String>()
        /** The form with only number holes, marks collapsed → its pattern. */
        val numeric = HashMap<String, Pattern>()
        val byHead = HashMap<String, MutableList<Pattern>>()
        val byTail = HashMap<String, MutableList<Pattern>>()
        val inner = ArrayList<Pattern>()
        /** By capitals, and by the first letter's case: «ТЕМПЕРАТУРА» and «температура» of a line «Температура». */
        val caps = HashMap<String, String>()

        init {
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("//")) continue
                val t = line.indexOf('\t')
                if (t <= 0) continue
                val key = line.substring(0, t).replace("\\n", "\n")
                val value = line.substring(t + 1).replace("\\n", "\n")
                add(key, value)
            }
            for (l in byHead.values) l.sortByDescending { it.literal }
            for (l in byTail.values) l.sortByDescending { it.literal }
            inner.sortByDescending { it.literal }
        }

        private fun add(key: String, value: String) {
            register(key, value)
            // a fragment (" · now # l") also serves the text cut out of what the screen joined
            val core = key.trim { PUNCT.indexOf(it) >= 0 }
            if (core.length < key.length && core.isNotEmpty() && core !in exact && hasCyrillic(core)) {
                register(core, value.trim { PUNCT.indexOf(it) >= 0 })
            }
        }

        private fun register(key: String, value: String) {
            val hasHole = key.indexOf('#') >= 0 || key.contains("{}")
            // a key with a real # in it (Learning ## Airflow) still finds itself
            exact[key] = value
            if (!hasHole) {
                if (key.length <= 60) caps[key.uppercase()] = value
                return
            }
            val toks = ArrayList<Tok>()
            var i = 0
            val lit = StringBuilder()
            var literal = 0
            fun flush() { if (lit.isNotEmpty()) { toks.add(Lit(lit.toString())); literal += lit.length; lit.setLength(0) } }
            while (i < key.length) {
                if (key[i] == '#') { flush(); toks.add(Num); i++ }
                else if (key.startsWith("{}", i)) { flush(); toks.add(Any); i += 2 }
                else lit.append(key[i++])
            }
            flush()
            val p = Pattern(toks, value, literal)
            if (toks.none { it === Any }) {
                val b = StringBuilder()
                for (tk in toks) if (tk is Lit) b.append(tk.s) else b.append(MARK)
                numeric[b.toString()] = p
                return
            }
            val head = toks.first() as? Lit
            val tail = toks.last() as? Lit
            when {
                head != null && head.s.length >= 2 -> byHead.getOrPut(head.s.substring(0, 2)) { ArrayList() }.add(p)
                tail != null && tail.s.length >= 2 -> byTail.getOrPut(tail.s.takeLast(2)) { ArrayList() }.add(p)
                else -> inner.add(p)
            }
        }

        /** The translation of [s], numbers as indexed marks. */
        fun resolve(s: String, depth: Int, run: Run): Hit? {
            if (depth > DEPTH_MAX) return null
            if (!hasCyrillic(s)) return Hit(s, true)
            exact[s]?.let { return Hit(it, true) }
            learned[s]?.let { return Hit(it, true) }
            // inside a hole the digits are marks by now, and a line may hold them as written
            if (depth > 0 && s.any { isMark(it) }) {
                val real = fill(s, run.numbers)
                (exact[real] ?: learned[real])?.let { run.exactNumbers = true; return Hit(it, true) }
            }
            byNumbers(s)?.let { return Hit(it, true) }
            byHoles(s, depth, run)?.let { if (it.done) return it else return bestOf(it, s, depth, run) }
            return afterHoles(s, depth, run)
        }

        private fun bestOf(partial: Hit, s: String, depth: Int, run: Run): Hit = afterHoles(s, depth, run)?.takeIf { it.done } ?: partial

        private fun afterHoles(s: String, depth: Int, run: Run): Hit? {
            byCase(s)?.let { return Hit(it, true) }
            around(s, depth, run)?.let { return it }
            return byParts(s, depth, run)
        }

        private fun byNumbers(s: String): String? {
            if (numeric.isEmpty()) return null
            var any = false
            for (c in s) if (isMark(c)) { any = true; break }
            if (!any) return null
            val b = StringBuilder(s.length)
            val marks = ArrayList<Char>(4)
            for (c in s) if (isMark(c)) { b.append(MARK); marks.add(c) } else b.append(c)
            val p = numeric[b.toString()] ?: return null
            return write(p.value, marks.map { it.toString() })
        }

        private fun byHoles(s: String, depth: Int, run: Run): Hit? {
            var partial: Hit? = null
            fun tryAll(list: List<Pattern>?): Hit? {
                if (list == null) return null
                for (p in list) {
                    val caps = ArrayList<String>(3)
                    if (!match(p.toks, 0, s, 0, caps)) continue
                    var done = true
                    val out = ArrayList<String>(caps.size)
                    for ((k, c) in caps.withIndex()) {
                        if (p.toks.holeIsNumber(k)) { out.add(c); continue }
                        val h = resolve(c, depth + 1, run)
                        if (h == null) { if (hasCyrillic(c)) done = false; out.add(c) }
                        else { if (!h.done) done = false; out.add(h.text) }
                    }
                    val r = Hit(write(p.value, out), done)
                    if (done) return r
                    // a hole that found nothing: only a pattern with real words around it may stand as a half translation
                    if (p.literal < 6) continue
                    if (partial == null) partial = r
                }
                return null
            }
            if (s.length >= 2) {
                tryAll(byHead[s.substring(0, 2)])?.let { return it }
                tryAll(byTail[s.takeLast(2)])?.let { return it }
            }
            tryAll(inner)?.let { return it }
            return partial
        }

        private fun List<Tok>.holeIsNumber(k: Int): Boolean {
            var n = 0
            for (t in this) if (t !is Lit) { if (n == k) return t === Num; n++ }
            return false
        }

        private fun match(toks: List<Tok>, ti: Int, s: String, at: Int, caps: MutableList<String>): Boolean {
            if (ti == toks.size) return at == s.length
            when (val t = toks[ti]) {
                is Lit -> return s.startsWith(t.s, at) && match(toks, ti + 1, s, at + t.s.length, caps)
                Num -> {
                    if (at >= s.length || !isMark(s[at])) return false
                    caps.add(s[at].toString())
                    if (match(toks, ti + 1, s, at + 1, caps)) return true
                    caps.removeAt(caps.size - 1)
                    return false
                }
                Any -> {
                    val next = toks.getOrNull(ti + 1) as? Lit
                    if (ti == toks.size - 1) {
                        if (at >= s.length) return false
                        caps.add(s.substring(at))
                        return true
                    }
                    // the shortest that lets the rest fit
                    var e = at + 1
                    while (e <= s.length) {
                        if (next != null) {
                            e = s.indexOf(next.s, e)
                            if (e < 0) return false
                        }
                        caps.add(s.substring(at, e))
                        if (match(toks, ti + 1, s, e, caps)) return true
                        caps.removeAt(caps.size - 1)
                        e++
                    }
                    return false
                }
            }
        }

        /** The English with its holes filled, one after the other or by `{n}`. */
        private fun write(value: String, holes: List<String>): String {
            val b = StringBuilder(value.length + 16)
            var i = 0
            var next = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '#' -> { b.append(holes.getOrElse(next) { "" }); next++; i++ }
                    c == '{' && value.startsWith("{}", i) -> { b.append(holes.getOrElse(next) { "" }); next++; i += 2 }
                    c == '{' && i + 2 < value.length && value[i + 1] in '1'..'9' && value[i + 2] == '}' -> {
                        b.append(holes.getOrElse(value[i + 1] - '1') { "" })
                        i += 3
                    }
                    else -> { b.append(c); i++ }
                }
            }
            return b.toString()
        }

        private fun byCase(s: String): String? {
            caps[s]?.let { return it.uppercase() }
            val first = s[0]
            if (first in 'а'..'я' || first == 'ё') {
                val up = s.replaceFirstChar { it.uppercaseChar() }
                exact[up]?.let { return it.replaceFirstChar { c -> c.lowercaseChar() } }
                byNumbers(up)?.let { return it.replaceFirstChar { c -> c.lowercaseChar() } }
            } else if (first in 'А'..'Я' || first == 'Ё') {
                val low = s.replaceFirstChar { it.lowercaseChar() }
                exact[low]?.let { return it.replaceFirstChar { c -> c.uppercaseChar() } }
                byNumbers(low)?.let { return it.replaceFirstChar { c -> c.uppercaseChar() } }
            }
            return null
        }

        /** Stops, spaces, brackets and quotes around a line stay, and the line in them is looked up. */
        private fun around(s: String, depth: Int, run: Run): Hit? {
            var a = 0
            var e = s.length
            while (a < e && PUNCT.indexOf(s[a]) >= 0) a++
            while (e > a && PUNCT.indexOf(s[e - 1]) >= 0) e--
            if (e - a >= 2 && (s[a] == '(' && s[e - 1] == ')' || s[a] == '«' && s[e - 1] == '»' || s[a] == '"' && s[e - 1] == '"')) {
                val open = if (s[a] == '«') "“" else s[a].toString()
                val close = if (s[a] == '«') "”" else s[e - 1].toString()
                val h = resolve(s.substring(a + 1, e - 1), depth + 1, run) ?: return null
                return Hit(s.substring(0, a) + open + h.text + close + s.substring(e), h.done)
            }
            if (a == 0 && e == s.length) return null
            if (a >= e) return null
            val h = resolve(s.substring(a, e), depth + 1, run) ?: return null
            return Hit(s.substring(0, a) + h.text + s.substring(e), h.done)
        }

        /** Parts the screen joined itself: each is translated, and something must have been found in them. */
        private fun byParts(s: String, depth: Int, run: Run): Hit? {
            var partial: Hit? = null
            for (sep in SEPARATORS) {
                if (!s.contains(sep)) continue
                val parts = s.split(sep)
                var done = true
                var found = false
                val b = StringBuilder(s.length)
                for ((k, part) in parts.withIndex()) {
                    if (k > 0) b.append(sep)
                    if (!hasCyrillic(part)) { b.append(part); continue }
                    val h = resolve(part, depth + 1, run)
                    if (h == null) { done = false; b.append(part) }
                    else { found = true; if (!h.done) done = false; b.append(h.text) }
                }
                if (done && found) return Hit(b.toString(), true)
                if (found && partial == null) partial = Hit(b.toString(), false)
            }
            return partial ?: byWords(s)
        }

        /** A number and a word or two, all of them known: «дроссель 26», «2.31 мс». Never a half translation. */
        private fun byWords(s: String): Hit? {
            val words = s.split(' ')
            if (words.size !in 2..3 || words.none { w -> w.length == 1 && isMark(w[0]) }) return null
            val b = StringBuilder(s.length)
            for ((k, w) in words.withIndex()) {
                if (k > 0) b.append(' ')
                if (!hasCyrillic(w)) { b.append(w); continue }
                b.append(exact[w] ?: return null)
            }
            return Hit(b.toString(), true)
        }
    }
}

/** What to show for a text: the argument itself with Russian chosen. */
fun tr(text: String): String = if (I18n.english) I18n.translate(text) else text
