package kg.forester.sg.service

import kotlin.math.abs

/*
 * Russian voice commands: the text Vosk heard (after the wake word) → what to do.
 * Pure Kotlin, no Android, so it can be tested on the desktop; Voice.kt maps the result to actions.
 *
 * How it matches: normalize (lower case, ё→е, spelled-out letters like "а з с" → "азс", filler words out),
 * then try the groups in a fixed order — dialog → navigation → volume → radio → media → values → screens → apps.
 * Words are compared by stem prefix, so every case form works ("песню/песни/песне", "заправку/заправки").
 * A stem written as "=слово" must match the whole word (for short words: "=еду" must not catch "едут").
 */

/** What the driver asked for. */
enum class VoiceIntent {
    // music and other audio
    PLAY, PAUSE, NEXT, PREV, RESTART, REWIND, FORWARD,
    SHUFFLE_ON, SHUFFLE_OFF, REPEAT_ON, REPEAT_OFF, LIKE, DISLIKE, WHAT_PLAYING,
    PLAY_QUERY,     // "включи песню …" — query + focus (+ app)
    MEDIA_APP,      // "открой яндекс музыку" — app
    SOURCE_BT, SOURCE_USB,

    // volume
    VOLUME_UP, VOLUME_DOWN, VOLUME_SET, MUTE, UNMUTE,

    // head-unit radio
    RADIO,          // just switch to the radio
    RADIO_FREQ,     // freq, MHz
    RADIO_PRESET,   // number, 1-based
    RADIO_NAME,     // query: station name ("европа плюс", "ретро фм")
    RADIO_NEXT, RADIO_PREV,
    RADIO_SEEK,     // number: +1 up, -1 down
    RADIO_BAND,     // target: "AM", "FM" or null (next band)

    // navigation
    NAV_ROUTE,      // query: where to (null: ask "Куда едем?"), via, app
    NAV_SEARCH,     // query: something to show on the map
    NAV_NEARBY,     // target: category ("АЗС"), query: what to search for
    NAV_HOME, NAV_WORK,
    NAV_SAVE_PLACE, // target: "home" / "work"
    NAV_CANCEL, NAV_TRAFFIC, NAV_ETA, NAV_WHERE,
    NAV_SOUND_OFF, NAV_SOUND_ON,
    NAV_OPEN,       // app: package if named

    // our own app
    SCREEN,         // target: a VoiceTarget name (HOME, DRIVE, FUEL, GEARBOX, BATTERY, DTC, MEDIA)
    FULL_SCREEN,    // target: "nav" = the navigator full screen, null = ours
    READ_VALUE,     // target: COOLANT, OIL, OILP, OUTSIDE, INTAKE, ATF, FUEL, RANGE, CONSUMPTION, BATTERY, SPEED, RPM, BOOST, DTC, TRIP, TIME, VOLUME
    APP_OPEN,       // query: app name as heard, resolved against shortcuts / installed apps

    // dialog
    CANCEL, HELP,
    UNSUPPORTED,    // understood but not possible (climate, phone, weather…): target = what
}

/** What a music search is about (maps to MediaStore.EXTRA_MEDIA_FOCUS). */
enum class MediaFocus { ANY, TRACK, ARTIST, ALBUM, PLAYLIST, GENRE, PODCAST, FAVORITES }

data class VoiceCommand(
    val intent: VoiceIntent,
    /** Destination, map search, song / artist / station name, or an app name. */
    val query: String? = null,
    /** Volume level or steps, radio preset, seek direction, seconds to skip. */
    val number: Int? = null,
    /** [number] is a percentage of the maximum volume. */
    val percent: Boolean = false,
    /** Radio frequency in MHz. */
    val freq: Double? = null,
    val focus: MediaFocus? = null,
    /** Screen, value, place category, "home"/"work", "one"/"all", band… (see [VoiceIntent]). */
    val target: String? = null,
    /** Route via ("через центр"). */
    val via: String? = null,
    /** Package of the app the driver named ("в спотифай", "в два гис"). */
    val app: String? = null,
    /** The normalized phrase, for logs. */
    val text: String = "",
)

/** The driver's own phrase and what it does: one or more ordinary commands, ";" between them. */
data class OwnCommand(val phrase: String, val action: String)

/** A number said at some position: its value, the index after it, and whether it was an ordinal ("третья"). */
data class SpokenNumber(val value: Int, val start: Int, val end: Int, val ordinal: Boolean)

object Commands {

    // --- normalization ---------------------------------------------------------------------------

    /** Spelled-out abbreviations and English / split spellings Vosk produces → one word. */
    private val JOINS = listOf(
        "а г з с" to "агзс", "а з с" to "азс", "а зэ эс" to "азс", "азэс" to "азс",
        "а ка пэ пэ" to "акпп", "а кэ пэ пэ" to "акпп", "кэ пэ пэ" to "кпп", "а ка бэ" to "акб", "а кэ бэ" to "акб",
        "а тэ эф" to "атф", "си ви ти" to "вариатор", "эс тэ о" to "сто", "тэ цэ" to "тц",
        "гэ и бэ дэ дэ" to "гибдд", "гэ а и" to "гаи", "дэ пэ эс" to "дпс",
        "эф эм" to "фм", "fm" to "фм", "эфэм" to "фм", "а эм" to "ам", "am" to "ам",
        "ю эс би" to "юсб", "usb" to "юсб", "джи пи эс" to "gps",
        "блю туз" to "блютуз", "блю тус" to "блютуз", "bluetooth" to "блютуз",
        "плей лист" to "плейлист", "play лист" to "плейлист", "playlist" to "плейлист",
        "ю туб" to "ютуб", "youtube" to "ютуб", "ютьюб" to "ютуб", "ютюб" to "ютуб",
        "вэ ка" to "вк", "ве ка" to "вк", "vk" to "вк", "в контакте" to "вконтакте",
        "два гис" to "2гис", "дубль гис" to "2гис", "2 гис" to "2гис", "2gis" to "2гис", "дубльгис" to "2гис", "дубль гис" to "2гис",
        "google" to "гугл", "гугол" to "гугл", "spotify" to "спотифай", "спотифи" to "спотифай",
        "waze" to "вейз", "вэйз" to "вейз", "вейс" to "вейз", "osmand" to "османд", "осм энд" to "османд", "осмэнд" to "османд",
        "мак дональдс" to "макдональдс", "чек энджин" to "чек", "чек инджин" to "чек",
        "мега герц" to "мгц", "мегагерц" to "мгц", "хит фм" to "хит фм",
        "эм ка эр" to "мкр", "мэ ка эр" to "мкр", "эм кэ эр" to "мкр", "эм ка эр эн" to "мкрн",
    )

    /** Inside-word spelling variants of the recogniser. */
    private val SPELLINGS = listOf("трэк" to "трек", "плэй" to "плей", "блютус" to "блютуз", "флэш" to "флеш", "мьюзик" to "музык")

    /** Words that carry no meaning in a command. */
    private val FILLERS = setOf(
        "пожалуйста", "плиз", "please", "давай", "давайте", "ка", "ну", "а", "ой", "эй", "слушай", "слушайте", "алло",
        "так", "вот", "ладно", "ок", "окей", "хорошо", "ага", "можешь", "можете", "мог", "могла", "бы", "ли",
        "ты", "вы", "же", "сейчас", "это", "эту", "этот", "эта", "будь", "добр", "добра", "любезен", "любезна",
        "срочно", "короче", "типа", "блин", "уже", "быстренько", "быстро",
    )

    /**
     * Street words → the words the rules know, so the meaning gets through: "включи музло" is "включи музыку",
     * "выруби" is "выключи", "погнали на хату" is "поехали домой".
     */
    private val SLANG = mapOf(
        "музло" to "музыку", "музон" to "музыку", "музончик" to "музыку", "музыченьку" to "музыку", "музычку" to "музыку",
        "музяку" to "музыку", "музыкой" to "музыку", "мьюзик" to "музыку", "музла" to "музыки", "музона" to "музыки",
        "песенку" to "песню", "песенки" to "песни", "трэчок" to "трек", "трекан" to "трек", "трекос" to "трек",
        "врубай" to "включи", "врубани" to "включи", "вруби" to "включи", "врубить" to "включи", "запили" to "включи",
        "запуляй" to "включи", "заводи" to "включи", "зарядь" to "включи", "газуй" to "включи",
        "выруби" to "выключи", "вырубай" to "выключи", "вырубить" to "выключи", "туши" to "выключи", "потуши" to "выключи",
        "глуши" to "выключи", "заглохни" to "пауза", "тормозни" to "пауза", "стопни" to "пауза", "стопэ" to "пауза",
        "скипни" to "следующую", "скипай" to "следующую", "скипнуть" to "следующую", "перекинь" to "следующую",
        "листани" to "следующую", "перелистни" to "следующую", "мотни" to "следующую",
        "радиво" to "радио", "радиву" to "радио", "радейку" to "радио", "радиоху" to "радио", "радивочку" to "радио", "радиоточку" to "радио",
        "погнали" to "поехали", "погнал" to "поехали", "гоним" to "поехали", "двинули" to "поехали", "валим" to "поехали", "рванули" to "поехали",
        "гони" to "поехали", "гоните" to "поехали", "гнать" to "поехали", "гоню" to "поехали", "мчи" to "поехали", "мчим" to "поехали",
        "летим" to "поехали", "полетели" to "поехали", "покатили" to "поехали", "катим" to "поехали", "дуй" to "поехали", "дуем" to "поехали",
        "двигай" to "поехали", "двигаем" to "поехали", "чеши" to "поехали", "топи" to "поехали",
        "хату" to "домой", "хате" to "домой", "хаты" to "домой", "домик" to "домой", "хаус" to "домой",
        "бенз" to "бензин", "бенза" to "бензина", "заправон" to "заправку", "заправочку" to "заправку", "запраку" to "заправку",
        "навик" to "навигатор", "навигашку" to "навигатор", "навигашка" to "навигатор",
        "громчей" to "громче", "громчее" to "громче", "погромчей" to "громче", "тишей" to "тише", "потишей" to "тише",
    )

    /**
     * Whole phrases that mean a plain command (what was meant, not what was said). Checked by stems, in order;
     * the first that fits replaces the phrase.
     */
    private val MEANINGS = listOf(
        listOf("скучн") to "включи музыку",
        listOf("=тишина") to "включи музыку",
        listOf("хочу", "музык") to "включи музыку",
        listOf("что", "нибудь", "послуша") to "включи музыку",
        listOf("надоел") to "следующая",
        listOf("=не", "то") to "следующая",
        listOf("друг", "песн") to "следующая",
        listOf("уши", "вян") to "тише",
        listOf("оглох") to "тише",
        listOf("орет") to "тише",
        listOf("=ору") to "тише",
        listOf("не", "слыш") to "громче",
        listOf("еле", "слыш") to "громче",
        listOf("кушать") to "хочу есть",
        listOf("голод") to "хочу есть",
        listOf("перекус") to "хочу есть",
        listOf("бензин", "кончает") to "найди ближайшую заправку",
        listOf("заправ", "надо") to "найди ближайшую заправку",
        listOf("горит", "лампочк") to "покажи ошибки",
        listOf("загорел", "чек") to "покажи ошибки",
    )

    private fun meaning(words: List<String>): String? = MEANINGS.firstOrNull { (stems, _) ->
        stems.all { st -> words.any { stemOk(it, st) } }
    }?.second?.takeIf { _ ->
        // only when the phrase isn't already a command of its own kind (a song name with "надоел" in it…)
        words.none { it in PLAY_VERBS } || words.size <= 3
    }

    /** Lower case, ё→е, no punctuation, abbreviations joined, fillers removed; "" if nothing is left. */
    fun normalize(text: String): String {
        var s = text.lowercase().replace('ё', 'е').replace('-', ' ')
        // a typed address: "65/2" keeps its fraction, "12мкр" is "12 мкр"
        s = Regex("(\\d)\\s*/\\s*(\\d)").replace(s) { "${it.groupValues[1]} дробь ${it.groupValues[2]}" }
        s = Regex("(\\d)(мкрн|мкр)").replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        s = Regex("(\\d)[,.](\\d)").replace(s) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        s = Regex("[^a-zа-я0-9. ]").replace(s, " ")
        s = Regex("(?<!\\d)\\.|\\.(?!\\d)").replace(s, " ")
        for ((a, b) in SPELLINGS) s = s.replace(a, b)
        s = " " + s.split(' ').filter { it.isNotBlank() }.joinToString(" ") + " "
        for ((a, b) in JOINS) s = s.replace(" $a ", " $b ")
        return s.split(' ').filter { it.isNotBlank() && it !in FILLERS }.map { SLANG[it] ?: it }.joinToString(" ")
    }

