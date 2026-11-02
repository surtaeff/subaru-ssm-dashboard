package kg.forester.sg.service

import kg.forester.sg.data.DistanceUnits
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * English voice commands: the text Vosk heard (after the wake word) → what to do. The twin of [Commands], with the
 * same intents, targets and order of groups (dialog → navigation → volume → radio → media → values → screens → apps),
 * written for how English is said rather than translated. Pure Kotlin, no screen, tested on the desktop.
 *
 * English has next to no word endings, so words are compared whole, not by stem as in Russian. Word pairs that mean one
 * thing ("gas station", "turn off") are joined into one word first, and "turn the radio off" is put in the same order.
 */

/** What [Voice] and the settings screen need of a language's grammar. */
interface VoiceGrammar {
    val EXAMPLES: List<Pair<String, String>>
    val OWN_TEMPLATES: List<Triple<String, String, String>>
    val OWN_POPULAR: List<Pair<String, String>>
    val OWN_GROUPS: List<Pair<String, List<Pair<String, String>>>>

    /** The example in the «try a command» field. */
    val SAMPLE_ASK: String

    /** The examples in the own command's two fields. */
    val SAMPLE_PHRASE: String
    val SAMPLE_ACTION: String

    /** A word that stands for "*" when a step with one is checked. */
    val STAR_WORD: String

    fun parse(text: String): VoiceCommand?
    fun describe(c: VoiceCommand): String
    fun ownFor(said: String, own: List<OwnCommand>): Pair<OwnCommand, List<String>>?
    fun steps(action: String): List<String> = Commands.steps(action)
}

object CommandsEn : VoiceGrammar {

    // --- normalization ---------------------------------------------------------------------------

    private val CONTRACTIONS = listOf(
        "what's" to "what is", "whats" to "what is", "where's" to "where is", "wheres" to "where is", "who's" to "who is",
        "how's" to "how is", "it's" to "it is", "that's" to "that is", "there's" to "there is", "i'm" to "i am", "i'd" to "i would",
        "we're" to "we are", "don't" to "do not", "dont" to "do not", "doesn't" to "does not", "can't" to "cannot", "cant" to "cannot",
        "isn't" to "is not", "let's" to "lets",
    )

    /** Word pairs that mean one thing, and spellings the recogniser splits. Longer ones first. */
    private val JOINS = listOf(
        "you tube" to "youtube", "blue tooth" to "bluetooth", "play list" to "playlist", "u s b" to "usb", "f m" to "fm", "a m" to "am",
        "a t f" to "atf", "c v t" to "cvt", "r p m" to "rpm", "m p g" to "mpg", "e t a" to "eta", "k f c" to "kfc", "a t m" to "atm",
        "a c" to "ac", "a/c" to "ac", "air conditioning" to "ac", "air conditioner" to "ac", "d t c" to "dtc",
        "two gis" to "2gis", "to gis" to "2gis", "2 gis" to "2gis", "double gis" to "2gis", "spot if i" to "spotify", "o s m and" to "osmand",
        "fast forward" to "fastforward", "check engine" to "checkengine", "gear box" to "gearbox", "mega hertz" to "fm",
        "full screen" to "fullscreen", "turn on" to "turnon", "switch on" to "turnon", "put on" to "puton",
        "turn off" to "turnoff", "switch off" to "turnoff", "shut off" to "turnoff", "shut up" to "shutup", "be quiet" to "shutup", "hold on" to "shutup",
        "turn it up" to "turnup", "turn this up" to "turnup", "turn that up" to "turnup", "turn up" to "turnup", "crank it up" to "turnup", "pump it up" to "turnup",
        "turn it down" to "turndown", "turn this down" to "turndown", "turn that down" to "turndown", "turn down" to "turndown",
        "gas station" to "gasstation", "petrol station" to "gasstation", "filling station" to "gasstation", "fuel station" to "gasstation", "service station" to "gasstation",
        "charging station" to "evcharger", "ev charger" to "evcharger", "electric charger" to "evcharger", "charging point" to "evcharger", "e v charger" to "evcharger",
        "tire shop" to "tireshop", "tire repair" to "tireshop", "flat tire" to "tireshop", "car wash" to "carwash",
        "car service" to "mechanic", "auto repair" to "mechanic", "car repair" to "mechanic", "repair shop" to "mechanic", "service center" to "mechanic", "tow truck" to "mechanic",
        "car park" to "parking", "where can i park" to "find parking", "place to park" to "parking",
        "auto parts" to "carparts", "car parts" to "carparts", "parts store" to "carparts", "coffee shop" to "coffee",
        "fast food" to "fastfood", "burger king" to "burgerking", "cash machine" to "atm", "emergency room" to "hospital",
        "shopping center" to "mall", "shopping mall" to "mall", "post office" to "postoffice", "movie theater" to "cinema",
    )

    /** How the small model really prints some commands. Run on what it heard for several synthetic voices. */
    private val MISHEARD = listOf(
        "soon to" to "tune to", "open feel" to "open fuel", "cancel the road" to "cancel the route", "cancel the rude" to "cancel the route",
        "a dm" to "atm", "eight am" to "atm", "on you to" to "on youtube", "and you to" to "in youtube", "in you to" to "in youtube",
        "u s be" to "usb", "us be" to "usb", "spotted fi" to "spotify", "turnon the sand" to "turnon the sound", "turnoff the sand" to "turnoff the sound",
    )

    /** "What's" at the start of a command, as the model prints it for an English voice that isn't American. */
    private val WHATS_MISHEARD = Regex("^ (?:once|wants|whoops) (the|my|to) ")

    /** "turn the radio off" → "turnoff the radio". */
    private val TURN_AROUND = Regex(" (?:turn|switch) ((?:the |my |this |that )?[a-z0-9]+) (on|off) ")

    /** Words that carry no meaning in a command. */
    private val FILLERS = setOf(
        "please", "kindly", "okay", "ok", "hey", "hi", "hello", "um", "uh", "er", "erm", "ah", "yeah", "yep", "well", "man", "dude", "buddy",
        "just", "actually", "maybe", "quickly", "now",
    )

    /** How a request starts, before the request itself. */
    private val LEADS = listOf(
        "can you", "could you", "would you", "will you", "can we", "could we", "i want to", "i would like to", "i would like", "i wanna",
        "i want", "i need to", "i need", "we need to", "we need", "i have to", "we have to", "lets", "let us", "let me", "go ahead and", "how about", "do you mind", "oh",
    ).map { it.split(' ') }

    /** Other words for what the rules know. */
    private val SLANG = mapOf(
        "tunes" to "music", "jams" to "music", "softer" to "quieter", "favourite" to "favorite", "favourites" to "favorites",
        "tyre" to "tire", "tyres" to "tires", "tireshop" to "tireshop", "kilometres" to "kilometers", "kilometre" to "kilometer", "litres" to "liters",
        "centre" to "center", "theatre" to "theater", "unpause" to "resume", "playback" to "play", "chemist" to "pharmacy", "drugstore" to "pharmacy",
    )

    /** Whole sentences that mean a plain command: what was meant, not what was said. All the words of an entry must be in. */
    private val MEANINGS = listOf(
        listOf("bored") to "play music",
        listOf("silence", "boring") to "play music",
        listOf("sick", "song") to "next song",
        listOf("too", "loud") to "quieter",
        listOf("too", "quiet") to "louder",
        listOf("cannot", "hear") to "louder",
        listOf("hungry") to "find food",
        listOf("starving") to "find food",
        listOf("need", "gas") to "find the nearest gas station",
        listOf("need", "fuel") to "find the nearest gas station",
        listOf("low", "gas") to "find the nearest gas station",
        listOf("low", "fuel") to "find the nearest gas station",
        listOf("out", "gas") to "find the nearest gas station",
        listOf("out", "fuel") to "find the nearest gas station",
        listOf("checkengine", "light") to "show codes",
        listOf("engine", "light") to "show codes",
        listOf("warning", "light") to "show codes",
    )

    private fun meaning(words: List<String>): String? = MEANINGS.firstOrNull { (need, _) -> need.all { it in words } }?.second
        ?.takeIf { words.none { it in PLAY_VERBS && it != "hear" } || words.size <= 3 }

    /** Lower case, contractions out, pairs joined, fillers out: what is left of a sentence before its request starts. */
    private fun expand(text: String): List<String> {
        var s = text.lowercase(Locale.ROOT).replace('’', '\'').replace('-', ' ')
        s = Regex("(\\d)\\s*/\\s*(\\d)").replace(s) { "${it.groupValues[1]}/${it.groupValues[2]}" }
        s = Regex("(\\d)[,.](\\d)").replace(s) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        s = " " + s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { CONTRACTION_MAP[it] ?: it } + " "
        s = Regex("[^a-z0-9./' ]").replace(s, " ")
        s = s.replace("'", "")
        s = Regex("(?<!\\d)\\.|\\.(?!\\d)").replace(s, " ")
        s = Regex("(?<![\\d])/|/(?![\\d])").replace(s, " ")
        s = " " + s.split(' ').filter { it.isNotBlank() }.joinToString(" ") + " "
        s = TURN_AROUND.replace(s) { " turn${it.groupValues[2]} ${it.groupValues[1]} " }
        s = WHATS_MISHEARD.replace(s) { " what is ${if (it.groupValues[1] == "to") "the" else it.groupValues[1]} " }
        for ((a, b) in JOINS) s = s.replace(" $a ", " $b ")
        for ((a, b) in MISHEARD) s = s.replace(" $a ", " $b ")
        return s.split(' ').filter { it.isNotBlank() && it !in FILLERS }.map { SLANG[it] ?: it }
    }

    private val CONTRACTION_MAP = CONTRACTIONS.toMap()

    private fun strip(words: List<String>): List<String> {
        var w = words
        while (true) {
            val lead = LEADS.firstOrNull { w.size >= it.size && w.subList(0, it.size) == it } ?: return w
            w = w.drop(lead.size)
        }
    }

    fun normalize(text: String): String = strip(expand(text)).joinToString(" ")

    // --- word helpers ----------------------------------------------------------------------------

