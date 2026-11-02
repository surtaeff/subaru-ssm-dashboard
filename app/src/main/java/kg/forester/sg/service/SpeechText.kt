package kg.forester.sg.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An alert or an answer made speakable before it goes to the voice: numbers in words that agree with their unit
 * and with the preposition before them («при 110°» → «при ста десяти градусах», «12,5 В» → «двенадцать
 * с половиной вольта»), units, abbreviations and Latin words spelled the way a driver says them, symbols and
 * emoji dropped. The Piper voices read through eSpeak, which ignores stress marks ("+" is even read as «плюс»),
 * so the few words its dictionary stresses wrong are swapped for ones it says right.
 */
object SpeechText {
    fun prepare(text: String): String {
        var t = clean(text)
        t = phrases(t)
        // a trouble code letter by letter: P0420 → «пи ноль четыре два ноль»
        t = Regex("(?<![\\p{L}\\d])([PCBU])(\\d{4})(?!\\d)").replace(t) { m ->
            (listOf(EN_LETTERS.getValue(m.groupValues[1][0])) + m.groupValues[2].map { ONES[it - '0'] }).joinToString(" ")
        }
        t = Regex("([A-Za-z]{2})(\\d)").replace(t, "$1 $2") // EJ20 → EJ 20
        t = Regex("(\\d)[ \\u00A0\\u202F](?=\\d{3}(?!\\d))").replace(t, "$1") // 150 000 → 150000
        t = time(t)
        t = ranges(t)
        t = Regex("(^|[\\s(:;,])[-−](?=\\d)").replace(t) { it.groupValues[1] + "минус " }
        t = Regex("(^|[\\s(:;,])\\+(?=\\d)").replace(t) { it.groupValues[1] + "плюс " }
        t = ordinals(t)
        t = numbers(t)
        t = words(t)
        return tidy(t)
    }

