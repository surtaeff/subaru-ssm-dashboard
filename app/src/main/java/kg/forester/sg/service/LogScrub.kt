package kg.forester.sg.service

/**
 * Takes what is private out of a log before it leaves the unit (the upload and the preview before it, one text for
 * both). What stays: ROM ID, capability bytes, SSM frames, errors, versions.
 *
 * Two ways at once, since the log is free text: the values the app knows are private ([known]: the VIN, the car's
 * name, the adapter's address) are cut out wherever they are, and the shapes of private things (a VIN, a MAC, an
 * IP address, coordinates, what was said, track names, street names) go by pattern. A pattern is a net, not a proof:
 * a new log line with a new private thing has to be added here (see the SsmTrace.log audit of Task 16).
 */
object LogScrub {
    const val HIDDEN = "[скрыто]"

    private val rules: List<Pair<Regex, String>> = listOf(
        // the car's name in the header
        Regex("Машина: «[^»\\n]*»") to "Машина: «$HIDDEN»",
        // every line that says VIN: the value goes
        Regex("(?i)(\\bVIN\\b[^:\\n]*:[ \\t]*).*") to "$1[VIN скрыт]",
        // the answer to OBD 09 02 in any form (ELM lines with 0:/1: parts, K-line, CAN): the whole answer
        Regex("(?m)^([ \\t]*\\S+ (?:<<|K RX|CAN RX|RX)(?=\\s)).*\\b49 ?02 ?0[0-9A-Fa-f].*$") to "$1 [ответ с VIN скрыт]",
        // 17 ASCII letters and digits in a row in a hex dump (the VIN read from SSM memory, or inside any frame)
        Regex("\\b(?:(?:3[0-9]|4[1-8A-E]|50|5[2-9A]) ){16}(?:3[0-9]|4[1-8A-E]|50|5[2-9A])\\b") to "[VIN]",
        // a VIN in text
        Regex("\\b(?=[A-HJ-NPR-Z0-9]{0,16}\\d)(?=[A-HJ-NPR-Z0-9]{0,16}[A-HJ-NPR-Z])[A-HJ-NPR-Z0-9]{17}\\b") to "[VIN]",
        // addresses of Bluetooth devices (adapter, tyre sensors), IPv4 of a Wi-Fi adapter or a router
        Regex("\\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\\b") to "[MAC]",
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b") to "[IP]",
        // places
        Regex("-?\\d{1,3}[.,]\\d{4,}(?:\\s*[;,]\\s*|\\s+)-?\\d{1,3}[.,]\\d{4,}") to "[GPS]",
        Regex("(?i)\\b(lat|lon|lng|latitude|longitude)\\b[\"']?\\s*[=:]?\\s*-?\\d+(?:[.,]\\d+)?") to "$1=[GPS]",
        // what was said to the voice control or recognised
        Regex("(?im)^([ \\t]*\\S+ (?:voice|speech|asr|stt|голос)[^:\\n]*\\b(?:heard|text|said|phrase|result|recogni[sz]ed|transcript|сказал\\w*|услышал\\w*)[^:\\n]*:).*$") to "$1 $HIDDEN",
        // tracks: a player error keeps its code, the media id goes; the music index errors carry file names
        Regex("(?m)^([ \\t]*\\S+ player: \\S+) .*$") to "$1 [трек скрыт]",
        Regex("(?m)^([ \\t]*\\S+ music (?:search )?index:).*$") to "$1 $HIDDEN",
        Regex("(?im)^([ \\t]*\\S+ (?:now playing|track|title|artist|album|трек|исполнитель)[^:\\n]*:).*$") to "$1 $HIDDEN",
        // the navigator's wording of a manoeuvre has street names; the icon's name stays
        Regex("(?m)^([ \\t]*\\S+ 2GIS (?:manoeuvre icon|barrier) «[^»\\n]*»):.*$") to "$1: [описание скрыто]",
        // the chip id of an ESP32 screen is the unit's own number
        Regex("(?m)^([ \\t]*\\S+ screen: .*\\bid )\\S*$") to "$1[ид платы скрыт]",
        // files of the user
        Regex("(?:/storage|/sdcard|/mnt|/data/media)/.*") to "[путь]",
    )

    private val keysOnly = Regex("([^,=\\s][^,=]*)=[^,\\n]*")

    /** [known]: values the app knows are private (VIN, car name, addresses); cut out wherever they stand, any case. */
    fun scrub(text: String, known: Set<String> = emptySet()): String {
        var s = text
        // the long ones first, so a part of a long value does not leave the rest behind
        for (k in known.map { it.trim() }.filter { it.length >= 3 }.sortedByDescending { it.length }) {
            s = Regex(Regex.escape(k), RegexOption.IGNORE_CASE).replace(s, Regex.escapeReplacement(HIDDEN))
        }
        // broadcasts of players carry titles in their extras: the keys stay, the values go
        s = broadcast.replace(s) { m -> m.groupValues[1] + keysOnly.findAll(m.groupValues[2]).joinToString(",") { it.groupValues[1].trim() + "=…" } }
        for ((re, to) in rules) s = re.replace(s, to)
        return s
    }

    private val broadcast = Regex("(?m)^([ \\t]*\\S+ bt: broadcast \\S+ )(.*)$")
}
