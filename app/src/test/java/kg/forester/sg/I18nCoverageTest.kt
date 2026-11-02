package kg.forester.sg

import kg.forester.sg.data.I18n
import kg.forester.sg.data.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Every Cyrillic string literal of the sources that can reach a screen, put through the real [tr] with its holes filled.
 * Left out as files: the voice grammar and its spoken form (Russian on purpose). tools/i18n/scan.py is the twin of this
 * scan and lists what is missing, by file.
 */
class I18nCoverageTest {
    private val root = File("src/main/java/kg/forester/sg")
    private val tsv = File("src/main/assets/i18n/en.tsv")
    private val allow = File("src/test/resources/i18n_allowlist.txt")
    private val skip = setOf("service/Commands.kt", "service/SpeechText.kt", "ui/I18nText.kt")
    private val HOLE = ''

    @Before
    fun on() {
        I18n.install(tsv.readLines())
        I18n.english = true
    }

    @After
    fun off() {
        I18n.english = false
        I18n.install(emptyList())
    }

    // ---- the scan: a hand-written reader of Kotlin strings, comments skipped, + chains joined ----

    private class Lit(val text: String, val nested: List<Lit> = emptyList())

    private inner class Reader(val s: String) {
        val found = ArrayList<String>()

        /** At an opening quote: the text (holes as [HOLE]), and the position after it. */
        fun literal(from: Int): Pair<String, Int> {
            var i = from
            val raw = s.startsWith("\"\"\"", i)
            i += if (raw) 3 else 1
            val b = StringBuilder()
            while (i < s.length) {
                if (raw && s.startsWith("\"\"\"", i)) return b.toString() to i + 3
                val c = s[i]
                if (!raw && c == '"') return b.toString() to i + 1
                if (!raw && c == '\\') {
                    val e = s[i + 1]
                    if (e == 'u') { b.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    b.append(when (e) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; else -> e })
                    i += 2
                    continue
                }
                if (c == '$' && i + 1 < s.length && s[i + 1] == '{') {
                    val j = block(i + 2)
                    found += Reader(s.substring(i + 2, j - 1)).scan()
                    b.append(HOLE)
                    i = j
                    continue
                }
                if (c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                    var j = i + 1
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    b.append(HOLE)
                    i = j
                    continue
                }
                b.append(c)
                i++
            }
            return b.toString() to i
        }

        private fun block(from: Int): Int {
            var d = 1
            var i = from
            while (i < s.length && d > 0) {
                when (s[i]) {
                    '"' -> { i = literalEnd(i); continue }
                    '\'' -> { i = charEnd(i); continue }
                    '{' -> d++
                    '}' -> d--
                }
                i++
            }
            return i
        }

        private fun literalEnd(at: Int): Int {
            val saved = found.size
            val end = literal(at).second
            while (found.size > saved) found.removeAt(found.size - 1)
            return end
        }

        private fun charEnd(at: Int): Int {
            val j = if (s.getOrNull(at + 1) == '\\') at + 3 else at + 2
            val k = s.indexOf('\'', j)
            return if (k >= 0 && k - j < 8) k + 1 else at + 1
        }

        /** One term of a + chain: where it ends, -1 when a string starts in it at the top level. */
        private fun term(from: Int): Int {
            var d = 0
            var i = from
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '(' || c == '[' || c == '{' -> d++
                    c == ')' || c == ']' || c == '}' -> { if (d == 0) return i; d-- }
                    c == '"' && d == 0 -> return -1
                    c == '"' -> { i = literalEnd(i); continue }
                    d == 0 && (c == '+' || c == ',' || c == '\n' || c == ';') -> return i
                    d == 0 && (s.startsWith(" else ", i) || s.startsWith(" ?: ", i)) -> return i
                }
                i++
            }
            return i
        }

        private fun plus(from: Int): Int {
            var i = from
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != '+') return -1
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            return i
        }

        fun scan(): List<String> {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    s.startsWith("//", i) -> { val j = s.indexOf('\n', i); i = if (j >= 0) j else s.length }
                    s.startsWith("/*", i) -> {
                        var d = 1
                        var j = i + 2
                        while (j < s.length && d > 0) {
                            if (s.startsWith("/*", j)) { d++; j += 2 } else if (s.startsWith("*/", j)) { d--; j += 2 } else j++
                        }
                        i = j
                    }
                    c == '\'' -> i = charEnd(i)
                    c == '"' -> i = chain(i)
                    else -> i++
                }
            }
            return found
        }

        private fun chain(at: Int): Int {
            val before = s.substring(maxOf(0, at - 70), at)
            val text = StringBuilder()
            val tail = before.trimEnd()
            if (tail.endsWith("+") && !tail.dropLast(1).trimEnd().endsWith("=")) text.append(HOLE)
            var k = at
            while (true) {
                val (t, e) = literal(k)
                text.append(t)
                k = e
                var again = false
                while (true) {
                    val j = plus(k)
                    if (j < 0) break
                    if (s.getOrNull(j) == '"') { k = j; again = true; break }
                    val end = term(j)
                    if (end < 0) { text.append(HOLE); break }
                    if (end == j) break
                    found += Reader(s.substring(j, end)).scan()
                    text.append(HOLE)
                    k = end
                }
                if (!again) break
            }
            if (!LOG_CALL.containsMatchIn(before)) found += text.toString()
            return k
        }
    }

    private val LOG_CALL = Regex("(?:\\bLog\\.[a-z]\\(|SsmTrace\\.\\w+\\(|\\btrace\\(|\\btraceLine\\(|GapLog\\.\\w+\\(|\\bgapLog\\(|\\bPerfLog\\.\\w+\\(|\\blogLine\\(|Regex\\(|\\.toRegex\\()\\s*$")
    private val FORMAT = Regex("%(?:\\d+\\$)?[-+ 0,#(]*\\d*(?:\\.\\d+)?([sdfegxXc%])")
    private val NUMBER = Regex("(?<![0-9A-Za-zА-Яа-яЁё.,])\\d+(?:[.,:]\\d+)*(?![0-9A-Za-z])")

    private fun cyrillic(s: String) = s.any { it in 'Ѐ'..'ӿ' }

    /** A literal as a dictionary key, the way the tool writes it. */
    private fun keyOf(lit: String): String {
        var s = FORMAT.replace(lit) { m ->
            when (m.groupValues[1]) { "s", "c" -> HOLE.toString(); "%" -> "%"; else -> "#" }
        }
        val holes = s.indexOf(HOLE) >= 0 || s.indexOf('#') >= 0
        s = s.replace(HOLE.toString(), "{}").replace(Regex("(\\{\\}\\s*){2,}")) { if (it.value.endsWith(" ")) "{} " else "{}" }
        if (holes) s = NUMBER.replace(s, "#")
        return s.replace("\r", "").replace("\t", " ").replace("\n", "\\n")
    }

    private fun keysInSources(): Set<String> {
        val out = LinkedHashSet<String>()
        root.walkTopDown().filter { it.extension == "kt" && it.relativeTo(root).path !in skip }.forEach { f ->
            Reader(f.readText()).scan().forEach { if (cyrillic(it.replace(HOLE.toString(), ""))) out += keyOf(it) }
        }
        return out
    }

    private val allowed by lazy { allow.readLines().filter { it.isNotBlank() && !it.startsWith("//") }.toSet() }

    private val entries by lazy {
        tsv.readLines().filter { it.isNotEmpty() && !it.startsWith("//") && it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    /** As the screen would show it: holes filled, \n a line break. */
    private fun shown(key: String) = key.replace("{}", "7").replace("#", "7").replace("\\n", "\n")

    private fun translated(key: String): Boolean {
        val s = shown(key)
        if (!cyrillic(s)) return true
        val t = tr(s)
        if (!cyrillic(t)) return true
        // kept Russian on purpose: voice phrases in quotes, the name of a station, a language's own name
        return entries.any { (k, v) -> k == key && cyrillic(v) } || (t != s && entries.any { (_, v) -> cyrillic(v) && t.contains(v.substringBefore('“').take(8)) })
    }

    @Test
    fun atLeast98PercentOfDisplayLiteralsAreTranslated() {
        val all = keysInSources()
        val open = all - allowed
        val missing = open.filterNot { translated(it) }
        val pct = 100.0 * (open.size - missing.size) / open.size
        println("I18N coverage: ${open.size - missing.size}/${open.size} = ${"%.2f".format(pct)} % (allowlisted ${all.size - open.size} of ${all.size})")
        File("build/i18n-missing.txt").apply { parentFile?.mkdirs() }.writeText(missing.joinToString("\n"))
        assertTrue("coverage $pct %, first missing: ${missing.take(20)}", pct >= 98.0)
    }

    @Test
    fun theAllowlistHasNoDeadLines() {
        val all = keysInSources()
        val dead = allowed.filterNot { it in all }
        assertEquals("allowlist lines no literal has", emptyList<String>(), dead)
    }

    @Test
    fun dictionaryLinesAreWellFormed() {
        val hole = Regex("#|\\{\\}|\\{[1-9]\\}")
        val bad = ArrayList<String>()
        val seen = HashSet<String>()
        for ((k, v) in entries) {
            if (!seen.add(k)) bad += "twice: $k"
            if (!cyrillic(k)) bad += "no Cyrillic in key: $k"
            if (v.isBlank()) bad += "empty: $k"
            val isPattern = k.contains('#') || k.contains("{}")
            if (isPattern && NUMBER.containsMatchIn(k)) bad += "digits in a pattern: $k"
            if (isPattern && k.replace("{}", "").replace("#", "").none { it in 'Ѐ'..'ӿ' }) bad += "no words in a pattern: $k"
            val ks = hole.findAll(k).count()
            val vs = hole.findAll(v).count()
            if (ks != vs) bad += "holes $ks → $vs: $k"
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun englishIsNotRussian() {
        // only what the owner kept on purpose may stay Cyrillic: voice phrases, station names, the language's own name
        val kept = entries.filter { (_, v) -> cyrillic(v) }
        assertTrue("too many lines kept in Russian: ${kept.size}: ${kept.take(10)}", kept.size <= 40)
    }
}