    // --- word helpers ----------------------------------------------------------------------------

    private fun stemOk(word: String, s: String) = if (s.startsWith("=")) word == s.substring(1) else word.startsWith(s)
    private fun List<String>.has(vararg s: String) = any { w -> s.any { stemOk(w, it) } }
    private fun List<String>.hasAny(s: Collection<String>) = any { w -> s.any { stemOk(w, it) } }
    private fun List<String>.firstIdx(s: Collection<String>, from: Int = 0): Int {
        for (i in from until size) if (s.any { stemOk(this[i], it) }) return i
        return -1
    }

    /** Consecutive words matching the space-separated stems of [p] (each stem is a prefix). */
    private fun List<String>.seqAt(p: List<String>, i: Int) = i + p.size <= size && p.indices.all { stemOk(this[i + it], p[it]) }
    private fun List<String>.seqIdx(p: String): Int {
        val ps = p.split(' ')
        for (i in indices) if (seqAt(ps, i)) return i
        return -1
    }
    private fun List<String>.seq(vararg p: String) = p.any { seqIdx(it) >= 0 }

    private fun List<String>.dropLead(lead: Set<String>): List<String> {
        var i = 0
        while (i < size && this[i] in lead) i++
        return drop(i)
    }

    // --- numbers ---------------------------------------------------------------------------------

    private val CARDINAL: Map<String, Int> = HashMap<String, Int>().apply {
        fun put(v: Int, vararg ws: String) = ws.forEach { this[it] = v }
        put(0, "ноль", "нуль", "нуля", "нулю")
        put(1, "один", "одна", "одно", "одну", "одного", "одной", "одним", "одному")
        put(2, "два", "две", "двух", "двум", "двумя", "пару", "пара")
        put(3, "три", "трех", "трем", "тремя")
        put(4, "четыре", "четырех", "четырем", "четырьмя")
        put(5, "пять", "пяти", "пятью"); put(6, "шесть", "шести", "шестью"); put(7, "семь", "семи")
        put(8, "восемь", "восьми", "восемью", "восьмью"); put(9, "девять", "девяти", "девятью")
        put(10, "десять", "десяти", "десятью"); put(11, "одиннадцать", "одиннадцати"); put(12, "двенадцать", "двенадцати")
        put(13, "тринадцать", "тринадцати"); put(14, "четырнадцать", "четырнадцати"); put(15, "пятнадцать", "пятнадцати")
        put(16, "шестнадцать", "шестнадцати"); put(17, "семнадцать", "семнадцати"); put(18, "восемнадцать", "восемнадцати")
        put(19, "девятнадцать", "девятнадцати"); put(20, "двадцать", "двадцати"); put(30, "тридцать", "тридцати")
        put(40, "сорок", "сорока"); put(50, "пятьдесят", "пятидесяти"); put(60, "шестьдесят", "шестидесяти")
        put(70, "семьдесят", "семидесяти"); put(80, "восемьдесят", "восьмидесяти"); put(90, "девяносто", "девяноста")
        put(100, "сто", "ста"); put(200, "двести", "двухсот"); put(300, "триста", "трехсот"); put(400, "четыреста", "четырехсот")
        put(500, "пятьсот", "пятисот"); put(600, "шестьсот"); put(700, "семьсот"); put(800, "восемьсот"); put(900, "девятьсот")
    }

    /** "первый/первая/первую…", "третий/третья/третью…", "двадцатый", "сотый". */
    private val ORDINAL: Map<String, Int> = HashMap<String, Int>().apply {
        val hard = listOf("ый", "ой", "ая", "ое", "ую", "ого", "ом", "ому", "ые", "ых", "ым")
        val stems = listOf(
            1 to "перв", 2 to "втор", 4 to "четверт", 5 to "пят", 6 to "шест", 7 to "седьм", 8 to "восьм", 9 to "девят",
            10 to "десят", 11 to "одиннадцат", 12 to "двенадцат", 13 to "тринадцат", 14 to "четырнадцат", 15 to "пятнадцат",
            16 to "шестнадцат", 17 to "семнадцат", 18 to "восемнадцат", 19 to "девятнадцат", 20 to "двадцат", 30 to "тридцат",
            40 to "сороков", 50 to "пятидесят", 60 to "шестидесят", 70 to "семидесят", 80 to "восьмидесят", 90 to "девяност", 100 to "сот",
        )
        for ((v, s) in stems) for (e in hard) this[s + e] = v
        for (e in listOf("ий", "ья", "ье", "ью", "ьего", "ьей", "ьем", "ьему", "ьи", "ьих")) this["трет$e"] = 3
    }

    /** The number spoken at [start] ("двадцать пять", "сто один", "25", "третья"), or null. */
    fun numberAt(w: List<String>, start: Int): SpokenNumber? {
        if (start !in w.indices) return null
        w[start].toIntOrNull()?.let { v ->
            // "25 я" (from "25-я") is an ordinal
            val ord = w.getOrNull(start + 1) in setOf("я", "й", "ая", "ой", "ый", "ую", "ю")
            return SpokenNumber(v, start, if (ord) start + 2 else start + 1, ord)
        }
        var j = start
        var total = 0
        var stage = 0 // 1 hundreds, 2 tens, 3 units/teens
        while (j < w.size) {
            val c = CARDINAL[w[j]]
            val o = ORDINAL[w[j]]
            val v = c ?: o ?: break
            val ok = when {
                v >= 100 -> stage < 1
                v >= 20 -> stage < 2 && v % 10 == 0
                v >= 10 -> stage < 2
                v == 0 -> j == start
                else -> stage < 3
            }
            if (!ok) break
            total += v
            j++
            if (c == null) return SpokenNumber(total, start, j, true)
            stage = when { v >= 100 -> 1; v >= 20 -> 2; else -> 3 }
            if (v == 0 || v in 10..19) break
        }
        return if (j > start) SpokenNumber(total, start, j, false) else null
    }

    /** The first number in the phrase. */
    fun firstNumber(w: List<String>, from: Int = 0): SpokenNumber? {
        for (i in from until w.size) numberAt(w, i)?.let { return it }
        return null
    }

    /** Nouns an ordinal before them is a number of: "двенадцатый микрорайон" → "12 микрорайон". */
    private val NUMBERED = listOf("микрорайон", "мкр", "квартал", "лини", "проезд", "=км", "километр", "участ", "массив")
    private val SLASH = setOf("дробь", "слеш", "слэш", "косая", "дробью")