    private fun List<String>.has(vararg s: String) = any { it in s }
    private fun List<String>.hasAny(s: Collection<String>) = any { it in s }
    private fun List<String>.seqAt(p: List<String>, i: Int) = i + p.size <= size && p.indices.all { this[i + it] == p[it] }
    private fun List<String>.seq(vararg p: String) = p.any { one -> val ps = one.split(' '); indices.any { seqAt(ps, it) } }
    private fun List<String>.dropLead(lead: Set<String>): List<String> {
        var i = 0
        while (i < size && this[i] in lead) i++
        return drop(i)
    }

    // --- numbers ---------------------------------------------------------------------------------

    private val UNITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine").withIndex().associate { it.value to it.index }
    private val TEENS = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen").withIndex().associate { it.value to it.index + 10 }
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val ORD_UNITS = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9)
    private val ORD_TEENS = mapOf("tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19)
    private val ORD_TENS = mapOf("twentieth" to 20, "thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50, "sixtieth" to 60, "seventieth" to 70, "eightieth" to 80, "ninetieth" to 90, "hundredth" to 100)
    private val ZERO_WORDS = setOf("oh", "o", "zero")
    private val NUMBER_WORDS = UNITS.keys + TEENS.keys + TENS.keys + setOf("hundred", "thousand")

    /** Under a thousand at [start]: value, index after it, whether it was an ordinal; null if no number stands there. */
    private fun under1000(w: List<String>, start: Int): Triple<Int, Int, Boolean>? {
        var j = start
        var total = 0
        var any = false
        when {
            w.getOrNull(j) == "hundred" -> { total = 100; j++; any = true }
            w.getOrNull(j) == "a" && w.getOrNull(j + 1) == "hundred" -> { total = 100; j += 2; any = true }
            (UNITS[w.getOrNull(j)] ?: 0) > 0 && w.getOrNull(j + 1) == "hundred" -> { total = UNITS.getValue(w[j]) * 100; j += 2; any = true }
        }
        // "one hundred and one": the "and" only with a number after it
        if (any && w.getOrNull(j) == "and" && under1000(w, j + 1)?.let { it.first < 100 } == true) j++
        val word = w.getOrNull(j)
        when {
            word != null && word in ORD_TENS -> return Triple(total + ORD_TENS.getValue(word), j + 1, true)
            word != null && word in ORD_TEENS -> return Triple(total + ORD_TEENS.getValue(word), j + 1, true)
            word != null && word in ORD_UNITS -> return Triple(total + ORD_UNITS.getValue(word), j + 1, true)
            word != null && word in TEENS -> return Triple(total + TEENS.getValue(word), j + 1, false)
            word != null && word in TENS -> {
                total += TENS.getValue(word); j++; any = true
                val next = w.getOrNull(j)
                if (next != null && next in ORD_UNITS) return Triple(total + ORD_UNITS.getValue(next), j + 1, true)
                if (next != null && (UNITS[next] ?: 0) > 0) { total += UNITS.getValue(next); j++ }
            }
            word != null && word in UNITS && (!any || UNITS.getValue(word) > 0) -> { total += UNITS.getValue(word); j++; any = true }
        }
        return if (any) Triple(total, j, false) else null
    }

    /** The number spoken at [start] ("twenty five", "one hundred one", "one oh one", "25", "third", "3rd"), or null. */
    fun numberAt(w: List<String>, start: Int): SpokenNumber? {
        if (start !in w.indices) return null
        w[start].toIntOrNull()?.let { return SpokenNumber(it, start, start + 1, false) }
        Regex("(\\d+)(?:st|nd|rd|th)").matchEntire(w[start])?.let { return SpokenNumber(it.groupValues[1].toInt(), start, start + 1, true) }
        // digit by digit, as a frequency is said: "one oh one", "one zero two"
        if ((UNITS[w[start]] ?: 0) > 0 && w.getOrNull(start + 1) in ZERO_WORDS && (w.getOrNull(start + 2) in UNITS || w.getOrNull(start + 2) in ZERO_WORDS)) {
            var v = UNITS.getValue(w[start])
            var j = start + 1
            while (j < w.size && (w[j] in UNITS || w[j] in ZERO_WORDS)) { v = v * 10 + (UNITS[w[j]] ?: 0); j++ }
            return SpokenNumber(v, start, j, false)
        }
        val a = under1000(w, start) ?: return null
        if (w.getOrNull(a.second) == "thousand" && !a.third) {
            val rest = under1000(w, a.second + 1)
            return SpokenNumber(a.first * 1000 + (rest?.first ?: 0), start, rest?.second ?: (a.second + 1), rest?.third ?: false)
        }
        return SpokenNumber(a.first, start, a.second, a.third)
    }

    private fun firstNumber(w: List<String>, from: Int = 0): SpokenNumber? {
        for (i in from until w.size) numberAt(w, i)?.let { return it }
        return null
    }

    /** "ten main street" → "10 main street": cardinals as digits, for addresses; ordinals stay words ("first street"). */
    private fun numbersToDigits(w: List<String>): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            val n = numberAt(w, i)
            if (n != null && !n.ordinal && w[i].toIntOrNull() == null) { out += n.value.toString(); i = n.end; continue }
            out += if (w[i] == "slash") "/" else w[i]
            i++
        }
        return out.joinToString(" ").replace(" / ", "/")
    }

    private val DECIMAL_SEP = setOf("point", "dot", "decimal")

    /** An FM frequency: "one oh one point seven" → 101.7, "ninety eight point eight", "101.7", "one hundred two". */
    private fun findFreq(w: List<String>, context: Boolean): Double? {
        for (i in w.indices) {
            if (w[i].contains('.')) w[i].toDoubleOrNull()?.let { d -> if (d in 64.0..108.0) return d }
            val n = numberAt(w, i) ?: continue
            if (n.ordinal) continue
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

    private val NAV_APPS = listOf(
        "yandex navigator" to "ru.yandex.yandexnavi", "yandex navi" to "ru.yandex.yandexnavi", "yandex maps" to "ru.yandex.yandexmaps", "yandex map" to "ru.yandex.yandexmaps",
        "google maps" to "com.google.android.apps.maps", "google map" to "com.google.android.apps.maps",
        "2gis" to "ru.dublgis.dgismobile", "waze" to "com.waze", "ways" to "com.waze", "osmand" to "net.osmand.plus", "organic maps" to "app.organicmaps",
        "navitel" to "com.navitel", "sygic" to "com.sygic.aura",
    )

    private val MUSIC_APPS = listOf(
        "yandex music" to "ru.yandex.music", "spotify" to "com.spotify.music", "vk music" to "com.uma.musicvk", "boom" to "com.uma.musicvk",
        "youtube music" to "com.google.android.apps.youtube.music", "youtube" to "com.google.android.youtube",
    )

    private val APP_PREPS = setOf("in", "on", "with", "using", "through", "via", "from", "by", "and")

    /** (first index, end index, package) of an app named in [w], with the "in/on/with" before it if there is one. */
    private fun findApp(w: List<String>, table: List<Pair<String, String>>): Triple<Int, Int, String>? {
        for (i in w.indices) for ((name, pkg) in table) {
            val p = name.split(' ')
            if (w.seqAt(p, i)) return Triple(if (i > 0 && w[i - 1] in APP_PREPS) i - 1 else i, i + p.size, pkg)
        }
        return null
    }

    private fun List<String>.without(r: Triple<Int, Int, String>?) = if (r == null) this else take(r.first) + drop(r.second)

    /** Place categories: canonical search text → the words that name it. Same categories, same order as [Commands.PLACES]. */
    internal val PLACES: List<Pair<String, List<String>>> = listOf(
        "EV charging station" to listOf("evcharger", "charger"),
        "LPG station" to listOf("lpg", "propane", "autogas", "cng", "methane"),
        "Gas station" to listOf("gasstation", "shell", "exxon", "chevron", "lukoil", "texaco"),
        "Tire shop" to listOf("tireshop", "tires", "tire", "puncture"),
        "Car wash" to listOf("carwash"),
        "Car service" to listOf("mechanic", "service"),
        "Subaru dealer" to listOf("subaru", "dealer", "dealership"),
        "Parking" to listOf("parking"),
        "Auto parts" to listOf("carparts"),
        "Coffee shop" to listOf("coffee", "starbucks"),
        "Fast food" to listOf("fastfood", "mcdonalds", "burger", "burgerking", "kfc"),
        "Restaurant" to listOf("restaurant", "diner"),
        "Cafe" to listOf("cafe", "food", "eat", "lunch", "dinner", "breakfast", "canteen"),
        "Pharmacy" to listOf("pharmacy"),
        "Hospital" to listOf("hospital", "clinic", "doctor"),
        "ATM" to listOf("atm", "cash"),
        "Bank" to listOf("bank"),
        "Supermarket" to listOf("supermarket", "grocery", "groceries", "store", "shop"),
        "Shopping mall" to listOf("mall"),
        "Market" to listOf("market", "bazaar"),
        "Restroom" to listOf("restroom", "toilet", "bathroom", "washroom"),
        "Hotel" to listOf("hotel", "motel", "hostel", "lodging"),
        "Police" to listOf("police", "cops"),
        "Post office" to listOf("postoffice"),
        "Mosque" to listOf("mosque"),
        "Church" to listOf("church", "cathedral", "temple"),
        "Cinema" to listOf("cinema"),
    )

    private val BRANDS = setOf("shell", "exxon", "chevron", "lukoil", "texaco", "starbucks", "mcdonalds", "burgerking", "kfc", "subaru")

    private val PLACE_FILLER = setOf(
        "nearest", "closest", "nearby", "near", "around", "me", "us", "close", "by", "local", "here", "the", "a", "an", "any", "some", "good", "cheap", "open",
        "where", "is", "are", "there", "can", "i", "we", "find", "search", "look", "for", "show", "in", "at", "to", "on", "of", "my", "way", "along",
        "get", "stop", "something", "place", "locate", "add", "station", "see", "somewhere", "and",
    )

    private val MUSIC_NOUNS = setOf(
        "song", "songs", "track", "tracks", "music", "album", "artist", "singer", "band", "group", "playlist", "podcast", "audiobook", "genre", "clip",
        "hits", "single", "radio", "fm", "frequency", "video", "videos", "youtube",
    )

    private val ROUTE_VERBS = setOf(
        "navigate", "drive", "go", "head", "heading", "route", "directions", "guide", "lead", "take", "bring", "destination", "get",
    )

    private val ROUTE_LEAD = setOf(
        "navigate", "drive", "go", "head", "heading", "route", "directions", "guide", "lead", "take", "bring", "destination", "get", "set", "new", "plan", "build", "make", "find", "start",
        "how", "do", "does", "can", "i", "we", "me", "us", "to", "the", "a", "an", "my", "our", "for", "address", "navigation", "navigator", "show", "going", "over",
    )

    private val ASKING_FIRST = setOf("how", "what", "when", "which", "who", "is", "are", "do", "does", "where")

    private val GENRES = setOf(
        "rock", "pop", "jazz", "blues", "classical", "rap", "hip", "hop", "hiphop", "country", "electronic", "dance", "disco", "metal", "punk", "reggae", "folk", "indie",
        "lounge", "chill", "chillout", "ambient", "soul", "funk", "techno", "house", "trance", "dubstep", "soundtrack", "phonk", "oldies", "retro", "relaxing", "calm", "happy",
        "sad", "romantic", "energetic", "workout", "christmas", "kids", "slow", "fast", "sixties", "seventies", "eighties", "nineties", "80s", "90s", "instrumental", "russian",
    )

    private val PLAY_VERBS = setOf("play", "playing", "listen", "hear", "turnon", "puton", "start", "find", "search", "look")

    private val OPEN_VERBS = setOf("open", "launch", "show", "display", "run", "start", "view")

    /** Opening something, never playing it. */
    private val ONLY_OPEN = OPEN_VERBS - "start"

    private val SCREENS = listOf(
        "FUEL" to setOf("consumption", "fuel", "mileage", "economy", "mpg"),
        "GEARBOX" to setOf("gearbox", "transmission", "cvt", "atf", "automatic"),
        "BATTERY" to setOf("battery", "alternator"),
        "DTC" to setOf("errors", "error", "codes", "code", "trouble", "diagnostics", "faults", "checkengine", "dtc"),
        "DRIVE" to setOf("dynamics", "boost", "turbo", "acceleration"),
        "HOME" to setOf("home", "main", "dashboard", "gauges", "instruments", "speedometer", "tachometer"),
        "MEDIA" to setOf("media", "multimedia", "player"),
    )

    private fun screenIn(w: List<String>) = SCREENS.firstOrNull { (_, words) -> w.hasAny(words) }?.first

    private val UNSUPPORTED = listOf(
        "climate" to setOf("ac", "climate", "heater", "heating", "fan", "defrost", "defroster"),
        "body" to setOf("headlights", "lights", "wipers", "window", "windows", "sunroof", "door", "doors", "trunk"),
        "phone" to setOf("call", "dial", "text", "message", "sms", "answer"),
        "weather" to setOf("weather", "forecast", "raining"),
        "alarm" to setOf("alarm", "timer", "reminder", "remind"),
        "talk" to setOf("joke", "story", "poem"),
    )

    private val CANCEL_WORDS = setOf(
        "cancel", "never", "nevermind", "mind", "forget", "it", "no", "nothing", "nope", "thanks", "thank", "you", "abort", "dismiss", "goodbye", "bye",
        "that", "is", "all", "sorry", "wrong", "mistake", "ignore", "do", "not",
    )

    /** Words that cancel alone. The rest of the set only does in company: "it", "is", "you". */
    private val CANCEL_ALONE = setOf("cancel", "nevermind", "no", "nothing", "nope", "thanks", "abort", "dismiss", "goodbye", "bye", "sorry", "ignore")

    // --- entry point -----------------------------------------------------------------------------

    /** What [text] (the command after the wake word) asks for; null if nothing was understood. */
    override fun parse(text: String): VoiceCommand? {
        val raw = expand(text)
        val w = strip(raw)
        if (w.isEmpty()) return null
        val t = w.joinToString(" ")
        meaning(raw)?.let { m -> return rules(strip(expand(m)))?.copy(text = t) }
        rules(w)?.let { return it.copy(text = t) }
        // a word typed or heard slightly wrong: the nearest known word, then once more
        val fixed = w.map { correct(it) }
        if (fixed != w) rules(fixed)?.let { return it.copy(text = t) }
        return null
    }

    private fun rules(w: List<String>): VoiceCommand? =
        dialog(w) ?: navigation(w) ?: volume(w) ?: radio(w) ?: media(w) ?: readValue(w) ?: screen(w) ?: openApp(w)

    private val VOCAB by lazy { (listOf(
        "louder", "quieter", "volume", "radio", "station", "frequency", "music", "track", "pause", "resume", "previous", "shuffle", "repeat", "route", "navigate", "navigator",
        "temperature", "coolant", "battery", "voltage", "consumption", "errors", "codes", "gearbox", "transmission", "bluetooth", "favorites", "playlist", "album", "artist",
        "traffic", "dashboard", "media", "fullscreen",
    ) + PLAY_VERBS + OPEN_VERBS).toSet() }

    private fun correct(word: String): String {
        if (word.length < 5 || word in VOCAB || word.any { it.isDigit() }) return word
        val max = if (word.length >= 8) 2 else 1
        return VOCAB.filter { it[0] == word[0] && abs(it.length - word.length) <= max }
            .map { it to Commands.levenshtein(it, word) }.filter { it.second <= max }.minByOrNull { it.second }?.first ?: word
    }

    private fun cmd(i: VoiceIntent, query: String? = null, target: String? = null, number: Int? = null) =
        VoiceCommand(i, query = query?.takeIf { it.isNotBlank() }, target = target, number = number)

    // --- dialog, full screen, things we cannot do --------------------------------------------------

    private fun dialog(w: List<String>): VoiceCommand? {
        if (w.all { it in CANCEL_WORDS } && (w.size > 1 || w[0] in CANCEL_ALONE)) return cmd(VoiceIntent.CANCEL)
        if (w.seq("what can you do", "what do you do", "what can i say", "what commands", "list of commands", "what should i say", "how do you work") ||
            (w.size <= 2 && w.has("help", "commands"))
        ) return cmd(VoiceIntent.HELP)
        val nav = w.has("navigator", "navigation", "map", "maps", "2gis")
        if (w.has("fullscreen") || (w.has("maximize", "expand") && (w.size == 1 || nav))) return cmd(VoiceIntent.FULL_SCREEN, target = if (nav) "nav" else null)
        if (nav && w.has("close", "hide", "minimize", "exit")) return cmd(VoiceIntent.FULL_SCREEN)
        // a song or a destination may have such a word in it: "play the doors", "go to call center"
        if (w[0] != "play" && w[0] !in setOf("listen", "hear") && routeStart(w) < 0)
            UNSUPPORTED.firstOrNull { (_, words) -> w.hasAny(words) }?.let { return cmd(VoiceIntent.UNSUPPORTED, target = it.first) }
        return null
    }

    // --- navigation ------------------------------------------------------------------------------

    private val FUEL_WORDS = arrayOf("fuel", "gas", "petrol", "diesel", "tank", "charge")

    private fun navigation(w: List<String>): VoiceCommand? {
        val navWord = w.has("navigator", "navigation", "gps", "map", "maps")
        val off = w.has("turnoff", "mute", "silence", "disable", "stop", "without", "shutup", "off")
        if (navWord && w.has("voice", "sound", "speech", "prompts", "guidance", "mute", "unmute", "audio", "shutup"))
            return cmd(if (off) VoiceIntent.NAV_SOUND_OFF else VoiceIntent.NAV_SOUND_ON)
        if (w.has("route", "navigation", "guidance", "directions") && w.has("cancel", "clear", "stop", "end", "finish", "abort", "turnoff", "remove", "delete", "reset"))
            return cmd(VoiceIntent.NAV_CANCEL)
        if (w.has("traffic", "jam", "jams", "congestion") || w.seq("road conditions")) return cmd(VoiceIntent.NAV_TRAFFIC)
        if (!w.has(*FUEL_WORDS) && !w.hasAny(MUSIC_NOUNS) && !w.seq("can i", "can we") && (w.has("eta", "arrive", "arrival") ||
                w.seq("how long", "how much longer", "how much further", "how much farther", "are we there", "get there", "how far to", "how far is it", "how far away", "time left", "distance left", "distance remaining", "how many minutes"))
        ) return cmd(VoiceIntent.NAV_ETA)
        val place = PLACES.firstOrNull { (_, words) -> w.hasAny(words) }
        if (place == null && !w.has("navigate", "route", "temperature") && (w.seq("where am i", "where are we", "my location", "current location") || w.has("coordinates") ||
                (w.has("what", "which") && w.has("street", "city", "town", "road", "highway", "district")))
        ) return cmd(VoiceIntent.NAV_WHERE)
        if (w.has("remember", "save", "mark", "set", "store", "here") || w.seq("this is my")) {
            if (w.has("home", "house")) return cmd(VoiceIntent.NAV_SAVE_PLACE, target = "home")
            if (w.has("work", "office")) return cmd(VoiceIntent.NAV_SAVE_PLACE, target = "work")
        }
        // "find the song…", "find a station…" are not about the map
        if (place == null && w.hasAny(MUSIC_NOUNS)) return null
        // a station is the radio's, unless one goes to it: "take me to the train station", "where is the bus station"
        if (place == null && w.has("station", "stations") && routeStart(w) < 0 && w[0] != "where") return null

        // the navigator itself
        val app = findApp(w, NAV_APPS)
        val rest = w.without(app).dropLead(OPEN_VERBS + setOf("turnon", "me", "the", "my", "continue", "resume", "navigator", "navigation", "map", "maps", "gps", "app"))
        if ((app != null || navWord) && rest.isEmpty()) return VoiceCommand(VoiceIntent.NAV_OPEN, app = app?.third)

        val near = w.has("nearest", "closest", "nearby", "near", "around", "local") || w.seq("close by", "on the way", "along the way", "around here")
        val routeAt = routeStart(w)
        if (place != null) {
            val content = w.without(app).filter { it !in PLACE_FILLER && it !in ROUTE_LEAD }
            // only the category is named → the nearest one; "cafe pushkin" is a named place, routed or searched as said
            if (near || content.all { it in place.second }) {
                val q = if (content.any { it in BRANDS }) numbersToDigits(content) else place.first
                return VoiceCommand(VoiceIntent.NAV_NEARBY, query = q, target = place.first, app = app?.third)
            }
        }
        if (routeAt >= 0) {
            // the place said first, the verb last ("to the airport let's go")
            val after = w.drop(routeAt + 1).filter { it !in ROUTE_LEAD }
            val r = if (after.isEmpty() && routeAt > 0) route(listOf(w[routeAt]) + w.take(routeAt).filter { it != "lets" }) else route(w.drop(routeAt))
            if (r != null) return r
        }
        if (near) {
            val q = w.without(app).filter { it !in PLACE_FILLER }
            if (q.isNotEmpty()) return VoiceCommand(VoiceIntent.NAV_NEARBY, query = numbersToDigits(q), app = app?.third)
        }
        // "find main street", "where is the station", "show on the map …"
        val search = when {
            w[0] in setOf("find", "locate") -> 1
            w.seq("search for", "look for") && w.size > 2 -> 2
            w.seq("where is", "where are") && w.size > 2 -> 2
            w.seq("show on the map") -> 4
            else -> -1
        }
        if (search > 0) {
            val after = w.drop(search)
            val q = after.without(findApp(after, NAV_APPS)).dropLead(setOf("me", "the", "a", "an", "address", "on", "map"))
            if (q.isNotEmpty()) return VoiceCommand(VoiceIntent.NAV_SEARCH, query = numbersToDigits(q), app = app?.third)
        }
        // bare "home", "to work"
        val bare = w.dropLead(ROUTE_LEAD)
        if (bare.size == 1 && w.size <= 3) homeOrWork(bare)?.let { return it }
        return null
    }

    /** Index of the route verb, -1 if not a route. A question is one only as "how do I get to…". */
    private fun routeStart(w: List<String>): Int {
        if (w[0] in ASKING_FIRST && !w.seq("how do i get", "how to get", "how can i get", "how do we get", "how can we get", "how do i drive", "how to drive", "how do i go", "how to go")) return -1
        val i = w.indexOfFirst { it in ROUTE_VERBS }
        return if (i in 0..4) i else -1
    }

    private fun homeOrWork(dest: List<String>): VoiceCommand? {
        if (dest.isEmpty() || dest.size > 1) return null
        return when (dest[0]) {
            "home", "house" -> cmd(VoiceIntent.NAV_HOME)
            "work", "office", "job", "workplace" -> cmd(VoiceIntent.NAV_WORK)
            else -> null
        }
    }

    /** Null when it turns out not to be a route after all: "go back", "go to fuel". */
    private fun route(fromVerb: List<String>): VoiceCommand? {
        val verb = fromVerb[0]
        var dest = fromVerb.dropLead(ROUTE_LEAD)
        val app = findApp(dest, NAV_APPS)
        dest = dest.without(app)
        var via: String? = null
        val v = dest.indexOfFirst { it == "via" || it == "through" }
        if (v >= 0) {
            via = numbersToDigits(dest.drop(v + 1).dropLead(setOf("the", "a", "an", "to"))).ifBlank { null }
            dest = dest.take(v)
        }
        homeOrWork(dest)?.let { return it.copy(via = via, app = app?.third) }
        if (dest.firstOrNull() in setOf("back", "forward", "next", "previous")) return null
        if (dest == listOf("on") || dest == listOf("ahead")) return VoiceCommand(VoiceIntent.NAV_OPEN, app = app?.third)
        if (verb == "go" && dest.size in 1..2 && screenIn(dest) != null) return null
        return VoiceCommand(VoiceIntent.NAV_ROUTE, query = numbersToDigits(dest).ifBlank { null }, via = via, app = app?.third)
    }

    // --- volume ----------------------------------------------------------------------------------

    private fun volume(w: List<String>): VoiceCommand? {
        val noun = w.has("volume", "sound", "sounds", "audio")
        val louder = w.has("louder", "turnup") || (noun && w.has("up", "raise", "increase", "higher", "more", "boost"))
        val quieter = w.has("quieter", "turndown", "lower") || w == listOf("quiet") || (noun && w.has("down", "decrease", "reduce", "less"))
        val n = firstNumber(w)?.takeIf { !it.ordinal }
        val toLevel = n != null && n.start > 0 && w[n.start - 1] == "to"
        val pct = w.has("percent", "%")
        val step = when {
            w.has("little", "bit", "slightly", "tad") -> 1
            w.has("much", "lot", "way") -> 4
            else -> null
        }
        if ((louder || quieter) && !toLevel) return VoiceCommand(if (louder) VoiceIntent.VOLUME_UP else VoiceIntent.VOLUME_DOWN, number = n?.value ?: step)
        if (louder || quieter || noun) {
            if (w.has("maximum", "max", "full")) return VoiceCommand(VoiceIntent.VOLUME_SET, number = 100, percent = true)
            if (w.has("half", "middle")) return VoiceCommand(VoiceIntent.VOLUME_SET, number = 50, percent = true)
            if (w.has("minimum", "min")) return VoiceCommand(VoiceIntent.VOLUME_SET, number = 1)
            if (n != null) return if (n.value == 0) cmd(VoiceIntent.MUTE) else VoiceCommand(VoiceIntent.VOLUME_SET, number = n.value, percent = pct)
        }
        // "set it to twenty", "turn it to five"
        if (w[0] in setOf("set", "turn", "put") && toLevel && n != null) return VoiceCommand(VoiceIntent.VOLUME_SET, number = n.value, percent = pct)
        if (w.has("mute", "silence") || (noun && w.has("turnoff", "off", "without", "disable"))) return cmd(VoiceIntent.MUTE)
        if (w.has("unmute") || (noun && w.has("turnon", "on", "back", "restore", "enable"))) return cmd(VoiceIntent.UNMUTE)
        if (w.has("volume") && (w.size == 1 || w.has("what", "how", "which", "current"))) return cmd(VoiceIntent.READ_VALUE, target = "VOLUME")
        return null
    }

    // --- radio -----------------------------------------------------------------------------------

    private val RADIO_LEAD = setOf(
        "play", "playing", "turnon", "puton", "switch", "change", "tune", "open", "start", "listen", "hear", "find", "to", "the", "on", "a", "my", "favorite",
        "radio", "station", "fm", "channel", "preset", "number", "frequency",
    )

    private fun radio(w: List<String>): VoiceCommand? {
        // "where am i", "how fast am i going": the verb, not the band
        val amBand = "am" in w && "i" !in w
        val bandWord = "band" in w && w[0] != "play"
        val ctx = amBand || bandWord || w.has("radio", "fm", "frequency", "preset", "station", "stations", "channel", "tune", "tuner")
        val onlyNumber = w.filter { it !in NUMBER_WORDS && it.toDoubleOrNull() == null && it !in ZERO_WORDS }
            .all { it in setOf("play", "set", "switch", "turnon", "to", "tune", "frequency", "point", "dot", "and", "on") }
        val freq = findFreq(w, ctx)
        if (freq != null && (ctx || onlyNumber)) return VoiceCommand(VoiceIntent.RADIO_FREQ, freq = freq)
        if (!ctx) return null
        if (w.has("what", "which", "who", "name")) return cmd(VoiceIntent.WHAT_PLAYING, target = "radio")
        if (w.has("turnoff", "off", "stop", "disable", "pause")) return cmd(VoiceIntent.PAUSE, target = "radio")
        if (bandWord || amBand || (w.has("fm") && w.has("switch", "change") && w.size <= 4))
            return cmd(VoiceIntent.RADIO_BAND, target = if (amBand) "AM" else if (w.has("fm")) "FM" else null)
        if (w.has("find", "search", "scan", "seek", "look", "auto"))
            return cmd(VoiceIntent.RADIO_SEEK, number = if (w.has("back", "backward", "backwards", "down", "previous")) -1 else 1)
        if (w.has("next", "forward", "another", "different")) return cmd(VoiceIntent.RADIO_NEXT)
        if (w.has("previous", "back", "last")) return cmd(VoiceIntent.RADIO_PREV)
        val station = w.has("station", "radio", "channel")
        val rest = w.dropLead(RADIO_LEAD)
        val n = numberAt(rest, 0) ?: if (station || w.has("preset", "number")) firstNumber(w) else null
        if (n != null && n.value in 1..40) return cmd(VoiceIntent.RADIO_PRESET, number = n.value)
        val name = rest.dropLastWhile { it == "radio" || it == "station" }
        if (name.isNotEmpty() && name.none { it in NUMBER_WORDS }) return cmd(VoiceIntent.RADIO_NAME, query = name.joinToString(" "))
        return cmd(VoiceIntent.RADIO)
    }

    // --- media -----------------------------------------------------------------------------------

    private val FOCUS_NOUNS: Map<String, MediaFocus> = mapOf(
        "songs" to MediaFocus.ARTIST, "tracks" to MediaFocus.ARTIST, "hits" to MediaFocus.ARTIST,
        "song" to MediaFocus.TRACK, "track" to MediaFocus.TRACK, "tune" to MediaFocus.TRACK, "single" to MediaFocus.TRACK, "clip" to MediaFocus.TRACK,
        "album" to MediaFocus.ALBUM, "record" to MediaFocus.ALBUM,
        "artist" to MediaFocus.ARTIST, "band" to MediaFocus.ARTIST, "group" to MediaFocus.ARTIST, "singer" to MediaFocus.ARTIST, "rapper" to MediaFocus.ARTIST,
        "playlist" to MediaFocus.PLAYLIST, "mix" to MediaFocus.PLAYLIST,
        "podcast" to MediaFocus.PODCAST, "audiobook" to MediaFocus.PODCAST, "book" to MediaFocus.PODCAST,
        "video" to MediaFocus.ANY, "videos" to MediaFocus.ANY,
        "genre" to MediaFocus.GENRE, "music" to MediaFocus.ANY,
    )

    /** Small words between the verb, the noun and the name. */
    private val FOCUS_GLUE = setOf("the", "a", "an", "some", "by", "from", "called", "named", "titled", "of", "me", "us", "something", "anything", "any", "to", "for", "it", "this", "that")

    /** After a verb, a phrase of only these words is a command for the player; anything else may be a name ("play back in black"). */
    private val TRANSPORT_WORDS = setOf(
        "next", "previous", "last", "again", "shuffle", "shuffled", "shuffling", "random", "repeat", "repeating", "loop", "bluetooth", "usb", "from", "my", "phone", "flash", "drive", "thumb",
        "this", "that", "it", "the", "a", "song", "track", "one", "music", "in", "order", "beginning", "start", "over", "stick", "memory", "on", "off", "something", "else",
    )

    private fun media(w: List<String>): VoiceCommand? {
        val at = w.indexOfFirst { it in PLAY_VERBS }.takeIf { it in 0..1 && w[0] !in ONLY_OPEN }
        val after = at?.let { w.drop(it + 1) }
        if (after == null || after.isNotEmpty() && after.all { it in TRANSPORT_WORDS }) transport(w, at)?.let { return it }
        return search(w, at)
    }

    private fun transport(w: List<String>, playIdx: Int?): VoiceCommand? {
        val asking = w.has("what", "which", "who", "name", "tell")
        if (playIdx == null && asking && (w.has("song", "track", "playing", "singing", "sings", "sing", "artist", "band", "tune") || w.seq("what is this", "who is this")))
            return cmd(VoiceIntent.WHAT_PLAYING)
        if (w.has("dislike", "hate") || w.seq("do not like", "thumbs down")) return cmd(VoiceIntent.DISLIKE)
        if (w.has("like", "love") && w.size <= 4 || w.seq("thumbs up") || (w.has("add", "save") && w.has("favorites", "favorite", "library", "collection", "song", "track")))
            return cmd(VoiceIntent.LIKE)

        val offWords = w.has("turnoff", "off", "stop", "no", "without", "disable", "cancel")
        val shuffleWord = w.has("shuffle", "shuffled", "shuffling", "random", "randomize")
        if (shuffleWord && offWords || w.seq("in order")) return cmd(VoiceIntent.SHUFFLE_OFF)
        if (shuffleWord || w.seq("mix it up")) return cmd(VoiceIntent.SHUFFLE_ON)
        val repeatWord = w.has("repeat", "repeating", "loop", "looping")
        if (repeatWord && offWords) return cmd(VoiceIntent.REPEAT_OFF)
        if (repeatWord) {
            val one = w.has("song", "track", "one", "this")
            val all = w.has("playlist", "album", "all", "everything")
            return cmd(VoiceIntent.REPEAT_ON, target = if (all) "all" else if (one) "one" else null)
        }
        val secs = firstNumber(w)?.let { n -> if (w.getOrNull(n.end)?.startsWith("minute") == true) n.value * 60 else n.value }
            ?: if (w.has("minute")) 60 else null
        if (w.has("rewind", "fastforward") || (w.has("seconds", "minutes", "minute") && w.has("back", "backward", "forward", "ahead", "skip", "jump")))
            return cmd(if (w.has("rewind", "back", "backward")) VoiceIntent.REWIND else VoiceIntent.FORWARD, number = secs)
        if (w.has("restart", "replay", "again", "beginning") || w.seq("start over", "from the start", "once more", "one more time"))
            return cmd(VoiceIntent.RESTART)
        if (w.has("resume", "continue") || w.seq("keep playing")) return cmd(VoiceIntent.PLAY)
        if (w.has("next", "skip", "forward") || w.seq("another song", "another track", "another one", "different song", "different track", "something else", "change the song", "change song", "change track", "change the track", "change this", "switch song", "switch track"))
            return cmd(VoiceIntent.NEXT)
        if (w.has("previous", "back") || (w.has("last") && (w.size == 1 || w.has("song", "track", "one")))) return cmd(VoiceIntent.PREV)
        if (w.has("pause", "stop", "halt", "shutup", "enough") || w == listOf("cause") || (w.has("turnoff") && w.drop(1).all { it in setOf("it", "this", "that", "the", "music", "player", "song", "track", "everything", "all") }))
            return cmd(VoiceIntent.PAUSE)
        if (w.has("bluetooth") || w.seq("from phone", "from my phone", "from the phone", "phone audio")) return cmd(VoiceIntent.SOURCE_BT)
        if (w.has("usb") || w.seq("flash drive", "thumb drive", "memory stick")) return cmd(VoiceIntent.SOURCE_USB)
        return null
    }

    /** "play …": a song, artist, genre, playlist, favourites or a music app. */
    private fun search(w: List<String>, playIdx: Int?): VoiceCommand? {
        val app = findApp(w, MUSIC_APPS)
        val opener = w.indexOfFirst { it in OPEN_VERBS }.takeIf { it in 0..1 && app != null }
        val video = w.has("video", "videos", "clip")
        val v = playIdx ?: opener ?: if (w[0] in ONLY_OPEN && w.any { it in FOCUS_NOUNS }) 0 else null
        if (v == null) {
            if (app != null && w.without(app).isEmpty()) return VoiceCommand(VoiceIntent.MEDIA_APP, app = app.third)
            if (w.size <= 2 && w.has("music", "play")) return cmd(VoiceIntent.PLAY)
            if (w.seq("my wave")) return VoiceCommand(VoiceIntent.PLAY_QUERY, query = "My Wave", focus = MediaFocus.FAVORITES, app = app?.third)
            return null
        }
        val afterVerb = w.drop(v + 1)
        val named = afterVerb.without(findApp(afterVerb, MUSIC_APPS))
        var rest = named.dropLead(FOCUS_GLUE)
        val pkg = app?.third ?: if (video) "com.google.android.youtube" else null
        if (rest.isEmpty()) return VoiceCommand(if (pkg != null) VoiceIntent.MEDIA_APP else VoiceIntent.PLAY, app = pkg)
        if (w[v] != "play" && rest.size <= 2 && !rest.has("music", "player", "media")) screenIn(rest)?.let { return cmd(VoiceIntent.SCREEN, target = it) }

        if (rest.seq("my wave")) return VoiceCommand(VoiceIntent.PLAY_QUERY, query = "My Wave", focus = MediaFocus.FAVORITES, app = pkg)
        if (rest.has("favorites", "favorite", "liked", "loved") || (rest[0] == "my" && rest.size == 2 && rest.has("music", "songs", "tracks")))
            return VoiceCommand(VoiceIntent.PLAY_QUERY, query = "Liked songs", focus = MediaFocus.FAVORITES, app = pkg)

        // "the song / band / album / playlist …": remember what kind, then the name
        var focus: MediaFocus? = null
        var byName = "by" in named.takeWhile { it in FOCUS_GLUE }
        while (rest.isNotEmpty()) {
            val f = FOCUS_NOUNS[rest[0]]
            if (f != null && rest.drop(1).any { it !in FOCUS_GLUE } || f != null && rest.size == 1) {
                if (focus == null || focus == MediaFocus.ANY || f != MediaFocus.ANY) focus = f
                rest = rest.drop(1)
                continue
            }
            if (rest[0] in FOCUS_GLUE) { if (rest[0] == "by") byName = true; rest = rest.drop(1); continue }
            break
        }
        if (rest.isEmpty()) return VoiceCommand(VoiceIntent.PLAY, focus = focus?.takeIf { it != MediaFocus.ANY }, app = pkg)
        // "rock music", "relaxing songs": the genre alone is what is looked for
        val words = rest.filter { it != "music" && it != "songs" }
        if ((focus == null || focus == MediaFocus.ANY) && words.isNotEmpty() && words.size <= 3 && words.all { it in GENRES || it in setOf("and", "the") }) {
            return VoiceCommand(VoiceIntent.PLAY_QUERY, query = words.joinToString(" "), focus = MediaFocus.GENRE, app = pkg)
        }
        if (focus == MediaFocus.ANY) focus = if (byName) MediaFocus.ARTIST else MediaFocus.GENRE
        if (focus == null && byName) focus = MediaFocus.ARTIST
        return VoiceCommand(VoiceIntent.PLAY_QUERY, query = rest.joinToString(" "), focus = focus ?: MediaFocus.ANY, app = pkg)
    }

    // --- values the app can say ------------------------------------------------------------------

    private fun readValue(w: List<String>): VoiceCommand? {
        if (w.seq("what time", "the time", "current time") || w == listOf("time")) return cmd(VoiceIntent.READ_VALUE, target = "TIME")
        val ask = w.has("what", "how", "which", "tell", "check", "read", "say", "any", "do", "does", "is", "are", "have", "got")
        val show = w.any { it in OPEN_VERBS } || w.has("screen", "tab", "page")
        if (show && !ask) return null
        val howFar = w.seq("how far")
        val temp = w.has("temperature", "temp", "degrees", "hot", "cold", "warm", "warmed", "overheating", "coolant")
        val target = when {
            temp && (w.has("outside", "outdoor", "outdoors", "ambient") || (w.has("hot", "cold", "warm") && w.has("it"))) -> "OUTSIDE"
            temp && w.has("gearbox", "transmission", "atf", "cvt") -> "ATF"
            temp && w.has("intake", "air") -> "INTAKE"
            temp && w.has("oil") -> "OIL"
            temp -> "COOLANT"
            w.has("pressure") && w.has("oil") -> "OILP"
            w.has("oil") -> "OIL"
            w.has("range") || w.seq("how far can", "how much farther can", "how much further can", "miles left", "kilometers left", "km left", "miles to empty", "distance to empty") || (howFar && w.has(*FUEL_WORDS)) -> "RANGE"
            w.has("consumption", "mileage", "economy", "mpg") -> "CONSUMPTION"
            w.has(*FUEL_WORDS) -> "FUEL"
            w.has("voltage", "volts", "battery") -> "BATTERY"
            w.seq("engine speed") || w.has("rpm", "revs", "rev") -> "RPM"
            w.has("speed") || w.seq("how fast") -> "SPEED"
            w.has("boost", "turbo") -> "BOOST"
            w.has("errors", "error", "codes", "code", "faults", "fault", "checkengine", "dtc", "trouble") -> "DTC"
            w.has("trip", "driven", "traveled", "travelled", "distance") || howFar || w.seq("how many miles", "how many kilometers") -> "TRIP"
            else -> return null
        }
        val plain = target in setOf("COOLANT", "OUTSIDE", "INTAKE", "ATF", "OIL", "OILP", "SPEED", "RPM", "FUEL", "RANGE") ||
            (target == "BATTERY" && w.has("voltage", "volts")) || (target == "BOOST" && w.has("pressure"))
        if (!ask && !(plain && w.size <= 3)) return null
        return cmd(VoiceIntent.READ_VALUE, target = target)
    }

    // --- our screens and other apps --------------------------------------------------------------

    private fun screen(w: List<String>): VoiceCommand? {
        val s = screenIn(w) ?: return null
        val content = w.filter { it !in OPEN_VERBS && it !in setOf("screen", "tab", "page", "section", "the", "my", "me", "to", "go", "switch", "on", "turnon") }
        return if (content.size <= 3) cmd(VoiceIntent.SCREEN, target = s) else null
    }

    private fun openApp(w: List<String>): VoiceCommand? {
        if (w[0] !in OPEN_VERBS && w[0] != "app" && w[0] != "turnon") return null
        val rest = w.drop(1).dropLead(setOf("the", "app", "application", "my", "me"))
        return if (rest.isEmpty()) null else cmd(VoiceIntent.APP_OPEN, query = rest.joinToString(" "))
    }

    // --- the driver's own phrases ----------------------------------------------------------------

    override fun ownFor(said: String, own: List<OwnCommand>) = Commands.ownFor(said, own, ::normalize)

    // --- the words said back ---------------------------------------------------------------------

    private val APP_NAMES = mapOf(
        "ru.yandex.yandexnavi" to "Yandex Navigator", "ru.yandex.yandexmaps" to "Yandex Maps", "com.google.android.apps.maps" to "Google Maps",
        "ru.dublgis.dgismobile" to "2GIS", "com.waze" to "Waze", "net.osmand.plus" to "OsmAnd", "app.organicmaps" to "Organic Maps",
        "com.navitel" to "Navitel", "com.sygic.aura" to "Sygic", "ru.yandex.music" to "Yandex Music", "com.spotify.music" to "Spotify",
        "com.uma.musicvk" to "VK Music", "com.google.android.apps.youtube.music" to "YouTube Music", "com.google.android.youtube" to "YouTube",
    )

    private val SCREEN_NAMES = mapOf(
        "HOME" to "home", "DRIVE" to "dynamics", "FUEL" to "fuel use", "GEARBOX" to "transmission", "BATTERY" to "battery", "DTC" to "codes", "MEDIA" to "media",
    )

    private val VALUE_NAMES = mapOf(
        "COOLANT" to "engine temperature", "OIL" to "oil temperature", "OILP" to "oil pressure", "OUTSIDE" to "outside temperature",
        "INTAKE" to "intake temperature", "ATF" to "transmission temperature", "FUEL" to "fuel left", "RANGE" to "range",
        "CONSUMPTION" to "consumption", "BATTERY" to "voltage", "SPEED" to "speed", "RPM" to "rpm", "BOOST" to "boost",
        "DTC" to "codes", "TRIP" to "trip distance", "TIME" to "time", "VOLUME" to "volume",
    )

    /** How the assistant took [c], in a few words for the own commands editor. */
    override fun describe(c: VoiceCommand): String {
        val q = c.query?.let { "“$it”" }.orEmpty()
        val inApp = c.app?.let { " in ${APP_NAMES[it] ?: it}" }.orEmpty()
        return when (c.intent) {
            VoiceIntent.PLAY_QUERY -> if (c.app == "com.google.android.youtube") "YouTube: search $q" else "play music $q$inApp"
            VoiceIntent.MEDIA_APP -> "open ${c.app?.let { APP_NAMES[it] ?: it } ?: "player"}"
            VoiceIntent.PLAY -> "play"
            VoiceIntent.PAUSE -> "pause"
            VoiceIntent.NEXT, VoiceIntent.DISLIKE -> "next track"
            VoiceIntent.PREV -> "previous track"
            VoiceIntent.RESTART -> "track from the start"
            VoiceIntent.REWIND -> "back ${c.number ?: 15} s"
            VoiceIntent.FORWARD -> "forward ${c.number ?: 15} s"
            VoiceIntent.SHUFFLE_ON -> "shuffle"
            VoiceIntent.SHUFFLE_OFF -> "in order"
            VoiceIntent.REPEAT_ON -> "repeat"
            VoiceIntent.REPEAT_OFF -> "no repeat"
            VoiceIntent.LIKE -> "like"
            VoiceIntent.WHAT_PLAYING -> "say what's playing"
            VoiceIntent.SOURCE_BT -> "music over Bluetooth"
            VoiceIntent.SOURCE_USB -> "music from USB"
            VoiceIntent.VOLUME_UP -> "louder"
            VoiceIntent.VOLUME_DOWN -> "quieter"
            VoiceIntent.VOLUME_SET -> "volume ${c.number}${if (c.percent) "%" else ""}"
            VoiceIntent.MUTE -> "mute"
            VoiceIntent.UNMUTE -> "unmute"
            VoiceIntent.RADIO -> "radio"
            VoiceIntent.RADIO_FREQ -> "radio ${c.freq} MHz"
            VoiceIntent.RADIO_PRESET -> "radio, station ${c.number}"
            VoiceIntent.RADIO_NAME -> "radio $q"
            VoiceIntent.RADIO_NEXT -> "next station"
            VoiceIntent.RADIO_PREV -> "previous station"
            VoiceIntent.RADIO_SEEK -> "station search"
            VoiceIntent.RADIO_BAND -> "band ${c.target ?: "next"}"
            VoiceIntent.NAV_ROUTE -> if (c.query == null) "route, will ask where to" else "route: ${c.query}${c.via?.let { " via $it" }.orEmpty()}$inApp"
            VoiceIntent.NAV_SEARCH -> "find on the map $q$inApp"
            VoiceIntent.NAV_NEARBY -> "nearest: ${c.target ?: c.query}$inApp"
            VoiceIntent.NAV_HOME -> "route home$inApp"
            VoiceIntent.NAV_WORK -> "route to work$inApp"
            VoiceIntent.NAV_SAVE_PLACE -> "remember ${if (c.target == "home") "home" else "work"}"
            VoiceIntent.NAV_CANCEL -> "cancel the route"
            VoiceIntent.NAV_TRAFFIC -> "show traffic"
            VoiceIntent.NAV_ETA -> "time to arrival"
            VoiceIntent.NAV_WHERE -> "where am I"
            VoiceIntent.NAV_SOUND_OFF -> "navigator voice off"
            VoiceIntent.NAV_SOUND_ON -> "navigator voice on"
            VoiceIntent.NAV_OPEN -> "open ${c.app?.let { APP_NAMES[it] ?: it } ?: "navigator"}"
            VoiceIntent.SCREEN -> "screen: ${SCREEN_NAMES[c.target] ?: c.target}"
            VoiceIntent.FULL_SCREEN -> if (c.target == "nav") "navigator full screen" else "full screen"
            VoiceIntent.READ_VALUE -> "say: ${VALUE_NAMES[c.target] ?: c.target}"
            VoiceIntent.APP_OPEN -> "open app $q"
            VoiceIntent.CANCEL -> "cancel"
            VoiceIntent.HELP -> "list of commands"
            VoiceIntent.UNSUPPORTED -> "can't do that"
        }
    }

    /** What a car's values sound like when asked for; numbers are what the driver reads on the screen, in his units. */
    class Facts(
        val coolantC: Number? = null, val intakeC: Number? = null, val atfC: Number? = null, val oilC: Number? = null, val oilEstimated: Boolean = false,
        val oilBar: Double? = null, val batteryV: Number? = null, val speedKmh: Double? = null, val rpm: Number? = null, val boostBar: Number? = null,
        val fuelLeftL: Number? = null, val fuelPct: Number? = null, val rangeKm: Double? = null, val per100: Double? = null, val tripKm: Double? = null,
        val volume: Int? = null, val time: String = "",
    )

    private fun count(x: Number?, unit: String, plural: String = unit + "s"): String {
        val v = x?.toDouble()?.roundToLong() ?: return "no data"
        return "$v ${if (abs(v) == 1L) unit else plural}"
    }

    /** The answer to READ_VALUE [target], ready for [Speech]. DTC opens the screen, which [Voice] does. */
    fun valueAnswer(target: String, f: Facts): String {
        val miles = DistanceUnits.miles
        fun one(x: Number?) = x?.let { String.format(Locale.US, "%.1f", it.toDouble()) }
        return when (target) {
            "COOLANT" -> "Engine ${count(f.coolantC, "degree")}"
            "INTAKE", "OUTSIDE" -> "Intake air ${count(f.intakeC, "degree")}"
            "ATF" -> "Transmission ${count(f.atfC, "degree")}"
            "OIL" -> f.oilC?.let { "Oil ${if (f.oilEstimated) "about " else ""}${count(it, "degree")}" } ?: "No oil data"
            "OILP" -> f.oilBar?.let { "Oil pressure ${one(it)} bar" } ?: "No oil pressure sensor"
            "BATTERY" -> f.batteryV?.let { "Voltage ${one(it)} volts" } ?: "Voltage no data"
            "SPEED" -> if (miles) "Speed ${count(DistanceUnits.speed(f.speedKmh), "mile per hour", "miles per hour")}" else "Speed ${count(f.speedKmh, "kilometer per hour", "kilometers per hour")}"
            "RPM" -> f.rpm?.let { "Engine at ${it.toDouble().roundToLong()} r p m" } ?: "R p m no data"
            "BOOST" -> f.boostBar?.let { "Boost ${String.format(Locale.US, "%.2f", it.toDouble())} bar" } ?: "Boost no data"
            "FUEL" -> f.fuelLeftL?.let { "${count(it, "liter")} in the tank" } ?: f.fuelPct?.let { "${count(it, "percent", "percent")} in the tank" } ?: "No fuel data: mark a refill"
            "RANGE" -> if (f.rangeKm == null) "Range no data" else if (miles) "Enough for ${count(DistanceUnits.distance(f.rangeKm), "mile")}" else "Enough for ${count(f.rangeKm, "kilometer")}"
            "CONSUMPTION" -> if (f.per100 == null) "Consumption no data"
                else if (miles) "Consumption ${one(DistanceUnits.consumption(f.per100))} miles per gallon" else "Consumption ${one(f.per100)} liters per 100 kilometers"
            "TRIP" -> if (f.tripKm == null) "Trip no data" else if (miles) "Driven ${count(DistanceUnits.distance(f.tripKm), "mile")}" else "Driven ${count(f.tripKm, "kilometer")}"
            "TIME" -> f.time
            "VOLUME" -> "Volume ${f.volume ?: 0}"
            "DTC" -> "Opening codes"
            else -> "I don't know"
        }
    }

    // --- hints -----------------------------------------------------------------------------------

    override val SAMPLE_ASK = "what's the oil temperature"
    override val SAMPLE_PHRASE = "take me to the garage"
    override val SAMPLE_ACTION = "go to 10 Main Street; play music"
    override val STAR_WORD = "queen"

    override val OWN_TEMPLATES = listOf(
        Triple("YouTube clip", "show clip *", "play * clip on youtube"),
        Triple("Song on Spotify", "put on *", "play * on spotify"),
        Triple("Home with music", "take me home", "go home; play music"),
        Triple("Fuel station", "running low", "find the nearest gas station"),
        Triple("Quiet", "night mode", "volume 5"),
        Triple("Route to an address", "go to the garage", "go to 10 Main Street"),
    )

    override val OWN_POPULAR = listOf(
        "Route" to "go to 10 Main Street", "Home" to "go home", "YouTube" to "play * on youtube", "Music" to "play music",
    )

    override val OWN_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Navigation" to listOf(
            "Route to an address" to "go to 10 Main Street", "Route where I say" to "navigate to *", "Via" to "go to the airport via downtown",
            "Home" to "go home", "To work" to "go to work", "Ask where to" to "navigate",
            "Find on the map" to "find Main Street", "Time to arrival" to "how long to go", "Traffic" to "show traffic",
            "Cancel route" to "cancel the route", "Where am I" to "where am i", "Remember home" to "remember this place as home",
            "Remember work" to "remember this place as work", "Navigator voice off" to "turn off the navigator voice",
            "Navigator voice on" to "turn on the navigator voice", "Open navigator" to "open navigator",
            "Navigator full screen" to "navigator full screen", "In 2GIS" to "go home in 2gis",
            "In Yandex Maps" to "open yandex maps", "In Google Maps" to "open google maps",
        ),
        "Nearby" to listOf(
            "Gas station" to "find the nearest gas station", "LPG" to "find the nearest lpg station", "EV charging" to "find a charging station",
            "Tire shop" to "find a tire shop", "Car wash" to "find a car wash", "Car service" to "find a car service",
            "Subaru dealer" to "find a subaru dealer", "Parking" to "find parking", "Auto parts" to "find auto parts",
            "Coffee" to "find a coffee shop", "Fast food" to "find fast food", "Restaurant" to "find a restaurant", "Cafe" to "find a cafe",
            "Pharmacy" to "find a pharmacy", "Hospital" to "find a hospital", "ATM" to "find an atm", "Bank" to "find a bank",
            "Supermarket" to "find a supermarket", "Shopping mall" to "find a shopping mall", "Market" to "find a market",
            "Restroom" to "find a restroom", "Hotel" to "find a hotel", "Police" to "find the police", "Post office" to "find a post office",
            "Mosque" to "find a mosque", "Church" to "find a church", "Cinema" to "find a cinema",
        ),
        "Music" to listOf(
            "Play" to "play music", "Pause" to "pause", "Next" to "next track", "Previous" to "previous track",
            "From the start" to "start over", "Back 30 s" to "back 30 seconds", "Forward 30 s" to "forward 30 seconds",
            "Shuffle" to "shuffle", "In order" to "turn off shuffle", "Repeat" to "turn on repeat",
            "No repeat" to "turn off repeat", "Like" to "i like this", "Dislike" to "i don't like this",
            "What's playing" to "what's playing", "Song" to "play the song *", "Artist" to "play the artist *",
            "Album" to "play the album *", "Playlist" to "play the playlist *", "Favorites" to "play my favorites",
            "Podcast" to "play the podcast *", "Yandex Music" to "play * in yandex music", "Spotify" to "play * on spotify",
            "VK" to "play * in vk music", "YouTube" to "play * on youtube", "YouTube Music" to "play * on youtube music",
            "Open player" to "open spotify", "Bluetooth" to "turn on bluetooth", "USB" to "play from usb",
        ),
        "Volume" to listOf(
            "Louder" to "louder", "Quieter" to "quieter", "Volume 15" to "volume 15", "Volume 50%" to "volume 50 percent",
            "Mute" to "turn off the sound", "Unmute" to "turn on the sound",
        ),
        "Radio" to listOf(
            "Radio" to "turn on the radio", "Frequency" to "radio 101.7", "Station number" to "play the third station",
            "Station by name" to "play radio kiss fm", "Next" to "next station", "Previous" to "previous station",
            "Search station" to "search for a station", "FM" to "switch band to fm", "AM" to "switch to am",
        ),
        "Values" to listOf(
            "Engine" to "what's the coolant temperature", "Oil" to "what's the oil temperature", "Oil pressure" to "what's the oil pressure",
            "Outside" to "what's the temperature outside", "Intake" to "what's the intake temperature", "Transmission" to "what's the transmission temperature",
            "Fuel" to "how much fuel do i have", "Range" to "what's the range", "Consumption" to "what's the consumption",
            "Voltage" to "what's the voltage", "Speed" to "what's my speed", "RPM" to "what's the rpm", "Boost" to "what's the boost",
            "Codes" to "any trouble codes", "Trip" to "how far have i driven", "Time" to "what time is it", "Volume" to "what's the volume",
        ),
        "Screens" to listOf(
            "Home" to "open the home screen", "Dynamics" to "show boost", "Fuel use" to "open fuel", "Transmission" to "open transmission",
            "Battery" to "open battery", "Codes" to "show codes", "Media" to "open media", "Full screen" to "full screen",
        ),
        "Apps" to listOf(
            "Open an app" to "open telegram", "Which I say" to "open *",
        ),
    )

    override val EXAMPLES = listOf(
        "go to 10 Main Street" to "route to an address",
        "take me home / go to work" to "home and work",
        "find the nearest gas station" to "gas station, tire shop, parking, cafe, pharmacy…",
        "how long to go / show traffic / cancel the route" to "navigator",
        "louder / quieter / volume 15 / mute" to "volume",
        "next track / pause / play / shuffle / what's playing" to "music",
        "play the band Queen / play rock / play my favorites" to "music search",
        "radio / next station / search for a station" to "head unit radio",
        "navigator / full screen / open telegram" to "launch buttons and apps",
        "remember this place as home" to "so that “go home” works later",
        "what's the coolant temperature / how much fuel / voltage" to "values",
        "show boost / open fuel / open battery / show codes" to "screens",
    )

    // --- self-test -------------------------------------------------------------------------------

    private class Case(
        val text: String, val intent: VoiceIntent?, val query: String? = null, val number: Int? = null,
        val target: String? = null, val freq: Double? = null, val focus: MediaFocus? = null, val via: String? = null, val app: String? = null,
    )

    private val CASES = listOf(
        // navigation
        Case("go to ten main street", VoiceIntent.NAV_ROUTE, query = "10 main street"),
        Case("Navigate to Manas airport, please", VoiceIntent.NAV_ROUTE, query = "manas airport"),
        Case("take me to the airport via downtown", VoiceIntent.NAV_ROUTE, query = "airport", via = "downtown"),
        Case("how do i get to the central market square", VoiceIntent.NAV_ROUTE, query = "central market square"),
        Case("take me home", VoiceIntent.NAV_HOME),
        Case("let's go to work", VoiceIntent.NAV_WORK),
        Case("home", VoiceIntent.NAV_HOME),
        Case("to work", VoiceIntent.NAV_WORK),
        Case("navigate", VoiceIntent.NAV_ROUTE),
        Case("let's go", VoiceIntent.NAV_ROUTE),
        Case("i need to go to the airport", VoiceIntent.NAV_ROUTE, query = "airport"),
        Case("find the nearest gas station", VoiceIntent.NAV_NEARBY, target = "Gas station"),
        Case("where's the closest pharmacy", VoiceIntent.NAV_NEARBY, target = "Pharmacy"),
        Case("go to a gas station", VoiceIntent.NAV_NEARBY, target = "Gas station"),
        Case("find a tire shop", VoiceIntent.NAV_NEARBY, target = "Tire shop"),
        Case("i'm hungry", VoiceIntent.NAV_NEARBY, target = "Cafe"),
        Case("where can i park", VoiceIntent.NAV_NEARBY, target = "Parking"),
        Case("find a shell gas station", VoiceIntent.NAV_NEARBY, query = "shell gasstation", target = "Gas station"),
        Case("go to cafe pushkin", VoiceIntent.NAV_ROUTE, query = "cafe pushkin"),
        Case("find main street one twenty", VoiceIntent.NAV_SEARCH),
        Case("navigate home in two gis", VoiceIntent.NAV_HOME, app = "ru.dublgis.dgismobile"),
        Case("how long to go", VoiceIntent.NAV_ETA),
        Case("when will we arrive", VoiceIntent.NAV_ETA),
        Case("cancel the route", VoiceIntent.NAV_CANCEL),
        Case("show traffic", VoiceIntent.NAV_TRAFFIC),
        Case("how's the traffic", VoiceIntent.NAV_TRAFFIC),
        Case("mute the navigator voice", VoiceIntent.NAV_SOUND_OFF),
        Case("where am i", VoiceIntent.NAV_WHERE),
        Case("open navigator", VoiceIntent.NAV_OPEN),
        Case("open yandex maps", VoiceIntent.NAV_OPEN, app = "ru.yandex.yandexmaps"),
        Case("remember this place as home", VoiceIntent.NAV_SAVE_PLACE, target = "home"),
        // volume
        Case("louder", VoiceIntent.VOLUME_UP),
        Case("turn it up", VoiceIntent.VOLUME_UP),
        Case("raise the volume", VoiceIntent.VOLUME_UP),
        Case("a little quieter", VoiceIntent.VOLUME_DOWN, number = 1),
        Case("louder by five", VoiceIntent.VOLUME_UP, number = 5),
        Case("turn the volume down by three", VoiceIntent.VOLUME_DOWN, number = 3),
        Case("volume fifteen", VoiceIntent.VOLUME_SET, number = 15),
        Case("set the volume to twenty five", VoiceIntent.VOLUME_SET, number = 25),
        Case("volume to the max", VoiceIntent.VOLUME_SET, number = 100),
        Case("volume fifty percent", VoiceIntent.VOLUME_SET, number = 50),
        Case("mute", VoiceIntent.MUTE),
        Case("turn the sound on", VoiceIntent.UNMUTE),
        Case("it's too loud", VoiceIntent.VOLUME_DOWN),
        // radio
        Case("turn on the radio", VoiceIntent.RADIO),
        Case("radio one oh one point seven", VoiceIntent.RADIO_FREQ, freq = 101.7),
        Case("tune to ninety eight point eight", VoiceIntent.RADIO_FREQ, freq = 98.8),
        Case("fm one hundred two", VoiceIntent.RADIO_FREQ, freq = 102.0),
        Case("one hundred one point seven", VoiceIntent.RADIO_FREQ, freq = 101.7),
        Case("station three", VoiceIntent.RADIO_PRESET, number = 3),
        Case("play the third station", VoiceIntent.RADIO_PRESET, number = 3),
        Case("next station", VoiceIntent.RADIO_NEXT),
        Case("search for a station", VoiceIntent.RADIO_SEEK, number = 1),
        Case("play radio kiss fm", VoiceIntent.RADIO_NAME, query = "kiss fm"),
        Case("switch to am", VoiceIntent.RADIO_BAND, target = "AM"),
        // media
        Case("next track", VoiceIntent.NEXT),
        Case("skip", VoiceIntent.NEXT),
        Case("play the next song", VoiceIntent.NEXT),
        Case("another song", VoiceIntent.NEXT),
        Case("previous", VoiceIntent.PREV),
        Case("go back", VoiceIntent.PREV),
        Case("pause", VoiceIntent.PAUSE),
        Case("stop", VoiceIntent.PAUSE),
        Case("turn off the music", VoiceIntent.PAUSE),
        Case("play", VoiceIntent.PLAY),
        Case("resume", VoiceIntent.PLAY),
        Case("play music", VoiceIntent.PLAY),
        Case("play something", VoiceIntent.PLAY),
        Case("start over", VoiceIntent.RESTART),
        Case("shuffle", VoiceIntent.SHUFFLE_ON),
        Case("turn off shuffle", VoiceIntent.SHUFFLE_OFF),
        Case("repeat this song", VoiceIntent.REPEAT_ON, target = "one"),
        Case("fast forward thirty seconds", VoiceIntent.FORWARD, number = 30),
        Case("go back thirty seconds", VoiceIntent.REWIND, number = 30),
        Case("what's playing", VoiceIntent.WHAT_PLAYING),
        Case("who sings this", VoiceIntent.WHAT_PLAYING),
        Case("like this song", VoiceIntent.LIKE),
        Case("play the song yesterday", VoiceIntent.PLAY_QUERY, query = "yesterday", focus = MediaFocus.TRACK),
        Case("play the band queen", VoiceIntent.PLAY_QUERY, query = "queen", focus = MediaFocus.ARTIST),
        Case("play songs by queen", VoiceIntent.PLAY_QUERY, query = "queen", focus = MediaFocus.ARTIST),
        Case("play rock music", VoiceIntent.PLAY_QUERY, query = "rock", focus = MediaFocus.GENRE),
        Case("play the playlist road trip", VoiceIntent.PLAY_QUERY, query = "road trip", focus = MediaFocus.PLAYLIST),
        Case("find the song back in black", VoiceIntent.PLAY_QUERY, query = "back in black", focus = MediaFocus.TRACK),
        Case("play back in black", VoiceIntent.PLAY_QUERY, query = "back in black"),
        Case("play the doors", VoiceIntent.PLAY_QUERY, query = "the doors".substringAfter(' ')),
        Case("play my favorites", VoiceIntent.PLAY_QUERY, focus = MediaFocus.FAVORITES),
        Case("play queen on spotify", VoiceIntent.PLAY_QUERY, query = "queen", app = "com.spotify.music"),
        Case("open yandex music", VoiceIntent.MEDIA_APP, app = "ru.yandex.music"),
        Case("turn on bluetooth", VoiceIntent.SOURCE_BT),
        Case("music from usb", VoiceIntent.SOURCE_USB),
        // values, screens, dialog
        Case("what's the coolant temperature", VoiceIntent.READ_VALUE, target = "COOLANT"),
        Case("what's the oil temperature", VoiceIntent.READ_VALUE, target = "OIL"),
        Case("what's the oil pressure", VoiceIntent.READ_VALUE, target = "OILP"),
        Case("how much fuel", VoiceIntent.READ_VALUE, target = "FUEL"),
        Case("how much gas is left", VoiceIntent.READ_VALUE, target = "FUEL"),
        Case("how far can i go", VoiceIntent.READ_VALUE, target = "RANGE"),
        Case("what's the voltage", VoiceIntent.READ_VALUE, target = "BATTERY"),
        Case("what's my consumption", VoiceIntent.READ_VALUE, target = "CONSUMPTION"),
        Case("are there any errors", VoiceIntent.READ_VALUE, target = "DTC"),
        Case("what time is it", VoiceIntent.READ_VALUE, target = "TIME"),
        Case("what's the boost", VoiceIntent.READ_VALUE, target = "BOOST"),
        Case("show boost", VoiceIntent.SCREEN, target = "DRIVE"),
        Case("open fuel", VoiceIntent.SCREEN, target = "FUEL"),
        Case("consumption", VoiceIntent.SCREEN, target = "FUEL"),
        Case("open the gearbox", VoiceIntent.SCREEN, target = "GEARBOX"),
        Case("show codes", VoiceIntent.SCREEN, target = "DTC"),
        Case("battery", VoiceIntent.SCREEN, target = "BATTERY"),
        Case("go to the home screen", VoiceIntent.SCREEN, target = "HOME"),
        Case("full screen", VoiceIntent.FULL_SCREEN),
        Case("cancel", VoiceIntent.CANCEL),
        Case("never mind", VoiceIntent.CANCEL),
        Case("what can you do", VoiceIntent.HELP),
        Case("turn on the ac", VoiceIntent.UNSUPPORTED, target = "climate"),
        // the meaning, said in other words
        Case("i'm bored", VoiceIntent.PLAY),
        Case("put on some tunes", VoiceIntent.PLAY),
        Case("i hate this song", VoiceIntent.DISLIKE),
        Case("i can't hear it", VoiceIntent.VOLUME_UP),
        Case("running low on gas", VoiceIntent.NAV_NEARBY, target = "Gas station"),
        Case("the check engine light is on", VoiceIntent.SCREEN, target = "DTC"),
        Case("volumne twenty", VoiceIntent.VOLUME_SET, number = 20),
        Case("open telegram", VoiceIntent.APP_OPEN, query = "telegram"),
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
                (c.focus == null || r.focus == c.focus) && (c.via == null || r.via == c.via) && (c.app == null || r.app == c.app)
            if (!ok) bad += "“${c.text}” → $r (expected ${c.intent} ${listOfNotNull(c.query, c.number, c.target, c.freq, c.focus, c.via, c.app)})"
        }
        val wake = listOf("assistant louder", "assistance louder", "assistants louder")
        for (p in wake) if (Commands.wakeEnd(p.split(' '), WAKE_WORD) != p.split(' ').size - 1) bad += "name not recognised: “$p”"
        val own = listOf(
            OwnCommand("go to the garage", "go to 10 Main Street; play music"),
            OwnCommand("disco", "play radio kiss fm"),
            OwnCommand("gas", "louder"),
            OwnCommand("show clip *", "play * clip on youtube"),
        )
        val ownCases = listOf(
            "go to the garage" to "go to the garage", "please go to the garage now" to "go to the garage",
            "disco" to "disco", "play disco" to "disco", "gas" to "gas", "gasoline home" to null,
            "go home" to null, "show clip bohemian rhapsody" to "show clip *", "show clip" to null,
        )
        for ((said, want) in ownCases) {
            val got = ownFor(said, own)?.first?.phrase
            if (got != want) bad += "own command “$said” → $got (expected $want)"
        }
        if (ownFor("go to the garage", own)?.second != listOf("go to 10 Main Street", "play music")) bad += "own steps: ${ownFor("go to the garage", own)?.second}"
        val clip = ownFor("show clip bohemian rhapsody", own)?.second?.singleOrNull()
        val yt = clip?.let { parse(it) }
        if (clip != "play bohemian rhapsody clip on youtube" || yt?.intent != VoiceIntent.PLAY_QUERY || yt.app != "com.google.android.youtube")
            bad += "own command with a star: “$clip” → $yt"
        // every template and piece must be understood, and together the pieces must show everything the assistant can do
        val texts = OWN_TEMPLATES.flatMap { steps(it.third) } + OWN_POPULAR.map { it.second } + OWN_GROUPS.flatMap { g -> g.second.map { it.second } }
        val got = texts.mapNotNull { t -> parse(t.replace("*", STAR_WORD)).also { if (it == null) bad += "hint not understood: “$t”" } }
        val skip = setOf(VoiceIntent.CANCEL, VoiceIntent.HELP, VoiceIntent.UNSUPPORTED)
        (VoiceIntent.entries - skip - got.map { it.intent }.toSet()).forEach { bad += "no hint for $it" }
        val values = listOf("COOLANT", "OIL", "OILP", "OUTSIDE", "INTAKE", "ATF", "FUEL", "RANGE", "CONSUMPTION", "BATTERY", "SPEED", "RPM", "BOOST", "DTC", "TRIP", "TIME", "VOLUME")
        (values - got.filter { it.intent == VoiceIntent.READ_VALUE }.mapNotNull { it.target }.toSet()).forEach { bad += "no hint for the value $it" }
        (SCREEN_NAMES.keys - got.filter { it.intent == VoiceIntent.SCREEN }.mapNotNull { it.target }.toSet()).forEach { bad += "no hint for the screen $it" }
        (PLACES.map { it.first } - got.filter { it.intent == VoiceIntent.NAV_NEARBY }.mapNotNull { it.target }.toSet()).forEach { bad += "no hint for the place $it" }
        if (PLACES.size != Commands.PLACES.size) bad += "places: ${PLACES.size} against ${Commands.PLACES.size} in Russian"
        return bad
    }

    /**
     * The name suggested until the driver gives his own: of the names tried on the real model it came through in every
     * voice ("carpulse" and "copilot" did not), and it's rarely said in a car by itself.
     */
    const val WAKE_WORD = "assistant"
}