    /**
     * The same for English, which the head unit's engine says by itself: only what it would read as a symbol or an
     * abbreviation it doesn't know, and what a Russian text left behind (a decimal comma). Numbers stay digits, the
     * engine reads those; a unit after one agrees with it: 1 degree, 2 degrees, 1.5 liters.
     */
    fun prepareEnglish(text: String): String {
        var t = text
        // a trouble code digit by digit, an engine's letters apart from its number: P0420, EJ20
        t = Regex("(?<![\\p{L}\\d])([PCBU])(\\d{4})(?!\\d)").replace(t) { it.groupValues[1] + " " + it.groupValues[2].toList().joinToString(" ") }
        t = Regex("(?<![\\p{L}\\d])([A-Z]{2})(\\d{2,})(?!\\d)").replace(t, "$1 $2")
        t = Regex("(\\d)[ \\u00A0\\u202F](?=\\d{3}(?!\\d))").replace(t, "$1")
        t = Regex("(?<=\\d),(?=\\d{1,2}(?!\\d))").replace(t, ".")
        t = Regex("(?<![\\p{L}\\d.,])(\\d+(?:\\.\\d+)?)\\s?[–-]\\s?(?=\\d)").replace(t) { it.groupValues[1] + " to " }
        t = Regex("(^|[\\s(:;,])[-−](?=\\d)").replace(t) { it.groupValues[1] + "minus " }
        t = Regex("(^|[\\s(:;,])\\+(?=\\d)").replace(t) { it.groupValues[1] + "plus " }
        for ((rx, one, many) in EN_AFTER_NUMBER) t = rx.replace(t) { it.groupValues[1] + " " + if (it.groupValues[1] == "1") one else many }
        for ((a, b) in EN_UNITS) t = a.replace(t, b)
        for ((rx, spelled) in EN_SPELLED) t = rx.replace(t, spelled)
        val b = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0x00A0 || cp == 0x202F || cp == 0x2009 -> b.append(' ')
                cp == '→'.code || cp == '·'.code || cp == '•'.code || cp == '|'.code || cp == '—'.code || cp == '–'.code -> b.append(", ")
                cp == '…'.code -> b.append('.')
                cp == '≈'.code || cp == '~'.code -> b.append(" about ")
                cp == '“'.code || cp == '”'.code || cp == '"'.code || cp == '«'.code || cp == '»'.code -> {}
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt() || Character.getType(cp) == Character.SURROGATE.toInt() -> {}
                else -> b.appendCodePoint(cp)
            }
        }
        return b.toString().replace(Regex("\\s+"), " ").replace(Regex("\\s+,"), ",").trim()
    }

    /** A unit right after a number, longest first: what it is called for exactly 1, and for anything else. */
    private val EN_AFTER_NUMBER = listOf(
        "km/h" to ("kilometer per hour" to "kilometers per hour"), "kph" to ("kilometer per hour" to "kilometers per hour"),
        "mph" to ("mile per hour" to "miles per hour"), "mpg" to ("mile per gallon" to "miles per gallon"),
        "l/100 km" to ("liter per 100 kilometers" to "liters per 100 kilometers"), "L/100 km" to ("liter per 100 kilometers" to "liters per 100 kilometers"),
        "l/100km" to ("liter per 100 kilometers" to "liters per 100 kilometers"), "l/100" to ("liter per 100 kilometers" to "liters per 100 kilometers"),
        "g/s" to ("gram per second" to "grams per second"), "kPa" to ("kilopascal" to "kilopascals"),
        "°C" to ("degree" to "degrees"), "°" to ("degree" to "degrees"), "%" to ("percent" to "percent"), "rpm" to ("r p m" to "r p m"),
        "km" to ("kilometer" to "kilometers"), "kilometers" to ("kilometer" to "kilometers"), "kilometres" to ("kilometer" to "kilometers"),
        "mi" to ("mile" to "miles"), "miles" to ("mile" to "miles"), "degrees" to ("degree" to "degrees"), "volts" to ("volt" to "volts"),
        "liters" to ("liter" to "liters"), "litres" to ("liter" to "liters"),
        "mm" to ("millimeter" to "millimeters"), "ms" to ("millisecond" to "milliseconds"), "min" to ("minute" to "minutes"), "sec" to ("second" to "seconds"),
        "s" to ("second" to "seconds"), "h" to ("hour" to "hours"), "V" to ("volt" to "volts"), "l" to ("liter" to "liters"), "L" to ("liter" to "liters"),
        "m" to ("meter" to "meters"), "ft" to ("foot" to "feet"),
    ).map { (sym, names) ->
        val edge = if (sym.last().isLetter()) "(?![\\p{L}\\d/])" else ""
        Triple(Regex("(?<![\\p{L}\\d.])(\\d+(?:\\.\\d+)?)\\s?${Regex.escape(sym)}$edge"), names.first, names.second)
    }

    /** The same symbols where no number stands before them. */
    private val EN_UNITS = listOf(
        "km/h" to " kilometers per hour", "l/100 km" to " liters per 100 kilometers", "°C" to " degrees", "°" to " degrees",
        "%" to " percent", "(?<=\\s)km(?![\\p{L}\\d])" to "kilometers", "(?<=\\s)mph(?![\\p{L}\\d])" to "miles per hour", "(?<=\\s)mpg(?![\\p{L}\\d])" to "miles per gallon",
        "(?<=\\s)rpm(?![\\p{L}\\d])" to "r p m",
    ).map { (a, b) -> Regex(if (a.startsWith("(?")) a else Regex.escape(a)) to b }

    /** Letters an engine would read as a word ("I am"), or stumble over. */
    private val EN_SPELLED = listOf("IAM", "FBKC", "FLKC", "AVCS", "AFR", "ATF", "ECU", "EGT", "MAF", "DTC", "CVT", "TPMS", "VDC", "OBD", "DAM").map { w ->
        Regex("(?<![\\p{L}\\d])$w(?![\\p{L}\\d])") to w.toList().joinToString(" ")
    }

    // ---- symbols ----

    private fun clean(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == 0x00A0 || cp == 0x202F || cp == 0x2009 -> b.append(' ')
                cp == '→'.code || cp == '·'.code || cp == '•'.code || cp == '|'.code -> b.append(", ")
                cp == '—'.code -> b.append(", ")
                cp == '…'.code -> b.append('.')
                cp == '≈'.code || cp == '~'.code -> b.append(" примерно ")
                cp == '±'.code -> b.append(" плюс-минус ")
                cp == '№'.code -> b.append(" номер ")
                cp == '«'.code || cp == '»'.code || cp == '"'.code || cp == '“'.code || cp == '”'.code || cp == '„'.code -> {}
                cp == '×'.code || cp == '*'.code || cp == '_'.code -> b.append(' ')
                cp == '('.code || cp == ')'.code || cp == '['.code || cp == ']'.code -> b.append(", ")
                cp == '°'.code -> b.append('°')
                // emoji and pictographs, their joiners and variation selectors
                type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                    cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF -> b.append(' ')
                else -> b.appendCodePoint(cp)
            }
        }
        // a dash between words is a pause; between digits it is a range, dealt with later
        return b.toString().replace(Regex("\\s+[–-]\\s+"), ", ")
    }

    /** Whole phrases and words eSpeak stresses or says wrong, and spellings without «ё». */
    private val PHRASES = listOf(
        "перед выключением" to "до выключения",
        "ложный детон" to "ложная детонация",
        "детон" to "детонация",
        "остановитесь" to "встаньте",
        "перенаддувом" to "превышением наддува",
        "перенаддуве" to "превышении наддува",
        "перенаддува" to "превышения наддува",
        "перенаддув" to "превышение наддува",
        "распредвалы" to "распределительные валы",
        "маслонасосе" to "масляном насосе",
        "маслонасоса" to "масляного насоса",
        "маслонасос" to "масляный насос",
        "гидроблока" to "гидравлики",
        "нагружайте" to "давайте нагрузку на",
        "перегреты" to "перегрелись",
        "стравливает" to "сбрасывает",
        "моточаса" to "моточасов",
        "вперед" to "вперёд",
        "еще" to "ещё",
    )
    private val PHRASE_RX = PHRASES.map { (from, to) -> Regex("(?<![\\p{L}])${Regex.escape(from)}(?![\\p{L}])", RegexOption.IGNORE_CASE) to to }

    private fun phrases(s: String) = PHRASE_RX.fold(s) { acc, (rx, to) -> rx.replace(acc, to) }

    // ---- times, ranges, ordinals ----

    private fun time(s: String): String {
        // a lap: 1:23,45
        var t = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})[,.](\\d{1,3})(?![\\d:])").replace(s) {
            "${it.groupValues[1]} мин ${it.groupValues[2].trimStart('0').ifEmpty { "0" }},${it.groupValues[3]} с"
        }
        // a clock: 14:05 → «четырнадцать ноль пять»
        t = Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])").replace(t) {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            spell(h.toLong(), G.M, Case.NOM) + " " + when {
                m == 0 -> "ровно"
                m < 10 -> "ноль " + spell(m.toLong(), G.M, Case.NOM)
                else -> spell(m.toLong(), G.M, Case.NOM)
            }
        }
        return t
    }

    private fun ranges(s: String) =
        Regex("(?<![\\p{L}\\d,.])(?:(от|с)\\s+)?(\\d+(?:[.,]\\d+)?)\\s?[–-]\\s?(\\d+(?:[.,]\\d+)?)(?![\\d])", RegexOption.IGNORE_CASE)
            .replace(s) { "${it.groupValues[1].ifEmpty { "от" }} ${it.groupValues[2]} до ${it.groupValues[3]}" }

    private val ORD_M = arrayOf("", "первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой", "восьмой", "девятый", "десятый")
    private val ORD_F = arrayOf("", "первая", "вторая", "третья", "четвёртая", "пятая", "шестая", "седьмая", "восьмая", "девятая", "десятая")
    private val ORD_F_OBL = arrayOf("", "первой", "второй", "третьей", "четвёртой", "пятой", "шестой", "седьмой", "восьмой", "девятой", "десятой")
    private val ORD_F_ACC = arrayOf("", "первую", "вторую", "третью", "четвёртую", "пятую", "шестую", "седьмую", "восьмую", "девятую", "десятую")
    private val ORD_M_GEN = arrayOf("", "первого", "второго", "третьего", "четвёртого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого")

    /** «3-й передаче» → «третьей передаче». */
    private fun ordinals(s: String) = Regex("(?<!\\d)(\\d{1,2})-(й|я|го|ю)(?![\\p{L}])(\\s+[\\p{L}]+)?").replace(s) {
        val n = it.groupValues[1].toInt()
        val next = it.groupValues[3]
        if (n !in 1..10) return@replace "${it.groupValues[1]}${next}"
        val w = next.trim().lowercase()
        val word = when {
            it.groupValues[2] == "го" -> ORD_M_GEN[n]
            it.groupValues[2] == "ю" -> ORD_F_ACC[n]
            it.groupValues[2] == "я" -> ORD_F[n]
            w.startsWith("передач") -> when {
                w.endsWith("у") -> ORD_F_ACC[n]
                w.endsWith("а") -> ORD_F[n]
                else -> ORD_F_OBL[n]
            }
            else -> ORD_M[n]
        }
        word + next
    }

    // ---- numbers ----

    enum class G { M, F }
    enum class Case { NOM, GEN, PREP, ACC }

    private val ONES = arrayOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val ONES_GEN = arrayOf("нуля", "одного", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти")
    private val ONES_PREP = arrayOf("нуле", "одном", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти")
    private val TEENS = arrayOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val TEENS_OBL = arrayOf("десяти", "одиннадцати", "двенадцати", "тринадцати", "четырнадцати", "пятнадцати", "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати")
    private val TENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val TENS_OBL = arrayOf("", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти", "семидесяти", "восьмидесяти", "девяноста")
    private val HUNDREDS = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val HUNDREDS_GEN = arrayOf("", "ста", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот", "восьмисот", "девятисот")
    private val HUNDREDS_PREP = arrayOf("", "ста", "двухстах", "трёхстах", "четырёхстах", "пятистах", "шестистах", "семистах", "восьмистах", "девятистах")

    /** 1, 21 → 0 («градус»); 2–4, 22 → 1 («градуса»); 0, 5–20 → 2 («градусов»). */
    fun plural(n: Long): Int {
        val a = n % 100
        return when {
            a in 11..14 -> 2
            a % 10 == 1L -> 0
            a % 10 in 2..4 -> 1
            else -> 2
        }
    }

    private fun one(d: Int, g: G, c: Case): String = when (c) {
        Case.NOM -> if (g == G.F && d == 1) "одна" else if (g == G.F && d == 2) "две" else ONES[d]
        Case.ACC -> if (g == G.F && d == 1) "одну" else if (g == G.F && d == 2) "две" else ONES[d]
        Case.GEN -> if (g == G.F && d == 1) "одной" else ONES_GEN[d]
        Case.PREP -> if (g == G.F && d == 1) "одной" else ONES_PREP[d]
    }

    private fun triplet(n: Int, g: G, c: Case): List<String> {
        val out = ArrayList<String>(3)
        val h = n / 100; val r = n % 100
        if (h > 0) out += when (c) { Case.GEN -> HUNDREDS_GEN[h]; Case.PREP -> HUNDREDS_PREP[h]; else -> HUNDREDS[h] }
        when {
            r in 10..19 -> out += if (c == Case.NOM || c == Case.ACC) TEENS[r - 10] else TEENS_OBL[r - 10]
            else -> {
                if (r >= 20) out += if (c == Case.NOM || c == Case.ACC) TENS[r / 10] else TENS_OBL[r / 10]
                if (r % 10 > 0) out += one(r % 10, g, c)
            }
        }
        return out
    }

    private val THOUSAND = mapOf(
        Case.NOM to arrayOf("тысяча", "тысячи", "тысяч"), Case.ACC to arrayOf("тысячу", "тысячи", "тысяч"),
        Case.GEN to arrayOf("тысячи", "тысяч", "тысяч"), Case.PREP to arrayOf("тысяче", "тысячах", "тысячах"),
    )
    private val MILLION = mapOf(
        Case.NOM to arrayOf("миллион", "миллиона", "миллионов"), Case.ACC to arrayOf("миллион", "миллиона", "миллионов"),
        Case.GEN to arrayOf("миллиона", "миллионов", "миллионов"), Case.PREP to arrayOf("миллионе", "миллионах", "миллионах"),
    )

    /** A whole number in words: [g] is the gender of what is counted, [c] the case the sentence puts it in. */
    fun spell(n: Long, g: G = G.M, c: Case = Case.NOM): String {
        if (n < 0) return "минус " + spell(-n, g, c)
        if (n == 0L) return one(0, g, c)
        if (n >= 1_000_000_000L) return n.toString().map { ONES[it - '0'] }.joinToString(" ")
        val out = ArrayList<String>()
        val mil = (n / 1_000_000).toInt(); val th = (n / 1000 % 1000).toInt(); val rest = (n % 1000).toInt()
        fun form(k: Int, f: Map<Case, Array<String>>) = f.getValue(c)[plural(k.toLong())]
        if (mil > 0) { out += triplet(mil, G.M, c).takeUnless { mil == 1 } ?: emptyList(); out += form(mil, MILLION) }
        if (th > 0) { out += triplet(th, G.F, c).takeUnless { th == 1 } ?: emptyList(); out += form(th, THOUSAND) }
        if (rest > 0) out += triplet(rest, g, c)
        return out.joinToString(" ")
    }

    /** A unit: its gender and forms (nominative singular, genitive singular and plural, prepositional singular and plural, accusative singular), and words that follow it unchanged. */
    private class Unit(val g: G, val nomSg: String, val genSg: String, val genPl: String, val prepSg: String, val prepPl: String,
                       val accSg: String = nomSg, val tail: String = "")

    private val DEG = Unit(G.M, "градус", "градуса", "градусов", "градусе", "градусах")
    private val VOLT = Unit(G.M, "вольт", "вольта", "вольт", "вольте", "вольтах")
    private val BAR = Unit(G.M, "бар", "бара", "бар", "баре", "барах")
    private val PCT = Unit(G.M, "процент", "процента", "процентов", "проценте", "процентах")
    private val KM = Unit(G.M, "километр", "километра", "километров", "километре", "километрах")
    private val KMH = Unit(G.M, "километр", "километра", "километров", "километре", "километрах", tail = " в час")
    private val MILE = Unit(G.F, "миля", "мили", "миль", "миле", "милях", "милю")
    private val MPH = Unit(G.F, "миля", "мили", "миль", "миле", "милях", "милю", tail = " в час")
    private val MPG = Unit(G.F, "миля", "мили", "миль", "миле", "милях", "милю", tail = " на галлон")
    private val LITRE = Unit(G.M, "литр", "литра", "литров", "литре", "литрах")
    private val L100 = Unit(G.M, "литр", "литра", "литров", "литре", "литрах", tail = " на сотню")
    private val REV = Unit(G.M, "оборот", "оборота", "оборотов", "обороте", "оборотах")
    private val RPM = Unit(G.M, "оборот", "оборота", "оборотов", "обороте", "оборотах", tail = " в минуту")
    private val SEC = Unit(G.F, "секунда", "секунды", "секунд", "секунде", "секундах", "секунду")
    private val MIN = Unit(G.F, "минута", "минуты", "минут", "минуте", "минутах", "минуту")
    private val MS = Unit(G.F, "миллисекунда", "миллисекунды", "миллисекунд", "миллисекунде", "миллисекундах", "миллисекунду")
    private val HOUR = Unit(G.M, "час", "часа", "часов", "часе", "часах")
    private val MHOUR = Unit(G.M, "моточас", "моточаса", "моточасов", "моточасе", "моточасах")
    private val DAY = Unit(G.M, "день", "дня", "дней", "дне", "днях")
    private val GS = Unit(G.M, "грамм", "грамма", "граммов", "грамме", "граммах", tail = " в секунду")
    private val KPA = Unit(G.M, "килопаскаль", "килопаскаля", "килопаскалей", "килопаскале", "килопаскалях")
    private val TIMES = Unit(G.M, "раз", "раза", "раз", "разе", "разах")
    private val MM = Unit(G.M, "миллиметр", "миллиметра", "миллиметров", "миллиметре", "миллиметрах")

    /** Unit symbols right after a number, longest first; the ones that are letters must not run into a word. */
    private val SYMBOLS = listOf(
        "°C" to DEG, "°С" to DEG, "°" to DEG, "км/ч" to KMH, "миль/ч" to MPH, "миль/гал" to MPG, "миль" to MILE, "л/100 км" to L100, "л/100км" to L100, "л/100" to L100,
        "об/мин" to RPM, "г/с" to GS, "кПа" to KPA, "%" to PCT, "км" to KM, "мин" to MIN, "сек" to SEC, "мс" to MS,
        "мм" to MM, "дн" to DAY, "ч" to HOUR, "с" to SEC, "В" to VOLT, "л" to LITRE,
    )

    /** Unit words already written out, in any form, so «91 градусов» comes out «девяносто один градус». */
    private val UNIT_WORDS: Map<String, Pair<Unit, Case?>> = buildMap {
        for (u in listOf(DEG, VOLT, BAR, PCT, KM, LITRE, REV, SEC, MIN, HOUR, MHOUR, DAY, TIMES, MS)) {
            for (f in listOf(u.nomSg, u.genSg, u.genPl, u.accSg)) putIfAbsent(f, u to null)
            put(u.prepSg, u to Case.PREP); put(u.prepPl, u to Case.PREP)
        }
        put("бар", BAR to null)
    }

    private val GEN_BEFORE = setOf("до", "от", "около", "выше", "ниже", "больше", "меньше", "свыше", "для", "из", "без", "после",
        "вместо", "кроме", "с", "со", "у", "против", "порядка", "менее", "более")
    private val PREP_BEFORE = setOf("при", "о", "об")
    private val ACC_BEFORE = setOf("на", "за", "через", "в", "во", "про")
    private val FEMININE = listOf("передач", "поездк", "ошибк", "недел", "тысяч", "минут", "секунд", "станци", "песн")

    private val NUM_RX = Regex("(?<![\\p{L}\\d.,])(\\d+)(?:[.,](\\d+))?(?![\\d])")
    private val WORD_BEFORE = Regex("([\\p{L}]+)\\s+(?:(?:минус|плюс)\\s+)?$")
    private val WORD_AFTER = Regex("^\\s+([\\p{L}]+)")

    private fun numbers(s: String): String {
        val b = StringBuilder()
        var pos = 0
        for (m in NUM_RX.findAll(s)) {
            if (m.range.first < pos) continue
            b.append(s, pos, m.range.first)
            var end = m.range.last + 1
            val before = WORD_BEFORE.find(s.substring(maxOf(0, m.range.first - 40), m.range.first))?.groupValues?.get(1)?.lowercase()
            var case = when (before) {
                in GEN_BEFORE -> Case.GEN
                in PREP_BEFORE -> Case.PREP
                in ACC_BEFORE -> Case.ACC
                else -> Case.NOM
            }
            // the unit: a symbol, or a unit word that may need another form
            var unit: Unit? = null
            val rest = s.substring(end)
            val gap = rest.takeWhile { it == ' ' }.let { if (it.length > 1) "" else it }
            for ((sym, u) in SYMBOLS) {
                val after = rest.removePrefix(gap)
                if (!after.startsWith(sym)) continue
                val next = after.getOrNull(sym.length)
                if (sym[0].isLetter() && next != null && next.isLetter()) continue
                if (sym == "с" && next != null && next !in ".,;:!?") continue
                if (sym == "В" && next != null && (next.isLetterOrDigit() || next == '-')) continue
                unit = u; end += gap.length + sym.length
                break
            }
            if (unit == null) WORD_AFTER.find(rest)?.let { w ->
                UNIT_WORDS[w.groupValues[1].lowercase()]?.let { (u, c) ->
                    unit = u; end += w.range.last + 1
                    if (c != null) case = c
                }
            }
            val gender = unit?.g ?: WORD_AFTER.find(rest)?.groupValues?.get(1)?.lowercase()
                ?.let { w -> if (FEMININE.any { w.startsWith(it) }) G.F else G.M } ?: G.M
            b.append(say(m.groupValues[1], m.groupValues[2], unit, gender, case))
            pos = end
        }
        b.append(s, pos, s.length)
        return b.toString()
    }

    /** Tenths, hundredths, thousandths: one in the nominative, the plural, one in the genitive or prepositional, one in the accusative. */
    private val FRACTION = arrayOf(arrayOf("десятая", "десятых", "десятой", "десятую"), arrayOf("сотая", "сотых", "сотой", "сотую"),
        arrayOf("тысячная", "тысячных", "тысячной", "тысячную"))

    /** A number with its unit: «12», «5» → «двенадцать с половиной»; the unit agrees with the number and case. */
    private fun say(whole: String, frac0: String, unit: Unit?, g: G, case: Case): String {
        var w = whole.trimStart('0').ifEmpty { "0" }
        var frac = frac0.trimEnd('0')
        if (unit === VOLT && frac.length > 1) { // «12,31 В»: a tenth is enough to hear
            val r = BigDecimal("$w.$frac").setScale(1, RoundingMode.HALF_UP).toPlainString().split('.')
            w = r[0]; frac = r.getOrElse(1) { "" }.trimEnd('0')
        }
        if (frac.length > 3) frac = frac.take(3)
        if (w.length > 15) return w.map { ONES[it - '0'] }.joinToString(" ") + (unit?.let { " " + it.genPl + it.tail } ?: "")
        val n = w.toLong()
        if (frac.isEmpty()) {
            val p = plural(n)
            val words = spell(n, g, case)
            val u = unit ?: return words
            val form = when (case) {
                Case.NOM -> arrayOf(u.nomSg, u.genSg, u.genPl)[p]
                Case.ACC -> arrayOf(u.accSg, u.genSg, u.genPl)[p]
                Case.GEN -> if (p == 0) u.genSg else u.genPl
                Case.PREP -> if (p == 0) u.prepSg else u.prepPl
            }
            return "$words $form${u.tail}"
        }
        val words = when {
            frac == "5" && n == 1L -> when (case) {
                Case.NOM, Case.ACC -> if (g == G.F) "полторы" else "полтора"
                else -> "полутора"
            }
            frac == "5" && n > 1 -> spell(n, g, case) + " с половиной"
            else -> {
                val f = frac.toLong()
                val fp = plural(f)
                val name = FRACTION[frac.length - 1]
                val fracName = if (fp != 0) name[1] else when (case) { Case.NOM -> name[0]; Case.ACC -> name[3]; else -> name[2] }
                val fracWords = spell(f, G.F, case)
                spell(n, G.F, case) + " и " + fracWords + " " + fracName
            }
        }
        val u = unit ?: return words
        // «полутора» and the genitive and prepositional of a fraction take the plural genitive: «до полутора градусов»
        val form = if (frac == "5" && n == 1L && case != Case.NOM && case != Case.ACC) u.genPl else u.genSg
        return "$words $form${u.tail}"
    }

    // ---- abbreviations, Latin ----

    /** Written as said; the keys are matched as whole words, in any case unless upper case only (see [CAPS_ONLY]). */
    private val ABBR = linkedMapOf(
        "ОЖ" to "охлаждающая жидкость", "ЭБУ" to "э-бэ-у", "АКПП" to "а-ка-пэ-пэ", "МКПП" to "эм-ка-пэ-пэ", "КПП" to "ка-пэ-пэ",
        "АКБ" to "а-ка-бэ", "ГБЦ" to "гэ-бэ-цэ", "ГРМ" to "гэ-эр-эм", "ТО" to "тэ-о", "АЗС" to "а-зэ-эс", "ДМРВ" to "дэ-эм-эр-вэ",
        "ШДК" to "шэ-дэ-ка", "ДТОЖ" to "датчик температуры охлаждающей жидкости", "ДПДЗ" to "дэ-пэ-дэ-зэ",
        "IAM" to "ай-эй-эм", "FBKC" to "эф-би-кей-си", "FLKC" to "эф-эл-кей-си", "AFR" to "а-эф-эр", "ATF" to "а-тэ-эф",
        "AVCS" to "эй-ви-си-эс", "GPS" to "джи-пи-эс", "EGT" to "и-джи-ти", "MAF" to "эм-эй-эф", "TPMS" to "ти-пи-эм-эс",
        "VDC" to "ви-ди-си", "OBD" to "о-би-ди", "USB" to "ю-эс-би", "CAN" to "кан", "VK" to "вэ-ка", "EJ" to "и-джей", "FB" to "эф-би",
        "FA" to "эф-эй", "DOHC" to "ди-оу-эйч-си", "SOHC" to "эс-оу-эйч-си",
        "Link" to "Линк", "Subaru" to "Субару", "Forester" to "Форестер", "Impreza" to "Импреза", "Legacy" to "Легаси",
        "Outback" to "Аутбэк", "Spotify" to "Спотифай", "Bluetooth" to "блютус", "Wi-Fi" to "вай-фай", "CANable" to "кэнэйбл",
        "N" to "нейтралку", "P" to "паркинг", "D" to "драйв", "R" to "задний ход",
    )
    private val CAPS_ONLY = setOf("ТО", "ОЖ", "N", "P", "D", "R", "CAN", "FA", "FB", "Link")
    private val ABBR_RX = ABBR.map { (k, v) ->
        Regex("(?<![\\p{L}\\d-])${Regex.escape(k)}(?![\\p{L}\\d])", if (k in CAPS_ONLY) emptySet() else setOf(RegexOption.IGNORE_CASE)) to v
    }

    private val RU_LETTERS = mapOf('А' to "а", 'Б' to "бэ", 'В' to "вэ", 'Г' to "гэ", 'Д' to "дэ", 'Е' to "е", 'Ж' to "жэ",
        'З' to "зэ", 'И' to "и", 'К' to "ка", 'Л' to "эль", 'М' to "эм", 'Н' to "эн", 'О' to "о", 'П' to "пэ", 'Р' to "эр",
        'С' to "эс", 'Т' to "тэ", 'У' to "у", 'Ф' to "эф", 'Х' to "ха", 'Ц' to "цэ", 'Ч' to "чэ", 'Ш' to "ша", 'Щ' to "ща",
        'Э' to "э", 'Ю' to "ю", 'Я' to "я")
    private val EN_LETTERS = mapOf('A' to "эй", 'B' to "би", 'C' to "си", 'D' to "ди", 'E' to "и", 'F' to "эф", 'G' to "джи",
        'H' to "эйч", 'I' to "ай", 'J' to "джей", 'K' to "кей", 'L' to "эл", 'M' to "эм", 'N' to "эн", 'O' to "оу", 'P' to "пи",
        'Q' to "кью", 'R' to "ар", 'S' to "эс", 'T' to "ти", 'U' to "ю", 'V' to "ви", 'W' to "дабл-ю", 'X' to "экс", 'Y' to "уай", 'Z' to "зед")
    private val TRANSLIT = listOf("sh" to "ш", "ch" to "ч", "th" to "т", "ph" to "ф", "oo" to "у", "ee" to "и", "kh" to "х",
        "zh" to "ж", "ck" to "к", "qu" to "кв", "a" to "а", "b" to "б", "c" to "к", "d" to "д", "e" to "е", "f" to "ф", "g" to "г",
        "h" to "х", "i" to "и", "j" to "дж", "k" to "к", "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п", "q" to "к",
        "r" to "р", "s" to "с", "t" to "т", "u" to "у", "v" to "в", "w" to "в", "x" to "кс", "y" to "и", "z" to "з")

    private fun words(s: String): String {
        var t = ABBR_RX.fold(s) { acc, (rx, v) -> rx.replace(acc, v) }
        // other upper-case abbreviations of up to four letters are spelled letter by letter
        t = Regex("(?<![\\p{L}])([А-ЯЁ]{2,4})(?![\\p{L}])").replace(t) { m ->
            val w = m.value
            if (w.count { it in "АЕЁИОУЫЭЮЯ" } > 1) w else w.map { RU_LETTERS[it] ?: it.lowercase() }.joinToString("-")
        }
        t = Regex("(?<![\\p{L}])([A-Z]{2,5})(?![\\p{L}])").replace(t) { m -> m.value.map { EN_LETTERS[it] ?: "" }.joinToString("-") }
        // any other Latin word read as it is written
        t = Regex("[A-Za-z]+").replace(t) { m ->
            var w = m.value.lowercase()
            val out = StringBuilder()
            while (w.isNotEmpty()) {
                val (k, v) = TRANSLIT.firstOrNull { w.startsWith(it.first) } ?: ("" to "")
                if (k.isEmpty()) { w = w.drop(1); continue }
                out.append(v); w = w.drop(k.length)
            }
            out.toString()
        }
        return t
    }

    private fun tidy(s: String): String = s
        .replace("%", " процентов").replace("°", " градусов").replace("/", " ").replace("&", " и ").replace("+", " плюс ")
        .replace(Regex("[^\\p{L}\\d\\s.,!?;:\\-]"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([.,!?;:])"), "$1")
        .replace(Regex("([,;:])(?:\\s*[,;:])+"), "$1")
        .replace(Regex("^[\\s,.;:]+"), "")
        .replace(Regex(",\\s*([.!?])"), "$1")
        .replace(Regex("[\\s,;:]+$"), "")
}