    /**
     * "ленина десять дробь два" → "ленина 10/2": cardinals as digits (for addresses); ordinals too where they
     * number a district or a line ("двенадцатый микрорайон" → "12 микрорайон"), else they stay words ("первая улица").
     */
    fun numbersToDigits(w: List<String>): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            val n = numberAt(w, i)
            val numbered = n != null && n.ordinal && w.getOrNull(n.end)?.let { next -> NUMBERED.any { stemOk(next, it) } } == true
            if (n != null && (!n.ordinal || numbered) && w[i].toIntOrNull() == null) { out += n.value.toString(); i = n.end; continue }
            out += if (w[i] in SLASH) "/" else w[i]
            i++
        }
        return out.joinToString(" ").replace(" / ", "/")
    }

    private val DECIMAL_SEP = setOf("и", "точка", "запятая", "целых", "целая")
    private val FREQ_TAIL = setOf("десятых", "десятая", "сотых", "мгц", "фм")

    /** An FM frequency in the phrase: "сто один и семь" → 101.7, "девяносто восемь точка восемь", "101.7", "сто два". */
    fun findFreq(w: List<String>, context: Boolean): Double? {
        for (i in w.indices) {
            w[i].toDoubleOrNull()?.let { d -> if (d in 64.0..108.0 && (context || w[i].contains('.'))) return d }
            val n = numberAt(w, i) ?: continue
            if (n.ordinal || w[i].toIntOrNull() != null) continue
            var f = n.value.toDouble()
            var decimal = false
            if (w.getOrNull(n.end) in DECIMAL_SEP) {
                val d = numberAt(w, n.end + 1)
                if (d != null && !d.ordinal && d.value < 100) {
                    f += if (d.value < 10) d.value / 10.0 else d.value / 100.0
                    decimal = true
                }
            }
            if (f in 64.0..108.0 && (context || decimal)) return Math.round(f * 100) / 100.0
        }
        return null
    }

    // --- vocabularies ----------------------------------------------------------------------------

    /** Navigators and map apps by how people call them → package. */
    private val NAV_APPS = listOf(
        "яндекс навиг" to "ru.yandex.yandexnavi", "навигатор яндекс" to "ru.yandex.yandexnavi",
        "яндекс карт" to "ru.yandex.yandexmaps", "карт яндекс" to "ru.yandex.yandexmaps",
        "гугл карт" to "com.google.android.apps.maps", "гугл мап" to "com.google.android.apps.maps", "карт гугл" to "com.google.android.apps.maps",
        "2гис" to "ru.dublgis.dgismobile", "=гис" to "ru.dublgis.dgismobile",
        "вейз" to "com.waze", "османд" to "net.osmand.plus", "органик" to "app.organicmaps",
        "навител" to "com.navitel", "сыгик" to "com.sygic.aura", "сайджик" to "com.sygic.aura",
    )

    /**
     * Music apps → package. Not every brand is in the small Vosk dictionary ("спотифай", "вейз", "османд" are not):
     * those entries work for typed text and for whatever spelling is added once seen in «Услышано».
     */
    private val MUSIC_APPS = listOf(
        "яндекс музык" to "ru.yandex.music", "музык яндекс" to "ru.yandex.music",
        "спотиф" to "com.spotify.music",
        "=вк музык" to "com.uma.musicvk", "музык =вк" to "com.uma.musicvk", "вконтакт" to "com.uma.musicvk", "=бум" to "com.uma.musicvk",
        "ютуб музык" to "com.google.android.apps.youtube.music", "ютуб" to "com.google.android.youtube",
    )

    /** (first index, end index, package) of an app named in [w], with a "в/на/через/из" before it if there is one. */
    private fun findApp(w: List<String>, table: List<Pair<String, String>>): Triple<Int, Int, String>? {
        for (i in w.indices) for ((name, pkg) in table) {
            val p = name.split(' ')
            if (w.seqAt(p, i)) {
                val s = if (i > 0 && w[i - 1] in setOf("в", "во", "на", "через", "из", "по")) i - 1 else i
                return Triple(s, i + p.size, pkg)
            }
        }
        return null
    }

    private fun List<String>.without(r: Triple<Int, Int, String>?) = if (r == null) this else take(r.first) + drop(r.second)

    /** Place categories for "найди ближайшую …": canonical search text → stems. Order matters (АГЗС before АЗС). */
    private val PLACES: List<Pair<String, List<String>>> = listOf(
        "Электрозаправка" to listOf("электрозаправ", "электрозаряд", "зарядн", "зарядк", "электромобил"),
        "АГЗС" to listOf("агзс", "газов", "метан", "пропан", "=газ", "=газом"),
        "АЗС" to listOf("заправ", "азс", "бензоколон", "бензозаправ", "колонк", "лукойл", "роснефт", "газпром", "татнефт", "=шелл", "заправиться"),
        "Шиномонтаж" to listOf("шиномонтаж", "шиноремонт", "вулканизац", "=шины", "=шин", "=шину", "колес", "подкач", "прокол", "=пробил", "=пробили", "спустил", "спущ"),
        "Автомойка" to listOf("мойк", "автомойк", "помыть", "помыт", "=мыть", "=мойку"),
        "Автосервис" to listOf("автосервис", "=сервис", "автомастер", "мастерск", "техобслуж", "техцентр", "автотехцентр", "=сто", "ремонт", "эвакуатор"),
        "Дилер Subaru" to listOf("субару", "дилер"),
        "Парковка" to listOf("парковк", "стоянк", "паркинг", "припарков", "запарков"),
        "Автозапчасти" to listOf("запчаст", "автозапчаст", "автомагазин"),
        "Кофейня" to listOf("кофе", "кофейн"),
        "Фастфуд" to listOf("макдональдс", "макдак", "бургер", "шаурм", "фастфуд", "kfc", "=кфс"),
        "Ресторан" to listOf("ресторан"),
        "Кафе" to listOf("кафе", "столов", "поесть", "покушать", "перекус", "пообедать", "поужинать", "позавтракать", "=еда", "=еду", "чайхан"),
        "Аптека" to listOf("аптек"),
        "Больница" to listOf("больниц", "травмпункт", "поликлин", "госпитал", "медпункт", "клиник"),
        "Банкомат" to listOf("банкомат", "наличн"),
        "Банк" to listOf("=банк", "=банка", "=банке"),
        "Продукты" to listOf("продукт", "супермаркет", "магазин", "гипермаркет", "универсам"),
        "Торговый центр" to listOf("=тц", "торгов", "=молл"),
        "Рынок" to listOf("рынок", "рынк", "базар"),
        "Туалет" to listOf("туалет", "уборн"),
        "Гостиница" to listOf("гостиниц", "отел", "хостел", "переночев", "ночлег", "мотел"),
        "Полиция" to listOf("полиц", "=гаи", "гибдд", "=дпс", "милиц"),
        "Почта" to listOf("почт"),
        "Мечеть" to listOf("мечет"),
        "Храм" to listOf("храм", "церк", "собор"),
        "Кинотеатр" to listOf("кинотеатр"),
    )

    /** Names that must stay in a category search. */
    private val BRANDS = setOf("лукойл", "роснефт", "газпром", "татнефт", "шелл", "макдональдс", "макдак", "kfc", "кфс", "субару", "бургер")

    /** Words that may stand next to a category without making it a named place ("ближайшую круглосуточную заправку"). */
    private val PLACE_FILLER = setOf(
        "ближайшую", "ближайший", "ближайшая", "ближайшее", "ближайшие", "ближайшей", "ближайшего", "рядом", "поблизости", "недалеко",
        "вокруг", "возле", "около", "по", "пути", "дороге", "меня", "нас", "мне", "нам", "какую", "какой", "какое", "нибудь", "любую", "любой",
        "хорошую", "хороший", "круглосуточную", "круглосуточный", "круглосуточная", "открытую", "дешевую", "нормальную", "станцию", "станция",
        "станции", "пункт", "точку", "место", "где", "можно", "в", "на", "до", "к", "ко", "с", "здесь", "тут", "хочу", "хочется", "надо",
        "нужно", "нужна", "нужен", "есть", "добавь", "добавить", "найди", "найти", "ищи", "поищи", "покажи", "подскажи", "заедем", "заехать", "заскочим", "остановимся",
    )

    /** The category named in the phrase, with the words that named it. */
    private fun placeIn(w: List<String>): Pair<String, List<String>>? {
        if (w.seq("хочу есть", "хочется есть", "хотим есть", "хочу кушать", "хочу жрать")) return "Кафе" to PLACES.first { it.first == "Кафе" }.second
        return PLACES.firstOrNull { (_, stems) -> w.hasAny(stems) }
    }

    /** Music words: a phrase with them is not about navigation. */
    private val MUSIC_NOUNS = listOf(
        "песн", "песен", "трек", "композиц", "мелоди", "музык", "альбом", "исполнител", "артист", "певц", "певиц", "групп",
        "плейлист", "подборк", "сборник", "подкаст", "аудиокниг", "жанр", "клип", "=хиты", "=хит", "сингл",
        "радио", "авторадио", "станц", "волн", "=фм", "частот", "видео", "ролик", "ютуб",
    )

    /** Route verbs ("поехали в…", "проложи маршрут до…", "как доехать до…"). */
    private val ROUTE_VERBS = listOf(
        "=поехали", "=поехал", "=поехала", "=поехать", "=поедем", "=поеду", "=едем", "=едим", "=еду", "=идем", "=пошли", "=езжай",
        "=поезжай", "=погнали", "=вези", "=отвези", "=довези", "=подвези", "=веди", "=проведи", "=проложи", "=проложить", "=проложим",
        "=построй", "=построить", "=построим", "маршрут", "=навигация", "=навигацию", "=доехать", "=проехать", "=добраться",
        "=доберемся", "=доедем", "=попасть", "=отправляемся", "=направляемся", "=двигаемся", "=двигаем", "=дорогу", "=дорога",
        "=путь", "=рули", "=заедем", "=заедь", "=едь", "=поедь", "=прокладывай", "=строй",
    )

    /** Words before the destination itself. */
    private val ROUTE_LEAD = setOf(
        "поехали", "поехал", "поехала", "поехать", "поедем", "поеду", "едем", "едим", "еду", "идем", "пошли", "езжай", "поезжай",
        "погнали", "вези", "отвези", "довези", "подвези", "веди", "проведи", "проложи", "проложить", "проложим", "построй", "построить",
        "построим", "маршрут", "маршрута", "маршрутом", "навигация", "навигацию", "доехать", "проехать", "добраться", "доберемся",
        "доедем", "попасть", "отправляемся", "направляемся", "двигаемся", "двигаем", "дорогу", "дорога", "путь", "рули", "заедем", "заедь",
        "едь", "поедь", "прокладывай", "строй", "навигатор", "навигатора", "как", "покажи", "меня", "нас", "мне", "нам", "я", "мы", "нужно", "надо", "хочу", "хотим",
        "по", "адресу", "адрес", "до", "в", "во", "на", "к", "ко", "за", "мой", "моя", "новый",
    )

    private val PREPS = setOf("в", "во", "на", "до", "к", "ко")

    private val GENRES = listOf(
        "рок", "поп", "джаз", "блюз", "классик", "классическ", "рэп", "реп", "хип", "шансон", "электрон", "танцевальн", "диско",
        "метал", "панк", "регги", "кантри", "инди", "лаунж", "чилаут", "эмбиент", "фолк", "восточн", "кыргыз", "киргиз", "русск",
        "казахск", "узбекск", "ретро", "спокойн", "весел", "грустн", "лирическ", "романтич", "энергичн", "бодр", "детск", "новогодн",
        "колыбельн", "медленн", "быстр", "хиты", "популярн", "новинк", "дорог", "поездк", "бега", "тренировк", "восьмидесят", "девяност",
        "нулев", "советск", "эстрад", "саундтрек", "фонк", "транс", "хаус", "техно", "дабстеп",
    )

    private val PLAY_VERBS = setOf(
        "включи", "включить", "включай", "включите", "вруби", "врубай", "врубить", "поставь", "поставить", "ставь", "сыграй", "играй",
        "поиграй", "проиграй", "воспроизведи", "крути", "покрути", "заведи", "найди", "найти", "поищи", "ищи", "послушаем", "плей", "play",
    )

    /** Verbs that only open things. */
    private val OPEN_VERBS = setOf("открой", "открыть", "запусти", "запустить", "зайди", "перейди", "перейти", "покажи", "показать", "вызови")

    private val SCREENS = listOf(
        "FUEL" to listOf("расход", "топлив", "экономичн", "бензин"),
        "GEARBOX" to listOf("коробк", "акпп", "=кпп", "вариатор", "трансмисс", "=атф"),
        "BATTERY" to listOf("аккумулятор", "батаре", "=акб", "генератор"),
        "DTC" to listOf("ошибк", "неисправн", "диагност", "=чек", "=коды"),
        "DRIVE" to listOf("динамик", "наддув", "турбин", "=турбо", "=буст", "разгон"),
        "HOME" to listOf("главн", "прибор", "спидометр", "тахометр", "начальн"),
        "MEDIA" to listOf("медиа", "мультимедиа", "плеер", "проигрывател"),
    )

    private fun screenIn(w: List<String>) = SCREENS.firstOrNull { (_, stems) -> w.hasAny(stems) }?.first

    private val UNSUPPORTED = listOf(
        "climate" to listOf("кондиционер", "климат", "печк", "обогрев", "подогрев", "вентилятор", "обдув", "кондей"),
        "body" to listOf("=фары", "=фар", "габарит", "дворник", "стеклоочист", "=окно", "=окна", "стекл", "=люк", "=двери", "=дверь", "багажник", "сигнализац"),
        "phone" to listOf("позвони", "набери", "звонок", "вызов", "смс", "сообщени", "напиши", "ответь", "сбрось звонок"),
        "weather" to listOf("погод", "дожд", "прогноз"),
        "alarm" to listOf("будильник", "таймер", "напомни", "напоминани"),
        "talk" to listOf("анекдот", "шутк", "пошути", "расскажи", "стих", "сказк"),
    )

    private val CANCEL_WORDS = setOf(
        "отмена", "отмени", "отменить", "отбой", "ничего", "не", "надо", "нужно", "забудь", "неважно", "проехали", "спасибо",
        "благодарю", "все", "никуда", "нет", "ошибся", "ошиблась", "молчи", "свободен", "пока",
    )

    // --- entry point -----------------------------------------------------------------------------

    /** What [text] (the command after the wake word) asks for; null if nothing was understood. */
    fun parse(text: String): VoiceCommand? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        val w = t.split(' ')
        // what the phrase means, when it is said in other words
        meaning(w)?.let { m -> return rules(m.split(' '))?.copy(text = t) }
        rules(w)?.let { return it.copy(text = t) }
        // a word the recogniser got slightly wrong ("громкасть", "следущую"): the nearest known word, then once more
        val fixed = w.map { correct(it) }
        if (fixed != w) rules(fixed)?.let { return it.copy(text = t) }
        return null
    }

    private fun rules(w: List<String>): VoiceCommand? =
        dialog(w) ?: navigation(w) ?: volume(w) ?: radio(w) ?: media(w) ?: readValue(w) ?: screen(w) ?: openApp(w)

    /** The words commands are made of, for [correct]. */
    private val VOCAB by lazy { (listOf(
        "включи", "выключи", "следующую", "следующая", "предыдущую", "пауза", "продолжи", "громче", "тише", "громкость", "звук",
        "радио", "станцию", "частоту", "музыку", "песню", "трек", "перемешай", "повтори", "заправку", "маршрут", "поехали",
        "домой", "работу", "навигатор", "пробки", "температура", "бензина", "напряжение", "расход", "коробка", "аккумулятор",
        "ошибки", "открой", "покажи", "главную", "блютуз", "флешку", "избранное", "плейлист", "альбом", "исполнителя", "отмена",
    ) + PLAY_VERBS + OPEN_VERBS).toSet() }

    private fun correct(word: String): String {
        if (word.length < 5 || word in VOCAB || word.any { it.isDigit() }) return word
        val max = if (word.length >= 8) 2 else 1
        return VOCAB.filter { it[0] == word[0] && abs(it.length - word.length) <= max }
            .map { it to levenshtein(it, word) }.filter { it.second <= max }.minByOrNull { it.second }?.first ?: word
    }

    private fun cmd(i: VoiceIntent, query: String? = null, target: String? = null, number: Int? = null) =
        VoiceCommand(i, query = query?.takeIf { it.isNotBlank() }, target = target, number = number)

    // --- dialog, full screen, things we cannot do --------------------------------------------------

    private fun dialog(w: List<String>): VoiceCommand? {
        if (w.all { it in CANCEL_WORDS } && !(w.size == 1 && w[0] in setOf("не", "надо", "нужно"))) return cmd(VoiceIntent.CANCEL)
        if (w.has("умеешь", "умеете", "справк") || w.seq("какие команды", "что можно сказать", "список команд", "что говорить") ||
            (w.size <= 2 && w.has("помощь", "помоги", "=команды", "=помогите"))
        ) return cmd(VoiceIntent.HELP)
        val nav = w.has("навигат", "=карту", "=карта", "=карты", "2гис")
        if (w.seq("весь экран", "полный экран", "на весь") || w.has("полноэкран") || (w.has("разверни") && (w.size == 1 || nav)))
            return cmd(VoiceIntent.FULL_SCREEN, target = if (nav) "nav" else null)
        if (nav && w.has("закрой", "убери", "сверни", "скрой", "спрячь")) return cmd(VoiceIntent.FULL_SCREEN)
        UNSUPPORTED.firstOrNull { (_, stems) -> w.hasAny(stems) }?.let { return cmd(VoiceIntent.UNSUPPORTED, target = it.first) }
        return null
    }

    // --- navigation ------------------------------------------------------------------------------

    private val FUEL_WORDS = arrayOf("бензин", "топлив", "=бак", "=баке", "горюч", "солярк", "дизел", "заряд")

    private fun navigation(w: List<String>): VoiceCommand? {
        val off = w.has("выключ", "отключ", "убер", "замолч", "заткн", "=без", "выруб", "=mute", "мьют", "заглуш")
        // the navigator's voice
        if (w.has("навигат") && w.has("звук", "голос", "подсказ", "озвуч", "замолч", "молч", "заткн", "говор")) {
            return cmd(if (off || w.seq("не говори")) VoiceIntent.NAV_SOUND_OFF else VoiceIntent.NAV_SOUND_ON)
        }
        if (w.has("маршрут", "навигаци", "ведени") &&
            w.has("отмен", "сброс", "сбрось", "убер", "останов", "прекрат", "заверш", "законч", "выключ", "отключ", "=стоп", "отбой", "удали")
        ) return cmd(VoiceIntent.NAV_CANCEL)
        if (w.has("пробк", "загруженн", "затор") || w.seq("обстановк на дорог", "ситуаци на дорог", "что на дорог"))
            return cmd(VoiceIntent.NAV_TRAFFIC)
        if (!w.has(*FUEL_WORDS) && w.has("сколько", "когда", "скоро", "долго", "далеко", "=во") &&
            w.has("ехать", "приед", "доед", "прибуд", "прибыт", "добер", "осталось", "=пути", "=места", "=место", "=будем", "=км", "километр", "=до", "=еще")
        ) return cmd(VoiceIntent.NAV_ETA)
        val place = placeIn(w)
        if (place == null && (w.seq("где я", "где мы", "где нахожусь", "где находимся") || w.has("местоположен", "координат") ||
                (w.has("какой", "какая", "какое", "=что") && w.has("=город", "улиц", "=район", "=трасса", "=трассе"))) &&
            !w.has("поехали", "маршрут", "температур", "градус", "=холодно", "=жарко")
        ) return cmd(VoiceIntent.NAV_WHERE)
        if (w.has("запомн", "сохран", "отметь", "установи", "сделай") || w.seq("мой дом", "здесь дом", "тут дом", "моя работа")) {
            if (w.has("=дом", "=дома", "=домом", "=домашн")) return cmd(VoiceIntent.NAV_SAVE_PLACE, target = "home")
            if (w.has("работ", "=офис", "=офисом")) return cmd(VoiceIntent.NAV_SAVE_PLACE, target = "work")
        }
        // "найди песню…", "найди станцию…" are not about the map
        if (place == null && w.hasAny(MUSIC_NOUNS)) return null

        // open the navigator itself
        val app = findApp(w, NAV_APPS)
        val rest = w.without(app).dropLead(OPEN_VERBS + setOf("включи", "мне", "навигатор", "навигатора", "навигацию", "карту", "карты", "карта", "на", "весь", "экран"))
        if ((app != null || w.has("навигат", "=карту", "=карта", "=карты", "=навигация", "=навигацию")) && rest.isEmpty())
            return VoiceCommand(VoiceIntent.NAV_OPEN, app = app?.third)

        val near = w.has("ближайш", "рядом", "поблиз", "недалеко", "вокруг", "возле") || w.seq("по пути", "по дороге", "около меня")
        val routeAt = routeStart(w)
        if (place != null) {
            val content = w.without(app).filter { it !in PLACE_FILLER && it !in ROUTE_LEAD }
            // only the category is named → nearest one; "кафе пушкин" is a named place, routed / searched as said
            if (near || content.all { word -> place.second.any { stemOk(word, it) } }) {
                // a brand stays in the search ("заправку лукойл"), otherwise the plain category
                val q = if (content.any { word -> BRANDS.any { word.startsWith(it) } }) numbersToDigits(content) else place.first
                return VoiceCommand(VoiceIntent.NAV_NEARBY, query = q, target = place.first, app = app?.third)
            }
        }
        if (routeAt >= 0) {
            // the place said first, the verb last ("домой гони", "в аэропорт погнали"): the place goes after the verb
            val after = w.drop(routeAt + 1).filter { it !in ROUTE_LEAD }
            if (after.isEmpty() && routeAt > 0) return route(listOf(w[routeAt]) + w.take(routeAt))
            return route(w.drop(routeAt))
        }
        if (near) {
            val q = w.without(app).filter { it !in PLACE_FILLER }
            if (q.isNotEmpty()) return VoiceCommand(VoiceIntent.NAV_NEARBY, query = numbersToDigits(q), app = app?.third)
        }
        // "найди улицу ленина", "где находится вокзал", "покажи на карте …"
        val search = when {
            w[0] in setOf("найди", "найти", "ищи", "поищи", "отыщи") -> 1
            w.seq("где находится", "где находятся", "где тут", "где здесь") -> 2
            w.seq("покажи на карте") -> 3
            else -> -1
        }
        if (search > 0) {
            val q = w.drop(search).without(findApp(w.drop(search), NAV_APPS)).dropLead(setOf("мне", "адрес", "на", "карте"))
            if (q.isNotEmpty()) return VoiceCommand(VoiceIntent.NAV_SEARCH, query = numbersToDigits(q), app = app?.third)
        }
        // bare "домой", "на работу"
        val bare = w.dropLead(ROUTE_LEAD)
        if (bare.isNotEmpty() && bare.size <= 1 && w.size <= 3) homeOrWork(bare)?.let { return it }
        return null
    }

    /** Index of the route verb, or of "надо/хочу/мне" right before "в/на/до/к" or "домой"; -1 if not a route. */
    private fun routeStart(w: List<String>): Int {
        val i = w.firstIdx(ROUTE_VERBS)
        if (i in 0..3) return i
        for (k in 0 until minOf(w.size - 1, 3)) {
            if (w[k] in setOf("надо", "нужно", "хочу", "хотим", "мне", "нам", "давай", "навигатор", "навигатора") && (w[k + 1] in PREPS || w[k + 1] == "домой")) return k
        }
        return -1
    }

    private fun homeOrWork(dest: List<String>): VoiceCommand? {
        if (dest.isEmpty() || dest.size > 2) return null
        val d = dest.joinToString(" ")
        if (d in setOf("домой", "дом", "дома", "дому", "мой дом", "моему дому", "моего дома", "до дома")) return cmd(VoiceIntent.NAV_HOME)
        if (dest[0].startsWith("работ") || dest[0] in setOf("офис", "офиса", "офису") || d in setOf("мою работу", "моей работы", "мой офис"))
            return cmd(VoiceIntent.NAV_WORK)
        return null
    }

    private fun route(fromVerb: List<String>): VoiceCommand {
        var dest = fromVerb.dropLead(ROUTE_LEAD)
        val app = findApp(dest, NAV_APPS)
        dest = dest.without(app)
        var via: String? = null
        val v = dest.indexOf("через")
        if (v >= 0) {
            via = numbersToDigits(dest.drop(v + 1).dropLead(PREPS)).ifBlank { null }
            dest = dest.take(v)
        }
        homeOrWork(dest)?.let { return it.copy(via = via, app = app?.third) }
        if (dest == listOf("дальше")) return VoiceCommand(VoiceIntent.NAV_OPEN, app = app?.third) // "поехали дальше": back to the guidance
        return VoiceCommand(VoiceIntent.NAV_ROUTE, query = numbersToDigits(dest).ifBlank { null }, via = via, app = app?.third)
    }

    // --- volume ----------------------------------------------------------------------------------

    private fun volume(w: List<String>): VoiceCommand? {
        val noun = w.has("громкост", "звук")
        val louder = w.has("громче", "погромче", "громчей", "погромчей", "прибав", "=громко") && !w.seq("слишком громк", "очень громк", "не так громк") ||
            (noun && w.has("увелич", "подним", "подыми", "повыс", "добав", "=выше", "=больше", "сильнее", "=вверх", "=плюс")) ||
            w.seq("слишком тихо", "очень тихо", "не слышно", "плохо слышно", "ничего не слышно")
        val quieter = w.has("тише", "потише", "=тиш", "=тиши", "=тиша", "убав", "сбав", "приглуш") ||
            (noun && w.has("уменьш", "пониз", "опуст", "=ниже", "=меньше", "слабее", "=вниз", "=минус")) ||
            w.seq("слишком громк", "очень громк", "не так громк") || w == listOf("тихо")
        val n = firstNumber(w)?.takeIf { !it.ordinal }
        val toLevel = n != null && n.start > 0 && w[n.start - 1] == "до"
        val pct = w.has("процент")
        val step = when {
            w.has("чуть", "немн", "слегка", "капельк", "=малость", "=чуточку") -> 1
            w.has("намного", "гораздо", "=сильно", "значительн", "=очень", "=сильнее") -> 4
            else -> null
        }
        if ((louder || quieter) && !toLevel) {
            return VoiceCommand(if (louder) VoiceIntent.VOLUME_UP else VoiceIntent.VOLUME_DOWN, number = n?.value ?: step)
        }
        if (louder || quieter || noun) {
            if (w.has("максим", "=полную", "=всю", "=упор")) return VoiceCommand(VoiceIntent.VOLUME_SET, number = 100, percent = true)
            if (w.has("половин", "=середину")) return VoiceCommand(VoiceIntent.VOLUME_SET, number = 50, percent = true)
            if (w.has("миним")) return VoiceCommand(VoiceIntent.VOLUME_SET, number = 1)
            if (n != null) return if (n.value == 0) cmd(VoiceIntent.MUTE) else VoiceCommand(VoiceIntent.VOLUME_SET, number = n.value, percent = pct)
        }
        if (w.has("беззвуч", "заглуш", "мьют", "=mute", "тишин") || w.seq("без звука") ||
            (w.has("звук") && w.has("выключ", "отключ", "выруб", "убер", "убрать", "=выкл"))
        ) return cmd(VoiceIntent.MUTE)
        if (w.seq("со звуком") || w.has("размьют") || (w.has("звук") && w.has("включ", "верн", "вруб", "=вкл", "обратно")))
            return cmd(VoiceIntent.UNMUTE)
        if (w.has("громкост") && w.has("какая", "какой", "сколько")) return cmd(VoiceIntent.READ_VALUE, target = "VOLUME")
        if (w.has("громкост") && w.size == 1) return cmd(VoiceIntent.READ_VALUE, target = "VOLUME")
        return null
    }

    // --- radio -----------------------------------------------------------------------------------

    private val RADIO_LEAD = setOf(
        "включи", "включить", "включай", "поставь", "переключи", "переключись", "переключить", "открой", "запусти", "давай", "хочу",
        "послушать", "послушаем", "найди", "мне", "на", "радио", "радиостанцию", "радиостанция", "радиостанции", "станцию", "станция",
        "станции", "волну", "волна", "частоту", "частота", "фм", "номер", "пресет", "канал", "кнопку", "ячейку", "любимую", "любимая",
    )

    private fun radio(w: List<String>): VoiceCommand? {
        val myWave = w.has("волн") && w.has("=мою", "=моя", "=мой", "=моей")
        val ctx = !myWave && w.has("радио", "авторадио", "=фм", "частот", "диапазон", "пресет", "=ам", "станц", "волн")
        val onlyNumber = w.filter { CARDINAL[it] == null && it.toDoubleOrNull() == null } .all { it in setOf("включи", "поставь", "переключи", "на", "частоту", "и", "точка", "запятая") }
        val freq = findFreq(w, ctx)
        if (freq != null && (ctx || onlyNumber)) return VoiceCommand(VoiceIntent.RADIO_FREQ, freq = freq)
        if (!ctx) return null
        if (w.has("=что", "какая", "какое", "какой", "=чья", "назови", "скажи")) return cmd(VoiceIntent.WHAT_PLAYING, target = "radio")
        if (w.has("выключ", "отключ", "выруб", "останов", "=стоп", "убер")) return cmd(VoiceIntent.PAUSE, target = "radio")
        if (w.has("диапазон", "=ам") || w.seq("средн волн", "длинн волн", "коротк волн"))
            return cmd(VoiceIntent.RADIO_BAND, target = if (w.has("=ам", "средн", "длинн", "коротк")) "AM" else if (w.has("=фм")) "FM" else null)
        val station = w.has("станц", "волн", "радио", "канал")
        if (w.has("поиск", "найди", "найти", "ищи", "поищи", "скан", "автопоиск", "настрой"))
            return cmd(VoiceIntent.RADIO_SEEK, number = if (w.has("=назад", "=вниз", "обратн", "=ниже", "предыдущ")) -1 else 1)
        if (w.has("следующ", "=дальше", "=вперед", "=другую", "=другая") || (w.has("переключ") && w.has("станц", "волн") && !w.has("=на")))
            return cmd(VoiceIntent.RADIO_NEXT)
        if (w.has("предыдущ", "=назад", "прошл", "=обратно")) return cmd(VoiceIntent.RADIO_PREV)
        val rest = w.dropLead(RADIO_LEAD)
        val n = numberAt(rest, 0) ?: if (station || w.has("пресет", "номер")) firstNumber(w) else null
        if (n != null && n.value in 1..40) return cmd(VoiceIntent.RADIO_PRESET, number = n.value)
        if (rest.isNotEmpty() && rest.none { it in CARDINAL }) return cmd(VoiceIntent.RADIO_NAME, query = rest.joinToString(" "))
        return cmd(VoiceIntent.RADIO)
    }

    // --- media -----------------------------------------------------------------------------------

    private val FOCUS_NOUNS: List<Pair<String, MediaFocus>> = listOf(
        "=песни" to MediaFocus.ARTIST, "=треки" to MediaFocus.ARTIST, "=хиты" to MediaFocus.ARTIST, "=композиции" to MediaFocus.ARTIST,
        "песн" to MediaFocus.TRACK, "песенк" to MediaFocus.TRACK, "трек" to MediaFocus.TRACK, "композиц" to MediaFocus.TRACK,
        "мелоди" to MediaFocus.TRACK, "сингл" to MediaFocus.TRACK, "клип" to MediaFocus.TRACK,
        "альбом" to MediaFocus.ALBUM, "пластинк" to MediaFocus.ALBUM, "диск" to MediaFocus.ALBUM,
        "исполнител" to MediaFocus.ARTIST, "артист" to MediaFocus.ARTIST, "певц" to MediaFocus.ARTIST, "певиц" to MediaFocus.ARTIST,
        "групп" to MediaFocus.ARTIST, "рэпер" to MediaFocus.ARTIST, "музыкант" to MediaFocus.ARTIST,
        "плейлист" to MediaFocus.PLAYLIST, "подборк" to MediaFocus.PLAYLIST, "сборник" to MediaFocus.PLAYLIST, "микс" to MediaFocus.PLAYLIST,
        "подкаст" to MediaFocus.PODCAST, "аудиокниг" to MediaFocus.PODCAST, "книг" to MediaFocus.PODCAST,
        "видео" to MediaFocus.ANY, "ролик" to MediaFocus.ANY, "видос" to MediaFocus.ANY,
        "жанр" to MediaFocus.GENRE, "музык" to MediaFocus.ANY,
    )

    /** Small words between the noun and the name: "песню из альбома…", "группы под названием…". */
    private val FOCUS_GLUE = setOf("из", "от", "исполнении", "под", "названием", "называется", "мне", "нам", "что", "нибудь", "то", "какую", "любую", "немного", "послушать", "слушать")

    private fun media(w: List<String>): VoiceCommand? {
        val playIdx = w.indexOfFirst { it in PLAY_VERBS }.takeIf { it in 0..2 }
            ?: if (w.has("хочу", "хотим", "хочется") && w.has("послуш", "слуша")) w.firstIdx(listOf("послуш", "слуша")) else null
        // "включи песню «Дальше действовать будем мы»": a name follows the noun, so no "дальше" / "назад" tricks below
        val named = playIdx != null && w.drop(playIdx + 1).dropLead(FOCUS_GLUE).let { r ->
            r.size >= 2 && FOCUS_NOUNS.any { stemOk(r[0], it.first) && it.second != MediaFocus.ANY } && !r.has("повтор", "пауз", "перемеш", "следующ", "предыдущ")
        }
        if (!named) transport(w, playIdx)?.let { return it }
        return search(w, playIdx)
    }

    /** Play / pause / next / shuffle / repeat / what is playing… */
    private fun transport(w: List<String>, playIdx: Int?): VoiceCommand? {
        val asking = w.has("=что", "какая", "какой", "=чья", "=кто", "=как", "назови", "скажи")
        if (playIdx == null && asking && w.has("песн", "трек", "композиц", "мелоди", "=играет", "=звучит", "=поет", "исполня", "называ", "=играло", "=играл"))
            return cmd(VoiceIntent.WHAT_PLAYING)
        if (w.has("дизлайк") || w.seq("не нравится")) return cmd(VoiceIntent.DISLIKE)
        if ((w.has("лайк") && w.size <= 3) || (w.seq("мне нравится", "нравится") && playIdx == null && w.size <= 3) ||
            (w.has("добав", "сохран") && w.has("избранн", "любим", "понрав", "коллекц", "медиатек"))
        ) return cmd(VoiceIntent.LIKE)

        val offWords = w.has("выключ", "отключ", "убер", "отмен", "=без", "=не", "=хватит")
        val shuffleWord = w.has("перемеш", "вперемешку", "случайн", "шафл", "шаффл", "рандом")
        if (shuffleWord && offWords || w.seq("по порядку")) return cmd(VoiceIntent.SHUFFLE_OFF)
        if (shuffleWord) return cmd(VoiceIntent.SHUFFLE_ON)
        if (w.has("повтор") && offWords) return cmd(VoiceIntent.REPEAT_OFF)
        if (w.has("повторяй", "зацикл") || w.seq("на повтор") || (w.has("повтор") && w.has("режим", "включ", "постав")) || w.seq("повтор")) {
            val one = w.has("песн", "трек", "композиц", "=одну", "=один", "=одного")
            val all = w.has("плейлист", "альбом", "=все", "=всех", "=весь", "=всё")
            return cmd(VoiceIntent.REPEAT_ON, target = if (all) "all" else if (one) "one" else null)
        }
        // seconds to skip: "на тридцать секунд", "на минуту"
        val secs = firstNumber(w)?.let { n -> if (w.getOrNull(n.end)?.startsWith("минут") == true) n.value * 60 else n.value }
            ?: if (w.has("минут")) 60 else null
        if (w.has("перемот", "отмот", "промот", "мотни", "прокрут") || (w.has("секунд", "минут") && w.has("=вперед", "=назад"))) {
            return cmd(if (w.has("=назад", "обратн")) VoiceIntent.REWIND else VoiceIntent.FORWARD, number = secs)
        }
        if (w.has("сначала", "заново") || w.seq("с начала", "еще раз", "с самого начала") || (w.has("повтори") && w.has("песн", "трек", "композиц")))
            return cmd(VoiceIntent.RESTART)
        if (w.has("продолж", "возобнов") || w.seq("сними с пауз", "сними пауз", "убери пауз") || (w.has("играй", "=играть") && w.has("=дальше", "=далее")))
            return cmd(VoiceIntent.PLAY)
        if (w.has("следующ", "=дальше", "=далее", "=вперед", "скип", "пропуст", "=некст", "=next", "=skip") ||
            (w.has("переключ", "смени", "поменяй", "=другую", "=другой", "=другое") && (w.size == 1 || w.has("песн", "трек", "композиц", "мелоди", "=другую", "=другой", "=другое")))
        ) return cmd(VoiceIntent.NEXT)
        if (w.has("предыдущ", "=назад", "прошл", "=обратно") || (w.has("верн") && w.has("песн", "трек", "композиц")))
            return cmd(VoiceIntent.PREV)
        if (w.has("пауз", "=стоп", "останов", "приостанов", "замолч", "помолч", "=stop", "=pause") || w.seq("не играй") || w == listOf("выключи") || w == listOf("выключить") || (w.has("=хватит") && !w.has(*FUEL_WORDS)) ||
            (w.has("выключ", "отключ", "выруб", "убер") && w.has("музык", "песн", "плеер", "трек", "=все", "=всё"))
        ) return cmd(VoiceIntent.PAUSE)
        if (w.has("блютуз", "блютус") || w.seq("с телефона", "через телефон")) return cmd(VoiceIntent.SOURCE_BT)
        if (w.has("флешк", "флешь", "=юсб")) return cmd(VoiceIntent.SOURCE_USB)
        return null
    }

    /** "включи …": a song, artist, genre, playlist, favourites or a music app. */
    private fun search(w: List<String>, playIdx: Int?): VoiceCommand? {
        // which app, if one was named: "включи кино в спотифай", "открой яндекс музыку"
        val app = findApp(w, MUSIC_APPS)
        val opener = w.indexOfFirst { it in OPEN_VERBS }.takeIf { it in 0..1 && app != null }
        val video = w.has("видео", "ролик", "видос")
        val v = playIdx ?: opener ?: if (w[0] in setOf("запусти", "открой", "покажи") && w.hasAny(FOCUS_NOUNS.map { it.first })) 0 else null
        if (v == null) {
            if (w.size <= 2 && w.has("музык", "=плей", "=играй")) return cmd(VoiceIntent.PLAY)
            if (w.seq("моя волна", "мою волну")) return VoiceCommand(VoiceIntent.PLAY_QUERY, query = "Моя волна", focus = MediaFocus.FAVORITES, app = app?.third)
            return null
        }
        var rest = w.drop(v + 1).without(findApp(w.drop(v + 1), MUSIC_APPS)).dropLead(FOCUS_GLUE + setOf("включи", "поставь"))
        // a video with no app named is a YouTube one
        val pkg = app?.third ?: if (video) "com.google.android.youtube" else null
        // "поставь на двадцать": a bare number is the volume, not a song
        numberAt(rest.dropLead(setOf("на")), 0)?.takeIf { it.end == rest.dropLead(setOf("на")).size && !it.ordinal }
            ?.let { return VoiceCommand(VoiceIntent.VOLUME_SET, number = it.value) }
        if (rest.isEmpty()) return VoiceCommand(if (pkg != null) VoiceIntent.MEDIA_APP else VoiceIntent.PLAY, app = pkg)
        screenIn(rest)?.takeIf { rest.size <= 2 && !rest.has("музык", "плеер", "медиа") }?.let { return cmd(VoiceIntent.SCREEN, target = it) }

        // favourites: "мою волну", "мою музыку", "мои любимые песни", "мне нравится"
        if (rest.has("волн") && (rest.has("=мою", "=моя") || rest.size == 1))
            return VoiceCommand(VoiceIntent.PLAY_QUERY, query = "Моя волна", focus = MediaFocus.FAVORITES, app = pkg)
        if (rest.seq("мне нравится", "нравится") || rest.has("избранн", "любим", "понравивш", "лайкнут") ||
            (rest.has("=мою", "=мои", "=мой", "=моё", "=мое") && rest.size <= 2)
        ) return VoiceCommand(VoiceIntent.PLAY_QUERY, query = "Мне нравится", focus = MediaFocus.FAVORITES, app = pkg)

        // "песню / группу / альбом / плейлист …": remember what kind, then the name
        var focus: MediaFocus? = null
        while (rest.isNotEmpty()) {
            val f = FOCUS_NOUNS.firstOrNull { stemOk(rest[0], it.first) }?.second
            if (f != null) { if (focus == null || focus == MediaFocus.ANY || f != MediaFocus.ANY) focus = if (focus == MediaFocus.TRACK && f == MediaFocus.ARTIST) MediaFocus.ARTIST else f; rest = rest.drop(1); continue }
            if (rest[0] in FOCUS_GLUE) { rest = rest.drop(1); continue }
            break
        }
        if (rest.isEmpty() || rest.all { it in setOf("что", "нибудь", "то", "любую", "какую") })
            return VoiceCommand(VoiceIntent.PLAY, focus = focus?.takeIf { it != MediaFocus.ANY }, app = pkg)
        if ((focus == null || focus == MediaFocus.ANY) && rest.size <= 3 && rest.hasAny(GENRES)) focus = MediaFocus.GENRE
        if (focus == MediaFocus.ANY) focus = MediaFocus.GENRE // "спокойную музыку" never gets here; "музыку восьмидесятых" does
        return VoiceCommand(VoiceIntent.PLAY_QUERY, query = rest.joinToString(" "), focus = focus ?: MediaFocus.ANY, app = pkg)
    }

    // --- values the app can say ------------------------------------------------------------------

    private fun readValue(w: List<String>): VoiceCommand? {
        if (w.seq("который час", "сколько времени", "какое время", "сколько время") || w == listOf("время"))
            return cmd(VoiceIntent.READ_VALUE, target = "TIME")
        val ask = w.has("какая", "какой", "какое", "какие", "каков", "сколько", "скажи", "назови", "сообщи", "проверь", "узнай", "=что", "=есть", "=как")
        val show = w.any { it in OPEN_VERBS } || w.has("экран", "вкладк", "страниц")
        if (show && !ask) return null
        val temp = w.has("температур", "градус", "прогрел", "прогрет", "нагрел", "греется", "=холодно", "=жарко")
        val target = when {
            temp && (w.has("улиц", "=бортом", "снаружи", "=холодно", "=жарко")) -> "OUTSIDE"
            temp && w.has("коробк", "акпп", "вариатор", "=атф", "трансмисс") -> "ATF"
            temp && w.has("впуск", "воздух") -> "INTAKE"
            temp && w.has("масл") -> "OIL"
            temp -> "COOLANT"
            w.has("давлен") && w.has("масл") -> "OILP"
            w.seq("запас хода", "на сколько хватит", "насколько хватит") || (w.has("хватит", "проед", "доед", "=км", "километр") && w.has(*FUEL_WORDS)) -> "RANGE"
            w.has("расход") -> "CONSUMPTION"
            w.has(*FUEL_WORDS) -> "FUEL"
            w.has("напряжен", "вольт", "аккумулятор", "батаре", "=акб") -> "BATTERY"
            w.has("скорост") -> "SPEED"
            w.has("оборот") -> "RPM"
            w.has("наддув", "=буст", "турбин") -> "BOOST"
            w.has("ошибк", "неисправн") -> "DTC"
            w.has("проехал", "пробег", "километраж") -> "TRIP"
            else -> return null
        }
        val plain = target in setOf("COOLANT", "OUTSIDE", "INTAKE", "ATF", "OIL", "OILP", "SPEED", "RPM", "FUEL", "RANGE") ||
            (target == "BATTERY" && w.has("напряжен", "вольт"))
        if (!ask && !(plain && w.size <= 3)) return null
        return cmd(VoiceIntent.READ_VALUE, target = target)
    }

    // --- our screens and other apps --------------------------------------------------------------

    private fun screen(w: List<String>): VoiceCommand? {
        val s = screenIn(w) ?: return null
        // "открой расход", "покажи ошибки", "коробка", "экран аккумулятора"; long phrases are probably something else
        val content = w.filter { it !in OPEN_VERBS && it !in setOf("экран", "вкладку", "страницу", "на", "мне", "включи", "раздел") }
        return if (content.size <= 3) cmd(VoiceIntent.SCREEN, target = s) else null
    }

    private fun openApp(w: List<String>): VoiceCommand? {
        if (w[0] !in OPEN_VERBS && w[0] !in setOf("включи", "приложение")) return null
        val rest = w.drop(1).dropLead(setOf("приложение", "программу", "мне"))
        return if (rest.isEmpty()) null else cmd(VoiceIntent.APP_OPEN, query = rest.joinToString(" "))
    }

    // --- wake word -------------------------------------------------------------------------------

    /**
     * How vosk-model-small-ru-0.22 may hear "Форестер". The word itself is in its dictionary (as are "форест",
     * "фореста", "форрест", "форрестер", "форстер", "фостер", "форестье"); the model only ever prints dictionary
     * words, so other spellings ("фарестер") cannot come out of it and are caught by the edit distance instead.
     */
    val WAKE_ALIASES = mapOf(
        "форестер" to listOf(
            "форестер", "форестера", "форестом", "форест", "фореста", "форрест", "форрестер", "форрестера", "форстер",
            "фостер", "фостера", "форестье", "форест р", "фор естер", "форе стер",
        ),
    )

    /**
     * Index right after the wake word in [words], or -1. Accepts the known mishearings, a word close to it
     * (edit distance ≤ ¼ of its length) and the word split in two ("форе стер").
     */
    fun wakeEnd(words: List<String>, wake: String): Int {
        val wk = wake.trim().lowercase().replace('ё', 'е')
        val aliases = WAKE_ALIASES[wk].orEmpty()
        for (i in words.indices) {
            val one = words[i]
            if (close(one, wk) || one in aliases) return i + 1
            if (i + 1 < words.size) {
                val two = one + words[i + 1]
                if (close(two, wk) || "$one ${words[i + 1]}" in aliases) return i + 2
            }
        }
        return -1
    }

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        return levenshtein(a, b) <= maxOf(1, b.length / 4)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // --- the driver's own phrases ----------------------------------------------------------------

    /**
     * The own command [said] calls for, with its steps ready to run: the whole phrase said (words close enough for
     * the recogniser's spelling), else all of its words inside a longer command ("ну давай едем в гараж" for "едем
     * в гараж"); the longest wins. A "*" in the phrase takes any words ("покажи клип *"), and they replace the "*"
     * in the steps ("включи на ютубе *").
     */
    fun ownFor(said: String, own: List<OwnCommand>): Pair<OwnCommand, List<String>>? {
        val s = normalize(said).split(' ').filter { it.isNotBlank() }
        if (s.isEmpty()) return null
        return own.mapNotNull { o ->
            // normalize() drops punctuation, so the "*" passes it as a word of its own
            val p = normalize(o.phrase.replace("*", " qstarq ")).split(' ').filter { it.isNotBlank() }.map { if (it == "qstarq") "*" else it }
            val star = p.indexOf("*")
            when {
                p.isEmpty() || steps(o.action).isEmpty() -> null
                star >= 0 -> {
                    val pre = p.take(star); val post = p.drop(star + 1).filter { it != "*" }
                    val fits = s.size > pre.size + post.size && pre.indices.all { close(s[it], pre[it]) } &&
                        post.indices.all { close(s[s.size - post.size + it], post[it]) }
                    if (!fits || pre.size + post.size == 0) null
                    else Triple(o, 2 * (pre.size + post.size) + 1, s.subList(pre.size, s.size - post.size).joinToString(" "))
                }
                p.size == s.size && p.indices.all { close(s[it], p[it]) } -> Triple(o, 2 * p.size + 1, "")
                // one short word alone would fire inside any command
                (p.size >= 2 || p[0].length >= 5) && p.all { w -> s.any { close(it, w) } } -> Triple(o, 2 * p.size, "")
                else -> null
            }
        }.maxByOrNull { it.second }?.let { (o, _, words) -> o to steps(o.action).map { it.replace("*", words).trim() } }
    }

    private val APP_NAMES = mapOf(
        "ru.yandex.yandexnavi" to "Яндекс Навигатор", "ru.yandex.yandexmaps" to "Яндекс Карты", "com.google.android.apps.maps" to "Google Карты",
        "ru.dublgis.dgismobile" to "2ГИС", "com.waze" to "Waze", "net.osmand.plus" to "OsmAnd", "app.organicmaps" to "Organic Maps",
        "com.navitel" to "Навител", "com.sygic.aura" to "Sygic", "ru.yandex.music" to "Яндекс Музыка", "com.spotify.music" to "Spotify",
        "com.uma.musicvk" to "VK Музыка", "com.google.android.apps.youtube.music" to "YouTube Music", "com.google.android.youtube" to "YouTube",
    )

    private val SCREEN_NAMES = mapOf(
        "HOME" to "главный экран", "DRIVE" to "динамика", "FUEL" to "расход", "GEARBOX" to "АКПП", "BATTERY" to "АКБ",
        "DTC" to "ошибки", "MEDIA" to "медиа",
    )

    private val VALUE_NAMES = mapOf(
        "COOLANT" to "температура двигателя", "OIL" to "температура масла", "OILP" to "давление масла", "OUTSIDE" to "температура на улице",
        "INTAKE" to "температура впуска", "ATF" to "температура АКПП", "FUEL" to "остаток топлива", "RANGE" to "запас хода",
        "CONSUMPTION" to "расход", "BATTERY" to "напряжение", "SPEED" to "скорость", "RPM" to "обороты", "BOOST" to "наддув",
        "DTC" to "ошибки", "TRIP" to "пробег за поездку", "TIME" to "время", "VOLUME" to "громкость",
    )

    /** How the assistant took [c], in a few words for the own commands editor ("YouTube: поиск «король и шут»"). */
    fun describe(c: VoiceCommand): String {
        val q = c.query?.let { "«$it»" }.orEmpty()
        val inApp = c.app?.let { " в ${APP_NAMES[it] ?: it}" }.orEmpty()
        return when (c.intent) {
            VoiceIntent.PLAY_QUERY -> if (c.app == "com.google.android.youtube") "YouTube: поиск $q" else "включить музыку $q$inApp"
            VoiceIntent.MEDIA_APP -> "открыть ${c.app?.let { APP_NAMES[it] ?: it } ?: "плеер"}"
            VoiceIntent.PLAY -> "играть"
            VoiceIntent.PAUSE -> "пауза"
            VoiceIntent.NEXT, VoiceIntent.DISLIKE -> "следующий трек"
            VoiceIntent.PREV -> "предыдущий трек"
            VoiceIntent.RESTART -> "трек сначала"
            VoiceIntent.REWIND -> "назад на ${c.number ?: 15} с"
            VoiceIntent.FORWARD -> "вперёд на ${c.number ?: 15} с"
            VoiceIntent.SHUFFLE_ON -> "перемешать"
            VoiceIntent.SHUFFLE_OFF -> "по порядку"
            VoiceIntent.REPEAT_ON -> "повтор"
            VoiceIntent.REPEAT_OFF -> "без повтора"
            VoiceIntent.LIKE -> "нравится"
            VoiceIntent.WHAT_PLAYING -> "сказать, что играет"
            VoiceIntent.SOURCE_BT -> "музыка по Bluetooth"
            VoiceIntent.SOURCE_USB -> "музыка с флешки"
            VoiceIntent.VOLUME_UP -> "громче"
            VoiceIntent.VOLUME_DOWN -> "тише"
            VoiceIntent.VOLUME_SET -> "громкость ${c.number}${if (c.percent) "%" else ""}"
            VoiceIntent.MUTE -> "без звука"
            VoiceIntent.UNMUTE -> "вернуть звук"
            VoiceIntent.RADIO -> "радио"
            VoiceIntent.RADIO_FREQ -> "радио ${c.freq} МГц"
            VoiceIntent.RADIO_PRESET -> "радио, станция ${c.number}"
            VoiceIntent.RADIO_NAME -> "радио $q"
            VoiceIntent.RADIO_NEXT -> "следующая станция"
            VoiceIntent.RADIO_PREV -> "предыдущая станция"
            VoiceIntent.RADIO_SEEK -> "поиск станции"
            VoiceIntent.RADIO_BAND -> "диапазон ${c.target ?: "следующий"}"
            VoiceIntent.NAV_ROUTE -> if (c.query == null) "маршрут, спросит куда" else "маршрут: ${c.query}${c.via?.let { " через $it" }.orEmpty()}$inApp"
            VoiceIntent.NAV_SEARCH -> "найти на карте $q$inApp"
            VoiceIntent.NAV_NEARBY -> "ближайшее: ${c.target ?: c.query}$inApp"
            VoiceIntent.NAV_HOME -> "маршрут домой$inApp"
            VoiceIntent.NAV_WORK -> "маршрут на работу$inApp"
            VoiceIntent.NAV_SAVE_PLACE -> "запомнить ${if (c.target == "home") "дом" else "работу"}"
            VoiceIntent.NAV_CANCEL -> "отменить маршрут"
            VoiceIntent.NAV_TRAFFIC -> "показать пробки"
            VoiceIntent.NAV_ETA -> "сколько ехать"
            VoiceIntent.NAV_WHERE -> "где я"
            VoiceIntent.NAV_SOUND_OFF -> "выключить голос навигатора"
            VoiceIntent.NAV_SOUND_ON -> "включить голос навигатора"
            VoiceIntent.NAV_OPEN -> "открыть ${c.app?.let { APP_NAMES[it] ?: it } ?: "навигатор"}"
            VoiceIntent.SCREEN -> "экран: ${SCREEN_NAMES[c.target] ?: c.target}"
            VoiceIntent.FULL_SCREEN -> if (c.target == "nav") "навигатор на весь экран" else "на весь экран"
            VoiceIntent.READ_VALUE -> "сказать: ${VALUE_NAMES[c.target] ?: c.target}"
            VoiceIntent.APP_OPEN -> "открыть приложение $q"
            VoiceIntent.CANCEL -> "отмена"
            VoiceIntent.HELP -> "список команд"
            VoiceIntent.UNSUPPORTED -> "так не умеет"
        }
    }

    /** Ready own commands for the editor: a tap fills both fields. */
    val OWN_TEMPLATES = listOf(
        Triple("Клип на YouTube", "покажи клип *", "включи на ютубе * клип"),
        Triple("Песня в Яндекс Музыке", "поставь *", "включи * в яндекс музыке"),
        Triple("Домой с музыкой", "едем домой", "поехали домой; включи музыку"),
        Triple("Заправка", "кончается бензин", "найди ближайшую заправку"),
        Triple("Тихо", "ночной режим", "громкость 5"),
        Triple("Маршрут по адресу", "едем в гараж", "поехали на Ленина 10"),
    )

    /** The few pieces shown right under the «Сделать» field; the rest are in «Все команды». */
    val OWN_POPULAR = listOf(
        "Маршрут" to "поехали на Ленина 10", "Домой" to "поехали домой", "YouTube" to "включи на ютубе *", "Музыка" to "включи музыку",
    )

    /**
     * Everything the assistant can do, as pieces for the «Сделать» field, by group: a tap adds the piece; "*" is what
     * will be said in the phrase's "*". The self-test checks that every intent, value, screen and place is here.
     */
    val OWN_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Навигация" to listOf(
            "Маршрут по адресу" to "поехали на Ленина 10", "Маршрут куда скажу" to "поехали на *", "Через" to "поехали в аэропорт через центр",
            "Домой" to "поехали домой", "На работу" to "поехали на работу", "Спросить куда" to "поехали",
            "Найти на карте" to "найди улицу Ленина", "Сколько ехать" to "сколько ехать", "Пробки" to "покажи пробки",
            "Отменить маршрут" to "отмени маршрут", "Где я" to "где я", "Запомнить дом" to "запомни это место как дом",
            "Запомнить работу" to "запомни это место как работу", "Голос навигатора выкл" to "выключи звук навигатора",
            "Голос навигатора вкл" to "включи звук навигатора", "Открыть навигатор" to "открой навигатор",
            "Навигатор на весь экран" to "навигатор на весь экран", "В 2ГИС" to "поехали домой в 2гис",
            "В Яндекс Картах" to "открой яндекс карты", "В Google Картах" to "открой гугл карты",
        ),
        "Рядом" to listOf(
            "АЗС" to "найди ближайшую заправку", "Газ" to "найди газовую заправку рядом", "Электрозарядка" to "найди электрозаправку",
            "Шиномонтаж" to "найди шиномонтаж", "Мойка" to "найди автомойку", "Автосервис" to "найди автосервис",
            "Дилер Subaru" to "найди дилера субару", "Парковка" to "найди парковку", "Запчасти" to "найди автозапчасти",
            "Кофе" to "найди кофейню", "Фастфуд" to "найди фастфуд", "Ресторан" to "найди ресторан", "Кафе" to "найди кафе",
            "Аптека" to "найди аптеку", "Больница" to "найди больницу", "Банкомат" to "найди банкомат", "Банк" to "найди банк",
            "Продукты" to "найди супермаркет", "Торговый центр" to "найди торговый центр рядом", "Рынок" to "найди рынок",
            "Туалет" to "найди туалет", "Гостиница" to "найди гостиницу", "Полиция" to "найди полицию", "Почта" to "найди почту",
            "Мечеть" to "найди мечеть", "Храм" to "найди храм", "Кинотеатр" to "найди кинотеатр",
        ),
        "Музыка" to listOf(
            "Играть" to "включи музыку", "Пауза" to "пауза", "Следующий" to "следующий трек", "Предыдущий" to "предыдущий трек",
            "Трек сначала" to "с начала", "Назад 30 с" to "назад на 30 секунд", "Вперёд 30 с" to "вперед на 30 секунд",
            "Перемешать" to "перемешай", "По порядку" to "выключи перемешивание", "Повтор" to "включи повтор",
            "Без повтора" to "выключи повтор", "Нравится" to "мне нравится", "Не нравится" to "мне не нравится",
            "Что играет" to "что играет", "Песня" to "включи песню *", "Исполнитель" to "включи исполнителя *",
            "Альбом" to "включи альбом *", "Плейлист" to "включи плейлист *", "Избранное" to "включи избранное",
            "Подкаст" to "включи подкаст *", "Яндекс Музыка" to "включи * в яндекс музыке", "Spotify" to "включи * в спотифай",
            "VK" to "включи * в вк музыке", "YouTube" to "включи на ютубе *", "YouTube Music" to "включи * в ютуб музыке",
            "Открыть плеер" to "открой яндекс музыку", "Bluetooth" to "включи блютуз", "Флешка" to "включи флешку",
        ),
        "Громкость" to listOf(
            "Громче" to "громче", "Тише" to "тише", "Громкость 15" to "громкость 15", "Громкость 50%" to "громкость 50 процентов",
            "Без звука" to "выключи звук", "Вернуть звук" to "включи звук",
        ),
        "Радио" to listOf(
            "Радио" to "включи радио", "Частота" to "включи радио 101.7", "Станция №" to "включи третью станцию",
            "Станция по имени" to "включи радио европа плюс", "Следующая" to "следующая станция", "Предыдущая" to "предыдущая станция",
            "Поиск станции" to "поиск станции", "FM" to "переключи диапазон на фм", "AM" to "переключи на ам",
        ),
        "Значения" to listOf(
            "Двигатель" to "какая температура двигателя", "Масло" to "какая температура масла", "Давление масла" to "какое давление масла",
            "На улице" to "какая температура на улице", "Впуск" to "какая температура впуска", "Коробка" to "какая температура коробки",
            "Бензин" to "сколько бензина", "Запас хода" to "какой запас хода", "Расход" to "какой расход",
            "Напряжение" to "какое напряжение", "Скорость" to "какая скорость", "Обороты" to "какие обороты", "Наддув" to "какой наддув",
            "Ошибки" to "есть ошибки", "Пробег" to "сколько проехали", "Время" to "который час", "Громкость" to "какая громкость",
        ),
        "Экраны" to listOf(
            "Главный" to "открой главный экран", "Динамика" to "открой динамику", "Расход" to "открой расход", "АКПП" to "открой коробку",
            "АКБ" to "открой аккумулятор", "Ошибки" to "покажи ошибки", "Медиа" to "открой медиа", "На весь экран" to "на весь экран",
        ),
        "Приложения" to listOf(
            "Открыть приложение" to "открой телеграм", "Какое скажу" to "открой *",
        ),
    )

    /** The ordinary commands an own command runs, in order (";" or a new line between them). */
    fun steps(action: String): List<String> = action.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    // --- examples and self-test ------------------------------------------------------------------

    /** Phrases for the "Команды" panel (after the assistant's name). */
    val EXAMPLES = listOf(
        "поехали на Ленина 10" to "маршрут по адресу",
        "проложи маршрут домой / на работу" to "дом и работа",
        "найди ближайшую заправку" to "АЗС, шиномонтаж, парковка, кафе, аптека…",
        "сколько ехать / покажи пробки / отмени маршрут" to "навигатор",
        "громче / тише / громкость 15 / без звука" to "громкость",
        "следующая / пауза / играй / перемешай / что играет" to "музыка",
        "включи группу Кино / включи русский рок / мою волну" to "поиск музыки",
        "радио / следующая станция / поиск станции" to "радио магнитолы",
        "навигатор / весь экран / открой телеграм" to "кнопки быстрого запуска и приложения",
        "запомни это место как дом" to "чтобы потом «поехали домой»",
        "какая температура / сколько бензина / напряжение" to "значения",
        "расход / коробка / аккумулятор / ошибки" to "экраны",
    )

    private data class Case(
        val text: String, val intent: VoiceIntent?, val query: String? = null, val number: Int? = null,
        val target: String? = null, val freq: Double? = null, val focus: MediaFocus? = null, val via: String? = null, val app: String? = null,
    )

    private val CASES = listOf(
        // navigation
        Case("поехали на ленина десять", VoiceIntent.NAV_ROUTE, query = "ленина 10"),
        Case("Проложи маршрут до аэропорта Манас, пожалуйста", VoiceIntent.NAV_ROUTE, query = "аэропорта манас"),
        Case("построй маршрут в аэропорт через центр", VoiceIntent.NAV_ROUTE, query = "аэропорт", via = "центр"),
        Case("как доехать до ошского рынка", VoiceIntent.NAV_ROUTE, query = "ошского рынка"),
        Case("отвези меня домой", VoiceIntent.NAV_HOME),
        Case("едем на работу", VoiceIntent.NAV_WORK),
        Case("домой", VoiceIntent.NAV_HOME),
        Case("на работу", VoiceIntent.NAV_WORK),
        Case("поехали", VoiceIntent.NAV_ROUTE, query = null),
        Case("мне надо в аэропорт", VoiceIntent.NAV_ROUTE, query = "аэропорт"),
        Case("найди ближайшую заправку", VoiceIntent.NAV_NEARBY, target = "АЗС"),
        Case("где ближайшая аптека", VoiceIntent.NAV_NEARBY, target = "Аптека"),
        Case("поехали на заправку", VoiceIntent.NAV_NEARBY, target = "АЗС"),
        Case("найди шиномонтаж", VoiceIntent.NAV_NEARBY, target = "Шиномонтаж"),
        Case("хочу есть", VoiceIntent.NAV_NEARBY, target = "Кафе"),
        Case("где припарковаться", VoiceIntent.NAV_NEARBY, target = "Парковка"),
        Case("найди газовую заправку рядом", VoiceIntent.NAV_NEARBY, target = "АГЗС"),
        Case("поехали в кафе пушкин", VoiceIntent.NAV_ROUTE, query = "кафе пушкин"),
        Case("найди улицу киевская сто двадцать", VoiceIntent.NAV_SEARCH, query = "улицу киевская 120"),
        Case("проложи маршрут до дома в два гис", VoiceIntent.NAV_HOME, app = "ru.dublgis.dgismobile"),
        Case("сколько ехать", VoiceIntent.NAV_ETA),
        Case("когда приедем", VoiceIntent.NAV_ETA),
        Case("отмени маршрут", VoiceIntent.NAV_CANCEL),
        Case("покажи пробки", VoiceIntent.NAV_TRAFFIC),
        Case("какие пробки", VoiceIntent.NAV_TRAFFIC),
        Case("выключи звук навигатора", VoiceIntent.NAV_SOUND_OFF),
        Case("где я", VoiceIntent.NAV_WHERE),
        Case("открой навигатор", VoiceIntent.NAV_OPEN),
        Case("открой яндекс карты", VoiceIntent.NAV_OPEN, app = "ru.yandex.yandexmaps"),
        Case("запомни это место как дом", VoiceIntent.NAV_SAVE_PLACE, target = "home"),
        // volume
        Case("громче", VoiceIntent.VOLUME_UP),
        Case("сделай погромче", VoiceIntent.VOLUME_UP),
        Case("прибавь звук", VoiceIntent.VOLUME_UP),
        Case("чуть тише", VoiceIntent.VOLUME_DOWN, number = 1),
        Case("громче на пять", VoiceIntent.VOLUME_UP, number = 5),
        Case("убавь громкость на три", VoiceIntent.VOLUME_DOWN, number = 3),
        Case("громкость пятнадцать", VoiceIntent.VOLUME_SET, number = 15),
        Case("сделай громкость на двадцать пять", VoiceIntent.VOLUME_SET, number = 25),
        Case("громкость на максимум", VoiceIntent.VOLUME_SET, number = 100),
        Case("громкость пятьдесят процентов", VoiceIntent.VOLUME_SET, number = 50),
        Case("выключи звук", VoiceIntent.MUTE),
        Case("включи звук", VoiceIntent.UNMUTE),
        Case("слишком громко", VoiceIntent.VOLUME_DOWN),
        // radio
        Case("включи радио", VoiceIntent.RADIO),
        Case("радио сто один и семь", VoiceIntent.RADIO_FREQ, freq = 101.7),
        Case("поставь частоту девяносто восемь точка восемь", VoiceIntent.RADIO_FREQ, freq = 98.8),
        Case("фм сто два", VoiceIntent.RADIO_FREQ, freq = 102.0),
        Case("станция три", VoiceIntent.RADIO_PRESET, number = 3),
        Case("включи третью станцию", VoiceIntent.RADIO_PRESET, number = 3),
        Case("следующая радиостанция", VoiceIntent.RADIO_NEXT),
        Case("найди станцию", VoiceIntent.RADIO_SEEK, number = 1),
        Case("включи радио европа плюс", VoiceIntent.RADIO_NAME, query = "европа плюс"),
        Case("включи русское радио", VoiceIntent.RADIO_NAME, query = "русское радио"),
        Case("переключи на ам", VoiceIntent.RADIO_BAND, target = "AM"),
        // media
        Case("следующий трек", VoiceIntent.NEXT),
        Case("дальше", VoiceIntent.NEXT),
        Case("включи следующую песню", VoiceIntent.NEXT),
        Case("другую песню", VoiceIntent.NEXT),
        Case("предыдущий", VoiceIntent.PREV),
        Case("верни прошлую песню", VoiceIntent.PREV),
        Case("пауза", VoiceIntent.PAUSE),
        Case("поставь на паузу", VoiceIntent.PAUSE),
        Case("стоп", VoiceIntent.PAUSE),
        Case("выключи музыку", VoiceIntent.PAUSE),
        Case("играй", VoiceIntent.PLAY),
        Case("продолжи", VoiceIntent.PLAY),
        Case("включи музыку", VoiceIntent.PLAY),
        Case("включи что-нибудь", VoiceIntent.PLAY),
        Case("с начала", VoiceIntent.RESTART),
        Case("перемешай", VoiceIntent.SHUFFLE_ON),
        Case("выключи перемешивание", VoiceIntent.SHUFFLE_OFF),
        Case("поставь эту песню на повтор", VoiceIntent.REPEAT_ON, target = "one"),
        Case("перемотай вперед на тридцать секунд", VoiceIntent.FORWARD, number = 30),
        Case("что сейчас играет", VoiceIntent.WHAT_PLAYING),
        Case("кто поет", VoiceIntent.WHAT_PLAYING),
        Case("лайк", VoiceIntent.LIKE),
        Case("включи песню кукушка", VoiceIntent.PLAY_QUERY, query = "кукушка", focus = MediaFocus.TRACK),
        Case("включи группу кино", VoiceIntent.PLAY_QUERY, query = "кино", focus = MediaFocus.ARTIST),
        Case("поставь песни цоя", VoiceIntent.PLAY_QUERY, query = "цоя", focus = MediaFocus.ARTIST),
        Case("включи русский рок", VoiceIntent.PLAY_QUERY, query = "русский рок", focus = MediaFocus.GENRE),
        Case("включи плейлист для поездки", VoiceIntent.PLAY_QUERY, query = "для поездки", focus = MediaFocus.PLAYLIST),
        Case("найди песню дальше действовать будем мы", VoiceIntent.PLAY_QUERY, query = "дальше действовать будем мы", focus = MediaFocus.TRACK),
        Case("включи мою волну", VoiceIntent.PLAY_QUERY, query = "Моя волна", focus = MediaFocus.FAVORITES),
        Case("включи кино в спотифай", VoiceIntent.PLAY_QUERY, query = "кино", app = "com.spotify.music"),
        Case("открой яндекс музыку", VoiceIntent.MEDIA_APP, app = "ru.yandex.music"),
        Case("включи блютуз", VoiceIntent.SOURCE_BT),
        Case("музыку с флешки", VoiceIntent.SOURCE_USB),
        // values, screens, dialog
        Case("какая температура двигателя", VoiceIntent.READ_VALUE, target = "COOLANT"),
        Case("какая температура масла", VoiceIntent.READ_VALUE, target = "OIL"),
        Case("какое давление масла", VoiceIntent.READ_VALUE, target = "OILP"),
        Case("сколько бензина", VoiceIntent.READ_VALUE, target = "FUEL"),
        Case("сколько осталось бензина", VoiceIntent.READ_VALUE, target = "FUEL"),
        Case("на сколько хватит бензина", VoiceIntent.READ_VALUE, target = "RANGE"),
        Case("какое напряжение", VoiceIntent.READ_VALUE, target = "BATTERY"),
        Case("какой расход", VoiceIntent.READ_VALUE, target = "CONSUMPTION"),
        Case("есть ли ошибки", VoiceIntent.READ_VALUE, target = "DTC"),
        Case("который час", VoiceIntent.READ_VALUE, target = "TIME"),
        Case("расход", VoiceIntent.SCREEN, target = "FUEL"),
        Case("открой коробку", VoiceIntent.SCREEN, target = "GEARBOX"),
        Case("покажи ошибки", VoiceIntent.SCREEN, target = "DTC"),
        Case("аккумулятор", VoiceIntent.SCREEN, target = "BATTERY"),
        Case("на главную", VoiceIntent.SCREEN, target = "HOME"),
        Case("весь экран", VoiceIntent.FULL_SCREEN),
        Case("отмена", VoiceIntent.CANCEL),
        Case("ничего не надо", VoiceIntent.CANCEL),
        Case("что ты умеешь", VoiceIntent.HELP),
        Case("включи кондиционер", VoiceIntent.UNSUPPORTED, target = "climate"),
        // the meaning, said in other words
        Case("включи музло", VoiceIntent.PLAY),
        Case("врубай музон", VoiceIntent.PLAY),
        Case("мне скучно", VoiceIntent.PLAY),
        Case("выруби музыку", VoiceIntent.PAUSE),
        Case("скипни", VoiceIntent.NEXT),
        Case("надоела эта песня", VoiceIntent.NEXT),
        Case("уши вянут", VoiceIntent.VOLUME_DOWN),
        Case("еле слышно", VoiceIntent.VOLUME_UP),
        Case("врубай радиво", VoiceIntent.RADIO),
        Case("погнали на хату", VoiceIntent.NAV_HOME),
        Case("гони домой", VoiceIntent.NAV_HOME),
        Case("домой гони", VoiceIntent.NAV_HOME),
        Case("гони на работу", VoiceIntent.NAV_WORK),
        Case("гони на заправку", VoiceIntent.NAV_NEARBY, target = "АЗС"),
        Case("летим в аэропорт", VoiceIntent.NAV_ROUTE, query = "аэропорт"),
        Case("в аэропорт погнали", VoiceIntent.NAV_ROUTE, query = "аэропорт"),
        Case("построй маршрут двенадцатый микрорайон дом шестьдесят пять дробь два", VoiceIntent.NAV_ROUTE, query = "12 микрорайон дом 65/2"),
        Case("найди видео на ютубе мистер бист", VoiceIntent.PLAY_QUERY, query = "мистер бист", app = "com.google.android.youtube"),
        Case("найди на ютубе мистер бист", VoiceIntent.PLAY_QUERY, query = "мистер бист", app = "com.google.android.youtube"),
        Case("покажи видео мистер бист", VoiceIntent.PLAY_QUERY, query = "мистер бист", app = "com.google.android.youtube"),
        Case("включи на ютубе обзор форестера", VoiceIntent.PLAY_QUERY, query = "обзор форестера", app = "com.google.android.youtube"),
        Case("построй маршрут двенадцатый эм ка эр шестьдесят пять слеш два", VoiceIntent.NAV_ROUTE, query = "12 мкр 65/2"),
        Case("построй маршрут 12мкр дом 65/2", VoiceIntent.NAV_ROUTE, query = "12 мкр дом 65/2"),
        Case("на работу поехали", VoiceIntent.NAV_WORK),
        Case("бензин кончается", VoiceIntent.NAV_NEARBY, target = "АЗС"),
        Case("громкасть двадцать", VoiceIntent.VOLUME_SET, number = 20),
        Case("следущую песню", VoiceIntent.NEXT),
        Case("открой телеграм", VoiceIntent.APP_OPEN, query = "телеграм"),
        Case("", null),
    )

    /** Runs the sample phrases; returns the ones that came out wrong (empty = all good). */
    fun selfTest(): List<String> {
        val bad = ArrayList<String>()
        for (c in CASES) {
            val r = parse(c.text)
            val ok = if (c.intent == null) r == null else r != null && r.intent == c.intent &&
                (c.query == null || r.query == c.query) && (c.number == null || r.number == c.number) &&
                (c.target == null || r.target == c.target) && (c.freq == null || r.freq == c.freq) &&
                (c.focus == null || r.focus == c.focus) && (c.via == null || r.via == c.via) && (c.app == null || r.app == c.app) &&
                (c.intent != VoiceIntent.NAV_ROUTE || c.query != null || r.query == null)
            if (!ok) bad += "«${c.text}» → $r (ждали ${c.intent} ${listOfNotNull(c.query, c.number, c.target, c.freq, c.focus, c.via, c.app)})"
        }
        val wake = listOf("форестер громче", "фарестер громче", "форе стер громче", "форестор громче")
        for (p in wake) if (wakeEnd(p.split(' '), "форестер") != p.split(' ').size - 1) bad += "имя не узнано: «$p»"
        val own = listOf(
            OwnCommand("едем в гараж", "поехали на Ленина 10; включи музыку"),
            OwnCommand("дискотека", "включи радио европа плюс"),
            OwnCommand("газ", "громче"),
            OwnCommand("покажи клип *", "включи на ютубе * клип"),
        )
        val ownCases = listOf(
            "едем в гараж" to "едем в гараж", "ну давай едем в гараж" to "едем в гараж", "едим в гараж" to "едем в гараж",
            "дискотека" to "дискотека", "включи дискотеку" to "дискотека", "газ" to "газ", "газуй домой" to null,
            "поехали домой" to null, "покажи клип король и шут" to "покажи клип *", "покажи клип" to null,
        )
        for ((said, want) in ownCases) {
            val got = ownFor(said, own)?.first?.phrase
            if (got != want) bad += "своя команда «$said» → $got (ждали $want)"
        }
        if (ownFor("едем в гараж", own)?.second != listOf("поехали на Ленина 10", "включи музыку")) bad += "шаги своей команды: ${ownFor("едем в гараж", own)?.second}"
        val clip = ownFor("покажи клип король и шут", own)?.second?.singleOrNull()
        val yt = clip?.let { parse(it) }
        if (clip != "включи на ютубе король и шут клип" || yt?.intent != VoiceIntent.PLAY_QUERY || yt.app != "com.google.android.youtube")
            bad += "своя команда со звёздочкой: «$clip» → $yt"
        // every template and piece must be understood, and together the pieces must show everything the assistant can do
        val texts = OWN_TEMPLATES.flatMap { steps(it.third) } + OWN_GROUPS.flatMap { g -> g.second.map { it.second } }
        val got = texts.mapNotNull { t -> parse(t.replace("*", "кино")).also { if (it == null) bad += "подсказка не понята: «$t»" } }
        val skip = setOf(VoiceIntent.CANCEL, VoiceIntent.HELP, VoiceIntent.UNSUPPORTED)
        (VoiceIntent.entries - skip - got.map { it.intent }.toSet()).forEach { bad += "нет подсказки для $it" }
        val values = listOf("COOLANT", "OIL", "OILP", "OUTSIDE", "INTAKE", "ATF", "FUEL", "RANGE", "CONSUMPTION", "BATTERY", "SPEED", "RPM", "BOOST", "DTC", "TRIP", "TIME", "VOLUME")
        (values - got.filter { it.intent == VoiceIntent.READ_VALUE }.mapNotNull { it.target }.toSet()).forEach { bad += "нет подсказки для значения $it" }
        (SCREEN_NAMES.keys - got.filter { it.intent == VoiceIntent.SCREEN }.mapNotNull { it.target }.toSet()).forEach { bad += "нет подсказки для экрана $it" }
        (PLACES.map { it.first } - got.filter { it.intent == VoiceIntent.NAV_NEARBY }.mapNotNull { it.target }.toSet()).forEach { bad += "нет подсказки для места $it" }
        return bad
    }
}
